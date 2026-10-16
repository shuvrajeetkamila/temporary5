package ai.arena.qatar.game;

import ai.arena.qatar.world.GameManifest;
import ai.arena.qatar.world.Roads;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Traffic, checked against the roads it is driving on.
 *
 * <p>The country was empty until this round: 180,000 ways, and every one of them drawn for nobody. What is
 * asserted here is the four things that make a handful of cars on those ways honest rather than decorative
 * -- they are <b>on</b> the roads, they <b>move</b> at the compiled speed, they stay <b>near the player</b>,
 * and they are <b>the same cars every time</b>, which is what makes any of the other three testable at all.
 *
 * <p>The fixture is small -- two usable ways, 1.5 km and 1.1 km long -- and that is enough: the rules are
 * about a car and one way, and the country has 180,000 of them.
 */
final class TrafficTest {

    private static final double DT = 1.0 / 60.0;

    private static WorldData world;

    @BeforeAll
    static void loadFixture() throws IOException {
        Path candidate = Paths.get("src/test/resources/synthetic_world");
        if (!Files.isDirectory(candidate)) {
            candidate = Paths.get("core/src/test/resources/synthetic_world");
        }
        assertTrue(Files.isDirectory(candidate), "the fixture is missing: " + candidate);
        Path fixture = candidate;
        GameManifest manifest = GameManifest.parse(
                new String(Files.readAllBytes(fixture.resolve("manifest.json")), StandardCharsets.UTF_8));
        world = WorldData.fromSource(manifest, name -> {
            try {
                return Files.readAllBytes(fixture.resolve(name));
            } catch (IOException e) {
                throw new IllegalStateException("fixture file unreadable: " + name, e);
            }
        });
    }

    /** The middle of the fixture, where both of its usable ways are. */
    private static final double CENTRE_E = 800.0;
    private static final double CENTRE_N = 800.0;

    private static Traffic fleet() {
        Traffic traffic = new Traffic();
        traffic.update(world, CENTRE_E, CENTRE_N, DT);
        return traffic;
    }

    // ---------------------------------------------------------------- on the road

    @Test
    @DisplayName("traffic appears on the fixture's roads, and nowhere else")
    void carsAreOnTheRoads() {
        Traffic traffic = fleet();
        assertTrue(traffic.count() > 0, "the country is empty: no car was put on either way");
        assertTrue(traffic.count() <= Traffic.MAX_CARS, "the fleet is bigger than its own limit");
        for (int i = 0; i < traffic.count(); i++) {
            Traffic.Car car = traffic.car(i);
            assertTrue(car.line >= 0 && car.line < world.roads.count, "a car has no way under it");
            assertTrue(world.roads.roadClass[car.line] <= Traffic.MAX_ROAD_CLASS,
                    "a car was put on a road traffic does not use, class "
                            + world.roads.roadClass[car.line]);
            assertFalse(world.roads.isBridge(car.line) || world.roads.isTunnel(car.line),
                    "a car was put on a bridge or in a tunnel, where its height is a guess");
            // On its own way's centre-line, offset into its own lane: within half the width plus the
            // offset of the line itself, and never further than the lane offset allows.
            double distance = distanceToWay(car.line, car.eastM, car.northM);
            assertTrue(distance <= car.laneOffsetM + 0.01,
                    "car " + i + " is " + distance + " m off the centre-line it is driving, with a lane "
                            + "offset of " + car.laneOffsetM);
        }
    }

