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
        repeat(12000) { // the sim runs ~4x slower now, so give it proportionally more ticks
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

        // Hand: tickle floods pleasure, slap floods pain (the reward/punishment substrate)
        NornsCommands.apply(w, view, "tickle ${victim.id}")
        assertTrue(victim.chem.pleasure > 0f, "tickle should flood pleasure")
        NornsCommands.apply(w, view, "slap ${victim.id}")
        assertTrue(victim.chem.pain > 0f, "slap should flood pain")

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
    fun liftIdlesUntilCalledThenServesEachFloor() {
        val lift = Lift(column = 0)
        // an uncalled lift never moves on its own (unlike the old perpetual oscillator)
        repeat(50) { lift.tick(speed = 0.05f, dwellTicks = 10) }
        assertEquals(0f, lift.carPos, "an uncalled car stays parked where it is")
        assertTrue(lift.idle)

        // pressing a call button summons it to that floor, where it stops, pauses, then idles
        lift.call(2)
        var ticks = 0
        while (!lift.idle && ticks < 2000) { lift.tick(0.05f, 10); ticks++ }
        assertEquals(2f, lift.carPos, "the car arrives at the called floor")
        assertTrue(ticks > 1, "travelling to the floor takes time, not an instant hop")
        assertTrue(lift.idle, "after serving the call (and pausing) the car waits there")

        // a later call brings it back, and it stays within the floor range throughout
        lift.call(0)
        ticks = 0
        while (!lift.idle && ticks < 2000) {
            lift.tick(0.05f, 10); ticks++
            assertTrue(lift.carPos in 0f..2f, "car stays within the floor range: ${lift.carPos}")
        }
        assertEquals(0f, lift.carPos, "the car returns to the newly called floor")
    }

    @Test
    fun liftFinishesItsTripBeforeAnsweringANewerCall() {
        // car at floor 0, called up to floor 2; partway up, floor 0 is called again (the nearer
        // button). It must NOT turn back — it finishes the trip to 2, pauses, THEN serves 0.
        val lift = Lift(column = 0)
        lift.call(2)
        repeat(10) { lift.tick(0.05f, 8) }            // start climbing toward 2
        val midway = lift.carPos
        assertTrue(midway > 0f && midway < 2f, "car should be mid-shaft, was $midway")
        lift.call(0)                                   // a nearer call arrives mid-trip

        var ticks = 0
        while (lift.carPos < 2f && ticks < 2000) {
            val prev = lift.carPos
            lift.tick(0.05f, 8); ticks++
            assertTrue(lift.carPos >= prev - 1e-4f, "car must not reverse before reaching its target")
        }
        assertEquals(2f, lift.carPos, "it reaches its committed destination first")

        // only now (after the stop + pause) does it answer the floor-0 call queued mid-trip
        ticks = 0
        while (!lift.idle && ticks < 3000) { lift.tick(0.05f, 8); ticks++ }
        assertEquals(0f, lift.carPos, "then it goes to the floor that was called during the trip")
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
        for (t in 1..1500) { // lift + movement are ~4x slower now
            w.step()
            if (c.onLift) rode = true
            if (c.floor == 1) { arrivedTick = t; break }
        }
        assertTrue(rode, "the creature should board the lift to change floor")
        assertTrue(arrivedTick > 5, "changing floor should take time, not be instant (took $arrivedTick ticks)")
        assertEquals(1, c.floor, "should arrive on the target floor")
    }

    @Test
    fun movementButtonsStepTheCarOneFloor() {
        val w = NornsWorld(NornsConfig(), seed = 2)
        val lift = w.lifts.first()                       // parked at floor 0
        assertEquals(0, lift.restFloor())

        w.liftUp(lift)
        assertTrue(1 in lift.calls, "the up button queues the floor above")
        // drive the car with bare ticks (no creatures interfering) until it settles
        var t = 0; while ((lift.target >= 0 || lift.calls.isNotEmpty()) && t < 5000) { lift.tick(0.05f, 5); t++ }
        assertEquals(1f, lift.carPos, "up sends the car up one floor")

        w.liftDown(lift)
        assertTrue(0 in lift.calls, "the down button queues the floor below")
        t = 0; while ((lift.target >= 0 || lift.calls.isNotEmpty()) && t < 5000) { lift.tick(0.05f, 5); t++ }
        assertEquals(0f, lift.carPos, "down brings it back down a floor")
    }

    @Test
    fun liftCommandPressesTheButtons() {
        val w = NornsWorld(NornsConfig(), seed = 2)
        val lift = w.lifts.first()
        NornsCommands.apply(w, ViewState(), "lift 0 up")
        assertTrue(1 in lift.calls, "`lift 0 up` presses the up button")
        NornsCommands.apply(w, ViewState(), "lift 0 ${w.cfg.floors - 1}")
        assertTrue((w.cfg.floors - 1) in lift.calls, "`lift 0 <floor>` presses that floor's call button")
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
