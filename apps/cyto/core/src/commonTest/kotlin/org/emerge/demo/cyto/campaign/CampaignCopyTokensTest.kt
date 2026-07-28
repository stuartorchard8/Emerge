package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.host.CampaignContent
import org.emerge.demo.cyto.sim.SpeciesNames
import org.emerge.demo.cyto.ui.GeneCardLabels
import kotlin.test.Test
import kotlin.test.fail

/**
 * **The coach's copy may not name a UI word that isn't on screen.**
 *
 * Campaign steps instruct by quoting labels — "change the action from (NOTHING) to (CONVERT)", "tap
 * (ALWAYS)", "flip the (>) to (<)". Those are bare strings in prose: renaming a gene-card label used to
 * leave the copy telling the player to tap something that no longer exists, with every test still green.
 * `CampaignContent.validateGlyphs` proves the *characters* render; this proves the *words* are real.
 *
 * Every `(TOKEN)` in a chapter's title/blurb or a step's text/detail/hint/gate description must be in
 * [GeneCardLabels.VOCABULARY]. Anything the UI composes at runtime is skipped **by pattern**, never by an
 * exception list, so a new chapter can't quietly opt out:
 *
 *  - a molecule name or a genome alias — the card writes whatever [SpeciesNames] returns;
 *  - a reaction pair (`R+G`) or an either/or gloss (`R/G`);
 *  - a bare number, which is a value the player types, not a label;
 *  - lower-case text, which is ordinary parenthetical prose rather than a quoted UI word.
 */
class CampaignCopyTokensTest {

    private val token = Regex("\\(([^()]{1,32})\\)")
    private val number = Regex("^[0-9]+$")

    /** Every molecule display name reachable from the campaign — built-ins plus each genome's aliases. */
    private val speciesWords: Set<String> = buildSet {
        for (n in SpeciesNames.ATOMS.values) add(n.uppercase())
        for (n in SpeciesNames.DUOMERS.values) add(n.uppercase())
        for (k in SpeciesNames.ATOMS.keys) add(k.uppercase())
        for (k in SpeciesNames.DUOMERS.keys) add(k.uppercase())
        for (ch in CampaignContent.PLAYABLE_CHAPTERS) {
            for (a in ch.scenario.aliases.values) add(a.uppercase())
            for (s in ch.scenario.aliases.keys) add(s.uppercase())
        }
    }

    /** True when the token is something the UI composes at runtime rather than a fixed label. */
    private fun isDynamic(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return true
        if (t != t.uppercase()) return true              // ordinary prose in parens, not a quoted UI word
        if (number.matches(t)) return true               // a value the player types
        if ('+' in t || '/' in t) return true            // a reaction pair, or an either/or gloss
        return t.split(' ', '+', '/').all { it.isEmpty() || it in speciesWords }
    }

    @Test fun everyQuotedUiTokenIsARealLabel() {
        val stale = ArrayList<String>()
        for (ch in CampaignContent.PLAYABLE_CHAPTERS) {
            fun scan(where: String, s: String?) {
                s ?: return
                for (m in token.findAll(s)) {
                    val raw = m.groupValues[1]
                    if (isDynamic(raw)) continue
                    if (raw.trim().uppercase() !in GeneCardLabels.VOCABULARY) {
                        stale += "${ch.id} $where: ($raw) is not a gene-card label"
                    }
                }
            }
            scan("title", ch.title)
            scan("blurb", ch.blurb)
            for ((i, st) in ch.steps.withIndex()) {
                scan("step $i text", st.text)
                scan("step $i detail", st.detail)
                scan("step $i hint", st.spotlight?.hint)
                (st.gate as? Gate.World)?.let { scan("step $i gate", it.desc) }
                (st.gate as? Gate.Did)?.let { scan("step $i gate", it.desc) }
            }
        }
        if (stale.isNotEmpty()) fail("Campaign copy names UI labels that don't exist:\n" + stale.joinToString("\n"))
    }

    /** The vocabulary is only useful if it's actually populated from the label functions. */
    @Test fun vocabularyCoversTheWordsTheCopyLeansOn() {
        for (w in listOf("NOTHING", "CONVERT", "DIVIDE", "ALWAYS", "BIO", "USE LIGHT", "BOND", ">", "<")) {
            if (w !in GeneCardLabels.VOCABULARY) fail("$w missing from GeneCardLabels.VOCABULARY")
        }
    }
}
