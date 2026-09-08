package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.world.Ambient
import org.emerge.demo.outofspace.world.Source
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * **The first thing aboard that makes something out of nothing, and is allowed to.**
 *
 * Increment 3 of `PLAN_power_network.md`. It drives current between its two terminals, at a strength
 * set by how much of it faces out and how bright it is out there.
 *
 * ### ⚠️ A current source with a stall, which is two numbers and not one
 *
 * A panel drives a near-constant current regardless of what the bus is doing — that is what a
 * photovoltaic cell *is* — **up to its open-circuit voltage**, past which it stops pushing. The old
 * model had the first half and not the second, so it injected for ever and overran its own overflow
 * bound in about 2500 ticks.
 *
 * Both halves are one [Source]: an EMF of [OPEN_CIRCUIT_MICROVOLTS] behind [CONDUCTANCE_PER_FACE]
 * per lit face. Into a short that delivers `G·V`; open-circuit it sits at `V` and drives nothing.
 * ⭐ So *"a panel wired to nothing does nothing"* stops being a thing anybody wrote down.
 *
 * ### ⛔ It is the one machine in this plan with an internal rule
 *
 * A panel could be 1×2 with P-type at one end and N-type at the other, with photons generating
 * carriers that a junction field separates and no rule for a "solar panel" at all. That is the right
 * shape and it is *"too much extra physics for one machine's functionality"* (Stu). Recorded as a
 * decision rather than an oversight — `PLAN_power_network.md` §4.
 *
 * ### ⛔ Exposure is `openToSpace`, and it is asked of the neighbours
 *
 * *The sun is anywhere outside the vessel* (Stu, 2026-09-06), so there is no new concept here: a
 * panel collects on each face that space reaches, which is the same question
 * [org.emerge.demo.outofspace.world.StructureMap.openToSpace] already answers for what a hot surface
 * radiates at. ⚠️ Asked of the **neighbours** rather than of the panel's own tile, because a machine
 * blocks passage and therefore faces nothing from the inside — exactly as `SolidHeat` counts its
 * radiating faces.
 *
 * Bury one inside the ship and it makes nothing. Nothing forbids that; it simply has no sky.
 */
data class SolarPanel(
    override val center: TileIndex,
    override val wiring: Wiring = Wiring.RUNNING,
) : DeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.SolarPanel
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    companion object {
        /**
         * ⛔ **The stall, and it is what the old model was missing.**
         *
         * A photovoltaic cell drives a near-constant current *up to its open-circuit voltage* and
         * then stops pushing. `SolarPanel`'s own doc said exactly that and the code did not
         * implement it, so a panel injected charge for ever: one four-face panel breached the stated
         * `MAX_CHARGE` in some 2500 ticks, after which the dissipation arithmetic overflowed. **This
         * constant is what bounds the network by construction rather than by a clamp.**
         *
         * ⭐ **Derived from the load it has to drive**, in `HEATER_POWER`'s idiom: twice the 1230 mV
         * water splits at, so a *single* panel can hold a cell above its knee with room for the drop
         * along the run between them. Below about 1.3 V a lone panel could not run a cell at all and
         * the player would be forced into series banks before they had any way to understand why.
         */
        const val OPEN_CIRCUIT_MICROVOLTS: Long = 2_460_000L

        /**
         * **What one exposed face conducts**, which with [OPEN_CIRCUIT_MICROVOLTS] is the whole of a
         * panel: a source of that EMF behind this conductance delivers `G·V` into a short and sits
         * at `V` open-circuit, which is a photovoltaic cell linearised.
         *
         * ⭐ **Derived against the wire, so that wiring is a decision.** A fully exposed panel's own
         * internal resistance is set to about **ten tiles of copper cable** (`electricalConductanceOf`
         * 117213 at `Conduit.Power`'s 2‰ fill — `NUMERIC_LIMITS.md` §13). So a short run costs
         * almost nothing and a run past ten tiles starts to bite, which is the length scale at which
         * a player can *see* that thicker or shorter or better metal helped.
         *
         * ⚠️ **Not anchored to a joule**, like [org.emerge.demo.outofspace.world.CircuitSolve.POWER_PER_UNIT]
         * — `PLAN_power_network.md` increment 5 is where a panel's output becomes worth something
         * definite, against `HEATER_POWER`. This is the lever.
         */
        const val CONDUCTANCE_PER_FACE: Long = 117_213L / 10L / 4L

        /** What one panel's source conducts this tick: its exposed faces, dimmed by the light. */
        fun conductanceAt(exposedFaces: Int, ambient: Ambient): Long =
            CONDUCTANCE_PER_FACE * exposedFaces * ambient.insolation / Ambient.FULL_SUN
    }
}
