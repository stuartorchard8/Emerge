package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Species
import kotlin.jvm.JvmInline

@JvmInline
value class TileIndex(val index: Int) {
    companion object {
        val NONE: TileIndex = TileIndex(-1)
    }
}
@JvmInline
value class TileArray(val data: IntArray) {
    operator fun get(key: TileIndex): TileIndex {
        return TileIndex(data[key.index])
    }

    operator fun set(key: TileIndex, value: TileIndex) {
        data[key.index] = value.index
    }

    fun contentEquals(other: TileArray) : Boolean = data.contentEquals(other.data)
    fun contentHashCode() : Int = data.contentHashCode()
    fun copyOf() : TileArray = TileArray(data.copyOf())
    inline val size : Int get() = data.size
}
fun TileArray(size: Int, init: (Int) -> TileIndex = { TileIndex.NONE}): TileArray {
    return TileArray(IntArray(size, { init(it).index }))
}

@JvmInline
value class MassIndex(val index: Int)
fun MassIndex(i: TileIndex, f: Fluid) =
    MassIndex(i.index*Fluid.COUNT + f.ordinal)

/**
 * Mass per tile per [Fluid], stored **densely** and iterated **sparsely**.
 *
 * ### Why a [Fluid] and not a [Species]
 *
 * Because this field is the air and the pipes, and 142 of the 165 species can never be in either.
 * Indexing it by [Species] made "no solid in the atmosphere" a doc comment that two call sites had
 * already broken; indexing it by [Fluid] makes the same statement a thing the compiler checks, and
 * incidentally makes the field seven times narrower. See [Fluid].
 *
 * ### Why both halves
 *
 * The air is a field, not a scatter: [org.emerge.demo.outofspace.world.diffuseFluid] is a stencil
 * that writes to a tile's *neighbours*, and neighbour addressing wants to stay arithmetic. That is
 * why this is not a [StuffLayer] — rows would put a dependent load in the hottest loop in the game,
 * and a freshly-zeroed delta field would allocate and clear a row per tile touched to build
 * something that ends up dense anyway. Rows are right for a few hundred tiles of rail. They are
 * wrong here.
 *
 * But the *reading* problem is the same one [StuffLayer] solved, so the answer is the same. A tile
 * of air holds about six species out of 165, and every pass that wanted to know which ones loaded
 * all 165 to find out — twenty-one cache lines to read six values. [present] is the same
 * three-words-per-tile bitmask [StuffLayer.forEachSpecies] walks, and [forEachSpecies] here has
 * deliberately the same shape, so a caller that iterates matter does not have to know or care which
 * of the two it is holding. That is what lets one chemical pass run over the air and the rails
 * without being written twice.
 *
 * ⚠️ **The bitmask is authoritative about zero-ness, not a cache.** [set] clears the bit whenever it
 * stores a zero. A stale bit would not corrupt a mass — the value behind it is still right — but an
 * empty tile would iterate as though occupied, which is a slow leak rather than a loud one.
 * [checkInvariants] asserts the two agree, for the same reason [StuffLayer.checkInvariants] does.
 *
 * ⚠️ **No longer a `value class`,** because a value class wraps exactly one value and this now has
 * two arrays. Source-compatible — [data] is still public and every call site is unchanged — but it
 * is a real object now, so do not create one per element. It is created a handful of times a tick,
 * which is what makes that acceptable.
 */
