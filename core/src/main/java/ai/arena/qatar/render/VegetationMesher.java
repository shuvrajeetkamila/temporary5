package ai.arena.qatar.render;

import ai.arena.qatar.physics.SurfaceMaterial;
import ai.arena.qatar.world.Areas;
import ai.arena.qatar.world.MaterialGrid;
import ai.arena.qatar.world.TerrainGrid;

/**
 * Scatters palms and scrub over the vegetation the compiled country already carries.
 *
 * <p>The world compiler has been marking land-use polygons that OSM tags as vegetation -- scrub, heath,
 * shrubbery, grass, meadow, orchard, vineyard, wood, mangrove -- with {@link Areas#KIND_VEGETATION} since
 * the first country build, and nothing ever drew them. They drive the terrain colour today; this is the
 * other half of what they were compiled for.
 *
 * <p>Three decisions worth stating, because each of them is a choice rather than a fact:
 *
 * <ol>
 *   <li><b>The scatter is deterministic.</b> Every plant's position, height and kind come from a hash of
 *       its lattice cell, so a chunk that is rebuilt after streaming out and back in grows exactly the
 *       same palms in exactly the same places. Anything else would make the country shuffle itself every
 *       time the player drove in a circle, and a test could not tell that from working code.</li>
 *   <li><b>The kind follows the compiled material, not the OSM tag.</b> The bundle stores the classified
 *       material -- farm soil for an orchard, gravel and rock for scrub, mangrove and marsh for mangrove
 *       -- and the raw tag is not in the APK. So orchards, meadows and farmland all get the same mix of
 *       palms and bushes, which is honest for a country where a date-palm farm and a grass field really do
 *       look alike from above, and wrong in the details.</li>
 *   <li><b>The budget is spent before a plant is written.</b> A 500 m chunk of scrub holds thousands of
 *       lattice cells; the mesher is handed a vertex allowance and stops when it is used up, rather than
 *       discovering the limit halfway through a palm (which is how the third install died, and why
 *       {@link MeshData#hasRoom} exists).</li>
 * </ol>
 *
 * <p>No GL and no libGDX: it writes vertices into a {@link MeshData} and can be tested against the
 * committed fixture, so the geometry, the winding and the budget are all checkable on a laptop.
 */
public final class VegetationMesher {

    /** The surface type the shader paints as foliage: see {@link VertexFormat#SURFACE_VEGETATION}. */
    public static final float SURFACE = VertexFormat.SURFACE_VEGETATION;

    /** Lattice spacing for the scatter, in metres: one candidate plant per cell, thinned by detail. */
    public static final double SPACING_M = 17.0;
    /** How far a plant is jittered from its lattice point, in metres: keeps ranks from reading as ranks. */
    public static final double JITTER_M = 6.0;

    /**
     * Vertices one palm costs, to the vertex: a four-sided trunk in three bands (4 x 3 x 4 = 48), the
     * cone of eight frond faces (8 x 3 = 24), and the eight hanging fronds (8 x 3 = 24).
     *
     * <p>It is written as the sum it is rather than as 96 for the same reason the mesher spends its
     * budget before it writes: the whole failure mode is a constant that has drifted away from the
     * code that spends it, and the test asserts this number against the mesh.
     */
    public static final int PALM_VERTICES = 4 * 3 * 4 + 8 * 3 + 8 * 3;
    /** Vertices one bush costs: an eight-sided dome, one triangle per side. */
    public static final int BUSH_VERTICES = 8 * 3;
    /** Section of a palm: the fraction of its height that is trunk. The shader splits colour on this. */
    public static final double PALM_TRUNK_FRACTION = 0.62;
    /** Palm height range, in metres: the trunk, with the crown above it. */
    public static final double PALM_HEIGHT_MIN_M = 5.0;
    public static final double PALM_HEIGHT_MAX_M = 9.5;
    /** Crown radius range, in metres. */
    public static final double CROWN_RADIUS_MIN_M = 1.7;
    public static final double CROWN_RADIUS_MAX_M = 2.7;
    /** Bushes: waist high scrub, wide rather than tall. */
    public static final double BUSH_HEIGHT_M = 0.9;
    public static final double BUSH_RADIUS_M = 1.5;

