package ai.arena.qatar.physics;

import ai.arena.qatar.math.Vec3;

/**
 * Collision proxy: a small set of sphere-swept local probe points.
 *
 * <p>Why probes instead of triangle meshes: the specification forbids concave dynamic triangle
 * meshes and per-window Bullet bodies. Sphere probes give cheap, stable, deterministic contacts for
 * vehicles (one probe per wheel contact patch), the human capsule (2-3 probes), the boat hull (hull
 * chine line), and helicopter skids, while the render mesh stays high detail and completely
 * independent.
 *
 * <p>Probe radii and local positions are validated at startup against the render mesh bounds.
 */
public final class Collider {

    /** Local-space probe centres. */
    public final Vec3[] localPoints;
    /** Per-probe radius in metres. */
    public final double[] radii;
    /** Axis-aligned bounds in local space, used for broadphase and spawn-validation. */
    public final Vec3 localMin = new Vec3();
    public final Vec3 localMax = new Vec3();
    /** Collision layer bit for this proxy. */
    public int layer;
    /** Collision mask. */
    public int mask;

    public Collider(Vec3[] points, double radius) {
        this(points, filled(points.length, radius));
    }

    public Collider(Vec3[] points, double[] radii) {
        if (points.length != radii.length || points.length == 0) {
            throw new IllegalArgumentException("collider needs one radius per probe point");
        }
        this.localPoints = new Vec3[points.length];
        this.radii = new double[points.length];
        for (int i = 0; i < points.length; i++) {
            Vec3 p = points[i];
            double r = radii[i];
            if (!p.isFinite() || !Double.isFinite(r) || r <= 0.0) {
                throw new IllegalArgumentException("invalid probe " + i + ": " + p + " r=" + r);
            }
            this.localPoints[i] = new Vec3(p);
            this.radii[i] = r;
            localMin.set(Math.min(localMin.x, p.x - r), Math.min(localMin.y, p.y - r), Math.min(localMin.z, p.z - r));
            localMax.set(Math.max(localMax.x, p.x + r), Math.max(localMax.y, p.y + r), Math.max(localMax.z, p.z + r));
        }
        this.layer = CollisionLayers.HUMAN;
        this.mask = CollisionLayers.ALL;
    }

    private static double[] filled(int n, double v) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    public int probeCount() {
        return localPoints.length;
    }

    public double maxRadius() {
        double m = 0;
        for (double r : radii) {
            m = Math.max(m, r);
        }
        return m;
    }

    public double minRadius() {
        double m = Double.MAX_VALUE;
        for (double r : radii) {
            m = Math.min(m, r);
        }
        return m;
    }

    /** Bounding radius about the collider origin -- used for broadphase rejection. */
    public double boundingRadius() {
        double m = 0;
        for (int i = 0; i < localPoints.length; i++) {
            m = Math.max(m, localPoints[i].length() + radii[i]);
        }
        return m;
    }

    public Collider withLayers(int layer, int mask) {
        this.layer = layer;
        this.mask = mask;
        return this;
    }

    /** World-space position of probe {@code i} given a body transform. */
    public void worldProbe(int i, Vec3 position, ai.arena.qatar.math.Quat orientation, Vec3 out) {
        out.set(localPoints[i]).rotate(orientation).add(position);
    }
}
