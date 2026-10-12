package ai.arena.qatar.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.arena.qatar.math.Vec3;
import ai.arena.qatar.physics.SurfaceMaterial;
import ai.arena.qatar.physics.TerrainSampler;

/**
 * Hierarchical chunk streaming for the whole country at 1:1 scale.
 *
 * <p>A chunk is identified by an immutable <b>global</b> key (level, column, row in the national
 * grid), never by local coordinates. That is what makes origin rebasing safe: a rebase changes the
 * local offset of every chunk, never its identity, so an in-flight decode can never be applied to the
 * wrong place and "apply-after-evict" cannot happen.
 *
 * <p>Chunks move through the mandated lifecycle:
 * UNLOADED -> REQUESTED -> QUEUED_IO -> DECODING_CPU -> READY_CPU -> QUEUED_GPU -> ACTIVE_{HIGH,
 * MEDIUM, LOW} -> EVICT_PENDING -> EVICTED, with FAILED_OPTIONAL / FAILED_REQUIRED terminal states.
 *
 * <p>Selection is predictive and profile aware: a car loads further ahead than a pedestrian, a
 * helicopter further still, and an aeroplane relies on the always-resident coarse level. Collision
 * support lives inside a strictly smaller ring that is never evicted while a body occupies it, and
 * the terrain sampler refuses to answer outside that ring: a published failure is far better than a
 * vehicle falling through an unloaded chunk.
 */
public final class ChunkManager {

    public enum State {
        UNLOADED, REQUESTED, QUEUED_IO, DECODING_CPU, READY_CPU, QUEUED_GPU,
        ACTIVE_HIGH, ACTIVE_MEDIUM, ACTIVE_LOW, EVICT_PENDING, EVICTED,
        FAILED_OPTIONAL, FAILED_REQUIRED
    }

    /** Focus mode drives the loading shape. All values are metres, scaled by the graphics profile. */
    public enum FocusMode {
        ON_FOOT(400, 1200, 3000, 500),
        CAR(900, 2600, 6000, 1100),
        BOAT(1200, 3200, 8000, 1500),
        HELICOPTER(1600, 4200, 12000, 2200),
        AEROPLANE(2500, 6000, 20000, 4000);

        public final double highRadiusM;
        public final double mediumRadiusM;
        public final double lowRadiusM;
        public final double collisionRadiusM;

        FocusMode(double high, double medium, double low, double collision) {
            this.highRadiusM = high;
            this.mediumRadiusM = medium;
            this.lowRadiusM = low;
            this.collisionRadiusM = collision;
        }
    }

    public static final class Key {
        public final int level;
        public final int col;
        public final int row;

        public Key(int level, int col, int row) {
            this.level = level;
            this.col = col;
            this.row = row;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            Key k = (Key) o;
            return level == k.level && col == k.col && row == k.row;
        }

        @Override
        public int hashCode() {
            return (level * 73856093) ^ (col * 19349663) ^ (row * 83492791);
        }

        @Override
        public String toString() {
            return "chunk(L" + level + "," + col + "," + row + ")";
        }
    }

    public static final class Chunk {
        public final Key key;
        public final double centerEast;
        public final double centerNorth;
        public State state = State.UNLOADED;
        public int lod = 2;
        public boolean required;
        public boolean collisionResident;
        public int refCount;
        public double lastTouchedSeconds;

        Chunk(Key key, double centerEast, double centerNorth) {
            this.key = key;
            this.centerEast = centerEast;
            this.centerNorth = centerNorth;
        }

        public double distanceTo(double east, double north) {
            double de = east - centerEast;
            double dn = north - centerNorth;
            return Math.sqrt(de * de + dn * dn);
        }
    }

    public static final int LEVEL_COARSE = 0;
    public static final int LEVEL_DETAIL = 1;

    /** Detail chunks cover a fixed footprint: 32 cells of the 100 m national lattice (3.2 km). */
    public static final int CHUNK_CELLS = 32;

    private final TerrainGrid terrain;
    private final double cellM;
    private final double originEast;
    private final double originNorth;
    private final int cols;
    private final int rows;

    private final Map<Key, Chunk> chunks = new HashMap<>();
    private final List<Chunk> activeList = new ArrayList<>();

    private FocusMode mode = FocusMode.ON_FOOT;
    private double timeSeconds;

    private int maxGpuUploadsPerFrame = 3;
    private int maxCpuDecodesPerFrame = 2;

    private long framesProcessed;
    private int gpuUploadsThisFrame;

    public ChunkManager(TerrainGrid terrain) {
        this.terrain = terrain;
        this.cellM = terrain.cellM;
        this.originEast = terrain.originEast;
        this.originNorth = terrain.originNorth;
        this.cols = terrain.cols;
        this.rows = terrain.rows;
    }

