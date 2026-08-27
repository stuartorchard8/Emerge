package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.num.Budget

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.CellShape
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.floorTile
import org.emerge.demo.outofspace.world.overlapBetween
import org.emerge.demo.outofspace.world.shapeReach
import org.emerge.demo.outofspace.world.Pose
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.Wiring

/**
 * The ore source: a deck plate that leeches mass off whatever rock is lying on it.
 *
 * It replaces the miner, which minted ore out of nothing at a rate somebody typed. Everything
 * downstream of it is unchanged — same output port, same one buffer, same backing-up
 * behaviour — because the interesting difference is upstream: this thing has to be **given** a rock,
 * and when the rock is gone it stops. That is the loop closing — and what it gets out of one is what
 * that rock is made of.
 *
 * Two things about it are unlike every other deck machine, and both are the same choice:
 *
 *  - **It is five tiles across**, which is a smelter's footprint rather than a concentrator's, because
 *    it is a floor to land a rock on and a rock is five tiles across.
 *  - **It is permeable.** Air crosses it and a rock does not bounce off it — see [org.emerge.demo.outofspace.world.StructureMap] and
 *    [org.emerge.demo.outofspace.world.overlapsHull]. A solid deck machine would be a wall a rock could never get on top of, so an
 *    impermeable extractor could not do the one thing it exists to do.
 *
 * **One store, and a bite goes straight into it.** Mass leaves a rock one **cell** at a time — a few
 * kilograms of whatever that rock assays at, see [org.emerge.demo.outofspace.world.RigidBody.massPerTile] — and the machine takes a
 * whole one or none. The rock is never half-eaten, which is what keeps the two ledgers exact against
 * each other, and a dense body is worth more ore per bite as well as taking more to shift.
 *
 * It used to hold a second store: the cell in its jaws, ground across into the buffer at a rate. That
 * bought nothing observable. **The rail sets the throughput** — a belt tile holds one packet and a
 * machine hands over one packet a tick — so what actually leaves is capped by the track whatever
 * rate is applied in here, and the grinding was a number nobody could see. What the second store was
 * really for was meeting a rock measured in whole cells with a rate measured in mass, and
 * [BUFFER_CAP] does that on its own: it bites while it has room and stops when it does not.
 *
 * You can still see both halves: the rock pits away from the side the machine is working, a cell at
 * a time, while the belt carries ore off at the belt's own pace.
 */
