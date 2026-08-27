package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.VesselState
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * The momentum identity, asserted the way it is actually true.
 *
 * `VesselState.momentumBalanceX` is **exactly zero on a ship that is not turning**, and both callers
 * of this used to say so with `assertEquals(0L, …)`. That is the right assertion and it is worth
 * keeping in that case; what it cannot survive is a rotation.
 *
 * ### Why a turning ship cannot close it to the unit
 *
 * The gas's momentum is stored per-edge on the grid — the ship's own axes — and turned into the
 * world to be compared against the ship's, which is world-frame. Two things follow, and only one of
 * them was a bug:
 *
 *  1. **The turn itself moves momentum, and nobody was charged.** Fixed, and it was the whole of the
 *     failure this file was written for. Every store is turned where it is booked; unturned, the balance
 *     walked monotonically to 359 by tick 116 on a starter vessel and to billions on a long run.
 *  2. **The integer turn is not exactly linear.** `R(a) + R(b)` and `R(a + b)` differ by a unit or
 *     two, because each `R` rounds. The ship is charged the impulse the gas gave it, turned, while
 *     the gas is read as one turned total, so the two sides disagree by that non-linearity every
 *     tick the ship is turned. It is exactly zero while `ang` is zero, because `R(0)` is the
 *     identity and the identity is perfectly linear.
 *
 * (2) is unbiased since `rotScale` rounds to nearest, so it random-walks rather than drifts.
 * Measured on a starter vessel left to spin for 1500 ticks: worst `|balance|` of **38**, still
 * wandering rather than growing, against a frame-turn term of 3.2e9 over the same run. [SLOP] is
 * that measurement with room to spare — wide enough not to flake, and many orders below the size of
 * anything the ledger exists to catch, which announces itself in the millions and keeps climbing.
 */
object MomentumLedger {

    /** See the note above: the measured worst is 38 over 1500 spinning ticks. */
    const val SLOP = 256L

    fun assertBalanced(s: VesselState, what: String) {
        assertBalanced(s.momentumBalanceX, s, "$what: x")
        assertBalanced(s.momentumBalanceY, s, "$what: y")
    }

    private fun assertBalanced(balance: Long, s: VesselState, what: String) {
        // A ship pointing along the grid has no excuse: the turn is the identity, so the books close
        // to the unit and the assertion stays as strong as it ever was.
        if (s.ang.raw == 0) {
            assertTrue(
                balance == 0L,
                "$what: the ledger is out by $balance on a vessel that is not turned, where the " +
                    "frame conversion is exact — this is momentum, not rounding",
            )
            return
        }
        assertTrue(
            abs(balance) <= SLOP,
            "$what: the ledger is out by $balance, past the $SLOP the integer turn can account " +
                "for at ang=${s.ang.raw} — see MomentumLedger. Every store in the identity is " +
                "turned where it is booked, so a break here is momentum and not a frame.",
        )
    }
}
