package org.emerge.demo.drockets.soa

import org.emerge.demo.drockets.AtmosphereSourceComponent
import org.emerge.demo.drockets.DrocketStateComponent
import org.emerge.demo.drockets.GenomeComponent
import org.emerge.demo.drockets.KnightStateComponent
import org.emerge.demo.drockets.LineageSeedComponent
import org.emerge.demo.drockets.ParticleTintComponent
import org.emerge.demo.drockets.ReproducerComponent
import org.emerge.demo.drockets.SpriteAnimationState
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.ecs.soa.ColliderColumnStore
import org.emerge.sim.core.ecs.soa.ColumnStore
import org.emerge.sim.core.ecs.soa.ComponentColumns
import org.emerge.sim.core.ecs.soa.DamageColumnStore
import org.emerge.sim.core.ecs.soa.ForceFieldColumnStore
import org.emerge.sim.core.ecs.soa.ImpulseColumnStore
import org.emerge.sim.core.ecs.soa.LandingAttachmentColumnStore
import org.emerge.sim.core.ecs.soa.MaterialColumnStore
import org.emerge.sim.core.ecs.soa.MotionColumnStore
import org.emerge.sim.core.ecs.soa.ParticleColumnStore
import org.emerge.sim.core.ecs.soa.RenderShapeColumnStore
import org.emerge.sim.core.ecs.soa.SoaWorld
import org.emerge.sim.core.ecs.soa.TransformColumnStore
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.components.ForceFieldComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimState
import kotlin.reflect.KClass

/**
 * The struct-of-arrays world for the drockets simulation, built on the generic [SoaWorld]
 * framework. Unlike cyto (homogeneous cells, aligned columns), drockets entities are
 * heterogeneous — the planet, drockets, knights, and particles each carry different component
 * sets — so columns do NOT align across types; systems resolve a component by EntityId →
 * dense slot per type ([ComponentColumns.slotOf]).
 *
 * Flat scalar components live in column stores ([DrocketsColumns]); the recursive
 * [ReproducerComponent] (nullable nested `spawn` / `spawnGenome`) stays whole in an
 * EntityId-keyed **object side-table** ([reproducers]) — the plan's cold-path fallback for
 * unbounded/recursive shapes.
 *
 * [fromSimState] / [toSimState] are the lossless loader/export bridge (round-trip-gated); the
 * reducer mutates the columns in place per tick with no `SimState` rebuild.
 */
class DrocketsWorld private constructor(
    val world: SoaWorld,
    /** Object side-table for the recursive ReproducerComponent (cold path). */
    val reproducers: LinkedHashMap<Int, ReproducerComponent>,
) {
    companion object {
        /** Column-backed component types, in a fixed registration order. */
        private val COLUMN_STORES: List<Pair<KClass<*>, () -> ColumnStore<*>>> = listOf(
            TransformComponent::class to { TransformColumnStore() },
            MotionComponent::class to { MotionColumnStore() },
            ImpulseComponent::class to { ImpulseColumnStore() },
            ColliderComponent::class to { ColliderColumnStore() },
            MaterialComponent::class to { MaterialColumnStore() },
            RenderShapeComponent::class to { RenderShapeColumnStore() },
            DamageComponent::class to { DamageColumnStore() },
            ForceFieldComponent::class to { ForceFieldColumnStore() },
            LandingAttachmentComponent::class to { LandingAttachmentColumnStore() },
            ParticleComponent::class to { ParticleColumnStore() },
            DrocketStateComponent::class to { DrocketStateColumnStore() },
            KnightStateComponent::class to { KnightStateColumnStore() },
            GenomeComponent::class to { GenomeColumnStore() },
            SpriteAnimationState::class to { SpriteAnimationColumnStore() },
            LineageSeedComponent::class to { LineageSeedColumnStore() },
            ParticleTintComponent::class to { ParticleTintColumnStore() },
            AtmosphereSourceComponent::class to { AtmosphereSourceColumnStore() },
        )

        @Suppress("UNCHECKED_CAST")
        fun fromSimState(state: SimState): DrocketsWorld {
            val world = SoaWorld(randomSeed = state.randomSeed)
            world.tick = state.tick
            for ((type, factory) in COLUMN_STORES) {
                world.register(type as KClass<Any>, factory() as ColumnStore<Any>)
            }
            // Load each column type in ascending-EntityId order (the ComponentColumns invariant).
            for ((type, _) in COLUMN_STORES) {
                val table = state.components.tables[type] ?: continue
                val cols = world.columns(type as KClass<Any>)
                for (id in table.asMap().keys.sortedBy { it.value }) {
                    cols.put(id, table.asMap().getValue(id))
                    world.ensureEntity(id)
                }
            }
            // Recursive reproducer → object side-table, ascending for stable iteration.
            val reproducers = LinkedHashMap<Int, ReproducerComponent>()
            val reproTable = state.components.getTable<ReproducerComponent>().asMap()
            for (id in reproTable.keys.sortedBy { it.value }) {
                reproducers[id.value] = reproTable.getValue(id)
                world.ensureEntity(id)
            }
            world.seedLastEntityValue(state.world.lastEntityValue)
            return DrocketsWorld(world, reproducers)
        }
    }

    /** SoA tick clock, mirroring [SimState.tick]. */
    var tick: Long
        get() = world.tick
        set(v) { world.tick = v }

    /** Exports the columns + side-table back to an engine [SimState] (loader/snapshot path). */
    @Suppress("UNCHECKED_CAST")
    fun toSimState(): SimState {
        val tables = HashMap<KClass<*>, ComponentTable<*>>()
        for (type in world.registeredTypes) {
            val cols = world.columns(type as KClass<Any>)
            if (cols.count == 0) continue
            val values = LinkedHashMap<EntityId, Any>(cols.count)
            cols.forEachAliveSlot { slot, id -> values[id] = cols.gatherAt(slot) }
            if (values.isNotEmpty()) tables[type] = ComponentTable(type, values)
        }
        if (reproducers.isNotEmpty()) {
            val values = LinkedHashMap<EntityId, ReproducerComponent>(reproducers.size)
            for ((idv, r) in reproducers) values[EntityId(idv)] = r
            tables[ReproducerComponent::class] = ComponentTable(ReproducerComponent::class, values)
        }
        val entities = world.liveIds.toMutableSet()
        return SimState(
            world = EcsWorld(entities = entities, lastEntityValue = world.lastEntityValue),
            components = ComponentStore(tables),
            randomSeed = world.randomSeed,
            tick = world.tick,
        )
    }
}
