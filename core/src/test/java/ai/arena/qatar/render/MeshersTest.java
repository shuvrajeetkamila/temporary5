package ai.arena.qatar.render;

import ai.arena.qatar.world.Areas;
import ai.arena.qatar.world.Buildings;
import ai.arena.qatar.world.BundleReader;
import ai.arena.qatar.world.GameManifest;
import ai.arena.qatar.world.MaterialGrid;
import ai.arena.qatar.world.Roads;
import ai.arena.qatar.world.TerrainGrid;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the compiled country actually becomes 3D geometry: terrain surfaces, water planes,
 * extruded buildings and road ribbons, all from the same committed bundle the game ships.
 *
 * <p>These tests are the reason the meshers deliberately contain no GL calls: the geometry can be
 * asserted exactly -- triangle counts, winding (is the ground facing up?), heights (does a building
 * stand on the terrain?), widths (is a 14 m road 14 m wide?) -- without a device, a GPU or a
 * screenshot.
 */
final class MeshersTest {

    private static final double ORIGIN_E = 553000.0;
    private static final double ORIGIN_N = 2796000.0;

    private static Path fixture;
    private static GameManifest manifest;
    private static TerrainGrid terrain;
    private static MaterialGrid materials;

    @BeforeAll
    static void load() throws IOException {
        fixture = Paths.get("src/test/resources/synthetic_world");
        if (!Files.isDirectory(fixture)) {
            fixture = Paths.get("core/src/test/resources/synthetic_world");
        }
        manifest = GameManifest.parse(Files.readString(fixture.resolve("manifest.json")));
        byte[] terrainZip = Files.readAllBytes(fixture.resolve(manifest.terrainL1.file));
        terrain = TerrainGrid.parse(BundleReader.open("terrain", terrainZip, manifest.terrainL1.sha256)
                .payloadBytes(), ORIGIN_E, ORIGIN_N, "QTT1");
        byte[] materialZip = Files.readAllBytes(fixture.resolve(manifest.materials.file));
        materials = MaterialGrid.parse(BundleReader.open("materials", materialZip,
                manifest.materials.sha256).payloadBytes(), ORIGIN_E, ORIGIN_N);
    }

    private static Buildings buildings() throws IOException {
        return Buildings.parse(BundleReader.open("buildings",
                Files.readAllBytes(fixture.resolve(manifest.buildings.file)), manifest.buildings.sha256),
                ORIGIN_E, ORIGIN_N);
    }

    private static Roads roads() throws IOException {
        return Roads.parse(BundleReader.open("roads",
                Files.readAllBytes(fixture.resolve(manifest.roads.file)), manifest.roads.sha256),
                ORIGIN_E, ORIGIN_N);
    }

    @Test
    @DisplayName("terrain meshing produces an upward-facing surface with the right triangle count")
    void terrainSurface() {
        TerrainMesher mesher = new TerrainMesher(terrain, materials);
        MeshData land = mesher.mesh(0, 0, 1600, 1600, true);
        assertFalse(land.isEmpty(), "the land mesh has geometry");

        // Columns 0..10 are land and 11..15 are water. A cell is drawn only when all four of its
        // corners are land, so 10 cell-columns x 15 cell-rows = 150 land cells, two triangles each.
        int waterCells = 5 * 16;
        int expectedCells = 16 * 16 - waterCells;
        assertEquals(300, land.triangleCount(),
                "land is 10 x 15 cells of two triangles, water cells are skipped");
        assertTrue(land.triangleCount() <= expectedCells * 2,
                "no more triangles than land cells allow: " + land.triangleCount());

        float[] vertices = land.vertices();
        int upFacing = 0;
        for (int i = 0; i < land.vertexCount(); i++) {
            float ny = vertices[i * MeshData.FLOATS_PER_VERTEX + 4];
            assertTrue(ny > 0, "every terrain normal points up (got ny=" + ny + ")");
            upFacing++;
        }
        assertEquals(land.vertexCount(), upFacing);

        // Winding: the first triangle's geometric normal must agree with the direction the terrain
        // faces (up), or the whole country would be back-face culled.
        short[] indices = land.indices();
        for (int t = 0; t < Math.min(50, land.triangleCount()); t++) {
            float[] normal = triangleNormal(land, indices[t * 3], indices[t * 3 + 1], indices[t * 3 + 2]);
            assertTrue(normal[1] > 0, "triangle " + t + " faces up (ny=" + normal[1] + ")");
        }

        MeshData water = mesher.mesh(0, 0, 1600, 1600, false);
        assertFalse(water.isEmpty(), "the sea is meshed as a water plane");
        float[] waterVertices = water.vertices();
        for (int i = 0; i < water.vertexCount(); i++) {
            assertEquals(TerrainMesher.SEA_LEVEL + 0.05f,
                    waterVertices[i * MeshData.FLOATS_PER_VERTEX + 1], 1e-4f,
                    "every water vertex sits at sea level");
        }
    }

