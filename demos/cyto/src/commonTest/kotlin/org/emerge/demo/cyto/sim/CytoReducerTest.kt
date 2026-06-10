package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord2
import kotlin.math.abs
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Headless checks for the native (Box2D-free) Cyto reducer — the parity-critical paths
 * that can't be eyeballed without a display: cells weld on contact via the spring system,
 * and a stem cell divides into a connected colony.
 */
class CytoReducerTest {
    private val cfg = CytoConfig()
    private val reducer = CytoReducer()
    private val noInput = mapOf(PlayerId(0) to CytoInput.EMPTY)

    private fun springCount(state: SimState): Int =
        state.components.getTable<SpringConstraintComponent>().asMap().values.sumOf { it.springs.size }

    @Test
    fun overlappingCellsWeld() {
        // Two cells 0.2 apart (sum radii 0.5; weld threshold 0.375) — they should spring-join.
        var state = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(-0.1f, 0f), Coord2.zero, CellType.Blank, mapOf("energy" to 1f), MIN_RADIUS)
            b.spawnCell(CytoUnits.coord2(0.1f, 0f), Coord2.zero, CellType.Blank, mapOf("energy" to 1f), MIN_RADIUS)
            b.build()
        }
        repeat(5) { state = reducer.reduce(cfg, state, noInput) }
        assertTrue(springCount(state) > 0, "overlapping cells should have welded")
    }

    @Test
    fun stemCellDividesIntoConnectedColony() {
        var state = createCytoInitialState()
        repeat(700) { state = reducer.reduce(cfg, state, noInput) }
        val cellCount = state.components.getTable<CytoCellComponent>().keys().size
        assertTrue(cellCount > 1, "stem cell should divide into a colony; got $cellCount")
        assertTrue(springCount(state) > 0, "divided cells should be spring-connected")
    }

    @Test
    fun divisionInheritsTheGenomeNotTheType() {
        // Behaviour is carried by a per-cell genome, not looked up from the cell type, and division is
        // itself a gene now. Seed a founder with a CUSTOM genome — a Mitosis gene (so it divides) plus
        // a harmless marker gene — distinct from the Stem preset, and feed it from a Support neighbour.
        // Every dividing descendant must inherit that exact genome clonally (the Support, a separate
        // lineage with an empty genome, must not) — the heritability that makes the substrate evolvable.
        val customGenome = listOf(
            Gene(
                inputs = listOf(GeneInput(GeneInputType.Chem, chem = "energy", weight = 1f)),
                output = GeneOutput(GeneOutputType.Mitosis, chem1 = "", chem2 = "", bias = -STEM_MITOSIS_ENERGY_GATE),
            ),
            Gene( // marker: non-empty + distinct from the preset, with no chemical I/O of its own
                inputs = listOf(GeneInput(GeneInputType.Touch, chem = "", weight = 1f)),
                output = GeneOutput(GeneOutputType.Sticky, chem1 = "", chem2 = "", bias = 0f),
            ),
        )
        var state = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(-0.1f, 0f), Coord2.zero, CellType.Support, mapOf("energy" to 2f), MIN_RADIUS)
            b.spawnCell(CytoUnits.coord2(0.1f, 0f), Coord2.zero, CellType.Stem, mapOf("energy" to 2f), MIN_RADIUS, genome = customGenome)
            b.build()
        }
        repeat(700) { state = reducer.reduce(cfg, state, noInput) }
        val cells = state.components.getTable<CytoCellComponent>().asMap().values
        val lineage = cells.filter { it.genome.isNotEmpty() }       // the founder + its descendants (not the Support)
        assertTrue(lineage.size > 1, "the gene-driven founder should divide into a colony; got ${lineage.size}")
        assertTrue(
            lineage.all { it.genome == customGenome },
            "every descendant should inherit the seeded genome, not be reconstructed from its type",
        )
    }

    @Test
    fun collectorProducesEnergyOnlyInTheLight() {
        // A Collector turns environmental light into energy — no free lunch. One sitting on a light
        // source should fill up; an identical one parked at the dark torus centre (between all four
        // sources) should not. Proves the energy economy is anchored to the field, not minted.
        fun energyAfter(x: Float, y: Float): Float {
            val b = SimBuilder(SimState())
            val id = b.spawnCell(CytoUnits.coord2(x, y), Coord2.zero, CellType.Collector, mapOf("energy" to 1f), MIN_RADIUS)
            var s = b.build()
            repeat(40) { s = reducer.reduce(cfg, s, noInput) }
            return s.components.getTable<CytoCellComponent>()[id]?.chemicals?.get("energy") ?: 0f
        }
        val (sx, sy) = CytoLightField.SOURCES.first()
        val lit = energyAfter(sx, sy)
        val dark = energyAfter(0f, 0f)
        assertTrue(lit > 2f, "a Collector in the light should gain energy; got $lit")
        assertTrue(dark < 1.5f && dark < lit, "a Collector in the dark should not gain energy: lit=$lit dark=$dark")
    }

    @Test
    fun grabbedCellMovesTowardPointer() {
        var state = run {
            val b = SimBuilder(SimState())
            b.spawnCell(Coord2.zero, Coord2.zero, CellType.Blank, mapOf("energy" to 1f), MIN_RADIUS)
            b.build()
        }
        val id = state.components.getTable<CytoCellComponent>().keys().first()
        val grabInput = mapOf(PlayerId(0) to CytoInput(grab = CytoInput.Grab(id, 5f, 0f)))
        repeat(30) { state = reducer.reduce(cfg, state, grabInput) }
        val pos = state.components.getTable<TransformComponent>()[id]!!.pos
        assertTrue(CytoUnits.toLogical(pos.x) > 0.5f, "grabbed cell should move toward the +x pointer")
    }

    @Test
    fun weldedClusterStaysStable() {
        // A tight 2x2 clump welds into a cluster; left alone it must settle, not explode.
        // (Catches the Jacobi over-relaxation blow-up the spring relaxation fix addresses.)
        var state = run {
            val b = SimBuilder(SimState())
            val coords = listOf(-0.15f to -0.15f, 0.15f to -0.15f, -0.15f to 0.15f, 0.15f to 0.15f)
            for ((x, y) in coords) {
                b.spawnCell(CytoUnits.coord2(x, y), Coord2.zero, CellType.Blank, mapOf("energy" to 5f), MIN_RADIUS)
            }
            b.build()
        }
        repeat(300) { state = reducer.reduce(cfg, state, noInput) }

        val motions = state.components.getTable<MotionComponent>().asMap()
        assertTrue(motions.isNotEmpty(), "cluster should still have living cells")
        for ((_, motion) in motions) {
            val vx = CytoUnits.toLogical(motion.vel.x)
            val vy = CytoUnits.toLogical(motion.vel.y)
            assertTrue(
                vx.isFinite() && vy.isFinite() && abs(vx) < 50f && abs(vy) < 50f,
                "cluster velocity blew up: ($vx, $vy)",
            )
        }
    }
}
