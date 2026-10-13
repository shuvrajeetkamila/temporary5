package ai.arena.qatar.physics;

import ai.arena.qatar.math.Quat;
import ai.arena.qatar.math.Vec3;

/**
 * Dynamic rigid body used by every player-controlled object.
 *
 * <p>Normal movement is always force/torque based. Directly setting position or velocity is only
 * permitted for explicit spawn, recovery, or floating-origin rebase -- and origin rebase shifts the
 * global anchor rather than the body in local space (see {@code WorldOrigin}).
 *
 * <p>Inertia is stored as a body-frame diagonal (validated positive and finite at construction).
 * The centre of mass sits at the body origin by convention; geometry offsets live on the collider
 * probes.
 */
public final class RigidBody {

    public final Vec3 position = new Vec3();
    public final Quat orientation = new Quat();
    public final Vec3 linearVelocity = new Vec3();
    public final Vec3 angularVelocity = new Vec3();

    /** Force/torque accumulators, cleared after each fixed step. */
    public final Vec3 accumulatedForce = new Vec3();
    public final Vec3 accumulatedTorque = new Vec3();

    /** Render interpolation endpoints. */
    public final Vec3 previousPosition = new Vec3();
    public final Quat previousOrientation = new Quat();

    public final double mass;
    public final double inverseMass;
    public final Vec3 inertiaDiagonal = new Vec3();
    public final Vec3 inverseInertiaDiagonal = new Vec3();

    public double linearDamping = 0.02;
    public double angularDamping = 0.05;
    public double linearSleepThreshold = 0.05;
    public double angularSleepThreshold = 0.12;
    /**
     * Velocity at the start of the current step, before gravity was added. Sleep detection uses this
     * value: a body resting on the ground cancels gravity in the contact solver every step, so its
     * pre-gravity velocity is ~0 even though its post-integration velocity briefly equals one gravity
     * increment. Testing the latter would keep every resting body awake forever.
     */
    public final Vec3 preStepLinearVelocity = new Vec3();
    public final Vec3 preStepAngularVelocity = new Vec3();
    public double sleepTime = 0.0;
    public boolean sleeping = false;
    public boolean allowSleep = true;
    /** Continuous collision protection: engage adaptive micro-substeps above a speed threshold. */
    public boolean ccdEnabled = true;
    public String debugName = "body";

    public Collider collider;
    /** Material currently under the body (written by the solver; gameplay reads it). */
    public int groundMaterialId = SurfaceMaterial.ID_UNKNOWN;
    /** Last contact normal, world space. */
    public final Vec3 groundNormal = new Vec3(0, 1, 0);
    /** Seconds since the body last had solid ground contact. */
    public double airTime = 0.0;

    // Single-threaded solver scratch: keeps the fixed step allocation free.
    private final Quat scratchInverseOrientation = new Quat();
    private final Quat scratchRotation = new Quat();
    private final Vec3 scratchA = new Vec3();
    private final Vec3 scratchB = new Vec3();
    private final Vec3 scratchC = new Vec3();

    public RigidBody(double massKg, Vec3 inertiaDiagonalKgM2, Collider collider) {
        if (!(massKg > 0.0) || !Double.isFinite(massKg)) {
            throw new IllegalArgumentException("mass must be positive and finite: " + massKg);
        }
        if (!inertiaDiagonalKgM2.isFinite() || inertiaDiagonalKgM2.x <= 0 || inertiaDiagonalKgM2.y <= 0
                || inertiaDiagonalKgM2.z <= 0) {
            throw new IllegalArgumentException("inertia diagonal must be positive and finite: " + inertiaDiagonalKgM2);
        }
        this.mass = massKg;
        this.inverseMass = 1.0 / massKg;
        this.inertiaDiagonal.set(inertiaDiagonalKgM2);
        this.inverseInertiaDiagonal.set(1.0 / inertiaDiagonalKgM2.x, 1.0 / inertiaDiagonalKgM2.y,
                1.0 / inertiaDiagonalKgM2.z);
        this.collider = collider;
    }

