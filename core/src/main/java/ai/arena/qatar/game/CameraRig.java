package ai.arena.qatar.game;

import ai.arena.qatar.physics.TerrainSampler;

/**
 * The player's viewpoint: where it is, which way it looks, and how it moves -- with no GL types, so the
 * movement contract (walk on the ground, fly over it, never sink through it) is testable.
 *
 * <p>Everything is in the national frame (metres from the manifest grid origin), because that is the
 * frame the world data is in; converting to a rendered local frame is the renderer's business.
 *
 * <p>The eye height, walk speed and slope limits are the same numbers the character controller and the
 * vehicles will use, so the camera previews exactly what the player can do on foot.
 */
public final class CameraRig {

    /** Standing eye height for a 1.75 m person. */
    public static final double EYE_HEIGHT_M = 1.62;
    /** Walking and sprinting speeds in metres per second (a brisk pace, a real sprint). */
    public static final double WALK_SPEED = 1.5;
    public static final double SPRINT_SPEED = 6.5;
    /** Free-camera speeds: survey the country quickly without a vehicle. */
    public static final double FLY_SPEED = 60.0;
    public static final double FLY_BOOST = 400.0;
    /** Steepest ground the camera will walk up, as a normal.y threshold (~35 degrees). */
    public static final double WALK_SLOPE_LIMIT = 0.82;

    public enum Mode {
        /** On foot: follows the terrain, cannot enter water deeper than a wade. */
        WALK,
        /** Free camera: used before a vehicle exists and for surveying the world. */
        FLY
    }

    public double east;
    public double north;
    public double height;
    public double yawDegrees;
    public double pitchDegrees = -8.0;
    public Mode mode = Mode.WALK;
    public boolean sprinting;

    private final TerrainSampler terrain;
    private boolean onGround = true;

    public CameraRig(TerrainSampler terrain, double east, double north) {
        this.terrain = terrain;
        this.east = east;
        this.north = north;
        this.height = terrainHeight(east, north) + EYE_HEIGHT_M;
    }

    /** Horizontal position as a 2-element array (east, north) -- avoids allocating a vector per frame. */
    public double[] horizontal() {
        return new double[]{east, north};
    }

    public boolean onGround() {
        return onGround;
    }

    /** Applies movement input: {@code forward}/{@code strafe} are in [-1,1], {@code dt} in seconds. */
    public void move(double forward, double strafe, double dt) {
        double heading = Math.toRadians(yawDegrees);
        // yaw 0 looks north; 90 looks east. Forward is (sin, cos) on (east, north).
        double fx = Math.sin(heading);
        double fz = Math.cos(heading);
        double sx = Math.cos(heading);
        double sz = -Math.sin(heading);
        double dx = fx * forward + sx * strafe;
        double dz = fz * forward + sz * strafe;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-6) {
            return;
        }
        dx /= length;
        dz /= length;

        if (mode == Mode.FLY) {
            double speed = FLY_SPEED * (sprinting ? FLY_BOOST / FLY_SPEED : 1.0);
            east += dx * speed * dt;
            north += dz * speed * dt;
            height = Math.max(terrainHeight(east, north) + 1.0, height);
            pitchDegrees = clamp(pitchDegrees, -89.0, 89.0);
            // While flying, height is driven by look direction: pitch up and the camera climbs.
            height += Math.sin(Math.toRadians(pitchDegrees)) * speed * dt * 0.8;
            onGround = false;
            return;
        }

        double speed = (sprinting ? SPRINT_SPEED : WALK_SPEED) * dt;
        double targetEast = east + dx * speed;
        double targetNorth = north + dz * speed;

        // Walk, don't climb walls: refuse a step onto ground steeper than the slope limit, and never
        // step into water deeper than wading depth.
        double targetGround = terrainHeight(targetEast, targetNorth);
        double currentGround = terrainHeight(east, north);
        if (!terrain.isCollisionLoaded(targetEast, targetNorth)) {
            return;                                     // off the loaded world: stand still, do not fall
        }
        boolean tooSteep = targetGround - currentGround > speed * 3.0 + 0.6;
        boolean tooDeep = targetGround < -1.2;
        if (!tooSteep && !tooDeep) {
            east = targetEast;
            north = targetNorth;
        }
        height = terrainHeight(east, north) + EYE_HEIGHT_M;
        onGround = true;
    }

    /** Turns the camera, in degrees. */
    public void look(double yawDeltaDegrees, double pitchDeltaDegrees) {
        yawDegrees = (yawDegrees + yawDeltaDegrees) % 360.0;
        if (yawDegrees < 0) {
            yawDegrees += 360.0;
        }
        pitchDegrees = clamp(pitchDegrees + pitchDeltaDegrees, -89.0, 89.0);
    }

    /** Places the camera at a world position (teleport, spawn), standing on the ground. */
    public void placeAt(double east, double north) {
        this.east = east;
        this.north = north;
        this.height = terrainHeight(east, north) + (mode == Mode.FLY ? 40.0 : EYE_HEIGHT_M);
        this.onGround = mode != Mode.FLY;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        if (mode == Mode.WALK) {
            height = terrainHeight(east, north) + EYE_HEIGHT_M;
            onGround = true;
        } else {
            height = Math.max(height, terrainHeight(east, north) + 60.0);
            onGround = false;
        }
    }

    /** Ground height at a position, clamped into the loaded world. */
    private double terrainHeight(double east, double north) {
        return terrain.height(east, north);
    }

    private static double clamp(double value, double low, double high) {
        return value < low ? low : (value > high ? high : value);
    }

    /** Compact status line for the HUD. */
    public String describe() {
        return String.format(java.util.Locale.ROOT, "%s  E %.0f m  N %.0f m  alt %.1f m  hdg %.0f%c%s",
                mode == Mode.WALK ? "on foot" : "survey camera", east, north, height, yawDegrees,
                '\u00B0', sprinting ? "  sprint" : "");
    }
}
