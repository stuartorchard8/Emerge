package org.emerge.demo.norns.gene

import org.emerge.demo.norns.bio.Biochemistry
import org.emerge.demo.norns.bio.Emitter
import org.emerge.demo.norns.bio.Reaction
import org.emerge.demo.norns.bio.Receptor

/**
 * A creature's heritable genome: an ordered sequence of [Gene]s that, when [express]ed, builds
 * the phenotype. For now genes encode only the biochemistry ([Biochemistry]); brain and biology
 * gene variants join the [Gene] hierarchy in their own subsystems and add their own express
 * targets.
 *
 * Reproduction is [crossover] (per-locus parent choice over aligned genomes) followed by
 * [mutate] — the Creatures mechanism that makes offspring resemble both parents while drifting.
 * Both are deterministic given a [GeneRng] seed, so a lineage replays bit-identically.
 *
 * Structural variation (genomes of differing length, gene duplication/deletion, index mutation)
 * is deferred — DESIGN.md assumption A6: crossover assumes positionally-aligned, same-shape
 * parents, and mutation perturbs numeric fields only.
 */
class Genome(
    val chemicalCount: Int,
    val locusCount: Int,
    val genes: List<Gene>,
) {
    /** Walks the genes into a runnable [Biochemistry] network (the biochemical phenotype). */
    fun expressBiochemistry(): Biochemistry {
        val halfLives = FloatArray(chemicalCount) // default 0 = no decay
        val emitters = ArrayList<Emitter>()
        val receptors = ArrayList<Receptor>()
        val reactions = ArrayList<Reaction>()
        for (g in genes) when (g) {
            is HalfLifeGene -> halfLives[g.chemical] = g.halfLife
            is EmitterGene -> emitters.add(Emitter(g.locus, g.chemical, g.gain, g.threshold))
            is ReceptorGene -> receptors.add(Receptor(g.chemical, g.locus, g.gain, g.threshold, g.nominal))
            is ReactionGene -> reactions.add(Reaction(g.reactants, g.products, g.rate))
        }
        return Biochemistry(chemicalCount, halfLives, reactions, emitters, receptors)
    }

    /**
     * Sexual recombination with [other]: each gene locus is taken from one parent or the other by
     * a coin flip. Parents must be positionally aligned (same gene count); the shorter length is
     * used defensively. Deterministic in [rng].
     */
    fun crossover(other: Genome, rng: GeneRng): Genome {
        val n = minOf(genes.size, other.genes.size)
        val child = ArrayList<Gene>(n)
        for (i in 0 until n) child.add(if (rng.nextFloat() < 0.5f) genes[i] else other.genes[i])
        return Genome(chemicalCount, locusCount, child)
    }

    /** Point-mutates each mutable gene with probability [rate], perturbing its numeric fields. */
    fun mutate(rate: Float, rng: GeneRng): Genome =
        Genome(chemicalCount, locusCount, genes.map { g ->
            if (g.header.mutable && rng.nextFloat() < rate) g.mutate(rng) else g
        })

    /** Convenience: [crossover] then [mutate] — produce an offspring genome from two parents. */
    fun reproduceWith(partner: Genome, mutationRate: Float, rng: GeneRng): Genome =
        crossover(partner, rng).mutate(mutationRate, rng)
}

/** Header shared by every gene. [mutable] gates mutation (Creatures genes carry such flags).
 *  Life-stage / sex activation gating is deferred to the biology subsystem (DESIGN.md A5). */
data class GeneHeader(val mutable: Boolean = true)

/** A heritable gene. Variants map 1:1 onto the biochemistry primitives they express into. */
sealed interface Gene {
    val header: GeneHeader
    /** A copy with numeric fields perturbed within bounds. Called only for mutable genes. */
    fun mutate(rng: GeneRng): Gene
}

/** Expresses to an [Emitter] (locus → chemical). */
data class EmitterGene(
    val locus: Int,
    val chemical: Int,
    val gain: Float,
    val threshold: Float,
    override val header: GeneHeader = GeneHeader(),
) : Gene {
    override fun mutate(rng: GeneRng) = copy(gain = rng.perturbGain(gain), threshold = rng.perturbUnit(threshold))
}

/** Expresses to a [Receptor] (chemical → locus). */
data class ReceptorGene(
    val chemical: Int,
    val locus: Int,
    val gain: Float,
    val threshold: Float,
    val nominal: Float,
    override val header: GeneHeader = GeneHeader(),
) : Gene {
    override fun mutate(rng: GeneRng) =
        copy(gain = rng.perturbGain(gain), threshold = rng.perturbUnit(threshold), nominal = rng.perturbUnit(nominal))
}

/** Expresses to a [Reaction]. Mutation perturbs the rate and stoichiometric amounts (not the
 *  participating chemical indices — that's structural, deferred). */
data class ReactionGene(
    val reactants: List<Pair<Int, Float>>,
    val products: List<Pair<Int, Float>>,
    val rate: Float,
    override val header: GeneHeader = GeneHeader(),
) : Gene {
    override fun mutate(rng: GeneRng) = copy(
        reactants = reactants.map { (c, a) -> c to rng.perturbAmount(a) },
        products = products.map { (c, a) -> c to rng.perturbAmount(a) },
        rate = rng.perturbRate(rate),
    )
}

/** Sets a chemical's decay half-life (ticks; 0 = no decay). */
data class HalfLifeGene(
    val chemical: Int,
    val halfLife: Float,
    override val header: GeneHeader = GeneHeader(),
) : Gene {
    override fun mutate(rng: GeneRng) = copy(halfLife = rng.perturbHalfLife(halfLife))
}

/**
 * Deterministic PRNG for genetics, using the same LCG as the engine's `SimBuilder.nextRandomInt`
 * so genome operations replay bit-identically across runs/platforms. Also provides the bounded
 * field-perturbation helpers mutation uses, so mutated values can never escape sane ranges.
 */
class GeneRng(seed: Long) {
    private var state = seed

    fun nextInt(): Int {
        state = state * 2862933555777941757L + 3037000493L
        return (state ushr 32).toInt()
    }

    /** Uniform in [0, 1). */
    fun nextFloat(): Float = (nextInt().toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f

    /** Uniform in [-1, 1). */
    fun signed(): Float = nextFloat() * 2f - 1f

    fun perturbGain(v: Float): Float = (v + signed() * GAIN_STEP).coerceIn(-MAX_GAIN, MAX_GAIN)
    fun perturbUnit(v: Float): Float = (v + signed() * UNIT_STEP).coerceIn(0f, 1f)
    fun perturbRate(v: Float): Float = (v + signed() * RATE_STEP).coerceIn(RATE_MIN, 1f)
    fun perturbAmount(v: Float): Float = (v + signed() * AMOUNT_STEP).coerceIn(0f, MAX_AMOUNT)
    fun perturbHalfLife(v: Float): Float = (v + signed() * HALF_LIFE_STEP).coerceAtLeast(0f)

    companion object {
        const val MAX_GAIN = 8f
        const val MAX_AMOUNT = 4f
        const val RATE_MIN = 1e-3f
        private const val GAIN_STEP = 0.25f
        private const val UNIT_STEP = 0.1f
        private const val RATE_STEP = 0.1f
        private const val AMOUNT_STEP = 0.2f
        private const val HALF_LIFE_STEP = 2f
    }
}