    /** I⁻¹_world v = R (I⁻¹_body (Rᵀ v)). */
    public Vec3 applyInverseInertiaWorld(Vec3 worldVec, Vec3 out) {
        scratchInverseOrientation.set(-orientation.x, -orientation.y, -orientation.z, orientation.w);
        scratchA.set(worldVec).rotate(scratchInverseOrientation);
        scratchA.set(scratchA.x * inverseInertiaDiagonal.x, scratchA.y * inverseInertiaDiagonal.y,
                scratchA.z * inverseInertiaDiagonal.z);
        scratchRotation.set(orientation);
        scratchA.rotate(scratchRotation);
        return out.set(scratchA);
    }

    public void applyForce(Vec3 force) {
        accumulatedForce.add(force);
        wake();
    }

    public void applyForceAtPoint(Vec3 force, Vec3 worldPoint) {
        accumulatedForce.add(force);
        scratchB.set(worldPoint).sub(position);
        scratchC.set(0, 0, 0);
        scratchB.cross(force, scratchC);
        accumulatedTorque.add(scratchC);
        wake();
    }

    public void applyTorque(Vec3 torque) {
        accumulatedTorque.add(torque);
        wake();
    }

    /**
     * Applies an impulse at a world point without waking the body: this is the contact solver's hot
     * path, and waking on every solver impulse would make it impossible for a resting body to ever
     * sleep. Gameplay code that genuinely disturbs a body calls {@link #wake()} explicitly.
     */
    public void applyImpulse(Vec3 impulse, Vec3 worldPoint) {
        linearVelocity.addScaled(impulse, inverseMass);
        scratchB.set(worldPoint).sub(position);
        scratchC.set(0, 0, 0);
        scratchB.cross(impulse, scratchC);
        applyInverseInertiaWorld(scratchC, scratchC);
        angularVelocity.add(scratchC);
    }

    /** Solver-friendly central impulse (no implicit wake). */
    public void applyCentralImpulse(Vec3 impulse) {
        linearVelocity.addScaled(impulse, inverseMass);
    }

    /** Explicit disturbance from gameplay (collision with another vehicle, explosion, wind gust). */
    public void applyDisturbingImpulse(Vec3 impulse, Vec3 worldPoint) {
        applyImpulse(impulse, worldPoint);
        wake();
    }

    public void clearAccumulators() {
        accumulatedForce.set(0, 0, 0);
        accumulatedTorque.set(0, 0, 0);
    }

    public void wake() {
        sleeping = false;
        sleepTime = 0.0;
    }

    /** Velocity of a material point of this body (world frame). */
    public Vec3 pointVelocity(Vec3 worldPoint, Vec3 out) {
        scratchB.set(worldPoint).sub(position);
        scratchC.set(0, 0, 0);
        angularVelocity.cross(scratchB, scratchC);
        return out.set(linearVelocity).add(scratchC);
    }

    public void storePreviousTransform() {
        previousPosition.set(position);
        previousOrientation.set(orientation);
    }

    public boolean isFinite() {
        return position.isFinite() && linearVelocity.isFinite() && angularVelocity.isFinite()
                && orientation.isFinite() && accumulatedForce.isFinite() && accumulatedTorque.isFinite();
    }

    /** Clean, finite, awake state -- used by spawn, recovery and origin-rebase paths. */
    public void teleport(Vec3 newPosition, Quat newOrientation) {
        position.set(newPosition);
        orientation.set(newOrientation).normalize();
        linearVelocity.set(0, 0, 0);
        angularVelocity.set(0, 0, 0);
        clearAccumulators();
        storePreviousTransform();
        wake();
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "RigidBody(%s m=%.1fkg p=%s |v|=%.2f m/s)",
                debugName, mass, position, linearVelocity.length());
    }
}
