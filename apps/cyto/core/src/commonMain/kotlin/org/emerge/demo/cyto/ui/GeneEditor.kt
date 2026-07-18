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
import org.emerge.sim.core.EntityId
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.PanelBuilder
import org.emerge.render.torus.ui.UiBuilder

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
class GeneEditor {
    /** A registry-less grouping used in free play: [GenomeGrouping.sections] still buckets a tagged genome by
     *  its own tags (auto-coloured by name); it just offers no "+ ADD" inserts (those are campaign-authored). */
    private val EMPTY_GROUPING = GenomeGrouping(emptyList())

    private companion object {
        // Container geometry, shared by the render paths and freeAreaOffsetPx (the source of truth for how
        // much of the world each layout occludes). dp — multiply by scale for framebuffer px.
        const val CELL_PANEL_DP = 380f       // wide: dockRight cell-panel width
        const val PANEL_MARGIN_DP = 12f      // wide: dockRight / editor-column margin
        const val EDIT_COL_DP = 440f         // wide: max gene-editor column width
        const val SHEET_FRACTION = 0.58f     // narrow: dockBottom cell-sheet full (L2) height fraction
        const val PEEK_FRACTION = 0.2f       // narrow: dockBottom cell-sheet collapsed peek (L1) height
    }

    private var editingId: EntityId? = null
    private var editingIndex: Int? = null
    private var draft: Gene? = null

    /** The current world's chemical aliases, refreshed from the controller at the top of every [render] so
     *  the species formatter ([sp]) names molecules the campaign's way. */
    private var activeAliases: Map<String, String> = emptyMap()

    // Narrow L1→L2 detent: a freshly selected cell shows a shallow peek (name + biomass); expanding drills to
    // the full L2 sheet. Reset to the peek whenever the held cell changes ([peekedId] tracks that).
    private var cellExpanded = false
    private var peekedId: EntityId? = null
    /** Live sheet height fraction while dragging the grab handle (null when not dragging); snapped to a
     *  detent on release. Lets the peek↔full transition track the finger instead of jumping. */
    private var sheetDragFrac: Float? = null

    /** Which L4 picker sheet (if any) is open over the narrow L3 modal, and its target (clause index +
     *  side for operand/value pickers). Cross-frame UI state, like the rest of the editor. */
    private enum class Pick { None, Action, Source, Group, Operand, SpeciesA, SpeciesB, Eff, Overflow }
    private var pick = Pick.None
    private var pickClause = -1
    private var pickSide = 0            // 0 = lhs, 1 = rhs (Operand picker)
    private var confirmingDelete = false   // Overflow sheet is showing the "delete this gene?" confirm step.

    // In-game group tagging: when the player picks "New group..." the editor captures a typed name into
    // [groupBuffer] (the host routes keystrokes here — see [capturingGroupName]/[typeGroupChar]). Only the
    // draft's tag changes; it commits with DONE like every other field.
    private var capturingGroup = false
    private val groupBuffer = StringBuilder()

    /** Which functional groups are expanded (by name) when a [GenomeGrouping] overlay is showing. Collapsed
     *  by default so the genome reads as a few named subsystems, not a wall of genes; the player opens the
     *  one they care about. Cross-frame UI state, like the rest of the editor. */
    private val expandedGroups = HashSet<String>()

    /** The dense ENV/CYT/BIO metabolism table is collapsed by default — it's a spreadsheet of chemical
     *  counts that overwhelms a new player. Tap the header to reveal it. Reset when the target cell changes. */
    private var metabExpanded = false

    // Option lists, derived from the seeded alphabet. Species operands are built atom-by-atom (see
    // [speciesBuilder]) rather than picked from a fixed list, so any-length molecules (abb, abcb, …) are
    // reachable — the old monomer+dimer dropdown couldn't express them.
    private val atoms: List<String> = CytoSeed.SEED_MONOMERS
    private val bonds: List<String> = atoms.flatMap { x -> atoms.map { y -> x + y } }

