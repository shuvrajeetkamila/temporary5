package ai.arena.qatar.game;

import ai.arena.qatar.hud.MapRaster;
import ai.arena.qatar.hud.MapView;
import ai.arena.qatar.world.GameManifest;
import ai.arena.qatar.world.Points;
import ai.arena.qatar.world.WorldData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map is the only control that moves the player across 259 km in one gesture, so it is tested
 * against the real compiled geometry rather than by eye: where a tap lands, whether a tap can put the
 * player in the sea, and whether zooming keeps the point under the finger still.
 *
 * <p>Runs on the committed synthetic fixture, so it needs no country compile and no device.
 */
final class MapTeleportTest {

    private static final double LAKE_EAST = 1000.0;
    private static final double LAKE_NORTH = 400.0;
    private static final double LAND_EAST = 240.0;
    private static final double LAND_NORTH = 285.0;

    private static WorldData world;
    private static MapRaster raster;
    private static TeleportService teleport;

    @BeforeAll
    static void loadFixture() throws IOException {
        Path fixture = Paths.get("src/test/resources/synthetic_world");
        if (!Files.isDirectory(fixture)) {
            fixture = Paths.get("core/src/test/resources/synthetic_world");
        }
        assertTrue(Files.isDirectory(fixture), "synthetic world fixture is missing: " + fixture);
        Path dir = fixture;
        GameManifest manifest = GameManifest.parse(
                new String(Files.readAllBytes(dir.resolve("manifest.json")), StandardCharsets.UTF_8));
        world = WorldData.fromSource(manifest, name -> read(dir, name));
        raster = MapRaster.build(world, 512);
        teleport = new TeleportService(world, raster);
    }

