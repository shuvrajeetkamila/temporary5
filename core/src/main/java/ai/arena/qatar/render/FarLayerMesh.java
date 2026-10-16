package ai.arena.qatar.render;

import ai.arena.qatar.world.Areas;
import ai.arena.qatar.world.FeatureIndex;
import ai.arena.qatar.world.Roads;
import ai.arena.qatar.world.TerrainGrid;
import ai.arena.qatar.world.WorldData;

import java.util.Arrays;
import java.util.HashSet;

/**
 * The country between the detail radius and the horizon: the road network, and the palms and scrub that
 * grow along it.
 *
 * <p>{@link SkylineMesh} filled the band from two and a half kilometres to eight with the shapes of the
 * buildings, which fixed the largest hole in the far world -- a city that stopped dead at the streaming
 * radius -- and left the smaller one next to it. What a person flying over Qatar actually steers by is
 * not the buildings: it is the road, the pale ribbon that runs from one end of the country to the other,
 * and the dark green of a palm farm against the sand. Both of those stopped at the same line the city
 * did, so the Salwa road vanished into the desert at three kilometres and the plantations beside Al
 * Shamal became bare ground two kilometres past the bonnet.
 *
 * <p>This is the same trick as the skyline, applied to the two things the compiled country already
 * carries as lines and rings: {@code roads.bin.gz}, 180,000 OSM ways, and the land-use polygons tagged as
 * vegetation. Neither is invented here and nothing is textured: a road is a ribbon of
 * {@link VertexFormat#SURFACE_ROAD} drawn from the real centre-line at the real width, and vegetation is
 * drawn twice, which is the one design decision in this class worth arguing about.
 *
 * <h2>Two treatments for vegetation, split by distance</h2>
 *
 * <p>The near half of the band, 2.2 to 4 km, gets <b>palms</b>: a trunk and a crown per lattice cell,
 * twenty-five vertices each, written in the same vertex language as the real palms in the streamed chunks
 * -- {@code VegetationMesher}'s surface, its trunk fraction, its u-as-height-fraction convention -- so a
 * plantation thins into the same green as the player flies towards it. Past 4 km that geometry would be a
 * lie about resolution, because a palm crown at five kilometres is under a pixel and the trunk is nothing
 * at all, so the far half gets <b>canopy patches</b>: one flat quad per coarser lattice cell, at the
 * height of the top of the scrub, following the ground under its own four corners. From a helicopter that
 * is what a plantation looks like: a green patch in the right place, with the right shape and the right
 * material, because the compiled material id still comes through and mangrove reads darker than farmland.
 *
 * <p>The lattice is aligned to the compiled grid, not to the player: a cell that held a palm holds the
 * same palm after a rebuild, so the country does not shuffle itself when the player drives in a circle.
 * Only the split between the two treatments moves with the build centre, and it moves a plant at a time
 * between two drawings of the same vegetation.
 *
 * <h2>What it must not do</h2>
 *
 * <p>Same three rules as the skyline, and the same kind of test holds them: nothing inside {@link #NEAR_M}
 * where the real geometry is; nothing past {@link #FAR_M} where the horizon takes over; every face wound
 * so that it is visible from above, which is where a player in a helicopter or a car looks at it from.
 *
 * <p>No GL and no libGDX: plain arithmetic over the same bundle the detailed mesher reads, so the whole
 * layer can be asserted in a JVM. See {@code FarLayerMeshTest}.
 */
public final class FarLayerMesh {

    /** The inner radius, in metres: inside the detail radius, so the boundary has both rather than a gap. */
    public static final double NEAR_M = 2200.0;

    /** The outer radius, in metres, matching the skyline's: past it the horizon ring and the fog own it. */
    public static final double FAR_M = 8000.0;

    /** How far the player may travel before the layer is built again around them. */
    public static final double REBUILD_MOVE_M = 1500.0;

    // ------------------------------------------------------------------ roads

    /**
     * The least important road the layer draws: 7 is {@code residential}.
     *
     * <p>{@code TeleportService} accepts anything down to class 10, because a player may reasonably want
     * to stand on a track; a painting of the network at five kilometres is a different question, and the
     * answer is that service alleys, driveways, tracks and footpaths together outnumber the roads that
     * carry traffic about ten to one. Drawing them would turn every suburb into a grey lattice with no
     * legible way through it -- the same reason a road atlas has primary and secondary roads on it and the
     * driveways of a villa district do not.
     */
    public static final int MAX_ROAD_CLASS = 7;

    /** How far apart a coarse road's vertices are along the way, in metres. */
    public static final double ROAD_STEP_M = 60.0;

    /** Half-width limits for a drawn road, in metres: a lane is not a motorway and a lane is not a path. */
    public static final float ROAD_MIN_HALF_M = 2.5f;
    public static final float ROAD_MAX_HALF_M = 13.0f;

    /**
     * How far the ribbon floats above the ground under it, in metres.
     *
     * <p>Past the detail radius the ground being drawn is the horizon ring's coarse mesh, not the compiled
     * grid this samples, so the two surfaces disagree by whatever the coarse ring's interpolation loses on
     * a slope. Half a metre is under a pixel at these distances and buys that difference -- the alternative
     * is a road that dives into a dune and comes back out.
     */
    public static final float ROAD_LIFT_M = 0.6f;

