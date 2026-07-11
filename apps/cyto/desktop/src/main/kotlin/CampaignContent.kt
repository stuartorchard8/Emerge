package org.emerge.desktop

import org.emerge.demo.cyto.campaign.Chapter
import org.emerge.demo.cyto.campaign.Control
import org.emerge.demo.cyto.campaign.ControlMask
import org.emerge.demo.cyto.campaign.Gate
import org.emerge.demo.cyto.campaign.PlayerAction
import org.emerge.demo.cyto.campaign.Spotlight
import org.emerge.demo.cyto.campaign.Step
import org.emerge.demo.cyto.campaign.WorldRun
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.AUTOTROPH_GROW_ONLY_GENES
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.FounderSpec
import org.emerge.render.torus.ui.UiTextRenderer

/**
 * The authored campaign chapters (see `apps/cyto/CAMPAIGN_PLAN.md`). Content-only; the runtime lives in
 * [org.emerge.demo.cyto.campaign.CampaignDirector]. Act I is authored in full; later acts land in
 * subsequent phases. Copy voice: clear + engaging, one idea per beat.
 *
 * Copy is restricted to the bitmap font's glyph set (uppercase-folded A-Z 0-9 and simple punctuation
 * incl. the apostrophe + arrow); avoid em-dashes and decorative symbols (they render as `?`).
 */
object CampaignContent {

    /** Camera + cell selection + the info panel - enough to explore, nothing to overwhelm. */
    private val LOOK = ControlMask.of(Control.Camera, Control.Select, Control.GeneEditor, Control.Menu)

    /** LOOK plus the overlay + speed controls - for watching the world run. */
    private val WATCH = ControlMask.of(
        Control.Camera, Control.Select, Control.GeneEditor, Control.Overlays, Control.Speed, Control.Menu,
    )

    /** The campaign's substrate: a single autotroph whose reproduction gene has been removed, so it grows to
     *  full size and then holds there, stationary and self-repairing but unable to spread (see
     *  [AUTOTROPH_GROW_ONLY_GENES]). The player watches this calm, easy-to-reason-about organism, reads its two
     *  grow genes, then *adds* reproduction to bring it to life. Frames the world as a substrate to author,
     *  not a busy ecosystem to catch up on. */
    private val GROW_ONLY = CytoScenario.DEFAULT.copy(
        name = "Campaign",
        founders = listOf(FounderSpec(CellType.Collector, 1, genome = AUTOTROPH_GROW_ONLY_GENES)),
    )

    val CHAPTERS: List<Chapter> = listOf(
        chapter1FirstContact(),
        chapter2LetThereBeLight(),
        chapter3AnatomyOfAGene(),
    )

    val ORDER: List<String> = CHAPTERS.map { it.id }

    /** Distinct characters in the chapters' player-facing copy that the bitmap font can't render (would
     *  show as `?`). Empty = all copy is safe. The harness runs this as a guard so a bad glyph is caught
     *  headlessly rather than only spotted in the GL window. */
    fun validateGlyphs(): List<Char> {
        val bad = LinkedHashSet<Char>()
        fun scan(s: String?) { s?.forEach { if (it != '\n' && !UiTextRenderer.supports(it)) bad.add(it) } }
        for (ch in CHAPTERS) {
            scan(ch.title); scan(ch.blurb)
            for (st in ch.steps) {
                scan(st.text); scan(st.detail); scan(st.spotlight?.hint)
                (st.gate as? Gate.World)?.let { scan(it.desc) }
                (st.gate as? Gate.Did)?.let { scan(it.desc) }
            }
        }
        return bad.toList()
    }

    private fun chapter1FirstContact() = Chapter(
        id = "ch01-first-contact",
        act = 1,
        title = "First Contact",
        blurb = "Meet a single living cell, and learn to look at it.",
        scenario = GROW_ONLY,
        steps = listOf(
            Step(
                text = "Welcome to Cyto. That speck in the middle is a single living cell, floating in an empty world.",
                gate = Gate.Next,
                allow = LOOK,
            ),
            Step(
                text = "Drag empty space to move around, and scroll to zoom. Try it - get a good look at the cell.",
                gate = Gate.Did(PlayerAction.MovedCamera, "Pan or zoom the view"),
                allow = LOOK,
            ),
            Step(
                text = "Now click the cell to select it.",
                gate = Gate.Did(PlayerAction.SelectedCell, "Select the cell"),
                allow = LOOK,
                spotlight = Spotlight(dim = true),
            ),
            Step(
                text = "This panel is the cell's dossier: its size, its chemistry, and its genes. You'll live in here.",
                gate = Gate.Next,
                allow = LOOK,
                spotlight = Spotlight(hint = "See the info panel, top-right"),
            ),
            Step(
                text = "One last thing: the world wraps around. Walk off one edge and you arrive at the other - it's a doughnut, with no walls.",
                gate = Gate.Next,
                allow = LOOK,
                detail = "A torus has no special centre or corner: every point behaves the same, so a colony can spread in any direction forever.",
            ),
        ),
    )

