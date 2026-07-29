package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.Clause
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.GeneAction
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneCondition
import org.emerge.demo.cyto.sim.Molecules
import org.emerge.demo.cyto.sim.Operand
import org.emerge.demo.cyto.sim.SpeciesNames
import org.emerge.demo.cyto.sim.SpeciesRegistry
import org.emerge.sim.core.EntityId
import org.emerge.render.torus.ui.HoverAction
import org.emerge.render.torus.ui.PanelBuilder
import org.emerge.render.torus.ui.UiBuilder
import org.emerge.render.torus.ui.UiTok

/**
 * The in-game **gene-editor kit**. Renders the last-held cell's info panel with a tappable gene list;
 * tapping a gene pops a second panel (stacked below) for full editing — every field is a click-to-expand
 * dropdown ([UiBuilder]'s picker) and the threshold a hold-to-repeat ± stepper. Edits accumulate in a
 * local **draft** and only commit to the live cell on **DONE** (CANCEL discards); DUPLICATE and DELETE
 * act on the live genome immediately (DELETE closes the editor). All mutation goes through
 * [CytoController]'s thread-safe gene-edit API.
 *
 * Immediate-mode: [render] is called inside `ui.frame { }` every frame; this object holds the cross-frame
 * editor state (which gene, the draft, which dropdown is open).
 */
/** A banked gene-group as the editor sees it: a [name] and its [genes] (all tagged with that group). The host
 *  supplies these from its on-disk gene bank; commonMain stays file-I/O-free. */
class GeneSnippet(val name: String, val genes: List<Gene>)

class GeneEditor {
    /** A registry-less grouping used in free play: [GenomeGrouping.sections] still buckets a tagged genome by
     *  its own tags (auto-coloured by name); it just offers no "+ ADD" inserts (those are campaign-authored). */
    private val EMPTY_GROUPING = GenomeGrouping(emptyList())

    private companion object {
        // Container geometry, shared by the render paths and freeAreaOffsetPx (the source of truth for how
        // much of the world each layout occludes). dp — multiply by scale for framebuffer px.
        const val CELL_PANEL_DP = 380f       // wide: dockRight cell-panel width
        const val PANEL_MARGIN_DP = 12f      // wide: dockRight / editor-column margin
        // The sheet is now the gene EDITOR, not a list that opens one, so it earns more of the screen than it
        // did when a full-screen modal did the editing.
        const val SHEET_FRACTION = 0.72f     // narrow: dockBottom cell-sheet full (L2) height fraction
        const val PEEK_FRACTION = 0.2f       // narrow: dockBottom cell-sheet collapsed peek (L1) height

        // Drag-and-drop re-grouping (desktop, §8a): a gene card is a drag source "gene-drag-<i>"; group
        // section headers are drop targets "group-drop:<name>" ("" = the untagged OTHER bucket); a drop on the
        // drag-only "new group" placeholder opens the typed-name dialog for that gene.
        const val GENE_DRAG_PREFIX = "gene-drag-"
        const val GROUP_DROP_PREFIX = "group-drop:"
        const val GROUP_DROP_NEW = "group-drop-new"
        // Drag-only action dropzones: duplicate sits at the origin group's end (instant); delete is in the
        // bottom stack (opens a confirm). Reorder slots between same-group genes carry the target index.
        const val GENE_DROP_DUP = "gene-drop-dup"
        const val GENE_DROP_DEL = "gene-drop-del"
        const val GENE_REORDER_PREFIX = "gene-reorder:"

        // The starting gene for "+ NEW GENE" / "+ NEW GROUP" — a genuinely BLANK slate: no condition (reads
        // ALWAYS) and no action (reads NOTHING), so it is inert until the player authors it. The player fills
        // in the two blanks — tapping ALWAYS adds a first (blank) clause, tapping NOTHING picks an action —
        // rather than deleting a plausible-but-wrong placeholder. The group tag is filled in by the caller.
        val BLANK_GENE = Gene(
            EnergySource.Light,
            GeneCondition(emptyList()),
            GeneAction(ActionType.None),
        )

        /** Averaging window for the gene activity glow, in **sim ticks** (not frames — the panel is drawn far
         *  more often than the world steps, and at FAST far less). See [glow]. */
        const val GLOW_TICKS = 12

        /** Actions whose blocking is really their chemical's: they name a molecule, and it is that molecule
         *  being unset or absent that stops them — the verb itself never blocks (see [geneTokenCard]). */
        val OPERAND_ACTIONS = setOf(
            ActionType.Import, ActionType.Export, ActionType.Convert, ActionType.Retain, ActionType.BreakBond,
        )
    }

    private var editingId: EntityId? = null
    private var editingIndex: Int? = null
    private var draft: Gene? = null

    /** The current world's chemical aliases, refreshed from the controller at the top of every [render] so
     *  the species formatter ([sp]) names molecules the campaign's way. */
    private var activeAliases: Map<String, String> = emptyMap()

    /** The held cell's current cytoplasm / environment species counts (species token → count), refreshed each
     *  [render] like [activeAliases]. The molecule pickers read these to warn — non-blocking — when a gene
     *  names a chemical the cell can't currently supply (e.g. a CONVERT of something not in the cytoplasm),
     *  so an inexperienced player sees WHY a freshly-authored gene does nothing yet. */
    private var cellCytoplasm: Map<String, Int> = emptyMap()
    private var cellEnv: Map<String, Int> = emptyMap()

    /** The gene bank (banked group snippets) + the "save this group" sink, refreshed from the host at the top
     *  of every [render] (like [activeAliases]) so the paste picker + SAVE buttons see the current bank without
     *  threading them through every panel helper. Empty list / no-op default = a host that hasn't wired a bank
     *  (the feature simply doesn't appear). */
    private var activeSnippets: List<GeneSnippet> = emptyList()
    private var onSaveGroupCb: (String, List<Gene>) -> Unit = { _, _ -> }
    /** Paste UI state: the snippet picker sheet is open; and (if a paste hit a group-name clash) the snippet
     *  awaiting a Replace / Insert-duplicate / Cancel choice. */
    private var pastePicking = false
    private var pasteConflict: GeneSnippet? = null

    // Narrow L1→L2 detent: a freshly selected cell shows a shallow peek (name + biomass); expanding drills to
    // the full L2 sheet. Reset to the peek whenever the held cell changes ([peekedId] tracks that).
    private var cellExpanded = false
    private var peekedId: EntityId? = null
    /** Live sheet height fraction while dragging the grab handle (null when not dragging); snapped to a
     *  detent on release. Lets the peek↔full transition track the finger instead of jumping. */
    private var sheetDragFrac: Float? = null

    /** Which L4 picker sheet (if any) is open over the narrow L3 modal, and its target (clause index +
     *  side for operand/value pickers). Cross-frame UI state, like the rest of the editor. */
    // Bond edits BOTH reactants of a synthesis energy source in one sheet, because they are only meaningful
    // together — what a gene builds is a property of the pair, so picking them separately means editing
    // blind through an intermediate state that makes nothing.
    private enum class Pick { None, Action, Source, Group, Operand, SpeciesA, SpeciesB, Bond, Break, Eff, Overflow }
    private var pick = Pick.None
    private var pickClause = -1
    private var pickSide = 0            // 0 = lhs, 1 = rhs (Operand picker)
    private var confirmingDelete = false   // Overflow sheet is showing the "delete this gene?" confirm step.

    // ── Desktop inline editor (apps/cyto/UI_REDESIGN.md §8a step 3b) ──
    // The wide gene card IS the editor: its tokens edit the gene live, no draft/commit. `inlineLive` marks
    // that the pick sheet (operand/species/group — the builder/keyboard slots) is serving the inline card
    // rather than the narrow modal, so every change flushes straight to the genome. `openMenu` keys the one
    // open inline dropdown (action/source/efficiency) as "<geneIndex>:<slot>". `lastFlush` de-dupes the flush.
    private var inlineLive = false
    private var openMenu: String? = null
    private var lastFlush: Gene? = null
    private var armedClauseDelete: String? = null   // "<geneIndex>:<clauseIndex>" armed for a second-tap delete
    // The layout in play, for the handful of decisions taken outside a render pass (a drop handler, a dialog
    // confirm). Narrow shows a gene by REPLACING the sheet with a modal, so a gesture that merely re-tags a
    // gene must not go through the draft — that would answer a drag with a full-screen editor.
    private var narrowLayout = false
    // The gene a group-name capture belongs to when there is no draft to hang it on (narrow drag-to-new-group).
    private var groupCaptureGene: Int? = null
    // A (gene, group) re-tag waiting for a render to apply it. The name is confirmed from a host callback,
    // which has no controller to write through — the same reason pendingDeleteGene defers.
    private var pendingRetag: Pair<Int, String>? = null

    // ── Gene activity glow ──
    // A gene's "would fire this tick" flag is genuinely twitchy: a gate like `TOUCH > 0` flips as cells
    // jostle, and a Convert that empties its cytoplasm alternates on/off tick by tick. Colouring the card
    // straight off that flag strobes. Instead each gene carries a rolling average of the flag over
    // GLOW_TICKS sim ticks, so a gene that fires half the time sits half-lit — a dimmer, not a flash.
    private var glow = FloatArray(0)
    private var glowCellId = -1
    private var glowTick = 0L
    private var pendingDeleteGene: Int? = null       // a gene dropped on DELETE, awaiting the confirm dialog

    // In-game group tagging: when the player picks "New group..." the editor captures a typed name into
    // [groupBuffer] (the host routes keystrokes here — see [capturingGroupName]/[typeGroupChar]). Only the
    // draft's tag changes; it commits with DONE like every other field.
    private var capturingGroup = false
    private val groupBuffer = StringBuilder()

    // Direct text entry for a SPECIES operand (the molecule fields in the BOND/BREAK sheets, Convert/Import
    // operands, condition molecules). Unlike group-name and constant capture there is deliberately **no
    // buffer**: the field's content IS the operand, so each keystroke writes straight through to the draft
    // and every derived readout — the operand's own name, and the BOND/BREAK target it feeds — updates as
    // you type.
    //
    // Focus holds a LENS over the draft rather than a captured value, and that is load-bearing: a host
    // delivers every character of a frame before the next render (GLFW drains its char queue inside
    // glfwPollEvents), so closures capturing the render-time draft would have every keystroke in a frame
    // rebuild from the same stale gene — typing "gb" onto "r" yielded "rb", silently dropping the "g".
    // Reading and writing through [draft] on each keystroke is what makes fast typing and key-repeat safe.
    private var speciesFocusKey: String? = null
    private var speciesLens: SpeciesLens? = null

    /** Read/write access to one species operand of a [Gene]. Both halves are pure functions of the gene, so
     *  keyboard input always acts on the CURRENT draft — see [speciesFocusKey]. */
    private class SpeciesLens(val get: (Gene) -> String, val set: (Gene, String) -> Gene)

    // Direct text entry for a numeric constant (efficiency gear, gene-condition VALUE): tapping the number
    // in [numberField] captures digit keystrokes into [constantBuffer] the same way group-name capture works.
    private var capturingConstant = false
    private val constantBuffer = StringBuilder()
    // "Select-all on open": the buffer opens pre-filled with the current value, and the first keystroke
    // REPLACES it rather than appending — typing 5 over a 2000 field means 5, not 20005. Cleared as soon as
    // any edit lands, so subsequent keystrokes append normally.
    private var constantPristine = false
    private var constantMin = 0
    private var constantMax = 0
    private var constantSet: ((Int) -> Unit)? = null

