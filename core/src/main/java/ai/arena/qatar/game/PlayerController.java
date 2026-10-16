package ai.arena.qatar.game;

import ai.arena.qatar.vehicle.AircraftBody;
import ai.arena.qatar.vehicle.BoatBody;
import ai.arena.qatar.vehicle.CarBody;
import ai.arena.qatar.vehicle.HelicopterBody;
import ai.arena.qatar.vehicle.HumanBody;
import ai.arena.qatar.vehicle.Surface;
import ai.arena.qatar.vehicle.VehicleBody;
import ai.arena.qatar.vehicle.VehicleInput;
import ai.arena.qatar.vehicle.VehicleState;

import java.util.Locale;

/**
 * Turns input into movement for whichever thing the player is being: a person, a car, an aeroplane, a
 * helicopter or a boat.
 *
 * <p>Each locomotion type owns a real {@link VehicleBody} with its own physics, plus its own state, so
 * switching vehicles does not teleport the previous one's momentum into the next one's flight model.
 * The controller's job is the seam: it maps the HUD's axes onto the body's axes, hands the body the
 * world through a {@link Surface}, and places the camera where that body's operator would be sitting.
 *
 * <p>Looking means different things in different vehicles, and that is deliberate. A person walks where
 * they look. A car has a chase camera the player can swing around while the car keeps its own heading.
 * An aeroplane and a helicopter have a self-centring stick: the stick commands an attitude, and letting
 * go of it returns the machine to trim, which is what every one of those machines actually does.
 */
public final class PlayerController {

    public enum Loco {
        HUMAN("HUMAN", "إنسان", 1.0, false, 0.0, 1.62),
        CAR("CAR", "سيارة", 9.0, false, 0.0, 1.35),
        PLANE("PLANE", "طائرة", 110.0, true, 120.0, 1.20),
        HELICOPTER("HELI", "مروحية", 18.0, true, 60.0, 1.55),
        BOAT("BOAT", "قارب", 6.0, false, 0.0, 1.85);

        public final String label;
        public final String labelAr;
        /** Kept for the HUD and the demo mode; the bodies define the real speeds. */
        public final double speedScale;
        /** True when it flies, and therefore uses the rig's flying mode. */
        public final boolean flying;
        /** Cruise altitude in metres for flying machines. */
        public final double cruiseAltitudeM;
        /** How high the camera sits above the vehicle's own reference point. */
        public final double cameraHeightM;

        Loco(String label, String labelAr, double speedScale, boolean flying, double cruiseAltitudeM,
             double cameraHeightM) {
            this.label = label;
            this.labelAr = labelAr;
            this.speedScale = speedScale;
            this.flying = flying;
            this.cruiseAltitudeM = cruiseAltitudeM;
            this.cameraHeightM = cameraHeightM;
        }
    }

    private final CameraRig rig;
    private final VehicleBody[] bodies;
    /** Held as its own type: the hover trim needs the machine's aerodynamics, not a written number. */
    private final HelicopterBody helicopter;
    private final VehicleState[] states;
    private final VehicleInput input = new VehicleInput();
    private final boolean[] placed = new boolean[Loco.values().length];

    private Surface surface = Surface.land(0, ai.arena.qatar.physics.SurfaceMaterial.ID_COMPACT_SAND);
    private Loco loco = Loco.HUMAN;

    /** Movement input, -1..1, already resolved in screen space. */
    public double forward;
    public double strafe;
    public boolean sprinting;
    /** Vertical input from the UP/DOWN buttons: +1 climbs, -1 descends. */
    public double climb;

    /**
     * How long a requested jump stays requested, in seconds: about three fixed steps, so a press on a
     * frame that happens to run no physics step is not silently dropped.
     */
    private static final double JUMP_BUFFER_SECONDS = 0.05;
    private double jumpBufferSeconds;
    /** Wheel brake / astern, 0..1, from the BRAKE button. */
    public boolean braking;

