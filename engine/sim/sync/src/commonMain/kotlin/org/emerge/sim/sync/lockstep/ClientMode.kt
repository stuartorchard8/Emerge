package org.emerge.sim.sync.lockstep

/**
 * How a remote client participates in the simulation:
 *
 * - [LOCKSTEP] — client runs the full simulation locally, receiving only the input bundle each tick.
 * - [THIN] — client defers simulation to the host and receives periodic full-state snapshots.
 */
enum class ClientMode {
    LOCKSTEP,
    THIN,
}
