package ai.arena.qatar.game;

import ai.arena.qatar.vehicle.CarBody;
import ai.arena.qatar.vehicle.HumanBody;
import ai.arena.qatar.vehicle.VehicleInput;
import ai.arena.qatar.vehicle.VehicleState;
import ai.arena.qatar.world.Barriers;
import ai.arena.qatar.world.GameManifest;
import ai.arena.qatar.world.GridTerrainSampler;
import ai.arena.qatar.world.WorldData;
import ai.arena.qatar.world.WorldOrigin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walls, fences and hedges are what make a compound a compound, and a player who walks through them is
 * walking through the world.
 *
 * <p>Two things are checked against the committed fixture: that a barrier's bounding box is expressed
 * in the same frame as its own vertices, and that a person and a car are actually stopped by one. The
 * first is not academic -- the boxes used to be seeded from the barrier's absolute first vertex, so every
 * one of the country's barriers claimed to be at EPSG east 553,100 in a world 1600 m across, and no
 * chunk ever meshed one.
 */
final class BarrierCollisionTest {

    private static final double DT = 1.0 / 60.0;

    private static WorldData world;
    private static WorldSurface surface;

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
        WorldOrigin origin = new WorldOrigin(manifest.originEast, manifest.originNorth);
        surface = new WorldSurface(new GridTerrainSampler(world.terrainL1, world.materials, origin),
                world);
    }

    private static byte[] read(Path dir, String name) {
        try {
            return Files.readAllBytes(dir.resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException("fixture file unreadable: " + name, e);
        }
    }

    @Test
    @DisplayName("a barrier's box is in the same frame as its own line")
    void barrierBoundsAreBundleRelative() {
        assertTrue(world.barriers.count > 0, "the fixture has no barriers");
        for (int line = 0; line < world.barriers.count; line++) {
            float minE = world.barriers.bounds[line * 4];
            float minN = world.barriers.bounds[line * 4 + 1];
            float maxE = world.barriers.bounds[line * 4 + 2];
            float maxN = world.barriers.bounds[line * 4 + 3];
            assertTrue(minE <= maxE && minN <= maxN,
                    "barrier " + line + " has an inverted box: " + minE + ".." + maxE + ", "
                            + minN + ".." + maxN);
            assertTrue(minE >= -1.0 && maxE <= world.manifest.widthM + 1.0
                            && minN >= -1.0 && maxN <= world.manifest.heightM + 1.0,
                    "barrier " + line + " is outside the country it is in: " + minE + ".." + maxE
                            + ", " + minN + ".." + maxN);
            // The line's own vertices must be inside the box the index is built from.
            int start = world.barriers.lineStart[line];
            for (int i = 0; i < world.barriers.lineLength[line]; i++) {
                float e = world.barriers.xz[(start + i) * 2];
                float n = world.barriers.xz[(start + i) * 2 + 1];
                assertTrue(e >= minE - 0.01f && e <= maxE + 0.01f && n >= minN - 0.01f
                                && n <= maxN + 0.01f,
                        "barrier " + line + " has a vertex outside its own box");
            }
        }
    }

    @Test
    @DisplayName("a wall stops a walker, and a wall you are above is not in your way")
    void wallStopsAWalker() {
        int wall = wallLine();
        double[] mid = midPoint(wall);
        double south = clearGroundToTheSouth(mid[0], mid[1]);

        HumanBody human = new HumanBody();
        VehicleState state = new VehicleState();
        human.spawn(state, mid[0], south, surface, 0.0);
        VehicleInput input = new VehicleInput();
        input.throttle = 1.0;
        for (double t = 0; t < 40.0; t += DT) {
            human.step(state, input, surface, DT);
        }
        double ground = surface.groundHeightM(mid[0], mid[1]);
        assertTrue(state.northM < mid[1], "the walker is standing inside the wall: " + state.northM);
        assertTrue(state.northM > mid[1] - 2.0,
                "the walker stopped " + (mid[1] - state.northM) + " m short of the wall");
        assertTrue(state.blocked, "the walker did not report being stopped");

        // Chest height is stopped, head height above a 2.4 m wall is not: a helicopter is not blocked
        // by somebody's compound wall.
        assertTrue(surface.solidAt(mid[0], mid[1], ground + 1.4), "the wall is not solid at chest height");
        assertFalse(surface.solidAt(mid[0], mid[1], ground + world.barriers.heightM[wall] + 0.5),
                "the wall is solid above its own top");
        assertFalse(surface.solidAt(mid[0], south, ground + 1.4),
                "open ground in front of the wall is solid");
    }

    @Test
    @DisplayName("a hedge stops a car")
    void hedgeStopsACar() {
        int hedge = -1;
        for (int line = 0; line < world.barriers.count; line++) {
            if (world.barriers.lineLength[line] >= 2 && world.barriers.heightM[line] >= 1.5
                    && world.barriers.kind[line] != Barriers.KIND_GATE) {
                hedge = line;
            }
        }
        assertTrue(hedge >= 0, "the fixture has no barrier a car should be stopped by");

        double[] start = clearStartFor(hedge);
        CarBody car = new CarBody();
        VehicleState state = new VehicleState();
        car.spawn(state, start[0], start[1], surface, 90.0);      // heading east
        VehicleInput input = new VehicleInput();
        input.throttle = 1.0;
        for (double t = 0; t < 30.0; t += DT) {
            car.step(state, input, surface, DT);
        }

        double edge = world.barriers.xz[(world.barriers.lineStart[hedge] + 1) * 2];
        assertTrue(state.eastM < edge, "the car drove through the barrier: " + state.eastM);
        assertTrue(state.eastM > edge - 20.0, "the car stopped " + (edge - state.eastM)
                + " m short of the barrier");
    }

    /** The fixture's wall: the tallest line, which is the one that crosses the map east to west. */
    private static int wallLine() {
        int best = -1;
        double tallest = -1;
        for (int line = 0; line < world.barriers.count; line++) {
            if (world.barriers.heightM[line] > tallest && world.barriers.lineLength[line] >= 2) {
                tallest = world.barriers.heightM[line];
                best = line;
            }
        }
        assertTrue(best >= 0, "the fixture has no barrier with height");
        return best;
    }

    private static double[] midPoint(int line) {
        int start = world.barriers.lineStart[line];
        int end = start + world.barriers.lineLength[line] - 1;
        return new double[]{(world.barriers.xz[start * 2] + world.barriers.xz[end * 2]) * 0.5,
                (world.barriers.xz[start * 2 + 1] + world.barriers.xz[end * 2 + 1]) * 0.5};
    }

    /** A clear spot 4 m south of the wall's middle, so the walker has somewhere to start from. */
    private static double clearGroundToTheSouth(double east, double north) {
        for (double back = 4.0; back < 24.0; back += 1.0) {
            double spot = north - back;
            if (!surface.solidAt(east, spot, surface.groundHeightM(east, spot) + 1.4)) {
                return spot;
            }
        }
        throw new IllegalStateException("no clear ground south of the barrier");
    }

    /** A clear spot west of a barrier, with room to build up speed before hitting it. */
    private static double[] clearStartFor(int line) {
        int start = world.barriers.lineStart[line];
        int end = start + world.barriers.lineLength[line] - 1;
        double east = world.barriers.xz[start * 2];
        double north = (world.barriers.xz[start * 2 + 1] + world.barriers.xz[end * 2 + 1]) * 0.5;
        for (double back = 12.0; back < 60.0; back += 1.0) {
            double spot = east - back;
            if (!surface.solidAt(spot, north, surface.groundHeightM(spot, north) + 1.0)) {
                return new double[]{spot, north};
            }
        }
        throw new IllegalStateException("no clear ground west of the barrier");
    }
}
