package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimState

/**
 * Struct-of-arrays store for the cyto simulation — the spike prototype for an engine-wide
 * SoA rework (see /home/stu/.claude/plans/cozy-swimming-yao.md). Every per-cell field is a
 * dense column indexed by a slot `0 until count`; the spring topology is a CSR adjacency.
 * A [CytoSoaReducer] mutates this store in place each tick (double-buffering pos/vel) with
 * NO `SimState` rebuild — that's the structural win over the immutable-snapshot ECS.
 *
 * Scope of the spike (energy-only chemistry): every cell in the benchmark/equivalence
 * scenarios carries exactly one chemical, `"energy"`, because reactions only fire for
 * enzyme-bearing cells (none here). So energy is a dense column and the open-ended sparse
 * chemistry / species registry is deferred to the generalization. [fromSimState] asserts the
 * input is energy-only.
 *
 * Slot order is kept ascending-by-EntityId (= the engine's LinkedHashMap insertion order),
 * which is what makes the per-tick float/fixed accumulation bit-identical to [CytoReducer].
 */
class CytoWorld private constructor(
    var count: Int,
    var capacity: Int,
    // identity
    var entityId: IntArray,
    val idToSlot: HashMap<Int, Int>,
    var nextEntityValue: Int,
    // physics (raw fixed-point; Coord = Int raw, Frac = Long raw)
    var posX: IntArray, var posY: IntArray, var ang: IntArray,
    var velX: IntArray, var velY: IntArray,
    var impX: LongArray, var impY: LongArray,
    var radiusRaw: LongArray,
    var mass: IntArray,
    // biology (Float)
    var energy: FloatArray,
    var energyPending: FloatArray,
    var logicalRadius: FloatArray,
    var divideCooldown: FloatArray,
    var touch: FloatArray,
    var type: IntArray,
    var sticky: BooleanArray,
    var stickyTemp: BooleanArray,
    // double-buffer scratch for integration (write here, then swap)
    var posXNext: IntArray, var posYNext: IntArray,
    var velXNext: IntArray, var velYNext: IntArray,
    // spring CSR adjacency (springOffset has size count+1)
    var springOffset: IntArray,
    var springOther: IntArray,      // neighbour slot
    var springOtherId: IntArray,    // neighbour EntityId.value (for lower-id solve order)
    var springRestRaw: LongArray,
    var springStiffRaw: LongArray,
    var springDampRaw: LongArray,
    var springDamage: FloatArray,
) {
    /** Total directed spring ends across the whole world. */
    val springEnds: Int get() = springOffset[count]

    companion object {
        private const val ENERGY = "energy"

        /**
         * Builds a CytoWorld from an engine [SimState], preserving ascending-EntityId order so
         * iteration matches the engine's insertion order. Runs ONCE (loader), never per tick.
         * Requires energy-only chemistry (the spike scope).
         */
        fun fromSimState(state: SimState): CytoWorld {
            val cells = state.components.getTable<CytoCellComponent>().asMap()
            val transforms = state.components.getTable<TransformComponent>()
            val motions = state.components.getTable<MotionComponent>()
            val colliders = state.components.getTable<ColliderComponent>()
            val materials = state.components.getTable<MaterialComponent>()
            val springMap = state.components.getTable<SpringConstraintComponent>().asMap()
            val damageMap = state.components.getTable<ConnectionStateComponent>().asMap()

            // Ascending-EntityId order = the engine's stable insertion order for cells that were
            // never removed (ids are monotonic). Sort to be safe regardless of table order.
            val ids = cells.keys.sortedBy { it.value }
            val n = ids.size
            val cap = maxOf(n, 16)
            val slotOf = HashMap<Int, Int>(n * 2)
            ids.forEachIndexed { slot, id -> slotOf[id.value] = slot }

            val world = CytoWorld(
                count = n, capacity = cap,
                entityId = IntArray(cap), idToSlot = slotOf,
                nextEntityValue = maxOf(state.world.lastEntityValue, ids.lastOrNull()?.value ?: 0),
                posX = IntArray(cap), posY = IntArray(cap), ang = IntArray(cap),
                velX = IntArray(cap), velY = IntArray(cap),
                impX = LongArray(cap), impY = LongArray(cap),
                radiusRaw = LongArray(cap), mass = IntArray(cap),
                energy = FloatArray(cap), energyPending = FloatArray(cap),
                logicalRadius = FloatArray(cap), divideCooldown = FloatArray(cap),
                touch = FloatArray(cap), type = IntArray(cap),
                sticky = BooleanArray(cap), stickyTemp = BooleanArray(cap),
                posXNext = IntArray(cap), posYNext = IntArray(cap),
                velXNext = IntArray(cap), velYNext = IntArray(cap),
                springOffset = IntArray(cap + 1), springOther = IntArray(0),
                springOtherId = IntArray(0), springRestRaw = LongArray(0),
                springStiffRaw = LongArray(0), springDampRaw = LongArray(0),
                springDamage = FloatArray(0),
            )

            // Per-cell columns.
            for (slot in 0 until n) {
                val id = ids[slot]
                val cell = cells.getValue(id)
                val t = transforms[id]!!
                val m = motions[id]!!
                world.entityId[slot] = id.value
                world.posX[slot] = t.pos.x.raw
                world.posY[slot] = t.pos.y.raw
                world.ang[slot] = t.ang.raw
                world.velX[slot] = m.vel.x.raw
                world.velY[slot] = m.vel.y.raw
                world.radiusRaw[slot] = colliders[id]!!.radius.raw
                world.mass[slot] = (materials[id]?.mass ?: 1u).toInt()
                require(cell.chemicals.keys.all { it == ENERGY }) {
                    "CytoWorld spike supports energy-only chemistry; got ${cell.chemicals.keys}"
                }
                require(cell.pendingTransfers.keys.all { it == ENERGY }) {
                    "CytoWorld spike supports energy-only pendingTransfers; got ${cell.pendingTransfers.keys}"
                }
                world.energy[slot] = cell.chemicals[ENERGY] ?: 0f
                world.energyPending[slot] = cell.pendingTransfers[ENERGY] ?: 0f
                world.logicalRadius[slot] = cell.logicalRadius
                world.divideCooldown[slot] = cell.divideCooldown
                world.touch[slot] = cell.touch
                world.type[slot] = cell.type.ordinal
                world.sticky[slot] = cell.sticky
                world.stickyTemp[slot] = cell.stickyTemp
                require(cell.suppression.isEmpty()) { "CytoWorld spike assumes empty suppression" }
            }

            // Spring CSR: preserve each cell's spring list order exactly.
            val totalEnds = ids.sumOf { springMap[it]?.springs?.size ?: 0 }
            world.springOther = IntArray(totalEnds)
            world.springOtherId = IntArray(totalEnds)
            world.springRestRaw = LongArray(totalEnds)
            world.springStiffRaw = LongArray(totalEnds)
            world.springDampRaw = LongArray(totalEnds)
            world.springDamage = FloatArray(totalEnds)
            var cursor = 0
            for (slot in 0 until n) {
                world.springOffset[slot] = cursor
                val id = ids[slot]
                val springs = springMap[id]?.springs ?: emptyList()
                val damage = damageMap[id]?.damage ?: emptyMap()
                for (s in springs) {
                    world.springOther[cursor] = slotOf.getValue(s.other.value)
                    world.springOtherId[cursor] = s.other.value
                    world.springRestRaw[cursor] = s.restLength.raw
                    world.springStiffRaw[cursor] = s.stiffness.raw
                    world.springDampRaw[cursor] = s.damping.raw
                    world.springDamage[cursor] = damage[s.other] ?: 0f
                    cursor++
                }
            }
            world.springOffset[n] = cursor
            return world
        }
    }

    /**
     * Exports the current store back to an engine [SimState] for equivalence comparison.
     * Loader-only (never per tick). Produces components keyed by the same EntityIds.
     */
    fun toComparison(): SoaComparison {
        val cells = HashMap<Int, ComparisonCell>(count * 2)
        for (slot in 0 until count) {
            val springs = HashMap<Int, SpringTriple>()
            val damage = HashMap<Int, Float>()
            for (k in springOffset[slot] until springOffset[slot + 1]) {
                springs[springOtherId[k]] = SpringTriple(springRestRaw[k], springStiffRaw[k], springDampRaw[k])
                damage[springOtherId[k]] = springDamage[k]
            }
            cells[entityId[slot]] = ComparisonCell(
                posX = posX[slot], posY = posY[slot], ang = ang[slot],
                velX = velX[slot], velY = velY[slot],
                radiusRaw = radiusRaw[slot],
                energy = energy[slot], energyPending = energyPending[slot],
                logicalRadius = logicalRadius[slot], divideCooldown = divideCooldown[slot],
                touch = touch[slot], type = type[slot],
                sticky = sticky[slot], stickyTemp = stickyTemp[slot],
                springs = springs, damage = damage,
            )
        }
        return SoaComparison(cells, nextEntityValue)
    }

    fun slotOf(idValue: Int): Int = idToSlot[idValue] ?: -1
}

/** Canonical, storage-agnostic projection of one cell for bit-identity comparison. */
class ComparisonCell(
    val posX: Int, val posY: Int, val ang: Int,
    val velX: Int, val velY: Int,
    val radiusRaw: Long,
    val energy: Float, val energyPending: Float,
    val logicalRadius: Float, val divideCooldown: Float,
    val touch: Float, val type: Int,
    val sticky: Boolean, val stickyTemp: Boolean,
    val springs: Map<Int, SpringTriple>,
    val damage: Map<Int, Float>,
)

class SpringTriple(val restRaw: Long, val stiffRaw: Long, val dampRaw: Long) {
    override fun equals(other: Any?): Boolean =
        other is SpringTriple && restRaw == other.restRaw && stiffRaw == other.stiffRaw && dampRaw == other.dampRaw
    override fun hashCode(): Int = (restRaw * 31 + stiffRaw) .hashCode() * 31 + dampRaw.hashCode()
}

class SoaComparison(val cells: Map<Int, ComparisonCell>, val nextEntityValue: Int)
