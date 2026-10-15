package ai.arena.qatar.hud;

import ai.arena.qatar.hud.HudLayout.Button;
import ai.arena.qatar.hud.HudLayout.Id;
import ai.arena.qatar.hud.HudLayout.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controls, as geometry: which of them exist, where they are, and -- the part that matters on a
 * phone -- that a tap meant for the minimap cannot be eaten by a button lying on top of it.
 *
 * <p>The layout is pure arithmetic, so all of this is checkable without a device. It is here because of
 * the seventh install, where the controls were drawn and then culled away by the GPU: the layout was
 * never the problem, and this test says so, so that the next report of "no controls" can rule it out in
 * a second instead of in an hour.
 */
final class HudLayoutTest {

    /** A phone in landscape, a small tablet, and a tall oddity: the sizes this has to survive. */
    private static final float[][] SCREENS = {
            {2400f, 1080f}, {1920f, 1080f}, {2340f, 1080f}, {1280f, 720f}, {960f, 540f}, {800f, 1280f},
    };

    private static HudLayout laidOut(float width, float height) {
        HudLayout layout = new HudLayout();
        layout.resize(width, height);
        return layout;
    }

    @Test
    @DisplayName("the world screen has every control the game needs, including the map's")
    void everyControlExists() {
        HudLayout layout = laidOut(2400f, 1080f);
        for (Id id : new Id[]{Id.MODE, Id.SPRINT, Id.BRAKE, Id.VIEW, Id.LOCO_HUMAN, Id.LOCO_CAR,
                Id.LOCO_PLANE, Id.LOCO_HELI, Id.LOCO_BOAT, Id.MAP, Id.ALT_UP, Id.ALT_DOWN}) {
            assertNotNull(layout.button(id), "there is no " + id + " control");
        }
        // And none of the map screen's own controls, which would have nowhere to go.
        assertNull(layout.button(Id.MAP_CLOSE), "a map control on the world screen");
    }

    @Test
    @DisplayName("opening the map replaces the controls with the map's own, including a way to close it")
    void bigMapSwapsTheControls() {
        HudLayout layout = laidOut(2400f, 1080f);
        float worldButtons = layout.buttons().size();
        layout.setBigMapVisible(true);
        assertTrue(layout.bigMapVisible(), "the map is open");
        assertTrue(layout.buttons().size() < worldButtons,
                "the map screen carries fewer controls than the world screen");
        // A map with no close button is a map the player cannot leave: this is the assertion that says
        // so, in the place where the layout is decided.
        assertNotNull(layout.button(Id.MAP_CLOSE), "the open map cannot be closed");
        assertNotNull(layout.button(Id.ZOOM_IN), "the map cannot be zoomed in");
        assertNotNull(layout.button(Id.ZOOM_OUT), "the map cannot be zoomed out");
        assertNotNull(layout.button(Id.RECENTRE), "the map cannot be recentred");
        assertNull(layout.button(Id.LOCO_CAR), "a locomotion button is on the map screen");
        layout.setBigMapVisible(false);
        assertNotNull(layout.button(Id.LOCO_CAR), "closing the map did not bring the controls back");
        assertNotNull(layout.button(Id.MAP), "closing the map lost the button that opens it");
    }

