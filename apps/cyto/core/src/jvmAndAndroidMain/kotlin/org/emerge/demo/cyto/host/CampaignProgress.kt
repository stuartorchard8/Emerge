package org.emerge.demo.cyto.host

import java.nio.file.Files
import java.nio.file.Path

/**
 * Persisted campaign progress: the set of completed chapter ids, one per line in a plain text file
 * (`campaign-progress`) beside the game (mirrors the [CytoSaves] file convention). A chapter is
 * **unlocked** once any chapter that leads to it is completed — see [isUnlocked].
 */
class CampaignProgress private constructor(private val completed: MutableSet<String>) {

    fun isCompleted(id: String): Boolean = id in completed

    fun complete(id: String) {
        if (completed.add(id)) save()
    }

    /**
     * Unlocked = **any** chapter that can lead here is completed (or nothing leads here, which covers the
     * first chapter and any WIP chapter outside the authored flow).
     *
     * Predecessors rather than "the previous index" because the campaign branches: a branch destination sits
     * at an arbitrary point in the flat list, and the chapter that unlocks it is whichever one names it —
     * see [org.emerge.demo.cyto.campaign.Chapter.branchesTo]. For a linear chapter the sole predecessor IS
     * the previous one, so this is the old rule where nothing branches.
     */
    fun isUnlocked(id: String, predecessors: List<String>): Boolean =
        predecessors.isEmpty() || predecessors.any { it in completed }

    private fun save() {
        runCatching { Files.write(FILE, completed.joinToString("\n").toByteArray()) }
    }

    companion object {
        private val FILE: Path get() = CytoStorage.baseDir.resolve("campaign-progress")

        fun load(): CampaignProgress {
            val set = runCatching {
                if (Files.exists(FILE)) Files.readAllLines(FILE).map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                else mutableSetOf()
            }.getOrDefault(mutableSetOf())
            return CampaignProgress(set)
        }
    }
}
