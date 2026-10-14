package ai.arena.qatar.world;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a bundle is stored is not how a bundle is named, and the difference stopped the country from
 * loading on a phone.
 *
 * <p>In this repository a bundle is a gzip member: {@code roads.bin.gz}. On Android it is neither
 * compressed nor suffixed: the asset tooling unpacks a {@code .gz} member at package time and drops the
 * suffix, so the same data arrives as {@code roads.bin}. The first device build died in the loading stage
 * with {@code world bundle missing: strings.bin.gz} -- the whole country present in the APK, one
 * extension away from being found -- while every test here passed, because every test here reads the
 * repository's form.
 *
 * <p>So this test loads the same fixture four ways: the repository's names and shape, the packaged names
 * and shape, and the two mixed cases. All four must produce the same world, because all four are the
 * same country.
 */
final class BundlePackagingTest {

    private static final String WORLD = "synthetic_world";

    private static final String[] BUNDLES = {
            "barriers.bin.gz", "buildings.bin.gz", "landuse.bin.gz", "material_l1.bin.gz",
            "pois.bin.gz", "roads.bin.gz", "strings.bin.gz", "terrain_l0.bin.gz", "terrain_l1.bin.gz",
            "water.bin.gz"};

    private static Path fixture;
    private static GameManifest manifest;
    private static Map<String, byte[]> onDisk;

    @BeforeAll
    static void loadFixture() throws IOException {
        Path candidate = Paths.get("src/test/resources", WORLD);
        if (!Files.isDirectory(candidate)) {
            candidate = Paths.get("core/src/test/resources", WORLD);
        }
        assertTrue(Files.isDirectory(candidate), "the fixture is missing: " + candidate);
        fixture = candidate;
        manifest = GameManifest.parse(
                new String(Files.readAllBytes(fixture.resolve("manifest.json")), StandardCharsets.UTF_8));
        onDisk = new LinkedHashMap<>();
        for (String name : BUNDLES) {
            onDisk.put(name, Files.readAllBytes(fixture.resolve(name)));
        }
    }

    @Test
    @DisplayName("the repository's own form loads, and that is the baseline")
    void compressedRepositoryFormLoads() {
        WorldData world = WorldData.fromSource(manifest, name -> {
            byte[] bytes = onDisk.get(name);
            assertNotNull(bytes, "the test asked for a bundle that is not in the fixture: " + name);
            return bytes;
        });
        assertCountryScale(world);
    }

    @Test
    @DisplayName("the packaged form loads: inflated contents, .gz suffix stripped")
    void packagedFormLoads() {
        // This is what an Android build actually ships. Before the fix it threw "world bundle missing".
        WorldData world = WorldData.fromSource(manifest, packagedWithStrippedNames());
        assertCountryScale(world);
        assertEquals(2, world.manifest.format, "the manifest survived the round trip");
    }

