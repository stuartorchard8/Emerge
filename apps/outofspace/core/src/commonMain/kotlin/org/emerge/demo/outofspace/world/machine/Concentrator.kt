package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * Mineral concentrator: **pure** metal out the facing side, tailings out clockwise-side.
 *
 * One machine, one pass, one species. It draws [efficiencyPermille] of the charge's dominant
 * species out whole and banks it until it has a 100 kg packet of it — see
 * [org.emerge.demo.outofspace.chem.process] for the draw and `Work.refine` for the bank. Fed the
 * standard ore body a pure packet of iron costs about **3.25 packets of ore**, against the 32 the
 * old ladder charged.
 *
 * ⛔ **It used to be a chain**, and the chain was academic. Purity climbed **41 → 65 → 86 → 94 → 97
 * → 100** across five machines, half the mass lost at each, and nothing downstream had any use for
 * the intermediate steps: `BUILD_PURITY_PERCENT` is 100, an electrolyzer takes pure water and
 * nothing else, and a sell order is priced per species. So the interesting decision was never how to
 * *reach* pure — it was what to do with the concentrate once you had it, and five machines of
 * plumbing stood in front of that decision. `reference_oos_processor_purity_ladder` records what
 * the ladder cost to make terminate at all.
 *
 * ⚠️ **What is lost is now yield rather than quality.** The tailings still carry the share the
 * machine missed plus every other species, so reprocessing them is worth doing — and because the
 * draw always takes whatever is *dominant*, a tailings loop works its way down through the species
 * in order of abundance without being told to. Each pass takes a quarter of what is left, so the
 * loop converges rather than plateauing.
 */
data class Concentrator(
    override val center: TileIndex,
    override val facing: Direction,
    val carry: Long = 0L,
    /**
     * Minimum number of machine ticks it takes to convert inProgress resources to product and tailings.
     */
    val ticksPerAction: Int = 16,
    val progress: Int = 0,
    /**
     * Machine quality, and since the draw replaced the ladder it is a plain **recovery rate**: the
     * share of the charge's dominant species this machine picks out. 750 means three quarters of the
     * iron in a charge of ore leaves as pure iron and the last quarter stays in the tailings.
     *
     * ⛔ **It is no longer capped by the ore's own purity**, because a draw cannot invent purity the
     * way a fractional split could — see [org.emerge.demo.outofspace.chem.process]. Bad ore costs
     * yield now, not quality.
     *
     * ⚠️ **The old justification for this number is void and the number has not been re-derived.**
     * It used to be chosen to space a five-stage purity ladder evenly, an argument about a mechanism
     * that no longer exists. What it buys today is the size of the tailings stream and therefore how
     * much reprocessing is worth building — which is a balance question, and an open one.
     */
    val efficiencyPermille: Int = 750,
    override val wiring: Wiring = Wiring.RUNNING,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Concentrator
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    companion object {
        const val CHARGE_MASS = Capacity.PACKET_MASS*2
    }
}