    /**
     * Third person: the camera sits behind the body on a boom and looks at it, which is the only way to
     * see the shape the player is driving. Off, the camera is the driver's own eye.
     */
    public boolean chase = true;
    /**
     * How far the body's moving parts have turned, in degrees, wrapped into one revolution, since the
     * game started. Purely visual: nothing in the simulation reads it, so a wheel cannot make the car
     * go faster by turning.
     */
    public double spinDegrees;
    /** Camera orbit for the vehicles: swings with a look drag and recentres when driving. */
    private double cameraYawOffset;
    private double cameraPitchOffset;
    /** Self-centring stick for the aircraft: commands attitude, springs back to trim. */
    private double stickPitch;
    private double stickRoll;
    /**
     * True while a finger is holding the stick deflected. The stick is a position, not a nudge: a player
     * who holds their thumb a centimetre forward is holding a centimetre of cyclic, and the machine must
     * keep flying that attitude until they let go. It used to be applied as a drag *delta* with a spring
     * pulling it back to trim at 1.6 per second, so a held thumb was an impulse: the disc tipped for half
     * a second and levelled itself while the finger never moved. From the pilot's seat that reads as a
     * helicopter that will not hold a course -- the tenth install's "it flies but not perfectly".
     */
    private boolean stickHeld;
    private double altitudeTarget;
    private boolean altitudeHold;

    /**
     * The altitude hold is a spring-damper, in units a pilot would recognise: it asks for a vertical
     * acceleration proportional to how far the machine is from the commanded height, damped by the rate
     * it is closing on it, and turns that into collective through the machine's own hover trim.
     *
     * <p>The gains are a four-second period at critical damping: {@code kp = (2*pi/T)^2 = 2.5},
     * {@code kd = 2*(2*pi/T) = 3.2}. The ninth install released the hold the moment the machine came
     * within a metre of the commanded height, with whatever climb rate it had built -- so a take-off to
     * 25 m coasted to 45 m and stayed there, which is what "it flies but not perfectly" was. The hold
     * now stays engaged until the pilot takes the lever, and the rate term is what stops the coast.
     */
    private static final double ALTITUDE_KP = 2.5;
    private static final double ALTITUDE_KD = 3.2;
    /** The assist asks for at most this much of gravity: a helicopter, not a lift-off. */
    private static final double ALTITUDE_CLIMB_LIMIT_G = 0.6;
    private static final double ALTITUDE_DESCENT_LIMIT_G = 0.5;

    public PlayerController(CameraRig rig) {
        this.rig = rig;
        this.bodies = new VehicleBody[]{new HumanBody(), new CarBody(), new AircraftBody(),
                new HelicopterBody(), new BoatBody()};
        this.helicopter = (HelicopterBody) bodies[Loco.HELICOPTER.ordinal()];
        this.states = new VehicleState[Loco.values().length];
        for (int i = 0; i < states.length; i++) {
            states[i] = new VehicleState();
        }
        placeAll(rig.east, rig.north, rig.yawDegrees);
        applyLoco();
    }

    public Loco loco() {
        return loco;
    }

    public CameraRig rig() {
        return rig;
    }

    /** The body currently being simulated. */
    public VehicleBody body() {
        return bodies[loco.ordinal()];
    }

    /** The state of the body currently being simulated. */
    public VehicleState state() {
        return states[loco.ordinal()];
    }

    /** The state of any locomotion type, so the HUD can show where the others were left. */
    public VehicleState stateOf(Loco which) {
        return states[which.ordinal()];
    }

