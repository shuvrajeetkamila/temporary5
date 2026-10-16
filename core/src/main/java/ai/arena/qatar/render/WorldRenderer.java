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
    /** The fog the world is built with: the horizon the shader fades into. */
    public static final float FOG_START = 900.0f;
    public static final float FOG_END = 5600.0f;
    /**
     * The furthest the fog may run when a horizon is being drawn: the camera's far plane (12 km) with a
     * little room, because fog that ended exactly at the far plane would leave a hard line where clipped
     * geometry stopped.
     */
    public static final float MAX_FOG_M = 11800.0f;
    /**
     * The fog actually in force, which follows the draw radius.
     *
     * <p>When the renderer shortens how far it draws, the fog has to shorten with it, or the world ends
     * at a hard line with clear air beyond it. Keeping the two together is what makes the phone's
     * performance visible as weather rather than as a bug.
     */
    private float fogStart = FOG_START;
    private float fogEnd = FOG_END;

    private ShaderProgram worldProgram;
    private ShaderProgram skyProgram;
    private Mesh skyQuad;
    /** The player's own body: the only mesh in the game that moves every frame. */
    private Mesh actorMesh;
    /** Wheels, a propeller, rotors: the parts of that body that turn where they are. */
    private Mesh[] actorParts = new Mesh[0];
    /**
     * The far horizon: the country at 300 m a cell, out to just inside the camera's far plane.
     *
     * <p>Not streamed and not chunked -- one mesh, rebuilt only when the player has travelled far enough
     * that its centre is no longer where they are. It is what stops the world ending in fog.
     */
    private Mesh horizonLand;
    private Mesh horizonWater;
    private int horizonTriangles;
    /**
     * The skyline: the buildings beyond the detail radius, one coarse block each.
     *
     * <p>One mesh for walls and roofs together, because a block is a solid and the two are never drawn
     * apart; unlike the sea it is single-sided from every angle the player can be at, so it needs no
     * second pass and no cull-state switch.
     */
    private Mesh skyline;
    private int skylineTriangles;
    /**
     * The far layer: the roads and the vegetation of the band between the detail radius and the horizon.
     *
     * <p>One mesh for both, because they are the same kind of thing -- a painting of the country at three
     * to eight kilometres -- and because splitting them would cost a second draw call to say the same.
     *
     * <p>It is drawn with a polygon offset, and that is not a preference. The camera's near plane is 0.25 m
     * and its far plane is 12 km, so at eight kilometres one step of a 24-bit depth buffer is about fifteen
     * metres: a road six tenths of a metre above the ground it was draped on is a difference the depth
     * buffer cannot represent, and without the offset it wins some pixels and loses others as the camera
     * moves. The offset tells the rasteriser to pull the whole layer one resolution step towards the
     * viewer, which is exactly the guarantee needed and costs nothing.
     */
    private Mesh farLayer;
    private int farLayerTriangles;
    /** One matrix per moving part, reused every frame: a car has four wheels, so this never grows. */
    private Matrix4[] partScratch = new Matrix4[8];
    /**
     * The traffic's own body, and one matrix per car.
     *
     * <p>A second copy of the player's body machinery rather than a share of it, and that is deliberate:
     * the player's body is uploaded when the locomotion changes, traffic is uploaded once and drawn many
     * times a frame, and the two have different lifetimes. Fourteen cars at a draw call each is nothing
     * next to the hundred chunks behind them, and it is the difference between a country with roads on a
     * map and a country with traffic on its roads.
     */
    private Mesh trafficMesh;
    private Mesh[] trafficParts = new Mesh[0];
    private Matrix4[] trafficScratch = new Matrix4[8];
    private int trafficCars;

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
        // Blending is OFF for the world, and that is a performance decision with a proof attached: all
        // three of this renderer's shaders end in gl_FragColor = vec4(colour, 1.0), so nothing in the 3D
        // pass is ever transparent. Leaving blending enabled cost every terrain pixel of the country a
        // read-modify-write of the frame buffer -- the single most expensive thing a phone GPU does --
        // for a blend with one. Water is opaque in the shader too (its transparency is a colour mix).
        Gdx.gl.glDisable(GL20.GL_BLEND);

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
        worldProgram.setUniformf("u_fogStart", fogStart);
        worldProgram.setUniformf("u_fogEnd", fogEnd);
        worldProgram.setUniformf("u_time", time);
    }

    /**
     * Sets how far the fog runs, in metres: the caller passes its own draw radius so the two never
     * disagree. Clamped to something sane, because a fog that starts behind the camera is worse than no
     * fog at all.
     */
    public void setFogRange(float farMetres) {
        setFogRange(farMetres, false);
    }

    /**
     * Sets how far the fog runs, in metres, and whether a far horizon is being drawn under it.
     *
     * <p>Two different worlds, and the caller says which one it is in:
     * <ul>
     *   <li><b>No horizon.</b> The fog ends where the streamed chunks end, and it is dense: the edge of the
     *       loaded world is hidden inside haze. This is what the game did everywhere until the horizon
     *       existed, and it is what it still does while the horizon is being rebuilt.</li>
     *   <li><b>Horizon.</b> The fog runs to the horizon's own far edge -- inside the camera's far plane --
     *       so the mesh's rim is exactly the colour of the sky's haze and there is no line where it ends.
     *       It starts a little sooner than the old fog did, because the streaming boundary at five or six
     *       kilometres is still a change of resolution, and haze is what hides it.</li>
     * </ul>
     */
    public void setFogRange(float farMetres, boolean farHorizon) {
        float limit = farHorizon ? MAX_FOG_M : FOG_END;
        float far = Math.max(600f, Math.min(limit, farMetres));
        fogEnd = far;
        fogStart = Math.max(200f, far * (farHorizon ? 0.22f : 0.26f));
    }

    /** The fog distance currently in force, for the diagnostics panel. */
    public float fogEndMetres() {
        return fogEnd;
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
    /**
     * Uploads the far horizon: the coarsened country that fills the space between the streaming window and
     * the sky. Two meshes for the same reason a chunk has two -- the sea is drawn double sided, the land
     * is not -- and one translation, exactly as a chunk is drawn.
     */
    public void uploadHorizon(MeshData land, MeshData water) {
        if (!isReady()) {
            return;
        }
        horizonLand = upload(horizonLand, land);
        horizonWater = upload(horizonWater, water);
        horizonTriangles = (land == null ? 0 : land.triangleCount())
                + (water == null ? 0 : water.triangleCount());
    }

    /** The horizon's own triangle count, which the panel reports alongside the chunks'. */
    public int horizonTriangles() {
        return horizonTriangles;
    }

    /** Drops the horizon: after a teleport it is the country around the place the player left. */
    public void releaseHorizon() {
        if (horizonLand != null) {
            horizonLand.dispose();
            horizonLand = null;
        }
        if (horizonWater != null) {
            horizonWater.dispose();
            horizonWater = null;
        }
        horizonTriangles = 0;
    }

    /**
     * Uploads the skyline: the buildings beyond the detail radius, extruded as coarse blocks.
     *
     * <p>One mesh, like the horizon and for the same reason -- it is a few thousand blocks, not a stream --
     * and one upload, because a block has no second layer. An empty mesh means there is no city within
     * reach (the open desert, or a bundle without buildings) and that is not a failure.
     */
    public void uploadSkyline(MeshData blocks) {
        if (!isReady()) {
            return;
        }
        skyline = upload(skyline, blocks);
        skylineTriangles = blocks == null ? 0 : blocks.triangleCount();
    }

    /** The skyline's own triangle count, which the panel reports alongside the chunks'. */
    public int skylineTriangles() {
        return skylineTriangles;
    }

    /** Drops the skyline: after a teleport it is the city around the place the player left. */
    public void releaseSkyline() {
        if (skyline != null) {
            skyline.dispose();
            skyline = null;
        }
        skylineTriangles = 0;
    }

    /**
     * Uploads the far layer: the roads and the vegetation of the band past the detail radius.
     *
     * <p>One mesh, like the skyline and the horizon, and for the same reason: it is a painting of a band,
     * not a stream of chunks. An empty mesh means there is nothing within reach -- the middle of the
     * southern desert, where the only road is behind the player -- and that is not a failure.
     */
    public void uploadFarLayer(MeshData data) {
        if (!isReady()) {
            return;
        }
        farLayer = upload(farLayer, data);
        farLayerTriangles = data == null ? 0 : data.triangleCount();
    }

    /** The far layer's own triangle count, which the panel reports alongside the chunks'. */
    public int farLayerTriangles() {
        return farLayerTriangles;
    }

    /** Drops the far layer: after a teleport it is the roads around the place the player left. */
    public void releaseFarLayer() {
        if (farLayer != null) {
            farLayer.dispose();
            farLayer = null;
        }
        farLayerTriangles = 0;
    }

    /** Uploads the body every traffic car shares, with the parts of it that turn. Called once. */
    public void setTrafficMesh(MeshData body, java.util.List<BodyMesher.Part> parts) {
        trafficMesh = upload(trafficMesh, body);
        for (Mesh existing : trafficParts) {
            if (existing != null) {
                existing.dispose();
            }
        }
        if (trafficScratch.length < parts.size()) {
            trafficScratch = new Matrix4[parts.size()];
        }
        trafficParts = new Mesh[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            BodyMesher.Part part = parts.get(i);
            // Parts are uploaded from their own geometry, positioned at the origin: the pivot is applied by
            // the matrix at draw time, which is what lets one mesh be turned about its own axle.
            MeshData centred = part.mesh;
            trafficParts[i] = upload(null, centred);
            if (trafficScratch[i] == null) {
                trafficScratch[i] = new Matrix4();
            }
        }
    }

    /** How many traffic cars were drawn in the last frame, for the panel. */
    public int trafficCarsDrawn() {
        return trafficCars;
    }

    /**
     * Draws one traffic car and its wheels at a local position and heading.
     *
     * <p>Called with the world program bound and the fog set, inside the world's own pass and before the
     * player's body: traffic that were drawn after the body would be drawn after {@code endFrame}, which
     * turns depth testing and back-face culling off for the two-dimensional layer that follows.
     */
    public void drawTrafficCar(Matrix4 bodyTransform, java.util.List<BodyMesher.Part> parts, double eastM,
                               double heightM, double northM, double headingDegrees, double spinDegrees) {
        if (trafficMesh == null || !isReady()) {
            return;
        }
        worldProgram.setUniformMatrix("u_model", bodyTransform);
        trafficMesh.render(worldProgram, GL20.GL_TRIANGLES);
        drawCalls++;
        drawnTriangles += trafficMesh.getNumIndices() / 3;
        trafficCars++;
        for (int i = 0; i < trafficParts.length && i < parts.size(); i++) {
            Mesh part = trafficParts[i];
            if (part == null || trafficScratch[i] == null) {
                continue;
            }
            BodyMesher.transform(trafficScratch[i], parts.get(i), eastM, heightM, northM, headingDegrees,
                    spinDegrees);
            worldProgram.setUniformMatrix("u_model", trafficScratch[i]);
            part.render(worldProgram, GL20.GL_TRIANGLES);
            drawCalls++;
            drawnTriangles += part.getNumIndices() / 3;
        }
    }

    /** Resets the per-frame traffic count: the caller calls this at the start of every frame. */
    public void beginTraffic() {
        trafficCars = 0;
    }

    /**
     * Draws the far layer, translated from the centre it was built around. Called with the world program
     * bound and the fog set, after the horizon and the skyline so that its own depth wins the last
     * resolution step: see {@link #farLayer}.
     */
    public void drawFarLayer(double offsetEast, double offsetNorth) {
        if (!isReady() || farLayer == null) {
            return;
        }
        identity.setToTranslation((float) offsetEast, 0f, (float) offsetNorth);
        worldProgram.setUniformMatrix("u_model", identity);
        Gdx.gl.glEnable(GL20.GL_POLYGON_OFFSET_FILL);
        Gdx.gl.glPolygonOffset(-1f, -2f);
        farLayer.render(worldProgram, GL20.GL_TRIANGLES);
        Gdx.gl.glPolygonOffset(0f, 0f);
        Gdx.gl.glDisable(GL20.GL_POLYGON_OFFSET_FILL);
        drawCalls++;
        drawnTriangles += farLayer.getNumIndices() / 3;
    }

    /**
     * Draws the skyline, translated from the centre it was built around, with the world program bound and
     * the fog set. Drawn after the chunks so that the real geometry wins the depth test where the two
     * overlap: the band between {@code SkylineMesh.NEAR_M} and the detail radius is streamed properly, and
     * what survives the depth test there is the detailed building, not the block.
     */
    public void drawSkyline(double offsetEast, double offsetNorth) {
        if (!isReady() || skyline == null) {
            return;
        }
        identity.setToTranslation((float) offsetEast, 0f, (float) offsetNorth);
        worldProgram.setUniformMatrix("u_model", identity);
        skyline.render(worldProgram, GL20.GL_TRIANGLES);
        drawCalls++;
        drawnTriangles += skyline.getNumIndices() / 3;
    }

    /**
     * Draws the horizon at its own distance from the floating origin. Called with the world program bound
     * and the fog set, exactly like a chunk; the only difference is that this mesh is a hundred times
     * bigger and there is only one of it.
     */
    public void drawHorizon(double offsetEast, double offsetNorth) {
        if (!isReady() || (horizonLand == null && horizonWater == null)) {
            return;
        }
        identity.setToTranslation((float) offsetEast, 0f, (float) offsetNorth);
        worldProgram.setUniformMatrix("u_model", identity);
        if (horizonLand != null) {
            horizonLand.render(worldProgram, GL20.GL_TRIANGLES);
            drawCalls++;
            drawnTriangles += horizonLand.getNumIndices() / 3;
        }
        if (horizonWater != null) {
            Gdx.gl.glDisable(GL20.GL_CULL_FACE);
            horizonWater.render(worldProgram, GL20.GL_TRIANGLES);
            Gdx.gl.glEnable(GL20.GL_CULL_FACE);
            drawCalls++;
            drawnTriangles += horizonWater.getNumIndices() / 3;
        }
    }

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
        Gdx.gl.glDisable(GL20.GL_BLEND);              // see begin(): no 3D shader here writes alpha < 1
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
        releaseHorizon();
        releaseSkyline();
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
