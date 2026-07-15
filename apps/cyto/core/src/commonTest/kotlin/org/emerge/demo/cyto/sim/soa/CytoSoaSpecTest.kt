package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.AUTOTROPH_GENES
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoMutation
import org.emerge.demo.cyto.sim.CytoTuning
// RepairWeldMode removed — hard-coded to InternalOnly
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.CellWork
import org.emerge.demo.cyto.sim.Clause
import org.emerge.demo.cyto.sim.CytoBiologyCore
import org.emerge.demo.cyto.sim.MoleculeStore
import org.emerge.demo.cyto.sim.totalBiomassBonds
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneAction
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.GeneCondition
import org.emerge.demo.cyto.sim.Operand
import org.emerge.demo.cyto.sim.SpeciesRegistry
import org.emerge.demo.cyto.sim.handleableOf
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.demo.cyto.sim.systems.addSpring
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The biology spec, run on the **live [CytoSoaReducer]** — the SoA-native successor to the AoS
 * `CytoReducerTest` (which is deleted with the AoS path). These are *property* assertions (cells weld,
 * the autotroph grows + divides, matter is conserved, lineages diverge under mutation, repair heals,
 * matter diffuses), the semantic backstop behind the [CytoGoldenTest] regression hashes: the golden
 * proves "same trajectory as before", these prove "the right things still happen" — so a deliberate
 * golden re-baseline can't silently break an invariant.
 */
class CytoSoaSpecTest {
    private val cfg = CytoConfig(mutationRateDenom = 0)  // deterministic unless a test opts into mutation

    private fun cellCount(s: SimState) = s.components.getTable<CytoCellComponent>().asMap().size
    private fun springCount(s: SimState) =
        s.components.getTable<SpringConstraintComponent>().asMap().values.sumOf { it.springs.size }

    /** Total atoms across the reservoir + every cell's cytoplasm + biomass — the conserved quantity. */
    private fun totalAtoms(s: SimState): Long {
        var sum = s.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid?.totalAtoms() ?: 0L
        for (cell in s.components.getTable<CytoCellComponent>().asMap().values) {
            for ((sp, c) in cell.cytoplasm) sum += sp.length.toLong() * c
            for ((sp, c) in cell.biomass) sum += sp.length.toLong() * c
        }
        return sum
    }

    private fun maxConnectionDamage(s: SimState): Float =
        s.components.getTable<ConnectionStateComponent>().asMap().values
            .flatMap { it.damage.values }.maxOrNull() ?: 0f

    /** Tick [initial] on the SoA reducer for [ticks], invoking [each] with the materialised state after
     *  every tick (for per-tick invariants like conservation). Returns the final state. */
    private fun run(
        initial: SimState,
        cfg: CytoConfig = this.cfg,
        ticks: Int,
        input: CytoInput = CytoInput.EMPTY,
        each: (SimState, Int) -> Unit = { _, _ -> },
    ): SimState {
        val soa = CytoSoaReducer(cfg)
        var w = CytoWorld.fromSimState(initial)
        for (t in 1..ticks) { w = soa.tick(w, input); each(w.toSimState(), t) }
        return w.toSimState()
    }

    @Test
    fun overlappingCellsWeld() {
        // Two deeply overlapping cells with a Repair gene that burns stored 'ab'.
        // Auto-weld on overlap is disabled; the weld forms via Repair adhesion.
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(-0.1f, 0f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = Frac(1, 2), genome = repairOnly)
            b.spawnCell(CytoUnits.coord2(0.1f, 0f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = Frac(1, 2), genome = repairOnly)
            b.build()
        }
        val state = run(initial, ticks = 20)
        assertTrue(springCount(state) > 0, "overlapping Repair-active cells should have welded")
    }

    @Test
    fun matterIsConserved() {
        val initial = createCytoInitialState()
        val total0 = totalAtoms(initial)
        run(initial, ticks = 150) { s, t -> assertEquals(total0, totalAtoms(s), "atoms not conserved at step $t") }
    }

