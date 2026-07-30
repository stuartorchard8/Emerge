package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.ui.GenomeGrouping
import org.emerge.demo.cyto.sim.SpeciesNames
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.CanvasBuilder
import org.emerge.render.torus.ui.PanelBuilder
import org.emerge.render.torus.ui.Ui
import org.emerge.render.torus.ui.UiBuilder
import org.emerge.render.torus.ui.UiTextRenderer

/**
 * The campaign runtime. Owns the current chapter + step, evaluates the active step's [Gate] against the
 * per-frame [CampaignQuery], and draws the bottom-centre **coach** overlay with the shared immediate-mode
 * UI toolkit. Immediate-mode, same lifecycle as the gene editor: the host calls [update] once a frame with
 * the world snapshot + the [PlayerAction]s that occurred, then [render] inside its `ui.frame { }` block.
 *
 * No sim state, no file I/O — the host supplies the authored [Chapter], detects player interactions, and
 * persists progress (see `apps/cyto/CAMPAIGN_PLAN.md` §3).
 */
class CampaignDirector {
    var active: Boolean = false
        private set

    private var chapter: Chapter? = null
    private var stepIndex: Int = 0
    private val satisfiedDid = HashSet<PlayerAction>()
    private var gateMet: Boolean = false
    private var lastQuery: CampaignQuery? = null
    /** The controller the host walks this campaign against, remembered from the last step entry. Only so
     *  [update] can perform a [Step.autoAdvance] transition, which needs the same controller a "Next" click
     *  would have supplied. Not ownership — every other path still takes one as a parameter. */
    private var host: CytoController? = null
    private var showDetail: Boolean = false
    /** The Reset sheet is open (the two ways back). Cleared on every step entry, like [showDetail]. */
    private var resetMenuOpen: Boolean = false

    /** The ordered campaign. The director walks this itself so completing a chapter segues straight into the
     *  next (a single continuous world) instead of returning the host to the chapter selector. The host sets
     *  it once. Empty ⇒ single-chapter mode (advancing past the last step just ends via [onCampaignComplete]). */
    var chapters: List<Chapter> = emptyList()

    /** Called with a chapter id the moment its final step is passed — the host persists it as completed
     *  (unlocking the next). Fires whether or not another chapter follows. */
    var onChapterCompleted: (String) -> Unit = {}

    /** The whole campaign is finished (the last chapter's final step was passed) — the host returns to the
     *  menu. This is the ONLY path back to the selector now; mid-campaign, chapters flow into each other. */
    var onCampaignComplete: () -> Unit = {}

    /** Rebuild the world for [Chapter] from its [Chapter.scenario] (the host does `newGame` + resets the
     *  camera). Called when entering a [Chapter.startsFreshWorld] chapter and by [resetChapter]. */
    var onWorldReset: (Chapter) -> Unit = {}

    /** Invoked whenever a new step becomes current (chapter start + each advance). The host applies the
     *  step's [WorldRun] here (pause/resume the sim). */
    var onStepEnter: (Step) -> Unit = {}

    /**
     * Put the player's own lineage back into a just-emptied world: spawn one cell carrying [carriedGenome]
     * under the middle of the camera. Invoked by [resetChapter] after [onWorldReset], and only for a chapter
     * whose scenario seeds no founders of its own (see [resetChapter] for why).
     *
     * The host owns this because only it knows where the camera is pointing.
     */
    var onReseedLineage: (Chapter, List<Gene>) -> Unit = { _, _ -> }

    /**
     * Restore the world exactly as this chapter began, from the entry state the host persisted on
     * [onChapterEntered]. Returns false if there is none (a chapter never entered on this install), leaving
     * the world untouched so the caller can fall back.
     *
     * The counterpart to [resetChapter]: this one puts back *the world the player had*, mid-experiment and
     * all, where the other builds a clean scripted one.
     */
    var onRestoreEntryState: (Chapter) -> Boolean = { false }

    /**
     * The genome the player's lineage carries into the current chapter — snapshotted from the living world
     * at the moment the previous chapter handed over, so it is *their* authored genome, not a canned one.
     *
     * Null in the very first chapter, which is where the genome gets authored in the first place; there a
     * Reset correctly returns an empty world for the player to seed by hand.
     */
    var carriedGenome: List<Gene>? = null
        private set

    /**
     * Fired the moment a chapter becomes current — on [start] and on every segue — with the route that led
     * here ([path]). The host persists the world at this point, so the state at the *beginning* of a chapter
     * can be reloaded as-is when the player comes back to it from the menu, instead of the world being
     * rebuilt from the chapter's canned [Chapter.scenario] and their authored lineage thrown away.
     *
     * Entry rather than exit is deliberate: it is the same instant for a linear campaign, but when a chapter
     * fans out into a branch each destination records its own entry state under its own name, so "the state
     * this chapter starts from" needs no reasoning about predecessors.
     */
    var onChapterEntered: (Chapter, List<String>) -> Unit = { _, _ -> }

    /**
     * The chapters visited to get here, in order, ending with the current one — "where we are and how we got
     * here". Persisted alongside the world so a branch the player took stays explicit rather than being
     * re-derived from whatever their genome happens to look like later.
     */
    val path: List<String> get() = pathInternal
    private val pathInternal = mutableListOf<String>()

