package ai.arena.qatar.world;

/**
 * Points of interest and spawn points (bundle {@code QPO1}).
 *
 * <p>These are absolute (delta-free) coordinates on purpose: the game queries them randomly -- "where
 * is the nearest car spawn?", "which landmarks are inside the map view?" -- and a delta-encoded list
 * would force a full decode for every query. Behind the scenes a {@link FeatureIndex} narrows a query
 * to a handful of candidates, so asking for nearby points stays cheap even with tens of thousands of
 * them.
 */
public final class Points {

    /** Spawn kinds; a point with kind {@link #KIND_LANDMARK} is not a spawn. */
    public static final int KIND_CAR = 0;
    public static final int KIND_HELI = 1;
    public static final int KIND_BOAT = 2;
    public static final int KIND_PLANE = 3;
    public static final int KIND_LANDMARK = 8;

    /** POI classes, matching {@code POI_CLASS_IDS} in tools/game_world.py. */
    public static final int CLASS_PARKING = 1;
    public static final int CLASS_FUEL = 2;
    public static final int CLASS_HELIPAD = 3;
    public static final int CLASS_AIRPORT = 4;
    public static final int CLASS_PORT = 5;
    public static final int CLASS_METRO = 6;
    public static final int CLASS_BUS = 7;
    public static final int CLASS_HOSPITAL = 8;
    public static final int CLASS_SCHOOL = 9;
    public static final int CLASS_MOSQUE = 10;
    public static final int CLASS_MALL = 11;
    public static final int CLASS_STADIUM = 12;
    public static final int CLASS_LANDMARK = 13;

    public final int count;
    public final float[] xz;        // 2 floats per point, grid-origin relative metres
    public final byte[] kind;
    public final byte[] poiClass;
    public final byte[] flags;
    public final int[] nameIndex;
    public final float[] facingDeg;

    public Points(int count, float[] xz, byte[] kind, byte[] poiClass, byte[] flags, int[] nameIndex,
                  float[] facingDeg) {
        this.count = count;
        this.xz = xz;
        this.kind = kind;
        this.poiClass = poiClass;
        this.flags = flags;
        this.nameIndex = nameIndex;
        this.facingDeg = facingDeg;
    }

    public static Points parse(BundleReader reader) {
        BundleReader.Header header = reader.header("QPO1");
        int count = header.count;
        float[] xz = new float[count * 2];
        byte[] kind = new byte[count];
        byte[] poiClass = new byte[count];
        byte[] flags = new byte[count];
        int[] nameIndex = new int[count];
        float[] facingDeg = new float[count];

        reader.seek(20);
        for (int i = 0; i < count; i++) {
            xz[i * 2] = reader.readInt() / 100.0f;
            xz[i * 2 + 1] = reader.readInt() / 100.0f;
            kind[i] = (byte) reader.readUnsignedByte();
            poiClass[i] = (byte) reader.readUnsignedByte();
            flags[i] = (byte) reader.readUnsignedByte();
            reader.readUnsignedByte();                          // padding
            nameIndex[i] = reader.readUnsignedShort();
            facingDeg[i] = reader.readUnsignedShort() / 10.0f;
        }
        return new Points(count, xz, kind, poiClass, flags, nameIndex, facingDeg);
    }

    /** Index of the nearest point with the given spawn kind, or -1 when there is none in range. */
    public int nearest(int spawnKind, double east, double north, FeatureIndex index, double maxRangeM) {
        if (index == null) {
            int best = -1;
            double bestDistance = maxRangeM * maxRangeM;
            for (int i = 0; i < count; i++) {
                if (kind[i] != spawnKind) {
                    continue;
                }
                double dx = xz[i * 2] - east;
                double dz = xz[i * 2 + 1] - north;
                double distance = dx * dx + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = i;
                }
            }
            return best;
        }
        // Widen the search box until something turns up: a spawn point may be many kilometres away
        // in the open desert, but scanning the whole country every frame would be absurd.
        double[] best = {-1, Double.MAX_VALUE};
        double radius = Math.max(cellHint(), maxRangeM);
        for (int attempt = 0; attempt < 12; attempt++) {
            final double searchRadius = radius;
            index.query(east - searchRadius, north - searchRadius, east + searchRadius,
                    north + searchRadius, candidate -> {
                if (kind[candidate] != spawnKind) {
                    return true;
                }
                double dx = xz[candidate * 2] - east;
                double dz = xz[candidate * 2 + 1] - north;
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance <= searchRadius && distance < best[1]) {
                    best[1] = distance;
                    best[0] = candidate;
                }
                return true;
            });
            if (best[0] >= 0) {
                return (int) best[0];
            }
            radius *= 4;
            if (radius > 400_000) {
                break;
            }
        }
        return best[0] >= 0 ? (int) best[0] : -1;
    }

    private static double cellHint() {
        return 2_000.0;
    }
}
