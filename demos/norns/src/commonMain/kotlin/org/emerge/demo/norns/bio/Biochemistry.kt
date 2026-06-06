package org.emerge.demo.norns.bio

import kotlin.math.pow

/**
 * A creature's biochemical network — the fixed (genome-derived, later) set of [reactions],
 * [emitters], [receptors], and per-chemical [halfLives] that defines how its [ChemistryState]
 * evolves. The network is immutable; [tick] mutates the state in place.
 *
 * This is the Creatures biochemistry mechanism rebuilt on plain floats: a homeostatic soup
 * where loci drive chemicals (emitters), chemicals transform (reactions) and decay (half-lives),
 * and chemicals drive loci back (receptors). Drives, organs, metabolism and the brain are all
 * layered on top by reading/writing loci — they never touch this engine directly.
 *
 * **Tick order** (DESIGN.md A3): emitters → reactions → decay → receptors. Emitters inject from
 * this tick's loci (set by sensors/organs/last tick's receptors); reactions transform; decay
 * applies half-lives; receptors publish the resulting chemistry back to the loci the rest of the
 * creature reads. Deterministic: pure float arithmetic over the state arrays.
 */
class Biochemistry(
    val chemicalCount: Int,
    /** Per-chemical decay half-life in ticks; `<= 0` means the chemical does not decay. */
    val halfLives: FloatArray,
    val reactions: List<Reaction> = emptyList(),
    val emitters: List<Emitter> = emptyList(),
    val receptors: List<Receptor> = emptyList(),
) {
    init {
        require(halfLives.size == chemicalCount) {
            "halfLives size ${halfLives.size} != chemicalCount $chemicalCount"
        }
    }

    /** Precomputed per-tick multiplicative decay factor: 0.5^(1/halfLife), or 1 for no decay. */
    private val decayFactor = FloatArray(chemicalCount) { i ->
        val hl = halfLives[i]
        if (hl <= 0f) 1f else 0.5f.pow(1f / hl)
    }

    fun tick(state: ChemistryState) {
        emit(state)
        react(state)
        decay(state)
        receive(state)
        clampConcentrations(state)
    }

    // ── emitters: locus -> chemical ─────────────────────────────────────────────
    private fun emit(state: ChemistryState) {
        for (e in emitters) {
            val drive = state.locus[e.locus] - e.threshold
            if (drive > 0f) state.concentration[e.chemical] += e.gain * drive
        }
    }

    // ── reactions: reactants -> products ────────────────────────────────────────
    private fun react(state: ChemistryState) {
        val c = state.concentration
        for (r in reactions) {
            // Limiting-reactant model: the reaction fires `rate × min(conc_i / amount_i)` times.
            var limiting = Float.MAX_VALUE
            for ((chem, amount) in r.reactants) {
                if (amount <= 0f) continue
                val available = c[chem] / amount
                if (available < limiting) limiting = available
            }
            if (limiting == Float.MAX_VALUE) limiting = 0f // a reaction with no reactants does nothing
            val fires = r.rate * limiting
            if (fires <= 0f) continue
            for ((chem, amount) in r.reactants) c[chem] -= amount * fires
            for ((chem, amount) in r.products) c[chem] += amount * fires
        }
    }

    // ── half-life decay ──────────────────────────────────────────────────────────
    private fun decay(state: ChemistryState) {
        val c = state.concentration
        for (i in 0 until chemicalCount) c[i] *= decayFactor[i]
    }

    // ── receptors: chemical -> locus ─────────────────────────────────────────────
    private fun receive(state: ChemistryState) {
        for (r in receptors) {
            val signal = state.concentration[r.chemical] - r.threshold
            state.locus[r.locus] = r.nominal + r.gain * signal
        }
    }

    private fun clampConcentrations(state: ChemistryState) {
        val c = state.concentration
        for (i in 0 until chemicalCount) {
            if (c[i] < 0f) c[i] = 0f
            else if (c[i] > ChemistryState.MAX_CONCENTRATION) c[i] = ChemistryState.MAX_CONCENTRATION
        }
    }
}

/**
 * A chemical reaction: each tick fires `rate × (limiting reactant / its amount)` times,
 * consuming [reactants] and producing [products] in proportion. [rate] in `(0, 1]` is the
 * fraction of the limiting reactant converted per tick. Pairs are `(chemical index, amount)`.
 */
class Reaction(
    val reactants: List<Pair<Int, Float>>,
    val products: List<Pair<Int, Float>>,
    val rate: Float,
)

/**
 * Adds [chemical] proportional to how far the [locus] exceeds [threshold]: `gain × (locus −
 * threshold)` per tick (only while positive). The path by which sensors/organs/drives inject
 * chemistry. (C1's digital mode + per-emitter clocks are deferred — DESIGN.md gap G3.)
 */
class Emitter(val locus: Int, val chemical: Int, val gain: Float, val threshold: Float = 0f)

/**
 * Publishes a [locus] from a [chemical]'s concentration: `locus = nominal + gain × (conc −
 * threshold)`. A negative [gain] makes the receptor inhibitory. The path by which chemistry
 * drives the brain, organs, and drives.
 */
class Receptor(
    val chemical: Int,
    val locus: Int,
    val gain: Float,
    val threshold: Float = 0f,
    val nominal: Float = 0f,
)
