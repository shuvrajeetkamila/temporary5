package ai.arena.qatar.render;

/**
 * CPU-side triangle soup produced by the meshers, ready to be uploaded to one GPU mesh.
 *
 * <p>Deliberately plain: nothing here is libGDX, so the geometry generation -- terrain grids, building
 * extrusions, road ribbons -- can be asserted exactly in CI with no GL context at all.
 *
 * <p>Vertex layout, matching the shader's attributes:
 * <pre>
 *   position : 3 x float   local metres
 *   normal   : 3 x float   unit
 *   aux      : 4 x float   surfaceType, u, v, tint
 *   material : 1 x float   compiled material id, 0 when the surface has none
 * </pre>
 * Eleven floats, and the GPU layout must agree with that exactly: the vertex stride comes from
 * {@link VertexFormat}, so a mismatch here would feed the driver a different story about where each
 * vertex starts. {@code VertexLayoutTest} holds the two together.
 *
 * <p>A mesh holds at most {@link #MAX_VERTICES} vertices, because the index buffer uses unsigned
 * shorts: 65,536 is a hard limit of the format, not a preference. A chunk that needs more is split into
 * parts -- and the way that limit is respected matters more than the limit itself. The third install of
 * this game closed immediately, and the reason was here: the meshers asked {@link #isFull()} once
 * <em>before</em> a whole primitive (a building of forty vertices, a road of three hundred) and then
 * added it, so a primitive could start on a mesh with four vertices left and run off the end. That
 * threw, the exception escaped the render loop, and the GL thread -- and the app -- died with it. The
 * rule now is that a caller asks {@link #hasRoom(int)} for the primitive it is about to add, and the
 * throw below is a backstop that should be unreachable.
 */
public final class MeshData {

    public static final int FLOATS_PER_VERTEX = 11;
    public static final int MAX_VERTICES = 65_000;

    /**
     * What a fresh mesh allocates before it grows.
     *
     * <p>This was 4096 vertices -- 180 KB -- and every chunk allocated three of them, so four hundred
     * resident chunks held a quarter of a gigabyte of mostly empty arrays. A phone kills an app that
     * does that, without an exception and without a stack trace. Small by default, grown by doubling,
     * and trimmed to size by {@link #compact()} as soon as the mesher is done with it.
     */
    private static final int DEFAULT_VERTEX_CAPACITY = 1024;
    private static final int DEFAULT_INDEX_CAPACITY = 2048;

    private static final float[] NO_VERTICES = new float[0];
    private static final short[] NO_INDICES = new short[0];

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
        this(DEFAULT_VERTEX_CAPACITY, DEFAULT_INDEX_CAPACITY);
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

    /** True when a four-vertex quad no longer fits: the conservative question the meshers used to ask. */
    public boolean isFull() {
        return !hasRoom(4);
    }

    /**
     * Whether {@code vertices} more vertices fit.
     *
     * <p>This is the question a mesher must ask, and it must ask it for the primitive it is about to
     * add rather than for a generic quad: a building wall is four vertices per ring edge, a road ribbon
     * is four per centre-line point plus its markings. Asking the wrong question is what killed the
     * third build.
     */
    public boolean hasRoom(int vertices) {
        return vertices >= 0 && vertexCount + vertices <= MAX_VERTICES;
    }

    /** How many more vertices still fit. */
    public int remainingVertices() {
        return MAX_VERTICES - vertexCount;
    }

    /**
     * Trims the backing arrays to exactly what was written.
     *
     * <p>A mesh that grew to 65,536 vertices while producing 900 of them should not keep the array it
     * grew into: the resident chunk cache holds tens of meshes, and each one's unused tail is memory
     * the phone cannot use for anything else.
     */
    public void compact() {
        int used = vertexCount * FLOATS_PER_VERTEX;
        if (vertices.length != used) {
            float[] trimmed = new float[used];
            System.arraycopy(vertices, 0, trimmed, 0, used);
            vertices = trimmed;
        }
        if (indices.length != indexCount) {
            short[] trimmed = new short[indexCount];
            System.arraycopy(indices, 0, trimmed, 0, indexCount);
            indices = trimmed;
        }
    }

    /**
     * Drops the vertex data after it has been uploaded to the GPU.
     *
     * <p>The GPU has its own copy and nothing reads the CPU one again, so keeping it means holding
     * every chunk's geometry twice. The counts are the caller's business from here on (the chunk cache
     * records them before calling this), and the mesh must not be uploaded twice.
     */
    public void releaseArrays() {
        vertices = NO_VERTICES;
        indices = NO_INDICES;
        vertexCount = 0;
        indexCount = 0;
    }

    /**
     * Moves every vertex horizontally, keeping the bounds in step.
     *
     * <p>Used to put a chunk into its own frame of reference: the meshers emit bundle-space coordinates
     * -- metres from the compiled grid's origin, which is where the terrain, building and road data live
     * -- and a chunk is drawn by translating it from its own origin. Without this the translation is
     * applied to coordinates that already include it, and every chunk is drawn its own origin distance
     * away from where it belongs: a world that is empty except for whichever squares happen to land near
     * the camera.
     *
     * <p>Doing it here rather than in the meshers keeps one rule instead of four: whatever a mesher
     * writes, a {@code ChunkMesh} is in chunk-local metres, so a vertex coordinate stays under 500 and a
     * float has a couple of centimetres of precision left at the far end of a 259 km country.
     */
    public void translate(float dx, float dz) {
        if (vertexCount == 0 || (dx == 0f && dz == 0f)) {
            return;
        }
        for (int i = 0; i < vertexCount; i++) {
            int at = i * FLOATS_PER_VERTEX;
            vertices[at] += dx;
            vertices[at + 2] += dz;
        }
        minX += dx;
        maxX += dx;
        minZ += dz;
        maxZ += dz;
    }

    /** Bytes of vertex and index data currently held, used to budget the resident chunk cache. */
    public int memoryBytes() {
        return vertices.length * 4 + indices.length * 2;
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
        return addVertexMaterial(x, y, z, nx, ny, nz, surfaceType, u, v, tint, 0f);
    }

    /**
     * Adds a vertex that also names its compiled material.
     *
     * <p>Land needs it: the material id is what says whether this square metre is dune sand, sabkha,
     * tidal mud or mangrove, and a tint cannot say it -- it can only shade a colour somebody else
     * chose. It stays out of the four auxiliary floats so nothing has to share a channel.
     */
    public int addVertexMaterial(float x, float y, float z, float nx, float ny, float nz,
                                 float surfaceType, float u, float v, float tint, float materialId) {
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
        vertices[offset + 10] = materialId;
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
