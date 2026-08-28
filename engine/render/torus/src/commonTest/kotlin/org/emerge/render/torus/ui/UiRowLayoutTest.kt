package org.emerge.render.torus.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [PanelBuilder.row] — the toolkit's one horizontal container — and the two ways it hands out width:
 * [PanelBuilder.spacer] (take what's left over) and `weight` (share what's left over).
 *
 * The property under test is that **a weight beats the text**. Every widget here could lay itself out
 * by measuring its own label, and the reason [PanelBuilder.clauseRow] does not is that an operand which
 * resized as you picked a longer value would slide the comparator out from under your finger. A test
 * that only checked "the row draws something" would pass just as well against natural widths, so each
 * case below is built to fail if the weight were ignored.
 */
class UiRowLayoutTest {

    private fun ui() = Ui().apply { setResolution(800f, 600f) }

    /** The whole point: wildly uneven operands still come out the same width. */
    @Test fun weightedSiblingsAreEqualHoweverLongTheirTextIs() {
        val ui = ui()
        ui.frame {
            panel(Anchor.TopLeft) {
                clauseRow(
                    lhs = "A VERY LONG OPERAND INDEED", cmp = ">", rhs = "X",
                    onLhs = {}, onCmp = {}, onRhs = {},
                )
            }
        }
        val lhs = assertNotNull(ui.element("A VERY LONG OPERAND INDEED"), "lhs chip is on screen")
        val rhs = assertNotNull(ui.element("X"), "rhs chip is on screen")
        assertTrue(
            abs(lhs.w - rhs.w) < 1f,
            "operands share the leftover equally: lhs=${lhs.w} rhs=${rhs.w} — natural widths would differ wildly",
        )
        // ...and the fixed-width comparator sits between them, not scaled with either.
        val cmp = assertNotNull(ui.element(">"), "comparator is on screen")
        assertTrue(cmp.x > lhs.x + lhs.w - 1f && cmp.x + cmp.w <= rhs.x + 1f, "comparator lies between the operands")
    }

    /** A weighted row spends exactly the width it was given — no drift, no overhang. */
    @Test fun aWeightedRowFillsItsPanelExactly() {
        val ui = ui()
        ui.frame {
            panel(Anchor.TopLeft, minWidth = 500f) {
                clauseRow(lhs = "L", cmp = "=", rhs = "R", onLhs = {}, onCmp = {}, onRhs = {})
            }
        }
        val panel = assertNotNull(ui.lastPanelRect)
        val lhs = assertNotNull(ui.element("L"))
        val rhs = assertNotNull(ui.element("R"))
        // The row starts at the panel's left padding and ends at its right one, symmetrically.
        val leftPad = lhs.x - panel.x
        val rightPad = (panel.x + panel.w) - (rhs.x + rhs.w)
        assertTrue(abs(leftPad - rightPad) < 1f, "row fills the content box: leftPad=$leftPad rightPad=$rightPad")
    }

    /** A spacer takes the slack, so the item after it lands hard against the right edge. */
    @Test fun aSpacerPushesWhatFollowsToTheRightEdge() {
        val ui = ui()
        ui.frame {
            panel(Anchor.TopLeft, minWidth = 400f) {
                row {
                    button("LEFT", 0x333333FFL) {}
                    spacer()
                    button("RIGHT", 0x333333FFL) {}
                }
            }
        }
        val panel = assertNotNull(ui.lastPanelRect)
        val left = assertNotNull(ui.element("LEFT"))
        val right = assertNotNull(ui.element("RIGHT"))
        assertTrue(right.x > left.x + left.w + 100f, "the spacer actually spread them apart")
        val leftPad = left.x - panel.x
        val rightPad = (panel.x + panel.w) - (right.x + right.w)
        assertTrue(abs(leftPad - rightPad) < 1f, "the trailing item sits at the right padding: $rightPad vs $leftPad")
    }

    /** ⚠️ The guarantee that makes a spacer safe to add anywhere: it never widens the panel. */
    @Test fun aSpacerDoesNotWidenThePanel() {
        fun widthOf(withSpacer: Boolean): Float {
            val ui = ui()
            ui.frame {
                panel(Anchor.TopLeft) {
                    row {
                        button("LEFT", 0x333333FFL) {}
                        if (withSpacer) spacer()
                        button("RIGHT", 0x333333FFL) {}
                    }
                }
            }
            return assertNotNull(ui.lastPanelRect).w
        }
        assertTrue(
            widthOf(withSpacer = true) <= widthOf(withSpacer = false) + 0.01f,
            "a spacer measures zero: ${widthOf(true)} vs ${widthOf(false)}",
        )
    }

    /**
     * ⚠️ The panel must reserve **twice the longer** operand, not the two added together — the bug that
     * put a chip's dropdown arrow on top of its own text. Summing looks right until the two differ.
     */
    @Test fun aWeightedRowReservesEnoughForItsWidestChild() {
        val ui = ui()
        ui.frame {
            panel(Anchor.TopLeft) {
                clauseRow(lhs = "AT LEAST", cmp = "100%", rhs = "X", onLhs = {}, onCmp = {}, onRhs = {})
            }
        }
        val lhs = assertNotNull(ui.element("AT LEAST"))
        // Each operand got half the leftover, so half must still cover the *longer* one's own text.
        val textW = UiTextRenderer.measureWidthPx("AT LEAST", 16f)
        assertTrue(
            lhs.w > textW,
            "the operand fits its own text: ${lhs.w} vs $textW — a summed reservation would fall short",
        )
    }

    /** A row of nothing is nothing — `actionRow(emptyList())` is a real call, and `maxOf` has no answer. */
    @Test fun anEmptyRowAddsNothing() {
        val ui = ui()
        ui.frame {
            panel(Anchor.TopLeft) {
                title("KEPT")
                row { }
                controlRow(emptyList())
            }
        }
        assertNotNull(ui.element("KEPT"), "the panel still built")
    }

    /** Segments are uniform because their width is a property of the *set*, not of each label. */
    @Test fun segmentsAreAllAsWideAsTheLongestOption() {
        val ui = ui()
        ui.frame {
            panel(Anchor.TopLeft) { segmented("KEEP", listOf("A", "LONGEST"), 0) {} }
        }
        val short = assertNotNull(ui.element("A"))
        val long = assertNotNull(ui.element("LONGEST"))
        assertEquals(long.w, short.w, absoluteTolerance = 0.01f, "every segment is as wide as the widest")
    }
}
