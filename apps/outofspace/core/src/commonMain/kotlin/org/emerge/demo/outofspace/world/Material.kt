package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.MachineKind
import org.emerge.demo.outofspace.world.machine.thermalTiles

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
 * declared anywhere: see [massPerTileOf] and [specificHeatOf]. The species table reproduces every
 * one of the specific heats this enum used to state by hand, to within a few per cent, which is the
 * evidence that the decomposition is the real one and not a fit.
 *
 * A tile of any of this is a full [org.emerge.demo.outofspace.chem.TILE_LITRES] of the solid — six
 * and a half tonnes of steel. Nothing is built out of full tiles of metal; what fraction of a tile
 * a given machine actually is lives on the machine, as [org.emerge.demo.outofspace.world.machine.MachineKind.fillPermille], because that is a
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
    /**
     * How much a surface of this stuff grips one it is sliding across — Coulomb's `μ`, in parts per
     * thousand, and the third physical property a material has here.
     *
     * Stated per material rather than per contact because that is what it is a fact about, exactly
     * as [conductanceCentiTicks] is: a joint's conductance is a property of the two things meeting
     * and neither of the numbers that make it is. [pairRoughness] is where two of these become one.
     *
     * The values are dry static coefficients, near enough to measure against: steel on steel is
     * about 0.4–0.6, and brick and stone are appreciably grippier at 0.6–0.8. What matters for the
     * game is the ordering — **metal slides and rock does not** — which is the thing that decides
     * whether a rock skates across a deck or stays where it lands.
     */
    val roughness: Long,
) {
    /** The skin. Cheap, stiff, and the only thing that touches space. */
    Steel("STEEL", Mixture.of(Species.Iron to 990L, Species.Carbon to 10L, energy = Budget.JOULE), conductanceCentiTicks = 2_450L, roughness = 400L),

    /** Track: light, and a decent conductor, so a long run is a long thermal short circuit. */
    Iron("IRON", Mixture.of(Species.Iron to 1_000L, energy = Budget.JOULE), conductanceCentiTicks = 400L, roughness = 450L),

    /** Pipe and cable. Barely any thermal mass and enormous conductance — a heat pipe by accident. */
    Copper("COPPER", Mixture.of(Species.Copper to 1_000L, energy = Budget.JOULE), conductanceCentiTicks = 58L, roughness = 500L),

    /** Machine casings: heavy, and a poor conductor, so a machine holds its own heat. */
    Titanium("TITANIUM", Mixture.of(Species.Titanium to 1_000L, energy = Budget.JOULE), conductanceCentiTicks = 5_200L, roughness = 400L),

    /** Furnace lining. The most thermal mass and the least conductance: it is meant to stay hot. */
    Firebrick("FIREBRICK", Mixture.of(Species.Quartz to 550L, Species.Aluminum to 450L, energy = Budget.JOULE), conductanceCentiTicks = 88_000L, roughness = 700L),
    ;

    /**
     * What a full tile of this stuff weighs, at its real density.
     *
     * ⚠️ **A field, not a `get()`, and that is a performance fact rather than a style one.**
     * [massPerTileOf] walks every one of [Species]' entries doing two [scaledRatio]s apiece, and a
     * material's [composition] is a compile-time constant — so every evaluation after the first
     * returns the identical number for identical work. As a getter it measured at roughly a quarter
     * of the whole sim tick, because [conductance] reaches it through [capacityPerTile] and the
     * heat solver asks per contact per tick. Same for the two below.
     */
    val massPerTile: Long = massPerTileOf(composition)

    /** Millijoules per kelvin for a full tile of it. */
    val capacityPerTile: Long = capacityPerTileOf(composition)

    /**
     * How much heat crosses a contact of this material per kelvin per tick.
     *
     * Derived from [conductanceCentiTicks] against a full tile's capacity, so the material's thermal
     * behaviour is stated once, in the unit anyone can check against the game — "a firebrick joint
     * takes the better part of a thousand ticks" — and cannot drift when a density changes.
     */
    val conductance: Long = capacityPerTile * 100L / conductanceCentiTicks

    companion object {
        /**
         * Solid-to-air contact gas-side conductance (film coefficient; prevents instant
         * wall-to-room equalisation).
         *
         * ⚠️ **Unchanged by the move to real densities, and it must stay that way.** This is the
         * *gas* side of the joint — the film of still air against the wall — and the gas was always
         * at real scale: [Stuff.AMBIENT_AIR] is a real kilogram of air in a tile. Only the solids
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
         * [org.emerge.demo.outofspace.world.machine.MachineKind.Hull]'s capacity, fill fraction and all, not a full tile of steel — so a
         * ship cools to space on the timescale it always did.
         */
        val RADIANCE: Long get() = DeckMachineKind.Hull.capacityPerTile / 6_533L
    }
}

