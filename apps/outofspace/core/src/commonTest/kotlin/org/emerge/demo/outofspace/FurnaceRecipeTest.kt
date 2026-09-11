package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.REACTIONS
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Furnace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A furnace locked to a recipe: one door, three hoppers, and a charge built to the row.**
 *
 * The furnace keeps both jobs and swaps its whole control surface between them. Broad mode is
 * unchanged — a whitelist, a setpoint and a dwell — and is what a player uses to roast serpentine
 * and ammonia and hematite in one machine on sight. Recipe mode states a reaction and a conversion
 * target, and infers everything else.
 *
 * What is worth pinning:
 *
 *  - ⛔ **the book is DERIVED, not stated beside the recipe.** One statement of what may be sent, so
 *    the demand pass never learns recipes exist — `sinkAdmits`' rule that two statements of one fact
 *    are one edit away from disagreeing.
 *  - ⛔ **one port, three stores, sorted by contents.** The door cannot say which hopper a lump is
 *    for, so it does not have to.
 *  - ⛔ **the charge is all-or-nothing**, which is the opposite of a rocket's short feed and the
 *    reason the completion hold is safe: a charge short of one reagent stalls at whatever
 *    percentage it reached.
 *  - ⛔ **completion is measured against what was LOADED**, because nothing vents out of a buffer
 *    and the chamber's mass never changes.
 */
class FurnaceRecipeTest {

    private val grid = Grid(24, 16)

    /** The kiln at (12,7): door (11,7), hoppers (11,6) and (11,8), charge (12,7), out (13,7). */
    private val kilnAt = grid.tile(12, 7)
    private val oreTank = grid.tile(4, 4)
    private val carbonTank = grid.tile(4, 11)

    private val row = REACTIONS.first { it.principal == Species.Ferrosilite }
    private val kg = Budget.KILOGRAM

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        val cfg = OutofspaceConfig(initialGrid = state.grid)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    private fun kiln(s: VesselState): Furnace = s.deck[kilnAt] as Furnace

    private fun store(s: VesselState, role: BufferRole): Mixture? = s.inStore(kilnAt, role)

    private fun mineral(grams: Long): Mixture =
        Mixture.of(Species.Ferrosilite to grams, energy = 0L).atAmbient()

    private fun carbon(grams: Long): Mixture =
        Mixture.of(Species.Carbon to grams, energy = 0L).atAmbient()

