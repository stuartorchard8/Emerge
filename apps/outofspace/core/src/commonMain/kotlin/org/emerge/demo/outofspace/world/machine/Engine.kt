package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex

/**
 * **A machine that throws mass astern.** What every rocket in the game has in common, which turns
 * out to be less than it looks: a rate, a nozzle, a throttle it was told, and a store to throw from.
 *
 * ### Why this exists
 *
 * There is one kind of engine per *way of making hot gas*, and Stu named four — cold gas,
 * bipropellant, hot-gas from a reactor, ionic. They are separate machines because they are separate
 * decisions for the player: what you feed them, what you build to feed them, and what you get back
 * are different in each case. **They are not separate physics.** Once there is a parcel in a store
 * and a direction to throw it, `Thruster.exhaustVelocity` prices it and `Work.fire` throws it, and
 * neither has any business knowing which machine filled the store.
 *
 * So this is deliberately the *narrowest* thing the firing code needs, and everything that makes an
 * engine what it is stays on the machine: a [Thruster] has a filter and nothing else, a [Rocket] has
 * two feeds, a ratio and a chamber. ⛔ **Nothing that only one kind has belongs here.** The moment
 * this interface grows a dial that half its implementors answer with a default, it has stopped
 * describing engines and started describing the union of the ones that happen to exist.
 *
 * ### ⚠️ What the flight controls see
 *
 * [flightActivations] weighs motors against each other by [Work.chamberPush], which reads
 * [propellantRole] — so an engine is entered into the ship's balance by *what it can actually throw
 * right now*. A rocket whose chamber has not filled yet reports nothing and the balance is struck
 * between the engines that can fire, which is the same answer an empty thruster gives and for the
 * same reason.
 */
sealed interface Engine : DirectedDeckMachine {

    /** Propellant thrown per tick at full activation, out of [propellantRole]. */
    val massPerTick: Long

    /** The fraction of a unit of propellant left over from last tick's throttling — see `throttled`. */
    val carry: Long

    /**
     * What it was actually told to do last tick, in permille — a readout, not a setting.
     *
     * ⚠️ **The panel cannot work this out for itself, and must not try.** On flight control a motor's
     * throttle depends on every other motor aboard, and a panel that recomputed the single-engine
     * answer would confidently report 100% at an engine running at 40.
     */
    val firing: Int

    /** Where this motor takes its orders from: the pilot, or the wire. See [ThrusterControl]. */
    val control: ThrusterControl

    /**
     * The store the exhaust is drawn from.
     *
     * ⛔ **This is the whole of what separates a cold-gas thruster from a rocket in the firing
     * code.** A thruster throws what a belt handed it, so its propellant store is the one its input
     * port fills; a rocket throws what its chamber has *become*, which is a different store with a
     * combustion between it and the doors. Everything downstream — the velocity, the impulse, the
     * ledger — is identical, because by then it is a parcel of matter at a temperature either way.
     */
    val propellantRole: BufferRole

    /** The way the ship is pushed: the other way from the way the exhaust goes. */
    val thrust: Direction get() = facing.opposite

    /**
     * The nozzle — one step [facing]-ward of the anchor.
     *
     * ⚠️ **A tile of the machine, for both kinds, and by two different routes.** A [Thruster] is a
     * 1×2 anchored at its tail, so this is its nose; a [Rocket] is 3×3 anchored at its middle, so
     * this is the middle of its front face. Neither is a coincidence worth relying on anywhere else:
     * what matters is that the exhaust starts *outside the tile the machine is fed at*, and both
     * shapes give that. See `exhaustPath`, which walks from here outwards.
     */
    fun bell(grid: Grid): TileIndex = grid.neighbour(center, facing)

    /**
     * The same engine having been told to fire at [activation], carrying [carry] into next tick.
     *
     * One call rather than two because the two always move together and a `copy` per kind at every
     * early return in `fire` is how one of them gets forgotten.
     */
    fun told(activation: Int, carry: Long): Engine

    fun withControl(control: ThrusterControl): Engine
}
