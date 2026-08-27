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
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Stockpile
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Storage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The inventory answers two different questions and must not confuse them: **how much is aboard**,
 * and **what could a construction site actually be built from**.
 *
 * ⛔ The second is a per-storage fact and the first is a sum, so one cannot be recovered from the
 * other — which is what made the old one-heap view useless the day `BUILD_PURITY_PERCENT` went to
 * 100. These pin the distinction from both sides.
 */
class StockpileTest {

    private val grid = Grid(16, 8)

    /** A vessel with one tank per entry, each holding exactly what it is given. */
    private fun vesselHolding(vararg tanks: Mixture): VesselState {
        val deck = DeckArray(grid)
        val at = tanks.indices.map { grid.tile(2 + it * 4, 4) }
        for (tile in at) deck += Storage(tile, Direction.Right)
        var s = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(arrayOfNulls<org.emerge.demo.outofspace.world.Segment>(grid.size).toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
        for ((i, tile) in at.withIndex()) s = s.stocked(tile, tanks[i], BufferRole.Inside)
        return s
    }

    private fun pure(species: Species, packets: Long) =
        Mixture.of(species to packets * Capacity.PACKET_MASS, energy = 0L)

    /**
     * ⛔ **The case the old view got wrong, and the reason this was rewritten.** Two pure tanks of
     * different metals are two things you can build with. Summed they are a 50/50 blend with no
     * dominant species, which is neither of them and is not buildable at all.
     */
    @Test
    fun `two pure tanks are two buildable materials, not one blend`() {
        val s = vesselHolding(pure(Species.Iron, 4), pure(Species.Titanium, 4))
        val stock = s.stockpile

        assertEquals(8 * Capacity.PACKET_MASS, stock.totalMass, "the heap is still the whole of it")
        assertEquals(4 * Capacity.PACKET_MASS, stock.buildable(Species.Iron), "iron")
        assertEquals(4 * Capacity.PACKET_MASS, stock.buildable(Species.Titanium), "titanium")
        assertEquals(
            listOf(Species.Iron, Species.Titanium).toSet(),
            stock.buildableSpecies.toSet(),
            "both metals should be offered",
        )
    }

    /**
     * ⛔ **A tank that is nearly pure is not buildable, and saying so is the whole point.**
     *
     * A packet is a proportional sample rather than the good bits skimmed off, so a 99% iron tank
     * emits 99% iron packets and `buildableFrom` refuses every one of them. An inventory that
     * reported that tank as iron would be lying about the only thing it is being asked.
     */
    @Test
    fun `a tank one microgram off pure offers nothing`() {
        val nearly = Mixture.of(
            Species.Iron to 4 * Capacity.PACKET_MASS - 1L,
            Species.Quartz to 1L,
            energy = 0L,
        )
        val stock = vesselHolding(nearly).stockpile

        assertTrue(stock.totalMass > 0L, "the matter is still aboard")
        assertEquals(0L, stock.buildable(Species.Iron), "a contaminated tank was offered as iron")
        assertEquals(0L, stock.inFabric(Species.Iron), "and none of it is in the fabric either")
    }

    /**
     * ⛔ **The tank itself is made of something, and that counts.** A storage is a titanium casing,
     * so a vessel that holds nothing whatsoever still has titanium aboard — recoverable by taking
     * the tank apart, and reported as fabric rather than as loose so nobody is promised a build the
     * network cannot start.
     *
     * ⚠️ Three tests in this class asserted the *shortlist was empty* before the sweep widened, and
     * all three were wrong for this reason rather than by a rounding: the vessel they described was
     * standing on metal the whole time.
     */
    @Test
    fun `a vessel is made of something, and that is stock too`() {
        val stock = vesselHolding(pure(Species.Iron, 1)).stockpile

        assertEquals(0L, stock.inFabric(Species.Iron), "loose iron is not fabric")
        assertTrue(stock.buildable(Species.Iron) > 0L, "and it is loose")
        assertTrue(
            stock.inFabric(Species.Titanium) > 0L,
            "a tank is made of titanium, so there is titanium in the fabric",
        )
        assertEquals(0L, stock.buildable(Species.Titanium), "but none of it is loose")
    }

    /** Two tanks of the same thing add up, because a site can draw from either. */
    @Test
    fun `tanks holding the same species pool`() {
        val stock = vesselHolding(pure(Species.Iron, 3), pure(Species.Iron, 5)).stockpile
        assertEquals(8 * Capacity.PACKET_MASS, stock.buildable(Species.Iron), "iron should pool")
        assertEquals(1, stock.buildableSpecies.count { it == Species.Iron }, "and read as one entry")
    }

    /** Heaviest first: a picker's shortlist is ordered by what you have most of, as ONI's is. */
    @Test
    fun `the offer is ordered by how much there is`() {
        val stock = vesselHolding(
            pure(Species.Copper, 1),
            pure(Species.Iron, 9),
            pure(Species.Titanium, 4),
        ).stockpile
        // ⚠️ Only the metals put in the tanks, because the tanks themselves are titanium and their
        // casings are legitimately in the list too — see `a vessel is made of something`.
        val stored = stock.buildableSpecies.filter { stock.buildable(it) > 0L }
        assertEquals(
            listOf(Species.Iron, Species.Titanium, Species.Copper),
            stored,
            "the shortlist is not in descending order of mass",
        )
    }

    /** An empty vessel offers nothing and does not pretend otherwise. */
    @Test
    fun `nothing aboard offers nothing`() {
        assertTrue(Stockpile.EMPTY.isEmpty, "the empty stockpile is empty")
        assertTrue(Stockpile.EMPTY.buildableSpecies.isEmpty(), "and offers nothing")
        assertEquals(0L, Stockpile.EMPTY.buildable(Species.Iron), "including iron")
    }
}

/**
 * The mass ledger's write-off: a drift recorded once so the tripwire can be trusted again.
 *
 * ⛔ **The dangerous version of this feature is one that keeps writing off.** The whole value of the
 * balance is that it is zero until something is wrong; a mechanism that re-anchors on every load
 * would turn every future leak into a silent correction, which is strictly worse than the permanent
 * red light it replaces. So the gate is what these mostly test.
 */
class ReconciledLedgerTest {

