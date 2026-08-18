package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.remapped
import org.emerge.demo.outofspace.world.FlowCursors
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.Motion
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.EdgeGrid
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.MomentumField
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachine
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [VesselState.remapped] — the function that moves the entire world onto a different
 * grid, translated by (dx, dy) tiles.
 *
 * The plan calls this "the phase that must not be rushed — it is the only one where a bug is cheap
 * to find." Every field in VesselState must be remapped correctly, or the ledger breaks.
 */
class RemappedTest {

    private fun simpleWorld(w: Int, h: Int): VesselState {
        val grid = Grid(w, h)
        val deck = DeckArray(grid)
        for (x in 1 until w - 1) {
            deck += Hull(grid.tile(x, 1))
            deck += Hull(grid.tile(x, h - 2))
        }
        for (y in 2 until h - 2) {
            deck += Hull(grid.tile(1, y))
            deck += Hull(grid.tile(w - 2, y))
        }
        return VesselState(grid, deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
    }

    private fun populatedWorld(w: Int = 20, h: Int = 14): VesselState {
        val grid = Grid(w, h)
        val deck = DeckArray(grid)
        // Hull
        for (x in 1 until w - 1) {
            deck += Hull(grid.tile(x, 1))
            deck += Hull(grid.tile(x, h - 2))
        }
        for (y in 2 until h - 2) {
            deck += Hull(grid.tile(1, y))
            deck += Hull(grid.tile(w - 2, y))
        }
        // Two obstacles in the middle of the room. Guarded because a small `populatedWorld` puts
        // the second of them on the starboard wall, and the deck refuses to be built over.
        for (t in listOf(grid.tile(5, 5), grid.tile(10, 5))) if (deck[t] == null) deck += Hull(t)
        // Diverter
        val diverters = FlowCursors(mapOf(grid.tile(7, 7) to 1))
        // Air with uniform mass and energy
        val airMass = MassArray(grid.size) { _,_ -> 100L}
        val airEnergy = EnergyArray(grid.size) { 500L }
        val air = Stuff.from(airMass, airEnergy)
        // Momentum with non-zero values
        val xEdges = EdgeGrid(grid).xEdgeCount
        val yEdges = EdgeGrid(grid).yEdgeCount
        val momX = LongArray(xEdges) { 10L }
        val momY = LongArray(yEdges) { 20L }
        val momentum = MomentumField.of(EdgeGrid(grid), momX, momY)
        // Pipe air: empty
        val pipeAir = Stuff.gas(MassArray(grid.size))
        val pipeMomentum = MomentumField.of(EdgeGrid(grid), LongArray(xEdges), LongArray(yEdges))
        // One body
        val bodies = listOf(
            RigidBody.rockBlob(
                radius = 2,
                positionX = 3L * Flight.PER_TILE,
                positionY = 3L * Flight.PER_TILE,
                composition = OutofspaceReducer.DEFAULT_ORE_BODY
            )
        )
        return VesselState(
            grid = grid,
                        deck = deck,
            diverters = diverters,
            air = air,
            momentum = momentum,
            pipeAir = pipeAir,
            pipeMomentum = pipeMomentum,
            bodies = bodies,
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )
    }

    // ── Identity (zero offset) ───────────────────────────────────────────

    @Test
    fun `zero offset is the identity`() {
        val s0 = simpleWorld(20, 14)
        val s1 = s0.remapped(s0.grid, 0, 0)
        assertEquals(s0.grid, s1.grid)
        assertEquals(s0.deck.size, s1.deck.size)
        assertEquals(s0.grid.tiles.map { s0.deck[it] }, s1.grid.tiles.map { s1.deck[it] })
        assertEquals(s0.conduits, s1.conduits)
        assertEquals(s0.diverters.forkCursors, s1.diverters.forkCursors)
        assertEquals(s0.air.copyMass().data.contentToString(), s1.air.copyMass().data.contentToString())
        assertEquals(s0.air.copyEnergy().data.contentToString(), s1.air.copyEnergy().data.contentToString())
        assertEquals(s0.pipeAir.copyMass().data.contentToString(), s1.pipeAir.copyMass().data.contentToString())
        assertEquals(s0.pipeAir.copyEnergy().data.contentToString(), s1.pipeAir.copyEnergy().data.contentToString())
        assertTrue(s0.momentum.copyX().contentEquals(s1.momentum.copyX()), "momentum X")
        assertTrue(s0.momentum.copyY().contentEquals(s1.momentum.copyY()), "momentum Y")
        assertTrue(s0.pipeMomentum.copyX().contentEquals(s1.pipeMomentum.copyX()), "pipeMomentum X")
        assertTrue(s0.pipeMomentum.copyY().contentEquals(s1.pipeMomentum.copyY()), "pipeMomentum Y")
        assertEquals(s0.bodies, s1.bodies)
        assertEquals(s0.baselineAirMass, s1.baselineAirMass)
        assertEquals(s0.baselineAirEnergy, s1.baselineAirEnergy)
        assertEquals(s0.baselineEnergy, s1.baselineEnergy)

    }

    // ── Positive offset: grow left and up ────────────────────────────────

    @Test
    fun `machines remap correctly with positive dx and dy`() {
        val s0 = simpleWorld(10, 8)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 4, oldGrid.height + 3)
        val dx = 4
        val dy = 3

        val s1 = s0.remapped(newGrid, dx, dy)

        // Old machine at (5, 5) should now be at (9, 8)
        val oldTile = oldGrid.tile(5, 5)
        val newTile = newGrid.tile(9, 8)
        assertEquals(s0.deck[oldTile], s1.deck[newTile])
        // Edge machine
        val edgeTile = oldGrid.tile(0, 0)
        val edgeNewTile = newGrid.tile(dx, dy)
        assertEquals(s0.deck[edgeTile], s1.deck[edgeNewTile])
    }

