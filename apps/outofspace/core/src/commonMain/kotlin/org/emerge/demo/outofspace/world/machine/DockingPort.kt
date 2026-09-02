package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Acceptance
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
    /** What the player is willing to sell, and how much of it. */
    val sell: List<SellOrder> = emptyList(),
    /** Standing purchase orders, worked through in list order until the money runs out. */
    val buy: List<BuyOrder> = emptyList(),
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.DockingPort
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    /** The standing order to sell pure [species], if there is one. */
    fun selling(species: Species): SellOrder? = sell.firstOrNull { it.filter.species == species }

    /** The standing order to sell mixed ore, if there is one. */
    fun sellingOre(): SellOrder? = sell.firstOrNull { it.filter == SpeciesFilter.MIXED }

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
 * A standing order to sell [remaining] mass of whatever [filter] admits, worked down as the network
 * delivers it.
 *
 * ⛔ **Pure, or mixed, and never both** — see [SpeciesFilter.MIXED]. A per-species order is written
 * at [SpeciesFilter.MAX_PERCENT], so it takes that species and nothing blended; the ore order takes
 * everything blended and nothing pure. The two **partition** every lump between them, which is what
 * makes it safe to have both on one mouth: no lump can satisfy two orders, so attributing a delivery
 * to the order that pulled it in is never a guess.
 *
 * ⚠️ **It used to be a bare filter at any purity**, which was the same thing as "sell everything
 * with iron in it" — so a sell order for iron quietly gave away the ship's ore at a quarter rate,
 * which is precisely the trade `Market.sellValue` exists to make the player think about. The
 * `SELL` column shows [org.emerge.demo.outofspace.world.Stockpile.buildable], which counts only
 * stores holding nothing but that species, so the number and the order now agree about what will go.
 */
data class SellOrder(val filter: SpeciesFilter, val remaining: Long) {
    /** True while this order has no end — the `>>` button. */
    val isEndless: Boolean get() = remaining == ENDLESS

    companion object {
        /**
         * An order with no end: sell this, for as long as the ship keeps making it.
         *
         * ⚠️ **The same number [Acceptance.UNLIMITED] is, and deliberately the same one.** This
         * becomes that when the acceptance is built, and two constants for one idea is how they
         * drift apart.
         */
        const val ENDLESS: Long = Acceptance.UNLIMITED

        /** Sell pure [species] — [mass], or [ENDLESS]. */
        fun of(species: Species, mass: Long): SellOrder =
            SellOrder(SpeciesFilter(species, SpeciesFilter.MAX_PERCENT), mass)

        /** Sell mixed ore — [mass], or [ENDLESS]. */
        fun ore(mass: Long): SellOrder = SellOrder(SpeciesFilter.MIXED, mass)
    }
}

/**
 * A standing order to buy [remaining] mass of [species], worked down as the money allows — or
 * [ENDLESS], which is a different kind of order rather than a big number.
 *
 * ⛔ **A finite order is PUSHED and an endless one is PULLED**, and the difference is not a detail.
 * A finite order is a thing the player asked for, so it buys a packet and parks it in the output
 * store until the network comes for it. An endless order buys only what something aboard is
 * actually short of — because the port has *one* output store, and an endless order for something
 * nobody wants would fill it and starve every other order behind it for ever.
 *
 * ⚠️ **A finite order that never ended would drain the balance** the moment the player docked
 * somewhere well stocked, and the whole of the early game is a balance measured in the tens. What
 * makes the endless one safe is not a smaller appetite, it is that the network's own demand is the
 * appetite.
 */
data class BuyOrder(val species: Species, val remaining: Long) {
    /** True while this order has no end — the `<<` button. */
    val isEndless: Boolean get() = remaining == ENDLESS

    companion object {
        /** Keep the ship supplied with this, for as long as it wants any. See [SellOrder.ENDLESS]. */
        const val ENDLESS: Long = Acceptance.UNLIMITED
    }
}
