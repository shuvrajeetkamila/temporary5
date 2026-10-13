package ai.arena.qatar.world;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime view of {@code assets/world/manifest.json} (game format 2).
 *
 * <p>The manifest is the contract between the Python compiler and the game: it names every bundle,
 * carries its SHA-256 digest and record count, and describes the grid the coordinates live in. The
 * game refuses to start if the manifest is missing or claims a format it cannot read, because a
 * half-loaded country is worse than a clear error.
 */
public final class GameManifest {

    public static final int SUPPORTED_FORMAT = 2;

    public int format;
    public int worldEpoch;
    public String crs = "";
    public String crsName = "";
    public double originEast;
    public double originNorth;
    public double widthM;
    public double heightM;
    public String attribution = "";

    /** One bundle file with its integrity data. */
    public static final class Bundle {
        public String file = "";
        public int records;
        public String sha256 = "";
        public double cellM;
        public int cols;
        public int rows;

        public boolean present() {
            return file != null && !file.isEmpty();
        }
    }

    public Bundle terrainL0 = new Bundle();
    public Bundle terrainL1 = new Bundle();
    public Bundle materials = new Bundle();
    public Bundle roads = new Bundle();
    public Bundle strings = new Bundle();
    public Bundle buildings = new Bundle();
    public Bundle landuse = new Bundle();
    public Bundle water = new Bundle();
    public Bundle barriers = new Bundle();
    public Bundle pois = new Bundle();

    public static final class Municipality {
        public String id = "";
        public String name = "";
        public String nameAr = "";
        public double east;
        public double north;
        public double areaKm2;
    }

    public static final class Island {
        public String id = "";
        public String name = "";
        public double east;
        public double north;
        public double areaM2;
    }

    public final List<Municipality> municipalities = new ArrayList<>();
    public final List<Island> islands = new ArrayList<>();
    public final Map<String, Integer> spawnPoints = new LinkedHashMap<>();
    public final Map<String, Long> roadClassHistogram = new LinkedHashMap<>();

    public double maxEast() {
        return originEast + widthM;
    }

    public double maxNorth() {
        return originNorth + heightM;
    }

    public boolean contains(double east, double north) {
        return east >= originEast && east <= maxEast() && north >= originNorth && north <= maxNorth();
    }

