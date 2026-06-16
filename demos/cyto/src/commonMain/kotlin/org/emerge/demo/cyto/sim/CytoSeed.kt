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
    const val MATTER_PEAK = 512_000
    /** Gaussian radius of the seeded matter clumps (logical units) — decoupled from the light falloff so
     *  nutrient niches can be tight (low total starting matter) without dimming the light field. */
    const val MATTER_FALLOFF = 70f
    /** The free monomer species the world is seeded with — the **k=3 element alphabet**. Capped at 3 to
     *  bound the molecule species space (3 elements → ≤1884 possible molecules), which the upcoming
     *  dense-chemistry representation depends on. Mutation's alphabet matches (a,b,c). */
    val SEED_MONOMERS = listOf("a", "b", "c")
    /** Uniform reservoir: seed EVERY grid cell with [MATTER_UNIFORM_LEVEL] of each monomer instead of the
     *  Gaussian clumps around the sources — for the moving-light world, where the daylight grazes the whole
     *  torus and needs substrate everywhere (diffusion then refills grazed patches behind the band).
     *  false = the 4 Gaussian clumps (matches the static 4-source world). */
    const val MATTER_UNIFORM = false
    /** Per-monomer count per grid cell when [MATTER_UNIFORM]. Total matter ≈ GRID_RES² × this × species,
     *  so keep it small — ~8 ≈ the clumped world's ~216k atoms spread evenly. */
    const val MATTER_UNIFORM_LEVEL = 8_000

    // ── Seed cell composition (a freshly-spawned / founder cell) ──────────────────────────────────────
    /** Founder autotroph's starting cytoplasm (a small a/b reserve to bootstrap bonding before passive
     *  uptake kicks in). */
    val SEED_CYTOPLASM: Map<String, Int> = mapOf("a" to 4_000, "b" to 4_000)
    /** Biomass a freshly-spawned cell (and the founder) starts with — a little structure so it doesn't
     *  instantly die to the death-on-empty-biomass rule. */
    val STARTER_BIOMASS: Map<String, Int> = mapOf("ab" to 8_000)

    // ── Seed genome thresholds — the *starting* values of evolvable gene gates (structure in CytoGenes) ─
    // Grow > divide on purpose: with sub-tick interpolation a growth gene fills biomass exactly up to its
    // GROW gate and stops, so the (lower) DIVIDE gate is what it crosses — if they were equal the cell would
    // park at the threshold and never divide (see CytoBiologyCore.selfGateCap).
    /** Autotroph: build biomass (and hold the cytoplasm 'ab' reserve) up to this. */
    const val AUTOTROPH_GROW_BIOMASS = 8_000
    /** Autotroph: divide once biomass exceeds this (< GROW). */
    const val AUTOTROPH_DIVIDE_BIOMASS = 6_000
    /** Heterotroph: build biomass off stored 'ab' up to this. */
    const val HETEROTROPH_GROW_BIOMASS = 12_000
    /** Heterotroph: divide once biomass exceeds this (< GROW). */
    const val HETEROTROPH_DIVIDE_BIOMASS = 8_000
}
