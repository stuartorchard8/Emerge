package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless checks for the matter-model Cyto reducer — the parity-critical paths that can't be eyeballed
 * without a display: cells weld on contact, the autotroph genome grows + divides into a colony, the
 * genome is inherited clonally, and **matter (atoms) is conserved** through it all.
 */
class CytoReducerTest {
    // Mutation OFF by default so the base-mechanics tests are deterministic; the mutation test opts in.
    private val cfg = CytoConfig(mutationRateDenom = 0)
    private val reducer = CytoReducer()
    private val noInput = mapOf(PlayerId(0) to CytoInput.EMPTY)

    private fun cellCount(state: SimState) = state.components.getTable<CytoCellComponent>().asMap().size
    private fun springCount(state: SimState): Int =
        state.components.getTable<SpringConstraintComponent>().asMap().values.sumOf { it.springs.size }

    /** Total atoms across the reservoir + every cell's cytoplasm + biomass — the conserved quantity. */
    private fun totalAtoms(state: SimState): Long {
        var sum = state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid?.totalAtoms() ?: 0L
        for (cell in state.components.getTable<CytoCellComponent>().asMap().values) {
            for ((s, c) in cell.cytoplasm) sum += s.length.toLong() * c
            for ((s, c) in cell.biomass) sum += s.length.toLong() * c
        }
        return sum
    }