    /** The host's input phrasing, interpolated into coach copy so a `{pan}`/`{zoom}` gesture reads correctly
     *  for this platform — [InputHints.MOUSE] on desktop, [InputHints.TOUCH] on a phone. The host sets it once
     *  at construction; defaults to MOUSE. */
    var inputHints: InputHints = InputHints.MOUSE

    /** Expand a coach string's input tokens for this host. */
    /** Expand a coach string: platform gesture tokens ([InputHints]) plus two dynamic chemistry tokens that
     *  let the coach speak the player's own choices back to them.
     *
     *  - **`{chem}`** - the selected cell's chosen CONVERT chemical (its first CONVERT gene's operand),
     *    which is how Genesis reacts to the "starter" pick: "{chem} it is." Falls back to "nothing".
     *  - **`{bond}`** - what the cell's DIVIDE gene synthesises for energy, for the chapter where the player
     *    picks a reaction to power division. Falls back to "nothing".
     *
     *  Both are named the world's way (chapter aliases over the built-in [SpeciesNames]); the whole coach
     *  renders upper-case, so their casing is irrelevant. [alt] is the "you skipped the choice" variant, shown
     *  whenever a token the copy is built on has nothing to name. */
    private fun copy(s: String, alt: String? = null): String {
        var expanded = inputHints.expand(s)
        if (!expanded.contains("{chem}") && !expanded.contains("{bond}")) return expanded
        val chem = lastQuery?.lineage?.convertChem
        val bond = lastQuery?.lineage?.divideProduct
        // Cheeky alt text for people who skip — fires when the token the copy is BUILT ON has nothing to
        // name, whichever token that is. (It used to key off {chem} alone, which left the divide chapter's
        // "you never picked a reaction" line unreachable on a step whose text is about {bond}.)
        val unnamed = (expanded.contains("{chem}") && chem.isNullOrEmpty()) ||
            (expanded.contains("{bond}") && bond.isNullOrEmpty())
        if (alt != null && unnamed) expanded = inputHints.expand(alt)
        return expanded
            .replace("{chem}", named(chem))
            .replace("{bond}", named(bond))
    }

    /** A species token as the world names it, or "nothing" when it's absent/unset. */
    private fun named(token: String?): String =
        if (token.isNullOrEmpty()) "nothing"
        else SpeciesNames.name(token, chapter?.scenario?.aliases ?: emptyMap())

    val activeChapter: Chapter? get() = chapter

    /**
     * The grouping the gene editor should render — **what the hosts read**, rather than
     * `activeChapter.grouping` directly.
     *
     * A rehomed chapter declares [Chapter.groupingFor] instead of a fixed grouping, because the subsystems
     * it offers to insert must be bound to the player's own fuel pair ([ChemBinding]); resolving that needs
     * the live `Lineage`, which only the director has. Chapters with a static [Chapter.grouping] (the whole
     * authored Ch1-10 arc) are returned unchanged.
     */
    val grouping: GenomeGrouping?
        get() = chapter?.let { ch -> ch.groupingFor?.invoke(lastQuery?.lineage) ?: ch.grouping }
    val currentStep: Step? get() = chapter?.steps?.getOrNull(stepIndex)

    /** Which controls the host should keep live this step (ALL when no chapter is active). */
    val controlMask: ControlMask get() = when {
        !active -> ControlMask.ALL
        // The extinction offer needs a tap on empty space to be possible, whatever the step was allowing —
        // a step that masked spawning off did so to keep the player on task, and the task is gone.
        extinctionOffer -> (currentStep?.allow ?: ControlMask.ALL).plus(Control.Spawn)
        // Likewise: the coach is asking them to pick another cell, so picking one has to be possible.
        watchedCellOffer -> (currentStep?.allow ?: ControlMask.ALL).plus(Control.Select)
        else -> currentStep?.allow ?: ControlMask.ALL
    }

    /**
     * Nothing of the player's is alive, but their genome is — so the coach stops asking for whatever the step
     * wanted and offers the two ways forward instead (put a cell back, or reset the chapter).
     *
     * Extinction used to be an unmarked dead end: every goal keyed on a living cell became unsatisfiable and
     * the only way on was a Reset the coach never mentioned. It is a normal thing to happen in this game —
     * two chapters are *about* a lineage failing — so it is handled as a state, not an accident.
     *
     * Suppressed only when the step ASKED for this death (`ch01-divide` has the player watch a lineage divide
     * itself below the rupture floor). Talking them out of it there would replace the beat's copy with a
     * recovery offer for a situation that is not a setback, and send them back to fix a world the next step is
     * about to discuss.
     *
     * That is a narrower test than "the gate is met", which is what this used to check and why the offer went
     * missing on so many steps: most gates read the LINEAGE, and a lineage outlives its cells by design — so a
     * player sitting on a satisfied goal (waiting to click Next, or reading) watched their world empty out with
     * the coach saying nothing. [goalIsExtinction] asks the gate the question that actually distinguishes the
     * two: would you still be met if a cell were alive?
     */
    val extinctionOffer: Boolean
        get() = active && lastQuery?.extinct == true && !goalIsExtinction &&
            (lastQuery?.lineage != null || carriedGenome != null)

