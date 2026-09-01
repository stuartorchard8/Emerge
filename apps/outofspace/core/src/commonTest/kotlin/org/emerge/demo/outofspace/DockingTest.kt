package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BodyKind
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.DockNode
import org.emerge.demo.outofspace.world.Docking
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Market
import org.emerge.demo.outofspace.world.Pose
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Station
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.firstStation
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DockingPort
import org.emerge.demo.outofspace.world.starterWorld
import org.emerge.sim.core.physics.primitives.Coord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Whether two mouths may join — `PLAN_economy.md` §7, the geometry half.
 *
 * ⚠️ The test that discriminates is **the one where the ship is in exactly the right place pointing
 * exactly the wrong way**. Distance alone is easy to get right and says almost nothing: a station is
 * twenty tiles across, so "near it" is true from anywhere in the berth.
 */
class DockingTest {

    private val grid = Grid(24, 16)

    /** A ship whose docking port sits at (12,8) facing [facing], with the hull at [ang]. */
    private fun ship(facing: Direction = Direction.Right, ang: Coord = Coord(0)): Pair<VesselState, DockingPort> {
        val deck = DeckArray(grid)
        val port = DockingPort(grid.tile(12, 8), facing)
        deck += port
        val s = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(arrayOfNulls<org.emerge.demo.outofspace.world.Segment>(grid.size).toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
            ang = ang,
        )
        return s to port
    }

    /** A station whose single berth is its left-hand side, placed so that berth faces the ship. */
    private fun stationFacing(port: DockingPort, s: VesselState, gap: Long): RigidBody {
        // The ship's berth mouth, in the world.
        val mouthX = Docking.berthWorldX(grid, port, s.pose)
        val mouthY = Docking.berthWorldY(grid, port, s.pose)
        val node = DockNode(0, 5, Direction.Left)
        // Put the station's node mouth `gap` tiles beyond the ship's, along +x.
        val body = RigidBody.stationShell(
            width = 12, height = 12,
            positionX = 0L, positionY = 0L,
            composition = Mixture.of(Species.Steel to Budget.KILOGRAM, energy = 0L),
            station = Station(Mixture.EMPTY, Market.empty(), id = 7, docks = listOf(node)),
        )
        val nodeX = Docking.nodeWorldX(node, body.pose)
        val nodeY = Docking.nodeWorldY(node, body.pose)
        return body.copy(
            positionX = body.positionX + (mouthX + gap * Flight.PER_TILE - nodeX),
            positionY = body.positionY + (mouthY - nodeY),
        )
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    @Test
    fun `mouths that meet may dock`() {
        val (s, port) = ship()
        val station = stationFacing(port, s, gap = 0L)
        assertTrue(Docking.canDock(grid, port, s.pose, station, 0), "two mouths in the same place refused")
    }

    @Test
    fun `mouths too far apart may not dock`() {
        val (s, port) = ship()
        val station = stationFacing(port, s, gap = Docking.RANGE_TILES + 1)
        assertTrue(!Docking.canDock(grid, port, s.pose, station, 0), "docked from outside the range")
    }

    @Test
    fun `just inside the range is enough`() {
        val (s, port) = ship()
        val station = stationFacing(port, s, gap = Docking.RANGE_TILES)
        assertTrue(Docking.canDock(grid, port, s.pose, station, 0), "the stated range is not reachable")
    }

    @Test
    fun `a ship in the right place pointing the wrong way may not dock`() {
        // ⛔ The discriminating case. The mouths are a tile apart and the alignment is 180° out — a
        // distance-only test passes this, and a player would dock backwards through their own hull.
        val (s, port) = ship()
        val station = stationFacing(port, s, gap = 0L)
        val (backwards, backPort) = ship(facing = Direction.Left)
        assertTrue(
            !Docking.canDock(grid, backPort, backwards.pose, station, 0),
            "a port pointing away from the berth was allowed to dock",
        )
    }

    @Test
    fun `a rolled ship still docks if its port still lines up`() {
        // The whole reason alignment is a dot product of world-frame directions rather than a
        // difference of angles: the ship's hull angle and its port's facing compose, and neither is
        // meaningful on its own.
        val quarter = Coord(Int.MAX_VALUE / 2)
        val (s, port) = ship(facing = Direction.Up, ang = quarter)
        val station = stationFacing(port, s, gap = 0L)
        assertTrue(Docking.canDock(grid, port, s.pose, station, 0), "a rolled ship could not berth")
    }

    @Test
    fun `an unknown berth is refused rather than crashing`() {
        val (s, port) = ship()
        val station = stationFacing(port, s, gap = 0L)
        assertTrue(!Docking.canDock(grid, port, s.pose, station, 4), "a berth that does not exist was accepted")
    }

    // ── The starting world ───────────────────────────────────────────────────

    @Test
    fun `a new game opens with exactly one station, and it is not on top of the ship`() {
        val world = starterWorld(OutofspaceConfig().initialGrid)
        val stations = world.bodies.filter { it.kind == BodyKind.STATION }
        assertEquals(1, stations.size, "a new game did not open with one station")
        val post = stations.single()
        assertEquals(RigidBody.STATION_TILES, post.width, "the first station is not the standard size")
        // Far enough that reaching it is a flight; the grid is a few dozen tiles across.
        assertTrue(post.positionX > 100L * Flight.PER_TILE, "the station is parked on the launch pad")
    }

    @Test
    fun `the first station has a berth on every side`() {
        val economy = assertNotNull(firstStation().station)
        assertEquals(4, economy.docks.size, "a station approachable from one bearing is a chore")
        assertEquals(
            setOf(Direction.Up, Direction.Down, Direction.Left, Direction.Right),
            economy.docks.map { it.facing }.toSet(),
        )
        // Every berth must be on a cell the station actually has.
        val post = firstStation()
        for (node in economy.docks) {
            assertTrue(
                post.cells[node.cellY * post.width + node.cellX],
                "berth at ${node.cellX},${node.cellY} is on empty space",
            )
        }
    }

    @Test
    fun `the starter world's fixtures are untouched by the station`() {
        // ⛔ `starterWorld` is deliberately not `starterVessel`: several hundred fixtures build their
        // world from the latter and must not inherit a twenty-tile body, its contacts, its industry
        // or its save lines.
        assertEquals(
            emptyList(),
            org.emerge.demo.outofspace.world.starterVessel(OutofspaceConfig().initialGrid).bodies,
        )
    }

    @Test
    fun `a station's berths survive a round trip`() {
        val world = starterWorld(OutofspaceConfig().initialGrid)
        val back = Save.read(Save.write(world)).bodies.single { it.kind == BodyKind.STATION }
        val economy = assertNotNull(back.station, "the station lost its economy")
        assertEquals(1, economy.id, "the station lost its identity")
        assertEquals(assertNotNull(firstStation().station).docks, economy.docks, "the berths did not survive")
    }
}
