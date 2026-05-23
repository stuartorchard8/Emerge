package org.emerge.demo.scavengers

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.sync.ecs.ComponentCodec

object HomePlanetCodec : ComponentCodec<HomePlanetComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val c = s.getTable<HomePlanetComponent>()[id]
        w.writeInt(c?.teamId?.value ?: -1)
    }
    override fun decode(c: ByteCursor): HomePlanetComponent? {
        val teamIdRaw = c.readInt()
        if (teamIdRaw < 0) return null
        return HomePlanetComponent(
            teamId = TeamId(teamIdRaw),
        )
    }
}
