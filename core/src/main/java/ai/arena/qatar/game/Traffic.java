package ai.arena.qatar.game;

import ai.arena.qatar.vehicle.CarBody;
import ai.arena.qatar.vehicle.Loco;
import ai.arena.qatar.vehicle.VehicleContact;
import ai.arena.qatar.vehicle.VehicleState;
import ai.arena.qatar.world.FeatureIndex;
import ai.arena.qatar.world.Roads;
import ai.arena.qatar.world.TerrainGrid;
import ai.arena.qatar.world.WorldData;

import java.util.ArrayList;
import java.util.List;

/**
 * Traffic: a handful of cars driving the compiled roads around the player.
 *
 * <p>Until this round the country was empty. It had 180,000 ways, 187,000 buildings, a skyline, a coast,
 * two hundred thousand palms and one person in it, and every road in it was drawn for nobody: the Salwa
 * road at noon had as much traffic as the middle of the Rub' al Khali. This class is the smallest thing
 * that fixes that honestly, and it is deliberately not a traffic simulation.
 *
 * <h2>What it is</h2>
 *
 * <ul>
 *   <li><b>Way-bound.</b> A car lives on one compiled way: its own segment index, its own distance into
 *       that segment. It drives the way's own centre-line, at the way's own compiled speed limit times
 *       {@link #SPEED_FRACTION}, offset to the right of travel by its own lane offset -- Qatar drives on
 *       the right, and a car in the middle of the road looks like a car in the middle of the road. It
 *       never leaves the way, never turns at a junction and never chooses a destination, because a turn
 *       needs a graph and this is not a graph: it is the country's own lines, driven along.</li>
 *   <li><b>Near the player and nowhere else.</b> {@link #MAX_CARS} of them, spawned on ways inside
 *       {@link #SPAWN_RADIUS_M} and dropped once they are {@link #DESPAWN_RADIUS_M} away. A car the player
 *       cannot see is a car the phone pays for: the whole fleet costs one mesh and fourteen draw calls,
 *       and it costs nothing at the other end of the country.</li>
 *   <li><b>Deterministic.</b> Which way a car appears on, where along it, and how fast it goes are all
 *       pure functions of a counter and a hash. Two runs of the game put the same cars on the same roads
 *       in the same order, which is the only way a test can assert anything about them at all.</li>
 *   <li><b>On the ground.</b> Height is the compiled terrain under the car, so traffic follows the dunes
 *       out of Doha and the causeway over the bay. Bridges and tunnels are skipped rather than driven at
 *       the height of whatever is underneath them: a car floating over the water at the height of the
 *       seabed is worse than a road with no cars on it.</li>
 * </ul>
 *
 * <h2>What it is not</h2>
 *
 * <p>Not simulated, not solid, and not a pathfinder. Cars do not collide with each other, do not queue,
 * do not stop for the player and do not avoid the player's car: they are geometry that moves, and the one
 * thing they do to the player is in {@code PlayerController.resolveTrafficContacts}, where a car that
 * reaches the player's own body pushes it out of the way rather than passing through it. Everything else
 * -- lanes that change, junctions, lights, a driver that brakes -- is a bigger game than this one, and the
 * report says so rather than pretending otherwise.
 */
public final class Traffic {

    /** How many cars may exist at once. Fourteen is the point where a street looks lived in and cheap. */
    public static final int MAX_CARS = 14;

    /** Ways within this of the player are candidates for a car, in metres. */
    public static final double SPAWN_RADIUS_M = 750.0;

    /** A car this far from the player is dropped, in metres, whatever it is doing. */
    public static final double DESPAWN_RADIUS_M = 1100.0;

    /** The least important road traffic uses: 7 is {@code residential}, the same cut the far layer uses. */
    public static final int MAX_ROAD_CLASS = 7;

    /** A way shorter than this never carries a car: a car that crosses it in a second is a glitch. */
    public static final double MIN_WAY_LENGTH_M = 60.0;

    /** How much of the compiled speed limit traffic actually drives at. */
    public static final double SPEED_FRACTION = 0.75;

    /** Below this a car is not crawling through a residential street, it is stopped, so it is not spawned. */
    public static final double MIN_SPEED_MS = 6.0;

    /** The lane offset as a fraction of the way's own half-width, and its ceiling in metres. */
    public static final double LANE_OFFSET_FRACTION = 0.28;
    public static final double LANE_OFFSET_MAX_M = 3.5;

    /** How many spawn attempts per update before giving up: a phone should not search for a road for ever. */
    public static final int SPAWN_ATTEMPTS = 24;

    /** One car: which way it is on, where along it, and what it is doing. */
    public static final class Car {
        /** The compiled way this car is driving, or -1 when it is between ways and about to be replaced. */
        public int line = -1;
        /** The vertex it is heading for: the interval is [vertex - 1, vertex) of the way's own line. */
        public int vertex;
        /** Metres it has travelled into that interval. */
        public double alongIntoM;
        public double eastM;
        public double northM;
        public double heightM;
        public double headingDegrees;
        public double speedMS;
        /** Degrees the wheels have turned since the car appeared: rolling without slipping, as ever. */
        public double spinDegrees;
        /** The lane offset actually used, in metres, to the right of the direction of travel. */
        public double laneOffsetM;
    }

