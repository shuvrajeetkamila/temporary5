package ai.arena.qatar.vehicle;

import java.util.Locale;

/**
 * A 6.5 m planing motor boat: 1.85 t, one 3000 N propeller, a 2.2 m beam and a 0.55 m hull.
 *
 * <p>Two resistances and two lifts, all of them real effects with the right shape: viscous drag rises
 * with the square of speed, wave-making resistance follows Froude number, buoyancy falls as the hull
 * climbs, and hydrodynamic lift over the planing bottom rises with the square of speed until the hull
 * is running on a small wetted patch. That is what makes the model behave like a boat instead of a car
 * on a blue surface: it squats, it climbs onto the plane, its bow comes down, and it tops out around
 * 34 knots because the propeller is out of thrust, not because a constant says so.
 *
 * <p>The heave axis is damped to about 60% of critical, so the hull settles in a second or so and does
 * not oscillate like a spring. Beaching is real: if the water is shallower than the hull, the hull
 * rests on the seabed and drags.
 */
public final class BoatBody extends VehicleBody {

    public static final double MASS_KG = 1850.0;
    public static final double LENGTH_M = 6.5;
    public static final double BEAM_M = 2.2;
    public static final double DRAFT_M = 0.55;
    /** Waterplane area of the hull at rest: 0.7 * length * beam for a planing hull. */
    public static final double WATERPLANE_AREA_M2 = 0.7 * LENGTH_M * BEAM_M;
    public static final double THRUST_MAX_N = 3000.0;
    public static final double REVERSE_THRUST_N = 1200.0;
    /** Viscous coefficient of the wetted surface (ITTC-like, at model scale). */
    public static final double SKIN_FRICTION = 0.006;
    /** Residuary (wave-making) resistance as a fraction of displacement at high Froude number. */
    public static final double RESIDUARY_FRACTION = 0.06;
    /** Heave damping as a fraction of critical: enough to settle, not enough to be rigid. */
    public static final double HEAVE_DAMPING_FRACTION = 0.6;
    /** Planing lift coefficient referred to the wetted planing area. */
    public static final double PLANE_CL = 0.16;
    /** Length of wetted bottom at rest, as a multiple of the hull length. */
    public static final double WETTED_LENGTH_FACTOR = 1.9;
    public static final double WETTED_LENGTH_MIN_M = 0.4;
    public static final double WETTED_LENGTH_MAX_M = 4.5;
    public static final double RUDDER_YAW_MAX_DEG_S = 22.0;
    /** Extra resistance multiplier when the hull is sitting on the seabed. */
    public static final double BEACHED_DRAG = 20.0;

    @Override
    public String label() {
        return "BOAT";
    }

    @Override
    public String labelAr() {
        return "قارب";
    }

    @Override
    public double massKg() {
        return MASS_KG;
    }

