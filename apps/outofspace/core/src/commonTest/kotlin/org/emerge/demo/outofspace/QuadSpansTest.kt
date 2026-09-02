package org.emerge.demo.outofspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scanline fill behind the nav map's ship silhouette.
 *
 * ⚠️ **Tested here because it cannot be reviewed where it is used.** The silhouette is about eleven
 * pixels by six, so a fault in it renders as a slightly wrong smudge — the first version of this was
 * called correct off a screenshot and the second was called broken off one, and both readings were
 * of a 10× nearest-neighbour blowup of a shape smaller than a word.
 */
class QuadSpansTest {

    /** A rectangle fills its rows, at its full width, and no others. */
    @Test
    fun `an axis-aligned rectangle fills exactly itself`() {
        val spans = spansOf(
            floatArrayOf(-5f, 6f, 6f, -5f),
            floatArrayOf(-3f, -3f, 3f, 3f),
        )
        assertEquals(6, spans.size, "a six-pixel-tall box wants six rows: $spans")
        for ((_, _, w) in spans) assertEquals(11f, w, 0.001f, "every row of a rectangle is its width")
        assertEquals(-3f, spans.first().second, "the first row is the top edge")
        assertEquals(2f, spans.last().second, "the last row is the one above the bottom edge")
    }

    /**
     * A turned quad comes to a point at each end, with no notch on the way.
     *
     * ⛔ **This is the half-open rule.** Counted with `<=` at both ends, a scanline through a vertex
     * takes its span from one edge and back again, and the shape grows a notch at exactly the row
     * where it should be narrowest. The widths of a convex quad rise and then fall, once.
     */
    @Test
    fun `a turned quad has no notch`() {
        val c = 0.866f
        val s = 0.5f
        val xs = floatArrayOf(-5f, 6f, 6f, -5f)
        val ys = floatArrayOf(-3f, -3f, 3f, 3f)
        val spans = spansOf(
            FloatArray(4) { xs[it] * c - ys[it] * s },
            FloatArray(4) { xs[it] * s + ys[it] * c },
        )

        assertTrue(spans.size > 6, "a turned box is taller than a square one: ${spans.size} rows")
        val widths = spans.map { it.third }
        val peak = widths.indexOf(widths.max())
        for (i in 1..peak) {
            assertTrue(widths[i] >= widths[i - 1], "width dipped on the way up at row $i: $widths")
        }
        for (i in peak + 1 until widths.size) {
            assertTrue(widths[i] <= widths[i - 1], "width rose again after the peak at row $i: $widths")
        }
    }

    /** A quad with no height is not half a quad; it is nothing. */
    @Test
    fun `a flat quad draws nothing`() {
        assertTrue(
            spansOf(floatArrayOf(-5f, 6f, 6f, -5f), floatArrayOf(2f, 2f, 2f, 2f)).isEmpty(),
            "a zero-height quad emitted a row",
        )
    }

    private fun spansOf(xs: FloatArray, ys: FloatArray): List<Triple<Float, Float, Float>> =
        buildList { quadSpans(xs, ys) { x, y, w -> add(Triple(x, y, w)) } }
}
