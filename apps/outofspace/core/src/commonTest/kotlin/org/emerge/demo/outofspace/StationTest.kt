package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.REACTIONS
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BodyKind
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.DockNode
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Market
import org.emerge.demo.outofspace.world.Pose
import org.emerge.demo.outofspace.world.CONCENTRATION_BATCH
import org.emerge.demo.outofspace.world.REACTION_BATCH
import org.emerge.demo.outofspace.world.batchMass
import org.emerge.demo.outofspace.world.heatFee
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Station
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.demo.outofspace.world.worked
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A trading post: a rigid body with an economy hanging off it — `PLAN_economy.md` §6.
 *
 * Three claims: **a station is permanent**, **a hollow shell is a correct body and a cheap one**,
 * and **cracking compounds fires only through the local stock discount** (§3.4, as live behaviour
 * rather than as an assertion about a price table).
 */
class StationTest {

    @AfterTest
    fun tidy() {
        RockSpawner.enabled = true
        RockSpawner.reset()
    }

    private val tonne = Budget.TONNE

    private fun steel() = Mixture.of(Species.Steel to Budget.KILOGRAM, energy = 0L)

    private fun station(
        ore: Mixture = Mixture.EMPTY,
        market: Market = Market.empty(),
        width: Int = 100,
        height: Int = 100,
        atTileX: Long = 0L,
        atTileY: Long = 0L,
    ) = RigidBody.stationShell(
        width = width, height = height,
        positionX = atTileX * Flight.PER_TILE, positionY = atTileY * Flight.PER_TILE,
        composition = steel(),
        station = Station(ore, market),
    )

    // ── The shell ────────────────────────────────────────────────────────────

    @Test
    fun `a hundred-tile station is four hundred cells, not ten thousand`() {
        // ⛔ The reason a station is a shell at all: `collectBodyContacts` is O(cells × cells) and a
        // 100×100 station's bound radius culls nothing in its own neighbourhood. Solid would be
        // 10,000 cells for every nearby rock to test against, every substep.
        val s = station()
        assertEquals(396, s.filled, "the shell is not a one-cell-deep ring")
        assertEquals(10_000, s.cells.size, "the bounding box changed shape")
    }

    @Test
    fun `a ring's centre of mass is its centre`() {
        // `cellDistribution` walks the mask and skips empties, so a ring needs no special case
        // anywhere. If this ever drifts, something has started reconstructing a body from its
        // bounding box instead of from its cells.
        //
        // ⚠️ Asked of the body's **own grid**, which is where the claim lives. It used to be asked
        // of [RigidBody.comX] and got the same number for a body placed at the world origin — but
        // that reads the *world* centre, and since the anchor flipped a body's world centre is
        // wherever it was put. The distribution is what walks the mask, so the distribution is what
        // this is about; the expected value has not moved.
        val s = station(width = 21, height = 21)
        assertEquals(s.width * Flight.PER_TILE / 2, s.about.comX, "a symmetric ring is off-centre in x")
        assertEquals(s.height * Flight.PER_TILE / 2, s.about.comY, "a symmetric ring is off-centre in y")
        assertTrue(s.about.gyrationSq > 0L, "a ring cannot spin")

        // And the other half of the anchor: a body's world centre of mass is where it was placed,
        // exactly, with no bounding box anywhere in the answer.
        val placed = station(width = 21, height = 21, atTileX = 7L, atTileY = -3L)
        assertEquals(7L * Flight.PER_TILE, placed.comX, "a body is placed by its centre of mass")
        assertEquals(-3L * Flight.PER_TILE, placed.comY, "a body is placed by its centre of mass")
    }

    @Test
    fun `a station carries its economy through a copy`() {
        // ⚠️ **The trap `RigidBody.copy` warns about, and the field it was waiting for.**
        // `driftBodies` copies every body every tick, so a station left off that parameter list
        // would lose its whole economy on the first tick it moved — silently, in a running world,
        // and never in a unit test that did not think to copy.
        val s = station(market = Market.of(Species.Iron to tonne))
        val moved = s.copy(positionX = 999L)
        assertEquals(tonne, moved.station?.market?.stockOf(Species.Iron), "the copy dropped the shelves")
    }

