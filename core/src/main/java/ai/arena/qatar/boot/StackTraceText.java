package ai.arena.qatar.boot;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the frames of a stack trace that are worth reading on a phone.
 *
 * <p>A stack trace is deepest-first, and the deepest frames belong to whoever threw: on the sixth
 * install the dialog's six lines were six lines of {@code java.util.Formatter}, and the sentence that
 * mattered -- {@code GameScreen.drawStatusText}, the line that asked a fractional format to print an
 * integer -- was below them, off the end of the list. The exception was reported perfectly and the
 * cause was invisible, which is the same as not reporting it.
 *
 * <p>So the app's own frames come first, whatever the exception is. If the throw happened entirely
 * inside a library -- a GL error raised by libGDX is the usual one -- there is no app frame to show,
 * and the library frames are then the useful ones. Only if there is neither does this hand back what
 * the JVM produced, because a trace is still better than nothing.
 */
public final class StackTraceText {

    /** How many frames the dialog has room for. Six is the number that fits above the fold. */
    public static final int ALERT_FRAMES = 6;

    private StackTraceText() {
    }

    /**
     * Up to {@code limit} frames to print, app first. Never null, and never longer than the input.
     */
    public static List<StackTraceElement> useful(StackTraceElement[] frames, int limit) {
        List<StackTraceElement> picked = new ArrayList<>(Math.min(limit, frames == null ? 0 : frames.length));
        if (frames == null || limit <= 0) {
            return picked;
        }
        // 1. Ours. The frame that says which method of the game asked for the wrong thing.
        for (StackTraceElement frame : frames) {
            if (picked.size() >= limit) {
                break;
            }
            if (isApp(frame)) {
                picked.add(frame);
            }
        }
        // 2. The framework's and the platform's, when the throw never crossed our code on the way out.
        if (picked.isEmpty()) {
            for (StackTraceElement frame : frames) {
                if (picked.size() >= limit) {
                    break;
                }
                if (!isCorePlatform(frame)) {
                    picked.add(frame);
                }
            }
        }
        // 3. Anything at all, rather than a blank line where the trace goes.
        if (picked.isEmpty()) {
            for (int i = 0; i < frames.length && picked.size() < limit; i++) {
                picked.add(frames[i]);
            }
        }
        return picked;
    }

    /** The single most useful frame, or null when the trace is empty. */
    public static StackTraceElement firstUseful(StackTraceElement[] frames) {
        List<StackTraceElement> picked = useful(frames, 1);
        return picked.isEmpty() ? null : picked.get(0);
    }

    /** Whether a frame is one of this game's, which is the whole point of this class. */
    public static boolean isApp(StackTraceElement frame) {
        return frame != null && frame.getClassName().startsWith("ai.arena.qatar");
    }

    /**
     * The JDK's own frames: the machinery the exception came out of. They are what filled the dialog,
     * and they are the last thing a person needs to see.
     */
    private static boolean isCorePlatform(StackTraceElement frame) {
        String name = frame.getClassName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("com.sun.");
    }
}
