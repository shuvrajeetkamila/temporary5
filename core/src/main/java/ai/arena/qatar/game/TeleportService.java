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

    /** How far a tap may be snapped to reach a road, in metres. */
    public static final double SNAP_RADIUS_M = 900.0;
    /** Roads the player can actually be placed on: anything from a track up to a motorway. */
    private static final int MIN_ROAD_CLASS = 10;
    /** Spawn points within this distance of the tap win over a road: they are meant to be used. */
    public static final double SPAWN_PREFERENCE_M = 350.0;

    /** The result of resolving a map tap. */
    public static final class Target {
        public double east;
        public double north;
        public boolean snappedToRoad;
        public boolean snappedToSpawn;
        /** True when the tap was in water and the player was placed on the nearest shore instead. */
        public boolean snappedToShore;
        public boolean onWater;
        public String label = "";
        public int spawnKind = -1;

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

    /** Resolves a tap in map pixels into a world target. */
    public Target fromMapPixel(double pixelX, double pixelY) {
        double east = raster.eastAt(pixelX / raster.width);
        double north = raster.northAt(pixelY / raster.height);
        return resolve(east, north);
    }

    /** Resolves a world position into something the player can actually stand on. */
    public Target resolve(double east, double north) {
        Target target = new Target();
        target.east = east;
        target.north = north;
        target.onWater = raster != null && raster.isWaterAt(east, north);

        // A named spawn point near the tap is the most deliberate answer: it is a place the data says
        // vehicles and people belong (a car park, a harbour, an apron, a runway end).
        Points points = world.points;
        if (points != null) {
            int nearest = nearestSpawn(east, north);
            if (nearest >= 0) {
                double dx = points.xz[nearest * 2] - east;
                double dz = points.xz[nearest * 2 + 1] - north;
                if (Math.sqrt(dx * dx + dz * dz) <= SPAWN_PREFERENCE_M) {
                    target.east = points.xz[nearest * 2];
                    target.north = points.xz[nearest * 2 + 1];
                    target.snappedToSpawn = true;
                    target.spawnKind = points.kind[nearest];
                    target.label = world.strings.get(points.nameIndex[nearest]);
                    target.onWater = raster != null && raster.isWaterAt(target.east, target.north);
                    return target;
                }
            }
        }

        // Otherwise snap to the nearest road vertex, measured on the real geometry.
        Roads roads = world.roads;
        if (roads != null && world.roadIndex != null) {
            double[] best = {Double.MAX_VALUE, 0, 0, -1};
            world.roadIndex.query(east - SNAP_RADIUS_M, north - SNAP_RADIUS_M, east + SNAP_RADIUS_M,
                    north + SNAP_RADIUS_M, line -> {
                if (line < 0 || line >= roads.count || roads.roadClass[line] > MIN_ROAD_CLASS) {
                    return true;
                }
                int start = roads.lineStart[line];
                int points_n = roads.lineLength[line];
                for (int i = 0; i < points_n; i++) {
                    double dx = roads.xz[(start + i) * 2] - east;
                    double dz = roads.xz[(start + i) * 2 + 1] - north;
                    double distance = dx * dx + dz * dz;
                    if (distance < best[0]) {
                        best[0] = distance;
                        best[1] = roads.xz[(start + i) * 2];
                        best[2] = roads.xz[(start + i) * 2 + 1];
                        best[3] = line;
                    }
                }
                return true;
            });
            if (best[3] >= 0 && Math.sqrt(best[0]) <= SNAP_RADIUS_M) {
                target.east = best[1];
                target.north = best[2];
                target.snappedToRoad = true;
                target.label = world.strings.get(roads.nameIndex[(int) best[3]]);
                target.onWater = raster != null && raster.isWaterAt(target.east, target.north);
            }
        }

        // Last resort: a tap out in the water with no harbour and no road within reach. The player is
        // placed on the nearest shore rather than in the sea, which is the one thing a map tap must
        // never do.
        if (target.onWater && raster != null) {
            double[] shore = nearestShore(target.east, target.north);
            if (shore != null) {
                target.east = shore[0];
                target.north = shore[1];
                target.onWater = false;
                target.snappedToShore = true;
                target.label = "";
            }
        }
        return target;
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
