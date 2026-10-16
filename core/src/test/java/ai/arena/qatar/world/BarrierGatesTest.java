package ai.arena.qatar.world;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gates: where a wall is open, and how wide.
 *
 * <p>The fault this holds shut is one a player meets at every compound in the country. The compiled
 * country has 421 wall lines and no gate lines, because OSM maps a gate as a node on a wall and the
 * pipeline compiles ways -- so every compound was sealed, and the only way in was the map. The gates are
 * in the data anyway: they are the driveways, they are in {@code roads.bin.gz}, and a wall is open
 * wherever one crosses it.
 *
 * <p>Two things are asserted here, and the second matters more than the first:
 *
 * <ol>
 *   <li>Where the gate is, how wide it is, and that a wall with no road through it is left whole.</li>
 *   <li>That the place the mesher leaves a hole and the place the collision lets a body through are the
 *       same place. A drawn gap that blocks, or a drawn wall that does not, is the worst outcome of this
 *       feature: the player sees a wall and walks through it, or sees a way in and is stopped by
 *       nothing, and either way the world stops making sense. The two are checked against each other
 *       point by point along the wall.</li>
 * </ol>
 */
final class BarrierGatesTest {

    private static WorldData world;

    @BeforeAll
    static void loadFixture() throws IOException {
        Path candidate = Paths.get("src/test/resources/synthetic_world");
        if (!Files.isDirectory(candidate)) {
            candidate = Paths.get("core/src/test/resources/synthetic_world");
        }
        assertTrue(Files.isDirectory(candidate), "the fixture is missing: " + candidate);
        Path fixture = candidate;
        GameManifest manifest = GameManifest.parse(
                new String(Files.readAllBytes(fixture.resolve("manifest.json")), StandardCharsets.UTF_8));
        world = WorldData.fromSource(manifest, name -> {
            try {
                return Files.readAllBytes(fixture.resolve(name));
            } catch (IOException e) {
                throw new IllegalStateException("fixture file unreadable: " + name, e);
            }
        });
    }

    // ---------------------------------------------------------------- where the gate is

    @Test
    @DisplayName("a wall is open where a road crosses it, and solid everywhere else on it")
    void theGateIsWhereTheRoadCrosses() {
        // The fixture's wall runs east from (360, 180) to (700, 180); the residential road runs north
        // along easting 550 from northing 5 to northing 1100, straight through it. Everything else on the
        // wall is masonry.
        assertTrue(world.gates.isGate(550, 180), "the wall is solid where the road crosses it");
        assertFalse(world.gates.isGate(400, 180), "the wall has a gate nowhere near a road");
        assertFalse(world.gates.isGate(500, 180), "the wall has a gate twenty metres from one");
        assertFalse(world.gates.isGate(690, 180), "the wall has a gate at its eastern corner");
        // The question this answers is "does a gate-cutting road pass here", not "is there a wall here":
        // out in the middle of the road it is true, and the caller is expected to ask it only about a
        // point a wall is already known to occupy. That contract is written down here because the other
        // reading -- "is this point a gateway" -- would be true in the empty desert beside a road.
        assertTrue(world.gates.isGate(550, 700), "the gate rule is not consulting the roads at all");
    }

    @Test
    @DisplayName("the hole the wall is drawn with is as wide as the road that made it")
    void theOpeningIsTheRoadsOwnWidth() {
        // The road is the fixture's class 7 residential way: 6 m wide, so the opening is 3 m either side
        // of its centre-line (its own half-width) plus a metre of gatepost at each side: 8 m in all,
        // centred on the crossing at 190 m along the wall from its western end.
        double[] standing = world.gates.standingIntervals(360, 180, 700, 180);
        assertNotNull(standing, "the wall has no gate to draw");
        assertEquals(4, standing.length, "the wall should be drawn in two pieces");
        assertEquals(0.0, standing[0], 1e-9, "the wall does not start where it starts");
        assertEquals(1.0, standing[3], 1e-9, "the wall does not end where it ends");
        double gapFrom = standing[1] * 340.0;
        double gapTo = standing[2] * 340.0;
        assertEquals(8.0, gapTo - gapFrom, 0.01, "the gate is not eight metres wide");
        assertEquals(190.0, (gapFrom + gapTo) * 0.5, 0.01, "the gate is not where the road crosses");
    }

