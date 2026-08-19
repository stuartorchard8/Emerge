package org.emerge.demo.outofspace.chem

/**
 * The species that can be in the air or in a pipe — the ones that are ever a gas or a liquid.
 *
 * ### Why this exists
 *
 * Nothing has ever stopped serpentine being a gas. The atmosphere is a dense `tiles × Species.COUNT`
 * array, [org.emerge.demo.outofspace.world.Stuff.pressureAt] states the missing invariant as a
 * *comment* — "assumes all species are gaseous, invalid if any are solid or liquid" — and the
 * mineral vaporizer put whatever it was handed into the air, because it looped [Species.ALL] and
 * there was no other list to loop.
 *
 * There are 165 species and about twenty of them can be a fluid. The other 145 columns of the
 * atmosphere are not merely empty, they are **unreachable by any correct program**, and they cost
 * some 6 MB of the 7.6 MB the field copies every tick.
 *
 * So this is the list, and the point of it being a separate type rather than a predicate on
 * [Species] is that `MassIndex(tile, Species.Serpentine)` should not compile. An invariant the type
 * system carries cannot be forgotten at a call site; one stated in a doc comment already was.
 *
 * ### What is in it, and what is deliberately not
 *
 * Everything under `THE VOLATILES` in [Species], which is the fifteen that were always meant to be
 * here. Then three groups that decomposition chemistry needs and that are just as genuinely fluid:
 *
 *  - the **halogens**, which are gases or (bromine) a liquid at any temperature a vessel sees;
 *  - the **volatile metals** — mercury above all, since roasting cinnabar *is* mercury vapour and
 *    always has been, plus zinc and cadmium, which boil low enough that a roaster loses them;
 *  - **sulfur**, which a hot sulfide bed gives off directly.
 *
 * ⚠️ **Molten metals are not here, and that is a deferral rather than a claim.** Liquid iron is a
 * reasonable thing to want in a pipe eventually. It is left out because the phase model has nothing
 * to say about it yet — [CRITICAL] carries four gases and water, and nothing else has a critical
 * point — not because a molten metal is not a fluid. Adding one later is an entry in this enum and
 * a wider array; nothing is rewritten and no call site changes. The door is held open on purpose.
 *
 * ### Ordering
 *
 * Declaration order is this enum's own index space, and it is **not** [Species] order. Nothing on
 * disk depends on it — [org.emerge.demo.outofspace.world.Save] writes species by name — so it is
 * free to change. It is the stride of every fluid array, so the only thing a reorder invalidates is
 * a raw index held across the change, and nothing holds one.
 */
enum class Fluid(val species: Species) {
    // ── The volatiles proper: what a comet is made of, and what a vessel breathes ──
    Water(Species.Water),
    CarbonDioxide(Species.CarbonDioxide),
    Ammonia(Species.Ammonia),
    Methane(Species.Methane),
    CarbonMonoxide(Species.CarbonMonoxide),
    HydrogenSulfide(Species.HydrogenSulfide),
    SulfurDioxide(Species.SulfurDioxide),
    Nitrogen(Species.Nitrogen),
    Hydrogen(Species.Hydrogen),
    Oxygen(Species.Oxygen),
    Argon(Species.Argon),
    Helium(Species.Helium),
    Neon(Species.Neon),
    Krypton(Species.Krypton),
    Xenon(Species.Xenon),

    // ── The halogens, as elements. Products of cracking a salt, and none of them stay solid. ──
    Fluorine(Species.Fluorine),
    Chlorine(Species.Chlorine),
    Bromine(Species.Bromine),
    Iodine(Species.Iodine),

    // ── Volatile enough to leave a roasting bed as vapour ──
    Mercury(Species.Mercury),
    Zinc(Species.Zinc),
    Cadmium(Species.Cadmium),
    Sulfur(Species.Sulfur),
    ;

    companion object {
        /** Cached for the same reason [Species.ALL] is: `entries` allocates on some targets. */
        val ALL: List<Fluid> = entries.toList()

        /** The stride of every fluid array. About a seventh of [Species.COUNT], which is the point. */
        val COUNT: Int = ALL.size

        /**
         * [Fluid] by [Species] ordinal, or null — the table behind [Species.fluid].
         *
         * An array rather than a map because this is asked per species inside loops that have
         * already measured badly once; [Species.ALL] exists for the same reason.
         */
        private val BY_SPECIES: Array<Fluid?> = arrayOfNulls<Fluid>(Species.COUNT).also { table ->
            for (f in ALL) table[f.species.ordinal] = f
        }

        /** @suppress internal to [Species.fluid]. */
        fun of(species: Species): Fluid? = BY_SPECIES[species.ordinal]
    }
}

/**
 * This species as a fluid, or null if it can only ever be a solid — the one bridge between the two
 * index spaces, and the only place a [Species] may become a fluid array's key.
 *
 * A null here is not a failed lookup. It is the answer: serpentine is not a fluid, and a caller
 * that wanted to put it in the air is the thing that is wrong.
 */
val Species.fluid: Fluid? get() = Fluid.of(this)

/** True if this species can ever be in the air or a pipe. */
val Species.isFluid: Boolean get() = Fluid.of(this) != null
