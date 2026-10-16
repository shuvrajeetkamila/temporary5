package ai.arena.qatar.render;

import com.badlogic.gdx.math.Frustum;

/**
 * Decides whether a chunk is worth drawing at all, before any GL call is made for it.
 *
 * <p>Two tests, both cheap, both about the tenth install's "the phone gets hot after a few seconds":
 *
 * <ol>
 *   <li><b>Distance.</b> Beyond the draw radius the fog has already eaten the world, so those triangles
 *       are paid for and never seen. The radius itself is adaptive (see
 *       {@code GameWorldRenderer.DRAW_RADIUS_M}); this class only applies it.</li>
 *   <li><b>The camera's frustum.</b> A chunk behind the camera, or off to the side, is still transformed
 *       and set up by the GPU before the clipper throws it away. With a 72 degree vertical field of view
 *       on a 20:9 phone screen, most of a 6 km disc lies outside the frustum: skipping those chunks is
 *       the largest saving available to this renderer, and it costs one sphere test each.</li>
 * </ol>
 *
 * <p>Pure arithmetic on libGDX's own {@link Frustum}, so it is testable without a device: the maths is
 * the part that can be wrong, and it is the part a phone cannot explain to us afterwards.
 *
 * <p>The sphere is deliberately generous. A chunk wrongly drawn costs some pixels; a chunk wrongly
 * culled is a hole in the world that the player can walk into and through. So the sphere encloses the
 * whole chunk box -- 500 m across, from below sea level to above the tallest tower in the country.
 */
public final class ChunkCulling {

    /** Top of a chunk's bounding box: above the tallest building in the compiled country. */
    public static final double MAX_CHUNK_TOP_M = 340.0;
    /** Bottom of a chunk's bounding box: below the lowest ground in the compiled country. */
    public static final double MIN_CHUNK_BOTTOM_M = -40.0;

    private static final double HALF_SPAN_M = ChunkMesher.CHUNK_M * 0.5;
    private static final double CENTRE_Y_M = (MAX_CHUNK_TOP_M + MIN_CHUNK_BOTTOM_M) * 0.5;
    private static final double HALF_HEIGHT_M = (MAX_CHUNK_TOP_M - MIN_CHUNK_BOTTOM_M) * 0.5;

    /**
     * The bounding sphere of a chunk box, in metres: half the diagonal of the 500 m square footprint,
     * and half the box's height. Exact for the box's corners, which is what "conservative" means here.
     *
     * <p>{@code Math.sqrt(2.0)} and not a named root: {@code java.lang.Math} has exactly two constants,
     * {@code E} and {@code PI}. {@code SQRT2} is JavaScript's, and javac says so in the middle of a CI
     * run -- see the gate beside this file, which now reads every {@code Math.} member against the list
     * the JDK actually declares.
     */
    public static final double CHUNK_SPHERE_RADIUS_M = Math.hypot(HALF_SPAN_M * Math.sqrt(2.0),
            HALF_HEIGHT_M);

    /** The height the chunk sphere is centred on, in metres. */
    public static double sphereCentreY() {
        return CENTRE_Y_M;
    }

    /** True when a chunk-sphere at this local position could be inside the camera frustum. */
    public static boolean inFrustum(Frustum frustum, double localEastM, double localNorthM,
                                   double radiusM) {
        if (frustum == null) {
            return true;                       // no frustum to test against: draw it rather than lose it
        }
        return frustum.sphereInFrustum((float) localEastM, (float) CENTRE_Y_M, (float) localNorthM,
                (float) radiusM);
    }

    /**
     * True when a chunk centre is within the draw radius of the focus.
     *
     * <p>Half a chunk of slack, because a chunk whose centre is just outside the radius still has most of
     * its area inside it: cutting at the centre would show a square hole at the edge of the fog.
     */
    public static boolean withinDrawRadius(double centreEastM, double centreNorthM, double focusEastM,
                                          double focusNorthM, double drawRadiusM) {
        double dx = centreEastM - focusEastM;
        double dz = centreNorthM - focusNorthM;
        double limit = drawRadiusM + HALF_SPAN_M;
        return dx * dx + dz * dz <= limit * limit;
    }

    private ChunkCulling() {
    }
}
