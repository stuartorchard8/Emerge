package org.emerge.demo.outofspace.chem

/**
 * Whether a species is a solid, a liquid or a gas **at the conditions the game currently models**.
 *
 * It is a fixed property of the species today because there is no temperature yet. When Phase 4
 * arrives, phase becomes a function of `(species, temperature, pressure)` and iron gets to melt —
 * so read [Species.phase] as "what this normally is", and never assume it can't change.
 *
 * The split that matters for logistics is **solid vs fluid**, not the three-way one: solids travel
 * as discrete items on belts, fluids flow through pipes. Liquid and gas differ at the *ends* of the
 * network — a pump that lifts a liquid against gravity and one that compresses a gas are different
 * machines — but the pipe between them carries either.
 */
enum class Phase {
    Solid,
    Liquid,
    Gas,
    ;

    /** Liquids and gases share a transport network; solids do not. */
    val isFluid: Boolean get() = this != Solid
}

/**
 * Everything the world is made of, at the granularity the simulation tracks.
 *
 * There is no "iron ore" species — ore is a [Mixture] that happens to be mostly [Iron]. Purity is a
 * property of a pile of stuff, not a name attached to it, and that is what makes refining a real
 * decision rather than a lookup.
 *
 * Declaration order is part of the contract: it fixes the iteration order of every [Mixture]
 * operation and breaks ties in [Mixture.dominant]. Reordering this enum changes simulation results.
 * **Append new species at the end.**
 *
 * [molarMass] is grams per mole, near enough. It is what makes a gas heavy or light, and therefore
 * what makes carbon dioxide pool at the floor and nitrogen ride above it.
 *
 * [specificHeat] is joules per kilogram per kelvin, near enough, at constant pressure. It is the
 * other half of that story: [molarMass] says how much a parcel weighs for its pressure, and this
 * says how much warming it costs. Both are needed together, because what rises is a parcel that is
 * light *for its pressure*, and heating is how a parcel gets that way.
 */
enum class Species(val phase: Phase, val molarMass: Int, val specificHeat: Int) {
    // ── Minerals: everything that comes out of the ground ──
    Iron(Phase.Solid, 56, 450),
    Aluminum(Phase.Solid, 27, 900),
    Copper(Phase.Solid, 64, 385),
    Titanium(Phase.Solid, 48, 520),
    Silica(Phase.Solid, 60, 700),
    Carbon(Phase.Solid, 12, 710),
    RareEarth(Phase.Solid, 140, 200),
    Uranium(Phase.Solid, 238, 116),

    // ── Fluids. Present so the fluid transport path is exercised by real species rather than by a
    // test fixture; the set will grow as life support and coolant loops need it. ──
    Oxygen(Phase.Gas, 32, 918),
    Nitrogen(Phase.Gas, 28, 1040),
    CarbonDioxide(Phase.Gas, 44, 844),
    Water(Phase.Liquid, 18, 4182),
    ;

    val isFluid: Boolean get() = phase.isFluid
    val isSolid: Boolean get() = phase == Phase.Solid

    companion object {
        /** Cached because `entries` allocates on some targets and this is read in inner loops. */
        val ALL: List<Species> = entries.toList()
        val COUNT: Int = ALL.size

        val SOLIDS: List<Species> = ALL.filter { it.isSolid }
        val FLUIDS: List<Species> = ALL.filter { it.isFluid }

        /** The species that make up a breathable — or unbreathable — atmosphere. */
        val GASES: List<Species> = ALL.filter { it.phase == Phase.Gas }
    }
}
