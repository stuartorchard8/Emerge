package org.emerge.desktop

import org.emerge.demo.norns.morph.MorphNode
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.stream.IntStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The creature renderer: a [MorphNode] genome **and** a mood bake to a lit, C2-style side-profile via
 * software SDF ray-march (deterministic, headless). **Everything is data-driven** — there is no
 * hardcoded face. Each node is a (rotated, per-axis-scaled) ellipsoid placed by the genome's
 * forward-kinematics; **expression is authored per node** as a linear response to the mood: the node's
 * offset, rotation, and per-axis scale each shift by `coeff·valence + coeff·arousal`. So a brow tilts,
 * a lid closes, a mouth curves purely because those nodes were given that response — all editable in
 * MorphLab and all carried in the genome's `extra` (so it crossbreeds).
 *
 * Per-node `extra` channels (all optional):
 *  - shape: `z` depth offset · `sx`,`sy`,`sz` per-axis radius (default 1) · `rot` base rotation (deg)
 *  - expression response (× valence v, × arousal a):
 *      position `vdx`,`adx`,`vdy`,`ady` · rotation `vrot`,`arot` (deg) · scale `vsx`,`asx`,`vsy`,`asy`
 *  Material comes from the node name: `eye`→white, `iris`→coloured, `pupil`→dark, `nose`→dark,
 *  `mouth`/`lip`→dark; anything else is fur (lids/brows are fur ridges that blend into the head).
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

    /** A mood in the PAD space: valence (−1 sad … +1 happy) × arousal (−1 calm … +1 excited) ×
     *  dominance (−1 submissive … +1 dominant). Dominance is what separates emotions that share a
     *  valence/arousal cell — notably anger (dominant) vs fear (submissive). Drives per-node response. */
    class Mood(valence: Double, arousal: Double, dominance: Double = 0.0) {
        val v = valence.coerceIn(-1.0, 1.0); val a = arousal.coerceIn(-1.0, 1.0); val d = dominance.coerceIn(-1.0, 1.0)
        companion object {
            val PRESETS = listOf(
                "neutral" to Mood(0.0, 0.0, 0.0), "happy" to Mood(0.85, 0.4, 0.3), "content" to Mood(0.6, -0.35, 0.2),
                "sad" to Mood(-0.7, -0.5, -0.5), "angry" to Mood(-0.7, 0.6, 0.7), "scared" to Mood(-0.5, 0.95, -0.7),
                "surprised" to Mood(0.1, 0.95, -0.1), "sleepy" to Mood(0.0, -0.95, 0.0),
            )
        }
    }

    private const val FUR = 0; private const val SCLERA = 1; private const val IRIS = 2
    private const val PUPIL = 3; private const val NOSE = 4; private const val MOUTH = 5
    private const val GIRTH = 0.62
    private const val DEG = Math.PI / 180.0

    internal class Bone(
        val node: String, val parent: V?, val center: V,
        val rx: Double, val ry: Double, val rz: Double, val orient: Mat3, val mat: Int, val connect: Boolean,
    )
    internal class Hit(val d: Double, val mat: Int)

    /** A 3×3 orthogonal matrix — a node's accumulated orientation (rotation, or rotation+reflection in a
     *  mirrored branch). Row-major. Cheap to build (only at bake time). */
    internal class Mat3(val m: DoubleArray) {
        operator fun times(o: Mat3): Mat3 {
            val r = DoubleArray(9)
            for (i in 0..2) for (j in 0..2) { var s = 0.0; for (k in 0..2) s += m[i * 3 + k] * o.m[k * 3 + j]; r[i * 3 + j] = s }
            return Mat3(r)
        }
        fun apply(v: V) = V(m[0] * v.x + m[1] * v.y + m[2] * v.z, m[3] * v.x + m[4] * v.y + m[5] * v.z, m[6] * v.x + m[7] * v.y + m[8] * v.z)
        fun applyT(v: V) = V(m[0] * v.x + m[3] * v.y + m[6] * v.z, m[1] * v.x + m[4] * v.y + m[7] * v.z, m[2] * v.x + m[5] * v.y + m[8] * v.z)
        companion object {
            val I = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
            val REFLECT_Z = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, -1.0))
            fun rotX(deg: Double): Mat3 { if (deg == 0.0) return I; val r = deg * DEG; val c = cos(r); val s = sin(r); return Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)) }
            fun rotY(deg: Double): Mat3 { if (deg == 0.0) return I; val r = deg * DEG; val c = cos(r); val s = sin(r); return Mat3(doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)) }
            fun rotZ(deg: Double): Mat3 { if (deg == 0.0) return I; val r = deg * DEG; val c = cos(r); val s = sin(r); return Mat3(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)) }
        }
    }

    private fun matFor(name: String) = when {
        name.startsWith("iris") -> IRIS
        name.startsWith("pupil") -> PUPIL
        name.startsWith("eye") -> SCLERA
        name.startsWith("nose") -> NOSE
        name.startsWith("mouth") || name.startsWith("lip") -> MOUTH
        else -> FUR
    }
    /** Fur features that should NOT draw a connecting limb to their parent (they're bumps/ridges/lids). */
    private fun connects(name: String, mat: Int) = mat == FUR &&
        !(name.startsWith("brow") || name.startsWith("lid") || name.startsWith("upperlid") || name.startsWith("lowerlid"))

    /** A genome flattened to mood-posed ellipsoid bones (expression already applied). Placement and
     *  orientation are full 3-D: each node has a local rotation about all three axes — base `rotX`/`rotY`/
     *  `rot` (=Z, the view axis) plus per-mood response (`vrotX`/`arotX`/`drotX`, …) — composed down the
     *  tree as a real joint, so a rotated node carries its whole subtree. A node's offset from its parent
     *  is (ox = forward/+x, oy = up/+y, z = depth), rotated by the parent's accumulated orientation [M].
     *
     *  Symmetry follows Evolutionism: a **mirrored** node duplicates its subtree as a true reflection
     *  across the depth plane (the far branch's [M] is multiplied by REFLECT_Z, so all descendants —
     *  offsets *and* rotations — reflect). The pair's separation is the node's own `z` (z=0 → coincident).
     *  **`sym`** (≥2) makes that many radial copies, each rotated 360/sym about the view axis. */
    class Baked(genome: MorphNode, mood: Mood) {
        private val fur = ArrayList<Bone>()
        private val features = ArrayList<Bone>()    // eyes/iris/pupil/nose (own materials, not fur)
        private val mouths = ArrayList<Bone>()      // mouth = a cut-away: subtract from fur + dark interior
        val v = mood.v; val a = mood.a; val d = mood.d

        init { layout(genome, V(0.0, 0.0, 0.0), Mat3.I, 1.0, null) }

        private fun layout(n: MorphNode, center: V, orient: Mat3, cumScale: Double, parent: V?) {
            if (fur.size + features.size + mouths.size > 280) return
            fun e(k: String) = n.extra[k] ?: 0f
            fun e1(k: String) = n.extra[k] ?: 1f
            val base = cumScale * GIRTH
            val sxF = e1("sx") * (1 + e("vsx") * v + e("asx") * a + e("dsx") * d)
            val syF = e1("sy") * (1 + e("vsy") * v + e("asy") * a + e("dsy") * d)
            val szF = e1("sz").toDouble()
            val mat = matFor(n.name)
            val bone = Bone(n.name, parent, center, base * sxF, base * syF, base * szF, orient, mat, connects(n.name, mat))
            when (mat) { FUR -> fur.add(bone); MOUTH -> mouths.add(bone); else -> features.add(bone) }
            for (c in n.children) {
                fun ce(k: String) = c.extra[k] ?: 0f
                val cs = (cumScale * c.scale).coerceAtMost(3.0)
                // child's local rotation about all three axes (base + per-mood response)
                val xd = ce("rotX") + ce("vrotX") * v + ce("arotX") * a + ce("drotX") * d
                val yd = ce("rotY") + ce("vrotY") * v + ce("arotY") * a + ce("drotY") * d
                val zd = ce("rot") + ce("vrot") * v + ce("arot") * a + ce("drot") * d
                val localR = Mat3.rotZ(zd) * Mat3.rotY(yd) * Mat3.rotX(xd)
                // offset from this node, in this node's frame: forward/up scaled by scale, depth (z) raw,
                // plus the per-mood positional shift (vdx/adx/ddx, …)
                val offX = c.ox * cumScale + ce("vdx") * v + ce("adx") * a + ce("ddx") * d
                val offY = c.oy * cumScale + ce("vdy") * v + ce("ady") * a + ce("ddy") * d
                val offset = V(offX, offY, ce("z").toDouble())
                val sym = c.sym.coerceAtLeast(1)
                val sides = if (c.mirrored) doubleArrayOf(1.0, -1.0) else doubleArrayOf(1.0)
                for (i in 0 until sym) {
                    val radial = if (sym > 1) Mat3.rotZ(i * 360.0 / sym) else Mat3.I
                    for (side in sides) {
                        val frame = if (side < 0) orient * radial * Mat3.REFLECT_Z else orient * radial
                        layout(c, center + frame.apply(offset), frame * localR, cs, center)
                    }
                }
            }
        }

        internal fun bones(): List<Bone> = fur + features + mouths

        internal fun bounds(bs: List<Bone> = fur + features + mouths): DoubleArray {
            var minX = 1e9; var maxX = -1e9; var minY = 1e9; var maxY = -1e9
            for (b in bs) {
                val rr = max(b.rx, b.ry)
                minX = min(minX, b.center.x - rr); maxX = max(maxX, b.center.x + rr)
                minY = min(minY, b.center.y - rr); maxY = max(maxY, b.center.y + rr)
            }
            return doubleArrayOf(minX, maxX, minY, maxY)
        }

        internal fun scene(p: V, include: ((Bone) -> Boolean)? = null): Hit {
            var d = 1e9
            for (b in fur) {
                if (include != null && !include(b)) continue
                d = smin(d, ellipsoid(p, b), 0.36)
                if (b.connect && b.parent != null) d = smin(d, capsule(p, b.parent, b.center, max(0.1, min(b.rx, b.ry) * 0.7)), 0.32)
            }
            // mouth = a cut-away: carve the cavity out of the fur. On the convex muzzle this yields a
            // curved opening (intrinsically a smile/frown depending on where/how the cut sits).
            for (b in mouths) {
                if (include != null && !include(b)) continue
                d = smax(d, -ellipsoid(p, b), 0.05)
            }
            var best = Hit(d, FUR)
            for (b in features) {
                if (include != null && !include(b)) continue
                val fd = ellipsoid(p, b); if (fd < best.d) best = Hit(fd, b.mat)
            }
            // dark interior, inset behind the opening so you see into the cut (not a flat patch)
            for (b in mouths) {
                if (include != null && !include(b)) continue
                val md = ellipsoid(p, b, 0.8); if (md < best.d) best = Hit(md, MOUTH)
            }
            return best
        }

        internal fun grad(p: V, include: ((Bone) -> Boolean)? = null): V {
            val h = 0.0018
            fun s(dx: Double, dy: Double, dz: Double) = scene(V(p.x + dx, p.y + dy, p.z + dz), include).d
            val k1 = V(1.0, -1.0, -1.0); val k2 = V(-1.0, -1.0, 1.0); val k3 = V(-1.0, 1.0, -1.0); val k4 = V(1.0, 1.0, 1.0)
            return (k1 * s(k1.x * h, k1.y * h, k1.z * h) + k2 * s(k2.x * h, k2.y * h, k2.z * h) +
                k3 * s(k3.x * h, k3.y * h, k3.z * h) + k4 * s(k4.x * h, k4.y * h, k4.z * h)).norm()
        }

        internal fun ao(p: V, n: V, include: ((Bone) -> Boolean)? = null): Double {
            var occ = 0.0; var sca = 1.0
            for (i in 1..5) { val hr = 0.02 + 0.12 * i; occ += (hr - scene(p + n * hr, include).d) * sca; sca *= 0.78 }
            return (1.0 - 3.0 * occ).coerceIn(0.0, 1.0)
        }
    }

    // ---- SDF ----
    /** Rotated (about the view/z axis) ellipsoid distance — the per-axis radii give shape, rot tilts it. */
    private fun ellipsoid(p: V, b: Bone, scale: Double = 1.0): Double {
        val q = b.orient.applyT(p - b.center)      // into the node's local frame (inverse orientation)
        val rx = b.rx * scale; val ry = b.ry * scale; val rz = b.rz * scale
        val ex = q.x / rx; val ey = q.y / ry; val ez = q.z / rz
        val k = sqrt(ex * ex + ey * ey + ez * ez)
        return (k - 1.0) * min(rx, min(ry, rz))
    }
    private fun capsule(p: V, a: V, b: V, r: Double): Double {
        val ba = b - a; val bb = ba.dot(ba)
        if (bb < 1e-9) return (p - a).len() - r            // coincident endpoints → a sphere (avoids 0/0 NaN)
        val pa = p - a; val h = (pa.dot(ba) / bb).coerceIn(0.0, 1.0)
        return (pa - ba * h).len() - r
    }
    private fun smin(a: Double, b: Double, k: Double): Double {
        val h = (0.5 + 0.5 * (b - a) / k).coerceIn(0.0, 1.0); return b * (1 - h) + a * h - k * h * (1.0 - h)
    }
    private fun smax(a: Double, b: Double, k: Double) = -smin(-a, -b, k)

    private fun shade(c: Baked, p: V, n: V, hit: Hit, fur: Color, include: ((Bone) -> Boolean)?, view: V): Int {
        // No directional key. Form comes from SDF ambient occlusion (crevices darken, exposed surfaces
        // brighten) plus a gentle vertical sky/ground term that — being about the world up-axis — is
        // invariant as you orbit, so no side darkens. Camera-relative rim for the silhouette.
        val occ = c.ao(p, n, include)
        val up = n.y * 0.5 + 0.5
        val fill = (0.45 + 0.55 * occ) * (0.72 + 0.28 * up)
        val rim = (1.0 - max(0.0, n.dot(view))).pow(2.8) * 0.4
        fun lit(base: V, glint: Double): Int {
            var r = base.x * fill + rim
            var g = base.y * fill + rim
            var b = base.z * fill + rim * 1.06
            if (glint > 0) { val gl = max(0.0, n.dot(view)).pow(36.0) * glint; r += gl; g += gl; b += gl }  // view-centred catchlight
            return ((r * 255).roundToInt().coerceIn(0, 255) shl 16) or ((g * 255).roundToInt().coerceIn(0, 255) shl 8) or (b * 255).roundToInt().coerceIn(0, 255)
        }
        return when (hit.mat) {
            SCLERA -> lit(V(0.97, 0.96, 0.93), 0.6)
            IRIS -> lit(V(0.34, 0.55, 0.78), 0.9)
            PUPIL -> lit(V(0.06, 0.05, 0.08), 0.8)
            NOSE -> lit(V(0.18, 0.13, 0.13), 0.6)
            MOUTH -> lit(V(0.30, 0.10, 0.12), 0.0)
            else -> lit(V(fur.red / 255.0, fur.green / 255.0, fur.blue / 255.0), 0.04)
        }
    }

    /** The orthographic side-on framing of a [Baked] creature in a square [tile]: lets callers foot-align. */
    class Frame(val span: Double, val cX: Double, val cY: Double, val tile: Int) {
        fun screenY(worldY: Double) = (0.5 - (worldY - cY) / span) * tile
    }

    fun frame(baked: Baked, tile: Int): Frame {
        val b = baked.bounds()
        val span = max(b[1] - b[0], b[3] - b[2]).coerceAtLeast(0.5) * 1.18
        return Frame(span, (b[0] + b[1]) / 2, (b[2] + b[3]) / 2, tile)
    }

    /** Render [baked] into [img] at column offset [ox], fitted to [tile] px, viewed from camera [yawDeg]
     *  (orbit around the vertical axis; 0 = the C2 side profile) / [pitchDeg] (tilt up/down). Orthographic.
     *  [transparent] (with an ARGB [img]) leaves the background clear for compositing. */
    fun render(baked: Baked, fur: Color, img: BufferedImage, ox: Int, tile: Int, bg: Int, transparent: Boolean = false, yawDeg: Double = 0.0, pitchDeg: Double = 0.0) {
        val fr = frame(baked, tile)
        val center = V(fr.cX, fr.cY, 0.0)
        val cyaw = cos(yawDeg * DEG); val syaw = sin(yawDeg * DEG); val cp = cos(pitchDeg * DEG); val sp = sin(pitchDeg * DEG)
        // camera→world: pitch about X, then yaw about Y (orbits the creature, light stays world-fixed)
        fun toWorld(p: V): V {
            val y1 = p.y * cp - p.z * sp; val z1 = p.y * sp + p.z * cp
            return V(p.x * cyaw + z1 * syaw, y1, -p.x * syaw + z1 * cyaw)
        }
        val dir = toWorld(V(0.0, 0.0, -1.0))
        val view = dir * -1.0     // toward the camera, for rim + specular
        IntStream.range(0, tile).parallel().forEach { py ->
            for (px in 0 until tile) {
                val u = (px.toDouble() / tile - 0.5) * fr.span
                val vv = -(py.toDouble() / tile - 0.5) * fr.span
                val ro = center + toWorld(V(u, vv, 6.0))
                var t = 0.0; var hit: Hit? = null; var hp = ro; var steps = 0
                while (steps < 110 && t < 13.0) {
                    hp = ro + dir * t; val h = baked.scene(hp)
                    if (h.d < 0.001) { hit = h; break }
                    t += h.d * 0.85; steps++
                }
                val value = if (hit != null) {
                    val rgb = shade(baked, hp, baked.grad(hp), hit, fur, null, view)
                    if (transparent) (0xFF shl 24) or rgb else rgb
                } else if (transparent) 0 else bg
                img.setRGB(ox + px, py, value)
            }
        }
    }
}