    /** The most of the budget roads may take: 3,000 segments of about sixty metres each. */
    public static final int MAX_ROAD_VERTICES = 12_000;

    /** A quad per segment: four vertices, two triangles. */
    public static final int ROAD_VERTICES_PER_SEGMENT = 4;
    public static final int ROAD_TRIANGLES_PER_SEGMENT = 2;

    /** Below this a segment is not drawn at all: a quad narrower than the ribbon is a flicker. */
    public static final double ROAD_MIN_SEGMENT_M = 6.0;

    // ------------------------------------------------------------------ palms, 2.2 to 4 km

    /** The distance at which palms stop being drawn as palms and become canopy, in metres. */
    public static final double PALM_BAND_M = 4000.0;

    /** The palm lattice, in metres: at most one palm per cell. */
    public static final double PALM_CELL_M = 90.0;

    /** How far a palm is moved from its lattice point, in metres: a plantation is not a parade ground. */
    public static final double PALM_JITTER_M = 28.0;

    public static final float PALM_MIN_HEIGHT_M = 4.0f;
    public static final float PALM_MAX_HEIGHT_M = 8.5f;
    /** Trunk radius at the base, in metres. */
    public static final float PALM_TRUNK_RADIUS_M = 0.34f;
    /** Crown radius, in metres. A date palm's spread is three to four metres across. */
    public static final float PALM_CROWN_RADIUS_M = 1.9f;
    /** Sides of the crown cone. Eight reads as round at these distances and costs eight triangles. */
    public static final int PALM_SIDES = 8;

    /** The most of the budget palms may take: 1,040 palms. */
    public static final int MAX_PALM_VERTICES = 26_000;

    /** Trunk (four walls of four vertices) and crown (eight sides plus the apex). */
    public static final int PALM_VERTICES = 4 * 4 + PALM_SIDES + 1;
    /** Trunk (four walls of two triangles) and crown (one triangle per side and one for the fan's seam). */
    public static final int PALM_TRIANGLES = 4 * 2 + PALM_SIDES;

    // ------------------------------------------------------------------ canopy, 4 to 8 km

    /** The canopy lattice, in metres: at most one patch per cell. */
    public static final double CANOPY_CELL_M = 170.0;

    /**
     * How far a canopy patch floats above the ground at each of its own corners, in metres, and how much
     * a hash may add to that per patch.
     *
     * <p>The patch follows the ground -- all four corners are sampled -- so the lift only has to clear the
     * interpolation difference against the horizon ring, exactly like the roads' lift, plus enough relief
     * that neighbouring patches do not merge into one sheet.
     */
    public static final float CANOPY_LIFT_M = 1.3f;
    public static final float CANOPY_VARIATION_M = 2.4f;

    /** The most of the budget canopy may take: 4,000 cells. */
    public static final int MAX_CANOPY_VERTICES = 16_000;

    /** A quad: four vertices, two triangles. */
    public static final int CANOPY_VERTICES = 4;
    public static final int CANOPY_TRIANGLES = 2;

    // ------------------------------------------------------------------ the whole layer

    /**
     * The ceiling this class builds to, below {@link MeshData#MAX_VERTICES} so that the three passes can
     * never add up to an index buffer that cannot name its own vertices.
     */
    public static final int MAX_VERTICES = 56_000;

    /** The built layer: one mesh, and where it was built from. */
    public static final class FarLayer {

        /** Roads, palms and canopy patches, in metres relative to the build centre. */
        public final MeshData mesh;
        /** The centre the vertices are relative to, in bundle metres (the frame the compiled data is in). */
        public final double centreEast;
        public final double centreNorth;
        public final double nearM;
        public final double farM;
        /** Road segments drawn, palms drawn, canopy patches drawn. */
        public final int roadSegments;
        public final int palms;
        public final int canopies;
        /** Ways and vegetation rings the search looked at: the panel's own honesty about the work done. */
        public final int consideredWays;
        public final int consideredAreas;
        /** True when a budget stopped that pass: what is missing is the far end of it, and it is reported. */
        public final boolean roadsTruncated;
        public final boolean palmsTruncated;
        public final boolean canopiesTruncated;

        FarLayer(MeshData mesh, double centreEast, double centreNorth, double nearM, double farM,
                 int roadSegments, int palms, int canopies, int consideredWays, int consideredAreas,
                 boolean roadsTruncated, boolean palmsTruncated, boolean canopiesTruncated) {
            this.mesh = mesh;
            this.centreEast = centreEast;
            this.centreNorth = centreNorth;
            this.nearM = nearM;
            this.farM = farM;
            this.roadSegments = roadSegments;
            this.palms = palms;
            this.canopies = canopies;
            this.consideredWays = consideredWays;
            this.consideredAreas = consideredAreas;
            this.roadsTruncated = roadsTruncated;
            this.palmsTruncated = palmsTruncated;
            this.canopiesTruncated = canopiesTruncated;
        }

        public int vertices() {
            return mesh.vertexCount();
        }

        public int triangles() {
            return mesh.triangleCount();
        }

