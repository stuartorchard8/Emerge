package org.emerge.demo.outofspace.world

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
 * not the tiles — so [forward] is grid-up and needs no rotation applied to it. A thruster's facing
 * and the pilot's intent are already expressed in the same basis, which is why this file does no
 * trigonometry at all.
 */
data class FlightIntent(
    /** Starboard-positive: +1 is "translate right", in grid columns. */
    val translateX: Int = 0,
    /** Down-positive, as every y in the grid is: −1 is "translate forward". */
    val translateY: Int = 0,
    /** Clockwise-positive on screen, matching the sign [torqueAbout] produces for a y-down grid. */
    val spin: Int = 0,
) {
    val isIdle: Boolean get() = translateX == 0 && translateY == 0 && spin == 0

    companion object {
        val NONE = FlightIntent()

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
                spin = held(InputKey.B) - held(InputKey.A),
            )
        }
    }
}

/**
 * How hard one thruster should fire to give the pilot what they asked for, in permille.
 *
 * The port of `Thruster.calculate_activation`. Two independent terms are summed and the total is
 * clamped, so an engine that both pushes the right way *and* turns the right way runs harder than
 * one that only does one of them — and an engine that would fight the request comes out negative,
 * which [org.emerge.demo.outofspace.OutofspaceReducer.throttled] reads as *off*. Nothing anywhere
 * decides which engines to use; every engine decides for itself, and the ones that would help fire.
 *
 * ### Translation
 *
 * `thrust · intent`, kept if it clears a half. Both are axis-aligned in this game so the dot is
 * exactly −1, 0 or 1, and the threshold is doing no work today — it is kept because it is what
 * decides the case the moment a thruster can point diagonally.
 *
 * ### Rotation, and why it is not simply "does it make torque"
 *
 * The lever arm from the centre of mass to the bell, crossed with the thrust direction, says which
 * way this engine turns the ship and how squarely. That alignment goes through
 * [activationCurve] — a soft saturation, `v/(1+v)` — and then is **multiplied by the length of the
 * lever arm**, so an engine far out on a boom is worth more to a turn than one beside the axis. It
 * is that multiplication that makes the whole scheme fly rather than merely work: the ship spends
 * its propellant where the propellant buys the most rotation.
 *
 * The quarter threshold on the curved value (`|v| > 1/3` before the curve) is the anti-chatter
 * rule. A motor nearly in line with the centre of mass produces a tiny torque either way, and
 * without a floor every engine on the ship would light for every nudge of the stick and cancel.
 *
 * [comX]/[comY] and the tile centre are in milli-tiles ([Rotation.MILLI_TILE]); the returned lever
 * arm is folded back to whole tiles inside the one expression, which is why no intermediate here is
 * a ratio anybody has to keep track of.
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
    var activation = 0L

    val alongX = thrust.dx * intent.translateX
    val alongY = thrust.dy * intent.translateY
    // Axis-aligned facings make this ±1 or 0; the threshold is the diagonal case's, kept live.
    val along = alongX + alongY
    if (along != 0) activation += along.toLong() * PERMILLE

    if (intent.spin != 0) {
        val leverX = atX - comX
        val leverY = atY - comY
        val lever = isqrt(leverX * leverX + leverY * leverY)
        // Sign convention: +z is clockwise on a y-down grid, which is what [torqueAbout] books and
        // what [FlightIntent.spin] means. `cross` is the torque this engine would make, in
        // milli-tiles, aligned so that positive is "the way the pilot asked for".
        val cross = (leverX * thrust.dy - leverY * thrust.dx) * intent.spin
        val magnitude = if (cross < 0) -cross else cross
        // |curve(cross/lever)| > 0.25  ⇔  3·|cross| > lever, with no division and no sqrt in it.
        if (lever > 0L && 3L * magnitude > lever) {
            // curve(cross/lever) × lever-in-tiles, in permille — see the doc above for the algebra.
            activation += cross * lever / ((lever + magnitude) * (Rotation.MILLI_TILE / PERMILLE))
        }
    }

    return activation.coerceIn(-PERMILLE, PERMILLE).toInt()
}

/** Full activation, in the permille [SignalField] and [Wiring] already count in. */
private const val PERMILLE: Long = 1000L

/**
 * The soft saturation the Godot original used: `sign(v)·(1 − 1/(1+|v|))`, i.e. `v/(1+|v|)`.
 *
 * Kept as a named function even though [thrusterActivation] inlines its integer form, because the
 * shape is the tuning decision and the algebra that avoids the division is not. Alignment near zero
 * costs nothing, and perfect alignment is worth a half rather than a whole — the remaining headroom
 * is what lets the lever arm decide between two equally well-aimed engines.
 */
fun activationCurve(value: Double): Double = value / (1.0 + (if (value < 0) -value else value))
