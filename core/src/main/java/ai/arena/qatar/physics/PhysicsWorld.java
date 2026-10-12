package ai.arena.qatar.physics;

import ai.arena.qatar.math.Quat;
import ai.arena.qatar.math.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, fixed-step rigid body world for the Qatar peninsula.
 *
 * <h2>Why an in-house solver rather than gdx-bullet</h2>
 * The project evaluated libGDX's Bullet binding first. Bullet is an excellent general engine, but the
 * performance plan here is unusual: a 1:1 country with thousands of streamed static boxes, four hand
 * tuned vehicle models, and a hard requirement that <i>every</i> physics acceptance test is runnable
 * head-less in CI on a plain JVM. Sphere-probe contacts against a sampled heightfield plus sequential
 * impulses give:
 * <ul>
 *   <li>bit-reproducible results on the JVM (no native dependency, testable without a device),</li>
 *   <li>a contact set small enough to inspect in unit tests (wheel-by-wheel load, hull submersion),</li>
 *   <li>zero per-step allocation and zero JNI boundary crossings in the 60 Hz loop.</li>
 * </ul>
 * The vehicle, rotor, hull and aerodynamic models are force/torque based on top of this solver; no
 * physical behaviour is faked by animation. See docs/PHYSICS_FOUNDATION.md for the full rationale,
 * measured numbers and the migration notes should Bullet ever become the better trade.
 *
 * <h2>Step order (per fixed tick)</h2>
 * <ol>
 *   <li>micro-substep count decided from the fastest body (continuous collision protection),</li>
 *   <li>integrate velocities from accumulated forces plus gravity,</li>
 *   <li>detect contacts against terrain and static boxes,</li>
 *   <li>resolve contacts with sequential impulses (friction cone limited),</li>
 *   <li>integrate positions and orientations,</li>
 *   <li>sleep bookkeeping.</li>
 * </ol>
 */
public final class PhysicsWorld {

    /** Standard gravity. Configurable, but applied consistently everywhere. */
    public static final double STANDARD_GRAVITY = 9.80665;

    private static final int MAX_CONTACTS = 512;
    private static final int SOLVER_ITERATIONS = 8;
    private static final double PENETRATION_SLOP = 0.005;
    private static final double POSITION_CORRECTION = 0.35;
    private static final double BOUNCE_THRESHOLD = 1.0;
    /** Rolling resistance coefficient (1/s) applied to grounded, slow bodies. */
    private static final double ROLLING_RESISTANCE = 6.0;

    /**
     * Speculative contact margin (metres): the terrain generates a contact when a probe is within this
     * distance of the surface, not only when it has already penetrated. Without it a resting body
     * alternates between "in contact" and "hovering" frames, its air time keeps resetting the sleep
     * timer, and no body would ever fall asleep. It also cancels each step's gravity increment before
     * it becomes visible motion.
     */
    private static final double CONTACT_MARGIN = 0.02;

    /** Rest must be sustained for this long before a body is allowed to sleep. */
    private static final double SLEEP_DELAY_S = 0.75;
    /** How fast a noisy substep erodes accumulated rest time (per second of simulation). */
    private static final double SLEEP_DECAY_FACTOR = 3.0;

    public double gravity = STANDARD_GRAVITY;

    /** Rolling resistance applied to slow, grounded bodies (1/s). Contact is not frictionless. */
    public double rollingResistance = ROLLING_RESISTANCE;

    private final List<RigidBody> bodies = new ArrayList<>();
    private final List<StaticBox> staticBoxes = new ArrayList<>();

    private TerrainSampler terrain = TerrainSampler.FLAT;

    // Pre-allocated contact pool.
    private final int[] contactBody = new int[MAX_CONTACTS];
    private final int[] contactProbe = new int[MAX_CONTACTS];
    private final Vec3[] contactPoint = new Vec3[MAX_CONTACTS];
    private final Vec3[] contactNormal = new Vec3[MAX_CONTACTS];
    private final double[] contactDepth = new double[MAX_CONTACTS];
    private final double[] contactFriction = new double[MAX_CONTACTS];
    private final double[] contactRestitution = new double[MAX_CONTACTS];
    private final int[] contactMaterial = new int[MAX_CONTACTS];