        public int memoryBytes() {
            return mesh.memoryBytes();
        }

        public boolean truncated() {
            return roadsTruncated || palmsTruncated || canopiesTruncated;
        }

        /** One line for the diagnostics panel. */
        public String describe() {
            return "far layer " + (int) (nearM / 1000.0) + "-" + (int) (farM / 1000.0) + " km, "
                    + roadSegments + " road, " + palms + " palm, " + canopies + " canopy, "
                    + (triangles() / 1000) + "k tris"
                    + (truncated() ? ", TRUNCATED" : "");
        }
    }

    /** Builds the layer around a point given in bundle metres -- the frame the compiled data is in. */
    public static FarLayer build(WorldData world, double centreEast, double centreNorth) {
        return build(world, centreEast, centreNorth, NEAR_M, FAR_M, PALM_BAND_M, PALM_CELL_M, CANOPY_CELL_M,
                MAX_VERTICES);
    }

    /**
     * Builds the layer with the radii, lattice spacings and ceiling asked for.
     *
     * <p>Open so that a test can build a small one over the synthetic world -- 1.6 km square, which the
     * real band does not fit inside -- and check every vertex of it, and so that a phone that cannot afford
     * the full ring can be given a smaller one without a code change.
     */
    static FarLayer build(WorldData world, double centreEast, double centreNorth, double nearM, double farM,
                          double palmBandM, double palmCellM, double canopyCellM, int maxVertices) {
        MeshData mesh = new MeshData(4096, 8192);
        Counts counts = new Counts();
        if (world == null) {
            return new FarLayer(mesh, centreEast, centreNorth, nearM, farM, 0, 0, 0, 0, 0, false, false, false);
        }
        Pass pass = new Pass(world, centreEast, centreNorth, nearM, farM, mesh);
        addRoads(pass, maxVertices, counts);
        addVegetation(pass, Math.min(palmBandM, farM), palmCellM, canopyCellM, maxVertices, counts);
        mesh.compact();
        return new FarLayer(mesh, centreEast, centreNorth, nearM, farM, counts.roadSegments, counts.palms,
                counts.canopies, counts.ways, counts.areas, counts.roadsTruncated, counts.palmsTruncated,
                counts.canopiesTruncated);
    }

    /**
     * What one build needs to know: the bundle, the band, the centre, and the mesh being written.
     *
     * <p>One object rather than eight parameters through four static methods, and it is also where the
     * ground is sampled -- so every vertex this class writes is draped by the same one function, and the
     * frame conversion (bundle metres to grid metres, through the manifest's origin) happens once.
     */
    private static final class Pass {
        final WorldData world;
        final TerrainGrid terrain;
        final MeshData mesh;
        final double centreEast;
        final double centreNorth;
        final double nearM;
        final double farM;

        Pass(WorldData world, double centreEast, double centreNorth, double nearM, double farM,
             MeshData mesh) {
            this.world = world;
            this.terrain = world.terrainL0 != null ? world.terrainL0 : world.terrainL1;
            this.mesh = mesh;
            this.centreEast = centreEast;
            this.centreNorth = centreNorth;
            this.nearM = nearM;
            this.farM = farM;
        }

        /** The ground under a point in bundle metres: the coarse national grid, or the detailed one. */
        float ground(double east, double north) {
            if (terrain == null) {
                return 0f;
            }
            return (float) terrain.heightBilinear(world.globalEast(east), world.globalNorth(north));
        }

        /** True when a point is between the two radii of the band. */
        boolean inBand(double east, double north, double innerM, double outerM) {
            double dx = east - centreEast;
            double dz = north - centreNorth;
            double distance2 = dx * dx + dz * dz;
            return distance2 >= innerM * innerM && distance2 <= outerM * outerM;
        }

        /**
         * True when a bounding box has any part of itself or its surroundings in the band.
         *
         * <p>Both halves matter and both are one distance comparison. The box is rejected when its
         * <em>farthest corner</em> is closer than the inner radius -- the whole thing is in the real
         * geometry's territory -- and when its <em>nearest point</em> is beyond the outer radius, where the
         * horizon owns it.
         */
        boolean reachesBand(float minEast, float minNorth, float maxEast, float maxNorth, double innerM,
                            double outerM) {
            double farthestEast = Math.max(Math.abs(minEast - centreEast), Math.abs(maxEast - centreEast));
            double farthestNorth = Math.max(Math.abs(minNorth - centreNorth), Math.abs(maxNorth - centreNorth));
            if (farthestEast * farthestEast + farthestNorth * farthestNorth < innerM * innerM) {
                return false;
            }
            double nearestEast = Math.max(0.0, Math.max(minEast - centreEast, centreEast - maxEast));
            double nearestNorth = Math.max(0.0, Math.max(minNorth - centreNorth, centreNorth - maxNorth));
            return nearestEast * nearestEast + nearestNorth * nearestNorth <= outerM * outerM;
        }
    }

    /** Pass counters, kept in one object so the three passes can be handed back in one return. */
    private static final class Counts {
        int roadSegments;
        int palms;
        int canopies;
        int ways;
        int areas;
        boolean roadsTruncated;
        boolean palmsTruncated;
        boolean canopiesTruncated;
    }

