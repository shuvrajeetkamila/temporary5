package ai.arena.qatar.game;

import java.util.Locale;

/**
 * Turns input into movement for whichever thing the player is being: a person, a car, an aeroplane, a
 * helicopter or a boat.
 *
 * <p>Each locomotion type is a small, honest set of numbers -- how fast it goes, whether it needs the
 * ground under it, how high it may fly -- rather than a branch per frame of special cases. Everything
 * still goes through the one tested {@link CameraRig}, so there is a single movement path to trust.
 *
 * <p>The bodies, wheels, wings and hull are step 2 of the plan; this class is the seam they plug into:
 * a vehicle's rigid body will drive the rig's position instead of these kinematics, and the HUD will not
 * have to change at all.
 */
public final class PlayerController {

    public enum Loco {
        HUMAN("HUMAN", "إنسان", 1.0, false, 0.0),
        CAR("CAR", "سيارة", 9.0, false, 0.0),
        PLANE("PLANE", "طائرة", 110.0, true, 120.0),
        HELICOPTER("HELI", "مروحية", 18.0, true, 60.0),
        BOAT("BOAT", "قارب", 6.0, false, 0.0);

        public final String label;
        public final String labelAr;
        /** Multiplier on the rig's base speeds. */
        public final double speedScale;
        /** True when it flies, and therefore uses the rig's flying mode. */
        public final boolean flying;
        /** Cruise altitude in metres for flying machines. */
        public final double cruiseAltitudeM;

        Loco(String label, String labelAr, double speedScale, boolean flying, double cruiseAltitudeM) {
            this.label = label;
            this.labelAr = labelAr;
            this.speedScale = speedScale;
            this.flying = flying;
            this.cruiseAltitudeM = cruiseAltitudeM;
        }
    }

    private final CameraRig rig;
    private Loco loco = Loco.HUMAN;

    /** Movement input, -1..1, already resolved in screen space. */
    public double forward;
    public double strafe;
    public boolean sprinting;
    /** Vertical input from the UP/DOWN buttons: +1 climbs, -1 descends. */
    public double climb;

    private double altitudeTarget;
    private boolean altitudeHold;

    public PlayerController(CameraRig rig) {
        this.rig = rig;
        applyLoco();
    }

    public Loco loco() {
        return loco;
    }

    public CameraRig rig() {
        return rig;
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
        altitudeHold = false;
        altitudeTarget = loco.flying ? rig.height : 0.0;
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
        rig.sprinting = sprinting && loco != Loco.BOAT;

        if (loco.flying && altitudeHold) {
            // Ease towards the requested altitude instead of snapping: a helicopter that jumps is not
            // a helicopter. When the target is reached, the climb input goes quiet.
            double error = altitudeTarget - rig.height;
            double rate = Math.max(-1.0, Math.min(1.0, error / Math.max(1.0, Math.abs(error))));
            rig.climbInput = Math.abs(error) < 0.5 ? 0.0 : rate;
            if (rig.climbInput == 0.0) {
                altitudeHold = false;
            }
        } else if (loco.flying) {
            rig.climbInput = climb;
        } else {
            rig.climbInput = 0.0;
        }

        rig.move(forward, strafe, dt);
    }

    /** Compact HUD line: what the player is, and how fast. */
    public String describe() {
        double speed = rig.speedScale * (rig.sprinting ? CameraRig.SPRINT_SPEED : CameraRig.WALK_SPEED);
        if (loco.flying) {
            speed = CameraRig.FLY_SPEED * rig.speedScale * (rig.sprinting ? CameraRig.FLY_BOOST
                    / CameraRig.FLY_SPEED : 1.0);
        }
        return String.format(Locale.ROOT, "%s  %.0f m/s  alt %.0f m  %s", loco.label, speed,
                rig.height, rig.mode == CameraRig.Mode.FLY ? "FLY" : "WALK");
    }
}
