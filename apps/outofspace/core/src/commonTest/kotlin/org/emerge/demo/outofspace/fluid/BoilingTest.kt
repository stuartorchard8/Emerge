package org.emerge.demo.outofspace.fluid

import org.emerge.demo.outofspace.chem.CRITICAL
import org.emerge.demo.outofspace.chem.FluidPhase
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.phaseAt
import org.emerge.demo.outofspace.chem.reducedDensity
import org.emerge.demo.outofspace.chem.reducedTemperature
import org.emerge.demo.outofspace.chem.SCALE
import org.emerge.demo.outofspace.chem.liquidFraction
import org.emerge.demo.outofspace.chem.saturatedLiquidDensity
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.VolumeField
import org.emerge.demo.outofspace.world.fluid.gasCapacity
import org.emerge.demo.outofspace.world.fluid.gasKelvin
import org.emerge.demo.outofspace.world.fluid.ambientGasJoules
import org.emerge.demo.outofspace.world.fluid.stepFluid
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A pool of water in a room full of nitrogen. Heat it until it boils; cool it until it comes back.
 *
 * This is the whole point of the equation of state, end to end and through the real solver rather
 * than against the pressure function alone. What it is looking for is not a number but a sequence:
 * water that starts gathered on the deck, spreads through the room when heated, and gathers again
 * when the heat is taken away — with the nitrogen present throughout, and with no code anywhere that
 * knows what boiling is.
 *
 * ## ⚠️ Parked — but for a different reason than before, and a much narrower one
 *
 * The original diagnosis here was that the liquid branch is ~30,000x stiffer than the gas and an
 * explicit scheme cannot step it. **That part is solved.** The Maxwell construction replaced the
 * falling stretch of the isotherm with the flat coexistence line it really predicts, so `dP/drho`
 * is zero across the dome instead of negative (`SaturationTest` asserts it over the whole density
 * range at eighty subcritical temperatures). A pool no longer tears itself apart, `cohesionUnpaid`
 * no longer comes back at 9.1e10, and mass is conserved to the gram throughout. The "30,000x"
 * figure was also somewhat misleading: it is the pressure swing per *fractional* density change,
 * whereas the CFL limit goes as `sqrt(dP/drho)` against *absolute* density, which for this fluid is
 * about 2,090 m/s against air's 343 — a 6x penalty, not a fatal one.
 *
 * Two further gaps were found and closed on the way, both real and neither obvious beforehand: a
 * liquid has to displace the gas sharing its cell (`liquidVolumeFraction`), and buoyancy's inverse
 * equation of state has to be clamped at close packing now that cells can be nearly solid with
 * liquid (`closePackedAirGrams`).
 *
 * ### What is actually left: transport does not know about phase
 *
 * Measured in freefall, in this 8x8 room, starting from a saturated pool of 705,854 g:
 *
 * ```
 *  tick    water in the pool tile     wet tiles
 *     0            705,854                1
 *     1            615,013                4
 *    10            267,853               13
 *    20            165,252               17
 * ```
 *
 * Smooth, monotone, no instability — the pool simply evaporates away. And it cannot be right,
 * because **saturating every one of the 36 interior tiles at this temperature takes about 19 kg of
 * vapour, and the pool has given up 540.** Evaporation is not being limited by the saturation
 * condition, for the plain reason that nothing in the transport path consults it: `advectMass`
 * moves water along the shared velocity field and `applySpeciesDrift` mixes it down its
 * concentration gradient, and neither asks whether the room is already holding all the water
 * vapour it can.
 *
 * That is the real remaining problem and it is a structural one: **every phase shares a single
 * velocity field.** A liquid and its own vapour are transported by the same flow at the same speed,
 * so a pool is advected like a gas and diffuses like a gas. Fixing it on the grid means genuine
 * multiphase transport — a separate velocity for the condensed phase, or an implicit incompressible
 * treatment of it with the gas left explicit. It is also exactly what a Lagrangian scheme gets for
 * nothing, since a particle *is* its phase and carries its own velocity.
 *
 * Under gravity it is worse again, and separately so: 705 kg of liquid pressed against a hull needs
 * an exact normal force to sit still, and the explicit projection instead turns the unbalanced
 * momentum sideways. Hence [FREEFALL] here — the phase behaviour is what these tests are about, and
 * hydrostatic equilibrium is a second unsolved problem that would mask it. With gravity on, the
 * same pool spreads across 25 tiles in 20 ticks; without, it holds its shape and thins slowly.
 *
 * The tests stay written and ignored, because the sequence they describe is still what success
 * looks like, and the target is now much closer than the original note implied.
 */
