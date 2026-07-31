package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.OutofspaceReducer

/**
 * The world the game opens on: one complete refinery line, already running.
 *
 * It exists so the first thing anyone sees is the loop working end to end — ore mined, concentrated,
 * smelted, stored, waste vented — rather than an empty grid and a palette. It is also the fixture
 * the tests drive.
 *
 * Machines are rooms now, so the line reads as a plant rather than a row of icons: three-tile miner,
 * processor and tanks, a five-tile smelter, and one-tile conveyors running between their ports. The
 * two analyzers sit in the belt runs either side of the processor, and answer the question the world
 * otherwise never answers out loud — the one before it reports raw ore on AMBER (about 41% iron),
 * the one after reports the concentrate on CYAN (about 75%). Watching those two numbers side by side
 * *is* the explanation of what a processor does.
 *
 * The processor is deliberately slower than the miner, so the line backs up and the belts behind it
 * fill. The jam is the point, and it is visible from the first minute.
 *
 * A second, shorter line below demonstrates **wiring**: a storage with nowhere to send its contents
 * fills up, a sensor watching it broadcasts that fullness on RED, and the miner feeding it is wired
 * `ALWAYS - RED`, so it digs until the tank is full and then stops.
 *
 * The whole thing sits inside a hull box, which is what makes it a *vessel* rather than machinery
 * floating in space. Knock a hull tile out and that room becomes outside.
 */
fun starterVessel(grid: Grid): VesselState {
    val machines = arrayOfNulls<Machine>(grid.size)

    fun put(x: Int, y: Int, m: Machine) {
        // Machines anchor at their centre, so this places the whole footprint around (x, y).
        if (grid.inBounds(x, y)) machines[grid.index(x, y)] = m
    }

    fun beltRun(fromX: Int, toX: Int, y: Int) {
        for (x in fromX..toX) put(x, y, Belt(Direction.Right))
    }

    // The spine of the plant. Everything below faces Right, so a machine's input port is the centre
    // of its left edge and its product port the centre of its right edge — one tile out from centre
    // for the three-tile machines, two for the smelter. The belt runs join those ports, and the
    // vents sit under the waste ports, which are on the machines' *bottom* edges.
    val y = 12

    put(5, y, Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY))   // covers x 4..6
    beltRun(7, 8, y)
    put(9, y, Analyzer(Direction.Right, Channel.Amber))                     // raw ore, on the way in
    beltRun(10, 11, y)

    put(13, y, Processor(Direction.Right))                                  // covers x 12..14
    put(13, y + 2, Vent())                                                  // under its tailings port
    beltRun(15, 16, y)
    put(17, y, Analyzer(Direction.Right, Channel.Cyan))                     // concentrate, on the way out
    beltRun(18, 19, y)

    put(22, y, Smelter(Direction.Right))                                    // covers x 20..24
    put(22, y + 3, Vent())                                                  // under its slag port
    beltRun(25, 27, y)

    put(29, y, Storage(Direction.Right))   // the inventory: what is in here is what you can build with

    // ── The wiring demonstration, seven rows below ──
    val wy = y + 7
    put(5, wy, Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY).withWiring(STOP_WHEN_RED))
    beltRun(7, 9, wy)
    put(11, wy, Storage(Direction.Right))        // faces open deck, so it fills rather than drains
    // Watching the tank's bottom edge. A three-tile tank reaches to wy+1, so the sensor sits at
    // wy+2 to be looking at the building rather than at the deck below it.
    put(11, wy + 2, Sensor(Direction.Up, Channel.Red))

    // ── The hull, enclosing all of it ──
    val left = 1
    val right = 33
    val top = y - 5
    val bottom = wy + 5
    for (hx in left..right) {
        put(hx, top, Hull())
        put(hx, bottom, Hull())
    }
    for (hy in top..bottom) {
        put(left, hy, Hull())
        put(right, hy, Hull())
    }

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
