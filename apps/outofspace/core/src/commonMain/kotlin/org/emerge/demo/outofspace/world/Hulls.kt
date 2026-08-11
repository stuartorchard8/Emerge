package org.emerge.demo.outofspace.world

/** A vent: throws material overboard. Somewhere for slag to go that is not "jam the line". */
data class Vent(
    val ventedGrams: Long = 0L,
    override val wiring: Wiring = Wiring.RUNNING,
    override val joules: Long = ambientJoules(MachineKind.Vent),
) : Machine {
    override val kind: MachineKind get() = MachineKind.Vent
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withJoules(joules: Long): Machine = copy(joules = joules)
}

/**
 * A wall. It does nothing, which is the point: it is the only thing that separates inside from
 * outside, and everything about heat and air follows from where it is.
 *
 * Hull is a machine rather than a separate "paint the structure" mode purely so it reuses the whole
 * build/remove/inspect pipeline unchanged — placing a wall and placing a belt should not be two
 * different verbs. [StructureMap] derives the enclosed space from wherever these end up.
 */
data class Hull(
    override val wiring: Wiring = Wiring.RUNNING,
    override val joules: Long = ambientJoules(MachineKind.Hull),
) : Machine {
    override val kind: MachineKind get() = MachineKind.Hull
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withJoules(joules: Long): Machine = copy(joules = joules)
}

/**
 * A hull tile that opens. Signal it and it becomes a hole; stop signalling and it is a wall again.
 *
 * It is a [Hull] in every respect but one, which is why it is a machine of its own rather than a flag
 * on hull: what varies is not *whether* it is open but *how much*, and that number has to come from
 * somewhere. It comes from the same `Σ(signal × weight)` every other machine runs on, so an airlock
 * is wired exactly like a smelter is throttled, and a half-strength signal cracks the door halfway.
 *
 * Note the default wiring. Every other machine ships wired to [Channel.Always] at full, because a
 * machine you place should just work — but "just works" for a door in the only thing holding your air
 * in means **shut**, so this one is placed wired to nothing and stays sealed until you say otherwise.
 *
 * An open airlock is not a wall with a hole in it; it is genuinely not there. See
 * [org.emerge.demo.outofspace.world.airlockOpenness] for what that costs and why it is right.
 */
data class Airlock(
    override val wiring: Wiring = SEALED,
    override val joules: Long = ambientJoules(MachineKind.Airlock),
) : Machine {
    override val kind: MachineKind get() = MachineKind.Airlock
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withJoules(joules: Long): Machine = copy(joules = joules)

    companion object {
        /** Wired to nothing: a freshly placed door holds pressure until it is given a channel. */
        val SEALED = Wiring(mapOf(Action.Run to emptyList()))
    }
}
