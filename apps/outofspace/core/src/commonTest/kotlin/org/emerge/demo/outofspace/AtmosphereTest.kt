package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Structure
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.demo.outofspace.world.tryDisplaceAir
import org.emerge.sim.core.PlayerId
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

    /**
     * Every seeded mass below is a number of *grams*, said out loud.
     *
     * A tile of air at one atmosphere is about a kilogram, so "6,000" here means a room six times
     * over-pressured — a fixture you can reason about. Written as a bare `6_000L` it meant six
     * milligrams the moment the mass unit moved, which does not read as zero anywhere a gram is
     * compared to a gram, but rounds to zero the instant it becomes a pressure: `pressureAt` is in
     * millimoles, and six milligrams of oxygen is not one. Both flow tests then compared a spread of
     * 0 against a spread of 0 and failed with nothing visibly wrong in them.
     */
    private val gram = Budget.GRAM

    private fun cfgFor(grid: Grid) = OutofspaceConfig(initialGrid = grid)

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
            s.atmosphereMass + s.airVentedMass,
            "$what: aboard ${s.atmosphereMass} + vented ${s.airVentedMass}",
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
        return VesselState(grid, machines.toList(), gravity = VesselState.PLATING_ONE_G)
    }

    // ── Conservation ──────────────────────────────────────────────────────────

    @Test
    fun `air is conserved on every tick of a working vessel`() {
        var s = starterVessel(Grid(40, 28))
        val cfg = cfgFor(s.grid)
        assertTrue(s.atmosphereMass > 0L, "a sealed vessel starts with air in it")
        repeat(160) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (it % 79 == 0) assertAirBalanced(s, "tick ${s.tick}")
        }
        assertAirBalanced(s, "final")
        assertEquals(0L, s.airVentedMass, "an intact hull loses nothing")
    }

    @Test
    fun `hull separates two rooms so their pressures do not equalise`() {
        // Two 3-wide rooms sharing a wall, one at double pressure.
        val grid = Grid(9, 5)
        val machines = arrayOfNulls<Machine>(grid.size)
        for (x in 1..7) { machines[grid.index(x, 1)] = Hull(); machines[grid.index(x, 3)] = Hull() }
        for (y in 1..3) { machines[grid.index(1, y)] = Hull(); machines[grid.index(7, y)] = Hull() }
        machines[grid.index(4, 2)] = Hull()   // the dividing wall
        var s = VesselState(grid, machines.toList(), gravity = VesselState.PLATING_ONE_G)

        val grams = LongArray(grid.size * Species.COUNT)
        for (x in 2..3) grams[grid.index(x, 2) * Species.COUNT + Species.Oxygen.ordinal] = 2_000L * gram
        for (x in 5..6) grams[grid.index(x, 2) * Species.COUNT + Species.Oxygen.ordinal] = 500L * gram
        val field = AirField.of(grams)
        s = s.copy(air = field, baselineAirGrams = field.totalGrams)

        s = run(s, 40)
        // Per *room*, not per tile. What this test is about is the wall: a room at four times the
        // pressure of the one next door stays there, because nothing crosses a bulkhead. Within a
        // room the gas is free to slosh — the two tiles of the high side started at exactly 2000
        // each and a single gram moving between them is the sim working, not the wall leaking.
        // Asserting per-tile made this a test of "the air does not move at all", which is a
        // different and much stronger claim than the one in its name, and a false one.
        //
        // Pressure reads in millimoles now, not grams -- see [AirField.pressureAt].
        fun roomGrams(xs: IntRange) = xs.sumOf { s.air.densityAt(grid.index(it, 2)) }
        assertEquals(4_000L * gram, roomGrams(2..3), "the high side stayed high")
        assertEquals(1_000L * gram, roomGrams(5..6), "and the low side stayed low")
        assertAirBalanced(s, "divided rooms")
    }

    // ── Flow ──────────────────────────────────────────────────────────────────

    @Test
    fun `pressure equalises across a connected room`() {
        val room = sealedRoom(8, 4)
        val g = room.grid
        val grams = LongArray(g.size * Species.COUNT)
        grams[g.index(2, 2) * Species.COUNT + Species.Oxygen.ordinal] = 6_000L * gram
        val field = AirField.of(grams)
        var s = room.copy(air = field, baselineAirGrams = field.totalGrams)

        fun interior() = (0 until g.size).filter {
            s.structure.isContained(it) && !s.structure.isImpermeable(it)
        }
        val startSpread = interior().let { t ->
            t.maxOf { s.air.pressureAt(it) } - t.minOf { s.air.pressureAt(it) }
        }

        s = run(s, 400)

        // Two changes from the version this replaces, both because the air has inertia now.
        //
        // It used to assert every pair of neighbours sat within one unit of each other. That is a
        // statement about diffusion, which can only ever approach equilibrium. A solver that carries
        // momentum overshoots and rings on the way down, so what can honestly be asserted is that
        // the spread collapses -- not that it reaches a particular floor by a particular tick.
        //
        // And it asserted levelness in every direction, including vertically. Under gravity that is
        // now wrong on purpose: pure oxygen is heavier than the reference mixture, so it genuinely
        // settles and a standing vertical gradient is the correct answer rather than a failure.
        val spread = interior().let { t ->
            t.maxOf { s.air.pressureAt(it) } - t.minOf { s.air.pressureAt(it) }
        }
        assertTrue(
            spread < startSpread / 10,
            "the room should have evened out: spread $spread, started at $startSpread",
        )
        for (tile in interior()) {
            val right = g.neighbour(tile, org.emerge.demo.outofspace.world.Direction.Right)
            if (right < 0 || right !in interior()) continue
            val gap = s.air.pressureAt(tile) - s.air.pressureAt(right)
            val tolerance = s.air.pressureAt(tile) / 5L + 1L
            assertTrue(
                gap in -tolerance..tolerance,
                "horizontal neighbours should be close: $tile vs $right differ by $gap",
            )
        }
        assertAirBalanced(s, "after equalising")
    }

    @Test
    fun `flow settles rather than running away`() {
        val room = sealedRoom(6, 3)
        val g = room.grid
        val grams = LongArray(g.size * Species.COUNT)
        grams[g.index(2, 2) * Species.COUNT + Species.Oxygen.ordinal] = 10_000L * gram
        val field = AirField.of(grams)
        var s = room.copy(air = field, baselineAirGrams = field.totalGrams)

        // The old assertion was that the peak never rises, which is true of diffusion and false of
        // any solver that carries momentum: gas that has been accelerated has to arrive somewhere,
        // and a pressure wave can genuinely re-concentrate for a tick on the way to settling. What
        // must hold is that it settles -- the peak trends down and never runs away.
        val start = (0 until g.size).maxOf { s.air.pressureAt(it) }
        var peak = start
        repeat(300) {
            s = OutofspaceReducer.reduce(cfgFor(g), s, emptyMap())
            peak = (0 until g.size).maxOf { s.air.pressureAt(it) }
            assertTrue(peak <= start, "the densest tile ran away from where it began: $peak > $start")
        }
        assertTrue(peak < start / 2, "and it should have spread out by now: $peak vs $start")
        assertAirBalanced(s, "after settling")
    }

    @Test
    fun `a draught carries the room's mix rather than skimming one gas`() {
        val room = sealedRoom(6, 3)
        val g = room.grid
        val grams = LongArray(g.size * Species.COUNT)
        val source = g.index(2, 2) * Species.COUNT
        grams[source + Species.Oxygen.ordinal] = 2_000L * gram
        grams[source + Species.Nitrogen.ordinal] = 6_000L * gram
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
    fun `building a wall through a room pushes its air aside rather than swallowing it`() {
        val room = sealedRoom(8, 4)
        val g = room.grid
        val wall = g.index(4, 3)
        var s = run(room, 20)   // settle first, so the wall tile is holding a known amount
        val aboard = s.atmosphereMass
        assertTrue(s.air.pressureAt(wall) > 0L, "the tile we are about to wall off had air in it")

        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(wall, MachineKind.Hull, Direction.Up))))
        assertEquals(0L, s.air.pressureAt(wall), "a hull tile is not part of the atmosphere")
        assertEquals(aboard, s.atmosphereMass, "and not a gram of it was lost")
        assertAirBalanced(s, "after walling")
    }

    @Test
    fun `a build with nowhere to put the air is refused`() {
        // A one-tile pocket: hull all round bar the tile itself, which the player then tries to fill.
        val room = sealedRoom(5, 5)
        val g = room.grid
        val pocket = g.index(3, 3)
        val machines = room.machines.toMutableList()
        for (dir in Direction.ALL) machines[g.neighbour(pocket, dir)] = Hull()
        var s = run(room.copy(machines = machines.toList()), 4)
        val trapped = s.air.pressureAt(pocket)
        assertTrue(trapped > 0L, "the pocket has air in it to begin with")

        s = run(s, 2, OutofspaceInput(listOf(Edit.Place(pocket, MachineKind.Hull, Direction.Up))))
        assertEquals(null, s.machines[pocket], "the build had nowhere to put the air, so it did not happen")
        assertEquals(trapped, s.air.pressureAt(pocket), "and the air is untouched")
        assertAirBalanced(s, "after the refusal")
    }

    @Test
    fun `a footprint displaces the air under all of it at once`() {
        val room = sealedRoom(9, 5)   // a 7x3 interior, room for a 3x3 with a column either side
        val g = room.grid
        val at = g.index(5, 3)
        var s = run(room, 20)
        val aboard = s.atmosphereMass

        s = run(s, 1, OutofspaceInput(listOf(Edit.Place(at, MachineKind.Storage, Direction.Right))))
        assertTrue(s.machines[at] != null, "the storage went down")
        for (x in 4..6) for (y in 2..4) {
            assertEquals(0L, s.air.pressureAt(g.index(x, y)), "($x,$y) is under the machine")
        }
        assertEquals(aboard, s.atmosphereMass, "every gram of it moved rather than vanishing")
        assertAirBalanced(s, "after a footprint landed")
    }

    @Test
    fun `displaced air splits between the ways out by how far it has to travel`() {
        // A five-tile strip with one way out at each end, and air in a tile twice as far from the
        // right one as the left: it should leave two-to-one in favour of the near door.
        val g = Grid(9, 3)
        val strip = (2..6).map { g.index(it, 1) }
        val exits = setOf(g.index(1, 1), g.index(7, 1))
        val grams = LongArray(g.size * Species.COUNT)
        grams[g.index(3, 1) * Species.COUNT + Species.Oxygen.ordinal] = 3_000L * gram

        assertTrue(tryDisplaceAir(g, grams, strip) { it in exits }, "both ends are open")
        for (tile in strip) {
            assertEquals(0L, grams[tile * Species.COUNT + Species.Oxygen.ordinal], "the strip is empty")
        }
        assertEquals(2_000L * gram, grams[g.index(1, 1) * Species.COUNT + Species.Oxygen.ordinal], "near door")
        assertEquals(1_000L * gram, grams[g.index(7, 1) * Species.COUNT + Species.Oxygen.ordinal], "far door")
    }

    @Test
    fun `a sealed area displaces nothing and reports failure`() {
        val g = Grid(9, 3)
        val strip = (2..6).map { g.index(it, 1) }
        val grams = LongArray(g.size * Species.COUNT)
        grams[g.index(3, 1) * Species.COUNT + Species.Oxygen.ordinal] = 3_000L * gram
        val before = grams.copyOf()

        assertTrue(!tryDisplaceAir(g, grams, strip) { false }, "there is no way out")
        assertTrue(grams.contentEquals(before), "a refusal leaves the field exactly as it found it")
    }

    @Test
    fun `knocking a wall out lets air back into the tile`() {
        val room = sealedRoom(8, 4)
        val g = room.grid
        val wall = g.index(4, 3)
        var s = run(room, 1, OutofspaceInput(listOf(Edit.Place(wall, MachineKind.Hull, Direction.Up))))
        s = run(s, 20)
        val aboard = s.atmosphereMass

        s = run(s, 20, OutofspaceInput(listOf(Edit.Remove(wall))))
        assertTrue(s.air.pressureAt(wall) > 0L, "the room flowed back into it")
        assertEquals(aboard, s.atmosphereMass, "an intact hull still loses nothing")
    }

    @Test
    fun `two runs of a breathing world are identical`() {
        fun digest(s: VesselState) = buildString {
            append(s.atmosphereMass).append('|').append(s.airVentedMass)
            for (i in 0 until s.grid.size) append(s.air.pressureAt(i)).append(',')
        }
        val grid = Grid(40, 28)
        // 500 ticks, twice, which measures 2.8s. It was 900 and measured 5.06s the moment the
        // settling truncation came out — stronger buoyancy means faster gas means more CFL
        // sub-steps per tick, so the same tick count costs more than it used to. Determinism
        // either holds or it does not; a run long enough for the world to be busy is the whole
        // requirement, and 500 ticks of a breathing starter vessel is amply that.
        assertEquals(digest(run(starterVessel(grid), 500)), digest(run(starterVessel(grid), 500)))
    }
}
