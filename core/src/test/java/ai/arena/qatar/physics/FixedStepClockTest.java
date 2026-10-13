package ai.arena.qatar.physics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The physics clock must produce the same amount of simulated time whether the renderer runs at 15,
 * 20, 30 or 60 FPS, and must never accumulate an unrecoverable debt after a stall.
 */
final class FixedStepClockTest {

    private static double simulateSeconds(FixedStepClock clock, double frameDt, double totalSeconds) {
        double simulated = 0;
        int frames = (int) Math.round(totalSeconds / frameDt);
        for (int i = 0; i < frames; i++) {
            int steps = clock.beginFrame(frameDt);
            simulated += steps * FixedStepClock.FIXED_DT;
        }
        return simulated;
    }

    @Test
    @DisplayName("simulated time tracks wall clock across 15/20/30/60 FPS render profiles")
    void consistentAcrossProfiles() {
        double[] frameTimes = {1.0 / 15.0, 1.0 / 20.0, 1.0 / 30.0, 1.0 / 60.0};
        for (double frameDt : frameTimes) {
            FixedStepClock clock = new FixedStepClock();
            double simulated = simulateSeconds(clock, frameDt, 60.0);
            // One frame of quantisation plus the 4-substep budget is the only accepted deviation.
            assertTrue(Math.abs(simulated - 60.0) < 0.06,
                    "profile " + (1 / frameDt) + " FPS simulated " + simulated + "s of 60s");
        }
    }

    @Test
    @DisplayName("irregular frame times stay within one frame of the wall clock")
    void irregularFrameTimes() {
        FixedStepClock clock = new FixedStepClock();
        double[] frames = {0.0166, 0.021, 0.0333, 0.0508, 0.0083, 0.066, 0.0333};
        double wall = 0;
        double simulated = 0;
        for (int i = 0; i < 600; i++) {
            double dt = frames[i % frames.length];
            wall += dt;
            simulated += clock.beginFrame(dt) * FixedStepClock.FIXED_DT;
        }
        assertTrue(Math.abs(simulated - wall) < 0.05, "drift " + (simulated - wall) + "s over " + wall + "s");
    }

    @Test
    @DisplayName("a long stall does not queue hundreds of catch-up steps")
    void stallDoesNotSpiral() {
        FixedStepClock clock = new FixedStepClock();
        int steps = clock.beginFrame(30.0); // e.g. process resumed after 30 seconds in the background
        assertTrue(steps <= FixedStepClock.MAX_SUBSTEPS, "capped at MAX_SUBSTEPS, got " + steps);
        assertTrue(clock.accumulator() < FixedStepClock.FIXED_DT, "residual debt discarded");
        assertEquals(0, clock.beginFrame(0.0));
        clock.reset();
        assertEquals(0.0, clock.accumulator(), 1e-12);
    }

    @Test
    @DisplayName("negative and NaN frame times are ignored, not propagated")
    void rejectsBadInput() {
        FixedStepClock clock = new FixedStepClock();
        assertEquals(0, clock.beginFrame(-5.0));
        assertEquals(0, clock.beginFrame(Double.NaN));
        assertEquals(0, clock.beginFrame(Double.POSITIVE_INFINITY));
        assertEquals(0.0, clock.accumulator(), 1e-12);
    }
}
