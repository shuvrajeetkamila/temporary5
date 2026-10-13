package ai.arena.qatar.game;

import ai.arena.qatar.vehicle.Surface;
import ai.arena.qatar.world.GridTerrainSampler;

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

    public WorldSurface(GridTerrainSampler sampler) {
        this.sampler = sampler;
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
}
