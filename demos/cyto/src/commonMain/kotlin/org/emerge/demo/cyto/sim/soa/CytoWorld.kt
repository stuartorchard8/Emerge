package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoMatterGrid
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.ecs.soa.ColliderColumnStore
import org.emerge.sim.core.ecs.soa.ComponentColumns
import org.emerge.sim.core.ecs.soa.MaterialColumnStore
import org.emerge.sim.core.ecs.soa.MotionColumnStore
import org.emerge.sim.core.ecs.soa.RenderShapeColumnStore
import org.emerge.sim.core.ecs.soa.SoaWorld
import org.emerge.sim.core.ecs.soa.TransformColumnStore
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
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
 * [CytoCellColumnStore], and the world's matter reservoir as the held [grid] singleton. The columns
 * mutate in place each tick (no per-tick `SimState` rebuild) — the structural win — read by a dense
 * slot index that is shared across all per-cell column types (every cell carries all of them, added
 * together in the source table's order, so a slot indexes the same entity everywhere).
 *
 * **Landing stage.** While the physics phases are still bridged through the AoS systems (see
 * `CytoSoaReducer`), connections are held as faithful per-entity objects — [springs] /
 * [connState] — rather than a packed CSR; the spring/connection-maintenance port (which introduces
 * the CSR + `edgeAux` damage) is a later slice. [ImpulseComponent] is deliberately **not** stored:
 * it is reset to empty every tick by `ImpulseResetSystem` before anything reads it, so it carries no
 * state across the tick boundary (the equivalence gate excludes it accordingly).
 */
class CytoWorld private constructor(
    val world: SoaWorld,
    val cells: ComponentColumns<CytoCellComponent>,
    val transform: TransformColumnStore,
    val motion: MotionColumnStore,
    val collider: ColliderColumnStore,
    val material: MaterialColumnStore,
    val renderShape: RenderShapeColumnStore,
    val cell: CytoCellColumnStore,
    /** Connection topology + accrued damage, keyed by EntityId.value (faithful per-cell objects). */
    val springs: HashMap<Int, SpringConstraintComponent>,
    val connState: HashMap<Int, ConnectionStateComponent>,
    /** The finite matter reservoir singleton — held (copy-on-write) and re-emitted on [GRID_SINGLETON]. */
    val grid: CytoMatterGrid,
) {
    val count: Int get() = cells.count
    val entityId: IntArray get() = cells.denseIds()
    fun slotOf(idValue: Int): Int = cells.slotOfValue(idValue)

    companion object {
        /**
         * Loads a [CytoWorld] from an engine [SimState], **preserving the source `CytoCellComponent`
         * table's iteration order** (do not sort) — the physics/contact systems iterate component
         * tables in order, so reproducing it is what keeps the bridged tick bit-identical to AoS.
         * Runs at the materialize boundary (loader / per-bridge), never inside a ported phase.
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
            val collider = ColliderColumnStore()
            val material = MaterialColumnStore()
            val renderShape = RenderShapeColumnStore()
            val cellStore = CytoCellColumnStore()
            world.register(TransformComponent::class, transform)
            world.register(MotionComponent::class, motion)
            world.register(ColliderComponent::class, collider)
            world.register(MaterialComponent::class, material)
            world.register(RenderShapeComponent::class, renderShape)
            val cellCols = world.register(CytoCellComponent::class, cellStore)

            for ((id, cellComp) in cellsTable) {
                world.add(id, TransformComponent::class, transforms[id] ?: TransformComponent(Coord2.zero, Coord(0)))
                world.add(id, MotionComponent::class, motions[id] ?: MotionComponent(Coord2.zero, Coord(0)))
                world.add(id, ColliderComponent::class, colliders[id] ?: ColliderComponent(Frac(0)))
                world.add(
                    id, MaterialComponent::class,
                    materials[id] ?: MaterialComponent(mass = 1u, bounce = Frac(0), rough = Frac(0)),
                )
                world.add(id, RenderShapeComponent::class, renders[id] ?: RenderShapeComponent(BodyShape.CIRCLE))
                world.add(id, CytoCellComponent::class, cellComp)
            }
            world.seedLastEntityValue(state.world.lastEntityValue)

            val springs = HashMap<Int, SpringConstraintComponent>(springTable.size)
            for ((id, comp) in springTable) springs[id.value] = comp
            val connState = HashMap<Int, ConnectionStateComponent>(damageTable.size)
            for ((id, comp) in damageTable) connState[id.value] = comp

            // Copy-on-write clone so a (future) in-place tick never reaches back into the source state.
            val grid = state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid?.copy()
                ?: CytoMatterGrid.empty()

            return CytoWorld(world, cellCols, transform, motion, collider, material, renderShape, cellStore, springs, connState, grid)
        }
    }

    /**
     * Materializes the live store back into an engine [SimState] — the inverse of [fromSimState],
     * faithful for every component the world tracks. The SoA→AoS boundary the live runtime renders,
     * saves, and (during landing) bridges through; runs once per frame, never per tick. Emits tables
     * in slot order (= the order [fromSimState] read them, so it round-trips AoS table order).
     * [ImpulseComponent] is omitted by design (transient; reset every tick before use).
     */
    fun toSimState(): SimState {
        val n = count
        val transforms = LinkedHashMap<EntityId, TransformComponent>(n)
        val motions = LinkedHashMap<EntityId, MotionComponent>(n)
        val colliders = LinkedHashMap<EntityId, ColliderComponent>(n)
        val materials = LinkedHashMap<EntityId, MaterialComponent>(n)
        val rendersOut = LinkedHashMap<EntityId, RenderShapeComponent>(n)
        val cellsOut = LinkedHashMap<EntityId, CytoCellComponent>(n)
        val springsOut = LinkedHashMap<EntityId, SpringConstraintComponent>(springs.size)
        val damagesOut = LinkedHashMap<EntityId, ConnectionStateComponent>(connState.size)

        for (slot in 0 until n) {
            val idv = entityId[slot]
            val id = EntityId(idv)
            transforms[id] = transform.gather(slot)
            motions[id] = motion.gather(slot)
            colliders[id] = collider.gather(slot)
            materials[id] = material.gather(slot)
            rendersOut[id] = renderShape.gather(slot)
            cellsOut[id] = cell.gather(slot)
            springs[idv]?.let { springsOut[id] = it }
            connState[idv]?.let { damagesOut[id] = it }
        }

        val components = ComponentStore(
            mapOf(
                TransformComponent::class to ComponentTable.fromMap(transforms),
                MotionComponent::class to ComponentTable.fromMap(motions),
                ColliderComponent::class to ComponentTable.fromMap(colliders),
                MaterialComponent::class to ComponentTable.fromMap(materials),
                RenderShapeComponent::class to ComponentTable.fromMap(rendersOut),
                CytoCellComponent::class to ComponentTable.fromMap(cellsOut),
                SpringConstraintComponent::class to ComponentTable.fromMap(springsOut),
                ConnectionStateComponent::class to ComponentTable.fromMap(damagesOut),
                CytoMatterGridComponent::class to ComponentTable.fromMap(
                    linkedMapOf(GRID_SINGLETON to CytoMatterGridComponent(grid)),
                ),
            )
        )
        return SimState(
            world = EcsWorld(world.liveIds.toMutableSet(), world.lastEntityValue),
            components = components,
            contacts = emptyList(),
            randomSeed = world.randomSeed,
            tick = world.tick,
        )
    }
}