    /** A locked kiln with both hoppers stocked by hand, so the belts are not part of the question. */
    private fun kiln(
        ore: Mixture = mineral(200 * kg),
        reductant: Mixture = carbon(200 * kg),
        recipe: org.emerge.demo.outofspace.chem.Reaction? = row,
        completion: Int = Furnace.DEFAULT_COMPLETION,
    ): VesselState {
        val deck = DeckArray(grid)
        deck += Furnace(kilnAt, Direction.Right, recipe = recipe, completionPermille = completion)
        return VesselState(
            grid, deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
            .stocked(kilnAt, ore, BufferRole.Input)
            .stocked(kilnAt, reductant, BufferRole.SecondReagent)
    }

    // ── The book is one statement ────────────────────────────────────────────

    @Test
    fun `a locked furnace asks for exactly the recipe's reagents`() {
        val m = kiln(m = row)
        assertEquals(setOf(Species.Ferrosilite, Species.Carbon), m.whitelist, "the book is not the row")
        // ⛔ **And no ore.** A recipe is a statement about pure species in an exact ratio; a blend
        // has no single species to meter against.
        assertTrue(!m.ore, "a locked furnace offered to take mixed ore")
    }

    @Test
    fun `clearing the recipe gives the player's own list back`() {
        // ⛔ **The stored book is not destroyed by locking.** A player who locks a tuned kiln and
        // then changes their mind has not lost the list they built; recipe mode merely speaks over
        // it. That is what makes the mode switch safe to press.
        val broad = Furnace(kilnAt, Direction.Right, book = setOf(Species.Serpentine), oreByHand = true)
        val locked = broad.withRecipe(row)
        assertEquals(setOf(Species.Ferrosilite, Species.Carbon), locked.whitelist)
        assertEquals(setOf(Species.Serpentine), locked.withRecipe(null).whitelist, "the player's list was eaten")
        assertTrue(locked.withRecipe(null).ore, "the player's ore switch was eaten")
    }

    @Test
    fun `the temperature is the lowest rung that actually converts`() {
        // ⛔ **Above the onset, never AT it.** `SETPOINTS` carries the argument: a reaction at its
        // onset runs at BASE_RATE and essentially nothing happens, so "the lowest valid temperature"
        // is the slowest setting that technically qualifies.
        val m = kiln(m = row)
        assertTrue(m.heldKelvin > row.onsetKelvin, "the kiln sits at or under its own onset")
        assertEquals(Furnace.SETPOINTS.first { it > row.onsetKelvin }, m.heldKelvin)
    }

    @Test
    fun `every row in the table is a workable recipe`() {
        // ⛔ **Three hoppers is the whole table, not a guess**, and the ladder has to clear every
        // onset. A four-reagent row, or one hotter than the top rung, is a failure here rather than
        // a reagent silently dropped or a kiln that cannot run what it says it runs.
        for (r in REACTIONS) {
            assertTrue(r.reagents.size <= 3, "${r.principal} needs ${r.reagents.size} hoppers and there are 3")
            val m = Furnace(kilnAt, Direction.Right, recipe = r)
            assertTrue(
                m.heldKelvin > r.onsetKelvin,
                "${r.principal} onsets at ${r.onsetKelvin} K and the ladder tops out at ${Furnace.SETPOINTS.last()}",
            )
            for ((species, _) in r.reagents) {
                assertTrue(m.roleFor(species) != null, "${r.principal}'s $species has no hopper")
            }
        }
    }

    // ── The charge ───────────────────────────────────────────────────────────

    @Test
    fun `the charge is built to the row's own ratio`() {
        val after = run(kiln(), 2)
        val charge = store(after, BufferRole.Inside) ?: Mixture.EMPTY

        val mineral = charge[Species.Ferrosilite]
        val reductant = charge[Species.Carbon]
        assertTrue(mineral > 0L && reductant > 0L, "nothing was charged")
        // The row is 2 FeSiO₃ : 6 C, which is 264 g against 72 by formula mass. Asserted against the
        // table rather than against those numbers, so a corrected row moves the test with it.
        assertEquals(
            row.reagentFor(row.reagents.indexOfFirst { it.first == Species.Carbon }, mineral),
            reductant,
            "the charge is not on the stoichiometric line",
        )
    }

    @Test
    fun `a hopper short of one reagent charges nothing at all`() {
        // ⛔ **The opposite of a rocket's short feed, and the reason the completion hold is safe.** A
        // lean chamber is a worse engine and still an engine; a lean charge converts until the
        // scarce reagent is gone and then sits at whatever percentage it reached, which is a stall
        // the completion target has no answer for.
        val after = run(kiln(reductant = Mixture.EMPTY), 5)
        assertNull(store(after, BufferRole.Inside), "a kiln with no reductant built a charge anyway")
        assertTrue(
            (store(after, BufferRole.Input)?.total ?: 0L) > 0L,
            "the ore was consumed by a charge that could not be built",
        )
    }

    @Test
    fun `what is left over stays in its own hopper`() {
        // A hopper holding more than its share of a charge keeps the remainder, rather than the
        // charge being scaled up to use it. The next charge draws it.
        val after = run(kiln(ore = mineral(20 * kg), reductant = carbon(200 * kg)), 2)
        val leftOver = store(after, BufferRole.SecondReagent)?.get(Species.Carbon) ?: 0L
        assertTrue(leftOver > 0L, "the surplus reductant vanished")
    }

    // ── The hold ─────────────────────────────────────────────────────────────

    @Test
    fun `it holds until the conversion target and then hands on`() {
        // ⭐ **The mechanic, end to end.** The kiln builds a charge, holds it while the chemistry
        // works, and releases when the principal is down to the fraction the player asked for.
        val after = run(kiln(completion = 900), 8000)

        val out = store(after, BufferRole.Product)
        assertTrue(out != null, "nothing was ever handed on")
        val mineralOut = out!![Species.Ferrosilite]
        val ironOut = out[Species.Iron]
        assertTrue(ironOut > 0L, "the charge came out with no iron in it")
        // Released at or past the target: at most a tenth of the principal survives a 900‰ target.
        // Measured against the charge it came from, which is what the machine measures.
        assertTrue(
            mineralOut * 1000L / (mineralOut + ironOut * 10L) < 200L,
            "the charge was handed on barely converted",
        )
    }

    @Test
    fun `a charge does not leave before its target`() {
        // ⛔ The other half: a dwell would have handed this on already. Two hundred ticks is far
        // past `DWELLS`' lower rungs and nowhere near 900‰ of a reduction at fifty kelvin over onset.
        val after = run(kiln(completion = 990), 200)
        assertNull(store(after, BufferRole.Product), "the charge left before it was converted")
        assertTrue((store(after, BufferRole.Inside)?.total ?: 0L) > 0L, "the charge is not in the chamber")
    }

    @Test
    fun `the conversion is measured against what was loaded`() {
        // ⛔ **Because the charge's MASS cannot answer.** Nothing vents out of a machine buffer, so
        // the chamber weighs the same the whole way through and a rule watching its total would
        // never release anything at all.
        val started = run(kiln(), 2)
        val loaded = kiln(started).chargedPrincipal
        assertTrue(loaded > 0L, "the kiln did not record what it charged")
        assertEquals(
            loaded, store(started, BufferRole.Inside)?.get(Species.Ferrosilite),
            "the recorded baseline is not the principal that actually landed",
        )

        val later = run(started, 1500)
        val mass = store(later, BufferRole.Inside)?.total
        assertEquals(
            store(started, BufferRole.Inside)?.total, mass,
            "the chamber changed weight while reacting, so something vented after all",
        )
        assertTrue(
            (store(later, BufferRole.Inside)?.get(Species.Ferrosilite) ?: 0L) < loaded,
            "the principal did not move, so nothing converted",
        )
    }

    // ── Broad mode is untouched ──────────────────────────────────────────────

    @Test
    fun `an unlocked furnace is exactly what it was`() {
        // Every furnace in every save predates recipes. A broad kiln tips its whole input in, holds
        // for its dwell, and hands on — no hoppers, no ratio, no conversion measurement.
        val deck = DeckArray(grid)
        deck += Furnace(
            kilnAt, Direction.Right,
            setTemperature = 1250, dwellTicks = 0, book = setOf(Species.Ferrosilite),
        )
        val s = VesselState(
            grid, deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(kilnAt, mineral(50 * kg), BufferRole.Input)

        val after = run(s, 400)
        assertEquals(1250, kiln(after).heldKelvin, "an unlocked kiln stopped using its own dial")
        assertEquals(0L, kiln(after).chargedPrincipal, "an unlocked kiln measured a conversion")
        assertTrue(store(after, BufferRole.Product) != null, "a zero dwell did not hand the charge on")
    }

    // ── The belts ────────────────────────────────────────────────────────────

    /**
     * The kiln fed **down one belt from one tank holding both reagents**.
     *
     * ⭐ **The increment's real question.** One door, two hoppers, and a network that was never told
     * recipes exist — the demand pass reads [Furnace.whitelist] exactly as it always has, and the
     * sorting happens at the door because the door cannot answer.
     */
    private fun plumbed(): VesselState {
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        deck += Furnace(kilnAt, Direction.Right, recipe = row)
        // ⛔ **Two tanks, each holding ONE species pure.** A recipe's book is a list of pure species
        // — `SpeciesFilter(species, pure = true)`, the same filter any whitelisted feed produces — so
        // a single tank of pre-blended ore is not something the network will route here at all. That
        // is the mixer argument arriving from the other side: the consumer wants the reagents apart.
        deck += fixtureStorage(oreTank, Direction.Right)
        deck += fixtureStorage(carbonTank, Direction.Right)
        joinRow(grid, rails, 5, 9, 4)     // ore tank → the trunk
        joinRow(grid, rails, 5, 9, 11)    // carbon tank → the trunk
        joinCol(grid, rails, 9, 4, 11)    // the trunk
        joinRow(grid, rails, 9, 11, 7)    // → the kiln's one door
        return VesselState(
            grid, deck,
            air = Stuff.gas(MassArray(grid.size)),
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
            .stocked(oreTank, mineral(400 * kg))
            .stocked(carbonTank, carbon(400 * kg))
    }

    @Test
    fun `one door sorts two reagents into two hoppers`() {
        val after = run(plumbed(), 600)

        val ore = store(after, BufferRole.Input) ?: Mixture.EMPTY
        val reductant = store(after, BufferRole.SecondReagent) ?: Mixture.EMPTY
        val charge = store(after, BufferRole.Inside) ?: Mixture.EMPTY

        assertTrue(ore[Species.Ferrosilite] + charge.total > 0L, "no mineral arrived")
        assertTrue(reductant[Species.Carbon] > 0L, "no reductant reached its own hopper")
        // ⚠️ The strict half: neither hopper holds a gram of the other's species, though both came
        // down the same belt through the same door.
        assertEquals(0L, ore[Species.Carbon], "carbon landed in the ore hopper")
        assertEquals(0L, reductant[Species.Ferrosilite], "mineral landed in the reductant hopper")
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    @Test
    fun `it comes back off a save mid-conversion`() {
        val before = run(kiln(completion = 990), 300)
        val after = Save.read(Save.write(before))

        val m = kiln(after)
        assertEquals(row.principal, m.recipe?.principal, "the recipe did not survive")
        assertEquals(990, m.completionPermille, "the conversion target did not survive")
        // ⛔ **Without this the reload measures against whatever is left and reads 0% converted**,
        // and a charge nearly finished would serve a whole second hold.
        assertEquals(
            kiln(before).chargedPrincipal, m.chargedPrincipal,
            "the conversion baseline did not survive, so the hold restarted",
        )
        assertEquals(
            store(before, BufferRole.Inside)?.total, store(after, BufferRole.Inside)?.total,
            "the charge came back a different size",
        )
    }
}

/** A furnace built for a settings question rather than a world — no deck, no buffers. */
private fun FurnaceRecipeTest.kiln(m: org.emerge.demo.outofspace.chem.Reaction): Furnace =
    Furnace(TileIndex(0), Direction.Right, recipe = m)
