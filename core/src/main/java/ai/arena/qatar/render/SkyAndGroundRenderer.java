package ai.arena.qatar.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

import ai.arena.qatar.physics.RigidBody;

/**
 * First real 3D pass: terrain, Gulf water, and solid props, all lit by a single directional sun with
 * distance haze.
 *
 * <p>Milestone 0 uses a small procedural ground patch so the toolchain (GLES3 + shaders + fixed-step
 * physics + camera) is proven before any country data is streamed. The patch is replaced by compiled
 * chunk meshes in Milestone 1-3; the material model, haze and water shading are already the final
 * ones so the swap is data, not code.
 */
public final class SkyAndGroundRenderer {

    private static final int GROUND_SEGMENTS = 64;
    private static final float GROUND_EXTENT = 900f;

    private ShaderProgram terrainShader;
    private ShaderProgram waterShader;
    private ShaderProgram solidShader;

    private Mesh groundMesh;
    private Mesh waterMesh;
    private Mesh cubeMesh;

    private final Matrix4 model = new Matrix4();
    private final Matrix4 identity = new Matrix4();
    private final Vector3 sunDirection = new Vector3(-0.42f, 0.66f, 0.62f).nor();
    private final Vector3 sunColor = new Vector3(1.0f, 0.94f, 0.82f);
    private final Vector3 skyColor = new Vector3(0.42f, 0.52f, 0.66f);
    private final Vector3 fogColor = new Vector3(0.72f, 0.78f, 0.84f);

    private boolean shadersOk = true;
    private String shaderError = "";

    public SkyAndGroundRenderer() {
        terrainShader = compile(ShaderSources.TERRAIN_VERT, ShaderSources.TERRAIN_FRAG, "terrain");
        waterShader = compile(ShaderSources.WATER_VERT, ShaderSources.WATER_FRAG, "water");
        solidShader = compile(ShaderSources.SOLID_VERT, ShaderSources.SOLID_FRAG, "solid");
        buildGround();
        buildWater();
        buildCube();
    }

    private ShaderProgram compile(String vert, String frag, String name) {
        ShaderProgram program = new ShaderProgram(vert, frag);
        if (!program.isCompiled()) {
            shadersOk = false;
            shaderError = name + ": " + program.getLog();
            Gdx.app.error("Shader", "Compile failed for '" + name + "':\n" + program.getLog());
        }
        return program;
    }

