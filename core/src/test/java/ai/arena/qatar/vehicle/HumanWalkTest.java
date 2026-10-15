package ai.arena.qatar.vehicle;

import ai.arena.qatar.game.CameraRig;
import ai.arena.qatar.game.PlayerController;
import ai.arena.qatar.physics.SurfaceMaterial;
import ai.arena.qatar.world.GameManifest;
import ai.arena.qatar.world.GridTerrainSampler;
import ai.arena.qatar.world.WorldData;
import ai.arena.qatar.world.WorldOrigin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walking, in the direction the player asked for.
 *
 * <p>Two bugs lived here, and both were "the body is fine, the arithmetic around it is not":
 *
 * <ol>
 *   <li><b>Walking backwards walked forwards.</b> Pulling the stick back puts the desired direction
 *       behind the player <em>and</em> makes the speed negative, and the movement code flipped the
 *       direction a second time for the negative speed -- which cancelled out. The player pressed back
 *       and the person walked forward, which is exactly the kind of thing that reads as "the physics is
 *       wrong" rather than as a sign error.</li>
 *   <li><b>A jump could not be asked for on foot.</b> The jump came from the UP button's held value,
 *       and UP is the flying machines' altitude control: it is disabled while walking, so a walker could
 *       never jump at all, and the same held value would bunny-hop whenever it was not disabled.</li>
 * </ol>
 *
 * <p>Both are pinned here from the outside: the walker is driven the way the game drives it and the
 * direction of travel is compared with the direction of the input.
 */
final class HumanWalkTest {

    private static final double DT = 1.0 / 60.0;
    private static final Surface ASPHALT = Surface.land(0, SurfaceMaterial.ID_DRY_ASPHALT);

    /** The rig a controller needs, driven by the same committed fixture the collision tests use. */
    private static CameraRig rig;

    @BeforeAll
    static void loadFixture() throws IOException {
        Path fixture = Paths.get("src/test/resources/synthetic_world");
        if (!Files.isDirectory(fixture)) {
            fixture = Paths.get("core/src/test/resources/synthetic_world");
        }
        Path dir = fixture;
        GameManifest manifest = GameManifest.parse(
                new String(Files.readAllBytes(dir.resolve("manifest.json")), StandardCharsets.UTF_8));
        WorldData world = WorldData.fromSource(manifest, name -> {
            try {
                return Files.readAllBytes(dir.resolve(name));
            } catch (IOException e) {
                throw new IllegalStateException("fixture file unreadable: " + name, e);
            }
        });
        WorldOrigin origin = new WorldOrigin(manifest.originEast, manifest.originNorth);
        rig = new CameraRig(new GridTerrainSampler(world.terrainL1, world.materials, origin), 0, 0);
    }

    private static VehicleState walkFor(HumanBody human, VehicleState state, double throttle,
                                        double strafe, double seconds) {
        VehicleInput input = new VehicleInput();
        input.throttle = throttle;
        input.strafe = strafe;
        for (double t = 0; t < seconds; t += DT) {
            human.step(state, input, ASPHALT, DT);
        }
        return state;
    }

    @Test
    @DisplayName("pulling the stick back walks the player backwards, not forwards")
    void aBackwardsStickWalksBackwards() {
        // Heading 90 faces east, so "backwards" is west: a smaller east coordinate, and the same north.
        HumanBody human = new HumanBody();
        VehicleState state = new VehicleState();
        human.spawn(state, 0, 0, ASPHALT, 90.0);

        // Forward first, so there is a clear distance to come back from.
        walkFor(human, state, 1.0, 0.0, 3.0);
        double afterWalking = state.eastM;
        assertTrue(afterWalking > 3.0, "the walker did not walk forwards: " + afterWalking);

        walkFor(human, state, -1.0, 0.0, 3.0);
        assertTrue(state.eastM < afterWalking - 1.0,
                "three seconds of a backwards stick moved the player from " + afterWalking + " m to "
                        + state.eastM + " m: pulling back must walk *back*");
        assertTrue(state.speedMS < 0.0, "a backwards walk reports a forward speed: " + state.speedMS);
        assertEquals(0.0, state.northM, 1e-6, "walking backwards is not walking sideways");
    }

