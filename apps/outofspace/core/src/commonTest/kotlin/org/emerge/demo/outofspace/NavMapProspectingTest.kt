package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.RockSpawner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The nav map answers whatever the reference is open on.
 *
 * The default field is the whole rock — every chunk drawn in its mixture's own colour, brightness
 * being how much rock is there. That says where to fly to mine *something*, and nothing at all about
 * where to fly to mine **titanium**, which is the question a player who has just read the titanium
 * article is holding. So a natural species selected in the reference narrows the field to that
 * species: one colour, and a brightness that is the chunk's share of it against an ordinary rock's.
 *
 * ⚠️ The brightness must be the **ratio** to the ordinary share and not the share itself. Gold is
 * fourteen parts per hundred million in the richest seam the roll can produce; drawn linearly, every
 * chunk in the window is black and the map has quietly stopped being an instrument. The test that
 * would catch a regression to that is [`a rare species is still legible`].
 */
class NavMapProspectingTest {

    @AfterTest
    fun tidy() {
        RockSpawner.highlight = null
        RockSpawner.enabled = true
    }

    /** Fly for a while so the spawner has rolled a window's worth of chunks to read. */
    private fun populatedWindow(): OutofspaceController {
        RockSpawner.enabled = true
        RockSpawner.reset()
        RockSpawner.highlight = null
        val controller = OutofspaceController(CFG, bareHull())
        repeat(TICKS) { controller.stepOnce() }
        return controller
    }

    /** The same bare box [RockTest] flies, and for the same reason: it does not ring. */
    private fun bareHull(): VesselState {
        val grid = CFG.initialGrid
        val deck = DeckArray(grid)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in 1..33) { put(x, 6); put(x, 26) }
        for (y in 6..26) { put(1, y); put(33, y) }
        return VesselState(grid = grid, deck = deck, gravity = VesselState.PLATING_ONE_G, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
    }

    /** Alpha of every window slot — the only channel that says "there is something here". */
    private fun alphas(): List<Int> = buildList {
        for (row in 0 until RockSpawner.WINDOW_SIZE) {
            for (col in 0 until RockSpawner.WINDOW_SIZE) {
                val i = (row * RockSpawner.WINDOW_BUFFER_SIZE + col) * 4
                add(RockSpawner.abundanceBytes[i + 3].toInt() and 0xFF)
            }
        }
    }

    /** Which slots hold rock at all, read off the unfiltered field — the filtered one dims some to nothing. */
    private fun populated(): List<Int> = alphas().withIndex().filter { it.value > 0 }.map { it.index }

    private fun rgb(): List<Int> = buildList {
        for (row in 0 until RockSpawner.WINDOW_SIZE) {
            for (col in 0 until RockSpawner.WINDOW_SIZE) {
                val i = (row * RockSpawner.WINDOW_BUFFER_SIZE + col) * 4
                add(
                    ((RockSpawner.abundanceBytes[i].toInt() and 0xFF) shl 16) or
                        ((RockSpawner.abundanceBytes[i + 1].toInt() and 0xFF) shl 8) or
                        (RockSpawner.abundanceBytes[i + 2].toInt() and 0xFF),
                )
            }
        }
    }

    private companion object {
        val CFG = OutofspaceConfig()

        /** One chunk is rolled per tick, so a full 11×11 window needs at least 121 of them. */
        const val TICKS = 130
    }

    @Test
    fun `a selected species paints the window in its own colour`() {
        populatedWindow()
        assertTrue(alphas().any { it > 0 }, "no chunk was rolled — the fixture flew nowhere")
        val spectrum = rgb()

        val rock = populated()

        RockSpawner.highlight = Species.Iron
        val expected = (speciesColor(Species.Iron).toInt() ushr 8) and 0xFFFFFF
        val single = rgb()
        assertTrue(rock.all { single[it] == expected }, "a prospecting map is one colour, was ${rock.map { single[it] }.distinct()}")
        assertTrue(
            rock.count { alphas()[it] > 0 } * 2 >= rock.size,
            "iron went dark over most of the window — it is the commonest thing in a rock",
        )

        RockSpawner.highlight = null
        assertEquals(spectrum, rgb(), "clearing the selection did not restore the spectrum")
    }

    /**
     * Somewhere in the window is above ordinary and somewhere is below — otherwise the map has no
     * gradient to fly up, which is the entire point of it.
     */
    @Test
    fun `a rare species is still legible`() {
        populatedWindow()
        RockSpawner.highlight = Species.Gold
        val lit = alphas().filter { it > 0 }
        assertTrue(lit.isNotEmpty(), "gold vanished from every chunk — brightness is the raw share again")
        assertTrue(lit.max() > 32, "the richest gold in the window reads as black")
    }

    /** A slot with no rock rolled in it stays empty whatever species is selected. */
    @Test
    fun `empty space is empty for every species`() {
        RockSpawner.enabled = true
        RockSpawner.reset()
        RockSpawner.highlight = Species.Iron
        assertTrue(alphas().all { it == 0 }, "an unpopulated window drew something")
    }
}
