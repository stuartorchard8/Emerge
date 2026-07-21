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

    private val ATOMS = listOf("r", "g", "b")
    /** Longest a species operand may drift to — the registry's max molecule length (k²+1). Operands rarely
     *  reach it (drift is ±1/mutation and short-biased), so it almost never binds; it just bounds the space. */
    private val MAX_OPERAND_LEN = SpeciesRegistry.species.maxOf { it.length }

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
        // Bond-cap: reject a mutation that would push the genome past GENOME_MAX_BOND_TYPES distinct bonds
        // (keeps each cell's metabolic reach — hence its per-cell species — bounded). The PRNG draws above
        // happened regardless, so determinism is unaffected; the cell just keeps its prior genome this tick.
        if (out != null && handleableOf(out).bondTypeCount > CytoTuning.GENOME_MAX_BOND_TYPES) return null
        return out
    }

    private fun fires(rateDenom: Int, nextInt: (Int) -> Int) = nextInt(rateDenom) == 0

    /** Nudge a [Operand.Constant] side of a random clause by ±1 (clamped ≥0) — drifts the rhs constant if
     *  there is one, else the lhs constant; a clause comparing two live variables has no constant to drift
     *  (the clause index + ±1 sign are still drawn, keeping PRNG advancement deterministic). */
    private fun driftThreshold(g: Gene, nextInt: (Int) -> Int): Gene {
        val cs = g.condition.clauses
        val ci = nextInt(cs.size)
        val delta = if (nextInt(2) == 0) -1 else 1
        val c = cs[ci]
        val drifted = when {
            c.rhs is Operand.Constant -> c.copy(rhs = Operand.Constant((c.rhs.value + delta).coerceAtLeast(0)))
            c.lhs is Operand.Constant -> c.copy(lhs = Operand.Constant((c.lhs.value + delta).coerceAtLeast(0)))
            else -> c
        }
        return withClause(g, ci, drifted)
    }

    /** Change one field of the gene — a comparator/operand of a random clause, a whole **added** or
     *  **dropped** clause (the AND-conjunction explores its own arity), an action operand, the action type,
     *  the efficiency gear, or the energy source. */
    private fun pointMutate(g: Gene, nextInt: (Int) -> Int): Gene {
        val cs = g.condition.clauses
        return when (nextInt(11)) {
            0 -> { val ci = nextInt(cs.size); withClause(g, ci, cs[ci].copy(cmp = flip(cs[ci].cmp))) }
            1 -> { val ci = nextInt(cs.size); withClause(g, ci, cs[ci].copy(lhs = mutateOperand(cs[ci].lhs, nextInt))) }
            2 -> { val ci = nextInt(cs.size); withClause(g, ci, cs[ci].copy(rhs = mutateOperand(cs[ci].rhs, nextInt))) }
            3 -> g.copy(action = g.action.copy(a = mutateSpecies(g.action.a, nextInt)))
            4 -> g.copy(action = g.action.copy(b = mutateSpecies(g.action.b, nextInt)))
            5 -> {  // re-roll the action type; clear the Mitosis-only flags if it no longer applies (keeps the invariant + codec round-trip)
                val newType = ActionType.entries[nextInt(ActionType.entries.size)]
                val mitosis = newType == ActionType.Mitosis
                g.copy(action = g.action.copy(type = newType, morphogenToMother = g.action.morphogenToMother && mitosis, divideAcross = g.action.divideAcross && mitosis, rejectMother = g.action.rejectMother && mitosis))
            }
            6 -> g.copy(efficiency = (g.efficiency + if (nextInt(2) == 0) -1 else 1).coerceIn(0, CytoTuning.EFFICIENCY_MAX_GEAR))  // nudge the efficiency gear ±1
            7 -> g.copy(source = flipSource(g.source, nextInt))
            8 -> addClause(g, nextInt)
            9 -> dropClause(g, nextInt)
            else -> mutateSynthesisOperand(g, nextInt)   // perturb one of the two synthesis reactants
        }
    }

    /** Replace clause [ci] of the gene's gate. */
    private fun withClause(g: Gene, ci: Int, clause: Clause): Gene {
        val cs = g.condition.clauses.toMutableList()
        cs[ci] = clause
        return g.copy(condition = GeneCondition(cs))
    }

    /** Append a fresh random AND-clause (capped at [CytoTuning.GENOME_MAX_CLAUSES]). The operands/comparator
     *  are drawn regardless of the cap, so PRNG advancement is independent of the current clause count. */
    private fun addClause(g: Gene, nextInt: (Int) -> Int): Gene {
        val lhs = mutateOperand(Operand.Constant(0), nextInt)
        val cmp = if (nextInt(2) == 0) Comparison.Greater else Comparison.Less
        val rhs = mutateOperand(Operand.Constant(0), nextInt)
        if (g.condition.clauses.size >= CytoTuning.GENOME_MAX_CLAUSES) return g
        return g.copy(condition = GeneCondition(g.condition.clauses + Clause(lhs, cmp, rhs)))
    }

    /** Drop a random clause; never empties the gate (a gene keeps ≥1 clause — drops are no-ops at size 1,
     *  with no draw, so determinism holds). */
    private fun dropClause(g: Gene, nextInt: (Int) -> Int): Gene {
        val cs = g.condition.clauses
        if (cs.size <= 1) return g
        val ci = nextInt(cs.size)
        return g.copy(condition = GeneCondition(cs.filterIndexed { i, _ -> i != ci }))
    }

    /** Re-roll one clause operand to a fresh kind: a [Operand.Constant] (keeping any prior constant value,
     *  so drift can still tune it), a [Operand.Chem] (drifting its species — see [mutateSpecies]),
     *  [Operand.Biomass], [Operand.Touching], or [Operand.Neighbours]. */
    private fun mutateOperand(op: Operand, nextInt: (Int) -> Int): Operand = when (nextInt(5)) {
        0 -> Operand.Constant((op as? Operand.Constant)?.value ?: 0)
        1 -> Operand.Chem(mutateSpecies((op as? Operand.Chem)?.species ?: "", nextInt))
        2 -> Operand.Biomass
        3 -> Operand.Touching
        else -> Operand.Neighbours
    }

    /** Drift a species operand atom-by-atom — grow (append a random atom), shrink (drop the last), or
     *  substitute one atom — so the operand's LENGTH itself evolves: FormBond suffix/prefix specificity, and
     *  Convert/Import/Chem species. Stays in `[1, MAX_OPERAND_LEN]`, short-biased (drift is ±1). A result
     *  that isn't a valid registry species is a neutral no-op for Convert/Import (selection prunes it) and a
     *  literal suffix/prefix filter for FormBond — matching what the in-game atom-builder editor allows. */
    private fun mutateSpecies(current: String, nextInt: (Int) -> Int): String = when (nextInt(3)) {
        0 -> if (current.length < MAX_OPERAND_LEN) current + pick(ATOMS, nextInt) else substituteAtom(current, nextInt)  // grow
        1 -> if (current.length > 1) current.dropLast(1) else pick(ATOMS, nextInt)                                       // shrink (keep ≥1)
        else -> if (current.isEmpty()) pick(ATOMS, nextInt) else substituteAtom(current, nextInt)                        // substitute
    }

    private fun substituteAtom(s: String, nextInt: (Int) -> Int): String {
        val i = nextInt(s.length)
        return s.substring(0, i) + pick(ATOMS, nextInt) + s.substring(i + 1)
    }

    /** Perturb one of a synthesis gene's two reactants. This slot used to flip an operand between exact and
     *  wildcard (MORPHOGENESIS.md §2026-06-18); wildcards were removed with the chemistry inversion (see
     *  [EnergySource.FormBond]), so it now does the thing that still matters for evolving chemistry —
     *  changing *which* molecules a lineage joins. The side (a/b) is drawn regardless of source type, so PRNG
     *  advancement stays independent of gene content, but it is a no-op on a Light gene. */
    private fun mutateSynthesisOperand(g: Gene, nextInt: (Int) -> Int): Gene {
        val side = nextInt(2)
        val s = g.source as? EnergySource.FormBond ?: return g
        return if (side == 0) g.copy(source = s.copy(a = mutateSpecies(s.a, nextInt)))
        else g.copy(source = s.copy(b = mutateSpecies(s.b, nextInt)))
    }

    private fun flip(c: Comparison) = if (c == Comparison.Greater) Comparison.Less else Comparison.Greater

    /** Mutate the energy source. Light flips to a randomly-drawn synthesis reaction; a synthesis reaction
     *  either flips back to Light or has one of its two reactants perturbed. That second branch matters:
     *  synthesis operands used to live on the action (where mutations 3/4 reached them), so without it the
     *  reactants a lineage runs on would be frozen for good and evolution could never retune its chemistry. */
    private fun flipSource(s: EnergySource, nextInt: (Int) -> Int): EnergySource = when (s) {
        EnergySource.Light -> EnergySource.FormBond(pick(ATOMS, nextInt), pick(ATOMS, nextInt))
        is EnergySource.FormBond -> when (nextInt(3)) {
            0 -> EnergySource.Light
            1 -> s.copy(a = mutateSpecies(s.a, nextInt))
            else -> s.copy(b = mutateSpecies(s.b, nextInt))
        }
    }

    private fun pick(options: List<String>, nextInt: (Int) -> Int) = options[nextInt(options.size)]
}
