package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.Budget

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species

/** Shared temperatures across all systems (air, fabric, vacuum). */
object Temperature {
    /** Deep space, near enough. Everything radiates toward this and nothing gets colder. */
    const val SPACE_KELVIN = 3

    /** Comfortable room temperature — what a freshly built thing starts at. */
    const val AMBIENT_KELVIN = 293
}

/**
 * A solid material: **what it is made of**, and how well it passes heat.
 *
 * [composition] is the whole of the first half, and it is a bill of materials as much as it is a
 * physical property — steel is iron with a little carbon in it, firebrick is silica and alumina —
 * so it is simultaneously what a thing weighs, what it costs to warm, what it costs to build and
 * what it yields when broken up. Density and specific heat are **derived** from it and are not
 * declared anywhere: see [gramsPerTileOf] and [specificHeatOf]. The species table reproduces every
 * one of the specific heats this enum used to state by hand, to within a few per cent, which is the
 * evidence that the decomposition is the real one and not a fit.
 *
 * A tile of any of this is a full [org.emerge.demo.outofspace.chem.TILE_LITRES] of the solid — six
 * and a half tonnes of steel. Nothing is built out of full tiles of metal; what fraction of a tile
 * a given machine actually is lives on the machine, as [MachineKind.fillPermille], because that is a
 * fact about the machine and not about the steel.
 *
 * [conductanceCentiTicks] is the second half: how long heat takes to cross a contact of this stuff,
 * stated as a **time constant** (in hundredths of a tick — copper's is under one) rather than as a
 * conductance. That way round because a conductance
 * is only meaningful against a capacity, and the capacity now follows from real densities — stating
 * the ticks keeps every thermal behaviour in the game exactly where it was tuned while the masses
 * underneath it become real. These five numbers are the ones the old conductances worked out to.
 */
