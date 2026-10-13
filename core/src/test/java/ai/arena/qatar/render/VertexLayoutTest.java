package ai.arena.qatar.render;

import ai.arena.qatar.world.Buildings;
import ai.arena.qatar.world.BundleReader;
import ai.arena.qatar.world.GameManifest;
import ai.arena.qatar.world.MaterialGrid;
import ai.arena.qatar.world.Roads;
import ai.arena.qatar.world.TerrainGrid;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract between the three things that have to agree before a single triangle reaches the screen:
 * the floats {@link MeshData} writes, the attributes {@link VertexFormat} declares, and the shader that
 * reads them.
 *
 * <p>This exists because they did not agree. The layout declared the auxiliary data as four packed
 * bytes (a 28-byte vertex) while every mesher wrote four floats (a 40-byte vertex), so the driver was
 * told each vertex began twelve bytes before it did; and the shader then divided by 255 what the meshers
 * had written as plain numbers, so a wall (2.0) read as 510 and the whole country fell through to the
 * default asphalt grey. None of it is visible without a GPU, which is why it is asserted here instead.
 */
final class VertexLayoutTest {

    private static final double ORIGIN_E = 553000.0;
    private static final double ORIGIN_N = 2796000.0;

    private static GameManifest manifest;
    private static TerrainGrid terrain;
    private static MaterialGrid materials;
    private static Path fixture;

    @BeforeAll
    static void load() throws IOException {
        fixture = Paths.get("src/test/resources/synthetic_world");
        if (!Files.isDirectory(fixture)) {
            fixture = Paths.get("core/src/test/resources/synthetic_world");
        }
        manifest = GameManifest.parse(Files.readString(fixture.resolve("manifest.json")));
        byte[] terrainZip = Files.readAllBytes(fixture.resolve(manifest.terrainL1.file));
        terrain = TerrainGrid.parse(BundleReader.open("terrain", terrainZip, manifest.terrainL1.sha256)
                .payloadBytes(), ORIGIN_E, ORIGIN_N, "QTT1");
        byte[] materialZip = Files.readAllBytes(fixture.resolve(manifest.materials.file));
        materials = MaterialGrid.parse(BundleReader.open("materials", materialZip,
                manifest.materials.sha256).payloadBytes(), ORIGIN_E, ORIGIN_N);
    }

    @Test
    @DisplayName("the declared vertex stride is the stride the meshers write")
    void strideMatchesTheData() {
        int floatBytes = VertexFormat.FLOATS_PER_VERTEX * 4;
        assertEquals(MeshData.FLOATS_PER_VERTEX, VertexFormat.FLOATS_PER_VERTEX,
                "the two vertex classes disagree about how many floats a vertex is");
        assertEquals(floatBytes, VertexFormat.attributes().vertexSize,
                "the shader is told a vertex is " + VertexFormat.attributes().vertexSize
                        + " bytes while the meshers write " + floatBytes);
        assertEquals(floatBytes, VertexFormat.BYTES_PER_VERTEX,
                "BYTES_PER_VERTEX is not the layout's stride");

        int[] widths = {3, 3, 4, 1};
        String[] names = {VertexFormat.ATTRIBUTE_POSITION, VertexFormat.ATTRIBUTE_NORMAL,
                VertexFormat.ATTRIBUTE_AUX, VertexFormat.ATTRIBUTE_MATERIAL};
        assertEquals(widths.length, VertexFormat.attributes().size(), "attribute count changed");
        for (int i = 0; i < widths.length; i++) {
            assertEquals(names[i], VertexFormat.attributes().get(i).alias, "attribute " + i + " renamed");
            assertEquals(widths[i], VertexFormat.attributes().get(i).numComponents,
                    names[i] + " no longer carries " + widths[i] + " values");
        }
    }

    @Test
    @DisplayName("every attribute the layout declares is wired through both shaders")
    void shaderDeclaresEveryAttribute() {
        Set<String> outs = vertexOutputs();
        for (int i = 0; i < VertexFormat.attributes().size(); i++) {
            String alias = VertexFormat.attributes().get(i).alias;
            assertTrue(vertexInputs().contains(alias),
                    "the vertex shader never reads " + alias + ", so the driver fills nothing");
        }
        assertEquals(vertexOutputs(), fragmentInputs(),
                "the fragment shader receives different varyings than the vertex shader writes");
        assertTrue(outs.contains("v_material"), "the material id does not reach the fragment shader");
        assertTrue(outs.contains("v_aux"), "the auxiliary data does not reach the fragment shader");
    }

    @Test
    @DisplayName("the shader reads the auxiliary data as floats, not as normalised bytes")
    void shaderReadsFloats() {
        String fragment = ShaderSources.FRAGMENT;
        assertFalse(fragment.contains("v_aux.x * 255.0"),
                "the surface type is scaled as if it were a byte again");
        assertFalse(fragment.contains("v_aux.w * 255.0"),
                "the material id is scaled as if it were a byte again");
        assertTrue(fragment.contains("int surface = int(v_aux.x + 0.5);"),
                "the surface type is not decoded as a float");
        assertTrue(fragment.contains("int material = int(v_material + 0.5);"),
                "the material id is not decoded from its own attribute");
        assertTrue(fragment.contains("tintFactor"),
                "the per-vertex tint the meshers compute is not applied anywhere");
    }

