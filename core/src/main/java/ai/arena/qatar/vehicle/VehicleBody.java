package ai.arena.qatar.vehicle;

import java.util.Locale;

/**
 * One vehicle body: the physics of a person, a car, an aeroplane, a helicopter or a boat.
 *
 * <p>Every body is a reduced-order model of a real machine, integrated with an explicit step at the
 * physics rate. They are not general rigid bodies: a car does not roll over, a wing does not have
 * sideslip, a hull does not have six degrees of freedom. What they do have is the behaviour that makes
 * each one feel like itself -- a car that loses grip in sand, a wing that stalls, a rotor that takes a
 * moment to answer the collective, a hull that rises onto the plane -- and every number in them is a
 * published figure for the real thing or is derived from one.
 *
 * <p>The bodies never touch libGDX, GL, or the bundles: they read a {@link Surface} and write a
 * {@link VehicleState}, which is why a JVM test can fly the aeroplane without a phone.
 */
public abstract class VehicleBody {

    /** Standard gravity, the same value the contact solver uses. */
    public static final double G = 9.80665;
    /** Sea-level air density, kg/m^3. */
    public static final double SEA_LEVEL_DENSITY = 1.225;
    /** Sea water density, kg/m^3. */
    public static final double SEA_WATER_DENSITY = 1025.0;

    /** Short uppercase label, matching the HUD and the locomotion enum. */
    public abstract String label();

    /** Arabic label for the same button. */
    public abstract String labelAr();

    /** Operating mass with a nominal load, kilograms. */
    public abstract double massKg();

    /** Advances the body by one physics step. {@code dt} is seconds, and is never zero or negative. */
    public abstract void step(VehicleState state, VehicleInput input, Surface surface, double dt);

    /** The HUD's one-line instrument readout for this body. */
    public String instrument(VehicleState state) {
        return String.format(Locale.ROOT, "%s  %.1f m/s  %.0f m", label(), state.speedMS, state.heightM);
    }

    /** Air density at an altitude, from the standard atmosphere's scale height. */
    public static double airDensity(double altitudeM) {
        return SEA_LEVEL_DENSITY * Math.exp(-altitudeM / 8500.0);
    }

    /** Puts the state on the ground at a spawn point, facing a heading, stopped. */
    public void spawn(VehicleState state, double east, double north, Surface surface, double heading) {
        state.reset(east, north, surface.groundHeightM(), heading);
    }

    protected static double clamp(double v, double low, double high) {
        return v < low ? low : (v > high ? high : v);
    }

    /** Applies an acceleration for a step, keeping the sign conventions in one place. */
    protected static double approach(double value, double target, double maxDelta) {
        double delta = target - value;
        if (delta > maxDelta) {
            return value + maxDelta;
        }
        if (delta < -maxDelta) {
            return value - maxDelta;
        }
        return target;
    }

    protected static double sign(double v) {
        return v < 0 ? -1 : (v > 0 ? 1 : 0);
    }
}