    @Test
    fun `conduits remap correctly`() {
        val s0 = populatedWorld(12, 8)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 3, oldGrid.height + 2)
        val dx = 3
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        for (c in Conduit.entries) {
            val oldLayer = s0.conduits[c]
            val newLayer = s1.conduits[c]
            for (x in 0 until oldGrid.width) {
                for (y in 0 until oldGrid.height) {
                    val oldTile = oldGrid.tile(x, y)
                    val newTile = newGrid.tile(x + dx, y + dy)
                    assertEquals(oldLayer[oldTile.index], newLayer[newTile.index],
                        "conduit $c at ($x,$y) -> ($x+$dx,$y+$dy)")
                }
            }
        }
    }

    @Test
    fun `bridges remap correctly`() {
        val s0 = simpleWorld(10, 8)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 2, oldGrid.height + 2)
        val dx = 2
        val dy = 2
        val bridgeTile = oldGrid.tile(5, 5)
        val s0withBridge = s0.copy(deck = s0.deck.copyOf().also {
            it += Bridge(bridgeTile, Direction.Right)
        })
        val s1 = s0withBridge.remapped(newGrid, dx, dy)
        val newTile = newGrid.tile(5 + dx, 5 + dy)

        // Re-anchored, not merely copied: a machine's centre is a tile index and a tile index means
        // a different place on a different grid. Comparing the two machines for equality is what
        // this used to do and it cannot work — the whole point is that they differ by exactly this.
        val moved = s1[newTile] as? Bridge ?: error("no bridge at the remapped tile")
        assertEquals(newTile, moved.center, "it kept its old anchor")
        assertEquals(Direction.Right, moved.facing)
        // And its span came with it. A footprint is a line for a bridge, so this is also the check
        // that a non-square footprint survives a change of lattice.
        for (part in moved.tiles(newGrid)) {
            assertTrue(!s1.occupancy.isFree(part), "the span lost tile $part")
        }
    }

    @Test
    fun `diverters remap correctly`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 3, oldGrid.height + 2)
        val dx = 3
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        // Diverter at (7, 7) should move to (10, 9)
        val oldTile = oldGrid.tile(7, 7)
        val newTile = newGrid.tile(10, 9)
        assertEquals(1, s1.diverters.forkCursors[newTile])
    }

    @Test
    fun `air field remaps correctly`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 4, oldGrid.height + 3)
        val dx = 4
        val dy = 3

        val s1 = s0.remapped(newGrid, dx, dy)

        for (x in 0 until oldGrid.width) {
            for (y in 0 until oldGrid.height) {
                val oldTile = oldGrid.tile(x, y)
                val newTile = newGrid.tile(x + dx, y + dy)
                for (s in Species.entries) {
                    val oldMass = s0.air.massOf(oldTile, s)
                    val newMass = s1.air.massOf(newTile, s)
                    assertEquals(oldMass, newMass, "air mass at ($x,$y) species=$s")
                }
                assertEquals(s0.air.copyEnergy()[oldTile], s1.air.copyEnergy()[newTile],
                    "air energy at ($x,$y)")
            }
        }
    }

    @Test
    fun `pipeAir field remaps correctly`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 2, oldGrid.height + 2)
        val dx = 2
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        for (x in 0 until oldGrid.width) {
            for (y in 0 until oldGrid.height) {
                val oldTile = oldGrid.tile(x, y)
                val newTile = newGrid.tile(x + dx, y + dy)
                for (s in Species.entries) {
                    val oldMass = s0.pipeAir.massOf(oldTile, s)
                    val newMass = s1.pipeAir.massOf(newTile, s)
                    assertEquals(oldMass, newMass)
                }
            }
        }
    }

    // ── Edge fields: momentum ────────────────────────────────────────────

    @Test
    fun `momentum x-faces remap correctly`() {
        val s0 = populatedWorld(10, 8)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 3, oldGrid.height + 2)
        val dx = 3
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        for (oy in 0 until oldGrid.height) {
            for (ox in 0..oldGrid.width) {
                val nx = ox + dx
                val ny = oy + dy
                if (ny >= 0 && ny < newGrid.height && nx >= 0 && nx <= newGrid.width) {
                    val oldEdge = EdgeGrid(oldGrid).xEdge(ox, oy)
                    val newEdge = EdgeGrid(newGrid).xEdge(nx, ny)
                    assertEquals(s0.momentum.copyX()[oldEdge], s1.momentum.copyX()[newEdge],
                        "momentum x-face at ($ox,$oy)")
                }
            }
        }
    }

    @Test
    fun `momentum y-faces remap correctly`() {
        val s0 = populatedWorld(10, 8)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 3, oldGrid.height + 2)
        val dx = 3
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        for (ox in 0 until oldGrid.width) {
            for (oy in 0..oldGrid.height) {
                val nx = ox + dx
                val ny = oy + dy
                if (ny >= 0 && ny <= newGrid.height && nx >= 0 && nx < newGrid.width) {
                    val oldEdge = EdgeGrid(oldGrid).yEdge(ox, oy)
                    val newEdge = EdgeGrid(newGrid).yEdge(nx, ny)
                    assertEquals(s0.momentum.copyY()[oldEdge], s1.momentum.copyY()[newEdge],
                        "momentum y-face at ($ox,$oy)")
                }
            }
        }
    }

    @Test
    fun `pipeMomentum remaps correctly on both axes`() {
        val s0 = populatedWorld(10, 8)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 2, oldGrid.height + 3)
        val dx = 2
        val dy = 3

        val s1 = s0.remapped(newGrid, dx, dy)

        // x-faces
        for (oy in 0 until oldGrid.height) {
            for (ox in 0..oldGrid.width) {
                val nx = ox + dx
                val ny = oy + dy
                if (newGrid.inBounds(nx, ny) && ny < newGrid.height && nx <= newGrid.width) {
                    val oldEdge = EdgeGrid(oldGrid).xEdge(ox, oy)
                    val newEdge = EdgeGrid(newGrid).xEdge(nx, ny)
                    assertEquals(s0.pipeMomentum.copyX()[oldEdge], s1.pipeMomentum.copyX()[newEdge])
                }
            }
        }
        // y-faces
        for (ox in 0 until oldGrid.width) {
            for (oy in 0..oldGrid.height) {
                val nx = ox + dx
                val ny = oy + dy
                if (ny >= 0 && ny <= newGrid.height && nx >= 0 && nx < newGrid.width) {
                    val oldEdge = EdgeGrid(oldGrid).yEdge(ox, oy)
                    val newEdge = EdgeGrid(newGrid).yEdge(nx, ny)
                    assertEquals(s0.pipeMomentum.copyY()[oldEdge], s1.pipeMomentum.copyY()[newEdge])
                }
            }
        }
    }

    // ── Bodies ────────────────────────────────────────────────────────────

    /**
     * A rock does not move because the player built a row of hull off the port bow.
     *
     * **This replaces `bodies shift by dx_PER_TILE and dy_PER_TILE`, which asserted the opposite** —
     * correctly, while a body's position was stored in the grid's frame and every tile index moved
     * under it. Step 1 of `PLAN_rigid_bodies.md` moved bodies into the world, and the same physical
     * fact now has the opposite spelling: the body holds still and the *origin* is what moves.
     *
     * Both halves are asserted, because either alone would pass on a broken implementation. A body
     * that never moved would satisfy the first even if the pose had been left behind, and the grid
     * would then have slid out from under the whole asteroid field.
     */
    @Test
    fun `a grid that grows moves its origin, not its bodies`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 4, oldGrid.height + 3)
        val dx = 4
        val dy = 3

        val s1 = s0.remapped(newGrid, dx, dy)

        for (i in s0.bodies.indices) {
            assertEquals(s0.bodies[i].positionX, s1.bodies[i].positionX, "body $i moved in the world, x")
            assertEquals(s0.bodies[i].positionY, s1.bodies[i].positionY, "body $i moved in the world, y")
            // And the point of it: in the *grid* it has shifted by exactly the growth, so a body
            // that was over tile (5,5) is over tile (9,8), which is the same place on the deck.
            assertEquals(
                s0.bodies[i].localX(s0.pose) + dx * Flight.PER_TILE,
                s1.bodies[i].localX(s1.pose),
                "body $i is over the wrong tile now, x",
            )
            assertEquals(
                s0.bodies[i].localY(s0.pose) + dy * Flight.PER_TILE,
                s1.bodies[i].localY(s1.pose),
                "body $i is over the wrong tile now, y",
            )
        }
    }

    // ── Ledger identities ────────────────────────────────────────────────

    @Test
    fun `airBalance is preserved across remap`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 5, oldGrid.height + 4)
        val dx = 5
        val dy = 4

        val s1 = s0.remapped(newGrid, dx, dy)

        assertEquals(s0.airBalance, s1.airBalance, "airBalance must be preserved")
    }

    /**
     * ⚠️ **PARKED** — the identity is the whole test, so with [EnergyLedgers] silenced there is
     * nothing left to run. The mass twin above still covers that a remap moves a world intact.
     */
    @Ignore
    @Test
    fun `airEnergyBalance is preserved across remap`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 3, oldGrid.height + 2)
        val dx = 3
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        EnergyLedgers.assertPreserved(s0, s1, "remap")
    }

    @Test
    fun `momentumBalance is preserved across remap`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 4, oldGrid.height + 3)
        val dx = 4
        val dy = 3

        val s1 = s0.remapped(newGrid, dx, dy)

        // The full momentum identity:
        // vesselImpulse + momentum + pipeMomentum + exhaust + undelivered + body - debug == 0
        fun momentumX(s: VesselState) = s.momentumBalanceX

        fun momentumY(s: VesselState) = s.momentumBalanceY

        assertEquals(momentumX(s0), momentumX(s1), "momentumBalanceX must be preserved")
        assertEquals(momentumY(s0), momentumY(s1), "momentumBalanceY must be preserved")
    }

    @Test
    fun `massBalance is preserved across remap`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 3, oldGrid.height + 2)
        val dx = 3
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        // mass = mass + ventedMass + extractedMass (should be invariant)
        fun massBalance(s: VesselState) = s.mass + s.ventedMass + s.extractedMass
        assertEquals(massBalance(s0), massBalance(s1), "massBalance must be preserved")
    }

    @Test
    fun `a length of track keeps what it is made of across a remap`() {
        // The remap used to copy a segment's heat by hand and let `Conduits.with` re-derive its
        // mass from its kind's bill. That could not carry a composition anything had altered, and
        // it cannot carry a ghost at all — a half-built rail would have come back finished, or,
        // once laying stopped conjuring, every rail aboard would have come back a ghost.
        val base = populatedWorld()
        // `populatedWorld` lays no track, so state some: three tiles of rail across the room.
        val rails = MutableList<Segment?>(base.grid.size) { null }
        for (x in 4..6) rails[base.grid.tile(x, 7).index] = Segment(Conduit.Rail)
        val s0 = base.copy(conduits = Conduits.ofRails(rails))
        val laid = base.grid.tile(5, 7)
        val stuff = s0.conduits.tracks[Conduit.Rail]
        val iron = stuff[laid, Species.Iron]
        assertTrue(iron > 1L, "a length of rail should be made of some iron, got $iron")
        // Half-built, and rusted: neither is derivable from the fact that a rail is laid here.
        stuff[laid, Species.Iron] = iron / 2
        stuff[laid, Species.Oxygen] = 7L

        val newGrid = Grid(s0.grid.width + 3, s0.grid.height + 2)
        val s1 = s0.remapped(newGrid, 3, 2)
        val moved = newGrid.tile(s0.grid.xOf(laid) + 3, s0.grid.yOf(laid) + 2)

        val after = s1.conduits.tracks[Conduit.Rail]
        assertEquals(iron / 2, after[moved, Species.Iron], "iron at $moved")
        assertEquals(7L, after[moved, Species.Oxygen], "oxygen at $moved")
        assertTrue(s1.conduits.isGhost(Conduit.Rail, moved), "a half-built rail is still a ghost")
    }

    @Test
    fun `bodyBalance is preserved across remap`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 4, oldGrid.height + 3)
        val dx = 4
        val dy = 3

        val s1 = s0.remapped(newGrid, dx, dy)

        // No body conservation ledger (bodies spawn/despawn freely), bodies just transfer across.
        assertEquals(s0.bodies.size, s1.bodies.size, "body count must be preserved")
    }

    /** ⚠️ **PARKED** — see [EnergyLedgers], and the `airEnergyBalance` twin above for why. */
    @Ignore
    @Test
    fun `heatBalance is preserved across remap`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 3, oldGrid.height + 2)
        val dx = 3
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        EnergyLedgers.assertPreserved(s0, s1, "remap")
    }

    // ── Round trip ───────────────────────────────────────────────────────

    @Test
    fun `remap +4+3 then -4-3 is the identity`() {
        val s0 = populatedWorld(15, 10)
        val g0 = s0.grid
        val g1 = Grid(g0.width + 4, g0.height + 3)
        val g2 = Grid(g1.width - 4, g1.height - 3)

        val s1 = s0.remapped(g1, 4, 3)
        val s2 = s1.remapped(g2, -4, -3)

        assertEquals(s0.grid, s2.grid, "grid should be identical")
        assertEquals(
            s0.grid.tiles.map { s0.deck[it] },
            s2.grid.tiles.map { s2.deck[it] },
            "machines should be identical",
        )
        assertEquals(s0.conduits, s2.conduits, "conduits should be identical")
        assertEquals(s0.diverters.forkCursors, s2.diverters.forkCursors, "diverters should be identical")
        assertEquals(s0.air.copyMass().data.contentToString(), s2.air.copyMass().data.contentToString(), "air mass")
        assertEquals(s0.air.copyEnergy().data.contentToString(), s2.air.copyEnergy().data.contentToString(), "air energy")
        assertEquals(s0.pipeAir.copyMass().data.contentToString(), s2.pipeAir.copyMass().data.contentToString(), "pipeAir mass")
        assertEquals(s0.pipeAir.copyEnergy().data.contentToString(), s2.pipeAir.copyEnergy().data.contentToString(), "pipeAir energy")
        assertTrue(s0.momentum.copyX().contentEquals(s2.momentum.copyX()), "momentum X")
        assertTrue(s0.momentum.copyY().contentEquals(s2.momentum.copyY()), "momentum Y")
        assertTrue(s0.pipeMomentum.copyX().contentEquals(s2.pipeMomentum.copyX()), "pipeMomentum X")
        assertTrue(s0.pipeMomentum.copyY().contentEquals(s2.pipeMomentum.copyY()), "pipeMomentum Y")
        assertEquals(s0.bodies, s2.bodies, "bodies should be identical")
        assertEquals(s0.baselineAirMass, s2.baselineAirMass)
        assertEquals(s0.baselineAirEnergy, s2.baselineAirEnergy)
        assertEquals(s0.baselineEnergy, s2.baselineEnergy)

    }

    // ── Cells outside old grid ───────────────────────────────────────────

    @Test
    fun `new tiles are vacuum`() {
        val s0 = simpleWorld(10, 8)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 5, oldGrid.height + 4)
        val dx = 5
        val dy = 4

        val s1 = s0.remapped(newGrid, dx, dy)

        // Tiles in the new area that were not in the old grid should be zero
        for (x in 0 until 5) {
            for (y in 0 until 4) {
                val tile = newGrid.tile(x, y)
                assertEquals(0L, s1.air.massOf(tile, Species.Iron),
                    "new tile ($x,$y) should be vacuum")
                assertEquals(0L, s1.air.copyEnergy()[tile], "new tile ($x,$y) energy should be zero")
            }
        }
    }

    // ── Negative offset: shrink left and up ──────────────────────────────

    /**
     * ⚠️ **PARKED — do not delete.** Asserts the silent solid-dropping that P4's `require`
     * replaced. Revisit with the rigid-body rework. See `PLAN_dynamic_grid.md` §5.
     */
    @Ignore
    @Test
    fun `negative offset drops cells outside new grid`() {
        val s0 = populatedWorld(15, 10)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width - 3, oldGrid.height - 2)
        val dx = -3
        val dy = -2

        val s1 = s0.remapped(newGrid, dx, dy)

        // Tiles inside the new grid should still be correct
        for (x in 3 until oldGrid.width) {
            for (y in 2 until oldGrid.height) {
                val oldTile = oldGrid.tile(x, y)
                val nx = x + dx
                val ny = y + dy
                val newTile = newGrid.tile(nx, ny)
                assertEquals(s0.deck[oldTile], s1.deck[newTile],
                    "machine at ($x,$y) -> ($nx,$ny)")
            }
        }
    }

    @Test
    fun `motion is dropped rather than carried onto the new grid`() {
        // P1 left this as a known gap: the comment said motion was dropped, `copy()` carried it.
        // Harmless while nothing resizes mid-play, and P3's growth is exactly that — the renderer
        // would read an old-grid-sized array at new-grid tile indices, silently one row out.
        val s0 = populatedWorld(20, 14)
        val stale = Motion(
            ByteArray(s0.grid.size) { Motion.FROM_PORT.toByte() },
            LongArray(s0.grid.size) { 7L },
            mapOf(s0.grid.tile(5, 5) to 1),
            emptyList(),
        )
        val s1 = s0.copy(motion = stale).remapped(Grid(26, 20), 3, 3)

        assertEquals(Motion.NONE, s1.motion, "motion survived a resize")
        // The property the renderer actually depends on: nothing in the new grid claims to have
        // arrived from anywhere, at any index the new grid can produce.
        for (tile in s1.grid.tiles) {
            assertEquals(null, s1.motion.arrivedFrom(tile), "stale arrival at tile $tile")
            assertEquals(0L, s1.motion.previousMassAt(tile), "stale mass at tile $tile")
        }
    }
}
