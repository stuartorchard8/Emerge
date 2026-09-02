package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Assembly
import org.emerge.demo.outofspace.world.BodyKind
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.DockNode
import org.emerge.demo.outofspace.world.Docking
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Market
import org.emerge.demo.outofspace.world.Member
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Station
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Weld
import org.emerge.demo.outofspace.world.Welding
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DockingPort
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The weld forest: what it can say that one nullable berth could not, and the one thing that was
 * silently wrong for as long as a held member was held out of the collision set as well as out of
 * the integrator.
 *
 * `WeldTest` keeps the conservation properties — capture and release minting nothing, both members
 * leaving at the pair's spin. Those are about the *physics* and are unchanged by the shape of the
 * representation, which is why they are still over there and were not touched by this.
 */
class AssemblyTest {

    private val cfg = OutofspaceConfig()

    // ── The forest, as data ──────────────────────────────────────────────────

    private fun weld(child: Int, parent: Int) = Weld(
        childId = child, parentId = parent,
        childX = 0L, childY = 0L, childAng = 0,
        portTile = TileIndex(0), nodeIndex = 0,
    )

    @Test
    fun `a member hangs off its parent and roots at the top of the chain`() {
        val a = Assembly.NONE.plus(weld(2, Member.VESSEL)).plus(weld(3, 2))
        assertEquals(Member.VESSEL, a.rootOf(3), "a grandchild did not root at the vessel")
        assertEquals(2, assertNotNull(a.weldTo(3)).parentId, "the chain lost its middle")
        assertTrue(a.isHeld(3) && a.isHeld(2), "a held member said it was free")
        assertTrue(!a.isHeld(Member.VESSEL), "the root said it was held")
    }

    @Test
    fun `descendants come out parents before children`() {
        // Added child-first on purpose: the order of [Assembly.welds] must not decide the walk.
        val a = Assembly(listOf(weld(3, 2), weld(2, Member.VESSEL)))
        assertEquals(listOf(2, 3), a.descendants(Member.VESSEL).map { it.childId }, "a child was walked before its parent")
    }

    @Test
    fun `a member cannot have two parents and cannot close a loop`() {
        val a = Assembly.NONE.plus(weld(2, Member.VESSEL))
        assertEquals(a, a.plus(weld(2, 3)), "a member took a second parent")
        val chain = a.plus(weld(3, 2))
        assertEquals(chain, chain.plus(weld(Member.VESSEL, 3)), "a loop closed")
        assertEquals(a, a.plus(weld(5, 5)), "a member welded to itself")
    }

    @Test
    fun `letting one member go leaves what hangs off it welded to it`() {
        val a = Assembly.NONE.plus(weld(2, Member.VESSEL)).plus(weld(3, 2))
        val cut = a.without(2)
        assertTrue(!cut.isHeld(2), "the released member is still held")
        // ⛔ The subtree leaves as its own assembly rather than being scattered — letting go of a
        // terminal with a second ship moored on the far side must not also let go of the ship.
        assertEquals(2, cut.rootOf(3), "what hung off the released member came adrift")
    }

    // ── The world ────────────────────────────────────────────────────────────

