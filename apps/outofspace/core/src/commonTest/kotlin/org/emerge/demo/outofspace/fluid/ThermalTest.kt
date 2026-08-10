package org.emerge.demo.outofspace.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.OutofspaceConfig
import org.emerge.demo.outofspace.OutofspaceReducer
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.ambientGasJoules
import org.emerge.demo.outofspace.world.fluid.gasCapacity
import org.emerge.demo.outofspace.world.fluid.stepFluid
import org.emerge.demo.outofspace.world.fluid.gasKelvin
import org.emerge.demo.outofspace.world.fluid.tilePressure
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Temperature as a property of the gas: it sets pressure, and it goes where the gas goes.
 *
 * The two claims are separable and both are tested here, because either alone is a broken sim. Heat
 * that raises pressure but stays put gives a plume that lifts and leaves its warmth behind, so the
 * same tile lifts forever. Heat that travels but does not raise pressure is a number painted on the
 * world, which is exactly what the sim already had and exactly what this increment is for.
 *
 * The load-bearing test is [ambientChangesNothing]. Turning temperature on had to leave a vessel at
 * room temperature bit-for-bit where it was, or every measurement taken of venting and thrust before
 * this would have needed re-taking, and there would be no way to tell a real thermal effect from a
 * re-baselined constant.
 */
class ThermalTest {

    private class Room(val w: Int = 12, val h: Int = 8) {
        val grid = Grid(w, h)
        val edges = EdgeGrid(grid)
        val structure: StructureMap
        val grams = LongArray(grid.size * Species.COUNT)
        val mx = LongArray(edges.xEdgeCount)
        val my = LongArray(edges.yEdgeCount)
        val joules: LongArray

        init {
            val machines = arrayOfNulls<Machine>(grid.size)
            for (x in 0 until w) { machines[grid.index(x, 0)] = Hull(); machines[grid.index(x, h - 1)] = Hull() }
            for (y in 0 until h) { machines[grid.index(0, y)] = Hull(); machines[grid.index(w - 1, y)] = Hull() }
            structure = StructureMap.derive(grid, machines.toList())
            for (x in 1 until w - 1) for (y in 1 until h - 1) {
                val base = grid.index(x, y) * Species.COUNT
                for (s in Species.ALL) grams[base + s.ordinal] = AirField.AMBIENT_AIR[s]
            }
            // The air at room temperature, which is exactly the energy that costs.
            joules = ambientGasJoules(grid.size, grams)
        }

        fun kelvin(): IntArray = gasKelvin(joules, gasCapacity(grid.size, grams))

        fun warm(tile: Int, byKelvin: Int) {
            joules[tile] += gasCapacity(grid.size, grams)[tile] * byKelvin
        }

        fun step(gravity: Frac2 = DOWN, withHeat: Boolean = true) =
            stepFluid(grid, structure, grams, mx, my, gravity, if (withHeat) joules else null)

        fun totalJoules(): Long = joules.sum()
    }

    /**
     * A vessel at room temperature must run exactly as it did before temperature existed.
     *
     * `n × T / T_ambient` is the identity when `T` is ambient, and that is deliberate rather than
     * lucky — see [tilePressure]. This asserts it end to end rather than on the pressure field
     * alone, because the whole tick has to come out the same, not just the one multiplication.
     */
    @Test
    fun ambientChangesNothing() {
        val hot = Room()
        val cold = Room()
        repeat(20) { hot.step(withHeat = true); cold.step(withHeat = false) }

        assertTrue(hot.grams.contentEquals(cold.grams), "mass field diverged at ambient temperature")
        assertTrue(hot.mx.contentEquals(cold.mx), "x-momentum diverged at ambient temperature")
        assertTrue(hot.my.contentEquals(cold.my), "y-momentum diverged at ambient temperature")
    }

    /** `P ∝ nT`: the same gas, warmer, is at a higher pressure. */
    @Test
    fun heatRaisesPressure() {
        val room = Room()
        val tile = room.grid.index(6, 4)
        val before = tilePressure(room.grid.size, room.grams, room.kelvin())[tile]

        room.warm(tile, 293) // twice absolute room temperature
        val after = tilePressure(room.grid.size, room.grams, room.kelvin())[tile]

        // Doubling absolute temperature at fixed mass doubles pressure. Integer division on the
        // capacity split makes it a hair under, so this is a band rather than an equality.
        assertTrue(after > before * 19 / 10, "warming barely moved pressure: $before → $after")
        assertTrue(after < before * 21 / 10, "warming overshot: $before → $after")
    }

