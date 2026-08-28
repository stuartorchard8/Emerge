package org.emerge.demo.outofspace


import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tick in which the world is edited and no time passes — see `OutofspaceReducer.freeze`.
 *
 * The headline assertion is blunt on purpose: **the whole save text, identical but for the clock.**
 * A frozen tick is defined by everything it does *not* do, and a list of things not to do is exactly
 * the shape of claim that rots — the next subsystem to be added is not going to remember to ask
 * whether it should run while the game is stopped. A digest over the entire world does remember.
 * Anything that moves — a gram, a joule, a carry, a diverter cursor, a rock — fails this.
 *
 * The save does not carry presentation, so the things it cannot see are asserted by name below it.
 */
class FrozenTickTest {

    // The size the rest of the suite builds a working vessel at: the starter layout's ore
    // plates are placed against it, and a smaller grid leaves the extractors with nothing to bite.
    private val cfg = OutofspaceConfig(initialGrid = Grid(40, 28))

    private fun live(state: VesselState, ticks: Int = 1): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    private fun frozen(state: VesselState, ticks: Int = 1, input: OutofspaceInput? = null): VesselState {
        var s = state
        val inputs = if (input == null) emptyMap() else mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.freeze(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    /**
     * The world as the save records it, minus the three things a frozen tick is *meant* to change.
     *
     * The clock, and the two lines carrying "what happened during the tick that produced this
     * state" — `thrust` is `netImpulse`, and `rotation`'s last field is `netTorque`. A tick in which
     * nothing pushed correctly reports no push, so those go to zero rather than carrying the last
     * live tick's forces forward and telling the HUD an engine is still firing. ⚠️ Everything else
     * those two lines carry — the attitude, the running momentum totals, which are the ledgers — is
     * asserted by name in [a frozen tick changes nothing but the clock], so dropping the lines here
     * costs no coverage.
     */
    private fun worldBut(state: VesselState): String =
        Save.write(state).lines()
            .filterNot { it.startsWith("tick ") || it.startsWith("thrust ") || it.startsWith("rotation ") }
            .joinToString("\n")

    /**
     * An empty tile with track already beside it.
     *
     * ⚠️ **Not just any empty tile.** Building anywhere that extends the vessel's envelope grows the
     * grid to keep its pad, and a grow shifts the origin and renumbers every tile — so the edit lands
     * perfectly well and the test then looks for it at an index that now means somewhere else. A tile
     * the track already reaches is inside the envelope, so nothing moves. A live tick does exactly the
     * same thing, which is how this was caught being a bad test rather than a bad frozen tick.
     */
    private fun beside(s: VesselState): TileIndex = s.grid.tiles.first { tile ->
        s.railAt(tile) == null && s.deck[tile] == null && Direction.ALL.any { d ->
            val n = s.grid.neighbour(tile, d)
            n != TileIndex.NONE && s.railAt(n) != null
        }
    }

    // ── Nothing happens ───────────────────────────────────────────────────────

    @Test
    fun `a frozen tick changes nothing but the clock`() {
        // A world in mid-flow, so there is plenty in flight that *could* move: packets on the track,
        // gas spreading, heat conducting, rocks being chewed.
        val busy = live(workingVessel(cfg.initialGrid), 300)
        val still = frozen(busy)

        assertEquals(busy.tick + 1, still.tick, "the clock is the one thing that does move")
        assertEquals(worldBut(busy), worldBut(still), "something in the world moved on a frozen tick")

        // The two force readouts [worldBut] drops, stated rather than skipped: a tick in which
        // nothing pushed reports no push...
        assertEquals(0L, still.netImpulseX, "a frozen tick delivered an impulse")
        assertEquals(0L, still.netImpulseY)
        assertEquals(0L, still.netTorque, "a frozen tick delivered a torque")
        // ...and the running totals those feed — the momentum ledgers — are untouched by it, which
        // is what makes a pause free rather than a slow leak of momentum.
        assertEquals(busy.vesselImpulseX, still.vesselImpulseX, "a pause moved the momentum ledger")
        assertEquals(busy.vesselImpulseY, still.vesselImpulseY)
        assertEquals(busy.angImpulse, still.angImpulse, "a pause moved the angular ledger")
        assertEquals(busy.ang, still.ang, "a pause turned the ship")
    }

    @Test
    fun `a hundred frozen ticks are as still as one`() {
        val busy = live(workingVessel(cfg.initialGrid), 300)
        val still = frozen(busy, 100)

        assertEquals(busy.tick + 100, still.tick)
        assertEquals(worldBut(busy), worldBut(still), "the world drifted over a long pause")
    }

    /**
     * The save carries no presentation, so these are the fields it cannot speak for.
     *
     * [VesselState.motion] must be **carried**, not cleared: it is what a packet's slide is drawn
     * from, and a frozen tick that dropped it would strand every lump mid-tile — the opposite of
     * what stopping the game is supposed to look like. [VesselState.impacts] must be **cleared**:
     * a frozen tick had no collisions, and carrying last tick's would replay the same clang on every
     * frame the game was paused. The [VesselState.cadences] must be untouched, because that is what
     * lets the interpolations run on to rest against a clock that is still moving.
     */
    @Test
    fun `a frozen tick carries the motion, drops the impacts and leaves the stamps alone`() {
        val busy = live(workingVessel(cfg.initialGrid), 300)
        val still = frozen(busy)

        assertEquals(busy.motion, still.motion, "a paused packet has nowhere to slide from")
        assertTrue(still.impacts.isEmpty(), "a frozen tick reported a collision")
        assertEquals(busy.cadences, still.cadences, "a frozen tick restamped a pass that did not run")
        assertEquals(busy.motion.cadence, still.motion.cadence, "the rail's stamp moved")
    }

    /**
     * The stamps standing still while the clock moves is the whole mechanism: it is what makes a
     * half-finished slide finish rather than freeze. Stated here as the arithmetic rather than as a
     * screenshot, since the renderer is the only thing that can see the other version.
     */
    @Test
    fun `an interpolation runs on to rest across frozen ticks`() {
        // Start of a rail span: the pass has just run, so a packet is at the tile it left.
        var s = live(workingVessel(cfg.initialGrid), 300)
        while ((s.tick - 1) % OutofspaceReducer.RAIL_PERIOD != OutofspaceReducer.RAIL_OFFSET.toLong()) s = live(s)
        val cadence = s.motion.cadence
        assertEquals(0f, cadence.progress((s.tick - 1).toDouble()), "not at the start of a span")

        // Frozen ticks advance the clock and nothing else, so the slide finishes and stops there.
        val half = frozen(s, OutofspaceReducer.RAIL_PERIOD / 2)
        assertEquals(0.5f, cadence.progress((half.tick - 1).toDouble()), "half a span of frozen ticks")

        val over = frozen(s, OutofspaceReducer.RAIL_PERIOD * 4)
        assertEquals(cadence, over.motion.cadence, "the stamp moved and the slide would replay")
        assertEquals(1f, cadence.progress((over.tick - 1).toDouble()), "settled, and stays settled")
    }

    // ── Except the edit ───────────────────────────────────────────────────────

    @Test
    fun `an edit lands on a frozen tick`() {
        val busy = live(workingVessel(cfg.initialGrid), 300)
        val empty = beside(busy)

        val edited = frozen(busy, input = OutofspaceInput(listOf(fixturePlace(empty, Brush.Run(Conduit.Rail), Direction.Right))))

        assertTrue(edited.railAt(empty) != null, "the track the player laid while paused is not there")
    }

    /**
     * And **only** the edit: the same digest, against a frozen tick that did place something.
     *
     * Compared against the same world with the same edit applied on a *live* tick minus that tick's
     * physics would be circular, so this compares the two things a player can tell apart — the track
     * is down, and every ledger in the world reads the same as it did before the click.
     */
    @Test
    fun `an edit on a frozen tick moves nothing else`() {
        val busy = live(workingVessel(cfg.initialGrid), 300)
        val empty = beside(busy)
        val edited = frozen(busy, input = OutofspaceInput(listOf(fixturePlace(empty, Brush.Run(Conduit.Rail), Direction.Right))))

        assertEquals(busy.extractedMass, edited.extractedMass, "the extractor ran while the game was stopped")
        assertEquals(busy.ventedMass, edited.ventedMass, "a vent breathed")
        assertEquals(busy.radiatedEnergy, edited.radiatedEnergy, "the hull cooled")
        assertEquals(busy.airVentedMass, edited.airVentedMass)
        assertEquals(busy.injectedAirMass, edited.injectedAirMass)
        assertEquals(busy.positionX, edited.positionX, "the ship travelled")
        assertEquals(busy.positionY, edited.positionY)
    }

    // ── The things that would otherwise keep going ────────────────────────────

    @Test
    fun `a frozen tick does not fly the ship`() {
        // A vessel with momentum: it is the pose that must not advance, not the velocity.
        var s = live(workingVessel(cfg.initialGrid), 200)
        s = live(OutofspaceReducer.reduce(cfg, s, mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Thrust(1, 0))))), 60)
        assertTrue(s.velocityXAt(s.mass) != 0L, "this proves nothing unless the ship is moving")

