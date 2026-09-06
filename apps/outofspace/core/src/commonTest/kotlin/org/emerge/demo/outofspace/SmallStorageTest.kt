package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.PortKind
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.footprint
import org.emerge.demo.outofspace.world.fullness
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.materialBefore
import org.emerge.demo.outofspace.world.portsOf
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two small stores: a **silo** (1×3, five tonnes) and a **buffer** (1×2, two tonnes).
 *
 * ⛔ **They are [Storage], not new machines.** The whole design claim of this increment is that a
 * size is a [DeckMachineKind] and nothing else — the lock, the pooled store, the two doors and the
 * claim on the vessel's inventory are the warehouse's, unchanged, because they are the same class.
 * So most of what these things do is already covered by the suite that covers a warehouse, and what
 * is left is exactly what this file asserts: **the geometry, and the number**.
 *
 * The geometry is where it could go wrong silently. Both small sizes give up the property every
 * other store had — a silo is not square, so its footprint moves when it turns; a buffer's anchor is
 * not its middle, so `±reach` names one tile for both of its doors and the arithmetic that serves
 * every other machine quietly answers a plausible wrong thing. See `FootprintShape`.
 */
class SmallStorageTest {

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val cfg = OutofspaceConfig(initialGrid = state.grid)
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private val sizes = listOf(
        DeckMachineKind.Warehouse to Storage.WAREHOUSE_CAP,
        DeckMachineKind.Silo to Storage.SILO_CAP,
        DeckMachineKind.Buffer to Storage.BUFFER_CAP,
    )

    // ── Geometry ──────────────────────────────────────────────────────────────

    /**
     * Asserted for all four facings rather than one, for the reason `FootprintTest` states about a
     * thruster: a single facing passes against arithmetic with the sign of an offset backwards.
     */
    @Test
    fun `a silo is a line of three tiles along its facing`() {
        val grid = Grid(12, 12)
        val at = grid.tile(5, 5)
        for (facing in Direction.ALL) {
            assertEquals(
                setOf(
                    grid.tile(5 - facing.dx, 5 - facing.dy),
                    at,
                    grid.tile(5 + facing.dx, 5 + facing.dy),
                ),
                DeckMachineKind.Silo.footprint(at, grid, facing)!!.toSet(),
                "facing $facing, a silo stood on the wrong three tiles",
            )
        }
    }

    @Test
    fun `a buffer is its mouth and the tile in front of it`() {
        val grid = Grid(12, 12)
        val at = grid.tile(5, 5)
        for (facing in Direction.ALL) {
            assertEquals(
                setOf(at, grid.tile(5 + facing.dx, 5 + facing.dy)),
                DeckMachineKind.Buffer.footprint(at, grid, facing)!!.toSet(),
                "facing $facing, a buffer stood on the wrong pair of tiles",
            )
            // The anchor is an *end*, so a buffer on the rim fits pointing inboard and not outboard.
            assertNull(DeckMachineKind.Buffer.footprint(grid.tile(11, 5), grid, Direction.Right))
            assertNotNull(DeckMachineKind.Buffer.footprint(grid.tile(11, 5), grid, Direction.Left))
        }
    }

    @Test
    fun `a silo takes material in at one end and gives it out at the other`() {
        val grid = Grid(12, 12)
        val at = grid.tile(5, 5)
        val ports = portsOf(grid, fixtureStorage(at, Direction.Right, kind = DeckMachineKind.Silo), at)

        assertEquals(grid.tile(4, 5), ports.single { it.kind == PortKind.Input }.tile, "in at the back")
        assertEquals(grid.tile(6, 5), ports.single { it.kind == PortKind.Output }.tile, "out at the front")
        assertEquals(
            at,
            bufferTile(grid, fixtureStorage(at, Direction.Right, kind = DeckMachineKind.Silo), at, BufferRole.Inside),
            "and the store is the tile between them",
        )
    }

    /**
     * ⛔ **The case the shape was chosen for.** A buffer is one tile wide, so its `reach` is zero and
     * the `±r` every other store's doors come from puts both of them on the anchor — two rail ports
     * on one tile, which [org.emerge.demo.outofspace.world.Port] forbids, and a second tile that is
     * nothing but casing. Its doors are stated instead: in at the tail, out at the nose.
     */
    @Test
    fun `a buffer's doors are its two tiles, in at the one its store sits on`() {
        val grid = Grid(12, 12)
        val at = grid.tile(5, 5)
        val buffer = fixtureStorage(at, Direction.Right, kind = DeckMachineKind.Buffer)
        val ports = portsOf(grid, buffer, at)

        val input = ports.single { it.kind == PortKind.Input }
        val output = ports.single { it.kind == PortKind.Output }
        assertEquals(at, input.tile, "the anchor is the mouth")
        assertEquals(grid.tile(6, 5), output.tile, "and the nose is where it leaves")
        assertEquals(
            input.tile,
            bufferTile(grid, buffer, at, BufferRole.Inside),
            "the store sits on the input tile — at two tiles long there is no middle to put it in",
        )
    }

