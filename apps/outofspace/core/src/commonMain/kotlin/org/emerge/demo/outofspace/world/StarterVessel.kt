package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.OutofspaceReducer

/**
 * The world the game opens on: one complete refinery line, already running.
 *
 * It exists so the first thing anyone sees is the loop working end to end — ore mined, concentrated,
 * smelted, banked, waste vented — rather than an empty grid and a palette. It is also the Phase 2
 * exit criterion made executable, and the fixture the tests drive.
 *
 * ```
 *  MINER > belt belt belt > PROCESSOR > belt belt belt > SMELTER > belt belt belt > NODE
 *                               v                           v
 *                             VENT                        VENT
 * ```
 *
 * The processor is deliberately half the miner's throughput, so the line backs up and the belts
 * behind it fill — the jam is the point, and it is visible from the first minute.
 *
 * A second, shorter line below it demonstrates **wiring**: a storage with nowhere to send its
 * contents fills up, a sensor watching it broadcasts that fullness on RED, and the miner feeding it
 * is wired `ALWAYS - RED`, so it digs until the tank is full and then stops. Two machines and one
 * sensor is the smallest thing that shows what the trigger grammar is for.
 */
fun starterVessel(grid: Grid): VesselState {
    val machines = arrayOfNulls<Machine>(grid.size)
    val y = grid.height / 2

    fun put(x: Int, yy: Int, m: Machine) {
        if (grid.inBounds(x, yy)) machines[grid.index(x, yy)] = m
    }

    fun beltRun(fromX: Int, toX: Int, yy: Int) {
        for (x in fromX..toX) put(x, yy, Belt(Direction.Right))
    }

    var x = 4
    put(x, y, Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)); x++
    beltRun(x, x + 2, y); x += 3
    put(x, y, Processor(Direction.Right))
    put(x, y + 1, Vent()); x++            // tailings drop out the side clockwise of Right
    beltRun(x, x + 2, y); x += 3
    put(x, y, Smelter(Direction.Right))
    put(x, y + 1, Vent()); x++            // slag likewise
    beltRun(x, x + 2, y); x += 3
    put(x, y, Node())

    // ── The wiring demonstration, three rows below ──
    val wy = y + 3
    put(4, wy, Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY).withWiring(STOP_WHEN_RED))
    beltRun(5, 6, wy)
    put(7, wy, Storage(Direction.Right))          // faces empty floor, so it fills rather than drains
    put(7, wy + 1, Sensor(Direction.Up, Channel.Red))

    return VesselState(grid = grid, machines = machines.toList())
}

/** `RUN = ALWAYS − RED`: dig at full rate until something raises RED, then stop dead. */
private val STOP_WHEN_RED = Wiring(
    mapOf(
        Action.Run to listOf(
            Trigger(Channel.Always, Signals.FULL),
            Trigger(Channel.Red, -Signals.FULL),
        ),
    ),
)
