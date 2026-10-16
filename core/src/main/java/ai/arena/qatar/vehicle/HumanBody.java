package ai.arena.qatar.vehicle;

/**
 * A walking person: the body the player starts in.
 *
 * <p>Numbers are for a loaded adult: 82 kg, 1.5 m/s walking and 6.6 m/s running (the rig's own
 * constants), 7 m/s^2 of acceleration from a standstill, 12 m/s^2 of braking on a good surface, and a
 * 0.42 m step. A step higher than that is a wall, and the body refuses it rather than climbing it,
 * which is what makes kerbs feel like kerbs.
 *
 * <p>Deep water is refused rather than swum: the plan lists a body for the human, not a swimming
 * model, and an honest stop at wading depth is better than pretending to swim.
 */
public final class HumanBody extends VehicleBody {

    public static final double MASS_KG = 82.0;
    public static final double WALK_SPEED_MS = 1.5;
    public static final double RUN_SPEED_MS = 6.6;
    public static final double ACCEL_MS2 = 7.0;
    public static final double BRAKE_MS2 = 12.0;
    public static final double AIR_CONTROL = 0.25;
    /** Vertical take-off speed for a standing jump: 0.49 m of clearance at 1 g. */
    public static final double JUMP_SPEED_MS = 3.1;
    /** The tallest step the body will walk up. */
    public static final double STEP_UP_M = 0.42;
    /** Water deeper than this is not waded. */
    public static final double WADE_DEPTH_M = 1.2;
    /** Eye height above the feet, used by the camera rig. */
    public static final double EYE_HEIGHT_M = 1.62;

    @Override
    public String label() {
        return "HUMAN";
    }

    @Override
    public String labelAr() {
        return "إنسان";
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
            state.blocked = true;                       // no collision mesh: stand still, never fall
            return;
        }
        state.blocked = false;

        double ground = surface.groundHeightM(x, z);
        double depth = surface.waterDepthM(x, z);
        state.wading = depth > 0.15 && depth <= WADE_DEPTH_M;

        // ---- where the player wants to go -------------------------------------------------------
        double forward = clamp(input.throttle, -1, 1);
        double side = clamp(input.strafe, -1, 1);
        double magnitude = Math.hypot(forward, side);
        if (magnitude < 0.05) {
            forward = 0;
            side = 0;
            magnitude = 0;
        } else if (magnitude > 1) {
            forward /= magnitude;
            side /= magnitude;
            magnitude = 1;
        }

        // Sand and shingle cost speed; the material registry already knows by how much.
        double surfaceFactor = state.wading ? 0.45 : 1.0 - 0.55 * surface.materialAt(x, z).sinkage;
        double nominal = (input.boost ? RUN_SPEED_MS : WALK_SPEED_MS) * surfaceFactor;
        // Side-stepping is slower than walking forward, and walking backwards slower still.
        double alongHeading = forward >= 0 ? magnitude : magnitude * 0.55;
        double signed = forward < 0 && side == 0 ? -1.0 : 1.0;
        double targetSpeed = nominal * alongHeading * signed;
        double rate = (magnitude > 0.02 ? ACCEL_MS2 : BRAKE_MS2) * (state.grounded ? 1.0 : AIR_CONTROL);
        double speedBefore = state.speedMS;
        state.speedMS = approach(state.speedMS, targetSpeed, rate * dt);

        double heading = Math.toRadians(state.headingDegrees);
        double forwardE = Math.sin(heading);
        double forwardN = Math.cos(heading);
        double sideE = Math.cos(heading);
        double sideN = -Math.sin(heading);
        double dirE = forwardE * forward + sideE * side;
        double dirN = forwardN * forward + sideN * side;
        double dirLength = Math.hypot(dirE, dirN);
        double stepDistance = state.speedMS * dt;
        double moveE = 0;
        double moveN = 0;
        if (dirLength > 1e-6 && magnitude > 0) {
            // The direction already carries the sign of the walk: pulling the stick back puts `forward`
            // negative, which points (dirE, dirN) behind the player. The speed is negative in exactly
            // that same case, so the distance here is its magnitude and no second flip is applied --
            // flipping it again is what made the player *walk forwards while pulling the stick back*.
            // `HumanWalkTest.aBackwardsStickWalksBackwards` holds this: it is a one-character bug that
            // looks like broken physics, and it survived a careful reading because both halves are
            // individually sensible.
            moveE = dirE / dirLength * Math.abs(stepDistance);
            moveN = dirN / dirLength * Math.abs(stepDistance);
        }

