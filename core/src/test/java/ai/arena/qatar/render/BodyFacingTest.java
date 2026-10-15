package ai.arena.qatar.render;

import ai.arena.qatar.physics.SurfaceMaterial;
import ai.arena.qatar.vehicle.AircraftBody;
import ai.arena.qatar.vehicle.BoatBody;
import ai.arena.qatar.vehicle.CarBody;
import ai.arena.qatar.vehicle.HelicopterBody;
import ai.arena.qatar.vehicle.HumanBody;
import ai.arena.qatar.vehicle.Surface;
import ai.arena.qatar.vehicle.VehicleBody;
import ai.arena.qatar.vehicle.VehicleInput;
import ai.arena.qatar.vehicle.VehicleState;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every body points where it is going.
 *
 * <p>This is the seam nothing else covers: the simulation works in east/north metres and a heading in
 * degrees, the renderer turns that into a matrix, and each mesh was modelled nose-along-+Z by hand. Three
 * separate conventions, each sensible on its own, and a mistake in any of them produces a machine that
 * drives, flies or sails <em>sideways</em> -- which is what "the physics is wrong" looks like from the
 * outside, and what no amount of reading the dynamics code will find.
 *
 * <p>So this test takes the body's own transform, turns the mesh's nose direction through it, and
 * compares the result with the distance the body actually moved. It is a test about a rendering matrix
 * that its own package would never think to write, because the thing it protects is physics.
 */
final class BodyFacingTest {

    private static final double DT = 1.0 / 60.0;
    private static final Surface ASPHALT = Surface.land(0, SurfaceMaterial.ID_DRY_ASPHALT);
    private static final Surface SEA = Surface.water(-14.0, 14.0);

    /** One body, the surface it belongs on, and how it is asked to move forward. */
    private static final class Case {
        final String name;
        final VehicleBody body;
        final Surface surface;
        final VehicleInput input;

        Case(String name, VehicleBody body, Surface surface, VehicleInput input) {
            this.name = name;
            this.body = body;
            this.surface = surface;
            this.input = input;
        }
    }

    private static Case[] cases() {
        VehicleInput drive = new VehicleInput();
        drive.throttle = 1.0;

        VehicleInput sail = new VehicleInput();
        sail.throttle = 1.0;

        VehicleInput fly = new VehicleInput();
        fly.throttle = 1.0;

        VehicleInput hoverForward = new VehicleInput();
        hoverForward.collective = 0.8;
        hoverForward.pitch = 0.6;

        return new Case[]{
                new Case("the person", new HumanBody(), ASPHALT, walk()),
                new Case("the car", new CarBody(), ASPHALT, drive),
                new Case("the aeroplane", new AircraftBody(), ASPHALT, fly),
                new Case("the helicopter", new HelicopterBody(), ASPHALT, hoverForward),
                new Case("the boat", new BoatBody(), SEA, sail),
        };
    }

    private static VehicleInput walk() {
        VehicleInput input = new VehicleInput();
        input.throttle = 1.0;
        return input;
    }

    @Test
    @DisplayName("each body moves along the direction its own mesh is pointing")
    void bodiesMoveWhereTheyPoint() {
        int moved = 0;
        for (Case testCase : cases()) {
            VehicleState state = new VehicleState();
            testCase.body.spawn(state, 0, 0, testCase.surface, 37.0);      // an arbitrary heading
            double lastEast = 0;
            double lastNorth = 0;
            double deltaEast = 0;
            double deltaNorth = 0;
            for (double t = 0; t < 12.0; t += DT) {
                lastEast = state.eastM;
                lastNorth = state.northM;
                testCase.body.step(state, testCase.input, testCase.surface, DT);
                deltaEast = state.eastM - lastEast;
                deltaNorth = state.northM - lastNorth;
            }

            // Where the mesh says it is looking, through the same transform the renderer uses.
            Matrix4 transform = BodyMesher.transform(state.eastM, state.heightM, state.northM,
                    state.headingDegrees);
            Vector3 nose = new Vector3(0f, 0f, 1f).mul(transform).sub(
                    new Vector3((float) state.eastM, (float) state.heightM, (float) state.northM));

            double moved2 = Math.hypot(deltaEast, deltaNorth);
            if (moved2 < 1e-3) {
                continue;                       // not moving in this configuration: nothing to compare
            }
            moved++;
            double noseEast = nose.x;
            double noseNorth = nose.z;
            double noseLength = Math.hypot(noseEast, noseNorth);
            double alignment = (deltaEast * noseEast + deltaNorth * noseNorth) / (moved2 * noseLength);
            assertTrue(alignment > 0.9,
                    testCase.name + " moved (" + deltaEast + ", " + deltaNorth + ") m while its mesh "
                            + "pointed at (" + noseEast + ", " + noseNorth + "): cos = " + alignment
                            + ", so it is going sideways or backwards");
        }
        assertTrue(moved >= 4, "only " + moved + " of " + cases().length
                + " bodies moved at all: this test is not checking anything");
    }

    @Test
    @DisplayName("a heading of 90 degrees is east, 0 is north, 180 is south -- for every body")
    void headingsPointTheRightWay() {
        for (Case testCase : cases()) {
            for (double[] heading : new double[][]{{0, 0, 1}, {90, 1, 0}, {180, 0, -1}, {270, -1, 0}}) {
                VehicleState state = new VehicleState();
                testCase.body.spawn(state, 0, 0, testCase.surface, heading[0]);
                Matrix4 transform = BodyMesher.transform(0, 0, 0, state.headingDegrees);
                Vector3 nose = new Vector3(0f, 0f, 1f).mul(transform);
                assertEquals(heading[1], nose.x, 1e-4,
                        testCase.name + " at heading " + heading[0] + " does not point east");
                assertEquals(heading[2], nose.z, 1e-4,
                        testCase.name + " at heading " + heading[0] + " does not point north");
                assertEquals(0f, nose.y, 1e-4, testCase.name + " points at the sky");
            }
        }
    }

    @Test
    @DisplayName("every body's lowest point is its own contact plane, wheels and legs included")
    void bodiesStandOnTheirOwnContact() {
        // A body's y = 0 is where it touches the world: a tyre patch, a boat's waterline, a person's
        // soles. The renderer places the mesh by that height every frame, so a body modelled around its
        // own centre instead stands half a metre above the road or buried in it. This measures the whole
        // body -- the static mesh *and* every moving part at its pivot -- because the parts are where the
        // contact lives for two of the five: a car's wheels and a person's legs.
        for (BodyMesher.Body body : BodyMesher.Body.values()) {
            MeshData mesh = BodyMesher.mesh(body);
            assertTrue(mesh.vertexCount() > 0, body + " has no mesh at all");
            float lowest = mesh.bounds()[1];
            float highest = mesh.bounds()[4];
            for (BodyMesher.Part part : BodyMesher.parts(body)) {
                lowest = Math.min(lowest, (float) part.offsetY + part.mesh.bounds()[1]);
                highest = Math.max(highest, (float) part.offsetY + part.mesh.bounds()[4]);
            }
            assertTrue(lowest <= 0.02f, body + " is modelled from y = " + lowest
                    + " m upwards: it floats above its own contact patch by that much on every frame");
            assertTrue(lowest >= -0.5f, body + " is modelled down to y = " + lowest
                    + " m: it would be buried in the ground");
            assertTrue(highest > 0.5f, body + " is only " + highest + " m tall in total");
        }
    }
}
