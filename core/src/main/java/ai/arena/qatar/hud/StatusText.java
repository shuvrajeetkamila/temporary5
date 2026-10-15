package ai.arena.qatar.hud;

import java.util.Locale;

/**
 * The status line the HUD shows at the top-left, as text and nothing else.
 *
 * <p>It lives here, out of the frame loop, for one reason: on the sixth install of this game the
 * controls half of every frame threw
 * {@code IllegalFormatConversionException: f != java.lang.Integer}, the world kept drawing behind a
 * diagnostics panel, and the cause was a single specifier in this line -- {@code %.0f fps} asked a
 * fractional format to print the whole number {@code Graphics.getFramesPerSecond()} returns. Java's
 * formatter refuses that, and an exception in the status line is an exception in the controls.
 *
 * <p>Formatting that cannot be reached by a test is formatting that is only tried on a phone. The
 * numbers go in as numbers here, the text comes out as text, and {@code StatusTextTest} holds both the
 * correct specifier and the mistake that made it necessary.
 */
public final class StatusText {

    private StatusText() {
    }

    /**
     * The English status line: what the player is in, where they are, what the world is doing, and the
     * frame rate. {@code fps} is an {@code int} because a frame count is a whole number of frames --
     * libGDX counts them, and the count is what {@code %d} exists for.
     */
    public static String english(String body, double east, double north, String world, int fps) {
        return String.format(Locale.ROOT, "%s | %.0f, %.0f | %s | %d fps", body, east, north, world, fps);
    }
}