        // ---- the ground decides whether that move is allowed ------------------------------------
        // One rule, because a walker takes a step: the ground ahead may be up to STEP_UP_M above the
        // feet and no more. A kerb is a step; a sea wall is a wall; there is no wall-climbing and no
        // sliding down slopes, which is the honest limit of a walker that has no ragdoll.
        // Is the player standing inside something they should not be? A spawn point inside a compound,
        // a teleport into a courtyard, a building's *bounding box* that swallows the road beside it --
        // in all three, every direction is refused for ever by the solid test below and the person never
        // moves. Being inside something is not a wall to respect: it is somewhere to walk out of, so
        // the solid test is skipped while the feet themselves are in a solid.
        boolean insideSomething = surface.solidAt(x, z, Math.max(ground, state.heightM));
        if (state.grounded && (moveE != 0 || moveN != 0)) {
            double aheadGround = surface.groundHeightM(x + moveE, z + moveN);
            double aheadDepth = surface.waterDepthM(x + moveE, z + moveN);
            boolean tooTall = aheadGround - ground > STEP_UP_M;
            boolean tooDeep = aheadDepth > WADE_DEPTH_M;
            boolean tooSolid = !insideSomething
                    && surface.solidAt(x + moveE, z + moveN, Math.max(aheadGround, state.heightM));
            if (tooTall || tooDeep || tooSolid) {
                state.blocked = true;
                state.speedMS = 0;
                moveE = 0;
                moveN = 0;
                stepDistance = 0;
            }
        }

        state.eastM += moveE;
        state.northM += moveN;
        state.odometerM += Math.abs(stepDistance);

        // ---- vertical: stand on the terrain, fall off it, jump off it ---------------------------
        double groundHere = surface.groundHeightM(state.eastM, state.northM);
        if (state.grounded) {
            if (input.jump) {
                state.grounded = false;
                state.verticalMS = JUMP_SPEED_MS;
                state.heightM = Math.max(state.heightM, groundHere);
            } else {
                // Follow the terrain down as well as up, but never drop instantly: a 3 m sea wall is a
                // fall, not a lift ride.
                state.heightM = Math.max(groundHere, state.heightM - 6.0 * dt);
                if (state.heightM > groundHere + 0.05) {
                    state.grounded = false;
                    state.verticalMS = 0;
                }
                state.verticalMS = 0;
            }
        }
        if (!state.grounded) {
            state.verticalMS -= G * dt;
            state.heightM += state.verticalMS * dt;
            if (state.heightM <= groundHere && state.verticalMS <= 0) {
                state.heightM = groundHere;
                state.verticalMS = 0;
                state.grounded = true;
            }
        }

        state.pitchDegrees = state.grounded
                ? approach(state.pitchDegrees, 0, 30 * dt)
                : approach(state.pitchDegrees, clamp(-state.verticalMS * 1.5, -35, 35), 60 * dt);
        state.slip = state.wading ? 0.3 : 0.0;
        state.sinkage = surface.materialAt(x, z).sinkage * 0.5;
        // The real acceleration, not a zero: it is what the walker's own lean and the panel's readout
        // are computed from, and a body that always reports 0 m/s^2 is a body that leans never and
        // reports nothing. Measured from the speed the step actually produced, so it includes the
        // blocking below rather than contradicting it.
        state.accelMS2 = dt > 1e-6 ? (state.speedMS - speedBefore) / dt : 0.0;
        state.stalled = false;
        state.floating = false;
        state.rotorClimb = false;
    }

    @Override
    public String instrument(VehicleState state) {
        String mode = state.grounded ? "ON FOOT" : "AIRBORNE";
        // BLOCKED is on the readout because a walker that will not move has to say why: on a phone the
        // status line is the only debugger there is.
        return String.format(java.util.Locale.ROOT, "%s  %.1f m/s  %s%s%s", label(), state.speedMS,
                mode, state.wading ? "  WADING" : "", state.blocked ? "  BLOCKED" : "");
    }
}
