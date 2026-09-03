package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.CARBON_IGNITION_KELVIN
import org.emerge.demo.outofspace.chem.DECOMPOSITIONS
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
import org.emerge.demo.outofspace.world.machine.MACHINE_BUFFER_CAP
import org.emerge.demo.outofspace.world.machine.Furnace
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
class FurnaceTest {

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
     * The world at the moment the chamber hands its charge on, or a failure saying it never did.
     *
     * Waits on the **event** rather than on a tick count, because the dwell is a measured quantity
     * now — it depends on the charge's mass, the element's power and the room — and a test that
     * pinned it to a number would be re-pinned by every tuning pass. It also lets the assertions
     * about a hot charge run while it is still hot: left sitting in the output store the charge
     * cools into the room, exactly as it should, and a fixed run length would be measuring that.
     */
    private fun runUntilHandedOn(state: VesselState): VesselState {
        var s = state
        repeat(DWELL_TICKS) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (s.inStore(centre, BufferRole.Product) != null) return s
        }
        throw AssertionError("the charge was never handed on within $DWELL_TICKS ticks")
    }

    /**
     * The same wait, reporting **how long** it took — what the dwell tests compare.
     *
     * Separate from [runUntilHandedOn] rather than folded into it, because the two want different
     * things out of the same loop and a function returning a pair of them would be read wrong by
     * whichever caller cared about the other half.
     */
    private fun ticksUntilHandedOn(state: VesselState): Int {
        var s = state
        repeat(DWELL_TICKS) { tick ->
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (s.inStore(centre, BufferRole.Product) != null) return tick + 1
        }
        return 0
    }

    /**
     * A sealed box with one decomposer in it and [charge] already in its input hopper.
     *
     * ⚠️ **The charge goes in before the state is constructed**, so `baselineCargoMass` counts it.
     * A fixture that stocks a machine afterwards is a fixture that states a leak, and the ledger
     * assertions below would be measuring the fixture rather than the machine.
     *
     * Sealed because the room's air is a reagent and a breach is a second reason for it to change —
     * and because the charge bleeds its heat into the box and the box into the room, so how well
     * the vessel holds heat is part of what is under test.
     */
    private fun withCharge(charge: Mixture, setTemperature: Int = 1200, dwellTicks: Int = 0): VesselState {
        val deck = DeckArray(grid)
        for (x in 0 until grid.width) {
            deck += Hull(grid.tile(x, 0))
            deck += Hull(grid.tile(x, grid.height - 1))
        }
        for (y in 1 until grid.height - 1) {
            deck += Hull(grid.tile(0, y))
            deck += Hull(grid.tile(grid.width - 1, y))
        }
        val machine = Furnace(centre, Direction.Right, setTemperature = setTemperature, dwellTicks = dwellTicks)
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

    /** Everything in every buffer store on the grid, by species. */
    private fun bufferMass(s: VesselState, species: Species): Long {
        var sum = 0L
        for (tile in grid.tiles) sum += s.buffers.stuff[tile, species]
        return sum
    }

    /** A charge of several things at once, carrying enough heat to be at [kelvin]. */
    private fun lumpAt(kelvin: Int, vararg parts: Pair<Species, Long>): Mixture {
        var capacity = 0L
        for ((species, mass) in parts) capacity += mass * species.specificHeat / Budget.CAPACITY_DIVISOR
        return Mixture.of(*parts, energy = capacity * kelvin)
    }

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
        // Still in the chamber and warmer than it went in. The charge is a full hopper on purpose:
        // a light one is at temperature within a tick or two and there is no ramp left to watch.
        assertNotNull(warmed.inStore(centre, BufferRole.Inside), "the charge was handed on before it could be watched")
        assertTrue(
            warmed.buffers.stuff.kelvinAt(chamber()) > loaded.buffers.stuff.kelvinAt(chamber()),
            "the element did not warm the charge",
        )
    }

    @Test
    fun `the element never drives the charge past the setpoint`() {
        // The regulation, and it is by construction: the element may put in at most the gap the
        // charge still has. Without that cap a light charge would be blown hundreds of kelvin past
        // its setpoint by one tick of an element sized for a full hopper — and it would react on
        // the way, so the overshoot would not merely be untidy, it would change what came out.
        val setpoint = 1200
        val done = runUntilHandedOn(withCharge(cold(Species.Calcite, LIGHT_CHARGE), setTemperature = setpoint))

        val productTile = bufferTile(grid, done.deck[centre]!!, centre, BufferRole.Product)!!
        assertTrue(
            done.buffers.stuff.kelvinAt(productTile) <= setpoint,
            "the charge overshot: ${done.buffers.stuff.kelvinAt(productTile)}K against a ${setpoint}K setpoint",
        )
    }

    @Test
    fun `the heat leaks into the machine rather than staying in the charge for ever`() {
        // The element is *in* the chamber, so the charge is what gets hot and the firebrick is what
        // the heat then bleeds into — slowly, at the buffer's own contact conductance. A machine
        // that stayed at ambient while its contents glowed would mean the casing was not thermally
        // connected to what it holds, which is the bug this arrangement could plausibly have.
        val done = runUntilHandedOn(withCharge(cold(Species.Calcite)))
        val warmed = run(done, HEATING_TICKS * 4)

        assertTrue(
            warmed.deck.stuff.kelvinAt(chamber()) > Temperature.AMBIENT_KELVIN,
            "the machine never felt the charge it was holding",
        )
    }

    @Test
    fun `a charge that reaches the setpoint is handed on`() {
        // "At temperature" is the dwell now. `ticksPerAction` is gone, and how long a charge stays
        // is how long it takes to heat — a real quantity that depends on its mass and its room.
        val done = runUntilHandedOn(withCharge(cold(Species.Calcite)))

        val product = store(done, BufferRole.Product)
        assertNotNull(product, "the charge never came out of the chamber")
        assertTrue(product.total > 0L, "the chamber handed on nothing")
        assertNull(store(done, BufferRole.Inside), "the chamber kept a copy of what it handed on")
    }

    @Test
    fun `a charge comes out as hot as the chamber left it`() {
        // The heat goes with the matter, here as everywhere. A machine that handed on a charge at
        // ambient would be destroying the energy it just spent several hundred ticks putting in.
        val done = runUntilHandedOn(withCharge(cold(Species.Calcite)))

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
        // ⛔ **Into the store, not into the room.** A hopper never off-gasses now — that is what
        // makes a store a tank — so a furnace's gaseous product comes out of its output port as
        // cargo the player can route, rather than out of its casing as something they breathe. See
        // `PLAN_fluid_thrusters.md` §2.1. Put a valve on the output run and the room gets it.
        assertTrue(
            bufferMass(after, Species.CarbonDioxide) > bufferMass(start, Species.CarbonDioxide),
            "carbon left the chamber and no carbon dioxide took its place",
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
            bufferMass(after, Species.CarbonDioxide) > bufferMass(start, Species.CarbonDioxide),
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
        assertTrue(machine is Furnace, "the machine did not survive the round trip")
        assertEquals(1150, machine.setTemperature, "the setpoint did not survive the round trip")
    }


    // ── Increment 4: the table, in the machine it was built for ──────────────

    @Test
    fun `limestone calcines into quicklime and the carbon dioxide comes out with it`() {
        // The reaction this machine's documentation has promised since before it could happen.
        // CaCO3 -> CaO + CO2: the lime stays in the chamber and leaves by the belt, the gas leaves
        // by the room, and the machine's one output port is correct precisely because of that.
        val calcite = DECOMPOSITIONS.first { it.reactant == Species.Calcite }
        val start = withCharge(cold(Species.Calcite, CALCINING_CHARGE), setTemperature = calcite.onsetKelvin + 250)
        val after = run(start, SETTLING_TICKS)

        val limeMade = bufferMass(after, Species.Lime)
        assertTrue(limeMade > 0L, "no lime was made")
        assertTrue(
            bufferMass(after, Species.Calcite) < CALCINING_CHARGE,
            "the limestone came through untouched",
        )
        // ⛔ **Into the store, not into the room.** A hopper never off-gasses now — that is what
        // makes a store a tank — so a furnace's gaseous product comes out of its output port as
        // cargo the player can route, rather than out of its casing as something they breathe. See
        // `PLAN_fluid_thrusters.md` §2.1. Put a valve on the output run and the room gets it.
        assertTrue(
            bufferMass(after, Species.CarbonDioxide) > bufferMass(start, Species.CarbonDioxide),
            "lime appeared and no carbon dioxide came with it",
        )

        // And in the proportion the formula says, not merely in the right direction: CaCO3 is 100,
        // CaO is 56. A reaction that ran at all but split wrong would pass every assertion above.
        val calcined = CALCINING_CHARGE - bufferMass(after, Species.Calcite)
        val expectedLime = calcined * Species.Lime.molarMass / Species.Calcite.molarMass
        assertTrue(
            limeMade in (expectedLime - TICKS)..(expectedLime + TICKS),
            "$calcined of limestone yielded $limeMade of lime, not about $expectedLime",
        )
    }

    // ── The second dial: how long, not just how hot ──────────────────────────

    @Test
    fun `with no dwell set a charge is handed on the moment it is at temperature`() {
        // The default is the old behaviour exactly, and that is the point of it being zero: a
        // decomposer nobody has tuned behaves as it always did, and the dial is opt-in.
        val done = runUntilHandedOn(withCharge(cold(Species.Calcite, LIGHT_CHARGE), setTemperature = 1200))
        assertNotNull(store(done, BufferRole.Product), "a charge with no dwell was not handed on")
    }

    @Test
    fun `a dwell holds the charge after it is at temperature`() {
        // Two dials, and this is the second one doing something the first cannot. The charge reaches
        // the setpoint at the same tick either way; what differs is how long it then sits there.
        val dwell = 200
        val plain = withCharge(cold(Species.Calcite, LIGHT_CHARGE), setTemperature = 1200)
        val patient = withCharge(
            cold(Species.Calcite, LIGHT_CHARGE),
            setTemperature = 1200,
            dwellTicks = dwell,
        )

        val quick = ticksUntilHandedOn(plain)
        val slow = ticksUntilHandedOn(patient)

        assertTrue(quick > 0, "the plain charge was never handed on at all")
        assertTrue(slow > 0, "the held charge was never handed on — is the dwell counting down?")

        // ⚠️ **At least, not exactly, and the gap is the interesting part.** A 200-tick dwell takes
        // rather longer than 200 ticks of wall clock, because calcining is endothermic: the reaction
        // keeps knocking the charge back below its setpoint, and the ticks the element spends
        // climbing back do not count. The dwell is a residence time *at temperature*, so a reaction
        // that fights the element makes the charge sit there longer than the dial says. That is the
        // machine being honest, and pinning it to an exact number would be pinning the enthalpy of
        // calcite to a tick count.
        assertTrue(
            slow - quick >= dwell,
            "a dwell of $dwell ticks held the charge only ${slow - quick} ticks longer",
        )
    }

    @Test
    fun `a longer dwell converts more of the charge`() {
        // Why anybody would set it. The reaction is asymptotic, so there is no moment at which a
        // charge is finished — residence time is what buys conversion, and this is the assertion that
        // the two dials are not redundant.
        fun limeFrom(dwell: Int): Long {
            val start = withCharge(cold(Species.Calcite, CALCINING_CHARGE), setTemperature = 1400, dwellTicks = dwell)
            return bufferMass(run(start, 1200), Species.Lime)
        }

        val brief = limeFrom(0)
        val patient = limeFrom(600)
        assertTrue(brief > 0L, "nothing calcined even before the dwell mattered")
        assertTrue(
            patient > brief,
            "holding the charge 600 ticks longer yielded $patient of lime against $brief — the dwell is not " +
                "buying any conversion",
        )
    }

    @Test
    fun `the dwell does not run while the charge is still heating`() {
        // What makes it a *residence* time rather than a delay. A charge below its setpoint is not
        // being held at anything, so the clock must not be running — otherwise a cold charge in a
        // slow machine would serve its whole dwell on the way up and leave the instant it arrived.
        val ticks = 400
        val cool = run(withCharge(cold(Species.Calcite, CHARGE), setTemperature = 3000, dwellTicks = 50), ticks)
        val machine = cool.deck[centre] as Furnace

        assertTrue(
            cool.buffers.stuff.kelvinAt(chamber()) < 3000,
            "the fixture is wrong: the charge reached 3000 K, so there was no ramp to test",
        )
        assertEquals(0, machine.heldTicks, "the dwell ran while the charge was still climbing")
    }

    @Test
    fun `both dials survive a save`() {
        val saved = Save.read(
            Save.write(withCharge(cold(Species.Calcite), setTemperature = 1150, dwellTicks = 250)),
        )
        val machine = saved.deck[centre] as Furnace
        assertEquals(1150, machine.setTemperature, "the setpoint did not survive the round trip")
        assertEquals(250, machine.dwellTicks, "the dwell did not survive the round trip")
    }

    @Test
    fun `a charge part way through its dwell survives a save`() {
        // The half of the state that is easy to forget. Dropping `held` would silently restart every
        // hold on every load, which is invisible except as a machine that is mysteriously slower in a
        // reloaded game than it was in a fresh one.
        var s = withCharge(cold(Species.Calcite, LIGHT_CHARGE), setTemperature = 1200, dwellTicks = 5_000)
        var held = 0
        repeat(2_000) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            held = (s.deck[centre] as Furnace).heldTicks
        }
        assertTrue(held > 0, "the charge never started its dwell, so there is nothing to round trip")

        val saved = Save.read(Save.write(s))
        assertEquals(held, (saved.deck[centre] as Furnace).heldTicks, "the dwell restarted on load")
    }

    @Test
    fun `a setpoint between the two carbonates cracks one and leaves the other`() {
        // Heat as a separator, which is what makes the dial a decision. The same mixed charge at one
        // temperature is a magnesite process and at another is a magnesite-and-calcite process, and
        // nothing anywhere is filtering by species.
        val magnesite = DECOMPOSITIONS.first { it.reactant == Species.Magnesite }
        val calcite = DECOMPOSITIONS.first { it.reactant == Species.Calcite }
        val between = (magnesite.onsetKelvin + calcite.onsetKelvin) / 2

        val mixed = lumpAt(
            Temperature.AMBIENT_KELVIN,
            Species.Magnesite to CALCINING_CHARGE / 2,
            Species.Calcite to CALCINING_CHARGE / 2,
        )
        val after = run(withCharge(mixed, setTemperature = between), SETTLING_TICKS)

        assertTrue(bufferMass(after, Species.Periclase) > 0L, "the magnesite did not crack")
        assertEquals(
            CALCINING_CHARGE / 2,
            bufferMass(after, Species.Calcite),
            "the calcite cracked too, at a temperature it should have ignored",
        )
        assertEquals(0L, bufferMass(after, Species.Lime), "lime appeared below calcite's onset")
    }

    @Test
    fun `calcining cools the charge it happens to`() {
        // The enthalpy, which increment 1 deliberately left out and the table is the answer to.
        // Calcining takes more energy per kilogram than limestone holds at its own calcining
        // temperature, so a charge cannot run away with it: it cools, drops under its onset and
        // waits for the element. That is the loop the decomposer exists to fight, and the reason the
        // machine is a heat sink rather than a timer.
        val calcite = DECOMPOSITIONS.first { it.reactant == Species.Calcite }
        val setpoint = calcite.onsetKelvin + 250

        val hot = lumpAt(setpoint, Species.Calcite to CALCINING_CHARGE)
        // A setpoint of zero, so the element never runs: the charge arrives already hot and the
        // only things that can change its heat are the reaction and the room it stands in.
        val after = run(withCharge(hot, setTemperature = 0), TICKS)

        assertTrue(bufferMass(after, Species.Lime) > 0L, "nothing calcined, so nothing was paid for")
        assertTrue(
            after.buffers.stuff.kelvinAt(chamber()) < setpoint,
            "calcining a charge did not cool it: still ${after.buffers.stuff.kelvinAt(chamber())}K",
        )
    }

    @Test
    fun `calcining closes both ledgers`() {
        val calcite = DECOMPOSITIONS.first { it.reactant == Species.Calcite }
        val after = run(
            withCharge(cold(Species.Calcite, CALCINING_CHARGE), setTemperature = calcite.onsetKelvin + 250),
            SETTLING_TICKS,
        )

        assertTrue(bufferMass(after, Species.Lime) > 0L, "nothing calcined, so this proves nothing")
        assertEquals(0L, after.airBalance, "the air ledger is out by ${after.airBalance}")
        assertEquals(0L, cargoLedger(after), "the cargo ledger is out by ${cargoLedger(after)}")
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
        /** A handful of chemistry passes — `CHEM_PERIOD` is 8. */
        const val TICKS = 32

        /**
         * How long the dwell tests are willing to wait for a hand-off.
         *
         * Longer than [SETTLING_TICKS] because a dwell is *meant* to hold a charge past the point the
         * other tests care about, and a wait sized for a machine with no dwell would fail every one
         * of them for the reason they exist.
         */
        const val DWELL_TICKS = 4_000

        const val SETTLING_TICKS = 900

        /**
         * The charge: a full hopper, so that heating it is something with a duration.
         *
         * ⚠️ **The dwell scales with the mass**, because the element's power is fixed and a heavier
         * charge has more to warm. `HEATER_POWER` is sized against exactly this — a full chamber
         * climbing a couple of kelvin a tick — so the ramp here is a few hundred ticks and can be
         * watched. That is a real property of the machine rather than a fixture convenience.
         */
        val CHARGE = MACHINE_BUFFER_CAP

        /**
         * A light charge, which reaches its setpoint almost at once.
         *
         * The interesting case for the regulation: one tick of an element sized for [CHARGE] carries
         * enough energy to take this far past the temperature the player asked for, so it is the
         * charge that would show an uncapped element up.
         */
        val LIGHT_CHARGE = 10L * Budget.KILOGRAM

        /**
         * The charge for the calcining tests, and small for a reason.
         *
         * Calcining is strongly endothermic — more per kilogram than the rock holds at its own
         * onset — so a big charge spends the whole test being reheated a fraction at a time. A light
         * one reaches temperature at once and gets on with the reaction, which is what these are
         * about.
         */
        val CALCINING_CHARGE = 10L * Budget.KILOGRAM
    }
}
