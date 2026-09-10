package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.chem.BASE_RATE
import org.emerge.demo.outofspace.world.Wiring

/**
 * A well-insulated box in which the player chooses the conditions.
 *
 * Thermal decomposition — carbonates and hydrates giving up CO₂/H₂O on heating alone, calcite →
 * lime + CO₂, serpentine → olivine + water — is not something this machine *does*. It is something
 * that happens to matter at a temperature, anywhere in the vessel, and this is simply the one place
 * where a temperature can be asked for rather than merely suffered. See
 * `PLAN_ambient_chemistry.md`, decision 3.
 *
 * ⚠️ **It has no recipe and no rate.** Those went with the chemistry when the chemistry left. What is
 * left is a thermostat with a timer: it pulls a charge in, runs an element until the charge reaches
 * [setTemperature], holds it there for [dwellTicks], and hands on whatever the charge has *become* by
 * then. If nothing decomposes at the temperature the player set, nothing decomposes — which is the
 * machine finally telling the truth about what it is for.
 *
 * ### Two dials, because conversion is asymptotic
 *
 * ⚠️ **[dwellTicks] is not the `ticksPerAction` increment 3 deleted**, and the difference is the whole
 * argument. That was a hidden constant standing in for a reaction rate nobody had modelled: a charge
 * was "done" after 128 ticks because the number said so. This is a **control**, and it exists because
 * of something the chemistry turned out to be: a reaction approaches completion asymptotically, so
 * there is no moment at which a charge is finished, and any rule claiming to find one is either an
 * invented threshold or a wait that never ends.
 *
 * A residence time is what a real furnace operator sets, and it makes the pair of dials a genuine
 * decision — hotter converts faster but costs more element and leaks more heat into the room; longer
 * converts more of each charge but throttles throughput. Neither dominates.
 *
 * ⚠️ **A setpoint barely above onset is slow, and that is physics rather than a tax.** 30 K over
 * calcite's onset is about 0.3% of the charge per chemistry pass; 400 K over is nearer 4%. What the
 * dwell buys the player is the choice to sit there anyway.
 *
 * ⚠️ **Zero is the default and it is the old behaviour exactly** — hand on the moment the charge is at
 * temperature. So the dial is opt-in, and a decomposer nobody has tuned behaves as it always did.
 *
 * ### What goes in is the player's list, not the network's leftovers
 *
 * ⛔ **A decomposer states what may be sent to it, and an empty statement means nothing may.** See
 * [whitelist] and [FeedBook]. This machine is the reason that interface is not just the ejector's
 * field: what a kiln and a hole in the hull have in common is that both are places the player must
 * *name* what goes, and the network then routes accordingly.
 *
 * ⚠️ **It has no opinion about whether the list makes sense.** Ticking iron on a furnace set to
 * 300 K fills the chamber with iron and holds it at 300 K, which is a legible thing to have built by
 * mistake and not something to refuse on the player's behalf.
 *
 * Its firebrick casing -- if the player builds it in firebrick -- stops being decoration at the
 * same moment. The element is modelled as being *in* the chamber, so the charge is what gets hot and
 * the casing is what the heat then bleeds into — slowly, at the buffer's own contact conductance —
 * and from there into the room. A decomposer working steadily is a heat source you have to plan
 * around, and that is also the argument for putting it somewhere the ventilation has been thought
 * about: its gaseous products leave by the room, not by a belt.
 */
data class Furnace(
    override val center: TileIndex,
    override val facing: Direction,
    val setTemperature: Int = 900,
    /** How long a charge is held **at** [setTemperature] before it is handed on. */
    val dwellTicks: Int = 0,
    /**
     * How much of [dwellTicks] this charge has served.
     *
     * ⚠️ **Counts only while the charge is at temperature and the machine is running**, which is what
     * makes it a residence time rather than a delay: a decomposer starved of signal, or still ramping,
     * is not holding anything at anything. Reset when a charge is handed on, so the next one serves
     * its own full dwell.
     */
    val heldTicks: Int = 0,
    /**
     * Every species the belts may send here **pure** — see [FeedBook], which is the whole of the
     * rule. Empty means none, not any.
     *
     * ⛔ **A charge nobody chose was the tedium this exists to end.** A decomposer takes anything, so
     * a network with one on it routes *everything* through the kiln; the only way to feed it one
     * species was a tank in front of its mouth locked to that species, re-locked by hand every time
     * something new turned up. Ten things worth decomposing meant ten tanks, on a ship whose whole
     * subject is that there is no room for ten of anything.
     *
     * ⚠️ **The book is not derived from [setTemperature], though it very nearly could be.** A
     * setpoint already implies a set — every species with a single-reagent reaction whose onset it
     * clears — and deriving it would need no panel at all. It is a *list* instead because the two
     * dials answer different questions: the setpoint says how hot, and a player who wants serpentine
     * cooked and calcite left alone at 1100 K is asking something the temperature cannot express.
     * Stu, 2026-09-10.
     */
    override val whitelist: Set<Species> = emptySet(),
    /** Whether mixed ore may be sent here — see [FeedBook.ore]. */
    override val ore: Boolean = false,
    override val wiring: Wiring = Wiring.RUNNING,
) : DirectedDeckMachine, FeedBook {
    override val kind: DeckMachineKind get() = DeckMachineKind.Furnace
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    override fun withFeed(whitelist: Set<Species>, ore: Boolean): FeedBook =
        copy(whitelist = whitelist, ore = ore)

    companion object {
        /**
         * The setpoints the panel offers, coldest first.
         *
         * ⚠️ **A ladder of round numbers, not the reaction onsets themselves.** Offering the onsets
         * would look tidier and would be a trap: a reaction *at* its onset runs at [BASE_RATE] and
         * essentially nothing happens, so every setpoint on the dial would be the slowest possible
         * one for the thing it names. What the player needs is headroom above an onset, which is what
         * the gaps here are.
         *
         * ⚠️ **`ThermalDecomposerUiTest` insists every reaction in every table has a rung strictly
         * above its onset**, so a row added hotter than 2400 K is a test failure rather than a
         * reaction the dial silently cannot reach.
         *
         * The bottom rung is off, near enough: nothing in either table happens at 200 K, so it is how
         * a decomposer is told to stop without unwiring it.
         */
        val SETPOINTS: List<Int> = listOf(200, 300, 900, 1100, 1250, 1400, 1600, 1900, 2200, 2400)

        /**
         * The residence times the panel offers, in **ticks**.
         *
         * ⚠️ **Ticks, deliberately and temporarily.** This is the first thing in the game to name a
         * duration, and what a tick should be called in front of a player — seconds, cycles, anything
         * — is not decided (Stu, 2026-08-20). Naming it wrong now would put the wrong word in a save
         * file and in every screenshot; naming it `ticks` is obviously provisional, which is the
         * honest state of it.
         *
         * Zero first because zero is the default and the old behaviour: hand the charge on the moment
         * it is at temperature.
         */
        val DWELLS: List<Int> = listOf(0, 100, 250, 500, 1_000, 2_500, 5_000)
    }
}
