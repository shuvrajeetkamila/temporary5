package ai.arena.qatar.game;

import ai.arena.qatar.physics.SurfaceMaterial;
import ai.arena.qatar.vehicle.BoatBody;
import ai.arena.qatar.vehicle.Surface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a called boat goes, and where it must never go.
 *
 * <p>The failure this class exists for is the one a player meets on every beach in this country: the boat
 * arrives at their feet, which is dry sand, so the hull is aground, the propeller has nothing to push
 * against and the boat that was called does not move. Where it is placed is a decision over a shoreline,
 * so it is tested over shorelines -- written here as functions, a hundred lines each -- rather than
 * against the compiled country, and every property asserted is one a player could have reported.
 */
final class BoatCallTest {

    /** Land south of the shoreline and water north of it, with the water deepening in steps offshore. */
    private static Surface coast(double shoreNorthM, double depthM) {
        return new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return northM < shoreNorthM ? 2.0 : 2.0 - depthM;
            }

            @Override
            public int materialId(double eastM, double northM) {
                if (northM >= shoreNorthM) {
                    return depthM > 2.0 ? SurfaceMaterial.ID_OPEN_WATER
                            : SurfaceMaterial.ID_SHALLOW_WATER;
                }
                return SurfaceMaterial.ID_COMPACT_SAND;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return northM < shoreNorthM ? 0.0 : depthM;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }
        };
    }

    /** A country with no sea in it at all, inside the search radius. */
    private static Surface desert() {
        return Surface.land(12.0, SurfaceMaterial.ID_LOOSE_SAND_DUNE);
    }

    @Test
    @DisplayName("a boat called from the beach is placed in the water, and the water is the nearest there is")
    void theBoatGoesToTheWater() {
        // The shoreline is fifty metres north of the player. Fifty is not a multiple of the eight-metre
        // ring spacing, so the nearest usable ring is the seventh, at fifty-six metres -- and the test
        // asserting the number rather than a range is the point: a search that quietly walked a hundred
        // metres out to find deeper water would put the boat beyond a swimmer's reach.
        Surface surface = coast(50.0, 5.0);
        BoatCall.Result call = BoatCall.plan(surface, 0.0, 0.0, 0.0);
        assertTrue(call.found, "a boat was refused on a beach with deep water fifty metres away");
        assertEquals(56.0, call.distanceM, 0.001, "the boat was not placed at the nearest water");
        assertTrue(surface.waterDepthM(call.eastM, call.northM) >= BoatCall.MIN_DEPTH_M,
                "the boat was placed in water too shallow to float it: "
                        + surface.waterDepthM(call.eastM, call.northM));
        assertTrue(call.northM > 0.0, "the boat was placed on the sand: " + call.northM);
    }

    @Test
    @DisplayName("the boat arrives bow-on to whoever called it")
    void theBoatFacesThePlayer() {
        Surface surface = coast(50.0, 5.0);
        BoatCall.Result call = BoatCall.plan(surface, 0.0, 0.0, 90.0);
        assertTrue(call.found, "no boat");
        // The heading is the body convention: forward is (sin, cos). Pointing at the player from the
        // boat's own place means the two agree to within a degree, which is what lets a swimmer see the
        // bow and swim for it.
        double forwardEast = Math.sin(Math.toRadians(call.headingDegrees));
        double forwardNorth = Math.cos(Math.toRadians(call.headingDegrees));
        double toPlayerEast = 0.0 - call.eastM;
        double toPlayerNorth = 0.0 - call.northM;
        double length = Math.hypot(toPlayerEast, toPlayerNorth);
        double agreement = (forwardEast * toPlayerEast + forwardNorth * toPlayerNorth) / length;
        assertTrue(agreement > 0.999, "the boat does not face the player who called it: " + agreement);
    }

    @Test
    @DisplayName("a boat called from open water is placed under the player and keeps their heading")
    void aBoatCalledFromTheWaterLandsUnderfoot() {
        Surface surface = coast(-1000.0, 14.0);
        BoatCall.Result call = BoatCall.plan(surface, 200.0, 400.0, 37.0);
        assertTrue(call.found, "a swimmer in fourteen metres of water was refused a boat");
        assertEquals(200.0, call.eastM, 0.001, "the boat was moved off the player's own position");
        assertEquals(400.0, call.northM, 0.001, "the boat was moved off the player's own position");
        assertEquals(37.0, call.headingDegrees, 0.001, "the boat turned for no reason");
        assertEquals(0.0, call.distanceM, 0.001, "the boat was placed somewhere other than underfoot");
    }

    @Test
    @DisplayName("a call with no water in reach is refused, and says so, and moves nobody")
    void noWaterIsRefusedRatherThanGuessed() {
        Surface surface = desert();
        BoatCall.Result call = BoatCall.plan(surface, 123.0, -456.0, 90.0);
        assertFalse(call.found, "a boat was placed in a country with no sea in it");
        assertEquals(123.0, call.eastM, 0.001, "a refused call moved the player");
        assertEquals(-456.0, call.northM, 0.001, "a refused call moved the player");
        assertTrue(call.reason != null && call.reason.contains("600"),
                "the refusal does not say how far it looked: " + call.reason);
    }

    @Test
    @DisplayName("the search stops at the radius it advertises")
    void theSearchStopsAtItsRadius() {
        // Water six hundred and forty metres north, which is past the six hundred the call promises. The
        // player is told there is none rather than being put in a boat they cannot see.
        Surface surface = coast(640.0, 5.0);
        BoatCall.Result call = BoatCall.plan(surface, 0.0, 0.0, 0.0);
        assertFalse(call.found, "the search went past its own radius: " + call.distanceM);
    }

    @Test
    @DisplayName("a boat is never placed inside something solid, however close the water is")
    void waterUnderABuildingIsNotWaterForABoat() {
        // Deep water everywhere north of the shoreline, and a bridge pier over the nearest stretch of it:
        // a hull placed inside the pier is a hull that cannot move and that the player cannot board.
        Surface surface = new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return northM < 20.0 ? 2.0 : -12.0;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return northM < 20.0 ? SurfaceMaterial.ID_COMPACT_SAND
                        : SurfaceMaterial.ID_OPEN_WATER;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return northM < 20.0 ? 0.0 : 14.0;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }

            @Override
            public boolean solidAt(double eastM, double northM, double altitudeM) {
                return northM >= 20.0 && northM < 120.0;
            }
        };
        BoatCall.Result call = BoatCall.plan(surface, 0.0, 0.0, 0.0);
        assertTrue(call.found, "no boat was found, though there is open water past the pier");
        assertFalse(surface.solidAt(call.eastM, call.northM, 0.0),
                "the boat was placed inside a solid: " + call.eastM + ", " + call.northM);
        assertTrue(surface.waterDepthM(call.eastM, call.northM) >= BoatCall.MIN_DEPTH_M,
                "the boat was placed in water too shallow to float it");
        assertTrue(call.distanceM >= 120.0, "the search skipped past the pier without noticing it");
    }

    @Test
    @DisplayName("a boat is called into water it can actually float in, not into a puddle")
    void shallowWaterIsNotWaterForABoat() {
        // A beach that shelves very slowly: the water at the shoreline is knee deep for thirty metres.
        Surface surface = new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return northM < 0.0 ? 0.0 : -northM * 0.04;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return northM < 0.0 ? SurfaceMaterial.ID_COMPACT_SAND
                        : SurfaceMaterial.ID_SHALLOW_WATER;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return northM < 0.0 ? 0.0 : northM * 0.04;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return true;
            }
        };
        BoatCall.Result call = BoatCall.plan(surface, 0.0, 0.0, 0.0);
        assertTrue(call.found, "no water was found, though the sea is right there");
        assertTrue(surface.waterDepthM(call.eastM, call.northM) >= BoatCall.MIN_DEPTH_M,
                "the boat was beached in " + surface.waterDepthM(call.eastM, call.northM) + " m of water");
        // Thirty metres of shelving beach at four centimetres a metre: the depth reaches 1.2 m at 30 m,
        // so the boat belongs about that far out and not at the water's edge.
        assertTrue(call.distanceM >= 24.0, "the boat was called into the surf: " + call.distanceM);
    }

    @Test
    @DisplayName("the draft the boat actually has is what the minimum depth is for")
    void theMinimumDepthClearsTheDraft() {
        assertTrue(BoatCall.MIN_DEPTH_M > BoatBody.DRAFT_M * 2.0,
                "a boat called into water barely over its draft is aground on the first swell");
    }
}
