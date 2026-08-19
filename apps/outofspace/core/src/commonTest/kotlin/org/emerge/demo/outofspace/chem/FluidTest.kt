package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.world.TileIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [Fluid] subset and the presence bitmask that rides with it — increment 0a of
 * `PLAN_ambient_chemistry.md`.
 *
 * These are cheap by construction: the largest field here is one grid of tiles, and nothing steps a
 * simulation. A test that needed ticks to prove a bitmask agrees with an array would be testing the
 * wrong thing.
 */
class FluidTest {

    // ── The subset ───────────────────────────────────────────────────────────

    @Test
    fun everyFluidRoundTripsThroughItsSpecies() {
        for (f in Fluid.ALL) {
            assertEquals(f, f.species.fluid, "${f.species} did not map back to $f")
            assertTrue(f.species.isFluid, "${f.species} is a Fluid but does not read as one")
        }
    }

    @Test
    fun theSubsetIsAProperSubset() {
        assertTrue(Fluid.COUNT < Species.COUNT, "a subset that is not smaller buys nothing")
        // The whole point of the increment: the air's stride shrinks by something worth having.
        assertTrue(Species.COUNT / Fluid.COUNT >= 5, "expected at least a 5x narrowing, got ${Species.COUNT}/${Fluid.COUNT}")
    }

    @Test
    fun aSolidIsNotAFluid() {
        // The species the plan is named after: serpentine has never been a gas, and until this
        // enum existed nothing in the codebase could say so.
        assertNull(Species.Serpentine.fluid)
        assertNull(Species.Forsterite.fluid)
        assertNull(Species.Iron.fluid)
        assertTrue(!Species.Hematite.isFluid)
    }

    @Test
    fun everyVolatileIsAFluid() {
        // The fifteen under THE VOLATILES were always meant to be here; this is the guard that a
        // new ice added to Species does not quietly fail to reach the atmosphere.
        for (s in listOf(
            Species.Water, Species.CarbonDioxide, Species.Ammonia, Species.Methane,
            Species.CarbonMonoxide, Species.HydrogenSulfide, Species.SulfurDioxide,
            Species.Nitrogen, Species.Hydrogen, Species.Oxygen, Species.Argon,
            Species.Helium, Species.Neon, Species.Krypton, Species.Xenon,
        )) assertNotNull(s.fluid, "$s is a volatile but not a Fluid")
    }

    @Test
    fun noSpeciesIsClaimedTwice() {
        val seen = HashSet<Species>()
        for (f in Fluid.ALL) assertTrue(seen.add(f.species), "${f.species} appears twice in Fluid")
    }

    // ── The bitmask ──────────────────────────────────────────────────────────

    @Test
    fun presenceFollowsMass() {
        val field = MassArray(4)
        val tile = TileIndex(2)

        assertTrue(field.isEmptyAt(tile))

        field[tile, Species.Oxygen] = 500L
        field[tile, Species.Nitrogen] = 1500L
        assertTrue(!field.isEmptyAt(tile))
        field.checkInvariants()

        val found = HashMap<Species, Long>()
        field.forEachSpecies(tile) { s, m -> found[s] = m }
        assertEquals(mapOf(Species.Oxygen to 500L, Species.Nitrogen to 1500L), found)
    }

    @Test
    fun writingZeroClearsTheBit() {
        val field = MassArray(2)
        val tile = TileIndex(1)
        field[tile, Species.Oxygen] = 100L
        field[tile, Species.Oxygen] = 0L

        assertTrue(field.isEmptyAt(tile), "a tile emptied by writing zero still reads as occupied")
        var seen = 0
        field.forEachSpecies(tile) { _, _ -> seen++ }
        assertEquals(0, seen, "an emptied tile still iterates")
        field.checkInvariants()
    }

    @Test
    fun addBackToZeroClearsTheBit() {
        // The stencil's shape: mass leaves a tile entirely across several faces. If the bit
        // survived, an evacuated cell would iterate forever — the slow leak, not the loud one.
        val field = MassArray(2)
        val tile = TileIndex(0)
        field.add(tile, Species.Water, 300L)
        field.add(tile, Species.Water, -200L)
        field.add(tile, Species.Water, -100L)

        assertEquals(0L, field[tile, Species.Water])
        assertTrue(field.isEmptyAt(tile))
        field.checkInvariants()
    }

    @Test
    fun theFlatIndexSetterMaintainsItToo() {
        // MassIndex(tile, s) is what every existing caller uses; it must not be the path that
        // leaves the mask stale.
        val field = MassArray(3)
        val tile = TileIndex(2)
        field[MassIndex(tile, Species.Argon)] = 42L
        field.checkInvariants()

        var seen: Pair<Species, Long>? = null
        field.forEachSpecies(tile) { s, m -> seen = s to m }
        assertEquals(Species.Argon to 42L, seen)

        field[MassIndex(tile, Species.Argon)] = 0L
        assertTrue(field.isEmptyAt(tile))
        field.checkInvariants()
    }

    @Test
    fun theRawConstructorDerivesTheMask() {
        // Handed a populated array it never watched being filled, MassArray has to work the mask
        // out for itself — Save and copyMass both arrive this way.
        val raw = LongArray(2 * Species.COUNT)
        raw[1 * Species.COUNT + Species.Methane.ordinal] = 7L
        val field = MassArray(raw)

        field.checkInvariants()
        assertTrue(field.isEmptyAt(TileIndex(0)))
        assertTrue(!field.isEmptyAt(TileIndex(1)))
        assertEquals(7L, field[TileIndex(1), Species.Methane])
    }

    @Test
    fun copyKeepsTheMask() {
        val field = MassArray(2)
        field[TileIndex(0), Species.Helium] = 9L
        val copy = field.copyOf()

        copy.checkInvariants()
        assertEquals(9L, copy[TileIndex(0), Species.Helium])

        // And the copy is independent in both halves, not just the masses.
        copy[TileIndex(0), Species.Helium] = 0L
        assertTrue(copy.isEmptyAt(TileIndex(0)))
        assertTrue(!field.isEmptyAt(TileIndex(0)), "clearing the copy cleared the original's mask")
    }

    @Test
    fun iterationMatchesABruteForceScan() {
        // The property that matters, stated independently of the implementation: forEachSpecies
        // visits exactly the non-zero entries, no more and no less.
        val field = MassArray(3)
        val tile = TileIndex(1)
        val expected = mapOf(
            Species.Hydrogen to 1L,
            Species.Xenon to 2L,          // last declared volatile — exercises a high ordinal
            Species.Water to 3L,
        )
        for ((s, m) in expected) field[tile, s] = m

        val brute = Species.ALL.filter { field[tile, it] != 0L }.associateWith { field[tile, it] }
        val walked = HashMap<Species, Long>()
        field.forEachSpecies(tile) { s, m -> walked[s] = m }

        assertEquals(brute, walked)
        assertEquals(expected, walked)
    }
}
