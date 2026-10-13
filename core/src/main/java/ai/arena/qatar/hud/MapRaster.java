package ai.arena.qatar.hud;

import ai.arena.qatar.world.MaterialGrid;
import ai.arena.qatar.world.Points;
import ai.arena.qatar.world.Roads;
import ai.arena.qatar.world.TerrainGrid;
import ai.arena.qatar.world.WorldData;

/**
 * Renders the whole country into a pixel buffer, once, for the minimap and the full map.
 *
 * <p>Both maps are views of the same image: the minimap shows a corner of it, the full map shows all of
 * it with pan and zoom. That is why a tap on the full map can be turned into a world position with a
 * single linear mapping, with no projections, no tiles and no network.
 *
 * <p>The raster is built from the compiled data itself -- terrain heights, material ids, road geometry
 * and points of interest -- so it shows the real country: the peninsula, the islands, the sabkha flats
 * and the city grid.
 */
public final class MapRaster {

    public final int width;
    public final int height;
    /** ARGB pixels, row 0 at the south (the same convention as the terrain grid). */
    public final int[] pixels;

    private final double originEast;
    private final double originNorth;
    private final double extentEast;
    private final double extentNorth;

    private MapRaster(int width, int height, double originEast, double originNorth, double extentEast,
                      double extentNorth) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
        this.originEast = originEast;
        this.originNorth = originNorth;
        this.extentEast = extentEast;
        this.extentNorth = extentNorth;
    }

    /**
     * Builds a raster of the world at the given resolution.
     *
     * @param resolution pixels along the longer axis; the raster keeps the world's aspect ratio
     */
    public static MapRaster build(WorldData world, int resolution) {
        TerrainGrid terrain = world.terrainL1;
        MaterialGrid materials = world.materials;
        double widthM = terrain.cols * terrain.cellM;
        double heightM = terrain.rows * terrain.cellM;
        boolean wider = widthM >= heightM;
        int width = wider ? resolution : Math.max(64, (int) Math.round(resolution * widthM / heightM));
        int height = wider ? Math.max(64, (int) Math.round(resolution * heightM / widthM)) : resolution;

        MapRaster raster = new MapRaster(width, height, 0, 0, widthM, heightM);

        // Ground: one pixel per sample, taken straight from the compiled material grid.
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double east = (x + 0.5) * widthM / width;
                double north = (y + 0.5) * heightM / height;
                int col = (int) (east / terrain.cellM);
                int row = (int) (north / terrain.cellM);
                col = clamp(col, 0, terrain.cols - 1);
                row = clamp(row, 0, terrain.rows - 1);
                int index = row * terrain.cols + col;
                int colour;
                if (terrain.landMask[index] == 0) {
                    colour = materials != null && materials.ids[index] == 15
                            ? Palette.WATER_SHALLOW : Palette.WATER_SEA;
                } else {
                    int material = materials != null ? materials.ids[index] & 0xFF : 5;
                    colour = Palette.material(material);
                    double h = terrain.heights[index];
                    // Relief: higher ground catches the light, which makes the dunes readable.
                    colour = Palette.brighten(colour, Math.min(0.30, Math.max(-0.08, h / 120.0 * 0.30)));
                }
                raster.pixels[y * width + x] = 0xFF000000 | colour;
            }
        }

        raster.drawRoads(world.roads, widthM, heightM);
        raster.drawInlandWater(world.water, widthM, heightM);
        raster.drawPoints(world.points, widthM, heightM);
        return raster;
    }

    private void drawRoads(Roads roads, double widthM, double heightM) {
        if (roads == null) {
            return;
        }
        for (int line = 0; line < roads.count; line++) {
            int start = roads.lineStart[line];
            int points = roads.lineLength[line];
            int colour = Palette.road(roads.roadClass[line]);
            for (int i = 0; i < points - 1; i++) {
                double ax = roads.xz[(start + i) * 2];
                double az = roads.xz[(start + i) * 2 + 1];
                double bx = roads.xz[(start + i + 1) * 2];
                double bz = roads.xz[(start + i + 1) * 2 + 1];
                drawLine(ax, az, bx, bz, widthM, heightM, colour);
            }
        }
    }

    private void drawInlandWater(ai.arena.qatar.world.Areas water, double widthM, double heightM) {
        if (water == null) {
            return;
        }
        for (int ring = 0; ring < water.count; ring++) {
            int colour = water.kind[ring] == 2 ? Palette.WATER_WADI : Palette.WATER_INLAND;
            int start = water.ringStart[ring];
            int points = water.ringLength[ring];
            for (int i = 0; i < points - 1; i++) {
                drawLine(water.xz[(start + i) * 2], water.xz[(start + i) * 2 + 1],
                        water.xz[(start + i + 1) * 2], water.xz[(start + i + 1) * 2 + 1],
                        widthM, heightM, colour);
            }
        }
    }

    private void drawPoints(Points points, double widthM, double heightM) {
        if (points == null) {
            return;
        }
        for (int i = 0; i < points.count; i++) {
            int kind = points.kind[i];
            int colour = kind == Points.KIND_LANDMARK ? Palette.LANDMARK : Palette.SPAWN;
            int x = (int) (points.xz[i * 2] / widthM * width);
            int y = (int) (points.xz[i * 2 + 1] / heightM * height);
            plot(x, y, colour);
        }
    }

    private void drawLine(double ax, double az, double bx, double bz, double widthM, double heightM,
                          int colour) {
        double x0 = ax / widthM * width;
        double y0 = az / heightM * height;
        double x1 = bx / widthM * width;
        double y1 = bz / heightM * height;
        int steps = (int) Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) + 1;
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : i / (double) steps;
            plot((int) Math.round(x0 + (x1 - x0) * t), (int) Math.round(y0 + (y1 - y0) * t), colour);
        }
    }

    /** Sets a pixel with bounds checking: map drawing must never throw on an edge case. */
    public void plot(int x, int y, int rgb) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return;
        }
        pixels[y * width + x] = 0xFF000000 | rgb;
    }

    public int pixelAt(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return 0;
        }
        return pixels[y * width + x];
    }

    /** Fractional map coordinates (0..1 on each axis) for a world position in bundle metres. */
    public double u(double east, double north) {
        return extentEast <= 0 ? 0 : east / extentEast;
    }

    public double v(double east, double north) {
        return extentNorth <= 0 ? 0 : north / extentNorth;
    }

    /** World position for fractional map coordinates; the inverse of {@link #u} and {@link #v}. */
    public double eastAt(double u) {
        return u * extentEast;
    }

    public double northAt(double v) {
        return v * extentNorth;
    }

    public double widthM() {
        return extentEast;
    }

    public double heightM() {
        return extentNorth;
    }

    public double originEast() {
        return originEast;
    }

    public double originNorth() {
        return originNorth;
    }

    /** True when the pixel is water: the teleport logic refuses to drop the player into the sea. */
    public boolean isWaterAt(double east, double north) {
        int x = (int) (u(east, north) * width);
        int y = (int) (v(east, north) * height);
        int rgb = pixelAt(x, y) & 0xFFFFFF;
        return rgb == Palette.WATER_SEA || rgb == Palette.WATER_SHALLOW || rgb == Palette.WATER_INLAND;
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }
}
