package org.emerge.demo.drockets.soa

import org.emerge.demo.drockets.AtmosphereSourceComponent
import org.emerge.demo.drockets.DrocketPhase
import org.emerge.demo.drockets.DrocketStateComponent
import org.emerge.demo.drockets.Genome
import org.emerge.demo.drockets.GenomeComponent
import org.emerge.demo.drockets.HsvColor
import org.emerge.demo.drockets.HsvColorGene
import org.emerge.demo.drockets.KnightPhase
import org.emerge.demo.drockets.KnightStateComponent
import org.emerge.demo.drockets.LineageSeedComponent
import org.emerge.demo.drockets.ParticleTintComponent
import org.emerge.demo.drockets.SpriteAnimationState
import org.emerge.demo.drockets.SpriteSheet
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.soa.ColumnStore

/**
 * Hand-written [ColumnStore]s for the drockets-specific components — primitive field columns,
 * the same reflection-free, field-ordered flavour as the engine physics schemas. Enums store
 * as their ordinal; nullable EntityId fields use a `-1` sentinel (entity ids are monotonic and
 * non-negative). The recursive [org.emerge.demo.drockets.ReproducerComponent] and the per-frame
 * lineage are NOT here — they stay in object side-tables on [DrocketsWorld] (cold path).
 */

class DrocketStateColumnStore : ColumnStore<DrocketStateComponent> {
    var phase = IntArray(0); private set       // DrocketPhase.ordinal
    var walkDirection = IntArray(0); private set
    var ticksRemaining = IntArray(0); private set
    var fuel = IntArray(0); private set
    override fun ensureCapacity(capacity: Int) {
        if (phase.size >= capacity) return
        phase = phase.copyOf(capacity); walkDirection = walkDirection.copyOf(capacity)
        ticksRemaining = ticksRemaining.copyOf(capacity); fuel = fuel.copyOf(capacity)
    }
    override fun scatter(slot: Int, value: DrocketStateComponent) {
        phase[slot] = value.phase.ordinal; walkDirection[slot] = value.walkDirection
        ticksRemaining[slot] = value.ticksRemaining; fuel[slot] = value.fuel
    }
    override fun gather(slot: Int): DrocketStateComponent = DrocketStateComponent(
        phase = DrocketPhase.entries[phase[slot]], walkDirection = walkDirection[slot],
        ticksRemaining = ticksRemaining[slot], fuel = fuel[slot],
    )
    override fun moveSlot(dst: Int, src: Int) {
        phase[dst] = phase[src]; walkDirection[dst] = walkDirection[src]
        ticksRemaining[dst] = ticksRemaining[src]; fuel[dst] = fuel[src]
    }
}

class KnightStateColumnStore : ColumnStore<KnightStateComponent> {
    var phase = IntArray(0); private set       // KnightPhase.ordinal
    var planetId = IntArray(0); private set    // EntityId.value
    var walkDirection = IntArray(0); private set
    var ticksRemaining = IntArray(0); private set
    override fun ensureCapacity(capacity: Int) {
        if (phase.size >= capacity) return
        phase = phase.copyOf(capacity); planetId = planetId.copyOf(capacity)
        walkDirection = walkDirection.copyOf(capacity); ticksRemaining = ticksRemaining.copyOf(capacity)
    }
    override fun scatter(slot: Int, value: KnightStateComponent) {
        phase[slot] = value.phase.ordinal; planetId[slot] = value.planetId.value
        walkDirection[slot] = value.walkDirection; ticksRemaining[slot] = value.ticksRemaining
    }
    override fun gather(slot: Int): KnightStateComponent = KnightStateComponent(
        phase = KnightPhase.entries[phase[slot]], planetId = EntityId(planetId[slot]),
        walkDirection = walkDirection[slot], ticksRemaining = ticksRemaining[slot],
    )
    override fun moveSlot(dst: Int, src: Int) {
        phase[dst] = phase[src]; planetId[dst] = planetId[src]
        walkDirection[dst] = walkDirection[src]; ticksRemaining[dst] = ticksRemaining[src]
    }
}

