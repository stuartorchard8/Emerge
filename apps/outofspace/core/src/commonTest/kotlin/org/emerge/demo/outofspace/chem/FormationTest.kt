package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The oracle for every reaction's energy, and the audit that produced it.
 *
 * ⛔ **The reason this file exists, stated as a measurement.** Before [FORMATION_ENTHALPY] every row
 * of [REACTIONS] carried a hand-typed `enthalpyPerKg`, and the only check on any of them was a test
 * naming *nine* rows by string. Scoring all twenty-eight against formation enthalpies reproduced
 * eighteen of them to within 2 kJ — which is the evidence that they were computed this way once, by
 * hand — and found ten that had drifted, two of them by a factor of three. Every one of those ten
 * passed every test in the suite.
 *
 * A reaction's enthalpy is now [hessEnthalpyKJ]'s answer and cannot be anything else. What this file
 * checks is the layer below: that the *table* says what a textbook says.
 */
class FormationTest {

    private fun Reaction.formula(): String =
        reagents.joinToString(" + ") { "${it.second} ${it.first}" } + " -> " +
            products.joinToString(" + ") { "${it.second} ${it.first}" }

    /** kJ per reaction as written, recovered from the per-kilogram figure the row actually carries. */
    private fun Reaction.kJPerReaction(): Long =
        enthalpyPerKg / kJPerMolAt(principalUnits.toLong() * principal.molarMass)

    // ── What every row is worth ──────────────────────────────────────────────

