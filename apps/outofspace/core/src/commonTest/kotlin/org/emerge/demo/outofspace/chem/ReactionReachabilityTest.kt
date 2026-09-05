package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.chem.Species

/**
 * Whether a reaction can happen **at all**, and whether what it makes has anywhere to go —
 * increment 0 of `PLAN_unified_reactions.md`, rewritten by increment 4.
 *
 * Every other test in this package asks whether a row is *right*: that it balances atom for atom,
 * that its enthalpy is quoted against its own formula mass, that its rate follows the law. All of
 * them pass for a row that never fires, because none of them knows where the matter is kept.
 *
 * ### What it caught, and how the question changed
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
 * ⚠️ **The failure it turned into is the opposite one, and it is live.** Making every row
 * store-agnostic meant methane pyrolysis and photosynthesis started firing *in the air*, where their
 * solid products cannot go: `addTo` looks up `Species.fluid`, finds nothing, and returns. The mass
 * would have been dropped on the floor every pass, silently, with the ledgers none the wiser.
 *
 * So this is the check now: **whatever a reaction can make, the store it makes it in can hold.**
 */
class ReactionReachabilityTest {

    @Test
    fun `a row whose principal is a fluid leaves nothing the air cannot hold`() {
        // ⛔ The one that was live. A fluid principal means the pass finds it in the air and runs it
        // there, so every product has to be something the air can hold — `MassIndex(tile,
        // Species.Carbon)` does not compile, by `Fluid.kt`'s design, and `addTo` cannot do at
        // runtime what the type system forbids at compile time. It drops it.
        //
        // Two rows failed this the day the tables were unified:
        //
        //  - `CH₄ → C + 2 H₂` — deleted. It had never fired anywhere but inside a wall, and the fix
        //    is to widen the fluid field, which is parked (plan, decision 4).
        //  - photosynthesis — its principal became the *algae* rather than the water. Asking where
        //    the products should go answers what the principal is: the bloom is the thing that
        //    grows, so the reaction happens in the tank and draws the room's water and CO₂ into it.
        for (r in REACTIONS) {
            if (!r.principal.isFluid) continue
            for ((product, _) in r.products) {
                assertTrue(
                    product.isFluid,
                    "${r.principal.name} reacts in the air and leaves ${product.name}, " +
                        "which the air cannot hold — it would be dropped silently",
                )
            }
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
