package ai.arena.qatar.hud;

import ai.arena.qatar.hud.HudLayout.Button;
import ai.arena.qatar.render.GlState2D;
import ai.arena.qatar.hud.HudLayout.Rect;
import ai.arena.qatar.text.ArabicReshaper;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;

/**
 * Draws the controls, the minimap and the full map.
 *
 * <p>Everything the player touches is drawn as a filled rectangle with a label: no skin files, no atlas
 * to keep in sync with the layout, and the same code works at any resolution because the layout hands
 * over pixel rectangles it has already made sensible for the screen it is on.
 *
 * <p>The map is the one picture in the game. {@link MapRaster} paints the country once; the minimap and
 * the full map are two crops of that single texture, so they can never disagree about where a road is.
 */
public final class HudRenderer implements Disposable {

    private static final Color PANEL = new Color(0.06f, 0.08f, 0.10f, 0.72f);
    private static final Color PANEL_ON = new Color(0.18f, 0.52f, 0.34f, 0.92f);
    private static final Color PANEL_DISABLED = new Color(0.10f, 0.11f, 0.12f, 0.45f);
    private static final Color EDGE = new Color(0.94f, 0.96f, 1.0f, 0.55f);
    private static final Color TEXT = new Color(0.96f, 0.97f, 1.0f, 1f);
    private static final Color TEXT_DIM = new Color(0.72f, 0.76f, 0.80f, 1f);
    private static final Color MARKER = new Color(1f, 0.23f, 0.19f, 1f);
    /**
     * The stick's own translucent greys. They were built with {@code new Color(...)} at the point of use,
     * which is three objects per stick per frame -- six a frame with the flying stick up as well, which is
     * 360 short-lived objects a second of pure garbage on a phone that is already warm.
     */
    private static final Color STICK_DISC = new Color(0.95f, 0.97f, 1.0f, 0.14f);
    private static final Color STICK_EDGE = new Color(0.95f, 0.97f, 1.0f, 0.28f);
    private static final Color STICK_KNOB = new Color(0.98f, 0.99f, 1.0f, 0.55f);
    private static final Color STICK_HOME_DISC = new Color(0.95f, 0.97f, 1.0f, 0.10f);
    private static final Color STICK_HOME_EDGE = new Color(0.95f, 0.97f, 1.0f, 0.26f);
    private static final Color STICK_HOME_KNOB = new Color(0.98f, 0.99f, 1.0f, 0.42f);
    private static final Color CROSSHAIR = new Color(1f, 0.85f, 0.35f, 0.9f);

    private final SpriteBatch batch = new SpriteBatch();
    private final Matrix4 projection = new Matrix4();
    private final GlyphLayout layout = new GlyphLayout();

    private BitmapFont small;
    private BitmapFont large;
    private boolean fontsReady;
    private Texture white;
    private final TextureRegion pixel = new TextureRegion();
    private Texture mapTexture;
    private MapRaster raster;
    private final TextureRegion mapRegion = new TextureRegion();
    private final TextureRegion crop = new TextureRegion();
    /** One warning per launch, not one per frame: see drawBigMap. */
    private boolean warnedNoMapTexture;
    private float scale = 1f;

    /** Loads fonts and uploads the map. Safe to call more than once; failures degrade, they do not crash. */
    public void init(MapRaster raster) {
        white = solidTexture(0xFFFFFFFF);
        pixel.setRegion(white);
        this.raster = raster;
        if (raster != null) {
            mapTexture = uploadRaster(raster);
            mapRegion.setRegion(mapTexture);
            // The full map is the same texture with a different rectangle, and a TextureRegion is a
            // texture *and* a rectangle. Setting only the rectangle leaves getTexture() null, and
            // SpriteBatch.draw then calls texture.getWidth() on it -- the eighth install's
            // "Failed the controls", NullPointerException in drawBigMap, on every frame the map was
            // open. The texture is given once, here, where the texture is made.
            crop.setRegion(mapTexture);
        }
        try {
            small = new BitmapFont(Gdx.files.internal("fonts/hud_small.fnt"));
            large = new BitmapFont(Gdx.files.internal("fonts/hud_large.fnt"));
            small.setUseIntegerPositions(false);
            large.setUseIntegerPositions(false);
            fontsReady = true;
            Gdx.app.log("HudRenderer", "HUD fonts loaded: small + large");
        } catch (Throwable e) {
            // A missing font must not end the game: the world still draws, labels are just absent. Say
            // what was thrown though -- a NullPointerException has no message, and "labels disabled: null"
            // is exactly the kind of log line that costs a day.
            fontsReady = false;
            Gdx.app.error("HudRenderer", "HUD fonts unavailable, labels disabled: " + e);
            ai.arena.qatar.boot.ScreenLog.GLOBAL.warn("HUD fonts unavailable: " + e
                    + " (labels are off; the world still draws)");
        }
    }

