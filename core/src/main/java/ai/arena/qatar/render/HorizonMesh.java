package ai.arena.qatar.render;

import ai.arena.qatar.world.MaterialGrid;
import ai.arena.qatar.world.TerrainGrid;
import ai.arena.qatar.world.WorldData;

/**
 * The far horizon: the country's own ground, coarsened, carried out beyond the streaming window.
 *
 * <p>Until now the world ended where the streamed chunks ended. The fog was pulled in to meet them, so
 * the edge of the loaded world was never a hard line -- it was worse than that. It was a wall of haze
 * about five kilometres out, and past it there was nothing at all: stand on the Corniche and Doha had no
 * far side, fly a helicopter up and the peninsula was a disc with a grey rim, and the sea simply stopped.
 * No amount of detail inside the window fixes a horizon made of fog, because the problem is that there is
 * nothing to look at beyond it.
 *
 * <p>There is, though, and it was already in the bundle: {@code terrain_l0}, the same national heightfield
 * at 300 m a cell. Geometry that coarse costs nothing to draw -- the whole ring below is about twelve
 * thousand triangles, one draw call, less than the buildings of a single Doha block -- and at five to
 * eleven kilometres away a 300 m cell is a couple of pixels tall. What it buys is the shape of the
 * country: the bay curving round to the east, the dunes to the west, the ridge of the north, and a sea
 * that goes all the way to the sky instead of stopping at a line.
 *
 * <h2>Why rings</h2>
 *
 * <p>A second square grid of 300 m cells out to eleven kilometres would be four thousand cells on a side,
 * most of them behind the player. A polar ring mesh is one triangle per few hundred metres in the
 * direction you are looking and none behind you, and its resolution follows distance for free: the rings
 * grow geometrically ({@code u^1.6}), so the near ones are tens of metres apart and the far ones are three
 * hundred, which is exactly where the detail is worth having. Each ring's angle grid divides the previous
 * one evenly, so no stitching is needed at the resolution change.
 *
 * <h2>What it is not</h2>
 *
 * <p>No buildings, no roads, no palms: the detail radius is 2.5 km and none of it would survive the
 * distance anyway. The horizon is land and sea, and nothing else.
 *
 * <p>Everything here is plain geometry -- no GL, no libGDX -- so the mesh can be asserted exactly, cell
 * by cell, in CI. {@code HorizonMeshTest} does that: no holes at the coast, no sea drawn as sand, up-facing
 * winding, and a vertex count that stays inside a 16-bit index buffer.
 */
public final class HorizonMesh {

    /**
     * The inner radius of the ring, in metres.
     *
     * <p>Deliberately inside {@code GameWorldRenderer.MIN_DRAW_RADIUS_M}: the chunk window shrinks when
     * the phone is struggling, and a horizon that started at the *biggest* window would leave a ring of
     * nothing all the way round the player on a slow device -- a visible hole in the world, worse than the
     * fog it replaced. It is also inside the smallest window's own radius, so the streams always overlap:
     * where both draw, the chunks are nearer and win the depth test, and the horizon quietly fills the
     * gaps between them.
     */
    public static final double NEAR_M = 2400.0;

    /**
     * The outer radius of the ring, in metres: inside the camera's far plane (12 km), and where the fog
     * reaches its full strength, so the rim of the mesh dissolves into the sky's own haze.
     */
    public static final double FAR_M = 11400.0;

    /** Number of rings from {@link #NEAR_M} to {@link #FAR_M}. Spacing grows geometrically. */
    public static final int RINGS = 48;

    /** Angular divisions around the ring. 128 at 5 km is about 250 m of arc. */
    public static final int DIVISIONS = 128;

    /** How far the player may travel before the ring is rebuilt around them, in metres. */
    public static final double REBUILD_MOVE_M = 1500.0;

    /** Terrain is lifted a little on land, exactly as the detailed mesher lifts it, and for the same
     * reason: buildings and roads sit on the surface, not inside it. */
    private static final float LAND_LIFT = 0.02f;
    /** The sea plane, above the land lift and below nothing: the shoreline stays crisp. */
    private static final float WATER_LIFT = 0.05f;

    /** Surface coordinates are metres per tile, matching {@code TerrainMesher}: grain and waves scale
     * with the world rather than with the mesh. */
    private static final double TEXTURE_TILE_M = 25.0;

    /** How the ring spacing grows with radius. 1.0 would be linear; this crowds the near rings. */
    private static final double RADIAL_BIAS = 1.6;

    private HorizonMesh() {
    }

    /** The built ring: two meshes, because the sea is drawn double sided and the land is not. */
    public static final class Horizon {

