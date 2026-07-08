package org.emerge.demo.cyto.sim

/**
 * Throwaway timing probe for the [CytoBiologyCore.passiveEnvExchange] descent-parallelization decision
 * (see `docs/cyto-parallel-next-session.md`). Splits the drop-contested exchange into its three costs so we
 * can see the Amdahl ceiling of the proposed 4-step redesign BEFORE building it:
 *   - [descentNanos] — the serial quad-tree descent (openFootprint: refine + stamp + copy leaves). In the
 *     redesign this becomes the serial "step 3" (refine only), so it caps the achievable speedup.
 *   - [planNanos] — drop-contested filtering + species union + transfer-array build (redesign steps 1/2/4a,
 *     all parallelizable).
 *   - [balanceNanos] — the balance/transfer inner loop (already parallel in debdaddc; redesign step 4b).
 *
 * Off in production ([enabled] == false); NOT thread-safe — only the sequential measurement path sets it.
 */
object ExchangeProbe {
    var enabled = false

    var ticks = 0L
    var descentNanos = 0L
    var planNanos = 0L
    var balanceNanos = 0L

    fun reset() { ticks = 0; descentNanos = 0; planNanos = 0; balanceNanos = 0 }

    fun report(): String {
        val t = ticks.coerceAtLeast(1)
        val total = (descentNanos + planNanos + balanceNanos).coerceAtLeast(1)
        fun us(n: Long) = n / 1000 / t
        fun pct(n: Long) = "%.1f".format(100.0 * n / total)
        return buildString {
            appendLine("  exchange timing over $ticks measured ticks (per-tick):")
            appendLine("    descent(serial refine)=${us(descentNanos)}us (${pct(descentNanos)}%)")
            appendLine("    plan(parallelizable)  =${us(planNanos)}us (${pct(planNanos)}%)")
            appendLine("    balance(parallel)     =${us(balanceNanos)}us (${pct(balanceNanos)}%)")
            appendLine("    → serial-floor if only descent stays serial: ${pct(descentNanos)}% of exchange")
        }
    }
}