    @Test
    @DisplayName("the minimap is in the top-right corner and no button lies on top of it")
    void theMinimapIsTappable() {
        for (float[] screen : SCREENS) {
            float width = screen[0];
            float height = screen[1];
            HudLayout layout = laidOut(width, height);
            Rect minimap = layout.minimap();
            assertTrue(minimap.w > 0f && minimap.h > 0f, width + "x" + height + ": no minimap");
            assertTrue(minimap.x + minimap.w <= width + 0.5f, "the minimap runs off the right edge");
            assertTrue(minimap.y + minimap.h <= height + 0.5f, "the minimap runs off the top edge");
            assertTrue(minimap.x > width * 0.5f, "the minimap is not in the right half of the screen");
            assertTrue(minimap.y > height * 0.4f, "the minimap is not in the top half of the screen");

            // The gesture: a finger on the minimap opens the whole map. GameScreen checks the buttons
            // first, so a button overlapping the minimap would swallow that tap -- which is exactly the
            // kind of thing that is invisible until somebody tries it, on a phone.
            float centreX = minimap.x + minimap.w * 0.5f;
            float centreY = minimap.y + minimap.h * 0.5f;
            assertNull(layout.hit(centreX, centreY),
                    width + "x" + height + ": a button covers the middle of the minimap");
            // And the corners, where a thumb actually lands.
            for (int corner = 0; corner < 4; corner++) {
                float x = (corner % 2 == 0 ? minimap.x + 2f : minimap.x + minimap.w - 2f);
                float y = (corner < 2 ? minimap.y + 2f : minimap.y + minimap.h - 2f);
                assertNull(layout.hit(x, y),
                        width + "x" + height + ": a button covers corner " + corner + " of the minimap");
            }
        }
    }

    @Test
    @DisplayName("every control is fully on the screen, at every size, and none overlaps another")
    void controlsFitAndDoNotCollide() {
        for (float[] screen : SCREENS) {
            float width = screen[0];
            float height = screen[1];
            for (boolean bigMap : new boolean[]{false, true}) {
                HudLayout layout = laidOut(width, height);
                layout.setBigMapVisible(bigMap);
                String where = width + "x" + height + (bigMap ? " (map)" : " (world)");
                for (Button button : layout.buttons()) {
                    assertTrue(button.x >= -0.5f && button.y >= -0.5f,
                            where + ": " + button.id + " starts off the screen");
                    assertTrue(button.x + button.w <= width + 0.5f,
                            where + ": " + button.id + " runs off the right edge");
                    assertTrue(button.y + button.h <= height + 0.5f,
                            where + ": " + button.id + " runs off the top edge");
                    assertTrue(button.w >= 30f && button.h >= 30f,
                            where + ": " + button.id + " is too small to press");
                }
                for (int i = 0; i < layout.buttons().size(); i++) {
                    for (int j = i + 1; j < layout.buttons().size(); j++) {
                        Button a = layout.buttons().get(i);
                        Button b = layout.buttons().get(j);
                        boolean apart = a.x + a.w <= b.x || b.x + b.w <= a.x
                                || a.y + a.h <= b.y || b.y + b.h <= a.y;
                        assertTrue(apart, where + ": " + a.id + " and " + b.id + " overlap");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("a tap on a control is read as that control, and disabled controls are not pressable")
    void hitsAreReadable() {
        HudLayout layout = laidOut(1920f, 1080f);
        Button map = layout.button(Id.MAP);
        assertEquals(Id.MAP, layout.hit(map.centreX(), map.centreY()).id,
                "the middle of the MAP button is not a press on MAP");
        Button look = layout.button(Id.LOCO_CAR);
        layout.setEnabled(Id.LOCO_CAR, false);
        assertNull(layout.hit(look.centreX(), look.centreY()),
                "a disabled control still takes presses");
        layout.setEnabled(Id.LOCO_CAR, true);
        assertEquals(Id.LOCO_CAR, layout.hit(look.centreX(), look.centreY()).id);
    }

    @Test
    @DisplayName("the status block has the top-left to itself, and the look area avoids the controls")
    void reservedAreasDoNotClash() {
        HudLayout layout = laidOut(2340f, 1080f);
        Rect minimap = layout.minimap();
        Rect look = layout.lookArea();
        assertFalse(look.x < minimap.x + minimap.w && minimap.x < look.x + look.w
                        && look.y < minimap.y + minimap.h && minimap.y < look.y + look.h,
                "the look area reaches under the minimap");
        // Nothing is drawn where the status lines go: top-left, below the top edge.
        for (Button button : layout.buttons()) {
            assertFalse(button.y > layout.screenHeight() * 0.75f && button.x < layout.screenWidth() * 0.5f,
                    button.id + " is drawn where the status text goes");
        }
    }
}
