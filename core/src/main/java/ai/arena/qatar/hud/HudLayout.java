package ai.arena.qatar.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Where every control lives, in pixels, for any screen size.
 *
 * <p>Pure geometry and pure lists: no GL, no fonts, no input singletons. That means the layout of the
 * whole game can be unit-tested at a dozen screen sizes -- including that no two buttons overlap and
 * that every button is fully on screen -- which is exactly the class of bug that otherwise only shows
 * up on someone else's device.
 *
 * <p>Coordinates are y-up from the bottom-left of the screen, matching the way the HUD is drawn;
 * touch input (y-down, from the top) is converted once, at the edge of the input code.
 */
public final class HudLayout {

    public enum Id {
        MODE,          // walk <-> car, the two ground ways of getting about
        SPRINT,
        BRAKE,
        LOCO_HUMAN,
        LOCO_CAR,
        LOCO_PLANE,
        LOCO_HELI,
        LOCO_BOAT,
        MAP,           // open the big map
        ALT_UP,
        ALT_DOWN,
        ZOOM_IN,
        ZOOM_OUT,
        MAP_CLOSE,
        RECENTRE
    }

    /** A control: a rectangle with a stable id and a label in both languages. */
    public static final class Button {
        public final Id id;
        public float x;
        public float y;
        public float w;
        public float h;
        public String label;
        public String labelAr;
        /** False when this control cannot be used in the current mode (drawn dimmed, not pressable). */
        public boolean enabled = true;

        Button(Id id, String label, String labelAr) {
            this.id = id;
            this.label = label;
            this.labelAr = labelAr;
        }

        public boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }

        public float centreX() {
            return x + w * 0.5f;
        }

