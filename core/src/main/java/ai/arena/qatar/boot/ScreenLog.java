package ai.arena.qatar.boot;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
import java.util.List;

/**
 * A console on the screen.
 *
 * <p>Written because of a real failure: the first build installed on a phone showed a blue screen and
 * nothing else, and the reason -- which stage had failed, what the driver had said about the shaders --
 * was in {@code logcat}, where the person holding the phone could not see it. A build that fails
 * silently on a device is a build nobody can fix.
 *
 * <p>It is deliberately the most robust drawing code in the project: its own {@link SpriteBatch} with
 * libGDX's own default shader, its own font with two fallbacks (the HUD font, then the font compiled
 * into libGDX itself), no game state, and no dependency on anything that might be the thing that is
 * broken. If the HUD font is missing the panel still draws; if a font cannot load at all, the panel
 * still draws, in coloured bars.
 *
 * <p>{@link #GLOBAL} is the one the whole game writes to, so a caller three layers down can say what it
 * is doing without every class taking a logger parameter.
 */
public final class ScreenLog implements Disposable {

    /** The instance the game logs through; created before the GL context exists, initialised after. */
    public static final ScreenLog GLOBAL = new ScreenLog();

    public enum Level {
        INFO,
        WARN,
        ERROR
    }

    private static final class Line {
        final Level level;
        final String text;

        Line(Level level, String text) {
            this.level = level;
            this.text = text;
        }
    }

    private static final Color PANEL = new Color(0.03f, 0.04f, 0.07f, 0.86f);
    private static final Color INFO = new Color(0.86f, 0.90f, 0.95f, 1f);
    private static final Color WARN = new Color(0.98f, 0.80f, 0.35f, 1f);
    private static final Color ERROR = new Color(1.00f, 0.55f, 0.50f, 1f);
    private static final Color EDGE_INFO = new Color(0.35f, 0.55f, 0.75f, 1f);
    private static final Color EDGE_WARN = new Color(0.85f, 0.60f, 0.15f, 1f);
    private static final Color EDGE_ERROR = new Color(0.90f, 0.25f, 0.20f, 1f);

    private final List<Line> lines = new ArrayList<>();
    private final Matrix4 projection = new Matrix4();
    private final GlyphLayout layout = new GlyphLayout();

    private SpriteBatch batch;
    private BitmapFont font;
    private float scale = 1f;
    private boolean ready;
    private boolean fontFallback;
    /** Stays true once an error has been logged: a failure is never hidden again. */
    private boolean hasError;

    ScreenLog() {
    }

    /**
     * Creates the batch and a font. Safe to call once, on the GL thread, as early as possible; safe to
     * call again after a context loss (the batch and font belong to the old context).
     */
    public void init() {
        dispose();
        try {
            batch = new SpriteBatch();
        } catch (Throwable t) {
            batch = null;
            ready = false;
            return;
        }
        font = loadFont();
        if (font != null) {
            font.setUseIntegerPositions(false);
            // A phone screen is small and the person reading this is often looking at a photograph of it:
            // the panel is drawn larger than the HUD font's own size.
            scale = 1.5f;
            font.getData().setScale(scale);
        }
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        ready = font != null;
    }

    private BitmapFont loadFont() {
        // First choice: the same Amiri font the HUD uses, so Arabic in a message is legible too.
        try {
            BitmapFont hud = new BitmapFont(Gdx.files.internal("fonts/hud_small.fnt"));
            return hud;
        } catch (Throwable t) {
            fontFallback = true;
            com.badlogic.gdx.Gdx.app.error("ScreenLog", "the HUD font did not load: " + t);
        }
        // Second: the font libGDX compiles into itself. ASCII only, and always present.
        try {
            return new BitmapFont();
        } catch (Throwable t) {
            // Third: no font at all. The panel is still drawn, so the failure is still visible.
            return null;
        }
    }

    public boolean isReady() {
        return ready;
    }

    public boolean hasError() {
        return hasError;
    }

    /** True when only libGDX's built-in ASCII font is available. */
    public boolean isFontFallback() {
        return fontFallback;
    }

    public void resize(int width, int height) {
        projection.setToOrtho2D(0f, 0f, Math.max(1, width), Math.max(1, height));
    }

