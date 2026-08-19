package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.TILE_LITRES
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * A rocket motor on one tile: propellant in at the back, exhaust out the front, and the ship goes
 * the other way.
 *
 * The first engine in the game that is a *thing you build* rather than a hole in the hull or a key
 * held down. [org.emerge.demo.outofspace.Edit.Thrust] mints momentum and books the fact; a breach
 * makes real thrust but only in the direction the wall happens to face. This makes thrust the same
 * way a real rocket does — throw mass astern, fast — and pays for it in propellant.
 *
 * ### Where the thrust comes from, and where it does not
 *
 * Firing throws [Capacity.PACKET_MASS]-scale propellant out of the exit face at
 * [EXHAUST_METRES_PER_SECOND]. The momentum that leaves is booked to `exhaustMomentum` and the
 * negative of it goes to the ship, so this adds **no new term** to the momentum ledger: it is the
 * same `+p` overboard / `−p` aboard pair that a venting breach already is, and
 * `vesselImpulse + momentum + pipeMomentum + exhaust + undelivered + body − debug == 0` still holds
 * on every tick of a burn.
 *
 * ⚠️ **A blocked motor produces no direct impulse at all**, and that is the physics and not a
 * simplification: exhaust that hits your own ship pushes your own ship, so the two halves cancel
 * exactly. What is left is a tile full of very hot, very fast gas, which pushes on the hull around
 * it through [org.emerge.demo.outofspace.world.applyPressureForce] like any other pressure — so a badly placed thruster is not
 * *nothing*, it is a wildly inefficient one. See [exhaustPath] for how "blocked" is decided.
 *
 * A motor bolted face-first against a wall is that case taken to its limit rather than a fourth
 * case needing a rule of its own: the machine is [DeckMachineKind.isPermeable], so its own tile holds
 * gas, and with nowhere further to send the exhaust it sends it there. It runs, it produces no
 * thrust, and it cooks itself — which is a legible thing to build by mistake and to have to fix.
 */
data class Thruster(
    override val center: TileIndex,
    override val facing: Direction,
    /** Propellant waiting to be thrown. Solid, arriving by rail, exactly as a smelter's feed does. */
    val carry: Long = 0L,
    /**
     * Propellant thrown per tick at full activation.
     *
     * A tenth of a belt-load rather than the whole one every other machine takes: a thruster is not
     * a producer feeding a belt but a consumer *of* one, and at a full packet a tick a single motor
     * would drink a dedicated supply line dry. A tenth is a rate one line can sustain to several
     * engines, which is the arrangement the machine is for.
     */
    val massPerTick: Long = Capacity.PACKET_MASS / 30L,
    override val wiring: Wiring = Wiring.RUNNING,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Thruster
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    companion object {

        /**
         * **The dial.** How fast exhaust leaves the nozzle, in metres per second.
         *
         * 3 km/s is a chemical rocket — a little better than hydrolox, a little worse than the best
         * thing anybody has flown. It is stated in metres per second and not in the grid's own units
         * because that is the number an engineer would quote and the number worth arguing about;
         * [tilesPerTick] turns it into the units the ledger counts in, and doing that conversion in
         * one place is what stops "the engine got weaker" the next time the tick rate moves.
         *
         * Everything about the machine scales off this: thrust is linear in it and the heat a
         * blocked motor dumps is **quadratic**, so doubling it doubles the push and quadruples the
         * damage done by pointing it at your own wall.
         */
        const val EXHAUST_METRES_PER_SECOND: Long = 3000L

        /**
         * How wide a tile is, in millimetres.
         *
         * The cube root of [TILE_LITRES] — 830 litres is a cube 0.9404 m on a side — restated as an
         * integer because Kotlin has no compile-time cube root. It exists only to convert a real
         * velocity into tiles per tick; nothing else in the game needs a tile to have a length.
         */
        const val TILE_MILLIMETRES: Long = 940L

        /**
         * The exhaust velocity in the unit momentum is counted in: tiles per tick.
         *
         * `m/s ÷ (metres per tile × ticks per second)`. At 3 km/s, a 0.94 m tile and four ticks a
         * second this is about 800 tiles a tick — an absurd-looking number that is simply what a
         * rocket is next to a room, and never a velocity anything is integrated at. It is a
         * multiplier on a mass to get an impulse and nothing moves at it.
         */
        fun tilesPerTick(ticksPerSecond: Int): Long =
            EXHAUST_METRES_PER_SECOND * 1_000L / (TILE_MILLIMETRES * ticksPerSecond)

        /**
         * The kinetic energy carried by [mass] of exhaust: `½mv²`, in the game's energy unit.
         *
         * Worked in SI and converted at the end, because `v²` in tiles-per-tick would need the
         * conversion applied twice and the second one is exactly the sort of thing that goes
         * missing. Through [scaledRatio] so the mass-over-a-kilogram ratio is taken before the
         * scale is applied rather than after: `mass × v²` for a kilogram of propellant is 9e15
         * before any unit conversion, and the headroom above that is not worth relying on.
         */
        fun kineticEnergy(mass: Long): Long = scaledRatio(
            mass,
            Budget.KILOGRAM,
            Budget.JOULE * EXHAUST_METRES_PER_SECOND * EXHAUST_METRES_PER_SECOND / 2L,
        )
    }
}

