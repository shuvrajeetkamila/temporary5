package ai.arena.qatar.text;

/**
 * Deterministic Arabic shaping and visual reordering for glyph-run renderers.
 *
 * <p>Arabic is cursive: a letter changes shape depending on whether it joins the letter before it
 * and/or the letter after it. Naively reversing code points (a common shortcut) produces disconnected
 * letters and is explicitly forbidden by the project rules. This class performs:
 *
 * <ol>
 *   <li><b>Shaping</b> -- contextual isolated/initial/medial/final forms from Unicode Arabic
 *       Presentation Forms-B, plus the mandatory lam-alef ligature.</li>
 *   <li><b>Reordering</b> -- the shaped logical sequence is emitted in visual order for a
 *       left-to-right glyph renderer: right-to-left runs are reversed, embedded left-to-right runs
 *       (Latin words, digits, punctuation groups) are kept intact.</li>
 * </ol>
 *
 * <p>Harakat (vowel marks) and other transparent marks never break joining. The output is UTF-8/UTF-16
 * clean, requires no runtime shaping library, and is fully unit tested -- including mixed
 * Arabic/English strings with numbers, which is exactly what Qatari signage contains.
 */
public final class ArabicReshaper {

    // Arabic Presentation Forms-B: index = base letter, values = {isolated, final, initial, medial}, 0 = none.
    private static final char[][] FORMS = new char[0x680][];

    private static void form(char base, char isolated, char finalForm, char initialForm, char medialForm) {
        FORMS[base] = new char[]{isolated, finalForm, initialForm, medialForm};
    }

    static {
        form('\u0621', '\uFE80', (char) 0, (char) 0, (char) 0); // HAMZA
        form('\u0622', '\uFE81', '\uFE82', (char) 0, (char) 0); // ALEF MADDA
        form('\u0623', '\uFE83', '\uFE84', (char) 0, (char) 0); // ALEF HAMZA ABOVE
        form('\u0624', '\uFE85', '\uFE86', (char) 0, (char) 0); // WAW HAMZA
        form('\u0625', '\uFE87', '\uFE88', (char) 0, (char) 0); // ALEF HAMZA BELOW
        form('\u0626', '\uFE89', '\uFE8A', '\uFE8B', '\uFE8C'); // YEH HAMZA
        form('\u0627', '\uFE8D', '\uFE8E', (char) 0, (char) 0); // ALEF
        form('\u0628', '\uFE8F', '\uFE90', '\uFE91', '\uFE92'); // BEH
        form('\u0629', '\uFE93', '\uFE94', (char) 0, (char) 0); // TEH MARBUTA
        form('\u062A', '\uFE95', '\uFE96', '\uFE97', '\uFE98'); // TEH
        form('\u062B', '\uFE99', '\uFE9A', '\uFE9B', '\uFE9C'); // THEH
        form('\u062C', '\uFE9D', '\uFE9E', '\uFE9F', '\uFEA0'); // JEEM
        form('\u062D', '\uFEA1', '\uFEA2', '\uFEA3', '\uFEA4'); // HAH
        form('\u062E', '\uFEA5', '\uFEA6', '\uFEA7', '\uFEA8'); // KHAH
        form('\u062F', '\uFEA9', '\uFEAA', (char) 0, (char) 0); // DAL
        form('\u0630', '\uFEAB', '\uFEAC', (char) 0, (char) 0); // THAL
        form('\u0631', '\uFEAD', '\uFEAE', (char) 0, (char) 0); // REH
        form('\u0632', '\uFEAF', '\uFEB0', (char) 0, (char) 0); // ZAIN
        form('\u0633', '\uFEB1', '\uFEB2', '\uFEB3', '\uFEB4'); // SEEN
        form('\u0634', '\uFEB5', '\uFEB6', '\uFEB7', '\uFEB8'); // SHEEN
        form('\u0635', '\uFEB9', '\uFEBA', '\uFEBB', '\uFEBC'); // SAD
        form('\u0636', '\uFEBD', '\uFEBE', '\uFEBF', '\uFEC0'); // DAD
        form('\u0637', '\uFEC1', '\uFEC2', '\uFEC3', '\uFEC4'); // TAH
        form('\u0638', '\uFEC5', '\uFEC6', '\uFEC7', '\uFEC8'); // ZAH
        form('\u0639', '\uFEC9', '\uFECA', '\uFECB', '\uFECC'); // AIN
        form('\u063A', '\uFECD', '\uFECE', '\uFECF', '\uFED0'); // GHAIN
        form('\u0640', '\u0640', '\u0640', '\u0640', '\u0640'); // TATWEEL
        form('\u0641', '\uFED1', '\uFED2', '\uFED3', '\uFED4'); // FEH
        form('\u0642', '\uFED5', '\uFED6', '\uFED7', '\uFED8'); // QAF
        form('\u0643', '\uFED9', '\uFEDA', '\uFEDB', '\uFEDC'); // KAF
        form('\u0644', '\uFEDD', '\uFEDE', '\uFEDF', '\uFEE0'); // LAM
        form('\u0645', '\uFEE1', '\uFEE2', '\uFEE3', '\uFEE4'); // MEEM
        form('\u0646', '\uFEE5', '\uFEE6', '\uFEE7', '\uFEE8'); // NOON
        form('\u0647', '\uFEE9', '\uFEEA', '\uFEEB', '\uFEEC'); // HEH
        form('\u0648', '\uFEED', '\uFEEE', (char) 0, (char) 0); // WAW
        form('\u0649', '\uFEEF', '\uFEF0', (char) 0, (char) 0); // ALEF MAKSURA
        form('\u064A', '\uFEF1', '\uFEF2', '\uFEF3', '\uFEF4'); // YEH
        form('\u0671', '\uFB50', '\uFB51', (char) 0, (char) 0); // ALEF WASLA
    }

