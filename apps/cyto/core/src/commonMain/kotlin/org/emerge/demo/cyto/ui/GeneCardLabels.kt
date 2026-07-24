package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.Operand

/**
 * **The gene card's word list** — every fixed label a player can read or tap on a gene, in one place.
 *
 * It exists because campaign copy *names* these words ("change the action from (NOTHING) to (CONVERT)")
 * as bare strings. Renaming a label used to silently make that copy lie, with every test still green;
 * `CampaignCopyTokensTest` now checks the copy against [VOCABULARY], so the rename fails loudly instead.
 *
 * Only **fixed** words live here. Anything the label composes at runtime — a molecule name, a group tag, a
 * number — is dynamic and is skipped by that test rather than enumerated here.
 *
 * The UI font is all-caps, so everything is compared upper-cased: [VOCABULARY] holds upper-case entries and
 * membership tests should upper-case first.
 */
object GeneCardLabels {

    /** How an action reads in the pickers and the inline action menu. [ActionType.BreakBond] shows as
     *  **BREAK** (so the digestion row mirrors the synthesis row's **BOND**); [ActionType.None] — the
     *  authoring blank — shows as **NOTHING**, an invitation to pick a real action rather than `None`. */
    fun action(t: ActionType): String = when (t) {
        ActionType.BreakBond -> "BREAK"
        ActionType.None -> "NOTHING"
        else -> t.name
    }

    /** The verb the *read card* uses, which is not always the picker's word: [ActionType.Divide] picks as
     *  MITOSIS but reads as DIVIDE. */
    fun actionVerb(t: ActionType): String = when (t) {
        ActionType.Divide -> "DIVIDE"
        else -> action(t)
    }

    /** The energy-source *type* token. Light says USE LIGHT (never a bare LIGHT — the copy has been wrong
     *  about this before); a synthesis gene's reaction is a separate, dynamic token beside it. */
    fun sourceType(s: EnergySource): String = when (s) {
        EnergySource.Light -> "USE LIGHT"
        is EnergySource.FormBond -> "BOND"
    }

    /** The operand token, for the kinds whose label is fixed. [Operand.Chem] and [Operand.Constant] compose
     *  a molecule name / a number and so have no fixed word beyond the `CHEM` prefix. */
    fun operand(op: Operand): String? = when (op) {
        Operand.Biomass -> "BIO"
        Operand.Touching -> "TOUCH"
        Operand.Neighbours -> "NBRS"
        is Operand.Chem -> null
        is Operand.Constant -> null
    }

    /** The comparator toggle. */
    fun comparison(c: Comparison): String = if (c == Comparison.Greater) ">" else "<"

    /** The four operand kinds as the L4 operand picker lists them. */
    val OPERAND_KINDS: List<String> = listOf("Const", "Chem", "BIO", "Touch", "Nbrs")

    /**
     * Every fixed on-card / in-picker word, upper-cased.
     *
     * Derived from the label functions above wherever it can be (so adding an [ActionType] extends the
     * vocabulary for free) and listed literally only for the words the card writes inline.
     */
    val VOCABULARY: Set<String> = buildSet {
        for (t in ActionType.entries) { add(action(t)); add(actionVerb(t)) }
        add(sourceType(EnergySource.Light))
        add(sourceType(EnergySource.FormBond("r", "g")))
        for (op in listOf(Operand.Biomass, Operand.Touching, Operand.Neighbours)) operand(op)?.let { add(it) }
        addAll(OPERAND_KINDS)
        for (c in Comparison.entries) add(comparison(c))
        // Condition row.
        addAll(listOf("ALWAYS", "WHEN", "AND"))
        // The empty-molecule reading (SpeciesNames.name("")), stripped of its own parens by the extractor.
        add("NONE")
        // Divide modifier lines, and the grey connective words the card writes between tokens.
        addAll(listOf(
            "ALONG", "ACROSS", "NO GRADIENT", "GRADIENT",
            "RETAINING", "GIVING", "NOTHING", "CELL 1", "CELL 2",
            "SEVERING CELL 2 FREE", "AND STICK", "TO MASS", "TO", "WELDS",
        ))
    }.mapTo(LinkedHashSet()) { it.uppercase() }
}
