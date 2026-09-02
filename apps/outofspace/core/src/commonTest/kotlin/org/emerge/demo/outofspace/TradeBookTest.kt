package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.DockingPort
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The four controls set **one signed number**, and this is what pressing them means.
 *
 * ⛔ **One number is the design and not an optimisation.** A port that held a sell list and a buy
 * list could hold both for the same species, and then "which one paid for this delivery" is a guess.
 * Here the question cannot be asked: pressing sell against a buy permission is subtraction.
 */
class TradeBookTest {

    private val at = Grid(16, 8).tile(8, 4)
    private val packet = Capacity.PACKET_MASS

    /** Iron's figure after [presses], each applied to the result of the last. */
    private fun pressing(from: Long, vararg presses: (DockingPort) -> DockingPort): Long {
        var port = DockingPort(at, Direction.Right)
        if (from != 0L) port = port.withOrder(Species.Iron, from)
        for (press in presses) port = press(port)
        return port.permitted(Species.Iron)
    }

    private val sell: (DockingPort) -> DockingPort = { it.nudged(Species.Iron, -packet) }
    private val buy: (DockingPort) -> DockingPort = { it.nudged(Species.Iron, packet) }
    private val sellAll: (DockingPort) -> DockingPort = { it.unbounded(Species.Iron, -DockingPort.ENDLESS) }
    private val buyAll: (DockingPort) -> DockingPort = { it.unbounded(Species.Iron, DockingPort.ENDLESS) }

    @Test
    fun `the two directions are one axis`() {
        assertEquals(-packet, pressing(0L, sell), "one press of sell is a packet of selling")
        assertEquals(packet, pressing(0L, buy), "one press of buy is a packet of buying")
        assertEquals(3L * packet, pressing(0L, buy, buy, buy), "presses do not accumulate")
    }

    @Test
    fun `pressing sell against a buy permission reduces it`() {
        // ⛔ **Stu's rule, and the whole reason for one number.** Permitting five hundred kilograms
        // of iron and then pressing sell leaves four hundred permitted — it does not open a sell
        // order alongside the buy order, because there is nothing for a second order to be.
        assertEquals(4L * packet, pressing(5L * packet, sell))
        assertEquals(0L, pressing(packet, sell), "the last packet of permission did not clear")
        assertEquals(-packet, pressing(packet, sell, sell), "crossing zero did not carry on into selling")
    }

    @Test
    fun `any press stands an unbounded permission down to nothing`() {
        // ⛔ **Not to a packet the other way, and not to the other unbounded state.** Flipping from
        // "buy me all of this" to "sell me out of it" is a large thing to do by accident, and the
        // player who meant it is one press away from saying so again.
        for (press in listOf(sell, buy, sellAll, buyAll)) {
            assertEquals(0L, pressing(DockingPort.ENDLESS, press), "a press against buy-all did not stop")
            assertEquals(0L, pressing(-DockingPort.ENDLESS, press), "a press against sell-all did not stop")
        }
    }

    @Test
    fun `the unbounded states are reachable from anywhere bounded`() {
        assertEquals(-DockingPort.ENDLESS, pressing(0L, sellAll))
        assertEquals(DockingPort.ENDLESS, pressing(0L, buyAll))
        // Including from a permission pointing the other way: this is a statement, not a nudge.
        assertEquals(DockingPort.ENDLESS, pressing(-3L * packet, buyAll))
        assertEquals(-DockingPort.ENDLESS, pressing(3L * packet, sellAll))
    }

    @Test
    fun `a species at nothing is off the book entirely`() {
        // Kept out rather than kept at zero: the counter lists anything with a permission on it, so
        // a zero left behind would be a row that cannot be got rid of.
        val port = DockingPort(at, Direction.Right, orders = mapOf(Species.Iron to packet))
        assertEquals(
            emptyMap(), port.nudged(Species.Iron, -packet).orders,
            "a spent permission left a zero on the book",
        )
    }
}
