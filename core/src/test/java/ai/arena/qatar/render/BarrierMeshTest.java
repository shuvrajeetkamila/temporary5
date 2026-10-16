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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate in the drawn wall.
 *
 * <p>{@code BarrierGatesTest} holds the rule and {@code BarrierCollisionTest} holds the physics; this
 * holds the picture, because a gap that is walkable and drawn as masonry is the fault this round is
 * about. The chunk mesher leaves the wall out where a road crosses it, and the assertion is geometric:
 * walk the fixture's wall in the meshed chunk and find where the vertical wall surface stops and starts
 * again.
 *
 * <p>The fixture makes that exact rather than approximate. Its wall runs from (360, 180) to (700, 180),
 * its residential road runs north along easting 550 (6 m wide, so the gate is 3 m of half-width plus a
 * metre of gatepost at each side), and the gate is therefore a hole from 546 to 554.
 */
final class BarrierMeshTest {

    /** The chunk the gate is in: chunk 1 covers 500 to 1000 m east, 0 to 500 m north. */
    private static final int CHUNK_X = 1;
    private static final int CHUNK_Z = 0;

    private static WorldData world;

    @BeforeAll
    static void loadFixture() throws IOException {
        Path candidate = Paths.get("src/test/resources/synthetic_world");
        if (!Files.isDirectory(candidate)) {
            candidate = Paths.get("core/src/test/resources/synthetic_world");
        }
        assertTrue(Files.isDirectory(candidate), "the fixture is missing: " + candidate);
        Path fixture = candidate;
        GameManifest manifest = GameManifest.parse(
                new String(Files.readAllBytes(fixture.resolve("manifest.json")), StandardCharsets.UTF_8));
        world = WorldData.fromSource(manifest, name -> {
            try {
                return Files.readAllBytes(fixture.resolve(name));
            } catch (IOException e) {
                throw new IllegalStateException("fixture file unreadable: " + name, e);
            }
        });
    }

    @Test
    @DisplayName("the wall is drawn in two pieces, with a real hole where the road crosses it")
    void theWallIsDrawnWithAGate() {
        ChunkMesher.ChunkMesh mesh = new ChunkMesher(world).mesh(CHUNK_X, CHUNK_Z, 1.0);
        // The wall of the fixture that runs along northing 180, in the chunk's own frame: the mesher
        // writes local metres, and the chunk's origin is 500.
        double wallNorth = 180.0;
        double westOfGate = -1.0;
        double eastOfGate = -1.0;
        int atWall = 0;
        for (MeshData part : mesh.detail) {
            float[] vertices = part.vertices();
            for (int i = 0; i < part.vertexCount(); i++) {
                int at = i * MeshData.FLOATS_PER_VERTEX;
                if (vertices[at + 6] != VertexFormat.SURFACE_WALL) {
                    continue;
                }
                double east = vertices[at] + ChunkMesher.chunkOrigin(CHUNK_X);
                double north = vertices[at + 2] + ChunkMesher.chunkOrigin(CHUNK_Z);
                if (Math.abs(north - wallNorth) > 0.01) {
                    continue;                               // the hedge, and the wall's own top corners
                }
                atWall++;
                // Half a metre of slack at each side of the gateposts, because these are float coordinates:
                // a wall that stopped at 546.0001 is the wall stopping where it should, and a wall that
                // stopped at 549 is a wall across its own gate.
                if (east < 546.5) {
                    westOfGate = Math.max(westOfGate, east);
                } else if (east > 553.5) {
                    eastOfGate = eastOfGate < 0 ? east : Math.min(eastOfGate, east);
                } else {
                    throw new AssertionError("the wall is drawn across its own gate, at easting " + east);
                }
            }
        }
        assertTrue(atWall > 0, "no wall was meshed at all: the chunk has none of the fixture's masonry");
        assertEquals(546.0, westOfGate, 0.05,
                "the wall does not stop at the western gatepost: it ends at " + westOfGate);
        assertEquals(554.0, eastOfGate, 0.05,
                "the wall does not start again at the eastern gatepost: it starts at " + eastOfGate);
    }

    @Test
    @DisplayName("the hedge is drawn with its own gate, cut across its own direction")
    void theHedgeIsDrawnWithItsGate() {
        // The fixture's second barrier runs north along easting 700 from northing 180 to 420, and the
        // class 8 service road crosses it at 235.71: a four-metre road, so two metres of half-width plus
        // the metre of gatepost at each side, and a hole from 232.71 to 238.71. This is the assertion that
        // the rule works on a wall running the other way -- a hole cut across a line that is not
        // axis-aligned with the road that cut it is what a compound's side wall looks like.
        ChunkMesher.ChunkMesh mesh = new ChunkMesher(world).mesh(CHUNK_X, CHUNK_Z, 1.0);
        double southOfGate = -1.0;
        double northOfGate = -1.0;
        int atHedge = 0;
        for (MeshData part : mesh.detail) {
            float[] vertices = part.vertices();
            for (int i = 0; i < part.vertexCount(); i++) {
                int at = i * MeshData.FLOATS_PER_VERTEX;
                if (vertices[at + 6] != VertexFormat.SURFACE_WALL) {
                    continue;
                }
                double east = vertices[at] + ChunkMesher.chunkOrigin(CHUNK_X);
                if (Math.abs(east - 700.0) > 0.01) {
                    continue;
                }
                double north = vertices[at + 2] + ChunkMesher.chunkOrigin(CHUNK_Z);
                atHedge++;
                if (north < 232.5) {
                    southOfGate = Math.max(southOfGate, north);
                } else if (north > 238.9) {
                    northOfGate = northOfGate < 0 ? north : Math.min(northOfGate, north);
                } else {
                    throw new AssertionError("the hedge is drawn across its own gate, at northing " + north);
                }
            }
        }
        assertTrue(atHedge > 0, "the hedge was not meshed at all");
        assertEquals(232.71, southOfGate, 0.05, "the hedge does not stop at its southern gatepost");
        assertEquals(238.71, northOfGate, 0.05, "the hedge does not start again at its northern gatepost");
    }
}
