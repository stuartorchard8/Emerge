package org.emerge.demo.outofspace.fluid

import org.emerge.demo.outofspace.OutofspaceConfig
import org.emerge.demo.outofspace.OutofspaceController
import org.emerge.demo.outofspace.chem.Species
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A hole in a flat wall is a symmetric problem, so the plume it makes must be a symmetric answer.
 *
 * This is the sharpest instrument available for the venting model, and it is sharp precisely because
 * nothing about it depends on knowing the right answer. Whatever the gas does, it must do the same
 * thing to the left as to the right. Any lean is the *scheme* leaking through — a sweep order, an
 * edge convention, a rounding rule that treats `-1` differently from `+1` — and none of those are
 * physics.
 *
 * It catches a class of bug that conservation cannot. A ledger stays perfectly balanced while the
 * gas all goes left, and a plume looks plausible at a glance in almost any state; the eye is bad at
 * spotting a twenty percent lean. So this measures the lean.
 *
 * ### ⚠️ The mirror plane has to be a symmetry of the *world*, not just of the hole
 *
 * The obvious breach to test is one near the bow, and it is the wrong one, which took measuring to
 * discover. The starter vessel's hull runs from `x = 1` to `x = 33` inside a grid 96 wide, so it sits
 * hard against the left rim with sixty-odd tiles of space to its right. A breach at `x = 7` has the
 * vessel's own corner six tiles to its left and twenty-six to its right, and the grid rim — which
 * deletes whatever reaches it — is closer still. Gas venting left fills a short corner and backs up
 * against an absorber; gas venting right spreads thin over open space that slowly builds
 * back-pressure. Those are different problems, and the plume leans about eighteen percent because of
 * it.
 *
 * Measured across three breach columns at fifty ticks, density either side:
 *
 * ```
 *   breach x=7    ±2: 15% lean    ±5: 18% lean
 *   breach x=17   ±2:  0% lean    ±5:  1% lean
 *   breach x=27   ±2:  7% lean    ±5:  3% lean
 * ```
 *
 * Amidships the profile mirrors almost exactly — `24 31 35 33 32 30 24` on both sides of the jet.
 * The bulk solver is even-handed; the *world* is not. So the strict assertion lives at `x = 17`,
 * where the mirror plane is a real symmetry, and the bow case is kept alongside it as a recorded
 * number rather than deleted, because it is the one that will move if anybody changes the rim.
 *
 * ### What it caught on its first run
 *
 * Amidships, density and pressure mirrored to within one percent while **oxygen sat at three grams
 * against six** — a lopsided mixture under a symmetric mass distribution. That combination can only
 * be [applySpeciesDrift], because bulk flow moves every species together and cannot change a ratio.
 * The cause was that drift edited the mass field *in place* as it swept faces in index order, so a
 * tile's left face was always resolved before its right one; gas arriving from the left could move
 * on again within the same sweep and gas arriving from the right could not. Making it plan against a
 * snapshot and apply afterwards took that pair to five against four, which is one gram and therefore
 * nothing.
 *
 * Worth keeping in mind for whatever is added next: mass was conserved perfectly the entire time.
 * No ledger, invariant, or conservation test could have found it, and it had been there since drift
 * was written.
 */
class BreachSymmetryTest {

    @Test
    fun `a breach amidships vents symmetrically about its own column`() {
        val leans = leansAfterBreachAt(MIDSHIPS, listOf(2, 5))
        val bad = leans.filter { it.percent > TOLERANCE_PERCENT }
        assertTrue(bad.isEmpty(), "the plume leans:\n" + bad.joinToString("\n") { "  $it" })
    }

    /**
     * The bow breach, asserted loosely on purpose.
     *
     * Not a claim that eighteen percent is acceptable — it is a claim that eighteen percent is what a
     * breach six tiles from an absorbing rim currently does, and that a jump to fifty would mean
     * something broke. Centring the vessel in its grid, or giving the void an open boundary rather
     * than a deleting ring, should pull this toward the midships figure; if it ever does, tighten
     * this and say so.
     */
    @Test
    fun `a breach near the bow leans toward the near rim, by this much`() {
        val leans = leansAfterBreachAt(BOW, listOf(2, 5))
        val bad = leans.filter { it.percent > BOW_TOLERANCE_PERCENT }
        assertTrue(bad.isEmpty(), "the bow plume leans further than recorded:\n" + bad.joinToString("\n") { "  $it" })

        // And it really does lean — if this ever passes cleanly the confound has gone away and the
        // strict test above should be extended to cover the bow too.
        assertTrue(
            leans.any { it.percent > TOLERANCE_PERCENT },
            "the bow breach no longer leans; fold it into the midships test",
        )
    }