    public void resize(float width, float height) {
        projection.setToOrtho2D(0f, 0f, width, height);
        // Text is authored for a ~720 px tall screen; keep it proportional on phones and tablets.
        float unit = Math.min(width, height);
        scale = Math.max(0.7f, Math.min(2.4f, unit / 720f)) * 0.85f;
        if (fontsReady) {
            small.getData().setScale(scale);
            large.getData().setScale(scale * 1.25f);
        }
    }

    public void begin() {
        // The state first, and from here rather than from the caller: the world renderer leaves
        // GL_CULL_FACE and GL_DEPTH_TEST enabled, SpriteBatch.begin() does not turn either of them off,
        // and a culled HUD is an HUD that draws nothing at all. See GlState2D for the whole story --
        // it is the seventh install's "no controls", in one sentence.
        GlState2D.beginUi();
        batch.setProjectionMatrix(projection);
        batch.begin();
    }

    public void end() {
        batch.end();
    }

    /**
     * Releases the HUD, and the GL objects with it.
     *
     * <p>Guarded because {@link #dispose()} is reached from two places on two different threads: the
     * game screen being torn down (GL context alive) and the launcher giving up on the world (context
     * gone, or never created). On Android the second one closed the app from the *next* frame onwards:
     * {@code batch.dispose()} ends the batch, {@code SpriteBatch.end()} calls {@code glDepthMask(...)}
     * with no current context, libGDX's {@code Gdx.gl} is null, and the app died in the code that was
     * supposed to clean up.
     *
     * <p>The GL objects are leaked instead when there is no context -- deliberately. The process is
     * finished with them, the driver reclaims everything when the context dies with the process, and a
     * leak is indistinguishable from correct code next to a crash at exit.
     */
    public void dispose(boolean glAvailable) {
        if (glAvailable) {
            dispose();
        } else {
            Gdx.app.log("HudRenderer", "no GL context: leaving the HUD's GL objects to the driver");
        }
    }

    /** Dims the world behind the full map so the map is readable. */
    public void drawScrim(float width, float height) {
        batch.setColor(0.02f, 0.03f, 0.05f, 0.82f);
        batch.draw(white, 0, 0, width, height);
        batch.setColor(Color.WHITE);
    }

    public void drawButton(Button button, boolean highlighted) {
        Color fill = !button.enabled ? PANEL_DISABLED : (highlighted ? PANEL_ON : PANEL);
        batch.setColor(fill);
        batch.draw(white, button.x, button.y, button.w, button.h);
        batch.setColor(EDGE);
        float edge = Math.max(1.5f, button.h * 0.03f);
        batch.draw(white, button.x, button.y, button.w, edge);
        batch.draw(white, button.x, button.y + button.h - edge, button.w, edge);
        batch.draw(white, button.x, button.y, edge, button.h);
        batch.draw(white, button.x + button.w - edge, button.y, edge, button.h);
        batch.setColor(Color.WHITE);
        if (!fontsReady) {
            return;
        }
        // The English label, and the Arabic one underneath it in the smaller style: the game is
        // bilingual everywhere the player reads something, not only in menus.
        drawCentred(small, button.label, button.centreX(),
                button.y + button.h * (button.labelAr == null ? 0.62f : 0.70f), TEXT);
        if (button.labelAr != null && !button.labelAr.isEmpty()) {
            float arabicScale = 0.62f;
            small.getData().setScale(scale * arabicScale);
            drawCentred(small, ArabicReshaper.shapeForDisplay(button.labelAr), button.centreX(),
                    button.y + button.h * 0.26f, TEXT_DIM);
            small.getData().setScale(scale);
        }
    }

