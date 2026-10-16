package ai.arena.qatar.render;

import ai.arena.qatar.world.Buildings;
import ai.arena.qatar.world.FeatureIndex;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The city beyond the detail radius, checked block by block against the committed synthetic world.
 *
 * <p>This is the geometry that decides what a player sees when they look up and out: before it existed,
 * the world had real buildings for two and a half kilometres and bare ground past that, so a helicopter
 * over West Bay saw Doha end and the desert begin where the city was in fact still going. It is built
 * from the same bundle the detailed mesher uses, so it can be asserted exactly, in a JVM, with no GL:
 * which buildings survive the thinning, whether a block can land on top of the real geometry or past the
 * far edge, and whether every face of every block is wound so that it is visible from outside -- the
 * fault that made the eighth install's buildings look like walls, which is what a box with invisible
 * faces looks like from the street.
 */
final class SkylineMeshTest {

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

    // ---------------------------------------------------------------- the thinning rules

    /**
     * A set of square buildings, in the frame the compiled bundles use: rows of
     * {@code {centre east, centre north, half side, height}}.
     *
     * <p>Written by hand rather than loaded so that the thinning rules can be driven with exactly the
     * buildings they are about -- two in one cell, one just inside the far edge, one just outside it --
     * which is what the synthetic fixture, with five buildings in it, cannot do.
     */
    private static Buildings squares(double[][] spec) {
        int n = spec.length;
        int[] ringStart = new int[n];
        int[] ringLength = new int[n];
        float[] xz = new float[n * 10];
        float[] heightM = new float[n];
        float[] minHeightM = new float[n];
        byte[] type = new byte[n];
        byte[] roof = new byte[n];
        int[] nameIndex = new int[n];
        byte[] flags = new byte[n];
        float[] bounds = new float[n * 4];
        for (int i = 0; i < n; i++) {
            double east = spec[i][0];
            double north = spec[i][1];
            double half = spec[i][2];
            heightM[i] = (float) spec[i][3];
            ringStart[i] = i * 5;
            ringLength[i] = 5;
            // A closed OSM ring: the first vertex repeated at the end, which is what the real bundle has.
            double[] ring = {east - half, north - half, east + half, north - half, east + half, north + half,
                    east - half, north + half, east - half, north - half};
            for (int j = 0; j < ring.length; j++) {
                xz[i * 10 + j] = (float) ring[j];
            }
            bounds[i * 4] = (float) (east - half);
            bounds[i * 4 + 1] = (float) (north - half);
            bounds[i * 4 + 2] = (float) (east + half);
            bounds[i * 4 + 3] = (float) (north + half);
        }
        return new Buildings(n, ringStart, ringLength, xz, heightM, minHeightM, type, roof, nameIndex, flags,
                bounds);
    }

    private static FeatureIndex indexOf(Buildings buildings) {
        return new FeatureIndex(buildings.bounds, buildings.ringCount, 0.0, 0.0, 80000.0, 80000.0, 500.0);
    }

    @Test
    @DisplayName("one block per cell, and it is the tallest building in it")
    void theTallestInACellWins() {
        // Two buildings forty metres apart, so both fall in the same ninety-metre cell, and a third a
        // hundred and eighty metres north, in a cell of its own.
        Buildings buildings = squares(new double[][]{
                {3000, 0, 12, 9},
                {3040, 0, 12, 41},
                {3000, 180, 12, 15},
        });
        int[] chosen = SkylineMesh.select(buildings, indexOf(buildings), 0, 0, 1000, 8000,
                SkylineMesh.CELL_M, 100);
        assertEquals(2, chosen.length, "two cells hold a building: " + Arrays.toString(chosen));
        assertEquals(1, chosen[0], "the tallest building of the two in one cell is the block: "
                + Arrays.toString(chosen));
        assertEquals(2, chosen[1], "and the other cell keeps its own, in height order");
    }

    @Test
    @DisplayName("nothing is drawn inside the detail radius or past the far edge")
    void theBandIsRespected() {
        Buildings buildings = squares(new double[][]{
                {500, 0, 12, 20},            // inside the near radius: the chunks own this
                {3000, 0, 12, 20},           // in the band
                {7900, 0, 12, 20},           // just inside the far edge
                {8600, 0, 12, 20},           // past it: the horizon's country
                {3000, 0, 12, 1},            // in the band but only a metre high: not a silhouette
        });
        int[] chosen = SkylineMesh.select(buildings, indexOf(buildings), 0, 0, 2200, 8000,
                SkylineMesh.CELL_M, 100);
        assertEquals(2, chosen.length, "only the two buildings in the band qualify: "
                + Arrays.toString(chosen));
        assertTrue(chosen[0] == 2 || chosen[1] == 2, "the far edge of the band is included");
    }

