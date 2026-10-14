package ai.arena.qatar.render;

import ai.arena.qatar.vehicle.CarBody;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shapes the player is: their sizes are the sizes of real machines, and they are asserted as such.
 *
 * <p>A body that is the wrong size is not a crash, it is a game that looks wrong -- a person as tall as a
 * bus, a wing inside the fuselage, a boat floating a metre above the water. None of that is visible
 * without a screen, so it is checked here: dimensions against the published figures, symmetry about the
 * centreline, which end is the front, whether the wheels, skids and hull bottom actually reach the ground
 * or the water they are standing on -- and that the parts which turn, turn where they are.
 */
final class BodyMesherTest {

    private static final double TOLERANCE = 0.06;

    @Test
    @DisplayName("every body is painted geometry the shader can colour")
    void bodiesAreBuilt() {
        for (BodyMesher.Body body : BodyMesher.Body.values()) {
            MeshData mesh = BodyMesher.mesh(body);
            assertFalse(mesh.isEmpty(), body + " has no geometry");
            assertTrue(BodyMesher.triangles(body) >= 60, body + " is too simple to read: "
                    + BodyMesher.triangles(body) + " triangles");
            assertTrue(BodyMesher.triangles(body) <= 250, body + " costs more than it needs: "
                    + BodyMesher.triangles(body) + " triangles");

            Set<Integer> used = new LinkedHashSet<>();
            checkPainted(body + " body", mesh, used);
            for (BodyMesher.Part part : BodyMesher.parts(body)) {
                assertFalse(part.mesh.isEmpty(), body + "'s " + part.name + " has no geometry");
                checkPainted(body + "'s " + part.name, part.mesh, used);
            }
            assertTrue(used.size() >= 3, body + " is painted in fewer than three colours: " + used);
        }
    }

