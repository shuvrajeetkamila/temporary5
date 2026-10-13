package ai.arena.qatar.hud;

/**
 * The colour table shared by the minimap and the full map, and the Java twin of the palette in the
 * world shader.
 *
 * <p>The map colours a cell by its compiled surface material, exactly like the terrain does, so the map
 * and the world never disagree about where the sabkha ends and the dunes begin. Keeping this in one
 * Java class means the minimap and the full map cannot drift apart either.
 */
public final class Palette {

    private Palette() {
    }

    /** Packed 0xRRGGBB for each compiled material id (see tools/build_world.py MATERIAL). */
    private static final int[] MATERIAL_RGB = {
            0x6B6152, // 0  unknown: neutral desert grey
            0x333336, // 1  dry asphalt
            0x3B3A38, // 2  dusty asphalt
            0x2B2E33, // 3  wet asphalt
            0x9E9C95, // 4  concrete pavers
            0x94857A, // 5  gravel and rock
            0xC9B189, // 6  compact sand
            0xDCC79A, // 7  loose dune sand
            0xA89976, // 8  wet sand
            0xC7C4B6, // 9  sabkha (salt flat)
            0x6B6152, // 10 tidal mud
            0x3E5B39, // 11 mangrove and marsh
            0x6E7846, // 12 farm soil
            0xA8A69F, // 13 urban pad
            0xB3B0A7, // 14 reclaimed land
            0x59A3B0, // 15 shallow water
            0x1B4E74, // 16 open water
    };

    /** Rivers, lakes and the sea on the map; matches the mesh's water surface hue. */
    public static final int WATER_SEA = 0x1B4E74;
    public static final int WATER_SHALLOW = 0x59A3B0;
    public static final int WATER_INLAND = 0x2C6E8F;
    public static final int WATER_WADI = 0x8A7E63;
    public static final int ROAD = 0x4A4340;
    public static final int ROAD_MAJOR = 0xE0B23C;
    public static final int BACKGROUND = 0x101820;
    public static final int LANDMARK = 0xE0552F;
    public static final int SPAWN = 0x2FD07A;
    public static final int PLAYER = 0xFF3B30;

    public static int material(int id) {
        if (id < 0 || id >= MATERIAL_RGB.length) {
            return MATERIAL_RGB[0];
        }
        return MATERIAL_RGB[id];
    }

    /** Roads are drawn brighter the more important they are, which is how a map reads. */
    public static int road(int roadClass) {
        return roadClass <= 5 ? ROAD_MAJOR : ROAD;
    }

    /** Blends two colours, {@code t} = 0 gives {@code a}, 1 gives {@code b}. */
    public static int mix(int a, int b, double t) {
        double clamped = t < 0 ? 0 : (t > 1 ? 1 : t);
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = (int) Math.round(ar + (br - ar) * clamped);
        int g = (int) Math.round(ag + (bg - ag) * clamped);
        int bl = (int) Math.round(ab + (bb - ab) * clamped);
        return (r << 16) | (g << 8) | bl;
    }

    /** Shading for terrain relief on the map: slope lightens the sand towards bare rock. */
    public static int shaded(int base, double slope) {
        int rock = 0x94857A;
        return mix(base, rock, Math.min(1.0, Math.max(0.0, slope)) * 0.55);
    }

    /** Brightens (positive) or darkens (negative) a colour by a fraction. Used for dune relief. */
    public static int brighten(int rgb, double amount) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        double factor = 1.0 + amount;
        r = clampChannel((int) Math.round(r * factor));
        g = clampChannel((int) Math.round(g * factor));
        b = clampChannel((int) Math.round(b * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static int clampChannel(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }
}
