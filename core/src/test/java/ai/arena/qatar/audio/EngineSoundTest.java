package ai.arena.qatar.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sound, measured rather than listened to.
 *
 * <p>There is no audio in CI and no ears in a JVM, so what is asserted here is everything about a
 * synthesised engine that can be seen in the samples themselves, and it is the list of faults that make
 * synthesised sound embarrassing:
 *
 * <ol>
 *   <li><b>Silence is silence.</b> A game with the player standing still on a beach must be exactly silent,
 *       not a quiet hiss: any source that leaks a constant into the mix is a ring the player hears all day.</li>
 *   <li><b>Nothing clips.</b> Four sources at once, and the sample has to stay inside the range a driver can
 *       take. A phone speaker driven into its own limit is worse than a quiet game.</li>
 *   <li><b>The engine's pitch follows the throttle.</b> It is the one thing that makes the noise the
 *       machine's rather than the game's.</li>
 *   <li><b>The seam between two blocks is inaudible.</b> Phase carried, not reset: a synthesised engine that
 *       restarts its oscillator every 46 ms buzzes at 21 Hz, and it is not an obvious fault to look for --
 *       the samples are all perfect, the blocks are each correct, and the sound is still wrong.</li>
 *   <li><b>A block writes exactly what it was asked for.</b> Not one sample more: this is called from an
 *       audio thread holding a buffer that a device is reading, and the caller's own array ends somewhere.</li>
 * </ol>
 */
final class EngineSoundTest {

    private static EngineSound.Mix mix(double engine, double rotor, double wind, double water) {
        EngineSound.Mix mix = new EngineSound.Mix();
        mix.engineLoad = (float) engine;
        mix.rotorLoad = (float) rotor;
        mix.windLoad = (float) wind;
        mix.waterLoad = (float) water;
        return mix;
    }

    @Test
    @DisplayName("nothing asked for is nothing at all, not a quiet hiss")
    void silenceIsSilent() {
        float[] out = new float[EngineSound.BLOCK_SAMPLES];
        java.util.Arrays.fill(out, 7f);
        EngineSound.render(out, out.length, mix(0, 0, 0, 0), new EngineSound.Phase());
        for (int i = 0; i < out.length; i++) {
            assertEquals(0f, out[i], "sample " + i + " is not silence: " + out[i]);
        }
    }

    @Test
    @DisplayName("four sources at full load still fit inside what a speaker can take")
    void nothingClips() {
        for (double scale = 0.25; scale <= 1.0; scale += 0.25) {
            float[] out = new float[EngineSound.BLOCK_SAMPLES];
            EngineSound.render(out, out.length, mix(scale, scale, scale, scale), new EngineSound.Phase());
            for (int i = 0; i < out.length; i++) {
                assertTrue(Float.isFinite(out[i]), "sample " + i + " is not a number");
                assertTrue(Math.abs(out[i]) <= 1f, "sample " + i + " clips at " + out[i]);
                assertTrue(Math.abs(out[i]) < 0.75f, "sample " + i + " is louder than this game should "
                        + "ever be: " + out[i]);
            }
        }
    }

    /** A stretch long enough to count whole cycles: 0.37 s, which is four turns of a slow rotor. */
    private static final int LONG_SAMPLES = 8192;

    @Test
    @DisplayName("the engine's note rises with the throttle")
    void theNoteFollowsTheLoad() {
        // Zero crossings over a stretch, which is proportional to frequency: 26 Hz at the bottom of the
        // range and 110 at the top, so the two counts differ by about three times whatever the two
        // harmonics add to the crossings. Wind and water are off, because noise has no note.
        int idle = crossings(renderLong(mix(0.10, 0, 0, 0)));
        int fast = crossings(renderLong(mix(1.0, 0, 0, 0)));
        assertTrue(idle > 2, "an engine at a tenth throttle makes no sound at all: " + idle + " crossings");
        assertTrue(fast > idle * 2, "the engine's note does not follow the throttle: " + idle
                + " crossings at a tenth and " + fast + " at full load");
    }

    @Test
    @DisplayName("the rotor is a thump, not a hiss, and it is far slower than the engine")
    void theRotorThumps() {
        // The blade-pass frequency is 8 to 13 Hz: an order of magnitude below the engine, which is what
        // makes a helicopter a helicopter from a hundred metres away. Counting zero crossings over a
        // stretch long enough to hold four turns of it separates the two cleanly.
        int rotor = crossings(renderLong(mix(0, 0.9, 0, 0)));
        int engine = crossings(renderLong(mix(0.9, 0, 0, 0)));
        assertTrue(rotor > 4, "the rotor makes no sound: " + rotor + " crossings");
        assertTrue(rotor * 2 < engine, "the rotor is not slower than the engine: " + rotor
                + " crossings against " + engine);
    }