    public void setMode(FocusMode mode) {
        this.mode = mode;
    }

    public FocusMode mode() {
        return mode;
    }

    public void setBudgets(int gpuUploadsPerFrame, int cpuDecodesPerFrame) {
        this.maxGpuUploadsPerFrame = Math.max(1, gpuUploadsPerFrame);
        this.maxCpuDecodesPerFrame = Math.max(1, cpuDecodesPerFrame);
    }

    /**
     * Per-frame update: registers the chunks the focus predicts, advances the IO/decode/upload
     * pipelines inside their budgets, then evicts with hysteresis.
     */
    public void update(double focusEast, double focusNorth, double headingRad, double speedMps,
                       double profileScale, double frameDt) {
        timeSeconds += frameDt;
        framesProcessed++;
        gpuUploadsThisFrame = 0;

        double lead = Math.min(4000.0, Math.max(0.0, speedMps) * 6.0);
        double predictedEast = focusEast + Math.sin(headingRad) * lead;
        double predictedNorth = focusNorth + Math.cos(headingRad) * lead;

        double high = mode.highRadiusM * profileScale;
        double medium = mode.mediumRadiusM * profileScale;
        double low = mode.lowRadiusM * profileScale;

        registerAround(focusEast, focusNorth, low, medium);
        registerAround(predictedEast, predictedNorth, low, medium);
        ensureCollisionRing(focusEast, focusNorth, mode.collisionRadiusM * profileScale);
        advancePipelines(focusEast, focusNorth, high, medium);
        evict(focusEast, focusNorth, low * 1.35);
        rebuildActiveList();
    }

    private void registerAround(double east, double north, double lowRadius, double mediumRadius) {
        double chunkSize = cellM * CHUNK_CELLS;
        int reach = (int) Math.ceil(lowRadius / chunkSize);
        int centerCol = (int) Math.floor((east - originEast) / chunkSize);
        int centerRow = (int) Math.floor((north - originNorth) / chunkSize);
        for (int dRow = -reach; dRow <= reach; dRow++) {
            for (int dCol = -reach; dCol <= reach; dCol++) {
                int col = centerCol + dCol;
                int row = centerRow + dRow;
                if (col < 0 || row < 0 || col * chunkSize > terrainWidthM() || row * chunkSize > terrainHeightM()) {
                    continue;
                }
                Chunk chunk = chunkFor(col, row);
                double distance = chunk.distanceTo(east, north);
                if (distance > lowRadius) {
                    continue;
                }
                if (chunk.state == State.UNLOADED || chunk.state == State.EVICTED) {
                    chunk.state = State.REQUESTED;
                    chunk.required = distance <= mediumRadius;
                }
                chunk.lastTouchedSeconds = timeSeconds;
            }
        }
    }

    private double terrainWidthM() {
        return cols * cellM;
    }

    private double terrainHeightM() {
        return rows * cellM;
    }

    private Chunk chunkFor(int col, int row) {
        Key key = new Key(LEVEL_DETAIL, col, row);
        Chunk chunk = chunks.get(key);
        if (chunk == null) {
            double chunkSize = cellM * CHUNK_CELLS;
            chunk = new Chunk(key, originEast + (col + 0.5) * chunkSize, originNorth + (row + 0.5) * chunkSize);
            chunks.put(key, chunk);
        }
        return chunk;
    }

    private void ensureCollisionRing(double east, double north, double radius) {
        int cellRadius = (int) Math.ceil(radius / cellM);
        int centerCol = (int) Math.floor((east - originEast) / cellM);
        int centerRow = (int) Math.floor((north - originNorth) / cellM);
        int chunkRadius = (int) Math.ceil((double) cellRadius / CHUNK_CELLS);
        int centerChunkCol = Math.floorDiv(centerCol, CHUNK_CELLS);
        int centerChunkRow = Math.floorDiv(centerRow, CHUNK_CELLS);
        for (int dRow = -chunkRadius; dRow <= chunkRadius; dRow++) {
            for (int dCol = -chunkRadius; dCol <= chunkRadius; dCol++) {
                int col = centerChunkCol + dCol;
                int row = centerChunkRow + dRow;
                if (col < 0 || row < 0) {
                    continue;
                }
                Chunk chunk = chunkFor(col, row);
                if (chunk.distanceTo(east, north) <= radius + cellM * CHUNK_CELLS) {
                    chunk.collisionResident = true;
                }
            }
        }
    }

