package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.isqrt
import org.emerge.demo.outofspace.world.machine.InputKey

/**
 * What the pilot is asking the *vessel* to do, as opposed to what any one machine is asked to do.
 *
 * Six keys make three axes: a translation in the vessel's own frame and a spin about its centre of
 * mass. This is the whole of the flight stick, and it is deliberately a statement about the **ship**
 * and not about any thruster — no key names a motor, and no motor is bound to a key. Each thruster
 * works out for itself whether firing would help ([thrusterActivation]), which is what lets a player
 * bolt engines on wherever they fit and still fly with WSAD.
 *
 * Ported from the Godot original's `thruster.gd`, where the same idea ran in three dimensions with
 * pitch/yaw/roll instead of one spin. The 2D collapse is exact: a cross product of two in-plane
 * vectors has only a z component, so "which way does this engine turn the ship" is one signed
 * number rather than an axis.
 *
 * ⚠️ **The frame is the grid's.** The grid *is* the vessel's own frame — rotation turns the view,
 * not the tiles — so forward is grid-up and needs no rotation applied to it. A thruster's facing and
 * the pilot's intent are already expressed in the same basis, which is why this file does no
 * trigonometry at all.
 */
data class FlightIntent(
    /** Starboard-positive: +1 is "translate right", in grid columns. */
    val translateX: Int = 0,
    /** Down-positive, as every y in the grid is: −1 is "translate forward". */
    val translateY: Int = 0,
    /**
     * Clockwise-positive on screen, in **permille**, matching the sign [torqueAbout] produces for a
     * y-down grid.
     *
     * A magnitude and not a direction, unlike the two translation axes, because [Sas] contributes a
     * *partial* spin: a ship drifting gently needs a gentle correction, and a switch that could only
     * say "hard about" would ring rather than settle. A key press is ±[FULL].
     */
    val spin: Int = 0,
) {
    val isIdle: Boolean get() = translateX == 0 && translateY == 0 && spin == 0

    /**
     * The same intent with the autopilot's counter-spin folded into it — see [Sas].
     *
     * Folded into the *intent* rather than handled beside it, and that is the port's central choice
     * exactly as it was the original's: SAS does not command thrusters, it leans on the stick. Every
     * motor goes on reading one spin number and cannot tell whether a person or the autopilot put it
     * there, so there is no second path through the machine for the correction to behave differently
     * on.
     */
    fun withSas(angVel: Long): FlightIntent =
        copy(spin = (spin + Sas.correction(angVel)).coerceIn(-FULL, FULL))

    companion object {
        val NONE = FlightIntent()

        /** Full deflection, in the permille [SignalField] and [Wiring] already count in. */
        const val FULL: Int = 1000

        /**
         * The held-key mask read as a flight stick.
         *
         * The [InputKey] palette is unchanged and still deliberately un-named — a key is an input,
         * and what it means is whatever reads it. A [org.emerge.demo.outofspace.world.machine.WireButton]
         * goes on reading the same six keys as six anonymous signals; this reads them as a stick.
         * On the desktop they are W/S/A/D and Q/E.
         */
        fun of(heldKeys: Int): FlightIntent {
            fun held(key: InputKey) = if (InputKey.heldIn(heldKeys, key)) 1 else 0
            return FlightIntent(
                translateX = held(InputKey.Right) - held(InputKey.Left),
                translateY = held(InputKey.Down) - held(InputKey.Up),
                spin = (held(InputKey.E) - held(InputKey.Q)) * FULL,
            )
        }
    }
}

/**
 * Stability augmentation: hold the ship still by leaning on the rotation stick, not by commanding
 * anything.
 *
 * The port of `vessel.gd`'s `counter_rotate`, which adds the negated angular velocity into the same
 * Pitch/Yaw/Roll inputs the thrusters were already reading. In two dimensions that is one number.
 *
 * ⚠️ **It is a feedback loop, so it acts only on rotation that has already started.** It is not what
 * keeps a translation burn straight — [flightActivations] does that ahead of time, by scaling — and
 * conflating the two is the mistake that makes an autopilot look like it is working while it quietly
 * burns propellant round the clock. What SAS is for is everything feed-forward cannot see: a
 * collision, a rock landing on the deck, a tank emptying and moving the centre of mass out from
 * under an engine that was balanced when the burn started.
 */
