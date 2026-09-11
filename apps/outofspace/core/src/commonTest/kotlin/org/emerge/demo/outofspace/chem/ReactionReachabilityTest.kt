package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.chem.Species

/**
 * Whether a reaction can happen **at all**, and whether what it makes has anywhere to go —
 * increment 0 of `PLAN_unified_reactions.md`, rewritten by increment 4 and again on 2026-09-11.
 *
 * Every other test in this package asks whether a row is *right*: that it balances atom for atom,
 * that its enthalpy is quoted against its own formula mass, that its rate follows the law. All of
 * them pass for a row that never fires, because none of them knows where the matter is kept.
 *
 * ### What it caught, and how the question changed — twice
 *
 * When it was written there were four tables and each *claimed* a store by which table it was in.
 * Three rows claimed one their reactant could not be in: `offGas` empties a cargo layer of anything
 * the tile wants as a gas, so ammonia at 1100 K, methane at 1300 K and the Boudouard reaction at
 * 973 K were all asking for matter that had been evicted hundreds of kelvin earlier. They could only
 * fire inside a sealed tile, where nothing is allowed to leave.
 *
 * A row cannot claim a store any more — the pass finds the principal wherever it is — so **that
 * failure is now unrepresentable** and the test's first case is gone with it.
 *
 * Making every row store-agnostic then produced the opposite failure: methane pyrolysis and
 * photosynthesis started firing *in the air*, where their solid products cannot go. `addTo` looks up
 * `Species.fluid`, finds nothing, and returns, so the mass would have been dropped on the floor every
 * pass with the ledgers none the wiser. This test's answer was to forbid such a row from existing,
 * and methane pyrolysis was deleted to satisfy it.
 *
 * ⛔ **That answer was aimed at the wrong thing, and the ban is gone.** "A fluid principal may only
 * have fluid products" is a statement about a *species*; what is actually true is a statement about a
 * *store*, because a cargo layer holds every species and only the air is fussy. `AmbientChemistry`
 * asks it per store now — `runsIn` — so methane cracks to soot in a packet or a hopper and a room
 * simply declines to host the row. `MethanePyrolysisTest` is where that is proved on live arrays;
 * what is left here is the structural half.
 */
class ReactionReachabilityTest {

    @Test
    fun `every row has at least one store that can hold everything it makes`() {
        // ⛔ **The property the deleted ban was a crude proxy for.** A row nothing can host is a row
        // that never runs, whichever store it is offered — which is the original sin this file was
        // opened to catch, in its last remaining form.
        //
        // A cargo layer holds every species, so the only way to fail today is a row that also cannot
        // be in a cargo layer, which is not currently expressible. Stated anyway, because it is the
        // sentence that stops being free the day a store appears with a narrower appetite than a
        // `StuffLayer`.
        for (r in REACTIONS) {
            val cargoCanHold = r.products.all { Species.ALL.contains(it.first) }
            assertTrue(
                r.airCanHoldProducts || cargoCanHold,
                "${r.principal.name}'s row makes something no store can hold — it can never run",
            )
        }
    }

    @Test
    fun `a row the air cannot host has a principal that can be somewhere else`() {
        // The follow-on, and the one that has teeth. A row the air refuses is only useful if its
        // principal can be found outside the air — and for a fluid principal that means the matter
        // must be able to sit in a cargo layer at the temperature the row needs.
        //
        // ⚠️ **`offGas` is what makes this a real question.** It evicts a species from a cargo layer
        // once the tile will take it as vapour, which is the mechanism that made methane pyrolysis
        // unreachable in the first place. What saves the row now is that eviction needs somewhere to
        // evict *to*: a packet on a rail lets go of its volatiles at a `Valve` and nowhere else, so a
        // sealed run of track carries methane to 1300 K. That is a fact about `offGas`'s gate rather
        // than about this table, so what is checked here is the weaker structural half — the row is
        // not asking for a species that no store but the air can hold.
        for (r in REACTIONS) {
            if (r.airCanHoldProducts) continue
            assertTrue(
                Species.ALL.contains(r.principal),
                "${r.principal.name}'s row cannot run in the air and its principal cannot be " +
                    "anywhere else either",
            )
        }
    }

    @Test
    fun `a row whose principal is a solid can still reach every reagent it needs`() {
        // The mirror. A cargo reaction draws from its own layer and from the surrounding air, so a
        // reagent that is neither something a layer can hold nor a fluid would be unreachable —
        // which is not possible today, since a cargo layer holds every species. Stated so that it
        // stays true if that ever stops being so.
        for (r in REACTIONS) {
            if (r.principal.isFluid) continue
            for ((reagent, _) in r.reagents) {
                assertTrue(
                    reagent.isFluid || Species.ALL.contains(reagent),
                    "${r.principal.name} needs ${reagent.name}, which is in no store it can reach",
                )
            }
        }
    }

    /**
     * ⛔ **`nothing outside REACTIONS is still waiting for a sweep` is deleted, and its premise with
     * it.** It counted the rows `REACTIONS` derived from `DECOMPOSITIONS` and `REDUCTIONS` and
     * insisted there were hand-written ones as well, because a row stranded in a table nothing sweeps
     * is a row that never runs — and rows in *both* would be worse: two engines running the same
     * reaction at one tile, each unaware of the other's draw.
     *
     * Both tables are now deleted and every row is typed into `REACTIONS` itself, so there is no
     * second table for a row to be stranded in and nothing left to count. The property is structural
     * rather than tested, which is the better place for it to be.
     */
}
