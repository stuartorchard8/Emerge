package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.conservationOf
import org.emerge.demo.outofspace.chem.Electrolysed
import org.emerge.demo.outofspace.chem.cellAction
import org.emerge.demo.outofspace.chem.electrolyse
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TerminalRole
import org.emerge.demo.outofspace.world.terminalTile
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Electrolyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Water comes apart, and the two halves leave by different doors.**
 *
 * The machine a chemical rocket is waiting for — see `PLAN_chemical_rockets.md`. What is worth
 * pinning is not that it splits something, but the three decisions that made it a machine at all:
 *
 *  - the two gases **land in stores that never meet**, which is the whole reason this is not a
 *    `REACTIONS` row. Put hydrogen and oxygen in one hot store and they burn straight back to water
 *    at 773 K; `hydrogen and oxygen land in stores that never meet` is that decision made observable.
 *  - the split **conserves to the microgram** and is 1:8 to within a flooring remainder, because the
 *    game's molar masses happen to make `2 × 18 = 36` in and `2 × 2 + 32 = 36` out.
 *  - it asks for **pure water and nothing else**, at the route rather than at the door, which is
 *    what lets `electrolyse` be three lines with no answer for a contaminant.
 */
class ElectrolyzerTest {

    private val grid = Grid(16, 10)

    /**
     * The machine, at (5,3) facing right — **three baths along one edge**, so all three mouths are on
     * row 3: hydrogen out at (4,3), the feed in at (5,3), oxygen out at (6,3). Its two terminals
     * stand above them at (4,2) and (6,2), on casing with no port.
     *
     * ⚠️ **It was a 3×3 whose ports made a T** until `PLAN_electrochemistry.md` §5.5 — feed at (4,3),
     * hydrogen at (6,3), oxygen at (5,4). Every belt in this fixture moved with the doors; not one
     * assertion about what the machine *does* changed.
     */
    private val plantAt = grid.tile(5, 3)
    private val hydrogenTank = grid.tile(1, 3)
    private val oxygenTank = grid.tile(9, 3)
    private val feedTank = grid.tile(5, 7)

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        val cfg = OutofspaceConfig(initialGrid = state.grid)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * An electrolyzer with a charge of water in its feed and two belts leading away from it.
     *
     * ⚠️ **Two kinds of assertion hang off this, and the difference is the [Electrolyzer.MASS_PER_TICK]
     * dial.** What the machine *makes* is visible in its own two stores within a handful of ticks and
     * is checked there. What actually **leaves** costs a whole packet of the light half — nine hundred
     * kilograms of water, because the machine ships whole packets and hydrogen is a ninth of the mass
     * — so at the machine's first rate of 27 g a tick that was thirty-four thousand ticks and could
     * not be written. At a belt-load a tick it is nine, and `both mouths open` is the test that
     * became affordable. ⛔ **If the dial ever comes back down, that test is the one that will start
     * timing out, and its charge is the thing to grow — not its patience.**
     */
    private fun plant(feed: Mixture): VesselState {
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        deck += Electrolyzer(plantAt, Direction.Right)          // covers x 4..6, y 2..3
        // Facing Left, so its input port is on its right at (2,3), under the end of the hydrogen run.
        deck += fixtureStorage(hydrogenTank, Direction.Left)
        deck += fixtureStorage(oxygenTank, Direction.Right)     // input port at (8,3)
        joinRow(grid, rails, 2, 4, 3)   // hydrogen run, leaving to the left
        joinRow(grid, rails, 6, 8, 3)   // oxygen run, leaving to the right
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(plantAt, feed)
    }

    /**
     * The same machine, fed down a belt from a tank of [cargo] instead of stocked by hand.
     *
     * The only fixture that can say anything about the **appetite**, because an appetite is a fact
     * about a route: stocking a store by hand bypasses the whole question.
     */
    private fun fedFrom(cargo: Mixture): VesselState {
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        deck += Electrolyzer(plantAt, Direction.Right)      // covers x 4..6, y 2..3
        // ⚠️ **The feed comes from BELOW now**, because the feed port is the middle of the bath edge
        // and the two gas mouths have the ends of it. Facing Up, so the tank pours upward from (5,6).
        deck += fixtureStorage(feedTank, Direction.Up)
        joinCol(grid, rails, 5, 3, 6)                       // tank → the plant's input port
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(feedTank, cargo)
    }

