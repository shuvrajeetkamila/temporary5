package ai.arena.qatar.game;

import ai.arena.qatar.vehicle.Surface;
import ai.arena.qatar.world.Buildings;
import ai.arena.qatar.world.FeatureIndex;
import ai.arena.qatar.world.GridTerrainSampler;
import ai.arena.qatar.world.WorldData;

/**
 * The {@link Surface} the vehicle bodies see, backed by the streaming collision terrain.
 *
 * <p>It exists so the vehicles never touch the world bundles or the floating origin: they ask for a
 * height, a material and a depth in local metres, and this class answers from the same sampler the
 * camera rig already uses. Because queries carry a position, a neighbour query is just a second call,
 * which is what makes a kerb a step and a sea wall a refusal instead of an invisible ramp.
 */
public final class WorldSurface implements Surface {

    private final GridTerrainSampler sampler;
    private final Buildings buildings;
    private final FeatureIndex buildingIndex;

    public WorldSurface(GridTerrainSampler sampler, WorldData world) {
        this.sampler = sampler;
        this.buildings = world.buildings;
        this.buildingIndex = world.buildingIndex;
    }

    @Override
    public double groundHeightM(double localEastM, double localNorthM) {
        return sampler.height(localEastM, localNorthM);
    }

    @Override
    public int materialId(double localEastM, double localNorthM) {
        return sampler.materialId(localEastM, localNorthM);
    }

    @Override
    public double waterDepthM(double localEastM, double localNorthM) {
        // The grid knows the surface, not the sounding: the compiled material is the honest source.
        return Surface.depthOf(sampler.materialId(localEastM, localNorthM));
    }

    @Override
    public boolean collisionLoaded(double localEastM, double localNorthM) {
        return sampler.isCollisionLoaded(localEastM, localNorthM);
    }

    /**
     * Buildings are solid from the ground up to their compiled roof height, which is exactly what the
     * bundle stores. Footprints are boxes here, not the ring the mesh draws: a courtyard is walkable
     * through its bounding box, which is the honest limit of a box test and is stated rather than hidden.
     */
    @Override
    public boolean solidAt(double localEastM, double localNorthM, double altitudeM) {
        if (buildings == null || buildingIndex == null) {
            return false;
        }
        double roofLimit = altitudeM - sampler.height(localEastM, localNorthM);
        boolean[] hit = {false};
        buildingIndex.query(localEastM - 0.05, localNorthM - 0.05, localEastM + 0.05, localNorthM + 0.05,
                id -> {
                    if (roofLimit >= buildings.heightM[id]) {
                        return true;                       // standing above this roof
                    }
                    if (localEastM < buildings.bounds[id * 4]
                            || localEastM > buildings.bounds[id * 4 + 2]
                            || localNorthM < buildings.bounds[id * 4 + 1]
                            || localNorthM > buildings.bounds[id * 4 + 3]) {
                        return true;                       // bounding box says no
                    }
                    hit[0] = true;
                    return false;
                });
        return hit[0];
    }
}
