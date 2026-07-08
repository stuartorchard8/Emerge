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
    /** Fixed distinct-species capacity for a cell store (pre-sized backing, never grows in steady
     *  state); 0 = unbounded/dynamic — used by grid-leaf reservoirs, which legitimately accumulate
     *  many species. See [grow] for the Phase-2 overflow (toxicity eviction) hook. */
    private val cap: Int,
    var _bondPresence: Int = 0,  // bitmask of bonds present (max 9 bits for k=3)
    var _firstAtomMask: Int = 0, // bitmask of first atoms present (max 3 bits for k=3)
    var _lastAtomMask: Int = 0,  // bitmask of last atoms present (max 3 bits for k=3)
    private var _masksDirty: Boolean = false,  // lazily rebuild masks on first query
) {
    /** [cap] = 0 → dynamic (grid reservoirs); [cap] > 0 → fixed-capacity backing pre-sized to [cap]
     *  (cell cytoplasm/biomass), so the column is uniform and never reallocates under the cap. */
    constructor(cap: Int = 0) : this(
        if (cap > 0) IntArray(cap) else EMPTY,
        if (cap > 0) IntArray(cap) else EMPTY,
        0, cap,
    )

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

    /** Fast linear-scan count — optimal for tiny stores (≤ 8 species) where binary-search branch
      *  overhead dominates. Used by the matter-field exchange loop. */
    fun countLinear(id: Int): Int {
        if (id < 0) return 0
        for (i in 0 until n) if (ids[i] == id) return counts[i]
        return 0
    }

    /** Apply `count[id] += delta`, pruning to absent when the result is ≤ 0 — identical semantics to the
     *  old `addOrRemove(map, key, delta)`. A no-op for [id] < 0 or a non-positive delta on an absent id.
     *  Marks masks as dirty for lazy rebuild on next query. */
    fun add(id: Int, delta: Int) {
        if (id < 0 || delta == 0) return
        val i = indexOf(id)
        if (i >= 0) {
            val v = counts[i] + delta
            if (v <= 0) { removeAt(i); _masksDirty = true }
            else counts[i] = v
        } else if (delta > 0) {
            insertAt(-(i + 1), id, delta)
            _masksDirty = true
        }
    }

    /** Rebuild all presence masks from scratch — lazy, called on first query after mutations. */
    fun rebuildBondMask() {
        if (!_masksDirty) return
        _bondPresence = 0
        _firstAtomMask = 0
        _lastAtomMask = 0
        for (i in 0 until n) {
            val id = ids[i]
            _bondPresence = _bondPresence or SpeciesRegistry.bondMask(id)
            _firstAtomMask = _firstAtomMask or (1 shl SpeciesRegistry.firstAtom(id))
            _lastAtomMask = _lastAtomMask or (1 shl SpeciesRegistry.lastAtom(id))
        }
        _masksDirty = false
    }

    /** Fast check: does this store contain any species with the given bond? */
    fun hasBond(bondIdx: Int): Boolean { rebuildBondMask(); return bondIdx >= 0 && (_bondPresence and (1 shl bondIdx)) != 0 }
    /** Fast check: does this store contain any species ending with the given atom index? */
    fun hasLastAtom(atomIdx: Int): Boolean { rebuildBondMask(); return atomIdx >= 0 && atomIdx < 32 && (_lastAtomMask and (1 shl atomIdx)) != 0 }
    /** Fast check: does this store contain any species starting with the given atom index? */
    fun hasFirstAtom(atomIdx: Int): Boolean { rebuildBondMask(); return atomIdx >= 0 && atomIdx < 32 && (_firstAtomMask and (1 shl atomIdx)) != 0 }

    fun inc(id: Int, n: Int) = add(id, n)
    fun dec(id: Int) = add(id, -1)

    /** Index of the scarcest species (lowest count; ties broken by lowest id). Returns -1 if empty.
     *  Ids are stored ascending, so the first-seen minimum count is already the lowest-id tie-break.
     *  Used by the cell chem-cap eviction (the lysis-toxicity release valve). */
    fun scarcestIndex(): Int {
        if (n == 0) return -1
        var best = 0
        var bestCount = counts[0]
        for (i in 1 until n) if (counts[i] < bestCount) { bestCount = counts[i]; best = i }
        return best
    }

    fun copy(): MoleculeStore {
        if (n == 0) return MoleculeStore(cap)
        val backing = if (cap > 0) cap else n
        return MoleculeStore(ids.copyOf(backing), counts.copyOf(backing), n, cap).also { s ->
            s._bondPresence = _bondPresence
            s._firstAtomMask = _firstAtomMask
            s._lastAtomMask = _lastAtomMask
            s._masksDirty = false
        }
    }

    /** Reuse this store as a snapshot of [src], reusing this store's backing arrays (grown only if too
     *  small) so a per-tick snapshot allocates nothing once warmed. Copies [src]'s sorted/positive
     *  invariant verbatim, so it's interchangeable with [copy] as a read-only snapshot.
     *  Rebuilds masks from the copied data to ensure correctness. */
    fun copyFrom(src: MoleculeStore) {
        val m = src.n
        if (ids.size < m) { ids = IntArray(m); counts = IntArray(m) }
        src.ids.copyInto(ids, 0, 0, m)
        src.counts.copyInto(counts, 0, 0, m)
        n = m
        // Rebuild masks from scratch — copying from src could propagate stale masks
        // if src had uncommitted add() calls.
        _bondPresence = 0
        _firstAtomMask = 0
        _lastAtomMask = 0
        _masksDirty = false
        for (i in 0 until n) {
            val id = ids[i]
            _bondPresence = _bondPresence or SpeciesRegistry.bondMask(id)
            _firstAtomMask = _firstAtomMask or (1 shl SpeciesRegistry.firstAtom(id))
            _lastAtomMask = _lastAtomMask or (1 shl SpeciesRegistry.lastAtom(id))
        }
    }

    /** Boundary view for save / render / digest — a key-sorted `Map<String, Int>` identical to the old
     *  storage (counts are all > 0 by invariant, so no zero-filtering is needed). */
    fun toStringMap(): Map<String, Int> {
        if (n == 0) return emptyMap()
        val m = LinkedHashMap<String, Int>(n * 2)
        for (i in 0 until n) m[SpeciesRegistry.string(ids[i])] = counts[i]
        return m
    }

    /** Binary search for [id] in the sorted species array. Returns the index if found, or negative
     *  if absent. Public for use by [CytoMatterField.balanceBatchedOn]. */
    fun binarySearchId(id: Int): Int {
        if (id < 0) return -2
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
        // A capped cell store reaching here means it would exceed its distinct-species cap. Phase 2
        // replaces this with the toxicity mechanic: evict the scarcest species (spilling its atoms to
        // the cell's grid leaf) instead of growing. Until then we grow as a bit-identical safety net —
        // verified never hit across the golden trajectories, so the cap holds in practice.
        val newCap = if (ids.size == 0) 4 else ids.size * 2
        ids = ids.copyOf(newCap); counts = counts.copyOf(newCap)
    }

    companion object {
        private val EMPTY = IntArray(0)

        /** Build a store from a string-keyed count map (the load / lifecycle boundary). Pass [cap]
         *  > 0 for a cell store (fixed capacity); leave 0 for a dynamic grid-leaf reservoir. */
        fun of(map: Map<String, Int>, cap: Int = 0): MoleculeStore {
            if (map.isEmpty()) return MoleculeStore(cap)
            val s = MoleculeStore(cap)
            for ((species, c) in map) if (c > 0) s.add(SpeciesRegistry.id(species), c)
            return s
        }
    }
}
