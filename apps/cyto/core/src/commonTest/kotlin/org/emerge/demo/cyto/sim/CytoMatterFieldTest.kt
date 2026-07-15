package org.emerge.demo.cyto.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Standalone invariants for the refine-only quad-tree matter field: conservation through every op,
 *  determinism, and inertness (nothing but a cell ever moves matter). */
class CytoMatterFieldTest {
    private val A = SpeciesRegistry.id("r")
    private val AB = SpeciesRegistry.id("rg")

    private fun leafCount(f: CytoMatterField): Int { var n = 0; f.forEachLeaf { _, _, _, _ -> n++ }; return n }
    private fun digest(f: CytoMatterField): String {
        val sb = StringBuilder()
        f.forEachLeaf { x, y, sz, s ->
            sb.append(x).append(',').append(y).append(',').append(sz).append(':')
            for (i in 0 until s.size) sb.append(s.idAt(i)).append('=').append(s.countAt(i)).append(',')
            sb.append(';')
        }
        return sb.toString()
    }

    @Test fun splitConservesAtoms() {
        val f = CytoMatterField.seededUniform(10)
        val t0 = f.totalAtoms()
        f.openFootprint(5f, 5f, 0.6f); f.closeFootprint()   // forces splits to MAX_DEPTH
        assertTrue(leafCount(f) > 4, "footprint access should have refined the tile")
        assertEquals(t0, f.totalAtoms(), "split must conserve atoms")
    }

    @Test fun exchangeConservesAndReturnsDelta() {
        val f = CytoMatterField.seededUniform(10)
        val before = f.totalAtoms()
        val n = f.openFootprint(5f, 5f, 0.6f)
        assertTrue(n > 0)
        val delta = f.balance(A, cEff = 0, scaleFactor = 0f)   // cell wants 0 ⇒ leaves give delta
        f.closeFootprint()
        assertTrue(delta > 0, "cell with cEff=0 absorbs from a rich footprint")
        assertEquals(before - delta.toLong(), f.totalAtoms(), "grid total changes by exactly −delta")
    }

    @Test fun exchangeLeaksWhenCellRicher() {
        val f = CytoMatterField.seededUniform(10)
        val before = f.totalAtoms()
        f.openFootprint(5f, 5f, 0.6f)
        val n = f.openFootprint(5f, 5f, 0.6f)
        val delta = f.balance(A, cEff = 100 * n, scaleFactor = 0f)   // cell much richer than leaves ⇒ delta < 0 (leaks in)
        f.closeFootprint()
        assertTrue(delta < 0, "cell richer than footprint pushes matter in (negative delta)")
        assertEquals(before - delta.toLong(), f.totalAtoms(), "conserved both ways")
    }

    @Test fun depositConserves() {
        val f = CytoMatterField.seededUniform(10)
        val before = f.totalAtoms()
        f.deposit(-20f, 30f, 0.6f, A, amount = 1000)   // monomer ⇒ molecules == atoms
        assertEquals(before + 1000L, f.totalAtoms(), "deposit adds exactly the amount")
    }

    /** The field is INERT: with no cell touching it and no decay due, maintain must not move a single atom
     *  and must not coarsen the tree. The old field pooled unobserved regions back toward coarse leaves,
     *  which doubled as the world's only diffusion — but it could only ever fire where no cell was, so it
     *  was removed rather than kept as a bad approximation of diffusion. Refinement is now one-way, and
     *  matter stays exactly where it was last left until life moves it. */
    @Test fun maintainLeavesAnUnobservedFieldExactlyAsItWas() {
        val f = CytoMatterField.seededUniform(10)
        // Split symmetrically at the 4-tile corner (the origin), down to the finest depth.
        f.openFootprint(0f, 0f, 0.6f); f.closeFootprint()
        val split = leafCount(f); assertTrue(split > 4)
        val t0 = f.totalAtoms()
        val d0 = digest(f)
        repeat(520) { f.maintain(decayPeriod = Int.MAX_VALUE) }   // no decay due ⇒ maintain is a pure walk
        assertEquals(t0, f.totalAtoms(), "maintain conserves")
        assertEquals(split, leafCount(f), "the tree must never coarsen — refinement is one-way")
        assertEquals(d0, digest(f), "an unobserved field must not move a single atom")
    }

