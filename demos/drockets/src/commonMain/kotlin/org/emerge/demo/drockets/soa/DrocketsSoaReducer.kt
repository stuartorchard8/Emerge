package org.emerge.demo.drockets.soa

import org.emerge.demo.drockets.DrocketsConfig
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.soa.ImpulseColumnStore
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * Struct-of-arrays tick for the drockets simulation on a persistent [DrocketsWorld] — the
 * Phase-2 analogue of cyto's [org.emerge.demo.cyto.sim.soa.CytoSoaReducer], assembled **one
 * phase at a time**, each gated bit-identical against its array-of-structs counterpart
 * (`DrocketsSoaPhaseEquivalenceTest`) before the next is added.
 *
 * Math reconstructs the engine value types from column reads and reuses the exact operators, so
 * results are bit-identical by construction (the cyto lesson).
 *
 * **Impulse model.** Impulse is a *dense per-entity accumulator* over the force-receiving
 * (Material) entities, zeroed at [reset]; force phases add into it in place (no sparse
 * add-out-of-id-order, which the ascending-id column would reject). Its end-of-tick table
 * content is transient (consumed by `integrate`, cleared next tick), so dense-with-zeros vs the
 * AoS sparse table is invisible to future state and excluded from equivalence comparison.
 *
 * Ported so far: `reset`, `forceGather` (gravity + atmosphere drag), `integrate`.
 */
class DrocketsSoaReducer(private val cfg: DrocketsConfig) {

    // ── reset (engine ImpulseResetSystem) ───────────────────────────────────────
    /** Rebuilds Impulse as a dense zero accumulator over Material entities (ascending id). */
    fun reset(w: DrocketsWorld) {
        val impulse = w.world.columns(ImpulseComponent::class)
        val material = w.world.columns(MaterialComponent::class)
        impulse.clear()
        for (slot in 0 until material.count) impulse.put(material.entityAt(slot), ImpulseComponent())
    }

    // ── forceGather (engine GravitySystem + drockets AtmosphereDragSystem) ───────
    // Both only ADD to Impulse and read components no one writes this phase, so the AoS
    // `.isolated()` fork/merge is equivalent to this in-place sequential pass (additive,
    // commutative Frac sums → order-independent).
    fun forceGather(w: DrocketsWorld) {
        gravity(w)
        atmosphereDrag(w)
    }

    private fun addVel(store: ImpulseColumnStore, slot: Int, v: Frac2) {
        store.velX[slot] += v.x.raw; store.velY[slot] += v.y.raw
    }

    /** Inverse-(dist-1)^8 gravity between CIRCLE sources and non-CIRCLE ships (ported from GravitySystem). */
    private fun gravity(w: DrocketsWorld) {
        if (cfg.gravityNumerator.sign <= 0) return
        val materialCols = w.world.columns(MaterialComponent::class)
        val transformCols = w.world.columns(TransformComponent::class)
        val colliderCols = w.world.columns(ColliderComponent::class)
        val renderCols = w.world.columns(RenderShapeComponent::class)
        val landingCols = w.world.columns(LandingAttachmentComponent::class)
        val impulseCols = w.world.columns(ImpulseComponent::class)
        val impulseStore = impulseCols.store as ImpulseColumnStore

        // Partition into sources (CIRCLE) and ships (other), excluding attached bodies — same
        // shape as GravitySystem.partition, sourced from columns in ascending-id order.
        val srcId = ArrayList<EntityId>(); val srcT = ArrayList<TransformComponent>()
        val srcM = ArrayList<MaterialComponent>(); val srcR = ArrayList<Frac>()
        val shipId = ArrayList<EntityId>(); val shipT = ArrayList<TransformComponent>()
        val shipM = ArrayList<MaterialComponent>(); val shipR = ArrayList<Frac>()
        for (slot in 0 until materialCols.count) {
            val id = materialCols.entityAt(slot)
            if (landingCols.slotOf(id) >= 0) continue
            val t = transformCols.gather(id) ?: continue
            val m = materialCols.gatherAt(slot)
            val c = colliderCols.gather(id) ?: continue
            val shape = renderCols.gather(id)?.shape ?: continue
            if (shape == BodyShape.CIRCLE) { srcId.add(id); srcT.add(t); srcM.add(m); srcR.add(c.radius) }
            else { shipId.add(id); shipT.add(t); shipM.add(m); shipR.add(c.radius) }
        }
        if (srcId.isEmpty() || shipId.isEmpty()) return

        // Accumulate per-entity impulse deltas (Frac add is commutative → order-independent),
        // then apply onto the dense accumulator.
        val deltas = LinkedHashMap<EntityId, ImpulseComponent>()
        for (s in shipId.indices) {
            val shipTransform = shipT[s]; val shipMaterial = shipM[s]; val shipRadius = shipR[s]
            for (k in srcId.indices) {
                val sourceTransform = srcT[k]; val sourceMaterial = srcM[k]; val sourceRadius = srcR[k]
                val delta = shipTransform.pos - sourceTransform.pos
                if (delta.lenSq.raw == 0L) continue
                val minDist = shipRadius + sourceRadius
                val dist = if (delta > minDist) delta.lenSq else minDist
                val accelTowardSource = gravityAcceleration(sourceMaterial.mass, dist)
                val accelTowardShip = gravityAcceleration(shipMaterial.mass, dist)
                val normal = delta.norm
                val shipImpulse = ImpulseComponent(vel = -(normal * accelTowardSource))
                val sourceImpulse = ImpulseComponent(vel = (normal * accelTowardShip))
                deltas[shipId[s]] = shipImpulse + deltas[shipId[s]]
                deltas[srcId[k]] = sourceImpulse + deltas[srcId[k]]
            }
        }
        for ((id, delta) in deltas) {
            val slot = impulseCols.slotOf(id); if (slot < 0) continue
            addVel(impulseStore, slot, delta.vel)
        }
    }

