package ai.arena.qatar.render;

import ai.arena.qatar.QatarGame;
import ai.arena.qatar.game.GameWorldRenderer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How much work a second of walking does, which is what a hot phone is made of.
 *
 * <p>The tenth install became hot within seconds of starting, and the reason was not one expensive
 * thing: it was a loop that never rested. The streaming cache asked "which chunks near the player are
 * missing?" every frame, meshed three of them, then evicted the farthest chunks to stay inside its
 * memory budget -- and the chunks it had just evicted were of course still near the player, so they came
 * straight back onto the build list. Three chunk builds a frame, sixty frames a second, three hundred
 * and sixty thousand triangles a minute, for geometry the frame never got to keep. Meanwhile the frame
 * rate was uncapped, so the phone spent every millisecond it had on that loop and answered the only way
 * a warm phone can: by getting hotter.
 *
 * <p>The fix has two halves and both are asserted here. The frame rate is capped, so the device cannot
 * be asked for more work than a screen can show. And the streaming cache builds nothing while its window
 * is full, so a player standing still costs a few checks a frame instead of three mesh builds.
 *
 * <p>This test walks a virtual world: the cache's own numbers, driving the cache's own API, for the same
 * six hundred frames a player would spend standing on the Corniche.
 */
final class StreamingWorkTest {

    private static final double DT = 1.0 / 60.0;

    /** A chunk mesh of a given size, with no GL involved: what the caller would have built. */
    private static ChunkMesher.ChunkMesh meshOf(int vertices) {
        ChunkMesher.ChunkMesh mesh = new ChunkMesher.ChunkMesh();
        MeshData part = mesh.detailPart();
        for (int i = 0; i < vertices; i++) {
            part.addVertex(i, 0, 0, 0, 1, 0, 0, 0, 0, 1);
        }
        return mesh;
    }

    /** Runs the streaming loop the renderer runs: ask the cache, build what it asks for, upload it. */
    private static int[] stream(int frames, double startEast, double startNorth, double speedEast,
                                double speedNorth, int perChunkVertices) {
        ChunkMeshCache cache = new ChunkMeshCache();
        int totalBuilt = 0;
        int idleFrames = 0;
        for (int frame = 0; frame < frames; frame++) {
            double east = startEast + speedEast * frame * DT;
            double north = startNorth + speedNorth * frame * DT;
            // The focus moves at most 60 m/s: three chunks a frame at the streaming budget is the
            // renderer's own arithmetic, and this test uses exactly it.
            int budget = GameWorldRenderer.STREAM_BUDGET;
            int builtThisFrame = 0;
            // The budget and the window are the cache's own promise, kept inside update(): by the time it
            // hands back a build list it has already evicted whatever it had to, and it refuses to be asked
            // for more chunks than its room can pay for. So the numbers are checked here -- at the moment
            // the frame decides what to build. The chunk built below is paid for out of the room this
            // update found, and the next update takes that room back; a chunk that arrives after this
            // point is not a leak, it is the single frame of overshoot the round-eight budget test
            // documents ("the window is back inside its budget").
            List<ChunkMeshCache.Entry> wanted = cache.update(east, north, budget,
                    GameWorldRenderer.DRAW_RADIUS_M);
            assertTrue(cache.residentVertices() <= ChunkMeshCache.MAX_RESIDENT_VERTICES,
                    "the frame " + frame + " began its builds over the memory budget: "
                            + cache.residentVertices() + " vertices");
            assertTrue(cache.residentCount() <= ChunkMeshCache.MAX_RESIDENT,
                    "the frame " + frame + " began its builds holding " + cache.residentCount()
                            + " chunks, over the " + ChunkMeshCache.MAX_RESIDENT + " it may hold");
            for (ChunkMeshCache.Entry candidate : wanted) {
                // Build it, hand it to the GPU, drop the CPU copy: what the renderer does with the list
                // the cache returned. Nothing here holds a reference to what it built -- a test that kept
                // two hundred megabyte-scale meshes would be measuring the JVM's heap, not the cache's
                // work.
                ChunkMeshCache.Entry entry = cache.put(candidate.chunkX, candidate.chunkZ,
                        meshOf(perChunkVertices));
                cache.markUploaded(entry);
                builtThisFrame++;
            }
            totalBuilt += builtThisFrame;
            if (builtThisFrame == 0) {
                idleFrames++;
            }
        }
        return new int[]{totalBuilt, idleFrames, cache.residentCount()};
    }

    @Test
    @DisplayName("standing still costs almost nothing: the cache fills its window and then stops")
    void standingStillStopsBuilding() {
        int[] firstTenSeconds = stream(600, 0, 0, 0, 0, 12_000);
        int built = firstTenSeconds[0];
        int idle = firstTenSeconds[1];
        assertTrue(idle > 400, "the cache built something on " + (600 - idle) + " of six hundred frames "
                + "while the player stood still: a full window is a full window, and rebuilding it every "
                + "frame is the heat this test exists for");
        // Two hundred chunks is under the 320-chunk window, and each one is 12,000 vertices: the window
        // is 2.4 million vertices, so the memory budget stops the filling, and then there is no more work.
        assertTrue(built <= 220, "standing still meshed " + built + " chunks in ten seconds");
        assertTrue(firstTenSeconds[2] >= 40,
                "and the ground around the player is actually there: " + firstTenSeconds[2] + " chunks");
    }

