package ai.arena.qatar.game;

import ai.arena.qatar.render.ChunkMeshCache;
import ai.arena.qatar.render.ChunkMesher;
import ai.arena.qatar.render.ChunkMesher.ChunkMesh;
import ai.arena.qatar.render.WorldRenderer;
import ai.arena.qatar.world.WorldData;
import ai.arena.qatar.world.WorldOrigin;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.LongSet;

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
 */
public final class GameWorldRenderer implements Disposable {

    /** Chunks built per frame during normal play. */
    public static final int STREAM_BUDGET = 4;
    /** Chunks built per frame during the first seconds, while the world is still filling in. */
    public static final int WARMUP_BUDGET = 24;
    /** How long the warm-up budget is used, in seconds. */
    public static final float WARMUP_SECONDS = 3.0f;
    private final WorldData world;
    private final WorldOrigin origin;
    private final ChunkMesher mesher;
    private final ChunkMeshCache cache;
    private final WorldRenderer renderer;

    private final LongSet uploaded = new LongSet();
    private final LongSet liveKeys = new LongSet();

    private float elapsed;
    private float lastDelta = 0.016f;
    private int uploadedChunks;
    private int releasedChunks;
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
        for (ChunkMeshCache.Entry entry : cache.update(bundleEast, bundleNorth, budget)) {
            double detail = ChunkMeshCache.detailAt(bundleEast, bundleNorth, entry.centreEast,
                    entry.centreNorth);
            ChunkMesh mesh = mesher.mesh(entry.chunkX, entry.chunkZ, detail);
            cache.put(entry.chunkX, entry.chunkZ, mesh);
            renderer.upload(entry.key(), mesh);
            uploaded.add(entry.key());
            uploadedChunks++;
        }
        releaseDepartedChunks();
    }

    /** Releases GPU meshes for chunks the cache has dropped. */
    private void releaseDepartedChunks() {
        if (uploaded.size == 0) {
            return;
        }
        liveKeys.clear();
        for (ChunkMeshCache.Entry entry : cache.resident()) {
            liveKeys.add(entry.key());
        }
        LongSet.LongSetIterator iterator = uploaded.iterator();
        long[] gone = new long[uploaded.size];
        int count = 0;
        while (iterator.hasNext) {
            long key = iterator.next();
            if (!liveKeys.contains(key)) {
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
            if (entry.mesh == null) {
                continue;
            }
            double offsetEast = ChunkMesher.chunkOrigin(entry.chunkX) - bundleOffsetEast;
            double offsetNorth = ChunkMesher.chunkOrigin(entry.chunkZ) - bundleOffsetNorth;
            renderer.draw(entry.key(), offsetEast, offsetNorth);
        }
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

    /** One-line streaming status for the debug overlay. */
    public String describe() {
        return "chunks " + residentChunks() + " resident / " + pendingChunks() + " pending, "
                + drawCalls() + " draws, " + (drawnTriangles() / 1000) + "k tris";
    }

    @Override
    public void dispose() {
        renderer.dispose();
        cache.clear();
        uploaded.clear();
    }
}
