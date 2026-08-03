package org.emerge.demo.outofspace.world

/** Mutable diverter cursors for one tick. */
class DiverterWork(diverters: Diverters) {
    private val cursor: MutableMap<Int, Int> = HashMap(diverters.cursor)

    /**
     * Picks a successor for a packet leaving [tile], preferring one that is free, and alternating
     * between them so a fork splits its throughput rather than favouring a branch.
     */
    fun choose(tile: Int, options: IntArray, isFree: (Int) -> Boolean): Int {
        if (options.isEmpty()) return -1
        if (options.size == 1) return if (isFree(options[0])) options[0] else -1
        val start = cursor[tile] ?: 0
        for (step in options.indices) {
            val pick = options[(start + step) % options.size]
            if (isFree(pick)) {
                // Advance past the branch actually *used*, not past the one we hoped to use. A
                // blocked branch must not consume its turn, or a jam on one side would quietly
                // halve the throughput of the other.
                cursor[tile] = (start + step + 1) % options.size
                return pick
            }
        }
        return -1
    }

    fun snapshot(): Diverters = Diverters.of(cursor)
}