        val still = frozen(s, 50)
        assertEquals(s.positionX, still.positionX, "the ship kept flying while the game was stopped")
        assertEquals(s.positionY, still.positionY)
        assertEquals(s.ang, still.ang, "and it kept turning")
        // The momentum is untouched, so releasing the pause resumes rather than restarts.
        assertEquals(s.vesselImpulseX, still.vesselImpulseX, "a pause cost the ship its momentum")
        assertTrue(live(still).positionX != s.positionX, "and it does not fly again afterwards")
    }

    @Test
    fun `a frozen tick does not run a machine`() {
        val busy = live(workingVessel(cfg.initialGrid), 300)
        assertTrue(busy.extractedMass > 0L, "this proves nothing unless something was being dug")
        assertEquals(busy.extractedMass, frozen(busy, 200).extractedMass, "an extractor bit while paused")
    }

    @Test
    fun `a thrust that lands on a frozen tick does not push`() {
        // A `Thrust` is an edit like any other, so it can arrive on a frozen tick. It must be inert:
        // pushing the ship is the one edit whose effect *is* time passing.
        val busy = live(workingVessel(cfg.initialGrid), 200)
        val shoved = frozen(busy, input = OutofspaceInput(listOf(Edit.Thrust(1, 0))))

        assertEquals(busy.positionX, shoved.positionX, "a paused ship was shoved")
        assertEquals(busy.vesselImpulseX, shoved.vesselImpulseX, "and it kept the momentum afterwards")
    }

    @Test
    fun `a rock at rest does not clang once a tick while the game is stopped`() {
        // Long enough for the spawned field to have settled against the plating.
        val busy = live(workingVessel(cfg.initialGrid), 400)
        assertTrue(busy.bodies.isNotEmpty(), "this proves nothing without a rock in the world")

        var s = busy
        repeat(30) {
            s = frozen(s)
            assertTrue(s.impacts.isEmpty(), "a resting rock was reported as a collision on a frozen tick")
            assertEquals(busy.bodies, s.bodies, "a rock drifted while the game was stopped")
        }
    }

    // ── And it is still the same world afterwards ─────────────────────────────

    /**
     * A pause is not a hole in the world's history: the run either side of it is the run there would
     * have been without it, to the gram. Ledgers included — a conservation check a pause could
     * unbalance would be a mass leak on a keypress.
     *
     * ⚠️ **The pause has to be a whole number of cycles**, and that is a real property of frozen
     * ticks rather than a fudge to make a test pass. They are counted like any other tick, so a
     * pause of 137 moves every subsystem to a different point in its period and the world afterwards
     * runs a different — equally valid — sequence. Pause for a multiple of the longest period and
     * the phase is exactly where it was, which is what makes this comparison bit-for-bit rather than
     * merely plausible. Nothing is starved either way: after a pause of any length each pass fires
     * again within its own period.
     */
    @Test
    fun `a pause of a whole cycle changes nothing about what follows`() {
        val start = live(workingVessel(cfg.initialGrid), 200)
        val straight = live(start, 200)
        val interrupted = live(frozen(live(start, 100), OutofspaceReducer.RAIL_PERIOD * 2), 100)

        assertEquals(worldBut(straight), worldBut(interrupted), "the run diverged across a pause")
    }
}