    @Test
    fun `a station has no thermal model`() {
        // The boundary is the door: a reserve that kept its heat would put it inside the station,
        // where ore warms and the pure pile it becomes cannot.
        val hot = Mixture.of(Species.Iron to tonne, energy = 999_999L)
        assertEquals(0L, Station(hot, Market.empty()).ore.energy, "a station kept a temperature")
        assertEquals(tonne, Station(hot, Market.empty()).ore[Species.Iron], "and lost the mass with it")
    }

    // ── Permanence ───────────────────────────────────────────────────────────

    @Test
    fun `a station survives the player flying two thousand tiles away`() {
        RockSpawner.enabled = true
        RockSpawner.reset()
        val post = station(atTileX = 0, atTileY = 0)
        var bodies = listOf(post)
        // Eleven chunks of window at 64 tiles apiece is ~700; two thousand is well outside it, which
        // is exactly the distance that despawns everything else.
        for (step in 1..40) {
            bodies = RockSpawner.process(10_000L + step, bodies, step * 50L, 0L)
        }
        val stations = bodies.filter { it.kind == BodyKind.STATION }
        assertEquals(1, stations.size, "the station was despawned when the player left")
        assertEquals(post.positionX, stations[0].positionX, "and it moved while nobody was looking")
    }

    @Test
    fun `rocks do not spawn inside a station's clearance`() {
        RockSpawner.enabled = true
        RockSpawner.reset()
        val post = station(atTileX = 0, atTileY = 0)
        var bodies = listOf(post)
        for (step in 1..60) bodies = RockSpawner.process(10_000L + step, bodies, 0L, 0L)

        val clear = RockSpawner.STATION_CLEARANCE_TILES * Flight.PER_TILE
        val minX = post.positionX - clear
        val maxX = post.positionX + post.width * Flight.PER_TILE + clear
        val minY = post.positionY - clear
        val maxY = post.positionY + post.height * Flight.PER_TILE + clear
        for (body in bodies) {
            if (body.kind == BodyKind.STATION) continue
            val insideX = body.positionX < maxX && body.positionX + body.width * Flight.PER_TILE > minX
            val insideY = body.positionY < maxY && body.positionY + body.height * Flight.PER_TILE > minY
            assertTrue(!(insideX && insideY), "a rock spawned in the station's approach at ${body.positionX},${body.positionY}")
        }
    }

    // ── Industry ─────────────────────────────────────────────────────────────

    @Test
    fun `a glutted station works its ore down a batch at a time`() {
        // ⚠️ **The dominant species is IRON on purpose — an element, which cannot be cracked.** With
        // a compound dominant, the shelf figure is separation minus whatever the cracker took back
        // off it in the same ticks, and this test would be measuring both plants at once. It caught
        // exactly that: 100 kg separated read as 32 kg on the shelf.
        val ore = Mixture.of(Species.Iron to 100L * tonne, Species.Nickel to tonne, energy = 0L)
        var s = Station(ore, Market.empty())
        val before = s.ore.total

        repeat(10) { s = s.worked() }

        assertEquals(before - 10L * CONCENTRATION_BATCH, s.ore.total, "the reserve did not fall by a batch a batch")
        assertEquals(10L * CONCENTRATION_BATCH, s.market.stockOf(Species.Iron), "iron never reached the shelves")
        assertEquals(0L, s.market.stockOf(Species.Nickel), "the minority species was separated first")
    }

    @Test
    fun `a station keeps its identity and its berths through a batch of work`() {
        // ⛔ **`Station` is rebuilt from two fields by both of its plants, and its constructor
        // defaults the other two** — so this went wrong the tick after the world started, silently,
        // in a value nothing reads until the player tries to dock. It was found by a screenshot of
        // the trade sheet reading "STATION 0". Exactly the shape `RigidBody.copy` warns about, one
        // field list over.
        val berths = listOf(DockNode(0, 5, Direction.Left), DockNode(9, 0, Direction.Up))
        var s = Station(
            Mixture.of(Species.Iron to 10L * tonne, energy = 0L),
            Market.of(Species.Forsterite to 500L * tonne),
            id = 42,
            docks = berths,
        )
        repeat(10) { s = s.worked() }
        assertEquals(42, s.id, "the station forgot who it was")
        assertEquals(berths, s.docks, "the station lost its berths")
    }

