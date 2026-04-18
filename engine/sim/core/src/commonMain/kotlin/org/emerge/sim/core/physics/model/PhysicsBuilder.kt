@file:OptIn(BypassesStagedView::class)

package org.emerge.sim.core.physics.model

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.BypassesStagedView
import org.emerge.sim.core.ecs.EcsBuilder
import org.emerge.sim.core.ecs.withLock
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.components.PlayerOwnedComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Physics-domain view of the generic [EcsBuilder]. Nothing about the builder itself is
 * physics-specific — all physics-domain frame state is carried via [PhysicsFrameScratch]
 * and the extension functions below.
 *
 * The builder operates on [PhysicsState] directly. Reducers construct a builder from the
 * current snapshot, run systems, and return the built snapshot.
 */
typealias PhysicsBuilder = EcsBuilder<PhysicsState>

/**
 * Constructs a [PhysicsBuilder] wired up with lenses over [PhysicsState], and eagerly
 * registers [PhysicsFrameScratch] so that frame-scoped collections like [contacts] are
 * reset every frame even if no system reads or writes them. Audio events live in the
 * generic [EcsBuilder] event stream and are folded into the snapshot by the scratch
 * finalizer, so any system may [EcsBuilder.emit] a [CrashImpactAudioEvent] without
 * needing to coordinate with a designated "audio producer".
 */
@Suppress("FunctionName")
fun PhysicsBuilder(initial: PhysicsState): PhysicsBuilder {
    val builder = EcsBuilder(
        initial = initial,
        getComponents = { it.components },
        getWorld = { it.world },
        applyComponents = { snap, components -> snap.copy(components = components) },
    )
    builder.physicsScratch()
    return builder
}

/**
 * Per-frame physics state carried through the builder. Fields come in two flavours:
 *
 *  - **Typed phase outputs:** [contacts] is written once by its producing phase via
 *    [setContacts] and read as an immutable [List] by subsequent phases. The handoff
 *    is read-only once the producing phase ends, so downstream systems are safe to
 *    share it across threads in a parallel executor.
 *
 *  - **Parent-delegated shared resources:** [pendingRespawns] and [randomSeed] are
 *    seeded from the initial snapshot and mutated in place, but on a fork the
 *    accessors below (`pendingRespawns`, `nextRandomInt`, `queueRespawn`, etc.) walk
 *    up [EcsBuilder.parent] and operate on the root builder's scratch instead of the
 *    fork's own. That keeps DamageSystem's respawn enqueues and ShipThrust's PRNG
 *    draws deterministic and globally visible across forks in the same isolated
 *    phase, at the cost of needing a lock at the domain layer once forks actually
 *    run on separate threads.
 *
 * Audio events are NOT on this scratch — they are emitted via [EcsBuilder.emit] on the
 * generic event stream and folded into [PhysicsState.crashImpactAudioEvents] by the
 * [physicsScratch] finalizer. Any system may emit events without coordinating with a
 * single producer, and fork emits replay onto the parent via the write-log.
 *
 * Finalizer also calls [PhysicsState.rebuildIndexes] so the derived `playerEntities` map
 * always reflects the authoritative [PlayerOwnedComponent] table.
 */
class PhysicsFrameScratch(initial: PhysicsState) {
    /**
     * Contacts detected this frame. Seeded from [initial] so that a builder forked
     * mid-frame (via [EcsBuilder.fork][org.emerge.sim.core.ecs.fork]) inherits the
     * parent's current contact list automatically; for a normal start-of-frame
     * builder this is just last-frame's published contacts, which the contactDetect
     * producer overwrites wholesale before any downstream system reads them.
     */
    var contacts: List<Contact> = initial.contacts

    val pendingRespawns: MutableMap<PlayerId, PlayerRespawnState> =
        LinkedHashMap(initial.pendingRespawns)
    var randomSeed: Long = initial.randomSeed
}

/**
 * Walks up [EcsBuilder.parent] to find the root builder. Shared-resource scratch
 * accessors funnel through this so a fork's writes land on the root's scratch where
 * they're globally visible within the isolated phase and survive beyond merge.
 */
private fun PhysicsBuilder.rootBuilder(): PhysicsBuilder {
    var b: PhysicsBuilder = this
    while (true) {
        val p = b.parent ?: return b
        b = p
    }
}

private fun PhysicsBuilder.sharedScratch(): PhysicsFrameScratch = rootBuilder().physicsScratch()

