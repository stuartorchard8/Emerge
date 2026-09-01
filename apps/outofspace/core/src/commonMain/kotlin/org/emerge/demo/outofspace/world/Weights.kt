package org.emerge.demo.outofspace.world

/*
 * ⛔ `WEIGHT_LADDER` stood here. A wiring term has a sign and no strength now, so there is no weight
 * to cycle — the UI toggles NOT instead. See [Trigger].
 */

/** The ladders the UI cycles through. A short ladder beats a slider on a touchscreen. */
val POSITIVE_WEIGHT_LADDER: List<Int> = listOf(1000, 750, 500, 250, 0)
val TICK_LADDER: List<Int> = listOf(64*120, 64*60, 64*30, 64*20, 64*10, 64*5, 64*2, 64, 32, 16, 8, 0)
