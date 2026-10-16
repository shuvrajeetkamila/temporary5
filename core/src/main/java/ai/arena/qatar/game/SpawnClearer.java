package ai.arena.qatar.game;

import ai.arena.qatar.vehicle.Surface;

/**
 * Finds somewhere clear to stand.
 *
 * <p>A spawn point, a teleport or a vehicle change puts a body at a coordinate that came out of the
 * compiled country. That coordinate can be inside a building's bounding box -- the boxes overlap roads
 * in places, and a courtyard complex is one box around all of it -- and a body inside a box is refused
 * in every direction by its own collision rule, which is the "the human and the car cannot move" report
 * of the ninth install. The bodies can now walk out of a solid they are standing in, but the honest fix
 * is not to put them there in the first place.
 *
 * <p>The search is deliberately dull: rings of candidate points outward from where the body wants to be,
 * nearest first, and the first one that a person could stand on wins. Deterministic, testable, and it
 * costs one pass over a few dozen points on the frames when it runs -- which is a teleport, not a frame
 * in the middle of driving.
 *
 * <p>Pure arithmetic over the {@link Surface} interface, so it works with the real country under a unit
 * test's feet as easily as with the fallback plane.
 */
public final class SpawnClearer {

    /** How far out to look, in metres: beyond this, the requested place is simply the wrong place. */
    public static final double MAX_SEARCH_M = 160.0;
    /** Rings are spaced this far apart: fine enough not to skip a street, coarse enough to be cheap. */
    public static final double RING_STEP_M = 8.0;
    /** Candidate directions per ring. 24 gives 15-degree spacing. */
    public static final int DIRECTIONS = 24;
    /** Water deeper than this is not somewhere a person can stand (they would have to swim). */
    public static final double WADE_DEPTH_M = 1.2;

    /** The result of a search: where to stand, and whether it is where we asked for. */
    public static final class Spot {
        public final double eastM;
        public final double northM;
        public final boolean moved;
        public final double movedM;

        Spot(double eastM, double northM, boolean moved, double movedM) {
            this.eastM = eastM;
            this.northM = northM;
            this.moved = moved;
            this.movedM = movedM;
        }
    }

    /** True when a walker could be placed here: on ground it can stand on, and not inside a solid. */
    public static boolean isClear(Surface surface, double eastM, double northM) {
        double ground = surface.groundHeightM(eastM, northM);
        if (surface.waterDepthM(eastM, northM) > WADE_DEPTH_M) {
            return false;
        }
        // A metre above the ground: what a standing person occupies, and the altitude the collision
        // question is about.
        return !surface.solidAt(eastM, northM, ground + 1.0);
    }

    /**
     * The nearest clear place to stand: {@code (east, north)} itself when it is already clear, and
     * otherwise the first clear candidate found on rings outwards. When nothing within
     * {@link #MAX_SEARCH_M} is clear -- open water, say, or a compound the size of a city block -- the
     * original point is returned unchanged and {@code moved} is false: an honest failure rather than a
     * teleport the player did not ask for.
     */
    public static Spot nearestClear(Surface surface, double eastM, double northM) {
        if (isClear(surface, eastM, northM)) {
            return new Spot(eastM, northM, false, 0.0);
        }
        for (double radius = RING_STEP_M; radius <= MAX_SEARCH_M; radius += RING_STEP_M) {
            for (int i = 0; i < DIRECTIONS; i++) {
                double angle = (2.0 * Math.PI * i) / DIRECTIONS;
                double candidateEast = eastM + Math.cos(angle) * radius;
                double candidateNorth = northM + Math.sin(angle) * radius;
                if (isClear(surface, candidateEast, candidateNorth)) {
                    return new Spot(candidateEast, candidateNorth, true, radius);
                }
            }
        }
        return new Spot(eastM, northM, false, 0.0);
    }

    /**
     * The nearest place a body of a given kind could move from: on ground it can use, outside anything
     * solid, and no more than a step away in height.
     *
     * <p>This is {@link #nearestClear} generalised for the rescue that runs while the player is holding
     * the stick and going nowhere. A person needs water no deeper than they can wade; a car needs a shallow
     * ford at worst; a boat needs enough water under its keel. All three need the place to be within a step
     * of where they are, because a rescue that drops a car off a bridge is not a rescue.
     *
     * @param minWaterDepthM the least water the body can use (0 for anything that stands on the ground)
     * @param maxWaterDepthM the most water the body can use (huge for a boat)
     * @param maxStepM       the largest change in ground height the body will accept
     */
    public static Spot nearestUsable(Surface surface, double eastM, double northM, double minWaterDepthM,
                                     double maxWaterDepthM, double maxStepM) {
        double reference = surface.groundHeightM(eastM, northM);
        if (usable(surface, eastM, northM, reference, minWaterDepthM, maxWaterDepthM, maxStepM)) {
            return new Spot(eastM, northM, false, 0.0);
        }
        for (double radius = RING_STEP_M; radius <= MAX_SEARCH_M; radius += RING_STEP_M) {
            for (int i = 0; i < DIRECTIONS; i++) {
                double angle = (2.0 * Math.PI * i) / DIRECTIONS;
                double candidateEast = eastM + Math.cos(angle) * radius;
                double candidateNorth = northM + Math.sin(angle) * radius;
                if (usable(surface, candidateEast, candidateNorth, reference, minWaterDepthM,
                        maxWaterDepthM, maxStepM)) {
                    return new Spot(candidateEast, candidateNorth, true, radius);
                }
            }
        }
        return new Spot(eastM, northM, false, 0.0);
    }

    /** Whether one candidate spot works for a body with those water and step limits. */
    private static boolean usable(Surface surface, double eastM, double northM, double referenceGroundM,
                                  double minWaterDepthM, double maxWaterDepthM, double maxStepM) {
        if (!surface.collisionLoaded(eastM, northM)) {
            // No collision data is not clear ground: it is unknown ground, and moving a body into it would
            // be a guess. The rescue leaves a body where it is rather than somewhere nobody has compiled.
            return false;
        }
        double depth = surface.waterDepthM(eastM, northM);
        if (depth < minWaterDepthM || depth > maxWaterDepthM) {
            return false;
        }
        double ground = surface.groundHeightM(eastM, northM);
        if (ground - referenceGroundM > maxStepM || referenceGroundM - ground > maxStepM) {
            return false;
        }
        return !surface.solidAt(eastM, northM, ground + 1.0);
    }

    private SpawnClearer() {
    }
}