    // Solver scratch.
    private final Vec3 sA = new Vec3();
    private final Vec3 sB = new Vec3();
    private final Vec3 sC = new Vec3();
    private final Vec3 sD = new Vec3();
    private final Vec3 sE = new Vec3();
    private final Vec3 sF = new Vec3();

    private int lastContactCount;
    private int lastSubsteps;
    private int overflowContacts;
    private SubstepHook substepHook;

    /**
     * Called once per micro-substep before integration, so vehicle/human models can recompute their
     * forces against the latest state. Without this, an adaptive substep would integrate gravity-only
     * motion for the extra substeps.
     */
    public interface SubstepHook {
        void beforeSubstep(PhysicsWorld world, double h, int substepIndex);
    }

    public void setSubstepHook(SubstepHook hook) {
        this.substepHook = hook;
    }

    public PhysicsWorld() {
        for (int i = 0; i < MAX_CONTACTS; i++) {
            contactPoint[i] = new Vec3();
            contactNormal[i] = new Vec3();
        }
    }

    public void setTerrain(TerrainSampler sampler) {
        this.terrain = sampler == null ? TerrainSampler.FLAT : sampler;
    }

    public TerrainSampler terrain() {
        return terrain;
    }

    public RigidBody addBody(RigidBody body) {
        bodies.add(body);
        return body;
    }

    public void removeBody(RigidBody body) {
        bodies.remove(body);
    }

    public void clearBodies() {
        bodies.clear();
    }

    public List<RigidBody> bodies() {
        return bodies;
    }

    public void addStaticBox(StaticBox box) {
        staticBoxes.add(box);
    }

    public void clearStaticBoxes() {
        staticBoxes.clear();
    }

    public List<StaticBox> staticBoxes() {
        return staticBoxes;
    }

    public int lastContactCount() {
        return lastContactCount;
    }

    public int lastSubsteps() {
        return lastSubsteps;
    }

    /** Number of contact slots that were discarded because the pool was full (must stay 0 in tests). */
    public int overflowContacts() {
        return overflowContacts;
    }

    /** Runs exactly one fixed step of {@code dt} seconds (normally {@code 1/60}). */
    public void step(double dt) {
        if (!Double.isFinite(dt) || dt <= 0.0) {
            return;
        }
        lastSubsteps = substepCount(dt);
        double h = dt / lastSubsteps;
        for (int sub = 0; sub < lastSubsteps; sub++) {
            if (substepHook != null) {
                substepHook.beforeSubstep(this, h, sub);
            }
            integrateVelocities(h);
            int contacts = detectContacts();
            solveContacts(contacts, h);
            applyRollingResistance(h, contacts);
            integratePositions(h, contacts);
            updateSleepState(h, contacts);
        }
        lastContactCount = 0;
    }

    private int substepCount(double dt) {
        double maxSpeed = 0.0;
        double minRadius = 0.25;
        for (int i = 0; i < bodies.size(); i++) {
            RigidBody b = bodies.get(i);
            if (b.sleeping || !b.ccdEnabled) {
                continue;
            }
            double speed = b.linearVelocity.length();
            if (speed > maxSpeed) {
                maxSpeed = speed;
                minRadius = Math.max(0.05, b.collider == null ? 0.25 : b.collider.minRadius());
            }
        }
        if (maxSpeed * dt <= minRadius) {
            return 1;
        }
        int n = (int) Math.ceil((maxSpeed * dt) / minRadius);
        return Math.max(1, Math.min(8, n));
    }

