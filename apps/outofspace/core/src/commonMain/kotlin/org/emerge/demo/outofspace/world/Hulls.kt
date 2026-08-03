package org.emerge.demo.outofspace.world

/** A vent: throws material overboard. Somewhere for slag to go that is not "jam the line". */
data class Vent(
    val ventedGrams: Long = 0L,
    override val wiring: Wiring = Wiring.RUNNING,
) : Machine {
    override val kind: MachineKind get() = MachineKind.Vent
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
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
) : Machine {
    override val kind: MachineKind get() = MachineKind.Hull
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
}
