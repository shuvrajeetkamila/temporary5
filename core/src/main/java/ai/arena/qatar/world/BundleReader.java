package ai.arena.qatar.world;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;

/**
 * Reads a compiled world bundle: one gzip member whose payload is a simple little-endian record stream.
 *
 * <p>Every bundle is checked against the digest the manifest recorded for it, because a bundle that loads
 * but is truncated is far worse than one that refuses to load: the first produces a world with a missing
 * city, the second produces an error message at the right moment.
 *
 * <p>Kept free of libGDX on purpose. The whole data layer runs in a plain JVM test: `WorldData` is
 * handed bytes, not file handles, so a unit test can load the same bundles the phone does.
 */
public final class BundleReader {

    private BundleReader() {
    }

    /** Inflates a gzip member; the compiled bundles are stored compressed on disk. */
    public static byte[] inflate(byte[] compressed) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed), 1 << 16)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("bundle is not a readable gzip member: " + e.getMessage(), e);
        }
    }

    /** SHA-256 as lowercase hex; the manifest stores the same string the compiler computed. */
    public static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from this JVM", e);
        }
    }

    /** Verifies a bundle payload against the manifest digest. */
    public static void verify(String name, byte[] payload, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isEmpty()) {
            return;                                  // an unsigned bundle is allowed while authoring
        }
        String actual = sha256(payload);
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw new IllegalStateException("bundle " + name + " digest mismatch: manifest "
                    + expectedSha256 + ", payload " + actual);
        }
    }

    /**
     * Opens a whole bundle: verifies the digest of the file it was read from, then inflates it and checks
     * the magic.
     *
     * <p>The digest covers the bytes on disk -- the compressed member -- because that is what the
     * compiler hashed and what a checksum file can be checked against without knowing the format.
     */
    public static Cursor open(String name, byte[] compressed, String expectedSha256, String magic) {
        verify(name, compressed, expectedSha256);
        byte[] payload = inflate(compressed);
        Cursor cursor = new Cursor(name, payload);
        String actual = cursor.ascii(4);
        if (!magic.equals(actual)) {
            throw new IllegalStateException("bundle " + name + " has magic '" + actual
                    + "', expected '" + magic + "'");
        }
        return cursor;
    }

    /** A forward-only little-endian cursor with bounds checks that name the bundle on failure. */
    public static final class Cursor {
        private final String name;
        private final byte[] data;
        private int position;

        Cursor(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }

        public int remaining() {
            return data.length - position;
        }

        public int position() {
            return position;
        }

        public void position(int next) {
            if (next < 0 || next > data.length) {
                throw new IllegalStateException(name + ": seek out of range: " + next
                        + " of " + data.length);
            }
            position = next;
        }

        private void require(int bytes) {
            if (remaining() < bytes) {
                throw new IllegalStateException(name + ": truncated at byte " + position
                        + " (needed " + bytes + ", have " + remaining() + ")");
            }
        }

        public byte[] bytes(int count) {
            require(count);
            byte[] out = new byte[count];
            System.arraycopy(data, position, out, 0, count);
            position += count;
            return out;
        }

        public String ascii(int count) {
            return new String(bytes(count), java.nio.charset.StandardCharsets.US_ASCII);
        }

        public int u8() {
            require(1);
            return data[position++] & 0xFF;
        }

        public int u16() {
            require(2);
            int value = (data[position] & 0xFF) | ((data[position + 1] & 0xFF) << 8);
            position += 2;
            return value;
        }

        public int i32() {
            require(4);
            int value = (data[position] & 0xFF) | ((data[position + 1] & 0xFF) << 8)
                    | ((data[position + 2] & 0xFF) << 16) | ((data[position + 3] & 0xFF) << 24);
            position += 4;
            return value;
        }

        public long i64() {
            require(8);
            long value = 0;
            for (int i = 7; i >= 0; i--) {
                value = (value << 8) | (data[position + i] & 0xFFL);
            }
            position += 8;
            return value;
        }

        public float f32() {
            return Float.intBitsToFloat(i32());
        }

        /** Header shared by the record-stream bundles: magic, version, count, payload bytes. */
        public long header(String magic, int expectedVersion) {
            Cursor self = this;
            int version = self.i32();
            if (version != expectedVersion) {
                throw new IllegalStateException(name + ": version " + version + ", expected "
                        + expectedVersion);
            }
            long count = self.i32() & 0xFFFFFFFFL;
            long payloadBytes = self.i64();
            if (payloadBytes < 0 || payloadBytes > remaining()) {
                throw new IllegalStateException(name + ": payload size " + payloadBytes
                        + " does not fit in " + remaining() + " remaining bytes");
            }
            return count | (payloadBytes << 32);
        }

        public static int count(long header) {
            return (int) (header & 0xFFFFFFFFL);
        }
    }
}
