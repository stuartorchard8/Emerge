package org.emerge.sim.sync.ecs

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.primitives.Frac

object MaterialCodec : ComponentCodec<MaterialComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val c = s.getTable<MaterialComponent>()[id]
        w.writeInt(c?.mass?.toInt() ?: -1)
        w.writeInt(c?.bounce?.raw?.toInt() ?: -1)
        w.writeInt(c?.rough?.raw?.toInt() ?: -1)
    }
    override fun decode(c: ByteCursor): MaterialComponent? {
        val mass = c.readInt()
        val bounce = c.readInt()
        val rough = c.readInt()
        if (mass <= 0 || bounce <= 0 && rough <= 0) {
            return null
        }
        return MaterialComponent(
            mass = mass.toUInt(),
            bounce = Frac(bounce.toLong()),
            rough = Frac(rough.toLong()),
        )
    }
}
