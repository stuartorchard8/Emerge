package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.Electrolyzer
import org.emerge.demo.outofspace.world.machine.SolarPanel
import org.emerge.demo.outofspace.world.machine.Terminal

/**
 * **Which of a machine's tiles bond the layers under them**, and which end of it each one is.
 *
 * Increment 1 of `PLAN_power_network.md`. A terminal is *"a big conductive rod touching all the
 * layers in a given tile"* (Stu), and it is the **only** place the electrical contact graph differs
 * from the thermal one: everything standing on a tile conducts heat to everything else standing on
 * it, and charge crosses between layers **only** where a terminal stands.
 *
 * ⭐ **That is what makes wiring a choice rather than a consequence of geometry.** Without it a
 * power run would short to the deck plate it crosses, a rail to the signal wire it passes under, and
 * every wired machine to the hull through its own chassis. With it, a rail crossing a power run is
 * two circuits — so crossings are free and no power bridge is needed — and the player picks whether
 * charge travels by wire, by rail, or through the building itself.
 *
 * ### Why roles rather than one flag
 *
 * A bonding point and a *device* terminal are not quite the same thing. A machine that does
 * electrical work has **two** ends and has to tell them apart, because the whole of
 * `PLAN_power_network.md` §5 is that its casing is a parallel path *between* them; the standalone
 * [Terminal] of increment 3c has no ends at all and says so with [TerminalRole.Bond]. So they are
 * named here and the naming costs nothing.
 *
 * ⚠️ **They must sit on different tiles, and that is a constraint on the footprint rather than a
 * rule stated here.** A one-tile machine's casing is a single body, so both its ends would be the
 * same node — a dead short no material can fix. It is why `SolarPanel` grows to 3×3 in increment 3,
 * and why [Electrolyzer]'s two ends are its opposite arms with three tiles of casing between them.
 *
 * ⚠️ **Not folded into [BufferRole].** A port is where matter crosses and a terminal is where charge
 * does; they coincide on the cell's arms and they will not always, and this codebase deleted
 * `Material` rather than live with a name that means two things.
 */
enum class TerminalRole {
    Positive,
    Negative,

    /**
     * ⭐ **A bonding point, which is not one end of anything.**
     *
     * The standalone [org.emerge.demo.outofspace.world.machine.Terminal] of increment 3c is a rod
     * and nothing else: it does no work, so it has no element for a casing to be a parallel path
     * around and no pair for [Positive] and [Negative] to be the two of. Calling it positive would
     * have it answer a question it has no answer to, and every walk that asks for a *device's* ends
     * asks for a named role and would then find one that is not there.
     *
     * ⚠️ **Everything that merely asks "does a terminal stand here" walks [entries]**, so this joins
     * the graph with no branch anywhere — see [terminalTiles] and [hasTerminalAt].
     */
    Bond,
}

/**
 * Where the [role] terminal of the machine at [centre] stands, or null if it keeps no such terminal.
 *
 * Offsets are stated in the machine's own frame and turned by its facing, exactly as
 * [bufferTile] turns a port's — the same quarter-turns, for the same reason.
 */
fun terminalTile(grid: Grid, machine: DeckMachine, centre: TileIndex, role: TerminalRole): TileIndex? {
    val packed = localTerminalOffset(machine, role)
    if (packed == NO_TERMINAL) return null
    var dx = (packed shr 8) - TERMINAL_BIAS
    var dy = (packed and 0xFF) - TERMINAL_BIAS
    repeat(machine.turns) {
        val nx = -dy
        dy = dx
        dx = nx
    }
    val x = grid.xOf(centre) + dx
    val y = grid.yOf(centre) + dy
    return if (grid.inBounds(x, y)) grid.tile(x, y) else null
}

/** Every terminal this machine keeps. Allocation-free walks should use [terminalTile] directly. */
fun terminalRolesOf(machine: DeckMachine): List<TerminalRole> =
    TerminalRole.entries.filter { localTerminalOffset(machine, it) != NO_TERMINAL }

/** Whether any terminal of [machine] stands at [at] — the question the contact graph asks. */
fun hasTerminalAt(grid: Grid, machine: DeckMachine, centre: TileIndex, at: TileIndex): Boolean {
    for (role in TerminalRole.entries) {
        if (localTerminalOffset(machine, role) == NO_TERMINAL) continue
        if (terminalTile(grid, machine, centre, role) == at) return true
    }
    return false
}

private const val TERMINAL_BIAS = 8
private const val NO_TERMINAL = -1

private fun packTerminal(dx: Int, dy: Int): Int = ((dx + TERMINAL_BIAS) shl 8) or (dy + TERMINAL_BIAS)

/**
 * **What has terminals, and where.**
 *
 * ⚠️ **Two so far.** The cell is the machine the network was designed against, so it declared its
 * ends first and the graph's tests are written against it; the panel got its pair in increment 3,
 * along with the footprint to hang them off.
 */
private fun localTerminalOffset(machine: DeckMachine, role: TerminalRole): Int {
    // ⚠️ **The block's reach from its anchor, not a half-width.** Every machine below is square or a
    // span, so `ahead`, `behind`, `below` and `above` are all equal to what `reach` used to answer —
    // but they are equal for a *reason* now rather than by luck, and an oblong kind can state its
    // doors here without a hand-written branch. See [Footprint.ahead].
    val fp = machine.shape
    return when (machine) {
        // ⭐ **The two arms, with the machine's body between them.** This is the pair
        // `PLAN_power_network.md` §5 needs: current entering one end reaches the other either
        // through the electrolyte, which does the work, or around the outside through the casing,
        // which does not — so a copper-cased cell shorts itself and a firebrick-cased one runs.
        is Electrolyzer -> when (role) {
            TerminalRole.Negative -> packTerminal(-fp.behind, 0)
            TerminalRole.Positive -> packTerminal(fp.ahead, 0)
            else -> NO_TERMINAL
        }
        // ⭐ **The centre line at either end** (Stu), which is the same pair for the same reason:
        // three tiles of casing between them, so what the casing is made of decides whether the
        // panel drives anything or merely warms itself.
        //
        // ⚠️ A photovoltaic cell drives electrons from its P side to its N side, so the **N side is
        // the negative terminal** — the one with the surplus. Getting this backwards would run the
        // whole network the wrong way and look entirely plausible doing it.
        is SolarPanel -> when (role) {
            TerminalRole.Negative -> packTerminal(fp.ahead, 0)
            TerminalRole.Positive -> packTerminal(-fp.behind, 0)
            else -> NO_TERMINAL
        }
        // ⭐ **On its own tile, because the machine *is* the terminal.** One tile is the whole of it
        // — there is nothing to be at one end of, which is why the role is [TerminalRole.Bond] and
        // why the one-body-one-node constraint that forced the panel to 3×3 does not apply here.
        is Terminal -> if (role == TerminalRole.Bond) packTerminal(0, 0) else NO_TERMINAL
        else -> NO_TERMINAL
    }
}
