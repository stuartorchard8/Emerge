package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Carbon on a belt, oxygen in the room, and what it costs the two ledgers — increment 1 of
 * `PLAN_ambient_chemistry.md`, end to end through the real tick.
 *
 * The reaction itself is arithmetic and `ReactionTest` has it. What is worth testing *here* is the
 * thing the plan calls the risk: a solid leaves one conservation identity and a gas joins another,
 * and the two know nothing about each other. `airBalance` breaks **silently** if a path forgets to
 * say so — the mineral vaporizer drifted both ledgers by its whole throughput for its entire life
 * and no test was pointed at it. So these are pointed at it.
 *
 * ⚠️ **Per species, not just in total.** A total can balance while carbon quietly becomes iron,
 * which is what `conservationOf` exists to catch and what this checks across the whole world.
 */
class AmbientChemistryTest {

    private val grid = Grid(12, 8)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    /** Hot enough to be well up the rate curve, so a few ticks move a measurable amount. */
    private val burningKelvin = 1400

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * A sealed box with a short run of track down the middle and one lump on it.
     *
     * Sealed because a breach vents air, and a vent is a second reason for the atmosphere to change
     * mass — this is about the first one. The lump is placed before the state is constructed so
     * that `baselineCargoMass` counts it: a fixture that states its stock *after* construction is a
     * fixture that states a leak.
     */
    private fun withLump(lump: Mixture, at: Int = 5): VesselState {
        val deck = DeckArray(grid)
        for (x in 0 until grid.width) {
            deck += Hull(grid.tile(x, 0))
            deck += Hull(grid.tile(x, grid.height - 1))
        }
        for (y in 1 until grid.height - 1) {
            deck += Hull(grid.tile(0, y))
            deck += Hull(grid.tile(grid.width - 1, y))
        }

        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 2, grid.width - 3, ROW)

        val rail = RailLayer.empty(grid.size)
        rail.put(grid.tile(at, ROW), lump)

