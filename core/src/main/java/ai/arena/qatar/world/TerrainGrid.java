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

    /** Raw (nearest-cell) height in metres, clamped at the grid edges. */
    public double heightNearest(double localX, double localZ) {
        int col = (int) Math.floor((localX - originEast) / cellM);
        int row = (int) Math.floor((localZ - originNorth) / cellM);
        col = clamp(col, 0, cols - 1);
        row = clamp(row, 0, rows - 1);
        return heights[row * cols + col];
    }

    /** Bilinear height sample in metres; never returns NaN. */
    public double heightBilinear(double localX, double localZ) {
        double fx = (localX - originEast) / cellM - 0.5;
        double fz = (localZ - originNorth) / cellM - 0.5;
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

    public boolean landBilinear(double localX, double localZ) {
        int col = clamp((int) Math.floor((localX - originEast) / cellM), 0, cols - 1);
        int row = clamp((int) Math.floor((localZ - originNorth) / cellM), 0, rows - 1);
        return landMask[row * cols + col] != 0;
    }

    /** Height gradient (dH/dx, dH/dz), used to derive surface normals consistently with rendering. */
    public double[] heightGradient(double localX, double localZ, double h) {
        double[] out = new double[2];
        out[0] = (heightBilinear(localX + h, localZ) - heightBilinear(localX - h, localZ)) / (2 * h);
        out[1] = (heightBilinear(localX, localZ + h) - heightBilinear(localX, localZ - h)) / (2 * h);
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
