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
            if (mesh.isFull()) {
                break;
            }
            addRoad(line, mesh);
            emitted++;
        }
        return emitted;
    }

    private boolean overlaps(int line, double minEast, double minNorth, double maxEast, double maxNorth) {
        return roads.bounds[line * 4] <= maxEast && roads.bounds[line * 4 + 2] >= minEast
                && roads.bounds[line * 4 + 1] <= maxNorth && roads.bounds[line * 4 + 3] >= minNorth;
    }

    /** Adds one road: surface ribbon, plus a centre line when it is wide enough to have one. */
    public void addRoad(int line, MeshData mesh) {
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
            float ay = heights[i] + LIFT;
            float by = heights[i + 1] + LIFT;
            float u0 = distance / 8.0f;
            float u1 = (distance + length) / 8.0f;

            // Left edge, right edge, at the start and at the end of this segment.
            int a = mesh.addVertex(ax + normalE[i] * halfWidth, ay, az + normalN[i] * halfWidth,
                    0, 1, 0, SURFACE_ROAD, u0, 0, tint);
            int b = mesh.addVertex(ax - normalE[i] * halfWidth, ay, az - normalN[i] * halfWidth,
                    0, 1, 0, SURFACE_ROAD, u0, 1, tint);
            int c = mesh.addVertex(bx - normalE[i + 1] * halfWidth, by, bz - normalN[i + 1] * halfWidth,
                    0, 1, 0, SURFACE_ROAD, u1, 1, tint);
            int d = mesh.addVertex(bx + normalE[i + 1] * halfWidth, by, bz + normalN[i + 1] * halfWidth,
                    0, 1, 0, SURFACE_ROAD, u1, 0, tint);
            // (a, d, c, b) is counter-clockwise seen from above, so the surface faces up.
            mesh.addQuad(a, d, c, b);

            boolean marked = roads.lanes[line] >= 2 && roads.widthM[line] >= 6.0f && !roads.isOneWay(line);
            if (marked) {
                int m0 = mesh.addVertex(ax + normalE[i] * MARKING_HALF_WIDTH, ay + 0.01f,
                        az + normalN[i] * MARKING_HALF_WIDTH, 0, 1, 0, SURFACE_MARKING, u0, 0, 1f);
                int m1 = mesh.addVertex(ax - normalE[i] * MARKING_HALF_WIDTH, ay + 0.01f,
                        az - normalN[i] * MARKING_HALF_WIDTH, 0, 1, 0, SURFACE_MARKING, u0, 1, 1f);
                int m2 = mesh.addVertex(bx - normalE[i + 1] * MARKING_HALF_WIDTH, by + 0.01f,
                        bz - normalN[i + 1] * MARKING_HALF_WIDTH, 0, 1, 0, SURFACE_MARKING, u1, 1, 1f);
                int m3 = mesh.addVertex(bx + normalE[i + 1] * MARKING_HALF_WIDTH, by + 0.01f,
                        bz + normalN[i + 1] * MARKING_HALF_WIDTH, 0, 1, 0, SURFACE_MARKING, u1, 0, 1f);
                mesh.addQuad(m0, m3, m2, m1);
            }
            distance += length;
        }
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
