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

    @Test
    @DisplayName("the search stays inside its own budget of distance and directions")
    void theSearchIsBounded() {
        assertEquals(160.0, SpawnClearer.MAX_SEARCH_M, 1e-9);
        assertTrue(SpawnClearer.RING_STEP_M > 0.0 && SpawnClearer.RING_STEP_M < 20.0,
                "rings this far apart would step over a street");
        assertEquals(24, SpawnClearer.DIRECTIONS, "15-degree spacing keeps a one-way street reachable");
    }
}