    @Test
    fun `a buffer's doors turn with it`() {
        val grid = Grid(12, 12)
        val at = grid.tile(5, 5)
        for (facing in Direction.ALL) {
            val ports = portsOf(grid, fixtureStorage(at, facing, kind = DeckMachineKind.Buffer), at)
            assertEquals(at, ports.single { it.kind == PortKind.Input }.tile, "facing $facing")
            assertEquals(
                grid.tile(5 + facing.dx, 5 + facing.dy),
                ports.single { it.kind == PortKind.Output }.tile,
                "facing $facing, the nose was not where the machine points",
            )
        }
    }

    /** Two ports may share a tile only if their conduits differ, and every store's are both rail. */
    @Test
    fun `no store has two doors on one tile, at any size or facing`() {
        val grid = Grid(12, 12)
        val at = grid.tile(5, 5)
        for ((kind, _) in sizes) for (facing in Direction.ALL) {
            val ports = portsOf(grid, fixtureStorage(at, facing, kind = kind), at)
            assertEquals(2, ports.size, "$kind facing $facing")
            assertEquals(2, ports.map { it.tile }.toSet().size, "$kind facing $facing put both doors on one tile")
            // And both of them stand on the machine, not beside it.
            val footprint = kind.footprint(at, grid, facing)!!.toSet()
            assertTrue(ports.all { it.tile in footprint }, "$kind facing $facing has a door off its own footprint")
        }
    }

    // ── The number ────────────────────────────────────────────────────────────

    @Test
    fun `the three sizes hold twenty, five and two tonnes`() {
        for ((kind, cap) in sizes) {
            assertEquals(
                cap,
                fixtureStorage(Grid(4, 4).tile(1, 1), Direction.Right, kind = kind).capacity,
                "$kind reported the wrong tank",
            )
        }
        // What those three numbers are *in grams* is `BudgetParityTest`'s to say — that is where
        // the unit lives, and stating it twice is how the two come to disagree. What is this file's
        // is that a store answers with its own one, which is what the loop above checks.
        assertTrue(
            Storage.WAREHOUSE_CAP > Storage.SILO_CAP && Storage.SILO_CAP > Storage.BUFFER_CAP,
            "the sizes are meant to be in that order",
        )
    }

    /**
     * A source tank pouring down a belt into a store of [kind], run until the line backs up.
     *
     * Through the reducer rather than against `acceptInto` directly, because the claim being tested
     * is that a *delivery* stops at the right number — and the door a packet is refused at is the
     * one thing a constant read in the wrong place would get wrong without anything else noticing.
     */
    private fun filled(kind: DeckMachineKind): VesselState {
        val grid = Grid(20, 12)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        val source = grid.tile(3, 6)
        deck += fixtureStorage(source, Direction.Right)              // 3×3: output at (4, 6)
        // The store under test, anchored so its input door lands at (11, 6) whatever its size.
        val at = grid.tile(11 + inputSetback(kind), 6)
        deck += fixtureStorage(at, Direction.Right, kind = kind)
        joinRow(grid, rails, 4, 11, 6)
        val world = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(source, Mixture.of(Species.Iron to Storage.WAREHOUSE_CAP, energy = 0).atAmbient())
        // Long enough for a tank's worth of packets to cross, plus half as many again: a belt hands
        // over a hundred kilograms about every ten ticks, so five tonnes is fifty crossings. Sized
        // to the tank under test rather than fixed, because a buffer needs a fifth of a silo's run
        // and paying a silo's ticks for it is how a suite gets slow.
        val crossings = Storage.capacityOf(kind) / Capacity.PACKET_MASS
        return run(world, (crossings * 15 + 200).toInt())
    }

    @Test
    fun `a small store stops taking deliveries at its own tank, not at a warehouse's`() {
        for (kind in listOf(DeckMachineKind.Silo, DeckMachineKind.Buffer)) {
            val s = filled(kind)
            val at = s.grid.tile(11 + inputSetback(kind), 6)
            val store = bufferTile(s.grid, s.deck[at]!!, at, BufferRole.Inside)!!
            val held = s.buffers.massAt(store)
            val cap = Storage.capacityOf(kind)
            assertTrue(held <= cap, "$kind took $held into a $cap tank")
            // And it really did fill: a store that stopped at one packet would pass the line above.
            assertTrue(
                held > cap - Capacity.PACKET_MASS,
                "$kind stalled at $held, short of its $cap tank",
            )
        }
    }

