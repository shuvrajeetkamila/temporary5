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
 *
 * <h2>The roof winding, which was wrong the other way round</h2>
 *
 * <p>The walls were right and the roofs were inverted, and the result looked exactly like what the eighth
 * install reported: <em>buildings that look like walls</em>. Every roof -- flat, gabled, domed, all of
 * them -- was wound so that its front face pointed down into the building and its back face up at the
 * sky, and the world is drawn with {@code glCullFace(GL_BACK)}. So no roof was ever drawn: from above,
 * a building was a hole you could see the inside of its own walls through, and from the street it was
 * four walls and nothing on top. A city of 186,993 roofless boxes.
 *
 * <p>The rule this class now obeys, and the tests hold: <b>in a right-handed coordinate system with x
 * east, y up and z north, a triangle whose vertices appear counter-clockwise when seen from above has a
 * normal with a positive y component</b> -- it faces the sky, which is what a roof is for. The wall quads
 * were already consistent with this (their {east, north} projection is clockwise); the roofs were not.
 *
 * <p>One rule then covers every roof shape, because the shapes differ only in the Y they give their
 * vertices: a footprint's ring in the raster's own direction faces up; walking it the other way does
 * not. See {@link #addRoof}.
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
            if (!mesh.hasRoom(vertexCost(buildings, ring))) {
                // Four vertices per wall edge plus the roof: asking "does a quad fit" would let a
                // forty-vertex building start on a mesh with four vertices left.
                break;
            }
            if (addBuilding(ring, mesh)) {
                emitted++;
            }
        }
        return emitted;
    }

    /**
     * Vertices a footprint will need: four per wall edge, plus a roof of at most four more per edge.
     *
     * <p>Reported without clamping. The caller has to be able to tell "this fits in the part I am about
     * to use" from "this cannot fit in any part at all", and a value quietly capped at the limit would
     * make those two look the same -- which is exactly how a primitive ends up on a full mesh.
     */
    public static int vertexCost(Buildings buildings, int ring) {
        int length = buildings.ringLength[ring];
        if (length <= 0) {
            return 0;
        }
        // Walls (4n) + the roof deck (3n-6, three vertices per ear-clipped triangle) + a shaped cap
        // (2n+1). Ten per vertex plus a margin covers all three at once, which is what a *bound* has to
        // do: the mesher sizes a building before it starts, and an underestimate is a primitive running
        // off the end of an index buffer.
        long cost = 10L * length + 32L;
        return cost > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) cost;
    }

    private boolean overlaps(int ring, double minEast, double minNorth, double maxEast, double maxNorth) {
        return buildings.bounds[ring * 4] <= maxEast && buildings.bounds[ring * 4 + 2] >= minEast
                && buildings.bounds[ring * 4 + 1] <= maxNorth && buildings.bounds[ring * 4 + 3] >= minNorth;
    }

    /**
     * Wall shades, written into the vertex tint the fragment shader reads back: a mosque is 0.55, a
     * landmark 0.8, everything else 1.0.
     *
     * <p>They are public because the shader's wall branches are chosen by them -- a mosque gets a
     * sandstone arcade, a landmark a curtain wall, the rest limestone render with doorways along the
     * street level -- so the two files have to agree on the same three numbers.
     * {@code ShaderSourcesTest.wallShadesDriveTheShadersDetail} holds them together: changing a shade
     * here without changing the threshold there is a silent loss of detail, not a compile error.
     */
    public static final float SHADE_MOSQUE = 0.55f;
    public static final float SHADE_LANDMARK = 0.8f;
    public static final float SHADE_PLAIN = 1.0f;

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
        float tint = type == Buildings.TYPE_MOSQUE ? SHADE_MOSQUE
                : (buildings.isLandmark(ring) ? SHADE_LANDMARK : SHADE_PLAIN);

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

    /**
     * Whether a triangle with these east/north corners faces the sky, in the order given.
     *
     * <p>The handedness is the whole trap, so it is stated once and tested rather than assumed. This
     * coordinate system is right-handed with x east, y up and z north, which means {@code x * z = -y}:
     * the (east, north) plane is a <em>left-handed</em> 2D plane, and a triangle whose {east, north}
     * winding is counter-clockwise has a normal pointing <b>down</b>.
     *
     * <p>So a roof faces the sky when its corners run <b>clockwise</b> in (east, north) -- the negative of
     * the usual 2D cross product. {@code BuildingRoofTest} proves that against a real 3D cross product
     * and against the ground, which is drawn the same way.
     *
     * <p>One rule covers flat, gabled, pyramidal and domed roofs alike, because the shapes differ only
     * in the height they give their vertices: for a triangle whose three corners project to the same
     * (east, north) winding, the normal is up whether it is flat or a slope.
     */
    static boolean facesUp(float ax, float az, float bx, float bz, float cx, float cz) {
        return (bx - ax) * (cz - az) - (bz - az) * (cx - ax) < 0f;
    }

    /**
     * The ring in one consistent direction, with the wall winding that follows from it.
     *
     * <p>Measured on the real country: 185,551 of the 186,993 rings arrive counter-clockwise in
     * {east, north} -- and counter-clockwise there means facing <em>down</em>, so almost the whole
     * country's roofs were wound the wrong way and the rest happened to be right. That is why it read as
     * a rendering quirk in one street rather than as a rule.
     */
    private int[] ringInOrder(int start, int vertices, boolean counterClockwise) {
        int[] order = new int[vertices];
        for (int i = 0; i < vertices; i++) {
            // Counter-clockwise in {east, north} faces down (see facesUp), so that is the case to
            // reverse; the minority that already come out clockwise are left alone.
            order[i] = counterClockwise ? start + (vertices - 1 - i) : start + i;
        }
        return order;
    }

    /**
     * The roof: a deck that always covers the whole footprint, plus a cap when OSM asked for a shape.
     *
     * <h2>Why the deck comes first</h2>
     *
     * <p>The shaped roofs are approximated -- a ridge for gables, an apex for domes and pyramids -- and an
     * approximation over an arbitrary concave ring leaves gaps: each ring edge of a gable contributes one
     * triangle to the ridge end nearest it, and the triangles do not tile a rectangle, let alone an
     * L-shaped villa. The player would see through the holes into the building, which is the same
     * symptom as no roof at all.
     *
     * <p>So every building gets a complete ear-clipped deck at the eave line, wound to face the sky, and
     * the shaped cap is drawn <em>above</em> it. The deck is also what the player's own eye expects from
     * the street: a box with a lid, and something pitched on top of the lid.
     */
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

        addRoofDeck(start, vertices, mesh, topY, tint, centreE, centreN, counterClockwise);

        switch (roofShape) {
            case Buildings.ROOF_DOME:
            case Buildings.ROOF_PYRAMID: {
                // Apex over the centroid: a dome is a pyramid with a wider base and a rounded look from
                // the shading, which is honest for a first pass and costs 2n triangles.
                float apex = roofShape == Buildings.ROOF_DOME ? topY + 3.0f : topY + 1.5f;
                // Roof coordinates in the auxiliary pair are metres from the roof centre, not 0..1: the
                // fragment shader tiles roof plant, tanks and panel seams at real sizes on them, and a
                // 0..1 range would stretch a different amount on every building in the country.
                int apexIndex = mesh.addVertex((float) centreE, apex, (float) centreN, 0, 1, 0,
                        SURFACE_ROOF, 0f, 0f, tint);
                int[] cone = ringInOrder(start, vertices, counterClockwise);
                for (int i = 0; i < vertices; i++) {
                    int a = cone[i];
                    int b = cone[(i + 1) % vertices];
                    float ax = buildings.xz[a * 2];
                    float az = buildings.xz[a * 2 + 1];
                    float bx = buildings.xz[b * 2];
                    float bz = buildings.xz[b * 2 + 1];
                    float nx = (float) (ax - centreE);
                    float nz = (float) (az - centreN);
                    float length = (float) Math.sqrt(nx * nx + nz * nz);
                    nx = length > 0.01f ? nx / length : 0;
                    nz = length > 0.01f ? nz / length : 0;
                    // The apex is above both corners, so this triangle's orientation is the ring's:
                    // one consistent order and every face of the cone looks up.
                    int ia = mesh.addVertex(ax, topY, az, nx, 0.6f, nz, SURFACE_ROOF,
                            (float) (ax - centreE), (float) (az - centreN), tint);
                    int ib = mesh.addVertex(bx, topY, bz, nx, 0.6f, nz, SURFACE_ROOF,
                            (float) (bx - centreE), (float) (bz - centreN), tint);
                    mesh.addTriangle(ia, ib, apexIndex);
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
                        (float) (centreN - axisN * ridgeHalf), 0, 1, 0, SURFACE_ROOF,
                        (float) (-axisE * ridgeHalf), (float) (-axisN * ridgeHalf), tint);
                int r2 = mesh.addVertex((float) (centreE + axisE * ridgeHalf), ridgeY,
                        (float) (centreN + axisN * ridgeHalf), 0, 1, 0, SURFACE_ROOF,
                        (float) (axisE * ridgeHalf), (float) (axisN * ridgeHalf), tint);
                int[] slope = ringInOrder(start, vertices, counterClockwise);
                for (int i = 0; i < vertices; i++) {
                    int a = slope[i];
                    int b = slope[(i + 1) % vertices];
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
                    int ia = mesh.addVertex(ax, topY, az, rnx, 0.6f, rnz, SURFACE_ROOF,
                            (float) (ax - centreE), (float) (az - centreN), tint);
                    int ib = mesh.addVertex(bx, topY, bz, rnx, 0.6f, rnz, SURFACE_ROOF,
                            (float) (bx - centreE), (float) (bz - centreN), tint);
                    mesh.addTriangle(ia, ib, near);
                }
                break;
            }
            default:
                // Flat: the deck above *is* the roof, complete by ear clipping, and already drawn.
                break;
        }
    }

    /**
     * The roof deck: the footprint filled at the eave line, ear-clipped so concave footprints (L-shaped
     * villas all over Al Rayyan) are covered correctly, and wound so every triangle faces the sky.
     */
    private void addRoofDeck(int start, int vertices, MeshData mesh, float topY, float tint,
                             double centreE, double centreN, boolean counterClockwise) {
        int[] triangle = new int[3];
        int[] ringIndices = ringInOrder(start, vertices, counterClockwise);
        EarClipper clipper = new EarClipper(buildings.xz, ringIndices, vertices);
        while (clipper.next(triangle)) {
            int i0 = mesh.addVertex(buildings.xz[triangle[0] * 2], topY,
                    buildings.xz[triangle[0] * 2 + 1], 0, 1, 0, SURFACE_ROOF,
                    (float) (buildings.xz[triangle[0] * 2] - centreE),
                    (float) (buildings.xz[triangle[0] * 2 + 1] - centreN), tint);
            int i1 = mesh.addVertex(buildings.xz[triangle[1] * 2], topY,
                    buildings.xz[triangle[1] * 2 + 1], 0, 1, 0, SURFACE_ROOF,
                    (float) (buildings.xz[triangle[1] * 2] - centreE),
                    (float) (buildings.xz[triangle[1] * 2 + 1] - centreN), tint);
            int i2 = mesh.addVertex(buildings.xz[triangle[2] * 2], topY,
                    buildings.xz[triangle[2] * 2 + 1], 0, 1, 0, SURFACE_ROOF,
                    (float) (buildings.xz[triangle[2] * 2] - centreE),
                    (float) (buildings.xz[triangle[2] * 2 + 1] - centreN), tint);
            mesh.addTriangle(i0, i1, i2);
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
