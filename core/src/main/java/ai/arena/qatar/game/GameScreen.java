package ai.arena.qatar.game;

import ai.arena.qatar.game.PlayerController.Loco;
import ai.arena.qatar.hud.Credits;
import ai.arena.qatar.hud.HudLayout;
import ai.arena.qatar.hud.HudLayout.Button;
import ai.arena.qatar.hud.HudLayout.Id;
import ai.arena.qatar.hud.HudLayout.Rect;
import ai.arena.qatar.hud.HudRenderer;
import ai.arena.qatar.hud.HudText;
import ai.arena.qatar.hud.MapRaster;
import ai.arena.qatar.hud.MapView;
import ai.arena.qatar.hud.StatusText;
import ai.arena.qatar.math.Vec3;
import ai.arena.qatar.physics.FixedStepClock;
import ai.arena.qatar.world.GridTerrainSampler;
import ai.arena.qatar.world.WorldData;
import ai.arena.qatar.world.WorldOrigin;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.utils.Disposable;

import java.util.Collections;
import java.util.List;

/**
 * The playable screen: the world, the camera, the controls and the two maps.
 *
 * <p>This is the only class that knows about touches, buttons and frame timing at the same time, and it
 * is deliberately thin -- it moves numbers between systems that each do one job:
 * {@link PlayerController} decides how the player moves, {@link GameWorldRenderer} decides what is
 * drawn, {@link TeleportService} decides where a tap puts the player, and everything hard lives in a
 * class that can be tested with no screen at all.
 */
public final class GameScreen extends InputAdapter implements Disposable {

    /** How far a finger may drift before a tap stops being a tap. */
    private static final float TAP_SLOP_PX = 22f;
    /** Zoom factor per press of the map's +/- buttons. */
    private static final float ZOOM_STEP = 1.8f;
    /** Metres per press of the altitude buttons. */
    private static final double ALTITUDE_STEP_M = 25.0;
    /** Look sensitivity, degrees per pixel at the reference 720 px screen height. */
    private static final float LOOK_DEGREES_PER_PX = 0.16f;

    private final WorldData world;
    private WorldOrigin origin;

    private GridTerrainSampler sampler;
    private CameraRig rig;
    private PlayerController controller;
    private GameWorldRenderer worldRenderer;
    /** The country as the vehicle bodies see it: heights, materials, solids. Set at startup. */
    private WorldSurface worldSurface;
    private HudRenderer hud;
    private HudLayout layout;
    private MapRaster raster;
    private MapView mapView;
    private TeleportService teleport;

    private PerspectiveCamera camera;
    private final FixedStepClock clock = new FixedStepClock();

    // Touch state. One pointer looks, one moves, one presses a button: a phone held in two hands can
    // do all three at once, which is the difference between a demo and something playable.
    private int lookPointer = -1;
    private int movePointer = -1;
    private int buttonPointer = -1;
    private float moveAnchorX;
    private float moveAnchorY;
    private float moveCurrentX;
    private float moveCurrentY;
    private float lookLastX;
    private float lookLastY;
    /** Where the current look drag started: the flying stick is measured from here, not from last frame. */
    private float lookAnchorX;
    private float lookAnchorY;
    private float moveRadius = 90f;
    private float lookSensitivity = LOOK_DEGREES_PER_PX;

    // Map gesture state.
    private int mapPointerA = -1;
    private int mapPointerB = -1;
    private float lastPinchDistance;
    private float mapDragLastX;
    private float mapDragLastY;
    private float mapTravel;
    private boolean mapPressedButton;

    private String message = "";
    /** The Arabic twin of {@link #message}, when it has one: the stuck reports are bilingual. */
    private String messageArabic = "";
    /** Metres of odometer at the last stuck check, and how long the player has been asking to move. */
    private double lastOdometerM;
    private float stuckSeconds;
    private float messageSeconds;
    /**
     * Where the last tap on the map put the player, as a place rather than a pixel, and how long to keep
     * marking it.
     *
     * <p>A place and not a pixel because the map recentres when the player is teleported to it, and the
     * player can pan and zoom afterwards: a marker pinned to a screen coordinate would slide off the
     * spot it is meant to name, and the one thing a teleport must never do is lie about where you are.
     */
    private double teleportMarkEast;
    private double teleportMarkNorth;
    private boolean teleportMarkSet;
    private float teleportMarkSeconds;

    /** How long the launch credit stays in the corner: long enough to be read, short enough to ignore. */
    private static final float LAUNCH_CREDITS_SECONDS = 12f;

    /** Where selecting a helicopter takes it: high enough to fly, low enough to see where it is. */
    private static final double TAKE_OFF_HOVER_M = 25.0;

    /** Raster resolution used when the caller does not supply one. */
    private static final int DEFAULT_MAP_RESOLUTION = 1024;

    private float statusRefresh;
    private String statusLine = "";
    /**
     * The status panel, as the lines the HUD draws: the player's own line, then the world's measurements
     * wrapped to the width of a phone. See {@code StatusText} -- it was one line until the numbers added
     * over the last four rounds pushed its right-hand half off the screen.
     */
    private List<String> statusLines = Collections.emptyList();
    private String statusLineArabic = "";

    /**
     * Seconds left of the launch hint: the one sentence that says how to move. It is on screen for a
     * while at launch and gone afterwards, because a hint that never leaves is furniture.
     */
    private float hintSeconds = LAUNCH_CREDITS_SECONDS;
    /**
     * How long the flying hint stays up after a flying machine is selected. A hint that appears the
     * moment the controls change is the only moment the player is looking for it.
     */
    private static final float FLY_HINT_SECONDS = 8f;
    private float flyHintSeconds;

    /** The data credit, printed on the map screen and for the first seconds of a launch. */
    private String creditsEnglish = "";
    private float creditsSeconds = LAUNCH_CREDITS_SECONDS;

    /**
     * The map raster is built by the caller, during the loading stage that reports progress: building it
     * here, inside the first frame, was seconds of blocked GL thread in the middle of a launch.
     */
    public GameScreen(WorldData world, MapRaster raster) {
        this.world = world;
        this.raster = raster;
    }

    /** Fallback for tests and tools that do not have a raster yet. */
    public GameScreen(WorldData world) {
        this(world, null);
    }

