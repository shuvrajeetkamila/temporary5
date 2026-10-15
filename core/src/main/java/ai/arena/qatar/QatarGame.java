package ai.arena.qatar;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g3d.utils.CameraInputController;
import com.badlogic.gdx.math.Vector3;

import ai.arena.qatar.boot.Alerts;
import ai.arena.qatar.boot.BootSequence;
import ai.arena.qatar.boot.CrashJournal;
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

    /**
     * The build stamp shown on the diagnostics panel. The release asset keeps its name, so this is the
     * only way to tell two builds apart on a phone: the first diagnostic build carried three failures
     * that the panel reported, and the person installing the next one needs to see at a glance that it
     * is the next one and not the same file installed twice.
     */
    public static final String VERSION = "0.1.0-m0+diag8";

    /**
     * Map raster resolution. 2048 covers the country at 126 m per pixel and cost seconds of blocking
     * work on a phone at startup; 1024 is 253 m per pixel, which is finer than the tap-to-travel snap
     * radius needs, and the big map's 16x zoom is still usable.
     */
    private static final int MAP_RESOLUTION = 1024;

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

    /**
     * Roughly what the compiled country costs in heap once it is parsed: 16 MB of bundles inflate to
     * about 60 MB of arrays, the map raster is 4 MB, and the resident chunks want tens of megabytes on
     * top. Measured on the desktop build with the real bundles; used to warn a person before a phone's
     * smaller heap turns into a failure that looks like a bug.
     */
    private static final int COUNTRY_HEAP_MB = 150;

    private float traceSeconds;
    /** Frames that threw. One is a bug; a thousand is a game that must stop trying. */
    private int frameFailures;
    /**
     * How long an error keeps the diagnostics panel on the screen, in seconds.
     *
     * <p>Long enough to read a sentence and take a photograph of it; short enough that the game is not
     * permanently covered by a report about something that has already been written to the journal.
     */
    private static final float ERROR_PANEL_SECONDS = 30f;

    /**
     * How many consecutive failed frames before the game screen is given up on.
     *
     * <p>About two seconds at sixty frames a second. One bad frame is a hiccup; two seconds of them is a
     * subsystem that is not coming back. The third install disposed the screen on the first exception, so
     * any single failure anywhere in a frame took the controls, the map and the world away with it.
     */
    private static final int PERSISTENT_FAILURE_FRAMES = 120;

    /** Failures inside the fallback scene, reported a few times and then left alone. */
    private int demoFailures;
    private LoadStage stage = LoadStage.GL_INFO;
    private boolean stagePending = true;
    private boolean failed;
    /** True when the game screen has been given up on and still has to be torn down inside a frame. */
    private boolean rollback;
    private float elapsedSeconds;
    private boolean disposed;
    private StartupReport startup;

    /**
     * Whether there is a GL context to call into right now.
     *
     * <p>{@code Gdx.gl} is null before a context exists and after one is destroyed, and libGDX's
     * {@code SpriteBatch.end()} -- one of the first things any teardown does -- calls straight through to
     * {@code glDepthMask}. That call with no context is
     * {@code NullPointerException: com.badlogic.gdx.Gdx.gl}, and the app dies of its own cleanup, which is
     * exactly what the eighth install did when the minimap was opened.
     *
     * <p>There is no {@code isGLThread()} to ask in libGDX 1.12.1 (checked against its own source: the
     * method does not exist in {@code Graphics} at any version this project builds against). The
     * thread question is answered by <em>where</em> this is called instead: the queued teardown runs
     * inside {@link #renderFrame()}, which is the GL thread by definition, and the platform's own
     * {@link #dispose()} is called with the context current.
     */
    public static boolean glAvailable() {
        return Gdx.gl != null;
    }

    /**
     * Tears down whatever game screen exists, on the GL thread, and says so once if it cannot be done
     * safely.
     *
     * <p>The one place that disposes {@link ai.arena.qatar.game.GameScreen} except {@link #dispose()}
     * itself, which is the process ending and has nothing left to lose.
     */
    private void disposeScreen(boolean glReady) {
        if (gameScreen == null) {
            return;
        }
        ai.arena.qatar.game.GameScreen doomed = gameScreen;
        gameScreen = null;
        try {
            doomed.dispose(glReady);
        } catch (Throwable t) {
            // A teardown that fails must not take the process with it: the screen is already out of the
            // frame loop, so the worst case is a leaked mesh, and that is cheap next to this.
            log.warn("could not release the game screen: " + t);
            CrashJournal.record("dispose the game screen", t);
        }
    }

    private void createInternal() {
        // The crash handler and the breadcrumb trail are installed by the platform entry point before
        // libGDX exists (see AndroidLauncher): this method is one of the things that can fail, and a
        // recorder that is created here would die with the failure it was meant to record.
        CrashJournal.note("create");

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

        // How much heap the platform has granted this process, said out loud before the country is
        // asked to fit in it. The third install failed at a launch stage and there was no way to tell
        // from the screen whether that was a bug or a phone that was never going to hold 60 MB of
        // compiled geometry -- the number below is the answer, and it is printed before anything heavy
        // happens rather than after.
        long heapLimitMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        log.log("Heap for this app: " + heapLimitMb + " MB (needs about " + COUNTRY_HEAP_MB + " MB)");
        if (heapLimitMb < COUNTRY_HEAP_MB * 2L) {
            log.warn("This phone grants the app only " + heapLimitMb + " MB. If the country fails to "
                    + "load with an OutOfMemoryError below, that is why, and it is the phone's limit "
                    + "rather than a bug in the game.");
        }

        // The demo scene is built now -- it is cheap -- but it is only drawn if the country cannot be.
        sceneRenderer = new SkyAndGroundRenderer();

        startup = new StartupReport(VERSION, "pending");
        Gdx.input.setInputProcessor(cameraController);
    }

    @Override
    public void create() {
        try {
            createInternal();
        } catch (Throwable t) {
            // The GL thread is the process's life-support: an exception escaping create() kills it and
            // Android kills the app with it, before a single frame can be drawn. So this is the last
            // place where a failure can still be written down, and it is written down twice -- in the
            // journal for the next launch's dialog, and in the panel in case a frame still gets drawn.
            failed = true;
            CrashJournal.record("create", t);
            echoToPanel("create failed", t);
        }
    }

    @Override
    public void render() {
        // Every frame on the GL thread runs through this guard. Round three of this game died with a
        // silent close: an exception in the streaming code escaped render(), the GL thread died, and
        // the process went with it -- no panel, no message, nothing to photograph. A game that cannot
        // draw the world must still be able to draw the reason.
        try {
            renderFrame();
        } catch (Throwable t) {
            frameFailures++;
            // The screen names the part of the frame that threw -- the world or the controls -- so the
            // message says what broke rather than "a frame failed". The same failure is written once and
            // then counted, because a frame is drawn sixty times a second and a message per frame is a
            // wall of text in half a second, which is exactly what the third install showed.
            String where = gameScreen == null
                    ? ("while loading: " + t)
                    : gameScreen.describeFrameFailure(t);
            CrashJournal.record("frame " + frameFailures + " " + where, t);
            echoToPanel(where, frameFailures == 1 ? t : null);
            if (frameFailures == 1) {
                // Once, and with the frames that matter: a frame that throws usually throws every frame,
                // and three dialogs stacked on each other is worse than none.
                Alerts.report("Qatar stopped drawing", Alerts.describe(where, t));
            }
            if (gameScreen != null && frameFailures > PERSISTENT_FAILURE_FRAMES) {
                // Two seconds of failing every frame is not a glitch, it is a broken subsystem: give up
                // on the game screen and fall back to the demo behind the panel, which is what the panel
                // was built to sit on. One bad frame no longer ends the game -- that took the controls
                // away from the player the moment anything anywhere in a frame went wrong.
                echoToPanel("giving up on the game screen after " + frameFailures
                        + " failed frames; the panel stays up", null);
                // The screen is dropped here, but *disposed* at the start of the next frame, inside
                // renderFrame and inside its own try. Disposing touches GL (freeing meshes, ending the
                // HUD's batch), and this catch block is the one place where the context is suspect:
                // round eight closed the app here, from a HUD that could not finish its own teardown.
                gameScreen = null;
                rollback = true;
            }
        }
    }

    private void renderFrame() {
        // Work left over from the last frame, done where the context is known to be current: the frame
        // guard in render() calls this method and everything else it does is already guarded.
        if (rollback) {
            rollback = false;
            disposeScreen(glAvailable());
        }
        float frameDt = Math.min(Gdx.graphics.getDeltaTime(), 0.1f);
        elapsedSeconds += frameDt;
        if (boot != null) {
            boot.advance(frameDt);
        }

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

        // The panel is a report, not furniture. The trace stays up for a few seconds after a good start
        // -- long enough to photograph and read -- and an error keeps it up for half a minute, because
        // that is when it is news. After that it gets out of the way: it used to stay for the rest of the
        // session once anything at all had gone wrong, which meant one failed chunk build covered the top
        // of the screen until the app was closed. Nothing is lost by hiding it: the failure is in the
        // journal, and the next launch's dialog shows it in full.
        traceSeconds = Math.max(0f, traceSeconds - frameDt);
        boolean news = failed || traceSeconds > 0f || frameFailures > 0
                || log.secondsSinceError() < ERROR_PANEL_SECONDS;
        if (news) {
            log.render();
        }
    }

    /** Puts a failure on the panel from a place where the panel itself might be what is broken. */
    private void echoToPanel(String message, Throwable t) {
        try {
            if (t == null) {
                log.error(message);          // already spelled out: the frames are in the record
            } else {
                log.error(message, t);
            }
        } catch (Throwable ignored) {
            // The panel is the last resort, not a guarantee.
        }
    }

    /** Runs the current stage's work, then moves to the next. A failure stops the walk and stays visible. */
    private void runStage() {
        // A breadcrumb per stage, with the heap in use: if the process is killed outright -- which is
        // what a phone does to an app that grows too large -- this trail is the only evidence there is.
        CrashJournal.note(stage.label);
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
            CrashJournal.record("stage " + stage.label, t);
            log.error("Load stopped in stage '" + stage.label + "'", t);
            log.error("memory: " + CrashJournal.heapSummary());
            // Out of memory is not a bug in the same way the rest of these are, and it is the one
            // failure where the message is worth more than the stack: say what the limit was.
            String reason = t instanceof OutOfMemoryError
                    ? "This phone ran out of heap while loading the country.\n\n"
                            + "The app's heap limit is "
                            + (Runtime.getRuntime().maxMemory() / (1024L * 1024L)) + " MB and the compiled "
                            + "country, the map and the first chunks need roughly " + COUNTRY_HEAP_MB
                            + " MB or more.\n\n" + t
                    : Alerts.describe("The launch stopped while: " + stage.label, t);
            Alerts.report("Qatar could not load the country", reason);
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
        // One line per stage transition: the country is about 60 MB of geometry in, and the raster,
        // the chunk cache and the GPU copies all come after it. A phone that runs out of memory kills
        // the process without an exception, so the number that was reached before the kill is the
        // diagnosis. It is on the panel, where a photograph can carry it.
        log.log("stage " + following.label + " | " + CrashJournal.heapSummary());
    }

    private void drawLoadingFrame(float frameDt) {
        try {
            ScreenLog.clearTo(0.10f, 0.13f, 0.18f);
        } catch (Throwable ignored) {
            // No GL to clear: the panel below is still worth attempting.
        }
        log.render();

        if (gameScreen != null) {
            return;
        }
        // Something to look at while the country arrives: the demo scene, still on the toolchain.
        // Guarded, because the demo is not allowed to be the reason a failure is invisible: the panel
        // has already been drawn by the time this runs, and it stays drawn whatever happens next.
        try {
            int steps = boot.clock().beginFrame(frameDt);
            for (int i = 0; i < steps; i++) {
                physics.step(ai.arena.qatar.physics.FixedStepClock.FIXED_DT);
            }
            cameraController.update();
            cameraFocus.set((float) testCrate.position.x, (float) testCrate.position.y,
                    (float) testCrate.position.z);
            sceneRenderer.render(camera, cameraFocus, elapsedSeconds,
                    (float) boot.clock().alpha(), testCrate);
        } catch (Throwable t) {
            demoFailures++;
            if (demoFailures <= 3) {
                log.error("The fallback scene failed (" + demoFailures + ")", t);
                CrashJournal.record("demo scene", t);
            }
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
     * Prints what the last launch left behind: the crash record from {@link CrashJournal}, which is
     * written by the platform entry point's handler even when this class never got a chance to run.
     *
     * <p>The record is deliberately <b>not</b> deleted here. The previous version of this method printed
     * the trace and then removed the file, so a crash that repeats showed its reason for as long as it
     * took to die again -- a fraction of a second, and impossible to photograph. Now the Android
     * launcher shows it in a dialog before the game starts and only a person clears it.
     */
    private void reportPreviousCrash() {
        try {
            String text = CrashJournal.readCrash();
            if (text == null || text.isEmpty()) {
                log.log("No crash recorded by a previous run");
            } else {
                log.error("PREVIOUS RUN REPORTED A PROBLEM (kept for the next launch):");
                for (String line : text.split("\n")) {
                    log.add(ScreenLog.Level.ERROR, line);
                }
            }
            String trace = CrashJournal.readTrace();
            if (trace != null && !trace.isEmpty()) {
                String[] lines = trace.split("\n");
                int from = Math.max(0, lines.length - 6);
                log.warn("where the last launch had got to:");
                for (int i = from; i < lines.length; i++) {
                    log.add(ScreenLog.Level.WARN, lines[i]);
                }
            }
        } catch (Throwable t) {
            log.warn("could not read the crash record: " + t);
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
        disposeScreen(glAvailable());
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
