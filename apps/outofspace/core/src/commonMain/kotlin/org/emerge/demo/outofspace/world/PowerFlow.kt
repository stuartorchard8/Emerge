package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.electricalConductivityOf
import org.emerge.demo.outofspace.chem.apportion
import org.emerge.demo.outofspace.num.scaledRatio

/**
 * **Charge moving down a wire, and the heat it leaves behind.**
 *
 * Increment 1 of `PLAN_power_network.md`. [Conduit.Power] has existed as a layer since the conduits
 * were split — laid, saved, rendered, made of copper, and reading *"carries nothing yet"* in the
 * inspector. This is what it carries.
 *
 * ### The model, and why it is capacitive
 *
 * Every power segment holds a **charge**; its potential is that charge over a capacitance. Charge
 * moves between joined segments in proportion to their difference and to the conductance between
 * them, which is [seriesConductance] of what the two are made of — the same harmonic mean heat
 * already crosses a joint by, for the same reason: the worse side governs.
 *
 * Ohm's law falls out. So does a voltage divider, and so does the fact that a long run of the wrong
 * metal cannot deliver what a short run of copper can. None of that is written down.
 *
 * ⛔ **A wire is given far more capacitance than a wire has, and it is a stated fiction** — decision
 * 3 of the plan. A resistive network relaxed explicitly is stiff: with little stored charge the
 * relaxation overshoots and rings, and a finer timestep makes it worse rather than better. This is
 * the failure `Saturation.kt` documents at length, and the out is the same shape — change what is
 * being solved rather than how fast.
 *
 * ⭐ **[SETTLING_TICKS] is derived, not chosen.** An explicit relaxation is non-oscillatory when a
 * node sheds at most half its excess per step. A tile has at most four neighbours, so the per-edge
 * fraction must not exceed one eighth, and the fraction is `G / (SETTLING_TICKS × MAX_CONDUCTANCE)`
 * with `G` at most [MAX_CONDUCTANCE]. Eight is the smallest value that holds for **any** metal in
 * the table, including a silver bus, and it makes a step a half-relaxation rather than a full one.
 * ⚠️ What it costs in play is that a bus does not respond instantly — invisible at this scale, and
 * exactly the handle a capacitor machine would take hold of.
 *
 * ### ⚠️ Capacitance is geometric; conductance is material
 *
 * A tile of wire is a tile of wire, so every segment gets the same capacitance and the metal shows
 * up only in the conductance. That is both physically right and what keeps the arithmetic exact —
 * a per-material capacitance would put a division inside the potential and the ledger would stop
 * closing to the unit.
 */
object PowerFlow {

    /**
     * How many ticks a run takes to settle — the dial, and the only number here that is chosen.
     *
     * See the class doc for why eight rather than four: it is the smallest value that keeps a
     * four-neighbour node non-oscillatory for the most conductive metal in the table.
     */
    const val SETTLING_TICKS = 8L

    /**
     * **The most any species conducts** — derived from the table, so a new metal cannot silently
     * exceed the bound [SETTLING_TICKS] is chosen against.
     *
     * Silver today. ⚠️ If something more conductive is ever added this stays correct, because it is
     * a maximum over the table rather than silver's figure written down.
     */
    val MAX_CONDUCTANCE: Long = Species.ALL.maxOf { electricalConductivityOf(it) }

    /** The denominator every move is a fraction of. */
    private val DENOMINATOR: Long = SETTLING_TICKS * MAX_CONDUCTANCE

    /**
     * ⛔ **The most charge a tile may hold**, and it is an overflow bound rather than a physical one.
     *
     * Dissipation is the drop in `Σ q²/2`, so the network's charge has the range a `Long` leaves
     * after squaring. This is a bound on the **total across the network**, not on one tile: at 3e9
     * the stored energy is 4.5e18 against a ceiling of 9.2e18, a factor of two clear, and no
     * distribution of that charge over tiles can do worse than putting it all on one.
     *
     * ⚠️ **Nothing enforces this yet because nothing yet injects charge.** The rate a solar panel
     * pushes is what has to be sized against this, and that is increment 1b's constant to pick. It
     * is stated here, now, so that it is a number somebody chose rather than one a save discovers.
     * See `NUMERIC_LIMITS.md`.
     */
    const val MAX_CHARGE = 3_000_000_000L

    /**
     * **How much charge on one tile reads as one millivolt** — where the wire meets the chemistry.
     *
     * ⛔ **Chosen, and it is the only place the two scales touch.** Charge is its own quantity and
     * potential is charge over a capacitance that is one by construction, so this is really the
     * capacitance, wearing the units the electrochemistry already speaks. It is set so that **one
     * fully exposed solar panel brings a tile to roughly the potential water splits at** — 1230 mV
     * against a panel's four faces of [org.emerge.demo.outofspace.world.machine.SolarPanel.CHARGE_PER_FACE].
     *
     * ⚠️ So a single panel is *marginal* and a small bank is comfortable, which is the reading that
     * makes a threshold worth having: a vessel can be short of power without being dead.
     */
    const val CHARGE_PER_MILLIVOLT = 1_000L

    /** What the run at [tile] is sitting at, in millivolts against hull-ground. */
    fun millivoltsAt(charge: PowerCharge, tile: TileIndex): Int =
        (charge[tile] / CHARGE_PER_MILLIVOLT).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** What one tile of laid power conduit conducts, from the metal actually in it. */
    fun conductanceAt(power: List<Segment?>, metalAt: (TileIndex) -> Species?, tile: TileIndex): Long {
        val segment = power[tile.index] ?: return 0L
        val metal = metalAt(tile) ?: segment.material
        return electricalConductivityOf(metal)
    }

