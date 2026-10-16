package ai.arena.qatar.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.AudioDevice;

/**
 * The audio device, the thread that feeds it, and the honest answer when either of them fails.
 *
 * <p>{@link EngineSound} turns four numbers into samples; this owns the thing that plays them. The shape
 * is forced by libGDX's own API: an {@link AudioDevice} is written to in blocks and the write blocks until
 * the hardware has room for the next one, so something has to be rendering while the game thread is
 * drawing. That something is one daemon thread doing nothing but: render 1,024 samples from whatever the
 * mix says, write them, repeat.
 *
 * <h2>Failure is a state, not a crash</h2>
 *
 * <p>Audio is the least reliable subsystem on a phone: a device can refuse to open, a headset can be
 * unplugged, a build can be missing the backend. Every one of those is caught here and turned into
 * {@link Status#FAILED} with the reason kept, which the panel prints. The alternative -- letting the audio
 * thread's exception escape -- is a game that plays no sound and says nothing about why, and this project
 * has been bitten by exactly that shape of bug enough times.
 *
 * <p>Silence is also a state: {@link #setActive(boolean)} is what a paused game calls, and it does not
 * stop the thread. A stopped device has to be reopened, and reopening an audio device on the frame the
 * player comes back is a stutter at the worst possible moment.
 */
public final class Sound {

    /** What the sound system is doing, which is what the panel prints. */
    public enum Status {
        /** Never started, or stopped deliberately. */
        OFF,
        /** The device is open and its thread is rendering. */
        ON,
        /** The device would not open, or the thread died: the reason is in {@link #describe()}. */
        FAILED
    }

    private final EngineSound.Mix mix = new EngineSound.Mix();
    private final EngineSound.Phase phase = new EngineSound.Phase();
    private final float[] block = new float[EngineSound.BLOCK_SAMPLES];

    private volatile boolean running;
    private volatile boolean audible = true;
    private volatile long blocksRendered;
    private Status status = Status.OFF;
    private String failure;
    private Thread thread;
    private AudioDevice device;

    /**
     * Opens the audio device and starts rendering.
     *
     * <p>Called once, from the screen's own construction, inside a guarded step: a phone with no audio at
     * all must still play the game, and the panel says so rather than the silence being a mystery.
     */
    public void start() {
        if (status == Status.ON || status == Status.FAILED) {
            return;
        }
        try {
            device = Gdx.audio.newAudioDevice(EngineSound.SAMPLE_RATE, true);
            device.setVolume(1f);
            running = true;
            thread = new Thread(this::pump, "qatar-sound");
            thread.setDaemon(true);
            thread.start();
            status = Status.ON;
            ai.arena.qatar.boot.ScreenLog.GLOBAL.log("sound: " + EngineSound.SAMPLE_RATE
                    + " Hz mono, synthesised, block " + EngineSound.BLOCK_SAMPLES);
        } catch (Throwable t) {
            status = Status.FAILED;
            failure = shortReason(t);
            ai.arena.qatar.boot.ScreenLog.GLOBAL.warn("no sound: " + failure);
        }
    }

    /**
     * The audio thread: render, write, repeat, until it is told to stop.
     *
     * <p>One catch around the whole loop, because a write that throws -- a device removed, a backend that
     * gave up -- is a dead sound system and not a dead game. The thread ends itself and the panel repeats
     * the reason for as long as the game runs.
     */
    private void pump() {
        try {
            while (running) {
                if (audible) {
                    EngineSound.render(block, EngineSound.BLOCK_SAMPLES, mix, phase);
                } else {
                    java.util.Arrays.fill(block, 0f);
                }
                device.writeSamples(block, 0, EngineSound.BLOCK_SAMPLES);
                blocksRendered++;
            }
        } catch (Throwable t) {
            status = Status.FAILED;
            failure = shortReason(t);
            running = false;
        }
    }

    /**
     * What the player can hear, from where they are and what they are in. Called once a frame; four
     * volatile writes and no allocation, so it costs nothing on the game thread.
     */
    public void update(double engineLoad, double rotorLoad, double windLoad, double waterLoad) {
        mix.engineLoad = (float) clamp01(engineLoad);
        mix.rotorLoad = (float) clamp01(rotorLoad);
        mix.windLoad = (float) clamp01(windLoad);
        mix.waterLoad = (float) clamp01(waterLoad);
    }

    /** True when the mix is being silenced: a paused game, not a stopped device. */
    public void setActive(boolean active) {
        audible = active;
    }

    public boolean audible() {
        return audible;
    }

    public Status status() {
        return status;
    }

    /** How many blocks have been written. A number that stops climbing is a stalled audio thread. */
    public long blocksRendered() {
        return blocksRendered;
    }

    /** One short line for the diagnostics panel: never null, never longer than the panel has room for. */
    public String describe() {
        switch (status) {
            case ON:
                // Seconds of sound actually written, from the count and the block size rather than from a
                // wall clock: a number that stops climbing is a stalled audio thread, and an estimate that
                // drifts away from the device is worse than no number at all.
                return audible ? "sound on, "
                        + (blocksRendered * EngineSound.BLOCK_SAMPLES) / EngineSound.SAMPLE_RATE + " s"
                        : "sound paused";
            case FAILED:
                return "sound FAILED: " + failure;
            default:
                return "sound off";
        }
    }

    /** Stops the thread and closes the device. The screen's own dispose calls this. */
    public void stop() {
        running = false;
        audible = false;
        Thread thread = this.thread;
        if (thread != null) {
            try {
                thread.join(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.thread = null;
        }
        if (device != null) {
            try {
                device.dispose();
            } catch (Throwable ignored) {
                // Closing a device that is already gone is not a second failure.
            }
            device = null;
        }
        if (status == Status.ON) {
            status = Status.OFF;
        }
    }

    /** The first line of a failure, cut to something the panel can print. */
    private static String shortReason(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            message = t.getClass().getSimpleName();
        }
        return message.length() > 40 ? message.substring(0, 40) : message;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            return 0.0;
        }
        return value > 1.0 ? 1.0 : value;
    }
}