    @Test
    @DisplayName("the same country always yields the same blocks, in the same order")
    void selectionIsDeterministic() {
        // Two buildings of exactly the same height in cells that are searched in an order a HashMap does
        // not promise: the tie has to be broken by something of the data's own, not by the iteration.
        Buildings buildings = squares(new double[][]{
                {2400, 0, 10, 30},
                {4800, 0, 10, 30},
                {7000, 0, 10, 30},
        });
        FeatureIndex index = indexOf(buildings);
        int[] first = SkylineMesh.select(buildings, index, 0, 0, 2200, 8000, SkylineMesh.CELL_M, 100);
        for (int attempt = 0; attempt < 8; attempt++) {
            int[] again = SkylineMesh.select(buildings, index, 0, 0, 2200, 8000, SkylineMesh.CELL_M, 100);
            assertArrayEquals(first, again, "the selection changed between runs");
        }
        assertEquals(3, first.length, Arrays.toString(first));
    }

    @Test
    @DisplayName("a cap keeps the tallest blocks, not the first ones found")
    void theCapKeepsTheTallest() {
        Buildings buildings = squares(new double[][]{
                {2400, 0, 10, 12},
                {2800, 0, 10, 90},
                {3200, 0, 10, 45},
                {3600, 0, 10, 200},
        });
        int[] chosen = SkylineMesh.select(buildings, indexOf(buildings), 0, 0, 2200, 8000,
                SkylineMesh.CELL_M, 2);
        assertEquals(2, chosen.length, "the cap is a cap");
        assertEquals(3, chosen[0], "the tallest of the four is drawn first");
        assertEquals(1, chosen[1], "and the second tallest after it");
    }

    // ---------------------------------------------------------------- the mesh that is drawn

    @Test
    @DisplayName("every block of the fixture city is drawn with its walls facing out and its lid up")
    void everyFaceLooksOutward() {
        // A band that reaches the fixture's own buildings -- a 120 m tower 140 m from the centre, and two
        // twelve-metre blocks 28 m apart -- so the mesh is built from real compiled rings with real bounds
        // and the thinning has to make a choice on the way through. The mosque at 740 m and the villa at
        // 953 m are both past the far edge of this band.
        SkylineMesh.Skyline skyline = SkylineMesh.build(world, CENTRE_E, CENTRE_N, 100.0, 700.0, 90.0, 100);
        assertEquals(2, skyline.blockCount, "two blocks survive the thinning of the fixture's five "
                + "buildings: the tower, and one of the two apartments that share a cell. "
                + skyline.describe());
        assertFalse(skyline.truncated, "a city of two blocks fits in one mesh");
        assertEquals(2, skyline.considered, "and nothing else was within reach: " + skyline.describe());
        assertEveryBlockIsSolid(skyline.blocks, skyline.blockCount);
    }

    @Test
    @DisplayName("a block is never drawn nearer than the detail radius the chunks own")
    void nothingIsDrawnUnderTheRealCity() {
        SkylineMesh.Skyline skyline = SkylineMesh.build(world, CENTRE_E, CENTRE_N, 100.0, 700.0, 90.0, 100);
        float[] vertices = skyline.blocks.vertices();
        for (int i = 0; i < skyline.blocks.vertexCount(); i++) {
            int at = i * MeshData.FLOATS_PER_VERTEX;
            double dx = vertices[at] - CENTRE_E;
            double dz = vertices[at + 2] - CENTRE_N;
            double distance = Math.sqrt(dx * dx + dz * dz);
            // Slack for the block's own size and the grow: the ring is drawn from bounds centres, so a
            // sixty-metre footprint whose centre sits just outside the radius still reaches inside it.
            assertTrue(distance >= 60.0, "a block was drawn on top of the real geometry: " + distance);
            assertTrue(distance <= 700.0 + 60.0, "a block was drawn past the far edge: " + distance);
        }
    }

