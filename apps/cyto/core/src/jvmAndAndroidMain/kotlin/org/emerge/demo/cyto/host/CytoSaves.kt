package org.emerge.demo.cyto.host

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.CytoWorldConfig
import org.emerge.demo.cyto.sim.GeneCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

/**
 * Named-save store: each save is a `<name>.bin` snapshot plus a `<name>.world` geometry sidecar (world size +
 * day/night, which the `.bin` codec doesn't carry) under [DIR]. Replaces the old single `cyto-save.bin`.
 * Pure file IO + the geometry-apply-before-restore dance; the menu drives it via [CytoMenu.Callbacks].
 *
 * Campaign entry states add a third, optional `<name>.campaign` sidecar (chapter id + the route taken to
 * reach it) — see the section at the bottom of this file. Sidecars rather than save-format fields so the
 * versioned `.bin` codec, and the golden trajectories that depend on it, stay untouched.
 */
object CytoSaves {
    private val DIR: Path get() = CytoStorage.baseDir.resolve("cyto-saves")

    /** Save names, newest first (by last-modified). Campaign entry states are excluded: they are written by
     *  the director on the player's behalf, not chosen by them, and listing a `campaign-*` snapshot per
     *  chapter would bury their own saves (see [campaignSaveName]). [allNames] includes them. */
    fun list(): List<String> = allNames().filterNot { it.startsWith(CAMPAIGN_PREFIX) }

