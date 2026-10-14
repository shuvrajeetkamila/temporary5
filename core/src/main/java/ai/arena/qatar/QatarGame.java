package ai.arena.qatar;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g3d.utils.CameraInputController;
import com.badlogic.gdx.math.Vector3;

import ai.arena.qatar.boot.BootSequence;
import ai.arena.qatar.boot.ScreenLog;
import ai.arena.qatar.boot.StartupReport;
import ai.arena.qatar.game.GameScreen;
import ai.arena.qatar.hud.MapRaster;
import ai.arena.qatar.math.Vec3;
import ai.arena.qatar.physics.Collider;
import ai.arena.qatar.physics.PhysicsWorld;
import ai.arena.qatar.physics.RigidBody;
import ai.arena.qatar.render.SkyAndGroundRenderer;
import ai.arena.qatar.text.ArabicReshaper;
import ai.arena.qatar.world.GameManifest;
import ai.arena.qatar.world.WorldData;

/**
 * QATAR: THE PENINSULA -- application entry point.
 *
 * <p>Responsibilities: bring up GLES, load the compiled country, hand it to {@link GameScreen}, and
 * survive pause/resume/recreate without losing state.
 *
 * <p><b>Nothing here is allowed to fail silently.</b> The first build installed on a phone showed a blue
 * screen and nothing else: the country had not loaded, the fallback demo's shaders had not compiled
 * either, and both reasons were in logcat, where the person holding the phone could not see them. The
 * loading now runs as stages across frames -- so the screen keeps showing which stage is running -- and
 * every failure is printed on the screen by {@link ScreenLog} and left there. A blue screen with no text
 * on it is now impossible: if the game cannot say what it is doing, it says that.
 */
public final class QatarGame extends ApplicationAdapter {

    /** The build stamp shown on the diagnostics panel. The release asset keeps its name. */
    public static final String VERSION = "0.1.0-m0+diag";

    /**
     * Map raster resolution. 2048 covers the country at 126 m per pixel and cost seconds of blocking
     * work on a phone at startup; 1024 is 253 m per pixel, which is finer than the tap-to-travel snap
     * radius needs, and the big map's 16x zoom is still usable.
     */
    private static final int MAP_RESOLUTION = 1024;

    private static final String CRASH_FILE = "last-crash.txt";

    /** The stages of a launch, in order. One runs per frame while the panel is on the screen. */
    private enum LoadStage {
        GL_INFO("Reading the GL context"),
        CRASH_FILE("Checking for a crash from a previous run"),
        MANIFEST("Opening the compiled world manifest"),
        BUNDLES("Inflating the country (about 50 MB of geometry)"),
        MAP("Painting the map raster"),
        GAME("Starting the game screen (shaders, chunks, HUD)"),
        RUNNING("Running");

        final String label;

        LoadStage(String label) {
            this.label = label;
        }
    }

    private final ScreenLog log = ScreenLog.GLOBAL;

    private BootSequence boot;
    private PhysicsWorld physics;
    private RigidBody testCrate;
    private PerspectiveCamera camera;
    private CameraInputController cameraController;
    private SkyAndGroundRenderer sceneRenderer;
    private final Vector3 cameraFocus = new Vector3();

    /** The playable world. Null only if the compiled data could not be loaded at all. */
    private GameScreen gameScreen;
    private WorldData world;
    private GameManifest manifest;
    private MapRaster raster;
    private String worldError = "";

    /** How long the start-up trace stays on the screen once the game is running. */
    private static final float TRACE_SECONDS = 8f;

    private float traceSeconds;
    private LoadStage stage = LoadStage.GL_INFO;
    private boolean stagePending = true;
    private boolean failed;
    private float elapsedSeconds;
    private boolean disposed;
    private StartupReport startup;

