package org.emerge.demo.outofspace.world

/**
 * What a tile *is*, structurally — which is the question everything in Phase 4 needs answered before
 * it can do anything. Heat needs to know what conducts, atmosphere needs to know what contains.
 */
enum class Structure {
    /** Open space. Cold, empty, and connected to the outside. */
    Vacuum,

    /** A wall. Blocks the inside from the outside, conducts heat, and is the only thing that does. */
    Hull,

    /** Inside the vessel: air, and whatever else is being kept alive in here. */
    Interior,

    /**
     * A deck machine: solid, so air cannot be in it or pass through it, but not a wall.
     *
     * It is its own case rather than being folded into [Hull] because "does not admit air" and "is
     * the vessel's skin" are different questions with different answers here — a smelter displaces
     * the air it stands in, and it is still a smelter and not a pressure vessel, so it does not get
     * a wall's thermal mass.
     */
    Machine,
}
