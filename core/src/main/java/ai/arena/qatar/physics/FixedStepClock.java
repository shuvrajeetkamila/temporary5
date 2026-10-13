package ai.arena.qatar.physics;

/**
 * Fixed simulation clock. Physics always advances in exact {@link #FIXED_DT} (60 Hz) steps regardless
 * of the render frame rate (30/20/15 FPS profiles). Rendering interpolates between the two most
 * recent simulation states using {@link #alpha()}.
 *
 * <p>Anti-spiral policy: at most {@link #MAX_SUBSTEPS} catch-up steps per frame; if the frame time is
 * absurd (debugger pause, process resume, loading hitch) the accumulator is clamped rather than
 * replaying hundreds of steps, which would otherwise launch vehicles into orbit.
 */
public final class FixedStepClock {

    public static final double FIXED_DT = 1.0 / 60.0;
    public static final int MAX_SUBSTEPS = 4;
    public static final double MAX_FRAME_DT = 0.10;

    private double accumulator;
    private int lastSubsteps;

    /** Feeds a render-frame duration (seconds) and returns the number of fixed steps to run now. */
    public int beginFrame(double frameDt) {
        double dt = frameDt;
        if (!Double.isFinite(dt) || dt < 0.0) {
            dt = 0.0;
        }
        if (dt > MAX_FRAME_DT) {
            dt = MAX_FRAME_DT;
        }
        accumulator += dt;
        int steps = 0;
        while (accumulator >= FIXED_DT && steps < MAX_SUBSTEPS) {
            accumulator -= FIXED_DT;
            steps++;
        }
        // Discard anything the substep cap could not consume: never build a debt we cannot repay.
        if (accumulator >= FIXED_DT) {
            accumulator = accumulator % FIXED_DT;
        }
        lastSubsteps = steps;
        return steps;
    }

    /** Interpolation factor in [0,1) between the previous and current simulation state. */
    public double alpha() {
        double a = accumulator / FIXED_DT;
        if (a < 0.0) {
            return 0.0;
        }
        return a >= 1.0 ? 0.999999 : a;
    }

    public double accumulator() {
        return accumulator;
    }

    public int lastSubsteps() {
        return lastSubsteps;
    }

    /** Drops any partial time step. Used after pause/resume, loading and debugger stalls. */
    public void reset() {
        accumulator = 0.0;
        lastSubsteps = 0;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "FixedStepClock(dt=%.6f, acc=%.6f, last=%d)",
                FIXED_DT, accumulator, lastSubsteps);
    }
}