    /**
     * A warmed parcel rises, and — the part that needs the advection to be right — the warmth is
     * somewhere else afterwards.
     *
     * Convection is not one behaviour, it is these two composing. The gas is warmed near the floor
     * and the assertion is that the hottest tile has moved *up* the room, which can only happen if
     * buoyancy lifted the parcel and the heat went with it.
     */
    @Test
    fun warmthRisesWithTheGasCarryingIt() {
        val room = Room(w = 10, h = 14)
        val floor = room.grid.index(5, 11)
        room.warm(floor, 400)
        val startY = floor / room.w

        repeat(60) { room.step() }

        val kelvin = room.kelvin()
        var hottest = -1
        var best = 0
        for (t in 0 until room.grid.size) {
            if (!room.structure.isContained(t) || room.structure.isImpermeable(t)) continue
            if (kelvin[t] > best) { best = kelvin[t]; hottest = t }
        }
        assertTrue(hottest >= 0, "no interior tile had a temperature")
        val endY = hottest / room.w
        // Up the screen is decreasing y — see EdgeGrid.
        assertTrue(endY < startY, "the warm parcel did not rise: y $startY → $endY")
        // A modest margin on purpose. Donor-cell upwind advection is diffusive by construction —
        // see advectMass, which takes that as the price of conserving mass exactly — so a parcel
        // started 400K above ambient is spread across a good part of the room after sixty ticks.
        // What is being asserted is that identifiable warmth survived the journey, not that it
        // arrived intact; a scheme that kept it intact would be one that had stopped conserving.
        assertTrue(best > Temperature.AMBIENT_KELVIN + 10, "the warmth dissipated entirely: ${best}K")
    }

    /**
     * Energy is conserved to the joule, and what leaves is what is reported.
     *
     * The same discipline as mass: the only way for energy to stop being in the world is to be
     * counted on its way out. A sealed room must not lose a single joule over any number of ticks,
     * which is a far sharper statement than "roughly conserved" and catches the rounding in the
     * gas-share split that a tolerance would hide.
     */
    @Test
    fun sealedRoomLosesNoEnergy() {
        val room = Room()
        room.warm(room.grid.index(4, 4), 500)
        val before = room.totalJoules()

        var vented = 0L
        repeat(40) { vented += room.step().ventedJoules }

        assertEquals(0L, vented, "a sealed room vented energy")
        assertEquals(before, room.totalJoules(), "energy was created or destroyed in a sealed room")
    }

    /** Breach the hull and the heat leaves with the air — booked, not deleted. */
    @Test
    fun ventingCarriesHeatOut() {
        val room = Room()
        val before = room.totalJoules()

        // Open the middle of the port wall.
        val breach = room.grid.index(0, 4)
        val machines = arrayOfNulls<Machine>(room.grid.size)
        for (x in 0 until room.w) {
            machines[room.grid.index(x, 0)] = Hull()
            machines[room.grid.index(x, room.h - 1)] = Hull()
        }
        for (y in 0 until room.h) {
            machines[room.grid.index(0, y)] = Hull()
            machines[room.grid.index(room.w - 1, y)] = Hull()
        }
        machines[breach] = null
        val breached = StructureMap.derive(room.grid, machines.toList())

        var vented = 0L
        repeat(80) {
            vented += stepFluid(
                room.grid, breached, room.grams, room.mx, room.my, DOWN, room.joules,
            ).ventedJoules
        }

        assertTrue(vented > 0L, "a breached vessel vented no energy at all")
        assertEquals(before, room.totalJoules() + vented, "the energy ledger did not close on a breach")
    }

    /**
     * The whole-world ledger, through the real reducer: the atmosphere's energy plus what it has
     * vented equals what it started with, on every tick.
     *
     * The other tests here drive [stepFluid] directly, which is the right grain for asking what one
     * pass does and the wrong one for asking whether the sim keeps its books. This goes through
     * [OutofspaceReducer] so that everything between an edit and the fluid — displacement and
     * the tick's array copying — is inside the assertion rather than beside it.
     */
    @Test
    fun `the atmosphere's energy ledger closes every tick`() {
        val g = Grid(14, 10)
        val machines = arrayOfNulls<Machine>(g.size)
        for (x in 0 until g.width) { machines[g.index(x, 0)] = Hull(); machines[g.index(x, g.height - 1)] = Hull() }
        for (y in 0 until g.height) { machines[g.index(0, y)] = Hull(); machines[g.index(g.width - 1, y)] = Hull() }
        // Built sealed and breached afterwards, which is not a detail. A vessel constructed with a
        // hole in it is open to space from the start, so the flood fill finds no enclosed volume and
        // AirField.ambient fills nothing — the world begins airless and vents nothing, which looks
        // exactly like a broken sim and is not one.
        var s = VesselState(g, machines.toList())
        machines[g.index(0, 5)] = null
        s = s.copy(machines = machines.toList())

        repeat(120) {
            s = OutofspaceReducer.reduce(OutofspaceConfig(), s, emptyMap())
            assertEquals(
                s.baselineAirJoules,
                s.air.totalJoules + s.airVentedJoules,
                "the air's energy ledger broke on tick ${s.tick}",
            )
        }
        assertTrue(s.airVentedGrams > 0L, "a breached vessel vented no air at all: ${s.airVentedGrams}g")
        assertTrue(s.airVentedJoules > 0L, "air left but its heat did not: ${s.airVentedGrams}g vented")
    }

    companion object {
        private val DOWN = Frac2(Frac(0L, 1), Frac(1L, 1))
    }
}
