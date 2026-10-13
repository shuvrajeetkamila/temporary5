package ai.arena.qatar.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arabic correctness is a hard requirement: shaping, joining, lam-alef ligature, embedded Latin and
 * Western numerals. Reversing raw code points is explicitly not acceptable, so the tests pin the
 * exact expected presentation forms.
 */
final class ArabicReshaperTest {

    @Test
    @DisplayName("Qatar shapes into contextual forms and renders right-to-left")
    void qatarWord() {
        // qaf + tah + reh:  initial + medial + final, emitted in visual (reversed) order.
        String logical = "\u0642\u0637\u0631"; // قطر
        assertEquals("\uFED7\uFEC4\uFEAE", ArabicReshaper.shape(logical), "shaped in logical order");
        assertEquals("\uFEAE\uFEC4\uFED7", ArabicReshaper.shapeForDisplay(logical), "visual order");
    }

    @Test
    @DisplayName("non-joining letters keep their isolated/final forms")
    void nonJoiningLetters() {
        // alef + reh: alef never joins to the following letter, so reh is isolated.
        String logical = "\u0627\u0631"; // ار
        assertEquals("\uFE8D\uFEAD", ArabicReshaper.shape(logical));

        // dal is right-joining only: beh + dal => beh initial, dal final.
        String behDal = "\u0628\u062F"; // بد
        assertEquals("\uFE91\uFEAA", ArabicReshaper.shape(behDal));
    }

    @Test
    @DisplayName("the mandatory lam-alef ligature is produced")
    void lamAlefLigature() {
        // lam + alef with nothing before: isolated ligature.
        assertEquals("\uFEFB", ArabicReshaper.shape("\u0644\u0627"));
        // beh + lam + alef: the ligature takes its final form.
        String word = "\u0628\u0644\u0627"; // بلا
        String shaped = ArabicReshaper.shape(word);
        assertTrue(shaped.startsWith("\uFE91"), "beh must take its initial form: " + escape(shaped));
        assertTrue(shaped.contains("\uFEFC"), "lam-alef must be the final-form ligature: " + escape(shaped));
        // The ligature is one glyph for two code points.
        assertEquals(2, shaped.length());
    }

    @Test
    @DisplayName("harakat do not break joining")
    void transparentMarksDoNotBreakJoining() {
        String withFatha = "\u0642\u064E\u0637\u0631"; // قَطر
        String shaped = ArabicReshaper.shape(withFatha);
        assertTrue(shaped.startsWith("\uFED7"), "qaf still takes the initial form: " + escape(shaped));
        assertTrue(shaped.contains("\u064E"), "the fatha is preserved");
    }

    @Test
    @DisplayName("Latin words and Western digits stay readable inside Arabic text")
    void mixedLatinAndDigits() {
        String logical = "\u0645\u064A\u0646\u0627\u0621 22 km"; // "ميناء 22 km"
        String display = ArabicReshaper.shapeForDisplay(logical);
        assertTrue(display.contains("km"), "Latin unit must survive untouched: " + display);
        assertTrue(display.contains("22"), "Western digits must survive untouched: " + display);
        // Arabic letters must all be presentation forms (i.e. shaped, never raw reversed code points).
        for (int i = 0; i < display.length(); i++) {
            char c = display.charAt(i);
            if (ArabicReshaper.isArabic(c) && Character.isLetter(c) && c < 0xFB50) {
                throw new AssertionError("unshaped Arabic letter leaked into display text: " + escape(display));
            }
        }
    }

    @Test
    @DisplayName("an English label is passed through unchanged")
    void englishPassthrough() {
        assertFalse(ArabicReshaper.containsArabic("Doha Corniche"));
        assertEquals("Doha Corniche", ArabicReshaper.shapeForDisplay("Doha Corniche"));
        assertEquals("Al Wakrah 22 km", ArabicReshaper.shapeForDisplay("Al Wakrah 22 km"));
    }

    @Test
    @DisplayName("base direction detection follows the first strong character")
    void baseDirection() {
        assertTrue(ArabicReshaper.baseDirectionIsRtl("\u0627\u0644\u062F\u0648\u062D\u0629")); // الدوحة
        assertFalse(ArabicReshaper.baseDirectionIsRtl("Doha"));
        assertTrue(ArabicReshaper.baseDirectionIsRtl("22 \u0627\u0644\u062F\u0648\u062D\u0629"));
    }

    @Test
    @DisplayName("visual length never exceeds the logical length (ligatures only shorten)")
    void visualLength() {
        assertEquals(3, ArabicReshaper.visualLength("\u0642\u0637\u0631"));
        assertEquals(1, ArabicReshaper.visualLength("\u0644\u0627")); // lam-alef is a single glyph
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(String.format("\\u%04X", (int) c));
        }
        return sb.toString();
    }
}