    /** Which functional groups are expanded (by name) when a [GenomeGrouping] overlay is showing. Collapsed
     *  by default so the genome reads as a few named subsystems, not a wall of genes; the player opens the
     *  one they care about. Cross-frame UI state, like the rest of the editor. */
    private val expandedGroups = HashSet<String>()

    /** The dense ENV/CYT/BIO metabolism table is collapsed by default — it's a spreadsheet of chemical
     *  counts that overwhelms a new player. Tap the header to reveal it. Reset when the target cell changes. */
    private var metabExpanded = false

    /** Whether the chemistry readout is open on the cell being looked at — a campaign beat that asks the
     *  player to open it gates on this rather than on the tap that opened it, so a player who got there
     *  first is not asked to do it again. Resets with the selection, like [metabExpanded] itself. */
    val chemistryOpen: Boolean get() = metabExpanded

    // Option lists, derived from the seeded alphabet. Species operands are built atom-by-atom (see
    // [speciesBuilder]) rather than picked from a fixed list, so any-length molecules (abb, abcb, …) are
    // reachable — the old monomer+dimer dropdown couldn't express them.
    private val atoms: List<String> = CytoSeed.SEED_MONOMERS
    private val bonds: List<String> = atoms.flatMap { x -> atoms.map { y -> x + y } }

    /** The four operand kinds, in picker order: a constant value, a cytoplasm count, total biomass, or
     *  the contact count. */
    private val operandKindLabels: List<String> = GeneCardLabels.OPERAND_KINDS

    /** A press outside the UI dismisses any open L4 picker sheet (the host calls this). */
    fun closeDropdown() { closePick() }

    /** True while the editor is capturing a typed group name — the host routes keystrokes here instead of
     *  its global shortcuts (mirrors the menu's name-capture). */
    val capturingGroupName: Boolean get() = capturingGroup

    /** Append a printable char to the group name being typed (host char-callback). */
    fun typeGroupChar(c: Char) { if (capturingGroup && groupBuffer.length < 24 && c >= ' ') groupBuffer.append(c) }

    /** Delete the last typed char of the group name (host BACKSPACE). */
    fun groupBackspace() { if (capturingGroup && groupBuffer.isNotEmpty()) groupBuffer.setLength(groupBuffer.length - 1) }

    /** Commit the typed group name onto the draft (host ENTER); a blank name is ignored (leaves the tag as-is). */
    fun confirmGroupName() {
        if (!capturingGroup) return
        val name = groupBuffer.toString().trim()
        if (name.isNotEmpty()) {
            // A capture with a draft behind it is an edit in progress (either editor) and writes there. One
            // without is the narrow drag-to-new-group, which has no editor open and must not open one.
            val gene = groupCaptureGene
            if (draft != null) draft = draft?.copy(group = name)
            else if (gene != null) pendingRetag = gene to name   // applied by the next render, which has the controller
            expandedGroups.add(name)
        }
        capturingGroup = false
        groupCaptureGene = null
    }

    /** Abandon the typed group name unchanged (host ESC). */
    fun cancelGroupName() { capturingGroup = false; groupCaptureGene = null }

    /** The group name captured so far — a soft-keyboard host (Android) reads this to pre-fill its dialog. */
    val capturedGroupName: String get() = groupBuffer.toString()

    /** Submit a whole group name at once (a soft-keyboard host's dialog result, vs desktop's per-char
     *  [typeGroupChar]). Filters through the same rules, then commits. */
    fun submitGroupName(text: String) {
        if (!capturingGroup) return
        groupBuffer.setLength(0)
        for (c in text) typeGroupChar(c)
        confirmGroupName()
    }

    private fun startGroupCapture(initial: String) {
        capturingGroup = true
        groupBuffer.setLength(0); groupBuffer.append(initial)
        pick = Pick.None
    }

    /** True while a species operand field has keyboard focus — the host routes keystrokes here instead of its
     *  global shortcuts (mirrors [capturingGroupName]/[capturingConstantValue]). */
    val capturingSpeciesOperand: Boolean get() = speciesFocusKey != null

    /** Append a typed atom to the focused species operand (host char-callback). **Only alphabet atoms are
     *  accepted** — R/G/B for the seeded alphabet — so the field can't be driven into a token that isn't
     *  chemistry at all; anything else is silently ignored rather than beeping or inserting junk. Case
     *  doesn't matter: species are stored lowercase and displayed uppercase.
     *
     *  A legal *string* is still not necessarily a legal *molecule* — the registry enumerates walks that
     *  never repeat a bond, so `rrr` is typeable but nonexistent. That is surfaced in the field itself
     *  (see [speciesFieldHint]) rather than blocked, because a half-typed molecule is legitimately invalid
     *  on the way to a valid one. */
    fun typeSpeciesChar(c: Char) {
        val atom = c.lowercaseChar().toString()
        if (atom !in atoms) return
        editFocusedSpecies { cur ->
            // Cap at the longest molecule the alphabet can express (a bond-non-repeating walk visits at most
            // k²+1 atoms), so held keys can't grow an unbounded string.
            if (cur.length >= atoms.size * atoms.size + 1) cur else cur + atom
        }
    }

    /** Delete the last atom of the focused species operand (host BACKSPACE). */
    fun speciesBackspace() = editFocusedSpecies { it.dropLast(1) }

    /** Apply [edit] to the focused operand, reading and writing through the LIVE draft so consecutive
     *  keystrokes within one frame compose instead of overwriting each other. */
    private fun editFocusedSpecies(edit: (String) -> String) {
        val lens = speciesLens ?: return
        val g = draft ?: return
        draft = lens.set(g, edit(lens.get(g)))
    }

    /** Release keyboard focus (host ENTER/ESC). There is nothing to commit or revert — every keystroke was
     *  already applied to the draft — so both keys do the same thing, and the draft still commits with DONE
     *  like every other field. */
    fun blurSpeciesOperand() { speciesFocusKey = null; speciesLens = null }

    /** True while the editor is capturing typed digits for a numeric constant — the host routes keystrokes
     *  here instead of its global shortcuts (mirrors [capturingGroupName]). */
    val capturingConstantValue: Boolean get() = capturingConstant

    /** Append a typed digit to the constant being entered (host char-callback). Digits only, capped at 7
     *  characters (comfortably covers the 0..1_000_000 range fields use). The first digit after the field
     *  opens replaces the pre-filled value outright ([constantPristine]). */
    fun typeConstantChar(c: Char) {
        if (!capturingConstant || !c.isDigit()) return
        if (constantPristine) { constantBuffer.setLength(0); constantPristine = false }
        if (constantBuffer.length < 7) constantBuffer.append(c)
    }

    /** Delete the last typed digit (host BACKSPACE) — or, on the pristine pre-filled value, clear it whole,
     *  which is what backspacing over a fully-selected field does everywhere else. */
    fun constantBackspace() {
        if (!capturingConstant) return
        if (constantPristine) { constantBuffer.setLength(0); constantPristine = false; return }
        if (constantBuffer.isNotEmpty()) constantBuffer.setLength(constantBuffer.length - 1)
    }

    /** Commit the typed value, clamped to the field's range (host ENTER); a blank/unparsable buffer leaves
     *  the value unchanged. */
    fun confirmConstantValue() {
        if (!capturingConstant) return
        constantBuffer.toString().toIntOrNull()?.let { constantSet?.invoke(it.coerceIn(constantMin, constantMax)) }
        capturingConstant = false; constantSet = null; constantPristine = false
    }

    /** Abandon the typed value unchanged (host ESC). */
    fun cancelConstantValue() { capturingConstant = false; constantSet = null; constantPristine = false }

    /** The digits captured so far — a soft-keyboard host (Android) reads this to pre-fill its dialog. */
    val capturedConstantValue: String get() = constantBuffer.toString()

    /** Submit a whole numeric value at once (a soft-keyboard host's dialog result, vs desktop's per-char
     *  [typeConstantChar]). Filters to digits, then commits with the field's range clamp. */
    fun submitConstantValue(text: String) {
        if (!capturingConstant) return
        constantBuffer.setLength(0); constantPristine = false
        for (c in text) typeConstantChar(c)
        confirmConstantValue()
    }

    /** Open the numeric field on [current]. `internal` rather than private only so the keystroke contract
     *  (select-all on open — see [constantPristine]) can be tested without driving a UI frame. */
    internal fun startConstantCapture(current: Int, min: Int, max: Int, onSet: (Int) -> Unit) {
        capturingConstant = true
        constantBuffer.setLength(0); constantBuffer.append(current.toString()); constantPristine = true
        constantMin = min; constantMax = max; constantSet = onSet
    }

    /** The framebuffer-pixel offset from the screen centre to the centre of the *un-obscured* world area,
     *  given the current layout — so the host can recentre the followed cell into the free space beside/above
     *  the panel (feed to `CytoRenderer.setFollowOffsetPx`). Returns `(dx right, dy down)`; `(0, 0)` when
     *  nothing meaningful occludes the world. Uses this editor's live state, so it must be read the same frame
     *  as [render]. [cellShown] is whether a cell panel/sheet is up at all (host tracks the held cell). */
    fun freeAreaOffsetPx(narrow: Boolean, cellShown: Boolean, resW: Float, resH: Float, scale: Float, topObscuredPx: Float = 0f): Pair<Float, Float> {
        if (!cellShown) return 0f to 0f
        if (narrow) {
            // A full-screen modal (editing) hides the world entirely; otherwise the world shows in the band
            // between the top-docked campaign coach ([topObscuredPx] down from the top) and the cell sheet
            // (its height set by the L1 peek vs full L2 detent). Centre the cell in that band.
            if (draft != null) return 0f to 0f
            val frac = sheetDragFrac ?: (if (cellExpanded) SHEET_FRACTION else PEEK_FRACTION)
            return 0f to (topObscuredPx - frac * resH) * 0.5f
        }
        // Wide: the cell panel docks right and NOTHING docks beside it — the genome card is itself the editor
        // (§8a), and every sheet is a centred popover. So the free world area is just everything left of the
        // cell panel, whether or not a gene is being edited. (This used to widen for an L3 editor column that
        // docked to the panel's left; that column is gone, and reserving space for it shifted the followed
        // cell off-centre whenever a draft was parked.)
        val cellLeftX = resW - (CELL_PANEL_DP + PANEL_MARGIN_DP) * scale
        return -(resW - cellLeftX) * 0.5f to 0f
    }

