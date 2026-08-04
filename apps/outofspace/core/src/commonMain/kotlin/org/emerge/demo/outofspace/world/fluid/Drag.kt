package org.emerge.demo.outofspace.world.fluid

/** The momentum drag took out of the air and handed to the ship, by axis. */
class DragResult(val vesselX: Long, val vesselY: Long)

/**
 * Bleeds momentum out of the air, so that a disturbed room eventually stops moving.
 *
 * ### Why anything settles at all
 *
 * The atmosphere this replaced was pure diffusion: it had no momentum, so it could only ever
 * approach equilibrium and never overshoot it. Give the gas inertia and that free lunch ends. A slug
 * of air pushed into a sealed room arrives with momentum, compresses the far end, is pushed back,
 * and — with nothing to dissipate into — does it again forever. The first run of the new solver had
 * a test room still visibly sloshing after eighty ticks, the wave having crossed and recrossed with
 * no sign of stopping. That is not a bug in the fluid model. It is what a fluid model *is*, and it is
 * what real air would do if it were frictionless.
 *
 * Real air is not. It rubs against the hull and against itself, and the motion turns into heat. This
 * is that, at the crudest useful fidelity: a fixed fraction of every face's momentum, every tick.
 *
 * ### It is drag, not viscosity, and the difference is admitted
 *
 * Proper viscosity smooths velocity *differences* — it damps shear while leaving a uniform drift
 * alone. This damps everything equally, including a whole room moving together, which is really wall
 * friction rather than an internal property of the gas. For a vessel where every parcel of air is
 * within a few tiles of a bulkhead that is close enough to the truth to be worth its simplicity, and
 * the honest version can replace it later without anything above having to change.
 *
 * ### Where the momentum goes
 *
 * To the ship. Momentum is not destroyed by friction — it is handed to whatever is doing the
 * rubbing, and that is the hull. Quietly scaling the field down instead would leak momentum out of
 * the ledger, and the ledger is the thing that makes thrust trustworthy. A room circulating with no
 * net momentum damps symmetrically and hands over nothing, which is the answer a sealed vessel needs.
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
