package org.emerge.demo.cyto.sim

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

    // ── Seed reservoir: a Gaussian bump of free monomers around each light source ─────────────────────
    /** Peak free-monomer count seeded at a source grid cell — the matter the world starts with per
     *  source (hence the early population ceiling, until it's drawn down / recycled). */
    const val MATTER_PEAK = 512
    /** Gaussian radius of the seeded matter clumps (logical units) — decoupled from the light falloff so
     *  nutrient niches can be tight (low total starting matter) without dimming the light field. */
    const val MATTER_FALLOFF = 70f
    /** The free monomer species the world is seeded with (the starting matter alphabet). */
    val SEED_MONOMERS = listOf("a", "b", "c", "d", "e", "f", "g")

    // ── Seed cell composition (a freshly-spawned / founder cell) ──────────────────────────────────────
    /** Founder autotroph's starting cytoplasm (a small a/b reserve to bootstrap bonding before passive
     *  uptake kicks in). */
    val SEED_CYTOPLASM: Map<String, Int> = mapOf("a" to 4, "b" to 4)
    /** Biomass a freshly-spawned cell (and the founder) starts with — a little structure so it doesn't
     *  instantly die to the death-on-empty-biomass rule. */
    val STARTER_BIOMASS: Map<String, Int> = mapOf("ab" to 8)

    // ── Seed genome thresholds — the *starting* values of evolvable gene gates (structure in CytoGenes) ─
    /** Autotroph: cytoplasm 'ab' kept back (passively leaks to the environment → food for heterotrophs). */
    const val AUTOTROPH_LEAK_RESERVE = 4
    /** Autotroph: divide once biomass reaches this many bonds. */
    const val AUTOTROPH_DIVIDE_BIOMASS = 8
    /** Heterotroph: cytoplasm 'ab' kept as an energy reserve. */
    const val HETEROTROPH_RESERVE = 2
    /** Heterotroph: divide once biomass reaches this many bonds. */
    const val HETEROTROPH_DIVIDE_BIOMASS = 8
}
