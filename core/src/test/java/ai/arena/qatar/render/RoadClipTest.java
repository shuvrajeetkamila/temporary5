package ai.arena.qatar.render;

import ai.arena.qatar.world.Roads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The road ribbon is clipped to the chunk it is drawn in, and this holds the clipping to what it is
 * for: a chunk draws its own metres of a way and not its neighbour's, the two chunks that share an edge
 * meet exactly at that edge, and a way drawn whole is not moved by any of it.
 *
 * <p>The fixture is one straight 1200 m street with 13 points and a painted centre line, set at east
 * 150 m, so it crosses two 600 m rectangles end to end. Nothing here needs a bundle, a GPU or a device:
 * the mesher is arithmetic, so the arithmetic is checked.
 */
final class RoadClipTest {

    private static final double WAY_EAST = 150.0;
    private static final double WAY_LENGTH = 1200.0;
    private static final int POINTS = 13;                    // one every 100 m
    private static final float WIDTH_M = 14.0f;
    private static final float HALF_WIDTH = WIDTH_M * 0.5f;

    /** South half of the way: east [0, 600], north [0, 600]. */
    private static final double[] SOUTH = {0.0, 0.0, 600.0, 600.0};
    /** North half: east [0, 600], north [600, 1200]. */
    private static final double[] NORTH = {0.0, 600.0, 600.0, 1200.0};

    /** A straight marked street: 3 lanes, 14 m of asphalt, not one-way. */
    private static Roads street() {
        int[] lineStart = {0};
        int[] lineLength = {POINTS};
        float[] xz = new float[POINTS * 2];
        for (int i = 0; i < POINTS; i++) {
            xz[i * 2] = (float) WAY_EAST;
            xz[i * 2 + 1] = (float) (WAY_LENGTH * i / (POINTS - 1));
        }
        return new Roads(1, lineStart, lineLength, xz, new byte[]{3}, new byte[]{0}, new byte[]{3},
                new float[]{WIDTH_M}, new int[]{60}, new int[]{-1}, new int[]{-1},
                new float[]{WIDTH_M, WIDTH_M, (float) WAY_EAST, (float) WAY_LENGTH});
    }

    private static MeshData ribbon(double[] rect) {
        MeshData mesh = new MeshData();
        new RoadMesher(street(), null).addRoad(0, mesh, rect[0], rect[1], rect[2], rect[3]);
        return mesh;
    }

    @Test
    @DisplayName("a chunk draws its own stretch of a way and nothing beyond its own edge")
    void aChunkDrawsOnlyItsOwnStretch() {
        MeshData south = ribbon(SOUTH);
        // Six whole 100 m segments in 0..600 m: eight vertices each (ribbon and centre line).
        assertEquals(6 * 8, south.vertexCount(), "south half vertices");

        float[] v = south.vertices();
        float maxNorth = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < south.vertexCount(); i++) {
            int at = i * MeshData.FLOATS_PER_VERTEX;
            assertEquals(WAY_EAST, v[at], HALF_WIDTH + 0.01f, "a ribbon vertex left the road");
            maxNorth = Math.max(maxNorth, v[at + 2]);
            assertTrue(v[at + 2] <= 600.0f + 0.01f,
                    "vertex " + i + " is " + v[at + 2] + " m north: the ribbon is not clipped");
            assertTrue(v[at + 2] >= -0.01f, "vertex " + i + " is below the chunk");
        }
        assertEquals(600.0f, maxNorth, 0.01f, "the ribbon stops short of the chunk edge");

