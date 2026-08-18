package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.reach
import org.emerge.demo.outofspace.world.capacityPerTile
import org.emerge.demo.outofspace.world.diameter

/**
 * Thermal energy stored **per tile** of something that occupies several.
 *
 * All that is left of the machine hierarchy. Every kind of building keeps its heat in the deck layer
 * now, addressed by the tile it is on; the one thing still holding a `TileEnergy` is a
 * [org.emerge.demo.outofspace.world.RigidBody], which is not on the grid at all and so has no layer
 * to put it in.
 */
class TileEnergy private constructor(private val perTile: LongArray) {

    val size: Int get() = perTile.size

    operator fun get(index: Int): Long = perTile[index]

    /** Every tile's energy added up — what the ledgers and the scrap value want. */
    val total: Long get() {
        var sum = 0L
        for (j in perTile) sum += j
        return sum
    }

    /** The same energy with [index] replaced. Returns a new value; nothing here mutates. */
    fun with(index: Int, energy: Long): TileEnergy {
        val next = perTile.copyOf()
        next[index] = energy
        return TileEnergy(next)
    }

    /**
     * [added] energy spread evenly across every tile.
     *
     * Even, because waste heat is made by the *work* a machine does and the work happens throughout
     * it — there is no more reason for a smelter's furnace losses to appear in one corner than
     * another. The remainder goes to the first tiles rather than being dropped, so that repeatedly
     * adding less than one joule per tile still warms the machine instead of vanishing.
     */
    fun plusEnergySpread(added: Long): TileEnergy {
        if (perTile.isEmpty()) return this
        val next = perTile.copyOf()
        val each = added / perTile.size
        var remainder = added - each * perTile.size
        for (i in next.indices) {
            var share = each
            if (remainder > 0) { share++; remainder-- } else if (remainder < 0) { share--; remainder++ }
            next[i] += share
        }
        return TileEnergy(next)
    }

    /**
     * The same energy with tile [index] gone — one shorter, and the rest untouched.
     *
     * For a body losing a tile, which a machine cannot do and a rock does every time an extractor
     * bites it. **Conservation is structural**: what leaves is exactly `this[index]` and what stays
     * is exactly everything else, so the two add back to the original with no rounding involved at
     * all. The share-of-the-whole arithmetic this replaces had to be written as a remainder of a
     * single truncating divide to achieve the same thing.
     */
    fun dropping(index: Int): TileEnergy {
        val next = LongArray(perTile.size - 1)
        for (i in next.indices) next[i] = if (i < index) perTile[i] else perTile[i + 1]
        return TileEnergy(next)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is TileEnergy && perTile.contentEquals(other.perTile))

    override fun hashCode(): Int = perTile.contentHashCode()

    override fun toString(): String = perTile.joinToString(prefix = "TileEnergy[", postfix = "]")

    companion object {
        /** [count] tiles all holding [each]. */
        fun uniform(count: Int, each: Long): TileEnergy = TileEnergy(LongArray(count) { each })

        /** Takes ownership of [values] — callers must not keep a reference to it. */
        fun of(values: LongArray): TileEnergy = TileEnergy(values)
    }
}

/**
 * How many tiles' worth of material a machine is made of.
 *
 * The footprint, squared — except for a bridge, which claims no floor space at all and is
 * nonetheless three tiles of metal spanning three tiles of room. Deliberately **not** derived from
 * the clipped [org.emerge.demo.outofspace.world.coveredTiles] of wherever it stands: what a thing is made of does not change when it
 * is built near the edge of the grid, and a capacity that varied with position would make an
 * identical machine hold a different amount of heat depending on where you put it.
 */
/**
 * Machine input buffers hold this much before they stop accepting.
 *
 * **Derivation**: four tonnes — **32 ticks** of a 125 kg/tick machine's throughput, so a machine can
 * run for a good few seconds on a full buffer while its feed is interrupted.
 *
 * ⚠️ Sized in *ticks of throughput*, not in belt-loads, and that distinction has already bitten
 * once. Written as `4 × PACKET_MASS` it silently shrank tenfold when the belt-load went from a
 * tonne to 100 kg, leaving every machine with two ticks of buffer — enough that an extractor
 * stalled before its throttle could make any difference, which is a behaviour change nobody asked
 * for. A buffer's job is to decouple a machine from its supply *for a while*; the unit of "a while"
 * is ticks.
 */
val MACHINE_BUFFER_CAP = 4L * Budget.TONNE

/**
 * And output buffers hold this much before the machine stops *running*.
 *
 * Without this a processor whose waste side is blocked keeps working and hoards its tailings
 * indefinitely — tens of tonnes inside one tile, invisibly. Capping it makes a blocked output
 * back up into the input and then up the belt behind it, which is the same way every other blockage
 * in the game behaves: visibly, and starting at the thing that is actually stuck.
 *
 * **Derivation**: the same four tonnes as [MACHINE_BUFFER_CAP], and deliberately equal to it — a
 * machine that can hoard more output than input would drain its feed before it stalled.
 */
val MACHINE_OUTPUT_CAP = 4L * Budget.TONNE
