package org.emerge.desktop

import java.nio.file.Files
import java.nio.file.Path

/**
 * Persisted campaign progress: the set of completed chapter ids, one per line in a plain text file
 * (`campaign-progress`) beside the game (mirrors the [CytoSaves] file convention). A chapter is
 * **unlocked** if it's the first chapter or the previous chapter (by authored order) is completed.
 */
class CampaignProgress private constructor(private val completed: MutableSet<String>) {

    fun isCompleted(id: String): Boolean = id in completed

    fun complete(id: String) {
        if (completed.add(id)) save()
    }

    /** Unlocked = first chapter, or the previous chapter in [order] is completed. */
    fun isUnlocked(id: String, order: List<String>): Boolean {
        val i = order.indexOf(id)
        if (i <= 0) return true
        return completed.contains(order[i - 1])
    }

    private fun save() {
        runCatching { Files.writeString(FILE, completed.joinToString("\n")) }
    }

    companion object {
        private val FILE: Path = Path.of("campaign-progress")

        fun load(): CampaignProgress {
            val set = runCatching {
                if (Files.exists(FILE)) Files.readAllLines(FILE).map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                else mutableSetOf()
            }.getOrDefault(mutableSetOf())
            return CampaignProgress(set)
        }
    }
}
