package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.cohesionOf
import org.emerge.demo.outofspace.world.gasKelvin
import org.emerge.demo.outofspace.world.heatCapacity
import org.emerge.demo.outofspace.world.heatCapacityAt
import org.emerge.demo.outofspace.world.scavengeFrost
import org.emerge.demo.outofspace.world.settleCohesion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Empty track picks frost up off the floor.**
 *
 * A vessel that cools below a species' triple point does not lose that species — it lands, and since
 * `a60499a3` it stays where it landed, because a solid is not a gradient. That left the matter
 * visible, immobile and completely unreachable: the only thing that could take it was the
 * atmosphere, and the atmosphere is what put it there.
 *
 * Solids only. Not vapour, which is the room's; and not liquid, because a packet of puddle is not a
 * thing. The boundary is the triple point, a measured property, so which species a hold can be mined
 * for is a fact about how cold it is rather than a list somebody wrote.
 */
class ScavengeTest {

    private val grid = Grid(6, 1)
    private val kg = Budget.KILOGRAM
    private val tile = grid.tile(2, 0)

    private fun rails(): Pair<Conduits, RailLayer> {
        val segments = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, segments, 0, grid.width - 1, 0)
        return Conduits.ofRails(segments.toList()) to RailLayer.empty(grid.size)
    }

    /** A tile of [what] at [kelvin], and the cohesion that goes with it. */
    private fun room(vararg what: Pair<Fluid, Long>, kelvin: Int): Triple<MassArray, EnergyArray, EnergyArray> {
        val air = MassArray(grid.size)
        for ((fluid, mass) in what) air.add(tile, fluid, mass)
        val energy = EnergyArray(grid.size)
        energy[tile] = heatCapacityAt(air, tile) * kelvin
        return Triple(air, energy, cohesionOf(air, gasKelvin(energy, heatCapacity(grid.size, air))))
    }

    private fun airTotal(air: MassArray): Long {
        var sum = 0L
        for (f in Fluid.ALL) sum += air[tile, f]
        return sum
    }

    // ── It happens ───────────────────────────────────────────────────────────

    @Test
    fun `an empty rail standing in frost lifts it onto the track`() {
        // 200 K is below water's triple point, so this is frost and not a puddle.
        val (conduits, rail) = rails()
        val (air, energy, cohesion) = room(Fluid.Water to 10L * kg, kelvin = 200)
        val before = airTotal(air)

        val lifted = scavengeFrost(grid, conduits, rail, air, energy, cohesion)

        assertTrue(lifted.mass > 0L, "nothing was lifted")
        val packet = rail.resourceAt(tile)
        assertTrue(packet != null && packet.total > 0L, "the track is still empty")
        assertEquals(lifted.mass, packet!![Species.Water], "the packet is not what the step reported")
        assertEquals(before - lifted.mass, airTotal(air), "the room did not lose what the track gained")
    }

    @Test
    fun `it takes at most a packet at a time`() {
        val (conduits, rail) = rails()
        val (air, energy, cohesion) = room(Fluid.Water to 900L * kg, kelvin = 200)

        val lifted = scavengeFrost(grid, conduits, rail, air, energy, cohesion)

        assertEquals(Capacity.PACKET_MASS, lifted.mass, "a rail lifted more than a packet")
        assertTrue(airTotal(air) > 700L * kg, "it took the whole drift rather than a packet of it")
    }

    // ── And only for the right matter ────────────────────────────────────────

    @Test
    fun `vapour is the room's and stays there`() {
        // Well above the critical point, so there is no condensed phase at all to argue about.
        val (conduits, rail) = rails()
        val (air, energy, cohesion) = room(Fluid.Water to 1L * kg, kelvin = 900)
        val before = airTotal(air)

        val lifted = scavengeFrost(grid, conduits, rail, air, energy, cohesion)

        assertTrue(lifted.isNothing, "steam was shovelled onto a belt")
        assertNull(rail.resourceAt(tile), "the track picked something up")
        assertEquals(before, airTotal(air), "the room lost mass anyway")
    }

    @Test
    fun `a puddle is not a packet`() {
        // 320 K is between water's triple point and its critical point, so what is condensed here
        // is a *liquid*. The rule is solids only, and this is the case that says so.
        val (conduits, rail) = rails()
        val (air, energy, cohesion) = room(Fluid.Water to 10L * kg, kelvin = 320)

        val lifted = scavengeFrost(grid, conduits, rail, air, energy, cohesion)

        assertTrue(lifted.isNothing, "a rail carried away a puddle")
        assertNull(rail.resourceAt(tile), "the track picked up a liquid")
    }

    @Test
    fun `a rail with something already on it takes nothing`() {
        val (conduits, rail) = rails()
        val (air, energy, cohesion) = room(Fluid.Water to 10L * kg, kelvin = 200)
        rail.put(tile, org.emerge.demo.outofspace.chem.Mixture.of(Species.Iron to 1L * kg, energy = 0L))
        val before = airTotal(air)

        val lifted = scavengeFrost(grid, conduits, rail, air, energy, cohesion)

        assertTrue(lifted.isNothing, "an occupied rail scavenged anyway")
        assertEquals(before, airTotal(air), "the room lost mass to a full belt")
    }

    // ── The line that is easy to miss ────────────────────────────────────────

    @Test
    fun `taking the frost away does not chill the room it came from`() {
        // ⛔ The whole reason [scavengeFrost] touches the cohesion at all. That array is a statement
        // about the matter in the tile; take a hundred kilograms of frost out and say nothing, and
        // the next settlement finds less bound matter than the total says it is paying for and makes
        // up the difference out of the room's heat. Removing frost would chill the room — the
        // free-refrigerator hole arriving from the other side, wearing a plausible disguise.
        val (conduits, rail) = rails()
        val (air, energy, cohesion) = room(Fluid.Water to 20L * kg, Fluid.Nitrogen to 1L * kg, kelvin = 200)
        val before = gasKelvin(energy, heatCapacity(grid.size, air))[0]

        val lifted = scavengeFrost(grid, conduits, rail, air, energy, cohesion)
        assertTrue(lifted.mass > 0L, "nothing was lifted, so nothing was proved")
        settleCohesion(air, energy, cohesion)

        val after = gasKelvin(energy, heatCapacity(grid.size, air))[0]
        assertTrue(
            kotlin.math.abs(after - before) <= 2,
            "the room went from ${before}K to ${after}K just by having its frost carted off",
        )
    }

    @Test
    fun `nothing is charged for a phase change that did not happen`() {
        // The matter was bound in the air and it is bound on the track. What moved is the binding
        // itself, out of the field's books and into the cargo's, where matter has always been bound
        // implicitly. A settlement straight afterwards must find nothing to do.
        val (conduits, rail) = rails()
        val (air, energy, cohesion) = room(Fluid.Water to 20L * kg, Fluid.Nitrogen to 1L * kg, kelvin = 200)

        scavengeFrost(grid, conduits, rail, air, energy, cohesion)
        val booked = settleCohesion(air, energy, cohesion)

        assertTrue(
            kotlin.math.abs(booked) < heatCapacityAt(air, tile) * 2,
            "settling after a scavenge booked $booked of energy across the bond boundary",
        )
    }
}