    @Test
    @DisplayName("strafe runs across the player's heading, and neither input drifts the aim")
    void strafingIsSideways() {
        HumanBody human = new HumanBody();
        VehicleState state = new VehicleState();
        human.spawn(state, 0, 0, ASPHALT, 90.0);              // facing east
        walkFor(human, state, 0.0, 1.0, 2.0);                 // strafe right, which is south
        assertEquals(0.0, state.eastM, 0.05, "strafing moved the walker along the heading");
        assertTrue(state.northM < -0.5, "strafing right while facing east did not go south: "
                + state.northM);
        assertEquals(90.0, state.headingDegrees, 1e-6, "walking turned the player's aim");
    }

    @Test
    @DisplayName("a jump happens when it is asked for, once, and the walker lands")
    void aJumpOnlyHappensWhenAsked() {
        HumanBody human = new HumanBody();
        VehicleState state = new VehicleState();
        human.spawn(state, 0, 0, ASPHALT, 0);

        // Walking, with no jump asked for: the walker stays on the ground the whole way.
        VehicleInput walking = new VehicleInput();
        walking.throttle = 1.0;
        for (double t = 0; t < 2.0; t += DT) {
            human.step(state, walking, ASPHALT, DT);
            assertTrue(state.grounded, "walking left the ground at t = " + t);
        }

        // Asked for: up, and back down.
        VehicleInput jump = new VehicleInput();
        jump.throttle = 1.0;
        jump.jump = true;
        human.step(state, jump, ASPHALT, DT);
        assertFalse(state.grounded, "the jump did not leave the ground");
        double apex = 0;
        VehicleInput none = new VehicleInput();
        none.throttle = 1.0;
        for (double t = 0; t < 3.0; t += DT) {
            human.step(state, none, ASPHALT, DT);
            apex = Math.max(apex, state.heightM);
        }
        assertTrue(apex > 0.3 && apex < 0.65, "the jump cleared " + apex + " m");
        assertTrue(state.grounded, "the walker never came back down");
    }

    @Test
    @DisplayName("one request is one jump: no bunny-hopping in the controller")
    void aRequestedJumpIsExactlyOneJump() {
        // The controller is where "a jump" became a held flag, and the controller is where the fix
        // lives, so this drives it the way the JUMP button does: a request, then frames.
        PlayerController controller = new PlayerController(rig);
        controller.setLoco(PlayerController.Loco.HUMAN);
        controller.setSurface(ASPHALT);
        controller.requestJump();

        int takeoffs = 0;
        boolean wasGrounded = controller.state().grounded;
        for (int frame = 0; frame < 240; frame++) {                  // four seconds at 60 fps
            if (wasGrounded && !controller.state().grounded) {
                takeoffs++;
            }
            wasGrounded = controller.state().grounded;
            controller.update(DT);
        }
        assertEquals(1, takeoffs, "one request produced " + takeoffs + " take-offs");

        // And a request made in mid-air must not come back as a jump after the landing: it is the
        // difference between a press and a finger resting on the button.
        assertTrue(controller.state().grounded, "the walker should be standing after four seconds");
        controller.requestJump();
        controller.update(DT);
        assertFalse(controller.state().grounded, "the second request did not jump");
        controller.requestJump();                     // a press made while already in the air
        boolean standingBeforeFrame = false;                  // we are airborne right now
        int extra = 0;
        for (int frame = 0; frame < 240; frame++) {
            if (standingBeforeFrame && !controller.state().grounded) {
                extra++;
            }
            standingBeforeFrame = controller.state().grounded;
            controller.update(DT);
        }
        assertEquals(0, extra, "a press made in mid-air jumped " + extra + " more times after landing");
        assertTrue(controller.state().grounded, "the walker never came back down");
    }
}
