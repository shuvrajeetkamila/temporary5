package ai.arena.qatar.physics;

/**
 * One physical ground/water surface class. Values are gameplay-tuned but physically ordered and are
 * never multiplied together twice: contact friction covers the low level solver, while the tire and
 * hydrodynamic models read the same numbers through clearly separated coefficients.
 *
 * <p>Everything here is SI: friction coefficients are dimensionless, rolling resistance is a
 * coefficient (force per unit normal load), dust/spray factors are 0..1 visual intensities that the
 * particle system consumes.
 */
public final class SurfaceMaterial {

    /** Stable ids are compiled into world chunks -- never renumber an existing entry. */
    public static final int ID_UNKNOWN = 0;
    public static final int ID_DRY_ASPHALT = 1;
    public static final int ID_DUSTY_ASPHALT = 2;
    public static final int ID_WET_ASPHALT = 3;
    public static final int ID_CONCRETE_PAVERS = 4;
    public static final int ID_GRAVEL_ROCK = 5;
    public static final int ID_COMPACT_SAND = 6;
    public static final int ID_LOOSE_SAND_DUNE = 7;
    public static final int ID_WET_SAND = 8;
    public static final int ID_SABKHA = 9;
    public static final int ID_MUD_TIDALFLAT = 10;
    public static final int ID_SALT_MARSH_MANGROVE = 11;
    public static final int ID_FARM_SOIL = 12;
    public static final int ID_URBAN_PAD = 13;
    public static final int ID_RECLAIMED_LAND = 14;
    public static final int ID_SHALLOW_WATER = 15;
    public static final int ID_OPEN_WATER = 16;

    public final int id;
    public final String key;
    /** Coulomb friction for low level contact resolution (human feet, prop skids, hull beaching). */
    public final double contactFriction;
    /** Rolling resistance coefficient applied to wheeled vehicles. */
    public final double rollingResistance;
    /** Peak tire friction multiplier (car/4x4 grip before surface modifiers such as sinkage). */
    public final double tireGrip;
    /** Sinkage factor 0..1: how much a loaded tire digs in (drives sand/dune bogging). */
    public final double sinkage;
    /** Base sand/lateral resistance coefficient used by the loose-surface model. */
    public final double surfaceDrag;
    public final double restitution;
    /** Visual dust emission factor 0..1 for wheels/rotors/footsteps. */
    public final double dustFactor;
    /** Water spray factor 0..1. */
    public final double sprayFactor;
    /** Footstep/vehicle audio surface tag. */
    public final String audioTag;

    public SurfaceMaterial(int id, String key, double contactFriction, double rollingResistance, double tireGrip,
                           double sinkage, double surfaceDrag, double restitution, double dustFactor,
                           double sprayFactor, String audioTag) {
        this.id = id;
        this.key = key;
        this.contactFriction = contactFriction;
        this.rollingResistance = rollingResistance;
        this.tireGrip = tireGrip;
        this.sinkage = sinkage;
        this.surfaceDrag = surfaceDrag;
        this.restitution = restitution;
        this.dustFactor = dustFactor;
        this.sprayFactor = sprayFactor;
        this.audioTag = audioTag;
    }

    @Override
    public String toString() {
        return "SurfaceMaterial(" + id + ", " + key + ")";
    }
}
