package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.ApertureField
import org.emerge.demo.outofspace.world.EdgeGrid
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.diffuseFluid
import org.emerge.demo.outofspace.world.heatCapacityAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Frost stays where it froze.**
 *
 * Diffusion is Fick's law, which describes a mixture spreading through itself. A pool of liquid
 * sitting under an atmosphere is not a mixture — it is two phases with an interface — and a block
 * of ice is not one either. Differencing concentration across that interface makes the condensed
 * matter read as an infinitely steep gradient, and drift dissolves it: a saturated pool was measured
 * shedding 76% of itself in twenty ticks.
 *
 * So the pass moves the **vapour**, not the mass. Which part of a cell's contents that is, is
 * exactly what `vapourMass` has always answered and what nothing has ever asked it — see the note
 * on that function, which was written for this and then left dead for long enough to grow a bug.
 *
 * ⚠️ This is where the whole matter-state design earns itself. A phase that is only a label on a
 * readout is decoration; a phase that decides whether something can move is a mechanic.
 */
class PhaseTransportTest {

    private val grid = Grid(7, 1)
    private val edges = EdgeGrid(grid)
    private val middle = grid.tile(3, 0)
    private val kg = Budget.KILOGRAM

    /** Nothing in the way: every interior face wide open, the rim shut so nothing vents. */
    private fun sealed(): ApertureField {
        val x = IntArray(edges.xEdgeCount) { if (edges.isXBoundary(it)) ApertureField.CLOSED else ApertureField.OPEN }
        val y = IntArray(edges.yEdgeCount) { if (edges.isYBoundary(it)) ApertureField.CLOSED else ApertureField.OPEN }
        return ApertureField(edges, x, y)
    }

    /** One lump of [species] on the middle tile, at [kelvin], and nothing anywhere else. */
    private fun world(species: Species, mass: Long, kelvin: Int): Pair<MassArray, EnergyArray> {
        val masses = MassArray(grid.size)
        masses.add(middle, Fluid.of(species)!!, mass)
        val energies = EnergyArray(grid.size)
        energies[middle] = heatCapacityAt(masses, middle) * kelvin
        return masses to energies
    }

    private fun run(masses: MassArray, energies: EnergyArray, passes: Int) {
        repeat(passes) { diffuseFluid(edges, sealed(), masses, energies) }
    }

    private fun at(masses: MassArray, x: Int, species: Species): Long =
        masses[grid.tile(x, 0), Fluid.of(species)!!]

    private fun total(masses: MassArray, species: Species): Long {
        var sum = 0L
        for (x in 0 until grid.width) sum += at(masses, x, species)
        return sum
    }

    // ── It moves when it is a gas ────────────────────────────────────────────

    @Test
    fun `water vapour in a warm room spreads out`() {
        // The control, and it has to come first: everything below is an assertion that something
        // did *not* happen, and none of it means anything if the pass moves nothing anyway.
        val (masses, energies) = world(Species.Water, 10L * kg, kelvin = 500)
        run(masses, energies, 40)

        assertTrue(at(masses, 0, Species.Water) > 0L, "steam did not reach the far end of the room")
        assertTrue(
            at(masses, 3, Species.Water) < 10L * kg,
            "the middle tile still holds everything it started with",
        )
    }

    // ── And it does not when it is not ───────────────────────────────────────

    @Test
    fun `ice does not crawl across the floor`() {
        // 200 K is below water's triple point, so this is a solid: no liquid phase exists at any
        // pressure at all. Ten kilograms in one tile is far past the vapour the room can hold, so
        // essentially all of it is frost.
        val (masses, energies) = world(Species.Water, 10L * kg, kelvin = 200)
        val before = at(masses, 3, Species.Water)
        run(masses, energies, 40)

        assertEquals(10L * kg, total(masses, Species.Water), "mass was not conserved")
        val kept = at(masses, 3, Species.Water).toDouble() / before
        assertTrue(kept > 0.99, "the ice spread: the tile it froze in kept only ${kept * 100}% of it")
    }

    @Test
    fun `a puddle stays a puddle`() {
        // Above the triple point and below the critical point, so this is a liquid. It has a real
        // vapour pressure, unlike the ice above, so the room does take some of it — what must not
        // happen is the *pool* being differenced away as though it were a gradient.
        val (masses, energies) = world(Species.Water, 10L * kg, kelvin = 320)
        run(masses, energies, 20)

        val kept = at(masses, 3, Species.Water).toDouble() / (10L * kg)
        assertTrue(kept > 0.85, "the pool dissolved: only ${kept * 100}% of it stayed put")
        assertTrue(
            at(masses, 0, Species.Water) > 0L,
            "nothing evaporated at all — a puddle is not a rock",
        )
    }

    @Test
    fun `what the frost does not hold, the room still carries away`() {
        // The frost is not a wall. Nitrogen poured into the same tile is a gas at 200 K and has to
        // leave normally, or "solids do not diffuse" has been implemented as "cold tiles are stuck".
        val masses = MassArray(grid.size)
        masses.add(middle, Fluid.Water, 10L * kg)
        masses.add(middle, Fluid.Nitrogen, 1L * kg)
        val energies = EnergyArray(grid.size)
        energies[middle] = heatCapacityAt(masses, middle) * 200

        run(masses, energies, 40)

        assertTrue(
            at(masses, 0, Species.Nitrogen) > 0L,
            "the nitrogen was trapped by the ice sharing its tile",
        )
        val ice = at(masses, 3, Species.Water).toDouble() / (10L * kg)
        assertTrue(ice > 0.99, "and the ice still should not have moved; it kept ${ice * 100}%")
    }
}