    /** A hull with one docking port at (16,8), pointing right, and no momentum anywhere. */
    private fun quietShip(): Pair<VesselState, DockingPort> {
        RockSpawner.enabled = false
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        fun hull(x: Int, y: Int) { if (deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in 2..14) { hull(x, 4); hull(x, 12) }
        for (y in 4..12) { hull(2, y) }
        val port = DockingPort(grid.tile(16, 8), Direction.Right)
        deck += port
        val s = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(arrayOfNulls<Segment>(grid.size).toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
        return s to port
    }

    /** A station whose berth [node] is lined up with [port]'s mouth, still and silent. */
    private fun stationAt(s: VesselState, port: DockingPort, node: DockNode, id: Int): RigidBody {
        val station = RigidBody.stationShell(
            positionX = 0L, positionY = 0L,
            composition = Mixture.of(Species.Steel to Budget.KILOGRAM, energy = 0L),
            station = Station(Mixture.EMPTY, Market.of(Species.Iron to Budget.TONNE), id = id, docks = listOf(node)),
        )
        return station.copy(
            positionX = station.positionX + (Docking.berthWorldX(s.grid, port, s.pose) - Docking.nodeWorldX(node, station.pose)),
            positionY = station.positionY + (Docking.berthWorldY(s.grid, port, s.pose) - Docking.nodeWorldY(node, station.pose)),
        )
    }

    private fun run(state: VesselState, ticks: Int, edit: Edit? = null): VesselState {
        var s = state
        val inputs = mapOf(PlayerId(0) to OutofspaceInput(listOfNotNull(edit)))
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    /**
     * A pebble at [x],[y] closing to the **left** at one [perTick]th of a tile a tick.
     *
     * ⚠️ Stated as a fraction rather than as a speed because `p = m·v / PER_TILE` written out is
     * `mass × 3.3e8`, which leaves `Long` for any rock worth throwing — the rescale's standing
     * lesson, and it reads as a rock that simply does not move. As a divisor the mass cancels first.
     */
    private fun pebble(x: Long, y: Long, perTick: Long): RigidBody {
        val rock = RigidBody.rockBlob(
            radius = 1, positionX = x, positionY = y,
            composition = Mixture.of(Species.Forsterite to 100L * Budget.KILOGRAM, energy = 0L),
        )
        return rock.copy(impulseX = -rock.mass / perTick)
    }

    /**
     * ⛔ **The bug this was all for.**
     *
     * A held member used to be dropped from the body list to stop the sweep integrating it, and the
     * body list is also the collision set — so a rock aimed at a docked station sailed clean through
     * it and lodged in it the instant the clamps opened. Fired from clear of the far face, the rock
     * must still be on the side it started.
     *
     * ⚠️ Stated as *which side of the station it ends on* rather than as a position or a speed,
     * because that is the difference between the two behaviours and nothing else is: a rock that is
     * turned back stays outside, and a rock that is not is thirty tiles past.
     */
    @Test
    fun `a rock cannot fly through a station the ship is docked to`() {
        val (ship, port) = quietShip()
        val node = DockNode(0, 10, Direction.Left)
        val world = ship.copy(bodies = listOf(stationAt(ship, port, node, id = 9)))
        var s = run(world, 1, Edit.Dock(port.center))
        assertNotNull(s.berth, "nothing docked, so nothing was proven")

        val station = s.bodies.single { it.kind == BodyKind.STATION }
        val faceX = station.comX + station.width * Flight.PER_TILE / 2L
        // Eight tiles clear of the far face, closing at a third of a tile a tick.
        //
        // ⚠️ **Aimed seven tiles off the station's waist, and that is not cosmetic.** Fired down the
        // middle the rock lines up with the ship behind it, so a rock that sailed through the station
        // bounced off the *hull* and came back — and the test passed for the wrong reason. Verified:
        // with the held geometry taken out this fails, and down the middle it does not.
        val aimY = station.comY - 7L * Flight.PER_TILE
        s = s.copy(bodies = s.bodies + pebble(faceX + 8L * Flight.PER_TILE, aimY, 3L))
        var struck = false
        repeat(150) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (s.impacts.isNotEmpty()) struck = true
        }

        val rock = s.bodies.single { it.kind != BodyKind.STATION }
        assertTrue(struck, "the rock never touched the station at all")
        assertTrue(
            rock.comX > station.comX,
            "the rock passed through the station: it is ${(station.comX - rock.comX) / Flight.PER_TILE} tiles beyond its centre",
        )
    }

    /**
     * ⛔ **And the assembly is what it pushes**, not the hull on its own.
     *
     * A rigid assembly is one body, so a rock bouncing off a docked terminal must shove the ship as
     * well — through the same [VesselState.bodyImpulseX] store any other contact is booked through,
     * with the ledger closed on both sides.
     */
    @Test
    fun `a rock that hits a docked station shoves the whole assembly`() {
        val (ship, port) = quietShip()
        val node = DockNode(0, 10, Direction.Left)
        val world = ship.copy(bodies = listOf(stationAt(ship, port, node, id = 9)))
        var s = run(world, 1, Edit.Dock(port.center))
        val station = s.bodies.single { it.kind == BodyKind.STATION }
        val faceX = station.comX + station.width * Flight.PER_TILE / 2L
        s = s.copy(bodies = s.bodies + pebble(faceX + 2L * Flight.PER_TILE, station.comY, 3L))

        val before = s.vesselImpulseX
        repeat(60) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }

        assertTrue(s.vesselImpulseX < before, "the assembly was not pushed by a rock landing on it")
        assertEquals(0L, s.momentumBalanceX, "momentum was minted by a touch on a held member")
        assertEquals(0L, s.momentumBalanceY, "momentum was minted by a touch on a held member")
    }

    // ── Two members ──────────────────────────────────────────────────────────

    /**
     * The whole point of the exercise: a second berth composes with the first instead of replacing
     * it, and both members hold their frozen place.
     *
     * ⚠️ Assembled by hand rather than by docking twice, because this ship has one port and
     * [Edit.Dock] occupies it. What is being pinned is the walk and the fold, which do not care how
     * the welds arrived.
     */
    @Test
    fun `two members welded to one root both hold their place`() {
        val (ship, port) = quietShip()
        val left = stationAt(ship, port, DockNode(0, 10, Direction.Left), id = 9)
        val far = left.copy(
            positionX = left.positionX + 60L * Flight.PER_TILE,
            station = Station(Mixture.EMPTY, Market.empty(), id = 4, docks = left.station!!.docks),
        )
        var s = ship.copy(
            bodies = listOf(left, far),
            assembly = Assembly.NONE
                .plus(Weld(9, Member.VESSEL, ship.pose.toLocalX(left.comX, left.comY), ship.pose.toLocalY(left.comX, left.comY), 0, port.center, 0))
                .plus(Weld(4, Member.VESSEL, ship.pose.toLocalX(far.comX, far.comY), ship.pose.toLocalY(far.comX, far.comY), 0, port.center, 0)),
        )
        // A shove, so the assembly is actually going somewhere while it is being watched.
        s = s.copy(vesselImpulseX = 400_000_000_000L, debugImpulseX = 400_000_000_000L, angImpulse = 9_000_000_000_000L, bodyAngImpulse = -9_000_000_000_000L)

        val frozen = s.assembly.welds.associateBy { it.childId }
        repeat(120) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }

        assertEquals(2, s.bodies.count { it.kind == BodyKind.STATION }, "a member fell out of the world")
        // The pose primitives round trip at 2.7e-6 of a tile — `PLAN_trig_free_rotation.md`.
        val slack = 1_000L
        for (body in s.bodies) {
            val weld = assertNotNull(frozen[body.station!!.id])
            assertTrue(
                (weld.childX - s.pose.toLocalX(body.comX, body.comY)) in -slack..slack &&
                    (weld.childY - s.pose.toLocalY(body.comX, body.comY)) in -slack..slack,
                "member ${body.station!!.id} drifted out of its berth",
            )
            assertEquals(weld.childAng, body.ang.raw - s.pose.ang.raw, "member ${body.station!!.id} twisted")
        }
    }