    @Test fun decayConservesAndAtomises() {
        val f = CytoMatterField.empty()
        f.deposit(0f, 0f, 0.6f, AB, amount = 4096)   // a pile of 'rg' molecules
        val t0 = f.totalAtoms()
        repeat(20) { f.maintain(decayPeriod = 2) }
        assertEquals(t0, f.totalAtoms(), "decay conserves atoms (rg → r + g)")
        var rg = 0L; var mono = 0L
        f.forEachLeaf { _, _, _, s -> rg += s.count(AB).toLong(); mono += s.count(A).toLong() }
        assertTrue(rg < 4096, "some 'rg' atomised")
        assertTrue(mono > 0, "monomers released")
    }

    @Test fun deterministic() {
        fun run(): CytoMatterField {
            val f = CytoMatterField.seededUniform(10)
            f.openFootprint(3f, 3f, 0.6f); f.balance(A, 4, 0f); f.closeFootprint()
            f.deposit(3f, 3f, 0.6f, AB, 500)
            f.openFootprint(-40f, 80f, 0.6f); f.balance(A, 0, 0f); f.closeFootprint()  // near a different tile
            f.maintain(4)
            return f
        }
        assertEquals(digest(run()), digest(run()), "identical op sequences produce identical fields")
    }

    /** The renderer's flat [MatterLeafSummary] must describe exactly the same leaves as a tree walk — same
     *  geometry, same per-channel atom totals — both before any tick and after maintain() has refilled it.
     *  A drift here silently miscolours or drops regions of the matter overlay. */
    private fun summaryDigest(f: CytoMatterField): String {
        val s = f.leafSummary
        val sb = StringBuilder()
        for (i in 0 until s.n) {
            sb.append(s.xs[i]).append(',').append(s.ys[i]).append(',').append(s.sizes[i]).append(':')
            sb.append(s.reds[i]).append('/').append(s.greens[i]).append('/').append(s.blues[i]).append(';')
        }
        return sb.toString()
    }

    /** The same digest, computed independently by walking the tree and tallying each leaf's store. */
    private fun walkDigest(f: CytoMatterField): String {
        val sb = StringBuilder()
        f.forEachLeaf { x, y, sz, store ->
            var r = 0L; var g = 0L; var b = 0L
            for (i in 0 until store.size) {
                val c = store.countAt(i); val id = store.idAt(i)
                r += c * SpeciesRegistry.atomsInChannel(id, 0)
                g += c * SpeciesRegistry.atomsInChannel(id, 1)
                b += c * SpeciesRegistry.atomsInChannel(id, 2)
            }
            sb.append(x).append(',').append(y).append(',').append(sz).append(':')
            sb.append(r).append('/').append(g).append('/').append(b).append(';')
        }
        return sb.toString()
    }

    @Test fun leafSummaryMatchesTreeWalkOnConstruction() {
        val f = CytoMatterField.seededUniform(10)
        assertEquals(walkDigest(f), summaryDigest(f), "summary must be built up-front (pre-tick)")
        assertEquals(leafCount(f), f.leafSummary.n)
    }

    @Test fun leafSummaryTracksMaintainThroughDecay() {
        val f = CytoMatterField.seededUniform(10)
        f.openFootprint(0f, 0f, 0.6f); f.closeFootprint()
        f.deposit(0f, 0f, 0.3f, AB, 500)
        assertTrue(leafCount(f) > 4)
        // Decay rewrites leaf stores under the summary every pass; check the published summary against an
        // independent walk each time.
        repeat(64) {
            f.maintain(decayPeriod = 4)
            assertEquals(walkDigest(f), summaryDigest(f), "summary drifted from the tree on pass $it")
        }
        var rg = 0L
        f.forEachLeaf { _, _, _, s -> rg += s.count(AB).toLong() }
        assertTrue(rg < 500, "decay should have atomised some 'rg' (so the summary was tracking real churn)")
    }
}