    @Test
    fun `a reserve holding less than a batch does nothing at all`() {
        // ⛔ **The threshold is the balance, not a tidiness rule.** A station one gram short of a
        // tonne of its own dominant species does *nothing*, for ever — which is what stops it
        // quietly extracting trace metals from whatever dribble of ore has been left with it. See
        // [CONCENTRATION_BATCH].
        val s = Station(Mixture.of(Species.Iron to CONCENTRATION_BATCH - 1L, energy = 0L), Market.empty())
        assertEquals(s.ore.total, s.worked().ore.total, "a part-batch reserve was dribbled out")
        assertEquals(0L, s.worked().market.holdings().total, "a part-batch reserve reached the shelves")
    }

    @Test
    fun `a reserve rich in trace metals is not worked for them`() {
        // The same threshold read the way a player meets it: nine hundred kilograms of ore with gold
        // in it is not an ore body, and a station will not pick it over. ⚠️ Gold is the dominant
        // species here only because there is nothing else — the point is the *quantity*, not which
        // species happens to be on top.
        val dribble = Mixture.of(
            Species.Gold to 400L * Budget.KILOGRAM, Species.Iron to 500L * Budget.KILOGRAM, energy = 0L,
        )
        val s = Station(dribble, Market.empty()).worked()
        assertEquals(dribble.total, s.ore.total, "a sub-batch reserve was picked over for its gold")
    }

    // ── Chemistry ────────────────────────────────────────────────────────────

    @Test
    fun `a station glutted with serpentine cracks it, and gets water out of it`() {
        // ⛔ **The case that condemned the old splitter.** Taking Mg3Si2O5(OH)4 apart by element
        // yields magnesium, silicon, oxygen and hydrogen — so under that rule there was no way in
        // the game for a station to replenish its water except to be sold some. The *reaction* is
        // right there in `DECOMPOSITIONS` and yields forsterite, enstatite and two waters.
        var s = Station(Mixture.EMPTY, Market.of(Species.Serpentine to 500L * tonne))
        repeat(20) { s = s.worked() }

        assertTrue(s.market.stockOf(Species.Water) > 0L, "a station cracked serpentine and made no water")
        assertTrue(s.market.stockOf(Species.Forsterite) > 0L, "no forsterite either")
        assertTrue(s.market.stockOf(Species.Enstatite) > 0L, "no enstatite either")
        assertEquals(0L, s.market.stockOf(Species.Hydrogen), "the elemental splitter is still in here")
    }

    @Test
    fun `a station makes steel out of iron and carbon`() {
        // ⛔ **The other direction, which the old rule could not express at all.** `Fe99C` is a
        // formula in `MINERALS`, so a splitter could take an alloy to pieces and never make one —
        // and steel is the more useful commodity of the two.
        var s = Station(Mixture.EMPTY, Market.of(Species.Iron to 500L * tonne, Species.Carbon to 50L * tonne))
        repeat(20) { s = s.worked() }

        assertTrue(s.market.stockOf(Species.Steel) > 0L, "a station sitting on iron and carbon made no steel")
        assertTrue(s.market.stockOf(Species.Iron) < 500L * tonne, "the steel came from nowhere")
    }

    @Test
    fun `titanium costs magnesium, and is not reachable without it`() {
        // ⛔ **Ilmenite is one of the commoner minerals in the game**, and an elemental splitter
        // handed over its titanium for nothing — routing straight around the reduction chain
        // `PLAN_ambient_chemistry.md` exists to make interesting. The real second step spends
        // magnesium, which does not occur naturally anywhere and has to be made.
        var dry = Station(Mixture.EMPTY, Market.of(Species.Rutile to 500L * tonne))
        repeat(20) { dry = dry.worked() }
        assertEquals(0L, dry.market.stockOf(Species.Titanium), "titanium appeared with no reducing agent")
        assertEquals(500L * tonne, dry.market.stockOf(Species.Rutile), "the rutile went somewhere")

        var wet = Station(
            Mixture.EMPTY,
            Market.of(Species.Rutile to 500L * tonne, Species.Magnesium to 200L * tonne),
        )
        repeat(20) { wet = wet.worked() }
        assertTrue(wet.market.stockOf(Species.Titanium) > 0L, "magnesium bought no titanium")
        assertTrue(
            wet.market.stockOf(Species.Magnesium) < 200L * tonne,
            "the titanium arrived without spending the magnesium",
        )
    }

