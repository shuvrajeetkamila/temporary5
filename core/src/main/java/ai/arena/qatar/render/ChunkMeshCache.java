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
 */
public final class ChunkMeshCache {

    /** Detail is 1 at the focus and falls to 0 at the detail radius. */
    public static final double DETAIL_RADIUS_M = 2500.0;
    /** Terrain is built further out than buildings and roads, so the horizon does not pop. */
    public static final double TERRAIN_RADIUS_M = 6000.0;
    /** Hysteresis: a chunk stays resident until it is this far outside its build radius. */
    public static final double EVICT_MARGIN_M = 1000.0;
    /** Hard cap on resident chunks; beyond this the least recently used go, whatever their distance. */
    public static final int MAX_RESIDENT = 420;

    /** One resident chunk: the mesh plus the bookkeeping the cache needs. */
    public static final class Entry {
        public final int chunkX;
        public final int chunkZ;
        public final double centreEast;
        public final double centreNorth;
        public ChunkMesh mesh;
        public long lastUsedFrame;

        Entry(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.centreEast = ChunkMesher.chunkOrigin(chunkX) + ChunkMesher.CHUNK_M * 0.5;
            this.centreNorth = ChunkMesher.chunkOrigin(chunkZ) + ChunkMesher.CHUNK_M * 0.5;
        }

        public long key() {
            return key(chunkX, chunkZ);
        }
    }

    private final Map<Long, Entry> resident = new LinkedHashMap<>();
    private final List<Entry> pending = new ArrayList<>();
    private long frame;
    private int builtChunks;
    private int evictedChunks;

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
            if (entry.mesh != null) {
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
                iterator.remove();
                evictedChunks++;
            }
        }
        while (resident.size() > MAX_RESIDENT) {
            Iterator<Map.Entry<Long, Entry>> oldest = resident.entrySet().iterator();
            if (!oldest.hasNext()) {
                break;
            }
            oldest.next();
            oldest.remove();
            evictedChunks++;
        }

        int limit = Math.max(0, budget);
        List<Entry> chosen = new ArrayList<>(Math.min(limit, pending.size()));
        for (int i = 0; i < pending.size() && chosen.size() < limit; i++) {
            chosen.add(pending.get(i));
        }
        return chosen;
    }

    /** Installs a freshly built chunk (called on the GL thread right after building). */
    public void put(int chunkX, int chunkZ, ChunkMesh mesh) {
        Entry entry = new Entry(chunkX, chunkZ);
        entry.mesh = mesh;
        entry.lastUsedFrame = frame;
        resident.put(entry.key(), entry);
        builtChunks++;
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
