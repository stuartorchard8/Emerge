package org.emerge.sim.codec.physics

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.model.PhysicsSnapshot
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.sync.StateCodec

object ImpulseCodec: StateCodec<PhysicsState> {
    private const val INT_SIZE_BYTES = 4
    private const val MAX_STATE_ENTITIES = 2048
    private const val IMPULSE_STATE_HEADER_INT_COUNT = 2
    private const val IMPULSE_STATE_ENTITY_INT_COUNT = 7
    private const val IMPULSE_STATE_DAMAGE_INT_COUNT = 2


    override fun encode(state: PhysicsState): ByteArray {
        val w = ByteWriter()
        with (state.raw) {
            w.writeInt(impulses.keys().size)
            val recentDamages = damages.entries().filter { it.value.last.raw > 0 }
            w.writeInt(recentDamages.size)
            for ((entityId, impulse) in impulses.entries()) {
                w.writeInt(entityId.value)
                w.writeInt(impulse.pos.x.raw.toInt())
                w.writeInt(impulse.pos.y.raw.toInt())
                w.writeInt(impulse.vel.x.raw.toInt())
                w.writeInt(impulse.vel.y.raw.toInt())
                w.writeInt(impulse.angVel.raw.toInt())
            }
            for ((entityId, damage) in recentDamages) {
                w.writeInt(entityId.value)
                w.writeInt(damage.last.raw.toInt())
            }
        }
        return w.toByteArray()
    }

    override fun decode(bytes: ByteArray): PhysicsState {
        val c = ByteCursor(bytes)
        val n = c.readInt()
        require(n in 0..MAX_STATE_ENTITIES) { "Invalid entity count: $n" }
        val d = c.readInt()
        require(d in 0..MAX_STATE_ENTITIES) { "Invalid damage count: $d" }
        val expectedSize = INT_SIZE_BYTES * (
                IMPULSE_STATE_HEADER_INT_COUNT +
                        (n * IMPULSE_STATE_ENTITY_INT_COUNT) +
                        (d * IMPULSE_STATE_DAMAGE_INT_COUNT)
                )
        require(bytes.size == expectedSize) {
            "Invalid state payload size: expected $expectedSize bytes for $n entities + $d damages, got ${bytes.size}"
        }
        val impulses = LinkedHashMap<EntityId, ImpulseComponent>(n)
        val damages = LinkedHashMap<EntityId, DamageComponent>(d)
        repeat(n) {
            val entityId = EntityId(c.readInt())
            val px = c.readInt()
            val py = c.readInt()
            val vx = c.readInt()
            val vy = c.readInt()
            val va = c.readInt()
            impulses[entityId] = ImpulseComponent(
                pos = Frac2.raw(px, py),
                vel = Frac2.raw(vx, vy),
                angVel = Frac(va.toLong()),
            )
        }

        repeat(d) {
            val entityId = EntityId(c.readInt())
            val damage = c.readInt()
            damages[entityId] = DamageComponent(Frac(0),Frac(0),Frac(damage.toLong()))
        }
        val state = PhysicsSnapshot(
            components = ComponentStore().update {
                set(ComponentTable.fromMap(impulses))
                set(ComponentTable.fromMap(damages))
            },
        ).mutable
        return state
    }
}
