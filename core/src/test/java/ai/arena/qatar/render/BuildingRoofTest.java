package ai.arena.qatar.render;

import ai.arena.qatar.world.Buildings;
import ai.arena.qatar.world.GameManifest;
import ai.arena.qatar.world.TerrainGrid;
import ai.arena.qatar.world.WorldData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The roofs, which were the wrong way round for seven installs.
 *
 * <p>The eighth report said the buildings look like walls, and that is precisely what they were: every
 * roof triangle in the country faced <em>down</em>, so the world's back-face culling threw all of them
 * away. A city of 186,993 boxes with no lid -- you could see the inside of a building's own walls
 * through its roofline, and its roof at all only if the camera was underneath it.
 *
 * <p>The bug was a handedness mistake, so the test does not take the handedness on trust: every normal
 * here is computed with the actual three-dimensional cross product from the actual vertex positions, and
 * the ground -- which has always been drawn correctly -- is the control. If the ground faces up and the
 * roofs face up by the same rule, the rule is right.
 *
 * <p>It covers both ring directions and all five roof shapes, because the bug was in the shape-independent
 * part: reversing the ring by hand is the difference between two thirds of the country's roofs showing
 * and none of them.
 */
final class BuildingRoofTest {

    private static final int FLOATS = MeshData.FLOATS_PER_VERTEX;
    private static final float HEIGHT_M = 9f;

    private static WorldData world;

    @BeforeAll
    static void loadFixture() throws IOException {
        Path fixture = Paths.get("src/test/resources/synthetic_world");
        if (!Files.isDirectory(fixture)) {
            fixture = Paths.get("core/src/test/resources/synthetic_world");
        }
        assertTrue(Files.isDirectory(fixture), "synthetic world fixture is missing: " + fixture);
        Path dir = fixture;
        GameManifest manifest = GameManifest.parse(
                new String(Files.readAllBytes(dir.resolve("manifest.json")), StandardCharsets.UTF_8));
        world = WorldData.fromSource(manifest, name -> read(dir, name));
    }