    private void buildGround() {
        int verts = (GROUND_SEGMENTS + 1) * (GROUND_SEGMENTS + 1);
        int indices = GROUND_SEGMENTS * GROUND_SEGMENTS * 6;
        groundMesh = new Mesh(true, verts, indices,
                new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.Normal, 3, "a_normal"),
                new VertexAttribute(VertexAttributes.Usage.Generic, 3, "a_biome"));
        float[] v = new float[verts * 9];
        short[] idx = new short[indices];
        float step = (GROUND_EXTENT * 2f) / GROUND_SEGMENTS;
        for (int z = 0; z <= GROUND_SEGMENTS; z++) {
            for (int x = 0; x <= GROUND_SEGMENTS; x++) {
                int i = z * (GROUND_SEGMENTS + 1) + x;
                float wx = -GROUND_EXTENT + x * step;
                float wz = -GROUND_EXTENT + z * step;
                float h = placeholderHeight(wx, wz);
                float hx = placeholderHeight(wx + step, wz) - h;
                float hz = placeholderHeight(wx, wz + step) - h;
                // Normal from finite differences of the same function the height comes from.
                Vector3 n = new Vector3(-hx, step, -hz).nor();
                float[] colour = placeholderAlbedo(wx, wz, h);
                int o = i * 9;
                v[o] = wx;
                v[o + 1] = h;
                v[o + 2] = wz;
                v[o + 3] = n.x;
                v[o + 4] = n.y;
                v[o + 5] = n.z;
                v[o + 6] = colour[0];
                v[o + 7] = colour[1];
                v[o + 8] = colour[2];
            }
        }
        int k = 0;
        for (int z = 0; z < GROUND_SEGMENTS; z++) {
            for (int x = 0; x < GROUND_SEGMENTS; x++) {
                int i0 = z * (GROUND_SEGMENTS + 1) + x;
                int i1 = i0 + 1;
                int i2 = i0 + (GROUND_SEGMENTS + 1);
                int i3 = i2 + 1;
                idx[k++] = (short) i0;
                idx[k++] = (short) i2;
                idx[k++] = (short) i1;
                idx[k++] = (short) i1;
                idx[k++] = (short) i2;
                idx[k++] = (short) i3;
            }
        }
        groundMesh.setVertices(v);
        groundMesh.setIndices(idx);
    }

    /** Placeholder relief until compiled country elevation is streamed (clearly not real geography). */
    private static float placeholderHeight(float x, float z) {
        return (float) (2.6 * Math.sin(x * 0.0031) * Math.cos(z * 0.0027)
                + 0.9 * Math.sin(x * 0.0113 + z * 0.0071)
                + 0.35 * Math.sin(z * 0.021 + x * 0.004));
    }

    private static float[] placeholderAlbedo(float x, float z, float h) {
        // Sand/gravel/sabkha-ish palettes, blended by slope-independent noise for the toolchain proof.
        double n = 0.5 + 0.5 * Math.sin(x * 0.0047 + z * 0.0039);
        float r = (float) (0.78 + 0.08 * n);
        float g = (float) (0.72 + 0.07 * n);
        float b = (float) (0.58 + 0.06 * n);
        if (h < 0.15f) {
            // Pale sabkha/limestone flats near the water line.
            r = 0.86f;
            g = 0.85f;
            b = 0.79f;
        }
        return new float[]{r, g, b};
    }

    private void buildWater() {
        float e = GROUND_EXTENT * 6f;
        waterMesh = new Mesh(true, 4, 6,
                new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"));
        waterMesh.setVertices(new float[]{
                -e, 0f, -e,
                e, 0f, -e,
                -e, 0f, e,
                e, 0f, e
        });
        waterMesh.setIndices(new short[]{0, 2, 1, 1, 2, 3});
    }

    private void buildCube() {
        // Unit cube about the origin: reused for the physics crate and static collision boxes.
        float[] p = {
                -1, -1, -1, 0, 0, -1,
                1, -1, -1, 0, 0, -1,
                1, -1, 1, 0, 0, -1,
                -1, -1, 1, 0, 0, -1,
                -1, 1, -1, 0, 0, 1,
                1, 1, -1, 0, 0, 1,
                1, 1, 1, 0, 0, 1,
                -1, 1, 1, 0, 0, 1,
                -1, -1, -1, -1, 0, 0,
                -1, 1, -1, -1, 0, 0,
                -1, 1, 1, -1, 0, 0,
                -1, -1, 1, -1, 0, 0,
                1, -1, -1, 1, 0, 0,
                1, 1, -1, 1, 0, 0,
                1, 1, 1, 1, 0, 0,
                1, -1, 1, 1, 0, 0,
                -1, -1, -1, 0, -1, 0,
                -1, -1, 1, 0, -1, 0,
                1, -1, 1, 0, -1, 0,
                1, -1, -1, 0, -1, 0,
                -1, 1, -1, 0, 1, 0,
                -1, 1, 1, 0, 1, 0,
                1, 1, 1, 0, 1, 0,
                1, 1, -1, 0, 1, 0
        };
        short[] i = {
                0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6,
                8, 9, 10, 8, 10, 11, 12, 14, 13, 12, 15, 14,
                16, 18, 17, 16, 19, 18, 20, 21, 22, 20, 22, 23
        };
        cubeMesh = new Mesh(true, 24, i.length,
                new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.Normal, 3, "a_normal"));
        cubeMesh.setVertices(p);
        cubeMesh.setIndices(i);
    }

    public void render(Camera camera, Vector3 focus, float time, float interpolationAlpha, RigidBody crate) {
        GL20 gl = Gdx.gl;
        gl.glEnable(GL20.GL_DEPTH_TEST);
        gl.glEnable(GL20.GL_CULL_FACE);
        gl.glCullFace(GL20.GL_BACK);

        renderGround(camera, focus);
        renderWater(camera, focus, time);
        renderStaticBoxes(camera, focus);
        if (crate != null) {
            renderCrate(camera, crate, interpolationAlpha);
        }
        gl.glDisable(GL20.GL_CULL_FACE);
    }

    private void renderGround(Camera camera, Vector3 focus) {
        if (!shadersOk) {
            return;
        }
        model.idt().setToTranslation(focus.x, 0f, focus.z);
        terrainShader.begin();
        terrainShader.setUniformMatrix("u_projView", camera.combined);
        terrainShader.setUniformMatrix("u_model", model);
        terrainShader.setUniformf("u_sunDirection", sunDirection);
        terrainShader.setUniformf("u_sunColor", sunColor);
        terrainShader.setUniformf("u_skyColor", skyColor);
        terrainShader.setUniformf("u_fogColor", fogColor);
        terrainShader.setUniformf("u_fogDensity", 0.00035f);
        terrainShader.setUniformf("u_heatHaze", 0f);
        terrainShader.setUniformf("u_cameraLocal", camera.position);
        groundMesh.render(terrainShader, GL20.GL_TRIANGLES);
        terrainShader.end();
    }

    private void renderWater(Camera camera, Vector3 focus, float time) {
        if (!shadersOk) {
            return;
        }
        model.idt().setToTranslation(focus.x, 0f, focus.z);
        waterShader.begin();
        waterShader.setUniformMatrix("u_projView", camera.combined);
        waterShader.setUniformMatrix("u_model", model);
        waterShader.setUniformf("u_time", time);
        waterShader.setUniformf("u_waveScale", 1f);
        waterShader.setUniformf("u_sunDirection", sunDirection);
        waterShader.setUniformf("u_sunColor", sunColor);
        waterShader.setUniformf("u_skyColor", skyColor);
        waterShader.setUniformf("u_fogColor", fogColor);
        waterShader.setUniformf("u_fogDensity", 0.00035f);
        waterShader.setUniformf("u_depthBlend", 0.55f);
        waterShader.setUniformf("u_shallowColor", 0.18f, 0.62f, 0.62f);
        waterShader.setUniformf("u_deepColor", 0.03f, 0.20f, 0.32f);
        waterShader.setUniformf("u_cameraLocal", camera.position);
        waterMesh.render(waterShader, GL20.GL_TRIANGLES);
        waterShader.end();
    }

    private final java.util.List<ai.arena.qatar.physics.StaticBox> pendingBoxes = new java.util.ArrayList<>();

    /** Static collision boxes are drawn too: "what you collide with is what you see". */
    public void setStaticBoxes(java.util.List<ai.arena.qatar.physics.StaticBox> boxes) {
        pendingBoxes.clear();
        pendingBoxes.addAll(boxes);
    }

    private void renderStaticBoxes(Camera camera, Vector3 focus) {
        if (!shadersOk) {
            return;
        }
        for (ai.arena.qatar.physics.StaticBox box : pendingBoxes) {
            model.idt()
                    .setToTranslation((float) box.center.x, (float) box.center.y, (float) box.center.z);
            float sx = (float) box.halfExtents.x;
            float sy = (float) box.halfExtents.y;
            float sz = (float) box.halfExtents.z;
            model.scale(sx, sy, sz);
            drawSolid(camera, model, new Vector3(0.74f, 0.71f, 0.66f), 0.85f, 0.0f);
        }
    }

    private final Matrix4 tempModel = new Matrix4();

    private void renderCrate(Camera camera, RigidBody crate, float alpha) {
        if (!shadersOk) {
            return;
        }
        float px = (float) (crate.previousPosition.x + (crate.position.x - crate.previousPosition.x) * alpha);
        float py = (float) (crate.previousPosition.y + (crate.position.y - crate.previousPosition.y) * alpha);
        float pz = (float) (crate.previousPosition.z + (crate.position.z - crate.previousPosition.z) * alpha);
        tempModel.idt().setToTranslation(px, py, pz);
        tempModel.rotate(0f, 1f, 0f, (float) Math.toDegrees(crate.orientation.yaw()));
        tempModel.scale(0.35f, 0.35f, 0.35f);
        drawSolid(camera, tempModel, new Vector3(0.72f, 0.36f, 0.22f), 0.65f, 0.1f);
    }

    private void drawSolid(Camera camera, Matrix4 modelMatrix, Vector3 color, float roughness, float metallic) {
        solidShader.begin();
        solidShader.setUniformMatrix("u_projView", camera.combined);
        solidShader.setUniformMatrix("u_model", modelMatrix);
        solidShader.setUniformf("u_baseColor", color);
        solidShader.setUniformf("u_sunDirection", sunDirection);
        solidShader.setUniformf("u_sunColor", sunColor);
        solidShader.setUniformf("u_skyColor", skyColor);
        solidShader.setUniformf("u_fogColor", fogColor);
        solidShader.setUniformf("u_fogDensity", 0.00035f);
        solidShader.setUniformf("u_roughness", roughness);
        solidShader.setUniformf("u_metallic", metallic);
        solidShader.setUniformf("u_cameraLocal", camera.position);
        cubeMesh.render(solidShader, GL20.GL_TRIANGLES);
        solidShader.end();
    }

    public boolean shadersCompiled() {
        return shadersOk;
    }

    public String shaderError() {
        return shaderError;
    }

    public void dispose() {
        if (terrainShader != null) {
            terrainShader.dispose();
        }
        if (waterShader != null) {
            waterShader.dispose();
        }
        if (solidShader != null) {
            solidShader.dispose();
        }
        if (groundMesh != null) {
            groundMesh.dispose();
        }
        if (waterMesh != null) {
            waterMesh.dispose();
        }
        if (cubeMesh != null) {
            cubeMesh.dispose();
        }
    }
}
