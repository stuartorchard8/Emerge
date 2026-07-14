package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.ui.GenomeGrouping

/**
 * The authored content model for the campaign / story mode (see `apps/cyto/CAMPAIGN_PLAN.md`). A
 * [Chapter] is a starting-world recipe plus an ordered list of coaching [Step]s; the [CampaignDirector]
 * walks the steps, evaluating each step's [Gate] against the live world and drawing the coach overlay.
 *
 * This is pure data + predicates — no sim state, no rendering here. The director owns the runtime and
 * the desktop host owns the file I/O (progress) + the authored chapter list. Kept in `commonMain` so a
 * future Android/web host can run the same content.
 */

/** One chapter: a world recipe and its scripted coaching sequence. [id] is the stable key used for
 *  unlock/progress persistence. [seededGenomeNames] documents which library genomes the chapter expects
 *  to exist as brushes (the host seeds them). */
class Chapter(
    val id: String,
    val act: Int,
    val title: String,
    val blurb: String,
    val scenario: CytoScenario,
    val steps: List<Step>,
    val seededGenomeNames: List<String> = emptyList(),
    /** An optional functional grouping for this chapter's genome (CAMPAIGN_PLAN.md §10). When present the
     *  gene editor shows the held cell's genes collapsed into named subsystems instead of a flat list, so
     *  Act II can teach at the subsystem level. Order-preserving label only — no effect on the sim. */
    val grouping: GenomeGrouping? = null,
    /** Which of the [grouping]'s groups the gene editor offers as "+ ADD <group>" buttons when they're absent
     *  from the held cell. Named per chapter so a chapter offers only the one subsystem it teaches (Ch4 =
     *  Reproduce, Ch5 = Hold Together); empty (Act I / Ch3) = read-only, no inserts. */
    val insertableGroups: Set<String> = emptySet(),
    /** The genome a tap on empty space drops in as a fresh cell, during steps whose [ControlMask] allows
     *  [Control.Spawn] (Ch8's "tap an empty space to add another cell with the same genome" — a re-seed if a
     *  founder dies before it gets going). Null ⇒ no world-spawn even if a step allowed it. The host sets this
     *  as the brush genome and permits empty-space taps *without* surfacing the full brush palette. */
    val spawnGenome: List<Gene>? = null,
    /** When true, an empty-space tap during a [Control.Spawn] step drops a **live copy of the currently
     *  selected cell's genome** instead of the fixed [spawnGenome] — a "last-modified brush". Ch9 leans on
     *  this: each time the player edits the muscle on their selected cell, the next cell they tap out carries
     *  those edits, so they iterate a lineage in place (and can go off-script if they choose). Falls back to
     *  [spawnGenome] when no cell is selected. */
    val spawnCopiesHeldCell: Boolean = false,
)

/** One coaching beat: the instruction, how it advances ([gate]), which controls are live ([allow]), an
 *  optional attention [spotlight], and an [onEnter] side effect (focus a cell, etc.). [detail] is opt-in
 *  depth shown behind a "More" toggle. */
class Step(
    val text: String,
    val gate: Gate,
    val allow: ControlMask = ControlMask.ALL,
    val spotlight: Spotlight? = null,
    val detail: String? = null,
    /** Whether the world should run or be held still while this step is showing. Reading/interaction
     *  beats default [WorldRun.Frozen] so a slow reader isn't overtaken by events from a later concept;
     *  steps whose goal needs the sim to change (growth, division) are [WorldRun.Live]. Applied by the
     *  host on step entry (the player keeps manual pause/speed control within the step). */
    val world: WorldRun = WorldRun.Frozen,
    val onEnter: (CytoController) -> Unit = {},
)

/** Whether the simulation advances during a [Step]. */
enum class WorldRun { Frozen, Live }

/** What advances a step to the next. */
sealed interface Gate {
    /** Manual: the player reads and clicks "Next". */
    object Next : Gate

    /** A live world condition. [desc] is shown as the objective line; [progress] optionally drives a
     *  progress bar as `current to target`. */
    class World(
        val desc: String,
        val met: (CampaignQuery) -> Boolean,
        val progress: ((CampaignQuery) -> Pair<Int, Int>)? = null,
    ) : Gate

    /** The player performed an interaction (detected by the host and reported to [CampaignDirector.update]). */
    class Did(val action: PlayerAction, val desc: String) : Gate

    /** Every sub-gate is satisfied. */
    class All(val gates: List<Gate>) : Gate
}

/** Interactions the director can wait on. The host detects these (mostly by diffing controller/UI state
 *  between frames) and passes the set that occurred this frame to [CampaignDirector.update]. */
enum class PlayerAction {
    SelectedCell,
    ToggledMatterOverlay,
    ChangedSpeed,
    MovedCamera,
    PaintedCell,
    OpenedGeneEditor,
}

/** A hint about where to point the player's attention. v1 is intentionally lightweight: an optional text
 *  cue rendered in the coach panel. Region-targeted rings / arrows are a planned enhancement
 *  (CAMPAIGN_PLAN.md §4.2). */
class Spotlight(val hint: String? = null)

/** Which controls are live during a step — everything else is hidden/greyed by the host. An immutable
 *  allow-set so authored steps read declaratively (`ControlMask.of(Camera, Select)`). */
class ControlMask private constructor(private val allowed: Set<Control>) {
    fun allows(c: Control): Boolean = c in allowed

    companion object {
        val ALL = ControlMask(Control.entries.toSet())
        fun of(vararg cs: Control) = ControlMask(cs.toSet())
    }
}

/** The host-maskable control surfaces. [Spawn] permits tapping empty space to drop in a [Chapter.spawnGenome]
 *  cell *without* the full [Brush] palette — a focused re-seed affordance (Ch8). */
enum class Control { Camera, Select, Brush, GeneEditor, Speed, Mutation, Overlays, Save, Menu, Spawn }
