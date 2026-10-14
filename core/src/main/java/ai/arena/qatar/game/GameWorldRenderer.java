package ai.arena.qatar.game;

import ai.arena.qatar.render.ChunkMeshCache;
import ai.arena.qatar.render.ChunkMesher;
import ai.arena.qatar.render.ChunkMesher.ChunkMesh;
import ai.arena.qatar.render.WorldRenderer;
import ai.arena.qatar.world.WorldData;
import ai.arena.qatar.world.WorldOrigin;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.LongMap;

/**
 * Draws the whole country by streaming chunks around the player.
 *
 * <p>Three coordinate systems meet in this class and nowhere else:
 * <ul>
 *   <li><b>Global</b> EPSG:32639 metres, in double precision -- what the data is authored in.</li>
 *   <li><b>Bundle</b> metres, relative to the manifest origin -- what the compiled geometry and the
 *       chunk grid are expressed in.</li>
 *   <li><b>Local</b> metres around the floating origin -- what the camera, the simulation and the GPU
 *       use, so coordinates stay small and precise no matter how far the player travels.</li>
 * </ul>
 *
 * <p>The GPU sees chunk vertices in bundle space plus one translation per chunk, so no mesh is ever
 * rebuilt when the floating origin rebases: only the translation changes.
 *
 * <p>The update is deliberately budgeted. Building a 500 m chunk costs a few milliseconds, so streaming
 * is spread over frames, nearest chunk first: the ground under the player appears immediately and the
 * horizon fills in over the next second or two, which is the difference between a stutter and a game.
 *
 * <p>And it is <b>isolated per chunk</b>. This is the code that killed the third install: one chunk of
 * dense city geometry threw while being built, the exception escaped through the render loop and killed
 * the GL thread, and Android killed the process with it -- no panel, no message. A chunk that cannot be
 * built is now a hole in the world with a sentence in the diagnostics panel, and the other four hundred
 * and fifty chunks are built anyway.
 */
public final class GameWorldRenderer implements Disposable {

    /** Chunks built per frame during normal play. */
    public static final int STREAM_BUDGET = 3;
    /**
     * Chunks built per frame during the first seconds, while the world is still filling in.
     *
     * <p>This used to be 24, which built a fifth of the resident window before the first frame was
     * finished: a quarter of a gigabyte of geometry allocated in three seconds, on a device that was
     * also holding the compiled country. Fewer per frame costs a second or two of filling in and keeps
     * the allocator, and the phone, out of trouble.
     */
    public static final int WARMUP_BUDGET = 6;
    /** How long the warm-up budget is used, in seconds. */
    public static final float WARMUP_SECONDS = 4.0f;
    /** How many chunk failures are reported before the log is left alone (the holes still stay). */
    private static final int MAX_REPORTED_FAILURES = 5;
    private final WorldData world;
    private final WorldOrigin origin;
    private final ChunkMesher mesher;
    private final ChunkMeshCache cache;
    private final WorldRenderer renderer;

    private final com.badlogic.gdx.math.Matrix4 actorScratch = new com.badlogic.gdx.math.Matrix4();
    private final LongMap<Boolean> uploaded = new LongMap<>();
    private final LongMap<Boolean> liveKeys = new LongMap<>();

    private float elapsed;
    private float lastDelta = 0.016f;
    private int uploadedChunks;
    private int releasedChunks;
    private int failedChunks;
    private int reportedFailures;
    private double bundleOffsetEast;
    private double bundleOffsetNorth;

    public GameWorldRenderer(WorldData world, WorldOrigin origin, WorldRenderer renderer) {
        this.world = world;
        this.origin = origin;
        this.renderer = renderer;
        this.mesher = new ChunkMesher(world);
        this.cache = new ChunkMeshCache();
        updateOffsets();
    }

    public void init() {
        renderer.init();
    }

    /**
     * False when the world's shaders did not compile. The caller must report it rather than carry on:
     * a renderer that draws nothing and says nothing is a blank screen with no explanation.
     */
    public boolean isReady() {
        return renderer.isReady();
    }

    /** Recomputes bundle<->local translation; call after the floating origin rebases. */
    public void updateOffsets() {
        bundleOffsetEast = origin.originEast() - world.manifest.originEast;
        bundleOffsetNorth = origin.originNorth() - world.manifest.originNorth;
    }

    /** Local horizontal position of the camera, in metres, for streaming decisions. */
    public void update(double localEast, double localNorth, float deltaSeconds) {
        elapsed += deltaSeconds;
        lastDelta = deltaSeconds;
        updateOffsets();
        double bundleEast = localEast + bundleOffsetEast;
        double bundleNorth = localNorth + bundleOffsetNorth;

        int budget = elapsed < WARMUP_SECONDS ? WARMUP_BUDGET : STREAM_BUDGET;
        for (ChunkMeshCache.Entry chosen : cache.update(bundleEast, bundleNorth, budget)) {
            build(chosen, bundleEast, bundleNorth);
        }
        releaseDepartedChunks();
    }

