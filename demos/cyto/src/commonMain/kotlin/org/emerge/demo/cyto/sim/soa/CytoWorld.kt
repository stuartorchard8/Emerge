package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoSimParamsComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.PARAMS_SINGLETON
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.ecs.soa.ColliderColumnStore
import org.emerge.sim.core.ecs.soa.ComponentColumns
import org.emerge.sim.core.ecs.soa.ImpulseColumnStore
import org.emerge.sim.core.ecs.soa.MaterialColumnStore
import org.emerge.sim.core.ecs.soa.MotionColumnStore
import org.emerge.sim.core.ecs.soa.RenderShapeColumnStore
import org.emerge.sim.core.ecs.soa.SoaWorld
import org.emerge.sim.core.ecs.soa.SpringCsr
import org.emerge.sim.core.ecs.soa.TransformColumnStore
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimState

/**
 * Cyto's struct-of-arrays world for the matter model, built on the generic [SoaWorld] framework:
 * engine physics components live in their stock column stores, the per-cell biology in
 * [CytoCellColumnStore], the spring topology + accrued damage in a framework [SpringCsr], and the
 * world's matter reservoir as the held [grid] singleton. The in-place physics phases mutate the column
 * arrays / CSR directly each tick (no per-tick `SimState` rebuild) read by a dense slot index shared
 * across all per-cell columns (every cell carries all of them, added together in the source table's
 * order, so a slot indexes the same entity everywhere).
 *
 * **Impulse** is a dense per-cell accumulator (`ImpulseColumnStore`): zeroed by `reset`, written by
 * contacts/connections/forces/grab/drag, applied by `integrate`. It carries no state across the tick
 * boundary (so it's excluded from the equivalence gate and from [toSimState] unless explicitly
 * requested for a bridge). **Springs/connection-damage** round-trip through the CSR (`toSimState`
 * derives the `SpringConstraintComponent` / `ConnectionStateComponent` tables from it).
 */