    @Test
    fun `at list prices no reaction pays`() {
        // ⛔ **Every reaction in the game is exactly value-neutral at list**, measured across the
        // whole table — a species' price is *defined* as the sum of its elements' and a reaction
        // conserves atoms, so the two sides agree to within a few credits of rounding on charges
        // worth thousands. ⚠️ A tonne of everything is not "no stock": it is an equal and shallow
        // holding, so every price sits at very nearly list and no discount points anywhere. What
        // keeps the rounding from firing a row on its own is [heatFee].
        val even = Market.of(*Species.ALL.map { it to tonne }.toTypedArray())
        var s = Station(Mixture.EMPTY, even)
        repeat(5) { s = s.worked() }
        assertEquals(even.holdings(), s.market.holdings(), "a station reacted at list prices")
    }

    @Test
    fun `a station already rich in a reaction's products does not run it`() {
        // ⛔ **The discriminating test, and the mechanism from the other side.** The only thing that
        // can make a reaction pay is the station-local stock discount; glut the products harder than
        // the reagent and the discount points the other way, so nothing happens. That is also why
        // the discount must be applied per SPECIES stock and never per element — one level up and
        // both sides of the comparison move together and the mechanism dies silently.
        val stocked = Market.of(
            Species.Serpentine to 10L * tonne,
            Species.Forsterite to 5_000L * tonne,
            Species.Enstatite to 5_000L * tonne,
            Species.Water to 5_000L * tonne,
        )
        var s = Station(Mixture.EMPTY, stocked)
        repeat(20) { s = s.worked() }
        assertEquals(
            10L * tonne, s.market.stockOf(Species.Serpentine),
            "a station reacted into shelves already full",
        )
    }

    @Test
    fun `a batch neither invents nor loses a gram`() {
        // A station is outside every ledger in the game, so nothing else would ever catch a drift
        // here — which is the reason to be exact rather than an excuse not to be. Both sides are
        // apportioned off one stated total, so the row closes by construction.
        val stocked = Market.of(Species.Serpentine to 500L * tonne)
        var s = Station(Mixture.EMPTY, stocked)
        repeat(20) { s = s.worked() }
        assertEquals(
            stocked.holdings().total, s.market.holdings().total,
            "twenty batches of chemistry did not conserve mass",
        )
    }

    @Test
    fun `an exothermic reaction is never paid for its heat`() {
        // ⛔ **The rebate that must not exist.** A station has no thermal model, no store to put
        // recovered heat in and nobody to sell it to. Crediting one for its own fires would also be
        // a large mistake rather than a small one: burning carbon releases about thirty times what
        // it costs to light, so a station paid for that would burn every gram it owned for the
        // money. Endothermy is charged; exothermy is free and no more than free.
        val burn = REACTIONS.first { r ->
            r.principal == Species.Carbon && r.products.any { it.first == Species.CarbonDioxide }
        }
        assertTrue(burn.enthalpy(Budget.KILOGRAM) < 0L, "the row picked is not the exothermic one")
        val drawn = burn.draw(batchMass(burn))
        val charge = Mixture.of(
            *burn.reagents.mapIndexed { i, (species, _) -> species to drawn[i] }.toTypedArray(),
            energy = 0L,
        )
        assertTrue(burn.heatFee(charge) > 0L, "an exothermic row was paid to run")
    }

    @Test
    fun `a batch is sized by the largest product, not by the charge`() {
        // Stu's rule, and the one that makes batches comparable across rows of very different
        // shapes: a row yielding a tonne of tailings and a kilogram of metal, and one yielding the
        // reverse, would otherwise be run at sizes whose profits mean different things.
        for (reaction in REACTIONS) {
            val total = batchMass(reaction)
            val yielded = reaction.split(total)
            assertEquals(total, yielded.sum(), "$reaction did not split its charge exactly")
            // ⚠️ Two microgrammes of slack, and it is `apportion`'s contract rather than sloppiness:
            // the total is exact by construction and each individual share is proportional only to
            // within a unit. Sizing the charge off the batch rounds once more on the way in.
            assertTrue(
                yielded.max() in REACTION_BATCH - 2L..REACTION_BATCH,
                "$reaction's largest product was ${yielded.max()}, not a batch",
            )
        }
    }

