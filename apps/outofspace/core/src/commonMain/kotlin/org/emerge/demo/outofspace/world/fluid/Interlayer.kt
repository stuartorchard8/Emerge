package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Temperature

/**
 * What crossed between the two bodies of fluid, and what the vessel felt for it.
 *
 * [grams] and [joules] are signed **room-to-pipe**: positive means the rooms lost that much to the
 * plumbing. Signed rather than two counters because the quantity really is one flow that can run
 * either way, and a valve that alternates direction should read as a small net number rather than as
 * two large ones that have to be subtracted to mean anything.
 *
 * [vesselX] and [vesselY] are momentum absorbed by the fitting — see [exchangeLayers] for when that
 * happens and why it is not simply a loss.
 */
class InterlayerStep(
    val grams: Long,
    val joules: Long,
    val vesselX: Long,
    val vesselY: Long,
)

/**
 * Lets gas cross between a room and the pipe sharing its tile, wherever something has opened a way.
 *
 * ### Why this cannot be a face
 *
 * Every other transfer in this package happens across an [ApertureField] face, between two cells that
 * are side by side. This one is between two cells at the **same place** — the room's air and the
 * pipe's contents both occupy tile `t`, which is the whole reason the pipe layer had to be a second
 * field. There is no edge between them, no direction to point along, and therefore no velocity: the
 * lattice simply has no axis for "downward through the layers".
 *
 * So this is a **relaxation**, not an advection, and that is a real modelling choice rather than a
 * convenience. Gas crossing a valve does not get to arrive going somewhere; it equalises. A pressure
 * wave running along a pipe is inertial, because that happens on faces the ordinary solver owns, but
 * the crossing itself is not. The honest version would give a valve its own small axis of momentum,
 * and would be worth building only once something needs the ringing.
 *
 * ### What equalising means when the two cells are different sizes
 *
 * The whole point of a pipe is that it is small (see [VolumeField]), so "let them reach the same
 * pressure" has to respect that. Each side is characterised by a **capacity** — how many moles it
 * takes to raise it by one unit of pressure — which is its volume over its temperature. Conserving
 * total moles and equalising pressure gives a split in proportion to those capacities, and the
 * transfer is the difference between what a side has and its share.
 *
 * That is what makes this stable without a damping constant. Opening a room onto an empty pipe an
 * eighth its size moves an eighth of the room's air *at most*, because that is all the pipe can hold
 * at the pressure they meet at. A relaxation written the obvious way — move a fixed fraction of the
 * gap — would need a tuning number to stop it emptying the room, and would get the equilibrium wrong.
 *
 * Temperature is in the capacity because it belongs there: hot gas takes fewer moles to reach a given
 * pressure, so a hot pipe accepts less. Composition rides along in proportion, which is the only
 * defensible reading of a well-mixed cell handing over a share of itself.
 *
 * ### Momentum, and the one place it is destroyed on purpose
 *
 * Both layers share the lattice, so a pipe cell's faces *are* the room cell's faces — same edge
 * indices, same basis. Momentum crossing with the gas therefore needs no interpolation at all: the
 * donor's share of each face is handed to the acceptor on the identical face. That matters, because
 * the face-to-tile-to-face round trip every other coupling scheme would need is exactly the low-pass
 * smear [advectMomentum] warns about, and it would quietly launder a jet into a breeze.
 *
 * A face is shared between two cells, so a cell's own share of it is half — hence the halving.
 *
 * Where the acceptor's face is **shut**, there is nowhere for the flow to continue, and the momentum
 * is booked to the vessel instead of being carried. This is the dead-end case: gas shoved into the
 * closed end of a pipe pushes on the fitting, and the fitting is bolted to the ship. That is the same
 * accounting [applyDrag] does, and it is not a loss — leaving the momentum on a face no gas can use
 * would let a sealed stub quietly hoard a shove, which is the failure the stranded-momentum sweep at
 * the end of [stepFluid] exists to prevent.
 *
 * The reverse case is the one worth wanting: a pipe blowing into a room hands its momentum to open
 * room faces, so the gas arrives **going somewhere** and leans on whatever is in front of it.
 *
 * ### Ordering
 *
 * Called before either layer's [stepFluid], for the reason conduction runs before the fluid: a
 * pressure delivered into a cell should be free to propagate away in the tick it arrived, rather than
 * sitting for one. Running it afterwards would work and would lag every valve by a tick.
 *
 * [openings] is per tile: [ApertureField.CLOSED] for the overwhelming majority, and how wide the way
 * is where something has opened one. Every array is **edited in place**.
 */
