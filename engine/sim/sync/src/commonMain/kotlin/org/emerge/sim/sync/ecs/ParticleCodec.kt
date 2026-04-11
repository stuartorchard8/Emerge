package org.emerge.sim.sync.ecs

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.physics.components.ParticleComponent

object ParticleCodec : ComponentCodec<ParticleComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val c = s.getTable<ParticleComponent>()[id]
        w.writeInt(c?.life ?: 0)
        w.writeInt(c?.lifeTime ?: 1)
    }
    override fun decode(c: ByteCursor): ParticleComponent? {
        val life = c.readInt()
        val lifeTime = c.readInt()
        if (life <= 0) return null
        return ParticleComponent(
            life = life,
            lifeTime = lifeTime,
        )
    }
}