    /**
     * Every reaction in the game, priced, in one place.
     *
     * ⚠️ **This is a transcription of the whole table on purpose.** It is the one test that would
     * notice a formation enthalpy being edited — change `Species.Quartz to -911` and the silicon row
     * moves, and nothing else in the suite has an opinion. Adding a row means adding a line here,
     * which is the cost of every row's energy being checkable and is meant to be paid.
     */
    @Test
    fun everyReactionIsWorthWhatItsFormationEnthalpiesSay() {
        val expected = mapOf(
            // ── Gas-phase and cracking ──
            "2 Ammonia -> 1 Nitrogen + 3 Hydrogen" to 92L,
            "1 CarbonDioxide + 1 Carbon -> 2 CarbonMonoxide" to 172L,
            "100 Algae + 6 Water + 6 CarbonDioxide -> 101 Algae + 6 Oxygen" to 2545L,
            // ── The fires ──
            "1 Methane + 2 Oxygen -> 1 CarbonDioxide + 2 Water" to -803L,
            "2 Hydrogen + 1 Oxygen -> 2 Water" to -484L,
            "2 CarbonMonoxide + 1 Oxygen -> 2 CarbonDioxide" to -566L,
            "2 HydrogenSulfide + 3 Oxygen -> 2 SulfurDioxide + 2 Water" to -1038L,
            "4 Ammonia + 3 Oxygen -> 2 Nitrogen + 6 Water" to -1268L,
            "1 Sulfur + 1 Oxygen -> 1 SulfurDioxide" to -297L,
            // ── Burning in the room's air ──
            "1 Carbon + 1 Oxygen -> 1 CarbonDioxide" to -394L,
            "4 Iron + 3 Oxygen -> 2 Hematite" to -1648L,
            "1 Steel + 1 Oxygen -> 99 Iron + 1 CarbonDioxide" to -394L,
            // ── Making what the vessel is built of ──
            "99 Iron + 1 Carbon -> 1 Steel" to 0L,
            "11 Periclase + 6 Quartz -> 1 Firebrick" to 0L,
            // ── Thermal decomposition ──
            "1 Calcite -> 1 Lime + 1 CarbonDioxide" to 178L,
            "1 Magnesite -> 1 Periclase + 1 CarbonDioxide" to 117L,
            "1 Serpentine -> 1 Forsterite + 1 Enstatite + 2 Water" to 159L,
            "1 Pyrite -> 1 Troilite + 1 Sulfur" to 78L,
            "6 Hematite -> 4 Magnetite + 1 Oxygen" to 472L,
            "1 Algae -> 1 Methane + 1 CarbonDioxide + 4 Water + 4 Carbon" to -166L,
            // ── Reduction ──
            "1 Quartz + 2 Carbon -> 1 Silicon + 2 CarbonMonoxide" to 689L,
            "2 Periclase + 1 Silicon -> 2 Magnesium + 1 Quartz" to 293L,
            "1 Forsterite + 4 Carbon -> 2 Periclase + 1 Silicon + 2 Carbon + 2 CarbonMonoxide" to 748L,
            "1 Enstatite + 3 Carbon -> 1 Periclase + 1 Silicon + 1 Carbon + 2 CarbonMonoxide" to 723L,
            "1 Fayalite + 2 Carbon -> 2 Iron + 1 Silicon + 2 CarbonDioxide" to 691L,
            "2 Ferrosilite + 3 Carbon -> 2 Iron + 2 Silicon + 3 CarbonDioxide" to 1208L,
            "1 Ilmenite + 1 Carbon -> 1 Iron + 1 Rutile + 1 CarbonMonoxide" to 182L,
            "1 Rutile + 2 Magnesium -> 1 Titanium + 2 Periclase" to -260L,
            // ── Roasting, and the oxide ores ──
            "1 Argentite + 1 Oxygen -> 2 Silver + 1 SulfurDioxide" to -265L,
            "1 Cassiterite + 2 Carbon -> 1 Tin + 2 CarbonMonoxide" to 359L,
            "1 Pyrolusite + 2 Carbon -> 1 Manganese + 2 CarbonMonoxide" to 298L,
            "1 Chromite + 4 Carbon -> 1 Iron + 2 Chromium + 4 CarbonMonoxide" to 1001L,
            "1 Galena + 1 Oxygen -> 1 Lead + 1 SulfurDioxide" to -197L,
            "1 Stibnite + 3 Iron -> 2 Antimony + 3 Troilite" to -125L,
            "1 Bismuthinite + 3 Iron -> 2 Bismuth + 3 Troilite" to -157L,
            "2 Molybdenite + 7 Oxygen -> 2 Molybdite + 4 SulfurDioxide" to -2208L,
            "1 Molybdite + 3 Hydrogen -> 1 Molybdenum + 3 Water" to 19L,
            "2 Sphalerite + 3 Oxygen -> 2 Zincite + 2 SulfurDioxide" to -882L,
            "1 Zincite + 1 Carbon -> 1 Zinc + 1 CarbonMonoxide" to 239L,
            "2 Greenockite + 3 Oxygen -> 2 Monteponite + 2 SulfurDioxide" to -786L,
            "1 Monteponite + 1 Carbon -> 1 Cadmium + 1 CarbonMonoxide" to 147L,
            "1 Cinnabar + 1 Oxygen -> 1 Mercury + 1 SulfurDioxide" to -239L,
            // ── The iron oxides, dolomite and copper ──
            "1 Hematite + 3 Carbon -> 2 Iron + 3 CarbonMonoxide" to 491L,
            "1 Magnetite + 4 Carbon -> 3 Iron + 4 CarbonMonoxide" to 674L,
            "1 Wustite + 1 Carbon -> 1 Iron + 1 CarbonMonoxide" to 161L,
            "2 Troilite + 3 Oxygen -> 2 Wustite + 2 SulfurDioxide" to -938L,
            "1 Dolomite -> 1 Lime + 1 Periclase + 2 CarbonDioxide" to 301L,
            "4 Chalcopyrite + 13 Oxygen -> 4 Tenorite + 2 Hematite + 8 SulfurDioxide" to -3892L,
            "1 Tenorite + 1 Carbon -> 1 Copper + 1 CarbonMonoxide" to 46L,
        )

        val seen = mutableSetOf<String>()
        for (reaction in REACTIONS) {
            val formula = reaction.formula()
            val kJ = expected[formula]
            assertNotNull(kJ, "$formula is in REACTIONS and is not priced here")
            seen.add(formula)
            assertEquals(kJ, reaction.kJPerReaction(), "$formula is worth the wrong number of kJ")
        }
        // ⛔ A row renamed or removed must fail here rather than quietly stop being checked — the
        // failure mode of every expectation keyed by a string.
        assertEquals(expected.keys, seen, "these rows are priced here but are not in REACTIONS")
    }

