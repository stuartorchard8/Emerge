package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.StuffLayer
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.tileBillOfMaterials

/**
 * The deck layer: which machine stands on each tile, and — in [stuff] — the matter and energy that
 * machine is made of.
 *
 * The two are separate arrays over one lattice rather than fields on the machine, which reverses the
 * arrangement [Machine.energy]'s note argues for. The argument there was desynchronisation: a
 * parallel array keyed by tile is not re-seated by `copy(machines = …)`, so a freshly laid rail
 * inherits the energy of the furnace that used to stand there. That risk is real and it is now held
 * off structurally instead — see [minusAssign] versus [set], which is the whole distinction between
 * *demolishing* a machine and *replacing* it with the next tick's version of itself.
 *
 * What the old arrangement could not do is the reason for the change: a machine cannot own the
 * storage for a chemical reaction that spans layers, because the reaction is not the machine's. Heat
 * crossing from a buffer into the casing around it, or carbon on a rail burning in the room's oxygen,
 * are facts about a *tile*, and they want every layer at that tile addressed the same way.
 */
class DeckArray(private val machines: Array<DeckMachine?>, val stuff: StuffLayer) {

    operator fun get(key: TileIndex): DeckMachine? = machines[key.index]

    /**
     * Demolish the machine at [tile]: it stops standing there, and the deck stops holding matter and
     * energy for its tiles.
     *
     * N.B. This assumes the mass and energy were taken somewhere else first — a scrapped machine's
     * refund is booked by the caller, because only the caller knows whether this is a scrapping, a
     * vaporisation or a load.
     */
    operator fun minusAssign(tile: TileIndex) {
        val previous = machines[tile.index] ?: return
        for (part in previous.tiles) stuff.release(part)
        machines[tile.index] = null
    }

    /**
     * Swaps the machine at [key] for another standing on the same tiles, leaving the stores alone.
     *
     * The distinction from `-=` then `+=` is the whole reason it exists: that pair means *demolish
     * and build*, and it drops the matter and re-seeds the energy at ambient. Running a machine
     * for a tick is neither, so the tick loop uses this and a hull stops being reset to room
     * temperature every time it is stepped.
     */
    operator fun set(key: TileIndex, m: DeckMachine) {
        require(machines[key.index] != null) { "nothing to replace at $key" }
        require(m.center == key) { "tried to replace machine at $key with one at ${m.center}" }
        machines[key.index] = m
    }

    operator fun plusAssign(m: DeckMachine) {
        require(machines[m.center.index] == null) { "already a machine at ${m.center}" }
        machines[m.center.index] = m
        val bill = tileBillOfMaterials(m.kind)
        for (tile in m.tiles) {
            require(!stuff.occupies(tile)) { "deck already holds stuff at $tile" }
            // The casing is real matter, put there tile by tile. Energy comes *after* and is derived
            // from that matter rather than from the kind: ambient means "this much stuff, at room
            // temperature", and the only way to state it without a second table that can drift from
            // the first is to ask what is actually here.
            for (s in Species.ALL) stuff[tile, s] = bill[s]
            stuff.setEnergy(tile, stuff.heatCapacityAt(tile) * Temperature.AMBIENT_KELVIN)
        }
    }

    fun copyOf(): DeckArray = DeckArray(machines.copyOf(), stuff.copyOf())

    val size get() = machines.size

    val totalEnergy get() = stuff.totalEnergy

    fun energyAt(tile: TileIndex): Long = stuff.energyAt(tile)

    fun setEnergy(tile: TileIndex, energy: Long) = stuff.setEnergy(tile, energy)
}

fun DeckArray(size: Int): DeckArray = DeckArray(arrayOfNulls(size), StuffLayer.empty(size))
