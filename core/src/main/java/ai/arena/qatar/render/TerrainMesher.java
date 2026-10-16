package ai.arena.qatar.render;

import ai.arena.qatar.world.MaterialGrid;
import ai.arena.qatar.world.TerrainGrid;

/**
 * Turns the compiled national heightfield into triangles: land as a surface, water as a flat plane at
 * sea level, in chunks small enough to stream and cull.
 *
 * <p>Land and water are separate meshes on purpose. Water is a plane at zero with its own material, so
 * a chunk can draw a lake without the terrain beneath it leaking through, and the shore is where the
 * land mask says it is -- the same mask the physics sampler reads.
 *
 * <p>Surface type and tint per vertex come from the compiled material grid, which is why the country
 * looks like Qatar and not like one pink rectangle: rock, sand, sabkha, mangrove and urban pads are all
 * real classes from the data.
 *
 * <h2>The coastline, and the hole that was along all of it</h2>
 *
 * <p>A cell of the grid is classified once -- land or water -- by the flag at the corner it starts at, and
 * each layer meshes the cells it owns and nothing else. That sounds obvious, and it is the fix for a bug
 * that had been in every build: the land pass drew a cell only when all four of its corners were land, and
 * the water pass drew one only when all four were water, so a cell straddling the shore was drawn by
 * neither. Two hundred metres of nothing ran along every coast in the country, with the sky behind it,
 * exactly where a player in a boat or a plane looks hardest.
 *
 * <p>The heights then have to agree, or the fix would only trade one gap for another: the height of a
 * lattice point is the same in both layers ({@link #latticeHeight}), so a shared edge is one edge and not
 * two surfaces that nearly touch.
 */
public final class TerrainMesher {

    /** Sea level, in metres, for the whole world (the compiled grid clamps water to 0). */
    public static final float SEA_LEVEL = 0.0f;

    /** A little above the simulated surface so the water plane never z-fights with the seabed. */
    private static final float WATER_LIFT = 0.05f;
    /** Terrain is lifted on land by the same amount so roads and buildings sit on it cleanly. */
    private static final float LAND_LIFT = 0.02f;

    /** Surface type ids passed to the shader; they select the shading branch, not the material. */
    public static final float SURFACE_LAND = 0.0f;
    public static final float SURFACE_WATER = 1.0f;

    private final TerrainGrid terrain;
    private final MaterialGrid materials;

    public TerrainMesher(TerrainGrid terrain, MaterialGrid materials) {
        this.terrain = terrain;
        this.materials = materials;
    }

    /**
     * Meshes the grid cells whose centres fall inside the given rectangle, expressed in metres
     * relative to the manifest grid origin. Vertices are emitted in the same relative space.
     *
     * @param land true to emit the dry surface, false to emit the water plane
     */
    public MeshData mesh(double minEast, double minNorth, double maxEast, double maxNorth, boolean land) {
        MeshData mesh = new MeshData();
        meshInto(mesh, minEast, minNorth, maxEast, maxNorth, land);
        return mesh;
    }

