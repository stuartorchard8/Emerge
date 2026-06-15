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

    /** id of a molecule string, or -1 if it isn't a legal species of this alphabet. */
    fun id(molecule: String): Int = idOf[molecule] ?: -1
    fun string(id: Int): String = species[id]
    fun bondCount(id: Int): Int = bondCountById[id]
    fun atomCount(id: Int): Int = atomCountById[id]

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
                sb.append(atoms[nxt]); dfs(nxt, used or bit); sb.deleteCharAt(sb.length - 1)
            }
        }
        for (a in 0 until k) { sb.append(atoms[a]); dfs(a, 0); sb.deleteCharAt(sb.length - 1) }
        return out
    }
}
