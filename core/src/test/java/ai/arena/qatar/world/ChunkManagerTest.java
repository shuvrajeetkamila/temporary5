package ai.arena.qatar.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Streaming contract: chunks have stable global identities, budgets are respected, collision terrain
 * is always resident around the focus, and a rebase never strands a chunk.
 */
class ChunkManagerTest {

    private static final double CELL = 100.0;
    private static final double ORIGIN_E = 431000.0;
    private static final double ORIGIN_N = 2681000.0;
    private static final int COLS = 600;   // 60 km of lattice for the test
    private static final int ROWS = 600;

    private static TerrainGrid grid() {
        short[] heights = new short[COLS * ROWS];
        byte[] mask = new byte[COLS * ROWS];
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                heights[r * COLS + c] = (short) (r / 3);
                mask[r * COLS + c] = (byte) (c < COLS / 2 ? 1 : 0);
            }
        }
        return new TerrainGrid("QTT1", COLS, ROWS, CELL, ORIGIN_E, ORIGIN_N, heights, mask);
    }

    @Test
    void collisionTerrainIsResidentAroundTheFocusInEveryMode() {
        ChunkManager manager = new ChunkManager(grid());
        double east = ORIGIN_E + 20000;
        double north = ORIGIN_N + 20000;
        for (ChunkManager.FocusMode mode : ChunkManager.FocusMode.values()) {
            manager.setMode(mode);
            for (int frame = 0; frame < 30; frame++) {
                manager.update(east, north, 0.0, 0.0, 1.0, 1.0 / 60.0);
            }
            assertTrue(manager.collisionAt(east, north),
                    mode + ": collision terrain must exist under the focus");
            assertTrue(manager.collisionAt(east + mode.collisionRadiusM * 0.5, north),
                    mode + ": collision terrain must extend outward within the profile radius");
            // 30 km away is far beyond every profile's ring (radius + one chunk of slack), so the
            // manager must report that collision data is not resident rather than pretend it is.
            assertFalse(manager.collisionAt(east + 30000.0, north),
                    mode + ": collision terrain far outside the safety ring must not be claimed");
        }
    }

    @Test
    void chunksBecomeActiveAndUploadsStayInBudget() {
        ChunkManager manager = new ChunkManager(grid());
        manager.setMode(ChunkManager.FocusMode.CAR);
        manager.setBudgets(2, 2);
        double east = ORIGIN_E + 20000;
        double north = ORIGIN_N + 20000;
        int frames = 0;
        while (manager.activeCount() == 0 && frames < 200) {
            manager.update(east, north, 0.0, 0.0, 1.0, 1.0 / 60.0);
            assertTrue(manager.gpuUploadsThisFrame() <= 2, "GPU uploads must respect the per-frame budget");
            frames++;
        }
        assertTrue(manager.activeCount() > 0, "chunks must become active within a couple of seconds");
        assertTrue(manager.countInState(ChunkManager.State.ACTIVE_HIGH) > 0,
                "immediate surroundings are high detail");
    }

    @Test
    void fasterProfilesLoadMoreThanSlowerOnes() {
        ChunkManager manager = new ChunkManager(grid());
        double east = ORIGIN_E + 20000;
        double north = ORIGIN_N + 20000;

        manager.setMode(ChunkManager.FocusMode.ON_FOOT);
        for (int i = 0; i < 60; i++) {
            manager.update(east, north, 0.0, 1.4, 1.0, 1.0 / 60.0);
        }
        int onFoot = manager.activeCount();

        manager.setMode(ChunkManager.FocusMode.HELICOPTER);
        for (int i = 0; i < 120; i++) {
            manager.update(east, north, 0.0, 40.0, 1.0, 1.0 / 60.0);
        }
        int helicopter = manager.activeCount();

        assertTrue(helicopter > onFoot,
                "a helicopter must stream a wider ring than a pedestrian (" + helicopter + " vs " + onFoot + ")");
    }

    @Test
    void movingFarEvictsWhatIsLeftBehind() {
        ChunkManager manager = new ChunkManager(grid());
        manager.setMode(ChunkManager.FocusMode.CAR);
        double east = ORIGIN_E + 5000;
        double north = ORIGIN_N + 5000;
        for (int i = 0; i < 60; i++) {
            manager.update(east, north, 0.0, 0.0, 1.0, 1.0 / 60.0);
        }
        assertTrue(manager.activeCount() > 0, "something is resident before the move");
        // Teleport 25 km north-east: everything within the old ring must drain.
        east += 25000;
        north += 25000;
        for (int i = 0; i < 400; i++) {
            manager.update(east, north, 0.0, 0.0, 1.0, 1.0 / 60.0);
        }
        assertTrue(manager.countInState(ChunkManager.State.EVICTED) > 0,
                "chunks left behind must be evicted, not kept alive forever");
        assertTrue(manager.collisionAt(east, north),
                "the new location has resident collision terrain");
    }

    @Test
    void rebaseNeverChangesChunkIdentity() {
        WorldOrigin origin = new WorldOrigin(ORIGIN_E, ORIGIN_N);
        ChunkManager manager = new ChunkManager(grid());
        double east = ORIGIN_E + 40000;
        double north = ORIGIN_N + 40000;
        for (int i = 0; i < 60; i++) {
            manager.update(east, north, 0.0, 0.0, 1.0, 1.0 / 60.0);
        }
        int known = manager.knownChunks();
        // A rebase shifts every local transform; global keys and residency are untouched.
        var delta = origin.planRebase(east, north);
        assertTrue(delta.x != 0 || delta.z != 0, "four kilometres from the origin must trigger a rebase");
        origin.applyRebase(delta);
        assertEquals(1, origin.rebaseCount());
        assertEquals(known, manager.knownChunks(), "a rebase must not create or destroy chunks");
        assertTrue(manager.collisionAt(east, north), "collision residency survives a rebase");
        assertEquals(east - delta.x, origin.globalEast(origin.localX(east)), 1e-6,
                "local/global conversion round-trips after a rebase");
    }

    @Test
    void terrainSamplingMatchesTheCompiledGrid() {
        TerrainGrid grid = grid();
        ChunkManager manager = new ChunkManager(grid);
        var sampler = manager.sampler();
        // Row index grows northwards; the test grid stores height = row / 3.
        double localZ = 1500.0;
        double localX = 2500.0;
        assertEquals(grid.heightBilinear(localX, localZ), sampler.height(localX, localZ), 1e-9);
        assertEquals(SurfaceMaterial.ID_SHALLOW_WATER, sampler.materialId(localX, localZ),
                "west half of the test lattice is water");
        assertEquals(SurfaceMaterial.ID_COMPACT_SAND, sampler.materialId(COLS * CELL - 500, 1500),
                "east half of the test lattice is land");
        var normal = new ai.arena.qatar.math.Vec3();
        sampler.normal(localX, localZ, normal);
        assertEquals(1.0, normal.length(), 1e-6, "sampled normals are unit length");
        assertTrue(normal.y > 0, "terrain normals point upwards");
    }
}
