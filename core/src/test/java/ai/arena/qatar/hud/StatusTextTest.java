package ai.arena.qatar.hud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.IllegalFormatConversionException;
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
        assertEquals(Integer.class, refusal.getParameterClass());
        assertTrue(refusal.getMessage().contains("f != java.lang.Integer"),
                "the message the phone showed, and it is the platform's own words: " + refusal.getMessage());
    }
}
