package org.emerge.sim.sync.ecs

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.primitives.Frac

object ColliderCodec : ComponentCodec<ColliderComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val c = s.getTable<ColliderComponent>()[id]
        w.writeInt(c?.radius?.raw?.toInt() ?: -1)
    }
    override fun decode(c: ByteCursor) = ColliderComponent(
        radius = Frac(c.readInt().toLong()),
    )
}
