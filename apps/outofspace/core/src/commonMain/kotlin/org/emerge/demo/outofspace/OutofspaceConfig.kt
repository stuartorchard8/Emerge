package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Grid

/** Fixed world parameters. */
data class OutofspaceConfig(
    /**
     * Generous, because machines are now rooms: a smelter is five tiles across and a refinery line
     * of them is long. A grid this size is still trivial to sweep, and "a big bound with the hull
     * drawn inside it" is what gives the expansion fantasy without a growable world.
     */
    val grid: Grid = Grid(96, 60),
    /**
     * How fast to run the world, and **nothing else**.
     *
     * The tick is the unit of simulation: every rate in the game is stated per tick, so nothing
     * below this line divides by it. This number therefore says how quickly you watch the world
     * happen, not how finely it is computed — raise it and the factory runs faster, exactly as if
     * you had turned a speed dial, with identical results per tick.
     *
     * That is deliberate, and it is the second answer to the question. The first was to make every
     * subsystem invariant to this number, which cost a carry, a sub-stepping loop, a stability
     * constant and a test-clock helper, and *still* leaked: processor purity moved with the tick
     * rate because the chunk it rounds is a chunk-per-tick. Making the tick the unit costs nothing
     * and cannot leak, because there is no second unit to disagree with.
     *
     * Only [OutofspaceController]'s frame accumulator reads it, which is the one place that is
     * honestly about real time.
     */
    val ticksPerSecond: Int = 4,
) {
    val secondsPerTick: Float get() = 1f / ticksPerSecond
}
