package ai.arena.qatar.render;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;

/**
 * The single vertex layout every mesh in the game uses, and the stride that goes with it.
 *
 * <p>Ten floats per vertex, matching {@link MeshData}: position (3), normal (3), and four packed bytes
 * of auxiliary data (surface type, u, v, tint). Packing the surface type and texture coordinates into
 * four bytes instead of four floats cuts a vertex from 40 bytes to 28, which matters when a city chunk
 * carries tens of thousands of them.
 */
public final class VertexFormat {

    /** Surface type codes carried in the first auxiliary byte; the shader branches on these. */
    public static final int SURFACE_LAND = 0;
    public static final int SURFACE_WATER = 1;
    public static final int SURFACE_WALL = 2;
    public static final int SURFACE_ROOF = 3;
    public static final int SURFACE_ROAD = 4;
    public static final int SURFACE_MARKING = 5;

    public static final int FLOATS_PER_VERTEX = MeshData.FLOATS_PER_VERTEX;
    public static final int BYTES_PER_VERTEX = FLOATS_PER_VERTEX * 4;

    private VertexFormat() {
    }

    public static VertexAttributes attributes() {
        return new VertexAttributes(
                new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.Normal, 3, "a_normal"),
                // Four unsigned bytes, normalised to 0..1 in the shader: (type, u, v, tint).
                new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, GL20.GL_UNSIGNED_BYTE, true,
                        "a_aux"));
    }

    /** Vertex positions for a mesh of {@code vertexCount} vertices. */
    public static int floatCount(int vertexCount) {
        return vertexCount * FLOATS_PER_VERTEX;
    }
}