/**
 * Returns (creating on first call) the physics frame scratch, registering its finalizer
 * with the builder at the same time. Internal: public surface is the extensions below.
 */
internal fun PhysicsBuilder.physicsScratch(): PhysicsFrameScratch {
    val builder = this
    return scratch(
        factory = { init -> PhysicsFrameScratch(init) },
        finalize = { scratch ->
            copy(
                contacts = scratch.contacts,
                crashImpactAudioEvents = builder.events<CrashImpactAudioEvent>(),
                pendingRespawns = scratch.pendingRespawns.toMap(),
                randomSeed = scratch.randomSeed,
            ).rebuildIndexes()
        },
    )
}

// --- Typed phase outputs -------------------------------------------------

/**
 * Contacts detected this frame. Empty before the `contactDetect` phase runs; populated
 * by that phase's producer via [setContacts]; read as an immutable list by all phases
 * that follow. Treat it as a read-only phase input outside `contactDetect`.
 */
val PhysicsBuilder.contacts: List<Contact>
    get() = physicsScratch().contacts

/**
 * Publishes the full contact list for this frame, replacing any prior value. Only the
 * `contactDetect` phase's producer should call this.
 */
fun PhysicsBuilder.setContacts(contacts: List<Contact>) {
    physicsScratch().contacts = contacts
}

// --- Audio events --------------------------------------------------------
//
// Emitted via [EcsBuilder.emit] on the generic event stream and read via
// [EcsBuilder.events]. The [physicsScratch] finalizer folds the accumulated stream
// into [PhysicsState.crashImpactAudioEvents] at build time.

// --- Deterministic PRNG --------------------------------------------------
//
// Draws advance the ROOT builder's seed so forks in an isolated phase produce a
// single linear sequence matching sequential execution. The root lock serialises
// concurrent draws from parallel forks so the sequence stays well-defined; under
// sequential fork dispatch the lock is uncontended and ~free on JVM (no-op on JS).

fun PhysicsBuilder.nextRandomInt(): Int = rootLock.withLock {
    val scratch = sharedScratch()
    scratch.randomSeed = scratch.randomSeed * 2862933555777941757L + 3037000493L
    (scratch.randomSeed ushr 32).toInt()
}

fun PhysicsBuilder.nextRandomInt(until: Int): Int {
    require(until > 0)
    return (nextRandomInt().toLong() and 0x7FFFFFFFL).toInt() % until
}

// --- Respawn queue -------------------------------------------------------
//
// Like the PRNG, the respawn queue is shared across forks in an isolated phase:
// reads and writes route through the ROOT builder's scratch so DamageSystem's
// enqueues and RespawnSystem's drains are globally visible. Under sequential fork
// execution this is bit-identical to the old in-place behaviour; threads will
// need a lock at this layer.

/**
 * Snapshot view of the current pending respawns as last written this frame. The
 * returned map is an immutable copy taken while holding [rootLock], so callers
 * can safely iterate it even if other forks are mutating the underlying queue
 * concurrently. Mutations must go through [queueRespawn] / [clearRespawn] /
 * [updateRespawn] which also acquire [rootLock].
 */
val PhysicsBuilder.pendingRespawns: Map<PlayerId, PlayerRespawnState>
    get() = rootLock.withLock { sharedScratch().pendingRespawns.toMap() }

/**
 * Captures the entity's current transform/material/collider/renderShape/team into a
 * [PlayerRespawnState] and enqueues it under [playerId]. If any required component is
 * missing the entity is instead removed outright — matching the legacy behaviour.
 *
 * Deliberately uses the frozen [EcsBuilder.initial] view so we can still resolve the
 * player's entity even if it has been tombstoned earlier this frame (e.g. by
 * [org.emerge.sim.core.physics.systems.DamageSystem]). Per-component reads go through
 * [EcsBuilder.getComponent] which honours the staged overlay, so if damage wiped a
 * required component mid-frame we fall through to the "just remove the entity" path.
 *
 * Does NOT remove the entity itself; callers are expected to destroy the rocket separately
 * (typically via [EcsBuilder.removeEntity]) after queuing.
 */
