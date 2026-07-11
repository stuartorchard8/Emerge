package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.sim.Gene

/**
 * A named, coloured **functional subsystem** of a genome (CAMPAIGN_PLAN.md §10): an order-independent set of
 * member genes, matched by structural equality. Purely an authoring/display label — it has **no effect on how
 * genes run**. Grouping never reorders or edits the genome (gene order is behaviourally significant — see the
 * `reference_gene_order_matters` note), so assigning/showing a group is a guaranteed behavioural no-op.
 */
class GeneGroup(val name: String, val color: Long, val members: List<Gene>)

/**
 * A grouping overlay for one authored genome: the [groups] its genes fall into. The gene editor buckets a
 * *live* genome against this by gene equality and collates by group for display — so the impenetrable flat
 * list collapses into a few understandable subsystems (the campaign shows two grow genes as one "Grow"
 * label). Display order differs from storage/sim order, which is safe precisely because grouping is a label,
 * not a reorder. Genes matching no group render in an implicit "Other" section; empty groups are omitted.
 */
class GenomeGrouping(val groups: List<GeneGroup>) {

    /** Bucket [genome] into display [Section]s: each non-empty group in declared order (holding its matched
     *  live genes, in live order), then an "Other" section for the rest. Every gene keeps its live [Item.index]
     *  so the editor opens the correct gene despite the display reordering. A gene matching several groups
     *  joins the first. */
    fun sections(genome: List<Gene>): List<Section> {
        val assigned = BooleanArray(genome.size)
        val out = ArrayList<Section>()
        for (grp in groups) {
            val items = ArrayList<Item>()
            genome.forEachIndexed { i, g ->
                if (!assigned[i] && grp.members.contains(g)) { items.add(Item(i, g)); assigned[i] = true }
            }
            if (items.isNotEmpty()) out.add(Section(grp.name, grp.color, items))
        }
        val other = ArrayList<Item>()
        genome.forEachIndexed { i, g -> if (!assigned[i]) other.add(Item(i, g)) }
        if (other.isNotEmpty()) out.add(Section(null, OTHER_COLOR, other))
        return out
    }

    /** One display section: a named group, or the unnamed "Other" bucket ([name] == null). */
    class Section(val name: String?, val color: Long, val items: List<Item>) {
        val isOther: Boolean get() = name == null
    }

    /** A member gene plus its index in the live genome (needed to address it for editing). */
    class Item(val index: Int, val gene: Gene)

    companion object {
        /** Neutral colour for the "Other" bucket (unmatched / evolved genes). */
        const val OTHER_COLOR = 0x6B7280FFL
    }
}