    public void log(String message) {
        add(Level.INFO, message);
        echo(false, message);
    }

    public void warn(String message) {
        add(Level.WARN, message);
        echo(false, "WARNING: " + message);
    }

    public void error(String message) {
        add(Level.ERROR, message);
        hasError = true;
        echo(true, "ERROR: " + message);
    }

    /**
     * Echoes to logcat, when there is a logcat. The panel is written to from code that also runs without
     * an application behind it -- the world loader reports which name a bundle arrived under, and the
     * tests load the same bundles through the same code -- so the platform call must never be the thing
     * that throws.
     */
    private static void echo(boolean asError, String message) {
        if (Gdx.app == null) {
            return;
        }
        if (asError) {
            Gdx.app.error("Qatar", message);
        } else {
            Gdx.app.log("Qatar", message);
        }
    }

    /**
     * Records a throwable, with as much of it as a person reading a phone screen can use.
     *
     * <p>Two lines, not twelve. An earlier version pushed the whole stack into the panel -- up to eight
     * frames, plus a cause and four more -- and on the third install that turned the screen into a wall
     * of text in which the one sentence that mattered was in the middle, under a boot log. The frames are
     * not lost: {@link CrashJournal} has them, and the next launch shows them in a dialog that scrolls and
     * can be copied, which is what the second line says.
     */
    public void error(String message, Throwable cause) {
        error(message + ": " + cause);
        if (cause == null) {
            return;
        }
        Throwable deeper = cause.getCause();
        if (deeper != null && deeper != cause) {
            add(Level.ERROR, "  caused by " + deeper);
        }
        StackTraceElement[] trace = cause.getStackTrace();
        if (trace.length > 0) {
            add(Level.ERROR, "  at " + trace[0]
                    + (trace.length > 1 ? "  (+" + (trace.length - 1) + " frames in the record)" : ""));
        }
    }

    /** Adds a line that is already the message, without echoing it to the platform log again. */
    public void add(Level level, String message) {
        if (message == null) {
            return;
        }
        lines.add(new Line(level, message));
        if (level == Level.ERROR) {
            hasError = true;
        }
        // The panel is a console, not a log file: what has just happened is what a person can read
        // before the next frame paints over it. History lives in the journal.
        while (lines.size() > 40) {
            lines.remove(0);
        }
    }

    public List<String> text() {
        List<String> out = new ArrayList<>(lines.size());
        for (Line line : lines) {
            out.add(line.text);
        }
        return out;
    }

    /**
     * Draws the console: a dark panel across the top of the screen, oldest line first, always ending
     * with the most recent one -- because the most recent line is the one that says what just happened.
     *
     * <p>Defensive by construction, and now actually so. This is the code that has to work when
     * something else does not, so every failure inside it is caught and the panel falls back to
     * coloured bars: a console that throws while reporting a problem turns a diagnosable run into a
     * silent one, which is exactly the bug this whole file exists to prevent. The bars-only path used
     * to end with an unguarded {@code font.setColor(...)} and take the whole app down with a
     * NullPointerException when no font could be loaded at all.
     */
    public void render() {
        if (lines.isEmpty() || batch == null) {
            // Nothing to say: draw nothing. A running game is not covered by an empty panel.
            return;
        }
        try {
            draw();
        } catch (Throwable t) {
            // Whatever went wrong, the next frame draws bars instead of text -- see the flag below.
            font = null;
            ready = false;
            try {
                batch.end();
            } catch (Throwable ignored) {
                // A batch that cannot be closed is a batch that was never opened.
            }
            echo(true, "the diagnostics panel failed to draw and fell back to bars: " + t);
        }
    }

