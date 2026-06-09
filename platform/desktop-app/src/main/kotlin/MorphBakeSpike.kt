package org.emerge.desktop

import org.emerge.demo.norns.gene.GeneRng
import org.emerge.demo.norns.morph.MorphGenome
import org.emerge.demo.norns.morph.MorphNode
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.stream.IntStream
import javax.imageio.ImageIO
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * THROWAWAY SPIKE — does **genetic variation** of the SDF creature look appealing? The side-profile bake
 * ([CreatureBakeSpike]) settled the *look*; this settles the *genome → look* binding by driving the form
 * from a real [MorphNode] tree and showing a base creature, mutants, and a crossbreed of two parents.
 *
 * Binding (uses the genome's native fields, so mutation + crossbreed both produce real change):
 *  - the chain's forward-kinematics places each node in the **profile plane** (x = fore/aft, y = up/down);
 *  - a **mirrored** node (bilateral pair) becomes a near/far pair separated in **depth** (z) — so in the
 *    side view the near eye/leg occludes the far one, for free;
 *  - **scale** compounds down the chain into each part's radius.
 * Each node is a sphere + a capsule back to its parent → a connected, organic, metaball-smoothed body.
 * Rendered at a neutral expression (this spike is about the body, not the mood). Deterministic + headless.
 */
object MorphBakeSpike {

    private class V(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Double) = V(x * s, y * s, z * s)
        fun dot(o: V) = x * o.x + y * o.y + z * o.z
        fun len() = sqrt(x * x + y * y + z * z)
        fun norm(): V { val l = len(); return if (l < 1e-9) V(0.0, 0.0, 0.0) else V(x / l, y / l, z / l) }
    }

    private const val FUR = 0; private const val SCLERA = 1; private const val NOSE = 2
    private const val GIRTH = 0.62      // sphere radius per unit cumulative scale
    private const val Z_SPREAD = 0.62   // how far a bilateral pair splays into depth

    private class Bone(val parent: V?, val center: V, val r: Double, val mat: Int)
    private class Hit(val d: Double, val mat: Int, val eye: V? = null)

    // ---- genome → bones (forward kinematics in the profile plane; mirror → depth pair) ----
    private fun dirAngleDeg(ox: Float, oy: Float) = atan2(-ox.toDouble(), oy.toDouble()) * 180.0 / Math.PI
    private fun rot(x: Double, y: Double, deg: Double): Pair<Double, Double> {
        val r = deg * Math.PI / 180.0; val c = cos(r); val s = sin(r)
        return (x * c - y * s) to (x * s + y * c)
    }
    private fun matFor(name: String) = when {
        name.startsWith("eye") -> SCLERA
        name.startsWith("nose") -> NOSE
        else -> FUR
    }

    private fun layout(n: MorphNode, px: Double, py: Double, dirDeg: Double, cumScale: Double, z: Double, parent: V?, out: MutableList<Bone>) {
        if (out.size > 240) return
        val r = cumScale * GIRTH
        val center = V(px, py, z)
        out.add(Bone(parent, center, r, matFor(n.name)))
        for (c in n.children) {
            val cdir = dirDeg + dirAngleDeg(c.ox, c.oy)
            val (rx, ry) = rot(c.ox.toDouble(), c.oy.toDouble(), dirDeg)
            val cx = px + rx * cumScale; val cy = py + ry * cumScale
            val cs = (cumScale * c.scale).coerceAtMost(3.0)
            if (c.mirrored) {
                val zd = cs * Z_SPREAD
                layout(c, cx, cy, cdir, cs, z + zd, center, out)   // near (toward viewer)
                layout(c, cx, cy, cdir, cs, z - zd, center, out)   // far (occluded in profile)
            } else {
                layout(c, cx, cy, cdir, cs, z, center, out)
            }
        }
    }

    // ---- SDF ----
    private fun sphere(p: V, c: V, r: Double) = (p - c).len() - r
    private fun capsule(p: V, a: V, b: V, r: Double): Double {
        val pa = p - a; val ba = b - a
        val h = (pa.dot(ba) / ba.dot(ba)).coerceIn(0.0, 1.0)
        return (pa - ba * h).len() - r
    }
    private fun smin(a: Double, b: Double, k: Double): Double {
        val h = (0.5 + 0.5 * (b - a) / k).coerceIn(0.0, 1.0)
        return b * (1 - h) + a * h - k * h * (1.0 - h)
    }

    private class Creature(bones: List<Bone>) {
        val fur = bones.filter { it.mat == FUR }
        val eyes = bones.filter { it.mat == SCLERA }
        val noses = bones.filter { it.mat == NOSE }
        fun scene(p: V): Hit {
            var d = 1e9
            for (b in fur) {
                d = smin(d, sphere(p, b.center, b.r), 0.42)
                if (b.parent != null) d = smin(d, capsule(p, b.parent, b.center, max(0.12, b.r * 0.72)), 0.34)
            }
            var best = Hit(d, FUR)
            for (e in eyes) { val ed = sphere(p, e.center, e.r); if (ed < best.d) best = Hit(ed, SCLERA, e.center) }
            for (nb in noses) { val nd = sphere(p, nb.center, nb.r); if (nd < best.d) best = Hit(nd, NOSE) }
            return best
        }
        fun grad(p: V): V {
            val h = 0.0018
            fun s(dx: Double, dy: Double, dz: Double) = scene(V(p.x + dx, p.y + dy, p.z + dz)).d
            val k1 = V(1.0, -1.0, -1.0); val k2 = V(-1.0, -1.0, 1.0); val k3 = V(-1.0, 1.0, -1.0); val k4 = V(1.0, 1.0, 1.0)
            return (k1 * s(k1.x * h, k1.y * h, k1.z * h) + k2 * s(k2.x * h, k2.y * h, k2.z * h) +
                k3 * s(k3.x * h, k3.y * h, k3.z * h) + k4 * s(k4.x * h, k4.y * h, k4.z * h)).norm()
        }
        fun ao(p: V, n: V): Double {
            var occ = 0.0; var sca = 1.0
            for (i in 1..5) { val hr = 0.02 + 0.12 * i; occ += (hr - scene(p + n * hr).d) * sca; sca *= 0.78 }
            return (1.0 - 3.0 * occ).coerceIn(0.0, 1.0)
        }
    }

    private val LIGHT = V(0.42, 0.62, 0.66).norm()
    private val VIEW = V(0.0, 0.0, 1.0)

    private fun shade(c: Creature, p: V, n: V, hit: Hit, fur: Color): Int {
        val diff = max(0.0, n.dot(LIGHT)); val occ = c.ao(p, n)
        val rim = (1.0 - max(0.0, n.dot(VIEW))).pow(2.6) * 0.7; val ambient = 0.32 * occ
        fun lit(base: V, spec: Double): Int {
            var r = base.x * (ambient + diff * 0.99) + rim * 0.55
            var g = base.y * (ambient + diff * 0.95) + rim * 0.55
            var b = base.z * (ambient + diff * 0.90) + rim * 0.62
            if (spec > 0) { val sp = max(0.0, n.dot((LIGHT + VIEW).norm())).pow(48.0) * spec; r += sp; g += sp; b += sp }
            return ((r * 255).roundToInt().coerceIn(0, 255) shl 16) or ((g * 255).roundToInt().coerceIn(0, 255) shl 8) or (b * 255).roundToInt().coerceIn(0, 255)
        }
        return when (hit.mat) {
            SCLERA -> {
                val gaze = V(0.55, -0.05, 0.84).norm(); val sd = (p - (hit.eye ?: p)).norm()
                val ang = acos(sd.dot(gaze).coerceIn(-1.0, 1.0))
                val base = when { ang < 0.21 -> V(0.07, 0.05, 0.09); ang < 0.52 -> V(0.34, 0.55, 0.78); else -> V(0.97, 0.96, 0.93) }
                lit(base, if (ang > 0.52) 0.9 else 0.5)
            }
            NOSE -> lit(V(0.18, 0.13, 0.13), 0.6)
            else -> lit(V(fur.red / 255.0, fur.green / 255.0, fur.blue / 255.0), 0.04)
        }
    }

    private fun renderTile(img: BufferedImage, ox: Int, tile: Int, c: Creature, fur: Color, bg: Int) {
        // fit the creature to the tile from its bone bounds
        var minX = 1e9; var maxX = -1e9; var minY = 1e9; var maxY = -1e9
        for (b in (c.fur + c.eyes + c.noses)) {
            minX = min(minX, b.center.x - b.r); maxX = max(maxX, b.center.x + b.r)
            minY = min(minY, b.center.y - b.r); maxY = max(maxY, b.center.y + b.r)
        }
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(0.5) * 1.18
        val cX = (minX + maxX) / 2; val cY = (minY + maxY) / 2
        IntStream.range(0, tile).parallel().forEach { py ->
            for (px in 0 until tile) {
                val wx = cX + (px.toDouble() / tile - 0.5) * span
                val wy = cY - (py.toDouble() / tile - 0.5) * span
                val ro = V(wx, wy, 6.0)
                var t = 0.0; var hit: Hit? = null; var hp = ro; var steps = 0
                while (steps < 110 && t < 13.0) {
                    hp = V(ro.x, ro.y, ro.z - t); val h = c.scene(hp)
                    if (h.d < 0.001) { hit = h; break }
                    t += h.d * 0.85; steps++
                }
                img.setRGB(ox + px, py, if (hit != null) shade(c, hp, c.grad(hp), hit, fur) else bg)
            }
        }
    }

    /** A cute baby-schema base norn: small body, dominant head, big eye, button nose, round ears, stubs.
     *  Head sits close + large (≈half the height), body compact, limbs stubby → baby proportions. */
    private fun cuteGenome(): MorphNode {
        val body = MorphNode("body", scale = 0.82f)
        val head = MorphNode("head", ox = 0f, oy = 1.25f, scale = 1.85f).apply {
            children.add(MorphNode("crown", ox = 0f, oy = 0.42f, scale = 0.82f))               // forehead/cranium mass
            children.add(MorphNode("muzzle", ox = 0.86f, oy = -0.22f, scale = 0.5f).apply {
                children.add(MorphNode("nose", ox = 0.5f, oy = 0.02f, scale = 0.34f))
            })
            children.add(MorphNode("eye", ox = 0.55f, oy = 0.02f, scale = 0.66f, mirX = 1f))
            children.add(MorphNode("ear", ox = -0.34f, oy = 0.66f, scale = 0.5f, mirX = 1f))
        }
        body.children.add(head)
        body.children.add(MorphNode("arm", ox = 0.62f, oy = -0.35f, scale = 0.4f, mirX = 1f))
        body.children.add(MorphNode("leg", ox = 0.16f, oy = -0.95f, scale = 0.58f, mirX = 1f))
        return body
    }

    private fun build(g: MorphNode): Creature {
        val bones = ArrayList<Bone>()
        layout(g, 0.0, 0.0, 0.0, 1.0, 0.0, null, bones)
        return Creature(bones)
    }

    fun run(out: File) {
        val base = cuteGenome()
        val mutA = base.deepClone().also { MorphGenome.mutate(it, GeneRng(4), 0.35f, 0.06f) }
        val mutB = base.deepClone().also { MorphGenome.mutate(it, GeneRng(13), 0.45f, 0.10f) }
        val mutC = base.deepClone().also { MorphGenome.mutate(it, GeneRng(27), 0.55f, 0.14f) }
        val cross = MorphGenome.crossbreed(mutA, mutB)
        val genomes = listOf("base" to base, "mutant A" to mutA, "mutant B" to mutB, "A × B child" to cross, "mutant C" to mutC)
        val furs = listOf(Color(176, 142, 104), Color(150, 120, 90), Color(150, 152, 120), Color(168, 130, 120), Color(130, 140, 158))

        val tile = 300; val bgC = Color(236, 232, 224); val bg = bgC.rgb and 0xFFFFFF
        val img = BufferedImage(tile * genomes.size, tile, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = bgC; g.fillRect(0, 0, img.width, img.height)
        for ((i, ng) in genomes.withIndex()) {
            renderTile(img, i * tile, tile, build(ng.second), furs[i], bg)
            g.color = Color(60, 50, 40); g.font = Font("SansSerif", Font.BOLD, 14); g.drawString(ng.first, i * tile + 10, 22)
        }
        g.dispose(); out.parentFile?.mkdirs(); ImageIO.write(img, "png", out); println("wrote ${out.absolutePath}")
    }
}

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    MorphBakeSpike.run(File(args.getOrElse(0) { "build/morph-bake.png" }))
}
