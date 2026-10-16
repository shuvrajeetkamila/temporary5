package ai.arena.qatar.render;

import ai.arena.qatar.world.GameManifest;
import ai.arena.qatar.world.WorldData;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The far horizon, checked cell by cell against the committed synthetic world.
 *
 * <p>This is the geometry that decides what a player sees when they look up from the ground: before it
 * existed the world ended in a wall of fog about five kilometres out, and past that wall there was
 * nothing at all -- no bay, no dunes, no sea, no country. The ring is built from the national heightfield
 * at 300 m a cell, so it can be asserted exactly, in a JVM, with no GL: whether it covers every cell of
 * the ring, whether its triangles face up (a horizon wound the wrong way is culled into invisibility, and
 * looks exactly like the bug it was built to fix), whether its coordinates stay small enough to keep float
 * precision at eleven kilometres, and whether it can ever overflow the one mesh it lives in.
 */
final class HorizonMeshTest {

    private static Path fixture;
    private static GameManifest manifest;
    private static WorldData world;

    @BeforeAll
    static void loadFixture() throws IOException {
        Path candidate = Paths.get("src/test/resources/synthetic_world");
        if (!Files.isDirectory(candidate)) {
            candidate = Paths.get("core/src/test/resources/synthetic_world");
        }
        assertTrue(Files.isDirectory(candidate), "the fixture is missing: " + candidate);
        fixture = candidate;
        manifest = GameManifest.parse(
                new String(Files.readAllBytes(fixture.resolve("manifest.json")), StandardCharsets.UTF_8));
        Map<String, byte[]> bundles = new LinkedHashMap<>();
        for (String name : new String[]{"terrain_l0.bin.gz", "terrain_l1.bin.gz", "material_l1.bin.gz",
                "roads.bin.gz", "buildings.bin.gz", "landuse.bin.gz", "water.bin.gz", "barriers.bin.gz",
                "pois.bin.gz", "strings.bin.gz"}) {
            Path file = fixture.resolve(name);
            if (Files.isRegularFile(file)) {
                bundles.put(name, Files.readAllBytes(file));
            }
        }
        world = WorldData.fromSource(manifest, name -> {
            byte[] bytes = bundles.get(name);
            assertNotNull(bytes, "the test asked for a bundle that is not in the fixture: " + name);
            return bytes;
        });
    }

    /** The fixture is 1600 m square with 100 m cells; its middle is the natural centre to build around. */
    private static final double CENTRE_E = 800.0;
    private static final double CENTRE_N = 800.0;

    @Test
    @DisplayName("the ring covers every cell from the inner radius to the far plane, with no holes")
    void ringCoversTheWholeCellGrid() {
        HorizonMesh.Horizon horizon = HorizonMesh.build(world, CENTRE_E, CENTRE_N);

        assertEquals(HorizonMesh.NEAR_M, horizon.nearM, 1e-9);
        assertEquals(HorizonMesh.FAR_M, horizon.farM, 1e-9);
        // One cell of the ring, one quad: a cell that no layer draws is a hole in the horizon, and the
        // count is the only way to see one -- the mesh itself would look plausible from any angle.
        assertEquals(HorizonMesh.RINGS * HorizonMesh.DIVISIONS, horizon.landQuads + horizon.waterQuads,
                "every cell of the ring is drawn exactly once");
        assertEquals((horizon.landQuads + horizon.waterQuads) * 2, horizon.triangles(),
                "two triangles per cell");
        assertFalse(horizon.truncated, "the default ring fits inside one mesh with room to spare");

        // The meshes are bounded by the vertex budget that a 16-bit index buffer imposes, and with room to
        // spare: about twenty-five thousand vertices for the whole ring.
        assertTrue(horizon.land.vertexCount() <= MeshData.MAX_VERTICES,
                "the land mesh fits: " + horizon.land.vertexCount());
        assertTrue(horizon.water.vertexCount() <= MeshData.MAX_VERTICES,
                "the water mesh fits: " + horizon.water.vertexCount());

        // The outer edge is at the horizon distance and the vertices are relative to the build centre, which
        // is what keeps a float's precision at eleven kilometres what it is at a hundred metres. The fixture
        // is a kilometre and a half square, so a ring this size samples nothing but the clamped border of
        // its grid -- which is water, the eastern third of the fixture -- and it is the sea mesh that
        // carries the outer edge here. Both are checked for the same property.
        for (MeshData mesh : new MeshData[]{horizon.land, horizon.water}) {
            if (mesh.isEmpty()) {
                continue;
            }
            float[] bounds = mesh.bounds();
            double reach = Math.max(Math.abs(bounds[0]), Math.abs(bounds[3]));
            assertTrue(reach <= HorizonMesh.FAR_M + 1.0,
                    "no vertex is drawn further out than the horizon: " + reach);
        }
        float[] bounds = horizon.water.bounds();
        assertEquals(HorizonMesh.FAR_M, Math.max(Math.abs(bounds[0]), Math.abs(bounds[3])), 1.0,
                "the far edge of the ring is the horizon distance in metres: "
                        + java.util.Arrays.toString(bounds));
    }