    /**
     * ⛔ The reading a shared constant gets wrong quietly. A full silo measured against a warehouse's
     * tank reads a quarter full, so a sensor watching a line back up never fires and the player's
     * wiring simply does nothing.
     */
    @Test
    fun `a small store reads full to a sensor when it is full of its own capacity`() {
        val grid = Grid(12, 12)
        val at = grid.tile(6, 6)
        for ((kind, cap) in sizes) {
            val deck = DeckArray(grid)
            deck += fixtureStorage(at, Direction.Right, kind = kind)
            val world = VesselState(
                grid, deck,
                buffers = BufferLayer.forDeck(grid, deck),
                rail = RailLayer.empty(grid.size),
            ).stocked(at, Mixture.of(Species.Iron to cap, energy = 0).atAmbient())
            assertEquals(
                SignalField.FULL,
                fullness(world.deck[at], at, grid, world.buffers),
                "a full $kind did not read full",
            )
        }
    }

    // ── It is a storage in every other way ────────────────────────────────────

    @Test
    fun `a buffer's contents are part of the vessel's inventory`() {
        val grid = Grid(12, 12)
        val at = grid.tile(6, 6)
        val deck = DeckArray(grid)
        deck += fixtureStorage(at, Direction.Right, kind = DeckMachineKind.Buffer)
        val world = VesselState(
            grid, deck,
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(at, Mixture.of(Species.Iron to Storage.BUFFER_CAP, energy = 0).atAmbient())

        assertEquals(
            Storage.BUFFER_CAP,
            world.stockpile.held.total,
            "the smallest store is still somewhere you can build out of",
        )
    }

    // ── Saves ─────────────────────────────────────────────────────────────────

    @Test
    fun `every size survives a save and a load`() {
        val grid = Grid(24, 12)
        val deck = DeckArray(grid)
        val places = sizes.mapIndexed { i, (kind, _) -> kind to grid.tile(4 + i * 6, 6) }
        for ((kind, at) in places) deck += fixtureStorage(at, Direction.Down, kind = kind)
        val world = VesselState(grid, deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))

        val reloaded = Save.read(Save.write(world))
        for ((kind, at) in places) {
            val back = reloaded.deck[at] as? Storage
            assertNotNull(back, "$kind did not come back")
            assertEquals(kind, back.kind, "$kind came back as ${back.kind}")
            assertEquals(Direction.Down, back.facing, "$kind lost its facing")
            assertEquals(Storage.capacityOf(kind), back.capacity, "$kind came back the wrong size")
        }
        assertEquals(Save.write(world), Save.write(reloaded), "the reload differs")
    }

    /**
     * ⛔ The 3×3 was written to disk as `Storage` for the whole of the game's life before it had
     * company. Every existing save says so, and a file that cannot be loaded is the whole cost of a
     * rename — see `Save.canonicalKindName`.
     */
    @Test
    fun `a file that says Storage loads a warehouse`() {
        val grid = Grid(12, 12)
        val at = grid.tile(6, 6)
        val deck = DeckArray(grid)
        deck += fixtureStorage(at, Direction.Right)
        val world = VesselState(grid, deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))

        val written = Save.write(world)
        assertTrue("Warehouse" in written, "a warehouse is written under its own name today")
        val old = written.replace("Warehouse", "Storage")

        val back = Save.read(old).deck[at] as? Storage
        assertNotNull(back, "the old spelling did not load")
        assertEquals(DeckMachineKind.Warehouse, back.kind)
        assertEquals(Storage.WAREHOUSE_CAP, back.capacity)
    }
}

/**
 * How far a store's **input door** sits behind its anchor, so a fixture can lay a run of track to a
 * fixed tile and anchor whichever size it is testing so as to meet it.
 *
 * Test-local on purpose: the game has no such notion — a door is `portsOf`'s answer, and nothing in
 * the game reconstructs one from a number. This exists so that one fixture can serve three
 * geometries, and it is checked against `portsOf` by
 * `a buffer's doors are its two tiles, in at the one its store sits on` and its silo twin above.
 */
private fun inputSetback(kind: DeckMachineKind): Int = if (kind == DeckMachineKind.Buffer) 0 else 1
