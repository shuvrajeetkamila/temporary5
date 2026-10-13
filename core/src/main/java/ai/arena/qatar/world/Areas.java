package ai.arena.qatar.world;

/**
 * Areal features: land use ({@code QLU1}) and water ({@code QWA1}). Both use the same record layout,
 * so one parser serves both and the two bundles can never drift apart.
 *
 * <p>Land use drives terrain colour, vegetation and surface friction; water polygons carve the
 * terrain mesh and get a water surface. Class ids are the shared surface material ids from the
 * compiler, so a land use polygon and a terrain cell are two views of one classification.
 */
public final class Areas {

    /** Kind values in a {@code QLU1} bundle: an ordinary surface polygon. */
    public static final int KIND_SURFACE = 0;
    /** Kind values in a {@code QLU1} bundle: vegetation to scatter. */
    public static final int KIND_VEGETATION = 1;
    /** {@code QWA1} kinds: sea level water, inland water body, wadi (usually dry). */
    public static final int WATER_SEA = 0;
    public static final int WATER_INLAND = 1;
    public static final int WATER_WADI = 2;

    public final int count;
    public final int[] ringStart;
    public final int[] ringLength;
    public final float[] xz;
    public final byte[] areaClass;
    public final byte[] kind;
    public final byte[] flags;
    public final int[] nameIndex;
    public final float[] bounds;

    public Areas(int count, int[] ringStart, int[] ringLength, float[] xz, byte[] areaClass,
                 byte[] kind, byte[] flags, int[] nameIndex, float[] bounds) {
        this.count = count;
        this.ringStart = ringStart;
        this.ringLength = ringLength;
        this.xz = xz;
        this.areaClass = areaClass;
        this.kind = kind;
        this.flags = flags;
        this.nameIndex = nameIndex;
        this.bounds = bounds;
    }

    public static Areas parse(BundleReader reader, String magic) {
        BundleReader.Header header = reader.header(magic);
        int count = header.count;
        int[] ringLength = new int[count];
        int totalVertices = 0;
        for (int ring = 0; ring < count; ring++) {
            reader.skip(8);                                     // absolute first vertex
            int points = reader.readUnsignedShort();
            reader.skip(6);                                     // class, kind, flags, padding, name
            ringLength[ring] = points;
            totalVertices += points;
            reader.skip((points - 1) * 8);
        }

        int[] ringStart = new int[count];
        float[] xz = new float[totalVertices * 2];
        byte[] areaClass = new byte[count];
        byte[] kind = new byte[count];
        byte[] flags = new byte[count];
        int[] nameIndex = new int[count];
        float[] bounds = new float[count * 4];

        reader.seek(20);
        int vertex = 0;
        for (int ring = 0; ring < count; ring++) {
            double east = reader.readInt() / 100.0;
            double north = reader.readInt() / 100.0;
            int points = reader.readUnsignedShort();
            areaClass[ring] = (byte) reader.readUnsignedByte();
            kind[ring] = (byte) reader.readUnsignedByte();
            flags[ring] = (byte) reader.readUnsignedByte();
            reader.readUnsignedByte();                          // padding
            nameIndex[ring] = reader.readUnsignedShort();

            ringStart[ring] = vertex;
            double minE = east;
            double maxE = east;
            double minN = north;
            double maxN = north;
            for (int i = 0; i < points; i++) {
                if (i > 0) {
                    east += reader.readInt() / 100.0;
                    north += reader.readInt() / 100.0;
                }
                xz[vertex * 2] = (float) east;
                xz[vertex * 2 + 1] = (float) north;
                vertex++;
                minE = Math.min(minE, east);
                maxE = Math.max(maxE, east);
                minN = Math.min(minN, north);
                maxN = Math.max(maxN, north);
            }
            bounds[ring * 4] = (float) minE;
            bounds[ring * 4 + 1] = (float) minN;
            bounds[ring * 4 + 2] = (float) maxE;
            bounds[ring * 4 + 3] = (float) maxN;
        }
        return new Areas(count, ringStart, ringLength, xz, areaClass, kind, flags, nameIndex, bounds);
    }

    /** Signed area of a ring in square metres (absolute value, ring order agnostic). */
    public double areaM2(int ring) {
        int start = ringStart[ring];
        int length = ringLength[ring];
        double twice = 0;
        for (int i = 0; i < length; i++) {
            int a = start + i;
            int b = start + ((i + 1) % length);
            twice += xz[a * 2] * (double) xz[b * 2 + 1] - xz[b * 2] * (double) xz[a * 2 + 1];
        }
        return Math.abs(twice) * 0.5;
    }

    public boolean contains(int ring, double east, double north) {
        int start = ringStart[ring];
        int length = ringLength[ring];
        boolean inside = false;
        for (int i = 0, j = length - 1; i < length; j = i++) {
            double xi = xz[(start + i) * 2];
            double zi = xz[(start + i) * 2 + 1];
            double xj = xz[(start + j) * 2];
            double zj = xz[(start + j) * 2 + 1];
            if ((zi > north) != (zj > north)
                    && east < (xj - xi) * (north - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }
}
