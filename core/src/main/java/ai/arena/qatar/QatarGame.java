package ai.arena.qatar;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.utils.CameraInputController;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import ai.arena.qatar.boot.BootSequence;
import ai.arena.qatar.game.GameScreen;
import ai.arena.qatar.world.WorldData;
import ai.arena.qatar.boot.StartupReport;
import ai.arena.qatar.physics.Collider;
import ai.arena.qatar.physics.PhysicsWorld;
import ai.arena.qatar.physics.RigidBody;
import ai.arena.qatar.math.Vec3;
import ai.arena.qatar.render.GlVersion;
import ai.arena.qatar.render.SkyAndGroundRenderer;
import ai.arena.qatar.text.ArabicReshaper;

/**
 * QATAR: THE PENINSULA -- application entry point.
 *
 * <p>Responsibilities: bring up GLES3, load the compiled country, hand it to {@link GameScreen}, and
 * survive pause/resume/recreate without losing state.
 *
 * <p>The loading state machine reports real stages (manifest -> terrain -> physics -> shaders ->
 * gameplay); a fatal manifest error is shown, an optional decoration error degrades visibly.
 */
public final class QatarGame extends ApplicationAdapter {

    private BootSequence boot;
    private PhysicsWorld physics;
    private RigidBody testCrate;
    private PerspectiveCamera camera;
    private CameraInputController cameraController;
    private SkyAndGroundRenderer sceneRenderer;
    private final Vector3 cameraFocus = new Vector3();
    /** The playable world. Null only if the compiled data could not be loaded at all. */
    private GameScreen gameScreen;
    private String worldError = "";

    private float elapsedSeconds;
    private boolean disposed;
    /** Debug overlay toggle (development only). */
    private boolean debugOverlay = true;
    private StartupReport startup;

    @Override
    public void create() {
        // GLES3 is required: the renderer uses instancing, UBO-free explicit attribute bindings and
        // GLSL ES 3.0 shaders with MRT-free forward passes tuned for mobile GPUs.
        GlVersion.logContextInfo();

        boot = new BootSequence();
        boot.begin();

        startup = new StartupReport("0.1.0-m0", GlVersion.describe());
        loadPlayableWorld();

        physics = new PhysicsWorld();
        physics.setTerrain(ai.arena.qatar.physics.TerrainSampler.FLAT);

        // Toolchain proof: a rigid crate dropped onto a static surface through the real solver.
        Collider crateCollider = new Collider(new Vec3[]{new Vec3(0, 0, 0)}, 0.35);
        testCrate = new RigidBody(12.0, Vec3.of(0.6, 0.6, 0.6), crateCollider);
        testCrate.debugName = "test-crate";
        testCrate.position.set(0, 6.0, 0);
        physics.addBody(testCrate);

        camera = new PerspectiveCamera(67f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.position.set(-9f, 5.5f, 9f);
        camera.lookAt(0f, 1.2f, 0f);
        camera.near = 0.15f;
        camera.far = 6000f;
        camera.update();

        cameraController = new CameraInputController(camera);
        Gdx.input.setInputProcessor(cameraController);

        sceneRenderer = new SkyAndGroundRenderer();

        startup.put("worldPlayable", gameScreen != null ? "yes" : "no");
        if (!worldError.isEmpty()) {
            startup.warn("world bundle not playable: " + worldError);
        }
        boot.complete(startup);
        Gdx.app.log("QatarGame", "Boot complete. Arabic shaping self-check: "
                + ArabicReshaper.shapeForDisplay("\u0642\u0637\u0631"));
    }

    @Override
    public void render() {
        float frameDt = Math.min(Gdx.graphics.getDeltaTime(), 0.1f);
        elapsedSeconds += frameDt;

        boot.advance(frameDt);

        if (gameScreen != null) {
            gameScreen.render(frameDt);
            return;
        }

        int steps = boot.clock().beginFrame(frameDt);
        for (int i = 0; i < steps; i++) {
            physics.step(ai.arena.qatar.physics.FixedStepClock.FIXED_DT);
        }

        cameraController.update();

        Gdx.gl.glClearColor(0.55f, 0.68f, 0.80f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        cameraFocus.set((float) testCrate.position.x, (float) testCrate.position.y,
                (float) testCrate.position.z);
        sceneRenderer.render(camera, cameraFocus, elapsedSeconds,
                (float) boot.clock().alpha(), testCrate);
    }

    /**
     * Loads the compiled country and starts the game on it.
     *
     * <p>A failure here is reported, not swallowed: the fallback demo runs instead, and the reason is
     * printed and recorded in the startup report, because "the world did not load" is the single most
     * important thing a build can tell its author.
     */
    private void loadPlayableWorld() {
        try {
            WorldData world = WorldData.load("world");
            gameScreen = new GameScreen(world);
            gameScreen.show();
            Gdx.app.log("QatarGame", "Playing the compiled country: " + world.describe());
        } catch (RuntimeException e) {
            gameScreen = null;
            worldError = e.toString();
            Gdx.app.error("QatarGame", "Compiled world not playable; running the toolchain demo instead", e);
        }
    }

    @Override
    public void resize(int width, int height) {
        if (gameScreen != null) {
            gameScreen.resize(width, height);
            return;
        }
        camera.viewportWidth = width;
        camera.viewportHeight = height;
        camera.update();
    }

    @Override
    public void pause() {
        // Physics timing is reset on resume: a backgrounded process must not replay catch-up steps.
        boot.clock().reset();
        if (gameScreen != null) {
            gameScreen.pause();
        }
    }

    @Override
    public void resume() {
        boot.clock().reset();
        if (gameScreen != null) {
            gameScreen.resume();
        }
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (gameScreen != null) {
            gameScreen.dispose();
        }
        if (sceneRenderer != null) {
            sceneRenderer.dispose();
        }
    }

    public BootSequence boot() {
        return boot;
    }

    public PhysicsWorld physics() {
        return physics;
    }

    public GameScreen gameScreen() {
        return gameScreen;
    }

    /** Non-empty when the compiled world could not be loaded; shown in the startup report. */
    public String worldError() {
        return worldError;
    }
}