    /** [onExport] is invoked when the EXPORT button is tapped — the host writes the held cell's genome to a
     *  file (desktop file-I/O lives outside this commonMain kit). No-op default keeps non-desktop hosts simple. */
    fun render(
        b: UiBuilder,
        controller: CytoController,
        grouping: GenomeGrouping? = null,
        insertableGroups: Set<String> = emptySet(),
        narrow: Boolean = false,
        onExport: () -> Unit = {},
        savedSnippets: List<GeneSnippet> = emptyList(),
        onSaveGroup: (String, List<Gene>) -> Unit = { _, _ -> },
    ) {
        val info = controller.heldCellInfo()
        if (info == null) { reset(); return }
        updateGlow(controller, info)
        activeAliases = controller.speciesAliases
        cellCytoplasm = info.metabolism.filter { it.cyto > 0 }.associate { it.species to it.cyto }
        cellEnv = info.metabolism.filter { it.env > 0 }.associate { it.species to it.env }
        activeSnippets = savedSnippets
        onSaveGroupCb = onSaveGroup
        if (editingId != null && editingId != controller.lastHeldId) reset()   // grabbed a different cell
        if (controller.lastHeldId != peekedId) { peekedId = controller.lastHeldId; cellExpanded = false; sheetDragFrac = null }   // new cell → peek

        // Progressive-disclosure UI everywhere (apps/cyto/UI_REDESIGN.md §8); `narrow` only chooses the
        // container geometry — a bottom sheet + bottom picker on a phone, a docked right panel + a centred
        // popover on a wide screen. The gene card itself is the SAME editor at both widths.
        val wide = !narrow
        narrowLayout = narrow
        pendingRetag?.let { (i, name) ->
            retagHeldGene(controller, i, name)
            pendingRetag = null
        }
        // A gene card can be dragged in either layout, but the pickup gesture differs: a mouse takes the card
        // the moment it moves, a finger must hold it first — the narrow sheet is a scrolling list *made of*
        // draggable cards, so an immediate pickup would swallow every scroll.
        b.longPressDrag = narrow
        val draggingGene = b.draggingId?.removePrefix(GENE_DRAG_PREFIX)?.toIntOrNull()
        if (wide) {
            // §8a: the genome card IS the editor — its tokens edit live in place, no L3 column. A sheet-backed
            // slot (operand / species / group) opens the pick sheet as a centred popover, also editing live.
            // A gene card can also be *dragged* onto a group header (or the "new group" placeholder) to re-tag
            // it; the drag id encodes the gene index, so the panel knows which gene is in flight.
            renderCellPanel(b, controller, info, grouping, insertableGroups, onExport, wide = true, draggingGene = draggingGene)
        } else {
            renderCellPanel(b, controller, info, grouping, insertableGroups, onExport, wide = false, draggingGene = draggingGene)
        }
        if (draggingGene != null) b.dragGhost("GENE ${draggingGene + 1}")
        draft?.let { renderPickerSheet(b, controller, it, wide) }
        if (capturingGroup) renderGroupCaptureDialog(b, wide)
        if (capturingConstant) renderConstantCaptureDialog(b, wide)
        if (pendingDeleteGene != null) renderDeleteConfirmDialog(b, controller, info, wide)
        if (pastePicking) renderPastePicker(b, controller, info, wide)
        pasteConflict?.let { renderPasteConflictDialog(b, controller, it, wide) }
        // Desktop inline is live: the pick sheet / token controls mutate `draft`; flush each change straight
        // to the genome (no DONE step). The narrow modal leaves `inlineLive` false and commits on DONE.
        if (inlineLive) {
            val d = draft; val idx = editingIndex
            if (d != null && idx != null && d != lastFlush) { controller.setHeldGene(idx, d); lastFlush = d }
        }
    }

    /**
     * The **L2 cell view** (`apps/cyto/UI_REDESIGN.md` §3): the held cell's vitals, a collapsible chemistry
     * table, and its genome grouped into collapsible subsystems. Tapping a gene opens the L3 editor. Same
     * content in either container — a **docked right panel** on a wide screen (returns its left x so the L3
     * column can dock beside it) or a **bottom sheet** on a phone (returns 0).
     */
    private fun renderCellPanel(
        b: UiBuilder, controller: CytoController, info: CytoController.CellInfo,
        grouping: GenomeGrouping?, insertableGroups: Set<String>, onExport: () -> Unit,
        wide: Boolean,
        draggingGene: Int? = null,
    ): Float {
        if (wide) {
            // The dock is a fixed width, so the sentence wraps against it minus the panel's own padding.
            val wrapDp = CELL_PANEL_DP - PANEL_MARGIN_DP * 2f
            val body: PanelBuilder.() -> Unit = { cellBody(controller, info, grouping, insertableGroups, onExport, wide = true, wrapDp = wrapDp, draggingGene = draggingGene) }
            return b.dockRight("cell-panel", width = CELL_PANEL_DP, margin = PANEL_MARGIN_DP, rowHeight = 26f, textSize = 15f, block = body)
        }
        // Narrow: a shallow peek (name + biomass) that drags up to the full L2 sheet. While dragging, the
        // height tracks the finger (sheetDragFrac) and the body switches at the midpoint; a tap on the handle
        // toggles; a hard drag down dismisses. Opaque background so the short peek fully hides what's behind it.
        val screenH = b.screenH
        val padDp = 12f
        val midFrac = (PEEK_FRACTION + SHEET_FRACTION) * 0.5f
        val liveFrac = sheetDragFrac ?: (if (cellExpanded) SHEET_FRACTION else PEEK_FRACTION)
        val showFull = sheetDragFrac?.let { it > midFrac } ?: cellExpanded
        // Shared drag behaviour: the finger sets the live height, release snaps to dismiss / peek / full.
        val onDrag: (Float) -> Unit = { dy -> sheetDragFrac = ((sheetDragFrac ?: liveFrac) - dy / screenH).coerceIn(0f, SHEET_FRACTION) }
        val onRelease: () -> Unit = {
            val f = sheetDragFrac
            sheetDragFrac = null
            if (f != null) cellExpanded = when {
                f < PEEK_FRACTION * 0.55f -> { controller.clearSelection(); false }   // dragged down to dismiss
                f > midFrac -> true
                else -> false
            }
        }
        b.dockBottom("cell-sheet", heightFraction = liveFrac, background = 0x121722FFL, padding = padDp, rowHeight = 44f, textSize = if (showFull) 15f else 16f) {
            if (showFull) {
                dragHandle("cell-grab", onTap = { cellExpanded = false; sheetDragFrac = null }, onDrag = onDrag, onRelease = onRelease)
                // The sheet is full-bleed, so the sentence wraps against the screen (in dp) less its padding.
                cellBody(controller, info, grouping, insertableGroups, onExport, wide = false,
                    wrapDp = b.screenW / b.density - padDp * 2f, draggingGene = draggingGene)
            } else {
                // The collapsed peek is one non-scrolling card filling the sheet: a drag anywhere on it (not
                // just a top handle) expands, and its tiny content can't scroll. Height = sheet minus padding.
                val cardH = screenH * liveFrac - padDp * b.density * 2f
                dragCard(
                    "cell-grab", cardH,
                    listOf(
                        "CELL ${info.id}" to 0xFFFFFFFFL,
                        "BIOMASS ${info.totalBiomass}" to 0xBFD0E6FFL,
                    ),
                    onTap = { cellExpanded = true; sheetDragFrac = null },
                    onDrag = onDrag,
                    onRelease = onRelease,
                )
            }
        }
        return 0f
    }

    private fun PanelBuilder.cellBody(
        controller: CytoController, info: CytoController.CellInfo,
        grouping: GenomeGrouping?, insertableGroups: Set<String>, onExport: () -> Unit,
        wide: Boolean = false,
        wrapDp: Float = CELL_PANEL_DP - PANEL_MARGIN_DP * 2f,
        draggingGene: Int? = null,
    ) {
        // ONE gene UI at every width (§8a step 3b): the card is the editor, each editable word a control that
        // writes straight to the genome. Only the container differs — a docked column or a bottom sheet.
        fun PanelBuilder.gene(g: CytoController.CellInfo.GeneRow, i: Int) =
            geneTokenCard(controller, g, i, wrapDp = wrapDp, touch = !wide)
        // Render the genes at [indices] (global genome positions, in group order). When [isOrigin] (the
        // section the dragged gene belongs to), interleave thin reorder drop slots — one before each gene and
        // one after the last, keyed by the target *rank* within the group — and cap the run with the DUPLICATE
        // zone. A drop on a slot reorders within the group; a drop on DUPLICATE inserts a copy (handleGeneDrop).
        fun PanelBuilder.geneRun(indices: List<Int>, isOrigin: Boolean, group: String) {
            // A finger needs a target it can actually hit: the mouse-sized 10dp sliver between two cards is
            // below the minimum touch target, so the narrow slots are fat enough to aim at.
            val slotH = if (wide) 10f else 28f
            indices.forEachIndexed { rank, idx ->
                if (isOrigin) dropSlot("$GENE_REORDER_PREFIX$rank", slotH)
                gene(info.genes[idx], idx)
            }
            if (isOrigin) {
                dropSlot("$GENE_REORDER_PREFIX${indices.size}", slotH)
                button("DUPLICATE GENE", 0x2E5A38FFL, dropTargetId = GENE_DROP_DUP) {}
            } else if (draggingGene == null && indices.isNotEmpty()) {
                // Not dragging (and this group has genes): offer a create-from-scratch affordance at the end of
                // the group (during a drag this slot is the DUPLICATE zone instead). Appends a blank gene tagged
                // to this group. The empty-genome case is handled by the bottom-stack "+ NEW GENE" instead.
                button("+ NEW GENE", 0x2E5A38FFL) { createGene(controller, group) }
                // Bank this whole group as a named snippet (the gene bank). Only a real (named) group can be
                // banked — the untagged OTHER bucket isn't a reusable subsystem.
                if (group.isNotEmpty()) button("SAVE $group TO BANK", 0x3A5A6EFFL) {
                    onSaveGroupCb(group, indices.map { info.genes[it].gene })
                }
            }
        }
        // Cell type is intentionally NOT shown: in the matter model behaviour is entirely genome-driven, so
        // the type is a non-functional label that only distracts (front-and-centre in the campaign panel).
        title("CELL ${info.id}")
        // Per-line key/values right-align their value, so the panel reads at any width (a single-line vitals
        // row overflowed the narrow wide-screen column).
        keyValue("SIZE", info.radius)
        keyValue("BIOMASS", info.totalBiomass.toString())
        keyValue("LIGHT", info.light)
        if (info.metabolism.isNotEmpty()) {
            gap(6f)
            button("${if (metabExpanded) "-" else "+"} CHEMISTRY (${info.metabolism.size})", 0x2A3550FFL) {
                metabExpanded = !metabExpanded
            }
            if (metabExpanded) metabolismTable(info)
        }
        // The genome section always shows, even for an empty cell, so it still offers the create-from-scratch
        // buttons (there'd otherwise be no way to author a first gene without duplicating one).
        run {
            gap(8f)
            row(if (info.genes.isEmpty()) "GENOME  (EMPTY)" else "GENOME  (TAP A WORD TO EDIT)", 0x7A8699FFL)
            val liveGenes = info.genes.map { it.gene }
            val effectiveGrouping = grouping ?: if (liveGenes.any { it.group.isNotEmpty() }) EMPTY_GROUPING else null
            val sections = effectiveGrouping?.sections(liveGenes)
            // The group a dragged gene came from, so its own section can show the reorder slots (drop between
            // same-group genes to re-order) and the DUPLICATE zone at its end. Reorder is within-group only;
            // dropping on a *different* group's header re-tags instead.
            val dragGroup = draggingGene?.let { info.genes.getOrNull(it)?.gene?.group }
            if (sections != null) {
                for (sec in sections) {
                    val label = sec.name ?: "OTHER"
                    val open = expandedGroups.contains(label)
                    // Desktop: the header doubles as a drop target — drag a gene onto it to re-tag the gene
                    // into this group (§8a). Dropping a gene already in this group is a no-op.
                    button("${if (open) "-" else "+"} $label (${sec.items.size})", groupHeaderBg(sec.color),
                        dropTargetId = "$GROUP_DROP_PREFIX${sec.name ?: ""}") {
                        if (open) expandedGroups.remove(label) else expandedGroups.add(label)
                    }
                    val isOrigin = dragGroup != null && (sec.name ?: "") == dragGroup
                    if (open) geneRun(sec.items.map { it.index }, isOrigin, sec.name ?: "")
                }
                for (grp in effectiveGrouping.groups) {
                    if (grp.name in insertableGroups && grp.insert.isNotEmpty() && liveGenes.none { it.group == grp.name })
                        button("+ ADD ${grp.name.uppercase()}", 0x2A3F5AFFL) { controller.addHeldGenes(grp.insert) }
                }
            } else {
                // Flat (ungrouped) genome: one implicit group, so reorder + duplicate apply to the whole list.
                geneRun(info.genes.indices.toList(), isOrigin = draggingGene != null, group = "")
            }
            // Bottom stack. While a gene is in flight, the drag-only drop zones (new-group + delete); otherwise
            // the persistent create-from-scratch affordances. "+ NEW GROUP" names a brand-new group and drops a
            // blank gene into it; the top-level "+ NEW GENE" only appears for an empty genome (a non-empty one
            // gets a per-group "+ NEW GENE" at the end of each section's run — see geneRun). Duplicate lives at
            // the origin group's end during a drag.
            if (draggingGene != null) {
                gap(6f)
                button("+ NEW GROUP", 0x2E4A6EFFL, dropTargetId = GROUP_DROP_NEW) {}
                button("DELETE GENE", 0x6E2A2AFFL, dropTargetId = GENE_DROP_DEL) {}
            } else {
                gap(6f)
                if (info.genes.isEmpty()) button("+ NEW GENE", 0x2E5A38FFL) { createGene(controller, "") }
                button("+ NEW GROUP", 0x2E4A6EFFL) { createGeneInNewGroup(controller) }
                // Paste a banked group from the gene bank into this cell (only when the bank has something).
                if (activeSnippets.isNotEmpty()) button("PASTE GROUP FROM BANK (${activeSnippets.size})", 0x3A5A6EFFL) { pastePicking = true }
            }
            if (info.genes.isNotEmpty()) {
                gap(6f)
                button("EXPORT GENOME", 0x3A6EA5FFL) { onExport() }
            }
        }
    }