    @Test
    @DisplayName("a wall no road goes near is drawn whole")
    void aWallWithNoGateIsOnePiece() {
        // The western half of the same wall: the crossing is ninety metres further east.
        assertNull(world.gates.standingIntervals(360, 180, 500, 180),
                "a wall with no road through it was cut into pieces");
    }

    @Test
    @DisplayName("the second barrier is cut too, and by the service road that crosses it")
    void theHedgeHasItsOwnGate() {
        // The fixture's hedge runs north from (700, 180) to (700, 420), and the class 8 service road
        // crosses it at northing 235.7 -- which is 55.7 m along the hedge.
        double[] standing = world.gates.standingIntervals(700, 180, 700, 420);
        assertNotNull(standing, "the hedge has no gate to draw");
        double gapFrom = standing[1] * 240.0;
        double gapTo = standing[2] * 240.0;
        // 2 m half-width for a 4 m service road, plus the metre of gatepost at each side.
        assertEquals(6.0, gapTo - gapFrom, 0.02, "the hedge's gate is not six metres wide");
        assertEquals(55.71, (gapFrom + gapTo) * 0.5, 0.05, "the hedge's gate is in the wrong place");
    }

    @Test
    @DisplayName("the picture and the physics agree along every metre of the wall")
    void theGapThatIsDrawnIsTheGapThatIsWalkable() {
        // Both rules derive from the same crossing of the same road with the same half-width, and this is
        // the assertion that says so: walk the wall in half-metre steps, ask the collision whether a body
        // could be there, ask the mesher whether it left the wall out, and require the two to agree.
        double[] standing = world.gates.standingIntervals(360, 180, 700, 180);
        assertNotNull(standing, "the wall has no gate");
        int checked = 0;
        for (double along = 0.0; along <= 340.0; along += 0.5) {
            double east = 360.0 + along;
            // Points within a metre of the edge of a hole are not checked: the two rules meet there and
            // either answer is defensible. Everything else has one right answer.
            if (nearAnEdge(standing, along / 340.0, 1.0 / 340.0)) {
                continue;
            }
            boolean walkable = world.gates.isGate(east, 180.0);
            boolean drawnOpen = !covered(standing, along / 340.0);
            assertEquals(drawnOpen, walkable, "at " + along + " m along the wall the wall is "
                    + (walkable ? "walkable" : "solid") + " and the drawing says the opposite");
            checked++;
        }
        assertTrue(checked > 600, "only " + checked + " points of the wall were checked");
    }

    @Test
    @DisplayName("the whole country is asked, and the answer is a small and plausible number of gates")
    void theRuleIsNotGreedy() {
        // The fixture has two crossings in it -- the residential road through the wall, the service road
        // through the hedge -- and the rule finds exactly those two. A rule based on distance rather than
        // on crossing would find more: the service road starts twenty metres north of the wall and runs
        // back to within a few metres of its eastern end, and every point of it that came within a gate's
        // width would have opened another hole.
        int gates = 0;
        for (int line = 0; line < world.barriers.count; line++) {
            int start = world.barriers.lineStart[line];
            int points = world.barriers.lineLength[line];
            int pieces = 0;
            for (int i = 0; i + 1 < points; i++) {
                double[] standing = world.gates.standingIntervals(
                        world.barriers.xz[(start + i) * 2], world.barriers.xz[(start + i) * 2 + 1],
                        world.barriers.xz[(start + i + 1) * 2],
                        world.barriers.xz[(start + i + 1) * 2 + 1]);
                if (standing == null) {
                    continue;
                }
                // Two standing pieces with a hole between them is one gate; one piece is a gate at the
                // end of a segment, which counts the same.
                if (standing.length >= 4) {
                    pieces++;
                }
            }
            gates += pieces;
        }
        assertEquals(2, gates, "the fixture's two barriers should have one gate each");
    }

    // ---------------------------------------------------------------- helpers

    /** True when a parameter is within {@code margin} of any edge of a standing interval. */
    private static boolean nearAnEdge(double[] standing, double at, double margin) {
        for (int i = 0; i < standing.length; i++) {
            if (Math.abs(standing[i] - at) <= margin) {
                return true;
            }
        }
        return false;
    }

    /** True when a parameter is inside one of the standing intervals: the wall is still there. */
    private static boolean covered(double[] standing, double at) {
        for (int i = 0; i + 1 < standing.length; i += 2) {
            if (at >= standing[i] && at <= standing[i + 1]) {
                return true;
            }
        }
        return false;
    }
}
