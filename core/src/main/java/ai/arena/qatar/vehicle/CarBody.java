package ai.arena.qatar.vehicle;

import java.util.Locale;

/**
 * A car: 1.62 t, front-wheel drive, 92 kW, on a 2.79 m wheelbase.
 *
 * <p>A bicycle model with a grip budget. The engine can only ever deliver what the tyres can put down
 * (which is why a rear-wheel-drive car on sand spins its wheels and a car on asphalt does not), the
 * steering lock shrinks with speed the way real rack rates do, and lateral acceleration is capped by
 * the surface, so a corner taken too fast pushes wide and the HUD can show the tyres working. Beyond
 * about 0.6 m/s^2 on loose ground the car simply cannot corner, and it says so through
 * {@link VehicleState#slip}.
 *
 * <p>It is explicitly not a multi-body car: it does not roll over, does not jack up under braking, and
 * has no suspension travel beyond a single load transfer number. Those would be worth having, and
 * they are not worth pretending to have.
 */
public final class CarBody extends VehicleBody {

    public static final double MASS_KG = 1620.0;
    public static final double WHEELBASE_M = 2.79;
    public static final double TRACK_M = 1.57;
    public static final double WHEEL_RADIUS_M = 0.33;
    public static final double MAX_POWER_W = 92_000;
    /** Traction limit of the driven axle on the best surface, newtons. */
    public static final double MAX_TRACTION_N = 6_800;
    public static final double BRAKE_FORCE_N = 13_500;
    /** Cd * A: a saloon's 0.31 drag coefficient over 2.8 m^2 of frontal area. */
    public static final double DRAG_AREA_M2 = 0.86;
    public static final double STEER_LOCK_LOW_RAD = 0.60;
    public static final double STEER_LOCK_HIGH_RAD = 0.11;
    /** Lateral grip budget as a fraction of the surface's peak friction. */
    public static final double LATERAL_GRIP_FRACTION = 0.95;
    /** The steepest slope the car will drive up (about 30 degrees). */
    public static final double SLOPE_LIMIT = 0.58;
    /** Length of the car, used for the nose test against walls. */
    public static final double CAR_LENGTH_M = 4.6;
    /** Water deeper than this stops the engine rather than slowing the car. */
    public static final double STALL_DEPTH_M = 0.7;

    @Override
    public String label() {
        return "CAR";
    }

    @Override
    public String labelAr() {
        return "سيارة";
    }

