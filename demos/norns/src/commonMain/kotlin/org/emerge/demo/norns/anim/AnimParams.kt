package org.emerge.demo.norns.anim

/**
 * The tunable dials behind [CreatureAnimation.pose] — every number that shapes the Norn's *look*
 * (proportions, fur shading) and *motion* (swing/bob/dip amplitudes + frequencies).
 *
 * Map-backed with grouped `"group/name"` keys so a viewer/tweaker tool can **enumerate, slider, and
 * export** every dial with zero code changes — adding a new dial is one line here + one read in
 * `pose()`. [DEFAULT] reproduces the hand-tuned baseline *exactly* (gated by `CreatureAnimationTest`);
 * `pose()` reads from the shared immutable [DEFAULT] unless handed a tweaked [tweakable] copy, so the
 * live game pays no allocation and the tool mutates its own instance.
 *
 * Treat [DEFAULT] as read-only. The tool must call [tweakable] for an editable copy.
 */
class AnimParams private constructor(private val values: LinkedHashMap<String, Float>) {

    operator fun get(key: String): Float = values[key] ?: error("unknown anim param: $key")

    operator fun set(key: String, value: Float) {
        require(key in values) { "unknown anim param: $key" }
        values[key] = value
    }

    /** Dial keys in stable, grouped insertion order (so a UI lays them out predictably). */
    val keys: List<String> get() = values.keys.toList()

    /** An independent, editable copy (the baseline, or a snapshot of current edits). */
    fun copy(): AnimParams = AnimParams(LinkedHashMap(values))

    /** Reset this instance's dials back to the baseline. */
    fun reset() { values.clear(); values.putAll(BASELINE) }

    companion object {
        /** The hand-tuned baseline — must equal the literals `pose()` shipped with. */
        private val BASELINE: LinkedHashMap<String, Float> = linkedMapOf(
            // ---- motion: limb / tail swing amplitudes ----
            "swing/legWalk" to 0.14f,
            "swing/armWalk" to 0.16f,
            "swing/armCourt" to 0.14f,
            "swing/armIdle" to 0.02f,
            "swing/tailMove" to 0.08f,
            "swing/tailIdle" to 0.03f,
            // ---- motion: torso bob per action ----
            "bob/rest" to 0.022f,
            "bob/walk" to 0.05f,
            "bob/eat" to 0.03f,
            "bob/pickup" to -0.05f,
            "bob/court" to 0.10f,
            // ---- motion: oscillation frequencies (× phase) ----
            "freq/courtBob" to 1.5f,
            "freq/tail" to 1.3f,
            "freq/ear" to 1.7f,
            "freq/eatHead" to 2.0f,
            // ---- motion: head gesture per action ----
            "head/eatDip" to 0.18f,
            "head/pickupDip" to -0.30f,
            "head/courtLift" to 0.05f,
            "head/turn" to 0.06f,
            "head/leanWalk" to 0.04f,
            "head/leanPickup" to 0.10f,
            "twitch/ear" to 0.04f,
            // ---- colour: paler/darker blends from the base fur ----
            "color/muzzleBlend" to 0.42f,
            "color/bellyBlend" to 0.5f,
            "color/earShade" to 0.82f,
            "color/limbShade" to 0.92f,
            "color/browShade" to 0.42f,
            "color/footShade" to 0.8f,
            // ---- proportions: per-part half-extents (body units, torso ≈ 1 across) ----
            "size/headW" to 0.52f,
            "size/headH" to 0.50f,
            "size/torsoW" to 0.42f,
            "size/torsoH" to 0.50f,
            "size/bellyW" to 0.31f,
            "size/bellyH" to 0.36f,
            "size/earW" to 0.20f,
            "size/earH" to 0.42f,
            "size/legW" to 0.16f,
            "size/legH" to 0.26f,
            "size/armW" to 0.13f,
            "size/armH" to 0.42f,
            "size/muzzleW" to 0.30f,
            "size/muzzleH" to 0.22f,
            "size/eyeScale" to 1.0f,
        )

        /** Shared, read-only baseline used as `pose()`'s default (no per-call allocation). */
        val DEFAULT: AnimParams = AnimParams(LinkedHashMap(BASELINE))

        /** A fresh, editable copy of the baseline (for the tweaker tool). */
        fun tweakable(): AnimParams = AnimParams(LinkedHashMap(BASELINE))

        /** The baseline value of a dial — for "reset this slider" / change detection in a tool. */
        fun defaultOf(key: String): Float = BASELINE[key] ?: error("unknown anim param: $key")

        /** A sensible slider range for a dial, derived from its group (tools need min/max bounds). */
        fun rangeOf(key: String): ClosedFloatingPointRange<Float> {
            val name = key.substringAfter('/')
            return when (key.substringBefore('/')) {
                "swing" -> 0f..0.5f
                "bob" -> -0.2f..0.3f
                "freq" -> 0f..4f
                "twitch" -> 0f..0.2f
                "head" -> -0.6f..0.6f
                "color" -> if (name.endsWith("Shade")) 0f..1.5f else 0f..1f
                "size" -> if (name == "eyeScale") 0.3f..2.5f else 0f..1.2f
                else -> 0f..1f
            }
        }
    }
}