    /** Joins an array of strings (the manifest records attribution as a list). */
    private static String joinStrings(com.badlogic.gdx.utils.JsonValue array) {
        StringBuilder sb = new StringBuilder();
        for (com.badlogic.gdx.utils.JsonValue value = array.child; value != null; value = value.next) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(value.asString());
        }
        return sb.toString();
    }

    /** Loads the manifest from the asset root, e.g. {@code world}. */
    public static GameManifest load(String worldDir) {
        FileHandle handle = Gdx.files.internal(worldDir + "/manifest.json");
        if (!handle.exists()) {
            throw new IllegalStateException("world manifest missing at " + worldDir + "/manifest.json");
        }
        return parse(handle.readString("UTF-8"));
    }

    public static GameManifest parse(String json) {
        JsonValue root = new JsonReader().parse(json);
        GameManifest manifest = new GameManifest();
        manifest.format = root.getInt("format", 0);
        if (manifest.format != SUPPORTED_FORMAT) {
            throw new IllegalStateException("world manifest format " + manifest.format
                    + " is not supported (this build reads format " + SUPPORTED_FORMAT + ")");
        }
        manifest.worldEpoch = root.getInt("worldEpoch", 0);
        manifest.crs = root.getString("crs", "");
        manifest.crsName = root.getString("crsName", "");
        // The grid stage 1 writes lives under "grid"; flat keys are accepted for hand-written manifests.
        // Falling back to 0,0 for a missing grid used to be silent and catastrophic: every cell lookup
        // would be 431 km off, so an absent grid is an error, not a default.
        JsonValue grid = root.get("grid");
        JsonValue originSource = grid != null ? grid : root;
        manifest.originEast = originSource.getDouble("originEast", Double.NaN);
        manifest.originNorth = originSource.getDouble("originNorth", Double.NaN);
        manifest.widthM = originSource.getDouble("widthM", Double.NaN);
        manifest.heightM = originSource.getDouble("heightM", Double.NaN);
        if (Double.isNaN(manifest.originEast) || Double.isNaN(manifest.originNorth)
                || Double.isNaN(manifest.widthM) || Double.isNaN(manifest.heightM)
                || manifest.widthM <= 0 || manifest.heightM <= 0) {
            throw new IllegalStateException("world manifest has no usable grid: origin ("
                    + manifest.originEast + ", " + manifest.originNorth + ") extent ("
                    + manifest.widthM + " x " + manifest.heightM + ")");
        }
        JsonValue attribution = root.get("attribution");
        manifest.attribution = attribution == null ? ""
                : (attribution.isArray() ? joinStrings(attribution) : attribution.asString());

        manifest.terrainL0 = bundle(root.get("terrain_l0"));
        manifest.terrainL1 = bundle(root.get("terrain_l1"));
        manifest.materials = bundle(root.get("materials"));
        manifest.roads = bundle(root.get("roads"));
        manifest.strings = bundle(root.get("strings"));
        JsonValue game = root.get("gameBundles");
        if (game != null) {
            manifest.buildings = bundle(game.get("buildings"));
            manifest.landuse = bundle(game.get("landuse"));
            manifest.water = bundle(game.get("water"));
            manifest.barriers = bundle(game.get("barriers"));
            manifest.pois = bundle(game.get("pois"));
        }
        JsonValue levels = root.get("levels");
        if (levels != null && levels.isArray() && levels.size > 0) {
            // The level list is the authoritative terrain description; terrain_l1 is its finest entry.
            for (JsonValue level = levels.child; level != null; level = level.next) {
                if ("l1".equals(level.getString("name", ""))) {
                    manifest.terrainL1.cellM = level.getDouble("cellM", manifest.terrainL1.cellM);
                    manifest.terrainL1.cols = level.getInt("cols", manifest.terrainL1.cols);
                    manifest.terrainL1.rows = level.getInt("rows", manifest.terrainL1.rows);
                }
            }
        }
        JsonValue munis = root.get("municipalities");
        if (munis != null) {
            for (JsonValue value = munis.child; value != null; value = value.next) {
                Municipality m = new Municipality();
                m.id = value.getString("id", "");
                m.name = value.getString("name", "");
                m.nameAr = value.getString("nameAr", "");
                m.east = value.getDouble("east", 0);
                m.north = value.getDouble("north", 0);
                m.areaKm2 = value.getDouble("areaKm2", 0);
                manifest.municipalities.add(m);
            }
        }
        JsonValue islands = root.get("islands");
        if (islands != null) {
            for (JsonValue value = islands.child; value != null; value = value.next) {
                Island island = new Island();
                island.id = value.getString("id", "");
                island.name = value.getString("name", "");
                island.east = value.getDouble("east", 0);
                island.north = value.getDouble("north", 0);
                island.areaM2 = value.getDouble("areaM2", 0);
                manifest.islands.add(island);
            }
        }
        JsonValue stats = root.get("stats");
        if (stats != null) {
            JsonValue spawns = stats.get("spawnPoints");
            if (spawns != null) {
                for (JsonValue value = spawns.child; value != null; value = value.next) {
                    manifest.spawnPoints.put(value.name, value.asInt());
                }
            }
            JsonValue roads = stats.get("roadClassHistogram");
            if (roads != null) {
                for (JsonValue value = roads.child; value != null; value = value.next) {
                    manifest.roadClassHistogram.put(value.name, value.asLong());
                }
            }
        }
        return manifest;
    }

    private static Bundle bundle(JsonValue value) {
        Bundle bundle = new Bundle();
        if (value == null || !value.isObject()) {
            return bundle;
        }
        bundle.file = value.getString("file", "");
        bundle.sha256 = value.getString("sha256", "");
        bundle.records = value.getInt("count", value.getInt("records", 0));
        bundle.cellM = value.getDouble("cellM", 0);
        bundle.cols = value.getInt("cols", 0);
        bundle.rows = value.getInt("rows", 0);
        return bundle;
    }

    /** Human readable summary, used by the loading screen and the startup report. */
    public String describe() {
        return "world format " + format + " (" + crs + "), grid " + (int) (widthM / 1000) + "x"
                + (int) (heightM / 1000) + " km, " + municipalities.size() + " municipalities, "
                + islands.size() + " islands, " + buildings.records + " buildings, "
                + roads.records + " roads, " + landuse.records + " land use polygons";
    }
}
