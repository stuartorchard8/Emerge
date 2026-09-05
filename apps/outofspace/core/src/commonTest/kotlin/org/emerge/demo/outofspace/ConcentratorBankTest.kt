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
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.Concentrator
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.MACHINE_OUTPUT_CAP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The concentrate port emits nothing but whole packets of one pure species.**
 *
 * That is the invariant the rest of the game is built to lean on: `BUILD_PURITY_PERCENT` is 100, an
 * electrolyzer takes pure water and nothing else, a tank can lock onto a species, and a sell order
 * is priced per species. Before the bank existed none of them could be fed without a chain of five
 * machines and a snap-to-pure threshold — see `reference_oos_processor_purity_ladder`, and
 * [org.emerge.demo.outofspace.chem.process] for why that whole apparatus is gone.
 *
 * `ConcentratorChainTest` owns the **port contract** — which stream leaves by which face. This owns
 * what comes *out* of them.
 */
class ConcentratorBankTest {

    private val grid = Grid(12, 10)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private val mill = grid.tile(3, 3)          // covers x 2..4
    private val forward = grid.tile(7, 3)       // tank on the concentrate run
    private val below = grid.tile(3, 8)         // tank on the tailings run
    private val productRun = listOf(grid.tile(5, 3), grid.tile(6, 3))

