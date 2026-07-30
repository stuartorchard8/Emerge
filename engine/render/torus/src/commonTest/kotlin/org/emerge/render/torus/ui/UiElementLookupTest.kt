package org.emerge.render.torus.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Ui.element] — "where the widget `tap-ui <label>` would hit is" — and [Ui.lastPanelRect].
 *
 * Both exist for the campaign coach's spotlight: it draws a box around a named widget and a connector from
 * its own panel to it. The property under test is **agreement**, not geometry: whatever [Ui.tapLabel] would
 * fire is what [Ui.element] must return a rect for, since a coach that circles one widget while a script taps
 * another is worse than a coach that says nothing.
 */
class UiElementLookupTest {

    /** Two panels: a left one of plain buttons, a right one that repeats a label the left panel also uses. */
    private class Fixture {
        var fired: String? = null
        val ui = Ui().apply { setResolution(400f, 300f) }

        fun build() = ui.frame {
            panel(Anchor.TopLeft) {
                button("+ NEW GENE", 0x336633FFL) { fired = "left-new-gene" }
                button("USE LIGHT", 0x333366FFL) { fired = "left-light" }
            }
            panel(Anchor.TopRight) {
                button("USE LIGHT", 0x333366FFL) { fired = "right-light" }
            }
        }
    }

    @Test fun aLabelResolvesToTheRectOfTheWidgetThatWouldBeTapped() {
        val f = Fixture()
        f.build()
        val hit = assertNotNull(f.ui.element("+ NEW GENE"), "the button is on screen")
        assertTrue(hit.w > 0f && hit.h > 0f, "a real rect")
        // The rect must be the one that gets clicked: press its centre and the right handler fires.
        f.ui.hitTestDown(hit.x + hit.w / 2f, hit.y + hit.h / 2f)
        f.ui.hitTestUp(hit.x + hit.w / 2f, hit.y + hit.h / 2f)
        assertEquals("left-new-gene", f.fired, "the rect points at the widget it names")
    }

    /**
     * The reason occurrence exists: a gene card's tokens repeat across cards, so "the DIVIDE gene's energy
     * source" is only ever *the n-th* `USE LIGHT`. The spotlight must count them the way `tap-ui @n` does.
     */
    @Test fun occurrencePicksTheSameMatchTapLabelWould() {
        val f = Fixture()
        f.build()
        val first = assertNotNull(f.ui.element("USE LIGHT", 1))
        val second = assertNotNull(f.ui.element("USE LIGHT", 2))
        assertTrue(first.x != second.x, "two different widgets, in two different panels")

        f.build(); f.ui.tapLabel("USE LIGHT", 2)
        val byTap = f.fired
        f.build(); f.ui.hitTestDown(second.x + second.w / 2f, second.y + second.h / 2f)
        f.ui.hitTestUp(second.x + second.w / 2f, second.y + second.h / 2f)
        assertEquals(byTap, f.fired, "element(@2) and tapLabel(@2) must resolve to the same widget")
    }

    /** No match ⇒ null, which is what makes an unresolvable spotlight a quiet coach rather than a box drawn
     *  at the origin. */
    @Test fun anAbsentLabelResolvesToNothing() {
        val f = Fixture()
        f.build()
        assertNull(f.ui.element("+ ADD REPRODUCE"))
        assertNull(f.ui.element("USE LIGHT", 3), "only two of them exist")
    }

    /**
     * `offsetX` nudges a panel off its anchor — the campaign coach's way of centring on the free world area
     * rather than the screen, since the cell panel docks right and is drawn over anything that reaches under
     * it. A centre anchor cannot express "centred in what's left" on its own.
     */
    @Test fun offsetXShiftsAPanelOffItsAnchor() {
        val ui = Ui().apply { setResolution(400f, 300f) }
        fun place(offset: Float): Ui.UiElement {
            ui.frame { panel(Anchor.BottomCenter, offsetX = offset) { button("COACH", 0x333366FFL) {} } }
            return ui.lastPanelRect!!
        }
        val centred = place(0f)
        val shifted = place(-50f)
        assertEquals(centred.w, shifted.w, "the same panel, only moved")
        assertEquals(-50f, shifted.x - centred.x, "moved left by exactly the offset (scale 1)")
        // ...and the widget inside moves with it, so a spotlight still resolves against the shifted panel.
        val inside = assertNotNull(ui.element("COACH"))
        assertTrue(inside.x >= shifted.x && inside.x + inside.w <= shifted.x + shifted.w)
    }

    /** A tall genome in a short viewport: the same three labels every frame, scrolled. */
    private fun scrolled(offset: Float): Ui {
        val ui = Ui().apply { setResolution(400f, 300f) }
        fun build() = ui.frame {
            scrollArea("genome", 0f, 0f, 200f, 60f, rowHeight = 30f) {
                button("GENE ONE", 0x333366FFL) {}
                button("GENE TWO", 0x333366FFL) {}
                button("GENE THREE", 0x333366FFL) {}
            }
        }
        build() // lay out once so the area knows its content height, then scroll and rebuild.
        ui.scrollBy("genome", offset)
        build()
        return ui
    }

    /**
     * A row scrolled *fully* out of its viewport is culled at layout, so it never becomes a region at all —
     * which is the fail-quiet the spotlight wants for free: no rect, no box, and the hint text still names it.
     * Worth pinning, because it is the reason "point at a target below the fold" cannot draw off-panel.
     */
    @Test fun aRowBelowTheFoldIsNotARegionAtAll() {
        val ui = scrolled(0f)
        assertTrue(assertNotNull(ui.element("GENE ONE")).visible, "at the top of the viewport")
        assertNull(ui.element("GENE THREE"), "culled below the fold, exactly as an absent label would be")
    }

    @Test fun scrollingBringsATargetIntoView() {
        val ui = scrolled(60f)
        assertTrue(assertNotNull(ui.element("GENE THREE")).visible, "scrolled down to it")
        assertNull(ui.element("GENE ONE"), "and the first has left the top")
    }

    /**
     * The case culling does *not* cover: a row straddling the viewport edge is emitted, and drawn clipped.
     * The clip travels with the element so a decoration can be clamped the same way, instead of its far edge
     * spilling past the panel onto the world behind.
     */
    @Test fun aPartlyScrolledRowIsVisibleAndCarriesItsViewport() {
        val ui = scrolled(15f)
        val straddling = assertNotNull(ui.element("GENE ONE"), "half of it is still on screen")
        assertTrue(straddling.visible)
        val clip = assertNotNull(straddling.clip)
        assertEquals(60f, clip.h, "the viewport's height, not the content's")
        assertTrue(straddling.y < clip.y, "it really does start above the viewport's top edge")
    }

    /** The connector's anchor end: a panel is auto-sized and anchor-placed, so its rect is knowable only from
     *  the toolkit. It reports the panel most recently emitted, and resets with the frame. */
    @Test fun theLastPanelRectIsTheLastPanelEmitted() {
        val f = Fixture()
        assertNull(f.ui.lastPanelRect, "nothing has been drawn yet")
        f.build()
        val rect = assertNotNull(f.ui.lastPanelRect)
        val right = assertNotNull(f.ui.element("USE LIGHT", 2), "the right panel's button")
        assertTrue(
            right.x >= rect.x && right.x + right.w <= rect.x + rect.w,
            "the last panel is the TopRight one, so it contains that button",
        )
    }
}