    /** Whether the current step is met BECAUSE the world is empty — a gate that stops being satisfied the
     *  moment a cell exists ([CampaignQuery.asIfPopulated]). False on a [Gate.Next] step, which has no
     *  condition, so a lineage that dies while the player is merely reading still gets the net. */
    private val goalIsExtinction: Boolean
        get() {
            if (!gateMet) return false
            val q = lastQuery ?: return false
            return !evalGate(currentStep?.gate, q.asIfPopulated())
        }

    /**
     * The **one cell** the beat was about has died, while the rest of the lineage carries on — so the coach
     * asks them to pick another instead of leaving them watching a corpse.
     *
     * The sibling of [extinctionOffer], for the other half of the campaign's beats. A step like Exhaust 7/7
     * ("now watch the cell you have selected") gates on a reading taken from the *selected* cell, and the
     * moment that cell ruptures the reading is null and the gate can never be satisfied again — but the world
     * is fine, hundreds of its siblings are running the very genome the player just wrote, so nothing about
     * the situation announces itself. The player is left waiting on a goal that has quietly become impossible.
     *
     * [gateWantsASelection] is what keeps this off every other step: a beat that never reads the selection is
     * unaffected by the death, and must not be interrupted to talk about it.
     *
     * [staleWatchedDeath] keeps it off a step the death *preceded*. Genesis has the player watch a gene-less
     * cell rupture and then place a fresh one — the death is the previous beat's whole point, and the
     * controller's "the cell you were watching is gone" memory outlives it, so the next step (which does want
     * a selection) opened by telling them about a corpse they had already buried.
     */
    val watchedCellOffer: Boolean
        get() = active && !gateMet && !extinctionOffer && !staleWatchedDeath &&
            lastQuery?.watchedCellDied == true && lastQuery?.extinct == false && gateWantsASelection

    /**
     * The watched-cell death was already true when this step began, so it is the *previous* beat's death and
     * this step has nothing to recover from. Cleared the moment the world reports no such death (the player
     * selected a living cell, or the controller's memory was reset), after which a fresh death on this step
     * offers as normal.
     */
    private var staleWatchedDeath: Boolean = false

    /**
     * Whether the unmet gate would answer differently if *something* were selected — i.e. the missing
     * selection is what is blocking it.
     *
     * Probed rather than declared, for the same reason [goalIsExtinction] is: the gates are predicates the
     * chapters author freely, and asking one what it depends on is more honest than maintaining a flag beside
     * it that someone has to remember to set. Both stand-ins are tried, since a threshold gate only moves for
     * one of them — see [CampaignQuery.withProbeSelection].
     */
    private val gateWantsASelection: Boolean
        get() {
            val q = lastQuery ?: return false
            if (q.focused != null) return false
            val gate = currentStep?.gate
            return evalGate(gate, q.withProbeSelection(full = false)) != gateMet ||
                evalGate(gate, q.withProbeSelection(full = true)) != gateMet
        }

    /** What the coach says while [extinctionOffer] holds, in place of the step's own text. */
    private fun extinctionText(): String =
        "Your cells are all gone - but their genome is not. Tap anywhere in the world to place a new cell " +
            "carrying the genome you last authored and carry on from there. Alternatively reset will rebuild the chapter " +
            "around you instead."

    /** What the coach says while [watchedCellOffer] holds. */
    private fun watchedCellText(): String =
        "The cell you were watching is gone. Tap an empty patch of the world to place a new cell " +
                "carrying the genome you last authored, and carry on from there. Reset will rebuild the chapter " +
                "around you instead."

    /** The coach's copy for the situation the player is actually in, or null when that is just the step. */
    private fun situationText(): String? = when {
        extinctionOffer -> extinctionText()
        watchedCellOffer -> watchedCellText()
        else -> null
    }

    /**
     * Begin a chapter, the world already standing — the host either restored this chapter's saved entry
     * state or built it from [Chapter.scenario] before calling (the selector's explicit "start this chapter"
     * path). Mid-campaign advances instead go through [advance], which carries the world forward unless the
     * next chapter is [Chapter.startsFreshWorld].
     *
     * [priorPath] is the route recorded against the world the host just restored, so resuming from the menu
     * keeps the branch history instead of restarting it. Empty (a cold start) ⇒ the path is just this
     * chapter. The current chapter is appended if the caller didn't already include it, so both a bare
     * `listOf()` and a full persisted path behave.
     */
    fun start(chapter: Chapter, controller: CytoController, priorPath: List<String> = emptyList()) {
        this.chapter = chapter
        controller.setSpeciesAliases(chapter.scenario.aliases)
        stepIndex = 0
        active = true
        pathInternal.clear()
        pathInternal.addAll(priorPath)
        if (pathInternal.lastOrNull() != chapter.id) pathInternal.add(chapter.id)
        // The world here IS this chapter's starting state, so it also carries the lineage a Reset restores.
        // Matters most on a menu re-entry, where nothing else would have populated it.
        controller.representativeGenome()?.let { carriedGenome = it }
        enterStep(controller)
        onChapterEntered(chapter, path)
    }

