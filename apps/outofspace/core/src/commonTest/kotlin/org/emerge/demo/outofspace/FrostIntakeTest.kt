package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.SignalSource
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.cohesionOf
import org.emerge.demo.outofspace.world.gasKelvin
import org.emerge.demo.outofspace.world.heatCapacity
import org.emerge.demo.outofspace.world.heatCapacityAt
import org.emerge.demo.outofspace.world.liftFrost
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **An extractor scrapes the frost off its own plate, and ships whole packets or nothing.**
 *
 * Frost that has landed is unreachable: a solid is not a gradient, so diffusion will not move it,
 * and the only thing that could take it was the atmosphere — which is what put it there. The way
 * back to cargo is a machine standing on it.
 *
 * ⛔ **Deliberately not what `910163f9` was.** That let every empty length of finished rail lift
 * frost off its own tile and was reverted: a rail network in a cold vessel spends itself collecting
 * the ice around it instead of carrying what it was drawn for, and every build acquires an
 * obligation to keep the room warm. Here it costs an extractor, sited on purpose.
 *
 * The second half is what that turns the machine's store into. A bite is a whole *cell* and a scrape
 * is whatever was lying there; neither is a round number, so the hopper is nearly always some
 * packets plus a remainder — and a remainder that dribbles out becomes a runt lump that owns its
 * tile for good, because packets never merge.
 */
class FrostIntakeTest {

    private val kg = Budget.KILOGRAM

    // ── The scrape itself ────────────────────────────────────────────────────

    private val grid = Grid(6, 1)
    private val plate: Array<TileIndex> = Array(3) { grid.tile(it + 1, 0) }
    private val outside = grid.tile(5, 0)

    /** A row of air, [what] at [kelvin] on each of [at], and the cohesion that goes with it. */
    private fun room(
        vararg what: Pair<Fluid, Long>,
        kelvin: Int,
        at: Array<TileIndex> = arrayOf(plate[1]),
    ): Triple<MassArray, EnergyArray, EnergyArray> {
        val air = MassArray(grid.size)
        for (tile in at) for ((fluid, mass) in what) air.add(tile, fluid, mass)
        val energy = EnergyArray(grid.size)
        for (tile in grid.tiles) energy[tile] = heatCapacityAt(air, tile) * kelvin
        return Triple(air, energy, cohesionOf(air, gasKelvin(energy, heatCapacity(grid.size, air))))
    }

    private fun airTotal(air: MassArray): Long {
        var sum = 0L
        for (tile in grid.tiles) for (f in Fluid.ALL) sum += air[tile, f]
        return sum
    }

    @Test
    fun `a plate standing in frost scrapes it up`() {
        // 200 K is below water's triple point, so what is condensed here is frost and not a puddle.
        val (air, energy, cohesion) = room(Fluid.Water to 10L * kg, kelvin = 200)
        val before = airTotal(air)

        val lifted = liftFrost(plate, air, energy, cohesion)

        assertTrue(lifted.total > 0L, "nothing was lifted")
        assertEquals(lifted.total, lifted[Species.Water], "something other than the frost came up")
        assertEquals(before - lifted.total, airTotal(air), "the room did not lose what the store gained")
    }

    @Test
    fun `it takes at most a packet a pass`() {
        val (air, energy, cohesion) = room(Fluid.Water to 900L * kg, kelvin = 200)

        val lifted = liftFrost(plate, air, energy, cohesion)

        assertEquals(Capacity.PACKET_MASS, lifted.total, "a plate inhaled more than a packet")
        assertTrue(airTotal(air) > 700L * kg, "it took the whole drift rather than a packet of it")
    }

    @Test
    fun `it works its whole footprint and not just one tile`() {
        // Two tiles, each holding well under a packet, so the budget cannot be what stops it — only
        // a walk that gave up after the first tile could.
        val (air, energy, cohesion) = room(Fluid.Water to 5L * kg, kelvin = 200, at = arrayOf(plate[0], plate[2]))

        val lifted = liftFrost(plate, air, energy, cohesion)

        assertTrue(lifted.total > 5L * kg, "only one tile of the plate was scraped: ${lifted.total}")
    }

    @Test
    fun `it reaches no further than the plate it stands on`() {
        val (air, energy, cohesion) = room(Fluid.Water to 10L * kg, kelvin = 200, at = arrayOf(outside))
        val before = airTotal(air)

        val lifted = liftFrost(plate, air, energy, cohesion)

        assertTrue(lifted.isEmpty, "it took frost from a tile it is not standing on")
        assertEquals(before, airTotal(air), "the room next door lost mass anyway")
    }

    // ── And only for the right matter ────────────────────────────────────────

