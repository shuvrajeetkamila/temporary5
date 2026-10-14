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
    public final FeatureIndex barrierIndex;

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
        // A 250 m cell, because a barrier is a thin line: a coarser index would report neighbours that
        // are nowhere near the query, and the per-segment test would do the work anyway.
        this.barrierIndex = barriers == null ? null
                : new FeatureIndex(barriers.bounds, barriers.count, manifest.originEast,
                        manifest.originNorth, width, height, 250.0);
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
            // The manifest names the file as it is in the repository. The packager may have stored it
            // under a different name: see BundleReader.openPackaged for what it does with a ".gz" member.
            String stored = storedName(fileName, candidate -> {
                FileHandle handle = Gdx.files.internal(assetDir + "/" + candidate);
                return handle.exists();
            });
            FileHandle handle = Gdx.files.internal(assetDir + "/" + stored);
            if (!handle.exists()) {
                throw new IllegalStateException("world bundle missing: " + fileName
                        + " (also looked for it without the .gz suffix)");
            }
            if (!stored.equals(fileName)) {
                ai.arena.qatar.boot.ScreenLog.GLOBAL.log("world/" + stored + " stands in for " + fileName);
            }
            return handle.readBytes();
        });
    }

    /**
     * The name a bundle is actually stored under, given the name the manifest records and a way to ask
     * whether a name exists. Pure, so it can be tested without a device: the Android packager strips a
     * ".gz" suffix from assets it unpacks, and that single difference is what stopped the country from
     * loading on a phone while every test and every desktop run was green.
     */
    public static String storedName(String wanted, java.util.function.Predicate<String> exists) {
        if (exists.test(wanted)) {
            return wanted;
        }
        if (wanted != null && wanted.endsWith(".gz")) {
            String stripped = wanted.substring(0, wanted.length() - 3);
            if (exists.test(stripped)) {
                return stripped;
            }
        }
        return wanted;
    }

    /** Loads and verifies every bundle the manifest names, from whatever holds them. */
    public static WorldData fromSource(GameManifest manifest, BundleSource rawSource) {
        // A source that cannot produce a bundle must say which one, and the only way to say it from here
        // is to be the one that throws: this is also the path a null-returning source takes, which is what
        // a test or a tool does when it has a missing file and no exception of its own.
        BundleSource source = fileName -> {
            byte[] bytes = rawSource.read(fileName);
            if (bytes == null) {
                throw new IllegalStateException("world bundle missing: " + fileName);
            }
            return bytes;
        };
        long started = System.nanoTime();
        StringTable strings = manifest.strings.present()
                ? StringTable.parseUncompressed(BundleReader.openPackaged("strings",
                        source.read(manifest.strings.file), manifest.strings.sha256))
                : StringTable.empty();

        // The terrain and material grids are gzip members like every other bundle: verify the file
        // against the manifest, inflate it, and only then read the QTT1/QTM1 header. Reading the
        // compressed bytes as if they were the grid is a crash at startup, not a subtle wrong answer.
        TerrainGrid terrainL1 = TerrainGrid.parse(payload(manifest.terrainL1, source),
                manifest.originEast, manifest.originNorth, "QTT1");
        TerrainGrid terrainL0 = manifest.terrainL0.present()
                ? TerrainGrid.parse(payload(manifest.terrainL0, source),
                        manifest.originEast, manifest.originNorth, "QTT1")
                : terrainL1;
        MaterialGrid materials = MaterialGrid.parse(payload(manifest.materials, source),
                manifest.originEast, manifest.originNorth);

        Roads roads = manifest.roads.present()
                ? Roads.parse(BundleReader.openPackaged("roads", source.read(manifest.roads.file),
                        manifest.roads.sha256), manifest.originEast, manifest.originNorth)
                : null;
        Buildings buildings = manifest.buildings.present()
                ? Buildings.parse(BundleReader.openPackaged("buildings", source.read(manifest.buildings.file),
                        manifest.buildings.sha256), manifest.originEast, manifest.originNorth)
                : null;
        Areas landuse = manifest.landuse.present()
                ? Areas.parse(BundleReader.openPackaged("landuse", source.read(manifest.landuse.file),
                        manifest.landuse.sha256), "QLU1", manifest.originEast, manifest.originNorth)
                : null;
        Areas water = manifest.water.present()
                ? Areas.parse(BundleReader.openPackaged("water", source.read(manifest.water.file),
                        manifest.water.sha256), "QWA1", manifest.originEast, manifest.originNorth)
                : null;
        Barriers barriers = manifest.barriers.present()
                ? Barriers.parse(BundleReader.openPackaged("barriers", source.read(manifest.barriers.file),
                        manifest.barriers.sha256), manifest.originEast, manifest.originNorth)
                : null;
        Points points = manifest.pois.present()
                ? Points.parse(BundleReader.openPackaged("pois", source.read(manifest.pois.file),
                        manifest.pois.sha256), manifest.originEast, manifest.originNorth)
                : null;

        long millis = (System.nanoTime() - started) / 1_000_000L;
        return new WorldData(manifest, strings, terrainL1, terrainL0, materials, roads, buildings,
                landuse, water, barriers, points, millis);
    }

    /** Verifies a bundle file against the manifest and returns its inflated payload. */
    private static byte[] payload(GameManifest.Bundle bundle, BundleSource source) {
        return BundleReader.openPackaged(bundle.file, source.read(bundle.file), bundle.sha256)
                .payloadBytes();
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
