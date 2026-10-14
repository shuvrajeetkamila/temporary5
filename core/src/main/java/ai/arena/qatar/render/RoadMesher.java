package ai.arena.qatar.render;

import ai.arena.qatar.world.Roads;
import ai.arena.qatar.world.TerrainGrid;

/**
 * Turns road centre-lines into drivable ribbons: a surface per lane group, a kerb line, and painted
 * centre lines, all draped onto the terrain.
 *
 * <p>Draping is what stops roads from floating. Every ribbon vertex samples the compiled heightfield
 * and sits a few centimetres above it, and the ribbon's cross-slope follows the terrain gradient, so a
 * road up a dune looks like a road up a dune. Bridges are the exception: they interpolate straight
 * between their two ends, because a bridge deck is built level, not draped.
 *
 * <p>Widths come from OSM (or the road-class default when untagged), so a 4-lane primary road is
 * visibly wider than a service alley -- and the same numbers will drive traffic lanes later.
 *
 * <p>A ribbon drawn into a chunk is <b>clipped to that chunk</b>. It used to be emitted whole by every
 * chunk it overlapped, so a 30 km highway was drawn thirty times over, cost thirty times the vertices,
 * and -- because a whole way can be longer than any index buffer -- was dropped from every chunk at once
 * by the vertex budget. Now each chunk draws its own metres of a way plus the two segments that cross
 * its edge, and the clip points are computed from the same polyline and the same interpolation on both
 * sides of the edge, so the two halves meet exactly and the seam does not show.
 */
public final class RoadMesher {

    public static final float SURFACE_ROAD = 4.0f;
    public static final float SURFACE_MARKING = 5.0f;

    /** Ribbon lift above the terrain, to keep the paint out of the ground. */
    private static final float LIFT = 0.06f;
    /** Half-width of a painted centre line, in metres. */
    private static final float MARKING_HALF_WIDTH = 0.09f;

    private final Roads roads;
    private final TerrainGrid terrain;

    public RoadMesher(Roads roads, TerrainGrid terrain) {
        this.roads = roads;
        this.terrain = terrain;
    }

    public int mesh(double minEast, double minNorth, double maxEast, double maxNorth, MeshData mesh,
                    int[] candidates, int candidateCount) {
        int emitted = 0;
        for (int i = 0; i < candidateCount; i++) {
            int line = candidates[i];
            if (line < 0 || line >= roads.count) {
                continue;
            }
            if (!overlaps(line, minEast, minNorth, maxEast, maxNorth)) {
                continue;
            }
            if (!mesh.hasRoom(vertexCost(roads, line, minEast, minNorth, maxEast, maxNorth))) {
                // A way is a polyline: one way can need hundreds of vertices, so the budget question
                // has to be asked about the way, not about a generic quad -- and asked about the part
                // of the way that lands in this rectangle, which is the part that will be drawn.
                break;
            }
            addRoad(line, mesh, minEast, minNorth, maxEast, maxNorth);
            emitted++;
        }
        return emitted;
    }

    /**
     * Whether this way overlaps the rectangle at all.
     *
     * <p>Public because {@link ChunkMesher} walks the ways itself: it has to size each one before
     * adding it (8 vertices per segment, ribbon and markings) so a nearly full mesh is never handed a
     * long polyline to overflow. The loop in {@link #mesh} below asks {@code isFull()} once per way,
     * which is safe only for the callers that use it with a whole range instead of a chunk.
     */
    public boolean overlaps(int line, double minEast, double minNorth, double maxEast, double maxNorth) {
        return roads.bounds[line * 4] <= maxEast && roads.bounds[line * 4 + 2] >= minEast
                && roads.bounds[line * 4 + 1] <= maxNorth && roads.bounds[line * 4 + 3] >= minNorth;
    }