    @Test
    @DisplayName("the blocks carry the city's own heights and stand on the ground under them")
    void blocksCarryRealHeights() {
        SkylineMesh.Skyline skyline = SkylineMesh.build(world, CENTRE_E, CENTRE_N, 100.0, 700.0, 90.0, 100);
        float[] bounds = skyline.blocks.bounds();
        // The fixture's tallest building in this band is the 120 m tower at (697, 897). Its block reaches
        // that height above the ground the terrain grid gives under it -- about 14.7 m there, on the
        // ridge the fixture's terrain rises along -- so the top is a little under 135 m and the base is
        // the same ground less the sink. A block built from the wrong number would be metres out, and a
        // block built on the wrong ground would be tens.
        assertTrue(bounds[4] >= 128.0f && bounds[4] <= 140.0f,
                "the tallest block carries the tower's own height: " + bounds[4]);
        assertTrue(bounds[1] >= 8.0f && bounds[1] <= 12.0f,
                "and its base is the ground under it less the sink: " + bounds[1]);
    }

    @Test
    @DisplayName("a mesh too small to hold the city stops cleanly instead of overflowing it")
    void oversizedSkylineStopsAtTheVertexBudget() {
        // Four thousand buildings in a grid, every one its own cell, and no cap: far past the 3 250 blocks
        // a 65 000-vertex mesh can hold. The third install of this game died by running one primitive off
        // the end of a full mesh, so the rule here is the same as the horizon's -- the mesher asks before
        // it adds, and what it did fit is still a mesh a GPU can draw.
        int side = 64;
        double[][] spec = new double[side * side][];
        int at = 0;
        for (int row = 0; row < side; row++) {
            for (int column = 0; column < side; column++) {
                spec[at++] = new double[]{400 + column * 120.0, 400 + row * 120.0, 8, 20 + (at % 7)};
            }
        }
        Buildings buildings = squares(spec);
        FeatureIndex index = indexOf(buildings);
        SkylineMesh.Skyline skyline = SkylineMesh.buildFrom(world, buildings, index, 4000, 4000, 100.0,
                8000.0, 90.0, 4000);
        assertTrue(skyline.truncated, "the build reports that it stopped early: " + skyline.describe());
        assertTrue(skyline.blocks.vertexCount() <= MeshData.MAX_VERTICES,
                "the mesh stayed inside its vertex budget: " + skyline.blocks.vertexCount());
        assertTrue(skyline.triangles() > 0, "and it still built everything that did fit");
        assertEveryBlockIsSolid(skyline.blocks, skyline.blockCount);
    }

    @Test
    @DisplayName("a country with no buildings in reach is drawn as nothing, and is not an error")
    void anEmptyBandIsNotAFailure() {
        SkylineMesh.Skyline skyline = SkylineMesh.build(world, CENTRE_E, CENTRE_N, 100.0, 700.0, 90.0, 0);
        assertEquals(0, skyline.blockCount, "a cap of zero draws nothing");
        assertTrue(skyline.triangles() == 0, "and nothing is what it draws");
        assertFalse(skyline.truncated, "an empty skyline is not a truncated one");
        SkylineMesh.Skyline missing = SkylineMesh.buildFrom(world, null, null, 0, 0, 100.0, 700.0, 90.0, 10);
        assertEquals(0, missing.blockCount, "a bundle without buildings draws no skyline");
    }

