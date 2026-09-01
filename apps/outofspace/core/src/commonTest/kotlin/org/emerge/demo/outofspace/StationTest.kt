package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.compositionOf
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BodyKind
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.DockNode
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Market
import org.emerge.demo.outofspace.world.Pose
import org.emerge.demo.outofspace.world.RATE
import org.emerge.demo.outofspace.world.RigidBody
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
            bodies = RockSpawner.process(Pose.IDENTITY, 10_000L + step, bodies, step * 50L, 0L)
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
        for (step in 1..60) bodies = RockSpawner.process(Pose.IDENTITY, 10_000L + step, bodies, 0L, 0L)

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
    fun `a glutted station works its ore down a kilogram at a time`() {
        // ⚠️ **The dominant species is IRON on purpose — an element, which cannot be cracked.** With
        // a compound dominant, the shelf figure is separation minus whatever the cracker took back
        // off it in the same ticks, and this test would be measuring both plants at once. It caught
        // exactly that: 100 kg separated read as 32 kg on the shelf.
        val ore = Mixture.of(Species.Iron to 10L * tonne, Species.Nickel to tonne, energy = 0L)
        var s = Station(ore, Market.empty())
        val before = s.ore.total

        repeat(100) { s = s.worked() }

        assertEquals(before - 100L * RATE, s.ore.total, "the reserve did not fall by a kilogram a tick")
        assertEquals(100L * RATE, s.market.stockOf(Species.Iron), "iron never reached the shelves")
        assertEquals(0L, s.market.stockOf(Species.Nickel), "the minority species was separated first")
    }

    @Test
    fun `a station keeps its identity and its berths through a tick of work`() {
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
    fun `a reserve holding less than a kilogram does nothing at all`() {
        // Go/no-go at the full kilogram: no dribbling out what is left.
        val s = Station(Mixture.of(Species.Iron to RATE - 1L, energy = 0L), Market.empty())
        assertEquals(s.ore.total, s.worked().ore.total, "a part-kilogram reserve was dribbled out")
    }

    @Test
    fun `a station already rich in the elements does not crack the compound`() {
        // ⛔ **This is §3.4's mechanism from the other side, and it is the discriminating test.**
        // A compound's *list* price is exactly the sum of its elements', so the only thing that can
        // ever make cracking pay is the **station-local stock discount**. Give the elements a bigger
        // glut than the compound and the discount points the other way — so nothing happens.
        //
        // ⚠️ **The naive version of this test was wrong and the failure was informative.** It gave a
        // station 10 t of forsterite and no elements and expected no cracking "because list prices
        // are neutral". But 10 t is `REFERENCE_STOCK` — a glut by the curve's own definition — so it
        // cracked, correctly. Neutrality holds at *list*, and a station is never at list.
        var s = Station(
            Mixture.EMPTY,
            Market.of(
                Species.Forsterite to 10L * tonne,
                Species.Magnesium to 500L * tonne,
                Species.Silicon to 500L * tonne,
                Species.Oxygen to 500L * tonne,
            ),
        )
        val before = s.market.stockOf(Species.Forsterite)
        repeat(50) { s = s.worked() }
        assertEquals(before, s.market.stockOf(Species.Forsterite), "a station cracked into shelves already full")
    }

    @Test
    fun `cracking is a slow drift toward elements, and it self-limits`() {
        // ⚠️ **A consequence worth writing down: with any stock at all a compound is fractionally
        // discounted against its own (emptier) element shelves, so a station cracks CONTINUOUSLY
        // rather than only when glutted.** At a kilogram a tick that is a slow background drift, and
        // it stops on its own as the element shelves fill. So a station is a good place to buy
        // *elements* from — which is exactly what a player building a ship wants — and that is
        // emergent rather than authored.
        var s = Station(Mixture.EMPTY, Market.of(Species.Quartz to 40L * tonne))
        repeat(400) { s = s.worked() }
        val cracked = 40L * tonne - s.market.stockOf(Species.Quartz)
        assertTrue(cracked > 0L, "nothing cracked at all")
        assertTrue(cracked <= 400L * RATE, "more than a kilogram a tick was cracked")
        assertEquals(
            cracked,
            s.market.stockOf(Species.Silicon) + s.market.stockOf(Species.Oxygen),
            "mass was lost cracking quartz",
        )
    }

    @Test
    fun `a station glutted in one compound cracks it, and stops when it stops paying`() {
        // The mechanism: a station drowning in forsterite quotes forsterite cheap while its
        // magnesium, silicon and oxygen shelves are near list — so cracking pays *because* it is
        // over-supplied, and it self-limits as the element shelves fill.
        var s = Station(Mixture.EMPTY, Market.of(Species.Forsterite to 500L * tonne))
        repeat(200) { s = s.worked() }

        assertTrue(s.market.stockOf(Species.Magnesium) > 0L, "a glutted station never cracked anything")
        assertTrue(s.market.stockOf(Species.Silicon) > 0L)
        assertTrue(s.market.stockOf(Species.Oxygen) > 0L)
        // And the mass is conserved through the crack, remainder included.
        val elements = compositionOf(Species.Forsterite).sumOf { s.market.stockOf(it.element) }
        assertEquals(500L * tonne - s.market.stockOf(Species.Forsterite), elements, "mass was lost cracking")
    }

    @Test
    fun `cracking takes the most abundant compound worth cracking`() {
        // Two gluts, one much larger. The richer shelf goes first — Stu's ordering.
        var s = Station(
            Mixture.EMPTY,
            Market.of(Species.Forsterite to 500L * tonne, Species.Quartz to 100L * tonne),
        )
        repeat(20) { s = s.worked() }
        assertTrue(
            s.market.stockOf(Species.Forsterite) < 500L * tonne,
            "the richer shelf was not the one cracked",
        )
        assertEquals(100L * tonne, s.market.stockOf(Species.Quartz), "the poorer shelf was cracked first")
    }

    @Test
    fun `separating and cracking both happen on the same tick`() {
        // Two plants working two stockpiles. Making them take turns would be a rule about the code.
        val s = Station(
            Mixture.of(Species.Iron to 10L * tonne, energy = 0L),
            Market.of(Species.Forsterite to 500L * tonne),
        ).worked()
        assertEquals(RATE, s.market.stockOf(Species.Iron), "nothing was separated")
        assertTrue(s.market.stockOf(Species.Magnesium) > 0L, "nothing was cracked")
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
}
