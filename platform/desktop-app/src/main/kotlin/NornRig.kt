package org.emerge.desktop

import org.emerge.demo.norns.anim.CreatureAction
import org.emerge.demo.norns.world.WorldCreature
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sin

/**
 * Articulated Norn built from the real C2 sprite **parts** (head, torso, thigh/shin/foot ×2,
 * upper/forearm ×2 — decoded + exported in `tools/norns-sprites/`), one part-set per life-stage
 * age. Each age's parts are exported at its base pose (older = upright side / pose 2; babies +
 * children = crawl / pose 0), then we treat them as a skeleton: at rest the parts assemble exactly
 * as the breed art intends (the head's neck sits on the body's neck via the real ATT points), and
 * the joints get **continuous** swing for the walk — so motion interpolates smoothly rather than
 * cutting between frames. Genuine C2 art, smooth articulation, real size + crawl by age.
 */
object NornRig {
    private class Part(val img: BufferedImage, val pts: Map<String, FloatArray>) {
        fun pt(k: String) = pts[k] ?: floatArrayOf(0f, 0f)
    }
    // base breeds (genuinely different ripped sprite sets) keyed by name → age → parts
    private val bases = HashMap<String, HashMap<Int, HashMap<String, Part>>>()
    private var loaded = false
    val ready: Boolean get() = bases.values.any { it.values.any { m -> m.containsKey("body") && m.containsKey("head") } }

    // The breed roster: each creature's heritable breed indexes this. Genuinely different ripped
    // species (no recolours): denali (blonde), bavaria (mint/silver crest), bilba (green/purple),
    // calypso (orange/green), cloud (soft blue). Add more by ripping + baking and extending this.
    private val TABLE = arrayOf("denali", "bavaria", "bilba", "calypso", "cloud", "foxi", "dog", "duck", "daffodil")
    val BREEDS = TABLE.size

    fun ensure() { if (loaded) return; loaded = true; try { for (b in TABLE) loadBreed(b) } catch (e: Exception) { System.err.println("[NornRig] ${e.message}") } }

    private fun loadBreed(name: String) {
        val perAge = HashMap<Int, HashMap<String, Part>>()
        for (age in 0..3) {
            val txt = res("/assets/norns/${name}_rig_a$age.txt")?.toString(Charsets.UTF_8) ?: continue
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
            if (map.containsKey("body")) perAge[age] = map
        }
        if (perAge.isNotEmpty()) bases[name] = perAge
    }

    private fun res(path: String): ByteArray? =
        NornRig::class.java.getResourceAsStream(path)?.readBytes()
            ?: File("assets$path").takeIf { it.exists() }?.readBytes()
            ?: File(System.getProperty("user.dir")).parentFile?.parentFile?.resolve("assets$path")?.takeIf { it.exists() }?.readBytes()

    // BABY/CHILD use their own (crawl) art; ADOLESCENT reuses the adult art scaled down (the age-2
    // sprites are drawn crouched/curled with no clean upright), ADULT/OLD use the adult art.
    private fun ageOf(stage: String) = when (stage) { "BABY" -> 0; "CHILD" -> 1; else -> 3 }

    private fun partsFor(breedIdx: Int, age: Int): HashMap<String, Part>? {
        val name = TABLE[breedIdx % TABLE.size]
        val perAge = bases[name] ?: bases["denali"] ?: bases.values.firstOrNull() ?: return null
        return perAge[age] ?: perAge[3] ?: perAge.values.firstOrNull()
    }
    // a clear size progression — babies ~5x smaller than adults so they read as babies, not
    // shrunken adults — growing up through childhood
    private fun targetHeight(stage: String) = when (stage) {
        "BABY" -> 0.6f; "CHILD" -> 1.3f; "ADOLESCENT" -> 2.2f; "OLD" -> 2.85f; else -> 2.95f
    }

