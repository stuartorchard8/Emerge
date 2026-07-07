package org.emerge.demo.cyto.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Standalone invariants for the adaptive quad-tree matter field (QUADTREE.md): conservation through every
 *  op, determinism, and progressive collapse — validated in isolation before integration. */
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
        f.openFootprint(5f, 5f, 0.6f, 1); f.closeFootprint()   // forces splits to MAX_DEPTH
        assertTrue(leafCount(f) > 4, "footprint access should have refined the tile")
        assertEquals(t0, f.totalAtoms(), "split must conserve atoms")
    }

    @Test fun exchangeConservesAndReturnsDelta() {
        val f = CytoMatterField.seededUniform(10)
        val before = f.totalAtoms()
        val n = f.openFootprint(5f, 5f, 0.6f, 1)
        assertTrue(n > 0)
        val delta = f.balance(A, cEff = 0, scaleFactor = 0f)   // cell wants 0 ⇒ each leaf (r=10) gives 5 ⇒ delta = 5·n
        f.closeFootprint()
        assertTrue(delta > 0, "cell with cEff=0 absorbs from a rich footprint")
        assertEquals(before - delta.toLong(), f.totalAtoms(), "grid total changes by exactly −delta")
    }

    @Test fun exchangeLeaksWhenCellRicher() {
        val f = CytoMatterField.seededUniform(10)
        val before = f.totalAtoms()
        f.openFootprint(5f, 5f, 0.6f, 1)
        val n = f.openFootprint(5f, 5f, 0.6f, 1)
        val delta = f.balance(A, cEff = 100 * n, scaleFactor = 0f)   // cell much richer (bucket=100 vs leaf 10) ⇒ delta < 0 (leaks in)
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

    @Test fun maintainConservesAndProgressivelyCollapses() {
        val f = CytoMatterField.seededUniform(10)
        // Split symmetrically at the 4-tile corner (the origin) at tick 1, down to the finest depth.
        f.openFootprint(0f, 0f, 0.6f, 1); f.closeFootprint()
        val split = leafCount(f); assertTrue(split > 4)
        val t0 = f.totalAtoms()
        // The collapse delay DOUBLES per layer above the finest (base = 1 here for speed): a region twice as
        // coarse takes twice as long to pool, so matter disperses at a constant speed. Step tick-by-tick and
        // record the ticks where the leaf count drops (one layer of the tree collapsing) — the gaps double.
        val collapseTicks = ArrayList<Int>()
        var prev = split
        for (tk in 2..520) {
            f.maintain(tk, collapseDelay = 1, decayPeriod = Int.MAX_VALUE)   // no decay, just collapse
            val now = leafCount(f)
            if (now < prev) collapseTicks.add(tk)
            prev = now
        }
        assertEquals(t0, f.totalAtoms(), "maintain conserves")
        assertTrue(leafCount(f) <= CytoMatterField.BASE_RES * CytoMatterField.BASE_RES, "unobserved region fully collapses back to tile leaves")
        assertTrue(collapseTicks.size >= 3, "collapse is progressive (one layer at a time), got ${collapseTicks.size}")
        // Each successive layer waits twice as long as the one below: the gaps between collapse events double.
        for (i in 2 until collapseTicks.size) {
            val prevGap = collapseTicks[i - 1] - collapseTicks[i - 2]
            val gap = collapseTicks[i] - collapseTicks[i - 1]
            assertEquals(prevGap * 2, gap, "layer $i should take twice as long as the one below (twice as far ⇒ twice as long)")
        }
    }

    @Test fun decayConservesAndAtomises() {
        val f = CytoMatterField.empty()
        f.deposit(0f, 0f, 0.6f, AB, amount = 4096)   // a pile of 'rg' molecules
        val t0 = f.totalAtoms()
        repeat(20) { f.maintain(2 + it, collapseDelay = Int.MAX_VALUE, decayPeriod = 2) }  // decay, no collapse
        assertEquals(t0, f.totalAtoms(), "decay conserves atoms (rg → r + g)")
        var rg = 0L; var mono = 0L
        f.forEachLeaf { _, _, _, s -> rg += s.count(AB).toLong(); mono += s.count(A).toLong() }
        assertTrue(rg < 4096, "some 'rg' atomised")
        assertTrue(mono > 0, "monomers released")
    }

    @Test fun deterministic() {
        fun run(): CytoMatterField {
            val f = CytoMatterField.seededUniform(10)
            f.openFootprint(3f, 3f, 0.6f, 1); f.balance(A, 4, 0f); f.closeFootprint()
            f.deposit(3f, 3f, 0.6f, AB, 500)
            f.openFootprint(-40f, 80f, 0.6f, 3); f.balance(A, 0, 0f); f.closeFootprint()  // near a different tile
            f.maintain(70, 64, 4)
            return f
        }
        assertEquals(digest(run()), digest(run()), "identical op sequences produce identical fields")
    }
}
