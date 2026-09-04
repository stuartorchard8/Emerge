package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.Electrolyzer
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DockingPort
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Concentrator
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Furnace
import org.emerge.demo.outofspace.world.machine.Pump
import org.emerge.demo.outofspace.world.machine.Rocket
import org.emerge.demo.outofspace.world.machine.Thruster

/**
 * What a machine keeps a store *for*. One machine has at most one store of each role, and every
 * store in the vessel lives on [BufferLayer] — so a role plus a machine's centre tile is a complete
 * address for anything a machine is holding.
 *
 * The five cover every buffer in the game: what is waiting to go in, what is waiting to go in
 * *alongside* it, what is being worked on, what is waiting to come out, and what came out that
 * nobody wanted.
 *
 * ### ⛔ Why there is a second input rather than a reused [Waste]
 *
 * A [Rocket] needs fuel, oxidiser and a chamber, which is three stores, and [Inside] is spoken for
 * by the chamber. Handing the oxidiser [Waste] would fit the tile rule perfectly and would be a lie
 * in the one place a reader looks — the inspector would label a full oxidiser tank WASTE, and the
 * next person to add a machine would have no way to tell which of the two meanings was meant. This
 * codebase deleted `Material` and `MachineKind` rather than live with a name that lies.
 *
 * ⚠️ **Adding one is cheap and stays cheap**, which is the property to preserve: [BufferLayer] is
 * keyed by *tile*, not by (machine, role), so a role costs a distinct tile of the machine's own
 * footprint and nothing else. A 3×3 has nine and the rocket uses three.
 */
enum class BufferRole { Input, Oxidiser, Inside, Product, Waste }

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
 * That is what lets every buffer in the vessel share a single layer without a slot index: the roles
 * of one machine resolve to as many distinct tiles of its own footprint. A [Storage] is the
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

/**
 * The store the input port **at [at]** fills, or null if nothing behind that tile takes deliveries.
 *
 * ⛔ **A machine with two doors cannot answer [inputBufferRole], and asking it is the bug.** That
 * function takes a machine and no place, which was a complete question while every kind had at most
 * one mouth; a [Rocket] has two and they mean different things. Everything that routes material now
 * asks *which door did this arrive at* — which it always knew, because a delivery is made through a
 * port and a port has a tile.
 *
 * The general rule does the work: a store sits on the port it serves, so the door and the store are
 * the same tile and the answer is a lookup rather than a table. [Storage] is the one exception, and
 * it is the exception here for the same reason it is one above — its pooled store is the volume of
 * the building, not a queue at either door.
 */
fun inputBufferRoleAt(grid: Grid, machine: DeckMachine, at: TileIndex): BufferRole? {
    if (machine is Storage) return BufferRole.Inside
    for (role in INPUT_ROLES) {
        if (localBufferOffset(machine, role) == NO_OFFSET) continue
        if (bufferTile(grid, machine, machine.center, role) == at) return role
    }
    // A door that is not on a store's tile: every machine but the rocket has exactly one input and
    // the port it is drawn at need not coincide with it. Fall back to the kind-blind answer, which
    // is the whole of the behaviour that existed before two doors did.
    return inputBufferRole(machine)
}

/** The roles a *delivery* may land in, in the order [inputBufferRoleAt] considers them. */
private val INPUT_ROLES: List<BufferRole> = listOf(BufferRole.Input, BufferRole.Oxidiser)

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
        // One port, one store, both on the chamber — the tile the machine is stored at. A thruster
        // is two tiles long but only one wide, so its reach is zero and there is no second role to
        // collide with; its bell is footprint and never a store.
        is Thruster -> if (role == BufferRole.Input) pack(0, 0) else NO_OFFSET

        // In at the back, concentrate out the front, tailings out of the floor, and a lump held in
        // the middle while it is worked.
        is Concentrator -> when (role) {
            BufferRole.Input -> pack(-r, 0)
            BufferRole.Inside -> pack(0, 0)
            BufferRole.Product -> pack(r, 0)
            BufferRole.Waste -> pack(0, r)
            BufferRole.Oxidiser -> NO_OFFSET
        }

        // Fuel in at one back corner, oxidiser in at the other, and the chamber between them at the
        // anchor. ⛔ **The bell is `pack(r, 0)` and is deliberately NOT a store** — it is the tile
        // the exhaust starts from, and a store there would be propellant sitting in the nozzle.
        is Rocket -> when (role) {
            BufferRole.Input -> pack(-r, -r)
            BufferRole.Oxidiser -> pack(-r, r)
            BufferRole.Inside -> pack(0, 0)
            BufferRole.Product, BufferRole.Waste -> NO_OFFSET
        }
        // ⛔ **No [BufferRole.Inside], and that is the machine rather than an omission.** An
        // electrolyzer works at a rate straight out of its feed into its two hoppers; there is no
        // charge sitting in the middle of it being worked on, so there is no tile that would mean
        // anything. See `Electrolyzer`, which argues the same point from the other end.
        is Electrolyzer -> when (role) {
            BufferRole.Input -> pack(-r, 0)
            BufferRole.Product -> pack(r, 0)
            BufferRole.Waste -> pack(0, r)
            BufferRole.Inside, BufferRole.Oxidiser -> NO_OFFSET
        }
        is Furnace -> when (role) {
            BufferRole.Input -> pack(-r, 0)
            BufferRole.Inside -> pack(0, 0)
            BufferRole.Product -> pack(r, 0)
            BufferRole.Waste, BufferRole.Oxidiser -> NO_OFFSET
        }
        // One store, on the one port it has — a pump is one tile, so both are its anchor. What it
        // banks is what it has drawn out of the room and not yet handed to a belt.
        is Pump -> if (role == BufferRole.Product) pack(0, 0) else NO_OFFSET

        is Storage -> if (role == BufferRole.Inside) pack(0, 0) else NO_OFFSET

        is DockingPort -> when (role) {
            BufferRole.Input   -> pack(-r, -r)
            BufferRole.Product -> pack(-r, +r)
            else -> NO_OFFSET
        }

        // The three slots of a gantry, which are the three tiles it stands on: what has just been
        // lifted off the track at the near end, what is over the gap, and what is waiting to be put
        // down at the far end. The one machine whose `Inside` is genuinely *in transit* rather than
        // being worked on — see `Bridge`, and `advanceBridges` for the shuffle that moves it along.
        is Bridge -> when (role) {
            BufferRole.Input -> pack(-r, 0)
            BufferRole.Inside -> pack(0, 0)
            BufferRole.Product -> pack(r, 0)
            BufferRole.Waste, BufferRole.Oxidiser -> NO_OFFSET
        }
        else -> NO_OFFSET
    }
}
