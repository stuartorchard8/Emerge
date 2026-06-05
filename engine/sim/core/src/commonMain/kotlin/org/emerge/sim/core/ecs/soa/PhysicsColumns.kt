package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
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
