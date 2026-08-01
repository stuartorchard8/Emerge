package org.emerge.demo.outofspace

/**
 * How many ticks a stretch of world-time is, at whatever rate the sim actually runs at.
 *
 * Every test that says "run this for thirty seconds" used to write `60 times 30`, and the 60 was a
 * literal rather than a reading of [OutofspaceConfig.ticksPerSecond]. Dropping the default rate to 4
 * turned all of them into "run this for **four hundred and fifty** seconds" at a stroke, and the
 * failures that followed said nothing about tick rates: a miner had dug fifteen times too much, a
 * gauge's line had drained empty long before it was looked at, a tank had hit its cap and the branch
 * feeding it looked lopsided. Eight tests, eight unrelated-looking symptoms, one cause.
 *
 * So durations are stated in seconds and converted here. A test asserting "1kg a second for ten
 * seconds is 10kg" is then a statement about the *machine*, which is what it was always trying to
 * be, and changing the tick rate again cannot quietly change what any of them mean.
 *
 * Ticks are still the right unit where the tick is the thing under test — [org.emerge.demo.outofspace.world.Bridge.STEP_TICKS]
 * multiples in `BridgeTest`, or [org.emerge.demo.outofspace.logistics.Rate] in `PacketTest`, which
 * passes its own rate in and is testing the arithmetic rather than the world.
 */
fun seconds(count: Int): Int = TICKS_PER_SECOND * count

/** The rate every test builds its config at: whatever the game's default is. */
val TICKS_PER_SECOND: Int = OutofspaceConfig().ticksPerSecond
