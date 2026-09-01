package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.ApertureField
import org.emerge.demo.outofspace.world.EdgeGrid
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.diffuseFluid
import org.emerge.demo.outofspace.world.heatCapacity
import org.emerge.demo.outofspace.world.heatCapacityAt
import org.emerge.demo.outofspace.world.settleCondensate
import org.emerge.demo.outofspace.world.MassDistribution
import org.emerge.demo.outofspace.world.Rotation
import org.emerge.demo.outofspace.world.gasKelvin
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
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

    // ── And nothing may pile into a cell that is already full of it ─────────

    /**
     * **A cold cell that is already at its own condensed density takes no more.**
     *
     * The other half of "frost stays where it froze", and without it the pass is a **one-way
     * valve**: vapour walks into a cold cell, condenses, and can never leave again, because the
     * only thing in the game that moves a puddle is an extractor standing on it. A cold dead end
     * therefore ratchets without limit.
     *
     * Measured on a live save before this existed: a sealed nose cone at 25 K had collected 241 kg
     * into a single tile, half of it hydrogen at **19.3x its own liquid density**, still climbing
     * monotonically at +630 g per 10,000 ticks after seventeen million ticks of it — and reading
     * 3,025 atm, because at that density the equation of state is on its compressed-liquid branch.
     * That one tile was 99.87% of the entire pressure field of the ship.
     *
     * Hydrogen because it has the lowest condensed density on file and so overfills a cell first,
     * which is exactly why it was hydrogen that did it on the real ship.
     */
    @Test
    fun `gas does not pile into a cell already at its condensed density`() {
        val h2 = Fluid.of(Species.Hydrogen)!!
        val masses = MassArray(grid.size)
        val energies = EnergyArray(grid.size)

        // A tile of liquid hydrogen at 25 K is about 6.4 kg, so 100 kg is some fifteen times over.
        val packedTile = 100L * kg
        masses.add(middle, h2, packedTile)
        energies[middle] = heatCapacityAt(masses, middle) * 25

        // And a warm puff of the same gas two cells away, with a clear run at it.
        val source = grid.tile(1, 0)
        val puff = 1L * kg
        masses.add(source, h2, puff)
        energies[source] = heatCapacityAt(masses, source) * 300

        run(masses, energies, 40)

        assertEquals(
            packedTile, at(masses, 3, Species.Hydrogen),
            "the full cell took more hydrogen on top of what it could already not get rid of",
        )
        // Not merely frozen in place: the puff has to have gone somewhere, or this would pass on a
        // pass that had stopped moving anything at all.
        assertTrue(
            at(masses, 0, Species.Hydrogen) > 0L,
            "the puff never moved, so the assertion above proves nothing",
        )
        assertEquals(
            packedTile + puff, total(masses, Species.Hydrogen),
            "refusing the gas lost it — it is supposed to stay where it was",
        )
    }

    // ── But it falls, if there is a down for it to fall ─────────────────────

    private fun kelvinOf(masses: MassArray, energies: EnergyArray): IntArray =
        gasKelvin(energies, heatCapacity(grid.size, masses))

    private fun settle(masses: MassArray, energies: EnergyArray, down: Frac2, passes: Int, spin: Long = 0L) {
        repeat(passes) {
            settleCondensate(
                edges, sealed(), masses, energies, kelvinOf(masses, energies),
                down, spin, MassDistribution(mass = 1L, comMilliX = 3_500L, comMilliY = 500L, gyrationSq = 1L),
            )
        }
    }

    /**
     * **"Does not diffuse" is not "does not move."**
     *
     * Everything above says frost stays where it froze, and that is a statement about *diffusion* —
     * Fick's law across a phase interface dissolves a pool, so the vapour moves and the ice does
     * not. But a block of ice in a ship under thrust slides to the back of the room, and this is the
     * pass that lets it. See [settleCondensate].
     */
    @Test
    fun `frost falls when there is a down for it to fall`() {
        val (masses, energies) = world(Species.Water, 10L * kg, 100)
        settle(masses, energies, Frac2(Frac(1L, 1), Frac(0L)), passes = 30)

        assertTrue(
            at(masses, 4, Species.Water) > 0L,
            "the ice sat where it froze under a full gravity — nothing in this game will ever pour",
        )
        assertEquals(10L * kg, total(masses, Species.Water), "settling did not conserve the ice")
    }

    /**
     * ⛔ **In freefall nothing settles, and that is the point rather than a limitation.**
     *
     * Every scrap of motion in [settleCondensate] is subjective gravity; there is no term that acts
     * without one. A ship with no plating, no burn and no spin has no down, so its frost hangs where
     * it formed — which is also what makes this pass safe to run every tick on a coasting vessel.
     */
    @Test
    fun `in freefall nothing settles at all`() {
        val (masses, energies) = world(Species.Water, 10L * kg, 100)
        settle(masses, energies, Frac2(Frac(0L), Frac(0L)), passes = 30)

        assertEquals(
            10L * kg, at(masses, 3, Species.Water),
            "frost slid downhill on a ship that has no downhill",
        )
    }

    /**
     * **A spun ring holds its frost against the rim** — the centrifugal half, and Stu's reason for
     * wanting it: rotation as non-locomotive artificial gravity.
     *
     * ⚠️ Nothing else is pushing: the uniform field is zero, so every unit of motion here is `ω²r`
     * about the axis. The lump starts inboard of the axis and has to end further out, which is a
     * different claim from "it moved" — a bug that slid everything one way would fail it.
     */
    @Test
    fun `a spinning ship holds its frost out against the rim`() {
        val (masses, energies) = world(Species.Water, 10L * kg, 100)
        // Axis at x = 2.5 tiles, so the lump at x = 3 is outboard of it and should climb away.
        val axis = MassDistribution(mass = 1L, comMilliX = 2_500L, comMilliY = 500L, gyrationSq = 1L)
        repeat(30) {
            settleCondensate(
                edges, sealed(), masses, energies, kelvinOf(masses, energies),
                Frac2(Frac(0L), Frac(0L)), spin = Rotation.RAW_PER_RADIAN / 8L, about = axis,
            )
        }

        assertTrue(
            at(masses, 4, Species.Water) > 0L,
            "a spun ring did not throw its frost outward: nothing left tile 3",
        )
        assertEquals(0L, at(masses, 2, Species.Water), "frost fell *toward* the axis of rotation")
        assertEquals(10L * kg, total(masses, Species.Water), "spinning did not conserve the ice")
    }

    /**
     * **A liquid that cannot go down goes sideways; a solid stays put and piles.**
     *
     * The one difference between the two in [settleCondensate], and it is what makes a liquid read
     * as *filling its container* while frost reads as a heap. Both still respect the cell they move
     * into — neither may over-pack one.
     *
     * The fixture is the same water twice, at two temperatures either side of its triple point, with
     * downhill already full. Same species, same mass, same gravity, same blocked road: the only
     * thing that differs is whether it is ice or a puddle.
     *
     * ⚠️ Told apart by the **triple point**, not by `phaseAt`. The dome is a vapour-liquid
     * construction with no solid branch, so below the triple point it is extrapolating the wrong
     * curve — see [org.emerge.demo.outofspace.world.settleCondensate].
     */
    @Test
    fun `a blocked liquid spreads sideways and blocked frost does not`() {
        // A two-row world, so there is a sideways to go: gravity points +x, and the cell downhill
        // of the lump is packed solid with the same species.
        val tall = Grid(5, 3)
        val tallEdges = EdgeGrid(tall)
        fun sealedTall(): ApertureField {
            val x = IntArray(tallEdges.xEdgeCount) { if (tallEdges.isXBoundary(it)) ApertureField.CLOSED else ApertureField.OPEN }
            val y = IntArray(tallEdges.yEdgeCount) { if (tallEdges.isYBoundary(it)) ApertureField.CLOSED else ApertureField.OPEN }
            return ApertureField(tallEdges, x, y)
        }
        fun spread(kelvin: Int): Long {
            val masses = MassArray(tall.size)
            val start = tall.tile(2, 1)
            masses.add(start, Fluid.Water, 8L * kg)
            // Downhill is already full to its condensed density, so the fall is refused.
            masses.add(tall.tile(3, 1), Fluid.Water, 4_000L * kg)
            val energies = EnergyArray(tall.size)
            for (t in tall.tiles) energies[t] = heatCapacityAt(masses, t) * kelvin
            repeat(20) {
                settleCondensate(
                    tallEdges, sealedTall(), masses, energies,
                    gasKelvin(energies, heatCapacity(tall.size, masses)),
                    Frac2(Frac(1L, 1), Frac(0L)), spin = 0L,
                    about = MassDistribution(1L, 2_500L, 1_500L, 1L),
                )
            }
            // How much left the starting cell sideways, into the two rows either side of it.
            return masses[MassIndex(tall.tile(2, 0), Fluid.Water)] +
                masses[MassIndex(tall.tile(2, 2), Fluid.Water)]
        }

        // Water's triple point is 273 K, so 200 K is ice and 300 K is a puddle.
        val ice = spread(200)
        val puddle = spread(300)

        assertEquals(0L, ice, "frost went round the obstruction instead of piling against it")
        assertTrue(
            puddle > 0L,
            "a blocked puddle sat still instead of spreading: it will never fill a container",
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
