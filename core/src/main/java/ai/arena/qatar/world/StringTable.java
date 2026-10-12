package ai.arena.qatar.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The compiled UTF-8 string table ({@code QTS1}): every road name, municipality, island and landmark
 * label in both English and Arabic. Arabic strings are stored in <b>logical</b> order and shaped at
 * display time by {@code ArabicReshaper}, so the table never contains pre-reversed text.
 */
public final class StringTable {

    public static final int HEADER_BYTES = 12; // magic(4) + version(4) + count(4)

    private final List<String> values = new ArrayList<>();

    public static StringTable parse(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < HEADER_BYTES) {
            throw new IllegalArgumentException("string table too small");
        }
        byte[] magic = new byte[4];
        buf.get(magic);
        if (!"QTS1".equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("unexpected string table magic "
                    + new String(magic, StandardCharsets.US_ASCII));
        }
        int version = buf.getInt();
        if (version != 1) {
            throw new IllegalArgumentException("unsupported string table version " + version);
        }
        int count = buf.getInt();
        StringTable table = new StringTable();
        for (int i = 0; i < count; i++) {
            if (buf.remaining() < 2) {
                throw new IllegalArgumentException("string table truncated at entry " + i);
            }
            int length = buf.getShort() & 0xFFFF;
            if (buf.remaining() < length) {
                throw new IllegalArgumentException("string table truncated inside entry " + i);
            }
            byte[] raw = new byte[length];
            buf.get(raw);
            table.values.add(new String(raw, StandardCharsets.UTF_8));
        }
        return table;
    }

    /** Missing entries (0xFFFF) and out-of-range indices return an empty string, never null. */
    public String get(int index) {
        if (index < 0 || index == 0xFFFF || index >= values.size()) {
            return "";
        }
        return values.get(index);
    }

    /** Bilingual pair with a documented fallback: never render a mixture or gibberish. */
    public String english(int index) {
        return get(index);
    }

    public String arabic(int index) {
        return get(index);
    }

    /** Picks Arabic when the user prefers it and the Arabic string exists, else English. */
    public String best(int nameIdx, int nameArIdx, boolean preferArabic) {
        String ar = arabic(nameArIdx);
        String en = english(nameIdx);
        if (preferArabic && !ar.isEmpty()) {
            return ar;
        }
        if (!en.isEmpty()) {
            return en;
        }
        return ar;
    }

    public int size() {
        return values.size();
    }
}
