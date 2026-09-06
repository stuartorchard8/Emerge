package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.logistics.Capacity.PACKET_MASS
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * **The machine that takes water apart**: `2 H₂O → 2 H₂ + O₂`, in at the back and out of two
 * different faces.
 *
 * The thing a chemical rocket is waiting for. Hydrogen is the only propellant worth a high exhaust
 * velocity and oxygen is the only oxidiser to burn it with, and until this existed the ship's routes
 * to either were roasting hematite at 1730 K, growing algae, or pumping a room — all of which work
 * and none of which is a bulk supply. See `PLAN_chemical_rockets.md`.
 *
 * ### ⛔ Why it performs its reaction instead of hosting one
 *
 * This is the first machine in the game that genuinely **does** chemistry, and it is a deliberate
 * departure from the principle [Furnace] is built on — *machines control conditions, chemistry does
 * the work*. Two things make electrolysis the exception, and either alone would be enough:
 *
 *  - **It has no onset temperature.** It is driven by electricity at ambient, so there is no
 *    condition a machine could create that would make a `REACTIONS` row fire here and nowhere else.
 *    Any onset low enough to *be* electrolysis would split every drop of water on the ship. The
 *    thermal alternative — real thermolysis, around 2500 K — is a different reaction telling a
 *    different story, and one the [Furnace] would already be the right machine for.
 *  - **Its products recombine instantly.** `2 H₂ + O₂ → 2 H₂O` lights at 773 K and a store reacts
 *    with itself, so hydrogen and oxygen made in one place burn straight back to water. The rate
 *    model is one-directional rather than an equilibrium solver, so what came out would be an
 *    artefact of which row ran first.
 *
 * Both dissolve because the split happens here and the two gases land in **separate stores** that
 * never meet. That is what the second output port is really for.
 *
 * ### What it is not
 *
 * ⚠️ **No charge, no progress, no dwell.** A [Concentrator] pulls a lump in, works it for
 * `ticksPerAction` and hands out two finished packets; this fills two hoppers at a rate, which is
 * the [Pump]'s shape rather than the concentrator's. There is nothing to watch happening to a
 * charge, so there is no `Inside` store to hold one and no state on the machine at all beyond where
 * it stands and what it is wired to. ⚠️ The 1:8 mass split is why: a charge handed out as two
 * finished packets would make one of them a runt every time, and packets never merge.
 */
data class Electrolyzer(
    override val center: TileIndex,
    override val facing: Direction,
    override val wiring: Wiring = Wiring.RUNNING,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Electrolyzer
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    companion object {
        /**
         * **How many electron-passes one unit of charge buys** — where the wire pays for the
         * chemistry.
         *
         * ⛔ **Derived from what it is for**, in [HEATER_POWER]'s idiom: *one fully exposed solar
         * panel should run a cell at about a tenth of its ceiling.* A panel makes four faces of
         * [org.emerge.demo.outofspace.world.machine.SolarPanel.CHARGE_PER_FACE] a tick; a pass of
         * water electrolysis moves four electrons and consumes 36 of formula mass; and a tenth of
         * [MASS_PER_TICK] is what a plant filling tanks between burns looks like.
         *
         * ⚠️ **So a bank of ten panels runs a cell flat out**, and one panel runs it slowly rather
         * than not at all — see the rate rule in `OutofspaceSim.split`. That is the difference
         * between a threshold a player can plan around and a cliff they fall off.
         */
        const val ELECTRONS_PER_CHARGE: Long = 1_000L

        /**
         * **The voltage this machine puts across its charge**, and the only thing it decides.
         *
         * ⛔ **This replaced `ENTHALPY_PER_KG`**, which stated what breaking a kilogram of water
         * costs and which *nothing read*. The cost is not what gates a cell — the **potential** is,
         * and it gates by competition rather than by price: at 1500 mV a charge of water clears
         * water's own 1230 and splits, and a charge with copper in it would plate the copper first
         * at 890. See `chem/Cell.kt`.
         *
         * ⛔ **Superseded, and kept only as the fallback for a cell with no cable under it.** As of
         * `PLAN_power_network.md` increment 2 the applied voltage is *read off the bus* — a cell
         * standing on a run answers whatever the run is sitting at, and a vessel short of panels is
         * a vessel whose cell does not clear water's 1230 mV. This is what a cell with no run under
         * it uses instead, so that a machine placed without wiring still does something rather than
         * silently doing nothing.
         *
         * ⚠️ **That fallback is a kindness, not a model**, and it is the first thing to delete when
         * power is billed for — `PLAN_power_network.md` increment 3.
         */
        const val UNWIRED_MILLIVOLTS: Int = 1500

        /**
         * **The dial.** How much water it takes apart in a tick, at full activation: one belt-load.
         *
         * ⛔ **Chosen, not derived — and knowingly overpowered.** It used to be [HEATER_POWER]'s
         * worth of the enthalpy of splitting water, about 27 g a tick, and that number was defensible on paper and
         * indefensible in play: the light half is a **ninth** of the mass, and because the machine
         * ships whole packets the hydrogen mouth opened roughly once every **nine minutes**. A mouth
         * that slow does not read as a plant filling tanks between burns, it reads as a machine that
         * is broken. So this is [PACKET_MASS] — the quantum everything else in the logistics layer is
         * a small whole number of — and the hydrogen mouth opens every nine ticks instead.
         *
         * ⚠️ **Expected to come back down.** Implied draw is 484 kJ per 36 g × [PACKET_MASS], some
         * **1.3 GJ a tick — around 3700 furnace elements** — against a motor's 32 kg/s appetite that
         * a *third* of one of these now feeds. Nothing charges for it, so nothing stops it; the lever
         * when `PLAN_power_network.md` lands is this constant and that arithmetic, not a mechanism.
         *
         * ⚠️ **The rate is not the throughput.** The split stalls whenever *either* output store is
         * at [BUFFER_CAP], so what the machine actually does is set by whichever of its two belts
         * drains slower — and one of them is carrying eight times the mass of the other.
         */
        val MASS_PER_TICK: Long = PACKET_MASS

        /**
         * How much of either gas it banks before it stops splitting.
         *
         * [MACHINE_OUTPUT_CAP], the size every other output hopper is, and the stall is stated
         * against **each** store rather than their sum: the oxygen side fills eight times faster
         * than the hydrogen side, so a shared cap would be a machine that stops because one of its
         * two mouths is backed up while the other stands empty.
         */
        val BUFFER_CAP: Long = MACHINE_OUTPUT_CAP
    }
}
