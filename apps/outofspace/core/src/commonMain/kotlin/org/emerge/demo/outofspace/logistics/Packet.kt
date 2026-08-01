package org.emerge.demo.outofspace.logistics

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource

/**
 * Matter in transit: a discrete lump moving along a belt or through a pipe, ONI-style.
 *
 * Packets rather than flow rates because a packet is a *thing you can see*. You can watch it move,
 * point at it, and read what is in it — which is the whole reason this game is side-on. A continuous
 * throughput number would be easier to simulate and impossible to look at.
 *
 * ### Solid or fluid, not solid/liquid/gas
 * Solids ride belts; liquids and gases share pipes. Liquid and gas differ at the *ends* of a network
 * — lifting a liquid against gravity and compressing a gas are different machines with different
 * costs — but the pipe between them carries either, so the transport layer only needs the two-way
 * split. See [org.emerge.demo.outofspace.chem.Phase].
 *
 * ### Why a solid packet has a [Form] and a fluid packet does not
 * It matters enormously whether a solid is an ingot or a structural frame, so a solid packet carries
 * a whole [Resource]. A fluid is only ever "whatever was at the source" — an amalgam with no
 * identity beyond its composition — so a fluid packet is a bare [Mixture]. That asymmetry is real,
 * not an oversight.
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

    init {
        require(resource.mixture.isAllSolid) { "a solid packet cannot carry fluids: $resource" }
    }

    override fun toString(): String = "SolidPacket(${resource})"
}

/** A slug of liquid or gas in a pipe — an amalgam of whatever the source had. */
data class FluidPacket(override val contents: Mixture) : Packet {
    init {
        require(contents.isAllFluid) { "a fluid packet cannot carry solids: $contents" }
    }

    override fun toString(): String = "FluidPacket(${mass}g $contents)"
}

/**
 * How much a packet, belt slot or pipe segment can hold.
 *
 * **Everything goes through [quantityOf] rather than reading mass directly.** Today quantity *is*
 * mass in grams, but volume is the truer measure and the intent is to move to it — so this one
 * function is where that change happens, and nothing else needs to know.
 *
 * A caveat worth recording before that switch: volume works for solids and liquids and is
 * meaningless for gases. A gas has no volume of its own; it fills whatever it is in, and "a litre of
 * gas" says nothing without a pressure. So the likely end state is **volume for solids and liquids,
 * mass for gases** — the door left open here is per-phase, not global, and [quantityOf] takes the
 * whole packet rather than a mass so it can make that distinction when the time comes.
 */
object Capacity {
    /**
     * The most one packet may hold, in grams. Matches the Godot conveyor's 1 kg lumps.
     *
     * Packet size and throughput are separate dials: this caps how much rides in one lump, while
     * [Rate] decides how often lumps are produced. Both are data, not physics.
     */
    const val PACKET_GRAMS: Long = 1_000L

    /** The measure capacity is expressed in. Mass today; see the class note for why it is a function. */
    fun quantityOf(packet: Packet): Long = packet.mass

    /** Room left in a packet of the given capacity. */
    fun headroom(packet: Packet, capacity: Long = PACKET_GRAMS): Long = (capacity - quantityOf(packet)).coerceAtLeast(0L)
}

/**
 * Scales a whole-gram rate by a fraction **without losing the fraction**.
 *
 * A machine's throughput is stated per *tick*, so the clock never enters this: a miner is 250 g/tick
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
     * @param denominator what that multiplier is out of, e.g. [org.emerge.demo.outofspace.world.Signals.FULL]
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
    require(source.mixture.isAllSolid) { "not a solid: $source" }
    if (source.isEmpty || capacity <= 0L) return null to source
    val taken = source.mixture.take(capacity)
    return SolidPacket(Resource(source.form, taken)) to Resource(source.form, source.mixture - taken)
}

/** As [packSolid], for a fluid reservoir. */
fun packFluid(source: Mixture, capacity: Long = Capacity.PACKET_GRAMS): Pair<FluidPacket?, Mixture> {
    require(source.isAllFluid) { "not a fluid: $source" }
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
