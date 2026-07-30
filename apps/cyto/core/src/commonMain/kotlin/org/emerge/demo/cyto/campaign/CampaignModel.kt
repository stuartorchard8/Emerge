package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.ui.GeneKeys
import org.emerge.demo.cyto.ui.MetabKeys
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
    /**
     * A grouping built from the **player's own chemistry** rather than fixed at authoring time — used by the
     * rehomed chapters, whose "+ ADD <group>" subsystems have to be bound to whatever fuel pair the player
     * chose (see [ChemBinding]). Takes precedence over [grouping] when set.
     *
     * A function rather than a field because the answer depends on a `Lineage` that does not exist until the
     * chapter is running; `CampaignDirector.grouping` resolves it and is what the hosts read.
     */
    val groupingFor: ((Lineage?) -> GenomeGrouping)? = null,
    /** Which of the [grouping]'s groups the gene editor offers as "+ ADD <group>" buttons when they're absent
     *  from the held cell. Named per chapter so a chapter offers only the one subsystem it teaches (Ch4 =
     *  Reproduce, Ch5 = Hold Together); empty (Act I / Ch3) = read-only, no inserts. */
    val insertableGroups: Set<String> = emptySet(),
    /** The genome a tap on empty space drops in as a fresh cell — a re-seed if a founder dies before it gets
     *  going. A world tap acts on every step of a chapter (`CytoControls.worldTapsEnabled`), so this is live
     *  throughout; the host sets it as the brush genome *without* surfacing the full brush palette. Null ⇒
     *  the player's own lineage instead (see `CampaignDirector.brushGenome`). */
    val spawnGenome: List<Gene>? = null,
    /** The biomass a world tap gives the cell it drops in, overriding the genome-derived default.
     *  Lets a chapter place a hand-authored starter — the opening chapter drops a gene-less cell holding a
     *  fixed reserve (1000 each of r/g/b) so it can be watched slowly decaying to death. Null ⇒ default. */
    val spawnBiomass: Map<String, Int>? = null,
    /** The mobile **cytoplasm** a world tap seeds the cell with (brush-placed cells otherwise start
     *  with none — only scenario founders get a reserve). Currently unused by the authored chapters: Genesis
     *  deliberately hands its first cell nothing, because monomers diffuse in from the environment anyway and
     *  a pre-filled reserve would hide where a cell's raw material actually comes from. Null ⇒ empty. */
    val spawnCytoplasm: Map<String, Int>? = null,
    /** When true, an empty-space tap drops a **live copy of the currently
     *  selected cell's genome** instead of the fixed [spawnGenome] — a "last-modified brush". Ch9 leans on
     *  this: each time the player edits the muscle on their selected cell, the next cell they tap out carries
     *  those edits, so they iterate a lineage in place (and can go off-script if they choose). Falls back to
     *  [spawnGenome] when no cell is selected. */
    val spawnCopiesHeldCell: Boolean = false,
    /**
     * Whether entering this chapter rebuilds the world from [scenario] instead of carrying the previous
     * chapter's world forward. The campaign is a single continuous world (CAMPAIGN_PLAN.md): completing a
     * chapter segues straight into the next in the SAME world, because each chapter's authored end-state is
     * the next chapter's starting point. Set this only where that chain genuinely breaks — the first chapter
     * (nothing precedes it) and Ch8 (the autotroph gives way to the bespoke swimmer lineage). Elsewhere the
     * player's living world continues, and the always-available Reset is how they realign it to [scenario].
     */
    val startsFreshWorld: Boolean = false,
    /**
     * Which chapter follows this one, chosen from the world the player leaves behind — the campaign's one
     * branch point. Returns a chapter id, or null to take the next chapter in the list (what every linear
     * chapter does, and the default).
     *
     * The choice is read from the world rather than recorded as an answer to a question, because the player
     * never answers a question: they build something, and what they built decides where they go. An id that
     * isn't in the director's chapter list falls back to list order, so a half-authored branch can't strand
     * anyone mid-campaign.
     */
    val next: ((CampaignQuery) -> String?)? = null,
    /**
     * Every chapter [next] may name, declared statically. [next] itself is a function of the finished world,
     * so nothing can enumerate its outcomes — but the chapter *selector* has to know which chapters this one
     * can unlock, and a branch destination has no meaningful position in the flat list to be unlocked from.
     *
     *  - **null** (the default) — linear: this chapter leads to the next one in the list.
     *  - **a list** — these and only these. An EMPTY list is therefore meaningful: "leads nowhere", which is
     *    what a branch destination says while its own continuation is unauthored. Without that distinction a
     *    dead-end branch would confer list-order succession on whatever happened to sit after it, and
     *    finishing one branch would unlock the other — re-opening the door the branch just closed.
     */
    val branchesTo: List<String>? = null,
)