object Sas {

    /**
     * **The dial.** The angular velocity at which the autopilot asks for everything it has, in
     * [Coord] raw per tick.
     *
     * A full revolution in a hundred seconds. Chosen as the fastest spin a player would still call
     * "drifting": below it the correction is proportional and settles gently, and above it the
     * autopilot is simply flat out, which is the right thing to be when the ship is tumbling.
     *
     * ⚠️ **Lower is a stronger autopilot, not a weaker one** — it is the point at which the
     * controller saturates, so it sets the gain.
     *
     * ⚠️ **And on most ships it makes no difference at all**, which is worth knowing before anybody
     * turns it looking for a faster recovery. [rotationTerm] multiplies by the lever arm, so a motor
     * a few tiles off the axis is already clamped at full on a spin far inside this dial: measured on
     * a two-engine box, moving this by 5× changed the recovery by *nothing*, because both settings
     * saturated the same two motors. Recovery is limited by how much torque the ship can make, not by
     * the gain. The dial bites only for motors close enough to the axis that the term does not clamp.
     */
    val FULL_AUTHORITY: Long = Rotation.RAW_PER_RADIAN * 2L / 125L

    /**
     * Below this the ship counts as still and the autopilot lets go, in [Coord] raw per tick.
     *
     * ⚠️ **Not a tuning nicety — without it SAS never stops burning.** A proportional controller
     * asks for a little thrust at a little error for ever, and "a little thrust" out of a rocket is
     * still propellant leaving the ship.
     *
     * **Derived rather than chosen: it is the controller's own resolution.** [correction] asks for
     * `magnitude × FlightIntent.FULL / FULL_AUTHORITY` permille, so below
     * `FULL_AUTHORITY / FlightIntent.FULL` that quotient truncates to zero and the autopilot is
     * asking for nothing whatever the deadband says. Setting the two equal is the tightest deadband
     * that means anything — one that lets go exactly when it runs out of things to ask for, instead
     * of well before.
     *
     * ⚠️ **Was a fiftieth of full authority**, and that left a settled ship turning once every five
     * and a half minutes — slow, but visibly not stopped, and it was noticed in play. A twentieth of
     * that is a residual of about one revolution every two hours, for 3% more propellant and roughly
     * twice as long to settle.
     *
     * ⛔ **Tightening it past this buys nothing at all.** Measured at a five-thousandth, the ship
     * never reaches the band: the permille truncates to zero first, so it stops where it is with the
     * autopilot still nominally chasing it. A deadband under the resolution is not a stricter rule,
     * it is a rule that stops being applied.
     */
    val DEADBAND: Long = FULL_AUTHORITY / FlightIntent.FULL

    /** How hard to lean on the stick against a ship turning at [angVel]: opposite, and proportional. */
    fun correction(angVel: Long): Int {
        val magnitude = if (angVel < 0L) -angVel else angVel
        if (magnitude <= DEADBAND) return 0
        val asked = (magnitude * FlightIntent.FULL / FULL_AUTHORITY).coerceAtMost(FlightIntent.FULL.toLong())
        return if (angVel > 0L) -asked.toInt() else asked.toInt()
    }
}

/**
 * One motor, as the flight controls see it: which way it pushes, and where it is relative to the
 * centre of mass.
 *
 * A value handed *to* [flightActivations] rather than a thruster read *by* it, so the whole of the
 * allocation is testable without a world — and so that the reducer, which already knows how to skip
 * a ghost and a machine being taken apart, stays the one place that decides which motors exist.
 */
class Motor(
    val thrust: Direction,
    /** Bell minus centre of mass, in milli-tiles ([Rotation.MILLI_TILE]). */
    val leverX: Long,
    val leverY: Long,
    /** What it throws at full activation: the weight its torque carries in the balance. */
    val massPerTick: Long,
) {
    /**
     * The torque this motor makes per unit of thrust, in milli-tiles: `r × d̂`, clockwise-positive.
     *
     * The lever arm is *in* this number rather than divided back out of it, because that is what
     * makes it the thing that can be summed: a torque is a force times a distance, and two motors
     * balance when their `activation × massPerTick × cross` cancel.
     */
    val cross: Long get() = leverX * thrust.dy - leverY * thrust.dx
}

