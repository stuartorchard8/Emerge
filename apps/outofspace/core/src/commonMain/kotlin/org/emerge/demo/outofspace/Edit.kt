package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.massAtReducedDensity
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.reducedTemperature
import org.emerge.demo.outofspace.chem.saturatedLiquidDensity

import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.machine.InputKey
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.VolumeField
import org.emerge.demo.outofspace.world.machine.DeckMachineKind

/** A player action. Actions are values, so they replay, serialise and travel over a wire. */
sealed interface Edit {
    /**
     * Put something on a tile. One case for both a run and a building — see [Brush] for why the
     * difference belongs where the edit is applied rather than in the edit itself.
     */
    data class Place(val tile: TileIndex, val brush: Brush, val facing: Direction) : Edit
    data class Rotate(val tile: TileIndex) : Edit
    /**
     * Takes something off a tile — one layer of it, or a named one, or all of it.
     *
     * [layer] defaults to [DeleteLayer.Top], which is the blind one-layer-per-click behaviour every
     * caller had before the delete tool existed, so nothing that predates it changes meaning.
     */
    data class Remove(val tile: TileIndex, val layer: DeleteLayer = DeleteLayer.Top) : Edit

    /** Lay conduit between adjacent tiles [from]→[to] (one drag step). Missing track laid at ends. Non-adjacent = ignored (no pathfind). */
    data class Lay(val from: TileIndex, val to: TileIndex, val conduit: Conduit = Conduit.Rail) : Edit

    /**
     * Calls off a deconstruction, on whatever layers of [tile] have been told to go.
     *
     * **It restores a target, not a machine.** Ghost-ness is derived — a thing is unbuilt exactly
     * when it is short of its bill — so taking the mark off is the whole of the operation: something
     * that has not yet given anything back is instantly a finished machine again, and something
     * half-dismantled becomes an ordinary construction site and fills itself back up off the network.
     * There is no third state to restore and nothing to book, because nothing left the world.
     *
     * The mirror of [Remove] and deliberately blind in the same way: it clears the mark from every
     * layer of the tile at once. A player pointing at a condemned tile means "not that one", and
     * having to name which of the four layers they meant would be a worse tool than the one that
     * marked them.
     */
    data class Cancel(val tile: TileIndex) : Edit

    /** Severs the join between two adjacent tiles, leaving both lengths of track in place. */
    data class Cut(val from: TileIndex, val to: TileIndex, val conduit: Conduit = Conduit.Rail) : Edit

    /** Binds a button to a different key — see [org.emerge.demo.outofspace.world.machine.WireButton]. */
    data class BindKey(val tile: TileIndex, val key: InputKey) : Edit

    /**
     * Locks a warehouse onto what it is holding most of, at [minPercent] purity — or unlocks it
     * when [minPercent] is null.
     *
     * ⛔ **The species is not in the edit, and that is the whole design.** The player picks a
     * threshold; *what* is locked in is whatever the tank is already full of, read here in the
     * reducer where the contents live. A UI that named a species would be offering a list of every
     * material in the game to a player who has seen three of them, and would let them lock a
     * warehouse onto something that has never come aboard.
     *
     * Locking an empty warehouse does nothing: there is nothing to lock onto.
     */
    data class LockStorage(val tile: TileIndex, val minPercent: Int?) : Edit

    /** Wire: rewires action term. slot≥end=append, null trigger=remove. Single edit type (add/change/remove are same list op). */
    data class Wire(val tile: TileIndex, val action: Action, val slot: Int, val trigger: Trigger?) : Edit

    /** Directional thrust (dx, dy each −1/0/1). Acceleration-based (reducer multiplies by vessel mass). Placeholder engine (ledger: debugImpulseX). */
    data class Thrust(val dx: Int, val dy: Int) : Edit

    /** Drop rock at ([x], [y]) (relative to grid). Free mass — no ledger entry, same as spawner. */
    data class DropRock(val x: Float, val y: Float, val radius: Int = DEFAULT_ROCK_RADIUS) : Edit

