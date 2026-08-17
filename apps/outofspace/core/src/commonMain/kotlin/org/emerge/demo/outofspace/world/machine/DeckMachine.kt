package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.diameter
import org.emerge.demo.outofspace.world.material
import kotlin.jvm.JvmInline

/**
 * A machine on a tile. Immutable — the reducer builds new ones rather than mutating, so a snapshot
 * of the world is a snapshot of the world.
 *
 * Every machine that produces something has a **facing**: its product leaves that side. The two with
 * a waste stream ([Processor], [Smelter]) put waste out the side *clockwise* of facing, which
 * mirrors the separate out/slag ports on the Godot originals and makes a refinery line read as a
 * spine with waste coming off it.
 *
 * Every machine also carries [wiring]: the `Σ(signal × weight)` rules that decide whether — and how
 * fast — it runs. New machines default to "wired to ALWAYS at full", so placing one just works and
 * wiring is something you add rather than something you must do.
 *
 * Rates are mass per second, turned into whole mass per tick by
 * [org.emerge.demo.outofspace.logistics.Rate] with the fraction kept in each machine's own `carry`.
 * Carry is machine state and not a global precisely so it survives a save.
 */
sealed interface DeckMachine {
    val kind: DeckMachineKind
    val wiring: Wiring

    val tiles: Array<TileIndex>
    val center get() = tiles[tiles.size/2]

    fun withWiring(wiring: Wiring): DeckMachine

    fun energy(deckEnergy: EnergyArray) = tiles.map { deckEnergy[it] }

    fun setEnergy(machineEnergy: LongArray, deckEnergy: EnergyArray) {
        require(machineEnergy.size == tiles.size)
        for (i in tiles.indices) {
            deckEnergy[tiles[i]] = machineEnergy[i]
        }
    }

    fun addEnergySpread(added: Long, deck: DeckArray) {
        val each = added / tiles.size
        for (tile in tiles) {
            deck.energies[tile] += each
        }
        // Remainder lands on the centre tile for symmetry
        deck.energies[center] += added % tiles.size
    }
}

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

/**
 * The machine's temperature **averaged over its tiles**.
 *
 * ⚠️ A machine no longer *has* a temperature — it has one per tile, and that is the point of storing
 * them separately. This is the mean, which is the right answer for a readout, a ledger or a test that
 * cares how much heat is in the thing, and the wrong one for anything that cares where the heat is.
 * Reach for [DeckMachine.energy] directly when the gradient is the subject.
 */
fun DeckMachine.temperatureKelvin(energies: EnergyArray): Int {
    val capacity = kind.material.capacityPerTile * kind.diameter * kind.diameter
    val totalEnergy = tiles.sumOf { energies[it] }
    return if (capacity <= 0L) Temperature.SPACE_KELVIN else (totalEnergy / capacity).toInt()
}

/** The same machine with every one of its tiles at [kelvin] — how a uniform body is stated. */
fun DeckMachine.setTemperature(kelvin: Int, deckEnergy: EnergyArray) =
    setEnergy(LongArray(kind.diameter*kind.diameter) { kind.material.capacityPerTile * kelvin }, deckEnergy)

/** What a freshly built machine of this kind holds: every tile of it, at room temperature. */
val DeckMachine.ambientEnergy : Long get() = kind.material.capacityPerTile * Temperature.AMBIENT_KELVIN

/** A machine that faces somewhere. Its ports are laid out relative to that direction. */
sealed interface DirectedDeckMachine : Machine {
    val facing: Direction
    fun rotated(): Machine
}