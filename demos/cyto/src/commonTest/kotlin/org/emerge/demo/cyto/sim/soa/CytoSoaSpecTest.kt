package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.AUTOTROPH_GENES
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.ConditionType
import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoMatterGrid
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoMutation
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneAction
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.GeneCondition
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
        val state = run(initial, ticks = 300)
        assertTrue(cellCount(state) > start, "autotroph should divide into a colony; got ${cellCount(state)} from $start")
        assertTrue(springCount(state) > 0, "divided cells should be spring-connected")
    }

    @Test
    fun heterotrophLivesOffStoredMatter() {
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(
                CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Muscle,
                cytoplasm = mapOf("ab" to 40), biomass = mapOf("ab" to 8),
            )
            b.build()
        }
        val start = cellCount(initial)
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 60) { s, t -> assertEquals(total0, totalAtoms(s), "atoms not conserved at step $t") }
        assertTrue(cellCount(state) > start, "heterotroph should grow + divide off stored ab; got ${cellCount(state)}")
    }

    @Test
    fun foodWebFeedsHeterotrophFromAutotrophLeak() {
        val (sx, sy) = CytoLightField.SOURCES.first()
        val initial = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(sx, sy), Coord2.zero, CellType.Collector, cytoplasm = mapOf("a" to 4, "b" to 4), biomass = mapOf("ab" to 8))
            b.spawnCell(CytoUnits.coord2(sx + 0.3f, sy), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("ab" to 2), biomass = mapOf("ab" to 8))
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterGrid.seeded()) }
            b.build()
        }
        val total0 = totalAtoms(initial)
        val state = run(initial, ticks = 1500) { s, t -> assertEquals(total0, totalAtoms(s), "atoms not conserved at step $t") }
        val heterotrophs = state.components.getTable<CytoCellComponent>().asMap().values.count { it.type == CellType.Muscle }
        assertTrue(heterotrophs > 1, "heterotroph should have fed on the autotroph's leaked ab and divided; got $heterotrophs")
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
        val mutCfg = cfg.copy(mutationRateDenom = 200)
        val state = run(createCytoInitialState(), mutCfg, ticks = 120)

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
            Gene(EnergySource.Light, GeneCondition(ConditionType.Biomass, "", Comparison.Greater, 0), GeneAction(ActionType.Mitosis)),
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
        val state = run(createCytoInitialState(), ticks = 300)
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
        val a = b.spawnCell(CytoUnits.coord2(sx, sy), Coord2.zero, CellType.Collector, biomass = mapOf("ab" to 8), genome = genome)
        val c = b.spawnCell(CytoUnits.coord2(sx + 0.5f, sy), Coord2.zero, CellType.Collector, biomass = mapOf("ab" to 8), genome = genome)
        addSpring(b, a, c, cfg)
        b.update<ConnectionStateComponent>(a) { ConnectionStateComponent(mapOf(c to damage)) }
        b.update<ConnectionStateComponent>(c) { ConnectionStateComponent(mapOf(a to damage)) }
        return b.build()
    }

    private val repairOnly = listOf(
        Gene(EnergySource.Light, GeneCondition(ConditionType.Biomass, "", Comparison.Greater, 0), GeneAction(ActionType.Repair)),
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
        Gene(EnergySource.BreakBond("ab"), GeneCondition(ConditionType.Biomass, "", Comparison.Greater, 0), GeneAction(action)),
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
            b.spawnCell(CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("ab" to 200), biomass = mapOf("ab" to 8), genome = flexGenome(ActionType.Expand))
            b.spawnCell(CytoUnits.coord2(20f, 0f), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("ab" to 200), biomass = mapOf("ab" to 8), genome = flexGenome(ActionType.Contract))
            b.spawnCell(CytoUnits.coord2(40f, 0f), Coord2.zero, CellType.Blank, cytoplasm = mapOf("ab" to 200), biomass = mapOf("ab" to 8), genome = emptyList())
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
            b.spawnCell(CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("ab" to 80), biomass = mapOf("ab" to 8), genome = flexGenome(ActionType.Expand))
            b.spawnCell(CytoUnits.coord2(20f, 0f), Coord2.zero, CellType.Blank, cytoplasm = mapOf("ab" to 80), biomass = mapOf("ab" to 8), genome = emptyList())
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
}
