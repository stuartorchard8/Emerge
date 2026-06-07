package org.emerge.demo.norns.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-verification harness for the side-scroll world (subsystem 8 sim side): a viable,
 * deterministic, multi-floor colony, plus the player-interaction commands and a well-formed
 * camera frame. (Rigorous evolution-under-selection is gated separately by EvolutionTest.)
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
    fun creaturesStayWithinTheWorldBounds() {
        val w = NornsWorld(NornsConfig(), seed = 11)
        repeat(1500) {
            w.step()
            for (c in w.creatures) {
                assertTrue(c.x >= 0f && c.x < w.cfg.worldWidth, "x in bounds: ${c.x}")
                assertTrue(c.floor in 0 until w.cfg.floors, "floor in bounds: ${c.floor}")
            }
        }
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
            assertEquals(a.creatures[i].x.toRawBits(), b.creatures[i].x.toRawBits(), "creature $i x")
            assertEquals(a.creatures[i].floor, b.creatures[i].floor, "creature $i floor")
        }
    }

    @Test
    fun interactionCommandsAffectTheWorld() {
        val w = NornsWorld(NornsConfig(), seed = 3)
        val view = ViewState()
        repeat(50) { w.step() }
        val victim = w.creatures.first()

        // drop food at a specific spot
        NornsCommands.apply(w, view, "food 2 119")
        assertTrue(w.food.contains(w.cell(2, 119)), "food command should place food")

        // hand-feed lowers hunger
        victim.chem.setHunger(0.9f)
        NornsCommands.apply(w, view, "feed ${victim.id}")
        assertTrue(victim.hunger < 0.9f, "feed should lower hunger (${victim.hunger})")

        // pick up + place moves a creature and pauses its foraging
        NornsCommands.apply(w, view, "pick ${victim.id}")
        assertTrue(victim.held)
        NornsCommands.apply(w, view, "place ${victim.id} 1 5")
        assertEquals(1, victim.floor); assertEquals(5f, victim.x); assertTrue(!victim.held)

        // playback controls
        NornsCommands.apply(w, view, "follow ${victim.id}")
        assertEquals(victim.id, view.followId)
        NornsCommands.apply(w, view, "speed 300"); assertEquals(300L, view.delayMs)
        NornsCommands.apply(w, view, "pause"); assertTrue(view.paused)
        NornsCommands.apply(w, view, "go"); assertTrue(!view.paused)
        NornsCommands.apply(w, view, "quit"); assertTrue(view.quit)
    }

    @Test
    fun heldCreatureDoesNotForage() {
        val w = NornsWorld(NornsConfig(), seed = 9)
        repeat(20) { w.step() }
        val c = w.creatures.first()
        NornsCommands.apply(w, ViewState(), "place ${c.id} 0 80")
        NornsCommands.apply(w, ViewState(), "pick ${c.id}")
        val (fx, ff) = c.x to c.floor
        repeat(30) { w.step() }
        assertEquals(fx, c.x, "held creature should not move")
        assertEquals(ff, c.floor, "held creature should not change floor")
    }

    @Test
    fun liftOscillatesBetweenFloors() {
        val lift = Lift(column = 0)
        var minP = 9f; var maxP = -9f
        repeat(400) {
            lift.tick(floors = 3, speed = 0.05f)
            minP = minOf(minP, lift.carPos); maxP = maxOf(maxP, lift.carPos)
            assertTrue(lift.carPos in 0f..2f, "car stays within the floor range: ${lift.carPos}")
        }
        assertTrue(minP <= 0.05f && maxP >= 1.95f, "the car should sweep the full shaft (min=$minP max=$maxP)")
    }

    @Test
    fun changingFloorTakesTimeViaTheLift() {
        val w = NornsWorld(NornsConfig(), seed = 5)
        val c = w.creatures.first()
        w.place(c.id, 0, 0)          // floor 0, at a lift column
        c.chem.setHunger(0f)
        c.activity = ActivityType.MOVING
        c.goalAction = CreatureMind.A_SEEK_FOOD
        c.targetX = 0f; c.targetFloor = 1; c.partnerId = -1

        var rode = false
        var arrivedTick = -1
        for (t in 1..200) {
            w.step()
            if (c.onLift) rode = true
            if (c.floor == 1) { arrivedTick = t; break }
        }
        assertTrue(rode, "the creature should board the lift to change floor")
        assertTrue(arrivedTick > 5, "changing floor should take time, not be instant (took $arrivedTick ticks)")
        assertEquals(1, c.floor, "should arrive on the target floor")
    }

    @Test
    fun cameraFrameIsWellFormed() {
        val w = NornsWorld(NornsConfig(), seed = 3)
        repeat(50) { w.step() }
        val follow = w.creatures.first().id
        val frame = AsciiView.render(w, cameraX = 20, followId = follow)
        val lines = frame.split('\n')
        val vw = minOf(w.cfg.worldWidth, AsciiView.VIEW_WIDTH)
        // each floor contributes an air row + a floor line; both are vw chars wide between borders
        val airRow = lines[1]
        assertEquals(vw + 2, airRow.length, "air row width incl. borders")
        assertTrue(frame.contains("follow #$follow"), "detail panel present for the followed creature")
        assertTrue(frame.contains("hunger ["), "hunger bar present")
    }
}
