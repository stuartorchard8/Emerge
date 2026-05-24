package org.emerge.sim.codec.ecs

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.sim.SimState
import org.emerge.sim.sync.StateCodec
import org.emerge.sim.sync.ecs.ComponentCodec

/**
 * Generic ECS-state codec built from a per-demo list of [ComponentCodec]s. Encodes only
 * engine-shape fields: the entity world, component tables, and the deterministic PRNG
 * seed. Demos that need to carry additional per-frame state (e.g. respawn queues, audio
 * events) wrap this codec inside their own state codec.
 */
class EcsNetCodecs(val componentCodecs: List<ComponentCodec<*>>) {
    val stateCodec: StateCodec<SimState> =
        object : StateCodec<SimState> {
            override fun encode(state: SimState): ByteArray {
                val w = ByteWriter()
                with(state) {
                    val motions = components.getTable<MotionComponent>()
                    val renderShapes = components.getTable<RenderShapeComponent>()
                    val serializableEntities =
                        motions.keys().filter { entityId -> renderShapes[entityId] != null }
                    w.writeInt(serializableEntities.size)
                    w.writeLong(randomSeed)
                    w.writeInt(world.lastEntityValue)
                    for (entityId in serializableEntities) {
                        w.writeInt(entityId.value)
                        for (encoder in componentCodecs) {
                            encoder.encode(w, components, entityId)
                        }
                    }
                }
                return w.toByteArray()
            }

            override fun decode(bytes: ByteArray): SimState {
                val c = ByteCursor(bytes)
                val n = c.readInt()
                require(n in 0..MAX_STATE_ENTITIES) { "Invalid entity count: $n" }
                val randomSeed = c.readLong()
                val lastEntityValue = c.readInt()
                val entities = mutableSetOf<Int>()

                var state = SimState()

                repeat(n) {
                    val entityId = EntityId(c.readInt())
                    entities += entityId.value
                    state = state.copy(
                        components = state.components.update {
                            for (codec in componentCodecs) {
                                val component = codec.decode(c)
                                if (component != null) {
                                    setRaw(entityId, component)
                                }
                            }
                        }
                    )
                }
                val decoded = state.copy(
                    world = EcsWorld(
                        entities = entities,
                        lastEntityValue = lastEntityValue,
                    ),
                    randomSeed = randomSeed,
                )
                decoded.world.lastEntityValue = lastEntityValue
                return decoded
            }
        }

    companion object {
        private const val MAX_STATE_ENTITIES = 10_000
    }
}
