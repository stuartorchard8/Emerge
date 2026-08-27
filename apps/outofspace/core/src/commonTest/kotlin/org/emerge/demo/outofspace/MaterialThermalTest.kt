package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.TILE_LITRES
import org.emerge.demo.outofspace.world.Material
import org.emerge.demo.outofspace.world.conductanceCentiTicksOf
import org.emerge.demo.outofspace.world.conductivityOf
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Material.conductanceCentiTicks` is derived from real thermal conductivities now — these are the
 * checks that keep the derivation honest, and the record of what it moved.
 *
 * ⛔ **The five numbers this replaced were not physics and could not all be kept.** Measured against
 * their own densities and conductivities they implied tick durations from 1,025 s (firebrick) to
 * 13,110 s (copper). The calibration chosen is one hour, which is the round number nearest their
 * geometric mean and so the anchor that moves each of them the least it can.
 */
class MaterialThermalTest {

    /**
     * ⛔ **The one that would catch a broken derivation**: every material's time constant is exactly
     * `ρ·c·L²/k` at the stated calibration, recomputed here from the species table rather than
     * restated as a literal.
     *
     * ⚠️ Written as the *formula* and not as five expected numbers on purpose. Five numbers would
     * pass just as well against a derivation that had quietly stopped depending on conductivity at
     * all, which is precisely the regression worth fearing when a stated table becomes a computed
     * one.
     */
    @Test
    fun `every material's time constant is its own physics`() {
        val secondsPerTick = 3_600L
        val faceMilliSquareMetres = 883L
        for (m in Material.entries) {
            val species = m.composition.dominant!!
            val expected = 100L * species.solidKgPerCubicMetre * species.specificHeat *
                faceMilliSquareMetres / (species.milliWattsPerMetreKelvin * secondsPerTick)
            assertEquals(
                expected,
                m.conductanceCentiTicks,
                "${m.label} does not conduct as ρ·c·L²/k says it should",
            )
        }
    }

    /**
     * The tile's face, checked against the tile's own volume rather than trusted as a literal.
     *
     * `TILE_LITRES` is the one place SI touches the vessel, so a change to it has to move the
     * geometry with it — and a cube root is exactly the constant that gets left behind when a
     * dimension moves.
     */
    @Test
    fun `the tile face is the tile volume's own two-thirds power`() {
        val edge = 0.830 // m, from TILE_LITRES
        assertEquals(830L, TILE_LITRES, "the tile changed size and the face constant did not follow")
        val face = edge.pow(2.0 / 3.0)
        assertTrue(
            abs(face * 1000.0 - 883.0) < 1.0,
            "0.830 m³ has a face of ${face * 1000} milli-m², not 883",
        )
    }

    /**
     * ⛔ **The ordering the whole table exists to express**, and the thing a calibration cannot break
     * however it is chosen: a furnace lining insulates and a cable does not.
     */
    @Test
    fun `a firebrick joint is slow and a copper one is quick`() {
        assertTrue(
            Material.Firebrick.conductanceCentiTicks > Material.Copper.conductanceCentiTicks * 100L,
            "firebrick (${Material.Firebrick.conductanceCentiTicks}) is supposed to be far slower " +
                "than copper (${Material.Copper.conductanceCentiTicks})",
        )
        for (m in Material.entries) {
            assertTrue(m.conductanceCentiTicks > 0L, "${m.label} has no time constant at all")
            assertTrue(m.conductance > 0L, "${m.label} conducts nothing")
        }
    }

    /**
     * ⚠️ **A mixture's conductivity is the harmonic mean, so a trace of metal does not make a brick
     * conduct like one.** Nothing in the game has a multi-species material today; this is the only
     * thing exercising that path, and the arithmetic mean it must not be is the obvious mistake.
     */
    @Test
    fun `the poor conductor governs a mixture`() {
        val half = Mixture.of(Species.Copper to 500L, Species.Firebrick to 500L, energy = 0L)
        val arithmetic = (Species.Copper.milliWattsPerMetreKelvin +
            Species.Firebrick.milliWattsPerMetreKelvin) / 2L
        val k = conductivityOf(half)
        assertTrue(
            k < arithmetic / 10L,
            "half copper by mass conducts at $k, which is far too near the arithmetic mean $arithmetic",
        )
        assertTrue(
            k > Species.Firebrick.milliWattsPerMetreKelvin,
            "adding copper to firebrick made it conduct worse than firebrick",
        )
        // And a pure mixture is exactly its species, with no fixed-point round trip in the way.
        assertEquals(
            Species.Copper.milliWattsPerMetreKelvin.toLong(),
            conductivityOf(Mixture.of(Species.Copper to 1_000L, energy = 0L)),
            "pure copper is not copper",
        )
    }

    /** Nothing is not a material: an empty mixture conducts nothing rather than dividing by zero. */
    @Test
    fun `an empty mixture has no thermal behaviour at all`() {
        assertEquals(0L, conductivityOf(Mixture.EMPTY), "nothing conducts")
        assertEquals(0L, conductanceCentiTicksOf(Mixture.EMPTY), "nothing has a time constant")
    }
}
