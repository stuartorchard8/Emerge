package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.TILE_LITRES
import org.emerge.demo.outofspace.chem.adiabaticK
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.isqrt
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.kelvinOf
import org.emerge.demo.outofspace.world.millimolesOf
import org.emerge.demo.outofspace.world.thermalMassOf
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * A rocket motor two tiles long: propellant in at the chamber, exhaust out of the bell in front of
 * it, and the ship goes the other way.
 *
 * ### The two tiles
 *
 * [center] is the **chamber** — where the machine is stored, where a rail is threaded under it, and
 * where its one store sits. [bell] is the tile immediately [facing]-ward of that, and it is part of
 * the machine: it is claimed, it is made of metal, it weighs, and nothing else may be built there.
 * That makes the thruster the one kind whose anchor is not the middle of its own footprint — see
 * [org.emerge.demo.outofspace.world.FootprintShape.Nose], which holds the argument.
 *
 * Everything about where the exhaust *goes* is measured from the bell and not from the chamber:
 * [exhaustPath] starts its walk there, the impulse is booked there, and the lever arm the flight
 * controls give this motor is the bell's. A motor is a thing that pushes at its nozzle.
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
 * `vesselImpulse + exhaust + bodies + vented − debug == 0` still holds
 * on every tick of a burn.
 *
 * ⚠️ **A blocked motor produces no direct impulse at all**, and that is the physics and not a
 * simplification: exhaust that hits your own ship pushes your own ship, so the two halves cancel
 * exactly. What is left is a tile full of very hot, very fast gas, which pushes on the hull around
 * it through the pressure field like any other pressure — so a badly placed thruster is not
 * *nothing*, it is a wildly inefficient one. See [exhaustPath] for how "blocked" is decided.
 *
 * A motor bolted bell-first against a wall is that case taken to its limit rather than a fourth
 * case needing a rule of its own: the machine does not [DeckMachineKind.preventAirflow], so the bell
 * tile holds gas, and with nowhere further to send the exhaust it sends it there. It runs, it
 * produces no thrust, and it cooks itself — which is a legible thing to build by mistake and to have
 * to fix.
 */
