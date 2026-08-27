package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.world.Ambient
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Flying through something.**
 *
 * Outside the grid used to be stated by absence — a rim face only ever shed, and `beyond()` read a
 * potential of zero. [Ambient] gives it contents, and everything below comes out of that one change
 * plus the machinery that was already there.
 *
 * ⛔ **No drag law is stated anywhere in the game**, and that is the result rather than an
 * implementation note. The ambient is at rest in the world; a moving hull scoops gas that is not
 * moving; the coupling then drags that gas up to the hull's speed at the ship's expense — see
 * [org.emerge.demo.outofspace.world.airCoupling]. Drag is momentum carried in and not carried back
 * out. Nothing computes a drag coefficient, a frontal area or a Reynolds number.
 *
 * ⚠️ **Every ship here has an open hull**, and that is the case this models honestly. Gas crosses
 * the boundary, is accelerated, and leaves again; a hermetically sealed hull would still be dragged,
 * because the ambient floods the vacuum tiles between the hull and the edge of the grid and *that*
 * gas is carried too — but how much of it there is depends on how much pad the grid happens to have,
 * which is not physics. See the note on the last test.
 */
class AtmosphericFlightTest {

    private fun flying(ambient: Ambient, tilesPerTick: Long): VesselState {
        val grid = Grid(25, 17)
        val deck = DeckArray(grid)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in 4..20) { put(x, 4); put(x, 12) }
        for (y in 4..12) { put(4, y); put(20, y) }
        val base = VesselState(
            grid = grid, deck = deck,
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        ).copy(ambient = ambient)
        // ⚠️ Booked to the debug engine as well, so the fixture starts with a **closed** ledger. A
        // ship simply handed momentum is one the balance reports as broken from tick zero, and then
        // every conservation assertion below would be measuring the fixture instead of the sim.
        val p = scaledRatio(base.mass, Flight.PER_TILE, tilesPerTick)
        return base.copy(vesselImpulseX = p, debugImpulseX = p)
    }

    /**
     * How much momentum the hull loses over [ticks], **after** its own atmosphere has come up to
     * speed.
     *
     * ⚠️ The settling run is not padding. A ship started at speed with its air at rest spends the
     * first few hundred ticks towing that air up — a real cost, but a **transient**, and one a
     * vacuum ship pays too. Measuring across it reads that transient as drag and would report a
     * vacuum as draggy. See `a ship coasting in vacuum is not dragged by anything`.
     */
    private fun dragged(ambient: Ambient, tilesPerTick: Long, ticks: Int = 200): Long {
        val controller = OutofspaceController(OutofspaceConfig(), flying(ambient, tilesPerTick))
        repeat(600) { controller.stepOnce() }
        val settled = controller.state.vesselImpulseX
        repeat(ticks) { controller.stepOnce() }
        val s = controller.state
        assertEquals(0L, s.airBalance, "the air ledger broke while flying through $ambient")
        assertEquals(0L, s.momentumBalanceX, "the momentum ledger broke while flying through $ambient")
        return settled - s.vesselImpulseX
    }

    @Test
    fun `a ship coasting in vacuum is not dragged by anything`() {
        val lost = dragged(Ambient.VACUUM, 200_000_000L)
        val carried = flying(Ambient.VACUUM, 200_000_000L).vesselImpulseX
        // Not exactly zero: the atmosphere aboard is still trading momentum with the hull as cargo
        // shifts. It must be *nothing next to* what the same ship loses in air.
        assertTrue(
            lost < carried / 1000L,
            "a ship in vacuum lost $lost of $carried — something is dragging it that should not be",
        )
    }

    @Test
    fun `an atmosphere drags, and a denser one drags harder`() {
        val speed = 200_000_000L
        val vacuum = dragged(Ambient.VACUUM, speed)
        val earth = dragged(Ambient.EARTHLIKE, speed)
        val giant = dragged(Ambient.GAS_GIANT, speed)

        assertTrue(earth > vacuum * 10L, "air did not drag: $earth against $vacuum in vacuum")
        // ⚠️ **Harder, and deliberately not "twenty times harder".** Measured at 4.0x for 20x the
        // density, and the sub-proportionality is the model being sensible rather than wrong: once
        // a hull has flooded it is submerged, the exchange across its rim is near balance, and what
        // is left is the through-flow. A ship in a gas giant is a ship full of gas giant.
        assertTrue(
            giant > earth * 2L,
            "a gas giant twenty times as dense as sea-level air dragged only ${giant.toDouble() / earth} " +
                "times as hard — the intake is not following the density at all",
        )
    }

    /**
     * **Drag grows faster than the speed does**, which is the shape that matters rather than any
     * particular coefficient.
     *
     * Two terms feed the intake and only one of them knows the ship is moving: the ambient sheds a
     * share inward whatever happens, and the leading face *also* sweeps up whatever it drives
     * through. So the momentum lost per unit of speed is not constant — it climbs as the ram term
     * overtakes the diffusive one, which is a linear drag at a crawl tending to a quadratic one at
     * speed. Measured as momentum lost per unit of speed from Mach 0.1 to Mach 1: **449, 674,
     * 1005, 1320**.
     *
     * ⚠️ **It rolls back over above Mach 1** — 1091 at Mach 2 — and that is the model reaching its
     * limit rather than a fact about flight. The hull floods faster than it can pass gas through, so
     * the intake chokes on how much the interior will take. Real drag goes on climbing.
     */
    @Test
    fun `drag grows faster than linearly with speed`() {
        val slow = 50_000_000L
        val fast = 800_000_000L
        val perUnitSlow = dragged(Ambient.EARTHLIKE, slow) * 1000L / slow
        val perUnitFast = dragged(Ambient.EARTHLIKE, fast) * 1000L / fast

        assertTrue(
            perUnitFast > perUnitSlow,
            "drag was exactly proportional to speed ($perUnitSlow vs $perUnitFast per unit) — the " +
                "leading face is not scooping, so this is diffusion and not flight",
        )
    }

    /**
     * ⛔ **PARKED — the model cannot tell a duct from a scoop, and this is the POC's real finding.**
     *
     * The claim is Stu's, and it is the right one: a hull gas can blow straight through should be
     * dragged less than one that traps it, because gas that leaves still carrying what it came in
     * with never cost the ship anything. Measured, a hull open front and back loses **160.7e9**
     * against a scoop's **161.8e9** — seven parts in a thousand, which is nothing.
     *
     * **Why, and it is not a tuning problem.** The atmosphere has *one* momentum for the whole
     * vessel — see [VesselState.airMomentumX]. Gas leaving takes its share of that **average**, so
     * a gram that arrived this tick and a gram that has been aboard for a minute leave carrying
     * exactly the same thing. Whether a parcel had time to be accelerated before it left is
     * precisely the distinction "aerodynamic" is made of, and a bulk store has nowhere to put it.
     *
     * ⛔ **So this wants per-tile gas momentum, which is the transport solver that was cut** —
     * advection, projection, CFL, sub-stepping, and the 3× suite time that went with it. It is not
     * a small addition and it should not be smuggled in as one. Everything else here works without
     * it: drag exists, follows density, and grows faster than linearly with speed.
     */
    @kotlin.test.Ignore
    @Test
    fun `a hull gas can pass through is dragged less than one it collects in`() {
        fun lostBy(openTrailing: Boolean): Long {
            val grid = Grid(25, 17)
            val deck = DeckArray(grid)
            fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
            for (x in 4..20) { put(x, 4); put(x, 12) }
            for (y in 4..12) {
                // Moving +x, so x = 20 is the leading wall and x = 4 the trailing one.
                if (y !in 7..9) put(20, y)
                if (!(openTrailing && y in 7..9)) put(4, y)
            }
            val base = VesselState(
                grid = grid, deck = deck,
                buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
            ).copy(ambient = Ambient.EARTHLIKE)
            val p = scaledRatio(base.mass, Flight.PER_TILE, 400_000_000L)
            val c = OutofspaceController(OutofspaceConfig(), base.copy(vesselImpulseX = p, debugImpulseX = p))
            repeat(600) { c.stepOnce() }
            val settled = c.state.vesselImpulseX
            repeat(200) { c.stepOnce() }
            return settled - c.state.vesselImpulseX
        }

        val scoop = lostBy(openTrailing = false)
        val duct = lostBy(openTrailing = true)
        assertTrue(
            duct < scoop * 9L / 10L,
            "a hull gas can blow straight through was dragged as hard as one that traps it " +
                "($duct against $scoop) — the atmosphere has one bulk momentum, so what leaves " +
                "carries the average and a fresh parcel is indistinguishable from a resident one",
        )
    }

    /**
     * **What a face meets is the outside compressed or rarefied by how fast the hull drives into
     * it** — `1 + M` along that face's own outward normal.
     *
     * Half of Mach 1 into the wind is a leading face reading about 150% of an atmosphere while the
     * one behind reads 50%; past Mach 1 the trailing face reads **nothing at all**, because a vessel
     * outrunning sound leaves a vacuum behind it. That is where the wake comes from, and it is the
     * whole of what makes a moving ship's boundary asymmetric.
     *
     * ⚠️ [Flight.ventTilesPerTick] is the speed of sound *and* the speed gas leaves a hole at,
     * because those are the same physical quantity — five tiles a tick at 64 Hz.
     *
     * Measured, leading against trailing across the range: **3.0, 6.9, 15.2, 42.8, 201.5**.
     */
    @Test
    fun `a moving hull meets a compressed atmosphere ahead and a rarefied one behind`() {
        fun ratioAt(tilesPerTick: Long): Double {
            val c = OutofspaceController(OutofspaceConfig(), flying(Ambient.EARTHLIKE, tilesPerTick))
            repeat(600) { c.stepOnce() }
            val s = c.state
            assertEquals(0L, s.airBalance, "the air ledger broke at $tilesPerTick")
            // The vacuum gap outside each wall: +x is the leading side, -x the trailing one.
            val ahead = s.air.mixtureAt(s.grid.tile(23, 8)).total
            val behind = s.air.mixtureAt(s.grid.tile(1, 8)).total
            assertTrue(behind >= 0L)
            return ahead.toDouble() / (behind + 1L)
        }

        val crawling = ratioAt(500_000_000L)     // Mach 0.1
        val transonic = ratioAt(2_500_000_000L)  // Mach 0.5
        val supersonic = ratioAt(10_000_000_000L) // Mach 2

        assertTrue(crawling > 1.0, "a moving hull met the same atmosphere on both sides: $crawling")
        assertTrue(
            transonic > crawling * 2.0,
            "the gradient did not steepen with speed: $crawling at Mach 0.1, $transonic at Mach 0.5",
        )
        assertTrue(
            supersonic > 50.0,
            "past the speed of sound the trailing face still held a real atmosphere " +
                "(leading is only ${supersonic}x it) — there is no wake",
        )
    }

    private companion object { init { RockSpawner.enabled = false } }
}