    /** Draws the small map in the corner: the whole country, the player, and where they are looking. */
    public void drawMinimap(Rect box, double playerEast, double playerNorth, double yawDegrees,
                            float detail) {
        if (mapTexture == null || box.w <= 0) {
            return;
        }
        batch.setColor(Color.WHITE);
        batch.draw(mapRegion, box.x, box.y, box.w, box.h);
        batch.setColor(EDGE);
        float edge = Math.max(1.5f, box.w * 0.012f);
        batch.draw(white, box.x, box.y, box.w, edge);
        batch.draw(white, box.x, box.y + box.h - edge, box.w, edge);
        batch.draw(white, box.x, box.y, edge, box.h);
        batch.draw(white, box.x + box.w - edge, box.y, edge, box.h);

        float px = box.x + (float) raster.u(playerEast, playerNorth) * box.w;
        // Raster row 0 is the south edge, so north maps straight onto the box from the bottom up.
        float py = box.y + (float) raster.v(playerEast, playerNorth) * box.h;
        batch.setColor(MARKER);
        float dot = Math.max(3f, box.w * 0.022f);
        batch.draw(white, px - dot * 0.5f, py - dot * 0.5f, dot, dot);
        // A short line for the heading: enough to orient, small enough not to cover the streets.
        float radians = (float) Math.toRadians(yawDegrees);
        float length = box.w * 0.10f;
        float tipX = px + (float) Math.sin(radians) * length;
        float tipY = py + (float) Math.cos(radians) * length;
        drawLine(px, py, tipX, tipY, Math.max(1.5f, box.w * 0.008f), MARKER);
        if (fontsReady && detail > 0f) {
            small.getData().setScale(scale * 0.7f);
            drawLeft(small, "TAP TO OPEN MAP", box.x, box.y + box.h + small.getLineHeight() * 0.9f,
                    TEXT_DIM);
            small.getData().setScale(scale);
        }
        batch.setColor(Color.WHITE);
    }

    /** Draws the full map, cropped and scaled by the view, with a marker for the player. */
    public void drawBigMap(MapView view, Rect area, double playerEast, double playerNorth) {
        if (mapTexture == null || crop.getTexture() == null || area.w <= 0 || area.h <= 0) {
            // A map that cannot be drawn is a bug, but an exception here is a game that cannot be
            // played: this is the path that closed the app on the eighth install. Say it once, on the
            // panel and in the log, and let the rest of the frame draw.
            if (!warnedNoMapTexture) {
                warnedNoMapTexture = true;
                Gdx.app.error("HudRenderer", "the big map has no texture (mapTexture="
                        + (mapTexture == null ? "null" : "present") + ", crop="
                        + (crop.getTexture() == null ? "untextured" : "textured")
                        + "); drawing nothing rather than throwing");
                ai.arena.qatar.boot.ScreenLog.GLOBAL.warn("the big map has no texture: the map "
                        + "raster did not load, so the zoomable map cannot be drawn (the rest of "
                        + "the game is unaffected)");
            }
            return;
        }
        float leftU = view.leftU();
        float bottomV = view.bottomV();
        float rightU = leftU + view.visibleU();
        float topV = bottomV + view.visibleV();
        // Texture v runs from the top of the image, and raster row 0 is the south edge, so v is flipped.
        crop.setRegion(clamp01(leftU), clamp01(1f - topV), clamp01(rightU), clamp01(1f - bottomV));
        float drawW = view.fitWidth() * view.zoom();
        float drawH = view.fitHeight() * view.zoom();
        float drawX = area.x + area.w * 0.5f - drawW * 0.5f;
        float drawY = area.y + area.h * 0.5f - drawH * 0.5f;

        batch.setColor(Color.WHITE);
        // Repeat the crop so a zoomed-out map still covers the screen when the view is clamped.
        batch.draw(crop, drawX, drawY, drawW, drawH);

        float px = drawX + (float) ((raster.u(playerEast, playerNorth) - leftU) / view.visibleU()) * drawW;
        float py = drawY + (float) ((raster.v(playerEast, playerNorth) - bottomV) / view.visibleV()) * drawH;
        batch.setColor(MARKER);
        float dot = Math.max(5f, area.w * 0.006f);
        batch.draw(white, px - dot, py - dot, dot * 2f, dot * 2f);
        batch.setColor(Color.WHITE);
    }

