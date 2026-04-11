package org.emerge.sim.sync.ecs

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2

object MotionCodec : ComponentCodec<MotionComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val c = s.getTable<MotionComponent>()[id]
        w.writeInt(c?.vel?.x?.raw ?: 0)
        w.writeInt(c?.vel?.y?.raw ?: 0)
        w.writeInt(c?.angVel?.raw ?: 0)
    }
    override fun decode(c: ByteCursor) = MotionComponent(
        vel = Coord2.raw(c.readInt(), c.readInt()),
        angVel = Coord(c.readInt())
    )
}
