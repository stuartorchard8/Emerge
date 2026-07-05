package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.sim.CytoTuning.CHEMISTRY_SCALE

/**
 * **Initial data** — the starting world and the seed organisms' design. Unlike [CytoTuning] (the fixed
 * laws the simulation reads *every tick* and never changes), everything here is read **once, at setup**;
 * the running sim then evolves and depletes *away* from it:
 *  - the seeded reservoir is consumed, diffused, and recycled as the sim runs;
 *  - the preset genome thresholds seed a freshly-spawned cell, then live on the cell and **mutate** — so
 *    they are the *starting* values of evolvable traits, not constants the sim enforces.
 *
 * So tweaking a value here changes the *starting conditions* (what world / creature you begin with);
 * tweak [CytoTuning] to change the *rules* of the world.
 */
object CytoSeed {
    /** The free monomer species the world is seeded with — the **k=3 element alphabet**. Capped at 3 to
     *  bound the molecule species space (3 elements → ≤1884 possible molecules), which the upcoming
     *  dense-chemistry representation depends on. Mutation's alphabet matches (a,b,c). */
    val SEED_MONOMERS = listOf("a", "b", "c")
    /** Per-monomer density per cell-diameter squared area. At MATTER_GRID_RES=1024 cells have a 4x4 footbrint.
     *  Each 16 cells holds this per species — sized so a founder's footprint has access to roughly this much matter.
     *  i.e. enough to bootstrap, scarce enough that depletion bites. */
    const val MATTER_UNIFORM_LEVEL_CELL_SCALE = 3 * CHEMISTRY_SCALE
    const val MATTER_UNIFORM_LEVEL = MATTER_UNIFORM_LEVEL_CELL_SCALE / 16

    // ── Seed cell composition (a freshly-spawned / founder cell) ──────────────────────────────────────
    /** Biomass a freshly-spawned cell (and the founder) starts with — a little structure so it doesn't
     *  instantly die to the death-on-empty-biomass rule. */
    val STARTER_BIOMASS: Map<String, Int> = mapOf("ab" to 1 * CHEMISTRY_SCALE, "bc" to 1 * CHEMISTRY_SCALE, "ca" to 1 * CHEMISTRY_SCALE)

    // ── Seed genome thresholds — the *starting* values of evolvable gene gates (structure in CytoGenes) ─
    // Grow > divide on purpose: with sub-tick interpolation a growth gene fills biomass exactly up to its
    // GROW gate and stops, so the (lower) DIVIDE gate is what it crosses — if they were equal the cell would
    // park at the threshold and never divide (see CytoBiologyCore.selfGateCap).
    /** Autotroph: build biomass (and hold the cytoplasm 'ab' reserve) up to this. */
    const val AUTOTROPH_GROW_BIOMASS = 3 * CHEMISTRY_SCALE
    /** Autotroph: divide once biomass exceeds this (< GROW). */
    const val AUTOTROPH_DIVIDE_BIOMASS = 2 * CHEMISTRY_SCALE
    /** Heterotroph: build biomass off stored 'ab' up to this. */
    const val HETEROTROPH_GROW_BIOMASS = 4 * CHEMISTRY_SCALE
    /** Heterotroph: divide once biomass exceeds this (< GROW). */
    const val HETEROTROPH_DIVIDE_BIOMASS = 3 * CHEMISTRY_SCALE
}