    private fun gravityAcceleration(sourceMass: UInt, dist: Frac): Frac {
        if (dist.raw <= 0 || cfg.gravityNumerator.sign <= 0 || dist.raw >= Int.MAX_VALUE) return Frac(0)
        var n = (dist - Frac(1, 1)); n *= n; n *= n; n *= n
        return n * Frac(sourceMass.toLong()) * cfg.gravityNumerator
    }

    /** Velocity² atmospheric drag on non-landed triangles inside a planet's atmosphere. */
    private fun atmosphereDrag(w: DrocketsWorld) {
        val atmosphereCols = w.world.columns(org.emerge.demo.drockets.AtmosphereSourceComponent::class)
        if (atmosphereCols.count == 0) return
        val renderCols = w.world.columns(RenderShapeComponent::class)
        val transformCols = w.world.columns(TransformComponent::class)
        val motionCols = w.world.columns(MotionComponent::class)
        val colliderCols = w.world.columns(ColliderComponent::class)
        val landingCols = w.world.columns(LandingAttachmentComponent::class)
        val impulseCols = w.world.columns(ImpulseComponent::class)
        val impulseStore = impulseCols.store as ImpulseColumnStore

        val planetIds = ArrayList<EntityId>(atmosphereCols.count)
        for (slot in 0 until atmosphereCols.count) planetIds.add(atmosphereCols.entityAt(slot))

        val deltas = LinkedHashMap<EntityId, ImpulseComponent>()
        for (slot in 0 until renderCols.count) {
            if (renderCols.gatherAt(slot).shape != BodyShape.TRIANGLE) continue
            val id = renderCols.entityAt(slot)
            if (landingCols.slotOf(id) >= 0) continue
            val transform = transformCols.gather(id) ?: continue
            val motion = motionCols.gather(id) ?: continue
            for (planetId in planetIds) {
                val planetTransform = transformCols.gather(planetId) ?: continue
                val planetCollider = colliderCols.gather(planetId) ?: continue
                val planetMotion = motionCols.gather(planetId) ?: continue
                val delta = transform.pos - planetTransform.pos
                val dist = delta.len
                val surfaceRadius = planetCollider.radius
                val atmosphereRadius = surfaceRadius + ATMOSPHERE_DEPTH
                if (dist.raw <= surfaceRadius.raw || dist.raw >= atmosphereRadius.raw) continue
                val elevation = dist - surfaceRadius
                val depthFrac = Frac(1, 1) - elevation / ATMOSPHERE_DEPTH
                if (depthFrac.raw <= 0) continue
                val airspeed = motion.vel - planetMotion.surfaceVelocityAtOffset(delta.norm, dist)
                val depth2 = depthFrac * depthFrac
                val dragX = airspeed.x * Frac.abs(airspeed.x) * depth2 * DRAG_COEFFICIENT
                val dragY = airspeed.y * Frac.abs(airspeed.y) * depth2 * DRAG_COEFFICIENT
                val drag = ImpulseComponent(vel = Frac2(-dragX, -dragY))
                deltas[id] = deltas[id]?.plus(drag) ?: drag
            }
        }
        for ((id, impulse) in deltas) {
            val slot = impulseCols.slotOf(id); if (slot < 0) continue
            addVel(impulseStore, slot, impulse.vel)
        }
    }

    // ── integrate (engine IntegrationSystem) ─────────────────────────────────────
    /** Semi-implicit Euler over every Motion-bearing entity (per-entity, order-independent). */
    fun integrate(w: DrocketsWorld) {
        val motionCols = w.world.columns(MotionComponent::class)
        val transformCols = w.world.columns(TransformComponent::class)
        val impulseCols = w.world.columns(ImpulseComponent::class)
        val count = motionCols.count
        for (slot in 0 until count) {
            val id = motionCols.entityAt(slot)
            val tSlot = transformCols.slotOf(id)
            if (tSlot < 0) continue
            val motion = motionCols.gatherAt(slot)
            val transform = transformCols.gatherAt(tSlot)
            val iSlot = impulseCols.slotOf(id)
            val impulse = if (iSlot < 0) ImpulseComponent() else impulseCols.gatherAt(iSlot)

            val vel = motion.vel + impulse.vel
            val pos = transform.pos + impulse.pos + vel.asFrac2()
            val ang = transform.ang + Frac(motion.angVel.raw.toLong()) + impulse.angVel / 2
            val angVel = motion.angVel + impulse.angVel

            transformCols.put(id, transform.copy(pos = pos, ang = ang))
            motionCols.put(id, motion.copy(vel = vel, angVel = angVel))
        }
    }

    companion object {
        private val ATMOSPHERE_DEPTH = Frac(1, 32)
        private val DRAG_COEFFICIENT = Frac(64, 1)
    }
}