class MassArray(
    /**
     * ⚠️ **Read-only from outside.** Public because ledger checks and content comparisons want the
     * flat array, but a raw write here does not update [present] and leaves the bitmask claiming a
     * mass that is not there, or hiding one that is. Every write goes through [set] or [add].
     */
    val data: LongArray,
    private val present: LongArray,
) {

    constructor(data: LongArray) : this(data, presenceFor(data))

    operator fun get(key: MassIndex): Long {
        return data[key.index]
    }

    /**
     * ⚠️ **Divides.** [Fluid.COUNT] is a `val`, not a `const`, so recovering the tile and the
     * ordinal from a flat [MassIndex] costs two real integer divisions — and this is the setter the
     * diffusion stencil calls four times per species per tile per sub-step. Kept because it is the
     * signature every existing caller uses and the arithmetic is unavoidable once the two halves
     * have been multiplied together; but anything in a hot loop already *has* both halves and
     * should call [set] or [add] below, which never combine them in the first place.
     */
    operator fun set(key: MassIndex, value: Long) {
        data[key.index] = value
        val tile = key.index / Fluid.COUNT
        val ordinal = key.index - tile * Fluid.COUNT
        setPresence(tile, ordinal, value)
    }

    /** Mass of [f] at [tile]. The division-free twin of `get(MassIndex(tile, f))`. */
    operator fun get(tile: TileIndex, f: Fluid): Long = data[tile.index * Fluid.COUNT + f.ordinal]

    /** Stores mass of [f] at [tile], maintaining the bitmask. No division — prefer this in a loop. */
    operator fun set(tile: TileIndex, f: Fluid, value: Long) {
        data[tile.index * Fluid.COUNT + f.ordinal] = value
        setPresence(tile.index, f.ordinal, value)
    }

    /**
     * Adds [delta] to the mass of [f] at [tile] — the stencil's operation, as one call.
     *
     * `field[i] += d` on the flat index is a get *and* a set, so it pays the division in [set] and
     * re-derives an address the caller already knew. This pays neither.
     */
    fun add(tile: TileIndex, f: Fluid, delta: Long) {
        val at = tile.index * Fluid.COUNT + f.ordinal
        val value = data[at] + delta
        data[at] = value
        setPresence(tile.index, f.ordinal, value)
    }

    /** @suppress the one place a presence bit is written. */
    private fun setPresence(tile: Int, ordinal: Int, value: Long) {
        val word = tile * PRESENCE_WORDS + (ordinal ushr 6)
        val bit = 1L shl (ordinal and 63)
        if (value == 0L) present[word] = present[word] and bit.inv() else present[word] = present[word] or bit
    }

    fun copyOf() : MassArray = MassArray(data.copyOf(), present.copyOf())
    fun contentEquals(other: MassArray) : Boolean = data.contentEquals(other.data)
    fun contentHashCode() : Int = data.contentHashCode()
    val size : Int get() = data.size
    inline fun forEach(action: (Long) -> Unit) {
        for (i in data.indices) {
            action(data[i])
        }
    }

    /**
     * Every species this field holds at [tile], skipping the zeroes — the twin of
     * [StuffLayer.forEachSpecies], down to the argument order, so one loop can serve both — the
     * air's own species are a [Fluid] subset, and this is the widening back to [Species] that lets a
     * chemical pass be written once.
     */
    inline fun forEachSpecies(tile: TileIndex, action: (Species, Long) -> Unit) {
        forEachFluid(tile) { fluid, mass -> action(fluid.species, mass) }
    }

    /**
     * The same walk in this field's own terms — what a caller that stays inside the air wants, and
     * what [forEachSpecies] is written on top of. Prefer this unless the loop genuinely has to be
     * the same source as one over a [StuffLayer].
     */
    inline fun forEachFluid(tile: TileIndex, action: (Fluid, Long) -> Unit) {
        forEachPresentOrdinal(tile) { ordinal ->
            action(Fluid.ALL[ordinal], data[tile.index * Fluid.COUNT + ordinal])
        }
    }

    /** @suppress internal to [forEachSpecies]. */
    inline fun forEachPresentOrdinal(tile: TileIndex, action: (Int) -> Unit) {
        val base = tile.index * PRESENCE_WORDS
        for (word in 0 until PRESENCE_WORDS) {
            var bits = presenceWord(base + word)
            while (bits != 0L) {
                val bit = bits.countTrailingZeroBits()
                action((word shl 6) or bit)
                bits = bits and (bits - 1L)
            }
        }
    }

    /** @suppress internal to [forEachPresentOrdinal]. */
    fun presenceWord(index: Int): Long = present[index]

    /** True if [tile] holds nothing at all — three word loads, whatever the species count is. */
    fun isEmptyAt(tile: TileIndex): Boolean {
        val base = tile.index * PRESENCE_WORDS
        for (word in 0 until PRESENCE_WORDS) if (present[base + word] != 0L) return false
        return true
    }

    /** Asserts [present] agrees with [data] everywhere. Tests and debug builds; walks the whole field. */
    fun checkInvariants() {
        val tiles = data.size / Fluid.COUNT
        for (tile in 0 until tiles) {
            for (f in Fluid.ALL) {
                val mass = data[tile * Fluid.COUNT + f.ordinal]
                val bit = present[tile * PRESENCE_WORDS + (f.ordinal ushr 6)] and (1L shl (f.ordinal and 63))
                require((mass != 0L) == (bit != 0L)) { "tile $tile fluid $f: mass $mass, present ${bit != 0L}" }
            }
        }
    }

    companion object {
        /** Words of bitmask per tile, matching [StuffLayer.PRESENCE_WORDS]'s scheme. */
        val PRESENCE_WORDS: Int = (Fluid.COUNT + 63) / 64

        /**
         * Derive a bitmask from masses that already exist — for the raw-array constructor, which is
         * handed a populated array and cannot have watched it being filled.
         */
        fun presenceFor(data: LongArray): LongArray {
            val tiles = data.size / Fluid.COUNT
            val present = LongArray(tiles * PRESENCE_WORDS)
            for (i in data.indices) {
                if (data[i] == 0L) continue
                val ordinal = i % Fluid.COUNT
                present[(i / Fluid.COUNT) * PRESENCE_WORDS + (ordinal ushr 6)] =
                    present[(i / Fluid.COUNT) * PRESENCE_WORDS + (ordinal ushr 6)] or (1L shl (ordinal and 63))
            }
            return present
        }
    }
}
fun MassArray(size: Int, init: (TileIndex, Fluid) -> Long = { _,_ -> 0}): MassArray {
    return MassArray(LongArray(size*Fluid.COUNT, {init(TileIndex(it/Fluid.COUNT), Fluid.ALL[it%Fluid.COUNT])}))
}

@JvmInline
value class EnergyArray(val data: LongArray) {
    operator fun get(key: TileIndex): Long {
        return data[key.index]
    }

    operator fun set(key: TileIndex, value: Long) {
        data[key.index] = value
    }

    fun copyOf() : EnergyArray = EnergyArray(data.copyOf())
    fun contentEquals(other: EnergyArray) : Boolean = data.contentEquals(other.data)
    fun contentHashCode() : Int = data.contentHashCode()
    val size : Int get() = data.size
    inline fun forEach(action: (Long) -> Unit) {
        for (i in data.indices) {
            action(data[i])
        }
    }
}
fun EnergyArray(size: Int, init: (Int) -> Long = {0}): EnergyArray {
    return EnergyArray(LongArray(size, init))
}