    private static byte[] read(Path dir, String name) {
        try {
            return Files.readAllBytes(dir.resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException("fixture file unreadable: " + name, e);
        }
    }

    /**
     * One rectangular footprint 8 m by 6 m, given either way round, with a chosen roof shape.
     *
     * <p>Built by hand rather than taken from the bundle so both windings are covered: measured on the
     * real country, 185,551 of 186,993 rings come one way and 1,442 come the other, and the bug hid in
     * the majority.
     */
    private static Buildings rectangle(boolean counterClockwiseInEastNorth, int roofShape) {
        float[][] open = {
                {0f, 0f}, {8f, 0f}, {8f, 6f}, {0f, 6f},
        };
        int vertices = open.length + 1;                      // OSM rings repeat the first vertex
        float[] xz = new float[vertices * 2];
        for (int i = 0; i < open.length; i++) {
            // `open` is listed counter-clockwise in (east, north); take it as it is or reverse it.
            int source = counterClockwiseInEastNorth ? i : open.length - 1 - i;
            xz[i * 2] = open[source][0];
            xz[i * 2 + 1] = open[source][1];
        }
        xz[(vertices - 1) * 2] = xz[0];
        xz[(vertices - 1) * 2 + 1] = xz[1];

        // signedArea here is computed in (east, north) with the standard 2D cross product, which is what
        // BuildingMesher reads; make sure the fixture really is what the name says.
        double twice = 0;
        for (int i = 0; i < open.length; i++) {
            int a = i * 2;
            int b = ((i + 1) % open.length) * 2;
            twice += xz[a] * xz[b + 1] - xz[b] * xz[a + 1];
        }
        boolean isCounterClockwise = twice > 0;
        assertEquals(counterClockwiseInEastNorth, isCounterClockwise,
                "the fixture ring is wound the wrong way for this test");

        return new Buildings(1, new int[]{0}, new int[]{vertices}, xz, new float[]{HEIGHT_M},
                new float[]{0f}, new byte[]{Buildings.TYPE_HOUSE}, new byte[]{(byte) roofShape},
                new int[]{0}, new byte[]{0}, new float[]{0f, 0f, 8f, 6f});
    }

    /**
     * The triangle's normal, computed the way the GPU does: {@code (b - a) x (c - a)}. Only the y
     * component is needed here -- this file is asking whether a face looks at the sky -- and it is
     * {@code uz*vx - ux*vz} out of that cross product.
     */
    private static float normalY(float[] v, int a, int b, int c) {
        float ax = v[a * FLOATS], az = v[a * FLOATS + 2];
        float ux = v[b * FLOATS] - ax;
        float uz = v[b * FLOATS + 2] - az;
        float vx = v[c * FLOATS] - ax;
        float vz = v[c * FLOATS + 2] - az;
        return uz * vx - ux * vz;
    }

    private static float surface(float[] v, int index) {
        return v[index * FLOATS + 6];
    }

    private static float projectedArea(float[] v, int a, int b, int c) {
        float ax = v[a * FLOATS], az = v[a * FLOATS + 2];
        float bx = v[b * FLOATS], bz = v[b * FLOATS + 2];
        float cx = v[c * FLOATS], cz = v[c * FLOATS + 2];
        return Math.abs((bx - ax) * (cz - az) - (bz - az) * (cx - ax)) * 0.5f;
    }

    @Test
    @DisplayName("the ground is drawn facing up -- the control this whole test is measured against")
    void theGroundFacesUp() {
        // TerrainMesher's rule, in its own words: addQuad(a, d, c, b) for the grid corners a=(west,south),
        // b=(east,south), c=(east,north), d=(west,north).
        MeshData mesh = new MeshData();
        int a = mesh.addVertex(0f, 0f, 0f, 0f, 1f, 0f, VertexFormat.SURFACE_LAND, 0f, 0f, 1f);
        int b = mesh.addVertex(10f, 0f, 0f, 0f, 1f, 0f, VertexFormat.SURFACE_LAND, 1f, 0f, 1f);
        int c = mesh.addVertex(10f, 0f, 10f, 0f, 1f, 0f, VertexFormat.SURFACE_LAND, 1f, 1f, 1f);
        int d = mesh.addVertex(0f, 0f, 10f, 0f, 1f, 0f, VertexFormat.SURFACE_LAND, 0f, 1f, 1f);
        mesh.addQuad(a, d, c, b);
        float[] v = mesh.vertices();
        short[] indices = mesh.indices();
        for (int t = 0; t < mesh.triangleCount(); t++) {
            assertTrue(normalY(v, indices[t * 3], indices[t * 3 + 1], indices[t * 3 + 2]) > 0f,
                    "the ground triangle " + t + " faces down: every other rule in this file is derived "
                            + "from this one");
        }
    }

    @Test
    @DisplayName("the winding rule BuildingMesher uses agrees with the real normal, always")
    void theWindingRuleMatchesTheNormal() {
        // n_y = uz*vx - ux*vz out of the cross product holds only the x and z differences, so the
        // projected winding of a triangle decides which way it looks *whatever* its heights are -- which
        // is why one rule can cover flat and pitched roofs at once. Checked on triangles that are sloped,
        // not just on horizontals: that is the claim the roofs depend on.
        float[][] corners = {
                {0f, 0f, 8f, 0f, 4f, 3f},
                {0f, 0f, 8f, 0f, 0f, 6f},
                {1f, 1f, 2f, 5f, 7f, 2f},
        };
        float[] heights = {0f, 1.5f, 3f, -2f};
        for (float[] c : corners) {
            for (float ay : heights) {
                MeshData mesh = new MeshData();
                int a = mesh.addVertex(c[0], ay, c[1], 0, 1, 0, VertexFormat.SURFACE_ROOF, 0, 0, 1f);
                int b = mesh.addVertex(c[2], ay - 1.2f, c[3], 0, 1, 0, VertexFormat.SURFACE_ROOF, 0, 0, 1f);
                int cc = mesh.addVertex(c[4], ay + 2.4f, c[5], 0, 1, 0, VertexFormat.SURFACE_ROOF, 0, 0, 1f);
                mesh.addTriangle(a, b, cc);
                float[] v = mesh.vertices();
                float ny = normalY(v, a, b, cc);
                assertEquals(ny > 0f, BuildingMesher.facesUp(c[0], c[1], c[2], c[3], c[4], c[5]),
                        "the rule and the normal disagree for (" + c[0] + ", " + c[1] + ") ("
                                + c[2] + ", " + c[3] + ") (" + c[4] + ", " + c[5] + ") at y " + ay);
            }
        }
    }

    @Test
    @DisplayName("every roof faces the sky, for every shape, whichever way the ring came in")
    void everyRoofFacesTheSky() {
        for (int shape : new int[]{Buildings.ROOF_FLAT, Buildings.ROOF_GABLE, Buildings.ROOF_DOME,
                Buildings.ROOF_PYRAMID, Buildings.ROOF_SHED}) {
            for (boolean counterClockwise : new boolean[]{true, false}) {
                MeshData mesh = new MeshData();
                Buildings buildings = rectangle(counterClockwise, shape);
                assertTrue(new BuildingMesher(buildings, null).addBuilding(0, mesh),
                        "no geometry at all for roof shape " + shape);
                float[] v = mesh.vertices();
                short[] indices = mesh.indices();
                float upwardArea = 0f;
                int roofTriangles = 0;
                for (int t = 0; t < mesh.triangleCount(); t++) {
                    int i0 = indices[t * 3];
                    int i1 = indices[t * 3 + 1];
                    int i2 = indices[t * 3 + 2];
                    if (surface(v, i0) != VertexFormat.SURFACE_ROOF) {
                        continue;
                    }
                    roofTriangles++;
                    float ny = normalY(v, i0, i1, i2);
                    assertTrue(ny >= -1e-3f, "a " + shapeName(shape) + " roof triangle faces down (n_y = "
                            + ny + "), ring counterClockwise=" + counterClockwise
                            + ": the GPU culls it and the building has no roof");
                    if (ny > 0f) {
                        upwardArea += projectedArea(v, i0, i1, i2);
                    }
                }
                assertTrue(roofTriangles > 0, "no roof triangles for " + shapeName(shape));
                // The deck covers the footprint: 8 x 6 m of it, and any shaped cap rises above that deck
                // rather than replacing it with a tent full of holes. A missing deck triangle shows up
                // here as an area that is smaller than the building.
                assertTrue(upwardArea >= 48f - 0.05f,
                        "the " + shapeName(shape) + " roof leaves the footprint open: only " + upwardArea
                                + " of 48 m2 faces up (counterClockwise=" + counterClockwise + ")");
                if (shape == Buildings.ROOF_FLAT) {
                    assertEquals(48f, upwardArea, 0.05f,
                            "a flat roof is exactly the footprint, no more and no less");
                }
            }
        }
    }

    @Test
    @DisplayName("walls still face outwards, and stay vertical")
    void wallsStillFaceOut() {
        for (boolean counterClockwise : new boolean[]{true, false}) {
            MeshData mesh = new MeshData();
            Buildings buildings = rectangle(counterClockwise, Buildings.ROOF_FLAT);
            new BuildingMesher(buildings, null).addBuilding(0, mesh);
            float[] v = mesh.vertices();
            short[] indices = mesh.indices();
            int walls = 0;
            for (int t = 0; t < mesh.triangleCount(); t++) {
                int i0 = indices[t * 3];
                int i1 = indices[t * 3 + 1];
                int i2 = indices[t * 3 + 2];
                if (surface(v, i0) != VertexFormat.SURFACE_WALL) {
                    continue;
                }
                walls++;
                float ny = normalY(v, i0, i1, i2);
                assertEquals(0f, ny, 1e-3f, "a wall leans: its normal has a vertical part");
                // Outwards, checked against the stored normal rather than by guessing at the ring: the
                // stored normal is what the shader lights the wall with, and it must point away from the
                // middle of the footprint at (4, 3).
                float nx = v[i0 * FLOATS + 3];
                float nz = v[i0 * FLOATS + 5];
                float midX = 0f;
                float midZ = 0f;
                for (int corner = 0; corner < 3; corner++) {
                    midX += v[indices[t * 3 + corner] * FLOATS] / 3f;
                    midZ += v[indices[t * 3 + corner] * FLOATS + 2] / 3f;
                }
                float outwardX = midX - 4f;
                float outwardZ = midZ - 3f;
                float outwardLength = (float) Math.sqrt(outwardX * outwardX + outwardZ * outwardZ);
                outwardX /= outwardLength;
                outwardZ /= outwardLength;
                assertTrue(nx * outwardX + nz * outwardZ > 0.9f,
                        "a wall's normal points into the building (counterClockwise=" + counterClockwise
                                + ", n=(" + nx + ", " + nz + "), outward=(" + outwardX + ", " + outwardZ
                                + ")): seen from the street it is a hole");
            }
            assertEquals(8, walls, "an 8x6 m building has four walls, so eight triangles");
        }
    }

    @Test
    @DisplayName("the whole committed fixture: every wall vertical, every roof up")
    void theFixtureHasNoRooflessBuildings() {
        BuildingMesher mesher = new BuildingMesher(world.buildings, world.terrainL1);
        int meshed = 0;
        for (int ring = 0; ring < world.buildings.ringCount; ring++) {
            MeshData mesh = new MeshData();
            if (!mesher.addBuilding(ring, mesh)) {
                continue;
            }
            meshed++;
            float[] v = mesh.vertices();
            short[] indices = mesh.indices();
            boolean roof = false;
            for (int t = 0; t < mesh.triangleCount(); t++) {
                int i0 = indices[t * 3];
                int i1 = indices[t * 3 + 1];
                int i2 = indices[t * 3 + 2];
                float ny = normalY(v, i0, i1, i2);
                if (surface(v, i0) == VertexFormat.SURFACE_WALL) {
                    assertEquals(0f, ny, 1e-3f, "ring " + ring + " has a leaning wall");
                } else if (surface(v, i0) == VertexFormat.SURFACE_ROOF) {
                    assertTrue(ny >= -1e-3f, "ring " + ring + " has a roof triangle facing down");
                    roof |= ny > 0f;
                }
            }
            assertTrue(roof, "ring " + ring + " was meshed with no roof facing up");
        }
        assertTrue(meshed > 0, "the fixture has no buildings to mesh");
    }

    private static String shapeName(int shape) {
        switch (shape) {
            case Buildings.ROOF_FLAT:
                return "flat";
            case Buildings.ROOF_GABLE:
                return "gabled";
            case Buildings.ROOF_DOME:
                return "domed";
            case Buildings.ROOF_PYRAMID:
                return "pyramidal";
            case Buildings.ROOF_SHED:
                return "shed";
            default:
                return "unknown(" + shape + ")";
        }
    }
}
