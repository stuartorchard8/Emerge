package org.emerge.sim.sync.ecs

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.primitives.BodyShape

object RenderShapeCodec : ComponentCodec<RenderShapeComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val c = s.getTable<RenderShapeComponent>()[id]
        w.writeInt(c?.shape?.wireValue ?: -1)
    }
    override fun decode(c: ByteCursor): RenderShapeComponent? {
        val shapeRaw = c.readInt()
        if (shapeRaw < 0) return null
        return RenderShapeComponent(
            shape = BodyShape.fromWireValue(shapeRaw),
        )
    }
}
