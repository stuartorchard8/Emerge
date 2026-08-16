package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VolumeField
import org.emerge.demo.outofspace.world.millimolesOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Is a species' phase actually emergent, or is it being smuggled in?
 *
 * The claim under test is that nothing declares water to be a liquid or nitrogen to be a gas — that
 * both are just fluids, and which one behaves as which is decided entirely by how far each is from
 * its own critical temperature. The way to prove that rather than assert it is to make the two swap
 * roles: cool the nitrogen far enough and it must pool as a liquid, with nothing in the code
 * knowing that nitrogen can do that.
 *
 * Also pinned here is the compatibility claim the whole approach rests on — that van der Waals
 * reproduces the ideal gas law the solver has always used, at the densities the solver has always
 * seen. If that were not true, adopting it would silently move every existing pressure in the game.
 */
class PhaseEmergenceTest {

    private val full = VolumeField.FULL
    private val room = 293
    private val ambientWater = 0L

    @Test
    fun `ordinary air keeps the pressure it always had`() {
        // Van der Waals only departs from the ideal gas law when molecules are close enough to
        // notice each other. A tile of room-temperature air is nowhere near that, so the two must
        // agree — and the old value is the oracle, computed the way the solver has always computed
        // it rather than copied out of a run.
        for (species in listOf(Species.Nitrogen, Species.Oxygen, Species.CarbonDioxide)) {
            val mass = Stuff.AMBIENT_AIR[species]
            val ideal = millimolesOf(massFieldOf(species to mass), tile = TileIndex(0))
            val real = partialPressure(mass, species, room, full, full)!!

            val driftPerMille = (real - ideal) * 1000 / ideal
            assertTrue(
                driftPerMille in -10..10,
                "$species: ideal gas says $ideal, van der Waals says $real — ${driftPerMille}‰ apart",
            )
        }
    }

    @Test
    fun `nitrogen in a warm room has no liquid phase to fall into`() {
        // Not because anything labels it a gas. Because 293 K is 2.3x its critical temperature, so
        // the isotherm has no falling stretch anywhere on it, at any density at all.
        val tr = reducedTemperature(room, Species.Nitrogen)!!
        assertTrue(tr > SCALE, "nitrogen at room temperature should be supercritical; Tr=$tr")

        for (mass in listOf(755L, 10_000L, 200_000L)) {
            val dr = reducedDensity(mass, Species.Nitrogen, full, full)!!
            assertEquals(
                FluidPhase.Supercritical,
                phaseAt(dr, tr),
                "nitrogen at ${mass}g should have no liquid branch at room temperature",
            )
        }
    }

    @Test
    fun `water in the same warm room does have one`() {
        // Same code path, same room, opposite answer — and the only thing that differs is that
        // water's critical temperature is 647 K rather than 126 K.
        val tr = reducedTemperature(room, Species.Water)!!
        assertTrue(tr < SCALE, "water at room temperature should be subcritical; Tr=$tr")

        val dense = reducedDensity(liquidWater, Species.Water, full, full)!!
        assertEquals(FluidPhase.Liquid, phaseAt(dense, tr), "dense water in a warm room should be liquid")

        val sparse = reducedDensity(critical(Species.Water) / 100, Species.Water, full, full)!!
        assertEquals(FluidPhase.Vapour, phaseAt(sparse, tr), "a trace of water in a warm room should be vapour")
    }

    @Test
    fun `cooling the nitrogen makes it a liquid, and nothing had to be told that it could be`() {
        // The emergence proof. Nitrogen is declared Phase.Gas, is called a gas everywhere in the
        // codebase, and appears in Species.GASES. None of that reaches this calculation. Take it to
        // 80 K — below its 126 K critical point — and it lands on the liquid branch, because that is
        // where the equation puts it.
        val cold = reducedTemperature(80, Species.Nitrogen)!!
        assertTrue(cold < SCALE, "80 K should be below nitrogen's critical temperature")

        val dense = reducedDensity(saturatedLiquid(Species.Nitrogen, 80), Species.Nitrogen, full, full)!!
        assertEquals(FluidPhase.Liquid, phaseAt(dense, cold), "nitrogen at 80 K and saturated-liquid density is liquid")

        // And the same nitrogen, same density, back in a warm room, is not.
        val warm = reducedTemperature(room, Species.Nitrogen)!!
        assertEquals(FluidPhase.Supercritical, phaseAt(dense, warm))
    }

    @Test
    fun `heating the water takes its liquid phase away`() {
        // The mirror of the above, and the reason a boiler cannot simply be made hotter forever:
        // past 647 K there is no liquid water, at any pressure, and the model knows it.
        val dense = reducedDensity(liquidWater, Species.Water, full, full)!!
        assertEquals(FluidPhase.Liquid, phaseAt(dense, reducedTemperature(400, Species.Water)!!))
        assertEquals(FluidPhase.Supercritical, phaseAt(dense, reducedTemperature(700, Species.Water)!!))
    }

