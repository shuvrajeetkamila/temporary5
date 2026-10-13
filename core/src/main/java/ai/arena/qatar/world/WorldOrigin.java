package ai.arena.qatar.world;

import ai.arena.qatar.math.Vec3;

/**
 * Floating origin for a whole country at 1:1 scale.
 *
 * <p>The country spans ~160 km, so 32-bit floats lose millimetre precision far from the origin and
 * sub-centimetre precision much sooner. The simulation therefore keeps every entity in
 * <b>global double metres</b> (EPSG:32639 east/north) and renders/contacts in <b>local float metres</b>
 * measured from a rebased origin. A rebase moves the origin in whole chunk-sized steps (never per
 * frame) so streamed data keys stay stable and no chunk is invalidated by a small shift.
 *
 * <p>Invariant: {@code global = local + origin} for every entity, always. A rebase shifts
 * {@code origin} and subtracts the same delta from every local transform in one coherent step.
 */
public final class WorldOrigin {

    /** Whole-grid chunks; a rebase moves by an exact multiple of this so chunk keys remain valid. */
    public static final double REBASE_STEP_M = 1000.0;
    /** Rebase once the focus drifts this far from the origin (well inside float precision limits). */
    public static final double REBASE_THRESHOLD_M = 4000.0;

    private double originEast;
    private double originNorth;
    private int rebaseCount;
    private double lastRebaseDeltaEast;
    private double lastRebaseDeltaNorth;

    public WorldOrigin(double originEast, double originNorth) {
        this.originEast = originEast;
        this.originNorth = originNorth;
    }

    public double originEast() {
        return originEast;
    }

    public double originNorth() {
        return originNorth;
    }

    public int rebaseCount() {
        return rebaseCount;
    }

    public double lastRebaseDeltaEast() {
        return lastRebaseDeltaEast;
    }

    public double lastRebaseDeltaNorth() {
        return lastRebaseDeltaNorth;
    }

    /** Local (float-render) coordinates from global double metres. */
    public float localX(double globalEast) {
        return (float) (globalEast - originEast);
    }

    public float localZ(double globalNorth) {
        return (float) (globalNorth - originNorth);
    }

    public double globalEast(double localX) {
        return localX + originEast;
    }

    public double globalNorth(double localZ) {
        return localZ + originNorth;
    }

    public double globalDistanceToOrigin(double globalEast, double globalNorth) {
        double de = globalEast - originEast;
        double dn = globalNorth - originNorth;
        return Math.sqrt(de * de + dn * dn);
    }

    /**
     * Decides whether the origin should move for a focus at the given global position.
     *
     * @return the rebase delta in metres, or (0,0) when no rebase is needed.
     */
    public Vec3 planRebase(double globalEast, double globalNorth) {
        double de = globalEast - originEast;
        double dn = globalNorth - originNorth;
        if (Math.abs(de) < REBASE_THRESHOLD_M && Math.abs(dn) < REBASE_THRESHOLD_M) {
            return new Vec3(0, 0, 0);
        }
        double stepsE = Math.round(de / REBASE_STEP_M);
        double stepsN = Math.round(dn / REBASE_STEP_M);
        return new Vec3(stepsE * REBASE_STEP_M, 0, stepsN * REBASE_STEP_M);
    }

    /**
     * Applies a rebase delta. Callers must shift every local transform by {@code -delta} and add the
     * delta to the origin; velocity, angular velocity, forces, rotor/propeller phase, route progress
     * and interpolation state are unaffected because they are expressed relative to the entity.
     */
    public void applyRebase(Vec3 delta) {
        originEast += delta.x;
        originNorth += delta.z;
        lastRebaseDeltaEast = delta.x;
        lastRebaseDeltaNorth = delta.z;
        rebaseCount++;
    }

    /** Convenience for entity transforms expressed as doubles in local space. */
    public void shiftLocal(Vec3 localPosition, Vec3 delta) {
        localPosition.x -= delta.x;
        localPosition.z -= delta.z;
    }
}
