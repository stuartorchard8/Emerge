package org.emerge.desktop

import org.emerge.demo.norns.anim.CreatureAction
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.sin

/**
 * A **data-driven, fully-editable Norn rig**: the creature composited from the ripped sprite parts
 * ([NornParts]) by *procedural* animation — no baked frames. Each [RigPart] names a sprite, a
 * parent, the **anchor** point it attaches to on that parent, its own pivot, a rest angle, a draw
 * order, and a per-action [JointAnim] (a rotation `bias + sign·sin(phase·freq + phase)·amp`). Global
 * per-action body motion (bob/lean/hop) lives in [GlobalAnim].
 *
 * Seeded from the breed's `.att` points so it opens as a correct standing Norn, then every number is
 * tweakable in [NornsAnimViewer] and the whole rig serialises to text ([toText]/[parse]) so a look +
 * its animations can be saved, reloaded, and iterated.
 */
class JointAnim(
    var bias: Float = 0f,    // static rotation for this action (e.g. head pecks down to eat)
    var amp: Float = 0f,     // oscillation amplitude
    var freq: Float = 1f,    // oscillation frequency (× phase)
    var phase: Float = 0f,   // oscillation phase offset
    var sign: Float = 1f,    // +1 / −1 (limbs swing in anti-phase)
) {
    fun copy() = JointAnim(bias, amp, freq, phase, sign)
    fun rot(phaseT: Float): Float = bias + sign * sin(phaseT * freq + phase) * amp
}

class GlobalAnim(
    var bobAmp: Float = 0f,
    var bobFreq: Float = 1f,
    var lean: Float = 0f,     // constant forward lean (radians, about the feet)
    var hopAmp: Float = 0f,   // screen-space lift (world units)
    var hopFreq: Float = 1.7f,
) {
    fun copy() = GlobalAnim(bobAmp, bobFreq, lean, hopAmp, hopFreq)
}

class RigPart(
    val id: String,
    var sprite: String,
    val parent: String?,
    var anchorU: Float, var anchorV: Float,    // attach point ON THE PARENT, as a FRACTION of the parent sprite's w/h
    var pivotU: Float, var pivotV: Float,       // this part's own pivot, as a fraction of its own sprite's w/h
    var restAngle: Float = 0f,
    var z: Int = 0,
    val anim: MutableMap<CreatureAction, JointAnim> = HashMap(),
) {
    fun animFor(a: CreatureAction): JointAnim = anim.getOrPut(a) { JointAnim() }
}

class Placed(val id: String, val img: BufferedImage, val transform: AffineTransform, val z: Int)

class NornRigDef(val parts: MutableList<RigPart>, val global: MutableMap<CreatureAction, GlobalAnim>) {

    fun part(id: String): RigPart? = parts.firstOrNull { it.id == id }
    fun globalFor(a: CreatureAction): GlobalAnim = global.getOrPut(a) { GlobalAnim() }

    /** Forward-kinematics: resolve every part to a body-local transform for [action] at [phaseT].
     *  Parts are ordered parents-first, so a child reads its parent's already-computed transform. */
    fun pose(sprites: Map<String, NornParts.Part>, action: CreatureAction, phaseT: Float): List<Placed> {
        val g = global[action] ?: GlobalAnim()
        val bob = -abs(sin(phaseT * g.bobFreq)) * g.bobAmp
        val partOf = HashMap<String, NornParts.Part>()
        for (rp in parts) (sprites[rp.sprite] ?: sprites[rp.id])?.let { partOf[rp.id] = it }
        val worldBy = HashMap<String, AffineTransform>()
        val out = ArrayList<Placed>(parts.size)
        for (rp in parts) {
            val self = partOf[rp.id] ?: continue
            val world: AffineTransform
            if (rp.parent == null) {
                world = AffineTransform().apply { translate(0.0, bob.toDouble()) }
            } else {
                val parent = partOf[rp.parent]                                  // denormalise U/V → px
                world = AffineTransform(worldBy[rp.parent] ?: AffineTransform())
                world.translate((rp.anchorU * (parent?.w ?: 1)).toDouble(), (rp.anchorV * (parent?.h ?: 1)).toDouble())
                world.rotate((rp.restAngle + rp.animFor(action).rot(phaseT)).toDouble())
                world.translate((-rp.pivotU * self.w).toDouble(), (-rp.pivotV * self.h).toDouble())
            }
            worldBy[rp.id] = world
            out += Placed(rp.id, self.img, world, rp.z)
        }
        return out
    }

