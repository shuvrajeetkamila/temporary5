package ai.arena.qatar.physics;

/**
 * Central, allocation-free surface material registry. World chunks store only the integer id, so a
 * material tweak never requires recompiling the country.
 */
public final class MaterialRegistry {

    private static final SurfaceMaterial[] MATERIALS = new SurfaceMaterial[32];

    static {
        put(new SurfaceMaterial(SurfaceMaterial.ID_UNKNOWN, "unknown", 0.75, 0.020, 0.85, 0.0, 0.0, 0.05, 0.05, 0.0, "gravel"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_DRY_ASPHALT, "asphalt_dry", 0.95, 0.012, 1.00, 0.0, 0.0, 0.02, 0.05, 0.0, "asphalt"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_DUSTY_ASPHALT, "asphalt_dusty", 0.85, 0.014, 0.92, 0.05, 0.01, 0.02, 0.35, 0.0, "asphalt_dusty"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_WET_ASPHALT, "asphalt_wet", 0.62, 0.016, 0.68, 0.0, 0.0, 0.02, 0.02, 0.55, "asphalt_wet"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_CONCRETE_PAVERS, "concrete", 0.90, 0.015, 0.95, 0.0, 0.0, 0.03, 0.06, 0.10, "concrete"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_GRAVEL_ROCK, "gravel_rock", 0.80, 0.030, 0.82, 0.06, 0.02, 0.04, 0.45, 0.0, "gravel"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_COMPACT_SAND, "sand_compact", 0.78, 0.045, 0.78, 0.15, 0.05, 0.02, 0.55, 0.0, "sand"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_LOOSE_SAND_DUNE, "sand_loose_dune", 0.62, 0.090, 0.62, 0.55, 0.14, 0.01, 0.95, 0.0, "sand_loose"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_WET_SAND, "sand_wet", 0.86, 0.055, 0.86, 0.10, 0.06, 0.02, 0.20, 0.45, "sand_wet"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_SABKHA, "sabkha", 0.55, 0.070, 0.52, 0.35, 0.18, 0.01, 0.35, 0.30, "sabkha"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_MUD_TIDALFLAT, "mud_tidalflat", 0.42, 0.110, 0.38, 0.60, 0.28, 0.00, 0.15, 0.70, "mud"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_SALT_MARSH_MANGROVE, "mangrove_marsh", 0.38, 0.130, 0.34, 0.65, 0.32, 0.00, 0.10, 0.80, "marsh"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_FARM_SOIL, "farm_soil", 0.72, 0.060, 0.70, 0.25, 0.09, 0.02, 0.40, 0.15, "soil"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_URBAN_PAD, "urban_pad", 0.92, 0.014, 0.97, 0.0, 0.0, 0.03, 0.05, 0.05, "concrete"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_RECLAIMED_LAND, "reclaimed_land", 0.90, 0.016, 0.94, 0.0, 0.0, 0.03, 0.08, 0.10, "concrete"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_SHALLOW_WATER, "water_shallow", 0.30, 0.150, 0.28, 0.30, 0.35, 0.00, 0.05, 1.00, "water_shallow"));
        put(new SurfaceMaterial(SurfaceMaterial.ID_OPEN_WATER, "water_open", 0.20, 0.150, 0.20, 0.0, 0.40, 0.00, 0.00, 1.00, "water_open"));
    }

    private MaterialRegistry() {
    }

    private static void put(SurfaceMaterial m) {
        MATERIALS[m.id] = m;
    }

    /** Never null: unknown ids fall back to a safe conservative material and are logged once. */
    public static SurfaceMaterial get(int id) {
        if (id >= 0 && id < MATERIALS.length && MATERIALS[id] != null) {
            return MATERIALS[id];
        }
        return MATERIALS[SurfaceMaterial.ID_UNKNOWN];
    }

    public static int count() {
        int n = 0;
        for (SurfaceMaterial m : MATERIALS) {
            if (m != null) {
                n++;
            }
        }
        return n;
    }
}
