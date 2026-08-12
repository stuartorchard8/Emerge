package org.emerge.demo.outofspace.logistics

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.world.Budget

/**
 * Matter in transit: discrete lumps on belts/pipes. Solid=Resource (Form matters), fluid=Mixture (source-dependent).
 * Two categories: solid (belts) and fluid (pipes carry both).
 */
sealed interface Packet {
    /** What is in it, species by species. */
    val contents: Mixture

    /** Total mass in grams. Also the quantity capacity is measured in today — see [Capacity]. */
    val mass: Long get() = contents.total

    val isEmpty: Boolean get() = contents.isEmpty
}

/** A discrete item on a belt: an ingot, a lump of ore, a finished component. */
data class SolidPacket(val resource: Resource) : Packet {
    override val contents: Mixture get() = resource.mixture
    val form: Form get() = resource.form

    override fun toString(): String = "SolidPacket(${resource})"
}

/** A slug of liquid or gas in a pipe — an amalgam of whatever the source had. */
data class FluidPacket(override val contents: Mixture) : Packet {
    override fun toString(): String = "FluidPacket(${mass}g $contents)"
}

/**
 * Capacity: max grams per packet/slot/segment. quantityOf() wraps mass→volume transition (solids/liquids: volume; gases: mass).
 */
object Capacity {
    /**
     * Max grams per packet: a tonne. Separate from Rate (throughput).
     *
     * A lump on a belt used to be a kilogram, back when a tile of rock was three. Solids are at
     * their real densities now — a tile of ore is about four tonnes — so a belt that moved kilograms
     * would take an hour to shift one boulder. Everything measured in grams moved up by a thousand
     * with it; the masses themselves moved by rather more (see [org.emerge.demo.outofspace.world.Material]),
     * so the refinery runs about a third slower per rock than it used to, in exchange for numbers
     * that stay round.
     *
     * **Derivation**: one tonne. This is the quantum the whole logistics layer is built on — every
     * buffer below is a small whole number of these — so it is stated in [Budget]'s units and
     * everything else refers back to it rather than restating a mass of its own.
     */
    val PACKET_GRAMS: Long = 1L * Budget.TONNE

    /** The measure capacity is expressed in. Mass today; see the class note for why it is a function. */
    fun quantityOf(packet: Packet): Long = packet.mass

    /** Room left in a packet of the given capacity. */
    fun headroom(packet: Packet, capacity: Long = PACKET_GRAMS): Long = (capacity - quantityOf(packet)).coerceAtLeast(0L)
}

/**
 * Scales a whole-gram rate by a fraction **without losing the fraction**.
 *
 * A machine's throughput is stated per *tick*, so the clock never enters this: an extractor is 250 g/tick
 * and that is a whole number by construction. What is not whole is a **throttle**. A processor run
 * at 45% of 125 g/tick owes 56.25 g, and there is no honest integer for that. Rounding it away every
 * tick either leaks mass or silently runs the machine at the wrong speed — over an hour, either is
 * a lot. So the fraction is carried in state: each tick adds the scaled numerator to a carry, takes
 * out whole grams, and keeps the remainder for next time. Over any run of ticks the delivered total
 * is exact to within a gram.
 *
 * The carry is a plain `Long` living in whatever machine owns the rate, so it serialises with the
 * snapshot and survives a save like everything else. At full activation the numerator divides
 * exactly and the carry simply stays at zero.
 */
object Rate {
    /**
     * Given `numerator / denominator` grams and the accumulated fractional [carry], returns
     * `(gramsThisTick, newCarry)`.
     *
     * @param numerator grams-per-tick already multiplied by the throttle, e.g. `125 * activation`
     * @param denominator what that multiplier is out of, e.g. [org.emerge.demo.outofspace.world.SignalField.FULL]
     * @param carry leftover from the previous tick; start at 0
     */
    fun tick(numerator: Long, denominator: Int, carry: Long): Pair<Long, Long> {
        require(numerator >= 0L) { "negative rate: $numerator" }
        require(denominator > 0) { "denominator must be positive: $denominator" }
        val accumulated = carry + numerator
        return (accumulated / denominator) to (accumulated % denominator)
    }
}

/**
 * Fills a packet from [source] up to [capacity], returning the packet and what is left behind.
 *
 * The split is proportional across species ([Mixture.take]), so a shovelful of ore is a *sample* of
 * the pile rather than the good bits skimmed off the top. That is what stops a belt from acting as
 * an accidental free refinery.
 */
fun packSolid(source: Resource, capacity: Long = Capacity.PACKET_GRAMS): Pair<SolidPacket?, Resource> {
    if (source.isEmpty || capacity <= 0L) return null to source
    val taken = source.mixture.take(capacity)
    return SolidPacket(Resource(source.form, taken)) to Resource(source.form, source.mixture - taken)
}

/** As [packSolid], for a fluid reservoir. */
fun packFluid(source: Mixture, capacity: Long = Capacity.PACKET_GRAMS): Pair<FluidPacket?, Mixture> {
    if (source.isEmpty || capacity <= 0L) return null to source
    val taken = source.take(capacity)
    return FluidPacket(taken) to (source - taken)
}

/**
 * Pours [incoming] into [existing] up to [capacity], returning the merged packet and any overflow.
 *
 * Solids only merge when they are the same [Form] — you cannot pour an ingot into a structural
 * frame. Fluids always merge, because that is what fluids do; the result is an amalgam of both.
 * Returns null when the two cannot combine at all, leaving the caller to decide (usually: the belt
 * backs up).
 */
fun mergeInto(existing: Packet, incoming: Packet, capacity: Long = Capacity.PACKET_GRAMS): MergeResult? {
    val room = Capacity.headroom(existing, capacity)
    return when {
        existing is SolidPacket && incoming is SolidPacket -> {
            if (existing.form != incoming.form) return null
            val accepted = incoming.contents.take(room)
            MergeResult(
                merged = SolidPacket(Resource(existing.form, existing.contents + accepted)),
                rejected = if (accepted == incoming.contents) null
                else SolidPacket(Resource(incoming.form, incoming.contents - accepted)),
            )
        }
        existing is FluidPacket && incoming is FluidPacket -> {
            val accepted = incoming.contents.take(room)
            MergeResult(
                merged = FluidPacket(existing.contents + accepted),
                rejected = if (accepted == incoming.contents) null else FluidPacket(incoming.contents - accepted),
            )
        }
        // A solid and a fluid share no network, so this is a wiring mistake rather than a full pipe.
        else -> null
    }
}

/** The outcome of [mergeInto]: what the destination now holds, and what would not fit. */
data class MergeResult(val merged: Packet, val rejected: Packet?)