    @Test
    fun `boiling has to be paid for out of the heat`() {
        // Cohesion energy is negative — a liquid is a bound state — and it rises toward zero as the
        // fluid is pulled apart. That rise is the latent heat, and it has to come from somewhere.
        val liquid = reducedDensity(liquidWater, Species.Water, full, full)!!
        val vapour = reducedDensity(critical(Species.Water) / 10, Species.Water, full, full)!!

        val bound = cohesionEnergy(liquid, Species.Water, full, full)
        val free = cohesionEnergy(vapour, Species.Water, full, full)

        assertTrue(bound < 0, "a liquid must be bound; got $bound")
        assertTrue(free > bound, "pulling it apart must cost energy; bound=$bound free=$free")
        assertTrue(free <= 0, "a sparse vapour must not be a source of energy; got $free")
    }

    @Test
    fun `nitrogen overhead raises the pressure the water has to boil against`() {
        // The scenario this whole model exists to serve. The water does not know the nitrogen is
        // there; it only ever pushes against the total. So a room with more nitrogen in it is a room
        // where the water has more to push against, with nothing coupling them but the sum.
        val water = liquidWater
        val hot = 450

        // Grams, said out loud — a tile of air is about a kilogram, so this is a wisp against a room
        // packed twenty times over. As bare integers the pair became 200 ng and 20 µg when the mass
        // unit moved: both round to no millimoles at all, both give the same pressure, and the test
        // fails claiming nitrogen does not push, which is not what went wrong.
        val thin = totalPressure(mapOf(Species.Water to water, Species.Nitrogen to 200L * Budget.GRAM), hot)
        val thick = totalPressure(mapOf(Species.Water to water, Species.Nitrogen to 20_000L * Budget.GRAM), hot)

        assertTrue(thick > thin, "more nitrogen must mean more pressure to expand against; $thin then $thick")
    }

    // ── helpers ──

    private fun critical(species: Species): Long = CRITICAL.getValue(species).massPerTile

    /**
     * A tile of liquid water, at two and a half times critical density.
     *
     * Not the real thing, which is 3.1x critical and past the close-packing limit van der Waals
     * imposes — see [CLOSE_PACKED]. The stable liquid branch at room temperature runs from about
     * 2.1x to 3.0x, so this sits in the middle of the window the model actually has. Twice critical
     * is *not* liquid at this temperature: it is still inside the unstable band, which is a fact
     * about the equation rather than about water, and worth knowing before reaching for a round
     * number.
     */
    /**
     * Water dense enough to be unambiguously on the liquid branch at [room] — taken off the
     * saturation curve rather than guessed at.
     *
     * This used to be a flat 2.5x critical density, which was a reasonable guess and is wrong by a
     * hair: the saturated liquid branch at 293 K sits at 2.5212x, so 2.5x is *inside* the dome and
     * the honest answer for it is [FluidPhase.Separating] — a cell holding mostly liquid and a
     * little of its own vapour. Asking the curve where the branch actually is removes the guess, and
     * it is the same reason the pool in `BoilingTest` is started from this function and not from a
     * round number.
     */
    private fun saturatedLiquid(species: Species, kelvin: Int): Long {
        val tr = reducedTemperature(kelvin, species)!!
        val branch = saturatedLiquidDensity(tr) ?: error("$species is supercritical at $kelvin K")
        // A tenth of a percent past the branch, so that integer rounding on the way back through
        // reducedDensity cannot land it on the boundary and read as Separating again. Applied to the
        // reduced density rather than to the mass, so the whole conversion is the simulation's own.
        //
        // ⚠️ It used to be `branch * massPerTile / SCALE` written out here, which is the multiply
        // [massAtReducedDensity] exists to own — and this copy carried the overflow four months
        // after the production copies were found, because a test that recomputes what it is testing
        // against inherits its bugs and reports them as failures of the thing under test. At one
        // microgram per unit it wrapped to a negative mass, which became a negative temperature and
        // surfaced as an ArrayIndexOutOfBoundsException in ValveTest.
        return massAtReducedDensity(branch * 1001 / 1000, species, full, full)!!
    }

    private val liquidWater: Long = saturatedLiquid(Species.Water, room)

    private fun totalPressure(mix: Map<Species, Long>, kelvin: Int): Long =
        mix.entries.sumOf { (s, g) -> partialPressure(g, s, kelvin, full, full) ?: 0L }

    /** A one-tile mass field, so the ideal-gas oracle can be asked the way the solver asks it. */
    private fun massFieldOf(vararg amounts: Pair<Species, Long>): MassArray {
        val out = MassArray(1)
        for ((s, g) in amounts) out[MassIndex(TileIndex(0),s)] = g
        return out
    }
}
