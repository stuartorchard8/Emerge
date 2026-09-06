package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The competition, and the fact that nothing in it knows what water is.**
 *
 * Increment 1 of `PLAN_electrochemistry.md`. `ElectrolyzerTest` proves the machine still behaves;
 * this proves the *reason* it behaves — that `2 H₂O → 2 H₂ + O₂` is not written down anywhere and
 * is what the rule does to a charge that happens to be water.
 *
 * ⚠️ **There is no dissolution model yet.** A charge with copper in it is treated as having copper
 * available to the cathode; whether it is dissolved or sitting in the bottom of the tank is
 * increment 3's question. What is being tested here is the *ordering*, which does not change.
 */
class CellTest {

    private val gram = 1_000L

    private fun charge(vararg parts: Pair<Species, Long>): Mixture =
        Mixture.of(*parts.map { it.first to it.second * gram }.toTypedArray(), energy = 0L)

    private val water = charge(Species.Water to 1_000_000L)

    // ── Water, which is what the machine did before and does now ─────────────

    @Test
    fun aChargeOfWaterSplitsBecauseNothingElseIsThere() {
        val action = assertNotNull(cellAction(water, 1500))
        assertEquals(Species.Hydrogen, action.cathodeCouple.principal)
        assertEquals(Species.Water, action.anodeCouple.principal)
        assertEquals(1230, action.requiredMillivolts)
        assertEquals(listOf(Species.Water to 2), action.consumes)
        assertEquals(listOf(Species.Hydrogen to 2), action.cathodeProducts)
        assertEquals(listOf(Species.Oxygen to 1), action.anodeProducts)
    }

    /** ⭐ The protons cancel, so splitting water leaves no acid behind. */
    @Test
    fun splittingWaterLeavesNoSurplusAcid() {
        assertEquals(0, cellAction(water, 1500)!!.surplusProtons)
    }

    @Test
    fun belowItsOwnPotentialWaterDoesNothing() {
        assertNull(cellAction(water, 1229))
        assertNotNull(cellAction(water, 1230))
    }

    // ── ⭐ The two claims the arc rests on, at the level of a real charge ─────

    /**
     * **Copper plates and hydrogen does not**, on the strength of +340 being above 0.
     *
     * Nothing branches on copper. The same three lines that split water pick a different cathode
     * because a different couple is now the highest one available.
     */
    @Test
    fun copperInTheChargeIsPlatedInsteadOfHydrogen() {
        val action = assertNotNull(cellAction(charge(Species.Water to 1000L, Species.Copper to 10L), 1500))
        assertEquals(Species.Copper, action.cathodeCouple.principal)
        assertEquals(890, action.requiredMillivolts, "copper is cheaper than splitting water")
        assertTrue(action.cathodeProducts.any { it.first == Species.Copper }, "the copper should plate")
        assertTrue(action.cathodeProducts.none { it.first == Species.Hydrogen }, "no hydrogen from a copper cell")
    }

    /**
     * ⭐ **The acid the anode made survives — and it is why copper cannot run until §6 is answered.**
     *
     * The cathode eats no protons, so all four of the anode's are left over. They weigh a gram
     * apiece and there is nowhere to put them: an acid needs an anion, and whether the game grows a
     * `Sulfate` species is `PLAN_electrochemistry.md` §6's open question.
     *
     * ⚠️ So [electrolyse] **refuses the pass**, and that is arithmetic rather than caution: the draw
     * is 2 Cu + 2 H₂O = 164 against products of 2 Cu + O₂ = 160. Running it would spread 164 over a
     * 128:32 split and hand back copper and oxygen that each weigh slightly too much.
     */
    @Test
    fun aCopperCellComputesItsAcidAndThenRefusesToRun() {
        val copperCharge = charge(Species.Water to 1000L, Species.Copper to 10L)
        val action = cellAction(copperCharge, 1500)!!
        assertEquals(4, action.surplusProtons, "the regenerated acid of the plan's section 2.4")
        assertEquals(164L, action.consumedMass, "2 Cu + 2 H2O, in formula-unit mass")
        assertNull(electrolyse(copperCharge, action, copperCharge.total), "nowhere to put the acid yet")
    }

    /**
     * ⭐ **Aluminium is not won from water at any voltage, and nothing forbids it.**
     *
     * −1660 loses the cathode to hydrogen's 0, so the charge splits water and the aluminium sits
     * there. This is the whole scope of the aqueous arc, asserted against a charge rather than
     * against the table.
     */
    @Test
    fun aluminiumNeverPlatesFromAnAqueousCharge() {
        for (applied in listOf(1500, 5000, 50_000)) {
            val action = assertNotNull(cellAction(charge(Species.Water to 1000L, Species.Aluminum to 10L), applied))
            assertEquals(
                Species.Hydrogen,
                action.cathodeCouple.principal,
                "at $applied mV the cathode should still take hydrogen, not aluminium",
            )
        }
    }

    /** Zinc and iron sit on the same wrong side of the line, for the same reason. */
    @Test
    fun theMetalsBelowHydrogenAllLoseTheirCathode() {
        for (metal in listOf(Species.Zinc, Species.Iron, Species.Nickel, Species.Tin, Species.Lead)) {
            val action = assertNotNull(cellAction(charge(Species.Water to 1000L, metal to 10L), 3000))
            assertEquals(Species.Hydrogen, action.cathodeCouple.principal, "$metal should not plate from water")
        }
    }

    /** Silver outranks copper, so a charge holding both plates the silver. */
    @Test
    fun theHighestCoupleWinsWhenTwoMetalsCompete() {
        val action = assertNotNull(
            cellAction(charge(Species.Water to 1000L, Species.Copper to 10L, Species.Silver to 10L), 1500),
        )
        assertEquals(Species.Silver, action.cathodeCouple.principal)
    }

    // ── The arithmetic ───────────────────────────────────────────────────────

    /**
     * Mass is conserved exactly across a pass, and the remainder is the charge's.
     *
     * ⚠️ **Whole passes only.** A partial pass would have to round its stoichiometry, and that is
     * where a gram gets invented once a reaction has more than one reagent.
     */
    @Test
    fun aPassConservesMassToTheMicrogram() {
        val action = cellAction(water, 1500)!!
        val made = assertNotNull(electrolyse(water, action, water.total))
        assertEquals(made.consumed.total, made.cathode.total + made.anode.total)
        assertTrue(made.consumed.total <= water.total, "consumed more than was there")
        assertTrue(water.total - made.consumed.total < action.consumedMass, "a whole pass was left unrun")
    }

    /** The 1:8 split the old hand-written row produced, now a consequence of the molar masses. */
    @Test
    fun waterStillSplitsOneToEightByMass() {
        val action = cellAction(water, 1500)!!
        val made = electrolyse(water, action, water.total)!!
        assertEquals(made.cathode.total * 8L, made.anode.total)
    }

    @Test
    fun aChargeTooSmallForOneWholePassDoesNothing() {
        val crumb = Mixture.of(Species.Water to 10L, energy = 0L)
        val action = cellAction(crumb, 1500)!!
        assertNull(electrolyse(crumb, action, crumb.total), "10 units is less than one 36-unit pass")
    }
}