    @Test
    @DisplayName("a car drives at the speed the way was compiled with, and stays on the way")
    void carsMoveAlongTheirOwnWay() {
        Traffic traffic = fleet();
        assertTrue(traffic.count() > 0, "no cars at all");
        int line = traffic.car(0).line;
        double speed = traffic.car(0).speedMS;
        double expected = Math.max(Traffic.MIN_SPEED_MS,
                world.roads.speedKph[line] / 3.6 * Traffic.SPEED_FRACTION);
        assertEquals(expected, speed, 0.001, "the car's speed is not the way's own limit");
        assertTrue(speed >= Traffic.MIN_SPEED_MS, "a car is crawling: " + speed + " m/s");

        // One second of frames. The fleet is full, so nothing is spawned and -- the fixture's ways being
        // longer than a second's drive -- nothing is dropped either: the car in the first slot is the same
        // car, and it has travelled what its own speed says it should have.
        double startEast = traffic.car(0).eastM;
        double startNorth = traffic.car(0).northM;
        for (double t = 0; t < 1.0; t += DT) {
            traffic.update(world, CENTRE_E, CENTRE_N, DT);
        }
        assertTrue(traffic.count() > 0, "the fleet emptied itself while it drove");
        Traffic.Car after = traffic.car(0);
        assertEquals(line, after.line, "the first car changed road without being replaced");
        double travelled = Math.hypot(after.eastM - startEast, after.northM - startNorth);
        assertTrue(travelled > speed * 0.5, "a car that should have driven " + speed
                + " m in a second drove " + travelled);
        assertTrue(travelled < speed * 1.5, "a car drove " + travelled + " m in a second at " + speed
                + " m/s");
        // And it is still on its own way: driving is not leaving.
        assertTrue(distanceToWay(after.line, after.eastM, after.northM) <= after.laneOffsetM + 0.01,
                "the car left its own road while driving");
    }

    @Test
    @DisplayName("cars follow the ground and turn with the road")
    void carsSitOnTheGroundAndFaceTheWay() {
        Traffic traffic = fleet();
        assertTrue(traffic.count() > 0, "no cars at all");
        for (int i = 0; i < traffic.count(); i++) {
            Traffic.Car car = traffic.car(i);
            double ground = world.terrainL1.heightBilinear(world.globalEast(car.eastM),
                    world.globalNorth(car.northM));
            assertEquals(ground, car.heightM, 0.01, "car " + i + " is not standing on the ground");
            assertTrue(car.headingDegrees >= 0.0 && car.headingDegrees < 360.0,
                    "a heading outside the compass: " + car.headingDegrees);
            // The heading is the way's own direction where the car is: the local heading, not the whole
            // way's, which on a road that turns would be wrong by the turn.
            double forwardEast = Math.sin(Math.toRadians(car.headingDegrees));
            double forwardNorth = Math.cos(Math.toRadians(car.headingDegrees));
            double[] direction = localDirection(car);
            double agreement = forwardEast * direction[0] + forwardNorth * direction[1];
            assertTrue(agreement > 0.999, "car " + i + " is not facing along the way it is driving: "
                    + agreement);
        }
    }

    // ---------------------------------------------------------------- near the player

    @Test
    @DisplayName("every car is within the spawn radius of the player, and none survives leaving it")
    void theFleetStaysNearThePlayer() {
        Traffic traffic = fleet();
        assertTrue(traffic.count() > 0, "no cars at all");
        for (int i = 0; i < traffic.count(); i++) {
            Traffic.Car car = traffic.car(i);
            double distance = Math.hypot(car.eastM - CENTRE_E, car.northM - CENTRE_N);
            assertTrue(distance <= Traffic.SPAWN_RADIUS_M + 1.0,
                    "a car was spawned " + distance + " m from the player");
        }
        // Now move the player five kilometres east, past the end of the fixture's roads: the old fleet is
        // out of range and must be dropped rather than driven along beside the player for ever. The
        // fixture's ways do not reach that far, so what replaces them is nothing -- and nothing is the right
        // answer, which is the other half of this test.
        double farEast = CENTRE_E + 5000.0;
        for (int i = 0; i < 4; i++) {
            traffic.update(world, farEast, CENTRE_N, DT);
        }
        for (int i = 0; i < traffic.count(); i++) {
            Traffic.Car car = traffic.car(i);
            double distance = Math.hypot(car.eastM - farEast, car.northM - CENTRE_N);
            assertTrue(distance <= Traffic.SPAWN_RADIUS_M + 1.0,
                    "a car from the old place is still in the fleet, " + distance + " m from the player");
        }
        assertEquals(0, traffic.count(), "cars are still driving the roads the player left behind");
    }