    private fun openPick(p: Pick, clause: Int = -1, side: Int = 0) {
        pick = p; pickClause = clause; pickSide = side
    }

    /** Move the edited gene to group [g] (blank = untagged) and auto-expand its target section, so a gene
     *  re-tagged into a collapsed group on the desktop inline card doesn't vanish from view. */
    private fun setGroup(d: Gene, g: String) {
        draft = d.copy(group = g)
        expandedGroups.add(g.ifEmpty { "OTHER" })
        closePick()
    }

    /**
     * The **L4 field picker** (`apps/cyto/UI_REDESIGN.md` §4), a bottom sheet over the L3 modal. One value,
     * one sheet, big targets. Four shapes cover every field: a **list** (action — with a one-line
     * description each, the onboarding win a dropdown never had room for — source, group), an **operand
     * builder** (kind + value: number stepper or species builder), a **species builder** (atom-by-atom), and
     * a **number** (efficiency). List picks auto-dismiss; builders stay open so you can keep tapping.
     */
    private fun renderPickerSheet(b: UiBuilder, controller: CytoController, d: Gene, wide: Boolean) {
        when (pick) {
            Pick.None -> return
            Pick.Action -> pickSheet(b, "DO WHAT?", wide) {
                for ((i, t) in actionChoices.withIndex()) {
                    listRow(actionTypeLabel(t), actionBlurb(t), selected = t == d.action.type) {
                        draft = d.copy(action = retype(d.action, t))
                        closePick()
                    }
                    if (i < actionChoices.lastIndex) gap(4f)
                }
            }
            // Only the source TYPE — which reaction a BOND gene runs is picked in [Pick.Bond], so this list
            // no longer enumerates every bond (it couldn't anyway: reactants can be arbitrary molecules).
            Pick.Source -> pickSheet(b, "POWERED BY?", wide, heightFraction = 0.35f) {
                listRow("USE LIGHT", "FREE, BUT ONLY IN DAYLIGHT", selected = d.source is EnergySource.Light) {
                    draft = d.copy(source = EnergySource.Light); closePick()
                }
                gap(4f)
                listRow("BOND", "JOIN TWO MOLECULES - RELEASES ENERGY", selected = d.source is EnergySource.FormBond) {
                    if (d.source !is EnergySource.FormBond) draft = d.copy(source = defaultSynthesis())
                    // Straight on to picking the reaction: a bare "BOND" with default reactants isn't an
                    // answer to anything, so switching source type flows into choosing what it makes.
                    openPick(Pick.Bond)
                }
            }
            Pick.Group -> pickSheet(b, "GROUP?", wide) {
                val existing = controller.heldGenome()?.mapNotNull { it.group.ifBlank { null } }?.distinct() ?: emptyList()
                listRow("(NONE)", selected = d.group.isEmpty()) { setGroup(d, "") }
                gap(4f)
                for (g in existing) {
                    listRow(g.uppercase(), selected = d.group == g) { setGroup(d, g) }
                    gap(4f)
                }
                listRow("NEW GROUP...", "TYPE A NAME ON THE KEYBOARD") { startGroupCapture(d.group); closePick() }
            }
            Pick.Operand -> renderOperandSheet(b, d, wide)
            Pick.SpeciesA -> pickSheet(b, "MOLECULE", wide) {
                speciesBuilder("act-a", actionLens(left = true))
                actionSupplyWarning(d, d.action.a)
            }
            Pick.SpeciesB -> pickSheet(b, "MOLECULE", wide) {
                speciesBuilder("act-b", actionLens(left = false))
            }
            // Both reactants in one sheet, with a live MAKES readout underneath: the whole point of a
            // synthesis gene is its product, and building the pair blind (one operand per sheet) means you
            // can't see what you're making until you've committed to both.
            // The mirror of Pick.Bond: same two builders, same live readout, opposite direction. BREAK names
            // the FRAGMENTS and the substrate is derived, so the reaction is chosen the same way either way.
            Pick.Break -> pickSheet(b, "BREAK WHAT?", wide, heightFraction = 0.5f) {
                val act = d.action
                row("INTO", 0x7A8699FFL)
                speciesBuilder("brk-a", actionLens(left = true))
                gap(10f)
                row("AND", 0x7A8699FFL)
                speciesBuilder("brk-b", actionLens(left = false))
                gap(10f)
                row("SPLITS", 0x7A8699FFL)
                row(breakLabel(act), if (act.breakTarget.isEmpty()) 0xC8963CFFL else 0x8FCF9FFFL)
                // BreakBond consumes the JOINED substrate from the cytoplasm, so warn on that molecule, not the
                // two fragments the gene names.
                supplyWarning(act.breakTarget, fromEnv = false)
            }
            Pick.Bond -> pickSheet(b, "BOND WHAT?", wide, heightFraction = 0.5f) {
                val s = d.source as? EnergySource.FormBond ?: return@pickSheet
                row("JOIN", 0x7A8699FFL)
                speciesBuilder("bond-a", sourceLens(left = true))
                gap(10f)
                row("TO", 0x7A8699FFL)
                speciesBuilder("bond-b", sourceLens(left = false))
                gap(10f)
                // A READOUT, not a control — plain text rather than a chip, because a chip draws a dropdown
                // chevron and would promise an interaction that doesn't exist. Orange when the pair can't
                // react, matching how the panel colours a gene that can't fire.
                row("MAKES", 0x7A8699FFL)
                row(synthesisLabel(s), if (s.product.isEmpty()) 0xC8963CFFL else 0x8FCF9FFFL)
                // Synthesis draws BOTH reactants from the cytoplasm; warn on whichever the cell doesn't hold.
                supplyWarning(s.a, fromEnv = false)
                supplyWarning(s.b, fromEnv = false)
            }
            Pick.Eff -> pickSheet(b, "EFFICIENCY GEAR", wide, heightFraction = 0.4f) {
                numberField(d.efficiency, 0, CytoTuning.EFFICIENCY_MAX_GEAR) { draft = d.copy(efficiency = it) }
            }
            Pick.Overflow -> pickSheet(b, if (confirmingDelete) "DELETE GENE?" else "GENE", wide, heightFraction = 0.35f) {
                val idx = editingIndex ?: return@pickSheet
                if (confirmingDelete) {
                    // Two-step guard: DELETE only arms the confirm; this second tap actually removes it.
                    row("THIS CAN'T BE UNDONE.", 0x9A9A9AFFL)
                    gap(6f)
                    listRow("DELETE GENE", "REMOVE IT PERMANENTLY") { controller.deleteHeldGene(idx); closePick(); reset() }
                    gap(4f)
                    listRow("KEEP IT", "GO BACK") { confirmingDelete = false }
                } else {
                    listRow("DUPLICATE", "ADD A COPY OF THIS GENE") { controller.duplicateHeldGene(idx); closePick() }
                    gap(4f)
                    // The tap route to grouping. A mouse re-files a gene by dragging it onto a header, which a
                    // finger can do too — but only if it thinks to try holding the card, so don't make it the
                    // only way.
                    listRow("MOVE TO GROUP", "FILE IT UNDER A SUBSYSTEM") { openPick(Pick.Group) }
                    gap(4f)
                    listRow("DELETE", "REMOVE THIS GENE") { confirmingDelete = true }
                }
            }
        }
    }

    /** The **new-group name dialog** (§8a drag-and-drop): after a gene is dropped on the "new group"
     *  placeholder, this shows the live typed name (host routes keystrokes via [typeGroupChar]) with an
     *  in-field cursor, plus CREATE / CANCEL. ENTER/ESC also work through the host. */
    private fun renderGroupCaptureDialog(b: UiBuilder, wide: Boolean) {
        val typed = groupBuffer.toString()
        val shown = (typed.uppercase().ifEmpty { "" }) + "_"   // trailing cursor, all-caps font
        val body: PanelBuilder.() -> Unit = {
            row("TYPE A NAME, THEN ENTER.", 0x9A9A9AFFL)
            gap(6f)
            row(shown, 0xFFFFFFFFL)
            gap(10f)
            listRow("CREATE GROUP", if (typed.isBlank()) "TYPE A NAME FIRST" else "TAG THE GENE '${typed.uppercase()}'") {
                if (typed.isNotBlank()) confirmGroupName()
            }
            gap(4f)
            listRow("CANCEL", "LEAVE THE GENE WHERE IT WAS") { cancelGroupName() }
        }
        if (wide) {
            val w = minOf(460f * b.density, b.screenW * 0.6f)
            val h = minOf(b.screenH * 0.85f, b.screenH * 0.4f)
            b.sheet("group-name", "NEW GROUP", onDismiss = ::cancelGroupName, boxX = (b.screenW - w) * 0.5f, boxY = (b.screenH - h) * 0.5f, boxW = w, boxH = h, rowHeight = 34f, textSize = 15f, body = body)
        } else {
            b.sheet("group-name", "NEW GROUP", onDismiss = ::cancelGroupName, heightFraction = 0.4f, rowHeight = 48f, textSize = 16f, body = body)
        }
    }

