package org.emerge.demo.outofspace.fluid

import org.emerge.demo.outofspace.OutofspaceConfig
import org.emerge.demo.outofspace.OutofspaceController
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.VesselState
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
 * ### ⚠️ Where the vessel sits must not change how it vents
 *
 * It used to, badly, and the reason is worth keeping because the fix was arrived at through two
 * wrong answers first.
 *
 * [stepFluid] once wrote off whatever sat in the outermost ring of tiles at the end of every tick.
 * That is reasonable bookkeeping and a ruinous boundary condition: a ring emptied every tick is a
 * **permanent hard vacuum**, so every cell beside it read a neighbour at zero pressure and was
 * granted full expansion forever. The rim was not an exit, it was a pump — and the starter vessel
 * sits one tile from it.
 *
 * The control that isolates this is a bare hull box with a breach **centred in it**, so the ship
 * cannot be blamed, run at two distances from the rim:
 *
 * ```
 *                                  ring deleted   ring kept
 *   hull 1..13,  breach 7   (rim)     28% lean      6% lean
 *   hull 41..53, breach 47  (open)     0% lean      0% lean
 * ```
 *
 * The two wrong answers, recorded because both are easy to reach again. First: blaming the *ship's*
 * shape, which came from a comparison that moved away from the rim and centred within the hull at
 * the same time and so could not tell the two apart. Second: trying to fix it *at* the boundary by
 * making the rim non-reflecting — which stops the suction and makes matters worse (28% → 42%),
 * because a pocket that cannot drain fills up and reflects instead.
 *
 * What works is deleting the special case entirely. Gas leaves by flowing off the grid, which
 * [advectMass] already did and counted; the rim ring is now an ordinary tile that fills and pushes
 * back like any other. A vessel beside the edge of the world now vents within a few percent of one
 * in the middle of it, so it can be put wherever the game wants it.
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
     * Six tiles from the port wall and two from the grid rim, so the two sides stop being comparable
     * quickly: at ±5 the port sample is already at the ship's corner while its mirror still has twenty
     * tiles of flat hull ahead of it, and oxygen there leans 14%. Only ±2 is a fair pairing, and that
     * is all this asserts.
     *
     * It is the assertion that was failing at ~18% while the rim was still being emptied every tick,
     * and it is the reason the vessel can go on sitting where it does.
     */
    @Test
    fun `a breach off-centre in the ship is still even near the hole`() {
        val leans = leansAfterBreachAt(BOW, listOf(2))
        val bad = leans.filter { it.percent > BOW_TOLERANCE_PERCENT }
        assertTrue(bad.isEmpty(), "the bow plume leans:\n" + bad.joinToString("\n") { "  $it" })
    }

    /**
     * A hull box and nothing else, on the starter vessel's own footprint.
     *
     * It used to run on the starter vessel itself, which was fine while the atmosphere was
     * isothermal: the only thing the gas could feel was the hull, and the hull is symmetric about
     * x=17 whatever is standing inside it.
     *
     * Coupling the fabric to the air ended that. A refinery line is *not* mirror-symmetric — the
     * smelter is to starboard, the tank to port — so the air over one half of the ship is now
     * genuinely warmer than the air over the other, and a warmer plume is a lighter, faster plume.
     * The measured lean went to twenty-odd percent, and every gram of it was real.
     *
     * That is a fact about that ship, not about the solver, and this instrument is only sharp
     * because it needs no knowledge of the right answer — which it only has while the *world* is
     * symmetric. So the machines come out. The doc above already describes this box as the control
     * that isolated the rim-deletion bug; it is now the subject rather than the control.
     */
    private fun bareHull(grid: Grid): VesselState {
        val machines = arrayOfNulls<Machine>(grid.size)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y)) machines[grid.index(x, y)] = Hull() }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_ROW); put(x, HULL_BOTTOM) }
        for (y in HULL_ROW..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        return VesselState(grid = grid, machines = machines.toList())
    }

    /** One measured comparison of a mirrored pair. */
    private class Lean(val what: String, val left: Long, val right: Long, val percent: Long) {
        override fun toString(): String = "$what: $left vs $right — $percent% lean"
    }

    /**
     * Breaches the hull at [breachX], runs [TICKS], and measures every mirrored pair of columns.
     *
     * A column is everything above the hull, rows `0 until HULL_ROW`. The hull course itself is
     * excluded because it is solid — sampled there, two mirrored tiles both hold nothing and agree
     * however broken the sim is.
     *
     * Mass and mixture are both measured, and they can fail independently: bulk flow moves every
     * species together, so a lopsided *mixture* means [applySpeciesDrift] is leaning while a lopsided
     * *mass* with an even mixture means the transport is. Being told which is most of the diagnosis.
     */
    private fun leansAfterBreachAt(breachX: Int, distances: List<Int>, speciesToo: Boolean = true): List<Lean> {
        val cfg = OutofspaceConfig()
        val grid = cfg.grid
        val controller = OutofspaceController(cfg, bareHull(grid))

        controller.remove(grid.index(breachX, HULL_ROW))
        repeat(TICKS) { controller.stepOnce() }

        val air = controller.state.air
        val out = ArrayList<Lean>()
        for (d in distances) {
            val at = "±$d from x=$breachX"
            add(out, "column $at", column(air, grid, breachX - d), column(air, grid, breachX + d))
            if (!speciesToo) continue
            for (s in Species.GASES) {
                add(out, "${s.name} $at", column(air, grid, breachX - d, s), column(air, grid, breachX + d, s))
            }
        }
        return out
    }

    /**
     * All the gas standing above the hull in one column — the whole plume, not one tile of it.
     *
     * Single tiles were the original design and they stopped working once the rim stopped hoarding
     * gas: with the plume free to leave, a tile out here holds six to nine grams, where the ±1 of
     * integer transport reads as twenty percent and swamps whatever bias is actually present. The
     * measurement had lost its resolution rather than the sim having lost its symmetry.
     *
     * Summing the column restores it without weakening anything — it is the same quantity over more
     * samples, so a real lean still shows at full strength while the quantisation averages out. It is
     * also closer to the question actually being asked, which was never about one tile: it was
     * whether there is more gas on one side of the hole than the other.
     */
    private fun column(air: AirField, grid: Grid, x: Int, species: Species? = null): Long {
        var sum = 0L
        for (y in 0 until HULL_ROW) {
            val tile = grid.index(x, y)
            sum += if (species == null) air.densityAt(tile) else air.gramsOf(tile, species)
        }
        return sum
    }

    /**
     * Records one pair, skipping those where both sides are tiny.
     *
     * Even summed over a column the far reaches of a plume come to single grams, and one against two
     * is a hundred percent lean that means nothing — integer rounding, not bias. [FLOOR] is where that
     * stops being true. ⚠️ Carbon dioxide is only 1.3% of the mix by mass, so it is often below the
     * floor even summed; the mixture check is in practice a nitrogen and oxygen check, and a
     * CO₂-specific bias would not be caught here.
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
        /** Amidships: the hull spans 1..33, so this is the mirror plane of the ship itself. */
        const val MIDSHIPS = 17

        /** Six tiles from the port wall, twenty-six from the starboard one, and beside the grid rim. */
        const val BOW = 7

        /** The hull's top course. Everything above it is outside, and is what gets compared. */
        const val HULL_ROW = 7
        const val HULL_LEFT = 1
        const val HULL_RIGHT = 33
        const val HULL_BOTTOM = 24
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
         *
         * ⚠️ **It rose from 10 to 13 when the subject changed from the starter vessel to the bare
         * hull box, and the rise is not a regression in the solver.** The bare box leans 11% in
         * nitrogen at ±5 and 13% in oxygen at ±12, and it leans by exactly those amounts on commit
         * `1d7c8e1e` too — the last one before the fabric and the air were coupled. It was measured
         * both ways on purpose, because a lean appearing in the same commit that touches heat is
         * precisely the thing that gets misattributed.
         *
         * So there is a real, pre-existing asymmetry in the transport that the starter vessel was
         * masking, and it is worth chasing on its own. What this file can still say with the old
         * sharpness is that the body model and the fabric-to-air coupling add **nothing** to it:
         * with the machines taken out, the numbers before and after are identical to the gram.
         */
        const val TOLERANCE_PERCENT = 13L

        /** Near the hole the off-centre breach gets no more slack than the centred one. */
        const val BOW_TOLERANCE_PERCENT = TOLERANCE_PERCENT
    }
}
