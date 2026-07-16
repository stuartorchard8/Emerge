package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.Clause
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneCondition
import org.emerge.demo.cyto.sim.Operand
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
    private enum class Field { Source, LhsKind, Cmp, RhsKind, Action, MitosisSide, MitosisAxis, Group }

    /** A registry-less grouping used in free play: [GenomeGrouping.sections] still buckets a tagged genome by
     *  its own tags (auto-coloured by name); it just offers no "+ ADD" inserts (those are campaign-authored). */
    private val EMPTY_GROUPING = GenomeGrouping(emptyList())

    private var editingId: EntityId? = null
    private var editingIndex: Int? = null
    private var draft: Gene? = null
    private var openField: Field? = null
    private var openClause: Int = -1   // which clause's LHS/CMP/RHS dropdown is open (for the AND-conjunction)

    /** Which L4 picker sheet (if any) is open over the narrow L3 modal, and its target (clause index +
     *  side for operand/value pickers). Cross-frame UI state, like the rest of the editor. */
    private enum class Pick { None, Action, Source, Group, Operand, SpeciesA, SpeciesB, Eff }
    private var pick = Pick.None
    private var pickClause = -1
    private var pickSide = 0            // 0 = lhs, 1 = rhs (Operand picker)

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
    // [speciesField]) rather than picked from a fixed list, so any-length molecules (abb, abcb, …) are
    // reachable — the old monomer+dimer dropdown couldn't express them.
    private val atoms: List<String> = CytoSeed.SEED_MONOMERS
    private val bonds: List<String> = atoms.flatMap { x -> atoms.map { y -> x + y } }
    private val sourceLabels: List<String> = listOf("Light") + bonds.map { "Brk $it" }
    private val actionLabels: List<String> = ActionType.entries.map { it.name }

    /** The four operand kinds, in picker order: a constant value, a cytoplasm count, total biomass, or
     *  the contact count. */
    private val operandKindLabels: List<String> = listOf("Const", "Chem", "Conc", "BIO", "Touch", "Nbrs")

    /** True while a gene is open for editing. In the narrow layout the L3 modal is full-screen, so the host
     *  suppresses other overlays (the campaign coach) behind it — see `apps/cyto/UI_REDESIGN.md` §6.1. */
    val isEditing: Boolean get() = draft != null

    /** Called if the editor target vanishes (cell died / deselected) — discard any in-progress edit. */
    fun closeDropdown() { openField = null }

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
        openField = null
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
        if (editingId != null && editingId != controller.lastHeldId) reset()   // grabbed a different cell

        // Narrow (phone) layout: while a gene is open, the whole screen becomes the L3 gene-detail modal
        // (apps/cyto/UI_REDESIGN.md §3), replacing the two stacked desktop panels. The wide layout below is
        // untouched, so desktop renders bit-identically.
        if (narrow && draft != null) { renderGeneModal(b, controller); return }

        b.panel(Anchor.TopRight) {
            title("CELL ${info.id}")
            keyValue("TYPE", info.type)
            keyValue("SIZE", info.radius)
            keyValue("BIOMASS", info.totalBiomass.toString())
            keyValue("LIGHT", info.light)
            if (info.metabolism.isNotEmpty()) {
                gap()
                button("${if (metabExpanded) "-" else "+"} CHEMISTRY (${info.metabolism.size})", 0x2A3550FFL) { metabExpanded = !metabExpanded }
                if (metabExpanded) metabolismTable(info)
            }
            if (info.genes.isNotEmpty()) {
                val liveGenes = info.genes.map { it.gene }
                // Show the grouped view whenever a grouping is supplied (campaign, with "+ ADD" inserts) OR
                // the genome carries any group tag (free play / loaded saves) — so grouping is a property of
                // the genome, visible everywhere, not a campaign-only feature. Untagged genomes stay flat.
                val effectiveGrouping = grouping ?: if (liveGenes.any { it.group.isNotEmpty() }) EMPTY_GROUPING else null
                val sections = effectiveGrouping?.sections(liveGenes)
                if (sections != null) {
                    gap(); row("GENES BY FUNCTION (tap a group to open it)")
                    for (sec in sections) {
                        val label = sec.name ?: "OTHER"
                        val open = expandedGroups.contains(label)
                        // A group header: "+ NAME (n)" collapsed / "- NAME (n)" expanded (triangle glyphs
                        // aren't in the bitmap font). Tinted with the group's colour so subsystems are
                        // visually distinct at a glance.
                        button("${if (open) "-" else "+"} $label (${sec.items.size})", groupHeaderBg(sec.color)) {
                            if (open) expandedGroups.remove(label) else expandedGroups.add(label)
                        }
                        if (open) for (item in sec.items) geneButton(controller, info.genes[item.index], item.index)
                    }
                    // Absent groups the current chapter marks insertable show as a ready-made "ADD" affordance:
                    // one tap inserts the whole pre-made group. This is how Act II teaches an action — the
                    // player adds a *meaningful unit*, not a hand-authored gene. Per-group gating (vs a single
                    // flag) lets a chapter offer only the one subsystem it's teaching.
                    for (grp in effectiveGrouping.groups) {
                        if (grp.name in insertableGroups && grp.insert.isNotEmpty() && liveGenes.none { it.group == grp.name }) {
                            button("+ ADD ${grp.name.uppercase()}", 0x2A3F5AFFL) { controller.addHeldGenes(grp.insert) }
                        }
                    }
                } else {
                    gap(); row("GENES (tap to edit. orange = blocking)")
                    info.genes.forEachIndexed { i, g -> geneButton(controller, g, i) }
                }
                button("EXPORT GENOME", 0x3A6EA5FFL) { onExport() }
            }
        }

        val d = draft ?: return
        val idx = editingIndex ?: return
        // A separate column to the LEFT of the cell-info panel, so a tall multi-clause editor doesn't stack
        // under it and run off the bottom.
        b.panel(Anchor.TopRight, newColumn = true) {
            title("EDIT GENE ${idx + 1}", 0xAACCFFFFL)
            picker("SOURCE", sourceLabel(d.source), sourceLabels, openField == Field.Source, { toggle(Field.Source) }) { i ->
                draft = d.copy(source = if (i == 0) EnergySource.Light else EnergySource.BreakBond(bonds[i - 1])); openField = null
            }
            // The gate is an AND-conjunction: render every clause (both sides are operands — a constant or a
            // live reading — each with a kind picker + a value stepper / species field), with add/remove.
            val clauses = d.condition.clauses
            clauses.forEachIndexed { ci, cl ->
                if (clauses.size > 1) row("AND ${ci + 1}", 0x99AACCFFL)
                val lhs = cl.lhs
                picker("LHS", operandKindLabels[operandKind(lhs)], operandKindLabels, openField == Field.LhsKind && openClause == ci, { toggleClause(Field.LhsKind, ci) }) { i ->
                    draft = withClauseAt(d, ci, cl.copy(lhs = operandOfKind(i, lhs))); openField = null
                }
                when (lhs) {
                    is Operand.Constant -> stepper("L VAL", lhs.value.toString()) { delta -> bumpConstantAt(ci, left = true, delta = delta) }
                    is Operand.Chem -> speciesField("L CHEM", lhs.species, atoms) { s -> draft = withClauseAt(d, ci, cl.copy(lhs = Operand.Chem(s))) }
                    is Operand.Conc -> speciesField("L CONC", lhs.species, atoms) { s -> draft = withClauseAt(d, ci, cl.copy(lhs = Operand.Conc(s))) }
                    else -> {}
                }
                picker("CMP", if (cl.cmp == Comparison.Greater) ">" else "<", listOf(">", "<"), openField == Field.Cmp && openClause == ci, { toggleClause(Field.Cmp, ci) }) { i ->
                    draft = withClauseAt(d, ci, cl.copy(cmp = if (i == 0) Comparison.Greater else Comparison.Less)); openField = null
                }
                val rhs = cl.rhs
                picker("RHS", operandKindLabels[operandKind(rhs)], operandKindLabels, openField == Field.RhsKind && openClause == ci, { toggleClause(Field.RhsKind, ci) }) { i ->
                    draft = withClauseAt(d, ci, cl.copy(rhs = operandOfKind(i, rhs))); openField = null
                }
                when (rhs) {
                    is Operand.Constant -> stepper("R VAL", rhs.value.toString()) { delta -> bumpConstantAt(ci, left = false, delta = delta) }
                    is Operand.Chem -> speciesField("R CHEM", rhs.species, atoms) { s -> draft = withClauseAt(d, ci, cl.copy(rhs = Operand.Chem(s))) }
                    is Operand.Conc -> speciesField("R CONC", rhs.species, atoms) { s -> draft = withClauseAt(d, ci, cl.copy(rhs = Operand.Conc(s))) }
                    else -> {}
                }
                if (clauses.size > 1) button("− AND ${ci + 1}", 0x804040FFL) { draft = removeClauseAt(d, ci); openField = null }
            }
            if (clauses.size < CytoTuning.GENOME_MAX_CLAUSES) button("+ AND clause", 0x3A6EA5FFL) { draft = addClauseUi(d); openField = null }
            picker("ACTION", d.action.type.name, actionLabels, openField == Field.Action, { toggle(Field.Action) }) { i ->
                val newType = ActionType.entries[i]
                // Clear the Mitosis-only flags when switching away (invariant: each ⟹ Mitosis).
                val stillMitosis = newType == ActionType.Mitosis
                draft = d.copy(action = d.action.copy(type = newType, morphogenToMother = d.action.morphogenToMother && stillMitosis, divideAcross = d.action.divideAcross && stillMitosis, rejectMother = d.action.rejectMother && stillMitosis)); openField = null
            }
            when (d.action.type) {
                ActionType.Import, ActionType.Export, ActionType.Convert, ActionType.Retain ->
                    speciesField("OPERAND", d.action.a, atoms) { s -> draft = d.copy(action = d.action.copy(a = s)) }
                ActionType.FormBond -> {
                    // Bond two molecules end-to-end. EXACT species by default — each operand names the whole
                    // reactant (built atom-by-atom); the MATCH toggle opts into a WILDCARD (left = any molecule
                    // ENDING with the operand, right = any STARTING with it). MORPHOGENESIS.md §2026-06-18.
                    speciesField(if (d.action.aWild) "LEFT ·*" else "LEFT", d.action.a, atoms) { s -> draft = d.copy(action = d.action.copy(a = s)) }
                    button(if (d.action.aWild) "LEFT MATCH: ends-with *" else "LEFT MATCH: exact", 0x3A6EA5FFL) { draft = d.copy(action = d.action.copy(aWild = !d.action.aWild)) }
                    speciesField(if (d.action.bWild) "RIGHT *·" else "RIGHT", d.action.b, atoms) { s -> draft = d.copy(action = d.action.copy(b = s)) }
                    button(if (d.action.bWild) "RIGHT MATCH: starts-with *" else "RIGHT MATCH: exact", 0x3A6EA5FFL) { draft = d.copy(action = d.action.copy(bWild = !d.action.bWild)) }
                }
                ActionType.Mitosis -> {
                    // Optional morphogen ⇒ asymmetric division; KEEP picks which side keeps it (centred vs edge
                    // source — MORPHOGENESIS.md §Source placement). Empty morphogen ⇒ symmetric, flag cleared.
                    speciesField("MORPHOGEN", d.action.a, atoms) { s ->
                        draft = d.copy(action = d.action.copy(a = s, morphogenToMother = d.action.morphogenToMother && s.isNotEmpty()))
                    }
                    if (d.action.a.isNotEmpty()) {
                        picker("KEEP", if (d.action.morphogenToMother) "mother" else "daughter", listOf("daughter", "mother"), openField == Field.MitosisSide, { toggle(Field.MitosisSide) }) { i ->
                            draft = d.copy(action = d.action.copy(morphogenToMother = i == 1)); openField = null
                        }
                    }
                    // Oriented division: an axis-morphogen whose local ∇ (from welded neighbours) supplies the
                    // division axis. Empty ⇒ unoriented free-space placement. ALONG ∇ extends a thread, ACROSS
                    // widens a 2D sheet (MORPHOGENESIS.md §Morphogens for shape).
                    speciesField("AXIS", d.action.b, atoms) { s ->
                        draft = d.copy(action = d.action.copy(b = s, divideAcross = d.action.divideAcross && s.isNotEmpty()))
                    }
                    if (d.action.b.isNotEmpty()) {
                        picker("ORIENT", if (d.action.divideAcross) "across" else "along", listOf("along", "across"), openField == Field.MitosisAxis, { toggle(Field.MitosisAxis) }) { i ->
                            draft = d.copy(action = d.action.copy(divideAcross = i == 1)); openField = null
                        }
                    }
                    button(if (d.action.rejectMother) "SEVER: yes" else "SEVER: no", 0x3A6EA5FFL) { draft = d.copy(action = d.action.copy(rejectMother = !d.action.rejectMother)) }
                }
                else -> {}
            }
            // Efficiency gear — throughput actions (Convert/Import/Repair/Contract) use rate↔efficiency;
            // FormBond uses the cap only (potency / morphogen-spread dial); Lyse uses gear for capture
            // fraction (assimilation ratio). Mitosis is exempt (fixed biomass/4 cost).
            if (d.action.type == ActionType.Convert || d.action.type == ActionType.Import ||
                d.action.type == ActionType.Export ||
                d.action.type == ActionType.Repair || d.action.type == ActionType.FormBond ||
                d.action.type == ActionType.Contract || d.action.type == ActionType.Lyse) {
                stepper("EFF", d.efficiency.toString()) { delta ->
                    draft = d.copy(efficiency = (d.efficiency + if (delta > 0) 1 else -1).coerceIn(0, CytoTuning.EFFICIENCY_MAX_GEAR))
                }
            }
            // Functional-group tag — assign the gene to a subsystem from inside the editor (no .gene file
            // editing). Pick an existing group in this genome, clear it, or type a brand-new name. Only the
            // draft changes here; the tag commits with DONE like every other field.
            gap()
            if (capturingGroup) {
                row("NEW GROUP: ${groupBuffer}_", 0xAACCFFFFL)
                row("ENTER confirm . ESC cancel", 0x99AACCFFL)
            } else {
                val existing = controller.heldGenome()?.mapNotNull { it.group.ifBlank { null } }?.distinct() ?: emptyList()
                val opts = listOf(NO_GROUP) + existing + NEW_GROUP
                picker("GROUP", d.group.ifEmpty { NO_GROUP }, opts, openField == Field.Group, { toggle(Field.Group) }) { i ->
                    draft = when {
                        i == 0 -> d.copy(group = "")
                        i == opts.lastIndex -> { startGroupCapture(d.group); d }
                        else -> d.copy(group = existing[i - 1])
                    }
                    openField = null
                }
            }
            gap()
            actionRow(
                listOf(
                    Triple("DONE", 0x33AA33FFL) { commit(controller) },
                    Triple("CANCEL", 0x808890FFL) { reset() },
                    Triple("DUP", 0x3A6EA5FFL) { controller.duplicateHeldGene(idx) },
                    Triple("DEL", 0xCC3333FFL) { controller.deleteHeldGene(idx); reset() },
                ),
            )
        }
    }

    /**
     * The **L3 gene-detail modal** (narrow/phone layout — `apps/cyto/UI_REDESIGN.md` §3). The gene reads as a
     * sentence: **WHEN** <condition> **DO** <action> **POWERED BY** <source>, each phrase a tappable chip that
     * opens its picker. Inline binary choices (comparator, sever, orient, keep) are segmented controls — no
     * drill-down. Chip taps that need a value list (operands, action, source, morphogen, group) will open L4
     * sheets in the next step; here they lay out and read live draft state.
     */
    private fun renderGeneModal(b: UiBuilder, controller: CytoController) {
        val d = draft ?: return
        val idx = editingIndex ?: return
        b.modal(
            id = "gene-modal",
            title = "GENE ${idx + 1}${d.group.ifEmpty { "" }.let { if (it.isEmpty()) "" else " · $it" }}",
            onBack = { reset() },
            statusBar = 24f, titleBar = 56f, bottomBar = 72f, rowHeight = 48f, textSize = 16f,
            actions = listOf(
                Triple("CANCEL", 0x808890FFL) { reset() },
                Triple("DONE", 0x33AA33FFL) { commit(controller) },
            ),
        ) {
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
                    chip("OPERAND", d.action.a.uppercase().ifEmpty { "(NONE)" }) { openPick(Pick.SpeciesA) }
                ActionType.FormBond -> {
                    chip("LEFT", d.action.a.uppercase().ifEmpty { "(NONE)" }) { openPick(Pick.SpeciesA) }
                    segmented("MATCH L", listOf("EXACT", "ENDS *"), if (d.action.aWild) 1 else 0) { i -> draft = d.copy(action = d.action.copy(aWild = i == 1)) }
                    chip("RIGHT", d.action.b.uppercase().ifEmpty { "(NONE)" }) { openPick(Pick.SpeciesB) }
                    segmented("MATCH R", listOf("EXACT", "* STARTS"), if (d.action.bWild) 1 else 0) { i -> draft = d.copy(action = d.action.copy(bWild = i == 1)) }
                }
                ActionType.Mitosis -> {
                    chip("MORPHOGEN", d.action.a.uppercase().ifEmpty { "(NONE)" }) { openPick(Pick.SpeciesA) }
                    if (d.action.a.isNotEmpty())
                        segmented("KEEP", listOf("DAUGHTER", "MOTHER"), if (d.action.morphogenToMother) 1 else 0) { i -> draft = d.copy(action = d.action.copy(morphogenToMother = i == 1)) }
                    chip("AXIS", d.action.b.uppercase().ifEmpty { "(NONE)" }) { openPick(Pick.SpeciesB) }
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
            chip("", sourceLabel(d.source).uppercase()) { openPick(Pick.Source) }
            gap(12f)

            chip("GROUP", d.group.uppercase().ifEmpty { "(NONE)" }) { openPick(Pick.Group) }
        }
        renderPickerSheet(b, controller, d)
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
    private fun renderPickerSheet(b: UiBuilder, controller: CytoController, d: Gene) {
        when (pick) {
            Pick.None -> return
            Pick.Action -> b.sheet("pick", "DO WHAT?", onDismiss = ::closePick, rowHeight = 48f, textSize = 16f) {
                for ((i, t) in ActionType.entries.withIndex()) {
                    listRow(t.name, actionBlurb(t), selected = t == d.action.type) {
                        val mitosis = t == ActionType.Mitosis
                        draft = d.copy(action = d.action.copy(type = t, morphogenToMother = d.action.morphogenToMother && mitosis, divideAcross = d.action.divideAcross && mitosis, rejectMother = d.action.rejectMother && mitosis))
                        closePick()
                    }
                    if (i < ActionType.entries.lastIndex) gap(4f)
                }
            }
            Pick.Source -> b.sheet("pick", "POWERED BY?", onDismiss = ::closePick, rowHeight = 48f, textSize = 16f) {
                sourceLabels.forEachIndexed { i, label ->
                    listRow(label.uppercase(), selected = label == sourceLabel(d.source)) {
                        draft = d.copy(source = if (i == 0) EnergySource.Light else EnergySource.BreakBond(bonds[i - 1])); closePick()
                    }
                    gap(4f)
                }
            }
            Pick.Group -> b.sheet("pick", "GROUP?", onDismiss = ::closePick, rowHeight = 48f, textSize = 16f) {
                val existing = controller.heldGenome()?.mapNotNull { it.group.ifBlank { null } }?.distinct() ?: emptyList()
                listRow("(NONE)", selected = d.group.isEmpty()) { draft = d.copy(group = ""); closePick() }
                gap(4f)
                for (g in existing) {
                    listRow(g.uppercase(), selected = d.group == g) { draft = d.copy(group = g); closePick() }
                    gap(4f)
                }
                listRow("NEW GROUP...", "TYPE A NAME ON THE KEYBOARD") { startGroupCapture(d.group); closePick() }
            }
            Pick.Operand -> renderOperandSheet(b, d)
            Pick.SpeciesA -> b.sheet("pick", "MOLECULE", onDismiss = ::closePick, rowHeight = 48f, textSize = 16f) {
                speciesBuilder(d.action.a) { draft = d.copy(action = d.action.copy(a = it)) }
            }
            Pick.SpeciesB -> b.sheet("pick", "MOLECULE", onDismiss = ::closePick, rowHeight = 48f, textSize = 16f) {
                speciesBuilder(d.action.b) { draft = d.copy(action = d.action.copy(b = it)) }
            }
            Pick.Eff -> b.sheet("pick", "EFFICIENCY GEAR", onDismiss = ::closePick, heightFraction = 0.4f, rowHeight = 48f, textSize = 16f) {
                numberField(d.efficiency, 0, CytoTuning.EFFICIENCY_MAX_GEAR) { draft = d.copy(efficiency = it) }
            }
        }
    }

    /** The operand picker: a kind list (Const/Chem/Conc/BIO/Touch/Nbrs) then the value editor for whichever
     *  kind is selected — a number for Const, a species builder for Chem/Conc, nothing for the live readings. */
    private fun renderOperandSheet(b: UiBuilder, d: Gene) {
        val ci = pickClause
        val cl = d.condition.clauses.getOrNull(ci) ?: run { closePick(); return }
        val op = if (pickSide == 0) cl.lhs else cl.rhs
        fun setOp(newOp: Operand) { draft = withClauseAt(d, ci, if (pickSide == 0) cl.copy(lhs = newOp) else cl.copy(rhs = newOp)) }
        b.sheet("pick", if (pickSide == 0) "LEFT SIDE" else "RIGHT SIDE", onDismiss = ::closePick, rowHeight = 48f, textSize = 16f) {
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
        chip("", current.uppercase().ifEmpty { "(NONE)" }, 0x2A3550FFL) {}
        gap(6f)
        actionRow(atoms.map { a -> Triple<String, Long, () -> Unit>("+${a.uppercase()}", 0x32503CFFL) { onChange(current + a) } } +
            Triple<String, Long, () -> Unit>("<", 0x5A3A3AFFL) { onChange(current.dropLast(1)) })
    }

    private fun closePick() { pick = Pick.None; pickClause = -1; pickSide = 0 }

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

    /** A compact operand label for an L3 clause chip (see [renderGeneModal]). */
    private fun operandLabel(op: Operand): String = when (op) {
        is Operand.Constant -> op.value.toString()
        is Operand.Chem -> "CHEM ${op.species.uppercase()}"
        is Operand.Conc -> "CONC ${op.species.uppercase()}"
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
        button(g.spans.map { it.text to (if (it.blocking) 0xC8963CFFL else null) }, bg) { open(controller, i) }
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
        openField = null
    }

    private fun toggle(f: Field) { openField = if (openField == f) null else f }

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

    /** Nudge the [Operand.Constant] value on one side of clause [ci] (no-op if that side isn't a constant). */
    private fun bumpConstantAt(ci: Int, left: Boolean, delta: Int) {
        val d = draft ?: return
        val c = d.condition.clauses[ci]
        val op = if (left) c.lhs else c.rhs
        if (op !is Operand.Constant) return
        val next = Operand.Constant((op.value + delta - (op.value%delta)).coerceAtLeast(0))
        draft = withClauseAt(d, ci, if (left) c.copy(lhs = next) else c.copy(rhs = next))
    }

    /** Open/close clause [ci]'s [f] dropdown (the AND-clause pickers are keyed by clause index). */
    private fun toggleClause(f: Field, ci: Int) {
        if (openField == f && openClause == ci) openField = null else { openField = f; openClause = ci }
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
        openField = null
        metabExpanded = false
        capturingGroup = false
        groupBuffer.setLength(0)
        pick = Pick.None
        pickClause = -1
        pickSide = 0
    }

    private fun sourceLabel(s: EnergySource): String = when (s) {
        EnergySource.Light -> "Light"
        is EnergySource.BreakBond -> "Brk ${s.bond}"
    }

    private companion object {
        const val NO_GROUP = "(none)"
        const val NEW_GROUP = "New group..."
    }
}

/** A species operand built **atom-by-atom**: a label + the molecule so far, then a `+<atom>` button per
 *  alphabet atom and a `<` backspace. Appends/trims one atom per tap (immediate-mode: the next frame reads
 *  the updated draft), so any-length molecule (`abb`, `abcb`, …) is reachable — unlike the old fixed
 *  monomer+dimer dropdown. Empty shows `(none)`; an empty species just makes the gene a no-op until built up. */
private fun PanelBuilder.speciesField(label: String, current: String, atoms: List<String>, onChange: (String) -> Unit) {
    row("$label  ${current.ifEmpty { "(none)" }}", 0x9A9A9AFFL)
    val buttons = atoms.map { a ->
        Triple<String, Long, () -> Unit>("+$a", 0x32503CFFL) { onChange(current + a) }
    } + Triple<String, Long, () -> Unit>("<", 0x5A3A3AFFL) { onChange(current.dropLast(1)) }
    actionRow(buttons)
}