    @Test
    @DisplayName("two blocks joined end to end have no seam")
    void blocksJoinWithoutAClick() {
        // The fault this is for: a phase reset per block. Every sample would be inside range, every block
        // would look right, and the sound would buzz at the block rate. The engine alone is used because
        // white noise is discontinuous by nature -- that is what noise is -- and its jump across the seam
        // is not a fault.
        EngineSound.Mix mix = mix(0.6, 0, 0, 0);
        EngineSound.Phase phase = new EngineSound.Phase();
        float[] first = EngineSound.renderBlock(mix, phase);
        float[] second = EngineSound.renderBlock(mix, phase);
        double seam = Math.abs(second[0] - first[first.length - 1]);
        // The largest a single sample step can be at 110 Hz and this gain: 2*pi*110/22050 * the peak,
        // which is about a fifth of the peak. Anything much larger is a discontinuity.
        double peak = 0.0;
        for (float value : first) {
            peak = Math.max(peak, Math.abs(value));
        }
        // Tight on purpose: one sample's worth of a 76 Hz tone is about one per cent of the peak, and a
        // phase reset jumps by the whole waveform -- so a tolerance of half the peak would have accepted the
        // reset it exists to catch. Measured: 0.0013 against a peak of 0.137 for a carried phase, and 0.018
        // for a reset one.
        assertTrue(seam < peak * 0.05, "the seam between two blocks is a click: " + seam
                + " against a peak of " + peak);
    }

    @Test
    @DisplayName("a block writes exactly the samples it was asked for and leaves the rest alone")
    void aBlockWritesExactlyItsOwnSamples() {
        float[] out = new float[256];
        java.util.Arrays.fill(out, 3f);
        EngineSound.render(out, 64, mix(0.5, 0.5, 0.5, 0.5), new EngineSound.Phase());
        for (int i = 0; i < 64; i++) {
            assertTrue(Math.abs(out[i]) <= 1f, "sample " + i + " was not written");
        }
        for (int i = 64; i < out.length; i++) {
            assertEquals(3f, out[i], "the render wrote past the samples it was asked for, at " + i);
        }
    }

    @Test
    @DisplayName("the same four numbers always make the same sound")
    void renderingIsDeterministic() {
        float[] first = EngineSound.renderBlock(mix(0.5, 0.4, 0.6, 0.3));
        float[] second = EngineSound.renderBlock(mix(0.5, 0.4, 0.6, 0.3));
        assertEquals(first.length, second.length, "two renders of the same block differ in length");
        for (int i = 0; i < first.length; i++) {
            assertEquals(first[i], second[i], 1e-9, "two renders of the same block differ at " + i);
        }
    }

    @Test
    @DisplayName("a load outside the range is clamped rather than trusted")
    void loadsAreClamped() {
        float[] loud = EngineSound.renderBlock(mix(4.0, 4.0, 4.0, 4.0));
        for (float sample : loud) {
            assertTrue(Math.abs(sample) <= 1f, "an out-of-range load got through: " + sample);
        }
        float[] negative = EngineSound.renderBlock(mix(-3.0, -3.0, -3.0, -3.0));
        for (float sample : negative) {
            assertEquals(0f, sample, 0f, "a negative load was not treated as silence");
        }
        float[] huge = EngineSound.renderBlock(mix(Double.MAX_VALUE, 0, 0, 0));
        for (float sample : huge) {
            assertTrue(Float.isFinite(sample), "an infinite load produced a non-number");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A longer stretch than one block: the pitch tests need whole cycles to count. */
    private static float[] renderLong(EngineSound.Mix mix) {
        float[] out = new float[LONG_SAMPLES];
        EngineSound.render(out, out.length, mix, new EngineSound.Phase());
        return out;
    }

    /** How many times a block's samples cross zero, which is a cheap and honest stand-in for pitch. */
    private static int crossings(float[] block) {
        int count = 0;
        for (int i = 1; i < block.length; i++) {
            if ((block[i - 1] < 0f) != (block[i] < 0f)) {
                count++;
            }
        }
        return count;
    }
}
