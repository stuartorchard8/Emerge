package org.emerge.demo.cyto.host

import org.emerge.demo.cyto.campaign.Chapter
import org.emerge.demo.cyto.campaign.Control
import org.emerge.demo.cyto.campaign.ControlMask
import org.emerge.demo.cyto.campaign.InputHints
import org.emerge.demo.cyto.campaign.Gate
import org.emerge.demo.cyto.campaign.PlayerAction
import org.emerge.demo.cyto.campaign.Spotlight
import org.emerge.demo.cyto.campaign.Step
import org.emerge.demo.cyto.campaign.WorldRun
import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.AUTOTROPH_GENES
import org.emerge.demo.cyto.sim.AUTOTROPH_GROW_ONLY_GENES
import org.emerge.demo.cyto.sim.AUTOTROPH_MOVE_GENE
import org.emerge.demo.cyto.sim.AUTOTROPH_REPAIR_GENE
import org.emerge.demo.cyto.sim.CytoScenario
import org.emerge.demo.cyto.sim.FounderSpec
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneCodec
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

    /** LOOK plus the overlay control - for watching the world run at the campaign's curated pace. Note: no
     *  Speed control. Early chapters deliberately withhold the SLOW/PAUSE/FAST buttons (the campaign runs its
     *  Live steps at a good default speed on its own); time controls are introduced later, in Ch8, where
     *  watching long-term locomotion across day/night cycles makes them genuinely useful. */
    private val WATCH = ControlMask.of(
        Control.Camera, Control.Select, Control.GeneEditor, Control.Overlays, Control.Menu,
    )

    /** WATCH plus tapping empty space to re-seed the chapter genome (Ch8's "tap to add a cell"). No time
     *  controls yet — those arrive on the next beat. */
    private val SPAWN = ControlMask.of(
        Control.Camera, Control.Select, Control.GeneEditor, Control.Overlays, Control.Menu, Control.Spawn,
    )

    /** WATCH plus the SLOW/PAUSE/FAST time controls (and re-seeding) - introduced in Ch8, the first chapter
     *  where the player watches long-running behaviour (day/night-linked locomotion) and needs to pace time. */
    private val WATCH_TIME = ControlMask.of(
        Control.Camera, Control.Select, Control.GeneEditor, Control.Overlays, Control.Menu,
        Control.Speed, Control.Spawn,
    )

    private const val GROUP_GROW = "Grow"
    private const val GROUP_REPRODUCE = "Reproduce"
    private const val GROUP_HOLD = "Hold Together"
    private const val GROUP_MOVE = "Move"

    private fun List<Gene>.tagged(group: String): List<Gene> = map { it.copy(group = group) }

    /** Tag a full-autotroph gene by its role: the two grow genes → Grow, the Mitosis gene → Reproduce. */
    private fun Gene.taggedByRole(): Gene = copy(group = if (this in AUTOTROPH_GROW_ONLY_GENES) GROUP_GROW else GROUP_REPRODUCE)

    /** The reproduction subsystem the grow-only substrate is missing (the break-powered Mitosis gene) — the
     *  genes Ch4's "+ ADD REPRODUCE" inserts, pre-tagged so they carry their group label from the moment
     *  they're added. */
    private val REPRODUCE_GENES = AUTOTROPH_GENES.filter { it !in AUTOTROPH_GROW_ONLY_GENES }.tagged(GROUP_REPRODUCE)

    /** The cohesion subsystem Ch6's "+ ADD HOLD TOGETHER" inserts: a Repair gene, pre-tagged. */
    private val HOLD_TOGETHER_GENES = listOf(AUTOTROPH_REPAIR_GENE).tagged(GROUP_HOLD)

    /** The locomotion subsystem Ch7's "+ ADD MOVE" inserts: a single Contract gene, pre-tagged. Powered by
     *  breaking the `rg` reserve, it flexes the cell in place and drives it into constant metabolism (so the
     *  Living-World flows finally show). Phased across a body it becomes swimming — held for Ch8. */
    private val MOVE_GENES = listOf(AUTOTROPH_MOVE_GENE).tagged(GROUP_MOVE)

    /** The campaign's substrate: a single autotroph whose reproduction gene has been removed, so it grows to
     *  full size and then holds there, stationary and self-repairing but unable to spread (see
     *  [AUTOTROPH_GROW_ONLY_GENES]). The player watches this calm, easy-to-reason-about organism, reads its two
     *  grow genes, then *adds* reproduction to bring it to life. Frames the world as a substrate to author,
     *  not a busy ecosystem to catch up on. */
    // Chemical aliases (see [CytoScenario.aliases]): name the campaign's molecules by what they DO in the
    // genome, so a gene card reads "BREAK FUEL (R/G) TO POWER" instead of "BREAK REDREEN…". `rg` is the
    // energy store every genome burns; `bb` the polarity marker one daughter keeps; `gb` the clock signal the
    // muscle waits on. Built-in names still cover everything unaliased.
    private val FUEL_ALIASES = mapOf("rg" to "fuel")
    private val SWIMMER_ALIASES = FUEL_ALIASES + mapOf("bb" to "marker")
    private val CLOCKED_ALIASES = SWIMMER_ALIASES + mapOf("gb" to "beat")

    private val GROW_ONLY = CytoScenario.DEFAULT.copy(
        name = "Campaign",
        founders = listOf(FounderSpec(CellType.Collector, 1, genome = AUTOTROPH_GROW_ONLY_GENES.tagged(GROUP_GROW))),
        aliases = FUEL_ALIASES,
    )

    /** Ch5 substrate: the grow+reproduce autotroph the player built in Ch4 (the full [AUTOTROPH_GENES], tagged
     *  in place so gene order — behaviourally significant — is preserved). It colonises on its own; Ch5 makes
     *  the colony cohere by toggling the divide gene's SEVER field. */
    private val GROW_REPRODUCE = CytoScenario.DEFAULT.copy(
        name = "Campaign",
        founders = listOf(FounderSpec(CellType.Collector, 1, genome = AUTOTROPH_GENES.map { it.taggedByRole() })),
        aliases = FUEL_ALIASES,
    )

    /** Ch6 substrate: the Ch5 end-state — a *welded* grow+reproduce autotroph (divide gene's SEVER already
     *  off, so daughters stay attached). It grows into a connected body when towed, but strain snaps its
     *  welds; Ch6 adds Repair to hold it together. */
    private val GROW_REPRODUCE_WELDED = CytoScenario.DEFAULT.copy(
        name = "Campaign",
        founders = listOf(FounderSpec(CellType.Collector, 1, genome = AUTOTROPH_GENES.map { g ->
            g.taggedByRole().let { if (it.action.type == ActionType.Mitosis) it.copy(action = it.action.copy(rejectMother = false)) else it }
        })),
        aliases = FUEL_ALIASES,
    )

    /** Functional grouping for the campaign autotroph, over the Act II arc: two grow genes read as one "Grow"
     *  subsystem, the reproduction gene as "Reproduce". Membership is by each gene's [Gene.group] tag (set
     *  when the genome is seeded / inserted), so it survives editing — no matching. An absent group only
     *  surfaces as a "+ ADD" button in the chapter that names it insertable. Collapsed, the genome reads as a
     *  couple of plain labels (§10). */
    private val CAMPAIGN_GROUPING = GenomeGrouping(listOf(
        GeneGroup(GROUP_GROW),
        GeneGroup(GROUP_REPRODUCE, insert = REPRODUCE_GENES),
        GeneGroup(GROUP_HOLD, insert = HOLD_TOGETHER_GENES),
        GeneGroup(GROUP_MOVE, insert = MOVE_GENES),
    ))

    /** Ch7 substrate: the Ch6 end-state - a welded grow+reproduce autotroph that also holds itself together
     *  (the Repair "Hold Together" gene already inserted). It's tough and self-mending but inert; Ch7 adds a
     *  Contract "Move" gene so it flexes and comes alive. Grow/Reproduce tagged by role; the trailing Repair
     *  carries its Hold-Together tag. */
    private val GROW_REPRODUCE_WELDED_HOLD = CytoScenario.DEFAULT.copy(
        name = "Campaign",
        founders = listOf(FounderSpec(CellType.Collector, 1, genome = AUTOTROPH_GENES.map { g ->
            g.taggedByRole().let { if (it.action.type == ActionType.Mitosis) it.copy(action = it.action.copy(rejectMother = false)) else it }
        } + HOLD_TOGETHER_GENES)),
        aliases = FUEL_ALIASES,
    )

    // ── Ch8: the Polarise / differentiation genome (Stu's world-8 swimmer lineage) ──────────────────────
    // A bespoke locomotion genome, tagged into functional groups. Unlike the Ch1-7 autotroph it runs a
    // b/bb/gr/br chemistry: `bb` is a morphogen handed WHOLE to one daughter on division (Mitosis `bb
    // mother`), so only the marked side carries it. The MOVE muscle (light-powered Contract) fires while
    // `bb < 1`, so the UN-marked cells clench and the marked cell holds still - an ASYMMETRIC squeeze that
    // makes the body travel. Authored as text + parsed via GeneCodec so it reads exactly like the `.gene`
    // files it came from (group tags = the optional 4th `:`-part).
    private const val GROUP_POLARIZE = "polarize"

    /** Ch8 substrate: the swimmer WITHOUT its polarize group - it feeds, maintains, and contracts, but with
     *  no `bb` marker every cell clenches the same, so (like Ch7's muscle) it goes nowhere; and with no `gr`
     *  the grow chain stalls, so it can't divide. A calm single cell, just like Ch7's end-state. */
    private val CH8_INIT_GENES: List<Gene> = GeneCodec.parse(
        """
        Light : rg < 10000 : FormBond r g : feed
        Break rg : Biomass < 3000 & rg > 500 : Convert rg @15 : maintain
        Break rg : rg > 500 : Repair @10 : maintain
        Light : bb < 1 : Contract @15 : move
        Break rg : bb < 5 & gr > 0 & br < rg : FormBond b r : grow
        Break br : gr > 9 : FormBond r g @13 : grow
        Break rg : br < 10 & Biomass > 2000 : Mitosis bb mother across gr : grow
        """.trimIndent(),
    )

    /** The polarize subsystem Ch8's "+ ADD POLARIZE" inserts: it synthesises the `bb` morphogen and hands it
     *  to one daughter, establishing the front/back difference that makes contraction asymmetric. */
    private val CH8_POLARIZE_GENES: List<Gene> = GeneCodec.parse(
        """
        Break rg : gr < 1 & bb < rg : FormBond b b : polarize
        Break rg : bb > 1 & bb < rg : FormBond b b : polarize
        Break bb : Biomass < 3000 & bb > 40 : Convert bb @15 : polarize
        Break bb : bb > 1 : Retain bb : polarize
        Break rg : bb > 0 & gr < rg : FormBond g r : polarize
        Break gr : bb < 1 & gr > 1 : FormBond r g @14 : polarize
        """.trimIndent(),
    )

    /** The full differentiated swimmer = substrate + polarize (the exact order "+ ADD" produces, appending
     *  the inserted group). Used as the re-seed genome for the "tap empty space to add a cell" affordance. */
    private val CH8_FULL_GENES: List<Gene> = CH8_INIT_GENES + CH8_POLARIZE_GENES

    private val CH8_SUBSTRATE = CytoScenario.DEFAULT.copy(
        name = "Campaign",
        founders = listOf(FounderSpec(CellType.Stem, 1, genome = CH8_INIT_GENES)),
        aliases = SWIMMER_ALIASES,
    )

    /** Ch8's grouping - the swimmer's functional subsystems. Only POLARIZE is offered as an insert (the rest
     *  are the substrate the player already has). Header colours auto-derive from the names. */
    private val CH8_GROUPING = GenomeGrouping(listOf(
        GeneGroup("feed"),
        GeneGroup("maintain"),
        GeneGroup("move"),
        GeneGroup("grow"),
        GeneGroup(GROUP_POLARIZE, insert = CH8_POLARIZE_GENES),
    ))

    // ── Ch9: the metabolic clock + the muscle-fuel lineage (Stu's swimmerx / swimmerxX genomes) ──────────
    // Ch8 left a swimmer that moves only in daylight (a light-powered muscle). Ch9 gives it an internal
    // OSCILLATOR - the `clock` group synthesises a `gb`/`gg`/`bg` feedback loop that rises and falls on its
    // own beat - and the muscle here is already wired to wait on it (`gb > 50`), so before the clock is added
    // the body sits still. The player then edits a LINEAGE by hand across three generations, each tapped out
    // as a live copy of the last (the "last-modified brush", [Chapter.spawnCopiesHeldCell]):
    //   gen 1: as-authored - Light-powered muscle on the BARE cells (`bb < 1`); swims by day only.
    //   gen 2: muscle fuel Light -> Break rg; runs on stored reserves, so it swims through the night too.
    //   gen 3: muscle marker `bb < 1` -> `bb > 0`; the MARKED cell drives instead - a more adept swimmer.
    // Authored as text so it reads like the `.gene` library files it came from (group tags = the 4th part).
    private const val GROUP_CLOCK = "clock"

    /** Ch9 substrate: the swimmerx genome WITHOUT its clock group. Every group is present - feed, maintain,
     *  polarize, move, grow - but the MOVE muscle gates on `gb > 50`, a clock chemical nothing here makes, so
     *  the body sits inert (like Ch7/Ch8's calm starting cell). Adding CLOCK sets it beating. */
    private val CH9_INIT_GENES: List<Gene> = GeneCodec.parse(
        """
        Light : rg < 10000 : FormBond r g : feed
        Break rg : Biomass < 3000 & rg > 500 : Convert rg @15 : maintain
        Break rg : rg > 500 : Repair @10 : maintain
        Break rg : gr < 1 & bb < rg : FormBond b b : polarize
        Break rg : bb > 1 & bb < rg : FormBond b b : polarize
        Break bb : Biomass < 3000 & bb > 40 : Convert bb @15 : polarize
        Break bb : bb > 1 : Retain bb : polarize
        Break rg : bb > 0 & gr < rg : FormBond g r : polarize
        Break gr : bb < 1 & gr > 1 : FormBond r g @14 : polarize
        Light : bb < 1 & gb > 50 : Contract @15 : move
        Break rg : bb < 5 & gr > 0 & br < 200 : FormBond b r : grow
        Break br : gr > 9 : FormBond r g @10 : grow
        Break rg : br < 20 & gr > 1000 & Biomass > 2000 : Mitosis bb mother across gr : grow
        """.trimIndent(),
    )

    /** The clock subsystem Ch9's "+ ADD CLOCK" inserts: a `gg`/`gb`/`bg` feedback loop, powered by breaking
     *  `br`, that oscillates on its own beat. The muscle (`gb > 50`) fires on the crest, so the body pulses. */
    private val CH9_CLOCK_GENES: List<Gene> = GeneCodec.parse(
        """
        Break rg : gg < 20 & gb < 20 & br > 100 : FormBond b g : clock
        Break rg : gg > 19 & bg < 20 & br > 100 : FormBond b g : clock
        Break gb : gg < 20 : FormBond b g @14 : clock
        Break bg : gg > 19 : FormBond g b @14 : clock
        Break rg : gg < 30 & bg > 100 : FormBond g g : clock
        Break gg : gb > 100 : FormBond r g : clock
        """.trimIndent(),
    )

    /** The clocked swimmer = substrate + clock (the exact order "+ ADD" produces). The fallback re-seed genome
     *  for empty-space taps when no cell is selected; normally the tap copies the selected cell's live genome. */
    private val CH9_FULL_GENES: List<Gene> = CH9_INIT_GENES + CH9_CLOCK_GENES

    private val CH9_SUBSTRATE = CytoScenario.DEFAULT.copy(
        name = "Campaign",
        founders = listOf(FounderSpec(CellType.Stem, 1, genome = CH9_INIT_GENES)),
        aliases = CLOCKED_ALIASES,
    )

    /** Ch9's grouping - the swimmer's subsystems, with CLOCK offered as the insert (the rest are the substrate
     *  the player already has). Header colours auto-derive from the names. */
    private val CH9_GROUPING = GenomeGrouping(listOf(
        GeneGroup("feed"),
        GeneGroup("maintain"),
        GeneGroup(GROUP_POLARIZE),
        GeneGroup("move"),
        GeneGroup("grow"),
        GeneGroup(GROUP_CLOCK, insert = CH9_CLOCK_GENES),
    ))

    // ── Ch10: reproduction / colonisation (Stu's reproducer genome = SwimmerxX + a sever-division group) ──
    // The Ch9 end-state swimmer (clocked, night-running, marked-cell muscle) is growth-capped by the Ch8
    // morphogen: it grows to a small cluster and holds there - one lone creature. Ch10 adds a REPRODUCE
    // group whose payload is a SEVER division (`Mitosis gr mother sever` = rejectMother): when the body is
    // big enough it buds a daughter FREE - no weld - as its own single-celled founder, which drifts off,
    // escapes the size cap (it's small again), and grows into a whole new swimmer. A lineage that spreads.
    private const val GROUP_REPRODUCE_SWIMMER = "reproduce"

    /** Ch10 substrate: the Ch9 end-state swimmer (Stu's SwimmerxX) WITHOUT its reproduce group. It swims -
     *  clocked, break-powered, marked-cell muscle - but every division stays welded and the morphogen caps
     *  its size, so it grows to one small cluster and holds there. Adding REPRODUCE lets it colonise. */
    private val CH10_INIT_GENES: List<Gene> = GeneCodec.parse(
        """
        Light : rg < 10000 : FormBond r g : feed
        Break rg : Biomass < 3000 & rg > 500 : Convert rg @15 : maintain
        Break rg : rg > 500 : Repair @10 : maintain
        Break rg : gr < 1 & bb < rg : FormBond b b : polarize
        Break rg : bb > 1 & bb < rg : FormBond b b : polarize
        Break bb : Biomass < 3000 & bb > 40 : Convert bb @15 : polarize
        Break bb : bb > 1 : Retain bb : polarize
        Break rg : bb > 0 & gr < rg : FormBond g r : polarize
        Break gr : bb < 1 & gr > 1 : FormBond r g @14 : polarize
        Break rg : bb > 0 & gb > 50 & rg > 500 : Contract @15 : move
        Break rg : gg < 20 & gb < 20 & br > 100 : FormBond b g : clock
        Break rg : gg > 19 & bg < 20 & br > 100 : FormBond b g : clock
        Break gb : gg < 20 : FormBond b g @14 : clock
        Break bg : gg > 19 : FormBond g b @14 : clock
        Break rg : gg < 30 & bg > 100 : FormBond g g : clock
        Break gg : gb > 100 : FormBond r g : clock
        Break rg : bb < 5 & gr > 0 & br < 200 : FormBond b r : grow
        Break br : gr > 9 : FormBond r g @10 : grow
        Break rg : br < 20 & gr > 1000 & Biomass > 2000 : Mitosis bb mother across gr : grow
        """.trimIndent(),
    )

    /** The reproduction subsystem Ch10's "+ ADD REPRODUCE" inserts: a reserve-building Convert plus a SEVER
     *  division (`Mitosis gr mother sever` = rejectMother). Once the body is large it splits off a daughter
     *  that breaks free, unwelded, as its own founder - which then drifts away and grows a new swimmer. */
    private val CH10_REPRODUCE_GENES: List<Gene> = GeneCodec.parse(
        """
        Break br : br > 179 & Biomass < 5000 & rg > 5000 : Convert br : reproduce
        Break rg : Biomass > 3500 & gr > 1000 : Mitosis gr mother sever along gr : reproduce
        """.trimIndent(),
    )

    /** The reproducing swimmer = substrate + reproduce (the exact order "+ ADD" produces). Fallback re-seed
     *  genome for empty-space taps when no cell is selected. */
    private val CH10_FULL_GENES: List<Gene> = CH10_INIT_GENES + CH10_REPRODUCE_GENES

    private val CH10_SUBSTRATE = CytoScenario.DEFAULT.copy(
        name = "Campaign",
        founders = listOf(FounderSpec(CellType.Stem, 1, genome = CH10_INIT_GENES)),
        aliases = CLOCKED_ALIASES,
    )

    /** Ch10's grouping - the full swimmer's subsystems, with REPRODUCE offered as the insert (the rest are
     *  the substrate the player already has). Header colours auto-derive from the names. */
    private val CH10_GROUPING = GenomeGrouping(listOf(
        GeneGroup("feed"),
        GeneGroup("maintain"),
        GeneGroup(GROUP_POLARIZE),
        GeneGroup("move"),
        GeneGroup("clock"),
        GeneGroup("grow"),
        GeneGroup(GROUP_REPRODUCE_SWIMMER, insert = CH10_REPRODUCE_GENES),
    ))

    val CHAPTERS: List<Chapter> = listOf(
        chapter1FirstContact(),
        chapter2LetThereBeLight(),
        chapter3AnatomyOfAGene(),
        chapter4Reproduce(),
        chapter5HoldTogether(),
        chapter6HoldUnderStrain(),
        chapter7Move(),
        chapter8Polarise(),
        chapter9Clock(),
        chapter10Reproduce(),
    )

    val ORDER: List<String> = CHAPTERS.map { it.id }

    /** Distinct characters in the chapters' player-facing copy that the bitmap font can't render (would
     *  show as `?`). Empty = all copy is safe. The harness runs this as a guard so a bad glyph is caught
     *  headlessly rather than only spotted in the GL window. */
    fun validateGlyphs(): List<Char> {
        val bad = LinkedHashSet<Char>()
        // Scan the RENDERED copy of every modality: input `{tokens}` are expanded first, so the `{`/`}`
        // delimiters (which the font lacks) don't false-positive and the actual on-screen phrases are checked.
        val modalities = listOf(InputHints.MOUSE, InputHints.TOUCH)
        fun scan(s: String?) {
            s ?: return
            for (hints in modalities) hints.expand(s).forEach { if (it != '\n' && !UiTextRenderer.supports(it)) bad.add(it) }
        }
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
        startsFreshWorld = true,   // the campaign begins here; nothing precedes it
        grouping = CAMPAIGN_GROUPING,
        steps = listOf(
            Step(
                text = "Welcome to Cyto. That speck in the middle is a single living cell, floating in an empty world.",
                gate = Gate.Next,
                allow = LOOK,
            ),
            Step(
                text = "{pan} to move around, and {zoom} to zoom. Try it - get a good look at the cell.",
                gate = Gate.Did(PlayerAction.MovedCamera, "Pan or zoom the view"),
                allow = LOOK,
            ),
            Step(
                text = "Now select the cell.",
                gate = Gate.World("Select the cell", { it.focused != null }),
                allow = LOOK,
            ),
            Step(
                text = "This panel is the cell's dossier: its size, its chemistry, and its genes. You'll live in here.",
                gate = Gate.Next,
                allow = LOOK,
                spotlight = Spotlight(hint = "the cell's panel"),
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
                text = "You've watched this cell hold steady. Now let's read why it does what it does. Select it to open its dossier.",
                gate = Gate.World("Select the cell", { it.focused != null }),
                allow = LOOK,
                spotlight = Spotlight(hint = "Select the cell"),
            ),
            Step(
                text = "Its genome is shown by FUNCTION. Right now there's just one job: GROW. That single label sums up everything this organism does.",
                gate = Gate.Next,
                allow = LOOK,
                spotlight = Spotlight(hint = "the GROW group, in the cell's panel"),
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
                spotlight = Spotlight(hint = "Select the cell"),
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
                text = "Done. It now has a REPRODUCE group. Watch: big enough, the cell splits in two, then those split, and a colony spreads.",
                gate = Gate.World(
                    "Grow to 30 cells",
                    met = { it.cellCount >= 30 },
                    progress = { it.cellCount.coerceAtMost(30) to 30 },
                ),
                allow = WATCH,
                world = WorldRun.Live,
            ),
            Step(
                text = "One gene turned a static cell into a spreading colony. It won't fill the world forever, though. Look at the ground the colony has crossed: it's darker than the ground ahead of it.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
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
                spotlight = Spotlight(hint = "Select the cell, then + REPRODUCE, then the gene"),
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
                text = "Watch. It divides once, into a welded pair - then stops. Splitting off used to fling the cells into fresh matter. Now they sit still and quickly eat what's right around them.",
                gate = Gate.World(
                    "Watch it divide once",
                    met = { it.cellCount >= 2 },
                ),
                allow = WATCH,
                world = WorldRun.Live,
            ),
            Step(
                text = "Why has it stalled? Look at the ground right under the pair - it's darker than the ground around them. That's matter they've already eaten. Stuck in their own used-up patch, they've nothing left to build a daughter from.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
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
                detail = "It only builds in daylight, so if it stalls mid-tow you may have towed it into night. Daylight is the brighter stripe sweeping across the ground - keep the body in it, and watch the matter it eats darken the ground underneath as it feeds.",
            ),
            Step(
                text = "You're towing a living, connected body - flipping SEVER to no turned a scattering swarm into this. But drag it hard and you'll see the welds strain, and snap. Holding together under stress is next.",
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
                text = "Grow, reproduce, cohere, and now mend under stress. That one cell is now a tough, mobile body that repairs its own damage as it goes.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
            ),
        ),
    )

    /** Act II close / bridge to Act III (locomotion arc). In Ch5-6 the *player* supplied the motion, dragging
     *  the body around to feed it. Ch7 starts handing that job to the organism. The player inserts the **Move**
     *  subsystem - a single light-powered Contract gene - and watches the body clench through the day and relax
     *  at night (contraction coupled to sunlight). Crucially the squeeze is even and symmetric, so the body
     *  pulls in on itself and goes *nowhere* - the deliberate lesson that sets up Ch8 (a morphogen gradient ->
     *  asymmetric contraction -> real directional movement) and Ch9 (a metabolic clock to decouple the beat
     *  from day/night). See [AUTOTROPH_MOVE_GENE]. */
    private fun chapter7Move() = Chapter(
        id = "ch07-move",
        act = 2,
        title = "A Muscle",
        blurb = "You've been dragging this body to its food. Time it learned to move itself.",
        scenario = GROW_REPRODUCE_WELDED_HOLD,
        grouping = CAMPAIGN_GROUPING,
        insertableGroups = setOf(GROUP_MOVE),
        steps = listOf(
            Step(
                text = "Twice now, YOU have been the muscle - dragging this body around to find it food. A real creature moves itself. Let's start giving it that power.",
                gate = Gate.Next,
                allow = WATCH,
            ),
            Step(
                text = "Select the cell and add the MOVE group. It's a single Contract gene - a muscle. A contracting cell clenches inward, pulling itself smaller.",
                gate = Gate.World(
                    "Add the Move group",
                    met = { (it.focused?.geneCount ?: 0) >= 5 },
                ),
                allow = LOOK,
                spotlight = Spotlight(hint = "+ ADD MOVE, below the groups"),
                detail = "Like its grow genes, this muscle runs on light - so it can only clench while daylight is on it. In the dark it goes slack.",
            ),
            Step(
                text = "Watch it through a day and a night. As daylight sweeps over it, the body clenches up tight and small - the muscle firing. As night follows, the light leaves and it relaxes slowly back to full size.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
                detail = "It may sit in shadow a while first - wait for the bright band to reach it, then you'll see it pull in. It breathes in time with the sun.",
            ),
            Step(
                text = "So it can clench - but look where it ends up - exactly where it started. It squeezes evenly, all over at once, so every pull cancels every other. An even contraction just pulses on the spot. It gets nowhere.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
            ),
            Step(
                text = "To actually travel, the body has to squeeze LOPSIDED - cells on one side pulling harder than the other, so it lurches that way. To do that, the cells must first tell which side they're on. Building that sense of place is next.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
            ),
        ),
    )

    /** Act III opener - the locomotion payoff. The player takes a calm single cell (grows/contracts but
     *  goes nowhere, like Ch7's end-state), adds the POLARIZE group, and it comes alive: dividing into a
     *  small cluster whose marked and bare cells contract unevenly, so the body crawls. Introduces two
     *  things: cellular differentiation via a morphogen (a marker handed to one daughter → asymmetric
     *  contraction → real travel), and the SLOW/PAUSE/FAST time controls, now that behaviour plays out over
     *  day-night cycles. Also teaches the re-seed affordance (tap empty space) since a lone founder can die
     *  before it gets going. See [CH8_INIT_GENES]/[CH8_POLARIZE_GENES]. */
    private fun chapter8Polarise() = Chapter(
        id = "ch08-polarise",
        act = 3,
        title = "A Sense of Place",
        blurb = "An even squeeze goes nowhere. Teach the cells which side they're on.",
        scenario = CH8_SUBSTRATE,
        startsFreshWorld = true,   // the only mid-campaign break: the autotroph gives way to the swimmer lineage
        grouping = CH8_GROUPING,
        insertableGroups = setOf(GROUP_POLARIZE),
        spawnGenome = CH8_FULL_GENES,
        steps = listOf(
            Step(
                text = "Last chapter your muscle squeezed the whole body at once - it pulsed on the spot and went nowhere. To travel, some cells must pull while others hold still. For that, a cell first has to know which side it's on.",
                gate = Gate.Next,
                allow = LOOK,
            ),
            Step(
                text = "Here's a body built for it. Select it and open its genome - FEED, MAINTAIN, GROW, and a MOVE muscle like before. But it's still missing the one thing that makes its cells DIFFER from each other.",
                gate = Gate.World("Select the cell", { it.focused != null }),
                allow = LOOK,
                spotlight = Spotlight(hint = "Select the cell"),
            ),
            Step(
                text = "Add the POLARIZE group. It builds a chemical marker and, on every division, hands it to just ONE of the two daughters - so one cell ends up marked and the other bare. That difference is a sense of place.",
                gate = Gate.World("Add the Polarise group", met = { (it.focused?.geneCount ?: 0) >= 13 }),
                allow = LOOK,
                spotlight = Spotlight(hint = "+ ADD POLARIZE, below the groups"),
                detail = "The muscle only fires in cells WITHOUT the marker. So the marked cell holds still while its neighbours clench - the squeeze is now lopsided, and a lopsided squeeze travels.",
            ),
            Step(
                text = "Now give it a push to get it started - press and drag it across the world. As it grows and divides, the marked and bare cells pull unevenly and the little body starts to crawl. If a founder dies before it gets going, tap an empty space to drop in another cell with the same genome.",
                gate = Gate.World(
                    "Grow it to a moving cluster",
                    met = { it.cellCount >= 2 },
                    progress = { it.cellCount.coerceAtMost(2) to 2 },
                ),
                allow = SPAWN,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "Drag to push. Tap empty space to re-seed."),
                detail = "It grows into a small cluster of a few cells and then stops - that's deliberate. POLARIZE does double duty: the same marker gradient that tells cells which side they're on also lets the body sense how big it is, so it grows to a set size and holds there instead of spreading forever. A lone cell can't locomote, so it needs that first shove (or a fresh neighbour) to get over the line.",
            ),
            Step(
                text = "It swims on sunlight - the muscle only fires in the light, so it crawls through the day and drifts at night. Speed the world up to watch it travel across a few day-night cycles.",
                gate = Gate.Did(PlayerAction.ChangedSpeed, "Change the sim speed"),
                allow = WATCH_TIME,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "PAUSE in the bottom bar opens the speed controls"),
                detail = "This is why the speed controls matter now: locomotion plays out over whole day-night cycles - too slow to sit and watch in real time.",
            ),
            Step(
                text = "From a cell that only pulsed in place, you have a creature that SWIMS - just because its cells took on different roles. That's differentiation: one genome, read differently depending on where a cell sits.",
                gate = Gate.Next,
                allow = WATCH_TIME,
                world = WorldRun.Live,
            ),
        ),
    )

    /** Act III, the locomotion payoff continued. Ch8's swimmer moved only in daylight. Ch9 hands it a
     *  metabolic CLOCK (an internal oscillator) so its beat no longer depends on the sun, then walks the player
     *  through editing a three-generation LINEAGE - clock, then a fuel swap (moves at night), then a marker
     *  flip (a better swimmer) - each generation tapped out as a live copy of the last via the last-modified
     *  brush ([Chapter.spawnCopiesHeldCell]). All three coexist in one world for the closing payoff. See
     *  [CH9_INIT_GENES]/[CH9_CLOCK_GENES]. */
    private fun chapter9Clock() = Chapter(
        id = "ch09-clock",
        act = 3,
        title = "A Beat of Its Own",
        blurb = "It swims on sunlight, and stalls every night. Give it an inner clock, then breed it better.",
        scenario = CH9_SUBSTRATE,
        grouping = CH9_GROUPING,
        insertableGroups = setOf(GROUP_CLOCK),
        spawnGenome = CH9_FULL_GENES,
        spawnCopiesHeldCell = true,
        steps = listOf(
            Step(
                text = "Last chapter it swam on sunlight alone - crawling by day, drifting dead through every night. A real swimmer keeps a rhythm of its own. Let's give this one an inner beat.",
                gate = Gate.Next,
                allow = WATCH,
            ),
            Step(
                text = "Here's that swimmer - almost. Select it and open its genome. FEED, POLARIZE, MOVE, GROW are all here, yet it sits perfectly still. Its muscle has been wired to wait on a beat it doesn't have yet.",
                gate = Gate.World("Select the cell", { it.focused != null }),
                allow = LOOK,
                spotlight = Spotlight(hint = "Select the cell"),
            ),
            Step(
                text = "Add the CLOCK group. It builds a chemical that rises and falls on a loop of its own - an oscillator, ticking inside the cell whether or not the sun is up.",
                gate = Gate.World("Add the Clock group", met = { (it.focused?.geneCount ?: 0) >= 19 }),
                allow = LOOK,
                spotlight = Spotlight(hint = "+ ADD CLOCK, below the groups"),
                detail = "The muscle only fires when this clock chemical runs high. So instead of one long squeeze through the daylight, the body now PULSES - clench, release, clench - in time with its own beat.",
            ),
            Step(
                text = "Give it a push to get it going, and watch. In daylight the muscle now fires in beats and the little body pulses along as it crawls. If a founder dies before it gets going, tap an empty space to drop in another.",
                gate = Gate.World(
                    "Grow it to a moving cluster",
                    met = { it.cellCount >= 3 },
                    progress = { it.cellCount.coerceAtMost(3) to 3 },
                ),
                allow = WATCH_TIME,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "Drag to push. Tap empty space to re-seed."),
            ),
            Step(
                text = "But the muscle still runs on LIGHT, so night still stops it dead. Let's breed a variant that doesn't need the sun. Tap an empty space to lay down a fresh copy of your cell, then select that new one to work on it.",
                gate = Gate.Did(PlayerAction.SelectedCell, "Tap out a fresh copy and select it"),
                allow = WATCH_TIME,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "Tap empty space, then select the new cell"),
                detail = "Every cell you tap out now copies whatever genome your selected cell carries - so this fresh one already has the clock. You'll change just one thing about it.",
            ),
            Step(
                text = "Open its MOVE muscle and switch its power SOURCE from LIGHT to BREAK FUEL. Now it burns a stored reserve instead of sunlight - fuel it carries with it, day or night.",
                gate = Gate.World("Switch the muscle to CHEMICAL FUEL", met = { it.focused?.contractOnChem == true }),
                allow = WATCH_TIME,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "MOVE -> the muscle gene -> SOURCE -> BREAK FUEL"),
                detail = "Change only its fuel and leave the rest. The clock keeps ticking - the muscle just draws on reserves now instead of waiting for daylight.",
            ),
            Step(
                text = "Push your new variant off and speed the world up. Watch it cross from day into night - and keep swimming. On stored reserves its clock beats on in the dark, so it no longer stalls when the sun goes down.",
                gate = Gate.Next,
                allow = WATCH_TIME,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "Drag to push, then FAST - watch it swim past nightfall"),
            ),
            Step(
                text = "One more change to try. Right now the BARE cells pull while the MARKED cell holds still. Tap out another fresh copy - it carries your night-swimmer genome - and select it.",
                gate = Gate.Did(PlayerAction.SelectedCell, "Tap out another copy and select it"),
                allow = WATCH_TIME,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "Tap empty space, then select the new cell"),
            ),
            Step(
                text = "In its MOVE muscle, flip the marker test: change MARKER < 1 to MARKER > 0, so the MARKED cells drive the stroke instead of the bare ones. One flipped test, a different stroke.",
                gate = Gate.World("Flip the muscle to MARKER > 0", met = { it.focused?.contractOnMarked == true }),
                allow = WATCH_TIME,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "MOVE -> the muscle gene -> set MARKER's test to > and its value to 0"),
                detail = "In the editor, find the muscle's MARKER clause: set its comparator to > and step its value down to 0. That hands the driving role to the other cell type.",
            ),
            Step(
                text = "Push it off among the others. Now three of your lineage share one world - the day-only original, the night-runner, and this newest one. Speed up and compare how they swim.",
                gate = Gate.Next,
                allow = WATCH_TIME,
                world = WorldRun.Live,
            ),
            Step(
                text = "You just bred a lineage by hand: a clock to free it from the sun, a fuel swap to move by night, a flipped marker to swim better. One genome, iterated - each cell you tapped out carried your latest design forward.",
                gate = Gate.Next,
                allow = WATCH_TIME,
                world = WorldRun.Live,
            ),
        ),
    )

    /** Act III close - the reproduction / colonisation payoff. The Ch9 swimmer is a single, growth-capped
     *  creature: it grows to a small cluster and holds there. The player inserts a REPRODUCE group whose
     *  payload is a SEVER division - once big enough the body buds a daughter FREE (unwelded), a fresh
     *  single-celled founder that drifts off, escapes the size cap, and grows into a whole new swimmer. From
     *  one lone body to a spreading lineage. See [CH10_INIT_GENES]/[CH10_REPRODUCE_GENES]. */
    private fun chapter10Reproduce() = Chapter(
        id = "ch10-reproduce",
        act = 3,
        title = "Spread",
        blurb = "You've bred one fine swimmer. Now make it multiply - a lineage that colonises the world.",
        scenario = CH10_SUBSTRATE,
        grouping = CH10_GROUPING,
        insertableGroups = setOf(GROUP_REPRODUCE_SWIMMER),
        spawnGenome = CH10_FULL_GENES,
        steps = listOf(
            Step(
                text = "Here's your finished swimmer - clock, night-running muscle, the lot. But watch it a while and you'll see the catch: it grows to a small cluster, and stops. One creature, holding its place.",
                gate = Gate.Next,
                allow = WATCH_TIME,
                world = WorldRun.Live,
                detail = "That size cap is the POLARISE morphogen doing double duty - the same marker that tells cells which side they're on also lets the body sense how big it is, so it never outgrows a tidy cluster.",
            ),
            Step(
                text = "A lineage can't live in one body. It has to SPREAD - throw off new founders that swim away and start colonies of their own. Select the swimmer and open its genome.",
                gate = Gate.World("Select the cell", { it.focused != null }),
                allow = LOOK,
                spotlight = Spotlight(hint = "Select the cell"),
            ),
            Step(
                text = "Add the REPRODUCE group. It's a division that SEVERS: once the body is large enough, it buds a daughter that breaks FREE - no weld holding it back - as its own single cell.",
                gate = Gate.World("Add the Reproduce group", met = { (it.focused?.geneCount ?: 0) >= 21 }),
                allow = LOOK,
                spotlight = Spotlight(hint = "+ ADD REPRODUCE, below the groups"),
                detail = "A freed daughter is small again, so it's back under the size cap - free to grow into a whole new swimmer. That's the trick: sever to escape the cap, then regrow. Severing also shoves the two apart, flinging the founder off toward fresh matter.",
            ),
            Step(
                text = "Give it a push to get it going, then speed the world up and watch. As each body reaches full size it flings off a founder - that founder swims away, grows, and buds again. One swimmer becomes a scattered, spreading lineage.",
                gate = Gate.World(
                    "Spread to 20 cells",
                    met = { it.cellCount >= 20 },
                    progress = { it.cellCount.coerceAtMost(20) to 20 },
                ),
                allow = WATCH_TIME,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "Drag to push, then FAST. Tap empty space to re-seed a dead founder."),
                detail = "Each new founder needs matter to grow, so they spread OUTWARD into fresh ground - the same finite-matter budget from Ch4, now driving a whole population across the world.",
            ),
            Step(
                text = "That's the full arc: one cell that could only grow, taught to reproduce, cohere, mend, move, differentiate, keep time - and now to seed new life. From a single speck, a living lineage that spreads on its own.",
                gate = Gate.Next,
                allow = WATCH_TIME,
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
                text = "The world is turning. Watch the daylight sweep across and slide over the cell - it passes into the light, then back into shadow.",
                gate = Gate.Next,
                allow = WATCH,
                world = WorldRun.Live,
            ),
            Step(
                text = "Now select the cell and watch its LIGHT reading in the panel. When daylight covers it the number climbs - that's it feeding. When night passes over, it drops back to zero.",
                gate = Gate.World("Select the cell", { it.focused != null }),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "the cell's LIGHT reading"),
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
