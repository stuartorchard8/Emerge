package org.emerge.demo.norns.bio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-verification harness for the biochemistry engine (subsystem 1). Two flavours:
 *  - internal consistency: half-life decay, reaction stoichiometry, emitter/receptor activation,
 *    clamping, and determinism;
 *  - a behavioural proxy: a closed hunger-regulation loop, the homeostatic shape every higher
 *    Creatures system is built on.
 *
 * These prove the mechanism is correct and life-shaped; they do NOT prove fidelity to the
 * original's constants (DESIGN.md gap G1) — that's for tuning.
 */
class BiochemistryTest {

    private fun eqf(a: Float, b: Float, tol: Float = 1e-4f, msg: String = "") =
        assertTrue(kotlin.math.abs(a - b) <= tol, "$msg expected≈$b got=$a")

    @Test
    fun halfLifeHalvesConcentrationAfterHalfLifeTicks() {
        val bio = Biochemistry(chemicalCount = 1, halfLives = floatArrayOf(10f))
        val s = ChemistryState(1, 0)
        s.concentration[0] = 1f
        repeat(10) { bio.tick(s) }
        eqf(s.concentration[0], 0.5f, msg = "after one half-life:")
        repeat(10) { bio.tick(s) }
        eqf(s.concentration[0], 0.25f, msg = "after two half-lives:")
    }

    @Test
    fun zeroHalfLifeMeansNoDecay() {
        val bio = Biochemistry(chemicalCount = 1, halfLives = floatArrayOf(0f))
        val s = ChemistryState(1, 0)
        s.concentration[0] = 0.7f
        repeat(100) { bio.tick(s) }
        eqf(s.concentration[0], 0.7f, msg = "no-decay chemical unchanged:")
    }

    @Test
    fun reactionConsumesReactantsAndProducesProductsByStoichiometry() {
        // A + B -> C at rate 0.5, no decay. One tick: limiting=min(1,1)=1, fires=0.5.
        val bio = Biochemistry(
            chemicalCount = 3,
            halfLives = floatArrayOf(0f, 0f, 0f),
            reactions = listOf(Reaction(listOf(0 to 1f, 1 to 1f), listOf(2 to 1f), rate = 0.5f)),
        )
        val s = ChemistryState(3, 0)
        s.concentration[0] = 1f; s.concentration[1] = 1f
        bio.tick(s)
        eqf(s.concentration[0], 0.5f, msg = "reactant A:")
        eqf(s.concentration[1], 0.5f, msg = "reactant B:")
        eqf(s.concentration[2], 0.5f, msg = "product C:")
    }

    @Test
    fun reactionIsCappedByTheLimitingReactant() {
        // A scarce (0.2), B plentiful (1.0), rate 1.0 -> fires = min(0.2, 1.0) = 0.2.
        val bio = Biochemistry(
            chemicalCount = 3,
            halfLives = floatArrayOf(0f, 0f, 0f),
            reactions = listOf(Reaction(listOf(0 to 1f, 1 to 1f), listOf(2 to 1f), rate = 1f)),
        )
        val s = ChemistryState(3, 0)
        s.concentration[0] = 0.2f; s.concentration[1] = 1f
        bio.tick(s)
        eqf(s.concentration[0], 0f, msg = "limiting reactant exhausted:")
        eqf(s.concentration[1], 0.8f, msg = "surplus reactant:")
        eqf(s.concentration[2], 0.2f, msg = "product capped by limiter:")
    }

    @Test
    fun emitterAddsChemicalProportionalToLocusAboveThreshold() {
        val bio = Biochemistry(
            chemicalCount = 1, halfLives = floatArrayOf(0f),
            emitters = listOf(Emitter(locus = 0, chemical = 0, gain = 0.5f, threshold = 0.3f)),
        )
        val s = ChemistryState(1, 1)
        s.locus[0] = 0.8f
        bio.tick(s)
        eqf(s.concentration[0], 0.25f, msg = "emit gain*(locus-threshold):") // 0.5*(0.8-0.3)

        // Below threshold: no emission.
        val s2 = ChemistryState(1, 1)
        s2.locus[0] = 0.2f
        bio.tick(s2)
        eqf(s2.concentration[0], 0f, msg = "below-threshold locus emits nothing:")
    }

    @Test
    fun receptorPublishesChemicalToLocusWithGainAndNominal() {
        val bio = Biochemistry(
            chemicalCount = 1, halfLives = floatArrayOf(0f),
            receptors = listOf(Receptor(chemical = 0, locus = 0, gain = 2f, threshold = 0.1f, nominal = 0.05f)),
        )
        val s = ChemistryState(1, 1)
        s.concentration[0] = 0.6f
        bio.tick(s)
        eqf(s.locus[0], 1.05f, msg = "nominal + gain*(conc-threshold):") // 0.05 + 2*(0.6-0.1)

        // Inhibitory receptor (negative gain) drives the locus down.
        val inhib = Biochemistry(
            chemicalCount = 1, halfLives = floatArrayOf(0f),
            receptors = listOf(Receptor(chemical = 0, locus = 0, gain = -1f, nominal = 1f)),
        )
        val s2 = ChemistryState(1, 1)
        s2.concentration[0] = 0.4f
        inhib.tick(s2)
        eqf(s2.locus[0], 0.6f, msg = "inhibitory receptor:") // 1 + (-1)*0.4
    }

