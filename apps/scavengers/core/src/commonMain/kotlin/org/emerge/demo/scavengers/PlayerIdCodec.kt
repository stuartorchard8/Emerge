package org.emerge.demo.scavengers

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.sync.ecs.ComponentCodec

object PlayerIdCodec : ComponentCodec<PlayerOwnedComponent> {
    override fun encode(w: ByteWriter, s: ComponentStore, id: EntityId) {
        val c = s.getTable<PlayerOwnedComponent>()[id]
        w.writeInt(c?.playerId?.value ?: -1)
    }
    override fun decode(c: ByteCursor): PlayerOwnedComponent? {
        val playerIdRaw = c.readInt()
        if (playerIdRaw < 0) return null
        return PlayerOwnedComponent(
            playerId = PlayerId(playerIdRaw),
        )
    }
}
