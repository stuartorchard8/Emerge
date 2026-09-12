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
import org.emerge.demo.outofspace.world.machine.MACHINE_BUFFER_CAP
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
        val loaded = kiln(started).chargedReagents[row.principalIndex]
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

    // ── A principal that is its own product ──────────────────────────────────

    /**
     * ⭐ **The row that broke measuring on the principal**: `1 Algae + 6 Water + 6 CO₂ →
     * 2 Algae + 6 O₂`. The principal *doubles*, so `(loaded - left) / loaded` walks from 0 to −1000‰
     * as the charge converts, and a finished charge reads −99% on the panel and is held until
     * [Furnace.RECIPE_TIMEOUT_TICKS] hands it on twenty thousand ticks late. Found in `farm.txt`,
     * Stu, 2026-09-12.
     */
    private val photosynthesis = REACTIONS.first {
        it.principal == Species.Algae && it.reagents.size == 3
    }

    /** The algae kiln, all three hoppers stocked by hand. */
    private fun grower(completion: Int = Furnace.DEFAULT_COMPLETION): VesselState {
        val deck = DeckArray(grid)
        deck += Furnace(kilnAt, Direction.Right, recipe = photosynthesis, completionPermille = completion)
        return VesselState(
            grid, deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
            .stocked(kilnAt, pure(Species.Algae, 20 * kg), BufferRole.Input)
            .stocked(kilnAt, pure(Species.Water, 200 * kg), BufferRole.SecondReagent)
            .stocked(kilnAt, pure(Species.CarbonDioxide, 200 * kg), BufferRole.ThirdReagent)
    }

    private fun pure(species: Species, grams: Long): Mixture =
        Mixture.of(species to grams, energy = 0L).atAmbient()

    @Test
    fun `a row that remakes its own principal still measures a conversion`() {
        // ⛔ **The inputs, and the one least of which is left.** The algae grows while the water and
        // the CO2 go to nothing, so the water and the CO2 are what say the charge is spent.
        val started = run(grower(), 4)
        val m = kiln(started)
        assertEquals(
            photosynthesis.reagents.size, m.chargedReagents.size,
            "the kiln recorded a baseline for less than the whole row",
        )
        assertTrue(m.chargedReagents.all { it > 0L }, "a reagent went in without being counted")
        assertTrue(
            m.convertedPermille { started.inStore(kilnAt, BufferRole.Inside)?.get(it) ?: 0L } < 100,
            "a charge four ticks old was already most of the way converted",
        )

        // Drive it: the reagents run down, the principal runs UP, and the reading still climbs.
        val later = run(started, 3_000)
        val charge = later.inStore(kilnAt, BufferRole.Inside) ?: later.inStore(kilnAt, BufferRole.Product)
        val converted = kiln(later).convertedPermille { charge?.get(it) ?: 0L }
        assertTrue(converted > 0, "the conversion never moved off zero, so nothing measured it")
    }

    @Test
    fun `a grower hands its charge on rather than holding to the timeout`() {
        // ⭐ **The defect end to end.** Measured on the principal this charge is never released by
        // the target — it is released by `RECIPE_TIMEOUT_TICKS`, which is far past this run.
        val started = run(grower(completion = 900), 4)
        val loadedWater = kiln(started).chargedReagents[photosynthesis.reagents.indexOfFirst { it.first == Species.Water }]
        assertTrue(loadedWater > 0L, "no water was ever loaded")

        val after = run(started, 6_000)
        assertTrue(
            kiln(after).heldTicks < Furnace.RECIPE_TIMEOUT_TICKS,
            "the charge only left because the timeout gave up on it",
        )
        val out = store(after, BufferRole.Product)
        assertTrue(out != null, "the finished charge was never handed on")
        assertTrue(out!![Species.Algae] > 0L, "the algae did not come out")
        assertTrue(out[Species.Oxygen] > 0L, "the row never ran")
        // What makes it finished: the reagents that are NOT remade are spent. At a 900‰ target at
        // most a tenth of the water that went in can still be there.
        assertTrue(
            out[Species.Water] * 10L <= loadedWater,
            "the water was not spent, so this was released early",
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
        assertEquals(emptyList(), kiln(after).chargedReagents, "an unlocked kiln measured a conversion")
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

    /**
     * ⛔ **A starved hopper asks for what it can HOLD, never for the whole seam.**
     *
     * The demand pass's standing rule is that a working machine's fullness is momentary — drain it
     * and it takes more — so a machine is [org.emerge.demo.outofspace.world.Acceptance.ANYTHING] and
     * the network is not rationed by it. A locked kiln breaks that rule, which is why it is the
     * second machine after the warehouse to state a number: it loads nothing until every reagent is
     * there in proportion, so a hopper whose partner reagent never arrives is full **for good**.
     *
     * Stu's save `over_fill.txt`, the kiln at (19,10) on this exact recipe. Asking endlessly for
     * ferrosilite drew every gram of it on the vessel into the one corridor leading to the one door
     * — and the carbon that was the only thing able to empty the hopper could not get past what had
     * already set off for it. There was never enough carbon to cook it all; what the endless
     * appetite bought was the whole network being loaded up to find that out.
     *
     * ⚠️ **`hopper + onTrack`, because either alone would pass for the wrong reason.** The hopper
     * was already capped at its own door — `acceptInto` has always refused a lump that would take it
     * past [MACHINE_BUFFER_CAP] — so the overdraw never showed up *in* the machine. It showed up in
     * the corridor, which is why the sum is the assertion.
     */
    @Test
    fun `a starved hopper asks for no more than it can hold`() {
        // Same plumbing, with the reductant tank empty: nothing will ever be cooked here.
        val after = run(plumbed().stocked(carbonTank, null), 600)

        assertNull(store(after, BufferRole.Inside), "a kiln with no reductant built a charge anyway")

        val hopper = store(after, BufferRole.Input)?.total ?: 0L
        var onTrack = 0L
        for (i in 0 until grid.size) onTrack += after.rail.massAt(TileIndex(i))

        assertTrue(
            hopper + onTrack <= MACHINE_BUFFER_CAP,
            "the kiln drew ${hopper + onTrack}g for a hopper that holds ${MACHINE_BUFFER_CAP}g",
        )
        // And the other half: the seam is still in the tank, where the player can re-plumb it.
        assertTrue(
            (after.inStore(oreTank, BufferRole.Inside)?.total ?: 0L) >= 400 * kg - MACHINE_BUFFER_CAP,
            "the tank emptied into a machine that cannot use what it was sent",
        )
    }

    // ── Re-plumbing ──────────────────────────────────────────────────────────

    @Test
    fun `a species the new recipe does not want leaves by the product mouth`() {
        // ⛔ **Otherwise it is dead weight the player cannot reach.** A reagent hopper has no door
        // and no control that empties one, so carbon left in front of a kiln that now cracks
        // ammonia would sit there for the rest of the game.
        // ⚠️ **Small stocks on purpose.** A full hopper apiece is more than the output mouth holds,
        // so evicting both takes more than one drain — correct, and not what this test is about.
        val started = run(kiln(ore = mineral(20 * kg), reductant = carbon(20 * kg)), 2)
        val switched = started.copy(
            deck = started.deck.also {
                it[kilnAt] = kiln(started).withRecipe(REACTIONS.first { r -> r.principal == Species.Ammonia })
            },
        )
        val after = run(switched, 20)

        assertEquals(0L, store(after, BufferRole.SecondReagent)?.get(Species.Carbon) ?: 0L, "the carbon stayed put")
        assertTrue(
            (store(after, BufferRole.Product)?.get(Species.Carbon) ?: 0L) > 0L,
            "the carbon did not come out of the product mouth",
        )
    }

    @Test
    fun `a species the new recipe wants elsewhere moves across rather than out`() {
        // ⛔ **Sending it out to be re-demanded would be a round trip through the whole network to
        // end up two tiles away.** Carbon is the principal of its own oxidation row, so switching to
        // that row means the carbon belongs in the FIRST hopper rather than the second.
        val started = run(kiln(ore = mineral(20 * kg), reductant = carbon(20 * kg)), 2)
        val carbonRow = REACTIONS.first { it.principal == Species.Carbon }
        // ⚠️ **The chamber is emptied first, and that is the question this test is NOT asking.** A
        // charge built for the old row has no conversion the new one can read, so it is handed on —
        // the reducer clears the baseline on a recipe change for exactly that reason. Leaving it
        // there would put the chamber's own carbon in the mouth and nothing here could tell that
        // apart from a stranded reagent being thrown out.
        val switched = started
            .copy(deck = started.deck.also { it[kilnAt] = kiln(started).withRecipe(carbonRow) })
            .stocked(kilnAt, null, BufferRole.Inside)
        val after = run(switched, 20)

        assertEquals(BufferRole.Input, kiln(after).roleFor(Species.Carbon), "carbon is not the principal here")
        assertTrue(
            (store(after, BufferRole.Input)?.get(Species.Carbon) ?: 0L) > 0L,
            "the carbon did not move into the hopper it now belongs in",
        )
        assertEquals(
            0L, store(after, BufferRole.Product)?.get(Species.Carbon) ?: 0L,
            "carbon the recipe still wants was thrown out anyway",
        )
    }

    @Test
    fun `a full output mouth delays the eviction rather than losing it`() {
        // ⛔ **The reason this is a standing rule and not a one-shot on the edit.** With the product
        // mouth full there is nowhere to put the stranded reagent this tick; a one-shot would have
        // dropped it on the floor, and the hopper would have kept it for ever.
        val started = run(kiln(ore = mineral(20 * kg), reductant = carbon(20 * kg)), 2)
        val blocked = started
            .copy(deck = started.deck.also {
                it[kilnAt] = kiln(started).withRecipe(REACTIONS.first { r -> r.principal == Species.Ammonia })
            })
            .stocked(kilnAt, mineral(200 * kg), BufferRole.Product)
        val stuck = run(blocked, 20)
        assertTrue(
            (store(stuck, BufferRole.SecondReagent)?.get(Species.Carbon) ?: 0L) > 0L,
            "the carbon was evicted into a full mouth, so it went nowhere",
        )

        // Drain the mouth and it leaves on its own, with nothing having been lost in between.
        val drained = stuck.stocked(kilnAt, null, BufferRole.Product)
        val after = run(drained, 20)
        assertTrue(
            (store(after, BufferRole.Product)?.get(Species.Carbon) ?: 0L) > 0L,
            "the eviction did not happen once there was room",
        )
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
            kiln(before).chargedReagents, m.chargedReagents,
            "the conversion baseline did not survive, so the hold restarted",
        )
        assertEquals(
            store(before, BufferRole.Inside)?.total, store(after, BufferRole.Inside)?.total,
            "the charge came back a different size",
        )
    }

    @Test
    fun `a save written before version 31 derives the rest of its baseline`() {
        // ⛔ **An existing file records the PRINCIPAL alone**, and on the algae row that is the one
        // figure that cannot measure anything. A charge is built exactly stoichiometric, so the rest
        // of the row follows from it — see `Save.CHARGED_REAGENTS_VERSION`.
        val started = run(grower(), 4)
        val full = kiln(started).chargedReagents
        val old = Save.write(started).replace(
            Regex("""charged=[0-9,]+"""),
            "charged=${full[photosynthesis.principalIndex]}",
        )
        val back = kiln(Save.read(old))
        assertEquals(full.size, back.chargedReagents.size, "the derived baseline is the wrong length")
        for ((i, want) in full.withIndex()) {
            // Exact: `reagentFor` is the same arithmetic the charge was drawn with.
            assertEquals(want, back.chargedReagents[i], "reagent $i came back with the wrong baseline")
        }
    }

    @Test
    fun `three hoppers come back as three hoppers`() {
        // ⛔ **They shared one field name until version 32**, so a three-reagent kiln wrote two
        // `oxid=` fields on one line and the reader kept the last: both hoppers came back holding
        // the same thing, which destroyed one reagent and duplicated the other. See
        // `Save.REAGENT_HOPPER_KEYS_VERSION`.
        val before = grower()
        val after = Save.read(Save.write(before))

        for (role in listOf(BufferRole.Input, BufferRole.SecondReagent, BufferRole.ThirdReagent)) {
            val was = before.inStore(kilnAt, role)
            val now = after.inStore(kilnAt, role)
            assertEquals(was?.dominant, now?.dominant, "$role came back holding something else")
            assertEquals(was?.total, now?.total, "$role came back a different size")
        }
        // The whole point, stated as the thing that was wrong: the two hoppers are not each other.
        assertEquals(Species.Water, after.inStore(kilnAt, BufferRole.SecondReagent)?.dominant)
        assertEquals(Species.CarbonDioxide, after.inStore(kilnAt, BufferRole.ThirdReagent)?.dominant)
    }

    @Test
    fun `a save written before version 32 gets both colliding hoppers back`() {
        // ⭐ **Nothing was ever lost in the FILE** — both `oxid=` fields are on the line, and only
        // the map built from them dropped one. So an old record is recovered by position rather
        // than written off. This is Stu's `farm.txt` in miniature.
        val current = Save.write(grower())
        val legacy = current
            .replace("outofspace ${Save.VERSION}", "outofspace ${Save.REAGENT_HOPPER_KEYS_VERSION - 1}")
            .replace("reagent2=", "oxid=")
            .replace("reagent3=", "oxid=")
        assertEquals(2, Regex("""oxid=""").findAll(legacy).count(), "the fixture is not the old spelling")

        val back = Save.read(legacy)
        assertEquals(
            Species.Water, back.inStore(kilnAt, BufferRole.SecondReagent)?.dominant,
            "the water was lost, which is the bug this migration exists for",
        )
        assertEquals(
            Species.CarbonDioxide, back.inStore(kilnAt, BufferRole.ThirdReagent)?.dominant,
            "the third hopper did not come back",
        )
        // ⛔ **And the masses are the ones the file states**, not one of them twice.
        assertEquals(200 * kg, back.inStore(kilnAt, BufferRole.SecondReagent)?.total)
        assertEquals(200 * kg, back.inStore(kilnAt, BufferRole.ThirdReagent)?.total)
    }

    @Test
    fun `each row of a shared principal comes back as itself`() {
        // ⛔ **A save wrote `recipe=<principal>` until version 30**, so periclase's three rows were
        // one word on disk and a reload could only ever hand back the first of them. The field is
        // the row's whole equation now — see `Reaction.id`.
        for (r in REACTIONS.filter { it.principal == Species.Periclase }) {
            val before = kiln(recipe = r)
            val back = kiln(Save.read(Save.write(before)))
            assertEquals(r.id, back.recipe?.id, "a saved ${r.id} came back as ${back.recipe?.id}")
        }
    }

    @Test
    fun `a save written before version 30 still reads its principal`() {
        // The other half: an existing file says `recipe=Periclase` and means the row it was
        // running, which is the first with that principal. `ReactionOrderTest` is the record of
        // which one that is for each of the six shared principals.
        val text = Save.write(kiln(recipe = REACTIONS.first { it.principal == Species.Periclase }))
        val old = text
            .replaceFirst("outofspace ${Save.VERSION} ", "outofspace ${Save.RECIPE_ROW_VERSION - 1} ")
            .replaceFirst(Regex("""recipe=\S+"""), "recipe=Periclase")
        assertTrue("recipe=Periclase" in old, "the fixture did not rewrite the field")

        val back = kiln(Save.read(old))
        assertEquals(
            REACTIONS.first { it.principal == Species.Periclase }.id,
            back.recipe?.id,
            "an older file's recipe changed meaning",
        )
    }
}

/** A furnace built for a settings question rather than a world — no deck, no buffers. */
private fun FurnaceRecipeTest.kiln(m: org.emerge.demo.outofspace.chem.Reaction): Furnace =
    Furnace(TileIndex(0), Direction.Right, recipe = m)