    private static void checkPainted(String what, MeshData mesh, Set<Integer> used) {
        float[] v = mesh.vertices();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int base = i * MeshData.FLOATS_PER_VERTEX;
            assertEquals(VertexFormat.SURFACE_BODY, (int) (v[base + 6] + 0.5f),
                    what + " has a part that is not part of a body");
            int material = (int) (v[base + 10] + 0.5f);
            assertTrue(material >= 1 && material <= 11, what + " uses paint " + material);
            used.add(material);
            // Normals are unit length: a face pointing nowhere would be shaded as if lit from inside.
            float nx = v[base + 3];
            float ny = v[base + 4];
            float nz = v[base + 5];
            assertEquals(1.0f, Math.sqrt(nx * nx + ny * ny + nz * nz), 1e-3f,
                    what + " has a face with a broken normal");
        }
    }

    @Test
    @DisplayName("a body is placed at its own contact height, not at sea level")
    void bodiesArePlacedAtTheirOwnHeight() {
        // Every body mesh here is modelled with y = 0 at whatever it stands on, and the vehicle bodies
        // keep that contact height -- feet, tyres, waterline, wheels -- in VehicleState.heightM. This
        // transform used to place the body at y = 0 whatever the height was, which is sea level: a
        // player walking across Doha at 10 m of elevation was under the ground and invisible, and a car
        // on the sabkha had no relationship to its own shadow. The height is a required argument now,
        // and this is the assertion that keeps it from being dropped again.
        Matrix4 atTwelveMetres = BodyMesher.transform(500.0, 12.0, -300.0, 45.0);
        Vector3 contact = new Vector3(0f, 0f, 0f).mul(atTwelveMetres);
        assertEquals(500.0, contact.x, 1e-3, "the body is not at its east position");
        assertEquals(12.0, contact.y, 1e-3, "the body is not standing at its own height");
        assertEquals(-300.0, contact.z, 1e-3, "the body is not at its north position");

        // And a point above the contact -- a wheel centre, a shoulder -- keeps its offset from it, so
        // the machine is neither sunk into the ground nor floating over it.
        Vector3 wheelCentre = new Vector3(0.8f, 0.33f, 1.3f).mul(atTwelveMetres);
        assertEquals(12.33, wheelCentre.y, 1e-3, "the wheel is not 0.33 m above the contact point");

        // A moving part inherits the same height: a wheel that spans the ground when the car is on the
        // ground spans the water when it is in the water, and nothing is placed by hand.
        // The same through a real moving part, taken from a real machine: a car wheel, whose pivot is
        // the axle 0.33 m above the ground. Its contact point must land 7.83 m up when the car is at
        // 7.5 m, whatever the machine is doing.
        BodyMesher.Part wheel = null;
        for (BodyMesher.Part candidate : BodyMesher.parts(BodyMesher.Body.CAR)) {
            if (candidate.name != null && candidate.name.contains("wheel")) {
                wheel = candidate;
                break;
            }
        }
        assertNotNull(wheel, "a car has wheels to check");
        Matrix4 partAtHeight = BodyMesher.transform(wheel, 0.0, 7.5, 0.0, 0.0, 0.0);
        // A part's own origin is its pivot, which for a wheel is the axle: on a car at 7.5 m the axle
        // is at 7.83 m, and the tyre's contact patch -- one wheel radius below the axle, and the part
        // mesh is built around the axle -- is back on 7.5 m. The point of the assertion is that the
        // body's height reaches the part at all; the wheel was right when the body was wrong.
        assertEquals(7.5 + wheel.offsetY, new Vector3(0f, 0f, 0f).mul(partAtHeight).y, 1e-3,
                "the wheel's axle is not one radius above the body's contact point");
        Vector3 patch = new Vector3(0f, (float) -CarBody.WHEEL_RADIUS_M, 0f).mul(partAtHeight);
        assertEquals(7.5, patch.y, 1e-3, "a moving part ignores the body's height");
    }

    @Test
    @DisplayName("a person is 1.75 m tall and stands on their feet, legs included")
    void humanIsAHeight() {
        // The person is a body plus four limbs now: the torso and head do not move, and the legs and
        // arms swing from their joints. So the height a person has is the height of everything that
        // makes them up, and the feet are at the bottom of a leg rather than of the mesh.
        MeshData mesh = BodyMesher.mesh(BodyMesher.Body.HUMAN);
        float[] b = mesh.bounds();
        float minY = b[1];
        float maxY = b[4];
        float minX = b[0];
        float maxX = b[3];
        for (BodyMesher.Part part : BodyMesher.parts(BodyMesher.Body.HUMAN)) {
            float[] p = part.mesh.bounds();
            // A part's own bounds are in the body's frame once its pivot is added.
            minY = Math.min(minY, p[1] + (float) part.offsetY);
            maxY = Math.max(maxY, p[4] + (float) part.offsetY);
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[3]);
        }
        assertEquals(1.75, maxY, TOLERANCE, "the person is not 1.75 m tall");
        assertEquals(0.0, minY, 0.01, "the person does not stand on the ground");
        assertEquals(0.0, minX + maxX, 0.01, "the person is not centred on their own spine");
        assertTrue(maxX - minX < 0.60, "the person is wider than a person: " + (maxX - minX));

        List<BodyMesher.Part> limbs = BodyMesher.parts(BodyMesher.Body.HUMAN);
        assertEquals(4, limbs.size(), "two legs and two arms swing: " + limbs.size());
    }

    @Test
    @DisplayName("walking swings one leg forward while the other goes back, feet near the ground")
    void legsSwingWhenWalking() {
        List<BodyMesher.Part> limbs = BodyMesher.parts(BodyMesher.Body.HUMAN);
        BodyMesher.Part left = limbs.get(0);
        BodyMesher.Part right = limbs.get(1);
        assertEquals("left leg", left.name, "the first limb is the left leg");
        assertEquals("right leg", right.name, "and the second is the right leg");
        assertTrue(left.swingDegrees > 10 && right.swingDegrees > 10,
                "a leg swings rather than turning all the way round: " + left.swingDegrees);

        // A quarter of the way through a stride cycle both legs are at full swing, and they are at full
        // swing in *opposite* directions: that is what a stride is, and it is the whole reason the two
        // legs are given opposite phases instead of the same one.
        assertEquals(BodyMesher.LEG_SWING_DEGREES, left.angleDegrees(90.0), 0.01,
                "the left leg is not fully forward at a quarter cycle");
        assertEquals(-BodyMesher.LEG_SWING_DEGREES, right.angleDegrees(90.0), 0.01,
                "the right leg should be fully back when the left is forward");
        // Half a cycle later they have changed places, and through the whole cycle the angle stays
        // inside its amplitude: a leg is not a wheel and never goes round.
        assertEquals(-BodyMesher.LEG_SWING_DEGREES, left.angleDegrees(270.0), 0.01,
                "the left leg is not fully back half a cycle later");
        for (double phase = 0; phase < 360.0; phase += 5.0) {
            assertTrue(Math.abs(left.angleDegrees(phase)) <= BodyMesher.LEG_SWING_DEGREES + 1e-6,
                    "a leg is turned " + left.angleDegrees(phase) + " degrees at phase " + phase);
        }

        // And the foot stays near the ground while it swings. A limb mesh is built around its own joint,
        // so the foot is the lowest point of the leg in the leg's own frame -- and after a full swing
        // it should have moved forwards or backwards by a stride's worth and risen only a few
        // centimetres, which is what a step looks like rather than what a swim looks like.
        Vector3 footAtRest = new Vector3(0f, (float) -BodyMesher.HIP_HEIGHT_M, 0f);
        Vector3 rest = new Vector3(footAtRest).mul(BodyMesher.transform(left, 0.0, 0.0, 0.0, 0.0, 0.0));
        Vector3 swung = new Vector3(footAtRest)
                .mul(BodyMesher.transform(left, 0.0, 0.0, 0.0, 0.0, 90.0));
        assertEquals(0.0, rest.y, 0.01, "a leg at rest does not reach the ground");
        double forward = Math.abs(swung.z - rest.z);
        assertTrue(forward > 0.20, "the foot has not moved fore or aft: " + forward);
        assertTrue(swung.y > -0.02 && swung.y < 0.15,
                "the foot left the ground: " + swung.y + " m above it");
    }

    @Test
    @DisplayName("a car is 4.60 m long, 1.42 m tall, and its crew sits behind the middle")
    void carIsTheSizeOfACar() {
        MeshData mesh = BodyMesher.mesh(BodyMesher.Body.CAR);
        float[] b = mesh.bounds();
        assertEquals(CarBody.CAR_LENGTH_M, b[5] - b[2], TOLERANCE,
                "the car is not as long as the car physics thinks it is");
        assertEquals(1.42, b[4], TOLERANCE, "the roof is not 1.42 m off the ground");
        assertTrue(b[1] > 0.2 && b[1] <= BodyMesher.WHEEL_RADIUS_M + 0.02,
                "the hull is not riding on its wheels: the floor is at " + b[1] + " m");
        assertEquals(0.0, b[0] + b[3], 0.001, "the car is not symmetric about its centreline");
        assertTrue(b[3] - b[0] > 1.7 && b[3] - b[0] < 2.2, "the car is not 1.7-2.2 m wide: "
                + (b[3] - b[0]));

        // Glass is the greenhouse, and the greenhouse is behind the nose: z is forward.
        double glassZ = meanZ(mesh, BodyMesher.MATERIAL_GLASS);
        assertTrue(glassZ < 0, "the cabin is in front of the driver: " + glassZ);

        // Four wheels, each centred on its own axle, each rim exactly touching the road: the running
        // gear is the physics' running gear, because the mesh has to roll on the same wheelbase, track
        // and radius the model yaws on or the car skates sideways.
        assertEquals(CarBody.WHEELBASE_M, BodyMesher.WHEELBASE_M, 1e-9,
                "the mesher and the physics disagree about the wheelbase");
        assertEquals(CarBody.TRACK_M, BodyMesher.TRACK_M, 1e-9,
                "the mesher and the physics disagree about the track");
        assertEquals(CarBody.WHEEL_RADIUS_M, BodyMesher.WHEEL_RADIUS_M, 1e-9,
                "the mesher and the physics disagree about the wheel radius");
        List<BodyMesher.Part> wheels = BodyMesher.parts(BodyMesher.Body.CAR);
        assertEquals(4, wheels.size(), "the car does not have four wheels");
        Set<String> contactPoints = new LinkedHashSet<>();
        double front = Double.NEGATIVE_INFINITY;
        double rear = Double.POSITIVE_INFINITY;
        for (BodyMesher.Part wheel : wheels) {
            assertEquals(BodyMesher.AXIS_X, wheel.axis,
                    "a car wheel does not turn about its axle");
            assertEquals(BodyMesher.WHEEL_RADIUS_M, wheel.offsetY, 1e-6,
                    "a wheel is not one radius above the ground");
            front = Math.max(front, wheel.offsetNorth);
            rear = Math.min(rear, wheel.offsetNorth);
            assertEquals(BodyMesher.TRACK_M, Math.abs(wheel.offsetEastM) * 2, 0.01,
                    "the two wheels on one axle are not a track apart");
            assertEquals(BodyMesher.WHEELBASE_M * 0.5, Math.abs(wheel.offsetNorth), 0.01,
                    "an axle is not half a wheelbase from the middle of the car");
            assertTrue(Math.abs(wheel.offsetEastM) + wheel.mesh.bounds()[3] <= b[3] + 0.01,
                    "a wheel sticks out past the bodywork");
            double[] rim = rimRadii(wheel.mesh);
            assertEquals(BodyMesher.WHEEL_RADIUS_M, rim[0], 0.01, "the wheel is not round");
            assertEquals(rim[0], rim[1], 0.02,
                    "the rim is not a circle: radius varies by " + (rim[1] - rim[0]));
            contactPoints.add(String.format(java.util.Locale.ROOT, "%.2f,%.2f", wheel.offsetEastM,
                    wheel.offsetNorth));
        }
        assertEquals(4, contactPoints.size(), "two wheels are in the same place: " + contactPoints);
        assertEquals(BodyMesher.WHEELBASE_M, front - rear, 0.01,
                "the axles are not a wheelbase apart");
    }

    @Test
    @DisplayName("an aeroplane has an 11 m wing, an 8.28 m fuselage and a propeller at the front")
    void planeHasWings() {
        BodyMesher.Body kind = BodyMesher.Body.PLANE;
        MeshData mesh = BodyMesher.mesh(kind);
        float[] b = mesh.bounds();
        assertEquals(11.00, b[3] - b[0], TOLERANCE, "the wing is not a Cessna's 11 m");
        assertEquals(8.28, spanZ(mesh, BodyMesher.MATERIAL_AIRCRAFT)[0], TOLERANCE,
                "the fuselage is not 8.28 m long");
        assertTrue(b[4] > 2.4, "the fin does not stand up: " + b[4]);
        assertEquals(0.0, b[1], 0.001, "the aeroplane does not stand on its gear");
        assertEquals(0.0, b[0] + b[3], 0.001, "the two wings are not the same length");

        float wingTip = 0f;
        float fuselageHalf = 0f;
        float[] v = mesh.vertices();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int base = i * MeshData.FLOATS_PER_VERTEX;
            if ((int) (v[base + 10] + 0.5f) == BodyMesher.MATERIAL_AIRCRAFT) {
                if (v[base + 1] > 1.3f && v[base + 1] < 1.6f && Math.abs(v[base + 2]) < 1f) {
                    wingTip = Math.max(wingTip, Math.abs(v[base]));
                }
                if (v[base + 1] < 0.7f) {
                    fuselageHalf = Math.max(fuselageHalf, Math.abs(v[base]));
                }
            }
        }
        assertTrue(wingTip > 5.0f, "the wing does not reach the tip: " + wingTip);
        assertTrue(fuselageHalf > 0.5f && fuselageHalf < 1.0f, "the fuselage is "
                + fuselageHalf + " m in half-width");

        // The propeller is a part of its own, on the nose, turning about the fuselage.
        List<BodyMesher.Part> parts = BodyMesher.parts(kind);
        assertEquals(1, parts.size(), "an aeroplane has one propeller");
        BodyMesher.Part propeller = parts.get(0);
        assertEquals(BodyMesher.AXIS_Z, propeller.axis, "the propeller does not turn about the nose");
        assertTrue(propeller.offsetNorth > 4.0, "the propeller is not on the nose: "
                + propeller.offsetNorth);
        assertTrue(propeller.offsetY > 0.4 && propeller.offsetY < 1.2,
                "the propeller is not on the engine axis: " + propeller.offsetY);
    }

    @Test
    @DisplayName("a helicopter has a 5.6 m rotor, stands on skids and carries its tail out the back")
    void helicopterSpinsTheRotor() {
        BodyMesher.Body kind = BodyMesher.Body.HELICOPTER;
        MeshData mesh = BodyMesher.mesh(kind);
        float[] b = mesh.bounds();
        assertEquals(0.0, b[1], 0.001, "the helicopter does not stand on its skids");
        assertTrue(b[5] - b[2] > 4.5, "the helicopter is shorter than its own tail boom");

        List<BodyMesher.Part> parts = BodyMesher.parts(kind);
        assertEquals(2, parts.size(), "a helicopter has a main rotor and a tail rotor");
        BodyMesher.Part main = parts.get(0);
        BodyMesher.Part tail = parts.get(1);
        assertEquals(BodyMesher.AXIS_Y, main.axis, "the main rotor does not turn about the mast");
        assertEquals(BodyMesher.AXIS_X, tail.axis, "the tail rotor does not turn about its shaft");
        assertTrue(tail.rateScale > 1.0, "the tail rotor turns no faster than the main rotor");
        assertEquals(5.60, spanX(main.mesh), TOLERANCE, "the rotor disc is not 5.60 m across");
        // The highest thing under the disc is the cabin roof: the rotor turns clear above it, and the
        // fin still stands above the rotor.
        double under = 0;
        float[] v = mesh.vertices();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int base = i * MeshData.FLOATS_PER_VERTEX;
            if (Math.abs(v[base]) <= 2.8f && Math.abs(v[base + 2]) <= 2.8f) {
                under = Math.max(under, v[base + 1]);
            }
        }
        assertTrue(main.offsetY > under, "the rotor turns inside the cabin: hub at " + main.offsetY
                + " m, the cabin reaches " + under + " m");
        assertTrue(main.offsetY < b[4], "the rotor hub is level with the top of the fin");
        assertTrue(spanX(main.mesh) > spanX(mesh), "the rotor is narrower than the machine");
    }

    @Test
    @DisplayName("a boat floats: hull below the waterline, cabin above it")
    void boatFloats() {
        MeshData mesh = BodyMesher.mesh(BodyMesher.Body.BOAT);
        float[] b = mesh.bounds();
        assertEquals(6.50, spanZ(mesh, BodyMesher.MATERIAL_HULL)[0], TOLERANCE,
                "the hull is not 6.50 m long");
        assertEquals(2.10, b[3] - b[0], TOLERANCE, "the beam is not 2.10 m");
        assertTrue(b[1] < -0.30, "nothing of the hull is under the waterline: " + b[1]);
        assertTrue(b[4] > 1.8, "the cabin does not stand above the deck: " + b[4]);
        assertTrue(meanZ(mesh, BodyMesher.MATERIAL_STEEL) < -3.0, "the outboard is not astern");
        assertTrue(meanZ(mesh, BodyMesher.MATERIAL_DECK) > -0.5, "the deck is not in the boat");
        assertTrue(BodyMesher.parts(BodyMesher.Body.BOAT).isEmpty(),
                "a boat has something that spins above the water");
    }

    @Test
    @DisplayName("a wheel turns on its axle and a limb on its joint, and neither drifts off it")
    void partsTurnWhereTheyAre() {
        for (BodyMesher.Body kind : BodyMesher.Body.values()) {
            for (BodyMesher.Part part : BodyMesher.parts(kind)) {
                float[] b = part.mesh.bounds();
                if (part.swingDegrees == 0.0) {
                    // Something that goes round and round has to be balanced across its own axis, or the
                    // wheel, the propeller or the rotor would wobble its way around instead of turning.
                    switch (part.axis) {
                        case BodyMesher.AXIS_X:
                            assertEquals(0.0, b[1] + b[4], 0.05, part.name + " is not centred vertically");
                            assertEquals(0.0, b[2] + b[5], 0.05, part.name + " is not centred lengthways");
                            break;
                        case BodyMesher.AXIS_Z:
                            assertEquals(0.0, b[0] + b[3], 0.05, part.name + " is not centred sideways");
                            assertEquals(0.0, b[1] + b[4], 0.05, part.name + " is not centred vertically");
                            break;
                        default:
                            assertEquals(0.0, b[0] + b[3], 0.05, part.name + " is not centred sideways");
                            assertEquals(0.0, b[2] + b[5], 0.05, part.name + " is not centred lengthways");
                            break;
                    }
                } else {
                    // A limb swings, and a limb is made downward from its joint: the pivot is its top,
                    // not its middle. A leg balanced on its hip would be half buried in the torso.
                    assertEquals(0.0, b[4], 0.05,
                            part.name + " does not hang from its joint: its top is " + b[4] + " m");
                    assertTrue(b[1] < -0.15,
                            part.name + " hangs nowhere below its joint: its lowest point is " + b[1] + " m");
                }
                // And whatever the spin, the part's pivot stays exactly where the body put it.
                for (double spin : new double[]{0.0, 37.5, 180.0, 359.0}) {
                    Vector3 pivot = new Vector3(0f, 0f, 0f)
                            .mul(BodyMesher.transform(part, 120.0, 0.0, -45.0, 90.0, spin));
                    assertEquals(120.0 + part.offsetNorth, pivot.x, 1e-3,
                            part.name + " moved sideways when it turned: " + pivot.x);
                    assertEquals(part.offsetY, pivot.y, 1e-3,
                            part.name + " changed height when it turned: " + pivot.y);
                    assertEquals(-45.0 - part.offsetEastM, pivot.z, 1e-3,
                            part.name + " moved along the body when it turned: " + pivot.z);
                }
            }
        }
    }

    @Test
    @DisplayName("the transform puts a body where it is and turns it to its heading")
    void transformPlacesAndTurnsTheBody() {
        Matrix4 north = BodyMesher.transform(120.0, 0.0, -45.0, 0.0);
        Vector3 nose = new Vector3(0f, 0f, 2f).mul(north);
        assertEquals(120.0, nose.x, 1e-3, "heading 0 does not face north");
        assertEquals(-45.0 + 2.0, nose.z, 1e-3, "heading 0 does not face north");
        assertEquals(0.0, nose.y, 1e-3, "the body is not on the ground");

        Vector3 east = new Vector3(0f, 0f, 2f).mul(BodyMesher.transform(0.0, 0.0, 0.0, 90.0));
        assertEquals(2.0, east.x, 1e-3, "heading 90 does not face east");
        assertEquals(0.0, east.z, 1e-3, "heading 90 does not face east");

        Vector3 right = new Vector3(1f, 0f, 0f).mul(BodyMesher.transform(0.0, 0.0, 0.0, 0.0));
        assertEquals(1.0, right.x, 1e-3, "the body's own x axis is not east when it faces north");
    }

    @Test
    @DisplayName("the shader has a colour for every paint the bodies use")
    void shaderKnowsEveryPaint() {
        String fragment = ShaderSources.FRAGMENT;
        String body = fragment.substring(fragment.indexOf("vec3 bodyColour"));
        body = body.substring(0, body.indexOf('}'));
        for (int material : BodyMesher.MATERIALS) {
            assertTrue(body.contains("if (id == " + material + ")"),
                    "the shader paints no body part with paint " + material);
        }
        assertTrue(fragment.contains("SURFACE_BODY = " + VertexFormat.SURFACE_BODY + ";"),
                "the shader and VertexFormat disagree about the body surface code");
        assertTrue(fragment.contains("bodyColour"), "the shader never paints a body");
    }

    /** Smallest and largest distance of a rim vertex from its own axis, for "is it round" checks. */
    private static double[] rimRadii(MeshData mesh) {
        float[] v = mesh.vertices();
        double min = Double.POSITIVE_INFINITY;
        double max = 0;
        int found = 0;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int base = i * MeshData.FLOATS_PER_VERTEX;
            double radius = Math.hypot(v[base + 1], v[base + 2]);
            if (radius > 0.01) {
                min = Math.min(min, radius);
                max = Math.max(max, radius);
                found++;
            }
        }
        assertTrue(found >= 6, "the wheel has no rim");
        return new double[]{min, max};
    }

    /** Sideways extent of a mesh, for "is the wing wider than the fuselage" checks. */
    private static double spanX(MeshData mesh) {
        float[] b = mesh.bounds();
        return b[3] - b[0];
    }

    /**
     * Forward span, in metres, of the vertices painted with one material: {length, minZ, maxZ}. Used
     * wherever the whole mesh is longer than the part being measured -- a propeller, an outboard.
     */
    private static double[] spanZ(MeshData mesh, int material) {
        float[] v = mesh.vertices();
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int count = 0;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int base = i * MeshData.FLOATS_PER_VERTEX;
            if ((int) (v[base + 10] + 0.5f) == material) {
                min = Math.min(min, v[base + 2]);
                max = Math.max(max, v[base + 2]);
                count++;
            }
        }
        assertTrue(count > 0, "no part of the body is painted " + material);
        return new double[]{max - min, min, max};
    }

    /** Mean forward position of the vertices carrying one material, for "which end is this on" checks. */
    private static double meanZ(MeshData mesh, int material) {
        float[] v = mesh.vertices();
        double sum = 0;
        int count = 0;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int base = i * MeshData.FLOATS_PER_VERTEX;
            if ((int) (v[base + 10] + 0.5f) == material) {
                sum += v[base + 2];
                count++;
            }
        }
        assertTrue(count > 0, "no part of the body is painted " + material);
        return sum / count;
    }
}