    // ── The save ─────────────────────────────────────────────────────────────

    @Test
    fun `a station survives a round trip`() {
        val post = station(
            ore = Mixture.of(Species.Forsterite to 3L * tonne, Species.Gold to Budget.KILOGRAM, energy = 0L),
            market = Market.of(Species.Iron to 7L * tonne, Species.Titanium to Budget.KILOGRAM),
            width = 20, height = 20, atTileX = 300, atTileY = -200,
        )
        val start = starterVessel(OutofspaceConfig().initialGrid).copy(bodies = listOf(post))
        val back = org.emerge.demo.outofspace.world.Save.read(
            org.emerge.demo.outofspace.world.Save.write(start),
        ).bodies.single()

        assertEquals(BodyKind.STATION, back.kind)
        assertEquals(post.positionX, back.positionX)
        assertEquals(post.cells.toList(), back.cells.toList(), "the shell came back a different shape")
        assertEquals(post.fillPermille, back.fillPermille)
        val economy = assertNotNull(back.station, "the station came back without an economy")
        assertEquals(3L * tonne, economy.ore[Species.Forsterite])
        assertEquals(Budget.KILOGRAM, economy.ore[Species.Gold])
        assertEquals(7L * tonne, economy.market.stockOf(Species.Iron))
        assertEquals(Budget.KILOGRAM, economy.market.stockOf(Species.Titanium))
    }

    @Test
    fun `an old save's shelves are tipped back into the heap`() {
        // ⛔ **The shelves in a pre-v24 file are not shelves.** Every sale was absorbed onto them
        // species by species, so what is recorded is every lump anybody ever sold, taken apart for
        // free — a live station reached a hundred and forty species in sub-gram quantities. Nothing
        // takes matter off a shelf except a purchase, so playing forward never tidies it.
        val post = station(
            ore = Mixture.of(Species.Forsterite to 3L * tonne, energy = 0L),
            market = Market.of(
                Species.Iron to 7L * tonne,
                Species.Titanium to Budget.KILOGRAM,
                Species.Gold to Budget.GRAM,
            ),
            width = 20, height = 20,
        )
        val start = starterVessel(OutofspaceConfig().initialGrid).copy(bodies = listOf(post))
        val aged = Save.write(start).replaceFirst(
            "outofspace ${Save.VERSION}",
            "outofspace ${Save.WORKED_SHELVES_VERSION - 1}",
        )
        val economy = assertNotNull(Save.read(aged).bodies.single().station)

        assertEquals(0L, economy.market.holdings().total, "an old file kept its shelves")
        assertEquals(
            post.station!!.ore.total + post.station!!.market.holdings().total, economy.ore.total,
            "the shelves did not all reach the heap",
        )
        // ✅ **All of it, the pure metal included.** A shelf below this version cannot say which of
        // its species got there honestly, and keeping the ones that look legitimate would be
        // guessing about the player's history.
        assertEquals(7L * tonne, economy.ore[Species.Iron], "the iron was judged legitimate and kept")
        assertEquals(Budget.GRAM, economy.ore[Species.Gold], "the dust was dropped rather than reworked")
    }

    @Test
    fun `a current save keeps its shelves where they are`() {
        // The other half of the gate: the migration is keyed on the version, not on what a shelf
        // looks like, so a station that has legitimately separated a gram of something keeps it.
        val post = station(
            ore = Mixture.of(Species.Forsterite to 3L * tonne, energy = 0L),
            market = Market.of(Species.Iron to 7L * tonne, Species.Gold to Budget.GRAM),
            width = 20, height = 20,
        )
        val start = starterVessel(OutofspaceConfig().initialGrid).copy(bodies = listOf(post))
        val economy = assertNotNull(Save.read(Save.write(start)).bodies.single().station)

        assertEquals(7L * tonne, economy.market.stockOf(Species.Iron), "a current save lost its shelves")
        assertEquals(Budget.GRAM, economy.market.stockOf(Species.Gold))
        assertEquals(3L * tonne, economy.ore.total, "the heap grew on a save that needed no migration")
    }
}
