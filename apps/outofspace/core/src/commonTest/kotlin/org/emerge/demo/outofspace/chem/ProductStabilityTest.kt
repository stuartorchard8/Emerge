package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **A row's products must survive the temperature that made them**, which is the rule that says
 * whether a reaction's product list is the truth or only the first half of it.
 *
 * ### The rule
 *
 * > *Each row is one decomposition, and its products must not react with one another at or below
 * > that decomposition's own temperature — because if they do, those further products are what the
 * > row actually yields.* (Stu, 2026-09-11)
 *
 * ⛔ **It is a statement about chemistry, not about scheduling.** A furnace holding a charge at
 * 2050 K is not running one reaction; it is holding matter at a temperature, and every row in
 * [REACTIONS] whose onset that temperature clears will fire on whatever is in the store. So a row
 * whose products are another row's whole reagent list, at a lower onset, has not described an
 * outcome — it has described an intermediate and then stopped writing. The player is told they will
 * get X and they get Y, with nothing anywhere saying why.
 *
 * ### The bug it was written from
 *
 * ⚠️ **Smelting forsterite, 2026-09-11.** `Mg₂SiO₄ + 4 C → 2 MgO + Si + 2 C + 2 CO` fired at 1800 K
 * and handed its magnesia and silicon straight to `2 MgO + Si → 2 Mg + SiO₂`, which claimed to start
 * at **1500 K** — three hundred kelvin *below* the row that fed it. The quartz that came back then
 * met the leftover periclase and fired to brick at 1700 K. Three rows deep, and what came out of a
 * forsterite smelt was magnesium and firebrick.
 *
 * Two separate faults, and the rule catches the shape rather than either cause: the silicothermic
 * onset was a **vacuum** number used at one atmosphere, and the silicate rows were stopping at an
 * intermediate that no temperature actually produces.
 *
 * ### What it does not check
 *
 * ⚠️ **Leftover *reagents* are not products, and this test does not treat them as such.** A real
 * charge is mixed to a ratio and converts a fraction of itself, so unreacted carbon does sit beside
 * the products, and three pairs in the table do react that way: carbothermic chromite carburising
 * its own iron into steel above 1811 K, burning steel's iron rusting in the oxygen that burnt it,
 * and cooked algae photosynthesising in the water and CO₂ it gave off. **All three are physically
 * correct** — that is what those charges do — so they are not failures and the rule is deliberately
 * narrower than "nothing may react with anything".
 *
 * ⚠️ **It is a stoichiometric check and knows nothing about rates.** Two rows at the same onset are
 * treated as contemporaneous, which is why [violations] compares with `<=`.
 */
class ProductStabilityTest {

    private fun Reaction.formula(): String =
        reagents.joinToString(" + ") { "${it.second} ${it.first}" } + " -> " +
            products.joinToString(" + ") { "${it.second} ${it.first}" }

    /**
     * Every pair where one row's product list contains another row's entire reagent list, and the
     * second row is no hotter than the first.
     */
    private fun violations(): List<String> = buildList {
        for (maker in REACTIONS) {
            val made = maker.products.mapTo(mutableSetOf()) { it.first }
            for (eater in REACTIONS) {
                if (eater === maker) continue
                if (eater.onsetKelvin > maker.onsetKelvin) continue
                if (!eater.reagents.all { it.first in made }) continue
                add(
                    "${maker.formula()} @${maker.onsetKelvin} K\n" +
                        "    is eaten whole by  ${eater.formula()} @${eater.onsetKelvin} K",
                )
            }
        }
    }

    /**
     * ⛔ **Zero, and a new row that breaks it is a row whose products are wrong** — not a row that
     * needs a hotter onset to dodge the check. The fix is to write down what the charge actually
     * becomes, which is what the two silicate reduction rows now do.
     */
    @Test
    fun `no reaction hands another reaction its whole charge at or below its own onset`() {
        assertEquals(
            emptyList(),
            violations(),
            "a row's products are another row's reagents, at or below the temperature that made " +
                "them — so those products are not what comes out",
        )
    }

