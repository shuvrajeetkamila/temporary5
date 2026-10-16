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
        VIEW,          // first person <-> behind the body
        LOCO_HUMAN,
        LOCO_CAR,
        LOCO_PLANE,
        LOCO_HELI,
        LOCO_BOAT,
        /** Calls the boat to the nearest water a boat can float in; see {@code BoatCall}. */
        CALL_BOAT,
        MAP,           // open the big map
        ALT_UP,
        /** The walking jump: the only thing that gets a person over a kerb or off a wall. */
        JUMP,
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
        /** True when this control is currently on (a chosen locomotion type, the view, a held stick). */
        public boolean on;

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
    private final Rect minimapTouch = new Rect();
    /**
     * Where the movement stick rests when no thumb is on it, and the area that grabs it.
     *
     * <p>The stick is the only control that moves a person or a car, and until this round it was
     * invisible until it had been grabbed: nothing on the screen said that dragging the lower left is
     * how a person walks. The ninth install reported exactly that -- "human, car: cannot move" -- so the
     * stick now rests where a thumb expects it, drawn, with a word under it.
     */
    private final Rect stickHome = new Rect();
    /** The lower-left area that grabs the stick: dragging anywhere in it moves the player. */
    private final Rect moveZone = new Rect();
    /** Area reserved for looking around: everything not covered by a control. */
    private final Rect lookArea = new Rect();
    /**
     * Where the flying stick rests: the ring on the right-hand side that the drag of a pilot's thumb is
     * measured from, at the same size as the movement stick. It sits inside the look area, above the
     * bottom row of buttons and below the map's tap target, so a thumb flying a helicopter is looking
     * around, flying, and not opening the map.
     */
    private final Rect flyStickHome = new Rect();

    private float screenWidth = 1280;
    private float screenHeight = 720;
    /** The drawn radius of both sticks, in pixels: also the distance that means "full deflection". */
    private float stickRadius = 64f;
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

    /** The area a tap opens the whole map from: the minimap's corner, sized for a thumb. */
    public Rect minimapTouch() {
        return minimapTouch;
    }

    /** Where the movement stick is drawn when it is not being held. */
    public Rect stickHome() {
        return stickHome;
    }

    /** The lower-left area that grabs the movement stick. */
    public Rect moveZone() {
        return moveZone;
    }

    public Rect lookArea() {
        return lookArea;
    }

    /** The flying stick at rest, in the look area on the right-hand side. */
    public Rect flyStickHome() {
        return flyStickHome;
    }

    /** The drawn radius of a stick: both are the same, and a drag of this far is full deflection. */
    public float stickRadius() {
        return stickRadius;
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
            minimapTouch.x = 0;
            minimapTouch.y = 0;
            minimapTouch.w = 0;
            minimapTouch.h = 0;
            stickHome.x = 0;
            stickHome.y = 0;
            stickHome.w = 0;
            stickHome.h = 0;
            flyStickHome.x = 0;
            flyStickHome.y = 0;
            flyStickHome.w = 0;
            flyStickHome.h = 0;
            moveZone.x = 0;
            moveZone.y = 0;
            moveZone.w = 0;
            moveZone.h = 0;
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
        // JUMP sits beside RUN, inside the thumb's arc: the walker needs it and the machines ignore it,
        // so it is enabled only on foot.
        add(Id.JUMP, pad + bw * 1.15f + gap, pad, bw * 1.05f, bh, "JUMP", "قفز");

        // Altitude and the map sit on the right, under the minimap.
        add(Id.ALT_UP, screenWidth - pad - bw * 0.7f, pad + bh + gap, bw * 0.7f, bh, "UP", "أعلى");
        add(Id.ALT_DOWN, screenWidth - pad - bw * 0.7f, pad, bw * 0.7f, bh, "DOWN", "أسفل");
        float rightX = screenWidth - pad - bw * 0.7f - gap;
        add(Id.MAP, rightX - bw * 1.2f, pad, bw * 1.2f, bh, "MAP", "خريطة");
        // The brake lives beside the map, where a driving thumb can reach it without leaving the stick.
        add(Id.BRAKE, rightX - bw * 1.2f - gap - bw * 1.2f, pad, bw * 1.2f, bh, "BRAKE", "فرامل");
        // Above them: whose eyes the screen is looking through -- the driver's, or behind the body.
        add(Id.VIEW, rightX - bw * 1.2f, pad + bh + gap, bw * 1.2f, bh, "VIEW", "منظر");
        // And above that, the boat call. It is not a locomotion button -- it does not change what the
        // player is -- so it is not in the locomotion column: it is a request for a machine, and it lives
        // with the other world requests, out of the way of the walking thumb.
        add(Id.CALL_BOAT, rightX - bw * 1.2f, pad + (bh + gap) * 2f, bw * 1.2f, bh, "CALL", "استدعاء");

        // The movement stick. Its resting place is the lower left, clear of the locomotion column and
        // the MODE/RUN/JUMP row on one side, clear of the minimap corner and the map's tap target on the
        // other: a thumb lands here without looking, and the ring on the screen is what tells the player
        // that there is something here to be dragged.
        stickRadius = Math.max(64f, Math.min(screenWidth, screenHeight) * 0.14f);
        stickHome.w = stickRadius * 2f;
        stickHome.h = stickRadius * 2f;
        stickHome.x = pad + bw * 1.15f + gap + stickRadius * 0.55f;
        stickHome.y = screenHeight * 0.19f;
        // The flying stick: the same ring, mirrored to the right-hand side. A pilot's right thumb rests
        // here whether or not a flying machine is selected, for the same reason the movement stick is
        // drawn at rest: a control that only appears once it has been used is not a control.
        float flyRadius = stickRadius * 0.85f;
        flyStickHome.w = flyRadius * 2f;
        flyStickHome.h = flyRadius * 2f;
        flyStickHome.x = screenWidth * 0.70f;
        flyStickHome.y = screenHeight * 0.30f;
        // The zone that grabs it: the lower left third, up to the height where the map's corner begins.
        // It never reaches the right-hand side, where looking, the minimap and the map corner live.
        moveZone.x = 0f;
        moveZone.y = 0f;
        moveZone.w = screenWidth * 0.34f;
        moveZone.h = screenHeight * 0.62f;

        // The minimap: a square in the top-right corner, with the whole world in it.
        float mapSize = Math.min(screenHeight * 0.30f, screenWidth * 0.22f);
        minimap.w = mapSize;
        minimap.h = mapSize;
        minimap.x = screenWidth - pad - mapSize;
        minimap.y = screenHeight - pad - mapSize;

        // The tap target, which is not the square: a 100 px square is a two-millimetre target on a
        // phone held at arm's length. The map opens from a much larger area of the same corner -- the
        // whole top-right quarter, down to the middle of the screen -- and the map's own hint text sits
        // in it, so the player sees why the tap worked. It deliberately excludes the left third (the
        // movement stick) and everything below 62% of the height (the stick's arc and the buttons).
        // Left of this is the movement stick's zone: the two never overlap, so a thumb reaching for one
        // cannot open the other.
        minimapTouch.x = moveZone.w;
        minimapTouch.y = screenHeight * 0.62f;
        minimapTouch.w = screenWidth - minimapTouch.x;
        minimapTouch.h = screenHeight - minimapTouch.y;

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

    /**
     * Marks whether one control is currently on (the chosen locomotion, the view, a held stick).
     *
     * <p>Per control, not one shared flag: the game sets the state of five or six of them every frame,
     * and a single field would leave only the last one it touched telling the truth.
     */
    public void setHighlighted(Id id, boolean on) {
        Button button = button(id);
        if (button != null) {
            button.on = on;
        }
        highlighted = id;
        highlightedOn = on;
    }

    private Id highlighted;
    private boolean highlightedOn;

    /** The last control whose state was set; use {@link #isHighlighted} for a specific one. */
    public Id highlighted() {
        return highlighted;
    }

    public boolean highlightedOn() {
        return highlightedOn;
    }

    /** True when this control is currently on. */
    public boolean isHighlighted(Id id) {
        Button button = button(id);
        return button != null && button.on;
    }
}
