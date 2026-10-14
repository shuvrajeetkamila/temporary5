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
    @DisplayName("the .fnt files parse the way libGDX parses them, and the font the HUD wants is in them")
    void fontsParseTheWayLibGdxDoes() throws IOException {
        // This is the test that would have caught the second device failure. libGDX reads a .fnt as a
        // stream: after the page definitions it loops over lines, and it STOPS at the first line that
        // begins with "kernings". Write "kernings count=0" above the character lines -- which the
        // generator did -- and zero glyphs are parsed; BitmapFont then looks up a fallback glyph to
        // measure a space, gets null, and throws a NullPointerException. The font does not load, the HUD
        // has no text, and nothing says why. So: parse it here, the same way, and require glyphs.
        for (String name : new String[]{"hud_small.fnt", "hud_large.fnt"}) {
            Path file = font(name);
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.StringReader(text));

            String info = reader.readLine();
            assertTrue(info != null && info.startsWith("info "), name + ": no info line");
            assertTrue(info.contains("padding="), name + ": the info line has no padding");

            String common = reader.readLine();
            assertTrue(common != null && common.startsWith("common "), name + ": no common line");
            assertTrue(common.contains("lineHeight=") && common.contains("base="),
                    name + ": the common line lacks lineHeight or base");

            String page = reader.readLine();
            assertTrue(page != null && page.startsWith("page "), name + ": no page line");
            assertTrue(page.contains("file=\""), name + ": the page line names no file");
            String pageFile = page.substring(page.indexOf("file=\"") + 6);
            pageFile = pageFile.substring(0, pageFile.indexOf('"'));
            assertTrue(Files.isRegularFile(file.getParent().resolve(pageFile)),
                    name + " points at a page image that does not exist: " + pageFile);

            // The page is RGBA with the coverage in the alpha channel: the shape libGDX's own font tool
            // writes and the one a BitmapFont is built to consume. Read the PNG header rather than trust
            // it, because a greyscale page only works if the decoder happens to map it to alpha, and that
            // is the kind of "it works on my device" that wastes a day.
            byte[] png = Files.readAllBytes(file.getParent().resolve(pageFile));
            assertEquals(0x89, png[0] & 0xFF, name + ": the page is not a PNG");
            assertEquals('P', png[1]);
            assertEquals('N', png[2]);
            assertEquals('G', png[3]);
            int depth = png[24] & 0xFF;
            int colourType = png[25] & 0xFF;
            assertEquals(8, depth, name + ": the page is not 8 bits per channel");
            assertEquals(6, colourType, name + ": the page is not RGBA (colour type " + colourType
                    + "). It must carry white pixels with coverage in alpha.");

            String chars = reader.readLine();
            assertTrue(chars != null && chars.startsWith("chars count="), name + ": no chars line");
            int declared = Integer.parseInt(chars.substring("chars count=".length()).trim());

            int glyphs = 0;
            boolean spaceFound = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("kernings ") || line.startsWith("metrics ")) {
                    break;                       // exactly where libGDX stops
                }
                if (!line.startsWith("char ")) {
                    continue;
                }
                glyphs++;
                if (line.startsWith("char id=32 ")) {
                    spaceFound = true;
                    int advance = Integer.parseInt(line.substring(line.indexOf("xadvance=") + 9)
                            .split(" ")[0]);
                    assertTrue(advance > 0, name + ": the space glyph advances nothing, so words "
                            + "would run together");
                }
            }
            assertEquals(declared, glyphs,
                    name + " declares " + declared + " characters and lists " + glyphs
                            + " before the section libGDX stops at");
            assertTrue(glyphs > 300, name + " parses to only " + glyphs + " glyphs");
            assertTrue(spaceFound, name + " has no space glyph: every word would be run together");
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