    fun toText(): String {
        val sb = StringBuilder("# norn rig — sprite-part compositor; coords: normalized (fraction of sprite w/h)\n")
        for (p in parts) {
            sb.append("part ${p.id} sprite=${p.sprite} parent=${p.parent ?: "-"} ")
                .append("anchor=${f(p.anchorU)},${f(p.anchorV)} pivot=${f(p.pivotU)},${f(p.pivotV)} ")
                .append("rest=${f(p.restAngle)} z=${p.z}\n")
        }
        for (p in parts) for ((a, j) in p.anim) {
            if (j.bias == 0f && j.amp == 0f) continue
            sb.append("anim ${p.id} $a bias=${f(j.bias)} amp=${f(j.amp)} freq=${f(j.freq)} phase=${f(j.phase)} sign=${f(j.sign)}\n")
        }
        for ((a, gA) in global) {
            sb.append("global $a bob=${f(gA.bobAmp)} bobFreq=${f(gA.bobFreq)} lean=${f(gA.lean)} hop=${f(gA.hopAmp)} hopFreq=${f(gA.hopFreq)}\n")
        }
        return sb.toString()
    }

    companion object {
        private fun f(v: Float) = "%.4f".format(v)
        private fun kv(tokens: List<String>) = tokens.mapNotNull { t -> t.split("=").takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        private fun xy(s: String?): Pair<Float, Float>? = s?.split(",")?.takeIf { it.size == 2 }?.let { it[0].toFloat() to it[1].toFloat() }

        /** Topology of the Norn body plan: (id, parent, anchorKeyOnParent, ownPivotKey, z-order).
         *  z is **0-centred**: body = 0, the far (L) side is negative (behind), the near (R) side
         *  positive (in front), paired parts as exact negatives so symmetry mirrors z by negation. */
        private val TOPO = listOf(
            arrayOf("body", null, null, null, "0"),
            arrayOf("uarmL", "body", "shL", "start", "-2"),
            arrayOf("farmL", "uarmL", "end", "start", "-2"),
            arrayOf("thighL", "body", "hipL", "start", "-4"),
            arrayOf("shinL", "thighL", "end", "start", "-4"),
            arrayOf("footL", "shinL", "end", "start", "-6"),
            arrayOf("head", "body", "head", "neck", "1"),
            arrayOf("uarmR", "body", "shR", "start", "2"),
            arrayOf("farmR", "uarmR", "end", "start", "2"),
            arrayOf("thighR", "body", "hipR", "start", "4"),
            arrayOf("shinR", "thighR", "end", "start", "4"),
            arrayOf("footR", "shinR", "end", "start", "6"),
        )

        /** Build the default rig for a loaded part-set: anchors/pivots from the `.att` points, plus a
         *  sensible per-action animation seed (mirrors the look the hardcoded NornRig shipped). */
        fun default(sprites: Map<String, NornParts.Part>): NornRigDef {
            val parts = ArrayList<RigPart>()
            for (row in TOPO) {
                val id = row[0]!!
                val parent = row[1]
                val anchorKey = row[2]
                val pivotKey = row[3]
                val z = row[4]!!.toInt()
                val self = sprites[id] ?: continue
                val pp = parent?.let { sprites[it] }
                val anchor = if (pp != null && anchorKey != null) pp.pt(anchorKey) else floatArrayOf(0f, 0f)
                val pivot = if (pivotKey != null) self.pt(pivotKey) else floatArrayOf(0f, 0f)
                val au = if (pp != null) anchor[0] / pp.w else 0f
                val av = if (pp != null) anchor[1] / pp.h else 0f
                parts += RigPart(id, id, parent, au, av, pivot[0] / self.w, pivot[1] / self.h, 0f, z)
            }
            val def = NornRigDef(parts, HashMap())
            seedAnimation(def)
            return def
        }

        private fun seedAnimation(def: NornRigDef) {
            fun j(id: String, a: CreatureAction, bias: Float = 0f, amp: Float = 0f, sign: Float = 1f, freq: Float = 1f) {
                def.part(id)?.anim?.put(a, JointAnim(bias, amp, freq, 0f, sign))
            }
            // WALK — legs stride, arms counter-swing
            j("thighL", CreatureAction.WALK, amp = 0.38f, sign = 1f); j("thighR", CreatureAction.WALK, amp = 0.38f, sign = -1f)
            j("shinL", CreatureAction.WALK, amp = 0.16f, sign = 1f); j("shinR", CreatureAction.WALK, amp = 0.16f, sign = -1f)
            j("uarmL", CreatureAction.WALK, amp = 0.42f, sign = -1f); j("uarmR", CreatureAction.WALK, amp = 0.42f, sign = 1f)
            // REST — gentle arm sway
            j("uarmL", CreatureAction.REST, amp = 0.06f, sign = -1f); j("uarmR", CreatureAction.REST, amp = 0.06f, sign = 1f)
            // COURT — arms wave, chin up
            j("uarmL", CreatureAction.COURT, amp = 0.35f, sign = -1f); j("uarmR", CreatureAction.COURT, amp = 0.35f, sign = 1f)
            j("head", CreatureAction.COURT, bias = -0.25f)
            // EAT — peck down, front arm to mouth
            j("head", CreatureAction.EAT, bias = 0.42f); j("uarmR", CreatureAction.EAT, bias = -0.5f); j("farmR", CreatureAction.EAT, bias = -0.35f)
            // PICK_UP — bend down, front arm to ground
            j("head", CreatureAction.PICK_UP, bias = 0.72f); j("uarmR", CreatureAction.PICK_UP, bias = 0.35f); j("farmR", CreatureAction.PICK_UP, bias = 0.25f)
            // global body motion per action
            def.global[CreatureAction.WALK] = GlobalAnim(bobAmp = 0.4f, bobFreq = 1f)
            def.global[CreatureAction.REST] = GlobalAnim(bobAmp = 0.15f, bobFreq = 1f)
            def.global[CreatureAction.EAT] = GlobalAnim(bobAmp = 0.15f, lean = 0.10f)
            def.global[CreatureAction.PICK_UP] = GlobalAnim(bobAmp = 0f, lean = 0.22f)
            def.global[CreatureAction.COURT] = GlobalAnim(bobAmp = 0.15f, hopAmp = 0.28f, hopFreq = 1.7f)
        }

        /** Parse a rig previously written by [toText] onto a fresh [default] for [sprites]
         *  (auto-converting legacy pixel-coord files to normalized on read). */
        fun parse(text: String, sprites: Map<String, NornParts.Part>): NornRigDef {
            val def = default(sprites)
            // Legacy rigs stored anchor/pivot in pixels; new ones are normalised (fraction of sprite
            // w/h). Detect by the header marker and convert pixels → fractions on read.
            val normalized = text.contains("coords: normalized")
            for (line in text.lines()) {
                val tok = line.trim().split(" ").filter { it.isNotEmpty() }
                if (tok.isEmpty() || tok[0].startsWith("#")) continue
                when (tok[0]) {
                    "part" -> {
                        val p = def.part(tok.getOrNull(1) ?: continue) ?: continue
                        val m = kv(tok.drop(2))
                        m["sprite"]?.let { p.sprite = it }
                        val parentW = (p.parent?.let { sprites[it] }?.w ?: 1).toFloat()
                        val parentH = (p.parent?.let { sprites[it] }?.h ?: 1).toFloat()
                        val selfW = ((sprites[p.sprite] ?: sprites[p.id])?.w ?: 1).toFloat()
                        val selfH = ((sprites[p.sprite] ?: sprites[p.id])?.h ?: 1).toFloat()
                        xy(m["anchor"])?.let {
                            p.anchorU = if (normalized) it.first else it.first / parentW
                            p.anchorV = if (normalized) it.second else it.second / parentH
                        }
                        xy(m["pivot"])?.let {
                            p.pivotU = if (normalized) it.first else it.first / selfW
                            p.pivotV = if (normalized) it.second else it.second / selfH
                        }
                        m["rest"]?.toFloatOrNull()?.let { p.restAngle = it }
                        m["z"]?.toIntOrNull()?.let { p.z = it }
                    }
                    "anim" -> {
                        val p = def.part(tok.getOrNull(1) ?: continue) ?: continue
                        val a = runCatching { CreatureAction.valueOf(tok.getOrNull(2) ?: "") }.getOrNull() ?: continue
                        val m = kv(tok.drop(3))
                        p.anim[a] = JointAnim(
                            m["bias"]?.toFloatOrNull() ?: 0f, m["amp"]?.toFloatOrNull() ?: 0f,
                            m["freq"]?.toFloatOrNull() ?: 1f, m["phase"]?.toFloatOrNull() ?: 0f,
                            m["sign"]?.toFloatOrNull() ?: 1f,
                        )
                    }
                    "global" -> {
                        val a = runCatching { CreatureAction.valueOf(tok.getOrNull(1) ?: "") }.getOrNull() ?: continue
                        val m = kv(tok.drop(2))
                        def.global[a] = GlobalAnim(
                            m["bob"]?.toFloatOrNull() ?: 0f, m["bobFreq"]?.toFloatOrNull() ?: 1f,
                            m["lean"]?.toFloatOrNull() ?: 0f, m["hop"]?.toFloatOrNull() ?: 0f,
                            m["hopFreq"]?.toFloatOrNull() ?: 1.7f,
                        )
                    }
                }
            }
            return def
        }
    }
}
