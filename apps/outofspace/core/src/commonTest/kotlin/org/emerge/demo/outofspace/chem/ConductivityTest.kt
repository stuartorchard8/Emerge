package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guard on [electricalConductivityOf] — increment 0 of `PLAN_power_network.md`.
 *
 * ⛔ **A derived number is only worth having if something scores it against reality.** This is
 * `FormationTest`'s job for energy and `MineralTest`'s for molar mass; a wire's resistance now has
 * the same. Every figure in the right-hand column below is a measured room-temperature conductivity,
 * and nothing in the game states one.
 */
class ConductivityTest {

    /** Measured σ at 20 °C, in S/m. The oracle this file exists to check the derivation against. */
    private val measured: Map<Species, Long> = mapOf(
        Species.Silver to 63_000_000L,
        Species.Copper to 59_600_000L,
        Species.Gold to 45_200_000L,
        Species.Aluminum to 37_700_000L,
        Species.Zinc to 16_900_000L,
        Species.Nickel to 14_300_000L,
        Species.Iron to 10_000_000L,
        Species.Platinum to 9_430_000L,
        Species.Tin to 9_170_000L,
        Species.Lead to 4_550_000L,
        Species.Tungsten to 17_900_000L,
        Species.Titanium to 2_380_000L,
        Species.Mercury to 1_040_000L,
        Species.Bismuth to 770_000L,
        Species.Manganese to 694_000L,
    )

    /** What a wire would plausibly be drawn from: the ones the derivation has to be *good* at. */
    private val structural = listOf(
        Species.Silver, Species.Copper, Species.Gold, Species.Aluminum,
        Species.Zinc, Species.Nickel, Species.Iron, Species.Platinum, Species.Tin, Species.Lead,
    )

    private fun errorPercent(s: Species): Long {
        val got = electricalConductivityOf(s)
        val want = measured.getValue(s)
        return (got - want) * 100L / want
    }

    /**
     * ⭐ **The ten a wire is actually made of, within 15% of measurement, from data nobody added.**
     *
     * Tin is exact to three figures. That is Wiedemann–Franz holding, not a fit.
     */
    @Test
    fun theStructuralConductorsAreWithinFifteenPercentOfMeasurement() {
        for (s in structural) {
            val err = errorPercent(s)
            assertTrue(err in -15L..15L, "$s derives to ${electricalConductivityOf(s)} S/m, off by $err%")
        }
    }

    /**
     * ⚠️ **The poor metals reach 60%, and that is the law being honest rather than the arithmetic
     * being wrong.** Wiedemann–Franz assumes electrons carry all the heat; in manganese, bismuth and
     * tungsten a real share of it goes by lattice vibration instead, so the derived σ comes out
     * high. Manganese is the worst in the table at +57%.
     *
     * They are checked at a looser bound rather than excluded, because a wire made of manganese
     * should still be *bad* by roughly the right amount.
     */
    @Test
    fun evenTheWorstMetalIsWithinSixtyPercent() {
        for (s in measured.keys) {
            val err = errorPercent(s)
            assertTrue(err in -60L..60L, "$s derives to ${electricalConductivityOf(s)} S/m, off by $err%")
        }
    }

    /** The ordering is what a player actually feels: copper beats aluminium beats iron beats titanium. */
    @Test
    fun theOrderingMatchesTheRealMaterials() {
        val ladder = listOf(
            Species.Silver, Species.Copper, Species.Gold, Species.Aluminum,
            Species.Zinc, Species.Iron, Species.Tin, Species.Lead, Species.Titanium, Species.Mercury,
        )
        for (i in 0 until ladder.size - 1) {
            assertTrue(
                electricalConductivityOf(ladder[i]) > electricalConductivityOf(ladder[i + 1]),
                "${ladder[i]} should out-conduct ${ladder[i + 1]}",
            )
        }
    }

    // ── What is and is not a conductor ───────────────────────────────────────

    /**
     * ⛔ **Iron ore is not wire**, and this is the test that would catch the tempting shortcut.
     *
     * `Material.kt`'s `METALLIC_CONDUCTION_MILLIWATTS` calls a solid a metal above 10 W/m/K, and
     * hematite is 11.3, cassiterite 12, pyrite 20 and thorianite 10. Deriving conductivity from that
     * threshold would let a vessel draw a bus bar out of unreduced ore.
     */
    @Test
    fun mineralsAboveTheThermalMetalLineStillDoNotConduct() {
        for (rock in listOf(Species.Hematite, Species.Cassiterite, Species.Pyrite, Species.Thorianite)) {
            assertEquals(0L, electricalConductivityOf(rock), "$rock is a mineral and must not conduct")
            assertTrue(rock.milliWattsPerMetreKelvin >= 10_000, "this test is pointless if $rock moved")
        }
    }

    /** And the converse: three real metals sit *below* that line and must still conduct. */
    @Test
    fun metalsBelowTheThermalMetalLineStillConduct() {
        for (metal in listOf(Species.Mercury, Species.Manganese, Species.Bismuth)) {
            assertTrue(electricalConductivityOf(metal) > 0L, "$metal is a metal and must conduct")
            assertTrue(metal.milliWattsPerMetreKelvin < 10_000, "this test is pointless if $metal moved")
        }
    }

    /**
     * ⚠️ **Graphite conducts heat better than iron and must not conduct charge like it.**
     *
     * 130 W/m/K would derive to 1.8e7 S/m, sixty times what graphite manages, because its heat rides
     * on phonons and the law's premise fails. The single most likely way for this file to go wrong
     * is somebody noticing carbon's conductivity and "fixing" its omission.
     */
    @Test
    fun carbonDoesNotConductDespiteCarryingHeatLikeAMetal() {
        assertTrue(Species.Carbon.milliWattsPerMetreKelvin > Species.Iron.milliWattsPerMetreKelvin)
        assertEquals(0L, electricalConductivityOf(Species.Carbon))
    }

    /** Steel is the ship's fabric and a metal wearing a formula, so it is the one alloy exception. */
    @Test
    fun steelConductsThoughItHasAFormula() {
        assertTrue(MINERALS[Species.Steel] != null, "steel is a compound in the table")
        assertTrue(electricalConductivityOf(Species.Steel) > 0L, "steel must still conduct")
        assertEquals(0L, electricalConductivityOf(Species.Firebrick), "firebrick must not")
    }

    /** Rock, ice and air conduct nothing, which is most of the table. */
    @Test
    fun theRestOfTheTableIsAnInsulator() {
        for (s in listOf(
            Species.Forsterite, Species.Quartz, Species.Water, Species.Calcite,
            Species.Halite, Species.Oxygen, Species.Methane, Species.Serpentine,
        )) {
            assertEquals(0L, electricalConductivityOf(s), "$s must not conduct")
        }
    }

    /** Every conductor has a conductivity to derive from, or the derivation silently yields zero. */
    @Test
    fun everyConductorHasAThermalConductivityToDeriveFrom() {
        val mute = Species.ALL.filter { conductsElectrically(it) && it.milliWattsPerMetreKelvin <= 0 }
        assertTrue(mute.isEmpty(), "these conduct but state no thermal conductivity: $mute")
    }
}
