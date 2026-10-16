package ai.arena.qatar.vehicle;

import ai.arena.qatar.physics.SurfaceMaterial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Swimming: the water as a way through, not a wall.
 *
 * <p>Until this round the walker stopped dead at wading depth. The rule was defensible -- "an honest stop
 * at wading depth is better than pretending to swim" -- and it made the sea a boundary the player could
 * see across and not cross: the island off the west bay, the creek at Al Khor, and the far side of every
 * lagoon in the country were places the game could show and not let anybody reach. Worse, the rescue that
 * walks a stuck body clear of water it cannot cross would fire while the player was pressing towards the
 * water, so the honest stop came with a teleport back to the sand.
 *
 * <p>The model is the smallest one that is honest, and these tests hold the four properties that make it
 * honest: a walker enters deep water rather than refusing it; a swimmer floats with their head above the
 * surface and their feet under it; swimming is slower than walking; and the shore is climbable while a
 * quay wall is not.
 */
final class HumanSwimTest {

    private static final double DT = 1.0 / 60.0;

    /**
     * A shore running east to west: dry sand south of {@code shoreNorthM}, water of a constant depth north
     * of it, over a seabed that is the sand's height minus the depth.
     *
     * <p>Written as a function rather than as a grid because the body only ever asks for a point: the
     * shoreline is one comparison, and a test that needs a coastline does not need a world.
     */
    private static Surface shore(double shoreNorthM, double depthM) {
        return new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return northM < shoreNorthM ? 2.0 : 2.0 - depthM;
            }