        /** Dry ground, one quad per land cell, in metres relative to the build centre. */
        public final MeshData land;
        /** The sea, a plane at sea level over every water cell, in the same relative metres. */
        public final MeshData water;
        /** The centre the vertices are relative to, in bundle metres (the frame the compiled data is in). */
        public final double centreEast;
        public final double centreNorth;
        public final double nearM;
        public final double farM;
        public final int landQuads;
        public final int waterQuads;
        /** True when the vertex budget stopped the build: the far rim is missing, and it is reported. */
        public final boolean truncated;

        Horizon(MeshData land, MeshData water, double centreEast, double centreNorth, double nearM,
                double farM, int landQuads, int waterQuads, boolean truncated) {
            this.land = land;
            this.water = water;
            this.centreEast = centreEast;
            this.centreNorth = centreNorth;
            this.nearM = nearM;
            this.farM = farM;
            this.landQuads = landQuads;
            this.waterQuads = waterQuads;
            this.truncated = truncated;
        }

        public int vertices() {
            return land.vertexCount() + water.vertexCount();
        }

        public int triangles() {
            return land.triangleCount() + water.triangleCount();
        }

        public int memoryBytes() {
            return land.memoryBytes() + water.memoryBytes();
        }

        /** One line for the diagnostics panel. */
        public String describe() {
            return "horizon " + (int) (farM / 1000.0) + " km, " + (landQuads + waterQuads) + " cells ("
                    + landQuads + " land, " + waterQuads + " water), " + triangles() + " triangles"
                    + (truncated ? ", TRUNCATED" : "");
        }
    }

    /** Builds the ring around a point given in bundle metres -- the frame the compiled grids use. */
    public static Horizon build(WorldData world, double centreEast, double centreNorth) {
        return build(world, centreEast, centreNorth, NEAR_M, FAR_M, RINGS, DIVISIONS);
    }

    /**
     * Builds the ring with the radii and resolution asked for. The parameters are open so a test can build
     * a small one and check every cell of it, and so a phone that cannot afford the full ring can be given
     * a smaller one without a code change.
     */
    static Horizon build(WorldData world, double centreEast, double centreNorth, double nearM,
                         double farM, int rings, int divisions) {
        // The coarse national grid is the whole point of this mesh; a bundle without one falls back to the
        // detailed grid, which gives a finer ring for the same triangles.
        TerrainGrid terrain = world.terrainL0 != null ? world.terrainL0 : world.terrainL1;
        MaterialGrid materials = world.materials;

        MeshData land = new MeshData(4096, 8192);
        MeshData water = new MeshData(1024, 2048);

        double inner = Math.max(1.0, nearM);
        double outer = Math.max(inner + 1.0, farM);
        int ringCount = Math.max(1, rings);
        int divisionsPerRing = Math.max(8, divisions);
        double step = Math.PI * 2.0 / divisionsPerRing;

        int landQuads = 0;
        int waterQuads = 0;
        boolean truncated = false;

        // Rings are laid out from the inside out; each quad is emitted on its own four vertices, which is
        // what lets a cell be classified -- land or sea -- without a hole or an overlap at the shore.
        for (int ring = 0; ring < ringCount && !truncated; ring++) {
            double r0 = radiusAt(ring, ringCount, inner, outer);
            double r1 = radiusAt(ring + 1, ringCount, inner, outer);
            for (int div = 0; div < divisionsPerRing; div++) {
                double a0 = div * step;
                double a1 = a0 + step;

                // The four corners of the cell, each the terrain under it.
                Corner sw = corner(world, terrain, materials, centreEast, centreNorth, r0, a0);
                Corner se = corner(world, terrain, materials, centreEast, centreNorth, r0, a1);
                Corner ne = corner(world, terrain, materials, centreEast, centreNorth, r1, a1);
                Corner nw = corner(world, terrain, materials, centreEast, centreNorth, r1, a0);
                // A cell belongs to the land when at least half of it is land. Majority rather than "all
                // four" is the whole reason there is no hole along the coast: a cell straddling the
                // shoreline has to be drawn by one side or the other, and drawn by both it would z-fight.
                boolean landCell = countLand(sw, se, ne, nw) >= 2;

                MeshData target = landCell ? land : water;
                if (!target.hasRoom(4)) {
                    // A full mesh is a stopped build, not a hole in it: better an honest rim that ends
                    // early, with the flag reported on the panel, than quads missing at random.
                    truncated = true;
                    break;
                }
                emit(target, sw, se, ne, nw, landCell);
                if (landCell) {
                    landQuads++;
                } else {
                    waterQuads++;
                }
            }
        }

        land.compact();
        water.compact();
        return new Horizon(land, water, centreEast, centreNorth, inner, outer, landQuads, waterQuads,
                truncated);
    }

    /** Radius of ring {@code index} of {@code rings}, biased so the near rings are the tight ones. */
    private static double radiusAt(int index, int rings, double inner, double outer) {
        double u = Math.max(0.0, Math.min(1.0, index / (double) rings));
        return inner + (outer - inner) * Math.pow(u, RADIAL_BIAS);
    }

