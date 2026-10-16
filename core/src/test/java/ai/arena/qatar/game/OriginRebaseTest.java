package ai.arena.qatar.game;

import ai.arena.qatar.math.Vec3;
import ai.arena.qatar.physics.SurfaceMaterial;
import ai.arena.qatar.physics.TerrainSampler;
import ai.arena.qatar.vehicle.Surface;
import ai.arena.qatar.world.WorldOrigin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The floating origin moves the frame, not the player -- and this is the test that says so.
 *
 * <p>What broke the tenth install's map was a half-done rebase. {@link WorldOrigin} keeps the invariant
 * {@code global = local + origin}, and {@code applyRebase} tells its callers what that costs them: every
 * local transform in the game must move by the same {@code -delta} in the same frame. The bodies were
 * not moved. The camera rig is derived from the body ({@code PlayerController.syncRig}) and is rewritten
 * on the next frame, so the rig went back to the body's stale local coordinate, the origin rebased again
 * -- tens of kilometres, once per frame, for ever -- and what the player saw was a map tap that took them
 * somewhere nobody asked for, over ground that was not theirs, in a machine that would not move. The
 * helicopter flew, because flying is what it does.
 *
 * <p>These tests hold the invariant directly, on a world whose ground depends on the global position, so
 * a rebase that fails to move a body changes the ground under it and cannot pass.
 */
final class OriginRebaseTest {

    /** The compiled frame's own origin: the same numbers as the real country. */
    private static final double ORIGIN_EAST = 431_000.0;
    private static final double ORIGIN_NORTH = 2_681_000.0;

