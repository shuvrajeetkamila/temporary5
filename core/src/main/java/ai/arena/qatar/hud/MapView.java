package ai.arena.qatar.hud;

/**
 * The view state of the full map: what part of the country is on screen, at what zoom.
 *
 * <p>Kept as pure arithmetic on the {@link MapRaster} rather than as GL state, because "where does a tap
 * land" is the one thing about a map that must be exactly right: the player taps a street and expects to
 * appear on that street. Zooming keeps the point under the fingers fixed and the view is clamped to the
 * data, so panning cannot scroll off into blank space.
 *
 * <p>At {@code zoom == 1} the whole country is on screen, letterboxed into whatever rectangle the HUD
 * gives it, so map pixels stay square on any screen. Zoom then scales that rectangle about the centre.
 */
public final class MapView {

    public static final float MIN_ZOOM = 1.0f;
    public static final float MAX_ZOOM = 40.0f;

    private final MapRaster raster;

    private float zoom = MIN_ZOOM;
    private float centreU = 0.5f;
    private float centreV = 0.5f;

    /** The rectangle on screen the map may occupy. */
    private float rectX;
    private float rectY;
    private float rectW;
    private float rectH;
    /** The letterboxed rectangle the whole country occupies at zoom 1. */
    private float fitW;
    private float fitH;

    public MapView(MapRaster raster) {
        this.raster = raster;
    }

    public void setViewport(float x, float y, float w, float h) {
        this.rectX = x;
        this.rectY = y;
        this.rectW = w;
        this.rectH = h;
        float rasterAspect = raster.width / (float) raster.height;
        if (w / h > rasterAspect) {
            fitH = h;
            fitW = h * rasterAspect;
        } else {
            fitW = w;
            fitH = w / rasterAspect;
        }
        clampCentre();
    }

    public float zoom() {
        return zoom;
    }

    public float centreU() {
        return centreU;
    }

    public float centreV() {
        return centreV;
    }

    /** Fraction of the country visible on each axis at the current zoom. */
    public float visibleU() {
        return 1.0f / zoom;
    }

    public float visibleV() {
        return 1.0f / zoom;
    }

    public float fitWidth() {
        return fitW;
    }

    public float fitHeight() {
        return fitH;
    }

    /** The raster fraction at the left edge of the visible window. */
    public float leftU() {
        return centreU - visibleU() * 0.5f;
    }

    public float bottomV() {
        return centreV - visibleV() * 0.5f;
    }

    /** Screen pixel -> raster fraction. Outside the drawn rectangle the result is simply off-window. */
    public float uAt(float px) {
        if (fitW <= 0) {
            return centreU;
        }
        return centreU + (px - rectX - rectW * 0.5f) / (fitW * zoom);
    }

    public float vAt(float py) {
        if (fitH <= 0) {
            return centreV;
        }
        // Screen y grows upwards and so does north, so the mapping is direct.
        return centreV + (py - rectY - rectH * 0.5f) / (fitH * zoom);
    }

    /** World position (bundle metres) under a screen pixel. */
    public double eastAt(float px) {
        return raster.eastAt(uAt(px));
    }

    public double northAt(float py) {
        return raster.northAt(vAt(py));
    }

    /** Zooms by a factor, keeping the raster point under (anchorX, anchorY) where it is. */
    public void zoomAt(float anchorX, float anchorY, float factor) {
        float anchorU = uAt(anchorX);
        float anchorV = vAt(anchorY);
        float newZoom = clamp(zoom * factor, MIN_ZOOM, MAX_ZOOM);
        if (newZoom == zoom) {
            return;
        }
        zoom = newZoom;
        // Put the anchor back where it was: centre = anchor - offset / scale.
        centreU = anchorU - (anchorX - rectX - rectW * 0.5f) / (fitW * zoom);
        centreV = anchorV - (anchorY - rectY - rectH * 0.5f) / (fitH * zoom);
        clampCentre();
    }

    /** Pans by a screen-pixel delta, used for finger drags. */
    public void pan(float dxPixels, float dyPixels) {
        if (fitW <= 0 || fitH <= 0) {
            return;
        }
        centreU -= dxPixels / (fitW * zoom);
        centreV -= dyPixels / (fitH * zoom);
        clampCentre();
    }

    public void setZoom(float value) {
        zoom = clamp(value, MIN_ZOOM, MAX_ZOOM);
        clampCentre();
    }

    /** Moves the view so a world position is centred, e.g. "find me". */
    public void centreOnWorld(double east, double north) {
        centreU = (float) raster.u(east, north);
        centreV = (float) raster.v(east, north);
        clampCentre();
    }

    /** Keeps the visible window inside the raster: no panning into empty space. */
    private void clampCentre() {
        float half = visibleU() * 0.5f;
        centreU = clamp(centreU, Math.min(half, 0.5f), Math.max(1f - half, 0.5f));
        centreV = clamp(centreV, Math.min(half, 0.5f), Math.max(1f - half, 0.5f));
    }

    private static float clamp(float value, float low, float high) {
        return value < low ? low : (value > high ? high : value);
    }
}
