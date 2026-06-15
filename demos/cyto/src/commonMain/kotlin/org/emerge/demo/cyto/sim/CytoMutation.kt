package org.emerge.demo.cyto.sim

/**
 * Per-tick **genetic damage** (MORPHOGENESIS.md step 7): every gene of every cell independently faces
 * each of four mutation operators — **threshold drift (±1)**, **duplication**, **deletion**, and
 * **point-mutation** — with probability `1 / rateDenom` per tick (not tied to division). Accumulating
 * damage is both the source of variation natural selection acts on and a pressure that disrupts frozen
 * no-division steady states. Mutations are inherited clonally (the daughter copies the mother's
 * already-mutating genome on division).
 *
 * Randomness comes from the sim's deterministic PRNG via the injected [nextInt] (`nextInt(n)` → `0..n-1`),
 * so it stays lockstep-safe; the helper itself is pure + testable. **Copy-on-write:** returns a new
 * genome only if something changed (so shared preset lists are never mutated in place).
 */
object CytoMutation {

    /** Species an operand/gate mutation may pick (the k=3 monomers + their directed bonded pairs;
     *  `FormBond` reads only the first atom of each operand). Kept to the a,b,c alphabet. */
    private val SPECIES = listOf("a", "b", "c", "aa", "ab", "ac", "ba", "bb", "bc", "ca", "cb", "cc")
    private val ATOMS = listOf("a", "b", "c")

    /** Apply per-tick mutation to [genome]; returns a new list if anything changed, else null. */
    fun mutate(genome: List<Gene>, rateDenom: Int, nextInt: (Int) -> Int): List<Gene>? {
        if (rateDenom <= 0) return null
        var out: ArrayList<Gene>? = null
        for (i in genome.indices) {
            val gene = genome[i]
            // Draw all four operators every gene every tick (keeps PRNG advancement deterministic).
            val del = fires(rateDenom, nextInt)
            val drift = fires(rateDenom, nextInt)
            val point = fires(rateDenom, nextInt)
            val dup = fires(rateDenom, nextInt)
            if (!del && !drift && !point && !dup) {
                out?.add(gene)
                continue
            }
            if (out == null) {
                out = ArrayList(genome.size + 1)
                for (j in 0 until i) out.add(genome[j])
            }
            if (del) continue                       // gene deleted (skips the rest)
            var g = gene
            if (drift) g = driftThreshold(g, nextInt)
            if (point) g = pointMutate(g, nextInt)
            out.add(g)
            if (dup) out.add(g)                     // duplicate the (possibly-mutated) gene
        }
        return out
    }

    private fun fires(rateDenom: Int, nextInt: (Int) -> Int) = nextInt(rateDenom) == 0

    /** Nudge the gate threshold by ±1 (clamped ≥0). */
    private fun driftThreshold(g: Gene, nextInt: (Int) -> Int): Gene {
        val delta = if (nextInt(2) == 0) -1 else 1
        val t = (g.condition.threshold + delta).coerceAtLeast(0)
        return g.copy(condition = g.condition.copy(threshold = t))
    }

    /** Change one field of the gene — comparator, gate species/type, an action operand, the action
     *  type, or the energy source. */
    private fun pointMutate(g: Gene, nextInt: (Int) -> Int): Gene = when (nextInt(7)) {
        0 -> g.copy(condition = g.condition.copy(cmp = flip(g.condition.cmp)))
        1 -> g.copy(condition = g.condition.copy(species = pick(SPECIES, nextInt)))
        2 -> g.copy(condition = g.condition.copy(type = ConditionType.entries[nextInt(ConditionType.entries.size)]))
        3 -> g.copy(action = g.action.copy(a = pick(SPECIES, nextInt)))
        4 -> g.copy(action = g.action.copy(b = pick(SPECIES, nextInt)))
        5 -> g.copy(action = g.action.copy(type = ActionType.entries[nextInt(ActionType.entries.size)]))
        else -> g.copy(source = flipSource(g.source, nextInt))
    }

    private fun flip(c: Comparison) = if (c == Comparison.Greater) Comparison.Less else Comparison.Greater
    private fun flipSource(s: EnergySource, nextInt: (Int) -> Int): EnergySource = when (s) {
        EnergySource.Light -> EnergySource.BreakBond(pick(ATOMS, nextInt) + pick(ATOMS, nextInt))
        is EnergySource.BreakBond -> EnergySource.Light
    }

    private fun pick(options: List<String>, nextInt: (Int) -> Int) = options[nextInt(options.size)]
}