/** One coaching beat: the instruction, how it advances ([gate]), which controls are live ([allow]), an
 *  optional attention [spotlight], and an [onEnter] side effect (focus a cell, etc.). [detail] is opt-in
 *  depth shown behind a "More" toggle. */
class Step(
    val text: String,
    val gate: Gate,
    val altText: String? = null,
    val allow: ControlMask = ControlMask.ALL,
    val spotlight: Spotlight? = null,
    val detail: String? = null,
    /** Whether the world should run or be held still while this step is showing. Reading/interaction
     *  beats default [WorldRun.Frozen] so a slow reader isn't overtaken by events from a later concept;
     *  steps whose goal needs the sim to change (growth, division) are [WorldRun.Live]. Applied by the
     *  host on step entry (the player keeps manual pause/speed control within the step). */
    val world: WorldRun = WorldRun.Frozen,
    /**
     * Move on the instant [gate] is met, without waiting for a "Next" click.
     *
     * For beats where the gate firing IS the event the player was told to watch for, so pausing on a
     * satisfied goal just leaves stale copy on screen describing something that already happened — the
     * lineage in `ch01-divide` wiping itself out is the case this exists for. Off by default: most gated
     * steps want the player to look at what they just achieved before the coach talks again.
     *
     * Meaningless on a [Gate.Next] step, which by definition has no condition to fire on.
     */
    val autoAdvance: Boolean = false,
    val onEnter: (CytoController) -> Unit = {},
)

/** Whether the simulation advances during a [Step]. */
enum class WorldRun { Frozen, Live }

/** What advances a step to the next. */
sealed interface Gate {
    /** Manual: the player reads and clicks "Next". */
    object Next : Gate

    /**
     * A live world condition — **the default**, and what a beat should gate on unless it genuinely cannot.
     *
     * State, not event: it asks whether the thing the coach described is *true*, so a player who did it
     * before being asked has already satisfied it and moves straight on. That is the common case, not an
     * edge one — people poke at a new game while they read, and Genesis spends its first four beats
     * teaching interactions the player has very likely already tried.
     *
     * [desc] is shown as the objective line; [progress] optionally drives a progress bar as
     * `current to target`.
     */
    class World(
        val desc: String,
        val met: (CampaignQuery) -> Boolean,
        val progress: ((CampaignQuery) -> Pair<Int, Int>)? = null,
    ) : Gate

    /**
     * The player performed an interaction (detected by the host and reported to [CampaignDirector.update]).
     *
     * **Prefer [World].** This one only fires while its step is showing, so doing the thing a moment early
     * does not count and the player is asked to repeat an action they have just performed — sometimes with
     * no way to tell that the game wanted a *fresh* one. Reach for it only when there is no state to read:
     * a gesture that leaves no trace (panning the camera, dragging a cell) is the honest case. Anything with
     * a lasting consequence — a selection, an opened panel, an authored gene — has state, so gate on that.
     */
    class Did(val action: PlayerAction, val desc: String) : Gate

    /** Every sub-gate is satisfied. */
    class All(val gates: List<Gate>) : Gate
}

/** Interactions the director can wait on. The host detects these (mostly by diffing controller/UI state
 *  between frames) and passes the set that occurred this frame to [CampaignDirector.update]. */