    /** Every save on disk, campaign entry states included — the raw listing [list] filters. */
    fun allNames(): List<String> {
        if (!Files.isDirectory(DIR)) return emptyList()
        return runCatching {
            Files.list(DIR).use { stream ->
                stream.filter { it.toString().endsWith(".bin") }
                    .sorted(compareByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) })
                    .map { it.nameWithoutExtension }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    fun exists(name: String): Boolean = Files.exists(binPath(name))

    /** The most recently modified save name, or null if none. */
    fun mostRecent(): String? = list().firstOrNull()

    /** Trim a user-typed name to a safe, non-empty filename stem.
     *
     *  **Always lowercase.** Filenames are the identity of a save/genome/snippet (there is no separate
     *  display name — [list] shows the on-disk stem), and this is the single chokepoint every path goes
     *  through, so lowercasing here keeps the library case-uniform and makes name lookup case-insensitive
     *  (loading "Swimmer" finds `swimmer.gene`). Any pre-existing mixed-case files must be renamed to their
     *  lowercase stem or they stop resolving — [list] reads their true case off disk but every load
     *  re-sanitizes to lowercase. */
    fun sanitize(raw: String): String =
        raw.trim().map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("").lowercase().trim().take(40).ifEmpty { "save" }

    fun save(controller: CytoController, rawName: String): String {
        val name = sanitize(rawName)
        Files.createDirectories(DIR)
        Files.write(binPath(name), controller.snapshotBytes())
        val c = CytoWorldConfig
        Files.write(worldPath(name), "${c.cellsPerAxis} ${c.orbitPeriod} ${c.dayFraction}".toByteArray())
        println("[cyto] saved '$name'")
        return name
    }

    /** Apply the save's geometry to [CytoWorldConfig], then restore its snapshot (which rebuilds the reducer). */
    fun load(controller: CytoController, name: String): Boolean {
        if (!exists(name)) { println("[cyto] no save '$name'"); return false }
        return runCatching {
            applyGeometry(name)
            controller.restoreSnapshot(Files.readAllBytes(binPath(name)))
            println("[cyto] loaded '$name'")
            true
        }.getOrElse { println("[cyto] load '$name' failed: ${it.message}"); false }
    }

    fun delete(name: String) {
        runCatching {
            Files.deleteIfExists(binPath(name)); Files.deleteIfExists(worldPath(name))
            Files.deleteIfExists(campaignPath(name)); Files.deleteIfExists(brushPath(name))
        }
        println("[cyto] deleted '$name'")
    }

    private fun applyGeometry(name: String) {
        val g = storedGeometry(name) ?: return
        CytoWorldConfig.applyFrom(g.cellsPerAxis, g.orbitPeriod, g.dayFraction)
    }

    /** The geometry recorded beside save [name], or null if there is no readable sidecar. */
    private fun storedGeometry(name: String): CytoWorldConfig.Geometry? {
        val p = worldPath(name)
        if (!Files.exists(p)) return null
        return runCatching {
            val parts = Files.readAllBytes(p).decodeToString().trim().split(Regex("\\s+"))
            if (parts.size < 3) null
            else CytoWorldConfig.Geometry(parts[0].toInt(), parts[1].toLong(), parts[2].toFloat())
        }.getOrNull()
    }

    private fun binPath(name: String): Path = DIR.resolve("${sanitize(name)}.bin")
    private fun worldPath(name: String): Path = DIR.resolve("${sanitize(name)}.world")

    // ── Campaign entry states ───────────────────────────────────────────────────────────────────────
    // The campaign persists the world at the START of each chapter, under a reserved name, so returning to
    // a chapter from the menu resumes the player's actual world - their authored genome, their lineage, the
    // matter they have used up - instead of rebuilding a canned one from Chapter.scenario and discarding it.
    //
    // Alongside the usual .bin/.world pair, a `.campaign` sidecar records WHERE we are and HOW WE GOT HERE:
    // the chapter id plus the ordered route taken to reach it. That route is only reconstructible from a
    // genome by guesswork once a branch exists (and not at all once the player edits the evidence away), so
    // it is written down rather than inferred. Redundant with the chapter id while the campaign is linear;
    // it stops being redundant the moment two routes can reach the same chapter.

    /** Prefix reserving a save name for the campaign's own use (filtered out of [list]). */
    const val CAMPAIGN_PREFIX = "campaign-"

    /** Reserved save name holding the world as chapter [chapterId] began. */
    fun campaignSaveName(chapterId: String): String = sanitize("$CAMPAIGN_PREFIX$chapterId")

    /** Persist the world as this chapter starts, plus the [path] that led here and the brush it started
     *  with (see [brushPath]). */
    fun saveCampaignEntry(controller: CytoController, chapterId: String, path: List<String>) {
        val name = campaignSaveName(chapterId)
        runCatching {
            save(controller, name)
            Files.write(campaignPath(name), "chapter=$chapterId\npath=${path.joinToString(",")}\n".toByteArray())
            val brush = controller.lastAuthoredGenome
            if (brush == null) Files.deleteIfExists(brushPath(name))
            else Files.write(brushPath(name), GeneCodec.serialize(brush).toByteArray())
        }.onFailure { println("[cyto] campaign save for '$chapterId' failed: ${it.message}") }
    }

    /** Whether the world stored as [name] was built at the geometry [scenario] now asks for. A save with no
     *  readable sidecar counts as a mismatch: its geometry is unknown, and the safe reading of "unknown" is
     *  that it is not this one. */
    private fun geometryMatches(name: String, scenario: CytoScenario): Boolean {
        val stored = storedGeometry(name) ?: return false
        val want = CytoWorldConfig.geometryOf(scenario)
        return stored.cellsPerAxis == want.cellsPerAxis && stored.orbitPeriod == want.orbitPeriod &&
            kotlin.math.abs(stored.dayFraction - want.dayFraction) < 1e-4f
    }

    /** True if [chapterId] has a stored entry state to resume from. */
    fun hasCampaignEntry(chapterId: String): Boolean = exists(campaignSaveName(chapterId))

    /**
     * Restore the world as [chapterId] began. False (nothing touched) if there is no stored entry state,
     * which is the cold-start case - the caller then builds from the chapter's scenario as before.
     *
     * [scenario] is the chapter's own recipe, and passing it makes **the scenario authoritative over a stale
     * save**: an entry state whose geometry no longer matches is discarded rather than resumed. Without this,
     * re-authoring a chapter's world was silently a no-op for anyone who had already entered it — the entry
     * state was preferred, its `.world` sidecar pushed the OLD geometry back into [CytoWorldConfig], and
     * entering re-saved the same stale geometry forward. Genesis became a pocket universe on 2026-07-24 and
     * every world already on disk carried on at the old size.
     *
     * Geometry only: matter, lineage and the player's spent world are exactly what an entry state is FOR, and
     * a scenario tweak that doesn't resize the world must not throw their experiment away.
     */
    fun loadCampaignEntry(
        controller: CytoController,
        chapterId: String,
        scenario: CytoScenario? = null,
    ): Boolean {
        if (!hasCampaignEntry(chapterId)) return false
        if (scenario != null && !geometryMatches(campaignSaveName(chapterId), scenario)) {
            println("[cyto] campaign entry for '$chapterId' predates its scenario's geometry - rebuilding")
            return false
        }
        if (!load(controller, campaignSaveName(chapterId))) return false
        restoreBrush(controller, campaignSaveName(chapterId))
        return true
    }

    /**
     * Put back the brush this chapter began with — including *no* brush, which is why this always writes.
     *
     * Chapters are isolated at the menu boundary: `CytoController.lastAuthoredGenome` outlives a world
     * rebuild on purpose (a mid-chapter Reset must hand the player's own organism back), so re-entering an
     * earlier chapter without this leaves the brush pointing at whatever was authored in a *later* one — and
     * Genesis, which hands out a gene-less cell as its opening beat, would silently place a finished organism.
     */
    private fun restoreBrush(controller: CytoController, name: String) {
        val p = brushPath(name)
        val genome = if (!Files.exists(p)) null
        else runCatching { GeneCodec.parse(Files.readAllBytes(p).decodeToString()) }.getOrNull()
        controller.setAuthoredGenome(genome)
    }

    /** The recorded route to [chapterId], or empty if none is stored. */
    fun campaignEntryPath(chapterId: String): List<String> {
        val p = campaignPath(campaignSaveName(chapterId))
        if (!Files.exists(p)) return emptyList()
        return runCatching {
            Files.readAllLines(p).firstOrNull { it.startsWith("path=") }
                ?.removePrefix("path=")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun campaignPath(name: String): Path = DIR.resolve("${sanitize(name)}.campaign")

    /** The brush (last-authored genome) a chapter began with, as `.gene` text. Absent = it began with none. */
    private fun brushPath(name: String): Path = DIR.resolve("${sanitize(name)}.brush")
}
