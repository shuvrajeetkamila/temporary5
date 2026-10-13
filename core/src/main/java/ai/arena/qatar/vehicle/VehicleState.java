package ai.arena.qatar.vehicle;

import java.util.Locale;

/**
 * The full motion state of whatever the player is being, in local world metres.
 *
 * <p>Local means the floating-origin frame the renderer and {@code TerrainSampler} already use: the
 * numbers are small, and nothing here is near 431,000 m where float precision dies. Height is the
 * altitude of the body's own reference point (feet for a person, contact patch for a car, hull bottom
 * for a boat, gear for an aircraft), never the eye or the camera: those are the camera rig's business.
 *
 * <p>Every field is public and mutable. The bodies are the only writers, the HUD and the camera are
 * readers, and a test can start a simulation anywhere in any attitude without a builder chain.
 */
public final class VehicleState {

    /** Local east, metres. */
    public double eastM;
    /** Local north, metres. */
    public double northM;
    /** Altitude of the body reference point, metres above the world datum. */
    public double heightM;
    /** Yaw, degrees: 0 faces north, 90 faces east (the same convention as the camera rig). */
    public double headingDegrees;
    /** Speed along the heading, m/s; negative means moving backwards. */
    public double speedMS;
    /** Vertical speed, m/s, positive up. */
    public double verticalMS;
    /** Pitch attitude, degrees, positive nose up. */
    public double pitchDegrees;
    /** Roll attitude, degrees, positive right wing down. */
    public double rollDegrees;
    /** Longitudinal acceleration actually achieved, m/s^2 (for the HUD and for skid evidence). */
    public double accelMS2;
    /** 0..1: how much the wheels/feet are slipping on the surface. */
    public double slip;
    /** 0..1: how far the tyres/hull have sunk into a soft surface. */
    public double sinkage;
    /** True while the body is supported by ground contact (wheels, feet, skids, beached hull). */
    public boolean grounded;
    /** True while the body is supported by water (floating, not beaching). */
    public boolean floating;
    /** True when the wing is past its critical angle of attack; only the aeroplane can be stalled. */
    public boolean stalled;
    /** True when the rotor is delivering thrust above the weight of the machine. */
    public boolean rotorClimb;
    /** True when a step or a slope refused the requested movement this tick. */
    public boolean blocked;
    /** True while moving through water shallow enough to wade (a person or a car). */
    public boolean wading;
    /** Metres travelled since the state was reset, for the HUD odometer. */
    public double odometerM;

    public void reset(double east, double north, double height, double heading) {
        eastM = east;
        northM = north;
        heightM = height;
        headingDegrees = wrap(heading);
        speedMS = 0;
        verticalMS = 0;
        pitchDegrees = 0;
        rollDegrees = 0;
        accelMS2 = 0;
        slip = 0;
        sinkage = 0;
        grounded = true;
        floating = false;
        stalled = false;
        rotorClimb = false;
        blocked = false;
        wading = false;
        odometerM = 0;
    }

    /** Forward unit vector as {east, north}: the one place this convention is written down twice. */
    public double[] forward() {
        double heading = Math.toRadians(headingDegrees);
        return new double[]{Math.sin(heading), Math.cos(heading)};
    }

    public double groundSpeedMS() {
        return Math.abs(speedMS);
    }

    public static double wrap(double degrees) {
        double d = degrees % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    /** Compact bilingual-free line: the HUD adds the labels, this adds the numbers. */
    public String describe() {
        return String.format(Locale.ROOT, "%.0f m/s  alt %.0f m  hdg %.0f",
                speedMS, heightM, headingDegrees);
    }

    @Override
    public String toString() {
        return describe();
    }
}
