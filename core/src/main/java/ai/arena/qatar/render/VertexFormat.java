package ai.arena.qatar.render;

import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;

/**
 * The single vertex layout every mesh in the game uses, and the stride that goes with it.
 *
 * <p>Eleven floats per vertex, exactly the floats {@link MeshData} writes: position (3), normal (3),
 * auxiliary data (4: surface type, u, v, tint) and the compiled material id (1).
 *
 * <p>These attributes used to declare the auxiliary data as four packed bytes while every mesher wrote
 * four floats, so the stride the driver was told (28 bytes) was not the stride the data was written in
 * (40). A chunk would have failed to upload, or worse, been read as noise. The layout now says what the
 * meshers do, and {@code VertexLayoutTest} asserts the two agree.
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

    /** Attribute names, in the order the shaders declare them. */
    public static final String ATTRIBUTE_POSITION = "a_position";
    public static final String ATTRIBUTE_NORMAL = "a_normal";
    public static final String ATTRIBUTE_AUX = "a_aux";
    public static final String ATTRIBUTE_MATERIAL = "a_material";

    private VertexFormat() {
    }

    public static VertexAttributes attributes() {
        return new VertexAttributes(
                new VertexAttribute(VertexAttributes.Usage.Position, 3, ATTRIBUTE_POSITION),
                new VertexAttribute(VertexAttributes.Usage.Normal, 3, ATTRIBUTE_NORMAL),
                new VertexAttribute(VertexAttributes.Usage.Generic, 4, ATTRIBUTE_AUX),
                new VertexAttribute(VertexAttributes.Usage.Generic, 1, ATTRIBUTE_MATERIAL));
    }

    /** Vertex positions for a mesh of {@code vertexCount} vertices. */
    public static int floatCount(int vertexCount) {
        return vertexCount * FLOATS_PER_VERTEX;
    }
}