    /**
     * Builds and uploads one chunk, and refuses to let it take the game with it.
     *
     * <p>Everything is inside the guard: meshing (which reads the compiled country and can meet a ring,
     * a way or a wall it cannot use), uploading, and the bookkeeping. A failure is reported once per
     * chunk, counted, and the chunk is remembered as failed so the next frame does not try it again --
     * and, crucially, so the frames after that still draw the other four hundred chunks.
     */
    private void build(ChunkMeshCache.Entry entry, double bundleEast, double bundleNorth) {
        try {
            double detail = ChunkMeshCache.detailAt(bundleEast, bundleNorth, entry.centreEast,
                    entry.centreNorth);
            ChunkMesh mesh = mesher.mesh(entry.chunkX, entry.chunkZ, detail);
            ChunkMeshCache.Entry stored = cache.put(entry.chunkX, entry.chunkZ, mesh);
            renderer.upload(entry.key(), mesh);
            // The GPU has its own copy now: drop the CPU arrays and record the count for the budget.
            cache.markUploaded(stored);
            uploaded.put(entry.key(), Boolean.TRUE);
            uploadedChunks++;
            if (mesh.truncated && reportedFailures < MAX_REPORTED_FAILURES) {
                reportedFailures++;
                ai.arena.qatar.boot.ScreenLog.GLOBAL.warn("chunk " + entry.chunkX + "," + entry.chunkZ
                        + " hit the vertex budget: its detail is " + mesh.describe());
            }
        } catch (Throwable t) {
            failedChunks++;
            cache.putFailed(entry.chunkX, entry.chunkZ);
            if (reportedFailures < MAX_REPORTED_FAILURES) {
                reportedFailures++;
                ai.arena.qatar.boot.ScreenLog.GLOBAL.error("chunk " + entry.chunkX + ","
                        + entry.chunkZ + " could not be built (it is left empty, the rest of the "
                        + "world carries on)", t);
            }
        }
    }

    /** Releases GPU meshes for chunks the cache has dropped. */
    private void releaseDepartedChunks() {
        if (uploaded.size == 0) {
            return;
        }
        liveKeys.clear();
        for (ChunkMeshCache.Entry entry : cache.resident()) {
            liveKeys.put(entry.key(), Boolean.TRUE);
        }
        // Collect first, release second: the map must not be modified while it is being walked.
        LongMap.Keys iterator = uploaded.keys();
        long[] gone = new long[uploaded.size];
        int count = 0;
        while (iterator.hasNext) {
            long key = iterator.next();
            if (!liveKeys.containsKey(key)) {
                gone[count++] = key;
            }
        }
        for (int i = 0; i < count; i++) {
            uploaded.remove(gone[i]);
            renderer.release(gone[i]);
            releasedChunks++;
        }
    }

    /** Draws every resident chunk. */
    public void render(Camera camera) {
        renderer.begin(camera, lastDelta);
        for (ChunkMeshCache.Entry entry : cache.resident()) {
            if (!entry.uploaded) {
                continue;                     // failed, or not yet built: an empty chunk, not a hole
            }
            double offsetEast = ChunkMesher.chunkOrigin(entry.chunkX) - bundleOffsetEast;
            double offsetNorth = ChunkMesher.chunkOrigin(entry.chunkZ) - bundleOffsetNorth;
            renderer.draw(entry.key(), offsetEast, offsetNorth);
        }
    }

    /** Uploads the player's body, and the parts of it that move, for the locomotion being played. */
    public void setActor(ai.arena.qatar.render.BodyMesher.Body kind) {
        renderer.setActor(ai.arena.qatar.render.BodyMesher.mesh(kind),
                ai.arena.qatar.render.BodyMesher.parts(kind));
    }

    /**
     * Draws the player's body at its local position and heading. Local, not bundle: the transform is
     * one translation near the floating origin, which is the whole point of having one.
     */
    public void drawActor(double localEast, double localNorth, double headingDegrees,
                          double spinDegrees, ai.arena.qatar.render.BodyMesher.Body kind) {
        renderer.drawActor(ai.arena.qatar.render.BodyMesher.transform(actorScratch, localEast,
                        localNorth, headingDegrees),
                ai.arena.qatar.render.BodyMesher.parts(kind), localEast, localNorth, headingDegrees,
                spinDegrees);
    }

    public ChunkMeshCache cache() {
        return cache;
    }

    public int uploadedChunks() {
        return uploadedChunks;
    }

    public int releasedChunks() {
        return releasedChunks;
    }

    public int residentChunks() {
        return cache.residentCount();
    }

    public int pendingChunks() {
        return cache.pendingCount();
    }

    public int drawCalls() {
        return renderer.drawCalls();
    }

    public int drawnTriangles() {
        return renderer.drawnTriangles();
    }

    /** Chunks whose build failed: reported in the panel, because a hole in the world is a bug. */
    public int failedChunks() {
        return failedChunks;
    }

    /** Vertices resident on the GPU, and the memory budget the chunk cache keeps them inside. */
    public int residentVertices() {
        return renderer.residentVertices();
    }

    public int geometryBudgetVertices() {
        return ChunkMeshCache.MAX_RESIDENT_VERTICES;
    }

    /** One-line streaming status for the debug overlay and the launch trace. */
    public String describe() {
        return "chunks " + residentChunks() + " resident / " + pendingChunks() + " pending, "
                + drawCalls() + " draws, " + (drawnTriangles() / 1000) + "k tris, "
                + (residentVertices() / 1000) + "k verts of " + (geometryBudgetVertices() / 1000)
                + "k" + (failedChunks() > 0 ? ", " + failedChunks() + " FAILED" : "");
    }

    @Override
    public void dispose() {
        renderer.dispose();
        cache.clear();
        uploaded.clear();
    }
}
