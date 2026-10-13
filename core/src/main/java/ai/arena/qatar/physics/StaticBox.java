package ai.arena.qatar.physics;

import ai.arena.qatar.math.Quat;
import ai.arena.qatar.math.Vec3;

/**
 * Static oriented box: buildings, walls, barriers, port/airport massing and roadside furniture all
 * compile to these so the solver never sees a dynamic triangle mesh. Buildings are closed shells with
 * a handful of boxes -- not one body per window.
 */
public final class StaticBox {

    public final Vec3 center = new Vec3();
    public final Vec3 halfExtents = new Vec3();
    public final Quat orientation = new Quat();
    public int layer = CollisionLayers.STATIC_BUILDING;
    public int materialId = SurfaceMaterial.ID_CONCRETE_PAVERS;
    /** Stable id from the compiled world chunk (used for diagnostics and streaming bookkeeping). */
    public int sourceId;
    /** Bounding radius about the centre, for broadphase rejection. */
    public double boundingRadius;

    public StaticBox(Vec3 center, Vec3 halfExtents, Quat orientation) {
        set(center, halfExtents, orientation);
    }

    public StaticBox set(Vec3 c, Vec3 he, Quat q) {
        if (!c.isFinite() || !he.isFinite() || he.x <= 0 || he.y <= 0 || he.z <= 0) {
            throw new IllegalArgumentException("invalid static box: c=" + c + " he=" + he);
        }
        center.set(c);
        halfExtents.set(he);
        orientation.set(q).normalize();
        boundingRadius = he.length();
        return this;
    }

    /**
     * Closest point on this box to {@code point} (world frame).
     *
     * @return true when the point is outside the box (standard Euclidean closest point), false when
     *         the point is inside (the closest point is then on the nearest face plane).
     */
    public boolean closestPoint(Vec3 point, Vec3 out) {
        Vec3 local = SCRATCH_LOCAL.set(point).sub(center);
        Quat inv = SCRATCH_INV.set(-orientation.x, -orientation.y, -orientation.z, orientation.w);
        local.rotate(inv);
        double cx = clamp(local.x, -halfExtents.x, halfExtents.x);
        double cy = clamp(local.y, -halfExtents.y, halfExtents.y);
        double cz = clamp(local.z, -halfExtents.z, halfExtents.z);
        boolean outside = Math.abs(local.x) > halfExtents.x || Math.abs(local.y) > halfExtents.y
                || Math.abs(local.z) > halfExtents.z;
        if (!outside) {
            // Push out along the shallowest axis.
            double dx = halfExtents.x - Math.abs(local.x);
            double dy = halfExtents.y - Math.abs(local.y);
            double dz = halfExtents.z - Math.abs(local.z);
            if (dx <= dy && dx <= dz) {
                cx = Math.signum(local.x == 0 ? 1 : local.x) * halfExtents.x;
            } else if (dy <= dz) {
                cy = Math.signum(local.y == 0 ? 1 : local.y) * halfExtents.y;
            } else {
                cz = Math.signum(local.z == 0 ? 1 : local.z) * halfExtents.z;
            }
        }
        out.set(cx, cy, cz);
        Quat rot = SCRATCH_ROT.set(orientation);
        out.rotate(rot).add(center);
        return outside;
    }

    private static final Vec3 SCRATCH_LOCAL = new Vec3();
    private static final Quat SCRATCH_INV = new Quat();
    private static final Quat SCRATCH_ROT = new Quat();

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public String toString() {
        return "StaticBox(c=" + center + " he=" + halfExtents + ")";
    }
}
