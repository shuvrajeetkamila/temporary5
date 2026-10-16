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
 * The roads and the vegetation of the far band, checked primitive by primitive against the committed
 * synthetic world.
 *
 * <p>This is the layer a player reads the country by when they are flying: the ribbon of the Salwa road
 * running north out of Doha, the palms along it, and past four kilometres the green patch on the sand
 * that is the plantation they are heading for. None of it can be checked from a screenshot, so all of it
 * is asserted here: the band, the winding of every face -- the eighth install's fault, which made a box
 * with invisible walls -- the trunk and crown colours the shader is told apart by, and the budget rules
 * that keep a mesh inside a sixteen-bit index buffer.
 *
 * <p>The fixture is 1,600 m square, so the real band (2.2 to 8 km) does not fit inside it: every test
 * here uses {@code FarLayerMesh.build}'s open radii, which is the same code with a different ruler. What
 * is being checked is the arithmetic, and the arithmetic does not know how big it is.
 */
final class FarLayerMeshTest {

    private static WorldData world;

    @BeforeAll
    static void loadFixture() throws IOException {
        Path candidate = Paths.get("src/test/resources/synthetic_world");
        if (!Files.isDirectory(candidate)) {
            candidate = Paths.get("core/src/test/resources/synthetic_world");
        }
        assertTrue(Files.isDirectory(candidate), "the fixture is missing: " + candidate);
        GameManifest manifest = GameManifest.parse(new String(
                Files.readAllBytes(candidate.resolve("manifest.json")), StandardCharsets.UTF_8));
        Map<String, byte[]> bundles = new LinkedHashMap<>();
        for (String name : new String[]{"terrain_l0.bin.gz", "terrain_l1.bin.gz", "material_l1.bin.gz",
                "roads.bin.gz", "buildings.bin.gz", "landuse.bin.gz", "water.bin.gz", "barriers.bin.gz",
                "pois.bin.gz", "strings.bin.gz"}) {
            Path file = candidate.resolve(name);
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

    /**
     * The band these tests are built with, and why it is where it is.
     *
     * <p>The centre is off the middle of the fixture so that the one vegetated ring -- a 300 by 200 m
     * rectangle at (50..350, 900..1100) -- lands across all three treatments: its far cells are within the
     * palm band and become palms, its near ones fall past it and become canopy, and the two roads cross
     * both. At (800, 800) the ring's nearest corner is 461 m away and the whole thing is canopy.
     */
    private static final double CENTRE_E = 700.0;
    private static final double CENTRE_N = 700.0;
    private static final double NEAR_M = 200.0;
    private static final double FAR_M = 800.0;
    private static final double PALM_BAND_M = 560.0;
    private static final double PALM_CELL_M = 60.0;
    private static final double CANOPY_CELL_M = 110.0;

    private static FarLayerMesh.FarLayer build() {
        return FarLayerMesh.build(world, CENTRE_E, CENTRE_N, NEAR_M, FAR_M, PALM_BAND_M, PALM_CELL_M,
                CANOPY_CELL_M, FarLayerMesh.MAX_VERTICES);
    }

    // ---------------------------------------------------------------- what the layer is made of

    /**
     * The whole layer, in the fixture, to the segment.
     *
     * <p>Thirty-five road segments, five palms and four canopy patches are not round numbers: they are
     * what the compiled fixture produces, and each has a reason that can be checked by hand. Two ways are
     * inside the band -- the class 3 primary running east along northing 850 for 1,495 m, and the class 7
     * residential running north at easting 550 -- and the class 8 service way between them is not drawn at
     * all, which is the class cut doing its job. Fifteen palm cells of the vegetated ring fall in that
     * band and five of them survive the jitter check and the budget; four canopy cells fall past it.
     */
    @Test
    @DisplayName("the fixture draws its two real roads, five palms and four canopy patches, and nothing else")
    void theFixtureIsDrawnExactly() {
        FarLayerMesh.FarLayer layer = build();
        assertEquals(35, layer.roadSegments, "thirty-five segments of sixty metres along the two ways "
                + "inside the band: " + layer.describe());
        assertEquals(5, layer.palms, "five of the fixture's palm cells land in the palm band: "
                + layer.describe());
        assertEquals(4, layer.canopies, "four canopy cells past it: " + layer.describe());
        assertEquals(2, layer.consideredWays, "two ways are in the band; the class 8 service way is not");
        assertEquals(1, layer.consideredAreas, "one vegetation ring; the two land-use rings are not green");
        assertFalse(layer.truncated(), "nothing here comes close to a budget: " + layer.describe());

        assertEquals(35 * FarLayerMesh.ROAD_VERTICES_PER_SEGMENT + 5 * FarLayerMesh.PALM_VERTICES
                + 4 * FarLayerMesh.CANOPY_VERTICES, layer.vertices(),
                "the mesh is exactly the primitives it reports: " + layer.describe());
        assertEquals(35 * FarLayerMesh.ROAD_TRIANGLES_PER_SEGMENT + 5 * FarLayerMesh.PALM_TRIANGLES
                + 4 * FarLayerMesh.CANOPY_TRIANGLES, layer.triangles(), layer.describe());
        assertEquals(158, layer.triangles(), "and that is 158 triangles: " + layer.describe());
        assertLayerIsWellFormed(layer);
    }

    @Test
    @DisplayName("nothing is drawn inside the detail radius, past the far edge, or between the two bands")
    void theBandIsRespected() {
        FarLayerMesh.FarLayer layer = build();
        float[] vertices = layer.mesh.vertices();
        int stride = MeshData.FLOATS_PER_VERTEX;

        // Roads, then palms, then canopy: the order the three passes write in, which is what makes a mesh
        // as one flat array checkable primitive by primitive.
        for (int segment = 0; segment < layer.roadSegments; segment++) {
            int first = segment * FarLayerMesh.ROAD_VERTICES_PER_SEGMENT * stride;
            double centreEast = (vertices[first] + vertices[first + 2 * stride]) * 0.5;
            double centreNorth = (vertices[first + 2] + vertices[first + 2 * stride + 2]) * 0.5;
            double distance = Math.hypot(centreEast - CENTRE_E, centreNorth - CENTRE_N);
            // A road segment is drawn when its midpoint is in the band, so a sixty-metre step can hang
            // thirty metres over either edge -- plus the ribbon's own half-width and the lift.
            assertTrue(distance >= NEAR_M - FarLayerMesh.ROAD_STEP_M,
                    "a road was drawn under the real geometry: " + distance);
            assertTrue(distance <= FAR_M + FarLayerMesh.ROAD_STEP_M,
                    "a road was drawn past the far edge: " + distance);
        }

        int palmBase = layer.roadSegments * FarLayerMesh.ROAD_VERTICES_PER_SEGMENT * stride;
        for (int palm = 0; palm < layer.palms; palm++) {
            int first = palmBase + palm * FarLayerMesh.PALM_VERTICES * stride;
            double east = 0;
            double north = 0;
            for (int i = 0; i < FarLayerMesh.PALM_VERTICES; i++) {
                east += vertices[first + i * stride];
                north += vertices[first + i * stride + 2];
            }
            east /= FarLayerMesh.PALM_VERTICES;
            north /= FarLayerMesh.PALM_VERTICES;
            double distance = Math.hypot(east - CENTRE_E, north - CENTRE_N);
            // A palm is placed by its own jittered point, so unlike a road it has no slack at all: the
            // point itself is tested against the band.
            assertTrue(distance >= NEAR_M, "a palm stands under the real geometry: " + distance);
            assertTrue(distance <= PALM_BAND_M, "a palm was drawn past the palm band: " + distance);
        }

        int canopyBase = palmBase + layer.palms * FarLayerMesh.PALM_VERTICES * stride;
        for (int patch = 0; patch < layer.canopies; patch++) {
            int first = canopyBase + patch * FarLayerMesh.CANOPY_VERTICES * stride;
            double centreEast = (vertices[first] + vertices[first + 2 * stride]) * 0.5;
            double centreNorth = (vertices[first + 2] + vertices[first + 2 * stride + 2]) * 0.5;
            double distance = Math.hypot(centreEast - CENTRE_E, centreNorth - CENTRE_N);
            assertTrue(distance >= PALM_BAND_M,
                    "a canopy patch was drawn where palms belong: " + distance);
            assertTrue(distance <= FAR_M, "a canopy patch was drawn past the far edge: " + distance);
        }
    }

    @Test
    @DisplayName("the same country always yields the same layer, vertex for vertex")
    void theLayerIsDeterministic() {
        FarLayerMesh.FarLayer first = build();
        FarLayerMesh.FarLayer second = build();
        assertEquals(first.roadSegments, second.roadSegments);
        assertEquals(first.palms, second.palms);
        assertEquals(first.canopies, second.canopies);
        assertArrayEquals(first.mesh.vertices(), second.mesh.vertices());
        assertArrayEquals(first.mesh.indices(), second.mesh.indices());
    }

    // ---------------------------------------------------------------- the rules that keep it cheap

    @Test
    @DisplayName("a mesh too small for the country stops at the budget instead of overflowing it")
    void aSmallCeilingStopsTheBuildCleanly() {
        // Sixty-four vertices a pass: sixteen road segments, two palms, and all four canopy patches,
        // because the canopy lattice is the coarsest of the three and its cells are all equally near.
        FarLayerMesh.FarLayer layer = FarLayerMesh.build(world, CENTRE_E, CENTRE_N, NEAR_M, FAR_M,
                PALM_BAND_M, PALM_CELL_M, CANOPY_CELL_M, 64);
        assertEquals(16, layer.roadSegments, layer.describe());
        assertEquals(2, layer.palms, layer.describe());
        assertEquals(4, layer.canopies, layer.describe());
        assertTrue(layer.roadsTruncated, "a sixth way's worth of ribbon was left undrawn");
        assertTrue(layer.palmsTruncated, "three palm cells were left undrawn");
        assertFalse(layer.canopiesTruncated, "all four patches fit, so nothing was dropped");
        assertEquals(16 * FarLayerMesh.ROAD_VERTICES_PER_SEGMENT, layer.vertices()
                        - layer.palms * FarLayerMesh.PALM_VERTICES - layer.canopies * FarLayerMesh.CANOPY_VERTICES,
                "the road pass spent its sixty-four vertices and no more");
        assertTrue(layer.roadSegments * FarLayerMesh.ROAD_VERTICES_PER_SEGMENT <= 64, "roads fit");
        assertTrue(layer.palms * FarLayerMesh.PALM_VERTICES <= 64, "palms fit");
        assertTrue(layer.canopies * FarLayerMesh.CANOPY_VERTICES <= 64, "canopy patches fit");
        assertLayerIsWellFormed(layer);
    }

    @Test
    @DisplayName("a band with nothing in it is drawn as nothing, and is not an error")
    void anEmptyBandIsNotAFailure() {
        FarLayerMesh.FarLayer layer = FarLayerMesh.build(world, CENTRE_E, CENTRE_N, 5000.0, 8000.0, 6000.0,
                PALM_CELL_M, CANOPY_CELL_M, FarLayerMesh.MAX_VERTICES);
        assertEquals(0, layer.roadSegments, layer.describe());
        assertEquals(0, layer.palms, layer.describe());
        assertEquals(0, layer.canopies, layer.describe());
        assertEquals(0, layer.consideredWays, "nothing was even looked at: " + layer.describe());
        assertEquals(0, layer.consideredAreas, layer.describe());
        assertTrue(layer.mesh.isEmpty(), "an empty band is an empty mesh");
        assertFalse(layer.truncated(), "an empty band is not a truncated one");
    }

    @Test
    @DisplayName("the layer names itself in one line for the diagnostics panel")
    void describeIsReadable() {
        // The fixture's own band, because that is the build whose numbers are known; the radii are what
        // the panel reads, and they are the same field the real build fills from its own constants.
        String described = build().describe();
        assertTrue(described.startsWith("far layer 0-0 km, 35 road, 5 palm, 4 canopy, "),
                "the panel line names the band and what is in it: " + described);
        assertFalse(described.contains("TRUNCATED"), "nothing was dropped: " + described);
        assertTrue(described.length() < 60, "the panel has one line for this: " + described);

        FarLayerMesh.FarLayer truncated = FarLayerMesh.build(world, CENTRE_E, CENTRE_N, NEAR_M, FAR_M,
                PALM_BAND_M, PALM_CELL_M, CANOPY_CELL_M, 64);
        assertTrue(truncated.describe().endsWith(", TRUNCATED"),
                "a build that ran out of budget says so: " + truncated.describe());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Every primitive of a layer, checked as the primitive it is.
     *
     * <p>The mesher writes in a fixed order -- roads, then palms, then canopy patches -- and each kind has
     * a fixed size, so the mesh can be walked exactly as it was built. That is the point of the walk: a
     * screenshot from one angle cannot tell a crown cone wound inside out from one wound correctly, and a
     * crown that is invisible from above is a plantation that vanishes when the player flies over it.
     */
    private static void assertLayerIsWellFormed(FarLayerMesh.FarLayer layer) {
        MeshData mesh = layer.mesh;
        short[] indices = mesh.indices();
        float[] vertices = mesh.vertices();
        int stride = MeshData.FLOATS_PER_VERTEX;
        assertEquals(layer.roadSegments * FarLayerMesh.ROAD_TRIANGLES_PER_SEGMENT
                        + layer.palms * FarLayerMesh.PALM_TRIANGLES
                        + layer.canopies * FarLayerMesh.CANOPY_TRIANGLES, mesh.triangleCount(),
                "the mesh is exactly the primitives it reports");

        int triangle = 0;
        for (int segment = 0; segment < layer.roadSegments; segment++) {
            int first = segment * FarLayerMesh.ROAD_VERTICES_PER_SEGMENT * stride;
            for (int t = 0; t < FarLayerMesh.ROAD_TRIANGLES_PER_SEGMENT; t++, triangle++) {
                double[] normal = geometricNormal(vertices, indices, triangle);
                assertTrue(normal[1] > 0.9, "road triangle " + triangle + " does not face the sky: "
                        + normal[1]);
                assertAgreesWithStoredNormal(vertices, indices, triangle, normal, "road " + segment);
                for (int i = 0; i < 3; i++) {
                    int vertex = indices[triangle * 3 + i] & 0xFFFF;
                    assertEquals(RoadMesher.SURFACE_ROAD, vertices[vertex * stride + 6],
                            "a road ribbon is drawn as a road");
                    assertEquals(0f, vertices[vertex * stride + 4], "and lies flat: its normal is up");
                }
            }
            // The four corners of a ribbon are a parallelogram: opposite corners differ by the same
            // vector, which is what makes it a ribbon rather than a bowtie.
            int a = segment * FarLayerMesh.ROAD_VERTICES_PER_SEGMENT;
            assertEquals(vertices[a * stride] + vertices[(a + 2) * stride]
                            - vertices[(a + 3) * stride], vertices[(a + 1) * stride], 0.001,
                    "segment " + segment + " is a quadrilateral, not a crossing pair of triangles");
        }

        for (int palm = 0; palm < layer.palms; palm++) {
            int first = (layer.roadSegments * FarLayerMesh.ROAD_VERTICES_PER_SEGMENT
                    + palm * FarLayerMesh.PALM_VERTICES) * stride;
            double centreEast = 0;
            double centreNorth = 0;
            for (int i = 0; i < FarLayerMesh.PALM_VERTICES; i++) {
                centreEast += vertices[first + i * stride];
                centreNorth += vertices[first + i * stride + 2];
            }
            centreEast /= FarLayerMesh.PALM_VERTICES;
            centreNorth /= FarLayerMesh.PALM_VERTICES;
            for (int t = 0; t < FarLayerMesh.PALM_TRIANGLES; t++, triangle++) {
                double[] normal = geometricNormal(vertices, indices, triangle);
                assertAgreesWithStoredNormal(vertices, indices, triangle, normal, "palm " + palm);
                int vertex = indices[triangle * 3] & 0xFFFF;
                assertEquals(VegetationMesher.SURFACE, vertices[vertex * stride + 6],
                        "a palm is drawn as vegetation");
                assertEquals(0f, vertices[vertex * stride + 9], "and as a palm rather than as scrub, "
                        + "which the shader reads from the tint");
                if (t < 4 * 2) {
                    // A trunk wall: vertical, so it has no up-facing component at all, and outward, so it
                    // points away from the trunk's own axis -- the axis being the middle of the palm.
                    double outwardEast = vertices[vertex * stride] - centreEast;
                    double outwardNorth = vertices[vertex * stride + 2] - centreNorth;
                    assertTrue(Math.abs(normal[1]) < 0.01, "a trunk wall leans: " + normal[1]);
                    assertTrue(normal[0] * outwardEast + normal[2] * outwardNorth > 0.0,
                            "palm " + palm + " has a trunk wall that faces inwards, which is invisible "
                                    + "from outside");
                } else {
                    assertTrue(normal[1] > 0.0, "palm " + palm + " has a crown face pointing at the "
                            + "ground, which is the half of the crown nobody sees");
                }
            }
            // The vertex language, exactly as VegetationMesher writes it and the shader reads it: u is
            // the height fraction up the plant, the trunk runs from 0 to the split, and the crown is
            // written at the split and at 1. Four walls of four vertices, then the apex, then eight rim
            // vertices, which is the order the mesher added them in.
            for (int wall = 0; wall < 4; wall++) {
                for (int corner = 0; corner < 4; corner++) {
                    int vertex = wall * 4 + corner;
                    float u = vertices[first + vertex * stride + 7];
                    float expected = corner < 2 ? 0f : (float) VegetationMesher.PALM_TRUNK_FRACTION;
                    assertEquals(expected, u, 0.0001f, "trunk wall " + wall + " corner " + corner
                            + " is drawn at the wrong height fraction, which repaints it: " + u);
                }
            }
            assertEquals(1f, vertices[first + 16 * stride + 7], 0.0001f, "the crown's apex is the top "
                    + "of the plant");
            for (int rim = 0; rim < FarLayerMesh.PALM_SIDES; rim++) {
                assertEquals((float) VegetationMesher.PALM_TRUNK_FRACTION,
                        vertices[first + (17 + rim) * stride + 7], 0.0001f,
                        "the crown's rim is drawn at the split, as the detailed palms are");
            }
        }

        for (int patch = 0; patch < layer.canopies; patch++) {
            int first = (layer.roadSegments * FarLayerMesh.ROAD_VERTICES_PER_SEGMENT
                    + layer.palms * FarLayerMesh.PALM_VERTICES
                    + patch * FarLayerMesh.CANOPY_VERTICES) * stride;
            for (int t = 0; t < FarLayerMesh.CANOPY_TRIANGLES; t++, triangle++) {
                double[] normal = geometricNormal(vertices, indices, triangle);
                assertTrue(normal[1] > 0.9, "canopy triangle " + triangle + " does not face the sky: "
                        + normal[1]);
                assertAgreesWithStoredNormal(vertices, indices, triangle, normal, "canopy " + patch);
            }
            for (int i = 0; i < FarLayerMesh.CANOPY_VERTICES; i++) {
                int vertex = layer.roadSegments * FarLayerMesh.ROAD_VERTICES_PER_SEGMENT
                        + layer.palms * FarLayerMesh.PALM_VERTICES
                        + patch * FarLayerMesh.CANOPY_VERTICES + i;
                assertEquals(VegetationMesher.SURFACE, vertices[vertex * stride + 6],
                        "a canopy patch is drawn as vegetation");
                assertEquals(1f, vertices[vertex * stride + 7], "at the top of the plant, which the "
                        + "shader paints as frond");
                assertEquals(1f, vertices[vertex * stride + 9], "and as scrub rather than as a palm");
                // The compiled material comes through: the fixture's vegetated ring is class 12, which the
                // shader uses to darken mangrove and marsh.
                assertEquals(12f, vertices[vertex * stride + 10], "the compiled material is carried");
            }
        }
        assertEquals(mesh.triangleCount(), triangle, "every triangle of the mesh was checked");
    }

    /** The geometric winding agrees with the normal the shader was given. */
    private static void assertAgreesWithStoredNormal(float[] vertices, short[] indices, int triangle,
                                                     double[] normal, String what) {
        int stride = MeshData.FLOATS_PER_VERTEX;
        int vertex = indices[triangle * 3] & 0xFFFF;
        double agreement = normal[0] * vertices[vertex * stride + 3]
                + normal[1] * vertices[vertex * stride + 4]
                + normal[2] * vertices[vertex * stride + 5];
        assertTrue(agreement > 0.9, what + " triangle " + triangle
                + " is wound against the normal it was given: " + agreement);
    }

    /** The un-normalised normal of a triangle, from the vertices it names. */
    private static double[] geometricNormal(float[] vertices, short[] indices, int triangle) {
        int stride = MeshData.FLOATS_PER_VERTEX;
        int a = indices[triangle * 3] & 0xFFFF;
        int b = indices[triangle * 3 + 1] & 0xFFFF;
        int c = indices[triangle * 3 + 2] & 0xFFFF;
        double ux = vertices[b * stride] - vertices[a * stride];
        double uy = vertices[b * stride + 1] - vertices[a * stride + 1];
        double uz = vertices[b * stride + 2] - vertices[a * stride + 2];
        double vx = vertices[c * stride] - vertices[a * stride];
        double vy = vertices[c * stride + 1] - vertices[a * stride + 1];
        double vz = vertices[c * stride + 2] - vertices[a * stride + 2];
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return length < 1e-9 ? new double[]{0, 0, 0} : new double[]{nx / length, ny / length, nz / length};
    }

    private static void assertArrayEquals(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length, "the two builds are different lengths");
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                throw new AssertionError("vertex data differs at " + i + ": " + expected[i] + " and "
                        + actual[i]);
            }
        }
    }

    private static void assertArrayEquals(short[] expected, short[] actual) {
        assertEquals(expected.length, actual.length, "the two builds are different lengths");
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                throw new AssertionError("index data differs at " + i + ": " + expected[i] + " and "
                        + actual[i]);
            }
        }
    }
}
