package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.chem.Species

/**
 * Matter and energy for one layer of the world, stored so that it is cheap both to *hold* and to
 * *walk*. Every layer of the vessel — the deck, machine buffers, rails and their contents, pipes,
 * bridges — is one of these, addressed the same way: `layer[tile, species]`.
 *
 * ### Why rows, and not one entry per tile
 *
 * A layer holds every species at every tile it occupies, because it has to: a reaction cannot be
 * told in advance which species will turn up (carbon on a rail meeting oxygen in the air produces
 * carbon dioxide that neither side started with). But a layer occupies **very few tiles**. Rail runs
 * across a couple of hundred tiles of several thousand; machine buffers across fewer still.
 *
 * Storing `tiles × Species.COUNT` would therefore mean, at 165 species and a 96×60 grid, 7.6 MB per
 * layer of which almost all is zero — and it is copied every tick, because the reducer is pure. Rows
 * are allocated on first use instead, so a layer costs what it actually occupies. [rowOf] is the
 * indirection and it is the only thing that distinguishes this from a flat array.
 *
 * ⚠️ **A row is allocated by writing, and never by reading.** [get] on an unoccupied tile answers
 * zero without touching the store, so probing a layer cannot silently inflate it. This matters more
 * than it sounds: the natural way to write a cross-layer reaction is to ask every layer what it has
 * at a tile, and if asking allocated, one pass over the grid would make every layer dense.
 *
 * ### Why the bitmask
 *
 * Walking a row species-by-species is 165 loads to find the two or three that are non-zero, and this
 * code base has already measured that loop at roughly half a tick once — see [Mixture]'s memo
 * fields, which exist to avoid exactly it. [presentAt] keeps three words per row saying which
 * species are non-zero, so [forEachSpecies] costs what the tile actually contains.
 *
 * The bitmask is maintained by [set] and is not a cache: it is *authoritative* about zero-ness, and
 * every write clears the bit when it stores a zero. A stale bit would not corrupt a mass — the value
 * behind it is still right — but it would make an empty tile iterate as though occupied, so
 * [checkInvariants] asserts the two agree.
 */