    private static byte[] read(Path dir, String name) {
        try {
            return Files.readAllBytes(dir.resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException("fixture file unreadable: " + name, e);
        }
    }

    @Test
    @DisplayName("a map pixel and a world position are the same thing to within one pixel")
    void mapPixelsRoundTrip() {
        double[] samples = {0.0, 137.0, 400.0, 1000.0, 1599.0};
        for (double east : samples) {
            for (double north : samples) {
                double pixelX = raster.u(east, north) * raster.width;
                double pixelY = raster.v(east, north) * raster.height;
                assertTrue(pixelX >= -0.5 && pixelX <= raster.width + 0.5,
                        "east " + east + " fell outside the raster: pixel " + pixelX);
                assertTrue(pixelY >= -0.5 && pixelY <= raster.height + 0.5,
                        "north " + north + " fell outside the raster: pixel " + pixelY);
                double backEast = raster.eastAt(pixelX / raster.width);
                double backNorth = raster.northAt(pixelY / raster.height);
                double cell = raster.widthM() / raster.width;
                assertEquals(east, backEast, cell, "east round trip at " + east);
                assertEquals(north, backNorth, cell, "north round trip at " + north);
            }
        }
    }

    @Test
    @DisplayName("the map knows water from land")
    void mapKnowsWater() {
        assertTrue(raster.isWaterAt(LAKE_EAST, LAKE_NORTH), "the lake is drawn as land");
        assertTrue(!raster.isWaterAt(LAND_EAST, LAND_NORTH), "a building plot is drawn as water");
    }

    @Test
    @DisplayName("tapping the lake does not leave the player swimming")
    void tappingWaterIsSafe() {
        TeleportService.Target target = teleport.resolve(LAKE_EAST, LAKE_NORTH);
        boolean boatSpawn = target.snappedToSpawn && target.spawnKind == Points.KIND_BOAT;
        assertTrue(boatSpawn || !target.onWater,
                "a tap on the lake left the player in the water: " + target);
        assertTrue(boatSpawn || !raster.isWaterAt(target.east, target.north),
                "the resolved position is still water: " + target);
        assertTrue(boatSpawn || target.snappedToRoad || target.snappedToShore,
                "a water tap resolved to neither a harbour, a road nor a shore: " + target);
        assertTrue(target.east >= -100 && target.east <= world.manifest.widthM + 100
                        && target.north >= -100 && target.north <= world.manifest.heightM + 100,
                "the resolved position is outside the country: " + target);
        assertNotNull(target.toString());
    }

    @Test
    @DisplayName("tapping beside a road puts the player on that road")
    void tappingNearARoadSnapsToIt() {
        int line = -1;
        for (int i = 0; i < world.roads.count; i++) {
            if ((world.roads.roadClass[i] & 0xFF) <= 10) {
                line = i;
                break;
            }
        }
        assertTrue(line >= 0, "the fixture has no road the player can be placed on");
        double roadEast = world.roads.xz[world.roads.lineStart[line] * 2];
        double roadNorth = world.roads.xz[world.roads.lineStart[line] * 2 + 1];

        TeleportService.Target target = teleport.resolve(roadEast + 25.0, roadNorth + 25.0);
        double distance = Math.hypot(target.east - roadEast, target.north - roadNorth);
        assertTrue(distance <= TeleportService.SNAP_RADIUS_M,
                "a tap 35 m from a road landed " + distance + " m away");
        assertTrue(target.snappedToRoad || target.snappedToSpawn,
                "a tap beside a road snapped to nothing: " + target);
    }

    @Test
    @DisplayName("every tap on the map lands inside the country")
    void everyTapLandsInsideTheCountry() {
        for (int py = 0; py < raster.height; py += 37) {
            for (int px = 0; px < raster.width; px += 41) {
                TeleportService.Target target = teleport.fromMapPixel(px, py);
                assertTrue(target.east >= -100 && target.east <= world.manifest.widthM + 100,
                        "tap at pixel " + px + "," + py + " resolved to east " + target.east);
                assertTrue(target.north >= -100 && target.north <= world.manifest.heightM + 100,
                        "tap at pixel " + px + "," + py + " resolved to north " + target.north);
                assertNotNull(target.label);
            }
        }
    }

    @Test
    @DisplayName("a tap beside the map is not a place: the letterbox is not the country")
    void tapsOffTheMapAreRefused() {
        MapView view = new MapView(raster);
        // A phone-shaped rectangle for a square raster: 2400 x 864 leaves 768 px of scrim on each side,
        // and that scrim is exactly where the thumb lands when someone aims at the edge of the map.
        view.setViewport(0f, 108f, 2400f, 864f);
        assertTrue(view.containsPoint(1200f, 540f), "the middle of the map is not on the map");
        assertFalse(view.containsPoint(90f, 540f), "a tap on the scrim was read as a tap on the map");
        assertFalse(view.containsPoint(2310f, 540f), "a tap on the scrim was read as a tap on the map");
        assertFalse(view.containsPoint(1200f, 60f), "a tap above the map was read as a tap on the map");
        // And the reason the guard exists: what a tap in the scrim resolves to is outside the country.
        double outside = view.eastAt(90f);
        assertTrue(outside < 0.0 || outside > raster.widthM(),
                "the scrim maps to " + outside + " m east, inside the raster: the guard is not needed and "
                        + "this test is describing something that does not happen");
    }

    @Test
    @DisplayName("a destination carries the direction the place faces")
    void theDestinationCarriesItsOwnDirection() {
        // The compiled bundle stores a direction as a mathematical angle (0 east, anticlockwise) because
        // that is how the compiler measures a runway; this project's bodies steer by heading degrees
        // (0 north, clockwise). A runway whose bearing is 90 in bundle terms runs north.
        assertEquals(0.0, TeleportService.headingFromFacing(90.0), 1e-9,
                "a runway pointing north in the bundle's convention");
        assertEquals(270.0, TeleportService.headingFromFacing(180.0), 1e-9,
                "a runway pointing west in the bundle's convention");
        assertEquals(45.0, TeleportService.headingFromFacing(45.0), 1e-9, "a runway pointing north-east");
        assertEquals(-1.0, TeleportService.headingFromFacing(0.0), 1e-9,
                "a stored zero means the data has no direction, not that the place faces north");

        // End to end, on the fixture's own runway threshold: 50 m east of the manifest's origin and
        // 1450 m north of it, with a stored facing of 90.
        TeleportService.Target target = teleport.resolve(55.0, 1455.0);
        assertTrue(target.snappedToSpawn, "the tap beside the runway threshold did not find it: "
                + target);
        assertEquals(0.0, target.headingDegrees, 1e-6,
                "arriving at the runway threshold should face down the runway, and it faces "
                        + target.headingDegrees);
    }

    @Test
    @DisplayName("a tap on a road arrives facing along it")
    void aRoadTapFacesTheRoad() {
        // The fixture's east-west way runs along northing 850 from x=5 to x=1500.
        TeleportService.Target target = teleport.resolve(120.0, 852.0);
        assertTrue(target.snappedToRoad, "the tap on the road did not find it: " + target);
        assertEquals(90.0, target.headingDegrees, 1.0,
                "a west-to-east road should be faced west-to-east (heading 90), and the destination is "
                        + target.headingDegrees);
    }

    @Test
    @DisplayName("a start position exists for every locomotion type")
    void everyLocomotionHasAStart() {
        int[] kinds = {Points.KIND_CAR, Points.KIND_BOAT, Points.KIND_HELI, Points.KIND_PLANE};
        for (int kind : kinds) {
            TeleportService.Target target = teleport.spawnFor(kind);
            assertTrue(target.east >= 0 && target.east <= world.manifest.widthM,
                    "kind " + kind + " starts outside the country: " + target.east);
            assertTrue(target.north >= 0 && target.north <= world.manifest.heightM,
                    "kind " + kind + " starts outside the country: " + target.north);
        }
    }

    @Test
    @DisplayName("zooming keeps the point under the fingers still and the view inside the data")
    void zoomHoldsTheAnchor() {
        MapView view = new MapView(raster);
        view.setViewport(0, 0, 1200, 700);
        view.setZoom(4.0f);

        float anchorX = 812.0f;
        float anchorY = 233.0f;
        float anchorU = view.uAt(anchorX);
        float anchorV = view.vAt(anchorY);
        view.zoomAt(anchorX, anchorY, 1.8f);
        assertEquals(anchorU, view.uAt(anchorX), 1e-4, "zoom moved the anchored point sideways");
        assertEquals(anchorV, view.vAt(anchorY), 1e-4, "zoom moved the anchored point vertically");

        // Panning cannot scroll off the raster at any zoom.
        for (float zoom : new float[]{MapView.MIN_ZOOM, 3.0f, MapView.MAX_ZOOM}) {
            view.setZoom(zoom);
            view.pan(-1_000_000f, -1_000_000f);
            assertTrue(view.leftU() >= -1e-4, "panned past the west edge at zoom " + zoom);
            assertTrue(view.bottomV() >= -1e-4, "panned past the south edge at zoom " + zoom);
            view.pan(1_000_000f, 1_000_000f);
            assertTrue(view.leftU() + view.visibleU() <= 1.0001f, "panned past the east edge at zoom " + zoom);
            assertTrue(view.bottomV() + view.visibleV() <= 1.0001f, "panned past the north edge at zoom " + zoom);
        }

        // The minimap and the big map are two crops of one raster, so they cannot disagree.
        view.setViewport(0, 0, 220, 220);
        view.centreOnWorld(LAKE_EAST, LAKE_NORTH);
        assertEquals(raster.u(LAKE_EAST, LAKE_NORTH), view.centreU(), 1e-3,
                "centring on a position did not centre the map");
    }

    @Test
    @DisplayName("a place can be pointed at on the map, and the mark stays on the place")
    void aPlaceCanBeMarked() {
        MapView view = new MapView(raster);
        view.setViewport(0, 0, 1200, 700);
        for (float zoom : new float[]{MapView.MIN_ZOOM, 2.5f, MapView.MAX_ZOOM}) {
            view.setZoom(zoom);
            for (float px : new float[]{5.0f, 411.0f, 1195.0f}) {
                for (float py : new float[]{5.0f, 233.0f, 695.0f}) {
                    double east = view.eastAt(px);
                    double north = view.northAt(py);
                    // The mark that says "you were teleported here" is asked for as a screen pixel every
                    // frame, from a world position: if the two do not agree, the crosshair names a place
                    // the player is not standing on.
                    assertEquals(px, view.screenXAt(east, north), 0.5f,
                            "the mark drifted east at zoom " + zoom);
                    assertEquals(py, view.screenYAt(east, north), 0.5f,
                            "the mark drifted north at zoom " + zoom);
                }
            }
        }
        // And after a teleport the place that was travelled to is in the middle of the map, which is
        // where the map itself puts it.
        view.setZoom(6.0f);
        view.centreOnWorld(LAND_EAST, LAND_NORTH);
        assertEquals(600.0f, view.screenXAt(LAND_EAST, LAND_NORTH), 1.0f,
                "the travelled-to place is not where the map is looking");
        assertEquals(350.0f, view.screenYAt(LAND_EAST, LAND_NORTH), 1.0f,
                "the travelled-to place is not where the map is looking");
    }
}