    @Override
    public void spawn(VehicleState state, double east, double north, Surface surface, double heading) {
        // A boat spawns floating at its own waterline rather than on the seabed.
        double waterSurface = surface.waterSurfaceM(east, north);
        super.spawn(state, east, north, surface, heading);
        state.heightM = waterSurface - DRAFT_M * 0.35;
        state.floating = true;
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

        double weight = MASS_KG * G;
        double speed = state.speedMS;
        double absSpeed = Math.abs(speed);
        double waterSurface = surface.waterSurfaceM(x, z);
        double depth = surface.waterDepthM(x, z);
        boolean anyWater = depth > 0.02;
        boolean enoughWater = depth > DRAFT_M;
        boolean beached = anyWater && !enoughWater;
        boolean onLand = !anyWater;

        // ---- how much hull is in the water ---------------------------------------------------------
        double submerged = clamp(waterSurface - state.heightM, 0, DRAFT_M + 0.15);
        double submergedFraction = submerged / DRAFT_M;
        double buoyancy = anyWater
                ? SEA_WATER_DENSITY * G * WATERPLANE_AREA_M2 * submerged * (1.0 - 0.15 * submergedFraction)
                : 0.0;
        if (beached) {
            // The seabed carries what the water cannot: a hull in ankle-deep water is on the ground.
            buoyancy = Math.min(buoyancy, SEA_WATER_DENSITY * G * WATERPLANE_AREA_M2 * depth);
        }

        // ---- planing lift: water, not air, is what holds a planing hull up --------------------------
        double wetLength = clamp(WETTED_LENGTH_FACTOR * submergedFraction, WETTED_LENGTH_MIN_M,
                WETTED_LENGTH_MAX_M);
        double planingArea = BEAM_M * wetLength;
        double planingLift = anyWater
                ? 0.5 * SEA_WATER_DENSITY * absSpeed * absSpeed * planingArea * PLANE_CL * submergedFraction
                : 0.0;

        // ---- heave ---------------------------------------------------------------------------------
        double criticalDamping = 2.0 * MASS_KG * Math.sqrt(SEA_WATER_DENSITY * G * WATERPLANE_AREA_M2 / MASS_KG);
        double heaveDamping = HEAVE_DAMPING_FRACTION * criticalDamping * state.verticalMS;
        double heaveAccel = (buoyancy + planingLift - weight - heaveDamping) / MASS_KG;
        if (onLand) {
            heaveAccel = 0;
            state.verticalMS = 0;
        } else {
            state.verticalMS += heaveAccel * dt;
        }
        state.heightM += state.verticalMS * dt;
        double seabed = surface.groundHeightM(state.eastM, state.northM);
        if (state.heightM < seabed) {
            state.heightM = seabed;
            state.verticalMS = 0;
        }
        state.floating = anyWater && !beached && state.heightM + DRAFT_M > waterSurface - 0.02;

        // ---- resistance ----------------------------------------------------------------------------
        double wettedArea = wetLength * (BEAM_M + 2.0 * submerged);
        double viscous = 0.5 * SEA_WATER_DENSITY * speed * speed * wettedArea * SKIN_FRICTION;
        double froude = absSpeed / Math.sqrt(G * LENGTH_M);
        double froudeTerm = froude * froude / (1.0 + froude * froude);
        double residuary = RESIDUARY_FRACTION * weight * froudeTerm;
        double airDrag = 0.5 * airDensity(state.heightM) * 1.2 * speed * speed;
        double resistance = viscous + residuary + airDrag;
        if (onLand) {
            resistance = weight * 0.9;                      // dragging a hull over sand: not going far
        } else if (beached) {
            resistance *= BEACHED_DRAG;
        }

        // ---- propeller -----------------------------------------------------------------------------
        double throttle = clamp(input.throttle, -1, 1);
        double thrust = 0;
        if (!onLand) {
            thrust = throttle >= 0 ? throttle * THRUST_MAX_N * clamp(1.0 - absSpeed / 40.0, 0.1, 1.0)
                    : throttle * REVERSE_THRUST_N;
            if (beached && throttle != 0) {
                thrust *= 0.25;                             // half the propeller is out of the water
            }
        }
        double brake = clamp(input.brake, 0, 1) * (onLand ? weight * 0.5 : 1200.0);
        double alongAccel = (thrust - sign(speed) * (resistance + brake)) / MASS_KG;
        state.accelMS2 = alongAccel;
        state.speedMS = speed + alongAccel * dt;
        if (Math.abs(state.speedMS) < 0.12 && Math.abs(throttle) < 0.05) {
            state.speedMS = 0;
        }
        if (onLand) {
            state.speedMS = clamp(state.speedMS, -1.5, 1.5);
        }

        // ---- rudder --------------------------------------------------------------------------------
        double rudderAuthority = clamp(absSpeed / 6.0, 0.15, 1.0);
        double yawRate = clamp(input.steer, -1, 1) * RUDDER_YAW_MAX_DEG_S * rudderAuthority
                * (state.floating ? 1.0 : 0.35);
        state.headingDegrees = VehicleState.wrap(state.headingDegrees + yawRate * dt * sign(speed == 0 ? 1 : speed));

        // ---- where that leaves the boat --------------------------------------------------------------
        double heading = Math.toRadians(state.headingDegrees);
        double travel = 0.5 * (speed + state.speedMS) * dt;
        state.eastM += Math.sin(heading) * travel;
        state.northM += Math.cos(heading) * travel;
        state.odometerM += Math.abs(travel);

        // ---- attitude and flags ----------------------------------------------------------------------
        // A planing hull runs bow-up, then levels off as it climbs onto the plane.
        double targetTrim = clamp(2.0 + 9.0 * clampedRatio(absSpeed, 12.0) - 6.0 * clampedRatio(absSpeed, 18.0),
                -3.0, 12.0);
        state.pitchDegrees = approach(state.pitchDegrees, targetTrim, 20 * dt);
        state.rollDegrees = approach(state.rollDegrees,
                clamp(-yawRate * absSpeed * 0.02, -18, 18), 30 * dt);
        state.grounded = beached || onLand;
        state.wading = beached;
        state.slip = onLand ? 1.0 : (beached ? 0.7 : 0);
        state.sinkage = beached ? 0.6 : 0;
        state.stalled = false;
        state.rotorClimb = false;
    }

    private static double clampedRatio(double value, double scale) {
        return clamp(value / scale, 0, 1.5);
    }

    @Override
    public String instrument(VehicleState state) {
        double knots = state.speedMS * 1.94384;
        String note;
        if (state.slip >= 1.0) {
            note = "  AGROUND";
        } else if (state.grounded) {
            note = "  TOUCHING BOTTOM";
        } else {
            note = state.floating ? "  AFLOAT" : "  AIRBORNE";
        }
        return String.format(Locale.ROOT, "%s  %.1f kn%s", label(), knots, note);
    }
}
