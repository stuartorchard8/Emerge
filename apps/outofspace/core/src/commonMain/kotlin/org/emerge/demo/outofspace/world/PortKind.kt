package org.emerge.demo.outofspace.world

/** Whether material enters the machine here or leaves it here. */
enum class PortKind { Input, Output }

/**
 * Which of a machine's output buffers a port drains.
 *
 * Machines that separate a product from a waste stream need to say which port is which, and the old
 * "product leaves by `facing`, waste by `facing.clockwise`" rule could only express that because
 * every machine was one tile. On a five-tile furnace the two streams leave from genuinely different
 * places, so the stream has to be named rather than inferred from an angle.
 */
enum class Stream { Product, Waste }
