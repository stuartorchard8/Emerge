package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.Frac
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a cell sheds degraded biomass. The invariant: **a cell drops matter exactly where it can pick matter
 * up** — the deposit disc is the same centre and radius as the [passiveEnvExchange] footprint, so shed matter
 * is always still within reach.
 *
 * This replaced `9b7ab254`, which deposited at the touching cell nearest the centroid of all touching cells
 * (to keep shed matter "in the body" for colonies). That offset the deposit by up to a cell diameter while
 * still using the DEGRADING cell's radius, so the two discs only partially overlapped and a cell could shed
 * matter it was then unable to reclaim.
 */
class CytoDegradeDepositTest {
    private val AB = SpeciesRegistry.id("rg")

    /** A cell carrying plenty of `rg` biomass and enough accrued wear to break a bond this tick. */
    private fun shedder(cx: Float, cy: Float, radius: Frac, touching: Int): CellWork {
        val biomass = MoleculeStore().apply { add(AB, 40_000) }
        val work = CellWork(
            cytoplasm = MoleculeStore(),
            biomass = biomass,
            logicalRadius = radius,
            type = CellType.Collector,
            genome = emptyList(),
            quanta = 0,
            touchCount = touching,
            // Well past the threshold, so the cell sheds MORE molecules than its disc has texels. Otherwise
            // `deposit` hands 1 each to the first `amount` texels and 0 to the rest — correct, but it would
            // make "the shed disc IS the reachable disc" untestable (a 3-molecule shed can only mark 3).
            wear = CytoTuning.DEGRADE_PERIOD * 200,
            gridIndex = -1,
            connectionDamage = mutableMapOf(),
        )
        work.cx = cx
        work.cy = cy
        work.weldedDegree = 0   // no maintenance bonus ⇒ wear accrues at full rate
        return work
    }

    /** The texels a cell exchanges over at [radius] — the ground truth the deposit is compared against.
     *
     *  [radius] must be the cell's radius as of the SHED, not after: `finishCompute` runs `degrade` (which
     *  stages the deposit) and only then relaxes `logicalRadius` toward the new, smaller biomass baseline.
     *  The deposit therefore uses the radius the cell had while it was still that size — the same one this
     *  tick's `passiveEnvExchange` footprint used — which is the correct pairing. */
    private fun reachable(f: CytoMatterField, w: CellWork, radius: Frac): Set<Int> {
        val out = mutableSetOf<Int>()
        f.forEachFootprintTexel(w.cx, w.cy, radius.toFloat()) { out.add(it) }
        return out
    }

    private fun texelsHolding(f: CytoMatterField, sp: Int): Set<Int> {
        val col = f.columnOrNull(sp) ?: return emptySet()
        return col.indices.filter { col[it] > 0 }.toSet()
    }

    @Test fun shedBiomassLandsExactlyWhereTheCellCanReach() {
        for (radius in listOf(CytoTuning.MIN_RADIUS, Frac(1, 2), Frac(1, 1))) {
            val f = CytoMatterField.empty()
            val w = shedder(3.3f, -7.1f, radius, touching = 0)
            val shedRadius = w.logicalRadius            // captured pre-relax; see [reachable]
            CytoBiologyCore.finishCompute(w)
            assertTrue(w.degradeDepositCount > 0, "radius $radius: the cell should have shed something")
            assertEquals(shedRadius.toFloat(), w.degradeDepositRadius, "the deposit must use the shed-time radius")
            CytoBiologyCore.applyDegradeDeposit(w, f)

            val shed = texelsHolding(f, w.degradeDepositTargetId)
            val canReach = reachable(f, w, shedRadius)
            assertTrue(shed.isNotEmpty(), "radius $radius: shed matter must land somewhere")
            assertEquals(canReach, shed, "radius $radius: the shed disc must BE the reachable disc")
        }
    }

    /** The behaviour `9b7ab254` introduced and this replaces: touching cells must no longer drag the deposit
     *  off the shedder. Nothing about a cell's neighbours may move where its own matter lands. */
    @Test fun touchingNeighboursDoNotMoveTheDeposit() {
        val alone = CytoMatterField.empty()
        val a = shedder(3.3f, -7.1f, Frac(1, 2), touching = 0)
        CytoBiologyCore.finishCompute(a)
        CytoBiologyCore.applyDegradeDeposit(a, alone)

        val crowded = CytoMatterField.empty()
        val b = shedder(3.3f, -7.1f, Frac(1, 2), touching = 4)
        b.touchingIds.addAll(listOf(EntityId(11), EntityId(12), EntityId(13), EntityId(14)))
        CytoBiologyCore.finishCompute(b)
        CytoBiologyCore.applyDegradeDeposit(b, crowded)

        assertEquals(a.degradeDepositX, b.degradeDepositX, "neighbours must not shift the deposit in x")
        assertEquals(a.degradeDepositY, b.degradeDepositY, "neighbours must not shift the deposit in y")
        assertEquals(texelsHolding(alone, a.degradeDepositTargetId), texelsHolding(crowded, b.degradeDepositTargetId))
    }

