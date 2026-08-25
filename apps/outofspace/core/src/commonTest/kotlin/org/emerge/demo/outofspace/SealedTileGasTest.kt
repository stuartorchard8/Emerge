package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.heatCapacityOf
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Nothing may put gas inside a tile that holds air out.**
 *
 * A wall is not a room with the doors shut — it is a place a gas cell does not exist. Every face of
 * a `blocksAir` tile reads `CLOSED` in `ApertureField.sideAperture`, so `diffuseFluid` can never
 * move anything out of one again. Gas that arrives there is gas that is gone: it is off the
 * pressure solver, off every route the player has, and it stays for the life of the save.
 *
 * `inject` already knows this — the debug bellows refuses outright at a tile whose machine
 * [org.emerge.demo.outofspace.world.machine.DeckMachineKind.preventAirflow]. The ambient chemistry
 * does not: `oxidise` takes no [org.emerge.demo.outofspace.world.StructureMap] and its `ventGas`
 * puts a reaction's gaseous products into `air` wherever the matter happened to be sitting. Track
 * crosses a bulkhead in every vessel anyone builds, so "wherever the matter happened to be sitting"
 * includes the inside of a hull plate.
 *
 * Found in Stu's save at (10,31) and up the column to (10,27): 18.45 kg of oxygen, water vapour,
 * CO2 and methane sealed inside six hull tiles, at 2.8x ambient pressure and unreachable. Not a
 * trapped roomful — there is no nitrogen and no argon in it at all, which is what says it was
 * *made* there rather than shut in.
 *
 * ⚠️ **These pin the invariant, not the remedy.** Where the gas goes instead is a design question —
 * see `the chemistry still runs inside a bulkhead` below for the half of it these tests deliberately
 * do not decide.
 */
class SealedTileGasTest {

    private val grid = Grid(14, 9)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    /** The row the track runs along, straight through the bulkhead at [wall]. */
    private val row = 4

    /** An interior hull plate with track running through it — the bulkhead penetration. */
    private val wall get() = grid.tile(7, row)

    /** A tile of the same run that is open to the room, for the control. */
    private val openDeck get() = grid.tile(4, row)

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * A sealed box, an interior bulkhead, and one lump standing on the track at [at].
     *
     * The lump is put on the rail before the state is constructed so `baselineCargoMass` counts it —
     * `AmbientChemistryTest.withLump`'s reason, and the difference between a fixture that states its
     * stock and one that states a leak.
     */
    private fun withLump(lump: Mixture, at: TileIndex): VesselState {
        val deck = DeckArray(grid)
        for (x in 0 until grid.width) {
            deck += Hull(grid.tile(x, 0))
            deck += Hull(grid.tile(x, grid.height - 1))
        }
        for (y in 1 until grid.height - 1) {
            deck += Hull(grid.tile(0, y))
            deck += Hull(grid.tile(grid.width - 1, y))
        }
        // The bulkhead the run passes through. A wall inside the room rather than part of its shell,
        // so a failure cannot be confused with the vessel venting to space.
        deck += Hull(wall)

        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 2, grid.width - 3, row)

        val rail = RailLayer.empty(grid.size)
        rail.put(at, lump)

