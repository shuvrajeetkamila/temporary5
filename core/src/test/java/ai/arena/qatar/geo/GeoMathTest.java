package ai.arena.qatar.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EPSG:32639 (WGS 84 / UTM zone 39N) verification.
 *
 * <p>The expected values are produced by PROJ 9.5.1 (pyproj) with {@code always_xy=True}, i.e. an
 * independent authority. Qatar spans roughly 24.5N..26.2N and 50.75E..51.65E, so the fixtures below
 * cover the four corners of the country plus Doha and the two extremes of the peninsula.
 *
 * <p>Tolerance: 1 cm. The Transverse Mercator series is valid to well below that across a single UTM
 * zone; anything worse means the implementation (or the CRS definition) is wrong.
 */
final class GeoMathTest {

    private static final double METRE_TOLERANCE = 0.01;
    private static final double DEGREE_TOLERANCE = 1e-8;

    @Test
    @DisplayName("forward projection matches PROJ at Qatar checkpoints")
    void forwardMatchesProj() {
        assertProjection(51.5310, 25.2854, 553459.1074, 2796656.1019, "Doha Corniche");
        assertProjection(51.2150, 26.1330, 521492.4432, 2890430.6448, "Al Ruwais (northwest)");
        assertProjection(51.1700, 24.7420, 517190.3159, 2736390.9423, "Abu Samra (south border)");
        assertProjection(51.3090, 26.1580, 530882.6071, 2893218.2435, "Ras Khuwair (northern tip)");
        assertProjection(50.7830, 25.3900, 478172.1864, 2808150.7523, "Dukhan (west coast)");
        assertProjection(51.4200, 24.6300, 542508.3675, 2724044.1211, "Khor Al Adaid (southeast)");
        assertProjection(51.5500, 24.9900, 555505.3839, 2763953.0110, "Mesaieed");
        assertProjection(51.5000, 25.6800, 550174.0228, 2840341.5155, "Al Khor");
        assertProjection(51.6000, 25.1700, 560462.9628, 2783906.4689, "Al Wakrah coast");
    }

    private void assertProjection(double lon, double lat, double east, double north, String label) {
        GeoMath.Projected p = GeoMath.toProjected(lat, lon, 0);
        assertEquals(east, p.east, METRE_TOLERANCE, label + " easting");
        assertEquals(north, p.north, METRE_TOLERANCE, label + " northing");
    }

    @Test
    @DisplayName("inverse projection matches PROJ and round-trips the whole country")
    void inverseMatchesProj() {
        assertInverse(553459.1074, 2796656.1019, 51.5310, 25.2854, "Doha");
        assertInverse(517190.3159, 2736390.9423, 51.1700, 24.7420, "Abu Samra");
        assertInverse(478172.1864, 2808150.7523, 50.7830, 25.3900, "Dukhan");

        // Round trip at the four national extremes plus Doha: sub-millimetre agreement.
        double[][] checkpoints = {
                {50.75, 24.55}, {51.65, 24.55}, {50.75, 26.20}, {51.65, 26.20}, {51.5310, 25.2854}
        };
        for (double[] cp : checkpoints) {
            GeoMath.Projected p = GeoMath.toProjected(cp[1], cp[0], 12.5);
            GeoMath.Geographic g = GeoMath.toGeographic(p.east, p.north, 12.5);
            assertEquals(cp[1], g.latDeg, DEGREE_TOLERANCE, "lat round trip");
            assertEquals(cp[0], g.lonDeg, DEGREE_TOLERANCE, "lon round trip");
            assertEquals(12.5, g.height, 1e-9, "height preserved");
        }
    }

    private void assertInverse(double east, double north, double lon, double lat, String label) {
        GeoMath.Geographic g = GeoMath.toGeographic(east, north, 0);
        assertEquals(lat, g.latDeg, DEGREE_TOLERANCE, label + " latitude");
        assertEquals(lon, g.lonDeg, DEGREE_TOLERANCE, label + " longitude");
    }

    @Test
    @DisplayName("projected distances and bearings match known national geometry")
    void distancesAndBearings() {
        // Doha Corniche to Al Ruwais: the full northwestern run of the peninsula (~99.1 km).
        double d = GeoMath.planarDistance(553459.1074, 2796656.1019, 521492.4432, 2890430.6448);
        assertEquals(99072.0, d, 600.0, "Doha to Al Ruwais straight-line distance (km-scale sanity)");

        // The country is roughly 160 km north-south and 90 km east-west.
        double ns = GeoMath.planarDistance(530882.6071, 2893218.2435, 517190.3159, 2736390.9423);
        assertTrue(ns > 150000 && ns < 165000, "north-south extent should be ~157 km, got " + ns);
        double ew = GeoMath.planarDistance(478172.1864, 2808150.7523, 560462.9628, 2783906.4689);
        assertTrue(ew > 80000 && ew < 95000, "east-west extent should be ~88 km, got " + ew);

        // Abu Samra -> Doha runs north-east: bearing should be in the 30-50 degree band.
        double bearing = GeoMath.planarBearingDeg(517190.3159, 2736390.9423, 553459.1074, 2796656.1019);
        assertTrue(bearing > 25 && bearing < 55, "Abu Samra -> Doha bearing band, got " + bearing);
    }

    @Test
    @DisplayName("grid convergence is small but non-zero and signed correctly")
    void gridConvergence() {
        double near = Math.abs(GeoMath.gridConvergenceRad(25.2854, 51.5310));   // east of 51E
        double far = Math.abs(GeoMath.gridConvergenceRad(25.0000, 50.2000));    // far west of 51E
        assertTrue(near < Math.toRadians(0.5), "convergence near the central meridian stays small");
        assertTrue(far > near, "convergence grows away from the central meridian");
        assertTrue(GeoMath.gridConvergenceRad(25.0, 52.0) > 0, "east of the central meridian is positive");
        assertTrue(GeoMath.gridConvergenceRad(25.0, 50.0) < 0, "west of the central meridian is negative");
    }

    @Test
    @DisplayName("invalid input is rejected instead of producing NaN geography")
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> GeoMath.toProjected(Double.NaN, 51.0, 0));
        assertThrows(IllegalArgumentException.class, () -> GeoMath.toProjected(25.0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> GeoMath.toGeographic(Double.NaN, 1000.0, 0));
    }
}
