package org.emerge.sim.sync.ecs

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore

interface ComponentCodec<T : Any> {
    fun encode(w: ByteWriter, s: ComponentStore, id: EntityId)
    fun decode(c: ByteCursor): T?
}
