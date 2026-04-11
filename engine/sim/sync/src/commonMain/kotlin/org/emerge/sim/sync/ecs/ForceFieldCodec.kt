package org.emerge.sim.sync.ecs

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.physics.components.ForceFieldComponent
import org.emerge.sim.core.physics.primitives.Frac

object ForceFieldCodec : ComponentCodec<ForceFieldComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val c = s.getTable<ForceFieldComponent>()[id]
        w.writeInt(c?.depth?.raw?.toInt() ?: 0)
        w.writeInt(c?.strength?.raw?.toInt() ?: 0)
        w.writeInt(c?.alpha?.raw?.toInt() ?: 0)
    }
    override fun decode(c: ByteCursor): ForceFieldComponent? {
        val depth = c.readInt()
        val strength = c.readInt()
        val alpha = c.readInt()

        if (depth <= 0) return null
        return ForceFieldComponent(
            depth = Frac(depth.toLong()),
            strength = Frac(strength.toLong()),
            alpha = Frac(alpha.toLong()),
        )
    }
}
