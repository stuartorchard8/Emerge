package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.StuffLayer
import org.emerge.demo.outofspace.world.TileIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [StuffLayer] — the storage every layer of the world shares.
 *
 * The properties here are the ones the row/bitmask layout is *for*, and they are all structural:
 * a layer costs what it occupies rather than what the grid holds, walking a tile costs what the tile
 * holds rather than what the species table holds, and neither of those optimisations is allowed to
 * change the answers. There is deliberately nothing here about a *magnitude* of memory — the row
 * count is the observable, and it is the thing the design promises.
 */
class StuffLayerTest {

    private val tiles = 64
    private fun tile(i: Int) = TileIndex(i)

    // ── Rows are allocated by writing, and only by writing ────────────────────

    @Test
    fun `an empty layer occupies nothing`() {
        val layer = StuffLayer.empty(tiles)
        assertEquals(0, layer.occupiedTiles)
        assertFalse(layer.occupies(tile(3)))
        layer.checkInvariants()
    }

    @Test
    fun `reading an absent tile answers zero without allocating`() {
        val layer = StuffLayer.empty(tiles)
        assertEquals(0L, layer[tile(3), Species.Iron])
        assertEquals(0L, layer.energyAt(tile(3)))
        assertEquals(0L, layer.massAt(tile(3)))
        // The property that matters: probing every layer at every tile — which is exactly what a
        // cross-layer reaction pass does — must not make every layer dense.
        for (i in 0 until tiles) for (s in Species.ALL) layer[tile(i), s]
        assertEquals(0, layer.occupiedTiles)
    }

    @Test
    fun `writing a zero to an absent tile does not allocate either`() {
        val layer = StuffLayer.empty(tiles)
        layer[tile(3), Species.Iron] = 0L
        layer.setEnergy(tile(3), 0L)
        assertEquals(0, layer.occupiedTiles)
    }

    @Test
    fun `a layer costs the tiles it occupies, not the tiles that exist`() {
        val layer = StuffLayer.empty(tiles)
        for (i in listOf(2, 17, 40)) layer[tile(i), Species.Iron] = 100L
        assertEquals(3, layer.occupiedTiles)
        layer.checkInvariants()
    }

    @Test
    fun `claiming a tile makes it present while holding nothing`() {
        val layer = StuffLayer.empty(tiles)
        layer.claim(tile(5))
        // A freshly built hull occupies its tile and holds no matter, and must still be distinguishable
        // from a tile the layer is not on at all — otherwise it cannot conduct heat.
        assertTrue(layer.occupies(tile(5)))
        assertEquals(0L, layer.massAt(tile(5)))
        assertEquals(1, layer.occupiedTiles)
    }

    // ── The bitmask agrees with the masses, always ────────────────────────────

    @Test
    fun `walking a tile visits exactly what it holds`() {
        val layer = StuffLayer.empty(tiles)
        layer[tile(7), Species.Iron] = 500L
        layer[tile(7), Species.Oxygen] = 30L

        val seen = mutableMapOf<Species, Long>()
        layer.forEachSpecies(tile(7)) { s, m -> seen[s] = m }
        assertEquals(mapOf(Species.Iron to 500L, Species.Oxygen to 30L), seen)
    }

    @Test
    fun `zeroing a species removes it from the walk`() {
        val layer = StuffLayer.empty(tiles)
        layer[tile(7), Species.Iron] = 500L
        layer[tile(7), Species.Oxygen] = 30L
        layer[tile(7), Species.Iron] = 0L

        val seen = mutableListOf<Species>()
        layer.forEachSpecies(tile(7)) { s, _ -> seen.add(s) }
        assertEquals(listOf(Species.Oxygen), seen)
        assertEquals(30L, layer.massAt(tile(7)))
        layer.checkInvariants()
    }