        // The whole way is 12 segments; the chunk draws half of it and pays for half of it.
        MeshData whole = new MeshData();
        new RoadMesher(street(), null).addRoad(0, whole);
        assertEquals(12 * 8, whole.vertexCount(), "whole way vertices");
        assertTrue(south.vertexCount() < whole.vertexCount(), "the clip saved nothing");
    }

    @Test
    @DisplayName("two chunks that share an edge meet exactly on it, with no gap and no overlap")
    void twoChunksMeetAtTheEdge() {
        MeshData south = ribbon(SOUTH);
        MeshData north = ribbon(NORTH);
        assertEquals(6 * 8, north.vertexCount(), "north half vertices");
        // Together the two chunks draw the whole way, once: the same vertices the whole way has.
        assertEquals(12 * 8, south.vertexCount() + north.vertexCount(), "the two halves do not add up");

        List<String> seamSouth = seam(south, 600.0f);
        List<String> seamNorth = seam(north, 600.0f);
        // Two ribbon corners and the two centre-line corners of the segment that ends on the edge.
        assertEquals(4, seamSouth.size(), "the south chunk does not end on the edge: " + seamSouth);
        assertEquals(seamSouth, seamNorth, "the two chunks do not agree on the edge");
    }

    /**
     * The vertices on the shared edge, as text sorted in a fixed order. Positions only -- what is
     * asserted is that the point is the same one, to the third decimal of a metre.
     */
    private static List<String> seam(MeshData mesh, float north) {
        List<String> found = new ArrayList<>();
        float[] v = mesh.vertices();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int at = i * MeshData.FLOATS_PER_VERTEX;
            if (Math.abs(v[at + 2] - north) <= 0.001f) {
                // Millimetres as integers: no locale, no rounding mode, and the same text on any machine.
                found.add(Math.round(v[at] * 1000f) + "/" + Math.round(v[at + 1] * 1000f));
            }
        }
        found.sort(String::compareTo);
        return found;
    }

    @Test
    @DisplayName("clipping a way that is already inside the rectangle changes nothing at all")
    void aRectangleThatContainsTheWayChangesNothing() {
        MeshData whole = new MeshData();
        new RoadMesher(street(), null).addRoad(0, whole);
        MeshData contained = ribbon(new double[]{-50.0, -50.0, 700.0, WAY_LENGTH + 50.0});
        assertEquals(whole.vertexCount(), contained.vertexCount(), "a clip that cuts nothing changed the count");
        float[] a = whole.vertices();
        float[] b = contained.vertices();
        for (int i = 0; i < whole.vertexCount() * MeshData.FLOATS_PER_VERTEX; i++) {
            assertEquals(a[i], b[i], 1e-4f, "vertex value " + i + " moved");
        }
    }

    @Test
    @DisplayName("the vertex cost of a clipped way is the cost of what the clip emits, and zero off it")
    void vertexCostFollowsTheClip() {
        Roads roads = street();
        assertEquals(8 * POINTS + 8, RoadMesher.vertexCost(roads, 0), "the whole way's cost");
        int south = RoadMesher.vertexCost(roads, 0, SOUTH[0], SOUTH[1], SOUTH[2], SOUTH[3]);
        assertTrue(south >= 6 * 8 && south <= 8 * 6 + 8, "south cost " + south);
        assertEquals(0, RoadMesher.vertexCost(roads, 0, 5000.0, 5000.0, 5600.0, 5600.0),
                "a way that is nowhere near the rectangle costs nothing");
    }

    @Test
    @DisplayName("the clipper agrees with brute force: inside the rectangle exactly between its bounds")
    void theClipperAgreesWithBruteForce() {
        Random random = new Random(20261015L);
        double[] interval = new double[2];
        int inside = 0;
        int outside = 0;
        for (int trial = 0; trial < 4000; trial++) {
            double ax = random.nextDouble() * 1600.0 - 500.0;
            double az = random.nextDouble() * 1600.0 - 500.0;
            double bx = random.nextDouble() * 1600.0 - 500.0;
            double bz = random.nextDouble() * 1600.0 - 500.0;
            if (Math.hypot(bx - ax, bz - az) < 1.0) {
                continue;
            }
            boolean clipped = RoadMesher.clipSegment(ax, az, bx, bz,
                    SOUTH[0], SOUTH[1], SOUTH[2], SOUTH[3], interval);
            if (clipped) {
                inside++;
                double t0 = interval[0];
                double t1 = interval[1];
                assertTrue(t0 >= -1e-9 && t1 <= 1.0 + 1e-9 && t0 <= t1,
                        "interval " + t0 + ".." + t1 + " is not a piece of the segment");
                assertTrue(inside(ax + (bx - ax) * t0, az + (bz - az) * t0), "the start of the piece is outside");
                assertTrue(inside(ax + (bx - ax) * t1, az + (bz - az) * t1), "the end of the piece is outside");
                // Nothing sampled beyond the piece, and the piece is as long as it can be.
                for (int step = 0; step <= 400; step++) {
                    double t = step / 400.0;
                    if (inside(ax + (bx - ax) * t, az + (bz - az) * t)) {
                        assertTrue(t >= t0 - 1e-6 && t <= t1 + 1e-6,
                                "the piece misses an inside sample at t=" + t + " (" + t0 + ".." + t1 + ")");
                    }
                }
            } else {
                outside++;
                for (int step = 0; step <= 400; step++) {
                    double t = step / 400.0;
                    assertFalse(inside(ax + (bx - ax) * t, az + (bz - az) * t),
                            "a segment the clipper rejected has an inside sample at t=" + t);
                }
            }
        }
        assertTrue(inside > 200, "too few segments crossed the rectangle to prove anything: " + inside);
        assertTrue(outside > 200, "too few segments missed the rectangle to prove anything: " + outside);
    }

    /**
     * Whether a point is in the rectangle. The tolerance is a micrometre rather than a millimetre on
     * purpose: a segment the clipper rejected may pass arbitrarily close to the edge, and calling such a
     * point inside would fail a test for being exactly what it is.
     */
    private static boolean inside(double east, double north) {
        return east >= SOUTH[0] - 1e-6 && east <= SOUTH[2] + 1e-6
                && north >= SOUTH[1] - 1e-6 && north <= SOUTH[3] + 1e-6;
    }
}