    /**
     * Reload the current chapter's authored world and restart it at step 1 — the coach's always-available
     * "Reset" control. The escape hatch for a world that has drifted off the script (a colony sprawled, the
     * founders died), and how the player pulls a [Chapter.startsFreshWorld] substrate back in.
     *
     * A chapter whose scenario seeds **no founders of its own** gets its lineage back instead of a canned
     * one: the world is emptied and its matter rebuilt as usual, then a single cell carrying [carriedGenome]
     * is placed under the camera ([onReseedLineage]). The campaign is a continuous world the player seeded
     * by hand, so a reset that discarded the genome they authored would throw away the very thing the
     * chapters are about, and the founder-less scenarios are exactly the ones with no other lineage to fall
     * back on. Chapters that *do* declare founders already carry a lineage in their recipe and are left
     * alone.
     */
    /**
     * The **other** way back: restore the world exactly as this chapter began — the player's own world,
     * their part-built experiment and all — and return to step 1.
     *
     * This is the non-destructive escape hatch, for "I have made a mess of this, put it back how it was".
     * [resetChapter] is the destructive one, for "start me over with a clean substrate". Neither rewrites
     * the stored entry state, so a clean reset never costs the player the ability to come back here.
     *
     * Falls back to [resetChapter] when nothing was ever stored (a chapter reached before entry states
     * existed, or a wiped store), so the control is never a dead button.
     */
    fun restartFromEntryState(controller: CytoController) {
        val ch = chapter ?: return
        if (!onRestoreEntryState(ch)) { resetChapter(controller); return }
        // The restored world is this chapter's starting state, so re-read the lineage a later clean reset
        // would re-seed — otherwise it would still hold whatever the player had drifted to.
        controller.representativeGenome()?.let { carriedGenome = it }
        stepIndex = 0
        enterStep(controller)
    }

    fun resetChapter(controller: CytoController) {
        val ch = chapter ?: return
        onWorldReset(ch)
        val genome = carriedGenome
        if (genome != null && ch.scenario.founders.isEmpty()) onReseedLineage(ch, genome)
        stepIndex = 0
        enterStep(controller)
    }

    /** Leave the campaign (host returns to the menu). */
    fun stop() {
        active = false; chapter = null; lastQuery = null; carriedGenome = null; host = null; pathInternal.clear()
    }

    private fun enterStep(controller: CytoController) {
        host = controller
        satisfiedDid.clear()
        gateMet = false
        showDetail = false
        resetMenuOpen = false
        // A death the player has already been shown belongs to the step that just ended, not this one.
        staleWatchedDeath = lastQuery?.watchedCellDied == true
        currentStep?.let { it.onEnter(controller); onStepEnter(it) }
    }

    /** Whether the current gate is satisfied (Next-gated steps are always "ready"). */
    val gateReady: Boolean get() = currentStep?.gate is Gate.Next || gateMet

    /**
     * The genome a cell placed by a [Control.Spawn] tap should carry, in precedence order. Lives here rather
     * than in each host because all three of them (desktop, Android, the agent harness) need the same answer
     * and had drifted into three near-copies of it.
     *
     *  1. A chapter that names a fixed [Chapter.spawnGenome] means it — Genesis deliberately hands out a
     *     GENE-LESS cell, which is the whole point of its opening beat, so an empty list is a real answer and
     *     not a missing one.
     *  2. Otherwise the player's own lineage, freshest first: the cell they have selected, then the genome
     *     they last authored, then [carriedGenome] — the snapshot taken at the chapter boundary.
     *
     * That last fallback is what makes Skip work. A player who skips a chapter never authors or selects
     * anything in it, so without it they arrive at the next chapter with an empty brush and place gene-less
     * cells into a world their lineage is supposed to be living in.
     */
    fun brushGenome(controller: CytoController): List<Gene>? {
        val ch = activeChapter
        // LAST AUTHORED FIRST. `CytoController.lastAuthoredGenome` is captured when an edit lands and not
        // when a cell is clicked, precisely so it means the shape of the player's intent rather than whatever
        // they happen to have selected — so it has to outrank the selection here too. Putting `heldGenome()`
        // first (as this briefly did) meant clicking any passing cell silently re-pointed the brush, and
        // clicking a GENE-LESS one re-pointed it at nothing: an empty genome is a non-null answer, so it won.
        val mine = { controller.lastAuthoredGenome ?: controller.heldGenome() ?: carriedGenome }
        return when {
            // Ch9's "last-modified brush" is the one case that genuinely wants the selection: the chapter
            // asks for it by name so the player can iterate a lineage cell by cell.
            ch?.spawnCopiesHeldCell == true -> controller.heldGenome() ?: mine() ?: ch.spawnGenome
            // The extinction offer exists to hand their work back, so it always prefers theirs.
            extinctionOffer -> mine() ?: ch?.spawnGenome
            !ch?.spawnGenome.isNullOrEmpty() -> ch?.spawnGenome
            else -> mine() ?: ch?.spawnGenome
        }
    }

    /** Advance to the next step if the current gate allows it. Returns true if it advanced (or completed
     *  the chapter). Used by the agent harness to drive "click Next". */
    fun tryAdvance(controller: CytoController): Boolean {
        if (!active || !gateReady) return false
        advance(controller)
        return true
    }