    @Test
    fun `the walk covers every species, including the last word of the bitmask`() {
        // Three 64-bit words cover 165 species; the last one is the partly-filled one, so a species
        // near the end is the case an off-by-one in the word loop would drop.
        val layer = StuffLayer.empty(tiles)
        for (s in Species.ALL) layer[tile(1), s] = (s.ordinal + 1).toLong()

        var count = 0
        layer.forEachSpecies(tile(1)) { s, m ->
            count++
            assertEquals((s.ordinal + 1).toLong(), m)
        }
        assertEquals(Species.COUNT, count)
        layer.checkInvariants()
    }

    @Test
    fun `the last declared species survives a round trip`() {
        val layer = StuffLayer.empty(tiles)
        val last = Species.ALL.last()
        layer[tile(1), last] = 77L
        assertEquals(77L, layer[tile(1), last])
        val seen = mutableListOf<Species>()
        layer.forEachSpecies(tile(1)) { s, _ -> seen.add(s) }
        assertEquals(listOf(last), seen)
    }

    // ── Growth ───────────────────────────────────────────────────────────────

    @Test
    fun `growing past the initial rows keeps every value`() {
        val layer = StuffLayer.empty(tiles)
        for (i in 0 until tiles) {
            layer[tile(i), Species.Iron] = (i + 1).toLong()
            layer.setEnergy(tile(i), (i + 1) * 10L)
        }
        assertEquals(tiles, layer.occupiedTiles)
        for (i in 0 until tiles) {
            assertEquals((i + 1).toLong(), layer[tile(i), Species.Iron])
            assertEquals((i + 1) * 10L, layer.energyAt(tile(i)))
        }
        layer.checkInvariants()
    }

    // ── Release ──────────────────────────────────────────────────────────────

    @Test
    fun `releasing a tile drops it and leaves the others untouched`() {
        val layer = StuffLayer.empty(tiles)
        for (i in listOf(2, 17, 40)) {
            layer[tile(i), Species.Iron] = i.toLong()
            layer.setEnergy(tile(i), i * 100L)
        }
        layer.release(tile(17))

        assertFalse(layer.occupies(tile(17)))
        assertEquals(0L, layer[tile(17), Species.Iron])
        assertEquals(2, layer.occupiedTiles)
        assertEquals(2L, layer[tile(2), Species.Iron])
        assertEquals(40L, layer[tile(40), Species.Iron])
        assertEquals(4000L, layer.energyAt(tile(40)))
        layer.checkInvariants()
    }

    @Test
    fun `releasing the last row is not a special case`() {
        val layer = StuffLayer.empty(tiles)
        layer[tile(2), Species.Iron] = 2L
        layer[tile(9), Species.Iron] = 9L
        layer.release(tile(9))
        assertEquals(1, layer.occupiedTiles)
        assertEquals(2L, layer[tile(2), Species.Iron])
        layer.checkInvariants()
    }

    @Test
    fun `a released tile can be claimed again and starts empty`() {
        val layer = StuffLayer.empty(tiles)
        layer[tile(4), Species.Iron] = 999L
        layer.setEnergy(tile(4), 999L)
        layer.release(tile(4))
        layer[tile(4), Species.Oxygen] = 1L

        // The recycled row must not still hold the demolished machine's iron — this is the
        // "a freshly laid rail inherits the furnace's heat" failure, in its new form.
        assertEquals(0L, layer[tile(4), Species.Iron])
        assertEquals(0L, layer.energyAt(tile(4)))
        assertEquals(1L, layer.massAt(tile(4)))
        layer.checkInvariants()
    }

    @Test
    fun `releasing does not disturb the tiles whose rows moved`() {
        val layer = StuffLayer.empty(tiles)
        for (i in 0 until 20) layer[tile(i), Species.Iron] = (i + 1).toLong()
        layer.release(tile(0))
        for (i in 1 until 20) assertEquals((i + 1).toLong(), layer[tile(i), Species.Iron])
        layer.checkInvariants()
    }

    // ── Ledgers ──────────────────────────────────────────────────────────────

