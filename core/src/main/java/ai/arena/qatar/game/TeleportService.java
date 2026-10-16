package ai.arena.qatar.game;

import ai.arena.qatar.hud.MapRaster;
import ai.arena.qatar.world.Points;
import ai.arena.qatar.world.Roads;
import ai.arena.qatar.world.WorldData;

/**
 * Turns "the player tapped here on the map" into "put the player there".
 *
 * <p>A tap on a map is approximate: the full map is a few thousand pixels for a country tens of
 * kilometres wide, so a finger is worth a hundred metres. Dropping the player at the raw tap would
 * regularly mean standing in the sea or inside a building, so the service snaps the target to the
 * nearest road within a tolerance (measured against the real compiled road geometry, not the raster),
 * and falls back to the tapped point only when it is on land and nothing is near.
 *
 * <p>Deterministic and free of GL, so the contract can be tested: a tap in the middle of Doha Bay must
 * not teleport the player into the water.
 */
public final class TeleportService {

    /**
     * How far a tap may be snapped to reach a road, in metres, when the tapped place itself cannot be
     * stood on.
     *
     * <p>The wide radius is the rescue, not the rule: it is what turns a tap on the roof of a mall or in
     * the middle of a lagoon into a place on the road beside it. See {@link #ROAD_SNAP_M} for the radius
     * that applies when the tap is somewhere a person could actually stand.
     */
    public static final double SNAP_RADIUS_M = 900.0;
    /** Roads the player can actually be placed on: anything from a track up to a motorway. */
    private static final int MIN_ROAD_CLASS = 10;
    /**
     * How far a tap may be moved to reach a road when the tapped place is standable, in metres.
     *
     * <p>This was the whole 900 m radius for every tap, which is what "the teleport happens abnormally"
     * looked like from the player's seat: tapping an empty stretch of desert beside a highway sent them
     * to the highway, hundreds of metres from where the finger went down, with nothing on the screen
     * saying why. A tap that can be honoured is honoured; a road is only preferred when the finger is
     * genuinely on or beside one.
     */
    public static final double ROAD_SNAP_M = 150.0;
    /**
     * Spawn points within this distance of the tap win over the tap itself: they are places the data
     * says vehicles and people belong -- a car park, a harbour, an apron, a runway end.
     *
     * <p>It was 350 m, which let a spawn point move a deliberate tap a third of a kilometre; and it did
     * so with no check that the spawn was anywhere a body could stand, which is how a tap could land a
     * player in a car park whose compiler-chosen point was in water. Both of those are fixed here: the
     * radius is a nudge rather than a shove, and a spawn is only used when it is standable.
     */
    public static final double SPAWN_PREFERENCE_M = 120.0;

    /**
     * Whether a person could be put on the ground at a bundle coordinate.
     *
     * <p>The teleport service works in bundle metres and the caller in local metres -- the floating
     * origin moves between the two -- so the caller supplies the check rather than the frame. A caller
     * with no world to check against passes {@link #ANYWHERE}, which is what the pure map tests do; the
     * game passes a check against the compiled country, and that is what stops a tap from resolving to
     * the middle of a building or to a car spawn the compiler put in the sea.
     */
    public interface Placement {
        boolean standable(double east, double north);
    }

    /** The placement that accepts everything: no world to ask, so no reason to refuse. */
    public static final Placement ANYWHERE = (east, north) -> true;