    /** One measured comparison of a mirrored pair. */
    private class Lean(val what: String, val left: Long, val right: Long, val percent: Long) {
        override fun toString(): String = "$what: $left vs $right — $percent% lean"
    }

    /**
     * Breaches the hull at [breachX], runs [TICKS], and measures every mirrored pair on [ROW].
     *
     * [ROW] is the first open row *above* the hull. Row 7 is the hull course itself — the breached
     * tile's own row — so a pair taken there would compare two solid tiles that both hold nothing and
     * would agree however broken the sim was.
     *
     * Mass and mixture are both measured, and they can fail independently: bulk flow moves every
     * species together, so a lopsided *mixture* means [applySpeciesDrift] is leaning while a lopsided
     * *mass* with an even mixture means the transport is. Being told which is most of the diagnosis.
     */
    private fun leansAfterBreachAt(breachX: Int, distances: List<Int>): List<Lean> {
        val cfg = OutofspaceConfig()
        val grid = cfg.grid
        val controller = OutofspaceController(cfg)

        controller.remove(grid.index(breachX, HULL_ROW))
        repeat(TICKS) { controller.stepOnce() }

        val air = controller.state.air
        val out = ArrayList<Lean>()
        for (d in distances) {
            val l = grid.index(breachX - d, ROW)
            val r = grid.index(breachX + d, ROW)
            val at = "±$d from x=$breachX"
            add(out, "density $at", air.densityAt(l), air.densityAt(r))
            add(out, "pressure $at", air.pressureAt(l), air.pressureAt(r))
            for (s in Species.GASES) add(out, "${s.name} $at", air.gramsOf(l, s), air.gramsOf(r, s))
        }
        return out
    }

    /**
     * Records one pair, skipping those where both sides are tiny.
     *
     * Out at the edge of a plume the values are single grams, and one gram against two is a hundred
     * percent lean that means nothing — that is integer rounding, not a bias. [FLOOR] is where that
     * stops being true. ⚠️ Carbon dioxide is only 1.3% of the mix by mass, so at plume densities it
     * is almost always below the floor; that is why the mixture check is really a nitrogen and oxygen
     * check, and why a CO₂-specific bias would not be caught here.
     */
    private fun add(into: MutableList<Lean>, what: String, left: Long, right: Long) {
        if (left < FLOOR && right < FLOOR) return
        val total = left + right
        if (total == 0L) return
        // A single gram is the quantum this sim counts in, so a one-gram difference is rounding
        // however large it looks as a percentage — five against four is eleven percent and means
        // nothing. ⚠️ The cost is that a systematic one-gram bias spread over many tiles would slip
        // through; if that is ever suspected, sum a row rather than lowering this.
        if (abs(left - right) <= GRAIN) return
        into.add(Lean(what, left, right, abs(left - right) * 100L / total))
    }

    private companion object {
        /** Amidships: the vessel's hull spans 1..33, so this is the one column with space either side. */
        const val MIDSHIPS = 17

        /** Six tiles from the vessel's corner and closer still to the deleting grid rim. */
        const val BOW = 7

        const val HULL_ROW = 7
        const val ROW = 6
        const val TICKS = 50

        /** Below this many grams a difference is rounding rather than bias. */
        const val FLOOR = 4L

        /** The smallest amount the sim counts in; a difference this size is never evidence. */
        const val GRAIN = 1L

        /**
         * How far off centre a plume may sit where the world is actually symmetric.
         *
         * ⚠️ A record of what the model achieves, not a specification. Tighten it when the model
         * improves; a rise means something regressed.
         */
        const val TOLERANCE_PERCENT = 10L

        /** What the bow breach currently does, held only loosely enough to catch a real break. */
        const val BOW_TOLERANCE_PERCENT = 30L
    }
}
