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
enum class BufferRole {
    Input,
    Oxidiser,
    Inside,
    Product,
    Waste,

    /**
     * ⭐ **The two ends of a cell** — `PLAN_electrochemistry.md` §5.5, and named for the same reason
     * [Oxidiser] is.
     *
     * That role exists because a rocket's oxidiser tank reading WASTE *"would be a lie in the one
     * place a reader looks"*. These are the same case twice over. An electrolytic cell's two baths
     * are not a product and a waste — the anode's is oxygen **and the acid the anode reaction makes**,
     * the cathode's is hydrogen **and the caustic**, and increment 3 turns draining either one into
     * a reason to build the machine. Calling one of them WASTE would be wrong before the chemistry
     * that makes it wrong even lands.
     *
     * ⚠️ **The sign is the chemistry, not a convention to be dialled.** Reduction happens at the
     * [Cathode], which is the bath under the negative terminal; oxidation at the [Anode], under the
     * positive one. `localTerminalOffset` and `localBufferOffset` put them over each other on
     * purpose, so the two cannot drift apart.
     */
    Cathode,
    Anode,
}

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
        // ⚠️ **Naming, not a second `Storage` exception.** §5.5 forbade `is Electrolyzer ->` beside
        // `is Storage ->` in [inputBufferRole], where it would have meant "this machine's input port
        // fills a store that is not under it" — the mechanism the 3×2 shape deletes. This is the
        // other question: a cell's two output stores are called [BufferRole.Cathode] and
        // [BufferRole.Anode] rather than product and waste, so the stream each mouth carries has to
        // be told which of them it drains. Every store here still sits on its own port.
        machine is Electrolyzer ->
            if (stream == Stream.Waste) BufferRole.Anode else BufferRole.Cathode
        stream == Stream.Waste -> BufferRole.Waste
        else -> BufferRole.Product
    }
    return if (localBufferOffset(machine, role) != NO_OFFSET) role else null
}

private const val OFFSET_BIAS = 8
internal const val NO_OFFSET = -1

private fun pack(dx: Int, dy: Int): Int = ((dx + OFFSET_BIAS) shl 8) or (dy + OFFSET_BIAS)

