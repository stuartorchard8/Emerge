package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.components.ForceFieldComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * Hand-written [ColumnStore]s for the engine physics components — primitive field columns
 * (Coord = Int raw, Frac = Long raw). One per component; the same field-ordered, no-reflection
 * flavour as the engine's per-component net codecs. Hot systems read these arrays directly via
 * a slot index; [scatter]/[gather] bridge to the data classes for spawn/decode and the
 * compatibility API.
 */

class TransformColumnStore : ColumnStore<TransformComponent> {
    var posX = IntArray(0); private set
    var posY = IntArray(0); private set
    var ang = IntArray(0); private set
    override fun ensureCapacity(capacity: Int) {
        if (posX.size >= capacity) return
        posX = posX.copyOf(capacity); posY = posY.copyOf(capacity); ang = ang.copyOf(capacity)
    }
    override fun scatter(slot: Int, value: TransformComponent) {
        posX[slot] = value.pos.x.raw; posY[slot] = value.pos.y.raw; ang[slot] = value.ang.raw
    }
    override fun gather(slot: Int): TransformComponent =
        TransformComponent(Coord2(Coord(posX[slot]), Coord(posY[slot])), Coord(ang[slot]))
    override fun moveSlot(dst: Int, src: Int) { posX[dst] = posX[src]; posY[dst] = posY[src]; ang[dst] = ang[src] }
}

class MotionColumnStore : ColumnStore<MotionComponent> {
    var velX = IntArray(0); private set
    var velY = IntArray(0); private set
    var angVel = IntArray(0); private set
    override fun ensureCapacity(capacity: Int) {
        if (velX.size >= capacity) return
        velX = velX.copyOf(capacity); velY = velY.copyOf(capacity); angVel = angVel.copyOf(capacity)
    }
    override fun scatter(slot: Int, value: MotionComponent) {
        velX[slot] = value.vel.x.raw; velY[slot] = value.vel.y.raw; angVel[slot] = value.angVel.raw
    }
    override fun gather(slot: Int): MotionComponent =
        MotionComponent(Coord2(Coord(velX[slot]), Coord(velY[slot])), Coord(angVel[slot]))
    override fun moveSlot(dst: Int, src: Int) { velX[dst] = velX[src]; velY[dst] = velY[src]; angVel[dst] = angVel[src] }
}

class ImpulseColumnStore : ColumnStore<ImpulseComponent> {
    var posX = LongArray(0); private set
    var posY = LongArray(0); private set
    var velX = LongArray(0); private set
    var velY = LongArray(0); private set
    var angVel = LongArray(0); private set
    override fun ensureCapacity(capacity: Int) {
        if (posX.size >= capacity) return
        posX = posX.copyOf(capacity); posY = posY.copyOf(capacity)
        velX = velX.copyOf(capacity); velY = velY.copyOf(capacity); angVel = angVel.copyOf(capacity)
    }
    override fun scatter(slot: Int, value: ImpulseComponent) {
        posX[slot] = value.pos.x.raw; posY[slot] = value.pos.y.raw
        velX[slot] = value.vel.x.raw; velY[slot] = value.vel.y.raw; angVel[slot] = value.angVel.raw
    }
    override fun gather(slot: Int): ImpulseComponent = ImpulseComponent(
        pos = Frac2(Frac(posX[slot]), Frac(posY[slot])),
        vel = Frac2(Frac(velX[slot]), Frac(velY[slot])),
        angVel = Frac(angVel[slot]),
    )
    override fun moveSlot(dst: Int, src: Int) {
        posX[dst] = posX[src]; posY[dst] = posY[src]; velX[dst] = velX[src]; velY[dst] = velY[src]; angVel[dst] = angVel[src]
    }
}

class ColliderColumnStore : ColumnStore<ColliderComponent> {
    var radius = LongArray(0); private set
    override fun ensureCapacity(capacity: Int) { if (radius.size < capacity) radius = radius.copyOf(capacity) }
    override fun scatter(slot: Int, value: ColliderComponent) { radius[slot] = value.radius.raw }
    override fun gather(slot: Int): ColliderComponent = ColliderComponent(Frac(radius[slot]))
    override fun moveSlot(dst: Int, src: Int) { radius[dst] = radius[src] }
}

class MaterialColumnStore : ColumnStore<MaterialComponent> {
    var mass = IntArray(0); private set      // UInt bits
    var bounce = LongArray(0); private set
    var rough = LongArray(0); private set
    override fun ensureCapacity(capacity: Int) {
        if (mass.size >= capacity) return
        mass = mass.copyOf(capacity); bounce = bounce.copyOf(capacity); rough = rough.copyOf(capacity)
    }
    override fun scatter(slot: Int, value: MaterialComponent) {
        mass[slot] = value.mass.toInt(); bounce[slot] = value.bounce.raw; rough[slot] = value.rough.raw
    }
    override fun gather(slot: Int): MaterialComponent =
        MaterialComponent(mass = mass[slot].toUInt(), bounce = Frac(bounce[slot]), rough = Frac(rough[slot]))
    override fun moveSlot(dst: Int, src: Int) { mass[dst] = mass[src]; bounce[dst] = bounce[src]; rough[dst] = rough[src] }
}