    /**
     * The world the bodies move through. Set once the collision terrain is available -- and every body
     * is then re-seated on it.
     *
     * <p>The controller is constructed with a flat fallback surface (ground 0), because at that moment
     * the country has not been handed to it yet. Every body was therefore spawned with its feet at sea
     * level while the real ground could be twelve metres above that, and a walker in that state is
     * refused every step by its own step-up rule ("the ground ahead is twelve metres above my feet"):
     * the person does not move, and the panel says only that the speed is zero. That is half of the
     * ninth install's "the human and the car cannot move" -- the other half was a control nobody could
     * see. Re-seating is also what a spawn, a teleport and a vehicle change all end up doing.
     */
    public void setSurface(Surface surface) {
        if (surface == null) {
            return;
        }
        boolean reSeat = surface != this.surface;
        this.surface = surface;
        if (reSeat) {
            for (int i = 0; i < states.length; i++) {
                bodies[i].spawn(states[i], states[i].eastM, states[i].northM, surface,
                        states[i].headingDegrees);
            }
            syncRig(state());
        }
    }

    public Surface surface() {
        return surface;
    }

    public void setLoco(Loco next) {
        if (next == loco) {
            return;
        }
        loco = next;
        applyLoco();
    }

    private void applyLoco() {
        rig.mode = loco.flying ? CameraRig.Mode.FLY : CameraRig.Mode.WALK;
        rig.speedScale = loco.speedScale;
        cameraYawOffset = 0;
        cameraPitchOffset = loco == Loco.HUMAN ? 0 : -6.0;
        stickPitch = 0;
        stickRoll = 0;
        altitudeHold = false;
        altitudeTarget = loco.flying ? rig.height : 0.0;
        VehicleState state = state();
        if (!placed[loco.ordinal()]) {
            body().spawn(state, rig.east, rig.north, surface, rig.yawDegrees);
            placed[loco.ordinal()] = true;
        } else {
            // The machine the player is moving into was already parked, and the one they are leaving is
            // standing where it was: re-park the stable around the machine they are now in, so what they
            // just got out of is behind them rather than directly under their nose. Without this, the
            // first thing a player does after switching from the car to the aeroplane is taxi into their
            // own car.
            placeAll(state.eastM, state.northM, state.headingDegrees);
        }
        syncRig(state);
    }

    /**
     * Places the player at a position and parks the rest of the stable around them, e.g. after a
     * teleport or at start-up.
     *
     * <p>The player's own body goes exactly where it was asked to go. The other four are laid out in a
     * ring far enough away that no two footprints overlap -- see {@link VehicleContact#parkingSpots} --
     * because five vehicles on one coordinate is a collision the first fixed step has to resolve, and
     * resolving it means shoving the player sideways out of their own parked aeroplane on arrival.
     */
    public void placeAll(double east, double north, double heading) {
        double[][] spots = VehicleContact.parkingSpots(east, north, heading, loco);
        for (int i = 0; i < states.length; i++) {
            bodies[i].spawn(states[i], spots[i][0], spots[i][1], surface, heading);
            placed[i] = true;
        }
        syncRig(state());
    }

    /** Places the player, and every vehicle with them: a teleport moves the whole stable. */
    public void placePlayer(double east, double north) {
        placeAll(east, north, rig.yawDegrees);
    }

    /**
     * Applies a look drag, in degrees. What that means depends on what the player is being: see the
     * class comment -- walking turns the body, driving swings the camera, flying moves the stick.
     */
    public void look(double yawDeltaDegrees, double pitchDeltaDegrees) {
        switch (loco) {
            case HUMAN:
                state().headingDegrees = VehicleState.wrap(state().headingDegrees + yawDeltaDegrees);
                rig.pitchDegrees = clamp(rig.pitchDegrees + pitchDeltaDegrees, -80, 80);
                break;
            case CAR:
            case BOAT:
                cameraYawOffset = clamp(cameraYawOffset + yawDeltaDegrees, -135, 135);
                cameraPitchOffset = clamp(cameraPitchOffset + pitchDeltaDegrees, -35, 25);
                break;
            default:
                // The stick is spring-centred: a drag displaces it, and releasing lets it return.
                stickPitch = clamp(stickPitch + pitchDeltaDegrees / 35.0, -1, 1);
                stickRoll = clamp(stickRoll + yawDeltaDegrees / 45.0, -1, 1);
                break;
        }
    }