    /** "Roughly even" — `deposit` hands out ⌊amount/n⌋ each and the ±1 remainder to the first texels in
     *  index order, so no texel may differ from another by more than a single molecule. */
    @Test fun shedBiomassSpreadsEvenlyAcrossTheFootprint() {
        val f = CytoMatterField.empty()
        val w = shedder(3.3f, -7.1f, Frac(1, 1), touching = 0)
        CytoBiologyCore.finishCompute(w)
        CytoBiologyCore.applyDegradeDeposit(w, f)

        val col = f.columnOrNull(w.degradeDepositTargetId)!!
        val counts = reachable(f, w, Frac(1, 1)).map { col[it] }
        assertTrue(counts.size > 1, "want a multi-texel footprint to have anything to spread")
        assertTrue(counts.max() - counts.min() <= 1, "uneven spread: ${counts.min()}..${counts.max()}")
    }

    /** A cell must never touch matter outside its own visible boundary. Metabolic size is emergent and
     *  unbounded, but PHYSICAL size is capped at [CytoTuning.MAX_COLLISION_RADIUS] — the collider, the weld
     *  radius, the render radius, and the matter footprint all clamp to it.
     *
     *  Before 2026-07-16 the footprint didn't: it passed `logicalRadius` raw, bounded only by
     *  `CytoMatterField.MAX_DISC_RADIUS` (4.0) — 4× the cap, i.e. **16× the area** — so a hoarding cell
     *  rendered 1.0 wide while feeding from a 4.0 disc. */
    @Test fun aGiantCellCannotShedBeyondItsPhysicalRadius() {
        val f = CytoMatterField.empty()
        val giant = shedder(3.3f, -7.1f, Frac(4, 1), touching = 0)   // metabolically huge, 4× the cap
        assertTrue(giant.logicalRadius > CytoTuning.MAX_COLLISION_RADIUS, "want an oversized cell to clamp")
        CytoBiologyCore.finishCompute(giant)
        CytoBiologyCore.applyDegradeDeposit(giant, f)

        assertEquals(
            CytoTuning.MAX_COLLISION_RADIUS.toFloat(), giant.degradeDepositRadius,
            "an oversized cell's deposit disc must clamp to the physical radius",
        )
        // …and the matter really does stay inside the capped disc, not merely the staged number.
        val capped = reachable(f, giant, CytoTuning.MAX_COLLISION_RADIUS)
        assertEquals(capped, texelsHolding(f, giant.degradeDepositTargetId), "shed must stay inside the collider")

        // The uncapped disc it used to reach is strictly larger — this is the regression being pinned.
        val uncapped = reachable(f, giant, Frac(4, 1))
        assertTrue(uncapped.size > capped.size * 4, "sanity: the old reach was vastly bigger (${uncapped.size} vs ${capped.size})")
    }

    /** A normal-sized cell is below the cap, so the clamp must not touch it. */
    @Test fun anOrdinaryCellIsUnaffectedByTheCap() {
        val f = CytoMatterField.empty()
        val w = shedder(3.3f, -7.1f, Frac(1, 2), touching = 0)
        CytoBiologyCore.finishCompute(w)
        assertEquals(Frac(1, 2).toFloat(), w.degradeDepositRadius, "a sub-cap cell keeps its own radius")
        CytoBiologyCore.applyDegradeDeposit(w, f)
        assertEquals(reachable(f, w, Frac(1, 2)), texelsHolding(f, w.degradeDepositTargetId))
    }

    /** Conservation across the shed: what biomass loses, the field gains, exactly. */
    @Test fun sheddingConservesAtoms() {
        val f = CytoMatterField.empty()
        val w = shedder(3.3f, -7.1f, Frac(1, 2), touching = 0)
        val before = w.biomass.count(AB)
        CytoBiologyCore.finishCompute(w)
        CytoBiologyCore.applyDegradeDeposit(w, f)
        val shedCount = before - w.biomass.count(AB)
        assertTrue(shedCount > 0)
        assertEquals(shedCount.toLong(), f.totalAtoms() / SpeciesRegistry.atomCount(w.degradeDepositTargetId))
    }
}
