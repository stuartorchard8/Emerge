package org.emerge.desktop

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.stream.IntStream
import javax.imageio.ImageIO
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * THROWAWAY SPIKE — the core bet behind the "genes → 3D → baked 2D sprite" pivot: does a procedural,
 * gene-driven creature rendered as a *lit 3D form* read as **appealing AND expressive** in the C2-style
 * **side profile** (subjective-forward, one eye visible), where flat 2D looked primitive?
 *
 * It builds a cute baby-schema creature as smooth-unioned **implicit primitives** (spheres/capsules),
 * sphere-traces it in software with baked lighting (key + rim + ambient + AO + glossy eye), and bakes
 * **expression into the 3D itself** — eyelids that physically close, a jaw that drops, a brow ridge that
 * tilts — driven by a valence/arousal mood. No mesh, no GL: deterministic + headless so it stays
 * PNG-verifiable like the rest. The point is the LOOK, not genome wiring (that comes once the bet wins).
 */
object CreatureBakeSpike {

    // ----- tiny 3D vector -----
    private class V(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Double) = V(x * s, y * s, z * s)
        fun dot(o: V) = x * o.x + y * o.y + z * o.z
        fun len() = sqrt(x * x + y * y + z * z)
        fun norm(): V { val l = len(); return if (l < 1e-9) V(0.0, 0.0, 0.0) else V(x / l, y / l, z / l) }
    }

    private const val FUR = 0
    private const val SCLERA = 1
    private const val NOSE = 2
    private const val MOUTH = 3

    private class Hit(val d: Double, val mat: Int)

    /** Mood → expression knobs, baked into the 3D geometry (valence/arousal, same model as FaceLab). */
    private class Expr(valence: Double, arousal: Double) {
        val v = valence.coerceIn(-1.0, 1.0); val a = arousal.coerceIn(-1.0, 1.0)
        val eyeOpen = (0.70 + a * 0.55 - max(0.0, -v) * 0.45).coerceIn(0.06, 1.25)  // wide when excited, droop when sad
        val pupil = (0.42 + a * 0.18).coerceIn(0.22, 0.66)                          // arousal dilates
        val browTilt = if (v < 0) (if (a > 0) -1.0 else 1.0) * (-v) * 0.9 else v * 0.2  // angry inner-down / sad inner-up
        val browRaise = a * 0.16 - max(0.0, -v) * 0.04
        val mouthCurve = v * 0.6 - max(0.0, a) * 0.1                                 // smile up / frown down
        val mouthOpen = (max(0.0, a) * (1.0 - max(0.0, v)) * 0.9).coerceIn(0.0, 1.0) // aroused + not-happy → agape
    }

    // ----- SDF primitives -----
    private fun sphere(p: V, c: V, r: Double) = (p - c).len() - r
    private fun capsule(p: V, a: V, b: V, r: Double): Double {
        val pa = p - a; val ba = b - a
        val h = (pa.dot(ba) / ba.dot(ba)).coerceIn(0.0, 1.0)
        return (pa - ba * h).len() - r
    }
    /** polynomial smooth-min: blends shapes into one organic mass (the cute "no hard seams" trick). */
    private fun smin(a: Double, b: Double, k: Double): Double {
        val h = (0.5 + 0.5 * (b - a) / k).coerceIn(0.0, 1.0)
        return b * (1 - h) + a * h - k * h * (1.0 - h)
    }
    private fun smax(a: Double, b: Double, k: Double) = -smin(-a, -b, k)

    private val EYE_C = V(0.70, 0.00, 0.58)   // near eye: forward (+x), proud (+z toward viewer)
    private const val EYE_R = 0.45

    private fun scene(p: V, e: Expr): Hit {
        // --- fur mass: head dominant, big forehead, small low muzzle, body + stubby limbs ---
        var fur = sphere(p, V(0.0, 0.05, 0.0), 0.98)                        // cranium
        fur = smin(fur, sphere(p, V(0.05, 0.45, 0.0), 0.74), 0.45)          // big baby forehead
        fur = smin(fur, sphere(p, V(0.80, -0.34, 0.0), 0.46), 0.40)         // muzzle (small, low)
        fur = smin(fur, sphere(p, V(-0.15, -1.45, 0.0), 0.86), 0.55)        // body (smaller than head)
        // round teddy-bear ears (near + far); near one reads in profile
        for (zs in intArrayOf(1, -1)) {
            fur = smin(fur, sphere(p, V(-0.18, 0.78, 0.52 * zs), 0.34), 0.16)
        }
        // limbs: near pair visible in side view
        for (zs in intArrayOf(1, -1)) {
            fur = smin(fur, capsule(p, V(0.15, -1.7, 0.40 * zs), V(0.35, -2.45, 0.42 * zs), 0.26), 0.22)  // leg
            fur = smin(fur, capsule(p, V(0.45, -1.15, 0.55 * zs), V(0.80, -1.55, 0.60 * zs), 0.20), 0.20) // arm
        }
        // subtle brow ridge that frames (not overhangs) the eye; tilt + raise carry expression.
        // inner end is toward the snout (+x, toward the nose); dropping it = the angry glare.
        val browY = EYE_C.y + 0.50 + e.browRaise
        val bOut = V(EYE_C.x - 0.30, browY, EYE_C.z + 0.30)                      // outer/back end (-x)
        val bIn = V(EYE_C.x + 0.30, browY - e.browTilt * 0.26, EYE_C.z + 0.28)   // inner end (+x, toward nose)
        fur = smin(fur, capsule(p, bOut, bIn, 0.075), 0.09)
        // upper eyelid: a fur sphere that slides DOWN over the eyeball as it closes
        val lidY = EYE_C.y + EYE_R * (1.55 * e.eyeOpen - 0.32)
        fur = smin(fur, sphere(p, V(EYE_C.x, lidY, EYE_C.z + 0.04), EYE_R * 1.04), 0.06)
        // lower lid: small static fur bump cradling the eye
        fur = smin(fur, sphere(p, V(EYE_C.x, EYE_C.y - EYE_R * 0.95, EYE_C.z + 0.02), EYE_R * 0.85), 0.10)

        // --- jaw drop: carve a real mouth cavity into the front of the muzzle when agape ---
        val mouthC = V(1.02, -0.58 + e.mouthCurve * 0.08, 0.0)
        if (e.mouthOpen > 0.02) {
            val cavity = sphere(p, mouthC + V(-0.06, -0.06 * e.mouthOpen, 0.0), 0.09 + 0.24 * e.mouthOpen)
            fur = smax(fur, -cavity, 0.05)
        }

        // --- glossy eyeball (its own material; protrudes for that big-eyed cute read) ---
        val eye = sphere(p, EYE_C, EYE_R)
        // --- button nose at the muzzle tip ---
        val nose = sphere(p, V(1.18, -0.34, 0.0), 0.15)
        // --- dark mouth interior sphere (shows through the carved cavity) ---
        val mouthInner = if (e.mouthOpen > 0.02) sphere(p, mouthC + V(-0.03, -0.04 * e.mouthOpen, 0.0), 0.04 + 0.20 * e.mouthOpen) else 1e9

        var best = Hit(fur, FUR)
        if (eye < best.d) best = Hit(eye, SCLERA)
        if (nose < best.d) best = Hit(nose, NOSE)
        if (mouthInner < best.d) best = Hit(mouthInner, MOUTH)
        return best
    }

    private fun grad(p: V, e: Expr): V {
        val h = 0.0015
        fun d(dx: Double, dy: Double, dz: Double) = scene(V(p.x + dx, p.y + dy, p.z + dz), e).d
        val k1 = V(1.0, -1.0, -1.0); val k2 = V(-1.0, -1.0, 1.0); val k3 = V(-1.0, 1.0, -1.0); val k4 = V(1.0, 1.0, 1.0)
        val n = k1 * d(k1.x * h, k1.y * h, k1.z * h) + k2 * d(k2.x * h, k2.y * h, k2.z * h) +
            k3 * d(k3.x * h, k3.y * h, k3.z * h) + k4 * d(k4.x * h, k4.y * h, k4.z * h)
        return n.norm()
    }

    /** cheap SDF ambient occlusion: how crowded is the space along the normal. */
    private fun ao(p: V, n: V, e: Expr): Double {
        var occ = 0.0; var sca = 1.0
        for (i in 1..5) {
            val hr = 0.02 + 0.12 * i
            val dd = scene(p + n * hr, e).d
            occ += (hr - dd) * sca
            sca *= 0.78
        }
        return (1.0 - 3.0 * occ).coerceIn(0.0, 1.0)
    }

    private val LIGHT = V(0.42, 0.62, 0.66).norm()      // key: upper, slightly front + toward viewer
    private val VIEW = V(0.0, 0.0, 1.0)                  // ortho camera looks down -z; +z is toward viewer

    private fun shade(p: V, n: V, mat: Int, e: Expr, fur: Color): IntArray {
        val diff = max(0.0, n.dot(LIGHT))
        val occ = ao(p, n, e)
        val rim = (1.0 - max(0.0, n.dot(VIEW))).pow(2.6) * 0.7
        val ambient = 0.32 * occ
        fun lit(base: V, spec: Double): IntArray {
            val warm = V(1.04, 1.0, 0.94)               // slightly warm key light
            var r = base.x * (ambient + diff * 0.95 * warm.x) + rim * 0.55
            var g = base.y * (ambient + diff * 0.95 * warm.y) + rim * 0.55
            var b = base.z * (ambient + diff * 0.95 * warm.z) + rim * 0.62
            if (spec > 0) {
                val hdir = (LIGHT + VIEW).norm()
                val sp = max(0.0, n.dot(hdir)).pow(48.0) * spec
                r += sp; g += sp; b += sp
            }
            return intArrayOf((r * 255).roundToInt().coerceIn(0, 255), (g * 255).roundToInt().coerceIn(0, 255), (b * 255).roundToInt().coerceIn(0, 255))
        }
        return when (mat) {
            SCLERA -> {
                // iris/pupil by gaze: the creature looks forward + slightly toward the viewer
                val gaze = V(0.55, -0.05, 0.84).norm()
                val sd = (p - EYE_C).norm()
                val ang = acos(sd.dot(gaze).coerceIn(-1.0, 1.0))
                val irisCol = V(0.34, 0.55, 0.78); val pupCol = V(0.07, 0.05, 0.09); val scl = V(0.97, 0.96, 0.93)
                val base = when {
                    ang < 0.42 * e.pupil -> pupCol
                    ang < 0.52 -> irisCol
                    else -> scl
                }
                lit(base, if (ang > 0.52) 0.9 else 0.5)   // glossy, strongest catchlight on the white
            }
            NOSE -> lit(V(0.18, 0.13, 0.13), 0.6)
            MOUTH -> lit(V(0.30, 0.10, 0.12), 0.0)
            else -> {
                // fur, with a soft dark mouth-line decal painted on the muzzle (closed-mouth lips).
                // the corner (toward the cheek, smaller x) lifts for a smile / drops for a frown.
                var base = V(fur.red / 255.0, fur.green / 255.0, fur.blue / 255.0)
                val mlY = -0.58 + e.mouthCurve * 0.05 + (0.95 - p.x).coerceAtLeast(0.0) * e.mouthCurve * 0.55
                val onMuzzle = p.x > 0.70 && p.z > -0.30 && p.z < 0.48
                if (onMuzzle && kotlin.math.abs(p.y - mlY) < 0.05 && e.mouthOpen < 0.3) {
                    base = base * 0.4
                }
                lit(base, 0.04)
            }
        }
    }

    private fun renderTile(img: BufferedImage, ox: Int, tile: Int, e: Expr, fur: Color, bg: IntArray) {
        val viewW = 4.1; val cX = 0.12; val cY = -0.52
        IntStream.range(0, tile).parallel().forEach { py ->
            for (px in 0 until tile) {
                val wx = cX + (px.toDouble() / tile - 0.5) * viewW
                val wy = cY - (py.toDouble() / tile - 0.5) * viewW
                val ro = V(wx, wy, 4.0)
                var t = 0.0; var hit = false; var hp = ro; var mat = FUR
                var steps = 0
                while (steps < 96 && t < 9.0) {
                    hp = V(ro.x, ro.y, ro.z - t)
                    val h = scene(hp, e)
                    if (h.d < 0.0009) { hit = true; mat = h.mat; break }
                    t += h.d * 0.85
                    steps++
                }
                val rgb = if (hit) {
                    val n = grad(hp, e)
                    val c = shade(hp, n, mat, e, fur)
                    (c[0] shl 16) or (c[1] shl 8) or c[2]
                } else (bg[0] shl 16) or (bg[1] shl 8) or bg[2]
                img.setRGB(ox + px, py, rgb)
            }
        }
    }

    fun run(out: File) {
        val moods = listOf(
            "neutral" to (0.0 to 0.0), "happy" to (0.85 to 0.4), "sad" to (-0.7 to -0.5),
            "angry" to (-0.7 to 0.6), "scared" to (-0.5 to 0.95), "sleepy" to (0.0 to -0.95),
        )
        val tile = 300; val cols = moods.size
        val fur = Color(176, 142, 104)
        val bg = intArrayOf(236, 232, 224)
        val img = BufferedImage(tile * cols, tile, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(bg[0], bg[1], bg[2]); g.fillRect(0, 0, img.width, img.height)
        for ((i, m) in moods.withIndex()) {
            val (name, va) = m
            renderTile(img, i * tile, tile, Expr(va.first, va.second), fur, bg)
            g.color = Color(60, 50, 40); g.font = Font("SansSerif", Font.BOLD, 14); g.drawString(name, i * tile + 10, 22)
        }
        g.dispose(); out.parentFile?.mkdirs(); ImageIO.write(img, "png", out); println("wrote ${out.absolutePath}")
    }
}

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    CreatureBakeSpike.run(File(args.getOrElse(0) { "build/creature-bake.png" }))
}
