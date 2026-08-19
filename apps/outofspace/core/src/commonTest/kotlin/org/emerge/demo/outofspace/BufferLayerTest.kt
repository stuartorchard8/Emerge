package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.TileIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BufferLayer] — every machine buffer in the vessel on one layer, separated by *where* rather than
 * by having a layer each.
 *
 * The properties worth pinning are the ones that make one layer safe: two roles of the same machine
 * land on different tiles and cannot merge, and a buffer's contents cannot come apart from the store
 * it labels.
 */
class BufferLayerTest {

    private val tiles = 32
    private fun tile(i: Int) = TileIndex(i)

    private fun ore(iron: Long, energy: Long = 0L) =
        Mixture.of(Species.Iron to iron, energy = energy)

    @Test
    fun `an empty layer holds nothing anywhere`() {
        val buffers = BufferLayer.empty(tiles)
        assertNull(buffers.resourceAt(tile(3)))
        assertEquals(0L, buffers.massAt(tile(3)))
        buffers.checkInvariants()
    }

    @Test
    fun `what goes in comes back out`() {
        val buffers = BufferLayer.empty(tiles)
        buffers.put(tile(5), ore(1_000L, energy = 77L))
        assertEquals(1_000L, buffers.massAt(tile(5)))
        assertEquals(ore(1_000L, energy = 77L), buffers.resourceAt(tile(5)))
        buffers.checkInvariants()
    }

    @Test
    fun `two roles of one machine are separate stores`() {
        // The whole reason a single layer works: an input and an output store are different tiles, so
        // "iron waiting to go in" and "iron waiting to come out" do not add up into one pile.
        val buffers = BufferLayer.empty(tiles)
        val input = tile(4)
        val output = tile(6)
        buffers.put(input, ore(500L))
        buffers.put(output, Mixture.of(Species.Iron to 300L, energy = 0L))

        assertEquals(500L, buffers.massAt(input))
        assertEquals(300L, buffers.massAt(output))
        buffers.checkInvariants()
    }

    @Test
    fun `claiming a role twice is refused rather than merged`() {
        // A one-tile machine resolves every port to its own centre. If it ever asks for two roles
        // there, that must fail loudly instead of silently becoming one store.
        val buffers = BufferLayer.empty(tiles)
        buffers.claimRole(tile(2))
        assertFailsWith<IllegalArgumentException> { buffers.claimRole(tile(2)) }
    }

    @Test
    fun `a claimed role exists while still empty`() {
        val buffers = BufferLayer.empty(tiles)
        buffers.claimRole(tile(2))
        assertTrue(buffers.hasRole(tile(2)))
        assertEquals(0L, buffers.massAt(tile(2)))
        assertNull(buffers.resourceAt(tile(2)))
        buffers.checkInvariants()
    }

    // ── Contents cannot come apart from the store that holds them ─────────────

    @Test
    fun `emptying a store leaves nothing behind`() {
        val buffers = BufferLayer.empty(tiles)
        buffers.put(tile(5), ore(1_000L))
        buffers.put(tile(5), null)
        assertNull(buffers.resourceAt(tile(5)))
        assertEquals(0L, buffers.massAt(tile(5)))
        buffers.checkInvariants()
    }

    @Test
    fun `replacing contents replaces them wholly`() {
        val buffers = BufferLayer.empty(tiles)
        buffers.put(tile(5), ore(1_000L))
        buffers.put(tile(5), Mixture.of(Species.Quartz to 40L, energy = 0L))

        assertEquals(40L, buffers.massAt(tile(5)))
        // The iron from the previous occupant must not still be sitting underneath the new pile.
        assertEquals(0L, buffers.stuff[tile(5), Species.Iron])
        buffers.checkInvariants()
    }

    @Test
    fun `an empty pile is stored as nothing at all`() {
        val buffers = BufferLayer.empty(tiles)
        buffers.put(tile(5), Mixture.EMPTY)
        assertNull(buffers.resourceAt(tile(5)))
        assertEquals(0L, buffers.massAt(tile(5)))
        buffers.checkInvariants()
    }

    @Test
    fun `releasing a role takes its contents with it`() {
        val buffers = BufferLayer.empty(tiles)
        buffers.put(tile(5), ore(1_000L))
        buffers.releaseRole(tile(5))
        assertFalse(buffers.hasRole(tile(5)))
        assertNull(buffers.resourceAt(tile(5)))
        assertEquals(0L, buffers.massAt(tile(5)))
        buffers.checkInvariants()
    }

    @Test
    fun `a released tile can be reused without inheriting the old contents`() {
        val buffers = BufferLayer.empty(tiles)
        buffers.put(tile(5), Mixture.of(Species.Iron to 999L, energy = 0L))
        buffers.releaseRole(tile(5))
        buffers.put(tile(5), Mixture.of(Species.Quartz to 1L, energy = 0L))

        assertEquals(0L, buffers.stuff[tile(5), Species.Iron])
        assertEquals(1L, buffers.massAt(tile(5)))
        buffers.checkInvariants()
    }

    // ── Ledgers and copying ──────────────────────────────────────────────────

    @Test
    fun `totals add up every buffer aboard`() {
        val buffers = BufferLayer.empty(tiles)
        buffers.put(tile(1), ore(100L, energy = 10L))
        buffers.put(tile(2), ore(250L, energy = 20L))
        assertEquals(350L, buffers.totalMass)
        assertEquals(30L, buffers.totalEnergy)
    }

    @Test
    fun `a copy is independent of its original`() {
        val buffers = BufferLayer.empty(tiles)
        buffers.put(tile(1), ore(100L))

        val copy = buffers.copyOf()
        copy.put(tile(1), Mixture.of(Species.Quartz to 5L, energy = 0L))
        copy.put(tile(2), ore(9L))

        assertEquals(100L, buffers.massAt(tile(1)))
        assertEquals(ore(100L), buffers.resourceAt(tile(1)))
        assertNull(buffers.resourceAt(tile(2)))
        buffers.checkInvariants()
        copy.checkInvariants()
    }

    @Test
    fun `a copy equals its original`() {
        val buffers = BufferLayer.empty(tiles)
        buffers.put(tile(1), ore(100L))
        assertEquals(buffers, buffers.copyOf())
        assertEquals(buffers.hashCode(), buffers.copyOf().hashCode())
    }
}