    @Test
    fun `vapour is the room's and stays there`() {
        // Well above the critical point, so there is no condensed phase at all to argue about.
        val (air, energy, cohesion) = room(Fluid.Water to 1L * kg, kelvin = 900)
        val before = airTotal(air)

        val lifted = liftFrost(plate, air, energy, cohesion)

        assertTrue(lifted.isEmpty, "steam was shovelled into a hopper")
        assertEquals(before, airTotal(air), "the room lost mass anyway")
    }

    @Test
    fun `a puddle is not a packet`() {
        // 320 K is between water's triple point and its critical point, so what is condensed here is
        // a *liquid*. The rule is solids only, and this is the case that says so.
        val (air, energy, cohesion) = room(Fluid.Water to 10L * kg, kelvin = 320)

        val lifted = liftFrost(plate, air, energy, cohesion)

        assertTrue(lifted.isEmpty, "an extractor bottled a puddle")
    }

    // ── The line that is easy to miss ────────────────────────────────────────

    @Test
    fun `the cohesion array still describes what is left`() {
        // ⛔ The whole reason [liftFrost] touches the cohesion at all. That array is a statement
        // about the matter in the tile; take frost out and say nothing, and the next settlement
        // finds less bound matter than the total says it is paying for and makes up the difference
        // out of the room's heat. Lifting frost would chill the room it came from — the
        // free-refrigerator hole arriving from the other side, wearing a plausible disguise.
        //
        // Asserted against a fresh derivation rather than against a settled temperature, so that
        // what is being measured is this pass and not `settleCohesion`'s own accuracy — see the
        // note on `a settlement is not the yardstick` below.
        val at = plate[1]
        val (air, energy, cohesion) = room(Fluid.Water to 20L * kg, Fluid.Nitrogen to 1L * kg, kelvin = 200)
        val kelvin = gasKelvin(energy, heatCapacity(grid.size, air))[at.index]

        val lifted = liftFrost(plate, air, energy, cohesion)

        assertTrue(lifted.total > 0L, "nothing was lifted, so nothing was proved")
        val honest = cohesionOf(air, IntArray(grid.size) { kelvin })[at]
        assertEquals(
            honest, cohesion[at],
            "the tile is still booked for binding the frost that left it",
        )
    }

    @Test
    fun `the room it was swept out of stays the temperature it was`() {
        // ⚠️ The other half, and the one the mass-share version got wrong. Two kilograms of water
        // frost in twenty of nitrogen is nine percent of the mass and twenty-eight percent of the
        // heat capacity, because water carries four times the heat of the same weight of nitrogen.
        // Take nine percent of the energy away with it and the room is left holding far too much
        // for the matter still in it: 200 K in, 255 K out, a free heater exactly mirroring the free
        // refrigerator above.
        val at = plate[1]
        val (air, energy, cohesion) = room(Fluid.Nitrogen to 20L * kg, Fluid.Water to 2L * kg, kelvin = 200)

        val lifted = liftFrost(plate, air, energy, cohesion)

        assertTrue(lifted.total > 0L, "nothing was lifted, so nothing was proved")
        val after = gasKelvin(energy, heatCapacity(grid.size, air))[at.index]
        assertTrue(after in 199..201, "the room went from 200K to ${after}K just by being swept")
    }

    // ── Through the real tick ────────────────────────────────────────────────

    /**
     * The starter refinery, and where its working plate is.
     *
     * ⚠️ **Found by scanning, not by coordinates.** The vessel is fitted to its own contents on
     * construction, so `STARTER_PLATE_X/Y` name a tile on the grid it was *drawn* on and not on the
     * grid it comes back on. Two extractors are aboard and the second is the wire-throttled
     * demonstration one, so the plate wanted here is the one nothing is holding back.
     */
    private class Plant(val state: VesselState) {
        val grid: Grid = state.grid
        val cfg: OutofspaceConfig = OutofspaceConfig(initialGrid = grid)
        val plate: TileIndex = grid.tiles.single { t ->
            val m = state.deck[t]
            m is Extractor && m.center == t &&
                m.wiring.triggers(Action.Run).none { it.source == SignalSource.Wire }
        }
    }

    private fun plant(): Plant = Plant(starterVessel(Grid(40, 28)))

    private fun Plant.run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    private fun Plant.edit(state: VesselState, vararg edits: Edit): VesselState =
        OutofspaceReducer.reduce(cfg, state, mapOf(PlayerId(0) to OutofspaceInput(edits.toList())))

    /** Everything riding on any belt — where a packet went is not the question. */
    private fun Plant.onBelts(s: VesselState, species: Species): Long {
        var sum = 0L
        for (tile in grid.tiles) sum += s.rail.stuff[tile, species]
        return sum
    }