    private final List<Car> cars = new ArrayList<>();
    private final List<Integer> candidates = new ArrayList<>();
    private long spawnCounter;

    /** How many cars are on the road right now. */
    public int count() {
        return cars.size();
    }

    public Car car(int index) {
        return cars.get(index);
    }

    public List<Car> cars() {
        return cars;
    }

    /**
     * Empties the fleet: a teleport moves the player, and the traffic around the place they left is not
     * traffic around the place they arrived at.
     */
    public void clear() {
        cars.clear();
        candidates.clear();
    }

    /** The hash the fleet's own decisions are drawn from: pure, so the fleet is reproducible. */
    public static double hash01(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }

    /**
     * Moves every car, drops the ones that have left, and fills the fleet back up around the player.
     *
     * <p>Called once a frame with the frame's own delta, so a car's motion is its speed times the time
     * that passed and nothing else -- the same rule the vehicle physics learned this round, for the same
     * reason: a per-frame step would make traffic on a 120 Hz phone twice as fast as traffic on a 60 Hz
     * one, and nobody would ever think to look here for it.
     */
    public void update(WorldData world, double centreEast, double centreNorth, double dt) {
        Roads roads = world == null ? null : world.roads;
        if (roads == null || world.roadIndex == null || dt <= 0.0) {
            cars.clear();
            return;
        }
        for (int i = cars.size() - 1; i >= 0; i--) {
            Car car = cars.get(i);
            if (!advance(world, roads, car, dt)
                    || distance(car.eastM, car.northM, centreEast, centreNorth) > DESPAWN_RADIUS_M) {
                cars.remove(i);
            }
        }
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS && cars.size() < MAX_CARS; attempt++) {
            if (!spawnOne(world, roads, centreEast, centreNorth)) {
                return;                                     // no roads in reach: nothing more to try
            }
        }
    }

    /**
     * Drives one car forward along its own way, and answers whether it is still on it.
     *
     * <p>A car that reaches the end of its way is finished rather than turned around: the compiled ways
     * have a direction, a one-way street has only one, and a car that reversed at every end would be
     * driving the wrong way up half the roads in Doha.
     */
    private boolean advance(WorldData world, Roads roads, Car car, double dt) {
        if (car.line < 0 || car.line >= roads.count) {
            return false;
        }
        int start = roads.lineStart[car.line];
        int points = roads.lineLength[car.line];
        double travelled = car.speedMS * dt;
        while (travelled > 0.0) {
            if (car.vertex >= points) {
                return false;
            }
            double ax = roads.xz[(start + car.vertex - 1) * 2];
            double az = roads.xz[(start + car.vertex - 1) * 2 + 1];
            double bx = roads.xz[(start + car.vertex) * 2];
            double bz = roads.xz[(start + car.vertex) * 2 + 1];
            double length = Math.hypot(bx - ax, bz - az);
            if (length < 0.05) {
                car.vertex++;
                car.alongIntoM = 0.0;
                continue;
            }
            double remaining = length - car.alongIntoM;
            if (travelled < remaining) {
                car.alongIntoM += travelled;
                travelled = 0.0;
            } else {
                travelled -= remaining;
                car.vertex++;
                car.alongIntoM = 0.0;
            }
            place(world, roads, car, start, ax, az, bx, bz, length);
        }
        // The wheels: one radius of travel is one radian of rotation, exactly as the player's own car has
        // it, so traffic and the car the player is driving turn their wheels at the same rate.
        car.spinDegrees = VehicleState.wrap(car.spinDegrees
                + Math.toDegrees(car.speedMS * dt / CarBody.WHEEL_RADIUS_M));
        return true;
    }

    /** Puts a car on the ground at its place on its own segment, offset into its lane and facing along it. */
    private void place(WorldData world, Roads roads, Car car, int start, double ax, double az, double bx,
                       double bz, double length) {
        double dx = bx - ax;
        double dz = bz - az;
        double t = Math.max(0.0, Math.min(1.0, car.alongIntoM / length));
        double east = ax + dx * t;
        double north = az + dz * t;
        // Right of travel: for a heading of (dx, dz) the right-hand side is (dz, -dx), which is the side a
        // Qatari driver is on. The offset is a fraction of the way's own half-width so a lane on a
        // fourteen-metre road is further out than a lane on a six-metre one, with a ceiling: past three and
        // a half metres the car is in the next lane, or in a wall.
        double half = Math.max(1.5, roads.widthM[car.line] * 0.5);
        car.laneOffsetM = Math.min(LANE_OFFSET_MAX_M, half * (1.0 - LANE_OFFSET_FRACTION));
        double scale = length < 1e-6 ? 0.0 : car.laneOffsetM / length;
        car.eastM = east + dz * scale;
        car.northM = north - dx * scale;
        car.headingDegrees = VehicleState.wrap(Math.toDegrees(Math.atan2(dx, dz)));
        car.heightM = ground(world, car.eastM, car.northM);
    }

    /** The compiled ground under a point, in bundle metres. */
    private static double ground(WorldData world, double eastM, double northM) {
        TerrainGrid terrain = world.terrainL1 != null ? world.terrainL1 : world.terrainL0;
        if (terrain == null) {
            return 0.0;
        }
        return terrain.heightBilinear(world.globalEast(eastM), world.globalNorth(northM));
    }

    /**
     * Tries to put one car on a road near the player, and answers whether there is anywhere to keep trying.
     *
     * <p>The candidate list is rebuilt on every attempt rather than cached: the player is moving, the ways
     * in reach change as they move, and a cached list would spawn cars on streets they left ten minutes
     * ago. The index makes it a handful of grid cells, and it runs at most twice a second because the
     * fleet is full the rest of the time.
     */
    private boolean spawnOne(WorldData world, Roads roads, double centreEast, double centreNorth) {
        FeatureIndex index = world.roadIndex;
        candidates.clear();
        index.query(centreEast - SPAWN_RADIUS_M, centreNorth - SPAWN_RADIUS_M, centreEast + SPAWN_RADIUS_M,
                centreNorth + SPAWN_RADIUS_M, line -> {
                    if (line < 0 || line >= roads.count) {
                        return true;
                    }
                    if (roads.roadClass[line] > MAX_ROAD_CLASS || roads.isBridge(line)
                            || roads.isTunnel(line) || roads.lineLength[line] < 2) {
                        return true;
                    }
                    // The way's own bounding box, not the length of its polyline: the box is two
                    // subtractions, and the length is a walk over every vertex of every way in reach on a
                    // frame where the fleet is filling. A way whose box is shorter than the limit cannot be
                    // longer than the limit, which is all this test needs to be.
                    int box = line * 4;
                    if (Math.hypot(roads.bounds[box + 2] - roads.bounds[box],
                            roads.bounds[box + 3] - roads.bounds[box + 1]) < MIN_WAY_LENGTH_M) {
                        return true;
                    }
                    candidates.add(line);
                    return true;
                });
        if (candidates.isEmpty()) {
            return false;
        }
        long pick = spawnCounter++;
        int line = candidates.get((int) (hash01(pick * 3 + 1) * candidates.size()) % candidates.size());
        int start = roads.lineStart[line];
        int points = roads.lineLength[line];
        int vertex = 1 + (int) (hash01(pick * 3 + 2) * (points - 1));
        if (vertex >= points) {
            vertex = points - 1;
        }
        Car car = new Car();
        car.line = line;
        car.vertex = vertex;
        car.alongIntoM = 0.0;
        double speed = Math.max(MIN_SPEED_MS, roads.speedKph[line] / 3.6 * SPEED_FRACTION);
        car.speedMS = speed;
        // Placed on its own segment before it is added, so a car is never in the fleet at the origin.
        double ax = roads.xz[(start + vertex - 1) * 2];
        double az = roads.xz[(start + vertex - 1) * 2 + 1];
        double bx = roads.xz[(start + vertex) * 2];
        double bz = roads.xz[(start + vertex) * 2 + 1];
        double length = Math.hypot(bx - ax, bz - az);
        car.alongIntoM = length * hash01(pick * 3 + 3) * 0.5;
        place(world, roads, car, start, ax, az, bx, bz, length);
        // And the way it is on has to have somewhere for it to go, or the fleet fills with cars that are
        // removed the frame after they appear.
        // A pick that lands out of range or on a degenerate segment is not a failure of the search: there
        // were candidates, this one was not usable, and the next attempt may do better. Returning false
        // here -- which the first draft did -- stops the whole fill on the first bad draw, so a fleet
        // stays at one car on a frame when it could have had fourteen.
        if (car.vertex < points && length >= 2.0
                && distance(car.eastM, car.northM, centreEast, centreNorth) <= SPAWN_RADIUS_M) {
            cars.add(car);
        }
        return true;
    }

    /** A car's own distance to a point, in metres. */
    private static double distance(double eastM, double northM, double otherEastM, double otherNorthM) {
        return Math.hypot(eastM - otherEastM, northM - otherNorthM);
    }

    /**
     * The traffic as the contacts a body can be pushed out of: four numbers per car.
     *
     * <p>Flat, and a plain array rather than anything clever, because this is read once a frame and written
     * once a frame and the allocation is the thing that would show up on a phone: the caller owns the
     * array and this fills it.
     *
     * @return how many contacts were written
     */
    public int contactsInto(double[] out) {
        int written = 0;
        for (Car car : cars) {
            if (written * 4 + 4 > out.length) {
                break;
            }
            out[written * 4] = car.eastM;
            out[written * 4 + 1] = car.northM;
            out[written * 4 + 2] = car.heightM;
            out[written * 4 + 3] = VehicleContact.radiusM(Loco.CAR);
            written++;
        }
        return written;
    }
}
