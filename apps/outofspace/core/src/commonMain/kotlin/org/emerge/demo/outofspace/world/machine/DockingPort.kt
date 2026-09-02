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
 * ### An order is PERMISSION, and the network is what acts on it

⛔ **Nothing is bought because the player asked for it.** A positive figure lets the rail network
*pull* that species through the mouth, and the purchase happens at the moment a packet is drawn onto
the track — so a docking port never holds bought matter, has no output store, and cannot spend the
player's money on something nothing aboard wanted. Pressing `<` five times does not buy five packets;
it permits five, and the ship buys them as it uses them.

⚠️ **Which makes the buttons a choice rather than an action**, and that is the point: the player says
what they will allow and in which direction, and the network decides when. What the counter's middle
column reports is therefore an expectation and not a receipt.

### It is the rail network that decides what gets sold
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
     * **One signed number per species**: what the player has permitted, and in which direction.
     *
     * Negative sells, positive buys, absent is nothing, and [ENDLESS] at either sign is "no bound".
     * A species is on one side of the counter or the other and never both — which is not a rule
     * enforced on top of the representation, it is what having one number *is*. Selling a hundred
     * kilograms while permitting the purchase of five hundred is not a state anybody means to be in;
     * pressing sell against a buy permission simply reduces it, which is the same arithmetic and
     * needs no special case.
     *
     * ⛔ **A buy figure is PERMISSION, not a purchase.** Nothing is bought because the player asked
     * for it — see the class note. It is bought when the network comes for it, and this is the bound
     * on how much of that the player has agreed to.
     */
    val orders: Map<Species, Long> = emptyMap(),
    /**
     * The same number for **mixed ore**, which has no species to key on.
     *
     * ⚠️ **Never positive.** A station's unworked heap is not for sale — it is what it has not got
     * round to separating — so the ore counter has a sell side and no buy side, and the controls the
     * player is offered say so by not existing.
     */
    val ore: Long = 0L,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.DockingPort
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    /** What is permitted for [species], signed. Zero when nothing is. */
    fun permitted(species: Species): Long = orders[species] ?: 0L

    /** How much of [species] may still be **sold**, or [ENDLESS]. Zero when the player is buying it. */
    fun selling(species: Species): Long = sellingOf(permitted(species))

    /** How much of [species] may still be **bought**, or [ENDLESS]. Zero when the player is selling it. */
    fun buying(species: Species): Long = if (permitted(species) > 0L) permitted(species) else 0L

    /** How much mixed ore may still be sold, or [ENDLESS]. */
    fun sellingOre(): Long = sellingOf(ore)

    /**
     * This port after one press of `>` or `<` on [species].
     *
     * [by] is signed the way the book is, so `>` is a step down the one axis and `<` a step up it —
     * see [stepped] for what a press against an unbounded permission does.
     */
    fun nudged(species: Species, by: Long): DockingPort =
        withOrder(species, stepped(permitted(species), by))

    /** The same press on the ore row, which has no buy side. */
    fun nudgedOre(by: Long): DockingPort = copy(ore = stepped(ore, by))

    /** This port after `>>` or `<<` on [species]: on, or off if it was already that. */
    fun unbounded(species: Species, to: Long): DockingPort =
        withOrder(species, if (isUnbounded(permitted(species))) 0L else to)

    /** `>>` on the ore row. */
    fun unboundedOre(): DockingPort = copy(ore = if (isUnbounded(ore)) 0L else -ENDLESS)

    /** This port with [species] set to [value] — dropped from the map when it comes to nothing. */
    fun withOrder(species: Species, value: Long): DockingPort = copy(
        orders = if (value == 0L) orders - species else orders + (species to value),
    )

    /**
     * What a button press does to one signed figure.
     *
     * ⛔ **From an unbounded permission, ANY press stands down to nothing.** Not to a packet the
     * other way, and not to the other unbounded state either. Flipping straight from "buy me all of
     * this" to "sell me out of it" is a large thing to do by accident, and the player who meant it
     * is one press from saying so again — so `<<` then `>` is a stop, not a reversal.
     *
     * ⚠️ Otherwise it is plain arithmetic on one axis, which is what makes the two directions one
     * control rather than two that have to be kept from disagreeing.
     */
    private fun stepped(now: Long, by: Long): Long = if (isUnbounded(now)) 0L else now + by

    private fun isUnbounded(value: Long): Boolean = value == ENDLESS || value == -ENDLESS

    /** The unsigned sell figure hiding in a signed one. */
    private fun sellingOf(value: Long): Long = when {
        value >= 0L -> 0L
        value == -ENDLESS -> ENDLESS
        else -> -value
    }

    companion object {
        /**
         * No bound: sell it for as long as the ship keeps making it, or buy it for as long as the
         * ship keeps wanting it. Carried as `+ENDLESS` and `-ENDLESS`.
         *
         * ⚠️ **The same number [Acceptance.UNLIMITED] is, and deliberately the same one.** A sell
         * permission becomes an `Acceptance`'s appetite directly, and two constants for one idea is
         * how they drift apart.
         */
        const val ENDLESS: Long = Acceptance.UNLIMITED

        /**
         * How much the port's **input** store holds — one packet apiece plus room to accumulate.
         *
         * ⛔ **There is no output store any more.** A purchase is minted onto the track at the moment
         * the network draws it, so a docking port never holds bought matter at all; see the class
         * note. Cargo waiting to be *sold* still waits here, briefly, because the belt hands it over
         * rather than the mouth reaching for it.
         */
        val CAP: Long = 2L * MACHINE_BUFFER_CAP
    }
}