    /** The result of resolving a map tap. */
    public static final class Target {
        public double east;
        public double north;
        public boolean snappedToRoad;
        public boolean snappedToSpawn;
        /** True when the tap was in water and the player was placed on the nearest shore instead. */
        public boolean snappedToShore;
        public boolean onWater;
        /**
         * The direction the destination faces, in this project's heading degrees (0 north, 90 east), or
         * -1 when the data has no direction to offer.
         *
         * <p>A runway threshold faces down its runway and a road vertex faces along its road, which is
         * the difference between arriving somewhere able to drive off and arriving pointing at a wall.
         * The compiled point bundle stores the direction as a mathematical angle (0 east, anticlockwise)
         * because that is how the compiler measures it, so it is converted here, once.
         */
        public double headingDegrees = -1.0;
        public String label = "";
        public int spawnKind = -1;
        /**
         * How far the arrival is from the tapped point, in metres.
         *
         * <p>Zero when the player went exactly where they pointed. It is reported rather than hidden:
         * the one thing a teleport must never do is pretend it went where it was asked to, and a tap
         * that has been moved a hundred metres is a fact about the map, not a detail.
         */
        public double snapM;

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "(%.0f, %.0f) %s%s%s", east, north,
                    snappedToRoad ? "road" : "", snappedToSpawn ? "spawn" : "",
                    onWater ? " water" : "");
        }
    }

    private final WorldData world;
    private final MapRaster raster;

    public TeleportService(WorldData world, MapRaster raster) {
        this.world = world;
        this.raster = raster;
    }

    /** Resolves a tap in map pixels into a world target, with no placement check. */
    public Target fromMapPixel(double pixelX, double pixelY) {
        return fromMapPixel(pixelX, pixelY, ANYWHERE);
    }

    public Target fromMapPixel(double pixelX, double pixelY, Placement placement) {
        double east = raster.eastAt(pixelX / raster.width);
        double north = raster.northAt(pixelY / raster.height);
        return resolve(east, north, placement);
    }

    /** Resolves a world position into something the player can actually stand on. */
    public Target resolve(double east, double north) {
        return resolve(east, north, ANYWHERE);
    }

    /**
     * Resolves a world position into somewhere the player can be put, checking every candidate against
     * the world it is about to be put in.
     *
     * <p>The order is deliberate, and it is the order of how much the player meant it:
     *
     * <ol>
     *   <li>A <b>named spawn point</b> within {@link #SPAWN_PREFERENCE_M} that is standable. The data
     *       says vehicles and people belong there, so a tap that lands on one means it.</li>
     *   <li>A <b>road vertex</b> within {@link #ROAD_SNAP_M}, when the tap is on a road, on its verge,
     *       or inside a wall that stands on one.</li>
     *   <li><b>The tapped place itself</b>, when a person could stand there. This is the case that was
     *       missing: the old resolver always moved a tap to the nearest road within 900 m, so pointing
     *       at clear open ground beside a highway put the player on the highway -- hundreds of metres
     *       from the finger, with nothing on the screen to say why.</li>
     *   <li>A road within the full {@link #SNAP_RADIUS_M}, for a tap that cannot be honoured at all.</li>
     *   <li>The <b>nearest shore</b>, for a tap out on the water.</li>
     * </ol>
     *
     * <p>Every candidate is checked before it is chosen, so a spawn the compiler placed in the sea, a
     * road vertex inside a compound's bounding box, or a standable-looking point in deep water is passed
     * over rather than handed to the player. What the caller still has to do is the last step: ground
     * that is clear at one point can be inside a building's box ten metres away, and
     * {@code SpawnClearer} is what moves the body the final few metres.
     */
    public Target resolve(double east, double north, Placement placement) {
        Placement where = placement == null ? ANYWHERE : placement;
        Target target = new Target();
        target.east = east;
        target.north = north;
        target.onWater = raster != null && raster.isWaterAt(east, north);
        boolean tapIsWater = target.onWater;

        // 1. A named spawn point near the tap, if a body can be put on it.
        Points points = world.points;
        if (points != null) {
            int nearest = nearestSpawn(east, north);
            if (nearest >= 0) {
                double spawnEast = points.xz[nearest * 2];
                double spawnNorth = points.xz[nearest * 2 + 1];
                double distance = Math.hypot(spawnEast - east, spawnNorth - north);
                if (distance <= SPAWN_PREFERENCE_M && where.standable(spawnEast, spawnNorth)) {
                    target.east = spawnEast;
                    target.north = spawnNorth;
                    target.snappedToSpawn = true;
                    target.spawnKind = points.kind[nearest];
                    target.label = world.strings.get(points.nameIndex[nearest]);
                    target.headingDegrees = headingFromFacing(points.facingDeg[nearest]);
                    target.onWater = raster != null && raster.isWaterAt(target.east, target.north);
                    target.snapM = distance;
                    return target;
                }
            }
        }

        // 2. A road just beside the tap, when the tap is on it, on its verge, or inside a wall that
        //    stands on it: within the width of a road a tap *is* the road, and arriving on the tarmac
        //    facing along it is the difference between being able to drive off and pointing at a wall.
        Roads roads = world.roads;
        if (!tapIsWater && roads != null && world.roadIndex != null) {
            double[] near = nearestRoadPoint(roads, east, north, ROAD_SNAP_M, where);
            if (near != null) {
                target.east = near[0];
                target.north = near[1];
                target.snappedToRoad = true;
                target.label = world.strings.get(roads.nameIndex[(int) near[2]]);
                target.headingDegrees = near[3];
                target.onWater = raster != null && raster.isWaterAt(target.east, target.north);
                target.snapM = Math.hypot(target.east - east, target.north - north);
                return target;
            }
        }

        // 3. The tapped place itself, when it can be stood on. This is the case the old resolver was
        //    missing, and the reason a tap on open ground beside a highway was answered with the
        //    highway: nothing has moved, so nothing is reported as moved either.
        if (!tapIsWater && where.standable(east, north)) {
            target.onWater = false;
            target.snapM = 0.0;
            return target;
        }

        // 4. A road anywhere within the wide radius, for a tap that cannot be honoured where it landed.
        if (roads != null && world.roadIndex != null) {
            double[] far = nearestRoadPoint(roads, east, north, SNAP_RADIUS_M, where);
            if (far != null) {
                target.east = far[0];
                target.north = far[1];
                target.snappedToRoad = true;
                target.label = world.strings.get(roads.nameIndex[(int) far[2]]);
                target.headingDegrees = far[3];
                target.onWater = raster != null && raster.isWaterAt(target.east, target.north);
                target.snapM = Math.hypot(target.east - east, target.north - north);
                return target;
            }
        }

        // 5. A tap out on the water with no harbour and no road in reach: the nearest shore, which is
        //    the one thing a map tap must never fail to do.
        if (target.onWater && raster != null) {
            double[] shore = nearestShore(east, north);
            if (shore != null) {
                target.east = shore[0];
                target.north = shore[1];
                target.onWater = false;
                target.snappedToShore = true;
                target.label = "";
                target.snapM = Math.hypot(target.east - east, target.north - north);
            }
        }
        return target;
    }

    /**
     * The nearest usable point on a road within a radius, or null when there is none.
     *
     * <p><b>On the road, not on a vertex of it.</b> OSM ways are polylines whose vertices sit wherever
     * the survey put them -- tens of metres apart on a curve, hundreds on a straight desert highway --
     * and the first version of this method measured to the vertices alone. On a long straight way that
     * is not a small error: the nearest vertex to a tap in the middle of a two-kilometre straight is a
     * kilometre away, so a tap on the road, or on the verge beside it, resolved to one of the ends. The
     * nearest point on the *segment* is what "the road beside me" means.
     *
     * <p>The heading comes from the segment the point is on, which is also more honest than the
     * tangent through a vertex: what the player faces on arrival is the direction of the road where
     * they are standing.
     *
     * @return {east, north, line, headingDegrees}, or null
     */
    private double[] nearestRoadPoint(Roads roads, double east, double north, double radius,
                                      Placement placement) {
        double[] best = {Double.MAX_VALUE, 0, 0, -1, 0, -1};
        double radius2 = radius * radius;
        world.roadIndex.query(east - radius, north - radius, east + radius, north + radius, line -> {
            if (line < 0 || line >= roads.count || roads.roadClass[line] > MIN_ROAD_CLASS) {
                return true;
            }
            int start = roads.lineStart[line];
            int count = roads.lineLength[line];
            for (int i = 0; i < count; i++) {
                double ax = roads.xz[(start + i) * 2];
                double az = roads.xz[(start + i) * 2 + 1];
                double bx;
                double bz;
                if (i + 1 < count) {
                    bx = roads.xz[(start + i + 1) * 2];
                    bz = roads.xz[(start + i + 1) * 2 + 1];
                } else {
                    // The last vertex of a way is a point on the road like any other; give it a
                    // zero-length segment so the arithmetic below covers it without a second loop.
                    bx = ax;
                    bz = az;
                }
                double dx = bx - ax;
                double dz = bz - az;
                double length2 = dx * dx + dz * dz;
                double t = 0.0;
                if (length2 > 1e-9) {
                    t = clamp01(((east - ax) * dx + (north - az) * dz) / length2);
                }
                double pointEast = ax + dx * t;
                double pointNorth = az + dz * t;
                double ex = pointEast - east;
                double ez = pointNorth - north;
                double distance2 = ex * ex + ez * ez;
                if (distance2 >= best[0] || distance2 > radius2) {
                    continue;
                }
                if (placement != null && !placement.standable(pointEast, pointNorth)) {
                    continue;
                }
                best[0] = distance2;
                best[1] = pointEast;
                best[2] = pointNorth;
                best[3] = line;
                best[5] = length2 > 1e-9 ? wrapDegrees(Math.toDegrees(Math.atan2(dx, dz))) : -1.0;
            }
            return true;
        });
        if (best[3] < 0) {
            return null;
        }
        return new double[]{best[1], best[2], best[3], best[5]};
    }

    private static double clamp01(double value) {
        return value < 0.0 ? 0.0 : (value > 1.0 ? 1.0 : value);
    }

    /**
     * A pixel of dry land close to the tapped point, found by searching outward in square rings.
     *
     * <p>Not the mathematically nearest shore -- a raster ring search is the honest description -- but
     * the answer is always land, always close, and always the same for the same tap. It runs only for a
     * tap that lands in water, which is a gesture, not a frame.
     */
    private double[] nearestShore(double east, double north) {
        int centreX = (int) Math.round(raster.u(east, north) * raster.width);
        int centreY = (int) Math.round(raster.v(east, north) * raster.height);
        int limit = Math.max(16, raster.width / 8);
        for (int radius = 0; radius <= limit; radius++) {
            for (int ring = 0; ring <= radius; ring++) {
                int[] xs = {centreX - radius, centreX + radius, centreX - radius + ring, centreX + radius - ring};
                int[] ys = {centreY - radius + ring, centreY + radius - ring, centreY - radius,
                        centreY + radius};
                for (int i = 0; i < xs.length; i++) {
                    int x = xs[i];
                    int y = ys[i];
                    if (x < 0 || y < 0 || x >= raster.width || y >= raster.height) {
                        continue;
                    }
                    if (!raster.isWaterAt(worldEast(x), worldNorth(y))) {
                        return new double[]{worldEast(x), worldNorth(y)};
                    }
                }
            }
        }
        return null;
    }

    /**
     * The compiled point bundle's facing as a heading: the compiler measures direction as a
     * mathematical angle (0 east, anticlockwise, from the runway end towards its other end), and this
     * project's bodies steer by heading degrees (0 north, clockwise). A stored zero means "no
     * direction", not "north".
     */
    static double headingFromFacing(double facingDegrees) {
        if (facingDegrees <= 0.0 || facingDegrees >= 360.0) {
            return -1.0;
        }
        return wrapDegrees(90.0 - facingDegrees);
    }

    private static double wrapDegrees(double degrees) {
        double d = degrees % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    private double worldEast(int pixelX) {
        return raster.eastAt((pixelX + 0.5) / raster.width);
    }

    private double worldNorth(int pixelY) {
        return raster.northAt((pixelY + 0.5) / raster.height);
    }

    private int nearestSpawn(double east, double north) {
        Points points = world.points;
        int best = -1;
        double bestDistance = SPAWN_PREFERENCE_M * SPAWN_PREFERENCE_M;
        for (int i = 0; i < points.count; i++) {
            if (points.kind[i] == Points.KIND_LANDMARK) {
                continue;
            }
            double dx = points.xz[i * 2] - east;
            double dz = points.xz[i * 2 + 1] - north;
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * A safe starting position: the first vehicle spawn of the requested kind, or the centre of Doha if
     * the bundle has none. The game starts the player somewhere real, never at (0,0).
     */
    public Target spawnFor(int spawnKind) {
        Points points = world.points;
        if (points != null) {
            double[] centre = dohaCentre();
            int nearest = points.nearest(spawnKind, centre[0], centre[1], world.pointIndex, 200_000);
            if (nearest >= 0) {
                Target target = new Target();
                target.east = points.xz[nearest * 2];
                target.north = points.xz[nearest * 2 + 1];
                target.snappedToSpawn = true;
                target.spawnKind = spawnKind;
                target.label = world.strings.get(points.nameIndex[nearest]);
                return target;
            }
        }
        Target fallback = new Target();
        double[] centre = dohaCentre();
        fallback.east = centre[0];
        fallback.north = centre[1];
        fallback.label = "Doha";
        return fallback;
    }

    /** Doha city centre in the national frame, from the compiled municipalities when present. */
    public double[] dohaCentre() {
        for (GameManifestView municipality : municipalities()) {
            if (municipality.name.toLowerCase(java.util.Locale.ROOT).contains("dawhah")
                    || municipality.name.toLowerCase(java.util.Locale.ROOT).contains("doha")) {
                return new double[]{municipality.east, municipality.north};
            }
        }
        return new double[]{world.manifest.widthM * 0.42, world.manifest.heightM * 0.34};
    }

    private java.util.List<GameManifestView> municipalities() {
        java.util.List<GameManifestView> out = new java.util.ArrayList<>();
        for (ai.arena.qatar.world.GameManifest.Municipality m : world.manifest.municipalities) {
            GameManifestView view = new GameManifestView();
            view.name = m.name;
            view.east = m.east - world.manifest.originEast;
            view.north = m.north - world.manifest.originNorth;
            out.add(view);
        }
        return out;
    }

    /** A position in bundle space with a name, used for choosing a start point. */
    private static final class GameManifestView {
        String name = "";
        double east;
        double north;
    }
}