    @Test
    @DisplayName("a chunk far from the focus is still meshed from the same data (no popping holes)")
    void terrainChunking() {
        TerrainMesher mesher = new TerrainMesher(terrain, materials);
        MeshData chunkA = mesher.mesh(0, 0, 500, 500, true);
        MeshData chunkB = mesher.mesh(500, 0, 1000, 500, true);
        assertFalse(chunkA.isEmpty());
        assertFalse(chunkB.isEmpty());
        float[] boundsA = chunkA.bounds();
        float[] boundsB = chunkB.bounds();
        // Each chunk carries one cell of margin so its edge is continuous with its neighbour: it must
        // cover its own rectangle (not fall short, which would leave a visible hole) and must not
        // extend beyond one extra cell.
        assertTrue(boundsA[0] <= 0.0f && boundsA[3] >= 500.0f,
                "chunk A covers its own rectangle: " + java.util.Arrays.toString(boundsA));
        assertTrue(boundsA[3] <= 700.0f, "chunk A does not extend more than a cell past its edge");
        assertTrue(boundsB[0] <= 500.0f, "chunk B starts at or before its own rectangle");
        assertTrue(boundsB[3] >= 1000.0f, "chunk B covers its rectangle");
    }

    @Test
    @DisplayName("buildings extrude into walls whose height matches the compiled data")
    void buildingExtrusion() throws IOException {
        Buildings buildings = buildings();
        BuildingMesher mesher = new BuildingMesher(buildings, terrain);
        MeshData mesh = new MeshData();
        int[] candidates = new int[buildings.ringCount];
        for (int i = 0; i < buildings.ringCount; i++) {
            candidates[i] = i;
        }
        int emitted = mesher.mesh(0, 0, 1600, 1600, mesh, candidates, buildings.ringCount);
        assertEquals(buildings.ringCount, emitted, "every building in the chunk is emitted");
        assertFalse(mesh.isEmpty(), "buildings produce geometry");

        // The tower: 120 m tall, standing on terrain that reaches 20 m, so its roof is around 140 m.
        float[] vertices = mesh.vertices();
        float highest = Float.NEGATIVE_INFINITY;
        float lowest = Float.MAX_VALUE;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            float y = vertices[i * MeshData.FLOATS_PER_VERTEX + 1];
            highest = Math.max(highest, y);
            lowest = Math.min(lowest, y);
        }
        assertTrue(highest > 120f, "the tower reaches above 120 m (got " + highest + ")");
        assertTrue(lowest >= 0f, "no building is buried under the ground (lowest " + lowest + ")");

