package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.world.Ambient
import org.emerge.demo.outofspace.world.PowerFlow
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * **The first thing aboard that makes something out of nothing, and is allowed to.**
 *
 * Increment 1b of `PLAN_power_network.md`. It pushes charge onto the [org.emerge.demo.outofspace.world.Conduit.Power]
 * run under it, in proportion to how much of it faces out and how bright it is out there.
 *
 * ### ⚠️ A current source, not a power source
 *
 * A panel pushes charge at a rate set by the light, near enough regardless of what the bus is doing
 * — that is what a photovoltaic cell *is*, up to its open-circuit voltage. So the constant here is a
 * **current** and the power it delivers is `I × V`, rising as the bus charges. That falls out rather
 * than being stated, and it is why a panel wired to nothing does nothing useful: with nowhere for
 * the charge to go the run simply sits at potential.
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
         * **The dial**: charge onto the bus per exposed face per tick, at [Ambient.FULL_SUN].
         *
         * ⛔ **Chosen, and stated against the one thing that bounds it.** A panel is the only source
         * of charge in the game, so what it pushes is what sets the scale of everything on the wire
         * — and the ceiling is [PowerFlow.MAX_CHARGE], past which the dissipation arithmetic
         * overflows. This is a ten-thousandth of that budget, so a single face takes about ten
         * thousand ticks to fill a network on its own and four faces take a quarter of that. There is
         * room for a great many panels before anything is near the edge.
         *
         * ⚠️ **Not yet anchored to a joule.** Charge and the game's energy unit meet at
         * [PowerFlow.storedEnergy], and what a panel is *worth* only becomes a real question when
         * something bills for it — `PLAN_power_network.md` increment 3. Sizing it against the
         * overflow bound is what can be done honestly today; sizing it against a furnace element is
         * what increment 3 will do instead, and this constant is the lever.
         */
        const val CHARGE_PER_FACE: Long = PowerFlow.MAX_CHARGE / 10_000L

        /** What one panel pushes this tick: its exposed faces, dimmed by how far out the vessel is. */
        fun outputAt(exposedFaces: Int, ambient: Ambient): Long =
            CHARGE_PER_FACE * exposedFaces * ambient.insolation / Ambient.FULL_SUN
    }
}
