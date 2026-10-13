package ai.arena.qatar.render;

import ai.arena.qatar.world.Buildings;
import ai.arena.qatar.world.TerrainGrid;

/**
 * Extrudes real OpenStreetMap footprints into 3D volumes: walls, roofs, and the ground floor that
 * meets the terrain.
 *
 * <p>Three details are what make this look like a city rather than a field of boxes:
 *
 * <ol>
 *   <li><b>Levelled bases.</b> A building sits on the <em>highest</em> terrain under its footprint, not
 *       on the terrain at its centre. On a slope that is the difference between a house and a house
 *       half-buried in the ground.</li>
 *   <li><b>Real rings.</b> The footprint is the OSM ring, concavities and all, and the roof is properly
 *       triangulated by ear clipping, so an L-shaped villa is an L-shaped villa.</li>
 *   <li><b>Roof shapes.</b> Flat, gabled, pyramidal and domed roofs come from OSM's {@code roof:shape},
 *       which is how a Doha mosque ends up with a dome without any hand-modelling.</li>
 * </ol>
 *
 * <p>Winding is normalised: OSM rings have no guaranteed direction, so the signed area decides whether
 * the walls face outwards or inwards. Get that wrong and every building in the country is inside out.
 */
public final class BuildingMesher {

    public static final float SURFACE_WALL = 2.0f;
    public static final float SURFACE_ROOF = 3.0f;

    private final Buildings buildings;
    private final TerrainGrid terrain;

    public BuildingMesher(Buildings buildings, TerrainGrid terrain) {
        this.buildings = buildings;
        this.terrain = terrain;
    }

    /**
     * Meshes every building whose bounds overlap the rectangle (grid-origin relative metres), into the
     * supplied mesh. Buildings are added whole; the caller is responsible for chunking.
     */
    public int mesh(double minEast, double minNorth, double maxEast, double maxNorth, MeshData mesh,
                    int[] candidates, int candidateCount) {
        int emitted = 0;
        for (int i = 0; i < candidateCount; i++) {
            int ring = candidates[i];
            if (ring < 0 || ring >= buildings.ringCount) {
                continue;
            }
            if (!overlaps(ring, minEast, minNorth, maxEast, maxNorth)) {
                continue;
            }
            if (mesh.isFull()) {
                break;
            }
            if (addBuilding(ring, mesh)) {
                emitted++;
            }
        }
        return emitted;
    }

    private boolean overlaps(int ring, double minEast, double minNorth, double maxEast, double maxNorth) {
        return buildings.bounds[ring * 4] <= maxEast && buildings.bounds[ring * 4 + 2] >= minEast
                && buildings.bounds[ring * 4 + 1] <= maxNorth && buildings.bounds[ring * 4 + 3] >= minNorth;
    }

    /** Adds one building; returns true when anything was emitted. */
    public boolean addBuilding(int ring, MeshData mesh) {
        int start = buildings.ringStart[ring];
        int length = buildings.ringLength[ring];
        if (length < 3) {
            return false;
        }
        // OSM rings repeat the first vertex; drop the duplicate for triangulation and walls.
        int vertices = length;
        if (samePoint(start, start + length - 1)) {
            vertices = length - 1;
        }
        if (vertices < 3) {
            return false;
        }

        float baseY = baseHeight(ring, start, vertices);
        float topY = baseY + Math.max(2.5f, buildings.heightM[ring]);
        float bottomY = baseY + Math.max(0.0f, buildings.minHeightM[ring]);

        boolean counterClockwise = signedArea(start, vertices) > 0;
        float type = buildings.type[ring];
        float roof = buildings.roof[ring];
        float tint = type == Buildings.TYPE_MOSQUE ? 0.55f : (buildings.isLandmark(ring) ? 0.8f : 1.0f);

        // --- walls: one quad per ring edge ------------------------------------------------
        for (int i = 0; i < vertices; i++) {
            int a = start + i;
            int b = start + (i + 1) % vertices;
            float ax = buildings.xz[a * 2];
            float az = buildings.xz[a * 2 + 1];
            float bx = buildings.xz[b * 2];
            float bz = buildings.xz[b * 2 + 1];
            float dx = bx - ax;
            float dz = bz - az;
            float edgeLength = (float) Math.sqrt(dx * dx + dz * dz);
            if (edgeLength < 0.01f) {
                continue;
            }
            // Outward normal: for a counter-clockwise ring the outward side is (dz, -dx).
            float nx = counterClockwise ? dz / edgeLength : -dz / edgeLength;
            float nz = counterClockwise ? -dx / edgeLength : dx / edgeLength;
            int bottomA = mesh.addVertex(ax, bottomY, az, nx, 0, nz, SURFACE_WALL, 0, 0, tint);
            int bottomB = mesh.addVertex(bx, bottomY, bz, nx, 0, nz, SURFACE_WALL, 1, 0, tint);
            int topA = mesh.addVertex(ax, topY, az, nx, 0, nz, SURFACE_WALL, 0, topY - bottomY, tint);
            int topB = mesh.addVertex(bx, topY, bz, nx, 0, nz, SURFACE_WALL, 1, topY - bottomY, tint);
            if (counterClockwise) {
                mesh.addQuad(bottomA, topA, topB, bottomB);
            } else {
                mesh.addQuad(bottomA, bottomB, topB, topA);
            }
        }

        // --- roof -------------------------------------------------------------------------
        addRoof(ring, start, vertices, mesh, topY, tint, (int) roof, counterClockwise);
        return true;
    }