    /** The move stick: a ring where the thumb landed and a knob where it is now. */
    public void drawStick(float anchorX, float anchorY, float knobX, float knobY, float radius) {
        batch.setColor(STICK_DISC);
        batch.draw(white, anchorX - radius, anchorY - radius, radius * 2f, radius * 2f);
        batch.setColor(STICK_EDGE);
        float edge = Math.max(2f, radius * 0.04f);
        batch.draw(white, anchorX - radius, anchorY - radius, radius * 2f, edge);
        batch.draw(white, anchorX - radius, anchorY + radius - edge, radius * 2f, edge);
        batch.draw(white, anchorX - radius, anchorY - radius, edge, radius * 2f);
        batch.draw(white, anchorX + radius - edge, anchorY - radius, edge, radius * 2f);
        float knobRadius = radius * 0.3f;
        batch.setColor(STICK_KNOB);
        batch.draw(white, knobX - knobRadius, knobY - knobRadius, knobRadius * 2f, knobRadius * 2f);
        batch.setColor(Color.WHITE);
    }

    /**
     * The movement stick at rest, with its word underneath.
     *
     * <p>This is the fix for the ninth install's "the human and the car cannot move". The stick was
     * always there and always worked, but it only existed once a thumb had grabbed it, so nothing on the
     * screen said that dragging the lower left is how a person walks. A control that has to be discovered
     * by accident is not a control.
     *
     * <p>No allocation per frame: it is drawn sixty times a second forever, and a {@code new Color} in
     * that path is garbage the collector has to carry on a phone that is already warm.
     */
    public void drawStickHome(float centreX, float centreY, float radius, String label, String labelAr) {
        batch.setColor(STICK_HOME_DISC);
        batch.draw(white, centreX - radius, centreY - radius, radius * 2f, radius * 2f);
        batch.setColor(STICK_HOME_EDGE);
        float edge = Math.max(2f, radius * 0.04f);
        batch.draw(white, centreX - radius, centreY - radius, radius * 2f, edge);
        batch.draw(white, centreX - radius, centreY + radius - edge, radius * 2f, edge);
        batch.draw(white, centreX - radius, centreY - radius, edge, radius * 2f);
        batch.draw(white, centreX + radius - edge, centreY - radius, edge, radius * 2f);
        // The knob sits in the middle, where it will be grabbed from.
        float knobRadius = radius * 0.3f;
        batch.setColor(STICK_HOME_KNOB);
        batch.draw(white, centreX - knobRadius, centreY - knobRadius, knobRadius * 2f, knobRadius * 2f);
        batch.setColor(Color.WHITE);
        if (fontsReady) {
            float baseline = centreY - radius - small.getLineHeight() * 0.6f;
            drawCentred(small, label, centreX, baseline, TEXT_DIM);
            if (labelAr != null && !labelAr.isEmpty()) {
                drawCentred(small, ArabicReshaper.shapeForDisplay(labelAr), centreX,
                        baseline - small.getLineHeight() * 1.05f, TEXT_DIM);
            }
        }
    }

    /** Crosshair for the point a tap would teleport to. */
    public void drawTeleportMarker(float x, float y, float size) {
        batch.setColor(CROSSHAIR);
        drawLine(x - size, y, x + size, y, Math.max(2f, size * 0.16f), CROSSHAIR);
        drawLine(x, y - size, x, y + size, Math.max(2f, size * 0.16f), CROSSHAIR);
        batch.setColor(Color.WHITE);
    }

    /** A one-line message, drawn in the large style across the bottom of the screen. */
    public void drawMessage(String text, float x, float y, boolean arabic) {
        if (!fontsReady || text == null || text.isEmpty()) {
            return;
        }
        drawLeft(large, arabic ? ArabicReshaper.shapeForDisplay(text) : text, x, y, TEXT);
    }