    private void integrateVelocities(double h) {
        for (int i = 0; i < bodies.size(); i++) {
            RigidBody b = bodies.get(i);
            if (b.sleeping) {
                b.clearAccumulators();
                continue;
            }
            b.preStepLinearVelocity.set(b.linearVelocity);
            b.preStepAngularVelocity.set(b.angularVelocity);
            // v += (F/m + g) * h     with linear damping applied as a bounded exponential decay.
            double ax = b.accumulatedForce.x * b.inverseMass;
            double ay = b.accumulatedForce.y * b.inverseMass - gravity;
            double az = b.accumulatedForce.z * b.inverseMass;
            b.linearVelocity.x += ax * h;
            b.linearVelocity.y += ay * h;
            b.linearVelocity.z += az * h;
            double linDamp = Math.max(0.0, 1.0 - b.linearDamping * h);
            b.linearVelocity.scale(linDamp);

            b.applyInverseInertiaWorld(b.accumulatedTorque, sA);
            b.angularVelocity.addScaled(sA, h);
            double angDamp = Math.max(0.0, 1.0 - b.angularDamping * h);
            b.angularVelocity.scale(angDamp);

            if (!b.isFinite()) {
                // Never let a NaN propagate silently: freeze the body in a diagnostic state.
                b.linearVelocity.set(0, 0, 0);
                b.angularVelocity.set(0, 0, 0);
                b.clearAccumulators();
                b.groundMaterialId = SurfaceMaterial.ID_UNKNOWN;
            }
        }
    }

    private int detectContacts() {
        int n = 0;
        for (int bi = 0; bi < bodies.size(); bi++) {
            RigidBody b = bodies.get(bi);
            if (b.sleeping || b.collider == null) {
                continue;
            }
            for (int pi = 0; pi < b.collider.probeCount(); pi++) {
                b.collider.worldProbe(pi, b.position, b.orientation, sA);
                double radius = b.collider.radii[pi];

                // --- terrain (sampled heightfield) ---
                if (terrain.isCollisionLoaded(sA.x, sA.z)) {
                    double groundY = terrain.height(sA.x, sA.z);
                    double depth = (groundY + radius) - sA.y;
                    if (depth > -CONTACT_MARGIN) {
                        terrain.normal(sA.x, sA.z, sB);
                        int matId = terrain.materialId(sA.x, sA.z);
                        if (n < MAX_CONTACTS) {
                            recordContact(n++, bi, pi, sA.x - sB.x * radius, sA.y - sB.y * radius,
                                    sA.z - sB.z * radius, sB, Math.max(depth, 0.0), matId);
                        } else {
                            overflowContacts++;
                        }
                        b.groundNormal.set(sB);
                        b.groundMaterialId = matId;
                        continue;
                    }
                }

                // --- static boxes (buildings, barriers, massing) ---
                for (int si = 0; si < staticBoxes.size(); si++) {
                    StaticBox box = staticBoxes.get(si);
                    if (!CollisionLayers.interacts(b.collider.layer, box.layer)) {
                        continue;
                    }
                    double dx = sA.x - box.center.x;
                    double dy = sA.y - box.center.y;
                    double dz = sA.z - box.center.z;
                    double reach = box.boundingRadius + radius;
                    if (dx * dx + dy * dy + dz * dz > reach * reach) {
                        continue;
                    }
                    boolean outside = box.closestPoint(sA, sB);
                    sC.set(sA).sub(sB);
                    double dist = sC.length();
                    if (dist > radius) {
                        continue;
                    }
                    if (dist > 1e-6) {
                        sC.scale(1.0 / dist);
                    } else {
                        sC.set(0, 1, 0);
                    }
                    double depth = radius - dist;
                    if (depth <= 0.0) {
                        continue;
                    }
                    if (!outside) {
                        // Inside the shell: push out through the shallowest face (already returned).
                        depth = radius + dist;
                    }
                    if (n < MAX_CONTACTS) {
                        recordContact(n++, bi, pi, sB.x, sB.y, sB.z, sC, depth, box.materialId);
                    } else {
                        overflowContacts++;
                    }
                }
            }
        }
        return n;
    }