    @Test
    fun overlappingCellsWeld() {
        // Two cells 0.2 apart (sum radii ≥ 0.5; weld when penetration > ¼·sumRadii) — they spring-join.
        var state = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(-0.1f, 0f), Coord2.zero, CellType.Blank)
            b.spawnCell(CytoUnits.coord2(0.1f, 0f), Coord2.zero, CellType.Blank)
            b.build()
        }
        repeat(5) { state = reducer.reduce(cfg, state, noInput) }
        assertTrue(springCount(state) > 0, "overlapping cells should have welded")
    }

    @Test
    fun matterIsConserved() {
        // The closed matter loop: with no player input, total atoms (reservoir + cells) is bit-constant
        // every tick — through import, bonding, conversion, diffusion, degradation, division, and death.
        var state = createCytoInitialState()
        val total0 = totalAtoms(state)
        repeat(150) {
            state = reducer.reduce(cfg, state, noInput)
            assertEquals(total0, totalAtoms(state), "atoms not conserved at step ${it + 1}")
        }
    }

    @Test
    fun autotrophGrowsIntoAColony() {
        // The hand-authored autotroph (the default scene) imports a/b, builds biomass, and divides into
        // a connected colony — the end-to-end matter loop working.
        var state = createCytoInitialState()
        val start = cellCount(state)
        repeat(300) { state = reducer.reduce(cfg, state, noInput) }
        val grown = cellCount(state)
        assertTrue(grown > start, "autotroph should divide into a colony; got $grown from $start")
        assertTrue(springCount(state) > 0, "divided cells should be spring-connected")
    }

    @Test
    fun heterotrophLivesOffStoredMatter() {
        // A heterotroph (no light genes) at the dark torus centre, given a cytoplasm 'ab' reserve:
        // it breaks 'ab' for energy to power converting 'ab' into biomass and dividing — proving the
        // BreakBond energy source / food web works without light, with matter conserved.
        var state = run {
            val b = SimBuilder(SimState())
            b.spawnCell(
                CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Muscle,
                cytoplasm = mapOf("ab" to 40), biomass = mapOf("ab" to 8),
            )
            b.build()
        }
        val start = cellCount(state)
        val total0 = totalAtoms(state)
        repeat(60) {
            state = reducer.reduce(cfg, state, noInput)
            assertEquals(total0, totalAtoms(state), "atoms not conserved at step ${it + 1}")
        }
        assertTrue(cellCount(state) > start, "heterotroph should grow + divide off stored ab; got ${cellCount(state)}")
    }

    @Test
    fun foodWebFeedsHeterotrophFromAutotrophLeak() {
        // The food web: an autotroph (light) beside a heterotroph (no light, no Import). The autotroph
        // leaks its surplus `ab` to the environment (down-gradient, free); the heterotroph absorbs it
        // (free) and breaks/converts it into biomass + division. Its starter biomass (8) is below the
        // divide gate (>8), so ANY heterotroph division proves it fed on the autotroph's output.
        val (sx, sy) = CytoLightField.SOURCES.first()
        var state = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(sx, sy), Coord2.zero, CellType.Collector, cytoplasm = mapOf("a" to 4, "b" to 4), biomass = mapOf("ab" to 8))
            b.spawnCell(CytoUnits.coord2(sx + 0.3f, sy), Coord2.zero, CellType.Muscle, cytoplasm = mapOf("ab" to 2), biomass = mapOf("ab" to 8))
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterGrid.seeded()) }
            b.build()
        }
        val total0 = totalAtoms(state)
        repeat(1500) {
            state = reducer.reduce(cfg, state, noInput)
            assertEquals(total0, totalAtoms(state), "atoms not conserved at step ${it + 1}")
        }
        val heterotrophs = state.components.getTable<CytoCellComponent>().asMap().values.count { it.type == CellType.Muscle }
        assertTrue(heterotrophs > 1, "heterotroph should have fed on the autotroph's leaked ab and divided; got $heterotrophs")
    }

    @Test
    fun mutationDivergesGenomesAndConservesMatter() {
        // Per-tick genetic damage: with mutation ON, lineages diverge from the seeded autotroph genome
        // over time — and mutation never mints/destroys matter (it only edits genomes), so total atoms
        // stay conserved. (rateDenom 0 elsewhere; here a high rate to force divergence quickly.)
        val mutCfg = cfg.copy(mutationRateDenom = 200)
        var state = createCytoInitialState()
        val total0 = totalAtoms(state)
        var sawDivergence = false
        repeat(600) {
            state = reducer.reduce(mutCfg, state, noInput)
            assertEquals(total0, totalAtoms(state), "atoms not conserved under mutation at step ${it + 1}")
            if (state.components.getTable<CytoCellComponent>().asMap().values.any { c -> c.genome != AUTOTROPH_GENES }) {
                sawDivergence = true
            }
        }
        assertTrue(sawDivergence, "mutation should have diverged at least one lineage from the seeded genome")
    }

    @Test
    fun mutationDisabledIsNoOp() {
        // rateDenom 0 ⇒ mutate() is a no-op (returns the shared genome unchanged) — what keeps the
        // base-mechanics tests deterministic.
        assertEquals(null, CytoMutation.mutate(AUTOTROPH_GENES, 0) { 0 })
    }

    @Test
    fun saveRoundTripsTheMatterWorld() {
        // Grow a varied world (mutation on so genomes diverge), then encode → decode and check it
        // round-trips: cell count, every cell's genome (via GeneCodec text), spring count, and — the
        // strongest check — total atoms (reservoir + cells) preserved through the save.
        var state = createCytoInitialState()
        val mutCfg = cfg.copy(mutationRateDenom = 200)
        repeat(120) { state = reducer.reduce(mutCfg, state, noInput) }

        val bytes = org.emerge.demo.cyto.CytoSaveCodec.encode(state)
        val restored = org.emerge.demo.cyto.CytoSaveCodec.decode(bytes)

        assertEquals(cellCount(state), cellCount(restored), "cell count")
        assertTrue(cellCount(state) > 1, "expected a non-trivial colony to exercise the codec")
        assertEquals(springCount(state), springCount(restored), "spring count")
        assertEquals(totalAtoms(state), totalAtoms(restored), "total atoms not preserved through save")
        fun genomes(s: SimState) = s.components.getTable<CytoCellComponent>().asMap().values
            .map { org.emerge.demo.cyto.sim.GeneCodec.serialize(it.genome) }.sorted()
        assertEquals(genomes(state), genomes(restored), "genomes not preserved through save")
    }

    @Test
    fun degenerateDivisionKillsTheCellAndRecyclesMatter() {
        // A cell whose every molecule is count-1 can't split — neither daughter gets a whole instance,
        // so it dies and all its matter is emitted to the environment (the general rounding rule:
        // whole amounts preserved, remainders to the reservoir, never minted into a phantom cell).
        val (sx, sy) = CytoLightField.SOURCES.first()
        val divideNow = listOf(
            Gene(EnergySource.Light, GeneCondition(ConditionType.Biomass, "", Comparison.Greater, 0), GeneAction(ActionType.Mitosis)),
        )
        var state = run {
            val b = SimBuilder(SimState(randomSeed = 1))
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterGrid.empty()) }
            b.spawnCell(
                CytoUnits.coord2(sx, sy), Coord2.zero, CellType.Collector,
                cytoplasm = emptyMap(), biomass = mapOf("ab" to 1, "ba" to 1), logicalRadius = MIN_RADIUS, genome = divideNow,
            )
            b.build()
        }
        val total0 = totalAtoms(state)
        repeat(3) { state = reducer.reduce(cfg, state, noInput) }
        assertEquals(0, cellCount(state), "a cell with only count-1 molecules should die trying to divide")
        assertEquals(total0, totalAtoms(state), "its matter must be recycled to the environment, conserved")
    }

    @Test
    fun grabDoesNotFlingACellAcrossTheWorld() {
        // The mouse-joint reach cap: grabbing toward a far pointer must follow at a bounded speed, not
        // inject a teleporting one-tick velocity (which used to whip the cell's spring network — the
        // "spring spiking" on drag). Bound = grabStiffness × grabMaxReach (0.5 × 4 = 2 logical/tick).
        var state = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(0f, 0f), Coord2.zero, CellType.Blank)
            b.build()
        }
        val id = state.components.getTable<CytoCellComponent>().asMap().keys.first()
        // Pointer 500 logical units away — the old unclamped pull would fling the cell ~250 units/tick.
        state = reducer.reduce(cfg, state, mapOf(PlayerId(0) to CytoInput(grab = CytoInput.Grab(id, 500f, 0f))))
        val v = state.components.getTable<MotionComponent>().asMap().getValue(id).vel
        val speed = kotlin.math.hypot(CytoUnits.toLogical(v.x).toDouble(), CytoUnits.toLogical(v.y).toDouble())
        assertTrue(speed < 3.0, "grab should follow at a bounded speed (~2), not fling; got $speed")
    }

    @Test
    fun divisionInheritsTheGenome() {
        // Clonal inheritance: every cell in the grown colony carries the autotroph genome (the
        // heritability that makes the substrate evolvable).
        var state = createCytoInitialState()
        repeat(300) { state = reducer.reduce(cfg, state, noInput) }
        val cells = state.components.getTable<CytoCellComponent>().asMap().values
        assertTrue(cells.size > 1, "expected a colony")
        assertTrue(cells.all { it.genome == AUTOTROPH_GENES }, "every cell should inherit the autotroph genome")
    }
}
