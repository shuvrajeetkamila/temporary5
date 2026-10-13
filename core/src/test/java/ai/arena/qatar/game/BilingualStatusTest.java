package ai.arena.qatar.game;

import ai.arena.qatar.game.PlayerController.Loco;
import ai.arena.qatar.physics.TerrainSampler;
import ai.arena.qatar.text.ArabicReshaper;
import ai.arena.qatar.vehicle.VehicleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every control in this game is labelled in English and Arabic, and the status line was the last thing
 * that was not: it read {@code CAR | 24 m/s | alt 0 m | hdg 47} whatever language the player reads. These
 * tests hold the Arabic line to being Arabic, to carrying the same numbers, and to being shapeable by the
 * reshaper -- because Arabic that is never shaped renders as disconnected letters in the wrong order, and
 * nothing but a device would show that.
 */
final class BilingualStatusTest {

    private static PlayerController controller() {
        return new PlayerController(new CameraRig(TerrainSampler.FLAT, 0.0, 0.0));
    }

    @Test
    @DisplayName("the second status line is Arabic, and it carries the same numbers")
    void theArabicLineSaysTheSameNumbers() {
        PlayerController controller = controller();
        VehicleState state = controller.state();
        state.speedMS = 12.4;
        state.heightM = 33.0;
        state.headingDegrees = 47.0;

        String line = controller.describeArabic();
        assertTrue(ArabicReshaper.containsArabic(line), "the Arabic line has no Arabic in it: " + line);
        assertTrue(ArabicReshaper.baseDirectionIsRtl(line),
                "the Arabic line does not read right to left: " + line);
        for (String number : new String[]{"12", "33", "47"}) {
            assertTrue(line.contains(number), "the Arabic line has lost the " + number + ": " + line);
        }

        // The renderer lays glyphs out left to right, so the line has to be shaped before it is drawn.
        // Shaping is what joins the letters, and joined letters are the presentation forms.
        String shown = ArabicReshaper.shapeForDisplay(line);
        assertTrue(hasPresentationForm(shown), "the line is not shaped for display: " + shown);
        assertNotEquals(line, shown, "shaping left the line as it was written");
        assertEquals(ArabicReshaper.visualLength(line), shown.length(),
                "visualLength disagrees with the display form");
        // Numbers are not reordered by any of that: 12 must still read as 12.
        assertTrue(shown.contains("12"), "shaping reordered the digits: " + shown);
        assertTrue(shown.contains("33") && shown.contains("47"), "shaping lost a number: " + shown);
    }

    @Test
    @DisplayName("every locomotion type names itself in Arabic on the status line")
    void everyLocomotionNamesItself() {
        PlayerController controller = controller();
        for (Loco loco : Loco.values()) {
            controller.setLoco(loco);
            String line = controller.describeArabic();
            assertTrue(line.startsWith(loco.labelAr),
                    loco + " is not named in Arabic first: " + line);
            assertTrue(ArabicReshaper.containsArabic(loco.labelAr),
                    loco + " has no Arabic name at all: " + loco.labelAr);
            // The Arabic name has to survive shaping into something a font can draw.
            assertTrue(ArabicReshaper.visualLength(loco.labelAr) > 0,
                    loco + " shapes to nothing");
        }
    }

    @Test
    @DisplayName("the Arabic line says whether the player is on the ground, in the air or in the water")
    void theArabicLineSaysWhereThePlayerIs() {
        PlayerController controller = controller();
        VehicleState state = controller.state();

        state.grounded = true;
        state.floating = false;
        String onFoot = controller.describeArabic();
        assertTrue(onFoot.contains("على الأرض"), "a grounded body is not on the ground: " + onFoot);

        state.grounded = false;
        state.floating = false;
        String flying = controller.describeArabic();
        assertTrue(flying.contains("في الجو"), "an airborne body is not in the air: " + flying);

        state.grounded = false;
        state.floating = true;
        String afloat = controller.describeArabic();
        assertTrue(afloat.contains("في الماء"), "a floating body is not in the water: " + afloat);
    }

    @Test
    @DisplayName("the map hint is Arabic on its own line, so the reshapers never flip the English")
    void theMapHintIsArabicOnItsOwn() {
        // The hint is drawn as two lines by the screen: this is the Arabic one, and it must be Arabic
        // only -- a mixed line would be reordered end for end by the reshapers.
        String hint = "اضغط على الخريطة للانتقال إلى هناك";
        assertTrue(ArabicReshaper.containsArabic(hint), "the hint has no Arabic");
        for (int i = 0; i < hint.length(); i++) {
            char c = hint.charAt(i);
            assertFalse(Character.isLetter(c) && !ArabicReshaper.isArabic(c),
                    "the Arabic hint carries a Latin letter: " + hint);
        }
        assertTrue(hasPresentationForm(ArabicReshaper.shapeForDisplay(hint)),
                "the hint is not shaped for display: " + hint);
    }

    /** True when the text carries at least one Arabic presentation form (U+FE70..U+FEFC). */
    private static boolean hasPresentationForm(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\uFE70' && c <= '\uFEFC') {
                return true;
            }
        }
        return false;
    }
}
