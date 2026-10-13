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

    private HudText() {
    }
}
