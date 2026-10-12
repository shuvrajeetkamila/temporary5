package ai.arena.qatar.physics;

import ai.arena.qatar.math.Vec3;

/**
 * Read-only view of the collision terrain for the chunk currently under the focus point.
 *
 * <p>Collision data is kept in a much smaller ring than render detail, and {@link #isCollisionLoaded}
 * exists so the solver can refuse to simulate a body over terrain that is not resident: a vehicle
 * must never fall through an unloaded chunk. Streaming tests assert the collision ring always leads
 * the fastest possible focus (aeroplane at cruise speed).
 */
public interface TerrainSampler {

    /** Terrain height (metres, local world space) at local x/z. */
    double height(double x, double z);

    /** Surface normal at local x/z (unit length, pointing up-ish). */
    void normal(double x, double z, Vec3 out);

    /** Compiled {@link SurfaceMaterial} id at local x/z. */
    int materialId(double x, double z);

    /** False while the chunk containing x/z has no resident collision mesh. */
    boolean isCollisionLoaded(double x, double z);

    /** Terrain sampler used before any world data is available (flat, safe, obviously synthetic). */
    TerrainSampler FLAT = new TerrainSampler() {
        @Override
        public double height(double x, double z) {
            return 0.0;
        }

        @Override
        public void normal(double x, double z, Vec3 out) {
            out.set(0, 1, 0);
        }

        @Override
        public int materialId(double x, double z) {
            return SurfaceMaterial.ID_COMPACT_SAND;
        }

        @Override
        public boolean isCollisionLoaded(double x, double z) {
            return true;
        }
    };
}
