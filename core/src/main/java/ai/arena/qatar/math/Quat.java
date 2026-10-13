package ai.arena.qatar.math;

/**
 * Double precision unit quaternion for rigid body orientation.
 *
 * <p>Multiplication order is {@code a.mul(b)} == "apply b first, then a" when rotating column
 * vectors, matching the engine convention documented in {@link Vec3}.
 */
public final class Quat {

    public double x;
    public double y;
    public double z;
    public double w;

    public Quat() {
        this(0, 0, 0, 1);
    }

    public Quat(double x, double y, double z, double w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

    public static Quat identity() {
        return new Quat(0, 0, 0, 1);
    }

    public static Quat fromAxisAngle(Vec3 axis, double angleRad) {
        Vec3 a = new Vec3(axis).normalize();
        double half = angleRad * 0.5;
        double s = Math.sin(half);
        return new Quat(a.x * s, a.y * s, a.z * s, Math.cos(half));
    }

    /** Yaw (about +Y), pitch (about +X), roll (about +Z) applied in that order. */
    public static Quat fromYawPitchRoll(double yawRad, double pitchRad, double rollRad) {
        Quat qy = fromAxisAngle(Vec3.of(0, 1, 0), yawRad);
        Quat qx = fromAxisAngle(Vec3.of(1, 0, 0), pitchRad);
        Quat qz = fromAxisAngle(Vec3.of(0, 0, 1), rollRad);
        return qy.mul(qx).mul(qz);
    }

    public Quat set(double nx, double ny, double nz, double nw) {
        this.x = nx;
        this.y = ny;
        this.z = nz;
        this.w = nw;
        return this;
    }

    public Quat set(Quat o) {
        return set(o.x, o.y, o.z, o.w);
    }

    public Quat mul(Quat o) {
        double nx = w * o.x + x * o.w + y * o.z - z * o.y;
        double ny = w * o.y - x * o.z + y * o.w + z * o.x;
        double nz = w * o.z + x * o.y - y * o.x + z * o.w;
        double nw = w * o.w - x * o.x - y * o.y - z * o.z;
        return set(nx, ny, nz, nw);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z + w * w);
    }

    public Quat normalize() {
        double len = length();
        if (len < 1e-12) {
            return set(0, 0, 0, 1);
        }
        double inv = 1.0 / len;
        return set(x * inv, y * inv, z * inv, w * inv);
    }

    public Vec3 rotate(Vec3 v, Vec3 out) {
        Vec3 tmp = new Vec3(v);
        tmp.rotate(this);
        return out.set(tmp);
    }

    public Vec3 forward() {
        return new Vec3(0, 0, 1).rotate(this);
    }

    public Vec3 up() {
        return new Vec3(0, 1, 0).rotate(this);
    }

    public Vec3 right() {
        return new Vec3(1, 0, 0).rotate(this);
    }

    /**
     * Integrates orientation by angular velocity for {@code dt} seconds.
     * {@code omega} is in radians/second, world frame.
     */
    public Quat integrateAngularVelocity(Vec3 omega, double dt) {
        double hx = omega.x * dt * 0.5;
        double hy = omega.y * dt * 0.5;
        double hz = omega.z * dt * 0.5;
        double nx = x + (w * hx + y * hz - z * hy);
        double ny = y + (w * hy - x * hz + z * hx);
        double nz = z + (w * hz + x * hy - y * hx);
        double nw = w - (x * hx + y * hy + z * hz);
        return set(nx, ny, nz, nw).normalize();
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Double.isFinite(w);
    }

    /** Signed angle of the body's forward axis in the XZ plane (radians, counter-clockwise from +Z). */
    public double yaw() {
        Vec3 f = forward();
        return Math.atan2(f.x, f.z);
    }

    public double pitch() {
        Vec3 f = forward();
        return Math.asin(clamp(f.y, -1.0, 1.0));
    }

    public double roll() {
        Vec3 r = right();
        Vec3 u = up();
        double flatRight = Math.sqrt(r.x * r.x + r.z * r.z);
        return Math.atan2(-u.x * (r.z / flatRight) + u.z * (r.x / flatRight), u.y);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "Quat(%.5f, %.5f, %.5f, %.5f)", x, y, z, w);
    }
}