fun PhysicsBuilder.queueRespawn(playerId: PlayerId, ticksRemaining: Int) {
    val entityId = initial.playerEntities[playerId] ?: return
    val transform = getComponent<TransformComponent>(entityId)
    val material = getComponent<MaterialComponent>(entityId)
    val collider = getComponent<ColliderComponent>(entityId)
    val renderShape = getComponent<RenderShapeComponent>(entityId)
    val teamId = getComponent<TeamComponent>(entityId)?.teamId
    if (transform == null || material == null || collider == null ||
        renderShape == null || teamId == null
    ) {
        removeEntity(entityId)
        return
    }
    val entry = PlayerRespawnState(
        ticksRemaining = ticksRemaining,
        deathPos = transform.pos,
        teamId = teamId,
        entityId = entityId,
        rocket = RespawnRocketSpec(
            mass = material.mass,
            radius = collider.radius,
            bounce = material.bounce,
            rough = material.rough,
            shape = renderShape.shape,
        ),
    )
    rootLock.withLock { sharedScratch().pendingRespawns[playerId] = entry }
}

/** Removes [playerId] from the pending respawn queue, if present. */
fun PhysicsBuilder.clearRespawn(playerId: PlayerId) {
    rootLock.withLock { sharedScratch().pendingRespawns.remove(playerId) }
}

/**
 * Applies [block] to the existing respawn entry for [playerId], if any. A null return
 * from [block] removes the entry; a non-null return replaces it. No-op if no entry
 * exists. Read/compute/write happens under [rootLock], so concurrent forks can't
 * lose each other's updates.
 */
fun PhysicsBuilder.updateRespawn(
    playerId: PlayerId,
    block: (PlayerRespawnState) -> PlayerRespawnState?,
) {
    rootLock.withLock {
        val scratch = sharedScratch()
        val current = scratch.pendingRespawns[playerId] ?: return@withLock
        val next = block(current)
        if (next == null) scratch.pendingRespawns.remove(playerId)
        else scratch.pendingRespawns[playerId] = next
    }
}

// --- Composite spawns ----------------------------------------------------

/**
 * Spawns a fresh body (player-owned if [playerId] is non-null) via [EcsBuilder.createEntity]
 * plus per-component [EcsBuilder.update] calls. No reads of the initial snapshot; the new
 * entity starts with exactly the components set here.
 */
fun PhysicsBuilder.spawnBody(
    playerId: PlayerId?,
    pos: Coord2,
    vel: Coord2,
    ang: Coord,
    angVel: Coord,
    mass: UInt,
    radius: Frac,
    bounce: Frac,
    rough: Frac,
    shape: BodyShape,
): EntityId {
    val entityId = createEntity()
    update<TransformComponent>(entityId) { TransformComponent(pos = pos, ang = ang) }
    update<MotionComponent>(entityId) { MotionComponent(vel = vel, angVel = angVel) }
    update<ColliderComponent>(entityId) { ColliderComponent(radius = radius) }
    update<MaterialComponent>(entityId) {
        MaterialComponent(mass = mass, bounce = bounce, rough = rough)
    }
    update<RenderShapeComponent>(entityId) { RenderShapeComponent(shape = shape) }
    if (playerId != null) {
        update<PlayerOwnedComponent>(entityId) { PlayerOwnedComponent(playerId) }
        rootLock.withLock { sharedScratch().pendingRespawns.remove(playerId) }
    }
    return entityId
}

/**
 * Spawns a particle entity via [EcsBuilder.createEntity] + per-component writes.
 */
fun PhysicsBuilder.spawnParticle(
    pos: Coord2,
    vel: Coord2,
    radius: Frac,
    shape: BodyShape,
    lifetime: Int,
    teamId: TeamId,
): EntityId {
    val entityId = createEntity()
    update<TransformComponent>(entityId) { TransformComponent(pos = pos, ang = Coord(0)) }
    update<MotionComponent>(entityId) { MotionComponent(vel = vel, angVel = Coord(0)) }
    update<ColliderComponent>(entityId) { ColliderComponent(radius = radius) }
    update<RenderShapeComponent>(entityId) { RenderShapeComponent(shape = shape) }
    update<TeamComponent>(entityId) { TeamComponent(teamId) }
    update<ParticleComponent>(entityId) { ParticleComponent(lifetime, lifetime) }
    return entityId
}

/**
 * Like [EcsBuilder.removeEntity] but also cascades any [LandingAttachmentComponent] whose
 * parent was [id], so orphaned surface attachments don't outlive their planet/ship.
 */
fun PhysicsBuilder.removeEntityWithLandingCascade(id: EntityId) {
    for ((landerId, landing) in entries<LandingAttachmentComponent>()) {
        if (landing.parentEntityId == id && landerId != id) {
            remove<LandingAttachmentComponent>(landerId)
        }
    }
    removeEntity(id)
}
