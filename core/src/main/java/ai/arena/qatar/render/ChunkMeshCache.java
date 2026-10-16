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
    /** The chunks chosen for this frame. Reused between frames: this runs sixty times a second. */
    private final List<Entry> pending = new ArrayList<>();
    /**
     * Candidate chunks for this frame, as keys and squared distances, in parallel arrays.
     *
     * <p>They are not {@link Entry} objects on purpose. Every chunk that is missing inside the radius
     * used to be allocated as an Entry, sorted, and then thrown away again at the end of the frame: a few
     * hundred objects per frame, tens of thousands per second, for a list whose only surviving members
     * are the three or four that get built. That garbage is collection work on a phone that is already
     * running a country.
     */
    /**
     * A missing chunk this close to the focus is built even when the vertex budget is full: it is the
     * ground the player is standing on or about to walk onto, and a hole there is not a trade to make.
     */
    private static final double URGENT_RADIUS_CHUNKS = 1.5;

    /** Marks a candidate as already chosen this frame, so the selection scan skips it. */
    private static final long TAKEN = Long.MIN_VALUE;
    private final long[] candidateKey = new long[4096];
    private final double[] candidateDistance2 = new double[4096];
    private int candidateCount;
    private long frame;
    private int builtChunks;
    private int evictedChunks;
    private int residentVertices;
    private int budgetEvictions;
    /**
     * Average vertices per built chunk, an exponential moving average.
     *
     * <p>It answers one question: is there room in the vertex budget for another chunk? Without it, the
     * cache built three chunks every single frame and then evicted the farthest ones to stay under
     * budget -- which put the same far ring back on the build list for ever. Three chunk builds every
     * frame is the country being meshed six hundred times a minute, on the CPU, for geometry that is
     * discarded moments later, and it is what makes a phone hot in ten seconds. With it, the cache
     * builds nothing while the window is full, and builds again when the player has moved somewhere
     * there is room for.
     */
    private double averageChunkVertices = 1500.0;

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Updates the cache for a new focus position.
     *
     * @return the chunks that must be built this frame (nearest first, at most {@code budget} of them)
     */
    public List<Entry> update(double focusEast, double focusNorth, int budget) {
        return update(focusEast, focusNorth, budget, TERRAIN_RADIUS_M);
    }

    /**
     * The same update, with the radius the caller is willing to hold.
     *
     * <p>The renderer passes its own draw radius, which shrinks when the phone is struggling: the window
     * the cache keeps should be the window the frame can afford to draw, or the cache spends its time
     * building chunks that are never on the screen.
     */
    public List<Entry> update(double focusEast, double focusNorth, int budget, double radiusM) {
        frame++;
        resident.values().forEach(entry -> {
            if (entry.uploaded || entry.mesh != null) {
                entry.lastUsedFrame = frame;
            }
        });

        // Which chunks should exist, nearest first? A square ring is enough and cheap to enumerate.
        double radius = Math.max(ChunkMesher.CHUNK_M, radiusM);
        int minX = ChunkMesher.chunkIndex(focusEast - radius);
        int maxX = ChunkMesher.chunkIndex(focusEast + radius);
        int minZ = ChunkMesher.chunkIndex(focusNorth - radius);
        int maxZ = ChunkMesher.chunkIndex(focusNorth + radius);

        candidateCount = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double centreEast = ChunkMesher.chunkOrigin(x) + ChunkMesher.CHUNK_M * 0.5;
                double centreNorth = ChunkMesher.chunkOrigin(z) + ChunkMesher.CHUNK_M * 0.5;
                double dx = centreEast - focusEast;
                double dz = centreNorth - focusNorth;
                double distance2 = dx * dx + dz * dz;
                double limit = radius + ChunkMesher.CHUNK_M;
                if (distance2 > limit * limit) {
                    continue;
                }
                long chunkKey = key(x, z);
                if (resident.containsKey(chunkKey)) {
                    continue;                           // includes the chunks whose build failed
                }
                if (candidateCount == candidateKey.length) {
                    break;                              // a full frame of candidates is enough
                }
                candidateKey[candidateCount] = chunkKey;
                candidateDistance2[candidateCount] = distance2;
                candidateCount++;
            }
        }

        // Evict what is now far outside the resident window. The window is the radius the caller asked
        // for -- the renderer's draw radius, which shrinks when the phone is struggling -- plus a margin
        // so that a chunk streaming in behind the player is not dropped before it is drawn. Reading the
        // constant here instead of the parameter meant a cache that could never let go of a seven
        // kilometre ring, whatever the frame could afford to draw, and it is also what made a teleport
        // drag the whole wrong country along with it until the memory budget took it back.
        double evictRadius = Math.max(ChunkMesher.CHUNK_M, radiusM) + EVICT_MARGIN_M;
        Iterator<Map.Entry<Long, Entry>> iterator = resident.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            double distance = Math.sqrt(distance2(entry, focusEast, focusNorth));
            if (distance > evictRadius) {
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

        pending.clear();

        // The budget gate. A window that is already full cannot hold another chunk, and building one
        // anyway is the churn that heats the phone: build, evict, rebuild. Nothing is built until there
        // is room for it -- except the ground under the player's feet, which is built whatever the
        // budget says, because a hole where the player is standing is not a performance trade.
        int limit = Math.max(0, budget);
        if (limit == 0 || candidateCount == 0) {
            return pending;                              // reused list, emptied: no allocation this frame
        }
        double nearestDistance2 = Double.MAX_VALUE;
        for (int i = 0; i < candidateCount; i++) {
            nearestDistance2 = Math.min(nearestDistance2, candidateDistance2[i]);
        }
        double urgent2 = URGENT_RADIUS_CHUNKS * ChunkMesher.CHUNK_M * URGENT_RADIUS_CHUNKS
                * ChunkMesher.CHUNK_M;
        if (resident.size() >= MIN_RESIDENT && nearestDistance2 > urgent2
                && residentVertices + averageChunkVertices > MAX_RESIDENT_VERTICES) {
            return pending;
        }

        // Nearest first, chosen one at a time: with a budget of three to six, a selection scan is
        // cheaper than sorting four hundred candidates, and it allocates nothing at all.
        for (int pick = 0; pick < limit; pick++) {
            int best = -1;
            double bestDistance2 = Double.MAX_VALUE;
            for (int i = 0; i < candidateCount; i++) {
                if (candidateKey[i] == TAKEN) {
                    continue;
                }
                if (candidateDistance2[i] < bestDistance2) {
                    bestDistance2 = candidateDistance2[i];
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            long chosenKey = candidateKey[best];
            candidateKey[best] = TAKEN;
            pending.add(new Entry((int) (chosenKey >> 32), (int) chosenKey));
        }
        return pending;
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
        // The moving average is what the budget gate asks "is there room for another one of these?".
        // It starts at a desert chunk's size and follows whatever this part of the country actually
        // costs, so a window of Doha stops building sooner than a window of sand.
        averageChunkVertices += (entry.vertices - averageChunkVertices) * 0.05;
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

    /** How many chunks this frame found that it does not have: the backlog, not the work chosen. */
    public int pendingCount() {
        return candidateCount;
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
