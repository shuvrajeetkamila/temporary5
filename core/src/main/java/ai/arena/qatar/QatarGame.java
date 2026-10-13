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
 * <p>Milestone 0 responsibilities: bring up GLES3, compile the material shaders, initialise the
 * fixed-step physics world, load a packaged binary world bundle with version/checksum validation,
 * accept touch input, and survive pause/resume/recreate without losing state.
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

    private float elapsedSeconds;
    private boolean disposed;
    /** Debug overlay toggle (development only). */
    private boolean debugOverlay = true;

    @Override
    public void create() {
        // GLES3 is required: the renderer uses instancing, UBO-free explicit attribute bindings and
        // GLSL ES 3.0 shaders with MRT-free forward passes tuned for mobile GPUs.
        GlVersion.logContextInfo();

        boot = new BootSequence();
        boot.begin();

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

        boot.complete(new StartupReport("0.1.0-m0", GlVersion.describe()));
        Gdx.app.log("QatarGame", "Boot complete. Arabic shaping self-check: "
                + ArabicReshaper.shapeForDisplay("\u0642\u0637\u0631"));
    }

    @Override
    public void render() {
        float frameDt = Math.min(Gdx.graphics.getDeltaTime(), 0.1f);
        elapsedSeconds += frameDt;

        boot.advance(frameDt);
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

    @Override
    public void resize(int width, int height) {
        camera.viewportWidth = width;
        camera.viewportHeight = height;
        camera.update();
    }

    @Override
    public void pause() {
        // Physics timing is reset on resume: a backgrounded process must not replay catch-up steps.
        boot.clock().reset();
    }

    @Override
    public void resume() {
        boot.clock().reset();
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
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
}
