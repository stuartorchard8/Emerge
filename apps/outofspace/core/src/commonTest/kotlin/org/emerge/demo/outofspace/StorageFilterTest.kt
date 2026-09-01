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
        val s = run(tankToTank(SpeciesFilter(Species.Iron, 90)), 40 * RAIL_PERIOD)

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
        val s = run(tankToTank(SpeciesFilter(Species.Titanium, 90)), 40 * RAIL_PERIOD)
        assertTrue(s.received() > 0L, "a warehouse locked to titanium refused titanium")
    }

    /**
     * ⚠️ The threshold is the player's number and is read as written: 98% ore clears 90 and misses
     * 99. If a filter were ever routed
     * through `buildableFrom` this reads 99% *of* the build tolerance instead, which is 94%, and
     * the ore sails in while the panel says 99.
     */
    @Test
    fun `the threshold is the number the player set`() {
        val ore = titanium(load)
        assertTrue(SpeciesFilter(Species.Titanium, 90).admits(ore))
        assertTrue(!SpeciesFilter(Species.Titanium, 99).admits(ore))
        assertTrue(!SpeciesFilter(Species.Titanium, 90).admits(Mixture.EMPTY), "nothing is not a delivery")
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
            tankToTank(SpeciesFilter(null, 100), pureIron(load), receiverIsGhost = true, railTo = 10),
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
        val start = tankToTank(SpeciesFilter(null, 100), pureIron(load), receiverIsGhost = true, railTo = 10)

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
        val s = run(tankToTank(SpeciesFilter(null, 100), pureIron(load), railTo = 10), 40 * RAIL_PERIOD)
        assertTrue(s.received() > 0L, "a warehouse locked at 100% refused a lump of one species")
    }

    /** A lock is the player's decision, so it survives the file. */
    @Test
    fun `a lock survives a save and a load`() {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += fixtureStorage(grid.tile(2, 3), Direction.Right, filter = SpeciesFilter(Species.Titanium, 70))
        val state = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(arrayOfNulls<Segment>(grid.size).toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
        val back = Save.read(Save.write(state))
        assertEquals(
            SpeciesFilter(Species.Titanium, 70),
            (back.deck[grid.tile(2, 3)] as Storage).filter,
        )
    }
}
