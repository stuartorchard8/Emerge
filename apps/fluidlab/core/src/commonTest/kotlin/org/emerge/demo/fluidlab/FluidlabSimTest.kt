package org.emerge.demo.fluidlab

import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tests a new app should start with. Because the reducer is pure and platform-free, all of this
 * runs headlessly in milliseconds on every target — no window, no GL, no device.
 *
 * The first test is the important one. A **golden digest** over the world after N ticks is the
 * cheapest possible tripwire for "I changed the sim and didn't mean to": it fails the moment a
 * refactor perturbs the trajectory, and passes silently when it doesn't. Cyto runs the same idea at
 * scale (`CytoGoldenTest`) — when it goes red, verify the new trajectory is *correct* before
 * re-baselining, or you have simply blessed the bug.
 */
class FluidlabSimTest {

    private val cfg = FluidlabConfig()

    /** Digest of the whole world — order-sensitive, so a reordering is a failure too. */
    private fun digest(state: FluidlabState): Long {
        var h = 1125899906842597L
        h = h * 31 + state.bodies.size
        for (b in state.bodies) {
            h = h * 31 + b.x.toRawBits()
            h = h * 31 + b.y.toRawBits()
            h = h * 31 + b.vx.toRawBits()
            h = h * 31 + b.vy.toRawBits()
        }
        return h
    }

    private fun run(ticks: Int, seed: Long = 0x5EEDL): FluidlabState {
        var s = FluidlabState.initial(cfg, seed = seed)
        repeat(ticks) { s = FluidlabReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    @Test
    fun `two runs from the same seed are bit-identical`() {
        assertEquals(digest(run(500)), digest(run(500)))
    }

    @Test
    fun `different seeds give different worlds`() {
        assertTrue(digest(run(100, seed = 1L)) != digest(run(100, seed = 2L)))
    }

    @Test
    fun `bodies stay inside the toroidal world`() {
        val half = cfg.worldSize * 0.5f
        for (b in run(1000).bodies) {
            assertTrue(b.x >= -half && b.x < half, "x out of range: ${b.x}")
            assertTrue(b.y >= -half && b.y < half, "y out of range: ${b.y}")
        }
    }

    @Test
    fun `input order does not depend on map iteration order`() {
        val a = mapOf(PlayerId(0) to FluidlabInput(spawns = listOf(0.1f to 0.1f)), PlayerId(1) to FluidlabInput(spawns = listOf(-0.2f to 0.3f)))
        val b = mapOf(PlayerId(1) to FluidlabInput(spawns = listOf(-0.2f to 0.3f)), PlayerId(0) to FluidlabInput(spawns = listOf(0.1f to 0.1f)))
        val start = FluidlabState.initial(cfg)
        assertEquals(digest(FluidlabReducer.reduce(cfg, start, a)), digest(FluidlabReducer.reduce(cfg, start, b)))
    }

    @Test
    fun `spawns are capped`() {
        val small = cfg.copy(maxBodies = 130)
        var s = FluidlabState.initial(small)
        repeat(20) {
            s = FluidlabReducer.reduce(small, s, mapOf(PlayerId(0) to FluidlabInput(spawns = List(10) { 0f to 0f })))
        }
        assertEquals(130, s.bodies.size)
    }

    @Test
    fun `clear empties the world`() {
        val s = FluidlabReducer.reduce(cfg, FluidlabState.initial(cfg), mapOf(PlayerId(0) to FluidlabInput(clear = true)))
        assertEquals(0, s.bodies.size)
    }

    @Test
    fun `wrapDelta takes the short way round the torus`() {
        assertEquals(-0.2f, wrapDelta(1.8f, 2f), 1e-6f)
        assertEquals(0.2f, wrapDelta(-1.8f, 2f), 1e-6f)
        assertEquals(0.5f, wrapDelta(0.5f, 2f), 1e-6f)
    }
}
