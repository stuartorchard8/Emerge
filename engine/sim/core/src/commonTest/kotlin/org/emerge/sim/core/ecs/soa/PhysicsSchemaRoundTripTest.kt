package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.components.ForceFieldComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase-0 gate: gather∘scatter must round-trip bit-identically for every new component schema
 * (data-class equality compares the raw fields). This is what the cold-system compat shim
 * leans on.
 */
class PhysicsSchemaRoundTripTest {

    private fun <T : Any> roundTrip(store: ColumnStore<T>, value: T): T {
        val cols = ComponentColumns(store)
        cols.put(EntityId(1), value)
        return cols.gather(EntityId(1))!!
    }

    @Test
    fun renderShapeRoundTrips() {
        assertEquals(RenderShapeComponent(BodyShape.TRIANGLE), roundTrip(RenderShapeColumnStore(), RenderShapeComponent(BodyShape.TRIANGLE)))
        assertEquals(RenderShapeComponent(BodyShape.CIRCLE), roundTrip(RenderShapeColumnStore(), RenderShapeComponent(BodyShape.CIRCLE)))
    }

    @Test
    fun particleRoundTrips() {
        assertEquals(ParticleComponent(7, 30), roundTrip(ParticleColumnStore(), ParticleComponent(7, 30)))
    }

    @Test
    fun damageRoundTrips() {
        val d = DamageComponent(Frac(123L), Frac(-45L), Frac(9_000_000_000L))
        assertEquals(d, roundTrip(DamageColumnStore(), d))
    }

    @Test
    fun forceFieldRoundTrips() {
        val f = ForceFieldComponent(Frac(11L), Frac(22L), Frac(33L))
        assertEquals(f, roundTrip(ForceFieldColumnStore(), f))
    }

    @Test
    fun landingAttachmentRoundTrips() {
        val l = LandingAttachmentComponent(EntityId(42), Frac2(Frac(-7L), Frac(7L)), Frac(99L))
        assertEquals(l, roundTrip(LandingAttachmentColumnStore(), l))
    }
}