    @Test
    fun lyseStealsAllVictimSpeciesNotJustSome() {
        // Regression: processLyseAttacks iterated the victim biomass store while add()-removing each fully
        // drained species. That removal compacts the store, shifting unvisited species under the loop cursor
        // → they were SKIPPED, so "Lyse steals all species" silently missed some (and matter that should have
        // moved to the attacker stayed in the victim). Fixed by snapshotting the victim's species first.
        // Setup: a Lyse attacker overlaps (touches, un-welded) a victim whose biomass holds three distinct
        // species of one molecule each — one Lyse hit fully drains each, triggering the mid-iteration compaction.
        val lyseGene = Gene(
            EnergySource.BreakBond("bb"),
            GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)),   // always fires
            GeneAction(ActionType.Lyse),
        )
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(   // attacker: bb fuel powers Lyse; its own biomass is never a victim
                CytoUnits.coord2(-0.1f, 0f), Coord2.zero, CellType.Collector,
                cytoplasm = mapOf("bb" to 100_000), biomass = mapOf("bb" to 4000),
                logicalRadius = Frac(1, 2), genome = listOf(lyseGene),
            )
            b.spawnCell(   // victim: three distinct biomass species, each fully drainable in one hit
                CytoUnits.coord2(0.1f, 0f), Coord2.zero, CellType.Collector,
                cytoplasm = mapOf("rg" to 100), biomass = mapOf("rr" to 1, "gg" to 1, "bb" to 1),
                logicalRadius = Frac(1, 2), genome = emptyList(),
            )
            b.build()
        }
        val victimId = initial.components.getTable<CytoCellComponent>().asMap()
            .entries.first { it.value.genome.isEmpty() }.key
        val total0 = totalAtoms(initial)

        val state = run(initial, ticks = 1)   // one tick: victim is drained by Lyse but not yet death-collected

        assertEquals(total0, totalAtoms(state), "lyse must conserve atoms")
        val victimBio = state.components.getTable<CytoCellComponent>().asMap()[victimId]?.biomass ?: emptyMap()
        val leftover = (victimBio["rr"] ?: 0) + (victimBio["gg"] ?: 0) + (victimBio["bb"] ?: 0)
        assertEquals(0, leftover,
            "Lyse must steal ALL victim biomass species; leftover=$leftover means it skipped some (mid-iteration compaction)")
    }

    @Test
    fun scarcestIndexPicksLowestCountThenLowestId() {
        val s = MoleculeStore()
        s.inc(3, 5); s.inc(1, 5); s.inc(9, 2); s.inc(4, 2)  // min count 2 shared by ids 4 and 9
        assertEquals(4, s.idAt(s.scarcestIndex()), "ties broken by lowest species id")
    }

    @Test
    fun chemCapEvictsScarcestSpeciesToEnvConservingMatter() {
        // The toxicity mechanic: a cell at its distinct-species cap that ingests a new (toxic) species
        // evicts its scarcest resident, spilling those atoms to the grid — cap holds, matter conserved.
        val cap = CytoTuning.CELL_CHEM_CAP
        val grid = CytoMatterField.empty()
        val cyto = MoleculeStore(cap)
        for (id in 0 until cap) cyto.inc(id, if (id == 7) 1 else 100)  // id 7 is the unique scarcest
        assertEquals(cap, cyto.size)

        val toxinId = cap + 3
        val toxinAmount = 50
        val before = storeAtoms(cyto) + gridAtoms(grid)

        CytoSoaReducer.ingestWithCap(grid, cyto, toxinId, toxinAmount, ax = 0f, ay = 0f, ar = 0.5f)

        assertEquals(cap, cyto.size, "distinct-species cap must hold at $cap")
        assertEquals(0, cyto.count(7), "the scarcest resident (id 7) is evicted")
        assertTrue(cyto.count(toxinId) > 0, "the incoming toxin is admitted")
        val added = toxinAmount.toLong() * SpeciesRegistry.atomCount(toxinId)
        assertEquals(before + added, storeAtoms(cyto) + gridAtoms(grid),
            "matter conserved: the evicted species' atoms spilled to the grid, none lost")
    }

    private fun storeAtoms(s: MoleculeStore): Long {
        var sum = 0L
        for (i in 0 until s.size) sum += SpeciesRegistry.atomCount(s.idAt(i)).toLong() * s.countAt(i)
        return sum
    }

    private fun gridAtoms(g: CytoMatterField): Long {
        var sum = 0L
        g.forEachTexel { _, _, _, store ->
            for (i in 0 until store.size) sum += SpeciesRegistry.atomCount(store.idAt(i)).toLong() * store.countAt(i)
        }
        return sum
    }

    @Test
    fun autotrophGrowsIntoAColony() {
        val initial = createCytoInitialState()
        val start = cellCount(initial)
        val state = run(initial, ticks = 3000)   // first division slips later under the ~50× light nerf
        assertTrue(cellCount(state) > start, "autotroph should divide into a colony; got ${cellCount(state)} from $start")
        // NB: no spring-connectedness assertion. Auto-weld on overlap was deliberately disabled (fd9ebafb) —
        // welds now form only via the Repair gene (kept off the seed autotroph for single-cell viability +
        // motility under mutation), so daughters do NOT weld by design. See overlappingCellsWeld /
        // repairGeneWeldsATouchingCell for the welding path.
    }

    // The heterotroph builds biomass off its stored `ab` and divides a few times before the reserve runs
    // out. This works only because of sub-tick interpolation (CytoBiologyCore.selfGateCap): without it the
    // break-powered Convert would dump the whole reserve into biomass in one tick (overshooting its grow
    // gate) and strand too little `ab` to fund the bulk mitosis. With the cap it stops at GROW, keeping a
    // reserve to break for division.
    @Test
    fun heterotrophLivesOffStoredMatter() {
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(
                CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Muscle,
                cytoplasm = mapOf("rg" to 40000), biomass = mapOf("rg" to 8000),
            )
            b.build()
        }
        val start = cellCount(initial)
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 60) { s, t -> assertEquals(total0, totalAtoms(s), "atoms not conserved at step $t") }
        assertTrue(cellCount(state) > start, "heterotroph should grow + divide off stored ab; got ${cellCount(state)}")
    }

    // RETIRED with the quad-tree matter field: metabolicLeakRetainsUsableMatterDumpsWaste
    // (there is no passive waste leak now — waste accumulates until death/export) and
    // activeUptakeYieldsLessAgainstASteeperGradient (Import is now a flat C_eff bias on the diffusion
    // junction, no gradient-cost diminishing returns).
    @Test
    fun concBandAndGateFiresOnlyInRange() {
        // Conc operand + AND-conjunction (MORPHOGENESIS.md §Morphogens for shape): a Convert gene gated
        // `Conc(ab) > 50 & Conc(ab) < 200` locks ab into biomass only when the size-normalised ab
        // concentration is in-band. With CONC_SCALE=1000 and biomass=1000 bonds, Conc(ab) == count(ab), so
        // count 100 is in-band and 500 is above it. The above-band cell does NOT grow — which can only be the
        // SECOND clause (the upper bound) being ANDed in (a lone `> 50` would fire at 500 too), proving the
        // conjunction is enforced. cc is the (light-independent) BreakBond fuel.
        val convert = Gene(
            EnergySource.BreakBond("bb"),
            GeneCondition(listOf(
                Clause(Operand.Conc("rg"), Comparison.Greater, Operand.Constant(50)),
                Clause(Operand.Conc("rg"), Comparison.Less, Operand.Constant(200)),
            )),
            GeneAction(ActionType.Convert, "rg"),
        )
        fun bioAbAfterTick(cytoAb: Int): Int {
            val initial = run {
                val b = SimBuilder(SimState())
                b.spawnCell(
                    CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Collector,
                    cytoplasm = mapOf("rg" to cytoAb, "bb" to 1000), biomass = mapOf("rg" to 1000), genome = listOf(convert),
                )
                b.build()
            }
            return run(initial, ticks = 1).components.getTable<CytoCellComponent>().asMap().values.first().biomass["rg"] ?: 0
        }
        assertTrue(bioAbAfterTick(100) > 1000, "in-band (Conc 100) Converts → biomass grows; got ${bioAbAfterTick(100)}")
        assertEquals(1000, bioAbAfterTick(500), "above-band (Conc 500) fails the upper clause → no Convert")
    }

    @Test
    fun asymmetricMitosisRetainSidePlacesMorphogen() {
        // Retain-side (MORPHOGENESIS.md §Source placement): `Mitosis ac mother` keeps the morphogen in the
        // MOTHER (the surviving original entity = a centred source); the default daughter-retention hands it
        // to the new cell. Spawn one cell above the divide threshold with morphogen `ac` + cc fuel; after it
        // divides, the morphogen sits whole on the selected side and the OTHER side has none.
        fun morphogenSplit(toMother: Boolean): Pair<Int, Int> {  // (mother's ac, daughter's ac)
            val gene = Gene(
                EnergySource.BreakBond("bb"),
                GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(1000)),
                GeneAction(ActionType.Mitosis, "rb", morphogenToMother = toMother),
            )
            val initial = run {
                val b = SimBuilder(SimState())
                b.spawnCell(
                    CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Collector,
                    cytoplasm = mapOf("rb" to 100, "bb" to 100_000), biomass = mapOf("rg" to 4000), genome = listOf(gene),
                )
                b.build()
            }
            val motherId = initial.components.getTable<CytoCellComponent>().asMap().keys.first()
            val cells = run(initial, ticks = 1).components.getTable<CytoCellComponent>().asMap()
            assertEquals(2, cells.size, "should have divided into two")
            val motherAc = cells[motherId]?.cytoplasm?.get("rb") ?: 0
            val daughterAc = cells.entries.first { it.key != motherId }.value.cytoplasm["rb"] ?: 0
            return motherAc to daughterAc
        }
        val (mMother, mDaughter) = morphogenSplit(toMother = true)
        assertTrue(mMother > 0 && mDaughter == 0, "mother-retention keeps the morphogen in the mother; got mother=$mMother daughter=$mDaughter")
        val (dMother, dDaughter) = morphogenSplit(toMother = false)
        assertTrue(dDaughter > 0 && dMother == 0, "daughter-retention hands the morphogen to the daughter; got mother=$dMother daughter=$dDaughter")
    }

    @Test
    fun acrossOrientedDivisionGrowsA2DSheetNotAThread() {
        // End-to-end (MORPHOGENESIS.md §Morphogens for shape): a single founder (seeded with the `cc`
        // organizer determinant) sources the `bc` morphogen and divides ACROSS its gradient → the body widens
        // into a 2D sheet (real y-extent), whereas the SAME genome with unoriented (default) division grows a
        // 1-cell-thick thread (y-extent ≈ 0). Validates oriented placement at the organism scale.
        fun yExtent(divideGene: String): Double {
            val genome = GeneCodec.parse(
                """
                Light : rg < 4000 : FormBond r g
                Light : Biomass < 4500 : Convert rg
                $divideGene
                Light : Conc(bb) > 0 : FormBond b b
                Light : Conc(bb) > 0 : FormBond g b
                Break gb : Conc(gb) > 30 : Convert gb
                Break rg : Biomass > 0 : Repair
                """.trimIndent(),
            )
            val initial = run {
                val b = SimBuilder(SimState(randomSeed = 0x9E3779B97F4A7C15uL.toLong()))
                b.spawnCell(
                    CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Collector,
                    cytoplasm = mapOf("r" to 500, "g" to 500, "b" to 500, "bb" to 200),
                    biomass = CytoSeed.STARTER_BIOMASS, genome = genome,
                )
                b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterField.seededUniform(2000)) }
                b.build()
            }
            val s = run(initial, ticks = 1500)
            val ts = s.components.getTable<TransformComponent>().asMap()
            val ys = s.components.getTable<CytoCellComponent>().asMap().keys.mapNotNull { ts[it]?.let { tr -> CytoUnits.toLogical(tr.pos.y).toDouble() } }
            return if (ys.isEmpty()) 0.0 else ys.max() - ys.min()
        }
        val across = yExtent("Break rg : Biomass > 4000 : Mitosis bb across gb")
        val thread = yExtent("Break rg : Biomass > 4000 : Mitosis bb")
        assertTrue(across > thread + 1.0, "across-oriented division should widen into a 2D sheet; across y-extent=$across, thread y-extent=$thread")
    }

    @Test
    fun handleableSplitsSynthesisFromMetabolism() {
        // produce-without-diffuse: a species the genome only SYNTHESISES (FormBond) is held but intracellular;
        // a METABOLISED species (Break/Convert/Import) is held AND diffusible.
        val on = GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0))
        val h = handleableOf(listOf(
            Gene(EnergySource.Light, on, GeneAction(ActionType.FormBond, "r", "g")),     // 'rg' synthesised
            Gene(EnergySource.BreakBond("bb"), on, GeneAction(ActionType.Convert, "bg")), // 'bg' metabolised
        ))
        val rg = SpeciesRegistry.id("rg"); val bg = SpeciesRegistry.id("bg")
        assertTrue(h.canHold(rg) && !h.canDiffuse(rg), "synthesised 'rg' is held but intracellular (not diffusible)")
        assertTrue(h.canHold(bg) && h.canDiffuse(bg), "metabolised 'bg' is held AND diffusible")
    }

    @Test
    fun synthesisedSpeciesStaysIntracellularMetabolisedSpeciesDiffuses() {
        // The behaviour, end-to-end: two overlapping (→ welding via Repair) cells share a genome where
        // 'ab' is only synthesised (FormBond ⇒ intracellular) and 'cb' is metabolised (Convert ⇒ diffusible).
        // Only cell A starts with both in cytoplasm; the genes are gated OFF so nothing is produced/consumed
        // — only the diffuse phase moves matter. After welding, 'cb' spreads to B but 'ab' never leaves A.
        val off = GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(Int.MAX_VALUE))
        val genome = listOf(
            Gene(EnergySource.Light, off, GeneAction(ActionType.FormBond, "r", "g")),
            Gene(EnergySource.Light, off, GeneAction(ActionType.Convert, "bg")),
            Gene(EnergySource.BreakBond("bg"), GeneCondition(Operand.Chem("bg"), Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Repair)),  // weld via repair
        )
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(-0.1f, 0f), Coord2.zero, CellType.Collector,
                cytoplasm = mapOf("rg" to 1000, "bg" to 1000), biomass = mapOf("rr" to 2000), genome = genome)
            b.spawnCell(CytoUnits.coord2(0.1f, 0f), Coord2.zero, CellType.Collector,
                cytoplasm = mapOf("bg" to 1), biomass = mapOf("rr" to 2000), genome = genome)  // minimal bg for Repair energy
            b.build()
        }
        val cells = run(initial, ticks = 12).components.getTable<CytoCellComponent>().asMap().values.toList()
        assertEquals(2, cells.size, "both cells alive")
        val rg = cells.map { it.cytoplasm["rg"] ?: 0 }.sorted()
        assertEquals(0, rg[0], "intracellular 'rg' did NOT diffuse to the neighbour")
        assertTrue(rg[1] > 0, "'rg' retained in its own cell (not leaked)")
        assertTrue(cells.all { (it.cytoplasm["bg"] ?: 0) > 0 }, "metabolised 'bg' diffused to both cells; got ${cells.map { it.cytoplasm["bg"] ?: 0 }}")
    }

    @Test
    fun retainSealsAgainstWeldDiffusionAndCostsEnergy() {
        // Explicit membrane seal (ActionType.Retain), independent of the derived canDiffuse rule. `bg` is
        // metabolised (Convert, gated OFF) ⇒ canDiffuse-true, so across a weld it normally spreads to the
        // neighbour (cf. synthesisedSpeciesStaysIntracellular…). A `Retain bg` gene — powered by breaking the
        // plentiful, unrelated `rg` (1 energy/tick, so weld + seal fuels are decoupled) — blocks every
        // boundary crossing of `bg`, so it stays whole in the cell that started with it, while spending fuel.
        val on = GeneCondition(Operand.Chem("rg"), Comparison.Greater, Operand.Constant(0))
        val off = GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(Int.MAX_VALUE))
        fun pair(extra: List<Gene>): SimState {
            val genome = listOf(
                Gene(EnergySource.Light, off, GeneAction(ActionType.Convert, "bg")),   // makes `bg` canDiffuse (gated off)
                Gene(EnergySource.BreakBond("rg"), on, GeneAction(ActionType.Repair)), // weld, powered by rg
            ) + extra
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(-0.1f, 0f), Coord2.zero, CellType.Collector,
                cytoplasm = mapOf("bg" to 1_000, "rg" to 5_000), biomass = mapOf("rr" to 2_000), genome = genome)
            b.spawnCell(CytoUnits.coord2(0.1f, 0f), Coord2.zero, CellType.Collector,
                cytoplasm = mapOf("rg" to 5_000), biomass = mapOf("rr" to 2_000), genome = genome)
            return b.build()
        }
        val retain = Gene(EnergySource.BreakBond("rg"), on, GeneAction(ActionType.Retain, "bg"))

        val sealed = run(pair(listOf(retain)), ticks = 16).components.getTable<CytoCellComponent>().asMap().values.toList()
        val leaky = run(pair(emptyList()), ticks = 16).components.getTable<CytoCellComponent>().asMap().values.toList()

        assertEquals(2, sealed.size, "both cells alive")
        assertEquals(0, sealed.minOf { it.cytoplasm["bg"] ?: 0 }, "Retain sealed `bg` — it never crossed to the neighbour")
        assertTrue(leaky.minOf { it.cytoplasm["bg"] ?: 0 } > 0, "control (no Retain): `bg` diffused across the weld to both cells")
        assertTrue(sealed.any { (it.cytoplasm["rg"] ?: 0) < 5_000 }, "the seal cost fuel — `rg` was broken to pay for it")
    }

    @Test
    fun mutationDivergesGenomesAndConservesMatter() {
        val mutCfg = cfg.copy(mutationRateDenom = 200)
        val initial = createCytoInitialState()
        val total0 = totalAtoms(initial)
        var sawDivergence = false
        run(initial, mutCfg, ticks = 600) { s, t ->
            assertEquals(total0, totalAtoms(s), "atoms not conserved under mutation at step $t")
            if (s.components.getTable<CytoCellComponent>().asMap().values.any { it.genome != AUTOTROPH_GENES }) sawDivergence = true
        }
        assertTrue(sawDivergence, "mutation should have diverged at least one lineage from the seeded genome")
    }

    @Test
    fun mutationDisabledIsNoOp() {
        assertEquals(null, CytoMutation.mutate(AUTOTROPH_GENES, 0) { 0 })
    }

    @Test
    fun saveRoundTripsTheMatterWorld() {
        // Break-powered division bootstraps slowly, so colonise at the deterministic baseline first,
        // THEN evolve the colony under mutation — exercising the save codec on a real,
        // multi-cell, genetically-diverged world. (Mutation-on from tick 0 wouldn't colonise here: the lone
        // founder mutates before its first division. The LIVE world mutates slower than the stress fixture.)
        val grown = run(createCytoInitialState(), ticks = 1500)
        val state = run(grown, cfg.copy(mutationRateDenom = 200), ticks = 400)

        val bytes = org.emerge.demo.cyto.CytoSaveCodec.encode(state)
        val restored = org.emerge.demo.cyto.CytoSaveCodec.decode(bytes)

        assertEquals(cellCount(state), cellCount(restored), "cell count")
        assertTrue(cellCount(state) > 1, "expected a non-trivial colony to exercise the codec")
        assertEquals(springCount(state), springCount(restored), "spring count")
        assertEquals(totalAtoms(state), totalAtoms(restored), "total atoms not preserved through save")
        fun genomes(s: SimState) = s.components.getTable<CytoCellComponent>().asMap().values
            .map { GeneCodec.serialize(it.genome) }.sorted()
        assertEquals(genomes(state), genomes(restored), "genomes not preserved through save")
    }

    @Test
    fun mutationRateSavesAndDefaultsToInherit() {
        // The in-game mutation rate (CytoSimParamsComponent) persists through the codec; an unset (default)
        // world stays unset (-1 sentinel = inherit the cfg default), so every cfg-driven path is unchanged.
        val initial = createCytoInitialState()
        assertEquals(-1, CytoWorld.fromSimState(initial).mutationRateDenom, "fresh world inherits the cfg default")

        val world = CytoWorld.fromSimState(initial)
        world.mutationRateDenom = 10_000
        val restored = org.emerge.demo.cyto.CytoSaveCodec.decode(org.emerge.demo.cyto.CytoSaveCodec.encode(world.toSimState()))
        assertEquals(10_000, CytoWorld.fromSimState(restored).mutationRateDenom, "explicit rate survives save/load")

        val defaultRT = org.emerge.demo.cyto.CytoSaveCodec.decode(org.emerge.demo.cyto.CytoSaveCodec.encode(CytoWorld.fromSimState(initial).toSimState()))
        assertEquals(-1, CytoWorld.fromSimState(defaultRT).mutationRateDenom, "unset stays unset through save")
    }

    @Test
    fun degenerateDivisionKillsTheCellAndRecyclesMatter() {
        val (sx, sy) = CytoLightField.SOURCES.first()
        val divideNow = listOf(
            Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Mitosis)),
        )
        val initial = run {
            val b = SimBuilder(SimState(randomSeed = 1))
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterField.empty()) }
            b.spawnCell(
                CytoUnits.coord2(sx, sy), Coord2.zero, CellType.Collector,
                cytoplasm = emptyMap(), biomass = mapOf("rg" to 1, "gb" to 1), logicalRadius = MIN_RADIUS, genome = divideNow,
            )
            b.build()
        }
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 3)
        assertEquals(0, cellCount(state), "a cell with only count-1 molecules should die trying to divide")
        assertEquals(total0, totalAtoms(state), "its matter must be recycled to the environment, conserved")
    }

    @Test
    fun asymmetricMitosisAllocatesMorphogenToOneDaughterAndItPersists() {
        // C — asymmetric mitosis (MORPHOGENESIS.md §C). A founder poised to divide carries a *trace*
        // morphogen `gb` (a MOLECULE — bare atoms are now universally permeable, so a trace determinant must
        // be a bond): no gene metabolises OR senses it, so it is `!canHold`, and the canHold-gated cell↔cell
        // diffusion and env-uptake can therefore never move it. The single Mitosis gene names `gb` as its
        // morphogen, so on division `gb` goes WHOLE to the daughter and the mother keeps none.
        // Because `gb` is trace, the mother can never re-acquire it (no uptake, no diffusion in) — so the
        // asymmetry the split establishes PERSISTS. That persistent positional difference between two
        // clones from one founder is the substrate for differentiation. A gene that *gates* on `Chem(gb)`
        // to act on the difference keeps `gb` trace too — sensing doesn't grant permeability (handleableOf)
        // — so the behavioural fate persists; see morphogenGatedFatePersistsAsBehaviouralDifferentiation.
        val mitosis = Gene(
            EnergySource.BreakBond("rg"),
            GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(7_900)),
            GeneAction(ActionType.Mitosis, "gb"),   // morphogen `gb` → whole to the daughter
        )
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(
                CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Blank,
                cytoplasm = mapOf("rg" to 50_000, "gb" to 2_000), biomass = mapOf("rg" to 8_000),
                genome = listOf(mitosis),
            )
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterField.empty()) }
            b.build()
        }
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 12) { s, t -> assertEquals(total0, totalAtoms(s), "atoms not conserved at step $t") }

        val cells = state.components.getTable<CytoCellComponent>().asMap().values.toList()
        assertEquals(2, cells.size, "founder should divide exactly once (daughters can't re-divide at half biomass)")
        val withB = cells.count { (it.cytoplasm["gb"] ?: 0) > 0 }
        val withoutB = cells.count { (it.cytoplasm["gb"] ?: 0) == 0 }
        assertEquals(1, withB, "exactly one daughter inherits the morphogen")
        assertEquals(1, withoutB, "the other inherits none — and can't acquire a trace species, so the asymmetry persists")
    }

    @Test
    fun morphogenGatedFatePersistsAsBehaviouralDifferentiation() {
        // The payoff of C + sensing≠permeability: one genome → two stably-different cells. A founder
        // divides asymmetrically on morphogen `gb`; a fate gene (Contract) gates on `Chem(gb) > 0`. Because
        // *sensing* `gb` doesn't make it handleable (handleableOf ignores condition operands) and no gene
        // metabolises it, `gb` stays a trace MOLECULE — the mother (which got none) can never absorb or
        // diffuse it in — so only the morphogen-bearing daughter expresses the contract fate, and it holds.
        // Two clones, one genome, a divergent shape that persists. (The trace species must be a molecule now
        // that bare atoms diffuse freely regardless of genome; a monomer morphogen would equilibrate.)
        val mitosis = Gene(
            EnergySource.BreakBond("rg"),
            GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(7_900)),
            GeneAction(ActionType.Mitosis, "gb"),
        )
        val contractIfMorphogen = Gene(
            EnergySource.BreakBond("rg"),
            GeneCondition(Operand.Chem("gb"), Comparison.Greater, Operand.Constant(0)),
            GeneAction(ActionType.Contract),       // gates on `gb` but acts on radius — `gb` stays trace
        )
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(
                CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Blank,
                cytoplasm = mapOf("rg" to 80_000, "gb" to 2_000), biomass = mapOf("rg" to 8_000),
                genome = listOf(mitosis, contractIfMorphogen),
            )
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterField.empty()) }
            b.build()
        }
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 20) { s, t -> assertEquals(total0, totalAtoms(s), "atoms not conserved at step $t") }

        val cells = state.components.getTable<CytoCellComponent>().asMap().values.toList()
        assertEquals(2, cells.size, "founder should divide exactly once")
        val morphogenCell = cells.single { (it.cytoplasm["gb"] ?: 0) > 0 }
        val plainCell = cells.single { (it.cytoplasm["gb"] ?: 0) == 0 }
        assertTrue(
            morphogenCell.logicalRadius < plainCell.logicalRadius,
            "only the morphogen-bearing daughter should express the Contract fate (smaller radius), and it " +
                "should persist; morphogen r=${morphogenCell.logicalRadius} vs plain r=${plainCell.logicalRadius}",
        )
    }

    @Test
    fun grabDoesNotFlingACellAcrossTheWorld() {
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Blank)
            b.build()
        }
        val id = initial.components.getTable<CytoCellComponent>().asMap().keys.first()
        val state = run(initial, ticks = 1, input = CytoInput(grab = CytoInput.Grab(id, 500f, 0f)))
        val v = state.components.getTable<MotionComponent>().asMap().getValue(id).vel
        val speed = kotlin.math.hypot(CytoUnits.toLogical(v.x).toDouble(), CytoUnits.toLogical(v.y).toDouble())
        assertTrue(speed < 3.0, "grab should follow at a bounded speed (~2), not fling; got $speed")
    }

    @Test
    fun divisionInheritsTheGenome() {
        val state = run(createCytoInitialState(), ticks = 3000)   // first division slips later under the ~50× light nerf
        val cells = state.components.getTable<CytoCellComponent>().asMap().values
        assertTrue(cells.size > 1, "expected a colony")
        assertTrue(cells.all { it.genome == AUTOTROPH_GENES }, "every cell should inherit the autotroph genome")
    }

    /** Two Collector cells on a light source, spring-connected, connection pre-damaged near the break
     *  threshold. */
    @Test
    fun efficiencyGearTradesEnergyForThroughput() {
        // Drive one Convert gene directly with a fixed quanta budget + abundant substrate, so ENERGY is the
        // binding constraint, and measure biomass locked per tick at gear g.
        fun biomassGain(g: Int, quanta: Int): Int {
            val work = CellWork(
                cytoplasm = MoleculeStore.of(mapOf("rg" to 100_000_000)),   // substrate never binds
                biomass = MoleculeStore.of(mapOf("rg" to 1000)),
                logicalRadius = MIN_RADIUS, type = CellType.Collector,
                genome = listOf(Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Less, Operand.Constant(1_000_000_000)), GeneAction(ActionType.Convert, "rg"), efficiency = g)),
                quanta = quanta, touchCount = 0, wear = 0, gridIndex = -1, connectionDamage = HashMap(),
            )
            val before = totalBiomassBonds(work.biomass)
            CytoBiologyCore.runGenes(work)
            return totalBiomassBonds(work.biomass) - before
        }
        // Energy-poor: a high gear squeezes ~(g+1)× more actions out of the same quanta.
        val poor0 = biomassGain(0, 100)
        val poor5 = biomassGain(5, 100)
        assertTrue(poor5 >= poor0 * 5, "high gear should be ~6× as productive per scarce quantum; g0=$poor0 g5=$poor5")
        // Energy-rich: gear 5's energy-spend cap (REF>>5) binds, so the uncapped gear 0 out-throughputs it —
        // the cost that makes high efficiency a niche adaptation, not a free bonus.
        val rich0 = biomassGain(0, 10_000_000)
        val rich5 = biomassGain(5, 10_000_000)
        assertTrue(rich0 > rich5, "when energy is abundant, the low (uncapped) gear does more total work; g0=$rich0 g5=$rich5")
    }

    @Test
    fun mutationCanGrowOperandsPastTwoAtoms() {
        // Scripted PRNG drives one point-mutation that appends an atom to a FormBond operand, so the operand
        // length itself evolves (not capped at the old mono/dimer pool). Draw order in CytoMutation.mutate:
        // del,drift,point,dup (each fires on 0); then pointMutate's nextInt(11) case 3; then mutateSpecies.
        val seq = intArrayOf(1, 1, 0, 1, /*case*/3, /*grow*/0, /*atom 'r'*/0)
        var i = 0
        val gene = Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Less, Operand.Constant(100)), GeneAction(ActionType.FormBond, "rg", "g"))
        val out = CytoMutation.mutate(listOf(gene), rateDenom = 200) { seq[i++] }!!
        assertEquals("rgr", out[0].action.a, "FormBond operand should grow rg→rgr (append), not stay a dimer")
    }

    @Test
    fun formBondMatchesBySuffixAndPrefix() {
        // WILDCARD match (MORPHOGENESIS.md §2026-06-18; opt-in via aWild/bWild): `*rg` "g*" bonds a molecule
        // ENDING WITH "rg" to one STARTING WITH "g" — i.e. rg+g→rgg — and must NOT touch the bare monomer "r"
        // (which the looser single-atom "ends in r" rule would have grabbed). Targets specific molecules.
        val work = CellWork(
            cytoplasm = MoleculeStore.of(mapOf("r" to 1000, "rg" to 1000, "g" to 1000)),
            biomass = MoleculeStore.of(mapOf("rg" to 1000)),
            logicalRadius = MIN_RADIUS, type = CellType.Collector,
            genome = listOf(Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.FormBond, "rg", "g", aWild = true, bWild = true))),
            quanta = 300, touchCount = 0, wear = 0, gridIndex = -1, connectionDamage = HashMap(),
        )
        CytoBiologyCore.runGenes(work)
        assertEquals(1000, work.cytoplasm.count(org.emerge.demo.cyto.sim.SpeciesRegistry.id("r")), "the bare monomer 'r' must be untouched (suffix 'rg' doesn't match it)")
        assertTrue(work.cytoplasm.count(org.emerge.demo.cyto.sim.SpeciesRegistry.id("rgg")) > 0, "rg+g should have bonded into rgg")
        assertTrue(work.cytoplasm.count(org.emerge.demo.cyto.sim.SpeciesRegistry.id("rg")) < 1000, "the 'rg' molecule should have been consumed")
    }

    @Test
    fun formBondPicksMostAbundantMatchNotLexSmallest() {
        // Regression for the cell-8 bug (WILDCARD match, opt-in): among several molecules ending in "b", the
        // gene must bond the one the cell has MOST of (the monomer "b", →bg), not whichever sorts first
        // lexicographically. Here the lex-smallest match "rgrb" is a rare trace (count 1); the abundant
        // feedstock is "b" (count 1000). The old lex-first rule grabbed "rgrb" and produced "rgrbg"; the
        // count-first rule must make "bg". (Exercises `*b`/`b*`; exact `b` would name only the monomer.)
        val sid = { s: String -> org.emerge.demo.cyto.sim.SpeciesRegistry.id(s) }
        val work = CellWork(
            cytoplasm = MoleculeStore.of(mapOf("rgrb" to 1, "b" to 1000, "g" to 1000)),
            biomass = MoleculeStore.of(mapOf("bg" to 1000)),
            logicalRadius = MIN_RADIUS, type = CellType.Collector,
            genome = listOf(Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.FormBond, "b", "g", aWild = true, bWild = true))),
            quanta = 300, touchCount = 0, wear = 0, gridIndex = -1, connectionDamage = HashMap(),
        )
        CytoBiologyCore.runGenes(work)
        assertTrue(work.cytoplasm.count(sid("bg")) > 0, "abundant b+g should have bonded into bg")
        assertEquals(1, work.cytoplasm.count(sid("rgrb")), "the rare lex-smallest match 'rgrb' must be left alone")
        assertEquals(0, work.cytoplasm.count(sid("rgrbg")), "must NOT have produced rgrbg (the old lex-first product)")
        assertTrue(work.cytoplasm.count(sid("b")) < 1000, "the abundant 'b' feedstock should have been consumed")
    }

    @Test
    fun formBondExactBuildsHomodimerEvenWhenProductOutnumbersTheMonomer() {
        // The diagnosed live bug (MORPHOGENESIS.md §2026-06-18): a cell stockpiling its own product `rr`
        // (rr > r) STALLS under the wildcard match — `*r` and `r*` both resolve to the richest r-ender/-
        // starter, which is `rr`, and `rr+rr` repeats the `rr` bond → polymerisation-forbidden → silent
        // no-op. EXACT `r r` (the new default) joins the monomers regardless, so production continues.
        val sid = { s: String -> org.emerge.demo.cyto.sim.SpeciesRegistry.id(s) }
        fun run(aWild: Boolean, bWild: Boolean): Pair<Int, Int> {
            val work = CellWork(
                cytoplasm = MoleculeStore.of(mapOf("r" to 1000, "rr" to 5000)),   // product already outnumbers the monomer
                biomass = MoleculeStore.of(mapOf("rr" to 2000)),
                logicalRadius = MIN_RADIUS, type = CellType.Collector,
                genome = listOf(Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.FormBond, "r", "r", aWild = aWild, bWild = bWild))),
                quanta = 300, touchCount = 0, wear = 0, gridIndex = -1, connectionDamage = HashMap(),
            )
            CytoBiologyCore.runGenes(work)
            return work.cytoplasm.count(sid("r")) to work.cytoplasm.count(sid("rr"))
        }
        val (exactR, exactRR) = run(aWild = false, bWild = false)
        assertTrue(exactRR > 5000, "EXACT FormBond r r builds more rr from the monomers (was 5000); got $exactRR")
        assertTrue(exactR < 1000, "EXACT consumed monomer r (was 1000); got $exactR")
        val (wildR, wildRR) = run(aWild = true, bWild = true)
        assertEquals(5000, wildRR, "WILDCARD stalls — richest r-ender/-starter is rr, and rr+rr is forbidden; got $wildRR")
        assertEquals(1000, wildR, "WILDCARD consumed nothing (no-op); got $wildR")
    }

    @Test
    fun breakBondPicksMostAbundantFuelNotLexSmallest() {
        // BreakBond likewise breaks the molecule it has most of that holds the bond. "rgb" holds bond "gb" and
        // sorts before "gb", but the abundant fuel is the dimer "gb"; breaking it must yield g + b, not split rgb.
        val sid = { s: String -> org.emerge.demo.cyto.sim.SpeciesRegistry.id(s) }
        val work = CellWork(
            cytoplasm = MoleculeStore.of(mapOf("rgb" to 1, "gb" to 1000, "b" to 1000)),
            biomass = MoleculeStore.of(mapOf("bg" to 1000)),
            logicalRadius = MIN_RADIUS, type = CellType.Collector,
            genome = listOf(Gene(EnergySource.BreakBond("gb"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Convert, "b"))),
            quanta = 0, touchCount = 0, wear = 0, gridIndex = -1, connectionDamage = HashMap(),
        )
        CytoBiologyCore.runGenes(work)
        assertEquals(1, work.cytoplasm.count(sid("rgb")), "the rare lex-smallest fuel 'rgb' must be left alone")
        assertTrue(work.cytoplasm.count(sid("gb")) < 1000, "the abundant 'gb' fuel should have been broken")
    }

    private fun damagedPair(genome: List<Gene>, damage: Float): SimState {
        val (sx, sy) = CytoLightField.SOURCES.first()
        val b = SimBuilder(SimState(randomSeed = 1))
        b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterField.seededUniform(CytoSeed.MATTER_UNIFORM_LEVEL)) }
        // A stored `rg` cytoplasm reserve so a BreakBond-powered repair gene has fuel regardless of where the
        // moving daylight band is (light timing must not decide whether repair fires).
        // PIN logicalRadius to the biomass baseline (4000 bonds ⇒ radius 0.5) so the cells are full-size from
        // tick 0 — otherwise the radius elastically grows toward its target, the spring rest grows with it,
        // and the link is transiently compressed/stretched during growth, confounding the damage tests. Placed
        // at rest (rest = 2×0.5 = 1.0) so the connection is damaged but UNSTRESSED — isolating heal-vs-not.
        val r = Frac(1, 2)
        val a = b.spawnCell(CytoUnits.coord2(sx, sy), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r, genome = genome)
        val c = b.spawnCell(CytoUnits.coord2(sx + 1.0f, sy), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r, genome = genome)
        addSpring(b, a, c, cfg)
        b.update<ConnectionStateComponent>(a) { ConnectionStateComponent(mapOf(c to damage)) }
        b.update<ConnectionStateComponent>(c) { ConnectionStateComponent(mapOf(a to damage)) }
        return b.build()
    }

    private val repairOnly = listOf(
        // Break the stored `rg` reserve for repair energy — light-independent, so the test doesn't depend on
        // where the moving daylight band happens to be.
        Gene(EnergySource.BreakBond("rg"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Repair)),
    )

    // Fixture damage stays a fraction of CONNECTION_BREAK_DAMAGE (2.5) so the weld is partially damaged but
    // INTACT — a value at/over the threshold would break the (unstressed) link and there'd be no damage to
    // measure. 1.25 = half the threshold (mirrors the pre-nerf 2.5-of-5).
    @Test
    fun repairGeneHealsConnectionDamage() {
        val initial = damagedPair(repairOnly, damage = 1.25f)
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 40)
        assertTrue(springCount(state) > 0, "repair + light should keep the connection alive")
        assertTrue(maxConnectionDamage(state) < 1.25f, "repair should have reduced the damage; got ${maxConnectionDamage(state)}")
        assertEquals(total0, totalAtoms(state), "repair must conserve matter")
    }

    @Test
    fun withoutRepairGeneDamageIsNotHealed() {
        val state = run(damagedPair(emptyList(), damage = 1.25f), ticks = 40)
        assertTrue(maxConnectionDamage(state) >= 1.25f, "without a repair gene damage must not heal; got ${maxConnectionDamage(state)}")
    }

    @Test
    fun repairHealRateIsCappedPerTick() {
        // Repair mends at a bounded RATE per connection regardless of fuel hoarded, so an energy-rich
        // cell can't instantly undo damage — the basis for a hard enough stretch breaking a link
        // despite active repair (its stress outruns this cap).
        val cap = CytoTuning.MAX_REPAIR_HEAL_PER_TICK
        val dmg = maxConnectionDamage(run(damagedPair(repairOnly, damage = 1.25f), ticks = 1))
        assertTrue(dmg < 1.25f, "repair should heal some damage in a tick; got $dmg")
        assertTrue(dmg >= 1.25f - cap - 1e-3f, "one tick must not heal more than the per-tick cap ($cap); got $dmg (healed ${1.25f - dmg})")
    }

    @Test
    fun extremeStretchBreaksHealthyConnectionInOneTick() {
        // A perfectly healthy link stretched past the over-stretch break distance takes a full
        // CONNECTION_BREAK_DAMAGE of stress in a single tick and breaks — repair can't pre-empt it
        // (stress is applied after biology).
        val (sx, sy) = CytoLightField.SOURCES.first()
        val b = SimBuilder(SimState(randomSeed = 1))
        b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterField.seededUniform(CytoSeed.MATTER_UNIFORM_LEVEL)) }
        // Pin radius so rest = 1.0, break distance = OVERSTRETCH_BREAK_MULTIPLE × rest.
        // Place the centres past the break point — derived from the tuning so the test tracks
        // OVERSTRETCH_BREAK_MULTIPLE instead of hardcoding a gap that silently goes under the threshold
        // when welds are made less fragile.
        val r = Frac(1, 2)
        val rest = 1f   // 2 × radius 0.5
        val centreDist = rest * (1f + 1.1f * CytoTuning.OVERSTRETCH_BREAK_MULTIPLE)
        val a = b.spawnCell(CytoUnits.coord2(sx, sy), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r, genome = repairOnly)
        val c = b.spawnCell(CytoUnits.coord2(sx + centreDist, sy), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r, genome = repairOnly)
        addSpring(b, a, c, cfg)
        assertEquals(0, springCount(run(b.build(), ticks = 1)), "a link stretched past break distance must break in one tick, even with repair")
    }

    @Test
    fun repairGeneWeldsATouchingCell() {
        // Gene-driven adhesion (MORPHOGENESIS): a Repair-active cell touching an un-welded cell forms a weld
        // with it (born "at 0 health", healed by the spare repair). InternalOnly mode restricts adhesion
        // welds to touching cells that share a connected neighbour.
        fun touchingPair(genomeA: List<Gene>, genomeB: List<Gene>): SimState {
            val b = SimBuilder(SimState(randomSeed = 1))
            // biomass bonds ⇒ baseline radius; pin logicalRadius to it so the cells are full-size from
            // tick 0 (no elastic growth drift). minDist = 1.0; placed apart so penetration is a touch
            // (contact), but well under the auto-weld threshold. Reserve fuels the BreakBond Repair gene.
            val r = Frac(1, 2)
            b.spawnCell(CytoUnits.coord2(-0.45f, 0f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r, genome = genomeA)
            b.spawnCell(CytoUnits.coord2(0.45f, 0f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r, genome = genomeB)
            return b.build()
        }

        // Two Repair-active cells touching but NOT sharing a neighbor — should weld (first-connection rule).
        // Once welded, additional repair welds are restricted to InternalOnly mode.
        val both = touchingPair(repairOnly, repairOnly)
        val total0 = totalAtoms(both)
        val welded = run(both, ticks = 20)
        assertTrue(springCount(welded) > 0, "two Repair-active touching cells with no shared neighbor should weld (first-connection rule)")
        assertEquals(total0, totalAtoms(welded), "conserves matter even when weld forms")

        val none = run(touchingPair(emptyList(), emptyList()), ticks = 20)
        assertEquals(0, springCount(none), "without a Repair gene a light touch must NOT weld")

        // Option 2: a weld needs BOTH cells repairing the same tick (the clock-as-identity gate). One-sided
        // Repair — the foreign-contact case — must not weld.
        val oneSided = run(touchingPair(repairOnly, emptyList()), ticks = 20)
        assertEquals(0, springCount(oneSided), "a weld requires BOTH cells repairing; one-sided Repair must NOT weld")
    }

    @Test
    fun repairWeldInternalOnlySharesNeighbor() {
        // InternalOnly: Repair only welds touching cells that share a connected neighbour.
        // Triangle: C at origin, A at (0.5, 0), B at (-0.3, 0.5).
        // A-C and B-C are pre-welded (overlap > 0.25 used to auto-weld them, but we weld manually now).
        // A-B touch (penetration ~0.057 < 0.25, no auto-weld).
        // When A and B both repair, they weld because they share C as a connected neighbour.
        val b = SimBuilder(SimState(randomSeed = 1))
        val r = Frac(1, 2)
        val c = b.spawnCell(CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r, genome = repairOnly)
        val a = b.spawnCell(CytoUnits.coord2(0.5f, 0f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r, genome = repairOnly)
        val bCell = b.spawnCell(CytoUnits.coord2(-0.3f, 0.5f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r, genome = repairOnly)
        // Pre-weld A-C and B-C to form the triangle backbone (auto-weld on overlap is disabled).
        addSpring(b, a, c, cfg)
        addSpring(b, bCell, c, cfg)
        val result = run(b.build(), ticks = 20)
        val sc = springCount(result)
        println("InternalOnly: springCount=$sc")
        assertTrue(sc >= 2, "InternalOnly: got $sc springs (expected ≥2)")

        // Two cells touching (no shared neighbor) should weld via Repair (first-connection rule).
        val twoB = SimBuilder(SimState(randomSeed = 1))
        val r2 = Frac(1, 2)
        // Two cells 0.9 apart (penetration 0.1 < 0.25 → touch, no auto-weld)
        twoB.spawnCell(CytoUnits.coord2(-0.45f, 0f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r2, genome = repairOnly)
        twoB.spawnCell(CytoUnits.coord2(0.45f, 0f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r2, genome = repairOnly)
        val twoResult = run(twoB.build(), ticks = 20)
        val twoCount = springCount(twoResult)
        // No shared neighbor → first-connection repair weld still forms. >0 springs = weld.
        println("InternalOnly two-cell: $twoCount springs")
        assertTrue(twoCount > 0, "InternalOnly: two touching cells with no shared neighbor should weld (first-connection rule)")
    }

    @Test
    fun matterDoesNotDiffuseAnUndisturbedDepositStaysPut() {
        // Diffusion was removed (the disc gather + observer-gated quad collapse replace it). With no cells
        // around, a monomer deposit stays exactly where it's put and atoms are conserved.
        val seed = CytoMatterField.empty()
        val aId = SpeciesRegistry.id("r")
        // Deposit well inside a single base tile (the origin is a multi-tile corner, which would split the disc).
        // Coords must stay in-bounds. The disc refines to fine leaves, so atoms spread across the footprint's
        // leaves — read locality by summing leaves NEAR vs FAR, not a single point.
        seed.deposit(8f, 8f, 0.6f, aId, 1000)   // 'r' is a monomer → env decay leaves it untouched too
        val initial = run {
            val b = SimBuilder(SimState())
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(seed) }
            b.build()
        }
        fun grid(s: SimState) = s.components.getTable<CytoMatterGridComponent>().asMap().getValue(GRID_SINGLETON).grid
        // Sum species `sp` over leaves whose centre is within `rad` cell-diam of (px,py).
        fun near(g: CytoMatterField, sp: Int, px: Float, py: Float, rad: Float): Int {
            var sum = 0
            g.forEachTexel { x, y, size, store ->
                val cxL = x + size * 0.5f; val cyL = y + size * 0.5f
                val ddx = cxL - px; val ddy = cyL - py
                if (ddx * ddx + ddy * ddy <= rad * rad) sum += store.count(sp)
            }
            return sum
        }
        val total0 = grid(initial).totalAtoms()
        val g = grid(run(initial, ticks = 200))
        assertEquals(1000, near(g, aId, 8f, 8f, 4f), "with diffusion gone an undisturbed deposit must stay put")
        assertEquals(0, near(g, aId, -24f, -24f, 4f), "matter must NOT spread to a distant point (no diffusion)")
        assertEquals(total0, g.totalAtoms(), "matter conserved")
    }

    /** A flex gene of [action] powered by breaking stored `ab` (no light needed), always gated on. */
    private fun flexGenome(action: ActionType) = listOf(
        Gene(EnergySource.BreakBond("rg"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(action)),
    )

    private fun radiusRaw(s: SimState, id: org.emerge.sim.core.EntityId): Long =
        s.components.getTable<CytoCellComponent>().asMap().getValue(id).logicalRadius.raw

    @Test
    fun contractShrinksRadiusBelowBiomassBaseline() {
        // Two cells with identical biomass (⇒ identical baseline radius), far apart so they never interact:
        // one Contract gene (fuelled by breaking stored `ab`), one inert control. After a few ticks the
        // contractor sits below the baseline — and the action moves no matter (radius is not conserved).
        // (Expand was banned — a radius above baseline would coarsen the broadphase grid — so Contract is
        // the only flex action.)
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("rg" to 200), biomass = mapOf("rg" to 8000), genome = flexGenome(ActionType.Contract))
            b.spawnCell(CytoUnits.coord2(40f, 0f), Coord2.zero, CellType.Blank, cytoplasm = mapOf("rg" to 200), biomass = mapOf("rg" to 8000), genome = emptyList())
            b.build()
        }
        val ids = initial.components.getTable<CytoCellComponent>().asMap().keys.sortedBy { it.value }
        val contractId = ids[0]; val controlId = ids[1]
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 10) { s, t -> assertEquals(total0, totalAtoms(s), "flex must move no matter; broke at $t") }
        assertTrue(radiusRaw(state, contractId) < radiusRaw(state, controlId), "Contract should hold the radius below the biomass baseline")
    }

    @Test
    fun contractedRadiusRelaxesBackToBaselineWhenTheGeneStops() {
        // The muscle property: a held contraction springs back UP to the biomass baseline once the gene can
        // no longer fire (here, its `ab` fuel runs out) — via the same elastic blend that grows a cell.
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("rg" to 80), biomass = mapOf("rg" to 8000), genome = flexGenome(ActionType.Contract))
            b.spawnCell(CytoUnits.coord2(20f, 0f), Coord2.zero, CellType.Blank, cytoplasm = mapOf("rg" to 80), biomass = mapOf("rg" to 8000), genome = emptyList())
            b.build()
        }
        val ids = initial.components.getTable<CytoCellComponent>().asMap().keys.sortedBy { it.value }
        val flexId = ids[0]; val controlId = ids[1]
        val contracted = run(initial, ticks = 6)
        assertTrue(radiusRaw(contracted, flexId) < radiusRaw(contracted, controlId), "should be contracted while fuelled")
        val relaxed = run(contracted, ticks = 300)
        assertTrue(radiusRaw(relaxed, flexId) > radiusRaw(contracted, flexId), "radius should spring back up once the gene stops firing")
        val ctrl = radiusRaw(relaxed, controlId)
        assertTrue(kotlin.math.abs(radiusRaw(relaxed, flexId) - ctrl) < ctrl / 16, "radius should relax back to the biomass baseline")
    }

    @Test
    fun touchingConditionGatesAGeneOnUnweldedCellContact() {
        // A gene that fires only while in contact with a cell it isn't welded to. Two cells placed barely
        // overlapping (so they touch + repel, not weld) each Contract on contact, fuelled by breaking stored
        // `ab`; a lone control with the identical gene never touches anything, so its gate stays false — so
        // the touched cells end up smaller (contracted) than the control.
        val touchContract = listOf(
            Gene(EnergySource.BreakBond("rg"), GeneCondition(Operand.Touching, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Contract)),
        )
        // Spawn at the biomass baseline radius (sqrt(8000/16000) ≈ 0.7) so Contract has room to bite on tick 1
        // (a cell already at MIN_RADIUS couldn't shrink); a shallow overlap touches without welding.
        fun cell(b: SimBuilder, x: Float) =
            b.spawnCell(CytoUnits.coord2(x, 0f), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("rg" to 100000), biomass = mapOf("rg" to 8000), logicalRadius = org.emerge.sim.core.physics.primitives.Frac(7, 10), genome = touchContract)
        val initial = run {
            val b = SimBuilder(SimState())
            cell(b, -0.6f); cell(b, 0.6f)   // ~0.2 overlap at radius 0.7 ⇒ touch, not weld
            cell(b, 20f)                     // lone control, never in contact
            b.build()
        }
        val ids = initial.components.getTable<CytoCellComponent>().asMap().keys.sortedBy { it.value }
        val (aId, bId, controlId) = ids
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 2) { s, t -> assertEquals(total0, totalAtoms(s), "touch gating must conserve matter; broke at $t") }
        val control = radiusRaw(state, controlId)
        assertTrue(radiusRaw(state, aId) < control, "a touched cell should have fired its Touching-gated Contract (got ${radiusRaw(state, aId)} vs control $control)")
        assertTrue(radiusRaw(state, bId) < control, "both touching cells should have fired")
    }

    @Test
    fun neighboursConditionGatesAGeneOnWeldedDegree() {
        // The Operand.Neighbours gate reads the cell's welded (connected) degree. Two deeply overlapping cells
        // Repair-weld into a connected pair (weldedDegree ⇒ 1); each also carries a Neighbours-gated Contract,
        // so once welded they clench. A lone control with the identical genome never welds (degree stays 0), so
        // its Neighbours-gated Contract never fires — leaving it larger than the welded, contracted pair.
        val weldThenSense = listOf(
            // Repair burns stored `rg` to weld the overlapping pair (auto-weld on overlap is disabled).
            Gene(EnergySource.BreakBond("rg"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Repair)),
            // Contract only while connected to at least one neighbour.
            Gene(EnergySource.BreakBond("rg"), GeneCondition(Operand.Neighbours, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Contract)),
        )
        fun cell(b: SimBuilder, x: Float) =
            b.spawnCell(CytoUnits.coord2(x, 0f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 100000), biomass = mapOf("rg" to 8000), logicalRadius = org.emerge.sim.core.physics.primitives.Frac(7, 10), genome = weldThenSense)
        val initial = run {
            val b = SimBuilder(SimState())
            cell(b, -0.1f); cell(b, 0.1f)   // deep overlap ⇒ Repair welds them into a connected pair
            cell(b, 20f)                     // lone control, never welds
            b.build()
        }
        val ids = initial.components.getTable<CytoCellComponent>().asMap().keys.sortedBy { it.value }
        val (aId, bId, controlId) = ids
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 40) { s, t -> assertEquals(total0, totalAtoms(s), "neighbour gating must conserve matter; broke at $t") }
        assertTrue(springCount(state) > 0, "the overlapping pair should have Repair-welded (so weldedDegree > 0)")
        val control = radiusRaw(state, controlId)
        assertTrue(radiusRaw(state, aId) < control, "a welded cell should have fired its Neighbours-gated Contract (got ${radiusRaw(state, aId)} vs control $control)")
        assertTrue(radiusRaw(state, bId) < control, "both welded cells should have fired")
    }

    @Test
    fun retainSealsRegardlessOfGenePosition() {
        // Retain genes are freshly evaluated every tick (no round-robin cache), so a Retain sitting
        // deep in a large genome still seals from the tick its gate first holds — no stale-cache leak
        // window. Build a 13-gene genome with Retain at idx 7 and confirm the sealed species never
        // crosses the weld even across many ticks.
        // Build a 13-gene genome where Retain sits at idx 6 (evaluated only at ticks
        // 6, 19, 32 … under pure round-robin). Run 20 ticks past tick 0: at tick 7 the
        // cached flag would still be false (never re-evaluated since tick 0 where bb was
        // absent), so without the fix bb leaks; with the fix it stays sealed.
        //
        // Gene layout (13 entries): idx 0-5 = no-ops to fill slots, idx 6 = Retain(bg),
        // idx 7-12 = no-ops. The cell starts with NO bg; at tick 1 a FormBond gene makes
        // bg. Retain's gate is bg > 0. Under round-robin stale-cache, idx 6 stays false
        // for 12 ticks (tick 7-18).
        val filler = Gene(EnergySource.Light,
            GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(Int.MAX_VALUE)),
            GeneAction(ActionType.Repair))   // always-off filler
        val makeBg = Gene(EnergySource.BreakBond("rg"),
            GeneCondition(Operand.Biomass, Comparison.Less, Operand.Constant(1_000_000)),
            GeneAction(ActionType.FormBond, "bg"))   // always-on (gated by huge biomass)
        val sealBg = Gene(EnergySource.BreakBond("rg"),
            GeneCondition(Operand.Chem("bg"), Comparison.Greater, Operand.Constant(0)),
            GeneAction(ActionType.Retain, "bg"))

        val genome = List(6) { filler } + listOf(makeBg, sealBg) + List(5) { filler }
        // genome[6] = makeBg, genome[7] = sealBg (Retain at idx 7)
        // With 13 genes, idx 7 fires at ticks 7, 20, 33 … round-robin.

        // Cell 1 gets bg + Retain; Cell 2 is empty. They touch.
        fun setup(): SimState {
            val b = SimBuilder(SimState())
            // Cell A: has bg=500, rg=10000, biomass=2000 — Retain fires (bg > 0), seals bg
            b.spawnCell(CytoUnits.coord2(-0.1f, 0f), Coord2.zero, CellType.Collector,
                cytoplasm = mapOf("bg" to 500, "rg" to 10_000), biomass = mapOf("rr" to 2_000),
                genome = genome)
            // Cell B: no bg — should NOT receive bg from A
            b.spawnCell(CytoUnits.coord2(0.1f, 0f), Coord2.zero, CellType.Collector,
                cytoplasm = mapOf("rg" to 10_000), biomass = mapOf("rr" to 2_000),
                genome = genome)
            return b.build()
        }

        val state = run(setup(), ticks = 20)
        val cells = state.components.getTable<CytoCellComponent>().asMap().values.toList()
        assertEquals(2, cells.size, "both cells alive after 20 ticks")
        // The cell that started with bg (cell A) still has it — Retain sealed it.
        val bgInA = cells.first { it.cytoplasm["bg"] != null && it.cytoplasm["bg"]!! > 0 }
        val bgInB = cells.firstOrNull { it.cytoplasm["bg"] != null && it.cytoplasm["bg"]!! > 0 }
        // Cell B should NOT have bg — the Retain gene sealed it at cell A.
        assertEquals(1, cells.count { it.cytoplasm["bg"] != null && it.cytoplasm["bg"]!! > 0 },
            "bg should stay in the cell that started with it; Retain sealed the membrane")
    }

    @Test
    fun retainDoesNotBlockMonomerPrecursorSynthesisInNeighbour() {
        // REPRO of the Ch8 "bb leaks despite Retain bb" report. The DIMER bb never crosses the seal
        // (retainSealsAgainstWeldDiffusion proves that). What actually happens: the free MONOMER b diffuses
        // across the weld (free-monomer rule), and the neighbour rebuilds bb LOCALLY via its own FormBond b b.
        val on = GeneCondition(Operand.Chem("rg"), Comparison.Greater, Operand.Constant(0))
        val alwaysOn = GeneCondition(Operand.Biomass, Comparison.Less, Operand.Constant(1_000_000))
        val genome = listOf(
            Gene(EnergySource.BreakBond("rg"), alwaysOn, GeneAction(ActionType.FormBond, "b", "b")), // build bb from b
            Gene(EnergySource.BreakBond("rg"), on, GeneAction(ActionType.Repair)),                    // weld
            Gene(EnergySource.BreakBond("rg"),
                GeneCondition(Operand.Chem("bb"), Comparison.Greater, Operand.Constant(0)),
                GeneAction(ActionType.Retain, "bb")),                                                 // seal bb
        )
        fun setup(neighbourCanSynthesise: Boolean): SimState {
            val bGenome = if (neighbourCanSynthesise) genome else genome.drop(1) // drop FormBond b b
            val b = SimBuilder(SimState())
            // Cell A: has bb + lots of monomer b to shed, seals its bb.
            b.spawnCell(CytoUnits.coord2(-0.1f, 0f), Coord2.zero, CellType.Collector,
                cytoplasm = mapOf("bb" to 500, "b" to 4_000, "rg" to 10_000), biomass = mapOf("rr" to 2_000),
                genome = genome)
            // Cell B: no bb, no b to start.
            b.spawnCell(CytoUnits.coord2(0.1f, 0f), Coord2.zero, CellType.Collector,
                cytoplasm = mapOf("rg" to 10_000), biomass = mapOf("rr" to 2_000),
                genome = bGenome)
            return b.build()
        }
        fun bbOf(s: SimState) = s.components.getTable<CytoCellComponent>().asMap().values
            .sortedBy { it.cytoplasm["rr"] }.map { it.cytoplasm["bb"] ?: 0 }

        val withSynth = run(setup(true), ticks = 30)
        val cellsW = withSynth.components.getTable<CytoCellComponent>().asMap().values.toList()
        // A keeps its sealed bb (~500, never crossed); B ends up WITH bb — but built it itself.
        val aBB = cellsW.maxOf { it.cytoplasm["bb"] ?: 0 }
        val bBB = cellsW.minOf { it.cytoplasm["bb"] ?: 0 }
        val bHasB = cellsW.any { (it.cytoplasm["b"] ?: 0) > 0 }
        println("[REPRO] with-synth: A_bb=$aBB  B_bb=$bBB  someBmonomer=$bHasB")
        assertTrue(aBB >= 500, "A's bb stayed sealed (never leaked out)")
        assertTrue(bBB > 0, "neighbour ended up WITH bb — the reported 'leak'")

        // Control: neighbour lacks FormBond b b ⇒ receives monomer b but CANNOT make bb ⇒ stays bb-free.
        val noSynth = run(setup(false), ticks = 30)
        val cellsN = noSynth.components.getTable<CytoCellComponent>().asMap().values.toList()
        val bBBNoSynth = cellsN.minOf { it.cytoplasm["bb"] ?: 0 }
        println("[REPRO] no-synth:   B_bb=$bBBNoSynth")
        assertEquals(0, bBBNoSynth, "without local FormBond b b the neighbour gets NO bb — proving it was synthesis, not a dimer leak")
    }
}
