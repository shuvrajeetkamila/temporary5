package ai.arena.qatar.render;

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
 */
public final class ChunkMesher {

    /** Chunk edge length in metres. 500 m at the finest terrain cell (100 m) is a 5x5 cell patch. */
    public static final double CHUNK_M = 500.0;

    /** Buildings smaller than this are only drawn when the chunk is close (square metres). */
    private static final double SMALL_BUILDING_M2 = 45.0;
    /** Below this detail, road ribbons are dropped entirely and only terrain is drawn. */
    private static final double ROAD_DETAIL_CUTOFF = 0.18;

    private final WorldData world;
    private final TerrainMesher terrainMesher;
    private final BuildingMesher buildingMesher;
    private final RoadMesher roadMesher;

    private final int[] candidates = new int[8192];

    public ChunkMesher(WorldData world) {
        this.world = world;
        this.terrainMesher = new TerrainMesher(world.terrainL1, world.materials);
        this.buildingMesher = new BuildingMesher(world.buildings, world.terrainL1);
        this.roadMesher = new RoadMesher(world.roads, world.terrainL1);
    }

    /** The three meshes of one chunk, kept apart because water is drawn without back-face culling. */
    public static final class ChunkMesh {
        public final MeshData land = new MeshData();
        public final MeshData water = new MeshData();
        public final MeshData detail = new MeshData();
        public int buildings;
        public int roads;

        public boolean isEmpty() {
            return land.isEmpty() && water.isEmpty() && detail.isEmpty();
        }

        public int triangleCount() {
            return land.triangleCount() + water.triangleCount() + detail.triangleCount();
        }

        public String describe() {
            return triangleCount() + " triangles (" + buildings + " buildings, " + roads + " roads)";
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
            mesh.buildings = meshBuildings(count, minEast, minNorth, maxEast, maxNorth, detail,
                    mesh.detail);
        }
        if (world.roads != null && world.roadIndex != null && detail >= ROAD_DETAIL_CUTOFF) {
            int count = collect(world.roadIndex, minEast, minNorth, maxEast, maxNorth);
            mesh.roads = roadMesher.mesh(minEast, minNorth, maxEast, maxNorth, mesh.detail,
                    candidates, count);
        }
        if (world.barriers != null) {
            meshBarriers(minEast, minNorth, maxEast, maxNorth, detail, mesh.detail);
        }
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
                              double maxNorth, double detail, MeshData mesh) {
        Buildings buildings = world.buildings;
        int emitted = 0;
        for (int i = 0; i < count; i++) {
            int ring = candidates[i];
            if (mesh.isFull()) {
                break;
            }
            if (buildings.bounds[ring * 4] > maxEast || buildings.bounds[ring * 4 + 2] < minEast
                    || buildings.bounds[ring * 4 + 1] > maxNorth
                    || buildings.bounds[ring * 4 + 3] < minNorth) {
                continue;
            }
            // Distance decides how much of the city is worth drawing: a shed in the far distance is
            // a couple of pixels of noise, a tower two kilometres away still reads as a landmark.
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
            if (buildingMesher.addBuilding(ring, mesh)) {
                emitted++;
            }
        }
        return emitted;
    }

    private void meshBarriers(double minEast, double minNorth, double maxEast, double maxNorth,
                              double detail, MeshData mesh) {
        if (detail < 0.5 || world.barriers.count == 0) {
            return;                                     // walls at distance are a line of noise
        }
        Barriers barriers = world.barriers;
        for (int line = 0; line < barriers.count && !mesh.isFull(); line++) {
            if (barriers.bounds[line * 4] > maxEast || barriers.bounds[line * 4 + 2] < minEast
                    || barriers.bounds[line * 4 + 1] > maxNorth
                    || barriers.bounds[line * 4 + 3] < minNorth) {
                continue;
            }
            addBarrier(barriers, line, mesh);
        }
    }

    /** A barrier is a thin vertical ribbon: two triangles per segment, culled from far away. */
    private void addBarrier(Barriers barriers, int line, MeshData mesh) {
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
            float baseA = (float) world.terrainL1.heightBilinear(
                    world.manifest.originEast + ax, world.manifest.originNorth + az);
            float baseB = (float) world.terrainL1.heightBilinear(
                    world.manifest.originEast + bx, world.manifest.originNorth + bz);
            float nx = -dz / length;
            float nz = dx / length;
            float wall = VertexFormat.SURFACE_WALL;
            int a0 = mesh.addVertex(ax, baseA, az, nx, 0, nz, wall, 0, 0, tint);
            int a1 = mesh.addVertex(ax, baseA + height, az, nx, 0, nz, wall, 0, height, tint);
            int b0 = mesh.addVertex(bx, baseB, bz, nx, 0, nz, wall, length, 0, tint);
            int b1 = mesh.addVertex(bx, baseB + height, bz, nx, 0, nz, wall, length, height, tint);
            mesh.addQuad(a0, a1, b1, b0);
            // Both sides, so a compound wall is solid from inside and outside alike.
            mesh.addQuad(a0, b0, b1, a1);
        }
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
