package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.soa.ColliderColumnStore
import org.emerge.sim.core.ecs.soa.ComponentColumns
import org.emerge.sim.core.ecs.soa.ImpulseColumnStore
import org.emerge.sim.core.ecs.soa.MaterialColumnStore
import org.emerge.sim.core.ecs.soa.MotionColumnStore
import org.emerge.sim.core.ecs.soa.SoaWorld
import org.emerge.sim.core.ecs.soa.SpringCsr
import org.emerge.sim.core.ecs.soa.TransformColumnStore
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimState

/**
 * Cyto's struct-of-arrays world, now built on the **generic** [SoaWorld] framework rather than
 * a bespoke flat store: engine physics components live in their stock column stores, per-cell
 * biology in [CytoCellColumnStore], and the spring topology in a framework [SpringCsr]. The
 * [CytoSoaReducer] mutates the column field arrays in place each tick (no `SimState` rebuild)
 * — the structural win — reading them by a dense slot index.
 *
 * Every cell carries all six component types, added together at spawn in ascending-EntityId
 * order, so a given slot indexes the same entity across *all* columns and the CSR. That slot
 * alignment is what lets the hot loops cross-reference columns by a single index, and it's
 * preserved by append-spawn + tombstone + a single coherent [SoaWorld.compact] (identical
 * membership ⇒ identical remap for every column).
 *
 * Field accessors below alias the underlying column arrays / CSR so the reducer (and the
 * proven spike logic it carries) reads `posX[i]` etc. directly. Indices `0 until count` are
 * live; backing arrays may be longer (capacity).
 */
class CytoWorld private constructor(
    val world: SoaWorld,
    val cells: ComponentColumns<CytoCellComponent>,
    val transform: TransformColumnStore,
    val motion: MotionColumnStore,
    val impulse: ImpulseColumnStore,
    val collider: ColliderColumnStore,
    val material: MaterialColumnStore,
    val cell: CytoCellColumnStore,
    val csr: SpringCsr,
) {
    val count: Int get() = cells.count

    // physics columns (raw fixed-point: Coord = Int raw, Frac = Long raw)
    val posX: IntArray get() = transform.posX
    val posY: IntArray get() = transform.posY
    val ang: IntArray get() = transform.ang
    val velX: IntArray get() = motion.velX
    val velY: IntArray get() = motion.velY
    /** Per-cell accumulated velocity impulse (the only impulse channels cyto uses). */
    val impX: LongArray get() = impulse.velX
    val impY: LongArray get() = impulse.velY
    val radiusRaw: LongArray get() = collider.radius
    val mass: IntArray get() = material.mass

    // biology columns (Float / ordinal)
    val energy: FloatArray get() = cell.energy
    val energyPending: FloatArray get() = cell.energyPending
    val logicalRadius: FloatArray get() = cell.logicalRadius
    val divideCooldown: FloatArray get() = cell.divideCooldown
    val touch: FloatArray get() = cell.touch
    val type: IntArray get() = cell.type
    val sticky: BooleanArray get() = cell.sticky
    val stickyTemp: BooleanArray get() = cell.stickyTemp

    /** Dense EntityId.value per slot (`0 until count`). */
    val entityId: IntArray get() = cells.denseIds()

    fun slotOf(idValue: Int): Int = cells.slotOfValue(idValue)

    companion object {
        /**
         * Builds a CytoWorld from an engine [SimState], preserving ascending-EntityId order so
         * iteration matches the engine's insertion order. Runs ONCE (loader), never per tick.
         */
        fun fromSimState(state: SimState): CytoWorld {
            val cellsTable = state.components.getTable<CytoCellComponent>().asMap()
            val transforms = state.components.getTable<TransformComponent>()
            val motions = state.components.getTable<MotionComponent>()
            val colliders = state.components.getTable<ColliderComponent>()
            val materials = state.components.getTable<MaterialComponent>()
            val springMap = state.components.getTable<SpringConstraintComponent>().asMap()
            val damageMap = state.components.getTable<ConnectionStateComponent>().asMap()

            val ids = cellsTable.keys.sortedBy { it.value }

            val world = SoaWorld(randomSeed = state.randomSeed)
            val transform = TransformColumnStore()
            val motion = MotionColumnStore()
            val impulse = ImpulseColumnStore()
            val collider = ColliderColumnStore()
            val material = MaterialColumnStore()
            val cellStore = CytoCellColumnStore()
            world.register(TransformComponent::class, transform)
            world.register(MotionComponent::class, motion)
            world.register(ImpulseComponent::class, impulse)
            world.register(ColliderComponent::class, collider)
            world.register(MaterialComponent::class, material)
            val cellCols = world.register(CytoCellComponent::class, cellStore)

            for (id in ids) {
                world.add(id, TransformComponent::class, transforms[id]!!)
                world.add(id, MotionComponent::class, motions[id]!!)
                world.add(id, ImpulseComponent::class, ImpulseComponent())
                world.add(id, ColliderComponent::class, colliders[id]!!)
                world.add(
                    id, MaterialComponent::class,
                    materials[id] ?: MaterialComponent(mass = 1u, bounce = Frac(0), rough = Frac(0)),
                )
                world.add(id, CytoCellComponent::class, cellsTable.getValue(id))
            }
            world.seedLastEntityValue(maxOf(state.world.lastEntityValue, ids.lastOrNull()?.value ?: 0))

            // Spring CSR over the cell ordering, preserving each cell's spring-list order.
            val csr = SpringCsr.build(
                count = ids.size,
                entityIdAt = { cellCols.denseIds()[it] },
                slotOf = { cellCols.slotOfValue(it) },
                springsAt = { slot -> springMap[cellCols.entityAt(slot)]?.springs ?: emptyList() },
                edgeAuxAt = { slot, other -> damageMap[cellCols.entityAt(slot)]?.damage?.get(other) ?: 0f },
            )

            return CytoWorld(world, cellCols, transform, motion, impulse, collider, material, cellStore, csr)
        }
    }

    /**
     * Exports the current store back to a storage-agnostic projection for the bit-identity
     * comparison. Loader-only (never per tick).
     */
    fun toComparison(): SoaComparison {
        val out = HashMap<Int, ComparisonCell>(count * 2)
        for (slot in 0 until count) {
            val springs = HashMap<Int, SpringTriple>()
            val damage = HashMap<Int, Float>()
            for (k in csr.offset[slot] until csr.offset[slot + 1]) {
                springs[csr.otherId[k]] = SpringTriple(csr.restRaw[k], csr.stiffRaw[k], csr.dampRaw[k])
                damage[csr.otherId[k]] = csr.edgeAux[k]
            }
            out[entityId[slot]] = ComparisonCell(
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
        return SoaComparison(out, world.lastEntityValue)
    }
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
    override fun hashCode(): Int = (restRaw * 31 + stiffRaw).hashCode() * 31 + dampRaw.hashCode()
}

class SoaComparison(val cells: Map<Int, ComparisonCell>, val nextEntityValue: Int)
