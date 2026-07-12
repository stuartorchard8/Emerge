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
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.AUTOTROPH_GENES
import org.emerge.demo.cyto.sim.AUTOTROPH_GROW_ONLY_GENES
import org.emerge.demo.cyto.sim.AUTOTROPH_REPAIR_GENE
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.FounderSpec
import org.emerge.demo.cyto.sim.Gene
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

    private const val GROUP_GROW = "Grow"
    private const val GROUP_REPRODUCE = "Reproduce"
    private const val GROUP_HOLD = "Hold Together"

    private fun List<Gene>.tagged(group: String): List<Gene> = map { it.copy(group = group) }

    /** Tag a full-autotroph gene by its role: the two grow genes → Grow, the Mitosis gene → Reproduce. */
    private fun Gene.taggedByRole(): Gene = copy(group = if (this in AUTOTROPH_GROW_ONLY_GENES) GROUP_GROW else GROUP_REPRODUCE)

    /** The reproduction subsystem the grow-only substrate is missing (the break-powered Mitosis gene) — the
     *  genes Ch4's "+ ADD REPRODUCE" inserts, pre-tagged so they carry their group label from the moment
     *  they're added. */
    private val REPRODUCE_GENES = AUTOTROPH_GENES.filter { it !in AUTOTROPH_GROW_ONLY_GENES }.tagged(GROUP_REPRODUCE)

    /** The cohesion subsystem Ch6's "+ ADD HOLD TOGETHER" inserts: a Repair gene, pre-tagged. */
    private val HOLD_TOGETHER_GENES = listOf(AUTOTROPH_REPAIR_GENE).tagged(GROUP_HOLD)

    /** The campaign's substrate: a single autotroph whose reproduction gene has been removed, so it grows to
     *  full size and then holds there, stationary and self-repairing but unable to spread (see
     *  [AUTOTROPH_GROW_ONLY_GENES]). The player watches this calm, easy-to-reason-about organism, reads its two
     *  grow genes, then *adds* reproduction to bring it to life. Frames the world as a substrate to author,
     *  not a busy ecosystem to catch up on. */
    private val GROW_ONLY = CytoScenario.DEFAULT.copy(
        name = "Campaign",
        founders = listOf(FounderSpec(CellType.Collector, 1, genome = AUTOTROPH_GROW_ONLY_GENES.tagged(GROUP_GROW))),
    )

    /** Ch5 substrate: the grow+reproduce autotroph the player built in Ch4 (the full [AUTOTROPH_GENES], tagged
     *  in place so gene order — behaviourally significant — is preserved). It colonises on its own; Ch5 makes
     *  the colony cohere by toggling the divide gene's SEVER field. */
    private val GROW_REPRODUCE = CytoScenario.DEFAULT.copy(
        name = "Campaign",
        founders = listOf(FounderSpec(CellType.Collector, 1, genome = AUTOTROPH_GENES.map { it.taggedByRole() })),
    )

    /** Ch6 substrate: the Ch5 end-state — a *welded* grow+reproduce autotroph (divide gene's SEVER already
     *  off, so daughters stay attached). It grows into a connected body when towed, but strain snaps its
     *  welds; Ch6 adds Repair to hold it together. */
    private val GROW_REPRODUCE_WELDED = CytoScenario.DEFAULT.copy(
        name = "Campaign",
        founders = listOf(FounderSpec(CellType.Collector, 1, genome = AUTOTROPH_GENES.map { g ->
            g.taggedByRole().let { if (it.action.type == ActionType.Mitosis) it.copy(action = it.action.copy(rejectMother = false)) else it }
        })),
    )

    /** Functional grouping for the campaign autotroph, over the Act II arc: two grow genes read as one "Grow"
     *  subsystem, the reproduction gene as "Reproduce". Membership is by each gene's [Gene.group] tag (set
     *  when the genome is seeded / inserted), so it survives editing — no matching. An absent group only
     *  surfaces as a "+ ADD" button in the chapter that names it insertable. Collapsed, the genome reads as a
     *  couple of plain labels (§10). */
    private val CAMPAIGN_GROUPING = GenomeGrouping(listOf(
        GeneGroup(GROUP_GROW, 0x3E9E5AFFL),
        GeneGroup(GROUP_REPRODUCE, 0xC77DD0FFL, insert = REPRODUCE_GENES),
        GeneGroup(GROUP_HOLD, 0xD98C40FFL, insert = HOLD_TOGETHER_GENES),
    ))

    val CHAPTERS: List<Chapter> = listOf(
        chapter1FirstContact(),
        chapter2LetThereBeLight(),
        chapter3AnatomyOfAGene(),
        chapter4Reproduce(),
        chapter5HoldTogether(),
        chapter6HoldUnderStrain(),
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
                gate = Gate.World("Select the cell", { it.focused != null }),
                allow = LOOK,
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
                gate = Gate.World("Select the cell", { it.focused != null }),
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
                gate = Gate.World("Select the cell", { it.focused != null }),
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

    /** Act II, first *direct gene edit*. The Ch4 colony scattered because every daughter severs on division -
     *  and severing doubles as locomotion (the two cells shove apart into fresh matter, fuelling more
     *  divisions). Here the player flips a single field, SEVER: yes -> no, so daughters stay welded. The
     *  welded pair holds together but stalls - stuck in place, it starves and stops dividing. The fix is
     *  *dragging*: tow the body around to feed it fresh matter and it grows again. That both unsticks it and
     *  sets up the next chapter - dragging strains the welds, and keeping them intact is a job of its own. */
    private fun chapter5HoldTogether() = Chapter(
        id = "ch05-hold",
        act = 2,
        title = "Hold Together",
        blurb = "A cloud of cells isn't a body. Weld it into one, and lead it.",
        scenario = GROW_REPRODUCE,
        grouping = CAMPAIGN_GROUPING,
        steps = listOf(
            Step(
                text = "Here's the grow-and-reproduce cell you built. Its colony scattered into a loose cloud - each daughter split off and shot away on its own. Let's make it stay together instead.",
                gate = Gate.Next,
                allow = LOOK,
            ),
            Step(
                text = "Select the cell, open its REPRODUCE group, and tap the divide gene inside to edit it.",
                gate = Gate.World("Select the cell", { it.focused != null }),
                allow = LOOK,
                spotlight = Spotlight(hint = "Click the cell, then + REPRODUCE, then the gene"),
            ),
            Step(
                text = "In the gene's fields, find SEVER: yes - that's what cuts each daughter loose. Switch it to SEVER: no, then press DONE.",
                gate = Gate.World(
                    "Set SEVER to no",
                    met = { it.focused?.divideWelds == true },
                ),
                allow = LOOK,
                spotlight = Spotlight(hint = "SEVER toggle, then DONE"),
                detail = "SEVER yes = the daughter breaks free as its own cell. SEVER no = it stays welded to its mother. One field, two completely different creatures.",
            ),
            Step(
                text = "Speed up and watch. It divides once, into a welded pair - then stops. Splitting off used to fling the cells into fresh matter. Now they sit still and quickly eat what's right around them.",
                gate = Gate.World(
                    "Watch it divide once",
                    met = { it.cellCount >= 2 },
                ),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "SLOW / PAUSE / FAST, top-left"),
            ),
            Step(
                text = "Why has it stalled? Turn on the matter grid. See the dark patch right under the pair - that's matter they've already eaten. Stuck in their own used-up ground, they've nothing left to build a daughter from.",
                gate = Gate.Did(PlayerAction.ToggledMatterOverlay, "Show the matter grid"),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "LIGHT/MATTER GRID button, bottom-right"),
                detail = "The bright ground all around them is fresh matter they can't reach - welded in place, they can't cross to it on their own.",
            ),
            Step(
                text = "So feed them yourself: drag the pair onto that bright, fresh matter. Fresh ground restarts division - lead the body around and watch the dark trail it eats behind it. As they feed, tiny flecks of matter drift in through the cells' skins and pass between the welded ones.",
                gate = Gate.World(
                    "Grow to 12 cells by dragging",
                    met = { it.cellCount >= 12 },
                    progress = { it.cellCount.coerceAtMost(12) to 12 },
                ),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "Press and drag a cell to tow the body"),
                detail = "It only builds in daylight, so if it stalls mid-tow you may have towed it into night. The matter grid hides the day/night light while it's on - tap the grid button back to LIGHT to see where the sun is, then carry on.",
            ),
            Step(
                text = "You're towing a living, connected body - one toggled field turned a scattering swarm into this. But drag it hard and you'll see the welds strain, and snap. Holding together under stress is next.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
                detail = "Welds bind neighbours, but they aren't unbreakable - yank the body and cells tear loose. Keeping a body intact while it moves is a job of its own, coming up.",
            ),
        ),
    )

    /** Act II, cohesion under strain. Ch5's welded body holds when still but tears when dragged hard - welds
     *  strain and snap. Here the player inserts a Repair "Hold Together" group; Repair is damage-gated, so it
     *  costs nothing at rest and, under strain, heals the welds as fast as dragging damages them - the body
     *  now holds together while it moves. Teaches that keeping a body intact is an active, on-demand job. */
    private fun chapter6HoldUnderStrain() = Chapter(
        id = "ch06-strain",
        act = 2,
        title = "Under Strain",
        blurb = "Welds snap when you pull. Teach the body to mend itself.",
        scenario = GROW_REPRODUCE_WELDED,
        grouping = CAMPAIGN_GROUPING,
        insertableGroups = setOf(GROUP_HOLD),
        steps = listOf(
            Step(
                text = "Here's your welded body again. Drag it around to grow it into a small colony first.",
                gate = Gate.World(
                    "Grow to 10 cells by dragging",
                    met = { it.cellCount >= 10 },
                    progress = { it.cellCount.coerceAtMost(10) to 10 },
                ),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "Press and drag a cell to tow the body"),
            ),
            Step(
                text = "Now yank it around hard and fast. See cells tear loose off the back - the welds survive a gentle tow, but a sharp pull snaps them.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "Drag hard and fast - cells shed off the back"),
            ),
            Step(
                text = "Select a cell and add the HOLD TOGETHER group. It's a Repair gene: it mends strained welds, and re-attaches neighbours that have drifted back together.",
                gate = Gate.World(
                    "Add the Hold Together group",
                    met = { (it.focused?.geneCount ?: 0) >= 4 },
                ),
                allow = LOOK,
                spotlight = Spotlight(hint = "+ ADD HOLD TOGETHER, below the groups"),
                detail = "Repair costs nothing while the body is calm - it only fires when there's damage to heal. Under strain it spends stored rg to mend welds, up to a limit.",
            ),
            Step(
                text = "Now drag it around again. It's tougher - Repair keeps mending the strained welds, so it holds together through pulls that tore it apart before. Yank hard enough and cells still rip loose, but it takes real force now.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "Drag it - it holds together far better now"),
            ),
            Step(
                text = "Grow, reproduce, cohere, and now mend under stress. Your single cell has become a tough, mobile body that repairs its own damage - a real creature.",
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
                text = "This cell is an autotroph - it feeds on light. Those bright bands filling the world are daylight - the dark gaps between them are night. See how the cell is sitting in a dark patch right now? In shadow, it can't feed.",
                gate = Gate.Next,
                allow = WATCH,
                detail = "Light comes from a few fixed sources and sweeps as the world turns, so every spot has a day and a night.",
            ),
            Step(
                text = "Let's set it in motion. Speed the world up - top-left - and watch the daylight sweep across and slide over the cell.",
                gate = Gate.Did(PlayerAction.ChangedSpeed, "Change the sim speed"),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "SLOW / PAUSE / FAST, top-left"),
            ),
            Step(
                text = "Now click the cell and watch its LIGHT reading in the panel. When daylight covers it the number climbs - that's it feeding. When night passes over, it drops back to zero.",
                gate = Gate.World("Select the cell", { it.focused != null }),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "LIGHT, in the panel top-right"),
                detail = "Watch SIZE too: it barely moves. Each spell of daylight rebuilds whatever the slow decay of living wears away, topping the cell back up to full - but never past it. A quiet, stable loop.",
            ),
            Step(
                text = "So it's alive and self-sustaining - but static. It never grows past this size, and it never spreads: a single cell, holding its ground forever. Next, let's read the tiny program that runs it, and then give it the power to multiply.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
            ),
        ),
    )
}
