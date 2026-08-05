package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.OutofspaceReducer

/**
 * Starting world: complete refinery line (extractor→processor→smelter→storage, waste vents), and a
 * field of rocks in the space around it.
 *
 * [rocks] is a count rather than a flag so a fixture can ask for an empty sky and *say so* — most
 * of the suite wants a world with nothing in it but the vessel, and a test that inherited a rock
 * field it never mentioned would be measuring something it did not choose. See [RockField].
 */
fun starterVessel(
    grid: Grid,
    rocks: Int = RockField.DEFAULT_COUNT,
    rockSeed: Int = RockField.DEFAULT_SEED,
): VesselState {
    val machines = arrayOfNulls<Machine>(grid.size)
    val rails = arrayOfNulls<Segment>(grid.size)

    fun put(x: Int, y: Int, m: Machine) {
        // Buildings anchored at centre.
        if (grid.inBounds(x, y)) machines[grid.index(x, y)] = m
    }

    /** Lay track, keeping existing joins (preserves crossings). */
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

    /** Horizontal track from [fromX] to [toX], laid and joined (runs under buildings). */
    fun rail(fromX: Int, toX: Int, y: Int, channelAt: Map<Int, Channel> = emptyMap()) {
        for (x in fromX..toX) {
            if (!grid.inBounds(x, y)) continue
            lay(grid.index(x, y), channelAt[x])
        }
        // Explicitly joined (touching ≠ connected).
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

    // Plant: all face Right (input left, output right). Each machine output starts a new run.
    val y = STARTER_PLATE_Y

    put(STARTER_PLATE_X, y, Extractor(Direction.Right))   // covers x 3..7
    put(13, y, Processor(Direction.Right))                                  // covers x 12..14
    put(22, y, Smelter(Direction.Right))                                    // covers x 20..24
    put(29, y, Storage(Direction.Right))   // the inventory: what is in here is what you can build with

    // Extractor→Processor: gauge reads raw ore (AMBER).
    rail(7, 12, y, mapOf(9 to Channel.Amber))
    // Processor→Smelter: gauge reads concentrate (CYAN).
    rail(14, 20, y, mapOf(17 to Channel.Cyan))
    // Smelter's output to the tank.
    rail(24, 28, y)

    // Waste: vertical drops to vents.
    put(13, y + 4, Vent())
    column(13, y + 1, y + 4)
    put(22, y + 5, Vent())
    column(22, y + 2, y + 5)

    // Wiring demo: 7 rows below.
    val wy = STARTER_DEMO_PLATE_Y
    put(STARTER_PLATE_X, wy, Extractor(Direction.Right).withWiring(STOP_WHEN_RED))
    put(11, wy, Storage(Direction.Right))
    rail(7, 10, wy)
    // Sensor looks at tank bottom edge.
    put(11, wy + 2, Sensor(Direction.Up, Channel.Red))

    // Hull: enclosing box.
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

    // No rock on either plate, and that is the increment showing through rather than an omission:
    // an extractor has to be **given** something to eat. What H4 changes is where you get one — the
    // ore is out there in the field now, and the vessel flies to it. See §5i and [RockField].
    val built = machines.toList()
    val state = VesselState(
        grid = grid,
        machines = built,
        conduits = Conduits.ofRails(rails.toList()),
        // Handed to the constructor, so `baselineRockGrams` and `baselineJoules` count them and both
        // ledgers start at zero. A rock added by `copy` afterwards keeps the baselines of a world
        // that had none and reads as mass conjured out of nothing — see `workingVessel`.
        rocks = RockField.scatter(grid, built, rocks, rockSeed, OutofspaceReducer.DEFAULT_ORE_BODY),
    )
    return state.fitGrid()
}

/**
 * Where the starter vessel's two extractor plates are.
 *
 * Named because a plate is now the only place ore can come from, so "put a rock on the plate" is a
 * thing anything setting the world up has to be able to say without knowing the layout by heart —
 * and the layout has moved before. See `apps/outofspace/agent-scripts/extractor.txt`.
 */
const val STARTER_PLATE_X = 5
const val STARTER_PLATE_Y = 12
const val STARTER_DEMO_PLATE_Y = STARTER_PLATE_Y + 7

/** `RUN = ALWAYS − RED`: dig at full rate until something raises RED, then stop dead. */
private val STOP_WHEN_RED = Wiring(
    mapOf(
        Action.Run to listOf(
            Trigger(Channel.Always, Signals.FULL),
            Trigger(Channel.Red, -Signals.FULL),
        ),
    ),
)