    /** The **numeric constant entry dialog** (mirrors [renderGroupCaptureDialog]): tapping a [numberField]'s
     *  value starts this, and the host routes keystrokes into [constantBuffer] via [typeConstantChar]. */
    private fun renderConstantCaptureDialog(b: UiBuilder, wide: Boolean) {
        val typed = constantBuffer.toString()
        // Pristine = the pre-filled value, which the next keystroke replaces wholesale. Shown in the accent
        // colour with no cursor to read as "selected"; once they start typing it's white text + a cursor.
        val shown = if (constantPristine) typed else typed + "_"
        val body: PanelBuilder.() -> Unit = {
            row("TYPE A NUMBER ($constantMin-$constantMax), THEN ENTER.", 0x9A9A9AFFL)
            gap(6f)
            row(shown, if (constantPristine) 0x66CC66FFL else 0xFFFFFFFFL)
            gap(10f)
            val parsed = typed.toIntOrNull()
            listRow("SET VALUE", if (parsed == null) "TYPE A NUMBER FIRST" else "USE ${parsed.coerceIn(constantMin, constantMax)}") {
                if (parsed != null) confirmConstantValue()
            }
            gap(4f)
            listRow("CANCEL", "LEAVE THE VALUE AS IT WAS") { cancelConstantValue() }
        }
        if (wide) {
            val w = minOf(460f * b.density, b.screenW * 0.6f)
            val h = minOf(b.screenH * 0.85f, b.screenH * 0.4f)
            b.sheet("constant-value", "ENTER VALUE", onDismiss = ::cancelConstantValue, boxX = (b.screenW - w) * 0.5f, boxY = (b.screenH - h) * 0.5f, boxW = w, boxH = h, rowHeight = 34f, textSize = 15f, body = body)
        } else {
            b.sheet("constant-value", "ENTER VALUE", onDismiss = ::cancelConstantValue, heightFraction = 0.4f, rowHeight = 48f, textSize = 16f, body = body)
        }
    }

    /** The **delete-gene confirm** dialog (§8a drag-and-drop): shown after a gene is dropped on the DELETE
     *  zone. The gesture is deliberate but there's no undo, so a drop only *arms* the delete — this confirms
     *  it. Cancelling (or dismissing) leaves the gene untouched. */
    private fun renderDeleteConfirmDialog(b: UiBuilder, controller: CytoController, info: CytoController.CellInfo, wide: Boolean) {
        val idx = pendingDeleteGene ?: return
        val summary = info.genes.getOrNull(idx)?.desc?.uppercase() ?: "GENE ${idx + 1}"
        val body: PanelBuilder.() -> Unit = {
            row("THIS CAN'T BE UNDONE.", 0x9A9A9AFFL)
            gap(6f)
            row(summary, 0xE0E6F0FFL)
            gap(10f)
            listRow("DELETE GENE", "REMOVE IT PERMANENTLY") { controller.deleteHeldGene(idx); pendingDeleteGene = null }
            gap(4f)
            listRow("CANCEL", "KEEP THE GENE") { pendingDeleteGene = null }
        }
        if (wide) {
            val w = minOf(460f * b.density, b.screenW * 0.6f)
            val h = minOf(b.screenH * 0.85f, b.screenH * 0.4f)
            b.sheet("gene-delete", "DELETE GENE?", onDismiss = { pendingDeleteGene = null }, boxX = (b.screenW - w) * 0.5f, boxY = (b.screenH - h) * 0.5f, boxW = w, boxH = h, rowHeight = 34f, textSize = 15f, body = body)
        } else {
            b.sheet("gene-delete", "DELETE GENE?", onDismiss = { pendingDeleteGene = null }, heightFraction = 0.4f, rowHeight = 48f, textSize = 16f, body = body)
        }
    }

    /** The **gene-bank paste picker**: lists every banked group; a tap pastes it into the held cell (via
     *  [tryPaste], which routes a name clash to the conflict dialog). */
    private fun renderPastePicker(b: UiBuilder, controller: CytoController, info: CytoController.CellInfo, wide: Boolean) {
        val body: PanelBuilder.() -> Unit = {
            if (activeSnippets.isEmpty()) row("THE BANK IS EMPTY.", 0x9A9A9AFFL)
            for (snip in activeSnippets) {
                listRow(snip.name.uppercase(), "${snip.genes.size} GENE(S)") { tryPaste(controller, info, snip) }
                gap(4f)
            }
            gap(8f)
            listRow("CANCEL", "DON'T PASTE ANYTHING") { pastePicking = false }
        }
        if (wide) {
            val w = minOf(460f * b.density, b.screenW * 0.6f)
            val h = minOf(b.screenH * 0.85f, b.screenH * 0.55f)
            b.sheet("paste-pick", "PASTE FROM BANK", onDismiss = { pastePicking = false }, boxX = (b.screenW - w) * 0.5f, boxY = (b.screenH - h) * 0.5f, boxW = w, boxH = h, rowHeight = 34f, textSize = 15f, body = body)
        } else {
            b.sheet("paste-pick", "PASTE FROM BANK", onDismiss = { pastePicking = false }, heightFraction = 0.55f, rowHeight = 48f, textSize = 16f, body = body)
        }
    }

    /** Paste [snip] into the held cell. No name clash → drop its genes straight in (they carry their group
     *  tag). A clash (the cell already has a group named the same) → raise the Replace / Add-on-top / Cancel
     *  conflict dialog instead. */
    private fun tryPaste(controller: CytoController, info: CytoController.CellInfo, snip: GeneSnippet) {
        if (info.genes.any { it.gene.group == snip.name }) {
            pasteConflict = snip; pastePicking = false
        } else {
            controller.appendHeldGenes(snip.genes); expandedGroups.add(snip.name); pastePicking = false
        }
    }

    /** The **paste name-clash** dialog: the pasted group already exists on this cell, so the player picks
     *  whether to replace it wholesale, add these genes on top of the existing group, or cancel. */
    private fun renderPasteConflictDialog(b: UiBuilder, controller: CytoController, snip: GeneSnippet, wide: Boolean) {
        val name = snip.name
        val body: PanelBuilder.() -> Unit = {
            row("THIS CELL ALREADY HAS A '${name.uppercase()}' GROUP.", 0x9A9A9AFFL)
            gap(10f)
            listRow("REPLACE", "SWAP THE EXISTING '${name.uppercase()}' FOR THE BANKED ONE") {
                controller.replaceHeldGroup(name, snip.genes); expandedGroups.add(name); pasteConflict = null
            }
            gap(4f)
            listRow("ADD ON TOP", "KEEP BOTH - ADD THESE ${snip.genes.size} GENE(S) INTO '${name.uppercase()}'") {
                controller.appendHeldGenes(snip.genes); expandedGroups.add(name); pasteConflict = null
            }
            gap(4f)
            listRow("CANCEL", "LEAVE THIS CELL UNCHANGED") { pasteConflict = null }
        }
        if (wide) {
            val w = minOf(460f * b.density, b.screenW * 0.6f)
            val h = minOf(b.screenH * 0.85f, b.screenH * 0.45f)
            b.sheet("paste-clash", "GROUP EXISTS", onDismiss = { pasteConflict = null }, boxX = (b.screenW - w) * 0.5f, boxY = (b.screenH - h) * 0.5f, boxW = w, boxH = h, rowHeight = 34f, textSize = 15f, body = body)
        } else {
            b.sheet("paste-clash", "GROUP EXISTS", onDismiss = { pasteConflict = null }, heightFraction = 0.45f, rowHeight = 48f, textSize = 16f, body = body)
        }
    }

    /** Chooses the picker container by width: a bottom sheet on a phone, a centred popover on a wide screen. */
    private fun pickSheet(b: UiBuilder, title: String, wide: Boolean, heightFraction: Float = 0.6f, body: PanelBuilder.() -> Unit) {
        if (wide) {
            val w = minOf(460f * b.density, b.screenW * 0.6f)
            val h = minOf(b.screenH * 0.85f, b.screenH * maxOf(heightFraction, 0.4f))
            b.sheet("pick", title, onDismiss = ::closePick, boxX = (b.screenW - w) * 0.5f, boxY = (b.screenH - h) * 0.5f, boxW = w, boxH = h, rowHeight = 34f, textSize = 15f, body = body)
        } else {
            b.sheet("pick", title, onDismiss = ::closePick, heightFraction = heightFraction, rowHeight = 48f, textSize = 16f, body = body)
        }
    }

    /** The operand picker: a kind list (Const/Chem/BIO/Touch/Nbrs) then the value editor for whichever
     *  kind is selected — a number for Const, a species builder for Chem, nothing for the live readings. */
    private fun renderOperandSheet(b: UiBuilder, d: Gene, wide: Boolean) {
        val ci = pickClause
        val cl = d.condition.clauses.getOrNull(ci) ?: run { closePick(); return }
        val op = if (pickSide == 0) cl.lhs else cl.rhs
        fun setOp(newOp: Operand) { draft = withClauseAt(d, ci, if (pickSide == 0) cl.copy(lhs = newOp) else cl.copy(rhs = newOp)) }
        pickSheet(b, if (pickSide == 0) "LEFT SIDE" else "RIGHT SIDE", wide) {
            row("KIND", 0x7A8699FFL)
            operandKindLabels.forEachIndexed { i, label ->
                listRow(label.uppercase(), operandKindBlurb(i), selected = operandKind(op) == i) { setOp(operandOfKind(i, op)) }
                gap(4f)
            }
            gap(8f)
            when (op) {
                is Operand.Constant -> { row("VALUE", 0x7A8699FFL); numberField(op.value, 0, 1_000_000) { setOp(Operand.Constant(it)) } }
                is Operand.Chem -> { row("MOLECULE", 0x7A8699FFL); speciesBuilder("op-chem", clauseSpeciesLens()) }
                else -> {}
            }
            // Any clause is removable — dropping the last one restores the gene to the unconditional ALWAYS
            // state (the last clause reads "back to ALWAYS" to make that consequence explicit).
            if (d.condition.clauses.isNotEmpty()) {
                gap(8f)
                val last = d.condition.clauses.size == 1
                listRow("REMOVE THIS CLAUSE", if (last) "BACK TO ALWAYS (NO CONDITION)" else "DROP THIS CONDITION FROM THE GENE") { draft = removeClauseAt(d, ci); closePick() }
            }
        }
    }

    /** A number editor: the value itself is a tappable row that opens direct text entry ([startConstantCapture]),
     *  plus a hold-to-repeat ± stepper (accelerating 1→10→100→1000, the desktop 2000-tap fix) for quick nudges
     *  and coarse presets. */
    private fun PanelBuilder.numberField(value: Int, min: Int, max: Int, onSet: (Int) -> Unit) {
        listRow(value.toString(), "TAP TO TYPE A NUMBER") { startConstantCapture(value, min, max, onSet) }
        gap(6f)
        stepper("", value.toString()) { delta -> onSet((value + delta).coerceIn(min, max)) }
        val presets = if (max <= 32) listOf(min, (min + max) / 2, max) else listOf(0, 100, 500, 1000, 5000)
        actionRow(presets.distinct().filter { it in min..max }.map { p ->
            Triple<String, Long, () -> Unit>(p.toString(), 0x2A3550FFL) { onSet(p) }
        })
    }

