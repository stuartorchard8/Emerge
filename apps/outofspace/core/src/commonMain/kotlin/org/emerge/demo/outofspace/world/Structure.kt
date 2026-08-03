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

    /** Inside the vessel: air, machines, and whatever else is being kept alive in here. */
    Interior,
}