    /**
     * Holds the flying stick at a deflection, in -1..1: {@code pitch} forward, {@code roll} to the right.
     *
     * <p>Called while a finger is dragging on the right-hand side of the screen, which is where the stick
     * is. The controllers of the ground vehicles use {@link #look} instead, because looking around is a
     * rate -- a held finger means "keep turning" -- and flying is a position -- a held finger means "fly
     * this attitude". Those are genuinely different gestures and they are on the same part of the screen.
     */
    public void setStick(double pitch, double roll) {
        stickPitch = clamp(pitch, -1, 1);
        stickRoll = clamp(roll, -1, 1);
        stickHeld = true;
    }

    /** Lets go of the stick: it springs back to trim and the machine flies itself level. */
    public void releaseStick() {
        stickHeld = false;
    }

    /** The stick's current deflection, for the HUD to draw: pitch (forward) then roll (right). */
    public double stickPitch() {
        return stickPitch;
    }

    public double stickRoll() {
        return stickRoll;
    }

    /**
     * Asks a flying machine to get airborne, to a height above where it is now.
     *
     * <p>Selecting a helicopter is the ask: on the skids with the collective at idle it does nothing at
     * all, which is correct and reads as a broken machine. This is the difference from
     * {@link #adjustAltitude(double)}, which nudges a hold the pilot has already established.
     */
    public void takeOff(double aboveGroundM) {
        if (!loco.flying) {
            return;
        }
        altitudeHold = true;
        altitudeTarget = state().heightM + Math.max(2.0, aboveGroundM);
    }

    /** Raises or lowers the target altitude for the flying machines (called by the UP/DOWN buttons). */
    public void adjustAltitude(double metres) {
        if (!loco.flying) {
            return;
        }
        altitudeHold = true;
        altitudeTarget = Math.max(2.0, altitudeTarget + metres);
    }

    /**
     * Asks for a jump: the person leaves the ground on the next fixed step, once.
     *
     * <p>Deliberately not a held flag. A jump held down repeats the moment the feet touch again, which
     * reads as the player bouncing across Qatar, and the body has no way to tell a new press from a
     * finger that never moved.
     */
    public void requestJump() {
        jumpBufferSeconds = JUMP_BUFFER_SECONDS;
    }

    /** True when a jump is queued or in progress: the HUD lights the button with it. */
    public boolean jumpPending() {
        return jumpBufferSeconds > 0.0;
    }

