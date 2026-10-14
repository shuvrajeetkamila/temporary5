package ai.arena.qatar.boot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A place for a crash to be written down, using nothing but {@code java.io}.
 *
 * <p>This class exists because of the third install: the app closed immediately and said nothing, and
 * there was no way to find out why from outside the phone. The construction of that round had two holes
 * in it, and both are this class's business:
 *
 * <ol>
 *   <li><b>The crash recorder lived inside the game.</b> It was installed in {@code create()}, which is
 *       the very code that might fail, and it wrote through libGDX's file handles, which need a GL
 *       context that a fatal failure may never have produced. A recorder that shares a fate with the
 *       thing it records is not a recorder. This one is installed by the Android entry point before
 *       libGDX is even created, and it writes with {@link FileOutputStream}.</li>
 *   <li><b>The record deleted itself.</b> On the next launch the panel printed the trace and then
 *       deleted the file -- so a repeating crash showed its reason for as long as it took to die again,
 *       which is a fraction of a second and impossible to photograph. Nothing here deletes anything:
 *       the record stays until a person chooses to clear it.</li>
 * </ol>
 *
 * <p>It also keeps <b>breadcrumbs</b>: one line per launch stage, with the time and the heap in use.
 * A crash handler only runs when something throws; when the process is killed outright -- which is what
 * a phone does to an app that grows too large -- there is no exception, no stack trace, and nothing at
 * all unless the app has been leaving a trail. The last breadcrumb is often the whole diagnosis.
 *
 * <p>Deliberately not libGDX and deliberately not a logging framework: this is the code that runs when
 * everything else has failed, so it depends on nothing that can fail first.
 */
public final class CrashJournal {

    /** The crash itself: thread, throwable, frames, and the last breadcrumb. */
    public static final String CRASH_FILE = "last-crash.txt";
    /** Where the launch had got to, one line per stage, oldest first. */
    public static final String TRACE_FILE = "boot-trace.txt";

    /** A crash record longer than this is truncated: a screenshot cannot show more anyway. */
    private static final int MAX_CRASH_CHARS = 6000;
    /** Breadcrumbs kept in the trace file; older ones are dropped when it is rewritten. */
    private static final int MAX_TRACE_LINES = 80;
    /** Rewrite the trace file when it grows past this, so breadcrumbs stay bounded. */
    private static final long MAX_TRACE_BYTES = 24_000;
    /** Stack frames recorded per throwable: enough to name the class and method that threw. */
    private static final int FRAMES = 24;

    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    private static File directory;
    private static volatile boolean installed;
    private static String lastBreadcrumb = "(nothing yet)";

    private CrashJournal() {
    }

    /**
     * Tells the journal where it may write. Called once, as early as possible -- on Android from the
     * entry point's {@code onCreate}, with the app's private files directory.
     *
     * <p>Without a directory every method here is a no-op, which is what desktop runs and unit tests
     * want: a test must not write into somebody's home directory just because a class was loaded.
     */
    public static synchronized void install(File dir) {
        directory = dir;
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
    }

    public static synchronized File directory() {
        return directory;
    }