enum class PlayerAction {
    SelectedCell,
    ChangedSpeed,
    MovedCamera,
    PaintedCell,
    OpenedGeneEditor,
    /** The player dragged a cell around the world (grab + move). Lets the opening chapter teach "you can
     *  push cells about" as its own beat. */
    MovedCell,
}

/**
 * A hint about where to point the player's attention: a [hint] line in the coach panel, and — when the thing
 * being described is something the player can actually see — a box the coach draws around it, with a
 * connector back to itself.
 *
 * Two ways to name that thing, and **the gene card wants [gene], not [target]**:
 *
 *  - [gene] is a *slot* on a gene, by what the gene does and what the slot is for — "the condition on the
 *    GROW gene". It resolves by [GeneKeys] identity, so it survives the slot's text changing under it
 *    (`ALWAYS` becomes `WHEN BIO < 3000` the moment the step's edit lands), the genome being reordered, and
 *    a display word being renamed. Nothing to count, nothing to spell.
 *  - [target] is a **widget label**, matched exactly the way the agent harness's `tap-ui` matches one
 *    (`Ui.element`): case-insensitive, exact before substring, an open sheet's rows before the panel behind
 *    it. Right for the fixed furniture — `+ NEW GENE`, `LAYERS`, `+ ADD HOLD TOGETHER` — where the word IS
 *    the identity and a script taps it by the same name, so a drift breaks both at once.
 *    [occurrence] picks the n-th match for a repeated label, as `tap-ui <label> @n` does; prefer [gene] over
 *    ever reaching for it.
 *
 * Something that doesn't resolve this frame — the panel is closed, the group is collapsed, the gene doesn't
 * exist yet — simply isn't drawn, and the step falls back to its [hint] text. Steps about a world gesture
 * ("press and drag a cell") have no widget and take neither.
 */
class Spotlight(
    val hint: String? = null,
    val target: String? = null,
    val occurrence: Int = 1,
    val gene: GeneSpot? = null,
    val metab: MetabSpot? = null,
)

/**
 * A single number in the selected cell's chemistry table — the [column] of the row for one of the player's
 * own molecules, named the way the coach's copy names it (`{chem}` / `{bond}`) rather than by a species
 * token the campaign cannot know in advance.
 *
 * For the beats that talk about a quantity ("they are filling up with their own waste"): the concept is
 * abstract enough that pointing at the table is not pointing at the evidence.
 */
class MetabSpot(val chem: Chem, val column: String = MetabKeys.CYT) {
    /** Which of the player's molecules — resolved against the selected lineage at the moment of drawing. */
    enum class Chem { Convert, Bond }
}

/** A slot on the gene that does [action] — the [ordinal]-th such gene, in genome order. See [GeneKeys]. */
class GeneSpot(
    val action: ActionType,
    val part: GeneKeys.Part,
    val ordinal: Int = 1,
    val clause: Int = 0,
) {
    val key: String get() = GeneKeys.part(action, part, ordinal, clause)
}

/** Which controls are live during a step — everything else is hidden/greyed by the host. An immutable
 *  allow-set so authored steps read declaratively (`ControlMask.of(Camera, Select)`). */
class ControlMask private constructor(private val allowed: Set<Control>) {
    fun allows(c: Control): Boolean = c in allowed

    /** This mask plus [cs] — for a permission the *situation* grants rather than the step, like the spawn
     *  the coach offers when a lineage has gone extinct. */
    fun plus(vararg cs: Control) = ControlMask(allowed + cs)

    companion object {
        val ALL = ControlMask(Control.entries.toSet())
        fun of(vararg cs: Control) = ControlMask(cs.toSet())
    }
}

/** The host-maskable control surfaces. [Spawn] permits tapping empty space to drop in a [Chapter.spawnGenome]
 *  cell *without* the full [Brush] palette. */
enum class Control { Camera, Select, Brush, GeneEditor, Speed, Mutation, Overlays, Save, Menu }