    /** Place [part] so its `start` pivot sits at (jx,jy), rotated by [rot] about that pivot (rot=0
     *  keeps the natural att-chained assembly). Returns transform + distal tip for chaining. */
    private fun place(jx: Float, jy: Float, part: Part, rot: Float): Triple<AffineTransform, Float, Float> {
        val st = part.pt("start"); val en = part.pt("end")
        val t = AffineTransform()
        t.translate(jx.toDouble(), jy.toDouble()); t.rotate(rot.toDouble()); t.translate(-st[0].toDouble(), -st[1].toDouble())
        val tip = java.awt.geom.Point2D.Float(en[0], en[1]); t.transform(tip, tip)
        return Triple(t, tip.x, tip.y)
    }

    fun draw(
        g: java.awt.Graphics2D, c: WorldCreature, action: CreatureAction,
        worldX: Float, worldY: Float, px: (Float) -> Float, py: (Float) -> Float, sx: Float,
    ) {
        val stage = c.biology.lifeStage.name
        val pa = partsFor(c.breed.mod(BREEDS), ageOf(stage)) ?: return
        val body = pa["body"] ?: return
        val phase = c.ticksLived * 0.085f   // ÷4 to match the slowed (quarter-speed) movement
        val s = sin(phase)
        val walk = action == CreatureAction.WALK
        val court = action == CreatureAction.COURT
        val hs = if (walk) 0.38f else if (court) 0.12f else 0f          // hip swing
        val aw = if (walk) 0.42f else if (court) 0.32f else 0.05f       // shoulder swing
        fun hip(k: String) = body.pt(k)

        val placed = ArrayList<Pair<Part, AffineTransform>>(12)
        var feetY = 0f
        fun leg(hipk: String, th: String, sh: String, ft: String, sgn: Float) {
            val r0 = sgn * s * hs
            val (t1, tx1, ty1) = place(hip(hipk)[0], hip(hipk)[1], pa[th]!!, r0)
            val r1 = r0 + maxOf(0f, sgn * s) * 0.3f          // knee bends on the forward swing
            val (t2, tx2, ty2) = place(tx1, ty1, pa[sh]!!, r1)
            val (t3, _, ty3) = place(tx2, ty2, pa[ft]!!, r1 * 0.4f)
            placed += pa[th]!! to t1; placed += pa[sh]!! to t2; placed += pa[ft]!! to t3
            feetY = maxOf(feetY, ty3, ty2)
        }
        fun arm(shk: String, ua: String, fa: String, sgn: Float) {
            val r0 = sgn * s * aw
            val (t1, tx1, ty1) = place(hip(shk)[0], hip(shk)[1], pa[ua]!!, r0)
            val (t2, _, _) = place(tx1, ty1, pa[fa]!!, r0 + 0.08f)
            placed += pa[ua]!! to t1; placed += pa[fa]!! to t2
        }
        // upper body lifts a hair on the up-beat (feet stay planted → no float); courting bounces
        val bob = -abs(s) * (if (court) 0.7f else if (walk) 0.4f else 0.15f)
        // far side (L) behind the body, near side (R) in front; legs counter-swing the arms
        leg("hipL", "thighL", "shinL", "footL", +1f)
        arm("shL", "uarmL", "farmL", -1f)
        val bodyT = AffineTransform(); bodyT.translate(0.0, bob.toDouble())
        placed += body to bodyT
        leg("hipR", "thighR", "shinR", "footR", -1f)
        arm("shR", "uarmR", "farmR", +1f)
        // head rides on the body's neck point (real ATT), rotating about the neck: dips to the
        // ground to eat, lifts a touch to court
        val head = pa["head"]!!; val hp = body.pt("head"); val neck = head.pt("neck")
        val headLook = when (action) { CreatureAction.EAT -> 0.5f; CreatureAction.COURT -> -0.18f; else -> 0f }
        val headT = AffineTransform()
        headT.translate(hp[0].toDouble(), (hp[1] + bob).toDouble())
        headT.rotate(headLook.toDouble())
        headT.translate(-neck[0].toDouble(), -neck[1].toDouble())
        placed += head to headT

        val rigTop = (hp[1] + bob) - neck[1]
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
