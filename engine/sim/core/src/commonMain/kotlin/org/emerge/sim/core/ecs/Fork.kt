package org.emerge.sim.core.ecs

/**
 * Forks this builder for running one parallel-capable system against a frozen snapshot
 * of the parent's current state.
 *
 * The fork is a freshly constructed [EcsBuilder] whose [EcsBuilder.initial] is the
 * materialised output of [EcsBuilder.build] on this (parent) builder at fork time.
 * That means:
 *
 *  - Parent's committed writes this frame (workingData / tombstones / authoritative
 *    tables) are baked into the fork's [EcsBuilder.initial].
 *  - Parent's scratch finalizers run into the fork's initial too, so domain scratches
 *    on the fork re-seed from the parent's current scratch contents (e.g. the physics
 *    scratch inherits live `contacts`, `randomSeed`).
 *  - The fork's own workingData / tombstones / authoritativeTypes start empty.
 *
 * The fork also records every mutation (calls to [EcsBuilder.update], [EcsBuilder.remove],
 * [EcsBuilder.setTable]) into a write-log keyed by replay closures. Feed the fork to
 * exactly one system, then call [mergeFork] on the parent to replay the log.
 *
 * **Entity lifecycle note.** [EcsBuilder.removeEntity] IS write-logged: a fork's removal
 * replays on the parent during [mergeFork], tombstoning the entity across every type the
 * parent knows about. [EcsBuilder.createEntity] is NOT write-logged; instead it mutates
 * the shared entity world directly and returns the allocated id, which the fork then
 * uses for ordinary component writes. Because component writes ARE write-logged, the
 * new entity and its components propagate to the parent through the usual replay path.
 *
 * Under sequential forked execution this is fully safe and deterministic. Under a
 * future multi-threaded dispatcher, concurrent [EcsBuilder.createEntity] calls would
 * race on the shared world's id counter, so isolated phases that allocate entities
 * will need a fork-local id reservation scheme before they can be parallelised.
 *
 * **Scratch note.** Mutations to domain scratches (registered via [EcsBuilder.scratch])
 * happen on the fork's own scratch instance by default and are NOT propagated to the
 * parent at merge time — if a scratch field a fork system writes to needs to be visible
 * upstream, the domain layer must route the access through [EcsBuilder.parent] so the
 * mutation lands on the parent's scratch instead. See the PRNG and respawn-queue
 * helpers in `SimBuilder.kt` for the canonical pattern. Under sequential fork
 * execution this is bit-identical to the old in-place behaviour; under future threading
 * those delegated accessors will need a lock at the domain layer.
 *
 * Building the fork's initial is O(parent component tables). Callers creating multiple
 * forks with the same frozen view (e.g. the N systems of a phase) should build one
 * [forkInitial] with [EcsBuilder.build] and feed it to [forkFrom] N times.
 */
fun <S> EcsBuilder<S>.fork(): EcsBuilder<S> = forkFrom(this.build())

/**
 * Like [fork], but uses [forkInitial] directly as the fork's [EcsBuilder.initial]
 * rather than building from this builder's state. Useful when several forks in the
 * same phase should share a single frozen snapshot.
 */
fun <S> EcsBuilder<S>.forkFrom(forkInitial: S): EcsBuilder<S> {
    val fork = EcsBuilder(
        initial = forkInitial,
        getComponents = getComponents,
        getWorld = getWorld,
        applyComponents = applyComponents,
    )
    fork.writeLog = mutableListOf()
    fork.parent = this
    return fork
}

/**
 * Replays [fork]'s write-log against this builder in recording order, then discards the
 * log. After this call, all of the fork's component writes are visible on this builder.
 *
 * Merging multiple forks back into a parent is done by calling [mergeFork] once per
 * fork, in the order the forks' systems are declared in the phase. Because the replay
 * is a straight re-application of each recorded block against the parent, and because
 * blocks for additive components like `ImpulseComponent` / `DamageComponent` commute,
 * the resulting parent state is bit-identical to the equivalent sequential execution
 * provided systems in the phase only *write* disjoint types and non-commutative writes
 * (e.g. bare replaces) happen in their registration order — which is exactly what
 * [runIsolated] guarantees.
 */
fun <S> EcsBuilder<S>.mergeFork(fork: EcsBuilder<S>) {
    val log = fork.writeLog ?: error(
        "mergeFork called on a non-forked builder (writeLog is null). " +
            "Only builders created via EcsBuilder.fork() can be merged."
    )
    for (replay in log) replay(this)
    fork.writeLog = null
    fork.parent = null
}
