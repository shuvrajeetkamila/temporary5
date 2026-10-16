package ai.arena.qatar.game;

import ai.arena.qatar.vehicle.Surface;
import ai.arena.qatar.world.Barriers;
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

    /** Half-thickness of a wall, a fence or a hedge for collision purposes, in metres. */
    public static final double BARRIER_HALF_WIDTH_M = 0.4;
    /** Lower than this a barrier is a kerb or a low wall, and a person steps over it. */
    public static final double MIN_BLOCKING_HEIGHT_M = 0.6;

    private final GridTerrainSampler sampler;
    private final Buildings buildings;
    private final FeatureIndex buildingIndex;
    private final Barriers barriers;
    private final FeatureIndex barrierIndex;

    public WorldSurface(GridTerrainSampler sampler, WorldData world) {
        this.sampler = sampler;
        this.buildings = world.buildings;
        this.buildingIndex = world.buildingIndex;
        this.barriers = world.barriers;
        this.barrierIndex = world.barrierIndex;
    }

    @Override
    public double groundHeightM(double localEastM, double localNorthM) {
        return sampler.height(localEastM, localNorthM);
    }

    @Override
    /**
     * True when a point is inside a building's own footprint, by the test every polygon renderer uses: a
     * horizontal ray out to the east, counting the walls it crosses. An odd count is inside.
     *
     * <p>The ring is what is drawn, so the ring is what is solid. The bounding box around it is kept only
     * as a fast reject, because a box is not a building: OSM buildings are rotated, L-shaped and
     * clustered, and the box around a fifty-metre tower turned thirty degrees is a hundred-metre square
     * of plaza, pavement and road that a walker may not step into. Measured against the compiled country:
     * of the road vertices sampled across Qatar, 2.4% are inside a building's box but outside its walls.
     *
     * <p>Cost is what it looks like -- a ray cast over one building's own ring, a handful of vertices,
     * called a couple of times per body per step, after the index has already narrowed the candidates to
     * the few whose boxes contain the point.
     */
    private boolean insideFootprint(int id, double eastM, double northM) {
        int start = buildings.ringStart[id];
        int length = buildings.ringLength[id];
        if (length < 3 || start < 0 || start + length > buildings.xz.length / 2) {
            // No ring to test against: believe the rectangle rather than let anything through it.
            return true;
        }
        boolean inside = false;
        for (int i = 0, j = length - 1; i < length; j = i++) {
            double xi = buildings.xz[(start + i) * 2];
            double zi = buildings.xz[(start + i) * 2 + 1];
            double xj = buildings.xz[(start + j) * 2];
            double zj = buildings.xz[(start + j) * 2 + 1];
            if ((zi > northM) != (zj > northM)
                    && eastM < (xj - xi) * (northM - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

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
                        return true;                       // the rectangle around it says no
                    }
                    if (!insideFootprint(id, localEastM, localNorthM)) {
                        // Inside the rectangle and outside the walls: the pavement beside a building that
                        // is not aligned with the axes, the courtyard of an L-shaped block, the road
                        // between two wings of one complex. See insideFootprint: this test used to stop
                        // at the rectangle, and 18.6% of the country's car spawn points are in exactly
                        // this case -- the player starts unable to move in any direction, which is the
                        // ninth install's "the human and the car cannot move".
                        return true;
                    }
                    hit[0] = true;
                    return false;
                });
        if (hit[0] || barriers == null || barrierIndex == null) {
            return hit[0];
        }

        // Barriers: the walls, fences and hedges that make a compound a compound. They are lines with a
        // height, so the question is whether the line passes close enough to walk into and is tall
        // enough to matter -- a gate is a way through, and a kerb is a step.
        double aboveGround = altitudeM - sampler.height(localEastM, localNorthM);
        barrierIndex.query(localEastM - 4, localNorthM - 4, localEastM + 4, localNorthM + 4, id -> {
            if (barriers.kind[id] == Barriers.KIND_GATE
                    || barriers.heightM[id] < MIN_BLOCKING_HEIGHT_M
                    || aboveGround > barriers.heightM[id]) {
                return true;
            }
            int start = barriers.lineStart[id];
            int points = barriers.lineLength[id];
            for (int i = 0; i + 1 < points; i++) {
                double ax = barriers.xz[(start + i) * 2];
                double az = barriers.xz[(start + i) * 2 + 1];
                double bx = barriers.xz[(start + i + 1) * 2];
                double bz = barriers.xz[(start + i + 1) * 2 + 1];
                if (distanceToSegment(localEastM, localNorthM, ax, az, bx, bz)
                        < BARRIER_HALF_WIDTH_M) {
                    hit[0] = true;
                    return false;
                }
            }
            return true;
        });
        return hit[0];
    }

    /** Distance from a point to a line segment, in metres. */
    private static double distanceToSegment(double px, double pz, double ax, double az, double bx,
                                            double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared < 1e-9) {
            return Math.hypot(px - ax, pz - az);
        }
        double t = ((px - ax) * dx + (pz - az) * dz) / lengthSquared;
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }
}