class BoilingTest {

    private val w = 8
    private val h = 8
    private val grid = Grid(w, h)
    private val edges = EdgeGrid(grid)
    private val structure: StructureMap
    private val grams = LongArray(grid.size * Species.COUNT)
    private val mx = LongArray(edges.xEdgeCount)
    private val my = LongArray(edges.yEdgeCount)
    private var joules: LongArray

    /**
     * The pool starts at exactly the density liquid water coexists with its own vapour at [COLD] —
     * read off the saturation curve rather than picked.
     *
     * That distinction is what changed when the Maxwell construction landed. The old value, 2.5×
     * critical, was a guess at "dense enough to be a liquid", and being a couple of percent off put
     * the cell 226 atmospheres out of balance, which is what tore it apart on the first tick. A
     * saturated density is not a guess: it is the one density at which a pool has no reason to do
     * anything, and starting there is the difference between simulating a pool and simulating an
     * explosion.
     */
    private val poolGrams =
        saturatedLiquidDensity(reducedTemperature(COLD, Species.Water)!!)!! *
            CRITICAL.getValue(Species.Water).gramsPerTile / org.emerge.demo.outofspace.chem.SCALE

    init {
        val machines = arrayOfNulls<Machine>(grid.size)
        for (x in 0 until w) { machines[grid.index(x, 0)] = Hull(); machines[grid.index(x, h - 1)] = Hull() }
        for (y in 0 until h) { machines[grid.index(0, y)] = Hull(); machines[grid.index(w - 1, y)] = Hull() }
        structure = StructureMap.derive(grid, machines.toList())

        val poolTile = grid.index(w / 2, h - 2)
        for (x in 1 until w - 1) for (y in 1 until h - 1) {
            val tile = grid.index(x, y)
            // The pool's own tile gets no nitrogen, and that is a physical statement rather than a
            // tidying-up. A tile full of liquid water has no room left for a full atmosphere of
            // anything else — see `liquidVolumeFraction`. Filling it with both describes a state
            // that cannot exist, and the solver correctly refuses to hold it: the cell reads about
            // 1.7 atm against neighbours at 1.0 and blows itself across the room in five ticks,
            // which for a while looked like the liquid failing to be stable and was really the
            // initial condition being impossible.
            if (tile == poolTile) continue
            val base = tile * Species.COUNT
            for (s in Species.GASES) grams[base + s.ordinal] = AirField.AMBIENT_AIR[s]
        }
        // The pool: one tile of liquid water on the deck, under all that nitrogen.
        grams[poolTile * Species.COUNT + Species.Water.ordinal] = poolGrams
        joules = ambientGasJoules(grid.size, grams)
        heatTo(COLD)
    }

    private fun step(ticks: Int) {
        repeat(ticks) { stepFluid(grid, structure, grams, mx, my, FREEFALL, joules, null) }
    }

    private fun waterAt(tile: Int): Long = grams[tile * Species.COUNT + Species.Water.ordinal]

    private fun totalWater(): Long = (0 until grid.size).sumOf { waterAt(it) }

    private fun totalNitrogen(): Long =
        (0 until grid.size).sumOf { grams[it * Species.COUNT + Species.Nitrogen.ordinal] }

    /** How many tiles hold enough water to be worth calling wet — the spread of the stuff. */
    private fun wetTiles(): Int = (0 until grid.size).count { waterAt(it) > poolGrams / 1000 }

    /** The densest water anywhere, in reduced units — high means gathered, low means dispersed. */
    private fun peakDensity(): Long =
        (0 until grid.size).maxOf { reducedDensity(waterAt(it), Species.Water, FULL, FULL) ?: 0L }

    /** How much of the wettest cell is liquid, in SCALE — the lever rule read off the real world. */
    private fun liquidFractionOfPeak(): Long {
        val tile = (0 until grid.size).maxBy { waterAt(it) }
        val kelvin = gasKelvin(joules, gasCapacity(grid.size, grams))[tile]
        return liquidFraction(
            reducedDensity(waterAt(tile), Species.Water, FULL, FULL) ?: 0L,
            reducedTemperature(kelvin, Species.Water)!!,
        ) ?: 0L
    }

