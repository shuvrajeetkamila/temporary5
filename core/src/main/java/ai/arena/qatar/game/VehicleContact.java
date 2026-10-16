package ai.arena.qatar.game;

/**
 * Two vehicles cannot occupy the same piece of ground.
 *
 * <p>This is the carried-forward item from the eighth install, and it is worth being clear about what it
 * is here, because the country has no traffic in it: the only vehicles in Qatar are the five the player
 * owns. So "vehicle-to-vehicle collision" means that when the player parks an aeroplane and walks into
 * it, the aeroplane is there -- and when they switch to the car, the car does not start inside the
 * aeroplane. Both of those were free before: every body ignored every other body, and
 * {@code placeAll} put all five of them on the same coordinate.
 *
 * <p>So there are two halves, and they are both arithmetic on positions:
 *
 * <ol>
 *   <li>{@link #separate} pushes the body the player is driving out of a parked one, to exactly
 *       touching. It is a pure function: same inputs, same outputs, testable on a laptop.</li>
 *   <li>{@link #parkingSpots} lays the player's other vehicles out in a ring around where the player
 *       arrives, far enough apart that no two footprints overlap. Without it, a spawn or a teleport
 *       would place five vehicles in one spot and the first fixed step would eject the player sideways
 *       -- which is the sort of thing that reads on a phone as "the human and the car cannot move".</li>
 * </ol>
 *
 * <p>Footprints are circles and are honest about being circles: a car is 1.15 m of radius where its real
 * half-width is about 1 m and half-length about 2.4 m, so the collision is generous lengthwise and
 * exact sideways. The alternative -- oriented boxes -- is a better model of the shapes and a worse model
 * of the time available, and the player's experience of it is that a parked car blocks a parked car.
 */
public final class VehicleContact {

    /** Footprint radius, in metres, of each vehicle the player can be. */
    public static final double HUMAN_RADIUS_M = 0.35;
    public static final double CAR_RADIUS_M = 1.15;
    public static final double PLANE_RADIUS_M = 5.5;
    public static final double HELICOPTER_RADIUS_M = 4.5;
    public static final double BOAT_RADIUS_M = 1.4;
    /** Vertical clearance: this far apart in height and the two are flying over each other. */
    public static final double VERTICAL_CLEARANCE_M = 2.5;
    /** Gap left between two parked vehicles, in metres, so the player can walk between them. */
    public static final double PARKING_GAP_M = 2.0;

    /** The footprint radius of a locomotion type. */
    public static double radiusM(PlayerController.Loco loco) {
        switch (loco) {
            case HUMAN:
                return HUMAN_RADIUS_M;
            case CAR:
                return CAR_RADIUS_M;
            case PLANE:
                return PLANE_RADIUS_M;
            case HELICOPTER:
                return HELICOPTER_RADIUS_M;
            default:
                return BOAT_RADIUS_M;
        }
    }

    /** Where a body has been put after a contact: the position, and what the contact did. */
    public static final class Resolved {
        public final double eastM;
        public final double northM;
        /** How far the body was pushed, in metres. Zero when it was already clear. */
        public final double pushedM;

        Resolved(double eastM, double northM, double pushedM) {
            this.eastM = eastM;
            this.northM = northM;
            this.pushedM = pushedM;
        }
    }

    /**
     * Pushes the body at {@code (eastM, northM)} with radius {@code radius} out of another body, so the
     * two are exactly touching and no closer.
     *
     * <p>When the two are in the same place -- which happens at a spawn, when every body is placed on one
     * coordinate -- the push is due north rather than undefined. Any direction would be arbitrary;
     * north is at least the same arbitrary direction every time, on every device.
     */
    public static Resolved separate(double eastM, double northM, double heightM, double radius,
                                    double otherEast, double otherNorth, double otherHeight,
                                    double otherRadius) {
        if (Math.abs(heightM - otherHeight) > VERTICAL_CLEARANCE_M) {
            return new Resolved(eastM, northM, 0.0);
        }
        double touching = radius + otherRadius;
        double dx = eastM - otherEast;
        double dz = northM - otherNorth;
        double distanceSquared = dx * dx + dz * dz;
        if (distanceSquared >= touching * touching) {
            return new Resolved(eastM, northM, 0.0);
        }
        double distance = Math.sqrt(distanceSquared);
        if (distance < 1e-6) {
            return new Resolved(otherEast, otherNorth + touching, touching);
        }
        double push = touching - distance;
        double scale = touching / distance;
        return new Resolved(otherEast + dx * scale, otherNorth + dz * scale, push);
    }

    /**
     * Where the player's other vehicles stand, given where the player has just arrived and where they
     * are facing.
     *
     * <p>A <b>line</b> directly behind the player, smallest footprint first, each one nose to tail with
     * the last: the distance to the {@code k}-th is the sum of every radius before it plus a gap each
     * time. Two reasons, and the first one is why this is not the fan it used to be.
     *
     * <ul>
     *   <li>Five vehicles cannot be spread around an arc behind a player. The aeroplane is 11 m across
     *       and the helicopter 9, and any arc that keeps them out of the player's path puts them within
     *       forty degrees of each other -- so they overlap, and the player arrives to a screen of
     *       interpenetrating machinery.</li>
     *   <li>Behind, because the player arrives facing somewhere and the next thing they will do is walk
     *       or drive forward. The line never crosses that direction, whatever the heading is: the
     *       smallest machine is nearest, so the car is the first thing they turn round to.</li>
     * </ul>
     *
     * <p>Deterministic, and sorted by radius, so the arrangement is the same in every test.
     *
     * @return for each locomotion type, its parked position; the player's own entry is the arrival point
     */
    public static double[][] parkingSpots(double eastM, double northM, double headingDegrees,
                                          PlayerController.Loco player) {
        PlayerController.Loco[] locos = PlayerController.Loco.values();
        double playerRadius = radiusM(player);
        double[][] spots = new double[locos.length][2];
        spots[player.ordinal()][0] = eastM;
        spots[player.ordinal()][1] = northM;

        // The others, smallest first, so the line is ordered by footprint and the arithmetic below is a
        // running total rather than a search.
        PlayerController.Loco[] queue = new PlayerController.Loco[locos.length - 1];
        int queued = 0;
        for (PlayerController.Loco loco : locos) {
            if (loco == player) {
                continue;
            }
            int at = queued;
            while (at > 0 && radiusM(queue[at - 1]) > radiusM(loco)) {
                queue[at] = queue[at - 1];
                at--;
            }
            queue[at] = loco;
            queued++;
        }
        // Straight back from the heading: the tail of the player's own machine, then the next one's nose.
        double backEast = -Math.sin(Math.toRadians(headingDegrees));
        double backNorth = -Math.cos(Math.toRadians(headingDegrees));
        double behind = playerRadius;
        for (PlayerController.Loco loco : queue) {
            double radius = radiusM(loco);
            behind += radius + PARKING_GAP_M;
            spots[loco.ordinal()][0] = eastM + backEast * behind;
            spots[loco.ordinal()][1] = northM + backNorth * behind;
            behind += radius;
        }
        return spots;
    }

    private VehicleContact() {
    }
}
