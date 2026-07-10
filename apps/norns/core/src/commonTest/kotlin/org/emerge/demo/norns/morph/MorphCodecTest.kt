package org.emerge.demo.norns.morph

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [MorphCodec] round-trips a genome (format → parse) preserving structure, params, and extra. */
class MorphCodecTest {

    private fun sample(): MorphNode {
        val body = MorphNode("body", scale = 0.82f)
        val head = MorphNode("head", ox = 0f, oy = 1.25f, scale = 1.85f).apply {
            children.add(MorphNode("crown", oy = 0.42f, scale = 0.82f))
            children.add(MorphNode("muzzle", ox = 0.86f, oy = -0.22f, scale = 0.5f).apply {
                children.add(MorphNode("nose", ox = 0.5f, oy = 0.02f, scale = 0.34f).also { it.extra["girth"] = 1.3f })
            })
            children.add(MorphNode("eye", ox = 0.55f, oy = 0.02f, scale = 0.66f, mirX = 1f))
        }
        body.children.add(head)
        body.children.add(MorphNode("leg", ox = 0.16f, oy = -0.95f, scale = 0.58f, sym = 3, mirX = 1f))
        return body
    }

    private fun sameTree(a: MorphNode, b: MorphNode): Boolean {
        fun close(x: Float, y: Float) = abs(x - y) < 1e-4f
        if (a.name != b.name) return false
        if (!close(a.ox, b.ox) || !close(a.oy, b.oy) || !close(a.scale, b.scale) ||
            a.sym != b.sym || !close(a.mirX, b.mirX) || !close(a.mirY, b.mirY)) return false
        if (a.extra.keys != b.extra.keys || a.extra.any { !close(it.value, b.extra[it.key]!!) }) return false
        if (a.children.size != b.children.size) return false
        return a.children.indices.all { sameTree(a.children[it], b.children[it]) }
    }

    @Test
    fun roundTripsAGenome() {
        val g = sample()
        val back = MorphCodec.parse(MorphCodec.format(g))
        assertTrue(sameTree(g, back), "format → parse preserves the genome")
    }

    @Test
    fun parsesHandWrittenText() {
        val g = MorphCodec.parse(
            """
            # a tiny critter
            body
              head oy=2 scale=1.5
                eye ox=0.5 mirX=1
            """.trimIndent(),
        )
        assertEquals("body", g.name)
        assertEquals(1, g.children.size)
        val head = g.children[0]
        assertEquals(2f, head.oy, 1e-4f)
        assertEquals(1, head.children.size)
        assertTrue(head.children[0].mirrored, "mirX=1 → bilaterally mirrored")
    }

    @Test
    fun emitsOnlyNonDefaultFields() {
        val text = MorphCodec.format(MorphNode("body"))   // all defaults
        assertEquals("body", text.trim(), "a default node serializes to just its name")
    }
}
