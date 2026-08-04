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
 * This took two wrong answers to pin down, and the wrong answers are worth keeping because both are
 * easy to reach again.
 *
 * The vessel used to be laid out from `x = 1` in a grid 96 wide, so its port wall sat one tile from
 * the edge of the world. That edge is not scenery — [stepFluid] writes off whatever reaches the
 * outermost ring, so it is a hard vacuum that never fills, and a cell beside it is granted full
 * expansion every tick forever. The plume leaned about eighteen percent toward it.
 *
 * The first diagnosis was that the *ship* was lopsided about the breach, which was plausible and
 * wrong. The control that settles it is a bare hull box with a **centred** breach, run at two
 * distances from the rim — same ship, same hole, only the position changed:
 *
 * ```
 *   hull 1..13,  breach 7,  one tile from the rim    ±5: 28% lean
 *   hull 41..53, breach 47, forty tiles from it      ±5:  0% lean
 * ```
 *
 * So it was the boundary, and the solver was even-handed all along. The second wrong answer was to
 * try to fix it *at* the boundary by making the rim non-reflecting: that stops the suction and makes
 * matters worse (28% → 42%), because a pocket that cannot drain simply fills up instead. Nothing
 * done to a boundary condition can conjure up somewhere for gas to go. The vessel needed room, so
 * `starterVessel` now centres itself and the hull sits at 31..63 with about thirty tiles either side.
 *
 * Which leaves the honest residual: a breach *off-centre in the ship* still leans at distance,
 * because far enough out one sample is beside a hull and its mirror is past the end of one. That is
 * the ship's shape and not the solver's, and it is what [BOW] records.
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
        val leans = leansAfterBreachAt(MIDSHIPS, listOf(2, 5, 8, 12))
        val bad = leans.filter { it.percent > TOLERANCE_PERCENT }
        assertTrue(bad.isEmpty(), "the plume leans:\n" + bad.joinToString("\n") { "  $it" })
    }

    /**
     * A breach off-centre in the ship, held only near the hole.
     *
     * Six tiles from the port wall and twenty-six from the starboard one, so the two sides stop being
     * comparable quickly: at ±5 the port sample is already in the corner while its mirror still has
     * twenty tiles of flat hull ahead of it. Only ±2 is a fair pairing, and that is all this asserts.
     *
     * It also asserts **bulk only**. Oxygen out here is four to eight grams a tile, where a two-gram
     * swing reads as thirty percent and means very little; the mixture check needs the midships case,
     * where the geometry is exact and the comparison can be trusted. Density and pressure are the
     * larger, steadier numbers, and they are the ones that moved when the vessel was off-centre —
     * this assertion failed at eighteen percent before it was centred, and passes now.
     */
    @Test
    fun `a breach off-centre in the ship is still even near the hole`() {
        val leans = leansAfterBreachAt(BOW, listOf(2), speciesToo = false)
        val bad = leans.filter { it.percent > BOW_TOLERANCE_PERCENT }
        assertTrue(bad.isEmpty(), "the bow plume leans:\n" + bad.joinToString("\n") { "  $it" })
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
    private fun leansAfterBreachAt(breachX: Int, distances: List<Int>, speciesToo: Boolean = true): List<Lean> {
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
            if (speciesToo) for (s in Species.GASES) add(out, "${s.name} $at", air.gramsOf(l, s), air.gramsOf(r, s))
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
        /** Amidships: the hull spans 31..63, so this is the mirror plane of the ship itself. */
        const val MIDSHIPS = 47

        /** Six tiles from the port wall and twenty-six from the starboard one. */
        const val BOW = 37

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

        /** Near the hole the off-centre breach gets no more slack than the centred one. */
        const val BOW_TOLERANCE_PERCENT = TOLERANCE_PERCENT
    }
}
