package ai.arena.qatar.audio;

/**
 * The sound of the machines, synthesised: one block of samples at a time, and no files anywhere.
 *
 * <p>The game ships 19 MB of world and no audio at all, and the agreed scope for this round was
 * procedural sound and no music. This is the procedural half. Everything audible in it is arithmetic on a
 * phase accumulator: a car engine is a fundamental with two harmonics, a helicopter is a blade thump plus
 * the engine under it, wind and water are one-pole filtered noise. There is no wav, no ogg and no mp3 in
 * the APK, which means there is nothing to fail to load, nothing to keep in sync with the machine that is
 * making the noise, and nothing that sounds the same at every throttle setting.
 *
 * <h2>Why a block and not a sample</h2>
 *
 * <p>The game thread decides what is audible and the audio thread decides when; the two never talk except
 * through one small object of volatile floats, read once per block (see {@link Mix}). The game writes a
 * load, a rotor fraction, a wind fraction and a water fraction -- four numbers, no allocation, once a
 * frame -- and the audio thread renders 1,024 samples at a time from whatever it finds there. A frame is
 * about 16 ms and a block is 46 ms, so the sound follows the throttle with less lag than a person can
 * hear, and neither thread ever waits for the other.
 *
 * <h2>The two rules this exists to obey</h2>
 *
 * <ul>
 *   <li><b>Phase is carried, never reset.</b> A phase that starts again at zero on every block is a click
 *       at twenty-one clicks a second: not an audible click, an audible buzz, and the one fault in a
 *       synthesised engine that everybody hears and nobody can name. {@link Phase} is the state that makes
 *       the block boundary invisible, and {@code EngineSoundTest} measures the seam.</li>
 *   <li><b>Nothing is ever louder than it is.</b> Four sources at full load add up, so the master gain is
 *       chosen for the sum and the output is clamped anyway: a phone speaker driven into its own limit is
 *       a worse sound than a quiet one, and a driver asked for a sample outside its range is a distortion
 *       the game would take the blame for.</li>
 * </ul>
 *
 * <p>No libGDX and no device: this class is arithmetic, so the whole of it is asserted in a JVM by
 * {@code EngineSoundTest}, and the device is {@link Sound}'s problem.
 */
public final class EngineSound {

    /** The sample rate, in hertz. 22,050 is a phone speaker's own limit and half the work of 44,100. */
    public static final int SAMPLE_RATE = 22050;

    /** Samples per block: 46 ms. Long enough that the thread sleeps, short enough to hear the throttle. */
    public static final int BLOCK_SAMPLES = 1024;

    /** The firing frequency of a four-cylinder engine at idle and at the red line, in hertz. */
    public static final double ENGINE_MIN_HZ = 26.0;
    public static final double ENGINE_MAX_HZ = 110.0;

    /** The blade-pass frequency of a helicopter's main rotor, in hertz: 330 rpm, two blades, under load. */
    public static final double ROTOR_MIN_HZ = 8.0;
    public static final double ROTOR_MAX_HZ = 13.0;

    /** How much of each source is mixed in at full load. */
    public static final double ENGINE_LEVEL = 0.80;
    public static final double ROTOR_LEVEL = 0.70;
    public static final double WIND_LEVEL = 0.45;
    public static final double WATER_LEVEL = 0.55;

    /**
     * The master gain, and it is low on purpose. Four sources can be audible at once -- a helicopter at
     * full collective in a gale over water -- and the sum has to stay comfortably inside the range the
     * speaker and the driver can both handle. A game that shouts is a game people mute.
     */
    public static final double MASTER_GAIN = 0.22;

    /** One-pole smoothing for the wind and the water: the coefficient of a low-pass at about 80 and 20 Hz. */
    public static final double WIND_SMOOTHING = 0.10;
    public static final double WATER_SMOOTHING = 0.03;

    private static final double TWO_PI = 2.0 * Math.PI;

    /**
     * What the game thread says is audible: four numbers, written once a frame and read once a block.
     *
     * <p>Volatile, because the two threads are two threads. Not synchronised, because a block rendered
     * from a load that changed halfway through it is a block, not a race: the worst a torn read can do is
     * make one 46 ms block land between two throttle settings, which is what a real engine does anyway.
     */
    public static final class Mix {
        public volatile float engineLoad;
        public volatile float rotorLoad;
        public volatile float windLoad;
        public volatile float waterLoad;
    }