    /** How dense the scatter is at the middle of the detail ring, and at its edge. */
    public static final double DENSITY_NEAR = 0.8;
    public static final double DENSITY_FAR = 0.22;
    /** Below this detail level nothing is scattered at all: it is fog, not scenery. */
    public static final double MIN_DETAIL = 0.3;
    /** However rich the land use is, a chunk never spends more than this on plants. */
    public static final int MAX_CHUNK_VERTICES = 12_000;

    /** What one chunk's scatter produced, for the diagnostics line and for the tests. */
    public static final class Stats {
        public int palms;
        public int bushes;
        /** True when the vertex allowance stopped the scatter before the land use ran out. */
        public boolean budgetReached;

        public int vertices() {
            return palms * PALM_VERTICES + bushes * BUSH_VERTICES;
        }

        public int plants() {
            return palms + bushes;
        }
    }

    /**
     * Adds the vegetation of every vegetation polygon inside the rectangle to {@code mesh}, spending at
     * most {@code vertexBudget} vertices.
     *
     * @param detail the chunk's detail level, 0..1, which thins the scatter with distance
     */
    public Stats meshInto(Areas landuse, TerrainGrid terrain, MaterialGrid materials, MeshData mesh,
                          double minEast, double minNorth, double maxEast, double maxNorth,
                          double detail, int vertexBudget) {
        Stats stats = new Stats();
        if (landuse == null || terrain == null || mesh == null || detail < MIN_DETAIL) {
            return stats;
        }
        int budget = Math.min(Math.max(0, vertexBudget), MAX_CHUNK_VERTICES);
        double density = DENSITY_FAR + (DENSITY_NEAR - DENSITY_FAR) * clamp(detail, 0.0, 1.0);
        for (int ring = 0; ring < landuse.count; ring++) {
            if (landuse.kind[ring] != Areas.KIND_VEGETATION) {
                continue;
            }
            if (landuse.bounds[ring * 4] > maxEast || landuse.bounds[ring * 4 + 2] < minEast
                    || landuse.bounds[ring * 4 + 1] > maxNorth
                    || landuse.bounds[ring * 4 + 3] < minNorth) {
                continue;
            }
            int material = landuse.areaClass[ring] & 0xFF;
            scatterPolygon(landuse, ring, terrain, materials, mesh, minEast, minNorth, maxEast,
                    maxNorth, detail, density, material, budget, stats);
            if (stats.budgetReached) {
                return stats;
            }
        }
        return stats;
    }

    /** Walks the lattice over one polygon, clipped to the chunk, and plants what the hash says. */
    private void scatterPolygon(Areas landuse, int ring, TerrainGrid terrain, MaterialGrid materials,
                               MeshData mesh, double minEast, double minNorth, double maxEast,
                               double maxNorth, double detail, double density, int material,
                               int budget, Stats stats) {
        double x0 = Math.max(minEast, landuse.bounds[ring * 4]);
        double z0 = Math.max(minNorth, landuse.bounds[ring * 4 + 1]);
        double x1 = Math.min(maxEast, landuse.bounds[ring * 4 + 2]);
        double z1 = Math.min(maxNorth, landuse.bounds[ring * 4 + 3]);
        if (x1 <= x0 || z1 <= z0) {
            return;
        }
        // The lattice is aligned to the world, not to the chunk: a plant must land in the same place
        // whichever chunk is being built.
        int cellX0 = (int) Math.floor(x0 / SPACING_M);
        int cellX1 = (int) Math.floor(x1 / SPACING_M);
        int cellZ0 = (int) Math.floor(z0 / SPACING_M);
        int cellZ1 = (int) Math.floor(z1 / SPACING_M);
        for (int cellX = cellX0; cellX <= cellX1; cellX++) {
            for (int cellZ = cellZ0; cellZ <= cellZ1; cellZ++) {
                double seed = hash01(cellX, cellZ);
                if (seed > density) {
                    continue;
                }
                double east = cellX * SPACING_M + SPACING_M * 0.5
                        + (hash01(cellX + 7919, cellZ) - 0.5) * 2.0 * JITTER_M;
                double north = cellZ * SPACING_M + SPACING_M * 0.5
                        + (hash01(cellX, cellZ + 104729) - 0.5) * 2.0 * JITTER_M;
                if (east < x0 || east > x1 || north < z0 || north > z1) {
                    continue;
                }
                if (!inside(landuse, ring, east, north)) {
                    continue;
                }
                if (!onLand(materials, east, north)) {
                    continue;
                }
                double variation = hash01(cellX + 31, cellZ + 17);
                boolean palm = palmFor(material, variation);
                int cost = palm ? PALM_VERTICES : BUSH_VERTICES;
                if (cost > budget - stats.vertices()) {
                    // The allowance is spent. Stop cleanly, and say so: a chunk that runs out of room
                    // for its plants is a fact about the budget, not a crash.
                    stats.budgetReached = true;
                    return;
                }
                double ground = terrain.heightBilinear(east, north);
                if (palm) {
                    addPalm(mesh, east, ground, north, variation, material);
                    stats.palms++;
                } else {
                    addBush(mesh, east, ground, north, variation, material);
                    stats.bushes++;
                }
            }
        }
    }

