package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Structure
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The atmosphere: per-tile gas, pressure-driven flow, and gravity stratification.
 *
 * Its invariant is the third of the set — `aboard + vented == baseline`, every tick. Solids, energy
 * and gas each get their own ledger because they never interconvert; folding them together would
 * make one leak able to hide inside another's noise.
 */
class AtmosphereTest {

    private fun cfgFor(grid: Grid) = OutofspaceConfig(grid = grid)

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val cfg = cfgFor(state.grid)
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private fun assertAirBalanced(s: VesselState, what: String) {
        assertEquals(
            s.baselineAirGrams,
            s.atmosphereGrams + s.airVentedGrams,
            "$what: aboard ${s.atmosphereGrams} + vented ${s.airVentedGrams}",
        )
    }

    /** A hull box [w] x [h] with a ring of space around it. */
    private fun sealedRoom(w: Int, h: Int): VesselState {
        val grid = Grid(w + 2, h + 2)
        val machines = arrayOfNulls<Machine>(grid.size)
        for (x in 1..w) {
            machines[grid.index(x, 1)] = Hull()
            machines[grid.index(x, h)] = Hull()
        }
        for (y in 1..h) {
            machines[grid.index(1, y)] = Hull()
            machines[grid.index(w, y)] = Hull()
        }
        return VesselState(grid, machines.toList())
    }

    // ── Conservation ──────────────────────────────────────────────────────────

