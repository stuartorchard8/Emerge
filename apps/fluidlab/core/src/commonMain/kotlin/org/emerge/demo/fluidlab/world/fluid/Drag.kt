package org.emerge.demo.fluidlab.world.fluid

/** The momentum drag took out of the air and handed to the ship, by axis. */
class DragResult(val vesselX: Long, val vesselY: Long)

/**
 * Drag: fixed fraction of each face's momentum, every tick.
 * Replaces pure diffusion (frictionless sloshing). Damps all motion equally (wall friction, not true viscosity).
 * Momentum goes to ship (not destroyed — ledger closed).
 */
fun applyDrag(edges: EdgeGrid, mx: LongArray, my: LongArray): DragResult {
    var takenX = 0L
    var takenY = 0L
    for (e in 0 until edges.xEdgeCount) {
        val lost = mx[e] / DRAG_DENOMINATOR
        if (lost == 0L) continue
        mx[e] -= lost
        takenX += lost
    }
    for (e in 0 until edges.yEdgeCount) {
        val lost = my[e] / DRAG_DENOMINATOR
        if (lost == 0L) continue
        my[e] -= lost
        takenY += lost
    }
    return DragResult(takenX, takenY)
}

/**
 * One part in this many of a face's momentum is lost per tick.
 *
 * Set from the two things it has to sit between. Too little and a room rings for hundreds of ticks
 * after a door opens; too much and an exhaust jet is scrubbed away before it can leave the nozzle,
 * which would take the rocket with it. A thirty-secondth settles a sloshing room inside one crossing
 * and costs a ten-tick blowout under a quarter of its momentum.
 *
 * Integer division floors, so momentum below this simply stops decaying rather than grinding to
 * zero. That is deliberate — it leaves a residual drift of at most a few units per face, which is far
 * below anything that moves a gram, and it means the damping can never fight the pressure solve over
 * the last unit.
 */
private const val DRAG_DENOMINATOR = 32L
