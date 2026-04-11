package org.emerge.sim.sync.ecs

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.primitives.Frac

object DamageCodec : ComponentCodec<DamageComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val c = s.getTable<DamageComponent>()[id]
        w.writeInt(c?.accumulated?.raw?.toInt() ?: 0)
        w.writeInt(c?.next?.raw?.toInt() ?: 0)
    }
    override fun decode(c: ByteCursor): DamageComponent? {
        val oldDamageRaw = c.readInt()
        val newDamageRaw = c.readInt()

        if (oldDamageRaw <= 0 && newDamageRaw <= 0) return null
        return DamageComponent(
            Frac(oldDamageRaw.toLong()),
            Frac(0),
            Frac(newDamageRaw.toLong()),
        )
    }
}