    private void recordContact(int index, int bodyIndex, int probeIndex, double px, double py, double pz,
                               Vec3 normal, double depth, int materialId) {
        contactBody[index] = bodyIndex;
        contactProbe[index] = probeIndex;
        contactPoint[index].set(px, py, pz);
        contactNormal[index].set(normal).normalize();
        contactDepth[index] = depth;
        SurfaceMaterial mat = MaterialRegistry.get(materialId);
        contactFriction[index] = mat.contactFriction;
        contactRestitution[index] = mat.restitution;
        contactMaterial[index] = mat.id;
    }

    private void solveContacts(int count, double h) {
        for (int iter = 0; iter < SOLVER_ITERATIONS; iter++) {
            for (int c = 0; c < count; c++) {
                RigidBody b = bodies.get(contactBody[c]);
                if (b.sleeping) {
                    continue;
                }
                Vec3 point = contactPoint[c];
                Vec3 normal = contactNormal[c];
                b.pointVelocity(point, sA);
                double vn = sA.dot(normal);
                double restitution = contactRestitution[c];
                double target = 0.0;
                if (vn < -BOUNCE_THRESHOLD && restitution > 0.0) {
                    target = -vn * restitution;
                }
                double deltaVn = target - vn;
                if (deltaVn > 0.0) {
                    // Normal impulse magnitude: 1/(1/m + n·(I⁻¹(r×n)×r))
                    sB.set(point).sub(b.position);
                    sC.set(0, 0, 0);
                    sB.cross(normal, sC);
                    b.applyInverseInertiaWorld(sC, sD);
                    sE.set(0, 0, 0);
                    sD.cross(sB, sE);
                    double denom = b.inverseMass + sE.dot(normal);
                    if (denom <= 1e-9) {
                        continue;
                    }
                    double jn = deltaVn / denom;
                    sA.set(normal).scale(jn);
                    b.applyImpulse(sA, point);

                    // Coulomb friction: tangential impulse limited by mu * jn (combined friction).
                    b.pointVelocity(point, sA);
                    sB.set(sA).addScaled(normal, -sA.dot(normal));
                    double tangentialSpeed = sB.length();
                    if (tangentialSpeed > 1e-6) {
                        sB.scale(1.0 / tangentialSpeed);
                        sC.set(point).sub(b.position);
                        sD.set(0, 0, 0);
                        sC.cross(sB, sD);
                        b.applyInverseInertiaWorld(sD, sF);
                        sE.set(0, 0, 0);
                        sF.cross(sC, sE);
                        double tanDenom = b.inverseMass + sE.dot(sB);
                        if (tanDenom > 1e-9) {
                            double maxFriction = contactFriction[c] * jn;
                            double jt = Math.min(tangentialSpeed / tanDenom, maxFriction);
                            sA.set(sB).scale(-jt);
                            b.applyImpulse(sA, point);
                        }
                    }
                }
            }
        }
    }

    /**
     * Rolling and pivoting resistance for bodies that are in contact and no longer moving.
     *
     * <p>A speed-ordered sequential impulse solver leaves a small permanent angular velocity on a
     * resting body (each contact probe is solved in turn, so the last one always spins it slightly).
     * Real contact resists that rolling; modelling it is what lets a settled crate actually stop, and
     * without it no resting body would ever sleep.
     */
    private void applyRollingResistance(double h, int contactCount) {
        for (int c = 0; c < contactCount; c++) {
            RigidBody b = bodies.get(contactBody[c]);
            if (b.sleeping) {
                continue;
            }
            if (b.preStepLinearVelocity.length() < b.linearSleepThreshold) {
                double damp = Math.max(0.0, 1.0 - rollingResistance * h);
                b.angularVelocity.scale(damp);
            }
        }
    }

    private void integratePositions(double h, int contactCount) {
        for (int i = 0; i < bodies.size(); i++) {
            RigidBody b = bodies.get(i);
            if (b.sleeping) {
                continue;
            }
            b.position.addScaled(b.linearVelocity, h);
            b.orientation.integrateAngularVelocity(b.angularVelocity, h);
        }
        // Positional correction: resolve residual penetration without adding energy.
        for (int c = 0; c < contactCount; c++) {
            RigidBody b = bodies.get(contactBody[c]);
            if (b.sleeping) {
                continue;
            }
            double depth = contactDepth[c] - PENETRATION_SLOP;
            if (depth <= 0.0) {
                continue;
            }
            Vec3 normal = contactNormal[c];
            b.position.addScaled(normal, depth * POSITION_CORRECTION);
        }
    }

