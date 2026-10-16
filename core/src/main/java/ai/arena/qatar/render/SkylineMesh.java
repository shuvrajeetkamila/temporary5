package ai.arena.qatar.render;

import ai.arena.qatar.world.Buildings;
import ai.arena.qatar.world.FeatureIndex;
import ai.arena.qatar.world.TerrainGrid;
import ai.arena.qatar.world.WorldData;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The city beyond the detail radius: every building's own footprint, extruded as one coarse block.
 *
 * <p>The streamed chunks carry real OSM geometry -- walls, gabled roofs, domes, arcades, street-level
 * doorways -- out to {@code ChunkMeshCache.DETAIL_RADIUS_M}, 2.5 km. Past that line the country is drawn
 * as ground alone, so from a helicopter at 300 m the city simply stops: there is sand where West Bay is,
 * and the far half of Doha is a dune. The horizon ring filled in the country beyond six kilometres, which
 * left exactly this band -- two and a half to eight kilometres -- as the one place the world was still
 * empty of people.
 *
 * <p>This is not a second detail system. It is the same bundle, read the same way, with everything that
 * costs a vertex thrown away: the footprint ring is replaced by its bounding rectangle, the roof shape by
 * a flat lid, the levelled base by the terrain under the block's centre, and the whole outline by four
 * walls and a top. Five quads instead of the ten-per-ring-vertex the detailed mesher spends, and at three
 * to eight kilometres a 30 m footprint is a handful of pixels wide -- which is the point: this is a
 * skyline, not a city. Nothing here is meant to be walked into; the player who drives out to one of these
 * blocks finds the real thing streamed in underneath it.
 *
 * <h2>Why a grid of cells decides what survives</h2>
 *
 * <p>Doha alone has around a hundred thousand mapped buildings inside eight kilometres, and one block
 * each would be two million vertices for a shape the eye reads as a texture. So the ring is divided into
 * {@link #CELL_M} cells and each cell keeps the <em>tallest</em> building whose bounds centre falls in
 * it: a hundred thousand buildings become a few thousand silhouettes, the towers that stand above the
 * rooftops keep their own block, and a suburb of villas contributes one. Tallest rather than first,
 * because the raster is dense and the towers are what a skyline is.
 *
 * <h2>What it must not do</h2>
 *
 * <p>Three rules, all of them testable without a GPU and all of them asserted in {@code SkylineMeshTest}:
 * no block may be drawn nearer than {@link #NEAR_M}, where the real geometry is (a coarse box on top of a
 * detailed building is an invisible z-fight at best and a lid over a courtyard at worst); none may reach
 * beyond {@link #FAR_M}, where the horizon ring and the fog take over; and every face must be wound so
 * that it is visible from outside -- the fault that cost the eighth install every roof in the country, and
 * the reason the winding here is computed from the geometry rather than remembered.
 *
 * <p>Plain arithmetic over the compiled country: no GL, no libGDX, so the whole mesh can be asserted in a
 * JVM. See {@code SkylineMeshTest}.
 */
public final class SkylineMesh {

    /**
     * The inner radius of the skyline, in metres: inside the detail radius, so the streamed chunks own the
     * boundary and there is a band where both are drawn rather than a ring of nothing between them.
     */
    public static final double NEAR_M = 2200.0;

    /** The outer radius, in metres: inside the streamed terrain (6 km) and well inside the fog's end. */
    public static final double FAR_M = 8000.0;

    /**
     * The thinning grid, in metres: at most one block per cell.
     *
     * <p>Ninety metres is about the width of the blocks the eye resolves at three kilometres on a phone
     * screen -- narrower and the blocks overlap into a single dark mass, wider and a tower is missing from
     * the skyline it belongs to.
     */
    public static final double CELL_M = 90.0;

    /**
     * The most blocks one mesh will draw. Twenty vertices each, so this is 52,000 of the 65,000 a 16-bit
     * index buffer can name, with room to spare for the roof lids.
     */
    public static final int MAX_BLOCKS = 2600;

    /** Gates, sheds and car ports: below this the silhouette is under a pixel and not worth a block. */
    public static final double MIN_HEIGHT_M = 3.0;

    /** How far the player may travel before the skyline is built again around them. */
    public static final double REBUILD_MOVE_M = 1500.0;

    /** Block footprints are grown by this much, so a thin building cannot fall between two pixels. */
    public static final float GROW_M = 1.5f;

    /**
     * How far a block's base is sunk below the ground under it.
     *
     * <p>The detailed mesher levels a building on the highest terrain under its whole footprint; a block
     * this coarse takes the terrain at its centre and can meet a slope, and a base that floats a
     * centimetre off the sand shows the sky through the gap all the way round.
     */
    public static final float SINK_M = 0.8f;

    /** Vertices and triangles one block costs: four walls of four vertices, plus a four-vertex lid. */
    public static final int VERTICES_PER_BLOCK = 20;
    public static final int TRIANGLES_PER_BLOCK = 10;

    /** The built skyline: one mesh, and where it was built from. */
    public static final class Skyline {

        /** Every block's walls and roofs, in metres relative to the build centre. */
        public final MeshData blocks;
        /** The centre the vertices are relative to, in bundle metres (the frame the compiled data is in). */
        public final double centreEast;
        public final double centreNorth;
        public final double nearM;
        public final double farM;
        /** How many blocks were emitted. */
        public final int blockCount;
        /** How many rings were looked at: the panel's own honesty about how big the search was. */
        public final int considered;
        /** True when the vertex budget stopped the build: the far blocks are missing, and it is reported. */
        public final boolean truncated;

        Skyline(MeshData blocks, double centreEast, double centreNorth, double nearM, double farM,
                int blockCount, int considered, boolean truncated) {
            this.blocks = blocks;
            this.centreEast = centreEast;
            this.centreNorth = centreNorth;
            this.nearM = nearM;
            this.farM = farM;
            this.blockCount = blockCount;
            this.considered = considered;
            this.truncated = truncated;
        }

        public int vertices() {
            return blocks.vertexCount();
        }

        public int triangles() {
            return blocks.triangleCount();
        }

        public int memoryBytes() {
            return blocks.memoryBytes();
        }

        /** One line for the diagnostics panel. */
        public String describe() {
            return "skyline " + (int) (farM / 1000.0) + " km, " + blockCount + " blocks of "
                    + considered + ", " + triangles() + " triangles"
                    + (truncated ? ", TRUNCATED" : "");
        }
    }

    /** Builds the skyline around a point given in bundle metres -- the frame the compiled data is in. */
    public static Skyline build(WorldData world, double centreEast, double centreNorth) {
        return build(world, centreEast, centreNorth, NEAR_M, FAR_M, CELL_M, MAX_BLOCKS);
    }

    /**
     * Builds the skyline with the radii and resolution asked for. The parameters are open so a test can
     * build a small one over the synthetic world and check every block of it, and so a phone that cannot
     * afford the full ring can be given a smaller one without a code change.
     */
    static Skyline build(WorldData world, double centreEast, double centreNorth, double nearM, double farM,
                         double cellM, int maxBlocks) {
        // A bundle without buildings is not an error: the skyline is simply not drawn, and the world looks
        // exactly as it did before this class existed.
        Buildings buildings = world == null ? null : world.buildings;
        FeatureIndex index = world == null ? null : world.buildingIndex;
        return buildFrom(world, buildings, index, centreEast, centreNorth, nearM, farM, cellM, maxBlocks);
    }

    /**
     * Builds the skyline from the buildings and the index it is given rather than from the bundle's own.
     *
     * <p>The seam exists for the tests, and it earns its place: the truncation rule -- a mesh that runs out
     * of its 65 000 vertices stops cleanly and says so -- cannot be reached through the synthetic country,
     * which has five buildings in it, and a rule no test can reach is a rule nobody has checked.
     */
    static Skyline buildFrom(WorldData world, Buildings buildings, FeatureIndex index, double centreEast,
                            double centreNorth, double nearM, double farM, double cellM, int maxBlocks) {
        MeshData mesh = new MeshData(4096, 8192);
        if (buildings == null || index == null) {
            return new Skyline(mesh, centreEast, centreNorth, nearM, farM, 0, 0, false);
        }
        int[] chosen = select(buildings, index, centreEast, centreNorth, nearM, farM, cellM, maxBlocks);
        // The coarse grid if the bundle has one: blocks stand on the ground the horizon draws, so the two
        // agree where they overlap. The detailed grid is the fallback, not the preference.
        TerrainGrid terrain = world.terrainL0 != null ? world.terrainL0 : world.terrainL1;

        int emitted = 0;
        boolean truncated = false;
        for (int ring : chosen) {
            if (!mesh.hasRoom(VERTICES_PER_BLOCK)) {
                // A full mesh is a stopped build, not a hole in it: the horizon and the chunks both take
                // this line, and the panel says TRUNCATED rather than quietly drawing a shorter skyline.
                truncated = true;
                break;
            }
            if (addBlock(mesh, world, terrain, buildings, ring)) {
                emitted++;
            }
        }
        mesh.compact();
        return new Skyline(mesh, centreEast, centreNorth, nearM, farM, emitted, chosen.length, truncated);
    }

    /**
     * The buildings the skyline will draw, nearest ring first: the tallest in each {@code cellM} cell of
     * the band between {@code nearM} and {@code farM}, capped at {@code maxBlocks} and ordered so that a
     * cap keeps the tallest.
     *
     * <p>Package-visible and free of {@code WorldData} so a test can drive it with a handful of hand-made
     * rings and check the rules that matter: the band, the tallest-wins cell, the cap, and the fact that
     * the same country always yields the same list.
     */
    static int[] select(Buildings buildings, FeatureIndex index, double centreEast, double centreNorth,
                        double nearM, double farM, double cellM, int maxBlocks) {
        if (buildings == null || index == null || buildings.ringCount == 0 || maxBlocks <= 0) {
            return new int[0];
        }
        double cell = Math.max(1.0, cellM);
        double near2 = nearM * nearM;
        double far2 = farM * farM;

        // One entry per cell, holding the tallest building whose bounds centre fell in it. A cell that is
        // empty is not in the map, which is how the count stays proportional to the built-up area rather
        // than to the whole ring.
        Map<Long, Integer> best = new HashMap<>();
        index.query(centreEast - farM, centreNorth - farM, centreEast + farM, centreNorth + farM, ring -> {
            if (ring < 0 || ring >= buildings.ringCount) {
                return true;
            }
            if (buildings.heightM[ring] < MIN_HEIGHT_M) {
                return true;
            }
            // The bounding rectangle's centre, not the ring's centroid: it is one lookup instead of a loop
            // over the ring, and for a thinned skyline the two differ by a metre or two.
            double boxEast = (buildings.bounds[ring * 4] + buildings.bounds[ring * 4 + 2]) * 0.5;
            double boxNorth = (buildings.bounds[ring * 4 + 1] + buildings.bounds[ring * 4 + 3]) * 0.5;
            double dx = boxEast - centreEast;
            double dz = boxNorth - centreNorth;
            double distance2 = dx * dx + dz * dz;
            if (distance2 < near2 || distance2 > far2) {
                return true;
            }
            long key = cellKey(boxEast, boxNorth, cell);
            Integer previous = best.get(key);
            if (previous == null || taller(buildings, ring, previous)) {
                best.put(key, ring);
            }
            return true;
        });

        Integer[] rings = best.values().toArray(new Integer[0]);
        // Tallest first, and a tie broken by ring index so that the same country always gives the same
        // list in the same order: the mesh has to be reproducible to be assertable, and a HashMap's own
        // order is not something to build on.
        Arrays.sort(rings, (a, b) -> {
            int byHeight = Float.compare(buildings.heightM[b], buildings.heightM[a]);
            return byHeight != 0 ? byHeight : Integer.compare(a, b);
        });
        int count = Math.min(rings.length, maxBlocks);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = rings[i];
        }
        return out;
    }

    /** True when {@code ring} is a better block for a cell than {@code previous}: taller, or first built. */
    private static boolean taller(Buildings buildings, int ring, int previous) {
        float height = buildings.heightM[ring];
        float other = buildings.heightM[previous];
        return height > other || (height == other && ring < previous);
    }

    /** A cell's own key, from the coordinates rather than from an array index: exact, and negative-safe. */
    private static long cellKey(double east, double north, double cell) {
        long cx = (long) Math.floor(east / cell);
        long cz = (long) Math.floor(north / cell);
        return (cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /**
     * Adds one building as a block: four walls and a lid, or nothing when the footprint is a sliver.
     *
     * <p>Returns true when anything was emitted, so the caller's block count is the number of blocks the
     * mesh actually carries rather than the number it asked for.
     */
    private static boolean addBlock(MeshData mesh, WorldData world, TerrainGrid terrain, Buildings buildings,
                                    int ring) {
        double minEast = buildings.bounds[ring * 4];
        double minNorth = buildings.bounds[ring * 4 + 1];
        double maxEast = buildings.bounds[ring * 4 + 2];
        double maxNorth = buildings.bounds[ring * 4 + 3];
        if (maxEast - minEast < 0.5 || maxNorth - minNorth < 0.5) {
            // A degenerate or hairline ring: extruding it would be two coincident quads, which is a
            // z-fighting flicker rather than a building.
            return false;
        }
        minEast -= GROW_M;
        minNorth -= GROW_M;
        maxEast += GROW_M;
        maxNorth += GROW_M;

        float baseY = ground(terrain, world, (minEast + maxEast) * 0.5, (minNorth + maxNorth) * 0.5)
                - SINK_M;
        // The top is the ground the block stands on plus the building's own height, and the sink is added
        // back: a block whose base is buried is not a block that is 0.8 m shorter.
        float topY = baseY + SINK_M + buildings.heightM[ring];
        float tint = buildings.isLandmark(ring) ? BuildingMesher.SHADE_LANDMARK
                : BuildingMesher.SHADE_PLAIN;

        float west = (float) minEast;
        float east = (float) maxEast;
        float south = (float) minNorth;
        float north = (float) maxNorth;
        // Each wall is given the direction the box's own outside faces, and the winding is derived from
        // that rather than written down: see addWall.
        addWall(mesh, west, south, east, south, baseY, topY, 0f, -1f, tint);
        addWall(mesh, east, south, east, north, baseY, topY, 1f, 0f, tint);
        addWall(mesh, east, north, west, north, baseY, topY, 0f, 1f, tint);
        addWall(mesh, west, north, west, south, baseY, topY, -1f, 0f, tint);
        addRoof(mesh, west, south, east, north, topY, tint);
        return true;
    }

    /**
     * One wall, as a quad from the base to the top, with the normal the box's outside faces.
     *
     * <p>Which way to wind the corners is worked out here rather than remembered, because this frame is
     * left-handed in the (east, north) plane ({@code x * z = -y}): the hand rule that works on a diagram
     * is the opposite of the one that works here, and one sign error turns every wall of the skyline
     * inside out -- invisible from outside, exactly like the roofs the eighth install reported as
     * "buildings that look like walls". See {@code BuildingMesher.facesUp}, which states the same trap for
     * the roofs and is held by the same kind of test.
     */
    private static void addWall(MeshData mesh, float ax, float az, float bx, float bz, float baseY,
                                float topY, float nx, float nz, float tint) {
        // With the second edge of the quad going straight up, the triangle (a, b, b-up) faces
        // (-dz, 0, dx) -- ninety degrees to the left of a->b. If that is not the outward normal asked
        // for, the corner order is the other way round.
        double dx = bx - ax;
        double dz = bz - az;
        boolean firstOrderFacesOut = nx * -dz + nz * dx > 0.0;
        float height = topY - baseY;
        int a = mesh.addVertex(ax, baseY, az, nx, 0f, nz, BuildingMesher.SURFACE_WALL, 0f, 0f, tint);
        int b = mesh.addVertex(bx, baseY, bz, nx, 0f, nz, BuildingMesher.SURFACE_WALL, 1f, 0f, tint);
        int c = mesh.addVertex(bx, topY, bz, nx, 0f, nz, BuildingMesher.SURFACE_WALL, 1f, height, tint);
        int d = mesh.addVertex(ax, topY, az, nx, 0f, nz, BuildingMesher.SURFACE_WALL, 0f, height, tint);
        if (firstOrderFacesOut) {
            mesh.addQuad(a, b, c, d);
        } else {
            mesh.addQuad(b, a, d, c);
        }
    }

    /**
     * The lid: a flat roof over the whole block, wound the way an up-facing quad is wound in this frame
     * (clockwise in {east, north} -- see {@link BuildingMesher#facesUp}).
     *
     * <p>Coordinates in the auxiliary pair are metres from the block's centre, which is what the shader
     * tiles roof plant and panel seams on; a 0..1 range would stretch a different amount on every block.
     */
    private static void addRoof(MeshData mesh, float west, float south, float east, float north, float topY,
                                float tint) {
        float centreEast = (west + east) * 0.5f;
        float centreNorth = (south + north) * 0.5f;
        int a = mesh.addVertex(west, topY, south, 0f, 1f, 0f, BuildingMesher.SURFACE_ROOF,
                west - centreEast, south - centreNorth, tint);
        int b = mesh.addVertex(east, topY, south, 0f, 1f, 0f, BuildingMesher.SURFACE_ROOF,
                east - centreEast, south - centreNorth, tint);
        int c = mesh.addVertex(east, topY, north, 0f, 1f, 0f, BuildingMesher.SURFACE_ROOF,
                east - centreEast, north - centreNorth, tint);
        int d = mesh.addVertex(west, topY, north, 0f, 1f, 0f, BuildingMesher.SURFACE_ROOF,
                west - centreEast, north - centreNorth, tint);
        mesh.addQuad(a, d, c, b);
    }

    /** The ground under a block: the coarse national grid when the bundle has one, else the detailed. */
    private static float ground(TerrainGrid terrain, WorldData world, double east, double north) {
        if (terrain == null) {
            return 0f;
        }
        return (float) terrain.heightBilinear(world.globalEast(east), world.globalNorth(north));
    }

    private SkylineMesh() {
    }
}
