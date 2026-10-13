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
        double cell = terrain.cellM;

        int col0 = clamp((int) Math.floor((minEast - terrain.originEast) / cell - 1), 0, terrain.cols - 1);
        int col1 = clamp((int) Math.ceil((maxEast - terrain.originEast) / cell + 1), 0, terrain.cols - 1);
        int row0 = clamp((int) Math.floor((minNorth - terrain.originNorth) / cell - 1), 0, terrain.rows - 1);
        int row1 = clamp((int) Math.ceil((maxNorth - terrain.originNorth) / cell + 1), 0, terrain.rows - 1);
        if (col1 <= col0 || row1 <= row0) {
            return mesh;
        }

        double originEast = terrain.originEast;
        double originNorth = terrain.originNorth;

        // One vertex grid for the chunk, reused between the land and water passes.
        int cols = col1 - col0 + 1;
        int rows = row1 - row0 + 1;
        int[] vertexIndex = new int[cols * rows];
        java.util.Arrays.fill(vertexIndex, -1);

        if (land) {
            // Build only the land vertices: water cells become holes, so the coast is exact.
            for (int row = row0; row <= row1; row++) {
                for (int col = col0; col <= col1; col++) {
                    if (terrainCellIsWater(col, row)) {
                        continue;
                    }
                    int local = (row - row0) * cols + (col - col0);
                    vertexIndex[local] = addLandVertex(mesh, col, row, originEast, originNorth, cell);
                }
            }
            for (int row = row0; row < row1; row++) {
                for (int col = col0; col < col1; col++) {
                    int a = vertexIndex[(row - row0) * cols + (col - col0)];
                    int b = vertexIndex[(row - row0) * cols + (col - col0 + 1)];
                    int c = vertexIndex[(row - row0 + 1) * cols + (col - col0 + 1)];
                    int d = vertexIndex[(row - row0 + 1) * cols + (col - col0)];
                    // A cell is drawn only when all four corners are land: the shoreline stays crisp.
                    if (a < 0 || b < 0 || c < 0 || d < 0) {
                        continue;
                    }
                    // Counter-clockwise seen from above (+Y), so the default GL winding faces up.
                    mesh.addQuad(a, d, c, b);
                }
            }
            return mesh;
        }

        // Water: a plane at sea level over every cell that touches water, drawn double sided by the
        // shader, so a lake seen from a boat does not disappear.
        for (int row = row0; row <= row1; row++) {
            for (int col = col0; col <= col1; col++) {
                if (!terrainCellIsWater(col, row)) {
                    continue;
                }
                int local = (row - row0) * cols + (col - col0);
                vertexIndex[local] = addWaterVertex(mesh, col, row, originEast, originNorth, cell);
            }
        }
        for (int row = row0; row < row1; row++) {
            for (int col = col0; col < col1; col++) {
                int a = vertexIndex[(row - row0) * cols + (col - col0)];
                int b = vertexIndex[(row - row0) * cols + (col - col0 + 1)];
                int c = vertexIndex[(row - row0 + 1) * cols + (col - col0 + 1)];
                int d = vertexIndex[(row - row0 + 1) * cols + (col - col0)];
                if (a < 0 || b < 0 || c < 0 || d < 0) {
                    continue;
                }
                mesh.addQuad(a, d, c, b);
            }
        }
        return mesh;
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
        double height = terrain.heights[row * terrain.cols + col];
        int material = materials == null ? 5 : materials.idAt(east, north);
        float[] slope = slopeAt(col, row, originEast, originNorth, cell);
        // Texture coordinates: one tile per 25 m, so surface detail keeps a constant real-world size.
        float u = (float) ((east - originEast) / 25.0);
        float v = (float) ((north - originNorth) / 25.0);
        float tint = surfaceTint(material, slope[0], slope[1], slope[2]);
        return mesh.addVertex((float) (east - originEast), (float) height + LAND_LIFT,
                (float) (north - originNorth), slope[0], slope[1], slope[2], SURFACE_LAND, u, v, tint);
    }

    private int addWaterVertex(MeshData mesh, int col, int row, double originEast, double originNorth,
                               double cell) {
        double east = originEast + col * cell;
        double north = originNorth + row * cell;
        int material = materials == null ? 16 : materials.idAt(east, north);
        float u = (float) ((east - originEast) / 25.0);
        float v = (float) ((north - originNorth) / 25.0);
        float tint = material == 15 ? 0.35f : 0.8f; // shallow water reads lighter than open sea
        return mesh.addVertex((float) (east - originEast), SEA_LEVEL + WATER_LIFT,
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