    /**
     * The ten rows whose energy **changed** when the table stopped being hand-typed, and by how much.
     *
     * ⚠️ **Kept as a record rather than as a check of the arithmetic**, which the test above already
     * owns. This is the balance change, written down: iron-from-olivine got three times more
     * expensive in heat, and algae pyrolysis turned out to be exothermic rather than endothermic. If
     * one of these ever needs revisiting, this is the list of what moved and what it moved from.
     *
     * ⛔ **Four of these are roundings and two are genuine unknowns.** Methane, hydrogen sulfide,
     * ammonia-burning, magnesite, quartz, forsterite, ilmenite and rutile all moved by 1–2 kJ, which
     * is the difference between a textbook's rounded figure and this table's — noise. Serpentine
     * (−91) and pyrite (+38) are **not** noise: they are entries whose ΔH_f nobody has sourced
     * properly, and they are the two rows most worth a reference check. See [FORMATION_ENTHALPY] on
     * sulfur, which is the open question behind pyrite.
     */
    @Test
    fun theShiftsAwayFromTheHandWrittenTableAreTheOnesWeMeantToLand() {
        val wasWorth = mapOf(
            "100 Algae + 6 Water + 6 CarbonDioxide -> 101 Algae + 6 Oxygen" to 2803L,
            "1 Methane + 2 Oxygen -> 1 CarbonDioxide + 2 Water" to -802L,
            "2 HydrogenSulfide + 3 Oxygen -> 2 SulfurDioxide + 2 Water" to -1036L,
            "4 Ammonia + 3 Oxygen -> 2 Nitrogen + 6 Water" to -1267L,
            "1 Magnesite -> 1 Periclase + 1 CarbonDioxide" to 118L,
            "1 Serpentine -> 1 Forsterite + 1 Enstatite + 2 Water" to 250L,
            "1 Pyrite -> 1 Troilite + 1 Sulfur" to 40L,
            "1 Algae -> 1 Methane + 1 CarbonDioxide + 4 Water + 4 Carbon" to 65L,
            "1 Quartz + 2 Carbon -> 1 Silicon + 2 CarbonMonoxide" to 690L,
            "1 Forsterite + 4 Carbon -> 2 Periclase + 1 Silicon + 2 Carbon + 2 CarbonMonoxide" to 750L,
            "1 Enstatite + 3 Carbon -> 1 Periclase + 1 Silicon + 1 Carbon + 2 CarbonMonoxide" to 890L,
            "1 Fayalite + 2 Carbon -> 2 Iron + 1 Silicon + 2 CarbonDioxide" to 210L,
            "2 Ferrosilite + 3 Carbon -> 2 Iron + 2 Silicon + 3 CarbonDioxide" to 480L,
            "1 Ilmenite + 1 Carbon -> 1 Iron + 1 Rutile + 1 CarbonMonoxide" to 180L,
            "1 Rutile + 2 Magnesium -> 1 Titanium + 2 Periclase" to -259L,
        )
        val byFormula = REACTIONS.associateBy { it.formula() }
        for ((formula, old) in wasWorth) {
            val reaction = byFormula[formula]
            assertNotNull(reaction, "$formula has left REACTIONS; drop it from this record")
            assertTrue(
                reaction.kJPerReaction() != old,
                "$formula is back at its hand-written $old kJ — this record is now wrong",
            )
        }
    }

    // ── The table itself ─────────────────────────────────────────────────────

