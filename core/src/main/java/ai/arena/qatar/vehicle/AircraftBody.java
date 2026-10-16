package ai.arena.qatar.vehicle;

import java.util.Locale;

/**
 * A light aeroplane: 1.04 t, 16.2 m^2 of wing, 2.25 kN of static thrust -- the numbers of a Cessna
 * 172, because those are the numbers anybody can check.
 *
 * <p>Point mass on a flight path, with a real lift curve. Angle of attack is trimmed to carry the
 * weight, so the wing stalls when the aeroplane is too slow or pulling too hard; the stall drops the
 * nose, adds separated-flow drag, and costs altitude. Speed and flight path are coupled the way they
 * are in the air: pitching up trades speed for height, and a dive gets the speed back. On the ground
 * it is a tricycle-gear aircraft rolling on its wheels, and it leaves the ground when the wing can
 * carry it -- never because a key says "take off".
 *
 * <p>No sideslip, no spin, no flap detents, no ground effect. The angle of attack is trimmed rather
 * than commanded, which is the single biggest simplification: it makes the model honest about stall
 * and cruise without pretending to be a flight simulator.
 */
public final class AircraftBody extends VehicleBody {

    public static final double MASS_KG = 1043.0;
    public static final double WING_AREA_M2 = 16.2;
    public static final double SPAN_M = 11.0;
    public static final double OSWALD_E = 0.8;
    /** Lift-curve slope, per radian, thin-airfoil-with-a-real-plane's-worth of correction. */
    public static final double CL_SLOPE = 5.2;
    public static final double ALPHA_STALL_RAD = 0.29;
    public static final double CL_MAX = CL_SLOPE * ALPHA_STALL_RAD;
    public static final double CD0 = 0.031;
    /** Static thrust of the propeller, newtons: the cap it cannot exceed at low speed. */
    public static final double THRUST_MAX_N = 2250.0;
    /** 110 kW (160 hp) at the shaft. */
    public static final double ENGINE_POWER_W = 110_000.0;
    public static final double PROP_EFFICIENCY = 0.80;
    /**
     * The wing's angle of attack while the wheels are carrying the aeroplane. On the runway the wing is
     * not lifting anything, so it is neither stalled nor draggy -- saturating it there was a real
     * modelling bug: it capped the ground roll at 65 km/h and the aeroplane never reached rotation speed.
     */
    public static final double CL_ROLL = 0.35;
    /** The attitude the pilot rotates to before the wheels leave: 16 degrees of nose-up. */
    public static final double ROTATION_PITCH_DEG = 16.0;
    /** How fast the elevator can raise the nose on the ground, degrees per second. */
    public static final double ROTATION_RATE_DEG_S = 10.0;
    public static final double GEAR_HEIGHT_M = 0.9;
    /** Seconds for a speed to halve when the wheels are in water too deep to taxi through. */
    public static final double WADING_HALVING_S = 0.6;
    public static final double GROUND_WHEELBASE_M = 1.65;
    public static final double ROLLING_COEFFICIENT = 0.03;
    public static final double BRAKE_FORCE_N = 4000.0;
    public static final double ELEVATOR_RATE_DEG_S = 35.0;
    public static final double AILERON_RATE_DEG_S = 60.0;
    public static final double RUDDER_RATE_DEG_S = 25.0;
    public static final double MAX_PITCH_DEG = 55.0;
    public static final double MAX_ROLL_DEG = 70.0;
    /** A touch-down harder than this scrubs speed; anything gentler costs nothing. */
    public static final double HARD_LANDING_MS = 5.0;
    /** Sink rate in a fully developed stall, m/s. */
    public static final double STALL_SINK_MS = 9.0;
    /** Never exceed speed, m/s (331 km/h). */
    public static final double VNE_MS = 92.0;
    /** The attitude a stalled aeroplane settles into while it recovers: nose down, not diving. */
    public static final double STALL_RECOVERY_PITCH_DEG = -25.0;
    /** Nose-down rate once the wing is stalled. */
    public static final double STALL_NOSE_DROP_DEG_S = 22.0;

    private static final double INDUCED_K = 1.0 / (Math.PI * (SPAN_M * SPAN_M / WING_AREA_M2) * OSWALD_E);

    @Override
    public String label() {
        return "PLANE";
    }

    @Override
    public String labelAr() {
        return "طائرة";
    }

    @Override
    public double massKg() {
        return MASS_KG;
    }

