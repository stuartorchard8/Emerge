package org.emerge.demo.drockets.soa

import org.emerge.demo.drockets.ANIM_FIRE
import org.emerge.demo.drockets.ANIM_IDLE_LEFT
import org.emerge.demo.drockets.ANIM_IDLE_RIGHT
import org.emerge.demo.drockets.ANIM_WALK_LEFT
import org.emerge.demo.drockets.ANIM_WALK_RIGHT
import org.emerge.demo.drockets.DROCKET_RADIUS
import org.emerge.demo.drockets.DrocketPhase
import org.emerge.demo.drockets.DrocketStateComponent
import org.emerge.demo.drockets.DrocketsConfig
import org.emerge.demo.drockets.GenomeComponent
import org.emerge.demo.drockets.KNIGHT_RADIUS
import org.emerge.demo.drockets.KnightPhase
import org.emerge.demo.drockets.KnightStateComponent
import org.emerge.demo.drockets.ReproducerComponent
import org.emerge.demo.drockets.SpriteAnimationState
import org.emerge.demo.drockets.SpriteSheet
import org.emerge.demo.drockets.DrocketAdaptiveDamageSystem
import org.emerge.demo.drockets.DrocketLandingSystem
import org.emerge.demo.drockets.DrocketParticleSystem
import org.emerge.demo.drockets.DrocketsInput
import org.emerge.demo.drockets.ReproductionSystem
import org.emerge.demo.drockets.nowMsForTick
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.Phase
import org.emerge.sim.core.ecs.Pipeline
import org.emerge.sim.core.ecs.isolated
import org.emerge.sim.core.ecs.runSequential
import org.emerge.sim.core.physics.systems.BounceSystem
import org.emerge.sim.core.physics.systems.ContactSystem
import org.emerge.sim.core.physics.systems.CrashSystem
import org.emerge.sim.core.physics.systems.ParticleSystem
import org.emerge.sim.core.physics.systems.RollingResistanceSystem
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.ecs.soa.ComponentColumns
import org.emerge.sim.core.ecs.soa.ImpulseColumnStore
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.primitives.Norm
import kotlin.math.abs

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

    // The hard phases (broadphase contacts, the isolated fork/merge contactResponse + effects,
    // and the structural lifecycle) are run through the EXACT array-of-structs systems via a
    // materialize→runSequential→reload bridge — guaranteed bit-identical, including the engine's
    // fork/merge for isolated phases. The clean sequential phases run in place on the columns.
    // (Future work: port the bridged phases in place too; for drockets the perf gain is marginal.)
    private val contactAndLifecycle: Pipeline<DrocketsConfig, SimState, DrocketsInput> = listOf(
        Phase("contactDetect", ContactSystem()),
        Phase("contactResponse", DrocketLandingSystem, ReproductionSystem, CrashSystem, BounceSystem, RollingResistanceSystem).isolated(),
        Phase("lifecycle", DrocketAdaptiveDamageSystem),
    )
    private val effectsPhase: Pipeline<DrocketsConfig, SimState, DrocketsInput> = listOf(
        Phase("effects", ParticleSystem, DrocketParticleSystem).isolated(),
    )

    /**
     * One full tick over a persistent [DrocketsWorld]. Sequential phases mutate columns in
     * place; the two bridge points run the exact AoS phases and return a reloaded world.
     */
    fun tick(initial: DrocketsWorld, inputs: Map<PlayerId, DrocketsInput>): DrocketsWorld {
        var w = initial
        reset(w)
        // aiAndMotion (sequential, in place).
        drocketAi(w); drocketWalk(w); knightAi(w); knightWalk(w); spriteAnimation(w)
        // forceGather (in place).
        forceGather(w)
        // contactDetect + contactResponse (isolated) + lifecycle (bridged).
        w = bridge(w, contactAndLifecycle, inputs)
        densifyImpulse(w) // restore the dense accumulator the bridge reload flattened to a sparse table
        attachment(w)
        // effects (isolated, bridged).
        w = bridge(w, effectsPhase, inputs)
        integrate(w)
        w.world.tick = initial.world.tick + 1
        return w
    }

    private fun bridge(
        w: DrocketsWorld,
        phases: Pipeline<DrocketsConfig, SimState, DrocketsInput>,
        inputs: Map<PlayerId, DrocketsInput>,
    ): DrocketsWorld {
        val builder = SimBuilder(w.toSimState())
        runSequential(cfg, builder, inputs, phases)
        return DrocketsWorld.fromSimState(builder.build())
    }

    /** Rebuild Impulse dense over Material entities (ascending), preserving current values. */
    private fun densifyImpulse(w: DrocketsWorld) {
        val impulse = w.world.columns(ImpulseComponent::class)
        val material = w.world.columns(MaterialComponent::class)
        val current = HashMap<Int, ImpulseComponent>(impulse.count)
        impulse.forEachAliveSlot { slot, id -> current[id.value] = impulse.gatherAt(slot) }
        impulse.clear()
        for (slot in 0 until material.count) {
            val id = material.entityAt(slot)
            impulse.put(id, current[id.value] ?: ImpulseComponent())
        }
    }

    // ── deterministic PRNG (mirrors SimBuilder.nextRandomInt over SimState.randomSeed) ──
    private fun nextRandomInt(w: DrocketsWorld): Int {
        w.world.randomSeed = w.world.randomSeed * 2862933555777941757L + 3037000493L
        return (w.world.randomSeed ushr 32).toInt()
    }

    private fun nextRandomInt(w: DrocketsWorld, until: Int): Int {
        require(until > 0)
        return (nextRandomInt(w).toLong() and 0x7FFFFFFFL).toInt() % until
    }

    // ── spriteAnimation (engine-side SpriteAnimationSystem) ──────────────────────
    /** Advances each entity's animation by one tick (per-entity, no RNG). */
    fun spriteAnimation(w: DrocketsWorld) {
        val animCols = w.world.columns(SpriteAnimationState::class)
        for (slot in 0 until animCols.count) {
            val state = animCols.gatherAt(slot)
            val anim = state.sheet.animations.getOrNull(state.animationIndex) ?: continue
            val nextTick = state.tickCounter + 1
            val newState = if (nextTick >= anim.ticksPerFrame) {
                val nextFrame = state.currentFrame + 1
                if (nextFrame >= anim.frames.size) {
                    if (anim.loop) state.copy(currentFrame = 0, tickCounter = 0)
                    else state.copy(currentFrame = anim.frames.size - 1, tickCounter = 0)
                } else {
                    state.copy(currentFrame = nextFrame, tickCounter = 0)
                }
            } else {
                state.copy(tickCounter = nextTick)
            }
            animCols.put(animCols.entityAt(slot), newState)
        }
    }

    // ── drocketWalk / knightWalk ─────────────────────────────────────────────────
    /** Rotates a WALKING entity's surface attachment around its planet (per-entity, no RNG). */
    private fun surfaceWalk(
        w: DrocketsWorld,
        stateColType: kotlin.reflect.KClass<*>,
        isWalking: (Int) -> Boolean,
        walkDirectionAt: (Int) -> Int,
        baseRadius: Frac,
    ) {
        val landingCols = w.world.columns(LandingAttachmentComponent::class)
        val colliderCols = w.world.columns(ColliderComponent::class)
        @Suppress("UNCHECKED_CAST")
        val stateCols = w.world.columns(stateColType as kotlin.reflect.KClass<Any>)
        for (slot in 0 until stateCols.count) {
            if (!isWalking(slot)) continue
            val id = stateCols.entityAt(slot)
            val landing = landingCols.gather(id) ?: continue
            val parentCollider = colliderCols.gather(landing.parentEntityId) ?: continue
            val walkStep = baseRadius / parentCollider.radius.toCircumference()
            val angularDelta = walkStep * walkDirectionAt(slot)
            landingCols.put(
                id,
                landing.copy(
                    relativePos = landing.relativePos.rotateByAngle(Coord(angularDelta.raw.toInt())),
                    relativeAng = landing.relativeAng + angularDelta,
                ),
            )
        }
    }

    fun drocketWalk(w: DrocketsWorld) {
        val store = w.world.columns(DrocketStateComponent::class).store as DrocketStateColumnStore
        surfaceWalk(
            w, DrocketStateComponent::class,
            isWalking = { store.phase[it] == DrocketPhase.WALKING.ordinal },
            walkDirectionAt = { store.walkDirection[it] },
            baseRadius = DROCKET_RADIUS / 16,
        )
    }

    fun knightWalk(w: DrocketsWorld) {
        val store = w.world.columns(KnightStateComponent::class).store as KnightStateColumnStore
        surfaceWalk(
            w, KnightStateComponent::class,
            isWalking = { store.phase[it] == KnightPhase.WALKING.ordinal },
            walkDirectionAt = { store.walkDirection[it] },
            baseRadius = KNIGHT_RADIUS / 16,
        )
    }

    // ── drocketAi (DrocketAISystem) ──────────────────────────────────────────────
    /**
     * The drocket walk→charge→thrust→fly state machine, ported verbatim from DrocketAISystem.
     * RNG draws happen in ascending-id entity order matching the AoS entries() sweep; impulses
     * accumulate additively; DrocketState / LandingAttachment / SpriteAnimationState are
     * authoritatively rebuilt from the mirrored maps (the SoA equivalent of `setTable`, which
     * also adds first-time SpriteAnimationState entries — handled by the ascending rebuild).
     */
    fun drocketAi(w: DrocketsWorld) {
        val dsCols = w.world.columns(DrocketStateComponent::class)
        val drocketStates = gatherAll(dsCols)
        if (drocketStates.isEmpty()) return
        val animCols = w.world.columns(SpriteAnimationState::class)
        val animationStates = gatherAll(animCols)
        val landingCols = w.world.columns(LandingAttachmentComponent::class)
        val landings = gatherAll(landingCols)
        val transformCols = w.world.columns(TransformComponent::class)
        val motionCols = w.world.columns(MotionComponent::class)
        val genomeCols = w.world.columns(GenomeComponent::class)
        val nowMs = nowMsForTick(w.world.tick)

        val impulses = LinkedHashMap<EntityId, ImpulseComponent>()
        val nextStates = LinkedHashMap<EntityId, DrocketStateComponent>()

        for ((entityId, ds) in drocketStates) {
            val transform = transformCols.gather(entityId) ?: continue
            val tuning = tuningFor(genomeCols.gather(entityId))
            when (ds.phase) {
                DrocketPhase.WALKING -> {
                    val remaining = ds.ticksRemaining - 1
                    if (remaining <= 0) {
                        val reproducer = w.reproducers[entityId.value] ?: continue
                        if (!reproducer.isMature(nowMs)) {
                            val newDirection = nextRandomInt(w, until = 2) * 2 - 1
                            val walkRange = (tuning.maxWalkTicks - tuning.minWalkTicks).coerceAtLeast(1)
                            val walkTicks = tuning.minWalkTicks + nextRandomInt(w, until = walkRange)
                            nextStates[entityId] = ds.copy(walkDirection = newDirection, ticksRemaining = walkTicks)
                            continue
                        }
                        nextStates[entityId] = ds.copy(phase = DrocketPhase.CHARGING, ticksRemaining = tuning.chargeTicks)
                        val motion = motionCols.gather(entityId) ?: continue
                        val landing = landings[entityId] ?: continue
                        val parentMotion = motionCols.gather(landing.parentEntityId) ?: continue
                        val parentTransform = transformCols.gather(landing.parentEntityId) ?: continue
                        val surfaceVelocity = surfaceVelocityAtAttachment(parentTransform, parentMotion, landing)
                        landings.remove(entityId)
                        val spinDir = tuning.chargeSpinSpeed * ds.walkDirection
                        impulses[entityId] = ImpulseComponent(
                            vel = surfaceVelocity - motion.vel,
                            angVel = parentMotion.angVel - motion.angVel + spinDir,
                        )
                    } else {
                        nextStates[entityId] = ds.copy(ticksRemaining = remaining)
                    }
                }
                DrocketPhase.CHARGING -> {
                    val remaining = ds.ticksRemaining - 1
                    nextStates[entityId] = if (remaining <= 0) {
                        ds.copy(phase = DrocketPhase.THRUSTING, fuel = tuning.fuelTicks, ticksRemaining = 0)
                    } else {
                        ds.copy(ticksRemaining = remaining)
                    }
                }
                DrocketPhase.THRUSTING -> {
                    val motion = motionCols.gather(entityId) ?: continue
                    if (abs(motion.angVel.raw) > Coord(1, 16).raw) {
                        nextStates[entityId] = ds.copy(phase = DrocketPhase.FLYING, fuel = 0); continue
                    }
                    val fuelLeft = ds.fuel - 1
                    var forward = Norm.fromAngle(transform.ang).cw90
                    if (ds.walkDirection < 0) forward = -forward
                    impulses[entityId] = ImpulseComponent(vel = forward * tuning.thrustStrength)
                    nextStates[entityId] = if (fuelLeft <= 0) ds.copy(phase = DrocketPhase.FLYING, fuel = 0) else ds.copy(fuel = fuelLeft)
                }
                DrocketPhase.FLYING -> {
                    val landing = landingCols.gather(entityId)
                    nextStates[entityId] = if (landing != null) {
                        val walkRange = (tuning.maxWalkTicks - tuning.minWalkTicks).coerceAtLeast(1)
                        val walkTicks = tuning.minWalkTicks + nextRandomInt(w, until = walkRange)
                        ds.copy(phase = DrocketPhase.WALKING, walkDirection = -ds.walkDirection, ticksRemaining = walkTicks)
                    } else {
                        ds
                    }
                }
            }
        }

        for ((entityId, newState) in nextStates) {
            val oldState = drocketStates[entityId]
            drocketStates[entityId] = newState
            if (oldState == null || oldState.phase != newState.phase) {
                val animIndex = when (newState.phase) {
                    DrocketPhase.WALKING -> ANIM_WALK_RIGHT
                    DrocketPhase.CHARGING -> ANIM_IDLE_RIGHT
                    DrocketPhase.THRUSTING -> ANIM_FIRE
                    DrocketPhase.FLYING -> ANIM_IDLE_RIGHT
                }
                setAnimation(animationStates, entityId, SpriteSheet.DROCKET, animIndex)
            }
        }
        for ((entityId, impulse) in impulses) addImpulse(w, entityId, impulse)
        rebuildColumn(landingCols, landings)
        rebuildColumn(dsCols, drocketStates)
        rebuildColumn(animCols, animationStates)
    }

    // ── knightAi (KnightAISystem) — idle↔walk; knights are absent in the default sim ─────
    fun knightAi(w: DrocketsWorld) {
        val ksCols = w.world.columns(KnightStateComponent::class)
        val states = gatherAll(ksCols)
        if (states.isEmpty()) return
        val animCols = w.world.columns(SpriteAnimationState::class)
        val animationStates = gatherAll(animCols)
        val landingCols = w.world.columns(LandingAttachmentComponent::class)
        val landings = gatherAll(landingCols)
        val transformCols = w.world.columns(TransformComponent::class)

        val nextStates = LinkedHashMap<EntityId, KnightStateComponent>()
        for ((entityId, state) in states) {
            transformCols.gather(entityId) ?: continue
            when (state.phase) {
                KnightPhase.WALKING -> {
                    val remaining = state.ticksRemaining - 1
                    nextStates[entityId] = if (remaining <= 0) state.copy(phase = KnightPhase.IDLE, ticksRemaining = KNIGHT_IDLE_TICKS)
                    else state.copy(ticksRemaining = remaining)
                }
                KnightPhase.IDLE -> {
                    val remaining = state.ticksRemaining - 1
                    nextStates[entityId] = if (remaining <= 0) {
                        val walkTicks = KNIGHT_MIN_WALK_TICKS + nextRandomInt(w, until = KNIGHT_MAX_WALK_TICKS - KNIGHT_MIN_WALK_TICKS)
                        state.copy(phase = KnightPhase.WALKING, ticksRemaining = walkTicks, walkDirection = -state.walkDirection)
                    } else {
                        state.copy(ticksRemaining = remaining)
                    }
                }
            }
        }
        for ((entityId, newState) in nextStates) {
            val oldState = states[entityId]
            states[entityId] = newState
            if (oldState == null || oldState.phase != newState.phase) {
                val animIndex = when (newState.phase) {
                    KnightPhase.WALKING -> if (newState.walkDirection == 1) ANIM_WALK_RIGHT else ANIM_WALK_LEFT
                    KnightPhase.IDLE -> if (newState.walkDirection == 1) ANIM_IDLE_RIGHT else ANIM_IDLE_LEFT
                }
                setAnimation(animationStates, entityId, SpriteSheet.KNIGHT, animIndex)
            }
        }
        rebuildColumn(landingCols, landings)
        rebuildColumn(ksCols, states)
        rebuildColumn(animCols, animationStates)
    }

    // ── shared helpers ───────────────────────────────────────────────────────────
    private fun <T : Any> gatherAll(cols: ComponentColumns<T>): LinkedHashMap<EntityId, T> {
        val out = LinkedHashMap<EntityId, T>(cols.count)
        cols.forEachAliveSlot { slot, id -> out[id] = cols.gatherAt(slot) }
        return out
    }

    /** Authoritative column rebuild from [map] in ascending-id order (the SoA `setTable`). */
    private fun <T : Any> rebuildColumn(cols: ComponentColumns<T>, map: Map<EntityId, T>) {
        cols.clear()
        for (id in map.keys.sortedBy { it.value }) cols.put(id, map.getValue(id))
    }

    private fun addImpulse(w: DrocketsWorld, id: EntityId, imp: ImpulseComponent) {
        val cols = w.world.columns(ImpulseComponent::class)
        val slot = cols.slotOf(id); if (slot < 0) return
        val s = cols.store as ImpulseColumnStore
        s.posX[slot] += imp.pos.x.raw; s.posY[slot] += imp.pos.y.raw
        s.velX[slot] += imp.vel.x.raw; s.velY[slot] += imp.vel.y.raw
        s.angVel[slot] += imp.angVel.raw
    }

    private fun setAnimation(
        animStates: MutableMap<EntityId, SpriteAnimationState>,
        entityId: EntityId,
        sheet: SpriteSheet,
        animationIndex: Int,
    ) {
        val current = animStates[entityId]
        if (current == null || current.animationIndex != animationIndex) {
            animStates[entityId] = SpriteAnimationState(sheet, animationIndex, 0, 0)
        }
    }

    private class AiTuning(
        val chargeTicks: Int, val fuelTicks: Int, val minWalkTicks: Int, val maxWalkTicks: Int,
        val chargeSpinSpeed: Frac, val thrustStrength: Frac,
    )

    private fun tuningFor(genome: GenomeComponent?): AiTuning {
        val p = genome?.genome?.phenotype()
        val minWalk = p?.aiWalkMinTicks ?: DROCKET_MIN_WALK_TICKS
        val maxWalk = (p?.aiWalkMaxTicks ?: DROCKET_MAX_WALK_TICKS).coerceAtLeast(minWalk + 1)
        return AiTuning(
            chargeTicks = p?.aiChargeTicks ?: CHARGE_TICKS,
            fuelTicks = p?.aiFuelTicks ?: FUEL_TICKS,
            minWalkTicks = minWalk,
            maxWalkTicks = maxWalk,
            chargeSpinSpeed = Frac((p?.aiSpin ?: CHARGE_SPIN_SPEED.raw.toInt()).toLong()),
            thrustStrength = Frac((p?.aiThrust ?: THRUST_STRENGTH.raw.toInt()).toLong()),
        )
    }

    private fun surfaceVelocityAtAttachment(
        parentTransform: TransformComponent,
        parentMotion: MotionComponent,
        landing: LandingAttachmentComponent,
    ): Coord2 {
        val worldOffset = landing.relativePos.rotateByAngle(parentTransform.ang)
        return parentMotion.surfaceVelocityAtOffset(worldOffset.norm, worldOffset.len)
    }

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

    // ── attachment (engine AttachmentSystem) ─────────────────────────────────────
    // Snaps each landed entity rigidly to its parent by OVERWRITING its impulse with a
    // position/velocity correction (drops stale attachments whose parent/self vanished).
    // Per-entity independent → order-independent. Runs after the force phases; the overwrite
    // discards any force impulse on attached bodies, exactly as AoS `update<Impulse>{ delta }`.
    fun attachment(w: DrocketsWorld) {
        val landingCols = w.world.columns(LandingAttachmentComponent::class)
        val transformCols = w.world.columns(TransformComponent::class)
        val motionCols = w.world.columns(MotionComponent::class)
        val impulseCols = w.world.columns(ImpulseComponent::class)
        val toRemove = ArrayList<EntityId>()
        for (slot in 0 until landingCols.count) {
            if (!landingCols.isAlive(slot)) continue
            val id = landingCols.entityAt(slot)
            val landing = landingCols.gatherAt(slot)
            val parentTransform = transformCols.gather(landing.parentEntityId)
            val parentMotion = motionCols.gather(landing.parentEntityId)
            val transform = transformCols.gather(id)
            val motion = motionCols.gather(id)
            if (parentTransform == null || parentMotion == null || transform == null || motion == null) {
                toRemove.add(id); continue
            }
            val outcome = TransformComponent(
                pos = parentTransform.pos + landing.relativePos.rotateByAngle(parentTransform.ang),
                ang = parentTransform.ang + landing.relativeAng,
            )
            val delta = ImpulseComponent(
                pos = outcome.pos - transform.pos,
                vel = parentMotion.vel - motion.vel,
                angVel = parentMotion.angVel - motion.angVel + (outcome.ang - transform.ang) / 4,
            )
            impulseCols.put(id, delta) // overwrite in place (attached bodies are Material ⇒ present)
        }
        for (id in toRemove) landingCols.remove(id)
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

        // DrocketAISystem tuning defaults (used when a gene is absent).
        private const val CHARGE_TICKS = 18
        private const val FUEL_TICKS = 200
        private const val DROCKET_MIN_WALK_TICKS = 120
        private const val DROCKET_MAX_WALK_TICKS = 600
        private val CHARGE_SPIN_SPEED = Frac(1, 120)
        private val THRUST_STRENGTH = Frac(1, 1024 * 256)

        // KnightAISystem tuning.
        private const val KNIGHT_IDLE_TICKS = 180
        private const val KNIGHT_MIN_WALK_TICKS = 120
        private const val KNIGHT_MAX_WALK_TICKS = 600
    }
}
