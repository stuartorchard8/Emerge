package org.emerge.demo.cyto.sim

/**
 * The dense-chemistry replacement for a cell's / grid-cell's `Map<String, Int>` of molecule counts: a
 * compact **id→count** store backed by two parallel `IntArray`s held **sorted ascending by [SpeciesRegistry]
 * id**. Because an id *is* a molecule's lexicographic rank, "iterate in id order" == "iterate in lex order",
 * so the deterministic lex tie-breaks the biology relies on (lex-smallest molecule containing a bond, the
 * leftmost-split degradation target, …) become a simple **forward scan** — no per-tick string hashing, no
 * `Integer` boxing, and a copy is two `IntArray` copies (bounded by the genome's metabolic reach, ≲ a few
 * dozen species) instead of a whole `HashMap`.
 *
 * Invariant: `ids[0 until n]` is strictly ascending and every `counts[i] > 0` (a species at count 0 is
 * absent), exactly mirroring the old `addOrRemove`-pruned map — so the [toStringMap] boundary view, and
 * thus the golden digest, is byte-for-byte what the map produced.
 */
class MoleculeStore private constructor(
    private var ids: IntArray,
    private var counts: IntArray,
    private var n: Int,
) {
    constructor() : this(EMPTY, EMPTY, 0)

    val size: Int get() = n
    fun isEmpty(): Boolean = n == 0

    /** id of the i-th present species (ascending == lex order). */
    fun idAt(i: Int): Int = ids[i]
    /** count of the i-th present species (always > 0). */
    fun countAt(i: Int): Int = counts[i]

    /** Count of species [id] (0 if absent or [id] < 0). */
    fun count(id: Int): Int {
        if (id < 0) return 0
        val i = indexOf(id)
        return if (i >= 0) counts[i] else 0
    }

    /** Apply `count[id] += delta`, pruning to absent when the result is ≤ 0 — identical semantics to the
     *  old `addOrRemove(map, key, delta)`. A no-op for [id] < 0 or a non-positive delta on an absent id. */
    fun add(id: Int, delta: Int) {
        if (id < 0 || delta == 0) return
        val i = indexOf(id)
        if (i >= 0) {
            val v = counts[i] + delta
            if (v <= 0) removeAt(i) else counts[i] = v
        } else if (delta > 0) {
            insertAt(-(i + 1), id, delta)
        }
    }

    fun inc(id: Int, n: Int) = add(id, n)
    fun dec(id: Int) = add(id, -1)

    fun copy(): MoleculeStore =
        if (n == 0) MoleculeStore() else MoleculeStore(ids.copyOf(n), counts.copyOf(n), n)

    /** Boundary view for save / render / digest — a key-sorted `Map<String, Int>` identical to the old
     *  storage (counts are all > 0 by invariant, so no zero-filtering is needed). */
    fun toStringMap(): Map<String, Int> {
        if (n == 0) return emptyMap()
        val m = LinkedHashMap<String, Int>(n * 2)
        for (i in 0 until n) m[SpeciesRegistry.string(ids[i])] = counts[i]
        return m
    }

    /** Binary search: index of [id], or `-(insertionPoint) - 1` if absent (java/kotlin convention). */
    private fun indexOf(id: Int): Int {
        var lo = 0; var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = ids[mid]
            when {
                v < id -> lo = mid + 1
                v > id -> hi = mid - 1
                else -> return mid
            }
        }
        return -(lo + 1)
    }

    private fun insertAt(i: Int, id: Int, count: Int) {
        if (n == ids.size) grow()
        for (j in n downTo i + 1) { ids[j] = ids[j - 1]; counts[j] = counts[j - 1] }
        ids[i] = id; counts[i] = count; n++
    }

    private fun removeAt(i: Int) {
        for (j in i until n - 1) { ids[j] = ids[j + 1]; counts[j] = counts[j + 1] }
        n--
    }

    private fun grow() {
        val cap = if (ids.size == 0) 4 else ids.size * 2
        ids = ids.copyOf(cap); counts = counts.copyOf(cap)
    }

    companion object {
        private val EMPTY = IntArray(0)

        /** Build a store from a string-keyed count map (the load / lifecycle boundary). */
        fun of(map: Map<String, Int>): MoleculeStore {
            if (map.isEmpty()) return MoleculeStore()
            val s = MoleculeStore()
            for ((species, c) in map) if (c > 0) s.add(SpeciesRegistry.id(species), c)
            return s
        }
    }
}
