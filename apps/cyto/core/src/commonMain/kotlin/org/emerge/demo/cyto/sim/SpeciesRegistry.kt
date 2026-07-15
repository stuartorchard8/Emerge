package org.emerge.demo.cyto.sim

/**
 * Interns every legal molecule over the element alphabet ([SEED_MONOMERS]) to a small int **id**, assigned
 * in **lexicographic-rank order** — so "the lex-smallest molecule present" (the deterministic tie-break the
 * biology uses in FormBond / BreakBond / degradation) becomes "the lowest id present", a forward scan.
 *
 * The species set is bounded by the k-element cap (k=3 → ≤1884 molecules; see /tmp/cyto_species.py), so it
 * is enumerated **once** at load and molecule operations become id lookups instead of string manipulation.
 * This is the foundation for the dense numeric chemistry (Phase B / #4) that replaces the per-cell
 * `Map<String,Int>` — eliminating the per-tick hashing + allocation. **Not yet wired into the sim**; its
 * contract (every op matches [Molecules]) is pinned by `SpeciesRegistryTest`.
 */
object SpeciesRegistry {
    /** The element alphabet — the single-atom seeded monomers (e.g. a,b,c at k=3). */
    private val atoms: List<Char> = CytoSeed.SEED_MONOMERS.map { it[0] }

    /** Every legal molecule over [atoms] (no repeated ordered bond), lexicographically sorted: id = index. */
    val species: List<String> = enumerate().sorted()
    val size: Int get() = species.size

    private val idOf: HashMap<String, Int> =
        HashMap<String, Int>(species.size * 2).apply { species.forEachIndexed { i, s -> put(s, i) } }
    private val bondCountById = IntArray(species.size) { Molecules.bondCount(species[it]) }
    private val atomCountById = IntArray(species.size) { species[it].length }
    /** Leftmost split per id: [2*id] = lead-monomer id, [2*id+1] = rest id; (-1,-1) for a lone atom. */
    private val splitLeft = IntArray(species.size * 2).also { arr ->
        for (id in species.indices) {
            val p = Molecules.splitLeftmost(species[id])
            arr[2 * id] = if (p == null) -1 else idOf.getValue(p.first)
            arr[2 * id + 1] = if (p == null) -1 else idOf.getValue(p.second)
        }
    }

    // ── hot-path predicates (precomputed; the biology scans stores in ascending-id == lex order and tests
    //    these instead of manipulating strings) ────────────────────────────────────────────────────────
    /** Number of atom types in the alphabet (k). Bonds are indexed `firstAtom·k + secondAtom` (0..k²−1). */
    private val k: Int = atoms.size
    private val atomIdx: HashMap<Char, Int> = HashMap<Char, Int>(k * 2).apply { atoms.forEachIndexed { i, c -> put(c, i) } }
    private val firstAtomById = IntArray(species.size) { atomIdx.getValue(species[it][0]) }
    private val lastAtomById = IntArray(species.size) { atomIdx.getValue(species[it].last()) }
    /** Per id, a bitmask of the bonds (`firstAtom·k+secondAtom`) the molecule contains. */
    private val bondMaskById = IntArray(species.size) { id ->
        var m = 0
        val s = species[id]
        for (i in 0 until s.length - 1) m = m or (1 shl (atomIdx.getValue(s[i]) * k + atomIdx.getValue(s[i + 1])))
        m
    }
    /** Break tables indexed `[id·k² + bondIdx]`: the (left,right) fragment ids of breaking [id] at the
     *  **first** occurrence of a bond, or -1 when the bond is absent. Precomputes [Molecules.breakAt]. */
    private val breakLeftById = IntArray(species.size * k * k) { -1 }
    private val breakRightById = IntArray(species.size * k * k).also { right ->
        for (id in species.indices) for (a in 0 until k) for (b in 0 until k) {
            val p = Molecules.breakAt(species[id], "${atoms[a]}${atoms[b]}") ?: continue
            val at = id * k * k + a * k + b
            breakLeftById[at] = idOf.getValue(p.first)
            right[at] = idOf.getValue(p.second)
        }
    }