/**
 * Where a thruster's exhaust ends up: straight out of the exit face until something stops it.
 *
 * [blocker] is the first impermeable tile in the way, or **−1** if the exhaust reaches the rim and
 * leaves the world. [destination] is the last tile the exhaust can actually get to: the permeable
 * tile immediately before the blocker, or — for a motor bolted face-first against a wall, with no
 * such tile — **the thruster's own**, which it can hold because a thruster is permeable.
 *
 * [path] is every permeable tile the jet crosses, exit face first, **including** [destination]. A
 * jet at 3 km/s does not politely thread between the gas already in the corridor: it takes it with
 * it. So the whole path is scooped and delivered wherever the propellant goes — overboard, or into
 * the destination tile — which is why the path is returned rather than just its two endpoints.
 *
 * ### The two cases, and why "blocked" is not the same as "off"
 *
 * - **Clear** (`blocker < 0`): everything leaves the grid, and the ship is pushed.
 * - **Blocked** (`blocker ≥ 0`): the exhaust is dumped into [destination] and the ship is pushed by
 *   *nothing*. It still gets pushed, indirectly, because a tile holding a jet's worth of hot gas
 *   leans on every wall around it.
 *
 * There is deliberately no third case for "nowhere to put it". A thruster's own tile is always
 * somewhere, so the walk always has an answer and no caller needs a branch for the absence of one.
 *
 * ⚠️ Derived every tick from [org.emerge.demo.outofspace.world.StructureMap], not cached on the machine and refreshed on edit. One
 * ray per thruster over a map that is itself rebuilt every tick is nothing, and it means there is no
 * invalidation to forget — the same trade [org.emerge.demo.outofspace.world.StructureMap] and [org.emerge.demo.outofspace.world.Occupancy] already made, and for the
 * same reason: a cache here would be wrong for exactly one tick after a wall moved, which is the
 * tick a player is watching.
 */
class ExhaustPath(val path: Array<TileIndex>, val blocker: TileIndex, val destination: TileIndex) {

    /** True when the exhaust leaves the world — the only case that pushes the ship directly. */
    val isClear: Boolean get() = blocker == TileIndex.NONE
}

/** The exhaust path of the thruster stored at [tile] — see [ExhaustPath]. */
fun exhaustPath(grid: Grid, structure: StructureMap, tile: TileIndex, facing: Direction): ExhaustPath {
    val crossed = ArrayList<TileIndex>(grid.width + grid.height)
    var at = tile
    while (true) {
        val next = grid.neighbour(at, facing)
        // Off the edge of the world: nothing ever stopped it.
        if (next == TileIndex.NONE) return ExhaustPath(crossed.toTypedArray(), blocker = TileIndex.NONE, destination = crossed.lastOrNull() ?: tile)
        if (structure.isImpermeable(next)) {
            // Nothing crossed means the wall is against the nozzle, and the exhaust stays home.
            return ExhaustPath(crossed.toTypedArray(), blocker = next, destination = crossed.lastOrNull() ?: tile)
        }
        crossed.add(next)
        at = next
    }
}
