package ai.arena.qatar.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Surface classification raster (bundle {@code QTM1}): one material id per terrain cell.
 *
 * <p>Same geometry as the terrain grid it accompanies, so a height sample and a material sample at the
 * same coordinates always describe the same cell. The id indexes into {@link ai.arena.qatar.physics.MaterialRegistry},
 * which is where friction, dust and audio properties live.
 */
public final class MaterialGrid {

    public static final int HEADER_BYTES = 20;

    public final int cols;
    public final int rows;
    public final double cellM;
    public final double originEast;
    public final double originNorth;
    public final byte[] ids;

    public MaterialGrid(int cols, int rows, double cellM, double originEast, double originNorth,
                        byte[] ids) {
        this.cols = cols;
        this.rows = rows;
        this.cellM = cellM;
        this.originEast = originEast;
        this.originNorth = originNorth;
        this.ids = ids;
    }

    public static MaterialGrid parse(byte[] data, double originEast, double originNorth) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magicBytes = new byte[4];
        buf.get(magicBytes);
        String magic = new String(magicBytes, java.nio.charset.StandardCharsets.US_ASCII);
        if (!"QTM1".equals(magic)) {
            throw new IllegalArgumentException("unexpected material magic '" + magic + "'");
        }
        buf.getInt();
        int cols = buf.getInt();
        int rows = buf.getInt();
        float cell = buf.getFloat();
        buf.getInt();
        if (cols <= 0 || rows <= 0) {
            throw new IllegalArgumentException("invalid material dimensions " + cols + "x" + rows);
        }
        long expected = (long) cols * rows + HEADER_BYTES;
        if (data.length < expected) {
            throw new IllegalArgumentException("material payload truncated: " + data.length + " < " + expected);
        }
        byte[] ids = new byte[cols * rows];
        System.arraycopy(data, HEADER_BYTES, ids, 0, cols * rows);
        return new MaterialGrid(cols, rows, cell, originEast, originNorth, ids);
    }

    /** Material id at global EPSG:32639 east/north, clamped to the grid. */
    public int idAt(double globalEastM, double globalNorthM) {
        int col = clamp((int) Math.floor((globalEastM - originEast) / cellM), 0, cols - 1);
        int row = clamp((int) Math.floor((globalNorthM - originNorth) / cellM), 0, rows - 1);
        return ids[row * cols + col] & 0xFF;
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }
}
