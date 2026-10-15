package ai.arena.qatar.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;

/**
 * The GL state 2D drawing needs, set by the 2D code itself.
 *
 * <h2>Why this exists</h2>
 *
 * <p>On a phone, the seventh install showed the country but no controls at all: no buttons, no minimap,
 * no status line, and no on-screen diagnostics panel. Nothing had thrown. The world renderer enables
 * {@code GL_CULL_FACE} and {@code GL_DEPTH_TEST} at the start of every frame and never turns them off
 * again, and libGDX's {@code SpriteBatch.begin()} -- read from its own source, 1.12.1 -- sets
 * {@code glDepthMask(false)} and nothing else. It does not disable depth testing and it does not disable
 * back-face culling.
 *
 * <p>That matters because of what SpriteBatch emits: for {@code draw(texture, x, y, w, h)} the four
 * corners are (x,y), (x,y+h), (x+w,y+h), (x+w,y), indexed 0-1-2, 2-3-0. Both triangles are clockwise in
 * a y-up coordinate system, and the world renderer leaves {@code glFrontFace(GL_CCW)} behind. Clockwise
 * triangles with counter-clockwise fronts are back faces, so with culling on, <b>every quad the HUD
 * draws is discarded before it reaches the screen</b>. The HUD was drawn perfectly, by correct code,
 * and the GPU threw it away.
 *
 * <p>It is invisible on a desktop, where nothing leaves culling enabled, and invisible to every test in
 * this repository that does not own a GPU -- which is all of them. So the contract is made explicit
 * here, and every 2D entry point calls it: the HUD, the diagnostics panel, and the world renderer on its
 * way out of a frame (see {@code WorldRenderer.endFrame}) so that anything drawn after it starts from a
 * sane state whether or not it thought about this at all.
 *
 * <p>The calls are idempotent and cheap -- three glDisable calls -- and they are the difference between
 * a game and a landscape with nothing on it.
 */
public final class GlState2D {

    private GlState2D() {
    }

    /**
     * Prepares the GL state for 2D drawing: no depth test, no back-face culling, depth writes off.
     *
     * <p>Blending is deliberately not touched: {@code SpriteBatch.flush()} enables it with the blend
     * function the batch was constructed with, so a batch that wants blending gets it either way, and a
     * batch constructed with blending disabled is not fought here.
     */
    public static void beginUi() {
        GL20 gl = Gdx.gl;
        if (gl == null) {
            return;                                   // no context at all: nothing to set, nothing to draw
        }
        gl.glDisable(GL20.GL_DEPTH_TEST);
        gl.glDisable(GL20.GL_CULL_FACE);
        gl.glDepthMask(false);
    }
}
