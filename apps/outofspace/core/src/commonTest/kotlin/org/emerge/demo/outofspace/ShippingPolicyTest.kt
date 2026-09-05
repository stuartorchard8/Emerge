package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machineBillOfMaterials
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.materialBefore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **When a machine that ships whole packets is allowed to ship a runt.**
 *
 * [DeckMachineKind.shipsWholePackets] exists to stop a hopper filling at a rate from dribbling its
 * remainder onto the track, where a runt lump owns a tile for good — packets are never merged into.
 * That rule is right, and it is a *veto*: it is asked after the demand work has already decided how
 * much is worth letting go of at all.
 *
 * ⛔ **A veto with no exemptions is a deadlock**, and the exemptions are not a softening of the rule
 * — each one names a case where holding back cannot achieve what the rule is for:
 *
 *  - **Being taken apart.** A machine that will not let go of its last eighty grams never comes
 *    apart, because deconstruction waits on the stores an output port drains. This one already
 *    existed; it is pinned here because the condition around it is being restructured.
 *  - **The demand itself is short.** A construction site owed thirty kilograms creates a thirty
 *    kilogram appetite, and a runt sized to a real appetite is *consumed* — it cannot come to rest
 *    on a tile and it cannot clog anything. Holding it back strands the site for ever, in front of
 *    material that would have finished it.
 *
 * ⚠️ **The second is not the fork-splitting rule.** `Whitelist.room` already sizes what leaves to
 * what is wanted (see `Demand.room`), and a whole-packet machine then refused to send it at all.
 * The two rules disagreed and the veto won.
 */
class ShippingPolicyTest {

    private val grid = Grid(14, 5)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    /** Centre of the extractor; five tiles across, so it covers x 1..5 and its port is (5,2). */
    private val extractor = grid.tile(3, 2)
    private val port = grid.tile(5, 2)
    private val far = grid.tile(10, 2)

    /**
     * Less than a packet in the hopper, which is the whole shape: a *whole* packet would be shipped
     * whatever the rule said, and every exemption here would be invisible.
     */
    private val held = Capacity.PACKET_MASS * 2L / 5L

    /**
     * An extractor holding [held] of [stock], with a run from its port out to [far].
     *
     * **No rock anywhere**, deliberately: a bodiless extractor takes no bite, so what is in the
     * hopper at the start is exactly what is in it at the end unless something shipped it.
     */
    private fun world(stock: Mixture, scrapped: Boolean = false): VesselState {
        val deck = DeckArray(grid)
        deck += Extractor(extractor, Direction.Right)

        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, grid.xOf(port), grid.xOf(far), 2)

        return VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false, scrapping = if (scrapped) setOf(extractor) else emptySet())
            .stocked(extractor, stock.atAmbient(), BufferRole.Product)
    }

    /** A part-built sensor at [far], owed [shortBy] and nothing more. */
    private fun VesselState.owing(shortBy: Long): VesselState = also {
        val bill = sensorBill()
        it.deck.standGhost(fixtureSensor(far, Direction.Right))
        val standing = bill.scaledTo(bill.total - shortBy)
        for (sp in Species.ALL) it.deck.stuff[far, sp] = standing[sp]
    }

    private fun sensorBill(): Mixture =
        machineBillOfMaterials(DeckMachineKind.Sensor, 1, materialBefore(DeckMachineKind.Sensor))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    private fun inHopper(s: VesselState): Long = s.inStore(extractor, BufferRole.Product)?.total ?: 0L

    private fun onTrack(s: VesselState): Long {
        var total = 0L
        for (t in grid.tiles) total += s.rail.massAt(t)
        return total
    }

    /**
     * ⛔ **The rule itself, which the exemptions must not have removed.**
     *
     * A tank takes anything for ever, so nothing downstream is *short* of anything — and a hopper
     * with less than a packet in it stays shut. Without this every test below passes on a machine
     * that simply dribbles.
     */
    @Test
    fun `a part-full hopper ships nothing to a sink that is not short`() {
        val s = run(world(sensorBill().scaledTo(held)).also { st ->
            st.deck += fixtureStorage(far, Direction.Right)
        }, RAIL_PERIOD * 40)

        assertEquals(held, inHopper(s), "the hopper dribbled its remainder onto the track")
        assertEquals(0L, onTrack(s), "a runt lump went out onto the track")
    }

    /**
     * ⛔ **A runt sized to a real appetite is consumed, so it cannot clog anything.**
     *
     * The site is owed a quarter of a packet. Today the extractor holds all forty kilograms rather
     * than send twenty-five, and the sensor is never finished.
     */
    @Test
    fun `a part-full hopper ships a runt when that is exactly what is wanted`() {
        val shortBy = Capacity.PACKET_MASS / 4L
        val s = run(world(sensorBill().scaledTo(held)).owing(shortBy), RAIL_PERIOD * 40)

        assertTrue(
            s.deck[far] != null && !s.deck.isGhost(far),
            "the sensor never finished: it is still owed material the hopper was holding",
        )
        assertEquals(held - shortBy, inHopper(s), "the hopper let go of the wrong amount")
        assertEquals(0L, onTrack(s), "and sent no more than was asked for")
    }

    /**
     * ⛔ **Told to go, a machine hands over whatever it has**, at any size — otherwise a hopper with
     * eighty grams in it is a machine that never comes apart. This exemption already existed; it is
     * pinned because the condition it lives in is being restructured around it.
     */
    @Test
    fun `a machine being taken apart lets go of a part packet`() {
        val iron = Mixture.of(Species.Iron to held, energy = 0L)
        val s = run(world(iron, scrapped = true).also { st ->
            st.deck += fixtureStorage(far, Direction.Right)
        }, RAIL_PERIOD * 40)

        assertEquals(
            held,
            s.inStore(far, BufferRole.Inside)?.get(Species.Iron) ?: 0L,
            "the hopper held on to its last part-packet while being taken apart",
        )
    }
}
