package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Which row an OLD save gets back when two rows share a principal** — the one place [REACTIONS]'
 * order is not merely "for reproducibility".
 *
 * ✅ **Fixed for new files on 2026-09-12** — `Save.kt` writes a [Reaction.id], the whole equation,
 * behind `Save.RECIPE_ROW_VERSION`. ⛔ **`principal` is not a key**, and six species are the
 * principal of more than one row; keying by it made three of periclase's rows one control, which is
 * how Stu found it: every row in the picker set the first row with that principal.
 *
 * What survives is the **reader for files below that version**, which have only the principal's
 * name on disk and are read with `REACTIONS.firstOrNull { it.principal == principal }`. Which row
 * each of those words means is decided by the table's order, and this file is what stops that
 * decision being made by accident. It is a record of what old files already ran, not a judgement
 * about which row deserves the word.
 *
 * ⚠️ **It caught a real one.** Methane pyrolysis came back on 2026-09-11 written directly after
 * ammonia cracking, which put it *above* the methane gas fire. Every existing save with a furnace
 * locked to the fire would have loaded as pyrolysis instead: setpoint 810 K → 1300 K, feed list
 * {methane, oxygen} → {methane}, no error and no sign anything had changed. The row moved below the
 * fires and this test was written so that the next reorder cannot do it again quietly.
 */
class ReactionOrderTest {

    /**
     * The row each doubly-used principal resolves to, **as a save has always resolved it**.
     *
     * ⚠️ **Written as the whole reaction rather than as an index or a reagent.** An index would have
     * to be rewritten every time an unrelated row was added and would stop meaning anything the first
     * time somebody did it without looking; a single reagent does not always tell a pair apart, since
     * ammonia cracking and the ammonia fire both take ammonia. The full signature is the only form
     * that is both stable and unambiguous, and it is the same one `FormationTest` keys on.
     *
     * ⛔ **None of these six is a judgement about which row *deserves* to win.** They are a record of
     * which one does *in a file written before `Save.RECIPE_ROW_VERSION`*, so that changing it has to
     * be deliberate. Two of them are arguably backwards — a player picking PERICLASE almost certainly
     * meant one of the two magnesium reductions, not the refractory firing — and that was an
     * argument for fixing the format, which is what [Reaction.id] did. Reordering the table
     * underneath saved games is still not the fix, because these files still exist.
     */
    private val resolvesTo: Map<Species, String> = mapOf(
        // The fire, not the cracking. ⛔ The one that had already been silently reversed once.
        Species.Methane to "1 Methane + 2 Oxygen -> 1 CarbonDioxide + 2 Water",
        // Cracking, not the fire — and the fire has been unreachable from a save since it was written.
        Species.Ammonia to "2 Ammonia -> 1 Nitrogen + 3 Hydrogen",
        // Photosynthesis, not the cooking of a dead bloom.
        Species.Algae to "100 Algae + 6 Water + 6 CarbonDioxide -> 101 Algae + 6 Oxygen",
        // Rusting, not the making of steel.
        Species.Iron to "4 Iron + 3 Oxygen -> 2 Hematite",
        // ⚠️ **Three rows now, not two, and this is still the first of them.** Refractory firing,
        // ahead of both routes to magnesium — the carbothermic one at 2050 K and the silicothermic
        // one at 2200 K.
        //
        // ⭐ **The one shared principal whose meaning did NOT move on 2026-09-11**, which is luck
        // worth naming. `Firebrick` was deleted and its firing row replaced by the forsterite one
        // that the MgO–SiO₂ system actually has, so a furnace saved with `recipe=Periclase` loads as
        // a different `Reaction` object than it did — but it loads as *the same intent*: fire
        // periclase and quartz into a lining. The setpoint moves 1700 → 1500 and the product changes
        // name; nothing about what the player was doing changes.
        Species.Periclase to "2 Periclase + 1 Quartz -> 1 Forsterite",
        // Roasting to magnetite, not the carbothermic reduction to iron.
        Species.Hematite to "6 Hematite -> 4 Magnetite + 1 Oxygen",
    )

    @Test
    fun `every principal shared by two rows is listed here`() {
        // ⛔ **The half that makes the other half honest.** A new row that gives some species a second
        // one is exactly the change that reverses a save's meaning, and it would sail straight past a
        // fixed list of expectations that never mentioned it.
        val duplicated = REACTIONS.groupBy { it.principal }.filter { it.value.size > 1 }.keys
        assertEquals(
            resolvesTo.keys,
            duplicated,
            "a principal gained or lost a second row — decide which one a save should resolve to, " +
                "then say so here",
        )
    }

    @Test
    fun `a save resolves each shared principal to the row it always has`() {
        for ((principal, expected) in resolvesTo) {
            // `Save.kt`'s legacy lookup, character for character — the branch taken below
            // `RECIPE_ROW_VERSION`. If this stops matching it, this test is measuring nothing.
            val resolved = REACTIONS.firstOrNull { it.principal == principal }
            assertTrue(resolved != null, "${principal.name} resolves to no row at all")
            assertEquals(
                expected,
                describe(resolved),
                "a saved ${principal.name} recipe now loads as a different row. Every existing save " +
                    "holding that recipe has silently changed meaning — setpoint, feed list and all.",
            )
        }
    }

    private fun describe(r: Reaction): String =
        r.reagents.joinToString(" + ") { "${it.second} ${it.first.name}" } + " -> " +
            r.products.joinToString(" + ") { "${it.second} ${it.first.name}" }
}
