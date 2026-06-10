package org.emerge.desktop

import org.emerge.demo.norns.morph.MorphCodec
import org.emerge.demo.norns.morph.MorphNode
import org.emerge.demo.norns.world.ActivityType
import org.emerge.demo.norns.world.WorldCreature
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bakes Norns into lit side-profile sprites for the live world — "genes → 3D → 2D sprite" applied to
 * actual creatures, with **no real-time 3D in the engine**: each (creature, mood-bucket) is ray-marched
 * once by [CreatureRenderer] from the creature's own inherited [WorldCreature.morph] and cached as a
 * transparent [BufferedImage]. Mood is read from the creature's drive chemistry → a PAD point, so a Norn
 * wears its actual feelings on the face the authored baseline gave it.
 */
object CreatureBaker {

    internal const val TILE = 128            // bake resolution ≈ on-screen creature size; oversampling above this is wasted
    private const val BUCKET = 0.34            // mood quantisation, to bound the cache

    /** A baked sprite + where the feet sit and how tall the creature is, as fractions of the tile,
     *  so the world renderer can foot-align and scale it to the creature's life-stage height. */
    class Sprite(val img: BufferedImage, val footFracY: Double, val heightFrac: Double)

    private val sprites = HashMap<Long, Sprite>()

    /** When true (live view), a cache miss bakes on a worker thread and returns null until ready, so the
     *  render thread never blocks on a 60-ms bake. Headless renderers leave it false (bake synchronously). */
    var async = false
    private val lock = Any()
    private val inFlight = HashSet<Long>()
    private val lastByCreature = HashMap<Int, Sprite>()   // most-recent baked sprite per creature, for graceful fallback
    private val exec by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "norn-bake").apply { isDaemon = true } } }

    /** The authored baseline genome, loaded from the shipped asset (null if absent). */
    fun baselineGenome(): MorphNode? = runCatching {
        NornParts.res("/assets/norns/norn.morph")?.let { MorphCodec.parse(it.toString(Charsets.UTF_8)) }
    }.getOrNull()

    /** Per-breed fur tint, so colonies read as varied (placeholder until colour is a gene). */
    fun furFor(breed: Int): Color {
        val h = breed * -0x61c88647
        return Color((150 + (h and 0x3F)).coerceIn(60, 235), (116 + ((h ushr 7) and 0x3F)).coerceIn(60, 220), (92 + ((h ushr 14) and 0x3F)).coerceIn(50, 200))
    }

    /** Drive chemistry + activity + the player's Hand → a PAD mood (valence × arousal × dominance). */
    fun moodOf(c: WorldCreature): CreatureRenderer.Mood {
        val eating = c.activity == ActivityType.EATING
        val courting = c.activity == ActivityType.COURTING
        // Hand feelings dominate while they last: a tickle floods pleasure (beams), a slap pain (cowers)
        val pleasure = c.chem.pleasure.toDouble(); val pain = c.chem.pain.toDouble()
        val valence = (0.35 - c.hunger * 0.7 - c.fatigue * 0.35 + (if (eating) 0.5 else 0.0) + (if (courting) 0.55 else 0.0) + pleasure * 0.9 - pain * 0.9).coerceIn(-1.0, 1.0)
        val base = when (c.activity) {
            ActivityType.MOVING -> 0.4; ActivityType.EATING -> 0.3; ActivityType.PICKING_UP -> 0.45
            ActivityType.COURTING -> 0.75; ActivityType.RESTING -> -0.7; ActivityType.IDLE -> -0.2
        }
        val arousal = (base + c.matingUrge * 0.3 + c.hunger * 0.15 + pleasure * 0.25 + pain * 0.55).coerceIn(-1.0, 1.0)
        // dominance: confident when grown + fed + rested + tickled; submissive when young, hungry, tired, hurt
        val maturity = when (c.biology.lifeStage.name) { "ADULT", "OLD" -> 0.25; "BABY", "CHILD" -> -0.25; else -> 0.0 }
        val dominance = (0.05 + maturity - c.hunger * 0.55 - c.fatigue * 0.4 + (if (courting) 0.35 else 0.0) + pleasure * 0.4 - pain * 0.7).coerceIn(-1.0, 1.0)
        return CreatureRenderer.Mood(valence, arousal, dominance)
    }

    /** The baked sprite for [c] at its current (bucketed) mood, or **null** if it isn't ready yet
     *  ([async] mode): the bake is enqueued on a worker and the caller should draw a placeholder; it lands
     *  in the cache for a later frame. Synchronous (always returns a sprite) when [async] is false. */
    fun spriteFor(c: WorldCreature): Sprite? {
        val mood = moodOf(c)
        val vb = (mood.v / BUCKET).roundToInt(); val ab = (mood.a / BUCKET).roundToInt(); val db = (mood.d / BUCKET).roundToInt()
        val key = (c.id.toLong() shl 16) or ((vb + 8).toLong() shl 10) or ((ab + 8).toLong() shl 5) or (db + 8).toLong()
        val bm = CreatureRenderer.Mood(vb * BUCKET, ab * BUCKET, db * BUCKET); val morph = c.morph; val fur = furFor(c.breed)
        if (!async) {
            synchronized(lock) { sprites[key]?.let { return it } }
            val s = bake(morph, bm, fur); synchronized(lock) { sprites[key] = s; lastByCreature[c.id] = s }; return s
        }
        synchronized(lock) {
            sprites[key]?.let { lastByCreature[c.id] = it; return it }       // exact mood ready
            if (!inFlight.add(key)) return lastByCreature[c.id]              // already baking → show last good
        }
        exec.submit { val s = bake(morph, bm, fur); synchronized(lock) { sprites[key] = s; lastByCreature[c.id] = s; inFlight.remove(key) } }
        return synchronized(lock) { lastByCreature[c.id] }                  // last good while the new mood bakes (null only pre-first-bake)
    }

    /** Drop all cached sprites (e.g. after loading a different colony). */
    fun clear() = synchronized(lock) { sprites.clear(); lastByCreature.clear(); inFlight.clear() }

    /** Drop cached sprites for creatures no longer alive (called by the renderer each frame). */
    fun evictDead(aliveIds: Set<Int>) = synchronized(lock) {
        sprites.keys.retainAll { (it shr 16).toInt() in aliveIds }
        lastByCreature.keys.retainAll { it in aliveIds }
    }

    /** A creature baked as the denali rig's part-set + a rig (anchors from the genome's joints, motion
     *  from denali's seeded animation) — so it can be composited + animated by [NornCompositor]. */
    class RigBake(val parts: Map<String, NornParts.Part>, val rig: NornRigDef)

    private const val RIG_TARGET_PX = 220.0    // whole-creature bake size; parts scale proportionally
    private const val PART_PAD = 4
    private val LIMBS = setOf("thigh", "shin", "foot", "uarm", "farm")

    /** Bake [genome] into the denali rig's parts. Limbs are mirrored nodes → split near(+z)=R / far(−z)=L
     *  into the rig's L/R slots; the head is everything that isn't the body or a limb. Joints (hip/knee/
     *  ankle, shoulder/elbow/hand, neck) come from the genome geometry and become the rig's anchors. */
    fun bakeRig(genome: MorphNode, fur: Color): RigBake {
        val baked = CreatureRenderer.Baked(genome, CreatureRenderer.Mood(0.0, 0.0, 0.0))
        val bones = baked.bones()
        fun bone(node: String, pos: Boolean?) = bones.firstOrNull { it.node == node && (pos == null || (it.center.z > 0.0) == pos) }
        val bb = baked.bounds()
        val s = RIG_TARGET_PX / maxOf(0.5, bb[3] - bb[2])
        val body = bone("body", null) ?: return RigBake(emptyMap(), NornRigDef.default(emptyMap()))
        val head = bone("head", null)
        fun top(c: CreatureRenderer.Bone, toward: CreatureRenderer.V) = c.center + (toward - c.center).norm() * maxOf(c.rx, c.ry)
        fun farEnd(c: CreatureRenderer.Bone, from: CreatureRenderer.V) = c.center + (c.center - from).norm() * maxOf(c.rx, c.ry)

        val parts = LinkedHashMap<String, NornParts.Part>()
        fun bakePart(id: String, include: (CreatureRenderer.Bone) -> Boolean, atts: Map<String, CreatureRenderer.V>) {
            val pb = baked.partBounds(include)
            if (pb[1] < pb[0]) return
            val originX = pb[0] - PART_PAD / s; val originY = pb[3] + PART_PAD / s
            val w = ((pb[1] - pb[0]) * s).toInt() + 2 * PART_PAD; val h = ((pb[3] - pb[2]) * s).toInt() + 2 * PART_PAD
            val img = CreatureRenderer.renderPart(baked, include, fur, w, h, originX, originY, s)
            val pts = HashMap<String, FloatArray>()
            for ((k, j) in atts) pts[k] = floatArrayOf(((j.x - originX) * s).toFloat(), ((originY - j.y) * s).toFloat())
            parts[id] = NornParts.Part(id, img, w, h, pts)
        }

        val neck = if (head != null) top(head, body.center) else body.center
        val bodyAtts = LinkedHashMap<String, CreatureRenderer.V>(); bodyAtts["head"] = neck
        for ((sfx, pos) in listOf("R" to true, "L" to false)) {
            bone("thigh", pos)?.let { thigh ->
                val hip = top(thigh, body.center); bodyAtts["hip$sfx"] = hip
                val shin = bone("shin", pos); val foot = bone("foot", pos)
                val knee = if (shin != null) top(shin, thigh.center) else farEnd(thigh, body.center)
                bakePart("thigh$sfx", { it.node == "thigh" && (it.center.z > 0) == pos }, mapOf("start" to hip, "end" to knee))
                if (shin != null) {
                    val ankle = if (foot != null) top(foot, shin.center) else farEnd(shin, thigh.center)
                    bakePart("shin$sfx", { it.node == "shin" && (it.center.z > 0) == pos }, mapOf("start" to knee, "end" to ankle))
                    if (foot != null) bakePart("foot$sfx", { it.node == "foot" && (it.center.z > 0) == pos }, mapOf("start" to ankle))
                }
            }
            bone("uarm", pos)?.let { uarm ->
                val sh = top(uarm, body.center); bodyAtts["sh$sfx"] = sh
                val farm = bone("farm", pos)
                val elbow = if (farm != null) top(farm, uarm.center) else farEnd(uarm, body.center)
                bakePart("uarm$sfx", { it.node == "uarm" && (it.center.z > 0) == pos }, mapOf("start" to sh, "end" to elbow))
                if (farm != null) bakePart("farm$sfx", { it.node == "farm" && (it.center.z > 0) == pos }, mapOf("start" to elbow, "end" to farEnd(farm, uarm.center)))
            }
        }
        if (head != null) bakePart("head", { it.node !in LIMBS && it.node != "body" }, mapOf("neck" to neck))
        bakePart("body", { it.node == "body" }, bodyAtts)
        return RigBake(parts, NornRigDef.default(parts))
    }

    private fun bake(genome: MorphNode, mood: CreatureRenderer.Mood, fur: Color): Sprite {
        val b = CreatureRenderer.Baked(genome, mood)
        val img = BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB)
        CreatureRenderer.render(b, fur, img, 0, TILE, 0, transparent = true)
        val fr = CreatureRenderer.frame(b, TILE)
        val bb = b.bounds()                                   // minX, maxX, minY, maxY
        val footFracY = fr.screenY(bb[2]) / TILE              // feet = lowest world-y
        val heightFrac = max(1e-3, (bb[3] - bb[2]) / fr.span)
        return Sprite(img, footFracY, heightFrac)
    }
}