/**
 * The friction of a contact, from the two surfaces that make it: **the smoother one governs.**
 *
 * The same shape of rule as [seriesConductance] and for a related reason — a property of a joint is
 * never better than its worse half. Grip comes from the two surfaces keying into each other, so a
 * rough face against a polished one has nothing to bite on: rock dragged over steel slides at
 * steel's number, not at rock's. It also gives the ordering Stu asked for directly, with metal on
 * metal at the bottom and rock on rock at the top, and it needs no special case at the extremes —
 * a frictionless surface makes every contact it takes part in frictionless.
 *
 * `RollingResistanceSystem` in the engine combines Drockets' two materials the same way, with
 * `min(a.rough, b.rough)`, which is the nearest thing in the codebase to a precedent.
 *
 * ⚠️ Returned in [Flight.FRAC_ONE]ths rather than in per mille, because that is the unit the solver
 * multiplies a normal impulse by. Converted here, once, rather than at the multiply.
 */
fun pairRoughness(a: Long, b: Long): Long = minOf(a, b) * Flight.FRAC_ONE / 1_000L

/**
 * What a rock's surface grips like.
 *
 * ⚠️ **One constant, and the argument is the hook that makes it stop being one.** A rock is a
 * [Mixture] rather than a [Material] — it is whatever the ore field made it — so unlike a hull plate
 * there is no enum entry to hang a number on. Deriving grip from a composition means saying what
 * each species' surface is like, and that is a table of invented numbers until something in the game
 * depends on the difference. What is real today is Stu's ordering: rubble grips harder than any
 * metal aboard, so at 800 against steel's 400 a rock stays where it lands on a deck and two rocks
 * grinding together stay put on each other.
 */
@Suppress("UNUSED_PARAMETER")
fun roughnessOf(ore: Mixture): Long = 800L

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
    // `2ab/(a+b)` multiplies two conductances together, and a conductance is energy per kelvin per
    // tick — mass-dimensioned, so the product is **quadratic in the mass unit**. A steel hull plate
    // conducts about 1.2e11 at one microgram per unit and the product reaches 2.9e22, which wraps and
    // takes the whole solid-heat solver with it: every contact in the vessel reads a nonsense
    // conductance and nothing conducts anywhere. Taken as the fraction `a/(a+b)` first, the unit
    // cancels out of the ratio and is carried only by the `2b`. Exact wherever the old form did not
    // overflow, and still symmetric in its arguments, since both orderings are exactly `2ab/(a+b)`.
    return if (sum <= 0L) 0L else scaledRatio(a, sum, 2L * b)
}

/** MachineKind → Material (hull=steel, smelter=firebrick, rest=titanium; conduits follow network material). */
val MachineKind.material: Material
    get() = when (this) {
        MachineKind.Smelter, MachineKind.ThermalDecomposer -> Material.Firebrick
        MachineKind.Extractor, MachineKind.Processor, MachineKind.Vaporizer, MachineKind.Storage,
        MachineKind.Sensor, MachineKind.Pump, MachineKind.KeyInput,
        MachineKind.Thruster,
        -> Material.Titanium
        MachineKind.Rail, MachineKind.Gauge -> Conduit.Rail.material
        MachineKind.Pipe, MachineKind.Valve -> Conduit.Pipe.material
        MachineKind.Bridge -> Conduit.Rail.material
        MachineKind.Wire -> Conduit.Signal.material
    }
