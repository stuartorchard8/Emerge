package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Processor
import org.emerge.demo.outofspace.world.machine.Smelter
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.ThermalDecomposer
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.Vaporizer

/**
 * What a machine keeps a store *for*. One machine has at most one store of each role, and every
 * store in the vessel lives on [BufferLayer] — so a role plus a machine's centre tile is a complete
 * address for anything a machine is holding.
 *
 * The four cover every buffer in the game: what is waiting to go in, what is being worked on, what
 * is waiting to come out, and what came out that nobody wanted.
 */
enum class BufferRole { Input, Inside, Product, Waste }

/**
 * Where the [role] store of the machine at [centre] stands, or null if it keeps no such store.
 *
 * ### The rule
 *
 * A store sits **on the port it serves**: input at the input port, product at the product port,
 * waste at the waste port. [BufferRole.Inside] is the one role with no port — nothing outside the
 * machine ever touches it — so it takes the centre tile, which no port of a machine bigger than one
 * tile ever claims.
 *
 * That is what lets every buffer in the vessel share a single layer without a slot index: the four
 * roles of one machine resolve to four distinct tiles of its own footprint. A [Storage] is the
 * degenerate case and the reason [BufferRole.Inside] is named for the volume rather than for
 * processing — a warehouse's contents are the volume of the building, not a queue at either door.
 *
 * ⚠️ **Only `reach == 0` machines can break the rule**, since all their offsets collapse onto the
 * centre. No machine with two roles is one tile across today, and [BufferLayer.claimRole] refuses
 * rather than merging if one ever becomes so.
 *
 * ### Why this is not [portsOf]
 *
 * [portsOf] allocates a list, and this is walked for every machine aboard on every tick that states
 * a world. The offsets are duplicated deliberately and `BufferRoleTest` holds the two in agreement.
 */
fun bufferTile(grid: Grid, machine: DeckMachine, centre: TileIndex, role: BufferRole): TileIndex? {
    val packed = localBufferOffset(machine, role)
    if (packed == NO_OFFSET) return null
    var dx = (packed shr 8) - OFFSET_BIAS
    var dy = (packed and 0xFF) - OFFSET_BIAS
    // Direction's declaration order is clockwise, so facing.ordinal is exactly how many quarter
    // turns to apply — the same turn portsOf uses, with +y pointing down the screen.
    repeat(machine.turns) {
        val nx = -dy
        dy = dx
        dx = nx
    }
    val x = grid.xOf(centre) + dx
    val y = grid.yOf(centre) + dy
    return if (grid.inBounds(x, y)) grid.tile(x, y) else null
}

/** Every role the machine actually keeps a store for. Allocation-free walks should use [bufferTile] directly. */
fun bufferRolesOf(machine: DeckMachine): List<BufferRole> =
    BufferRole.entries.filter { localBufferOffset(machine, it) != NO_OFFSET }

/**
 * The store an input port fills, or null if nothing behind that port takes deliveries.
 *
 * A [Storage] answers [BufferRole.Inside] to this *and* to [outputBufferRole], which is what "one
 * pooled store serving both doors" means when it is written down rather than special-cased at each
 * of the half-dozen places that ask.
 */
fun inputBufferRole(machine: DeckMachine): BufferRole? = when (machine) {
    is Storage -> BufferRole.Inside
    else -> if (localBufferOffset(machine, BufferRole.Input) != NO_OFFSET) BufferRole.Input else null
}

/** The store that drains out through an output port carrying [stream], or null. */
fun outputBufferRole(machine: DeckMachine, stream: Stream): BufferRole? {
    val role = when {
        machine is Storage -> BufferRole.Inside
        stream == Stream.Waste -> BufferRole.Waste
        else -> BufferRole.Product
    }
    return if (localBufferOffset(machine, role) != NO_OFFSET) role else null
}

private const val OFFSET_BIAS = 8
internal const val NO_OFFSET = -1

private fun pack(dx: Int, dy: Int): Int = ((dx + OFFSET_BIAS) shl 8) or (dy + OFFSET_BIAS)

internal fun localBufferOffset(machine: DeckMachine, role: BufferRole): Int {
    val r = machine.reach
    return when (machine) {
        // One store, on the one port it has. It used to hold a second — the cell in its jaws, ground
        // into the buffer at a rate — and that bought nothing: a belt tile holds one packet and a
        // machine hands over one packet a tick, so **the rail sets the throughput** and a rate
        // upstream of a full buffer is a rate nobody can observe. A bite now lands straight in the
        // store it leaves from.
        is Extractor -> if (role == BufferRole.Product) pack(r, 0) else NO_OFFSET
        // One port, one store. Both are one-tile machines, so both stores are the centre tile —
        // legal precisely because neither keeps a second role there.
        is Vaporizer -> if (role == BufferRole.Input) pack(r, 0) else NO_OFFSET
        is Thruster -> if (role == BufferRole.Input) pack(0, 0) else NO_OFFSET

        // In at the back, concentrate out the front, tailings out of the floor — and, for a
        // processor, a lump held in the middle while it is worked. A smelter has no such lump: it
        // converts what arrives in the same breath.
        is Processor -> when (role) {
            BufferRole.Input -> pack(-r, 0)
            BufferRole.Inside -> pack(0, 0)
            BufferRole.Product -> pack(r, 0)
            BufferRole.Waste -> pack(0, r)
        }
        is Smelter -> when (role) {
            BufferRole.Input -> pack(-r, 0)
            BufferRole.Product -> pack(r, 0)
            BufferRole.Waste -> pack(0, r)
            BufferRole.Inside -> NO_OFFSET
        }
        is ThermalDecomposer -> when (role) {
            BufferRole.Input -> pack(-r, 0)
            BufferRole.Inside -> pack(0, 0)
            BufferRole.Product -> pack(r, 0)
            BufferRole.Waste -> NO_OFFSET
        }
        is Storage -> if (role == BufferRole.Inside) pack(0, 0) else NO_OFFSET

        // The three slots of a gantry, which are the three tiles it stands on: what has just been
        // lifted off the track at the near end, what is over the gap, and what is waiting to be put
        // down at the far end. The one machine whose `Inside` is genuinely *in transit* rather than
        // being worked on — see `Bridge`, and `advanceBridges` for the shuffle that moves it along.
        is Bridge -> when (role) {
            BufferRole.Input -> pack(-r, 0)
            BufferRole.Inside -> pack(0, 0)
            BufferRole.Product -> pack(r, 0)
            BufferRole.Waste -> NO_OFFSET
        }
        else -> NO_OFFSET
    }
}
