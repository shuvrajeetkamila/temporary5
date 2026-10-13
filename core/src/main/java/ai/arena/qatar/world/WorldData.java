package ai.arena.qatar.world;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

/**
 * Every compiled bundle of the country, loaded once and verified.
 *
 * <p>Loading is deliberately strict: each bundle's SHA-256 digest is checked against the manifest
 * before it is parsed, so a truncated asset (a bad copy on the device, a half-written CI artefact)
 * fails loudly at startup instead of silently producing a broken country. A world that cannot be
 * loaded is reported to the loading screen, never half-used.
 *
 * <p>Coordinates inside every bundle are metres relative to the manifest grid origin; use
 * {@link #globalEast(double)} and {@link #globalNorth(double)} to reach EPSG:32639.
 */
public final class WorldData {

    public final GameManifest manifest;
    public final StringTable strings;
    public final TerrainGrid terrainL1;
    public final TerrainGrid terrainL0;
    public final MaterialGrid materials;
    public final Roads roads;
    public final Buildings buildings;
    public final Areas landuse;
    public final Areas water;
    public final Barriers barriers;
    public final Points points;

    public final FeatureIndex buildingIndex;
    public final FeatureIndex roadIndex;
    public final FeatureIndex waterIndex;
    public final FeatureIndex pointIndex;

    private final long loadMillis;

    private WorldData(GameManifest manifest, StringTable strings, TerrainGrid terrainL1,
                      TerrainGrid terrainL0, MaterialGrid materials, Roads roads, Buildings buildings,
                      Areas landuse, Areas water, Barriers barriers, Points points,
                      long loadMillis) {
        this.manifest = manifest;
        this.strings = strings;
        this.terrainL1 = terrainL1;
        this.terrainL0 = terrainL0;
        this.materials = materials;
        this.roads = roads;
        this.buildings = buildings;
        this.landuse = landuse;
        this.water = water;
        this.barriers = barriers;
        this.points = points;
        this.loadMillis = loadMillis;

        // Spatial indexes are built for the feature types the game queries per frame or per teleport.
        double width = manifest.widthM;
        double height = manifest.heightM;
        this.buildingIndex = buildings == null ? null
                : new FeatureIndex(buildings.bounds, buildings.ringCount, manifest.originEast,
                        manifest.originNorth, width, height, 500.0);
        this.roadIndex = roads == null ? null
                : new FeatureIndex(roads.bounds, roads.count, manifest.originEast,
                        manifest.originNorth, width, height, 500.0);
        this.waterIndex = water == null ? null
                : new FeatureIndex(water.bounds, water.count, manifest.originEast,
                        manifest.originNorth, width, height, 1000.0);
        this.pointIndex = points == null ? null
                : new FeatureIndex(pointBounds(points), points.count, manifest.originEast,
                        manifest.originNorth, width, height, 1000.0);
    }

    /** Points carry no stored bounds; a point's own coordinates are its bounding box. */
    private static float[] pointBounds(Points points) {
        float[] bounds = new float[points.count * 4];
        for (int i = 0; i < points.count; i++) {
            bounds[i * 4] = points.xz[i * 2];
            bounds[i * 4 + 1] = points.xz[i * 2 + 1];
            bounds[i * 4 + 2] = points.xz[i * 2];
            bounds[i * 4 + 3] = points.xz[i * 2 + 1];
        }
        return bounds;
    }

    public double globalEast(double relativeEast) {
        return manifest.originEast + relativeEast;
    }

    public double globalNorth(double relativeNorth) {
        return manifest.originNorth + relativeNorth;
    }

    public long loadMillis() {
        return loadMillis;
    }

    /**
     * Where bundle bytes come from: a file handle on a device, a directory in a test, memory in a tool.
     *
     * <p>The loading path is the same one everywhere, which is the point: the test that loads the
     * committed 14 MB of Qatar runs the same code the phone runs, not a paraphrase of it.
     */
    public interface BundleSource {
        byte[] read(String fileName);
    }

    /** Loads and verifies every bundle from an asset directory such as {@code world}. */
    public static WorldData load(String assetDir) {
        GameManifest manifest = GameManifest.load(assetDir);
        return fromSource(manifest, fileName -> {
            FileHandle handle = Gdx.files.internal(assetDir + "/" + fileName);
            if (!handle.exists()) {
                throw new IllegalStateException("world bundle missing: " + fileName);
            }
            return handle.readBytes();
        });
    }

    /** Loads and verifies every bundle the manifest names, from whatever holds them. */
    public static WorldData fromSource(GameManifest manifest, BundleSource source) {
        long started = System.nanoTime();
        StringTable strings = manifest.strings.present()
                ? StringTable.parseUncompressed(BundleReader.open("strings",
                        source.read(manifest.strings.file), manifest.strings.sha256))
                : StringTable.empty();

        TerrainGrid terrainL1 = TerrainGrid.parse(source.read(manifest.terrainL1.file),
                manifest.originEast, manifest.originNorth, "QTT1");
        TerrainGrid terrainL0 = manifest.terrainL0.present()
                ? TerrainGrid.parse(source.read(manifest.terrainL0.file),
                        manifest.originEast, manifest.originNorth, "QTT1")
                : terrainL1;
        MaterialGrid materials = MaterialGrid.parse(source.read(manifest.materials.file),
                manifest.originEast, manifest.originNorth);

        Roads roads = manifest.roads.present()
                ? Roads.parse(BundleReader.open("roads", source.read(manifest.roads.file),
                        manifest.roads.sha256), manifest.originEast, manifest.originNorth)
                : null;
        Buildings buildings = manifest.buildings.present()
                ? Buildings.parse(BundleReader.open("buildings", source.read(manifest.buildings.file),
                        manifest.buildings.sha256), manifest.originEast, manifest.originNorth)
                : null;
        Areas landuse = manifest.landuse.present()
                ? Areas.parse(BundleReader.open("landuse", source.read(manifest.landuse.file),
                        manifest.landuse.sha256), "QLU1", manifest.originEast, manifest.originNorth)
                : null;
        Areas water = manifest.water.present()
                ? Areas.parse(BundleReader.open("water", source.read(manifest.water.file),
                        manifest.water.sha256), "QWA1", manifest.originEast, manifest.originNorth)
                : null;
        Barriers barriers = manifest.barriers.present()
                ? Barriers.parse(BundleReader.open("barriers", source.read(manifest.barriers.file),
                        manifest.barriers.sha256), manifest.originEast, manifest.originNorth)
                : null;
        Points points = manifest.pois.present()
                ? Points.parse(BundleReader.open("pois", source.read(manifest.pois.file),
                        manifest.pois.sha256), manifest.originEast, manifest.originNorth)
                : null;

        long millis = (System.nanoTime() - started) / 1_000_000L;
        return new WorldData(manifest, strings, terrainL1, terrainL0, materials, roads, buildings,
                landuse, water, barriers, points, millis);
    }

    /** Human readable summary for the loading screen and the startup report. */
    public String describe() {
        StringBuilder text = new StringBuilder(manifest.describe());
        if (landuse != null) {
            text.append(", ").append(landuse.count).append(" land use polygons");
        }
        if (water != null) {
            text.append(", ").append(water.count).append(" water polygons");
        }
        if (barriers != null) {
            text.append(", ").append(barriers.count).append(" barriers");
        }
        if (points != null) {
            text.append(", ").append(points.count).append(" points of interest");
        }
        text.append(" | loaded in ").append(loadMillis).append(" ms");
        return text.toString();
    }
}
