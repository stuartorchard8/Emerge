package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState

/**
 * One readable layer of one tile — what [Tool.Inspect] is looking at.
 *
 * ⛔ **One layer at a time, deliberately.** A tile is not one thing: it can hold a building, the
 * track threaded under it, a pipe, a wire and a room's worth of air, and a panel that described all
 * of them at once would be a wall of numbers with the interesting one somewhere in the middle. So
 * the inspector reads *one*, and a repeat click on the same tile moves to the next layer that has
 * anything to say — the same idea as [DeleteLayer.Top] peeling one layer per click, except that
 * here nothing is destroyed by guessing wrong.
 *
 * The order below is the cycle order, and it is "most likely to be what you clicked on" first: a
 * player who clicks a furnace means the furnace, not the air in front of it.
 */
enum class InspectLayer(val label: String) {
    /**
     * The building standing on the tile: its whole-machine composition, everything in its buffers,
     * and every setting it has — wiring, thermostat, storage lock.
     *
     * ⚠️ **Whole machine, not this tile.** A furnace is nine tiles of one object; the mass of a
     * ninth of its casing is a number about the grid rather than about the furnace, and nobody
     * wants it. Buffers and settings already belong to the object, so the composition is the only
     * part that had a choice to make here.
     */
    Deck("DECK"),

    /** The track, and the lump riding on it. */
    Rail("RAIL"),


    /** The wire, and what the circuit under this tile is carrying. */
    Signal("SIGNAL"),

    /** Cable. Carries nothing yet — see [Conduit.Power]. */
    Power("POWER"),

    /**
     * The room: pressure, temperature, flow and gas composition.
     *
     * ⚠️ **Always present wherever air could be**, empty or not. "Vacuum" is the single most useful
     * thing this panel ever says — a hull breach reads as a tile that has *stopped* having an
     * atmosphere — so a layer that vanished when the room emptied would disappear exactly when it
     * was wanted.
     */
    Atmosphere("AIR"),
}

/**
 * Which layers of [tile] have anything to say, in cycle order. Empty for a tile off the grid.
 *
 * Presence is asked of the world, not of the player: a layer is listed when there is something
 * there to read. The one exception is [InspectLayer.Atmosphere] — see its note.
 */
fun inspectableLayers(state: VesselState, tile: TileIndex): List<InspectLayer> {
    if (tile == TileIndex.NONE || tile.index < 0 || tile.index >= state.grid.size) return emptyList()
    val out = ArrayList<InspectLayer>(3)
    // Through `occupancy`, so any tile of a five-by-five machine offers its DECK layer rather than
    // only the one square the object happens to be stored at.
    if (state.occupancy[tile] != TileIndex.NONE) out.add(InspectLayer.Deck)
    if (state.conduits[Conduit.Rail][tile.index] != null) out.add(InspectLayer.Rail)
    if (state.conduits[Conduit.Signal][tile.index] != null) out.add(InspectLayer.Signal)
    if (state.conduits[Conduit.Power][tile.index] != null) out.add(InspectLayer.Power)
    if (!state.structure.blocksAir(tile)) out.add(InspectLayer.Atmosphere)
    return out
}
