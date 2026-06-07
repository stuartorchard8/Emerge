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
 * upper/forearm ×2 — decoded + exported in `tools/norns-sprites/`). Rather than reproduce
 * Creatures' internal pose tables, we treat the parts as a skeleton we pose ourselves: each bone
 * is oriented via its attachment vector and driven by a walk cycle, then the real sprite segment
 * is rotated about its joint with a smooth transform. Genuine C2 art, full articulation.
 */
object NornRig {
    private class Part(val img: BufferedImage, val pts: Map<String, FloatArray>) {
        fun pt(k: String) = pts[k] ?: floatArrayOf(0f, 0f)
    }
    private val parts = HashMap<String, Part>()
    private var loaded = false
    val ready: Boolean get() = parts.containsKey("body") && parts.containsKey("head")

    fun ensure() { if (loaded) return; loaded = true; try { load() } catch (e: Exception) { System.err.println("[NornRig] ${e.message}") } }

    private fun load() {
        val txt = res("/assets/norns/denali_rig.txt")?.toString(Charsets.UTF_8) ?: return
        for (line in txt.lines()) {
            val tok = line.trim().split(" ").filter { it.isNotEmpty() }
            if (tok.size < 4) continue
            val name = tok[0]; val imgPath = "/assets/norns/" + tok[1]
            val pts = HashMap<String, FloatArray>()
            for (i in 4 until tok.size) {
                val (k, xy) = tok[i].split(":"); val (x, y) = xy.split(",")
                pts[k] = floatArrayOf(x.toFloat(), y.toFloat())
            }
            val bytes = res(imgPath) ?: continue
            parts[name] = Part(bytes.inputStream().use { ImageIO.read(it) }, pts)
        }
    }

    private fun res(path: String): ByteArray? =
        NornRig::class.java.getResourceAsStream(path)?.readBytes()
            ?: File("assets$path").takeIf { it.exists() }?.readBytes()
            ?: File("/home/stu/emerge/assets$path").takeIf { it.exists() }?.readBytes()

    private fun lifeScale(stage: String) = when (stage) {
        "BABY" -> 0.55f; "CHILD" -> 0.72f; "ADOLESCENT" -> 0.86f; "OLD" -> 0.96f; else -> 1.0f
    }

    /** Place [part] so its `start` pivot sits at joint (jx,jy), rotated by [rot] about that pivot
     *  (rot=0 keeps the sprite's natural orientation → the parts assemble exactly as the art
     *  intends; non-zero rot articulates). Returns the transform and the distal tip for chaining. */
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
        val body = parts["body"] ?: return
        val phase = c.ticksLived * 0.35f
        val s = sin(phase)
        val walk = action == CreatureAction.WALK
        val hs = if (walk) 0.4f else 0f
        val aw = if (walk) 0.5f else 0.04f
        fun hip(k: String) = body.pt(k)

        val placed = ArrayList<Pair<Part, AffineTransform>>(12)
        var feetY = 0f
        // a leg: thigh -> shin -> foot, swung at the hip by sgn (deltas around the natural pose)
        fun leg(hipk: String, th: String, sh: String, ft: String, sgn: Float) {
            val r0 = sgn * s * hs
            val (t1, tx1, ty1) = place(hip(hipk)[0], hip(hipk)[1], parts[th]!!, r0)
            val r1 = r0 + 0.10f + maxOf(0f, sgn * s) * 0.25f
            val (t2, tx2, ty2) = place(tx1, ty1, parts[sh]!!, r1)
            val (t3, _, ty3) = place(tx2, ty2, parts[ft]!!, r1 * 0.5f)
            placed += parts[th]!! to t1; placed += parts[sh]!! to t2; placed += parts[ft]!! to t3
            feetY = maxOf(feetY, ty3, ty2)
        }
        fun arm(shk: String, ua: String, fa: String, sgn: Float) {
            val r0 = sgn * s * aw
            val (t1, tx1, ty1) = place(hip(shk)[0], hip(shk)[1], parts[ua]!!, r0)
            val (t2, _, _) = place(tx1, ty1, parts[fa]!!, r0 + 0.1f)
            placed += parts[ua]!! to t1; placed += parts[fa]!! to t2
        }
        // far side (L) behind the body, near side (R) in front
        leg("hipL", "thighL", "shinL", "footL", +1f)
        arm("shL", "uarmL", "farmL", -1f)
        placed += body to AffineTransform()
        leg("hipR", "thighR", "shinR", "footR", -1f)
        arm("shR", "uarmR", "farmR", +1f)
        // head sits on the body's neck point, pulled down to overlap; dips when eating
        val head = parts["head"]!!; val hp = body.pt("head"); val neck = head.pt("neck")
        val headDip = if (action == CreatureAction.EAT) maxOf(0f, -s) * 5f else 0f
        val headT = AffineTransform()
        headT.translate(hp[0].toDouble(), (hp[1] + 11f + headDip).toDouble())
        headT.translate(-neck[0].toDouble(), -neck[1].toDouble())
        placed += head to headT

        // global: ground anchor + scale (+ horizontal flip for facing), feet on the ground
        val ls = lifeScale(c.biology.lifeStage.name)
        val rigTop = (hp[1] + 11f) - neck[1]
        val rigH = (feetY - rigTop).coerceAtLeast(1f)
        val scale = (2.9f * ls * sx) / rigH
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