    /**
     * Which plants a compiled material grows.
     *
     * <p>Farm soil carries orchards, vineyards, meadows and grass; gravel and rock is scrub, heath and
     * shrubbery; mangrove and marsh is low, wet scrub. Sand, rock and urban land grow nothing here even
     * when OSM marks them as vegetation, because a palm on a reclaimed building site looks like a bug.
     */
    private static boolean palmFor(int material, double variation) {
        if (material == SurfaceMaterial.ID_FARM_SOIL) {
            return variation < 0.55;              // planted country: date palms and orchards
        }
        if (material == SurfaceMaterial.ID_GRAVEL_ROCK) {
            return variation < 0.15;              // a lone palm in the scrub
        }
        return false;                             // mangrove and marsh: scrub only
    }

    /** True when the compiled material at a point is land a plant can stand on. */
    private static boolean onLand(MaterialGrid materials, double east, double north) {
        if (materials == null) {
            return true;
        }
        int id = materials.idAt(east, north);
        return id != SurfaceMaterial.ID_SHALLOW_WATER && id != SurfaceMaterial.ID_OPEN_WATER;
    }

    /** Adds one palm: a tapering four-sided trunk, a cone of fronds, and a skirt of hanging fronds. */
    private static void addPalm(MeshData mesh, double east, double ground, double north,
                                double variation, int material) {
        double height = PALM_HEIGHT_MIN_M + (PALM_HEIGHT_MAX_M - PALM_HEIGHT_MIN_M) * variation;
        double trunkHeight = height * PALM_TRUNK_FRACTION;
        double crownRadius = CROWN_RADIUS_MIN_M
                + (CROWN_RADIUS_MAX_M - CROWN_RADIUS_MIN_M) * variation;
        double baseY = ground - 0.25;             // set into the ground, so no seam shows
        double r0 = 0.30;
        double r1 = 0.19;

        // ---- trunk: four quads around a square section, tapering to the crown ----------------
        for (int i = 0; i < 4; i++) {
            double a0 = Math.PI * 0.5 * i;
            double a1 = Math.PI * 0.5 * (i + 1);
            float bx0 = (float) (east + Math.cos(a0) * r0);
            float bz0 = (float) (north + Math.sin(a0) * r0);
            float bx1 = (float) (east + Math.cos(a1) * r0);
            float bz1 = (float) (north + Math.sin(a1) * r0);
            float tx0 = (float) (east + Math.cos(a0) * r1);
            float tz0 = (float) (north + Math.sin(a0) * r1);
            float tx1 = (float) (east + Math.cos(a1) * r1);
            float tz1 = (float) (north + Math.sin(a1) * r1);
            double outwardX = Math.cos((a0 + a1) * 0.5);
            double outwardZ = Math.sin((a0 + a1) * 0.5);
            // Up the trunk, in three bands, so `up` in the shader can tell wood from fronds.
            for (int band = 0; band < 3; band++) {
                double tLow = band / 3.0;
                double tHigh = (band + 1) / 3.0;
                float yLow = (float) (baseY + trunkHeight * tLow);
                float yHigh = (float) (baseY + trunkHeight * tHigh);
                float uLow = (float) (tLow * PALM_TRUNK_FRACTION);
                float uHigh = (float) (tHigh * PALM_TRUNK_FRACTION);
                orientedQuad(mesh, bx0, yLow, bz0, bx1, yLow, bz1, tx1, yHigh, tz1, tx0, yHigh, tz0,
                        outwardX, 0.0, outwardZ, uLow, uHigh, variation, 0.0f, material);
            }
        }

        // ---- crown: a cone of fronds from the top of the trunk -------------------------------
        float crownY = (float) (baseY + trunkHeight);
        float apexY = (float) (baseY + height + 0.9);
        int sectors = 8;
        for (int i = 0; i < sectors; i++) {
            double a0 = 2.0 * Math.PI * i / sectors;
            double a1 = 2.0 * Math.PI * (i + 1) / sectors;
            float c0x = (float) (east + Math.cos(a0) * crownRadius);
            float c0z = (float) (north + Math.sin(a0) * crownRadius);
            float c1x = (float) (east + Math.cos(a1) * crownRadius);
            float c1z = (float) (north + Math.sin(a1) * crownRadius);
            // Cone face: ring to apex, so the crown is closed and never shows its inside.
            orientedTriangle(mesh, c0x, crownY, c0z, c1x, crownY, c1z, (float) east, apexY,
                    (float) north, Math.cos((a0 + a1) * 0.5), 0.6, Math.sin((a0 + a1) * 0.5),
                    (float) PALM_TRUNK_FRACTION, 1.0f, variation, 0.0f, material);
            // Hanging frond: a triangle drooping outwards and down from the rim.
            float dropY = (float) (crownY - crownRadius * 0.55);
            float dropX = (float) (east + Math.cos((a0 + a1) * 0.5) * crownRadius * 1.25);
            float dropZ = (float) (north + Math.sin((a0 + a1) * 0.5) * crownRadius * 1.25);
            orientedTriangle(mesh, c0x, crownY, c0z, c1x, crownY, c1z, dropX, dropY, dropZ,
                    Math.cos((a0 + a1) * 0.5), 0.25, Math.sin((a0 + a1) * 0.5),
                    (float) PALM_TRUNK_FRACTION, 0.95f, variation, 0.0f, material);
        }
    }

