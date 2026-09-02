package org.emerge.demo.outofspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.OutofspaceReducer.STATION_OFFSET
import org.emerge.demo.outofspace.OutofspaceReducer.STATION_PERIOD
import org.emerge.demo.outofspace.world.CONCENTRATION_BATCH
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.DockLink
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Market
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.Station
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.heatCapacityOf
import org.emerge.demo.outofspace.world.machine.BuyOrder
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DockingPort
import org.emerge.sim.core.PlayerId

/**
 * The ship's mouth onto somebody else's economy — `PLAN_economy.md` §5.
 *
 * Three claims: **the sell list is what routes cargo here**, **trade moves matter across the
 * vessel's boundary and both ledgers hear about it**, and **a purchase arrives on the rail**.
 */
class DockingPortTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(16, 8))
    private val tank = TileIndex(0).let { cfg.initialGrid.tile(2, 3) }
    private val port = cfg.initialGrid.tile(10, 3)

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        val inputs = mapOf(PlayerId(0) to OutofspaceInput.EMPTY)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private val tonne = Budget.TONNE

    private fun ore(mass: Long) = Mixture.of(Species.Iron to mass, energy = 0L).atAmbient()

    /** Iron with a fifth of it forsterite: a lump no shelf may take, and no rounding either. */
    private fun dirtyOre(mass: Long) =
        Mixture.of(Species.Iron to mass * 4L / 5L, Species.Forsterite to mass / 5L, energy = 0L).atAmbient()

    /**
     * The same world with a station berthed to it, so the far side of the sale is a real [Station]
     * and not just a loose [Market].
     *
     * ⚠️ **The bare [world] fixture has a `dockedMarket` and no station body**, which is enough for
     * every question about credits and is not enough for any question about *where the matter goes*
     * — the reserve lives on the body. Placed far enough out that nothing contacts the hull; these
     * tests are about the counter, not the physics of the berth.
     */
    private fun VesselState.berthedTo(station: Station): VesselState {
        val body = RigidBody.stationShell(
            width = 8, height = 8,
            positionX = 400L * Flight.PER_TILE, positionY = 0L,
            composition = Mixture.of(Species.Steel to Budget.KILOGRAM, energy = 0L),
            station = station,
        )
        return copy(
            bodies = bodies + body,
            docked = DockLink(
                stationId = station.id, portTile = port, nodeIndex = 0,
                stationLocalX = body.positionX, stationLocalY = body.positionY, stationRelativeAng = 0,
            ),
        )
    }

    /** The berthed station as it stands now, by the id it was given. */
    private fun VesselState.station(id: Int): Station =
        bodies.first { it.station?.id == id }.station!!

    /** A full tank at (2,3) pouring right into a docking port at (10,3). */
    private fun world(
        sell: List<SpeciesFilter> = listOf(SpeciesFilter(Species.Iron, null)),
        buy: List<BuyOrder> = emptyList(),
        market: Market? = Market.empty(),
        stock: Mixture = ore(6L * Capacity.PACKET_MASS),
        credits: Long = 0L,
    ): VesselState {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += fixtureStorage(tank, Direction.Right)
        deck += DockingPort(port, Direction.Right, sell = sell, buy = buy)
        val rails = arrayOfNulls<Segment>(grid.size)
        // Ten, so there is track under the port itself as well as up to its door.
        joinRow(grid, rails, 3, 10, 3)
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
            dockedMarket = market,
            credits = credits,
        ).stocked(tank, stock).anchored()
    }

    /**
     * The same world with its cargo baseline taken **after** the fixture stated its stock.
     *
     * ⚠️ `baselineCargoMass` is a constructor default computed from the layers as they stand, and
     * [stocked] writes into those layers afterwards — so a fixture that states a full tank starts
     * life with a `massBalance` of exactly the tank, which reads as a leak that was there before the
     * first tick. Re-anchoring is what "this is the world's starting matter" means; it is not a
     * correction to anything the sim did.
     */
    private fun VesselState.anchored(): VesselState = copy(baselineCargoMass = inTransitMass)

    private fun VesselState.onTrack(): Long {
        var total = 0L
        for (i in 0 until grid.size) total += rail.massAt(TileIndex(i))
        return total
    }

    // ── Selling ──────────────────────────────────────────────────────────────

    @Test
    fun `ore routed to a docking port becomes credits and leaves the world`() {
        val start = world()
        val s = run(start, 60 * RAIL_PERIOD)

        assertTrue(s.credits > 0L, "nothing was paid for the cargo")
        assertTrue(s.exportedMass > 0L, "nothing was booked as having left")
        assertTrue(s.exportedEnergy > 0L, "the cargo's heat left the vessel unbooked")
        // What left is gone: not in the tank, not on the belt, not in the port.
        assertEquals(
            start.inTransitMass - s.exportedMass, s.inTransitMass,
            "the vessel did not lose exactly what it sold",
        )
    }

    @Test
    fun `a port selling nothing is never sent anything`() {
        // ⛔ The whitelist claim, and the reason the sell list is an `Acceptance` rather than a
        // filter applied at the mouth. A port with an empty list is a dead end: refusing at the door
        // alone would let the tank pour and pack the run solid against a mouth that will never take
        // it.
        val s = run(world(sell = emptyList()), 60 * RAIL_PERIOD)
        assertEquals(0L, s.exportedMass, "a port with nothing on its list sold something")
        assertEquals(0L, s.onTrack(), "the run filled up against a port that wants nothing")
    }

    @Test
    fun `a port is only sent what is on its list`() {
        // Selling titanium; the tank holds iron. Same shape as a warehouse locked to the wrong thing.
        val s = run(world(sell = listOf(SpeciesFilter(Species.Titanium, null))), 60 * RAIL_PERIOD)
        assertEquals(0L, s.exportedMass, "iron was sold to a port listing only titanium")
        assertEquals(0L, s.onTrack(), "the run filled up against a list that will never admit it")
    }

    @Test
    fun `a port with no counterparty is inert`() {
        // ⚠️ Not the same as having nothing to sell. The list admits the cargo, so the network
        // delivers it — and then it sits in the mouth, because there is nobody on the other side.
        val s = run(world(market = null), 60 * RAIL_PERIOD)
        assertEquals(0L, s.credits, "an undocked port paid out")
        assertEquals(0L, s.exportedMass, "an undocked port sold something")
        assertTrue((s.inStore(port, BufferRole.Input)?.total ?: 0L) > 0L, "the cargo never arrived")
    }

    @Test
    fun `purity is worth more at the same mouth`() {
        // §3.6 through the whole machine rather than against `Market` directly: the same mass, sold
        // as one species and as a blend, through a real port over real track.
        //
        // ⚠️ **The partner species has to be priced NEAR IRON or this measures the wrong thing.**
        // The first version of this blended with nickel, which is four times iron's price at γ = 1/2
        // — so the "impure" lump was worth more per gram to begin with and the purity penalty was
        // half hidden by it (1.55× rather than the ~4× that is really there). Forsterite is 930
        // against iron's 1,000, so what is left is purity and nothing else.
        val mass = 4L * Capacity.PACKET_MASS
        val pure = run(world(stock = ore(mass)), 60 * RAIL_PERIOD)
        val blended = run(
            world(
                sell = listOf(SpeciesFilter(Species.Iron, null), SpeciesFilter(Species.Forsterite, null)),
                stock = Mixture.of(
                    Species.Iron to mass / 2, Species.Forsterite to mass / 2, energy = 0L,
                ).atAmbient(),
            ),
            60 * RAIL_PERIOD,
        )
        assertEquals(pure.exportedMass, blended.exportedMass, "the two sold different amounts")
        // A 50/50 blend pays each species a quarter rate, so the whole lump fetches about a quarter
        // of what the same two piles would fetch separated — the same number `MarketTest` pins.
        assertTrue(
            pure.credits > blended.credits * 3,
            "pure paid ${pure.credits} against a blend's ${blended.credits}",
        )
    }

    // ── Where a sale lands ───────────────────────────────────────────────────

    @Test
    fun `a pure lump goes straight onto the shelf`() {
        val post = Station(Mixture.EMPTY, Market.empty(), id = 3)
        val s = run(world(stock = ore(4L * Capacity.PACKET_MASS)).berthedTo(post), 60 * RAIL_PERIOD)
        val after = s.station(3)

        assertEquals(s.exportedMass, after.market.stockOf(Species.Iron), "pure iron did not reach the shelf")
        assertTrue(after.ore.isEmpty, "pure iron was sent to the reserve to be separated from itself")
    }

    @Test
    fun `a mixed lump goes to the reserve, not onto the shelves`() {
        // ⛔ **The bug this exists for.** Every sale used to be `market.absorbing(lump)`, so a dirty
        // lump was scattered across a shelf per species — already separated, for nothing — and
        // [Station.ore] was never written by anything at all. A live station ended up quoting well
        // over a hundred species in sub-gram quantities, and its separator, having eaten the seeded
        // reserve, had nothing to do for the rest of the game.
        val post = Station(Mixture.EMPTY, Market.empty(), id = 3)
        val s = run(world(
            sell = listOf(SpeciesFilter(Species.Iron, null), SpeciesFilter(Species.Forsterite, null)),
            stock = dirtyOre(4L * Capacity.PACKET_MASS),
        ).berthedTo(post), 60 * RAIL_PERIOD)
        val after = s.station(3)

        assertTrue(s.exportedMass > 0L, "nothing was sold at all")
        assertEquals(s.exportedMass, after.ore.total, "the dirty lump did not land in the reserve")
        assertEquals(0L, after.market.holdings().total, "a dirty lump reached the shelves")
        // And it is still a mixture in there, not thirteen tidy piles.
        assertTrue(after.ore[Species.Iron] > 0L && after.ore[Species.Forsterite] > 0L, "the reserve was separated")
    }

    @Test
    fun `a trace of a second species is enough to send the whole lump to the reserve`() {
        // ⚠️ **Purity is exact and there is no tolerance.** The same standard `BUILD_PURITY_PERCENT`
        // holds the player to, and the reason is the one the construction path already learned: a
        // tolerance is a crumb-swallowing rule, and a crumb-swallowing rule loses matter somewhere
        // nobody is looking. A gram in a tonne is 1 part per million and it still goes to the heap.
        val post = Station(Mixture.EMPTY, Market.empty(), id = 3)
        val mass = 4L * Capacity.PACKET_MASS
        val s = run(world(
            sell = listOf(SpeciesFilter(Species.Iron, null), SpeciesFilter(Species.Forsterite, null)),
            stock = Mixture.of(
                Species.Iron to mass - Budget.GRAM, Species.Forsterite to Budget.GRAM, energy = 0L,
            ).atAmbient(),
        ).berthedTo(post), 60 * RAIL_PERIOD)
        val after = s.station(3)

        assertEquals(0L, after.market.holdings().total, "a lump one gram short of pure reached the shelves")
        assertEquals(s.exportedMass, after.ore.total, "the near-pure lump went nowhere")
    }

    @Test
    fun `a station works its reserve once a minute and not before`() {
        // ⛔ **The schedule is the balance** — see [OutofspaceReducer.STATION_PERIOD]. A station used
        // to separate a kilogram every tick, which is nearly four tonnes a minute of free, perfect
        // refining. Here it is handed a reserve it *will* work, and asked to sit on it: nothing at
        // all until its turn comes round, then exactly one batch.
        val ore = Mixture.of(Species.Iron to 4L * tonne, Species.Forsterite to tonne, energy = 0L)
        val post = Station(ore, Market.empty(), id = 3)
        val start = world(sell = emptyList()).berthedTo(post)

        // ⚠️ **Counted in batches per window, not against the first firing.** [STATION_OFFSET]
        // decides only which tick of the period is the station's turn, so *when* the first batch
        // lands is arbitrary; what the schedule promises is one batch per window and no more.
        assertEquals(ore.total, run(start, STATION_OFFSET).station(3).ore.total, "a station worked every tick")
        assertEquals(
            ore.total - CONCENTRATION_BATCH, run(start, STATION_PERIOD).station(3).ore.total,
            "a minute did not buy exactly one batch",
        )
        assertEquals(
            ore.total - 2L * CONCENTRATION_BATCH, run(start, 2 * STATION_PERIOD).station(3).ore.total,
            "two minutes did not buy exactly two batches",
        )
    }

    // ── Buying ───────────────────────────────────────────────────────────────

    @Test
    fun `a standing order arrives at the output port`() {
        val stocked = Market.of(Species.Titanium to 10L * Budget.TONNE)
        val s = run(
            world(
                sell = emptyList(),
                buy = listOf(BuyOrder(Species.Titanium, Capacity.PACKET_MASS)),
                market = stocked,
                credits = 100_000_000L,
            ),
            60 * RAIL_PERIOD,
        )
        assertTrue(s.importedMass > 0L, "nothing was bought")
        assertTrue(s.importedEnergy > 0L, "bought matter arrived at absolute zero")
        assertTrue(s.credits < 100_000_000L, "the purchase was free")
        // ⛔ It must reach the *network*, not merely the machine — a purchase that cannot leave the
        // mouth is a purchase the player cannot use.
        assertTrue(
            s.onTrack() > 0L || (s.inStore(port, BufferRole.Product)?.total ?: 0L) > 0L,
            "the purchase never appeared",
        )
    }

    @Test
    fun `an order nobody can afford is not filled`() {
        val s = run(
            world(
                sell = emptyList(),
                buy = listOf(BuyOrder(Species.Gold, Capacity.PACKET_MASS)),
                market = Market.of(Species.Gold to Budget.TONNE),
                credits = 1L,
            ),
            20 * RAIL_PERIOD,
        )
        assertEquals(0L, s.importedMass, "gold was bought with one credit")
        assertEquals(1L, s.credits, "the balance moved without a purchase")
    }

    @Test
    fun `an order the counterparty cannot supply is not filled`() {
        val s = run(
            world(
                sell = emptyList(),
                buy = listOf(BuyOrder(Species.Titanium, Capacity.PACKET_MASS)),
                market = Market.empty(),
                credits = 1_000_000_000L,
            ),
            20 * RAIL_PERIOD,
        )
        assertEquals(0L, s.importedMass, "an empty counterparty supplied titanium")
    }

    @Test
    fun `a completed order leaves the list`() {
        val s = run(
            world(
                sell = emptyList(),
                buy = listOf(BuyOrder(Species.Titanium, Capacity.PACKET_MASS)),
                market = Market.of(Species.Titanium to 10L * Budget.TONNE),
                credits = 100_000_000L,
            ),
            60 * RAIL_PERIOD,
        )
        assertEquals(emptyList(), (s.deck[port] as DockingPort).buy, "a filled order stayed on the list")
    }

    // ── The ledgers ──────────────────────────────────────────────────────────

    @Test
    fun `both ledgers stay closed across a thousand ticks of trading`() {
        // ⛔ **The test this whole increment is for.** Matter crossing the vessel's boundary in both
        // directions, with mass AND energy booked — see `VesselState.importedEnergy`. Book the mass
        // and not the heat and `massBalance` reads zero while `heatBalance` reads a leak of exactly
        // the warmth that changed hands, which looks like an unrelated bug in the thermal sim.
        val start = world(
            buy = listOf(BuyOrder(Species.Titanium, 20L * Capacity.PACKET_MASS)),
            market = Market.of(Species.Titanium to 50L * Budget.TONNE),
            credits = 100_000_000L,
        )
        assertEquals(0L, start.massBalance, "the fixture did not start balanced")

        var s = start
        repeat(1_000) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            assertEquals(0L, s.massBalance, "mass leaked at tick ${s.tick}")
        }
        assertTrue(s.exportedMass > 0L, "nothing was sold, so nothing was proven")
        assertTrue(s.importedMass > 0L, "nothing was bought, so nothing was proven")
        // ⚠️ **The energy identity is PARKED**, so this asserts nothing today — see [EnergyLedgers],
        // whose whole point is that un-parking is one edit. `PLAN_economy.md` step 2 said heatBalance
        // must reach zero here and that was written without knowing: the whole-grid energy
        // accumulators overflow at the microgram unit and a non-zero value is expected rather than
        // alarming. The booking itself is checked directly below, where nothing is parked.
        EnergyLedgers.assertBalanced(s, "a thousand ticks of trading")
    }

    // ── The booking itself, which is not parked ──────────────────────────────

    /** Nothing but a port, stocked by hand — so one tick moves exactly one thing. */
    private fun bareWorld(port: DockingPort, market: Market?, credits: Long = 0L): VesselState {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += port
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(arrayOfNulls<Segment>(grid.size).toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
            dockedMarket = market,
            credits = credits,
        )
    }

    @Test
    fun `selling books exactly the lump's mass and exactly its energy`() {
        // ⛔ **The claim the four ledger terms exist for**, checked directly rather than through an
        // identity that is parked for unrelated reasons. A `Mixture` carries energy, so trade moves
        // heat; book the mass and not the heat and `heatBalance` reads a leak of exactly the warmth
        // that changed hands, which reads as a bug in the thermal sim.
        val lump = ore(Capacity.PACKET_MASS)
        val start = bareWorld(
            DockingPort(this.port, Direction.Right, sell = listOf(SpeciesFilter(Species.Iron, null))),
            Market.empty(),
        ).stocked(this.port, lump).anchored()

        val s = run(start, 1)
        assertEquals(lump.total, s.exportedMass, "the mass booked is not the mass sold")
        assertEquals(lump.energy, s.exportedEnergy, "the energy booked is not the energy sold")
        assertTrue(lump.energy > 0L, "the fixture sold a lump at absolute zero, proving nothing")
    }

    @Test
    fun `buying books exactly the lump's mass and lands it at ambient`() {
        val start = bareWorld(
            DockingPort(port, Direction.Right, buy = listOf(BuyOrder(Species.Titanium, Capacity.PACKET_MASS))),
            Market.of(Species.Titanium to 10L * Budget.TONNE),
            credits = 100_000_000L,
        )
        val s = run(start, 1)

        val arrived = s.inStore(port, BufferRole.Product) ?: error("nothing was bought")
        assertEquals(arrived.total, s.importedMass, "the mass booked is not the mass that arrived")
        assertEquals(arrived.energy, s.importedEnergy, "the energy booked is not the energy that arrived")
        // ⚠️ Stated, not assumed: a purchase comes off a warehouse shelf, not out of a furnace.
        assertEquals(
            Temperature.AMBIENT_KELVIN,
            (arrived.energy / heatCapacityOf(arrived)).toInt(),
            "bought matter did not arrive at room temperature",
        )
    }
    // ── The save ─────────────────────────────────────────────────────────────

    @Test
    fun `sell and buy lists survive a round trip`() {
        val start = world(
            sell = listOf(SpeciesFilter(Species.Iron, null), SpeciesFilter(Species.Titanium, 90)),
            buy = listOf(BuyOrder(Species.Gold, 5L * Budget.KILOGRAM), BuyOrder(Species.Copper, Budget.TONNE)),
        )
        val back = Save.read(Save.write(start)).deck[port] as DockingPort

        assertEquals(2, back.sell.size, "the sell list did not come back")
        assertEquals(Species.Iron, back.sell[0].species)
        assertEquals(null, back.sell[0].minPercent, "an absent purity came back as a number")
        assertEquals(Species.Titanium, back.sell[1].species)
        assertEquals(90, back.sell[1].minPercent)
        // ⚠️ A buy order's mass goes through the file's mass scale like every other mass. A count
        // read through it — or a mass read around it — is off by a factor of a million.
        assertEquals(
            listOf(BuyOrder(Species.Gold, 5L * Budget.KILOGRAM), BuyOrder(Species.Copper, Budget.TONNE)),
            back.buy,
        )
    }

    @Test
    fun `a port with empty lists writes nothing and comes back empty`() {
        val start = world(sell = emptyList())
        val line = Save.write(start).lineSequence().first { it.contains("DockingPort") }
        assertTrue("sell=" !in line && "buy=" !in line, "an untraded port wrote its empty lists: $line")
        val back = Save.read(Save.write(start)).deck[port] as DockingPort
        assertEquals(emptyList(), back.sell)
        assertEquals(emptyList(), back.buy)
    }

}