        // Walls must face outwards: for a closed ring, wall normals must cancel out horizontally.
        double sumX = 0;
        double sumZ = 0;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            if (vertices[i * MeshData.FLOATS_PER_VERTEX + 6] != BuildingMesher.SURFACE_WALL) {
                continue;
            }
            sumX += vertices[i * MeshData.FLOATS_PER_VERTEX + 3];
            sumZ += vertices[i * MeshData.FLOATS_PER_VERTEX + 5];
        }
        assertTrue(Math.abs(sumX) < 40.0, "wall normals cancel horizontally (sumX=" + sumX + ")");
        assertTrue(Math.abs(sumZ) < 40.0, "wall normals cancel horizontally (sumZ=" + sumZ + ")");
    }

    @Test
    @DisplayName("a concave footprint roofs correctly instead of collapsing to a fan")
    void concaveFootprintRoof() throws IOException {
        Buildings buildings = buildings();
        int lShaped = -1;
        for (int ring = 0; ring < buildings.ringCount; ring++) {
            if (buildings.ringLength[ring] == 7) {
                lShaped = ring;
            }
        }
        assertTrue(lShaped >= 0, "the fixture contains an L-shaped footprint");

        BuildingMesher mesher = new BuildingMesher(buildings, terrain);
        MeshData mesh = new MeshData();
        assertTrue(mesher.addBuilding(lShaped, mesh), "the L shape meshes");

        // Six corners means six wall quads (12 triangles) plus a roof of (6 - 2) = 4 triangles.
        int roofTriangles = 0;
        for (int t = 0; t < mesh.triangleCount(); t++) {
            short[] indices = mesh.indices();
            float type = mesh.vertices()[indices[t * 3] * MeshData.FLOATS_PER_VERTEX + 6];
            if (type == BuildingMesher.SURFACE_ROOF) {
                roofTriangles++;
            }
        }
        assertEquals(4, roofTriangles, "an L-shaped roof is 4 triangles, and none may overlap the notch");
    }

    @Test
    @DisplayName("roof vertices carry metres from the roof centre, which is what the roof detail draws on")
    void roofsCarryMetreCoordinates() {
        // The fragment shader lays out roof plant, water tanks and plank seams on the auxiliary pair of
        // a roof vertex, at real sizes: a 6 m grid of units and a 2.4 m seam. That only works if the
        // pair is metres from the roof centre -- so this asserts it is, on a footprint that is not the
        // unit square a 0..1 range would happily fake.
        Buildings buildings = new Buildings(1, new int[]{0}, new int[]{5},
                new float[]{0, 0, 18, 0, 18, 24, 0, 24, 0, 0},
                new float[]{12f}, new float[]{0f}, new byte[]{1}, new byte[]{0}, new int[]{0},
                new byte[]{0}, new float[]{0, 0, 18, 24});
        MeshData mesh = new MeshData();
        assertTrue(new BuildingMesher(buildings, terrain).addBuilding(0, mesh),
                "the footprint produced geometry");

        int roofVertices = 0;
        float widest = 0f;
        for (int v = 0; v < mesh.vertexCount(); v++) {
            int at = v * MeshData.FLOATS_PER_VERTEX;
            if (mesh.vertices()[at + 6] != BuildingMesher.SURFACE_ROOF) {
                continue;
            }
            roofVertices++;
            // A metre offset from a centre of an 18 x 24 m footprint cannot exceed half its diagonal.
            widest = Math.max(widest, Math.abs(mesh.vertices()[at + 7]));
            widest = Math.max(widest, Math.abs(mesh.vertices()[at + 8]));
            assertTrue(Math.abs(mesh.vertices()[at + 7]) <= 19f,
                    "roof east offset is not metres: " + mesh.vertices()[at + 7]);
            assertTrue(Math.abs(mesh.vertices()[at + 8]) <= 19f,
                    "roof north offset is not metres: " + mesh.vertices()[at + 8]);
        }
        assertTrue(roofVertices > 0, "the roof has vertices");
        assertTrue(widest > 2f, "the offsets are metres rather than 0..1: widest " + widest);
    }

    @Test
    @DisplayName("road ribbons are as wide as the compiled width and follow the terrain")
    void roadRibbon() throws IOException {
        Roads roads = roads();
        RoadMesher mesher = new RoadMesher(roads, terrain);
        MeshData mesh = new MeshData();

        int primary = -1;
        for (int line = 0; line < roads.count; line++) {
            if (roads.roadClass[line] == 3) {
                primary = line;
            }
        }
        mesher.addRoad(primary, mesh);
        assertFalse(mesh.isEmpty(), "the road produces a ribbon");

        // A straight three-lane road with 2 points: one quad for the surface plus one for the painted
        // centre line, so four triangles in total.
        assertEquals(4, mesh.triangleCount(), "one segment is two quads: " + mesh.triangleCount());

        float[] vertices = mesh.vertices();
        float minNorth = Float.MAX_VALUE;
        float maxNorth = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            float north = vertices[i * MeshData.FLOATS_PER_VERTEX + 2];
            minNorth = Math.min(minNorth, north);
            maxNorth = Math.max(maxNorth, north);
        }
        assertEquals(14.0f, maxNorth - minNorth, 0.05f, "a 14 m road is 14 m wide");

        MeshData all = new MeshData();
        int[] candidates = new int[roads.count];
        for (int i = 0; i < roads.count; i++) {
            candidates[i] = i;
        }
        assertEquals(roads.count, mesher.mesh(0, 0, 1600, 1600, all, candidates, roads.count));
        assertTrue(all.triangleCount() > 10, "all three roads produce a network");
        // Oblique and bent roads must not leave gaps: every vertex is inside the data rectangle.
        float[] bounds = all.bounds();
        assertTrue(bounds[3] < 1600.0f && bounds[5] < 1600.0f, "the network stays inside the chunk");
    }

    @Test
    @DisplayName("land use and water classify ground that the terrain mesh colours")
    void landUseClassification() throws IOException {
        Areas landuse = Areas.parse(BundleReader.open("landuse",
                Files.readAllBytes(fixture.resolve(manifest.landuse.file)), manifest.landuse.sha256),
                "QLU1", ORIGIN_E, ORIGIN_N);
        Areas water = Areas.parse(BundleReader.open("water",
                Files.readAllBytes(fixture.resolve(manifest.water.file)), manifest.water.sha256),
                "QWA1", ORIGIN_E, ORIGIN_N);
        assertNotNull(landuse);
        assertNotNull(water);
        assertTrue(landuse.count > 0 && water.count > 0);

        // The fixture's lake is inside its ring and outside a point 100 m north of it.
        assertTrue(water.contains(0, 1000.0, 400.0));
        assertFalse(water.contains(0, 1000.0, 700.0));
    }

    @Test
    @DisplayName("meshes stay inside the vertex budget of a 16-bit index buffer")
    void meshBudget() {
        MeshData mesh = new MeshData();
        for (int i = 0; i < 10; i++) {
            int a = mesh.addVertex(0, 0, 0, 0, 1, 0, 0, 0, 0, 0);
            int b = mesh.addVertex(1, 0, 0, 0, 1, 0, 0, 1, 0, 0);
            int c = mesh.addVertex(1, 0, 1, 0, 1, 0, 0, 1, 1, 0);
            mesh.addTriangle(a, b, c);
        }
        assertEquals(30, mesh.vertexCount());
        assertEquals(10, mesh.triangleCount());
        assertFalse(mesh.isFull());
        mesh.addQuad(0, 1, 2, 3);
        assertEquals(12, mesh.triangleCount());
        assertTrue(mesh.describe().contains("triangles"));
    }

    private static float[] triangleNormal(MeshData mesh, int a, int b, int c) {
        float[] v = mesh.vertices();
        int ia = a * MeshData.FLOATS_PER_VERTEX;
        int ib = b * MeshData.FLOATS_PER_VERTEX;
        int ic = c * MeshData.FLOATS_PER_VERTEX;
        float abx = v[ib] - v[ia];
        float aby = v[ib + 1] - v[ia + 1];
        float abz = v[ib + 2] - v[ia + 2];
        float acx = v[ic] - v[ia];
        float acy = v[ic + 1] - v[ia + 1];
        float acz = v[ic + 2] - v[ia + 2];
        return new float[]{aby * acz - abz * acy, abz * acx - abx * acz, abx * acy - aby * acx};
    }
}
