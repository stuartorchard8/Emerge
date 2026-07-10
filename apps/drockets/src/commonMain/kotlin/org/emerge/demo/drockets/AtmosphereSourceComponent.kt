package org.emerge.demo.drockets

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.sync.ecs.ComponentCodec

/**
 * Tag for entities that exert atmospheric drag on nearby flying drockets. Consumed by
 * [AtmosphereDragSystem]. The renderer also iterates this table to draw planet bodies
 * and to pick the camera focus fallback when no drocket is focused, since the planet
 * happens to be the world's only atmosphere source today.
 */
data object AtmosphereSourceComponent

object AtmosphereSourceCodec : ComponentCodec<AtmosphereSourceComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val present = s.getTable<AtmosphereSourceComponent>()[id] != null
        w.writeInt(if (present) 1 else -1)
    }
    override fun decode(c: ByteCursor): AtmosphereSourceComponent? {
        val raw = c.readInt()
        return if (raw >= 0) AtmosphereSourceComponent else null
    }
}
