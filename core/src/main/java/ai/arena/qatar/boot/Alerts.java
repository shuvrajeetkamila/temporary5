package ai.arena.qatar.boot;

/**
 * A way for the game to put a message in front of a person when the game itself may be what is broken.
 *
 * <p>The panel on the GL surface is not enough on its own, and the third install is the proof: the world
 * failed to load, the panel filled with the boot log and the failure, and the only way to hand that text
 * over was for somebody to read it off a screenshot and type it out. The panel is drawn by the thing that
 * is failing, on a screen the person is holding at arm's length, and it scrolls past as the next frame
 * paints over it.
 *
 * <p>So the platform gets a hook: on Android the launcher installs a reporter that posts a real
 * {@code AlertDialog} -- selectable text, a title that says what it is, and a button that copies the
 * whole thing to the clipboard. Core code only calls {@link #report(String, String)} and never learns
 * what a dialog is, so the desktop build, the tests and this file all stay free of Android.
 *
 * <p>Reporting is best-effort by design. A reporter that throws must not become the failure it was
 * called to describe, so every call is guarded, and with nothing installed every call is a no-op.
 */
public final class Alerts {

    /** What a platform can do with a message: show it, and let the person take it away. */
    public interface Reporter {
        void report(String title, String body);
    }

    /** How many messages are shown before the rest are left to the panel: a person has to be able to play. */
    private static final int MAX_ALERTS = 3;

    private static volatile Reporter reporter;
    private static volatile int shown;

    private Alerts() {
    }

    /** Installs the platform's reporter. Called once, before the game is created. */
    public static void install(Reporter installed) {
        reporter = installed;
        shown = 0;
    }

    /** The reporter in place, for tests; null on a platform that cannot show one. */
    public static Reporter reporter() {
        return reporter;
    }

    /** Posts a message to the person, if there is a platform to post it to and it is still worth it. */
    public static void report(String title, String body) {
        Reporter target = reporter;
        if (target == null || shown >= MAX_ALERTS) {
            return;
        }
        shown++;
        try {
            target.report(title == null ? "Qatar: The Peninsula" : title, body == null ? "" : body);
        } catch (Throwable ignored) {
            // Showing the failure failed. The panel still has it, and the journal has it for next time.
        }
    }

    /**
     * A failure with the detail a person can act on: what broke, where, and the driver's or the
     * platform's own words when there are any.
     *
     * <p>Kept to a handful of lines on purpose. This is read off a phone screen by somebody deciding
     * whether to send it on, so the first line is the whole story and the rest is context: the stage, the
     * heap, and the app's own limit from the platform.
     */
    public static String describe(String where, Throwable throwable) {
        StringBuilder text = new StringBuilder(512);
        text.append(where == null ? "A failure" : where).append('\n');
        text.append('\n').append(throwable == null ? "(no exception)" : throwable.toString()).append('\n');
        Throwable cause = throwable == null ? null : throwable.getCause();
        if (cause != null && cause != throwable) {
            text.append("caused by ").append(cause).append('\n');
        }
        StackTraceElement[] frames = throwable == null ? new StackTraceElement[0] : throwable.getStackTrace();
        // The frames the game itself is in, first. The sixth install printed six lines of
        // java.util.Formatter and stopped before the line of ours that had asked for the wrong thing:
        // a perfect report of an exception whose cause was off the end of it.
        java.util.List<StackTraceElement> shown = StackTraceText.useful(frames, StackTraceText.ALERT_FRAMES);
        for (StackTraceElement frame : shown) {
            text.append("  at ").append(frame).append('\n');
        }
        if (frames.length > shown.size()) {
            text.append("  (+").append(frames.length - shown.size())
                    .append(" more, the JDK's own, in the record)")
                    .append('\n');
        }
        text.append('\n').append("last stage: ").append(CrashJournal.lastBreadcrumb()).append('\n');
        text.append(CrashJournal.heapSummary()).append('\n');
        String trace = CrashJournal.readTrace();
        if (trace != null && !trace.isEmpty()) {
            String[] lines = trace.split("\n");
            int from = Math.max(0, lines.length - 5);
            text.append('\n').append("where the launch had got to:").append('\n');
            for (int i = from; i < lines.length; i++) {
                text.append("  ").append(lines[i]).append('\n');
            }
        }
        text.append('\n').append("The record is kept in the app's files (last-crash.txt); it is not deleted "
                + "by reading it, and the next launch shows it again.");
        return text.toString();
    }

    /** True when a platform reporter is installed, so callers can skip building a message nobody sees. */
    public static boolean canReport() {
        return reporter != null;
    }
}