    /**
     * Puts [INJECT_MASS] of room-temperature air into a tile — the debug bellows.
     *
     * **It mints matter, and that is the whole difficulty with it.** Every other way gas enters this
     * world is a transfer from somewhere else, which is why `atmosphere + vented == baseline` can be
     * asserted on every tick and why a leak is one assertion away rather than a mystery. Gas from
     * nowhere makes that identity false forever, and an instrument that reads LEAK whatever happens
     * is an instrument nobody looks at again.
     *
     * So it is booked, exactly as [Thrust] is booked into `debugImpulseX`: the balance becomes
     * `atmosphere + vented − injected == baseline`, which is the old identity precisely whenever
     * nothing has cheated. See [org.emerge.demo.outofspace.world.VesselState.injectedAirMass].
     *
     * One edit per tick for as long as the button is held — see [OutofspaceController.injectTile] —
     * so the rate is a rate rather than a function of the frame rate.
     */
    data class Inject(val tile: TileIndex, val mass: Long = INJECT_MASS, val water: Boolean = false) : Edit

    /**
     * Takes the grid back to the ship plus its pad. The only edit that can make the grid smaller,
     * so the only one that discards cells — which [org.emerge.demo.outofspace.world.remapped] vents.
     *
     * An edit rather than a controller method so two hosts fit on the same tick.
     */
    data object Fit : Edit

    companion object {
        /** Thrust magnitude: 250 milli-g (quarter gravity). Settles rooms over tens of ticks. */
        const val DEBUG_THRUST_MILLI_G: Long = 25L

        /**
         * What one tick of the injector delivers: a kilogram, which is about what a tile holds at one
         * atmosphere. So a held button fills a room at roughly a tile a tick — fast enough to be a
         * tool and slow enough to watch the front move.
         *
         * **Derivation**: one kilogram, which is [Budget]'s unit for exactly this reason. It could
         * equally be written as `AirField.AMBIENT_AIR.total` and that would be truer still — a
         * tile-load rather than a mass that happens to equal one — but the atmosphere is a layer
         * above this file and the coincidence is close enough to state in words instead.
         */
        val INJECT_MASS: Long = 1L * Budget.KILOGRAM

        /**
         * What one tick of the *water* injector delivers — a sixty-fourth of what a tile holds when
         * it is full of saturated liquid, so a held button fills a tile in about a second.
         *
         * Derived rather than picked, and it has to be: liquid water is some seven hundred times
         * denser than air, so [INJECT_MASS] would take six hundred ticks to make a puddle and would
         * read as a broken tool. The number is whatever `Saturation.kt` says a full tile weighs at
         * [WATER_INJECT_KELVIN], divided by 64.
         */
        val WATER_INJECT_MASS: Long = massAtReducedDensity(
            saturatedLiquidDensity(reducedTemperature(WATER_INJECT_KELVIN, Species.Water)!!)!!,
            Species.Water,
            VolumeField.FULL,
            VolumeField.FULL,
        )!! / 64

        /**
         * The temperature water arrives at: **−43 °C**, and it is cold on purpose.
         *
         * Van der Waals carries no acentric factor, so this model puts water's saturation pressure
         * at 4.9 atm at room temperature and its boiling point at one atmosphere near −33 °C — see
         * `PLAN_phase_transitions.md` §5c. Water injected at 293 K would therefore flash straight to
         * vapour and there would be no puddle to look at, which would look like a broken tool rather
         * than an honest equation. At 230 K its vapour pressure is 0.65 atm, comfortably under the
         * room, so it arrives as a liquid and stays one until something heats it.
         *
         * ⚠️ When the equation of state gains a third constant (Peng-Robinson), this should go back
         * to ambient — it exists only to work around a known quantitative error, not a design choice.
         */
        const val WATER_INJECT_KELVIN: Int = 230

        /** Rock radius: 2 cells (5 tiles across, 21 cells). Fits through doorways. */
        const val DEFAULT_ROCK_RADIUS: Int = 2
    }
}