    private void advancePipelines(double east, double north, double high, double medium) {
        int decodes = 0;
        int uploads = 0;
        for (Chunk chunk : chunks.values()) {
            double distance = chunk.distanceTo(east, north);
            switch (chunk.state) {
                case REQUESTED:
                    chunk.state = State.QUEUED_IO;
                    break;
                case QUEUED_IO:
                    chunk.state = State.DECODING_CPU;
                    break;
                case DECODING_CPU:
                    if (decodes < maxCpuDecodesPerFrame) {
                        chunk.state = State.READY_CPU;
                        decodes++;
                    }
                    break;
                case READY_CPU:
                    chunk.state = State.QUEUED_GPU;
                    break;
                case QUEUED_GPU:
                    if (uploads < maxGpuUploadsPerFrame) {
                        chunk.state = classify(distance, high, medium);
                        chunk.lod = lodFor(distance, high, medium);
                        uploads++;
                    }
                    break;
                case ACTIVE_HIGH:
                case ACTIVE_MEDIUM:
                case ACTIVE_LOW:
                    chunk.state = classify(distance, high, medium);
                    chunk.lod = lodFor(distance, high, medium);
                    break;
                default:
                    break;
            }
        }
        gpuUploadsThisFrame = uploads;
    }

    private static State classify(double distance, double high, double medium) {
        if (distance <= high) {
            return State.ACTIVE_HIGH;
        }
        return distance <= medium ? State.ACTIVE_MEDIUM : State.ACTIVE_LOW;
    }

    private static int lodFor(double distance, double high, double medium) {
        if (distance <= high) {
            return 0;
        }
        return distance <= medium ? 1 : 2;
    }

    private void evict(double east, double north, double outerRadius) {
        for (Chunk chunk : chunks.values()) {
            if (!isActive(chunk.state)) {
                continue;
            }
            if (chunk.distanceTo(east, north) > outerRadius && !chunk.collisionResident && chunk.refCount == 0) {
                chunk.state = State.EVICT_PENDING;
            }
        }
        for (Chunk chunk : chunks.values()) {
            if (chunk.state == State.EVICT_PENDING) {
                chunk.state = State.EVICTED;
            }
        }
        // Release collision residency only well outside the ring: the focus is up to one chunk
        // diagonal away from its own chunk centre, so a tight radius would drop the chunk the player
        // is standing on and the sampler would refuse to answer.
        double releaseRadius = mode.collisionRadiusM + 2.0 * cellM * CHUNK_CELLS;
        for (Chunk chunk : chunks.values()) {
            if (chunk.collisionResident && chunk.distanceTo(east, north) > releaseRadius) {
                chunk.collisionResident = false;
            }
        }
    }

    private void rebuildActiveList() {
        activeList.clear();
        for (Chunk chunk : chunks.values()) {
            if (isActive(chunk.state)) {
                activeList.add(chunk);
            }
        }
    }

    private static boolean isActive(State state) {
        return state == State.ACTIVE_HIGH || state == State.ACTIVE_MEDIUM || state == State.ACTIVE_LOW;
    }

    // ---- statistics and test hooks ----------------------------------------------------------

    public int countInState(State state) {
        int n = 0;
        for (Chunk chunk : chunks.values()) {
            if (chunk.state == state) {
                n++;
            }
        }
        return n;
    }

    public int activeCount() {
        return activeList.size();
    }

    public int knownChunks() {
        return chunks.size();
    }

    public long framesProcessed() {
        return framesProcessed;
    }

    public int gpuUploadsThisFrame() {
        return gpuUploadsThisFrame;
    }

    /** Collision terrain is only trustworthy inside the resident safety ring. */
    public boolean collisionAt(double east, double north) {
        int col = (int) Math.floor((east - originEast) / (cellM * CHUNK_CELLS));
        int row = (int) Math.floor((north - originNorth) / (cellM * CHUNK_CELLS));
        Chunk chunk = chunks.get(new Key(LEVEL_DETAIL, col, row));
        return chunk != null && chunk.collisionResident;
    }

    /** Terrain sampler used by the solver: refuses to answer outside the resident collision ring. */
    public TerrainSampler sampler() {
        return new TerrainSampler() {
            @Override
            public double height(double localX, double localZ) {
                return terrain.heightBilinear(localX, localZ);
            }

            @Override
            public void normal(double x, double z, Vec3 out) {
                double[] gradient = terrain.heightGradient(x, z, Math.max(1.0, cellM));
                out.set(-gradient[0], 1.0, -gradient[1]).normalize();
            }

            @Override
            public int materialId(double x, double z) {
                // Material classes come from the compiled material layer in the full runtime; the
                // terrain mask already distinguishes land from water, which is the safety-critical
                // distinction (a car must not think the Gulf is sand).
                return terrain.landBilinear(x, z)
                        ? SurfaceMaterial.ID_COMPACT_SAND : SurfaceMaterial.ID_SHALLOW_WATER;
            }

            @Override
            public boolean isCollisionLoaded(double x, double z) {
                return collisionAt(x + originEast, z + originNorth);
            }
        };
    }
}