        return VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = rail,
            creative = true,
        )
    }

    /** A lump of carbon carrying enough heat to be at [kelvin]. */
    private fun carbonAt(kelvin: Int, mass: Long = 20L * Budget.KILOGRAM): Mixture {
        val capacity = mass * Species.Carbon.specificHeat / Budget.CAPACITY_DIVISOR
        return Mixture.of(Species.Carbon to mass, energy = capacity * kelvin)
    }

    private fun railMass(s: VesselState, species: Species): Long {
        var sum = 0L
        for (tile in grid.tiles) sum += s.rail.stuff[tile, species]
        return sum
    }

    private fun airMass(s: VesselState, fluid: Fluid): Long {
        var sum = 0L
        for (tile in grid.tiles) sum += s.air.massOf(tile, fluid)
        return sum
    }

    // ── It happens at all ────────────────────────────────────────────────────

    @Test
    fun `hot carbon on a belt burns in the air around it`() {
        val start = withLump(carbonAt(burningKelvin))
        val after = run(start, TICKS)

        val carbonBefore = railMass(start, Species.Carbon)
        val carbonAfter = railMass(after, Species.Carbon)
        assertTrue(carbonAfter < carbonBefore, "the lump did not burn: still ${carbonAfter} of $carbonBefore")

        assertTrue(
            airMass(after, Fluid.CarbonDioxide) > airMass(start, Fluid.CarbonDioxide),
            "carbon left the belt and no carbon dioxide arrived",
        )
        assertTrue(
            airMass(after, Fluid.Oxygen) < airMass(start, Fluid.Oxygen),
            "carbon dioxide appeared without any oxygen being consumed",
        )
    }

    @Test
    fun `a cold lump sits there`() {
        // The whole model in one assertion: nothing happens because of what a thing *is*, only
        // because of the conditions it is in. Same carbon, same air, ambient temperature.
        val start = withLump(carbonAt(Temperature.AMBIENT_KELVIN))
        val after = run(start, TICKS)

        assertEquals(
            railMass(start, Species.Carbon),
            railMass(after, Species.Carbon),
            "carbon burned at room temperature",
        )
        assertEquals(airMass(start, Fluid.Oxygen), airMass(after, Fluid.Oxygen), "the room lost oxygen to nothing")
    }

    @Test
    fun `carbon in a vacuum does not burn however hot it is`() {
        // Decision 2 of the plan, as a test: the reagent comes from the atmosphere, so *where* a
        // thing is decides whether it reacts. This is the property a carbothermic reduction will
        // later depend on, and it is worth pinning before anything depends on it.
        val start = withLump(carbonAt(burningKelvin))
            .let { it.copy(air = Stuff.empty(grid.size), baselineAirMass = 0L, baselineAirEnergy = 0L) }
        val after = run(start, TICKS)

        assertEquals(
            railMass(start, Species.Carbon),
            railMass(after, Species.Carbon),
            "carbon burned with no oxygen to burn in",
        )
    }

    // ── The ledgers ──────────────────────────────────────────────────────────

    @Test
    fun `a fire closes both ledgers`() {
        val start = withLump(carbonAt(burningKelvin))
        val after = run(start, TICKS)

        // It has to have actually happened, or this passes by doing nothing — the failure mode of
        // every conservation test ever written.
        assertTrue(railMass(after, Species.Carbon) < railMass(start, Species.Carbon), "nothing burned")

        assertEquals(0L, after.airBalance, "the air ledger is out by ${after.airBalance}")
        assertEquals(
            0L,
            after.inTransitMass + after.ventedMass + after.builtMass -
                after.extractedMass - after.baselineCargoMass,
            "the cargo ledger is out",
        )
    }

    @Test
    fun `every atom is accounted for, species by species`() {
        // The per-species statement, across both media at once. Carbon that leaves the belt must
        // turn up as carbon *inside* carbon dioxide, and the oxygen that went with it must come out
        // of the room's oxygen — a total-only check would pass if the two were swapped.
        val start = withLump(carbonAt(burningKelvin))
        val after = run(start, TICKS)

        val carbonBurned = railMass(start, Species.Carbon) - railMass(after, Species.Carbon)
        assertTrue(carbonBurned > 0L, "nothing burned")

        val oxygenUsed = airMass(start, Fluid.Oxygen) - airMass(after, Fluid.Oxygen)
        val dioxideMade = airMass(after, Fluid.CarbonDioxide) - airMass(start, Fluid.CarbonDioxide)

        assertEquals(carbonBurned + oxygenUsed, dioxideMade, "the carbon dioxide does not weigh its own parts")
        assertEquals(
            carbonBurned * Species.Oxygen.molarMass / Species.Carbon.molarMass,
            oxygenUsed,
            "the world ran off the stoichiometric line even though the reaction did not",
        )
        // No other species moved anywhere. This is the one that catches a reaction writing into the
        // wrong ordinal, which arithmetic tests cannot see because they never index a field.
        for (s in Species.ALL) {
            if (s == Species.Carbon) continue
            assertEquals(railMass(start, s), railMass(after, s), "$s changed on the belt")
        }
        for (f in Fluid.ALL) {
            if (f == Fluid.Oxygen || f == Fluid.CarbonDioxide) continue
            assertEquals(airMass(start, f), airMass(after, f), "$f changed in the air")
        }
    }

    @Test
    fun `the heat goes with the matter`() {
        // A hot solid becoming a gas must hand its joules over, or the world quietly cools by the
        // temperature of everything that ever reacted. Checked as "the gas got warmer", which is
        // the observable half — the energy identity itself is parked, see [VesselState.heatBalance].
        val start = withLump(carbonAt(burningKelvin))
        val after = run(start, TICKS)

        assertTrue(railMass(after, Species.Carbon) < railMass(start, Species.Carbon), "nothing burned")
        assertTrue(
            after.air.totalEnergy > start.air.totalEnergy,
            "the air took a hot gas and did not get any warmer",
        )
        assertTrue(
            after.rail.stuff.energyAt(grid.tile(5, ROW)) < start.rail.stuff.energyAt(grid.tile(5, ROW)),
            "the lump gave up mass but kept all of its heat",
        )
    }

    @Test
    fun `a fire survives a save`() {
        // Nothing here is new state — the reaction writes into layers that already round-trip — so
        // this is a guard rather than a feature: a burning world must not be a world that saves
        // differently from any other.
        val after = run(withLump(carbonAt(burningKelvin)), TICKS)
        val loaded = Save.read(Save.write(after))

        assertEquals(railMass(after, Species.Carbon), railMass(loaded, Species.Carbon), "the lump changed")
        assertEquals(airMass(after, Fluid.CarbonDioxide), airMass(loaded, Fluid.CarbonDioxide), "the smoke changed")
        assertEquals(0L, loaded.airBalance, "the air ledger did not survive the round trip")
    }

    private companion object {
        /** The row the track runs along. */
        const val ROW = 4

        /**
         * Long enough for several chemistry passes and short enough to stay well inside the
         * five-second rule. `CHEM_PERIOD` is 8, so this is four passes.
         */
        const val TICKS = 32
    }
}
