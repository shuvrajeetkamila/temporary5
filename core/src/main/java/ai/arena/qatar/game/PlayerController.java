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
    /** Wheel brake / astern, 0..1, from the BRAKE button. */
    public boolean braking;

    /** Camera orbit for the vehicles: swings with a look drag and recentres when driving. */
    private double cameraYawOffset;
    private double cameraPitchOffset;
    /** Self-centring stick for the aircraft: commands attitude, springs back to trim. */
    private double stickPitch;
    private double stickRoll;
    private double altitudeTarget;
    private boolean altitudeHold;

    public PlayerController(CameraRig rig) {
        this.rig = rig;
        this.bodies = new VehicleBody[]{new HumanBody(), new CarBody(), new AircraftBody(),
                new HelicopterBody(), new BoatBody()};
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

    /** The world the bodies move through. Set once the collision terrain is available. */
    public void setSurface(Surface surface) {
        if (surface != null) {
            this.surface = surface;
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
        }
        syncRig(state);
    }

    /** Places every body at a position, e.g. after a teleport or at start-up. */
    public void placeAll(double east, double north, double heading) {
        for (int i = 0; i < states.length; i++) {
            bodies[i].spawn(states[i], east, north, surface, heading);
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

    /** Raises or lowers the target altitude for the flying machines (called by the UP/DOWN buttons). */
    public void adjustAltitude(double metres) {
        if (!loco.flying) {
            return;
        }
        altitudeHold = true;
        altitudeTarget = Math.max(2.0, altitudeTarget + metres);
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
                input.jump = climb > 0.5;
                state.headingDegrees = VehicleState.wrap(state.headingDegrees);
                break;
            case CAR:
                input.throttle = forward;
                input.steer = -strafe;
                input.brake = braking || forward < -0.05 ? Math.max(braking ? 1 : 0, -forward) : 0;
                break;
            case BOAT:
                input.throttle = forward;
                input.steer = -strafe;
                input.brake = braking ? 1 : 0;
                break;
            case PLANE:
                input.throttle = sprinting ? 1.0 : clamp(forward, 0, 1);
                input.pitch = stickPitch;
                input.roll = stickRoll;
                input.yaw = -strafe;
                input.brake = braking ? 1 : 0;
                break;
            case HELICOPTER:
                input.collective = collective();
                input.pitch = -stickPitch;                 // stick forward tips the disc forward
                input.roll = stickRoll;
                input.yaw = -strafe;
                break;
            default:
                break;
        }
        input.clamp();
        body.step(state, input, surface, dt);

        // ---- the stick springs back to trim ------------------------------------------------------
        double decay = 1.6 * dt;
        stickPitch = approach(stickPitch, 0, decay);
        stickRoll = approach(stickRoll, 0, decay);
        if (loco == Loco.CAR || loco == Loco.BOAT) {
            // A chase camera drops back behind the vehicle once the player stops looking around.
            if (Math.abs(state.speedMS) > 1.0) {
                cameraYawOffset = approach(cameraYawOffset, 0, 25.0 * dt);
            }
        }
        syncRig(state);
    }

    /**
     * Collective from the vertical buttons and the stick: up is more rotor thrust, and the altitude
     * button bias is applied on top so a helicopter can be asked to hold a height.
     */
    private double collective() {
        double base = 0.5 + 0.45 * clamp(forward, -1, 1);
        if (altitudeHold) {
            double error = altitudeTarget - state().heightM;
            if (Math.abs(error) < 1.0) {
                altitudeHold = false;
                return base;
            }
            base += clamp(error / 120.0, -0.35, 0.35);
        }
        return clamp(base + climb * 0.25, 0, 1);
    }

    /** Puts the camera where this vehicle's driver, pilot or walker would be looking from. */
    private void syncRig(VehicleState state) {
        rig.east = state.eastM;
        rig.north = state.northM;
        rig.height = state.heightM + loco.cameraHeightM;
        switch (loco) {
            case HUMAN:
                rig.yawDegrees = state.headingDegrees;
                break;
            case PLANE:
            case HELICOPTER:
                // The camera looks where the aircraft points, with the stick's own deflection added.
                rig.yawDegrees = state.headingDegrees;
                rig.pitchDegrees = clamp(state.pitchDegrees * 0.6
                        + Math.toDegrees(Math.atan2(state.verticalMS, Math.max(6.0, state.speedMS))) * 0.4,
                        -85, 85);
                break;
            default:
                rig.yawDegrees = VehicleState.wrap(state.headingDegrees + cameraYawOffset);
                rig.pitchDegrees = clamp(cameraPitchOffset
                        - Math.toDegrees(Math.atan2(state.verticalMS, Math.max(5.0, state.speedMS))), -60, 40);
                break;
        }
        rig.sprinting = sprinting && loco != Loco.BOAT;
    }

    /** Compact HUD line: what the player is, and what it is doing. */
    public String describe() {
        return body().instrument(state());
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