    private fun water(packets: Long = 8L): Mixture =
        Mixture.of(Species.Water to packets * Capacity.PACKET_MASS, energy = 0L).atAmbient()

    /**
     * One pass of the cell over [charge], the way the machine runs it.
     *
     * ⛔ **This used to be `electrolyse(charge)` and there used to be a hand-written
     * `2 H₂O → 2 H₂ + O₂` behind it.** There is not any more — [cellAction] competes the charge
     * against `HALF_REACTIONS` and water splits because water is all there is. Every assertion below
     * is the one it was; only the call changed.
     */
    private fun split(charge: Mixture): Electrolysed {
        val action = cellAction(charge, Electrolyzer.UNWIRED_MILLIVOLTS)!!
        return electrolyse(charge, action, charge.total)!!
    }

    /** Everything cargo anywhere: every store, plus whatever is standing on a belt. */
    private fun everywhere(s: VesselState): Long {
        var sum = 0L
        for (tile in grid.tiles) {
            sum += s.buffers.massAt(tile)
            for (sp in Species.ALL) sum += s.rail.stuff[tile, sp]
        }
        return sum
    }

    private fun fed(s: VesselState): Long = s.inStore(plantAt, BufferRole.Input)?.total ?: 0L

    /**
     * What a tank at the end of a belt is holding.
     *
     * ⚠️ **A [org.emerge.demo.outofspace.world.machine.Storage] keeps its cargo in `Inside`, not
     * `Input`** — it *is* the store, it does not feed one. Reading `Input` returns null forever and
     * a belt-level test then reports an empty tank no matter what arrived.
     */
    private fun tank(s: VesselState, at: TileIndex): Mixture =
        s.inStore(at, BufferRole.Inside) ?: Mixture.EMPTY

    // ── The split ────────────────────────────────────────────────────────────

    @Test
    fun `hydrogen and oxygen land in stores that never meet`() {
        val after = run(plant(water()), 200)

        val hydrogen = after.inStore(plantAt, BufferRole.Cathode)
        val oxygen = after.inStore(plantAt, BufferRole.Anode)

        assertTrue(hydrogen != null && hydrogen.total > 0L, "nothing reached the hydrogen store")
        assertTrue(oxygen != null && oxygen.total > 0L, "nothing reached the oxygen store")

        // ⛔ **Each store holds one gas and only that gas**, which is the whole reason this is a
        // machine rather than a `REACTIONS` row: hydrogen and oxygen together in one hot store burn
        // straight back to water at 773 K. See `Electrolyzer`, whose argument this is.
        assertEquals(hydrogen!!.total, hydrogen[Species.Hydrogen], "something other than hydrogen is in the hydrogen store")
        assertEquals(oxygen!!.total, oxygen[Species.Oxygen], "something other than oxygen is in the oxygen store")
        // And no water survived into either: what comes out has been taken apart.
        assertEquals(0L, hydrogen[Species.Water] + oxygen[Species.Water], "water passed through intact")
    }