    public void update(double dt) {
        VehicleState state = state();
        VehicleBody body = body();

        // ---- the axes, per vehicle ---------------------------------------------------------------
        input.clear();
        switch (loco) {
            case HUMAN:
                input.throttle = forward;
                input.strafe = strafe;
                input.boost = sprinting;
                // A jump is asked for once, by the JUMP button, and stays requested for a moment so a
                // fixed step cannot miss it. It used to be `climb > 0.5` -- the UP button's held value --
                // which meant a walker could not jump at all (UP is disabled while walking, because it is
                // the flying machines' altitude control) and that the same button bunny-hopped when it was
                // not. See `requestJump` and `HumanWalkTest.aJumpOnlyHappensWhenAsked`.
                input.jump = jumpBufferSeconds > 0.0;
                state.headingDegrees = VehicleState.wrap(state.headingDegrees);
                break;
            case CAR:
                input.throttle = forward;
                // The wheel, and the sign of it: the movement stick's horizontal axis is the steering
                // wheel, and pushing it right turns the car right, as it does in the boat and on foot.
                input.steer = strafe;
                // Pulling the stick back is the brake, and then reverse: the throttle goes negative with
                // it, so the car brakes first and backs up if the player keeps pulling.
                input.brake = braking || forward < -0.05 ? Math.max(braking ? 1 : 0, -forward) : 0;
                break;
            case BOAT:
                input.throttle = forward;
                input.steer = strafe;           // stick right turns the bow to starboard
                input.brake = braking ? 1 : 0;
                break;
            case PLANE:
                input.throttle = sprinting ? 1.0 : clamp(forward, 0, 1);
                // The stick: up is nose up, right is right wing down, and the horizontal axis is the
                // rudder on the ground and the pedals in the air. It used to negate the rudder, so
                // pulling the stick right turned the aeroplane left.
                input.pitch = stickPitch;
                input.roll = stickRoll;
                input.yaw = strafe;
                input.brake = braking ? 1 : 0;
                break;
            case HELICOPTER:
                input.collective = collective();
                // The cyclic, and the sign of it matters: dragging the stick forward tips the disc
                // forward, and a tilted disc pulls the machine the way it is tipped. The ninth install
                // negated this, so the machine flew backwards -- visibly, at 29 km/h, and refused to go
                // the way the pilot was pushing. The pedals are the stick's own horizontal axis, right
                // for right, as they are in the car and the boat.
                input.pitch = stickPitch;
                input.roll = stickRoll;
                input.yaw = strafe;
                break;
            default:
                break;
        }
        input.clamp();
        body.step(state, input, surface, dt);
        if (jumpBufferSeconds > 0.0) {
            jumpBufferSeconds = Math.max(0.0, jumpBufferSeconds - dt);
        }

        // A pilot on the stick is flying the machine: the altitude hold lets go rather than fighting
        // the lever they are holding.
        if (loco.flying && (Math.abs(forward) > 0.15 || Math.abs(climb) > 0.05)) {
            altitudeHold = false;
        }

        // ---- the stick springs back to trim. While a finger holds it, it is held. ------------------
        if (!stickHeld) {
            double decay = 1.6 * dt;
            stickPitch = approach(stickPitch, 0, decay);
            stickRoll = approach(stickRoll, 0, decay);
        }
        if (loco == Loco.CAR || loco == Loco.BOAT) {
            // A chase camera drops back behind the vehicle once the player stops looking around.
            if (Math.abs(state.speedMS) > 1.0) {
                cameraYawOffset = approach(cameraYawOffset, 0, 25.0 * dt);
            }
        }
        spinDegrees = VehicleState.wrap(spinDegrees + spinRateDegPerSec(state) * dt);
        resolveVehicleContacts(state);
        syncRig(state);
    }

    /**
     * Pushes the body the player is driving out of the bodies parked around them.
     *
     * <p>Only the player's own body moves: a parked aeroplane is not going to make way for a car, and
     * simulating the parked ones would be simulating four machines nobody is in. The contact is a push
     * out to touching plus half the closing speed, and the state records that the player just hit
     * something -- which is what lets the panel say "blocked" instead of "the car stopped for no
     * reason", and what the ninth install's report was really about.
     */
    private void resolveVehicleContacts(VehicleState state) {
        double radius = VehicleContact.radiusM(loco);
        for (Loco other : Loco.values()) {
            if (other == loco || !placed[other.ordinal()]) {
                continue;
            }
            VehicleState parked = states[other.ordinal()];
            VehicleContact.Resolved contact = VehicleContact.separate(state.eastM, state.northM,
                    state.heightM, radius, parked.eastM, parked.northM, parked.heightM,
                    VehicleContact.radiusM(other));
            if (contact.pushedM <= 0.0) {
                continue;
            }
            state.eastM = contact.eastM;
            state.northM = contact.northM;
            // Touching a parked machine takes the speed out of whatever touched it. Half, not all: a
            // walker bumps along a car's flank rather than being stopped dead by the paint.
            state.speedMS *= 0.5;
            state.blocked = true;
            state.slip = Math.max(state.slip, Math.min(1.0, contact.pushedM));
        }
    }