    /**
     * The same refinery with its rooms **frozen**, and no rock anywhere.
     *
     * No rock because the point is that a plate in a cold room is a source on its own; with one
     * lying on it there would be no telling a scrape from a bite. 60 K is below the triple point of
     * most of what ordinary air is made of — nitrogen freezes at 63 K, argon at 84, carbon dioxide
     * at 216 — so what is in the rooms is lying on the floor.
     */
    private fun Plant.frozen(kelvin: Int = 60): VesselState {
        val air = state.air.copyMass()
        val energy = EnergyArray(grid.size)
        for (tile in grid.tiles) energy[tile] = heatCapacityAt(air, tile) * kelvin
        return VesselState(
            grid,
            state.deck,
            conduits = state.conduits,
            buffers = state.buffers,
            rail = state.rail,
            air = Stuff.from(air, energy),
        )
    }

    @Test
    fun `an extractor in a frozen room fills up with no rock at all`() {
        val p = plant()
        val start = p.frozen()
        assertEquals(0L, start.inStore(p.plate, BufferRole.Product)?.total ?: 0L, "fixture: it starts empty")
        assertTrue(start.bodies.isEmpty(), "fixture: there should be no rock to bite")

        val s = p.run(start, 40)

        val held = s.inStore(p.plate, BufferRole.Product)?.total ?: 0L
        assertTrue(held > 0L, "the plate stood in frost for forty ticks and picked up none of it")
        assertTrue(s.atmosphereMass < start.atmosphereMass, "the cargo grew without the air shrinking")
        assertEquals(0L, s.airBalance, "the air ledger did not hear about it")
    }

    @Test
    fun `a warm room has nothing lying on its floor`() {
        // The control, and it is the whole of what makes this a mechanic rather than a leak: the
        // same plate in the same vessel at room temperature is a plate with no rock on it, and a
        // plate with no rock on it produces nothing at all.
        val p = plant()
        val start = p.state
        val s = p.run(start, 40)

        assertEquals(
            0L, s.inStore(p.plate, BufferRole.Product)?.total ?: 0L,
            "an extractor in a warm empty room made ore out of the air",
        )
    }

    // ── Whole packets ────────────────────────────────────────────────────────

    /**
     * A lump of actual **ore**, which is to say a blend.
     *
     * ⚠️ **It used to be pure iron, and calling that "ore" was the whole of a later bug.** These
     * tests run on the starter vessel, whose extractor's output run ends at a concentrator — and a
     * concentrator asks for [SpeciesFilter.MIXED], so it is never sent anything already pure. A pure
     * payload therefore never leaves the hopper, and the two tests below stopped measuring whole
     * packets and started measuring the routing rule instead.
     */
    private fun ore(mass: Long): Mixture =
        Mixture.of(Species.Iron to mass * 3L / 4L, Species.Forsterite to mass - mass * 3L / 4L, energy = 0L)
            .atAmbient()

    @Test
    fun `a part packet waits in the hopper`() {
        // A room at ambient and no rock, so nothing tops the store up: what is in the hopper at the
        // start is all there will ever be.
        val p = plant()
        val start = p.state.stocked(p.plate, ore(30L * kg), BufferRole.Product)

        val s = p.run(start, 60)

        assertEquals(0L, p.onBelts(s, Species.Iron), "a runt lump went out onto the track")
        assertEquals(
            30L * kg, s.inStore(p.plate, BufferRole.Product)?.total,
            "and the hopper did not keep it either",
        )
    }

    @Test
    fun `a whole packet goes`() {
        // The other side of the same rule. Without this the test above passes for the boring reason
        // that nothing ever leaves an extractor.
        val p = plant()
        val start = p.state.stocked(p.plate, ore(Capacity.PACKET_MASS), BufferRole.Product)

        val s = p.run(start, 60)

        assertTrue(p.onBelts(s, Species.Iron) > 0L, "a full packet stayed in the hopper")
    }

    @Test
    fun `a machine told to go hands over its last crumbs`() {
        // ⛔ The exception, and the reason the rule is a condition rather than a cap.
        // Deconstruction *waits* on a store an output port drains — see `scrapMachines` — so a
        // machine that will not let go of thirty kilograms is a machine that never comes apart.
        val p = plant()
        var s = p.state.stocked(p.plate, ore(30L * kg), BufferRole.Product)
        s = p.edit(s, Edit.Remove(p.plate, DeleteLayer.Deck))
        assertTrue(p.plate in s.scrapping, "fixture: it should be condemned")

        s = p.run(s, 60)

        assertTrue(p.onBelts(s, Species.Iron) > 0L, "the condemned machine kept its remainder")
    }
}