            @Override
            public int materialId(double eastM, double northM) {
                if (northM >= shoreNorthM) {
                    return depthM > 2.0 ? SurfaceMaterial.ID_OPEN_WATER
                            : SurfaceMaterial.ID_SHALLOW_WATER;
                }
                return SurfaceMaterial.ID_COMPACT_SAND;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return northM < shoreNorthM ? 0.0 : depthM;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }
        };
    }

    /** A shore with a wall along it: the water is deep, and the land is three metres above the surface. */
    private static Surface quay(double shoreNorthM) {
        return new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return northM < shoreNorthM ? 8.0 : -4.0;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return northM < shoreNorthM ? SurfaceMaterial.ID_URBAN_PAD
                        : SurfaceMaterial.ID_OPEN_WATER;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return northM < shoreNorthM ? 0.0 : 6.0;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }
        };
    }

    /** A walker at the origin, facing north, pressing forward. */
    private static VehicleState swim(Surface surface, double seconds) {
        HumanBody human = new HumanBody();
        VehicleState state = new VehicleState();
        state.eastM = 0;
        state.northM = 0;
        state.headingDegrees = 0;
        state.grounded = true;
        state.heightM = surface.groundHeightM(0, 0);
        VehicleInput input = new VehicleInput();
        input.throttle = 1.0;
        for (double t = 0; t < seconds; t += DT) {
            human.step(state, input, surface, DT);
        }
        return state;
    }

    // ---------------------------------------------------------------- the water is not a wall

    @Test
    @DisplayName("a person walks into deep water and swims, instead of stopping at the shelf")
    void deepWaterIsEntered() {
        // Sand to the north of nothing: the shoreline is at 10 m and the water beyond it is five metres
        // deep, which is deeper than anybody's chest.
        // Forty-five seconds of pressing north: ten metres of sand at a walking pace, then thirty-eight
        // of swimming at a swimmer's. The assertion is that the far side of that arithmetic is reached --
        // anything less than the shoreline plus a few strokes would pass a test that never left the beach.
        Surface surface = shore(10.0, 5.0);
        VehicleState state = swim(surface, 45.0);
        assertTrue(state.northM > 45.0, "the walker stopped at the water's edge: " + state.northM);
        assertTrue(state.floating, "the body is not floating after swimming 30 m out to sea");
        assertFalse(state.blocked, "a swimmer in open water is not blocked");
        assertFalse(state.grounded, "a swimmer is not standing on anything");
    }

    @Test
    @DisplayName("a swimmer floats with their feet under the surface and their head over it")
    void theSwimmerFloatsAtTheSurface() {
        Surface surface = shore(10.0, 5.0);
        VehicleState state = swim(surface, 45.0);
        double waterSurface = surface.waterSurfaceM(state.eastM, state.northM);
        assertEquals(HumanBody.SWIM_FEET_DEPTH_M, waterSurface - state.heightM, 0.05,
                "the body is not floating at its own waterline");
        assertTrue(state.heightM + HumanBody.EYE_HEIGHT_M > waterSurface,
                "the swimmer's eyes are under the water: "
                        + (state.heightM + HumanBody.EYE_HEIGHT_M - waterSurface) + " m above it");
        // And not flying above it either: the head is the only thing that should be out of the water.
        assertTrue(state.heightM + HumanBody.EYE_HEIGHT_M < waterSurface + 0.5,
                "the swimmer is walking on the water");
    }

    @Test
    @DisplayName("swimming is slower than walking, and both are the speeds the bodies claim")
    void swimmingIsSlowerThanWalking() {
        // Dry asphalt for the walking half, because the point of the comparison is the two bodies' own
        // nominal speeds: sand costs a walker speed and that is a different rule, tested where it lives.
        VehicleState onLand = swim(Surface.land(2.0, SurfaceMaterial.ID_DRY_ASPHALT), 20.0);
        VehicleState inWater = swim(shore(-100.0, 5.0), 20.0);
        assertEquals(HumanBody.WALK_SPEED_MS, onLand.speedMS, 0.01, "the walker's pace is its own");
        assertEquals(HumanBody.SWIM_SPEED_MS, inWater.speedMS, 0.01, "the swimmer's pace is its own");
        assertTrue(inWater.speedMS < onLand.speedMS * 0.8,
                "swimming is not a shortcut: " + inWater.speedMS + " against " + onLand.speedMS);
    }

    @Test
    @DisplayName("the sprint button does not turn a swim into a race")
    void sprintingDoesNotSwimFaster() {
        HumanBody human = new HumanBody();
        VehicleState calm = new VehicleState();
        VehicleState sprint = new VehicleState();
        VehicleInput input = new VehicleInput();
        input.throttle = 1.0;
        Surface surface = shore(-100.0, 5.0);
        for (double t = 0; t < 10.0; t += DT) {
            human.step(calm, input, surface, DT);
            input.boost = true;
            human.step(sprint, input, surface, DT);
            input.boost = false;
        }
        assertEquals(calm.speedMS, sprint.speedMS, 0.001,
                "a running person does not swim faster than a swimming one");
    }

    // ---------------------------------------------------------------- and the shore is not a wall

    @Test
    @DisplayName("a swimmer can climb out onto a beach")
    void theShoreIsClimbable() {
        // The player starts in deep water facing south, with sand at the shoreline a metre above the
        // water: within a step, so the same rule that lets a walker mount a kerb lets a swimmer come
        // ashore.
        Surface surface = shore(10.0, 5.0);
        HumanBody human = new HumanBody();
        VehicleState state = new VehicleState();
        state.eastM = 0;
        state.northM = 40.0;
        state.headingDegrees = 180.0;
        state.heightM = surface.waterSurfaceM(0, 40.0) - HumanBody.SWIM_FEET_DEPTH_M;
        VehicleInput input = new VehicleInput();
        input.throttle = 1.0;
        for (double t = 0; t < 30.0; t += DT) {
            human.step(state, input, surface, DT);
        }
        assertTrue(state.northM < 10.0, "the swimmer never reached the sand: " + state.northM);
        assertTrue(state.grounded, "the swimmer is still floating on dry sand");
        assertFalse(state.floating, "the body is floating on land");
    }

    @Test
    @DisplayName("a swimmer cannot climb a quay wall, and stops against it instead of swimming through it")
    void theQuayIsNotClimbable() {
        Surface surface = quay(10.0);
        HumanBody human = new HumanBody();
        VehicleState state = new VehicleState();
        state.eastM = 0;
        state.northM = 40.0;
        state.headingDegrees = 180.0;
        state.heightM = surface.waterSurfaceM(0, 40.0) - HumanBody.SWIM_FEET_DEPTH_M;
        VehicleInput input = new VehicleInput();
        input.throttle = 1.0;
        for (double t = 0; t < 30.0; t += DT) {
            human.step(state, input, surface, DT);
        }
        // Six metres of water over a quay whose deck is six metres above the surface: the wall at the
        // waterline is stone from the water to the sky, and there is no climbing it. The swimmer stops a
        // step short of it -- a step being eighteen millimetres of swimming -- so what is asserted is
        // which side of the wall they are on, not how close they got to it.
        assertTrue(state.northM >= 10.0, "the swimmer climbed the quay: " + state.northM);
        assertTrue(state.blocked, "the swimmer is pressing into a wall and is not blocked");
        assertTrue(state.floating, "the body left the water without climbing out of it");
    }

    @Test
    @DisplayName("the walker says out loud that it is swimming")
    void theInstrumentSaysSo() {
        HumanBody human = new HumanBody();
        VehicleState state = swim(shore(-100.0, 5.0), 5.0);
        String line = human.instrument(state);
        assertTrue(line.contains("SWIMMING"), "the panel does not say what the body is doing: " + line);
        // WADING and SWIMMING are different states and the line keeps them apart: a body standing in
        // knee-deep water is not swimming.
        VehicleState wading = swim(shore(-100.0, HumanBody.WADE_DEPTH_M - 0.2), 5.0);
        assertTrue(wading.wading, "a body in a metre of water is wading");
        assertFalse(wading.floating, "a body in a metre of water is not swimming");
        assertTrue(human.instrument(wading).contains("WADING"), human.instrument(wading));
    }
}
