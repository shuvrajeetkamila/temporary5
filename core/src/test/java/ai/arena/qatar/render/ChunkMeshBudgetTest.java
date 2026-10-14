package ai.arena.qatar.render;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import ai.arena.qatar.world.Buildings;
import ai.arena.qatar.world.GameManifest;
import ai.arena.qatar.world.WorldData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vertex budget, tested where it broke.
 *
 * <p>The third install of this game closed immediately with nothing on the screen. The reason was here:
 * a mesh holds 65,000 vertices, the meshers asked {@code isFull()} -- "do four more fit?" -- before
 * adding a building of forty vertices or a road of three hundred, and the primitive then ran off the
 * end of the mesh and threw. The exception escaped the render loop, the GL thread died, and the app
 * went with it.
 *
 * <p>So this class tests three things, in the order they matter:
 * <ol>
 *   <li>The <b>rule</b>: a primitive is sized before it is placed, a part that is full rolls over to the
 *       next part, and a primitive that fits no part is refused rather than started.</li>
 *   <li>The <b>real city</b>: the densest 500 m chunk of the committed country meshes without throwing,
 *       however much of Doha is inside it, and every part it produces is inside the index buffer limit.
 *       This is the test that would have failed before the fix -- on the real bundles the APK ships.</li>
 *   <li>The <b>round trip</b>: what {@link MeshData#hasRoom(int)} promises is what adding vertices
 *       actually does, so the question the mesher asks cannot drift away from the answer.</li>
 * </ol>
 */
public class ChunkMeshBudgetTest {

    private static Path worldDir() {
        Path[] candidates = {
                Paths.get("..", "android", "assets", "world"),
                Paths.get("android", "assets", "world"),
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    @DisplayName("a full part rolls over instead of throwing, and the limit is reported honestly")
    void fullPartRollsOver() {
        ChunkMesher.ChunkMesh mesh = new ChunkMesher.ChunkMesh();
        MeshData first = mesh.detailPartFor(4);
        assertNotNull(first, "an empty chunk has room for a quad");
        while (first.hasRoom(4)) {
            first.addVertex(0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
        }
        assertEquals(MeshData.MAX_VERTICES - 3, first.vertexCount(),
                "the loop stops with exactly three vertices left, one short of a quad");
        assertTrue(first.isFull(), "a mesh with three vertices left is full for a quad");

        // The crash in one line: ask for the primitive, not for a generic quad.
        assertFalse(first.hasRoom(40), "a forty-vertex building does not fit a mesh with three left");
        MeshData second = mesh.detailPartFor(40);
        assertNotNull(second, "a second part takes the building");
        assertEquals(2, mesh.detail.size(), "the chunk split its detail in two");
        assertEquals(0, second.vertexCount(), "and the building starts at the beginning of it");
        assertFalse(mesh.truncated, "nothing has been dropped yet");
    }

    @Test
    @DisplayName("a primitive too big for any part is refused, never started")
    void impossiblePrimitiveIsRefused() {
        ChunkMesher.ChunkMesh mesh = new ChunkMesher.ChunkMesh();
        // A single ring with more edges than the whole index buffer can hold -- a malformed footprint
        // instead of a shed. It must be dropped and flagged, because there is no part it can go in.
        assertNull(mesh.detailPartFor(MeshData.MAX_VERTICES + 1),
                "no part can hold more vertices than the index buffer addresses");
        assertTrue(mesh.truncated, "and the chunk says it is missing geometry");
        assertEquals(0, mesh.detail.size(),
                "and no part is created for it: an oversized primitive is refused before anything "
                        + "is allocated");

        // Costs are reported unclamped so this distinction is visible at all. A footprint of ten
        // thousand edges needs more vertices than the index buffer can address, and the caller has to
        // be able to see that -- a value quietly capped at 65,000 would look like something that fits
        // in a fresh part.
        Buildings huge = new Buildings(1, new int[]{0}, new int[]{10_000},
                new float[20_000], new float[]{12f}, new float[]{0f}, new byte[]{1}, new byte[]{0},
                new int[]{0}, new byte[]{0}, new float[]{0, 0, 30, 30});
        int cost = BuildingMesher.vertexCost(huge, 0);
        assertTrue(cost > MeshData.MAX_VERTICES,
                "an oversized footprint reports a cost over the limit, not a clamped one: " + cost);
        Buildings shed = new Buildings(1, new int[]{0}, new int[]{5},
                new float[10], new float[]{3f}, new float[]{0f}, new byte[]{1}, new byte[]{0},
                new int[]{0}, new byte[]{0}, new float[]{0, 0, 6, 6});
        assertEquals(64, BuildingMesher.vertexCost(shed, 0),
                "four per wall edge plus a roof: eight per edge and twenty-four");
    }

    @Test
    @DisplayName("hasRoom promises exactly what adding vertices then does")
    void hasRoomMatchesWhatHappens() {
        MeshData data = new MeshData(4, 6);
        for (int i = 0; i < MeshData.MAX_VERTICES - 4; i++) {
            data.addVertex(i, 0, 0, 0, 1, 0, 0, 0, 0, 1);
        }
        assertEquals(4, data.remainingVertices(),
                "filling to four short of the limit leaves exactly four vertices");
        assertTrue(data.hasRoom(4), "exactly four fit");
        assertFalse(data.hasRoom(5), "five do not");
        assertFalse(data.isFull(), "and a quad still fits");
        for (int i = 0; i < 4; i++) {
            data.addVertex(0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
        }
        assertEquals(MeshData.MAX_VERTICES, data.vertexCount(), "the mesh is exactly full now");
        assertEquals(0, data.remainingVertices(), "and has nothing left");
        assertTrue(data.isFull(), "so it is full, and the next caller has to ask first");
        data.compact();
        assertEquals(MeshData.MAX_VERTICES * MeshData.FLOATS_PER_VERTEX * 4, data.memoryBytes(),
                "compaction keeps exactly the vertices that were written");

        // The memory that matters is the array a mesh grew into and never used. Every chunk allocates
        // three of these and hundreds of chunks are resident, so a small default plus a trim is the
        // difference between a game and an out-of-memory kill with no stack trace.
        MeshData small = new MeshData(4, 6);
        for (int i = 0; i < 10; i++) {
            small.addVertex(i, 0, 0, 0, 1, 0, 0, 0, 0, 1);
        }
        int grown = small.memoryBytes();
        small.compact();
        assertEquals(10 * MeshData.FLOATS_PER_VERTEX * 4 + small.indexCount() * 2, small.memoryBytes(),
                "a compacted mesh holds its vertices and nothing else");
        assertTrue(small.memoryBytes() < grown,
                "and less than the array it grew into: " + grown + " -> " + small.memoryBytes());
        small.releaseArrays();
        assertEquals(0, small.vertexCount(), "released arrays hold no vertices");
        assertEquals(0, small.memoryBytes(), "and no bytes");
    }

    @Test
    @DisplayName("the densest chunk of the committed country meshes inside every part")
    void densestChunkOfQatarStaysInsideTheBudget() {
        Path dir = worldDir();
        Assumptions.assumeTrue(dir != null, "android/assets/world is not checked out");
        WorldData world = load(dir);

        int[] capital = densestChunk(world);
        int chunkX = capital[0];
        int chunkZ = capital[1];

        ChunkMesher mesher = new ChunkMesher(world);
        ChunkMesher.ChunkMesh near = mesher.mesh(chunkX, chunkZ, 1.0);
        assertTrue(near.buildings > 40, "the near mesh extruded the city: " + near.buildings
                + " buildings, " + near.describe());
        assertTrue(near.roads > 0, "and the streets: " + near.roads + " ways");
        checkPartsInsideTheLimit(near);
        // The chunk is meshed at full detail, so it must be clipped only if it genuinely overran three
        // parts -- and if it did, the parts must all be there and all be full.
        if (near.truncated) {
            assertEquals(ChunkMesher.ChunkMesh.MAX_DETAIL_PARTS, near.detail.size(),
                    "a truncated chunk used every part it is allowed");
        }

        // A small neighbourhood of the same place, so the whole streaming path is exercised where it is
        // heaviest rather than on one lucky chunk.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkMesher.ChunkMesh mesh = mesher.mesh(chunkX + dx, chunkZ + dz, 1.0);
                checkPartsInsideTheLimit(mesh);
                assertTrue(mesh.vertexCount() > 0, "chunk " + (chunkX + dx) + "," + (chunkZ + dz)
                        + " produced terrain at least");
            }
        }

        // Beyond the detail radius only landmarks are drawn. Before that rule existed a distant chunk
        // extruded every footprint inside it, which is what made the far half of the resident window
        // expensive enough to be worth caring about.
        ChunkMesher.ChunkMesh far = mesher.mesh(chunkX, chunkZ, 0.0);
        assertTrue(far.buildings < near.buildings,
                "landmarks only: " + far.buildings + " vs " + near.buildings + " buildings");
        assertTrue(far.vertexCount() < near.vertexCount(),
                "and far less geometry: " + far.vertexCount() + " vs " + near.vertexCount());
        checkPartsInsideTheLimit(far);

        // Road ribbons are dropped entirely past their cutoff, so the road count is zero and the ground
        // is still there: a distant chunk is terrain, not a hole.
        assertTrue(far.roads == 0, "no road ribbons at detail 0: " + far.roads);
        assertTrue(far.land.vertexCount() > 0, "the ground is drawn at any distance");
    }

    @Test
    @DisplayName("the committed country meshes into finite geometry that stays inside its chunk")
    void committedCountryProducesFiniteGeometry() {
        Path dir = worldDir();
        Assumptions.assumeTrue(dir != null, "android/assets/world is not checked out");
        WorldData world = load(dir);
        ChunkMesher mesher = new ChunkMesher(world);

        // A neighbourhood of the capital, meshed at full detail -- the densest geometry the game ever
        // asks a driver to draw. Every float is checked for being a number at all: a single NaN in a
        // vertex position turns a triangle into nothing (or, depending on the driver, into a polygon
        // across the whole screen), and no count-based test can see it.
        int[] capital = densestChunk(world);
        int originX = capital[0];
        int originZ = capital[1];
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                checkGeometryIsSane(mesher.mesh(originX + dx, originZ + dz, 1.0), originX + dx,
                        originZ + dz);
            }
        }
    }

    /**
     * Everything a vertex buffer must be for a driver to draw it: numbers, in range, and indices that
     * point at vertices that exist.
     *
     * <p>The chunk's own coordinates are local metres, so a position belongs inside the chunk plus the
     * overhang a road ribbon or a wall can have at its edge. Heights are checked against Qatar's own
     * range -- the country is flat and low, and anything outside it is a projection bug rather than a
     * hill.
     */
    private static void checkGeometryIsSane(ChunkMesher.ChunkMesh mesh, int chunkX, int chunkZ) {
        checkPart(mesh.land, "land " + chunkX + "," + chunkZ);
        checkPart(mesh.water, "water " + chunkX + "," + chunkZ);
        for (int i = 0; i < mesh.detail.size(); i++) {
            checkPart(mesh.detail.get(i), "detail " + i + " of " + chunkX + "," + chunkZ);
        }
        assertTrue(mesh.buildings > 0 || mesh.roads > 0 || mesh.land.vertexCount() > 0,
                "the chunk produced something");
    }

    private static void checkPart(MeshData part, String label) {
        int floats = part.vertexCount() * MeshData.FLOATS_PER_VERTEX;
        float[] vertices = part.vertices();
        assertEquals(floats, vertices.length,
                label + ": the array holds exactly the vertices written, and nothing that grew");
        for (int i = 0; i < floats; i++) {
            assertTrue(Float.isFinite(vertices[i]),
                    label + ": vertex float " + i + " is " + vertices[i] + ", which no driver can draw");
        }
        for (int v = 0; v < part.vertexCount(); v++) {
            int at = v * MeshData.FLOATS_PER_VERTEX;
            float east = vertices[at];
            float north = vertices[at + 2];
            assertTrue(east > -120f && east < ChunkMesher.CHUNK_M + 120f,
                    label + ": vertex " + v + " east " + east + " is outside its own chunk");
            assertTrue(north > -120f && north < ChunkMesher.CHUNK_M + 120f,
                    label + ": vertex " + v + " north " + north + " is outside its own chunk");
            assertTrue(vertices[at + 1] > -120f && vertices[at + 1] < 400f,
                    label + ": vertex " + v + " height " + vertices[at + 1]
                            + " is outside anything Qatar could be");
        }
        assertEquals(0, part.indexCount() % 3, label + ": triangles come in threes");
        short[] indices = part.indices();
        assertEquals(part.indexCount(), indices.length, label + ": indices are trimmed too");
        for (int i = 0; i < part.indexCount(); i++) {
            // Unsigned: the index buffer is GL_UNSIGNED_SHORT, so the sign bit is part of the value.
            int index = indices[i] & 0xFFFF;
            assertTrue(index < part.vertexCount(),
                    label + ": index " + index + " points past the " + part.vertexCount()
                            + " vertices in this mesh");
        }
    }

    /**
     * The 500 m chunk with the most building footprints starting in it: the densest geometry the game can
     * be asked to draw, found by counting the real data rather than by guessing a coordinate. The national
     * frame starts at the compiled grid's origin, so a UTM figure written into a test would quietly point
     * at the sea.
     */
    private static int[] densestChunk(WorldData world) {
        Map<Integer, Integer> density = new HashMap<>();
        int bestKey = 0;
        int best = -1;
        for (int ring = 0; ring < world.buildings.ringCount; ring++) {
            int chunkX = ChunkMesher.chunkIndex(world.buildings.bounds[ring * 4]);
            int chunkZ = ChunkMesher.chunkIndex(world.buildings.bounds[ring * 4 + 1]);
            int key = chunkX * 4096 + chunkZ;
            int count = density.merge(key, 1, Integer::sum);
            if (count > best) {
                best = count;
                bestKey = key;
            }
        }
        assertTrue(best > 40, "the densest chunk of Qatar holds a city block: " + best + " buildings");
        return new int[]{bestKey / 4096, bestKey % 4096};
    }

    /**
     * Every part must be addressable by a 16-bit index buffer, and no part before the last may be empty
     * -- an empty part in the middle would mean geometry was stranded in a mesh that was left behind.
     *
     * <p>An empty <b>list</b> is legitimate, though: a chunk of empty desert with no road in it has no
     * detail at all, and that is not a failure. Only the parts that exist are checked.
     */
    private static void checkPartsInsideTheLimit(ChunkMesher.ChunkMesh mesh) {
        for (int i = 0; i < mesh.detail.size(); i++) {
            MeshData part = mesh.detail.get(i);
            assertTrue(part.vertexCount() <= MeshData.MAX_VERTICES,
                    "part " + i + " of " + mesh.detail.size() + " holds " + part.vertexCount()
                            + " vertices, more than the index buffer can address");
            assertTrue(part.vertexCount() > 0, "part " + i + " is not an empty leftover");
        }
    }

    private static WorldData load(Path dir) {
        try {
            GameManifest manifest = GameManifest.parse(new String(
                    Files.readAllBytes(dir.resolve("manifest.json")), java.nio.charset.StandardCharsets.UTF_8));
            return WorldData.fromSource(manifest, file -> {
                try {
                    return Files.readAllBytes(dir.resolve(file));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("cannot read " + dir.resolve(file), e);
                }
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + dir, e);
        }
    }
}