    /** Act II opener. Re-uses the autotroph the player watched in Ch 2, but now opens its gene list and
     *  teaches the grammar: every cell is run by a short program of SOURCE : CONDITION : ACTION rules.
     *  Reading before writing - no edits here, just learning to read the cell's own three genes. */
    private fun chapter3AnatomyOfAGene() = Chapter(
        id = "ch03-anatomy",
        act = 2,
        title = "Anatomy of a Gene",
        blurb = "Read the tiny program that runs a living cell.",
        scenario = GROW_ONLY,
        steps = listOf(
            Step(
                text = "You've watched this cell hold steady. Now let's read why it does what it does. Click it to open its dossier.",
                gate = Gate.Did(PlayerAction.SelectedCell, "Select the cell"),
                allow = LOOK,
                spotlight = Spotlight(hint = "Click the cell"),
            ),
            Step(
                text = "Look at the GENES list in the panel. This whole organism is run by just two rules - that short list is the entire creature.",
                gate = Gate.Next,
                allow = LOOK,
                spotlight = Spotlight(hint = "GENES list, in the panel top-right"),
            ),
            Step(
                text = "Each gene reads as one sentence: an ACTION, IF a CONDITION holds, powered by a SOURCE shown in brackets.",
                gate = Gate.Next,
                allow = LOOK,
                detail = "Example: 'CONVERT RG IF BIO<3000 (LIGHT)' means - powered by light, while the cell is still small, lock rg into body mass. What to do, when to do it, and the power for it.",
            ),
            Step(
                text = "Both genes do one job: GROW. One bonds raw matter into food, the other locks that food into body mass. Together they keep the cell fed and repaired.",
                gate = Gate.Next,
                allow = LOOK,
                detail = "That's why it holds steady: as decay nibbles its body, CONVERT re-fires and rebuilds it, right back up to full size. A self-sustaining loop.",
            ),
            Step(
                text = "Colour shows a gene's state right now: green means it's firing, grey means it's waiting, and orange marks the part that's blocking it.",
                gate = Gate.Next,
                allow = LOOK,
                detail = "Only one gene runs per tick (round-robin), so at any instant most genes sit idle - watch the colours shift as the cell cycles through them.",
            ),
            Step(
                text = "But notice what's missing: nothing here makes a new cell. This organism can grow, but it can't reproduce. Next, you'll give it that power.",
                gate = Gate.Next,
                allow = LOOK,
            ),
        ),
    )

    private fun chapter2LetThereBeLight() = Chapter(
        id = "ch02-light",
        act = 1,
        title = "Let There Be Light",
        blurb = "Watch a cell feed on sunlight - and hold its ground.",
        scenario = GROW_ONLY,
        steps = listOf(
            Step(
                text = "This cell is an autotroph - it feeds on light. The bright band sweeping across the world is daylight. Where it's dark, the cell can't feed.",
                gate = Gate.Next,
                allow = WATCH,
                detail = "Light comes from a few fixed sources and sweeps as the world turns, so every spot has a day and a night.",
            ),
            Step(
                text = "Let's watch it live. Speed the simulation up with the controls at the top-left.",
                gate = Gate.Did(PlayerAction.ChangedSpeed, "Change the sim speed"),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "SLOW / PAUSE / FAST, top-left"),
            ),
            Step(
                text = "Watch it for a while. It feeds, repairs itself, and holds its size - but it never grows past this, and it never spreads. On its own, this organism just sits here.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
                detail = "It's already at full size, so it just tops itself up: light rebuilds whatever the slow decay of living wears away. A quiet, stable loop.",
            ),
            Step(
                text = "Its body is built from matter. Tap the LIGHT/MATTER button to reveal the raw matter scattered around it.",
                gate = Gate.Did(PlayerAction.ToggledMatterOverlay, "Show the matter overlay"),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "LIGHT/MATTER GRID button, bottom-right"),
            ),
            Step(
                text = "That matter is finite - nothing here comes from nothing. Light is free and endless, but matter is scarce and recycled. Every creature you build lives inside that budget.",
                gate = Gate.Next,
                allow = WATCH,
            ),
        ),
    )
}
