package ai.arena.qatar.world;

/**
 * The road network (bundle {@code QTR1}, shared with the world compiler's stage 1).
 *
 * <p>Each record is a polyline with its class, lane count, width, speed limit, one-way and
 * bridge/tunnel flags, and English/Arabic name indices. The mesher turns these into drivable ribbons
 * with lane markings; the traffic system will later follow the same polylines, so the geometry the
 * player sees and the geometry vehicles drive are literally the same data.
 */
public final class Roads {

    public static final int FLAG_BRIDGE = 1;
    public static final int FLAG_TUNNEL = 2;
    public static final int FLAG_ONEWAY = 4;

    public final int count;
    public final int[] lineStart;
    public final int[] lineLength;
    public final float[] xz;          // 2 floats per vertex, grid-origin relative metres
    public final byte[] roadClass;
    public final byte[] flags;
    public final byte[] lanes;
    public final float[] widthM;
    public final int[] speedKph;
    public final int[] nameIndex;
    public final int[] nameArIndex;
    public final float[] bounds;      // 4 floats per line

    public Roads(int count, int[] lineStart, int[] lineLength, float[] xz, byte[] roadClass,
                 byte[] flags, byte[] lanes, float[] widthM, int[] speedKph, int[] nameIndex,
                 int[] nameArIndex, float[] bounds) {
        this.count = count;
        this.lineStart = lineStart;
        this.lineLength = lineLength;
        this.xz = xz;
        this.roadClass = roadClass;
        this.flags = flags;
        this.lanes = lanes;
        this.widthM = widthM;
        this.speedKph = speedKph;
        this.nameIndex = nameIndex;
        this.nameArIndex = nameArIndex;
        this.bounds = bounds;
    }

    public boolean isOneWay(int line) {
        return (flags[line] & FLAG_ONEWAY) != 0;
    }

    public boolean isBridge(int line) {
        return (flags[line] & FLAG_BRIDGE) != 0;
    }

    public boolean isTunnel(int line) {
        return (flags[line] & FLAG_TUNNEL) != 0;
    }

    /** Approximate centre-line length in metres. */
    public double lengthM(int line) {
        int start = lineStart[line];
        int length = lineLength[line];
        double total = 0;
        for (int i = 1; i < length; i++) {
            float dx = xz[(start + i) * 2] - xz[(start + i - 1) * 2];
            float dz = xz[(start + i) * 2 + 1] - xz[(start + i - 1) * 2 + 1];
            total += Math.sqrt(dx * dx + dz * dz);
        }
        return total;
    }

    public static Roads parse(BundleReader reader) {
        BundleReader.Header header = reader.header("QTR1");
        int count = header.count;
        int[] lineStart = new int[count];
        int[] lineLength = new int[count];

        // Pass one: how many vertices, so the array is allocated once.
        int totalVertices = 0;
        for (int line = 0; line < count; line++) {
            byte roadClass = (byte) reader.readUnsignedByte();
            byte flags = (byte) reader.readUnsignedByte();
            byte lanes = (byte) reader.readUnsignedByte();
            byte speed5 = (byte) reader.readUnsignedByte();
            reader.skip(2 + 2 + 2);                            // width, name, arabic name
            int points = reader.readUnsignedShort();
            lineLength[line] = points;
            totalVertices += points;
            reader.skip(points * 8);
            if (roadClass == 0 && flags == 0 && lanes == 0) {
                throw new IllegalStateException("road " + line + " has an implausible class header");
            }
        }

        float[] xz = new float[totalVertices * 2];
        byte[] roadClass = new byte[count];
        byte[] flags = new byte[count];
        byte[] lanes = new byte[count];
        float[] widthM = new float[count];
        int[] speedKph = new int[count];
        int[] nameIndex = new int[count];
        int[] nameArIndex = new int[count];
        float[] bounds = new float[count * 4];

        reader.seek(20);
        int vertex = 0;
        for (int line = 0; line < count; line++) {
            roadClass[line] = (byte) reader.readUnsignedByte();
            flags[line] = (byte) reader.readUnsignedByte();
            lanes[line] = (byte) reader.readUnsignedByte();
            speedKph[line] = reader.readUnsignedByte() * 5;
            widthM[line] = reader.readUnsignedShort() / 10.0f;
            nameIndex[line] = reader.readUnsignedShort();
            nameArIndex[line] = reader.readUnsignedShort();
            int points = reader.readUnsignedShort();

            lineStart[line] = vertex;
            float minE = Float.MAX_VALUE;
            float maxE = -Float.MAX_VALUE;
            float minN = Float.MAX_VALUE;
            float maxN = -Float.MAX_VALUE;
            for (int i = 0; i < points; i++) {
                float east = reader.readInt() / 100.0f;
                float north = reader.readInt() / 100.0f;
                if (i > 0) {
                    east += xz[(vertex - 1) * 2];
                    north += xz[(vertex - 1) * 2 + 1];
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
        return new Roads(count, lineStart, lineLength, xz, roadClass, flags, lanes, widthM, speedKph,
                nameIndex, nameArIndex, bounds);
    }
}