    /**
     * Builds every subsystem. Called once, on the GL thread, before the first frame.
     *
     * <p>The floating origin is created at the spawn, so local coordinates start at zero and only grow
     * as far as the player travels before the next rebase -- which is why a country 259 km across can be
     * simulated in 32-bit floats without anything wobbling.
     */
    public void show() {
        ai.arena.qatar.boot.ScreenLog console = ai.arena.qatar.boot.ScreenLog.GLOBAL;

        // Every subsystem is built on its own and a failure is named and survived. This used to be one
        // long sequence: the third install gave up the *whole screen* because one part of it threw, and
        // the result on the phone was no controls, no map, nothing -- a fallback demo and a wall of text
        // where the game should have been, with no way to tell which of a dozen calls had failed. The
        // world, the controls and the maps are now independent: whatever builds, works.
        boolean rasterReady = step("map raster", () -> {
            if (raster == null) {
                console.log("Building the map raster (" + DEFAULT_MAP_RESOLUTION + ")");
                raster = MapRaster.build(world, DEFAULT_MAP_RESOLUTION);
            }
        });

        // The player's own position and the terrain under it: without these there is no game at all, so
        // this one is allowed to stop the screen rather than leave something half-built behind it.
        if (!stepEssential("the player and the terrain", () -> {
            double[] spawnBundle = spawnPosition();
            origin = new WorldOrigin(world.manifest.originEast + spawnBundle[0],
                    world.manifest.originNorth + spawnBundle[1]);
            sampler = new GridTerrainSampler(world.terrainL1, world.materials, origin);
            double[] spawn = {0.0, 0.0};
            rig = new CameraRig(sampler, spawn[0], spawn[1]);
            controller = new PlayerController(rig);
            // From here on the vehicles move through the real terrain: heights, materials and depths
            // all come from the compiled country, and a kerb is a step the walker has to be able to take.
            //
            // Setting the surface also re-seats every body on the real ground, which is what stops a
            // walker from starting the game with its feet twelve metres below the terrain around it.
            worldSurface = new WorldSurface(sampler, world);
            controller.setSurface(worldSurface);

            // And if the compiled spawn point is inside a building's box, the player starts the game
            // unable to move in any direction. Ask for a clear place before handing over the screen: a
            // player who cannot move is the ninth install's whole report.
            SpawnClearer.Spot clear = SpawnClearer.nearestClear(worldSurface, rig.east, rig.north);
            if (clear.moved) {
                controller.placeAll(clear.eastM, clear.northM, rig.yawDegrees);
                rig.east = clear.eastM;
                rig.north = clear.northM;
                console.log(String.format(java.util.Locale.ROOT,
                        "the spawn point was inside something solid; the player starts %.0f m away",
                        clear.movedM));
            }
            // Start looking north-west across the bay rather than into the sand: a first impression of a
            // capital city is the skyline across the water.
            rig.yawDegrees = 305.0;
            rig.pitchDegrees = -6.0;
        })) {
            return;
        }

        step("the world renderer", () -> {
            worldRenderer = new GameWorldRenderer(world, origin,
                    new ai.arena.qatar.render.WorldRenderer());
            worldRenderer.init();
            if (!worldRenderer.isReady()) {
                // The world shaders did not compile. Say so, with the driver's own words: the driver is
                // the only thing that knows why, and it is the one thing that cannot be tested here.
                console.error("World shaders did not compile: "
                        + ai.arena.qatar.render.WorldRenderer.lastShaderLog());
            } else {
                console.log("World shaders compiled: sky + terrain programs linked");
            }
            worldRenderer.setActor(controller.meshKind());
        });

        step("the credits", () -> creditsEnglish = Credits.english(world.manifest.attribution));

        step("tap-to-travel", () -> {
            if (raster == null) {
                mapUnavailableReason = "the map raster was not built, so there are no pixels to tap";
                return;
            }
            teleport = new TeleportService(world, raster);
            // The palette dialogs are named after real places, so a failed teleport service is visible
            // as a missing label rather than as a game that will not start.
        });

        step("the map view", () -> {
            if (raster == null) {
                mapUnavailableReason = "the map raster was not built, so there is nothing to show";
                return;
            }
            mapView = new MapView(raster);
            mapView.centreOnWorld(rig.east + offsetEastToBundle(), rig.north + offsetNorthToBundle());
        });

        // The controls last, and on their own: a font or a texture that will not load must leave the
        // player able to move with the world still drawing, not take the buttons down with the world.
        step("the controls", () -> {
            layout = new HudLayout();
            hud = new HudRenderer();
            hud.init(raster);
            if (!hud.fontsReady()) {
                console.warn("controls are drawn without labels: the HUD fonts did not load");
            }
        });

        step("the camera", () -> {
            camera = new PerspectiveCamera(72f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.near = 0.25f;
            camera.far = 12000f;             // resident radius plus margin; fog hides the end of it
            camera.up.set(0f, 1f, 0f);
        });

        try {
            resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            // Guarded, and the guard is the whole reason a tap that throws is survivable: the handlers run
            // on the GL thread from inside the platform's own event loop, which no frame guard of ours
            // wraps. See GuardedInput for the two lines of libGDX that prove it.
            ai.arena.qatar.boot.GuardedInput.install(this, (where, t) -> {
                // Straight onto the message line the player already reads: the failure is described to
                // them, not swallowed. Deliberately no Arabic twin -- the message is a diagnostic, and the
                // panels are English; the Arabic line stays for the game's own messages.
                message = "Control failed: " + where;
                messageArabic = "";
                messageSeconds = 4.0f;
            });
        } catch (Throwable t) {
            report("could not attach the touch controls", t);
        }
        // One line that says which subsystems exist, so a screenshot of the panel answers "what is
        // missing on this phone" without anybody having to read a stack trace.
        console.log("Subsystems: world=" + (worldRenderer != null)
                + " controls=" + (hud != null) + " map=" + (mapView != null)
                + " travel=" + (teleport != null) + " raster=" + (rasterReady && raster != null));
        Gdx.app.log("GameScreen", "World ready: " + world.describe());
        Gdx.app.log("GameScreen", "Map raster " + (rasterReady && raster != null
                ? raster.width + "x" + raster.height : "unavailable") + ", spawn "
                + String.format(java.util.Locale.ROOT, "%.0f, %.0f local", rig.east, rig.north));
    }

    /**
     * Runs one part of the setup, reporting a failure and carrying on without it.
     *
     * @return true when the part was built
     */
    private boolean step(String label, Runnable body) {
        try {
            body.run();
            return true;
        } catch (Throwable t) {
            report(label, t);
            return false;
        }
    }

    /** Runs one part of the setup that the screen cannot do without. */
    private boolean stepEssential(String label, Runnable body) {
        return step(label, body);
    }

    /** Puts a setup failure where a person can read it: the panel, the journal, and the phone's own dialog. */
    private void report(String label, Throwable t) {
        ai.arena.qatar.boot.ScreenLog.GLOBAL.error("Could not build " + label, t);
        ai.arena.qatar.boot.CrashJournal.record("game screen: " + label, t);
        ai.arena.qatar.boot.Alerts.report("Qatar: part of the game did not start",
                ai.arena.qatar.boot.Alerts.describe("Could not build " + label, t)
                        + "\n\nEverything else still runs: the world, the buttons and the map are built "
                        + "independently of each other.");
    }

    /** The player starts at a real place: a named car park or port, not the middle of the dunes. */
    private double[] spawnPosition() {
        // The spawn comes from the point bundle; the raster is only needed to know which parts are land,
        // and the caller has already built one -- building another 512-wide copy here cost a second.
        TeleportService chooser = new TeleportService(world, raster);
        TeleportService.Target target = chooser.spawnFor(ai.arena.qatar.world.Points.KIND_CAR);
        if ("Doha".equals(target.label)) {
            target = chooser.spawnFor(ai.arena.qatar.world.Points.KIND_BOAT);
        }
        double bundleEast = target.east;
        double bundleNorth = target.north;
        Gdx.app.log("GameScreen", "Spawn: " + target.label + " " + target);
        ai.arena.qatar.boot.ScreenLog.GLOBAL.log("Spawn: " + target.label + " at "
                + String.format(java.util.Locale.ROOT, "%.0f, %.0f", bundleEast, bundleNorth));
        return new double[]{bundleEast, bundleNorth};
    }

    private double offsetEastToBundle() {
        return origin.originEast() - world.manifest.originEast;
    }

    private double offsetNorthToBundle() {
        return origin.originNorth() - world.manifest.originNorth;
    }

    public void render(float deltaSeconds) {
        float dt = (float) Math.min(deltaSeconds, FixedStepClock.MAX_FRAME_DT);
        messageSeconds = Math.max(0f, messageSeconds - dt);

        rebaseIfNeeded();
        controller.update(dt);
        worldRenderer.update(rig.east, rig.north, dt);
        updateCamera();

        // The world and the controls are drawn under separate guards: whichever of them is broken, the
        // other one still appears. Losing the buttons because a chunk would not draw -- or the world
        // because a label would not -- is how a game turns into "nothing on the screen".
        frameSection = "the world";
        if (worldRenderer != null) {
            worldRenderer.render(camera);
            if (controller.chase) {
                // Drawn after the chunks, with the world program still bound: one more draw call per
                // frame buys the player the sight of the machine they are in.
                worldRenderer.drawActor(controller.state().eastM, controller.state().heightM,
                        controller.state().northM, controller.state().headingDegrees,
                        controller.spinDegrees, controller.meshKind());
            }
        }

        teleportMarkSeconds = Math.max(0f, teleportMarkSeconds - dt);
        reportStuckIfAskedAndNotMoving(dt);
        frameSection = "the controls";
        if (hud != null) {
            int width = Gdx.graphics.getWidth();
            int height = Gdx.graphics.getHeight();
            hud.begin();
            try {
                if (layout != null && layout.bigMapVisible() && mapView != null) {
                    hud.drawScrim(width, height);
                    Rect area = new Rect();
                    area.x = 0f;
                    area.y = height * 0.10f;
                    area.w = width;
                    area.h = height * 0.80f;
                    mapView.setViewport(area.x, area.y, area.w, area.h);
                    hud.drawBigMap(mapView, area, rig.east + offsetEastToBundle(),
                            rig.north + offsetNorthToBundle());
                    if (teleportMarkSet && teleportMarkSeconds > 0f) {
                        // A crosshair on the place the tap went to, asked for again every frame: it stays
                        // on the destination while the map is panned or zoomed, which is the only way a
                        // teleport of a hundred kilometres can be *seen* to have gone where it said.
                        float markX = mapView.screenXAt(teleportMarkEast, teleportMarkNorth);
                        float markY = mapView.screenYAt(teleportMarkEast, teleportMarkNorth);
                        if (markX >= area.x - 4f && markX <= area.x + area.w + 4f
                                && markY >= area.y - 4f && markY <= area.y + area.h + 4f) {
                            hud.drawTeleportMarker(markX, markY, Math.max(12f, width * 0.012f));
                        }
                    }
                    drawMapButtons();
                } else if (layout != null) {
                    drawWorldButtons(width, height);
                    if (hud.hasMap()) {
                        hud.drawMinimap(layout.minimap(), rig.east + offsetEastToBundle(),
                                rig.north + offsetNorthToBundle(), rig.yawDegrees, 1f);
                    }
                }
                drawStatusText(width, height, dt);
            } finally {
                // The batch is closed even when something inside threw: a batch left open makes the
                // *next* frame fail too, which turns one broken label into a game that never draws again.
                hud.end();
            }
        }
        frameSection = "";
    }

    private String frameSection = "";
    private int sectionFailures;
    private String lastSectionFailure = "";

    /** Called by the frame guard in {@code QatarGame} to name the part of the frame that threw. */
    public String describeFrameFailure(Throwable t) {
        String where = frameSection == null || frameSection.isEmpty() ? "while drawing" : frameSection;
        String key = where + ": " + t;
        if (!key.equals(lastSectionFailure)) {
            lastSectionFailure = key;
            sectionFailures = 0;
        }
        sectionFailures++;
        if (sectionFailures <= 3) {
            ai.arena.qatar.boot.ScreenLog.GLOBAL.error("Failed " + where, t);
            ai.arena.qatar.boot.CrashJournal.record("frame " + where, t);
        }
        return "Failed " + where + (sectionFailures > 3 ? " (again, " + sectionFailures + " times)"
                : "");
    }

    private void drawWorldButtons(int width, int height) {
        layout.setHighlighted(Id.MODE, rig.mode == CameraRig.Mode.FLY);
        layout.setHighlighted(Id.SPRINT, rig.sprinting);
        layout.setHighlighted(Id.VIEW, controller.chase);
        layout.setHighlighted(Id.LOCO_HUMAN, controller.loco() == Loco.HUMAN);
        layout.setHighlighted(Id.LOCO_CAR, controller.loco() == Loco.CAR);
        layout.setHighlighted(Id.LOCO_PLANE, controller.loco() == Loco.PLANE);
        layout.setHighlighted(Id.LOCO_HELI, controller.loco() == Loco.HELICOPTER);
        layout.setHighlighted(Id.LOCO_BOAT, controller.loco() == Loco.BOAT);
        boolean flying = controller.loco().flying;
        layout.setEnabled(Id.MODE, !flying);
        layout.setEnabled(Id.ALT_UP, flying);
        layout.setEnabled(Id.ALT_DOWN, flying);
        // JUMP is a walker's control: the machines ignore it, so it is dimmed and unpressed while they
        // are being driven, rather than sitting there looking available.
        layout.setEnabled(Id.JUMP, controller.loco() == Loco.HUMAN);
        layout.setHighlighted(Id.JUMP, controller.jumpPending());
        layout.button(Id.MODE).label = controller.loco() == Loco.CAR ? "WALK" : "DRIVE";
        for (Button button : layout.buttons()) {
            hud.drawButton(button, layout.isHighlighted(button.id));
        }
        // The movement stick: at rest where a thumb expects it, and where the thumb actually is while it
        // is being dragged. See HudLayout.stickHome -- a movement control nobody can see is a movement
        // control that gets reported as "cannot move".
        if (movePointer < 0) {
            HudLayout.Rect home = layout.stickHome();
            if (home.w > 0f) {
                hud.drawStickHome(home.x + home.w * 0.5f, home.y + home.h * 0.5f, home.w * 0.5f,
                        HudText.MOVE_LABEL, HudText.MOVE_LABEL_AR);
            }
        }
        if (movePointer >= 0) {
            hud.drawStick(moveAnchorX, moveAnchorY, moveCurrentX, moveCurrentY, moveRadius);
        }
        // And the flying stick, on the right, for the same reason: it is where a pilot's thumb goes, and
        // a control that has to be discovered is not a control. It is drawn whenever the machine being
        // flown is one that uses a stick -- which is both of the ones that fly.
        if (controller.loco().flying) {
            HudLayout.Rect flyHome = layout.flyStickHome();
            if (flyHome.w > 0f) {
                float flyRadius = flyHome.w * 0.5f;
                float centreX = flyHome.x + flyRadius;
                float centreY = flyHome.y + flyRadius;
                if (lookPointer < 0) {
                    hud.drawStickHome(centreX, centreY, flyRadius, HudText.FLY_LABEL,
                            HudText.FLY_LABEL_AR);
                } else {
                    hud.drawStick(centreX, centreY,
                            centreX + (float) controller.stickRoll() * flyRadius,
                            centreY + (float) controller.stickPitch() * flyRadius, flyRadius);
                }
            }
        }
    }

    /** One line of English and its Arabic twin, along the bottom centre, above the buttons. */
    private void drawLeftLines(String english, String arabic, int width, int height) {
        float baseline = height * 0.30f;
        hud.drawCentredStatus(english, width * 0.5f, baseline);
        hud.drawCentredStatusArabic(arabic, width * 0.5f, baseline - hud.lineHeight() * 1.15f);
    }

    private void drawMapButtons() {
        for (Button button : layout.buttons()) {
            hud.drawButton(button, false);
        }
    }

    private void drawStatusText(int width, int height, float dt) {
        statusRefresh -= dt;
        if (statusRefresh <= 0f) {
            statusRefresh = 0.25f;
            statusLines = StatusText.englishLines(controller.describe(),
                    rig.east + offsetEastToBundle(), rig.north + offsetNorthToBundle(),
                    worldRenderer.describe(), Gdx.graphics.getFramesPerSecond());
            // One line, for the tests and the crash report: the same text the panel shows, un-wrapped.
            statusLine = StatusText.english(controller.describe(),
                    rig.east + offsetEastToBundle(), rig.north + offsetNorthToBundle(),
                    worldRenderer.describe(), Gdx.graphics.getFramesPerSecond());
            statusLineArabic = controller.describeArabic();
        }
        // The status block stacks downwards from the top-left: English, then the same thing in Arabic,
        // then the map's hint in both. Each Arabic line is shaped on its own, so a mixed line never gets
        // reordered as a whole.
        float margin = height * 0.02f;
        float step = hud.lineHeight() * 1.2f;
        float lineY = height - margin;
        for (String line : statusLines) {
            hud.drawStatus(line, margin, lineY);
            lineY -= step;
        }
        hud.drawStatusArabic(statusLineArabic, margin, lineY);
        if (layout.bigMapVisible()) {
            lineY -= step;
            hud.drawStatus(HudText.MAP_HINT_EN, margin, lineY);
            lineY -= step;
            hud.drawStatusArabic(HudText.MAP_HINT_AR, margin, lineY);
        }
        // How to move. It goes at the foot of the screen for the first seconds of a launch: the stick is
        // drawn at rest now, but nothing else on the screen says which way to drag it, and the ninth
        // install's report was from a player who had not found it at all.
        hintSeconds = Math.max(0f, hintSeconds - dt);
        flyHintSeconds = Math.max(0f, flyHintSeconds - dt);
        if (!layout.bigMapVisible()) {
            if (flyHintSeconds > 0f) {
                drawLeftLines(HudText.FLY_HINT_EN, HudText.FLY_HINT_AR, width, height);
            } else if (hintSeconds > 0f) {
                drawLeftLines(HudText.CONTROLS_HINT_EN, HudText.CONTROLS_HINT_AR, width, height);
            }
        }

        // The data credit, in both languages. On the map screen, which is the picture made from that
        // data, it stays up; after a launch it fades out of the corner by itself. ODbL asks for the
        // attribution to reach the player, and an APK that never prints it does not.
        creditsSeconds = Math.max(0f, creditsSeconds - dt);
        if (creditsSeconds > 0f || layout.bigMapVisible()) {
            float creditY = layout.bigMapVisible() ? height * 0.095f : height * 0.05f;
            hud.drawStatus(creditsEnglish, margin, creditY);
            hud.drawStatusArabic(Credits.ARABIC, margin, creditY - step);
        }
        if (messageSeconds > 0f && !message.isEmpty()) {
            hud.drawMessage(message, margin, height * 0.18f, false);
            if (!messageArabic.isEmpty()) {
                hud.drawMessage(messageArabic, margin, height * 0.18f - step * 1.2f, true);
            }
        }
        if (!hud.fontsReady()) {
            hud.drawStatus("(HUD fonts missing)", margin, margin);
        }
    }

    private void updateCamera() {
        camera.viewportWidth = Gdx.graphics.getWidth();
        camera.viewportHeight = Gdx.graphics.getHeight();
        camera.position.set((float) rig.east, (float) rig.height, (float) rig.north);
        double yaw = Math.toRadians(rig.yawDegrees);
        double pitch = Math.toRadians(rig.pitchDegrees);
        double cosPitch = Math.cos(pitch);
        camera.direction.set((float) (Math.sin(yaw) * cosPitch), (float) Math.sin(pitch),
                (float) (Math.cos(yaw) * cosPitch));
        camera.update();
    }

    /**
     * Keeps the floating origin near the player.
     *
     * <p>Nothing visible happens: the world does not move, only the numbers describing where it is. That
     * is the whole point -- metres stay small and precise even at the far end of the country.
     */
    private void rebaseIfNeeded() {
        double globalEast = origin.globalEast(rig.east);
        double globalNorth = origin.globalNorth(rig.north);
        Vec3 delta = origin.planRebase(globalEast, globalNorth);
        if (delta.x == 0.0 && delta.z == 0.0) {
            return;
        }
        origin.applyRebase(delta);
        // Everything local moves by -delta in the same frame: every body (and, through it, the rig --
        // see PlayerController.shiftLocal) as well as the renderer's bundle offset. Shifting the rig
        // alone was the bug: the boss's own position was recomputed from the body a few lines later, so
        // it was put straight back at the stale coordinate and the origin rebased again on the very next
        // frame, for ever.
        if (controller != null) {
            controller.shiftLocal(delta.x, delta.z);
        } else {
            rig.east -= delta.x;
            rig.north -= delta.z;
        }
        if (worldRenderer != null) {
            worldRenderer.updateOffsets();
        }
    }

    /**
     * Says why a body that has been asked to move is not moving, in both languages.
     *
     * <p>The tenth install's report was "cant walk, use car": four words, because a speed of zero is all
     * the screen said. The game knows better than that -- the body's own state names the rule that
     * refused it -- and a player who is stuck is owed the reason on the screen rather than in a bug
     * report. Two seconds of asking is the threshold: shorter than that is a player still deciding.
     */
    private void reportStuckIfAskedAndNotMoving(float dt) {
        rescueCooldown = Math.max(0f, rescueCooldown - dt);
        if (controller == null || layout == null || layout.bigMapVisible()) {
            return;
        }
        double odometer = controller.state().odometerM;
        boolean asked = Math.abs(controller.forward) > 0.15 || Math.abs(controller.strafe) > 0.15
                || Math.abs(controller.climb) > 0.05;
        boolean moved = odometer - lastOdometerM > 0.02;
        lastOdometerM = odometer;
        if (!asked || moved) {
            stuckSeconds = 0f;
            return;
        }
        stuckSeconds += dt;
        if (stuckSeconds < 2.0f) {
            return;
        }
        stuckSeconds = 0f;                                  // say it again in another two seconds
        ai.arena.qatar.vehicle.VehicleState state = controller.state();
        boolean loaded = worldSurface == null || worldSurface.collisionLoaded(state.eastM, state.northM);
        if (!loaded) {
            message = HudText.STUCK_TERRAIN;
            messageArabic = HudText.STUCK_TERRAIN_AR;
        } else if (state.wading || worldSurface == null
                || worldSurface.waterDepthM(state.eastM, state.northM) > ai.arena.qatar.vehicle.HumanBody.WADE_DEPTH_M) {
            message = HudText.STUCK_WATER;
            messageArabic = HudText.STUCK_WATER_AR;
        } else if (state.blocked) {
            message = HudText.STUCK_SOLID;
            messageArabic = HudText.STUCK_SOLID_AR;
        } else if (!state.grounded) {
            message = HudText.STUCK_AIR;
            messageArabic = HudText.STUCK_AIR_AR;
        } else {
            message = HudText.STUCK_OTHER;
            messageArabic = HudText.STUCK_OTHER_AR;
        }
        messageSeconds = 3.0f;
        ai.arena.qatar.boot.ScreenLog.GLOBAL.warn("asked to move and did not: " + message
                + " -- " + state.describe());
        // And then, before settling for the explanation: the game does something about it.
        if (rescueCooldown <= 0f) {
            String rescued = rescueStuckBody(state, loaded);
            if (rescued != null) {
                message = rescued;
                messageArabic = "";
                messageSeconds = 4.0f;
            }
        }
    }

    /** Seconds between rescues: a body that keeps getting stuck is still the player's to steer. */
    private static final float RESCUE_INTERVAL_S = 6.0f;
    private float rescueCooldown;

    /**
     * Steps a body that cannot move to the nearest place it could.
     *
     * <p>The tenth install's report was "cant walk, use car", and the game's answer to it used to be
     * words. Words are not enough when the body has been *placed* badly: inside the footprint of a
     * building, in water deeper than a person can wade, or on ground whose collision was never compiled.
     * No amount of pressing will get a body out of any of those, and the player has no way to know that
     * from the screen. So after two seconds of trying the game looks for the nearest spot a body of this
     * kind could move from -- a few metres away, at the same height, on ground it can stand on, outside
     * anything solid -- and puts it there.
     *
     * <p>Deliberately narrow. It runs only for the three reasons that are placements rather than physics (a
     * missing grid, water too deep to cross, something solid where the body is standing); it never lifts a
     * flying body, because a helicopter that will not move is a different fault and hiding it would be
     * worse than reporting it; and it will not do it twice within {@link #RESCUE_INTERVAL_S} seconds, so a
     * player who is deliberately leaning on a wall is not teleported around the map for it.
     *
     * @return the message to show, or null when nothing was done
     */
    private String rescueStuckBody(ai.arena.qatar.vehicle.VehicleState state, boolean collisionLoaded) {
        if (worldSurface == null) {
            return null;
        }
        Loco loco = controller.loco();
        if (loco.flying) {
            return null;
        }
        boolean deepWater = state.wading || worldSurface.waterDepthM(state.eastM, state.northM)
                > ai.arena.qatar.vehicle.HumanBody.WADE_DEPTH_M;
        if (collisionLoaded && !deepWater && !state.blocked) {
            return null;
        }
        // What this body can use, by kind: a walker wades, a car does not swim, and a boat needs water.
        double minDepth = 0.0;
        double maxDepth = ai.arena.qatar.vehicle.HumanBody.WADE_DEPTH_M;
        double maxStep = 1.5;
        if (loco == Loco.CAR) {
            maxDepth = 0.4;
            maxStep = 2.0;
        } else if (loco == Loco.BOAT) {
            minDepth = 0.8;
            maxDepth = 100.0;
            maxStep = 3.0;
        }
        SpawnClearer.Spot spot = SpawnClearer.nearestUsable(worldSurface, state.eastM, state.northM,
                minDepth, maxDepth, maxStep);
        if (!spot.moved) {
            // Nowhere within a hundred and sixty metres is any better than here. Say the reason and leave
            // the body alone: a teleport to a worse place is worse than a wall.
            ai.arena.qatar.boot.ScreenLog.GLOBAL.warn("no clear place within "
                    + (int) SpawnClearer.MAX_SEARCH_M + " m to move this body to");
            return null;
        }
        double deltaEast = spot.eastM - state.eastM;
        double deltaNorth = spot.northM - state.northM;
        // The bodies are shifted *away* from the delta, which is the same convention the floating origin
        // rebase uses: see PlayerController.shiftLocal.
        controller.shiftLocal(-deltaEast, -deltaNorth);
        lastOdometerM = controller.state().odometerM;
        rescueCooldown = RESCUE_INTERVAL_S;
        ai.arena.qatar.boot.ScreenLog.GLOBAL.warn("moved a stuck body "
                + String.format(java.util.Locale.ROOT, "%.0f m", spot.movedM) + " clear of "
                + (collisionLoaded ? deepWater ? "water it cannot cross" : "something solid"
                        : "uncompiled ground"));
        return String.format(java.util.Locale.ROOT, "Stepped clear (%.0f m): move again", spot.movedM);
    }

    /**
     * The hardware BACK key (and ESCAPE on a desktop) closes the open map.
     *
     * <p>It is the one key on an Android phone, and it means "back out of this". An open map that
     * ignores it leaves the player only the CLOSE button; an app that exits when the map is open is
     * worse. Handled here and consumed, so the platform never sees it while the map is up.
     */
    @Override
    public boolean keyDown(int keycode) {
        if (layout != null && layout.bigMapVisible()
                && (keycode == Input.Keys.BACK || keycode == Input.Keys.ESCAPE)) {
            closeBigMap();
            return true;
        }
        return false;
    }

    /**
     * Opens the whole map, and lets BACK mean "close the map" while it is up.
     *
     * <p>{@code setCatchKey(Keys.BACK, true)} is what makes the phone hand the key to the game instead
     * of closing the activity with it -- and it is set here rather than once at startup, because while
     * the map is *closed* the player must still be able to press BACK and leave.
     */
    private void markMapOpen(boolean open) {
        layout.setBigMapVisible(open);
        if (Gdx.input != null) {
            Gdx.input.setCatchKey(Input.Keys.BACK, open);
        }
    }

    /** Closes the map from anywhere -- the CLOSE button, BACK, or a map that failed to build. */
    private void closeBigMap() {
        markMapOpen(false);
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        float x = screenX;
        float y = Gdx.graphics.getHeight() - screenY;   // HUD space is y-up
        if (layout.bigMapVisible()) {
            return mapTouchDown(x, y, pointer);
        }
        Button hit = layout.hit(x, y);
        if (hit != null) {
            buttonPointer = pointer;
            fire(hit.id, true);
            return true;
        }
        if (movePointer < 0 && layout.moveZone().contains(x, y)) {
            movePointer = pointer;
            moveAnchorX = x;
            moveAnchorY = y;
            moveCurrentX = x;
            moveCurrentY = y;
            return true;
        }
        if (layout.minimapTouch().contains(x, y)) {
            // The drawn square, or anywhere in its corner: see HudLayout.minimapTouch for why the target
            // is bigger than the picture.
            openBigMap();
            return true;
        }
        if (lookPointer < 0) {
            lookPointer = pointer;
            lookLastX = x;
            lookLastY = y;
            lookAnchorX = x;
            lookAnchorY = y;
            return true;
        }
        return false;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        float x = screenX;
        float y = Gdx.graphics.getHeight() - screenY;
        if (layout.bigMapVisible()) {
            return mapTouchDragged(x, y, pointer);
        }
        if (pointer == lookPointer) {
            if (controller.loco() == Loco.HELICOPTER) {
                // The cyclic is a stick, so the gesture is a stick: the offset of the thumb from where the
                // drag started *is* the deflection, and it stays deflected until the finger lets go. This
                // used to be a delta with a spring, so a still thumb was a machine that flew itself level
                // again after half a second -- see PlayerController.setStick.
                float flyRadius = Math.max(1f, layout.flyStickHome().w * 0.5f);
                float deflectionX = (x - lookAnchorX) / flyRadius;
                float deflectionY = (y - lookAnchorY) / flyRadius;
                float magnitude = (float) Math.sqrt(deflectionX * deflectionX + deflectionY * deflectionY);
                if (magnitude > 1f) {
                    deflectionX /= magnitude;
                    deflectionY /= magnitude;
                }
                controller.setStick(deflectionY, deflectionX);
            } else {
                float dx = x - lookLastX;
                float dy = y - lookLastY;
                controller.look(dx * lookSensitivity, dy * lookSensitivity);
            }
            lookLastX = x;
            lookLastY = y;
            return true;
        }
        if (pointer == movePointer) {
            moveCurrentX = x;
            moveCurrentY = y;
            updateMoveInput();
            return true;
        }
        if (pointer == buttonPointer) {
            // Pressing a button and sliding off it cancels the press: no accidental mode changes.
            Button hit = layout.hit(x, y);
            if (hit == null) {
                cancelButton();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        float x = screenX;
        float y = Gdx.graphics.getHeight() - screenY;
        if (layout.bigMapVisible()) {
            return mapTouchUp(x, y, pointer);
        }
        if (pointer == movePointer) {
            movePointer = -1;
            controller.forward = 0;
            controller.strafe = 0;
            return true;
        }
        if (pointer == lookPointer) {
            lookPointer = -1;
            // A stick is let go of, not switched off: the spring in the controller returns it to trim, and
            // a helicopter that was being flown home keeps its heading while the disc levels.
            controller.releaseStick();
            return true;
        }
        if (pointer == buttonPointer) {
            finishButton();
            buttonPointer = -1;
            return true;
        }
        return false;
    }

    /** Converts the stick position into movement input: push forward to go forward. */
    private void updateMoveInput() {
        float dx = moveCurrentX - moveAnchorX;
        float dy = moveCurrentY - moveAnchorY;
        float forward = -dy / moveRadius;
        float strafe = dx / moveRadius;
        float magnitude = (float) Math.sqrt(forward * forward + strafe * strafe);
        if (magnitude < 0.12f) {
            controller.forward = 0;
            controller.strafe = 0;
            return;
        }
        float scale = Math.min(1f, magnitude) / magnitude;
        controller.forward = forward * scale;
        controller.strafe = strafe * scale;
    }

    private void fire(Id id, boolean pressed) {
        switch (id) {
            case MODE:
                // One press between the two ground ways of getting about; the flying machines have their
                // own buttons because they are not a mode of walking.
                controller.setLoco(controller.loco() == Loco.CAR ? Loco.HUMAN : Loco.CAR);
                controller.chase = true;
                worldRenderer.setActor(controller.meshKind());
                break;
            case SPRINT:
                controller.sprinting = pressed;
                break;
            case JUMP:
                if (pressed) {
                    controller.requestJump();
                }
                break;
            case BRAKE:
                controller.braking = pressed;
                break;
            case VIEW:
                if (pressed) {
                    controller.chase = !controller.chase;
                }
                break;
            case LOCO_HUMAN:
                controller.setLoco(Loco.HUMAN);
                worldRenderer.setActor(controller.meshKind());
                break;
            case LOCO_CAR:
                controller.setLoco(Loco.CAR);
                worldRenderer.setActor(controller.meshKind());
                break;
            case LOCO_PLANE:
                controller.setLoco(Loco.PLANE);
                flyHintSeconds = FLY_HINT_SECONDS;
                worldRenderer.setActor(controller.meshKind());
                // No altitude hold for the aeroplane: a wing climbs by flying, so the stick is the whole
                // control. It used to ask for 220 m here, which the aeroplane ignores -- a line that read
                // as if it did something, which is worse than no line at all.
                break;
            case LOCO_HELI:
                controller.setLoco(Loco.HELICOPTER);
                flyHintSeconds = FLY_HINT_SECONDS;
                worldRenderer.setActor(controller.meshKind());
                // A take-off to a low hover, and then it is the player's: the stick flies it, the
                // altitude buttons climb and descend. It used to command 120 m on selection, whether or
                // not anyone was flying, which is the tenth install's "it flies but not perfectly" --
                // a machine that left on its own and then could not be commanded.
                controller.takeOff(TAKE_OFF_HOVER_M);
                break;
            case LOCO_BOAT:
                controller.setLoco(Loco.BOAT);
                worldRenderer.setActor(controller.meshKind());
                break;
            case MAP:
                openBigMap();
                break;
            case ALT_UP:
                controller.climb = pressed ? 1.0 : 0.0;
                break;
            case ALT_DOWN:
                controller.climb = pressed ? -1.0 : 0.0;
                break;
            case ZOOM_IN:
                if (pressed) {
                    mapView.zoomAt(layout.screenWidth() * 0.5f, layout.screenHeight() * 0.5f, ZOOM_STEP);
                }
                break;
            case ZOOM_OUT:
                if (pressed) {
                    mapView.zoomAt(layout.screenWidth() * 0.5f, layout.screenHeight() * 0.5f,
                            1f / ZOOM_STEP);
                }
                break;
            case MAP_CLOSE:
                if (pressed) {
                    closeBigMap();
                }
                break;
            case RECENTRE:
                if (pressed) {
                    mapView.centreOnWorld(rig.east + offsetEastToBundle(),
                            rig.north + offsetNorthToBundle());
                }
                break;
            default:
                break;
        }
    }

    private void cancelButton() {
        controller.sprinting = false;
        controller.climb = 0.0;
        buttonPointer = -1;
    }

    private void finishButton() {
        controller.climb = 0.0;   // altitude buttons work while held and stop when released
    }

    private void openBigMap() {
        if (mapView == null) {
            // The map is built independently of everything else now, so it can be missing while the game
            // runs. Saying so is the whole difference between a button that does nothing and a button
            // that does nothing *and says why*.
            message = "The map is not available in this session: " + mapUnavailableReason;
            messageSeconds = 6f;
            ai.arena.qatar.boot.ScreenLog.GLOBAL.error(message);
            ai.arena.qatar.boot.Alerts.report("Qatar: the map is not available",
                    "The first-person world is running, but the map was never built, so this button "
                            + "cannot open it.\n\n" + mapUnavailableReason);
            return;
        }
        markMapOpen(true);
        mapView.centreOnWorld(rig.east + offsetEastToBundle(), rig.north + offsetNorthToBundle());
        message = "";
        messageArabic = "";
    }

    // ---------------------------------------------------------------- big map gestures

    /** Why the map is missing, when it is: set by show() so the button can name the reason. */
    private String mapUnavailableReason = "the map raster or the map view did not build";

    private boolean mapTouchDown(float x, float y, int pointer) {
        if (mapView == null) {
            closeBigMap();
            return true;
        }
        Button hit = layout.hit(x, y);
        if (hit != null) {
            mapPressedButton = true;
            mapPointerA = pointer;
            fire(hit.id, true);
            return true;
        }
        if (mapPointerA < 0) {
            mapPointerA = pointer;
            mapDragLastX = x;
            mapDragLastY = y;
            mapTravel = 0f;
        } else if (mapPointerB < 0) {
            mapPointerB = pointer;
            lastPinchDistance = distanceBetween(mapPointerA, mapPointerB);
        }
        return true;
    }

    /** Distance between two live pointers, in HUD space; 0 when either is out of range. */
    private float distanceBetween(int pointerA, int pointerB) {
        if (pointerA < 0 || pointerB < 0 || pointerA == pointerB) {
            return 0f;
        }
        float ax = Gdx.input.getX(pointerA);
        float ay = Gdx.graphics.getHeight() - Gdx.input.getY(pointerA);
        float bx = Gdx.input.getX(pointerB);
        float by = Gdx.graphics.getHeight() - Gdx.input.getY(pointerB);
        float dx = bx - ax;
        float dy = by - ay;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private boolean mapTouchDragged(float x, float y, int pointer) {
        if (mapPressedButton) {
            return true;
        }
        if (pointer == mapPointerB) {
            float distance = distanceBetween(mapPointerA, mapPointerB);
            if (lastPinchDistance > 1f && distance > 1f) {
                float factor = distance / lastPinchDistance;
                mapView.zoomAt(x, y, factor);
            }
            lastPinchDistance = distance;
            return true;
        }
        if (pointer == mapPointerA) {
            float dx = x - mapDragLastX;
            float dy = y - mapDragLastY;
            mapTravel += Math.abs(dx) + Math.abs(dy);
            mapView.pan(dx, dy);
            mapDragLastX = x;
            mapDragLastY = y;
            return true;
        }
        return false;
    }

    private boolean mapTouchUp(float x, float y, int pointer) {
        if (mapPressedButton) {
            mapPressedButton = false;
            mapPointerA = -1;
            return true;
        }
        if (pointer == mapPointerB) {
            mapPointerB = -1;
            mapPointerA = -1;
            return true;
        }
        if (pointer == mapPointerA) {
            boolean tap = mapTravel < TAP_SLOP_PX;
            mapPointerA = -1;
            if (tap) {
                teleportTo(x, y);
            }
            return true;
        }
        return false;
    }

    /**
     * Travels where the player tapped.
     *
     * <p>A tap is a hope, not a coordinate: a finger covers a hundred metres of this country, so the
     * point is resolved into a place that can actually be stood on and the player is told where they
     * ended up, in the data's own name for it.
     */
    private void teleportTo(float screenX, float screenY) {
        if (mapView == null || raster == null || teleport == null) {
            // A tap that cannot be honoured must not throw inside the touch handler: the frame guard
            // would report it as a drawing failure, which is worse than saying what is missing.
            return;
        }
        if (!mapView.containsPoint(screenX, screenY)) {
            // The map keeps its aspect ratio inside the rectangle it is given, so there are bands of
            // scrim beside it that belong to the map *screen* and not to the map. A tap there used to be
            // resolved as a world position anyway -- extrapolated past the country's edge -- and the
            // player was sent to whatever that arithmetic produced. A tap off the map is not a place.
            message = "Tap on the map to travel";
            messageArabic = "";
            messageSeconds = 2.0f;
            return;
        }
        double beforeEast = rig.east;
        double beforeNorth = rig.north;
        double pixelX = mapView.uAt(screenX) * raster.width;
        double pixelY = mapView.vAt(screenY) * raster.height;
        TeleportService.Target target = teleport.fromMapPixel(pixelX, pixelY);
        double localEast = target.east - offsetEastToBundle();
        double localNorth = target.north - offsetNorthToBundle();
        // Resolved to a place, but a place can still be inside a building's box -- the boxes overlap
        // roads, and a compound is one box around all of it -- and a body inside a box is refused in
        // every direction by its own collision rule. Asking for clear ground is what makes a teleport
        // arrive somewhere the player can drive away from.
        if (worldSurface != null) {
            SpawnClearer.Spot clear = SpawnClearer.nearestClear(worldSurface, localEast, localNorth);
            if (clear.moved) {
                localEast = clear.eastM;
                localNorth = clear.northM;
                if (clear.movedM > 1.0) {
                    ai.arena.qatar.boot.ScreenLog.GLOBAL.log(String.format(java.util.Locale.ROOT,
                            "the tapped place was inside something solid; stepped %.0f m aside",
                            clear.movedM));
                }
            }
        }
        // Arrive facing the way the place faces: a runway threshold points down its runway and a road
        // vertex points along its road, so the player who taps a road can drive off instead of turning
        // around first. A place with no mapped direction keeps whatever heading the player had.
        double heading = target.headingDegrees >= 0.0 ? target.headingDegrees
                : controller.state().headingDegrees;
        // How far this was, measured in the frame the player is still in.
        double travelledM = Math.hypot(localEast - beforeEast, localNorth - beforeNorth);
        rig.placeAt(localEast, localNorth);
        controller.placeAll(localEast, localNorth, heading);
        // The origin rebases in this frame, not the next one: the player is now tens of kilometres from
        // where the frame started, and a frame that runs with a stale floating origin draws the country
        // around the wrong place. See rebaseIfNeeded and PlayerController.shiftLocal.
        rebaseIfNeeded();
        if (worldRenderer != null) {
            // And the streaming is told: what is resident is a hundred kilometres of the wrong country,
            // and the frame after a jump is the one that has to have the ground in it. The cache's own
            // distance eviction drops the old window on the next update; this pays for the new one.
            worldRenderer.onPlayerJumped();
        }
        mapView.centreOnWorld(target.east, target.north);
        teleportMarkEast = target.east;
        teleportMarkNorth = target.north;
        teleportMarkSet = true;
        teleportMarkSeconds = 3.0f;
        String where = target.label == null || target.label.isEmpty() ? "unnamed place" : target.label;
        String how = target.snappedToSpawn ? "spawn" : (target.snappedToRoad ? "road" : "open ground");
        message = String.format(java.util.Locale.ROOT, "Travelled to %s (%s), %.0f m away, facing %.0f",
                where, how, travelledM, heading);
        messageArabic = "";
        messageSeconds = 4.5f;
        Gdx.app.log("GameScreen", "Teleport -> " + where + " as " + how + " " + target);
    }

    public void resize(int width, int height) {
        if (layout != null) {
            layout.resize(width, height);
            hud.resize(width, height);
            // The radius of the ring on the screen *is* the drag that means full deflection: a stick that
            // is drawn at one size and responds at another is a stick the player cannot learn. It used to
            // be a fixed 90 px against a ring drawn at 14% of the screen's short side -- a hundred and
            // fifty px on a phone -- so full speed was reached at sixty per cent of the ring.
            moveRadius = layout.stickRadius();
            lookSensitivity = LOOK_DEGREES_PER_PX * (height / 720f);
        }
    }

    public void pause() {
        clock.reset();
    }

    public void resume() {
        clock.reset();
    }

    public String statusLine() {
        return statusLine;
    }

    public PlayerController controller() {
        return controller;
    }

    public HudLayout layout() {
        return layout;
    }

    public MapRaster raster() {
        return raster;
    }

    public GameWorldRenderer worldRenderer() {
        return worldRenderer;
    }

    public WorldOrigin origin() {
        return origin;
    }

    /**
     * Releases everything this screen owns, including GL resources.
     *
     * <p>{@code glReady} says whether a context is current. When it is not -- the surface was
     * destroyed, or the app is on its way out -- the GL objects are left to the driver instead: the
     * alternative is what the eighth install did, which is to crash inside the cleanup by calling
     * {@code glDepthMask} with no {@code Gdx.gl}. Input and the map raster are released either way,
     * because neither needs a context and both are worth releasing.
     */
    public void dispose(boolean glReady) {
        // Leave the platform as it was found: a caught BACK key outlives this screen, and an app whose
        // BACK key does nothing at all is worse than one that closes when asked.
        if (Gdx.input != null) {
            Gdx.input.setCatchKey(Input.Keys.BACK, false);
        }
        if (worldRenderer != null) {
            if (glReady) {
                worldRenderer.dispose();
            }
        }
        if (hud != null) {
            hud.dispose(glReady);
        }
    }

    @Override
    public void dispose() {
        // Called by the platform when the context is normally current; the argument version does the
        // work and knows how to survive when it is not.
        dispose(true);
    }
}
