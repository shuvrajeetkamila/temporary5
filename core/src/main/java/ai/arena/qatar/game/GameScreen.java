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
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.utils.Disposable;

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
    private float messageSeconds;

    /** How long the launch credit stays in the corner: long enough to be read, short enough to ignore. */
    private static final float LAUNCH_CREDITS_SECONDS = 12f;

    /** Raster resolution used when the caller does not supply one. */
    private static final int DEFAULT_MAP_RESOLUTION = 1024;

    private float statusRefresh;
    private String statusLine = "";
    private String statusLineArabic = "";

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
            controller.setSurface(new WorldSurface(sampler, world));
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
            Gdx.input.setInputProcessor(this);
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
        layout.button(Id.MODE).label = controller.loco() == Loco.CAR ? "WALK" : "DRIVE";
        for (Button button : layout.buttons()) {
            hud.drawButton(button, layout.isHighlighted(button.id));
        }
        // The move stick, drawn where the thumb actually is.
        if (movePointer >= 0) {
            hud.drawStick(moveAnchorX, moveAnchorY, moveCurrentX, moveCurrentY, moveRadius);
        }
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
        hud.drawStatus(statusLine, margin, lineY);
        lineY -= step;
        hud.drawStatusArabic(statusLineArabic, margin, lineY);
        if (layout.bigMapVisible()) {
            lineY -= step;
            hud.drawStatus(HudText.MAP_HINT_EN, margin, lineY);
            lineY -= step;
            hud.drawStatusArabic(HudText.MAP_HINT_AR, margin, lineY);
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
        rig.east -= delta.x;
        rig.north -= delta.z;
        worldRenderer.updateOffsets();
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
        if (movePointer < 0 && x < layout.screenWidth() * 0.3f && y < layout.screenHeight() * 0.62f) {
            movePointer = pointer;
            moveAnchorX = x;
            moveAnchorY = y;
            moveCurrentX = x;
            moveCurrentY = y;
            return true;
        }
        if (layout.minimap().contains(x, y)) {
            openBigMap();
            return true;
        }
        if (lookPointer < 0) {
            lookPointer = pointer;
            lookLastX = x;
            lookLastY = y;
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
            float dx = x - lookLastX;
            float dy = y - lookLastY;
            controller.look(dx * lookSensitivity, dy * lookSensitivity);
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
                worldRenderer.setActor(controller.meshKind());
                controller.adjustAltitude(220.0);
                break;
            case LOCO_HELI:
                controller.setLoco(Loco.HELICOPTER);
                worldRenderer.setActor(controller.meshKind());
                controller.adjustAltitude(120.0);
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
                    layout.setBigMapVisible(false);
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
        layout.setBigMapVisible(true);
        mapView.centreOnWorld(rig.east + offsetEastToBundle(), rig.north + offsetNorthToBundle());
        message = "";
    }

    // ---------------------------------------------------------------- big map gestures

    /** Why the map is missing, when it is: set by show() so the button can name the reason. */
    private String mapUnavailableReason = "the map raster or the map view did not build";

    private boolean mapTouchDown(float x, float y, int pointer) {
        if (mapView == null) {
            layout.setBigMapVisible(false);
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
        double pixelX = mapView.uAt(screenX) * raster.width;
        double pixelY = mapView.vAt(screenY) * raster.height;
        TeleportService.Target target = teleport.fromMapPixel(pixelX, pixelY);
        double localEast = target.east - offsetEastToBundle();
        double localNorth = target.north - offsetNorthToBundle();
        rig.placeAt(localEast, localNorth);
        controller.placePlayer(localEast, localNorth);
        mapView.centreOnWorld(target.east, target.north);
        String where = target.label == null || target.label.isEmpty() ? "unnamed place" : target.label;
        String how = target.snappedToSpawn ? "spawn" : (target.snappedToRoad ? "road" : "open ground");
        message = "Travelled to " + where + " (" + how + ")";
        messageSeconds = 4.5f;
        Gdx.app.log("GameScreen", "Teleport -> " + where + " as " + how + " " + target);
    }

    public void resize(int width, int height) {
        if (layout != null) {
            layout.resize(width, height);
            hud.resize(width, height);
            moveRadius = Math.min(width, height) * 0.14f;
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

    @Override
    public void dispose() {
        if (worldRenderer != null) {
            worldRenderer.dispose();
        }
        if (hud != null) {
            hud.dispose();
        }
    }
}
