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
import org.emerge.demo.outofspace.world.Stockpile
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
        assertTrue(stock.buildableSpecies.isEmpty(), "nothing in this vessel can build anything")
    }

    /** Two tanks of the same thing add up, because a site can draw from either. */
    @Test
    fun `tanks holding the same species pool`() {
        val stock = vesselHolding(pure(Species.Iron, 3), pure(Species.Iron, 5)).stockpile
        assertEquals(8 * Capacity.PACKET_MASS, stock.buildable(Species.Iron), "iron should pool")
        assertEquals(listOf(Species.Iron), stock.buildableSpecies, "and read as one entry")
    }

    /** Heaviest first: a picker's shortlist is ordered by what you have most of, as ONI's is. */
    @Test
    fun `the offer is ordered by how much there is`() {
        val stock = vesselHolding(
            pure(Species.Copper, 1),
            pure(Species.Iron, 9),
            pure(Species.Titanium, 4),
        ).stockpile
        assertEquals(
            listOf(Species.Iron, Species.Titanium, Species.Copper),
            stock.buildableSpecies,
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