    // ------------------------------------------------------------------ roads

    /**
     * Draws the road network as ribbons: one quad per sixty metres of way, most important class first.
     *
     * <p>Sorted by class rather than by distance, because the two failures are not equally bad: a build
     * that runs out of budget with a suburb missing has drawn the country's roads, and one that runs out
     * with the Salwa road missing has drawn a suburb. Ordering by class means a truncated build is the
     * same picture at a coarser resolution instead of a picture of somewhere else.
     *
     * <p>Long OSM segments are subdivided on the way through. A way's vertices are wherever the survey put
     * them -- a desert highway can have one vertex every two kilometres -- and a two-kilometre quad is both
     * a bad fit to the ground and too coarse for the band test to mean anything.
     */
    private static void addRoads(Pass pass, int ceiling, Counts counts) {
        Roads roads = pass.world.roads;
        FeatureIndex index = pass.world.roadIndex;
        if (roads == null || index == null || roads.count == 0) {
            return;
        }
        // The index reports a way once per grid cell it touches, so a 30 km highway comes back dozens of
        // times. One bit per way, thrown away with the rest of the build.
        long[] claimed = new long[(roads.count + 63) >> 6];
        IntList candidates = new IntList();
        index.query(pass.centreEast - pass.farM, pass.centreNorth - pass.farM, pass.centreEast + pass.farM,
                pass.centreNorth + pass.farM, line -> {
                    if (line < 0 || line >= roads.count) {
                        return true;
                    }
                    if (roads.roadClass[line] > MAX_ROAD_CLASS || roads.isTunnel(line)) {
                        return true;
                    }
                    if (roads.lineLength[line] < 2) {
                        // A single-vertex way has no direction, so it has no ribbon: a point is not a road.
                        return true;
                    }
                    if ((claimed[line >> 6] & (1L << (line & 63))) != 0) {
                        return true;
                    }
                    claimed[line >> 6] |= 1L << (line & 63);
                    int box = line * 4;
                    if (!pass.reachesBand(roads.bounds[box], roads.bounds[box + 1], roads.bounds[box + 2],
                            roads.bounds[box + 3], pass.nearM, pass.farM)) {
                        return true;
                    }
                    counts.ways++;
                    candidates.add(line);
                    return true;
                });

        int[] lines = candidates.toArray();
        int budget = Math.min(MAX_ROAD_VERTICES, ceiling);
        int emitted = 0;
        for (int roadClass = 1; roadClass <= MAX_ROAD_CLASS; roadClass++) {
            for (int candidate = 0; candidate < lines.length; candidate++) {
                int line = lines[candidate];
                if (roads.roadClass[line] != roadClass) {
                    continue;
                }
                // The budget is handed to the way rather than checked before it, because a way is a
                // polyline: checking per way would let the last way through the door emit three hundred
                // metres of ribbon past a budget of four vertices, which is how the third install died.
                int remaining = budget - emitted;
                int used = addWay(pass, roads, line, remaining);
                emitted += used;
                if (used + ROAD_VERTICES_PER_SEGMENT > remaining) {
                    // The way stopped with a segment still in hand, so what is missing is reported.
                    counts.roadsTruncated = true;
                    counts.roadSegments = emitted / ROAD_VERTICES_PER_SEGMENT;
                    return;
                }
            }
        }
        counts.roadSegments = emitted / ROAD_VERTICES_PER_SEGMENT;
    }

    /**
     * Adds every drawn piece of one way, and returns the vertices it used.
     *
     * <p>The way is walked once, subdivided to {@link #ROAD_STEP_M}, and each sub-segment whose midpoint
     * lies in the band is drawn. Testing the midpoint rather than clipping the line is a deliberate
     * approximation: with sixty-metre steps the worst a segment can hang over the far edge is thirty
     * metres, which at eight kilometres is inside the fog, and a clip would mean the two ends of the
     * boundary were computed twice and could disagree -- the seam the detailed road mesher exists to
     * avoid.
     */
    private static int addWay(Pass pass, Roads roads, int line, int budget) {
        int start = roads.lineStart[line];
        int points = roads.lineLength[line];
        float half = Math.max(ROAD_MIN_HALF_M, Math.min(ROAD_MAX_HALF_M, roads.widthM[line] * 0.5f));
        double fromEast = roads.xz[start * 2];
        double fromNorth = roads.xz[start * 2 + 1];
        int used = 0;
        for (int i = 1; i < points; i++) {
            double toEast = roads.xz[(start + i) * 2];
            double toNorth = roads.xz[(start + i) * 2 + 1];
            double dx = toEast - fromEast;
            double dz = toNorth - fromNorth;
            double length = Math.sqrt(dx * dx + dz * dz);
            int steps = Math.max(1, (int) Math.ceil(length / ROAD_STEP_M));
            // The step is a fixed increment of the whole segment, not a fraction of what is left of it. The
            // first version of this loop multiplied the segment's own vector by s/steps while advancing its
            // own start -- so every step was longer than the one before it, the last one ran half a
            // kilometre past the way's end, and the ribbon left the road it was drawn from. A simulated
            // build caught it: the fixture's two-kilometre straight produced ten segments where eighteen
            // belong, at positions on no lattice at all.
            double stepEastSize = dx / steps;
            double stepNorthSize = dz / steps;
            for (int s = 1; s <= steps; s++) {
                // The last step is pinned to the vertex, so a long way cannot accumulate a drift of its own
                // and the piece that meets the next segment meets it exactly.
                double stepEast = s == steps ? toEast : fromEast + stepEastSize;
                double stepNorth = s == steps ? toNorth : fromNorth + stepNorthSize;
                if (pass.inBand((fromEast + stepEast) * 0.5, (fromNorth + stepNorth) * 0.5, pass.nearM,
                        pass.farM)) {
                    double stepLength = Math.hypot(stepEast - fromEast, stepNorth - fromNorth);
                    if (stepLength >= ROAD_MIN_SEGMENT_M) {
                        if (used + ROAD_VERTICES_PER_SEGMENT > budget
                                || !pass.mesh.hasRoom(ROAD_VERTICES_PER_SEGMENT)) {
                            return used;
                        }
                        addRoadQuad(pass, fromEast, fromNorth, stepEast, stepNorth, half);
                        used += ROAD_VERTICES_PER_SEGMENT;
                    }
                }
                fromEast = stepEast;
                fromNorth = stepNorth;
            }
        }
        return used;
    }