    @Test
    fun `totals add up what is held`() {
        val layer = StuffLayer.empty(tiles)
        layer[tile(1), Species.Iron] = 10L
        layer[tile(1), Species.Oxygen] = 5L
        layer[tile(2), Species.Iron] = 7L
        layer.setEnergy(tile(1), 100L)
        layer.setEnergy(tile(2), 200L)

        assertEquals(22L, layer.totalMass)
        assertEquals(300L, layer.totalEnergy)
    }

    @Test
    fun `released mass leaves the ledger`() {
        val layer = StuffLayer.empty(tiles)
        layer[tile(1), Species.Iron] = 10L
        layer[tile(2), Species.Iron] = 7L
        layer.release(tile(1))
        assertEquals(7L, layer.totalMass)
    }

    // ── Copying ──────────────────────────────────────────────────────────────

    @Test
    fun `a copy is independent of its original`() {
        val layer = StuffLayer.empty(tiles)
        layer[tile(1), Species.Iron] = 10L
        layer.setEnergy(tile(1), 50L)

        val copy = layer.copyOf()
        copy[tile(1), Species.Iron] = 99L
        copy.setEnergy(tile(1), 99L)
        copy[tile(2), Species.Oxygen] = 1L

        assertEquals(10L, layer[tile(1), Species.Iron])
        assertEquals(50L, layer.energyAt(tile(1)))
        assertFalse(layer.occupies(tile(2)))
        layer.checkInvariants()
        copy.checkInvariants()
    }

    @Test
    fun `a copy equals its original`() {
        val layer = StuffLayer.empty(tiles)
        layer[tile(1), Species.Iron] = 10L
        layer.setEnergy(tile(1), 50L)
        assertEquals(layer, layer.copyOf())
        assertEquals(layer.hashCode(), layer.copyOf().hashCode())
    }

    @Test
    fun `equality is about what is held, not the order rows were allocated in`() {
        // release() backfills, so two layers that hold the same thing routinely disagree about which
        // row a tile sits in. That must not make them unequal — the world is compared for saves.
        val a = StuffLayer.empty(tiles)
        a[tile(1), Species.Iron] = 10L
        a[tile(2), Species.Iron] = 20L
        a[tile(3), Species.Iron] = 30L
        a.release(tile(1))

        val b = StuffLayer.empty(tiles)
        b[tile(3), Species.Iron] = 30L
        b[tile(2), Species.Iron] = 20L

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `a difference in mass is a difference`() {
        val a = StuffLayer.empty(tiles)
        a[tile(1), Species.Iron] = 10L
        val b = StuffLayer.empty(tiles)
        b[tile(1), Species.Iron] = 11L
        assertNotEquals(a, b)
    }

    @Test
    fun `a difference in energy is a difference`() {
        val a = StuffLayer.empty(tiles)
        a.setEnergy(tile(1), 10L)
        val b = StuffLayer.empty(tiles)
        b.setEnergy(tile(1), 11L)
        assertNotEquals(a, b)
    }

    // ── Iteration over the layer ─────────────────────────────────────────────

    @Test
    fun `walking the layer visits every occupied tile once`() {
        val layer = StuffLayer.empty(tiles)
        val placed = listOf(2, 17, 40)
        for (i in placed) layer[tile(i), Species.Iron] = 1L

        val seen = mutableListOf<Int>()
        layer.forEachOccupiedTile { seen.add(it.index) }
        assertEquals(placed.sorted(), seen.sorted())
    }

    @Test
    fun `add accumulates and clears back to absent`() {
        val layer = StuffLayer.empty(tiles)
        layer.add(tile(1), Species.Iron, 10L)
        layer.add(tile(1), Species.Iron, 5L)
        assertEquals(15L, layer[tile(1), Species.Iron])
        layer[tile(1), Species.Iron] = 0L
        var walked = 0
        layer.forEachSpecies(tile(1)) { _, _ -> walked++ }
        assertEquals(0, walked)
        // The tile is still part of the layer — emptied, not demolished.
        assertTrue(layer.occupies(tile(1)))
    }
}
