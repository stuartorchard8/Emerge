package org.emerge.desktop

import org.emerge.demo.norns.anim.CreatureAction
import org.emerge.demo.norns.world.WorldCreature
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sin

/**
 * Articulated Norn built from the real C2 sprite **parts** (head, torso, thigh/shin/foot ×2,
 * upper/forearm ×2 — decoded + exported in `tools/norns-sprites/`), one part-set per life stage
 * (ages 0–3: baby→adult). Rather than reproduce Creatures' internal pose tables, we treat the
 * parts as a skeleton we pose ourselves: each bone keeps its natural att-chained orientation at
 * rest and gets small swing deltas for the walk. Genuine C2 art, full articulation, real
 * size-progression with age.
 */
object NornRig {
    private class Part(val img: BufferedImage, val pts: Map<String, FloatArray>) {
        fun pt(k: String) = pts[k] ?: floatArrayOf(0f, 0f)
    }
    private val byAge = HashMap<Int, HashMap<String, Part>>()
    private var loaded = false
    val ready: Boolean get() = byAge.values.any { it.containsKey("body") && it.containsKey("head") }

    fun ensure() { if (loaded) return; loaded = true; try { for (a in 0..3) loadAge(a) } catch (e: Exception) { System.err.println("[NornRig] ${e.message}") } }

    private fun loadAge(age: Int) {
        val txt = res("/assets/norns/denali_rig_a$age.txt")?.toString(Charsets.UTF_8) ?: return
        val map = HashMap<String, Part>()
        for (line in txt.lines()) {
            val tok = line.trim().split(" ").filter { it.isNotEmpty() }
            if (tok.size < 4) continue
            val pts = HashMap<String, FloatArray>()
            for (i in 4 until tok.size) {
                val (k, xy) = tok[i].split(":"); val (x, y) = xy.split(",")
                pts[k] = floatArrayOf(x.toFloat(), y.toFloat())
            }
            val bytes = res("/assets/norns/" + tok[1]) ?: continue
            map[tok[0]] = Part(bytes.inputStream().use { ImageIO.read(it) }, pts)
        }
        if (map.containsKey("body")) byAge[age] = map
    }

    private fun res(path: String): ByteArray? =
        NornRig::class.java.getResourceAsStream(path)?.readBytes()
            ?: File("assets$path").takeIf { it.exists() }?.readBytes()
            ?: File("/home/stu/emerge/assets$path").takeIf { it.exists() }?.readBytes()

    private fun ageOf(stage: String) = when (stage) {
        "BABY" -> 0; "CHILD" -> 1; "ADOLESCENT" -> 2; "OLD" -> 3; else -> 3 // ADULT/YOUTH -> 3
    }
    private fun targetHeight(stage: String) = when (stage) {
        "BABY" -> 1.7f; "CHILD" -> 2.1f; "ADOLESCENT" -> 2.5f; "OLD" -> 2.8f; else -> 2.9f
    }

    /** Place [part] so its `start` pivot sits at (jx,jy), rotated by [rot] about that pivot
     *  (rot=0 keeps the sprite's natural assembly). Returns transform + distal tip for chaining. */
    private fun place(jx: Float, jy: Float, part: Part, rot: Float): Triple<AffineTransform, Float, Float> {
        val st = part.pt("start"); val en = part.pt("end")
        val t = AffineTransform()
        t.translate(jx.toDouble(), jy.toDouble())
        t.rotate(rot.toDouble())
        t.translate(-st[0].toDouble(), -st[1].toDouble())
        val tip = java.awt.geom.Point2D.Float(en[0], en[1]); t.transform(tip, tip)
        return Triple(t, tip.x, tip.y)
    }

    fun draw(
        g: java.awt.Graphics2D, c: WorldCreature, action: CreatureAction,
        worldX: Float, worldY: Float, px: (Float) -> Float, py: (Float) -> Float, sx: Float,
    ) {
        val stage = c.biology.lifeStage.name
        val pa = byAge[ageOf(stage)] ?: byAge[3] ?: byAge.values.firstOrNull() ?: return
        val body = pa["body"] ?: return
        val phase = c.ticksLived * 0.35f
        val s = sin(phase)
        val walk = action == CreatureAction.WALK
        val hs = if (walk) 0.4f else 0f
        val aw = if (walk) 0.5f else 0.04f
        fun hip(k: String) = body.pt(k)

        val placed = ArrayList<Pair<Part, AffineTransform>>(12)
        var feetY = 0f
        fun leg(hipk: String, th: String, sh: String, ft: String, sgn: Float) {
            val r0 = sgn * s * hs
            val (t1, tx1, ty1) = place(hip(hipk)[0], hip(hipk)[1], pa[th]!!, r0)
            val r1 = r0 + 0.10f + maxOf(0f, sgn * s) * 0.25f
            val (t2, tx2, ty2) = place(tx1, ty1, pa[sh]!!, r1)
            val (t3, _, ty3) = place(tx2, ty2, pa[ft]!!, r1 * 0.5f)
            placed += pa[th]!! to t1; placed += pa[sh]!! to t2; placed += pa[ft]!! to t3
            feetY = maxOf(feetY, ty3, ty2)
        }
        fun arm(shk: String, ua: String, fa: String, sgn: Float) {
            val r0 = sgn * s * aw
            val (t1, tx1, ty1) = place(hip(shk)[0], hip(shk)[1], pa[ua]!!, r0)
            val (t2, _, _) = place(tx1, ty1, pa[fa]!!, r0 + 0.1f)
            placed += pa[ua]!! to t1; placed += pa[fa]!! to t2
        }
        leg("hipL", "thighL", "shinL", "footL", +1f)
        arm("shL", "uarmL", "farmL", -1f)
        placed += body to AffineTransform()
        leg("hipR", "thighR", "shinR", "footR", -1f)
        arm("shR", "uarmR", "farmR", +1f)
        val head = pa["head"]!!; val hp = body.pt("head"); val neck = head.pt("neck")
        val headDip = if (action == CreatureAction.EAT) maxOf(0f, -s) * 5f else 0f
        val headT = AffineTransform()
        headT.translate(hp[0].toDouble(), (hp[1] + 11f + headDip).toDouble())
        headT.translate(-neck[0].toDouble(), -neck[1].toDouble())
        placed += head to headT

        val rigTop = (hp[1] + 11f) - neck[1]
        val rigH = (feetY - rigTop).coerceAtLeast(1f)
        val scale = (targetHeight(stage) * sx) / rigH
        val centerX = (hip("hipL")[0] + hip("hipR")[0]) / 2f
        val flip = c.facing < 0
        val g0 = AffineTransform()
        g0.translate(px(worldX).toDouble(), py(worldY).toDouble())
        g0.scale((if (flip) -scale else scale).toDouble(), scale.toDouble())
        g0.translate(-centerX.toDouble(), -feetY.toDouble())

        val oldInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        for ((part, t) in placed) {
            val at = AffineTransform(g0); at.concatenate(t)
            g.drawImage(part.img, at, null)
        }
        if (oldInterp != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp)
    }
}