    /**
     * How fast the wheels, the propeller or the rotors are turning, in degrees per second. Given the
     * machine, these are the real numbers: a car wheel turns at v/r, an aeroplane engine idles at 700 rpm
     * and turns 2,400 at full power, a rotor head is governed near 330 rpm whatever the collective does.
     */
    public double spinRateDegPerSec(VehicleState state) {
        switch (loco) {
            case HUMAN:
                // A stride is a swing cycle: one step is half of it, so the phase advances at two steps
                // per cycle. At a walking pace of 1.5 m/s and a 0.75 m stride that is 2 steps a second
                // and one full cycle a second -- a real walking cadence, and it stops dead when the
                // person does, which is what "the feet are not moving" looks like.
                return 180.0 * Math.abs(state.speedMS) / 0.75;
            case CAR:
                // Rolling without slipping: one radius of travel is one radian of rotation.
                return Math.toDegrees(state.speedMS / CarBody.WHEEL_RADIUS_M);
            case PLANE: {
                double throttle = sprinting ? 1.0 : clamp(forward, 0, 1);
                return 6.0 * (700.0 + 1_700.0 * throttle);
            }
            case HELICOPTER:
                return 6.0 * (330.0 + 40.0 * clamp(input.collective, 0, 1));
            default:
                // Nothing on a person or a boat that a camera can see: a boat's propeller is underwater.
                return 0.0;
        }
    }

    /**
     * Collective from the vertical buttons and the stick: up is more rotor thrust, and the altitude
     * button bias is applied on top so a helicopter can be asked to hold a height.
     *
     * <p>On the skids with nothing asked for, the collective is <b>zero</b>: the rotor turns and the
     * machine stays down. It used to be a standing 0.5 -- hover trim whether or not anybody was flying
     * -- so the helicopter took itself off the ground the moment it was selected and hovered there,
     * which is exactly what "it flies but not perfectly" looks like from the pilot's seat: a machine
     * that leaves on its own and cannot be commanded once it is up.
     */
    private double collective() {
        VehicleState s = state();
        double lever = 0.45 * clamp(forward, -1, 1) + climb * 0.25;      // the player's hand on the lever
        if (s.grounded && !altitudeHold) {
            // On the skids with nothing asked for: the rotor turns and the machine stays down. This is
            // the difference between a helicopter and a lift: it used to hold 0.5 of collective whenever
            // it was airborne, so selecting it was a take-off nobody asked for.
            return clamp(lever, 0, 1);
        }
        if (!altitudeHold) {
            // Airborne, hands off: hover trim, which is the machine's own arithmetic -- level flight at
            // any altitude, at any speed, with the disc tipped over.
            return clamp(helicopter.hoverCollective(s) + lever, 0, 1);
        }
        // A hold is a spring-damper on the altitude, and the collective that *produces* the acceleration
        // it asks for is the machine's: see HelicopterBody.collectiveForAccel. The hold stays engaged
        // until the pilot takes the lever, so the machine sits at the height it was told to sit at
        // instead of coasting past it.
        double error = altitudeTarget - s.heightM;
        double accel = clamp(ALTITUDE_KP * error - ALTITUDE_KD * s.verticalMS,
                -ALTITUDE_DESCENT_LIMIT_G * VehicleBody.G, ALTITUDE_CLIMB_LIMIT_G * VehicleBody.G);
        return clamp(helicopter.collectiveForAccel(s, accel) + lever, 0, 1);
    }

    /** Puts the camera where this vehicle's driver, pilot or walker would be looking from. */
    private void syncRig(VehicleState state) {
        double yaw;
        double pitch;
        switch (loco) {
            case HUMAN:
                yaw = state.headingDegrees;
                pitch = rig.pitchDegrees;
                break;
            case PLANE:
            case HELICOPTER:
                // The camera looks where the aircraft points, with the stick's own deflection added.
                yaw = state.headingDegrees;
                pitch = clamp(state.pitchDegrees * 0.6
                        + Math.toDegrees(Math.atan2(state.verticalMS, Math.max(6.0, state.speedMS))) * 0.4,
                        -85, 85);
                break;
            default:
                yaw = VehicleState.wrap(state.headingDegrees + cameraYawOffset);
                pitch = clamp(cameraPitchOffset
                        - Math.toDegrees(Math.atan2(state.verticalMS, Math.max(5.0, state.speedMS))),
                        -60, 40);
                break;
        }
        if (chase) {
            // Behind and above, looking at the body. The boom is longer than the machine so nothing of
            // the vehicle the player is inside ends up inside the near plane.
            double boom = boomM();
            rig.east = state.eastM - Math.sin(Math.toRadians(yaw)) * boom;
            rig.north = state.northM - Math.cos(Math.toRadians(yaw)) * boom;
            rig.height = state.heightM + loco.cameraHeightM + boom * 0.35;
            rig.yawDegrees = yaw;
            // Aim at the body, and let the look drag tilt the aim a little either way.
            rig.pitchDegrees = clamp(-Math.toDegrees(Math.atan2(boom * 0.35, boom)) * 0.6 + pitch * 0.5,
                    -60, 40);
        } else {
            rig.east = state.eastM;
            rig.north = state.northM;
            rig.height = state.heightM + loco.cameraHeightM;
            rig.yawDegrees = yaw;
            rig.pitchDegrees = pitch;
        }
        rig.sprinting = sprinting && loco != Loco.BOAT;
    }

