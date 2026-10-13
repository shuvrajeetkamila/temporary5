package ai.arena.qatar.game;

import ai.arena.qatar.hud.MapRaster;
import ai.arena.qatar.physics.SurfaceMaterial;
import ai.arena.qatar.vehicle.CarBody;
import ai.arena.qatar.vehicle.HumanBody;
import ai.arena.qatar.vehicle.Surface;
import ai.arena.qatar.vehicle.VehicleInput;
import ai.arena.qatar.vehicle.VehicleState;
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
 * Walls are walls. A city the size of Doha is most of what the player is walking and driving through, so
 * "can I walk through a building?" is answered against the committed bundle rather than by eye: the
 * compiled footprints are the collision test, and the walking and driving bodies ask them before they
 * move.
 *
 * <p>The last two tests hold a real car and a real walker to it, using a surface with one wall in it.
 */
final class BuildingCollisionTest {

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
        GridTerrainSampler sampler = new GridTerrainSampler(world.terrainL1, world.materials, origin);
        surface = new WorldSurface(sampler, world);
    }

    private static byte[] read(Path dir, String name) {
        try {
            return Files.readAllBytes(dir.resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException("fixture file unreadable: " + name, e);
        }
    }

    @Test
    @DisplayName("a compiled building is solid from the ground to its roof and empty above it")
    void buildingsAreSolid() {
        assertTrue(world.buildings.ringCount > 0, "the fixture has no buildings");
        // The centre of the first footprint's bounding box, and its own roof height.
        double east = (world.buildings.bounds[0] + world.buildings.bounds[2]) * 0.5;
        double north = (world.buildings.bounds[1] + world.buildings.bounds[3]) * 0.5;
        double roof = world.buildings.heightM[0];
        assertTrue(roof > 2.0, "the fixture building is not a building: " + roof);

        double ground = surface.groundHeightM(east, north);
        assertTrue(surface.solidAt(east, north, ground + 0.1),
                "a person standing in a building footprint is not blocked");
        assertTrue(surface.solidAt(east, north, ground + Math.max(0.2, roof - 0.2)),
                "the inside of the building is not solid below the roof");
        assertFalse(surface.solidAt(east, north, ground + roof + 2.0),
                "the building is solid above its own roof");
    }

    @Test
    @DisplayName("open ground is not blocked")
    void openGroundIsNotSolid() {
        // The fixture's lake and the ground around the buildings are both open.
        assertFalse(surface.solidAt(1400.0, 1400.0, 0.0), "empty ground reported as solid");
        assertFalse(surface.solidAt(1450.0, 1500.0, 0.0), "empty ground reported as solid");
    }

    @Test
    @DisplayName("a wall is solid above the ground but not at forty metres up")
    void solidityIsAnAltitude() {
        Surface wall = wallAt(20.0);
        assertTrue(wall.solidAt(25.0, 0.0, 1.0), "the wall is not solid at head height");
        assertFalse(wall.solidAt(25.0, 0.0, 40.0), "the wall is solid forty metres up");
        assertFalse(wall.solidAt(5.0, 0.0, 1.0), "empty air is solid");
    }

    @Test
    @DisplayName("a walker stops at a building instead of walking into it")
    void walkerStopsAtABuilding() {
        Surface wall = wallAt(20.0);
        HumanBody human = new HumanBody();
        VehicleState state = new VehicleState();
        human.spawn(state, 0, 0, wall, 90.0);
        VehicleInput input = new VehicleInput();
        input.throttle = 1.0;
        for (double t = 0; t < 30.0; t += DT) {
            human.step(state, input, wall, DT);
        }
        assertTrue(state.eastM < 20.0, "the walker walked into the building: " + state.eastM);
        assertTrue(state.eastM > 15.0, "the walker stopped far too early: " + state.eastM);
        assertTrue(state.blocked, "the walker did not report being blocked");
    }

    @Test
    @DisplayName("a car stops at a building instead of driving into it")
    void carStopsAtABuilding() {
        Surface wall = wallAt(60.0);
        CarBody car = new CarBody();
        VehicleState state = new VehicleState();
        car.spawn(state, 0, 0, wall, 90.0);
        VehicleInput input = new VehicleInput();
        input.throttle = 1.0;
        for (double t = 0; t < 30.0; t += DT) {
            car.step(state, input, wall, DT);
        }
        assertTrue(state.eastM < 60.0, "the car drove into the building: " + state.eastM);
        assertTrue(state.eastM > 45.0, "the car stopped far too early: " + state.eastM);
    }

    /** Flat asphalt with a building that starts at {@code edgeEast} and is 12 m tall. */
    private static Surface wallAt(double edgeEast) {
        return new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return 0;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return SurfaceMaterial.ID_DRY_ASPHALT;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return 0;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }

            @Override
            public boolean solidAt(double eastM, double northM, double altitudeM) {
                return eastM >= edgeEast && altitudeM < 12.0;
            }
        };
    }

    @Test
    @DisplayName("the map still draws the country after all of this")
    void mapStillBuilds() {
        MapRaster raster = MapRaster.build(world, 256);
        assertTrue(raster.width > 100 && raster.height > 100, "the raster collapsed");
    }
}
