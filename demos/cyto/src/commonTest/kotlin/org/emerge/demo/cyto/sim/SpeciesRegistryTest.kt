package org.emerge.demo.cyto.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the [SpeciesRegistry] contract: every interned-id operation reproduces exactly what the
 * string-based [Molecules] chemistry would compute, the id space is the full legal enumeration, and ids
 * are assigned in lexicographic order (so "lowest id present" == "lex-smallest molecule present", the
 * tie-break the biology relies on). If the registry ever drifts from [Molecules], the dense-chemistry
 * path would silently diverge from the goldens — these tests catch that at the unit level.
 */
class SpeciesRegistryTest {

    /** Every legal molecule over the k=3 alphabet is enumerated exactly once → 1884 species. */
    @Test
    fun enumeratesEveryLegalSpeciesOnce() {
        assertEquals(1884, SpeciesRegistry.size, "k=3 species count")
        assertEquals(SpeciesRegistry.size, SpeciesRegistry.species.toSet().size, "no duplicates")
        for (s in SpeciesRegistry.species) {
            assertTrue(Molecules.isLegal(s), "enumerated species must be legal: $s")
        }
    }

    /** ids are dense [0,size) and assigned in lexicographic-rank order. */
    @Test
    fun idsAreLexSortedAndDense() {
        val sorted = SpeciesRegistry.species.sorted()
        assertEquals(sorted, SpeciesRegistry.species, "species list must already be lex-sorted")
        SpeciesRegistry.species.forEachIndexed { i, s ->
            assertEquals(i, SpeciesRegistry.id(s), "id round-trip for $s")
            assertEquals(s, SpeciesRegistry.string(i), "string round-trip for id $i")
        }
    }

    /** A non-species (illegal molecule, or one over a foreign alphabet) maps to -1. */
    @Test
    fun unknownMoleculeIsMinusOne() {
        assertEquals(-1, SpeciesRegistry.id("abab"), "repeated bond ab is not legal")
        assertEquals(-1, SpeciesRegistry.id("z"), "foreign atom")
        assertEquals(-1, SpeciesRegistry.id(""), "empty")
    }

    /** bondCount / atomCount match [Molecules] for every species. */
    @Test
    fun bondAndAtomCountsMatchMolecules() {
        for (id in 0 until SpeciesRegistry.size) {
            val s = SpeciesRegistry.string(id)
            assertEquals(Molecules.bondCount(s), SpeciesRegistry.bondCount(id), "bondCount $s")
            assertEquals(s.length, SpeciesRegistry.atomCount(id), "atomCount $s")
        }
    }

    /** Leftmost-split ids match [Molecules.splitLeftmost] (mono + rest), and lone atoms split to (-1,-1). */
    @Test
    fun leftmostSplitMatchesMolecules() {
        for (id in 0 until SpeciesRegistry.size) {
            val s = SpeciesRegistry.string(id)
            val p = Molecules.splitLeftmost(s)
            if (p == null) {
                assertEquals(-1, SpeciesRegistry.splitLeftMono(id), "lone atom mono $s")
                assertEquals(-1, SpeciesRegistry.splitLeftRest(id), "lone atom rest $s")
            } else {
                assertEquals(SpeciesRegistry.id(p.first), SpeciesRegistry.splitLeftMono(id), "mono $s")
                assertEquals(SpeciesRegistry.id(p.second), SpeciesRegistry.splitLeftRest(id), "rest $s")
            }
        }
    }

    /** join(a,b) reproduces [Molecules.join]: same product id, or -1 when the join would repeat a bond
     *  (forbidden polymerisation). Crosses every species against the small molecules (atoms + 2-mers) —
     *  enough to exercise both legal joins and bond-repeat rejection without caching 1884² pairs. */
    @Test
    fun joinMatchesMolecules() {
        val smalls = (0 until SpeciesRegistry.size).filter { SpeciesRegistry.bondCount(it) <= 1 }
        for (a in 0 until SpeciesRegistry.size) {
            for (b in smalls) {
                assertJoin(a, b)
                assertJoin(b, a)
            }
        }
    }

    private fun assertJoin(a: Int, b: Int) {
        val expected = Molecules.join(SpeciesRegistry.string(a), SpeciesRegistry.string(b))
            ?.let { SpeciesRegistry.id(it) } ?: -1
        assertEquals(expected, SpeciesRegistry.join(a, b),
            "join ${SpeciesRegistry.string(a)}+${SpeciesRegistry.string(b)}")
    }

    /** breakAt(id, bond) reproduces [Molecules.breakAt] for every species × every directed atom pair. */
    @Test
    fun breakAtMatchesMolecules() {
        val atoms = CytoSeed.SEED_MONOMERS.map { it[0] }
        val bonds = atoms.flatMap { x -> atoms.map { y -> "$x$y" } }
        for (id in 0 until SpeciesRegistry.size) {
            val s = SpeciesRegistry.string(id)
            for (bond in bonds) {
                val expected = Molecules.breakAt(s, bond)
                val actual = SpeciesRegistry.breakAt(id, bond)
                if (expected == null) {
                    assertNull(actual, "breakAt $s @ $bond")
                } else {
                    assertEquals(SpeciesRegistry.id(expected.first), actual!![0], "breakAt left $s @ $bond")
                    assertEquals(SpeciesRegistry.id(expected.second), actual[1], "breakAt right $s @ $bond")
                }
            }
        }
    }
}
