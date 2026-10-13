package ai.arena.qatar.world;

/**
 * Walls, fences, hedges and retaining walls (bundle {@code QBA1}), as polylines with a height.
 *
 * <p>These are what make a compound a compound: without them the world would be a field of isolated
 * boxes. The mesher gives each one a thin extruded volume, and the physics layer will use the same
 * lines as static collision geometry.
 */
public final class Barriers {

    public static final int KIND_WALL = 0;
    public static final int KIND_FENCE = 1;
    public static final int KIND_HEDGE = 2;
    public static final int KIND_RETAINING = 3;
    public static final int KIND_GATE = 4;

    public final int count;
    public final int[] lineStart;
    public final int[] lineLength;
    public final float[] xz;
    public final byte[] kind;
    public final byte[] flags;
    public final float[] heightM;
    public final int[] nameIndex;
    public final float[] bounds;

    public Barriers(int count, int[] lineStart, int[] lineLength, float[] xz, byte[] kind,
                    byte[] flags, float[] heightM, int[] nameIndex, float[] bounds) {
        this.count = count;
        this.lineStart = lineStart;
        this.lineLength = lineLength;
        this.xz = xz;
        this.kind = kind;
        this.flags = flags;
        this.heightM = heightM;
        this.nameIndex = nameIndex;
        this.bounds = bounds;
    }

    /** Parses {@code QBA1} into grid-origin relative metres. */
    public static Barriers parse(BundleReader reader, double originEast, double originNorth) {
        BundleReader.Header header = reader.header("QBA1");
        int count = header.count;
        int[] lineLength = new int[count];
        int totalVertices = 0;
        for (int line = 0; line < count; line++) {
            reader.skip(8);
            int points = reader.readUnsignedShort();
            reader.skip(6);                                     // kind, flags, height, name
            lineLength[line] = points;
            totalVertices += points;
            reader.skip((points - 1) * 8);
        }

        int[] lineStart = new int[count];
        float[] xz = new float[totalVertices * 2];
        byte[] kind = new byte[count];
        byte[] flags = new byte[count];
        float[] heightM = new float[count];
        int[] nameIndex = new int[count];
        float[] bounds = new float[count * 4];

        reader.seek(20);
        int vertex = 0;
        for (int line = 0; line < count; line++) {
            float east = reader.readInt() / 100.0f;
            float north = reader.readInt() / 100.0f;
            int points = reader.readUnsignedShort();
            kind[line] = (byte) reader.readUnsignedByte();
            flags[line] = (byte) reader.readUnsignedByte();
            heightM[line] = reader.readUnsignedShort() / 10.0f;
            nameIndex[line] = reader.readUnsignedShort();

            lineStart[line] = vertex;
            // Initialised inside the loop, after the origin is subtracted. Seeded from the absolute
            // first vertex instead, the box mixed EPSG metres with bundle metres: east 553,100 to 100.
            // Every chunk test then said "not here", so no barrier in the whole country was ever drawn.
            float minE = Float.MAX_VALUE;
            float maxE = -Float.MAX_VALUE;
            float minN = Float.MAX_VALUE;
            float maxN = -Float.MAX_VALUE;
            for (int i = 0; i < points; i++) {
                if (i > 0) {
                    east += reader.readInt() / 100.0f;
                    north += reader.readInt() / 100.0f;
                } else {
                    east -= (float) originEast;
                    north -= (float) originNorth;
                }
                xz[vertex * 2] = east;
                xz[vertex * 2 + 1] = north;
                vertex++;
                minE = Math.min(minE, east);
                maxE = Math.max(maxE, east);
                minN = Math.min(minN, north);
                maxN = Math.max(maxN, north);
            }
            bounds[line * 4] = minE;
            bounds[line * 4 + 1] = minN;
            bounds[line * 4 + 2] = maxE;
            bounds[line * 4 + 3] = maxN;
        }
        return new Barriers(count, lineStart, lineLength, xz, kind, flags, heightM, nameIndex, bounds);
    }
}
