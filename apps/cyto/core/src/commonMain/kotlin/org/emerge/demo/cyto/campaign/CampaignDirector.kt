package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.CytoController
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.UiBuilder

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

    /** Called with the chapter id when its final step is advanced past (host unlocks the next chapter). */
    var onChapterComplete: (String) -> Unit = {}

    /** Invoked whenever a new step becomes current (chapter start + each advance). The host applies the
     *  step's [WorldRun] here (pause/resume the sim). */
    var onStepEnter: (Step) -> Unit = {}

    val activeChapter: Chapter? get() = chapter
    val currentStep: Step? get() = chapter?.steps?.getOrNull(stepIndex)

    /** Which controls the host should keep live this step (ALL when no chapter is active). */
    val controlMask: ControlMask get() = if (active) currentStep?.allow ?: ControlMask.ALL else ControlMask.ALL

    /** Begin a chapter. The host has already rebuilt the world from [Chapter.scenario]. */
    fun start(chapter: Chapter, controller: CytoController) {
        this.chapter = chapter
        stepIndex = 0
        active = true
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
            text = step.text, goal = goalText(step.gate),
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
            active = false
            val id = ch.id
            chapter = null
            onChapterComplete(id)
        } else {
            stepIndex++
            enterStep(controller)
        }
    }

    /** Draw the coach panel. Call inside the host's `ui.frame { }` after the other overlays. When
     *  [collapsed] (a cell/gene editor owns the bottom of the screen), the coach shrinks to a single-line
     *  pill in the top-left — progress + the current step's actionable hint — so onboarding stays visible
     *  while editing instead of disappearing (UI_REDESIGN.md §6.1). */
    fun render(ui: UiBuilder, controller: CytoController, collapsed: Boolean = false) {
        val ch = chapter ?: return
        val step = currentStep ?: return
        if (collapsed) { renderPill(ui, ch, step); return }
        val query = lastQuery
        val gate = step.gate
        val nextEnabled = gate is Gate.Next || gateMet

        // margin lifts the coach above the bottom control bar (~160px tall at typical resolutions) so it
        // doesn't overlap the corner buttons (Brush / Mode / Color / Light-Matter / speed).
        ui.panel(Anchor.BottomCenter, margin = 180f, padding = 14f, background = 0x11182AF2L, rowHeight = 22f) {
            title("${ch.title}    (${stepIndex + 1}/${ch.steps.size})", 0x6FD6C4FFL)
            gap(4f)
            for (line in wrap(step.text, COACH_WRAP)) row(line, 0xEAEEF6FFL)
            step.spotlight?.hint?.let { gap(2f); row("→ $it", 0xFFD86EFFL) }

            // Objective line + progress bar for a World gate.
            if (gate is Gate.World) {
                gap(4f)
                val prog = query?.let { gate.progress?.invoke(it) }
                val suffix = if (prog != null) "   ${prog.first}/${prog.second}" else ""
                row("GOAL: ${gate.desc}$suffix", if (gateMet) 0x8FE39AFFL else 0x9AA6BCFFL)
            } else if (gate is Gate.Did) {
                gap(4f)
                row("GOAL: ${gate.desc}", if (gateMet) 0x8FE39AFFL else 0x9AA6BCFFL)
            }

            if (showDetail && step.detail != null) {
                gap(4f)
                for (line in wrap(step.detail, COACH_WRAP)) row(line, 0xB6C0D4FFL)
            }

            gap(6f)
            val buttons = ArrayList<Triple<String, Long, () -> Unit>>()
            if (step.detail != null) buttons.add(Triple(if (showDetail) "Less" else "More", 0x2A3550FFL) { showDetail = !showDetail })
            buttons.add(Triple("Skip", 0x53384AFFL) { advance(controller) })
            buttons.add(Triple("Next >", if (nextEnabled) 0x2E6E5EFFL else 0x2A3040FFL) { if (nextEnabled) advance(controller) })
            actionRow(buttons)
        }
    }

    /** The collapsed coach — a top-left pill: `▸ N/M` progress + the step's hint (or its first text line),
     *  clipped to fit. Non-interactive; the full coach returns as soon as the editor closes. */
    private fun renderPill(ui: UiBuilder, ch: Chapter, step: Step) {
        val hint = step.spotlight?.hint ?: step.text
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
        private const val COACH_WRAP = 58   // approx chars per coach line before wrapping
        private const val PILL_WRAP = 42    // the collapsed pill is one line; clip the hint to this

        /** Truncate to [maxChars], appending an ellipsis when cut. */
        internal fun clip(text: String, maxChars: Int): String =
            if (text.length <= maxChars) text else text.take(maxChars - 1).trimEnd() + "…"

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