    /**
     * Writes a fatal throwable to the crash record, then lets whatever handler was installed before
     * this one run -- which on Android is the one that kills the process and shows the system dialog.
     * This must never be the last handler in the chain, or the app would hang on a dead thread.
     */
    public static synchronized void installHandler() {
        if (installed) {
            return;
        }
        installed = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    writeCrash("thread " + thread.getName(), throwable);
                } catch (Throwable ignored) {
                    // A crash handler that throws hides the crash it was recording.
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
        });
    }

    /** Records a failure the app survived (or is about to die from), with the current breadcrumb. */
    public static synchronized void record(String label, Throwable throwable) {
        try {
            writeCrash(label, throwable);
        } catch (Throwable ignored) {
            // Never let the recorder be the thing that breaks the app.
        }
    }

    /** One line per launch stage: what the app was doing, and how much heap it was using. */
    public static synchronized void note(String stage) {
        lastBreadcrumb = stage;
        if (directory == null) {
            return;
        }
        String line = stamp() + "  " + stage + "  " + heapSummary() + "\n";
        try {
            File file = new File(directory, TRACE_FILE);
            if (file.length() > MAX_TRACE_BYTES) {
                trimTrace(file);
            }
            Writer writer = new OutputStreamWriter(new FileOutputStream(file, true),
                    StandardCharsets.UTF_8);
            try {
                writer.write(line);
            } finally {
                writer.close();
            }
        } catch (Throwable ignored) {
            // Breadcrumbs are a diagnostic: losing one must never be an event.
        }
    }

    /** The last breadcrumb written, whether or not it reached the file. */
    public static String lastBreadcrumb() {
        return lastBreadcrumb;
    }

    /** Used memory and the limit this process is allowed, as a short line for the panel. */
    public static String heapSummary() {
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long max = runtime.maxMemory() / (1024L * 1024L);
        return "heap " + used + " of " + max + " MB";
    }

    public static synchronized boolean hasCrash() {
        File file = new File(directory == null ? new File(".") : directory, CRASH_FILE);
        return directory != null && file.isFile() && file.length() > 0;
    }

    /** The crash record, or null when there is none. Never deletes it. */
    public static synchronized String readCrash() {
        return read(directory, CRASH_FILE);
    }

    /** The breadcrumb trail, or null. */
    public static synchronized String readTrace() {
        return read(directory, TRACE_FILE);
    }

    /** Forgets the crash record. Only a person, through the launch dialog, calls this. */
    public static synchronized void clearCrash() {
        if (directory == null) {
            return;
        }
        File file = new File(directory, CRASH_FILE);
        if (file.isFile()) {
            file.delete();
        }
    }

    /**
     * A one-line summary of the crash record, for the diagnostics panel: the first line of the
     * throwable, which is the line that names the failure.
     */
    public static synchronized String summary() {
        String text = readCrash();
        if (text == null || text.isEmpty()) {
            return "";
        }
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("thread ") || trimmed.startsWith("last stage")
                    || trimmed.startsWith("---")) {
                continue;
            }
            return trimmed.length() > 160 ? trimmed.substring(0, 160) + "..." : trimmed;
        }
        return "";
    }

    private static void writeCrash(String label, Throwable throwable) {
        if (directory == null) {
            return;
        }
        StringBuilder text = new StringBuilder(1024);
        text.append("--- ").append(stamp()).append('\n');
        text.append("last stage: ").append(lastBreadcrumb).append('\n');
        text.append(label == null ? "failure" : label).append(": ");
        text.append(throwable == null ? "(no throwable)" : throwable.toString()).append('\n');
        Throwable cause = throwable;
        int depth = 0;
        while (cause != null && depth < 4) {
            if (depth > 0) {
                text.append("caused by ").append(cause).append('\n');
            }
            StackTraceElement[] frames = cause.getStackTrace();
            for (int i = 0; i < frames.length && i < FRAMES; i++) {
                text.append("    at ").append(frames[i]).append('\n');
            }
            if (frames.length > FRAMES) {
                text.append("    ... ").append(frames.length - FRAMES).append(" more\n");
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
            depth++;
        }
        text.append("---\n");
        if (text.length() > MAX_CRASH_CHARS) {
            text.setLength(MAX_CRASH_CHARS);
            text.append("\n... truncated\n");
        }
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(new File(directory, CRASH_FILE)),
                    StandardCharsets.UTF_8);
            writer.write(text.toString());
        } catch (IOException ignored) {
            // Nothing left to do: the process is about to die and this was the last chance to speak.
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // Closing a file we already failed to write is not a new event.
                }
            }
        }
    }

    private static void trimTrace(File file) throws IOException {
        String text = read(directory, TRACE_FILE);
        if (text == null) {
            return;
        }
        String[] lines = text.split("\n");
        List<String> keep = new ArrayList<>();
        for (int i = Math.max(0, lines.length - MAX_TRACE_LINES); i < lines.length; i++) {
            keep.add(lines[i]);
        }
        StringBuilder out = new StringBuilder(text.length() / 2);
        for (String line : keep) {
            out.append(line).append('\n');
        }
        Writer writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8);
        try {
            writer.write(out.toString());
        } finally {
            writer.close();
        }
    }

    private static String read(File dir, String name) {
        if (dir == null) {
            return null;
        }
        File file = new File(dir, name);
        if (!file.isFile()) {
            return null;
        }
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] bytes = new byte[(int) file.length()];
                int read = 0;
                while (read < bytes.length) {
                    int step = in.read(bytes, read, bytes.length - read);
                    if (step < 0) {
                        break;
                    }
                    read += step;
                }
                return new String(bytes, 0, read, StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }

    private static String stamp() {
        return new SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(new Date());
    }
}
