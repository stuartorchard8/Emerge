package org.emerge.demo.cyto.sim

import kotlin.test.Test

/**
 * Throwaway A/B probe (NOT a gate) for the operand dispatch change — type-switch vs [Operand.kind]
 * int-switch. Run with `-Doperandbench=1`; results to stdout.
 *
 * Interleaved in ONE process (A,B,A,B,…) because this machine drifts 20-30% and thermally collapses,
 * so two separate runs are not comparable (see reference_cyto_render_perf). Reports the median of the
 * per-round deltas, not a ratio of means, so a single thermal excursion can't carry the result.
 *
 * This measures dispatch ONLY — a tight loop over a representative operand mix, with none of the
 * surrounding gate work. It therefore OVERSTATES the effect relative to a real tick, where dispatch is
 * a fraction of the gate and the gate is a fraction of biology. Treat it as an upper bound on the win.
 */
class OperandDispatchBench {

    /** The pre-change dispatch, kept here only so the A/B can run both in one process. */
    private fun byType(op: Operand, counts: IntArray, bio: Int): Int = when (op) {
        is Operand.Constant -> op.value
        is Operand.Chem -> counts[op.speciesId and 31]
        Operand.Biomass -> bio
        Operand.Touching -> 3
        Operand.Neighbours -> 4
    }

    private fun byKind(op: Operand, counts: IntArray, bio: Int): Int = when (op.kind) {
        OperandKind.CONSTANT -> (op as Operand.Constant).value
        OperandKind.CHEM -> counts[(op as Operand.Chem).speciesId and 31]
        OperandKind.BIOMASS -> bio
        OperandKind.TOUCHING -> 3
        OperandKind.NEIGHBOURS -> 4
        else -> error("unhandled ${op.kind}")
    }

    @Test
    fun abDispatch() {
        if (System.getProperty("operandbench") == null) return

        // A mix weighted like real genomes: mostly Constant/Chem/Biomass, sensors occasional.
        val ops: List<Operand> = buildList {
            repeat(8) { add(Operand.Constant(it * 37)) }
            repeat(8) { add(Operand.Chem("rg")) }
            repeat(4) { add(Operand.Biomass) }
            repeat(2) { add(Operand.Touching) }
            repeat(2) { add(Operand.Neighbours) }
        }.let { base -> List(4096) { base[(it * 7 + it / 3) % base.size] } }   // deterministic shuffle

        val counts = IntArray(32) { it * 13 }
        val iters = 2_000
        var sink = 0

        fun run(useKind: Boolean): Long {
            val t0 = System.nanoTime()
            repeat(iters) {
                for (op in ops) sink += if (useKind) byKind(op, counts, 5000) else byType(op, counts, 5000)
            }
            return System.nanoTime() - t0
        }

        repeat(20) { run(true); run(false) }   // warm both to steady state / let the JIT settle

        val deltas = ArrayList<Double>()
        var sumA = 0L; var sumB = 0L
        repeat(15) {
            val a = run(false)   // type
            val b = run(true)    // kind
            sumA += a; sumB += b
            deltas += (a - b).toDouble() / a * 100.0
        }
        deltas.sort()
        val evals = iters.toLong() * ops.size
        println("operand dispatch A/B (interleaved, 15 rounds, ${evals / 1_000_000}M evals/round)")
        println("  type-switch : %.2f ms/round".format(sumA / 15.0 / 1e6))
        println("  kind-switch : %.2f ms/round".format(sumB / 15.0 / 1e6))
        println("  median delta: %+.1f%% (positive = kind faster)".format(deltas[deltas.size / 2]))
        println("  range       : %+.1f%% .. %+.1f%%".format(deltas.first(), deltas.last()))
        println("  sink=$sink")
    }
}
