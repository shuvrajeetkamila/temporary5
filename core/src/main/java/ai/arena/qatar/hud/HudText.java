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
    // ---------------------------------------------------------------------------------------------
    // Why the player is not moving.
    //
    // The tenth install's report was "cant walk, use car" -- four words and no diagnosis, which is what
    // a player can honestly tell us about a screen that shows a speed of zero. The game knows the
    // reason: a body that has been asked to move for two seconds and has not moved is blocked, and the
    // state says by what. These are the reasons, in the two languages the HUD speaks.
    // ---------------------------------------------------------------------------------------------
    public static final String STUCK_SOLID =
            "Cannot move: something solid is in the way";
    public static final String STUCK_SOLID_AR = "لا يمكن التحرك: هناك شيء صلب في الطريق";

    public static final String STUCK_WATER = "Cannot move: the water here is too deep";
    public static final String STUCK_WATER_AR = "لا يمكن التحرك: الماء هنا عميق جدًا";

    public static final String STUCK_TERRAIN =
            "Cannot move yet: the ground here is loading";
    public static final String STUCK_TERRAIN_AR = "لا يمكن التحرك بعد: الأرض هنا قيد التحميل";

    public static final String STUCK_AIR = "Cannot move: the machine is not on the ground";
    public static final String STUCK_AIR_AR = "لا يمكن التحرك: المركبة ليست على الأرض";

    public static final String STUCK_OTHER =
            "Cannot move here: see the diagnostics line";
    public static final String STUCK_OTHER_AR = "لا يمكن التحرك هنا: راجع سطر التشخيص";

    /** The flying stick's own word, under its ring, exactly as the movement stick has one. */
    public static final String FLY_LABEL = "FLY";
    public static final String FLY_LABEL_AR = "طيران";

    private HudText() {
    }
}
