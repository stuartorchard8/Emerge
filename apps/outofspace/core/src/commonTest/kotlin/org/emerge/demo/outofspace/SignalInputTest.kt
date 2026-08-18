package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.machine.Airlock
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.InputKey
import org.emerge.demo.outofspace.world.machine.WireButton
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.SignalSource
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Increment E of `PLAN_signal_network.md`: a person's finger on the wire.
 *
 * Every other transmitter reports on the vessel's own state. This one reports on the pilot, which is
 * what turns a vessel that runs into a vessel you fly — and the last test here is that whole claim
 * in one assertion: hold a key, and the ship moves.
 */
class SignalInputTest {

    private val grid = Grid(12, 10)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private fun run(state: VesselState, ticks: Int, held: Int = 0): VesselState {
        var s = state
        val input = mapOf(PlayerId(0) to OutofspaceInput(heldKeys = held))
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, input) }
        return s
    }

    private fun signalRow(wires: Array<Segment?>, fromX: Int, toX: Int, y: Int) {
        for (x in fromX..toX) if (wires[grid.tile(x, y).index] == null) wires[grid.tile(x, y).index] = Segment(Conduit.Signal)
        for (x in fromX until toX) {
            val a = grid.tile(x, y)
            val b = grid.tile(x + 1, y)
            wires[a.index] = wires[a.index]!!.joinedTo(Direction.Right)
            wires[b.index] = wires[b.index]!!.joinedTo(Direction.Left)
        }
    }

    private val buttonAt = 2 to 4
    private val farEnd = 8 to 4

    /** One button on one run of wire, going nowhere in particular. */
    private fun rig(key: InputKey = InputKey.Up): VesselState {
        val deck = DeckArray(grid)
        deck += WireButton(grid.tile(buttonAt.first, buttonAt.second), key)
        val wires = arrayOfNulls<Segment>(grid.size)
        signalRow(wires, buttonAt.first, farEnd.first, buttonAt.second)
        return VesselState(
            grid,
            deck,
            conduits = Conduits.of(grid.size, Conduit.Signal to wires.toList()),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )
    }

    private fun atFarEnd(s: VesselState) = s.signals.at(grid.tile(farEnd.first, farEnd.second))

    // ── Pressed and released ──────────────────────────────────────────────────

    @Test
    fun `a held key puts a full signal on its network`() {
        assertEquals(SignalField.FULL, atFarEnd(run(rig(), 1, held = InputKey.Up.bit)))
    }

    @Test
    fun `a released key puts nothing on it`() {
        assertEquals(0, atFarEnd(run(rig(), 1, held = 0)))
    }

    /**
     * No latch and no delay: the value is there in the tick the key goes down and gone in the tick it
     * comes up. Anything else would mean a thruster that outlives the finger on it.
     */
    @Test
    fun `the value arrives and leaves in the tick the key does`() {
        val held = run(rig(), 1, held = InputKey.Up.bit)
        assertEquals(SignalField.FULL, atFarEnd(held))
        assertEquals(0, atFarEnd(run(held, 1, held = 0)), "letting go should take effect at once")
    }

    @Test
    fun `a key bound to one button does not drive another`() {
        assertEquals(0, atFarEnd(run(rig(InputKey.Left), 1, held = InputKey.Right.bit)))
        assertEquals(SignalField.FULL, atFarEnd(run(rig(InputKey.Left), 1, held = InputKey.Left.bit)))
    }

    @Test
    fun `holding two keys drives both their buttons`() {
        val deck = DeckArray(grid)
        deck += WireButton(grid.tile(2, 2), InputKey.Left)
        deck += WireButton(grid.tile(2, 6), InputKey.Right)
        val wires = arrayOfNulls<Segment>(grid.size)
        signalRow(wires, 2, 8, 2)
        signalRow(wires, 2, 8, 6)
        val s = run(
            VesselState(grid, deck, conduits = Conduits.of(grid.size, Conduit.Signal to wires.toList()), buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size)),
            1,
            held = InputKey.Left.bit or InputKey.Right.bit,
        )

        assertEquals(SignalField.FULL, s.signals.at(grid.tile(8, 2)))
        assertEquals(SignalField.FULL, s.signals.at(grid.tile(8, 6)))
    }

    @Test
    fun `a button with no wire under it is harmless`() {
        val deck = DeckArray(grid)
        deck += WireButton(grid.tile(2, 4), InputKey.Up)
        val s = run(VesselState(grid, deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size)), 1, held = InputKey.Up.bit)
        assertEquals(0, s.signals.networkCount)
    }

    @Test
    fun `a button keeps its key across a save`() {
        val s = rig(InputKey.B)
        val back = Save.read(Save.write(s))
        assertEquals(InputKey.B, (back.deck[grid.tile(buttonAt.first, buttonAt.second)] as? WireButton)?.key)
    }

    // ── The whole point ───────────────────────────────────────────────────────

    /**
     * A pressurised room, a door in its right-hand wall wired to the wire, and a button on that wire.
     *
     * Hold the key: the door opens, the air leaves through the starboard wall, and the ship goes to
     * port. Nothing here is new machinery — the airlock predates the wire layer and cannot tell what
     * put the value on its network — which is exactly the property worth asserting. **The flight loop
     * closes at this test**: a person presses a key and the vessel moves, with a visible wire in
     * between.
     */
    @Test
    fun `holding a key vents the ship and drives it the other way`() {
        val w = 8
        val h = 8
        val deck = DeckArray(grid)
        for (x in 1..w) {
            deck += Hull(grid.tile(x, 1))
            deck += Hull(grid.tile(x, h))
        }
        for (y in 2 until h) {
            deck += Hull(grid.tile(1, y))
            deck += Hull(grid.tile(w, y))
        }
        // The door in the starboard wall, wired to whatever is on the run beneath it.
        val airlockTile = grid.tile(w, h / 2)
        deck -= airlockTile
        deck += Airlock(
            airlockTile,
            wiring = Wiring(mapOf(Action.Run to listOf(Trigger(SignalSource.Wire, SignalField.FULL)))),
        )
        deck += WireButton(grid.tile(3, h / 2), InputKey.Right)

        val wires = arrayOfNulls<Segment>(grid.size)
        signalRow(wires, 3, w, h / 2)

        val start = VesselState(
            grid,
            deck,
            conduits = Conduits.of(grid.size, Conduit.Signal to wires.toList()),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )

        val idle = run(start, 60, held = 0)
        val flying = run(start, 60, held = InputKey.Right.bit)

        assertEquals(0L, idle.airVentedMass, "with nobody holding the key the door stays shut")
        assertTrue(flying.airVentedMass > 0L, "held, it should be venting: ${flying.airVentedMass}g")
        assertTrue(
            flying.vesselImpulseX < 0L,
            "exhaust went +x, so the ship should go -x: ${flying.vesselImpulseX}",
        )
        assertEquals(0L, flying.airBalance, "and no gas went missing doing it")
    }
}
