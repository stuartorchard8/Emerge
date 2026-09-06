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
import org.emerge.demo.outofspace.world.PowerFlow
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Electrolyzer
import org.emerge.demo.outofspace.world.machine.SolarPanel
import org.emerge.demo.outofspace.world.machine.Storage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A cell runs on what the panels give it.**
 *
 * Increment 2 of `PLAN_power_network.md`, and the first time in this game a machine can be short of
 * something that is not matter.
 *
 * ⭐ **Voltage gates, current sets the rate.** Below water's 1230 mV a cell does nothing at all;
 * above it, a cell runs at whatever the bus can feed. That distinction is the whole design: a vessel
 * with too few panels has a *slow* plant rather than a dead one, so the shortage is something to
 * plan around rather than a cliff to fall off.
 */
class PoweredCellTest {

    private val grid = Grid(20, 10)
    private val plantAt = grid.tile(8, 4)
    private val hydrogenTank = grid.tile(16, 4)
    private val oxygenTank = grid.tile(8, 8)

    /** An electrolyzer wired to [panels] panels, its feed stocked with water. */
    private fun plant(panels: Int): VesselState {
        val deck = DeckArray(grid)
        deck += Electrolyzer(plantAt, Direction.Right)
        // Spaced two apart along the trunk, so each panel's neighbours are cable rather than
        // another panel — a machine blocks passage and would take a face of sky off its neighbour.
        // ⚠️ **Two apart and one row up, which is a fixture detail with a reason.** Spaced so each
        // panel's neighbours are cable rather than another panel — a machine blocks passage and
        // would take a face of sky off its neighbour — and *close* to the cell because the
        // relaxation is diffusive: a run settles in `L² × SETTLING_TICKS`, so a fifteen-tile trunk
        // takes some eighteen hundred ticks to come up and a test over one would be measuring the
        // fixture's length rather than the machine.
        // ⚠️ Row 1, clear of the electrolyzer's own 3×3 footprint at y 3..5.
        repeat(panels) { deck += SolarPanel(grid.tile(5 + it * 2, 1)) }

        // ⛔ **Somewhere for the gas to go, and without it this fixture measures the wrong thing.**
        // A cell stalls when either output hopper reaches `BUFFER_CAP`, so a plant with no belts
        // saturates in a couple of hundred ticks and every bank of panels then looks identical —
        // which is exactly how the first version of this test reported five panels as no better
        // than two.
        deck += fixtureStorage(hydrogenTank, Direction.Right)
        deck += fixtureStorage(oxygenTank, Direction.Down)

        val power = arrayOfNulls<Segment>(grid.size)
        fun lay(t: TileIndex) { power[t.index] = power[t.index] ?: Segment(Conduit.Power, material = Species.Copper) }
        fun join(a: TileIndex, dir: Direction) {
            val b = grid.neighbour(a, dir)
            lay(a); lay(b)
            power[a.index] = power[a.index]!!.joinedTo(dir)
            power[b.index] = power[b.index]!!.joinedTo(dir.opposite)
        }
        for (x in 4 until 14) join(grid.tile(x, 1), Direction.Right)
        for (y in 1 until 4) join(grid.tile(8, y), Direction.Down)

        val rails = arrayOfNulls<Segment>(grid.size)
        // ⚠️ **Starting ON the buffer tile, not beside it.** A machine's store sits on the port it
        // serves, so a run that begins one tile out never touches the mouth — which is how the first
        // version of this fixture let the oxygen hopper fill to `BUFFER_CAP` and stall the plant,
        // making every bank of panels look identical.
        joinRow(grid, rails, 9, 15, 4)    // hydrogen, out of the machine's right face
        joinCol(grid, rails, 8, 5, 7)     // oxygen, down out of its bottom face

        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()).with(Conduit.Power, power.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        // ⚠️ **Deep enough that the feed is never what runs out.** At the designed rate — one panel
        // to about a tenth of `MASS_PER_TICK` — two panels drink forty packets inside the warm-up,
        // and a test whose plants all stop for want of water reports every bank of panels as
        // identical. This is four thousand.
        ).stocked(plantAt, Mixture.of(Species.Water to 4_000L * Capacity.PACKET_MASS, energy = 0L).atAmbient())
    }

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        val cfg = OutofspaceConfig(initialGrid = state.grid)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * **Every gram of hydrogen anywhere aboard** — which is what the plant has actually made.
     *
     * ⚠️ **Not the machine's own hopper**, which was the first version of this and measured the
     * wrong thing: a hopper sits pinned at `BUFFER_CAP` while product streams past it onto the belt,
     * so every bank of panels read as identical once the plant had been running a while. Production
     * is what left, plus what is still waiting.
     */
    private fun made(s: VesselState): Long {
        var sum = 0L
        for (tile in grid.tiles) {
            sum += s.buffers.resourceAt(tile)?.get(Species.Hydrogen) ?: 0L
            sum += s.rail.stuff[tile, Species.Hydrogen]
        }
        return sum
    }

    // ── The threshold ────────────────────────────────────────────────────────

    /**
     * ⭐ **No panels, no hydrogen.** The bus sits at nothing, nothing clears 1230 mV, and the cell
     * is inert with a full tank of water in front of it.
     */
    @Test
    fun `a wired cell with no panels never runs`() {
        val after = run(plant(panels = 0), 600)
        assertEquals(0L, made(after), "a cell with no supply split water anyway")
        assertEquals(0L, after.charge.total, "charge appeared with no panel to make it")
    }

    /** And with panels it does run, which is the other half of the same claim. */
    @Test
    fun `a wired cell with panels splits water`() {
        val after = run(plant(panels = 4), 600)
        assertTrue(after.charge.total > 0L, "the panels made no charge")
        assertTrue(made(after) > 0L, "a powered cell made no hydrogen")
    }

    /**
     * ⭐ **The bus has to clear water's own potential**, and that number is not written anywhere —
     * it is `E°(anode) − E°(cathode)` off the half-reaction table.
     */
    @Test
    fun `the bus must clear the potential water splits at`() {
        val lit = run(plant(panels = 4), 600)
        val volts = PowerFlow.millivoltsAt(lit.charge, plantAt)
        assertTrue(volts >= 1230, "a running cell's bus was at $volts mV, below water's 1230")
    }

    // ── Current sets the rate ────────────────────────────────────────────────

    /**
     * ⭐ **More panels, more hydrogen** — the cell is current-limited, not on/off.
     *
     * This is what makes a shortage plannable: the player sees a slow plant and adds panels, rather
     * than seeing a dead one and guessing.
     */
    @Test
    fun `a bigger bank runs the plant faster`() {
        val small = made(run(plant(panels = 2), 800))
        val large = made(run(plant(panels = 5), 800))
        assertTrue(small > 0L, "the small bank made nothing at all")
        assertTrue(large > small, "five panels made $large against two panels' $small")
    }

    // ── ⛔ The chatter tripwire — decision 4, and NOT hysteresis ──────────────

    /**
     * ⛔ **The chatter tripwire — decision 4, and deliberately not hysteresis.**
     *
     * A threshold load on a bus it pulls down can limit-cycle: run, sag below the knee, go dark,
     * recover, run. The state that makes it pathological is the sag, so that is what is measured —
     * **once a well-supplied bus has settled it must never fall under water's 1230 mV.**
     *
     * ⚠️ Measured on the bus rather than on the machine's output on purpose. Production is paced by
     * `RAIL_PERIOD` and by `BUFFER_CAP`, so counting ticks a cell produced on measures the belts as
     * much as the supply — which is how an earlier version of this test reported three panels and
     * twelve as identical at eight ticks in sixty, that being 60/8 and nothing to do with power.
     *
     * ⚠️ **This test already earned its keep.** At first the cell was allowed to spend the whole
     * charge on its tile, drained itself under 1230 mV, went dark and recharged — a textbook limit
     * cycle, with power to spare. The fix was neither hysteresis nor a dial: a cell can only spend
     * its **overvoltage**, because current falls to zero as the bus approaches the knee. Decision 4
     * held that the answer to chatter should wait until something was measured chattering. Something
     * was, and the answer was that the load was simply wrong. See `OutofspaceSim.split`.
     */
    @Test
    fun `a settled bus never sags under the potential its cell needs`() {
        var s = run(plant(panels = 12), 600)
        val settled = PowerFlow.millivoltsAt(s.charge, plantAt)
        assertTrue(settled >= 1230, "the bus never came up: $settled mV")

        var lowest = settled
        repeat(120) {
            s = run(s, 1)
            lowest = minOf(lowest, PowerFlow.millivoltsAt(s.charge, plantAt))
        }
        assertTrue(lowest >= 1230, "a settled bus sagged to $lowest mV — the cell is pulling itself dark")
    }

    /** A cell with no cable under it still runs, on the stated unwired fallback. */
    @Test
    fun `a cell nobody wired still works`() {
        val deck = DeckArray(grid)
        deck += Electrolyzer(plantAt, Direction.Right)
        val bare = VesselState(
            grid, deck,
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        // ⚠️ **Deep enough that the feed is never what runs out.** At the designed rate — one panel
        // to about a tenth of `MASS_PER_TICK` — two panels drink forty packets inside the warm-up,
        // and a test whose plants all stop for want of water reports every bank of panels as
        // identical. This is four thousand.
        ).stocked(plantAt, Mixture.of(Species.Water to 4_000L * Capacity.PACKET_MASS, energy = 0L).atAmbient())
        assertTrue(made(run(bare, 40)) > 0L, "an unwired cell should still run — see UNWIRED_MILLIVOLTS")
    }
}