    @Override
    public double massKg() {
        return MASS_KG;
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
        double grip = surface.materialAt(x, z).tireGrip;
        double sinkage = surface.materialAt(x, z).sinkage;
        double rolling = surface.materialAt(x, z).rollingResistance;
        boolean stalled = depth > STALL_DEPTH_M;
        state.wading = depth > 0.10 && !stalled;

        // ---- steering --------------------------------------------------------------------------
        double speed = state.speedMS;
        double absSpeed = Math.abs(speed);
        double lock = STEER_LOCK_LOW_RAD
                + (STEER_LOCK_HIGH_RAD - STEER_LOCK_LOW_RAD) * clamp(absSpeed / 50.0, 0, 1);
        double steerAngle = lock * clamp(input.steer, -1, 1);
        double slipAngle = 0;
        if (absSpeed > 0.3) {
            double yawRate = absSpeed * Math.tan(steerAngle) / WHEELBASE_M;
            // The tyres, not the steering rack, decide how hard the car can turn.
            double gripCap = LATERAL_GRIP_FRACTION * grip * G * Math.max(1.0, 1.0 - 0.3 * sinkage);
            double maxYawRate = gripCap / Math.max(1.0, absSpeed);
            if (Math.abs(yawRate) > maxYawRate) {
                slipAngle = 1.0 - maxYawRate / Math.abs(yawRate);
                yawRate = sign(yawRate) * maxYawRate;
            }
            state.headingDegrees = VehicleState.wrap(state.headingDegrees
                    + Math.toDegrees(yawRate) * dt * sign(speed));
        }

        // ---- longitudinal ----------------------------------------------------------------------
        double thrust = 0;
        double slip = slipAngle;
        if (!stalled) {
            double throttle = clamp(input.throttle, -1, 1);
            double demanded = throttle * Math.min(MAX_TRACTION_N,
                    MAX_POWER_W / Math.max(6.0, absSpeed));
            if (Math.abs(demanded) > 1e-3) {
                // Available traction falls with sinkage: loose sand and sabkha are where a car bogs.
                double available = grip * MASS_KG * G * (1.0 - 0.45 * sinkage);
                if (Math.abs(demanded) > available) {
                    slip = Math.max(slip, 1.0 - available / Math.abs(demanded));
                    demanded = sign(demanded) * available;
                }
                thrust = demanded;
            }
        }

        double drag = 0.5 * airDensity(state.heightM) * DRAG_AREA_M2 * speed * speed;
        double rollingResistance = stalled || !state.grounded ? 0 : rolling * MASS_KG * G;
        double softDrag = surface.materialAt(x, z).surfaceDrag * MASS_KG * G * sinkage * 0.5;
        double wadingDrag = state.wading ? 0.5 * SEA_WATER_DENSITY * 0.5 * speed * speed : 0;
        double brake = clamp(input.brake, 0, 1) * BRAKE_FORCE_N;
        double resistance = drag + rollingResistance + softDrag + wadingDrag + brake;
        // Brakes and drag oppose motion; thrust does not have to fight the sign convention.
        if (Math.abs(speed) > 0.05) {
            resistance *= sign(speed);
        } else if (Math.abs(thrust) < resistance) {
            resistance = thrust;                  // stationary: rolling resistance cannot reverse the car
        }

        double acceleration = (thrust - resistance) / MASS_KG;
        state.accelMS2 = acceleration;
        double previous = speed;
        state.speedMS = speed + acceleration * dt;
        // A stopped car with no drive stays stopped; friction must not push it backwards.
        if (Math.abs(state.speedMS) < 0.15 && Math.abs(input.throttle) < 0.05) {
            state.speedMS = 0;
        }
        if (stalled && Math.abs(state.speedMS) < 0.4) {
            state.speedMS = 0;
        }

        // ---- where that takes the car ----------------------------------------------------------
        double heading = Math.toRadians(state.headingDegrees);
        double forwardE = Math.sin(heading);
        double forwardN = Math.cos(heading);
        double travel = 0.5 * (previous + state.speedMS) * dt;
        double moveE = forwardE * travel;
        double moveN = forwardN * travel;

        if (state.grounded && Math.abs(travel) > 1e-4) {
            double distance = Math.abs(travel);
            double aheadGround = surface.groundHeightM(x + moveE, z + moveN);
            double rise = aheadGround - ground;
            boolean tooSteep = rise / distance > SLOPE_LIMIT || rise > 0.55;
            // The nose of the car, not its middle: a car stops at a wall before driving into it.
            double noseE = state.eastM + forwardE * CAR_LENGTH_M * 0.5;
            double noseN = state.northM + forwardN * CAR_LENGTH_M * 0.5;
            boolean wall = surface.solidAt(noseE, noseN, ground + WHEEL_RADIUS_M + 0.2);
            if (tooSteep || wall) {
                // A wall, a kerb the car cannot take at speed, or a dune face: stop, do not climb it.
                state.blocked = true;
                state.speedMS = Math.min(Math.abs(state.speedMS), 2.0) * sign(previous);
                moveE = 0;
                moveN = 0;
            } else if (rise > 0.05) {
                // Climbing costs speed: gravity along the slope.
                double grade = rise / Math.max(distance, 1e-3);
                state.speedMS -= G * grade * dt;
            }
        }

        state.eastM += moveE;
        state.northM += moveN;
        state.odometerM += Math.abs(travel);

        // ---- vertical: on the wheels, or briefly in the air ------------------------------------
        double rideHeight = surface.groundHeightM(state.eastM, state.northM) + WHEEL_RADIUS_M;
        if (state.grounded) {
            if (state.heightM - rideHeight > 0.35) {
                state.grounded = false;           // the ground fell away: take off
                state.verticalMS = 0;
            } else {
                state.heightM = rideHeight;
                state.verticalMS = 0;
            }
        }
        if (!state.grounded) {
            state.verticalMS -= G * dt;
            state.heightM += state.verticalMS * dt;
            if (state.heightM <= rideHeight && state.verticalMS <= 0) {
                state.heightM = rideHeight;
                state.verticalMS = 0;
                state.grounded = true;
            }
        }

        state.slip = clamp(slip, 0, 1);
        state.sinkage = stalled ? 0 : sinkage * clamp(Math.abs(state.speedMS) / 8.0, 0, 1);
        state.stalled = stalled;
        state.floating = false;
        state.rotorClimb = false;
        state.pitchDegrees = approach(state.pitchDegrees,
                -clamp(state.accelMS2 * 0.6, -8, 8), 30 * dt);
        state.rollDegrees = approach(state.rollDegrees,
                clamp(absSpeed * absSpeed * Math.tan(steerAngle) / WHEELBASE_M / G * 3.0, -12, 12),
                60 * dt);
    }

    @Override
    public String instrument(VehicleState state) {
        double kph = state.speedMS * 3.6;
        String note = state.stalled ? "  ENGINE STALLED" : (state.slip > 0.25 ? "  TYRES SLIPPING" : "");
        return String.format(Locale.ROOT, "%s  %.0f km/h%s", label(), kph, note);
    }
}