/**
 * How hard every motor on the ship should fire, in permille, one entry per [Motor] in order.
 *
 * Two passes, and the order of them is the whole design.
 *
 * ### 1. Translation, then **balanced**
 *
 * Every motor that pushes the way the pilot asked runs at full — and then, because a ship is not
 * symmetric and its engines are wherever they fit, the ones that would twist it are throttled back
 * until the twist cancels. The side making more torque is scaled down to match the side making less;
 * nothing is ever scaled *up*, and no motor that was not already helping is lit.
 *
 * That last sentence is the fuel argument and it is the reason this is done here rather than left to
 * [Sas]. Given a forward request and two rearward motors either side of the centre of mass, the
 * answer is those two motors at different throttles and **nothing else running at all** — no lateral
 * engine fired to cancel a torque that a throttle could have prevented, and no propellant spent
 * pushing the ship sideways so that it can be pushed back. A feedback loop cannot do this: it has to
 * let the rotation start before it can see it, and by then the cheapest fix is no longer available.
 *
 * ⚠️ **A motor with nothing to balance against fires anyway.** One lonely off-axis engine is the only
 * way that ship can translate at all, so it translates and it spins, and the autopilot cleans up
 * afterwards if it is on. Refusing to fire would be a ship that cannot move, which is a worse answer
 * than a ship that wallows.
 *
 * ### 2. Rotation, added on top
 *
 * The turn the pilot (or [Sas]) asked for is then added — unbalanced, deliberately. A rotation
 * request *is* a request for net torque, so there is nothing to cancel; what a motor contributes is
 * its alignment through [Sas]-independent [rotationTerm], and the sum is clamped last so a motor
 * asked for both at once cannot exceed itself.
 */
fun flightActivations(intent: FlightIntent, motors: List<Motor>): IntArray {
    val out = IntArray(motors.size)
    if (motors.isEmpty() || intent.isIdle) return out

    // ── 1. Translation ───────────────────────────────────────────────────────
    var clockwise = 0L
    var widdershins = 0L
    for (i in motors.indices) {
        val m = motors[i]
        val term = translationTerm(intent, m.thrust)
        out[i] = term
        if (term <= 0) continue
        // Reduced by a gram so that many engines cannot overflow the sum. The balance is a *ratio*,
        // so any divisor common to both sides is free — and a motor throwing less than a gram a tick
        // is not one whose torque anybody can measure.
        val torque = term.toLong() * m.cross * (m.massPerTick / Budget.GRAM)
        if (torque > 0L) clockwise += torque else widdershins -= torque
    }
    if (clockwise > 0L && widdershins > 0L && clockwise != widdershins) {
        // Scale the stronger side down onto the weaker one. Down and never up: the weaker side is
        // already flat out, and a throttle that could exceed itself would be minting thrust.
        val heavy = if (clockwise > widdershins) 1L else -1L
        val numerator = if (clockwise > widdershins) widdershins else clockwise
        val denominator = if (clockwise > widdershins) clockwise else widdershins
        for (i in motors.indices) {
            if (out[i] <= 0) continue
            val side = motors[i].cross
            if (side == 0L || (side > 0L) != (heavy > 0L)) continue
            out[i] = (out[i].toLong() * numerator / denominator).toInt()
        }
    }

    // ── 2. Rotation ──────────────────────────────────────────────────────────
    for (i in motors.indices) {
        val m = motors[i]
        out[i] = (out[i] + rotationTerm(intent.spin, m.thrust, m.leverX, m.leverY))
            .coerceIn(-FlightIntent.FULL, FlightIntent.FULL)
    }
    return out
}

