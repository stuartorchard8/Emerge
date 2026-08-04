package org.emerge.demo.outofspace.world

/**
 * Temperatures the whole world agrees on.
 *
 * Its own object rather than constants hung off whichever field happened to need them first — air,
 * fabric and the vacuum outside all quote the same two numbers, and none of them owns the others.
 */
object Temperature {
    /** Deep space, near enough. Everything radiates toward this and nothing gets colder. */
    const val SPACE_KELVIN = 3

    /** Comfortable room temperature — what a freshly built thing starts at. */
    const val AMBIENT_KELVIN = 293
}

/**
 * What a solid thing is made of.
 *
 * ### Why a material and not a heat capacity per tile
 *
 * The field this replaces gave every tile one temperature and one capacity, worked out from what
 * kind of tile it was. That is a fair approximation right up to the point where a tile holds a rail,
 * a conduit and a machine at once — which is the ordinary case in this game, because conduits are
 * *layers* and layers exist precisely so several things can share a tile. Averaging an iron rail, a
 * copper pipe and a titanium furnace shell into one lump denies the three of them the only thing
 * that makes materials interesting: they warm at different rates and pass heat at different rates,
 * so a copper line is a heat leak and a firebrick furnace is a heat store, and where you run them
 * matters.
 *
 * So the temperature belongs to the **object**, and the material is what turns an object into a
 * thermal one.
 *
 * ### Units
 *
 * [specificHeat] is joules per kilogram per kelvin, exactly as [org.emerge.demo.outofspace.chem.Species.specificHeat]
 * is, and [gramsPerTile] is grams — so their product is **millijoules per kelvin**, which is the same
 * unit the atmosphere's heat capacity is carried in. That is not a coincidence and it is the point:
 * solids and gas exchange heat every tick, and a conversion factor sitting between two things that
 * add together is a rounding error waiting to become a conservation bug. One unit, no conversion.
 *
 * See [org.emerge.demo.outofspace.world.fluid.gasCapacity] for why the thousand lives in the unit
 * rather than in a division.
 *
 * ### The masses are tuned, and the ratios are real
 *
 * [gramsPerTile] is roughly two orders of magnitude under what a cubic metre of the stuff weighs.
 * That is deliberate. A real furnace is several tonnes of refractory and takes hours to come up to
 * temperature; simulated honestly, a smelter would warm its room at fifteen thousandths of a kelvin
 * per second and heat would once again be a number painted on the world rather than a thing
 * happening in it. What has to be right is the *ratio* between materials — that titanium holds more
 * than iron per tile and firebrick more than either — because that is what the player can act on.
 * The absolute scale is a time constant, and a time constant is a tuning dial.
 *
 * The scale is set so a tile of any material lands within a small multiple of the ~1000 J/K a tile
 * of air holds. Below that, solids would be dragged around by the atmosphere; far above it, the air
 * could never notice them. Comparable is what makes conduction between the two visible at all.
 *
 * [conductance] is millijoules per kelvin of difference **per tick**, across one contact. Same
 * ordering as real thermal conductivity — copper far ahead, then iron and steel, titanium poor,
 * firebrick barely at all — scaled so that a contact moves a useful fraction of a tile's heat in a
 * tick without being able to move all of it.
 */
