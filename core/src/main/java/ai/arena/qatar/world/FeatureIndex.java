package ai.arena.qatar.world;

/**
 * A uniform grid over the national frame that answers "which features are near this point?" without
 * scanning the country.
 *
 * <p>Storage is compressed-sparse-row: one {@code int[]} of per-cell start offsets and one of item
 * ids. Both feature types with a bounding box (buildings, roads, land use, water, barriers) and plain
 * points can be indexed with the same code, which is why it takes raw bounds arrays rather than a
 * feature type.
 *
 * <p>Queries report candidates by bounding box, not exact geometry: the caller does the precise test
 * on the handful of candidates. That is the right split -- the index is about not touching 190,000
 * buildings to find the four in front of you.
 */
public final class FeatureIndex {

    public interface Visitor {
        /** Return false to stop the walk early (the search found what it needed). */
        boolean visit(int id);
    }

    private final double originEast;
    private final double originNorth;
    private final double cellM;
    private final int cols;
    private final int rows;
    private final int[] cellStart;
    private final int[] items;

    /**
     * Builds an index over {@code count} features described by {@code bounds} (4 floats each:
     * minEast, minNorth, maxEast, maxNorth) in grid-origin relative metres.
     */
    public FeatureIndex(float[] bounds, int count, double originEast, double originNorth,
                        double widthM, double heightM, double cellM) {
        this.originEast = originEast;
        this.originNorth = originNorth;
        this.cellM = cellM;
        this.cols = Math.max(1, (int) Math.ceil(widthM / cellM));
        this.rows = Math.max(1, (int) Math.ceil(heightM / cellM));
        int cells = cols * rows;

        int[] counts = new int[cells + 1];
        for (int id = 0; id < count; id++) {
            int c0 = column(bounds[id * 4]);
            int c1 = column(bounds[id * 4 + 2]);
            int r0 = row(bounds[id * 4 + 1]);
            int r1 = row(bounds[id * 4 + 3]);
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    counts[r * cols + c + 1]++;
                }
            }
        }
        for (int i = 0; i < cells; i++) {
            counts[i + 1] += counts[i];
        }
        cellStart = counts;
        items = new int[cellStart[cells]];
        int[] cursor = new int[cells];
        for (int id = 0; id < count; id++) {
            int c0 = column(bounds[id * 4]);
            int c1 = column(bounds[id * 4 + 2]);
            int r0 = row(bounds[id * 4 + 1]);
            int r1 = row(bounds[id * 4 + 3]);
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    int cell = r * cols + c;
                    items[cellStart[cell] + cursor[cell]++] = id;
                }
            }
        }
    }

    public int cellCount() {
        return cols * rows;
    }

    public int itemCount() {
        return items.length;
    }

    // Bounds, queries and the features themselves are all in bundle space (metres from the grid
    // origin), so cell maths is a plain division -- and the stored origin exists only so callers can
    // tell which world frame this index describes.
    private int column(double eastRelative) {
        int value = (int) Math.floor(eastRelative / cellM);
        return value < 0 ? 0 : (value >= cols ? cols - 1 : value);
    }

    private int row(double northRelative) {
        int value = (int) Math.floor(northRelative / cellM);
        return value < 0 ? 0 : (value >= rows ? rows - 1 : value);
    }

    public double originEast() {
        return originEast;
    }

    public double originNorth() {
        return originNorth;
    }

    /** Visits every indexed feature whose bounding box overlaps the query rectangle. */
    public void query(double minEast, double minNorth, double maxEast, double maxNorth, Visitor visitor) {
        int c0 = column(minEast);
        int c1 = column(maxEast);
        int r0 = row(minNorth);
        int r1 = row(maxNorth);
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                int cell = r * cols + c;
                int end = cellStart[cell + 1];
                for (int i = cellStart[cell]; i < end; i++) {
                    if (!visitor.visit(items[i])) {
                        return;
                    }
                }
            }
        }
    }

    /** Convenience for tests and tools: the candidate id list for a rectangle. */
    public int[] collect(double minEast, double minNorth, double maxEast, double maxNorth) {
        java.util.ArrayList<Integer> found = new java.util.ArrayList<>();
        query(minEast, minNorth, maxEast, maxNorth, id -> {
            found.add(id);
            return true;
        });
        int[] out = new int[found.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = found.get(i);
        }
        return out;
    }
}
