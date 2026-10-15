package ai.arena.qatar.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dialog's frames, held to what the sixth install taught: a report that stops before the game's own
 * code is a report of nothing.
 *
 * <p>These are the real frames the phone photographed, in the real order -- six lines of
 * {@code java.util.Formatter} and then the game's own, which the dialog never reached. {@code String.format}
 * reaches {@code GameScreen.drawStatusText}, so the trace below really is a trace this codebase can
 * produce; nothing here needs a device to be true.
 */
final class StackTraceTextTest {

    private static final StackTraceElement FORMAT_SPECIFIER_FAIL =
            new StackTraceElement("java.util.Formatter$FormatSpecifier", "failConversion",
                    "Formatter.java", 4606);
    private static final StackTraceElement FORMAT_SPECIFIER_PRINT =
            new StackTraceElement("java.util.Formatter$FormatSpecifier", "printFloat",
                    "Formatter.java", 3134);
    private static final StackTraceElement FORMATTER_FORMAT =
            new StackTraceElement("java.util.Formatter", "format", "Formatter.java", 2702);
    private static final StackTraceElement STRING_FORMAT =
            new StackTraceElement("java.lang.String", "format", "String.java", 4755);
    private static final StackTraceElement DRAW_STATUS =
            new StackTraceElement("ai.arena.qatar.game.GameScreen", "drawStatusText",
                    "GameScreen.java", 399);
    private static final StackTraceElement RENDER = new StackTraceElement("ai.arena.qatar.game.GameScreen",
            "renderFrame", "GameScreen.java", 307);
    private static final StackTraceElement GDX_RENDER = new StackTraceElement("com.badlogic.gdx.Graphics",
            "render", "Graphics.java", 100);
    private static final StackTraceElement GL_DRAW = new StackTraceElement("com.badlogic.gdx.graphics.glutils",
            "GL20", "GL20.java", 42);

    /** What the phone showed: the JDK's frames first, the game's own below them. */
    private static StackTraceElement[] formatterThrow() {
        return new StackTraceElement[]{FORMAT_SPECIFIER_FAIL, FORMAT_SPECIFIER_PRINT, FORMATTER_FORMAT,
                STRING_FORMAT, DRAW_STATUS, RENDER, GDX_RENDER};
    }

    @Test
    @DisplayName("six JDK frames are no longer the whole report: the game's own frame comes first")
    void appFrameComesFirst() {
        List<StackTraceElement> shown = StackTraceText.useful(formatterThrow(), StackTraceText.ALERT_FRAMES);
        // Our frames are the report when we have any: which method, which line, in the game's own words.
        // The library frame that called into us, and the JDK's below that, add nothing a reader needs.
        assertEquals(2, shown.size(), "the game's two frames, and none of the JDK's: " + shown);
        assertEquals(DRAW_STATUS, shown.get(0), "the line that asked for the wrong thing, first");
        assertEquals(RENDER, shown.get(1));
        assertFalse(shown.contains(FORMAT_SPECIFIER_PRINT), "no Formatter frame is worth a phone's screen");
        assertFalse(shown.contains(GDX_RENDER), "nor a libGDX frame, while ours are there to read");
    }

    @Test
    @DisplayName("the single frame the panel has room for is the app's, not the throw site's")
    void firstUsefulSkipsTheJdk() {
        assertEquals(DRAW_STATUS, StackTraceText.firstUseful(formatterThrow()));
        // The old behaviour, for the record: the throw site, which is the JDK's own machinery.
        assertEquals(FORMAT_SPECIFIER_FAIL, formatterThrow()[0]);
    }

    @Test
    @DisplayName("a throw entirely inside the framework still reports the framework, not a blank line")
    void libraryOnlyTraceFallsBackToTheLibrary() {
        StackTraceElement[] libgdxOnly = {GL_DRAW, GDX_RENDER};
        List<StackTraceElement> shown = StackTraceText.useful(libgdxOnly, StackTraceText.ALERT_FRAMES);
        assertEquals(2, shown.size(), "the library frames are the useful ones when we are not in the throw");
        assertEquals(GL_DRAW, shown.get(0));
    }

    @Test
    @DisplayName("a JDK-only trace is still handed back rather than swallowed")
    void platformOnlyTraceIsStillShown() {
        StackTraceElement[] jdkOnly = {FORMAT_SPECIFIER_FAIL, FORMATTER_FORMAT, STRING_FORMAT};
        List<StackTraceElement> shown = StackTraceText.useful(jdkOnly, StackTraceText.ALERT_FRAMES);
        assertEquals(3, shown.size(), "something is better than an empty trace");
        assertEquals(FORMAT_SPECIFIER_FAIL, shown.get(0));
    }

    @Test
    @DisplayName("the limit is honoured, and an empty or absent trace is not an error")
    void limitsAndEmpties() {
        StackTraceElement[] longTrace = {DRAW_STATUS, RENDER, DRAW_STATUS, RENDER, DRAW_STATUS, RENDER,
                DRAW_STATUS, RENDER, DRAW_STATUS};
        assertEquals(2, StackTraceText.useful(longTrace, 2).size(), "two means two");
        assertEquals(9, StackTraceText.useful(longTrace, 100).size(), "all of them when there is room");
        assertTrue(StackTraceText.useful(new StackTraceElement[0], 6).isEmpty(), "an empty trace");
        assertTrue(StackTraceText.useful(null, 6).isEmpty(), "and no trace at all");
        assertTrue(StackTraceText.useful(longTrace, 0).isEmpty(), "and no room");
        assertNotNull(StackTraceText.firstUseful(longTrace), "a real trace has a first frame");
    }

    @Test
    @DisplayName("our own frames are the ones this recognises, and nothing else is claimed to be ours")
    void isAppIsAboutOurPackage() {
        assertTrue(StackTraceText.isApp(DRAW_STATUS));
        assertFalse(StackTraceText.isApp(GDX_RENDER));
        assertFalse(StackTraceText.isApp(FORMATTER_FORMAT));
        assertFalse(StackTraceText.isApp(null));
        // A near miss: a package that starts the same way is not the same package.
        assertFalse(StackTraceText.isApp(new StackTraceElement("ai.arena.qatarp", "x", "X.java", 1)));
    }
}