    @Test
    fun `air is conserved on every tick of a working vessel`() {
        var s = starterVessel(Grid(40, 28))
        val cfg = cfgFor(s.grid)
        assertTrue(s.atmosphereGrams > 0L, "a sealed vessel starts with air in it")
        repeat(160) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (it % 79 == 0) assertAirBalanced(s, "tick ${s.tick}")
        }
        assertAirBalanced(s, "final")
        assertEquals(0L, s.airVentedGrams, "an intact hull loses nothing")
    }

    @Test
    fun `hull separates two rooms so their pressures do not equalise`() {
        // Two 3-wide rooms sharing a wall, one at double pressure.
        val grid = Grid(9, 5)
        val machines = arrayOfNulls<Machine>(grid.size)
        for (x in 1..7) { machines[grid.index(x, 1)] = Hull(); machines[grid.index(x, 3)] = Hull() }
        for (y in 1..3) { machines[grid.index(1, y)] = Hull(); machines[grid.index(7, y)] = Hull() }
        machines[grid.index(4, 2)] = Hull()   // the dividing wall
        var s = VesselState(grid, machines.toList())

        val grams = LongArray(grid.size * Species.COUNT)
        for (x in 2..3) grams[grid.index(x, 2) * Species.COUNT + Species.Oxygen.ordinal] = 2_000L
        for (x in 5..6) grams[grid.index(x, 2) * Species.COUNT + Species.Oxygen.ordinal] = 500L
        val field = AirField.of(grams)
        s = s.copy(air = field, baselineAirGrams = field.totalGrams)

        s = run(s, 40)
        assertEquals(2_000L, s.air.pressureAt(grid.index(2, 2)), "the high side stayed high")
        assertEquals(500L, s.air.pressureAt(grid.index(6, 2)), "and the low side stayed low")
        assertAirBalanced(s, "divided rooms")
    }

    // ── Flow ──────────────────────────────────────────────────────────────────

    @Test
    fun `pressure equalises across a connected room`() {
        val room = sealedRoom(8, 4)
        val g = room.grid
        val grams = LongArray(g.size * Species.COUNT)
        grams[g.index(2, 2) * Species.COUNT + Species.Oxygen.ordinal] = 6_000L
        val field = AirField.of(grams)
        var s = room.copy(air = field, baselineAirGrams = field.totalGrams)

        s = run(s, 80)
        // Adjacent tiles are the precise statement: integer pressure settles into a +/-1 staircase,
        // because a difference of one cannot be halved without overshooting. Asserting a flat field
        // across the whole room would be asserting something that cannot be true.
        for (tile in 0 until g.size) {
            if (!s.structure.isContained(tile)) continue
            for (dir in org.emerge.demo.outofspace.world.Direction.ALL) {
                val other = g.neighbour(tile, dir)
                if (other < 0 || !s.structure.isContained(other) || s.structure.isImpermeable(other)) continue
                val gap = s.air.pressureAt(tile) - s.air.pressureAt(other)
                assertTrue(gap in -1L..1L, "neighbours should be level: $tile vs $other differ by $gap")
            }
        }
        assertAirBalanced(s, "after equalising")
    }

    @Test
    fun `flow never overshoots into an oscillation`() {
        val room = sealedRoom(6, 3)
        val g = room.grid
        val grams = LongArray(g.size * Species.COUNT)
        grams[g.index(2, 2) * Species.COUNT + Species.Oxygen.ordinal] = 10_000L
        val field = AirField.of(grams)
        var s = room.copy(air = field, baselineAirGrams = field.totalGrams)

        var previousPeak = Long.MAX_VALUE
        repeat(300) {
            s = OutofspaceReducer.reduce(cfgFor(g), s, emptyMap())
            val peak = (0 until g.size).maxOf { s.air.pressureAt(it) }
            assertTrue(peak <= previousPeak, "the densest tile got denser with no source: $peak > $previousPeak")
            previousPeak = peak
        }
        assertAirBalanced(s, "after settling")
    }

    @Test
    fun `a draught carries the room's mix rather than skimming one gas`() {
        val room = sealedRoom(6, 3)
        val g = room.grid
        val grams = LongArray(g.size * Species.COUNT)
        val source = g.index(2, 2) * Species.COUNT
        grams[source + Species.Oxygen.ordinal] = 2_000L
        grams[source + Species.Nitrogen.ordinal] = 6_000L
        val field = AirField.of(grams)
        var s = room.copy(air = field, baselineAirGrams = field.totalGrams)

        s = run(s, 30)   // part-way through equalising
        val neighbour = g.index(3, 2)
        val o2 = s.air.gramsOf(neighbour, Species.Oxygen)
        val n2 = s.air.gramsOf(neighbour, Species.Nitrogen)
        assertTrue(o2 > 0L && n2 > 0L, "both gases should have moved: O2=$o2 N2=$n2")
        assertTrue(n2 > o2 * 2, "and roughly in the source's 1:3 ratio: O2=$o2 N2=$n2")
    }

    // ── Stratification ────────────────────────────────────────────────────────

    @Test
    fun `heavy gas sinks and light gas rises`() {
        // A tall room with the layers deliberately the wrong way up: CO2 on top, nitrogen below.
        val room = sealedRoom(3, 8)
        val g = room.grid
        val grams = LongArray(g.size * Species.COUNT)
        for (y in 2..3) grams[g.index(2, y) * Species.COUNT + Species.CarbonDioxide.ordinal] = 1_000L
        for (y in 6..7) grams[g.index(2, y) * Species.COUNT + Species.Nitrogen.ordinal] = 1_000L
        val field = AirField.of(grams)
        var s = room.copy(air = field, baselineAirGrams = field.totalGrams)

        s = run(s, 120)
        val co2High = s.air.gramsOf(g.index(2, 2), Species.CarbonDioxide)
        val co2Low = s.air.gramsOf(g.index(2, 7), Species.CarbonDioxide)
        assertTrue(co2Low > co2High, "carbon dioxide should have settled: top=$co2High bottom=$co2Low")

        val n2High = s.air.gramsOf(g.index(2, 2), Species.Nitrogen)
        val n2Low = s.air.gramsOf(g.index(2, 7), Species.Nitrogen)
        assertTrue(n2High > n2Low, "and nitrogen risen: top=$n2High bottom=$n2Low")
        assertAirBalanced(s, "after stratifying")
    }

    @Test
    fun `stratification moves composition without moving pressure`() {
        val room = sealedRoom(3, 6)
        val g = room.grid
        val grams = LongArray(g.size * Species.COUNT)
        for (y in 2..5) {
            val base = g.index(2, y) * Species.COUNT
            grams[base + Species.CarbonDioxide.ordinal] = 500L
            grams[base + Species.Nitrogen.ordinal] = 500L
        }
        val field = AirField.of(grams)
        var s = room.copy(air = field, baselineAirGrams = field.totalGrams)

        s = run(s, 40)
        for (y in 2..5) {
            assertEquals(
                1_000L, s.air.pressureAt(g.index(2, y)),
                "a swap must not change how much is in a tile (row $y)",
            )
        }
    }

    @Test
    fun `without axis-aligned gravity nothing stratifies`() {
        // The fast path is the only thing that assumes a grid-aligned "down"; a diagonal gravity has
        // to leave the field alone rather than pick an axis at random.
        val room = sealedRoom(3, 6)
        val g = room.grid
        val grams = LongArray(g.size * Species.COUNT)
        for (y in 2..3) grams[g.index(2, y) * Species.COUNT + Species.CarbonDioxide.ordinal] = 1_000L
        for (y in 4..5) grams[g.index(2, y) * Species.COUNT + Species.Nitrogen.ordinal] = 1_000L
        val field = AirField.of(grams)
        val diagonal = Frac2(Frac(1L, 2), Frac(1L, 2))
        var s = room.copy(air = field, baselineAirGrams = field.totalGrams, gravity = diagonal)

        val before = s.air.gramsOf(g.index(2, 2), Species.CarbonDioxide)
        s = run(s, 40)
        assertEquals(before, s.air.gramsOf(g.index(2, 2), Species.CarbonDioxide), "no sorting happened")
        assertAirBalanced(s, "diagonal gravity")
    }

    @Test
    fun `inverting gravity inverts which way the heavy gas goes`() {
        val room = sealedRoom(3, 8)
        val g = room.grid
        val grams = LongArray(g.size * Species.COUNT)
        for (y in 6..7) grams[g.index(2, y) * Species.COUNT + Species.CarbonDioxide.ordinal] = 1_000L
        for (y in 2..3) grams[g.index(2, y) * Species.COUNT + Species.Nitrogen.ordinal] = 1_000L
        val field = AirField.of(grams)
        val upIsDown = Frac2(Frac(0L, 1), Frac(-1L, 1))
        var s = room.copy(air = field, baselineAirGrams = field.totalGrams, gravity = upIsDown)

        s = run(s, 120)
        val co2High = s.air.gramsOf(g.index(2, 2), Species.CarbonDioxide)
        val co2Low = s.air.gramsOf(g.index(2, 7), Species.CarbonDioxide)
        assertTrue(co2High > co2Low, "with gravity reversed, heavy gas rises: top=$co2High bottom=$co2Low")
    }

    // ── Interaction with the rest of the world ────────────────────────────────

    @Test
    fun `sealed but empty reads as vacuum pressure without being outside`() {
        val room = sealedRoom(4, 4)
        val g = room.grid
        val emptied = room.copy(
            air = AirField.of(LongArray(g.size * Species.COUNT)),
            baselineAirGrams = 0L,
        )
        val s = run(emptied, 4)
        assertEquals(Structure.Interior, s.structure[g.index(2, 2)], "it is still a room")
        assertEquals(0, s.pressurePercentAt(g.index(2, 2)), "it just has nothing in it")
    }

    @Test
    fun `two runs of a breathing world are identical`() {
        fun digest(s: VesselState) = buildString {
            append(s.atmosphereGrams).append('|').append(s.airVentedGrams)
            for (i in 0 until s.grid.size) append(s.air.pressureAt(i)).append(',')
        }
        val grid = Grid(40, 28)
        assertEquals(digest(run(starterVessel(grid), 900)), digest(run(starterVessel(grid), 900)))
    }
}
