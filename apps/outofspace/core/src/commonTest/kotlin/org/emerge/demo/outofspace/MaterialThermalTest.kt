package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.TILE_LITRES
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Material
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.bodiesOf
import org.emerge.demo.outofspace.world.conductanceOf
import org.emerge.demo.outofspace.world.fillPermille
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.material
import org.emerge.demo.outofspace.world.species
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

    /**
     * ⛔ **A tile conducts as what it is made of, not as what its kind usually is.**
     *
     * The whole point of the change: a run of track built out of copper salvage is a copper thermal
     * object, and a titanium extractor and a steel one are two different ones. Until a player is
     * given the choice everything is still built from its kind's default, which is why the entire
     * suite stayed green — so this is the only test that can see the capability at all, and without
     * it the derivation could quietly revert to a constant and nothing would notice.
     */
    @Test
    fun `a length of track conducts as the metal actually in it`() {
        val grid = Grid(6, 4)
        val tile = grid.tile(2, 2)

        fun conductanceMadeOf(species: Species): Long {
            val rails = arrayOfNulls<Segment>(grid.size)
            rails[tile.index] = Segment(Conduit.Rail)
            val conduits = Conduits.ofRails(rails.toList())
            // Replace the metal with the same *mass* of something else, so the only thing that can
            // move the answer is which species it is.
            val stuff = conduits.tracks[Conduit.Rail]
            val mass = stuff.massAt(tile)
            assertTrue(mass > 0L, "fixture: stated track is supposed to arrive finished")
            stuff.release(tile)
            stuff[tile, species] = mass
            val deck = DeckArray(grid)
            return bodiesOf(grid, conduits, deck, BufferLayer.forDeck(grid, deck), RailLayer.empty(grid.size))
                .single { it.tile == tile && it.conduit == Conduit.Rail }
                .conductance
        }

        val iron = conductanceMadeOf(Species.Iron)
        val copper = conductanceMadeOf(Species.Copper)
        val firebrick = conductanceMadeOf(Species.Firebrick)

        assertEquals(
            conductanceOf(Species.Iron, Conduit.Rail.fillPermille),
            iron,
            "an iron rail should conduct exactly what a tile of iron at rail fill does",
        )
        assertTrue(copper > iron * 2L, "a copper rail ($copper) barely beat an iron one ($iron)")
        assertTrue(firebrick < iron / 2L, "a firebrick rail ($firebrick) conducted like metal")
    }

    /**
     * ⚠️ And a construction site falls back to its kind's default rather than to nothing. It is not
     * made of anything yet, and a node with no conductance at all is a different thing in the solve
     * from a cold one.
     */
    @Test
    fun `a ghost still has its kind's conductance`() {
        val grid = Grid(6, 4)
        val tile = grid.tile(2, 2)
        val rails = arrayOfNulls<Segment>(grid.size)
        rails[tile.index] = Segment(Conduit.Rail)
        val conduits = Conduits.ofRails(rails.toList())
        conduits.tracks[Conduit.Rail].release(tile)

        val deck = DeckArray(grid)
        val body = bodiesOf(grid, conduits, deck, BufferLayer.forDeck(grid, deck), RailLayer.empty(grid.size))
            .single { it.tile == tile && it.conduit == Conduit.Rail }

        assertEquals(0L, conduits.massAt(Conduit.Rail, tile), "fixture: it is supposed to be a ghost")
        assertEquals(
            conductanceOf(Conduit.Rail.material.species, Conduit.Rail.fillPermille),
            body.conductance,
            "a ghost rail lost its conductance instead of falling back to iron",
        )
    }
}
