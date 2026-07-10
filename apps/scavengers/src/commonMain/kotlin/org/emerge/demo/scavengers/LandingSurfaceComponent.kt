package org.emerge.demo.scavengers

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.sync.ecs.ComponentCodec

/**
 * Tag for entities that triangles cannot land on safely — touching one applies lethal
 * damage. Consumed by [LandingSystem]. The renderer also iterates this table to place
 * off-screen edge indicators, since landing surfaces happen to be the entities players
 * navigate towards.
 */
data object LandingSurfaceComponent

object LandingSurfaceCodec : ComponentCodec<LandingSurfaceComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val present = s.getTable<LandingSurfaceComponent>()[id] != null
        w.writeInt(if (present) 1 else -1)
    }
    override fun decode(c: ByteCursor): LandingSurfaceComponent? {
        val raw = c.readInt()
        return if (raw >= 0) LandingSurfaceComponent else null
    }
}
