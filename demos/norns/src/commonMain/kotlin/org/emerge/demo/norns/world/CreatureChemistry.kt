package org.emerge.demo.norns.world

import org.emerge.demo.norns.bio.Biochemistry
import org.emerge.demo.norns.bio.ChemistryState
import org.emerge.demo.norns.bio.Emitter
import org.emerge.demo.norns.bio.Reaction
import org.emerge.demo.norns.bio.Receptor

/**
 * A creature's drives expressed as real **biochemistry** (DESIGN.md G8): hunger, the mating urge,
 * and fatigue are chemical concentrations driven by the engine from subsystem 1, not bare floats.
 * This closes the seam where the tested biochemistry engine wasn't used by the living creature.
 *
 * Network: a metabolism locus produces HUNGER (gain = the heritable [metabolism]); a fertility
 * locus produces URGE; an exertion locus produces FATIGUE while the creature is busy; eating
 * pulses GLUCOSE which reacts HUNGER away; receptors publish the drives for the brain. Resting
 * recovers fatigue. Effective dynamics keep the colony viable; everything flows through
 * emitters/reactions/receptors — the faithful C1 architecture and the substrate for the deeper
 * biochem gaps.
 */
class CreatureChemistry(val metabolism: Float, cfg: NornsConfig) {
    private val state = ChemistryState(CHEMICALS, LOCI)
    private val bio = Biochemistry(
        chemicalCount = CHEMICALS,
        halfLives = floatArrayOf(0f, 0f, GLUCOSE_HALF_LIFE, 0f, FEELING_HALF_LIFE, FEELING_HALF_LIFE), // hunger/urge/fatigue persist; glucose + feelings clear
        reactions = listOf(Reaction(listOf(GLUCOSE to 1f, HUNGER to 1f), emptyList(), rate = 1f)),
        emitters = listOf(
            Emitter(locus = L_METABOLISM, chemical = HUNGER, gain = metabolism),
            Emitter(locus = L_FERTILITY, chemical = URGE, gain = cfg.matingRate),
            Emitter(locus = L_FOOD, chemical = GLUCOSE, gain = cfg.eatAmount),
            Emitter(locus = L_EXERTION, chemical = FATIGUE, gain = cfg.fatigueRate),
        ),
        receptors = listOf(
            Receptor(chemical = HUNGER, locus = L_HUNGER_OUT, gain = 1f),
            Receptor(chemical = URGE, locus = L_URGE_OUT, gain = 1f),
            Receptor(chemical = FATIGUE, locus = L_FATIGUE_OUT, gain = 1f),
        ),
    )

    /** Drive levels, read by the brain + biology. */
    val hunger: Float get() = state.concentration[HUNGER]
    val urge: Float get() = state.concentration[URGE]
    val fatigue: Float get() = state.concentration[FATIGUE]
    /** Player-interaction feelings (Hand reward/punishment), read by the renderer for expression. */
    val pleasure: Float get() = state.concentration[PLEASURE]
    val pain: Float get() = state.concentration[PAIN]
    fun addPleasure(amount: Float) { state.concentration[PLEASURE] = (state.concentration[PLEASURE] + amount).coerceAtMost(ChemistryState.MAX_CONCENTRATION) }
    fun addPain(amount: Float) { state.concentration[PAIN] = (state.concentration[PAIN] + amount).coerceAtMost(ChemistryState.MAX_CONCENTRATION) }

    /** One tick of metabolism. [exerting] = the creature is busy (not resting) → fatigue builds. */
    fun tick(fertileActive: Boolean, exerting: Boolean = false) {
        state.locus[L_METABOLISM] = 1f
        state.locus[L_FERTILITY] = if (fertileActive) 1f else 0f
        state.locus[L_EXERTION] = if (exerting) 1f else 0f
        bio.tick(state)
        state.locus[L_FOOD] = 0f // the eat pulse is one-shot
    }

    fun eat() { state.locus[L_FOOD] = 1f }
    fun recover(amount: Float) { state.concentration[FATIGUE] = (state.concentration[FATIGUE] - amount).coerceAtLeast(0f) }
    fun resetUrge() { state.concentration[URGE] = 0f }
    fun setHunger(v: Float) { state.concentration[HUNGER] = v.coerceIn(0f, ChemistryState.MAX_CONCENTRATION) }

    companion object {
        const val HUNGER = 0; const val URGE = 1; const val GLUCOSE = 2; const val FATIGUE = 3
        const val PLEASURE = 4; const val PAIN = 5   // player reward/punishment (Hand); pulsed + decays
        const val CHEMICALS = 6
        const val L_METABOLISM = 0; const val L_FERTILITY = 1; const val L_FOOD = 2; const val L_EXERTION = 3
        const val L_HUNGER_OUT = 4; const val L_URGE_OUT = 5; const val L_FATIGUE_OUT = 6
        const val LOCI = 7
        private const val GLUCOSE_HALF_LIFE = 4f
        private const val FEELING_HALF_LIFE = 18f       // a tickle's glow / a slap's sting fades over ~1.5s
    }
}
