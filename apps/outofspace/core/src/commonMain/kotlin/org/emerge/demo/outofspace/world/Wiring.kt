package org.emerge.demo.outofspace.world

/**
 * Where one term of a machine's activation gets its number from.
 *
 * Two sources, and deliberately only two. [Always] is the constant — it reads full forever, needs no
 * wire, and is what a freshly placed machine is wired to, so placing a machine still just works and
 * wiring remains something you *add*. [Wire] reads the signal network under the machine's own tile.
 *
 * This pair is what replaced the six colours, and it is what made that replacement safe: an unwired
 * [Wire] term reads 0, which is exactly what an unemitted channel read, so every vessel that was
 * wired `ALWAYS − RED` went on behaving identically as `ALWAYS − WIRE` the day it changed.
 */
enum class SignalSource(val label: String) {
    Always("ALWAYS"),
    Wire("WIRE"),
    ;

    companion object {
        val ALL: List<SignalSource> = entries.toList()
    }
}

/**
 * One term of an action's sum: where the value comes from, and whether it counts for or against.
 *
 * ⛔ **A term has a sign, not a strength.** It used to carry a weight in permille, and the sum of
 * `signal × weight` was a throttle — a half-strength signal ran a machine at half speed. The signal
 * network is binary now, so the only thing a weight could still express was a constant: `ALWAYS ×
 * 50%` was a speed dial wearing a wire's clothes. A dial belongs on the machine it governs, where it
 * can mean something specific, rather than on a wire that has to mean the same thing for every
 * machine it reaches. See [Wiring].
 */
data class Trigger(val source: SignalSource, val negated: Boolean = false)

/**
 * Machine action drivers: a machine is on when its terms sum above zero.
 *
 * **Binary in, binary out.** Every term contributes +1 or −1 — [SignalSource.Always] always counts,
 * [SignalSource.Wire] counts only while the wire under the machine is live — and the machine runs if
 * the total is positive. There is no half-on: a machine that wants a rate picks it internally, and a
 * machine that wants to be clever about *when* it runs decides that on its own tile, the way a
 * [org.emerge.demo.outofspace.world.machine.Sensor] decides its threshold.
 *
 * ⛔ **This replaced a proportional controller, and the loss was deliberate.** Activation used to be
 * Σ(signal × weight) clamped to ±1000, and machines scaled their rate by it. What killed it was
 * that nothing on the network could *vary* any more once sensors started reporting a verdict instead
 * of a reading — so the arithmetic was carrying constants, and a constant is a setting. Analogue did
 * not go away; it moved inside the machines, where a thruster under
 * [org.emerge.demo.outofspace.world.machine.ThrusterControl.Flight] still gets a per-motor throttle
 * from the flight solver without the wire being involved at all.
 *
 * The sum survives the change untouched. `ALWAYS` alone is on; `ALWAYS` plus `NOT WIRE` is on until
 * the wire goes live, which is what "stop when full" always meant.
 *
 * ⚠️ One thing the change *gained*: `NOT WIRE` on its own now runs. Under weights it could not —
 * a lone negative term summed to zero or less and the machine never started, so "run while this is
 * quiet" had to be spelled `ALWAYS − WIRE`. Both spellings mean the same thing now, and the short
 * one is the one a player reaches for.
 */
data class Wiring(val byAction: Map<Action, List<Trigger>> = DEFAULT) {

    fun triggers(action: Action): List<Trigger> = byAction[action] ?: emptyList()

    /**
     * Whether [action] is on: the terms, each ±1, summing above zero.
     *
     * [wire] is what the network under this machine is carrying — see [SignalField.at]. It is passed
     * in rather than looked up because [Wiring] does not know where its machine is, and giving it a
     * tile index would make a machine's wiring depend on where it happens to be stored. It arrives as
     * an [Int] because that is what a [SignalField] stores; anything above zero is live, and since
     * every transmitter raises [SignalField.FULL] or nothing, that is the only distinction there is.
     *
     * ⚠️ **No terms means off**, not on. A machine placed with no wiring gets [DEFAULT], which is a
     * term; a machine whose last term the player deleted is one they told to stop.
     */
    fun isOn(action: Action, wire: Int): Boolean {
        var sum = 0
        for (t in triggers(action)) {
            val live = when (t.source) {
                SignalSource.Always -> true
                SignalSource.Wire -> wire > 0
            }
            if (live != t.negated) sum++ else sum--
        }
        return sum > 0
    }

    fun with(action: Action, triggers: List<Trigger>): Wiring =
        Wiring(byAction.toMutableMap().also { it[action] = triggers })

    companion object {
        /** Wired to the constant: a machine you place just works. */
        val DEFAULT: Map<Action, List<Trigger>> = mapOf(
            Action.Run to listOf(Trigger(SignalSource.Always)),
        )

        val RUNNING = Wiring()
    }
}
