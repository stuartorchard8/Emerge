package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.buildableFrom
import org.emerge.demo.outofspace.world.Material
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A ghost is track with a representation and no mass — see `apps/outofspace/PLAN_self_building_rails.md`.
 *
 * These pin the first increment only: **laying no longer conjures**. Nothing here builds a ghost up,
 * because nothing can yet; what is pinned is that a drawn run outside creative mode arrives empty,
 * that a *stated* world still arrives finished, and that the two are told apart the same way
 * everywhere — by the bill of materials, per species.
 */
class GhostTest {

    private val grid = Grid(12, 6)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private fun lay(state: VesselState, conduit: Conduit, from: TileIndex, to: TileIndex): VesselState =
        OutofspaceReducer.reduce(
            cfg,
            state,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Lay(from, to, conduit)))),
        )

    private fun drag(state: VesselState, conduit: Conduit, y: Int, fromX: Int, toX: Int): VesselState {
        var s = state
        for (x in fromX until toX) s = lay(s, conduit, grid.tile(x, y), grid.tile(x + 1, y))
        return s
    }

    @Test
    fun `a run drawn outside creative mode is laid as ghosts`() {
        val s = drag(VesselState.empty(grid).copy(creative = false), Conduit.Rail, y = 3, fromX = 2, toX = 8)

        for (x in 2..8) {
            val tile = grid.tile(x, 3)
            assertNotNull(s.conduits.at(Conduit.Rail, tile), "no rail was laid at ($x, 3) at all")
            assertTrue(s.conduits.isGhost(Conduit.Rail, tile), "the rail at ($x, 3) arrived with metal in it")
            assertEquals(0L, s.conduits.massAt(Conduit.Rail, tile), "mass at ($x, 3)")
        }
    }

    @Test
    fun `a run drawn in creative mode is finished track`() {
        val s = drag(VesselState.empty(grid).copy(creative = true), Conduit.Rail, y = 3, fromX = 2, toX = 8)

        for (x in 2..8) {
            val tile = grid.tile(x, 3)
            assertTrue(s.conduits.isComplete(Conduit.Rail, tile), "the rail at ($x, 3) arrived a ghost")
            assertTrue(s.conduits.massAt(Conduit.Rail, tile) > 0L, "mass at ($x, 3)")
        }
    }

    /**
     * The ledger is the reason the inversion goes first. Conjured metal is an *insertion* and has to
     * be booked as one; a ghost is not conjured, so nothing may be booked for it. Booking either way
     * round would read as the world spontaneously gaining or losing heat.
     */
    @Test
    fun `laying ghosts inserts no energy, and laying finished track does`() {
        val ghosts = drag(VesselState.empty(grid).copy(creative = false), Conduit.Rail, y = 3, fromX = 2, toX = 8)
        assertEquals(0L, ghosts.insertedEnergy, "a ghost cost the world energy it never received")

        val real = drag(VesselState.empty(grid).copy(creative = true), Conduit.Rail, y = 3, fromX = 2, toX = 8)
        assertTrue(real.insertedEnergy > 0L, "creative track arrived with heat nobody booked")
    }

    @Test
    fun `a stated vessel is built, not drawn`() {
        // The starting ship is a description of a finished vessel, so every length of track on it is
        // real. If this ever fails, `Conduits.finished` has stopped being said by a stated world and
        // the player wakes up aboard a ghost.
        val s = starterVessel(OutofspaceConfig().initialGrid)
        var laid = 0
        s.conduits.all { conduit, tile, _ ->
            laid++
            assertTrue(s.conduits.isComplete(conduit, tile), "$conduit at $tile is a ghost on the starter vessel")
        }
        assertTrue(laid > 0, "the starter vessel has no conduit on it to check")
    }

    /**
     * ⚠️ Completeness is per species, not against a total. A ghost admits a few percent of whatever
     * came with the material it was fed, so a tile can carry more mass than its bill while still
     * being short of the one thing it is made of — and a total-mass test would call it finished and
     * hand the player a free rail.
     */
    @Test
    fun `a heavy tile short of its iron is still a ghost`() {
        val s = drag(VesselState.empty(grid).copy(creative = true), Conduit.Rail, y = 3, fromX = 2, toX = 4)
        val tile = grid.tile(3, 3)
        val stuff = s.conduits.tracks[Conduit.Rail]
        val iron = stuff[tile, Species.Iron]
        assertTrue(iron > 1L, "a length of rail should be made of some iron, got $iron")

        stuff[tile, Species.Iron] = iron - 1
        stuff[tile, Species.Oxygen] = iron * 10

        assertTrue(s.conduits.massAt(Conduit.Rail, tile) > iron, "the fixture did not make the tile heavier")
        assertFalse(s.conduits.isComplete(Conduit.Rail, tile), "a tile short of its iron passed as finished")
    }

    // ── A ghost draws material to itself ──────────────────────────────────────

    /**
     * A tank of iron at (3, 3) pushing onto a run of track from (4, 3) to (7, 3), and nothing at the
     * far end. The only thing that can make the belt move is a **sink**, and the only candidate is
     * whatever is at (7, 3).
     */
    private fun tankAndRun(
        ghostAt: Int?,
        stored: Mixture = Mixture.of(Species.Iron to 4 * Capacity.PACKET_MASS, energy = 0),
    ): VesselState {
        val grid = Grid(12, 6)
        val deck = DeckArray(grid)
        deck += Storage(grid.tile(3, 3), Direction.Right)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 7, 3)
        val s = VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(grid.tile(3, 3), stored)
        // `Conduits.ofRails` states finished track, so a ghost has to be made by taking the metal
        // back out — which is exactly the state a drawn run arrives in.
        if (ghostAt != null) s.conduits.tracks[Conduit.Rail].release(grid.tile(ghostAt, 3))
        return s
    }

    private fun onTheRun(s: VesselState): Long =
        (4..7).sumOf { s.rail.massAt(s.grid.tile(it, 3)) }

    @Test
    fun `a run of finished track with nothing at the end never advances`() {
        // The control. Without it the test below proves only that belts work.
        //
        // ⚠️ **This used to assert that the tank loaded its own output tile anyway**, on the grounds
        // that a source pushes whether or not anything is drawing and the only question worth asking
        // was whether the load *travelled*. That is no longer true and the change is the point: a
        // source now holds on to what nothing downstream wants (see `DemandTest`). So the control
        // asserts the stronger thing — with no sink anywhere, not one gram leaves the tank at all —
        // and the run stays clear rather than filling up and jamming.
        val s = run(tankAndRun(ghostAt = null), RAIL_PERIOD * 8)
        assertEquals(
            0L,
            (4..7).sumOf { s.rail.massAt(s.grid.tile(it, 3)) },
            "material went out onto a run with no sink at the end of it",
        )
    }

    /**
     * A source stops pouring once the site has **enough on its way** — the quantity half of demand.
     *
     * The whitelist answers what kind of thing may usefully leave a tile; on its own it answers that
     * question the same way whether the site is short by a tonne or by a gram. So a tank facing a
     * ghost opened right up and kept pouring, and everything past what the site could use rode down
     * the run and stopped on it: an over-draw, and a full run is exactly what leaves a rail marked
     * for deconstruction unable to hand its metal back.
     *
     * What is pinned is the *peak*, not the end state. Finishing is already covered above; the
     * question here is how much material the network commits to a job while it is being done.
     *
     * ⚠️ The count is deliberately an over-count, so the bound is loose on purpose — see [InFlight].
     * Tightening it would be measuring the approximation rather than the rule.
     */
    @Test
    fun `a source stops pouring once the site has enough on its way`() {
        val bill = org.emerge.demo.outofspace.world.conduitBillOfMaterials(Conduit.Rail).total
        var s = tankAndRun(ghostAt = 7, stored = Mixture.of(Species.Iron to 40 * Capacity.PACKET_MASS, energy = 0))
        var peak = 0L
        repeat(RAIL_PERIOD * 20) {
            s = run(s, 1)
            peak = maxOf(peak, onTheRun(s))
        }
        assertTrue(s.conduits.isComplete(Conduit.Rail, s.grid.tile(7, 3)), "the ghost never finished")
        assertTrue(
            peak <= bill + Capacity.PACKET_MASS,
            "the tank committed ${peak}g to a ${bill}g job: it is pouring past what the site can use",
        )
    }

    /**
     * ⛔ **The run does not go quiet once the last source has gone.**
     *
     * The tail of the column transfer. Everything arrives and everything gets built, so the outcome
     * test above is happy — but Stu could see the belt lose pressure for a few ticks the moment the
     * last marked rail ceased to be, and go back to feeding one ghost at a time.
     *
     * It is the quantity gate, applied to a lump that has already been committed. At the end of a
     * transfer what is in flight *exactly* covers what is left to build, because the two columns
     * were the same size — so `wanted > covered` is false for every lump except the leading one,
     * which alone is allowed forward because the count at the tile it moves into does not include
     * itself. It is eaten, the total drops by one packet, and the next one goes. Single file.
     *
     * ⛔ Refusing a lump in a corridor with one way out **does not save the material**; it only stops
     * it arriving. Rationing belongs where a lump has a *choice* — at a fork, where it is the
     * difference between the branch that still needs feeding and the one already covered.
     *
     * Measured as dead rail periods rather than as a deadline: a tick budget would pass or fail on
     * the length of the fixture, and what is wrong here is the *shape* of the delivery.
     */
    @Test
    fun `the belt does not idle while it still holds what a ghost wants`() {
        val grid = Grid(10, 14)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinCol(grid, rails, 5, 2, 10)
        joinCol(grid, rails, 4, 2, 10)
        rails[grid.tile(5, 10).index] = rails[grid.tile(5, 10).index]!!.joinedTo(Direction.Left)
        rails[grid.tile(4, 10).index] = rails[grid.tile(4, 10).index]!!.joinedTo(Direction.Right)
        for (y in 2..10) {
            val t = grid.tile(5, y)
            rails[t.index] = rails[t.index]!!.copy(deconstructing = true)
        }
        var s = VesselState(
            grid,
            DeckArray(grid),
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, DeckArray(grid)),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)
        for (y in 2..10) s.conduits.tracks[Conduit.Rail].release(grid.tile(4, y))

        fun onGhosts() = (2..10).sumOf { s.conduits.massAt(Conduit.Rail, s.grid.tile(4, it)) }
        var idle = 0
        var worst = 0
        repeat(60) {
            val before = onGhosts()
            val carrying = grid.tiles.any { t -> !s.rail.isEmpty(t) }
            s = run(s, RAIL_PERIOD)
            val wanting = (2..10).any { !s.conduits.isComplete(Conduit.Rail, s.grid.tile(4, it)) }
            if (!carrying || !wanting) return@repeat
            if (onGhosts() == before) idle++ else idle = 0
            if (idle > worst) worst = idle
        }

        assertTrue(
            (2..10).all { s.conduits.isComplete(Conduit.Rail, s.grid.tile(4, it)) },
            "the transfer did not finish at all",
        )
        // One quiet period is a lump crossing a tile without reaching anything. A run of them is the
        // belt delivering single file.
        assertTrue(worst <= 1, "the belt idled for $worst rail periods with iron on it and ghosts waiting")
    }

    /**
     * ⛔ **Material already on the belt keeps moving after the last source has gone.**
     *
     * The end of Stu's column transfer. The marked rails are the only producers on that network, so
     * the tick the last one ceases to be there are **no sources at all** — only lumps in flight and
     * ghosts still wanting them. If anything about the network depends on a producer existing, this
     * is where it shows, and it shows as the run losing pressure and reverting to feeding only
     * whichever ghost happens to be adjacent.
     *
     * Stated rather than played out, so the question is asked directly: enough iron for every ghost,
     * standing on the run, and nothing anywhere that could ever emit another gram.
     */
    @Test
    fun `a run with no source at all still delivers what is standing on it`() {
        val grid = Grid(10, 14)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinCol(grid, rails, 4, 2, 12)
        val start = VesselState(
            grid,
            DeckArray(grid),
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, DeckArray(grid)),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)
        // Ghosts at the top of the column, iron standing below them on finished track.
        val bill = org.emerge.demo.outofspace.world.conduitBillOfMaterials(Conduit.Rail)
        for (y in 2..4) start.conduits.tracks[Conduit.Rail].release(grid.tile(4, y))
        for (y in 9..12) start.rail.loadOnto(grid.tile(4, y), bill.scaledTo(Capacity.PACKET_MASS))

        val s = run(start, RAIL_PERIOD * 200)

        val unbuilt = (2..4).filterNot { s.conduits.isComplete(Conduit.Rail, s.grid.tile(4, it)) }
        val stranded = (5..12).filter { !s.rail.isEmpty(s.grid.tile(4, it)) }
        assertTrue(
            unbuilt.isEmpty(),
            "ghosts at y=$unbuilt never got built; iron still standing at y=$stranded",
        )
    }

    /**
     * ⛔ **A column being taken apart pays for the column being built beside it.**
     *
     * Stu's case, and the one that decides whether rebuilding a network is playable at all. Nine
     * lengths of track marked for deconstruction, nine ghosts alongside them, joined at one end. The
     * matter in the first column is exactly what the second column costs, so the whole job is one
     * transfer and it ought to simply happen.
     *
     * What happened instead: the first rail tick was right — every marked tile dropped a packet and
     * sent it toward the ghosts — and then everything stalled but the tiles nearest the join. A
     * ghost is a **plug** for anything it cannot be built from, and the debt that rule levies was
     * charged against *every* route past it whatever the material was. So the demand of the eight
     * ghosts beyond the first was invisible, the column drained one rail at a time, and the tiles
     * that had already emptied ceased to be and cut the two columns apart.
     *
     * ⛔ **A ghost is not a plug for the thing it is made of.** It takes what it needs and the
     * remainder rides on — that is the whole design. The debt is for material the site *refuses*,
     * and asking that question needs the material, so it is asked at the door rather than baked into
     * the number.
     *
     * ⚠️ And demand **accumulates**: nine ghosts wanting a rail apiece want nine rails, not one. Read
     * one route at a time the far ghosts always look covered, because the material standing in the
     * corridor is counted against each of them separately when it can only ever be eaten once.
     */
    @Test
    fun `a column being deconstructed builds the column beside it`() {
        val grid = Grid(10, 14)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinCol(grid, rails, 5, 2, 10)
        joinCol(grid, rails, 4, 2, 10)
        // Joined at one end, in the last tick — which is where Stu left it.
        run {
            val a = grid.tile(5, 10)
            rails[a.index] = rails[a.index]!!.joinedTo(Direction.Left)
            val b = grid.tile(4, 10)
            rails[b.index] = rails[b.index]!!.joinedTo(Direction.Right)
        }
        for (y in 2..10) {
            val t = grid.tile(5, y)
            rails[t.index] = rails[t.index]!!.copy(deconstructing = true)
        }
        val start = VesselState(
            grid,
            DeckArray(grid),
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, DeckArray(grid)),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)
        for (y in 2..10) start.conduits.tracks[Conduit.Rail].release(grid.tile(4, y))

        val s = run(start, RAIL_PERIOD * 200)

        val unbuilt = (2..10).filterNot { s.conduits.isComplete(Conduit.Rail, s.grid.tile(4, it)) }
        val left = (2..10).filter { s.conduits.at(Conduit.Rail, s.grid.tile(5, it)) != null }
        assertTrue(
            unbuilt.isEmpty(),
            "ghosts at y=$unbuilt never got built; marked track still standing at y=$left",
        )
    }

    /**
     * ⛔ **Building with impure material does not precipitate the impurity.**
     *
     * From Stu's save: 181g of **pure carbon** parked on a rail at (12, 28), refusing to take any
     * part in building the ghost at (12, 29). The track had been built out of hull salvage, and a
     * hull is steel — 99 parts iron to 1 part carbon.
     *
     * A rail's bill is iron and nothing else, so the final top-up, which takes each species up to
     * its own shortfall, took the iron and **left every gram of the carbon behind**. Each top-up
     * therefore concentrated what it did not want, until what was standing there was too far off any
     * bill for anything on the network to use — a lump that can never move again, on a tile that
     * reads as ordinary track. Building with 99% pure material left a 0% pure residue.
     *
     * A top-up now takes the junk that came in with the metal at the same rate it takes the metal,
     * so what rides on is the blend that arrived. The tile ends up with a little carbon in its
     * fabric, which is exactly what the swallow-it-whole branch does for every delivery before the
     * last one — the last one was the odd man out.
     */
    @Test
    fun `building a rail out of steel does not leave the carbon behind`() {
        val steel = Material.Steel.composition.scaledTo(8 * Capacity.PACKET_MASS)
        val start = tankAndRun(ghostAt = 7, stored = steel)
        val bill = org.emerge.demo.outofspace.world.conduitBillOfMaterials(Conduit.Rail)

        val s = run(start, RAIL_PERIOD * 30)

        assertTrue(s.conduits.isComplete(Conduit.Rail, s.grid.tile(7, 3)), "the ghost never finished")
        for (x in 4..7) {
            val standing = s.rail.resourceAt(s.grid.tile(x, 3)) ?: continue
            assertTrue(
                buildableFrom(bill, standing),
                "a residue nothing can use was left at ($x, 3): ${composition(standing)}",
            )
        }
    }

    private fun composition(m: Mixture): String =
        Species.ALL.filter { m[it] > 0L }.joinToString(" ") { "${it.name} ${m[it] * 100 / m.total}%" }

    /**
     * ⛔ **A ghost refuses what it cannot be built from, including what is already standing on it.**
     *
     * From Stu's save: a length of finished track reading **56% iron, 43% titanium** — the full iron
     * bill plus one whole packet of titanium — marked for deconstruction and unable to hand a gram
     * of itself back, because what it is made of is not something the ghost below it can be built
     * from and nothing else on the network wanted it. Nothing had pushed that titanium in; there is
     * no machine and no bridge within a tile of it. It arrived as ordinary traffic.
     *
     * The anti-exploit was asked of every lump *entering* a tile and never of one already there. A
     * tile carrying traffic can become a construction site under the lump standing on it — mark a
     * length of track, let it hand some metal back, then CANCEL it, and it is short of its bill with
     * somebody else's cargo parked on top. The ghost then swallowed it whole, junk and all, exactly
     * as it is supposed to for a delivery that got past the door.
     *
     * ⚠️ Silent and permanent: the tile reads as ordinary finished track and only its composition
     * says otherwise. Asking the door here rather than at the three ways in means it cannot matter
     * how the lump arrived.
     */
    @Test
    fun `a ghost does not swallow a lump that was already standing on it`() {
        val start = tankAndRun(ghostAt = 7)
        val ghost = start.grid.tile(7, 3)
        // Somebody else's titanium, parked on the tile before it was ever a construction site.
        start.rail.loadOnto(ghost, Mixture.of(Species.Titanium to Capacity.PACKET_MASS, energy = 0))

        val s = run(start, RAIL_PERIOD * 20)

        assertEquals(
            0L,
            s.conduits.tracks[Conduit.Rail][ghost, Species.Titanium],
            "the ghost ate titanium it could never have been built from",
        )
    }

    /**
     * ⛔ **A machine's port does not get to skip the site's own door.**
     *
     * From Stu's save: a length of finished track reading **56% iron, 43% titanium**, marked for
     * deconstruction and unable to hand a gram of itself back — because what it is made of is not
     * something the ghost below it can be built from, so nothing on the network wants it. The rail
     * was fine; its *contents* were poisoned, one whole packet of titanium baked into the fabric.
     *
     * The anti-exploit was asked on exactly one of the three ways matter lands on a tile — track to
     * track, in [advanceSegments]. A storage whose output port stands on a ghost, and a bridge
     * setting its load down at its far end, both put material straight onto the tile without ever
     * asking whether the thing standing there could be built from it. The ghost then swallowed it
     * whole, junk and all, exactly as it is supposed to for a delivery that got past the door.
     *
     * ⚠️ Silent, and permanent: the tile reads as ordinary finished track, and only its composition
     * says otherwise.
     */
    @Test
    fun `a port cannot push into a ghost what the ghost cannot be built from`() {
        val grid = Grid(12, 6)
        val deck = DeckArray(grid)
        // Titanium, with its output port standing on the ghost itself.
        deck += Storage(grid.tile(3, 3), Direction.Right)
        // A real sink at the far end, so the network genuinely does want titanium *somewhere* —
        // without it the whitelist refuses the push for an unrelated reason and proves nothing.
        deck += Storage(grid.tile(8, 3), Direction.Left)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 9, 3)
        val start = VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(
            grid.tile(3, 3),
            Mixture.of(Species.Titanium to 8 * Capacity.PACKET_MASS, energy = 0),
        ).copy(creative = false)
        val ghost = grid.tile(4, 3)
        start.conduits.tracks[Conduit.Rail].release(ghost)

        val s = run(start, RAIL_PERIOD * 40)

        assertEquals(
            0L,
            s.conduits.tracks[Conduit.Rail].let { it[ghost, Species.Titanium] },
            "titanium was pushed straight into a ghost rail and baked into the track",
        )
    }

    @Test
    fun `a ghost at the end of a run draws material down it and builds itself`() {
        val start = tankAndRun(ghostAt = 7)
        val stocked = start.buffers.massAt(start.grid.tile(3, 3))
        val s = run(start, RAIL_PERIOD * 8)
        val ghost = s.grid.tile(7, 3)
        // ⚠️ **What left the tank**, not what is standing on the run. The run used to be the witness
        // — a tank that let go left a trail of packets on it — but a source now lets go of exactly
        // what the job needs and no more, so a finished job leaves the belt clean. Asserting on the
        // run measured the over-draw rather than the pull.
        assertTrue(
            s.buffers.massAt(s.grid.tile(3, 3)) < stocked,
            "the tank held on: a ghost is not pulling as a sink",
        )
        assertTrue(
            s.conduits.massAt(Conduit.Rail, ghost) > 0L,
            "material reached (7, 3) but the track there is still made of nothing",
        )
        assertTrue(s.conduits.isComplete(Conduit.Rail, ghost), "the ghost never finished building")
        assertTrue(s.builtMass > 0L, "a length of track was built and the ledger did not hear")
    }

    /**
     * The whole point, stated end to end: iron in a tank becomes a length of track, and the world
     * neither gains nor loses a gram doing it.
     */
    @Test
    fun `building a rail conserves mass`() {
        val before = tankAndRun(ghostAt = 7)
        val opening = before.inTransitMass + before.builtMass
        val after = run(before, RAIL_PERIOD * 8)
        assertTrue(after.builtMass > 0L, "nothing was built, so this proves nothing")
        assertEquals(
            opening,
            after.inTransitMass + after.builtMass,
            "grams went missing between the cargo ledger and the fabric",
        )
    }

    // ── ...but only material it can be built from ─────────────────────────────

    /**
     * ⛔ The anti-exploit. If anything at all could cross a ghost's tile, a player would draw a whole
     * network, run slag over it, and never pay a gram of iron for any of it. The refusal is at the
     * door: material that a rail cannot be built from does not *enter*, whatever the ghost would
     * like to keep once it is past.
     */
    @Test
    fun `a ghost refuses material it cannot be built from`() {
        val s = run(tankAndRun(ghostAt = 7, stored = slag()), RAIL_PERIOD * 8)
        assertEquals(0L, s.rail.massAt(s.grid.tile(7, 3)), "slag walked onto a ghost's tile")
        assertEquals(0L, s.conduits.massAt(Conduit.Rail, s.grid.tile(7, 3)), "a ghost built itself out of slag")
    }

    @Test
    fun `a mostly-pure delivery is admitted whole`() {
        // 95% iron, 5% something else. The slack is what stops a rail demanding perfectly separated
        // material before there is anything aboard that can separate it — and what comes with the
        // iron is baked into the tile rather than picked out of the lump.
        val nearly = Mixture.of(
                Species.Iron to 95 * Capacity.PACKET_MASS / 100,
                Species.Silicon to 5 * Capacity.PACKET_MASS / 100,
                energy = 0,
            )
        val s = run(tankAndRun(ghostAt = 7, stored = nearly), RAIL_PERIOD * 8)
        assertTrue(
            s.conduits.massAt(Conduit.Rail, s.grid.tile(7, 3)) > 0L,
            "a 95% delivery was turned away",
        )
        // And what came with the iron is in the tile, not picked out of the lump and discarded.
        assertTrue(
            s.conduits.tracks[Conduit.Rail][s.grid.tile(7, 3), Species.Silicon] > 0L,
            "the silicon that came with the iron went nowhere",
        )
    }

    @Test
    fun `a delivery just under the bar is refused`() {
        val dirty = Mixture.of(
                Species.Iron to 90 * Capacity.PACKET_MASS / 100,
                Species.Silicon to 10 * Capacity.PACKET_MASS / 100,
                energy = 0,
            )
        val s = run(tankAndRun(ghostAt = 7, stored = dirty), RAIL_PERIOD * 8)
        assertEquals(0L, s.conduits.massAt(Conduit.Rail, s.grid.tile(7, 3)), "a 90% delivery got in")
    }

    private fun slag(): Mixture =
        Mixture.of(Species.Silicon to 4 * Capacity.PACKET_MASS, energy = 0)

    // ── Taking it apart again ─────────────────────────────────────────────────

    private fun remove(state: VesselState, tile: TileIndex): VesselState =
        OutofspaceReducer.reduce(
            cfg,
            state,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Remove(tile, DeleteLayer.Rail)))),
        )

    /**
     * Calling off a rail's deconstruction, which is the same operation as a machine's and shares its
     * edit: the mark comes off, and what is left is judged on its bill like anything else. A rail
     * that had not yet given any metal back is simply finished track again.
     */
    @Test
    fun `cancelling puts a condemned rail back to work`() {
        val laid = drag(VesselState.empty(grid).copy(creative = true), Conduit.Rail, y = 3, fromX = 2, toX = 8)
        val tile = grid.tile(5, 3)
        val before = laid.conduits.massAt(Conduit.Rail, tile)
        assertTrue(before > 0L, "fixture: creative mode should have paid for the run")

        val marked = remove(laid.copy(creative = false), tile)
        assertTrue(marked.conduits.at(Conduit.Rail, tile)!!.deconstructing, "fixture: it should be condemned")

        val s = OutofspaceReducer.reduce(
            cfg, marked, mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Cancel(tile)))),
        )
        assertFalse(s.conduits.at(Conduit.Rail, tile)!!.deconstructing, "the mark should be gone")
        assertTrue(s.conduits.isComplete(Conduit.Rail, tile), "and untouched track is finished track")
        assertEquals(before, s.conduits.massAt(Conduit.Rail, tile), "not a gram of it moved")
    }

    @Test
    fun `outside creative mode deleting a rail marks it rather than removing it`() {
        val laid = drag(VesselState.empty(grid).copy(creative = false), Conduit.Rail, y = 3, fromX = 2, toX = 8)
        val tile = grid.tile(5, 3)
        val s = remove(laid, tile)

        val segment = s.conduits.at(Conduit.Rail, tile)
        assertNotNull(segment, "the rail vanished instead of being marked")
        assertTrue(segment.deconstructing, "the rail was not marked for deconstruction")
    }

    @Test
    fun `in creative mode deleting a rail removes it outright`() {
        // Conjuring track out of nothing and making it vanish into nothing are the same privilege.
        val laid = drag(VesselState.empty(grid).copy(creative = true), Conduit.Rail, y = 3, fromX = 2, toX = 8)
        val s = remove(laid, grid.tile(5, 3))
        assertNull(s.conduits.at(Conduit.Rail, grid.tile(5, 3)), "the rail was only marked")
    }

    /**
     * A bare run from (4, 3) to (8, 3) with the far tile emptied of metal — the ghost the player has
     * just drawn. No machines at all, so the only source and the only sink are the track itself.
     */
    private fun runWithGhostAtTheFarEnd(): VesselState {
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 8, 3)
        val s = VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)
        s.conduits.tracks[Conduit.Rail].release(grid.tile(8, 3))
        return s
    }

    @Test
    fun `a marked rail hands its metal back and then ceases to be`() {
        val before = runWithGhostAtTheFarEnd()
        val doomed = grid.tile(4, 3)
        assertTrue(before.conduits.massAt(Conduit.Rail, doomed) > 0L, "the fixture laid no metal to hand back")

        val s = run(remove(before, doomed), RAIL_PERIOD * 24)
        assertNull(s.conduits.at(Conduit.Rail, doomed), "the marked rail is still there")
    }

    /**
     * ⚠️ The two halves of the feature eat each other unless a marked segment is exempt from being a
     * ghost. It is short of its bill from the first load it hands back, so it reads as unbuilt and
     * absorbs its own metal straight off the belt — perfectly stable, entirely stationary, and from
     * outside it looks like deconstruction quietly doing nothing at all.
     */
    @Test
    fun `a rail being taken apart does not build itself back up`() {
        val doomed = grid.tile(4, 3)
        val s = run(remove(runWithGhostAtTheFarEnd(), doomed), RAIL_PERIOD)
        assertTrue(
            s.conduits.massAt(Conduit.Rail, doomed) < s.conduits.massAt(Conduit.Rail, grid.tile(5, 3)),
            "the marked rail is still holding as much metal as its untouched neighbour",
        )
    }

    @Test
    fun `deconstruction conserves mass`() {
        val before = runWithGhostAtTheFarEnd()
        val opening = before.inTransitMass + before.builtMass
        val s = run(remove(before, grid.tile(4, 3)), RAIL_PERIOD * 24)
        assertEquals(
            opening,
            s.inTransitMass + s.builtMass,
            "grams went missing between the fabric and the cargo ledger",
        )
    }

    /**
     * ⛔ The lock. Track under a deck machine's port cannot be taken up while the machine stands.
     *
     * It is what makes the port rules tractable — a locked run can never be deconstructing, so the
     * two awkward priority cases cannot arise — and it stops a player stranding a machine by pulling
     * up the very thing feeding it.
     */
    @Test
    fun `track under a machine's port cannot be marked`() {
        val built = tankAndRun(ghostAt = null).copy(creative = false)
        // The tank at (3, 3) faces right, so its output port stands on (4, 3).
        val underPort = built.grid.tile(4, 3)
        val s = remove(built, underPort)
        val segment = s.conduits.at(Conduit.Rail, underPort)
        assertNotNull(segment, "the locked rail was removed")
        assertFalse(segment.deconstructing, "the rail under a machine's port was marked anyway")
    }

    /**
     * The claim the whole design rests on: a length of track can be made to **walk**. Draw a ghost
     * ahead of it, mark the tile behind, and the same atoms travel down the line to the new tile.
     */
    @Test
    fun `a rail walks along the run when one end is drawn and the other marked`() {
        val before = runWithGhostAtTheFarEnd()
        val ghost = grid.tile(8, 3)
        val doomed = grid.tile(4, 3)
        val had = before.conduits.massAt(Conduit.Rail, doomed)
        assertTrue(before.conduits.isGhost(Conduit.Rail, ghost), "the fixture did not make a ghost")

        val s = run(remove(before, doomed), RAIL_PERIOD * 24)

        assertNull(s.conduits.at(Conduit.Rail, doomed), "the near end never finished going")
        assertTrue(s.conduits.isComplete(Conduit.Rail, ghost), "the far end never finished arriving")
        // The same atoms, to the unit. Four tiles of travel and two ledger crossings later.
        assertEquals(had, s.conduits.massAt(Conduit.Rail, ghost), "the rail lost mass on the way")
    }


    @Test
    fun `deconstruction conserves mass with a lump standing on the marked tile`() {
        val before = runWithGhostAtTheFarEnd()
        val doomed = grid.tile(4, 3)
        // A half packet of ore riding on the very tile being taken apart: room to spare, but not a
        // form the recovered metal merges with.
        before.rail.put(
            doomed,
            Mixture.of(Species.Iron to Capacity.PACKET_MASS / 2, energy = 0),
        )
        val opening = before.inTransitMass + before.builtMass
        val s = run(remove(before, doomed), RAIL_PERIOD * 24)
        assertEquals(opening, s.inTransitMass + s.builtMass, "grams went missing under passing traffic")
    }


    /** Build a ghost out of the tank, then take the same tile apart again. */
    @Test
    fun `a build-then-deconstruct round trip mints nothing`() {
        val before = tankAndRun(ghostAt = 7).copy(creative = false)
        val opening = before.inTransitMass + before.builtMass
        val ghost = before.grid.tile(7, 3)
        val built = run(before, RAIL_PERIOD * 8)
        assertTrue(built.conduits.isComplete(Conduit.Rail, ghost), "the ghost never finished building")
        assertEquals(opening, built.inTransitMass + built.builtMass, "the build leg did not conserve")

        var s = remove(built, ghost)
        repeat(40) {
            s = run(s, RAIL_PERIOD)
            assertEquals(opening, s.inTransitMass + s.builtMass, "the round trip did not conserve")
        }
    }


    private fun conserves(before: VesselState, label: String, ticks: Int, act: (VesselState) -> VesselState) {
        val opening = before.inTransitMass + before.builtMass
        var s = act(before)
        repeat(ticks) {
            s = run(s, RAIL_PERIOD)
            val now = s.inTransitMass + s.builtMass
            if (now != opening) {
                throw AssertionError("$label drifted by ${now - opening} at tick $it (opening $opening)")
            }
        }
    }

    @Test
    fun `scenario A - marking a half-built ghost conserves`() {
        val built = run(tankAndRun(ghostAt = 7).copy(creative = false), RAIL_PERIOD * 2)
        conserves(built, "half-built ghost marked", 40) { remove(it, it.grid.tile(7, 3)) }
    }

    @Test
    fun `scenario B - redrawing over a marked tile conserves`() {
        val built = tankAndRun(ghostAt = null).copy(creative = false)
        val marked = remove(built, built.grid.tile(6, 3))
        conserves(marked, "redraw over a marked tile", 40) {
            lay(it, Conduit.Rail, it.grid.tile(6, 3), it.grid.tile(7, 3))
        }
    }

    @Test
    fun `scenario E - marking a whole run conserves`() {
        val built = tankAndRun(ghostAt = null).copy(creative = false)
        conserves(built, "whole run marked", 60) {
            var s = it
            for (x in 5..7) s = remove(s, s.grid.tile(x, 3))
            s
        }
    }


    @Test
    fun `a ghost survives a save round trip`() {
        val before = tankAndRun(ghostAt = 7).copy(creative = false)
        val ghost = before.grid.tile(7, 3)
        val after = org.emerge.demo.outofspace.world.Save.read(org.emerge.demo.outofspace.world.Save.write(before))
        assertEquals(
            before.conduits.massAt(Conduit.Rail, ghost),
            after.conduits.massAt(Conduit.Rail, ghost),
            "an empty ghost changed mass across a save",
        )
        assertEquals(before.inTransitMass + before.builtMass, after.inTransitMass + after.builtMass, "empty ghost total")
    }

    @Test
    fun `a half-built ghost survives a save round trip`() {
        val before = tankAndRun(ghostAt = 7).copy(creative = false)
        val ghost = before.grid.tile(7, 3)
        // Half of every gram of the bill: a ghost caught midway through building itself.
        val stuff = before.conduits.tracks[Conduit.Rail]
        val bill = org.emerge.demo.outofspace.world.conduitBillOfMaterials(Conduit.Rail)
        for (sp in Species.ALL) if (bill[sp] > 0L) stuff[ghost, sp] = bill[sp] / 2
        assertTrue(before.conduits.isGhost(Conduit.Rail, ghost), "the fixture finished building")
        assertTrue(before.conduits.massAt(Conduit.Rail, ghost) > 0L, "the fixture never started building")
        val after = org.emerge.demo.outofspace.world.Save.read(org.emerge.demo.outofspace.world.Save.write(before))
        assertEquals(
            before.conduits.massAt(Conduit.Rail, ghost),
            after.conduits.massAt(Conduit.Rail, ghost),
            "a half-built ghost changed mass across a save",
        )
    }

    @Test
    fun `a marked rail survives a save round trip`() {
        val marked = remove(tankAndRun(ghostAt = null).copy(creative = false), grid.tile(6, 3))
        val before = run(marked, RAIL_PERIOD * 2)
        val doomed = before.grid.tile(6, 3)
        val after = org.emerge.demo.outofspace.world.Save.read(org.emerge.demo.outofspace.world.Save.write(before))
        assertEquals(
            before.conduits.massAt(Conduit.Rail, doomed),
            after.conduits.massAt(Conduit.Rail, doomed),
            "a rail being taken apart changed mass across a save",
        )
        assertEquals(before.inTransitMass + before.builtMass, after.inTransitMass + after.builtMass, "marked total")
    }


    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }
}
