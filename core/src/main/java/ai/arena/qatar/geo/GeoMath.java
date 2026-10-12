package ai.arena.qatar.geo;

/**
 * Geodesy for the Qatar peninsula.
 *
 * <p>Master runtime CRS is <b>WGS 84 / UTM zone 39N (EPSG:32639)</b>. One projected metre is one
 * game metre: the world is true 1:1 scale, never compressed.
 *
 * <p>Everything here is {@code double} precision and free of engine/assets dependencies so it can be
 * unit tested on a plain JVM. Latitude/longitude is metadata only; physics and rendering never see
 * degrees.
 *
 * <p>Reference: J. P. Snyder, <i>Map Projections -- A Working Manual</i>, USGS Professional Paper
 * 1395, Transverse Mercator forward/inverse series. Verified against PROJ (pyproj) fixtures in
 * {@code GeoMathTest}.
 */
public final class GeoMath {

    // ---- WGS 84 ellipsoid (EPSG:7030) ----
    public static final double WGS84_A = 6378137.0;
    public static final double WGS84_F = 1.0 / 298.257223563;
    public static final double WGS84_B = WGS84_A * (1.0 - WGS84_F);
    /** First eccentricity squared. */
    public static final double E2 = WGS84_F * (2.0 - WGS84_F);
    /** Second eccentricity squared. */
    public static final double EP2 = E2 / (1.0 - E2);

    // ---- EPSG:32639 parameters ----
    /** UTM zone 39 lies between 48 and 54 degrees east; Qatar straddles the zone. */
    public static final int UTM_ZONE = 39;
    public static final char UTM_ZONE_LETTER = 'N';
    public static final double UTM_CENTRAL_MERIDIAN_DEG = 51.0;
    public static final double UTM_CENTRAL_MERIDIAN_RAD = Math.toRadians(UTM_CENTRAL_MERIDIAN_DEG);
    public static final double UTM_SCALE_FACTOR = 0.9996;
    public static final double UTM_FALSE_EASTING = 500000.0;
    public static final double UTM_FALSE_NORTHING = 0.0;

    /** Local geodetic anchor used to express "local metres" for logging and quick sanity checks. */
    public static final double ANCHOR_LAT = 25.2854;  // Doha, Al Corniche
    public static final double ANCHOR_LON = 51.5310;

    private GeoMath() {
    }

    /** A double-precision projected position (east, north in metres) plus ellipsoidal height. */
    public static final class Projected {
        public final double east;
        public final double north;
        public final double height;

