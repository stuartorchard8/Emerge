package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.CytoController
import org.emerge.render.torus.ui.Anchor
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
    private var showDetail: Boolean = false

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

    /** The host's input phrasing, interpolated into coach copy so a `{pan}`/`{zoom}` gesture reads correctly
     *  for this platform — [InputHints.MOUSE] on desktop, [InputHints.TOUCH] on a phone. The host sets it once
     *  at construction; defaults to MOUSE. */
    var inputHints: InputHints = InputHints.MOUSE

    /** Expand a coach string's input tokens for this host. */
    private fun copy(s: String) = inputHints.expand(s)

    val activeChapter: Chapter? get() = chapter
    val currentStep: Step? get() = chapter?.steps?.getOrNull(stepIndex)

    /** Which controls the host should keep live this step (ALL when no chapter is active). */
    val controlMask: ControlMask get() = if (active) currentStep?.allow ?: ControlMask.ALL else ControlMask.ALL

    /** Begin a chapter, the world already built (the menu/host rebuilt it from [Chapter.scenario] before
     *  calling — the selector's explicit "start this chapter" path). Mid-campaign advances instead go through
     *  [advance], which carries the world forward unless the next chapter is [Chapter.startsFreshWorld]. */
    fun start(chapter: Chapter, controller: CytoController) {
        this.chapter = chapter
        controller.setSpeciesAliases(chapter.scenario.aliases)
        stepIndex = 0
        active = true
        enterStep(controller)
    }

    /** Reload the current chapter's authored world and restart it at step 1 — the coach's always-available
     *  "Reset" control. The escape hatch for a world that has drifted off the script (a colony sprawled, the
     *  founders died), and how the player pulls a [Chapter.startsFreshWorld] substrate back in. */
    fun resetChapter(controller: CytoController) {
        val ch = chapter ?: return
        onWorldReset(ch)
        stepIndex = 0
        enterStep(controller)
    }

    /** Leave the campaign (host returns to the menu). */
    fun stop() { active = false; chapter = null; lastQuery = null }

    private fun enterStep(controller: CytoController) {
        satisfiedDid.clear()
        gateMet = false
        showDetail = false
        currentStep?.let { it.onEnter(controller); onStepEnter(it) }
    }

    /** Whether the current gate is satisfied (Next-gated steps are always "ready"). */
    val gateReady: Boolean get() = currentStep?.gate is Gate.Next || gateMet

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
            text = copy(step.text), goal = goalText(step.gate)?.let { copy(it) },
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
        satisfiedDid.addAll(actions)
        gateMet = evalGate(currentStep?.gate, query)
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
            onChapterCompleted(ch.id)
            // Segue into the next chapter in the SAME world — no drop to the selector. Only rebuild the world
            // when the next chapter marks itself a fresh start (Ch8's swimmer); otherwise the player's living
            // world, which the coaching just walked them into being the next chapter's starting point, carries
            // straight on.
            val next = chapters.getOrNull(chapters.indexOfFirst { it.id == ch.id } + 1)
            if (next == null) {
                active = false
                chapter = null
                onCampaignComplete()
            } else {
                if (next.startsFreshWorld) onWorldReset(next)
                chapter = next
                controller.setSpeciesAliases(next.scenario.aliases)
                stepIndex = 0
                enterStep(controller)
            }
        } else {
            stepIndex++
            enterStep(controller)
        }
    }

    /** Draw the coach panel. Call inside the host's `ui.frame { }` after the other overlays. When
     *  [collapsed] (a cell/gene editor owns the bottom of the screen), the coach shrinks to a single-line
     *  pill in the top-left — progress + the current step's actionable hint — so onboarding stays visible
     *  while editing instead of disappearing (UI_REDESIGN.md §6.1). */
    /** Pixels the narrow top-docked coach occupies down from the screen top (its bottom edge), 0 when it isn't
     *  showing. The host feeds this to the camera recentre so a selected cell centres in the band between the
     *  coach and the cell sheet, not behind the coach. Reflects the last [render] (one frame stale — fine for a
     *  damped follow). */
    var coachTopInsetPx: Float = 0f
        private set

    fun render(ui: UiBuilder, controller: CytoController, collapsed: Boolean = false, narrow: Boolean = false) {
        coachTopInsetPx = 0f
        val ch = chapter ?: return
        val step = currentStep ?: return
        // On a phone the coach is a single top-docked panel (the only campaign modal there): it clears the
        // bottom controls + the cell info sheet, and keeps the full Skip/More/Next controls. It bounds its
        // width to the screen (wrapping/clipping every row) since a phone can't afford the desktop's 58-col
        // lines. On desktop it stays a bottom-centre panel that collapses to a top-left pill while editing.
        val counter = "(${stepIndex + 1}/${ch.steps.size})"
        if (narrow) {
            val budget = wrapBudget(ui, TOP_TEXT_DP, PAD_DP, TOP_MARGIN_DP)
            // Counter first so it survives a title clip on a very narrow screen. Fill the width (a top banner
            // with padding) so it reads as a phone app bar, not a left-hugging box.
            val h = renderFull(ui, ch, step, controller, "$counter ${ch.title}", Anchor.TopLeft, margin = TOP_MARGIN_DP, wrapChars = budget, textSize = TOP_TEXT_DP, fillWidth = true)
            coachTopInsetPx = TOP_MARGIN_DP * ui.density + h   // the coach's bottom edge, for the camera recentre
            return
        }
        if (collapsed) { renderPill(ui, ch, step); return }
        // BOTTOM_MARGIN_DP, not a hand-tuned gap: the host draws the HUD bar first, so this panel stacks
        // directly above it and only needs the same edge gap the bar uses.
        renderFull(ui, ch, step, controller, "${ch.title}  $counter", Anchor.BottomCenter, margin = BOTTOM_MARGIN_DP, wrapChars = COACH_WRAP, textSize = BOTTOM_TEXT_DP, fillWidth = false)
    }

    /** The full coach body — title, wrapped step text, spotlight hint, objective, optional detail, and the
     *  Skip / More / Next controls — laid out at [anchor]. All text rows wrap/clip to [wrapChars] so the
     *  auto-sized panel never grows past the screen (the phone's top-docked coach relies on this). */
    private fun renderFull(
        ui: UiBuilder, ch: Chapter, step: Step, controller: CytoController,
        header: String, anchor: Anchor, margin: Float, wrapChars: Int, textSize: Float, fillWidth: Boolean,
    ): Float {
        val query = lastQuery
        val gate = step.gate
        val nextEnabled = gate is Gate.Next || gateMet
        return ui.panel(anchor, margin = margin, padding = PAD_DP, background = 0x11182AF2L, rowHeight = 22f, textSize = textSize, fillWidth = fillWidth) {
            title(clip(header, wrapChars), 0x6FD6C4FFL)
            gap(4f)
            for (line in wrap(copy(step.text), wrapChars)) row(line, 0xEAEEF6FFL)
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
            // Reset: reload this chapter's clean starting world. Always available — the escape hatch for a
            // continuous world that has drifted off-script (see [resetChapter]).
            buttons.add(Triple("Reset", 0x4A3A2AFFL) { resetChapter(controller) })
            buttons.add(Triple("Skip", 0x53384AFFL) { advance(controller) })
            buttons.add(Triple("Next >", if (nextEnabled) 0x2E6E5EFFL else 0x2A3040FFL) { if (nextEnabled) advance(controller) })
            actionRow(buttons)
        }
    }

    /** Characters that fit one line of a [textSizeDp]-sized panel row across the screen, minus padding+margin. */
    private fun wrapBudget(ui: UiBuilder, textSizeDp: Float, padDp: Float, marginDp: Float): Int {
        val textH = textSizeDp * ui.density
        val sample = "abcdefghijklmnopqrstuvwxyz "
        val avgChar = (UiTextRenderer.measureWidthPx(sample, textH) / sample.length).coerceAtLeast(1f)
        val avail = ui.screenW - 2f * (padDp + marginDp) * ui.density
        return (avail / avgChar).toInt().coerceIn(16, COACH_WRAP)
    }

    /** The collapsed coach — a top-left pill: `▸ N/M` progress + the step's hint (or its first text line),
     *  clipped to fit. Non-interactive; the full coach returns as soon as the editor closes. */
    private fun renderPill(ui: UiBuilder, ch: Chapter, step: Step) {
        val hint = copy(step.spotlight?.hint ?: step.text)
        val progress = "${stepIndex + 1}/${ch.steps.size}"
        ui.panel(Anchor.TopLeft, margin = 12f, padding = 10f, background = 0x11182AF2L, rowHeight = 22f) {
            title("STEP $progress", 0x6FD6C4FFL)
            gap(2f)
            row("→ ${clip(hint, PILL_WRAP)}", 0xFFD86EFFL)
        }
    }

    /** Read-only view of the coach state, for headless/agent observation. */
    class CoachSnapshot(
        val chapterId: String, val chapterTitle: String,
        val stepIndex: Int, val stepCount: Int,
        val text: String, val goal: String?,
        val gateReady: Boolean, val world: WorldRun,
    )

    companion object {
        private const val BOTTOM_MARGIN_DP = 10f  // desktop coach's gap from the bottom edge — matches CytoHud's bar
        private const val COACH_WRAP = 58   // approx chars per coach line before wrapping (desktop cap)
        private const val PILL_WRAP = 42    // the collapsed pill is one line; clip the hint to this
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