    /**
     * ⛔ **An element in its standard state is zero, and nothing else may be.** This is what a
     * formation enthalpy is measured *against*, so an element carrying a value would make every row
     * that touches it wrong by that amount, in a direction no other test can see.
     */
    @Test
    fun everyElementIsZeroByDefinition() {
        for (species in FORMATION_ENTHALPY.keys) {
            // An element is a species [MINERALS] has no formula for — it is not made of anything.
            if (MINERALS.containsKey(species)) continue
            if (species == Species.Steel || species == Species.Firebrick) continue // defined, see the table
            assertEquals(
                0,
                FORMATION_ENTHALPY[species],
                "$species is an element and its formation enthalpy is zero by definition",
            )
        }
    }

    /**
     * Every species any reaction touches has a sourced formation enthalpy.
     *
     * ⚠️ Redundant with [Reaction]'s own `error`, which refuses to construct a row it cannot price —
     * and worth having anyway, because that one fails at class-load with a stack trace pointing at
     * whatever happened to touch [REACTIONS] first. This one names the species.
     */
    @Test
    fun everySpeciesInAReactionHasAFormationEnthalpy() {
        for (reaction in REACTIONS) {
            for ((species, _) in reaction.reagents + reaction.products) {
                assertNotNull(
                    formationEnthalpyOf(species),
                    "$species appears in ${reaction.formula()} with no formation enthalpy",
                )
            }
        }
    }

    /**
     * The audit behind [FORMATION_ENTHALPY]'s reference-phase rule: **where does the choice bite?**
     *
     * A species that can be a fluid is quoted as a gas, and the condensed and gas baselines differ
     * by [vaporisationHeat] — which is zero at and above the critical point, where there is no phase
     * change left to pay for. So a row running hot enough that its fluid participants are
     * supercritical is safe whatever we decided.
     *
     * ### ⛔ Only a row that can run in the AIR can get this wrong
     *
     * `AmbientChemistry.react` gives each store its own reaction — *"a store reacts with what it is
     * holding, and with nothing else"* (Stu, 2026-09-04). A row runs in the air only if its
     * **principal** can be in the air, because the principal is what the pass looks for; and the
     * cargo stores have no cohesion ledger, so their baseline is the condensed phase and a
     * solid-quoted enthalpy is simply *correct* there.
     *
     * ⚠️ **So a row whose principal is not a fluid is unconditionally safe**, however cold it runs
     * and whatever it makes. That is why zinc, cadmium and mercury are absent from this set despite
     * all three being made hundreds of kelvin below their critical points: their metallurgy is
     * `Zincite + C`, `Monteponite + C` and `Cinnabar + O₂`, and not one of those principals can be
     * in a room. The metal lands in the buffer it was made in and leaves on a belt.
     *
     * ⛔ **What is left is two rows, and they are the genuinely ambiguous ones**: a fire whose fuel
     * is a fluid can run in a room *or* in a packet, so one number has to serve both.
     *
     * - **Sulfur** (Tc 1314 K) burning at 505 K. Quoted as a solid, so a sulfur fire in a *room*
     *   under-releases by roughly the heat of vaporisation. See [FORMATION_ENTHALPY].
     * - **Water** (Tc 647 K) made by hydrogen sulfide burning at 533 K — the one fire cold enough
     *   that its water could be liquid. Every other fire runs above 647 K.
     */
    @Test
    fun theReferencePhaseOnlyMattersForSpeciesNamedHere() {
        val known = setOf(Species.Sulfur, Species.Water)
        val bites = mutableSetOf<Species>()
        for (reaction in REACTIONS) {
            // Only a row the air can hold the principal of ever reacts in the air.
            if (!reaction.principal.isFluid) continue
            for ((species, _) in reaction.reagents + reaction.products) {
                val critical = CRITICAL[species] ?: continue
                if (reaction.onsetKelvin < critical.kelvin) bites.add(species)
            }
        }
        assertEquals(
            known,
            bites,
            "the set of species reacting in the air below their critical point has changed — " +
                "the reference-phase question now bites for these, and FORMATION_ENTHALPY must say so",
        )
    }
}