    @Override
    public void create() {
        installCrashRecorder();

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

        // The console comes up before anything that can fail, and says so on the screen from frame one.
        log.init();
        if (!log.isReady()) {
            Gdx.app.error("QatarGame", "no font could be loaded: the diagnostics panel will draw bars");
        } else if (log.isFontFallback()) {
            log.warn("HUD font unavailable: diagnostics are in the built-in ASCII font");
        }
        log.log("Qatar: The Peninsula " + VERSION + " on " + Gdx.graphics.getWidth() + "x"
                + Gdx.graphics.getHeight());

        // The demo scene is built now -- it is cheap -- but it is only drawn if the country cannot be.
        sceneRenderer = new SkyAndGroundRenderer();

        startup = new StartupReport(VERSION, "pending");
        Gdx.input.setInputProcessor(cameraController);
    }

    @Override
    public void render() {
        float frameDt = Math.min(Gdx.graphics.getDeltaTime(), 0.1f);
        elapsedSeconds += frameDt;
        boot.advance(frameDt);

        if (stagePending) {
            stagePending = false;
            runStage();                          // may block for a second or two; the panel is already up
        }

        if (stage != LoadStage.RUNNING || gameScreen == null) {
            // Loading, or the world failed and the demo is running instead: the panel is on the screen
            // either way, and there is no game screen to render.
            drawLoadingFrame(frameDt);
            return;
        }

        gameScreen.render(frameDt);
        // The trace stays up for a few seconds after a good start -- long enough to photograph and read,
        // and gone afterwards. A failure leaves it up for good.
        traceSeconds = Math.max(0f, traceSeconds - frameDt);
        if (failed || log.hasError() || traceSeconds > 0f) {
            log.render();
        }
    }

    /** Runs the current stage's work, then moves to the next. A failure stops the walk and stays visible. */
    private void runStage() {
        try {
            switch (stage) {
                case GL_INFO:
                    readGlContext();
                    next(LoadStage.CRASH_FILE);
                    break;
                case CRASH_FILE:
                    reportPreviousCrash();
                    next(LoadStage.MANIFEST);
                    break;
                case MANIFEST:
                    manifest = GameManifest.load("world");
                    log.log("Manifest: " + manifest.describe());
                    next(LoadStage.BUNDLES);
                    break;
                case BUNDLES:
                    world = WorldData.load("world");
                    log.log("Country loaded in " + world.loadMillis() + " ms: " + world.describe());
                    next(LoadStage.MAP);
                    break;
                case MAP:
                    raster = MapRaster.build(world, MAP_RESOLUTION);
                    log.log("Map raster " + raster.width + "x" + raster.height);
                    next(LoadStage.GAME);
                    break;
                case GAME:
                    gameScreen = new GameScreen(world, raster);
                    gameScreen.show();
                    startup.put("worldPlayable", "yes");
                    startup.put("worldLoadMillis", world.loadMillis());
                    startup.put("mapRaster", raster.width + "x" + raster.height);
                    boot.complete(startup);
                    log.log("Playing: " + world.describe());
                    traceSeconds = TRACE_SECONDS;
                    next(LoadStage.RUNNING);
                    break;
                default:
                    next(LoadStage.RUNNING);
                    break;
            }
        } catch (Throwable t) {
            failed = true;
            worldError = t.toString();
            log.error("Load stopped in stage '" + stage.label + "'", t);
            log.error("The toolchain demo is running behind this panel. GL: " + glSummary());
            if (sceneRenderer != null && sceneRenderer.shaderError() != null
                    && !sceneRenderer.shaderError().isEmpty()) {
                log.error("Demo shaders also failed: " + sceneRenderer.shaderError());
            }
            startup.put("worldPlayable", "no");
            startup.warn("world bundle not playable: " + worldError);
            boot.fail(BootSequence.Stage.GAMEPLAY, BootSequence.Severity.FATAL, worldError, t);
            // Stop walking the stages: from here the demo runs, with the reason on the screen.
            stage = LoadStage.RUNNING;
            gameScreen = null;
        }
    }

    private void next(LoadStage following) {
        stage = following;
        // RUNNING is not a stage with work in it: arriving there means the walk is over.
        stagePending = following != LoadStage.RUNNING;
    }

