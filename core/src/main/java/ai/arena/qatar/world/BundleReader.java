package ai.arena.qatar.world;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;

/**
 * Reads one world bundle: inflates it, verifies its SHA-256 digest and walks its little-endian
 * records.
 *
 * <p>Every bundle is self-describing: a 4-byte magic, a version, a record count and the payload byte
 * length, followed by fixed-size headers and delta-encoded int32 centimetre coordinates. Coordinates
 * are returned as metres, in the EPSG:32639 national frame, as doubles; nothing here trusts a bundle
 * that fails its digest.
 */
public final class BundleReader {

    private final String name;
    private final byte[] payload;
    private int cursor;

    private BundleReader(String name, byte[] payload) {
        this.name = name;
        this.payload = payload;
    }

    /** Inflates a gzip bundle and checks the digest recorded in the manifest. */
    public static BundleReader open(String name, byte[] gzipBytes, String expectedSha256) {
        if (expectedSha256 != null && !expectedSha256.isEmpty()) {
            String actual = sha256(gzipBytes);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new IllegalStateException(name + ": SHA-256 mismatch (bundle is corrupt or tampered): "
                        + actual + " != " + expectedSha256);
            }
        }
        return new BundleReader(name, inflate(name, gzipBytes));
    }

    /** Opens an already-inflated payload (used by tests and by nested containers). */
    public static BundleReader ofRaw(String name, byte[] payload) {
        return new BundleReader(name, payload);
    }

    /**
     * Opens a bundle file that may arrive in either of the two shapes the same build produces.
     *
     * <p>In the repository, and in the tests, a bundle is a gzip member called {@code roads.bin.gz}. On a
     * phone it is neither: the Android asset tooling unpacks a {@code .gz} member at package time and
     * drops the suffix, so {@code assets/world/roads.bin} holds the inflated bytes under the shorter name.
     * The first device build died on exactly that -- {@code world bundle missing: strings.bin.gz} -- with
     * the whole file present in the APK, one extension away from being found.
     *
     * <p>So the rule is: if it is gzip, verify the manifest digest and inflate it; if it is already
     * inflated, use it, and say once that the digest could not be checked here, because the bytes that
     * were hashed are not the bytes that were packaged. Both are honest; neither is a silent lie.
     */
    public static BundleReader openPackaged(String name, byte[] bytes, String expectedSha256) {
        if (isGzip(bytes)) {
            return open(name, bytes, expectedSha256);
        }
        if (expectedSha256 != null && !expectedSha256.isEmpty()) {
            notePackaged(name);
        }
        return ofRaw(name, bytes);
    }

    /** True when the bytes begin with the gzip magic number, and so still need inflating. */
    public static boolean isGzip(byte[] bytes) {
        return bytes != null && bytes.length > 2
                && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
    }

    /** Says once per bundle which of the two shapes arrived, so a screenshot can settle the question. */
    private static void notePackaged(String name) {
        if (packagedNotes.add(name)) {
            ai.arena.qatar.boot.ScreenLog.GLOBAL.warn(name + " arrived inflated (the Android packager "
                    + "unpacks .gz assets): the manifest digest cannot be checked on this device");
        }
    }

    private static final java.util.Set<String> packagedNotes = new java.util.concurrent.ConcurrentSkipListSet<>();

    public static byte[] inflate(String name, byte[] gzipBytes) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipBytes), 1 << 16)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, gzipBytes.length * 3));
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(name + ": not a readable gzip bundle", e);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but missing", e);
        }
    }

    public String name() {
        return name;
    }

    /** The inflated payload (already digest-verified). */
    public byte[] payloadBytes() {
        return payload;
    }

    public int size() {
        return payload.length;
    }

    public int cursor() {
        return cursor;
    }

    public void skip(int bytes) {
        int next = cursor + bytes;
        if (next < 0 || next > payload.length) {
            throw new IllegalStateException(name + ": record runs past the end of the payload");
        }
        cursor = next;
    }

    public void seek(int offset) {
        if (offset < 0 || offset > payload.length) {
            throw new IllegalArgumentException(name + ": seek out of range " + offset);
        }
        cursor = offset;
    }

    /** Reads and validates the common container header. */
    public Header header(String expectedMagic) {
        String magic = new String(payload, 0, 4, StandardCharsets.US_ASCII);
        if (expectedMagic != null && !expectedMagic.equals(magic)) {
            throw new IllegalStateException(name + ": unexpected magic '" + magic + "', expected '"
                    + expectedMagic + "'");
        }
        cursor = 4;                 // step over the magic before reading the numeric header fields
        int version = readInt();
        long count = readUnsignedInt();
        long payloadBytes = readLong();
        long available = payload.length - 20L;
        if (payloadBytes != available) {
            throw new IllegalStateException(name + ": header claims " + payloadBytes
                    + " payload bytes, file has " + available);
        }
        return new Header(magic, version, (int) count, payloadBytes);   // cursor is at 20
    }

    public static final class Header {
        public final String magic;
        public final int version;
        public final int count;
        public final long payloadBytes;

        Header(String magic, int version, int count, long payloadBytes) {
            this.magic = magic;
            this.version = version;
            this.count = count;
            this.payloadBytes = payloadBytes;
        }
    }

    public int readUnsignedByte() {
        return payload[cursor++] & 0xFF;
    }

    public int readUnsignedShort() {
        int value = (payload[cursor] & 0xFF) | ((payload[cursor + 1] & 0xFF) << 8);
        cursor += 2;
        return value;
    }

    public int readInt() {
        int value = (payload[cursor] & 0xFF) | ((payload[cursor + 1] & 0xFF) << 8)
                | ((payload[cursor + 2] & 0xFF) << 16) | ((payload[cursor + 3] & 0xFF) << 24);
        cursor += 4;
        return value;
    }

    public long readUnsignedInt() {
        return readInt() & 0xFFFFFFFFL;
    }

    public long readLong() {
        long low = readUnsignedInt();
        long high = readUnsignedInt();
        return (high << 32) | low;
    }

    /** Delta-decodes {@code count} points into a metre-space ring, appending to {@code out}. */
    public void readPolyline(int count, double[] out, int outOffset) {
        double east = readInt() / 100.0;
        double north = readInt() / 100.0;
        out[outOffset] = east;
        out[outOffset + 1] = north;
        for (int i = 1; i < count; i++) {
            east += readInt() / 100.0;
            north += readInt() / 100.0;
            out[outOffset + i * 2] = east;
            out[outOffset + i * 2 + 1] = north;
        }
    }

    public static double centroidEast(double[] points, int count) {
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += points[i * 2];
        }
        return sum / count;
    }

    public static double centroidNorth(double[] points, int count) {
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += points[i * 2 + 1];
        }
        return sum / count;
    }
}
