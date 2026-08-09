package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource

/**
 * The ore source: a deck plate that leeches mass off whatever rock is lying on it.
 *
 * It replaces the miner, which minted ore out of nothing at a rate somebody typed. Everything
 * downstream of it is unchanged — same output port, same [Form.Ore] buffer, same backing-up
 * behaviour — because the interesting difference is upstream: this thing has to be **given** a rock,
 * and when the rock is gone it stops. That is the loop closing.
 *
 * Two things about it are unlike every other deck machine, and both are the same choice:
 *
 *  - **It is five tiles across**, which is a smelter's footprint rather than a processor's, because
 *    it is a floor to land a rock on and a rock is five tiles across.
 *  - **It is permeable.** Air crosses it and a rock does not bounce off it — see [StructureMap] and
 *    [overlapsHull]. A solid deck machine would be a wall a rock could never get on top of, so an
 *    impermeable extractor could not do the one thing it exists to do.
 *
 * [input] is how a rate in grams meets a rock measured in whole cells, and it makes the extractor
 * read like every other machine in the game: an input buffer, a rate, an output buffer. Mass leaves
 * a rock one **cell** at a time — 3 kg of [Rock.MATERIAL] — so the machine bites a whole cell off,
 * holds it, and grinds it into the buffer at [gramsPerTick] like a processor working a lump. The
 * rock is never half-eaten, which is what keeps the two ledgers exact against each other, and the
 * ore still comes out in a steady trickle rather than in 3 kg lurches.
 *
 * You can see both halves of that: the rock visibly pits away from the side the machine is working,
 * a cell at a time, while the belt fills smoothly.
 */
data class Extractor(
    override val facing: Direction,
    /** The cell it is chewing on. Counts as mass aboard — see [massIn] — because it is. */
    val input: Resource? = null,
    val buffer: Resource = Resource(Form.Ore, Mixture.EMPTY),
    val carry: Long = 0L,
    val gramsPerTick: Long = 250L,
    override val wiring: Wiring = Wiring.RUNNING,
    override val joules: Long = ambientJoules(MachineKind.Extractor),
) : Directed {
    override val kind: MachineKind get() = MachineKind.Extractor
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withJoules(joules: Long): Machine = copy(joules = joules)

    companion object {
        const val BUFFER_CAP = 5_000L
    }
}

/**
 * What one bite took: the rock as it is now — null once nothing is left of it — and the three
 * conserved quantities that came off with the cell.
 *
 * All three are stated as what *left the rock*, because that is the only half either ledger can see
 * for itself: the ore lands in a buffer, the heat lands in the casing and the momentum lands on the
 * ship, and each of the three has to be booked by the caller in the same breath.
 */
class Bite(val body: RigidBody?, val grams: Long, val joules: Long, val impulseX: Long, val impulseY: Long)

/**
 * Which of [body]'s cells a machine covering tiles `[x0,x1] × [y0,y1]` can reach, or `-1`.
 *
 * The nearest one to the plate's centre, so a body hanging half off is eaten from the side that is
 * actually over the machine, and so the answer never depends on iteration luck. The far edge is
 * half-open exactly as [overlapsHull]'s is: a cell that only touches the plate's edge is beside it,
 * not on it.
 */
fun reachableCell(body: RigidBody, x0: Int, y0: Int, x1: Int, y1: Int): Int {
    val loX = x0.toLong() * Flight.PER_TILE
    val loY = y0.toLong() * Flight.PER_TILE
    val hiX = (x1 + 1).toLong() * Flight.PER_TILE
    val hiY = (y1 + 1).toLong() * Flight.PER_TILE
    val focusX = (loX + hiX) / 2L
    val focusY = (loY + hiY) / 2L

    var best = -1
    var bestDistance = Long.MAX_VALUE
    for (i in body.cells.indices) {
        if (!body.cells[i]) continue
        val cellX = body.positionX + (i % body.width) * Flight.PER_TILE
        val cellY = body.positionY + (i / body.width) * Flight.PER_TILE
        if (cellX + Flight.PER_TILE <= loX || cellX >= hiX) continue
        if (cellY + Flight.PER_TILE <= loY || cellY >= hiY) continue
        // In tiles, so the square cannot overflow however far out the body is.
        val dx = (cellX + Flight.PER_TILE / 2L - focusX) / Flight.PER_TILE
        val dy = (cellY + Flight.PER_TILE / 2L - focusY) / Flight.PER_TILE
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) {
            bestDistance = distance
            best = i
        }
    }
    return best
}

/**
 * Takes cell [index] off [body], and says what went with it.
 *
 * Heat and momentum leave in the cell's **share** of the whole, so neither the body's temperature
 * nor its velocity changes when it gets smaller — which is the physics, since what is removed was at
 * the temperature and moving at the speed of the thing it was part of. Each share is the remainder
 * of a single truncating divide (`whole − whole × (n−1) / n`) rather than a multiply of its own, so
 * the two halves add back to the original **exactly** and no ledger can be broken by a rounding
 * crumb. §5g's lesson, applied before it had a chance to bite.
 */
fun biteCell(body: RigidBody, index: Int): Bite {
    require(body.cells[index]) { "cell $index of $body is not there to be taken" }
    val filled = body.filled
    if (filled <= 1) return Bite(null, body.massGrams, body.joules, body.impulseX, body.impulseY)

    val cells = body.cells.copyOf()
    cells[index] = false
    val keptJoules = body.joules * (filled - 1) / filled
    val keptX = body.impulseX * (filled - 1) / filled
    val keptY = body.impulseY * (filled - 1) / filled
    val left = body.copy(
        width = body.width, height = body.height, cells = cells,
        positionX = body.positionX, positionY = body.positionY,
        impulseX = keptX, impulseY = keptY,
        joules = keptJoules,
    )
    return Bite(
        body = left,
        grams = RigidBody.MATERIAL.gramsPerTile,
        joules = body.joules - keptJoules,
        impulseX = body.impulseX - keptX,
        impulseY = body.impulseY - keptY,
    )
}
