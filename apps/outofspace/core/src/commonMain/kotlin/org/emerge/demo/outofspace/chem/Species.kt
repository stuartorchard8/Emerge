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
 */
enum class Species(val phase: Phase) {
    // ── Minerals: everything that comes out of the ground ──
    Iron(Phase.Solid),
    Aluminum(Phase.Solid),
    Copper(Phase.Solid),
    Titanium(Phase.Solid),
    Silica(Phase.Solid),
    Carbon(Phase.Solid),
    RareEarth(Phase.Solid),
    Uranium(Phase.Solid),

    // ── Fluids. Present so the fluid transport path is exercised by real species rather than by a
    // test fixture; the set will grow as life support and coolant loops need it. ──
    Oxygen(Phase.Gas),
    Nitrogen(Phase.Gas),
    CarbonDioxide(Phase.Gas),
    Water(Phase.Liquid),
    ;

    val isFluid: Boolean get() = phase.isFluid
    val isSolid: Boolean get() = phase == Phase.Solid

    companion object {
        /** Cached because `entries` allocates on some targets and this is read in inner loops. */
        val ALL: List<Species> = entries.toList()
        val COUNT: Int = ALL.size

        val SOLIDS: List<Species> = ALL.filter { it.isSolid }
        val FLUIDS: List<Species> = ALL.filter { it.isFluid }
    }
}
