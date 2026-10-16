package ai.arena.qatar.hud;

/**
 * The fixed words the HUD prints that are not labels on a button or a number on a status line.
 *
 * <p>They live here rather than inline in the screen for one reason: the font coverage test has to know
 * the exact strings the player will see, and it cannot read a literal out of a method.
 */
public final class HudText {

    /** Under the minimap when the big map is open. */
    public static final String MAP_HINT_EN = "Tap a place on the map to travel there";
    /** The same sentence in Arabic, drawn on its own line so the reshaper never flips the English one. */
    public static final String MAP_HINT_AR = "اضغط على الخريطة للانتقال إلى هناك";

    /** Under the resting movement stick, so that the control says what it is. */
    public static final String MOVE_LABEL = "MOVE";
    /** The same word in Arabic. */
    public static final String MOVE_LABEL_AR = "حركة";
    /**
     * The launch hint, in both languages. It exists because the ninth install found that a control
     * which is only visible once it has been used is not a control: a player who does not already know
     * that dragging the lower left walks the person will report that the person cannot move.
     */
    public static final String CONTROLS_HINT_EN =
            "Drag the ring at the lower left to walk or drive. Drag the right side to look around.";
    public static final String CONTROLS_HINT_AR =
            "اسحب الحلقة أسفل اليسار للمشي أو القيادة، واسحب الجهة اليمنى للنظر حولك.";

    /**
     * The flying hint: a helicopter and an aeroplane are flown with the same two gestures as everything
     * else, and neither gesture is guessable -- the drag is the stick and the ring is the lever. Shown
     * for a few seconds when a flying machine is selected, which is when it can still be read.
     */
    public static final String FLY_HINT_EN =
            "Drag the right ring: up flies forward, down slows. The left ring is the lever: forward climbs.";
    public static final String FLY_HINT_AR =
            "اسحب الحلقة اليمنى: للأعلى للطيران، وللأسفل للتباطؤ. والحلقة اليسرى للارتفاع: للأمام للصعود.";
    /** The flying stick's own word, under its ring, exactly as the movement stick has one. */
    public static final String FLY_LABEL = "FLY";
    public static final String FLY_LABEL_AR = "طيران";

    private HudText() {
    }
}
