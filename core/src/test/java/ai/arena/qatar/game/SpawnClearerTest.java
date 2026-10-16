package ai.arena.qatar.game;

import ai.arena.qatar.physics.SurfaceMaterial;
import ai.arena.qatar.vehicle.Surface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standing somewhere possible.
 *
 * <p>A spawn point, a teleport target and a vehicle change all come out of the compiled country, and a
 * compiled coordinate is a coordinate, not a promise: the buildings are stored as bounding boxes, the
 * boxes overlap the roads in places, and a compound is one box around the whole of it. A body placed
 * inside a box is refused in every direction by its own collision rule -- which is what "the human and
 * the car cannot move" looked like from the player's seat, and what a teleport into a courtyard would
 * look like after this round's fix.
 *
 * <p>So the search is tested the way it is used: where it lands, what it does when there is nothing
 * clear to find, and that it never silently moves the player further than it says it did.
 */
final class SpawnClearerTest {

    /** A world whose only obstacle is a square of solid ground centred on the origin. */
    private static Surface withSolidSquare(double halfSideM) {
        return new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return 5.0;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return SurfaceMaterial.ID_URBAN_PAD;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return 0.0;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }

            @Override
            public boolean solidAt(double eastM, double northM, double altitudeM) {
                return Math.abs(eastM) < halfSideM && Math.abs(northM) < halfSideM;
            }
        };
    }

    /** A world of nothing but deep water: nowhere to stand at all. */
    private static Surface openSea() {
        return new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return -20.0;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return SurfaceMaterial.ID_OPEN_WATER;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return 20.0;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }
        };
    }

    @Test
    @DisplayName("clear ground is left exactly where the player asked for it")
    void clearGroundIsNotMoved() {
        SpawnClearer.Spot spot = SpawnClearer.nearestClear(withSolidSquare(10.0), 40.0, 40.0);
        assertFalse(spot.moved, "a clear spawn point was moved");
        assertEquals(40.0, spot.eastM, 1e-9);
        assertEquals(40.0, spot.northM, 1e-9);
        assertEquals(0.0, spot.movedM, 1e-9);
    }

    @Test
    @DisplayName("a point inside a building is moved out of it, by the nearest clear ring")
    void insideASolidMovesOut() {
        SpawnClearer.Spot spot = SpawnClearer.nearestClear(withSolidSquare(10.0), 0.0, 0.0);
        assertTrue(spot.moved, "a point inside a building was accepted as somewhere to stand");
        assertFalse(Math.abs(spot.eastM) < 10.0 && Math.abs(spot.northM) < 10.0,
                "the search returned a point that is still inside the building: " + spot.eastM + ", "
                        + spot.northM);
        // The nearest ring that reaches clear ground is the one at half a side, and 8 m rings step
        // outward from there: 16 m is the first ring outside a 10 m square.
        assertEquals(SpawnClearer.RING_STEP_M * 2, spot.movedM, 1e-9,
                "the search did not take the nearest way out: " + spot.movedM + " m");
    }

    @Test
    @DisplayName("nothing clear within reach means the player stays where they asked, unmoved")
    void nothingClearIsAnHonestFailure() {
        SpawnClearer.Spot spot = SpawnClearer.nearestClear(openSea(), 0.0, 0.0);
        assertFalse(spot.moved, "the search claimed to have found land in the middle of the sea");
        assertEquals(0.0, spot.eastM, 1e-9);
        assertEquals(0.0, spot.northM, 1e-9);
    }

    @Test
    @DisplayName("a solid with a clear courtyard is escaped, and deep water is not a place to stand")
    void theSearchUnderstandsWaterAndSolidity() {
        assertFalse(SpawnClearer.isClear(openSea(), 0.0, 0.0),
                "deep water counts as somewhere a person can stand");
        assertTrue(SpawnClearer.isClear(withSolidSquare(10.0), 30.0, 0.0),
                "clear ground next to a building was refused");
        // Shallow is wading, and wading is allowed: Qatar's khors are knee deep for hundreds of metres.
        Surface shallow = new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return 0.0;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return SurfaceMaterial.ID_SHALLOW_WATER;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return SpawnClearer.WADE_DEPTH_M - 0.1;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }
        };
        assertTrue(SpawnClearer.isClear(shallow, 0.0, 0.0), "wading depth is not swimmable water");
    }

    /**
     * Deep sea west of a line and ground under shallow water east of it: the shape a walker meets when they
     * are out of their depth next to the shore. The shore test below uses it; the cliff test writes its own
     * surface out in full, because it needs dry ground on both sides of the line -- the step rule has to be
     * the only thing refusing the drop.
     */
    private static Surface splitAtEast(double lineEastM, double westGroundM, double eastGroundM,
                                       double eastDepthM) {
        return new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return eastM < lineEastM ? westGroundM : eastGroundM;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return eastM < lineEastM ? SurfaceMaterial.ID_URBAN_PAD : SurfaceMaterial.ID_SHALLOW_WATER;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return eastM < lineEastM ? 20.0 : eastDepthM;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }
        };
    }

    @Test
    @DisplayName("a body in water it cannot cross is stepped to the shore, the shortest way out")
    void rescueFindsTheShore() {
        // Twenty metres of sea west of the line, knee-deep water east of it: a walker who is out of their
        // depth is put on the first ring that reaches the shallows, which is forty metres away.
        Surface shore = splitAtEast(40.0, 0.0, 0.0, 0.2);
        SpawnClearer.Spot spot = SpawnClearer.nearestUsable(shore, 0.0, 0.0, 0.0,
                SpawnClearer.WADE_DEPTH_M, 1.5);
        assertTrue(spot.moved, "a walker out of their depth was left there");
        assertEquals(40.0, spot.movedM, 1e-9, "the shortest way to ground that holds them: " + spot.movedM);
        assertTrue(SpawnClearer.isClear(shore, spot.eastM, spot.northM),
                "the rescue put the body somewhere a person can stand");
    }

    @Test
    @DisplayName("a rescue will not step a body off a cliff")
    void rescueRefusesADrop() {
        // The body is wedged inside a compound's bounding box, dry, on a plateau; the ground fifty metres
        // east of it is fifty metres lower. Rings go outwards from the body and the eastern candidates are
        // the first ones considered -- and refused, because a step of fifty metres is not a step. The body
        // is moved to the nearest ring of ground at its own height instead, one ring out, to the north-west.
        final double bodyEast = -5.0;
        final double wedgeRadiusM = 6.0;
        Surface cliff = new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return eastM < 0.0 ? 50.0 : 0.0;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return eastM < 0.0 ? SurfaceMaterial.ID_URBAN_PAD : SurfaceMaterial.ID_SHALLOW_WATER;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return 0.0;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }

            @Override
            public boolean solidAt(double eastM, double northM, double altitudeM) {
                double de = eastM - bodyEast;
                return de * de + northM * northM < wedgeRadiusM * wedgeRadiusM;
            }
        };
        SpawnClearer.Spot spot = SpawnClearer.nearestUsable(cliff, bodyEast, 0.0, 0.0,
                SpawnClearer.WADE_DEPTH_M, 1.5);
        assertTrue(spot.moved, "the body was wedged and nothing was found for it");
        assertEquals(SpawnClearer.RING_STEP_M, spot.movedM, 1e-9,
                "the nearest ground at the body's own height is one ring out: " + spot.movedM);
        assertEquals(50.0, cliff.groundHeightM(spot.eastM, spot.northM), 1e-9,
                "the rescue stepped the body off the cliff: " + spot.eastM + ", " + spot.northM);
        assertTrue(spot.eastM <= 0.0, "and it did not land past the edge either: " + spot.eastM);
        assertFalse(cliff.solidAt(spot.eastM, spot.northM, 51.0),
                "and it did not land inside the box it was wedged in");
    }

    @Test
    @DisplayName("a boat aground is moved into water deep enough to float it")
    void rescueFindsWaterForABoat() {
        Surface shallows = new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return -1.0;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return SurfaceMaterial.ID_SHALLOW_WATER;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return northM >= 16.0 ? 5.0 : 0.2;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }
        };
        SpawnClearer.Spot spot = SpawnClearer.nearestUsable(shallows, 0.0, 0.0, 0.8, 100.0, 3.0);
        assertTrue(spot.moved, "a boat with no water under it was left aground");
        assertTrue(shallows.waterDepthM(spot.eastM, spot.northM) >= 0.8,
                "the boat was moved to water it still cannot float in");
    }

    @Test
    @DisplayName("with nowhere better within reach, the body is left alone")
    void rescueIsAnHonestFailureToo() {
        SpawnClearer.Spot spot = SpawnClearer.nearestUsable(openSea(), 0.0, 0.0, 0.0,
                SpawnClearer.WADE_DEPTH_M, 1.5);
        assertFalse(spot.moved, "the middle of the sea is not a place to step a walker to");
        assertEquals(0.0, spot.eastM, 1e-9);
        assertEquals(0.0, spot.northM, 1e-9);
    }

    @Test
    @DisplayName("the search stays inside its own budget of distance and directions")
    void theSearchIsBounded() {
        assertEquals(160.0, SpawnClearer.MAX_SEARCH_M, 1e-9);
        assertTrue(SpawnClearer.RING_STEP_M > 0.0 && SpawnClearer.RING_STEP_M < 20.0,
                "rings this far apart would step over a street");
        assertEquals(24, SpawnClearer.DIRECTIONS, "15-degree spacing keeps a one-way street reachable");
    }
}
