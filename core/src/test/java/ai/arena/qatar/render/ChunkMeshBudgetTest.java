package ai.arena.qatar.render;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import ai.arena.qatar.world.Buildings;
import ai.arena.qatar.world.GameManifest;
import ai.arena.qatar.world.WorldData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vertex budget, tested where it broke.
 *
 * <p>The third install of this game closed immediately with nothing on the screen. The reason was here:
 * a mesh holds 65,000 vertices, the meshers asked {@code isFull()} -- "do four more fit?" -- before
 * adding a building of forty vertices or a road of three hundred, and the primitive then ran off the
 * end of the mesh and threw. The exception escaped the render loop, the GL thread died, and the app
 * went with it.
 *
 * <p>So this class tests three things, in the order they matter:
 * <ol>
 *   <li>The <b>rule</b>: a primitive is sized before it is placed, a part that is full rolls over to the
 *       next part, and a primitive that fits no part is refused rather than started.</li>
 *   <li>The <b>real city</b>: the densest 500 m chunk of the committed country meshes without throwing,
 *       however much of Doha is inside it, and every part it produces is inside the index buffer limit.
 *       This is the test that would have failed before the fix -- on the real bundles the APK ships.</li>
 *   <li>The <b>round trip</b>: what {@link MeshData#hasRoom(int)} promises is what adding vertices
 *       actually does, so the question the mesher asks cannot drift away from the answer.</li>
 * </ol>
 */
public class ChunkMeshBudgetTest {

    private static Path worldDir() {
        Path[] candidates = {
                Paths.get("..", "android", "assets", "world"),
                Paths.get("android", "assets", "world"),
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    @DisplayName("a full part rolls over instead of throwing, and the limit is reported honestly")
    void fullPartRollsOver() {
        ChunkMesher.ChunkMesh mesh = new ChunkMesher.ChunkMesh();
        MeshData first = mesh.detailPartFor(4);
        assertNotNull(first, "an empty chunk has room for a quad");
        while (first.hasRoom(4)) {
            first.addVertex(0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
        }
        assertEquals(MeshData.MAX_VERTICES - 3, first.vertexCount(),
                "the loop stops with exactly three vertices left, one short of a quad");
        assertTrue(first.isFull(), "a mesh with three vertices left is full for a quad");

        // The crash in one line: ask for the primitive, not for a generic quad.
        assertFalse(first.hasRoom(40), "a forty-vertex building does not fit a mesh with three left");
        MeshData second = mesh.detailPartFor(40);
        assertNotNull(second, "a second part takes the building");
        assertEquals(2, mesh.detail.size(), "the chunk split its detail in two");
        assertEquals(0, second.vertexCount(), "and the building starts at the beginning of it");
        assertFalse(mesh.truncated, "nothing has been dropped yet");
    }

    @Test
    @DisplayName("a primitive too big for any part is refused, never started")
    void impossiblePrimitiveIsRefused() {
        ChunkMesher.ChunkMesh mesh = new ChunkMesher.ChunkMesh();
        // A single ring with more edges than the whole index buffer can hold -- a malformed footprint
        // instead of a shed. It must be dropped and flagged, because there is no part it can go in.
        assertNull(mesh.detailPartFor(MeshData.MAX_VERTICES + 1),
                "no part can hold more vertices than the index buffer addresses");
        assertTrue(mesh.truncated, "and the chunk says it is missing geometry");
        assertEquals(1, mesh.detail.size(), "an empty part was still created first, which is harmless");

        // Costs are reported unclamped so this distinction is visible at all. A footprint of ten
        // thousand edges needs more vertices than the index buffer can address, and the caller has to
        // be able to see that -- a value quietly capped at 65,000 would look like something that fits
        // in a fresh part.
        Buildings huge = new Buildings(1, new int[]{0}, new int[]{10_000},
                new float[20_000], new float[]{12f}, new float[]{0f}, new byte[]{1}, new byte[]{0},
                new int[]{0}, new byte[]{0}, new float[]{0, 0, 30, 30});
        int cost = BuildingMesher.vertexCost(huge, 0);
        assertTrue(cost > MeshData.MAX_VERTICES,
                "an oversized footprint reports a cost over the limit, not a clamped one: " + cost);
        Buildings shed = new Buildings(1, new int[]{0}, new int[]{5},
                new float[10], new float[]{3f}, new float[]{0f}, new byte[]{1}, new byte[]{0},
                new int[]{0}, new byte[]{0}, new float[]{0, 0, 6, 6});
        assertEquals(64, BuildingMesher.vertexCost(shed, 0),
                "four per wall edge plus a roof: eight per edge and twenty-four");
    }

    @Test
    @DisplayName("hasRoom promises exactly what adding vertices then does")
    void hasRoomMatchesWhatHappens() {
        MeshData data = new MeshData(4, 6);
        for (int i = 0; i < MeshData.MAX_VERTICES - 4; i++) {
            data.addVertex(i, 0, 0, 0, 1, 0, 0, 0, 0, 1);
        }
        assertEquals(MeshData.MAX_VERTICES - 4, data.remainingVertices(), "remaining vertices");
        assertTrue(data.hasRoom(4), "exactly four fit");
        assertFalse(data.hasRoom(5), "five do not");
        assertFalse(data.isFull(), "and a quad still fits");
        for (int i = 0; i < 4; i++) {
            data.addVertex(0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
        }
        assertEquals(MeshData.MAX_VERTICES, data.vertexCount(), "the mesh is exactly full now");
        assertEquals(0, data.remainingVertices(), "and has nothing left");
        assertTrue(data.isFull(), "so it is full, and the next caller has to ask first");
        data.compact();
        assertEquals(MeshData.MAX_VERTICES * MeshData.FLOATS_PER_VERTEX * 4, data.memoryBytes(),
                "compaction keeps exactly the vertices that were written");

        // The memory that matters is the array a mesh grew into and never used. Every chunk allocates
        // three of these and hundreds of chunks are resident, so a small default plus a trim is the
        // difference between a game and an out-of-memory kill with no stack trace.
        MeshData small = new MeshData(4, 6);
        for (int i = 0; i < 10; i++) {
            small.addVertex(i, 0, 0, 0, 1, 0, 0, 0, 0, 1);
        }
        int grown = small.memoryBytes();
        small.compact();
        assertEquals(10 * MeshData.FLOATS_PER_VERTEX * 4 + small.indexCount() * 2, small.memoryBytes(),
                "a compacted mesh holds its vertices and nothing else");
        assertTrue(small.memoryBytes() < grown,
                "and less than the array it grew into: " + grown + " -> " + small.memoryBytes());
        small.releaseArrays();
        assertEquals(0, small.vertexCount(), "released arrays hold no vertices");
        assertEquals(0, small.memoryBytes(), "and no bytes");
    }

    @Test
    @DisplayName("the densest chunk of the committed country meshes inside every part")
    void densestChunkOfQatarStaysInsideTheBudget() {
        Path dir = worldDir();
        Assumptions.assumeTrue(dir != null, "android/assets/world is not checked out");
        WorldData world = load(dir);

        // Find the chunk with the most building footprints starting in it: the worst case the game can
        // be asked to draw, found in the real data rather than guessed at.
        Map<Integer, Integer> density = new HashMap<>();
        int bestKey = 0;
        int best = -1;
        for (int ring = 0; ring < world.buildings.ringCount; ring++) {
            int chunkX = ChunkMesher.chunkIndex(world.buildings.bounds[ring * 4]);
            int chunkZ = ChunkMesher.chunkIndex(world.buildings.bounds[ring * 4 + 1]);
            int key = chunkX * 4096 + chunkZ;
            int count = density.merge(key, 1, Integer::sum);
            if (count > best) {
                best = count;
                bestKey = key;
            }
        }
        assertTrue(best > 40, "the densest chunk of Qatar holds a city block: " + best + " buildings");
        int chunkX = bestKey / 4096;
        int chunkZ = bestKey % 4096;

        ChunkMesher mesher = new ChunkMesher(world);
        ChunkMesher.ChunkMesh near = mesher.mesh(chunkX, chunkZ, 1.0);
        assertTrue(near.buildings > 40, "the near mesh extruded the city: " + near.buildings
                + " buildings, " + near.describe());
        assertTrue(near.roads > 0, "and the streets: " + near.roads + " ways");
        checkPartsInsideTheLimit(near);
        // The chunk is meshed at full detail, so it must be clipped only if it genuinely overran three
        // parts -- and if it did, the parts must all be there and all be full.
        if (near.truncated) {
            assertEquals(ChunkMesher.ChunkMesh.MAX_DETAIL_PARTS, near.detail.size(),
                    "a truncated chunk used every part it is allowed");
        }

        // A small neighbourhood of the same place, so the whole streaming path is exercised where it is
        // heaviest rather than on one lucky chunk.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkMesher.ChunkMesh mesh = mesher.mesh(chunkX + dx, chunkZ + dz, 1.0);
                checkPartsInsideTheLimit(mesh);
                assertTrue(mesh.vertexCount() > 0, "chunk " + (chunkX + dx) + "," + (chunkZ + dz)
                        + " produced terrain at least");
            }
        }

        // Beyond the detail radius only landmarks are drawn. Before that rule existed a distant chunk
        // extruded every footprint inside it, which is what made the far half of the resident window
        // expensive enough to be worth caring about.
        ChunkMesher.ChunkMesh far = mesher.mesh(chunkX, chunkZ, 0.0);
        assertTrue(far.buildings < near.buildings,
                "landmarks only: " + far.buildings + " vs " + near.buildings + " buildings");
        assertTrue(far.vertexCount() < near.vertexCount(),
                "and far less geometry: " + far.vertexCount() + " vs " + near.vertexCount());
        checkPartsInsideTheLimit(far);

        // Road ribbons are dropped entirely past their cutoff, so the road count is zero and the ground
        // is still there: a distant chunk is terrain, not a hole.
        assertTrue(far.roads == 0, "no road ribbons at detail 0: " + far.roads);
        assertTrue(far.land.vertexCount() > 0, "the ground is drawn at any distance");
    }

    /** Every part must be addressable by a 16-bit index buffer, and only the last one may be partial. */
    private static void checkPartsInsideTheLimit(ChunkMesher.ChunkMesh mesh) {
        assertFalse(mesh.detail.isEmpty(), "a chunk inside the country has a detail part");
        for (int i = 0; i < mesh.detail.size(); i++) {
            MeshData part = mesh.detail.get(i);
            assertTrue(part.vertexCount() <= MeshData.MAX_VERTICES,
                    "part " + i + " of " + mesh.detail.size() + " holds " + part.vertexCount()
                            + " vertices, more than the index buffer can address");
            assertTrue(part.vertexCount() > 0, "part " + i + " is not an empty leftover");
        }
    }

    private static WorldData load(Path dir) {
        try {
            GameManifest manifest = GameManifest.parse(new String(
                    Files.readAllBytes(dir.resolve("manifest.json")), java.nio.charset.StandardCharsets.UTF_8));
            return WorldData.fromSource(manifest, file -> {
                try {
                    return Files.readAllBytes(dir.resolve(file));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("cannot read " + dir.resolve(file), e);
                }
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + dir, e);
        }
    }
}
