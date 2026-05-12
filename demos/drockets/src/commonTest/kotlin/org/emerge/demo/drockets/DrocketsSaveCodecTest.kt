package org.emerge.demo.drockets

import org.emerge.sim.core.TeamId
import org.emerge.sim.core.Tick
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.spawnBody
import org.emerge.sim.core.physics.model.spawnParticle
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrocketsSaveCodecTest {
    @Test
    fun encodeDecode_strips_particle_entities_from_physics_state() {
        val builder = PhysicsBuilder(PhysicsState())
        val bodyId = builder.spawnBody(
            playerId = null,
            pos = Coord2.zero,
            vel = Coord2.zero,
            ang = Coord(0),
            angVel = Coord(0),
            mass = 42u,
            radius = Frac(1, 256),
            bounce = Frac(1, 4),
            rough = Frac(1, 2),
            shape = BodyShape.CIRCLE,
        )
        val particleId = builder.spawnParticle(
            pos = Coord2.zero,
            vel = Coord2.zero,
            radius = Frac(1, 1024),
            shape = BodyShape.CIRCLE,
            lifetime = 30,
            teamId = TeamId(7),
        )
        val snapshot = DrocketsSnapshot(
            tick = Tick(123),
            state = builder.build(),
            lineage = DrocketLineageState.EMPTY,
        )

        val decoded = DrocketsSaveCodec.decode(DrocketsSaveCodec.encode(snapshot))

        assertTrue(decoded.state.motions.contains(bodyId))
        assertFalse(decoded.state.motions.contains(particleId))
        assertFalse(decoded.state.renderShapes.contains(particleId))
        assertFalse(decoded.state.colliders.contains(particleId))
        assertFalse(decoded.state.teams.contains(particleId))
        assertEquals(1, decoded.state.motions.keys().size)
    }
}
