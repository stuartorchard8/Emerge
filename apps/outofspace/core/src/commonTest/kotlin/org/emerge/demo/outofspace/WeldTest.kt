package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BodyKind
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DockingPort
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.Composite
import org.emerge.demo.outofspace.world.DockNode
import org.emerge.demo.outofspace.world.Docking
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Market
import org.emerge.demo.outofspace.world.MassDistribution
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Rotation
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Station
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.sim.core.PlayerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bolting the vessel to a station — `PLAN_economy.md` §7.
 *
 * ⛔ **The ledgers are the point.** A weld is an inelastic collision, so total linear momentum and
 * total angular momentum are both conserved; `momentumBalance` and `angularBalance` are the
 * instruments that say so, and they are the only ones that can tell a correct weld from one that
 * merely *looks* attached.
 */
class WeldTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(24, 16))

    @AfterTest
    fun tidy() { RockSpawner.enabled = true }

    // ── The composite arithmetic ─────────────────────────────────────────────

    @Test
    fun `two equal masses join at their midpoint`() {
        val a = MassDistribution(mass = 1_000L, comX = 0L, comY = 0L, gyrationSq = 0L)
        val b = MassDistribution(mass = 1_000L, comX = 4_000L, comY = 0L, gyrationSq = 0L)
        val joint = Composite.combined(a, b, 4_000L, 0L)
        assertEquals(2_000L, joint.about.mass)
        assertEquals(2_000L, joint.offsetX, "the joint centre is not halfway between them")
        // Two point masses two tiles either side of the centre: k² = d² = 2000 millitiles squared.
        assertEquals(2_000L * 2_000L, joint.about.gyrationSq, "the parallel-axis term is wrong")
    }

    @Test
    fun `a heavy member barely moves the joint centre`() {
        val light = MassDistribution(mass = 1L, comX = 0L, comY = 0L, gyrationSq = 0L)
        val heavy = MassDistribution(mass = 999L, comX = 1_000L, comY = 0L, gyrationSq = 0L)
        val joint = Composite.combined(light, heavy, 1_000L, 0L)
        assertEquals(999L, joint.offsetX, "the joint centre did not sit on the heavy member")
    }

    @Test
    fun `combining is symmetric in the members`() {
        val a = MassDistribution(mass = 3_000L, comX = 0L, comY = 0L, gyrationSq = 500L)
        val b = MassDistribution(mass = 7_000L, comX = 0L, comY = 2_000L, gyrationSq = 900L)
        val ab = Composite.combined(a, b, 0L, 2_000L)
        val ba = Composite.combined(b, a, 0L, -2_000L)
        assertEquals(ab.about.mass, ba.about.mass)
        assertEquals(ab.about.gyrationSq, ba.about.gyrationSq, "the pair's inertia depends on the order")
    }

    @Test
    fun `an empty member changes nothing`() {
        val a = MassDistribution(mass = 5_000L, comX = 100L, comY = 200L, gyrationSq = 700L)
        assertEquals(a, Composite.combined(a, MassDistribution.EMPTY, 9_999L, 9_999L).about)
    }

    // ── Docking a real ship ──────────────────────────────────────────────────

    /**
     * A small ship with a docking port on its right-hand face, and a station touching it.
     *
     * ⚠️ The ship is given a **drift and a spin**. A weld between two things that are both already
     * at rest conserves nothing interesting, and every one of the ledger assertions below would pass
     * against a weld that simply threw the momentum away.
     */
    private fun berthedWorld(): Pair<VesselState, DockingPort> {
        RockSpawner.enabled = false
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        fun hull(x: Int, y: Int) { if (deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in 2..14) { hull(x, 4); hull(x, 12) }
        for (y in 4..12) { hull(2, y) }
        val tile = grid.tile(16, 8)
        val port = DockingPort(tile, Direction.Right)
        deck += port

        var s = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(arrayOfNulls<Segment>(grid.size).toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
            vesselImpulseX = 900_000_000_000L,
            vesselImpulseY = -300_000_000_000L,
            angImpulse = 40_000_000_000_000L,
            // ⚠️ **Momentum handed to a fixture has to say where it came from.** `debugImpulse` is
            // the store that exists to name minted momentum — without it `momentumBalance` reads the
            // fixture's own drift as a leak for ever, and the instrument the weld is being measured
            // by would be broken before the first tick.
            debugImpulseX = 900_000_000_000L,
            debugImpulseY = -300_000_000_000L,
            // ⚠️ The angular ledger has **no** debug store — `angularBalance` is `angImpulse +
            // exhaust + bodies` and nothing else, on purpose. So a fixture that wants a spinning ship
            // has to give it a history instead of a source: this one took its spin off a body, which
            // is what `bodyAngImpulse` means and the only honest way to state it.
            bodyAngImpulse = -40_000_000_000_000L,
        )

        val node = DockNode(0, 5, Direction.Left)
        var station = RigidBody.stationShell(
            positionX = 0L, positionY = 0L,
            composition = Mixture.of(Species.Steel to Budget.KILOGRAM, energy = 0L),
            station = Station(Mixture.EMPTY, Market.of(Species.Iron to Budget.TONNE), id = 9, docks = listOf(node)),
        )
        val mouthX = Docking.berthWorldX(grid, port, s.pose)
        val mouthY = Docking.berthWorldY(grid, port, s.pose)
        station = station.copy(
            positionX = station.positionX + (mouthX - Docking.nodeWorldX(node, station.pose)),
            positionY = station.positionY + (mouthY - Docking.nodeWorldY(node, station.pose)),
            // The station is drifting too, so capture has two momenta to add rather than one.
            impulseX = -200_000_000_000L,
            impulseY = 50_000_000_000L,
            // Spinning too, so capture has two angular momenta to add and an orbital term besides.
            angImpulse = 8_000_000_000_000L,
        )
        s = s.copy(bodies = listOf(station))
        return s to port
    }

    private fun run(state: VesselState, ticks: Int, edit: Edit? = null): VesselState {
        var s = state
        val inputs = mapOf(PlayerId(0) to OutofspaceInput(listOfNotNull(edit)))
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    @Test
    fun `a ship lined up with a berth docks, and one that is not does not`() {
        val (world, port) = berthedWorld()
        assertNotNull(run(world, 1, Edit.Dock(port.center)).docked, "a lined-up ship would not dock")

        // Same ship, station shoved well out of range.
        val far = world.copy(
            bodies = world.bodies.map { it.copy(positionX = it.positionX + 60L * Flight.PER_TILE) },
        )
        assertEquals(null, run(far, 1, Edit.Dock(port.center)).docked, "docked across sixty tiles")
    }

    @Test
    fun `the station holds its place relative to the ship`() {
        val (world, port) = berthedWorld()
        val docked = run(world, 1, Edit.Dock(port.center))
        val link = assertNotNull(docked.docked)
        var s = docked
        repeat(200) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }

        val station = s.bodies.single { it.kind == BodyKind.STATION }
        // ⛔ The whole of "rigid": the station's pose in the ship's frame must be the pose the weld
        // froze at capture, two hundred ticks later, whatever the pair has been doing.
        // ⚠️ **Within a tolerance, and the tolerance is the pose primitives' own.** The station's
        // world pose is written out through `toWorld` and read back here through `toLocal`, and that
        // round trip is documented at 2.7e-6 of a tile — `PLAN_trig_free_rotation.md`. Measured here
        // at **five raw units**, which is 5e-9 of a tile and five hundred times inside it. It does
        // not accumulate: the station's pose is *derived* from the ship's every tick, never
        // integrated, so there is no running total for an error to collect in.
        val slack = 1_000L
        assertTrue(
            (link.stationLocalX - s.pose.toLocalX(station.positionX, station.positionY)) in -slack..slack,
            "the berth drifted in x",
        )
        assertTrue(
            (link.stationLocalY - s.pose.toLocalY(station.positionX, station.positionY)) in -slack..slack,
            "the berth drifted in y",
        )
        assertEquals(link.stationRelativeAng, station.ang.raw - s.pose.ang.raw, "the berth twisted")
    }

    @Test
    fun `the momentum ledgers stay closed across dock, drift and undock`() {
        // ⛔ **The test the weld exists to pass.** Capture is an inelastic collision, so nothing may
        // be minted: what the vessel takes off the station is booked through `bodyImpulse` and
        // `bodyAngImpulse`, the stores that exist to name exactly that exchange.
        val (world, port) = berthedWorld()
        assertEquals(0L, world.momentumBalanceX, "the fixture did not start balanced")

        var s = run(world, 1, Edit.Dock(port.center))
        assertNotNull(s.docked, "nothing docked, so nothing was proven")
        repeat(300) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            assertEquals(0L, s.momentumBalanceX, "momentum leaked in x at tick ${s.tick}")
            assertEquals(0L, s.momentumBalanceY, "momentum leaked in y at tick ${s.tick}")
            assertEquals(0L, s.angularBalance, "angular momentum leaked at tick ${s.tick}")
        }

        s = run(s, 1, Edit.Undock)
        assertEquals(null, s.docked, "undocking did nothing")
        repeat(100) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        assertEquals(0L, s.momentumBalanceX, "momentum leaked after release")
        assertEquals(0L, s.angularBalance, "angular momentum leaked after release")
    }

    @Test
    fun `a docked ship keeps its mass balance`() {
        val (world, port) = berthedWorld()
        var s = run(world, 1, Edit.Dock(port.center))
        repeat(200) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            assertEquals(0L, s.massBalance, "mass leaked while docked at tick ${s.tick}")
        }
    }

    // ── The interlock ────────────────────────────────────────────────────────

    @Test
    fun `engines are interlocked while berthed, and the switch releases them`() {
        val (world, port) = berthedWorld()
        val docked = run(world, 1, Edit.Dock(port.center))
        // Nothing may leave the nozzles while the interlock is on, whatever the pilot asks for.
        val held = run(docked, 40)
        assertEquals(docked.ventedMass, held.ventedMass, "an engine fired while berthed")

        val released = run(docked, 1, Edit.SetDockedThrust(true))
        assertTrue(released.dockedThrustAllowed, "the interlock could not be released")
    }

    // ── The save ─────────────────────────────────────────────────────────────

    @Test
    fun `a berth survives a round trip`() {
        val (world, port) = berthedWorld()
        val docked = run(world, 1, Edit.Dock(port.center))
        val link = assertNotNull(docked.docked)
        val back = assertNotNull(Save.read(Save.write(docked)).docked, "the ship reloaded flying free")
        assertEquals(link, back, "the berth came back different")
    }

    @Test
    fun `an undocked world writes no berth`() {
        val (world, _) = berthedWorld()
        assertTrue(Save.write(world).lineSequence().none { it.startsWith("dock ") })
    }
    // ── Where steps 2 and 3 meet ─────────────────────────────────────────────

    @Test
    fun `berthing puts the station's shelves on the other side of the mouth`() {
        val (world, port) = berthedWorld()
        assertEquals(null, world.dockedMarket, "the fixture already had a counterparty")
        val docked = run(world, 1, Edit.Dock(port.center))
        val market = assertNotNull(docked.dockedMarket, "berthing did not open a counterparty")
        assertEquals(Budget.TONNE, market.stockOf(Species.Iron), "the counterparty is not the station's")
    }

    @Test
    fun `letting go closes the counterparty`() {
        val (world, port) = berthedWorld()
        val docked = run(world, 1, Edit.Dock(port.center))
        assertNotNull(docked.dockedMarket)
        assertEquals(null, run(docked, 1, Edit.Undock).dockedMarket, "an undocked ship kept trading")
    }

    @Test
    fun `what the ship sells reaches the station's own shelves`() {
        // ⛔ The seam between the docking port and the station, end to end: a lump in the mouth, sold
        // to the berth the ship is bolted to, landing in that station's stock and paid for out of
        // that station's prices.
        val (world, port) = berthedWorld()
        val selling = world.copy(
            deck = world.deck.also {
                it[port.center] = port.copy(sell = listOf(SpeciesFilter(Species.Titanium, null)))
            },
        ).stocked(port.center, Mixture.of(Species.Titanium to 500L * Budget.KILOGRAM, energy = 0L).atAmbient())

        var s = run(selling, 1, Edit.Dock(port.center))
        repeat(5) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }

        assertTrue(s.credits > 0L, "the station paid nothing")
        assertTrue(s.exportedMass > 0L, "nothing left the vessel")
        val station = s.bodies.single { it.kind == BodyKind.STATION }
        assertTrue(
            assertNotNull(station.station).market.stockOf(Species.Titanium) > 0L,
            "the titanium was sold to nobody",
        )
    }

}
