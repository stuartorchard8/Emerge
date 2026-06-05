package org.emerge.sim.core.physics

import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.systems.ImpulseResetSystem
import org.emerge.sim.core.physics.systems.IntegrationSystem
import org.emerge.sim.core.physics.systems.SpringConstraintSystem
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.sim.spawnBody
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SpringConstraintSystemTest {

    private object NoTuning : PhysicsTuning {
        override val gravityNumerator = Frac(0)
        override val rollingResistance = Frac(0)
        override val collisionSpeedDamageThreshold = Frac(0)
    }

    private val REST = Frac(1, 10)        // 0.1
    private val STIFFNESS = Frac(1, 4)    // correct 25% of length error per tick
    private val DAMPING = Frac(3, 4)      // cancel 75% of relative normal velocity per tick

    /** Steps reset -> spring -> integrate for [ticks] and returns the final separation. */
    private fun settleDistance(startHalfSpacing: Frac, ticks: Int): Float {
        var state = SimState()
        val b = SimBuilder(state)
        val a = b.spawnBody(
            pos = Coord2((-startHalfSpacing).wrap(), Coord(0)),
            vel = Coord2.zero, ang = Coord(0), angVel = Coord(0),
            mass = 1u, radius = Frac(1, 50), bounce = Frac(0), rough = Frac(0),
            shape = BodyShape.CIRCLE,
        )
        val c = b.spawnBody(
            pos = Coord2(startHalfSpacing.wrap(), Coord(0)),
            vel = Coord2.zero, ang = Coord(0), angVel = Coord(0),
            mass = 1u, radius = Frac(1, 50), bounce = Frac(0), rough = Frac(0),
            shape = BodyShape.CIRCLE,
        )
        // Register the spring on the lower-id endpoint (a was created first).
        b.update<SpringConstraintComponent>(a) {
            SpringConstraintComponent(listOf(SpringConstraint(c, REST, STIFFNESS, DAMPING)))
        }
        state = b.build()

        repeat(ticks) {
            val step = SimBuilder(state)
            ImpulseResetSystem.update(NoTuning, step, emptyMap())
            SpringConstraintSystem().update(NoTuning, step, emptyMap())
            IntegrationSystem.update(NoTuning, step, emptyMap())
            state = step.build()
        }

        val transforms = state.components.getTable<TransformComponent>()
        val pa = transforms[a]!!.pos
        val pc = transforms[c]!!.pos
        return (pc - pa).len.toFloat()
    }

    @Test
    fun stretchedSpringPullsToRestLength() {
        // Start 0.2 apart (rest 0.1) — the spring should pull them together to ~rest.
        val dist = settleDistance(startHalfSpacing = Frac(1, 10), ticks = 400)
        assertTrue(abs(dist - REST.toFloat()) < 0.02f, "stretched spring settled at $dist, expected ~0.1")
    }

    @Test
    fun compressedSpringPushesToRestLength() {
        // Start 0.04 apart (rest 0.1) — the spring should push them apart to ~rest.
        val dist = settleDistance(startHalfSpacing = Frac(1, 50), ticks = 400)
        assertTrue(abs(dist - REST.toFloat()) < 0.02f, "compressed spring settled at $dist, expected ~0.1")
    }
}