        public Projected(double east, double north, double height) {
            this.east = east;
            this.north = north;
            this.height = height;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "Projected[E=%.4f N=%.4f h=%.3f]", east, north, height);
        }
    }

    /** A double-precision geographic position. */
    public static final class Geographic {
        public final double latDeg;
        public final double lonDeg;
        public final double height;

        public Geographic(double latDeg, double lonDeg, double height) {
            this.latDeg = latDeg;
            this.lonDeg = lonDeg;
            this.height = height;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "Geographic[lat=%.8f lon=%.8f h=%.3f]", latDeg, lonDeg, height);
        }
    }

    /** Forward Transverse Mercator: WGS 84 geographic -> EPSG:32639 projected metres. */
    public static Projected toProjected(double latDeg, double lonDeg, double heightMetres) {
        if (!isFinite(latDeg) || !isFinite(lonDeg) || Math.abs(latDeg) > 90.0) {
            throw new IllegalArgumentException("invalid geographic coordinate: " + latDeg + "," + lonDeg);
        }
        final double phi = Math.toRadians(latDeg);
        final double lam = Math.toRadians(lonDeg);
        final double sinPhi = Math.sin(phi);
        final double cosPhi = Math.cos(phi);
        final double tanPhi = Math.tan(phi);

        final double n = WGS84_A / Math.sqrt(1.0 - E2 * sinPhi * sinPhi);
        final double t = tanPhi * tanPhi;
        final double c = EP2 * cosPhi * cosPhi;
        double a = cosPhi * (lam - UTM_CENTRAL_MERIDIAN_RAD);
        // Keep A near [-pi, pi] for safe series convergence.
        a = wrapRadians(a);

        final double m = meridionalArc(phi);

        final double a2 = a * a;
        final double a3 = a2 * a;
        final double a4 = a3 * a;
        final double a5 = a4 * a;
        final double a6 = a5 * a;

        final double east = UTM_FALSE_EASTING + UTM_SCALE_FACTOR * n
                * (a + (1.0 - t + c) * a3 / 6.0
                + (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * EP2) * a5 / 120.0);

        final double north = UTM_FALSE_NORTHING + UTM_SCALE_FACTOR * (m + n * tanPhi
                * (a2 / 2.0
                + (5.0 - t + 9.0 * c + 4.0 * c * c) * a4 / 24.0
                + (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * EP2) * a6 / 720.0));

        return new Projected(east, north, heightMetres);
    }

    /** Inverse Transverse Mercator: EPSG:32639 projected metres -> WGS 84 geographic. */
    public static Geographic toGeographic(double east, double north, double heightMetres) {
        if (!isFinite(east) || !isFinite(north)) {
            throw new IllegalArgumentException("non-finite projected coordinate");
        }
        final double x = east - UTM_FALSE_EASTING;
        final double y = north - UTM_FALSE_NORTHING;

        final double m = y / UTM_SCALE_FACTOR;
        final double mu = m / (WGS84_A * (1.0 - E2 / 4.0 - 3.0 * E2 * E2 / 64.0 - 5.0 * E2 * E2 * E2 / 256.0));

        final double e1 = (1.0 - Math.sqrt(1.0 - E2)) / (1.0 + Math.sqrt(1.0 - E2));
        final double e1_2 = e1 * e1;
        final double e1_3 = e1_2 * e1;
        final double e1_4 = e1_3 * e1;

        final double phi1 = mu
                + (3.0 * e1 / 2.0 - 27.0 * e1_3 / 32.0) * Math.sin(2.0 * mu)
                + (21.0 * e1_2 / 16.0 - 55.0 * e1_4 / 32.0) * Math.sin(4.0 * mu)
                + (151.0 * e1_3 / 96.0) * Math.sin(6.0 * mu)
                + (1097.0 * e1_4 / 512.0) * Math.sin(8.0 * mu);

        final double sinPhi1 = Math.sin(phi1);
        final double cosPhi1 = Math.cos(phi1);
        final double tanPhi1 = Math.tan(phi1);

        final double n1 = WGS84_A / Math.sqrt(1.0 - E2 * sinPhi1 * sinPhi1);
        final double t1 = tanPhi1 * tanPhi1;
        final double c1 = EP2 * cosPhi1 * cosPhi1;
        final double r1 = WGS84_A * (1.0 - E2) / Math.pow(1.0 - E2 * sinPhi1 * sinPhi1, 1.5);
        final double d = x / (n1 * UTM_SCALE_FACTOR);

        final double d2 = d * d;
        final double d3 = d2 * d;
        final double d4 = d3 * d;
        final double d5 = d4 * d;
        final double d6 = d5 * d;

        final double latRad = phi1 - (n1 * tanPhi1 / r1)
                * (d2 / 2.0
                - (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1 - 9.0 * EP2) * d4 / 24.0
                + (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1 - 252.0 * EP2 - 3.0 * c1 * c1) * d6 / 720.0);

        final double lonRad = UTM_CENTRAL_MERIDIAN_RAD
                + (d - (1.0 + 2.0 * t1 + c1) * d3 / 6.0
                + (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1 + 8.0 * EP2 + 24.0 * t1 * t1) * d5 / 120.0)
                / cosPhi1;

        return new Geographic(Math.toDegrees(latRad), Math.toDegrees(lonRad), heightMetres);
    }

    /** Meridional arc length from the equator to latitude {@code phi} (radians). */
    public static double meridionalArc(double phi) {
        final double e4 = E2 * E2;
        final double e6 = e4 * E2;
        return WGS84_A * (
                (1.0 - E2 / 4.0 - 3.0 * e4 / 64.0 - 5.0 * e6 / 256.0) * phi
                        - (3.0 * E2 / 8.0 + 3.0 * e4 / 32.0 + 45.0 * e6 / 1024.0) * Math.sin(2.0 * phi)
                        + (15.0 * e4 / 256.0 + 45.0 * e6 / 1024.0) * Math.sin(4.0 * phi)
                        - (35.0 * e6 / 3072.0) * Math.sin(6.0 * phi));
    }

    /** Planar (projected) distance in metres between two EPSG:32639 points. */
    public static double planarDistance(double e1, double n1, double e2, double n2) {
        final double de = e2 - e1;
        final double dn = n2 - n1;
        return Math.sqrt(de * de + dn * dn);
    }

    /** Compass bearing in degrees (0 = north, 90 = east) from point 1 to point 2 in projected space. */
    public static double planarBearingDeg(double e1, double n1, double e2, double n2) {
        double deg = Math.toDegrees(Math.atan2(e2 - e1, n2 - n1));
        if (deg < 0.0) {
            deg += 360.0;
        }
        return deg;
    }

    /**
     * Rotation from projected grid north to true north, in radians (positive = grid north is
     * clockwise of true north). Small but non-zero in Qatar; used to keep headings honest.
     */
    public static double gridConvergenceRad(double latDeg, double lonDeg) {
        final double dLam = Math.toRadians(lonDeg) - UTM_CENTRAL_MERIDIAN_RAD;
        return Math.atan(Math.tan(dLam) * Math.sin(Math.toRadians(latDeg)));
    }

    /** Point at a given distance (m) and bearing (deg) from a projected point -- flat-earth forward. */
    public static Projected destination(double east, double north, double bearingDeg, double distanceMetres) {
        final double b = Math.toRadians(bearingDeg);
        return new Projected(east + Math.sin(b) * distanceMetres, north + Math.cos(b) * distanceMetres, 0.0);
    }

    private static double wrapRadians(double a) {
        while (a > Math.PI) {
            a -= 2.0 * Math.PI;
        }
        while (a < -Math.PI) {
            a += 2.0 * Math.PI;
        }
        return a;
    }

    public static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
