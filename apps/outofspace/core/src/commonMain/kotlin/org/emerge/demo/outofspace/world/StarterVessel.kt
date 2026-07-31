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

    return VesselState(grid = grid, machines = machines.toList())
}
