package ai.arena.qatar.vehicle;

import java.util.Locale;

/**
 * A light twin helicopter: 2.2 t, a 5.6 m rotor, collective with a real lag, cyclic that tilts the
 * thrust vector, and pedals that point the nose.
 *
 * <p>The collective does not snap: a rotor has inertia, so thrust chases the lever with a first-order
 * lag, and the machine climbs when the lever is above the weight it is carrying and sinks when it is
 * below. Descent is damped by the rotor's own airflow, which is why a helicopter settles rather than
 * falls. Forward speed comes from tilting the disc, and the disc has a limit: past about 20 degrees of
 * tilt the machine is trading height for speed, and above roughly 70 m/s retreating-blade stall and
 * available power stop it -- modelled here as thrust that falls away with speed.
 *
 * <p>Translational lift and ground effect are not modelled. Both make a real helicopter cheaper to fly
 * at speed and near the ground; leaving them out costs range and climb performance, never safety.
 */
public final class HelicopterBody extends VehicleBody {

    public static final double MASS_KG = 2200.0;
    public static final double ROTOR_RADIUS_M = 5.6;
    public static final double DISC_AREA_M2 = Math.PI * ROTOR_RADIUS_M * ROTOR_RADIUS_M;
    /** Maximum rotor thrust, 1.9 times the weight: enough for a 2200 kg machine to climb hard. */
    public static final double THRUST_MAX_N = 1.9 * MASS_KG * G;
    /** Time constant of the rotor answering the collective, seconds. */
    public static final double COLLECTIVE_TAU_S = 0.35;
    /** Largest cyclic tilt of the disc, radians. */
    public static final double TILT_MAX_RAD = Math.toRadians(20.0);
    /** Rate at which the cyclic moves the disc, degrees per second. */
    public static final double TILT_RATE_DEG_S = 40.0;
    /** Effective vertical drag area of the rotor, as a multiple of the disc. */
    public static final double VERTICAL_AREA_FACTOR = 4.0;
    /** Fuselage and rotor-head drag area, m^2. */
    public static final double DRAG_AREA_M2 = 2.6;
    public static final double SKID_HEIGHT_M = 0.4;
    public static final double YAW_RATE_MAX_DEG_S = 70.0;
    public static final double YAW_RATE_RATE_DEG_S2 = 90.0;
    /** Speed at which the rotor starts losing authority to power and blade limits, m/s. */
    public static final double SPEED_LIMIT_MS = 70.0;
    public static final double CLIMB_LIMIT_MS = 12.0;
    public static final double DESCENT_LIMIT_MS = 12.0;

    /** Thrust fraction of the rotor, in 0..1, lagging the collective. Persisted between steps. */
    private double rotorFraction;
    private double tiltPitchRad;
    private double tiltRollRad;
    private double yawRateDegS;

    @Override
    public String label() {
        return "HELI";
    }

    @Override
    public String labelAr() {
        return "مروحية";
    }

    @Override
    public double massKg() {
        return MASS_KG;
    }

    /** Forgets any rotor state, so a spawn is a spawn and not a continuation of the last flight. */
    public void resetRotor() {
        rotorFraction = 0;
        tiltPitchRad = 0;
        tiltRollRad = 0;
        yawRateDegS = 0;
    }

    @Override
    public void spawn(VehicleState state, double east, double north, Surface surface, double heading) {
        super.spawn(state, east, north, surface, heading);
        resetRotor();
        state.heightM = surface.groundHeightM(x, z) + SKID_HEIGHT_M;
    }

