package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * The ship's one connection to somebody else's economy — `PLAN_economy.md` §5.
 *
 * Three tiles square, an input port and an output port, and the only machine in the game whose
 * behaviour depends on something outside the vessel. What comes in the input port is **sold**; what
 * the player has standing orders for arrives at the output port and is **bought**.
 *
 * ### It is the rail network that decides what gets sold
 *
 * The port does not go looking for cargo. Its [sell] list becomes an
 * [org.emerge.demo.outofspace.world.Acceptance] at its input port tile, so the network only ever
 * *routes* here what the player has said to sell — nothing travels toward a place that cannot use
 * it. Put nothing on the list and the port is inert with the belts backed up behind it, which is
 * the correct behaviour and not a failure.
 *
 * ⚠️ **A sell list is several species where a locked warehouse is one**, and that needed no new
 * machinery at all: the acceptance map is keyed tile → *list*, and `Whitelist.room` admits a lump
 * that any demand at the tile wants. So one [SpeciesFilter] per order, unioned by the walk that
 * already existed. The plan expected to have to generalise `Acceptance` and it was wrong.
 *
 * ### Nothing here knows what anything is worth
 *
 * Prices belong to the counterparty ([org.emerge.demo.outofspace.world.Market]), not to the machine.
 * The port is a mouth; the market on the other side of it is what quotes.
 */
data class DockingPort(
    override val center: TileIndex,
    override val facing: Direction,
    override val wiring: Wiring = Wiring.RUNNING,
    /**
     * What the player is willing to sell, one filter per order.
     *
     * Each is the same shape a locked warehouse uses — a species and a minimum purity — because the
     * question is the same one: what may come down the branch that ends here. A player selling only
     * *pure* iron sets the purity to 100 and the network stops delivering their dirty ore to the
     * mouth that would give it away at a quarter rate.
     */
    val sell: List<SpeciesFilter> = emptyList(),
    /** Standing purchase orders, worked through in list order until the money runs out. */
    val buy: List<BuyOrder> = emptyList(),
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.DockingPort
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    /** Whether [species] may be routed here to be sold. */
    fun sells(species: Species): Boolean = sell.any { it.species == species }

    companion object {
        /**
         * How much the port's two stores hold — one [org.emerge.demo.outofspace.logistics.Capacity]
         * packet apiece plus room to accumulate a little.
         *
         * Deliberately **small**. A docking port is a doorway and not a warehouse: cargo waiting to
         * be sold should be waiting in a tank the player built, where they can see it and change
         * their mind about it, rather than inside the mouth that is about to give it away.
         */
        val CAP: Long = 2L * MACHINE_BUFFER_CAP
    }
}

/**
 * A standing order to buy [remaining] mass of [species], worked down as the money allows.
 *
 * ⛔ **A quantity, not a subscription.** An order with no end would drain the player's balance the
 * moment they docked with a well-stocked station, and the whole of the early game is a balance
 * measured in the tens. So an order is a thing that *completes*, and a completed order leaves the
 * list.
 */
data class BuyOrder(val species: Species, val remaining: Long)