    /** A structured snapshot of the coach state for headless observation (the agent harness dumps it). */
    fun snapshot(): CoachSnapshot? {
        val ch = chapter ?: return null
        val step = currentStep ?: return null
        return CoachSnapshot(
            chapterId = ch.id, chapterTitle = ch.title,
            stepIndex = stepIndex, stepCount = ch.steps.size,
            // A situation offer REPLACES the step's text on screen, so it has to replace it here too — a
            // headless observer that still saw the step text would be watching a different coach than the
            // player is.
            text = situationText() ?: copy(step.text, step.altText),
            goal = goalText(step.gate)?.let { copy(it) },
            gateReady = gateReady, world = step.world,
        )
    }

    private fun goalText(gate: Gate): String? = when (gate) {
        is Gate.Next -> null
        is Gate.World -> gate.desc
        is Gate.Did -> gate.desc
        is Gate.All -> "multiple goals"
    }

    /** Advance gate evaluation. [actions] is the set of interactions the host detected this frame. */
    fun update(query: CampaignQuery, actions: Set<PlayerAction>) {
        if (!active) return
        lastQuery = query
        if (!query.watchedCellDied) staleWatchedDeath = false
        satisfiedDid.addAll(actions)
        gateMet = evalGate(currentStep?.gate, query)
        // A step that opted into Step.autoAdvance moves on here rather than waiting for the button. Uses the
        // controller the host handed over at start/advance: this is the same transition the Next click makes,
        // just triggered by the world instead of the player.
        if (gateMet && currentStep?.autoAdvance == true) host?.let { advance(it) }
    }

    private fun evalGate(gate: Gate?, query: CampaignQuery): Boolean = when (gate) {
        null -> false
        is Gate.Next -> false                       // Next advances only via the button
        is Gate.World -> gate.met(query)
        is Gate.Did -> gate.action in satisfiedDid
        is Gate.All -> gate.gates.all { evalGate(it, query) }
    }

    private fun advance(controller: CytoController) {
        val ch = chapter ?: return
        if (stepIndex >= ch.steps.lastIndex) {
            val query = lastQuery
            onChapterCompleted(ch.id)
            // Segue into the next chapter in the SAME world — no drop to the selector. Only rebuild the world
            // when the next chapter marks itself a fresh start (Ch8's swimmer); otherwise the player's living
            // world, which the coaching just walked them into being the next chapter's starting point, carries
            // straight on.
            // A chapter may choose its own successor from the finished world (Chapter.next) — the campaign's
            // branch point. Anything it names must actually be in the list; otherwise, and for every linear
            // chapter, the successor is simply the next one along.
            val chosen = query?.let { q -> ch.next?.invoke(q) }?.let { id -> chapters.firstOrNull { it.id == id } }
            val next = chosen ?: chapters.getOrNull(chapters.indexOfFirst { it.id == ch.id } + 1)
            if (next == null) {
                active = false
                chapter = null
                onCampaignComplete()
            } else {
                // Snapshot the lineage BEFORE the handover, while the finished chapter's world is still
                // standing: this is the genome the player authored, and it is what a Reset in the next
                // chapter restores. Keep the previous snapshot if the world is empty (nothing to read).
                controller.representativeGenome()?.let { carriedGenome = it }
                if (next.startsFreshWorld) onWorldReset(next)
                chapter = next
                controller.setSpeciesAliases(next.scenario.aliases)
                stepIndex = 0
                pathInternal.add(next.id)
                enterStep(controller)
                onChapterEntered(next, path)
            }
        } else {
            stepIndex++
            enterStep(controller)
        }
    }

    /** Pixels the narrow top-docked coach occupies down from the screen top (its bottom edge), 0 when it isn't
     *  showing. The host feeds this to the camera recentre so a selected cell centres in the band between the
     *  coach and the cell sheet, not behind the coach. Reflects the last [render] (one frame stale — fine for a
     *  damped follow). */
    var coachTopInsetPx: Float = 0f
        private set

    /** The coach panel's own rect, captured as it is emitted — the anchor end of the spotlight connector.
     *  Null while no coach is drawn. */
    private var coachRect: Ui.UiElement? = null