    /**
     * A species operand field. Tap it to take keyboard focus and **type the molecule directly** (R/G/B only);
     * the `+<atom>` / `<` buttons below remain the whole input path on touch, where there is no key routing.
     *
     * [key] identifies the field across frames so focus survives re-renders. While focused it re-registers
     * the accessors each frame, which is how typing reads and writes the CURRENT draft rather than a stale
     * capture — see [speciesFocusKey].
     */
    private fun PanelBuilder.speciesBuilder(key: String, lens: SpeciesLens) {
        val current = draft?.let(lens.get) ?: ""
        val focused = speciesFocusKey == key
        fun apply(edit: (String) -> String) { draft?.let { draft = lens.set(it, edit(lens.get(it))) } }
        val shown = (if (current.isEmpty()) "" else current.uppercase()) + if (focused) "_" else ""
        listRow(shown.ifEmpty { "(NONE)" }, speciesFieldHint(current), selected = focused) {
            speciesFocusKey = key; speciesLens = lens
        }
        gap(6f)
        // The atom buttons go through the same lens as typing, so touch and keyboard can be interleaved
        // freely and neither can act on a stale value.
        actionRow(atoms.map { a -> Triple<String, Long, () -> Unit>("+${a.uppercase()}", 0x32503CFFL) { apply { it + a } } } +
            Triple<String, Long, () -> Unit>("<", 0x5A3A3AFFL) { apply { it.dropLast(1) } })
    }

    /** Lens onto a synthesis source's left/right reactant. A no-op on a Light gene (the field isn't shown). */
    private fun sourceLens(left: Boolean) = SpeciesLens(
        { g -> (g.source as? EnergySource.FormBond)?.let { if (left) it.a else it.b } ?: "" },
        { g, v -> (g.source as? EnergySource.FormBond)?.let { g.copy(source = if (left) it.copy(a = v) else it.copy(b = v)) } ?: g },
    )

    /** Lens onto an action's `a`/`b` operand (BREAK's two fragments, Convert/Import/Retain's species). */
    private fun actionLens(left: Boolean) = SpeciesLens(
        { g -> if (left) g.action.a else g.action.b },
        { g, v -> g.copy(action = if (left) g.action.copy(a = v) else g.action.copy(b = v)) },
    )

    /** Lens onto the species inside a condition operand, addressed by the picker's stored clause + side —
     *  so it stays valid across frames exactly like the other two. */
    private fun clauseSpeciesLens() = SpeciesLens(
        { g ->
            val cl = g.condition.clauses.getOrNull(pickClause) ?: return@SpeciesLens ""
            when (val op = if (pickSide == 0) cl.lhs else cl.rhs) {
                is Operand.Chem -> op.species
                else -> ""
            }
        },
        { g, v ->
            val cl = g.condition.clauses.getOrNull(pickClause) ?: return@SpeciesLens g
            val op: Operand = Operand.Chem(v)
            withClauseAt(g, pickClause, if (pickSide == 0) cl.copy(lhs = op) else cl.copy(rhs = op))
        },
    )

    /**
     * The helper line under a species field: the molecule's **live name**, or why it isn't one.
     *
     * The name is only worth showing when it actually says something the token doesn't. [SpeciesNames] names
     * monomers and duomers, plus whatever the current genome aliases; everything else falls back to the raw
     * token, so printing it would just echo the field back at the player. Hence the `!= uppercase` check
     * rather than always rendering it.
     *
     * Invalid tokens are reported, not blocked: the registry only contains walks that never repeat a bond, so
     * `rrr` is typeable but is no molecule at all. Blocking the keystroke would also block legitimate
     * half-typed states, so the field says so instead and the gene stays inert until it's a real species.
     */
    private fun speciesFieldHint(current: String): String {
        if (current.isEmpty()) return "TYPE ${atoms.joinToString("/") { it.uppercase() }}, OR TAP AN ATOM BELOW"
        if (SpeciesRegistry.id(current) < 0) return "NOT A MOLECULE - THAT BOND REPEATS"
        val name = sp(current)
        return if (name == current.uppercase()) "" else name
    }

    private fun closePick() {
        pick = Pick.None; pickClause = -1; pickSide = 0; confirmingDelete = false
        blurSpeciesOperand()   // the focused field lived in the sheet being closed
    }

    /** A **non-blocking** heads-up shown under a molecule field when the held cell can't currently supply what
     *  the gene names — a CONVERT/BREAK/BOND of a chemical that isn't in the cytoplasm (or an IMPORT of one
     *  that isn't in the surrounding environment) is valid, but does nothing until the chemical is there. Draws
     *  in the panel's advisory orange (never blocks the pick): the gene is fine, it's just waiting on supply.
     *  [fromEnv] picks which pool the species must be in — the environment (Import) vs the cytoplasm (the rest). */
    private fun PanelBuilder.supplyWarning(species: String, fromEnv: Boolean) {
        if (species.isEmpty() || SpeciesRegistry.id(species) < 0) return   // empty / not-a-molecule is its own hint
        val pool = if (fromEnv) cellEnv else cellCytoplasm
        if ((pool[species] ?: 0) > 0) return
        val where = if (fromEnv) "OUTSIDE THE CELL" else "IN THE CYTOPLASM"
        // Two short lines rather than one long one: the popover is narrow, and a single sentence clips. Orange
        // (the panel's advisory colour) carries the "heads up" — no leading glyph (the bitmap font has no `!`).
        gap(6f)
        row("NO ${sp(species)} $where YET.", 0xC8963CFFL)
        row("THE GENE WON'T ACT UNTIL THERE IS.", 0x9A9A9AFFL)
    }

    /** The [supplyWarning] appropriate to an action's own operand: the cytoplasm-consuming actions
     *  (Convert / Export / Retain) warn on the cytoplasm, Import warns on the environment it draws from, and
     *  everything else (Divide morphogen, the operand-less actions) has no supply precondition to warn about. */
    private fun PanelBuilder.actionSupplyWarning(d: Gene, species: String) {
        when (d.action.type) {
            ActionType.Convert, ActionType.Export, ActionType.Retain -> supplyWarning(species, fromEnv = false)
            ActionType.Import -> supplyWarning(species, fromEnv = true)
            else -> {}
        }
    }

    /** One-line gloss of an action, for the L4 list picker (the room a dropdown never had). */
    private fun actionBlurb(t: ActionType): String = when (t) {
        ActionType.Import -> "PULL A MOLECULE IN FROM OUTSIDE"
        ActionType.Export -> "PUSH A MOLECULE OUT TO OUTSIDE"
        ActionType.BreakBond -> "SPLIT A BOND APART - COSTS ENERGY"
        ActionType.Convert -> "LOCK A MOLECULE INTO BIOMASS - GROW"
        ActionType.Contract -> "SHRINK THE RADIUS - A MUSCLE FLEX"
        ActionType.Divide -> "DIVIDE INTO TWO CELLS"
        ActionType.Repair -> "HEAL THE MOST-DAMAGED WELD"
        ActionType.Lyse -> "TEAR BIOMASS FROM A TOUCHING CELL"
        ActionType.Retain -> "SEAL A MOLECULE INSIDE THE CELL"
        ActionType.None -> "DO NOTHING - PICK AN ACTION"
    }

    /** One-line gloss of an operand kind, for the L4 operand picker. Index-aligned with [operandKindLabels]
     *  (Const/Chem/BIO/Touch/Nbrs) — keep them in lockstep when the operand set changes. */
    private fun operandKindBlurb(i: Int): String = when (i) {
        0 -> "A FIXED NUMBER"
        1 -> "COUNT OF A MOLECULE (BIOMASS + CYTO)"
        2 -> "TOTAL BIOMASS"
        3 -> "CELLS TOUCHING THIS ONE"
        else -> "WELDED NEIGHBOURS"
    }

    /** A molecule's display name (built-in flavour name / raw token), upper-cased for the caps UI. Empty
     *  reads as "(NONE)". Genome aliases (Layer 2) will thread through here later. */
    private fun sp(species: String): String = SpeciesNames.name(species, activeAliases).uppercase()

    /**
     * Box 2 of a BOND or BREAK row: **the whole molecule**, with the pair it splits into / joins from in
     * brackets — `REDREEN (R+G)`, `RGB (R+GB)`.
     *
     * One function for both because they are the *same reaction read in opposite directions*: BOND joins the
     * pair into the molecule, BREAK splits the molecule into the pair. The molecule leads because that is
     * what the gene is about; the bracket stays because the molecule alone is ambiguous — `R+GB` and `RG+B`
     * are the same `RGB` but different genes, consuming (or producing) different things. Naming it at all is
     * only possible because operands are exact species; a wildcard reaction has no single answer here.
     *
     * [noJoin] is the wording when the pair can't combine at all, which means opposite things on each side:
     * for BOND the reaction is forbidden, for BREAK there is no such molecule to split.
     */
    private fun reactionLabel(a: String, b: String, noJoin: String): String {
        if (a.isEmpty() || b.isEmpty()) return "SET MOLECULES"
        val pair = "(${a.uppercase()}+${b.uppercase()})"
        val joined = Molecules.join(a, b) ?: return "$pair $noJoin"
        return "${sp(joined)} $pair"
    }

    /** How an action reads in the UI. [ActionType.BreakBond] shows as **BREAK** (so the digestion row mirrors
     *  the synthesis row's **BOND**); [ActionType.None] — the authoring blank — shows as **NOTHING**, an
     *  invitation to pick a real action rather than the enum's bare `None`. */
    private fun actionTypeLabel(t: ActionType) = GeneCardLabels.action(t)

    /** The real, player-choosable actions — every [ActionType] except the inert [ActionType.None] authoring
     *  blank, which is a starting state rather than something you'd deliberately select. */
    private val actionChoices: List<ActionType> = ActionType.entries.filter { it != ActionType.None }

    private fun synthesisLabel(s: EnergySource.FormBond) = reactionLabel(s.a, s.b, "WON'T BOND")
    private fun breakLabel(a: GeneAction) = reactionLabel(a.a, a.b, "NO SUCH MOLECULE")

    /** A sensible starting reaction when a gene is switched from Light to synthesis: the first two atoms of
     *  the alphabet, which always form a legal bond. */
    private fun defaultSynthesis() = EnergySource.FormBond(atoms.first(), atoms.getOrElse(1) { atoms.first() })

    /** A compact operand label for an L3 clause chip (see [renderGeneModal]). */
    private fun operandLabel(op: Operand): String = when (op) {
        is Operand.Constant -> op.value.toString()
        is Operand.Chem -> "CHEM ${sp(op.species)}"
        else -> GeneCardLabels.operand(op) ?: op.toString()
    }