    private void addRoof(int ring, int start, int vertices, MeshData mesh, float topY, float tint,
                         int roofShape, boolean counterClockwise) {
        double centreE = 0;
        double centreN = 0;
        for (int i = 0; i < vertices; i++) {
            centreE += buildings.xz[(start + i) * 2];
            centreN += buildings.xz[(start + i) * 2 + 1];
        }
        centreE /= vertices;
        centreN /= vertices;

        switch (roofShape) {
            case Buildings.ROOF_DOME:
            case Buildings.ROOF_PYRAMID: {
                // Apex over the centroid: a dome is a pyramid with a wider base and a rounded look from
                // the shading, which is honest for a first pass and costs 2n triangles.
                float apex = roofShape == Buildings.ROOF_DOME ? topY + 3.0f : topY + 1.5f;
                int apexIndex = mesh.addVertex((float) centreE, apex, (float) centreN, 0, 1, 0,
                        SURFACE_ROOF, 0.5f, 0.5f, tint);
                for (int i = 0; i < vertices; i++) {
                    int a = start + i;
                    int b = start + (i + 1) % vertices;
                    float ax = buildings.xz[a * 2];
                    float az = buildings.xz[a * 2 + 1];
                    float bx = buildings.xz[b * 2];
                    float bz = buildings.xz[b * 2 + 1];
                    float nx = (float) (ax - centreE);
                    float nz = (float) (az - centreN);
                    float length = (float) Math.sqrt(nx * nx + nz * nz);
                    nx = length > 0.01f ? nx / length : 0;
                    nz = length > 0.01f ? nz / length : 0;
                    int ia = mesh.addVertex(ax, topY, az, nx, 0.6f, nz, SURFACE_ROOF, 0, 1, tint);
                    int ib = mesh.addVertex(bx, topY, bz, nx, 0.6f, nz, SURFACE_ROOF, 1, 1, tint);
                    if (counterClockwise) {
                        mesh.addTriangle(ia, apexIndex, ib);
                    } else {
                        mesh.addTriangle(ib, apexIndex, ia);
                    }
                }
                break;
            }
            case Buildings.ROOF_GABLE:
            case Buildings.ROOF_SHED: {
                // A ridge over the longest axis: two roof planes meeting at a line, approximated by a
                // ridge segment rather than a single apex. Cheap, deterministic, and reads correctly.
                double axisE = 0;
                double axisN = 0;
                double best = -1;
                for (int i = 0; i < vertices; i++) {
                    int a = start + i;
                    int b = start + (i + 1) % vertices;
                    double dx = buildings.xz[b * 2] - buildings.xz[a * 2];
                    double dz = buildings.xz[b * 2 + 1] - buildings.xz[a * 2 + 1];
                    double length = Math.sqrt(dx * dx + dz * dz);
                    if (length > best) {
                        best = length;
                        axisE = dx / Math.max(length, 1e-6);
                        axisN = dz / Math.max(length, 1e-6);
                    }
                }
                float ridgeHalf = (float) (best * 0.5);
                float ridgeY = roofShape == Buildings.ROOF_GABLE ? topY + 1.2f : topY + 0.8f;
                int r1 = mesh.addVertex((float) (centreE - axisE * ridgeHalf), ridgeY,
                        (float) (centreN - axisN * ridgeHalf), 0, 1, 0, SURFACE_ROOF, 0, 0.5f, tint);
                int r2 = mesh.addVertex((float) (centreE + axisE * ridgeHalf), ridgeY,
                        (float) (centreN + axisN * ridgeHalf), 0, 1, 0, SURFACE_ROOF, 1, 0.5f, tint);
                for (int i = 0; i < vertices; i++) {
                    int a = start + i;
                    int b = start + (i + 1) % vertices;
                    float ax = buildings.xz[a * 2];
                    float az = buildings.xz[a * 2 + 1];
                    float bx = buildings.xz[b * 2];
                    float bz = buildings.xz[b * 2 + 1];
                    // Pick the ridge end nearest this edge, so the roof planes do not cross over.
                    double da = distance(ax, az, centreE - axisE * ridgeHalf, centreN - axisN * ridgeHalf)
                            + distance(bx, bz, centreE - axisE * ridgeHalf, centreN - axisN * ridgeHalf);
                    double db = distance(ax, az, centreE + axisE * ridgeHalf, centreN + axisN * ridgeHalf)
                            + distance(bx, bz, centreE + axisE * ridgeHalf, centreN + axisN * ridgeHalf);
                    int near = da <= db ? r1 : r2;
                    float rnx = (float) ((ax + bx) / 2.0 - centreE);
                    float rnz = (float) ((az + bz) / 2.0 - centreN);
                    float rl = (float) Math.sqrt(rnx * rnx + rnz * rnz);
                    rnx = rl > 0.01f ? rnx / rl : 0;
                    rnz = rl > 0.01f ? rnz / rl : 0;
                    int ia = mesh.addVertex(ax, topY, az, rnx, 0.6f, rnz, SURFACE_ROOF, 0, 1, tint);
                    int ib = mesh.addVertex(bx, topY, bz, rnx, 0.6f, rnz, SURFACE_ROOF, 1, 1, tint);
                    if (counterClockwise) {
                        mesh.addTriangle(ia, near, ib);
                    } else {
                        mesh.addTriangle(ib, near, ia);
                    }
                }
                break;
            }
            default: {
                // Flat roof: ear-clip the ring so concave footprints (L-shaped villas) roof correctly.
                int[] triangle = new int[3];
                int[] ringIndices = new int[vertices];
                for (int i = 0; i < vertices; i++) {
                    ringIndices[i] = start + i;
                }
                EarClipper clipper = new EarClipper(buildings.xz, ringIndices, vertices);
                while (clipper.next(triangle)) {
                    int i0 = mesh.addVertex(buildings.xz[triangle[0] * 2], topY,
                            buildings.xz[triangle[0] * 2 + 1], 0, 1, 0, SURFACE_ROOF, 0, 0, tint);
                    int i1 = mesh.addVertex(buildings.xz[triangle[1] * 2], topY,
                            buildings.xz[triangle[1] * 2 + 1], 0, 1, 0, SURFACE_ROOF, 1, 0, tint);
                    int i2 = mesh.addVertex(buildings.xz[triangle[2] * 2], topY,
                            buildings.xz[triangle[2] * 2 + 1], 0, 1, 0, SURFACE_ROOF, 0, 1, tint);
                    if (counterClockwise) {
                        mesh.addTriangle(i0, i1, i2);
                    } else {
                        mesh.addTriangle(i2, i1, i0);
                    }
                }
                break;
            }
        }
    }

