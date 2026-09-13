package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget

/**
 * **One engine's exhaust, for the tick it left by** — everything a picture or a noise of a burning
 * motor could want, and nothing the sim reads back.
 *
 * The sim's twin of [Impact], and it exists for the same reason: only the pass that threw the mass
 * knows what it threw, how fast, and how hot, and by the time a host has the next state every one of
 * those numbers has been spent. A renderer could rediscover *that* a motor is firing — [Engine.firing]
 * is on the machine — but not what came out of it, because the propellant has left the store it was
 * priced from.
 *
 * ⛔ **Presentation only.** Nothing in the sim reads this, the save does not carry it, and a loaded
 * world starts with its engines dark until the first tick relights them. It is dropped rather than
 * remapped when the grid resizes — [bell] is a tile index, and a re-indexed grid is a frame where
 * nothing animates, which is the trade [VesselState.motion] already made.
 *
 * ⭐ **The whole [mixture] rides along, not a colour and a mean.** What is in the exhaust is exactly
 * what the two consumers differ about: a plume's *colour* wants the dominant species, and its
 * *sound* wants the molar mass the velocity was priced off. Handing over a pre-chewed number for
 * each would be two decisions taken in the sim on behalf of code that is better placed to take them,
 * and a third consumer would need a third field. It is one already-allocated object per firing
 * engine per tick — the parcel `fire` drew, after it has been drawn.
 */
class Plume(
    /**
     * The nozzle: the tile the exhaust starts *at*, which is the machine's own bell and not the tile
     * the machine is stored at — see [org.emerge.demo.outofspace.world.machine.Engine.bell].
     */
    val bell: TileIndex,
    /** The way the exhaust goes. The ship goes the other way. */
    val facing: Direction,
    /**
     * What actually left the nozzle this tick: the propellant drawn from the store **plus** whatever
     * gas the jet entrained on its way out.
     *
     * ⚠️ Not [org.emerge.demo.outofspace.world.machine.Engine.massPerTick], which is what the motor
     * would throw at full activation with a full store. This is the throttled, store-limited truth,
     * which is why a motor running dry fades out instead of cutting.
     */
    val mass: Long,
    /**
     * How fast it left, in **metres per second** — [org.emerge.demo.outofspace.world.machine.Thruster.exhaustVelocity]'s
     * own unit, kept as it was priced rather than converted into the milli-tiles the impulse is
     * booked in. A cold nitrogen puff is ~780 and hot hydrogen ~9,300, and the range between those
     * two is the mechanic a player is being shown.
     */
    val metresPerSecond: Long,
    /**
     * Width of the nozzle that the plume exited from in tiles.
     */
    val nozzleWidth: Long,
    /** How hot the parcel was as it went, in kelvin. */
    val kelvin: Int,
    /**
     * What the motor was told to do, in permille — [org.emerge.demo.outofspace.world.machine.Engine.firing]
     * as of this tick.
     *
     * ⚠️ **Not the same thing as [mass], and both are here on purpose.** A motor at full throttle
     * with a nearly-empty store throws almost nothing; a motor at a tenth throttle with a full one
     * throws a tenth of its rate. A picture of a jet wants the first — how hard it is being asked to
     * work — and a noise of one wants the second, because it is the mass flow that makes the roar.
     */
    val firing: Int,
    /** What went: the parcel [org.emerge.demo.outofspace.OutofspaceSim] drew, after it was drawn. */
    val mixture: Mixture,
    /**
     * How many tiles of clear space the jet crossed — 0 for a motor bolted bell-first against a
     * wall, and the distance to the rim for one firing into the open.
     *
     * ⚠️ **Capped nowhere.** A motor pointing along a long vessel's axis reports the whole length of
     * it, and a plume drawn at that length would be a stripe across the ship. How far a *picture* of
     * an exhaust should reach is a look and not a fact, so it is the drawing that decides — this is
     * only the room the jet actually had.
     */
    val reach: Int,
    /**
     * True when the exhaust left the world, which is the only case that pushes the ship — see
     * [org.emerge.demo.outofspace.world.machine.ExhaustPath].
     *
     * A blocked motor is still a plume, and deliberately: it burns, it roars, and it cooks the tile
     * in front of it. Drawing and sounding nothing there would hide the one mistake this reports.
     */
    val clear: Boolean,
) {
    /**
     * **What one mole of this exhaust weighs, in grams** — the number the velocity was priced off,
     * and the one an ear hears: a light molecule leaves fast and hisses, a heavy one leaves slowly
     * and roars.
     *
     * Derived rather than stored, because most plumes are never asked: the renderer wants a colour
     * and the speakers want this, and neither should pay for the other's arithmetic. A parcel of
     * exhaust is half a kilogram, far below the mole table's overflow — see `millimolesOf`, which a
     * store-sized heap would send negative.
     *
     * Zero for a parcel too thin to weigh, which is the same answer
     * [org.emerge.demo.outofspace.world.machine.Thruster.exhaustVelocity] gives it.
     */
    val gramsPerMole: Int
        get() {
            var millimoles = 0L
            for (s in Species.ALL) {
                val held = mixture[s]
                if (held != 0L) millimoles += millimolesOf(held, s)
            }
            if (millimoles <= 0L) return 0
            // The ratio before the divide: flooring to whole grams first loses a light propellant
            // entirely, which is the lesson `kelvinOf` learned and `exhaustVelocity` repeats.
            return (mixture.total * 1_000L / (Budget.GRAM * millimoles)).toInt()
        }
}
