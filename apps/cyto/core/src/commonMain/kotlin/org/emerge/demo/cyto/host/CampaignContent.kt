package org.emerge.demo.cyto.host

import org.emerge.demo.cyto.campaign.Chapter
import org.emerge.demo.cyto.campaign.Control
import org.emerge.demo.cyto.campaign.ControlMask
import org.emerge.demo.cyto.campaign.InputHints
import org.emerge.demo.cyto.campaign.Gate
import org.emerge.demo.cyto.campaign.PlayerAction
import org.emerge.demo.cyto.campaign.GeneSpot
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
import org.emerge.demo.cyto.campaign.ChemBinding
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.ui.GeneGroup
import org.emerge.demo.cyto.ui.GeneKeys
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
    const val GROUP_HOLD_NAME = "Hold Together"
    private const val GROUP_HOLD = GROUP_HOLD_NAME
    private const val GROUP_MOVE = "Move"

    private fun List<Gene>.tagged(group: String): List<Gene> = map { it.copy(group = group) }

    /** Tag a full-autotroph gene by its role: the two grow genes → Grow, the Divide gene → Reproduce. */
    private fun Gene.taggedByRole(): Gene = copy(group = if (this in AUTOTROPH_GROW_ONLY_GENES) GROUP_GROW else GROUP_REPRODUCE)

    /** The reproduction subsystem the grow-only substrate is missing (the break-powered Divide gene) — the
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
    // genome, so a gene card reads "BREAK FUEL (R/G)" instead of "BREAK REDREEN…". `rg` is the
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
            g.taggedByRole().let { if (it.action.type == ActionType.Divide) it.copy(action = it.action.copy(rejectMother = false)) else it }
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
            g.taggedByRole().let { if (it.action.type == ActionType.Divide) it.copy(action = it.action.copy(rejectMother = false)) else it }
        } + HOLD_TOGETHER_GENES)),
        aliases = FUEL_ALIASES,
    )

    // ── Ch8: the Polarise / differentiation genome (Stu's world-8 swimmer lineage) ──────────────────────
    // A bespoke locomotion genome, tagged into functional groups. Unlike the Ch1-7 autotroph it runs a
    // b/bb/gr/br chemistry: `bb` is a morphogen handed WHOLE to one daughter on division (Divide `bb
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
        Break rg : br < 10 & Biomass > 2000 : Divide bb mother across gr : grow
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
        Break rg : br < 20 & gr > 1000 & Biomass > 2000 : Divide bb mother across gr : grow
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
    // group whose payload is a SEVER division (`Divide gr mother sever` = rejectMother): when the body is
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
        Break rg : br < 20 & gr > 1000 & Biomass > 2000 : Divide bb mother across gr : grow
        """.trimIndent(),
    )

    /** The reproduction subsystem Ch10's "+ ADD REPRODUCE" inserts: a reserve-building Convert plus a SEVER
     *  division (`Divide gr mother sever` = rejectMother). Once the body is large it splits off a daughter
     *  that breaks free, unwelded, as its own founder - which then drifts away and grows a new swimmer. */
    private val CH10_REPRODUCE_GENES: List<Gene> = GeneCodec.parse(
        """
        Break br : br > 179 & Biomass < 5000 & rg > 5000 : Convert br : reproduce
        Break rg : Biomass > 3500 & gr > 1000 : Divide gr mother sever along gr : reproduce
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

    /**
     * Which chapters can lead to [id] — every chapter that either names it in `branchesTo` or sits directly
     * before it in the authored flow. The chapter selector unlocks on this (see `CampaignProgress`).
     *
     * A chapter that declares branches does NOT also unlock its list-order neighbour: its successors are
     * exactly the ones it names, or the flat list would quietly re-open the door the branch just closed.
     */
    fun predecessorsOf(id: String, chapters: List<Chapter> = CHAPTERS): List<String> =
        chapters.filterIndexed { i, ch ->
            val declared = ch.branchesTo
            if (declared != null) id in declared else chapters.getOrNull(i + 1)?.id == id
        }.map { it.id }

    // ── Campaign rework (WIP) — a standalone scratch chapter, NOT in ORDER yet ────────────────────────────
    // The new campaign starts far more basic than the old autotroph opening: an empty world the player seeds
    // by hand, one primitive at a time. Kept off the main ORDER so it can be iterated in isolation (launch it
    // directly: `campaign ch00-genesis` in the agent harness) without disturbing the pre-inversion ch1-10.

    /** An empty world — no founders (the player places the first cell themselves) — on a **real day/night
     *  cycle**. Light is a band whose width is `dayTicks / (day+night)`, so 900/2700 is a quarter-torus day
     *  sweeping past: a light-powered CONVERT gene feeds while the band is over the cell and stalls when it
     *  passes, which is the pressure the chapters after Genesis are built on. */
    private val EMPTY_WORLD = CytoScenario.DEFAULT.copy(
        name = "Genesis", founders = emptyList(), dayTicks = 900L, nightTicks = 2700L,
        // A POCKET UNIVERSE — a sixteenth of the default world's area, and the smallest the engine allows
        // (`CytoWorldConfig.applyFrom` floors at 16). The campaign is a story about ONE cell, and a world
        // with room to spare buries it: siblings thriving around the cell the coach is talking about, which
        // is hard to follow while a concept is being explained.
        //
        // Nothing else about the world changes with it, which is why this is a one-line knob rather than a
        // retune. Everything size-coupled is expressed as a RATIO of the torus: `matterBaseRes` is
        // `cellsPerAxis / 16`, so a matter texel keeps the same logical size and a cell's matter footprint
        // (and its exchange dynamics) is invariant; `matterLevel` is per-texel, so the ground still holds
        // the same monomers per unit area; `LIGHT_FALLOFF` is `dayFraction * cellsPerAxis`, so 900/2700
        // keeps the daylight band at the same quarter-of-the-torus it has always been.
        //
        // The population it holds scales with the AREA, which is the point. Measured over five founders of
        // the ch02 genome, each placed in a freshly rebuilt world at a different spot:
        //
        //   worldSize 32:  2251-2345 cells at tick 10000, 1540-1576 at tick 20000
        //   worldSize 16:   542-584  cells at tick 10000,  387-423  at tick 20000
        //
        // A clean 4x, tight across placements. Density is untouched (see the ratio note above) — there is
        // simply a quarter as much room, and a quarter as much ground to grow on.
        //
        // ⚠️ Measure this with SEVERAL founders if you ever retune it. A single scripted run said size 32
        // went extinct by tick 15000 while size 16 bloomed to 900, i.e. exactly backwards: that founder had
        // been dragged to one marginal spot and starved there. One sample of an ecology this stochastic is
        // worth nothing — the same world size spans "extinct" to "2300 cells" depending on where you land.
        worldSize = 16,
        // A whisper of Perlin variation over the monomer soup, so the empty world reads as a living place with
        // subtle regional richness rather than a flat grey — mean-normalised, so no spot is a better start.
        matterNoise = 0.12f,
    )

    /** The hand-authored starter cell: **no genes**, a body of 1000 each of r/g/b (3000 atoms of biomass).
     *  With no maintenance gene it can only decay, so it's the vehicle for teaching why a cell needs genes -
     *  and 3000 is deliberately lean, because the player has to *watch* it decay to the 1000-atom rupture
     *  floor and a bigger body just makes that beat longer. It gets no starting cytoplasm: monomers diffuse
     *  in from the environment on their own, so seeding a reserve would only mask where matter comes from. */
    private val STARTER_CELL_BIOMASS = mapOf("r" to 1000, "g" to 1000, "b" to 1000)

    /**
     * The loosest growth cap `ch01-divide` will accept on the player's CONVERT gene.
     *
     * Division needs `biomass/4` energy in one tick, and in this world's soup a cell's cytoplasm settles
     * near 900 of each monomer however big the body gets — so a bond-powered divide can fund a body of
     * roughly 4 x 900 and no more. Measured: a cell capped at 3000 divides within ~2500 ticks; one capped
     * at 4000 never does. 3500 is the accept-threshold (the coach asks for 3000, and a player who nudges it
     * a little higher should not be told they are wrong), and anything above it genuinely does not work.
     */
    private const val GROWTH_CAP_MAX = 3500
    private const val DIVIDE_BIOMASS_MIN = 2000

    /** Watch mask WITH the SLOW/PAUSE/FAST controls, so the player can fast-forward the slow beats (the
     *  gene-less cell's death; the first gene's biomass climbing back). */
    private val WATCH_SPEED = ControlMask.of(
        Control.Camera, Control.Select, Control.GeneEditor, Control.Overlays, Control.Menu, Control.Speed,
    )

    private fun chapterGenesisScratch() = Chapter(
        id = "ch00-genesis",
        act = 1,
        title = "Genesis",
        blurb = "An empty world. Place a cell, look it over, and watch it live and die.",
        scenario = EMPTY_WORLD,
        startsFreshWorld = true,
        spawnGenome = emptyList(),                 // the placed cell has NO genes
        spawnBiomass = STARTER_CELL_BIOMASS,       // 2000 each of r/g/b = 6000 biomass
        steps = listOf(
            // 1. Camera.
            Step(
                text = "Welcome to Cyto. This is an empty world with nothing alive in it... yet. {pan} to move around and {zoom} to zoom.",
                // One of the two beats here that genuinely has to be an EVENT (see Gate.Did): a camera
                // move leaves no state behind to read, so there is nothing to ask "is it done?" about.
                gate = Gate.Did(PlayerAction.MovedCamera, "Pan or zoom the view"),
                allow = LOOK,
            ),
            // 2. Place a cell.
            Step(
                text = "Now tap anywhere to place an empty cell - a small pocket that independently stores and processes chemicals",
                gate = Gate.World("Place a cell", met = { it.cellCount >= 1 }),
                allow = SPAWN,
                world = WorldRun.Live,
            ),
            // 3. Inspect it.
            Step(
                text = "Tap the cell to select it, revealing its info panel {panelLocation}. This shows its size, its biomass, light exposure, and an empty genome.",
                gate = Gate.World("Select the cell", met = { it.focused != null }),
                allow = LOOK,
            ),
            // 3.5 Inspect it.
            Step(
                text = "Tap the chemistry section to view the detailed chemistry readouts. This cell is floating in a soup of three raw elements - REDOGEN, GREENUM and BLUEON. It also started with all three contributing equally to its biomass.",
                gate = Gate.World("View the chemistry details", met = { it.chemistryOpen }),
                // The panel is dense and this is the player's first errand inside it, so the coach points
                // at the row rather than describing where it is. Substring match: the header carries a count.
                spotlight = Spotlight(target = "CHEMISTRY"),
                allow = LOOK,
            ),
            // 4. Move it.
            Step(
                text = "Drag the cell around - you can move cells wherever you like. If you look closely as you move it, you'll see it exchange its contents with the environment to reach equilibrium.",
                // The other event beat: a dragged cell is where it is, and cells drift on their own, so
                // "has it been moved?" is not a question the world can answer.
                gate = Gate.Did(PlayerAction.MovedCell, "Drag the cell"),
                allow = LOOK,
                world = WorldRun.Live,
            ),
            // 5. Watch it die.
            Step(
                text = "Can you see the cell getting smaller? With no genes, a cell has no way to maintain itself. It slowly degrades into the environment, rupturing when its biomass goes below 1000 units.",
                gate = Gate.World("Wait for the cell to rupture", met = { it.cellCount == 0 }),
                allow = WATCH_SPEED,
                world = WorldRun.Live,
                autoAdvance = true,
            ),
            // 6. Place YOUR cell — a fresh start, this one gets a gene. The rupture the previous step asked
            // for leaves the controller's watched-cell memory set, and this step wants a selection, so the
            // coach used to open by offering to recover from a death the player had just been walked through.
            // Handled in the director now (CampaignDirector.staleWatchedDeath), not by masking it here.
            Step(
                text = "That cell couldn't rebuild itself. Let's fix that. Tap an empty spot to place a fresh cell. Then select it to view its genome.",
                gate = Gate.World("Place and select a cell", met = { it.focused != null }),
                allow = SPAWN,
                world = WorldRun.Live,
                autoAdvance = true,
            ),
            // 7. Choose a starter element and author the first CONVERT gene. This is the pivotal beat: the
            // player makes their organism's first real choice. Frozen so the cell waits, un-decaying, while
            // they author it. The gate fires once the genome holds a CONVERT gene (any chemical).
            Step(
                text = "Tap [+ NEW GENE] on the info panel to add an empty gene. Note that the new gene has no conditions, so it's always active. It currently uses light as its energy source.",
                gate = Gate.World("Give the cell a new gene", met = { it.lineage?.geneCount == 1 }),
                spotlight = Spotlight(target = "+ NEW GENE"),
                allow = LOOK,
                world = WorldRun.Frozen,
            ),
            Step(
                text = "Change your new gene's action from (NOTHING) to (GROW). GROW genes transform their specified chemical target from their cytoplasm [CYT] to their biomass [BIO].",
                gate = Gate.World("Set the new gene to GROW", met = { it.lineage?.convertChem == "" }),
                // The action token on the one gene they just made. Genesis reaches this step with a
                // single-gene genome, so occurrence 1 is unambiguous - it would not be in a grown one.
                spotlight = Spotlight(gene = GeneSpot(ActionType.None, GeneKeys.Part.Action)),
                allow = LOOK,
                world = WorldRun.Frozen,
                autoAdvance = true,
            ),
            Step(
                text = "Tap (NONE) to change your GROW gene's chemical target to either REDOGEN, GREENUM or BLUEON - they're interchangeable, so pick whichever you like.",
                detail = "The picker gives you a harmless heads-up if you name something the cell isn't holding. The gene will show itself as active once it's set up correctly.",
                gate = Gate.World("Complete the GROW gene", met = { it.lineage?.convertChem != null && it.lineage?.convertChem != "" }),
                spotlight = Spotlight(gene = GeneSpot(ActionType.Convert, GeneKeys.Part.Operand)),
                allow = LOOK,
                world = WorldRun.Frozen,
            ),
            // 8. Reaction + payoff. {chem} names the player's own pick (see CampaignDirector.copy), so the
            // coach speaks their choice back to them. Live + speed controls so they can watch it climb.
            Step(
                text = "{chem} it is. Now watch: when exposed to light, your cell locks {chem} into biomass. Its size stops falling and starts to climb - your cell is now able to use sunlight and raw materials to sustain itself indefinitely.",
                altText = "Nothing it is. Now watch: your cell locks nothing into biomass, so its size continues falling and the cell dies. Try again.",
                detail = "See the white particle wandering around inside your cell? That's the GROW gene you added. It's white because it's powered by light.",
                gate = Gate.Next,
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
        ),
    )

    /**
     * Genesis part two: the cell can feed itself, so now it has to copy itself.
     *
     * The chapter is built around one obstacle the player has to be *told*, because the world will not
     * explain it: division is a bulk cost, `biomass/4` energy in a **single tick**, and energy quanta are
     * use-or-lose (`CytoBiologyCore`, the `ActionType.Divide` branch). Light trickles in far below that
     * bar at any real divide size, so a Light-powered DIVIDE gene sits there doing nothing forever - it
     * never accumulates toward the cost. The way through is synthesis: bonding two molecules releases one
     * energy quantum per bond, and the world is full of loose monomers, so a bonding gene can mint the
     * whole burst at once.
     *
     * Continuous with Genesis - no fresh world, no new founders. The player arrives holding the cell they
     * authored, still converting the chemical they picked.
     */
    private fun chapterDivideScratch() = Chapter(
        id = "ch01-divide",
        act = 1,
        title = "Divide",
        blurb = "One cell that feeds itself is a dead end. Teach it to make another.",
        scenario = EMPTY_WORLD,
        startsFreshWorld = false,          // segue in place - this is the world Genesis left behind
        spawnGenome = emptyList(),
        spawnBiomass = STARTER_CELL_BIOMASS,
        steps = listOf(
            // 1. Frame the dead end. Live, so the cell is visibly still growing while they read.
            Step(
                text = "This cell can sustain itself, but it's only one cell. Your new gene has only one host, and if that host cell ruptures then your new species will go extinct.",
                gate = Gate.Next,
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
            // 2. Add the DIVIDE gene, in two beats — one tap each, so the coach can point at the thing the
            // player is being asked to touch *right now*. As one step it kept ringing "+ NEW GENE" after the
            // gene was already added, which is worse than no ring: it says the tap didn't take.
            // Both auto-advance, so a player who does both in one motion never sees the seam.
            Step(
                text = "Give it a way to spread. Tap [+ NEW GENE] again to give it a second gene.",
                gate = Gate.World("Add a second gene", met = { (it.lineage?.geneCount ?: 0) >= 2 }),
                allow = LOOK,
                world = WorldRun.Live,
                autoAdvance = true,
                spotlight = Spotlight(target = "+ NEW GENE"),
            ),
            Step(
                text = "Now change the new gene's action from (NOTHING) to (DIVIDE) - it will split the cell into two daughters, each taking half of the mother's biomass and cytoplasm.",
                gate = Gate.World("Add a DIVIDE gene", met = { it.lineage?.hasDivide == true }),
                allow = LOOK,
                world = WorldRun.Live,
                autoAdvance = true,
                // The blank gene's action token. The only NOTHING on screen: the gene that feeds the cell is
                // a GROW gene by now, and the divide gene's own "RETAINING NOTHING" row only appears once
                // this step is done.
                spotlight = Spotlight(gene = GeneSpot(ActionType.None, GeneKeys.Part.Action)),
            ),
            // 3. Let them watch it NOT work — and pin the blame on SIZE, not on the energy source. This is the
            // first of the two reasons the gene is inert, and it has to come first: a cell that has outgrown
            // its own cytoplasm cannot divide no matter what powers it, so teaching the chemistry switch first
            // leaves the player making the "right" edit and watching nothing happen. Live, so they can see the
            // size climbing while they read.
            Step(
                text = "Nothing happens. The gene is there, but your cell will not divide.  Division costs a quarter of the cell's biomass in energy. Bigger cells pay more, and currently your cell isn't able to cover that cost.",
                gate = Gate.Next,
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
            // 3.5. Cap the growth. The FIRST condition the player ever authors, so the copy walks the taps:
            // the blank clause the ALWAYS token drops in is `CHEM (NONE) > 0`, which needs all three parts
            // changed. Frozen while they author. Gated on the cap being one the cell can actually divide
            // under - see GROWTH_CAP_MAX.
            Step(
                text = "Lets investigate why the cost is so high for your cell. Take another look at the genome you've authored. Your first gene always locks {chem} into biomass, resulting in this situation where division is too expensive.",
                gate = Gate.Next,
                allow = LOOK,
                world = WorldRun.Live,
            ),
            Step(
                text = "Let's add a condition on the GROW gene to limit the cell's growth. Tap (ALWAYS) on the GROW gene to give it a condition, set the left side to (BIO), flip the (>) to (<), and set the number to 3000.",
                gate = Gate.World("Cap the GROW gene", met = {
                    val cap = it.lineage?.convertBiomassCap
                    cap != null && cap <= GROWTH_CAP_MAX && cap >= DIVIDE_BIOMASS_MIN
                }),
                allow = LOOK,
                world = WorldRun.Frozen,
                autoAdvance = true,
                spotlight = Spotlight(gene = GeneSpot(ActionType.Convert, GeneKeys.Part.Condition)),
            ),
            // 4. NOW the second reason, with the first one out of the way: the pivot off Light onto chemistry.
            // Selecting BOND opens the reaction sheet straight away (GeneEditor.Pick.Bond), so switching the
            // source and choosing the pair is one continuous move - hence one step, gated on a COMPLETE
            // reaction (a product, not just a source type).
            Step(
                text = "Now your cell has an upper-bound to its size, so division is nearly within reach. However, sunlight still cannot pay for it - light arrives in a steady trickle, and division requires a lump sum.",
                gate = Gate.Next,
                allow = LOOK,
                world = WorldRun.Live,
            ),
            Step(
                text = "Fortunately for your cell, there is another more abundant energy source available: chemistry. Change the DIVIDE gene's energy source from (USE LIGHT) to (BOND), then choose two chemicals to combine for energy.",
                detail = "Joining two molecules releases energy - one unit per bond made - and this world is full of loose atoms to join. Any pair works, so pick whichever you like.",
                gate = Gate.World("Power DIVIDE by bonding", met = { !it.lineage?.divideProduct.isNullOrEmpty() }),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(gene = GeneSpot(ActionType.Divide, GeneKeys.Part.Source)),
            ),
            // 5. Cells divide, but they do so uncontrollably, resulting in extinction
            Step(
                text = "{bond} it is. Every one your cell makes releases a unit of energy, and it can now raise a quarter of its own biomass in a single moment. Give it a push if it refuses to divide - it may need more materials.",
                altText = "Your gene has no reaction to run, so it makes no energy and the cell cannot divide. Go back and give it two chemicals to join.",
                // The player's OWN cell rupturing is the beat, not the world emptying. Gating on extinction
                // made the step outlast its own lesson: once a lineage gets going the watched cell splits,
                // its daughters rupture, and hundreds of cousins carry on for thousands of ticks with the
                // coach still saying "push stalled cells". The mother keeps her id across a division
                // (CytoBiologySystem's `state.divide`), so this fires when she herself ruptures — the moment
                // the player was told to watch. Extinction still satisfies it: if nothing is alive, hers died.
                gate = Gate.World("Watch your cell divide itself to death", met = { it.watchedCellDied }),
                allow = LOOK,
                world = WorldRun.Live,
                // The die-off IS the beat. Waiting for a Next click would leave this instruction on screen
                // telling the player to push a cell that no longer exists, so the rupture moves the coach on
                // by itself and the next step speaks to the wreckage.
                autoAdvance = true,
            ),
            Step(
                // Whether the world is empty now depends on how fast the collapse ran, so the copy can't
                // assume either. SPAWN stays allowed for the case where it IS empty.
                text = "Oops. It looks like we need some more tuning here. Take a look at your genome again.",
                gate = Gate.World("Select a cell", met = { it.focused != null }),
                allow = SPAWN,
                world = WorldRun.Live,
            ),
            Step(
                text = "Remember how cells with less than 1000 biomass rupture? Dividing cells share their biomass between their daughter cells, so if they divide with less than 2000 biomass then both daughters rupture immediately.",
                gate = Gate.Next,
                allow = LOOK,
                world = WorldRun.Frozen,
            ),
            // 6. Close the chapter POISED on the upgraded split, world still Frozen - the division itself is the next
            // chapter's opening beat, because which chapter that is depends on the pair chosen just before (see
            // `next` below). Ending here also means the player never watches their choice play out before the
            // chapter that is about to be *about* that choice.
            Step(
                text = "Lets fix the over-eager division gene by introducing a condition. Only divide if the cell's biomass is above 2000 units.",
                gate = Gate.World("Add a BIO>2000 condition to the division gene", met = {
                    val minimum = it.lineage?.divideBiomassMinimum
                    minimum != null && minimum >= DIVIDE_BIOMASS_MIN
                }),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(gene = GeneSpot(ActionType.Divide, GeneKeys.Part.Condition)),
            ),
        ),
        // THE BRANCH. Which chapter follows is decided by the pair the player just chose, and they were never
        // asked: a fuel reaction that eats the same monomer the CONVERT gene grows on puts growth and division
        // in competition for one atom, and that lineage lives a completely different life from one that grows
        // on the monomer it does not burn. Each chapter is about the problem its own build actually has.
        //
        // Read off the LINEAGE, not the selected cell: the player may well have sat and watched their colony
        // die out before clicking on, and they should still go to the chapter their genome is about. Only a
        // player who has authored nothing at all falls through to the stable path.
        branchesTo = listOf(BRANCH_PHOTOSYNTHESIS, BRANCH_CONVERSION),
        next = { q -> if (q.lineage?.divideFuelConflicts == true) BRANCH_CONVERSION else BRANCH_PHOTOSYNTHESIS },
    )

    /** Chapters that are launchable by id (agent harness / direct start) but deliberately kept OUT of the
     *  main [CHAPTERS]/[ORDER] campaign flow while they're being built. Order matters: the director segues
     *  from one to the next in place, so Genesis runs straight into Divide. */

    // ── The branch (WIP) ──────────────────────────────────────────────────────────────────────────────────
    // Two chapters, one of which the player will never see on a given run. Which one they get is decided by
    // the fuel pair they chose in `ch01-divide` and nothing else — see its `next`. Both open on the division
    // that chapter left poised, and then diverge immediately, because the two lineages diverge immediately.
    //
    // ⚠️ FIRST DRAFT — the opening beats only, and the voicing wants Stu's ear. Each chapter stops where its
    // fix would be authored; see CAMPAIGN_PLAN.md §12 for what is still open (including a finding that
    // changes what the conversion path's cost actually is).

    const val BRANCH_PHOTOSYNTHESIS = "ch02-photosynthesis"
    const val BRANCH_CONVERSION = "ch02-conversion"

    /**
     * **The stable lineage** — reached by bonding the two monomers the cell does NOT grow on. Nothing
     * competes: growth eats one atom, division burns the other two, and the colony spreads into the hundreds
     * before the world's loose chemistry runs thin. Its only problem is what it leaves behind — every split
     * mints another molecule of a thing the cell has no use for, and it fills up with its own exhaust.
     *
     * The fix this chapter is heading for is photosynthesis: a light-powered gene that breaks the waste back
     * into the pair it came from, returning both atoms to the cytoplasm. Sunlight cannot fund a division, but
     * it is perfectly good for taking something apart.
     */
    private fun chapterPhotosynthesisScratch() = Chapter(
        id = BRANCH_PHOTOSYNTHESIS,
        act = 1,
        title = "Exhaust",
        blurb = "Your cells divide and divide. Look at what they are leaving behind.",
        scenario = EMPTY_WORLD,
        startsFreshWorld = false,
        // Declared rather than left to list order: the OTHER branch sits next in the flat list, and falling
        // through to it would hand this player the chapter their genome is not about.
        branchesTo = listOf(NIGHT_SHIFT),
        next = { NIGHT_SHIFT },
        spawnGenome = emptyList(),
        spawnBiomass = STARTER_CELL_BIOMASS,
        steps = listOf(
            Step(
                text = "Let it run. If division is stalled, move your cell around to give it access to more resources.",
                gate = Gate.World("Divide into two cells", met = { it.cellCount >= 2 }),
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
            // 1. The payoff that the old ch01 ending used to carry, now where it belongs - after the split,
            // in the chapter that is about what the split costs.
            Step(
                text = "Two stable cells from one. Both carry your genome, so both will do this again - and their daughters after them. You grew on {chem} and you burn the other two for the energy to split, so nothing your cells do gets in their own way. This lineage will fill the world.",
                gate = Gate.World("Grow the colony", met = { it.cellCount >= 8 }, progress = { it.cellCount to 8 }),
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
            // 2. Name the cost. The molecule is IN the cells and visible in the chemistry table, so this is a
            // "look at the thing" beat, not a claim the player has to take on trust.
            Step(
                text = "Now select one and look at what it is holding. Every division your cells have ever paid for has left a molecule of {bond} behind, and they have no use for it. They are filling up with their own waste products.",
                detail = "Nothing is destroyed in this world, only rearranged. The atoms in that {bond} are the same atoms your cells started with - they are simply locked into a shape the genome has no gene for.",
                gate = Gate.World("Select a cell and read its chemistry", met = { it.chemistryOpen }),
                spotlight = Spotlight(target = "CHEMISTRY"),
                allow = LOOK,
                world = WorldRun.Frozen,
            ),
            // Not a metaphor: a cell's wear accumulator gains `cytoplasm + biomass` every tick
            // (CytoBiologyCore.degrade), so hoarded {bond} is charged for exactly like structure is, while
            // paying for nothing. Measured on this genome, it settles around 700 units per cell and stays
            // there - roughly a third of what a capped cell is carrying.
            Step(
                text = "And it never leaves. Cells shed a little of themselves every tick just by existing, and the bigger the load they carry the faster that goes - so a cell full of {bond} pays maintenance on a molecule it cannot use. Left alone this only gets heavier.",
                gate = Gate.Next,
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
            Step(
                text = "Nothing in the genome takes {bond} back apart, so let's write something that does. Tap [+ NEW GENE] on the cell you have selected.",
                // `>=`, not `==`: a player carrying a spare blank gene from earlier would otherwise be stuck
                // here with no way to satisfy a goal they have already met.
                gate = Gate.World("Give a cell a new gene", met = { (it.lineage?.geneCount ?: 0) >= 3 }),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(target = "+ NEW GENE"),
            ),
            Step(
                text = "Set the new gene's action to [BREAK] {bond} using light. The trickle of sunlight cells receive in the day could never pay for division, but it can be used to gradually break {bond} waste back into usable atoms.",
                detail = "This is the mirror of the reaction your division gene runs. That gene joins the pair to release energy, and this one spends energy to split it again.",
                gate = Gate.World("Update the gene to BREAK {bond}", met = { it.lineage?.hasPhotosynthesis == true }),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(gene = GeneSpot(ActionType.None, GeneKeys.Part.Action)),
            ),
            // The payoff, and the point of gating it on the world rather than a click: the {bond} reading in
            // the panel visibly drains away. Measured, same genome with and without this gene: ~700 units
            // held and climbing, against 1. The threshold is deliberately loose - the goal is "visibly
            // falling", not a race to zero.
            Step(
                text = "Now watch the cell you have selected. Every division still mints a molecule of {bond} - but daylight is pulling them apart again as fast as they appear, and the atoms go straight back into the pool your cells grow from.",
                detail = "Nothing here is new material. Your lineage is simply recycling the same atoms using energy input from the sun.",
                gate = Gate.World(
                    "Clear the {bond} out of a cell",
                    met = { q ->
                        val waste = q.lineage?.divideProduct
                        val held = q.focused?.cytoplasm
                        !waste.isNullOrEmpty() && held != null && (held[waste] ?: 0) < 100
                    },
                ),
                allow = LOOK,
                world = WorldRun.Live,
            ),
        ),
    )

    const val NIGHT_SHIFT = "ch03-nightshift"

    /**
     * **The night shift** — the chapter that gets the lineage off daylight for its growth.
     *
     * Everything the player has built so far runs on the sun, and the sun is off for three quarters of this
     * world's cycle (`EMPTY_WORLD`: 900 ticks of day, 2700 of night). A Light-powered gene earns nothing in
     * the dark while degradation carries on regardless, so a cell spends most of every cycle going backwards
     * and the day is merely long enough to undo it. Worse, `quantaShare` divides a cell's light between every
     * active Light gene — so the recycling gene the last chapter added is now halving the growth gene's
     * daylight, and vice versa.
     *
     * The fix is to move CONVERT onto the same `BOND` reaction that already funds division. The two halves of
     * the day then do different jobs: overnight the cell burns its monomer pair into {bond} to keep building,
     * and by day the light gene — now holding the cell's whole quanta share — breaks that {bond} back into
     * the pair. Measured over one night on this genome (one cell, biomass at its 3000 cap):
     *
     *   - CONVERT on Light: 2999 -> 2042 biomass (-32%), and no {bond} ever forms.
     *   - CONVERT on `BOND`: 2999 -> 2637 (-12%), {bond} climbs to ~767 by dawn and is back to 1 by morning.
     *
     * That sawtooth is the chapter's payoff, and both of its world-gates read it directly off the panel.
     *
     * It is also where the two branches converge: the competition path arrives at the same shape by its own
     * route (a CONVERT gene powered by the reaction that used to be its waste), so from here on both lineages
     * are grow / divide / recycle and Act II can teach subsystems instead of individual genes.
     */
    private fun chapterNightShiftScratch() = Chapter(
        id = NIGHT_SHIFT,
        act = 1,
        title = "Night Shift",
        blurb = "Your cells only earn a living in daylight. Most of this world is night.",
        scenario = EMPTY_WORLD,
        startsFreshWorld = false,
        // Declared, not left to list order - the conversion branch sits further down the same list.
        branchesTo = listOf(LEFTOVERS),
        next = { LEFTOVERS },
        spawnGenome = emptyList(),
        spawnBiomass = STARTER_CELL_BIOMASS,
        steps = listOf(
            // The problem, watched rather than asserted. The gate is the night dip itself: a capped cell sits
            // at 3000 and bottoms out near 2000 before dawn, so 2500 is comfortably inside the swing whatever
            // size the player's cell settled at.
            Step(
                text = "Keep an eye on your cell's size as night falls. Everything you have built runs on sunlight, and this world is dark for three quarters of its cycle. Your cells only earn in the day, but they degrade around the clock.",
                detail = "Degradation does not stop at night. A cell sheds a little of itself every tick, whether or not anything is coming in.",
                gate = Gate.World(
                    "Watch a cell lose ground overnight",
                    met = { q -> q.focused?.let { it.biomass in 1..2499 } == true },
                ),
                allow = LOOK,
                world = WorldRun.Live,
            ),
            Step(
                text = "There is a second cost here that you cannot see. Your two light genes draw on the same daylight and split it between them, so the gene that grows your cell and the gene that clears its waste are each running at half strength.",
                gate = Gate.Next,
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
            // The edit. `convertProduct == divideProduct` is the reconvergence reading: the CONVERT gene is
            // powered by the same reaction the DIVIDE gene is.
            Step(
                text = "Now that daylight can clear {bond} back out of the cytoplasm, it is safe to spend it. Change the GROW gene's energy source from (USE LIGHT) to (BOND), and give it the same pair your DIVIDE gene uses. Growth then runs on chemistry, at any hour.",
                detail = "Same move you made on the division gene, and for the same reason. Light arrives when it arrives. A bond is there whenever the cell has the atoms for it.",
                gate = Gate.World(
                    "Power the GROW gene with {bond}",
                    met = { q ->
                        val fuel = q.lineage?.convertProduct
                        !fuel.isNullOrEmpty() && fuel == q.lineage?.divideProduct
                    },
                ),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(gene = GeneSpot(ActionType.Convert, GeneKeys.Part.Source)),
            ),
            // The payoff: the sawtooth. Overnight {bond} piles up (measured ~767 by dawn) because nothing is
            // breaking it in the dark; the threshold is set well under that so an ordinary night clears it.
            Step(
                text = "Watch it through another night. Your cell keeps building in the dark now, and every unit it builds leaves another {bond} behind. Nothing breaks {bond} at night, so it piles up until morning.",
                gate = Gate.World(
                    "Let {bond} build up overnight",
                    met = { q ->
                        val waste = q.lineage?.divideProduct
                        !waste.isNullOrEmpty() && (q.focused?.cytoplasm?.get(waste) ?: 0) > 200
                    },
                ),
                allow = LOOK,
                world = WorldRun.Live,
            ),
            Step(
                text = "Now wait for morning. The light gene has the whole day to itself, and it takes that pile of {bond} apart and hands the atoms back.",
                detail = "Your cells no longer depend on when the light arrives. They store the day as {bond} and spend it overnight.",
                gate = Gate.World(
                    "Clear the night's {bond} by morning",
                    met = { q ->
                        val waste = q.lineage?.divideProduct
                        val held = q.focused?.cytoplasm
                        !waste.isNullOrEmpty() && held != null && (held[waste] ?: 0) < 100
                    },
                ),
                allow = LOOK,
                world = WorldRun.Live,
            ),
            Step(
                text = "Three genes, and between them a cell that grows around the clock, pays for its own division, and clears up after itself. There is one thing left that it is still throwing away.",
                gate = Gate.Next,
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
        ),
    )

    const val LEFTOVERS = "ch04-leftovers"

    /**
     * **Leftovers** — the photosynthesis path's last chapter, and where it converges on the shape the
     * competition path already reached: a CONVERT gene growing on the two-atom molecule the rest of the
     * genome runs on, rather than on a bare monomer.
     *
     * Built as the same kind of arc the competition players walked: a change that is strictly worse on its
     * own, and only pays once a second change lands beside it.
     *
     *  1. Point CONVERT at {bond}. Two atoms per op instead of one.
     *  2. Watch the lineage die. An action reads its input from the tick-start snapshot, so CONVERT needs
     *     {bond} already sitting in the cytoplasm - and the recycling gene added two chapters ago clears the
     *     cytoplasm right out every morning. Both genes are doing exactly what they were told, and between
     *     them the cell never gets to grow at all. Measured: CONVERT goes inert on the spot and the cell
     *     ruptures about 4,200 ticks later.
     *  3. Put a floor under the recycler so it only sheds the surplus, and the same two genes cooperate.
     *     Measured with a floor of 100, biomass comes back to its 3000 cap and holds there.
     *
     * ⚠️ It holds for about 2,100 ticks and then declines: every CONVERT op locks a whole {bond} into
     * biomass, and biomass is not something the recycler can reach, so the pair drains out of circulation
     * faster than the membrane brings it back. A lower growth cap or a richer world would both buy it back.
     * The chapter's own beats are all measured and reachable - it is the long tail that is unfinished.
     */
    private fun chapterLeftoversScratch() = Chapter(
        id = LEFTOVERS,
        act = 1,
        title = "Leftovers",
        blurb = "Your cells throw away the best thing they make.",
        scenario = EMPTY_WORLD,
        startsFreshWorld = false,
        // Where the two branches MERGE: both tails reach the same three-gene shape, so both go on to the
        // chapter about what that shape does to the world around it.
        branchesTo = listOf(RECLAIM),
        next = { RECLAIM },
        spawnGenome = emptyList(),
        spawnBiomass = STARTER_CELL_BIOMASS,
        steps = listOf(
            Step(
                text = "Look at what your cells are built from. {chem} is a single-atom-chemical. {bond} is two of them already joined together, and your cells make it themselves every time they pay for anything.",
                gate = Gate.Next,
                allow = LOOK,
                world = WorldRun.Live,
            ),
            Step(
                text = "Longer chemicals contribute more to biomass than shorter ones, so lets make the switch.",
                gate = Gate.Next,
                allow = LOOK,
                world = WorldRun.Live,
            ),
            Step(
                text = "Update the GROW gene to change its target chemical from {chem} to {bond}.",
                gate = Gate.World(
                    "Point the GROW gene at {bond}",
                    met = { q ->
                        val target = q.lineage?.convertChem
                        !target.isNullOrEmpty() && target == q.lineage?.divideProduct
                    },
                ),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(gene = GeneSpot(ActionType.Convert, GeneKeys.Part.Operand)),
            ),
            // The die-off. Measured: CONVERT goes inert immediately and the cell ruptures ~4,200 ticks later.
            //
            // Gated on the WATCHED cell, not on an empty world. The edit lands on one cell, and its siblings
            // carry on running the genome they already had - so by this point in a healthy chapter there are
            // hundreds of them and `cellCount == 0` would simply never fire. autoAdvance for the same reason
            // ch01's die-off has it: the instruction stops being true the moment the gate does.
            Step(
                text = "Now let it run, and keep your eye on the cell you just edited.",
                gate = Gate.World("Watch what happens to it", met = { it.watchedCellDied }),
                allow = SPAWN,
                world = WorldRun.Live,
                autoAdvance = true,
            ),
            Step(
                text = "Worse, not better. That cell starved with food all around it. Let's work out why.",
                gate = Gate.World("Select a cell", met = { it.focused != null }),
                allow = SPAWN,
                world = WorldRun.Live,
            ),
            Step(
                text = "A gene can only work with what is already in the cytoplasm. Your GROW gene wants {bond} to build from - and your BREAK gene spends all day clearing out every last unit of it. Both genes are doing their job, but between them the cell can't grow anymore.",
                gate = Gate.Next,
                allow = LOOK,
                world = WorldRun.Frozen,
            ),
            // The fix. 100 is what the sweep landed on: high enough that CONVERT always finds something to
            // work with, low enough that the recycler still frees up most of the pair each morning. The gate
            // takes anything in a sane band rather than the exact number, so a player who reasons their own
            // way to 150 is not told they are wrong.
            Step(
                text = "Lets give the BREAK gene a condition so it only clears the surplus. Tap (ALWAYS) on the BREAK gene, set the left side to {bond}, and set the right side to 100. Now it leaves a small reserve behind for the GROW gene to use.",
                gate = Gate.World(
                    "Block the BREAK gene when {bond} is low",
                    met = { q -> q.lineage?.recycleReserve?.let { it in 1..1000 } == true },
                ),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(gene = GeneSpot(ActionType.BreakBond, GeneKeys.Part.Condition)),
            ),
            // The payoff, read rather than waited out. (It used to gate on biomass >= 2800 — measured, a
            // capped cell comes back to 2999 and holds — but that made the beat a wait.)
            Step(
                text = "Watch it come back. The same two genes, and now the one feeds the other - the day's recycling leaves enough behind for growth.",
                gate = Gate.Next,
                allow = LOOK,
                world = WorldRun.Live,
            ),
        ),
    )

    /**
     * **The competing lineage** — reached by choosing a fuel pair that includes the very monomer the CONVERT
     * gene grows on. Growth and division now bid for the same atom every tick. It works, briefly: the cell
     * divides, and its daughters divide, and then the colony stalls and dies back as the shared atom runs out
     * from under both genes at once.
     *
     * The fix this chapter is heading for is to stop treating the waste as waste: a second CONVERT gene that
     * locks the fuel molecule into biomass recovers BOTH its atoms, and a two-atom molecule is worth more per
     * op than the monomer the cell started on. The conflict becomes the point.
     */
    private fun chapterConversionScratch() = Chapter(
        id = BRANCH_CONVERSION,
        act = 1,
        title = "Competition",
        blurb = "Your cells grow and divide on the same atom. Only one of them can win.",
        scenario = EMPTY_WORLD,
        startsFreshWorld = false,
        // Declared, not left to list order - Supply is the back half of this arc.
        branchesTo = listOf(BRANCH_SUPPLY),
        next = { BRANCH_SUPPLY },
        spawnGenome = emptyList(),
        spawnBiomass = STARTER_CELL_BIOMASS,
        steps = listOf(
            Step(
                text = "Let it run. If division is stalled, move your cell around to give it access to more resources.",
                gate = Gate.World("Divide into two cells", met = { it.cellCount >= 2 }),
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
            // The choice, named back to them. This is the beat the whole branch exists for: they were never
            // asked a question, so the coach has to show them the answer they gave.
            Step(
                text = "Two cells, from one. But look closely at what you built - Your cells grow by locking {chem} into biomass, and they pay for every division by bonding {bond} - which takes that same {chem} back out of the cytoplasm. Both genes are competing for the same {chem} reserve.",
                detail = "Neither gene is wrong. They simply want the same thing, and there is only so much of it coming in through the membrane.",
                gate = Gate.Next,
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
            Step(
                text = "There is a way out of this, and it isn't to choose a different bond - at least not for division. Cells in cyto can use any chemical compound for their biomass, as long as they have a gene to support it. In fact, longer chemicals contribute more to biomass than shorter ones.",
                gate = Gate.Next,
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
            Step(
                text = "Update your GROW gene to change its target chemical from {chem} to {bond}. ",
                gate = Gate.World("Update the first gene to GROW {bond}", met = {
                    it.lineage?.convertChem != null && it.lineage?.convertChem != ""
                            && it.lineage?.convertChem == it.lineage?.divideProduct
                }),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(gene = GeneSpot(ActionType.Convert, GeneKeys.Part.Operand)),
            ),
            // The chapter ends here, on the die-off. It auto-advances, so the collapse itself is the segue
            // into `ch03-supply` rather than something the player has to click past.
            Step(
                text = "This solves three problems at once: 1. It removes internal genetic competition for {chem}. 2. It deals with the {bond} byproduct of divide. 3. It upgrades the biomass to use a more efficient chemical.",
                // Same reason as Leftovers' die-off: the edit lands on ONE cell, and an empty-world gate does
                // not fire while its unedited siblings are still going.
                gate = Gate.World("What could go wrong?", met = { it.watchedCellDied }),
                allow = WATCH_SPEED,
                world = WorldRun.Live,
                autoAdvance = true,
            ),
        ),
    )

    const val BRANCH_SUPPLY = "ch03-supply"

    /**
     * **Supply** — the back half of the competition arc, split out because it teaches a different thing.
     *
     * `ch02-conversion` ends on a lineage that has just been retargeted onto {bond} and immediately died for
     * it. That is one lesson (growth and division can want the same atom) and this is another: the cell now
     * depends on a molecule it only ever produced as a byproduct of dividing, so it has to make the stuff on
     * purpose. The fix puts the same bond reaction underneath the CONVERT gene as its energy source.
     *
     * Kept as its own chapter because the die-off is a genuine ending - the player's colony collapses - and
     * because arriving at "you now depend on your own waste product" as an epilogue to a chapter they thought
     * they had finished buries the idea.
     */
    private fun chapterSupplyScratch() = Chapter(
        id = BRANCH_SUPPLY,
        act = 1,
        title = "Supply",
        blurb = "Your cells live on a molecule they only make by accident.",
        scenario = EMPTY_WORLD,
        startsFreshWorld = false,
        // Declared, not left to list order - Locked Up is where this path rejoins the other one.
        branchesTo = listOf(BRANCH_LOCKED_UP),
        next = { BRANCH_LOCKED_UP },
        spawnGenome = emptyList(),
        spawnBiomass = STARTER_CELL_BIOMASS,
        steps = listOf(
            // TODO block the coach extinctionOffer and watchedCellOffer here, and introduce the copy for adding a new cell into this step.
            Step(
                text = "Back to the drawing board. Lets take another look at the genome.",
                gate = Gate.World("Select a cell", met = { it.focused != null }),
                allow = SPAWN,
                world = WorldRun.Live,
            ),
            Step(
                text = "The cell bonds {bond} to divide, but that's its only way of getting more {bond}. Now that the cell relies on {bond} for growth, we need to make a more reliable way to acquire it.",
                gate = Gate.Next,
                allow = LOOK,
                world = WorldRun.Frozen,
            ),
            Step(
                text = "Lets change the energy source of the GROW gene to bond {chem}. This way the cell produces the {chem} it needs by using the bond as both the substrate and the energy source for growth.",
                gate = Gate.World("Update the first gene to BOND {bond} as its energy source", met = {
                    it.lineage?.convertChem != null && it.lineage?.convertChem != ""
                            && it.lineage?.convertChem == it.lineage?.convertProduct
                }),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(gene = GeneSpot(ActionType.Convert, GeneKeys.Part.Source)),
            ),
            Step(
                text = "Now we're cooking.",
                gate = Gate.Next,
                allow = LOOK,
                world = WorldRun.Live,
            ),
        ),
    )

    const val BRANCH_LOCKED_UP = "ch04-lockedup"

    /**
     * **Locked Up** — the competition path's last chapter, and where it rejoins the other one.
     *
     * By the end of `ch03-supply` the lineage makes {bond} and spends {bond}, and the two rates match, so
     * whatever it happened to be holding at the time just sits there. Those atoms are not lost, they are
     * unreachable: nothing in the genome takes {bond} apart, and a cell pays maintenance on everything it
     * carries whether it can use it or not (`CytoBiologyCore.degrade` charges wear on cytoplasm as well as
     * biomass).
     *
     * The fix is the recycling gene the photosynthesis path already has - a light-powered BREAK of {bond},
     * with a reserve so it does not strip the CONVERT gene of the standing stock it needs to fire at all.
     * Taught directly here rather than through the die-off arc `ch04-leftovers` uses: that chapter's lesson
     * is about paired changes, this one's is about reclaiming what is already yours.
     *
     * Measured on the Supply end state, same world, from the moment the gene lands:
     *
     *   - without it: {bond} frozen at 586 for the rest of the cell's life, dead in ~5,000 ticks.
     *   - with it:    {bond} 586 -> 95, biomass RECOVERS 2700 -> 2975 before declining, dead in ~6,600.
     *
     * Both still decline in the long run, for the reason recorded on [chapterLeftoversScratch]: every CONVERT
     * op locks a whole pair into biomass, which no recycler can reach.
     *
     * After this chapter both lineages are grow / divide / recycle - the same three jobs, differing only in
     * which chemical each grows on, which is the granularity gene groups care about.
     */
    private fun chapterLockedUpScratch() = Chapter(
        id = BRANCH_LOCKED_UP,
        act = 1,
        title = "Locked Up",
        blurb = "Your cells are carrying atoms they cannot spend.",
        scenario = EMPTY_WORLD,
        startsFreshWorld = false,
        // The other half of the merge - see the note on `chapterLeftoversScratch`.
        branchesTo = listOf(RECLAIM),
        next = { RECLAIM },
        spawnGenome = emptyList(),
        spawnBiomass = STARTER_CELL_BIOMASS,
        steps = listOf(
            // Measured: the standing pile sits at 586 and never moves, because the genome makes {bond} and
            // spends it at the same rate. 300 is comfortably inside that whatever the player's cell settled at.
            Step(
                text = "Select one of your cells and look at what it is holding. There is a pile of {bond} locked in there that never goes down.",
                gate = Gate.World(
                    "Find the {bond} your cells are sitting on",
                    met = { q ->
                        val waste = q.lineage?.divideProduct
                        !waste.isNullOrEmpty() && (q.focused?.cytoplasm?.get(waste) ?: 0) > 300
                    },
                ),
                allow = LOOK,
                world = WorldRun.Live,
            ),
            Step(
                text = "Your cells make {bond} and spend {bond} at the same rate, so whatever they were holding when you made that change is still sitting there. Those atoms are not gone, they are just out of reach - nothing in the genome takes {bond} apart.",
                detail = "And they are not free to hold. A cell pays maintenance on everything it carries, used or not, so that pile is costing it every tick.",
                gate = Gate.Next,
                allow = LOOK,
                world = WorldRun.Frozen,
            ),
            Step(
                text = "Lets get those atoms back. Tap [+ NEW GENE] on the cell you have selected.",
                gate = Gate.World("Give a cell a new gene", met = { (it.lineage?.geneCount ?: 0) >= 3 }),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(target = "+ NEW GENE"),
            ),
            Step(
                text = "Set the new gene's action to [BREAK] {bond} using light. Sunlight could never pay for a division, but it is enough to take one bond apart.",
                gate = Gate.World("Update the gene to BREAK {bond}", met = { it.lineage?.hasPhotosynthesis == true }),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(gene = GeneSpot(ActionType.None, GeneKeys.Part.Action)),
            ),
            // Taught up front rather than through a die-off: without a floor this gene strips the cytoplasm
            // bare, and CONVERT needs {bond} present at the start of a tick to fire at all.
            Step(
                text = "It needs a condition, or it will clear out every last unit and leave your GROW gene with nothing to build from. Tap (ALWAYS) on the new gene, set the left side to {bond}, and set the right side to 100.",
                gate = Gate.World(
                    "Block the BREAK gene when {bond} is low",
                    met = { q -> q.lineage?.recycleReserve?.let { it in 1..1000 } == true },
                ),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(gene = GeneSpot(ActionType.BreakBond, GeneKeys.Part.Condition)),
            ),
            // The payoff, measured: the pile drains 586 -> 95 and biomass climbs 2700 -> 2975 on the way.
            Step(
                text = "Now watch it. The pile comes down through the day, and every bond it breaks hands two atoms back to the cytoplasm. Your cell gets bigger on nothing but what it was already carrying.",
                gate = Gate.World(
                    "Reclaim the {bond}",
                    met = { q ->
                        val waste = q.lineage?.divideProduct
                        val held = q.focused?.cytoplasm
                        !waste.isNullOrEmpty() && held != null && (held[waste] ?: 0) < 200
                    },
                ),
                allow = LOOK,
                world = WorldRun.Live,
            ),
            Step(
                text = "Three genes: one to grow, one to divide, and one to put the leftovers back. That is the same shape every lineage that gets this far ends up with, whichever way it got here.",
                gate = Gate.Next,
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
        ),
    )

    const val RECLAIM = "ch05-reclaim"

    /**
     * **Spent** — where the two branches MERGE (CAMPAIGN_PLAN.md §12.4), and the first chapter about the
     * world rather than the cell.
     *
     * Both tails arrive at the same three-gene shape: grow on {bond}, divide on {bond}, break {bond} back
     * down in the light. It works, and it is still terminal, for a reason nothing so far has shown them:
     *
     *  - `degrade` sheds the cell's most abundant BIOMASS molecule **whole and unsplit** into its own
     *    footprint - and by now that molecule is {bond}, a two-atom molecule.
     *  - `passiveEnvExchange` transfers a species across the membrane only if it is a **monomer** or carries
     *    an import/export bias (`ib != 0 || eb != 0 || atomCount == 1`).
     *
     * So a colony steadily converts the world's free monomers into a molecule no cell can take back in. The
     * matter is not destroyed and it is not far away - it is lying directly under them, unreachable. This is
     * the asymmetry §12.2 went looking for and concluded was absent; that note checked `canDiffuseIn`, which
     * governs cell-to-cell weld diffusion, not the environment junction.
     *
     * **Why this one starts a fresh world**, against the campaign's continuous-world rule: the chapter is
     * about a world being consumed from a virgin state, and both predecessors hand it a world that has
     * already been consumed. The curve IS the lesson, so it has to be watched from the beginning.
     *
     * Measured on the default world, one founder, the 3-gene genome: 6,241 cells by tick 8,000, then
     * collapse to extinction by ~21,000. Free `b` and `g` fall 32.8M -> 6.0M while {bond} climbs 0 -> 26.8M;
     * `r`, which this lineage never touches, sits at 32.8M throughout - the world is still full of food, just
     * not food these cells can eat. With the light-powered import gene the same world holds ~11,000 cells
     * indefinitely (still capped at tick 30,000).
     *
     * Those figures are the DEFAULT world. This chapter runs in [EMPTY_WORLD], a sixteenth of that area, so
     * the gates below use the pocket universe's own numbers, measured the same way (one founder, this
     * chapter's scenario, the genome authored onto the placed cell):
     *
     *              t2k   t4k   t6k   t9k   t13k  t18k  t24k
     *   3 genes:   155   474   466    25     0     0     0     - peak at 4k, extinct by 13k
     *   + import:  185   567   595   613   646   695   710     - climbs, then holds
     */
    private fun chapterReclaimScratch() = Chapter(
        id = RECLAIM,
        act = 1,
        title = "Spent",
        blurb = "Your cells strip the world, and then starve in it.",
        scenario = EMPTY_WORLD,
        // The one deliberate break in the continuous world - see the chapter doc.
        startsFreshWorld = true,
        // The player's own genome, whichever branch they walked, carried in by `lastAuthoredGenome` (§13).
        spawnCopiesHeldCell = true,
        spawnGenome = emptyList(),
        spawnBiomass = STARTER_CELL_BIOMASS,
        // Into the rehomed Act II. The efficiency chapter that was going to sit here is parked - its premise
        // did not survive measurement (CAMPAIGN_PLAN.md 14) - and multicellularity is the next real step.
        branchesTo = listOf(REHOMED_HOLD),
        next = { REHOMED_HOLD },
        steps = listOf(
            Step(
                text = "A clean world, and the genome you built. Tap an empty space to put one cell into it.",
                detail = "Same three genes you finished the last chapter with. Nothing here is different except the world.",
                gate = Gate.World("Place a cell", met = { it.cellCount >= 1 }),
                allow = SPAWN,
                world = WorldRun.Live,
            ),
            // The boom. Peaks at 474 around tick 4,000, so 200 is comfortably inside it whichever branch's
            // chemistry the player brought.
            Step(
                text = "Now let it run, and let it run fast. One cell becomes two, then hundreds. This is the genome working exactly as you designed it.",
                gate = Gate.World("Spread across the world", met = { it.cellCount >= 200 }),
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
            // The crash. Gating on an EMPTY world also suppresses the extinction offer (goalIsExtinction),
            // so this copy stands instead of being replaced by a recovery prompt - the death is the beat.
            Step(
                text = "Keep watching. Something is going wrong, and it is not a mistake you made - this is what that genome does when you give it long enough.",
                detail = "It is not starving for want of anything you can see. The world looks as full as it ever did.",
                gate = Gate.World("Watch the lineage die out", met = { it.cellCount == 0 }),
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
            Step(
                text = "All of it, gone - from one cell to thousands to nothing. And the world is not empty. It is still holding almost everything your cells were ever made of.",
                gate = Gate.Next,
                allow = WATCH_SPEED,
                world = WorldRun.Frozen,
            ),
            // The reveal. The campaign cannot see the LAYERS sheet, so these two beats are read-and-look
            // rather than gated - the sheet's selection is not in any world reading.
            Step(
                text = "Open [LAYERS] and look at the MATTER list. It shows every chemical in the world, and how much of it there is. Pick {bond}.",
                detail = "The ground is drawn from that one chemical now. Bright means plenty of it lying there.",
                gate = Gate.Next,
                allow = WATCH_SPEED,
                world = WorldRun.Frozen,
                spotlight = Spotlight(target = "LAYERS"),
            ),
            Step(
                text = "Now pick one of the two single-atom chemicals {bond} is made of. The world goes dark - your cells took nearly all of it. Every cell that ever died here shed its {bond} whole, and {bond} is two atoms locked together.",
                detail = "A single-atom chemical drifts through a membrane on its own. A bonded one does not - nothing crosses in unless a gene reaches out and pulls it. Your cells spent the world and then could not pick it back up.",
                gate = Gate.Next,
                allow = WATCH_SPEED,
                world = WorldRun.Frozen,
            ),
            Step(
                text = "So lets teach them to pick it up. Tap an empty space to put a cell back in, and select it.",
                gate = Gate.World("Select a cell", met = { it.focused != null }),
                allow = SPAWN,
                world = WorldRun.Live,
            ),
            // Two beats, one tap each, for the reason the divide chapter's gene split: a single step ringing
            // "+ NEW GENE" keeps ringing it after the gene exists, which reads as "that didn't work".
            Step(
                text = "Add a fourth gene.",
                gate = Gate.World("Give the cell a fourth gene", met = { (it.lineage?.geneCount ?: 0) >= 4 }),
                allow = LOOK,
                world = WorldRun.Frozen,
                autoAdvance = true,
                spotlight = Spotlight(target = "+ NEW GENE"),
            ),
            Step(
                text = "Set its action to [IMPORT] {bond}. That is the gene that reaches out through the membrane and takes it back.",
                gate = Gate.World("Update the new gene to IMPORT {bond}", met = { it.lineage?.importsExhaust == true }),
                allow = LOOK,
                world = WorldRun.Frozen,
                // Hint, not a target: by this chapter the genome carries a DIVIDE gene, whose card writes its
                // own "RETAINING NOTHING" row ABOVE the blank gene appended at the end. Two NOTHINGs, and the
                // wrong one comes first - exactly the count-the-matches fragility a target must not rely on.
                spotlight = Spotlight(gene = GeneSpot(ActionType.None, GeneKeys.Part.Action)),
            ),
            // Light, deliberately: a cell in a spent world has no spare atoms to bond with, so a chemistry-
            // powered import cannot get started from the floor. Chemistry is ch06's job, once it can afford it.
            Step(
                text = "Power it with light. These cells are starting from nothing in a world with nothing loose in it - they have no atoms spare to pay chemistry with, and sunlight asks for none.",
                gate = Gate.World("Power the IMPORT gene with light", met = { it.lineage?.importOnChem == false }),
                allow = LOOK,
                world = WorldRun.Frozen,
            ),
            Step(
                text = "And give it a condition, or it will spend all day hauling in {bond} the cell already has. Tap (ALWAYS), set the left side to {bond}, and set the right side to 200.",
                detail = "The recycling gene empties the cytoplasm below 100. This one fills it back to 200. Between them the cell holds a working stock and stops.",
                gate = Gate.World(
                    "Stop the IMPORT gene when {bond} is high",
                    met = { q -> q.lineage?.importCeiling?.let { it in 1..1000 } == true },
                ),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(gene = GeneSpot(ActionType.Import, GeneKeys.Part.Condition)),
            ),
            Step(
                text = "Now let it run again. Same world, same three genes, and one more that puts back what the others throw away.",
                detail = "It stops climbing, and then it just holds. Nothing here is growing forever - it is living off what it already spent.",
                // 400 rather than 200: the dying lineage grazes 474 at its peak, so a lower bar could be met
                // by a colony that is about to collapse. With the import gene it is past 400 by tick 4,000 and
                // still climbing at 24,000.
                gate = Gate.World("Hold a living colony", met = { it.cellCount >= 400 }),
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
        ),
    )

    const val REHOMED_HOLD = "ch06-hold"

    /** The "Hold Together" subsystem, transcribed into [ChemBinding] placeholders: while the cell holds any
     *  of its own fuel bond (a proxy for "metabolising, not starved"), join more and spend the quanta on
     *  Repair. The authored `AUTOTROPH_REPAIR_GENE` in `r`/`g`; here in x/y so it can be bound to whichever
     *  pair the player's lineage actually runs on. */
    private val HOLD_TEMPLATE = listOf(
        GeneCodec.parse("Bond ${ChemBinding.X} ${ChemBinding.Y} : ${ChemBinding.X}${ChemBinding.Y} > 0 : Repair").single()
    )

    /**
     * **Hold Together** — the bridge from the authored campaign into the rehomed Act II, and the chapter where
     * gene groups arrive.
     *
     * Two jobs, deliberately merged rather than sequenced, because the second motivates the first: the SEVER
     * field the player has to change lives on the divide gene, which is *inside* the group they have just
     * made. So they name a group, watch it collapse to one line, and then have to open it again to reach a
     * gene. The collapse/expand ladder gets taught by use instead of explanation.
     *
     * **Groups arrive as labels, not as machinery.** The player has authored every gene they own by hand
     * since `ch00-genesis`, which is the exact inverse of the old campaign's plan (CAMPAIGN_PLAN.md §10.6:
     * Ch4-7 teach "by inserting a group, not by hand-writing gene fields"). So grouping cannot be sold here
     * as a way to avoid raw genes - they already fluently edit those. It is sold as **compression**: four
     * genes is where a flat list starts to hurt, and the Act II swimmer has nineteen.
     *
     * The gate is [Lineage.groupedGeneCount], never a group *name*: the player names their own subsystem and
     * the campaign has no business insisting on a word for it.
     *
     * **The insert is bound to their chemistry** ([ChemBinding], via [Chapter.groupingFor]). Every Act II
     * subsystem was authored against the autotroph's `r`/`g` fuel; a player who came through the branch on
     * `b`/`g` would be handed a gene that cannot fire. This chapter is where that machinery is first
     * exercised end to end, and so where its viability gets decided.
     */
    private fun chapterHoldTogetherRehomed() = Chapter(
        id = REHOMED_HOLD,
        act = 2,
        title = "Hold Together",
        blurb = "Four genes, one job - and a body instead of a crowd.",
        scenario = EMPTY_WORLD,
        startsFreshWorld = false,
        branchesTo = emptyList(),   // the rest of Act II is rehomed one chapter at a time
        // Bound to the player's own fuel pair, whichever branch they walked.
        groupingFor = { lineage ->
            val bind = ChemBinding.of(lineage)
            GenomeGrouping(listOf(GeneGroup(GROUP_HOLD, insert = bind.genes(HOLD_TEMPLATE).tagged(GROUP_HOLD))))
        },
        insertableGroups = setOf(GROUP_HOLD),
        spawnCopiesHeldCell = true,
        spawnGenome = emptyList(),
        spawnBiomass = STARTER_CELL_BIOMASS,
        steps = listOf(
            Step(
                text = "Select one of your cells and look at its genome. Four genes now, and they arrived one at a time - so they are sitting in one unnamed heap.",
                gate = Gate.World("Select a cell", met = { it.focused != null }),
                allow = SPAWN,
                world = WorldRun.Live,
            ),
            Step(
                text = "Between them they do a single job: keep one cell alive off what it has already spent. Give that job a name - tap [+ NEW GROUP], name it whatever you like, and put your genes in it.",
                detail = "Four genes still fits in your head. Nineteen does not, and organisms get there quickly. A group is just a label you can fold away.",
                gate = Gate.World(
                    "Put your genes in a group",
                    // Their name, not ours - only that the work is organised.
                    met = { q -> q.lineage?.let { it.groupedGeneCount >= it.geneCount && it.geneCount > 0 } == true },
                ),
                allow = LOOK,
                spotlight = Spotlight(hint = "+ NEW GROUP, below the genome", target = "+ NEW GROUP"),
                world = WorldRun.Frozen,
            ),
            Step(
                text = "Now it folds up. Your whole genome reads as one line - one job, done. That is what a group is for.",
                gate = Gate.Next,
                allow = LOOK,
                world = WorldRun.Frozen,
            ),
            // The reason to reopen it - and the beat old ch05-hold was built on, on the player's own gene.
            Step(
                text = "Open it again and find your DIVIDE gene. Every daughter it makes cuts itself loose and drifts off, which is why you have a crowd of cells and not a creature.",
                gate = Gate.Next,
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(gene = GeneSpot(ActionType.Divide, GeneKeys.Part.Action)),
            ),
            Step(
                text = "Find SEVER on that gene and switch it off. Daughters will stay welded to their mother instead of breaking away.",
                detail = "SEVER on = the daughter leaves as its own cell. SEVER off = it stays attached. One field, two completely different creatures.",
                gate = Gate.World("Switch SEVER off", met = { it.lineage?.divideWelds == true }),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(gene = GeneSpot(ActionType.Divide, GeneKeys.Part.Sever)),
            ),
            Step(
                text = "Watch what grows now. The same four genes, and this time the cells stay joined - a body, spreading as one thing.",
                gate = Gate.World("Grow a joined body", met = { it.cellCount >= 8 }),
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
            // The first insert: a subsystem the player did not author, bound to their chemistry.
            Step(
                text = "A body has a problem a loose cell never had: it can be pulled apart. Drag it around and the welds take the strain. There is a ready-made subsystem for that - tap [+ ADD HOLD TOGETHER].",
                detail = "You did not write this one. That is the other half of what groups are for: a named job can be handed over whole.",
                gate = Gate.World("Add the HOLD TOGETHER group", met = { it.lineage?.hasRepair == true }),
                allow = LOOK,
                world = WorldRun.Frozen,
                spotlight = Spotlight(target = "+ ADD HOLD TOGETHER"),
            ),
            Step(
                text = "It mends weld damage as fast as the strain makes it, and costs nothing while nothing is torn. Take hold of your creature and pull it around - it holds.",
                gate = Gate.Next,
                allow = WATCH_SPEED,
                world = WorldRun.Live,
            ),
        ),
    )

    val SCRATCH_CHAPTERS: List<Chapter> = listOf(
        chapterGenesisScratch(),
        chapterDivideScratch(),
        chapterPhotosynthesisScratch(),
        chapterNightShiftScratch(),
        chapterLeftoversScratch(),
        chapterConversionScratch(),
        chapterSupplyScratch(),
        chapterLockedUpScratch(),
        chapterReclaimScratch(),
        chapterHoldTogetherRehomed(),
    )

    /**
     * The chapter list the **real game** surfaces (menu + director): the reworked arc, and only that.
     *
     * [CHAPTERS] — the pre-inversion Act I–III campaign — is deliberately **orphaned**: still compiled, still
     * covered by its tests, but not reachable from the menu or the director's segue chain. It was written
     * against the old chemistry (bonding cost energy) and reads wrong under the current model, so leaving it
     * on the map would offer the player a route into a campaign that no longer describes this game. It stays
     * in the source as the reference for whatever Act II becomes.
     */
    val PLAYABLE_CHAPTERS: List<Chapter> = SCRATCH_CHAPTERS

    /** Distinct characters in the chapters' player-facing copy that the bitmap font can't render (would
     *  show as `?`). Empty = all copy is safe. The harness runs this as a guard so a bad glyph is caught
     *  headlessly rather than only spotted in the GL window. */
    fun validateGlyphs(): List<Char> {
        val bad = LinkedHashSet<Char>()
        // Scan the RENDERED copy of every modality: input `{tokens}` are expanded first, so the `{`/`}`
        // delimiters (which the font lacks) don't false-positive and the actual on-screen phrases are checked.
        val modalities = listOf(InputHints.MOUSE, InputHints.TOUCH)
        // Runtime tokens the director resolves (e.g. `{chem}` -> a chemical name) aren't known to InputHints,
        // so strip any leftover `{token}` after expansion — otherwise the `{`/`}` delimiters (which the font
        // lacks) false-positive. The `}` MUST be escaped for Android's stricter ICU regex (see the memory).
        val runtimeToken = Regex("\\{\\w+\\}")
        fun scan(s: String?) {
            s ?: return
            for (hints in modalities) hints.expand(s).replace(runtimeToken, "").forEach { if (it != '\n' && !UiTextRenderer.supports(it)) bad.add(it) }
        }
        // Include the WIP scratch chapters — they're player-facing now (wired into the menu via PLAYABLE_CHAPTERS).
        for (ch in PLAYABLE_CHAPTERS) {
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
                gate = Gate.World("Select a cell", { it.focused != null }),
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
                gate = Gate.World("Select a cell", { it.focused != null }),
                allow = LOOK,
                spotlight = Spotlight(hint = "Select the cell"),
            ),
            Step(
                text = "Its genome is shown by FUNCTION. Right now there's just one job: GROW. That single label sums up everything this organism does.",
                gate = Gate.Next,
                allow = LOOK,
                spotlight = Spotlight(hint = "the GROW group, in the cell's panel", target = "+ GROW"),
                detail = "A genome is a set of subsystems, each doing one job. Grouping them this way turns a wall of rules into a handful of purposes you can read at a glance.",
            ),
            Step(
                text = "Tap the GROW group to open it. Inside are the actual genes - two of them - that carry out the job.",
                gate = Gate.Next,
                allow = LOOK,
                spotlight = Spotlight(hint = "tap + GROW (2) to expand it", target = "+ GROW"),
            ),
            Step(
                text = "Each gene reads as one sentence, on three lines: what it DOES, WHEN it does it, and what POWERS it.",
                gate = Gate.Next,
                allow = LOOK,
                detail = "Example: 'GROW USING FUEL / WHEN BIO < 3000 / (USE LIGHT)' means - while the cell is still small, lock fuel into body mass, paid for by daylight. What to do, when to do it, and the power for it.",
            ),
            Step(
                text = "The two GROW genes work together: one bonds raw matter into food, the other locks that food into body mass. That loop keeps the cell fed and repaired.",
                gate = Gate.Next,
                allow = LOOK,
                detail = "That's why it holds steady: as decay nibbles its body, GROW re-fires and rebuilds it, right back up to full size. Colour shows each gene's state - green is firing, grey is waiting, orange marks what's blocking it.",
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
     *  Teaches Divide by using it to solve a problem, and the group-insert idea: you build with meaningful
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
                gate = Gate.World("Select a cell", { it.focused != null }),
                allow = LOOK,
                spotlight = Spotlight(hint = "Select the cell"),
            ),
            Step(
                text = "Below its GROW group is a ready-made subsystem it's missing: ADD REPRODUCE. Tap it to give the cell a reproduction gene.",
                gate = Gate.World(
                    "Add the Reproduce group",
                    met = { (it.lineage?.geneCount ?: 0) >= 3 },
                ),
                allow = LOOK,
                spotlight = Spotlight(hint = "+ ADD REPRODUCE, below the groups", target = "+ ADD REPRODUCE"),
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
                gate = Gate.World("Select a cell", { it.focused != null }),
                allow = LOOK,
                spotlight = Spotlight(hint = "Select the cell, then + REPRODUCE, then the gene"),
            ),
            Step(
                text = "In the gene's fields, find SEVER: yes - that's what cuts each daughter loose. Switch it to SEVER: no, then press DONE.",
                gate = Gate.World(
                    "Set SEVER to no",
                    met = { it.lineage?.divideWelds == true },
                ),
                allow = LOOK,
                spotlight = Spotlight(hint = "SEVER toggle, then DONE", gene = GeneSpot(ActionType.Divide, GeneKeys.Part.Sever)),
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
                    met = { (it.lineage?.geneCount ?: 0) >= 4 },
                ),
                allow = LOOK,
                spotlight = Spotlight(hint = "+ ADD HOLD TOGETHER, below the groups", target = "+ ADD HOLD TOGETHER"),
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
                    met = { (it.lineage?.geneCount ?: 0) >= 5 },
                ),
                allow = LOOK,
                spotlight = Spotlight(hint = "+ ADD MOVE, below the groups", target = "+ ADD MOVE"),
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
                gate = Gate.World("Select a cell", { it.focused != null }),
                allow = LOOK,
                spotlight = Spotlight(hint = "Select the cell"),
            ),
            Step(
                text = "Add the POLARIZE group. It builds a chemical marker and, on every division, hands it to just ONE of the two daughters - so one cell ends up marked and the other bare. That difference is a sense of place.",
                gate = Gate.World("Add the Polarise group", met = { (it.lineage?.geneCount ?: 0) >= 13 }),
                allow = LOOK,
                spotlight = Spotlight(hint = "+ ADD POLARIZE, below the groups", target = "+ ADD POLARIZE"),
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
                spotlight = Spotlight(hint = "PAUSE in the bottom bar opens the speed controls", target = "PAUSE"),
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
                gate = Gate.World("Select a cell", { it.focused != null }),
                allow = LOOK,
                spotlight = Spotlight(hint = "Select the cell"),
            ),
            Step(
                text = "Add the CLOCK group. It builds a chemical that rises and falls on a loop of its own - an oscillator, ticking inside the cell whether or not the sun is up.",
                gate = Gate.World("Add the Clock group", met = { (it.lineage?.geneCount ?: 0) >= 19 }),
                allow = LOOK,
                spotlight = Spotlight(hint = "+ ADD CLOCK, below the groups", target = "+ ADD CLOCK"),
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
                gate = Gate.World("Switch the muscle to CHEMICAL FUEL", met = { it.lineage?.contractOnChem == true }),
                allow = WATCH_TIME,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "MOVE -> the muscle gene -> SOURCE -> BREAK FUEL", target = "+ MOVE"),
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
                gate = Gate.World("Flip the muscle to MARKER > 0", met = { it.lineage?.contractOnMarked == true }),
                allow = WATCH_TIME,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "MOVE -> the muscle gene -> set MARKER's test to > and its value to 0", target = "+ MOVE"),
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
                gate = Gate.World("Select a cell", { it.focused != null }),
                allow = LOOK,
                spotlight = Spotlight(hint = "Select the cell"),
            ),
            Step(
                text = "Add the REPRODUCE group. It's a division that SEVERS: once the body is large enough, it buds a daughter that breaks FREE - no weld holding it back - as its own single cell.",
                gate = Gate.World("Add the Reproduce group", met = { (it.lineage?.geneCount ?: 0) >= 21 }),
                allow = LOOK,
                spotlight = Spotlight(hint = "+ ADD REPRODUCE, below the groups", target = "+ ADD REPRODUCE"),
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
                gate = Gate.World("Select a cell", { it.focused != null }),
                allow = WATCH,
                world = WorldRun.Live,
                spotlight = Spotlight(hint = "the cell's LIGHT reading", target = "LIGHT"),
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
