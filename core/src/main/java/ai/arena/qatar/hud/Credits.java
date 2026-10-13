package ai.arena.qatar.hud;

/**
 * The data credit: what this game owes the sources it is compiled from, in the two languages it is
 * played in.
 *
 * <p>OpenStreetMap's licence (ODbL 1.0) asks a produced work to make the attribution visible to the
 * people using it. The compiled country has always carried the attribution in its manifest, and for a
 * while the manifest was the only place it existed -- the text was in the APK and never on the screen,
 * which is not attribution however true it is in the file. The HUD prints it on the map screen, which is
 * the picture made from that data, and for the first seconds of a launch, which is where a credit
 * belongs.
 *
 * <p>Keep this text to characters the HUD fonts carry: {@code FontCoverageTest} reads the shipped
 * atlases and fails if a character here, or in any name in the compiled country, has no glyph.
 */
public final class Credits {

    /** Printed when the compiled world carries no attribution of its own. */
    public static final String ENGLISH_FALLBACK =
            "(c) OpenStreetMap contributors, ODbL 1.0 -- elevation from SRTM (NASA/USGS, public domain)";

    /** The Arabic credit: the same sources, written as an Arabic sentence rather than word for word. */
    public static final String ARABIC =
            "© مساهمو OpenStreetMap — رخصة ODbL — الارتفاع من SRTM (ناسا)";

    /** The English line the HUD prints: the manifest's own words when it has them, else the fallback. */
    public static String english(String manifestAttribution) {
        if (manifestAttribution == null || manifestAttribution.trim().isEmpty()) {
            return ENGLISH_FALLBACK;
        }
        return manifestAttribution;
    }

    private Credits() {
    }
}
