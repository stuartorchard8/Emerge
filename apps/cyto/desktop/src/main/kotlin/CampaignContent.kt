package org.emerge.desktop

import org.emerge.demo.cyto.campaign.Chapter
import org.emerge.demo.cyto.campaign.Control
import org.emerge.demo.cyto.campaign.ControlMask
import org.emerge.demo.cyto.campaign.Gate
import org.emerge.demo.cyto.campaign.PlayerAction
import org.emerge.demo.cyto.campaign.Spotlight
import org.emerge.demo.cyto.campaign.Step
import org.emerge.demo.cyto.campaign.WorldRun
import org.emerge.demo.cyto.sim.CytoScenario
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
        scenario = CytoScenario.DEFAULT,
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
        scenario = CytoScenario.DEFAULT,
        steps = listOf(
            Step(
                text = "You've watched this cell live. Now let's read why it does what it does. Click it to open its dossier.",
                gate = Gate.Did(PlayerAction.SelectedCell, "Select the cell"),
                allow = LOOK,
                spotlight = Spotlight(hint = "Click the cell"),
            ),
            Step(
                text = "Look at the GENES list in the panel. This cell is run by just three rules - that whole list is the entire creature.",
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
                text = "Colour tells you a gene's state right now: green means it's firing, grey means it's waiting, and orange marks the part that's blocking it.",
                gate = Gate.Next,
                allow = LOOK,
                detail = "Only one gene runs per tick (round-robin), so at any instant most genes sit idle - watch the colours shift as the cell cycles through them.",
            ),
            Step(
                text = "Tap any gene to open it - every part becomes an editable field. Have a look, but you don't need to change anything yet.",
                gate = Gate.Next,
                allow = LOOK,
            ),
            Step(
                text = "Three genes, three jobs: make food, grow, then divide. That's it. Next you'll fix a cell that's missing one of them.",
                gate = Gate.Next,
                allow = LOOK,
            ),
        ),
    )

    private fun chapter2LetThereBeLight() = Chapter(
        id = "ch02-light",
        act = 1,
        title = "Let There Be Light",
        blurb = "Watch a cell feed on sunlight, and reveal what it eats.",
        scenario = CytoScenario.DEFAULT,
        steps = listOf(
            Step(
                text = "This cell is an autotroph - it eats light. The bright band sweeping across the world is daylight. Where it's dark, the cell can't feed.",
                gate = Gate.Next,
                allow = WATCH,
                detail = "Interior cells are shaded by their neighbours too, so being buried also starves a cell of light.",
            ),
            Step(
                text = "Let's watch it work. Speed the simulation up with the controls at the top-left.",
                gate = Gate.Did(PlayerAction.ChangedSpeed, "Change the sim speed"),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "SLOW / PAUSE / FAST, top-left"),
            ),
            Step(
                text = "It's turning light and matter into its own body - growing.",
                gate = Gate.World(
                    "Watch it grow",
                    met = { it.maxBiomass > 1500 },
                    progress = { (it.maxBiomass.coerceAtMost(1500)) to 1500 },
                ),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "Watch the cell swell"),
            ),
            Step(
                text = "Big enough, it splits in two. Keep watching - a colony is forming.",
                gate = Gate.World(
                    "Reach 4 cells",
                    met = { it.cellCount >= 4 },
                    progress = { it.cellCount.coerceAtMost(4) to 4 },
                ),
                allow = WATCH,
                world = WorldRun.Live,
            ),
            Step(
                text = "The colony is slowing down - it has eaten the matter nearby. Tap the LIGHT/MATTER button to reveal what's left.",
                gate = Gate.Did(PlayerAction.ToggledMatterOverlay, "Show the matter overlay"),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "LIGHT/MATTER GRID button, bottom-right"),
            ),
            Step(
                text = "Nothing here comes from nothing: matter is finite, and recycled on death. Light is free, matter is scarce. Every creature you build lives inside that budget.",
                gate = Gate.Next,
                allow = WATCH,
            ),
        ),
    )
}