    /** The **desktop inline gene editor** (`apps/cyto/UI_REDESIGN.md` §8a step 3b): the read sentence with its
     *  words swapped for live controls. Inline-native slots (comparator, orient, sever, keep, action, source,
     *  efficiency) edit the gene in place via [inlineEdit]; the builder/keyboard slots (operand, species,
     *  group) open the shared pick sheet as a popover via [openInlinePick]. Every change flushes straight to
     *  the genome (see [render]) — no draft/DONE. */
    private fun PanelBuilder.geneTokenCard(
        controller: CytoController, g: CytoController.CellInfo.GeneRow, i: Int,
        wrapDp: Float, touch: Boolean,
    ) {
        // [touch] changes only where a token's *choices* appear, never the card: a mouse drops an inline
        // dropdown under the word, a finger gets the same L4 sheet the rest of the phone UI uses — big targets,
        // and room for the per-action blurbs that made the list worth having.
        val gene = g.gene
        val grey = 0x9A9A9AFFL
        val ctl = 0x35507AFFL
        val orange = 0xB56A1EFFL   // a control-box tint of the read card's blocking orange
        // Read the blocking flags the sim already computed (CytoController.describeGeneSpans): span[0] = the
        // action (input-blocked), the clause spans between " IF "/" (", and the source span after " (" =
        // energy-blocked. Colour the offending token so an inactive gene shows *why* — as the read card did.
        val ifIdx = g.spans.indexOfFirst { it.text == " IF " }
        val parenIdx = g.spans.indexOfFirst { it.text == " (" }
        val inputBlocked = g.spans.firstOrNull()?.blocking == true
        val energyBlocked = if (parenIdx >= 0) g.spans.getOrNull(parenIdx + 1)?.blocking == true else false
        val clauseBlocks = if (ifIdx in 0 until parenIdx)
            g.spans.subList(ifIdx + 1, parenIdx).filter { it.text != " & " }.map { it.blocking } else emptyList()
        fun ctlIf(blocked: Boolean) = if (blocked) orange else ctl
        val lines = ArrayList<List<UiTok>>()

        // WHEN <lhs> <cmp> <rhs>, one clause per line. An empty gate is the special ALWAYS case — no "WHEN"
        // prefix, just the ALWAYS token; tapping it drops in a first (blank) clause to author the condition.
        if (gene.condition.clauses.isEmpty()) {
            lines.add(listOf(
                UiTok.Toggle("ALWAYS", ctl) { inlineEdit(controller, i) { addFirstClause(it) } },
            ))
        }
        gene.condition.clauses.forEachIndexed { ci, cl ->
            val c = ctlIf(clauseBlocks.getOrElse(ci) { false })
            lines.add(listOf(
                UiTok.Text(if (ci == 0) "WHEN " else " AND ", grey),
                UiTok.Toggle(operandLabel(cl.lhs), c) { openInlinePick(controller, i, Pick.Operand, ci, 0) },
                UiTok.Text(" ", grey),
                UiTok.Toggle(if (cl.cmp == Comparison.Greater) ">" else "<", c) {
                    inlineEdit(controller, i) { g2 ->
                        val cc = g2.condition.clauses[ci]
                        withClauseAt(g2, ci, cc.copy(cmp = if (cc.cmp == Comparison.Greater) Comparison.Less else Comparison.Greater))
                    }
                },
                UiTok.Text(" ", grey),
                UiTok.Toggle(operandLabel(cl.rhs), c) { openInlinePick(controller, i, Pick.Operand, ci, 1) },
            ))
        }

        // POWERED BY: an inline Menu (LIGHT, or forming one of the bonds). Forming a bond is what *pays*
        // now, so there is no "break for fuel" entry — breaking is an action that costs (see EnergySource).
        val srcKey = "$i:src"
        // TWO controls: the source TYPE, then — synthesis only — what it builds. The reaction is one idea, so
        // it is one button that opens one sheet, rather than two operand tokens edited independently.
        // Same rule as the action row below: BOND doesn't fail, the molecules it names do (absent, or — for a
        // DIVIDE — too thin on the ground to fund the split), so the orange rides the reaction token. LIGHT
        // has no chemical to carry it, so there it stays on the type.
        val srcOpts = listOf("USE LIGHT", "BOND")
        val hasReactionTok = gene.source is EnergySource.FormBond
        val srcLine = ArrayList<UiTok>()
        srcLine.add(UiTok.Menu(sourceTypeLabel(gene.source), ctlIf(energyBlocked && !hasReactionTok), srcOpts, openMenu == srcKey,
            onToggle = { if (touch) openInlinePick(controller, i, Pick.Source) else openMenu = if (openMenu == srcKey) null else srcKey },
            onPick = { idx ->
                openMenu = null
                if (idx == 0) inlineEdit(controller, i) { it.copy(source = EnergySource.Light) }
                else {
                    inlineEdit(controller, i) { g -> if (g.source is EnergySource.FormBond) g else g.copy(source = defaultSynthesis()) }
                    openInlinePick(controller, i, Pick.Bond)   // straight on to "bond what?"
                }
            }))
        (gene.source as? EnergySource.FormBond)?.let { s ->
            srcLine.add(UiTok.Text(" ", grey))
            srcLine.add(UiTok.Toggle(synthesisLabel(s), ctlIf(energyBlocked)) { openInlinePick(controller, i, Pick.Bond) })
        }
        srcLine.add(UiTok.Text(" TO", grey))
        lines.add(srcLine)

        // DO: action Menu, its operand token(s), and efficiency (non-Divide).
        // An action is never blocked by *itself* — CONVERT has no biomass ceiling, IMPORT no import quota.
        // What blocks it is always the chemical it names: unset, or absent from the cytoplasm/environment.
        // So the orange lands on the operand token, and only falls back to the verb for actions that have no
        // operand to carry it (NOTHING, whose whole point is that it is unset).
        val actKey = "$i:act"
        val hasOperandTok = gene.action.type in OPERAND_ACTIONS
        val actLine = ArrayList<UiTok>()
        actLine.add(UiTok.Menu(actionTypeLabel(gene.action.type), ctlIf(inputBlocked && !hasOperandTok), actionChoices.map { actionTypeLabel(it) }, openMenu == actKey,
            onToggle = { if (touch) openInlinePick(controller, i, Pick.Action) else openMenu = if (openMenu == actKey) null else actKey },
            onPick = { idx ->
                val t = actionChoices[idx]
                inlineEdit(controller, i) { it.copy(action = retype(it.action, t)) }
                openMenu = null
            }))
        when (gene.action.type) {
            ActionType.Import, ActionType.Export, ActionType.Convert, ActionType.Retain -> {
                actLine.add(UiTok.Text(" ", grey))
                actLine.add(UiTok.Toggle(sp(gene.action.a), ctlIf(inputBlocked)) { openInlinePick(controller, i, Pick.SpeciesA) })
            }
            // One control for the whole reaction, exactly like the BOND source row above.
            ActionType.BreakBond -> {
                actLine.add(UiTok.Text(" ", grey))
                actLine.add(UiTok.Toggle(breakLabel(gene.action), ctlIf(inputBlocked)) { openInlinePick(controller, i, Pick.Break) })
            }
            else -> {}
        }
        if (gene.action.type != ActionType.Divide) {
            val effKey = "$i:eff"
            actLine.add(UiTok.Text(" ", grey))
            actLine.add(UiTok.Menu("E${gene.efficiency}", ctl, (0..CytoTuning.EFFICIENCY_MAX_GEAR).map { "E$it" }, openMenu == effKey,
                onToggle = { if (touch) openInlinePick(controller, i, Pick.Eff) else openMenu = if (openMenu == effKey) null else effKey },
                onPick = { idx -> inlineEdit(controller, i) { it.copy(efficiency = idx) }; openMenu = null }))
        }
        lines.add(actLine)

        // Divide modifiers — always shown (§8a step 3a wording), each token live.
        if (gene.action.type == ActionType.Divide) {
            val a = gene.action
            lines.add(listOf(
                UiTok.Text(" ", grey),
                UiTok.Toggle(if (a.divideAcross) "ACROSS" else "ALONG", ctl) { inlineEdit(controller, i) { it.copy(action = it.action.copy(divideAcross = !it.action.divideAcross)) } },
                UiTok.Text(" ", grey),
                UiTok.Toggle(if (a.b.isEmpty()) "NO" else sp(a.b), ctl) { openInlinePick(controller, i, Pick.SpeciesB) },
                UiTok.Text(" GRADIENT", grey),
            ))
            val keep = ArrayList<UiTok>()
            keep.add(UiTok.Text(" ", grey))
            if (a.a.isEmpty()) {
                keep.add(UiTok.Text("RETAINING ", grey))
                keep.add(UiTok.Toggle("NOTHING", ctl) { openInlinePick(controller, i, Pick.SpeciesA) })
            } else {
                keep.add(UiTok.Toggle(if (a.morphogenToMother) "RETAINING" else "GIVING", ctl) { inlineEdit(controller, i) { it.copy(action = it.action.copy(morphogenToMother = !it.action.morphogenToMother)) } })
                keep.add(UiTok.Text(" ", grey))
                keep.add(UiTok.Toggle(sp(a.a), ctl) { openInlinePick(controller, i, Pick.SpeciesA) })
                keep.add(UiTok.Text(if (a.morphogenToMother) " IN CELL 1" else " TO CELL 2", grey))
            }
            lines.add(keep)
            lines.add(listOf(
                UiTok.Text(" ", grey),
                UiTok.Toggle(if (a.rejectMother) "SEVERING CELL 2 FREE" else "AND STICK", ctl) { inlineEdit(controller, i) { it.copy(action = it.action.copy(rejectMother = !it.action.rejectMother)) } },
            ))
        }

        // (No GROUP line: the section header already names the gene's group, so a per-card readout is
        // redundant. Re-grouping is the drag-and-drop gesture — drag the card onto a header / the new-group
        // placeholder; see cellBody + handleGeneDrop.)

        // Hover affordances (§8a step 4): each clause line reveals a + (duplicate this clause) and, when the
        // gene has more than one, an × (arm-then-delete). Whole-gene duplicate/delete are no longer a card ...
        // menu — they're drag targets at the genome's end (see cellBody + handleGeneDrop). lineActions align
        // with `lines`; clauses are the first N.
        val green = 0x32503CFFL; val red = 0x5A2A2AFFL
        val clauseCount = gene.condition.clauses.size
        val lineActions = (0 until clauseCount).map { ci ->
            buildList {
                if (clauseCount < CytoTuning.GENOME_MAX_CLAUSES)
                    add(HoverAction("+", green, "clause-dup-$ci") { dupClause(controller, i, ci) })
                // Any clause is deletable — removing the last one restores the unconditional ALWAYS gate.
                run {
                    val armed = armedClauseDelete == "$i:$ci"
                    add(HoverAction(if (armed) "!" else "X", if (armed) 0xB03030FFL else red, "clause-del-$ci") { deleteClauseArmed(controller, i, ci) })
                }
            }
        }

        // Card background carries the read card's state cue: active = green glow, inactive = dark grey. The
        // read card (geneButton) used a solid bright green; here the token controls sit on top, so this is a
        // clearly-green but muted tint that still keeps the blue/orange control boxes legible.
        val bg = mixRgba(0x20242EFFL, 0x25522FFFL, glowOf(i))
        // Touch has no hover, so the per-clause +/× are drawn permanently — and the whole-gene menu comes back
        // as a corner button. On a mouse those live on hover and on the drag, which a finger can't discover
        // and shouldn't have to: duplicate/delete stay one tap away here.
        val cardActions = if (touch) listOf(HoverAction(":", 0x3A4560FFL, "gene-menu-$i") { openInlinePick(controller, i, Pick.Overflow) }) else emptyList()
        tokenLines(lines, wrapWidth = wrapDp, textSize = 15f, background = bg, lineActions = lineActions,
            cardActions = cardActions, alwaysShowActions = touch,
            dragId = "$GENE_DRAG_PREFIX$i", onDrop = { tid -> handleGeneDrop(controller, i, tid) })
        gap(8f)
    }