    private static int countLand(Corner sw, Corner se, Corner ne, Corner nw) {
        int count = 0;
        if (sw.land) {
            count++;
        }
        if (se.land) {
            count++;
        }
        if (ne.land) {
            count++;
        }
        if (nw.land) {
            count++;
        }
        return count;
    }

    /**
     * Adds one cell: four vertices in tangential-then-outward order, which is the winding that faces up
     * in this world's frame (x east, y up, z north -- the same order {@code TerrainMesher} uses).
     */
    private static void emit(MeshData mesh, Corner sw, Corner se, Corner ne, Corner nw, boolean land) {
        float[] normal = normalOf(sw, se, ne);
        int a = add(mesh, sw, normal, land);
        int b = add(mesh, se, normal, land);
        int c = add(mesh, ne, normal, land);
        int d = add(mesh, nw, normal, land);
        mesh.addQuad(a, b, c, d);
    }

    /**
     * The normal of one cell, from its own corners: the ring is drawn flat-shaded on purpose -- a smooth
     * normal spread across a 250 m cell would look like cloth -- and computing it from the geometry that
     * was actually emitted cannot disagree with that geometry.
     *
     * <p>Taken from the first of the cell's two triangles, whose winding (south-west, south-east, north-east)
     * is the up-facing one in this frame.
     */
    private static float[] normalOf(Corner sw, Corner se, Corner ne) {
        double ax = se.east - sw.east;
        double ay = se.y() - sw.y();
        double az = se.north - sw.north;
        double bx = ne.east - se.east;
        double by = ne.y() - se.y();
        double bz = ne.north - se.north;
        double nx = ay * bz - az * by;
        double ny = az * bx - ax * bz;
        double nz = ax * by - ay * bx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-6) {
            return new float[]{0f, 1f, 0f};
        }
        return new float[]{(float) (nx / length), (float) (ny / length), (float) (nz / length)};
    }

    /** Adds one vertex of a cell, as land or as the sea's flat plane. */
    private static int add(MeshData mesh, Corner corner, float[] normal, boolean land) {
        float u = (float) (corner.east / TEXTURE_TILE_M);
        float v = (float) (corner.north / TEXTURE_TILE_M);
        if (land) {
            // A corner over water is the waterline rather than the seabed, and a corner over land is the
            // ground. Both layers read the same rule, so an edge shared between the beach and the sea is one
            // edge at one height -- see TerrainMesher, where the same rule closed a two-hundred-metre band
            // of nothing that ran along every coast of the streamed world.
            float y = corner.y();
            int material = corner.material;
            if (material <= 0) {
                material = corner.land ? 5 : 16;
            }
            return mesh.addVertexMaterial(corner.east, y, corner.north, normal[0], normal[1], normal[2],
                    TerrainMesher.SURFACE_LAND, u, v, 1.0f, material);
        }
        // Sea: the same shallow/open split the detailed mesher paints, so the shelf reads as a shelf all
        // the way out to the horizon. Its height is the shared rule's, not a constant: a sea cell that
        // reaches a corner on land rises to the shore, which is how the two layers stay joined.
        float tint = corner.material == 15 ? 0.35f : 0.8f;
        return mesh.addVertex(corner.east, corner.y(), corner.north, 0f, 1f, 0f,
                TerrainMesher.SURFACE_WATER, u, v, tint);
    }

    /** One sampled point of the national heightfield, relative to the ring's centre. */
    private static final class Corner {
        final float east;
        final float north;
        final double height;
        final boolean land;
        final int material;

        Corner(float east, float north, double height, boolean land, int material) {
            this.east = east;
            this.north = north;
            this.height = height;
            this.land = land;
            this.material = material;
        }

        /**
         * The height both layers draw this sample at: the ground where the sample is land, the sea surface
         * where it is water. One rule, one height, so a beach quad and the sea quad beside it share an edge
         * instead of leaving a step between them that the sky shows through.
         */
        float y() {
            return land ? (float) height + LAND_LIFT : WATER_LIFT;
        }
    }

    private static Corner corner(WorldData world, TerrainGrid terrain, MaterialGrid materials,
                                 double centreEast, double centreNorth, double radius, double angle) {
        double east = centreEast + Math.cos(angle) * radius;
        double north = centreNorth + Math.sin(angle) * radius;
        double globalEast = world.globalEast(east);
        double globalNorth = world.globalNorth(north);
        return new Corner((float) Math.cos(angle) * (float) radius, (float) Math.sin(angle) * (float) radius,
                terrain.heightBilinear(globalEast, globalNorth),
                terrain.landBilinear(globalEast, globalNorth),
                materials == null ? 0 : materials.idAt(globalEast, globalNorth));
    }
}