/**
 * How much this motor's push is the push that was asked for: `thrust · intent`, in permille.
 *
 * Both are axis-aligned in this game so the dot is exactly −1, 0 or 1, and the original's half
 * threshold is doing no work today — it is kept because it is what decides the case the moment a
 * thruster can point diagonally.
 *
 * Negative for a motor that would push the wrong way, and not zero: an activation is a signed number
 * and the throttle reads anything at or below zero as off, so an engine that would fight the request
 * cannot be dragged back open by a rotation term that happens to like it.
 */
fun translationTerm(intent: FlightIntent, thrust: Direction): Int {
    val along = thrust.dx * intent.translateX + thrust.dy * intent.translateY
    return along * FlightIntent.FULL
}

/**
 * How much this motor's push is the *turn* that was asked for, in permille.
 *
 * The lever arm from the centre of mass to the bell, crossed with the thrust direction, says which
 * way this engine turns the ship and how squarely. That alignment goes through the original's soft
 * saturation `v/(1+|v|)` and then is **multiplied by the length of the lever arm**, so an engine far
 * out on a boom is worth more to a turn than one beside the axis. It is that multiplication that
 * makes the whole scheme fly rather than merely work: the ship spends its propellant where the
 * propellant buys the most rotation.
 *
 * The quarter threshold on the curved value (`|v| > 1/3` before the curve, i.e. `3|cross| > lever`
 * with no division and no square root in it) is the anti-chatter rule. A motor nearly in line with
 * the centre of mass produces a tiny torque either way, and without a floor every engine on the ship
 * would light for every nudge of the stick and cancel.
 *
 * ⚠️ **The threshold is asked of the *normalised* alignment and the magnitude is applied after**,
 * exactly as `calculate_torque` does. Folding [spin] in first would make a gentle correction fail a
 * test that a hard one passes, so the same geometry would light different engines depending on how
 * urgent the turn was — which is how an autopilot ends up hunting.
 */
fun rotationTerm(spin: Int, thrust: Direction, leverX: Long, leverY: Long): Int {
    if (spin == 0) return 0
    val lever = isqrt(leverX * leverX + leverY * leverY)
    if (lever <= 0L) return 0
    // Signed so that positive is "the way the stick was pushed", in milli-tiles.
    val cross = (leverX * thrust.dy - leverY * thrust.dx) * (if (spin > 0) 1L else -1L)
    val magnitude = if (cross < 0L) -cross else cross
    if (3L * magnitude <= lever) return 0
    val strength = (if (spin < 0) -spin else spin).toLong()
    // curve(cross/lever) × lever-in-tiles × how hard the stick is over, all in permille. The two
    // MILLI_TILE-scaled terms cancel against the permille the answer is wanted in.
    return (cross * lever / ((lever + magnitude) * (Rotation.MILLI_TILE / FlightIntent.FULL)) * strength /
        FlightIntent.FULL).toInt()
}

/**
 * What one motor makes of the stick, on its own, with nothing balanced against it.
 *
 * The single-engine answer — the sum of the two terms above. It is what the inspector panel reports
 * and what a test of the geometry asks, and it is deliberately **not** what the reducer uses: a real
 * ship's throttles depend on the other engines aboard, which is [flightActivations]'s business.
 */
fun thrusterActivation(
    intent: FlightIntent,
    thrust: Direction,
    atX: Long,
    atY: Long,
    comX: Long,
    comY: Long,
): Int {
    if (intent.isIdle) return 0
    val term = translationTerm(intent, thrust) +
        rotationTerm(intent.spin, thrust, atX - comX, atY - comY)
    return term.coerceIn(-FlightIntent.FULL, FlightIntent.FULL)
}

/**
 * The soft saturation the Godot original used: `sign(v)·(1 − 1/(1+|v|))`, i.e. `v/(1+|v|)`.
 *
 * Kept as a named function even though [rotationTerm] inlines its integer form, because the shape is
 * the tuning decision and the algebra that avoids the division is not. Alignment near zero costs
 * nothing, and perfect alignment is worth a half rather than a whole — the remaining headroom is
 * what lets the lever arm decide between two equally well-aimed engines.
 */
fun activationCurve(value: Double): Double = value / (1.0 + (if (value < 0) -value else value))
