package ai.arena.qatar.render;

import java.util.ArrayList;
import java.util.List;

import ai.arena.qatar.world.Areas;
import ai.arena.qatar.world.Barriers;
import ai.arena.qatar.world.Buildings;
import ai.arena.qatar.world.FeatureIndex;
import ai.arena.qatar.world.Roads;
import ai.arena.qatar.world.WorldData;

/**
 * Builds the geometry for one streaming chunk: the ground and sea, plus the detailed features that are
 * close enough to be worth drawing.
 *
 * <p>A chunk is a square of the national grid (see {@link #CHUNK_M}) and everything it produces is in
 * chunk-local metres, so a vertex coordinate stays small and precise however far the player is from the
 * map origin -- the same reason the floating origin exists.
 *
 * <p>Detail level is a number from 0 to 1 derived from distance, and it decides how much of the city is
 * drawn: at distance only the large buildings are extruded and road ribbons are skipped, while within a
 * kilometre every footprint and every lane marking is there. That is the difference between a phone
 * holding 30 frames per second and a phone melting.
 *
 * <h2>The vertex budget, and the crash it caused</h2>
 *
 * <p>A GPU mesh with 16-bit indices holds 65,000 vertices, and a dense chunk of Doha holds more than
 * that: a building costs four vertices per wall edge plus its roof, a road costs eight per segment with
 * its markings, and there are hundreds of each inside 500 metres. The first version of this class asked
 * {@code mesh.isFull()} -- which means "four vertices still fit" -- once before each building, and a
 * building needs forty. So a primitive could start on a full mesh and run off the end, which threw
 * inside the render loop, which killed the GL thread, which killed the app: the third install, closing
 * immediately with nothing on the screen.
 *
 * <p>Three things prevent that now, and all three are needed:
 * <ol>
 *   <li>Every primitive is <b>sized before it is added</b> ({@link ChunkMesh#detailPartFor(int)}), and a
 *       primitive that does not fit goes into the next part.</li>
 *   <li>A chunk's detail is <b>several parts</b>, up to {@link ChunkMesh#MAX_DETAIL_PARTS}, so a dense
 *       chunk keeps its detail instead of being cut off at 65,000 vertices.</li>
 *   <li>Past that cap, the mesher <b>stops cleanly and says so</b> ({@link ChunkMesh#truncated}), rather
 *       than throwing. A chunk that is missing a shed is a bug; a chunk that kills the app is worse.</li>
 * </ol>
 */
public final class ChunkMesher {

    /** Chunk edge length in metres. 500 m at the finest terrain cell (100 m) is a 5x5 cell patch. */
    public static final double CHUNK_M = 500.0;

    /** Below this detail, nothing is scattered: distance turns plants into pixels and then into fog. */
    public static final double VEGETATION_DETAIL_CUTOFF = VegetationMesher.MIN_DETAIL;
    /** Buildings smaller than this are only drawn when the chunk is close (square metres). */
    private static final double SMALL_BUILDING_M2 = 45.0;
    /** Below this detail, road ribbons are dropped entirely and only terrain is drawn. */
    private static final double ROAD_DETAIL_CUTOFF = 0.18;

    private final WorldData world;
    private final TerrainMesher terrainMesher;
    private final BuildingMesher buildingMesher;
    private final RoadMesher roadMesher;
    private final VegetationMesher vegetationMesher = new VegetationMesher();

    private final int[] candidates = new int[8192];

    public ChunkMesher(WorldData world) {
        this.world = world;
        this.terrainMesher = new TerrainMesher(world.terrainL1, world.materials);
        this.buildingMesher = new BuildingMesher(world.buildings, world.terrainL1);
        this.roadMesher = new RoadMesher(world.roads, world.terrainL1);
    }

    /**
     * The three meshes of one chunk, kept apart because water is drawn without back-face culling.
     *
     * <p>Land and water are single meshes: a 500 m chunk of terrain is a few hundred vertices and can
     * never approach the limit. Detail is a list, because that is where the country lives.
     */
    public static final class ChunkMesh {

        /**
         * Parts a chunk's detail may be split into: 3 x 65,000 vertices is 195,000, which is about as
         * much as a phone should hold for chunks near the player. The CPU arrays for three full parts
         * are 8.6 MB, and they are freed as soon as the GPU has them.
         */
        public static final int MAX_DETAIL_PARTS = 3;

        public final MeshData land = new MeshData();
        public final MeshData water = new MeshData();
        public final List<MeshData> detail = new ArrayList<>();
        public int buildings;
        public int roads;
        /** Plants scattered in this chunk: palms and bushes together. */
        public int vegetation;
        /** True when geometry was left out because every part was full: reported, never fatal. */
        public boolean truncated;