    /** Highest terrain height under the footprint, so the building stands on the ground, not in it. */
    private float baseHeight(int ring, int start, int vertices) {
        if (terrain == null) {
            return 0.0f;
        }
        float highest = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vertices; i++) {
            double east = terrain.originEast + buildings.xz[(start + i) * 2];
            double north = terrain.originNorth + buildings.xz[(start + i) * 2 + 1];
            highest = Math.max(highest, (float) terrain.heightBilinear(east, north));
        }
        return highest == Float.NEGATIVE_INFINITY ? 0.0f : highest;
    }

    private boolean samePoint(int a, int b) {
        return Math.abs(buildings.xz[a * 2] - buildings.xz[b * 2]) < 1e-4
                && Math.abs(buildings.xz[a * 2 + 1] - buildings.xz[b * 2 + 1]) < 1e-4;
    }

    private double signedArea(int start, int vertices) {
        double twice = 0;
        for (int i = 0; i < vertices; i++) {
            int a = start + i;
            int b = start + (i + 1) % vertices;
            twice += buildings.xz[a * 2] * (double) buildings.xz[b * 2 + 1]
                    - buildings.xz[b * 2] * (double) buildings.xz[a * 2 + 1];
        }
        return twice * 0.5;
    }

    private static double distance(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Ear clipping for simple polygons. Buildings are simple rings in OSM (self-intersections are rare
     * and the compiler's validity pass removes them), and ear clipping handles the concave footprints
     * that a triangle fan would break, such as the L-shaped villas all over Al Rayyan.
     */
    static final class EarClipper {
        private final float[] xz;
        private final int[] ring;
        private final int[] remaining;
        private int count;

        EarClipper(float[] xz, int[] ring, int count) {
            this.xz = xz;
            this.ring = ring;
            this.remaining = new int[count];
            System.arraycopy(ring, 0, this.remaining, 0, count);
            this.count = count;
        }

        boolean next(int[] out) {
            if (count < 3) {
                return false;
            }
            boolean counterClockwise = signedArea() > 0;
            for (int i = 0; i < count; i++) {
                int prev = remaining[(i + count - 1) % count];
                int curr = remaining[i];
                int next = remaining[(i + 1) % count];
                if (!isEar(prev, curr, next, counterClockwise)) {
                    continue;
                }
                out[0] = prev;
                out[1] = curr;
                out[2] = next;
                System.arraycopy(remaining, i + 1, remaining, i, count - i - 1);
                count--;
                return true;
            }
            // Degenerate or self-intersecting ring: emit a fan triangle and drop the middle vertex, so
            // a bad footprint still produces geometry instead of hanging or vanishing.
            out[0] = remaining[(count - 1) % count];
            out[1] = remaining[0];
            out[2] = remaining[1 % count];
            System.arraycopy(remaining, 1, remaining, 0, count - 1);
            count--;
            return true;
        }

        private boolean isEar(int prev, int curr, int next, boolean counterClockwise) {
            double cross = cross(prev, curr, next);
            if (counterClockwise ? cross <= 0 : cross >= 0) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                int candidate = remaining[i];
                if (candidate == prev || candidate == curr || candidate == next) {
                    continue;
                }
                if (pointInTriangle(candidate, prev, curr, next)) {
                    return false;
                }
            }
            return true;
        }

        private double cross(int a, int b, int c) {
            double abx = xz[b * 2] - xz[a * 2];
            double abz = xz[b * 2 + 1] - xz[a * 2 + 1];
            double acx = xz[c * 2] - xz[a * 2];
            double acz = xz[c * 2 + 1] - xz[a * 2 + 1];
            return abx * acz - abz * acx;
        }

        private boolean pointInTriangle(int p, int a, int b, int c) {
            double d1 = cross(a, b, p);
            double d2 = cross(b, c, p);
            double d3 = cross(c, a, p);
            boolean negative = d1 < 0 || d2 < 0 || d3 < 0;
            boolean positive = d1 > 0 || d2 > 0 || d3 > 0;
            return !(negative && positive);
        }

        private double signedArea() {
            double twice = 0;
            for (int i = 0; i < count; i++) {
                int a = remaining[i];
                int b = remaining[(i + 1) % count];
                twice += xz[a * 2] * (double) xz[b * 2 + 1] - xz[b * 2] * (double) xz[a * 2 + 1];
            }
            return twice * 0.5;
        }
    }
}