    @Test
    @DisplayName("a small ring over the fixture has both land and sea, and they meet where the mask says")
    void smallRingSplitsLandAndSea() {
        // A ring the size of the fixture itself, so that the country under it -- 11 land columns and 5 of
        // water -- is what gets sampled rather than the clamped border of the grid.
        HorizonMesh.Horizon horizon = HorizonMesh.build(world, CENTRE_E, CENTRE_N, 100.0, 700.0, 16, 64);

        assertFalse(horizon.land.isEmpty(), "the land over the fixture is meshed");
        assertFalse(horizon.water.isEmpty(), "the sea over the fixture is meshed");
        assertEquals(16 * 64, horizon.landQuads + horizon.waterQuads, "every cell is drawn once");
        assertTrue(horizon.landQuads > 0 && horizon.waterQuads > 0,
                "the ring has both a shore and open sea under it");
        // The fixture's water is its five eastern columns, so the land layer has to sit west of the sea
        // layer on average. A sample-based classification cannot make the two agree exactly at the
        // shoreline -- the ring's cells are trapezoids on a 300 m grid and the shore runs at 100 m -- but a
        // whole ring on the wrong side of it would show up here immediately.
        assertTrue(meanEast(horizon.land) < meanEast(horizon.water),
                "the land is west of the sea: " + meanEast(horizon.land) + " vs "
                        + meanEast(horizon.water));
    }