    /**
     * **Point at the thing the step is talking about**: a box around the widget named by the current
     * [Step.spotlight], and an elbow connector from the coach panel to it.
     *
     * A SECOND pass, called last inside the host's frame, and that ordering is the whole design. The coach
     * panel is drawn early — before the gene editor, so the editor's dropdowns and sheets sit over it — but
     * the widget being pointed at only exists once the editor has laid itself out, and a box drawn before it
     * would be painted over. Emitting the box last solves both at once: `Ui.element` sees a fully-built
     * frame, and the canvas lands on top of everything.
     *
     * Silent when there is no target, or when the target doesn't resolve — a collapsed group, a closed panel,
     * a row scrolled out of view. The step's hint text is the fallback and is always drawn by [render]
     * itself, so a spotlight that can't point is a quieter coach, never a broken one.
     *
     * ⚠️ A scrolled-away row still resolves (`Ui.element` enumerates regions, and does not intersect the
     * scroll clip), so a box can currently be drawn outside its viewport. Handling that — suppress, or scroll
     * the target into view — is the next phase; it is why this pilots on Genesis, whose panel does not scroll.
     */
    fun renderSpotlight(ui: UiBuilder) {
        if (!active) return
        val spot = currentStep?.spotlight ?: return
        val target = spot.target ?: return
        // The situation offers (extinction / watched-cell) replace the step's copy with a recovery offer, so
        // the step's own spotlight is pointing at something the player is no longer being asked to do.
        if (extinctionOffer || watchedCellOffer) return
        val hit = ui.element(target, spot.occurrence) ?: return
        val from = coachRect
        ui.canvas {
            val d = ui.density
            val pad = 3f * d
            val x = hit.x - pad; val y = hit.y - pad
            val w = hit.w + pad * 2f; val h = hit.h + pad * 2f
            if (from != null) elbow(this, from, x, y, w, h, d)
            // The box last, so the connector tucks under its edge rather than crossing into the widget.
            outline(this, x, y, w, h, 2f * d)
        }
    }

    /**
     * A right-angled connector from the coach panel to the spotlit box, routed through the **gap between
     * them** and meeting the box's near edge.
     *
     * Right angles rather than a straight diagonal because the rect primitive is axis-aligned with no
     * rotation ([CanvasBuilder.rect]): a diagonal would need a staircase of small rects or a new engine
     * primitive, and against a UI that is entirely rectangles the elbow reads better anyway.
     *
     * Routing is the part that matters, and the naive version is worse than no line at all: run to the
     * target's centre-x and turn, and the line is drawn straight down the middle of the cell panel across
     * every gene card between the coach and the thing it is pointing at.
     *
     * So the line leaves the coach's near horizontal edge at the coach's **centre**, climbs clear, then runs
     * in to the box's near vertical edge — the whole path over open world, meeting the box from outside.
     *
     * Centre rather than the nearer side edge, because a panel's rect is not what you can see of it: the
     * desktop coach is auto-sized to its longest line and runs on *underneath* the docked cell panel, which
     * is drawn later and covers it. A line leaving that hidden right edge appears to start in the middle of
     * the cell panel, from nothing. The centre of a panel is visible by construction — anything covering it
     * would have covered the coach.
     */
    private fun elbow(c: CanvasBuilder, from: Ui.UiElement, bx: Float, by: Float, bw: Float, bh: Float, d: Float) {
        val t = 2f * d                                   // line thickness
        val boxCy = by + bh * 0.5f
        val cx = from.x + from.w * 0.5f
        fun hRun(x0: Float, x1: Float, y: Float) = c.rect(minOf(x0, x1), y - t * 0.5f, kotlin.math.abs(x1 - x0), t, LINE)
        fun vRun(x: Float, y0: Float, y1: Float) = c.rect(x - t * 0.5f, minOf(y0, y1), t, kotlin.math.abs(y1 - y0), LINE)
        // The coach edge the target is on. A target level with the coach still leaves from an edge, not a side.
        val edgeY = if (boxCy < from.y + from.h * 0.5f) from.y else from.y + from.h
        if (bx > cx || bx + bw < cx) {
            // Off to one side: up (or down) the open channel at the coach's centre, then in to the near edge.
            val nearX = if (bx > cx) bx else bx + bw
            vRun(cx, edgeY, boxCy)
            hRun(cx, nearX, boxCy)
        } else {
            // Directly above/below (the phone: coach docked top, sheet below) — straight down, since no route
            // avoids what is in between.
            vRun(bx + bw * 0.5f, edgeY, if (boxCy < edgeY) by + bh else by)
        }
    }

    /** A hollow rectangle, as four thin rects — the toolkit draws fills only. */
    private fun outline(c: CanvasBuilder, x: Float, y: Float, w: Float, h: Float, t: Float) {
        c.rect(x, y, w, t, LINE)
        c.rect(x, y + h - t, w, t, LINE)
        c.rect(x, y, t, h, LINE)
        c.rect(x + w - t, y, t, h, LINE)
    }

