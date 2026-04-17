package org.emerge.sim.core.physics.model

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import kotlin.reflect.KClass

/**
 * Mutable scratchpad used by EcsSystems during a single [PhysicsReducer.reduce] frame.
 *
 * The builder is the **sole** place systems should write mutable frame state. Reads of
 * last-frame state go through [initial]; reads of the "what will the next snapshot look like"
 * go through [getComponent] (which overlays scratchpad on top of [initial]).
 *
 * [build] freezes the scratchpad into a new [PhysicsState] at the end of the frame.
 */
class PhysicsBuilder(val initial: PhysicsState) {
    // --- Component tables ------------------------------------------------

    /**
     * Per-type overlay of entries written via [update] or [setTable].
     * For non-[authoritativeTypes] types, this is merged on top of [initial] in [build].
     * For [authoritativeTypes] types, this IS the final table.
     */
    val workingData = mutableMapOf<KClass<*>, MutableMap<EntityId, Any>>()

    /**
     * Per-type set of entity ids whose component should be absent from the final frame,
     * masking any value present in [initial]. Populated by [remove], cleared by [update]
     * and by [setTable] (authoritative replacement supersedes tombstones).
     */
    val tombstones = mutableMapOf<KClass<*>, MutableSet<EntityId>>()

    /**
     * Types for which [setTable] was called this frame. For these, [workingData] IS the
     * authoritative full table — anything in [initial] that isn't in the scratchpad is gone.
     */
    val authoritativeTypes = mutableSetOf<KClass<*>>()

    // --- Frame-scalar state ----------------------------------------------

    /**
     * Contacts detected this frame. Starts **empty** — unlike the old `state.contacts` which
     * persisted last frame's list across ticks. Systems append via [addContact].
     */
    val contacts: MutableList<Contact> = mutableListOf()

    /**
     * Crash / impact audio events emitted this frame. Starts empty; DamageSystem appends
     * via [addAudioEvent].
     */
    val audioEvents: MutableList<CrashImpactAudioEvent> = mutableListOf()

    // --- Component read/write API ---------------------------------------

    /**
     * Gets the latest version of a component for an entity this frame:
     * tombstoned entries return null; scratchpad entries win over initial;
     * authoritative types disable the initial fallback.
     */
    inline fun <reified T : Any> getComponent(id: EntityId): T? {
        if (tombstones[T::class]?.contains(id) == true) return null
        val frameWork = workingData[T::class]?.get(id) as? T
        if (frameWork != null) return frameWork
        if (T::class in authoritativeTypes) return null
        return initial.raw.components.getTable<T>()[id]
    }

    /**
     * Returns a snapshot of the merged initial + scratchpad view for [T], with tombstones
     * and authoritative replacements applied. Use this when a system needs to iterate "all
     * current [T]s for this frame" — e.g. damage promotion, attachment positioning — so it
     * observes writes made by earlier systems in the same frame.
     *
     * The returned map is a fresh copy; mutating it is safe and does not affect the builder.
     */
    inline fun <reified T : Any> entries(): Map<EntityId, T> {
        val initialEntries: Map<EntityId, T> =
            if (T::class in authoritativeTypes) emptyMap()
            else initial.raw.components.getTable<T>().asMap()
        val work = workingData[T::class]
        val tombs = tombstones[T::class]
        if (work.isNullOrEmpty() && tombs.isNullOrEmpty()) return initialEntries

        val merged = LinkedHashMap<EntityId, T>(initialEntries.size + (work?.size ?: 0))
        merged.putAll(initialEntries)
        if (work != null) {
            @Suppress("UNCHECKED_CAST")
            merged.putAll(work as Map<EntityId, T>)
        }
        tombs?.forEach { merged.remove(it) }
        return merged
    }

