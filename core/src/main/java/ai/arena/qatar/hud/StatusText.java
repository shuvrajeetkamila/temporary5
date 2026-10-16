package ai.arena.qatar.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 *
 * <h2>Why the line is now several lines</h2>
 *
 * <p>Because it stopped fitting. The panel grew a number per round -- chunks resident, chunks drawn,
 * draw calls, triangles, milliseconds, the fog, the origin rebases, the horizon, and now the distant
 * city -- until the one-line status text was around two hundred characters, and the HUD draws text at a
 * fixed size that scales with the screen. The line was being drawn straight off the right edge of the
 * screen, which means the numbers added in the last four rounds were never actually readable on a phone:
 * the player was being asked to report a panel whose right-hand half they could not see.
 *
 * <p>The arithmetic is worth writing down, because it is what the wrap width comes from. The HUD font is
 * authored at 18 px and scaled by {@code unit / 720} where {@code unit} is the shorter screen side, so
 * on a 1080-wide phone the scale is 1.275 and on a 720-wide phone it is 0.85: the number of characters
 * that fit across the screen is about the same on both, a little over eighty. {@link #PANEL_CHARS} is
 * under that, and the caller stacks the lines down the screen.
 */
public final class StatusText {

    /**
     * How many characters of the panel fit across a phone: see the class comment for the arithmetic.
     *
     * <p>A little under the eighty-odd that fit at the HUD's own scale, because the glyphs are
     * proportional -- a line of {@code m} and {@code w} is wider than a line of {@code i} and {@code .}
     * -- and a panel that is sometimes cut off is worse than one that wraps a word early.
     */
    public static final int PANEL_CHARS = 78;

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

    /**
     * The same status, as the lines the HUD should draw: the player's own line first -- what they are in,
     * where they are, how fast it is going -- and then the world's report, wrapped.
     *
     * <p>The world's part is what got long: it is a list of measurements, and the ones at the end of it
     * are the ones a report needs. Wrapping keeps all of them on the screen; the player's own line stays
     * whole and first, because it is the one they can read out loud without knowing what any of it does.
     */
    public static List<String> englishLines(String body, double east, double north, String world, int fps) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "%s | %.0f, %.0f | %d fps", body, east, north, fps));
        lines.addAll(wrap(world, PANEL_CHARS));
        return lines;
    }

    /**
     * Breaks text into pieces no longer than {@code maxChars}, at the separators between measurements.
     *
     * <p>Every character of the input ends up in exactly one piece, separators included, so the pieces
     * concatenate back to the text they came from: a panel that silently dropped the end of its own last
     * measurement would be the same bug as a panel drawn off the screen, with better manners. A single
     * word longer than the whole line is split anyway -- an unbreakable token is a line that runs off the
     * screen, and there is no such thing as a word worth that.
     */
    public static List<String> wrap(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        int limit = Math.max(8, maxChars);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String token : splitKeepingSeparators(text, ", ")) {
            // The token itself is longer than a whole line: it goes on its own line, in as many pieces as
            // it needs, and the packing below carries on from there.
            if (line.length() > 0 && line.length() + token.length() > limit) {
                lines.add(line.toString());
                line.setLength(0);
            }
            while (token.length() > limit) {
                lines.add(token.substring(0, limit));
                token = token.substring(limit);
            }
            line.append(token);
            if (line.length() >= limit) {
                lines.add(line.toString());
                line.setLength(0);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * The text's pieces, each keeping the separator that followed it, so that concatenating the result
     * gives the input back exactly.
     */
    private static List<String> splitKeepingSeparators(String text, String separator) {
        List<String> parts = new ArrayList<>();
        int at = 0;
        while (at < text.length()) {
            int next = text.indexOf(separator, at);
            if (next < 0) {
                parts.add(text.substring(at));
                break;
            }
            parts.add(text.substring(at, next + separator.length()));
            at = next + separator.length();
        }
        return parts;
    }
}
