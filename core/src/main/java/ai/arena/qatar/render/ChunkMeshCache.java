package ai.arena.qatar.render;

import ai.arena.qatar.render.ChunkMesher.ChunkMesh;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which chunks of the country are built, which are thrown away, and in which order -- the
 * policy half of streaming, with no GL calls, so the behaviour can be tested on a machine with no GPU.
 *
 * <p>Three rules, in order of importance:
 * <ol>
 *   <li><b>Never leave a hole under the player.</b> The chunk containing the focus is always first in
 *       line to be built.</li>
 *   <li><b>Build nearest first, on a budget.</b> A frame that tries to build twenty chunks stalls; two
 *       per frame keeps the frame rate honest and the world fills in within a few seconds of moving.</li>
 *   <li><b>Evict lazily.</b> A chunk is only dropped once it is well outside the radius it was built
 *       for, so walking along a boundary does not thrash: build and evict thresholds differ by design.</li>
 * </ol>
 *
 * <p>There is a fourth rule, and it is the one a phone cares about: <b>the window is bounded by memory,
 * not only by distance</b>. The radius alone allows about 450 chunks, and the third install ran out of
 * heap and was killed by Android -- no exception, no message, the app simply closed. So the window now
 * carries a vertex budget ({@link #MAX_RESIDENT_VERTICES}) and evicts the oldest chunks while it is
 * over budget, and each entry records what it holds so the budget can be measured rather than hoped at.
 */
public final class ChunkMeshCache {

    /** Detail is 1 at the focus and falls to 0 at the detail radius. */
    public static final double DETAIL_RADIUS_M = 2500.0;
    /** Terrain is built further out than buildings and roads, so the horizon does not pop. */
    public static final double TERRAIN_RADIUS_M = 6000.0;
    /** Hysteresis: a chunk stays resident until it is this far outside its build radius. */
    public static final double EVICT_MARGIN_M = 1000.0;
    /** Hard cap on resident chunks; beyond this the least recently used go, whatever their distance. */
    public static final int MAX_RESIDENT = 320;
    /**
     * Vertices the whole resident window may hold.
     *
     * <p>Each vertex is eleven floats in the index buffer's worth of geometry: 44 bytes on the CPU
     * while it is being built and 44 bytes on the GPU afterwards, so a million vertices is about 88 MB
     * with both copies alive at once. That is as much geometry as this game should ask of a phone that
     * is also holding 60 MB of compiled country, a map raster and its own heap, and it is a fifth of
     * what the distance ring alone used to allow.
     */
    public static final int MAX_RESIDENT_VERTICES = 1_000_000;
    /** However heavy they are, this many chunks stay: the player needs a world around them. */
    private static final int MIN_RESIDENT = 48;

    /** One resident chunk: the mesh plus the bookkeeping the cache needs. */
    public static final class Entry {
        public final int chunkX;
        public final int chunkZ;
        public final double centreEast;
        public final double centreNorth;
        /** The CPU copy, null once the GPU has it (or once it failed). */
        public ChunkMesh mesh;
        /** True once the chunk's geometry is on the GPU and can be drawn. */
        public boolean uploaded;
        /** True when meshing this chunk threw: it stays empty and is not retried every frame. */
        public boolean failed;
        /** Vertices this chunk holds, recorded when it was built: survives the CPU copy being dropped. */
        public int vertices;
        public long lastUsedFrame;

        Entry(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.centreEast = ChunkMesher.chunkOrigin(chunkX) + ChunkMesher.CHUNK_M * 0.5;
            this.centreNorth = ChunkMesher.chunkOrigin(chunkZ) + ChunkMesher.CHUNK_M * 0.5;
        }

        public long key() {
            return ChunkMeshCache.key(chunkX, chunkZ);
        }
    }

    private final Map<Long, Entry> resident = new LinkedHashMap<>();
    private final List<Entry> pending = new ArrayList<>();
    private long frame;
    private int builtChunks;
    private int evictedChunks;
    private int residentVertices;
    private int budgetEvictions;

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Updates the cache for a new focus position.
     *
     * @return the chunks that must be built this frame (nearest first, at most {@code budget} of them)
     */
    public List<Entry> update(double focusEast, double focusNorth, int budget) {
        frame++;
        resident.values().forEach(entry -> {
            if (entry.uploaded || entry.mesh != null) {
                entry.lastUsedFrame = frame;
            }
        });

        // Which chunks should exist, nearest first? A square ring is enough and cheap to enumerate.
        double radius = TERRAIN_RADIUS_M;
        int minX = ChunkMesher.chunkIndex(focusEast - radius);
        int maxX = ChunkMesher.chunkIndex(focusEast + radius);
        int minZ = ChunkMesher.chunkIndex(focusNorth - radius);
        int maxZ = ChunkMesher.chunkIndex(focusNorth + radius);

        pending.clear();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double centreEast = ChunkMesher.chunkOrigin(x) + ChunkMesher.CHUNK_M * 0.5;
                double centreNorth = ChunkMesher.chunkOrigin(z) + ChunkMesher.CHUNK_M * 0.5;
                double dx = centreEast - focusEast;
                double dz = centreNorth - focusNorth;
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > TERRAIN_RADIUS_M + ChunkMesher.CHUNK_M) {
                    continue;
                }
                if (resident.containsKey(key(x, z))) {
                    continue;
                }
                Entry entry = new Entry(x, z);
                pending.add(entry);
            }
        }
        // Nearest first: the ground under the player exists before the horizon does.
        pending.sort((a, b) -> Double.compare(distance2(a, focusEast, focusNorth),
                distance2(b, focusEast, focusNorth)));

        // Evict what is now far outside the resident window, oldest use first.
        Iterator<Map.Entry<Long, Entry>> iterator = resident.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            double distance = Math.sqrt(distance2(entry, focusEast, focusNorth));
            if (distance > TERRAIN_RADIUS_M + EVICT_MARGIN_M) {
                drop(iterator, entry);
                evictedChunks++;
            }
        }
        while (resident.size() > MAX_RESIDENT) {
            Iterator<Map.Entry<Long, Entry>> oldest = resident.entrySet().iterator();
            if (!oldest.hasNext()) {
                break;
            }
            drop(oldest, oldest.next().getValue());
            evictedChunks++;
        }
        // And the rule that keeps a phone alive: the window is a memory budget, not just a radius.
        // A dense city chunk holds tens of thousands of vertices, so the distance ring alone would
        // let a driver through Doha hold hundreds of megabytes of geometry.
        //
        // The victim is the farthest chunk, not the oldest. Every resident chunk is marked as used
        // every frame, so "oldest" here means "inserted first", which is wherever the player came from
        // rather than what the player cannot see; and a chunk evicted from under the camera is a hole
        // in the world that rebuilding later cannot undo. The chunk over the horizon is the one whose
        // whole cost can be recovered for no visible loss.
        while (residentVertices > MAX_RESIDENT_VERTICES && resident.size() > MIN_RESIDENT) {
            if (!evictFarthest(focusEast, focusNorth)) {
                break;
            }
            evictedChunks++;
            budgetEvictions++;
        }

        int limit = Math.max(0, budget);
        List<Entry> chosen = new ArrayList<>(Math.min(limit, pending.size()));
        for (int i = 0; i < pending.size() && chosen.size() < limit; i++) {
            chosen.add(pending.get(i));
        }
        return chosen;
    }

    /** Installs a freshly built chunk (called on the GL thread right after building). */
    public Entry put(int chunkX, int chunkZ, ChunkMesh mesh) {
        Entry entry = new Entry(chunkX, chunkZ);
        entry.mesh = mesh;
        entry.vertices = mesh.vertexCount();
        entry.lastUsedFrame = frame;
        resident.put(entry.key(), entry);
        residentVertices += entry.vertices;
        builtChunks++;
        return entry;
    }

    /**
     * Remembers a chunk whose build failed, so it is not chosen again next frame and does not spam the
     * error log at sixty frames a second. It stays empty and invisible: a hole in the world is a bug to
     * fix, but a hole that is retried every frame is a stall.
     */
    public Entry putFailed(int chunkX, int chunkZ) {
        // A chunk can fail after it was already recorded as built -- uploading happens after meshing --
        // so whatever was registered under this key is taken back out first. Replacing it without
        // subtracting its vertices would leave the budget counting geometry that no longer exists, and
        // the budget is the thing keeping the window small enough for a phone.
        Entry previous = resident.remove(key(chunkX, chunkZ));
        if (previous != null) {
            residentVertices -= previous.vertices;
            if (residentVertices < 0) {
                residentVertices = 0;
            }
        }
        Entry entry = new Entry(chunkX, chunkZ);
        entry.failed = true;
        entry.mesh = null;
        entry.lastUsedFrame = frame;
        resident.put(entry.key(), entry);
        return entry;
    }

    /** Records that a chunk's geometry is on the GPU and frees the CPU copy of it. */
    public void markUploaded(Entry entry) {
        entry.uploaded = true;
        if (entry.mesh != null) {
            entry.mesh.releaseArrays();
        }
    }

    /** Vertices held by the resident window: the headroom the next chunk will be built into. */
    public int residentVertices() {
        return residentVertices;
    }

    /** How many chunks the memory budget has taken back, for the diagnostics panel. */
    public int budgetEvictions() {
        return budgetEvictions;
    }

    /**
     * Removes the entry the iterator has just returned, keeping the vertex total honest.
     *
     * <p>The iterator is passed in rather than re-created because removing from a {@code LinkedHashMap}
     * while walking it is only safe through the iterator that walked it -- and the entry is passed in
     * because the caller has already fetched it: calling {@code next()} twice would skip a chunk and
     * then throw at the end of the map.
     */
    /** Removes the resident chunk furthest from the focus. Returns false when the window is empty. */
    private boolean evictFarthest(double focusEast, double focusNorth) {
        Long victim = null;
        double worst = -1;
        for (Map.Entry<Long, Entry> entry : resident.entrySet()) {
            double distance2 = distance2(entry.getValue(), focusEast, focusNorth);
            if (distance2 > worst) {
                worst = distance2;
                victim = entry.getKey();
            }
        }
        if (victim == null) {
            return false;
        }
        Entry entry = resident.remove(victim);
        residentVertices -= entry.vertices;
        if (residentVertices < 0) {
            residentVertices = 0;
        }
        return true;
    }

    private void drop(Iterator<Map.Entry<Long, Entry>> iterator, Entry entry) {
        residentVertices -= entry.vertices;
        if (residentVertices < 0) {
            residentVertices = 0;
        }
        iterator.remove();
    }

    public Entry get(int chunkX, int chunkZ) {
        return resident.get(key(chunkX, chunkZ));
    }

    /** Detail level (1 near, 0 at the detail radius) for a chunk, used to decide what to draw. */
    public static double detailAt(double focusEast, double focusNorth, double centreEast,
                                  double centreNorth) {
        double dx = centreEast - focusEast;
        double dz = centreNorth - focusNorth;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 400.0) {
            return 1.0;
        }
        if (distance >= DETAIL_RADIUS_M) {
            return 0.0;
        }
        double t = (distance - 400.0) / (DETAIL_RADIUS_M - 400.0);
        return 1.0 - t * t;                              // falls off quickly, then gently
    }

    /** Everything currently resident, for the renderer to draw. */
    public Iterable<Entry> resident() {
        return resident.values();
    }

    public int residentCount() {
        return resident.size();
    }

    public int pendingCount() {
        return pending.size();
    }

    public int builtChunks() {
        return builtChunks;
    }

    public int evictedChunks() {
        return evictedChunks;
    }

    public void clear() {
        resident.clear();
        pending.clear();
    }

    private static double distance2(Entry entry, double focusEast, double focusNorth) {
        double dx = entry.centreEast - focusEast;
        double dz = entry.centreNorth - focusNorth;
        return dx * dx + dz * dz;
    }
}
