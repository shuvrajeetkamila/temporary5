package ai.arena.qatar.hud;

import ai.arena.qatar.game.CameraRig;
import ai.arena.qatar.game.PlayerController;
import ai.arena.qatar.game.PlayerController.Loco;
import ai.arena.qatar.physics.TerrainSampler;
import ai.arena.qatar.text.ArabicReshaper;
import ai.arena.qatar.world.BundleReader;
import ai.arena.qatar.world.GameManifest;
import ai.arena.qatar.world.StringTable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HUD prints three kinds of text: numbers and labels it builds itself, Arabic that the reshaper
 * shapes, and 17,000 names that came out of OpenStreetMap in both languages. A glyph the font does not
 * carry is not an exception and not a crash -- it is a blank space or a box, and the only person who
 * would notice is somebody holding the phone. So the shipped atlases are held against the strings.
 *
 * <p>This test found its first bug before it was ever green: the typeface subset the fonts were
 * generated from had no 'é', 'ā', 'ū' or 'ی', and the compiled country has nine names that use them, so
 * "Musée" and "Wādī" would have printed with a hole in them. The generator now covers every character
 * class Amiri actually has in the ranges the data uses, and this asserts it stayed that way.
 */
final class FontCoverageTest {

    private static final Pattern GLYPH = Pattern.compile("^char id=(\\d+) ", Pattern.MULTILINE);

    @Test
    @DisplayName("every character the HUD can print has a glyph in both shipped fonts")
    void everyPrintedCharacterHasAGlyph() throws IOException {
        Set<Integer> small = glyphs(font("hud_small.fnt"));
        Set<Integer> large = glyphs(font("hud_large.fnt"));
        assertTrue(small.size() > 300, "the small font shrank to " + small.size() + " glyphs");
        assertTrue(large.size() > 300, "the large font shrank to " + large.size() + " glyphs");

        List<String> lines = printedLines();
        assertTrue(lines.size() > 1_000, "only " + lines.size() + " lines were checked");

        for (String line : lines) {
            // Arabic goes on screen shaped, so that is the text the font actually has to carry. Mixed
            // lines are left alone -- the HUD draws those as separate Arabic and English lines.
            String shown = ArabicReshaper.containsArabic(line)
                    ? ArabicReshaper.shapeForDisplay(line) : line;
            for (int i = 0; i < shown.length(); i++) {
                char c = shown.charAt(i);
                if (c == '\n' || c == '\t') {
                    continue;
                }
                String where = "U+" + Integer.toHexString(c) + " ('" + c + "') in \"" + line + "\"";
                assertTrue(small.contains((int) c), "the small font has no glyph for " + where);
                assertTrue(large.contains((int) c), "the large font has no glyph for " + where);
            }
        }
    }

    @Test
    @DisplayName("the credit is credited: it names OpenStreetMap, the licence and the elevation source")
    void theCreditIsComplete() {
        String english = Credits.english(null);
        assertTrue(english.contains("OpenStreetMap"), "the credit does not name OpenStreetMap");
        assertTrue(english.contains("ODbL"), "the credit does not name the licence");
        assertTrue(english.contains("SRTM"), "the credit does not name the elevation source");
        assertTrue(ArabicReshaper.containsArabic(Credits.ARABIC),
                "the Arabic credit is not in Arabic");

        // A world that names its own sources is printed verbatim; one that names none gets the fallback
        // rather than an empty line where a licence should be.
        String mine = Credits.english("(c) Some Other Source, ODbl 9.9");
        assertTrue(mine.contains("Some Other Source"), "the world's own credit was replaced: " + mine);
        assertFalse(Credits.english("   ").trim().isEmpty(), "an empty credit produced no line");
    }

    /** Everything the HUD can put on the screen: its own lines, and every name in the country. */
    private static List<String> printedLines() {
        List<String> lines = new ArrayList<>();
        lines.add(Credits.english(null));
        lines.add(Credits.ARABIC);
        lines.add(HudText.MAP_HINT_EN);
        lines.add(HudText.MAP_HINT_AR);

        PlayerController controller = new PlayerController(new CameraRig(TerrainSampler.FLAT, 0.0, 0.0));
        for (Loco loco : Loco.values()) {
            controller.setLoco(loco);
            lines.add(controller.describe());
            lines.add(controller.describeArabic());
            lines.add(loco.labelAr);
        }

        StringTable names = worldNames();
        if (names != null) {
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                if (name != null && !name.isEmpty()) {
                    lines.add(name);
                }
            }
        }
        return lines;
    }

    /**
     * The string table out of the committed bundles -- the same 17,718 names the APK ships -- or null
     * when the world assets are not checked out, in which case the font is still checked against the
     * HUD's own text.
     */
    private static StringTable worldNames() {
        Path dir = worldDir();
        if (dir == null) {
            return null;
        }
        try {
            GameManifest manifest = GameManifest.parse(new String(
                    Files.readAllBytes(dir.resolve("manifest.json")), StandardCharsets.UTF_8));
            String file = manifest.strings.file.isEmpty() ? "strings.bin.gz" : manifest.strings.file;
            byte[] raw = Files.readAllBytes(dir.resolve(file));
            byte[] payload = raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B
                    ? BundleReader.inflate(file, raw) : raw;
            return StringTable.parse(payload);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the world strings: " + e, e);
        }
    }

    private static Path worldDir() {
        for (Path candidate : new Path[]{
                Paths.get("..", "android", "assets", "world"),
                Paths.get("android", "assets", "world")}) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path font(String name) {
        for (Path candidate : new Path[]{
                Paths.get("..", "android", "assets", "fonts", name),
                Paths.get("android", "assets", "fonts", name)}) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        Assumptions.assumeTrue(false, "the fonts are not checked out: " + name);
        return Paths.get(name);
    }

    private static Set<Integer> glyphs(Path fnt) throws IOException {
        Set<Integer> ids = new LinkedHashSet<>();
        String text = new String(Files.readAllBytes(fnt), StandardCharsets.UTF_8);
        Matcher matcher = GLYPH.matcher(text);
        while (matcher.find()) {
            ids.add(Integer.parseInt(matcher.group(1)));
        }
        int declared = 0;
        Matcher count = Pattern.compile("^chars count=(\\d+)$", Pattern.MULTILINE).matcher(text);
        if (count.find()) {
            declared = Integer.parseInt(count.group(1));
        }
        assertTrue(declared == ids.size(),
                fnt.getFileName() + " claims " + declared + " characters but lists " + ids.size());
        return ids;
    }
}
