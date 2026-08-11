package org.emerge.demo.outofspace.world

/** Shared temperatures across all systems (air, fabric, vacuum). */
object Temperature {
    /** Deep space, near enough. Everything radiates toward this and nothing gets colder. */
    const val SPACE_KELVIN = 3

    /** Comfortable room temperature — what a freshly built thing starts at. */
    const val AMBIENT_KELVIN = 293
}

/**
 * Solid material properties.
 * Temperature belongs to object, material converts object → thermal.
 * Units: specificHeat × gramsPerTile = millijoules/kelvin (same as gas capacity — no conversion).
 * GramsPerTile: ~100× under real density (tuned time constant; ratios are real).
 * Conductance: mJ/K/tick per contact (real ordering, tuned scale).
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
        /** Solid-to-air contact gas-side conductance (film coefficient; prevents instant wall-to-room equalisation). */
        const val AIR_FILM = 20_000L

        /** Exposed-face radiance: W/K/tick per face (linear gap, not T⁴; vacuum = excellent insulator). */
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

/** MachineKind → Material (hull=steel, smelter=firebrick, rest=titanium; conduits follow network material). */
val MachineKind.material: Material
    get() = when (this) {
        MachineKind.Hull, MachineKind.Airlock -> Material.Steel
        MachineKind.Smelter -> Material.Firebrick
        MachineKind.Extractor, MachineKind.Processor, MachineKind.Vaporizer, MachineKind.Storage,
        MachineKind.Sensor, MachineKind.Vent, MachineKind.Pump, MachineKind.KeyInput,
        -> Material.Titanium
        MachineKind.Rail, MachineKind.Gauge -> Conduit.Rail.material
        MachineKind.Pipe, MachineKind.Valve -> Conduit.Pipe.material
        MachineKind.Bridge -> Conduit.Rail.material
        MachineKind.Wire -> Conduit.Signal.material
    }

/** Conduit → Material (rail=iron; pipe/power/signal=copper; low thermal mass + high conductance = heat wire). */
val Conduit.material: Material
    get() = when (this) {
        Conduit.Rail -> Material.Iron
        Conduit.Pipe, Conduit.Power, Conduit.Signal -> Material.Copper
    }