internal fun localBufferOffset(machine: DeckMachine, role: BufferRole): Int {
    // ⚠️ **The block's reach from its anchor, not a half-width.** Every machine below is square or a
    // span, so `ahead`, `behind`, `below` and `above` are all equal to what `reach` used to answer —
    // but they are equal for a *reason* now rather than by luck, and an oblong kind can state its
    // doors here without a hand-written branch. See [Footprint.ahead].
    val fp = machine.shape
    return when (machine) {
        // One store, on the one port it has. It used to hold a second — the cell in its jaws, ground
        // into the buffer at a rate — and that bought nothing: a belt tile holds one packet and a
        // machine hands over one packet a tick, so **the rail sets the throughput** and a rate
        // upstream of a full buffer is a rate nobody can observe. A bite now lands straight in the
        // store it leaves from.
        is Extractor -> if (role == BufferRole.Product) pack(fp.ahead, 0) else NO_OFFSET
        // One port, one store, both on the chamber — the tile the machine is stored at. A thruster
        // is two tiles long but only one wide, so its reach is zero and there is no second role to
        // collide with; its bell is footprint and never a store.
        is Thruster -> if (role == BufferRole.Input) pack(0, 0) else NO_OFFSET

        // In at the back, concentrate out the front, tailings out of the floor, and a lump held in
        // the middle while it is worked.
        is Concentrator -> when (role) {
            BufferRole.Input -> pack(-fp.behind, 0)
            BufferRole.Inside -> pack(0, 0)
            BufferRole.Product -> pack(fp.ahead, 0)
            BufferRole.Waste -> pack(0, fp.below)
            BufferRole.Oxidiser, BufferRole.Cathode, BufferRole.Anode -> NO_OFFSET
        }

        // Fuel in at one back corner, oxidiser in at the other, and the chamber between them at the
        // anchor. ⛔ **The bell is `pack(fp.ahead, 0)` and is deliberately NOT a store** — it is the tile
        // the exhaust starts from, and a store there would be propellant sitting in the nozzle.
        is Rocket -> when (role) {
            BufferRole.Input -> pack(-fp.behind, -fp.above)
            BufferRole.Oxidiser -> pack(-fp.behind, fp.below)
            BufferRole.Inside -> pack(0, 0)
            BufferRole.Product, BufferRole.Waste, BufferRole.Cathode, BufferRole.Anode -> NO_OFFSET
        }
        // ⛔ **No [BufferRole.Inside], and that is the machine rather than an omission.** An
        // electrolyzer works at a rate straight out of its feed into its two hoppers; there is no
        // charge sitting in the middle of it being worked on, so there is no tile that would mean
        // anything. See `Electrolyzer`, which argues the same point from the other end.
        // ⭐ **Three baths in a line, each on its own port** — `PLAN_electrochemistry.md` §5.5. The
        // feed is at the anchor, *directly between* the two electrodes, which is what makes ion
        // migration a spatial statement rather than a bookkeeping entry between two stores that
        // happen to share an owner.
        //
        // ⛔ **No [BufferRole.Inside], and that is the point of the shape.** The 3×3 that came before
        // put the feed in `Inside` and gave it a port, which needed `Storage`'s exception —
        // *"a machine declares which store its input port fills"* — generalised to a second machine.
        // Here every store sits on the port it serves, exactly as this file's own rule says, and
        // `Inside` goes on meaning "the one role with no port".
        is Electrolyzer -> when (role) {
            BufferRole.Cathode -> pack(-fp.behind, 0)
            BufferRole.Input -> pack(0, 0)
            BufferRole.Anode -> pack(fp.ahead, 0)
            BufferRole.Inside, BufferRole.Oxidiser, BufferRole.Product, BufferRole.Waste, BufferRole.Cathode, BufferRole.Anode -> NO_OFFSET
        }
        is Furnace -> when (role) {
            BufferRole.Input -> pack(-fp.behind, 0)
            BufferRole.Inside -> pack(0, 0)
            BufferRole.Product -> pack(fp.ahead, 0)
            BufferRole.Waste, BufferRole.Oxidiser, BufferRole.Cathode, BufferRole.Anode -> NO_OFFSET
        }
        // One store, on the one port it has — a pump is one tile, so both are its anchor. What it
        // banks is what it has drawn out of the room and not yet handed to a belt.
        is Pump -> if (role == BufferRole.Product) pack(0, 0) else NO_OFFSET

        // The volume of the building, at the tile it is stored at — all three sizes, unchanged.
        // ⚠️ For a `DeckMachineKind.Buffer` the anchor is also its **input door**, which is the one
        // place a store and a port share a tile on purpose. Harmless here for the reason the note
        // above gives: a storage's one store serves both doors already, so the store is *on* the
        // port it serves either way. See `Storage`.
        is Storage -> if (role == BufferRole.Inside) pack(0, 0) else NO_OFFSET

        is DockingPort -> when (role) {
            BufferRole.Input   -> pack(-fp.behind, -fp.above)
            BufferRole.Product -> pack(-fp.behind, +fp.below)
            else -> NO_OFFSET
        }

        // The three slots of a gantry, which are the three tiles it stands on: what has just been
        // lifted off the track at the near end, what is over the gap, and what is waiting to be put
        // down at the far end. The one machine whose `Inside` is genuinely *in transit* rather than
        // being worked on — see `Bridge`, and `advanceBridges` for the shuffle that moves it along.
        is Bridge -> when (role) {
            BufferRole.Input -> pack(-fp.behind, 0)
            BufferRole.Inside -> pack(0, 0)
            BufferRole.Product -> pack(fp.ahead, 0)
            BufferRole.Waste, BufferRole.Oxidiser, BufferRole.Cathode, BufferRole.Anode -> NO_OFFSET
        }
        else -> NO_OFFSET
    }
}