    @Test
    fun `both mouths open, and each fills a different tank`() {
        // ⛔ **The end-to-end claim, and the one the rate exists to make true.** Everything else here
        // watches the machine's own stores; this watches two belts and the tanks at the end of them,
        // which is what a player sees. A hydrogen packet is nine hundred kilograms of water away, so
        // the charge is twenty belt-loads — enough for two of them, and enough that the run spends
        // most of its length gated by the oxygen belt rather than by the dial.
        val after = run(plant(water(20)), 300)

        val hydrogen = tank(after, hydrogenTank)
        val oxygen = tank(after, oxygenTank)

        // Each belt carried one gas the whole way. A crossed port would show up here and nowhere else.
        assertEquals(hydrogen.total, hydrogen[Species.Hydrogen], "the hydrogen belt delivered something else")
        assertEquals(oxygen.total, oxygen[Species.Oxygen], "the oxygen belt delivered something else")

        // ⛔ **Exact, because whole packets make it exact.** Two tonnes of water is 222 kg of hydrogen
        // and 1778 kg of oxygen, and a machine that ships whole packets rounds each *down*: two
        // packets and seventeen, with the remainders still sitting in the stores behind the doors.
        // That is the arithmetic a player is doing when they size a tank, so it is worth pinning as
        // an equality rather than as "something arrived".
        assertEquals(2L * Capacity.PACKET_MASS, hydrogen.total, "the hydrogen tank is not two packets")
        assertEquals(17L * Capacity.PACKET_MASS, oxygen.total, "the oxygen tank is not seventeen packets")
    }

    @Test
    fun `each mouth is wired to the store behind it`() {
        // ⭐ **Each bath sits on its own port**, which is what the 3×2 bought: every store here is on
        // the tile its mouth is on, so `BufferRole`'s own rule holds unmodified and no machine has to
        // declare which store its input fills. Which gas leaves which end is fixed rather than
        // dialled, so a player can lay a belt without inspecting the machine first.
        val s = plant(water())
        assertEquals(
            s.grid.tile(4, 3), bufferTileOf(s, BufferRole.Cathode),
            "the cathode bath is not on the cathode-end port",
        )
        assertEquals(
            s.grid.tile(5, 3), bufferTileOf(s, BufferRole.Input),
            "the feed store is not on the middle port, between the two electrodes",
        )
        assertEquals(
            s.grid.tile(6, 3), bufferTileOf(s, BufferRole.Anode),
            "the anode bath is not on the anode-end port",
        )
    }

    /**
     * ⭐ **The chemistry and the geometry agree about which end is which**, and nothing else in the
     * suite says so.
     *
     * `electrolyse` hands back `cathode` and `anode` — it has always spoken in electrodes — and the
     * baths are named for electrodes too, so the reducer's job is to put one in the other. What this
     * pins is the *third* leg: that the bath a reduction product lands in is the one standing under
     * the **negative** terminal. Get that backwards and a cell would look entirely plausible while
     * being wired inside out, which is the mistake `TerminalRole`'s own doc warns about for the
     * panel.
     */
    @Test
    fun `the reduction product lands in the bath under the negative terminal`() {
        val after = run(plant(water()), 30)
        val cell = after.deck[plantAt]!!

        val cathodeBath = bufferTileOf(after, BufferRole.Cathode)
        val anodeBath = bufferTileOf(after, BufferRole.Anode)
        val negative = terminalTile(after.grid, cell, plantAt, TerminalRole.Negative)
        val positive = terminalTile(after.grid, cell, plantAt, TerminalRole.Positive)

        // Directly above each bath, on casing that carries no port at all — see the fixture's note.
        assertEquals(after.grid.tile(4, 2), negative, "the negative terminal is not over the cathode bath")
        assertEquals(after.grid.tile(6, 2), positive, "the positive terminal is not over the anode bath")

        val cathode = after.buffers.resourceAt(cathodeBath!!)
        val anode = after.buffers.resourceAt(anodeBath!!)
        assertTrue((cathode?.get(Species.Hydrogen) ?: 0L) > 0L, "no hydrogen at the cathode")
        assertTrue((anode?.get(Species.Oxygen) ?: 0L) > 0L, "no oxygen at the anode")
    }

    /**
     * ⭐ **The feed bath stands directly between the two electrode baths**, which is the claim
     * increment 3's ion migration is going to need: cations drifting to the cathode and anions to the
     * anode is a movement from the middle to each end, rather than a bookkeeping entry between two
     * stores that happen to share an owner.
     */
    @Test
    fun `the feed bath stands between the two electrodes`() {
        val s = plant(water())
        val cathode = s.grid.xOf(bufferTileOf(s, BufferRole.Cathode)!!)
        val feed = s.grid.xOf(bufferTileOf(s, BufferRole.Input)!!)
        val anode = s.grid.xOf(bufferTileOf(s, BufferRole.Anode)!!)

        assertTrue(cathode < feed && feed < anode, "the three baths are not in a line, in that order")
        assertEquals(
            s.grid.yOf(bufferTileOf(s, BufferRole.Cathode)!!),
            s.grid.yOf(bufferTileOf(s, BufferRole.Anode)!!),
            "the two electrodes are not on the same edge",
        )
    }

