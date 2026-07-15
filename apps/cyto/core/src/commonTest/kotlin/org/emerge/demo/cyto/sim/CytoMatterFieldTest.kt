package org.emerge.demo.cyto.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Standalone invariants for the dense matter field: conservation through every op, determinism, and
 *  inertness (nothing but a cell ever moves matter). */
class CytoMatterFieldTest {
    private val A = SpeciesRegistry.id("r")
    private val AB = SpeciesRegistry.id("rg")

    private fun occupiedTexels(f: CytoMatterField): Int { var n = 0; f.forEachTexel { _, _, _, _ -> n++ }; return n }
    private fun digest(f: CytoMatterField): String {
        val sb = StringBuilder()
        f.forEachTexel { x, y, sz, s ->
            sb.append(x).append(',').append(y).append(',').append(sz).append(':')
            for (i in 0 until s.size) sb.append(s.idAt(i)).append('=').append(s.countAt(i)).append(',')
            sb.append(';')
        }
        return sb.toString()
    }

    @Test fun seededUniformFillsEveryTexelAndConserves() {
        val f = CytoMatterField.seededUniform(10)
        val res = f.resolution
        assertEquals(res * res, occupiedTexels(f), "a uniform seed reaches every texel")
        // 3 monomers × 10 each, in every texel — one atom apiece.
        assertEquals(3L * 10 * res * res, f.totalAtoms())
    }

    @Test fun footprintCoversTexelsAndConserves() {
        val f = CytoMatterField.seededUniform(10)
        val t0 = f.totalAtoms()
        val n = f.openFootprint(5f, 5f, 0.6f); f.closeFootprint()
        assertTrue(n > 0, "a footprint should cover at least one texel")
        assertEquals(t0, f.totalAtoms(), "opening a footprint must not move atoms")
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

    /** The field is INERT: with no cell touching it and no decay due, maintain must not move a single atom.
     *  The old quad-tree pooled unobserved regions back toward coarse leaves, and the re-smear on re-split
     *  doubled as the world's only diffusion — but it could only ever fire where no cell was, so it was
     *  removed rather than kept as a bad approximation. Matter now stays exactly where it was last left
     *  until life moves it. */
    @Test fun maintainLeavesAnUnobservedFieldExactlyAsItWas() {
        val f = CytoMatterField.seededUniform(10)
        f.deposit(0f, 0f, 0.6f, AB, amount = 4096)   // a non-uniform pile to notice any drift
        val t0 = f.totalAtoms()
        val d0 = digest(f)
        repeat(520) { f.maintain(decayPeriod = Int.MAX_VALUE) }   // no decay due ⇒ maintain moves nothing
        assertEquals(t0, f.totalAtoms(), "maintain conserves")
        assertEquals(d0, digest(f), "an unobserved field must not move a single atom")
    }

    @Test fun decayConservesAndAtomises() {
        val f = CytoMatterField.empty()
        f.deposit(0f, 0f, 0.6f, AB, amount = 4096)   // a pile of 'rg' molecules
        val t0 = f.totalAtoms()
        repeat(20) { f.maintain(decayPeriod = 2) }
        assertEquals(t0, f.totalAtoms(), "decay conserves atoms (rg → r + g)")
        var rg = 0L; var mono = 0L
        f.forEachTexel { _, _, _, s -> rg += s.count(AB).toLong(); mono += s.count(A).toLong() }
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

    /** The renderer's per-channel read model must agree with an independent tally of the columns, both
     *  before any tick and after maintain() has refilled it. A drift here silently miscolours the overlay. */
    private fun channelDigest(f: CytoMatterField): String {
        val sb = StringBuilder()
        for (i in 0 until f.resolution * f.resolution) {
            val r = f.channelRed[i]; val g = f.channelGreen[i]; val b = f.channelBlue[i]
            if (r == 0 && g == 0 && b == 0) continue
            sb.append(i).append(':').append(r).append('/').append(g).append('/').append(b).append(';')
        }
        return sb.toString()
    }

    /** The same digest, computed independently by walking the texels and tallying each one's contents. */
    private fun walkDigest(f: CytoMatterField): String {
        val sb = StringBuilder()
        val t = CytoMatterField.SPAN / f.resolution
        f.forEachTexel { x, y, _, store ->
            var r = 0L; var g = 0L; var b = 0L
            for (i in 0 until store.size) {
                val c = store.countAt(i); val id = store.idAt(i)
                r += c * SpeciesRegistry.atomsInChannel(id, 0)
                g += c * SpeciesRegistry.atomsInChannel(id, 1)
                b += c * SpeciesRegistry.atomsInChannel(id, 2)
            }
            if (r == 0L && g == 0L && b == 0L) return@forEachTexel
            val ix = ((x + CytoMatterField.HALF) / t).toInt()
            val iy = ((y + CytoMatterField.HALF) / t).toInt()
            sb.append(iy * f.resolution + ix).append(':')
            sb.append(r).append('/').append(g).append('/').append(b).append(';')
        }
        return sb.toString()
    }

    @Test fun channelReadModelMatchesColumnsOnConstruction() {
        val f = CytoMatterField.seededUniform(10)
        assertEquals(walkDigest(f), channelDigest(f), "channels must be built up-front (pre-tick)")
    }

    @Test fun channelReadModelTracksMaintainThroughDecay() {
        val f = CytoMatterField.seededUniform(10)
        f.deposit(0f, 0f, 0.3f, AB, 500)
        // Decay rewrites the columns under the read model every pass; check it against an independent walk.
        repeat(64) {
            f.maintain(decayPeriod = 4)
            assertEquals(walkDigest(f), channelDigest(f), "channels drifted from the columns on pass $it")
        }
        var rg = 0L
        f.forEachTexel { _, _, _, s -> rg += s.count(AB).toLong() }
        assertTrue(rg < 500, "decay should have atomised some 'rg' (so the read model tracked real churn)")
    }
}
