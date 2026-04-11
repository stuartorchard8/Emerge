package org.emerge.sim.sync.ecs

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

object LandingCodec : ComponentCodec<LandingAttachmentComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val c = s.getTable<LandingAttachmentComponent>()[id]
        w.writeInt(c?.parentEntityId?.value ?: -1)
        w.writeInt(c?.relativePos?.x?.raw?.toInt() ?: 0)
        w.writeInt(c?.relativePos?.y?.raw?.toInt() ?: 0)
        w.writeInt(c?.relativeAng?.raw?.toInt() ?: 0)
    }
    override fun decode(c: ByteCursor): LandingAttachmentComponent? {
        val parentEntityIdRaw = c.readInt()
        val x = c.readInt()
        val y = c.readInt()
        val a = c.readInt()

        if (parentEntityIdRaw < 0) return null
        return LandingAttachmentComponent(
            parentEntityId = EntityId(parentEntityIdRaw),
            relativePos = Frac2.raw(x, y),
            relativeAng = Frac(a.toLong())
        )
    }
}