    /**
     * ⭐ **The silicate rows go to the metal, and the reason is thermodynamic rather than a dodge.**
     *
     * Stopping forsterite at magnesia and silicon costs 748 kJ against ΔS of 362 J/K, so ΔG reaches
     * zero at 2069 K. Taking it all the way to magnesium costs 2024 kJ against ΔS of 989 J/K —
     * 2047 K, **lower**, because four CO and two magnesium vapours carry the entropy. There is no
     * window in which the partial products are the stable ones, which is why this is not a choice.
     *
     * Pinned here because the onsets are the load-bearing half of the rule above: if somebody cools
     * these rows back under the silicothermic one, the check passes on stoichiometry and the bug
     * comes back.
     */
    @Test
    fun `the magnesium routes are ordered as their Ellingham crossings say`() {
        fun onsetOf(formula: String): Int =
            REACTIONS.first { it.formula() == formula }.onsetKelvin

        // Silicothermic reduction needs 2206 K at one atmosphere; it is the hottest row in the game
        // and it must not sit under the rows that would otherwise feed it.
        assertEquals(2200, onsetOf("2 Periclase + 1 Silicon -> 2 Magnesium + 1 Quartz"))
        // Carbothermic magnesia crosses at 2034 K, so it is the cheaper of the two routes to the
        // metal — the opposite of industry's preference, which turns on reversion this game has no
        // model of. See [REACTIONS].
        assertEquals(2050, onsetOf("1 Periclase + 1 Carbon -> 1 Magnesium + 1 CarbonMonoxide"))
        assertEquals(2050, onsetOf("1 Forsterite + 4 Carbon -> 2 Magnesium + 1 Silicon + 4 CarbonMonoxide"))
        assertEquals(2000, onsetOf("1 Enstatite + 3 Carbon -> 1 Magnesium + 1 Silicon + 3 CarbonMonoxide"))

        // ⛔ Reducing a silicate has to cost more than reducing free quartz, because a silicate holds
        // its silica tighter — by the 59 kJ/mol the forsterite firing row is worth. The table said
        // the opposite until 2026-09-11: both silicates were 1800 K against quartz's 2000 K.
        val quartz = onsetOf("1 Quartz + 2 Carbon -> 1 Silicon + 2 CarbonMonoxide")
        assertEquals(2000, quartz)
        assertEquals(
            listOf(true, true),
            listOf(
                onsetOf("1 Forsterite + 4 Carbon -> 2 Magnesium + 1 Silicon + 4 CarbonMonoxide") > quartz,
                onsetOf("1 Enstatite + 3 Carbon -> 1 Magnesium + 1 Silicon + 3 CarbonMonoxide") >= quartz,
            ),
            "a silicate reduced cheaper than free quartz, which is backwards",
        )
    }

    /**
     * ⭐ **The bug, as a test: hold forsterite and carbon at furnace heat and see what the store ends
     * up holding.**
     *
     * A closure rather than a simulation — start from the charge, add the products of every row hot
     * enough to fire on what is present, and repeat until nothing new appears. That is what a store
     * at a fixed temperature *is*, minus the rates, and rates were never what was wrong.
     *
     * ⛔ **Run against the table as it stood on 2026-09-10 this yields eight species**, including
     * `Periclase`, `Quartz`, `Magnesium` and `Firebrick` — the exact list Stu reported after
     * smelting forsterite, arrived at without playing. Three rows deep: the silicate stopped at
     * magnesia and silicon, the silicothermic row (claiming a vacuum onset of 1500 K) ate both, and
     * the quartz it handed back fired to brick against the leftover magnesia at 1700 K.
     *
     * ⚠️ **Checked at both ends of the dial**, because a rule about temperature that is only tested
     * at one temperature is not tested. 2100 K is the first rung that clears the forsterite row;
     * 2400 K is the top of `Furnace.SETPOINTS` and fires every row in the game. The answer has to be
     * the same, and it is — nothing in the closure is a reagent of anything hotter.
     */
    @Test
    fun `a forsterite and carbon charge yields the metals and nothing else`() {
        fun closureAt(kelvin: Int, charge: Set<Species>): Set<Species> {
            val present = charge.toMutableSet()
            while (true) {
                val grown = REACTIONS
                    .filter { it.onsetKelvin <= kelvin && it.reagents.all { r -> r.first in present } }
                    .flatMap { it.products.map { p -> p.first } }
                if (present.containsAll(grown)) return present
                present.addAll(grown)
            }
        }

        val charge = setOf(Species.Forsterite, Species.Carbon)
        val expected = setOf(
            Species.Forsterite, Species.Carbon,
            Species.Magnesium, Species.Silicon, Species.CarbonMonoxide,
        )
        assertEquals(expected, closureAt(2100, charge), "a forsterite smelt at 2100 K")
        assertEquals(expected, closureAt(2400, charge), "a forsterite smelt at the top of the dial")

        // ⛔ And the periclase that is no longer *made* is still perfectly good as a feed: charged
        // deliberately with carbon it reduces, which is the row that replaced the old accident.
        assertEquals(
            setOf(Species.Periclase, Species.Carbon, Species.Magnesium, Species.CarbonMonoxide),
            closureAt(2100, setOf(Species.Periclase, Species.Carbon)),
            "periclase and carbon",
        )
    }
}
