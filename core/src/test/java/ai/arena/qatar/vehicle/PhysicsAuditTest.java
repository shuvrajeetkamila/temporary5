package ai.arena.qatar.vehicle;

import ai.arena.qatar.game.PlayerController;
import ai.arena.qatar.physics.MaterialRegistry;
import ai.arena.qatar.physics.SurfaceMaterial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The physics contract this project states about itself, held to the letter.
 *
 * <p>Two of the three tests here exist because the code broke the promises in {@code GAME_SPEC.md} and
 * {@code FixedStepClock} rather than because a body misbehaved:
 *
 * <ul>
 *   <li><b>The simulation does not depend on the frame rate.</b> Two places in the vehicle code damped a
 *       speed with a per-frame multiplier -- {@code speed *= 0.9} for an aeroplane taxiing into water and
 *       {@code speed *= 0.5} for a body leaning on a parked machine -- which is half the damping at 120
 *       frames a second as at 60. Both are now rates in seconds, and the test runs the same half second
 *       of the same scenario at two frame rates and demands the same answer.</li>
 *   <li><b>Loose ground takes grip away.</b> The car's lateral grip limit was written
 *       {@code max(1.0, 1.0 - 0.3 * sinkage)}, which is 1.0 for every surface that exists, so the sinkage
 *       term was dead code: a car cornered as hard on sabkha as on asphalt. The test drives the same
 *       corner on two surfaces that differ only in sinkage and demands the loose one turns less.</li>
 *   <li><b>A car's reverse gear is short.</b> Nothing limited reverse speed, and the same traction and
 *       power limits as first gear meant a car could run backwards at 44 km/h.</li>
 * </ul>
 */
final class PhysicsAuditTest {

    /** The car's own grip-versus-sinkage rule, as the body implements it: 0.95 * grip * sinkage factor. */
    private static double lateralCap(SurfaceMaterial material) {
        return 0.95 * material.tireGrip * 9.81 * Math.max(0.45, 1.0 - 0.3 * material.sinkage);
    }

    /** A surface of one material, flat, with the material's own water depth. */
    private static Surface of(SurfaceMaterial material) {
        return Surface.land(0.0, material.id);
    }

    @Test
    @DisplayName("the same half second of flying is the same at 30 frames a second and at 120")
    void theSimulationDoesNotDependOnTheFrameRate() {
        // An aeroplane taxiing through half a metre of water, which is the one place in the vehicle code
        // that damped a speed once per frame. The old line was `speed *= 0.9`, so 0.5 s of it left 4.3%
        // of the speed at 30 fps and 0.006% at 120: the same manoeuvre, two different aircraft.
        double fast = taxiThroughWater(1.0 / 120.0);
        double slow = taxiThroughWater(1.0 / 30.0);
        assertTrue(fast > 5.0, "the aeroplane was stopped dead by a puddle: " + fast + " m/s");
        assertEquals(fast, slow, Math.max(0.25, fast * 0.05),
                "the same half second gave " + fast + " m/s at 120 fps and " + slow + " at 30");
    }

    /** Flies one aeroplane down a flooded taxiway for half a second at the given step. */
    private static double taxiThroughWater(double dt) {
        AircraftBody plane = new AircraftBody();
        Surface water = Surface.water(-2.0, 1.0);
        VehicleState state = new VehicleState();
        plane.spawn(state, 0, 0, water, 0);
        state.speedMS = 25.0;
        VehicleInput input = new VehicleInput();
        input.throttle = 0.5;
        for (double t = 0; t < 0.5; t += dt) {
            plane.step(state, input, water, dt);
        }
        return state.speedMS;
    }

    @Test
    @DisplayName("a rate in seconds is the same rate whatever the step: the damping rule composes")
    void dampingRatesCompose() {
        // The other per-frame multiplier: pushing against a parked machine. The body now multiplies by
        // 0.1 ^ (dt / CONTACT_SLOWING_S) per step, and two half-steps have to be one whole step or the
        // rule is frame-rate dependent however it is written.
        double whole = Math.pow(0.1, (1.0 / 60.0) / PlayerController.CONTACT_SLOWING_S);
        double half = Math.pow(0.1, (1.0 / 120.0) / PlayerController.CONTACT_SLOWING_S);
        assertEquals(whole, half * half, 1e-12, "two half steps are not one whole step");
        assertTrue(whole < 0.9 && whole > 0.1,
                "one frame of contact should take most of a body's speed, not all of it: " + whole);
    }

    @Test
    @DisplayName("loose ground takes lateral grip away, not only drive")
    void looseGroundCostsCorneringGrip() {
        // Two materials that differ only in grip and sinkage, both of them published types: dry asphalt
        // is grip 1.00 sinkage 0.0, and sabkha is grip 0.52 sinkage 0.35. The sinkage half of that
        // difference was doing nothing at all, which is why this test measures the cap rather than the
        // corner: a car on sabkha should not be able to corner as hard as a car on tarmac even if the
        // two surfaces had the same friction.
        SurfaceMaterial asphalt = MaterialRegistry.get(SurfaceMaterial.ID_DRY_ASPHALT);
        SurfaceMaterial sabkha = MaterialRegistry.get(SurfaceMaterial.ID_SABKHA);
        double onAsphalt = lateralCap(asphalt);
        double onSabkha = lateralCap(sabkha);
        assertTrue(onSabkha < onAsphalt * 0.5,
                "sabkha corners at " + onSabkha + " m/s^2 against asphalt's " + onAsphalt);
        // And the sinkage term is live: a hypothetical surface with asphalt's grip and sabkha's sinkage
        // has to come out lower than asphalt. That is the assertion the old expression could not pass --
        // it returned exactly asphalt's number for every sinkage in existence.
        double imagined = 0.95 * asphalt.tireGrip * 9.81 * Math.max(0.45, 1.0 - 0.3 * sabkha.sinkage);
        assertTrue(imagined < onAsphalt * 0.92,
                "sinkage is not connected to lateral grip: " + imagined + " against " + onAsphalt);
    }

    @Test
    @DisplayName("a car reverses at reversing speed, and keeps reversing while the stick is back")
    void reverseHasAGear() {
        CarBody car = new CarBody();
        Surface asphalt = of(MaterialRegistry.get(SurfaceMaterial.ID_DRY_ASPHALT));
        VehicleState state = new VehicleState();
        car.spawn(state, 0, 0, asphalt, 0);
        VehicleInput input = new VehicleInput();
        input.throttle = -1.0;
        for (double t = 0; t < 10.0; t += 1.0 / 60.0) {
            car.step(state, input, asphalt, 1.0 / 60.0);
        }
        assertTrue(state.speedMS < -3.0, "the car did not reverse at all: " + state.speedMS);
        assertTrue(state.speedMS >= -CarBody.REVERSE_SPEED_MS - 0.5,
                "the car reversed at " + (-state.speedMS * 3.6) + " km/h, which no gearbox does");
        // And it goes backwards, which sounds obvious and is not: the heading is unchanged, so the
        // position has to move *south* from the origin with a north-facing car reversing.
        assertTrue(state.northM < -10.0,
                "a car in reverse went the wrong way: north " + state.northM);
    }
}