enum class Material(
    val label: String,
    val composition: Mixture,
    val conductanceCentiTicks: Long,
) {
    /** The skin. Cheap, stiff, and the only thing that touches space. */
    Steel("STEEL", Mixture.of(Species.Iron to 990L, Species.Carbon to 10L), conductanceCentiTicks = 2_450L),

    /** Track: light, and a decent conductor, so a long run is a long thermal short circuit. */
    Iron("IRON", Mixture.of(Species.Iron to 1_000L), conductanceCentiTicks = 400L),

    /** Pipe and cable. Barely any thermal mass and enormous conductance — a heat pipe by accident. */
    Copper("COPPER", Mixture.of(Species.Copper to 1_000L), conductanceCentiTicks = 58L),

    /** Machine casings: heavy, and a poor conductor, so a machine holds its own heat. */
    Titanium("TITANIUM", Mixture.of(Species.Titanium to 1_000L), conductanceCentiTicks = 5_200L),

    /** Furnace lining. The most thermal mass and the least conductance: it is meant to stay hot. */
    Firebrick("FIREBRICK", Mixture.of(Species.Silica to 550L, Species.Aluminum to 450L), conductanceCentiTicks = 88_000L),
    ;

    /** What a full tile of this stuff weighs, at its real density. */
    val gramsPerTile: Long get() = gramsPerTileOf(composition)

    /** Millijoules per kelvin for a full tile of it. */
    val capacityPerTile: Long get() = capacityPerTileOf(composition)

    /**
     * How much heat crosses a contact of this material per kelvin per tick.
     *
     * Derived from [conductanceCentiTicks] against a full tile's capacity, so the material's thermal
     * behaviour is stated once, in the unit anyone can check against the game — "a firebrick joint
     * takes the better part of a thousand ticks" — and cannot drift when a density changes.
     */
    val conductance: Long get() = capacityPerTile * 100L / conductanceCentiTicks

    companion object {
        /**
         * Solid-to-air contact gas-side conductance (film coefficient; prevents instant
         * wall-to-room equalisation).
         *
         * ⚠️ **Unchanged by the move to real densities, and it must stay that way.** This is the
         * *gas* side of the joint — the film of still air against the wall — and the gas was always
         * at real scale: [AirField.AMBIENT_AIR] is a real kilogram of air in a tile. Only the solids
         * moved. Scaling this with them made every wall equalise with its room inside a tick, which
         * showed up as gas tests failing rather than heat ones.
         *
         * **Derivation**: 20 J/K/tick, stated in [Budget]'s energy units. It is **energy**-dimensioned
         * — millijoules per kelvin per tick — so it moves with `Budget.MILLIJOULE` and not with
         * `Budget.GRAM`. Those two travel together by the `ENERGY_PER_MASS` relation, so writing it
         * this way costs nothing today and keeps it correct if the relation is ever revisited.
         */
        val AIR_FILM: Long = 20L * Budget.JOULE

        /**
         * Exposed-face radiance: mJ/K/tick per face (linear gap, not T⁴; vacuum = excellent
         * insulator).
         *
         * This one *does* scale, because it is the solid side: it sets how fast a hull plate sheds
         * its own heat to space. Anchored against the plate that actually does the radiating —
         * [MachineKind.Hull]'s capacity, fill fraction and all, not a full tile of steel — so a
         * ship cools to space on the timescale it always did.
         */
        val RADIANCE: Long get() = MachineKind.Hull.capacityPerTile / 6_533L
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

/**
 * How much of its tile a machine is actually made of, in parts per thousand.
 *
 * A tile is [org.emerge.demo.outofspace.chem.TILE_LITRES] — the better part of a cubic metre — and
 * nothing aboard is a solid block of that. A hull plate is a few centimetres of steel across a
 * metre of face; a smelter is a thick lining around a void that the ore goes in; a wire is a wire.
 * This is that fraction, stated where it belongs: on the machine, because it is a fact about how
 * the machine is built and not about the steel it is built from.
 *
 * It replaces the old deflated `gramsPerTile`, which was the same idea kept implicitly and in the
 * wrong place — a density that quietly meant "and it is mostly empty", which is why an iron rail
 * used to be five times lighter *per unit of material* than a steel plate.
 *
 * ⚠️ These are the dial now. They set what a ship weighs and therefore how briskly a given thrust
 * moves it, and — once building lands — what it costs to put up. [RigidBody] does not appear here:
 * a rock is solid, fill 1000, which is the whole reason it outweighs the ship that mines it.
 */
val MachineKind.fillPermille: Int
    get() = when (this) {
        // Plate: a few centimetres of steel over a metre of face, plus framing.
        MachineKind.Hull, MachineKind.Airlock -> 60

        // A lining thick enough to hold a furnace's heat in, around the space the ore occupies.
        MachineKind.Smelter -> 250

        // Casings with machinery in them: a shell, a mechanism, and a lot of air.
        MachineKind.Extractor, MachineKind.Processor, MachineKind.Vaporizer, MachineKind.Storage,
        MachineKind.Pump,
        -> 150

        // Instruments and fittings: mostly a housing.
        MachineKind.Sensor, MachineKind.Vent, MachineKind.KeyInput, MachineKind.Gauge -> 40

        // Track and pipework, laid across a tile rather than filling it.
        MachineKind.Rail, MachineKind.Bridge -> 20
        MachineKind.Pipe, MachineKind.Valve -> 15

        // A cable.
        MachineKind.Wire -> 2
    }

/** The same fraction for a bare conduit, which is what a fitting-free length of it is. */
val Conduit.fillPermille: Int
    get() = when (this) {
        Conduit.Rail -> MachineKind.Rail.fillPermille
        Conduit.Pipe -> MachineKind.Pipe.fillPermille
        Conduit.Power, Conduit.Signal -> MachineKind.Wire.fillPermille
    }

/** What one tile of this kind weighs: its material's real density, at the fraction it fills. */
val MachineKind.gramsPerTile: Long get() = material.gramsPerTile * fillPermille / 1_000L

/** Millijoules per kelvin for one tile of it — the same fill, the same fact. */
val MachineKind.capacityPerTile: Long get() = material.capacityPerTile * fillPermille / 1_000L

/** What crosses a contact of it: the material's conductance through the metal actually present. */
val MachineKind.conductance: Long get() = material.conductance * fillPermille / 1_000L

/** What one tile of bare conduit weighs. */
val Conduit.gramsPerTile: Long get() = material.gramsPerTile * fillPermille / 1_000L

/** Millijoules per kelvin for one tile of bare conduit. */
val Conduit.capacityPerTile: Long get() = material.capacityPerTile * fillPermille / 1_000L

/** What crosses a contact of bare conduit. */
val Conduit.conductance: Long get() = material.conductance * fillPermille / 1_000L

/**
 * What one tile of bare conduit holds at room temperature — what a freshly laid length starts at.
 *
 * ⚠️ Deliberately **not** on [Material], where it used to live. A material's ambient energy is a
 * full tile of solid metal at room temperature, and nothing in the vessel is that; a segment
 * initialised from it and then read against its own fill-scaled capacity came out at fourteen
 * thousand kelvin. Ambient energy only means anything once you know how much of a tile is there,
 * so it lives with the things that know.
 */
val Conduit.ambientPerTile: Long get() = capacityPerTile * Temperature.AMBIENT_KELVIN

/**
 * **The bill of materials for one machine of this kind** — every gram of every species in it.
 *
 * The whole point of building the mass model this way round. What a machine weighs, what it costs
 * to warm, what it will cost to build and what it will yield when torn down are one number seen
 * from four sides, so they cannot drift apart: a smelter is 550:450 silica to alumina by mass
 * because firebrick is, and salvaging it hands back exactly that.
 *
 * ⚠️ Nothing spends or refunds this yet — building is free and dismantling yields nothing. This is
 * the number those will read when they land; it is derived, so there is no second table to forget.
 */
fun billOfMaterials(kind: MachineKind): Mixture =
    kind.material.composition.scaledTo(kind.gramsPerTile * kind.thermalTiles)
