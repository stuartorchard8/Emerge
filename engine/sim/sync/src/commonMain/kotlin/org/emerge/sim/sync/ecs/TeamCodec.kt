package org.emerge.sim.sync.ecs

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.physics.components.TeamComponent

object TeamCodec : ComponentCodec<TeamComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val c = s.getTable<TeamComponent>()[id]
        w.writeInt(c?.teamId?.value ?: -1)
    }
    override fun decode(c: ByteCursor): TeamComponent? {
        val teamIdRaw = c.readInt()
        if (teamIdRaw < 0) return null
        return TeamComponent(
            teamId = TeamId(teamIdRaw),
        )
    }
}