    /** The average east coordinate of a mesh's vertices, relative to the build centre. */
    private static double meanEast(MeshData mesh) {
        float[] vertices = mesh.vertices();
        double sum = 0.0;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            sum += vertices[i * MeshData.FLOATS_PER_VERTEX];
        }
        return mesh.vertexCount() == 0 ? 0.0 : sum / mesh.vertexCount();
    }

    @Test
    @DisplayName("every triangle of the horizon faces up, so back-face culling cannot eat the distance")
    void trianglesFaceUp() {
        HorizonMesh.Horizon horizon = HorizonMesh.build(world, CENTRE_E, CENTRE_N, 100.0, 700.0, 8, 32);
        checkWinding(horizon.land, "land");
        checkWinding(horizon.water, "water");
        // The sea is a flat sheet and is drawn with culling off, but its stored normal is still the up one:
        // the shader reads it for the wave wobble, and a sea lit from below would be black.
        float[] vertices = horizon.water.vertices();
        for (int i = 0; i < horizon.water.vertexCount(); i++) {
            int at = i * MeshData.FLOATS_PER_VERTEX;
            assertEquals(1.0f, vertices[at + 4], 1e-4f, "a water vertex is up-facing");
        }
        float[] land = horizon.land.vertices();
        for (int i = 0; i < horizon.land.vertexCount(); i++) {
            assertTrue(land[i * MeshData.FLOATS_PER_VERTEX + 4] > 0f,
                    "a land vertex is up-facing: a horizon lit from underneath is a horizon of black rock");
        }
    }

    /** The geometric normal of every triangle in a mesh, from the vertices it actually indexes. */
    private static void checkWinding(MeshData mesh, String label) {
        short[] indices = mesh.indices();
        float[] vertices = mesh.vertices();
        for (int t = 0; t < mesh.triangleCount(); t++) {
            int a = indices[t * 3];
            int b = indices[t * 3 + 1];
            int c = indices[t * 3 + 2];
            assertTrue(a < mesh.vertexCount() && b < mesh.vertexCount() && c < mesh.vertexCount(),
                    label + " triangle " + t + " indexes a vertex that does not exist");
            double ux = vertices[b * MeshData.FLOATS_PER_VERTEX] - vertices[a * MeshData.FLOATS_PER_VERTEX];
            double uy = vertices[b * MeshData.FLOATS_PER_VERTEX + 1]
                    - vertices[a * MeshData.FLOATS_PER_VERTEX + 1];
            double uz = vertices[b * MeshData.FLOATS_PER_VERTEX + 2]
                    - vertices[a * MeshData.FLOATS_PER_VERTEX + 2];
            double vx = vertices[c * MeshData.FLOATS_PER_VERTEX] - vertices[a * MeshData.FLOATS_PER_VERTEX];
            double vy = vertices[c * MeshData.FLOATS_PER_VERTEX + 1]
                    - vertices[a * MeshData.FLOATS_PER_VERTEX + 1];
            double vz = vertices[c * MeshData.FLOATS_PER_VERTEX + 2]
                    - vertices[a * MeshData.FLOATS_PER_VERTEX + 2];
            double ny = uz * vx - ux * vz;
            assertTrue(ny > 0.0, label + " triangle " + t + " faces up (got ny=" + ny + ")");
        }
    }

    @Test
    @DisplayName("the horizon carries the country's own heights, not a plane at sea level")
    void heightsComeFromTheGrid() {
        HorizonMesh.Horizon horizon = HorizonMesh.build(world, CENTRE_E, CENTRE_N, 100.0, 700.0, 16, 64);
        // The fixture's land runs from 2 m to 20 m; if the ring were flat, or sampling something else
        // entirely, the range would collapse and the far country would be a sheet of paper.
        float[] bounds = horizon.land.bounds();
        assertTrue(bounds[1] >= -0.01f, "no land vertex is below the terrain lift: " + bounds[1]);
        assertTrue(bounds[4] > 5.0f, "the ring carries the country's relief, not a flat sheet: " + bounds[4]);
        assertTrue(bounds[4] <= 25.0f, "and no height the compiled grid never had: " + bounds[4]);
        // The sea sits at sea level and never below it, and where it reaches a corner standing on land its
        // edge climbs to the shore -- that climb is the join between the two layers, not slack in the rule.
        float[] water = horizon.water.bounds();
        assertTrue(water[1] >= 0.0f && water[1] <= 0.06f,
                "the sea is at sea level, never in the air and never under the seabed: " + water[1]);
        assertTrue(water[4] >= water[1], "the sea's bounds are ordered: "
                + java.util.Arrays.toString(water));
        assertTrue(water[4] <= 25.0f, "and its shore corners climb no higher than the land they meet: "
                + water[4]);
    }

    @Test
    @DisplayName("a ring too big for one mesh stops cleanly instead of overflowing it")
    void oversizedRingStopsAtTheVertexBudget() {
        // Sixteen hundred cells a ring by two hundred rings is a quarter of a million quads: far past what
        // a 16-bit index buffer can name, which is what the build has to survive. The third install of this
        // game died by running one primitive off the end of a full mesh, so the rule is that the mesher
        // asks before it adds, and this is the test that holds it.
        HorizonMesh.Horizon horizon = HorizonMesh.build(world, CENTRE_E, CENTRE_N, 100.0, 11400.0,
                200, 256);
        assertTrue(horizon.truncated, "the build reports that it stopped early");
        assertTrue(horizon.land.vertexCount() <= MeshData.MAX_VERTICES,
                "the land mesh stayed inside its vertex budget: " + horizon.land.vertexCount());
        assertTrue(horizon.water.vertexCount() <= MeshData.MAX_VERTICES,
                "the water mesh stayed inside its vertex budget: " + horizon.water.vertexCount());
        assertTrue(horizon.triangles() > 0, "and it still built everything that did fit");
        // Every index still has to name a vertex that exists, or the draw call is a crash on a real GPU.
        checkWinding(horizon.land, "truncated land");
        checkWinding(horizon.water, "truncated water");
    }

    @Test
    @DisplayName("the horizon names itself in one line for the diagnostics panel")
    void describeIsReadable() {
        HorizonMesh.Horizon horizon = HorizonMesh.build(world, CENTRE_E, CENTRE_N);
        String text = horizon.describe();
        assertTrue(text.contains("horizon 11 km"), text);
        assertTrue(text.contains("triangles"), text);
        assertFalse(text.contains("TRUNCATED"), "the default ring is complete: " + text);
    }
}