fun exchangeLayers(
    edges: EdgeGrid,
    openings: IntArray,
    roomApertures: ApertureField,
    roomGrams: LongArray,
    roomJoules: LongArray?,
    roomMx: LongArray,
    roomMy: LongArray,
    pipeApertures: ApertureField,
    pipeGrams: LongArray,
    pipeJoules: LongArray?,
    pipeMx: LongArray,
    pipeMy: LongArray,
    pipeVolumes: VolumeField,
): InterlayerStep {
    var movedGrams = 0L
    var movedJoules = 0L
    var vesselX = 0L
    var vesselY = 0L

    for (tile in openings.indices) {
        val opening = openings[tile]
        if (opening <= 0) continue

        val roomMoles = millimolesOf(roomGrams, tile)
        val pipeMoles = millimolesOf(pipeGrams, tile)
        if (roomMoles == 0L && pipeMoles == 0L) continue

        val roomCapacity = pressureCapacity(VolumeField.FULL, kelvinAt(roomGrams, roomJoules, tile))
        val pipeCapacity = pressureCapacity(pipeVolumes.at(tile), kelvinAt(pipeGrams, pipeJoules, tile))

        // The room's share at a common pressure, and how far it is from it. Positive means the room
        // is holding more than its share and gas moves into the pipe.
        val total = roomCapacity + pipeCapacity
        val surplus = (roomMoles * pipeCapacity - pipeMoles * roomCapacity) / total
        if (surplus == 0L) continue

        // Throttled by how wide the way is, which is what makes a part-open valve a part-open valve.
        val crossing = surplus * opening / ApertureField.OPEN
        if (crossing == 0L) continue

        val fromRoom = crossing > 0L
        val donorMoles = if (fromRoom) roomMoles else pipeMoles
        // Nothing to give. Can happen when the *other* side is hot enough to want moles out of an
        // empty cell, and there is no meaningful transfer to make.
        if (donorMoles <= 0L) continue

        val share = Share(if (crossing < 0L) -crossing else crossing, donorMoles)

        val moved = if (fromRoom) {
            handOver(share, tile, roomGrams, roomJoules, pipeGrams, pipeJoules)
        } else {
            handOver(share, tile, pipeGrams, pipeJoules, roomGrams, roomJoules)
        }
        // Signed room-to-pipe, so a valve breathing in and out reads as the small net it is.
        val sign = if (fromRoom) 1L else -1L
        movedGrams += sign * moved.grams
        movedJoules += sign * moved.joules

        val push = handOverMomentum(
            edges, tile, share,
            donorX = if (fromRoom) roomMx else pipeMx,
            donorY = if (fromRoom) roomMy else pipeMy,
            acceptorX = if (fromRoom) pipeMx else roomMx,
            acceptorY = if (fromRoom) pipeMy else roomMy,
            acceptorApertures = if (fromRoom) pipeApertures else roomApertures,
        )
        vesselX += push.x
        vesselY += push.y
    }

    return InterlayerStep(movedGrams, movedJoules, vesselX, vesselY)
}