    /** How far behind a body the chase camera sits, in metres: clear of its own length. */
    public double boomM() {
        switch (loco) {
            case CAR:
                return 6.5;
            case PLANE:
                return 12.0;
            case HELICOPTER:
                return 9.0;
            case BOAT:
                return 8.0;
            default:
                return 3.2;
        }
    }

    /** The body the player currently is, as the renderer names it. */
    public ai.arena.qatar.render.BodyMesher.Body meshKind() {
        switch (loco) {
            case CAR:
                return ai.arena.qatar.render.BodyMesher.Body.CAR;
            case PLANE:
                return ai.arena.qatar.render.BodyMesher.Body.PLANE;
            case HELICOPTER:
                return ai.arena.qatar.render.BodyMesher.Body.HELICOPTER;
            case BOAT:
                return ai.arena.qatar.render.BodyMesher.Body.BOAT;
            default:
                return ai.arena.qatar.render.BodyMesher.Body.HUMAN;
        }
    }

    /** Compact HUD line: what the player is, and what it is doing. */
    public String describe() {
        String line = body().instrument(state());
        // The third thing a player needs to know after speed and height: that the machine is being
        // refused. A body that says "0 km/h" with nothing else is what "the car will not move" looked
        // like from the outside, and it cost two installs to find out why.
        return state().blocked ? line + "  BLOCKED" : line;
    }

    /**
     * The status line in Arabic, in logical order: the HUD shapes and reverses it for a left-to-right
     * renderer, so this stays written the way an Arabic reader reads it.
     *
     * <p>It is a summary rather than a translation of {@link #describe()}: the numbers are the same ones,
     * in the same order, with Arabic labels in front of them, because a phrase cannot be translated word
     * for word between languages that put the verb in different places and still sound like a person
     * wrote it.
     */
    public String describeArabic() {
        VehicleState state = state();
        String where = state.floating ? "في الماء" : (state.grounded ? "على الأرض" : "في الجو");
        return String.format(Locale.ROOT,
                "%s | %s | السرعة %.0f م/ث | الارتفاع %.0f م | الاتجاه %.0f°",
                loco.labelAr, where, Math.abs(state.speedMS), state.heightM, state.headingDegrees);
    }

    /** Longer line for the debug overlay, including where the machine actually is. */
    public String describeVerbose() {
        VehicleState state = state();
        return String.format(Locale.ROOT, "%s  %.0f, %.0f local  alt %.1f m  %.1f m/s  %s",
                loco.label, state.eastM, state.northM, state.heightM, state.speedMS,
                state.grounded ? (state.floating ? "AFLOAT" : "ON GROUND") : "AIRBORNE");
    }

    private static double clamp(double v, double low, double high) {
        return v < low ? low : (v > high ? high : v);
    }

    private static double approach(double value, double target, double maxDelta) {
        double delta = target - value;
        if (delta > maxDelta) {
            return value + maxDelta;
        }
        if (delta < -maxDelta) {
            return value - maxDelta;
        }
        return target;
    }
}
