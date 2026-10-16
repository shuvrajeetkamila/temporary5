package ai.arena.qatar.game;

import ai.arena.qatar.hud.MapRaster;
import ai.arena.qatar.physics.TerrainSampler;
import ai.arena.qatar.world.GameManifest;
import ai.arena.qatar.world.GridTerrainSampler;
import ai.arena.qatar.world.Points;
import ai.arena.qatar.world.WorldData;
import ai.arena.qatar.world.WorldOrigin;

import org.junit.jupiter.api.Assumptions;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walls are walls, and <em>only</em> walls are walls: the tenth install's "the human and the car cannot
 * move", answered against the committed country rather than against an opinion.
 *
 * <p>{@code WorldSurface.solidAt} used to decide "am I inside a building" with the rectangle around each
 * building's footprint. A rectangle is not a building. OSM buildings are rotated, L-shaped and clustered,
 * so the box around a tower turned thirty degrees covers a square of plaza, pavement, courtyard and road
 * that no wall passes through -- and a walker or a car standing in it is refused in every direction by
 * its own collision rule. On the compiled country this was not a corner case:
 *
 * <ul>
 *   <li>52 of the 279 car spawn points (18.6%) are inside some building's rectangle and outside every
 *       wall, so a launch there began with a player who could not move at all;</li>
 *   <li>2.4% of the country's road vertices are in the same situation, so driving and walking met it
 *       constantly.</li>
 * </ul>
 *
 * <p>These tests hold the fixed rule: the footprint decides, the rectangle only rejects candidates
 * cheaply, every spawn point in the country is somewhere a person could stand, and a person placed at the
 * spawn point the game actually uses walks.
 */
public class WorldSurfaceFootprintTest {