val DeckMachineKind.material: Material
    get() = when (this) {
        DeckMachineKind.Hull, DeckMachineKind.Airlock -> Material.Steel
        // A hole in the hull with a housing around it, and the housing is the part that is made of
        // anything — which is why it keeps the instrument's fill rather than the plate's.
        DeckMachineKind.Vent -> Material.Titanium
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
 * It replaces the old deflated `massPerTile`, which was the same idea kept implicitly and in the
 * wrong place — a density that quietly meant "and it is mostly empty", which is why an iron rail
 * used to be five times lighter *per unit of material* than a steel plate.
 *
 * ⚠️ These are the dial now. They set what a ship weighs and therefore how briskly a given thrust
 * moves it, and — once building lands — what it costs to put up. [RigidBody] does not appear here:
 * a rock is solid, fill 1000, which is the whole reason it outweighs the ship that mines it.
 */
val MachineKind.fillPermille: Int
    get() = when (this) {
        // A lining thick enough to hold a furnace's heat in, around the space the ore occupies.
        MachineKind.Smelter, MachineKind.ThermalDecomposer -> 250

        // Casings with machinery in them: a shell, a mechanism, and a lot of air.
        MachineKind.Extractor, MachineKind.Processor, MachineKind.Vaporizer, MachineKind.Storage,
        MachineKind.Pump,
        -> 150

        // A bell, a throat and the plumbing behind them: thicker than an instrument housing and
        // nowhere near a furnace lining, because the hot part of a rocket is deliberately thin.
        MachineKind.Thruster -> 120

        // Instruments and fittings: mostly a housing.
        MachineKind.Sensor, MachineKind.KeyInput, MachineKind.Gauge -> 40

        // Track and pipework, laid across a tile rather than filling it.
        MachineKind.Rail, MachineKind.Bridge -> 20
        MachineKind.Pipe, MachineKind.Valve -> 15

        // A cable.
        MachineKind.Wire -> 2
    }
val DeckMachineKind.fillPermille: Int
    get() = when (this) {
        // Plate: a few centimetres of steel over a metre of face, plus framing.
        DeckMachineKind.Hull, DeckMachineKind.Airlock -> 60
        // Mostly a housing, as it was while it was a machine — the number is carried across, not
        // rechosen, so the migration does not quietly change what the ship weighs.
        DeckMachineKind.Vent -> 40
    }

/** The same fraction for a bare conduit, which is what a fitting-free length of it is. */
val Conduit.fillPermille: Int
    get() = when (this) {
        Conduit.Rail -> MachineKind.Rail.fillPermille
        Conduit.Pipe -> MachineKind.Pipe.fillPermille
        Conduit.Power, Conduit.Signal -> MachineKind.Wire.fillPermille
    }

/** What one tile of this kind weighs: its material's real density, at the fraction it fills. */
val MachineKind.massPerTile: Long get() = material.massPerTile * fillPermille / 1_000L
val DeckMachineKind.massPerTile: Long get() = material.massPerTile * fillPermille / 1_000L

/** Millijoules per kelvin for one tile of it — the same fill, the same fact. */
val MachineKind.capacityPerTile: Long get() = material.capacityPerTile * fillPermille / 1_000L
val DeckMachineKind.capacityPerTile: Long get() = material.capacityPerTile * fillPermille / 1_000L

/** What crosses a contact of it: the material's conductance through the metal actually present. */
val MachineKind.conductance: Long get() = material.conductance * fillPermille / 1_000L
val DeckMachineKind.conductance: Long get() = material.conductance * fillPermille / 1_000L

/** What one tile of bare conduit weighs. */
val Conduit.massPerTile: Long get() = material.massPerTile * fillPermille / 1_000L

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
    kind.material.composition.scaledTo(kind.massPerTile * kind.thermalTiles)

/**
 * **What one tile of a deck machine is made of** — the species, at the masses a tile of it weighs.
 *
 * The per-tile twin of [billOfMaterials], and the thing the deck layer is now *filled with* rather
 * than merely described by. A machine's casing stopped being a lookup on its kind and became real
 * matter sitting in [org.emerge.demo.outofspace.world.StuffLayer], which is what makes it possible
 * for chemistry to act on the casing at all: you cannot decompose a constant.
 *
 * ⚠️ [Mixture.scaledTo] apportions, so this sums to [massPerTile] **exactly** — not to within a
 * rounding. That is load-bearing: the deck's contribution to the vessel's mass used to be this
 * constant and is now the sum of these species, and the two must agree to the unit or the ship
 * changes weight the day the representation changes. Measured identical for every kind.
 */
fun tileBillOfMaterials(kind: DeckMachineKind): Mixture =
    kind.material.composition.scaledTo(kind.massPerTile)

/**
 * **The bill of materials for one tile of bare conduit** — the twin of [tileBillOfMaterials], and
 * the same apportioning, so a run of track weighs exactly [Conduit.massPerTile] a tile however its
 * material is composed.
 */
fun conduitBillOfMaterials(conduit: Conduit): Mixture =
    conduit.material.composition.scaledTo(conduit.massPerTile)