    private fun bufferTileOf(s: VesselState, role: BufferRole) =
        org.emerge.demo.outofspace.world.bufferTile(s.grid, s.deck[plantAt]!!, plantAt, role)

    // ── Conservation ─────────────────────────────────────────────────────────

    @Test
    fun `the vessel weighs exactly what it did`() {
        // The strongest statement available about a machine that crosses no ledger: water in and two
        // gases out are all cargo, so the total is not merely close, it is **equal**. ⚠️ Mass
        // conservation is the live tripwire in this codebase — the energy ledger is parked — so this
        // is the assertion that would actually catch a broken apportionment.
        // ⚠️ Not `massBalance`, which a hand-stocked fixture cannot satisfy: `stocked` puts matter
        // into a store without booking it as extracted or imported, so the ledger reads the charge
        // as a gain for the life of the world. What is actually being claimed is the stronger and
        // more local thing — this machine neither invents nor loses a microgram.
        val start = plant(water())
        val before = everywhere(start)
        val after = run(start, 900)
        assertEquals(before, everywhere(after), "the plant invented or lost mass")
    }

    @Test
    fun `the split is one part hydrogen to eight parts oxygen`() {
        // `2 H₂O → 2 H₂ + O₂`: 36 g in, 4 g of hydrogen and 32 g of oxygen out. The game's molar
        // masses make that exact, so this is an equality and not a tolerance — and it is worth
        // saying out loud, because the moment it stops being exact the remainder has to go somewhere
        // and nothing downstream is expecting it.
        val charge = water(4)
        val made = split(charge)

        // ⚠️ **Against what the pass consumed, not against the whole charge, and that is a real
        // change.** The cell runs *whole* passes only — a partial one would have to round its
        // stoichiometry, and rounding is exactly where a gram gets invented once a reaction has more
        // than one reagent. A four-packet charge is 4e11 against a 36-unit pass, so four micrograms
        // out of four hundred tonnes do not divide and stay behind. `split` in `OutofspaceSim` puts
        // that remainder back on the feed, and `the plant invented or lost mass` is what proves it.
        assertEquals(made.consumed.total, made.cathode.total + made.anode.total, "mass went missing in the split")
        assertEquals(made.consumed.energy, made.cathode.energy + made.anode.energy, "heat went missing in the split")

        // ⚠️ **Within the flooring remainder, and it has to be.** The hydrogen is computed and the
        // oxygen is `total − hydrogen`, so a charge whose mass is not a multiple of nine leaves its
        // remainder on the oxygen side — up to eight units of the smallest mass the game counts in.
        // Asserting exact equality here is asserting that integer division does not floor. What is
        // exact, and is checked above, is that the two halves sum back to what went in.
        val drift = made.anode.total - made.cathode.total * 8L
        assertTrue(drift in 0L..8L, "the ratio is not 1:8 to within a rounding remainder; out by $drift")
    }

    @Test
    fun `nothing else comes out of it`() {
        // Per species, because a total can balance while water quietly turns into copper. Water is
        // down by the whole charge, the two gases are up by their shares, and **every other species
        // in the table moved by zero**.
        val charge = water(4)
        val made = split(charge)
        val delta = conservationOf(listOf(made.consumed), listOf(made.cathode, made.anode))

        assertEquals(made.consumed.total, delta[Species.Water.ordinal], "the water was not all consumed")
        assertEquals(-made.cathode.total, delta[Species.Hydrogen.ordinal], "the hydrogen does not add up")
        assertEquals(-made.anode.total, delta[Species.Oxygen.ordinal], "the oxygen does not add up")
        for (s in Species.ALL) {
            if (s == Species.Water || s == Species.Hydrogen || s == Species.Oxygen) continue
            assertEquals(0L, delta[s.ordinal], "${s.name} appeared from nowhere")
        }
    }