class GenomeColumnStore : ColumnStore<GenomeComponent> {
    var walkMin = IntArray(0); private set
    var walkMax = IntArray(0); private set
    var charge = IntArray(0); private set
    var fuel = IntArray(0); private set
    var spin = IntArray(0); private set
    var thrust = IntArray(0); private set
    var bodyH = IntArray(0); private set
    var bodyS = IntArray(0); private set
    var bodyV = IntArray(0); private set
    var fireH = IntArray(0); private set
    var fireS = IntArray(0); private set
    var fireV = IntArray(0); private set
    override fun ensureCapacity(capacity: Int) {
        if (walkMin.size >= capacity) return
        walkMin = walkMin.copyOf(capacity); walkMax = walkMax.copyOf(capacity)
        charge = charge.copyOf(capacity); fuel = fuel.copyOf(capacity)
        spin = spin.copyOf(capacity); thrust = thrust.copyOf(capacity)
        bodyH = bodyH.copyOf(capacity); bodyS = bodyS.copyOf(capacity); bodyV = bodyV.copyOf(capacity)
        fireH = fireH.copyOf(capacity); fireS = fireS.copyOf(capacity); fireV = fireV.copyOf(capacity)
    }
    override fun scatter(slot: Int, value: GenomeComponent) {
        val g = value.genome
        walkMin[slot] = g.aiWalkMinTicks; walkMax[slot] = g.aiWalkMaxTicks
        charge[slot] = g.aiChargeTicks; fuel[slot] = g.aiFuelTicks
        spin[slot] = g.aiSpin; thrust[slot] = g.aiThrust
        bodyH[slot] = g.bodyColor.rawH; bodyS[slot] = g.bodyColor.rawS; bodyV[slot] = g.bodyColor.rawV
        fireH[slot] = g.fireColor.rawH; fireS[slot] = g.fireColor.rawS; fireV[slot] = g.fireColor.rawV
    }
    override fun gather(slot: Int): GenomeComponent = GenomeComponent(
        Genome(
            aiWalkMinTicks = walkMin[slot], aiWalkMaxTicks = walkMax[slot],
            aiChargeTicks = charge[slot], aiFuelTicks = fuel[slot],
            aiSpin = spin[slot], aiThrust = thrust[slot],
            bodyColor = HsvColorGene(bodyH[slot], bodyS[slot], bodyV[slot]),
            fireColor = HsvColorGene(fireH[slot], fireS[slot], fireV[slot]),
        ),
    )
    override fun moveSlot(dst: Int, src: Int) {
        walkMin[dst] = walkMin[src]; walkMax[dst] = walkMax[src]; charge[dst] = charge[src]
        fuel[dst] = fuel[src]; spin[dst] = spin[src]; thrust[dst] = thrust[src]
        bodyH[dst] = bodyH[src]; bodyS[dst] = bodyS[src]; bodyV[dst] = bodyV[src]
        fireH[dst] = fireH[src]; fireS[dst] = fireS[src]; fireV[dst] = fireV[src]
    }
}

class SpriteAnimationColumnStore : ColumnStore<SpriteAnimationState> {
    var sheet = IntArray(0); private set       // SpriteSheet.ordinal
    var animationIndex = IntArray(0); private set
    var currentFrame = IntArray(0); private set
    var tickCounter = IntArray(0); private set
    override fun ensureCapacity(capacity: Int) {
        if (sheet.size >= capacity) return
        sheet = sheet.copyOf(capacity); animationIndex = animationIndex.copyOf(capacity)
        currentFrame = currentFrame.copyOf(capacity); tickCounter = tickCounter.copyOf(capacity)
    }
    override fun scatter(slot: Int, value: SpriteAnimationState) {
        sheet[slot] = value.sheet.ordinal; animationIndex[slot] = value.animationIndex
        currentFrame[slot] = value.currentFrame; tickCounter[slot] = value.tickCounter
    }
    override fun gather(slot: Int): SpriteAnimationState = SpriteAnimationState(
        sheet = SpriteSheet.entries[sheet[slot]], animationIndex = animationIndex[slot],
        currentFrame = currentFrame[slot], tickCounter = tickCounter[slot],
    )
    override fun moveSlot(dst: Int, src: Int) {
        sheet[dst] = sheet[src]; animationIndex[dst] = animationIndex[src]
        currentFrame[dst] = currentFrame[src]; tickCounter[dst] = tickCounter[src]
    }
}

class LineageSeedColumnStore : ColumnStore<LineageSeedComponent> {
    var mother = IntArray(0); private set      // EntityId.value, or -1 for null
    var father = IntArray(0); private set
    override fun ensureCapacity(capacity: Int) {
        if (mother.size >= capacity) return
        mother = mother.copyOf(capacity); father = father.copyOf(capacity)
    }
    override fun scatter(slot: Int, value: LineageSeedComponent) {
        mother[slot] = value.motherEntityId ?: -1; father[slot] = value.fatherEntityId ?: -1
    }
    override fun gather(slot: Int): LineageSeedComponent = LineageSeedComponent(
        motherEntityId = mother[slot].takeIf { it >= 0 }, fatherEntityId = father[slot].takeIf { it >= 0 },
    )
    override fun moveSlot(dst: Int, src: Int) { mother[dst] = mother[src]; father[dst] = father[src] }
}

/** Tag component (the planet exerts atmospheric drag): membership only, no field data. */
class AtmosphereSourceColumnStore : ColumnStore<AtmosphereSourceComponent> {
    override fun ensureCapacity(capacity: Int) {}
    override fun scatter(slot: Int, value: AtmosphereSourceComponent) {}
    override fun gather(slot: Int): AtmosphereSourceComponent = AtmosphereSourceComponent
    override fun moveSlot(dst: Int, src: Int) {}
}

class ParticleTintColumnStore : ColumnStore<ParticleTintComponent> {
    var h = IntArray(0); private set
    var s = IntArray(0); private set
    var v = IntArray(0); private set
    override fun ensureCapacity(capacity: Int) {
        if (h.size >= capacity) return
        h = h.copyOf(capacity); s = s.copyOf(capacity); v = v.copyOf(capacity)
    }
    override fun scatter(slot: Int, value: ParticleTintComponent) {
        h[slot] = value.color.h; s[slot] = value.color.s; v[slot] = value.color.v
    }
    override fun gather(slot: Int): ParticleTintComponent = ParticleTintComponent(HsvColor(h[slot], s[slot], v[slot]))
    override fun moveSlot(dst: Int, src: Int) { h[dst] = h[src]; s[dst] = s[src]; v[dst] = v[src] }
}
