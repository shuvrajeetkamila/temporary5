package ai.arena.qatar.render;

import com.badlogic.gdx.Gdx;

/**
 * GLES capability reporting. The renderer targets OpenGL ES 3.0 (instancing, GLSL ES 3.0, integer
 * texture formats) and degrades explicitly rather than silently if a device reports less.
 */
public final class GlVersion {

    private GlVersion() {
    }

    public static void logContextInfo() {
        try {
            String vendor = Gdx.gl.glGetString(com.badlogic.gdx.graphics.GL20.GL_VENDOR);
            String renderer = Gdx.gl.glGetString(com.badlogic.gdx.graphics.GL20.GL_RENDERER);
            String version = Gdx.gl.glGetString(com.badlogic.gdx.graphics.GL20.GL_VERSION);
            Gdx.app.log("GlVersion", "vendor=" + vendor + " renderer=" + renderer + " version=" + version);
            if (Gdx.gl30 != null) {
                Gdx.app.log("GlVersion", "GLES 3.0 context available (shading language "
                        + Gdx.gl30.glGetString(com.badlogic.gdx.graphics.GL30.GL_SHADING_LANGUAGE_VERSION) + ")");
            } else {
                Gdx.app.error("GlVersion", "GLES 3.0 context NOT available -- falling back to GLES 2.0 paths");
            }
        } catch (Throwable t) {
            Gdx.app.error("GlVersion", "Unable to query GL strings: " + t);
        }
    }

    public static String describe() {
        try {
            String renderer = Gdx.gl.glGetString(com.badlogic.gdx.graphics.GL20.GL_RENDERER);
            String version = Gdx.gl.glGetString(com.badlogic.gdx.graphics.GL20.GL_VERSION);
            return (renderer == null ? "unknown" : renderer) + " / " + (version == null ? "unknown" : version);
        } catch (Throwable t) {
            return "unavailable";
        }
    }
}
