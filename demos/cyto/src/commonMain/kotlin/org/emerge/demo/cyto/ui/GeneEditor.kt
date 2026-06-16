package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.Gene
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
    private enum class Field { Source, LhsKind, Cmp, RhsKind, Action }

    private var editingId: EntityId? = null
    private var editingIndex: Int? = null
    private var draft: Gene? = null
    private var openField: Field? = null

    // Option lists, derived from the seeded alphabet. Species operands are built atom-by-atom (see
    // [speciesField]) rather than picked from a fixed list, so any-length molecules (abb, abcb, …) are
    // reachable — the old monomer+dimer dropdown couldn't express them.
    private val atoms: List<String> = CytoSeed.SEED_MONOMERS
    private val bonds: List<String> = atoms.flatMap { x -> atoms.map { y -> x + y } }
    private val sourceLabels: List<String> = listOf("Light") + bonds.map { "Brk $it" }
    private val actionLabels: List<String> = ActionType.entries.map { it.name }

    /** The four operand kinds, in picker order: a constant value, a cytoplasm count, total biomass, or
     *  the contact count. */
    private val operandKindLabels: List<String> = listOf("Const", "Chem", "BIO", "Touch")

    /** Called if the editor target vanishes (cell died / deselected) — discard any in-progress edit. */
    fun closeDropdown() { openField = null }

    fun render(b: UiBuilder, controller: CytoController) {
        val info = controller.heldCellInfo()
        if (info == null) { reset(); return }
        if (editingId != null && editingId != controller.lastHeldId) reset()   // grabbed a different cell

        b.panel(Anchor.TopRight) {
            title("CELL ${info.id}")
            keyValue("TYPE", info.type)
            keyValue("SIZE", info.radius)
            keyValue("BIOMASS", info.totalBiomass.toString())
            keyValue("LIGHT", info.light)
            metabolismTable(info)
            if (info.genes.isNotEmpty()) {
                gap(); row("GENES (tap to edit)")
                info.genes.forEachIndexed { i, desc ->
                    button(desc, if (editingIndex == i) 0x4488CCFFL else 0x2E5E2EFFL) { open(controller, i) }
                }
            }
        }

        val d = draft ?: return
        val idx = editingIndex ?: return
        b.panel(Anchor.TopRight) {
            title("EDIT GENE ${idx + 1}", 0xAACCFFFFL)
            picker("SOURCE", sourceLabel(d.source), sourceLabels, openField == Field.Source, { toggle(Field.Source) }) { i ->
                draft = d.copy(source = if (i == 0) EnergySource.Light else EnergySource.BreakBond(bonds[i - 1])); openField = null
            }
            // Both sides of the gate are operands (a constant or a live reading), so each gets a kind
            // picker plus, when it's a Const/Chem, a value stepper / species picker.
            val lhs = d.condition.lhs
            picker("LHS", operandKindLabels[operandKind(lhs)], operandKindLabels, openField == Field.LhsKind, { toggle(Field.LhsKind) }) { i ->
                draft = d.copy(condition = d.condition.copy(lhs = operandOfKind(i, lhs))); openField = null
            }
            when (lhs) {
                is Operand.Constant -> stepper("L VAL", lhs.value.toString()) { delta -> bumpConstant(left = true, delta = delta) }
                is Operand.Chem -> speciesField("L CHEM", lhs.species, atoms) { s ->
                    draft = d.copy(condition = d.condition.copy(lhs = Operand.Chem(s)))
                }
                else -> {}
            }
            picker("CMP", if (d.condition.cmp == Comparison.Greater) ">" else "<", listOf(">", "<"), openField == Field.Cmp, { toggle(Field.Cmp) }) { i ->
                draft = d.copy(condition = d.condition.copy(cmp = if (i == 0) Comparison.Greater else Comparison.Less)); openField = null
            }
            val rhs = d.condition.rhs
            picker("RHS", operandKindLabels[operandKind(rhs)], operandKindLabels, openField == Field.RhsKind, { toggle(Field.RhsKind) }) { i ->
                draft = d.copy(condition = d.condition.copy(rhs = operandOfKind(i, rhs))); openField = null
            }
            when (rhs) {
                is Operand.Constant -> stepper("R VAL", rhs.value.toString()) { delta -> bumpConstant(left = false, delta = delta) }
                is Operand.Chem -> speciesField("R CHEM", rhs.species, atoms) { s ->
                    draft = d.copy(condition = d.condition.copy(rhs = Operand.Chem(s)))
                }
                else -> {}
            }
            picker("ACTION", d.action.type.name, actionLabels, openField == Field.Action, { toggle(Field.Action) }) { i ->
                draft = d.copy(action = d.action.copy(type = ActionType.entries[i])); openField = null
            }
            when (d.action.type) {
                ActionType.Import, ActionType.Convert ->
                    speciesField("OPERAND", d.action.a, atoms) { s -> draft = d.copy(action = d.action.copy(a = s)) }
                ActionType.FormBond -> {
                    // Operands are a suffix/prefix match (built atom-by-atom): bond a molecule ENDING WITH the
                    // first operand to one STARTING WITH the second. Single atom = the old end-atom/start-atom.
                    speciesField("END WITH", d.action.a, atoms) { s -> draft = d.copy(action = d.action.copy(a = s)) }
                    speciesField("START WITH", d.action.b, atoms) { s -> draft = d.copy(action = d.action.copy(b = s)) }
                }
                else -> {}
            }
            // Efficiency gear — only the throughput actions use it (FormBond is lossless, Mitosis fixed).
            if (d.action.type == ActionType.Convert || d.action.type == ActionType.Import || d.action.type == ActionType.Repair) {
                stepper("EFF", d.efficiency.toString()) { delta ->
                    draft = d.copy(efficiency = (d.efficiency + if (delta > 0) 1 else -1).coerceIn(0, CytoTuning.EFFICIENCY_MAX_GEAR))
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

    private fun open(controller: CytoController, index: Int) {
        val gene = controller.heldGenome()?.getOrNull(index) ?: return
        editingId = controller.lastHeldId
        editingIndex = index
        draft = gene
        openField = null
    }

    private fun toggle(f: Field) { openField = if (openField == f) null else f }

    /** Nudge the [Operand.Constant] value on one side of the gate (no-op if that side isn't a constant). */
    private fun bumpConstant(left: Boolean, delta: Int) {
        val d = draft ?: return
        val c = d.condition
        val op = if (left) c.lhs else c.rhs
        if (op !is Operand.Constant) return
        val next = Operand.Constant((op.value + delta).coerceAtLeast(0))
        draft = d.copy(condition = if (left) c.copy(lhs = next) else c.copy(rhs = next))
    }

    /** Picker index (into [operandKindLabels]) for an operand's kind. */
    private fun operandKind(op: Operand): Int = when (op) {
        is Operand.Constant -> 0
        is Operand.Chem -> 1
        Operand.Biomass -> 2
        Operand.Touching -> 3
    }

    /** Build an operand of the picked [kind], carrying [prev]'s value/species when the kind is unchanged
     *  so switching kinds and back doesn't silently reset it. */
    private fun operandOfKind(kind: Int, prev: Operand): Operand = when (kind) {
        0 -> Operand.Constant((prev as? Operand.Constant)?.value ?: 0)
        1 -> Operand.Chem((prev as? Operand.Chem)?.species ?: atoms.first())
        2 -> Operand.Biomass
        else -> Operand.Touching
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
    }

    private fun sourceLabel(s: EnergySource): String = when (s) {
        EnergySource.Light -> "Light"
        is EnergySource.BreakBond -> "Brk ${s.bond}"
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
