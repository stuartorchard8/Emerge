package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.sim.Gene

/**
 * Display style for a **functional group** (CAMPAIGN_PLAN.md §10): a [name] that matches genes carrying that
 * [Gene.group] tag. [insert] is the pre-tagged genes the gene editor's "+ ADD" affordance drops in for this
 * group (empty = not offered as an insert). Membership is by the gene's own tag, never by matching gene
 * contents — so editing, moving, or re-tagging a gene keeps grouping correct. Header colour is not carried
 * here: it is derived from the group's name ([GenomeGrouping.autoColor]) so a group looks the same wherever
 * it appears (campaign or free play), with no shared colour registry to maintain.
 */
class GeneGroup(val name: String, val insert: List<Gene> = emptyList())

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
            byTag[grp.name]?.let { out.add(Section(grp.name, autoColor(grp.name), it)) }
        }
        // Tagged genes whose group isn't in the registry (free play, or after a re-tag to a new name): keep
        // the name, colour it from the name like any other group.
        for ((tag, items) in byTag) {
            if (tag.isNotEmpty() && tag !in registered) out.add(Section(tag, autoColor(tag), items))
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
        /** Neutral colour for the untagged "Other" bucket. */
        const val OTHER_COLOR = 0x6B7280FFL

        /** A distinct header palette (RGBA); a group's colour is picked from it by name, so the same name
         *  always draws the same colour without any shared registry. */
        private val PALETTE = longArrayOf(
            0x3E9E5AFFL, // green
            0xC77DD0FFL, // purple
            0xD98C40FFL, // orange
            0xD0504AFFL, // red
            0x4A90D0FFL, // blue
            0xD0C24AFFL, // yellow
            0x4AD0C2FFL, // teal
            0xB0704AFFL, // brown
        )

        /** Deterministic, platform-stable colour for a group [name] (FNV-1a hash → [PALETTE]). Pure function
         *  of the name so a group looks identical across genomes, saves, and game modes. */
        fun autoColor(name: String): Long {
            var h = 0x811C9DC5U
            for (c in name) { h = h xor c.code.toUInt(); h *= 0x01000193U }
            return PALETTE[(h % PALETTE.size.toUInt()).toInt()]
        }
    }
}