    private fun phaseOfPeak(): FluidPhase {
        val tile = (0 until grid.size).maxBy { waterAt(it) }
        val kelvin = gasKelvin(joules, gasCapacity(grid.size, grams))[tile]
        return phaseAt(
            reducedDensity(waterAt(tile), Species.Water, FULL, FULL) ?: 0L,
            reducedTemperature(kelvin, Species.Water)!!,
        )
    }

    private fun heatTo(kelvin: Int) {
        val capacity = gasCapacity(grid.size, grams)
        for (tile in 0 until grid.size) if (capacity[tile] > 0L) joules[tile] = capacity[tile] * kelvin
    }

    @Ignore
    @Test
    fun `water boils when heated and gathers again when cooled`() {
        val startWater = totalWater()
        val startNitrogen = totalNitrogen()

        // ── Cold: a pool on the deck ──
        step(20)
        val cold = wetTiles()
        // Asked as a liquid *fraction* rather than as a phase label, because the label is
        // `Separating` and that is the right answer: the cell holds about 88% liquid and 12% of its
        // own vapour, which is what a pool with air above it is. Demanding `Liquid` would be
        // demanding a cell with no free surface anywhere in it.
        val poolFraction = liquidFractionOfPeak()
        assertTrue(
            poolFraction > SCALE * 80 / 100,
            "a cold pool should stay mostly liquid; it was ${poolFraction * 100 / SCALE}%",
        )
        assertTrue(cold <= 10, "a cold pool should stay gathered; it was in $cold tiles")

        // ── Heated well past boiling at this pressure ──
        heatTo(HOT)
        step(60)
        val hot = wetTiles()
        val hotPeak = peakDensity()
        assertTrue(hot > cold, "heating must spread the water; $cold tiles cold, $hot hot")
        assertTrue(
            liquidFractionOfPeak() < poolFraction / 2,
            "boiling must convert liquid to vapour; fraction went $poolFraction -> ${liquidFractionOfPeak()}",
        )

        // ── Cooled again ──
        heatTo(COLD)
        step(120)
        val cooled = wetTiles()
        assertTrue(
            peakDensity() > hotPeak,
            "cooling must gather the water back; peak was $hotPeak hot, ${peakDensity()} cooled",
        )
        assertTrue(cooled < hot, "cooling must shrink the spread; $hot tiles hot, $cooled cooled")

        // ── Throughout ──
        assertEquals(startWater, totalWater(), "water is neither created nor destroyed")
        assertEquals(startNitrogen, totalNitrogen(), "nor is the nitrogen it is sitting under")
    }

    @Ignore
    @Test
    fun `the latent heat is charged for and the energy ledger closes`() {
        // Boiling must cost energy taken from the fluid's own heat. If cohesionUnpaid is ever
        // non-zero the tick asked for more latent heat than existed, which is the discretisation
        // failing rather than the physics.
        heatTo(HOT)
        var unpaid = 0L
        repeat(60) {
            unpaid += stepFluid(grid, structure, grams, mx, my, FREEFALL, joules, null).cohesionUnpaid
        }
        assertEquals(0L, unpaid, "the latent heat should never outrun the heat available to pay it")
    }

    private companion object {

        /**
         * Freefall. The phase behaviour is what this test is about, and gravity is a *separate*
         * unsolved problem that would otherwise mask it — see the class comment.
         */
        val FREEFALL = Frac2(Frac(0L, 1), Frac(0L, 1))
        const val FULL = VolumeField.FULL

        /**
         * Cold enough that water's vapour pressure is below the room's — so a pool has no reason to
         * boil and should just sit there.
         *
         * ⚠️ **230 K, not 293 K, and that is this model being wrong rather than the test being
         * odd.** Van der Waals carries no acentric factor, so it assumes every fluid has the same
         * reduced vapour-pressure curve, and water — hydrogen-bonded and the least obedient common
         * fluid there is — comes out badly: its saturation pressure at room temperature is 4.9 atm
         * against a real 0.023, which puts its boiling point at one atmosphere near −33 °C. The
         * sequence this test describes is unaffected, because it is about a pool boiling and
         * gathering rather than about the temperature it happens at, so the temperatures are set
         * where *this* equation puts the transition. Fixing the temperatures themselves means a
         * three-constant equation of state — see `PLAN_phase_transitions.md`.
         */
        const val COLD = 230

        /** Well past boiling at this pressure: water's saturation pressure at 300 K is ~5.8 atm. */
        const val HOT = 300
    }
}
