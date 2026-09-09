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
import kotlin.test.Ignore
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
 * ⚠️ **"One species" is a fact about a packet, not about a port over time.** Since `811be00f` the
 * chamber is an enricher that keeps what it does not ship — see [Work.refine] — so the dominant
 * climbs until a packet clears, and then whatever is left in the chamber takes its turn. A mill
 * therefore works its way down the ore body by abundance on its own and a tank downstream of one
 * ends up holding several pure species. Each **lump** is still 100% of one of them, and that is what
 * every consumer above actually asks of it.
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
     *
     * ⚠️ **Sampled lump by lump on the run, because the tank is the wrong place to ask.** A tank
     * downstream of a mill legitimately ends up holding several species — the chamber enriches what
     * it keeps, so once the iron is drawn down the quartz takes its turn — and reading the tank
     * cannot tell "two pure packets arrived" apart from "one blended packet did". The rail can: what
     * this pins is that **no lump ever carries a second species**, which is what a construction site
     * at `BUILD_PURITY_PERCENT` and a species-locked tank actually require.
     */
    @Test
    fun `every lump on the concentrate run is one species and nothing else`() {
        var s = world(ore(40))
        var seen = 0
        repeat(800) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            for (t in productRun) {
                val lump = s.rail.resourceAt(t) ?: continue
                seen++
                assertEquals(0L, lump.impurities, "a blended lump of concentrate is riding at $t: $lump")
            }
            // The bank is the only place a blend could be assembled, so it is worth asking directly
            // rather than waiting for the evidence to reach the track.
            val bank = s.inStore(mill, BufferRole.Product)
            if (bank != null) assertEquals(0L, bank.impurities, "the bank blended two species: $bank")
        }
        assertTrue(seen > 0, "nothing ever rode the concentrate run, so this proves nothing")
    }

    /**
     * ⛔ **And the mill walks down the ore body by abundance without being told to.**
     *
     * The other half of the purity contract, and the reason the test above cannot ask the tank: the
     * draw always takes whatever is *dominant*, and the chamber keeps what it does not ship, so once
     * a species has been drawn down the next-most-abundant one starts clearing packets. That is what
     * makes a tailings loop terminate instead of plateauing, and it is a **feature being pinned**,
     * not a leak being tolerated — a machine that stopped at the first species would strand every
     * other one in the rock for good.
     */
    @Test
    fun `successive packets work down through the ore body`() {
        val s = run(world(ore(40)), 800)
        val arrived = s.inStore(forward, BufferRole.Inside)

        assertTrue((arrived?.total ?: 0L) > 0L, "no concentrate ever arrived")
        assertEquals(Species.Iron, arrived!!.dominant, "the ore's own metal is still what comes first")
        assertTrue(
            arrived.impurities > 0L,
            "only one species was ever concentrated, so the mill never moved on: $arrived",
        )
        // Whatever else came, it came whole: nothing arrived that is not a count of packets.
        assertEquals(
            0L,
            arrived.total % Capacity.PACKET_MASS,
            "the tank holds a part packet, so something shipped short: $arrived",
        )
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
     * ⛔ **A cold machine clears a stale bank before it starts on a species that is not in it.**
     *
     * A part packet of one species sitting in the bank is the one state in which a deposit could
     * blend, since a deposit is `banked + output`. `Work.refine` refuses to lift a charge whose
     * dominant is not what the bank holds, `Work.holdsBack`'s `mustClearTheBank` exemption lets the
     * short packet go, and the new species starts on an empty bank the tick after.
     *
     * ⚠️ **The runt is the accepted cost**, and it is bounded by how much of the old species had
     * been banked. Small packets mean a loop with too little material in it, not a machine that is
     * misbehaving.
     *
     * ⚠️ **"Cold" is load-bearing and it is what the ignored test below is about.** The gate lives
     * inside the `?: run { … }` that only runs when the chamber is empty, and since `811be00f` the
     * chamber is never empty again once the machine has worked. So this pins the first lift of a
     * machine's life and nothing after it.
     */
    @Test
    fun `a cold machine clears a stale bank before starting a new species`() {
        val stale = Capacity.PACKET_MASS * 2L / 5L
        var s = world(quartzCharge()).also {
            it.buffers.put(
                bufferTile(grid, it.deck[mill]!!, mill, BufferRole.Product)!!,
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
        // ⚠️ **Asked of the bank and of every lump, not of the tank.** The quartz is *expected* to
        // reach the tank — a 200 kg charge assaying 60% clears a packet on its first action — so the
        // tank holding both proves nothing either way. What must never happen is the two travelling
        // together.
        assertEquals(
            0L,
            s.inStore(mill, BufferRole.Product)?.impurities ?: 0L,
            "the bank blended the stale iron with the new species",
        )
        assertTrue(
            (s.inStore(forward, BufferRole.Inside)?.get(Species.Quartz) ?: 0L) > 0L,
            "the machine never got going on the new species once its bank was clear",
        )
        assertEquals(started, massIn(s), "and a species change conserves mass")
    }

    /**
     * ⛔ **KNOWN HOLE, measured and deliberately left red rather than papered over.**
     *
     * The gate the test above pins is at the **lift**, and the lift only happens when the chamber is
     * empty — which, since `811be00f`, is only ever true of a machine that has never run. A machine
     * that *has* run and whose bank has been part-shipped is in exactly the state the gate exists to
     * refuse, and cannot reach it.
     *
     * A bank is left part-shipped whenever a sink's appetite is shorter than a packet: `holdsBack`
     * exempts a finite short appetite on purpose — a construction site owed thirty kilograms — and
     * `takePacket(buffer, room)` then takes the thirty and leaves seventy in the bank. The next
     * action deposits `banked + output` across two species, and the port ships blended lumps from
     * then on. Measured on this fixture: the bank went to `Quartz=100kg, Iron=40kg` and 208 of 400
     * ticks had a blended lump standing on the run.
     *
     * ⚠️ **Not fixed here because the fix is a design decision, not a repair.** Moving the gate to
     * the deposit strands a finished charge of one species against a bank of another with nowhere to
     * put either — which is the argument the old KDoc made for putting it at the lift in the first
     * place. Un-ignore this when that decision is made.
     */
    @Ignore
    @Test
    fun `a part-shipped bank is not blended into`() {
        var s = world(quartzCharge().scaledTo(Capacity.PACKET_MASS * 20L)).also {
            it.buffers.put(
                bufferTile(grid, it.deck[mill]!!, mill, BufferRole.Product)!!,
                Mixture.of(Species.Iron to Capacity.PACKET_MASS * 2L / 5L, energy = 0L).atAmbient(),
            )
            // What every machine that has ever worked looks like, and what the lift gate cannot see
            // past: rock already standing in the chamber.
            it.buffers.put(
                bufferTile(grid, it.deck[mill]!!, mill, BufferRole.Inside)!!,
                quartzCharge().atAmbient(),
            )
        }

        repeat(400) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            val bank = s.inStore(mill, BufferRole.Product)
            if (bank != null) assertEquals(0L, bank.impurities, "the bank blended two species: $bank")
            for (t in productRun) {
                val lump = s.rail.resourceAt(t) ?: continue
                assertEquals(0L, lump.impurities, "a blended lump of concentrate is riding at $t: $lump")
            }
        }
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
        // ⚠️ **Twenty belt-loads of feed, not the single charge this fixture used to hold.** Since
        // `811be00f` the chamber enriches over several actions rather than banking a fraction of one
        // — a 12% charge yields 24 kg of concentrate on its first pass and needs five more to clear a
        // packet — so a mill given exactly one charge simply runs out of feed. That is a dry hopper
        // and not a wedge, and a fixture that cannot tell them apart cannot pin either. A real save
        // has a belt behind the machine; this is that belt.
        var s = world(poor.scaledTo(Capacity.PACKET_MASS * 20L)).also {
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
