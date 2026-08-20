package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.CARBON_IGNITION_KELVIN
import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.ThermalDecomposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decomposer as a thermostat — increment 3 of `PLAN_ambient_chemistry.md`.
 *
 * The machine used to hold a charge for a fixed number of ticks and then call `cook`, which returned
 * its input unchanged: chemistry as a function a machine called, on a tick counter, against a
 * setpoint rather than a temperature. Both halves are gone, and what is worth testing is what
 * replaced them.
 *
 * ⚠️ **Every assertion here is about *conditions*, never about a recipe.** The machine has no idea
 * what is in its chamber. It reaches a temperature and hands on whatever the charge has become, and
 * what the charge became is the ambient chemistry pass over the buffer layer — the same pass, with
 * the same arithmetic, that burns a lump on a belt. If any test here starts asserting that a
 * decomposer turns X into Y, the inversion has been undone.
 */
class ThermalDecomposerTest {

    private val grid = Grid(12, 8)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    /** The machine's centre. Its footprint is three tiles, so it covers x 4..6 and y 3..5. */
    private val centre = grid.tile(5, 4)

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * A sealed box with one decomposer in it and [charge] already in its input hopper.
     *
     * ⚠️ **The charge goes in before the state is constructed**, so `baselineCargoMass` counts it.
     * A fixture that stocks a machine afterwards is a fixture that states a leak, and the ledger
     * assertions below would be measuring the fixture rather than the machine.
     *
     * Sealed because the room's air is a reagent and a breach is a second reason for it to change —
     * and because the element heats the *tile*, so how well the box holds heat is part of what is
     * under test.
     */
    private fun withCharge(charge: Mixture, setTemperature: Int = 1200): VesselState {
        val deck = DeckArray(grid)
        for (x in 0 until grid.width) {
            deck += Hull(grid.tile(x, 0))
            deck += Hull(grid.tile(x, grid.height - 1))
        }
        for (y in 1 until grid.height - 1) {
            deck += Hull(grid.tile(0, y))
            deck += Hull(grid.tile(grid.width - 1, y))
        }
        val machine = ThermalDecomposer(centre, Direction.Right, setTemperature = setTemperature)
        deck += machine

        val buffers = BufferLayer.forDeck(grid, deck)
        buffers.put(bufferTile(grid, machine, centre, BufferRole.Input)!!, charge)

        return VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(arrayOfNulls<org.emerge.demo.outofspace.world.Segment>(grid.size).toList()),
            buffers = buffers,
            rail = RailLayer.empty(grid.size),
            creative = true,
        )
    }

    /** A charge of [species] at ambient temperature — cold, the way a delivery arrives. */
    private fun cold(species: Species, mass: Long = CHARGE): Mixture {
        val capacity = mass * species.specificHeat / Budget.CAPACITY_DIVISOR
        return Mixture.of(species to mass, energy = capacity * Temperature.AMBIENT_KELVIN)
    }

    private fun chamber(): TileIndex = centre

    private fun store(s: VesselState, role: BufferRole): Mixture? = s.inStore(centre, role)

    private fun airMass(s: VesselState, fluid: Fluid): Long {
        var sum = 0L
        for (tile in grid.tiles) sum += s.air.massOf(tile, fluid)
        return sum
    }

    private fun cargoLedger(s: VesselState): Long =
        s.inTransitMass + s.ventedMass + s.builtMass - s.extractedMass - s.baselineCargoMass

    // ── The thermostat ───────────────────────────────────────────────────────

    @Test
    fun `a charge is drawn in and then heated where it stands`() {
        val start = withCharge(cold(Species.Calcite))
        // One tick to load: the hopper empties into the chamber and nothing else happens.
        val loaded = run(start, 1)
        assertNull(store(loaded, BufferRole.Input), "the charge was not drawn in")
        assertNotNull(store(loaded, BufferRole.Inside), "the charge did not arrive in the chamber")

        val warmed = run(loaded, HEATING_TICKS)
        assertTrue(
            warmed.buffers.stuff.kelvinAt(chamber()) > loaded.buffers.stuff.kelvinAt(chamber()),
            "the element did not warm the charge",
        )
    }

    @Test
    fun `the element stops when the charge arrives, and the box then cools`() {
        // The regulation. It reads the *charge*, so the box it heats through overshoots — which is
        // what a furnace does, and is why the machine is a waste-heat source. What must be true is
        // that the element stops: once there is nothing in the chamber below the setpoint, the box
        // has no more energy coming in and starts bleeding into the room.
        val setpoint = 1200
        val settled = run(withCharge(cold(Species.Calcite), setTemperature = setpoint), SETTLING_TICKS)
        assertNotNull(store(settled, BufferRole.Product), "the charge never finished, so nothing was regulated")

        val hot = settled.deck.stuff.kelvinAt(chamber())
        val later = run(settled, HEATING_TICKS * 4).deck.stuff.kelvinAt(chamber())
        assertTrue(later < hot, "the element kept running with an empty chamber: ${hot}K then ${later}K")
        assertTrue(
            hot < setpoint * 2,
            "the box ran away rather than overshooting: ${hot}K against a ${setpoint}K setpoint",
        )
    }

    @Test
    fun `a charge that reaches the setpoint is handed on`() {
        // "At temperature" is the dwell now. `ticksPerAction` is gone, and how long a charge stays
        // is how long it takes to heat — a real quantity that depends on its mass and its room.
        val done = run(withCharge(cold(Species.Calcite)), SETTLING_TICKS)

        val product = store(done, BufferRole.Product)
        assertNotNull(product, "the charge never came out of the chamber")
        assertTrue(product.total > 0L, "the chamber handed on nothing")
        assertNull(store(done, BufferRole.Inside), "the chamber kept a copy of what it handed on")
    }

    @Test
    fun `a charge comes out as hot as the chamber left it`() {
        // The heat goes with the matter, here as everywhere. A machine that handed on a charge at
        // ambient would be destroying the energy it just spent several hundred ticks putting in.
        val done = run(withCharge(cold(Species.Calcite)), SETTLING_TICKS)
        assertNotNull(store(done, BufferRole.Product), "nothing was handed on")

        val productTile = bufferTile(grid, done.deck[centre]!!, centre, BufferRole.Product)!!
        assertTrue(
            done.buffers.stuff.kelvinAt(productTile) > Temperature.AMBIENT_KELVIN * 2,
            "the charge came out cold: ${done.buffers.stuff.kelvinAt(productTile)}K",
        )
    }

    // ── Conditions, not recipes ──────────────────────────────────────────────

    @Test
    fun `carbon held above its ignition point burns in the chamber`() {
        // The point of the whole increment: the machine did not burn this. It made a place hot, and
        // carbon in air at that temperature burns for the same reason and by the same arithmetic as
        // carbon on a belt. The only thing the decomposer contributed was the conditions.
        val start = withCharge(cold(Species.Carbon), setTemperature = 1200)
        val after = run(start, SETTLING_TICKS)

        val carbonLeft = (store(after, BufferRole.Inside)?.get(Species.Carbon) ?: 0L) +
            (store(after, BufferRole.Product)?.get(Species.Carbon) ?: 0L)
        assertTrue(carbonLeft < CHARGE, "the charge came through untouched")
        assertTrue(
            airMass(after, Fluid.CarbonDioxide) > airMass(start, Fluid.CarbonDioxide),
            "carbon left the chamber and no carbon dioxide arrived in the room",
        )
    }

    @Test
    fun `a setpoint below the ignition point hands the carbon back unburnt`() {
        // The same charge and the same machine, and nothing happens — because the player chose a
        // temperature at which nothing happens. This is the assertion that would fail if any recipe
        // crept back into the machine.
        val setpoint = CARBON_IGNITION_KELVIN - 200
        val start = withCharge(cold(Species.Carbon), setTemperature = setpoint)
        val after = run(start, SETTLING_TICKS)

        val carbonLeft = (store(after, BufferRole.Inside)?.get(Species.Carbon) ?: 0L) +
            (store(after, BufferRole.Product)?.get(Species.Carbon) ?: 0L)
        assertEquals(CHARGE, carbonLeft, "carbon burned below its ignition point")
        assertEquals(
            airMass(start, Fluid.CarbonDioxide),
            airMass(after, Fluid.CarbonDioxide),
            "the room gained carbon dioxide from a cold chamber",
        )
    }

    @Test
    fun `a fire in a hopper closes both ledgers`() {
        // The buffer layer joined the chemistry sweep in this increment, and it is the same cargo
        // ledger the rail is — so a chamber that burns its charge has to book the crossing exactly
        // as a belt does. If `cargoMass` and the sweep ever disagreed about whether a hopper counts,
        // this is where it would show.
        val start = withCharge(cold(Species.Carbon), setTemperature = 1200)
        val after = run(start, SETTLING_TICKS)

        // Against the *start*, not against zero: a room already has carbon dioxide in it, so
        // "there is some" is a precondition that can never fail and would let this pass by
        // burning nothing at all.
        assertTrue(
            airMass(after, Fluid.CarbonDioxide) > airMass(start, Fluid.CarbonDioxide),
            "nothing burned, so this proves nothing",
        )
        assertEquals(0L, after.airBalance, "the air ledger is out by ${after.airBalance}")
        assertEquals(0L, cargoLedger(after), "the cargo ledger is out by ${cargoLedger(after)}")
    }

    // ── State ────────────────────────────────────────────────────────────────

    @Test
    fun `the setpoint is the whole of what a decomposer saves`() {
        // `carry` and `progress` went with the tick counter. An older file's copies are simply not
        // read, the same disposal the extractor's did — what must survive is the one number the
        // player actually chose.
        val saved = Save.read(Save.write(withCharge(cold(Species.Calcite), setTemperature = 1150)))
        val machine = saved.deck[centre]
        assertTrue(machine is ThermalDecomposer, "the machine did not survive the round trip")
        assertEquals(1150, machine.setTemperature, "the setpoint did not survive the round trip")
    }

    private companion object {
        /** Long enough for the element to have visibly moved the charge, and no longer. */
        const val HEATING_TICKS = 60

        /**
         * Long enough for the charge to reach a 1200 K setpoint and be handed on.
         *
         * This is the dwell, and it is now a *measured* quantity rather than a constant: the element
         * heats the tile, the firebrick casing takes most of it first, and the charge equilibrates
         * with the box around it. Comfortably inside the five-second rule — this is a twelve-by-eight
         * grid with one machine on it.
         */
        const val SETTLING_TICKS = 900

        /**
         * The charge, deliberately small.
         *
         * ⚠️ **The dwell scales with the mass**, because [org.emerge.demo.outofspace.world.BUFFER_CONTACT_CONDUCTANCE]
         * is a contact rather than a time — a heavier charge has more to warm through the same
         * contact. Ten kilograms is a fraction of a belt-load and takes some seven hundred ticks;
         * a full four-tonne chamber takes long enough that no test may wait for it. That is a real
         * property of the machine and not a fixture convenience, and it is the number to look at
         * first if a decomposer ever feels slow in play.
         */
        val CHARGE = 10L * Budget.KILOGRAM
    }
}
