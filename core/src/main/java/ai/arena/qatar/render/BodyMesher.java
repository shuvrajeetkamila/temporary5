package ai.arena.qatar.render;

import com.badlogic.gdx.math.Matrix4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The bodies the player actually is: a person, a car, an aeroplane, a helicopter and a boat.
 *
 * <p>Step 2 gave each of them real physics and a camera looking out of the front of them, which is
 * enough to move and not enough to see. This builds the missing half -- the shape -- as plain geometry,
 * in the body's own frame: origin on the ground under the middle, +z forward, +x to the right, metres
 * everywhere, so the sizes are the published sizes of real machines and can be asserted as such in CI.
 *
 * <p>Every part carries a material id from the paint table in the shader (car paint, glass, tyre,
 * aircraft white, teak deck, skin), which is how one surface type is red on one part and black on the
 * next without a texture anywhere in the project.
 *
 * <p>Parts that move are built separately, in coordinates centred on their own pivot, so they can spin
 * where they are instead of orbiting the body: four wheels about the axle, a propeller about the
 * fuselage, a main rotor about the mast and a tail rotor about its own shaft. See {@link #parts}.
 *
 * <p>Deliberately boxy. A person is seven boxes and a car is three plus four wheels, which reads
 * correctly at the distance a chase camera sits and costs tens of triangles instead of requiring a
 * modelling tool this project does not have. The silhouettes are right; the folds are not there, and
 * that is stated rather than hidden.
 */
public final class BodyMesher {

    /** Which body to build. */
    public enum Body {
        HUMAN,
        CAR,
        PLANE,
        HELICOPTER,
        BOAT
    }

    /** Paint table ids, matching {@code bodyColour()} in the shader. */
    public static final int MATERIAL_PAINT = 1;
    public static final int MATERIAL_GLASS = 2;
    public static final int MATERIAL_TYRE = 3;
    public static final int MATERIAL_AIRCRAFT = 4;
    public static final int MATERIAL_HELI = 5;
    public static final int MATERIAL_HULL = 6;
    public static final int MATERIAL_DECK = 7;
    public static final int MATERIAL_CLOTH = 8;
    public static final int MATERIAL_SKIN = 9;
    public static final int MATERIAL_STEEL = 10;
    public static final int MATERIAL_ROTOR = 11;

    /** Every id {@link #mesh} and {@link #parts} can emit; the test holds the shader's table to it. */
    public static final int[] MATERIALS = {MATERIAL_PAINT, MATERIAL_GLASS, MATERIAL_TYRE,
            MATERIAL_AIRCRAFT, MATERIAL_HELI, MATERIAL_HULL, MATERIAL_DECK, MATERIAL_CLOTH,
            MATERIAL_SKIN, MATERIAL_STEEL, MATERIAL_ROTOR};

    /** Spin axes, as indices into a rotation about that axis. */
    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_Z = 2;

    /**
     * The car's running gear, in metres: the same 0.33 m wheels, 1.57 m track and 2.79 m wheelbase the
     * car physics drives on. The mesh and the model have to agree or the car skates sideways.
     */
    public static final double WHEEL_RADIUS_M = 0.33;
    public static final double TRACK_M = 1.57;
    public static final double WHEELBASE_M = 2.79;

    /**
     * A part of a body that moves: its geometry is centred on its own pivot, and the caller rotates it
     * about {@link #axis} at {@code spinDegrees * rateScale} to make it turn where it is.
     */
    public static final class Part {
        public final String name;
        public final MeshData mesh;
        /** Pivot in the body's own frame, in metres. */
        public final double offsetEastM;
        public final double offsetY;
        public final double offsetNorth;
        public final int axis;
        /** Spins faster than the body's own rate when this is more than one: a tail rotor does. */
        public final double rateScale;

        Part(String name, MeshData mesh, double offsetEastM, double offsetY, double offsetNorth,
             int axis, double rateScale) {
            this.name = name;
            this.mesh = mesh;
            this.offsetEastM = offsetEastM;
            this.offsetY = offsetY;
            this.offsetNorth = offsetNorth;
            this.axis = axis;
            this.rateScale = rateScale;
        }
    }

    private BodyMesher() {
    }

    /** The geometry that does not move: everything except the wheels, propeller and rotors. */
    public static MeshData mesh(Body body) {
        switch (body) {
            case HUMAN:
                return human();
            case CAR:
                return car();
            case PLANE:
                return plane();
            case HELICOPTER:
                return helicopter();
            case BOAT:
                return boat();
            default:
                throw new IllegalArgumentException("no body for " + body);
        }
    }

    /** The parts that move. Empty for a person and a boat, which have nothing to turn. */
    public static List<Part> parts(Body body) {
        switch (body) {
            case CAR:
                return wheels();
            case PLANE:
                return Collections.singletonList(new Part("propeller", propeller(), 0.0, 0.76, 4.44,
                        AXIS_Z, 1.0));
            case HELICOPTER:
                List<Part> rotors = new ArrayList<>(2);
                rotors.add(new Part("main rotor", mainRotor(), 0.0, 1.59, 0.0, AXIS_Y, 1.0));
                rotors.add(new Part("tail rotor", tailRotor(), 0.0, 1.50, -3.40, AXIS_X, 2.5));
                return rotors;
            default:
                return Collections.emptyList();
        }
    }

    /** How many triangles a whole body costs, moving parts included. */
    public static int triangles(Body body) {
        int total = mesh(body).triangleCount();
        for (Part part : parts(body)) {
            total += part.mesh.triangleCount();
        }
        return total;
    }

    /**
     * Places a body in the local frame: east/north metres from the floating origin, and a heading in
     * degrees (0 faces north, 90 faces east), the same convention the bodies simulate in.
     */
    public static Matrix4 transform(double eastM, double northM, double headingDegrees) {
        return transform(new Matrix4(), eastM, northM, headingDegrees);
    }

    /** The same, into a matrix the caller reuses: this runs once per part per frame. */
    public static Matrix4 transform(Matrix4 out, double eastM, double northM, double headingDegrees) {
        out.setToTranslation((float) eastM, 0f, (float) northM);
        out.rotate(0f, 1f, 0f, (float) headingDegrees);
        return out;
    }

    /**
     * Places one moving part: the body's own transform, then out to the part's pivot, then the spin, so
     * the part turns where it is rather than orbiting the machine.
     */
    public static Matrix4 transform(Part part, double eastM, double northM, double headingDegrees,
                                    double spinDegrees) {
        return transform(new Matrix4(), part, eastM, northM, headingDegrees, spinDegrees);
    }

    /** The same, into a matrix the caller reuses. */
    public static Matrix4 transform(Matrix4 out, Part part, double eastM, double northM,
                                    double headingDegrees, double spinDegrees) {
        transform(out, eastM, northM, headingDegrees);
        out.translate((float) part.offsetEastM, (float) part.offsetY, (float) part.offsetNorth);
        switch (part.axis) {
            case AXIS_X:
                out.rotate(1f, 0f, 0f, (float) (spinDegrees * part.rateScale));
                break;
            case AXIS_Z:
                out.rotate(0f, 0f, 1f, (float) (spinDegrees * part.rateScale));
                break;
            default:
                out.rotate(0f, 1f, 0f, (float) (spinDegrees * part.rateScale));
                break;
        }
        return out;
    }

    // ------------------------------------------------------------------ the five bodies -----------

    /** Seven boxes: legs, torso, head, two arms, two shoes. 1.75 m tall. */
    private static MeshData human() {
        MeshData mesh = new MeshData();
        box(mesh, 0, 0.425, 0, 0.40, 0.85, 0.18, MATERIAL_CLOTH, 0.45f);    // legs, dark cloth
        box(mesh, 0, 1.175, 0, 0.44, 0.65, 0.24, MATERIAL_CLOTH, 1.0f);     // torso, white thobe
        box(mesh, 0, 1.625, 0, 0.19, 0.25, 0.19, MATERIAL_SKIN, 1.0f);      // head
        box(mesh, 0.215, 1.16, 0, 0.09, 0.52, 0.16, MATERIAL_CLOTH, 0.8f);  // arms
        box(mesh, -0.215, 1.16, 0, 0.09, 0.52, 0.16, MATERIAL_CLOTH, 0.8f);
        box(mesh, 0.10, 0.045, 0.04, 0.16, 0.09, 0.30, MATERIAL_TYRE, 1.0f);   // shoes
        box(mesh, -0.10, 0.045, 0.04, 0.16, 0.09, 0.30, MATERIAL_TYRE, 1.0f);
        return mesh;
    }

    /** Body, glass band and roof: 4.60 m long, 1.42 m tall. The wheels are {@link #wheels()}. */
    private static MeshData car() {
        MeshData mesh = new MeshData();
        box(mesh, 0, 0.54, 0, 1.82, 0.48, 4.60, MATERIAL_PAINT, 1.0f);      // hull of the car
        box(mesh, 0, 1.04, -0.30, 1.60, 0.52, 1.80, MATERIAL_GLASS, 0.9f);  // greenhouse
        box(mesh, 0, 1.36, -0.30, 1.62, 0.12, 1.90, MATERIAL_PAINT, 1.0f);  // roof
        return mesh;
    }

    /** Four round wheels on one wheelbase, centred on their own axles so they can roll. */
    private static List<Part> wheels() {
        List<Part> parts = new ArrayList<>(4);
        for (double track : new double[]{TRACK_M * 0.5, -TRACK_M * 0.5}) {
            for (double axle : new double[]{WHEELBASE_M * 0.5, -WHEELBASE_M * 0.5}) {
                parts.add(new Part("wheel", wheel(), track, WHEEL_RADIUS_M, axle, AXIS_X, 1.0));
            }
        }
        return parts;
    }

    /** One wheel, centred on its own axle: an eight-sided rim, 0.24 m of tyre. */
    private static MeshData wheel() {
        MeshData mesh = new MeshData();
        int sides = 8;
        double width = 0.24;
        double x0 = -width / 2;
        double x1 = width / 2;
        float[][] rim = new float[sides + 1][2];
        for (int i = 0; i <= sides; i++) {
            double angle = 2.0 * Math.PI * i / sides;
            rim[i][0] = (float) (WHEEL_RADIUS_M * Math.cos(angle));
            rim[i][1] = (float) (WHEEL_RADIUS_M * Math.sin(angle));
        }
        for (int i = 0; i < sides; i++) {
            float y0 = rim[i][0];
            float z0 = rim[i][1];
            float y1 = rim[i + 1][0];
            float z1 = rim[i + 1][1];
            double mid = Math.atan2((z0 + z1) * 0.5, (y0 + y1) * 0.5);
            quad(mesh, (float) x0, y0, z0, (float) x0, y1, z1, (float) x1, y1, z1, (float) x1, y0, z0,
                    0f, (float) Math.cos(mid), (float) Math.sin(mid), MATERIAL_TYRE, 1.0f);
            // Caps: a fan per side, wound so each faces out of the wheel.
            int capCentre = mesh.addVertexMaterial((float) x1, 0f, 0f, 1f, 0f, 0f,
                    VertexFormat.SURFACE_BODY, 0f, 0f, 1.0f, MATERIAL_TYRE);
            int a = mesh.addVertexMaterial((float) x1, y0, z0, 1f, 0f, 0f, VertexFormat.SURFACE_BODY,
                    0f, 0f, 1.0f, MATERIAL_TYRE);
            int b = mesh.addVertexMaterial((float) x1, y1, z1, 1f, 0f, 0f, VertexFormat.SURFACE_BODY,
                    0f, 0f, 1.0f, MATERIAL_TYRE);
            mesh.addTriangle(capCentre, a, b);
            int backCentre = mesh.addVertexMaterial((float) x0, 0f, 0f, -1f, 0f, 0f,
                    VertexFormat.SURFACE_BODY, 0f, 0f, 1.0f, MATERIAL_TYRE);
            int c = mesh.addVertexMaterial((float) x0, y0, z0, -1f, 0f, 0f, VertexFormat.SURFACE_BODY,
                    0f, 0f, 1.0f, MATERIAL_TYRE);
            int d = mesh.addVertexMaterial((float) x0, y1, z1, -1f, 0f, 0f, VertexFormat.SURFACE_BODY,
                    0f, 0f, 1.0f, MATERIAL_TYRE);
            mesh.addTriangle(backCentre, d, c);
        }
        return mesh;
    }

    /** Fuselage, cabin, wing, tailplane, fin and three wheels: 11 m span, 8.28 m long. */
    private static MeshData plane() {
        MeshData mesh = new MeshData();
        box(mesh, 0, 1.05, 0.24, 1.10, 1.00, 8.28, MATERIAL_AIRCRAFT, 1.0f);   // fuselage
        box(mesh, 0, 1.25, 0, 1.00, 0.50, 1.80, MATERIAL_GLASS, 0.95f);       // cabin
        box(mesh, 0, 1.45, 0.20, 11.00, 0.20, 1.00, MATERIAL_AIRCRAFT, 1.0f); // wing
        box(mesh, 0, 1.36, -3.45, 3.20, 0.12, 0.90, MATERIAL_AIRCRAFT, 1.0f); // tailplane
        box(mesh, 0, 2.075, -3.55, 0.12, 1.05, 0.70, MATERIAL_AIRCRAFT, 1.0f);
        box(mesh, 0, 0.275, 3.60, 0.24, 0.55, 0.60, MATERIAL_TYRE, 1.0f);     // nose wheel
        box(mesh, 1.00, 0.275, -0.30, 0.24, 0.55, 0.60, MATERIAL_TYRE, 1.0f);
        box(mesh, -1.00, 0.275, -0.30, 0.24, 0.55, 0.60, MATERIAL_TYRE, 1.0f);
        return mesh;
    }

    /** The propeller disc, centred on the hub, lying across the nose. */
    private static MeshData propeller() {
        MeshData mesh = new MeshData();
        box(mesh, 0.95, 0, 0, 1.90, 0.20, 0.12, MATERIAL_STEEL, 1.0f);
        box(mesh, -0.95, 0, 0, 1.90, 0.20, 0.12, MATERIAL_STEEL, 1.0f);
        return mesh;
    }

    /** Cabin, boom, fin and two skids: 5.60 m of rotor disc, in the parts. */
    private static MeshData helicopter() {
        MeshData mesh = new MeshData();
        box(mesh, 0, 0.815, 0.35, 1.50, 1.27, 2.50, MATERIAL_HELI, 1.0f);    // cabin
        box(mesh, 0, 1.20, 1.40, 1.40, 0.60, 0.40, MATERIAL_GLASS, 1.0f);   // canopy
        box(mesh, 0, 0.90, -2.05, 0.50, 0.40, 2.30, MATERIAL_HELI, 1.0f);   // tail boom
        box(mesh, 0, 1.20, -3.20, 0.16, 0.80, 0.40, MATERIAL_HELI, 1.0f);   // fin
        box(mesh, 0.65, 0.09, 0.40, 0.20, 0.18, 2.80, MATERIAL_STEEL, 1.0f);  // skids
        box(mesh, -0.65, 0.09, 0.40, 0.20, 0.18, 2.80, MATERIAL_STEEL, 1.0f);
        return mesh;
    }

    /** Two crossed blades, centred on the mast. */
    private static MeshData mainRotor() {
        MeshData mesh = new MeshData();
        box(mesh, 0, 0, 0, 5.60, 0.08, 0.20, MATERIAL_ROTOR, 0.7f);
        box(mesh, 0, 0, 0, 0.20, 0.08, 5.60, MATERIAL_ROTOR, 0.7f);
        return mesh;
    }

    /** Two blades in the vertical plane, centred on the tail shaft. */
    private static MeshData tailRotor() {
        MeshData mesh = new MeshData();
        box(mesh, 0, 0, 0, 0.12, 0.90, 0.10, MATERIAL_ROTOR, 0.7f);
        return mesh;
    }

    /** Hull (0.35 m of it under the waterline), deck, cabin, window band and an outboard. */
    private static MeshData boat() {
        MeshData mesh = new MeshData();
        box(mesh, 0, 0.35, 0.25, 2.10, 1.40, 6.50, MATERIAL_HULL, 1.0f);     // hull, sitting in the water
        box(mesh, 0, 1.085, 0.20, 2.00, 0.07, 5.60, MATERIAL_DECK, 1.0f);   // teak deck
        box(mesh, 0, 1.535, -0.15, 1.60, 0.83, 2.10, MATERIAL_HULL, 1.0f);  // cabin
        box(mesh, 0, 1.675, 0.88, 1.50, 0.45, 0.10, MATERIAL_GLASS, 1.0f);  // window band
        box(mesh, 0, 1.30, -3.30, 0.60, 0.60, 0.45, MATERIAL_STEEL, 0.9f);  // outboard
        return mesh;
    }

    // ------------------------------------------------------------------ one box ------------------

    /**
     * Adds one axis-aligned box: the centre and the full extent on each axis, so a call reads like the
     * machine's dimensions rather than like eight corner coordinates.
     */
    private static void box(MeshData mesh, double centreX, double centreY, double centreZ,
                            double sizeX, double sizeY, double sizeZ, int material, float tint) {
        float x0 = (float) (centreX - sizeX * 0.5);
        float x1 = (float) (centreX + sizeX * 0.5);
        float y0 = (float) (centreY - sizeY * 0.5);
        float y1 = (float) (centreY + sizeY * 0.5);
        float z0 = (float) (centreZ - sizeZ * 0.5);
        float z1 = (float) (centreZ + sizeZ * 0.5);
        quad(mesh, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f, material, tint);
        quad(mesh, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0f, 0f, -1f, material, tint);
        quad(mesh, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1f, 0f, 0f, material, tint);
        quad(mesh, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1f, 0f, 0f, material, tint);
        quad(mesh, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0f, 1f, 0f, material, tint);
        quad(mesh, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0f, -1f, 0f, material, tint);
    }

    /** One face, counter-clockwise seen from outside, with u along the face and v in metres up it. */
    private static void quad(MeshData mesh, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, float nx,
                             float ny, float nz, int material, float tint) {
        float u = (float) Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay)
                + (bz - az) * (bz - az));
        int a = mesh.addVertexMaterial(ax, ay, az, nx, ny, nz, VertexFormat.SURFACE_BODY, 0f, ay,
                tint, material);
        int b = mesh.addVertexMaterial(bx, by, bz, nx, ny, nz, VertexFormat.SURFACE_BODY, u, by,
                tint, material);
        int c = mesh.addVertexMaterial(cx, cy, cz, nx, ny, nz, VertexFormat.SURFACE_BODY, u, cy,
                tint, material);
        int d = mesh.addVertexMaterial(dx, dy, dz, nx, ny, nz, VertexFormat.SURFACE_BODY, 0f, dy,
                tint, material);
        mesh.addQuad(a, b, c, d);
    }
}