        return VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = rail,
            creative = true,
        )
    }

    /** This mixture carrying the heat that puts it at [kelvin]. */
    private fun Mixture.at(kelvin: Int): Mixture = Mixture.of(masses, heatCapacityOf(this) * kelvin)

    /**
     * Calcite hot enough to calcine: `CaCO3 -> CaO + CO2`, onset 1170 K.
     *
     * Chosen because a decomposition needs **no reagent** — not the room's oxygen, not a solid in
     * the layer. Heat alone, so it fires inside a wall where an oxidation could not, and there is
     * no second explanation for what turns up in the tile.
     */
    private fun calcining(): Mixture =
        Mixture.of(Species.Calcite to 20L * Budget.KILOGRAM, energy = 0L).at(1400)

    /**
     * Comet ore at room temperature: water, CO2 and a little algae.
     *
     * `6 H2O + 6 CO2 -> C6H12O6 + 6 O2` with the algae as its own catalyst — the photosynthesis row
     * in `Reduction.kt`, **onset 273 K**. This is the one that actually filled the column in Stu's
     * save, and the reason it matters more than the calcite is the temperature: it needs nothing
     * hotter than a room, so any ore lump carrying all three volatiles blooms wherever it stands.
     */
    private fun cometOre(): Mixture = Mixture.of(
        Species.Water to 20L * Budget.KILOGRAM,
        Species.CarbonDioxide to 20L * Budget.KILOGRAM,
        Species.Algae to 5L * Budget.KILOGRAM,
        energy = 0L,
    ).at(Temperature.AMBIENT_KELVIN)

    private fun gasIn(s: VesselState, tile: TileIndex): Long {
        var sum = 0L
        for (f in Fluid.ALL) sum += s.air.massOf(tile, f)
        return sum
    }

    private fun railMass(s: VesselState, species: Species): Long {
        var sum = 0L
        for (tile in grid.tiles) sum += s.rail.stuff[tile, species]
        return sum
    }

    /**
     * The invariant, over the whole world rather than the one tile under test.
     *
     * Swept wide on purpose: a lump that walks off the bulkhead into a different wall is the same
     * bug and must not read as a pass, and neither must a product that lands one tile over.
     */
    private fun assertNoGasInAnyWall(s: VesselState, note: String) {
        for (tile in grid.tiles) {
            if (!s.structure.blocksAir(tile)) continue
            assertEquals(
                0L, gasIn(s, tile),
                "$note: gas inside a tile that holds air out, at (${grid.xOf(tile)},${grid.yOf(tile)})",
            )
        }
    }

    // ── The invariant ────────────────────────────────────────────────────────

    @Test
    fun `a mineral calcining inside a bulkhead vents nothing into the wall`() {
        var s = withLump(calcining(), wall)
        assertEquals(0L, gasIn(s, wall), "the fixture started with gas in the plate")

        repeat(TICKS) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            assertNoGasInAnyWall(s, "tick ${s.tick}")
        }
    }

    @Test
    fun `algae blooming inside a bulkhead vents nothing into the wall`() {
        var s = withLump(cometOre(), wall)
        assertEquals(0L, gasIn(s, wall), "the fixture started with gas in the plate")

        repeat(TICKS) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            assertNoGasInAnyWall(s, "tick ${s.tick}")
        }
    }

    // ── Anti-vacuity ─────────────────────────────────────────────────────────

    @Test
    fun `the same lump on open deck does vent, so the two above are not green by accident`() {
        val start = withLump(calcining(), openDeck)
        val after = run(start, TICKS)

        assertTrue(
            gasIn(after, openDeck) > gasIn(start, openDeck),
            "the calcite never calcined in the open either — the fixture is inert, and the " +
                "invariant tests above prove nothing",
        )
    }

    /**
     * ⚠️ **This is the half these tests do not decide, written down so it cannot be decided by
     * accident.**
     *
     * A wall is a bad place to *put* a gas. It is not obviously a bad place for a rock to react —
     * the whole argument of `AmbientChemistry.kt` is that chemistry is a property of matter and
     * conditions and never asks what the matter is sitting on, and a lump does not know it is
     * inside a bulkhead.
     *
     * So the cheap way to make the two tests above pass is to stop the chemistry at a sealed tile,
     * and that is a different game: ore would stop refining the moment it crossed a doorway. This
     * fails if anyone takes that route, which is the point of it. If the design later *chooses*
     * that route, this is the test to argue with — the invariant above stands either way.
     */
    @Test
    fun `the chemistry still runs inside a bulkhead`() {
        val start = withLump(calcining(), wall)
        val after = run(start, TICKS)

        assertTrue(
            railMass(after, Species.Calcite) < railMass(start, Species.Calcite),
            "the calcite did not calcine inside the plate: ${railMass(after, Species.Calcite)} " +
                "of ${railMass(start, Species.Calcite)}",
        )
    }

    private companion object {
        /** Chemistry runs on `CHEM_PERIOD`, so this is a few dozen passes rather than a few. */
        const val TICKS = 240
    }
}
