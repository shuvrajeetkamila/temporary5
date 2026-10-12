package ai.arena.qatar.physics;

/**
 * Collision layers and the single authoritative collision matrix.
 *
 * <p>Triggers never generate solid contact impulses: a trigger pair is resolved by gameplay code,
 * not by the contact solver. Undocumented default masks are forbidden -- every collider declares its
 * layer and mask explicitly (see {@code Collider.withLayers}).
 */
public final class CollisionLayers {

    public static final int TERRAIN = 1 << 0;
    public static final int STATIC_BUILDING = 1 << 1;
    public static final int ROAD_BARRIER = 1 << 2;
    public static final int HUMAN = 1 << 3;
    public static final int PLAYER_CAR = 1 << 4;
    public static final int TRAFFIC_VEHICLE = 1 << 5;
    public static final int BOAT = 1 << 6;
    public static final int HELICOPTER = 1 << 7;
    public static final int AEROPLANE = 1 << 8;
    public static final int TRANSIT = 1 << 9;
    public static final int WILDLIFE = 1 << 10;
    public static final int DYNAMIC_PROP = 1 << 11;
    public static final int WATER_TRIGGER = 1 << 12;
    public static final int INTERACTION_TRIGGER = 1 << 13;
    public static final int RESTRICTED_ZONE_TRIGGER = 1 << 14;
    public static final int VISUAL_ONLY = 1 << 15;

    public static final int ALL = 0xFFFF;

    private CollisionLayers() {
    }

    private static final int[] MATRIX = new int[16];

    static {
        // Solid world: everything physical collides with terrain, buildings and barriers.
        final int solid = TERRAIN | STATIC_BUILDING | ROAD_BARRIER;
        set(TERRAIN, solid | HUMAN | PLAYER_CAR | TRAFFIC_VEHICLE | BOAT | HELICOPTER | AEROPLANE
                | TRANSIT | WILDLIFE | DYNAMIC_PROP);
        set(STATIC_BUILDING, solid | HUMAN | PLAYER_CAR | TRAFFIC_VEHICLE | BOAT | HELICOPTER
                | AEROPLANE | TRANSIT | WILDLIFE | DYNAMIC_PROP);
        set(ROAD_BARRIER, solid | HUMAN | PLAYER_CAR | TRAFFIC_VEHICLE | HELICOPTER | AEROPLANE | DYNAMIC_PROP);
        set(HUMAN, TERRAIN | STATIC_BUILDING | ROAD_BARRIER | PLAYER_CAR | TRAFFIC_VEHICLE | TRANSIT
                | DYNAMIC_PROP | WATER_TRIGGER | INTERACTION_TRIGGER | RESTRICTED_ZONE_TRIGGER);
        set(PLAYER_CAR, TERRAIN | STATIC_BUILDING | ROAD_BARRIER | HUMAN | PLAYER_CAR | TRAFFIC_VEHICLE
                | DYNAMIC_PROP | WATER_TRIGGER | RESTRICTED_ZONE_TRIGGER);
        set(TRAFFIC_VEHICLE, TERRAIN | STATIC_BUILDING | ROAD_BARRIER | HUMAN | PLAYER_CAR | TRAFFIC_VEHICLE
                | TRANSIT | WILDLIFE | DYNAMIC_PROP);
        set(BOAT, TERRAIN | STATIC_BUILDING | ROAD_BARRIER | HUMAN | DYNAMIC_PROP | WATER_TRIGGER
                | RESTRICTED_ZONE_TRIGGER | AEROPLANE | HELICOPTER | BOAT);
        set(HELICOPTER, TERRAIN | STATIC_BUILDING | ROAD_BARRIER | HUMAN | PLAYER_CAR | TRAFFIC_VEHICLE
                | BOAT | DYNAMIC_PROP | RESTRICTED_ZONE_TRIGGER);
        set(AEROPLANE, TERRAIN | STATIC_BUILDING | ROAD_BARRIER | HUMAN | PLAYER_CAR | TRAFFIC_VEHICLE
                | BOAT | HELICOPTER | DYNAMIC_PROP | RESTRICTED_ZONE_TRIGGER);
        set(TRANSIT, TERRAIN | STATIC_BUILDING | ROAD_BARRIER | HUMAN | TRAFFIC_VEHICLE | WILDLIFE);
        set(WILDLIFE, TERRAIN | STATIC_BUILDING | HUMAN | PLAYER_CAR | TRAFFIC_VEHICLE | TRANSIT | WILDLIFE);
        set(DYNAMIC_PROP, solid | HUMAN | PLAYER_CAR | TRAFFIC_VEHICLE | BOAT | HELICOPTER | AEROPLANE | DYNAMIC_PROP);
        set(WATER_TRIGGER, HUMAN | PLAYER_CAR | BOAT);
        set(INTERACTION_TRIGGER, HUMAN);
        set(RESTRICTED_ZONE_TRIGGER, HUMAN | PLAYER_CAR | BOAT | HELICOPTER | AEROPLANE);
        set(VISUAL_ONLY, 0);
    }

    private static void set(int layerBit, int mask) {
        MATRIX[Integer.numberOfTrailingZeros(layerBit)] = mask;
    }

    /** True when a collider on {@code layerA} may interact with {@code layerB} (symmetric by design). */
    public static boolean interacts(int layerBitA, int layerBitB) {
        int a = Integer.numberOfTrailingZeros(layerBitA);
        int b = Integer.numberOfTrailingZeros(layerBitB);
        if (a < 0 || a > 15 || b < 0 || b > 15) {
            return false;
        }
        return (MATRIX[a] & layerBitB) != 0 && (MATRIX[b] & layerBitA) != 0;
    }

    /** True when contacts on this layer are solid (i.e. generate impulses). */
    public static boolean isSolid(int layerBit) {
        return layerBit != WATER_TRIGGER && layerBit != INTERACTION_TRIGGER
                && layerBit != RESTRICTED_ZONE_TRIGGER && layerBit != VISUAL_ONLY;
    }

    public static int matrixSize() {
        return MATRIX.length;
    }
}
