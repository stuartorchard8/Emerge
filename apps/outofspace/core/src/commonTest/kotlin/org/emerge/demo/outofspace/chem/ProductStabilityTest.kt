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
 * ### Leftover reagents count, since 2026-09-12
 *
 * ⛔ **The check used to look only at the products, and three pairs slipped through the gap.** A real
 * charge converts a fraction of itself, so unreacted reagents sit in the chamber beside the products
 * — and a row is just as badly described when its follow-on eats one of each. The exclusion was
 * argued from the chemistry being *correct*, which it was in all three cases, and that was the wrong
 * test: the player is not told what is correct, they are told what the row says, and in all three
 * cases the row said something that did not happen.
 *
 * ⚠️ **The fourth was found by playing, which is why the rule moved.** Silicothermic magnesia
 * (`2 MgO + Si → 2 Mg + SiO₂`, 2200 K) yielded forsterite, because its quartz met the periclase it
 * had not consumed and fired at 1500 K. Stu, 2026-09-12: *"Maybe this is valid; but it's really hard
 * to reason about as a player."* All four rows were rewritten rather than exempted — see [REACTIONS]
 * for each — and photosynthesis, which is genuinely not a stoichiometry problem, got the one thing
 * that was actually missing: a [Reaction.ceilingKelvin], because algae cook.
 *
 * ⚠️ **A follow-on must be able to fire at the temperature that made the charge**, which with a
 * ceiling in the table is a window test rather than a compare. See [Reaction.firesAt].
 *
 * ⚠️ **It is a stoichiometric check and knows nothing about rates.** Two rows at the same onset are
 * treated as contemporaneous, which is why a row firing exactly at another's onset counts.
 */
class ProductStabilityTest {

    private fun Reaction.formula(): String =
        reagents.joinToString(" + ") { "${it.second} ${it.first}" } + " -> " +
            products.joinToString(" + ") { "${it.second} ${it.first}" }

    /**
     * Every pair where one row leaves a chamber holding another row's entire reagent list, and that
     * second row can fire at the temperature the first one needed.
     *
     * ⛔ **The chamber holds the products *and* whatever reagent did not convert**, which is the
     * whole of what widened on 2026-09-12. A follow-on has to touch at least one product to be this
     * row's fault — one that eats only reagents was already able to fire before this row ran, and
     * saying so here would blame the wrong row.
     */
    private fun violations(): List<String> = buildList {
        for (maker in REACTIONS) {
            val made = maker.products.mapTo(mutableSetOf()) { it.first }
            val chamber = made + maker.reagents.map { it.first }
            for (eater in REACTIONS) {
                if (eater === maker) continue
                if (!eater.firesAt(maker.onsetKelvin)) continue
                if (!eater.reagents.all { it.first in chamber }) continue
                if (eater.reagents.none { it.first in made }) continue
                val leftovers = eater.reagents.filterNot { it.first in made }
                val how =
                    if (leftovers.isEmpty()) "is eaten whole by"
                    else "feeds, beside its own leftover ${leftovers.joinToString(" and ") { "${it.first}" }},"
                add(
                    "${maker.formula()} @${maker.onsetKelvin} K\n" +
                        "    $how  ${eater.formula()} @${eater.onsetKelvin} K",
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
            "a row leaves a chamber holding another row's whole reagent list, at a temperature " +
                "that row fires at — so what the first row says comes out is not what comes out",
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
     * ⭐ **And the silicothermic row moved on 2026-09-12 for the same kind of reason.** Writing its
     * silica as forsterite rather than free quartz takes it from `587 kJ / 266.1 J/K = 2206 K` to
     * `528 kJ / 265.9 J/K = 1986 K` — the entropy is the two magnesium vapours either way, and the
     * enthalpy is 59 kJ cheaper because the slag is a compound rather than an oxide sitting loose.
     *
     * Pinned here because the onsets are the load-bearing half of the rule above: if somebody cools
     * these rows back under one another, the check passes on stoichiometry and the bug comes back.
     */
    @Test
    fun `the magnesium routes are ordered as their Ellingham crossings say`() {
        fun onsetOf(formula: String): Int =
            REACTIONS.first { it.formula() == formula }.onsetKelvin

        // ⭐ **Silicothermic reduction, with its silica bound up as forsterite: 1986 K.** It was
        // 2206 K while the row made free quartz, and the 220 K between them is the slag — the 59 kJ
        // the firing row is worth, handed back at almost no entropy cost. That is the whole reason
        // the real process runs on a silica binder, and it is why this row is now the *cheapest*
        // route to magnesium rather than the dearest.
        assertEquals(2000, onsetOf("4 Periclase + 1 Silicon -> 2 Magnesium + 1 Forsterite"))
        // Carbothermic magnesia crosses at 2034 K, so it is now the dearer of the two routes to the
        // metal — which is industry's ordering, and the table used to have it backwards for the one
        // reason industry has it this way: what silicothermic reduction buys is a silica binder.
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