    @Test
    @DisplayName("the skyline names itself in one line for the diagnostics panel")
    void describeIsReadable() {
        SkylineMesh.Skyline skyline = SkylineMesh.build(world, CENTRE_E, CENTRE_N, 100.0, 700.0, 90.0, 100);
        String text = skyline.describe();
        assertTrue(text.contains("skyline 0 km"), text);
        assertTrue(text.contains("2 blocks of 2"), text);
        assertTrue(text.contains("triangles"), text);
        assertFalse(text.contains("TRUNCATED"), "the default ring is complete: " + text);
        assertTrue(SkylineMesh.build(world, CENTRE_E, CENTRE_N).describe().contains("skyline 8 km"),
                "the full band is eight kilometres out");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Every block of a mesh, checked as a block: ten triangles in the order the mesher writes them -- four
     * walls of two triangles each, then a lid of two -- grouped by the vertices they use.
     *
     * <p>Three things are asserted, and they are the three that are invisible in a screenshot taken from
     * the wrong side: a triangle either faces the sky or faces away from its own block's centre; the
     * geometric winding agrees with the normal the shader was given; and no triangle indexes a vertex that
     * does not exist (the index buffer is unsigned 16-bit, so it has to be masked before it is read).
     */
    private static void assertEveryBlockIsSolid(MeshData mesh, int blocks) {
        short[] indices = mesh.indices();
        float[] vertices = mesh.vertices();
        assertEquals(blocks * SkylineMesh.TRIANGLES_PER_BLOCK, mesh.triangleCount(),
                "every block is ten triangles");
        for (int block = 0; block < blocks; block++) {
            int first = block * SkylineMesh.TRIANGLES_PER_BLOCK * 3;
            // The block's own centre, from the vertices of this block alone.
            double minEast = Double.MAX_VALUE;
            double maxEast = -Double.MAX_VALUE;
            double minNorth = Double.MAX_VALUE;
            double maxNorth = -Double.MAX_VALUE;
            for (int i = 0; i < SkylineMesh.TRIANGLES_PER_BLOCK * 3; i++) {
                int vertex = indices[first + i] & 0xFFFF;
                assertTrue(vertex < mesh.vertexCount(),
                        "block " + block + " indexes a vertex that does not exist");
                minEast = Math.min(minEast, vertices[vertex * MeshData.FLOATS_PER_VERTEX]);
                maxEast = Math.max(maxEast, vertices[vertex * MeshData.FLOATS_PER_VERTEX]);
                minNorth = Math.min(minNorth, vertices[vertex * MeshData.FLOATS_PER_VERTEX + 2]);
                maxNorth = Math.max(maxNorth, vertices[vertex * MeshData.FLOATS_PER_VERTEX + 2]);
            }
            double centreEast = (minEast + maxEast) * 0.5;
            double centreNorth = (minNorth + maxNorth) * 0.5;
            for (int t = 0; t < SkylineMesh.TRIANGLES_PER_BLOCK; t++) {
                int a = indices[first + t * 3] & 0xFFFF;
                int b = indices[first + t * 3 + 1] & 0xFFFF;
                int c = indices[first + t * 3 + 2] & 0xFFFF;
                double[] normal = geometricNormal(vertices, a, b, c);
                double[] stored = {vertices[a * MeshData.FLOATS_PER_VERTEX + 3],
                        vertices[a * MeshData.FLOATS_PER_VERTEX + 4],
                        vertices[a * MeshData.FLOATS_PER_VERTEX + 5]};
                double agreement = normal[0] * stored[0] + normal[1] * stored[1] + normal[2] * stored[2];
                assertTrue(agreement > 0.9, "block " + block + " triangle " + t
                        + " is wound against the normal it was given: " + agreement);
                if (Math.abs(normal[1]) > 0.5) {
                    assertTrue(normal[1] > 0.0, "block " + block + " triangle " + t
                            + " is a lid that faces the ground");
                } else {
                    double outwardEast = vertices[a * MeshData.FLOATS_PER_VERTEX] - centreEast;
                    double outwardNorth = vertices[a * MeshData.FLOATS_PER_VERTEX + 2] - centreNorth;
                    assertTrue(normal[0] * outwardEast + normal[2] * outwardNorth > 0.0,
                            "block " + block + " triangle " + t + " is a wall that faces inwards");
                }
            }
        }
    }

    /** The un-normalised normal of a triangle, from the vertices it names. */
    private static double[] geometricNormal(float[] vertices, int a, int b, int c) {
        double ux = vertices[b * MeshData.FLOATS_PER_VERTEX] - vertices[a * MeshData.FLOATS_PER_VERTEX];
        double uy = vertices[b * MeshData.FLOATS_PER_VERTEX + 1] - vertices[a * MeshData.FLOATS_PER_VERTEX + 1];
        double uz = vertices[b * MeshData.FLOATS_PER_VERTEX + 2] - vertices[a * MeshData.FLOATS_PER_VERTEX + 2];
        double vx = vertices[c * MeshData.FLOATS_PER_VERTEX] - vertices[a * MeshData.FLOATS_PER_VERTEX];
        double vy = vertices[c * MeshData.FLOATS_PER_VERTEX + 1] - vertices[a * MeshData.FLOATS_PER_VERTEX + 1];
        double vz = vertices[c * MeshData.FLOATS_PER_VERTEX + 2] - vertices[a * MeshData.FLOATS_PER_VERTEX + 2];
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return length < 1e-9 ? new double[]{0, 0, 0} : new double[]{nx / length, ny / length, nz / length};
    }

    private static void assertArrayEquals(int[] expected, int[] actual, String message) {
        assertEquals(Arrays.toString(expected), Arrays.toString(actual), message);
    }
}
