package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.kJPerMolAt
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio
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
         * What it costs to break a kilogram of water into its elements, in [Budget]'s energy unit.
         *
         * **484 kJ per 36 g** — two moles of water — which is the exact reverse of the combustion
         * row that puts it back together (`UnifiedReaction.kt`, corrected in `6f1ff780`). Quoted the
         * way that row is quoted, against the same formula mass, so the two cannot drift apart:
         * if somebody re-prices burning hydrogen and forgets this, the numbers stop being each
         * other's mirror and a test can say so.
         *
         * ⚠️ **Nothing pays it.** See [org.emerge.demo.outofspace.chem.electrolyse] — the energy is
         * minted, as a furnace's element mints its own, and the ledger stays closed because chemical
         * potential is not a pool the game tracks. It is here because it is what sets [MASS_PER_TICK],
         * not because anything is debited.
         */
        val ENTHALPY_PER_KG: Long = 484L * kJPerMolAt(2L * Species.Water.molarMass)

        /**
         * **The dial.** How much water it takes apart in a tick, at full activation.
         *
         * ⛔ **Derived, not chosen: it is [HEATER_POWER]'s worth of [ENTHALPY_PER_KG].** This machine
         * is given the same element a [Furnace] has, because there is no reason yet for it to have a
         * different one — and a rate derived from an already-calibrated number is one that moves
         * correctly when that number does, rather than one that has to be remembered.
         *
         * What falls out is about **27 g a tick — 1.7 kg/s**, and that is the number to argue with
         * if this machine feels wrong. A motor at full throttle eats 32 kg/s, so **one engine is
         * nineteen electrolyzers**, which is deliberately not a ratio anybody builds. A chemical
         * rocket is meant to burn from tanks that filled slowly while the ship was doing something
         * else; a plant that could feed an engine live would make propellant a tap rather than a
         * thing you stockpile.
         *
         * ⚠️ That is the same shape [Pump.MASS_PER_TICK] chose — *"a gas supply is a plant a player
         * builds, not a fitting they bolt on"* — and the same argument as the fifty pumps it takes
         * to saturate one belt.
         */
        val MASS_PER_TICK: Long = scaledRatio(HEATER_POWER, ENTHALPY_PER_KG, Budget.KILOGRAM)

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