        /** The part new detail goes into, creating the first one on demand. */
        public MeshData detailPart() {
            if (detail.isEmpty()) {
                detail.add(new MeshData());
            }
            return detail.get(detail.size() - 1);
        }

        /**
         * The part that can hold {@code vertices} more vertices, rolling over to a new one when the
         * current part is full. Returns null when the chunk has no room left at all -- the caller must
         * then stop adding and this chunk is flagged as truncated.
         */
        public MeshData detailPartFor(int vertices) {
            if (vertices > MeshData.MAX_VERTICES) {
                // More than a whole index buffer can hold: no part can ever take this primitive, so
                // say so here rather than letting a caller start it on a fresh mesh and overflow.
                truncated = true;
                return null;
            }
            MeshData part = detailPart();
            if (part.hasRoom(vertices)) {
                return part;
            }
            if (detail.size() < MAX_DETAIL_PARTS) {
                detail.add(new MeshData());
                return detail.get(detail.size() - 1);
            }
            truncated = true;
            return null;
        }

        public boolean isEmpty() {
            if (!land.isEmpty() || !water.isEmpty()) {
                return false;
            }
            for (MeshData part : detail) {
                if (!part.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        public int triangleCount() {
            int triangles = land.triangleCount() + water.triangleCount();
            for (MeshData part : detail) {
                triangles += part.triangleCount();
            }
            return triangles;
        }

        public int vertexCount() {
            int vertices = land.vertexCount() + water.vertexCount();
            for (MeshData part : detail) {
                vertices += part.vertexCount();
            }
            return vertices;
        }

        /** Bytes held on the CPU, for the resident budget: the GPU holds a second, equal copy. */
        public int memoryBytes() {
            int bytes = land.memoryBytes() + water.memoryBytes();
            for (MeshData part : detail) {
                bytes += part.memoryBytes();
            }
            return bytes;
        }

        /** Moves the whole chunk into its own frame of reference: see {@link MeshData#translate}. */
        public void translate(float dx, float dz) {
            land.translate(dx, dz);
            water.translate(dx, dz);
            for (MeshData part : detail) {
                part.translate(dx, dz);
            }
        }

        /** Trims every part to the size it actually reached. */
        public void compact() {
            land.compact();
            water.compact();
            for (MeshData part : detail) {
                part.compact();
            }
        }

        /** Drops the CPU vertex data once the GPU has a copy. */
        public void releaseArrays() {
            land.releaseArrays();
            water.releaseArrays();
            for (MeshData part : detail) {
                part.releaseArrays();
            }
        }

        public String describe() {
            int detailTriangles = 0;
            for (MeshData part : detail) {
                detailTriangles += part.triangleCount();
            }
            return triangleCount() + " triangles (" + buildings + " buildings, " + roads + " roads, "
                    + vegetation + " plants, "
                    + detail.size() + " detail part" + (detail.size() == 1 ? "" : "s")
                    + (truncated ? ", TRUNCATED at the vertex budget" : "") + ")";
        }
    }

    /** Chunk index for a national-frame coordinate. */
    public static int chunkIndex(double metres) {
        return (int) Math.floor(metres / CHUNK_M);
    }

    public static double chunkOrigin(int index) {
        return index * CHUNK_M;
    }

    public ChunkMesh mesh(int chunkX, int chunkZ, double detail) {
        ChunkMesh mesh = new ChunkMesh();
        double minEast = chunkOrigin(chunkX);
        double minNorth = chunkOrigin(chunkZ);
        double maxEast = minEast + CHUNK_M;
        double maxNorth = minNorth + CHUNK_M;

        terrainMesher.meshInto(mesh.land, minEast, minNorth, maxEast, maxNorth, true);
        terrainMesher.meshInto(mesh.water, minEast, minNorth, maxEast, maxNorth, false);

        if (world.buildings != null && world.buildingIndex != null) {
            int count = collect(world.buildingIndex, minEast, minNorth, maxEast, maxNorth);
            mesh.buildings = meshBuildings(count, minEast, minNorth, maxEast, maxNorth, detail, mesh);
        }
        if (world.roads != null && world.roadIndex != null && detail >= ROAD_DETAIL_CUTOFF) {
            int count = collect(world.roadIndex, minEast, minNorth, maxEast, maxNorth);
            mesh.roads = meshRoads(count, minEast, minNorth, maxEast, maxNorth, mesh);
        }
        if (world.barriers != null) {
            meshBarriers(minEast, minNorth, maxEast, maxNorth, detail, mesh);
        }
        // Palms and scrub last, and on whatever room is left: the city is what the player is driving
        // through, and a chunk that cannot hold both should lose its bushes rather than its buildings.
        // The land use carries the plants -- see VegetationMesher -- and the budget is spent before a
        // plant is written, so a chunk of dense scrub cannot overrun a detail part.
        if (world.landuse != null && detail >= VEGETATION_DETAIL_CUTOFF) {
            VegetationMesher.Stats stats = vegetationMesher.meshInto(world.landuse, world.terrainL1,
                    world.materials, mesh.detailPartFor(VegetationMesher.MAX_CHUNK_VERTICES),
                    minEast, minNorth, maxEast, maxNorth, detail,
                    VegetationMesher.MAX_CHUNK_VERTICES);
            mesh.vegetation = stats.plants();
        }
        // Everything the meshers wrote is in bundle space -- metres from the compiled grid's origin --
        // because that is the frame the terrain, building and road data are stored in. The renderer
        // places a chunk by translating it from its own origin into the rebased frame next to the
        // player, so the chunk has to be relative to that origin first. Without this, every chunk was
        // drawn one origin-distance away from where it belongs: the country came out as a mostly empty
        // screen with a few squares of sand and half a building in it, which is exactly what the fourth
        // install showed, and no test could see it because every test looked at one chunk on its own.
        mesh.translate((float) -minEast, (float) -minNorth);

        // Trim to size before the chunk is handed over: a mesh that grew to hold the whole city and ended
        // up with a shed in it must not keep the array it grew into. Hundreds of chunks are resident.
        mesh.compact();
        return mesh;
    }

    private int collect(FeatureIndex index, double minEast, double minNorth, double maxEast,
                        double maxNorth) {
        final int[] found = {0};
        index.query(minEast, minNorth, maxEast, maxNorth, id -> {
            if (found[0] < candidates.length) {
                candidates[found[0]++] = id;
            }
            return true;
        });
        return found[0];
    }

    private int meshBuildings(int count, double minEast, double minNorth, double maxEast,
                              double maxNorth, double detail, ChunkMesh mesh) {
        Buildings buildings = world.buildings;
        int emitted = 0;
        for (int i = 0; i < count; i++) {
            int ring = candidates[i];
            if (buildings.bounds[ring * 4] > maxEast || buildings.bounds[ring * 4 + 2] < minEast
                    || buildings.bounds[ring * 4 + 1] > maxNorth
                    || buildings.bounds[ring * 4 + 3] < minNorth) {
                continue;
            }
            // Distance decides how much of the city is worth drawing: a shed in the far distance is
            // a couple of pixels of noise, a tower two kilometres away still reads as a landmark.
            // Beyond the detail radius only landmarks are kept -- which is also what keeps the far
            // ring of the resident window cheap: terrain is a few hundred vertices a chunk, a city
            // is tens of thousands, and at five kilometres the fog has eaten the city anyway.
            if (detail <= 0.0 && !buildings.isLandmark(ring)) {
                continue;
            }
            if (detail < 0.85 && !buildings.isLandmark(ring)) {
                double width = buildings.bounds[ring * 4 + 2] - buildings.bounds[ring * 4];
                double depth = buildings.bounds[ring * 4 + 3] - buildings.bounds[ring * 4 + 1];
                double area = width * depth;
                if (detail < 0.35 && area < SMALL_BUILDING_M2 * 12.0) {
                    continue;
                }
                if (detail < 0.7 && area < SMALL_BUILDING_M2 * 3.0) {
                    continue;
                }
            }
            // Size the building before making room for it: four vertices per wall edge, plus a roof
            // that is at most four more per edge. This is the line whose absence killed the app.
            int cost = BuildingMesher.vertexCost(buildings, ring);
            if (cost > MeshData.MAX_VERTICES) {
                // One footprint too big for a 16-bit index buffer to hold at all: a courtyard complex
                // mapped as a single ring, or a bad ring. It cannot go in any part, so it is left out
                // and the chunk says so -- but nothing is allowed to throw over it.
                mesh.truncated = true;
                continue;
            }
            MeshData part = mesh.detailPartFor(cost);
            if (part == null) {
                return emitted;                       // out of budget: keep what we have, say so
            }
            if (buildingMesher.addBuilding(ring, part)) {
                emitted++;
            }
        }
        return emitted;
    }

    /**
     * Road ribbons, one way at a time so that each one can be sized before it is added: a way is a
     * polyline and a single long one can be hundreds of segments, which is exactly the primitive that
     * used to overflow a nearly full mesh.
     */
    private int meshRoads(int count, double minEast, double minNorth, double maxEast, double maxNorth,
                          ChunkMesh mesh) {
        Roads roads = world.roads;
        int emitted = 0;
        for (int i = 0; i < count; i++) {
            int line = candidates[i];
            if (line < 0 || line >= roads.count) {
                continue;
            }
            if (!roadMesher.overlaps(line, minEast, minNorth, maxEast, maxNorth)) {
                continue;
            }
            // Four vertices per segment for the ribbon, four more when it carries a centre line -- and
            // only for the segments inside this chunk, which is the part that will be drawn: the ribbon
            // is clipped to the chunk edge. Sizing this way is also what lets a 30 km highway into a
            // chunk at all, because the whole way is bigger than any mesh this renderer has.
            int cost = RoadMesher.vertexCost(roads, line, minEast, minNorth, maxEast, maxNorth);
            if (cost == 0) {
                continue;                             // the way's box overlaps, its metres do not
            }
            if (cost > MeshData.MAX_VERTICES) {
                mesh.truncated = true;                // a single way longer than the whole index buffer
                continue;
            }
            MeshData part = mesh.detailPartFor(cost);
            if (part == null) {
                return emitted;
            }
            roadMesher.addRoad(line, part, minEast, minNorth, maxEast, maxNorth);
            emitted++;
        }
        return emitted;
    }

    private void meshBarriers(double minEast, double minNorth, double maxEast, double maxNorth,
                              double detail, ChunkMesh mesh) {
        if (detail < 0.5 || world.barriers.count == 0) {
            return;                                     // walls at distance are a line of noise
        }
        Barriers barriers = world.barriers;
        for (int line = 0; line < barriers.count; line++) {
            if (barriers.bounds[line * 4] > maxEast || barriers.bounds[line * 4 + 2] < minEast
                    || barriers.bounds[line * 4 + 1] > maxNorth
                    || barriers.bounds[line * 4 + 3] < minNorth) {
                continue;
            }
            if (!addBarrier(barriers, line, mesh)) {
                return;                                 // out of budget, and it is already flagged
            }
        }
    }

    /**
     * A barrier is a thin vertical ribbon: two triangles per segment, culled from far away. Each segment
     * is sized on its own (four vertices) because a wall can be a kilometre long.
     *
     * @return false when the chunk has no room left and the caller must stop
     */
    private boolean addBarrier(Barriers barriers, int line, ChunkMesh mesh) {
        int start = barriers.lineStart[line];
        int points = barriers.lineLength[line];
        float height = Math.max(0.6f, barriers.heightM[line]);
        float tint = barriers.kind[line] == Barriers.KIND_HEDGE ? 0.5f : 1.0f;
        for (int i = 0; i < points - 1; i++) {
            float ax = barriers.xz[(start + i) * 2];
            float az = barriers.xz[(start + i) * 2 + 1];
            float bx = barriers.xz[(start + i + 1) * 2];
            float bz = barriers.xz[(start + i + 1) * 2 + 1];
            float dx = bx - ax;
            float dz = bz - az;
            float length = (float) Math.sqrt(dx * dx + dz * dz);
            if (length < 0.3f) {
                continue;
            }
            MeshData part = mesh.detailPartFor(4);
            if (part == null) {
                return false;
            }
            float baseA = (float) world.terrainL1.heightBilinear(
                    world.manifest.originEast + ax, world.manifest.originNorth + az);
            float baseB = (float) world.terrainL1.heightBilinear(
                    world.manifest.originEast + bx, world.manifest.originNorth + bz);
            float nx = -dz / length;
            float nz = dx / length;
            float wall = VertexFormat.SURFACE_WALL;
            int a0 = part.addVertex(ax, baseA, az, nx, 0, nz, wall, 0, 0, tint);
            int a1 = part.addVertex(ax, baseA + height, az, nx, 0, nz, wall, 0, height, tint);
            int b0 = part.addVertex(bx, baseB, bz, nx, 0, nz, wall, length, 0, tint);
            int b1 = part.addVertex(bx, baseB + height, bz, nx, 0, nz, wall, length, height, tint);
            part.addQuad(a0, a1, b1, b0);
            // Both sides, so a compound wall is solid from inside and outside alike.
            part.addQuad(a0, b0, b1, a1);
        }
        return true;
    }

    /** Land-use polygons are compiled for vegetation and gameplay, not drawn as geometry yet. */
    public Areas landUse() {
        return world.landuse;
    }

    /** Exposed for the map view and for tests. */
    public Roads roads() {
        return world.roads;
    }
}