    private void drawLoadingFrame(float frameDt) {
        ScreenLog.clearTo(0.10f, 0.13f, 0.18f);
        log.render();

        if (gameScreen == null) {
            // Something to look at while the country arrives: the demo scene, still on the toolchain.
            int steps = boot.clock().beginFrame(frameDt);
            for (int i = 0; i < steps; i++) {
                physics.step(ai.arena.qatar.physics.FixedStepClock.FIXED_DT);
            }
            cameraController.update();
            cameraFocus.set((float) testCrate.position.x, (float) testCrate.position.y,
                    (float) testCrate.position.z);
            sceneRenderer.render(camera, cameraFocus, elapsedSeconds,
                    (float) boot.clock().alpha(), testCrate);
        }
    }

    private void readGlContext() {
        String vendor = glString(GL20.GL_VENDOR);
        String renderer = glString(GL20.GL_RENDERER);
        String version = glString(GL20.GL_VERSION);
        String glsl = glString(GL20.GL_SHADING_LANGUAGE_VERSION);
        boolean es3 = Gdx.gl30 != null;
        log.log("GL vendor=" + vendor);
        log.log("GL renderer=" + renderer);
        log.log("GL version=" + version + " | GLSL " + glsl);
        if (es3) {
            log.log("OpenGL ES 3.0 context: 300 es shaders are compiled by this driver");
        } else {
            log.warn("No ES 3.0 context: the country's shaders need '300 es' and will not compile");
        }
        if (startup != null) {
            startup = new StartupReport(VERSION, renderer + " / " + version);
        }
    }

    private static String glString(int name) {
        try {
            String value = Gdx.gl == null ? null : Gdx.gl.glGetString(name);
            return value == null ? "(null)" : value;
        } catch (Throwable t) {
            return "(unavailable: " + t + ")";
        }
    }

    private String glSummary() {
        return glString(GL20.GL_RENDERER) + " / " + glString(GL20.GL_VERSION);
    }

    /**
     * A crash on a phone leaves nothing behind that the person holding it can read. The handler writes
     * the throwable to a file in the app's own storage, and the next launch prints it -- so "it closed
     * itself" comes back as an exception and a line number.
     */
    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringBuilder text = new StringBuilder();
                text.append("thread ").append(thread.getName()).append('\n');
                text.append(throwable).append('\n');
                Throwable cause = throwable;
                while (cause != null) {
                    for (StackTraceElement frame : cause.getStackTrace()) {
                        text.append("    at ").append(frame).append('\n');
                    }
                    cause = cause.getCause() == cause ? null : cause.getCause();
                }
                Gdx.files.local(CRASH_FILE).writeString(text.toString(), false, "UTF-8");
            } catch (Throwable ignored) {
                // Nothing can be done from here, and an exception in a crash handler hides the crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private void reportPreviousCrash() {
        try {
            com.badlogic.gdx.files.FileHandle file = Gdx.files.local(CRASH_FILE);
            if (!file.exists()) {
                log.log("No crash recorded by a previous run");
                return;
            }
            String text = file.readString("UTF-8");
            if (text.length() > 1200) {
                text = text.substring(0, 1200);
            }
            log.error("PREVIOUS RUN CRASHED:");
            for (String line : text.split("\n")) {
                log.add(ScreenLog.Level.ERROR, line);
            }
            file.delete();
        } catch (Throwable t) {
            log.warn("could not read the crash file: " + t);
        }
    }

    @Override
    public void resize(int width, int height) {
        log.resize(width, height);
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
        log.dispose();
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

    /** True when a stage failed: the panel stays on the screen when it did. */
    public boolean failed() {
        return failed;
    }

    /** Names the stage a launch is in, for the startup report and the tests. */
    public String stageName() {
        return stage.label;
    }

    /** The Arabic shaping self-check used by the boot report; kept so the log has one line of Arabic. */
    public String arabicSelfCheck() {
        return ArabicReshaper.shapeForDisplay("\u0642\u0637\u0631");
    }

    /** The colours the loading frames use; exposed so a screenshot can be matched to a stage. */
    public static float[] loadingBackdrop() {
        return new float[]{0.10f, 0.13f, 0.18f};
    }
}
