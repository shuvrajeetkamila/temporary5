package ai.arena.qatar.vehicle;

import ai.arena.qatar.physics.MaterialRegistry;
import ai.arena.qatar.physics.SurfaceMaterial;

/**
 * Everything a vehicle body is allowed to know about the world under it.
 *
 * <p>Queries take a local position, in the same metres the bodies integrate in, so one instance can
 * serve a person in Doha and an aeroplane over Al Ruwais without any hidden state. Deliberately free of
 * libGDX: a body must be simulatable in a unit test with a literal, and in the game with a sampler over
 * the streaming collision terrain. Water depth is the only value the terrain grid does not carry, so it
 * is approximated from the compiled surface material -- one rule for every vehicle that touches water.
 */
public interface Surface {

    /** Terrain height at a local position, metres above the world datum. */
    double groundHeightM(double localEastM, double localNorthM);

    /** Compiled {@link SurfaceMaterial} id at a local position. */
    int materialId(double localEastM, double localNorthM);

    /** Water depth above the seabed at a local position, 0 on dry land. */
    double waterDepthM(double localEastM, double localNorthM);

    /** False when the collision mesh covering this position has not streamed in. */
    boolean collisionLoaded(double localEastM, double localNorthM);

    /** The material record at a position; the one call most bodies actually want. */
    default SurfaceMaterial materialAt(double localEastM, double localNorthM) {
        return MaterialRegistry.get(materialId(localEastM, localNorthM));
    }

    /** Water surface altitude at a position: the seabed plus the water column. */
    default double waterSurfaceM(double localEastM, double localNorthM) {
        return groundHeightM(localEastM, localNorthM) + waterDepthM(localEastM, localNorthM);
    }

    /** True when there is enough water at a position to float a hull of the given draft. */
    default boolean floatable(double localEastM, double localNorthM, double draftM) {
        return waterDepthM(localEastM, localNorthM) > draftM;
    }

    /**
     * Depth for a compiled material id. The grid records "shallow" and "open" water, not a sounding, so
     * this is the honest two-step approximation: the shallow id is wading and inshore water, the open
     * id is deep enough for anything the game can float.
     */
    static double depthOf(int materialId) {
        if (materialId == SurfaceMaterial.ID_SHALLOW_WATER) {
            return 1.6;
        }
        if (materialId == SurfaceMaterial.ID_OPEN_WATER) {
            return 14.0;
        }
        return 0.0;
    }

    static boolean isWater(int materialId) {
        return materialId == SurfaceMaterial.ID_SHALLOW_WATER
                || materialId == SurfaceMaterial.ID_OPEN_WATER;
    }

    /** A flat surface over a whole plane of the same material, for tests and the demo fallback. */
    static Surface flat(double groundHeightM, int materialId) {
        return new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return groundHeightM;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return materialId;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return depthOf(materialId);
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }
        };
    }

    /** Dry ground of the given material, everywhere. */
    static Surface land(double groundHeightM, int materialId) {
        return flat(groundHeightM, materialId);
    }

    /** Water of the given depth over a flat seabed, everywhere. */
    static Surface water(double seabedM, double depthM) {
        return new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return seabedM;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return depthM > 2.0 ? SurfaceMaterial.ID_OPEN_WATER : SurfaceMaterial.ID_SHALLOW_WATER;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return depthM;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }
        };
    }

    /** A surface that answers a step-up test: solid ground, then a wall of the given height. */
    static Surface step(double groundM, int materialId, double edgeEastM, double riseM) {
        return new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return eastM >= edgeEastM ? groundM + riseM : groundM;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return materialId;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return 0;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }
        };
    }
}
