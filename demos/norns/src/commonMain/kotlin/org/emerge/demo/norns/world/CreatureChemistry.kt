package org.emerge.demo.norns.world

import org.emerge.demo.norns.bio.Biochemistry
import org.emerge.demo.norns.bio.ChemistryState
import org.emerge.demo.norns.bio.Emitter
import org.emerge.demo.norns.bio.Reaction
import org.emerge.demo.norns.bio.Receptor

/**
 * A creature's drives expressed as real **biochemistry** (DESIGN.md G8): hunger and the mating
 * urge are chemical concentrations driven by the engine from subsystem 1, not bare floats. This
 * closes the seam where the tested biochemistry engine wasn't actually used by the living creature.
 *
 * The network (the validated subsystem-1 hunger loop, generalised): a metabolism locus produces
 * HUNGER each tick (gain = the creature's heritable [metabolism]); a fertility locus produces the
 * URGE; eating pulses GLUCOSE, which reacts HUNGER away; receptors publish hunger/urge for the
 * brain to read. Effective dynamics match the previous float drives (so the colony stays viable),
 * but everything now flows through emitters/reactions/receptors — the faithful C1 architecture,
 * and the substrate the deeper biochem gaps (G3 modes, G6 organ chemistry) build on.
 */
class CreatureChemistry(val metabolism: Float, cfg: NornsConfig) {
    private val state = ChemistryState(CHEMICALS, LOCI)
    private val bio = Biochemistry(
        chemicalCount = CHEMICALS,
        halfLives = floatArrayOf(0f, 0f, GLUCOSE_HALF_LIFE), // hunger/urge persist; glucose clears
        reactions = listOf(Reaction(listOf(GLUCOSE to 1f, HUNGER to 1f), emptyList(), rate = 1f)),
        emitters = listOf(
            Emitter(locus = L_METABOLISM, chemical = HUNGER, gain = metabolism),
            Emitter(locus = L_FERTILITY, chemical = URGE, gain = cfg.matingRate),
            Emitter(locus = L_FOOD, chemical = GLUCOSE, gain = cfg.eatAmount),
        ),
        receptors = listOf(
            Receptor(chemical = HUNGER, locus = L_HUNGER_OUT, gain = 1f),
            Receptor(chemical = URGE, locus = L_URGE_OUT, gain = 1f),
        ),
    )

    /** Drive levels, read by the brain + biology. Equal to the receptor outputs after [tick]. */
    val hunger: Float get() = state.concentration[HUNGER]
    val urge: Float get() = state.concentration[URGE]

    /** One tick of metabolism: drives are produced/decayed/reacted by the engine. */
    fun tick(fertileActive: Boolean) {
        state.locus[L_METABOLISM] = 1f
        state.locus[L_FERTILITY] = if (fertileActive) 1f else 0f
        bio.tick(state)
        state.locus[L_FOOD] = 0f // the eat pulse is one-shot; consumed by this tick's emitter
    }

    /** Eating: pulse glucose, which reacts hunger down on the next [tick]. */
    fun eat() { state.locus[L_FOOD] = 1f }

    fun resetUrge() { state.concentration[URGE] = 0f }
    fun setHunger(v: Float) { state.concentration[HUNGER] = v.coerceIn(0f, ChemistryState.MAX_CONCENTRATION) }

    companion object {
        const val HUNGER = 0; const val URGE = 1; const val GLUCOSE = 2
        const val CHEMICALS = 3
        const val L_METABOLISM = 0; const val L_FERTILITY = 1; const val L_FOOD = 2
        const val L_HUNGER_OUT = 3; const val L_URGE_OUT = 4
        const val LOCI = 5
        private const val GLUCOSE_HALF_LIFE = 4f
    }
}
