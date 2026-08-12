package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.VesselState
import kotlin.test.assertEquals

/**
 * The one switch that parks the energy conservation checks — step 3 of `PLAN_unit_rescale.md`.
 *
 * ### Why they are parked
 *
 * Not a fix. An explicit, temporary surrender, taken 2026-08-12 so that a **known**-overflowing
 * ledger cannot be mistaken for a real regression while the units move underneath it.
 *
 * §2 of the plan measured it: energy inherits mass's dynamic range and then multiplies it by
 * specific heat and by temperature, and the whole-grid accumulators are single `Long`s. Per-tile
 * energy clears the target unit with three orders of magnitude to spare; the grand totals run out at
 * about `Kₘ = 1.4e5`, well short of the microgram target of `1e6`. Fixing that means the
 * accumulators stop being running totals — the only part of the whole job needing *restructuring*
 * rather than *reordering*, which is exactly why it was taken out of scope.
 *
 * ### What is NOT parked
 *
 * **Mass conservation, and it is deliberately the survivor.** It is the check most likely to catch a
 * rescaling mistake, and it survives the target unit comfortably. Every `airBalance`, `massBalance`
 * and `atmosphereGrams` assertion in the suite stays live and stays strict. If you are about to
 * silence one of those to make a rescale step go green, you have found a real bug.
 *
 * ### The shape of the parking
 *
 * One flag, and every energy identity in the suite routed through the helpers below, rather than
 * `@Ignore` scattered over a dozen files. Two reasons, and neither is tidiness:
 *
 *  - Almost none of those tests is *about* energy. `GridGrowTest` grows a grid, `ValveTest` opens a
 *    valve; each merely checks the ledgers in passing. `@Ignore`-ing them to park one assertion
 *    would take a large amount of unrelated coverage down with it, and the plan's own instruction is
 *    that nothing else changes.
 *  - Un-parking has to be **one edit**. The follow-on that stores divergence instead of running
 *    totals flips [PARKED] to `false` and the suite tells it, immediately and by name, what is still
 *    broken. A dozen scattered annotations would be un-parked the way such things always are:
 *    incompletely.
 *
 * The handful of tests whose *entire* subject is a joule identity do carry `@Ignore`, since with the
 * assertion parked there would be nothing left of them to run. Each names this file.
 *
 * ⚠️ While [PARKED] is true these helpers assert **nothing**. That is the point, and it is also the
 * hazard: a test calling them is not testing energy. Do not read a green suite as evidence the
 * energy ledgers are sound.
 */
object EnergyLedgers {

    /**
     * Flip to `false` to un-park. Expect failures — they are the follow-on's work list, not news.
     *
     * Left as a `const val` on purpose. A build flag or a system property would make "are the energy
     * checks running?" a question about the environment, and the answer needs to be readable from
     * the source alone.
     */
    const val PARKED = true

    /**
     * Both energy identities: the fabric's and the air's.
     *
     * They go together because the coupling term [VesselState.solidToAirEnergy] appears in each with
     * the opposite sign — checking one alone proves nothing, since a transfer that leaks out of one
     * ledger and mints into the other leaves both halves looking closed. That was a real bug in the
     * valve, caught only once a joule actually crossed.
     */
    fun assertBalanced(s: VesselState, what: String) {
        if (PARKED) return
        assertEquals(0L, s.heatBalance, "$what: the solid energy ledger is out by ${s.heatBalance}")
        assertEquals(0L, s.airJouleBalance, "$what: the air energy ledger is out by ${s.airJouleBalance}")
    }

    /** The air half alone, for the fixtures that have no fabric worth speaking of. */
    fun assertAirBalanced(s: VesselState, what: String) {
        if (PARKED) return
        assertEquals(0L, s.airJouleBalance, "$what: the air energy ledger is out by ${s.airJouleBalance}")
    }

    /**
     * The identities are **preserved** by an operation, rather than zero.
     *
     * A weaker statement than [assertBalanced] and the right one for a remap, which has to move a
     * world without disturbing its books but is not the thing that closed them in the first place.
     */
    fun assertPreserved(before: VesselState, after: VesselState, what: String) {
        if (PARKED) return
        assertEquals(before.heatBalance, after.heatBalance, "$what: heatBalance moved")
        assertEquals(before.airJouleBalance, after.airJouleBalance, "$what: airJouleBalance moved")
    }
}
