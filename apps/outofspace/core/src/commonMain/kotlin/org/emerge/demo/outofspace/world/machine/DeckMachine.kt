package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.footprint
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.StuffLayer
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.reach
import org.emerge.demo.outofspace.world.diameter
import org.emerge.demo.outofspace.world.capacityPerTile
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

    /**
     * The `Σ(signal × weight)` rules that decide whether — and how fast — it runs. A new machine is
     * wired to ALWAYS at full, so placing one just works and wiring is something you add.
     */
    val wiring: Wiring

    /** Half-width — where a square kind's ports and stores sit. See [DeckMachineKind.reach]. */
    val reach: Int get() = kind.reach

    /** Quarter-turns clockwise from the facing-Right frame. Zero for anything that does not face. */
    val turns: Int get() = (this as? DirectedDeckMachine)?.facing?.ordinal ?: 0

    /**
     * The tile it is stored at: where the deck array holds it, what [Occupancy] points every covered
     * tile back at, and the frame its ports and stores are offset from.
     *
     * ⚠️ **Not necessarily the middle of its footprint.** It is for a square kind and for a bridge,
     * and it is *not* for a thruster, whose anchor is its chamber and whose second tile is its bell
     * — see [org.emerge.demo.outofspace.world.FootprintShape.Nose]. Anything that wants the middle
     * of the thing standing here — a lever arm, a bounding box, a body to draw — must walk [tiles],
     * not add and subtract a half-width from this.
     */
    val center: TileIndex

    /**
     * Every tile this machine covers, on [grid].
     *
     * ⚠️ **A function of the grid, not a field.** A footprint is a set of flat tile indexes, and
     * which indexes surround a centre depends on how wide the grid is — so a stored array would be
     * silently wrong the moment the vessel grew, and an [Array] field would give every machine
     * reference equality into the bargain (see [TileEnergy] for that bug in its previous life).
     * Deriving it costs one small allocation at the sites that walk a footprint, all of which have a
     * grid to hand because they are all iterating a world.
     */
    fun tiles(grid: Grid): Array<TileIndex> =
        kind.footprint(center, grid, (this as? DirectedDeckMachine)?.facing ?: Direction.Right)
            ?: error("a ${kind.label} at $center does not fit this grid")

    fun withWiring(wiring: Wiring): DeckMachine

    /**
     * The same machine anchored at [center] — how a world states itself on a different lattice.
     *
     * A machine's [tiles] are absolute grid indexes, so they mean a different place the moment the
     * grid changes shape. Re-anchoring is the machine's own job because only it knows how its
     * footprint hangs off its centre; see [org.emerge.demo.outofspace.world.remapped].
     */
    fun movedTo(center: TileIndex): DeckMachine

    fun energy(grid: Grid, deck: StuffLayer) = tiles(grid).map { deck.energyAt(it) }

    fun setEnergy(machineEnergy: LongArray, grid: Grid, deck: StuffLayer) {
        val tiles = tiles(grid)
        require(machineEnergy.size == tiles.size)
        for (i in tiles.indices) {
            deck.setEnergy(tiles[i], machineEnergy[i])
        }
    }

    fun addEnergySpread(added: Long, grid: Grid, deck: DeckArray) {
        val tiles = tiles(grid)
        val each = added / tiles.size
        for (tile in tiles) {
            deck.stuff.addEnergy(tile, each)
        }
        // Remainder lands on the centre tile for symmetry
        deck.stuff.addEnergy(center, added % tiles.size)
    }
}

/**
 * The machine's temperature **averaged over its tiles**.
 *
 * ⚠️ A machine no longer *has* a temperature — it has one per tile, and that is the point of storing
 * them separately. This is the mean, which is the right answer for a readout, a ledger or a test that
 * cares how much heat is in the thing, and the wrong one for anything that cares where the heat is.
 * Reach for [DeckMachine.energy] directly when the gradient is the subject.
 */
fun DeckMachine.temperatureKelvin(grid: Grid, deck: StuffLayer): Int {
    // Capacity summed from the matter actually stored, not from `kind.capacityPerTile × tiles`. The
    // two agree to within a part per million today — measured — and they stop agreeing the moment a
    // reaction changes what a tile is made of, which is exactly when the stored answer is the right
    // one and the constant is stale.
    val tiles = tiles(grid)
    val capacity = tiles.sumOf { deck.heatCapacityAt(it) }
    val totalEnergy = tiles.sumOf { deck.energyAt(it) }
    return if (capacity <= 0L) Temperature.SPACE_KELVIN else (totalEnergy / capacity).toInt()
}

/** The same machine with every one of its tiles at [kelvin] — how a uniform body is stated. */
fun DeckMachine.setTemperature(kelvin: Int, grid: Grid, deck: StuffLayer) {
    val tiles = tiles(grid)
    setEnergy(LongArray(tiles.size) { deck.heatCapacityAt(tiles[it]) * kelvin }, grid, deck)
}

/** What a freshly built machine of this kind holds: every tile of it, at room temperature. */
val DeckMachine.ambientEnergy : Long get() = kind.capacityPerTile * Temperature.AMBIENT_KELVIN

/** A machine that faces somewhere. Its ports are laid out relative to that direction. */
sealed interface DirectedDeckMachine : DeckMachine {
    val facing: Direction
    fun rotated(): DeckMachine
}