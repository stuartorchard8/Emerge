package org.emerge.desktop

import org.emerge.demo.norns.morph.MorphNode
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.stream.IntStream
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
 * The consolidated creature renderer: a [MorphNode] genome **and** a mood → a lit, C2-style side-profile,
 * baked by software SDF ray-march (deterministic, headless). Folds the two throwaway spikes into one
 * real path:
 *  - body: the genome's forward-kinematics places each node in the profile plane; a mirrored node becomes
 *    a near/far depth pair (the far one occludes for free); scale compounds into each part's radius; nodes
 *    render as spheres + capsules-to-parent, metaball-smoothed into one organic mass;
 *  - expression baked into the geometry, anchored to the genome's real eye/muzzle nodes: eyelids that
 *    physically close, a brow ridge that tilts, a jaw that carves open — all driven by [Mood].
 *
 * This is the foundation the authoring tool drives and the bake pipeline will reuse.
 */
object CreatureRenderer {

    class V(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Double) = V(x * s, y * s, z * s)
        fun dot(o: V) = x * o.x + y * o.y + z * o.z
        fun len() = sqrt(x * x + y * y + z * z)
        fun norm(): V { val l = len(); return if (l < 1e-9) V(0.0, 0.0, 0.0) else V(x / l, y / l, z / l) }
    }

    /** Continuous mood (valence −1..+1 × arousal −1..+1) → expression knobs, baked into geometry. */
    class Mood(valence: Double, arousal: Double) {
        val v = valence.coerceIn(-1.0, 1.0); val a = arousal.coerceIn(-1.0, 1.0)
        val eyeOpen = (0.70 + a * 0.55 - max(0.0, -v) * 0.45).coerceIn(0.06, 1.25)
        val pupil = (0.42 + a * 0.18).coerceIn(0.22, 0.66)
        val browTilt = if (v < 0) (if (a > 0) -1.0 else 1.0) * (-v) * 0.9 else v * 0.2
        val browRaise = a * 0.16 - max(0.0, -v) * 0.04
        val mouthCurve = v * 0.6 - max(0.0, a) * 0.1
        val mouthOpen = (max(0.0, a) * (1.0 - max(0.0, v)) * 0.9).coerceIn(0.0, 1.0)

        companion object {
            val PRESETS = listOf(
                "neutral" to Mood(0.0, 0.0), "happy" to Mood(0.85, 0.4), "content" to Mood(0.6, -0.35),
                "sad" to Mood(-0.7, -0.5), "angry" to Mood(-0.7, 0.6), "scared" to Mood(-0.5, 0.95),
                "surprised" to Mood(0.1, 0.95), "sleepy" to Mood(0.0, -0.95),
            )
        }
    }

    private const val FUR = 0; private const val SCLERA = 1; private const val NOSE = 2; private const val MOUTH = 3
    private const val GIRTH = 0.62
    private const val Z_SPREAD = 0.62

    internal class Bone(val node: String, val parent: V?, val center: V, val r: Double, val mat: Int)
    internal class Hit(val d: Double, val mat: Int, val eye: V? = null)
    private class EyeAnchor(val c: V, val r: Double)

    // ---- genome → bones + expression anchors ----
    private fun dirAngleDeg(ox: Float, oy: Float) = atan2(-ox.toDouble(), oy.toDouble()) * 180.0 / Math.PI
    private fun rot(x: Double, y: Double, deg: Double): Pair<Double, Double> {
        val r = deg * Math.PI / 180.0; val c = cos(r); val s = sin(r); return (x * c - y * s) to (x * s + y * c)
    }
    private fun matFor(name: String) = when {
        name.startsWith("eye") -> SCLERA; name.startsWith("nose") -> NOSE; else -> FUR
    }

    /** A genome flattened to renderable primitives + the anchors expression hangs off. */
    class Baked(genome: MorphNode) {
        private val fur = ArrayList<Bone>()
        private val eyes = ArrayList<Bone>()
        private val noses = ArrayList<Bone>()
        private val eyeAnchors = ArrayList<EyeAnchor>()
        var muzzle: V = V(0.9, -0.4, 0.0); private set
        var muzzleR: Double = 0.4; private set

        init {
            layout(genome, 0.0, 0.0, 0.0, 1.0, 0.0, null)
            for (e in eyes) eyeAnchors.add(EyeAnchor(e.center, e.r))
        }

        private fun layout(n: MorphNode, px: Double, py: Double, dirDeg: Double, cumScale: Double, z: Double, parent: V?) {
            if (fur.size + eyes.size + noses.size > 260) return
            val r = cumScale * GIRTH
            val center = V(px, py, z)
            val mat = matFor(n.name)
            val bone = Bone(n.name, parent, center, r, mat)
            when (mat) { SCLERA -> eyes.add(bone); NOSE -> noses.add(bone); else -> fur.add(bone) }
            if (n.name.startsWith("muzzle") && z == 0.0) { muzzle = center; muzzleR = r }
            for (c in n.children) {
                val cdir = dirDeg + dirAngleDeg(c.ox, c.oy)
                val (rx, ry) = rot(c.ox.toDouble(), c.oy.toDouble(), dirDeg)
                val cx = px + rx * cumScale; val cy = py + ry * cumScale
                val cs = (cumScale * c.scale).coerceAtMost(3.0)
                if (c.mirrored) {
                    val zd = cs * Z_SPREAD
                    layout(c, cx, cy, cdir, cs, z + zd, center); layout(c, cx, cy, cdir, cs, z - zd, center)
                } else layout(c, cx, cy, cdir, cs, z, center)
            }
        }

        /** All renderable bones (fur + eyes + noses), each tagged with its genome node name — lets the
         *  part baker pick out one part (e.g. the near leg, the head assembly) by name + depth. */
        internal fun bones(): List<Bone> = fur + eyes + noses

        /** Bounds of [bs] (or all bones) as [minX, maxX, minY, maxY]. */
        internal fun bounds(bs: List<Bone> = fur + eyes + noses): DoubleArray {
            var minX = 1e9; var maxX = -1e9; var minY = 1e9; var maxY = -1e9
            for (b in bs) {
                minX = min(minX, b.center.x - b.r); maxX = max(maxX, b.center.x + b.r)
                minY = min(minY, b.center.y - b.r); maxY = max(maxY, b.center.y + b.r)
            }
            return doubleArrayOf(minX, maxX, minY, maxY)
        }

        internal fun scene(p: V, m: Mood, include: ((Bone) -> Boolean)? = null, expression: Boolean = true): Hit {
            var d = 1e9
            for (b in fur) {
                if (include != null && !include(b)) continue
                d = smin(d, sphere(p, b.center, b.r), 0.42)
                if (b.parent != null) d = smin(d, capsule(p, b.parent, b.center, max(0.12, b.r * 0.72)), 0.34)
            }
            // expression on the geometry, per eye
            if (expression) for (e in eyeAnchors) {
                val u = e.r / 0.45
                val lidY = e.c.y + e.r * (1.55 * m.eyeOpen - 0.32)
                d = smin(d, sphere(p, V(e.c.x, lidY, e.c.z + 0.04), e.r * 1.04), 0.06)               // upper lid closes
                d = smin(d, sphere(p, V(e.c.x, e.c.y - e.r * 0.95, e.c.z + 0.02), e.r * 0.85), 0.10) // lower lid
                val browY = e.c.y + 0.50 * u + m.browRaise * u
                val bOut = V(e.c.x - 0.30 * u, browY, e.c.z + 0.30 * u)
                val bIn = V(e.c.x + 0.30 * u, browY - m.browTilt * 0.26 * u, e.c.z + 0.28 * u)
                d = smin(d, capsule(p, bOut, bIn, 0.075 * u), 0.09)                                   // brow ridge tilts
            }
            // jaw drop: carve a real cavity into the muzzle front
            val mouthC = V(muzzle.x + muzzleR * 0.55, muzzle.y - muzzleR * 0.45 + m.mouthCurve * 0.08, 0.0)
            var mouthInner = 1e9
            if (expression && m.mouthOpen > 0.02) {
                val cav = sphere(p, mouthC + V(-muzzleR * 0.15, -0.06 * m.mouthOpen, 0.0), muzzleR * (0.22 + 0.55 * m.mouthOpen))
                d = smax(d, -cav, 0.05)
                mouthInner = sphere(p, mouthC + V(-muzzleR * 0.05, -0.04 * m.mouthOpen, 0.0), muzzleR * (0.1 + 0.45 * m.mouthOpen))
            }
            var best = Hit(d, FUR)
            for (e in eyes) { if (include != null && !include(e)) continue; val ed = sphere(p, e.center, e.r); if (ed < best.d) best = Hit(ed, SCLERA, e.center) }
            for (nb in noses) { if (include != null && !include(nb)) continue; val nd = sphere(p, nb.center, nb.r); if (nd < best.d) best = Hit(nd, NOSE) }
            if (mouthInner < best.d) best = Hit(mouthInner, MOUTH)
            return best
        }

        internal fun grad(p: V, m: Mood, include: ((Bone) -> Boolean)? = null, expression: Boolean = true): V {
            val h = 0.0018
            fun s(dx: Double, dy: Double, dz: Double) = scene(V(p.x + dx, p.y + dy, p.z + dz), m, include, expression).d
            val k1 = V(1.0, -1.0, -1.0); val k2 = V(-1.0, -1.0, 1.0); val k3 = V(-1.0, 1.0, -1.0); val k4 = V(1.0, 1.0, 1.0)
            return (k1 * s(k1.x * h, k1.y * h, k1.z * h) + k2 * s(k2.x * h, k2.y * h, k2.z * h) +
                k3 * s(k3.x * h, k3.y * h, k3.z * h) + k4 * s(k4.x * h, k4.y * h, k4.z * h)).norm()
        }

        internal fun ao(p: V, n: V, m: Mood, include: ((Bone) -> Boolean)? = null, expression: Boolean = true): Double {
            var occ = 0.0; var sca = 1.0
            for (i in 1..5) { val hr = 0.02 + 0.12 * i; occ += (hr - scene(p + n * hr, m, include, expression).d) * sca; sca *= 0.78 }
            return (1.0 - 3.0 * occ).coerceIn(0.0, 1.0)
        }
    }

    // ---- SDF ----
    private fun sphere(p: V, c: V, r: Double) = (p - c).len() - r
    private fun capsule(p: V, a: V, b: V, r: Double): Double {
        val pa = p - a; val ba = b - a; val h = (pa.dot(ba) / ba.dot(ba)).coerceIn(0.0, 1.0)
        return (pa - ba * h).len() - r
    }
    private fun smin(a: Double, b: Double, k: Double): Double {
        val h = (0.5 + 0.5 * (b - a) / k).coerceIn(0.0, 1.0); return b * (1 - h) + a * h - k * h * (1.0 - h)
    }
    private fun smax(a: Double, b: Double, k: Double) = -smin(-a, -b, k)

    private val LIGHT = V(0.42, 0.62, 0.66).norm()
    private val VIEW = V(0.0, 0.0, 1.0)

    private fun shade(c: Baked, p: V, n: V, hit: Hit, m: Mood, fur: Color, include: ((Bone) -> Boolean)? = null, expression: Boolean = true): Int {
        val diff = max(0.0, n.dot(LIGHT)); val occ = c.ao(p, n, m, include, expression)
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
                val base = when { ang < 0.42 * m.pupil -> V(0.07, 0.05, 0.09); ang < 0.52 -> V(0.34, 0.55, 0.78); else -> V(0.97, 0.96, 0.93) }
                lit(base, if (ang > 0.52) 0.9 else 0.5)
            }
            NOSE -> lit(V(0.18, 0.13, 0.13), 0.6)
            MOUTH -> lit(V(0.30, 0.10, 0.12), 0.0)
            else -> {
                var base = V(fur.red / 255.0, fur.green / 255.0, fur.blue / 255.0)
                val mlY = c.muzzle.y - c.muzzleR * 0.45 + m.mouthCurve * 0.05 + (c.muzzle.x + c.muzzleR * 0.55 - p.x).coerceAtLeast(0.0) * m.mouthCurve * 0.55
                val onMuzzle = p.x > c.muzzle.x - c.muzzleR * 0.2 && p.z > -0.30 && p.z < 0.48
                if (onMuzzle && kotlin.math.abs(p.y - mlY) < 0.05 && m.mouthOpen < 0.3) base = base * 0.4
                lit(base, 0.04)
            }
        }
    }

    /** The orthographic side-on framing of a [Baked] creature fitted into a square [tile]: lets callers
     *  foot-align + scale a baked sprite into the world (the world renderer needs the feet, not the box). */
    class Frame(val span: Double, val cX: Double, val cY: Double, val tile: Int) {
        /** Screen-y (px from the tile top) of a world-y — e.g. the feet at the bounds' min-y. */
        fun screenY(worldY: Double) = (0.5 - (worldY - cY) / span) * tile
    }

    fun frame(baked: Baked, tile: Int): Frame {
        val b = baked.bounds()
        val span = max(b[1] - b[0], b[3] - b[2]).coerceAtLeast(0.5) * 1.18
        return Frame(span, (b[0] + b[1]) / 2, (b[2] + b[3]) / 2, tile)
    }

    /** Render [baked] at [mood] into [img] at column offset [ox], side-profile, fitted to [tile] px.
     *  [transparent] (with an ARGB [img]) leaves the background fully transparent for compositing. */
    fun render(baked: Baked, mood: Mood, fur: Color, img: BufferedImage, ox: Int, tile: Int, bg: Int, transparent: Boolean = false) {
        val fr = frame(baked, tile)
        IntStream.range(0, tile).parallel().forEach { py ->
            for (px in 0 until tile) {
                val wx = fr.cX + (px.toDouble() / tile - 0.5) * fr.span
                val wy = fr.cY - (py.toDouble() / tile - 0.5) * fr.span
                val ro = V(wx, wy, 6.0)
                var t = 0.0; var hit: Hit? = null; var hp = ro; var steps = 0
                while (steps < 110 && t < 13.0) {
                    hp = V(ro.x, ro.y, ro.z - t); val h = baked.scene(hp, mood)
                    if (h.d < 0.001) { hit = h; break }
                    t += h.d * 0.85; steps++
                }
                val v = if (hit != null) {
                    val rgb = shade(baked, hp, baked.grad(hp, mood), hit, mood, fur)
                    if (transparent) (0xFF shl 24) or rgb else rgb
                } else if (transparent) 0 else bg
                img.setRGB(ox + px, py, v)
            }
        }
    }
}