    @Test
    fun `an empty machine splits nothing`() {
        // ⭐ Now answered one step earlier and more strongly: there is no *action* at all, because
        // an empty charge can supply neither electrode. Nothing has to come out empty because
        // nothing runs.
        assertNull(cellAction(Mixture.EMPTY, Electrolyzer.UNWIRED_MILLIVOLTS), "something came out of nothing")
    }

    /**
     * ⭐ **Below the knee, nothing happens — and 1.23 V is not written down anywhere.**
     *
     * It is `E°(anode) − E°(cathode)`: the water couple at +1230 against the hydrogen couple at 0.
     * This is the second condition axis doing its job, and the first test in the game to assert that
     * a machine can be short of something that is not matter.
     */
    @Test
    fun `a cell below the water knee does nothing at all`() {
        val charge = water(4)
        assertEquals(1230, cellAction(charge, 1500)!!.requiredMillivolts, "splitting water costs 1.23 V")
        assertNull(cellAction(charge, 1229), "water should not split below its own potential")
        assertTrue(cellAction(charge, 1230) != null, "water should split at exactly its potential")
    }

    // ── The appetite ─────────────────────────────────────────────────────────

    @Test
    fun `it takes the water it is for`() {
        assertTrue(fed(run(fedFrom(water()), 400)) > 0L, "the plant was never sent the water it asks for")
    }

    @Test
    fun `and a belt of ore never reaches it`() {
        // ⛔ **The route, not the door.** A lump the split has no answer for must never arrive, or it
        // settles in the feed store and stops the machine for good — `electrolyse` cannot turn
        // gravel into hydrogen and has nowhere to put it if it does not. Stated as an appetite, so
        // the network does not carry it here in the first place.
        val ore = OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(4L * Capacity.PACKET_MASS).atAmbient()
        assertEquals(0L, fed(run(fedFrom(ore), 400)), "the plant swallowed ore it can do nothing with")
    }

    @Test
    fun `and it will not take water that is still dirty`() {
        // ⚠️ **Rock, and the rule is unchanged**: there is nothing a cell can do with forsterite, so
        // the player concentrates first — a machine they already have and an idiom they know.
        // ⛔ **What changed is that "dirty" stopped meaning "not pure".** See the test below.
        val dirty = Mixture.of(
            Species.Water to 9L * Capacity.PACKET_MASS,
            Species.Forsterite to 1L * Capacity.PACKET_MASS,
            energy = 0L,
        ).atAmbient()
        assertEquals(0L, fed(run(fedFrom(dirty), 400)), "the plant took water with rock in it")
    }

    /**
     * ⭐ **But it takes brine, because a cell runs on brine.**
     *
     * `PLAN_power_network.md` increment 4 (Stu, 2026-09-09). The appetite was
     * `SpeciesFilter(Water, pure = true)` and that could not survive the electrolyte ceiling:
     * [org.emerge.demo.outofspace.chem.electrolyteStrength] scores pure water at **zero**, so a cell
     * that would only accept pure water is a cell nothing can ever make run. Chlor-alkali is
     * electrolysis *of brine*; taking salt in is the machine working, not a hole in a filter.
     */
    @Test
    fun `it takes brine, which is the only thing that can carry its current`() {
        val brine = Mixture.of(
            Species.Water to 9L * Capacity.PACKET_MASS,
            Species.Halite to 1L * Capacity.PACKET_MASS,
            energy = 0L,
        ).atAmbient()
        assertTrue(fed(run(fedFrom(brine), 400)) > 0L, "the plant refused brine")
    }

    /** And pure water still arrives, even though it is inert once the ceiling lands. */
    @Test
    fun `it still takes water on its own`() {
        assertTrue(fed(run(fedFrom(water()), 400)) > 0L, "the plant refused clean water")
    }
}
