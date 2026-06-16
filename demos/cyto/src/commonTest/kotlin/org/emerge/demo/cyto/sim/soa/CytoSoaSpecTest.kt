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
        val state = run(initial, ticks = 1200)   // first division ~tick 988 under the moving daylight band
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
        val importGene = Gene(
            EnergySource.Light,
            GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)),
            GeneAction(ActionType.Import, "a"),
        )
        fun uptakeFrom(startCytoA: Int): Int {
            val initial = run {
                val b = SimBuilder(SimState())
                b.spawnCell(
                    CytoUnits.coord2(sx, sy), Coord2.zero, CellType.Collector,
                    cytoplasm = mapOf("a" to startCytoA), biomass = mapOf("ab" to 1000), genome = listOf(importGene),
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
        val state = run(createCytoInitialState(), ticks = 1200)   // first division ~tick 988 under moving light
        val cells = state.components.getTable<CytoCellComponent>().asMap().values
        assertTrue(cells.size > 1, "expected a colony")
        assertTrue(cells.all { it.genome == AUTOTROPH_GENES }, "every cell should inherit the autotroph genome")
    }

    /** Two Collector cells on a light source, spring-connected, connection pre-damaged near the break
     *  threshold. */
    private fun damagedPair(genome: List<Gene>, damage: Float): SimState {
        val (sx, sy) = CytoLightField.SOURCES.first()
        val b = SimBuilder(SimState(randomSeed = 1))
        b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterGrid.seeded()) }
        val a = b.spawnCell(CytoUnits.coord2(sx, sy), Coord2.zero, CellType.Collector, biomass = mapOf("ab" to 8000), genome = genome)
        val c = b.spawnCell(CytoUnits.coord2(sx + 0.5f, sy), Coord2.zero, CellType.Collector, biomass = mapOf("ab" to 8000), genome = genome)
        addSpring(b, a, c, cfg)
        b.update<ConnectionStateComponent>(a) { ConnectionStateComponent(mapOf(c to damage)) }
        b.update<ConnectionStateComponent>(c) { ConnectionStateComponent(mapOf(a to damage)) }
        return b.build()
    }

    private val repairOnly = listOf(
        Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Repair)),
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
    fun flexActionsResizeRadiusAroundBiomassBaseline() {
        // Three cells with identical biomass (⇒ identical baseline radius), far apart so they never
        // interact: one Expand gene, one Contract gene, one inert control. Each flex gene is fuelled by
        // breaking stored `ab`. After a few ticks the expander sits above the baseline and the contractor
        // below it — and the actions move no matter (radius is not a conserved quantity).
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("ab" to 200), biomass = mapOf("ab" to 8000), genome = flexGenome(ActionType.Expand))
            b.spawnCell(CytoUnits.coord2(20f, 0f), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("ab" to 200), biomass = mapOf("ab" to 8000), genome = flexGenome(ActionType.Contract))
            b.spawnCell(CytoUnits.coord2(40f, 0f), Coord2.zero, CellType.Blank, cytoplasm = mapOf("ab" to 200), biomass = mapOf("ab" to 8000), genome = emptyList())
            b.build()
        }
        val ids = initial.components.getTable<CytoCellComponent>().asMap().keys.sortedBy { it.value }
        val (expandId, contractId, controlId) = ids
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 10) { s, t -> assertEquals(total0, totalAtoms(s), "flex must move no matter; broke at $t") }
        assertTrue(radiusRaw(state, expandId) > radiusRaw(state, controlId), "Expand should hold the radius above the biomass baseline")
        assertTrue(radiusRaw(state, contractId) < radiusRaw(state, controlId), "Contract should hold the radius below the biomass baseline")
    }

    @Test
    fun expandedRadiusRelaxesBackToBaselineWhenTheGeneStops() {
        // The muscle property: a held expansion springs back to the biomass baseline once the gene can no
        // longer fire (here, its `ab` fuel runs out) — via the same elastic blend that grows a cell.
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("ab" to 80), biomass = mapOf("ab" to 8000), genome = flexGenome(ActionType.Expand))
            b.spawnCell(CytoUnits.coord2(20f, 0f), Coord2.zero, CellType.Blank, cytoplasm = mapOf("ab" to 80), biomass = mapOf("ab" to 8000), genome = emptyList())
            b.build()
        }
        val ids = initial.components.getTable<CytoCellComponent>().asMap().keys.sortedBy { it.value }
        val flexId = ids[0]; val controlId = ids[1]
        val expanded = run(initial, ticks = 6)
        assertTrue(radiusRaw(expanded, flexId) > radiusRaw(expanded, controlId), "should be expanded while fuelled")
        val relaxed = run(expanded, ticks = 300)
        assertTrue(radiusRaw(relaxed, flexId) < radiusRaw(expanded, flexId), "radius should fall back once the gene stops firing")
        val ctrl = radiusRaw(relaxed, controlId)
        assertTrue(kotlin.math.abs(radiusRaw(relaxed, flexId) - ctrl) < ctrl / 16, "radius should relax back to the biomass baseline")
    }

    @Test
    fun touchingConditionGatesAGeneOnUnweldedCellContact() {
        // A gene that fires only while in contact with a cell it isn't welded to. Two cells placed barely
        // overlapping (so they touch + repel, not weld) each Expand on contact, fuelled by breaking stored
        // `ab`; a lone control with the identical gene never touches anything, so its gate stays false.
        val touchExpand = listOf(
            Gene(EnergySource.BreakBond("ab"), GeneCondition(Operand.Touching, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Expand)),
        )
        fun cell(b: SimBuilder, x: Float) =
            b.spawnCell(CytoUnits.coord2(x, 0f), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("ab" to 100000), biomass = mapOf("ab" to 8000), genome = touchExpand)
        val initial = run {
            val b = SimBuilder(SimState())
            cell(b, -0.22f); cell(b, 0.22f)   // ~0.06 overlap at MIN_RADIUS ⇒ touch, not weld
            cell(b, 20f)                       // lone control, never in contact
            b.build()
        }
        val ids = initial.components.getTable<CytoCellComponent>().asMap().keys.sortedBy { it.value }
        val (aId, bId, controlId) = ids
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 2) { s, t -> assertEquals(total0, totalAtoms(s), "touch gating must conserve matter; broke at $t") }
        val control = radiusRaw(state, controlId)
        assertTrue(radiusRaw(state, aId) > control, "a touched cell should have fired its Touching-gated Expand (got ${radiusRaw(state, aId)} vs control $control)")
        assertTrue(radiusRaw(state, bId) > control, "both touching cells should have fired")
    }
}