class RenderShapeColumnStore : ColumnStore<RenderShapeComponent> {
    var shape = IntArray(0); private set     // BodyShape.ordinal
    override fun ensureCapacity(capacity: Int) { if (shape.size < capacity) shape = shape.copyOf(capacity) }
    override fun scatter(slot: Int, value: RenderShapeComponent) { shape[slot] = value.shape.ordinal }
    override fun gather(slot: Int): RenderShapeComponent = RenderShapeComponent(BodyShape.entries[shape[slot]])
    override fun moveSlot(dst: Int, src: Int) { shape[dst] = shape[src] }
}

class ParticleColumnStore : ColumnStore<ParticleComponent> {
    var life = IntArray(0); private set
    var lifeTime = IntArray(0); private set
    override fun ensureCapacity(capacity: Int) {
        if (life.size >= capacity) return
        life = life.copyOf(capacity); lifeTime = lifeTime.copyOf(capacity)
    }
    override fun scatter(slot: Int, value: ParticleComponent) { life[slot] = value.life; lifeTime[slot] = value.lifeTime }
    override fun gather(slot: Int): ParticleComponent = ParticleComponent(life = life[slot], lifeTime = lifeTime[slot])
    override fun moveSlot(dst: Int, src: Int) { life[dst] = life[src]; lifeTime[dst] = lifeTime[src] }
}

class DamageColumnStore : ColumnStore<DamageComponent> {
    var accumulated = LongArray(0); private set
    var last = LongArray(0); private set
    var next = LongArray(0); private set
    override fun ensureCapacity(capacity: Int) {
        if (accumulated.size >= capacity) return
        accumulated = accumulated.copyOf(capacity); last = last.copyOf(capacity); next = next.copyOf(capacity)
    }
    override fun scatter(slot: Int, value: DamageComponent) {
        accumulated[slot] = value.accumulated.raw; last[slot] = value.last.raw; next[slot] = value.next.raw
    }
    override fun gather(slot: Int): DamageComponent =
        DamageComponent(Frac(accumulated[slot]), Frac(last[slot]), Frac(next[slot]))
    override fun moveSlot(dst: Int, src: Int) { accumulated[dst] = accumulated[src]; last[dst] = last[src]; next[dst] = next[src] }
}

class ForceFieldColumnStore : ColumnStore<ForceFieldComponent> {
    var depth = LongArray(0); private set
    var strength = LongArray(0); private set
    var alpha = LongArray(0); private set
    override fun ensureCapacity(capacity: Int) {
        if (depth.size >= capacity) return
        depth = depth.copyOf(capacity); strength = strength.copyOf(capacity); alpha = alpha.copyOf(capacity)
    }
    override fun scatter(slot: Int, value: ForceFieldComponent) {
        depth[slot] = value.depth.raw; strength[slot] = value.strength.raw; alpha[slot] = value.alpha.raw
    }
    override fun gather(slot: Int): ForceFieldComponent =
        ForceFieldComponent(Frac(depth[slot]), Frac(strength[slot]), Frac(alpha[slot]))
    override fun moveSlot(dst: Int, src: Int) { depth[dst] = depth[src]; strength[dst] = strength[src]; alpha[dst] = alpha[src] }
}

class LandingAttachmentColumnStore : ColumnStore<LandingAttachmentComponent> {
    var parentId = IntArray(0); private set  // EntityId.value
    var relX = LongArray(0); private set
    var relY = LongArray(0); private set
    var relAng = LongArray(0); private set
    override fun ensureCapacity(capacity: Int) {
        if (parentId.size >= capacity) return
        parentId = parentId.copyOf(capacity); relX = relX.copyOf(capacity)
        relY = relY.copyOf(capacity); relAng = relAng.copyOf(capacity)
    }
    override fun scatter(slot: Int, value: LandingAttachmentComponent) {
        parentId[slot] = value.parentEntityId.value
        relX[slot] = value.relativePos.x.raw; relY[slot] = value.relativePos.y.raw; relAng[slot] = value.relativeAng.raw
    }
    override fun gather(slot: Int): LandingAttachmentComponent = LandingAttachmentComponent(
        parentEntityId = EntityId(parentId[slot]),
        relativePos = Frac2(Frac(relX[slot]), Frac(relY[slot])),
        relativeAng = Frac(relAng[slot]),
    )
    override fun moveSlot(dst: Int, src: Int) {
        parentId[dst] = parentId[src]; relX[dst] = relX[src]; relY[dst] = relY[src]; relAng[dst] = relAng[src]
    }
}
