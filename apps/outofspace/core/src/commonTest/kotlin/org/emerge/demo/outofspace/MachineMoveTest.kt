package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.machine.Concentrator
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.newDeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A machine is picked up and put down somewhere else, and it is the same machine when it lands.**
 *
 * `PLAN_machine_relocation.md` increment 1. The gesture that reaches this is increment 2; everything
 * here drives `Edit.Move` directly, which is where `Edit.Rotate` has always been driven from.
 *
 * ⛔ **The ledgers are the test.** `rebuildInPlace` is booked through none of them — nothing crosses
 * the vessel's boundary — so a bug here is matter appearing or vanishing rather than a machine
 * looking wrong, and only a balance will say so.
 */
class MachineMoveTest {

    private val grid = Grid(16, 14)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private fun world(build: DeckArray.() -> Unit): VesselState {
        val deck = DeckArray(grid)
        deck.build()
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(arrayOfNulls<org.emerge.demo.outofspace.world.Segment>(grid.size).toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = true)
    }

    private fun move(s: VesselState, from: TileIndex, to: TileIndex, facing: Direction): VesselState =
        OutofspaceReducer.reduce(
            cfg, s, mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Move(from, to, facing)))),
        )

    private fun tick(s: VesselState): VesselState = OutofspaceReducer.reduce(cfg, s, emptyMap())

    /** Every gram of casing the deck is holding, wherever it stands. */
    private fun casing(s: VesselState): Long {
        var total = 0L
        for (t in grid.tiles) total += s.deck.stuff.massAt(t)
        return total
    }

    private fun deckEnergy(s: VesselState): Long {
        var total = 0L
        for (t in grid.tiles) total += s.deck.stuff.energyAt(t)
        return total
    }

    // ── The round trip ───────────────────────────────────────────────────────

    /**
     * ⛔ **The acceptance that matters most.** A stocked, warm, tuned machine goes somewhere and comes
     * back, and everything about it and about the ship reads what it read before.
     *
     * ⚠️ **Two moves rather than one**, because a move that goes nowhere is the case a bug can hide
     * in: `from == to` leaves the anchor a fixed point, which is exactly the assumption the split of
     * `rebuildInPlace` exists to remove. The machine has to actually travel and come home.
     */
    @Test
    fun `a machine that goes away and comes back changes nothing`() {
        val at = grid.tile(4, 4)
        val away = grid.tile(10, 8)
        var s = world { this += Concentrator(at, Direction.Right) }
        s.buffers.put(
            bufferTile(grid, s.deck[at]!!, at, BufferRole.Input)!!,
            Mixture.of(Species.Iron to Capacity.PACKET_MASS, energy = 0L).atAmbient(),
        )
        s = tick(s)

        // ⚠️ **Measured against a control tick, not against the world before the tick.** Heat conducts
        // between the casing and the room every tick, so a raw before-and-after of the deck's energy
        // reports conduction and calls it a leak — it did, by ten megajoules, the first time this was
        // written. What has to be true is that a tick containing a round trip is indistinguishable
        // from a tick containing nothing, which is a *differential* and is exact.
        val control = tick(s)
        val moved = OutofspaceReducer.reduce(
            cfg, s,
            mapOf(
                PlayerId(0) to OutofspaceInput(
                    listOf(Edit.Move(at, away, Direction.Down), Edit.Move(away, at, Direction.Right)),
                ),
            ),
        )

        assertEquals(control.deck[at], moved.deck[at], "the machine that came back is not the one that left")
        assertNull(moved.deck[away], "it left a copy where it had been")
        assertEquals(
            control.inStore(at, BufferRole.Input),
            moved.inStore(at, BufferRole.Input),
            "its store did not come back",
        )
        assertEquals(casing(control), casing(moved), "the casing did not weigh the same when it landed")
        assertEquals(deckEnergy(control), deckEnergy(moved), "the heat in the casing did not come back")

        assertEquals(control.massBalance, moved.massBalance, "the mass ledger moved")
        assertEquals(control.airBalance, moved.airBalance, "the air ledger moved")
        assertEquals(control.heatBalance, moved.heatBalance, "the heat ledger moved")
        assertEquals(control.momentumBalanceX, moved.momentumBalanceX, "the momentum ledger moved in x")
        assertEquals(control.momentumBalanceY, moved.momentumBalanceY, "the momentum ledger moved in y")
        // ⚠️ **A delta, not a zero.** `angularBalance` is already non-zero on today's code: `netTorque`
        // books `pressureTorque` onto the ship with nothing on the other side. Asserting zero here
        // would be red for a reason that has nothing to do with moving a machine — see `Vessel.kt`.
        assertEquals(control.angularBalance, moved.angularBalance, "moving a machine imparted a torque")
    }

    /** And it really did travel, rather than the round trip being two refusals in a row. */
    @Test
    fun `a machine leaves the tiles it was standing on and arrives on new ones`() {
        val at = grid.tile(4, 4)
        val away = grid.tile(10, 8)
        var s = world { this += Concentrator(at, Direction.Right) }
        s = tick(s)

        s = move(s, at, away, Direction.Down)

        assertNull(s.deck[at], "it did not leave")
        assertNotNull(s.deck[away], "it did not arrive")
        assertEquals(0L, s.deck.stuff.massAt(at), "it left casing behind on a tile it had vacated")
        assertTrue(s.deck.stuff.massAt(away) > 0L, "it arrived without its casing")
    }

    /**
     * ⭐ **A machine takes its stores with it, by role and not by tile.**
     *
     * A bridge is the proof, because its three slots are `Input`, `Inside` and `Product` at either end
     * of its line and its middle — so a move that carried them by tile index would deliver a gantry's
     * load to the wrong end of it, or off the machine entirely.
     */
    @Test
    fun `a loaded bridge carries its three slots to the far end of the ship`() {
        val at = grid.tile(4, 6)
        val away = grid.tile(11, 6)
        var s = world { this += Bridge(at, Direction.Right) }
        val load = listOf(
            BufferRole.Input to Species.Iron,
            BufferRole.Inside to Species.Quartz,
            BufferRole.Product to Species.Copper,
        )
        for ((role, species) in load) {
            s.buffers.put(
                bufferTile(grid, s.deck[at]!!, at, role)!!,
                Mixture.of(species to Capacity.PACKET_MASS, energy = 0L).atAmbient(),
            )
        }
        s = tick(s)
        val massBefore = s.massBalance

        s = move(s, at, away, Direction.Right)

        for ((role, species) in load) {
            val held = s.inStore(away, role)
            assertNotNull(held, "$role did not travel")
            assertEquals(species, held.dominant, "$role arrived holding the wrong slot's cargo")
        }
        assertEquals(massBefore, s.massBalance, "carrying a gantry's load moved the mass ledger")
    }

    // ── Its own tiles do not count as in the way ─────────────────────────────

    /**
     * ⛔ **Nudging one tile is the commonest use of the tool, and it overlaps the machine itself.**
     *
     * Asked without the exemption `canStand` refuses every one of these, because a 3×3 shifted by one
     * still covers six of the tiles it is standing on. A cursor that read red here would be wrong
     * about the ordinary case.
     */
    @Test
    fun `a machine may be nudged onto tiles it is already standing on`() {
        val at = grid.tile(5, 5)
        var s = world { this += Concentrator(at, Direction.Right) }
        s = tick(s)

        var was = at
        for (step in listOf(grid.tile(6, 5), grid.tile(6, 6), grid.tile(5, 6), grid.tile(5, 5))) {
            s = move(s, was, step, Direction.Right)
            assertNotNull(s.deck[step], "a nudge from $was to $step was refused")
            was = step
        }
    }

    /** ⛔ And turning in place, which is a move whose address happens not to change. */
    @Test
    fun `a span turns in place because a move is not a rotation`() {
        val at = grid.tile(6, 6)
        var s = world { this += Bridge(at, Direction.Right) }
        s = tick(s)
        val flat = s.deck[at]!!.tiles(grid).toSet()

        s = move(s, at, at, Direction.Down)

        val upright = s.deck[at]!!.tiles(grid).toSet()
        assertEquals(setOf(grid.tile(5, 6), grid.tile(6, 6), grid.tile(7, 6)), flat)
        assertEquals(setOf(grid.tile(6, 5), grid.tile(6, 6), grid.tile(6, 7)), upright)
    }

    // ── The three refusals ───────────────────────────────────────────────────

    /** ⛔ Somewhere it does not fit is a refusal, and a refusal changes nothing at all. */
    @Test
    fun `a move onto an occupied tile is refused and books nothing`() {
        val mover = grid.tile(4, 4)
        val other = grid.tile(9, 4)
        var s = world {
            this += Concentrator(mover, Direction.Right)
            this += newDeckMachine(DeckMachineKind.Warehouse, other, Direction.Right)
        }
        s = tick(s)
        val massBefore = s.massBalance
        val casingBefore = casing(s)

        s = move(s, mover, other, Direction.Right)

        assertNotNull(s.deck[mover], "the mover left even though it was refused")
        assertEquals(DeckMachineKind.Warehouse, s.deck[other]!!.kind, "it landed on top of the warehouse")
        assertEquals(massBefore, s.massBalance, "a refused move booked something")
        assertEquals(casingBefore, casing(s), "a refused move changed the casing")
    }

    /** ⛔ And off the rim, which `canStand` answers for a footprint that would hang over the edge. */
    @Test
    fun `a move that would hang off the grid is refused`() {
        val at = grid.tile(5, 5)
        var s = world { this += Concentrator(at, Direction.Right) }
        s = tick(s)

        s = move(s, at, grid.tile(0, 5), Direction.Right)

        assertNotNull(s.deck[at], "a 3x3 was allowed to hang off the rim")
    }

    /**
     * ⛔ **A ghost does not move**, either while it is building itself up or while it is coming apart
     * (Stu, 2026-09-09).
     *
     * ⚠️ **The hazard is real and not hypothetical**: `rebuildInPlace` does `deck -=` followed by
     * `stand(withCasing = true)`, which releases what a half-built machine is holding and stands a
     * whole one in its place. That is matter from nowhere, and the mass ledger is what would report
     * it — a long way from the gesture that caused it.
     */
    @Test
    fun `a ghost does not move`() {
        val at = grid.tile(4, 4)
        val deck = DeckArray(grid)
        // Standing, holding nothing: a machine that has been marked out but not yet built.
        deck.standGhost(Concentrator(at, Direction.Right))
        var s = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(arrayOfNulls<org.emerge.demo.outofspace.world.Segment>(grid.size).toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
        assertTrue(s.deck.isGhost(at), "fixture: this was supposed to be a ghost")
        val massBefore = s.massBalance

        s = move(s, at, grid.tile(10, 8), Direction.Right)

        assertNotNull(s.deck[at], "a ghost was moved")
        assertNull(s.deck[grid.tile(10, 8)], "and it arrived somewhere")
        assertEquals(massBefore, s.massBalance, "moving a ghost minted matter")
    }

    /** A hull is one tile and made of metal — the simplest thing that can be moved at all. */
    @Test
    fun `a one-tile machine moves`() {
        val at = grid.tile(3, 3)
        val to = grid.tile(12, 9)
        var s = world { this += Hull(at) }
        s = tick(s)
        val casingBefore = casing(s)

        s = move(s, at, to, Direction.Right)

        assertNotNull(s.deck[to], "a hull would not move")
        assertNull(s.deck[at], "and it left a copy behind")
        assertEquals(casingBefore, casing(s), "a hull changed weight in transit")
    }
}
