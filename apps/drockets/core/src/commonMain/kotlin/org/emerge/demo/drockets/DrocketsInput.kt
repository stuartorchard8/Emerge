package org.emerge.demo.drockets

import org.emerge.sim.core.SimInput

/**
 * Drockets has no per-tick player input — the simulation runs autonomously and the
 * only "human" interactions are save/load and renderer-side controls (camera, filters).
 * Engine's [SimReducer] signature requires an input type, so this empty singleton fills
 * the slot.
 */
object DrocketsInput : SimInput
