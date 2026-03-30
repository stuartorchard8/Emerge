package org.emerge.sim.sync.lockstep

/**
 * How a remote client participates in the simulation:
 *
 * - [LOCKSTEP] — client runs the full simulation locally, receiving only the input bundle each tick.
 * - [SEMI_THIN] — client defers complex simulation to the host, receiving impulses and inputs each tick.
 * - [THIN] — client defers simulation to the host and receives periodic full-state snapshots.
 */
enum class ClientMode {
    LOCKSTEP,
    SEMI_THIN,
    THIN,
}
