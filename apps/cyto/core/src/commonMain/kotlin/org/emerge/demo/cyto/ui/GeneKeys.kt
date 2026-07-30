package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.sim.ActionType

/**
 * **Stable identities for the words on a gene card** — what a widget *is*, as opposed to [GeneCardLabels],
 * which is what it *says*.
 *
 * The campaign coach has to point at slots ("the condition on the GROW gene") that keep their meaning while
 * their text moves under them, and a label cannot express that:
 *
 *  - the text changes as the state behind it changes — the condition slot reads `ALWAYS` until it reads
 *    `WHEN BIO < 3000`, which is precisely the edit a step is asking for;
 *  - the same word repeats down a genome, so naming one means counting matches, and the count shifts as the
 *    player authors more genes;
 *  - a label is a display decision, and display decisions get revised (CONVERT reads as GROW now).
 *
 * A key names the gene by **what it does** and the slot by **what it is for**, so it survives all three. The
 * gene's content is deliberately *not* part of it: the chemical is the player's own pick and the campaign
 * could not write it down in advance, and a content-derived key would change at the exact moment the step's
 * edit lands — losing the lock while the player is mid-edit, which is when it is wanted most.
 *
 * [ordinal] disambiguates two genes with the same action, 1-based in genome order.
 */
object GeneKeys {

    /** The slots on a gene card that can be pointed at or driven. */
    enum class Part(val slug: String) {
        /** The condition as a whole: the `ALWAYS` toggle while there is none, else the first clause's left
         *  operand. One key for "where the condition lives", either side of authoring it. */
        Condition("cond"),
        ConditionLhs("lhs"),
        Comparator("cmp"),
        ConditionRhs("rhs"),

        /** The energy row: the source type (`USE LIGHT` / `BOND`), and — synthesis only — its reaction. */
        Source("src"),
        Reaction("rx"),

        /** The action row: the verb, the chemical it names, and the efficiency gear. */
        Action("act"),
        Operand("operand"),
        Efficiency("eff"),

        /** DIVIDE's modifier rows. */
        Orient("orient"),
        Gradient("grad"),
        Morphogen("morph"),
        Sever("sever"),
    }

    /** The prefix every part of one gene shares. */
    fun gene(action: ActionType, ordinal: Int = 1): String = "gene:${action.name.uppercase()}:$ordinal"

    /** A slot on a gene. [clause] indexes the clause for the per-clause parts (0-based). */
    fun part(action: ActionType, part: Part, ordinal: Int = 1, clause: Int = 0): String {
        val suffix = when (part) {
            Part.ConditionLhs, Part.Comparator, Part.ConditionRhs -> "${part.slug}$clause"
            else -> part.slug
        }
        return "${gene(action, ordinal)}:$suffix"
    }
}
