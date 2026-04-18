@file:OptIn(BypassesStagedView::class)

package org.emerge.sim.core.physics.model

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.BypassesStagedView
import org.emerge.sim.core.ecs.EcsBuilder
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
 * registers [PhysicsFrameScratch] so that frame-scoped collections like [contacts] and
 * [audioEvents] are reset every frame even if no system reads or writes them.
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
 *  - **Frame-scoped:** [contacts] and [audioEvents] start empty each frame and are published
 *    wholesale into the snapshot on [EcsBuilder.build].
 *
 *  - **Persistent:** [pendingRespawns] and [randomSeed] are seeded from the initial snapshot,
 *    mutated in place by systems, and written back into the new snapshot.
 *
 * Finalizer also calls [PhysicsState.rebuildIndexes] so the derived `playerEntities` map
 * always reflects the authoritative [PlayerOwnedComponent] table.
 */
class PhysicsFrameScratch(initial: PhysicsState) {
    val contacts: MutableList<Contact> = mutableListOf()
    val audioEvents: MutableList<CrashImpactAudioEvent> = mutableListOf()
    val pendingRespawns: MutableMap<PlayerId, PlayerRespawnState> =
        LinkedHashMap(initial.pendingRespawns)
    var randomSeed: Long = initial.randomSeed
}

/**
 * Returns (creating on first call) the physics frame scratch, registering its finalizer
 * with the builder at the same time. Internal: public surface is the extensions below.
 */
internal fun PhysicsBuilder.physicsScratch(): PhysicsFrameScratch = scratch(
    factory = { init -> PhysicsFrameScratch(init) },
    finalize = { scratch ->
        copy(
            contacts = scratch.contacts.toList(),
            crashImpactAudioEvents = scratch.audioEvents.toList(),
            pendingRespawns = scratch.pendingRespawns.toMap(),
            randomSeed = scratch.randomSeed,
        ).rebuildIndexes()
    },
)

// --- Frame-scoped accessors ----------------------------------------------

val PhysicsBuilder.contacts: MutableList<Contact>
    get() = physicsScratch().contacts

val PhysicsBuilder.audioEvents: MutableList<CrashImpactAudioEvent>
    get() = physicsScratch().audioEvents

fun PhysicsBuilder.addContact(contact: Contact) {
    physicsScratch().contacts.add(contact)
}

fun PhysicsBuilder.addAudioEvent(event: CrashImpactAudioEvent) {
    physicsScratch().audioEvents.add(event)
}

// --- Deterministic PRNG --------------------------------------------------

fun PhysicsBuilder.nextRandomInt(): Int {
    val scratch = physicsScratch()
    scratch.randomSeed = scratch.randomSeed * 2862933555777941757L + 3037000493L
    return (scratch.randomSeed ushr 32).toInt()
}

fun PhysicsBuilder.nextRandomInt(until: Int): Int {
    require(until > 0)
    return (nextRandomInt().toLong() and 0x7FFFFFFFL).toInt() % until
}

// --- Respawn queue -------------------------------------------------------

/**
 * Snapshot view of the current pending respawns map as last written this frame.
 * Safe to iterate while the builder is in flight; mutations must go through
 * [queueRespawn] / [clearRespawn].
 */
val PhysicsBuilder.pendingRespawns: Map<PlayerId, PlayerRespawnState>
    get() = physicsScratch().pendingRespawns

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
    physicsScratch().pendingRespawns[playerId] = PlayerRespawnState(
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
}

/** Removes [playerId] from the pending respawn queue, if present. */
fun PhysicsBuilder.clearRespawn(playerId: PlayerId) {
    physicsScratch().pendingRespawns.remove(playerId)
}

/**
 * Applies [block] to the existing respawn entry for [playerId], if any. A null return
 * from [block] removes the entry; a non-null return replaces it. No-op if no entry exists.
 */
fun PhysicsBuilder.updateRespawn(
    playerId: PlayerId,
    block: (PlayerRespawnState) -> PlayerRespawnState?,
) {
    val scratch = physicsScratch()
    val current = scratch.pendingRespawns[playerId] ?: return
    val next = block(current)
    if (next == null) scratch.pendingRespawns.remove(playerId)
    else scratch.pendingRespawns[playerId] = next
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
        physicsScratch().pendingRespawns.remove(playerId)
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