    @Test
    @DisplayName("a way with no room for traffic gets none")
    void aWayTooShortForTrafficCarriesNone() {
        // The fixture's third way is 2.5 km of service road; the class cut is 7, so the question here is
        // the class, not the length: a car on a driveway is a car in somebody's garden.
        Traffic traffic = new Traffic();
        for (int i = 0; i < 30; i++) {
            traffic.update(world, CENTRE_E, CENTRE_N, DT);
        }
        for (int i = 0; i < traffic.count(); i++) {
            int line = traffic.car(i).line;
            assertTrue(world.roads.roadClass[line] <= Traffic.MAX_ROAD_CLASS,
                    "traffic is driving a class " + world.roads.roadClass[line] + " way");
        }
    }

    // ---------------------------------------------------------------- the same country every time

    @Test
    @DisplayName("the same place always yields the same cars, in the same order")
    void theFleetIsDeterministic() {
        Traffic first = fleet();
        Traffic second = fleet();
        assertEquals(first.count(), second.count(), "two runs disagreed about how many cars there are");
        for (int i = 0; i < first.count(); i++) {
            assertEquals(first.car(i).line, second.car(i).line, "car " + i + " is on a different way");
            assertEquals(first.car(i).eastM, second.car(i).eastM, 1e-9, "car " + i + " is elsewhere");
            assertEquals(first.car(i).northM, second.car(i).northM, 1e-9, "car " + i + " is elsewhere");
        }
    }

    @Test
    @DisplayName("the fleet reports itself as contacts a body can be pushed out of")
    void contactsAreFourNumbersPerCar() {
        Traffic traffic = fleet();
        double[] contacts = new double[Traffic.MAX_CARS * 4];
        int count = traffic.contactsInto(contacts);
        assertEquals(traffic.count(), count, "the contacts do not match the fleet");
        assertTrue(count > 0, "no contacts at all");
        for (int i = 0; i < count; i++) {
            assertEquals(traffic.car(i).eastM, contacts[i * 4], 1e-9, "contact " + i + " is elsewhere");
            assertEquals(traffic.car(i).northM, contacts[i * 4 + 1], 1e-9, "contact " + i + " is elsewhere");
            assertTrue(contacts[i * 4 + 3] > 0.5, "a contact with no radius is not a contact");
        }
        // And a world with no roads in reach reports none rather than throwing.
        Traffic empty = new Traffic();
        empty.update(world, -50_000.0, -50_000.0, DT);
        assertEquals(0, empty.count(), "cars appeared in a country with no roads in reach");
        assertEquals(0, empty.contactsInto(contacts), "a contact was reported for a car that is not there");
    }

    // ---------------------------------------------------------------- helpers

    /** The direction of the way under a car at the car's own place, as a unit vector in (east, north). */
    private static double[] localDirection(Traffic.Car car) {
        Roads roads = world.roads;
        int start = roads.lineStart[car.line];
        int from = start + Math.max(0, car.vertex - 1);
        int to = start + Math.min(roads.lineLength[car.line] - 1, car.vertex);
        double dx = roads.xz[to * 2] - roads.xz[from * 2];
        double dz = roads.xz[to * 2 + 1] - roads.xz[from * 2 + 1];
        double length = Math.hypot(dx, dz);
        return length < 1e-9 ? new double[]{0, 0} : new double[]{dx / length, dz / length};
    }

    /** The distance from a point to a way's own centre-line, in metres. */
    private static double distanceToWay(int line, double eastM, double northM) {
        Roads roads = world.roads;
        int start = roads.lineStart[line];
        int points = roads.lineLength[line];
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < points; i++) {
            double ax = roads.xz[(start + i) * 2];
            double az = roads.xz[(start + i) * 2 + 1];
            double bx = roads.xz[(start + i + 1) * 2];
            double bz = roads.xz[(start + i + 1) * 2 + 1];
            double dx = bx - ax;
            double dz = bz - az;
            double lengthSquared = dx * dx + dz * dz;
            double t = lengthSquared < 1e-9 ? 0.0
                    : Math.max(0.0, Math.min(1.0, ((eastM - ax) * dx + (northM - az) * dz)
                            / lengthSquared));
            best = Math.min(best, Math.hypot(eastM - (ax + dx * t), northM - (az + dz * t)));
        }
        return best;
    }
}
