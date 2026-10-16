package ai.arena.qatar.vehicle;

/**
 * A walking person: the body the player starts in.
 *
 * <p>Numbers are for a loaded adult: 82 kg, 1.5 m/s walking and 6.6 m/s running (the rig's own
 * constants), 7 m/s^2 of acceleration from a standstill, 12 m/s^2 of braking on a good surface, and a
 * 0.42 m step. A step higher than that is a wall, and the body refuses it rather than climbing it,
 * which is what makes kerbs feel like kerbs.
 *
 * <p>Deep water is swum. It used to be refused -- the body stopped dead at wading depth and the only way
 * out was the rescue that walks a stuck body clear of water it cannot cross -- which made the coast a
 * wall: a player who waded out to their chest on the west side of Doha could not reach the island they
 * could see, could not cross the creek at Al Khor, and was teleported back to the sand with a message
 * about a step after two seconds of pressing the stick. A person swims. The model here is deliberately
 * the smallest one that is honest: the body floats with its shoulders at the water's surface and its feet
 * trailing under it, it moves at a swimmer's speed and not a runner's, it can climb out onto a shore
 * within a step of the water it is floating in, and it is stopped by a quay wall above the surface like
 * anybody else. No stroke, no stamina and no current: three more things to get wrong and none of them
 * visible from the seat of a boat.
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
    /** Water deeper than this is not waded. Above it the body swims. */
    public static final double WADE_DEPTH_M = 1.2;
    /**
     * The speed a person swims at, in m/s.
     *
     * <p>A metre a second is a steady breaststroke and about two thirds of a walking pace: the point of
     * the number is that crossing deep water is possible and slow, so that the sea is a way to get
     * somewhere rather than a wall, and so that a swimmer is not a faster way to travel than a walker.
     */
    public static final double SWIM_SPEED_MS = 1.1;
    /**
     * How far a swimming person's feet trail below the water's surface, in metres.
     *
     * <p>The body's recorded height is its feet, the same as everywhere else in this engine, and the camera
     * sits an eye height above it. A swimmer who is 1.5 m "tall" under the surface therefore holds their
     * head about ten centimetres above it, which is what swimming looks like from the water.
     */
    public static final double SWIM_FEET_DEPTH_M = 1.5;
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
        // Anything deeper than a person can wade is swum rather than refused. This is the flag the boat
        // uses for "resting on water", which is exactly what a swimmer is doing.
        boolean swimming = depth > WADE_DEPTH_M;
        state.floating = swimming;

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

        // Sand and shingle cost speed; the material registry already knows by how much. Water is not a
        // material the body walks on: swimming has its own speed, and the bottom under a swimmer is
        // several metres down and has no say in it.
        double surfaceFactor = swimming ? 1.0
                : state.wading ? 0.45 : 1.0 - 0.55 * surface.materialAt(x, z).sinkage;
        double nominal = swimming ? SWIM_SPEED_MS
                : (input.boost ? RUN_SPEED_MS : WALK_SPEED_MS) * surfaceFactor;
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
        if ((state.grounded || swimming) && (moveE != 0 || moveN != 0)) {
            double aheadGround = surface.groundHeightM(x + moveE, z + moveN);
            // The step the body may take is measured from whatever it is travelling on: the ground under
            // its feet when it is walking or wading, and the water's own surface when it is swimming. So a
            // swimmer can climb out onto a beach -- the sand is within a step of the surface they are
            // floating in -- and cannot climb a quay wall, which is the same rule a walker has for a kerb.
            // The old rule blocked on depth alone, which is what made the sea a wall.
            double stepReference = swimming ? surface.waterSurfaceM(x, z) : ground;
            boolean tooTall = aheadGround - stepReference > STEP_UP_M;
            boolean tooSolid = !insideSomething
                    && surface.solidAt(x + moveE, z + moveN, Math.max(aheadGround, state.heightM));
            if (tooTall || tooSolid) {
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

        // ---- vertical: swim, or stand on the terrain, or fall off it ----------------------------
        double groundHere = surface.groundHeightM(state.eastM, state.northM);
        double depthHere = surface.waterDepthM(state.eastM, state.northM);
        if (depthHere > WADE_DEPTH_M) {
            // Floating: the body neither stands on the seabed nor falls through the water, it rides the
            // surface, and the jump button does nothing because a person in deep water has nothing to
            // push off. Walking out of it is the same code as walking into it: the shallows end and the
            // body is standing again on the next step.
            state.grounded = false;
            state.verticalMS = 0;
            state.heightM = surface.waterSurfaceM(state.eastM, state.northM) - SWIM_FEET_DEPTH_M;
        }
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
        if (!state.grounded && depthHere <= WADE_DEPTH_M) {
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
        state.slip = swimming ? 0.25 : state.wading ? 0.3 : 0.0;
        state.sinkage = surface.materialAt(x, z).sinkage * 0.5;
        // The real acceleration, not a zero: it is what the walker's own lean and the panel's readout
        // are computed from, and a body that always reports 0 m/s^2 is a body that leans never and
        // reports nothing. Measured from the speed the step actually produced, so it includes the
        // blocking below rather than contradicting it.
        state.accelMS2 = dt > 1e-6 ? (state.speedMS - speedBefore) / dt : 0.0;
        state.stalled = false;
        state.rotorClimb = false;
    }

    @Override
    public String instrument(VehicleState state) {
        // SWIMMING is its own word rather than a footnote on WADING: it is the difference between a body
        // that is standing in water and one that is in the middle of the bay, and on a phone the status
        // line is the only debugger there is.
        String mode = state.floating ? "SWIMMING" : state.grounded ? "ON FOOT" : "AIRBORNE";
        return String.format(java.util.Locale.ROOT, "%s  %.1f m/s  %s%s%s", label(), state.speedMS,
                mode, state.wading ? "  WADING" : "", state.blocked ? "  BLOCKED" : "");
    }
}
