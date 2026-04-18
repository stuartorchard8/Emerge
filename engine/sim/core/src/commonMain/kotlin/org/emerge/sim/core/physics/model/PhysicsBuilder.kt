package org.emerge.sim.core.physics.model

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.EcsBuilder
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Physics-domain view of the generic [EcsBuilder]. Nothing about the builder itself is
 * physics-specific — all physics-domain frame state is carried via [PhysicsFrameScratch]
 * and the extension functions below.
 */
typealias PhysicsBuilder = EcsBuilder<PhysicsState>

/**
 * Constructs a [PhysicsBuilder] wired up with lenses over [PhysicsState], and eagerly
 * registers [PhysicsFrameScratch] so that frame-scalar collections like [contacts] and
 * [audioEvents] are reset every frame even if no system reads or writes them.
 */
@Suppress("FunctionName")
fun PhysicsBuilder(initial: PhysicsState): PhysicsBuilder {
    val builder = EcsBuilder(
        initial = initial,
        getComponents = { it.raw.components },
        applyComponents = { state, components ->
            state.copy(raw = state.raw.copy(components = components))
        },
    )
    builder.physicsScratch()
    return builder
}

/**
 * Frame-scoped physics collections. Registered once via [physicsScratch]; a finalizer
 * folds these back into [PhysicsSnapshot] when the builder is [EcsBuilder.build]-ed.
 */
class PhysicsFrameScratch {
    val contacts: MutableList<Contact> = mutableListOf()
    val audioEvents: MutableList<CrashImpactAudioEvent> = mutableListOf()
}

/**
 * Returns (creating on first call) the physics frame scratch, registering its finalizer
 * with the builder at the same time.
 */
internal fun PhysicsBuilder.physicsScratch(): PhysicsFrameScratch = scratch(
    factory = ::PhysicsFrameScratch,
    finalize = { scratch ->
        copy(
            raw = raw.copy(
                contacts = scratch.contacts.toList(),
                crashImpactAudioEvents = scratch.audioEvents.toList(),
            ),
        )
    },
)

// --- Frame-scratch accessors ---------------------------------------------

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

// --- PRNG + entity lifecycle --------------------------------------------
//
// These still delegate to mutators on [PhysicsState], which write directly into
// `initial.raw`. That preserves existing behaviour; a future pass could route the
// mutations through the scratchpad so the builder becomes fully authoritative.

fun PhysicsBuilder.nextRandomInt(): Int = initial.nextRandomInt()

fun PhysicsBuilder.nextRandomInt(until: Int): Int = initial.nextRandomInt(until)

fun PhysicsBuilder.spawnParticle(
    pos: Coord2,
    vel: Coord2,
    radius: Frac,
    shape: BodyShape,
    lifetime: Int,
    teamId: TeamId,
): EntityId = initial.spawnParticle(
    pos = pos,
    vel = vel,
    radius = radius,
    shape = shape,
    lifetime = lifetime,
    teamId = teamId,
)

fun PhysicsBuilder.removeEntity(id: EntityId) {
    initial.removeEntity(id)
}

fun PhysicsBuilder.queueRespawn(playerId: PlayerId, ticksRemaining: Int) {
    initial.queuePlayerRespawn(playerId, ticksRemaining)
}
