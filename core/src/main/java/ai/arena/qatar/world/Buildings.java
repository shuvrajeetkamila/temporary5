package ai.arena.qatar.world;

/**
 * Every building in the country, as real footprint rings (bundle {@code QBG1}).
 *
 * <p>Qatar has roughly 190,000 mapped buildings, so the storage is flat primitive arrays rather than
 * one object per building: an object per building would cost more in headers than in geometry. Rings
 * live in one shared vertex array; each record indexes it.
 *
 * <p>Coordinates are metres <b>relative to the manifest grid origin</b> (the country spans 259 km, so
 * a float still resolves to ~1.6 cm). Meshers subtract the chunk origin from these to get small local
 * floats, which keeps a triangle stable no matter where the player is in the country.
 */
public final class Buildings {

    /** Roof silhouettes, matching {@code ROOF_*} in tools/game_world.py. */
    public static final int ROOF_FLAT = 0;
    public static final int ROOF_GABLE = 1;
    public static final int ROOF_DOME = 2;
    public static final int ROOF_PYRAMID = 3;
    public static final int ROOF_SHED = 4;

    /** Building types, matching {@code BUILDING_TYPE} in tools/build_world.py. */
    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_HOUSE = 1;
    public static final int TYPE_APARTMENTS = 2;
    public static final int TYPE_MOSQUE = 8;
    public static final int TYPE_SCHOOL = 9;
    public static final int TYPE_HOSPITAL = 11;
    public static final int TYPE_TOWER = 23;

    public static final int FLAG_MULTIPOLYGON = 1;
    public static final int FLAG_NAMED = 2;
    public static final int FLAG_LANDMARK = 4;

    public final int ringCount;
    public final int[] ringStart;      // first vertex index of each ring
    public final int[] ringLength;     // vertices in the ring (closed: last == first)
    public final float[] xz;           // 2 floats per vertex, grid-origin relative metres
    public final float[] heightM;      // roof height above the ground
    public final float[] minHeightM;   // lowest occupied height (0 = on the ground)
    public final byte[] type;
    public final byte[] roof;
    public final int[] nameIndex;
    public final byte[] flags;
    public final float[] bounds;       // 4 floats per ring: minE, minN, maxE, maxN

    public Buildings(int ringCount, int[] ringStart, int[] ringLength, float[] xz, float[] heightM,
                     float[] minHeightM, byte[] type, byte[] roof, int[] nameIndex, byte[] flags,
                     float[] bounds) {
        this.ringCount = ringCount;
        this.ringStart = ringStart;
        this.ringLength = ringLength;
        this.xz = xz;
        this.heightM = heightM;
        this.minHeightM = minHeightM;
        this.type = type;
        this.roof = roof;
        this.nameIndex = nameIndex;
        this.flags = flags;
        this.bounds = bounds;
    }

    public boolean isLandmark(int ring) {
        return (flags[ring] & FLAG_LANDMARK) != 0;
    }

    public double areaM2(int ring) {
        int start = ringStart[ring];
        int length = ringLength[ring];
        double twiceArea = 0;
        for (int i = 0; i < length; i++) {
            int a = start + i;
            int b = start + ((i + 1) % length);
            twiceArea += xz[a * 2] * xz[b * 2 + 1] - xz[b * 2] * xz[a * 2 + 1];
        }
        return Math.abs(twiceArea) * 0.5;
    }

    /** Parses {@code QBG1}; {@code originEast}/{@code originNorth} come from the manifest. */
    public static Buildings parse(BundleReader reader, double originEast, double originNorth) {
        BundleReader.Header header = reader.header("QBG1");
        int count = header.count;

        // Pass one: total vertex count, so the vertex array can be allocated exactly once.
        int totalVertices = 0;
        int[] ringLength = new int[count];
        for (int ring = 0; ring < count; ring++) {
            reader.skip(8);                                   // absolute first vertex
            int points = reader.readUnsignedShort();
            reader.skip(2 + 2 + 1 + 1 + 1 + 1 + 2);            // height, min height, levels, type, roof, flags, name
            ringLength[ring] = points;
            totalVertices += points;
            reader.skip((points - 1) * 8);                     // deltas
        }

        // Pass two: decode sequentially. No random access, no per-vertex seeks.
        int[] ringStart = new int[count];
        float[] xz = new float[totalVertices * 2];
        float[] heightM = new float[count];
        float[] minHeightM = new float[count];
        byte[] type = new byte[count];
        byte[] roof = new byte[count];
        byte[] flags = new byte[count];
        int[] nameIndex = new int[count];
        float[] bounds = new float[count * 4];

        reader.seek(20);
        int vertex = 0;
        for (int ring = 0; ring < count; ring++) {
            double east = reader.readInt() / 100.0;
            double north = reader.readInt() / 100.0;
            int points = reader.readUnsignedShort();
            heightM[ring] = reader.readUnsignedShort() / 10.0f;
            minHeightM[ring] = reader.readUnsignedShort() / 10.0f;
            reader.readUnsignedByte();                         // levels
            type[ring] = (byte) reader.readUnsignedByte();
            roof[ring] = (byte) reader.readUnsignedByte();
            flags[ring] = (byte) reader.readUnsignedByte();
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
                xz[vertex * 2] = (float) (east - originEast);
                xz[vertex * 2 + 1] = (float) (north - originNorth);
                vertex++;
                minE = Math.min(minE, east);
                maxE = Math.max(maxE, east);
                minN = Math.min(minN, north);
                maxN = Math.max(maxN, north);
            }
            bounds[ring * 4] = (float) (minE - originEast);
            bounds[ring * 4 + 1] = (float) (minN - originNorth);
            bounds[ring * 4 + 2] = (float) (maxE - originEast);
            bounds[ring * 4 + 3] = (float) (maxN - originNorth);
        }
        return new Buildings(count, ringStart, ringLength, xz, heightM, minHeightM, type, roof,
                nameIndex, flags, bounds);
    }
}
