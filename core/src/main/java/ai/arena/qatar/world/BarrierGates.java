package ai.arena.qatar.world;

import java.util.Arrays;

/**
 * Where a wall has a gate: the places a road crosses it.
 *
 * <p>The compiled country has 421 barrier lines -- walls, fences, hedges -- and no gates at all, because
 * OpenStreetMap maps a gate as a <em>node</em> on a wall and this pipeline compiles ways. So every
 * compound in Qatar was sealed: the villas, the schools, the palaces, the industrial yards and the whole
 * of the old town were enclosed by a wall the player could see over and not walk through, and the only
 * way in was the map. The gates are in the data all the same, and the game already loads them: they are
 * the driveways. A compound wall in the compiled country has a road through it wherever a service road
 * or a residential road ends at it or crosses it, and that crossing is exactly where the gate is.
 *
 * <p>So a gate is derived rather than stored: <b>a barrier is open wherever a road centre-line comes
 * within its own half-width, plus a margin, of the barrier</b>. Two things follow from that one rule, and
 * they are the reason it is one rule rather than two:
 *
 * <ul>
 *   <li>The physics and the picture agree. {@link #isGate} answers for a point and is what
 *       {@code WorldSurface} consults before calling a wall solid; {@link #standingIntervals} answers for
 *       a segment and is what the chunk mesher uses to leave a hole. Both come from the same crossing of
 *       the same road with the same half-width, so a gap that is drawn is a gap that can be walked
 *       through, and a wall that is drawn is a wall that stops you. A wall you can walk through is the
 *       fault this class exists to prevent, not the fix.</li>
 *   <li>The parallel road does not open the wall. A road running alongside a wall at three metres is not
 *       a gate, and the rule looks for a <em>crossing</em> -- two segments that intersect -- or for a road
 *       that <em>ends</em> at the wall, which is what a driveway into a compound looks like on the map.
 *       Distance alone would open every barrier on the Corniche.</li>
 * </ul>
 *
 * <p>A gate is as wide as the road that uses it: a 6 m residential street opens eight metres of wall
 * (three metres of half-width plus a metre of margin at each side, which is what a gatepost and a
 * pedestrian gap come to), and a 14 m primary road opens sixteen. Footpaths and tracks are excluded: a
 * gate a car cannot use is not a gate, and the country is full of tracks that merely touch a wall.
 *
 * <p>Nothing here is GL, nothing here is physics, and it is all arithmetic over two bundles, so the whole
 * rule is asserted against the committed fixture -- see {@code BarrierGatesTest}.
 */
public final class BarrierGates {

    /**
     * The least important road that cuts a gate: 8 is {@code service}, which is how a driveway is mapped.
     *
     * <p>9 and 10 are {@code living_street} and {@code track}, and 11 upwards are paths and steps. A
     * compound's gate is where its driveway crosses the wall, and a driveway is a service road.
     */
    public static final int MAX_ROAD_CLASS = 8;

    /** The least half-width of a gate, in metres: a foot gate is two and a half metres of opening. */
    public static final double MIN_HALF_WIDTH_M = 2.5;

    /** Added to the road's own half-width, in metres: a gatepost at each side of a driveway. */
    public static final double ROAD_MARGIN_M = 1.0;

    /** The widest half-width any gate can have: the widest road in the bundles plus the margin. */
    public static final double MAX_HALF_WIDTH_M = 20.0;

    private final Barriers barriers;
    private final Roads roads;
    private final FeatureIndex roadIndex;

    private BarrierGates(Barriers barriers, Roads roads, FeatureIndex roadIndex) {
        this.barriers = barriers;
        this.roads = roads;
        this.roadIndex = roadIndex;
    }

    /** The gates of one world: thin, and kept by {@link WorldData} alongside the bundles it derives from. */
    public static BarrierGates of(WorldData world) {
        return new BarrierGates(world.barriers, world.roads, world.roadIndex);
    }

    /** True when this world has both the walls and the roads a gate could be derived from. */
    public boolean usable() {
        return barriers != null && barriers.count > 0 && roads != null && roadIndex != null;
    }