    /** Adds one bush: an eight-sided dome sitting on the ground. */
    private static void addBush(MeshData mesh, double east, double ground, double north,
                                double variation, int material) {
        float baseY = (float) (ground - 0.15);
        float topY = (float) (ground + BUSH_HEIGHT_M * (0.7 + 0.6 * variation));
        int sectors = 8;
        for (int i = 0; i < sectors; i++) {
            double a0 = 2.0 * Math.PI * i / sectors;
            double a1 = 2.0 * Math.PI * (i + 1) / sectors;
            float r = (float) (BUSH_RADIUS_M * (0.8 + 0.4 * variation));
            float x0 = (float) (east + Math.cos(a0) * r);
            float z0 = (float) (north + Math.sin(a0) * r);
            float x1 = (float) (east + Math.cos(a1) * r);
            float z1 = (float) (north + Math.sin(a1) * r);
            orientedTriangle(mesh, x0, baseY, z0, x1, baseY, z1, (float) east, topY, (float) north,
                    Math.cos((a0 + a1) * 0.5), 0.8, Math.sin((a0 + a1) * 0.5),
                    0.0f, 1.0f, variation, 1.0f, material);
        }
    }

    /**
     * Writes a quad with the winding that puts its front face towards {@code (nx, ny, nz)}.
     *
     * <p>This is the roof bug's lesson turned into a method. Winding is invisible in the source, wrong
     * winding is invisible in a screenshot until the camera moves, and the country's own roofs were
     * drawn inside out for eight installs because the sign was reasoned about instead of computed. Here
     * the desired outward direction is the argument, the cross product of the winding that was written is
     * the check, and a face that disagrees is written the other way round. There is a test that every
     * triangle of every plant faces away from its own axis.
     */
    private static void orientedQuad(MeshData mesh, float ax, float ay, float az, float bx, float by,
                                     float bz, float cx, float cy, float cz, float dx, float dy,
                                     float dz, double nx, double ny, double nz, float uLow,
                                     float uHigh, double variation, float tint, int material) {
        boolean up = orientationMatches(ax, ay, az, bx, by, bz, dx, dy, dz, nx, ny, nz);
        // Whichever way round the winding went, the four vertices are written in the order the GPU has
        // to read them -- that is what the flip is for -- so the indices are simply the four that were
        // just written, in the order they were written. Writing vertices without indices is what the
        // eleventh install shipped: `WorldRenderer.upload` refuses a mesh with no indices, so every palm
        // and every bush in the country existed in the vertex buffer and nowhere on the screen, and the
        // only visible symptom was a chunk that was cheaper than it should have been.
        int first = mesh.vertexCount();
        if (up) {
            addVertex(mesh, ax, ay, az, uLow, variation, tint, material);
            addVertex(mesh, bx, by, bz, uLow, variation, tint, material);
            addVertex(mesh, cx, cy, cz, uHigh, variation, tint, material);
            addVertex(mesh, dx, dy, dz, uHigh, variation, tint, material);
        } else {
            addVertex(mesh, dx, dy, dz, uHigh, variation, tint, material);
            addVertex(mesh, cx, cy, cz, uHigh, variation, tint, material);
            addVertex(mesh, bx, by, bz, uLow, variation, tint, material);
            addVertex(mesh, ax, ay, az, uLow, variation, tint, material);
        }
        mesh.addQuad(first, first + 1, first + 2, first + 3);
    }