    /**
     * Vertices a way will need: four per segment for the ribbon, four more when it is marked.
     *
     * <p>Reported without clamping, so the caller can tell a way that fits a part from a way that fits
     * no part at all -- the second kind must be dropped, not started.
     */
    public static int vertexCost(Roads roads, int line) {
        long cost = 8L * roads.lineLength[line] + 8L;
        return cost > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) cost;
    }

    /**
     * Vertices the part of a way inside the rectangle needs: four per segment for the ribbon, four more
     * when it is marked, and <b>nothing for the segments that are not in the rectangle</b>.
     *
     * <p>This is the question a chunk asks. The whole-way cost of a 30 km highway is larger than any
     * mesh, so asking it there would drop the highway from every chunk; asking this one sizes the
     * handful of segments that actually cross the chunk. Zero means no part of the way is here at all.
     */
    public static int vertexCost(Roads roads, int line, double minEast, double minNorth,
                                 double maxEast, double maxNorth) {
        int start = roads.lineStart[line];
        int points = roads.lineLength[line];
        double[] interval = new double[2];
        int segments = 0;
        for (int i = 0; i + 1 < points; i++) {
            if (clipSegment(roads.xz[(start + i) * 2], roads.xz[(start + i) * 2 + 1],
                    roads.xz[(start + i + 1) * 2], roads.xz[(start + i + 1) * 2 + 1],
                    minEast, minNorth, maxEast, maxNorth, interval)
                    && interval[1] - interval[0] > 1e-6) {
                segments++;
            }
        }
        long cost = 8L * segments + 8L;
        return segments == 0 ? 0 : (cost > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) cost);
    }

    /**
     * Where a segment lies inside a rectangle, as fractions {@code out[0]}..{@code out[1]} of the way
     * from its start point. Returns false when the segment misses the rectangle entirely.
     *
     * <p>Liang-Barsky: each of the four edges cuts the parameter interval down, and an interval that
     * runs out means the segment is outside. Two chunks that share an edge pass the same crossing
     * coordinate, so they clip the same segment at the same point.
     */
    public static boolean clipSegment(double ax, double az, double bx, double bz,
                                      double minEast, double minNorth, double maxEast, double maxNorth,
                                      double[] out) {
        double dx = bx - ax;
        double dz = bz - az;
        double t0 = 0.0;
        double t1 = 1.0;
        if (dx == 0.0) {
            if (ax < minEast || ax > maxEast) {
                return false;                         // parallel to the east edge, and outside it
            }
        } else if (dx > 0.0) {
            double entering = (minEast - ax) / dx;
            double leaving = (maxEast - ax) / dx;
            if (entering > t0) {
                t0 = entering;
            }
            if (leaving < t1) {
                t1 = leaving;
            }
            if (t0 > t1) {
                return false;
            }
        } else {
            double entering = (maxEast - ax) / dx;
            double leaving = (minEast - ax) / dx;
            if (entering > t0) {
                t0 = entering;
            }
            if (leaving < t1) {
                t1 = leaving;
            }
            if (t0 > t1) {
                return false;
            }
        }
        if (dz == 0.0) {
            if (az < minNorth || az > maxNorth) {
                return false;
            }
        } else if (dz > 0.0) {
            double entering = (minNorth - az) / dz;
            double leaving = (maxNorth - az) / dz;
            if (entering > t0) {
                t0 = entering;
            }
            if (leaving < t1) {
                t1 = leaving;
            }
            if (t0 > t1) {
                return false;
            }
        } else {
            double entering = (maxNorth - az) / dz;
            double leaving = (minNorth - az) / dz;
            if (entering > t0) {
                t0 = entering;
            }
            if (leaving < t1) {
                t1 = leaving;
            }
            if (t0 > t1) {
                return false;
            }
        }
        out[0] = t0;
        out[1] = t1;
        return true;
    }

    /**
     * Adds a whole road: surface ribbon, plus a centre line when it is wide enough to have one.
     *
     * <p>Whole is what a caller wants that is not filling a chunk -- a test asserting the shape of one
     * way, or a future overview map. A chunk wants
     * {@link #addRoad(int, MeshData, double, double, double, double)}, which clips.
     */
    public void addRoad(int line, MeshData mesh) {
        addRoad(line, mesh, 0.0, 0.0, 0.0, 0.0, false);
    }

    /** Adds the part of a road that lies inside the rectangle, clipped at its edges. */
    public void addRoad(int line, MeshData mesh, double minEast, double minNorth, double maxEast,
                        double maxNorth) {
        addRoad(line, mesh, minEast, minNorth, maxEast, maxNorth, true);
    }

    private void addRoad(int line, MeshData mesh, double minEast, double minNorth, double maxEast,
                         double maxNorth, boolean clip) {
        int start = roads.lineStart[line];
        int points = roads.lineLength[line];
        if (points < 2) {
            return;
        }
        float halfWidth = Math.max(1.0f, roads.widthM[line] * 0.5f);
        boolean bridge = roads.isBridge(line);
        float tint = roads.isTunnel(line) ? 0.5f : 1.0f;

        // Draped heights, computed once per point and shared by both marked and unmarked ribbons.
        float[] heights = new float[points];
        if (bridge) {
            float first = drapeHeight(start, 0);
            float last = drapeHeight(start, points - 1);
            for (int i = 0; i < points; i++) {
                float t = points == 1 ? 0 : i / (float) (points - 1);
                heights[i] = first + (last - first) * t + 1.2f; // deck clearance over the water
            }
        } else {
            for (int i = 0; i < points; i++) {
                heights[i] = drapeHeight(start, i);
            }
        }

        // Per-point left normals: at a bend, the bisector of the two adjacent segments. Using one
        // normal per centre-line point (rather than one per segment) makes the ribbon continuous
        // through corners instead of leaving a wedge of bare ground on the outside of the turn.
        float[] normalE = new float[points];
        float[] normalN = new float[points];
        for (int i = 0; i < points; i++) {
            float e = 0;
            float n = 0;
            if (i > 0) {
                float dx = roads.xz[(start + i) * 2] - roads.xz[(start + i - 1) * 2];
                float dz = roads.xz[(start + i) * 2 + 1] - roads.xz[(start + i - 1) * 2 + 1];
                float length = (float) Math.sqrt(dx * dx + dz * dz);
                if (length > 1e-4f) {
                    e += -dz / length;
                    n += dx / length;
                }
            }
            if (i < points - 1) {
                float dx = roads.xz[(start + i + 1) * 2] - roads.xz[(start + i) * 2];
                float dz = roads.xz[(start + i + 1) * 2 + 1] - roads.xz[(start + i) * 2 + 1];
                float length = (float) Math.sqrt(dx * dx + dz * dz);
                if (length > 1e-4f) {
                    e += -dz / length;
                    n += dx / length;
                }
            }
            float length = (float) Math.sqrt(e * e + n * n);
            if (length < 1e-4f) {
                e = 0;
                n = 1;                       // degenerate point: fall back to a stable axis
            } else {
                e /= length;
                n /= length;
            }
            normalE[i] = e;
            normalN[i] = n;
        }

        float distance = 0;
        double[] interval = new double[2];
        float[] blend = new float[2];
        for (int i = 0; i < points - 1; i++) {
            float ax = roads.xz[(start + i) * 2];
            float az = roads.xz[(start + i) * 2 + 1];
            float bx = roads.xz[(start + i + 1) * 2];
            float bz = roads.xz[(start + i + 1) * 2 + 1];
            float dx = bx - ax;
            float dz = bz - az;
            float length = (float) Math.sqrt(dx * dx + dz * dz);
            if (length < 0.05f) {
                continue;
            }
            double t0 = 0.0;
            double t1 = 1.0;
            if (clip) {
                if (!clipSegment(ax, az, bx, bz, minEast, minNorth, maxEast, maxNorth, interval)) {
                    distance += length;               // outside, but the ribbon's own u still runs on
                    continue;
                }
                t0 = interval[0];
                t1 = interval[1];
                if (t1 - t0 < 1e-6) {
                    // The segment only touches the edge -- it starts where the last one ended. Drawing a
                    // zero-length ribbon piece would add vertices nothing can see.
                    distance += length;
                    continue;
                }
            }

            // The four corners of this piece of ribbon: the segment's own ends when it is whole, and the
            // points where it crosses the rectangle when it is clipped.
            float aEast = (float) (ax + dx * t0);
            float aNorth = (float) (az + dz * t0);
            float bEast = (float) (ax + dx * t1);
            float bNorth = (float) (az + dz * t1);
            float heightA = heights[i];
            float heightB = heights[i + 1];
            if (t0 > 0.0) {
                heightA = (float) (heights[i] + (heights[i + 1] - heights[i]) * t0);
            }
            if (t1 < 1.0) {
                heightB = (float) (heights[i] + (heights[i + 1] - heights[i]) * t1);
            }
            float aY = heightA + LIFT;
            float bY = heightB + LIFT;
            // The ribbon's own normal, taken at the two corners: a point's bisector at a shared point,
            // and the blend of the two bisectors where the segment is cut in the middle of itself. Both
            // chunks cut it the same way, so the two pieces meet at the edge without a notch.
            float aNormE = normalE[i];
            float aNormN = normalN[i];
            float bNormE = normalE[i + 1];
            float bNormN = normalN[i + 1];
            if (t0 > 0.0 && t0 < 1.0) {
                blendNormal(normalE, normalN, i, t0, blend);
                aNormE = blend[0];
                aNormN = blend[1];
            }
            if (t1 > 0.0 && t1 < 1.0) {
                blendNormal(normalE, normalN, i, t1, blend);
                bNormE = blend[0];
                bNormN = blend[1];
            }
            // u runs along the way in metres, not along the segment, so the paint does not jump at a clip.
            float u0 = t0 <= 0.0 ? distance / 8.0f : (float) ((distance + length * t0) / 8.0);
            float u1 = t1 >= 1.0 ? (distance + length) / 8.0f
                    : (float) ((distance + length * t1) / 8.0);

            // Left edge, right edge, at the start and at the end of this piece.
            int a = mesh.addVertex(aEast + aNormE * halfWidth, aY, aNorth + aNormN * halfWidth,
                    0, 1, 0, SURFACE_ROAD, u0, 0, tint);
            int b = mesh.addVertex(aEast - aNormE * halfWidth, aY, aNorth - aNormN * halfWidth,
                    0, 1, 0, SURFACE_ROAD, u0, 1, tint);
            int c = mesh.addVertex(bEast - bNormE * halfWidth, bY, bNorth - bNormN * halfWidth,
                    0, 1, 0, SURFACE_ROAD, u1, 1, tint);
            int d = mesh.addVertex(bEast + bNormE * halfWidth, bY, bNorth + bNormN * halfWidth,
                    0, 1, 0, SURFACE_ROAD, u1, 0, tint);
            // (a, d, c, b) is counter-clockwise seen from above, so the surface faces up.
            mesh.addQuad(a, d, c, b);

            boolean marked = roads.lanes[line] >= 2 && roads.widthM[line] >= 6.0f && !roads.isOneWay(line);
            if (marked) {
                int m0 = mesh.addVertex(aEast + aNormE * MARKING_HALF_WIDTH, aY + 0.01f,
                        aNorth + aNormN * MARKING_HALF_WIDTH, 0, 1, 0, SURFACE_MARKING, u0, 0, 1f);
                int m1 = mesh.addVertex(aEast - aNormE * MARKING_HALF_WIDTH, aY + 0.01f,
                        aNorth - aNormN * MARKING_HALF_WIDTH, 0, 1, 0, SURFACE_MARKING, u0, 1, 1f);
                int m2 = mesh.addVertex(bEast - bNormE * MARKING_HALF_WIDTH, bY + 0.01f,
                        bNorth - bNormN * MARKING_HALF_WIDTH, 0, 1, 0, SURFACE_MARKING, u1, 1, 1f);
                int m3 = mesh.addVertex(bEast + bNormE * MARKING_HALF_WIDTH, bY + 0.01f,
                        bNorth + bNormN * MARKING_HALF_WIDTH, 0, 1, 0, SURFACE_MARKING, u1, 0, 1f);
                mesh.addQuad(m0, m3, m2, m1);
            }
            distance += length;
        }
    }

    /**
     * The ribbon normal partway along segment {@code i}: the two bisectors at its ends, blended and
     * normalised. A hairpin, where the two bisectors point opposite ways, falls back to the bisector at
     * the start rather than to a zero normal nothing could be offset by.
     */
    private static void blendNormal(float[] normalE, float[] normalN, int i, double t, float[] out) {
        float e = normalE[i] + (float) ((normalE[i + 1] - normalE[i]) * t);
        float n = normalN[i] + (float) ((normalN[i + 1] - normalN[i]) * t);
        float length = (float) Math.sqrt(e * e + n * n);
        if (length < 1e-4f) {
            e = normalE[i];
            n = normalN[i];
            length = (float) Math.sqrt(e * e + n * n);
        }
        if (length < 1e-4f) {
            out[0] = 0f;
            out[1] = 1f;
            return;
        }
        out[0] = e / length;
        out[1] = n / length;
    }

    private float drapeHeight(int start, int index) {
        float east = roads.xz[(start + index) * 2];
        float north = roads.xz[(start + index) * 2 + 1];
        if (terrain == null) {
            return 0.0f;
        }
        return (float) terrain.heightBilinear(terrain.originEast + east, terrain.originNorth + north);
    }
}