    /** Meshes straight into a caller-provided mesh (chunk assembly), avoiding a temporary buffer. */
    public void meshInto(MeshData mesh, double minEast, double minNorth, double maxEast, double maxNorth,
                         boolean land) {
        double cell = terrain.cellM;

        // The rectangle is in bundle space (metres from the manifest grid origin) and the terrain grid
        // starts at that same origin, so the cell maths is a plain division. One extra cell of margin
        // on each side keeps a chunk's edge continuous with its neighbour.
        int col0 = clamp((int) Math.floor(minEast / cell) - 1, 0, terrain.cols - 1);
        int col1 = clamp((int) Math.ceil(maxEast / cell) + 1, 0, terrain.cols - 1);
        int row0 = clamp((int) Math.floor(minNorth / cell) - 1, 0, terrain.rows - 1);
        int row1 = clamp((int) Math.ceil(maxNorth / cell) + 1, 0, terrain.rows - 1);
        if (col1 <= col0 || row1 <= row0) {
            return;
        }

        double originEast = terrain.originEast;
        double originNorth = terrain.originNorth;

        // One vertex grid for the chunk, reused between the land and water passes.
        int cols = col1 - col0 + 1;
        int rows = row1 - row0 + 1;
        int[] vertexIndex = new int[cols * rows];
        java.util.Arrays.fill(vertexIndex, -1);

        if (land) {
            for (int row = row0; row < row1; row++) {
                for (int col = col0; col < col1; col++) {
                    // A cell belongs to the layer its own classification names, and to exactly one of them.
                    // Before this, the land pass drew a cell only when all four of its corners were land and
                    // the water pass drew one only when all four were water, so every cell straddling the
                    // shore was drawn by neither: a band up to two cells wide -- two hundred metres at the
                    // detailed resolution -- of nothing at all along every coast in the country, with the
                    // sky behind it. Going out to sea in a boat meant looking down at a hole where the beach
                    // should be, and the same hole ran along the whole shore seen from the air.
                    if (terrainCellIsWater(col, row)) {
                        continue;
                    }
                    int a = latticeVertex(mesh, vertexIndex, cols, row0, col0, true, col, row, originEast, originNorth, cell);
                    int b = latticeVertex(mesh, vertexIndex, cols, row0, col0, true, col + 1, row, originEast, originNorth, cell);
                    int c = latticeVertex(mesh, vertexIndex, cols, row0, col0, true, col + 1, row + 1, originEast, originNorth, cell);
                    int d = latticeVertex(mesh, vertexIndex, cols, row0, col0, true, col, row + 1, originEast, originNorth, cell);
                    // Counter-clockwise seen from above (+Y), so the default GL winding faces up.
                    mesh.addQuad(a, d, c, b);
                }
            }
            return;
        }

        // Water: the sea's own cells, drawn double sided by the shader, so a lake seen from a boat does
        // not disappear. A cell whose corners are all its own is a flat sheet at sea level; the corners
        // it shares with the land are at the land's height, so the water meets the shore exactly and there
        // is no sliver of nothing between the two layers for the player to see the sky through.
        for (int row = row0; row < row1; row++) {
            for (int col = col0; col < col1; col++) {
                if (!terrainCellIsWater(col, row)) {
                    continue;
                }
                int a = latticeVertex(mesh, vertexIndex, cols, row0, col0, false, col, row, originEast, originNorth, cell);
                int b = latticeVertex(mesh, vertexIndex, cols, row0, col0, false, col + 1, row, originEast, originNorth, cell);
                int c = latticeVertex(mesh, vertexIndex, cols, row0, col0, false, col + 1, row + 1, originEast, originNorth, cell);
                int d = latticeVertex(mesh, vertexIndex, cols, row0, col0, false, col, row + 1, originEast, originNorth, cell);
                mesh.addQuad(a, d, c, b);
            }
        }
    }

    /**
     * The vertex for one lattice point, added the first time this pass needs it.
     *
     * <p>Shared between the cells of a pass, and only the cells of that pass: a land quad and a sea quad
     * may be drawn at the same point at different heights, which is exactly what a coastline is, and each
     * pass keeps its own copy.
     */
    private int latticeVertex(MeshData mesh, int[] vertexIndex, int cols, int row0, int col0,
                              boolean land, int col, int row, double originEast, double originNorth,
                              double cell) {
        int local = (row - row0) * cols + (col - col0);
        if (vertexIndex[local] < 0) {
            vertexIndex[local] = land
                    ? addLandVertex(mesh, col, row, originEast, originNorth, cell)
                    : addWaterVertex(mesh, col, row, originEast, originNorth, cell);
        }
        return vertexIndex[local];
    }

    /**
     * True when the cell that <em>starts</em> at this lattice point is water.
     *
     * <p>This is the classification the two passes divide the world by, and the indexing is the whole
     * subtlety: the grid stores one height and one land flag per lattice point, the cell a mesher draws at
     * {@code (col, row)} spans the lattice points {@code (col, row)} to {@code (col + 1, row + 1)}, and the
     * flag at the corner it starts at is therefore that cell's own flag. A point past the last column or
     * row counts as water: there is no cell there to draw, and the edge of the compiled country is the sea.
     */

    /**
     * The height of a lattice point, which both layers agree on.
     *
     * <p>Two cases, and the coastline is the interesting one:
     * <ul>
     *   <li><b>The point's own cell is land.</b> Its compiled height plus the terrain lift -- the ground as
     *       the rest of the world sees it. A water cell that reaches this point therefore rises to the
     *       shore, which is what a sea meeting a beach does.</li>
     *   <li><b>The point's own cell is water.</b> The sea surface (with the water lift), whether the point
     *       is drawn as sea or as the last few metres of the beach. The compiled bathymetry is deliberately
     *       not used here: a corner dropped to the seabed would hang the shoreline over the water it stands
     *       in, and the player would see the sky through the gap.</li>
     * </ul>
     */
    private double latticeHeight(int col, int row) {
        if (terrainCellIsWater(col, row)) {
            return SEA_LEVEL + WATER_LIFT;
        }
        return terrain.heights[row * terrain.cols + col] + LAND_LIFT;
    }