    /** Stall speed at the current density and attitude: the speed at which CL_max carries the weight. */
    public double stallSpeed(double density, double rollDegrees) {
        double load = Math.cos(Math.toRadians(clamp(rollDegrees, -MAX_ROLL_DEG, MAX_ROLL_DEG)));
        double cl = Math.max(0.05, CL_MAX) * Math.max(0.25, load);
        return Math.sqrt(2.0 * MASS_KG * G / Math.max(1e-6, density * WING_AREA_M2 * cl));
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

        double ground = surface.groundHeightM(x, z);
        double depth = surface.waterDepthM(x, z);
        state.wading = false;
        state.floating = false;

        double pitchInput = clamp(input.pitch, -1, 1);
        double rollInput = clamp(input.roll, -1, 1);
        double yawInput = clamp(input.yaw, -1, 1);
        double throttle = clamp(input.throttle, 0, 1);
        double brake = clamp(input.brake, 0, 1);

        double density = airDensity(state.heightM);
        double speed = state.speedMS;
        if (speed < 0) {
            speed = 0;                       // an aeroplane does not taxi backwards in this model
        }
        double q = 0.5 * density * speed * speed;

        // ---- the wing ---------------------------------------------------------------------------
        double weight = MASS_KG * G;
        double clNeeded = q > 1e-3 ? weight * Math.cos(Math.toRadians(state.rollDegrees)) / (q * WING_AREA_M2)
                : CL_MAX * 4.0;
        double alphaTrim = clNeeded / CL_SLOPE;
        boolean wheelsCarry = state.grounded;
        boolean stalled = !wheelsCarry && alphaTrim > ALPHA_STALL_RAD;
        if (!stalled && !wheelsCarry && state.stalled && speed < stallSpeed(density, state.rollDegrees)) {
            stalled = true;                  // recovery takes speed, not just a lower demand
        }
        double clEffective = wheelsCarry ? CL_ROLL
                : (stalled ? CL_MAX * 0.72 : Math.min(clNeeded, CL_MAX));
        double liftDeficit = stalled ? clamp(1.0 - clEffective / Math.max(1e-3, clNeeded), 0, 1) : 0;
        double cd = wheelsCarry ? CD0 + INDUCED_K * CL_ROLL * CL_ROLL
                : (stalled ? CD0 + 3.0 * INDUCED_K * CL_MAX * CL_MAX
                        : CD0 + INDUCED_K * clEffective * clEffective);
        double drag = q * WING_AREA_M2 * cd;
        // Available thrust: a propeller converts shaft power into thrust over speed, so it falls with
        // speed, but it cannot exceed what the propeller can hold at a standstill.
        double thrust = throttle * Math.min(THRUST_MAX_N, ENGINE_POWER_W * PROP_EFFICIENCY / Math.max(8.0, speed))
                * Math.max(0.2, density / SEA_LEVEL_DENSITY);

        // ---- attitude ---------------------------------------------------------------------------
        boolean onWheels = state.grounded || state.heightM <= ground + GEAR_HEIGHT_M + 0.02;
        if (state.grounded) {
            // Rotation happens on the ground, not in the air: the elevator raises the nose while the
            // wheels are still rolling, and the aeroplane leaves the runway because of the attitude the
            // pilot has already set, not because a flag says "flying".
            double rotationTarget = pitchInput > 0.05 ? ROTATION_PITCH_DEG * pitchInput : 0.0;
            state.pitchDegrees = clamp(approach(state.pitchDegrees, rotationTarget, ROTATION_RATE_DEG_S * dt),
                    0.0, MAX_PITCH_DEG);
            state.rollDegrees = approach(state.rollDegrees, 0.0, 20.0 * dt);
        } else {
            double targetPitch = clamp(state.pitchDegrees + pitchInput * ELEVATOR_RATE_DEG_S * dt,
                    -MAX_PITCH_DEG, MAX_PITCH_DEG);
            if (stalled) {
                // The nose drops to a recovery attitude and stays there: a stall is not a permanent dive,
                // and the speed it builds is what reattaches the flow.
                targetPitch = Math.max(targetPitch - STALL_NOSE_DROP_DEG_S * dt, STALL_RECOVERY_PITCH_DEG);
            } else if (Math.abs(pitchInput) < 0.05) {
                // Hands off, the aeroplane trims itself: it settles at the attitude this speed needs. A
                // light aircraft is stable in pitch, and a model without that flies nothing like one.
                targetPitch = approach(state.pitchDegrees,
                        Math.toDegrees(Math.min(alphaTrim, ALPHA_STALL_RAD)), 6.0 * dt);
            }
            state.pitchDegrees = clamp(targetPitch, -MAX_PITCH_DEG, MAX_PITCH_DEG);
            state.rollDegrees = clamp(state.rollDegrees + rollInput * AILERON_RATE_DEG_S * dt,
                    -MAX_ROLL_DEG, MAX_ROLL_DEG);
            if (Math.abs(rollInput) < 0.05) {
                // Wings level on their own: a 172 has enough dihedral to matter.
                state.rollDegrees = approach(state.rollDegrees, 0, 12 * dt);
            }
        }

        // ---- the flight path --------------------------------------------------------------------
        double gammaRad;
        if (state.grounded || onWheels && state.grounded) {
            gammaRad = 0;
        } else {
            double alphaUsed = Math.min(alphaTrim, ALPHA_STALL_RAD);
            gammaRad = Math.toRadians(state.pitchDegrees) - alphaUsed;
        }
        double vertical = speed * Math.sin(gammaRad) - STALL_SINK_MS * liftDeficit;
        double along = (thrust - drag) / MASS_KG - G * Math.sin(gammaRad);
        if (stalled) {
            along -= 1.6 * liftDeficit;      // separated flow is draggy as well as slow
        }
        if (state.grounded) {
            double rolling = ROLLING_COEFFICIENT * weight;
            along = (thrust - drag - rolling) / MASS_KG - brake * BRAKE_FORCE_N / MASS_KG;
            vertical = 0;
        }

        state.verticalMS = vertical;
        state.accelMS2 = along;
        state.speedMS = clamp(speed + along * dt, 0, VNE_MS * 1.15);

        // ---- rotation: the wing decides, not the keyboard ---------------------------------------
        if (state.grounded) {
            double stall = stallSpeed(density, 0);
            boolean wingCanCarry = q * WING_AREA_M2 * Math.min(clNeeded, CL_MAX) >= weight * 0.98;
            if (wingCanCarry && state.speedMS > stall * 0.98 && pitchInput > 0.05) {
                // It leaves the ground along the flight path its own attitude gives it: the angle of the
                // nose less the angle of attack the wing needs at this speed.
                double liftGamma = Math.toRadians(state.pitchDegrees) - Math.min(alphaTrim, ALPHA_STALL_RAD);
                state.grounded = false;
                state.heightM = Math.max(state.heightM, ground + GEAR_HEIGHT_M + 0.05);
                state.verticalMS = Math.max(0.3, state.speedMS * Math.sin(liftGamma));
            }
        }

        // ---- where it goes ----------------------------------------------------------------------
        double heading = Math.toRadians(state.headingDegrees);
        double yawRateDegS;
        if (state.grounded) {
            yawRateDegS = Math.toDegrees(state.speedMS * Math.tan(0.5 * yawInput) / GROUND_WHEELBASE_M);
        } else {
            // Coordinated turn: the horizontal component of lift does the turning.
            double rate = state.speedMS > 1.0 ? G * Math.tan(Math.toRadians(state.rollDegrees)) / state.speedMS
                    : 0;
            yawRateDegS = Math.toDegrees(rate) + yawInput * RUDDER_RATE_DEG_S * 0.4;
        }
        state.headingDegrees = VehicleState.wrap(state.headingDegrees + yawRateDegS * dt);

        double travel = 0.5 * (speed + state.speedMS) * dt;
        state.eastM += Math.sin(heading) * travel;
        state.northM += Math.cos(heading) * travel;
        state.odometerM += Math.abs(travel);

        // ---- the ground -------------------------------------------------------------------------
        double groundHere = surface.groundHeightM(state.eastM, state.northM);
        if (state.grounded) {
            state.heightM = groundHere + GEAR_HEIGHT_M;
            state.verticalMS = 0;
        } else {
            state.heightM += state.verticalMS * dt;
            double ceiling = groundHere + GEAR_HEIGHT_M;
            if (state.heightM <= ceiling) {
                double arrival = state.verticalMS;
                state.heightM = ceiling;
                state.grounded = true;
                state.verticalMS = 0;
                if (arrival < -HARD_LANDING_MS) {
                    // Only a genuinely hard arrival costs anything. A flat penalty on every touchdown is
                    // wrong: an aeroplane skimming back onto the runway at a metre per second would be
                    // braked to a standstill by its own touch-down, which is what stopped this one from
                    // ever accelerating past rotation speed.
                    double scrub = clamp((-arrival - HARD_LANDING_MS) / 15.0, 0.0, 0.40);
                    state.speedMS *= 1.0 - scrub;
                    state.pitchDegrees -= 20.0 * scrub;
                }
            }
        }

        // ---- honesty flags for the HUD ----------------------------------------------------------
        state.stalled = stalled;
        state.slip = 0;
        state.sinkage = 0;
        state.rotorClimb = false;
        boolean overWater = Surface.isWater(surface.materialId(x, z));
        if (state.grounded && depth > 0.5 && overWater) {
            state.wading = true;
            // Taxiing into the sea is not a thing: the wheels are in half a metre of water and the
            // machine is going nowhere. Expressed as a rate rather than as a per-frame factor -- the
            // old line was `speed *= 0.9`, which is a different physics at 30 frames a second and at
            // 120, and this project's own contract is that the simulation does not depend on the frame
            // rate (see FixedStepClock). Six tenths of a second per halving.
            state.speedMS *= Math.pow(0.5, dt / WADING_HALVING_S);
        }
    }

    @Override
    public String instrument(VehicleState state) {
        String note = state.stalled ? "  STALL" : "";
        double density = airDensity(state.heightM);
        return String.format(Locale.ROOT, "%s  %.0f km/h  alt %.0f m  Vs %.0f km/h%s", label(),
                state.speedMS * 3.6, state.heightM, stallSpeed(density, state.rollDegrees) * 3.6, note);
    }
}
