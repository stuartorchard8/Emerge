package org.emerge.demo.norns.brain

/**
 * A neural-network brain in the Creatures lineage: [Lobe]s of neurons wired by learnable
 * dendrite [Tract]s, with reward-modulated Hebbian learning. This is the engine's first
 * subsystem with no prior analogue.
 *
 * Each tick the brain [propagate]s — every destination lobe recomputes its neurons from the
 * incoming tracts (Σ source-output × dendrite-weight, then an activation) — and may [learn],
 * nudging dendrite weights by `learnRate × reward × pre × post`. A creature thus learns which
 * action (decision-lobe neuron) to take in which context (perception-lobe pattern) by being
 * rewarded for it: the dendrites that were co-active when reward arrived strengthen.
 *
 * Faithful in mechanism, simplified in detail: connectivity is dense per tract and the learning
 * rule is a fixed reward-gated Hebbian law. C1's per-tract **SVRule** virtual machine (a small
 * bytecode defining bespoke state/weight update rules) is deferred — DESIGN.md gap G4. The brain
 * couples to the rest of the creature only through neuron arrays (perception loaded from loci,
 * the chosen decision written to a locus), so it stays ignorant of biochemistry and world.
 */
class Brain(
    val lobes: List<Lobe>,
    val tracts: List<Tract>,
    val learnRate: Float,
    val weightDecay: Float = 0f,
    val maxWeight: Float = MAX_WEIGHT,
) {
    /**
     * Recomputes every destination lobe's outputs from its incoming tracts. Lobes that are not a
     * destination of any tract (perception/input lobes) keep their externally-set outputs.
     * Deterministic: tracts accumulate in list order, neurons in index order.
     */
    fun propagate() {
        // Accumulate per destination lobe (a lobe may receive several tracts), then activate.
        val accum = LinkedHashMap<Lobe, FloatArray>()
        for (t in tracts) {
            val acc = accum.getOrPut(t.dst) { FloatArray(t.dst.size) }
            for (d in 0 until t.dst.size) {
                val w = t.weight[d]
                var sum = 0f
                for (s in 0 until t.src.size) sum += t.src.output[s] * w[s]
                acc[d] += sum
            }
        }
        for ((lobe, acc) in accum) for (i in 0 until lobe.size) lobe.output[i] = activation(acc[i])
    }

    /**
     * Reward-modulated Hebbian update across all tracts: `Δw = learnRate × reward × pre × post`,
     * minus a small weight decay, clamped to `[0, maxWeight]`. A positive [reward] strengthens
     * dendrites whose source and destination neurons were both active; a negative one weakens
     * them. With `reward = 0`, only decay applies.
     */
    fun learn(reward: Float) {
        for (t in tracts) {
            for (d in 0 until t.dst.size) {
                val post = t.dst.output[d]
                val w = t.weight[d]
                for (s in 0 until t.src.size) {
                    val pre = t.src.output[s]
                    var nw = w[s] + learnRate * reward * pre * post - weightDecay * w[s]
                    if (nw < 0f) nw = 0f else if (nw > maxWeight) nw = maxWeight
                    w[s] = nw
                }
            }
        }
    }

    private fun activation(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

    companion object {
        const val MAX_WEIGHT = 4f
    }
}

/** A layer of neurons. [output] is read by downstream [Tract]s and written by activation (or set
 *  externally for input lobes). Plain floats — a brain tick is deterministic and snapshotable. */
class Lobe(val size: Int) {
    val output = FloatArray(size)

    /** Sets all neuron outputs (e.g. an input lobe loaded from sensory/drive loci). */
    fun set(values: FloatArray) {
        require(values.size == size) { "expected $size values, got ${values.size}" }
        values.copyInto(output)
    }

    /** Index of the most-active neuron (ties → lowest index) — e.g. the chosen action. */
    fun argmax(): Int {
        var best = 0
        var bestV = output[0]
        for (i in 1 until size) if (output[i] > bestV) { bestV = output[i]; best = i }
        return best
    }
}

/**
 * A learnable dendrite bundle from the [src] lobe to the [dst] lobe: `weight[d][s]` is the
 * dendrite from source neuron `s` to destination neuron `d`. Dense connectivity (every dst
 * neuron has a dendrite to every src neuron); sparse, genome-specified connectivity is a faithful
 * refinement left for later. [init] seeds the weights.
 */
class Tract(val src: Lobe, val dst: Lobe, init: (dst: Int, src: Int) -> Float) {
    val weight: Array<FloatArray> = Array(dst.size) { d -> FloatArray(src.size) { s -> init(d, s) } }
}
