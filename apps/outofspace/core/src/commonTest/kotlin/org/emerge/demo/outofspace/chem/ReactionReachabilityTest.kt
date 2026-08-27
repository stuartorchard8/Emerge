package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether a reaction can happen **at all** — increment 0 of `PLAN_unified_reactions.md`.
 *
 * Every other test in this package asks whether a row is *right*: that it balances atom for atom,
 * that its enthalpy is quoted against its own formula mass, that its rate follows the law. All of
 * them pass for a row that never fires, because none of them knows where the matter is kept.
 *
 * ### The failure this exists to catch
 *
 * A row states a reactant and an onset temperature. The table it lives in decides which **store** it
 * is swept over — [DECOMPOSITIONS], [OXIDATIONS] and [REDUCTIONS] are swept by `oxidise` over the
 * cargo layers; [COMBUSTIONS] is swept by `combust` over the fluid field. Nothing checks that the
 * reactant can be in that store at that temperature.
 *
 * For a fluid, it usually cannot. `offGas` runs over the same cargo layers on the same pass and
 * empties them of anything the tile's conditions want as a gas, and above a species' critical
 * temperature `vapourHeadroom` returns "as much as you have" — every gram leaves. So a row whose
 * reactant is a fluid and whose onset is above that fluid's critical temperature is asking for
 * matter that was evicted hundreds of kelvin ago.
 *
 * `CH₄ → C + 2 H₂` is the one that prompted the plan: onset 1300 K, methane critical at 191 K. It
 * can only fire where `offGas` is forbidden to run — inside a sealed tile. **Methane pyrolysis works
 * if and only if it happens inside a wall.**
 *
 * ⚠️ **The only other signal is a reaction that never happens**, which is indistinguishable from a
 * vessel the player has not built the right machine for. At twenty-two rows this is four mistakes
 * that took five days to notice; at the few hundred rows the plan is written for, it is a coin flip
 * on every row involving a volatile — which is most of what a comet-mining vessel does.
 */
class ReactionReachabilityTest {

    /**
     * Whether a cargo layer can still be holding [species] once it is this hot.
     *
     * The question `offGas` asks, put the same way it asks it: a species with no saturated vapour
     * density at this temperature has no ceiling on how much of it the tile's air will take, so all
     * of it goes. That is `vapourHeadroom` returning [Long.MAX_VALUE], and it happens both for a
     * species with no critical point on file and for one hotter than the critical point it has.
     *
     * ⚠️ **Below the critical point this is a "maybe", and the test only asserts the "never".** A
     * fluid under its Tc is evicted up to the saturation ceiling, so whether any is left depends on
     * how much of it the room's air is already carrying — a fact about a live world, not about a
     * table. The unconditional case is the one a static test can be certain of, and it is the one
     * that is actually wrong.
     */
    private fun survivesInCargoAt(species: Species, kelvin: Int): Boolean {
        if (species.fluid == null) return true
        return saturatedVapourDensityAt(kelvin, species) != null
    }

    /** Every row swept over the cargo layers, as `(table, principal, onset)`. */
    private fun layerSweptRows(): List<Triple<String, Species, Int>> = buildList {
        for (d in DECOMPOSITIONS) add(Triple("HEAT", d.reactant, d.onsetKelvin))
        for (o in OXIDATIONS) add(Triple("AIR", o.reactant, o.onsetKelvin))
        for (r in REDUCTIONS) add(Triple("REAGENT", r.oxide, r.onsetKelvin))
    }

    /**
     * Every row swept over the fluid field — the fires, and the store-agnostic rows whose principal
     * happens to be a fluid.
     *
     * ⚠️ **The audit is two-sided now.** A cargo row fails by naming a principal the cargo layer
     * cannot hold; a fluid row fails the other way, by naming one the *air* cannot. Increment 1
     * moved ammonia across, so both directions are live and neither can be checked alone.
     */
    private fun fluidSweptRows(): List<Triple<String, Species, Int>> = buildList {
        for (c in COMBUSTIONS) add(Triple("GAS FIRE", c.fuel, c.onsetKelvin))
        for (r in REACTIONS) add(Triple("FLUID", r.principal, r.onsetKelvin))
    }

