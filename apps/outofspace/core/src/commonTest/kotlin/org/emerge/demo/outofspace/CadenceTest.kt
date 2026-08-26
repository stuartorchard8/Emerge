package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.CHEM_OFFSET
import org.emerge.demo.outofspace.OutofspaceReducer.CHEM_PERIOD
import org.emerge.demo.outofspace.OutofspaceReducer.FLUID_OFFSET
import org.emerge.demo.outofspace.OutofspaceReducer.FLUID_PERIOD
import org.emerge.demo.outofspace.OutofspaceReducer.HEAT_OFFSET
import org.emerge.demo.outofspace.OutofspaceReducer.HEAT_PERIOD
import org.emerge.demo.outofspace.OutofspaceReducer.MACHINE_OFFSET
import org.emerge.demo.outofspace.OutofspaceReducer.MACHINE_PERIOD
import org.emerge.demo.outofspace.OutofspaceReducer.PRESSURE_OFFSET
import org.emerge.demo.outofspace.OutofspaceReducer.PRESSURE_PERIOD
import org.emerge.demo.outofspace.OutofspaceReducer.PUMP_OFFSET
import org.emerge.demo.outofspace.OutofspaceReducer.PUMP_PERIOD
import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_OFFSET
import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.world.Cadence
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The clock every animation in the game is hung off.
 *
 * This exists because the thing it replaces failed **silently**. The renderer used to work out how
 * far through a subsystem's period the frame was by taking a wrapped global clock modulo an
 * imported constant, and that is right exactly when the subsystem fires on tick zero of a period
 * that divides the tick rate. When the subsystems were given staggered offsets to flatten the tick
 * cost, both halves of that stopped being true and every packet on every belt started snapping
 * forward on arrival and teleporting backwards mid-slide — with a green suite, because nothing in
 * it could see a frame drawn between two ticks.
 *
 * So these are about the property, not the arithmetic: for **every schedule the reducer declares**,
 * progress starts at zero when the pass runs, rises without a step in it, and reaches one exactly
 * as the pass runs again. A schedule that cannot satisfy that cannot be animated smoothly, and this
 * is where we find that out rather than on screen.
 */
class CadenceTest {

    /** Every (period, offset) pair the reducer schedules a subsystem on. */
    private val schedules = listOf(
        Triple("pump", PUMP_PERIOD, PUMP_OFFSET),
        Triple("heat", HEAT_PERIOD, HEAT_OFFSET),
        Triple("chem", CHEM_PERIOD, CHEM_OFFSET),
        Triple("pressure", PRESSURE_PERIOD, PRESSURE_OFFSET),
        Triple("fluid", FLUID_PERIOD, FLUID_OFFSET),
        Triple("machine", MACHINE_PERIOD, MACHINE_OFFSET),
        Triple("rail", RAIL_PERIOD, RAIL_OFFSET),
    )

    /** The reducer's own rule, restated: a pass of [period] whose turn is [offset] fires on these. */
    private fun firings(period: Int, offset: Int, ticks: Int): List<Long> =
        (0L until ticks).filter { it % period == offset.toLong() }

    // ── The property, for every schedule ──────────────────────────────────────

    @Test
    fun `progress is zero on the tick the pass runs`() {
        for ((name, period, offset) in schedules) {
            for (fired in firings(period, offset, 4 * period)) {
                val c = Cadence(fired, period)
                assertEquals(
                    0f, c.progress(fired.toDouble()),
                    "$name fired at $fired and its animation did not start there",
                )
            }
        }
    }

    @Test
    fun `progress rises to exactly one as the pass runs again`() {
        for ((name, period, offset) in schedules) {
            for (fired in firings(period, offset, 4 * period)) {
                val c = Cadence(fired, period)
                val next = fired + period
                assertTrue(
                    c.progress(next - 1e-6) > 0.999f,
                    "$name had not arrived a hair before the next pass at $next",
                )
                assertEquals(
                    1f, c.progress(next.toDouble()),
                    "$name was still in flight when the pass that replaces it ran, at $next",
                )
            }
        }
    }

