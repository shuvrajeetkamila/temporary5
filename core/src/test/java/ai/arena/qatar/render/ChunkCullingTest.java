package ai.arena.qatar.render;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Frustum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The culling arithmetic, tested where it can be tested: a camera's frustum is pure matrices, so the
 * tenth install's hot phone can be checked on a laptop.
 *
 * <p>What went wrong was not a wrong sign -- it was that nothing was culled at all. The renderer drew
 * every resident chunk in every direction, always: at a 6 km radius that is more than twice the geometry
 * the camera can see, and every vertex of it was transformed before the clipper discarded it. These tests
 * hold the two rules that fix it: a chunk behind the camera is skipped, and a chunk beyond the draw
 * radius is skipped -- while a chunk in front of the camera never is, because a wrongly culled chunk is
 * a hole in the world and a wrongly drawn one is a few wasted pixels.
 */
final class ChunkCullingTest {

    /** A camera looking north from the origin: +z is north, which is where libGDX's default is. */
    private static Frustum lookingNorth() {
        PerspectiveCamera camera = new PerspectiveCamera(72f, 2400f, 1080f);
        camera.position.set(0f, 120f, 0f);
        camera.lookAt(0f, 120f, 100f);
        camera.near = 0.5f;
        camera.far = 12000f;
        camera.update();
        return camera.frustum;
    }

    @Test
    @DisplayName("a chunk in front of the camera is drawn, and the same chunk behind it is not")
    void frustumCullsBehindTheCamera() {
        Frustum frustum = lookingNorth();
        double radius = ChunkCulling.CHUNK_SPHERE_RADIUS_M;
        assertTrue(ChunkCulling.inFrustum(frustum, 0.0, 500.0, radius),
                "a chunk straight ahead was culled");
        assertTrue(ChunkCulling.inFrustum(frustum, 0.0, 3000.0, radius),
                "a distant chunk straight ahead was culled");
        assertFalse(ChunkCulling.inFrustum(frustum, 0.0, -800.0, radius),
                "a chunk behind the camera was drawn: this is the whole saving, and it is the one the "
                        + "renderer was not taking");
        assertFalse(ChunkCulling.inFrustum(frustum, 4000.0, 300.0, radius),
                "a chunk far off to the right was drawn");
    }

    @Test
    @DisplayName("no frustum means draw it: culling may never be the reason the world is missing")
    void noFrustumDrawsEverything() {
        assertTrue(ChunkCulling.inFrustum(null, 12345.0, -9999.0, 400.0),
                "without a frustum to test against, a chunk was culled");
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
}