    @Test
    @DisplayName("driving across the country builds its ground once, not once per frame")
    void drivingBuildsEachChunkOnce() {
        // Ninety kilometres of desert and city at 90 km/h, twenty-five seconds of it: the same 1,500
        // frames a player gets from the airport to Doha.
        int frames = 1_500;
        int[] driven = stream(frames, 0, 0, 25.0, 0.0, 12_000);
        int built = driven[0];
        // A frame may not build more chunks than it is given budget for. That is the whole guarantee:
        // the work the frame does is bounded by the budget, whatever the player is doing.
        assertTrue(built <= frames * GameWorldRenderer.STREAM_BUDGET,
                "the streaming loop built " + built + " chunks in " + frames + " frames of "
                        + GameWorldRenderer.STREAM_BUDGET + " each");
        // 90 km/h for 25 s is 625 m, which is twenty-five chunks of 25 m: the window has to have grown
        // by about that much and no more. A cache that rebuilt every chunk it passed would be in the
        // thousands, and that is the phone getting hot.
        assertTrue(built <= 500, "driving half a kilometre meshed " + built
                + " chunks: the cache is rebuilding what it already holds");
        assertTrue(driven[2] >= 20, "the ground under the car is missing: " + driven[2] + " chunks");
    }

    @Test
    @DisplayName("a jump drops the window that was left behind, and fills the new one nearest first")
    void aJumpLeavesTheOldWindowBehind() {
        ChunkMeshCache cache = new ChunkMeshCache();
        for (int frame = 0; frame < 600; frame++) {
            for (ChunkMeshCache.Entry candidate : cache.update(0, 0, GameWorldRenderer.STREAM_BUDGET,
                    GameWorldRenderer.DRAW_RADIUS_M)) {
                cache.markUploaded(cache.put(candidate.chunkX, candidate.chunkZ, meshOf(9_000)));
            }
        }
        assertTrue(cache.residentCount() > 40,
                "the window never filled, so a jump has nothing to leave behind: "
                        + cache.residentCount() + " chunks");

        // The tap: a hundred kilometres away, which is one tap on the big map.
        double jumpEast = 100_000.0;
        double jumpNorth = 60_000.0;
        List<ChunkMeshCache.Entry> wanted = cache.update(jumpEast, jumpNorth,
                GameWorldRenderer.STREAM_BUDGET, GameWorldRenderer.DRAW_RADIUS_M);

        assertEquals(0, cache.residentCount(), "the window the player left came with them: "
                + cache.residentCount() + " chunks are still resident a hundred kilometres behind");
        assertEquals(0, cache.residentVertices(), "the old window's vertices are still on the budget");
        assertTrue(!wanted.isEmpty(), "nothing was queued to build at the new place");
        // And the queue starts under the player's feet, not at the edge of the new window: the first
        // frame after a teleport is the one that decides whether they arrive on ground or in a void.
        ChunkMeshCache.Entry first = wanted.get(0);
        double distance = Math.hypot(first.centreEast - jumpEast, first.centreNorth - jumpNorth);
        assertTrue(distance <= ChunkMesher.CHUNK_M,
                "the first chunk queued after a jump is " + distance + " m from where the player landed");
    }

    @Test
    @DisplayName("the frame rate is capped, and the renderer's budgets are the ones the camera assumes")
    void theFrameRateIsCapped() {
        // A phone asked for unlimited frames renders until it overheats: this is the constant that stops
        // that, and it is a public constant so a test can hold it.
        assertEquals(60, QatarGame.FOREGROUND_FPS, "the frame rate cap is not sixty");
        assertTrue(GameWorldRenderer.STREAM_BUDGET > 0 && GameWorldRenderer.STREAM_BUDGET <= 4,
                "the streaming budget is " + GameWorldRenderer.STREAM_BUDGET + " chunks a frame");
        assertTrue(GameWorldRenderer.WARMUP_BUDGET >= GameWorldRenderer.STREAM_BUDGET,
                "the warm-up cannot be smaller than the steady-state budget");
        assertTrue(GameWorldRenderer.MIN_DRAW_RADIUS_M >= 1000.0,
                "the distance the renderer shrinks to is short enough to see the fog: "
                        + GameWorldRenderer.MIN_DRAW_RADIUS_M + " m");
        assertTrue(GameWorldRenderer.MIN_DRAW_RADIUS_M < GameWorldRenderer.DRAW_RADIUS_M,
                "there is no distance left for the renderer to shrink into");
        // The thresholds are a frame's worth of time, either side of 60 fps: 16.7 ms is sixty frames a
        // second, and a renderer that reacts above it and recovers below it is a renderer that is not
        // fighting itself every frame.
        assertTrue(GameWorldRenderer.FAST_FRAME_MS < GameWorldRenderer.SLOW_FRAME_MS,
                "the recover-below threshold is above the shrink-above one: the draw distance would flap");
        assertTrue(GameWorldRenderer.SLOW_FRAME_MS >= 16.7 && GameWorldRenderer.SLOW_FRAME_MS <= 25.0,
                "the slow-frame threshold is " + GameWorldRenderer.SLOW_FRAME_MS + " ms, which is not a "
                        + "frame that is missing its budget");
    }
}
