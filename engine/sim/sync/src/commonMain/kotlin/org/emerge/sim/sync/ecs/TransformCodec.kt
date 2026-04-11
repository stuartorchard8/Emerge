package org.emerge.sim.sync.ecs

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2

object TransformCodec : ComponentCodec<TransformComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val c = s.getTable<TransformComponent>()[id]
        w.writeInt(c?.pos?.x?.raw ?: 0)
        w.writeInt(c?.pos?.y?.raw ?: 0)
        w.writeInt(c?.ang?.raw ?: 0)
    }
    override fun decode(c: ByteCursor) = TransformComponent(
        pos = Coord2.raw(c.readInt(), c.readInt()),
        ang = Coord(c.readInt())
    )
}