    @Override
    public void step(VehicleState state, VehicleInput input, Surface surface, double dt) {
        double x = state.eastM;
        double z = state.northM;
        if (!surface.collisionLoaded(x, z)) {
            state.blocked = true;
            return;
        }
        state.blocked = false;

        double density = airDensity(state.heightM);
        double densityFactor = Math.max(0.2, density / SEA_LEVEL_DENSITY);
        double weight = MASS_KG * G;
        double speed = state.speedMS;
        double absSpeed = Math.abs(speed);

        // ---- rotor ---------------------------------------------------------------------------------
        double collective = clamp(input.collective, 0, 1);
        rotorFraction = approach(rotorFraction, collective, dt / COLLECTIVE_TAU_S);
        // Power and retreating-blade limits: the disc cannot keep its thrust as speed builds.
        double speedFactor = clamp(1.0 - 0.35 * (absSpeed / SPEED_LIMIT_MS), 0.45, 1.0);
        double thrust = rotorFraction * THRUST_MAX_N * densityFactor * speedFactor;

        // ---- cyclic --------------------------------------------------------------------------------
        double targetPitchTilt = clamp(input.pitch, -1, 1) * TILT_MAX_RAD;
        double targetRollTilt = clamp(input.roll, -1, 1) * TILT_MAX_RAD;
        double tiltRate = Math.toRadians(TILT_RATE_DEG_S) * dt;
        tiltPitchRad = approach(tiltPitchRad, targetPitchTilt, tiltRate);
        tiltRollRad = approach(tiltRollRad, targetRollTilt, tiltRate);

        // ---- vertical ------------------------------------------------------------------------------
        double verticalThrust = thrust * Math.cos(tiltPitchRad) * Math.cos(tiltRollRad);
        double verticalDrag = 0.5 * density * DISC_AREA_M2 * VERTICAL_AREA_FACTOR
                * state.verticalMS * Math.abs(state.verticalMS);
        double verticalAccel = (verticalThrust - weight - verticalDrag) / MASS_KG;
        state.verticalMS = clamp(state.verticalMS + verticalAccel * dt, -DESCENT_LIMIT_MS, CLIMB_LIMIT_MS);
        state.rotorClimb = verticalThrust > weight;

        // ---- horizontal: the tilted disc pulls the machine forward ---------------------------------
        double forwardForce = thrust * Math.sin(tiltPitchRad);
        double lateralForce = thrust * Math.sin(tiltRollRad);
        double drag = 0.5 * density * DRAG_AREA_M2 * absSpeed * absSpeed;
        boolean airborne = !state.grounded;
        double alongAccel = (forwardForce - sign(speed) * drag) / MASS_KG;
        if (!airborne) {
            // On the skids the machine can still be walked along the ground, but friction and the
            // ground's own slope resistance dominate; a helicopter does not taxi at cruise power.
            alongAccel = (forwardForce - sign(speed) * (drag + 0.35 * weight)) / MASS_KG;
        }
        state.accelMS2 = alongAccel;
        state.speedMS = speed + alongAccel * dt;
        if (airborne && Math.abs(lateralForce) > drag * 0.5) {
            // Lateral cyclic slips the machine sideways; it is a real (and uncomfortable) manoeuvre.
            double heading = Math.toRadians(state.headingDegrees);
            double sideRate = lateralForce / MASS_KG * dt;
            state.eastM += Math.cos(heading) * sideRate;
            state.northM -= Math.sin(heading) * sideRate;
        }
        if (!airborne && Math.abs(state.speedMS) < 0.2 && Math.abs(input.pitch) < 0.05) {
            state.speedMS = 0;
        }
        state.speedMS = clamp(state.speedMS, -8, SPEED_LIMIT_MS * 1.25);

        // ---- pedals --------------------------------------------------------------------------------
        double targetYawRate = clamp(input.yaw, -1, 1) * YAW_RATE_MAX_DEG_S * clamp(0.4 + rotorFraction, 0, 1);
        yawRateDegS = approach(yawRateDegS, targetYawRate, YAW_RATE_RATE_DEG_S2 * dt);
        state.headingDegrees = VehicleState.wrap(state.headingDegrees + yawRateDegS * dt);

        // ---- where that leaves the machine ---------------------------------------------------------
        double heading = Math.toRadians(state.headingDegrees);
        double travel = 0.5 * (speed + state.speedMS) * dt;
        state.eastM += Math.sin(heading) * travel;
        state.northM += Math.cos(heading) * travel;
        state.odometerM += Math.abs(travel);

        double ground = surface.groundHeightM(state.eastM, state.northM);
        double waterSurface = surface.waterSurfaceM(state.eastM, state.northM);
        double floor = Math.max(ground + SKID_HEIGHT_M, waterSurface + 0.6);
        state.heightM += state.verticalMS * dt;
        if (state.heightM <= floor) {
            state.heightM = floor;
            if (state.verticalMS < 0) {
                state.verticalMS = 0;
            }
            state.grounded = true;
        } else {
            state.grounded = false;
        }

        // ---- attitude shown to the player -----------------------------------------------------------
        // The fuselage hangs under the disc: nose down when accelerating, banked in a turn.
        state.pitchDegrees = (float) -Math.toDegrees(tiltPitchRad) * 0.6f + (float) (clamp(-state.accelMS2, -10, 10) * 0.3f);
        state.rollDegrees = (float) Math.toDegrees(-tiltRollRad) * 0.6f;
        state.slip = 0;
        state.sinkage = 0;
        state.stalled = false;
        state.floating = false;
        state.wading = false;
    }

    @Override
    public String instrument(VehicleState state) {
        double density = airDensity(state.heightM);
        double margin = rotorFraction * THRUST_MAX_N * Math.max(0.2, density / SEA_LEVEL_DENSITY)
                - MASS_KG * G;
        return String.format(Locale.ROOT, "%s  %.0f km/h  alt %.0f m  v/s %+.1f  thrust %+.0f kN%s",
                label(), state.speedMS * 3.6, state.heightM, state.verticalMS, margin / 1000.0,
                state.grounded ? "  ON GROUND" : "");
    }
}
