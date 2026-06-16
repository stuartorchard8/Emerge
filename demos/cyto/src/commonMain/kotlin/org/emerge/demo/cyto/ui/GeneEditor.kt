package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.ConditionType
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.Gene
import org.emerge.sim.core.EntityId
import org.emerge.render.torus.ui.Anchor
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
    private enum class Field { Source, CondType, CondSpecies, Cmp, Action, OperandA, OperandB }

    private var editingId: EntityId? = null
    private var editingIndex: Int? = null
    private var draft: Gene? = null
    private var openField: Field? = null

    // Option lists, derived from the seeded alphabet so they track the k-atom cap.
    private val atoms: List<String> = CytoSeed.SEED_MONOMERS
    private val bonds: List<String> = atoms.flatMap { x -> atoms.map { y -> x + y } }
    private val species: List<String> = atoms + bonds
    private val sourceLabels: List<String> = listOf("Light") + bonds.map { "Brk $it" }
    private val condTypeLabels: List<String> = ConditionType.entries.map { it.name }
    private val actionLabels: List<String> = ActionType.entries.map { it.name }

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
            picker("COND", d.condition.type.name, condTypeLabels, openField == Field.CondType, { toggle(Field.CondType) }) { i ->
                draft = d.copy(condition = d.condition.copy(type = ConditionType.entries[i])); openField = null
            }
            if (d.condition.type == ConditionType.ChemQty) {
                picker("CHEM", d.condition.species.ifEmpty { "-" }, species, openField == Field.CondSpecies, { toggle(Field.CondSpecies) }) { i ->
                    draft = d.copy(condition = d.condition.copy(species = species[i])); openField = null
                }
            }
            picker("CMP", if (d.condition.cmp == Comparison.Greater) ">" else "<", listOf(">", "<"), openField == Field.Cmp, { toggle(Field.Cmp) }) { i ->
                draft = d.copy(condition = d.condition.copy(cmp = if (i == 0) Comparison.Greater else Comparison.Less)); openField = null
            }
            stepper("THRESH", d.condition.threshold.toString()) { delta -> bumpThreshold(delta) }
            picker("ACTION", d.action.type.name, actionLabels, openField == Field.Action, { toggle(Field.Action) }) { i ->
                draft = d.copy(action = d.action.copy(type = ActionType.entries[i])); openField = null
            }
            when (d.action.type) {
                ActionType.Import, ActionType.Convert ->
                    picker("OPERAND", d.action.a.ifEmpty { "-" }, species, openField == Field.OperandA, { toggle(Field.OperandA) }) { i ->
                        draft = d.copy(action = d.action.copy(a = species[i])); openField = null
                    }
                ActionType.FormBond -> {
                    // FormBond uses only the first atom of each operand; show/edit just that atom.
                    picker("ATOM A", d.action.a.take(1).ifEmpty { "-" }, atoms, openField == Field.OperandA, { toggle(Field.OperandA) }) { i ->
                        draft = d.copy(action = d.action.copy(a = atoms[i])); openField = null
                    }
                    picker("ATOM B", d.action.b.take(1).ifEmpty { "-" }, atoms, openField == Field.OperandB, { toggle(Field.OperandB) }) { i ->
                        draft = d.copy(action = d.action.copy(b = atoms[i])); openField = null
                    }
                }
                else -> {}
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

    private fun bumpThreshold(delta: Int) {
        val d = draft ?: return
        draft = d.copy(condition = d.condition.copy(threshold = (d.condition.threshold + delta).coerceAtLeast(0)))
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
