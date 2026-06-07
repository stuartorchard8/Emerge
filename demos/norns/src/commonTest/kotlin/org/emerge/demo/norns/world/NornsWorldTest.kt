package org.emerge.demo.norns.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-verification harness for the spatial world (subsystem 8 sim side): a viable, deterministic
 * colony — it doesn't die out or explode, creatures are born and die, and the ASCII frame is
 * well-formed. (Rigorous evolution-under-selection is gated separately by EvolutionTest; here the
 * world just has to be a watchable living colony.)
 */
class NornsWorldTest {

    @Test
    fun colonyStaysViable() {
        val w = NornsWorld(NornsConfig(), seed = 7)
        repeat(2500) {
            w.step()
            assertTrue(w.population in 1..w.cfg.maxPopulation,
                "population should stay alive and bounded (pop=${w.population} at tick=${w.ticks})")
        }
        assertTrue(w.births > 0, "creatures should breed")
        assertTrue(w.deaths > 0, "creatures should die")
    }

    @Test
    fun worldIsDeterministic() {
        fun run(): NornsWorld = NornsWorld(NornsConfig(), seed = 123).also { repeat(800) { _ -> it.step() } }
        val a = run(); val b = run()
        assertEquals(a.population, b.population)
        assertEquals(a.births, b.births)
        assertEquals(a.deaths, b.deaths)
        assertEquals(a.food.size, b.food.size)
        assertEquals(a.meanMetabolism().toRawBits(), b.meanMetabolism().toRawBits())
        for (i in a.creatures.indices) {
            assertEquals(a.creatures[i].id, b.creatures[i].id, "creature $i id")
            assertEquals(a.creatures[i].x, b.creatures[i].x, "creature $i x")
            assertEquals(a.creatures[i].y, b.creatures[i].y, "creature $i y")
        }
    }

    @Test
    fun asciiFrameIsWellFormed() {
        val w = NornsWorld(NornsConfig(width = 20, height = 8), seed = 3)
        repeat(50) { w.step() }
        val frame = AsciiView.render(w)
        val lines = frame.trimEnd().split('\n')
        // top border + height rows + bottom border + 2 HUD lines
        assertEquals(w.cfg.height + 4, lines.size, "frame line count")
        for (y in 1..w.cfg.height) {
            assertEquals(w.cfg.width + 2, lines[y].length, "grid row $y width (incl. borders)")
        }
        assertTrue(lines.last().contains("meanMetabolism"), "HUD present")
    }
}
