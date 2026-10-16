package ai.arena.qatar.game;

import ai.arena.qatar.render.ChunkCulling;
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

    /**
     * How far the renderer will draw when the phone is keeping up, in metres. The terrain is built out to
     * {@code ChunkMeshCache.TERRAIN_RADIUS_M} (6 km); this is the part of it that is drawn.
     */
    public static final double DRAW_RADIUS_M = 6000.0;
    /** The floor: however slow the phone is, this much of the world is drawn. */
    public static final double MIN_DRAW_RADIUS_M = 2800.0;
    /** Frame time above which the draw radius shrinks, in milliseconds: about 48 frames a second. */
    public static final double SLOW_FRAME_MS = 20.8;
    /** Frame time below which it grows again: about 62 frames a second. */
    public static final double FAST_FRAME_MS = 16.1;
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
    /**
     * The frame time the device actually took, in seconds.
     *
     * <p>{@code Gdx.graphics} is absent in a test or a headless tool, so the caller's own delta is the
     * fallback: a measurement that throws is worse than a measurement that is approximate.
     */
    private static double measuredFrameSeconds(float fallbackSeconds) {
        try {
            if (com.badlogic.gdx.Gdx.graphics != null) {
                double measured = com.badlogic.gdx.Gdx.graphics.getDeltaTime();
                if (measured > 0.0) {
                    return measured;
                }
            }
        } catch (Throwable ignored) {
            // No graphics subsystem (a unit test, a tool): use what the caller measured.
        }
        return Math.max(0.0, fallbackSeconds);
    }

    /** The focus the last update was given, in local metres: where the chunk ring is centred. */
    private double cacheFocusEast;
    private double cacheFocusNorth;

    /**
     * How far this frame will draw, in metres. It follows the measured frame time: a phone that is
     * holding 60 frames a second gets the whole 6 km, and one that is struggling loses horizon until it
     * can breathe. The fog follows it exactly (see {@code WorldRenderer.setFogRange}), so what the player
     * sees is weather rather than a shrinking world.
     *
     * <p>This is the honest form of a graphics settings menu: the setting nobody has to find, driven by
     * the number that actually matters on a handset that is getting warm.
     */
    private double drawRadiusM = DRAW_RADIUS_M;
    /** Exponential moving average of the frame time, in milliseconds, over roughly the last second. */
    private double frameMsEma = 16.0;
    /** Chunks drawn and chunks skipped in the last frame, for the diagnostics line. */
    private int drawnChunks;
    private int culledChunks;
    /** Scratch space for the camera's frustum planes: reused every frame, allocated once. */
    private final float[] frustumPlanes = new float[ChunkCulling.PLANE_FLOATS];

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
        cacheFocusEast = localEast;
        cacheFocusNorth = localNorth;
        elapsed += deltaSeconds;
        lastDelta = deltaSeconds;
        updateOffsets();
        double bundleEast = localEast + bundleOffsetEast;
        double bundleNorth = localNorth + bundleOffsetNorth;

        // The draw radius follows the frame time, and the cache follows the draw radius: building chunks
        // the frame will not draw is work for the phone's heat sink.
        //
        // The frame time is the *device's*, not the simulation's: the delta the caller passes has already
        // been clamped to a tenth of a second so that a stalled frame cannot teleport the physics, and a
        // performance decision made from a clamped number cannot see the stall it exists to react to.
        double frameMs = measuredFrameSeconds(deltaSeconds) * 1000.0;
        if (frameMs > 0.0) {
            frameMsEma += (frameMs - frameMsEma) * 0.05;
        }
        if (frameMsEma > SLOW_FRAME_MS) {
            drawRadiusM = Math.max(MIN_DRAW_RADIUS_M, drawRadiusM - 45.0);
        } else if (frameMsEma < FAST_FRAME_MS) {
            drawRadiusM = Math.min(DRAW_RADIUS_M, drawRadiusM + 15.0);
        }

        int budget = elapsed < WARMUP_SECONDS ? WARMUP_BUDGET : STREAM_BUDGET;
        // Half a kilometre of slack over the draw radius: the cache may hold a ring more than it draws,
        // which is what keeps chunks resident while the player moves along a chunk boundary.
        double buildRadius = Math.min(ChunkMeshCache.TERRAIN_RADIUS_M, drawRadiusM + ChunkMesher.CHUNK_M);
        for (ChunkMeshCache.Entry chosen : cache.update(bundleEast, bundleNorth, budget, buildRadius)) {
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
                // Once per chunk, and only a few of those: a phone that cannot build chunks fails on
                // hundreds of them, and hundreds of panel lines turned the screen into a wall of text
                // with the game underneath it. The count is in the status line either way.
                ai.arena.qatar.boot.ScreenLog.GLOBAL.error("chunk " + entry.chunkX + ","
                        + entry.chunkZ + " could not be built (it is left empty, the rest of the "
                        + "world carries on)", t);
            } else if (reportedFailures == MAX_REPORTED_FAILURES) {
                reportedFailures++;
                ai.arena.qatar.boot.ScreenLog.GLOBAL.error("more chunks are failing; the count is in "
                        + "the status line, and the reason is in the crash record");
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

    /**
     * Draws the resident chunks that can be seen, and counts what it skipped.
     *
     * <p>Everything the resident window holds used to be drawn, in all directions, always: at a 6 km
     * radius on a 20:9 screen that is more than twice as much geometry as the camera can show, and every
     * one of those vertices is transformed before the clipper discards it. The count of what was skipped
     * is on the diagnostics line, because a performance change nobody can measure is a superstition.
     */
    public void render(Camera camera) {
        renderer.begin(camera, lastDelta);
        renderer.setFogRange((float) drawRadiusM);
        drawnChunks = 0;
        culledChunks = 0;
        double focusEast = cacheFocusEast;
        double focusNorth = cacheFocusNorth;
        double sphere = ChunkCulling.CHUNK_SPHERE_RADIUS_M;
        // The camera's frustum as six planes, extracted once for the whole pass: libGDX's own Frustum
        // would do the same work, but reading it is a native call away from being untestable (see
        // ChunkCulling). `combined` is the projection-view matrix the camera itself draws with.
        float[] planes = camera == null ? null : frustumPlanes;
        if (planes != null) {
            ChunkCulling.planesOf(camera.combined, planes);
        }
        for (ChunkMeshCache.Entry entry : cache.resident()) {
            if (!entry.uploaded) {
                continue;                     // failed, or not yet built: an empty chunk, not a hole
            }
            double offsetEast = ChunkMesher.chunkOrigin(entry.chunkX) - bundleOffsetEast;
            double offsetNorth = ChunkMesher.chunkOrigin(entry.chunkZ) - bundleOffsetNorth;
            // The sphere test is done in local metres, which is where the camera is.
            double centreEast = offsetEast + ChunkMesher.CHUNK_M * 0.5;
            double centreNorth = offsetNorth + ChunkMesher.CHUNK_M * 0.5;
            if (!ChunkCulling.withinDrawRadius(centreEast, centreNorth, focusEast, focusNorth,
                    drawRadiusM)
                    || !ChunkCulling.sphereInPlanes(planes, centreEast, centreNorth, sphere)) {
                culledChunks++;
                continue;
            }
            renderer.draw(entry.key(), offsetEast, offsetNorth);
            drawnChunks++;
        }
        // No state hand-over here on purpose: the player's body may still be drawn with this pass's
        // state, and WorldRenderer.drawActor ends the 3D frame once the body is on the screen. The HUD
        // does not depend on either of them: HudRenderer.begin() sets its own state (see GlState2D).
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
    public void drawActor(double localEast, double localHeight, double localNorth,
                          double headingDegrees, double spinDegrees,
                          ai.arena.qatar.render.BodyMesher.Body kind) {
        // The height is the body's contact height -- feet, tyres, waterline, wheels -- which is where
        // the body meshes are modelled from. Passing zero here put the player at sea level: under the
        // ground they were walking on, and out of sight.
        renderer.drawActor(ai.arena.qatar.render.BodyMesher.transform(actorScratch, localEast,
                        localHeight, localNorth, headingDegrees),
                ai.arena.qatar.render.BodyMesher.parts(kind), localEast, localHeight, localNorth,
                headingDegrees, spinDegrees);
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

    /** Chunks whose geometry was submitted to the GPU in the last frame. */
    public int drawnChunks() {
        return drawnChunks;
    }

    /** Chunks skipped by the distance and frustum tests in the last frame. */
    public int culledChunks() {
        return culledChunks;
    }

    /** How far the renderer is drawing right now, in metres. */
    public double drawRadiusM() {
        return drawRadiusM;
    }

    /** The measured frame time, averaged, in milliseconds. */
    public double frameMs() {
        return frameMsEma;
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
    /**
     * The streaming profile, on one line: what is resident, what is drawn, what was skipped, what it
     * cost, and how far the fog is. This is the number a phone can report and this laptop cannot -- the
     * tenth install asked for the phone to stop getting hot, and the only way to know whether it did is
     * to put the measurement where the player can read it out.
     */
    public String describe() {
        return "chunks " + residentChunks() + " resident / " + pendingChunks() + " pending, "
                + drawnChunks + " drawn + " + culledChunks + " culled, "
                + drawCalls() + " draws, " + (drawnTriangles() / 1000) + "k tris, "
                + (residentVertices() / 1000) + "k verts of " + (geometryBudgetVertices() / 1000)
                + "k, " + String.format(java.util.Locale.ROOT, "%.1f ms, fog %.1f km",
                        frameMsEma, drawRadiusM / 1000.0)
                + (failedChunks() > 0 ? ", " + failedChunks() + " FAILED" : "");
    }

    @Override
    public void dispose() {
        renderer.dispose();
        cache.clear();
        uploaded.clear();
    }
}
