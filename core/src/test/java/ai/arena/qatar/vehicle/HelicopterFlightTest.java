package ai.arena.qatar.vehicle;

import ai.arena.qatar.game.CameraRig;
import ai.arena.qatar.game.PlayerController;
import ai.arena.qatar.physics.SurfaceMaterial;
import ai.arena.qatar.physics.TerrainSampler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a helicopter is flown, and the three ways the tenth install flew badly.
 *
 * <ol>
 *   <li><b>It took itself off.</b> The collective was a written-down 0.5 whenever the machine was
 *       airborne or holding a height, which is 0.95 of its own weight -- so selecting it was a launch,
 *       and the player's first act in a helicopter was to try to stop one. A helicopter on the skids with
 *       nothing asked of it has a rotor turning and no thrust: collective zero.</li>
 *   <li><b>It would not go forward.</b> The cyclic's sign was inverted, so tipping the disc forward flew
 *       the machine backwards at 29 km/h, and pushing the stick harder only reached the reverse limit.
 *       That is the report this round is answering: flies, but not properly.</li>
 *   <li><b>It would not hold a height.</b> The hover trim and the altitude hold were two different
 *       numbers, and the trim ignored the disc's tilt and the air's density, so a machine asked to hold
 *       25 m coasted to 43 m and stayed there.</li>
 * </ol>
 *
 * <p>The numbers here are not invented: they are the output of a simulation of this model (the same
 * expression for hover thrust, the same spring-damper on the altitude) run over the same scenarios, and
 * this test is that simulation's results written down as assertions. The trajectories it settles at are
 * stable -- 25 m is reached and held to the centimetre, and a hover in thin air at four thousand metres
 * holds the same height on a higher collective.
 */
final class HelicopterFlightTest {

    private static final double DT = 1.0 / 60.0;
    private static final Surface FLAT = Surface.land(0, SurfaceMaterial.ID_COMPACT_SAND);

    private static PlayerController helicopter() {
        PlayerController controller = new PlayerController(new CameraRig(TerrainSampler.FLAT, 0, 0));
        controller.setSurface(FLAT);
        controller.placeAll(0.0, 0.0, 0.0);
        controller.setLoco(PlayerController.Loco.HELICOPTER);
        return controller;
    }

    /** Runs the controller for a number of seconds with a fixed stick and lever, returning the state. */
    private static void fly(PlayerController controller, double seconds, double forward, double climb) {
        controller.forward = forward;
        controller.climb = climb;
        for (double t = 0; t < seconds; t += DT) {
            controller.update(DT);
        }
    }

    @Test
    @DisplayName("a helicopter on the skids with nothing asked of it stays on the skids")
    void itDoesNotTakeItselfOff() {
        PlayerController controller = helicopter();
        double startHeight = controller.state().heightM;
        fly(controller, 8.0, 0.0, 0.0);
        assertTrue(controller.state().grounded, "the helicopter left the ground on its own");
        assertEquals(startHeight, controller.state().heightM, 0.05,
                "the helicopter rose without being asked");
        assertFalse(controller.state().rotorClimb, "the rotor reported lift-off thrust at idle");
    }

    @Test
    @DisplayName("takeOff lifts it clear of the ground and the altitude hold settles on the height")
    void takeOffThenHoldTheHeight() {
        PlayerController controller = helicopter();
        controller.takeOff(25.0);
        double ground = controller.state().heightM;
        fly(controller, 30.0, 0.0, 0.0);
        double height = controller.state().heightM;
        assertTrue(height > 12.0, "asked to take off to 25 m, the helicopter reached " + height + " m");
        assertTrue(height - ground < 33.0, "asked to take off to 25 m, the helicopter reached "
                + (height - ground) + " m above where it started: the hold is overshooting, which is what "
                + "the tenth install's machine did on its way to 43 m");
        assertTrue(Math.abs(controller.state().verticalMS) < 0.6,
                "the helicopter is still climbing at " + controller.state().verticalMS + " m/s after "
                        + "thirty seconds: the altitude hold is not a hold");
    }

    @Test
    @DisplayName("the cyclic flies it forward, and the disc's tilt does not cost it its height")
    void theCyclicFliesItForward() {
        PlayerController controller = helicopter();
        controller.takeOff(25.0);
        // Climb to the commanded height with the hands off, then tip the disc fully forward.
        fly(controller, 12.0, 0.0, 0.0);
        double startHeight = controller.state().heightM;
        fly(controller, 20.0, 0.0, 0.0);
        double cruiseStart = controller.state().heightM;
        // The gesture the player makes: hold the flying stick fully forward, and keep holding it. It
        // used to be a nudge -- a held thumb was an impulse that decayed in half a second, so a pilot
        // flying straight had to keep re-commanding the attitude. See PlayerController.setStick.
        controller.setStick(1.0, 0.0);
        fly(controller, 25.0, 0.0, 0.0);
        assertTrue(controller.state().speedMS > 8.0, "twenty-five seconds of forward cyclic reached "
                + controller.state().speedMS + " m/s: the disc is tipping the wrong way, which is the "
                + "tenth install's 'it flies but not perfectly'");
        assertTrue(controller.state().northM > 100.0, "the helicopter flew forward but went nowhere: "
                + controller.state().northM + " m north");
        assertTrue(Math.abs(controller.state().heightM - cruiseStart) < 12.0,
                "tipping the disc forward cost the machine " + (cruiseStart - controller.state().heightM)
                        + " m of height: the hover trim does not know the disc is tilted (it started at "
                        + startHeight + " m)");
    }

