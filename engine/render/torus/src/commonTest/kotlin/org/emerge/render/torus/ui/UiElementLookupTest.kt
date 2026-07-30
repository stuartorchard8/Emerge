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
