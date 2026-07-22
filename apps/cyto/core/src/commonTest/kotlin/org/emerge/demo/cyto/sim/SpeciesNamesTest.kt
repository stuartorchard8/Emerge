package org.emerge.demo.cyto.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpeciesNamesTest {

    @Test fun atomsGetFlavourNames() {
        assertEquals("Redogen", SpeciesNames.name("r"))
        assertEquals("Greenum", SpeciesNames.name("g"))
        assertEquals("Blueon", SpeciesNames.name("b"))
    }

    @Test fun theConfusablePairsReadDistinctly() {
        // The whole point: rg vs gr (same atoms) and b vs bb (prefix) must not collide.
        assertNotEquals(SpeciesNames.name("rg"), SpeciesNames.name("gr"))
        assertNotEquals(SpeciesNames.name("b"), SpeciesNames.name("bb"))
        assertEquals("Redreen", SpeciesNames.name("rg"))
        assertEquals("Greed", SpeciesNames.name("gr"))
        assertEquals("Blub", SpeciesNames.name("bb"))
    }

    @Test fun allNineDuomersAreDistinct() {
        val atoms = listOf("r", "g", "b")
        val names = atoms.flatMap { x -> atoms.map { y -> SpeciesNames.name(x + y) } }
        assertEquals(9, names.toSet().size, "every duomer name must be unique: $names")
    }

    @Test fun longerMoleculesFallBackToTheRawToken() {
        assertEquals("rgg", SpeciesNames.name("rgg"))
        assertEquals("rbgb", SpeciesNames.name("rbgb"))
    }

    @Test fun aGenomeAliasOverridesTheBuiltInName() {
        val aliases = mapOf("rg" to "fuel", "bb" to "origineon")
        assertEquals("fuel", SpeciesNames.name("rg", aliases))
        assertEquals("origineon", SpeciesNames.name("bb", aliases))
        // A species with no alias still gets its built-in name.
        assertEquals("Greed", SpeciesNames.name("gr", aliases))
        // Empty alias map behaves like no aliases.
        assertEquals("Redreen", SpeciesNames.name("rg", emptyMap()))
    }

    @Test fun emptyTokenReadsAsNone() {
        assertEquals("(NONE)", SpeciesNames.name(""))
    }

    @Test fun colorLeansTowardTheDominantChannel() {
        val red = SpeciesNames.color("r")
        val green = SpeciesNames.color("g")
        val blue = SpeciesNames.color("b")
        fun r(c: Long) = (c ushr 24) and 0xFF
        fun g(c: Long) = (c ushr 16) and 0xFF
        fun b(c: Long) = (c ushr 8) and 0xFF
        assertTrue(r(red) > g(red) && r(red) > b(red), "red atom leans red")
        assertTrue(g(green) > r(green) && g(green) > b(green), "green atom leans green")
        assertTrue(b(blue) > r(blue) && b(blue) > g(blue), "blue atom leans blue")
        // Full opacity, always.
        assertEquals(0xFFL, red and 0xFF)
    }
}
