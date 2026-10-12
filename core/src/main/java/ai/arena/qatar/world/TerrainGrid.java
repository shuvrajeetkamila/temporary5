package ai.arena.qatar.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * A compiled elevation raster for the national grid. Row 0 is the <b>southernmost</b> row and column
 * 0 the westernmost, matching the compiler's convention: {@code north = originNorth + row * cellM}.
 *
 * <p>Heights are stored as int16 metres; the land mask distinguishes land from water so the renderer
 * and the collision sampler agree about where the coast is.
 */
public final class TerrainGrid {

    public static final int HEADER_BYTES = 20; // magic(4) + cols(4) + rows(4) + cellM(4) + reserved(4)

    public final String magic;
    public final int cols;
    public final int rows;
    public final double cellM;
    public final double originEast;
    public final double originNorth;
    public final short[] heights;
    public final byte[] landMask;

    public TerrainGrid(String magic, int cols, int rows, double cellM, double originEast, double originNorth,
                       short[] heights, byte[] landMask) {
        this.magic = magic;
        this.cols = cols;
        this.rows = rows;
        this.cellM = cellM;
        this.originEast = originEast;
        this.originNorth = originNorth;
        this.heights = heights;
        this.landMask = landMask;
    }

    /** Parses the {@code QTT1} terrain container emitted by {@code tools/build_world.py}. */
    public static TerrainGrid parse(byte[] data, double originEast, double originNorth, String expectedMagic) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < HEADER_BYTES) {
            throw new IllegalArgumentException("terrain payload too small: " + data.length);
        }
        byte[] magicBytes = new byte[4];
        buf.get(magicBytes);
        String magic = new String(magicBytes, java.nio.charset.StandardCharsets.US_ASCII);
        if (expectedMagic != null && !expectedMagic.equals(magic)) {
            throw new IllegalArgumentException("unexpected terrain magic '" + magic + "', expected '"
                    + expectedMagic + "'");
        }
        int cols = buf.getInt();
        int rows = buf.getInt();
        float cell = buf.getFloat();
        buf.getInt();
        if (cols <= 0 || rows <= 0) {
            throw new IllegalArgumentException("invalid terrain dimensions " + cols + "x" + rows);
        }
        long expected = (long) cols * rows * 3L + HEADER_BYTES;
        if (data.length < expected) {
            throw new IllegalArgumentException("terrain payload truncated: " + data.length + " < " + expected);
        }
        ShortBuffer shorts = buf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        short[] heights = new short[cols * rows];
        shorts.get(heights);
        byte[] mask = new byte[cols * rows];
        System.arraycopy(data, HEADER_BYTES + cols * rows * 2, mask, 0, cols * rows);
        return new TerrainGrid(magic, cols, rows, cell, originEast, originNorth, heights, mask);
    }

    public boolean isLand(int row, int col) {
        return landMask[row * cols + col] != 0;
    }

    /** Raw (nearest-cell) height in metres at global EPSG:32639 east/north, clamped at the edges. */
    public double heightNearest(double globalEastM, double globalNorthM) {
        int col = (int) Math.floor((globalEastM - originEast) / cellM);
        int row = (int) Math.floor((globalNorthM - originNorth) / cellM);
        col = clamp(col, 0, cols - 1);
        row = clamp(row, 0, rows - 1);
        return heights[row * cols + col];
    }

    /**
     * Bilinear height sample in metres at global EPSG:32639 east/north; never returns NaN.
     *
     * <p>The grid works in global coordinates on purpose: the renderer, the solver and the compiler
     * then agree on cell indices no matter how the floating origin has been rebased. Converting
     * local float metres to global doubles happens once, at the sampler boundary.
     */
    public double heightBilinear(double globalEastM, double globalNorthM) {
        double fx = (globalEastM - originEast) / cellM - 0.5;
        double fz = (globalNorthM - originNorth) / cellM - 0.5;
        int c0 = (int) Math.floor(fx);
        int r0 = (int) Math.floor(fz);
        double tx = fx - c0;
        double tz = fz - r0;
        int c1 = clamp(c0 + 1, 0, cols - 1);
        int r1 = clamp(r0 + 1, 0, rows - 1);
        c0 = clamp(c0, 0, cols - 1);
        r0 = clamp(r0, 0, rows - 1);
        double h00 = heights[r0 * cols + c0];
        double h01 = heights[r0 * cols + c1];
        double h10 = heights[r1 * cols + c0];
        double h11 = heights[r1 * cols + c1];
        double top = h00 + (h01 - h00) * tx;
        double bottom = h10 + (h11 - h10) * tx;
        return top + (bottom - top) * tz;
    }

    public boolean landBilinear(double globalEastM, double globalNorthM) {
        int col = clamp((int) Math.floor((globalEastM - originEast) / cellM), 0, cols - 1);
        int row = clamp((int) Math.floor((globalNorthM - originNorth) / cellM), 0, rows - 1);
        return landMask[row * cols + col] != 0;
    }

    /** Height gradient (dH/dx, dH/dz) at global east/north, for surface normals. */
    public double[] heightGradient(double globalEastM, double globalNorthM, double h) {
        double[] out = new double[2];
        out[0] = (heightBilinear(globalEastM + h, globalNorthM)
                - heightBilinear(globalEastM - h, globalNorthM)) / (2 * h);
        out[1] = (heightBilinear(globalEastM, globalNorthM + h)
                - heightBilinear(globalEastM, globalNorthM - h)) / (2 * h);
        return out;
    }

    public boolean contains(double east, double north) {
        return east >= originEast && east <= originEast + cols * cellM
                && north >= originNorth && north <= originNorth + rows * cellM;
    }

    public double maxEast() {
        return originEast + cols * cellM;
    }

    public double maxNorth() {
        return originNorth + rows * cellM;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
