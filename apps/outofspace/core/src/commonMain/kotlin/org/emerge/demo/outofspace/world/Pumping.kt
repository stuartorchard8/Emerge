package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.Pump

/*
 * ⛔ **`applyPumps` and `PumpDemand` stood here, and they went with the pump's old job.**
 *
 * A pump filled a pipe against a pressure gradient and stalled at four atmospheres. It is a rail
 * source now — see `Pump` and `PLAN_fluid_thrusters.md` §3.1 — so there is no demand to assemble and
 * nothing to push uphill. The rest of the pipe network follows in §9; this much could go early
 * because nothing else ever called it.
 */
