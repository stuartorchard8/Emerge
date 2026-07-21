package org.emerge.demo.cyto.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for the operand-mutation kind space.
 *
 * **Why this exists:** the mutation-on golden does NOT cover [CytoMutation] operand mutation. Verified by
 * experiment — hard-wiring `mutateOperand` to always return [Operand.Neighbours] leaves the mutation-on
 * golden byte-identical and passing. So the golden cannot be relied on to catch a change to the operand
 * kind space (adding a kind, removing one, or misnumbering the branch table); it will report GREEN either
 * way. GENE_OPERANDS_PLAN §4 assumed otherwise, and its "re-baseline the mutation golden" step is not the
 * safety net it looks like.
 *
 * These tests drive [CytoMutation] directly with a scripted `nextInt` so every branch is reachable and
 * asserted, which is what actually protects an operand-set change.
 */
class CytoMutationTest {

    /** A gene whose single clause is a constant on both sides — a clean subject for operand re-rolls. */
    private fun subject() = Gene(
        EnergySource.Light,
        GeneCondition(Operand.Constant(10), Comparison.Greater, Operand.Constant(0)),
        GeneAction(ActionType.Convert, "rg"),
    )

    /**
     * Every operand kind is reachable by mutation, and the draw is over exactly [OperandKind.COUNT] kinds.
     *
     * Drives `pointMutate`'s lhs-operand branch (case 1 of 11) with each possible kind draw in turn and
     * collects the kinds produced. If the `nextInt(n)` bound and the branch table ever fall out of step —
     * the exact failure mode of adding or removing an operand — some kind becomes unreachable and this
     * fails, where the golden would not.
     */
    @Test
    fun operandMutationReachesEveryKind() {
        val produced = mutableSetOf<Int>()
        for (kindDraw in 0 until OperandKind.COUNT) {
            // Script the PRNG: mutate() rolls del/drift/point/dup (each fires when the draw is 0), then
            // pointMutate picks its case, then the clause index, then the operand kind. rateDenom must be
            // > 1 or every operator fires and `del` drops the gene before pointMutate runs. `mutateOperand`
            // may draw again for a species, so anything after the kind draw is a harmless 0.
            val draws = ArrayDeque(listOf(1, 1, 0, 1, 1, 0, kindDraw))
            val mutated = CytoMutation.mutate(listOf(subject()), rateDenom = 2) { n ->
                (draws.removeFirstOrNull() ?: 0).let { if (n <= 0) 0 else it % n }
            }
            val lhs = mutated?.single()?.condition?.clauses?.single()?.lhs ?: continue
            produced += lhs.kind
        }
        assertEquals(
            (0 until OperandKind.COUNT).toSet(), produced,
            "every OperandKind must be reachable by mutation; missing kinds mean the nextInt bound and the " +
                "branch table disagree",
        )
    }

    /** The retired `Conc` kind is gone from the kind space entirely — no gap, no stale tag. Guards the
     *  density [OperandKind] relies on for jump-table dispatch. */
    @Test
    fun operandKindsAreDenseAndZeroBased() {
        val kinds = listOf(
            Operand.Constant(0), Operand.Chem("rg"), Operand.Biomass, Operand.Touching, Operand.Neighbours,
        ).map { it.kind }
        assertEquals((0 until OperandKind.COUNT).toList().sorted(), kinds.sorted(), "kinds must be dense 0..COUNT-1")
        assertEquals(kinds.size, kinds.toSet().size, "no two operands may share a kind tag")
    }

    /** Mutation never invents an operand outside the declared kind space — a crude fuzz over the injected
     *  PRNG, so a future kind added to the branch table without a tag can't slip through. */
    @Test
    fun mutationNeverProducesAnUnknownKind() {
        var seed = 12345
        fun rng(n: Int): Int {
            seed = seed * 1103515245 + 12345
            return if (n <= 0) 0 else ((seed ushr 16) and 0x7fff) % n
        }
        var genome = listOf(subject())
        repeat(5000) {
            genome = CytoMutation.mutate(genome, rateDenom = 2, nextInt = ::rng) ?: genome
            for (gene in genome) for (clause in gene.condition.clauses) {
                for (op in listOf(clause.lhs, clause.rhs)) {
                    assertTrue(op.kind in 0 until OperandKind.COUNT, "operand $op has out-of-range kind ${op.kind}")
                }
            }
        }
    }
}
