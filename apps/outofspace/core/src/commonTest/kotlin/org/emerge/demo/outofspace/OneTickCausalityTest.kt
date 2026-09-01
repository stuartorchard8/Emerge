package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Nothing in a tick reads its own tick.** See `apps/outofspace/PLAN_one_tick_causality.md`.
 *
 * Every pass reasons from the world as it stood when the tick began and writes to work, so a thing
 * that happens this tick is not observable until the next one. The signal network was the first
 * place this held — a sensor firing drives its machine a tick later — and these are the same rule
 * asked of matter.
 *
 * ⚠️ **This is about what a pass may *see*, not about ordering.** Matter still moves exactly once
 * and is still conserved to the gram; a pass still runs before or after its neighbours for the
 * reasons it always did. What changed is that a tile cannot act on its own arrival.
 *
 * Two neighbours of this rule are pinned elsewhere and are not repeated here:
 *  - **Bridges** already held it, and `BridgeTest > a packet crosses a bridge one slot at a time`
 *    is the assertion — a lump reaching the exit slot rests there "for a whole step" before it is
 *    set down, because `depositFromBridge` drains before `advanceBridges` shuffles.
 *  - **Signals**, where the rule first appeared: `WiringTest` and `SignalWiringTest` pin that a
 *    sensor drives its machine a tick after it fires.
 */
class OneTickCausalityTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(20, 10))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap<PlayerId, OutofspaceInput>()) }
        return s
    }

    private val grid = Grid(20, 10)
    private val y = 4

    /** The tile a pass-through tank takes delivery at. */
    private val intoMiddle = grid.tile(7, y)

    /** The tank itself, and the first tile of the run leading away from it. */
    private val middle = grid.tile(8, y)
    private val outOfMiddle = grid.tile(9, y)

    /**
     * A tank on a through line: fed from the left, leading away to a second tank on the right.
     *
     * Both tanks take anything, so the only thing governing when material moves is the rule under
     * test. The far tank exists because nothing travels toward a place that cannot use it — without
     * a sink beyond it the middle tank would never emit at all and the test would pass for the
     * wrong reason.
     */
    private fun throughLine(): VesselState {
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        deck += fixtureStorage(middle, Direction.Right)          // in at (7, y), out at (9, y)
        deck += fixtureStorage(grid.tile(13, y), Direction.Right) // in at (12, y)
        joinRow(grid, rails, 5, 7, y)
        joinRow(grid, rails, 9, 12, y)
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )
    }

    private fun packet() = Mixture.of(Species.Iron to Capacity.PACKET_MASS, energy = 0).atAmbient()

    /**
     * The case that named the rule: a warehouse used to put a packet straight back on the track,
     * having held it for no time at all.
     *
     * It happened inside one block. `advanceRails` delivers into the tank's store, and `pushOut`
     * drains the tank's output port a few lines further down the same function — so the arriving
     * packet was still moving when the tile it had arrived at offered it onward.
     */
    @Test
    fun `a tank does not pass on a packet it took delivery of this step`() {
        val start = throughLine().riding(intoMiddle, packet())

        // One step: the packet leaves the track and is taken into the tank.
        val delivered = run(start, RAIL_PERIOD)
        assertNull(delivered.onRail(intoMiddle), "the packet should have left the track")
        assertEquals(
            Capacity.PACKET_MASS,
            delivered.inStore(middle, BufferRole.Inside)?.total,
            "and be in the tank",
        )
        assertNull(delivered.onRail(outOfMiddle), "and NOT already be back on the track beyond it")

        // The step after: now it may go.
        val onward = run(delivered, RAIL_PERIOD)
        assertEquals(
            Capacity.PACKET_MASS,
            onward.onRail(outOfMiddle)?.total,
            "a step later the tank lets it go",
        )
    }

    /**
     * ⚠️ **A step of latency, not a halved rate.** The line is pipelined: a tank holding a queue
     * hands one packet on every step exactly as it did before, because what it may let go of is what
     * it was *already* holding rather than what it holds now.
     *
     * Worth pinning separately, because the obvious wrong implementation — a tile that simply sits
     * out a step after any delivery — passes the test above and fails this one.
     */
    @Test
    fun `a tank with a queue still hands one on every step`() {
        val stocked = throughLine()
            .stocked(middle, Mixture.of(Species.Iron to 4 * Capacity.PACKET_MASS, energy = 0).atAmbient())

        var s = run(stocked, RAIL_PERIOD)
        assertEquals(Capacity.PACKET_MASS, s.onRail(outOfMiddle)?.total, "first step, first packet")

        // Three more steps, and it is still letting one go each time rather than every other time.
        var seen = 1
        repeat(3) {
            s = run(s, RAIL_PERIOD)
            if (s.onRail(outOfMiddle) != null) seen++
        }
        assertEquals(4, seen, "a full tank should feed the run every step, not every other one")
    }

    /**
     * The other half of the same handoff, and it was **already true**.
     *
     * `pushOut` runs after `advanceRails` rather than inside it, so a packet a machine sets down has
     * missed this step's walk and waits for the next — the track cannot carry away what it was
     * handed a moment ago any more than the tank could pass on what it was just given. Pinned rather
     * than assumed: it holds today by the order of two calls, which is the kind of thing that is one
     * refactor away from silently ceasing to be true.
     */
    @Test
    fun `track does not carry away a packet a machine set down this step`() {
        val stocked = throughLine()
            .stocked(middle, Mixture.of(Species.Iron to 4 * Capacity.PACKET_MASS, energy = 0).atAmbient())

        val s = run(stocked, RAIL_PERIOD)

        assertEquals(
            Capacity.PACKET_MASS,
            s.onRail(outOfMiddle)?.total,
            "the packet should be on the first tile beyond the tank",
        )
        assertNull(s.onRail(grid.tile(10, y)), "and no further along than that")
    }

    /**
     * A packet crosses one tile per step and no more, however the walk happens to be ordered.
     *
     * `advanceSegments` keeps this with an `arrived` flag rather than by trusting `FlowGraph.order`,
     * because a run with an output port partway along it moves by both rules at once. Four steps,
     * four tiles.
     */
    @Test
    fun `a packet crosses one tile per step and no more`() {
        var s = throughLine().riding(grid.tile(9, y), packet())
        for (step in 1..3) {
            s = run(s, RAIL_PERIOD)
            assertEquals(
                Capacity.PACKET_MASS,
                s.onRail(grid.tile(9 + step, y))?.total,
                "after $step step(s) it should be exactly $step tiles along",
            )
        }
    }

    /**
     * The four-term mass balance — see `reference_oos_mass_ledger`.
     *
     * ⚠️ Stated as **drift** rather than as an absolute. `stocked` and `riding` put matter on a layer
     * without booking it as cargo, which is the right thing for a fixture that wants a line already
     * running and the wrong thing to measure an absolute balance against. What conservation means
     * here is that the books do not move, whatever they read to begin with.
     */
    private fun balance(s: VesselState): Long =
        (s.extractedMass + s.baselineCargoMass) - (s.inTransitMass + s.ventedMass + s.builtMass)

    /**
     * The rule must not cost the game a gram, which is the thing that would have killed it.
     *
     * A pass that decides from the old world and acts on the new one is exactly how mass gets
     * invented: two draws on one store each see it full, and between them take more than was there.
     * Asserted over a line that is moving, delivering and backing up all at once.
     */
    @Test
    fun `matter is conserved across a line running under the rule`() {
        val start = throughLine()
            .riding(intoMiddle, packet())
            .stocked(middle, Mixture.of(Species.Iron to 2 * Capacity.PACKET_MASS, energy = 0).atAmbient())
        val aboard = start.inTransitMass
        val opening = balance(start)

        val s = run(start, 20 * RAIL_PERIOD)

        assertEquals(opening, balance(s), "the books moved under one-tick causality")
        assertEquals(aboard, s.inTransitMass, "and the same matter is aboard, neither lost nor invented")
        assertTrue(s.stockpile.totalMass > 0L, "with the material still in the tanks")
    }
}