    private void draw() {
        float width = Gdx.graphics.getWidth();
        float height = Gdx.graphics.getHeight();

        batch.setProjectionMatrix(projection);
        batch.begin();

        // Wrap first, so the panel can be exactly as tall as it needs to be.
        List<Line> wrapped = new ArrayList<>();
        float lineHeight = ready ? font.getLineHeight() : 26f;
        for (Line line : lines) {
            if (!ready) {
                wrapped.add(new Line(line.level, ""));
                continue;
            }
            for (String piece : wrap(line.text, width - 32f)) {
                wrapped.add(new Line(line.level, piece));
            }
        }
        int capacity = Math.max(4, Math.min(12, (int) ((height * 0.72f) / lineHeight)));
        int from = Math.max(0, wrapped.size() - capacity);
        float panelHeight = Math.min(height * 0.78f, (wrapped.size() - from) * lineHeight + 18f);

        batch.setColor(PANEL);
        batch.draw(white(), 0f, height - panelHeight, width, panelHeight);
        batch.setColor(Color.WHITE);

        float y = height - 12f;
        for (int i = from; i < wrapped.size(); i++) {
            Line line = wrapped.get(i);
            y -= lineHeight;
            if (!ready) {
                // No font at all: one coloured bar per line still says "the app is alive and reporting".
                batch.setColor(edge(line.level));
                batch.draw(white(), 8f, y + lineHeight * 0.25f, width - 16f, lineHeight * 0.4f);
                continue;
            }
            if (font == null) {
                continue;
            }
            font.setColor(colour(line.level));
            float markerWidth = 0f;
            if (line.level != Level.INFO) {
                String marker = line.level == Level.ERROR ? "X " : "! ";
                font.draw(batch, marker, 12f, y);
                layout.setText(font, marker);
                markerWidth = layout.width;
            }
            font.draw(batch, line.text, 12f + markerWidth, y);
            batch.setColor(edge(line.level));
            batch.draw(white(), 4f, y - lineHeight * 0.15f, 4f, lineHeight * 0.85f);
        }
        if (font != null) {
            font.setColor(Color.WHITE);
        }
        batch.setColor(Color.WHITE);
        batch.end();
    }

    private Color colour(Level level) {
        switch (level) {
            case ERROR:
                return ERROR;
            case WARN:
                return WARN;
            default:
                return INFO;
        }
    }

    private Color edge(Level level) {
        switch (level) {
            case ERROR:
                return EDGE_ERROR;
            case WARN:
                return EDGE_WARN;
            default:
                return EDGE_INFO;
        }
    }

    private com.badlogic.gdx.graphics.Texture white;
    private com.badlogic.gdx.graphics.g2d.TextureRegion whiteRegion;

    private com.badlogic.gdx.graphics.g2d.TextureRegion white() {
        if (white == null) {
            com.badlogic.gdx.graphics.Pixmap pixmap =
                    new com.badlogic.gdx.graphics.Pixmap(1, 1, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
            pixmap.setColor(Color.WHITE);
            pixmap.fill();
            white = new com.badlogic.gdx.graphics.Texture(pixmap);
            pixmap.dispose();
            whiteRegion = new com.badlogic.gdx.graphics.g2d.TextureRegion(white);
        }
        return whiteRegion;
    }

    /** Greedy wrap by word, breaking a word that is longer than the line rather than losing it. */
    private List<String> wrap(String text, float maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (measure(candidate) <= maxWidth || line.length() == 0) {
                if (measure(candidate) <= maxWidth) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                // A single word too wide for the line: cut it up.
                StringBuilder piece = new StringBuilder();
                for (int i = 0; i < word.length(); i++) {
                    piece.append(word.charAt(i));
                    if (measure(piece.toString()) > maxWidth) {
                        piece.setLength(piece.length() - 1);
                        out.add(piece.toString());
                        piece.setLength(0);
                        piece.append(word.charAt(i));
                    }
                }
                line.setLength(0);
                line.append(piece);
                continue;
            }
            out.add(line.toString());
            line.setLength(0);
            line.append(word);
        }
        out.add(line.toString());
        return out;
    }

    private float measure(String text) {
        layout.setText(font, text);
        return layout.width;
    }

    /** Hides the console: called once the game is running and there is nothing to report. */
    public void clear() {
        lines.clear();
        hasError = false;
    }

    public void dispose() {
        if (batch != null) {
            batch.dispose();
            batch = null;
        }
        if (font != null) {
            font.dispose();
            font = null;
        }
        if (white != null) {
            white.dispose();
            white = null;
            whiteRegion = null;
        }
        ready = false;
    }

    /** Clears the framebuffer to one flat colour: the last resort that is never a blank blue. */
    public static void clearTo(float r, float g, float b) {
        Gdx.gl.glClearColor(r, g, b, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
    }
}
