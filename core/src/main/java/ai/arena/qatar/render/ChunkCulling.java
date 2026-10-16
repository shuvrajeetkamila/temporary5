package ai.arena.qatar.render;

import com.badlogic.gdx.math.Matrix4;

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
 * <p>The frustum planes are taken from the camera's own projection-view matrix by the arithmetic in
 * {@link #planesOf}. That is not a preference: {@code Frustum.update} calls {@code Matrix4.prj}, which
 * is native, so a {@code PerspectiveCamera} cannot even be constructed on a JVM -- a culling test that
 * reads {@code camera.frustum} throws {@code UnsatisfiedLinkError} before it asserts anything, which is
 * how this class was first written and how it first failed. The maths is the part that can be wrong and
 * the part a phone cannot explain afterwards, so the maths is what is tested.
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

    /** How many floats a set of six planes takes: four each, (a, b, c, d). */
    public static final int PLANE_FLOATS = 24;

    /**
     * Writes a camera's six frustum planes into {@code out} -- four floats a plane, in the order left,
     * right, bottom, top, near, far -- from its projection-view matrix.
     *
     * <p>Each plane is one addition or subtraction of two rows of the matrix, which is the textbook
     * extraction: a point is inside the frustum when it is on the inner side of all six. The planes are
     * normalised here, once, so {@link #sphereInPlanes} needs no divide per chunk.
     *
     * <p>libGDX stores a matrix column major in {@code val}: {@code val[col * 4 + row]}, so row 0 is
     * {@code val[0], val[4], val[8], val[12]}.
     */
    public static void planesOf(Matrix4 projectionView, float[] out) {
        if (projectionView == null || out == null || out.length < PLANE_FLOATS) {
            return;
        }
        float[] m = projectionView.val;
        plane(out, 0, m[3] + m[0], m[7] + m[4], m[11] + m[8], m[15] + m[12]);
        plane(out, 4, m[3] - m[0], m[7] - m[4], m[11] - m[8], m[15] - m[12]);
        plane(out, 8, m[3] + m[1], m[7] + m[5], m[11] + m[9], m[15] + m[13]);
        plane(out, 12, m[3] - m[1], m[7] - m[5], m[11] - m[9], m[15] - m[13]);
        plane(out, 16, m[3] + m[2], m[7] + m[6], m[11] + m[10], m[15] + m[14]);
        plane(out, 20, m[3] - m[2], m[7] - m[6], m[11] - m[10], m[15] - m[14]);
    }

    /** One normalised plane; a degenerate one is written as "culls nothing" rather than as zeroes. */
    private static void plane(float[] out, int at, float a, float b, float c, float d) {
        double length = Math.sqrt((double) a * a + (double) b * b + (double) c * c);
        if (length < 1e-9) {
            out[at] = 0f;
            out[at + 1] = 0f;
            out[at + 2] = 0f;
            out[at + 3] = Float.MAX_VALUE;
            return;
        }
        out[at] = (float) (a / length);
        out[at + 1] = (float) (b / length);
        out[at + 2] = (float) (c / length);
        out[at + 3] = (float) (d / length);
    }

    /**
     * True when a chunk-sphere at this local position could be inside the camera's frustum.
     *
     * <p>A null plane set means there is no camera to test against: draw the chunk rather than lose it.
     * The sphere's centre is the middle of the chunk's box, {@link #MAX_CHUNK_TOP_M} to
     * {@link #MIN_CHUNK_BOTTOM_M}, which is why the caller only passes the two ground coordinates.
     */
    public static boolean sphereInPlanes(float[] planes, double localEastM, double localNorthM,
                                        double radiusM) {
        if (planes == null || planes.length < PLANE_FLOATS) {
            return true;
        }
        for (int at = 0; at < PLANE_FLOATS; at += 4) {
            double distance = planes[at] * localEastM + planes[at + 1] * CENTRE_Y_M
                    + planes[at + 2] * localNorthM + planes[at + 3];
            if (distance < -radiusM) {
                return false;
            }
        }
        return true;
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