class CytoWorld private constructor(
    val world: SoaWorld,
    val cells: ComponentColumns<CytoCellComponent>,
    val transform: TransformColumnStore,
    val motion: MotionColumnStore,
    val impulse: ImpulseColumnStore,
    val collider: ColliderColumnStore,
    val material: MaterialColumnStore,
    val renderShape: RenderShapeColumnStore,
    val cell: CytoCellColumnStore,
    val csr: SpringCsr,
    /** The finite matter reservoir singleton — held (copy-on-write) and re-emitted on [GRID_SINGLETON]. */
    var grid: CytoMatterField,
    /** Runtime mutation rate-denominator, or **-1 = inherit the [CytoConfig] default** (the reducer falls
     *  back to `cfg.mutationRateDenom` when this is < 0). Set explicitly by in-game control / a loaded save;
     *  round-trips via [CytoSimParamsComponent]. -1 keeps every cfg-driven test/probe + the goldens unchanged. */
    var mutationRateDenom: Int = -1,
) {
    val count: Int get() = cells.count
    val entityId: IntArray get() = cells.denseIds()
    fun slotOf(idValue: Int): Int = cells.slotOfValue(idValue)

    // physics columns (raw fixed-point: Coord = Int raw, Frac = Long raw)
    val posX: IntArray get() = transform.posX
    val posY: IntArray get() = transform.posY
    val ang: IntArray get() = transform.ang
    val velX: IntArray get() = motion.velX
    val velY: IntArray get() = motion.velY
    val angVel: IntArray get() = motion.angVel
    val impPosX: LongArray get() = impulse.posX
    val impPosY: LongArray get() = impulse.posY
    val impVelX: LongArray get() = impulse.velX
    val impVelY: LongArray get() = impulse.velY
    val impAngVel: LongArray get() = impulse.angVel
    val radiusRaw: LongArray get() = collider.radius
    val mass: IntArray get() = material.mass

    companion object {
        /**
         * Loads a [CytoWorld] from an engine [SimState], **preserving the source `CytoCellComponent`
         * table's iteration order** (do not sort) — physics/contact iterate component tables in order,
         * so reproducing it keeps the tick bit-identical to AoS. Builds the spring CSR over that order
         * (spring-list order preserved). Runs at the materialize boundary, never inside a ported phase.
         */
        fun fromSimState(state: SimState): CytoWorld {
            val cellsTable = state.components.getTable<CytoCellComponent>().asMap()
            val transforms = state.components.getTable<TransformComponent>()
            val motions = state.components.getTable<MotionComponent>()
            val colliders = state.components.getTable<ColliderComponent>()
            val materials = state.components.getTable<MaterialComponent>()
            val renders = state.components.getTable<RenderShapeComponent>()
            val springTable = state.components.getTable<SpringConstraintComponent>().asMap()
            val damageTable = state.components.getTable<ConnectionStateComponent>().asMap()

            val world = SoaWorld(randomSeed = state.randomSeed, tick = state.tick)
            val transform = TransformColumnStore()
            val motion = MotionColumnStore()
            val impulse = ImpulseColumnStore()
            val collider = ColliderColumnStore()
            val material = MaterialColumnStore()
            val renderShape = RenderShapeColumnStore()
            val cellStore = CytoCellColumnStore()
            world.register(TransformComponent::class, transform)
            world.register(MotionComponent::class, motion)
            world.register(ImpulseComponent::class, impulse)
            world.register(ColliderComponent::class, collider)
            world.register(MaterialComponent::class, material)
            world.register(RenderShapeComponent::class, renderShape)
            val cellCols = world.register(CytoCellComponent::class, cellStore)

            for ((id, cellComp) in cellsTable) {
                world.add(id, TransformComponent::class, transforms[id] ?: TransformComponent(Coord2.zero, Coord(0)))
                world.add(id, MotionComponent::class, motions[id] ?: MotionComponent(Coord2.zero, Coord(0)))
                world.add(id, ImpulseComponent::class, ImpulseComponent())
                world.add(id, ColliderComponent::class, colliders[id] ?: ColliderComponent(Frac(0)))
                world.add(
                    id, MaterialComponent::class,
                    materials[id] ?: MaterialComponent(mass = 1u, bounce = Frac(0), rough = Frac(0)),
                )
                world.add(id, RenderShapeComponent::class, renders[id] ?: RenderShapeComponent(BodyShape.CIRCLE))
                world.add(id, CytoCellComponent::class, cellComp)
            }
            world.seedLastEntityValue(state.world.lastEntityValue)

            // Spring CSR over the cell ordering, preserving each cell's spring-list order; edgeAux carries
            // the per-edge connection-stress damage (the ConnectionStateComponent value).
            val csr = SpringCsr.build(
                count = cellCols.count,
                entityIdAt = { cellCols.denseIds()[it] },
                slotOf = { cellCols.slotOfValue(it) },
                springsAt = { slot -> springTable[cellCols.entityAt(slot)]?.springs ?: emptyList() },
                edgeAuxAt = { slot, other -> damageTable[cellCols.entityAt(slot)]?.damage?.get(other) ?: 0f },
            )

            val grid = state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid?.copy()
                ?: CytoMatterField.empty()
            // -1 (absent) = inherit the cfg default; an explicit value comes from in-game control / a save.
            val mutationRateDenom = state.components.getTable<CytoSimParamsComponent>()[PARAMS_SINGLETON]?.mutationRateDenom ?: -1

            return CytoWorld(world, cellCols, transform, motion, impulse, collider, material, renderShape, cellStore, csr, grid, mutationRateDenom)
        }
    }

    /**
     * Materializes the live store back into an engine [SimState] — the inverse of [fromSimState],
     * faithful for every component the world tracks. Emits tables in slot order (= the order
     * [fromSimState] read them). Springs + connection damage are derived from the CSR. [ImpulseComponent]
     * is emitted only when [includeImpulse] (a bridge that needs the in-flight impulse); the equivalence
     * gate and the live renderer/save never need it.
     */
    fun toSimState(includeImpulse: Boolean = false): SimState {
        val n = count
        val transforms = LinkedHashMap<EntityId, TransformComponent>(n)
        val motions = LinkedHashMap<EntityId, MotionComponent>(n)
        val colliders = LinkedHashMap<EntityId, ColliderComponent>(n)
        val materials = LinkedHashMap<EntityId, MaterialComponent>(n)
        val rendersOut = LinkedHashMap<EntityId, RenderShapeComponent>(n)
        val cellsOut = LinkedHashMap<EntityId, CytoCellComponent>(n)
        val springsOut = LinkedHashMap<EntityId, SpringConstraintComponent>(n)
        val damagesOut = LinkedHashMap<EntityId, ConnectionStateComponent>(n)
        val impulsesOut = if (includeImpulse) LinkedHashMap<EntityId, ImpulseComponent>(n) else null

        for (slot in 0 until n) {
            val id = EntityId(entityId[slot])
            transforms[id] = transform.gather(slot)
            motions[id] = motion.gather(slot)
            colliders[id] = collider.gather(slot)
            materials[id] = material.gather(slot)
            rendersOut[id] = renderShape.gather(slot)
            cellsOut[id] = cell.gather(slot)
            impulsesOut?.put(id, impulse.gather(slot))

            val lo = csr.offset[slot]
            val hi = csr.offset[slot + 1]
            if (hi > lo) {
                val springList = ArrayList<SpringConstraint>(hi - lo)
                val damageMap = LinkedHashMap<EntityId, Float>(hi - lo)
                for (k in lo until hi) {
                    val other = EntityId(csr.otherId[k])
                    springList.add(SpringConstraint(other, Frac(csr.restRaw[k]), Frac(csr.stiffRaw[k]), Frac(csr.dampRaw[k])))
                    damageMap[other] = csr.edgeAux[k]
                }
                springsOut[id] = SpringConstraintComponent(springList)
                damagesOut[id] = ConnectionStateComponent(damageMap)
            }
        }

        val tables = HashMap<kotlin.reflect.KClass<*>, ComponentTable<*>>()
        tables[TransformComponent::class] = ComponentTable.fromMap(transforms)
        tables[MotionComponent::class] = ComponentTable.fromMap(motions)
        tables[ColliderComponent::class] = ComponentTable.fromMap(colliders)
        tables[MaterialComponent::class] = ComponentTable.fromMap(materials)
        tables[RenderShapeComponent::class] = ComponentTable.fromMap(rendersOut)
        tables[CytoCellComponent::class] = ComponentTable.fromMap(cellsOut)
        tables[SpringConstraintComponent::class] = ComponentTable.fromMap(springsOut)
        tables[ConnectionStateComponent::class] = ComponentTable.fromMap(damagesOut)
        tables[CytoMatterGridComponent::class] = ComponentTable.fromMap(
            linkedMapOf(GRID_SINGLETON to CytoMatterGridComponent(grid)),
        )
        // Emit the params singleton only when a value is explicitly set (≥0), so an unset (default) world
        // round-trips byte-identically — every existing test/golden builds default worlds.
        if (mutationRateDenom >= 0) tables[CytoSimParamsComponent::class] = ComponentTable.fromMap(
            linkedMapOf(PARAMS_SINGLETON to CytoSimParamsComponent(mutationRateDenom)),
        )
        if (impulsesOut != null) tables[ImpulseComponent::class] = ComponentTable.fromMap(impulsesOut)

        return SimState(
            world = EcsWorld(world.liveIds.toMutableSet(), world.lastEntityValue),
            components = ComponentStore(tables),
            contacts = emptyList(),
            randomSeed = world.randomSeed,
            tick = world.tick,
        )
    }
}
