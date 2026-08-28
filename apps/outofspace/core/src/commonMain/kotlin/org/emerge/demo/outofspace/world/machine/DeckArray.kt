package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.StuffLayer
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.builtPermille
import org.emerge.demo.outofspace.world.holdsFullBill
import org.emerge.demo.outofspace.world.machineBillOfMaterials
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
class DeckArray(
    /**
     * The lattice its machines are anchored to.
     *
     * Held because a footprint is only meaningful against a grid — see [DeckMachine.tiles] — and
     * this class is the one place that lays matter down across a whole footprint and takes it away
     * again. Passing the grid to every call instead would put the burden on callers that have no
     * business knowing why it is needed.
     */
    val grid: Grid,
    private val machines: Array<DeckMachine?>,
    val stuff: StuffLayer,
    /**
     * **What each machine is to be built out of**, keyed by the tile it is anchored at, or null for
     * its kind's default.
     *
     * ⛔ **Per machine and not per covered tile**, which is the same rule [machines] itself follows:
     * a furnace is three tiles across and stored once, and a material spread over its footprint
     * could be edited into disagreeing with itself. The centre is the machine's address for
     * everything else and it is its address for this.
     *
     * ⚠️ **A parallel array and not a field on [DeckMachine]**, which is the arrangement this class'
     * own header warns about — a parallel array keyed by tile is not re-seated by `copy(...)`. It is
     * held off the same way [stuff] is: [minusAssign] clears it and [set] leaves it alone, which is
     * exactly the distinction between demolishing a machine and replacing it with the next tick's
     * version of itself. Putting it on the machine instead would mean threading it through every
     * subclass constructor and every branch of the save reader, for a value only construction reads.
     *
     * ⚠️ **Null means "no machine is anchored here", never "nobody chose".** A kind has no substance
     * of its own to fall back on — a machine is a *shape and a behaviour*, and neither is a
     * substance. See `Segment.material`, which carries the identical argument for track.
     */
    private val materials: Array<Species?>,
) {

    operator fun get(key: TileIndex): DeckMachine? = if (key == TileIndex.NONE) null else machines[key.index]

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
        for (part in previous.tiles(grid)) stuff.release(part)
        machines[tile.index] = null
        // Cleared with the machine: the choice belonged to the thing that stood here, and leaving it
        // would hand the next machine on this tile a material nobody picked for it.
        materials[tile.index] = null
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

    /*
     * ⛔ **There is no `deck += m` any more, and its absence is the point.** It stood a machine made
     * of its kind's material, which was the game's answer to "what is a Storage normally made of" —
     * the answer Stu asked to have deleted. Every caller now says [stand] and names a substance.
     * The test suite keeps a `+=` of its own (`FixtureDeck.kt`), which is a convention of the
     * fixtures rather than a rule of the game, and says so.
     */

    /**
     * Stands [m] on the deck, with or without the metal it is made of.
     *
     * **Without is a ghost**: the machine is there, it covers its tiles, it is in the player's way —
     * and it holds not one gram, so it weighs nothing, conducts nothing and does nothing. It fills
     * itself off the network through its construction port and becomes a machine when it holds its
     * whole bill. See `apps/outofspace/PLAN_self_building_rails.md`.
     *
     * The casing arriving with the machine is the identity increment 1 broke for track — a thing
     * existing and a thing having its matter were one fact — and this is the same break at the deck
     * layer. Creative mode is what still takes the `true` branch, and there the metal genuinely does
     * arrive from off-world, which is why the caller books it.
     */
    fun stand(m: DeckMachine, withCasing: Boolean, material: Species) {
        require(machines[m.center.index] == null) { "already a machine at ${m.center}" }
        machines[m.center.index] = m
        materials[m.center.index] = material
        val bill = tileBillOfMaterials(m.kind, material)
        for (tile in m.tiles(grid)) {
            require(!stuff.occupies(tile)) { "deck already holds stuff at $tile" }
            if (!withCasing) continue
            // The casing is real matter, put there tile by tile. Energy comes *after* and is derived
            // from that matter rather than from the kind: ambient means "this much stuff, at room
            // temperature", and the only way to state it without a second table that can drift from
            // the first is to ask what is actually here.
            for (s in Species.ALL) stuff[tile, s] = bill[s]
            stuff.setEnergy(tile, stuff.heatCapacityAt(tile) * Temperature.AMBIENT_KELVIN)
        }
    }

    /**
     * Whether the machine at [m] carries every gram of casing it is made of — the opposite of a
     * ghost, and the machine twin of [org.emerge.demo.outofspace.world.TrackLayers.holdsFullBill].
     *
     * Asked of the **whole footprint at once**, summed per species, because a machine's casing is
     * spread evenly over its tiles as it is absorbed rather than completing them one at a time. A
     * per-tile test would answer "finished" for the middle of a half-built furnace.
     *
     * ⛔ A total and never per species — the composition was settled at the door. See
     * [holdsFullBill].
     */
    /**
     * Whether the machine at [tile] is standing there without the metal it is made of.
     *
     * The single answer to "is this a ghost", so the tick, the structure map and the renderer cannot
     * form three opinions. Derived rather than stored — exactly as a ghost rail is `tracks[Rail]`
     * short of its bill — which is why none of this needs a line on disk.
     */
    fun isGhost(tile: TileIndex): Boolean {
        val m = this[tile] ?: return false
        return !holdsFullBill(m)
    }

    fun holdsFullBill(m: DeckMachine): Boolean {
        val tiles = m.tiles(grid)
        val bill = machineBillOfMaterials(m.kind, tiles.size, materialOf(m))
        return holdsFullBill(bill, tiles.sumOf { stuff.massAt(it) })
    }

    /**
     * How built the machine at [m] is, in parts per thousand — 0 for a bare ghost, 1000 for finished.
     *
     * For readouts and the renderer; the sim asks [holdsFullBill], which is the same question without
     * the arithmetic. ⚠️ The **minimum** per-species ratio over the whole footprint, so it reaches
     * 1000 exactly when [holdsFullBill] turns true and the picture cannot say finished while the sim
     * says ghost.
     */
    fun builtPermille(m: DeckMachine): Int {
        val tiles = m.tiles(grid)
        val bill = machineBillOfMaterials(m.kind, tiles.size, materialOf(m))
        return builtPermille(bill, tiles.sumOf { stuff.massAt(it) })
    }

    /**
     * What [m] is made of.
     *
     * ⛔ **A machine standing on the deck without a substance is a corrupt world, not a defaulted
     * one** — [stand] is the only way onto the deck and it requires one.
     */
    fun materialOf(m: DeckMachine): Species =
        materials[m.center.index] ?: error("machine at ${m.center} stands on the deck made of nothing")

    /** What the machine anchored at [tile] is made of, or null where nothing is anchored there. */
    fun materialAt(tile: TileIndex): Species? = materials[tile.index]

    fun copyOf(): DeckArray = DeckArray(grid, machines.copyOf(), stuff.copyOf(), materials.copyOf())

    val size get() = machines.size

    val totalEnergy get() = stuff.totalEnergy

    fun energyAt(tile: TileIndex): Long = stuff.energyAt(tile)

    fun setEnergy(tile: TileIndex, energy: Long) = stuff.setEnergy(tile, energy)
}

fun DeckArray(grid: Grid): DeckArray =
    DeckArray(grid, arrayOfNulls(grid.size), StuffLayer.empty(grid.size), arrayOfNulls(grid.size))
