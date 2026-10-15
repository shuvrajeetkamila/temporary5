package ai.arena.qatar.render;

import ai.arena.qatar.render.ChunkMesher.ChunkMesh;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.LongMap;

/**
 * The only class in the project that talks to OpenGL.
 *
 * <p>Everything expensive (deciding what to draw, generating triangles) lives in classes that can be
 * tested without a GPU; this one uploads finished vertex arrays and issues draw calls. Keeping the GL
 * surface this small is deliberate: it is the part that cannot be tested headlessly, so it is the part
 * that must be trivially simple.
 *
 * <p>Per chunk it keeps ground, water and detail (buildings, walls, roads) as separate meshes, because
 * they need different render state: water is drawn without back-face culling so a lake is visible from
 * a boat, and closing that state again after every chunk would cost more than the extra draw call.
 *
 * <p>Detail is an <b>array</b> of meshes, because a dense chunk of a capital city needs more than the
 * 65,000 vertices a 16-bit index buffer holds: {@link ChunkMesher.ChunkMesh} splits it into parts, and
 * this class uploads and draws each part. The alternative -- one mesh, cut off at the limit -- either
 * loses the middle of Doha or, as the third install of this game discovered the hard way, throws
 * halfway through a frame and takes the process with it.
 */
public final class WorldRenderer implements Disposable {

    /** Sun direction: mid-morning, from the north-east, which is how Qatar actually looks. */
    private static final Vector3 SUN_DIRECTION = new Vector3(0.45f, 0.72f, -0.53f).nor();
    private static final Vector3 SUN_COLOUR = new Vector3(1.05f, 1.0f, 0.92f);
    private static final Vector3 FOG_COLOUR = new Vector3(0.78f, 0.80f, 0.84f);
    private static final Vector3 ZENITH_COLOUR = new Vector3(0.32f, 0.52f, 0.80f);
    private static final float FOG_START = 900.0f;
    private static final float FOG_END = 5600.0f;

    private ShaderProgram worldProgram;
    private ShaderProgram skyProgram;
    private Mesh skyQuad;
    /** The player's own body: the only mesh in the game that moves every frame. */
    private Mesh actorMesh;
    /** Wheels, a propeller, rotors: the parts of that body that turn where they are. */
    private Mesh[] actorParts = new Mesh[0];
    /** One matrix per moving part, reused every frame: a car has four wheels, so this never grows. */
    private Matrix4[] partScratch = new Matrix4[8];

    private final LongMap<GpuChunk> chunks = new LongMap<>();
    private final Matrix4 identity = new Matrix4();
    private final Vector3 cameraPosition = new Vector3();
    private float time;
    private int drawCalls;
    private int drawnTriangles;

    private static final class GpuChunk {
        Mesh land;
        Mesh water;
        /** One mesh per CPU part of the chunk's detail; empty when the chunk has none. */
        Mesh[] detail = new Mesh[0];

        int vertices() {
            int vertices = 0;
            if (land != null) {
                vertices += land.getNumVertices();
            }
            if (water != null) {
                vertices += water.getNumVertices();
            }
            for (Mesh part : detail) {
                if (part != null) {
                    vertices += part.getNumVertices();
                }
            }
            return vertices;
        }
    }

    public boolean isReady() {
        return worldProgram != null && skyProgram != null;
    }

    /**
     * The driver's own words about the last shader compilation, empty when it succeeded. Kept in a
     * static because the interesting part of a failed launch is exactly this text, and the only place
     * it can be read on a phone is the diagnostics panel.
     */
    public static String lastShaderLog() {
        return lastShaderLog;
    }

    private static String lastShaderLog = "";

    /** Compiles shaders and creates the sky quad. Returns false when shaders could not compile. */
    public boolean init() {
        ShaderProgram.pedantic = false;
        worldProgram = new ShaderProgram(ShaderSources.VERTEX, ShaderSources.FRAGMENT);
        skyProgram = new ShaderProgram(ShaderSources.SKY_VERTEX, ShaderSources.SKY_FRAGMENT);
        String worldLog = worldProgram.getLog();
        String skyLog = skyProgram.getLog();
        if (!worldProgram.isCompiled() || !skyProgram.isCompiled()) {
            lastShaderLog = "world: " + worldLog + " | sky: " + skyLog;
            Gdx.app.error("WorldRenderer", "shader compilation failed:\n" + lastShaderLog);
            shutdown();
            return false;
        }
        lastShaderLog = "";

        // A single quad in clip space, drawn without depth so it can never hide the world.
        skyQuad = new Mesh(true, 4, 0,
                new com.badlogic.gdx.graphics.VertexAttribute(
                        com.badlogic.gdx.graphics.VertexAttributes.Usage.Position, 2, "a_position"));
        skyQuad.setVertices(new float[]{-1, -1, 1, -1, 1, 1, -1, 1});
        return true;
    }

