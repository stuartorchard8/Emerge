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
     * ⛔ **A tile heavy with the wrong stuff reads as finished, and the door is why that is safe.**
     *
     * Completeness is a mass — see [holdsFullBill] — so oxygen poured straight into a rail's fabric
     * finishes it. Nothing in the tick can pour it: [buildableFrom] weighs every gram against the
     * bill before it may become part of anything, and a lump that is mostly oxygen is turned away at
     * the tile. That is the anti-exploit, and it is one test rather than two standards that can
     * drift apart.
     *
     * ⚠️ Both halves stated together on purpose. "The completion test no longer refuses this" is a
     * fact about the old rule; "it can never arrive" is the guarantee, and it is the one that has to
     * hold.
     */
    @Test
    fun `a rail can only be finished by material it may be built from`() {
        val s = drag(VesselState.empty(grid).copy(creative = true), Conduit.Rail, y = 3, fromX = 2, toX = 4)
        val tile = grid.tile(3, 3)
        val stuff = s.conduits.tracks[Conduit.Rail]
        val iron = stuff[tile, Species.Iron]
        assertTrue(iron > 1L, "a length of rail should be made of some iron, got $iron")

        stuff[tile, Species.Iron] = iron - 1
        stuff[tile, Species.Oxygen] = iron * 10

        assertTrue(s.conduits.massAt(Conduit.Rail, tile) > iron, "the fixture did not make the tile heavier")
        assertTrue(
            s.conduits.isComplete(Conduit.Rail, tile),
            "completion is a mass — if this is false the rule has quietly moved back",
        )

        // And no delivery like that can ever be admitted, which is the actual protection.
        assertFalse(
            buildableFrom(Conduit.Rail, Mixture.of(Species.Oxygen to iron * 10, energy = 0)),
            "a lump of oxygen is not something a rail may be built from",
        )
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
        /** What the ghost is to be built from. Null is the conduit's default, which is iron. */
        ghostMaterial: Species? = null,
    ): VesselState {
        val grid = Grid(12, 6)
        val deck = DeckArray(grid)
        deck += Storage(grid.tile(3, 3), Direction.Right)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 7, 3)
        // Stated before the world is built, because `Conduits` hands out immutable segment lists —
        // a choice is part of what the world *is* rather than something done to it afterwards.
        if (ghostAt != null && ghostMaterial != null) {
            val t = grid.tile(ghostAt, 3)
            rails[t.index] = rails[t.index]!!.copy(material = ghostMaterial)
        }
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
     * ⛔ **A fork hands a route what that route can use, and sends the rest the other way.**
     *
     * Stu's save, (14,28): 100kg of iron standing on finished track at a fork. One way leads to a
     * rail ghost that already holds 100kg of its 130kg bill; the other to a ghost barely started.
     * Every rule said yes — the ghost admits iron, and it still wants 30kg of it — so the **whole**
     * packet went that way, the ghost skimmed the 30kg it was short of and finished, and the other
     * 69kg was left standing on what was now a finished dead end. It is not a deadlock: the surplus
     * walks back off the stub over the next few rail periods and eventually takes the turning it
     * should have taken at once. It is the shape of the delivery — the branch that wanted the whole
     * packet was fed one packet at a time, and the far ghost took five rail periods to reach a state
     * it now reaches in three.
     *
     * Nothing was wrong with any of the answers. The question had two values and the situation
     * needed three: not "may this cross" but "how much of this is worth sending". A route now takes
     * what it can use and the remainder goes to the next way out **in the same step**, because a
     * fork is precisely where a lump has somewhere else to be.
     *
     * ⚠️ The shortfall is deliberately not a round number of packets — a fork that split a packet
     * evenly would pass a fixture that says "some went each way" and still strand the difference.
     */
    @Test
    fun `a fork gives each ghost only what it is short of, in one step`() {
        val grid = Grid(10, 8)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 5, 3)       // the fork tile and the ghost to its right
        joinCol(grid, rails, 4, 3, 4)       // …and the ghost below it
        val fork = grid.tile(4, 3)
        val right = grid.tile(5, 3)
        val down = grid.tile(4, 4)
        val start = VesselState(
            grid,
            DeckArray(grid),
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, DeckArray(grid)),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)

        val stuff = start.conduits.tracks[Conduit.Rail]
        val bill = org.emerge.demo.outofspace.world.conduitBillOfMaterials(Conduit.Rail)
        // ⚠️ **Neither shortfall is a round number of packets, and together they are exactly one.**
        // A quarter and three quarters, so the packet covers both ghosts and neither can swallow it
        // whole — which makes the outcome independent of which branch the fork's turn falls on, and
        // that matters: with one capped branch the test would only fail when the cursor happened to
        // point at it.
        val quarter = Capacity.PACKET_MASS / 4
        stuff[right, Species.Iron] = stuff[right, Species.Iron] - quarter
        stuff[down, Species.Iron] = stuff[down, Species.Iron] - (Capacity.PACKET_MASS - quarter)
        assertFalse(start.conduits.isComplete(Conduit.Rail, right), "the fixture left a finished rail")
        assertFalse(start.conduits.isComplete(Conduit.Rail, down), "the fixture left a finished rail")
        // No source anywhere: one packet, standing on the fork, and two ghosts wanting it.
        start.rail.loadOnto(fork, bill.scaledTo(Capacity.PACKET_MASS))

        // ⚠️ **One rail period**, which is the claim: the division happens in the step the lump
        // moves, not over the several it takes a stranded remainder to find its way back out.
        val stepped = run(start, RAIL_PERIOD)
        assertEquals(0L, stepped.rail.massAt(fork), "the fork still holds something")
        assertEquals(quarter, stepped.rail.massAt(right), "the near-finished ghost was not given its own shortfall")
        assertEquals(
            Capacity.PACKET_MASS - quarter,
            stepped.rail.massAt(down),
            "the remainder did not go the other way in the same step",
        )

        val s = run(stepped, RAIL_PERIOD * 4)
        assertTrue(s.conduits.isComplete(Conduit.Rail, right), "the ghost to the right never finished")
        assertTrue(s.conduits.isComplete(Conduit.Rail, down), "the ghost below never finished")
        assertEquals(0L, grid.tiles.sumOf { s.rail.massAt(it) }, "iron was left standing once both jobs were done")
    }

    /**
     * ⛔ **A merge does not hold its turn open for a run that cannot move.**
     *
     * Stu's, and he called it from the symptom alone. Two runs join; one carries iron toward a ghost
     * rail, the other carries raw ore — 5% iron, which the ghost refuses outright. The ore sits at
     * the junction for ever and the iron behind the *other* branch never gets a turn.
     *
     * The junction takes turns, and a feeder counts as waiting if it **has something**. That is the
     * wrong question: the ore has something and can never hand it over, so it holds the turn, and
     * the cursor only advances when a move actually happens — which it never does. One branch is
     * starved permanently by a branch that is not moving either.
     *
     * ⚠️ The right question is whether the feeder has something *the junction will take*. Already
     * asked one line up for the tile actually moving, and now asked of the others too.
     */
    @Test
    fun `a merge does not give its turn to a feeder that cannot move`() {
        val grid = Grid(14, 8)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 5, 10, 4)
        // The two feeders, joining at (5, 4): a run of iron coming along the row, and a dead-end
        // stub of ore coming up from below.
        joinRow(grid, rails, 2, 5, 4)
        joinCol(grid, rails, 5, 4, 5)
        val start = VesselState(
            grid,
            DeckArray(grid),
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, DeckArray(grid)),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)
        val ghost = grid.tile(10, 4)
        start.conduits.tracks[Conduit.Rail].release(ghost)
        // ⚠️ **More iron than one packet, and the assertion is that the job FINISHES.** The first
        // packet always gets through — the junction's cursor starts on the iron branch — and it is
        // the handover that kills the run: moving advances the cursor onto the ore, the ore is not
        // empty so it reads as a run waiting its turn, and it can never take one. Assert only that
        // some iron arrived and the bug is invisible.
        for (x in 2..4) {
            start.rail.loadOnto(grid.tile(x, 4), Material.Iron.composition.scaledTo(Capacity.PACKET_MASS))
        }
        start.rail.loadOnto(
            grid.tile(5, 5),
            Mixture.of(Species.Quartz to Capacity.PACKET_MASS, energy = 0),
        )

        val s = run(start, RAIL_PERIOD * 60)

        assertTrue(
            s.conduits.isComplete(Conduit.Rail, ghost),
            "the ghost stalled at ${s.conduits.tracks.builtPermille(Conduit.Rail, ghost)} permille: " +
                "the ore branch held the junction open without ever moving",
        )
        assertFalse(s.rail.isEmpty(grid.tile(5, 5)), "the ore moved, and it has nowhere it could go")
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
     * ⛔ **A rail coming apart hands back what is WANTED, not a whole packet.**
     *
     * Stu's save, a marked rail at (15,28) beside a quarter-built ghost at (14,28): the ghost was
     * short of a fraction of a packet, the deconstruction put a whole one down, and the difference
     * stood on the track with nothing left that wanted it. Harmless only while something unlimited
     * waits beyond — a deadlock otherwise, because the residue comes to rest exactly in front of the
     * material that would finish the job.
     *
     * [pushOut] has sized its emissions by the demand for a while; this is the same rule for the
     * other kind of source, and the ghost being **partly built** is the whole point — a ghost
     * starting from nothing wants a round number of packets and never shows it.
     */
    @Test
    fun `a rail coming apart hands back only what the ghost beside it still wants`() {
        val grid = Grid(10, 6)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 5, 3)
        val ghost = grid.tile(4, 3)
        val marked = grid.tile(5, 3)
        rails[marked.index] = rails[marked.index]!!.copy(deconstructing = true)
        val start = VesselState(
            grid,
            DeckArray(grid),
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, DeckArray(grid)),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)

        // Part-built, and deliberately NOT short by a whole packet: the overshoot is the difference
        // between what it wants and what a packet is, so a round shortfall hides the bug entirely.
        val stuff = start.conduits.tracks[Conduit.Rail]
        val full = stuff[ghost, Species.Iron]
        stuff[ghost, Species.Iron] = full - Capacity.PACKET_MASS / 4
        assertFalse(start.conduits.isComplete(Conduit.Rail, ghost), "the fixture left a finished rail")

        val s = run(start, RAIL_PERIOD * 60)

        assertTrue(s.conduits.isComplete(Conduit.Rail, s.grid.tile(4, 3)), "the ghost never finished")
        assertEquals(
            0L,
            grid.tiles.sumOf { s.rail.massAt(it) },
            "a residue was left standing once the job was done",
        )
    }

    // ── Built out of something other than the default ─────────────────────────

    /**
     * ⛔ **A run chooses what it is made of, and then means it.**
     *
     * The site's bill follows its chosen material, so a copper rail is finished by a tile's worth of
     * copper and refuses iron — the same door asked about a different bill, which is the whole design:
     * nothing about material choice needed a new rule, only a bill that was allowed to vary.
     *
     * ⚠️ **Both halves.** "Copper builds it" alone would pass against a site that admits anything;
     * "iron does not" is what proves the choice is load-bearing rather than decorative.
     */
    @Test
    fun `a run built of copper takes copper and refuses iron`() {
        fun build(stored: Species): VesselState = run(
            tankAndRun(
                ghostAt = 7,
                stored = Mixture.of(stored to 8 * Capacity.PACKET_MASS, energy = 0),
                ghostMaterial = Species.Copper,
            ),
            RAIL_PERIOD * 30,
        )

        val withCopper = build(Species.Copper)
        val ghost = withCopper.grid.tile(7, 3)
        assertTrue(
            withCopper.conduits.isComplete(Conduit.Rail, ghost),
            "a copper rail was not finished by copper: ${withCopper.conduits.builtPermille(Conduit.Rail, ghost)} permille",
        )
        assertEquals(
            Species.Copper,
            withCopper.conduits.tracks.dominantAt(Conduit.Rail, ghost),
            "it finished, but not out of copper",
        )

        val withIron = build(Species.Iron)
        assertFalse(
            withIron.conduits.isComplete(Conduit.Rail, withIron.grid.tile(7, 3)),
            "iron built a rail that had chosen copper",
        )
        assertEquals(0L, withIron.conduits.massAt(Conduit.Rail, withIron.grid.tile(7, 3)), "and it took none of it")
    }

    /**
     * ⚠️ A copper rail's bill is not an iron rail's, because a tile of copper is not a tile of iron.
     * Copper is the denser, so it costs more — which is the kind of trade material choice is for.
     */
    @Test
    fun `a bill weighs what its own material weighs`() {
        val iron = org.emerge.demo.outofspace.world.conduitBillOfMaterials(Conduit.Rail, Species.Iron)
        val copper = org.emerge.demo.outofspace.world.conduitBillOfMaterials(Conduit.Rail, Species.Copper)
        assertEquals(iron.total, org.emerge.demo.outofspace.world.conduitBillOfMaterials(Conduit.Rail).total, "iron is the default")
        assertTrue(copper.total > iron.total, "copper is denser, so a tile of it should cost more")
        assertEquals(copper[Species.Copper], copper.total, "a copper bill is copper and nothing else")
    }

    /**
     * ⛔ **A rail refuses hull salvage outright rather than picking the iron out of it.**
     *
     * ⚠️ **This test used to assert the opposite, and the behaviour it asserted was a bug fix.**
     * From Stu's save: 181g of *pure carbon* parked on a rail at (12, 28), taking no part in
     * building the ghost at (12, 29). The track was being built out of hull salvage, a hull was
     * steel — 99 parts iron to one of carbon — and a rail's bill is iron and nothing else, so each
     * top-up took the iron and left every gram of the carbon behind. The residue concentrated until
     * it was too far off any bill for anything on the network to use: a lump that can never move
     * again, on a tile that reads as ordinary track. Building with 99% pure material left a 0% pure
     * residue. The fix was to make a top-up take the junk at the same rate as the metal.
     *
     * ⛔ **Steel being a species deletes the situation instead of managing it.** Hull salvage is
     * `Species.Steel`, a rail's bill is `Species.Iron`, and steel is not iron by any fraction — so
     * the door refuses the whole lump at the tile and there is no partial absorption to leave a
     * residue behind. Nothing can concentrate what was never taken apart.
     *
     * ⚠️ **The consequence is a real restriction and is asserted here rather than left implied: you
     * can no longer build track out of hull plate.** Salvaged steel is steel, and nothing in
     * `REACTIONS` runs the alloying backwards. What the lump must not do is *rot* — it stays whole,
     * on the run, still exactly what it was, for whatever does want steel.
     */
    @Test
    fun `a rail refuses hull salvage and leaves it whole`() {
        val steel = Material.Steel.composition.scaledTo(8 * Capacity.PACKET_MASS)
        val start = tankAndRun(ghostAt = 7, stored = steel)
        val ghost = start.grid.tile(7, 3)

        val s = run(start, RAIL_PERIOD * 30)

        assertFalse(s.conduits.isComplete(Conduit.Rail, ghost), "a rail was built out of steel")
        assertEquals(0L, s.rail.massAt(ghost), "steel was absorbed into an iron bill")
        // Whatever came out of the tank is still steel and still one piece: a refusal must not
        // strand a part-lump that has had its iron picked out of it.
        for (x in 4..7) {
            val standing = s.rail.resourceAt(s.grid.tile(x, 3)) ?: continue
            assertEquals(
                Species.Steel,
                standing.dominant,
                "a lump at ($x, 3) is no longer steel: ${composition(standing)}",
            )
            assertEquals(
                standing.total,
                standing[Species.Steel],
                "a residue was picked out of the steel at ($x, 3): ${composition(standing)}",
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

    /**
     * ⛔ **There is no "nearly", and this test used to assert that there was.**
     *
     * At `BUILD_PURITY_PERCENT = 95` a 95% delivery went in whole and its 5% of junk was baked into
     * the tile's fabric — the slack that stopped a rail demanding perfectly separated iron *while
     * perfectly separated iron was unreachable*. `Chemistry.PURE_ENOUGH_PERMILLE` made it reachable,
     * so the slack bought nothing and went on charging for itself: junk in a fabric is junk that
     * off-gasses out of the salvage when the thing is taken apart, leaving a site that reads 99%
     * built for ever.
     *
     * ⚠️ **The distances are the assertion.** One part in a million is refused for the same reason
     * five parts in a hundred are, and stating both is what stops this being read as a bar that
     * merely moved up.
     */
    @Test
    fun `a delivery that is not the recipe is refused, however close`() {
        fun ironWith(junk: Long) = run(
            tankAndRun(
                ghostAt = 7,
                stored = Mixture.of(
                    Species.Iron to Capacity.PACKET_MASS - junk,
                    Species.Silicon to junk,
                    energy = 0,
                ),
            ),
            RAIL_PERIOD * 8,
        ).conduits.massAt(Conduit.Rail, grid.tile(7, 3))

        assertEquals(0L, ironWith(Capacity.PACKET_MASS / 20), "a 95% delivery got in")
        assertEquals(0L, ironWith(Capacity.PACKET_MASS / 1_000_000), "a delivery one part per million off got in")
    }

    /** And the recipe itself still goes in, which is the half that makes the above a rule and not a wall. */
    @Test
    fun `a delivery that is exactly the recipe is admitted whole`() {
        val s = run(tankAndRun(ghostAt = 7), RAIL_PERIOD * 8)
        assertTrue(
            s.conduits.massAt(Conduit.Rail, s.grid.tile(7, 3)) > 0L,
            "pure iron was turned away from an iron bill",
        )
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
        // ⚠️ **Not on the rail step's tick**, because the mark is what this test is about and the
        // rail step is what acts on it: a ghost that never received any metal has nothing to hand
        // back, so `scrapDeconstructing` takes it away the moment it next runs. That is correct and
        // is a different claim from this one. The fixture happened to land six edits from tick zero,
        // which was clear of the rail step until it stopped firing on tick zero.
        var laid = drag(VesselState.empty(grid).copy(creative = false), Conduit.Rail, y = 3, fromX = 2, toX = 8)
        while (laid.tick % OutofspaceReducer.RAIL_PERIOD == OutofspaceReducer.RAIL_OFFSET.toLong()) {
            laid = OutofspaceReducer.reduce(cfg, laid, emptyMap())
        }
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
     * ⛔ **Track under a machine's port comes up like any other track**, and the lock that used to
     * stop it is gone.
     *
     * The lock kept the port rules tractable and stopped a player stranding a machine by pulling up
     * the very thing feeding it. It bought both by making the most obvious edit in the game silently
     * do nothing — click the tile in front of your tank, watch nothing happen, with no message and
     * no mark. Stu hit it a dozen times in one session, which is the whole argument: protecting
     * somebody from an edit they deliberately made is not worth a rule they cannot see.
     *
     * What the lock was avoiding turned out to be answered already — see [removeConduit]. A machine
     * whose feed is pulled up is simply disconnected, which is the answer the game gives for
     * anything else in the way, and it is reversible with CANCEL while the metal is still going
     * back.
     */
    @Test
    fun `track under a machine's port can be marked like any other`() {
        val built = tankAndRun(ghostAt = null).copy(creative = false)
        // The tank at (3, 3) faces right, so its output port stands on (4, 3).
        val underPort = built.grid.tile(4, 3)
        val s = remove(built, underPort)
        val segment = s.conduits.at(Conduit.Rail, underPort)
        assertNotNull(segment, "marking is not removing: the track stands until its metal is back")
        assertTrue(segment.deconstructing, "the rail under a machine's port refused the mark")
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