    /** A concentrator with [feed] in its mouth, a tank ahead of it and a tank under it. */
    private fun world(feed: Mixture): VesselState {
        val deck = DeckArray(grid)
        deck += Concentrator(mill, Direction.Right)
        deck += fixtureStorage(forward, Direction.Right)
        deck += fixtureStorage(below, Direction.Down)

        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 6, 3)   // concentrate
        joinCol(grid, rails, 3, 4, 7)   // tailings

        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(mill, feed.atAmbient())
    }

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    private fun ore(packets: Int): Mixture =
        OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(packets * Capacity.PACKET_MASS)

    /**
     * ⛔ **Pure, and not merely purer.** One machine, no chain, no threshold.
     */
    @Test
    fun `what reaches the concentrate tank is one species and nothing else`() {
        val s = run(world(ore(40)), 800)
        val banked = s.inStore(forward, BufferRole.Inside)

        assertTrue((banked?.total ?: 0L) > 0L, "no concentrate ever arrived")
        assertEquals(Species.Iron, banked!!.dominant, "the concentrate keeps the ore's own metal")
        assertEquals(0L, banked.impurities, "the concentrate is not pure: $banked")
    }

    /**
     * ⛔ **And it travels in whole packets**, because the bank is what makes that possible: the
     * machine's share of one charge is 61 kg of iron, and a port that shipped *that* would put a
     * runt on the track every action — and a runt owns its tile for good, since packets are never
     * merged into.
     *
     * Sampled every tick rather than at the end, because a lump that is the wrong size is on the
     * track for only a few ticks before a tank swallows it and the evidence with it.
     */
    @Test
    fun `every lump on the concentrate run is a whole packet`() {
        var s = world(ore(40))
        var seen = 0
        repeat(800) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            for (t in productRun) {
                val mass = s.rail.massAt(t)
                if (mass == 0L) continue
                seen++
                assertEquals(Capacity.PACKET_MASS, mass, "a runt of concentrate is riding at $t")
            }
        }
        assertTrue(seen > 0, "nothing ever rode the concentrate run, so this proves nothing")
    }

    /** A charge of 200 kg dominated by quartz — nothing like what the bank below is holding. */
    private fun quartzCharge(): Mixture = Mixture.of(
        Species.Quartz to Concentrator.CHARGE_MASS * 6L / 10L,
        Species.Iron to Concentrator.CHARGE_MASS * 3L / 10L,
        Species.Chalcopyrite to Concentrator.CHARGE_MASS / 10L,
        energy = 0L,
    )

    /**
     * ⛔ **A charge of a different species does not start until the bank has gone.**
     *
     * The bank holds one species. Mixing a second one into it would end the invariant the two tests
     * above pin, and dumping it into the tailings would stall for good in a reprocessing loop, where
     * dominance changes every charge. So the machine waits, the short packet is let go — see
     * `Work.holdsBack` — and the new species starts on an empty bank.
     *
     * ⚠️ **The runt is the accepted cost**, and it is bounded by how much of the old species had
     * been banked. Small packets mean a loop with too little material in it, not a machine that is
     * misbehaving.
     */
    @Test
    fun `a charge of a new species waits for the bank to be shipped`() {
        val stale = Capacity.PACKET_MASS * 2L / 5L
        var s = world(quartzCharge()).also {
            it.buffers.put(
                org.emerge.demo.outofspace.world.bufferTile(grid, it.deck[mill]!!, mill, BufferRole.Product)!!,
                Mixture.of(Species.Iron to stale, energy = 0L).atAmbient(),
            )
        }
        val started = massIn(s)

        // Long enough to have worked several charges had it been allowed to start at all.
        s = run(s, 200)

        assertEquals(
            stale,
            s.inStore(forward, BufferRole.Inside)?.get(Species.Iron) ?: 0L,
            "the stale bank was not shipped as a short packet",
        )
        assertEquals(
            0L,
            s.inStore(forward, BufferRole.Inside)?.get(Species.Quartz) ?: 0L,
            "quartz reached the concentrate tank, so the bank blended two species",
        )
        assertTrue(
            (s.inStore(mill, BufferRole.Product)?.get(Species.Quartz) ?: 0L) > 0L,
            "the machine never got going on the new species once its bank was clear",
        )
        assertEquals(started, massIn(s), "and a species change conserves mass")
    }

    /**
     * ⛔ **A hopper's cap is a stop-threshold, not a ceiling the next batch has to fit under.**
     *
     * Stu's save, 2026-09-05: the concentrator at (9,12) wedged for good. Its tailings hopper held
     * 82.8 kg — *below a packet*, so `holdsBack` would not let it ship — and the charge in the
     * chamber assayed 12% dominant, so the next action's tailings came to 181.4 kg. 82.8 + 181.4 is
     * over the 200 kg cap, so the deposit was refused, and neither number could ever change again.
     *
     * The bug was asking whether the *deposit* would fit. [MACHINE_OUTPUT_CAP] says what a buffer
     * holds "before the machine stops **running**" — a threshold read off what is there, which
     * shipping always reduces. Asked that way a stall is always temporary; asked predictively it is
     * a deadlock whenever a residue too small to ship meets a batch too big to fit beside it.
     *
     * ⚠️ **A low-purity charge is what makes this reachable**, and it is the ordinary case: the less
     * of the dominant species there is, the *more* tailings one action makes. A rich ore never gets
     * near it. That is why this was found in a real save and not by the ore body the fixtures use.
     */
    @Test
    fun `a part-packet of tailings does not wedge the machine for good`() {
        // 12% dominant, like the charge that wedged, and the rest spread thin enough that nothing
        // else overtakes it. ⚠️ **The spread is the point, not decoration**: put the remainder in one
        // filler species and that species becomes dominant, the draw is large, and the tailings are
        // small — which is the case that does NOT wedge. A real ore body has ninety species in it.
        val share = Concentrator.CHARGE_MASS * 11L / 100L
        val poor = Mixture.of(
            Species.Forsterite to Concentrator.CHARGE_MASS * 12L / 100L,
            Species.Anorthite to share,
            Species.Quartz to share,
            Species.Fayalite to share,
            Species.Enstatite to share,
            Species.Albite to share,
            Species.Troilite to share,
            Species.Water to share,
            Species.Calcite to share,
            energy = 0L,
        )
        val residue = Capacity.PACKET_MASS * 83L / 100L
        var s = world(poor).also {
            it.buffers.put(
                bufferTile(grid, it.deck[mill]!!, mill, BufferRole.Waste)!!,
                poor.scaledTo(residue).atAmbient(),
            )
        }

        s = run(s, 400)

        assertTrue(
            (s.inStore(below, BufferRole.Inside)?.total ?: 0L) > 0L,
            "no tailings ever reached the tank: the machine is wedged, holding " +
                "${(s.inStore(mill, BufferRole.Waste)?.total ?: 0L)}g against a cap of $MACHINE_OUTPUT_CAP",
        )
        assertTrue(
            (s.inStore(mill, BufferRole.Product)?.total ?: 0L) > 0L ||
                (s.inStore(forward, BufferRole.Inside)?.total ?: 0L) > 0L,
            "the machine never banked any concentrate, so it never completed an action",
        )
    }

    /** Every gram aboard, wherever it is standing. */
    private fun massIn(s: VesselState): Long {
        var total = 0L
        for (i in 0 until grid.size) total += s.rail.massAt(TileIndex(i))
        total += s.buffers.totalMass
        return total
    }
}
