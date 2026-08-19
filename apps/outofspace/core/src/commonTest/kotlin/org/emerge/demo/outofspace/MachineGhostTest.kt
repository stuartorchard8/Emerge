package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Stream
import org.emerge.demo.outofspace.world.bufferRolesOf
import org.emerge.demo.outofspace.world.outputBufferRole
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.machineBillOfMaterials
import org.emerge.demo.outofspace.world.Material
import org.emerge.demo.outofspace.world.material
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.PortKind
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.portsOf
import org.emerge.demo.outofspace.world.constructionTileOf
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Processor
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.remapped
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.demo.outofspace.world.Structure
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Gauge
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A deck machine placed outside creative mode is a **ghost**: standing there, made of nothing, doing
 * nothing — increment 5b of `apps/outofspace/PLAN_self_building_rails.md`.
 *
 * These pin the identity break at the deck layer and nothing more. Nothing here builds a machine up,
 * because the construction port does not exist yet; what is pinned is that a placed machine arrives
 * empty, that it weighs nothing, that it does not run, and that it does not hold pressure.
 */
class MachineGhostTest {

    private val grid = Grid(16, 10)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private fun place(state: VesselState, tile: TileIndex, kind: DeckMachineKind): VesselState =
        OutofspaceReducer.reduce(
            cfg, state,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(
                Edit.Place(tile, Brush.Building(kind), Direction.Right),
            ))),
        )

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, mapOf(PlayerId(0) to OutofspaceInput(emptyList()))) }
        return s
    }

    /** One tick carrying [edits], for the edits a test wants to name. */
    private fun edit(state: VesselState, vararg edits: Edit): VesselState =
        OutofspaceReducer.reduce(cfg, state, mapOf(PlayerId(0) to OutofspaceInput(edits.toList())))

    /**
     * A tank of iron at (3, 4), finished track running right from it, and a ghost machine standing
     * at the far end with track threaded under its centre tile — where its construction port is.
     *
     * The machine is *stated* as a ghost rather than placed, for the reason `GhostTest`'s fixture
     * states its rail ghosts: a fixture says what the world is, and what a placement puts down is
     * the same thing by a longer road.
     */
    private fun tankAndGhost(machine: DeckMachine): VesselState {
        val at = machine.center
        val deck = DeckArray(grid)
        deck += Storage(grid.tile(3, 4), Direction.Right)
        deck.stand(machine, withCasing = false)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, grid.xOf(at), 4)
        return VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(
            grid.tile(3, 4),
            // What the machine is *made of*, not simply iron. A hull is steel and a processor is
            // titanium, and a ghost is finished only when every species in its bill is there —
            // so a tank of pure iron builds neither. See the plan's note on alloys.
            // Several times what the machine costs: a run of track holds packets of its own while
            // they travel, so a tank stocked to the bill exactly would leave the last of it strung
            // out along the belt. A fixture should never be the reason a build stalls.
            machine.kind.material.composition.scaledTo(
                    machineBillOfMaterials(machine.kind, machine.tiles(grid).size).total * 4,
                ),
        ).copy(creative = false)
    }

    @Test
    fun `a ghost machine at the end of a run draws material down it and builds itself`() {
        val at = grid.tile(10, 4)
        val start = tankAndGhost(Hull(at))
        assertTrue(start.deck.isGhost(at), "the fixture stood a finished machine")

        val s = run(start, OutofspaceReducer.RAIL_PERIOD * 60)
        assertFalse(s.deck.isGhost(at), "the machine never finished itself")
        assertTrue(s.deck.stuff.massAt(at) > 0L, "it is finished but made of nothing")
    }

    /**
     * ⚠️ **Two ghosts on one tile, and both are heard.**
     *
     * A ghost machine is fed at its centre and a ghost *rail* is fed at its own tile, so an unbuilt
     * machine standing on unbuilt track puts two construction sinks at one address. They are not
     * alternatives: the rail takes what it needs and the remainder rides on into the casing, which
     * is the order the whole loop depends on — a machine standing on track that cannot carry
     * anything is a machine nothing can ever reach.
     *
     * ⛔ Whatever holds a tile's appetites must therefore hold **all** of them. Stu found this: one
     * acceptance was overwriting the other, so whichever lost was invisible to the network and the
     * pair could never both finish. Kept as a test because a map keyed by tile is the obvious shape
     * to reach for and silently drops the second entry.
     */
    @Test
    fun `a ghost machine standing on ghost track feeds both`() {
        val at = grid.tile(10, 4)
        // ⚠️ **A gauge**, for two reasons that both matter. It is one tile, so the machine's own tile
        // and the tile it is fed at are the same place — which is what puts two sinks at one address.
        // And it is made of **iron**, like the track, so one pure delivery can satisfy both: an alloy
        // here would drag in a separate defect that has nothing to do with the question (see the
        // ignored test below).
        val start = tankAndGhost(Gauge(at))
        start.conduits.tracks[Conduit.Rail].release(at)
        assertTrue(start.deck.isGhost(at), "fixture: the machine should start unbuilt")
        assertTrue(start.conduits.isGhost(Conduit.Rail, at), "fixture: the track under it too")

        val s = run(start, OutofspaceReducer.RAIL_PERIOD * 120)

        assertTrue(s.conduits.isComplete(Conduit.Rail, at), "the track under the machine never finished")
        assertFalse(s.deck.isGhost(at), "the machine never finished")
    }

    /**
     * ⛔ **A ghost rail finished off an alloy never quite finishes.** Found while covering the case
     * above; parked rather than fixed, because it is nothing to do with the whitelist and Stu has not
     * been asked.
     *
     * [absorbIntoGhost] takes a proportional share of **every** species in the lump, including ones
     * the bill does not want. While the shortfall is bigger than a packet the whole lump goes in and
     * all is well. Once the shortfall drops below a packet the proportional branch takes over, and a
     * rail wanting only iron gets `need × ironFraction` of iron per pass — always a shade under what
     * it asked for. It converges on the bill and never reaches it: 999 permille for ever, with the
     * run jammed solid behind it.
     *
     * Fixed 2026-08-19 by giving the conduit path the deck path's rule: the final top-up is capped
     * **per species** at that species' own shortfall, so the last gram of iron is actually taken.
     */
    @Test
    fun `a ghost rail finished off an alloy finishes`() {
        val at = grid.tile(10, 4)
        val start = tankAndGhost(Hull(at))
        start.conduits.tracks[Conduit.Rail].release(at)

        val s = run(start, OutofspaceReducer.RAIL_PERIOD * 120)
        assertTrue(
            s.conduits.isComplete(Conduit.Rail, at),
            "the rail stalled at ${s.conduits.tracks.builtPermille(Conduit.Rail, at)} permille",
        )
    }

    /**
     * ⛔ **Titanium does not set off down a run with an unfinished iron rail on it.**
     *
     * Stu's case, and the one demand-by-kind alone cannot see. A ghost storage wants titanium; two
     * ghost rails between it and the tanks want iron. Both appetites are real, both are reachable,
     * and a whitelist that only asks *what sort of thing is wanted somewhere ahead* says yes to
     * titanium — so the titanium sets off, comes to rest against the first ghost rail, which cannot
     * be built from it and will not let it past, and the iron behind it can never get through. The
     * rails never finish, so the plug never dissolves, so nothing ever moves again. A deadlock, in
     * a network where every single thing on it is wanted.
     *
     * ⚠️ **The titanium joins the run in FRONT of the iron**, which is the tight version: what it
     * lets go of can never be overtaken by the iron behind it, so holding it back is the only move
     * available. A layout where the iron went first would pass by luck.
     *
     * What fixes it is [Demand.debt]: a route that runs past a hungry construction site carries that
     * site's shortfall, and is refused until enough material the *site* can use is already standing
     * between here and it. Nothing overtakes on a rail, so the plug is then guaranteed to have
     * dissolved by the time anything sent now arrives.
     */
    @Test
    fun `titanium waits behind a ghost rail until the iron for it is on its way`() {
        val deck = DeckArray(grid)
        deck += Storage(grid.tile(2, 4), Direction.Right)
        deck += Storage(grid.tile(5, 3), Direction.Down)
        val target = Storage(grid.tile(13, 4), Direction.Right)
        deck.stand(target, withCasing = false)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, 13, 4)
        val start = VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
            .stocked(grid.tile(2, 4), Material.Iron.composition.scaledTo(20 * Capacity.PACKET_MASS))
            // Enough to finish a storage several times over: a fixture should never be the
            // reason a build stalls, and a storage is some fifty packets of titanium.
            .stocked(grid.tile(5, 3), Material.Titanium.composition.scaledTo(120 * Capacity.PACKET_MASS))
            .copy(creative = false)
        val plugs = listOf(grid.tile(7, 4), grid.tile(8, 4))
        for (t in plugs) start.conduits.tracks[Conduit.Rail].release(t)

        val s = run(start, OutofspaceReducer.RAIL_PERIOD * 400)

        for (t in plugs) assertTrue(
            s.conduits.isComplete(Conduit.Rail, t),
            "the ghost rail at $t stalled at ${s.conduits.tracks.builtPermille(Conduit.Rail, t)} permille: " +
                "titanium got in front of the iron",
        )
        assertFalse(s.deck.isGhost(grid.tile(13, 4)), "the storage past the plug never finished")
    }

    /**
     * ⛔ **A half-built machine standing on paid-for track does not stop the traffic on it.**
     *
     * From Stu's save: iron sitting on a belt, refusing to travel down a fully built run to a ghost
     * rail beyond, because a storage 90% of the way through building itself stood over one tile of
     * that run. The storage wants titanium; the iron is not titanium; so the iron was turned away at
     * a tile whose *track* was finished and paid for, and it never went anywhere again.
     *
     * ⛔ **The anti-exploit is about unpaid track, and only that.** A ghost *rail* must refuse what
     * it cannot be built from, because otherwise a player routes their network over a free length of
     * track they have not paid for. A ghost *machine* has no such claim: the track under it is
     * finished, the machine is inert and permeable, and a lump crossing it takes nothing that is not
     * already there. It was the same rule applied to two different things.
     *
     * A machine site still *pulls* what it can use — that is what builds it — and it still refuses
     * what it cannot use at its own door, so nothing wrong gets absorbed. It simply no longer stands
     * in the road.
     *
     * ⚠️ A ghost machine standing on ghost **track** is a different matter, and unchanged: the track
     * blocks, on its own account. See `a ghost machine standing on ghost track feeds both`.
     */
    @Test
    fun `iron crosses a half-built machine to reach the ghost beyond it`() {
        val deck = DeckArray(grid)
        deck += Storage(grid.tile(1, 4), Direction.Right)
        // Titanium, and 3x3, so its construction port sits on the run at its centre.
        val blocking = Storage(grid.tile(7, 4), Direction.Right)
        deck.stand(blocking, withCasing = false)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 2, 12, 4)
        val start = VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(
            grid.tile(1, 4),
            Material.Iron.composition.scaledTo(20 * Capacity.PACKET_MASS),
        ).copy(creative = false)
        val target = grid.tile(12, 4)
        start.conduits.tracks[Conduit.Rail].release(target)
        assertTrue(start.deck.isGhost(grid.tile(7, 4)), "fixture: the machine in the way should be unbuilt")
        assertTrue(
            start.conduits.isComplete(Conduit.Rail, grid.tile(7, 4)),
            "fixture: the track under it is finished and paid for — that is the whole point",
        )

        val s = run(start, OutofspaceReducer.RAIL_PERIOD * 120)

        assertTrue(
            s.conduits.isComplete(Conduit.Rail, target),
            "the ghost beyond the machine never got its iron",
        )
        assertTrue(s.deck.isGhost(grid.tile(7, 4)), "the storage ate iron it cannot be built from")
    }

    /** Casing spreads over the footprint as it arrives, so no tile of it runs ahead of the others. */
    @Test
    fun `a big machine builds evenly across its footprint`() {
        val at = grid.tile(10, 4)
        val start = tankAndGhost(Processor(at, Direction.Right))
        val machine = start.deck[at]!!
        val tiles = machine.tiles(grid)
        assertTrue(tiles.size > 1, "a processor is supposed to cover more than one tile")

        // Part-way through, not finished: the question is how the metal is distributed while it is
        // still arriving.
        val s = run(start, OutofspaceReducer.RAIL_PERIOD * 12)
        val held = tiles.map { s.deck.stuff.massAt(it) }
        assertTrue(held.sum() > 0L, "nothing arrived at all")
        assertTrue(s.deck.isGhost(at), "it finished too fast for this to be measuring anything")
        // Even to within the remainder of one division per delivery.
        val spread = held.max() - held.min()
        assertTrue(
            spread * tiles.size <= held.sum() / 4,
            "casing piled up on one tile: held $held",
        )
    }

    /** ⛔ The anti-exploit, at machine scale: a ghost refuses what it cannot be built from. */
    @Test
    fun `a ghost machine refuses material it cannot be built from`() {
        val at = grid.tile(10, 4)
        val start = tankAndGhost(Hull(at)).stocked(
            grid.tile(3, 4),
            Mixture.of(Species.Quartz to 40 * Capacity.PACKET_MASS, energy = 0),
        )
        val s = run(start, OutofspaceReducer.RAIL_PERIOD * 60)
        assertTrue(s.deck.isGhost(at), "a hull built itself out of silica")
        assertEquals(0L, s.deck.stuff.massAt(at), "silica got into the casing")
    }

    /**
     * ⛔ The matter sink, closed. A steel machine fed pure iron takes **nothing**: it is refused at
     * the tile rather than swallowed, so a tank of iron cannot drain into a hull that could never
     * finish. Before the per-species rule it absorbed iron for ever, past its own billed mass.
     */
    @Test
    fun `a steel machine fed pure iron takes nothing at all`() {
        val at = grid.tile(10, 4)
        val start = tankAndGhost(Hull(at)).stocked(
            grid.tile(3, 4),
            Mixture.of(Species.Iron to 40 * Capacity.PACKET_MASS, energy = 0),
        )
        val s = run(start, OutofspaceReducer.RAIL_PERIOD * 60)
        assertTrue(s.deck.isGhost(at), "a hull built itself out of iron alone")
        assertEquals(0L, s.deck.stuff.massAt(at), "iron went into a machine that can never finish")
    }

    /**
     * The intended path: mixing is a **logistics** problem, not a refining one. A storage blended to
     * the recipe emits packets that are proportional slices of what it holds, so keeping a tank
     * inside the tolerance is what makes steel machines buildable.
     *
     * The blend here is deliberately *not* the exact recipe — 95:5 against steel's 99:1 — because
     * the tolerance is the point: a player has to hold a ratio, not hit a number.
     */
    @Test
    fun `a storage blended within tolerance builds a steel machine`() {
        val at = grid.tile(10, 4)
        val bill = machineBillOfMaterials(DeckMachineKind.Hull, 1)
        val start = tankAndGhost(Hull(at)).stocked(
            grid.tile(3, 4),
            Mixture.of(
                    Species.Iron to 95 * bill.total * 4 / 100,
                    Species.Carbon to 5 * bill.total * 4 / 100,
                    energy = 0,
                ),
        )
        val s = run(start, OutofspaceReducer.RAIL_PERIOD * 60)
        val run = (4..10).joinToString(",") { x -> "${s.rail.resourceAt(grid.tile(x, 4))}" }
        assertFalse(s.deck.isGhost(at), "a blend inside the tolerance did not build the hull: casing=${s.deck.stuff.massAt(at)} of ${bill.total}; run=$run; tank=${s.buffers.massAt(grid.tile(3,4))}")
    }

    /** Building it is a transfer, not an arrival: the world gains nothing from off-world. */
    @Test
    fun `building a machine conserves mass`() {
        val at = grid.tile(10, 4)
        val start = tankAndGhost(Hull(at))
        val opening = start.inTransitMass + start.builtMass
        val s = run(start, OutofspaceReducer.RAIL_PERIOD * 60)
        assertFalse(s.deck.isGhost(at), "it never finished, so this proves nothing")
        assertEquals(
            opening,
            s.inTransitMass + s.builtMass,
            "grams went missing between the cargo and the fabric ledger",
        )
    }

    /** Once it holds its bill it is simply a machine: it runs, and its own ports are back. */
    @Test
    fun `a finished machine gets its ports back`() {
        val at = grid.tile(10, 4)
        val start = tankAndGhost(Processor(at, Direction.Right))
        assertTrue(start.deck.isGhost(at), "fixture")

        val s = run(start, OutofspaceReducer.RAIL_PERIOD * 200)
        assertFalse(s.deck.isGhost(at), "the smelter never finished")
        val ports = portsOf(grid, s.deck[at]!!)
        assertTrue(ports.any { it.kind == PortKind.Output }, "a finished smelter has an output port")
    }

    // ── Deconstruction (increment 5d) ─────────────────────────────────────────

    private fun remove(state: VesselState, tile: TileIndex): VesselState =
        OutofspaceReducer.reduce(
            cfg, state,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Remove(tile, DeleteLayer.Deck)))),
        )

    /**
     * A finished machine standing on track, with somewhere for its metal to *go*.
     *
     * The far end of the run is a **ghost rail**, which is the simplest honest sink: a length of
     * track short of its bill draws material down the line to itself. Without a sink the first
     * packet the machine hands back stands on its own tile for ever and the machine jams — the
     * occupied-tile family the rails already have written up, not something to paper over here.
     */
    private fun builtMachine(machine: DeckMachine, sink: Boolean = true): VesselState {
        val deck = DeckArray(grid)
        deck += machine
        // ⚠️ **A real sink, not a construction site.** A ghost rail used to stand in for one here,
        // and it worked only because demand was a question of *kind*: it said "iron is wanted" and
        // the whole of a hull's casing rode down the run on the strength of it. Now that a tile
        // knows how much is already covered, a one-tile appetite stops the second packet — and a
        // machine that cannot put its metal anywhere correctly refuses to start coming apart.
        // Facing Left puts its input on the run at (4, 4) and its output off the track entirely, so
        // it is a sink and nothing else.
        if (sink) deck += Storage(grid.tile(3, 4), Direction.Left)
        val rails = arrayOfNulls<Segment>(grid.size)
        // Past the machine, not merely up to it: a bridge hands its casing back through its *output*
        // end, a tile beyond its centre, and a run that stopped short would leave it nowhere to put
        // anything.
        joinRow(grid, rails, 4, minOf(grid.width - 2, grid.xOf(machine.center) + 2), 4)
        val s = VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)
        return s
    }

    @Test
    fun `outside creative mode deleting a machine marks it rather than removing it`() {
        val at = grid.tile(10, 4)
        val s = remove(builtMachine(Hull(at)), at)
        assertNotNull(s.deck[at], "the machine was taken away outright")
        assertTrue(at in s.scrapping, "and it was not marked either")
    }

    @Test
    fun `in creative mode deleting a machine removes it outright`() {
        val at = grid.tile(10, 4)
        val s = remove(builtMachine(Hull(at)).copy(creative = true), at)
        assertNull(s.deck[at], "creative deletion is supposed to be immediate")
        assertTrue(s.scrapping.isEmpty(), "and it should mark nothing")
    }

    @Test
    fun `a marked machine hands its casing back and then ceases to be`() {
        val at = grid.tile(10, 4)
        val before = builtMachine(Hull(at))
        assertTrue(before.deck.stuff.massAt(at) > 0L, "the fixture stood a machine with no casing")

        val s = run(remove(before, at), OutofspaceReducer.RAIL_PERIOD * 60)
        assertNull(s.deck[at], "the marked machine is still standing")
        assertTrue(at !in s.scrapping, "the mark outlived the machine that earned it")
    }

    /** Every gram of it comes back as cargo — deconstruction is a transfer, not a deletion. */
    @Test
    fun `deconstructing a machine conserves mass`() {
        val at = grid.tile(10, 4)
        val before = builtMachine(Hull(at))
        val opening = before.inTransitMass + before.builtMass
        val s = run(remove(before, at), OutofspaceReducer.RAIL_PERIOD * 60)
        assertNull(s.deck[at], "it never finished, so this proves nothing")
        assertEquals(
            opening,
            s.inTransitMass + s.builtMass,
            "grams went missing between the fabric and the cargo ledger",
        )
    }

    /**
     * ⚠️ The rails' collision, at the deck layer. A machine handing its casing back is short of its
     * bill from the first load, so it reads as a ghost and absorbs its own metal straight off the
     * belt — stable, stationary, and indistinguishable from deconstruction doing nothing.
     */
    @Test
    fun `a machine being taken apart does not build itself back up`() {
        val at = grid.tile(10, 4)
        val before = builtMachine(Hull(at))
        val full = before.deck.stuff.massAt(at)
        val s = run(remove(before, at), OutofspaceReducer.RAIL_PERIOD * 2)
        val casing = if (s.deck[at] == null) 0L else s.deck.stuff.massAt(at)
        assertTrue(casing < full, "the marked machine is still holding all of its casing")
    }

    /**
     * ⛔ Stu's ordering: the casing is the one thing whose removal cannot be undone, so it goes
     * **last**. A machine still holding cargo must hand that back before it starts on itself, or its
     * own demolition destroys its contents.
     */
    @Test
    fun `a machine hands its contents back before its casing`() {
        val at = grid.tile(10, 4)
        val tank = Storage(at, Direction.Right)
        val before = builtMachine(tank).stocked(
            at,
            Mixture.of(Species.Iron to 3 * Capacity.PACKET_MASS, energy = 0),
            BufferRole.Inside,
        )
        val full = before.deck.stuff.massAt(at)
        val stored = before.buffers.massAt(bufferTile(grid, tank, at, BufferRole.Inside)!!)
        assertTrue(stored > 0L, "the fixture stocked nothing")

        // One step: enough to start handing back, nowhere near enough to finish.
        val s = run(remove(before, at), OutofspaceReducer.RAIL_PERIOD)
        assertEquals(full, s.deck.stuff.massAt(at), "the casing came apart while cargo was still inside")
    }

    /**
     * ⛔ A **processing buffer goes back out through the input port**, not the output and not the
     * centre it is stored at.
     *
     * A processor holds a lump in the middle of being worked. That lump is not finished goods, so it
     * has no business leaving by the output — the way it came in is the honest way back out. The
     * store itself lives at the machine's centre, so this is the one place where where a store *is*
     * and where its contents are handed back deliberately differ.
     */
    @Test
    fun `a processing buffer is handed back through the input port`() {
        val at = grid.tile(9, 4)
        val processor = Processor(at, Direction.Right)
        val inside = bufferTile(grid, processor, at, BufferRole.Inside)!!
        val input = bufferTile(grid, processor, at, BufferRole.Input)!!
        assertEquals(at, inside, "a processor's working store is supposed to sit at its centre")
        assertTrue(input != at, "and its input port is supposed to be somewhere else")

        val half = Mixture.of(Species.Iron to Capacity.PACKET_MASS / 2, energy = 0)
        val before = builtMachine(processor).stocked(at, half, BufferRole.Inside)

        // ⚠️ Asserted as **which side of the machine it is on**, not as which tile it is standing on.
        // The fixture has a ghost drawing to the left, so the moment the lump is handed back it
        // starts travelling; pinning it to the input tile pinned how far it had got in one step,
        // which is a fact about the belt's speed and not about which mouth it left by.
        val s = run(remove(before, at), OutofspaceReducer.RAIL_PERIOD)
        val inputSide = (4..grid.xOf(input)).sumOf { s.rail.massAt(grid.tile(it, 4)) }
        assertTrue(inputSide > 0L, "the half-worked lump did not come back out of the input")
        assertEquals(0L, s.rail.massAt(at), "it came out of the centre instead")
    }

    /**
     * ⛔ An extractor keeps **one store**, and its own output port drains it — so deconstruction
     * only waits, exactly as it does for a tank. It used to keep a second, the cell in its jaws,
     * which was the one working store in the game with no input port to give itself back through.
     */
    @Test
    fun `an extractor keeps one store and its output drains it`() {
        val at = grid.tile(9, 4)
        val extractor = Extractor(at, Direction.Right)
        assertEquals(
            listOf(BufferRole.Product),
            bufferRolesOf(extractor),
            "an extractor is supposed to keep one store now",
        )
        assertEquals(
            BufferRole.Product,
            outputBufferRole(extractor, Stream.Product),
            "and its own output port is what drains it",
        )
    }

    /**
     * ⛔ A **bridge has no centre port**, at either end of its life. The middle of a span is over the
     * gap it is bridging and no belt can reach it, so a gantry is built through the end it takes
     * material in at and gives its metal back through the end it puts material down at.
     */
    @Test
    fun `a bridge is built through its own input rather than a centre port`() {
        val at = grid.tile(9, 4)
        val bridge = Bridge(at, Direction.Right)
        val port = portsOf(grid, bridge).first { it.kind == PortKind.Input }
        assertTrue(port.tile != at, "a bridge's input is supposed to be at its end, not its centre")

        assertEquals(port.tile, constructionTileOf(grid, bridge), "a bridge is fed at its input end")
        // Everything else is fed at its centre, which is the rule this is the exception to.
        val hull = Hull(grid.tile(10, 4))
        assertEquals(hull.center, constructionTileOf(grid, hull), "a hull is fed at its centre")
    }

    /**
     * ⛔ A bridge **goes on working while it is marked**: it is a length of track held in the air, and
     * a run does not stop carrying because the player condemned it. Its input closes only once it has
     * nothing left inside — closing it mid-span would strand the lump on the gantry.
     */
    @Test
    fun `a marked bridge carries what is on it out of the far end before it comes apart`() {
        val at = grid.tile(9, 4)
        val bridge = Bridge(at, Direction.Right)
        val lump = Mixture.of(Species.Iron to Capacity.PACKET_MASS / 2, energy = 0)
        val carried = lump.total
        val loaded = builtMachine(bridge).stocked(at, lump, BufferRole.Input)
        val casing = loaded.deck.stuff.massAt(at)

        // One step: the shuffle moves the load along, and the casing has not started yet.
        val s = run(remove(loaded, at), OutofspaceReducer.RAIL_PERIOD)
        assertEquals(casing, s.deck.stuff.massAt(at), "the span came apart with a lump still on it")
        assertTrue(carried > 0L, "fixture")

        // And it does eventually go, load first and metal after.
        val done = run(s, OutofspaceReducer.RAIL_PERIOD * 40)
        assertNull(done.deck[at], "the marked bridge never finished coming apart")
    }

    @Test
    fun `an empty marked bridge closes its input and gives its casing back out its output`() {
        val at = grid.tile(9, 4)
        val bridge = Bridge(at, Direction.Right)
        val exit = portsOf(grid, bridge).first { it.kind == PortKind.Output }.tile
        val entry = portsOf(grid, bridge).first { it.kind == PortKind.Input }.tile
        // ⚠️ **A sink on the OUTPUT side**, which is a fixture change with a reason. This used to
        // pass `sink = false` so that nothing drew the casing away and it could be found on the exit
        // tile — but a machine with nowhere to put its metal now correctly refuses to shed any, so
        // that fixture proves the bridge waits rather than which mouth it uses. The sink goes to the
        // right instead, and the question is answered by where the casing is *not*: a run flowing
        // rightward can never carry anything back over the entry or the middle of the span.
        val before = builtMachine(bridge, sink = false).also {
            it.conduits.tracks[Conduit.Rail].release(grid.tile(11, 4))
        }
        val casingBefore = before.deck.stuff.massAt(at)
        assertTrue(casingBefore > 0L, "fixture: the bridge should start with its casing")

        val s = run(remove(before, at), OutofspaceReducer.RAIL_PERIOD)
        assertTrue(s.deck.stuff.massAt(at) < casingBefore, "no casing came out of the bridge at all")
        assertEquals(0L, s.rail.massAt(at), "casing came out of the middle of the span")
        assertEquals(0L, s.rail.massAt(entry), "casing came back out of the end it takes deliveries at")
        assertTrue(exit != entry, "fixture: a span's two ends are different tiles")
    }

    // ── Calling it off ────────────────────────────────────────────────────────

    /**
     * ⚠️ **Cancelling restores a target, not a machine.** Ghost-ness is derived — a thing is unbuilt
     * exactly when it is short of its bill — so taking the mark off is the whole of the operation.
     * A machine that has given nothing back still holds its whole bill, so it is a *finished*
     * machine again the instant the mark goes, with no rebuilding to do and nothing to book.
     */
    @Test
    fun `cancelling a machine that has given nothing back restores it at once`() {
        val at = grid.tile(10, 4)
        // ⚠️ **No sink**, so that "given nothing back" is actually true. With one, the hull starts
        // shedding casing in the very tick it is marked and there is no untouched machine left to
        // cancel. That it holds perfectly still without one is the demand rule doing its job — see
        // `DemandTest`.
        val start = builtMachine(Hull(at), sink = false)
        val before = start.deck.stuff.massAt(at)

        val marked = remove(start, at)
        assertTrue(at in marked.scrapping, "fixture: it should be condemned")

        val s = edit(marked, Edit.Cancel(at))
        assertFalse(at in s.scrapping, "the mark should be gone")
        assertFalse(s.deck.isGhost(at), "an untouched machine is finished, not a construction site")
        assertEquals(before, s.deck.stuff.massAt(at), "and not a gram of it moved")
    }

    /**
     * The other half: a machine short of its bill and then reprieved is an ordinary construction
     * site, and fills itself back up off the network. Nothing puts the metal back — whatever went
     * onto the belt has to come home the way anything else does.
     *
     * ⚠️ The half-built state is reached by letting a construction site fill **part** way rather
     * than by driving a real deconstruction to its middle: the condition cancelling actually reads is
     * "short of its bill", and a half-fed site is in it. It has to hold *some* casing, though — an
     * entirely empty ghost has nothing to hand back and vanishes the instant it is marked, which is
     * its own test further down.
     */
    @Test
    fun `cancelling a machine short of its bill leaves a construction site that rebuilds`() {
        val at = grid.tile(10, 4)
        val start = run(tankAndGhost(Processor(at, Direction.Right)), OutofspaceReducer.RAIL_PERIOD * 8)
        assertTrue(start.deck.isGhost(at), "fixture: it should still be short of its bill")
        assertTrue(start.deck.stuff.massAt(at) > 0L, "fixture: and hold some casing already")

        var s = remove(start, at)
        assertTrue(at in s.scrapping, "fixture: and then be condemned")

        s = edit(s, Edit.Cancel(at))
        assertFalse(at in s.scrapping, "the mark should be gone")
        assertTrue(s.deck.isGhost(at), "a machine short of its bill is a construction site")

        val back = run(s, OutofspaceReducer.RAIL_PERIOD * 90)
        assertFalse(back.deck.isGhost(at), "and a construction site with a tank behind it rebuilds")
    }

    /** A ghost marked for deconstruction is just a part-built machine: it dumps what it has and goes. */
    @Test
    fun `a ghost marked for deconstruction simply goes`() {
        val at = grid.tile(10, 4)
        val start = tankAndGhost(Hull(at))
        assertTrue(start.deck.isGhost(at), "fixture")

        val s = run(remove(start, at), OutofspaceReducer.RAIL_PERIOD * 8)
        assertNull(s.deck[at], "an empty ghost had nothing to hand back and should have gone at once")
    }

    /**
     * ⚠️ The mark is a set of tile **indexes**, and a tile index means a different place the moment
     * the lattice changes shape. Carried across a resize rather than remapped, a world that grew came
     * back with its condemned machines reprieved and some innocent tile marked instead — silent both
     * ways, since the machine simply stands there at full casing looking finished.
     *
     * The grid remap has produced this class of bug twice before; see the plan's retrospective.
     */
    @Test
    fun `the mark follows its machine when the grid is remapped`() {
        val at = grid.tile(10, 4)
        val marked = remove(builtMachine(Hull(at)), at)
        assertTrue(at in marked.scrapping, "fixture")

        val wider = Grid(grid.width + 6, grid.height + 4)
        val moved = marked.remapped(wider, dx = 3, dy = 2)
        val now = wider.tile(grid.xOf(at) + 3, grid.yOf(at) + 2)

        assertNotNull(moved.deck[now], "the machine itself moved")
        assertTrue(now in moved.scrapping, "but its mark was left behind, so it is reprieved")
        assertTrue(moved.scrapping.size == 1, "and nothing else was condemned in its place")
    }

    /**
     * The mark is the only thing about any of this that is new on disk. Ghost-ness is derived from
     * the casing and saves for free; being condemned is a decision and has to be written down.
     */
    @Test
    fun `a machine marked for deconstruction survives a save`() {
        val at = grid.tile(10, 4)
        val before = remove(builtMachine(Hull(at)), at)
        assertTrue(at in before.scrapping, "fixture")

        val after = Save.read(Save.write(before))
        assertTrue(at in after.scrapping, "the machine came back from the save reprieved")
    }

    @Test
    fun `an unmarked machine comes back unmarked`() {
        val after = Save.read(Save.write(builtMachine(Hull(grid.tile(10, 4)))))
        assertTrue(after.scrapping.isEmpty(), "a save condemned a machine nobody had marked")
    }

    @Test
    fun `a machine placed outside creative mode arrives with no metal in it`() {
        val at = grid.tile(8, 5)
        val s = place(VesselState.empty(grid).copy(creative = false), at, DeckMachineKind.Processor)

        val m = s.deck[at]
        assertNotNull(m, "the processor did not go down at all")
        assertTrue(m is Processor)
        assertTrue(s.deck.isGhost(at), "the processor arrived with its casing")
        for (tile in m.tiles(grid)) {
            assertEquals(0L, s.deck.stuff.massAt(tile), "casing at $tile")
        }
    }

    @Test
    fun `a machine placed in creative mode is finished`() {
        val at = grid.tile(8, 5)
        val s = place(VesselState.empty(grid).copy(creative = true), at, DeckMachineKind.Processor)

        assertFalse(s.deck.isGhost(at), "creative placement is supposed to conjure the whole machine")
        assertTrue(s.deck.stuff.massAt(at) > 0L, "and its casing is real matter")
    }

    /** Nothing arrived from off-world, so the ledger has nothing to book. */
    @Test
    fun `a ghost machine costs the world nothing`() {
        val at = grid.tile(8, 5)
        val ghost = place(VesselState.empty(grid).copy(creative = false), at, DeckMachineKind.Processor)
        assertEquals(0L, ghost.insertedEnergy, "a ghost brought heat into the world from nowhere")

        val real = place(VesselState.empty(grid).copy(creative = true), at, DeckMachineKind.Processor)
        assertTrue(real.insertedEnergy > 0L, "creative placement is an insertion and is booked as one")
    }

    /**
     * ⚠️ The accepted consequence of a massless frame: a room is open to space until its *last* hull
     * tile is finished. Stated so that softening it later has to be a deliberate act.
     */
    @Test
    fun `a hull ghost does not hold pressure`() {
        val at = grid.tile(8, 5)
        val s = place(VesselState.empty(grid).copy(creative = false), at, DeckMachineKind.Hull)

        assertTrue(s.deck[at] is Hull, "the hull went down")
        assertTrue(s.deck.isGhost(at), "and it is a ghost")
        assertFalse(
            s.structure.isImpermeable(at),
            "a frame with no metal in it is holding air out",
        )
    }

    @Test
    fun `a finished hull does hold pressure`() {
        val at = grid.tile(8, 5)
        val s = place(VesselState.empty(grid).copy(creative = true), at, DeckMachineKind.Hull)
        assertEquals(Structure.Hull, s.structure[at.index])
    }

    /** A ghost weighs nothing, so a vessel gains no mass by having one drawn on it. */
    @Test
    fun `a ghost machine weighs nothing`() {
        val empty = VesselState.empty(grid).copy(creative = false)
        val withGhost = place(empty, grid.tile(8, 5), DeckMachineKind.Processor)
        assertEquals(
            empty.deck.stuff.totalMass,
            withGhost.deck.stuff.totalMass,
            "the deck got heavier for a machine made of nothing",
        )
    }

    /**
     * The placement restriction still governs a ghost even though the ghost displaces nothing.
     *
     * Air must have somewhere to go before a solid machine may be drawn, or a player would frame out
     * a machine in a sealed pocket and be told only at completion that it could never have been
     * built there. The question is asked at placement; the displacing waits for the metal.
     */
    @Test
    fun `a ghost is refused where the air would have nowhere to go`() {
        val g = Grid(5, 5)
        val cfg = OutofspaceConfig(initialGrid = g)
        val deck = DeckArray(g)
        val pocket = g.tile(2, 2)
        for (d in Direction.ALL) deck += Hull(g.neighbour(pocket, d))
        val sealed = VesselState(
            g, deck = deck, buffers = BufferLayer.forDeck(g, deck), rail = RailLayer.empty(g.size),
        ).copy(creative = false)

        val after = OutofspaceReducer.reduce(
            cfg, sealed,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(
                Edit.Place(pocket, Brush.Building(DeckMachineKind.Hull), Direction.Right),
            ))),
        )
        assertNull(after.deck[pocket], "a ghost went down in a pocket the air cannot leave")
    }

    /** Accepted, it still pushes nothing aside: there is no metal in it yet to push with. */
    @Test
    fun `placing a ghost moves no air`() {
        val g = Grid(40, 28)
        val cfg = OutofspaceConfig(initialGrid = g)
        var start = starterVessel(g)
        repeat(20) { start = OutofspaceReducer.reduce(cfg, start, mapOf(PlayerId(0) to OutofspaceInput(emptyList()))) }
        val open = g.tiles.first { start.air.pressureAt(it) > 0L && start.deck[it] == null }
        val before = start.atmosphereMass
        val air = start.air.pressureAt(open)

        val after = OutofspaceReducer.reduce(
            cfg, start.copy(creative = false),
            mapOf(PlayerId(0) to OutofspaceInput(listOf(
                Edit.Place(open, Brush.Building(DeckMachineKind.Hull), Direction.Right),
            ))),
        )
        assertTrue(after.deck.isGhost(open), "it went down as a ghost")
        assertEquals(before, after.atmosphereMass, "the ship's air changed")
        assertEquals(air, after.air.pressureAt(open), "the air under the frame was pushed aside")
    }

    /**
     * ⛔ The anti-exploit. Let a ghost run and the casing is a formality — the player already has
     * everything the machine was for.
     */
    @Test
    fun `a ghost machine does not run`() {
        val at = grid.tile(8, 5)
        var s = place(VesselState.empty(grid).copy(creative = false), at, DeckMachineKind.Extractor)
        val before = s.extractedMass
        s = run(s, 60)
        assertEquals(before, s.extractedMass, "a ghost extractor bit something")
    }
}
