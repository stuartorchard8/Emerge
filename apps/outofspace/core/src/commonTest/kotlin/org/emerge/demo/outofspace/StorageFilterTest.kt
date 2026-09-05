package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.tileBillOfMaterials
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A locked warehouse: the first sink whose appetite is **endless and picky at once**.
 *
 * Every appetite before this one answered both questions the same way — a machine took anything for
 * ever, a construction site took one recipe until it was finished — so the network could read one
 * flag for both and did. A lock separates them, and these tests are what say so: the first two are
 * the feature, the third is the flag that quietly meant the wrong thing until it was split (see
 * [org.emerge.demo.outofspace.world.Acceptance.takesAnything]), and it is the one that would go
 * green again on its own if the two were ever conflated back together.
 */
class StorageFilterTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(16, 8))

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private val load = 6L * Capacity.PACKET_MASS

    /**
     * 98% titanium: ore, not a bar.
     *
     * Chosen to sit **between** two of the panel's steps, so the threshold tests have something to
     * bite on — it clears 90 comfortably and misses 99, and a filter that ignored its own number
     * could not tell those two apart.
     */
    private fun titanium(mass: Long) = Mixture.of(
        Species.Titanium to mass - mass / 50L,
        Species.Iron to mass / 50L,
        energy = 0,
    )

    /**
     * A full tank at (2,3) pouring right into a second tank at (10,3), which may be locked.
     *
     * Two warehouses and not a warehouse and a vent, because the question is what a *sink* refuses:
     * a vent takes anything and would answer nothing.
     */
    private fun tankToTank(
        filter: SpeciesFilter?,
        stock: Mixture = titanium(load),
        receiverIsGhost: Boolean = false,
        /**
         * How far the track runs. Nine tiles stops at the warehouse's door; ten puts track under
         * the warehouse itself, which is what a site needs — a ghost machine is fed at the tile it
         * stands on, so with no rail there it is not on the network at all and the whole question
         * goes away untested.
         */
        railTo: Int = 9,
    ): VesselState {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += fixtureStorage(grid.tile(2, 3), Direction.Right)
        val receiver = fixtureStorage(grid.tile(10, 3), Direction.Right, filter = filter)
        if (receiverIsGhost) deck.standGhost(receiver) else deck += receiver
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, railTo, 3)
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(grid.tile(2, 3), stock)
    }

    /**
     * Pure iron: 100% of one species, and not the [Species.Titanium] a fixture storage is built out
     * of. So a purity-only lock admits it and a storage's own bill of materials does not — which is
     * the pair the ghost tests below turn on.
     */
    private fun pureIron(mass: Long) = Mixture.of(Species.Iron to mass, energy = 0)

    private fun VesselState.received(): Long = inStore(grid.tile(10, 3), BufferRole.Inside)?.total ?: 0L

    private fun VesselState.onTrack(): Long {
        var total = 0L
        for (i in 0 until grid.size) total += rail.massAt(TileIndex(i))
        return total
    }

    @Test
    fun `a warehouse locked to something else is never sent anything`() {
        val s = run(tankToTank(SpeciesFilter(Species.Iron, pure = null)), 40 * RAIL_PERIOD)

        assertEquals(0L, s.received(), "titanium got into a warehouse locked to iron")
        // ⛔ **And the run stays clear.** Refusing at the door alone would let the source pour
        // anyway and leave the line packed solid against a tank that will never take it — the dead
        // end failure, with the lock as the dead end. The whitelist is what makes the tank's answer
        // visible eight tiles upstream.
        assertEquals(0L, s.onTrack(), "the run filled up against a lock that will never open")
        assertEquals(load, s.inStore(s.grid.tile(2, 3), BufferRole.Inside)?.total, "and the source kept it all")
    }

    @Test
    fun `a warehouse locked to what is coming fills up as it always did`() {
        val s = run(tankToTank(SpeciesFilter(Species.Titanium, pure = null)), 40 * RAIL_PERIOD)
        assertTrue(s.received() > 0L, "a warehouse locked to titanium refused titanium")
    }

    /**
     * ⛔ **The dial says what it means, and it means one of three things.**
     *
     * It used to be a percentage the player typed and this test pinned that 98% ore cleared 90 and
     * missed 99. There is no middle any more — see [SpeciesFilter.pure] — so what it pins now is
     * that 98% ore is *mixed*, flatly, and that a species lock with no opinion about purity still
     * takes it. A near miss and a wide miss are the same answer, which is the point.
     */
    @Test
    fun `a purity state is not a threshold`() {
        val ore = titanium(load)
        assertTrue(SpeciesFilter(Species.Titanium, pure = null).admits(ore), "a species lock takes its own ore")
        assertTrue(!SpeciesFilter(Species.Titanium, pure = true).admits(ore), "98% titanium is not pure")
        assertTrue(SpeciesFilter(Species.Titanium, pure = false).admits(ore), "and it is certainly mixed")
        assertTrue(
            !SpeciesFilter(Species.Titanium, pure = null).admits(Mixture.EMPTY),
            "nothing is not a delivery",
        )
    }

    /**
     * ⛔ **A construction site is not a warehouse, whatever its dials say.**
     *
     * Stu's save `source_27_12`, 2026-09-01. A storage 29% built at the far end of a corridor was
     * locked at 100% purity — the dials are set when the site is placed, so a ghost carries them
     * from the first tick. That lock was stated to the network as an ordinary warehouse appetite:
     * endless, and satisfied by anything pure. Eleven tiles upstream a tank of pure Enstatite read
     * it, found somewhere its cargo could go for ever, and poured. The site then refused every
     * packet at its own door — it is being built out of Ferrosilite — and seven of them filled the
     * corridor solid.
     *
     * Every rule was doing its job. The appetite was simply not the site's to state: a shell that
     * cannot hold a gram until it is paid for has exactly one appetite, its bill, and that is
     * already stated for it as a construction site.
     */
    @Test
    fun `a warehouse still being built states its bill and not its lock`() {
        val s = run(
            tankToTank(SpeciesFilter(null, pure = true), pureIron(load), receiverIsGhost = true, railTo = 10),
            40 * RAIL_PERIOD,
        )

        assertEquals(0L, s.onTrack(), "a site's lock filled the run with material only the finished tank could take")
        assertEquals(load, s.inStore(s.grid.tile(2, 3), BufferRole.Inside)?.total, "and the source kept it all")
    }

    /**
     * The casing arrives from somewhere this fixture does not model — exactly what
     * `DeckArray.stand(withCasing = true)` does, on a machine that is already standing.
     */
    private fun VesselState.finishCasing(centre: TileIndex) {
        val m = deck[centre]!!
        val bill = tileBillOfMaterials(m.kind, deck.materialOf(m))
        for (tile in m.tiles(grid)) {
            for (sp in Species.ALL) deck.stuff[tile, sp] = bill[sp]
            deck.stuff.setEnergy(tile, deck.stuff.heatCapacityAt(tile) * Temperature.AMBIENT_KELVIN)
        }
    }

    /**
     * ⛔ **The two appetites are separate because they are SEQUENTIAL**, and this is what says so.
     *
     * A ghost's only port is its construction port, at its centre; a finished storage's intake is at
     * `centre - reach` and the construction port is gone (see `standingPortsOf`). So the site's
     * demand and the warehouse's demand are never live at the same time, and suppressing one while
     * the other stands is the whole of the separation — a half-built tank has no store to put
     * anything in, so there is nothing to be simultaneous with.
     *
     * ⚠️ What that costs: the lock is not cancelled, only deferred. The tick the casing is complete
     * the tank is a warehouse and draws exactly what it was always locked to draw. **Stu's save will
     * do this**: once (26,18) finishes, the Enstatite it refused for hours is admitted, because
     * `>=100% pure` is what it says.
     */
    @Test
    fun `a lock is deferred by construction, not cancelled by it`() {
        val start = tankToTank(SpeciesFilter(null, pure = true), pureIron(load), receiverIsGhost = true, railTo = 10)

        val asSite = run(start, 40 * RAIL_PERIOD)
        assertEquals(0L, asSite.received(), "a site took delivery into a store it does not have yet")
        assertEquals(0L, asSite.onTrack(), "and drew nothing onto the run on the strength of its dials")

        asSite.finishCasing(asSite.grid.tile(10, 3))

        val asWarehouse = run(asSite, 40 * RAIL_PERIOD)
        assertTrue(asWarehouse.received() > 0L, "a finished warehouse never took up the lock it was placed with")
    }

    /**
     * ⚠️ **The other half, or the fix is just a way of turning locks off.** The same tank, finished,
     * is a real warehouse with a real endless appetite, and pure iron is exactly what it asked for.
     */
    @Test
    fun `a finished warehouse locked on purity alone draws what is pure`() {
        val s = run(tankToTank(SpeciesFilter(null, pure = true), pureIron(load), railTo = 10), 40 * RAIL_PERIOD)
        assertTrue(s.received() > 0L, "a warehouse locked at 100% refused a lump of one species")
    }

    /**
     * A lock is the player's decision, so it survives the file — **in each of the three states**,
     * because the one that is omitted from the line when unset is the one a round trip can quietly
     * lose.
     */
    @Test
    fun `a lock survives a save and a load`() {
        val filters = listOf(
            SpeciesFilter(Species.Titanium, pure = null),
            SpeciesFilter(Species.Titanium, pure = true),
            SpeciesFilter(null, pure = false),
        )
        for (filter in filters) {
            val grid = cfg.initialGrid
            val deck = DeckArray(grid)
            deck += fixtureStorage(grid.tile(2, 3), Direction.Right, filter = filter)
            val state = VesselState(
                grid, deck,
                conduits = Conduits.ofRails(arrayOfNulls<Segment>(grid.size).toList()),
                buffers = BufferLayer.forDeck(grid, deck),
                rail = RailLayer.empty(grid.size),
            )
            val back = Save.read(Save.write(state))
            assertEquals(filter, (back.deck[grid.tile(2, 3)] as Storage).filter, "$filter did not come back")
        }
    }

    // ── The ceiling ──────────────────────────────────────────────────────────

    /**
     * ⛔ **An old file's percentage folds onto a state, and the middle WIDENS.**
     *
     * Files below [Save.PURITY_STATE_VERSION] carry an inclusive floor and an exclusive ceiling —
     * 25/50/75/90/95/100 and `< 100`. A floor of 100 is *pure* and a ceiling of 100 is *mixed*;
     * everything in between has no state to land on, and the direction it goes is a real decision.
     *
     * ⚠️ **Widening is the only safe direction.** A tank locked at "at least 75% iron" is holding
     * material a stricter filter would refuse, so rounding it up to *pure* would shut a door on a
     * tankful that was behind an open one when the player last saved. Widening can only admit more.
     *
     * Written by rewriting a current file's key rather than by hand, so the record's own format
     * cannot drift away from what this claims to be testing.
     */
    @Test
    fun `an old percentage filter loads as the state that cannot strand anything`() {
        fun reloadedWith(legacy: String): SpeciesFilter? {
            val grid = cfg.initialGrid
            val deck = DeckArray(grid)
            deck += fixtureStorage(grid.tile(2, 3), Direction.Right, filter = SpeciesFilter(Species.Titanium, true))
            val state = VesselState(
                grid, deck,
                conduits = Conduits.ofRails(arrayOfNulls<Segment>(grid.size).toList()),
                buffers = BufferLayer.forDeck(grid, deck),
                rail = RailLayer.empty(grid.size),
            )
            val text = Save.write(state)
            check("filterpure=true" in text) { "the current format changed; this test is rewriting nothing" }
            return (Save.read(text.replace("filterpure=true", legacy)).deck[grid.tile(2, 3)] as Storage).filter
        }

        assertEquals(
            SpeciesFilter(Species.Titanium, pure = true), reloadedWith("filterpct=100"),
            "a floor of 100 per cent is exactly what pure means",
        )
        assertEquals(
            SpeciesFilter(Species.Titanium, pure = false), reloadedWith("filterunder=100"),
            "an exclusive ceiling of 100 per cent is exactly what mixed means",
        )
        for (pct in listOf(25, 50, 75, 90, 95)) {
            assertEquals(
                SpeciesFilter(Species.Titanium, pure = null), reloadedWith("filterpct=$pct"),
                "a floor of $pct per cent must widen, not tighten — see the note above",
            )
        }
    }

    @Test
    fun `mixed means exactly one thing - not pure`() {
        // ⛔ **The reason the axis is a STATE and not a percentage.** No integer percentage can say
        // "more than one species is present": a lump 99.5% iron measures as 99, so an inclusive
        // ceiling of 99 refuses it and one of 100 admits pure metal. Asking `Mixture.impurities`
        // says it exactly — and says the same thing a station asks to decide whether a sale goes on
        // a shelf or into the unworked heap. These cases are why `pure` is a Boolean?.
        val ore = SpeciesFilter.MIXED
        val kg = Budget.KILOGRAM

        assertTrue(ore.admits(Mixture.of(Species.Iron to 800L * kg, Species.Forsterite to 200L * kg, energy = 0L)))
        // The awkward one: mixed, but only just, and an inclusive ceiling gets it wrong.
        assertTrue(
            ore.admits(Mixture.of(Species.Iron to 995L * kg, Species.Forsterite to 5L * kg, energy = 0L)),
            "a lump 99.5% pure is still a blend and is still ore",
        )
        assertTrue(
            ore.admits(Mixture.of(Species.Iron to 1000L * kg - 1L, Species.Forsterite to 1L, energy = 0L)),
            "a lump one microgram short of pure is still a blend",
        )
        assertTrue(!ore.admits(Mixture.of(Species.Iron to 1000L * kg, energy = 0L)), "pure metal got in as ore")
        assertTrue(!ore.admits(Mixture.EMPTY), "nothing is not a delivery")
    }

    @Test
    fun `a species order and the ore order never compete for the same lump`() {
        // The property that makes it safe to put both on one mouth: pure and mixed are
        // complementary, so `pure iron` and `not pure` partition every lump between them.
        val pureIron = SpeciesFilter(Species.Iron, pure = true)
        val kg = Budget.KILOGRAM
        for (impurity in listOf(0L, 1L, 5L * kg, 200L * kg)) {
            val lump = Mixture.of(
                Species.Iron to 1000L * kg - impurity, Species.Forsterite to impurity, energy = 0L,
            )
            assertTrue(
                pureIron.admits(lump) != SpeciesFilter.MIXED.admits(lump),
                "a lump with ${impurity}g of grit matched both orders or neither",
            )
        }
    }

}
