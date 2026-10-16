package ai.arena.qatar.game;

import ai.arena.qatar.vehicle.BoatBody;
import ai.arena.qatar.vehicle.Surface;

/**
 * Where to put a boat when somebody calls for it, and whether there is anywhere to put it at all.
 *
 * <p>The game's locomotion buttons place a body at the player's own feet: press CAR and the car is under
 * you, press BOAT and the boat is under you. That is right on a road and wrong on a beach, because a boat
 * placed at a walker's feet is a boat sitting on sand -- the hull is grounded, the propeller has nothing
 * to push against, and the player who asked for a boat has been given a garden ornament. The coast of this
 * country makes that the common case rather than the corner case: Doha's corniche, the whole eastern
 * seaboard and every beach on the west side are places where a person standing on land is within sight of
 * navigable water and not standing in it.
 *
 * <p>So a boat is not placed where the player is; it is placed where a boat can float, as near to the
 * player as that is. This class is that decision and nothing else: it takes a {@link Surface} and a
 * position and returns a place, a heading and the distance it had to travel to find it. It does not touch
 * the bodies, the world or the screen, so it can be driven in a unit test with a shoreline written in
 * three lines -- see {@code BoatCallTest}.
 *
 * <h2>The numbers</h2>
 *
 * <p>The search radius is {@link #SEARCH_M}, six hundred metres, and it is deliberately much larger than
 * the rescue's hundred and sixty. A rescue that teleported a body six hundred metres would be a teleport
 * the player did not ask for; a boat call is a request for a machine, and the player has asked for exactly
 * that. Six hundred metres is also about the widest water a person can see across from a beach in this
 * country before they would rather drive.
 *
 * <p>A boat needs more than its draft to be usable: {@link BoatBody#DRAFT_M} is 0.55 m at rest, and a hull
 * in half a metre of water is aground on the first swell. {@link #MIN_DEPTH_M} is therefore the same 1.2 m
 * the walker refuses to wade in -- water too deep to stand in is water a boat can float in, which is one
 * number to remember instead of two -- and the maximum is the deepest water the compiled material grid can
 * express, so open sea is never refused.
 */
public final class BoatCall {

    /** How far out to look for navigable water, in metres. See the class comment for why six hundred. */
    public static final double SEARCH_M = 600.0;

    /** The least water a called boat will be placed in, in metres. */
    public static final double MIN_DEPTH_M = 1.2;

    /** The most water a called boat will be placed in, in metres: the open-water material is 14 m deep. */
    public static final double MAX_DEPTH_M = 200.0;

    /**
     * The step limit for the search, in metres: none.
     *
     * <p>The rescue's step limit stops it dropping a car off a bridge, because a car has to drive out of
     * wherever it is put. A boat floats: what is under it is several metres of water and a seabed nobody
     * will ever drive on, and a limit on how much that seabed may vary between here and there would refuse
     * perfectly good water off a steep shore for no reason a player could see.
     */
    public static final double NO_STEP_LIMIT_M = 1.0e9;

    /** The answer: where the boat goes, which way it points, and how far away that is. */
    public static final class Result {

        /** True when a place was found. When it is false, the other fields are the player's own position. */
        public final boolean found;
        public final double eastM;
        public final double northM;
        /** The heading to give the boat, in degrees: the game's convention, 0 = north, 90 = east. */
        public final double headingDegrees;
        /** How far the boat has to be put from the player, in metres. Zero means "right here". */
        public final double distanceM;
        /** Why not, when {@code found} is false: the panel's own words, already short enough to print. */
        public final String reason;

        Result(boolean found, double eastM, double northM, double headingDegrees, double distanceM,
               String reason) {
            this.found = found;
            this.eastM = eastM;
            this.northM = northM;
            this.headingDegrees = headingDegrees;
            this.distanceM = distanceM;
            this.reason = reason;
        }
    }

    /**
     * Plans a boat call from the player's position.
     *
     * <p>The boat is placed at the nearest water a boat can float in, and it is turned to face the player:
     * whoever called it is swimming or standing on a shore, and a hull that arrives bow-on is a hull
     * somebody can climb into. When the player is already afloat the nearest usable water is the water they
     * are in, so the boat is placed under them and keeps the heading they are facing, which is what
     * pressing the button twice should do.
     */
    public static Result plan(Surface surface, double eastM, double northM, double headingDegrees) {
        if (surface == null) {
            return new Result(false, eastM, northM, headingDegrees, 0.0, "no world");
        }
        SpawnClearer.Spot spot = SpawnClearer.nearestUsable(surface, eastM, northM, MIN_DEPTH_M,
                MAX_DEPTH_M, NO_STEP_LIMIT_M, SEARCH_M);
        if (!spot.found) {
            return new Result(false, eastM, northM, headingDegrees, 0.0,
                    "no water a boat can float in within " + (int) SEARCH_M + " m");
        }
        if (!spot.moved) {
            // Already floating: the water under the player is the answer, and the boat keeps their heading.
            return new Result(true, eastM, northM, headingDegrees, 0.0, null);
        }
        // Bow towards the player: the angle from the boat's own place to theirs, in the same convention the
        // bodies use (heading = atan2(east, north), so the north axis is 0 degrees).
        double heading = ai.arena.qatar.vehicle.VehicleState.wrap(
                Math.toDegrees(Math.atan2(eastM - spot.eastM, northM - spot.northM)));
        return new Result(true, spot.eastM, spot.northM, heading, spot.movedM, null);
    }

    private BoatCall() {
    }
}