enum class Material(
    val label: String,
    val specificHeat: Int,
    val gramsPerTile: Long,
    val conductance: Long,
) {
    /** The skin. Cheap, stiff, and the only thing that touches space. */
    Steel("STEEL", specificHeat = 490, gramsPerTile = 2_000L, conductance = 40_000L),

    /** Track: light, and a decent conductor, so a long run is a long thermal short circuit. */
    Iron("IRON", specificHeat = 450, gramsPerTile = 400L, conductance = 45_000L),

    /** Pipe and cable. Barely any thermal mass and enormous conductance — a heat pipe by accident. */
    Copper("COPPER", specificHeat = 385, gramsPerTile = 300L, conductance = 200_000L),

    /** Machine casings: heavy, and a poor conductor, so a machine holds its own heat. */
    Titanium("TITANIUM", specificHeat = 520, gramsPerTile = 1_500L, conductance = 15_000L),

    /** Furnace lining. The most thermal mass and the least conductance: it is meant to stay hot. */
    Firebrick("FIREBRICK", specificHeat = 880, gramsPerTile = 3_000L, conductance = 3_000L),
    ;

    /** Millijoules per kelvin for one tile of this stuff. See the class note for the unit. */
    val capacityPerTile: Long get() = gramsPerTile * specificHeat

    /** What one tile of this holds at room temperature — what a freshly built thing starts at. */
    val ambientPerTile: Long get() = capacityPerTile * Temperature.AMBIENT_KELVIN

    companion object {
        /**
         * The gas side of a solid-to-air contact, in [conductance]'s units.
         *
         * A film coefficient rather than a conductivity: what limits heat crossing from a wall into
         * a room is the thin, nearly still layer of gas against the wall, not the gas's bulk
         * properties. Treating it as one more material in the series combination means solid-to-air
         * needs no separate rule — see [seriesConductance].
         *
         * Deliberately low enough that a hot wall does not equalise with its room in a single tick,
         * because the whole reason this exists is for the air to be *carrying* heat somewhere, and
         * air that reaches the wall's temperature instantly has nothing left to carry.
         */
        const val AIR_FILM = 20_000L

        /**
         * How fast an exposed face sheds heat to space, per kelvin above [Temperature.SPACE_KELVIN],
         * per face, per tick.
         *
         * Small on purpose, and the reasoning has not changed since it was tuned against a per-tile
         * field: vacuum is an excellent insulator, a spacecraft's thermal problem is rejecting heat
         * rather than keeping it, and every real one carries radiators for the purpose. A value that
         * let the hull dump a megajoule a second would make freezing the only possible failure,
         * which is neither true nor interesting to build against.
         *
         * Linear in the gap rather than the fourth power a real surface obeys. The fourth power
         * would make a hot vessel shed disproportionately faster, which is a genuinely different
         * game and worth having later; it is not worth having before anything makes a vessel hot.
         */
        const val RADIANCE = 150L
    }
}

/**
 * Two things in contact conduct at the **series** combination of their conductances.
 *
 * Heat crossing a joint passes through both sides, so the worse of the two governs — copper bolted to
 * firebrick is a firebrick-limited joint, not a copper-fast one. The harmonic mean says exactly that
 * and needs no special case for the extremes: it is never larger than either input, and it collapses
 * to zero if either side does.
 */
fun seriesConductance(a: Long, b: Long): Long {
    val sum = a + b
    return if (sum <= 0L) 0L else 2L * a * b / sum
}

/**
 * What each kind of deck machine is made of.
 *
 * A furnace is lined with firebrick because it wants to be hot and stay hot; everything else is a
 * titanium casing; the hull is steel because it is a pressure skin rather than a machine. The
 * conduit fittings are here for completeness and are only ever asked via [Conduit.material], since a
 * fitting's material follows the network it belongs to rather than the shape it is.
 */
val MachineKind.material: Material
    get() = when (this) {
        MachineKind.Hull -> Material.Steel
        MachineKind.Smelter -> Material.Firebrick
        MachineKind.Miner, MachineKind.Processor, MachineKind.Storage,
        MachineKind.Sensor, MachineKind.Vent, MachineKind.Pump,
        -> Material.Titanium
        MachineKind.Rail, MachineKind.Gauge -> Conduit.Rail.material
        MachineKind.Pipe, MachineKind.Valve -> Conduit.Pipe.material
        MachineKind.Bridge -> Conduit.Rail.material
    }

/**
 * What a length of each network is made of.
 *
 * Rail is iron track. The other three are copper, which is the honest answer for a pipe and a cable
 * and also the interesting one: copper has almost no thermal mass and enormous conductance, so a
 * conduit run is a wire that carries heat as readily as whatever it was laid for. Running a power
 * line through a furnace room and out to the hull is a radiator nobody designed, and finding that
 * out by building it is the kind of thing this model exists to allow.
 */
val Conduit.material: Material
    get() = when (this) {
        Conduit.Rail -> Material.Iron
        Conduit.Pipe, Conduit.Power, Conduit.Signal -> Material.Copper
    }
