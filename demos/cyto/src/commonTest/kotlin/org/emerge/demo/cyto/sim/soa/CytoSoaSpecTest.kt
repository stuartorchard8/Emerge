package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.AUTOTROPH_GENES
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoMatterGrid
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoMutation
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.CellWork
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
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.systems.addSpring
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
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
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(-0.1f, 0f), Coord2.zero, CellType.Blank)
            b.spawnCell(CytoUnits.coord2(0.1f, 0f), Coord2.zero, CellType.Blank)
            b.build()
        }
        val state = run(initial, ticks = 5)
        assertTrue(springCount(state) > 0, "overlapping cells should have welded")
    }

    @Test
    fun matterIsConserved() {
        val initial = createCytoInitialState()
        val total0 = totalAtoms(initial)
        run(initial, ticks = 150) { s, t -> assertEquals(total0, totalAtoms(s), "atoms not conserved at step $t") }
    }

    @Test
    fun autotrophGrowsIntoAColony() {
        val initial = createCytoInitialState()
        val start = cellCount(initial)
        val state = run(initial, ticks = 3000)   // first division slips later under the ~50× light nerf
        assertTrue(cellCount(state) > start, "autotroph should divide into a colony; got ${cellCount(state)} from $start")
        assertTrue(springCount(state) > 0, "divided cells should be spring-connected")
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
                cytoplasm = mapOf("ab" to 40000), biomass = mapOf("ab" to 8000),
            )
            b.build()
        }
        val start = cellCount(initial)
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 60) { s, t -> assertEquals(total0, totalAtoms(s), "atoms not conserved at step $t") }
        assertTrue(cellCount(state) > start, "heterotroph should grow + divide off stored ab; got ${cellCount(state)}")
    }

    @Test
    fun metabolicLeakRetainsUsableMatterDumpsWaste() {
        // Metabolic leak: passive exchange retains a species the cell CAN metabolise (so it can hoard an
        // imported reserve) but still dumps a species it can't use down-gradient. One inert gene makes `ab`
        // (and atoms a,b) handleable while never firing (its gate is unreachable), so runGenes is a no-op and
        // we observe the passive exchange alone. The cell sits in an empty reservoir, so without retention
        // both `ab` and `c` would leak; with it, only the un-metabolisable `c` does.
        val inertAbGene = Gene(
            EnergySource.Light,
            GeneCondition(Operand.Chem("ab"), Comparison.Greater, Operand.Constant(1_000_000_000)),  // never true
            GeneAction(ActionType.Convert, "ab"),
        )
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(
                CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Collector,
                cytoplasm = mapOf("ab" to 5000, "c" to 5000), biomass = mapOf("ab" to 2000),
                genome = listOf(inertAbGene),
            )
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterGrid.empty()) }
            b.build()
        }
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 1) { s, t -> assertEquals(total0, totalAtoms(s), "atoms not conserved at step $t") }
        val cell = state.components.getTable<CytoCellComponent>().asMap().values.first()
        assertEquals(5000, cell.cytoplasm["ab"] ?: 0, "metabolisable ab is retained (not leaked back to the reservoir)")
        assertTrue((cell.cytoplasm["c"] ?: 0) < 5000, "un-metabolisable c leaks toward the empty reservoir; got ${cell.cytoplasm["c"]}")
    }

    @Test
    fun activeUptakeYieldsLessAgainstASteeperGradient() {
        // Gradient-cost on Import: the same light budget buys far fewer molecules when the cell is already
        // concentrated above the ambient reservoir. Two identical cells on a light source with a fat 'a'
        // reservoir (so uptake is gradient-limited, not supply-limited) — one starting at ambient (excess 0),
        // one well above it (excess 2×SCALE → ~1/3 yield). Biomass is small enough that nothing degrades in
        // one tick, and at/above ambient passive exchange is inert, so the cytoplasm delta is pure uptake.
        val (sx, sy) = CytoLightField.SOURCES.first()
        // BreakBond("cc") fuel makes uptake light-independent; cc breaks to c+c (never `a`), so the measured
        // `a` delta is pure import, not break fragments.
        val importGene = Gene(
            EnergySource.BreakBond("cc"),
            GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)),
            GeneAction(ActionType.Import, "a"),
        )
        fun uptakeFrom(startCytoA: Int): Int {
            val initial = run {
                val b = SimBuilder(SimState())
                b.spawnCell(
                    CytoUnits.coord2(sx, sy), Coord2.zero, CellType.Collector,
                    cytoplasm = mapOf("a" to startCytoA, "cc" to 1_000_000), biomass = mapOf("ab" to 1000), genome = listOf(importGene),
                )
                val grid = CytoMatterGrid.empty()
                grid.deposit(grid.indexOf(sx, sy), "a", 1_000_000)
                b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(grid) }
                b.build()
            }
            fun cytoA(s: SimState) = s.components.getTable<CytoCellComponent>().asMap().values.first().cytoplasm["a"] ?: 0
            return cytoA(run(initial, ticks = 1) { _, _ -> }) - cytoA(initial)
        }
        val nearAmbient = uptakeFrom(1_000_000)                                       // excess 0 → full yield
        val concentrated = uptakeFrom(1_000_000 + 2 * CytoTuning.IMPORT_GRADIENT_SCALE)  // excess 2×SCALE → ~1/3
        assertTrue(concentrated > 0, "still imports something against the gradient; got $concentrated")
        assertTrue(nearAmbient > concentrated * 2, "uptake should fall steeply with concentration; near=$nearAmbient conc=$concentrated")
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
        // Break-powered division bootstraps slowly (under the moving daylight band the founder builds a
        // reserve + biomass for ~1000 ticks before its first split), so colonise at the deterministic
        // baseline first, THEN evolve the colony under mutation — exercising the save codec on a real,
        // multi-cell, genetically-diverged world. (Mutation-on from tick 0 wouldn't colonise here: the lone
        // founder mutates before its slow first division. The LIVE world mutates 500× slower —
        // MUTATION_RATE_DENOM=100_000 vs 200 — so its first division lands long before any mutation; rate
        // 200 is a divergence stress fixture.)
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
    fun degenerateDivisionKillsTheCellAndRecyclesMatter() {
        val (sx, sy) = CytoLightField.SOURCES.first()
        val divideNow = listOf(
            Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Mitosis)),
        )
        val initial = run {
            val b = SimBuilder(SimState(randomSeed = 1))
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterGrid.empty()) }
            b.spawnCell(
                CytoUnits.coord2(sx, sy), Coord2.zero, CellType.Collector,
                cytoplasm = emptyMap(), biomass = mapOf("ab" to 1, "ba" to 1), logicalRadius = MIN_RADIUS, genome = divideNow,
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
        // morphogen `c`: no gene metabolises OR senses it, so it is `!canHold`, and the canHold-gated
        // cell↔cell diffusion and env-uptake can therefore never move it. The single Mitosis gene names
        // `c` as its morphogen, so on division `c` goes WHOLE to the daughter and the mother keeps none.
        // Because `c` is trace, the mother can never re-acquire it (no uptake, no diffusion in) — so the
        // asymmetry the split establishes PERSISTS. That persistent positional difference between two
        // clones from one founder is the substrate for differentiation.
        //
        // (NB: the moment a gene *gates* on `Chem(c)` to act on the difference, `handleableOf` marks `c`
        // canHold — sensing currently grants permeability — and uptake+diffusion re-equilibrate it across
        // the division weld. So C establishes the asymmetry but persistent *behavioural* differentiation
        // needs the §A diffusion work, or sensing-≠-permeability in handleableOf. This test pins the
        // mechanism, not that downstream fate genes hold their state — see the note to Stu.)
        val mitosis = Gene(
            EnergySource.BreakBond("ab"),
            GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(7_900)),
            GeneAction(ActionType.Mitosis, "c"),   // morphogen `c` → whole to the daughter
        )
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(
                CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Blank,
                cytoplasm = mapOf("ab" to 50_000, "c" to 2_000), biomass = mapOf("ab" to 8_000),
                genome = listOf(mitosis),
            )
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterGrid.empty()) }
            b.build()
        }
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 12) { s, t -> assertEquals(total0, totalAtoms(s), "atoms not conserved at step $t") }

        val cells = state.components.getTable<CytoCellComponent>().asMap().values.toList()
        assertEquals(2, cells.size, "founder should divide exactly once (daughters can't re-divide at half biomass)")
        val withC = cells.count { (it.cytoplasm["c"] ?: 0) > 0 }
        val withoutC = cells.count { (it.cytoplasm["c"] ?: 0) == 0 }
        assertEquals(1, withC, "exactly one daughter inherits the morphogen")
        assertEquals(1, withoutC, "the other inherits none — and can't acquire a trace species, so the asymmetry persists")
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
                cytoplasm = MoleculeStore.of(mapOf("ab" to 100_000_000)),   // substrate never binds
                biomass = MoleculeStore.of(mapOf("ab" to 1000)),
                logicalRadius = MIN_RADIUS, type = CellType.Collector,
                genome = listOf(Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Less, Operand.Constant(1_000_000_000)), GeneAction(ActionType.Convert, "ab"), efficiency = g)),
                quanta = quanta, touchCount = 0, wear = 0, gridIndex = -1, connectionDamage = HashMap(),
            )
            val before = totalBiomassBonds(work.biomass)
            CytoBiologyCore.runGenes(work, CytoMatterGrid.empty())
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
        // del,drift,point,dup (each fires on 0); then pointMutate's nextInt(8) case; then mutateSpecies.
        val seq = intArrayOf(1, 1, 0, 1, /*case*/3, /*grow*/0, /*atom 'a'*/0)
        var i = 0
        val gene = Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Less, Operand.Constant(100)), GeneAction(ActionType.FormBond, "ab", "b"))
        val out = CytoMutation.mutate(listOf(gene), rateDenom = 200) { seq[i++] }!!
        assertEquals("aba", out[0].action.a, "FormBond operand should grow ab→aba (append), not stay a dimer")
    }

    @Test
    fun formBondMatchesBySuffixAndPrefix() {
        // FormBond "ab" "b" should bond a molecule ENDING WITH "ab" to one STARTING WITH "b" — i.e. ab+b→abb
        // — and must NOT touch the bare monomer "a" (which the looser single-atom "ends in a" rule would have
        // grabbed). That selectivity is the point: target specific molecules, skip irrelevant ones.
        val work = CellWork(
            cytoplasm = MoleculeStore.of(mapOf("a" to 1000, "ab" to 1000, "b" to 1000)),
            biomass = MoleculeStore.of(mapOf("ab" to 1000)),
            logicalRadius = MIN_RADIUS, type = CellType.Collector,
            genome = listOf(Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.FormBond, "ab", "b"))),
            quanta = 300, touchCount = 0, wear = 0, gridIndex = -1, connectionDamage = HashMap(),
        )
        CytoBiologyCore.runGenes(work, CytoMatterGrid.empty())
        assertEquals(1000, work.cytoplasm.count(org.emerge.demo.cyto.sim.SpeciesRegistry.id("a")), "the bare monomer 'a' must be untouched (suffix 'ab' doesn't match it)")
        assertTrue(work.cytoplasm.count(org.emerge.demo.cyto.sim.SpeciesRegistry.id("abb")) > 0, "ab+b should have bonded into abb")
        assertTrue(work.cytoplasm.count(org.emerge.demo.cyto.sim.SpeciesRegistry.id("ab")) < 1000, "the 'ab' molecule should have been consumed")
    }

    private fun damagedPair(genome: List<Gene>, damage: Float): SimState {
        val (sx, sy) = CytoLightField.SOURCES.first()
        val b = SimBuilder(SimState(randomSeed = 1))
        b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterGrid.seeded()) }
        // A stored `ab` cytoplasm reserve so a BreakBond-powered repair gene has fuel regardless of where the
        // moving daylight band is (light timing must not decide whether repair fires).
        val a = b.spawnCell(CytoUnits.coord2(sx, sy), Coord2.zero, CellType.Collector, cytoplasm = mapOf("ab" to 50000), biomass = mapOf("ab" to 8000), genome = genome)
        val c = b.spawnCell(CytoUnits.coord2(sx + 0.5f, sy), Coord2.zero, CellType.Collector, cytoplasm = mapOf("ab" to 50000), biomass = mapOf("ab" to 8000), genome = genome)
        addSpring(b, a, c, cfg)
        b.update<ConnectionStateComponent>(a) { ConnectionStateComponent(mapOf(c to damage)) }
        b.update<ConnectionStateComponent>(c) { ConnectionStateComponent(mapOf(a to damage)) }
        return b.build()
    }

    private val repairOnly = listOf(
        // Break the stored `ab` reserve for repair energy — light-independent, so the test doesn't depend on
        // where the moving daylight band happens to be.
        Gene(EnergySource.BreakBond("ab"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Repair)),
    )

    @Test
    fun repairGeneHealsConnectionDamage() {
        val initial = damagedPair(repairOnly, damage = 2.5f)
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 40)
        assertTrue(springCount(state) > 0, "repair + light should keep the connection alive")
        assertTrue(maxConnectionDamage(state) < 2.5f, "repair should have reduced the damage; got ${maxConnectionDamage(state)}")
        assertEquals(total0, totalAtoms(state), "repair must conserve matter")
    }

    @Test
    fun withoutRepairGeneDamageIsNotHealed() {
        val state = run(damagedPair(emptyList(), damage = 2.5f), ticks = 40)
        assertTrue(maxConnectionDamage(state) >= 2.5f, "without a repair gene damage must not heal; got ${maxConnectionDamage(state)}")
    }

    @Test
    fun matterDiffusesAcrossTheGridConservingAtoms() {
        val seed = CytoMatterGrid.empty()
        val center = seed.indexOf(0f, 0f)
        seed.deposit(center, "a", 1000)
        val initial = run {
            val b = SimBuilder(SimState())
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(seed) }
            b.build()
        }
        fun grid(s: SimState) = s.components.getTable<CytoMatterGridComponent>().asMap().getValue(GRID_SINGLETON).grid
        val total0 = grid(initial).totalAtoms()
        val centerStart = grid(initial).count(center, "a")
        val state = run(initial, ticks = 200) { s, t -> assertEquals(total0, grid(s).totalAtoms(), "diffusion must conserve atoms at step $t") }
        val g = grid(state)
        assertTrue(g.count(center, "a") < centerStart, "matter should have diffused out of the centre (was $centerStart)")
        val right = g.indexOf(CytoMatterGrid.SPAN / CytoMatterGrid.RES, 0f)
        assertTrue(g.count(right, "a") > 0, "matter should have spread to a neighbouring cell")
    }

    /** A flex gene of [action] powered by breaking stored `ab` (no light needed), always gated on. */
    private fun flexGenome(action: ActionType) = listOf(
        Gene(EnergySource.BreakBond("ab"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(action)),
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
            b.spawnCell(CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("ab" to 200), biomass = mapOf("ab" to 8000), genome = flexGenome(ActionType.Contract))
            b.spawnCell(CytoUnits.coord2(40f, 0f), Coord2.zero, CellType.Blank, cytoplasm = mapOf("ab" to 200), biomass = mapOf("ab" to 8000), genome = emptyList())
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
            b.spawnCell(CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("ab" to 80), biomass = mapOf("ab" to 8000), genome = flexGenome(ActionType.Contract))
            b.spawnCell(CytoUnits.coord2(20f, 0f), Coord2.zero, CellType.Blank, cytoplasm = mapOf("ab" to 80), biomass = mapOf("ab" to 8000), genome = emptyList())
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
            Gene(EnergySource.BreakBond("ab"), GeneCondition(Operand.Touching, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Contract)),
        )
        // Spawn at the biomass baseline radius (sqrt(8000/16000) ≈ 0.7) so Contract has room to bite on tick 1
        // (a cell already at MIN_RADIUS couldn't shrink); a shallow overlap touches without welding.
        fun cell(b: SimBuilder, x: Float) =
            b.spawnCell(CytoUnits.coord2(x, 0f), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("ab" to 100000), biomass = mapOf("ab" to 8000), logicalRadius = org.emerge.sim.core.physics.primitives.Frac(7, 10), genome = touchContract)
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
}