    /**
     * Ground that rises with the global northing, so the ground under a body is a function of where the
     * body really is rather than of the local frame it happens to be expressed in.
     */
    private static Surface groundAt(final WorldOrigin origin) {
        return new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return 10.0 + origin.globalNorth(northM) * 0.001;
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
                return false;
            }
        };
    }

    /** A walker forty-one kilometres east and twenty-seven north of the origin: a map tap away. */
    private static final double TELEPORT_EAST_M = 41_000.0;
    private static final double TELEPORT_NORTH_M = 27_000.0;

    @Test
    @DisplayName("a rebase moves the frame and not the player, and the ground under them stays theirs")
    void aRebaseKeepsThePlayerWhereTheyAre() {
        WorldOrigin origin = new WorldOrigin(ORIGIN_EAST, ORIGIN_NORTH);
        Surface surface = groundAt(origin);
        CameraRig rig = new CameraRig(TerrainSampler.FLAT, 0, 0);
        PlayerController controller = new PlayerController(rig);
        controller.setSurface(surface);
        controller.placeAll(TELEPORT_EAST_M, TELEPORT_NORTH_M, 0.0);

        double globalEast = origin.globalEast(controller.state().eastM);
        double globalNorth = origin.globalNorth(controller.state().northM);
        double ground = surface.groundHeightM(controller.state().eastM, controller.state().northM);

        Vec3 delta = origin.planRebase(globalEast, globalNorth);
        assertTrue(delta.x != 0.0 || delta.z != 0.0,
                "a forty-one kilometre offset asked for no rebase at all: this test is not measuring one");

        origin.applyRebase(delta);
        controller.shiftLocal(delta.x, delta.z);

        assertEquals(globalEast, origin.globalEast(controller.state().eastM), 1e-6,
                "the rebase moved the player east: global = local + origin was broken by the rebase itself");
        assertEquals(globalNorth, origin.globalNorth(controller.state().northM), 1e-6,
                "the rebase moved the player north");
        assertEquals(ground, surface.groundHeightM(controller.state().eastM, controller.state().northM),
                1e-9, "the ground under the player changed when the origin moved: they are standing on "
                        + "somebody else's terrain, which is what stopped the walker and the car moving");
        assertTrue(Math.abs(controller.state().eastM) < WorldOrigin.REBASE_THRESHOLD_M
                        && Math.abs(controller.state().northM) < WorldOrigin.REBASE_THRESHOLD_M,
                "the local frame did not move with the origin: the player is at "
                        + controller.state().eastM + ", " + controller.state().northM);

        // And the thing that made it a runaway: the frame the bodies are in must not ask for another
        // rebase the moment it has had one.
        Vec3 again = origin.planRebase(origin.globalEast(controller.state().eastM),
                origin.globalNorth(controller.state().northM));
        assertTrue(again.x == 0.0 && again.z == 0.0,
                "the rebase asked for another one immediately (" + again.x + ", " + again.z + "): that is "
                        + "the loop that threw the player fifty kilometres a frame");
        assertEquals(1, origin.rebaseCount(), "one rebase was asked for and " + origin.rebaseCount()
                + " were applied");
    }

    @Test
    @DisplayName("every machine in the stable is moved, not only the one being ridden")
    void everyBodyMovesWithTheFrame() {
        WorldOrigin origin = new WorldOrigin(ORIGIN_EAST, ORIGIN_NORTH);
        Surface surface = groundAt(origin);
        PlayerController controller = new PlayerController(new CameraRig(TerrainSampler.FLAT, 0, 0));
        controller.setSurface(surface);
        controller.placeAll(TELEPORT_EAST_M, TELEPORT_NORTH_M, 0.0);
        controller.setLoco(PlayerController.Loco.HELICOPTER);

        double[][] before = new double[PlayerController.Loco.values().length][2];
        for (PlayerController.Loco loco : PlayerController.Loco.values()) {
            before[loco.ordinal()][0] = origin.globalEast(controller.stateOf(loco).eastM);
            before[loco.ordinal()][1] = origin.globalNorth(controller.stateOf(loco).northM);
        }

        Vec3 delta = origin.planRebase(origin.globalEast(controller.state().eastM),
                origin.globalNorth(controller.state().northM));
        assertTrue(delta.x != 0.0 || delta.z != 0.0, "no rebase was needed, so nothing is measured");
        origin.applyRebase(delta);
        controller.shiftLocal(delta.x, delta.z);

        for (PlayerController.Loco loco : PlayerController.Loco.values()) {
            assertEquals(before[loco.ordinal()][0], origin.globalEast(controller.stateOf(loco).eastM),
                    1e-6, loco + " was left behind by the rebase: it is now "
                            + (origin.globalEast(controller.stateOf(loco).eastM) - before[loco.ordinal()][0])
                            + " m from where it was parked");
            assertEquals(before[loco.ordinal()][1], origin.globalNorth(controller.stateOf(loco).northM),
                    1e-6, loco + " was left behind by the rebase");
        }
    }

    @Test
    @DisplayName("the control: skipping the shift throws the player the length of the delta")
    void withoutTheShiftThePlayerIsThrown() {
        WorldOrigin origin = new WorldOrigin(ORIGIN_EAST, ORIGIN_NORTH);
        Surface surface = groundAt(origin);
        PlayerController controller = new PlayerController(new CameraRig(TerrainSampler.FLAT, 0, 0));
        controller.setSurface(surface);
        controller.placeAll(TELEPORT_EAST_M, TELEPORT_NORTH_M, 0.0);
        double ground = surface.groundHeightM(controller.state().eastM, controller.state().northM);

        Vec3 delta = origin.planRebase(origin.globalEast(controller.state().eastM),
                origin.globalNorth(controller.state().northM));
        origin.applyRebase(delta);                      // the half-done rebase, on purpose

        double moved = Math.hypot(origin.globalEast(controller.state().eastM) - TELEPORT_EAST_M,
                origin.globalNorth(controller.state().northM) - TELEPORT_NORTH_M);
        assertTrue(moved > 10_000.0,
                "a rebase without the shift should move the player tens of kilometres, and it moved "
                        + moved + " m: the two assertions above would not catch the bug they exist for");
        assertTrue(surface.groundHeightM(controller.state().eastM, controller.state().northM) != ground,
                "the ground under a stale body has to change, or this fixture cannot see the bug");
    }
}