data class Thruster(
    override val center: TileIndex,
    override val facing: Direction,
    /** Propellant waiting to be thrown. Solid, arriving by rail, exactly as a smelter's feed does. */
    val carry: Long = 0L,
    /**
     * Propellant thrown per tick at full activation.
     */
    val massPerTick: Long = Capacity.PACKET_MASS / 200L,
    /**
     * Where this motor takes its orders from. Flight controls by default — see [ThrusterControl].
     */
    val control: ThrusterControl = ThrusterControl.Flight,
    /**
     * What it was actually told to do last tick, in permille — a readout, not a setting.
     *
     * ⚠️ **The panel cannot work this out for itself, and must not try.** On flight control a motor's
     * throttle depends on every other motor aboard: the whole-ship balance in
     * [org.emerge.demo.outofspace.world.flightActivations] may have throttled this one back to keep a
     * burn straight, and a panel that recomputed the single-engine answer would confidently report
     * 100% at an engine running at 40. Recorded where it was decided, and read from there — the same
     * trade [Gauge.lastMass] makes.
     */
    val firing: Int = 0,
    override val wiring: Wiring = Wiring.RUNNING,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Thruster
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    fun withControl(control: ThrusterControl): Thruster = copy(control = control)

    /** The way the ship is pushed: the other way from the way the exhaust goes. */
    val thrust: Direction get() = facing.opposite

    /**
     * The nozzle: the second tile of the footprint, one step [facing]-ward of the chamber.
     *
     * Derived rather than stored, for the reason the footprint is — a tile index means a different
     * place on a different lattice. A standing thruster always has one, because a footprint that did
     * not fit was refused at placement.
     */
    fun bell(grid: Grid): TileIndex = grid.neighbour(center, facing)

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
         * **R × 1000, in the units this file's arithmetic wants.**
         *
         * The molar gas constant is 8.314 J/(mol·K), and [org.emerge.demo.outofspace.chem.Species.molarMass]
         * is in **grams** per mole — so `R/M` in SI needs a factor of a thousand to turn kilograms
         * into grams, and folding it into the constant is what leaves [exhaustVelocity] with no
         * conversion of its own to get wrong.
         *
         * ⚠️ **Not mass-dimensioned, and so it does not move with [Budget].** It is per *mole*, and
         * a mole is a count of particles — the same warning [Pump.MILLIMOLES_PER_TICK] carries. The
         * mass unit enters [exhaustVelocity] once, explicitly, where the millimoles are weighed
         * against the grams.
         */
        private const val GAS_CONSTANT_MILLI: Long = 8314L

        /**
         * **How fast this propellant leaves a nozzle**, in metres per second, into vacuum.
         *
         * `v_e = √( 2γ/(γ−1) · R·T/M )` — the ideal rocket with its expansion term at the limit,
         * which is every nozzle in the game until something flies where there is a back-pressure to
         * expand against (`PLAN_fluid_thrusters.md` §7.2). Hot chamber, light molecule, and nothing
         * else: **T over M is the whole mechanic.**
         *
         * ### Why this is exact integer arithmetic and not a fixed-point approximation
         *
         * Three numbers had to line up and did. `2γ/(γ−1)` is a whole number for each of the three
         * molecular shapes — see [org.emerge.demo.outofspace.chem.adiabaticK]. `Species.molarMass` is
         * already in grams per mole, the unit `R × 1000` wants. And a mixture's `K` is the
         * **mole-weighted mean** of its species', exactly rather than approximately, because
         * `K = 2·Cp/R` and a molar heat capacity is additive over moles. So there is no scale to
         * calibrate, no `Frac`, and no averaging of γ anywhere.
         *
         * The mole count cancels out of the ratio, which is what keeps the expression short:
         * `Σ(n_s·K_s) / n × R·T·1000 / (1000·mass/n)` loses its `n` and leaves the divide below.
         *
         * ⛔ **Every species present counts, including one that has no business being a gas.** A
         * chamber with rock in it throws the rock, slowly — forsterite is 140 g/mol against
         * hydrogen's 2 — and that is the correct penalty rather than an unhandled case. It is also
         * the honest reading of what the game did for its whole life before this: a solid-fed motor
         * was getting hydrogen's exhaust velocity out of gravel.
         *
         * Returns **0** for an empty parcel, which is the one case with no answer: no propellant is
         * not slow propellant, and a caller multiplying a zero mass by a velocity wants a zero.
         */
        fun exhaustVelocity(propellant: Mixture): Long {
            val mass = propellant.total
            if (mass <= 0L) return 0L

            // Σ n_s·K_s over the parcel, in millimoles — the numerator of the mole-weighted K, kept
            // un-divided so the mole count can cancel below instead of being rounded away here.
            var weightedK = 0L
            for (s in Species.ALL) {
                val held = propellant[s]
                if (held == 0L) continue
                weightedK += millimolesOf(held, s) * s.adiabaticK
            }
            if (weightedK <= 0L) return 0L

            // ⛔ Through `thermalMassOf`, never `heatCapacityOf` — the divided capacity has already
            // thrown away everything under CAPACITY_DIVISOR, which for a parcel of hydrogen is most
            // of it, and a temperature formed off it reads ambient. See `kelvinOf`.
            val kelvin = kelvinOf(propellant.energy, thermalMassOf(propellant))
            if (kelvin <= 0) return 0L

            // v² = Σ(n_s·K_s) · R·1000 · T · GRAM / (1000 · mass). Through `scaledRatio` so the
            // ratio is taken before the scale: the numerator alone is ~1e22 for a chamber-sized
            // parcel, and `Long` stops at 9.2e18.
            val squared = scaledRatio(
                weightedK,
                1000L * mass,
                GAS_CONSTANT_MILLI * kelvin * Budget.GRAM,
            )
            return isqrt(squared)
        }

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
 * Where a thruster's exhaust ends up: straight out of the bell until something stops it.
 *
 * [blocker] is the first impermeable tile in the way, or **−1** if the exhaust reaches the rim and
 * leaves the world. [destination] is the last tile the exhaust can actually get to: the permeable
 * tile immediately before the blocker, or — for a motor bolted bell-first against a wall, with no
 * such tile — **the bell itself**, which it can hold because a thruster is permeable.
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

/**
 * The exhaust path of [m] — see [ExhaustPath].
 *
 * ⚠️ **It takes the machine and not a tile, and the ray below is private for that reason.** The
 * walk starts at the [Thruster.bell], never at the chamber, and a caller that had to remember which
 * of a motor's two tiles to hand over would eventually hand over the wrong one — silently, since the
 * chamber is a perfectly plausible place to start a ray from and the answer it gives is merely one
 * tile too long.
 */
fun exhaustPath(grid: Grid, structure: StructureMap, m: Thruster): ExhaustPath =
    exhaustPath(grid, structure, m.bell(grid), m.facing)

/**
 * The same walk stated as a ray: out of [bell] along [facing] until something stops it.
 *
 * The gas in [bell] itself is **not** on the path. It is the machine's own tile, and what is in a
 * bell when it lights is what the chamber is about to throw out anyway.
 */
private fun exhaustPath(grid: Grid, structure: StructureMap, bell: TileIndex, facing: Direction): ExhaustPath {
    val crossed = ArrayList<TileIndex>(grid.width + grid.height)
    var at = bell
    while (true) {
        val next = grid.neighbour(at, facing)
        // Off the edge of the world: nothing ever stopped it.
        if (next == TileIndex.NONE) return ExhaustPath(crossed.toTypedArray(), blocker = TileIndex.NONE, destination = crossed.lastOrNull() ?: bell)
        if (structure.blocksAir(next)) {
            // Nothing crossed means the wall is against the nozzle, and the exhaust stays home.
            return ExhaustPath(crossed.toTypedArray(), blocker = next, destination = crossed.lastOrNull() ?: bell)
        }
        crossed.add(next)
        at = next
    }
}

/**
 * What a motor listens to: the pilot, or the wire.
 *
 * [Flight] is the default and is what a thruster is *for*. A newly built engine flies the ship the
 * moment it is fed, without a wire, a button or a single decision from the player about which key
 * ought to mean what — it works out from where it sits and which way it points whether firing would
 * help ([org.emerge.demo.outofspace.world.thrusterActivation]). Bolt four on in four directions and
 * WSAD/QE fly the vessel; bolt one on crooked and it still contributes whatever component of the
 * push and the turn it honestly can.
 *
 * [Wire] hands the same motor back to the signal network, where its [Wiring] drives it like any
 * other machine's. That is not a lesser mode — a thruster held open by a pressure sensor is a
 * perfectly good machine — it is simply no longer a *control surface*, and nothing about the
 * pilot's stick reaches it.
 *
 * ⚠️ **The default changed.** Thrusters were [Wire]-driven (`ALWAYS`, i.e. permanently on) for the
 * whole of the machine's life before this. Every saved vessel's engines become [Flight] on load,
 * which is the intended migration and not an oversight: an always-on engine is a design nobody was
 * choosing, they were accepting it.
 */
enum class ThrusterControl(val label: String) {
    Flight("FLIGHT"),
    Wire("WIRE"),
    ;

    val next: ThrusterControl get() = if (this == Flight) Wire else Flight

    companion object {
        val ALL: List<ThrusterControl> = entries.toList()
    }
}