    @Test
    @DisplayName("the shader's surface codes are the ones the meshers write")
    void surfaceCodesMatch() {
        int[] cpp = {VertexFormat.SURFACE_LAND, VertexFormat.SURFACE_WATER, VertexFormat.SURFACE_WALL,
                VertexFormat.SURFACE_ROOF, VertexFormat.SURFACE_ROAD, VertexFormat.SURFACE_MARKING,
                VertexFormat.SURFACE_BODY};
        String[] names = {"SURFACE_LAND", "SURFACE_WATER", "SURFACE_WALL", "SURFACE_ROOF",
                "SURFACE_ROAD", "SURFACE_MARKING", "SURFACE_BODY"};
        for (int i = 0; i < names.length; i++) {
            String declaration = "const int " + names[i] + " = " + cpp[i] + ";";
            assertTrue(ShaderSources.FRAGMENT.contains(declaration),
                    "the fragment shader does not declare " + declaration);
        }
    }

    @Test
    @DisplayName("meshed vertices decode to surfaces the shader knows, and land names its material")
    void meshedVerticesDecode() throws IOException {
        TerrainMesher terrainMesher = new TerrainMesher(terrain, materials);
        MeshData land = terrainMesher.mesh(0, 0, 1600, 1600, true);
        MeshData water = terrainMesher.mesh(0, 0, 1600, 1600, false);
        assertFalse(land.isEmpty(), "no land was meshed");
        assertFalse(water.isEmpty(), "no water was meshed");

        int landMaterials = 0;
        landMaterials += check(land, new int[]{VertexFormat.SURFACE_LAND}, true);
        check(water, new int[]{VertexFormat.SURFACE_WATER}, false);

        Buildings buildings = Buildings.parse(BundleReader.open("buildings",
                Files.readAllBytes(fixture.resolve(manifest.buildings.file)), manifest.buildings.sha256),
                ORIGIN_E, ORIGIN_N);
        MeshData city = new MeshData();
        int[] rings = new int[buildings.ringCount];
        for (int i = 0; i < rings.length; i++) {
            rings[i] = i;
        }
        new BuildingMesher(buildings, terrain).mesh(0, 0, 1600, 1600, city, rings, rings.length);
        // A building is walls and a roof, and both are written by the same mesher.
        check(city, new int[]{VertexFormat.SURFACE_WALL, VertexFormat.SURFACE_ROOF}, false);

        Roads roads = Roads.parse(BundleReader.open("roads",
                Files.readAllBytes(fixture.resolve(manifest.roads.file)), manifest.roads.sha256),
                ORIGIN_E, ORIGIN_N);
        RoadMesher roadMesher = new RoadMesher(roads, terrain);
        MeshData asphalt = new MeshData();
        for (int line = 0; line < roads.count; line++) {
            roadMesher.addRoad(line, asphalt);
        }
        // Roads are asphalt with paint on them.
        check(asphalt, new int[]{VertexFormat.SURFACE_ROAD, VertexFormat.SURFACE_MARKING}, false);

        assertTrue(landMaterials > 0, "no land vertex named its material");
    }

    /** Asserts one mesh's vertices decode to one of {@code surfaces}, and counts named materials. */
    private static int check(MeshData mesh, int[] surfaces, boolean expectMaterial) {
        float[] v = mesh.vertices();
        assertTrue(v.length >= mesh.vertexCount() * MeshData.FLOATS_PER_VERTEX,
                "the vertex array is shorter than its own stride");
        int materials = 0;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int base = i * MeshData.FLOATS_PER_VERTEX;
            float type = v[base + 6];
            int decoded = (int) (type + 0.5f);
            boolean known = false;
            for (int surface : surfaces) {
                known |= decoded == surface;
            }
            assertTrue(known, "a vertex of this mesh decodes to surface " + decoded
                    + ", which is not one of the surfaces this mesh makes");
            float tint = v[base + 9];
            assertTrue(tint >= 0.05f && tint <= 2.0f,
                    "the tint is outside what tintFactor() clamps: " + tint);
            float material = v[base + 10];
            if (expectMaterial) {
                assertEquals(material, (float) (int) material, "the material id is not a whole number");
                assertTrue(material >= 0f && material <= 16f,
                        "a land vertex names a material with no palette: " + material);
                if (material > 0f) {
                    materials++;
                }
            } else {
                assertEquals(0f, material, "a non-land vertex names a material it has no palette for");
            }
        }
        return materials;
    }

    private static Set<String> vertexInputs() {
        return matches(ShaderSources.VERTEX, Pattern.compile("^\\s*in\\s+\\w+\\s+(\\w+)\\s*;", Pattern.MULTILINE));
    }

    private static Set<String> vertexOutputs() {
        return matches(ShaderSources.VERTEX, Pattern.compile("^\\s*out\\s+\\w+\\s+(\\w+)\\s*;", Pattern.MULTILINE));
    }

    private static Set<String> fragmentInputs() {
        return matches(ShaderSources.FRAGMENT, Pattern.compile("^\\s*in\\s+\\w+\\s+(\\w+)\\s*;", Pattern.MULTILINE));
    }

    private static Set<String> matches(String shader, Pattern pattern) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(shader);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