    /** The same for a triangle. */
    private static void orientedTriangle(MeshData mesh, float ax, float ay, float az, float bx,
                                         float by, float bz, float cx, float cy, float cz,
                                         double nx, double ny, double nz, float u0, float u1,
                                         double variation, float tint, int material) {
        boolean up = orientationMatches(ax, ay, az, bx, by, bz, cx, cy, cz, nx, ny, nz);
        int first = mesh.vertexCount();
        if (up) {
            addVertex(mesh, ax, ay, az, u0, variation, tint, material);
            addVertex(mesh, bx, by, bz, u1, variation, tint, material);
            addVertex(mesh, cx, cy, cz, u1, variation, tint, material);
        } else {
            addVertex(mesh, ax, ay, az, u0, variation, tint, material);
            addVertex(mesh, cx, cy, cz, u1, variation, tint, material);
            addVertex(mesh, bx, by, bz, u1, variation, tint, material);
        }
        mesh.addTriangle(first, first + 1, first + 2);
    }

    /** True when the winding (a, b, c) has its front face towards the desired normal. */
    private static boolean orientationMatches(float ax, float ay, float az, float bx, float by,
                                              float bz, float cx, float cy, float cz, double nx,
                                              double ny, double nz) {
        double e1x = bx - ax;
        double e1y = by - ay;
        double e1z = bz - az;
        double e2x = cx - ax;
        double e2y = cy - ay;
        double e2z = cz - az;
        double crossX = e1y * e2z - e1z * e2y;
        double crossY = e1z * e2x - e1x * e2z;
        double crossZ = e1x * e2y - e1y * e2x;
        return crossX * nx + crossY * ny + crossZ * nz > 0.0;
    }

    /** One vegetation vertex: surface type, height along the plant, variation, kind, material. */
    private static void addVertex(MeshData mesh, float x, float y, float z, float up,
                                  double variation, float tint, int material) {
        // The normal is deliberately vertical: foliage is lit from above whatever direction its triangle
        // faces, which is both cheaper and closer to how a bush looks than a per-facet normal would be.
        mesh.addVertexMaterial(x, y, z, 0f, 1f, 0f, SURFACE, up, (float) variation, tint, material);
    }

    /**
     * A deterministic hash of a lattice cell, in 0..1.
     *
     * <p>Deterministic is the whole point: the same cell must yield the same plant every time a chunk is
     * built, or the country would re-shuffle itself whenever the player drove in a circle. The integer
     * mix is the one from the world compiler's own scatter -- bit shifts and a multiply, no allocation,
     * and identical on every device because it is integer arithmetic.
     */
    static double hash01(int x, int z) {
        int h = x * 0x27D4EB2D ^ z * 0x165667B1;
        h ^= h >>> 15;
        h *= 0x2545F491;
        h ^= h >>> 13;
        return (h & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
    }

    /** Winding-tolerant point-in-polygon by ray casting, on the ring's own coordinates. */
    private static boolean inside(Areas landuse, int ring, double east, double north) {
        int start = landuse.ringStart[ring];
        int length = landuse.ringLength[ring];
        boolean in = false;
        for (int i = 0, j = length - 1; i < length; j = i++) {
            double xi = landuse.xz[(start + i) * 2];
            double zi = landuse.xz[(start + i) * 2 + 1];
            double xj = landuse.xz[(start + j) * 2];
            double zj = landuse.xz[(start + j) * 2 + 1];
            if ((zi > north) != (zj > north)
                    && east < (xj - xi) * (north - zi) / (zj - zi) + xi) {
                in = !in;
            }
        }
        return in;
    }

    private static double clamp(double v, double low, double high) {
        return v < low ? low : (v > high ? high : v);
    }
}