    /**
     * The oscillators' own state, carried from one block to the next.
     *
     * <p>One instance per audio thread and nothing else touches it: these are doubles updated a thousand
     * times a block, and sharing them with another thread would be the one place in this class where
     * "roughly the right phase" was not good enough.
     */
    public static final class Phase {
        double engine;
        double rotor;
        double windFilter;
        double waterFilter;
        long noise;
    }

    /**
     * Renders {@code count} samples of mono sound into {@code out}, from {@code offset} zero.
     *
     * <p>The caller owns the array and the phase, so this allocates nothing and holds no state of its own:
     * the same call with the same inputs gives the same samples, which is what lets a test measure the
     * seam between two blocks and the pitch of an engine.
     */
    public static void render(float[] out, int count, Mix mix, Phase phase) {
        double engineLoad = clamp01(mix.engineLoad);
        double rotorLoad = clamp01(mix.rotorLoad);
        double windLoad = clamp01(mix.windLoad);
        double waterLoad = clamp01(mix.waterLoad);
        double engineHz = ENGINE_MIN_HZ + (ENGINE_MAX_HZ - ENGINE_MIN_HZ) * engineLoad;
        double rotorHz = ROTOR_MIN_HZ + (ROTOR_MAX_HZ - ROTOR_MIN_HZ) * rotorLoad;
        double engineStep = TWO_PI * engineHz / SAMPLE_RATE;
        double rotorStep = TWO_PI * rotorHz / SAMPLE_RATE;
        int limit = Math.min(count, out.length);

        for (int i = 0; i < limit; i++) {
            // The noise source: a 32-bit linear congruential generator, the same one every time, so a
            // rendered block is reproducible and a test can compare two of them.
            phase.noise = phase.noise * 1664525L + 1013904223L;
            double white = ((phase.noise >>> 9) & 0xFFFF) * (2.0 / 65535.0) - 1.0;
            phase.windFilter += (white - phase.windFilter) * WIND_SMOOTHING;
            phase.waterFilter += (white - phase.waterFilter) * WATER_SMOOTHING;

            // An engine: the firing frequency and its first two harmonics. A pure sine is a test tone; a
            // sine with two harmonics is a machine.
            double engine = Math.sin(phase.engine) + 0.45 * Math.sin(2.0 * phase.engine)
                    + 0.18 * Math.sin(3.0 * phase.engine);
            // A rotor: one thump per blade pass, which is a rectified sine cubed, plus the engine that
            // drives it underneath. The first draft used the sine itself and the helicopter sounded like
            // a badly tuned radio.
            double blade = Math.sin(phase.rotor);
            double thump = blade > 0.0 ? blade * blade * blade : 0.0;

            double sample = ENGINE_LEVEL * engineLoad * engine
                    + ROTOR_LEVEL * rotorLoad * (thump + 0.2 * Math.sin(2.0 * phase.rotor))
                    + WIND_LEVEL * windLoad * phase.windFilter
                    + WATER_LEVEL * waterLoad * phase.waterFilter;
            double scaled = sample * MASTER_GAIN;
            out[i] = (float) (scaled > 1.0 ? 1.0 : (scaled < -1.0 ? -1.0 : scaled));

            phase.engine = wrapPhase(phase.engine + engineStep);
            phase.rotor = wrapPhase(phase.rotor + rotorStep);
        }
    }

    /**
     * One block, into a fresh array.
     *
     * <p>For callers that want a block rather than a buffer: a test measuring the seam between two of them,
     * or a tool listening to one. The audio thread does not use this, because its whole point is to render
     * into an array it already owns and never allocate.
     */
    public static float[] renderBlock(Mix mix) {
        return renderBlock(mix, new Phase());
    }

    /** One block from an existing phase: what a caller needs to join two blocks end to end. */
    public static float[] renderBlock(Mix mix, Phase phase) {
        float[] out = new float[BLOCK_SAMPLES];
        render(out, BLOCK_SAMPLES, mix, phase);
        return out;
    }

    /** Keeps a phase inside one turn, so a double does not lose its precision after an hour of noise. */
    static double wrapPhase(double phase) {
        if (phase >= TWO_PI) {
            return phase - TWO_PI * Math.floor(phase / TWO_PI);
        }
        return phase;
    }

    static double clamp01(float value) {
        if (value < 0f) {
            return 0.0;
        }
        return value > 1f ? 1.0 : value;
    }

    private EngineSound() {
    }
}
