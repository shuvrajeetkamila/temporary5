package ai.arena.qatar.render;

/**
 * CPU-side triangle soup produced by the meshers, ready to be uploaded to one GPU mesh.
 *
 * <p>This class deliberately depends on nothing but the JDK: all the geometry generation (terrain
 * grids, building extrusions, road ribbons) can therefore be unit tested in CI without a GL context,
 * which is the only way to prove that "the whole country becomes 3D" before it is ever drawn.
 *
 * <p>Vertex layout, matching the shader's attributes:
 * <pre>
 *   position : 3 x float   local metres
 *   normal   : 3 x float   unit
 *   aux      : 4 x ubyte   surfaceType, u, v, tint (all normalised 0..1)
 * </pre>
 * A chunk with more than {@link #MAX_VERTICES} vertices must be split into several parts, because the
 * index buffer uses unsigned shorts: 65,536 vertices is a hard limit of the format, not a preference.
 */
public final class MeshData {

    public static final int FLOATS_PER_VERTEX = 10;
    public static final int MAX_VERTICES = 65_000;

    private float[] vertices;
    private short[] indices;
    private int vertexCount;
    private int indexCount;

    private float minX = Float.MAX_VALUE;
    private float minY = Float.MAX_VALUE;
    private float minZ = Float.MAX_VALUE;
    private float maxX = -Float.MAX_VALUE;
    private float maxY = -Float.MAX_VALUE;
    private float maxZ = -Float.MAX_VALUE;

    public MeshData() {
        this(4096, 8192);
    }

    public MeshData(int vertexCapacity, int indexCapacity) {
        vertices = new float[Math.max(1, vertexCapacity) * FLOATS_PER_VERTEX];
        indices = new short[Math.max(3, indexCapacity)];
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int indexCount() {
        return indexCount;
    }

    public int triangleCount() {
        return indexCount / 3;
    }

    public boolean isEmpty() {
        return indexCount == 0;
    }

    public boolean isFull() {
        return vertexCount + 4 > MAX_VERTICES;
    }

    public float[] vertices() {
        return vertices;
    }

    public short[] indices() {
        return indices;
    }

    /** Adds a vertex and returns its index. */
    public int addVertex(float x, float y, float z, float nx, float ny, float nz, float surfaceType,
                         float u, float v, float tint) {
        if (vertexCount >= MAX_VERTICES) {
            throw new IllegalStateException("mesh is full: split the chunk before adding more vertices");
        }
        int offset = vertexCount * FLOATS_PER_VERTEX;
        if (offset + FLOATS_PER_VERTEX > vertices.length) {
            float[] grown = new float[Math.max(vertices.length * 2, offset + FLOATS_PER_VERTEX)];
            System.arraycopy(vertices, 0, grown, 0, offset);
            vertices = grown;
        }
        vertices[offset] = x;
        vertices[offset + 1] = y;
        vertices[offset + 2] = z;
        vertices[offset + 3] = nx;
        vertices[offset + 4] = ny;
        vertices[offset + 5] = nz;
        vertices[offset + 6] = surfaceType;
        vertices[offset + 7] = u;
        vertices[offset + 8] = v;
        vertices[offset + 9] = tint;
        vertexCount++;

        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);
        return vertexCount - 1;
    }

    public void addTriangle(int a, int b, int c) {
        if (indexCount + 3 > indices.length) {
            short[] grown = new short[Math.max(indices.length * 2, indexCount + 3)];
            System.arraycopy(indices, 0, grown, 0, indexCount);
            indices = grown;
        }
        indices[indexCount++] = (short) a;
        indices[indexCount++] = (short) b;
        indices[indexCount++] = (short) c;
    }

    /** Two triangles for a quad given in counter-clockwise order (a, b, c, d). */
    public void addQuad(int a, int b, int c, int d) {
        addTriangle(a, b, c);
        addTriangle(a, c, d);
    }

    public float minY() {
        return vertexCount == 0 ? 0 : minY;
    }

    public float maxY() {
        return vertexCount == 0 ? 0 : maxY;
    }

    public float[] bounds() {
        return new float[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** Statistics line for the loading screen and the tests. */
    public String describe() {
        return vertexCount + " vertices, " + triangleCount() + " triangles";
    }
}
