package ai.arena.qatar.hud;

import ai.arena.qatar.hud.HudLayout.Button;
import ai.arena.qatar.hud.HudLayout.Rect;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stick that flies, and what a frame of the HUD is allowed to allocate.
 *
 * <p>The tenth install's helicopter was flown by dragging anywhere on the right of the screen, with a
 * gesture that behaved as a nudge rather than as a stick: the deflection was applied as a change and
 * decayed back to trim at 1.6 a second, so a thumb held steady for two seconds flew a machine that had
 * already levelled itself out. That is half of "it flies, but not perfectly". The other half was that
 * nothing on the screen said a stick was there at all -- the movement stick had exactly the same problem
 * one install earlier and the report was "the human and the car cannot move".
 *
 * <p>So the flying stick is drawn at rest, its ring is the range of the drag, and this test holds the
 * geometry: that the ring exists on the world screen, that it vanishes with the map, that it does not
 * sit under a button or inside the map's tap target, and that the radius the layout draws with is the
 * radius the touch handler measures deflection against. That last one was a real defect: the movement
 * stick drew a 150-pixel ring on a phone and treated 60 pixels of drag as full speed.
 *
 * <p>The second half of this file is about the heat. The HUD draws the sticks, the crosshair, thirteen
 * buttons and four lines of text every frame, sixty times a second, and it used to build three
 * {@code Color} objects per stick while doing it -- six a frame with the flying stick up, 360
 * short-lived objects a second, on a phone that is already meshing a country. The colours are constants
 * now, and this says they stay constants.
 */
final class FlyStickTest {

    private static final float[][] SCREENS = {
            {2400f, 1080f}, {1920f, 1080f}, {2340f, 1080f}, {1280f, 720f}, {960f, 540f}, {800f, 1280f},
    };

    private static HudLayout laidOut(float width, float height) {
        HudLayout layout = new HudLayout();
        layout.resize(width, height);
        return layout;
    }

    /** A rectangle as {x, y, w, h}: the layout's own Rect has no constructor taking one. */
    private static float[] box(Rect rect) {
        return new float[]{rect.x, rect.y, rect.w, rect.h};
    }

    private static float[] box(Button button) {
        return new float[]{button.x, button.y, button.w, button.h};
    }

    private static boolean overlaps(float[] a, float[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    private static boolean inside(float[] inner, float[] outer) {
        return inner[0] >= outer[0] && inner[0] + inner[2] <= outer[0] + outer[2]
                && inner[1] >= outer[1] && inner[1] + inner[3] <= outer[1] + outer[3];
    }

    @Test
    @DisplayName("the flying stick has a ring on the world screen, at the size the drag is measured in")
    void theFlyingStickIsOnTheScreen() {
        for (float[] screen : SCREENS) {
            HudLayout layout = laidOut(screen[0], screen[1]);
            float[] fly = box(layout.flyStickHome());
            float[] move = box(layout.stickHome());
            String where = (int) screen[0] + "x" + (int) screen[1];
            assertTrue(fly[2] > 0f && fly[3] > 0f, "there is no flying stick at " + where);
            assertEquals(fly[2], fly[3], 0.001, "the flying ring is not round at " + where);
            assertEquals(move[2], layout.stickRadius() * 2f, 0.001,
                    "the movement ring is drawn at one size and measured at another at " + where);
            assertEquals(fly[2], layout.stickRadius() * 2f * 0.85f, 0.001,
                    "the flying ring is not the size the deflection is measured against at " + where);
            // A thumb goes where the eye goes: the ring is on the right, the movement stick on the left.
            assertTrue(fly[0] > move[0] + move[2], "the two sticks are on top of each other at " + where);
        }
    }

    @Test
    @DisplayName("the flying stick is not under a button, the map's corner, or the map itself")
    void theFlyingStickDoesNotStealTouches() {
        for (float[] screen : SCREENS) {
            HudLayout layout = laidOut(screen[0], screen[1]);
            float[] fly = box(layout.flyStickHome());
            String where = (int) screen[0] + "x" + (int) screen[1];
            // The map's tap target is the whole top-right corner: a thumb flying a helicopter must not
            // open the map by accident, which is exactly what it would do if the ring sat inside it.
            assertFalse(overlaps(fly, box(layout.minimapTouch())),
                    "the flying stick sits inside the map's tap target at " + where);
            assertFalse(overlaps(fly, box(layout.minimap())),
                    "the flying stick sits under the minimap at " + where);
            for (Button button : layout.buttons()) {
                assertFalse(overlaps(fly, box(button)), "the flying stick sits under the " + button.id
                        + " button at " + where);
            }
            // And the drag that grabs it is in the look area, not in the movement zone.
            assertFalse(overlaps(fly, box(layout.moveZone())),
                    "the flying stick is inside the movement zone at " + where);
            float[] look = box(layout.lookArea());
            assertTrue(inside(fly, look),
                    "the flying stick is outside the area the drag is measured in at " + where);
        }
    }

    @Test
    @DisplayName("the flying stick goes away with the map, like every other world control")
    void theFlyingStickVanishesWithTheMap() {
        HudLayout layout = laidOut(2400f, 1080f);
        layout.setBigMapVisible(true);
        assertEquals(0f, layout.flyStickHome().w, "the flying stick is on the map screen");
        assertEquals(0f, layout.stickHome().w, "the movement stick is on the map screen");
        assertEquals(0f, layout.moveZone().w, "the movement zone is on the map screen");
        assertEquals(0f, layout.minimapTouch().w, "the map's own tap target is on the map screen");
    }

    @Test
    @DisplayName("the frames are not garbage: the HUD's colours are built once, not once a frame")
    void theHudDoesNotAllocatePerFrame() throws IOException {
        String text = new String(Files.readAllBytes(source("HudRenderer.java")), StandardCharsets.UTF_8);
        Matcher colours = Pattern.compile("new\\s+Color\\s*\\(").matcher(text);
        int perFrame = 0;
        int constants = 0;
        while (colours.find()) {
            // A colour built in a field declaration is built once, when the class loads. A colour built
            // anywhere else is built on the frame that draws it.
            int lineStart = text.lastIndexOf('\n', colours.start()) + 1;
            int lineEnd = text.indexOf('\n', colours.start());
            String line = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
            if (line.contains("static final")) {
                constants++;
            } else {
                perFrame++;
            }
        }
        assertEquals(0, perFrame, "the HUD builds " + perFrame + " colours per frame: every one of them "
                + "is an object the phone has to collect sixty times a second");
        assertTrue(constants >= 8, "the HUD's colour constants were removed: only " + constants
                + " are declared, and the sticks alone need six");
        // The flying stick's own colours, by name, so that a rewrite that copies the old code back
        // cannot pass by keeping the count.
        assertTrue(text.contains("STICK_HOME_DISC") && text.contains("STICK_KNOB"),
                "the stick's colours are not the named constants");
    }

    private static Path source(String name) {
        Path[] directories = {
                Paths.get("..", "core", "src", "main", "java", "ai", "arena", "qatar", "hud"),
                Paths.get("core", "src", "main", "java", "ai", "arena", "qatar", "hud"),
                Paths.get("src", "main", "java", "ai", "arena", "qatar", "hud")};
        for (Path directory : directories) {
            Path candidate = directory.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        Assumptions.assumeTrue(false, "the HUD source is not checked out: " + name);
        return Paths.get(name);
    }
}