    @Test
    fun `no row asks for a principal its own store cannot hold`() {
        val dead = layerSweptRows()
            .filterNot { (_, principal, onset) -> survivesInCargoAt(principal, onset) }
            .map { (table, principal, onset) -> "$table ${principal.name}@${onset}K" }
            .toSet()

        // ⚠️ **Pinned exactly, not asserted empty.** Three of these are known and are the reason the
        // plan exists; listing them keeps the test live rather than `@Ignore`d, so a *fourth* dead
        // row is a failure on the day it lands. Fixing one of these without striking it from here is
        // also a failure, which is what stops the list quietly outliving the bug.
        //
        // ⛔ Strike a row from this set only when it has genuinely moved store — never to make the
        // test green. See `PLAN_unified_reactions.md`; increments 1 and 3 are what empty it.
        val knownDead = setOf(
            // ✅ Ammonia cracking was here and is gone — increment 1 moved it to [REACTIONS], which
            // is swept over the fluid field where the ammonia actually is.
            //
            // Increment 2, ⛔ PARKED — it needs the fluid field to be able to hold carbon. Methane
            // critical at 191 K, pyrolysis at 1300 K.
            "HEAT Methane@1300K",
            // Increment 3. CO₂ critical at 304 K, Boudouard at 973 K. `Combustion.kt` credits this
            // row with filling the vessel's rooms with carbon monoxide; it has never fired.
            "REAGENT CarbonDioxide@973K",
        )
        assertEquals(knownDead, dead)
    }

    @Test
    fun `no fluid-swept row asks for a principal the air cannot hold`() {
        // The mirror of the case above, and it is not hypothetical: the way to "fix" a dead cargo
        // row is to move it to the fluid sweep, and the way to get that wrong is to move one whose
        // principal is a rock. A pass finds no fluid for it and skips it silently, which is the same
        // symptom the plan exists to eliminate — a reaction that never happens.
        for ((table, principal, onset) in fluidSweptRows()) {
            assertTrue(principal.isFluid, "$table ${principal.name}@${onset}K: the air cannot hold it")
        }
    }

    @Test
    fun `a fluid-swept row leaves nothing behind that the air cannot hold`() {
        // Products, where the case above is reagents. A gas-phase reaction with a solid product has
        // nowhere to put it — `MassIndex(tile, Species.Carbon)` does not compile, by `Fluid.kt`'s
        // design — so the mass would vanish. The answer when such a row is wanted is to widen the
        // fluid field, which is parked; see the plan, decision 4.
        for (r in REACTIONS) {
            for ((product, _) in r.products) {
                assertTrue(
                    product.isFluid,
                    "${r.principal.name} leaves ${product.name}, which the air cannot hold",
                )
            }
        }
    }

    @Test
    fun `every store-agnostic row has one reagent until contention exists`() {
        // ⛔ `reactInFluid` allocates nothing between rows, so a row with a second reagent would take
        // as much of it as it liked and no row would ever be starved. The list shape is the target
        // shape and it is right that it is a list — but until increment 3 builds the Jacobi
        // demand-then-apportion, a second entry is a reaction that quietly runs rich.
        for (r in REACTIONS) {
            assertEquals(1, r.reagents.size, "${r.principal.name} has a reagent nothing allocates")
            assertEquals(r.principal, r.reagents.single().first)
        }
    }

    @Test
    fun `a gas fire leaves nothing behind that the air cannot hold`() {
        // The guard that would have caught methane pyrolysis the day it was written, and it costs
        // nothing today: every product of every current gas fire is already a fluid. A gas-phase
        // reaction with a solid product has nowhere to put it — `MassIndex(tile, Species.Carbon)`
        // does not compile, by `Fluid.kt`'s design — and the answer when one is wanted is to widen
        // the fluid field rather than to invent a store. See the plan, decision 4.
        for (c in COMBUSTIONS) {
            assertTrue(c.fuel.isFluid, "${c.fuel.name} burns in the air but cannot be in it")
            for ((product, _) in c.products) {
                assertTrue(
                    product.isFluid,
                    "${c.fuel.name} burning leaves ${product.name}, which the air cannot hold",
                )
            }
        }
    }

    @Test
    fun `the reference describes exactly the rows this file audits`() {
        // Not a second copy of the first case — the point is *who is told*. Every dead row above is
        // printed by the in-game reference as a route the player can plan around, with an onset
        // temperature and an arrow, in the same confident voice as a row that works.
        //
        // So the two have to be looking at the same rows. A table the reference flattens and this
        // audit does not is a reaction the player can read about and nobody has checked can happen;
        // a table this audit walks and the reference does not is the bug fixed in `0de81dd9`. One
        // count, asserted equal, closes both directions.
        assertEquals(ALL_REACTIONS.size, layerSweptRows().size + fluidSweptRows().size)
    }
}