    /** One ribbon quad, draped over the ground at both ends and a little above it. */
    private static void addRoadQuad(Pass pass, double ax, double az, double bx, double bz, float half) {
        double dx = bx - ax;
        double dz = bz - az;
        double length = Math.hypot(dx, dz);
        if (length < 1e-6) {
            return;
        }
        // The perpendicular of the segment, in this frame's (east, north) plane. Which of the two
        // perpendiculars it is does not matter -- the quad is symmetric -- but the winding that follows
        // from it does, so it is derived below rather than chosen.
        double px = -dz / length * half;
        double pz = dx / length * half;
        double leftEastA = ax + px;
        double leftNorthA = az + pz;
        double leftEastB = bx + px;
        double leftNorthB = bz + pz;
        double rightEastA = ax - px;
        double rightNorthA = az - pz;
        double rightEastB = bx - px;
        double rightNorthB = bz - pz;

        float yA = pass.ground(ax, az) + ROAD_LIFT_M;
        float yB = pass.ground(bx, bz) + ROAD_LIFT_M;
        // u is metres along the way and v is across it: the shader paints the kerb from v and the kerb
        // joints from u, exactly as it does on the detailed road, so the coarse ribbon keeps the same
        // limestone edging as the road under the player's wheels.
        int a = pass.mesh.addVertex((float) leftEastA, yA, (float) leftNorthA, 0f, 1f, 0f,
                RoadMesher.SURFACE_ROAD, 0f, 0f, 1f);
        int b = pass.mesh.addVertex((float) leftEastB, yB, (float) leftNorthB, 0f, 1f, 0f,
                RoadMesher.SURFACE_ROAD, (float) length, 0f, 1f);
        int c = pass.mesh.addVertex((float) rightEastB, yB, (float) rightNorthB, 0f, 1f, 0f,
                RoadMesher.SURFACE_ROAD, (float) length, 1f, 1f);
        int d = pass.mesh.addVertex((float) rightEastA, yA, (float) rightNorthA, 0f, 1f, 0f,
                RoadMesher.SURFACE_ROAD, 0f, 1f, 1f);
        if (BuildingMesher.facesUp((float) leftEastA, (float) leftNorthA, (float) leftEastB,
                (float) leftNorthB, (float) rightEastB, (float) rightNorthB)) {
            pass.mesh.addQuad(a, b, c, d);
        } else {
            pass.mesh.addQuad(d, c, b, a);
        }
    }

    // ------------------------------------------------------------------ palms and canopy

    /**
     * Scatters palms and canopy patches over the compiled vegetation, one per lattice cell.
     *
     * <p>Iterating the rings rather than the lattice is what makes this affordable: Qatar's land use is
     * 5,784 rings of which 1,550 are vegetation, and a cell is only ever tested against the rings whose
     * bounding box contains it. Walking the lattice instead would test every cell of a 16 km square --
     * tens of thousands of them -- against every ring.
     *
     * <p>A cell is claimed by the first ring that contains its centre, and a claimed cell is never drawn
     * twice: land-use rings overlap in the compiled data (a farm inside a plantation, a mangrove inside a
     * marsh) and two palms on one spot is a palm the size of a car.
     */
    private static void addVegetation(Pass pass, double palmBandM, double palmCellM, double canopyCellM,
                                      int ceiling, Counts counts) {
        Areas landuse = pass.world.landuse;
        if (landuse == null || landuse.count == 0) {
            return;
        }
        double palmCell = Math.max(1.0, palmCellM);
        double canopyCell = Math.max(1.0, canopyCellM);
        // Two claim sets, because the two lattices are different sizes and a cell of one is not a cell of
        // the other. A key is the packed lattice coordinates, so it is exact for the whole country.
        HashSet<Long> palmCells = new HashSet<>();
        HashSet<Long> canopyCells = new HashSet<>();

        for (int ring = 0; ring < landuse.count; ring++) {
            if (landuse.kind[ring] != Areas.KIND_VEGETATION) {
                continue;
            }
            int box = ring * 4;
            if (!pass.reachesBand(landuse.bounds[box], landuse.bounds[box + 1], landuse.bounds[box + 2],
                    landuse.bounds[box + 3], pass.nearM, pass.farM)) {
                continue;
            }
            counts.areas++;
            claimCells(pass, landuse, ring, palmCells, palmCell, pass.nearM, palmBandM);
            claimCells(pass, landuse, ring, canopyCells, canopyCell, palmBandM, pass.farM);
        }

        drawPalms(pass, landuse, palmCells, palmCell, palmBandM,
                Math.min(MAX_PALM_VERTICES, ceiling), counts);
        drawCanopy(pass, landuse, canopyCells, canopyCell,
                Math.min(MAX_CANOPY_VERTICES, ceiling), counts);
    }

