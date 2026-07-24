package org.emerge.render.torus.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scroll-vs-drag arbitration behind [Ui.longPressDrag].
 *
 * This is the contract a phone's gene list depends on: the same cards are both the thing you scroll past and
 * the thing you pick up, so which gesture a press became can only be decided by *how* it was made. Each
 * outcome is asserted directly (did the list scroll? is a card in flight? did the tap fire?) rather than
 * through anything's rendering, because all three failures look identical on screen — nothing happens.
 */
class UiLongPressDragTest {

    /** A scrollable list of one draggable card, laid out over a 200×400 screen. Content is taller than the
     *  viewport so scrolling is possible at all. */
    private class Fixture(longPress: Boolean) {
        val ui = Ui().apply {
            setResolution(200f, 400f)
            longPressDrag = longPress
        }
        var dropped: String? = null
        var dropCount = 0
        var taps = 0

        fun build() = ui.frame {
            scrollArea("list", 0f, 0f, 200f, 400f, rowHeight = 100f) {
                geneCard(listOf(listOf("GENE" to null)), 0x336633FFL, dragId = "card", onDrop = { t -> dropped = t; dropCount++ }) { taps++ }
                repeat(8) { row("FILLER $it") }
            }
        }

        val scrollOffset: Float get() = ui.scrollOffsetOf("list")
    }

    /** Held still on a card: the press is taken away from the list and the card is in flight. */
    @Test
    fun longPressPicksTheCardUp() {
        val f = Fixture(longPress = true)
        f.build()
        f.ui.hitTestDown(100f, 50f)
        assertNull(f.ui.draggingId, "the press alone must not pick anything up")
        f.ui.updateHold(100f, 50f, 0.4f)
        assertEquals("card", f.ui.draggingId, "holding still on a card picks it up")
        assertEquals(0f, f.scrollOffset, "picking a card up must not also scroll the list")
    }

    /** Moved first: the gesture is a scroll, and stays one however long the finger then rests. */
    @Test
    fun movingBeforeTheHoldScrollsInstead() {
        val f = Fixture(longPress = true)
        f.build()
        f.ui.hitTestDown(100f, 50f)
        f.ui.dragTo(100f, 20f)
        f.ui.updateHold(100f, 20f, 1f)
        assertNull(f.ui.draggingId, "a press that moved first is a scroll, not a pickup")
        assertTrue(f.scrollOffset > 0f, "and it scrolled the list")
    }

    /** Released early: an ordinary tap on the card. */
    @Test
    fun shortPressIsStillATap() {
        val f = Fixture(longPress = true)
        f.build()
        f.ui.hitTestDown(100f, 50f)
        f.ui.updateHold(100f, 50f, 0.1f)
        f.ui.hitTestUp(100f, 50f)
        assertEquals(1, f.taps, "a short press on a draggable card still opens it")
        assertEquals(0, f.dropCount, "and drops nothing")
    }

    /** A completed pickup ends in a drop, carrying the target under the release point. */
    @Test
    fun aPickedUpCardDropsOnTheTargetUnderIt() {
        val f = Fixture(longPress = true)
        f.ui.frame {
            scrollArea("list", 0f, 0f, 200f, 400f, rowHeight = 100f) {
                geneCard(listOf(listOf("GENE" to null)), 0x336633FFL, dragId = "card", onDrop = { t -> f.dropped = t; f.dropCount++ }) { f.taps++ }
                button("TARGET", 0x333333FFL, dropTargetId = "bin") {}
            }
        }
        f.ui.hitTestDown(100f, 50f)
        f.ui.updateHold(100f, 50f, 0.4f)
        f.ui.dragTo(100f, 150f)
        f.ui.hitTestUp(100f, 150f)
        assertEquals(1, f.dropCount)
        assertEquals("bin", f.dropped)
        assertEquals(0, f.taps, "the drop is not also a click on what it landed on")
    }

    /** Without the flag (desktop), a press takes the card the moment it moves — no hold required. */
    @Test
    fun mouseDragNeedsNoHold() {
        val f = Fixture(longPress = false)
        f.build()
        f.ui.hitTestDown(100f, 50f)
        f.ui.dragTo(100f, 80f)
        assertEquals("card", f.ui.draggingId, "a mouse takes the card as soon as it moves")
        assertEquals(0f, f.scrollOffset, "and the list underneath does not scroll")
    }

    /** Dragging against a list's bottom edge scrolls it, so a target below the fold can be reached. */
    @Test
    fun draggingAtTheEdgeAutoscrolls() {
        val f = Fixture(longPress = true)
        f.build()
        f.ui.hitTestDown(100f, 50f)
        f.ui.updateHold(100f, 50f, 0.4f)
        f.ui.dragTo(100f, 399f)
        f.ui.updateHold(100f, 399f, 0.2f)
        assertTrue(f.scrollOffset > 0f, "a card held at the bottom edge scrolls the list toward it")
        assertEquals("card", f.ui.draggingId, "and stays in flight while it does")
    }
}