    /** A status line centred on a screen point: used by the launch hint, in both languages. */
    public void drawCentredStatus(String text, float centreX, float baselineY) {
        if (!fontsReady || text == null || text.isEmpty()) {
            return;
        }
        drawCentred(small, text, centreX, baselineY, TEXT_DIM);
    }

    /** The Arabic half of the same hint, shaped and centred. */
    public void drawCentredStatusArabic(String text, float centreX, float baselineY) {
        if (!fontsReady || text == null || text.isEmpty()) {
            return;
        }
        drawCentred(small, ArabicReshaper.shapeForDisplay(text), centreX, baselineY, TEXT_DIM);
    }

    public void drawStatus(String text, float x, float y) {
        if (!fontsReady) {
            return;
        }
        drawLeft(small, text, x, y, TEXT_DIM);
    }

    /**
     * Draws one Arabic line: shaped and put in visual order first, because the renderer lays glyphs out
     * left to right and unshaped Arabic would come out as disconnected letters in the wrong order.
     * Hand it a line that is Arabic, or Arabic with numbers in it -- not a mixed English line, which the
     * reordering would flip end for end.
     */
    public void drawStatusArabic(String logicalArabic, float x, float y) {
        if (!fontsReady) {
            return;
        }
        drawLeft(small, ArabicReshaper.shapeForDisplay(logicalArabic), x, y, TEXT_DIM);
    }

    public float lineHeight() {
        return fontsReady ? small.getLineHeight() : 24f;
    }

    public boolean fontsReady() {
        return fontsReady;
    }

    /** True when the country's raster is uploaded and the minimap and full map can be drawn. */
    public boolean hasMap() {
        return mapTexture != null;
    }

    private void drawLine(float x0, float y0, float x1, float y1, float thickness, Color colour) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1e-4f) {
            return;
        }
        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        batch.setColor(colour);
        batch.draw(pixel, x0, y0 - thickness * 0.5f, 0f, thickness * 0.5f, length, thickness, 1f, 1f,
                angle);
        batch.setColor(Color.WHITE);
    }

    private void drawCentred(BitmapFont font, String text, float centreX, float baselineY, Color colour) {
        layout.setText(font, text);
        font.setColor(colour);
        font.draw(batch, layout, centreX - layout.width * 0.5f, baselineY);
    }

    private void drawLeft(BitmapFont font, String text, float x, float baselineY, Color colour) {
        font.setColor(colour);
        font.draw(batch, text, x, baselineY);
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    /** Uploads the map raster. The pixmap is freed immediately: only the texture is needed. */
    private static Texture uploadRaster(MapRaster raster) {
        Pixmap pixmap = new Pixmap(raster.width, raster.height, Pixmap.Format.RGBA8888);
        java.nio.ByteBuffer buffer = pixmap.getPixels();
        buffer.clear();
        for (int i = 0; i < raster.pixels.length; i++) {
            int argb = raster.pixels[i];
            buffer.put((byte) ((argb >> 16) & 0xFF));   // R
            buffer.put((byte) ((argb >> 8) & 0xFF));    // G
            buffer.put((byte) (argb & 0xFF));           // B
            buffer.put((byte) ((argb >>> 24) & 0xFF));  // A
        }
        buffer.flip();
        Texture texture = new Texture(pixmap);
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pixmap.dispose();
        return texture;
    }

    private static Texture solidTexture(int rgba) {
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(((rgba >>> 16) & 0xFF) / 255f, ((rgba >>> 8) & 0xFF) / 255f,
                (rgba & 0xFF) / 255f, ((rgba >>> 24) & 0xFF) / 255f);
        pixmap.fill();
        Texture texture = new Texture(pixmap);
        pixmap.dispose();
        return texture;
    }

    @Override
    public void dispose() {
        batch.dispose();
        if (small != null) {
            small.dispose();
        }
        if (large != null) {
            large.dispose();
        }
        if (white != null) {
            white.dispose();
        }
        if (mapTexture != null) {
            mapTexture.dispose();
        }
    }
}
