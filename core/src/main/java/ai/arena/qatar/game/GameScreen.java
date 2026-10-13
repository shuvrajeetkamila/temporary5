package ai.arena.qatar.game;

import ai.arena.qatar.game.PlayerController.Loco;
import ai.arena.qatar.hud.HudLayout;
import ai.arena.qatar.hud.HudLayout.Button;
import ai.arena.qatar.hud.HudLayout.Id;
import ai.arena.qatar.hud.HudLayout.Rect;
import ai.arena.qatar.hud.HudRenderer;
import ai.arena.qatar.hud.MapRaster;
import ai.arena.qatar.hud.MapView;
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
    private final Vec3 moveAnchor = new Vec3();
    private final Vec3 moveCurrent = new Vec3();
    private final Vec3 lookLast = new Vec3();
    private float moveRadius = 90f;
    private float lookSensitivity = LOOK_DEGREES_PER_PX;

    // Map gesture state.
    private int mapPointerA = -1;
    private int mapPointerB = -1;
    private float lastPinchDistance;
    private final Vec3 mapDragLast = new Vec3();
    private float mapTravel;
    private boolean mapPressedButton;

    private String message = "";
    private float messageSeconds;

    private float statusRefresh;
    private String statusLine = "";

    public GameScreen(WorldData world) {
        this.world = world;
    }

    /**
     * Builds every subsystem. Called once, on the GL thread, before the first frame.
     *
     * <p>The floating origin is created at the spawn, so local coordinates start at zero and only grow
     * as far as the player travels before the next rebase -- which is why a country 259 km across can be
     * simulated in 32-bit floats without anything wobbling.
     */
    public void show() {
        double[] spawnBundle = spawnPosition();
        origin = new WorldOrigin(world.manifest.originEast + spawnBundle[0],
                world.manifest.originNorth + spawnBundle[1]);
        sampler = new GridTerrainSampler(world.terrainL1, world.materials, origin);
        double[] spawn = {0.0, 0.0};
        rig = new CameraRig(sampler, spawn[0], spawn[1]);
        controller = new PlayerController(rig);
        // Start looking north-west across the bay rather than into the sand: a first impression of a
        // capital city is the skyline across the water.
        rig.yawDegrees = 305.0;
        rig.pitchDegrees = -6.0;

        worldRenderer = new GameWorldRenderer(world, origin, new ai.arena.qatar.render.WorldRenderer());
        worldRenderer.init();

        raster = MapRaster.build(world, 2048);
        teleport = new TeleportService(world, raster);
        mapView = new MapView(raster);
        mapView.centreOnWorld(rig.east + offsetEastToBundle(), rig.north + offsetNorthToBundle());

        layout = new HudLayout();
        hud = new HudRenderer();
        hud.init(raster);

        camera = new PerspectiveCamera(72f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.near = 0.25f;
        camera.far = 12000f;                 // resident radius plus margin; fog hides the end of it
        camera.up.set(0f, 1f, 0f);

        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        Gdx.input.setInputProcessor(this);
        Gdx.app.log("GameScreen", "World ready: " + world.describe());
        Gdx.app.log("GameScreen", "Map raster " + raster.width + "x" + raster.height + ", spawn "
                + String.format(java.util.Locale.ROOT, "%.0f, %.0f local", rig.east, rig.north));
    }

    /** The player starts at a real place: a named car park or port, not the middle of the dunes. */
    private double[] spawnPosition() {
        // A small raster is enough to choose a start: the spawn comes from the point bundle, not from
        // the map, so this only needs to know which parts are land.
        MapRaster preview = MapRaster.build(world, 512);
        TeleportService chooser = new TeleportService(world, preview);
        TeleportService.Target target = chooser.spawnFor(ai.arena.qatar.world.Points.KIND_CAR);
        if ("Doha".equals(target.label)) {
            target = chooser.spawnFor(ai.arena.qatar.world.Points.KIND_BOAT);
        }
        double bundleEast = target.east;
        double bundleNorth = target.north;
        Gdx.app.log("GameScreen", "Spawn: " + target.label + " " + target);
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

        worldRenderer.render(camera);

        int width = Gdx.graphics.getWidth();
        int height = Gdx.graphics.getHeight();
        hud.begin();
        if (layout.bigMapVisible()) {
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
        } else {
            drawWorldButtons(width, height);
            hud.drawMinimap(layout.minimap(), rig.east + offsetEastToBundle(),
                    rig.north + offsetNorthToBundle(), rig.yawDegrees, 1f);
        }
        drawStatusText(width, height, dt);
        hud.end();
    }

    private void drawWorldButtons(int width, int height) {
        layout.setHighlighted(Id.MODE, rig.mode == CameraRig.Mode.FLY);
        layout.setHighlighted(Id.SPRINT, rig.sprinting);
        layout.setHighlighted(Id.LOCO_HUMAN, controller.loco() == Loco.HUMAN);
        layout.setHighlighted(Id.LOCO_CAR, controller.loco() == Loco.CAR);
        layout.setHighlighted(Id.LOCO_PLANE, controller.loco() == Loco.PLANE);
        layout.setHighlighted(Id.LOCO_HELI, controller.loco() == Loco.HELICOPTER);
        layout.setHighlighted(Id.LOCO_BOAT, controller.loco() == Loco.BOAT);
        boolean flying = controller.loco().flying;
        layout.setEnabled(Id.MODE, !flying);
        layout.setEnabled(Id.ALT_UP, flying);
        layout.setEnabled(Id.ALT_DOWN, flying);
        layout.button(Id.MODE).label = rig.mode == CameraRig.Mode.FLY ? "FLY" : "WALK";
        for (Button button : layout.buttons()) {
            hud.drawButton(button, layout.isHighlighted(button.id));
        }
        // The move stick, drawn where the thumb actually is.
        if (movePointer >= 0) {
            hud.drawStick(moveAnchor.x, moveAnchor.y, moveCurrent.x, moveCurrent.y, moveRadius);
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
            statusLine = String.format(java.util.Locale.ROOT,
                    "%s | %.0f, %.0f | %s | %.0f fps",
                    controller.describe(),
                    rig.east + offsetEastToBundle(), rig.north + offsetNorthToBundle(),
                    worldRenderer.describe(), Gdx.graphics.getFramesPerSecond());
        }
        float margin = height * 0.02f;
        hud.drawStatus(statusLine, margin, height - margin);
        if (layout.bigMapVisible()) {
            hud.drawStatus("Tap a place on the map to travel there  |  اضغط على الخريطة للانتقال",
                    margin, height - margin - hud.lineHeight() * 1.2f);
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
            moveAnchor.set(x, y, 0);
            moveCurrent.set(x, y, 0);
            return true;
        }
        if (layout.minimap().contains(x, y)) {
            openBigMap();
            return true;
        }
        if (lookPointer < 0) {
            lookPointer = pointer;
            lookLast.set(x, y, 0);
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
            float dx = x - lookLast.x;
            float dy = y - lookLast.y;
            rig.look(dx * lookSensitivity, dy * lookSensitivity);
            lookLast.set(x, y, 0);
            return true;
        }
        if (pointer == movePointer) {
            moveCurrent.set(x, y, 0);
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
        float dx = moveCurrent.x - moveAnchor.x;
        float dy = moveCurrent.y - moveAnchor.y;
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
                rig.setMode(rig.mode == CameraRig.Mode.FLY ? CameraRig.Mode.WALK : CameraRig.Mode.FLY);
                break;
            case SPRINT:
                controller.sprinting = pressed;
                break;
            case LOCO_HUMAN:
                controller.setLoco(Loco.HUMAN);
                break;
            case LOCO_CAR:
                controller.setLoco(Loco.CAR);
                break;
            case LOCO_PLANE:
                controller.setLoco(Loco.PLANE);
                controller.adjustAltitude(220.0);
                break;
            case LOCO_HELI:
                controller.setLoco(Loco.HELICOPTER);
                controller.adjustAltitude(120.0);
                break;
            case LOCO_BOAT:
                controller.setLoco(Loco.BOAT);
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
        layout.setBigMapVisible(true);
        mapView.centreOnWorld(rig.east + offsetEastToBundle(), rig.north + offsetNorthToBundle());
        message = "";
    }

    // ---------------------------------------------------------------- big map gestures

    private boolean mapTouchDown(float x, float y, int pointer) {
        Button hit = layout.hit(x, y);
        if (hit != null) {
            mapPressedButton = true;
            mapPointerA = pointer;
            fire(hit.id, true);
            return true;
        }
        if (mapPointerA < 0) {
            mapPointerA = pointer;
            mapDragLast.set(x, y, 0);
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
            float dx = x - mapDragLast.x;
            float dy = y - mapDragLast.y;
            mapTravel += Math.abs(dx) + Math.abs(dy);
            mapView.pan(dx, dy);
            mapDragLast.set(x, y, 0);
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
        double pixelX = mapView.uAt(screenX) * raster.width;
        double pixelY = mapView.vAt(screenY) * raster.height;
        TeleportService.Target target = teleport.fromMapPixel(pixelX, pixelY);
        double localEast = target.east - offsetEastToBundle();
        double localNorth = target.north - offsetNorthToBundle();
        rig.placeAt(localEast, localNorth);
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
