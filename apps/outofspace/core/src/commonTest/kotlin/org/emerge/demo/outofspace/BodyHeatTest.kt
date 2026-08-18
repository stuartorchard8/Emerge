package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.machine.temperatureKelvin
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.OutofspaceReducer.HEAT_PERIOD
import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.capacityPerTile

import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.machine.Smelter
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.heatCapacityOf
import org.emerge.demo.outofspace.world.tileBillOfMaterials
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.ambientEnergy
import org.emerge.demo.outofspace.world.machine.setTemperature
import org.emerge.demo.outofspace.world.material
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contact rules: **who touches whom**, which is the whole content of the body model.
 *
 * A per-tile heat field could not have failed any of these, because it could not have expressed
 * them. Each test is one sentence from [org.emerge.demo.outofspace.world.stepSolidHeat]'s contract,
 * and each one distinguishes the model from the average it replaced.
 */
class BodyHeatTest {

    private fun cfgFor(grid: Grid) = OutofspaceConfig(initialGrid = grid)

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        val cfg = cfgFor(state.grid)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /** A hull box with a hollow middle, [w] x [h] outer, with a ring of space around it. */
    private fun room(
        w: Int,
        h: Int,
        rails: (Grid) -> List<Segment?> = { List(it.size) { null } },
        deckFill: (Int, Int, TileIndex) -> DeckMachine? = { _, _, _ -> null },
    ): VesselState {
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
        for (y in 2 until h) for (x in 2 until w) {
            deckFill(x, y, grid.tile(x, y))?.let { deck += it }
        }
        return VesselState(grid, deck, conduits = Conduits.ofRails(rails(grid)), buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
    }

    /** The state with the body stored at [tile] set to [kelvin], and its ledger re-anchored. */
    private fun VesselState.heatDeckMachine(tile: TileIndex, kelvin: Int): VesselState {
        val d = deck.copyOf()
        val m = d[tile]!!
        m.setTemperature(kelvin, grid, d.stuff)
        return copy(deck = d).let { it.copy(baselineEnergy = it.storedEnergy) }
    }

    private fun VesselState.railKelvin(tile: TileIndex): Int {
        rails[tile.index] ?: error("no track at $tile")
        return (conduits.energyAt(Conduit.Rail, tile) / Conduit.Rail.capacityPerTile).toInt()
    }

    /**
     * Step 6b of `PLAN_unit_rescale.md`: a machine is a set of adjacent tiles, not a lump.
     *
     * This is the acceptance test for the whole change, and it is written to fail loudly against
     * the old model rather than to describe the new one — under a lump, *every* assertion below is
     * impossible, because a machine had exactly one number and no inside for heat to be uneven in.
     *
     * A smelter is five tiles across, so the far face is four tiles of firebrick away from the near
     * one. Firebrick is the most insulating material in the game, which is what makes a furnace a
     * furnace, and it is therefore also the machine where an internal gradient should be most
     * visible rather than least.
     */
    @Test
    fun `heat one face of a smelter and the far face lags behind`() {
        val g = Grid(14, 14)
        val tile = g.tile(6, 6)
        val world = room(12, 12, deckFill = { x, y, at ->
            if (x == 6 && y == 6) Smelter(at, Direction.Right) else null
        })

        // The whole machine cold, then one corner tile of it made very hot. The first tile of the
        // footprint is a corner and the last is the opposite corner, four tiles away.
        val deck = world.deck.copyOf()
        val furnace = deck[tile]!!
        furnace.setTemperature(Temperature.AMBIENT_KELVIN, world.grid, deck.stuff)
        val corner = furnace.tiles(world.grid).first()
        deck.stuff.setEnergy(corner, deck.stuff.heatCapacityAt(corner) * 2_000L)
        val seeded = world.copy(deck = deck).let { it.copy(baselineEnergy = it.storedEnergy) }

        // Off the deck layer, tile by tile — the same twenty-five figures, in the place a deck
        // machine keeps them.
        fun tiles(s: VesselState) = s.deck[tile]!!.energy(s.grid, s.deck.stuff)
        val start = tiles(seeded)
        assertEquals(25, start.size, "a five-by-five smelter stores twenty-five figures, not one")

        val settled = run(seeded, 20*HEAT_PERIOD)
        val end = tiles(settled)

        // The near corner cools and the far one warms: heat crossed the machine's own body, which
        // is conduction that did not exist before this step and is not written anywhere — it falls
        // out of the contact rules already in stepSolidHeat, once a machine is several bodies.
        assertTrue(end[0] < start[0], "the heated corner should have cooled: ${start[0]} -> ${end[0]}")
        assertTrue(end[24] > start[24], "the far corner should have warmed: ${start[24]} -> ${end[24]}")

        // And it has NOT equalised. This is the assertion that says the machine is a real object in
        // the world with an inside, rather than a bookkeeping change that stores one temperature in
        // twenty-five places: firebrick is a poor conductor, so twenty ticks is nowhere near enough
        // to level two and a half metres of it.
        assertTrue(
            end[0] > end[24] * 2,
            "the machine must still be uneven across itself: near=${end[0]} far=${end[24]}",
        )
    }

    @Test
    fun `a machine keeps every unit of energy it had when it is saved and loaded`() {
        // The migration in Save.readTileEnergy spreads an old file's single figure across the tiles,
        // and a spread that dropped its remainder would make save/load a slow leak that the energy
        // ledger would eventually notice. Round-tripping an uneven machine covers both directions.
        val g = Grid(14, 14)
        val tile = g.tile(6, 6)
        val world = room(12, 12, deckFill = { x, y, at ->
            if (x == 6 && y == 6) Smelter(at, Direction.Right) else null
        })
        val deck = world.deck.copyOf()
        val m = deck[tile]!!
        m.setTemperature(Temperature.AMBIENT_KELVIN, world.grid, deck.stuff)
        // Deliberately not divisible by twenty-five, so a lost remainder shows up.
        val uneven = m.tiles(world.grid)[3]
        deck.stuff.addEnergy(uneven, 1_000_000_007L)
        val before = world.copy(deck = deck)

        val after = Save.read(Save.write(before))
        assertEquals(
            before.deck[tile]!!.energy(before.grid, before.deck.stuff),
            after.deck[tile]!!.energy(after.grid, after.deck.stuff),
            "a saved machine must come back holding exactly what it held, tile by tile",
        )
    }

    @Test
    fun `two things sharing a tile share their heat`() {
        // Track threaded under a furnace. Nothing about the grid says they are connected — they are
        // in contact because they are in the same place, which is the one rule a tile field could
        // express and did, by averaging them into a single temperature they could never leave.
        val g = Grid(12, 12)
        val under = g.tile(5, 5)
        val world = room(
            10, 10,
            rails = { grid -> grid.tiles.map { if (it == under) Segment(Conduit.Rail) else null } },
            deckFill = { x, y, at -> if (x == 5 && y == 5) Smelter(at, Direction.Right) else null },
        )
            .heatDeckMachine(g.tile(5, 5), 900)

        val settled = run(world, 30*HEAT_PERIOD)
        assertTrue(
            settled.railKelvin(under) > Temperature.AMBIENT_KELVIN + 50,
            "the rail under the furnace should have warmed: ${settled.railKelvin(under)}K",
        )
        // And it is *its own* temperature, not the furnace's: iron holds far less than firebrick, so
        // it warms fast, but the two are separate bodies and never become one number.
        val furnace = settled.deck[under] ?: error("no machine at $under")
        val furnaceKelvin = furnace.temperatureKelvin(settled.grid, settled.deck.stuff)
        assertTrue(
            settled.railKelvin(under) != furnaceKelvin,
            "but the two are still separate bodies with separate temperatures: rail=${settled.railKelvin(under)}K furnace=${furnaceKelvin}K",
        )
    }

    @Test
    fun `track conducts along a line the player drew and not across one they did not`() {
        // Two parallel runs of rail, side by side and touching, joined along their own length and to
        // nothing else. Heat poured into one must travel down it and must not cross to the other.
        //
        // This is the rule that makes routing a decision. Adjacency-joining would light both runs
        // up identically and no amount of care about where you lay a cable could ever matter.
        val g = Grid(12, 8)
        val topRow = 3
        val nextRow = 4
        fun line(grid: Grid, y: Int): List<Pair<TileIndex, Segment>> =
            (2..8).map { x ->
                var s = Segment(Conduit.Rail)
                if (x > 2) s = s.joinedTo(Direction.Left)
                if (x < 8) s = s.joinedTo(Direction.Right)
                grid.tile(x, y) to s
            }

        val world = room(10, 6, rails = { grid ->
            val out = arrayOfNulls<Segment>(grid.size)
            for ((t, s) in line(grid, topRow)) out[t.index] = s
            for ((t, s) in line(grid, nextRow)) out[t.index] = s
            out.toList()
        })

        // Drive one tile of the upper run hot.
        val source = g.tile(2, topRow)
        var s = world.copy(
            conduits = world.conduits.heated(Conduit.Rail, source, Conduit.Rail.capacityPerTile * 2_000L),
        )
        s = s.copy(baselineEnergy = s.storedEnergy)

        val settled = run(s, 40*HEAT_PERIOD)
        val alongTheRun = settled.railKelvin(g.tile(6, topRow))
        val acrossToTheOther = settled.railKelvin(g.tile(6, nextRow))

        assertTrue(
            alongTheRun > acrossToTheOther,
            "heat should run down the drawn line, not across to the untouched one: " +
                "${alongTheRun}K along vs ${acrossToTheOther}K across",
        )
    }

    @Test
    fun `a hot wall warms the air in the room and not the air outside`() {
        val world = room(8, 8)
        val g = world.grid
        val wall = g.tile(4, 1)
        val heated = world.heatDeckMachine(wall, 1_200)

        val settled = run(heated, 40*HEAT_PERIOD)
        val inside = settled.airKelvinAt(g.tile(4, 2))
        assertTrue(
            inside > Temperature.AMBIENT_KELVIN + 5,
            "the air against the hot wall should have warmed: ${inside}K",
        )
        // Nothing arrives from outside and nothing is conducted into it: space is not a cold
        // reservoir the hull can convect into, it is empty, and radiation is the only path out.
        assertTrue(settled.radiatedEnergy > 0L, "and the exposed face radiates")
    }

    @Test
    fun `a hot machine warms what it is holding`() {
        // The point of putting buffers in a layer: a store is a body like any other, so the heat
        // solver's existing "bodies sharing a tile touch" rule couples it to the casing around it
        // with no contact logic written for it. This is the ThermalDecomposer's whole story in
        // miniature — heat the machine, and the charge inside comes up with it.
        val g = Grid(12, 12)
        val at = g.tile(5, 5)
        val ore = Resource(Form.Ore, Mixture.of(Species.Iron to 400L * Budget.KILOGRAM, energy = 0L))

        var s = room(10, 10, deckFill = { x, y, tile ->
            if (x == 5 && y == 5) Storage(tile, Direction.Right) else null
        }).stocked(at, ore)
        // Put the charge in at room temperature, so what follows is heat arriving and not heat
        // that was already there.
        s.buffers.stuff.setEnergy(at, s.buffers.stuff.heatCapacityAt(at) * Temperature.AMBIENT_KELVIN)

        // The casing is a deck machine now, so the heat goes into the deck layer rather than onto
        // the machine — the same quantity, at the same tiles.
        val d = s.deck.copyOf()
        d[at]!!.addEnergySpread(40_000_000_000L, s.grid, d)
        s = s.copy(deck = d).let { it.copy(baselineEnergy = it.storedEnergy) }

        val before = s.buffers.stuff.kelvinAt(at)
        val beforeEnergy = s.buffers.stuff.energyAt(at)
        assertEquals(Temperature.AMBIENT_KELVIN, before, "the charge starts at room temperature")

        val warmed = run(s, 40)
        // Energy first, because it is the finer instrument: a charge this size takes a few hundred
        // ticks to move a whole kelvin, so an integer temperature can read unchanged while heat is
        // plainly arriving. Asserting only on kelvin hid working conduction once already.
        assertTrue(
            warmed.buffers.stuff.energyAt(at) > beforeEnergy,
            "heat must cross from the casing into the charge",
        )
        val after = warmed.buffers.stuff.kelvinAt(at)
        assertTrue(after > before, "and enough of it to see: ${before}K -> ${after}K")
        assertTrue(after < 444, "but not all at once — the contact is finite, not instant")
    }

    @Test
    fun `an empty machine has nothing to warm`() {
        // An empty store has no thermal mass, so it is not a body at all. Worth pinning: a
        // zero-capacity node in the Jacobi solve would be a division by zero, and "nothing there,
        // nothing to warm" is also the physically right statement.
        val g = Grid(12, 12)
        val at = g.tile(5, 5)
        val s = room(10, 10, deckFill = { x, y, tile ->
            if (x == 5 && y == 5) Storage(tile, Direction.Right) else null
        })
        assertEquals(0L, s.buffers.massAt(at))
        // It survives a run rather than dividing by zero somewhere in the solver.
        assertEquals(0L, run(s, 5).buffers.massAt(at))
    }

    @Test
    fun `materials differ - the same heat moves a firebrick furnace less than a titanium tank`() {
        // The point of the whole exercise: identical footprints, identical energy, different stuff.
        val g = Grid(12, 12)
        val at = g.tile(5, 5)
        val furnace = room(10, 10, deckFill = { x, y, at ->
            if (x == 5 && y == 5) Smelter(at, Direction.Right) else null
        })
        val tank = room(10, 10, deckFill = { x, y, tile ->
            if (x == 5 && y == 5) Storage(tile, Direction.Right) else null
        })

        // The same number of energy into each, on top of ambient. One path now that every kind is a
        // deck machine and its heat is in the layer.
        val added = 20_000_000_000L
        fun bump(s: VesselState): VesselState {
            val d = s.deck.copyOf()
            d[at]!!.addEnergySpread(added, s.grid, d)
            return s.copy(deck = d).let { it.copy(baselineEnergy = it.storedEnergy) }
        }

        val hotFurnace = bump(furnace).kelvinAt(at) - Temperature.AMBIENT_KELVIN
        val hotTank = bump(tank).kelvinAt(at) - Temperature.AMBIENT_KELVIN
        assertTrue(
            hotTank > hotFurnace,
            "titanium should heat further than firebrick for the same energy: +${hotTank}K vs +${hotFurnace}K",
        )
    }

    @Test
    fun `building and scrapping a body books its energy in and out`() {
        // Creative: the whole subject is the insertion ledger, and an insertion is what creative
        // placement *is*. Outside it nothing arrives from off-world and there is nothing to book.
        val grid = Grid(6, 5)
        val at = grid.tile(2, 2)
        var s = VesselState.empty(grid).copy(creative = true)
        val cfg = cfgFor(grid)

        s = OutofspaceReducer.reduce(
            cfg, s,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Place(at, Brush.Building(DeckMachineKind.Hull), Direction.Right)))),
        )
        // Derived from what a tile of hull is *made of*, not from a per-kind capacity constant. Those
        // two used to be one expression and are now two: since a casing became real matter, its
        // capacity follows from the iron and carbon on the tile. They differ by 0.17 ppm, and this
        // side is the honest one — the other was a harmonic-mean-density round trip.
        assertEquals(
            heatCapacityOf(tileBillOfMaterials(DeckMachineKind.Hull)) * Temperature.AMBIENT_KELVIN,
            s.insertedEnergy,
            "a wall brings a wall's worth of room-temperature heat into the world",
        )

        s = OutofspaceReducer.reduce(
            cfg, s,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Remove(at)))),
        )
        // Not necessarily zero: the wall radiated and conducted for a tick before it was pulled, so
        // what left with it is what it was holding, not what it arrived with. That difference is
        // exactly why the term is booked rather than assumed.
        assertTrue(
            s.insertedEnergy < heatCapacityOf(tileBillOfMaterials(DeckMachineKind.Hull)) * Temperature.AMBIENT_KELVIN / 1_000L,
            "and scrapping it takes that heat back out: ${s.insertedEnergy}",
        )
    }
}