    /**
     * The seam between one span and the next: the old cadence must be at 1 exactly where the new one
     * is at 0. That is the whole of "smooth" — the packet finishes arriving at the tile it is then
     * drawn departing from — and it is the property the wrapped-modulo version broke, twice per
     * period, by a fifth of a tile and then by a whole one.
     */
    @Test
    fun `spans hand over without a jump`() {
        for ((name, period, offset) in schedules) {
            val fired = firings(period, offset, 4 * period)
            for (i in 0 until fired.size - 1) {
                val ending = Cadence(fired[i], period)
                val starting = Cadence(fired[i + 1], period)
                val seam = fired[i + 1].toDouble()
                assertEquals(1f, ending.progress(seam), "$name did not finish its span at $seam")
                assertEquals(0f, starting.progress(seam), "$name did not start its span at $seam")
            }
        }
    }

    @Test
    fun `progress never steps within a span`() {
        // A sixteenth of a tick is finer than any frame at any refresh rate the sim runs under, so a
        // step this walk cannot see is a step nothing can see.
        val step = 1.0 / 16.0
        for ((name, period, offset) in schedules) {
            val fired = firings(period, offset, 2 * period).last()
            val c = Cadence(fired, period)
            var previous = c.progress(fired.toDouble())
            var t = fired.toDouble()
            while (t <= fired + period) {
                val now = c.progress(t)
                assertTrue(now >= previous, "$name went backwards at $t: $previous then $now")
                assertTrue(
                    now - previous <= (step / period).toFloat() + 1e-5f,
                    "$name jumped from $previous to $now over a sixteenth of a tick at $t",
                )
                previous = now
                t += step
            }
        }
    }

    // ── The regression, stated in its own terms ───────────────────────────────

    /**
     * The bug, named. `(simTime % RAIL_PERIOD) / RAIL_PERIOD` reads 6/32 at the instant the rail
     * pass fires, because the pass fires at tick 6 of its 32 and that expression assumes zero.
     */
    @Test
    fun `the rail does not start its slide part-way along`() {
        val fired = RAIL_OFFSET.toLong()
        val wrapped = (fired % RAIL_PERIOD).toFloat() / RAIL_PERIOD
        assertTrue(
            abs(wrapped) > 0.1f,
            "this test is pointless unless RAIL_OFFSET is non-zero — it is $RAIL_OFFSET",
        )
        assertEquals(
            0f, Cadence(fired, RAIL_PERIOD).progress(fired.toDouble()),
            "a packet is at the tile it left, not $wrapped of the way off it",
        )
    }

    // ── Edges ─────────────────────────────────────────────────────────────────

    @Test
    fun `a settled world is finished at every time`() {
        for (t in listOf(0.0, 1.0, 1e9, Double.POSITIVE_INFINITY)) {
            assertEquals(1f, Cadence.SETTLED.progress(t), "settled but animating at $t")
        }
    }

    /**
     * A dropped tick — the spiral-of-death guard at work — leaves the clock past the end of a span.
     * Clamped, the animation settles where it was going. Unclamped it sails past and is yanked back,
     * which is the rubber-band this whole mechanism exists to stop.
     */
    @Test
    fun `a late frame settles rather than overshooting`() {
        val c = Cadence(100L, RAIL_PERIOD)
        assertEquals(1f, c.progress(100.0 + RAIL_PERIOD * 3), "overshot a span it had already finished")
        assertEquals(0f, c.progress(99.0), "animated a fact that had not been written yet")
    }

    /**
     * The clock does not wrap, so a session long enough to matter must still land on whole numbers.
     * `Float` would not: its mantissa runs out around three days of play at 64 ticks a second, and
     * the failure is a progress that quantises and then sticks.
     */
    @Test
    fun `the clock is still exact after a very long session`() {
        val fired = 64L * 60 * 60 * 24 * 30            // thirty days
        val c = Cadence(fired, RAIL_PERIOD)
        assertEquals(0f, c.progress(fired.toDouble()))
        assertEquals(0.5f, c.progress(fired + RAIL_PERIOD / 2.0))
        assertEquals(1f, c.progress((fired + RAIL_PERIOD).toDouble()))
    }
}