    /**
     * True when a gate-cutting road passes close enough to this point to be a way through.
     *
     * <p>Called for a point that a barrier is already known to occupy -- the caller has measured the
     * distance to the wall itself -- so the cost is one index query over a twenty-metre box and a walk
     * along the few roads it returns. A body pressed against a wall pays that once a step, and a body in
     * the open pays nothing at all.
     */
    public boolean isGate(double eastM, double northM) {
        if (!usable()) {
            return false;
        }
        double[] hit = {0.0};
        roadIndex.query(eastM - MAX_HALF_WIDTH_M, northM - MAX_HALF_WIDTH_M, eastM + MAX_HALF_WIDTH_M,
                northM + MAX_HALF_WIDTH_M, line -> {
                    if (line < 0 || line >= roads.count || !cutsGates(line)) {
                        return true;
                    }
                    double half = halfWidthM(line);
                    if (distanceToPolyline(line, eastM, northM) <= half) {
                        hit[0] = 1.0;
                        return false;
                    }
                    return true;
                });
        return hit[0] > 0.5;
    }

    /**
     * The parts of one barrier segment left standing, as fractions of its length from {@code a} to
     * {@code b}, or null when nothing crosses it.
     *
     * <p>Null is the common case and the cheap one: most walls in the country are nowhere near a road, and
     * the caller draws the segment exactly as it always did. When something does cross, the answer is the
     * complement of the gate intervals, so a caller that walks the returned pairs draws the wall in
     * pieces with a real hole between them.
     */
    public double[] standingIntervals(double ax, double az, double bx, double bz) {
        if (!usable()) {
            return null;
        }
        double dx = bx - ax;
        double dz = bz - az;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.3) {
            return null;
        }
        double[] cuts = new double[8];
        int cutCount = 0;
        int[] candidates = roadIndex.collect(ax - MAX_HALF_WIDTH_M - 1, az - MAX_HALF_WIDTH_M - 1,
                bx + MAX_HALF_WIDTH_M + 1, bz + MAX_HALF_WIDTH_M + 1);
        for (int candidate : candidates) {
            if (candidate < 0 || candidate >= roads.count || !cutsGates(candidate)) {
                continue;
            }
            double half = halfWidthM(candidate);
            int start = roads.lineStart[candidate];
            int points = roads.lineLength[candidate];
            for (int i = 0; i < points; i++) {
                double px = roads.xz[(start + i) * 2];
                double pz = roads.xz[(start + i) * 2 + 1];
                // A road that ends at the wall is a driveway arriving at the gate: its last vertex is
                // within the half-width, and the opening is centred on where it points at the wall.
                if (i == 0 || i == points - 1) {
                    double at = projectionParam(ax, az, dx, dz, length, px, pz, half);
                    if (!Double.isNaN(at)) {
                        if (cutCount + 2 > cuts.length) {
                            cuts = Arrays.copyOf(cuts, cuts.length * 2);
                        }
                        cuts[cutCount++] = at - half / length;
                        cuts[cutCount++] = at + half / length;
                    }
                }
                if (i + 1 < points) {
                    double qx = roads.xz[(start + i + 1) * 2];
                    double qz = roads.xz[(start + i + 1) * 2 + 1];
                    double at = crossingParam(ax, az, dx, dz, px, pz, qx, qz);
                    if (!Double.isNaN(at)) {
                        if (cutCount + 2 > cuts.length) {
                            cuts = Arrays.copyOf(cuts, cuts.length * 2);
                        }
                        cuts[cutCount++] = at - half / length;
                        cuts[cutCount++] = at + half / length;
                    }
                }
            }
        }
        if (cutCount == 0) {
            return null;
        }
        return complement(cuts, cutCount);
    }

    /** True when this road is one a compound with a gate would have a driveway on. */
    private boolean cutsGates(int line) {
        return roads.roadClass[line] <= MAX_ROAD_CLASS && !roads.isTunnel(line);
    }

    /** How much wall one road opens, in metres either side of its centre-line. */
    private double halfWidthM(int line) {
        return Math.max(MIN_HALF_WIDTH_M, roads.widthM[line] * 0.5 + ROAD_MARGIN_M);
    }

    /**
     * The parameter along the barrier segment where the road crosses it, or NaN.
     *
     * <p>The two-dimensional line intersection, without a tolerance: segments that merely come close do
     * not cut a gate, and a road that crosses at a shallow angle is still a crossing. The parallel case
     * -- which is the one that matters -- divides by a determinant of zero and is rejected before the
     * division, so a road running along the outside of a wall for a kilometre opens nothing.
     */
    private static double crossingParam(double ax, double az, double dx, double dz, double px, double pz,
                                        double qx, double qz) {
        double ex = qx - px;
        double ez = qz - pz;
        double determinant = dx * ez - dz * ex;
        if (Math.abs(determinant) < 1e-9) {
            return Double.NaN;
        }
        double wx = px - ax;
        double wz = pz - az;
        // Cramer's rule on [d, -e] [t, s]^T = w, and the sign of the second row is not decoration: with it
        // the wrong way round, every crossing on the far side of a road's first vertex is rejected, which
        // is every crossing that matters -- the fixture's own wall came out with no gate at all.
        double along = (wx * ez - wz * ex) / determinant;
        double across = (wx * dz - dx * wz) / determinant;
        if (along < 0.0 || along > 1.0 || across < 0.0 || across > 1.0) {
            return Double.NaN;
        }
        return along;
    }

    /** The parameter of the nearest point on the barrier segment to a road vertex, or NaN when it is far. */
    private static double projectionParam(double ax, double az, double dx, double dz, double length,
                                          double px, double pz, double half) {
        double along = ((px - ax) * dx + (pz - az) * dz) / (length * length);
        if (along < 0.0 || along > 1.0) {
            return Double.NaN;
        }
        double closestEast = ax + dx * along;
        double closestNorth = az + dz * along;
        if (Math.hypot(px - closestEast, pz - closestNorth) > half) {
            return Double.NaN;
        }
        return along;
    }

    /**
     * The parts of [0, 1] left over when the cut intervals are removed.
     *
     * <p>Cuts are sorted and merged first, because two roads through one gate -- a driveway and the lane
     * beside it -- produce two intervals that overlap, and a caller that drew the wall between them would
     * draw a pillar in the middle of the gateway.
     */
    private static double[] complement(double[] cuts, int cutCount) {
        // Sort the flat (from, to) pairs by their start. A selection sort, because a gate has one or two
        // cuts and this runs inside a chunk build.
        for (int i = 0; i + 3 < cutCount; i += 2) {
            for (int j = i + 2; j + 1 < cutCount; j += 2) {
                if (cuts[j] < cuts[i]) {
                    double from = cuts[i];
                    double to = cuts[i + 1];
                    cuts[i] = cuts[j];
                    cuts[i + 1] = cuts[j + 1];
                    cuts[j] = from;
                    cuts[j + 1] = to;
                }
            }
        }
        double[] standing = new double[8];
        int count = 0;
        double cursor = 0.0;
        for (int i = 0; i < cutCount; i += 2) {
            double from = Math.max(0.0, cuts[i]);
            double to = Math.min(1.0, cuts[i + 1]);
            if (to <= cursor) {
                continue;                                   // swallowed by the previous gate
            }
            if (from > cursor) {
                if (count + 2 > standing.length) {
                    standing = Arrays.copyOf(standing, standing.length * 2);
                }
                standing[count++] = cursor;
                standing[count++] = from;
            }
            cursor = Math.max(cursor, to);
        }
        if (cursor < 1.0) {
            if (count + 2 > standing.length) {
                standing = Arrays.copyOf(standing, standing.length * 2);
            }
            standing[count++] = cursor;
            standing[count++] = 1.0;
        }
        return count == 0 ? null : Arrays.copyOf(standing, count);
    }

    /** The distance from a point to one compiled way's centre-line, in metres. */
    private double distanceToPolyline(int line, double eastM, double northM) {
        int start = roads.lineStart[line];
        int points = roads.lineLength[line];
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < points; i++) {
            double ax = roads.xz[(start + i) * 2];
            double az = roads.xz[(start + i) * 2 + 1];
            double bx = roads.xz[(start + i + 1) * 2];
            double bz = roads.xz[(start + i + 1) * 2 + 1];
            best = Math.min(best, distanceToSegment(eastM, northM, ax, az, bx, bz));
        }
        if (points == 1) {
            best = Math.hypot(eastM - roads.xz[start * 2], northM - roads.xz[start * 2 + 1]);
        }
        return best;
    }

    /** The distance from a point to a line segment, in metres. Shared with the collision that uses it. */
    public static double distanceToSegment(double px, double pz, double ax, double az, double bx,
                                           double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared < 1e-9) {
            return Math.hypot(px - ax, pz - az);
        }
        double t = ((px - ax) * dx + (pz - az) * dz) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(px - (ax + dx * t), pz - (az + dz * t));
    }

    /** How many barrier lines this world has, for a caller that wants to say so. */
    public int barrierCount() {
        return barriers == null ? 0 : barriers.count;
    }
}