    /**
     * Draw the coach panel — a top-docked banner when [narrow], a bottom-centre panel stacked above the HUD
     * bar otherwise. Call inside the host's `ui.frame { }` after the other overlays.
     *
     * [freeAreaDxPx] is the host's `GeneEditor.freeAreaOffsetPx` dx — how far the centre of the *un-obscured*
     * world is from the screen centre, which on a wide screen is half the docked cell panel. The coach centres
     * on THAT rather than on the screen, because the cell panel is drawn after it and covers anything that
     * reaches underneath: at 1200px the coach spanned 272..928 while the dock began at 808, so the tail of
     * every line sat behind the panel. Passing 0 keeps the old screen-centred behaviour.
     */
    fun render(ui: UiBuilder, controller: CytoController, narrow: Boolean = false, freeAreaDxPx: Float = 0f) {
        coachTopInsetPx = 0f
        coachRect = null
        val ch = chapter ?: return
        val step = currentStep ?: return
        // On a phone the coach is a single top-docked panel (the only campaign modal there): it clears the
        // bottom controls + the cell info sheet, and keeps the full Skip/More/Next controls. It bounds its
        // width to the screen (wrapping/clipping every row) since a phone can't afford the desktop's 58-col
        // lines. On desktop it is a bottom-centre panel that stacks above the HUD bar and clears the
        // right-docked cell panel, so it always shows in full — nothing on that width needs the room.
        val counter = "(${stepIndex + 1}/${ch.steps.size})"
        if (narrow) {
            val budget = wrapBudget(ui, TOP_TEXT_DP, PAD_DP, TOP_MARGIN_DP)
            // Counter first so it survives a title clip on a very narrow screen. Fill the width (a top banner
            // with padding) so it reads as a phone app bar, not a left-hugging box.
            val h = renderFull(ui, ch, step, controller, "$counter ${ch.title}", Anchor.TopLeft, margin = TOP_MARGIN_DP, wrapChars = budget, textSize = TOP_TEXT_DP, fillWidth = true)
            coachTopInsetPx = TOP_MARGIN_DP * ui.density + h   // the coach's bottom edge, for the camera recentre
        } else {
            // BOTTOM_MARGIN_DP, not a hand-tuned gap: the host draws the HUD bar first, so this panel stacks
            // directly above it and only needs the same edge gap the bar uses.
            // The cell panel is docked to the right and drawn AFTER the coach, so it covers whatever of the
            // coach reaches under it. A bottom-CENTRE anchor centres on the screen, which is the wrong centre
            // while that dock is up: at 1200px the coach spans 272..928 and the dock starts at 808, so the
            // last two words of every line were hidden behind it. Centre the coach in what is actually free
            // instead, and wrap it to that width so a narrow desktop window shrinks the text rather than
            // sliding it back under the panel.
            // freeAreaDxPx is HALF the occluded width (it names the centre of what is left), so the coach has
            // exactly that much less room and sits exactly that far off centre.
            val free = ui.screenW - 2f * kotlin.math.abs(freeAreaDxPx)
            renderFull(
                ui, ch, step, controller, "${ch.title}  $counter", Anchor.BottomCenter,
                margin = BOTTOM_MARGIN_DP,
                wrapChars = wrapBudget(ui, BOTTOM_TEXT_DP, PAD_DP, BOTTOM_MARGIN_DP, availPx = free),
                textSize = BOTTOM_TEXT_DP, fillWidth = false, offsetX = freeAreaDxPx / ui.density,
            )
        }
        // Where the coach actually landed — it is auto-sized and anchor-placed, so this is the only way to
        // know. Read straight after emitting it, before any later panel overwrites the toolkit's note.
        coachRect = ui.lastPanelRect
        // Drawn last on both widths so its scrim covers the coach itself.
        if (resetMenuOpen) renderResetSheet(ui, controller, narrow)
    }

    /**
     * The two ways back, behind the coach's single Reset button. Each gets a line saying what it will
     * actually do, because one of them throws the player's world away and the labels alone don't carry
     * that. Dismissing (scrim/back) is a third, silent option: changing your mind is the common case.
     *
     * Geometry mirrors the gene editor's picker ([GeneEditor.pickSheet]): a bounded centre box on desktop,
     * a bottom sheet with phone-sized rows on a narrow screen.
     */
    private fun renderResetSheet(ui: UiBuilder, controller: CytoController, narrow: Boolean) {
        val body: PanelBuilder.() -> Unit = {
            // Descriptions are kept short deliberately: listRow CLIPS rather than wraps, and the phone's
            // sheet only fits ~36 characters at this row size (verified on a 1080x2160 shot).
            listRow("RESTART", "BACK TO HOW THIS CHAPTER BEGAN") {
                resetMenuOpen = false; restartFromEntryState(controller)
            }
            listRow("CLEAN", "WIPE IT ALL, KEEP YOUR GENOME") {
                resetMenuOpen = false; resetChapter(controller)
            }
        }
        val dismiss = { resetMenuOpen = false }
        if (narrow) {
            ui.sheet("coach-reset", "RESET", onDismiss = dismiss, heightFraction = 0.34f,
                rowHeight = 48f, textSize = 16f, body = body)
        } else {
            val w = minOf(520f * ui.density, ui.screenW * 0.6f)
            val h = minOf(ui.screenH * 0.5f, 240f * ui.density)
            ui.sheet("coach-reset", "RESET", onDismiss = dismiss,
                boxX = (ui.screenW - w) * 0.5f, boxY = (ui.screenH - h) * 0.5f, boxW = w, boxH = h,
                rowHeight = 34f, textSize = 15f, body = body)
        }
    }

