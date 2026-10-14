package ai.arena.qatar.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resident window is a memory budget, and this is where that is asserted.
 *
 * <p>The distance ring alone allows about 450 chunks within six kilometres, and each one can hold tens
 * of thousands of vertices where the country is dense. Hundreds of megabytes of geometry, on a phone
 * that is also holding the compiled country -- which is how the third install of this game ended, with
 * the process killed and no exception, no stack trace and nothing to read.
 *
 * <p>So these tests hold the cache to three promises: it keeps the memory inside the budget, it takes
 * the geometry back from the chunk the player is least able to see, and the counts it budgets with stay
 * true when a chunk is uploaded, when a chunk fails, and when one is evicted.
 */
public class ChunkMeshCacheBudgetTest {

    /** A chunk mesh whose detail holds exactly this many vertices, with no GL involved. */
    private static ChunkMesher.ChunkMesh meshOf(int vertices) {
        ChunkMesher.ChunkMesh mesh = new ChunkMesher.ChunkMesh();
        MeshData part = mesh.detailPart();
        for (int i = 0; i < vertices; i++) {
            part.addVertex(i, 0, 0, 0, 1, 0, 0, 0, 0, 1);
        }
        return mesh;
    }

    private static Set<Long> residentKeys(ChunkMeshCache cache) {
        Set<Long> out = new HashSet<>();
        for (ChunkMeshCache.Entry entry : cache.resident()) {
            out.add(entry.key());
        }
        return out;
    }

    @Test
    @DisplayName("the window is held to its memory budget, and the farthest chunk pays for it")
    void overBudgetEvictsTheFarthestChunk() {
        ChunkMeshCache cache = new ChunkMeshCache();
        // A grid of chunks that all sit inside the streaming radius -- 6.9 km at the far corner, and the
        // ring is 7 km -- so the only thing that can evict them is the memory budget, not the distance.
        int columns = 12;
        int rows = 8;
        int verticesEach = 12_000;
        int chunks = columns * rows;
        for (int x = 0; x < columns; x++) {
            for (int z = 0; z < rows; z++) {
                cache.put(x, z, meshOf(verticesEach));
            }
        }
        assertEquals(chunks * verticesEach, cache.residentVertices(),
                "the cache counts every vertex it holds");
        assertTrue(cache.residentVertices() > ChunkMeshCache.MAX_RESIDENT_VERTICES,
                "the test has to be over budget to mean anything");

        cache.update(0, 0, 0);              // no builds; just the eviction pass

        assertTrue(cache.residentVertices() <= ChunkMeshCache.MAX_RESIDENT_VERTICES,
                "the window is back inside its budget: " + cache.residentVertices());
        Set<Long> resident = residentKeys(cache);
        assertTrue(resident.contains(ChunkMeshCache.key(0, 0)),
                "the nearest chunk, under the player, is still there");
        assertFalse(resident.contains(ChunkMeshCache.key(columns - 1, rows - 1)),
                "the far corner of the window is the geometry that was taken back");
        assertTrue(cache.budgetEvictions() >= 12, "the budget did the evicting, not the distance ring: "
                + cache.budgetEvictions());
        assertTrue(resident.size() >= 80, "and the world around the player is intact: " + resident.size()
                + " chunks");
    }

    @Test
    @DisplayName("uploading a chunk frees its CPU copy without losing it from the budget")
    void uploadFreesTheCpuCopyAndKeepsTheCount() {
        ChunkMeshCache cache = new ChunkMeshCache();
        ChunkMeshCache.Entry entry = cache.put(3, 4, meshOf(12_000));
        assertEquals(12_000, cache.residentVertices(), "counted when it was built");
        assertEquals(12_000, entry.mesh.vertexCount(), "and the CPU copy is still here");

        cache.markUploaded(entry);
        assertTrue(entry.uploaded, "the chunk is on the GPU and can be drawn");
        assertEquals(0, entry.mesh.vertexCount(), "the CPU arrays are gone");
        assertEquals(12_000, cache.residentVertices(),
                "but the geometry is still resident: the GPU holds it, and the budget counts it");

        // The chunk is not offered for building again just because its CPU copy was released.
        for (ChunkMeshCache.Entry candidate : cache.update(1600, 2100, 8)) {
            assertFalse(candidate.chunkX == 3 && candidate.chunkZ == 4, "a resident chunk is not rebuilt");
        }
    }

    @Test
    @DisplayName("a chunk that fails after being recorded does not leave its geometry in the budget")
    void failedChunkIsTakenBackOutOfTheBudget() {
        ChunkMeshCache cache = new ChunkMeshCache();
        cache.put(7, 7, meshOf(9_000));
        assertEquals(9_000, cache.residentVertices(), "counted when it was built");

        // Uploading threw, so the chunk is remembered as failed: it is empty, invisible, and -- above
        // all -- it must not still be charging the window for geometry that no longer exists.
        cache.putFailed(7, 7);
        assertEquals(0, cache.residentVertices(), "the failed chunk's geometry is off the budget");
        assertTrue(residentKeys(cache).contains(ChunkMeshCache.key(7, 7)),
                "and it stays known, so it is not retried every frame");
    }
}