    /**
     * One tick of relaxation over the power layer.
     *
     * Charge is **conserved exactly** — every move subtracts from one tile precisely what it adds to
     * another — and the electrostatic energy the relaxation gives up is added to [heat], which is
     * the `I²R` a run warms by. ⚠️ Those two facts are the whole ledger and `PowerFlowTest` asserts
     * both directly rather than by sampling.
     *
     * ⚠️ **Decided from `before`, accounted on the live array** — the one-tick causality rule. Moves
     * read a snapshot so the answer cannot depend on which tile the sweep reached first, which is
     * what makes this Jacobi rather than Gauss-Seidel and what makes it deterministic.
     */
    fun relax(
        grid: Grid,
        power: List<Segment?>,
        metalAt: (TileIndex) -> Species?,
        charge: LongArray,
        heat: LongArray,
    ) {
        val before = charge.copyOf()
        val edgeLow = ArrayList<Int>()
        val edgeHigh = ArrayList<Int>()
        val shares = ArrayList<Long>()

        for (tile in grid.tiles) {
            val segment = power[tile.index] ?: continue
            if (segment.isIsolated) continue
            val here = conductanceAt(power, metalAt, tile)
            if (here <= 0L) continue

            for (dir in Direction.entries) {
                if (segment.links and (1 shl dir.ordinal) == 0) continue
                val next = grid.neighbour(tile, dir)
                // Each edge once: the low index owns it, so a link stated from both sides — which is
                // the only kind there is, links being symmetric — is not paid for twice.
                if (next == TileIndex.NONE || next.index <= tile.index) continue
                if (power[next.index] == null) continue
                val there = conductanceAt(power, metalAt, next)
                if (there <= 0L) continue

                val gap = before[tile.index] - before[next.index]
                if (gap == 0L) continue
                val moved = scaledRatio(seriesConductance(here, there), DENOMINATOR, gap)
                if (moved == 0L) continue

                charge[tile.index] -= moved
                charge[next.index] += moved

                // ⚠️ **A weight, not the answer.** `m × (Δq − m)` is what this edge would dissipate
                // if it were the only one running, and summing those across edges is *wrong* — see
                // [dissipate]. It is kept as the share each edge takes of the real total, which is
                // exactly what `I²R` says: the edge carrying the most current gets the most heat.
                edgeLow.add(tile.index)
                edgeHigh.add(next.index)
                shares.add(moved * (gap - moved))
            }
        }
        dissipate(before, charge, edgeLow, edgeHigh, shares, heat)
    }

    /**
     * Bank the energy the field actually gave up, split across the edges that carried the current.
     *
     * ⛔ **Why the per-edge figures cannot simply be added up, measured 2026-09-06.** Moving `m`
     * across one edge costs `m × (Δq − m)`, and that is exact *for one move*. Every edge here moves
     * simultaneously from the same snapshot, so a tile shedding to two neighbours at once has a
     * cross term between them that no per-edge formula sees. Summed, they came to **5.2% less** than
     * the field really lost, and a wire that dissipates 95% of what it takes is a wire that quietly
     * mints energy for ever.
     *
     * So the truth is the drop in `Σ q²/2` across the whole pass, and the per-edge figures are
     * demoted to weights. [apportion] telescopes them, so the shares sum back to the total to the
     * unit — the same guarantee every mass split in this game already has.
     */
    private fun dissipate(
        before: LongArray,
        after: LongArray,
        edgeLow: List<Int>,
        edgeHigh: List<Int>,
        weights: List<Long>,
        heat: LongArray,
    ) {
        if (weights.isEmpty()) return
        var drop = 0L
        for (i in before.indices) {
            if (before[i] != after[i]) drop += storedEnergy(before[i]) - storedEnergy(after[i])
        }
        if (drop <= 0L) return

        val apportioned = apportion(LongArray(weights.size) { weights[it] }, drop)
        for (e in apportioned.indices) {
            val half = apportioned[e] / 2L
            heat[edgeLow[e]] += half
            heat[edgeHigh[e]] += apportioned[e] - half
        }
    }

    /**
     * The electrostatic energy a tile holding [q] stores — `q²/2`, capacitance being one by
     * construction (see the class doc).
     *
     * ⚠️ Public because the ledger is only checkable if a test can state the same quantity the pass
     * does. Two floorings of `q²/2` that disagree would look exactly like a leak.
     */
    fun storedEnergy(q: Long): Long = q * q / 2L
}

/**
 * **What every tile of power conduit is holding**, and the one piece of state the network has.
 *
 * A class rather than a bare `LongArray` on [VesselState] for the reason every other layer is one:
 * an array compares by identity, and a state that compares by identity makes two worlds that are
 * the same read as different — which the save round-trip tests would report as corruption.
 */
class PowerCharge private constructor(internal val q: LongArray) {

    operator fun get(tile: TileIndex): Long = q[tile.index]

    val total: Long get() { var sum = 0L; for (v in q) sum += v; return sum }

    val size: Int get() = q.size

    fun copyOf(): PowerCharge = PowerCharge(q.copyOf())

    /** A copy for the save layer, which writes it sparsely exactly as the heat fields are written. */
    fun toLongArray(): LongArray = q.copyOf()

    /** This, with [amount] added at [tile] — the only way charge enters the world. */
    fun plus(tile: TileIndex, amount: Long): PowerCharge =
        PowerCharge(q.copyOf().also { it[tile.index] += amount })

    override fun equals(other: Any?): Boolean =
        this === other || (other is PowerCharge && q.contentEquals(other.q))

    override fun hashCode(): Int = q.contentHashCode()

    companion object {
        fun empty(tiles: Int): PowerCharge = PowerCharge(LongArray(tiles))

        fun of(q: LongArray): PowerCharge = PowerCharge(q.copyOf())
    }
}
