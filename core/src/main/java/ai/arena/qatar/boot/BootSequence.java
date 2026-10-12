package ai.arena.qatar.boot;

import ai.arena.qatar.physics.FixedStepClock;

/**
 * Loading state machine behind the loading screen.
 *
 * <p>Stages are real: the loading UI shows which one is running, and a failure carries the stage,
 * an operator readable message and the exception. Required world data (manifest, coarse terrain,
 * collision) failing is fatal and shows the error screen; optional decoration failing is a warning
 * and the world keeps running with a visible fallback.
 */
public final class BootSequence {

    public enum Stage {
        STARTING("Starting"),
        MANIFEST("World manifest"),
        COARSE_TERRAIN("Coarse country terrain"),
        COLLISION("Collision terrain"),
        MATERIALS("Materials and shaders"),
        PHYSICS("Physics world"),
        GAMEPLAY("Gameplay"),
        READY("Ready");

        public final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    public enum Severity {
        INFO,
        WARNING,
        FATAL
    }

    public static final class Failure {
        public final Stage stage;
        public final Severity severity;
        public final String message;
        public final Throwable cause;

        public Failure(Stage stage, Severity severity, String message, Throwable cause) {
            this.stage = stage;
            this.severity = severity;
            this.message = message;
            this.cause = cause;
        }

        @Override
        public String toString() {
            return severity + " at " + stage + ": " + message
                    + (cause == null ? "" : " (" + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")");
        }
    }

    private final FixedStepClock clock = new FixedStepClock();
    private final java.util.List<Failure> failures = new java.util.ArrayList<>();

    private Stage stage = Stage.STARTING;
    private StartupReport report;
    private float stageSeconds;

    public void begin() {
        stage = Stage.STARTING;
        stageSeconds = 0f;
    }

    /** Advances the stage timers; the loading screen calls this with the render frame time. */
    public void advance(float dt) {
        stageSeconds += dt;
    }

    public void enter(Stage next) {
        stage = next;
        stageSeconds = 0f;
    }

    public void fail(Stage where, Severity severity, String message, Throwable cause) {
        failures.add(new Failure(where, severity, message, cause));
    }

    public void complete(StartupReport report) {
        this.report = report;
        enter(Stage.READY);
    }

    public boolean isReady() {
        return stage == Stage.READY && !hasFatalFailure();
    }

    public boolean hasFatalFailure() {
        for (Failure f : failures) {
            if (f.severity == Severity.FATAL) {
                return true;
            }
        }
        return false;
    }

    public Failure firstFatal() {
        for (Failure f : failures) {
            if (f.severity == Severity.FATAL) {
                return f;
            }
        }
        return null;
    }

    public java.util.List<Failure> failures() {
        return java.util.Collections.unmodifiableList(failures);
    }

    public Stage stage() {
        return stage;
    }

    public float stageSeconds() {
        return stageSeconds;
    }

    public FixedStepClock clock() {
        return clock;
    }

    public StartupReport report() {
        return report;
    }
}
