package ai.arena.qatar.hud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.IllegalFormatConversionException;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status line is text, and this is the test that would have saved the sixth install.
 *
 * <p>On that phone the whole controls half of every frame threw
 * {@code IllegalFormatConversionException: f != java.lang.Integer} -- {@code %.0f fps} against the
 * {@code int} that libGDX returns -- and the game kept drawing the world behind a panel that said
 * "Failed the controls". The line itself is now a pure function, which is the only reason a test can
 * reach it: inside a frame loop, on a GL thread, nothing here could run.
 */
final class StatusTextTest {

    @Test
    @DisplayName("a whole-number frame rate prints, which is the exact thing that failed on the phone")
    void wholeNumberFramesPerSecond() {
        String line = StatusText.english("HUMAN 3.0 m/s", 553000.0, 2796000.0, "chunks 12", 60);
        assertEquals("HUMAN 3.0 m/s | 553000, 2796000 | chunks 12 | 60 fps", line);
    }

    @Test
    @DisplayName("the frame rate is a count: zero frames and four figures both print as themselves")
    void frameRateIsACount() {
        assertTrue(StatusText.english("HUMAN", 0.0, 0.0, "nothing yet", 0).endsWith(" | 0 fps"));
        assertTrue(StatusText.english("HUMAN", 0.0, 0.0, "nothing yet", 144).endsWith(" | 144 fps"));
    }

    @Test
    @DisplayName("metres are shown to the metre, so a position reads the same on any device")
    void positionsAreMetresRounded() {
        // Half a metre is not shown, and the rounding is Java's own rather than the phone's language.
        assertTrue(StatusText.english("CAR", 553000.4, 2796000.6, "w", 30).contains(" | 553000, 2796001 | "));
        // Negative coordinates -- west of the grid origin -- print as they are.
        assertTrue(StatusText.english("CAR", -12.0, -3.0, "w", 30).contains(" | -12, -3 | "));
        // Long lines are the caller's business: the world description goes in whole and untouched.
        String line = StatusText.english("body", 1.0, 2.0, "chunks 320 resident / 3 pending", 60);
        assertTrue(line.contains("chunks 320 resident / 3 pending"));
    }

    @Test
    @DisplayName("the panel wraps to the width of a phone instead of running off the screen")
    void thePanelWraps() {
        // The real thing: the line this build draws, which is around two hundred characters and was
        // being drawn off the right-hand edge of every phone it has been installed on since the numbers
        // started growing. Every character has to survive the wrap, in order.
        String world = "chunks 210 resident / 3 pending, 40 drawn + 200 culled, 12 draws, 90k tris, "
                + "300k verts of 900k, 16.2 ms, fog 6.0 km, 3 rebases, horizon 12k tris, "
                + "city 2600 blocks, 26k tris";
        List<String> lines = StatusText.wrap(world, StatusText.PANEL_CHARS);
        assertTrue(lines.size() >= 3, "a two-hundred-character panel is not one line: " + lines);
        for (String line : lines) {
            assertTrue(line.length() <= StatusText.PANEL_CHARS,
                    "a line the HUD would draw off the screen: " + line.length() + " chars: " + line);
        }
        assertEquals(world, String.join("", lines), "the wrap lost or reordered the panel");
        assertTrue(lines.get(lines.size() - 1).contains("26k tris"),
                "the last measurement added is the one at the end of the line, and it has to be readable: "
                        + lines);
    }

    @Test
    @DisplayName("a line with nothing to break on is split anyway, never dropped")
    void anUnbreakableTokenIsSplit() {
        // No separators at all: the wrap has no choice, and a piece that ran off the screen is the fault
        // it exists to prevent. Nothing is lost either way.
        List<String> lines = StatusText.wrap("0123456789abcdefghij", 8);
        assertEquals("0123456789abcdefghij", String.join("", lines));
        for (String line : lines) {
            assertTrue(line.length() <= 8, "too long: " + line);
        }
    }

    @Test
    @DisplayName("the player's own line comes first and stays whole")
    void theBodyLineIsFirst() {
        List<String> lines = StatusText.englishLines("HUMAN 3.0 m/s", 553000.0, 2796000.0,
                "chunks 210 resident / 3 pending, 40 drawn + 200 culled, 12 draws, 90k tris", 58);
        assertEquals("HUMAN 3.0 m/s | 553000, 2796000 | 58 fps", lines.get(0));
        assertTrue(lines.size() >= 2, "the world's report follows it: " + lines);
        for (int i = 1; i < lines.size(); i++) {
            assertTrue(lines.get(i).length() <= StatusText.PANEL_CHARS, lines.get(i));
        }
        assertEquals(String.join("", lines.subList(1, lines.size())),
                "chunks 210 resident / 3 pending, 40 drawn + 200 culled, 12 draws, 90k tris",
                "the world's half of the panel is carried whole");
    }

    @Test
    @DisplayName("an empty report is no lines at all, not an empty line")
    void anEmptyReportDrawsNothing() {
        assertTrue(StatusText.wrap("", StatusText.PANEL_CHARS).isEmpty());
        assertEquals(1, StatusText.englishLines("CAR", 0.0, 0.0, "", 60).size());
    }

    @Test
    @DisplayName("the mistake itself: %.0f cannot print an Integer, and it throws rather than lying")
    void theOldSpecifierThrows() {
        // Documented here so that the reason for `%d` is a test rather than a comment: a frame count is
        // an int, an int is not a Float or a Double, and the formatter says so by throwing rather than
        // printing an approximation of it.
        IllegalFormatConversionException refusal = assertThrows(IllegalFormatConversionException.class,
                // The line below is wrong on purpose: an int under a fractional specifier, which is the
                // sixth install in one line. The marker tells the format gate it is deliberate.
                // format-gate: intentional
                () -> String.format(Locale.ROOT, "%s | %.0f fps", "HUMAN", 60));
        assertEquals('f', refusal.getConversion());
        // `getArgumentClass`, not `getParameterClass`: the JDK's own source says so, and the compiler
        // said so first -- which is the kind of thing a test that no build can run offline costs a
        // whole CI cycle to learn.
        assertEquals(Integer.class, refusal.getArgumentClass());
        assertTrue(refusal.getMessage().contains("f != java.lang.Integer"),
                "the message the phone showed, and it is the platform's own words: " + refusal.getMessage());
    }
}