        public float centreY() {
            return y + h * 0.5f;
        }
    }

    public static final class Rect {
        public float x;
        public float y;
        public float w;
        public float h;

        public boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    private final List<Button> buttons = new ArrayList<>();
    private final Rect minimap = new Rect();
    /** Area reserved for looking around: everything not covered by a control. */
    private final Rect lookArea = new Rect();

    private float screenWidth = 1280;
    private float screenHeight = 720;
    private boolean bigMapVisible;

    public void resize(float width, float height) {
        this.screenWidth = Math.max(320, width);
        this.screenHeight = Math.max(240, height);
        layout();
    }

    public void setBigMapVisible(boolean visible) {
        if (bigMapVisible != visible) {
            bigMapVisible = visible;
            layout();
        }
    }

    public boolean bigMapVisible() {
        return bigMapVisible;
    }

    public float screenWidth() {
        return screenWidth;
    }

    public float screenHeight() {
        return screenHeight;
    }

    public Rect minimap() {
        return minimap;
    }

    public Rect lookArea() {
        return lookArea;
    }

    public List<Button> buttons() {
        return buttons;
    }

    public Button button(Id id) {
        for (Button b : buttons) {
            if (b.id == id) {
                return b;
            }
        }
        return null;
    }

    /** The topmost control under a point, or null when the touch belongs to the world. */
    public Button hit(float px, float py) {
        for (int i = buttons.size() - 1; i >= 0; i--) {
            Button b = buttons.get(i);
            if (b.enabled && b.contains(px, py)) {
                return b;
            }
        }
        return null;
    }

    /**
     * Places every control.
     *
     * <p>Thumb reach drives the sizes: everything the player presses while moving sits within a
     * comfortable arc of the bottom corners, and grows with the screen so it is not a speck on a
     * tablet. The map keeps a fixed square so its pixels stay square at any aspect ratio.
     */
    private void layout() {
        buttons.clear();
        float unit = Math.min(screenWidth, screenHeight);
        float pad = unit * 0.018f;
        float bh = Math.max(34f, unit * 0.075f);       // button height
        float bw = Math.max(74f, unit * 0.155f);       // button width
        float gap = pad * 0.5f;

        if (bigMapVisible) {
            // The map takes the screen; only its own controls remain.
            float size = bh;
            add(Id.ZOOM_IN, screenWidth - pad - size, screenHeight - pad - size, size, size, "+", "+");
            add(Id.ZOOM_OUT, screenWidth - pad - size * 2 - gap, screenHeight - pad - size, size, size,
                    "-", "-");
            add(Id.MAP_CLOSE, screenWidth - pad - bw * 1.6f, pad, bw * 1.6f, bh, "CLOSE", "إغلاق");
            add(Id.RECENTRE, pad, pad, bw * 1.9f, bh, "FIND ME", "موقعي");
            minimap.x = 0;
            minimap.y = 0;
            minimap.w = 0;
            minimap.h = 0;
            lookArea.x = 0;
            lookArea.y = 0;
            lookArea.w = 0;
            lookArea.h = 0;
            return;
        }

        // Locomotion: a column at the bottom-left, thumb-reachable while walking.
        float colX = pad;
        float colY = pad + bh + gap + bh + gap; // above the sprint/mode pair
        String[] labels = {"HUMAN", "CAR", "PLANE", "HELI", "BOAT"};
        String[] labelsAr = {"إنسان", "سيارة", "طائرة", "مروحية", "قارب"};
        Id[] ids = {Id.LOCO_HUMAN, Id.LOCO_CAR, Id.LOCO_PLANE, Id.LOCO_HELI, Id.LOCO_BOAT};
        for (int i = 0; i < ids.length; i++) {
            add(ids[i], colX, colY + (ids.length - 1 - i) * (bh + gap), bw * 1.15f, bh, labels[i],
                    labelsAr[i]);
        }

        add(Id.MODE, pad, pad + bh + gap, bw * 1.15f, bh, "WALK", "مشي");
        add(Id.SPRINT, pad, pad, bw * 1.15f, bh, "RUN", "ركض");

        // Altitude and the map sit on the right, under the minimap.
        add(Id.ALT_UP, screenWidth - pad - bw * 0.7f, pad + bh + gap, bw * 0.7f, bh, "UP", "أعلى");
        add(Id.ALT_DOWN, screenWidth - pad - bw * 0.7f, pad, bw * 0.7f, bh, "DOWN", "أسفل");
        float rightX = screenWidth - pad - bw * 0.7f - gap;
        add(Id.MAP, rightX - bw * 1.2f, pad, bw * 1.2f, bh, "MAP", "خريطة");
        // The brake lives beside the map, where a driving thumb can reach it without leaving the stick.
        add(Id.BRAKE, rightX - bw * 1.2f - gap - bw * 1.2f, pad, bw * 1.2f, bh, "BRAKE", "فرامل");

        // The minimap: a square in the top-right corner, with the whole world in it.
        float mapSize = Math.min(screenHeight * 0.30f, screenWidth * 0.22f);
        minimap.w = mapSize;
        minimap.h = mapSize;
        minimap.x = screenWidth - pad - mapSize;
        minimap.y = screenHeight - pad - mapSize;

        // Looking around: anywhere in the lower middle of the screen that is not a control.
        lookArea.x = pad + bw * 1.15f + gap;
        lookArea.y = 0;
        lookArea.w = screenWidth - lookArea.x - pad;
        lookArea.h = screenHeight - minimap.h - pad * 3f;
    }

    private void add(Id id, float x, float y, float w, float h, String label, String labelAr) {
        Button button = new Button(id, label, labelAr);
        button.x = x;
        button.y = y;
        button.w = w;
        button.h = h;
        buttons.add(button);
    }

    /** Disables a control for the current mode, e.g. altitude buttons while walking. */
    public void setEnabled(Id id, boolean enabled) {
        Button button = button(id);
        if (button != null) {
            button.enabled = enabled;
        }
    }

    /** Marks which control is currently on (the walk/fly toggle, the chosen locomotion). */
    public void setHighlighted(Id id, boolean on) {
        highlighted = id;
        highlightedOn = on;
    }

    private Id highlighted;
    private boolean highlightedOn;

    public Id highlighted() {
        return highlighted;
    }

    public boolean highlightedOn() {
        return highlightedOn;
    }

    /** True when the control with this id is currently the highlighted one and it is on. */
    public boolean isHighlighted(Id id) {
        return highlighted == id && highlightedOn;
    }
}
