package org.emerge.demo.drockets

import kotlin.math.roundToLong

data class GenomeComponent(
    private val encodedGenes: Map<String, Int> = emptyMap(),
) {
    enum class GenomeKey(val wireName: String) {
        AI_WALK_MIN_TICKS("ai_walk_min_ticks"),
        AI_WALK_MAX_TICKS("ai_walk_max_ticks"),
        AI_CHARGE_TICKS("ai_charge_ticks"),
        AI_FUEL_TICKS("ai_fuel_ticks"),
        AI_SPIN_RAW("ai_spin_raw"),
        AI_THRUST_RAW("ai_thrust_raw"),
        COLOR_H("color_h"),
        COLOR_S("color_s"),
        COLOR_V("color_v"),
        FIRE_COLOR_H("fire_color_h"),
        FIRE_COLOR_S("fire_color_s"),
        FIRE_COLOR_V("fire_color_v"),
    }

    fun decodedOrDefault(key: GenomeKey): Int {
        val spec = GENE_SPECS[key] ?: return 0
        return decodeRanged(key, encodedGenes[key.wireName]) ?: spec.fallback
    }

    fun decodedOrNull(key: GenomeKey): Int? = decodeRanged(key, encodedGenes[key.wireName])

    fun hasEncoded(key: GenomeKey): Boolean = encodedGenes.containsKey(key.wireName)
    fun hasEncodedRawKey(rawKey: String): Boolean = encodedGenes.containsKey(rawKey)

    /** Encoded gene payload intended for reproduction/mutation logic. */
    fun encodedGenesForReproduction(): Map<String, Int> = encodedGenes

    /** Encoded gene payload intended for persistence/snapshotting. */
    fun encodedGenesForPersistence(): Map<String, Int> = encodedGenes

    companion object {
        private const val GENE_UINT_RANGE = 4294967295.0

        data class GeneSpec(
            val min: Int,
            val max: Int,
            val fallback: Int,
        )

        val GENE_SPECS: Map<GenomeKey, GeneSpec> = linkedMapOf(
            GenomeKey.AI_WALK_MIN_TICKS to GeneSpec(min = 1, max = 6_000, fallback = 120),
            GenomeKey.AI_WALK_MAX_TICKS to GeneSpec(min = 1, max = 6_000, fallback = 600),
            GenomeKey.AI_CHARGE_TICKS to GeneSpec(min = 1, max = 6_000, fallback = 18),
            GenomeKey.AI_FUEL_TICKS to GeneSpec(min = 1, max = 400, fallback = 200),
            GenomeKey.AI_SPIN_RAW to GeneSpec(
                min = -Int.MAX_VALUE / 64,
                max = Int.MAX_VALUE / 64,
                fallback = Int.MAX_VALUE / 120,
            ),
            GenomeKey.AI_THRUST_RAW to GeneSpec(
                min = Int.MAX_VALUE / (1 shl 19),
                max = Int.MAX_VALUE / (1 shl 17),
                fallback = Int.MAX_VALUE / (1 shl 18),
            ),
            GenomeKey.COLOR_H to GeneSpec(min = 0, max = 360, fallback = 0),
            GenomeKey.COLOR_S to GeneSpec(min = 0, max = 1000, fallback = 1000),
            GenomeKey.COLOR_V to GeneSpec(min = 200, max = 1000, fallback = 1000),
            GenomeKey.FIRE_COLOR_H to GeneSpec(min = 0, max = 360, fallback = 0),
            GenomeKey.FIRE_COLOR_S to GeneSpec(min = 0, max = 1000, fallback = 1000),
            GenomeKey.FIRE_COLOR_V to GeneSpec(min = 0, max = 1000, fallback = 1000),
        )

        fun encodeRanged(key: GenomeKey, decodedValue: Int): Int {
            val spec = GENE_SPECS[key] ?: error("Unsupported gene key for ranged encoding: $key")
            val clamped = decodedValue.coerceIn(spec.min, spec.max)
            val span = (spec.max - spec.min).coerceAtLeast(1)
            val norm = (clamped - spec.min).toDouble() / span.toDouble()
            val raw = Int.MIN_VALUE.toLong() + (norm * GENE_UINT_RANGE).roundToLong()
            return raw.toInt()
        }

        fun decodeRanged(key: GenomeKey, raw: Int?): Int? {
            val spec = GENE_SPECS[key] ?: return null
            raw ?: return null
            val norm = ((raw.toLong() - Int.MIN_VALUE.toLong()) / GENE_UINT_RANGE).coerceIn(0.0, 1.0)
            return (spec.min + ((spec.max - spec.min) * norm)).toInt().coerceIn(spec.min, spec.max)
        }

        fun decodeRanged(key: GenomeKey, encodedGenes: Map<String, Int>): Int? =
            decodeRanged(key, encodedGenes[key.wireName])
    }
}