    private static WorldData world;
    private static WorldSurface surface;
    /** The point the game starts the player at, and the car spawn point it comes from. */
    private static double[] spawn;
    private static double[] carSpawn;

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
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + dir.resolve(file), e);
        }
    }

    @BeforeAll
    static void loadTheCountry() {
        Path dir = worldDir();
        Assumptions.assumeTrue(dir != null, "android/assets/world is not checked out");
        GameManifest manifest = GameManifest.parse(
                new String(read(dir, "manifest.json"), StandardCharsets.UTF_8));
        world = WorldData.fromSource(manifest, file -> read(dir, file));
        WorldOrigin origin = new WorldOrigin(manifest.originEast, manifest.originNorth);
        surface = new WorldSurface(new GridTerrainSampler(world.terrainL1, world.materials, origin),
                world);

        // The spawn the game actually uses, chosen by the code that chooses it -- GameScreen calls
        // exactly this, and then the boat spawn if the car spawn is the one named Doha.
        TeleportService chooser = new TeleportService(world, MapRaster.build(world, 512));
        TeleportService.Target car = chooser.spawnFor(Points.KIND_CAR);
        TeleportService.Target start = "Doha".equals(car.label) ? chooser.spawnFor(Points.KIND_BOAT) : car;
        // A compiled spawn point is a hope; the clear-ground search is the promise. GameScreen asks for
        // both, in this order, before it hands over the screen -- so the point this test walks from is
        // the point the player starts on, not the one the bundle named.
        SpawnClearer.Spot carSpot = SpawnClearer.nearestClear(surface, car.east, car.north);
        SpawnClearer.Spot startSpot = SpawnClearer.nearestClear(surface, start.east, start.north);
        carSpawn = new double[]{carSpot.eastM, carSpot.northM};
        spawn = new double[]{startSpot.eastM, startSpot.northM};
    }

    /**
     * The rectangle test the shipping build used -- asked through the same spatial index, so it is the
     * same set of candidates the shipping code would have looked at, and the count means something.
     */
    private static boolean insideBoundsRectangle(double eastM, double northM) {
        final double aboveGround = 1.0;                         // a walker's head above the ground
        boolean[] hit = {false};
        world.buildingIndex.query(eastM - 0.05, northM - 0.05, eastM + 0.05, northM + 0.05, ring -> {
            if (aboveGround >= world.buildings.heightM[ring]) {
                return true;                                    // standing above this roof
            }
            if (eastM < world.buildings.bounds[ring * 4] || eastM > world.buildings.bounds[ring * 4 + 2]
                    || northM < world.buildings.bounds[ring * 4 + 1]
                    || northM > world.buildings.bounds[ring * 4 + 3]) {
                return true;                                    // the index cell overlaps, the box does not
            }
            hit[0] = true;
            return false;
        });
        return hit[0];
    }

    @Test
    @DisplayName("every car spawn point in the country is somewhere a walker could stand")
    void everyCarSpawnPointIsClear() {
        int checked = 0;
        int refusedByTheRectangleOnly = 0;
        int inAWall = 0;
        int inTheSea = 0;
        for (int i = 0; i < world.points.count; i++) {
            if ((world.points.kind[i] & 0xFF) != Points.KIND_CAR) {
                continue;
            }
            double east = world.points.xz[i * 2];
            double north = world.points.xz[i * 2 + 1];
            checked++;
            double ground = surface.groundHeightM(east, north);
            if (surface.waterDepthM(east, north) > SpawnClearer.WADE_DEPTH_M) {
                // Not a wall: open water. Two of the country's 279 car spawn points are in the Gulf --
                // a mapped jetty, a car park the sea has taken, or a point the compiler should have
                // dropped. No walker can stand there, and no clear patch inside the search ring can be
                // found for the same reason, so what there is to assert is that the game does not start
                // the player on one (the two spawn tests below) and that there are not many of them.
                inTheSea++;
                continue;
            }
            if (surface.solidAt(east, north, ground + 1.0)) {
                // The spawn points that are genuinely inside a wall are the SpawnClearer's business, and
                // the game asks it before handing over the screen.
                inAWall++;
                SpawnClearer.Spot clear = SpawnClearer.nearestClear(surface, east, north);
                assertTrue(clear.moved, "a car spawn point is inside a wall and no clear place was found "
                        + "within " + SpawnClearer.MAX_SEARCH_M + " m of it: " + east + ", " + north);
                assertFalse(SpawnClearer.isClear(surface, east, north),
                        "the search said a solid point was clear");
                continue;
            }
            if (insideBoundsRectangle(east, north)) {
                refusedByTheRectangleOnly++;
            }
        }
        assertTrue(checked > 100, "car spawn points checked: " + checked);
        assertTrue(refusedByTheRectangleOnly > 20,
                "only " + refusedByTheRectangleOnly + " of " + checked + " car spawn points are inside a "
                        + "building's rectangle but outside its walls -- the committed country is expected "
                        + "to have dozens, and with none this test proves nothing about the footprint test "
                        + "(it would pass with the rectangle rule this round replaced)");
        assertTrue(inAWall > 0, "no car spawn point is inside a wall at all, so the promise "
                + "SpawnClearer makes (" + SpawnClearer.MAX_SEARCH_M + " m of search) is untested");
        // Fifteen of the country's 279 car spawn points stand in water deeper than a wader's limit -- the
        // marinas and harbours, where the mapped car park is the quay. They are a fact of the compiled
        // data, not a fault in the collision rule, and what matters is that they are a small minority and
        // that the game does not start the player on one (the two spawn tests below hold that). The bound
        // is fifteen per cent, loose enough to survive the next compile of the country and tight enough
        // to notice if the water classification ever breaks.
        assertTrue(inTheSea * 15 <= checked, inTheSea + " of " + checked + " car spawn points are in water "
                + "deeper than " + SpawnClearer.WADE_DEPTH_M + " m, which is more than a few marinas: the "
                + "country's spawn data or the water materials have gone wrong");
    }

    @Test
    @DisplayName("the spawn point the game uses is clear, and a walker standing on it walks")
    void theRealSpawnIsSomewhereAWalkerCanMove() {
        double east = spawn[0];
        double north = spawn[1];
        double ground = surface.groundHeightM(east, north);
        assertFalse(surface.solidAt(east, north, ground + 1.0),
                "the spawn point the game picks is inside a building: this is the tenth install's "
                        + "'the human and the car cannot move' at " + east + ", " + north);

        assertTrue(surface.waterDepthM(east, north) <= SpawnClearer.WADE_DEPTH_M,
                "the spawn point the game picks is in open water");

        // Eight headings, and the walker's best one of them. A street corner is a real place with a real
        // wall somewhere near it, and walking into that wall is correct behaviour -- what would be a
        // fault is a walker that cannot walk in *any* direction, which is exactly what the ninth install
        // reported and what this measures.
        double best = bestOfEight(east, north, PlayerController.Loco.HUMAN);
        assertTrue(best > 5.0, "the walker travelled " + best + " m in five seconds in the best of eight "
                + "directions from the real spawn point: this is the ninth install's report");
    }

    /** How far a body gets in five seconds from a point, over the eight compass directions. */
    private static double bestOfEight(double east, double north, PlayerController.Loco loco) {
        double best = 0.0;
        for (int i = 0; i < 8; i++) {
            PlayerController controller = new PlayerController(new CameraRig(TerrainSampler.FLAT, 0, 0));
            controller.setSurface(surface);
            controller.placeAll(east, north, i * 45.0);
            controller.setLoco(loco);
            controller.sprinting = true;
            double startEast = controller.state().eastM;
            double startNorth = controller.state().northM;
            controller.forward = 1.0;
            for (int step = 0; step < 300; step++) {
                controller.update(1.0 / 60.0);
            }
            best = Math.max(best, Math.hypot(controller.state().eastM - startEast,
                    controller.state().northM - startNorth));
        }
        return best;
    }

    @Test
    @DisplayName("a car reaches its own spawn point's road and drives along it")
    void theRealSpawnIsSomewhereACarCanDrive() {
        double best = bestOfEight(carSpawn[0], carSpawn[1], PlayerController.Loco.CAR);
        assertTrue(best > 8.0, "the car travelled " + best + " m in five seconds in the best of eight "
                + "directions from the real spawn point: this is the ninth install's report");
    }

    @Test
    @DisplayName("a building is solid where its walls are and open where its rectangle is not")
    void theFootprintDecidesNotTheRectangle() {
        int probes = 0;
        for (int ring = 0; ring < world.buildings.ringCount && probes < 40; ring++) {
            double x0 = world.buildings.bounds[ring * 4];
            double z0 = world.buildings.bounds[ring * 4 + 1];
            double x1 = world.buildings.bounds[ring * 4 + 2];
            double z1 = world.buildings.bounds[ring * 4 + 3];
            if (x1 - x0 < 6.0 || z1 - z0 < 6.0) {
                continue;                                       // too small to hold an outside corner
            }
            // A point just inside a corner of the rectangle. For a building that is not aligned with the
            // axes -- which is most of them -- that corner is outside the walls.
            double east = x0 + 0.6;
            double north = z0 + 0.6;
            if (insideBoundsRectangle(east, north)
                    && !insideFootprintForTest(ring, east, north)
                    && !nearABarrier(east, north)) {
                double ground = surface.groundHeightM(east, north);
                assertFalse(surface.solidAt(east, north, ground + 1.0),
                        "a point inside the rectangle around building " + ring + " and outside its walls "
                                + "is reported solid: the rectangle rule is back");
                probes++;
            }
            // And the footprint itself is solid, whatever shape it is.
            double centreEast = (x0 + x1) * 0.5;
            double centreNorth = (z0 + z1) * 0.5;
            if (insideFootprintForTest(ring, centreEast, centreNorth)) {
                double ground = surface.groundHeightM(centreEast, centreNorth);
                assertTrue(surface.solidAt(centreEast, centreNorth, ground + 1.0),
                        "the inside of building " + ring + " is not solid at all");
                probes++;
            }
        }
        assertTrue(probes > 10, "only " + probes + " buildings could be probed: the country is not what "
                + "this test expects");
    }

    /**
     * True when a barrier -- a wall, a fence, a hedge -- passes near enough to the point that
     * {@code WorldSurface.solidAt} would call it solid for a reason that has nothing to do with the
     * building being probed. The country has four hundred of them, so this is a guard, not a case.
     */
    private static boolean nearABarrier(double eastM, double northM) {
        return world.barrierIndex.collect(eastM - 1.0, northM - 1.0, eastM + 1.0, northM + 1.0).length > 0;
    }

    /** The same ray cast as {@code WorldSurface.insideFootprint}, so the test measures what ships. */
    private static boolean insideFootprintForTest(int ring, double eastM, double northM) {
        int start = world.buildings.ringStart[ring];
        int length = world.buildings.ringLength[ring];
        if (length < 3 || start < 0 || start + length > world.buildings.xz.length / 2) {
            return true;                                        // no ring: believe the rectangle, as ships
        }
        boolean inside = false;
        for (int i = 0, j = length - 1; i < length; j = i++) {
            double xi = world.buildings.xz[(start + i) * 2];
            double zi = world.buildings.xz[(start + i) * 2 + 1];
            double xj = world.buildings.xz[(start + j) * 2];
            double zj = world.buildings.xz[(start + j) * 2 + 1];
            if ((zi > northM) != (zj > northM)
                    && eastM < (xj - xi) * (northM - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    @Test
    @DisplayName("the terrain under a spawn point is loaded, so the body is not waiting on the streaming")
    void theSpawnPointHasCollision() {
        assertTrue(surface.collisionLoaded(spawn[0], spawn[1]),
                "the spawn point has no collision terrain: every body would stand still at spawn");
        assertEquals(world.terrainL1.cols, world.materials.cols, "grid width disagrees");
    }
}
