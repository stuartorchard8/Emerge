package org.emerge.demo.drockets

import kotlin.math.max
import kotlin.math.min

enum class Sex {
    MALE,
    FEMALE,
}

data class ReproducerComponent(
    val birthdayMs: Long,
    val sex: Sex,
    val maturityAgeMs: Long = 10_000,
    val gestationDuration: Long = 10_000,
    val spawn: ReproducerComponent? = null,
    val spawnGenome: Genome? = null,
    val spawnMotherEntityId: Int? = null,
    val spawnFatherEntityId: Int? = null,
) {
    fun isMature(nowMs: Long): Boolean {
        return birthdayMs + maturityAgeMs <= nowMs
    }

    fun getMaturityRatio(nowMs: Long): Float {
        return max(0f, min(1f, (nowMs - birthdayMs)/maturityAgeMs.toFloat()))
    }
}
