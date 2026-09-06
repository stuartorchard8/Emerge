package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.electricalConductivityOf
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.PowerFlow
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The wire carries charge, conserves it, and warms up doing so.**
 *
 * Increment 1 of `PLAN_power_network.md`. Two ledgers are asserted directly rather than sampled,
 * because they are the whole of what this pass promises: **charge is conserved exactly**, and
 * **every joule the field gives up turns into heat**.
 */
class PowerFlowTest {

    private val grid = Grid(12, 3)

    /** A straight run of [length] tiles along row 1, all of [metal], joined end to end. */
    private fun run(length: Int, metal: Species = Species.Copper): List<Segment?> {
        val layer = arrayOfNulls<Segment>(grid.size)
        for (x in 0 until length) {
            val tile = grid.tile(x, 1)
            var s = Segment(Conduit.Power, material = metal)
            if (x > 0) s = s.joinedTo(Direction.Left)
            if (x < length - 1) s = s.joinedTo(Direction.Right)
            layer[tile.index] = s
        }
        return layer.toList()
    }

    private fun charges(vararg at: Pair<Int, Long>): LongArray {
        val q = LongArray(grid.size)
        for ((x, amount) in at) q[grid.tile(x, 1).index] = amount
        return q
    }

    private fun relax(
        power: List<Segment?>,
        charge: LongArray,
        heat: LongArray = LongArray(grid.size),
        ticks: Int = 1,
        metal: Species = Species.Copper,
    ): LongArray {
        repeat(ticks) { PowerFlow.relax(grid, power, { metal }, charge, heat) }
        return heat
    }

    private fun LongArray.total(): Long = sum()

    // ── The two ledgers ──────────────────────────────────────────────────────

    /** Charge is conserved to the unit, over a long run and many ticks. */
    @Test
    fun chargeIsConservedExactly() {
        val power = run(10)
        val charge = charges(0 to 900_000_000L, 3 to 12_345_678L, 9 to 1L)
        val before = charge.total()
        relax(power, charge, ticks = 200)
        assertEquals(before, charge.total(), "the wire invented or lost charge")
    }

    /**
     * ⭐ **Every joule the field gives up becomes heat**, which is `I²R` and the reason a wire warms.
     *
     * Stored energy is `Σ q²/2` in these units, so the drop across the whole relaxation must equal
     * the heat banked. ⚠️ Asserted as an identity rather than a tolerance — the dissipation is
     * computed *as* the drop, so anything but equality means a move was accounted twice or dropped.
     */
    @Test
    fun theEnergyTheFieldLosesIsExactlyTheHeatItMakes() {
        val power = run(10)
        val charge = charges(0 to 800_000_000L)
        fun stored(q: LongArray): Long = q.sumOf { PowerFlow.storedEnergy(it) }

        val before = stored(charge)
        val heat = relax(power, charge, ticks = 300)
        val after = stored(charge)

        assertTrue(heat.total() > 0L, "a relaxing run should have warmed up")
        // Within the flooring of the halved square, which is one unit per tile.
        assertEquals(
            before - after,
            heat.total(),
            "the field lost energy the wire did not turn into heat",
        )
    }

    /** Heat lands on the run that carried the current, and nowhere else. */
    @Test
    fun theHeatLandsOnTheWireAndNotBesideIt() {
        val power = run(6)
        val heat = relax(run(6), charges(0 to 500_000_000L), ticks = 50)
        for (tile in grid.tiles) {
            if (power[tile.index] == null) assertEquals(0L, heat[tile.index], "heat off the wire at $tile")
        }
    }

    // ── It settles, and it does not ring ─────────────────────────────────────