data class Extractor(
    override val center: TileIndex,
    override val facing: Direction,
    override val wiring: Wiring = Wiring.RUNNING,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Extractor
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    companion object {
        /**
         * How much ore an extractor holds before it stops biting.
         *
         * **Five tonnes** — enough that the machine goes on working for a while when the belt pauses,
         * and small enough that it is a hopper rather than a warehouse.
         *
         * ⚠️ It is a **floor, not a ceiling**: a bite is a whole cell and may weigh more than what is
         * left of the room, so the store can end a tick above this. That is deliberate and it is what
         * lets a rock measured in whole cells meet a limit measured in mass — refusing a bite that
         * would overshoot would stall the machine against any rock dense enough.
         */
        val BUFFER_CAP = 5L * Budget.TONNE
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
class Bite(val body: RigidBody?, val mass: Long, val energy: Long, val impulseX: Long, val impulseY: Long)

/**
 * Which of [body]'s cells a machine covering tiles `[x0,x1] × [y0,y1]` can reach, or `-1`.
 *
 * The nearest one to the plate's centre, so a body hanging half off is eaten from the side that is
 * actually over the machine, and so the answer never depends on iteration luck.
 *
 * ⚠️ **A body has an orientation of its own, so its cells are not laid out along the grid's axes.**
 * This walks them through [org.emerge.demo.outofspace.world.RigidBody.poseIn] — the composed body-in-ship pose — exactly as
 * [org.emerge.demo.outofspace.world.overlapsHull] does. It used to transform the body's *corner* and then step the cell offsets
 * along the grid, which is [org.emerge.demo.outofspace.world.RigidBody.localX]'s documented trap: the answer then ignored `ang`
 * entirely, and a rock that arrived spinning — or any rock at all aboard a ship that had turned —
 * landed on the plate the collision solver could see and sat in a place the extractor could not.
 * A turned body read *identically* to an upright one, so the failure was silent rather than noisy.
 *
 * The reach test is [org.emerge.demo.outofspace.world.overlapBetween] against each plate tile, which is the same geometry
 * [org.emerge.demo.outofspace.world.collectHullContacts] emits contacts from. That agreement is the point: a cell is a disc
 * to the solver, so a cell that reaches the plate by the box test and not by the disc test is a bite
 * taken out of a rock that is not touching the machine. The old half-open box test could only match
 * that while nothing was ever turned.
 */
fun reachableCell(body: RigidBody, pose: Pose, x0: Int, y0: Int, x1: Int, y1: Int): Int {
    val half = Flight.PER_TILE / 2L
    val focusX = (x0 + x1 + 1).toLong() * Flight.PER_TILE / 2L
    val focusY = (y0 + y1 + 1).toLong() * Flight.PER_TILE / 2L
    // Hoisted: [Pose]'s constructor runs a CORDIC loop, which is cheap once a body and not once a
    // cell — see [RigidBody.pose].
    val at = body.poseIn(pose)

    var best = -1
    var bestDistance = Long.MAX_VALUE
    for (i in body.cells.indices) {
        if (!body.cells[i]) continue
        val shape = body.shapeAt(i)
        // Grid frame: the plate is a tile and a body is in the world, so the body comes to it.
        val localX = (i % body.width) * Flight.PER_TILE + half
        val localY = (i / body.width) * Flight.PER_TILE + half
        val cellX = at.toWorldX(localX, localY)
        val cellY = at.toWorldY(localX, localY)

        // In tiles, so the square cannot overflow however far out the body is. Measured before the
        // overlap test because it is the cheaper of the two and it rejects most cells.
        val dx = (cellX - focusX) / Flight.PER_TILE
        val dy = (cellY - focusY) / Flight.PER_TILE
        val distance = dx * dx + dy * dy
        if (distance >= bestDistance) continue

        var reached = false
        val reach = shapeReach(shape)
        for (ty in floorTile(cellY - reach)..floorTile(cellY + reach - 1L)) {
            if (ty < y0 || ty > y1) continue
            for (tx in floorTile(cellX - reach)..floorTile(cellX + reach - 1L)) {
                if (tx < x0 || tx > x1) continue
                if (overlapBetween(
                        shape, cellX, cellY,
                        CellShape.TILE, tx * Flight.PER_TILE + half, ty * Flight.PER_TILE + half,
                    )
                ) { reached = true; break }
            }
            if (reached) break
        }
        if (!reached) continue

        bestDistance = distance
        best = i
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
    if (filled <= 1) return Bite(null, body.mass, body.energy.total, body.impulseX, body.impulseY)

    val cells = body.cells.copyOf()
    cells[index] = false
    // [RigidBody.energy] is indexed by a cell's ordinal among the filled ones, so the cell being
    // taken has to be counted to, not indexed to. Heat now leaves as **the cell's own energy** and
    // not as a share of the whole: the same number while a body is isothermal, which it always is
    // today, and the right one the moment anything makes it otherwise.
    var ordinal = 0
    for (i in 0 until index) if (body.cells[i]) ordinal++
    val takenEnergy = body.energy[ordinal]

    val keptX = body.impulseX * (filled - 1) / filled
    val keptY = body.impulseY * (filled - 1) / filled
    val left = body.copy(
        width = body.width, height = body.height, cells = cells,
        positionX = body.positionX, positionY = body.positionY,
        impulseX = keptX, impulseY = keptY,
        energy = body.energy.dropping(ordinal),
    )
    return Bite(
        body = left,
        mass = body.massPerTile,
        energy = takenEnergy,
        impulseX = body.impulseX - keptX,
        impulseY = body.impulseY - keptY,
    )
}
