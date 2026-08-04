package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.OutofspaceReducer

/**
 * The world the game opens on: one complete refinery line, already running.
 *
 * It exists so the first thing anyone sees is the loop working end to end — ore mined, concentrated,
 * smelted, stored, waste vented — rather than an empty grid and a palette. It is also the fixture
 * the tests drive.
 *
 * Machines are rooms and the track runs *underneath* them: each run reaches under a building to the
 * one tile its port occupies, rather than butting up against its outside. Runs are short, because a
 * machine's output has to start a new one — its input and output are different networks.
 *
 * Two gauges sit in that run and answer the question the world otherwise never answers out loud —
 * the one before the processor reports raw ore on AMBER (about 41% iron), the one after reports the
 * concentrate on CYAN (about 75%). Watching those two numbers side by side *is* the explanation of
 * what a processor does.
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
    val rails = arrayOfNulls<Segment>(grid.size)

    fun put(x: Int, y: Int, m: Machine) {
        // Buildings anchor at their centre, so this places the whole footprint around (x, y).
        if (grid.inBounds(x, y)) machines[grid.index(x, y)] = m
    }

    /**
     * Lays track, **keeping** whatever joins are already at that tile.
     *
     * Overwriting instead would cut a crossing run's links while leaving its neighbours' intact — a
     * join that exists in one direction only, and a line that mysteriously stops halfway.
     */
    fun lay(tile: Int, channel: Channel? = null) {
        val existing = rails[tile]
        rails[tile] = existing?.copy(channel = channel ?: existing.channel)
            ?: Segment(Conduit.Rail, channel = channel)
    }

    /** Joins two adjacent tiles of track, both halves, exactly as a drag would. */
    fun join(a: Int, b: Int, dir: Direction) {
        rails[a] = rails[a]!!.joinedTo(dir)
        rails[b] = rails[b]!!.joinedTo(dir.opposite)
    }

    /**
     * Track from [fromX] to [toX] inclusive, running *through* whatever is on the deck, laid and
     * joined end to end.
     *
     * Two things worth seeing in this layout. The rail does not stop at a machine's edge and resume
     * on the far side — it carries on underneath, and the building reaches down to it at the one
     * tile its port is on. And each tile is explicitly **joined** to the next, because track that
     * merely touches is not connected.
     */
    fun rail(fromX: Int, toX: Int, y: Int, channelAt: Map<Int, Channel> = emptyMap()) {
        for (x in fromX..toX) {
            if (!grid.inBounds(x, y)) continue
            lay(grid.index(x, y), channelAt[x])
        }
        // Laid *and* connected. Track that merely touches is not joined any more, so a run has to
        // say so tile by tile — which is what makes two lines able to sit side by side.
        for (x in fromX until toX) {
            if (grid.inBounds(x, y) && grid.inBounds(x + 1, y)) {
                join(grid.index(x, y), grid.index(x + 1, y), Direction.Right)
            }
        }
    }

    /** A vertical run, for the waste that leaves through a machine's floor. */
    fun column(x: Int, fromY: Int, toY: Int) {
        for (y in fromY..toY) {
            if (grid.inBounds(x, y)) lay(grid.index(x, y))
        }
        for (y in fromY until toY) {
            if (grid.inBounds(x, y) && grid.inBounds(x, y + 1)) {
                join(grid.index(x, y), grid.index(x, y + 1), Direction.Down)
            }
        }
    }

    // The plant. Everything faces Right, so a building takes material in at the centre of its left
    // edge and puts product out at the centre of its right edge — one tile from centre for the
    // three-tile machines, two for the smelter. Waste leaves through the floor.
    //
    // **Each machine's output starts a NEW run.** A machine's input and output are two different
    // networks, and one continuous line under everything would join a machine's output back to the
    // track feeding its own input — where, material being pulled toward the nearest consumer, it
    // would go straight back in. A chain of short runs is the right shape.
    val y = 12

    put(5, y, Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY))   // covers x 4..6
    put(13, y, Processor(Direction.Right))                                  // covers x 12..14
    put(22, y, Smelter(Direction.Right))                                    // covers x 20..24
    put(29, y, Storage(Direction.Right))   // the inventory: what is in here is what you can build with

    // Miner's output port to the processor's input port. The gauge in it reads raw ore on AMBER,
    // about 41% iron.
    rail(6, 12, y, mapOf(9 to Channel.Amber))
    // Processor's output to the smelter's input, with the second gauge reading the concentrate on
    // CYAN at about 75%. Watching those two numbers side by side *is* the explanation of what a
    // processor does.
    rail(14, 20, y, mapOf(17 to Channel.Cyan))
    // Smelter's output to the tank.
    rail(24, 28, y)

    // Waste. Each machine's second port is in its floor, so its run drops away downward to a vent.
    put(13, y + 4, Vent())
    column(13, y + 1, y + 4)
    put(22, y + 5, Vent())
    column(22, y + 2, y + 5)

    // ── The wiring demonstration, seven rows below ──
    val wy = y + 7
    put(5, wy, Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY).withWiring(STOP_WHEN_RED))
    put(11, wy, Storage(Direction.Right))
    rail(6, 10, wy)
    // Watching the tank's bottom edge. A three-tile tank reaches to wy+1, so the sensor sits at
    // wy+2 to be looking at the building rather than at the deck below it.
    put(11, wy + 2, Sensor(Direction.Up, Channel.Red))

    // ── The hull, enclosing all of it ──
    val left = HULL_LEFT
    val right = HULL_RIGHT
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

    return centred(grid, machines, rails)
}