    /**
     * Stacks or updates a component safely.
     * Usage: builder.update<ImpulseComponent>(id) { it + thrust }
     */
    inline fun <reified T : Any> update(id: EntityId, crossinline block: (T?) -> T) {
        val current = getComponent<T>(id)
        val table = workingData.getOrPut(T::class) { mutableMapOf() }
        table[id] = block(current)
        tombstones[T::class]?.remove(id)
    }

    /**
     * Removes a component safely. After this call, [getComponent] returns null for the
     * entity regardless of whether the value lived in the initial snapshot.
     */
    inline fun <reified T : Any> remove(id: EntityId) {
        workingData[T::class]?.remove(id)
        tombstones.getOrPut(T::class) { mutableSetOf() }.add(id)
    }

    /**
     * Overwrites a whole table authoritatively. Initial entries not present in [table]
     * are dropped from the final frame; subsequent [update] calls add to this table,
     * [remove] calls tombstone from it.
     */
    inline fun <reified T : Any> setTable(table: MutableMap<EntityId, T>) {
        @Suppress("UNCHECKED_CAST")
        workingData[T::class] = table as MutableMap<EntityId, Any>
        authoritativeTypes.add(T::class)
        tombstones.remove(T::class)
    }

    // --- Frame-scalar API -----------------------------------------------

    fun addContact(contact: Contact) {
        contacts.add(contact)
    }

    fun addAudioEvent(event: CrashImpactAudioEvent) {
        audioEvents.add(event)
    }

    fun nextRandomInt(): Int = initial.nextRandomInt()

    fun nextRandomInt(until: Int): Int = initial.nextRandomInt(until)

    // --- Entity lifecycle -----------------------------------------------
    //
    // Phase B: these currently delegate to the legacy mutators on [PhysicsState], which
    // write directly into `initial.raw`. That's equivalent to the previous behaviour —
    // the point of this API is to give systems a single target (the builder) to write to.
    // Phase C will move the actual storage into the builder's own scratchpad so that the
    // builder becomes fully authoritative.

    fun spawnParticle(
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

    fun spawnBody(
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
    ): EntityId = initial.spawnBody(
        playerId = playerId,
        pos = pos,
        vel = vel,
        ang = ang,
        angVel = angVel,
        mass = mass,
        radius = radius,
        bounce = bounce,
        rough = rough,
        shape = shape,
    )

    fun removeEntity(id: EntityId) {
        initial.removeEntity(id)
    }

    fun setTeam(id: EntityId, teamId: TeamId) {
        initial.setTeam(id, teamId)
    }

    fun queueRespawn(playerId: PlayerId, ticksRemaining: Int) {
        initial.queuePlayerRespawn(playerId, ticksRemaining)
    }

    // --- Finalize -------------------------------------------------------

    /**
     * Freezes the scratchpad back into a new [PhysicsState].
     */
    fun build(): PhysicsState {
        val finalTables = initial.raw.components.tables.toMutableMap()

        val touchedTypes = workingData.keys + tombstones.keys + authoritativeTypes
        for (type in touchedTypes) {
            finalTables[type] = if (type in authoritativeTypes) {
                buildTable(type, workingData[type]?.toMap() ?: emptyMap())
            } else {
                val initialEntries = finalTables[type]?.asMap() ?: emptyMap()
                val merged = LinkedHashMap<EntityId, Any>(initialEntries.size)
                @Suppress("UNCHECKED_CAST")
                merged.putAll(initialEntries as Map<EntityId, Any>)
                workingData[type]?.let { merged.putAll(it) }
                tombstones[type]?.forEach { merged.remove(it) }
                buildTable(type, merged)
            }
        }

        return initial.copy(
            raw = initial.raw.copy(
                components = ComponentStore(finalTables.toMap()),
                contacts = contacts.toList(),
                crashImpactAudioEvents = audioEvents.toList(),
            ),
        )
    }

    @PublishedApi
    internal fun buildTable(type: KClass<*>, values: Map<EntityId, Any>): ComponentTable<*> {
        @Suppress("UNCHECKED_CAST")
        return ComponentTable(type as KClass<Any>, values)
    }
}