    private ArabicReshaper() {
    }

    public static boolean isArabicLetter(char c) {
        return c < FORMS.length && FORMS[c] != null;
    }

    /** Transparent marks never interrupt joining and never take a form themselves. */
    public static boolean isTransparentMark(char c) {
        return (c >= 0x064B && c <= 0x065F) || c == 0x0670 || c == 0x06D6 || (c >= 0x0610 && c <= 0x061A);
    }

    public static boolean isArabic(char c) {
        return (c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F) || (c >= 0xFB50 && c <= 0xFEFF);
    }

    public static boolean containsArabic(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            if (isArabic(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean joinsRight(char c) {
        char[] f = c < FORMS.length ? FORMS[c] : null;
        return f != null && f[2] != 0; // has an initial form => can connect to the following letter
    }

    private static boolean joinsLeft(char c) {
        char[] f = c < FORMS.length ? FORMS[c] : null;
        return f != null && f[1] != 0; // has a final form => can connect to the preceding letter
    }

    private static int prevBase(CharSequence s, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (isTransparentMark(c)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static int nextBase(CharSequence s, int index) {
        for (int i = index + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isTransparentMark(c)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    /**
     * Applies contextual forms in logical order. Lam followed by an alef variant becomes the
     * mandatory lam-alef ligature.
     */
    public static String shape(String logical) {
        StringBuilder out = new StringBuilder(logical.length() + 8);
        for (int i = 0; i < logical.length(); i++) {
            char c = logical.charAt(i);
            char[] forms = c < FORMS.length ? FORMS[c] : null;
            if (forms == null) {
                out.append(c);
                continue;
            }
            int prev = prevBase(logical, i);
            int next = nextBase(logical, i);
            boolean connectPrev = prev >= 0 && joinsRight(logical.charAt(prev));
            boolean connectNext = next >= 0 && joinsLeft(logical.charAt(next));

            // Mandatory lam-alef ligature (the only required composition in Modern Standard Arabic).
            if (c == '\u0644' && next >= 0) {
                char ligature = lamAlefLigature(logical.charAt(next), connectPrev);
                if (ligature != 0) {
                    out.append(ligature);
                    i = next;
                    continue;
                }
            }

            char shaped;
            if (connectPrev && connectNext) {
                shaped = forms[3] != 0 ? forms[3] : forms[0];
            } else if (connectPrev) {
                shaped = forms[1] != 0 ? forms[1] : forms[0];
            } else if (connectNext) {
                shaped = forms[2] != 0 ? forms[2] : forms[0];
            } else {
                shaped = forms[0];
            }
            out.append(shaped);
        }
        return out.toString();
    }

    private static char lamAlefLigature(char alef, boolean connectPrev) {
        switch (alef) {
            case '\u0622':
                return connectPrev ? '\uFEF6' : '\uFEF5';
            case '\u0623':
                return connectPrev ? '\uFEF8' : '\uFEF7';
            case '\u0625':
                return connectPrev ? '\uFEFA' : '\uFEF9';
            case '\u0627':
                return connectPrev ? '\uFEFC' : '\uFEFB';
            default:
                return 0;
        }
    }

    /** True when the first strong character of the text is right-to-left (Arabic). */
    public static boolean baseDirectionIsRtl(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isArabicLetter(c) || isArabic(c)) {
                return true;
            }
            if (Character.isLetter(c)) {
                return false;
            }
        }
        return false;
    }

    /**
     * Full pipeline for a left-to-right glyph renderer: shape, then emit visual order.
     *
     * <p>Runs of Arabic are shaped as one cursive run (joins are computed on the logical string, which
     * is why shaping happens before reordering); Latin words and digit groups stay embedded and
     * un-mirrored, exactly as BiDi requires for signage such as {@code "Al Wakrah 22 km"}.
     */
    public static String shapeForDisplay(String logical) {
        return reorderForDisplay(shape(logical), baseDirectionIsRtl(logical));
    }

    /**
     * Reorders an already shaped string into visual order for an LTR renderer.
     *
     * @param shaped       output of {@link #shape(String)}
     * @param baseRtl      paragraph base direction
     */
    public static String reorderForDisplay(String shaped, boolean baseRtl) {
        java.util.List<String> runs = splitRuns(shaped);
        java.util.List<Boolean> rtlFlags = runDirections(shaped);
        StringBuilder out = new StringBuilder(shaped.length() + 4);
        if (baseRtl) {
            for (int i = runs.size() - 1; i >= 0; i--) {
                appendRun(out, runs.get(i), rtlFlags.get(i));
            }
        } else {
            for (int i = 0; i < runs.size(); i++) {
                appendRun(out, runs.get(i), rtlFlags.get(i));
            }
        }
        return out.toString();
    }

    private static void appendRun(StringBuilder out, String run, boolean rtl) {
        if (rtl && run.length() > 1) {
            out.append(new StringBuilder(run).reverse());
        } else {
            out.append(run);
        }
    }

    private static java.util.List<String> splitRuns(String shaped) {
        java.util.List<String> runs = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentKind = -1; // 0 = RTL, 1 = LTR
        for (int i = 0; i < shaped.length(); i++) {
            char c = shaped.charAt(i);
            int kind = runKind(c);
            if (kind < 0) {
                // Neutral (space/punctuation) continues the current run so words are not shattered.
                if (current.length() > 0) {
                    current.append(c);
                } else {
                    runs.add(String.valueOf(c));
                }
                continue;
            }
            if (currentKind >= 0 && kind != currentKind && current.length() > 0) {
                runs.add(current.toString());
                current.setLength(0);
            }
            currentKind = kind;
            current.append(c);
        }
        if (current.length() > 0) {
            runs.add(current.toString());
        }
        return runs;
    }

    private static java.util.List<Boolean> runDirections(String shaped) {
        java.util.List<Boolean> flags = new java.util.ArrayList<>();
        for (String run : splitRuns(shaped)) {
            boolean rtl = false;
            for (int i = 0; i < run.length(); i++) {
                if (runKind(run.charAt(i)) == 0) {
                    rtl = true;
                    break;
                }
            }
            flags.add(rtl);
        }
        return flags;
    }

    /** 0 = strong RTL, 1 = strong LTR, -1 = neutral. */
    private static int runKind(char c) {
        if (isArabic(c)) {
            return 0;
        }
        if (Character.isLetterOrDigit(c)) {
            return 1;
        }
        return -1;
    }

    /** Number of glyph slots a string occupies after shaping (ligatures reduce it). */
    public static int visualLength(String logical) {
        return shapeForDisplay(logical).length();
    }
}
