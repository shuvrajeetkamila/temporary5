package ai.arena.qatar.render;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shapes the player is: their sizes are the sizes of real machines, and they are asserted as such.
 *
 * <p>A body that is the wrong size is not a crash, it is a game that looks wrong -- a person as tall as a
 * bus, a wing inside the fuselage, a boat floating a metre above the water. None of that is visible
 * without a screen, so it is checked here: dimensions against the published figures, symmetry about the
 * centreline, which end is the front, and whether the wheels, skids and hull bottom actually reach the
 * ground or the water they are standing on.
 */
final class BodyMesherTest {

    private static final double TOLERANCE = 0.06;

    @Test
    @DisplayName("every body is painted geometry the shader can colour")
    void bodiesAreBuilt() {
        for (BodyMesher.Body body : BodyMesher.Body.values()) {
            MeshData mesh = BodyMesher.mesh(body);
            assertFalse(mesh.isEmpty(), body + " has no geometry");
            assertTrue(mesh.triangleCount() >= 60, body + " is too simple to read: "
                    + mesh.triangleCount() + " triangles");
            assertTrue(mesh.triangleCount() <= 200, body + " costs more than it needs: "
                    + mesh.triangleCount() + " triangles");

            float[] v = mesh.vertices();
            Set<Integer> used = new LinkedHashSet<>();
            for (int i = 0; i < mesh.vertexCount(); i++) {
                int base = i * MeshData.FLOATS_PER_VERTEX;
                assertEquals(VertexFormat.SURFACE_BODY, (int) (v[base + 6] + 0.5f),
                        body + " has a part that is not part of a body");
                int material = (int) (v[base + 10] + 0.5f);
                assertTrue(material >= 1 && material <= 11, body + " uses paint " + material);
                used.add(material);
                // Normals are unit length: a face pointing nowhere would be shaded as if lit from inside.
                float nx = v[base + 3];
                float ny = v[base + 4];
                float nz = v[base + 5];
                assertEquals(1.0f, Math.sqrt(nx * nx + ny * ny + nz * nz), 1e-3f,
                        body + " has a face with a broken normal");
            }
            assertTrue(used.size() >= 3, body + " is painted in fewer than three colours: " + used);
        }
    }

    @Test
    @DisplayName("a person is 1.75 m tall and stands on their feet")
    void humanIsAHeight() {
        MeshData mesh = BodyMesher.mesh(BodyMesher.Body.HUMAN);
        float[] b = mesh.bounds();
        assertEquals(1.75, b[4], TOLERANCE, "the person is not 1.75 m tall");
        assertEquals(0.0, b[1], 0.001, "the person does not stand on the ground");
        assertEquals(0.0, b[0] + b[3], 0.001, "the person is not centred on their own spine");
        assertTrue(b[3] - b[0] < 0.60, "the person is wider than a person: " + (b[3] - b[0]));
    }