    /** Per id, the atom multiset as `[3·id + channel]` counts over the r/g/b element alphabet — the same
     *  tally a `for (ch in string(id))` scan produces, precomputed. The matter-field rasteriser colours one
     *  leaf per quad-tree leaf (~127k of them per frame on a refined world), so doing this by walking the
     *  molecule string there dominated the render thread. Channels not in the alphabet stay 0, which is what
     *  a colourless token should render as (white is the caller's zero-atom convention). */
    private val rgbById = IntArray(species.size * 3).also { arr ->
        for (id in species.indices) for (ch in species[id]) when (ch) {
            'r' -> arr[3 * id]++
            'g' -> arr[3 * id + 1]++
            'b' -> arr[3 * id + 2]++
        }
    }

    /** Count of [channel] (0=r, 1=g, 2=b) atoms in species [id]. See [rgbById]. */
    fun atomsInChannel(id: Int, channel: Int): Int = rgbById[3 * id + channel]

    /** id of a molecule string, or -1 if it isn't a legal species of this alphabet. */
    fun id(molecule: String): Int = idOf[molecule] ?: -1
    fun string(id: Int): String = species[id]
    fun bondCount(id: Int): Int = bondCountById[id]
    fun atomCount(id: Int): Int = atomCountById[id]

    /** Atom-index of [c] (0..k−1), or -1 if not in the alphabet. */
    fun atomIndexOf(c: Char): Int = atomIdx[c] ?: -1
    /** Bond-index (`firstAtom·k+secondAtom`, 0..k²−1) of a 2-atom bond string, or -1 if malformed. */
    fun bondIndexOf(bond: String): Int {
        if (bond.length != 2) return -1
        val a = atomIdx[bond[0]] ?: return -1
        val b = atomIdx[bond[1]] ?: return -1
        return a * k + b
    }
    /** Atom-index of [id]'s first / last atom (for FormBond endpoint matching). */
    fun firstAtom(id: Int): Int = firstAtomById[id]
    fun lastAtom(id: Int): Int = lastAtomById[id]
    /** Bitmask of the bonds molecule [id] contains (bit `bondIdx` set per [bondIndexOf]). */
    fun bondMask(id: Int): Int = bondMaskById[id]
    /** Does molecule [id] contain bond [bondIdx] (an adjacent atom pair)? */
    fun containsBond(id: Int, bondIdx: Int): Boolean = bondIdx >= 0 && (bondMaskById[id] ushr bondIdx) and 1 == 1
    /** Left/right fragment id of breaking [id] at the first occurrence of [bondIdx], or -1 if absent. */
    fun breakLeft(id: Int, bondIdx: Int): Int = if (bondIdx < 0) -1 else breakLeftById[id * k * k + bondIdx]
    fun breakRight(id: Int, bondIdx: Int): Int = if (bondIdx < 0) -1 else breakRightById[id * k * k + bondIdx]

    /** Lead-monomer id from a leftmost split (`abc`→`a`), or -1 for a lone atom. */
    fun splitLeftMono(id: Int): Int = splitLeft[2 * id]
    /** Remainder id from a leftmost split (`abc`→`bc`), or -1 for a lone atom. */
    fun splitLeftRest(id: Int): Int = splitLeft[2 * id + 1]

    private val joinCache = HashMap<Long, Int>()
    /** Join molecules [a]+[b] end-to-end; product id, or -1 if it would repeat a bond (forbidden). */
    fun join(a: Int, b: Int): Int {
        val key = a.toLong() * size + b
        joinCache[key]?.let { return it }
        val r = Molecules.join(species[a], species[b])?.let { idOf[it] ?: -1 } ?: -1
        joinCache[key] = r
        return r
    }

    /** Break [id] at the first occurrence of [bond] (a 2-atom pair) → (leftId, rightId), or null if absent. */
    fun breakAt(id: Int, bond: String): IntArray? {
        val p = Molecules.breakAt(species[id], bond) ?: return null
        return intArrayOf(idOf.getValue(p.first), idOf.getValue(p.second))
    }

    /** All legal molecules over [atoms]: trails (no repeated ordered bond) in the complete directed graph
     *  with self-loops, enumerated by DFS with a bitmask over the k² possible bonds. */
    private fun enumerate(): List<String> {
        val k = atoms.size
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun dfs(last: Int, used: Int) {
            out.add(sb.toString())
            for (nxt in 0 until k) {
                val bit = 1 shl (last * k + nxt)
                if (used and bit != 0) continue
                sb.append(atoms[nxt]); dfs(nxt, used or bit); sb.deleteAt(sb.length - 1)
            }
        }
        for (a in 0 until k) { sb.append(atoms[a]); dfs(a, 0); sb.deleteAt(sb.length - 1) }
        return out
    }
}
