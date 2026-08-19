package org.emerge.demo.outofspace.logistics

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.num.Budget

/**
 * Matter in transit: discrete lumps on belts and pipes. Two categories: solid (belts) and fluid
 * (pipes carry both). Both are simply a [Mixture] — what a thing has been *made into* is not
 * modelled, so a lump of ore and a compacted casing plate are told apart by what they are made of.
 */
sealed interface Packet {
    /** What is in it, species by species. */
    val contents: Mixture

    /** Total mass in mass. Also the quantity capacity is measured in today — see [Capacity]. */
    val mass: Long get() = contents.total

    val isEmpty: Boolean get() = contents.isEmpty
}

/** A discrete item on a belt: a lump of ore, a compacted plate, a finished component. */
data class SolidPacket(override val contents: Mixture) : Packet {
    override fun toString(): String = "SolidPacket(${mass}g $contents)"
}

/** A slug of liquid or gas in a pipe — an amalgam of whatever the source had. */
data class FluidPacket(override val contents: Mixture) : Packet {
    override fun toString(): String = "FluidPacket(${mass}g $contents)"
}

/**
 * Capacity: max mass per packet/slot/segment. quantityOf() wraps mass→volume transition (solids/liquids: volume; gases: mass).
 */
object Capacity {
    /**
     * Max mass per packet: **100 kg**, a lump one person could not lift. Separate from [Rate],
     * which is throughput.
     *
     * Solids are at their real densities, so a tile of ore is about four tonnes — roughly forty of
     * these. A boulder is therefore a few dozen belt-loads rather than a handful, which is what
     * makes a mining line read as a continuous stream instead of a trickle of enormous lumps.
     *
     * **Derivation**: `100 × KILOGRAM`. This is the quantum the whole logistics layer is built on —
     * every buffer is a small whole number of these — so it is stated in [Budget]'s units and
     * everything else refers back to it rather than restating a mass of its own.
     */
    const val PACKET_MASS: Long = 100L * Budget.KILOGRAM

    /** The measure capacity is expressed in. Mass today; see the class note for why it is a function. */
    fun quantityOf(packet: Packet): Long = packet.mass

    /** Room left in a packet of the given capacity. */
    fun headroom(packet: Packet, capacity: Long = PACKET_MASS): Long = (capacity - quantityOf(packet)).coerceAtLeast(0L)
}

/**
 * Scales a whole-gram rate by a fraction **without losing the fraction**.
 *
 * A machine's throughput is stated per *tick*, so the clock never enters this: an extractor is
 * 250 kg/tick and that is a whole number by construction. What is not whole is a **throttle**. A
 * processor run at 45% of 125 kg/tick owes 56.25 kg, and there is no honest integer for that. Rounding it away every
 * tick either leaks mass or silently runs the machine at the wrong speed — over an hour, either is
 * a lot. So the fraction is carried in state: each tick adds the scaled numerator to a carry, takes
 * out whole mass, and keeps the remainder for next time. Over any run of ticks the delivered total
 * is exact to within a gram.
 *
 * The carry is a plain `Long` living in whatever machine owns the rate, so it serialises with the
 * snapshot and survives a save like everything else. At full activation the numerator divides
 * exactly and the carry simply stays at zero.
 */
object Rate {
    /**
     * Given `numerator / denominator` mass and the accumulated fractional [carry], returns
     * `(massThisTick, newCarry)`.
     *
     * @param numerator mass-per-tick already multiplied by the throttle, e.g. `125 * activation`
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
fun packSolid(source: Mixture, capacity: Long = Capacity.PACKET_MASS): Pair<SolidPacket?, Mixture> {
    if (source.isEmpty || capacity <= 0L) return null to source
    val taken = source.take(capacity)
    return SolidPacket(taken) to source - taken
}

/** As [packSolid], for a fluid reservoir. */
fun packFluid(source: Mixture, capacity: Long = Capacity.PACKET_MASS): Pair<FluidPacket?, Mixture> {
    if (source.isEmpty || capacity <= 0L) return null to source
    val taken = source.take(capacity)
    return FluidPacket(taken) to (source - taken)
}

/**
 * Pours [incoming] into [existing] up to [capacity], returning the merged packet and any overflow.
 *
 * Two solids always combine, and so do two fluids — a solid and a fluid never do, because they
 * share no network and offering one to the other is a wiring mistake rather than a full pipe.
 * Returns null in that case, leaving the caller to decide (usually: the belt backs up).
 */
fun mergeInto(existing: Packet, incoming: Packet, capacity: Long = Capacity.PACKET_MASS): MergeResult? {
    val room = Capacity.headroom(existing, capacity)
    return when {
        existing is SolidPacket && incoming is SolidPacket -> {
            val accepted = incoming.contents.take(room)
            MergeResult(
                merged = SolidPacket(existing.contents + accepted),
                rejected = if (accepted == incoming.contents) null
                else SolidPacket(incoming.contents - accepted),
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