/**
 * The fraction of a cell that is leaving, kept as a ratio rather than evaluated.
 *
 * Every quantity the crossing carries — each species' grams, the energy, each face's momentum — is
 * the same fraction of a different total, and rounding the fraction once and reusing it would round
 * every one of those to a coarser grid than it needs. Multiplying then dividing keeps each of them
 * to its own precision, which is what stops a trace species being rounded out of existence on the way
 * through a valve.
 */
private class Share(val part: Long, val whole: Long) {
    fun of(quantity: Long): Long = quantity * part / whole
}

private class Moved(val grams: Long, val joules: Long)

private class Push(val x: Long, val y: Long)

/** Moves [share] of one cell's gas, species by species, with the energy that was riding on it. */
private fun handOver(
    share: Share,
    tile: Int,
    donorGrams: LongArray,
    donorJoules: LongArray?,
    acceptorGrams: LongArray,
    acceptorJoules: LongArray?,
): Moved {
    val base = tile * Species.COUNT
    var grams = 0L
    for (s in Species.GASES) {
        val i = base + s.ordinal
        val take = share.of(donorGrams[i])
        if (take == 0L) continue
        donorGrams[i] -= take
        acceptorGrams[i] += take
        grams += take
    }

    // Energy moves as a fraction of what the donor holds, not as `mass × temperature`: the first
    // conserves exactly and the second accumulates the rounding of a division per tick per valve.
    var joules = 0L
    if (donorJoules != null && acceptorJoules != null) {
        joules = share.of(donorJoules[tile])
        donorJoules[tile] -= joules
        acceptorJoules[tile] += joules
    }
    return Moved(grams, joules)
}

/**
 * Hands the donor's share of each of the tile's four faces to the acceptor, or to the ship.
 *
 * See [exchangeLayers] for why a shut face on the acceptor's side means the vessel takes it.
 */
private fun handOverMomentum(
    edges: EdgeGrid,
    tile: Int,
    share: Share,
    donorX: LongArray,
    donorY: LongArray,
    acceptorX: LongArray,
    acceptorY: LongArray,
    acceptorApertures: ApertureField,
): Push {
    var vesselX = 0L
    var vesselY = 0L

    fun cross(edge: Int, donor: LongArray, acceptor: LongArray, open: Boolean): Long {
        // Half, because the face is shared with the neighbour and only this cell's half is leaving.
        val carried = share.of(donor[edge]) / 2
        if (carried == 0L) return 0L
        donor[edge] -= carried
        if (open) {
            acceptor[edge] += carried
            return 0L
        }
        return carried
    }

    for (edge in intArrayOf(edges.leftEdgeOf(tile), edges.rightEdgeOf(tile))) {
        vesselX += cross(edge, donorX, acceptorX, acceptorApertures.isXOpen(edge))
    }
    for (edge in intArrayOf(edges.upEdgeOf(tile), edges.downEdgeOf(tile))) {
        vesselY += cross(edge, donorY, acceptorY, acceptorApertures.isYOpen(edge))
    }
    return Push(vesselX, vesselY)
}

/**
 * Moles per unit of pressure: how much gas this cell swallows before it pushes back as hard as its
 * neighbour. Volume over temperature, which is `PV = nRT` rearranged for the quantity being solved
 * for.
 *
 * Scaled so that a whole tile at room temperature is a comfortably large integer rather than a
 * handful of units, because the split between the two sides is a ratio of these and a ratio of small
 * integers is a coarse one.
 */
private fun pressureCapacity(volume: Int, kelvin: Int): Long =
    volume.toLong() * Temperature.AMBIENT_KELVIN / maxOf(kelvin, 1)

/** One cell's gas temperature, with the same "no gas reads as ambient" convention as [gasKelvin]. */
private fun kelvinAt(grams: LongArray, gasJoules: LongArray?, tile: Int): Int {
    if (gasJoules == null) return Temperature.AMBIENT_KELVIN
    val capacity = gasCapacityAt(grams, tile)
    return if (capacity <= 0L) Temperature.AMBIENT_KELVIN else (gasJoules[tile] / capacity).toInt()
}
