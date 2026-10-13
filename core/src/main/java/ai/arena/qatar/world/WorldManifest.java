package ai.arena.qatar.world;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed {@code assets/world/manifest.json}: world epoch, master CRS, the national grid, bundle
 * inventory with checksums, and the packaged coverage lists (municipalities, islands, checkpoints).
 *
 * <p>A save file or an APK from a different world epoch is detectable here: the epoch and the bundle
 * checksums are part of the manifest, and the startup test refuses to run against a manifest whose
 * required entries are missing.
 */
public final class WorldManifest {

    public static final int SUPPORTED_FORMAT = 1;

    public int format;
    public int worldEpoch;
    public String crs = "";
    public String crsName = "";

    public double originEast;
    public double originNorth;
    public double widthM;
    public double heightM;

    public static final class Level {
        public String name;
        public double cellM;
        public int cols;
        public int rows;
        public String file;
        public String sha256;
    }

    public static final class Bundle {
        public String file;
        public int count;
        public String sha256;
        public double cellM;
        public int cols;
        public int rows;
    }

    public final List<Level> levels = new ArrayList<>();
    public Bundle materials = new Bundle();
    public Bundle roads = new Bundle();
    public Bundle buildings = new Bundle();
    public Bundle coast = new Bundle();
    public Bundle strings = new Bundle();

    public static final class Municipality {
        public String id;
        public String name;
        public String nameAr;
        public double east;
        public double north;
        public double areaKm2;
        public int nameIdx;
        public int nameArIdx;
    }

    public static final class Island {
        public String id;
        public String name;
        public String nameAr;
        public double east;
        public double north;
        public double areaM2;
    }

    public static final class Checkpoint {
        public String group;
        public String id;
        public double east;
        public double north;
        public int nameIdx;
        public int nameArIdx;
    }

    public final List<Municipality> municipalities = new ArrayList<>();
    public final List<Island> islands = new ArrayList<>();
    public final List<Checkpoint> checkpoints = new ArrayList<>();
    public final List<String> attribution = new ArrayList<>();
    public final Map<String, Long> materialHistogram = new LinkedHashMap<>();

    public int landCells;
    public int roadCount;
    public int buildingCount;
    public int coastRings;
    public int stringCount;

    public static WorldManifest parse(String jsonText) {
        JsonValue root = new JsonReader().parse(jsonText);
        WorldManifest m = new WorldManifest();
        m.format = root.getInt("format", 0);
        m.worldEpoch = root.getInt("worldEpoch", 0);
        m.crs = root.getString("crs", "");
        m.crsName = root.getString("crsName", "");

        JsonValue grid = root.get("grid");
        if (grid != null) {
            m.originEast = grid.getDouble("originEast", 0);
            m.originNorth = grid.getDouble("originNorth", 0);
            m.widthM = grid.getDouble("widthM", 0);
            m.heightM = grid.getDouble("heightM", 0);
        }

        for (JsonValue level : root.get("levels")) {
            Level l = new Level();
            l.name = level.getString("name", "");
            l.cellM = level.getDouble("cellM", 0);
            l.cols = level.getInt("cols", 0);
            l.rows = level.getInt("rows", 0);
            l.file = level.getString("file", "");
            l.sha256 = level.getString("sha256", "");
            m.levels.add(l);
        }

        m.materials = bundle(root.get("materials"), true);
        m.roads = bundle(root.get("roads"), false);
        m.buildings = bundle(root.get("buildings"), false);
        m.coast = bundle(root.get("coast"), false);
        m.strings = bundle(root.get("strings"), false);

        for (JsonValue muni : root.get("municipalities")) {
            Municipality x = new Municipality();
            x.id = muni.getString("id", "");
            x.name = muni.getString("name", "");
            x.nameAr = muni.getString("nameAr", "");
            x.east = muni.getDouble("east", 0);
            x.north = muni.getDouble("north", 0);
            x.areaKm2 = muni.getDouble("areaKm2", 0);
            x.nameIdx = muni.getInt("nameIdx", -1);
            x.nameArIdx = muni.getInt("nameArIdx", -1);
            m.municipalities.add(x);
        }
        for (JsonValue isl : root.get("islands")) {
            Island i = new Island();
            i.id = isl.getString("id", "");
            i.name = isl.getString("name", "");
            i.nameAr = isl.getString("nameAr", "");
            i.east = isl.getDouble("east", 0);
            i.north = isl.getDouble("north", 0);
            i.areaM2 = isl.getDouble("areaM2", 0);
            m.islands.add(i);
        }
        for (JsonValue cp : root.get("checkpoints")) {
            Checkpoint c = new Checkpoint();
            c.group = cp.getString("group", "");
            c.id = cp.getString("id", "");
            c.east = cp.getDouble("east", 0);
            c.north = cp.getDouble("north", 0);
            c.nameIdx = cp.getInt("nameIdx", -1);
            c.nameArIdx = cp.getInt("nameArIdx", -1);
            m.checkpoints.add(c);
        }
        for (JsonValue a : root.get("attribution")) {
            m.attribution.add(a.asString());
        }

        JsonValue stats = root.get("stats");
        if (stats != null) {
            m.landCells = stats.getInt("landCells", 0);
            JsonValue hist = stats.get("materialHistogram");
            if (hist != null) {
                for (JsonValue child = hist.child; child != null; child = child.next) {
                    m.materialHistogram.put(child.name, child.asLong());
                }
            }
        }
        m.roadCount = m.roads.count;
        m.buildingCount = m.buildings.count;
        m.coastRings = m.coast.count;
        m.stringCount = m.strings.count;
        return m;
    }

    private static Bundle bundle(JsonValue v, boolean grid) {
        Bundle b = new Bundle();
        if (v == null) {
            return b;
        }
        b.file = v.getString("file", "");
        b.count = v.getInt("count", 0);
        b.sha256 = v.getString("sha256", "");
        if (grid) {
            b.cellM = v.getDouble("cellM", 0);
            b.cols = v.getInt("cols", 0);
            b.rows = v.getInt("rows", 0);
        }
        return b;
    }

    public Level level(String name) {
        for (Level l : levels) {
            if (l.name.equals(name)) {
                return l;
            }
        }
        return null;
    }

    /** True when every field the runtime needs is present and self-consistent. */
    public boolean isComplete() {
        if (format != SUPPORTED_FORMAT || worldEpoch <= 0) {
            return false;
        }
        if (!"EPSG:32639".equals(crs) || widthM <= 0 || heightM <= 0) {
            return false;
        }
        if (level("l0") == null || level("l1") == null) {
            return false;
        }
        if (materialFile() == null || roads.file == null || buildings.file == null
                || coast.file == null || strings.file == null) {
            return false;
        }
        return !municipalities.isEmpty() && !islands.isEmpty();
    }

    public String materialFile() {
        return materials == null || materials.file.isEmpty() ? null : materials.file;
    }

    public String describe() {
        return "epoch=" + worldEpoch + " " + crs + " grid=" + Math.round(widthM / 1000) + "x"
                + Math.round(heightM / 1000) + "km municipalities=" + municipalities.size()
                + " islands=" + islands.size() + " roads=" + roadCount + " buildings=" + buildingCount;
    }
}