    private boolean terrainCellIsWater(int col, int row) {
        if (col >= terrain.cols || row >= terrain.rows) {
            return true;
        }
        return terrain.landMask[row * terrain.cols + col] == 0;
    }

    private int addLandVertex(MeshData mesh, int col, int row, double originEast, double originNorth,
                              double cell) {
        double east = originEast + col * cell;
        double north = originNorth + row * cell;
        boolean water = terrainCellIsWater(col, row);
        // Over water the vertex is the waterline, not the bathymetry. The tide-line rule in the shader
        // already paints a land square whose material is 15 or 16 as wet sand, which is exactly what this
        // vertex is, and its height is the sea surface, so the beach and the water meet along their shared
        // edge with no step and no gap between them.
        double height = latticeHeight(col, row);
        int material = materials == null ? (water ? 16 : 5) : materials.idAt(east, north);
        float[] slope = slopeAt(col, row, originEast, originNorth, cell);
        // Texture coordinates: one tile per 25 m, so surface detail keeps a constant real-world size.
        float u = (float) ((east - originEast) / 25.0);
        float v = (float) ((north - originNorth) / 25.0);
        float tint = surfaceTint(material, slope[0], slope[1], slope[2]);
        // The material id travels with the vertex: the shader's own palette says sabkha is pale grey
        // and mangrove is dark green, and only the id can tell it so.
        return mesh.addVertexMaterial((float) (east - originEast), (float) height,
                (float) (north - originNorth), slope[0], slope[1], slope[2], SURFACE_LAND, u, v, tint,
                material);
    }

    private int addWaterVertex(MeshData mesh, int col, int row, double originEast, double originNorth,
                               double cell) {
        double east = originEast + col * cell;
        double north = originNorth + row * cell;
        // The sea is flat except where it shares a corner with the land, and there it rises to meet it: a
        // water cell's outer corner sits at the land's own height, so the two layers share an edge instead
        // of leaving a step the player can see through. Over open water this is sea level, to the metre.
        double height = latticeHeight(col, row);
        int material = materials == null ? 16 : materials.idAt(east, north);
        float u = (float) ((east - originEast) / 25.0);
        float v = (float) ((north - originNorth) / 25.0);
        float tint = material == 15 ? 0.35f : 0.8f; // shallow water reads lighter than open sea
        return mesh.addVertex((float) (east - originEast), (float) height,
                (float) (north - originNorth), 0.0f, 1.0f, 0.0f, SURFACE_WATER, u, v, tint);
    }

    /** Terrain normal from central differences on the height grid. */
    private float[] slopeAt(int col, int row, double originEast, double originNorth, double cell) {
        double hL = heightClamped(col - 1, row);
        double hR = heightClamped(col + 1, row);
        double hD = heightClamped(col, row - 1);
        double hU = heightClamped(col, row + 1);
        double dx = (hR - hL) / (2 * cell);
        double dz = (hU - hD) / (2 * cell);
        double nx = -dx;
        double nz = -dz;
        double length = Math.sqrt(nx * nx + 1.0 + nz * nz);
        return new float[]{(float) (nx / length), (float) (1.0 / length), (float) (nz / length)};
    }

    private double heightClamped(int col, int row) {
        int c = clamp(col, 0, terrain.cols - 1);
        int r = clamp(row, 0, terrain.rows - 1);
        return terrain.heights[r * terrain.cols + c];
    }

    /**
     * Per-material tint: a cheap, data-driven variation so that sand, rock and vegetation do not read
     * as one flat colour. Values are relative multipliers applied by the shader to its base palette.
     */
    private static float surfaceTint(int materialId, float nx, float ny, float nz) {
        float slope = 1.0f - Math.max(0.0f, ny);            // 0 flat .. 1 vertical
        float base;
        switch (materialId) {
            case 5: base = 0.75f; break;                    // gravel/rock: darker, dustier
            case 6: base = 0.95f; break;                    // compact sand
            case 7: base = 1.0f; break;                     // loose dune sand
            case 8: base = 0.85f; break;                    // wet sand
            case 9: base = 0.7f; break;                     // sabkha: pale grey
            case 10: base = 0.6f; break;                    // tidal mud: dark
            case 11: base = 0.45f; break;                   // mangrove: dark green
            case 12: base = 0.8f; break;                    // farm soil
            case 13: base = 0.9f; break;                    // urban pad
            case 14: base = 0.88f; break;                   // reclaimed land
            default: base = 0.8f; break;
        }
        return Math.max(0.05f, base - slope * 0.2f);
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }
}