    /**
     * Claims every lattice cell of one ring whose centre is inside the ring and inside the band.
     *
     * <p>The ring is walked as a polygon, not as a bounding box: a plantation is a rectangle but a wadi and
     * a mangrove are not, and the difference between a bounding box and a ring is most of the coast. A
     * ring's cells are visited in lattice order, so which ring claims a shared cell depends on the ring
     * order -- and the ring order is the compiled file's, which is fixed, so the whole scatter is
     * reproducible.
     */
    private static void claimCells(Pass pass, Areas landuse, int ring, HashSet<Long> claimed, double cell,
                                   double innerM, double outerM) {
        if (outerM <= innerM) {
            return;
        }
        int start = landuse.ringStart[ring];
        int count = landuse.ringLength[ring];
        if (count < 3) {
            return;
        }
        int box = ring * 4;
        int firstColumn = (int) Math.floor(Math.max(landuse.bounds[box], pass.centreEast - outerM) / cell);
        int lastColumn = (int) Math.ceil(Math.min(landuse.bounds[box + 2], pass.centreEast + outerM) / cell);
        int firstRow = (int) Math.floor(Math.max(landuse.bounds[box + 1], pass.centreNorth - outerM) / cell);
        int lastRow = (int) Math.ceil(Math.min(landuse.bounds[box + 3], pass.centreNorth + outerM) / cell);
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                long key = cellKey(column, row);
                if (claimed.contains(key)) {
                    continue;
                }
                double east = (column + 0.5) * cell;
                double north = (row + 0.5) * cell;
                if (!pass.inBand(east, north, innerM, outerM)) {
                    continue;
                }
                if (!inside(landuse, start, count, east, north)) {
                    continue;
                }
                claimed.add(key);
            }
        }
    }

    /**
     * Draws the palms of the near band, up to the budget.
     *
     * <p>Sorted by the cell key, which is the lattice's own order -- row-major across the whole country --
     * so the same world always gives the same palms in the same order, and a test can assert the sequence
     * rather than the set.
     */
    private static void drawPalms(Pass pass, Areas landuse, HashSet<Long> cells, double cell,
                                  double palmBandM, int budget, Counts counts) {
        long[] keys = sortedKeys(cells);
        int drawn = 0;
        for (long key : keys) {
            if (drawn * PALM_VERTICES + PALM_VERTICES > budget || !pass.mesh.hasRoom(PALM_VERTICES)) {
                // Reached with a candidate still in hand: something was dropped, and that is reported.
                counts.palmsTruncated = true;
                break;
            }
            int column = (int) (key >> 32);
            int row = (int) key;
            // A jittered point inside the cell, so a plantation of palms does not read as a grid, and far
            // enough from the cell's edge that the palm's crown stays inside its own cell.
            double jitter = Math.max(0.0, Math.min(PALM_JITTER_M, cell * 0.5 - PALM_CROWN_RADIUS_M - 1.0));
            double east = (column + 0.5) * cell + (hash01(key * 2 + 1) * 2.0 - 1.0) * jitter;
            double north = (row + 0.5) * cell + (hash01(key * 2 + 2) * 2.0 - 1.0) * jitter;
            // A jittered palm can cross its ring's edge, and a palm standing in the sea is worse than a
            // palm missing from a plantation, so the jitter is checked rather than trusted.
            if (pass.inBand(east, north, pass.nearM, palmBandM) && insideAny(landuse, east, north)) {
                float height = PALM_MIN_HEIGHT_M
                        + (float) hash01(key * 2 + 3) * (PALM_MAX_HEIGHT_M - PALM_MIN_HEIGHT_M);
                addPalm(pass, east, north, height, (float) hash01(key * 2 + 4));
                drawn++;
            }
        }
        counts.palms = drawn;
    }

    /** One palm: a four-sided trunk and a crown cone, standing on the ground under it. */
    private static void addPalm(Pass pass, double east, double north, float height, float variation) {
        float baseY = pass.ground(east, north) - 0.2f;
        float trunkTop = baseY + height * (float) VegetationMesher.PALM_TRUNK_FRACTION;
        float top = baseY + height;
        float trunk = PALM_TRUNK_RADIUS_M;
        // The shader splits trunk from crown at VegetationMesher.PALM_TRUNK_FRACTION and reads u as the
        // height fraction up the plant, so the trunk is written from 0 to that fraction and the crown at 1.
        // Asking the vegetation mesher for the fraction rather than writing 0.62 here is the point: the two
        // must agree exactly, or the top of the trunk turns green and the underside of the crown brown.
        addPalmWall(pass.mesh, east - trunk, north - trunk, east + trunk, north - trunk, baseY, trunkTop,
                0f, -1f, variation);
        addPalmWall(pass.mesh, east + trunk, north - trunk, east + trunk, north + trunk, baseY, trunkTop,
                1f, 0f, variation);
        addPalmWall(pass.mesh, east + trunk, north + trunk, east - trunk, north + trunk, baseY, trunkTop,
                0f, 1f, variation);
        addPalmWall(pass.mesh, east - trunk, north + trunk, east - trunk, north - trunk, baseY, trunkTop,
                -1f, 0f, variation);

        // The crown: a cone whose rim sits above the trunk top and whose apex is the top of the palm, so it
        // is a surface from every direction a player can see it from -- above all from a helicopter, which
        // is where the far layer is read.
        float rimY = trunkTop + (top - trunkTop) * 0.25f;
        double[] rimEast = new double[PALM_SIDES];
        double[] rimNorth = new double[PALM_SIDES];
        for (int i = 0; i < PALM_SIDES; i++) {
            double angle = i * 2.0 * Math.PI / PALM_SIDES;
            rimEast[i] = east + Math.cos(angle) * PALM_CROWN_RADIUS_M;
            rimNorth[i] = north + Math.sin(angle) * PALM_CROWN_RADIUS_M;
        }
        // The apex is written at 1 and the rim at the same fraction the trunk stops at, which is what
        // VegetationMesher does with its own crown: both sides of that split are painted as frond, and the
        // crown then shades from the hinge to the tip instead of being one flat green cone.
        int apex = pass.mesh.addVertex((float) east, top, (float) north, 0f, 1f, 0f,
                VegetationMesher.SURFACE, 1f, variation, 0f);
        int[] rim = new int[PALM_SIDES];
        for (int i = 0; i < PALM_SIDES; i++) {
            rim[i] = pass.mesh.addVertex((float) rimEast[i], rimY, (float) rimNorth[i], 0f, 1f, 0f,
                    VegetationMesher.SURFACE, (float) VegetationMesher.PALM_TRUNK_FRACTION, variation, 0f);
        }
        for (int i = 0; i < PALM_SIDES; i++) {
            int next = (i + 1) % PALM_SIDES;
            // The rim is generated anticlockwise in (east, north); whether that is the winding this frame
            // wants for an outward face is asked of the projected triangle rather than assumed. See
            // SkylineMesh.addWall, which states the same trap for the walls of a block.
            if (BuildingMesher.facesUp((float) rimEast[i], (float) rimNorth[i], (float) rimEast[next],
                    (float) rimNorth[next], (float) east, (float) north)) {
                pass.mesh.addTriangle(rim[i], rim[next], apex);
            } else {
                pass.mesh.addTriangle(rim[next], rim[i], apex);
            }
        }
    }

    /**
     * One wall of a palm trunk, from the base to the trunk top, with the normal the trunk's outside faces.
     *
     * <p>The same derivation as {@code SkylineMesh.addWall}, and for the same reason: this frame is
     * left-handed in the (east, north) plane, so the hand rule that works on a diagram is the opposite of
     * the one that works here, and a sign error makes every trunk in the country invisible from outside.
     */
    private static void addPalmWall(MeshData mesh, float ax, float az, float bx, float bz, float baseY,
                                    float topY, float nx, float nz, float variation) {
        double dx = bx - ax;
        double dz = bz - az;
        boolean firstOrderFacesOut = nx * -dz + nz * dx > 0.0;
        float uHigh = (float) VegetationMesher.PALM_TRUNK_FRACTION;
        int a = mesh.addVertex(ax, baseY, az, nx, 0f, nz, VegetationMesher.SURFACE, 0f, variation, 0f);
        int b = mesh.addVertex(bx, baseY, bz, nx, 0f, nz, VegetationMesher.SURFACE, 0f, variation, 0f);
        int c = mesh.addVertex(bx, topY, bz, nx, 0f, nz, VegetationMesher.SURFACE, uHigh, variation, 0f);
        int d = mesh.addVertex(ax, topY, az, nx, 0f, nz, VegetationMesher.SURFACE, uHigh, variation, 0f);
        if (firstOrderFacesOut) {
            mesh.addQuad(a, b, c, d);
        } else {
            mesh.addQuad(b, a, d, c);
        }
    }

    /** The canopy patches of the far band: one flat quad per cell, following the ground under its corners. */
    private static void drawCanopy(Pass pass, Areas landuse, HashSet<Long> cells, double cell, int budget,
                                   Counts counts) {
        long[] keys = sortedKeys(cells);
        int drawn = 0;
        for (long key : keys) {
            if (drawn * CANOPY_VERTICES + CANOPY_VERTICES > budget || !pass.mesh.hasRoom(CANOPY_VERTICES)) {
                counts.canopiesTruncated = true;
                break;
            }
            int column = (int) (key >> 32);
            int row = (int) key;
            double west = column * cell + 1.0;
            double south = row * cell + 1.0;
            double east = (column + 1) * cell - 1.0;
            double north = (row + 1) * cell - 1.0;
            if (east - west < 2.0 || north - south < 2.0) {
                continue;
            }
            float lift = CANOPY_LIFT_M + (float) hash01(key * 2 + 5) * CANOPY_VARIATION_M;
            float variation = (float) hash01(key * 2 + 6);
            int material = materialUnder(landuse, (west + east) * 0.5, (south + north) * 0.5);
            int a = canopyVertex(pass, west, south, lift, variation, material);
            int b = canopyVertex(pass, east, south, lift, variation, material);
            int c = canopyVertex(pass, east, north, lift, variation, material);
            int d = canopyVertex(pass, west, north, lift, variation, material);
            if (BuildingMesher.facesUp((float) west, (float) south, (float) east, (float) south,
                    (float) east, (float) north)) {
                pass.mesh.addQuad(a, b, c, d);
            } else {
                pass.mesh.addQuad(d, c, b, a);
            }
            drawn++;
        }
        counts.canopies = drawn;
    }

    /** One canopy corner, lifted off the ground under it so that a patch on a dune does not bury itself. */
    private static int canopyVertex(Pass pass, double east, double north, float lift, float variation,
                                    int material) {
        // u is 1 at every corner: the top of the plant, which the shader paints as frond colour. The tint
        // says scrub rather than palm, because at four kilometres a palm crown and a bush are the same two
        // pixels and claiming otherwise would be a story about data this layer does not have.
        return pass.mesh.addVertexMaterial((float) east, pass.ground(east, north) + lift, (float) north,
                0f, 1f, 0f, VegetationMesher.SURFACE, 1f, variation, 1f, material);
    }

    // ------------------------------------------------------------------ shared helpers

    /** True when a point is inside one specific ring, by the even-odd crossing test. */
    private static boolean inside(Areas areas, int start, int count, double east, double north) {
        boolean in = false;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            double ei = areas.xz[(start + i) * 2];
            double ni = areas.xz[(start + i) * 2 + 1];
            double ej = areas.xz[(start + j) * 2];
            double nj = areas.xz[(start + j) * 2 + 1];
            if ((ni > north) != (nj > north) && east < (ej - ei) * (north - ni) / (nj - ni) + ei) {
                in = !in;
            }
        }
        return in;
    }

    /** True when a point is inside any vegetation ring. Used on a jittered palm, which may cross an edge. */
    private static boolean insideAny(Areas landuse, double east, double north) {
        for (int ring = 0; ring < landuse.count; ring++) {
            if (landuse.kind[ring] != Areas.KIND_VEGETATION) {
                continue;
            }
            int box = ring * 4;
            if (east < landuse.bounds[box] || east > landuse.bounds[box + 2]
                    || north < landuse.bounds[box + 1] || north > landuse.bounds[box + 3]) {
                continue;
            }
            if (inside(landuse, landuse.ringStart[ring], landuse.ringLength[ring], east, north)) {
                return true;
            }
        }
        return false;
    }

    /** The compiled material of the vegetation the point is in, or 0 when it is in none. */
    private static int materialUnder(Areas landuse, double east, double north) {
        for (int ring = 0; ring < landuse.count; ring++) {
            if (landuse.kind[ring] != Areas.KIND_VEGETATION) {
                continue;
            }
            int box = ring * 4;
            if (east < landuse.bounds[box] || east > landuse.bounds[box + 2]
                    || north < landuse.bounds[box + 1] || north > landuse.bounds[box + 3]) {
                continue;
            }
            if (inside(landuse, landuse.ringStart[ring], landuse.ringLength[ring], east, north)) {
                return landuse.areaClass[ring] & 0xFF;
            }
        }
        return 0;
    }

    /** The claimed cells as a sorted array: the lattice's own order, which is the same in every build. */
    private static long[] sortedKeys(HashSet<Long> cells) {
        long[] keys = new long[cells.size()];
        int n = 0;
        for (Long key : cells) {
            keys[n++] = key;
        }
        Arrays.sort(keys);
        return keys;
    }

    /** A lattice cell's own key, from its coordinates rather than from an array index: exact and signed. */
    private static long cellKey(int column, int row) {
        return ((long) column << 32) | (row & 0xFFFFFFFFL);
    }

    /**
     * A number in [0, 1) from a key, so that every plant's height, lean and shade is a pure function of
     * where it is. The mixer is the usual 64-bit avalanche: any two neighbouring cells give unrelated
     * results, which is the whole point -- a weaker hash gives a plantation whose palms visibly run in
     * lines.
     */
    private static double hash01(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** A growable int array, so the candidate list does not need a size guess. */
    private static final class IntList {
        private int[] items = new int[256];
        private int size;

        void add(int value) {
            if (size == items.length) {
                items = Arrays.copyOf(items, size * 2);
            }
            items[size++] = value;
        }

        int[] toArray() {
            return Arrays.copyOf(items, size);
        }
    }

    private FarLayerMesh() {
    }
}