    @Test
    @DisplayName("a car is 4.60 m long, 1.42 m tall, and its crew sits behind the middle")
    void carIsTheSizeOfACar() {
        MeshData mesh = BodyMesher.mesh(BodyMesher.Body.CAR);
        float[] b = mesh.bounds();
        assertEquals(4.60, b[5] - b[2], TOLERANCE, "the car is not 4.60 m long");
        assertEquals(1.42, b[4] - b[1], TOLERANCE, "the car is not 1.42 m tall");
        assertEquals(0.0, b[1], 0.001, "the car does not stand on its wheels");
        assertEquals(0.0, b[0] + b[3], 0.001, "the car is not symmetric about its centreline");
        assertTrue(b[3] - b[0] > 1.8 && b[3] - b[0] < 2.2, "the car is not 1.8-2.2 m wide: "
                + (b[3] - b[0]));

        // Glass is the greenhouse, and the greenhouse is behind the nose: z is forward.
        double glassZ = meanZ(mesh, BodyMesher.MATERIAL_GLASS);
        assertTrue(glassZ < 0, "the cabin is in front of the driver: " + glassZ);

        // Four wheels, each touching the ground, front pair and rear pair the same distance out.
        int touching = 0;
        float[] v = mesh.vertices();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int base = i * MeshData.FLOATS_PER_VERTEX;
            if ((int) (v[base + 10] + 0.5f) == BodyMesher.MATERIAL_TYRE && Math.abs(v[base + 1]) < 0.01f) {
                touching++;
            }
        }
        assertEquals(16, touching, "the car does not sit on four wheels");
    }

    @Test
    @DisplayName("an aeroplane has an 11 m wing, an 8.28 m fuselage and a propeller at the front")
    void planeHasWings() {
        MeshData mesh = BodyMesher.mesh(BodyMesher.Body.PLANE);
        float[] b = mesh.bounds();
        assertEquals(11.00, b[3] - b[0], TOLERANCE, "the wing is not a Cessna's 11 m");
        assertEquals(8.28, b[5] - b[2], TOLERANCE, "the fuselage is not 8.28 m long");
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

        // The propeller is the furthest thing forward, and the tail is the furthest back.
        assertTrue(meanZ(mesh, BodyMesher.MATERIAL_STEEL) > 4.0, "the propeller is not on the nose");
    }

    @Test
    @DisplayName("a helicopter has a 5.6 m rotor, stands on skids and carries its tail out the back")
    void helicopterSpinsTheRotor() {
        MeshData mesh = BodyMesher.mesh(BodyMesher.Body.HELICOPTER);
        float[] b = mesh.bounds();
        assertEquals(5.60, b[3] - b[0], TOLERANCE, "the rotor disc is not 5.60 m across");
        assertEquals(0.0, b[1], 0.001, "the helicopter does not stand on its skids");
        assertTrue(b[5] - b[2] > 4.5, "the helicopter is shorter than its own tail boom");

        float rotorTop = Float.NEGATIVE_INFINITY;
        float cabinTop = Float.NEGATIVE_INFINITY;
        float[] v = mesh.vertices();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int base = i * MeshData.FLOATS_PER_VERTEX;
            int material = (int) (v[base + 10] + 0.5f);
            if (material == BodyMesher.MATERIAL_ROTOR && v[base + 2] > -1f) {
                rotorTop = Math.max(rotorTop, v[base + 1]);
            }
            if (material == BodyMesher.MATERIAL_HELI) {
                cabinTop = Math.max(cabinTop, v[base + 1]);
            }
        }
        assertTrue(rotorTop > cabinTop, "the rotor is below the cabin roof");
        assertTrue(meanZ(mesh, BodyMesher.MATERIAL_HELI) < 0.5, "the body is not where it should be");
    }

    @Test
    @DisplayName("a boat floats: hull below the waterline, cabin above it")
    void boatFloats() {
        MeshData mesh = BodyMesher.mesh(BodyMesher.Body.BOAT);
        float[] b = mesh.bounds();
        assertEquals(6.50, b[5] - b[2], TOLERANCE, "the hull is not 6.50 m long");
        assertEquals(2.10, b[3] - b[0], TOLERANCE, "the beam is not 2.10 m");
        assertTrue(b[1] < -0.30, "nothing of the hull is under the waterline: " + b[1]);
        assertTrue(b[4] > 1.8, "the cabin does not stand above the deck: " + b[4]);
        assertTrue(meanZ(mesh, BodyMesher.MATERIAL_STEEL) < -3.0, "the outboard is not astern");
        assertTrue(meanZ(mesh, BodyMesher.MATERIAL_DECK) > -0.5, "the deck is not in the boat");
    }

    @Test
    @DisplayName("the transform puts a body where it is and turns it to its heading")
    void transformPlacesAndTurnsTheBody() {
        Matrix4 north = BodyMesher.transform(120.0, -45.0, 0.0);
        Vector3 nose = new Vector3(0f, 0f, 2f).mul(north);
        assertEquals(120.0, nose.x, 1e-3, "heading 0 does not face north");
        assertEquals(-45.0 + 2.0, nose.z, 1e-3, "heading 0 does not face north");
        assertEquals(0.0, nose.y, 1e-3, "the body is not on the ground");

        Vector3 east = new Vector3(0f, 0f, 2f).mul(BodyMesher.transform(0.0, 0.0, 90.0));
        assertEquals(2.0, east.x, 1e-3, "heading 90 does not face east");
        assertEquals(0.0, east.z, 1e-3, "heading 90 does not face east");

        Vector3 left = new Vector3(1f, 0f, 0f).mul(BodyMesher.transform(0.0, 0.0, 0.0));
        assertEquals(1.0, left.x, 1e-3, "the body's own right is not east when facing north");
    }

    @Test
    @DisplayName("the shader has a colour for every paint the bodies use")
    void shaderKnowsEveryPaint() {
        String fragment = ShaderSources.FRAGMENT;
        for (int material : BodyMesher.MATERIALS) {
            assertTrue(fragment.contains("if (id == " + material + ")"),
                    "the shader has no colour for paint " + material);
        }
        assertTrue(fragment.contains("SURFACE_BODY = " + VertexFormat.SURFACE_BODY + ";"),
                "the shader and VertexFormat disagree about the body surface code");
        assertTrue(fragment.contains("bodyColour"), "the shader never paints a body");
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
