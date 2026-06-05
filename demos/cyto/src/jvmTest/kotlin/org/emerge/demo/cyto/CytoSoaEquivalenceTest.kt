package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.demo.cyto.sim.systems.addSpring
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.fail

/**
 * Bit-identity gate for the SoA spike: the [CytoSoaReducer] over a [CytoWorld] must produce
 * byte-identical state to the engine [CytoReducer] tick for tick. Without this, any benchmark
 * number is meaningless. Settled grid colony (no division/death/reactions) is the primary
 * target and the only thing the go/no-go number depends on.
 */
class CytoSoaEquivalenceTest {

    private val cfg = CytoConfig()
    private val checkpoints = intArrayOf(1, 2, 8, 64, 250)

    @Test
    fun settledColonyIsBitIdentical() {
        for (n in intArrayOf(250, 1000, 4000)) runScenario(buildColony(n), "settled-seq-$n", null)
    }

    @Test
    fun settledColonyParallelIsBitIdentical() {
        val executor = ParallelExecutor()
        try {
            for (n in intArrayOf(1000, 4000)) runScenario(buildColony(n), "settled-par-$n", executor)
        } finally {
            executor.close()
        }
    }

    private fun runScenario(initial: SimState, label: String, executor: ParallelExecutor?) {
        val reducer = CytoReducer()
        val soa = CytoSoaReducer(cfg, executor)
        val world = CytoWorld.fromSimState(initial)
        val input = mapOf(PlayerId(0) to CytoInput())
        var ref = initial
        val maxTick = checkpoints.max()
        for (tick in 1..maxTick) {
            ref = reducer.reduce(cfg, ref, input)
            soa.tick(world)
            if (tick in checkpoints) compare(ref, world, label, tick)
        }
    }

    private fun compare(ref: SimState, world: CytoWorld, label: String, tick: Int) {
        val refCells = project(ref)
        val soaCells = world.toComparison().cells
        if (refCells.keys != soaCells.keys) {
            fail("$label tick=$tick: cell-id set differs (ref ${refCells.size}, soa ${soaCells.size})")
        }
        for ((id, r) in refCells) {
            val s = soaCells.getValue(id)
            fun bad(field: String, refV: Any?, soaV: Any?): Nothing =
                fail("$label tick=$tick cell=$id field=$field: ref=$refV soa=$soaV")
            if (r.posX != s.posX) bad("posX", r.posX, s.posX)
            if (r.posY != s.posY) bad("posY", r.posY, s.posY)
            if (r.ang != s.ang) bad("ang", r.ang, s.ang)
            if (r.velX != s.velX) bad("velX", r.velX, s.velX)
            if (r.velY != s.velY) bad("velY", r.velY, s.velY)
            if (r.radiusRaw != s.radiusRaw) bad("radiusRaw", r.radiusRaw, s.radiusRaw)
            if (r.energy.toRawBits() != s.energy.toRawBits()) bad("energy", r.energy, s.energy)
            if (r.energyPending.toRawBits() != s.energyPending.toRawBits()) bad("energyPending", r.energyPending, s.energyPending)
            if (r.logicalRadius.toRawBits() != s.logicalRadius.toRawBits()) bad("logicalRadius", r.logicalRadius, s.logicalRadius)
            if (r.divideCooldown.toRawBits() != s.divideCooldown.toRawBits()) bad("divideCooldown", r.divideCooldown, s.divideCooldown)
            if (r.touch.toRawBits() != s.touch.toRawBits()) bad("touch", r.touch, s.touch)
            if (r.type != s.type) bad("type", r.type, s.type)
            if (r.springs != s.springs) bad("springs", r.springs, s.springs)
            // damage compared via raw bits per entry
            if (r.damage.keys != s.damage.keys) bad("damage.keys", r.damage.keys, s.damage.keys)
            for ((nb, dv) in r.damage) {
                if (dv.toRawBits() != (s.damage[nb] ?: Float.NaN).toRawBits()) bad("damage[$nb]", dv, s.damage[nb])
            }
        }
    }

    /** Project an engine SimState to the same comparison form CytoWorld.toComparison() yields. */
    private fun project(state: SimState): Map<Int, org.emerge.demo.cyto.sim.soa.ComparisonCell> {
        val cells = state.components.getTable<CytoCellComponent>().asMap()
        val transforms = state.components.getTable<TransformComponent>()
        val motions = state.components.getTable<MotionComponent>()
        val colliders = state.components.getTable<ColliderComponent>()
        val springs = state.components.getTable<SpringConstraintComponent>().asMap()
        val damages = state.components.getTable<ConnectionStateComponent>().asMap()
        val out = HashMap<Int, org.emerge.demo.cyto.sim.soa.ComparisonCell>(cells.size * 2)
        for ((id, cell) in cells) {
            val t = transforms[id]!!
            val m = motions[id]!!
            val springMap = HashMap<Int, org.emerge.demo.cyto.sim.soa.SpringTriple>()
            val damageMap = HashMap<Int, Float>()
            springs[id]?.springs?.forEach { sp ->
                springMap[sp.other.value] = org.emerge.demo.cyto.sim.soa.SpringTriple(sp.restLength.raw, sp.stiffness.raw, sp.damping.raw)
            }
            damages[id]?.damage?.forEach { (nb, dv) -> damageMap[nb.value] = dv }
            out[id.value] = org.emerge.demo.cyto.sim.soa.ComparisonCell(
                posX = t.pos.x.raw, posY = t.pos.y.raw, ang = t.ang.raw,
                velX = m.vel.x.raw, velY = m.vel.y.raw,
                radiusRaw = colliders[id]!!.radius.raw,
                energy = cell.chemicals["energy"] ?: 0f,
                energyPending = cell.pendingTransfers["energy"] ?: 0f,
                logicalRadius = cell.logicalRadius, divideCooldown = cell.divideCooldown,
                touch = cell.touch, type = cell.type.ordinal,
                sticky = cell.sticky, stickyTemp = cell.stickyTemp,
                springs = springMap, damage = damageMap,
            )
        }
        return out
    }

    /** Same settled mesh as CytoPerfBenchmark.buildColony: ~1-in-20 Support, rest Blank. */
    private fun buildColony(targetCells: Int): SimState {
        val builder = SimBuilder(SimState())
        val side = ceil(sqrt(targetCells.toDouble())).toInt()
        val spacing = 2.0f
        val grid = arrayOfNulls<EntityId>(side * side)
        var placed = 0
        for (row in 0 until side) {
            for (col in 0 until side) {
                if (placed >= targetCells) break
                val x = (col - side / 2) * spacing
                val y = (row - side / 2) * spacing
                val support = placed % 20 == 0
                grid[row * side + col] = builder.spawnCell(
                    pos = CytoUnits.coord2(x, y), vel = Coord2.zero,
                    type = if (support) CellType.Support else CellType.Blank,
                    chemicals = mapOf("energy" to 8f), logicalRadius = 1f,
                )
                placed++
            }
        }
        for (row in 0 until side) {
            for (col in 0 until side) {
                val id = grid[row * side + col] ?: continue
                if (col + 1 < side) grid[row * side + col + 1]?.let { addSpring(builder, id, it, cfg) }
                if (row + 1 < side) grid[(row + 1) * side + col]?.let { addSpring(builder, id, it, cfg) }
            }
        }
        return builder.build()
    }
}