    /**
     * Fold this frame's activity flags into the rolling [glow], advancing it by however many **sim ticks**
     * have passed since the last fold. Drawn frames and sim ticks run at unrelated rates, so weighting by
     * elapsed ticks is what makes the window mean 12 ticks at any speed: a paused world folds nothing (the
     * glow holds), and at FAST a single frame can carry the whole window.
     *
     * A different cell — or a genome that changed length — starts fresh at the current flags rather than
     * fading in from whatever the last cell was doing.
     */
    private fun updateGlow(controller: CytoController, info: CytoController.CellInfo) {
        fun flag(i: Int) = if (info.genes[i].active) 1f else 0f
        if (glowCellId != info.id || glow.size != info.genes.size) {
            glowCellId = info.id
            glow = FloatArray(info.genes.size) { flag(it) }
            glowTick = controller.tick
            return
        }
        val elapsed = (controller.tick - glowTick).coerceIn(0L, GLOW_TICKS.toLong()).toInt()
        glowTick = controller.tick
        if (elapsed == 0) return
        val w = elapsed.toFloat() / GLOW_TICKS
        for (i in glow.indices) glow[i] += (flag(i) - glow[i]) * w
    }

    /** How lit gene [i]'s card should be, 0 (dark) to 1 (firing every tick). */
    private fun glowOf(i: Int): Float = glow.getOrElse(i) { 0f }

    /** [inactive]→[active] blended by [t], both packed RGBA. The card's state cue is this blend rather than
     *  a switch, so a gene that fires intermittently reads as dimmed instead of strobing. */
    private fun mixRgba(inactive: Long, active: Long, t: Float): Long {
        val f = t.coerceIn(0f, 1f)
        var out = 0L
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val lo = (inactive ushr shift) and 0xFF
            val hi = (active ushr shift) and 0xFF
            out = out or (((lo + (hi - lo) * f).toLong() and 0xFF) shl shift)
        }
        return out
    }

    /**
     * Switch an action to type [t], keeping only the modifiers that still mean anything (they are all
     * Divide-only, so a move away from Divide clears them).
     *
     * Switching **to** Divide defaults to SEVER rather than STICK. A player picking DIVIDE without having
     * thought about welds — which is exactly the campaign's case, and near enough the only time it happens —
     * means "make another one of these", and a daughter that stays welded to its mother reads as a growing
     * blob, not as reproduction. Re-picking Divide on a gene that already has it keeps whatever the player
     * chose.
     */
    private fun retype(a: GeneAction, t: ActionType): GeneAction {
        val m = t == ActionType.Divide
        return a.copy(
            type = t,
            morphogenToMother = a.morphogenToMother && m,
            divideAcross = a.divideAcross && m,
            rejectMother = if (!m) false else if (a.type == ActionType.Divide) a.rejectMother else true,
        )
    }

    /** A dark tint of a group's [color] (40% brightness, full alpha) for its collapsible header background. */
    private fun groupHeaderBg(color: Long): Long {
        val r = ((color ushr 24) and 0xFF) * 40 / 100
        val g = ((color ushr 16) and 0xFF) * 40 / 100
        val b = ((color ushr 8) and 0xFF) * 40 / 100
        return (r shl 24) or (g shl 16) or (b shl 8) or 0xFF
    }

    /** Replace clause [ci], preserving the other AND-clauses. */
    private fun withClauseAt(d: Gene, ci: Int, c: Clause): Gene {
        val cs = d.condition.clauses.toMutableList()
        cs[ci] = c
        return d.copy(condition = GeneCondition(cs))
    }

    /** Drop clause [ci]. Removing the LAST clause is allowed — it empties the gate, restoring the gene to the
     *  unconditional ALWAYS state (the mirror of [addFirstClause]). */
    private fun removeClauseAt(d: Gene, ci: Int): Gene {
        val cs = d.condition.clauses
        if (ci !in cs.indices) return d
        return d.copy(condition = GeneCondition(cs.filterIndexed { i, _ -> i != ci }))
    }

    /** A fresh, deliberately **blank** first clause — `CHEM (nothing) > 0`. It reads as a real condition but
     *  never fires (a blank species counts 0), so it keeps the gene inert until the player names the molecule,
     *  matching how the blank ACTION keeps it inert until one is chosen. */
    private fun addFirstClause(d: Gene): Gene =
        d.copy(condition = GeneCondition(listOf(Clause(Operand.Chem(""), Comparison.Greater, Operand.Constant(0)))))

    /** Append a fresh AND-clause copied from the LAST clause (so a near-duplicate is one tweak away),
     *  capped at [CytoTuning.GENOME_MAX_CLAUSES]. (Clause is immutable, so reusing the value is a copy.) */
    private fun addClauseUi(d: Gene): Gene {
        if (d.condition.clauses.size >= CytoTuning.GENOME_MAX_CLAUSES) return d
        return d.copy(condition = GeneCondition(d.condition.clauses + d.condition.clauses.last()))
    }

    /** Picker index (into [operandKindLabels]) for an operand's kind. */
    private fun operandKind(op: Operand): Int = when (op) {
        is Operand.Constant -> 0
        is Operand.Chem -> 1
        Operand.Biomass -> 2
        Operand.Touching -> 3
        Operand.Neighbours -> 4
    }

    /** Build an operand of the picked [kind], carrying [prev]'s value/species when the kind is unchanged
     *  so switching kinds and back doesn't silently reset it. */
    private fun operandOfKind(kind: Int, prev: Operand): Operand = when (kind) {
        0 -> Operand.Constant((prev as? Operand.Constant)?.value ?: 0)
        1 -> Operand.Chem((prev as? Operand.Chem)?.species ?: atoms.first())
        2 -> Operand.Biomass
        3 -> Operand.Touching
        else -> Operand.Neighbours
    }

    private fun reset() {
        editingId = null
        editingIndex = null
        draft = null
        metabExpanded = false
        capturingGroup = false
        groupBuffer.setLength(0)
        pick = Pick.None
        pickClause = -1
        pickSide = 0
        confirmingDelete = false
        inlineLive = false
        openMenu = null
        lastFlush = null
        pendingDeleteGene = null
        pastePicking = false
        pasteConflict = null
    }

    // ── Desktop inline live-edit plumbing (§8a step 3b) ──
    /** Point `draft` at the live gene [i] (fresh, unless we're already editing it) so the shared edit
     *  logic + the render-loop flush write changes straight to the genome. No commit/DONE. */
    private fun beginInline(controller: CytoController, i: Int) {
        if (inlineLive && editingIndex == i && draft != null) return
        val g = controller.heldGenome()?.getOrNull(i) ?: return
        editingId = controller.lastHeldId
        editingIndex = i
        draft = g
        lastFlush = g
        inlineLive = true
    }

    /** Create a fresh [BLANK_GENE] tagged [group] at the end of the genome and open it inline immediately.
     *  The authoring "+ NEW GENE" path (create-from-scratch, not duplicate). The append is queued, so the new
     *  gene isn't in `heldGenome()` this frame — point the editor straight at its slot (prior genome size)
     *  rather than via [beginInline], which reads the live genome; the next-frame flush writes `draft` once
     *  the append lands (until then setHeldGene on that slot is a harmless out-of-range no-op). */
    private fun createGene(controller: CytoController, group: String) {
        val newIdx = controller.heldGenome()?.size ?: return
        val g = BLANK_GENE.copy(group = group)
        controller.appendHeldGene(g)
        editingId = controller.lastHeldId
        editingIndex = newIdx
        draft = g
        lastFlush = g
        inlineLive = true
        expandedGroups.add(group.ifEmpty { "OTHER" })
    }

    /** Create a fresh blank gene, open it inline, and immediately raise the typed-name dialog so the player
     *  names a brand-new group in one gesture. Mirrors the GROUP_DROP_NEW drag flow but for a new gene —
     *  [confirmGroupName] writes the typed name onto `draft` (the just-created gene). */
    private fun createGeneInNewGroup(controller: CytoController) {
        createGene(controller, "")
        startGroupCapture("")
    }

    /** Apply a live edit to gene [i] via `draft` (flushed next frame). */
    private fun inlineEdit(controller: CytoController, i: Int, transform: (Gene) -> Gene) {
        beginInline(controller, i)
        draft = draft?.let(transform)
    }

    /** Open the pick sheet for a sheet-backed slot (operand / species / group) on the inline card. */
    private fun openInlinePick(controller: CytoController, i: Int, p: Pick, clause: Int = -1, side: Int = 0) {
        beginInline(controller, i)
        openMenu = null
        openPick(p, clause, side)
    }

    /** Insert a copy of clause [ci] right after it (hover `+`), capped at [CytoTuning.GENOME_MAX_CLAUSES]. */
    private fun dupClause(controller: CytoController, i: Int, ci: Int) = inlineEdit(controller, i) { g ->
        val cs = g.condition.clauses
        if (cs.size >= CytoTuning.GENOME_MAX_CLAUSES || ci !in cs.indices) g
        else g.copy(condition = GeneCondition(cs.subList(0, ci + 1) + cs[ci] + cs.subList(ci + 1, cs.size)))
    }

    /** Resolve a gene [i] drop (§8a drag-and-drop re-group). A null target = dropped on empty space (no-op).
     *  The "new group" placeholder opens the typed-name dialog bound to this gene; a group header re-tags the
     *  gene live and auto-expands the destination so it doesn't vanish into a collapsed group. */
    private fun handleGeneDrop(controller: CytoController, i: Int, targetId: String?) {
        when {
            targetId == null -> {}
            // Narrow captures the name against the gene index alone: opening an inline draft would put the
            // full-screen editor up over a gesture that only meant to re-file the gene.
            targetId == GROUP_DROP_NEW ->
                if (narrowLayout) { groupCaptureGene = i; startGroupCapture("") }
                else { beginInline(controller, i); startGroupCapture("") }
            targetId == GENE_DROP_DUP -> controller.duplicateHeldGene(i)
            targetId == GENE_DROP_DEL -> pendingDeleteGene = i   // opens the confirm dialog next frame
            targetId.startsWith(GENE_REORDER_PREFIX) ->
                targetId.removePrefix(GENE_REORDER_PREFIX).toIntOrNull()?.let { controller.reorderHeldGeneInGroup(i, it) }
            targetId.startsWith(GROUP_DROP_PREFIX) -> {
                val name = targetId.removePrefix(GROUP_DROP_PREFIX)
                if (narrowLayout) retagHeldGene(controller, i, name)
                else inlineEdit(controller, i) { it.copy(group = name) }
                expandedGroups.add(name.ifEmpty { "OTHER" })
            }
        }
    }

    /** Write gene [i]'s group straight to the genome — the narrow re-tag, which (unlike [inlineEdit]) leaves
     *  no draft parked and so doesn't raise the full-screen editor behind the gesture. */
    private fun retagHeldGene(controller: CytoController, i: Int, group: String) {
        val g = controller.heldGenome()?.getOrNull(i) ?: return
        if (g.group != group) controller.setHeldGene(i, g.copy(group = group))
    }

    /** Hover `×`: first tap arms, second tap on the same clause deletes it (never the last clause). */
    private fun deleteClauseArmed(controller: CytoController, i: Int, ci: Int) {
        val key = "$i:$ci"
        if (armedClauseDelete == key) { inlineEdit(controller, i) { removeClauseAt(it, ci) }; armedClauseDelete = null }
        else armedClauseDelete = key
    }

    /** Box 1 of the POWERED BY row: the source TYPE alone. What a BOND gene actually makes is box 2
     *  ([synthesisLabel]), so this never names a molecule. */
    private fun sourceTypeLabel(s: EnergySource): String = GeneCardLabels.sourceType(s)

    private fun sourceLabel(s: EnergySource): String = sourceTypeLabel(s)
}