    /**
     * The fold is the pairwise answer, twice — which is what makes [Assembly.distribution] a
     * generalisation of the two-member `jointOf` rather than a second opinion about it.
     */
    @Test
    fun `folding two members agrees with combining them one at a time`() {
        val (ship, _) = quietShip()
        val a = RigidBody.stationShell(
            positionX = 30L * Flight.PER_TILE, positionY = 4L * Flight.PER_TILE,
            composition = Mixture.of(Species.Steel to Budget.KILOGRAM, energy = 0L),
            station = Station(Mixture.EMPTY, Market.empty(), id = 9),
        )
        val b = RigidBody.stationShell(
            positionX = -20L * Flight.PER_TILE, positionY = -11L * Flight.PER_TILE,
            composition = Mixture.of(Species.Steel to Budget.KILOGRAM, energy = 0L),
            station = Station(Mixture.EMPTY, Market.empty(), id = 4),
        )
        val s = ship.copy(
            bodies = listOf(a, b),
            assembly = Assembly.NONE
                .plus(Weld(9, Member.VESSEL, ship.pose.toLocalX(a.comX, a.comY), ship.pose.toLocalY(a.comX, a.comY), 0, TileIndex(0), 0))
                .plus(Weld(4, Member.VESSEL, ship.pose.toLocalX(b.comX, b.comY), ship.pose.toLocalY(b.comX, b.comY), 0, TileIndex(0), 0)),
        )

        val folded = s.assemblyDistribution
        // The same two joins, spelled out: vessel with a, then that with b.
        val first = Welding.jointOf(s.pose, s.distribution, a).about
        val second = Welding.jointOf(s.pose, first, b).about

        assertEquals(second.mass, folded.mass, "the fold lost mass")
        assertEquals(second.comMilliX, folded.comMilliX, "the fold put the centre somewhere else in x")
        assertEquals(second.comMilliY, folded.comMilliY, "the fold put the centre somewhere else in y")
        assertEquals(second.gyrationSq, folded.gyrationSq, "the fold answered a different inertia")
    }