class StuffLayer private constructor(
    val tileCount: Int,
    private var rowOf: IntArray,
    private var tileOf: IntArray,
    private var masses: LongArray,
    private var present: LongArray,
    private var energies: LongArray,
    /**
     * Total mass in each row — [massAt]'s answer, kept rather than re-derived.
     *
     * ⚠️ **Like [present], authoritative and not a cache**, and maintained by exactly the same write.
     * [checkInvariants] asserts it against the row it summarises, because a total that drifts from
     * its row is the kind of fault that reads as the ship quietly gaining or losing weight.
     *
     * It is here because `massAt` is not an inspector's convenience: [forEachVesselMass] asks it for
     * every deck tile and every conduit tile, and the tick asks that walk more than once. Summing
     * the bitmask each time made `StuffLayer.massAt` **18% of every execution sample in the game**,
     * to answer a question whose answer only changes when somebody writes.
     */
    private var totals: LongArray,
    private var rowCount: Int,
) {

    /** How many tiles this layer actually occupies. Its storage is proportional to this, not to [tileCount]. */
    val occupiedTiles: Int get() = rowCount

    /** True if this layer is present at [tile] — i.e. a row has been allocated for it. */
    fun occupies(tile: TileIndex): Boolean = tile.index >= 0 && rowOf[tile.index] != NO_ROW

    // ── Matter ───────────────────────────────────────────────────────────────

    operator fun get(tile: TileIndex, species: Species): Long {
        val row = rowOf[tile.index]
        return if (row == NO_ROW) 0L else masses[row * Species.COUNT + species.ordinal]
    }

    operator fun set(tile: TileIndex, species: Species, mass: Long) {
        // Writing a zero into a tile we do not hold is a no-op, not a reason to allocate.
        if (mass == 0L && rowOf[tile.index] == NO_ROW) return
        val row = rowFor(tile)
        val at = row * Species.COUNT + species.ordinal
        totals[row] += mass - masses[at]
        masses[at] = mass
        val word = row * PRESENCE_WORDS + (species.ordinal ushr 6)
        val bit = 1L shl (species.ordinal and 63)
        if (mass == 0L) present[word] = present[word] and bit.inv() else present[word] = present[word] or bit
    }

    fun add(tile: TileIndex, species: Species, mass: Long) {
        if (mass == 0L) return
        set(tile, species, get(tile, species) + mass)
    }

    /**
     * Every species this layer holds at [tile], in declaration order, skipping the zeroes.
     *
     * The whole reason the bitmask exists — this is the loop that a chemical pass runs over every
     * occupied tile of every layer, and it must cost what the tile holds rather than what the
     * species table could theoretically hold.
     */
    inline fun forEachSpecies(tile: TileIndex, action: (Species, Long) -> Unit) {
        forEachPresentOrdinal(tile) { ordinal -> action(Species.ALL[ordinal], massByOrdinal(tile, ordinal)) }
    }

    /** Total mass this layer holds at [tile]. One load — see [totals]. */
    fun massAt(tile: TileIndex): Long {
        val row = rowOf[tile.index]
        return if (row == NO_ROW) 0L else totals[row]
    }

    /** Everything at [tile] as a [Mixture], for inspectors and saves. Allocates — not for the hot path. */
    fun mixtureAt(tile: TileIndex): Mixture {
        val out = LongArray(Species.COUNT)
        forEachSpecies(tile) { s, m -> out[s.ordinal] = m }
        return Mixture.of(out, energyAt(tile))
    }

    /**
     * Joules per kelvin held by the matter at [tile] — what it costs to warm this much stuff by one
     * degree, derived from what is actually there.
     *
     * The **same expression** [org.emerge.demo.outofspace.world.heatCapacityAt] applies to the air,
     * and that is the point of putting a machine's matter in an array: a casing and a room now
     * answer the temperature question the same way, so a casing whose composition has been changed
     * by a reaction gets the right capacity with no code written for it. It replaces a per-kind
     * constant, which could not.
     */
    fun heatCapacityAt(tile: TileIndex): Long {
        var sum = 0L
        forEachSpecies(tile) { s, mass -> sum += mass * s.specificHeat }
        return sum / Budget.CAPACITY_DIVISOR
    }

    /**
     * **Which species the matter at [tile] mostly is** — the one that names what this tile is *made
     * of*, and so which material's thermal behaviour it has.
     *
     * ⛔ **"Mostly" is exact for anything built under the current rules and an approximation only for
     * legacy matter.** `BUILD_PURITY_PERCENT` is 100, so a casing or a length of track admits nothing
     * its bill does not name and a built tile holds exactly one species; the dominant *is* the whole
     * of it. A tile from a world saved before that admits a blend, and calling such a tile by its
     * largest constituent is both the best available answer and the one a player would give.
     *
     * ⚠️ **Allocation-free and O(what the tile holds)**, which is why it is here rather than
     * `mixtureAt(tile).dominant` — this is asked per tile per heat tick, and `mixtureAt` builds a
     * hundred and seventy longs to answer it. Same reason [heatCapacityAt] exists beside
     * `heatCapacityOf`.
     *
     * Null for a tile holding nothing, which is a construction site: it is not made of anything yet.
     */
    fun dominantAt(tile: TileIndex): Species? {
        var best: Species? = null
        var most = 0L
        forEachSpecies(tile) { species, mass ->
            if (mass > most) {
                most = mass
                best = species
            }
        }
        return best
    }

    /**
     * How hot the stuff at [tile] is, in kelvin. Matterless tiles read as ambient, for the reason
     * [org.emerge.demo.outofspace.world.gasKelvin] documents.
     */
    fun kelvinAt(tile: TileIndex): Int {
        val capacity = heatCapacityAt(tile)
        return if (capacity <= 0L) Temperature.AMBIENT_KELVIN else (energyAt(tile) / capacity).toInt()
    }

    // ── Energy ───────────────────────────────────────────────────────────────

    fun energyAt(tile: TileIndex): Long {
        val row = rowOf[tile.index]
        return if (row == NO_ROW) 0L else energies[row]
    }

    fun setEnergy(tile: TileIndex, energy: Long) {
        if (energy == 0L && rowOf[tile.index] == NO_ROW) return
        // The row is resolved into a local first, and that is not a style choice: `energies[rowFor(t)]`
        // reads the field before the call, so a rowFor that grows would write into the stale array.
        val row = rowFor(tile)
        energies[row] = energy
    }

    fun addEnergy(tile: TileIndex, energy: Long) {
        if (energy == 0L) return
        val row = rowFor(tile)
        energies[row] += energy
    }

    // ── Occupancy ────────────────────────────────────────────────────────────

    /**
     * Give this layer a row at [tile], zeroed, and answer it. Idempotent.
     *
     * Public because placing a thing is not the same act as putting something in it: a freshly built
     * hull occupies its tile and holds nothing, and it must still conduct heat. Without this, an
     * empty-but-present tile would be indistinguishable from an absent one.
     */
    fun claim(tile: TileIndex): Int = rowFor(tile)

    /**
     * Drop this layer's row at [tile] — the tile is no longer part of the layer, and whatever it held
     * is **gone**, not moved.
     *
     * Callers are expected to have taken the contents somewhere first; this is the structural half of
     * a demolition and it deliberately does not decide where matter goes, because that answer differs
     * per layer (a scrapped machine refunds, a vented tile does not).
     *
     * The freed row is filled by moving the last row into it, so rows stay contiguous and iteration
     * never has to skip holes. That reorders rows, which is why nothing outside this class may hold a
     * row number across a release.
     */
    fun release(tile: TileIndex) {
        val row = rowOf[tile.index]
        if (row == NO_ROW) return
        val last = rowCount - 1
        if (row != last) {
            masses.copyInto(masses, row * Species.COUNT, last * Species.COUNT, (last + 1) * Species.COUNT)
            present.copyInto(present, row * PRESENCE_WORDS, last * PRESENCE_WORDS, (last + 1) * PRESENCE_WORDS)
            energies[row] = energies[last]
            totals[row] = totals[last]
            tileOf[row] = tileOf[last]
            rowOf[tileOf[row]] = row
        }
        clearRow(last)
        tileOf[last] = TileIndex.NONE.index
        rowOf[tile.index] = NO_ROW
        rowCount = last
    }

    /** Every tile this layer occupies, in no particular order. The outer loop of any layer-wide pass. */
    inline fun forEachOccupiedTile(action: (TileIndex) -> Unit) {
        for (row in 0 until occupiedTiles) action(tileAtRow(row))
    }

    // ── Ledgers ──────────────────────────────────────────────────────────────

    val totalMass: Long get() {
        var sum = 0L
        for (row in 0 until rowCount) sum += totals[row]
        return sum
    }

    val totalEnergy: Long get() {
        var sum = 0L
        for (row in 0 until rowCount) sum += energies[row]
        return sum
    }

    // ── Copying ──────────────────────────────────────────────────────────────

    /**
     * A deep copy, trimmed to the rows in use.
     *
     * The tick reducer takes one of these per layer per tick, which is the operation the row layout
     * exists to make cheap: it copies what the layer occupies rather than what the grid could hold.
     */
    fun copyOf(): StuffLayer = StuffLayer(
        tileCount = tileCount,
        rowOf = rowOf.copyOf(),
        tileOf = tileOf.copyOf(),
        masses = masses.copyOf(),
        present = present.copyOf(),
        energies = energies.copyOf(),
        totals = totals.copyOf(),
        rowCount = rowCount,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StuffLayer || other.tileCount != tileCount || other.rowCount != rowCount) return false
        // Compared through tiles, not rows: two layers holding the same thing at the same places are
        // equal even if the rows were allocated in a different order, which release() makes routine.
        for (row in 0 until rowCount) {
            val tile = TileIndex(tileOf[row])
            if (!other.occupies(tile)) return false
            if (other.energyAt(tile) != energies[row]) return false
            for (s in Species.ALL) if (other[tile, s] != this[tile, s]) return false
        }
        return true
    }

    override fun hashCode(): Int {
        // Order-independent, for the same reason equals() is tile-keyed.
        var acc = tileCount * 31 + rowCount
        for (row in 0 until rowCount) {
            var h = tileOf[row] * 31 + energies[row].hashCode()
            for (i in 0 until PRESENCE_WORDS) h = h * 31 + present[row * PRESENCE_WORDS + i].hashCode()
            acc = acc xor h
        }
        return acc
    }

    /** Asserts the bitmask agrees with the masses, and the tile/row maps agree with each other. */
    fun checkInvariants() {
        for (row in 0 until rowCount) {
            val tile = tileOf[row]
            require(tile in 0 until tileCount) { "row $row maps to out-of-range tile $tile" }
            require(rowOf[tile] == row) { "tile $tile maps to row ${rowOf[tile]}, not $row" }
            var sum = 0L
            for (s in Species.ALL) {
                val mass = masses[row * Species.COUNT + s.ordinal]
                sum += mass
                val bit = present[row * PRESENCE_WORDS + (s.ordinal ushr 6)] and (1L shl (s.ordinal and 63))
                require((mass != 0L) == (bit != 0L)) { "tile $tile species $s: mass $mass, present ${bit != 0L}" }
            }
            require(totals[row] == sum) { "tile $tile totals ${totals[row]}, row sums to $sum" }
        }
        for (tile in 0 until tileCount) {
            val row = rowOf[tile]
            require(row == NO_ROW || (row < rowCount && tileOf[row] == tile)) { "tile $tile points at stale row $row" }
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** @suppress internal to [forEachOccupiedTile]. */
    fun tileAtRow(row: Int): TileIndex = TileIndex(tileOf[row])

    /** @suppress internal to [forEachSpecies]. */
    fun massByOrdinal(tile: TileIndex, ordinal: Int): Long {
        val row = rowOf[tile.index]
        return if (row == NO_ROW) 0L else masses[row * Species.COUNT + ordinal]
    }

    /** @suppress internal to [forEachSpecies]. */
    inline fun forEachPresentOrdinal(tile: TileIndex, action: (Int) -> Unit) {
        val base = presenceBase(tile)
        if (base < 0) return
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
    fun presenceBase(tile: TileIndex): Int {
        val row = rowOf[tile.index]
        return if (row == NO_ROW) -1 else row * PRESENCE_WORDS
    }

    /** @suppress internal to [forEachPresentOrdinal]. */
    fun presenceWord(index: Int): Long = present[index]

    private fun rowFor(tile: TileIndex): Int {
        val existing = rowOf[tile.index]
        if (existing != NO_ROW) return existing
        if (rowCount == energies.size) grow()
        val row = rowCount++
        clearRow(row)
        tileOf[row] = tile.index
        rowOf[tile.index] = row
        return row
    }

    private fun clearRow(row: Int) {
        masses.fill(0L, row * Species.COUNT, (row + 1) * Species.COUNT)
        present.fill(0L, row * PRESENCE_WORDS, (row + 1) * PRESENCE_WORDS)
        energies[row] = 0L
        totals[row] = 0L
    }

    private fun grow() {
        val next = if (energies.isEmpty()) INITIAL_ROWS else energies.size * 2
        masses = masses.copyOf(next * Species.COUNT)
        present = present.copyOf(next * PRESENCE_WORDS)
        energies = energies.copyOf(next)
        totals = totals.copyOf(next)
        tileOf = tileOf.copyOf(next).also { it.fill(TileIndex.NONE.index, rowCount, next) }
    }

    companion object {
        /** No row allocated for this tile — the layer is not present there at all. */
        const val NO_ROW: Int = -1

        /** Words of bitmask per row: one bit per species, rounded up to whole 64-bit words. */
        val PRESENCE_WORDS: Int = (Species.COUNT + 63) / 64

        /** Rows allocated on first write. Small: most layers occupy a handful of tiles and never grow. */
        private const val INITIAL_ROWS: Int = 16

        /** A layer occupying nothing, over a grid of [tileCount] tiles. */
        fun empty(tileCount: Int): StuffLayer = StuffLayer(
            tileCount = tileCount,
            rowOf = IntArray(tileCount) { NO_ROW },
            tileOf = IntArray(0),
            masses = LongArray(0),
            present = LongArray(0),
            energies = LongArray(0),
            totals = LongArray(0),
            rowCount = 0,
        )
    }
}
