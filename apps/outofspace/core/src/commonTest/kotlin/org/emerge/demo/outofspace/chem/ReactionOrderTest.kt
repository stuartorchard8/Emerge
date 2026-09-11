package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Which row a save gets back when two rows share a principal** — the one place [REACTIONS]' order
 * is not merely "for reproducibility".
 *
 * `Save.kt` writes a furnace's locked recipe as its principal's *name* and reads it back with
 * `REACTIONS.firstOrNull { it.principal == principal }`. ⛔ **`principal` is not a key**, and six
 * species are the principal of two rows, so that lookup silently resolves to whichever of the two is
 * written first — and the other row is not expressible across a save/load, however cheerfully the
 * recipe sheet offers it.
 *
 * That is a bug in the save format rather than in the table, and fixing it properly means writing
 * something that identifies a reaction — a principal *and* a reagent, or a name — behind a version
 * bump. Until then the table's order is what decides, and this file is what stops the decision being
 * made by accident.
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
     * which one does, so that changing it has to be deliberate. Two of them are arguably backwards —
     * a player picking PERICLASE almost certainly means the magnesium reduction, not firebrick — and
     * that is an argument for fixing the format, not for reordering the table underneath saved games.
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
        // ⚠️ Firebrick firing, **not** the Pidgeon reduction — the pair the memory names as the
        // original footgun, and it resolves to the one a player is less likely to have meant.
        Species.Periclase to "11 Periclase + 6 Quartz -> 1 Firebrick",
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
            // `Save.kt`'s lookup, character for character. If this stops matching it, this test is
            // measuring nothing.
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
