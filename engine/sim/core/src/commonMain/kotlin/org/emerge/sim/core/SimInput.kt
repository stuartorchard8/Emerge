package org.emerge.sim.core

/**
 * Marker for per-tick player input passed to a [SimReducer]. Each demo defines its own
 * concrete input type implementing this interface; the engine doesn't dictate any field
 * shape — Scavengers carries thrust/turn integers, Drockets has no per-tick player input
 * at all (its `DrocketsInput` is an empty singleton).
 */
interface SimInput
