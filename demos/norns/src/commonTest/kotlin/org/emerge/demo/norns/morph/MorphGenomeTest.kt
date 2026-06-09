package org.emerge.demo.norns.morph

import org.emerge.demo.norns.gene.GeneRng
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-verification harness for the morphology genome (ported to 2D from `evolutionism`): mutation is
 * deterministic + bounded, crossbreed averages homologous parts + inherits unique ones, the
 * reparent/deparent transform compensation round-trips, and genetic distance behaves (0 for identical,
 * symmetric, grows with difference). Renderer-agnostic — proves the genetics, not any pixels.
 */
class MorphGenomeTest {

    // a small "norn-ish" body: a body with a head (carrying an eye) and one mirrored leg
    private fun sample(): MorphNode {
        val body = MorphNode("body", oy = 0f, scale = 1f)
        val head = MorphNode("head", ox = 0f, oy = 2f, scale = 0.8f).apply {
            children.add(MorphNode("eye", ox = 0.3f, oy = 0.5f, scale = 0.3f))
        }
        val leg = MorphNode("leg", ox = 0.5f, oy = -1.5f, scale = 0.6f, mirX = 1f)
        body.children.add(head); body.children.add(leg)
        return body
    }

    private fun sameTree(a: MorphNode, b: MorphNode): Boolean {
        if (a.name != b.name) return false
        fun close(x: Float, y: Float) = abs(x - y) < 1e-4f
        if (!close(a.ox, b.ox) || !close(a.oy, b.oy) || !close(a.scale, b.scale) ||
            a.sym != b.sym || !close(a.mirX, b.mirX) || !close(a.mirY, b.mirY)) return false
        if (a.children.size != b.children.size) return false
        return a.children.indices.all { sameTree(a.children[it], b.children[it]) }
    }

    private fun allFinite(n: MorphNode): Boolean =
        n.ox.isFinite() && n.oy.isFinite() && n.scale.isFinite() && n.mirX.isFinite() && n.mirY.isFinite() &&
            n.children.all { allFinite(it) }

    @Test
    fun mutationIsDeterministicGivenASeed() {
        val a = sample(); MorphGenome.mutate(a, GeneRng(7), intensity = 0.3f)
        val b = sample(); MorphGenome.mutate(b, GeneRng(7), intensity = 0.3f)
        assertTrue(sameTree(a, b), "same seed → bit-identical mutation")
    }

    @Test
    fun mutationStaysFiniteAndUsuallyChangesTheGenome() {
        var changed = 0
        for (seed in 1L..40L) {
            val g = sample(); MorphGenome.mutate(g, GeneRng(seed), intensity = 0.4f, structuralOdds = 0.2f)
            assertTrue(allFinite(g), "no NaN/Inf after mutation (seed $seed)")
            assertTrue(g.children.size >= 1, "body keeps at least one child")
            if (!sameTree(g, sample())) changed++
        }
        assertTrue(changed > 30, "mutation actually perturbs most genomes ($changed/40)")
    }

    @Test
    fun crossbreedAveragesMatchedPartsAndInheritsUnmatched() {
        val mum = MorphNode("body").apply {
            children.add(MorphNode("head", ox = 0f, oy = 2f, scale = 1f))
            children.add(MorphNode("tail", ox = 0f, oy = -1f))   // only mum has a tail
        }
        val dad = MorphNode("body").apply {
            children.add(MorphNode("head", ox = 0f, oy = 4f, scale = 2f))
            children.add(MorphNode("wing", ox = 1f, oy = 0f))    // only dad has a wing
        }
        val kid = MorphGenome.crossbreed(mum, dad)
        val kHead = kid.children.first { it.name == "head" }
        assertEquals(3f, kHead.oy, 1e-4f, "matched head length is averaged (2,4 → 3)")
        assertEquals(1.5f, kHead.scale, 1e-4f, "matched head scale is averaged (1,2 → 1.5)")
        assertTrue(kid.children.any { it.name == "tail" }, "mum's unique tail inherited")
        assertTrue(kid.children.any { it.name == "wing" }, "dad's unique wing inherited")
    }

    @Test
    fun crossbreedToleratesAnInsertedSegment() {
        // dad inserted a "thigh" segment between body and the matching "foot"; both should still align
        val mum = MorphNode("body").apply { children.add(MorphNode("foot", ox = 0f, oy = -2f)) }
        val dad = MorphNode("body").apply {
            children.add(MorphNode("thigh", ox = 0f, oy = -1f).apply { children.add(MorphNode("foot", ox = 0f, oy = -1f)) })
        }
        val kid = MorphGenome.crossbreed(mum, dad)
        // the foot must survive the merge somewhere in the tree (aligned despite the topology shift)
        fun has(n: MorphNode, name: String): Boolean = n.name == name || n.children.any { has(it, name) }
        assertTrue(has(kid, "foot"), "foot aligns across the inserted segment")
    }

    @Test
    fun reparentDeparentRoundTrips() {
        val parent = MorphNode("p", ox = 1.3f, oy = 0.7f, scale = 1.5f)
        val child = MorphNode("c", ox = 0.4f, oy = -0.9f, scale = 0.8f)
        val ox0 = child.ox; val oy0 = child.oy; val s0 = child.scale
        MorphGenome.deparent(child, parent); MorphGenome.reparent(child, parent)
        assertEquals(ox0, child.ox, 1e-4f); assertEquals(oy0, child.oy, 1e-4f); assertEquals(s0, child.scale, 1e-4f)
    }

    @Test
    fun distanceIsZeroForIdenticalSymmetricAndGrows() {
        val a = sample(); val b = sample()
        assertEquals(0f, MorphGenome.distance(a, b), 1e-4f, "identical genomes are distance 0")
        val c = sample().apply { children.first { it.name == "head" }.oy = 6f }   // stretch the head out
        assertTrue(MorphGenome.distance(a, c) > 0f, "a differing genome has positive distance")
        assertEquals(MorphGenome.distance(a, c), MorphGenome.distance(c, a), 1e-4f, "distance is symmetric")
        val d = MorphNode("body")   // no limbs at all — should be much further
        assertTrue(MorphGenome.distance(a, d) > MorphGenome.distance(a, c), "missing whole limbs is further than a tweak")
    }

    @Test
    fun deepCloneIsIndependent() {
        val a = sample(); val clone = a.deepClone()
        clone.children.first().oy = 99f; clone.children.first().name = "zzz"
        assertTrue(sameTree(a, sample()), "mutating a clone does not touch the original")
    }
}
