package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.world.TileIndex

class DeckArray(private val machines: Array<DeckMachine?>, val masses: MassArray, val energies: EnergyArray) {
    operator fun get(key: TileIndex): DeckMachine? {
        return machines[key.index]
    }

    operator fun minusAssign(tile: TileIndex) {
        val previous = machines[tile.index] ?: return
        for (tile in previous.tiles) {
            // N.B. This assumes the mass and energy was copied elsewhere prior to calling
            energies[tile] = 0L
            Species.ALL.forEach { masses[MassIndex(tile,it)] = 0L }
        }
        machines[tile.index] = null
    }

    /**
     * Swaps the machine at [key] for another standing on the same tiles, leaving the stores alone.
     *
     * The distinction from `-=` then `+=` is the whole reason it exists: that pair means *demolish
     * and build*, and it zeroes the matter and re-seeds the energy at ambient. Running a machine
     * for a tick is neither, so the tick loop uses this and a hull stops being reset to room
     * temperature every time it is stepped.
     */
    operator fun set(key: TileIndex, m: DeckMachine) {
        require(machines[key.index] != null) { "nothing to replace at $key" }
        require(m.center == key) { "tried to replace machine at $key with one at ${m.center}" }
        machines[key.index] = m
    }

    operator fun plusAssign(m: DeckMachine) {
        val previous = machines[m.center.index]
        require(previous == null) // No overwriting

        machines[m.center.index] = m
        for (tile in m.tiles) {
            require(Species.ALL.all { masses[MassIndex(tile,it)] == 0L })
            require(energies[tile] == 0L)
            energies[tile] = m.ambientEnergy
            // TODO assign machine mass
        }
    }

    fun copyOf() : DeckArray = DeckArray(machines.copyOf(), masses.copyOf(), energies.copyOf())

    val size get() = machines.size

    val totalEnergy get() = energies.data.sum()
}

fun DeckArray(size: Int): DeckArray {
    return DeckArray(Array(size){null}, MassArray(size), EnergyArray(size))
}