    @Test
    fun `an empty assembly costs the vessel nothing at all`() {
        val (ship, _) = quietShip()
        // ⛔ Identity, not "close enough": an undocked vessel must accumulate no rounding, ever.
        assertEquals(ship.distribution, ship.assemblyDistribution, "a free vessel paid for a fold")
    }

    // ── The file ─────────────────────────────────────────────────────────────

    @Test
    fun `an assembly of two survives a round trip`() {
        val (ship, port) = quietShip()
        val a = stationAt(ship, port, DockNode(0, 10, Direction.Left), id = 9)
        val b = a.copy(
            positionX = a.positionX + 60L * Flight.PER_TILE,
            station = Station(Mixture.EMPTY, Market.empty(), id = 4, docks = a.station!!.docks),
        )
        val assembly = Assembly.NONE
            .plus(Weld(9, Member.VESSEL, 111L, -222L, 3_000, port.center, 2))
            .plus(Weld(4, 9, -333L, 444L, -5_000, port.center, 1))
        val s = ship.copy(bodies = listOf(a, b), assembly = assembly, dockedThrustAllowed = true)

        val back = Save.read(Save.write(s))
        assertEquals(assembly.welds, back.assembly.welds, "the assembly came back different")
        assertTrue(back.dockedThrustAllowed, "the interlock switch did not survive the file")
    }

    /**
     * ⚠️ A file written before the forest carries one `dock` line, and it must land as a one-weld
     * assembly hanging off the vessel — with the interlock, which used to ride in its last column.
     */
    @Test
    fun `a pre-forest berth reads as one weld on the vessel`() {
        val (ship, port) = quietShip()
        val a = stationAt(ship, port, DockNode(0, 10, Direction.Left), id = 9)
        val written = Save.write(ship.copy(bodies = listOf(a)))
            .replace("outofspace 25", "outofspace 24")
            .trimEnd() + "\ndock 9 ${port.center.index} 2 111 -222 3000 1\n"

        val back = Save.read(written)
        val weld = assertNotNull(back.berth, "a v24 berth reloaded flying free")
        assertEquals(9, weld.childId, "the berth named the wrong member")
        assertEquals(Member.VESSEL, weld.parentId, "the berth hung off something other than the vessel")
        assertEquals(2, weld.nodeIndex, "the berth lost which node it used")
        assertEquals(111L to -222L, weld.childX to weld.childY, "the frozen offset moved")
        assertEquals(Coord(3000).raw, weld.childAng, "the frozen angle moved")
        assertTrue(back.dockedThrustAllowed, "the interlock in the dock line's last column was dropped")
    }

    @Test
    fun `a station may not answer to the vessel's id`() {
        var threw = false
        try {
            Station(Mixture.EMPTY, Market.empty(), id = Member.VESSEL)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "a station took the vessel's id")
    }

}