    private val cfg = OutofspaceConfig()

    private fun drift(s: VesselState): Long =
        s.inTransitMass + s.ventedMass + s.builtMass -
            s.extractedMass - s.baselineCargoMass - s.reconciledMass

    /** A world that has lost a tonne without saying so — a save written before anybody was looking. */
    private fun scarred(): VesselState =
        starterVessel(cfg.initialGrid).copy(extractedMass = 1_000_000L * org.emerge.demo.outofspace.num.Budget.GRAM)

    /**
     * A file that never stated the term gets its drift written off, once, and the balance closes.
     *
     * Measured on the real save this was built for: 1.0 t, frozen across a thousand ticks, so the
     * sim conserves mass today and only lacked a clean starting point.
     */
    @Test
    fun `a save written before the term existed is anchored on load`() {
        val before = scarred()
        assertTrue(drift(before) != 0L, "fixture: the world is supposed to be out of balance")

        val loaded = Save.read(Save.write(before).lines().filterNot { it.startsWith("reconciled ") }.joinToString("\n"))

        assertEquals(0L, drift(loaded), "loading did not anchor the ledger")
        assertEquals(
            before.inTransitMass + before.ventedMass + before.builtMass -
                before.extractedMass - before.baselineCargoMass,
            loaded.reconciledMass,
            "the write-off is not the drift it was standing in for",
        )
    }

    /**
     * ⛔ **The one that matters: a file that HAS stated the term is never re-anchored**, so a leak
     * that develops after the write-off is reported rather than absorbed.
     *
     * Gated on the field's own absence rather than on a version number, because the question is
     * whether this file has ever had its ledger anchored and the absence is the exact answer.
     */
    @Test
    fun `a leak after the write-off is still reported`() {
        val anchored = Save.read(
            Save.write(scarred()).lines().filterNot { it.startsWith("reconciled ") }.joinToString("\n"),
        )
        assertEquals(0L, drift(anchored), "fixture: it should be balanced after anchoring")

        // Now lose a further tonne, the way a real leak would arrive, and save and reload it.
        val leaked = anchored.copy(
            extractedMass = anchored.extractedMass + 1_000_000L * org.emerge.demo.outofspace.num.Budget.GRAM,
        )
        val reloaded = Save.read(Save.write(leaked))

        assertEquals(
            anchored.reconciledMass,
            reloaded.reconciledMass,
            "the write-off moved, so the new leak was laundered into it",
        )
        assertTrue(drift(reloaded) != 0L, "a leak arriving after the write-off went unreported")
    }

    /** And a world that never drifted is written with a zero and stays at zero. */
    @Test
    fun `a clean world is not given a write-off`() {
        val clean = starterVessel(cfg.initialGrid)
        assertEquals(0L, drift(clean), "fixture: the starter vessel balances")
        val reloaded = Save.read(Save.write(clean))
        assertEquals(0L, reloaded.reconciledMass, "a clean world was written off anyway")
        assertEquals(0L, drift(reloaded), "and it should still balance")
    }

    /** It survives a round trip like any other ledger term. */
    @Test
    fun `the write-off round-trips`() {
        val s = starterVessel(cfg.initialGrid).copy(reconciledMass = -12_345L)
        assertEquals(-12_345L, Save.read(Save.write(s)).reconciledMass, "the write-off did not survive")
    }
}