    @Test
    @DisplayName("a mixed form is not a trap: gzip contents under the short name, or plain under the long")
    void mixedFormsLoad() {
        // A packager that renamed without unpacking, or unpacked without renaming: both have to work,
        // because the next build tool may do either.
        Map<String, byte[]> renamedOnly = new LinkedHashMap<>();
        Map<String, byte[]> unpackedOnly = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : onDisk.entrySet()) {
            renamedOnly.put(strip(entry.getKey()), entry.getValue());
            unpackedOnly.put(entry.getKey(), inflate(entry.getKey(), entry.getValue()));
        }
        assertCountryScale(WorldData.fromSource(manifest, name -> {
            byte[] direct = renamedOnly.get(name);
            return direct != null ? direct : renamedOnly.get(strip(name));
        }));
        assertCountryScale(WorldData.fromSource(manifest, name -> {
            byte[] direct = unpackedOnly.get(name);
            return direct != null ? direct : unpackedOnly.get(strip(name));
        }));
    }

    @Test
    @DisplayName("a bundle that is genuinely missing still says so, with both names it looked for")
    void aMissingBundleIsStillAnError() {
        WorldData.fromSource(manifest, packagedWithStrippedNames());   // baseline: this one works
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> WorldData.fromSource(manifest, name -> null));
        assertTrue(thrown.getMessage().contains("world bundle missing"),
                "a missing bundle is not reported as missing: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("strings.bin.gz"),
                "the error does not name the bundle the manifest asked for: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a corrupt or truncated bundle is refused, not silently used")
    void corruptionIsRefused() {
        byte[] good = onDisk.get("roads.bin.gz");
        byte[] bad = good.clone();
        bad[bad.length / 2] ^= 0x01;                        // a single flipped byte
        Map<String, byte[]> files = new LinkedHashMap<>(onDisk);
        files.put("roads.bin.gz", bad);
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> WorldData.fromSource(manifest, name -> files.get(name)));
        assertTrue(thrown.getMessage().toLowerCase(Locale.ROOT).contains("sha-256"),
                "a damaged bundle was not caught by its digest: " + thrown.getMessage());
    }

    @Test
    @DisplayName("the name a bundle is stored under is resolved, and the resolution is pure")
    void storedNamesResolve() {
        Set<String> packaged = new LinkedHashSet<>();
        for (String name : BUNDLES) {
            packaged.add(strip(name));
        }
        for (String name : BUNDLES) {
            assertEquals(name, WorldData.storedName(name, packaged::contains),
                    "the exact name is used when it exists");
            assertEquals(strip(name), WorldData.storedName(name, candidate -> packaged.contains(candidate)),
                    "the stripped name is used when only that one exists: " + name);
        }
        // A name with no .gz suffix is not guessed at: it either exists or the load fails honestly.
        assertEquals("manifest.json", WorldData.storedName("manifest.json", packaged::contains));
        assertFalse(packaged.contains(".gz"), "the fixture should not contain a bare .gz name");
        assertEquals("nothing.bin.gz", WorldData.storedName("nothing.bin.gz", name -> false),
                "an unresolvable name comes back unchanged, so the error names what was asked for");
    }

    @Test
    @DisplayName("the digest is checked whenever the bytes are still the bytes that were hashed")
    void digestsAreCheckedWhenTheyCanBe() {
        // Compressed: the manifest digest is the digest of the file on disk, and a mismatch is fatal.
        assertTrue(BundleReader.isGzip(onDisk.get("terrain_l1.bin.gz")),
                "the fixture is not gzip, so this test proves nothing");
        String sha = manifest.terrainL1.sha256;
        assertFalse(sha == null || sha.isEmpty(), "the fixture manifest records no digest");
        BundleReader.open("terrain_l1", onDisk.get("terrain_l1.bin.gz"), sha);

        // Inflated: the hashed bytes are not the packaged bytes, so the digest cannot be checked here and
        // the loader says so rather than pretending.
        byte[] inflated = inflate("terrain_l1.bin.gz", onDisk.get("terrain_l1.bin.gz"));
        assertFalse(BundleReader.isGzip(inflated), "an inflated payload still looks like gzip");
        BundleReader reader = BundleReader.openPackaged("terrain_l1.bin.gz", inflated, sha);
        assertEquals(inflated.length, reader.payloadBytes().length,
                "the inflated payload did not survive the packaged path");
    }

    /** A source that behaves like the APK does: short names, inflated contents, nothing else. */
    private static WorldData.BundleSource packagedWithStrippedNames() {
        Map<String, byte[]> packaged = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : onDisk.entrySet()) {
            packaged.put(strip(entry.getKey()), inflate(entry.getKey(), entry.getValue()));
        }
        return name -> {
            byte[] direct = packaged.get(name);
            if (direct != null) {
                return direct;
            }
            // 'short' is a Java keyword. Naming it that was a syntax error, which is the same class of
            // mistake as the reserved word that stopped the shader compiling -- so it is left here as a
            // comment rather than quietly forgotten.
            String shortName = strip(name);
            byte[] bytes = packaged.get(shortName);
            assertNotNull(bytes, "the packaged source has no " + name + " or " + shortName);
            return bytes;
        };
    }

    private static void assertCountryScale(WorldData world) {
        assertNotNull(world, "the world did not load");
        assertNotNull(world.strings, "the string table is missing");
        assertTrue(world.strings.size() > 0, "the string table is empty");
        assertNotNull(world.buildings, "the buildings are missing");
        assertTrue(world.buildings.ringCount > 0, "no building footprints survived");
        assertNotNull(world.roads, "the roads are missing");
        assertTrue(world.roads.count > 0, "no roads survived");
        assertTrue(world.points == null || world.points.count > 0, "the points bundle loaded empty");
    }

    private static String strip(String name) {
        return name.endsWith(".gz") ? name.substring(0, name.length() - 3) : name;
    }

    private static byte[] inflate(String name, byte[] gzipBytes) {
        return BundleReader.inflate(name, gzipBytes);
    }
}
