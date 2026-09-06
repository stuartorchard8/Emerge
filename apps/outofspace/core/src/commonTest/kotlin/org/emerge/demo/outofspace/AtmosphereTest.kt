package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Vent
import org.emerge.demo.outofspace.world.Structure
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.demo.outofspace.world.tryDisplaceAir
import org.emerge.sim.core.PlayerId
import kotlin.test.Ignore
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
     * Every seeded mass below is a number of *mass*, said out loud.
     *
     * A tile of air at one atmosphere is about a kilogram, so "6,000" here means a room six times
     * over-pressured — a fixture you can reason about. Written as a bare `6_000L` it meant six
     * milligrams the moment the mass unit moved, which does not read as zero anywhere a gram is
     * compared to a gram, but rounds to zero the instant it becomes a pressure: `pressureAt` is in
     * millimoles, and six milligrams of oxygen is not one. Both flow tests then compared a spread of
     * 0 against a spread of 0 and failed with nothing visibly wrong in them.
     */
    private val gram = Budget.GRAM
    private val joule = Budget.JOULE

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
            s.baselineAirMass,
            s.atmosphereMass + s.airVentedMass,
            "$what: aboard ${s.atmosphereMass} + vented ${s.airVentedMass}",
        )
    }

    /** A hull box [w] x [h] with a ring of space around it. */
    private fun sealedRoom(w: Int, h: Int): VesselState {
        val grid = Grid(w + 2, h + 2)
        val deck = DeckArray(grid)
        for (x in 1..w) {
            deck += Hull(grid.tile(x, 1))
            deck += Hull(grid.tile(x, h))
        }
        for (y in 2..<h) {
            deck += Hull(grid.tile(1, y))
            deck += Hull(grid.tile(w, y))
        }
        return VesselState(grid, deck = deck, gravity = VesselState.PLATING_ONE_G, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
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
        val deck = DeckArray(grid)
        for (x in 2..6) { deck += Hull(grid.tile(x, 1)); deck += Hull(grid.tile(x, 3)) }
        for (y in 1..2) { deck += Hull(grid.tile(1, y)); deck += Hull(grid.tile(7, y)) }
        deck += Hull(grid.tile(4, 2))   // the dividing wall
        var s = VesselState(grid, deck = deck, gravity = VesselState.PLATING_ONE_G, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))

        val mass = MassArray(grid.size)
        for (x in 2..3) mass[MassIndex(grid.tile(x, 2), Fluid.Oxygen)] = 2_000L * gram
        for (x in 5..6) mass[MassIndex(grid.tile(x, 2), Fluid.Oxygen)] = 500L * gram
        val field = Stuff.gas(mass)
        s = s.copy(air = field, baselineAirMass = field.totalMass)

        s = run(s, 40)
        // Per *room*, not per tile. What this test is about is the wall: a room at four times the
        // pressure of the one next door stays there, because nothing crosses a bulkhead. Within a
        // room the gas is free to slosh — the two tiles of the high side started at exactly 2000
        // each and a single gram moving between them is the sim working, not the wall leaking.
        // Asserting per-tile made this a test of "the air does not move at all", which is a
        // different and much stronger claim than the one in its name, and a false one.
        //
        // Pressure reads in millimoles now, not mass -- see [AirField.pressureAt].
        fun roomMass(xs: IntRange) = xs.sumOf { s.air.densityAt(grid.tile(it, 2)) }
        assertEquals(4_000L * gram, roomMass(2..3), "the high side stayed high")
        assertEquals(1_000L * gram, roomMass(5..6), "and the low side stayed low")
        assertAirBalanced(s, "divided rooms")
    }

    // ── Flow ──────────────────────────────────────────────────────────────────

    @Test
    fun `pressure equalises across a connected room`() {
        val room = sealedRoom(8, 4)
        val g = room.grid
        val mass = MassArray(g.size)
        mass[MassIndex(g.tile(2, 2), Fluid.Oxygen)] = 6_000L * gram
        val field = Stuff.gas(mass)
        var s = room.copy(air = field, baselineAirMass = field.totalMass)

        fun interior() = g.tiles.filter {
            s.structure.isContained(it) && !s.structure.blocksAir(it)
        }
        val startSpread = interior().let { t ->
            t.maxOf { s.air.pressureAt(it) } - t.minOf { s.air.pressureAt(it) }
        }
        // ⚠️ **1600 rather than 400, and the threshold below is untouched.** Felt gravity biases
        // the diffusion again, so the room has to *settle* as well as spread and the collapse takes
        // longer to finish. Measured: the spread converges to exactly 15,625 and sits there through
        // 8,400 ticks — a standing gradient, which the note above already says is the correct answer
        // under gravity, and comfortably inside the tenth this asserts. At 400 ticks it was still on
        // its way down at 19,338, so what failed was the horizon and not the physics.
        s = run(s, 1600)


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
            if (right == TileIndex.NONE || right !in interior()) continue
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
        val mass = MassArray(g.size)
        mass[MassIndex(g.tile(2, 2), Fluid.Oxygen)] = 10_000L * gram
        val field = Stuff.gas(mass)
        var s = room.copy(air = field, baselineAirMass = field.totalMass)

        // The old assertion was that the peak never rises, which is true of diffusion and false of
        // any solver that carries momentum: gas that has been accelerated has to arrive somewhere,
        // and a pressure wave can genuinely re-concentrate for a tick on the way to settling. What
        // must hold is that it settles -- the peak trends down and never runs away.
        val start = g.tiles.maxOf { s.air.pressureAt(it) }
        var peak = start
        repeat(300) {
            s = OutofspaceReducer.reduce(cfgFor(g), s, emptyMap())
            peak = g.tiles.maxOf { s.air.pressureAt(it) }
            assertTrue(peak <= start, "the densest tile ran away from where it began: $peak > $start")
        }
        assertTrue(peak < start / 2, "and it should have spread out by now: $peak vs $start")
        assertAirBalanced(s, "after settling")
    }

    @Test
    fun `a draught carries the room's mix rather than skimming one gas`() {
        val room = sealedRoom(6, 3)
        val g = room.grid
        val mass = MassArray(g.size)
        val tile = g.tile(2, 2)
        mass[MassIndex(tile, Fluid.Oxygen)] = 2_000L * gram
        mass[MassIndex(tile, Fluid.Nitrogen)] = 6_000L * gram
        val field = Stuff.gas(mass)
        var s = room.copy(air = field, baselineAirMass = field.totalMass)

        s = run(s, 30)   // part-way through equalising
        val neighbour = g.tile(3, 2)
        val o2 = s.air.massOf(neighbour, Species.Oxygen)
        val n2 = s.air.massOf(neighbour, Species.Nitrogen)
        assertTrue(o2 > 0L && n2 > 0L, "both gases should have moved: O2=$o2 N2=$n2")
        assertTrue(n2 > o2 * 2, "and roughly in the source's 1:3 ratio: O2=$o2 N2=$n2")
    }

    // ── Stratification ────────────────────────────────────────────────────────

    @Test
    fun `sealed but empty reads as vacuum pressure without being outside`() {
        val room = sealedRoom(4, 4)
        val g = room.grid
        val emptied = room.copy(
            air = Stuff.gas(MassArray(g.size)),
            baselineAirMass = 0L,
        )
        val s = run(emptied, 4)
        assertEquals(Structure.Interior, s.structure[g.tile(2, 2).index], "it is still a room")
        assertEquals(0, s.pressurePercentAt(g.tile(2, 2)), "it just has nothing in it")
    }

    @Test
    fun `building a wall through a room pushes its air aside rather than swallowing it`() {
        // Creative: this is about a *finished* wall displacing air, and outside creative what a
        // placement puts down is a ghost with no metal in it, which pushes nothing aside until it
        // is built. See `apps/outofspace/PLAN_self_building_rails.md`.
        val room = sealedRoom(8, 4).copy(creative = true)
        val g = room.grid
        val wall = g.tile(4, 3)
        var s = run(room, 20)   // settle first, so the wall tile is holding a known amount
        val aboard = s.atmosphereMass
        assertTrue(s.air.pressureAt(wall) > 0L, "the tile we are about to wall off had air in it")

        s = run(s, 1, OutofspaceInput(listOf(fixturePlace(wall, Brush.Building(DeckMachineKind.Hull), Direction.Up))))
        assertEquals(0L, s.air.pressureAt(wall), "a hull tile is not part of the atmosphere")
        assertEquals(aboard, s.atmosphereMass, "and not a gram of it was lost")
        assertAirBalanced(s, "after walling")
    }

    @Test
    fun `a build with nowhere to put the air is refused`() {
        // A one-tile pocket: hull all round bar the tile itself, which the player then tries to fill.
        val room = sealedRoom(5, 5)
        val g = room.grid
        val pocket = g.tile(3, 3)
        val deck = room.deck.copyOf()
        for (dir in Direction.ALL) deck += Hull(g.neighbour(pocket, dir))
        var s = run(room.copy(deck = deck), 4)
        val trapped = s.air.pressureAt(pocket)
        assertTrue(trapped > 0L, "the pocket has air in it to begin with")

        s = run(s, 2, OutofspaceInput(listOf(fixturePlace(pocket, Brush.Building(DeckMachineKind.Hull), Direction.Up))))
        assertEquals(null, s.deck[pocket], "the build had nowhere to put the air, so it did not happen")
        assertEquals(trapped, s.air.pressureAt(pocket), "and the air is untouched")
        assertAirBalanced(s, "after the refusal")
    }

    @Ignore // No more machines bigger than 1x1 block air. Bring this back if one is introduced
    @Test
    fun `a footprint displaces the air under all of it at once`() {
        // Creative, for the reason above: a ghost footprint displaces nothing.
        val room = sealedRoom(9, 5).copy(creative = true)   // a 7x3 interior, room for a 3x3 either side
        val g = room.grid
        val tile = g.tile(5, 3)
        var s = run(room, 20)
        val aboard = s.atmosphereMass

        s = run(s, 1, OutofspaceInput(listOf(fixturePlace(tile, Brush.Building(DeckMachineKind.Warehouse), Direction.Right))))
        val m: DeckMachine? = s.deck[tile]
        assertTrue(m != null, "the storage went down")
        for (x in 4..6) for (y in 2..4) {
            assertEquals(0L, s.air.pressureAt(g.tile(x, y)), "($x,$y) is under the machine")
        }
        assertEquals(aboard, s.atmosphereMass, "every gram of it moved rather than vanishing")
        assertAirBalanced(s, "after a footprint landed")
    }

    @Test
    fun `displaced air splits between the ways out by how far it has to travel`() {
        // A five-tile strip with one way out at each end, and air in a tile twice as far from the
        // right one as the left: it should leave two-to-one in favour of the near door.
        val g = Grid(9, 3)
        val strip = (2..6).map { g.tile(it, 1) }
        val exits = setOf(g.tile(1, 1), g.tile(7, 1))
        val masses = MassArray(g.size)
        val energies = EnergyArray(g.size)
        masses[MassIndex(g.tile(3, 1), Fluid.Oxygen)] = 3_000L * gram
        masses[MassIndex(g.tile(3, 1), Fluid.Oxygen)] = 3_000L * gram
        energies[g.tile(3, 1)] = 3_000L * joule
        energies[g.tile(3, 1)] = 3_000L * joule

        assertTrue(tryDisplaceAir(g, masses, energies, strip) { it in exits }, "both ends are open")
        for (tile in strip) {
            assertEquals(0L, masses[MassIndex(tile, Fluid.Oxygen)], "the strip is empty")
            assertEquals(0L, energies[tile], "the strip has no energy")
        }
        assertEquals(2_000L * gram, masses[MassIndex(g.tile(1, 1), Fluid.Oxygen)], "near door")
        assertEquals(1_000L * gram, masses[MassIndex(g.tile(7, 1), Fluid.Oxygen)], "far door")
    }

    @Test
    fun `a sealed area displaces nothing and reports failure`() {
        val g = Grid(9, 3)
        val strip = (2..6).map { g.tile(it, 1) }
        val masses = MassArray(g.size)
        val energies = EnergyArray(g.size)
        masses[MassIndex(g.tile(3, 1), Fluid.Oxygen)] = 3_000L * gram
        energies[g.tile(3, 1)] = 3_000L * gram
        val massesBefore = masses.copyOf()
        val energiesBefore = energies.copyOf()

        assertTrue(!tryDisplaceAir(g, masses, energies, strip) { false }, "there is no way out")
        assertTrue(masses.contentEquals(massesBefore), "a refusal leaves the field exactly as it found it")
        assertTrue(energies.contentEquals(energiesBefore), "a refusal leaves the field exactly as it found it")
    }

    @Test
    fun `knocking a wall out lets air back into the tile`() {
        val room = sealedRoom(8, 4)
        val g = room.grid
        val wall = g.tile(4, 3)
        var s = run(room, 1, OutofspaceInput(listOf(fixturePlace(wall, Brush.Building(DeckMachineKind.Hull), Direction.Up))))
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
            for (tile in s.grid.tiles) append(s.air.pressureAt(tile)).append(',')
        }
        val grid = Grid(40, 28)
        // 500 ticks, twice, which measures 2.8s. It was 900 and measured 5.06s the moment the
        // settling truncation came out — stronger buoyancy means faster gas means more CFL
        // sub-steps per tick, so the same tick count costs more than it used to. Determinism
        // either holds or it does not; a run long enough for the world to be busy is the whole
        // requirement, and 500 ticks of a breathing starter vessel is amply that.
        assertEquals(digest(run(starterVessel(grid), 500)), digest(run(starterVessel(grid), 500)))
    }

    @Test
    fun `air displaces through a tile occupied by a non-preventAirflow machine`() {
        // A narrow corridor where the only exit is a Vent tile. All other neighbors of the strip
        // are hull tiles (preventAirflow = true), which the predicate rejects as non-permeable.
        // This proves deck.isPermeableToAir returns true for a Vent.
        val g = Grid(5, 3)
        val vent = g.tile(2, 1)
        val strip = listOf(g.tile(1, 1), g.tile(3, 1))
        val deck = DeckArray(g)
        deck.stand(Vent(vent), withCasing = true, material = Species.Iron)
        // Block every other neighbor of the strip with Hull (preventAirflow) tiles,
        // leaving only the Vent as a permeable exit.
        deck.stand(Hull(g.tile(1, 0)), withCasing = true, material = Species.Iron)
        deck.stand(Hull(g.tile(1, 2)), withCasing = true, material = Species.Iron)
        deck.stand(Hull(g.tile(2, 0)), withCasing = true, material = Species.Iron)
        deck.stand(Hull(g.tile(2, 2)), withCasing = true, material = Species.Iron)
        deck.stand(Hull(g.tile(3, 0)), withCasing = true, material = Species.Iron)
        deck.stand(Hull(g.tile(3, 2)), withCasing = true, material = Species.Iron)
        deck.stand(Hull(g.tile(0, 1)), withCasing = true, material = Species.Iron)
        deck.stand(Hull(g.tile(4, 1)), withCasing = true, material = Species.Iron)
        val masses = MassArray(g.size)
        val energies = EnergyArray(g.size)
        masses[MassIndex(g.tile(1, 1), Fluid.Oxygen)] = 1_000L * gram
        masses[MassIndex(g.tile(3, 1), Fluid.Oxygen)] = 1_000L * gram
        energies[g.tile(1, 1)] = 1_000L * joule
        energies[g.tile(3, 1)] = 1_000L * joule

        assertTrue(
            tryDisplaceAir(g, masses, energies, strip) { deck.isPermeableToAir(it) },
            "air displaces through a Vent tile",
        )
        assertEquals(0L, masses[MassIndex(g.tile(1, 1), Fluid.Oxygen)], "left source is empty")
        assertEquals(0L, masses[MassIndex(g.tile(3, 1), Fluid.Oxygen)], "right source is empty")
        assertEquals(2_000L * gram, masses[MassIndex(vent, Fluid.Oxygen)], "all air arrived at the Vent")
    }
}
