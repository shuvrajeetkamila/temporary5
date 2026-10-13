package ai.arena.qatar.world;

import ai.arena.qatar.math.Vec3;
import ai.arena.qatar.physics.TerrainSampler;

/**
 * The physics world's view of the terrain: heights, normals and materials read straight from the
 * compiled national grid through the floating origin.
 *
 * <p>Two coordinate systems meet here and this is the only place that knows both. Callers hand in
 * <b>local</b> metres (what the simulation and the renderer use, small numbers that stay precise);
 * the sampler converts them to <b>global</b> EPSG:32639 metres, because that is what the grid is
 * indexed by. Rebase the origin and the same world position keeps meaning the same place.
 *
 * <p>{@code isCollisionLoaded} answers from the same grid: the whole country is resident as integers,
 * so collision is always available where the grid reaches -- no popping when a chunk streams in.
 */
public final class GridTerrainSampler implements TerrainSampler {

    private final TerrainGrid terrain;
    private final MaterialGrid materials;
    private final WorldOrigin origin;

    public GridTerrainSampler(TerrainGrid terrain, MaterialGrid materials, WorldOrigin origin) {
        this.terrain = terrain;
        this.materials = materials;
        this.origin = origin;
    }

    /** World height in metres under a local horizontal position. */
    @Override
    public double height(double localX, double localZ) {
        return terrain.heightBilinear(origin.globalEast(localX), origin.globalNorth(localZ));
    }

    /** Surface normal at a local position, written into {@code out}. */
    @Override
    public void normal(double localX, double localZ, Vec3 out) {
        double east = origin.globalEast(localX);
        double north = origin.globalNorth(localZ);
        double h = terrain.heightBilinear(east, north);
        double[] gradient = terrain.heightGradient(east, north, Math.max(1.0, terrain.cellM));
        // Gradient is (dH/dEast, dH/dNorth); the surface normal is (-dH/dx, 1, -dH/dz) normalised.
        double nx = -gradient[0];
        double nz = -gradient[1];
        double length = Math.sqrt(nx * nx + 1.0 + nz * nz);
        out.set(nx / length, 1.0 / length, nz / length);
    }

    /** Surface material id at a local position (see {@link ai.arena.qatar.physics.MaterialRegistry}). */
    @Override
    public int materialId(double localX, double localZ) {
        if (materials == null) {
            return terrain.landBilinear(origin.globalEast(localX), origin.globalNorth(localZ))
                    ? ai.arena.qatar.physics.SurfaceMaterial.ID_GRAVEL_ROCK
                    : ai.arena.qatar.physics.SurfaceMaterial.ID_OPEN_WATER;
        }
        return materials.idAt(origin.globalEast(localX), origin.globalNorth(localZ));
    }

    /** True when this position has real data behind it. Outside the grid, the player is out of world. */
    @Override
    public boolean isCollisionLoaded(double localX, double localZ) {
        return terrain.contains(origin.globalEast(localX), origin.globalNorth(localZ));
    }

    /** True when the local position is over dry land (used for spawning and for map colours). */
    public boolean isLand(double localX, double localZ) {
        return terrain.landBilinear(origin.globalEast(localX), origin.globalNorth(localZ));
    }

    public TerrainGrid terrain() {
        return terrain;
    }

    public MaterialGrid materials() {
        return materials;
    }
}
