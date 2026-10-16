package ai.arena.qatar.game;

import ai.arena.qatar.physics.SurfaceMaterial;
import ai.arena.qatar.physics.TerrainSampler;
import ai.arena.qatar.vehicle.Surface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of the ninth install's "the human and the car cannot move", pinned where they can be
 * checked without a phone.
 *
 * <p>What the panel showed was a speed of zero and nothing else: no message, because nothing was wrong
 * with the bodies. The points were wrong. The controller was built before the country was loaded, with
 * a flat fallback surface at sea level; the bodies were spawned on that, forty metres below the ground
 * they were supposed to be standing on, and every step of a walker or a car was then refused by its own
 * step-up limit -- "the ground ahead is twelve metres above my feet" -- in every direction. Selecting a
 * different vehicle did not help, because the vehicle was spawned from the same wrong surface.
 *
 * <p>The fix has two parts and both are asserted here: {@code setSurface} re-seats every body on the
 * ground it is actually standing on when the real terrain arrives, and a teleport or a vehicle change
 * goes through {@link SpawnClearer} -- which is the {@code HumanCarMovementTest}'s own subject in
 * {@link SpawnClearerTest}.
 */
final class HumanCarMovementTest {

    private static final double DT = 1.0 / 60.0;
    /** Ground twelve metres above the datum: the ninth install's walker was spawned twelve below it. */
    private static final double GROUND_M = 12.0;

    private static PlayerController controller(PlayerController.Loco loco) {
        PlayerController controller = new PlayerController(new CameraRig(TerrainSampler.FLAT, 0, 0));
        controller.setSurface(twelveMetreGround());
        controller.placeAll(0.0, 0.0, 0.0);
        controller.setLoco(loco);
        return controller;
    }

    /** Ground twelve metres up, with one wall across it thirty metres north, and nothing else in it. */
    private static Surface twelveMetreGround() {
        return new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return GROUND_M;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return SurfaceMaterial.ID_COMPACT_SAND;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return 0.0;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }

            @Override
            public boolean solidAt(double eastM, double northM, double heightM) {
                // One wall, 30 m north of the start, 2 m thick and 2 m tall above the ground: a car and
                // a walker both have to be stopped by it, and neither may be stopped anywhere else.
                return northM > 30.0 && northM < 32.0 && heightM < GROUND_M + 2.0;
            }
        };
    }

    @Test
    @DisplayName("a walker with a working surface walks: the ninth install's report, from the outside")
    void aWalkerWalks() {
        PlayerController controller = controller(PlayerController.Loco.HUMAN);
        controller.sprinting = true;
        double startEast = controller.state().eastM;
        double startNorth = controller.state().northM;
        double startHeight = controller.state().heightM;
        assertTrue(Math.abs(startHeight - GROUND_M) < 1.5,
                "the walker was not re-seated on the real ground when the surface arrived: the body is "
                        + "at " + startHeight + " m and the ground is at " + GROUND_M + " m, which is "
                        + "the ninth install's 'the human cannot move'");

        controller.forward = 1.0;
        for (int step = 0; step < 240; step++) {
            controller.update(DT);
        }

        double travelled = Math.hypot(controller.state().eastM - startEast,
                controller.state().northM - startNorth);
        assertTrue(travelled > 8.0, "the walker moved " + travelled + " m in four seconds: this is the "
                + "ninth install's 'the human cannot move'");
    }

    @Test
    @DisplayName("a car drives, and the wall stops it instead of the world stopping it")
    void aCarDrivesAndIsStoppedByAWall() {
        PlayerController controller = controller(PlayerController.Loco.CAR);
        assertTrue(controller.state().grounded, "the car was not seated on the ground");

        controller.forward = 1.0;
        controller.sprinting = true;
        for (int step = 0; step < 120; step++) {
            controller.update(DT);
        }
        double afterTwoSeconds = controller.state().northM;
        assertTrue(afterTwoSeconds > 5.0,
                "the car travelled " + afterTwoSeconds + " m in two seconds: this is the ninth "
                        + "install's 'the car cannot move'");

        // And now into the wall, which stands across the road at 30 m: the car must not be on the far
        // side of it, and it must not be inside it either.
        for (int step = 0; step < 600; step++) {
            controller.update(DT);
        }
        double stopped = controller.state().northM;
        assertTrue(stopped < 30.5, "the car drove through a wall to " + stopped + " m north");
        assertTrue(stopped > 24.0, "the car stopped " + stopped + " m north, long before the wall");
    }

    @Test
    @DisplayName("every locomotion gets its feet on the ground the moment the surface arrives")
    void everyBodyIsReSeated() {
        PlayerController controller = new PlayerController(new CameraRig(TerrainSampler.FLAT, 0, 0));
        // Constructed on the flat fallback: every body is at sea level, whatever the country says.
        controller.placeAll(0.0, 0.0, 0.0);
        assertEquals(0.0, controller.state().heightM, 1e-6,
                "the fallback surface is not flat at zero, so this test no longer tests anything");
        controller.setSurface(twelveMetreGround());
        for (PlayerController.Loco loco : PlayerController.Loco.values()) {
            controller.setLoco(loco);
            double height = controller.state().heightM;
            assertTrue(height > GROUND_M - 1.5,
                    loco + " is at " + height + " m on ground that is " + GROUND_M + " m up: selecting "
                            + "it would refuse every step, which is the 'cannot move' report");
        }
    }

    @Test
    @DisplayName("a parked vehicle is solid: the car cannot be driven through the aeroplane")
    void parkedVehiclesAreSolid() {
        PlayerController controller = controller(PlayerController.Loco.CAR);
        // Arriving parks the stable behind the player; move the aeroplane in front of the car instead,
        // so that driving north runs into it.
        ai.arena.qatar.vehicle.VehicleState plane =
                controller.stateOf(PlayerController.Loco.PLANE);
        plane.eastM = 0.0;
        plane.northM = 20.0;
        plane.heightM = GROUND_M;

        controller.forward = 1.0;
        controller.sprinting = true;
        for (int step = 0; step < 900; step++) {
            controller.update(DT);
        }
        double planeFront = 20.0 - VehicleContact.PLANE_RADIUS_M - VehicleContact.CAR_RADIUS_M;
        // 20 - 5.5 - 1.15 = 13.35 m: the car's nose is at the aeroplane's skin and no further.
        assertTrue(controller.state().northM <= planeFront + 0.05,
                "the car reached " + controller.state().northM + " m north, and the parked aeroplane "
                        + "occupies everything beyond " + planeFront + " m: it was driven through");
        assertTrue(controller.state().northM > planeFront - 2.0,
                "the car stopped " + (planeFront - controller.state().northM)
                        + " m short of the aeroplane: the contact pushes from too far away");
    }
}
