package ai.arena.qatar.world;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the real, committed country bundles -- the ones the APK ships -- through the same parsers the
 * game uses.
 *
 * <p>The synthetic fixture proves the formats are internally consistent. This proves the thing that
 * actually matters: that the 14 MB of compiled Qatar in the repository parses, that every bundle matches
 * the digest the manifest recorded, and that the numbers are country-scale. It is the test that would
 * have caught the manifest grid being read from the wrong nesting level, where every terrain lookup
 * would silently have been 431 km off.
 *
 * <p>Skipped (not failed) when the assets are not present, so the core module still tests on its own.
 */
public class RealWorldAssetsTest {

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

    private static byte[] read(Path dir, String file) {
        try {
            return Files.readAllBytes(dir.resolve(file));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + dir.resolve(file), e);
        }
    }

    @Test
    @DisplayName("the committed manifest describes the real country grid")
    void manifestDescribesTheRealCountry() {
        Path dir = worldDir();
        Assumptions.assumeTrue(dir != null, "android/assets/world is not checked out");
        try {
            GameManifest manifest = GameManifest.parse(
                    new String(read(dir, "manifest.json"), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(2, manifest.format, "manifest format");
            assertEquals("EPSG:32639", manifest.crs, "master CRS");
            assertTrue(manifest.widthM > 200_000, "grid is wider than 200 km: " + manifest.widthM);
            assertTrue(manifest.heightM > 200_000, "grid is taller than 200 km: " + manifest.heightM);
            assertTrue(manifest.originEast > 400_000 && manifest.originEast < 500_000,
                    "grid origin east is in the UTM 39N band: " + manifest.originEast);
            assertTrue(manifest.originNorth > 2_600_000 && manifest.originNorth < 2_900_000,
                    "grid origin north is over the peninsula: " + manifest.originNorth);
            assertTrue(manifest.attribution.contains("OpenStreetMap"),
                    "attribution names OpenStreetMap: " + manifest.attribution);
            assertTrue(manifest.terrainL1.present(), "terrain level 1 is named");
            assertTrue(manifest.materials.present(), "materials are named");
            assertTrue(manifest.roads.present(), "roads are named");
            assertTrue(manifest.buildings.present(), "buildings are named");
            assertTrue(manifest.pois.present(), "points are named");
            assertTrue(manifest.spawnPoints.getOrDefault("car", 0) > 0,
                    "car spawn points are reported: " + manifest.spawnPoints);
        } catch (RuntimeException e) {
            throw new AssertionError("the committed manifest does not parse: " + e.getMessage(), e);
        }
    }

    @Test
    @DisplayName("every committed bundle loads, verifies its digest and holds country-scale data")
    void everyBundleLoadsAndVerifies() {
        Path dir = worldDir();
        Assumptions.assumeTrue(dir != null, "android/assets/world is not checked out");
        GameManifest manifest = GameManifest.parse(
                new String(read(dir, "manifest.json"), java.nio.charset.StandardCharsets.UTF_8));

        TerrainGrid terrain = TerrainGrid.parse(read(dir, manifest.terrainL1.file),
                manifest.originEast, manifest.originNorth, "QTT1");
        assertEquals(manifest.terrainL1.cols, terrain.cols, "terrain columns match the manifest");
        assertEquals(manifest.terrainL1.rows, terrain.rows, "terrain rows match the manifest");
        assertEquals(manifest.terrainL1.cellM, terrain.cellM, 1e-6, "terrain cell size");
        assertTrue(terrain.heights.length > 1_000_000, "the country grid has millions of cells");

        MaterialGrid materials = MaterialGrid.parse(read(dir, manifest.materials.file),
                manifest.originEast, manifest.originNorth);
        assertEquals(terrain.cols, materials.cols, "materials and terrain agree on width");
        assertEquals(terrain.rows, materials.rows, "materials and terrain agree on height");

        BundleReader roads = BundleReader.open("roads", read(dir, manifest.roads.file),
                manifest.roads.sha256);
        BundleReader.Header roadHeader = roads.header("QTR1");
        assertTrue(roadHeader.count > 100_000, "roads: " + roadHeader.count + " ways");

        // Buildings, land use, water, barriers and points are verified by loading them the way the game
        // does: a digest mismatch or a truncated record throws here rather than at 30,000 feet.
        WorldData world = WorldData.fromSource(manifest, file -> read(dir, file));
        assertNotNull(world.buildings, "buildings loaded");
        assertNotNull(world.roads, "roads loaded");
        assertNotNull(world.points, "points loaded");
        assertTrue(world.buildings.count > 100_000,
                "building footprints: " + world.buildings.count);
        assertTrue(world.roads.count > 100_000, "road ways: " + world.roads.count);
        assertTrue(world.strings.size() > 10_000, "string table: " + world.strings.size());
        assertTrue(world.points.count > 500, "points of interest: " + world.points.count);

        // Every spawn kind the game offers must exist somewhere in the country, or a locomotion type
        // would have nowhere legitimate to start.
        int[] kinds = new int[16];
        for (int i = 0; i < world.points.count; i++) {
            int kind = world.points.kind[i] & 0xFF;
            if (kind < kinds.length) {
                kinds[kind]++;
            }
        }
        assertTrue(kinds[Points.KIND_CAR] > 0, "car spawn points: " + kinds[Points.KIND_CAR]);
        assertTrue(kinds[Points.KIND_BOAT] > 0, "boat spawn points: " + kinds[Points.KIND_BOAT]);
        assertTrue(kinds[Points.KIND_PLANE] > 0, "aeroplane spawn points: " + kinds[Points.KIND_PLANE]);
        assertTrue(kinds[Points.KIND_LANDMARK] > 0, "landmarks: " + kinds[Points.KIND_LANDMARK]);

        // A building footprint must be a usable ring: closed, and inside the grid it claims to be in.
        Buildings buildings = world.buildings;
        int length = buildings.ringLength[0];
        int start = buildings.ringStart[0];
        assertTrue(length >= 4, "first footprint has at least four vertices: " + length);
        float firstE = buildings.xz[start * 2];
        float firstN = buildings.xz[start * 2 + 1];
        float lastE = buildings.xz[(start + length - 1) * 2];
        float lastN = buildings.xz[(start + length - 1) * 2 + 1];
        assertEquals(firstE, lastE, 0.001f, "a footprint ring is closed in east");
        assertEquals(firstN, lastN, 0.001f, "a footprint ring is closed in north");
        assertTrue(firstE >= 0 && firstE <= manifest.widthM, "footprint east is inside the grid: " + firstE);
        assertTrue(firstN >= 0 && firstN <= manifest.heightM,
                "footprint north is inside the grid: " + firstN);
        assertTrue(buildings.heightM[0] >= 2.0f, "buildings have a real height: "
                + buildings.heightM[0]);

        // Roads must be inside the grid too: a road outside the terrain grid is a projection bug.
        for (int i = 0; i < world.roads.count; i++) {
            float e = world.roads.xz[world.roads.lineStart[i] * 2];
            float n = world.roads.xz[world.roads.lineStart[i] * 2 + 1];
            assertTrue(e >= 0 && e <= manifest.widthM && n >= 0 && n <= manifest.heightM,
                    "road " + i + " starts inside the grid: " + e + ", " + n);
            if (i > 5000) {
                break;                      // a sample is enough: 180k identical assertions prove nothing
            }
        }
    }
}
