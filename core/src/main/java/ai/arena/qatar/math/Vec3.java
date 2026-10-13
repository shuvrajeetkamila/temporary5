package ai.arena.qatar.math;

/**
 * Immutable-free mutable 3-component double vector used by the simulation layer.
 *
 * <p>Convention (documented once, used everywhere): <b>X east, Y up, Z north</b>, right handed.
 * Yaw is positive counter-clockwise about +Y, pitch positive nose-up about +X, roll positive
 * right-wing-down about +Z. Quaternion multiplication order is {@code q = qChild * qParent}.
 *
 * <p>All simulation quantities are SI metres/kilograms/seconds.
 */
public final class Vec3 {

    public double x;
    public double y;
    public double z;

    public Vec3() {
        this(0, 0, 0);
    }

    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3(Vec3 other) {
        this(other.x, other.y, other.z);
    }

    public static Vec3 of(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    public Vec3 set(double nx, double ny, double nz) {
        this.x = nx;
        this.y = ny;
        this.z = nz;
        return this;
    }

    public Vec3 set(Vec3 o) {
        return set(o.x, o.y, o.z);
    }

    public Vec3 add(Vec3 o) {
        x += o.x;
        y += o.y;
        z += o.z;
        return this;
    }

    public Vec3 add(double ox, double oy, double oz) {
        x += ox;
        y += oy;
        z += oz;
        return this;
    }

    public Vec3 sub(Vec3 o) {
        x -= o.x;
        y -= o.y;
        z -= o.z;
        return this;
    }

    public Vec3 scale(double s) {
        x *= s;
        y *= s;
        z *= s;
        return this;
    }

    public Vec3 addScaled(Vec3 o, double s) {
        x += o.x * s;
        y += o.y * s;
        z += o.z * s;
        return this;
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o, Vec3 out) {
        double cx = y * o.z - z * o.y;
        double cy = z * o.x - x * o.z;
        double cz = x * o.y - y * o.x;
        return out.set(cx, cy, cz);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double lengthSq() {
        return x * x + y * y + z * z;
    }

    public double distanceTo(Vec3 o) {
        double dx = x - o.x;
        double dy = y - o.y;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Normalises in place; a zero-length vector becomes (0,1,0) to stay finite. */
    public Vec3 normalize() {
        double len = length();
        if (len < 1e-12) {
            return set(0, 1, 0);
        }
        return scale(1.0 / len);
    }

    public Vec3 setLength(double len) {
        normalize();
        return scale(len);
    }

    public boolean isFinite() {
        return !Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z)
                && !Double.isInfinite(x) && !Double.isInfinite(y) && !Double.isInfinite(z);
    }

    /** Rotates this vector by {@code q} (quaternion must be normalised). */
    public Vec3 rotate(Quat q) {
        // t = 2 * (q.xyz x v); v' = v + q.w * t + (q.xyz x t)
        double tx = 2.0 * (q.y * z - q.z * y);
        double ty = 2.0 * (q.z * x - q.x * z);
        double tz = 2.0 * (q.x * y - q.y * x);
        double nx = x + q.w * tx + (q.y * tz - q.z * ty);
        double ny = y + q.w * ty + (q.z * tx - q.x * tz);
        double nz = z + q.w * tz + (q.x * ty - q.y * tx);
        return set(nx, ny, nz);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "(%.4f, %.4f, %.4f)", x, y, z);
    }
}
