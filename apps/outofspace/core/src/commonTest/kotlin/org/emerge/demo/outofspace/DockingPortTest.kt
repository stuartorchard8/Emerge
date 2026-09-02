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
        orders: Map<Species, Long> = mapOf(Species.Iron to -DockingPort.ENDLESS),
        ore: Long = 0L,
        market: Market? = Market.empty(),
        stock: Mixture = ore(6L * Capacity.PACKET_MASS),
        credits: Long = 0L,
        /**
         * A warehouse locked to this, downstream of the port's **output**, or none.
         *
         * ⚠️ **Down, not right.** A docking port takes cargo in at its inboard face and hands
         * purchases out of a *flank* — `LocalPort(0, r, Direction.Down, Output)` — so a run laid
         * along the row reaches the input and nothing else. A pull test on such a run answers "the
         * ship wants nothing" for the wrong reason entirely.
         */
        consumer: Species? = null,
    ): VesselState {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += fixtureStorage(tank, Direction.Right)
        deck += DockingPort(port, Direction.Right, orders = orders, ore = ore)
        val rails = arrayOfNulls<Segment>(grid.size)
        // Ten, so there is track under the port itself as well as up to its door.
        joinRow(grid, rails, 3, 10, 3)
        if (consumer != null) {
            deck += fixtureStorage(grid.tile(10, 6), Direction.Down, filter = SpeciesFilter(consumer, null))
            joinCol(grid, rails, 10, 4, 5)
        }
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
        val s = run(world(orders = emptyMap()), 60 * RAIL_PERIOD)
        assertEquals(0L, s.exportedMass, "a port with nothing on its list sold something")
        assertEquals(0L, s.onTrack(), "the run filled up against a port that wants nothing")
    }

    @Test
    fun `a port is only sent what is on its list`() {
        // Selling titanium; the tank holds iron. Same shape as a warehouse locked to the wrong thing.
        val s = run(world(orders = mapOf(Species.Titanium to -DockingPort.ENDLESS)), 60 * RAIL_PERIOD)
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
                // ⚠️ **The ore order, not two species orders.** A per-species order is written at
                // full purity and takes pure metal only, so a blend can reach the mouth by exactly
                // one route — see [SellOrder]. Listing iron and forsterite separately routes nothing
                // at all, which would have made this measure an empty mouth rather than a penalty.
                ore = -DockingPort.ENDLESS,
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

    // ── Orders ───────────────────────────────────────────────────────────────

    @Test
    fun `a finite sell order stops when it is filled`() {
        // ⛔ **The order is the appetite**, so this is the network declining to deliver rather than
        // the mouth declining to take: two packets asked for, two packets sold, and the rest of a
        // six-packet tank still in the tank. Were the order merely a filter with a counter beside
        // it, the belts would keep coming and the mouth would sell whatever turned up.
        val start = world(orders = mapOf(Species.Iron to -(2L * Capacity.PACKET_MASS)))
        val s = run(start, 120 * RAIL_PERIOD)

        assertEquals(2L * Capacity.PACKET_MASS, s.exportedMass, "a two-packet order did not sell two packets")
        assertEquals(0L, (s.deck[port] as DockingPort).permitted(Species.Iron), "a spent permission stayed on the book")
        assertEquals(0L, s.onTrack(), "the run kept filling after the order was done")
    }

    @Test
    fun `an endless sell order does not stop`() {
        // The other side of the same rule, on the same tank: without this the test above passes for
        // the boring reason that nothing ever sells more than two packets.
        val start = world(orders = mapOf(Species.Iron to -DockingPort.ENDLESS))
        val s = run(start, 120 * RAIL_PERIOD)

        assertTrue(
            s.exportedMass > 2L * Capacity.PACKET_MASS,
            "an endless order sold only ${s.exportedMass}",
        )
        assertEquals(-DockingPort.ENDLESS, (s.deck[port] as DockingPort).permitted(Species.Iron), "an unbounded permission was worked down")
    }

    @Test
    fun `a species order will not take ore, and the ore order will not take metal`() {
        // The partition, through the whole machine rather than against `SpeciesFilter` directly.
        // This is what makes attributing a delivery to an order a fact rather than a guess.
        val dirty = dirtyOre(4L * Capacity.PACKET_MASS)
        val bySpecies = run(world(orders = mapOf(Species.Iron to -DockingPort.ENDLESS), stock = dirty), 60 * RAIL_PERIOD)
        assertEquals(0L, bySpecies.exportedMass, "a pure-iron order gave away the ship's ore")

        val pure = ore(4L * Capacity.PACKET_MASS)
        // ⚠️ `orders` cleared explicitly: the fixture's default is an unbounded iron sell, and
        // leaving it on would have this measure that order rather than the ore one.
        val byOre = run(world(orders = emptyMap(), ore = -DockingPort.ENDLESS, stock = pure), 60 * RAIL_PERIOD)
        assertEquals(0L, byOre.exportedMass, "the ore order gave away refined metal")
    }

    // ── Buying is permission, drawn on by the network ────────────────────────

    private fun permitting(orders: Map<Species, Long>, wants: Species?): VesselState = run(
        world(
            orders = orders, consumer = wants,
            market = Market.of(
                Species.Titanium to 10L * Budget.TONNE, Species.Gold to 10L * Budget.TONNE,
            ),
            credits = 100_000_000L,
        ),
        60 * RAIL_PERIOD,
    )

    @Test
    fun `a permission buys nothing the ship has no use for`() {
        // ⛔ **The whole of what a buy figure IS.** It is not an instruction to purchase; it is a
        // licence for the network to draw through the mouth. With nothing aboard short of titanium
        // there is nothing to draw, so no money moves — and that holds for a bounded permission just
        // as much as for an unbounded one, which is the part that changed.
        for (permitted in listOf(Capacity.PACKET_MASS, DockingPort.ENDLESS)) {
            val s = permitting(mapOf(Species.Titanium to permitted), wants = null)
            assertEquals(0L, s.importedMass, "a permission of $permitted bought what nothing wanted")
            assertEquals(100_000_000L, s.credits, "money left the account for it")
        }
    }

    @Test
    fun `a permission is drawn on when the ship is short`() {
        // The other side: without this the test above passes on a mouth that never buys at all.
        val s = permitting(mapOf(Species.Titanium to DockingPort.ENDLESS), wants = Species.Titanium)
        assertTrue(s.importedMass > 0L, "a warehouse asking for titanium was never supplied")
        assertTrue(s.credits < 100_000_000L, "the titanium was free")
    }

    @Test
    fun `a bounded permission stops at its bound`() {
        // A warehouse that wants titanium for ever, against a licence for one packet of it.
        val s = permitting(mapOf(Species.Titanium to Capacity.PACKET_MASS), wants = Species.Titanium)
        assertEquals(Capacity.PACKET_MASS, s.importedMass, "a one-packet permission bought more than a packet")
        assertEquals(
            0L, (s.deck[port] as DockingPort).permitted(Species.Titanium),
            "a spent permission stayed on the book",
        )
    }

    @Test
    fun `an unbounded permission does not stop`() {
        val s = permitting(mapOf(Species.Titanium to DockingPort.ENDLESS), wants = Species.Titanium)
        assertTrue(s.importedMass > Capacity.PACKET_MASS, "only ${s.importedMass} was ever drawn")
        assertEquals(
            DockingPort.ENDLESS, (s.deck[port] as DockingPort).permitted(Species.Titanium),
            "an unbounded permission was worked down",
        )
    }

    @Test
    fun `the mouth never holds bought matter`() {
        // ⛔ **A docking port has no output store**, so a purchase is minted onto the track and is
        // never anywhere else. This is the assertion that would fail if buying ever went back to
        // filling a buffer and waiting to be collected — which is what let one unwanted species
        // block every other, and what made the mouth able to spend money on nothing.
        val s = permitting(mapOf(Species.Titanium to DockingPort.ENDLESS), wants = Species.Titanium)
        assertEquals(
            null, s.inStore(port, BufferRole.Product),
            "the mouth was holding a purchase instead of handing it straight over",
        )
        assertTrue(s.importedMass > 0L, "fixture: nothing was bought, so this proves nothing")
    }

    @Test
    fun `a permission nobody wants does not starve the one beside it`() {
        // ⛔ **The failure that made buying a pull in the first place.** While a purchase was pushed
        // into a single output store, a permission for something nothing wanted would buy a packet,
        // park it in the only store there is, and block everything behind it for ever.
        val s = permitting(
            mapOf(
                Species.Gold to DockingPort.ENDLESS,      // nothing aboard wants gold
                Species.Titanium to DockingPort.ENDLESS,  // the warehouse wants titanium
            ),
            wants = Species.Titanium,
        )
        assertTrue(s.importedMass > 0L, "the permission beside the unwanted one was never drawn on")
        assertEquals(0L, s.stockOfAboard(Species.Gold), "gold nobody wants was bought anyway")
    }

    /** Everything of [species] anywhere aboard — belts, buffers and all. */
    private fun VesselState.stockOfAboard(species: Species): Long {
        var sum = 0L
        for (i in 0 until grid.size) {
            sum += rail.stuff[TileIndex(i), species]
            sum += buffers.stuff[TileIndex(i), species]
        }
        return sum
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
            ore = -DockingPort.ENDLESS,
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
            ore = -DockingPort.ENDLESS,
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
        val start = world(orders = emptyMap()).berthedTo(post)

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

    @Test
    fun `what a berthed station's plants do reaches the counter`() {
        // ⛔ **Two copies of one market while berthed**, and the stale one used to win. The shelves
        // live on the body; `dockedMarket` is what the trade sheet reads and what the next tick
        // installs back *over* the body. So every batch a berthed station worked was overwritten one
        // tick later: the reserve fell, the shelves never rose, and the matter vanished.
        //
        // ⚠️ **The schedule test above did not see this** — it asserts on the reserve, which is the
        // half stored on the body and which moved correctly the whole time. A screenshot of the
        // counter found it: the separated forsterite was not on the shelf it had been lifted onto.
        val ore = Mixture.of(Species.Iron to 4L * tonne, energy = 0L)
        val post = Station(ore, Market.empty(), id = 3)
        val s = run(world(orders = emptyMap()).berthedTo(post), STATION_PERIOD)

        assertEquals(
            CONCENTRATION_BATCH, s.station(3).market.stockOf(Species.Iron),
            "the batch never reached the station's own shelves",
        )
        assertEquals(
            CONCENTRATION_BATCH, s.dockedMarket?.stockOf(Species.Iron),
            "the batch never reached the counter the player reads",
        )
        // And nothing was destroyed on the way: what left the heap is on a shelf.
        assertEquals(
            ore.total, s.station(3).ore.total + s.station(3).market.holdings().total,
            "a berthed station lost the matter its separator moved",
        )
    }

    // ── Buying: the limits ───────────────────────────────────────────────────

    @Test
    fun `a permission arrives on the track and not in the mouth`() {
        val stocked = Market.of(Species.Titanium to 10L * Budget.TONNE)
        val s = run(
            world(
                orders = mapOf(Species.Titanium to Capacity.PACKET_MASS),
                consumer = Species.Titanium,
                market = stocked,
                credits = 100_000_000L,
            ),
            60 * RAIL_PERIOD,
        )
        assertTrue(s.importedMass > 0L, "nothing was bought")
        assertTrue(s.importedEnergy > 0L, "bought matter arrived at absolute zero")
        assertTrue(s.credits < 100_000_000L, "the purchase was free")
        // ⛔ **On the network, never in the machine.** A purchase is minted onto the track at the
        // moment it is drawn; a mouth that held it would be the buffer this design does without.
        assertEquals(null, s.inStore(port, BufferRole.Product), "the mouth held the purchase")
    }

    @Test
    fun `a permission nobody can afford is not drawn on`() {
        val s = run(
            world(
                orders = mapOf(Species.Gold to Capacity.PACKET_MASS),
                consumer = Species.Gold,
                market = Market.of(Species.Gold to Budget.TONNE),
                credits = 1L,
            ),
            20 * RAIL_PERIOD,
        )
        assertEquals(0L, s.importedMass, "gold was bought with one credit")
        assertEquals(1L, s.credits, "the balance moved without a purchase")
    }

    @Test
    fun `a permission the counterparty cannot supply is not drawn on`() {
        val s = run(
            world(
                orders = mapOf(Species.Titanium to Capacity.PACKET_MASS),
                consumer = Species.Titanium,
                market = Market.empty(),
                credits = 1_000_000_000L,
            ),
            20 * RAIL_PERIOD,
        )
        assertEquals(0L, s.importedMass, "an empty counterparty supplied titanium")
    }

    @Test
    fun `a spent permission leaves the book`() {
        val s = run(
            world(
                orders = mapOf(Species.Titanium to Capacity.PACKET_MASS),
                consumer = Species.Titanium,
                market = Market.of(Species.Titanium to 10L * Budget.TONNE),
                credits = 100_000_000L,
            ),
            60 * RAIL_PERIOD,
        )
        assertEquals(
            0L, (s.deck[port] as DockingPort).permitted(Species.Titanium),
            "a spent permission stayed on the book",
        )
    }

    // ── The ledgers ──────────────────────────────────────────────────────────

    @Test
    fun `both ledgers stay closed across a thousand ticks of trading`() {
        // ⛔ **The test this whole increment is for.** Matter crossing the vessel's boundary in both
        // directions, with mass AND energy booked — see `VesselState.importedEnergy`. Book the mass
        // and not the heat and `massBalance` reads zero while `heatBalance` reads a leak of exactly
        // the warmth that changed hands, which looks like an unrelated bug in the thermal sim.
        // ⚠️ **A consumer, because buying is a pull now.** Without something aboard short of
        // titanium the permission is never drawn on, and the test would assert an empty ledger is
        // balanced — true, and proof of nothing.
        // ⚠️ **Both directions on one book.** The fixture's iron sell has to be stated now that it
        // shares a field with the titanium permission; dropping it left this asserting that an
        // empty ledger balances, which is true and proves nothing.
        val start = world(
            orders = mapOf(
                Species.Iron to -DockingPort.ENDLESS,
                Species.Titanium to 20L * Capacity.PACKET_MASS,
            ),
            consumer = Species.Titanium,
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
            DockingPort(this.port, Direction.Right, orders = mapOf(Species.Iron to -DockingPort.ENDLESS)),
            Market.empty(),
        ).stocked(this.port, lump).anchored()

        val s = run(start, 1)
        assertEquals(lump.total, s.exportedMass, "the mass booked is not the mass sold")
        assertEquals(lump.energy, s.exportedEnergy, "the energy booked is not the energy sold")
        assertTrue(lump.energy > 0L, "the fixture sold a lump at absolute zero, proving nothing")
    }

    @Test
    fun `buying books exactly the lump's mass and lands it at ambient`() {
        // ⚠️ **Not `bareWorld`.** A purchase is minted onto the track and there is no track in a
        // bare world, so nothing could ever be drawn — the old version of this could use a bare port
        // because a purchase went into a buffer.
        val start = world(
            orders = mapOf(Species.Titanium to Capacity.PACKET_MASS),
            consumer = Species.Titanium,
            market = Market.of(Species.Titanium to 10L * Budget.TONNE),
            credits = 100_000_000L,
        )
        val s = run(start, 4 * RAIL_PERIOD)

        assertTrue(s.importedMass > 0L, "nothing was bought")
        val arrived = Mixture.of(Species.Titanium to s.importedMass, energy = s.importedEnergy)
        // ⚠️ Stated, not assumed: a purchase comes off a warehouse shelf, not out of a furnace.
        assertEquals(
            Temperature.AMBIENT_KELVIN,
            (arrived.energy / heatCapacityOf(arrived)).toInt(),
            "bought matter did not arrive at room temperature",
        )
    }
    // ── The save ─────────────────────────────────────────────────────────────

    @Test
    fun `the signed book survives a round trip`() {
        // One of each thing the book can say: unbounded both ways, bounded both ways, and the ore
        // figure, which has no species to key on and rides the same field.
        val book = mapOf(
            Species.Iron to -DockingPort.ENDLESS,
            Species.Titanium to -(3L * Capacity.PACKET_MASS),
            Species.Gold to 5L * Budget.KILOGRAM,
            Species.Copper to DockingPort.ENDLESS,
        )
        val start = world(orders = book, ore = -Budget.TONNE)
        val back = Save.read(Save.write(start)).deck[port] as DockingPort

        // ⚠️ Compared whole rather than key by key: the sign IS the direction, so a per-field check
        // that lost one would read as a permission pointing the other way rather than as a failure.
        assertEquals(book, back.orders, "the book did not come back as it went in")
        assertEquals(-Budget.TONNE, back.ore, "the ore figure did not come back")
    }

    @Test
    fun `an unbounded permission does not come back merely very large`() {
        // ⛔ **ENDLESS is `Long.MAX_VALUE` and every mass in the file goes through the mass scale.**
        // Written as a number it would come back multiplied — or overflowed — into something that
        // behaves like a bound and is not one, and the player's `<<` would quietly become a very
        // big `<`. So it is written as a bare sign with no number at all.
        val start = world(orders = mapOf(Species.Iron to DockingPort.ENDLESS))
        val back = Save.read(Save.write(start)).deck[port] as DockingPort
        assertEquals(DockingPort.ENDLESS, back.permitted(Species.Iron), "an unbounded permission came back bounded")
    }

    @Test
    fun `a port with an empty book writes nothing and comes back empty`() {
        val start = world(orders = emptyMap())
        val line = Save.write(start).lineSequence().first { it.contains("DockingPort") }
        assertTrue("orders=" !in line, "an untraded port wrote its empty book: $line")
        val back = Save.read(Save.write(start)).deck[port] as DockingPort
        assertEquals(emptyMap(), back.orders)
        assertEquals(0L, back.ore)
    }

}
