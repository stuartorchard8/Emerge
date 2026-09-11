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
     * ⛔ **A stale part-bank is tipped back into the chamber, not shipped as a runt.**
     *
     * A part packet of one species sitting in the bank is the one state in which a deposit could
     * blend, since a deposit is `banked + output`. [Work.returnBankResidue] runs at the head of
     * every action and hands anything that is not a whole packet back to the chamber, so the bank a
     * deposit lands on is always empty and the stale iron is simply re-drawn with everything else.
     *
     * ⚠️ **The iron is not lost and it is not shipped short.** It goes into the charge, so it leaves
     * later as part of a whole pure packet of iron, whenever iron is dominant again. What this pins
     * is that it does not leave *now*, as a forty-kilogram runt owning a rail tile for good.
     *
     * ⛔ **This replaced `mustClearTheBank`**, which shipped exactly that runt so the machine would
     * not stop dead in front of a bank it could not add to. Nothing has to stop any more: the bank
     * clears itself into the chamber.
     */
    @Test
    fun `a stale part-bank goes back into the chamber rather than shipping short`() {
        val stale = Capacity.PACKET_MASS * 2L / 5L
        var s = world(quartzCharge().scaledTo(Capacity.PACKET_MASS * 20L)).also {
            it.buffers.put(
                bufferTile(grid, it.deck[mill]!!, mill, BufferRole.Product)!!,
                Mixture.of(Species.Iron to stale, energy = 0L).atAmbient(),
            )
        }
        val started = massIn(s)

        // Sampled every tick: a runt is on the track for only a few ticks before a tank swallows it.
        repeat(200) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            for (t in productRun) {
                val mass = s.rail.massAt(t)
                if (mass == 0L) continue
                assertEquals(Capacity.PACKET_MASS, mass, "the stale bank was shipped as a runt at $t")
            }
            val bank = s.inStore(mill, BufferRole.Product)
            if (bank != null) assertEquals(0L, bank.impurities, "the bank blended two species: $bank")
        }

        assertTrue(
            (s.inStore(forward, BufferRole.Inside)?.get(Species.Quartz) ?: 0L) > 0L,
            "the machine never got going on the new species",
        )
        assertEquals(started, massIn(s), "and tipping the bank back conserves mass")
    }

    /**
     * ⛔ **The case that was measured, red, and ignored**, and the reason [Work.returnBankResidue]
     * exists at all.
     *
     * A bank is left part-shipped whenever a sink's appetite is shorter than a packet: `holdsBack`
     * exempts a finite short appetite on purpose — a construction site owed thirty kilograms — and
     * `takePacket(buffer, room)` takes the thirty and leaves seventy behind. The gate that was meant
     * to stop the next deposit blending into that seventy lived at the **lift**, and the lift only
     * happens when the chamber is empty, which since `811be00f` is only ever true of a machine that
     * has never run. Measured on this fixture before the fix: the bank went to
     * `Quartz=100kg, Iron=40kg` and 208 of 400 ticks had a blended lump standing on the run.
     *
     * ⚠️ **The short appetite is still served.** Normalising at the head of the action rather than
     * vetoing at the port is what buys both: the thirty kilograms goes, and the seventy rejoins the
     * charge instead of waiting to be mixed into.
     */
    @Test
    fun `a part-shipped bank is not blended into`() {
        var s = world(quartzCharge().scaledTo(Capacity.PACKET_MASS * 20L)).also {
            it.buffers.put(
                bufferTile(grid, it.deck[mill]!!, mill, BufferRole.Product)!!,
                Mixture.of(Species.Iron to Capacity.PACKET_MASS * 2L / 5L, energy = 0L).atAmbient(),
            )
            // What every machine that has ever worked looks like, and what the lift gate could not
            // see past: rock already standing in the chamber.
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
     * ⛔ **A bank holding a blended packet is handed back whole, and the machine starts again.**
     *
     * Found in `mixed_concentrate.txt` at tick 30753966, not reasoned about: the bank held a full
     * 100 kg packet assaying 62% water and 32% enstatite, and 2000 ticks left every store in the
     * machine byte-identical. A blend is a packet nothing will have — a tank locks onto a species,
     * a site wants `BUILD_PURITY_PERCENT` — so it can never be delivered, and a full bank stops the
     * machine dead at the [MACHINE_OUTPUT_CAP] check.
     *
     * ⚠️ **It is a full packet by mass, which is why the mass rule alone does not cure it.** Keeping
     * a proportional packet's worth would ship one mixed lump and wedge again on the next; the bank
     * holds one packet **of one species** or nothing, and a blend fails the second half.
     */
    @Test
    fun `a blended bank is handed back whole and the machine starts again`() {
        var s = world(quartzCharge().scaledTo(Capacity.PACKET_MASS * 20L)).also {
            it.buffers.put(
                bufferTile(grid, it.deck[mill]!!, mill, BufferRole.Product)!!,
                Mixture.of(
                    Species.Water to Capacity.PACKET_MASS * 62L / 100L,
                    Species.Enstatite to Capacity.PACKET_MASS * 38L / 100L,
                    energy = 0L,
                ).atAmbient(),
            )
            it.buffers.put(
                bufferTile(grid, it.deck[mill]!!, mill, BufferRole.Inside)!!,
                quartzCharge().atAmbient(),
            )
        }
        val started = massIn(s)

        repeat(400) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            for (t in productRun) {
                val lump = s.rail.resourceAt(t) ?: continue
                assertEquals(0L, lump.impurities, "the blended bank was shipped as a lump at $t: $lump")
            }
        }

        assertTrue(
            (s.inStore(forward, BufferRole.Inside)?.total ?: 0L) > 0L,
            "the machine never shipped anything: it is still wedged on the blended bank",
        )
        assertEquals(started, massIn(s), "and handing a blend back conserves mass")
    }

    /**
     * ⛔ **And the bank is never more than one packet**, which is the other half of the same rule:
     * a store that can hold two packets' worth can hold two *species*' worth.
     *
     * A bank loaded over the cap — from a save written before [Work.returnBankResidue] existed —
     * keeps a packet and hands the excess back to the chamber. The sim can no longer reach that
     * state on its own, since a deposit only ever lands on a bank that has just been normalised to
     * empty; this pins that an old save converges rather than staying over for ever.
     */
    @Test
    fun `a bank loaded over a packet hands the excess back`() {
        var s = world(quartzCharge().scaledTo(Capacity.PACKET_MASS * 20L)).also {
            it.buffers.put(
                bufferTile(grid, it.deck[mill]!!, mill, BufferRole.Product)!!,
                Mixture.of(Species.Iron to Capacity.PACKET_MASS * 3L / 2L, energy = 0L).atAmbient(),
            )
        }
        val started = massIn(s)

        repeat(200) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            val bank = s.inStore(mill, BufferRole.Product)?.total ?: 0L
            assertTrue(bank <= Capacity.PACKET_MASS, "the bank is holding $bank, over a packet")
        }
        assertEquals(started, massIn(s), "and handing the excess back conserves mass")
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