    /** The four operand kinds, in picker order: a constant value, a cytoplasm count, total biomass, or
     *  the contact count. */
    private val operandKindLabels: List<String> = listOf("Const", "Chem", "Conc", "BIO", "Touch", "Nbrs")

    /** True while a gene is open for editing. In the narrow layout the L3 modal is full-screen, so the host
     *  suppresses other overlays (the campaign coach) behind it — see `apps/cyto/UI_REDESIGN.md` §6.1. */
    val isEditing: Boolean get() = draft != null

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
        if (name.isNotEmpty()) draft = draft?.copy(group = name)
        capturingGroup = false
    }

    /** Abandon the typed group name unchanged (host ESC). */
    fun cancelGroupName() { capturingGroup = false }

    private fun startGroupCapture(initial: String) {
        capturingGroup = true
        groupBuffer.setLength(0); groupBuffer.append(initial)
        pick = Pick.None
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
        // Wide: the cell panel docks right; when editing, the gene-editor column docks to its left. The free
        // world area is everything left of the leftmost panel — mirror renderGeneEditor's x0 exactly.
        val cellLeftX = resW - (CELL_PANEL_DP + PANEL_MARGIN_DP) * scale
        val leftEdge = if (draft != null) {
            val m = PANEL_MARGIN_DP * scale
            val colW = minOf(EDIT_COL_DP * scale, (cellLeftX - m * 2f).coerceAtLeast(200f))
            (cellLeftX - m - colW).coerceAtLeast(m)
        } else cellLeftX
        return -(resW - leftEdge) * 0.5f to 0f
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
    ) {
        val info = controller.heldCellInfo()
        if (info == null) { reset(); return }
        activeAliases = controller.speciesAliases
        if (editingId != null && editingId != controller.lastHeldId) reset()   // grabbed a different cell
        if (controller.lastHeldId != peekedId) { peekedId = controller.lastHeldId; cellExpanded = false; sheetDragFrac = null }   // new cell → peek

        // Progressive-disclosure UI everywhere (apps/cyto/UI_REDESIGN.md §8); `narrow` only chooses the
        // container geometry — a bottom sheet + full-screen modal + bottom picker on a phone, a docked right
        // panel + a column beside it + a centred popover on a wide screen. The sentence-model content is
        // identical in both.
        val wide = !narrow
        if (wide) {
            // L2 cell panel docked right; when a gene is open, the L3 editor docks as a column to its left.
            val cellLeftX = renderCellPanel(b, controller, info, grouping, insertableGroups, onExport, wide = true)
            if (draft != null) renderGeneEditor(b, controller, wide = true, rightEdge = cellLeftX)
        } else {
            if (draft != null) renderGeneEditor(b, controller, wide = false, rightEdge = 0f)
            else renderCellPanel(b, controller, info, grouping, insertableGroups, onExport, wide = false)
        }
        draft?.let { renderPickerSheet(b, controller, it, wide) }
    }

    /**
     * The **L2 cell view** (`apps/cyto/UI_REDESIGN.md` §3): the held cell's vitals, a collapsible chemistry
     * table, and its genome grouped into collapsible subsystems. Tapping a gene opens the L3 editor. Same
     * content in either container — a **docked right panel** on a wide screen (returns its left x so the L3
     * column can dock beside it) or a **bottom sheet** on a phone (returns 0).
     */
    private fun renderCellPanel(
        b: UiBuilder, controller: CytoController, info: CytoController.CellInfo,
        grouping: GenomeGrouping?, insertableGroups: Set<String>, onExport: () -> Unit, wide: Boolean,
    ): Float {
        if (wide) {
            val body: PanelBuilder.() -> Unit = { cellBody(controller, info, grouping, insertableGroups, onExport) }
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
                cellBody(controller, info, grouping, insertableGroups, onExport)
            } else {
                // The collapsed peek is one non-scrolling card filling the sheet: a drag anywhere on it (not
                // just a top handle) expands, and its tiny content can't scroll. Height = sheet minus padding.
                val cardH = screenH * liveFrac - padDp * b.density * 2f
                dragCard(
                    "cell-grab", cardH,
                    listOf(
                        "CELL ${info.id}  ${info.type}" to 0xFFFFFFFFL,
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
    ) {
        title("CELL ${info.id}  ${info.type}")
        // Per-line key/values right-align their value, so the panel reads at any width (a single-line vitals
        // row overflowed the narrow wide-screen column).
        keyValue("SIZE", info.radius)
        keyValue("BIOMASS", info.totalBiomass.toString())
        keyValue("LIGHT", info.light)
        if (info.metabolism.isNotEmpty()) {
            gap(6f)
            button("${if (metabExpanded) "-" else "+"} CHEMISTRY (${info.metabolism.size})", 0x2A3550FFL) { metabExpanded = !metabExpanded }
            if (metabExpanded) metabolismTable(info)
        }
        if (info.genes.isNotEmpty()) {
            gap(8f)
            row("GENOME  (TAP A GENE TO EDIT)", 0x7A8699FFL)
            val liveGenes = info.genes.map { it.gene }
            val effectiveGrouping = grouping ?: if (liveGenes.any { it.group.isNotEmpty() }) EMPTY_GROUPING else null
            val sections = effectiveGrouping?.sections(liveGenes)
            if (sections != null) {
                for (sec in sections) {
                    val label = sec.name ?: "OTHER"
                    val open = expandedGroups.contains(label)
                    button("${if (open) "-" else "+"} $label (${sec.items.size})", groupHeaderBg(sec.color)) {
                        if (open) expandedGroups.remove(label) else expandedGroups.add(label)
                    }
                    if (open) for (item in sec.items) geneButton(controller, info.genes[item.index], item.index)
                }
                for (grp in effectiveGrouping.groups) {
                    if (grp.name in insertableGroups && grp.insert.isNotEmpty() && liveGenes.none { it.group == grp.name })
                        button("+ ADD ${grp.name.uppercase()}", 0x2A3F5AFFL) { controller.addHeldGenes(grp.insert) }
                }
            } else {
                info.genes.forEachIndexed { i, g -> geneButton(controller, g, i) }
            }
            gap(6f)
            button("EXPORT GENOME", 0x3A6EA5FFL) { onExport() }
        }
    }

    /**
     * The **L3 gene-detail modal** (narrow/phone layout — `apps/cyto/UI_REDESIGN.md` §3). The gene reads as a
     * sentence: **WHEN** <condition> **DO** <action> **POWERED BY** <source>, each phrase a tappable chip that
     * opens its picker. Inline binary choices (comparator, sever, orient, keep) are segmented controls — no
     * drill-down. Chip taps that need a value list (operands, action, source, morphogen, group) will open L4
     * sheets in the next step; here they lay out and read live draft state.
     */
    private fun renderGeneEditor(b: UiBuilder, controller: CytoController, wide: Boolean, rightEdge: Float) {
        val d = draft ?: return
        val idx = editingIndex ?: return
        val title = "GENE ${idx + 1}" + if (d.group.isEmpty()) "" else " · ${d.group.uppercase()}"
        val actions = listOf(
            Triple("CANCEL", 0x808890FFL) { reset() },
            Triple("DONE", 0x33AA33FFL) { commit(controller) },
        )
        val body: PanelBuilder.() -> Unit = { geneBody(controller, d) }
        if (wide) {
            // A docked column to the LEFT of the cell panel — the world stays visible around it.
            val m = PANEL_MARGIN_DP * b.density
            val colW = minOf(EDIT_COL_DP * b.density, (rightEdge - m * 2f).coerceAtLeast(200f))
            val x0 = (rightEdge - m - colW).coerceAtLeast(m)
            b.modal("gene-editor", title, onBack = { reset() }, actions = actions, onOverflow = { openPick(Pick.Overflow) },
                boundsX = x0, boundsY = m, boundsW = colW, boundsH = b.screenH - m * 2f,
                titleBar = 34f, bottomBar = 46f, margin = 12f, rowHeight = 30f, textSize = 15f, body = body)
        } else {
            b.modal("gene-editor", title, onBack = { reset() }, actions = actions, onOverflow = { openPick(Pick.Overflow) },
                statusBar = 24f, titleBar = 56f, bottomBar = 72f, rowHeight = 48f, textSize = 16f, body = body)
        }
    }

    /** The gene-as-a-sentence body (WHEN / DO / POWERED BY / GROUP), shared by both container geometries. */
    private fun PanelBuilder.geneBody(controller: CytoController, d: Gene) {
        // ── WHEN: the condition, one row of three chips per AND-clause ──
        row("WHEN", 0x7A8699FFL)
        val clauses = d.condition.clauses
        clauses.forEachIndexed { ci, cl ->
            clauseRow(
                operandLabel(cl.lhs), if (cl.cmp == Comparison.Greater) ">" else "<", operandLabel(cl.rhs),
                onLhs = { openPick(Pick.Operand, ci, 0) }, onRhs = { openPick(Pick.Operand, ci, 1) },
                onCmp = { draft = withClauseAt(d, ci, cl.copy(cmp = if (cl.cmp == Comparison.Greater) Comparison.Less else Comparison.Greater)) },
            )
        }
        if (clauses.size < CytoTuning.GENOME_MAX_CLAUSES)
            chip("", "+ AND CLAUSE", 0x1E2634FFL) { draft = addClauseUi(d) }
        gap(12f)

        // ── DO: the action, then only the fields this action actually has ──
        row("DO", 0x7A8699FFL)
        chip("", d.action.type.name, 0x35507AFFL) { openPick(Pick.Action) }
        when (d.action.type) {
            ActionType.Import, ActionType.Export, ActionType.Convert, ActionType.Retain ->
                chip("OPERAND", sp(d.action.a)) { openPick(Pick.SpeciesA) }
            ActionType.FormBond -> {
                chip("LEFT", sp(d.action.a)) { openPick(Pick.SpeciesA) }
                segmented("MATCH L", listOf("EXACT", "ENDS *"), if (d.action.aWild) 1 else 0) { i -> draft = d.copy(action = d.action.copy(aWild = i == 1)) }
                chip("RIGHT", sp(d.action.b)) { openPick(Pick.SpeciesB) }
                segmented("MATCH R", listOf("EXACT", "* STARTS"), if (d.action.bWild) 1 else 0) { i -> draft = d.copy(action = d.action.copy(bWild = i == 1)) }
            }
            ActionType.Mitosis -> {
                chip("MORPHOGEN", sp(d.action.a)) { openPick(Pick.SpeciesA) }
                if (d.action.a.isNotEmpty())
                    segmented("KEEP", listOf("DAUGHTER", "MOTHER"), if (d.action.morphogenToMother) 1 else 0) { i -> draft = d.copy(action = d.action.copy(morphogenToMother = i == 1)) }
                chip("AXIS", sp(d.action.b)) { openPick(Pick.SpeciesB) }
                if (d.action.b.isNotEmpty())
                    segmented("ORIENT", listOf("ALONG", "ACROSS"), if (d.action.divideAcross) 1 else 0) { i -> draft = d.copy(action = d.action.copy(divideAcross = i == 1)) }
                segmented("SEVER", listOf("NO", "YES"), if (d.action.rejectMother) 1 else 0) { i -> draft = d.copy(action = d.action.copy(rejectMother = i == 1)) }
            }
            else -> {}
        }
        if (d.action.type != ActionType.Mitosis)
            chip("EFFICIENCY", d.efficiency.toString()) { openPick(Pick.Eff) }
        gap(12f)

        // ── POWERED BY: the energy source ──
        row("POWERED BY", 0x7A8699FFL)
        chip("", sourceLabel(d.source)) { openPick(Pick.Source) }
        gap(12f)

        chip("GROUP", d.group.uppercase().ifEmpty { "(NONE)" }) { openPick(Pick.Group) }
    }

    private fun openPick(p: Pick, clause: Int = -1, side: Int = 0) {
        pick = p; pickClause = clause; pickSide = side
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
                for ((i, t) in ActionType.entries.withIndex()) {
                    listRow(t.name, actionBlurb(t), selected = t == d.action.type) {
                        val mitosis = t == ActionType.Mitosis
                        draft = d.copy(action = d.action.copy(type = t, morphogenToMother = d.action.morphogenToMother && mitosis, divideAcross = d.action.divideAcross && mitosis, rejectMother = d.action.rejectMother && mitosis))
                        closePick()
                    }
                    if (i < ActionType.entries.lastIndex) gap(4f)
                }
            }
            Pick.Source -> pickSheet(b, "POWERED BY?", wide) {
                listRow("LIGHT", selected = d.source is EnergySource.Light) {
                    draft = d.copy(source = EnergySource.Light); closePick()
                }
                gap(4f)
                for (bond in bonds) {
                    listRow("BREAK ${sp(bond)}", selected = (d.source as? EnergySource.BreakBond)?.bond == bond) {
                        draft = d.copy(source = EnergySource.BreakBond(bond)); closePick()
                    }
                    gap(4f)
                }
            }
            Pick.Group -> pickSheet(b, "GROUP?", wide) {
                val existing = controller.heldGenome()?.mapNotNull { it.group.ifBlank { null } }?.distinct() ?: emptyList()
                listRow("(NONE)", selected = d.group.isEmpty()) { draft = d.copy(group = ""); closePick() }
                gap(4f)
                for (g in existing) {
                    listRow(g.uppercase(), selected = d.group == g) { draft = d.copy(group = g); closePick() }
                    gap(4f)
                }
                listRow("NEW GROUP...", "TYPE A NAME ON THE KEYBOARD") { startGroupCapture(d.group); closePick() }
            }
            Pick.Operand -> renderOperandSheet(b, d, wide)
            Pick.SpeciesA -> pickSheet(b, "MOLECULE", wide) {
                speciesBuilder(d.action.a) { draft = d.copy(action = d.action.copy(a = it)) }
            }
            Pick.SpeciesB -> pickSheet(b, "MOLECULE", wide) {
                speciesBuilder(d.action.b) { draft = d.copy(action = d.action.copy(b = it)) }
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
                    listRow("DELETE", "REMOVE THIS GENE") { confirmingDelete = true }
                }
            }
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

    /** The operand picker: a kind list (Const/Chem/Conc/BIO/Touch/Nbrs) then the value editor for whichever
     *  kind is selected — a number for Const, a species builder for Chem/Conc, nothing for the live readings. */
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
                is Operand.Chem -> { row("MOLECULE", 0x7A8699FFL); speciesBuilder(op.species) { setOp(Operand.Chem(it)) } }
                is Operand.Conc -> { row("MOLECULE", 0x7A8699FFL); speciesBuilder(op.species) { setOp(Operand.Conc(it)) } }
                else -> {}
            }
            // Remove-clause parity with the old form: only when more than one AND-clause remains.
            if (d.condition.clauses.size > 1) {
                gap(8f)
                listRow("REMOVE THIS CLAUSE", "DROP THIS CONDITION FROM THE GENE") { draft = removeClauseAt(d, ci); closePick() }
            }
        }
    }

    /** A number editor: a hold-to-repeat ± stepper (accelerating 1→10→100→1000, the desktop 2000-tap fix)
     *  plus coarse presets. */
    private fun PanelBuilder.numberField(value: Int, min: Int, max: Int, onSet: (Int) -> Unit) {
        stepper("", value.toString()) { delta -> onSet((value + delta).coerceIn(min, max)) }
        val presets = if (max <= 32) listOf(min, (min + max) / 2, max) else listOf(0, 100, 500, 1000, 5000)
        actionRow(presets.distinct().filter { it in min..max }.map { p ->
            Triple<String, Long, () -> Unit>(p.toString(), 0x2A3550FFL) { onSet(p) }
        })
    }

    /** A species built atom-by-atom: the molecule so far, then `+<atom>` per alphabet atom and a `<`
     *  backspace. `(NONE)` when empty (a valid no-op / symmetric-division state). */
    private fun PanelBuilder.speciesBuilder(current: String, onChange: (String) -> Unit) {
        chip("", sp(current), 0x2A3550FFL) {}
        gap(6f)
        actionRow(atoms.map { a -> Triple<String, Long, () -> Unit>("+${a.uppercase()}", 0x32503CFFL) { onChange(current + a) } } +
            Triple<String, Long, () -> Unit>("<", 0x5A3A3AFFL) { onChange(current.dropLast(1)) })
    }

    private fun closePick() { pick = Pick.None; pickClause = -1; pickSide = 0; confirmingDelete = false }

    /** One-line gloss of an action, for the L4 list picker (the room a dropdown never had). */
    private fun actionBlurb(t: ActionType): String = when (t) {
        ActionType.Import -> "PULL A MOLECULE IN FROM OUTSIDE"
        ActionType.Export -> "PUSH A MOLECULE OUT TO OUTSIDE"
        ActionType.FormBond -> "JOIN TWO MOLECULES INTO ONE"
        ActionType.Convert -> "LOCK A MOLECULE INTO BIOMASS - GROW"
        ActionType.Contract -> "SHRINK THE RADIUS - A MUSCLE FLEX"
        ActionType.Mitosis -> "DIVIDE INTO TWO CELLS"
        ActionType.Repair -> "HEAL THE MOST-DAMAGED WELD"
        ActionType.Lyse -> "TEAR BIOMASS FROM A TOUCHING CELL"
        ActionType.Retain -> "SEAL A MOLECULE INSIDE THE CELL"
    }

    /** One-line gloss of an operand kind, for the L4 operand picker. */
    private fun operandKindBlurb(i: Int): String = when (i) {
        0 -> "A FIXED NUMBER"
        1 -> "COUNT OF A MOLECULE (BIOMASS + CYTO)"
        2 -> "CONCENTRATION OF A MOLECULE"
        3 -> "TOTAL BIOMASS"
        4 -> "CELLS TOUCHING THIS ONE"
        else -> "WELDED NEIGHBOURS"
    }

    /** A molecule's display name (built-in flavour name / raw token), upper-cased for the caps UI. Empty
     *  reads as "(NONE)". Genome aliases (Layer 2) will thread through here later. */
    private fun sp(species: String): String = SpeciesNames.name(species, activeAliases).uppercase()

    /** A compact operand label for an L3 clause chip (see [renderGeneModal]). */
    private fun operandLabel(op: Operand): String = when (op) {
        is Operand.Constant -> op.value.toString()
        is Operand.Chem -> "CHEM ${sp(op.species)}"
        is Operand.Conc -> "CONC ${sp(op.species)}"
        Operand.Biomass -> "BIO"
        Operand.Touching -> "TOUCH"
        Operand.Neighbours -> "NBRS"
    }

    /** One gene as a tappable button: editing = blue, active = green, inactive = grey; the parts of an
     *  inactive gene that block it (failed clause / energy / input) draw orange inline. [i] is its live
     *  genome index, used to open it for editing. */
    private fun PanelBuilder.geneButton(controller: CytoController, g: CytoController.CellInfo.GeneRow, i: Int) {
        val bg = when {
            editingIndex == i -> 0x4488CCFFL
            g.active -> 0x2E8B40FFL
            else -> 0x3C3C3CFFL
        }
        // Split the one-line span sentence — `ACTION IF clause & clause (source) eN` — into a card with the
        // condition clauses ONE PER LINE, then an action line: `WHEN <clause>` / ` AND <clause>` / ... /
        // `→ <ACTION> (source) eN`. The markers " IF ", " (" and the " & " clause separator are emitted
        // verbatim by describeGeneSpans, so we partition on them. Blocking spans stay orange.
        val ORANGE = 0xC8963CFFL
        val GREY = 0x9A9A9AFFL
        val spans = g.spans
        val ifIdx = spans.indexOfFirst { it.text == " IF " }
        val parenIdx = spans.indexOfFirst { it.text == " (" }
        fun spanPair(s: CytoController.CellInfo.Span) = s.text to (if (s.blocking) ORANGE else null as Long?)
        val lines: List<List<Pair<String, Long?>>>
        if (ifIdx < 0 || parenIdx < 0) {
            // Fallback: shape not recognised — show the raw sentence on one line.
            lines = listOf(spans.map { spanPair(it) })
        } else {
            // The clause span run is "clause & clause & ..." between " IF " and " (". Break it at each " & "
            // so every AND-clause becomes its own line, keeping its blocking colour.
            val clauseSpans = spans.subList(ifIdx + 1, parenIdx)
            val clauseLines = mutableListOf<MutableList<Pair<String, Long?>>>()
            for (s in clauseSpans) {
                if (s.text == " & " || clauseLines.isEmpty()) clauseLines.add(mutableListOf())
                if (s.text != " & ") clauseLines.last().add(spanPair(s))
            }
            val out = mutableListOf<List<Pair<String, Long?>>>()
            if (clauseLines.isEmpty() || clauseLines.all { it.isEmpty() }) {
                out.add(listOf("WHEN " to GREY, "always" to GREY))
            } else {
                clauseLines.forEachIndexed { ci, cl ->
                    out.add(listOf<Pair<String, Long?>>((if (ci == 0) "WHEN " else " AND ") to GREY) + cl)
                }
            }
            // Fuel FIRST, on its own line, then the action, then its modifiers indented — so a long action
            // (a DIVIDE with a morphogen + axis) no longer overflows the panel. Blocking parts stay orange:
            // the source line when there's no fuel, the action line when its input is missing.
            val gene = g.gene
            val energyBlocked = spans.getOrNull(parenIdx + 1)?.blocking == true
            val inputBlocked = spans[0].blocking
            out.add(listOf(sourceProse(gene.source) to (if (energyBlocked) ORANGE else GREY)))
            val (actionMain, mods) = actionProse(gene.action)
            val actionText = actionMain + if (gene.efficiency != 0) " e${gene.efficiency}" else ""
            out.add(listOf(actionText to (if (inputBlocked) ORANGE else null)))
            for (m in mods) out.add(listOf<Pair<String, Long?>>(" $m" to GREY))
            lines = out
        }
        geneCard(lines, bg) { open(controller, i) }
    }

    /** The gene's power source as its own prose line, shown BEFORE the action (Stu's format) so a long
     *  action doesn't overflow. Break-bond fuel names the two atoms it frees; light is just light.
     *  NOTE: this wording is the placeholder the deeper energy-source rework will replace. */
    private fun sourceProse(s: EnergySource): String = when (s) {
        EnergySource.Light -> "USE LIGHT TO POWER"
        is EnergySource.BreakBond -> {
            // The duomer name already carries the identity, so the yielded atoms stay compact — their raw
            // single-letter symbols joined by `/` (e.g. GREBLU (G/B)) — to keep the fuel line inside the panel.
            // (The bitmap font has no `|` glyph, so `/` stands in for the separator.)
            val atoms = if (s.bond.length == 2) " (${s.bond.uppercase().toCharArray().joinToString("/")})" else ""
            "BREAK ${sp(s.bond)}$atoms TO POWER"
        }
    }

    /** The action as a main line plus any modifier lines (the caller indents the modifiers). Only Mitosis
     *  carries modifiers — the morphogen it hands to a daughter/mother, and a sever. */
    private fun actionProse(a: GeneAction): Pair<String, List<String>> {
        val av = sp(a.a); val bv = sp(a.b)
        return when (a.type) {
            ActionType.Import -> "IMPORT $av" to emptyList()
            ActionType.Export -> "EXPORT $av" to emptyList()
            ActionType.FormBond -> {
                // Mirror the break-bond line: name the PRODUCT, show the two reactants in parens.
                // BOND FUEL (R/G) reads as the inverse of BREAK FUEL (R/G). Wildcard/illegal joins have no
                // single product, so fall back to the reactant form with `*` markers.
                val product = if (!a.aWild && !a.bWild && a.a.isNotEmpty() && a.b.isNotEmpty()) Molecules.join(a.a, a.b) else null
                if (product != null) {
                    "BOND ${sp(product)} (${a.a.uppercase()}/${a.b.uppercase()})" to emptyList()
                } else {
                    val la = if (a.aWild && a.a.isNotEmpty()) "*$av" else av
                    val lb = if (a.bWild && a.b.isNotEmpty()) "$bv*" else bv
                    "BOND $la AND $lb" to emptyList()
                }
            }
            ActionType.Convert -> "CONVERT $av TO MASS" to emptyList()
            ActionType.Contract -> "CONTRACT" to emptyList()
            ActionType.Repair -> "REPAIR WELDS" to emptyList()
            ActionType.Lyse -> "LYSE" to emptyList()
            ActionType.Retain -> "RETAIN $av" to emptyList()
            ActionType.Mitosis -> {
                val main = "DIVIDE" + if (a.b.isEmpty()) "" else " ${if (a.divideAcross) "ACROSS" else "ALONG"} $bv GRADIENT"
                val mods = buildList {
                    if (a.a.isNotEmpty()) add(if (a.morphogenToMother) "RETAINING $av IN THE MOTHER CELL" else "GIVING $av TO ONE DAUGHTER")
                    if (a.rejectMother) add("SEVERING THE DAUGHTER FREE")
                }
                main to mods
            }
        }
    }

    /** A dark tint of a group's [color] (40% brightness, full alpha) for its collapsible header background. */
    private fun groupHeaderBg(color: Long): Long {
        val r = ((color ushr 24) and 0xFF) * 40 / 100
        val g = ((color ushr 16) and 0xFF) * 40 / 100
        val b = ((color ushr 8) and 0xFF) * 40 / 100
        return (r shl 24) or (g shl 16) or (b shl 8) or 0xFF
    }

    private fun open(controller: CytoController, index: Int) {
        val gene = controller.heldGenome()?.getOrNull(index) ?: return
        editingId = controller.lastHeldId
        editingIndex = index
        draft = gene
        closePick()
    }

    /** Replace clause [ci], preserving the other AND-clauses. */
    private fun withClauseAt(d: Gene, ci: Int, c: Clause): Gene {
        val cs = d.condition.clauses.toMutableList()
        cs[ci] = c
        return d.copy(condition = GeneCondition(cs))
    }

    /** Drop clause [ci] (never empties the gate — a gene keeps ≥1 clause). */
    private fun removeClauseAt(d: Gene, ci: Int): Gene {
        val cs = d.condition.clauses
        if (cs.size <= 1) return d
        return d.copy(condition = GeneCondition(cs.filterIndexed { i, _ -> i != ci }))
    }

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
        is Operand.Conc -> 2
        Operand.Biomass -> 3
        Operand.Touching -> 4
        Operand.Neighbours -> 5
    }

    /** Build an operand of the picked [kind], carrying [prev]'s value/species when the kind is unchanged
     *  so switching kinds and back doesn't silently reset it. */
    private fun operandOfKind(kind: Int, prev: Operand): Operand = when (kind) {
        0 -> Operand.Constant((prev as? Operand.Constant)?.value ?: 0)
        1 -> Operand.Chem((prev as? Operand.Chem)?.species ?: atoms.first())
        2 -> Operand.Conc((prev as? Operand.Conc)?.species ?: atoms.first())
        3 -> Operand.Biomass
        4 -> Operand.Touching
        else -> Operand.Neighbours
    }

    private fun commit(controller: CytoController) {
        val d = draft ?: return
        val idx = editingIndex ?: return
        controller.setHeldGene(idx, d)
        reset()
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
    }

    private fun sourceLabel(s: EnergySource): String = when (s) {
        EnergySource.Light -> "LIGHT"
        is EnergySource.BreakBond -> "BREAK ${sp(s.bond)}"
    }
}
