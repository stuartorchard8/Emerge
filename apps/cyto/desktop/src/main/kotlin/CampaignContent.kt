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
import org.emerge.demo.cyto.sim.AUTOTROPH_GENES
import org.emerge.demo.cyto.sim.AUTOTROPH_GROW_ONLY_GENES
import org.emerge.demo.cyto.sim.AUTOTROPH_REPAIR_GENE
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.FounderSpec
import org.emerge.demo.cyto.ui.GeneGroup
import org.emerge.demo.cyto.ui.GenomeGrouping
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

    /** Ch5+ substrate: the grow+reproduce autotroph the player built in Ch4 (the full [AUTOTROPH_GENES]). It
     *  colonises on its own, but with no cohesion gene the colony frays into a loose, drifting cloud - the
     *  problem Ch5 fixes by inserting the Hold Together group. */
    private val GROW_REPRODUCE = CytoScenario.DEFAULT.copy(
        name = "Campaign",
        founders = listOf(FounderSpec(CellType.Collector, 1, genome = AUTOTROPH_GENES)),
    )

    /** The genes of the full autotroph that the grow-only substrate is *missing* - the reproduction subsystem
     *  the player adds in Act II (structurally, whatever [AUTOTROPH_GENES] has that the grow-only set doesn't:
     *  the break-powered Mitosis gene). */
    private val REPRODUCE_GENES = AUTOTROPH_GENES.filter { it !in AUTOTROPH_GROW_ONLY_GENES }

    /** Functional grouping for the campaign autotroph, over the whole Act II arc: two grow genes read as one
     *  "Grow" subsystem, the reproduction gene as "Reproduce", the cohesion gene as "Hold Together". Absent
     *  groups only surface as "+ ADD" buttons in the chapter that names them insertable, so each chapter
     *  offers just the one subsystem it teaches. Collapsed, the genome reads as a few plain labels (§10). */
    private val CAMPAIGN_GROUPING = GenomeGrouping(listOf(
        GeneGroup("Grow", 0x3E9E5AFFL, AUTOTROPH_GROW_ONLY_GENES),
        GeneGroup("Reproduce", 0xC77DD0FFL, REPRODUCE_GENES),
        GeneGroup("Hold Together", 0xD98C40FFL, listOf(AUTOTROPH_REPAIR_GENE)),
    ))

    val CHAPTERS: List<Chapter> = listOf(
        chapter1FirstContact(),
        chapter2LetThereBeLight(),
        chapter3AnatomyOfAGene(),
        chapter4Reproduce(),
        chapter5HoldTogether(),
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
        grouping = CAMPAIGN_GROUPING,
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

    /** Act II opener. Re-uses the grow-only autotroph, and opens its genome - shown BY FUNCTION, so the
     *  player meets it as named subsystems (here a single "Grow" group) before ever seeing a raw gene.
     *  Purpose before syntax: read the group label, then open it to read the two genes inside. */
    private fun chapter3AnatomyOfAGene() = Chapter(
        id = "ch03-anatomy",
        act = 2,
        title = "Anatomy of a Gene",
        blurb = "Read the tiny program that runs a living cell.",
        scenario = GROW_ONLY,
        grouping = CAMPAIGN_GROUPING,
        steps = listOf(
            Step(
                text = "You've watched this cell hold steady. Now let's read why it does what it does. Click it to open its dossier.",
                gate = Gate.Did(PlayerAction.SelectedCell, "Select the cell"),
                allow = LOOK,
                spotlight = Spotlight(hint = "Click the cell"),
            ),
            Step(
                text = "Its genome is shown by FUNCTION. Right now there's just one job: GROW. That single label sums up everything this organism does.",
                gate = Gate.Next,
                allow = LOOK,
                spotlight = Spotlight(hint = "the GROW group, in the panel top-right"),
                detail = "A genome is a set of subsystems, each doing one job. Grouping them this way turns a wall of rules into a handful of purposes you can read at a glance.",
            ),
            Step(
                text = "Tap the GROW group to open it. Inside are the actual genes - two of them - that carry out the job.",
                gate = Gate.Next,
                allow = LOOK,
                spotlight = Spotlight(hint = "tap + GROW (2) to expand it"),
            ),
            Step(
                text = "Each gene reads as one sentence: an ACTION, IF a CONDITION holds, powered by a SOURCE shown in brackets.",
                gate = Gate.Next,
                allow = LOOK,
                detail = "Example: 'CONVERT RG IF BIO<3000 (LIGHT)' means - powered by light, while the cell is still small, lock rg into body mass. What to do, when to do it, and the power for it.",
            ),
            Step(
                text = "The two GROW genes work together: one bonds raw matter into food, the other locks that food into body mass. That loop keeps the cell fed and repaired.",
                gate = Gate.Next,
                allow = LOOK,
                detail = "That's why it holds steady: as decay nibbles its body, CONVERT re-fires and rebuilds it, right back up to full size. Colour shows each gene's state - green is firing, grey is waiting, orange marks what's blocking it.",
            ),
            Step(
                text = "But notice what's missing: there's no group for reproduction. This organism can grow, but it can't multiply. Next, you'll add that.",
                gate = Gate.Next,
                allow = LOOK,
            ),
        ),
    )

    /** Act II, first authoring beat. The player brings the static grow-only organism to life by *inserting*
     *  the ready-made Reproduce subsystem (one tap on "ADD REPRODUCE"), then watches it divide and spread.
     *  Teaches Mitosis by using it to solve a problem, and the group-insert idea: you build with meaningful
     *  units, not raw genes. */
    private fun chapter4Reproduce() = Chapter(
        id = "ch04-reproduce",
        act = 2,
        title = "Give It Life",
        blurb = "Add a gene, and turn one static cell into a spreading colony.",
        scenario = GROW_ONLY,
        grouping = CAMPAIGN_GROUPING,
        insertableGroups = setOf("Reproduce"),
        steps = listOf(
            Step(
                text = "This organism grows but can't reproduce - on its own it's a dead end. Let's fix that. Select the cell to open its genome.",
                gate = Gate.Did(PlayerAction.SelectedCell, "Select the cell"),
                allow = LOOK,
                spotlight = Spotlight(hint = "Click the cell"),
            ),
            Step(
                text = "Below its GROW group is a ready-made subsystem it's missing: ADD REPRODUCE. Tap it to give the cell a reproduction gene.",
                gate = Gate.World(
                    "Add the Reproduce group",
                    met = { (it.focused?.geneCount ?: 0) >= 3 },
                ),
                allow = LOOK,
                spotlight = Spotlight(hint = "+ ADD REPRODUCE, below the groups"),
                detail = "You're not writing a gene by hand - you're dropping in a whole pre-made function. That's how bodies are built here: from reusable subsystems.",
            ),
            Step(
                text = "Done. It now has a REPRODUCE group. Speed the sim up and watch: big enough, the cell splits in two, then those split, and a colony spreads.",
                gate = Gate.World(
                    "Grow to 30 cells",
                    met = { it.cellCount >= 30 },
                    progress = { it.cellCount.coerceAtMost(30) to 30 },
                ),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "SLOW / PAUSE / FAST, top-left"),
            ),
            Step(
                text = "One gene turned a static cell into a spreading colony - small genetic change, huge behaviour. But it won't fill the world forever. Turn on the matter grid to see the limit.",
                gate = Gate.Did(PlayerAction.ToggledMatterOverlay, "Show the matter grid"),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "LIGHT/MATTER GRID button, bottom-right"),
            ),
            Step(
                text = "See the dark patch? That's matter the colony has already used up. Cells stuck in that exhausted zone can't divide - only the frontier, reaching fresh matter, keeps spreading.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
                detail = "Zoom out to see the whole colony: a bright, growing edge chasing fresh matter, dragging a spent, crowded interior behind it.",
            ),
            Step(
                text = "That's the core tension: light is free and endless, but matter is scarce. Every colony grows until it runs into that budget. From here on, the game is about managing it.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
            ),
        ),
    )

    /** Act II, cohesion. The Ch4 colony spread into a loose, drifting cloud. Here the player inserts a
     *  "Hold Together" (Repair) subsystem *before* the cell colonises, so every descendant spends a little
     *  energy staying bonded - and the colony grows as a cohesive body instead of a scattering cloud.
     *  Teaches that a body is held together actively, at a cost, not for free. */
    private fun chapter5HoldTogether() = Chapter(
        id = "ch05-hold",
        act = 2,
        title = "Hold Together",
        blurb = "A cloud of cells isn't a body. Make the colony cohere.",
        scenario = GROW_REPRODUCE,
        grouping = CAMPAIGN_GROUPING,
        insertableGroups = setOf("Hold Together"),
        steps = listOf(
            Step(
                text = "Here's the grow-and-reproduce cell you built. Last time, its colony spread into a loose, drifting cloud - because nothing held the cells together.",
                gate = Gate.Next,
                allow = LOOK,
            ),
            Step(
                text = "Let's fix that before it spreads. Select the cell to open its genome.",
                gate = Gate.Did(PlayerAction.SelectedCell, "Select the cell"),
                allow = LOOK,
                spotlight = Spotlight(hint = "Click the cell"),
            ),
            Step(
                text = "Add the HOLD TOGETHER group. It spends a little stored energy keeping each cell bonded to its neighbours.",
                gate = Gate.World(
                    "Add the Hold Together group",
                    met = { (it.focused?.geneCount ?: 0) >= 4 },
                ),
                allow = LOOK,
                spotlight = Spotlight(hint = "+ ADD HOLD TOGETHER, below the groups"),
                detail = "Cohesion isn't free here - holding a body together costs matter, and a cell that runs out will start to fray. A body is something a colony actively maintains.",
            ),
            Step(
                text = "Now speed up and watch it colonise. This time the offspring stay bonded - the colony packs into a dense mass instead of scattering into a thin cloud.",
                gate = Gate.World(
                    "Grow to 40 cells",
                    met = { it.cellCount >= 40 },
                    progress = { it.cellCount.coerceAtMost(40) to 40 },
                ),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "SLOW / PAUSE / FAST, top-left"),
            ),
            Step(
                text = "Compare it to last chapter's drifting cloud - this one holds together. Grow, reproduce, cohere: three subsystems, and a single cell has become a living body.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
            ),
        ),
    )

    private fun chapter2LetThereBeLight() = Chapter(
        id = "ch02-light",
        act = 1,
        title = "Let There Be Light",
        blurb = "Watch a cell feed on sunlight - and hold its ground.",
        scenario = GROW_ONLY,
        grouping = CAMPAIGN_GROUPING,
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
                text = "So it's alive and self-sustaining - but static. A single cell, holding its ground forever. Next, let's read the tiny program that runs it, and then give it the power to multiply.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
            ),
        ),
    )
}
