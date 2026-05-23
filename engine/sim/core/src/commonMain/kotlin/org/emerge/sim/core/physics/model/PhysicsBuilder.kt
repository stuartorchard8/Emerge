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
 * reset every frame even if no system reads or writes them. Demo-specific frame state
 * (respawn queues, audio events, etc.) is layered on by demos via their own scratch
 * objects registered through [EcsBuilder.scratch].
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
 *  - **Parent-delegated shared resources:** [randomSeed] is seeded from the initial
 *    snapshot and mutated in place, but on a fork the accessors below walk up
 *    [EcsBuilder.parent] and operate on the root builder's scratch instead of the
 *    fork's own. That keeps PRNG draws deterministic and globally visible across
 *    forks in the same isolated phase, at the cost of needing a lock at the domain
 *    layer once forks actually run on separate threads.
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
    return scratch(
        factory = { init -> PhysicsFrameScratch(init) },
        finalize = { scratch ->
            copy(
                contacts = scratch.contacts,
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
