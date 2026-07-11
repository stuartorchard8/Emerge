package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.sim.Gene

/**
 * Display style for a **functional group** (CAMPAIGN_PLAN.md §10): a [name] that matches genes carrying that
 * [Gene.group] tag, plus a [color] for its header. [insert] is the pre-tagged genes the gene editor's "+ ADD"
 * affordance drops in for this group (empty = not offered as an insert). Membership is by the gene's own tag,
 * never by matching gene contents — so editing, moving, or re-tagging a gene keeps grouping correct.
 */
class GeneGroup(val name: String, val color: Long, val insert: List<Gene> = emptyList())

/**
 * A grouping overlay for a genome: the ordered [groups] whose names/colours style the display. The gene
 * editor buckets a live genome by each gene's [Gene.group] tag and renders the impenetrable flat list as a
 * few named subsystems (the campaign shows two grow genes as one "Grow" label). Because the tag lives on the
 * gene, grouping survives edits and division — no fuzzy or structural matching anywhere.
 */
class GenomeGrouping(val groups: List<GeneGroup>) {

    /** Bucket [genome] into display [Section]s by gene tag: each registered [groups] entry that has ≥1 tagged
     *  gene (in declared order), then any tag not in the registry (discovery order), then the untagged genes
     *  as an unnamed "Other" section. Every gene keeps its live [Item.index] so the editor addresses the right
     *  gene despite the display grouping. */
    fun sections(genome: List<Gene>): List<Section> {
        // Preserve first-seen order of tags, and the live index of every gene under each tag.
        val byTag = LinkedHashMap<String, MutableList<Item>>()
        genome.forEachIndexed { i, g -> byTag.getOrPut(g.group) { ArrayList() }.add(Item(i, g)) }
        val out = ArrayList<Section>()
        val registered = HashSet<String>()
        for (grp in groups) {
            registered.add(grp.name)
            byTag[grp.name]?.let { out.add(Section(grp.name, grp.color, it)) }
        }
        // Tagged genes whose group has no registered style (e.g. after a re-tag to a new name): keep the name,
        // give it a neutral colour.
        for ((tag, items) in byTag) {
            if (tag.isNotEmpty() && tag !in registered) out.add(Section(tag, OTHER_COLOR, items))
        }
        byTag[""]?.let { out.add(Section(null, OTHER_COLOR, it)) }
        return out
    }

    /** One display section: a named group, or the unnamed "Other" bucket ([name] == null). */
    class Section(val name: String?, val color: Long, val items: List<Item>) {
        val isOther: Boolean get() = name == null
    }

    /** A member gene plus its index in the live genome (needed to address it for editing). */
    class Item(val index: Int, val gene: Gene)

    companion object {
        /** Neutral colour for the untagged "Other" bucket and unregistered tags. */
        const val OTHER_COLOR = 0x6B7280FFL
    }
}