/**
 * Slides the finished vessel into the middle of its grid.
 *
 * ### Why this is not cosmetic
 *
 * It was laid out from `x = 1`, which put its port wall one tile from the edge of the world and left
 * sixty tiles of space to starboard. The rim is not scenery: [stepFluid] writes off whatever reaches
 * the outermost ring, so it is a hard vacuum that never fills, and a cell beside it is granted the
 * full expansion demand every tick forever. A vessel sitting on top of one vents into a six-tile
 * pocket on one side and into open space on the other.
 *
 * That is worth a lot more than it sounds. The same symmetrical hull with a centred breach leans
 * **28% one tile from the rim and 0% forty tiles away** — same ship, same hole, same solver. It was
 * mistaken twice for a bug in the fluid model, and it is neither a bug nor fixable there: a boundary
 * condition cannot conjure up somewhere for the gas to go. Suppressing the rim's suction only makes
 * it worse, because then the pocket fills and reflects instead of draining. The gas needs *room*, and
 * the only thing that provides room is not being next to the edge.
 *
 * So the layout keeps its comfortable absolute coordinates and gets translated once at the end,
 * which is why this is a shift rather than an origin threaded through forty call sites. Rails carry
 * directional joins and bridges carry ports, and a pure translation leaves every one of them
 * pointing the way it did.
 */
private fun centred(grid: Grid, machines: Array<Machine?>, rails: Array<Segment?>): VesselState {
    val shift = (grid.width - HULL_SPAN) / 2 - HULL_LEFT
    if (shift <= 0) return VesselState(grid = grid, machines = machines.toList(), rails = rails.toList())

    val movedMachines = arrayOfNulls<Machine>(grid.size)
    val movedRails = arrayOfNulls<Segment>(grid.size)
    for (y in 0 until grid.height) {
        for (x in 0 until grid.width) {
            val to = x + shift
            if (to >= grid.width) continue
            val from = grid.index(x, y)
            val into = grid.index(to, y)
            movedMachines[into] = machines[from]
            movedRails[into] = rails[from]
        }
    }
    return VesselState(grid = grid, machines = movedMachines.toList(), rails = movedRails.toList())
}

/** Where the hull is drawn before [centred] moves it — the two numbers that shift has to undo. */
private const val HULL_LEFT = 1
private const val HULL_RIGHT = 33
private const val HULL_SPAN = HULL_RIGHT - HULL_LEFT + 1
/** `RUN = ALWAYS − RED`: dig at full rate until something raises RED, then stop dead. */
private val STOP_WHEN_RED = Wiring(
    mapOf(
        Action.Run to listOf(
            Trigger(Channel.Always, Signals.FULL),
            Trigger(Channel.Red, -Signals.FULL),
        ),
    ),
)