    /** Starts a frame: clears to the horizon colour and prepares state. */
    public void begin(Camera camera, float deltaSeconds) {
        if (!isReady()) {
            // No shaders, no world. Draw one flat colour rather than nothing at all: a grey screen with
            // the diagnostics panel over it says "the renderer is broken", which a blue void does not.
            Gdx.gl.glClearColor(0.35f, 0.35f, 0.38f, 1f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            return;
        }
        time += deltaSeconds;
        drawCalls = 0;
        drawnTriangles = 0;
        cameraPosition.set((float) camera.position.x, (float) camera.position.y,
                (float) camera.position.z);

        Gdx.gl.glClearColor(FOG_COLOUR.x, FOG_COLOUR.y, FOG_COLOUR.z, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        Gdx.gl.glCullFace(GL20.GL_BACK);
        Gdx.gl.glFrontFace(GL20.GL_CCW);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Sky first, with depth writes off, so terrain at any distance covers it.
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        skyProgram.bind();
        skyProgram.setUniformMatrix("u_invProjView", camera.invProjectionView);
        skyProgram.setUniformf("u_camera", cameraPosition);
        skyProgram.setUniformf("u_zenithColour", ZENITH_COLOUR);
        skyProgram.setUniformf("u_fogColour", FOG_COLOUR);
        skyProgram.setUniformf("u_sunDirection", SUN_DIRECTION);
        skyQuad.render(skyProgram, GL20.GL_TRIANGLE_FAN);
        drawCalls++;
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);

        worldProgram.bind();
        worldProgram.setUniformMatrix("u_projView", camera.combined);
        worldProgram.setUniformf("u_camera", cameraPosition);
        worldProgram.setUniformf("u_sunDirection", SUN_DIRECTION);
        worldProgram.setUniformf("u_sunColour", SUN_COLOUR);
        worldProgram.setUniformf("u_fogColour", FOG_COLOUR);
        worldProgram.setUniformf("u_fogStart", FOG_START);
        worldProgram.setUniformf("u_fogEnd", FOG_END);
        worldProgram.setUniformf("u_time", time);
    }

    /** Uploads the GPU meshes for one chunk. Re-uploading a resident chunk replaces its meshes. */
    public void upload(long key, ChunkMesh mesh) {
        GpuChunk gpu = chunks.get(key);
        if (gpu == null) {
            gpu = new GpuChunk();
            chunks.put(key, gpu);
        }
        gpu.land = upload(gpu.land, mesh.land);
        gpu.water = upload(gpu.water, mesh.water);
        for (Mesh existing : gpu.detail) {
            if (existing != null) {
                existing.dispose();
            }
        }
        gpu.detail = new Mesh[mesh.detail.size()];
        for (int i = 0; i < gpu.detail.length; i++) {
            gpu.detail[i] = upload(null, mesh.detail.get(i));
        }
    }

    private Mesh upload(Mesh existing, MeshData data) {
        if (data.isEmpty()) {
            if (existing != null) {
                existing.dispose();
            }
            return null;
        }
        if (existing != null) {
            existing.dispose();
        }
        Mesh mesh = new Mesh(true, data.vertexCount(), data.indexCount(), VertexFormat.attributes());
        mesh.setVertices(data.vertices(), 0, data.vertexCount() * VertexFormat.FLOATS_PER_VERTEX);
        mesh.setIndices(data.indices(), 0, data.indexCount());
        return mesh;
    }

    /** Draws one chunk. The offset places the chunk's local coordinates back in the world. */
    public void draw(long key, double offsetEast, double offsetNorth) {
        if (!isReady()) {
            return;
        }
        GpuChunk gpu = chunks.get(key);
        if (gpu == null) {
            return;
        }
        identity.setToTranslation((float) offsetEast, 0f, (float) offsetNorth);
        worldProgram.setUniformMatrix("u_model", identity);
        if (gpu.land != null) {
            gpu.land.render(worldProgram, GL20.GL_TRIANGLES);
            drawCalls++;
            drawnTriangles += gpu.land.getNumIndices() / 3;
        }
        for (Mesh part : gpu.detail) {
            if (part == null) {
                continue;
            }
            part.render(worldProgram, GL20.GL_TRIANGLES);
            drawCalls++;
            drawnTriangles += part.getNumIndices() / 3;
        }
        if (gpu.water != null) {
            // Water is visible from both sides (a boat, a bridge, a plane), so culling is off for it.
            Gdx.gl.glDisable(GL20.GL_CULL_FACE);
            gpu.water.render(worldProgram, GL20.GL_TRIANGLES);
            Gdx.gl.glEnable(GL20.GL_CULL_FACE);
            drawCalls++;
            drawnTriangles += gpu.water.getNumIndices() / 3;
        }
    }

    /**
     * Ends a 3D frame and leaves the GL state the way 2D drawing expects it.
     *
     * <p>The state this frame set -- depth testing, back-face culling -- is right for terrain, buildings
     * and road ribbons and wrong for everything drawn after them. Leaving culling enabled is what made
     * the controls, the minimap and the diagnostics panel invisible on a phone: SpriteBatch's quads are
     * clockwise and the front face here is counter-clockwise, so the GPU culled every one of them.
     *
     * <p>{@link GlState2D} is the authoritative fix, called by the 2D code itself; this is the belt to
     * that pair of braces, for anything drawn later that has not read it.
     */
    public void endFrame() {
        if (!isReady()) {
            return;
        }
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
    }

    /**
     * Uploads the player's body, replacing whatever was there: the player changes vehicle, not shape.
     * Called when the locomotion type changes and never per frame.
     */
    public void setActorMesh(MeshData data) {
        actorMesh = upload(actorMesh, data);
    }

    /** Uploads the player's body together with the parts of it that move. */
    public void setActor(MeshData body, java.util.List<BodyMesher.Part> parts) {
        setActorMesh(body);
        for (Mesh existing : actorParts) {
            if (existing != null) {
                existing.dispose();
            }
        }
        if (partScratch.length < parts.size()) {
            partScratch = java.util.Arrays.copyOf(partScratch, parts.size());
        }
        actorParts = new Mesh[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            actorParts[i] = upload(null, parts.get(i).mesh);
            if (partScratch[i] == null) {
                partScratch[i] = new Matrix4();
            }
        }
    }

    /**
     * Draws the player's body. The world program must still be bound, so this is called straight after
     * the chunk loop and the uniforms are already set.
     */
    public void drawActor(Matrix4 transform) {
        if (actorMesh == null || !isReady()) {
            return;
        }
        // The state the terrain was drawn with, set again here rather than assumed: a caller is free to
        // draw its body at any point in a frame, and the HUD's own state call (see GlState2D) turns
        // depth testing and culling off for 2D. Without this the body would be drawn with its far side
        // painted over its near side.
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        Gdx.gl.glCullFace(GL20.GL_BACK);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        worldProgram.bind();
        worldProgram.setUniformMatrix("u_model", transform);
        actorMesh.render(worldProgram, GL20.GL_TRIANGLES);
        drawCalls++;
        drawnTriangles += actorMesh.getNumIndices() / 3;
    }

    /**
     * Draws the body and every moving part of it. Each part is placed at its own pivot first and turned
     * second, so a wheel spins on its axle instead of orbiting the car.
     */
    public void drawActor(Matrix4 bodyTransform, java.util.List<BodyMesher.Part> parts,
                          double localEast, double localHeight, double localNorth,
                          double headingDegrees, double spinDegrees) {
        drawActor(bodyTransform);
        for (int i = 0; i < actorParts.length && i < parts.size(); i++) {
            Mesh part = actorParts[i];
            if (part == null || partScratch[i] == null) {
                continue;
            }
            BodyMesher.transform(partScratch[i], parts.get(i), localEast, localHeight, localNorth,
                    headingDegrees, spinDegrees);
            worldProgram.setUniformMatrix("u_model", partScratch[i]);
            part.render(worldProgram, GL20.GL_TRIANGLES);
            drawCalls++;
            drawnTriangles += part.getNumIndices() / 3;
        }
        endFrame();
    }

    public void release(long key) {
        GpuChunk gpu = chunks.remove(key);
        if (gpu == null) {
            return;
        }
        if (gpu.land != null) {
            gpu.land.dispose();
        }
        if (gpu.water != null) {
            gpu.water.dispose();
        }
        for (Mesh part : gpu.detail) {
            if (part != null) {
                part.dispose();
            }
        }
    }

    public int drawCalls() {
        return drawCalls;
    }

    public int drawnTriangles() {
        return drawnTriangles;
    }

    public int residentChunks() {
        return chunks.size;
    }

    /** Vertices the GPU is holding for resident chunks: the number that grows until a phone complains. */
    public int residentVertices() {
        int vertices = 0;
        for (LongMap.Entry<GpuChunk> entry : chunks.entries()) {
            vertices += entry.value.vertices();
        }
        return vertices;
    }

    private void shutdown() {
        if (worldProgram != null) {
            worldProgram.dispose();
            worldProgram = null;
        }
        if (skyProgram != null) {
            skyProgram.dispose();
            skyProgram = null;
        }
        if (skyQuad != null) {
            skyQuad.dispose();
            skyQuad = null;
        }
        if (actorMesh != null) {
            actorMesh.dispose();
            actorMesh = null;
        }
        for (Mesh part : actorParts) {
            if (part != null) {
                part.dispose();
            }
        }
        actorParts = new Mesh[0];
    }

    @Override
    public void dispose() {
        for (LongMap.Entry<GpuChunk> entry : chunks.entries()) {
            if (entry.value.land != null) {
                entry.value.land.dispose();
            }
            if (entry.value.water != null) {
                entry.value.water.dispose();
            }
            for (Mesh part : entry.value.detail) {
                if (part != null) {
                    part.dispose();
                }
            }
        }
        chunks.clear();
        shutdown();
    }
}
