package org.emerge.demo.cyto.sim

/**
 * The chemistry primitives for the matter model (MORPHOGENESIS.md): a **molecule** is a string of
 * **atoms** over a small alphabet (e.g. `"ab"`, `"aba"`), its **bonds** are the adjacent atom pairs
 * (`"aba"` → `ab`, `ba`), and a chain of length *L* has *L−1* bonds. One bond stores one fixed energy
 * quantum and contributes one unit of biomass value, so [bondCount] is both a molecule's energy content
 * and its structural worth.
 *
 * **Polymerisation is forbidden:** a molecule may contain at most one of each ordered bond type, which
 * bounds the whole species set (with a *k*-atom alphabet, length ≤ *k²*+1). [join] enforces this.
 *
 * All operations are pure integer/string — no `Frac`, no PRNG — so the biology is trivially
 * deterministic and matter is conserved by construction (atoms are only ever moved, never minted).
 */
object Molecules {

    /** Number of bonds in [m] (= biomass value = stored energy quanta): `length − 1`, floored at 0. */
    fun bondCount(m: String): Int = if (m.length <= 1) 0 else m.length - 1

    /** True if [m] has no repeated ordered bond (adjacent pair) — the legality invariant every
     *  molecule must satisfy. A single atom (no bonds) is trivially legal. */
    fun isLegal(m: String): Boolean {
        if (m.length <= 2) return true
        val seen = HashSet<String>(m.length)
        for (i in 0 until m.length - 1) {
            if (!seen.add(m.substring(i, i + 2))) return false
        }
        return true
    }

    /** Join two whole molecules end-to-end (`x…ab…y`), forming the new bond `x.last + y.first`.
     *  Returns the product, or null if it would repeat a bond (polymerisation — forbidden). */
    fun join(x: String, y: String): String? {
        val z = x + y
        return if (isLegal(z)) z else null
    }

    /** Split [m] at its **leftmost** bond — the deterministic break used by degradation (no PRNG):
     *  `"aba"` → `("a", "ba")`. Returns null if [m] has no bond (a lone atom). */
    fun splitLeftmost(m: String): Pair<String, String>? =
        if (m.length < 2) null else m.substring(0, 1) to m.substring(1)
}
