package ai.arena.qatar.vehicle;

/**
 * One frame of control input, in the vehicle's own terms, shared by all five bodies.
 *
 * <p>The HUD produces these axes; a body reads only the ones it has. A car has no collective, a
 * helicopter has no brake pedal, and neither should have to pretend otherwise. Axes are unitless and
 * already smoothed by the input layer, so a body can be tested by handing it a constant vector and
 * stepping it.
 *
 * <p>Naming follows the vehicle that owns the axis: {@link #throttle} is engine/propulsion demand for
 * every body, {@link #steer} is a road wheel or a rudder, {@link #pitch}/{@link #roll}/{@link #yaw}
 * are attitude controls (elevator/aileron/rudder on the aeroplane, cyclic on the helicopter), and
 * {@link #collective} is rotor thrust only.
 */
public final class VehicleInput {

    /** Propulsion demand: -1 reverses a boat, brakes a car, and does nothing to a person. */
    public double throttle;
    /** Wheel brake 0..1 (car, aeroplane on the runway, boat astern). */
    public double brake;
    /** Steering -1 left .. +1 right. */
    public double steer;
    /** Attitude: -1 nose down .. +1 nose up (elevator, or fore/aft cyclic). */
    public double pitch;
    /** Attitude: -1 left .. +1 right (aileron, or lateral cyclic). */
    public double roll;
    /** Yaw: -1 left .. +1 right (rudder, pedals, nose wheel). */
    public double yaw;
    /** Rotor thrust demand 0..1 (helicopter collective). */
    public double collective;
    /** Straight sideways input, used by a walking person and ignored by everything else. */
    public double strafe;
    /** A discrete request: jump for the person, nothing for the machines. */
    public boolean jump;
    /** Run, boost, or full military power depending on the body. */
    public boolean boost;

    public void clear() {
        throttle = 0;
        brake = 0;
        steer = 0;
        pitch = 0;
        roll = 0;
        yaw = 0;
        collective = 0;
        strafe = 0;
        jump = false;
        boost = false;
    }

    /** Clamps every axis into its legal range, so a body never has to defend itself. */
    public void clamp() {
        throttle = clampAxis(throttle);
        brake = clampUnit(brake);
        steer = clampAxis(steer);
        pitch = clampAxis(pitch);
        roll = clampAxis(roll);
        yaw = clampAxis(yaw);
        collective = clampUnit(collective);
        strafe = clampAxis(strafe);
    }

    private static double clampAxis(double v) {
        return v < -1 ? -1 : (v > 1 ? 1 : v);
    }

    private static double clampUnit(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "VehicleInput(throttle=%.2f brake=%.2f steer=%.2f pitch=%.2f roll=%.2f yaw=%.2f collective=%.2f)",
                throttle, brake, steer, pitch, roll, yaw, collective);
    }
}
