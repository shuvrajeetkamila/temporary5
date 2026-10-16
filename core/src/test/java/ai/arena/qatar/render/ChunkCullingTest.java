package ai.arena.qatar.render;

import com.badlogic.gdx.math.Matrix4;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The culling arithmetic, tested where it can be tested.
 *
 * <p>What went wrong on the tenth install was not a wrong sign -- it was that nothing was culled at all.
 * The renderer drew every resident chunk in every direction, always: at a 6 km radius that is more than
 * twice the geometry the camera can show, and every vertex of it was transformed before the clipper
 * discarded it. These tests hold the two rules that fix it: a chunk behind the camera is skipped, and a
 * chunk beyond the draw radius is skipped -- while a chunk in front of the camera never is, because a
 * wrongly culled chunk is a hole in the world and a wrongly drawn one is a few wasted pixels.
 *
 * <p>The frustum planes come from a matrix this test builds itself, and that is not a short cut.
 * {@code Frustum.update} calls {@code Matrix4.prj}, which is native, so constructing libGDX's own
 * {@code PerspectiveCamera} on a JVM throws {@code UnsatisfiedLinkError} -- this test was written that
 * way first and failed that way, which is the one failure a test can never give a phone. The planes are
 * extracted by {@link ChunkCulling#planesOf}, which is the same call the renderer makes on the real
 * camera, so the test and the renderer cannot disagree about the arithmetic.
 */
final class ChunkCullingTest {

    /** 72 degrees, a phone's aspect, and the renderer's own near and far planes. */
    private static final double FOV_DEGREES = 72.0;
    private static final float ASPECT = 2400f / 1080f;
    private static final float NEAR_M = 0.5f;
    private static final float FAR_M = 12_000f;

    /**
     * A camera at the origin looking north, as six planes.
     *
     * <p>Column major, as libGDX stores it: {@code m[col * 4 + row]}. The view part is a half turn about
     * Y -- world (x, y, z) to camera (-x, y, -z), determinant +1, no mirroring -- which is the view
     * matrix of a camera whose view direction is world +z, the direction this world calls north.
     */
    private static float[] lookingNorth() {
        double f = 1.0 / Math.tan(Math.toRadians(FOV_DEGREES * 0.5));
        Matrix4 projectionView = new Matrix4();
        float[] m = projectionView.val;
        m[0] = (float) (-f / ASPECT);
        m[5] = (float) f;
        m[10] = (FAR_M + NEAR_M) / (FAR_M - NEAR_M);
        m[11] = 1f;
        m[14] = -2f * FAR_M * NEAR_M / (FAR_M - NEAR_M);
        float[] planes = new float[ChunkCulling.PLANE_FLOATS];
        ChunkCulling.planesOf(projectionView, planes);
        return planes;
    }

    @Test
    @DisplayName("a chunk in front of the camera is drawn, and the same chunk behind it is not")
    void frustumCullsBehindTheCamera() {
        float[] planes = lookingNorth();
        double radius = ChunkCulling.CHUNK_SPHERE_RADIUS_M;
        assertTrue(ChunkCulling.sphereInPlanes(planes, 0.0, 500.0, radius),
                "a chunk straight ahead was culled: this is the hole in the world");
        assertTrue(ChunkCulling.sphereInPlanes(planes, 0.0, 3000.0, radius),
                "a distant chunk straight ahead was culled");
        assertTrue(ChunkCulling.sphereInPlanes(planes, 0.0, 0.0, radius),
                "the chunk the camera is standing on was culled: the ground under the player would go");
        assertFalse(ChunkCulling.sphereInPlanes(planes, 0.0, -800.0, radius),
                "a chunk behind the camera was drawn: this is the whole saving, and it is the one the "
                        + "renderer was not taking");
        assertFalse(ChunkCulling.sphereInPlanes(planes, 4000.0, 300.0, radius),
                "a chunk far off to the right was drawn");
        assertFalse(ChunkCulling.sphereInPlanes(planes, 0.0, 40_000.0, radius),
                "a chunk beyond the far plane was drawn");
    }

    @Test
    @DisplayName("no planes means draw it: culling may never be the reason the world is missing")
    void noPlanesDrawsEverything() {
        assertTrue(ChunkCulling.sphereInPlanes(null, 12345.0, -9999.0, 400.0),
                "without a camera to test against, a chunk was culled");
        assertTrue(ChunkCulling.sphereInPlanes(new float[0], 12345.0, -9999.0, 400.0),
                "an empty plane set culled a chunk instead of drawing it");
        // And an all-zero matrix describes no frustum at all: every plane of it has a normal of length
        // zero, which would cull every chunk in the country if it were taken literally. The failure mode
        // of a mistake here is a world that is missing -- a hole the player walks through -- so a
        // degenerate plane is written as one that culls nothing.
        Matrix4 empty = new Matrix4();
        java.util.Arrays.fill(empty.val, 0f);
        float[] degenerate = new float[ChunkCulling.PLANE_FLOATS];
        ChunkCulling.planesOf(empty, degenerate);
        assertTrue(ChunkCulling.sphereInPlanes(degenerate, 900.0, -4000.0, 400.0),
                "a matrix with no projection in it culled the world away");
    }

    @Test
    @DisplayName("the draw radius cuts the horizon, with half a chunk of slack")
    void drawRadiusCutsTheHorizon() {
        double radius = 4000.0;
        assertTrue(ChunkCulling.withinDrawRadius(0.0, 3500.0, 0.0, 0.0, radius),
                "a chunk inside the radius was culled");
        assertTrue(ChunkCulling.withinDrawRadius(0.0, 4200.0, 0.0, 0.0, radius),
                "a chunk whose centre is just past the radius was culled: half of it is still inside, "
                        + "and cutting there shows a square hole at the edge of the fog");
        assertFalse(ChunkCulling.withinDrawRadius(0.0, 5000.0, 0.0, 0.0, radius),
                "a chunk beyond the radius and its slack was drawn");
    }

    @Test
    @DisplayName("the bounding sphere encloses the whole chunk box, corners and all")
    void sphereCoversTheBox() {
        // The corner of a chunk box is the point a sphere around its centre has to reach: half the
        // diagonal of the 500 m footprint, and half the height of the box.
        double halfSpan = ChunkMesher.CHUNK_M * 0.5;
        double halfHeight = (ChunkCulling.MAX_CHUNK_TOP_M - ChunkCulling.MIN_CHUNK_BOTTOM_M) * 0.5;
        double corner = Math.sqrt(2.0 * halfSpan * halfSpan + halfHeight * halfHeight);
        assertTrue(ChunkCulling.CHUNK_SPHERE_RADIUS_M >= corner - 1e-6,
                "the sphere is smaller than the chunk's own corner: it would cull visible geometry");
        assertTrue(ChunkCulling.sphereCentreY() > 0.0
                        && ChunkCulling.sphereCentreY() < ChunkCulling.MAX_CHUNK_TOP_M,
                "the sphere is centred nowhere near the chunk box");
    }

    @Test
    @DisplayName("the planes are normalised, so the sphere test is a comparison and not a division")
    void planesAreNormalised() {
        float[] planes = lookingNorth();
        for (int at = 0; at < ChunkCulling.PLANE_FLOATS; at += 4) {
            double length = Math.sqrt((double) planes[at] * planes[at]
                    + (double) planes[at + 1] * planes[at + 1]
                    + (double) planes[at + 2] * planes[at + 2]);
            assertTrue(Math.abs(length - 1.0) < 1e-4,
                    "plane " + (at / 4) + " has a normal of length " + length
                            + ": the radius would not be a distance");
        }
    }
}
