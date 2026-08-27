package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.CARBON_IGNITION_KELVIN
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
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


    /** The same, for iron — well above its onset and below carbon's, where only one runs. */
    private val scalingKelvin = 1400

    /** Above steel's 1000 K decarburising onset, and well up the rate curve like the other two. */
    private val decarburisingKelvin = 1400

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

    /**
     * Both carbon oxides, in both media at once — everything the burnt carbon can now be inside.
     *
     * ⚠️ **Two things changed under these tests at once, and each on its own looks like a leak.**
     *
     * A reaction's products stay in the layer that made them and [org.emerge.demo.outofspace.world
     * .offGas] releases them afterwards, so at any moment some of a product is still in the lump and
     * the rest is in the room. Counting only the air measures how far the off-gassing has got, which
     * is not what these tests are about.
     *
     * And because the CO2 now sits in the lump for a moment instead of leaving the instant it is
     * made, **the carbon standing next to it reduces some of it to carbon monoxide** — `CO2 + C ->
     * 2 CO`, the row that has been in `REDUCTIONS` all along and could never fire on a burning lump
     * because the CO2 was gone before the carbon could reach it. A hot carbon bed making CO out of
     * its own exhaust is right, and it is a *consequence* of the containment rather than anything
     * anybody wrote. It is also why "the CO2 weighs its own parts" is no longer the identity to
     * check: CO2 is an intermediate now, not a terminus. The oxides together still weigh exactly
     * what went into them, to the microgram.
     */
    private fun carbonOxidesAnywhere(s: VesselState): Long =
        airMass(s, Fluid.CarbonDioxide) + railMass(s, Species.CarbonDioxide) +
            airMass(s, Fluid.CarbonMonoxide) + railMass(s, Species.CarbonMonoxide)

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

    // ── Cargo has a temperature ──────────────────────────────────────────────

    @Test
    fun `a hot lump on a belt cools into the world around it`() {
        // Until cargo became a Body this was the one kind of matter aboard with no temperature to
        // speak of: a packet kept whatever energy it was minted with for its whole journey. It
        // conducted while it sat in a machine's buffer and stopped the moment it was set down on a
        // belt, which is not a distinction anything physical makes.
        val start = withLump(carbonAt(Temperature.AMBIENT_KELVIN * 2))
        val here = grid.tile(5, ROW)

        val before = start.rail.stuff.kelvinAt(here)
        val after = run(start, COOLING_TICKS).rail.stuff.kelvinAt(here)

        assertTrue(after < before, "the lump held its heat with cold track under it: ${before}K then ${after}K")
        assertTrue(after > Temperature.AMBIENT_KELVIN, "it equalised instantly rather than cooling over time")
    }

    @Test
    fun `a fire that has caught keeps itself alight`() {
        // ⚠️ **This test used to assert the opposite, and the change is the point of increment 4.**
        //
        // While the reaction was athermal a lump lit just over its ignition point shed heat into the
        // track and the room, dropped under the onset and went out — so fire had to be *sustained*.
        // Burning carbon is exothermic and now says so: about 33 MJ/kg against the half-megajoule it
        // takes to hold a kilogram at its ignition point. A fire that went out while releasing
        // seventy times what it needed to stay lit would not be a fire.
        //
        // So the loop runs the other way. A lump that catches heats itself, burns faster for being
        // hotter, and keeps going until something runs out.
        val start = withLump(carbonAt(CARBON_IGNITION_KELVIN + 60))
        val here = grid.tile(5, ROW)

        val lit = run(start, TICKS)
        assertTrue(railMass(lit, Species.Carbon) < railMass(start, Species.Carbon), "it never caught")

        val later = run(lit, TICKS)
        assertTrue(
            later.rail.stuff.kelvinAt(here) > start.rail.stuff.kelvinAt(here),
            "a lump that caught did not warm itself: ${start.rail.stuff.kelvinAt(here)}K then " +
                "${later.rail.stuff.kelvinAt(here)}K",
        )
        assertTrue(
            railMass(later, Species.Carbon) < railMass(lit, Species.Carbon),
            "the fire stalled once it was properly alight",
        )
    }

    @Test
    fun `a fire in a sealed room goes out when the room runs out of air`() {
        // What stops it, now that cooling does not. The oxygen is the bound — decision 2, and the
        // reason a sealed room is a different place to put something flammable than an open one.
        // A trace of air burns a trace of carbon and then nothing, however hot the lump is.
        val trace = 20L * Budget.GRAM
        val start = starved(carbonAt(burningKelvin), oxygen = trace)
        val after = run(start, TICKS * 4)

        assertTrue(railMass(after, Species.Carbon) > 0L, "the whole lump went, so nothing was starved")
        assertTrue(airMass(after, Fluid.Oxygen) < trace / 2L, "the fire did not use the air it had")

        // Out, and staying out: once it has settled, a further run of the same length takes no more
        // carbon at all, because there is no more oxygen to take it with.
        //
        // ⚠️ **Settled first, and then compared — not compared against the moment the fire stopped
        // being interesting.** Asked the other way round this pins *which tick* the last microgram
        // of a 20 kg lump lands on, and that moved by one round when the materials' time constants
        // became physical (`MaterialThermalTest`): the fire took 7.5 g, then one further microgram,
        // then nothing, for ever. Measured. So the exact equality is kept — it is a stronger claim
        // than a tolerance would be — and it is asked of two rounds that are both after the end.
        val settled = run(after, TICKS * 4)
        val later = run(settled, TICKS * 4)
        assertEquals(
            railMass(settled, Species.Carbon),
            railMass(later, Species.Carbon),
            "carbon kept burning in a room with no oxygen left in it",
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
        val oxidesMade = carbonOxidesAnywhere(after) - carbonOxidesAnywhere(start)

        assertEquals(carbonBurned + oxygenUsed, oxidesMade, "the carbon oxides do not weigh their own parts")

        // ⚠️ **A bracket, where this used to be a line, and the reason is not slack.**
        //
        // `carbonBurned * 32 / 12` was the exact answer while every atom of carbon that left the
        // belt had taken a whole O2 with it. It no longer does: some of it leaves by the Boudouard
        // door instead, taking its oxygen from CO2 that is already in the lump rather than from the
        // room, so the air gives up *less* than the combustion line asks for. Both reactions are
        // running and the ratio is a blend of the two.
        //
        // ⛔ **Not fixable by picking a cooler lump.** Boudouard starts at 973 K and carbon ignites
        // at 700, which looks like a 273 K window and is not one: combustion is exothermic, so a
        // lump lit anywhere in that window heats itself out of the top of it. Measured — at 900 K
        // the monoxide is smaller and it is still there.
        //
        // ⛔ **And not fixable by subtracting the monoxide off.** That arithmetic is correct and it
        // is `Reduction.split` restated in the test, which is the one thing `massAtReducedDensity`'s
        // documentation says not to do: a test that recomputes what it is testing against inherits
        // its bugs. The exact statement is the closure above, which holds to the microgram; what is
        // left for this to say is that the oxygen sits between the two lines the two reactions draw
        // — every atom of carbon took at least a CO's worth of oxygen from somewhere and at most a
        // CO2's worth from the room — and a mis-indexed write or a dropped factor leaves that band
        // immediately.
        val ifAllBurnedToDioxide = carbonBurned * Species.Oxygen.molarMass / Species.Carbon.molarMass
        val ifAllWentToMonoxide = ifAllBurnedToDioxide / 2
        assertTrue(
            oxygenUsed in ifAllWentToMonoxide..ifAllBurnedToDioxide,
            "the world ran off the stoichiometric band: $oxygenUsed is not between " +
                "$ifAllWentToMonoxide and $ifAllBurnedToDioxide",
        )
        // No other species moved anywhere. This is the one that catches a reaction writing into the
        // wrong ordinal, which arithmetic tests cannot see because they never index a field. Both
        // carbon oxides are on the exempt list: the belt is where a reaction puts them now, and the
        // room is only where they end up. See [carbonOxidesAnywhere].
        for (s in Species.ALL) {
            if (s == Species.Carbon || s == Species.CarbonDioxide || s == Species.CarbonMonoxide) continue
            assertEquals(railMass(start, s), railMass(after, s), "$s changed on the belt")
        }
        for (f in Fluid.ALL) {
            if (f == Fluid.Oxygen || f == Fluid.CarbonDioxide || f == Fluid.CarbonMonoxide) continue
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

        // ⚠️ **The lump does not end up cooler, and since increment 4 it must not.** It hands over a
        // share of its heat with every gram that leaves — that part is unchanged — but burning is
        // exothermic and puts back some thirty times more than the departing carbon took with it. So
        // the carried-heat rule cannot be observed as "the lump got colder" any more; it has to be
        // asked of a reaction that releases nothing.
        //
        // Serpentine giving up its water is that reaction, near enough: it is endothermic, so both
        // effects point the same way and neither can hide the other.
        val wet = withLump(lumpAt(1200, Species.Serpentine to 20L * Budget.KILOGRAM))
        val dried = run(wet, TICKS)
        assertTrue(
            railMass(dried, Species.Serpentine) < railMass(wet, Species.Serpentine),
            "the serpentine did not give up its water",
        )
        assertTrue(
            dried.rail.stuff.energyAt(grid.tile(5, ROW)) < wet.rail.stuff.energyAt(grid.tile(5, ROW)),
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


    // ── Increment 2: the other direction, and contention ─────────────────────

    /** A lump of whatever, carrying enough heat to be at [kelvin]. */
    private fun lumpAt(kelvin: Int, vararg parts: Pair<Species, Long>): Mixture {
        var capacity = 0L
        for ((species, mass) in parts) capacity += mass * species.specificHeat / Budget.CAPACITY_DIVISOR
        return Mixture.of(*parts, energy = capacity * kelvin)
    }

    /**
     * The same sealed box with almost no air in it — a trace of oxygen over the whole run of track.
     *
     * This is what makes contention *happen* rather than merely be implemented: with a room full of
     * air both reactions get everything they ask for and the apportionment is never consulted, so a
     * test against ambient air would pass just as well with the demand pass deleted.
     */
    private fun starved(lump: Mixture, oxygen: Long): VesselState {
        val base = withLump(lump)
        val mass = MassArray(grid.size)
        mass[grid.tile(5, ROW), Fluid.Oxygen] = oxygen
        val air = Stuff.gas(mass)
        return base.copy(air = air, baselineAirMass = air.totalMass, baselineAirEnergy = air.totalEnergy)
    }

    @Test
    fun `hot iron on a belt takes oxygen out of the room and keeps it`() {
        // The crossing increment 1 never ran: mass leaves the atmosphere and stays in the solid. A
        // reaction whose product is not a fluid makes the *cargo* heavier, which is the direction
        // `gasBecameSolid` exists for.
        val start = withLump(lumpAt(scalingKelvin, Species.Iron to 20L * Budget.KILOGRAM))
        val after = run(start, TICKS)

        val ironLost = railMass(start, Species.Iron) - railMass(after, Species.Iron)
        assertTrue(ironLost > 0L, "the iron did not scale")

        val scaleMade = railMass(after, Species.Hematite) - railMass(start, Species.Hematite)
        val oxygenUsed = airMass(start, Fluid.Oxygen) - airMass(after, Fluid.Oxygen)

        assertTrue(oxygenUsed > 0L, "scale appeared without the room losing any oxygen")
        assertEquals(ironLost + oxygenUsed, scaleMade, "the scale does not weigh its own parts")
        // ⚠️ **To within one unit per pass, not exactly**, and that is the honest statement. Each
        // pass floors its own oxygen from its own iron, and a sum of floors is not the floor of a
        // sum — carbon happens to divide evenly at these masses and iron does not. The direction is
        // what matters: flooring can only ever take *less* oxygen than the ratio calls for, never
        // more, so the drift is a microgram of iron that did not quite finish scaling rather than
        // oxygen conjured out of the room.
        val expected = ironLost * (3L * Species.Oxygen.molarMass) / (4L * Species.Iron.molarMass)
        assertTrue(
            oxygenUsed <= expected && expected - oxygenUsed <= TICKS / 8,
            "the world ran off the stoichiometric line for 4Fe + 3O₂ → 2Fe₂O₃: $oxygenUsed against $expected",
        )
    }

    /**
     * ⛔ **Hull salvage becomes rail iron, and what it costs is the room's oxygen.**
     *
     * `Fe₉₉C + O₂ → 99 Fe + CO₂`, which is decarburisation and which is what a Bessemer converter
     * does. It is the one way back out of an alloy in the game, and it is what makes a marked hull
     * worth anything to a player who wants track: steel is not iron by any fraction, so without this
     * row salvaged plate can only ever become more plate.
     *
     * ⚠️ **Asserted as "some iron appeared", not as a yield**, and deliberately. The iron this makes
     * is standing in hot air well above [IRON_OXIDATION_KELVIN], so the rust row is competing for the
     * same oxygen and some of the iron goes straight back to scale. Pinning a number here would be
     * pinning the outcome of that race, which is a tuning fact and not the contract.
     */
    @Test
    fun `steel in a hot airy room gives its carbon up and leaves iron behind`() {
        val start = withLump(lumpAt(decarburisingKelvin, Species.Steel to 20L * Budget.KILOGRAM))
        val after = run(start, TICKS)

        val steelLost = railMass(start, Species.Steel) - railMass(after, Species.Steel)
        assertTrue(steelLost > 0L, "the steel did not decarburise")

        val ironMade = railMass(after, Species.Iron) - railMass(start, Species.Iron)
        assertTrue(ironMade > 0L, "steel was consumed and no iron came out of it")

        val oxygenUsed = airMass(start, Fluid.Oxygen) - airMass(after, Fluid.Oxygen)
        assertTrue(oxygenUsed > 0L, "carbon left the steel without the room losing any oxygen")

        // ⚠️ Iron plus its scale, because the rust row takes a share of the iron the moment it
        // exists. The two together are what the steel actually turned into.
        val hematiteMade = railMass(after, Species.Hematite) - railMass(start, Species.Hematite)
        assertTrue(
            ironMade + hematiteMade > steelLost * 90L / 100L,
            "the iron went somewhere that is neither iron nor scale: " +
                "lost $steelLost, made $ironMade iron and $hematiteMade scale",
        )
    }

    /**
     * ⚠️ **And it needs the oxygen, which is the whole point of the row.** In a vacuum the carbon has
     * nowhere to go, so the alloy is simply hot metal — a player who wants their hull back as iron
     * has to spend air on it.
     */
    @Test
    fun `steel in a vacuum keeps its carbon however hot it is`() {
        val start = starved(lumpAt(decarburisingKelvin, Species.Steel to 20L * Budget.KILOGRAM), oxygen = 0L)
        val after = run(start, TICKS)

        assertEquals(
            railMass(start, Species.Steel),
            railMass(after, Species.Steel),
            "steel decarburised with no oxygen to carry the carbon away",
        )
    }

    @Test
    fun `cold steel keeps its carbon`() {
        // A vessel's hull is made of this. If ambient were enough, every plate in the game would be
        // quietly turning into iron and scale while the player watched something else.
        val start = withLump(lumpAt(Temperature.AMBIENT_KELVIN, Species.Steel to 20L * Budget.KILOGRAM))
        val after = run(start, TICKS)

        assertEquals(
            railMass(start, Species.Steel),
            railMass(after, Species.Steel),
            "steel decarburised at room temperature",
        )
    }

    @Test
    fun `cold iron does not rust`() {
        // A vessel is made of iron. If ambient temperature were enough, every belt in the game
        // would quietly turn into ore while the player watched something else.
        val start = withLump(lumpAt(Temperature.AMBIENT_KELVIN, Species.Iron to 20L * Budget.KILOGRAM))
        val after = run(start, TICKS)

        assertEquals(railMass(start, Species.Iron), railMass(after, Species.Iron), "iron rusted at room temperature")
        assertEquals(0L, railMass(after, Species.Hematite), "scale formed on cold iron")
    }

    @Test
    fun `iron scaling closes both ledgers`() {
        // The half of the conservation story that increment 1 could not tell, because nothing ran
        // this way. The air ledger has to hear that the atmosphere shrank on purpose and the cargo
        // ledger has to hear that it grew — book one and both are wrong in opposite directions,
        // which reads as two unrelated leaks.
        val start = withLump(lumpAt(scalingKelvin, Species.Iron to 20L * Budget.KILOGRAM))
        val after = run(start, TICKS)

        assertTrue(railMass(after, Species.Hematite) > 0L, "nothing scaled, so this proves nothing")

        assertEquals(0L, after.airBalance, "the air ledger is out by ${after.airBalance}")
        assertEquals(
            0L,
            after.inTransitMass + after.ventedMass + after.builtMass -
                after.extractedMass - after.baselineCargoMass,
            "the cargo ledger is out",
        )
    }

    @Test
    fun `two reactions in one tile never take more oxygen than is there`() {
        // The property the demand pass exists for. Both reactants on one tile, both well above
        // their onsets, and between them they want far more oxygen than the room holds. Resolved by
        // iteration order this would hand the lot to carbon and then let iron take oxygen that no
        // longer existed — which the air ledger reports not as a reaction but as a leak.
        val trace = 5L * Budget.GRAM
        val start = starved(
            lumpAt(burningKelvin, Species.Carbon to 20L * Budget.KILOGRAM, Species.Iron to 20L * Budget.KILOGRAM),
            oxygen = trace,
        )
        val after = run(start, TICKS)

        for (tile in grid.tiles) {
            assertTrue(after.air.massOf(tile, Fluid.Oxygen) >= 0L, "a tile went oxygen-negative")
        }
        assertTrue(airMass(after, Fluid.Oxygen) < trace, "a starved tile with two reactions in it used nothing")
        assertEquals(0L, after.airBalance, "the air ledger is out by ${after.airBalance}")
        assertEquals(
            0L,
            after.inTransitMass + after.ventedMass + after.builtMass -
                after.extractedMass - after.baselineCargoMass,
            "the cargo ledger is out",
        )
    }

    @Test
    fun `a scarce tile feeds the faster reaction first`() {
        // Decision 2's flavour, in the world rather than in the arithmetic: with barely any oxygen
        // to go round, carbon gets most of it because carbon wants most of it. There is no priority
        // list anywhere for this to be reading — see `OxidationContentionTest`.
        val start = starved(
            lumpAt(burningKelvin, Species.Carbon to 20L * Budget.KILOGRAM, Species.Iron to 20L * Budget.KILOGRAM),
            oxygen = 5L * Budget.GRAM,
        )
        val after = run(start, TICKS)

        val carbonBurned = railMass(start, Species.Carbon) - railMass(after, Species.Carbon)
        val ironScaled = railMass(start, Species.Iron) - railMass(after, Species.Iron)

        assertTrue(carbonBurned > 0L, "the carbon got none of the oxygen at all")
        assertTrue(
            carbonBurned > ironScaled,
            "iron outbid carbon for a scarce tile: $ironScaled of iron against $carbonBurned of carbon",
        )
    }

    @Test
    fun `both reactions at once still account for every atom`() {
        // The per-species statement with two reactions running, which is where a mis-indexed write
        // would show up: one reaction's product landing in the other's ordinal balances in total and
        // is nonsense in detail.
        val start = withLump(
            lumpAt(burningKelvin, Species.Carbon to 20L * Budget.KILOGRAM, Species.Iron to 20L * Budget.KILOGRAM),
        )
        val after = run(start, TICKS)

        val carbonBurned = railMass(start, Species.Carbon) - railMass(after, Species.Carbon)
        val ironScaled = railMass(start, Species.Iron) - railMass(after, Species.Iron)
        assertTrue(carbonBurned > 0L && ironScaled > 0L, "only one of the two reactions ran")

        val scaleMade = railMass(after, Species.Hematite) - railMass(start, Species.Hematite)
        val oxidesMade = carbonOxidesAnywhere(after) - carbonOxidesAnywhere(start)
        val oxygenUsed = airMass(start, Fluid.Oxygen) - airMass(after, Fluid.Oxygen)

        // Every gram of oxygen the room lost is in one of the two products, and nowhere else. The
        // carbon's share is spread across both of its oxides now — see [carbonOxidesAnywhere].
        assertEquals(
            oxygenUsed,
            (oxidesMade - carbonBurned) + (scaleMade - ironScaled),
            "the oxygen the room lost is not in the two products",
        )

        for (s in Species.ALL) {
            // CO2 is on this list now: the belt is where a reaction puts it, and the room is only
            // where it ends up. Same reason [carbonOxidesAnywhere] exists.
            if (s == Species.Carbon || s == Species.Iron || s == Species.Hematite ||
                s == Species.CarbonDioxide || s == Species.CarbonMonoxide
            ) continue
            assertEquals(railMass(start, s), railMass(after, s), "$s changed on the belt")
        }
        for (f in Fluid.ALL) {
            if (f == Fluid.Oxygen || f == Fluid.CarbonDioxide || f == Fluid.CarbonMonoxide) continue
            assertEquals(airMass(start, f), airMass(after, f), "$f changed in the air")
        }
    }

    private companion object {
        /** The row the track runs along. */
        const val ROW = 4

        /**
         * Long enough for several chemistry passes and short enough to stay well inside the
         * five-second rule. `CHEM_PERIOD` is 8, so this is four passes.
         */
        const val TICKS = 32

        /**
         * Long enough for a lump to shed a useful part of its heat into the track and the room.
         *
         * Conduction runs at `HEAT_PERIOD` and `CARGO_CONTACT_CONDUCTANCE` is deliberately slow — a
         * lump is meant to stay hot for a while as it travels — so cooling is the slowest thing
         * asserted here. Still well inside the five-second rule: this is a twelve-by-eight grid.
         */
        const val COOLING_TICKS = 600
    }
}