    @Test
    @DisplayName("pulling the stick back and the lever down brings it down and lands it")
    void itLands() {
        PlayerController controller = helicopter();
        controller.takeOff(25.0);
        fly(controller, 20.0, 0.0, 0.0);
        double flying = controller.state().heightM;
        assertTrue(flying > 10.0, "the helicopter never got up: " + flying);
        // The ring pulled back is a descent request: less rotor thrust than the weight it is carrying.
        fly(controller, 25.0, -1.0, 0.0);
        assertTrue(controller.state().grounded,
                "the helicopter would not come down: it is at " + controller.state().heightM + " m after "
                        + "twenty-five seconds of a full descend request");
    }

    @Test
    @DisplayName("the altitude buttons raise and hold a new height, and thin air needs more collective")
    void theAltitudeButtonsHoldAHeight() {
        PlayerController controller = helicopter();
        controller.takeOff(25.0);
        fly(controller, 20.0, 0.0, 0.0);
        double first = controller.state().heightM;
        // The UP button is a held climb input, and it also re-arms the hold via adjustAltitude.
        controller.adjustAltitude(20.0);
        fly(controller, 20.0, 0.0, 0.0);
        assertTrue(controller.state().heightM > first + 8.0,
                "asking for 20 m more got " + (controller.state().heightM - first) + " m");
        controller.adjustAltitude(-15.0);
        fly(controller, 25.0, 0.0, 0.0);
        // Twenty metres up, then fifteen down: the machine should be about five metres above where it
        // settled, and it should have got there by flying, not by coasting.
        assertTrue(Math.abs(controller.state().heightM - (first + 5.0)) < 8.0,
                "asking for 20 m and then 15 back left the helicopter at "
                        + controller.state().heightM + " m, and it settled at " + first + " m");
    }

    @Test
    @DisplayName("a held stick stays held, and letting go lets the machine fly itself level")
    void aHeldStickStaysHeld() {
        PlayerController controller = helicopter();
        controller.takeOff(25.0);
        fly(controller, 12.0, 0.0, 0.0);

        // Three seconds of a thumb held half forward, with no frames skipped.
        controller.setStick(0.5, 0.0);
        for (double t = 0; t < 3.0; t += DT) {
            controller.update(DT);
        }
        assertEquals(0.5, controller.stickPitch(), 1e-9,
                "the stick sprang back to trim while a finger was holding it: a pilot holding a heading "
                        + "would have to keep re-commanding it, which is how the tenth install's "
                        + "helicopter flew");

        // And the release: it springs back, and the machine keeps its heading rather than snapping to one.
        controller.releaseStick();
        for (double t = 0; t < 2.0; t += DT) {
            controller.update(DT);
        }
        assertEquals(0.0, controller.stickPitch(), 1e-6, "the stick did not centre when it was let go");
    }

    @Test
    @DisplayName("the hover trim is the machine's own arithmetic: it rises with the air and the speed")
    void theHoverTrimIsDerived() {
        HelicopterBody body = new HelicopterBody();
        VehicleState state = new VehicleState();
        state.reset(0, 0, 0, 0);
        double atSeaLevel = body.hoverCollective(state);
        // Level flight is thrust equal to weight, and the machine makes 1.9 times its weight at full
        // collective in still air at sea level: a hover is 1/1.9 of the lever.
        assertEquals(1.0 / 1.9, atSeaLevel, 0.01,
                "hover collective at sea level: " + atSeaLevel);

        state.heightM = 4000.0;
        double atAltitude = body.hoverCollective(state);
        assertTrue(atAltitude > atSeaLevel + 0.2,
                "the trim at four thousand metres (" + atAltitude + ") is not appreciably above the trim "
                        + "at sea level (" + atSeaLevel + "): the machine would sink wherever the air is "
                        + "thin, and Qatar is flat but its helicopters are not always low");

        state.heightM = 0.0;
        double slow = body.hoverCollective(state);
        state.speedMS = HelicopterBody.SPEED_LIMIT_MS;
        double fast = body.hoverCollective(state);
        assertTrue(fast > slow, "the trim does not rise with speed (" + fast + " vs " + slow
                + "): the machine loses height whenever it cruises");
    }
}