    /** The full coach body — title, wrapped step text, spotlight hint, objective, optional detail, and the
     *  Skip / More / Next controls — laid out at [anchor]. All text rows wrap/clip to [wrapChars] so the
     *  auto-sized panel never grows past the screen (the phone's top-docked coach relies on this). */
    private fun renderFull(
        ui: UiBuilder, ch: Chapter, step: Step, controller: CytoController,
        header: String, anchor: Anchor, margin: Float, wrapChars: Int, textSize: Float, fillWidth: Boolean,
        offsetX: Float = 0f,
    ): Float {
        val query = lastQuery
        val gate = step.gate
        val nextEnabled = gate is Gate.Next || gateMet
        return ui.panel(anchor, margin = margin, padding = PAD_DP, background = 0x11182AF2L, rowHeight = 22f, textSize = textSize, fillWidth = fillWidth, offsetX = offsetX) {
            title(clip(header, wrapChars), 0x6FD6C4FFL)
            gap(4f)
            val situation = situationText()
            val body = situation ?: copy(step.text, step.altText)
            for (line in wrap(body, wrapChars)) row(line, if (situation != null) 0xFFD86EFFL else 0xEAEEF6FFL)
            step.spotlight?.hint?.let { gap(2f); for (line in wrap(copy("→ $it"), wrapChars)) row(line, 0xFFD86EFFL) }

            // Objective line + progress for a World / Did gate.
            if (gate is Gate.World) {
                gap(4f)
                val prog = query?.let { gate.progress?.invoke(it) }
                val suffix = if (prog != null) "   ${prog.first}/${prog.second}" else ""
                for (line in wrap(copy("GOAL: ${gate.desc}$suffix"), wrapChars)) row(line, if (gateMet) 0x8FE39AFFL else 0x9AA6BCFFL)
            } else if (gate is Gate.Did) {
                gap(4f)
                for (line in wrap(copy("GOAL: ${gate.desc}"), wrapChars)) row(line, if (gateMet) 0x8FE39AFFL else 0x9AA6BCFFL)
            }

            if (showDetail && step.detail != null) {
                gap(4f)
                for (line in wrap(copy(step.detail), wrapChars)) row(line, 0xB6C0D4FFL)
            }

            gap(6f)
            val buttons = ArrayList<Triple<String, Long, () -> Unit>>()
            if (step.detail != null) buttons.add(Triple(if (showDetail) "Less" else "More", 0x2A3550FFL) { showDetail = !showDetail })
            // One button, two ways back — the choice lives in a sheet ([renderResetSheet]) rather than a
            // fourth and fifth control, because the coach row is shared with More/Skip/Next and is tight on
            // a phone. It also gives each option room for a line explaining what it will do, which matters
            // when one of them is destructive.
            buttons.add(Triple("Reset", 0x4A3A2AFFL) { resetMenuOpen = true })
            buttons.add(Triple("Skip", 0x53384AFFL) { advance(controller) })
            buttons.add(Triple("Next >", if (nextEnabled) 0x2E6E5EFFL else 0x2A3040FFL) { if (nextEnabled) advance(controller) })
            actionRow(buttons)
        }
    }

    /** Characters that fit one line of a [textSizeDp]-sized panel row across the screen, minus padding+margin. */
    private fun wrapBudget(
        ui: UiBuilder, textSizeDp: Float, padDp: Float, marginDp: Float, availPx: Float = ui.screenW,
    ): Int {
        val textH = textSizeDp * ui.density
        val sample = "abcdefghijklmnopqrstuvwxyz "
        val avgChar = (UiTextRenderer.measureWidthPx(sample, textH) / sample.length).coerceAtLeast(1f)
        val avail = availPx - 2f * (padDp + marginDp) * ui.density
        return (avail / avgChar).toInt().coerceIn(16, COACH_WRAP)
    }


    /** Read-only view of the coach state, for headless/agent observation. */
    class CoachSnapshot(
        val chapterId: String, val chapterTitle: String,
        val stepIndex: Int, val stepCount: Int,
        val text: String, val goal: String?,
        val gateReady: Boolean, val world: WorldRun,
    )

    companion object {
        /** The spotlight's box + connector: the coach's own hint amber ([renderFull] draws hint rows in it),
         *  so the line and the sentence that sent the player down it read as one voice. */
        private const val LINE = 0xFFD86EFFL

        private const val BOTTOM_MARGIN_DP = 10f  // desktop coach's gap from the bottom edge — matches CytoHud's bar
        private const val COACH_WRAP = 58   // approx chars per coach line before wrapping (desktop cap)
        private const val PAD_DP = 14f      // coach panel padding
        private const val TOP_MARGIN_DP = 12f    // phone top-docked coach inset from the top-left corner
        private const val TOP_TEXT_DP = 12f      // phone coach text size (smaller than desktop, to fit)
        private const val BOTTOM_TEXT_DP = 22f * 0.68f   // desktop bottom coach (the historical default)

        /** Truncate to [maxChars], marking a cut with ".." (the bitmap font has no "…" glyph). */
        internal fun clip(text: String, maxChars: Int): String =
            if (text.length <= maxChars) text else text.take(maxChars - 2).trimEnd() + ".."

        /** Greedy word-wrap to [maxChars]-wide lines (the toolkit's rows are single-line). */
        internal fun wrap(text: String, maxChars: Int): List<String> {
            val out = ArrayList<String>()
            val line = StringBuilder()
            for (word in text.split(' ')) {
                if (line.isEmpty()) { line.append(word); continue }
                if (line.length + 1 + word.length <= maxChars) { line.append(' ').append(word) }
                else { out.add(line.toString()); line.setLength(0); line.append(word) }
            }
            if (line.isNotEmpty()) out.add(line.toString())
            return out
        }
    }
}
