package org.emerge.sim.sync.ecs

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.physics.components.PlanetComponent

object PlanetCodec : ComponentCodec<PlanetComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val c = (s.getTable<PlanetComponent>()[id])
        w.writeInt(c?.seed ?: -1)
    }
    override fun decode(c: ByteCursor): PlanetComponent? {
        val seed = c.readInt()
        if (seed < 0) return null
        return PlanetComponent(
            seed = seed,
        )
    }
}