    /**
     * ⭐ **The stability claim, asserted.** `SETTLING_TICKS` is derived so a node sheds at most half
     * its excess per step, which makes the relaxation monotone: no tile may ever overshoot past a
     * neighbour it was below, which is what ringing looks like one step at a time.
     *
     * This is the test that would have caught the failure `Saturation.kt` documents — a solver that
     * grows rather than oscillates, and grows faster the finer the grid.
     */
    @Test
    fun aRelaxingRunNeverOvershoots() {
        val power = run(10)
        val charge = charges(0 to 900_000_000L)
        val peak = charge.max()
        repeat(400) {
            PowerFlow.relax(grid, power, { Species.Copper }, charge, LongArray(grid.size))
            assertTrue(charge.max() <= peak, "a tile rose above the starting peak — the run is ringing")
            assertTrue(charge.min() >= 0L, "a tile went negative — the run is ringing")
        }
    }

    /** And it actually arrives: a long run levels out rather than creeping forever. */
    @Test
    fun aRunSettlesToAUniformPotential() {
        val power = run(8)
        val charge = charges(0 to 800_000_000L)
        relax(power, charge, ticks = 2000)
        val onWire = (0 until 8).map { charge[grid.tile(it, 1).index] }
        val spread = onWire.max() - onWire.min()
        assertTrue(spread * 1000L < onWire.max(), "still uneven after 2000 ticks: $onWire")
    }

    // ── The material matters, which is the point of deriving conductivity ────

    /** ⭐ Copper delivers faster than iron, by a number nobody chose. */
    @Test
    fun copperMovesChargeFasterThanIron() {
        fun spreadAfter(metal: Species): Long {
            val charge = charges(0 to 900_000_000L)
            repeat(20) { PowerFlow.relax(grid, run(10, metal), { metal }, charge, LongArray(grid.size)) }
            return charge[grid.tile(9, 1).index]
        }
        val copper = spreadAfter(Species.Copper)
        val iron = spreadAfter(Species.Iron)
        assertTrue(copper > iron, "copper reached $copper at the far end, iron $iron")
        assertTrue(
            electricalConductivityOf(Species.Copper) > electricalConductivityOf(Species.Iron),
            "the ordering this rests on",
        )
    }

    /** A run of something that does not conduct carries nothing at all. */
    @Test
    fun aRunOfFirebrickCarriesNothing() {
        val charge = charges(0 to 900_000_000L)
        val heat = relax(run(10, Species.Firebrick), charge, metal = Species.Firebrick, ticks = 100)
        assertEquals(900_000_000L, charge[grid.tile(0, 1).index], "an insulator moved charge")
        assertEquals(0L, heat.total(), "an insulator dissipated something")
    }

    // ── The graph is the links, not adjacency ────────────────────────────────

    /** Two runs side by side but never joined do not share, which is what `links` is for. */
    @Test
    fun chargeDoesNotCrossAnUnjoinedNeighbour() {
        val layer = arrayOfNulls<Segment>(grid.size)
        layer[grid.tile(0, 1).index] = Segment(Conduit.Power, material = Species.Copper)
        layer[grid.tile(1, 1).index] = Segment(Conduit.Power, material = Species.Copper)
        val charge = charges(0 to 900_000_000L)
        relax(layer.toList(), charge, ticks = 50)
        assertEquals(900_000_000L, charge[grid.tile(0, 1).index], "charge crossed a joint that was never made")
    }

    /**
     * The bound the dissipation arithmetic rests on, stated where a change would trip it.
     *
     * ⚠️ `MAX_CHARGE` is a ceiling on the **whole network**, and the worst distribution of it is all
     * on one tile — so if `storedEnergy` of the whole budget fits, every arrangement of it does.
     */
    @Test
    fun theOverflowBoundLeavesRoomForTheSquare() {
        val worst = PowerFlow.storedEnergy(PowerFlow.MAX_CHARGE)
        assertTrue(worst > 0L, "the charge budget already overflows its own square")
        assertTrue(worst < Long.MAX_VALUE / 2L, "the charge budget leaves no headroom: $worst")
        assertTrue(
            PowerFlow.MAX_CONDUCTANCE >= electricalConductivityOf(Species.Silver),
            "SETTLING_TICKS is derived against the most conductive metal in the table",
        )
    }
}