    @Test
    fun digitalEmitterDosesAFixedAmountAboveThreshold() {
        val bio = Biochemistry(
            chemicalCount = 1, halfLives = floatArrayOf(0f),
            emitters = listOf(Emitter(locus = 0, chemical = 0, gain = 0.2f, threshold = 0.1f, mode = EmitterMode.DIGITAL)),
        )
        // a fixed dose regardless of how far above threshold the locus is
        val low = ChemistryState(1, 1).apply { locus[0] = 0.3f }; bio.tick(low)
        val high = ChemistryState(1, 1).apply { locus[0] = 0.9f }; bio.tick(high)
        eqf(low.concentration[0], 0.2f, msg = "digital dose:")
        eqf(high.concentration[0], 0.2f, msg = "digital dose is flat, not proportional:")
        // below threshold: nothing
        val off = ChemistryState(1, 1).apply { locus[0] = 0.05f }; bio.tick(off)
        eqf(off.concentration[0], 0f, msg = "below threshold:")
    }

    @Test
    fun clockedEmitterFiresOnlyEveryNTicks() {
        val bio = Biochemistry(
            chemicalCount = 1, halfLives = floatArrayOf(0f),
            emitters = listOf(Emitter(locus = 0, chemical = 0, gain = 0.1f, clock = 3)),
        )
        val s = ChemistryState(1, 1).apply { locus[0] = 1f }
        repeat(6) { bio.tick(s) } // fires at ticks 3 and 6 only → 2 × 0.1
        eqf(s.concentration[0], 0.2f, msg = "clocked emitter fires every 3rd tick:")
    }

    @Test
    fun concentrationsAreClampedToRange() {
        // A locus floods a chemical past the ceiling every tick; it must clamp, not run away.
        val bio = Biochemistry(
            chemicalCount = 1, halfLives = floatArrayOf(0f),
            emitters = listOf(Emitter(locus = 0, chemical = 0, gain = 10f)),
        )
        val s = ChemistryState(1, 1)
        s.locus[0] = 1f
        repeat(5) { bio.tick(s) }
        eqf(s.concentration[0], ChemistryState.MAX_CONCENTRATION, msg = "clamped to ceiling:")
    }

    @Test
    fun tickIsDeterministic() {
        val bio = hungerNetwork()
        fun run(): ChemistryState {
            val s = ChemistryState(2, 3)
            repeat(200) { tick -> s.locus[FOOD] = if (tick % 3 == 0) 1f else 0f; s.locus[METABOLISM] = 1f; bio.tick(s) }
            return s
        }
        val a = run(); val b = run()
        for (i in a.concentration.indices) assertEquals(a.concentration[i].toRawBits(), b.concentration[i].toRawBits(), "concentration[$i]")
        for (i in a.locus.indices) assertEquals(a.locus[i].toRawBits(), b.locus[i].toRawBits(), "locus[$i]")
    }

    @Test
    fun hungerRegulationLoopCloses() {
        // The homeostatic proxy for "alive": metabolism continuously raises a HUNGER chemical;
        // eating raises GLUCOSE, which reacts hunger away; a receptor publishes hunger as a drive.
        // Starving -> the drive climbs; fed -> the loop holds it low. (Constants are placeholders;
        // this checks the loop CLOSES, not that it feels right — DESIGN.md G1/G2.)
        val bio = hungerNetwork()

        val starving = ChemistryState(2, 3)
        val baseline = starving.locus[HUNGER_DRIVE]
        repeat(100) { starving.locus[FOOD] = 0f; starving.locus[METABOLISM] = 1f; bio.tick(starving) }

        val fed = ChemistryState(2, 3)
        repeat(100) { fed.locus[FOOD] = 1f; fed.locus[METABOLISM] = 1f; bio.tick(fed) }

        assertTrue(starving.locus[HUNGER_DRIVE] > baseline + 0.5f,
            "starving drive should climb: ${starving.locus[HUNGER_DRIVE]}")
        assertTrue(fed.locus[HUNGER_DRIVE] < starving.locus[HUNGER_DRIVE],
            "feeding should regulate the drive below starving (fed=${fed.locus[HUNGER_DRIVE]} starving=${starving.locus[HUNGER_DRIVE]})")
        assertTrue(fed.locus[HUNGER_DRIVE] < 0.2f,
            "fed drive should stay low: ${fed.locus[HUNGER_DRIVE]}")
    }

    // chemical / locus indices for the hunger network
    private val GLUCOSE = 0; private val HUNGER = 1
    private val FOOD = 0; private val METABOLISM = 1; private val HUNGER_DRIVE = 2

    private fun hungerNetwork() = Biochemistry(
        chemicalCount = 2,
        halfLives = floatArrayOf(5f, 0f), // glucose clears; hunger persists until reacted away
        reactions = listOf(Reaction(listOf(GLUCOSE to 1f, HUNGER to 1f), emptyList(), rate = 0.5f)),
        emitters = listOf(
            Emitter(locus = METABOLISM, chemical = HUNGER, gain = 0.02f),
            Emitter(locus = FOOD, chemical = GLUCOSE, gain = 0.5f),
        ),
        receptors = listOf(Receptor(chemical = HUNGER, locus = HUNGER_DRIVE, gain = 1f)),
    )
}