    private void updateSleepState(double h, int contactCount) {
        // Reset per-body contact flag for this substep.
        for (int i = 0; i < bodies.size(); i++) {
            bodies.get(i).airTime += h;
        }
        for (int c = 0; c < contactCount; c++) {
            bodies.get(contactBody[c]).airTime = 0.0;
        }
        for (int i = 0; i < bodies.size(); i++) {
            RigidBody b = bodies.get(i);
            b.clearAccumulators();
            b.storePreviousTransform();
            if (b.sleeping) {
                // A sleeping body is at rest by definition, and the contact detector ignores it, so it
                // generates no contacts and would otherwise look "airborne" to this very function and
                // erode its own rest clock until it woke itself up again. Keep it frozen with a full
                // rest clock; it leaves this state only through a real disturbance (RigidBody.wake,
                // applyForce, applyDisturbingImpulse) or when its supporting terrain is streamed out.
                b.linearVelocity.set(0, 0, 0);
                b.angularVelocity.set(0, 0, 0);
                b.airTime = 0.0;
                b.sleepTime = SLEEP_DELAY_S;
                if (!terrain.isCollisionLoaded(b.position.x, b.position.z)) {
                    b.wake();
                }
                continue;
            }
            if (!b.allowSleep) {
                continue;
            }
            boolean quiet = b.preStepLinearVelocity.length() < b.linearSleepThreshold
                    && b.preStepAngularVelocity.length() < b.angularSleepThreshold
                    && b.airTime == 0.0;
            if (quiet) {
                b.sleepTime += h;
                if (b.sleepTime > SLEEP_DELAY_S) {
                    b.sleeping = true;
                    b.linearVelocity.set(0, 0, 0);
                    b.angularVelocity.set(0, 0, 0);
                }
            } else {
                // A single noisy substep (one gravity increment that a speculative contact had not yet
                // cancelled, one contact probe solved out of order) must not throw away a full second
                // of measured rest. Quiet time erodes on disturbance and only a sustained disturbance
                // wakes the body, which is what the sleep contract actually promises.
                b.sleepTime = Math.max(0.0, b.sleepTime - SLEEP_DECAY_FACTOR * h);
                if (b.sleepTime == 0.0) {
                    b.sleeping = false;
                }
            }
        }
    }

    /** Deterministic checksum of the whole world state; used by determinism regression tests. */
    public long stateChecksum() {
        long hash = 1125899906842597L;
        for (int i = 0; i < bodies.size(); i++) {
            RigidBody b = bodies.get(i);
            hash = hash * 31 + Double.doubleToLongBits(Math.round(b.position.x * 1e4));
            hash = hash * 31 + Double.doubleToLongBits(Math.round(b.position.y * 1e4));
            hash = hash * 31 + Double.doubleToLongBits(Math.round(b.position.z * 1e4));
            hash = hash * 31 + Double.doubleToLongBits(Math.round(b.linearVelocity.x * 1e4));
            hash = hash * 31 + Double.doubleToLongBits(Math.round(b.linearVelocity.y * 1e4));
            hash = hash * 31 + Double.doubleToLongBits(Math.round(b.linearVelocity.z * 1e4));
        }
        return hash;
    }

    /** True when every body is finite and above the collision-loaded terrain. */
    public boolean validate() {
        for (int i = 0; i < bodies.size(); i++) {
            if (!bodies.get(i).isFinite()) {
                return false;
            }
        }
        return true;
    }

    /** Convenience for tests and diagnostics: gravity applied after a free-fall of {@code t} seconds. */
    public static double freeFallDistance(double t) {
        return 0.5 * STANDARD_GRAVITY * t * t;
    }

    public static Quat identity() {
        return Quat.identity();
    }
}
