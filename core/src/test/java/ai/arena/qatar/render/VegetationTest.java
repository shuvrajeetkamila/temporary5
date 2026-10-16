package ai.arena.qatar.render;

import ai.arena.qatar.physics.SurfaceMaterial;
import ai.arena.qatar.world.Areas;
import ai.arena.qatar.world.MaterialGrid;
import ai.arena.qatar.world.TerrainGrid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Palms and scrub, and the three things that can go wrong with them:
 *
 * <ol>
 *   <li>They move when a chunk is rebuilt. A scatter that is not deterministic makes the country shuffle
 *       itself as the player drives in a circle, and no screenshot could tell that from working code.</li>
 *   <li>Their faces point inwards. This is the roof bug again, in new geometry: winding is invisible in
 *       the source and invisible in a screenshot until the camera moves. The vertices are read back here
 *       and every triangle's own cross product is checked against the direction away from its plant.</li>
 *   <li>They ignore the budget. The vertex allowance exists because a chunk that overruns an index buffer
 *       killed the third install; plants are the cheapest way to overrun one, since a scrub polygon
 *       holds thousands of lattice cells.</li>
 * </ol>
 */
final class VegetationTest {

    private static final int FLOATS = MeshData.FLOATS_PER_VERTEX;

    /** A flat world 8 m above the datum: the grid's heights are whole metres. */
    private static TerrainGrid flatTerrain(double size) {
        int cols = (int) (size / 100.0) + 4;
        short[] heights = new short[cols * cols];
        byte[] land = new byte[cols * cols];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = (short) GROUND_M;
            land[i] = 1;
        }
        return new TerrainGrid("QTT1", cols, cols, 100.0, -100.0, -100.0, heights, land);
    }

    /** The ground the test world stands on, in metres. */
    private static final int GROUND_M = 8;

    private static MaterialGrid materials(double size, int id) {
        int cols = (int) (size / 100.0) + 4;
        byte[] ids = new byte[cols * cols];
        java.util.Arrays.fill(ids, (byte) id);
        return new MaterialGrid(cols, cols, 100.0, -100.0, -100.0, ids);
    }

    /** One square vegetation polygon, 400 m on a side, at the origin. */
    private static Areas square(double side, int material) {
        float[] xz = {0f, 0f, (float) side, 0f, (float) side, (float) side, 0f, (float) side};
        return new Areas(1, new int[]{0}, new int[]{4}, xz, new byte[]{(byte) material},
                new byte[]{Areas.KIND_VEGETATION}, new byte[]{0}, new int[]{0},
                new float[]{0f, 0f, (float) side, (float) side});
    }

    private static VegetationMesher.Stats scatter(Areas landuse, TerrainGrid terrain,
                                                  MaterialGrid grid, MeshData mesh, double detail,
                                                  int budget) {
        return new VegetationMesher().meshInto(landuse, terrain, grid, mesh, 0.0, 0.0, 400.0, 400.0,
                detail, budget);
    }

    @Test
    @DisplayName("the scatter is deterministic: the same land grows the same plants twice")
    void scatterIsDeterministic() {
        Areas land = square(400.0, SurfaceMaterial.ID_FARM_SOIL);
        TerrainGrid terrain = flatTerrain(400.0);
        MaterialGrid grid = materials(400.0, SurfaceMaterial.ID_FARM_SOIL);

        MeshData first = new MeshData();
        VegetationMesher.Stats firstStats = scatter(land, terrain, grid, first, 1.0, 4000);
        MeshData second = new MeshData();
        VegetationMesher.Stats secondStats = scatter(land, terrain, grid, second, 1.0, 4000);

        assertTrue(firstStats.plants() > 20, "the scatter found almost nothing: " + firstStats.plants());
        assertEquals(firstStats.plants(), secondStats.plants(),
                "the same land produced a different number of plants the second time");
        assertEquals(first.vertexCount(), second.vertexCount(), "a rebuilt chunk moved its plants");
        for (int i = 0; i < first.vertexCount() * FLOATS; i++) {
            assertEquals(first.vertices()[i], second.vertices()[i], 1e-6f,
                    "vertex float " + i + " differs between two builds of the same chunk");
        }
    }

    @Test
    @DisplayName("every plant stands on the ground, inside its own polygon, and is painted as foliage")
    void plantsAreWhereTheyShouldBe() {
        Areas land = square(400.0, SurfaceMaterial.ID_FARM_SOIL);
        MeshData mesh = new MeshData();
        VegetationMesher.Stats stats = scatter(land, flatTerrain(400.0),
                materials(400.0, SurfaceMaterial.ID_FARM_SOIL), mesh, 1.0, 20_000);
        assertTrue(stats.plants() > 0, "no plants at all");

        for (int i = 0; i < mesh.vertexCount(); i++) {
            float x = mesh.vertices()[i * FLOATS];
            float y = mesh.vertices()[i * FLOATS + 1];
            float z = mesh.vertices()[i * FLOATS + 2];
            assertEquals(VertexFormat.SURFACE_VEGETATION, mesh.vertices()[i * FLOATS + 6], 1e-6f,
                    "a plant vertex is not painted as vegetation");
            assertTrue(x >= 0.0f && x <= 400.0f && z >= 0.0f && z <= 400.0f,
                    "a plant grew outside its polygon at " + x + ", " + z);
            // The trunk is set 25 cm into the ground so no seam shows, and a palm's crown is metres up.
            assertTrue(y >= GROUND_M - 0.3f, "a plant vertex is below the ground: " + y);
            assertTrue(y <= GROUND_M + 20.0f, "a plant is absurdly tall: " + y);
            // The ground is flat, so every plant's base sits on it and none of them floats.
            assertTrue(y <= GROUND_M + VegetationMesher.PALM_HEIGHT_MAX_M
                            + VegetationMesher.CROWN_RADIUS_MAX_M,
                    "a plant is taller than the mesher can draw: " + y);
        }
    }

    @Test
    @DisplayName("every triangle faces away from its plant: the roof bug's check, on new geometry")
    void everyTriangleFacesOutwards() {
        Areas land = square(400.0, SurfaceMaterial.ID_FARM_SOIL);
        MeshData mesh = new MeshData();
        scatter(land, flatTerrain(400.0), materials(400.0, SurfaceMaterial.ID_FARM_SOIL), mesh, 1.0,
                20_000);
        assertTrue(mesh.indexCount() > 0, "no triangles were written");

        // The mesher writes one plant's vertices, then the next plant's, so the plants can be recovered
        // from the mesh itself: a palm is 64 vertices and a bush is 24, and the tint says which.
        int vertex = 0;
        int plants = 0;
        while (vertex < mesh.vertexCount()) {
            boolean palm = mesh.vertices()[vertex * FLOATS + 9] < 0.5f;
            int size = palm ? VegetationMesher.PALM_VERTICES : VegetationMesher.BUSH_VERTICES;
            assertTrue(vertex + size <= mesh.vertexCount(),
                    "a plant runs off the end of its own vertex group");
            for (int i = vertex; i < vertex + size; i++) {
                assertEquals(palm ? 0.0f : 1.0f, mesh.vertices()[i * FLOATS + 9], 1e-6f,
                        "a plant's own vertices disagree about what kind of plant it is");
            }
            // The plant's axis: the mean of its own vertices' horizontal position, which for a trunk, a
            // cone and a dome alike is where the plant stands.
            double axisEast = 0.0;
            double axisNorth = 0.0;
            for (int i = vertex; i < vertex + size; i++) {
                axisEast += mesh.vertices()[i * FLOATS];
                axisNorth += mesh.vertices()[i * FLOATS + 2];
            }
            axisEast /= size;
            axisNorth /= size;

            for (int triangle = 0; triangle < mesh.indexCount() / 3; triangle++) {
                int a = mesh.indices()[triangle * 3];
                int b = mesh.indices()[triangle * 3 + 1];
                int c = mesh.indices()[triangle * 3 + 2];
                if (a < vertex || a >= vertex + size) {
                    continue;                          // this triangle belongs to another plant
                }
                double ax = mesh.vertices()[a * FLOATS];
                double ay = mesh.vertices()[a * FLOATS + 1];
                double az = mesh.vertices()[a * FLOATS + 2];
                double bx = mesh.vertices()[b * FLOATS];
                double by = mesh.vertices()[b * FLOATS + 1];
                double bz = mesh.vertices()[b * FLOATS + 2];
                double cx = mesh.vertices()[c * FLOATS];
                double cy = mesh.vertices()[c * FLOATS + 1];
                double cz = mesh.vertices()[c * FLOATS + 2];
                double e1x = bx - ax;
                double e1y = by - ay;
                double e1z = bz - az;
                double e2x = cx - ax;
                double e2y = cy - ay;
                double e2z = cz - az;
                double nx = e1y * e2z - e1z * e2y;
                double ny = e1z * e2x - e1x * e2z;
                double nz = e1x * e2y - e1y * e2x;
                double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                assertTrue(length > 1e-9, "triangle " + triangle + " has no area");
                nx /= length;
                ny /= length;
                nz /= length;
                double outwardEast = (ax + bx + cx) / 3.0 - axisEast;
                double outwardNorth = (az + bz + cz) / 3.0 - axisNorth;
                double outward = nx * outwardEast + nz * outwardNorth;
                // A face of a plant looks away from the plant's own axis, or up. A face pointing at the
                // axis is a face the GPU culls, and the plant then reads as a hole in the ground.
                assertTrue(outward > 0.0 || ny > 0.3,
                        "a " + (palm ? "palm" : "bush") + " has a face pointing inwards: normal ("
                                + (float) nx + ", " + (float) ny + ", " + (float) nz
                                + ") against an outward direction of " + (float) outward);
            }
            vertex += size;
            plants++;
        }
        assertTrue(plants > 5, "only " + plants + " plants were checked");
    }

    @Test
    @DisplayName("the vertex allowance is spent before a plant is written, never after")
    void budgetIsRespected() {
        Areas land = square(400.0, SurfaceMaterial.ID_FARM_SOIL);
        MeshData mesh = new MeshData();
        VegetationMesher.Stats stats = scatter(land, flatTerrain(400.0),
                materials(400.0, SurfaceMaterial.ID_FARM_SOIL), mesh, 1.0, 200);
        assertTrue(stats.budgetReached, "a 200-vertex allowance did not stop the scatter");
        assertTrue(stats.vertices() <= 200,
                "the scatter spent " + stats.vertices() + " vertices of a 200 vertex allowance");
        assertEquals(stats.vertices(), mesh.vertexCount(),
                "the count and the mesh disagree about how much was written");
        assertTrue(stats.plants() > 0, "the scatter gave up without planting anything");
    }

    @Test
    @DisplayName("nothing grows in water, and nothing grows so far away that it is fog")
    void waterAndDistanceGrowNothing() {
        Areas land = square(400.0, SurfaceMaterial.ID_FARM_SOIL);
        MeshData onWater = new MeshData();
        VegetationMesher.Stats wet = scatter(land, flatTerrain(400.0),
                materials(400.0, SurfaceMaterial.ID_OPEN_WATER), onWater, 1.0, 20_000);
        assertEquals(0, wet.plants(), "palms were grown out of the sea");
        assertEquals(0, onWater.vertexCount(), "vertices were written for plants that do not exist");

        MeshData far = new MeshData();
        VegetationMesher.Stats distant = scatter(land, flatTerrain(400.0),
                materials(400.0, SurfaceMaterial.ID_FARM_SOIL), far,
                VegetationMesher.MIN_DETAIL - 0.01, 20_000);
        assertEquals(0, distant.plants(), "plants were scattered at a detail level that means fog");
    }

    @Test
    @DisplayName("scrub grows low bushes, and a farm grows palms among them")
    void theMaterialPicksThePlants() {
        MeshData farm = new MeshData();
        VegetationMesher.Stats planted = scatter(square(400.0, SurfaceMaterial.ID_FARM_SOIL),
                flatTerrain(400.0), materials(400.0, SurfaceMaterial.ID_FARM_SOIL), farm, 1.0, 30_000);
        assertTrue(planted.palms > 0, "a farm with no palms in it");
        assertTrue(planted.bushes > 0, "a farm with no low planting in it");

        MeshData marsh = new MeshData();
        VegetationMesher.Stats wet = scatter(square(400.0, SurfaceMaterial.ID_SALT_MARSH_MANGROVE),
                flatTerrain(400.0), materials(400.0, SurfaceMaterial.ID_SALT_MARSH_MANGROVE), marsh,
                1.0, 30_000);
        assertEquals(0, wet.palms, "palms were grown in a mangrove");
        assertTrue(wet.bushes > 0, "a mangrove with nothing in it");
        assertFalse(wet.budgetReached, "a small mangrove hit the vertex budget");
    }

    @Test
    @DisplayName("the reported cost is what was written, plant for plant")
    void theCostsAreReal() {
        Areas land = square(400.0, SurfaceMaterial.ID_FARM_SOIL);
        MeshData mesh = new MeshData();
        VegetationMesher.Stats stats = scatter(land, flatTerrain(400.0),
                materials(400.0, SurfaceMaterial.ID_FARM_SOIL), mesh, 1.0, 30_000);
        // The vertex count is the plants' own cost, exactly: the mesher spends its budget before it
        // writes, so a chunk that reports 3,000 vertices promised 3,000 vertices.
        assertEquals(stats.vertices(), mesh.vertexCount(),
                "the scatter reported " + stats.vertices() + " vertices and wrote "
                        + mesh.vertexCount());
        // And a budget between the two plant sizes plants what fits and stops: never a half a palm.
        MeshData tight = new MeshData();
        VegetationMesher.Stats squeezed = scatter(land, flatTerrain(400.0),
                materials(400.0, SurfaceMaterial.ID_FARM_SOIL), tight, 1.0,
                Math.min(VegetationMesher.PALM_VERTICES, VegetationMesher.BUSH_VERTICES));
        assertTrue(squeezed.vertices() <= Math.min(VegetationMesher.PALM_VERTICES,
                        VegetationMesher.BUSH_VERTICES),
                "a " + squeezed.vertices() + "-vertex scatter fit in a bush-sized allowance");
        assertEquals(squeezed.vertices(), tight.vertexCount(), "the mesher and the count disagree");
    }
}
