package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Machine
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.Processor
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Smelter
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Vent

/**
 * Starting world: complete refinery line (extractor→processor→smelter→storage, waste vents).
 */
fun starterVessel(
    grid: Grid,
): VesselState {
    val machines = arrayOfNulls<Machine>(grid.size)
    val deck = DeckArray(grid)
    val rails = arrayOfNulls<Segment>(grid.size)
    val wires = arrayOfNulls<Segment>(grid.size)

    fun put(x: Int, y: Int, m: Machine) {
        // Buildings anchored at centre.
        if (grid.inBounds(x, y)) machines[grid.tile(x, y).index] = m
    }
    fun put(x: Int, y: Int, m: (TileIndex) -> DeckMachine) {
        // Buildings anchored at centre. Clipped at the rim exactly as the machine `put` is: the
        // hull loops below run past the grid, and `grid.tile` of an off-grid (x, y) is not "no
        // tile" — it is row-major arithmetic landing on somebody else's tile.
        if (grid.inBounds(x, y)) deck += m(grid.tile(x, y))
    }

    /** Lay track, keeping existing joins (preserves crossings). */
    fun lay(tile: TileIndex, gauge: Boolean = false) {
        val existing = rails[tile.index]
        rails[tile.index] = existing?.copy(isGauge = gauge || existing.isGauge)
            ?: Segment(Conduit.Rail, isGauge = gauge)
    }

    /** Joins two adjacent tiles of track, both halves, exactly as a drag would. */
    fun join(a: TileIndex, b: TileIndex, dir: Direction) {
        rails[a.index] = rails[a.index]!!.joinedTo(dir)
        rails[b.index] = rails[b.index]!!.joinedTo(dir.opposite)
    }

    /** Horizontal track from [fromX] to [toX], laid and joined (runs under buildings). */
    fun rail(fromX: Int, toX: Int, y: Int, gaugeAt: Set<Int> = emptySet()) {
        for (x in fromX..toX) {
            if (!grid.inBounds(x, y)) continue
            lay(grid.tile(x, y), x in gaugeAt)
        }
        // Explicitly joined (touching ≠ connected).
        for (x in fromX until toX) {
            if (grid.inBounds(x, y) && grid.inBounds(x + 1, y)) {
                join(grid.tile(x, y), grid.tile(x + 1, y), Direction.Right)
            }
        }
    }

    fun layWire(tile: TileIndex) {
        if (wires[tile.index] == null) wires[tile.index] = Segment(Conduit.Signal)
    }

    fun joinWire(a: TileIndex, b: TileIndex, dir: Direction) {
        wires[a.index] = wires[a.index]!!.joinedTo(dir)
        wires[b.index] = wires[b.index]!!.joinedTo(dir.opposite)
    }

    /** Horizontal signal wire, laid and joined the way a drag would. */
    fun signalRow(fromX: Int, toX: Int, y: Int) {
        val lo = minOf(fromX, toX)
        val hi = maxOf(fromX, toX)
        for (x in lo..hi) if (grid.inBounds(x, y)) layWire(grid.tile(x, y))
        for (x in lo until hi) {
            if (grid.inBounds(x, y) && grid.inBounds(x + 1, y)) {
                joinWire(grid.tile(x, y), grid.tile(x + 1, y), Direction.Right)
            }
        }
    }

    /** Vertical signal wire. */
    fun signalColumn(x: Int, fromY: Int, toY: Int) {
        val lo = minOf(fromY, toY)
        val hi = maxOf(fromY, toY)
        for (yy in lo..hi) if (grid.inBounds(x, yy)) layWire(grid.tile(x, yy))
        for (yy in lo until hi) {
            if (grid.inBounds(x, yy) && grid.inBounds(x, yy + 1)) {
                joinWire(grid.tile(x, yy), grid.tile(x, yy + 1), Direction.Down)
            }
        }
    }

    /** A vertical run, for the waste that leaves through a machine's floor. */
    fun column(x: Int, fromY: Int, toY: Int) {
        for (y in fromY..toY) {
            if (grid.inBounds(x, y)) lay(grid.tile(x, y))
        }
        for (y in fromY until toY) {
            if (grid.inBounds(x, y) && grid.inBounds(x, y + 1)) {
                join(grid.tile(x, y), grid.tile(x, y + 1), Direction.Down)
            }
        }
    }

    // Plant: all face Right (input left, output right). Each machine output starts a new run.
    val y = STARTER_PLATE_Y

    put(STARTER_PLATE_X, y, Extractor(Direction.Right))   // covers x 3..7
    put(13, y, Processor(Direction.Right))                                  // covers x 12..14
    put(22, y, Smelter(Direction.Right))                                    // covers x 20..24
    put(29, y) { Storage(it, Direction.Right) }   // the inventory: what you can build with

    // Extractor→Processor: a gauge reads raw ore. What it reports on is whatever wire runs under
    // it — nothing, here, until the player lays one.
    rail(7, 12, y, setOf(9))
    // Processor→Smelter: a gauge reads concentrate.
    rail(14, 20, y, setOf(17))
    // Smelter's output to the tank.
    rail(24, 28, y)

    // Waste: vertical drops to vents.
    put(13, y + 4) { Vent(it) }
    column(13, y + 1, y + 4)
    put(22, y + 5) { Vent(it) }
    column(22, y + 2, y + 5)

    // Wiring demo: 7 rows below.
    val wy = STARTER_DEMO_PLATE_Y
    put(STARTER_PLATE_X, wy, Extractor(Direction.Right).withWiring(STOP_WHEN_FULL))
    put(11, wy) { Storage(it, Direction.Right) }
    rail(7, 10, wy)
    // Sensor looks at tank bottom edge.
    put(11, wy + 2) { Sensor(it, Direction.Up) }

    // ...and the run that makes it mean anything. This is the demonstration: the sensor drives the
    // wire beneath it, the wire reaches the extractor's anchor tile, and the extractor's second term
    // reads that wire. Every step of it is on screen, which is the entire point of the layer — the
    // old version of this vessel wired the two together through a colour named nowhere in the world.
    signalRow(STARTER_PLATE_X, 11, wy + 2)
    signalColumn(STARTER_PLATE_X, wy, wy + 2)

    // Hull: enclosing box.
    val left = 1
    val right = 33
    val top = y - 5
    val bottom = wy + 5
    for (hx in left..right) {
        put(hx, top, ::Hull)
        put(hx, bottom, ::Hull)
    }
    for (hy in top+1..<bottom) {
        put(left, hy, ::Hull)
        put(right, hy, ::Hull)
    }

    // No rock on either plate, and that is the increment showing through rather than an omission:
    // an extractor has to be **given** something to eat. What H4 changes is where you get one — the
    // ore is out there in the field now, and the vessel flies to it. See §5i and [RockField].
    val built = machines.toList()
    return VesselState(
        grid = grid,
        machines = built,
        deck = deck,
        buffers = BufferLayer.forMachines(grid, built),
        rail = RailLayer.empty(grid.size),
        conduits = Conduits.of(
            grid.size,
            Conduit.Rail to rails.toList(),
            Conduit.Signal to wires.toList(),
        ),
    ).fitGrid()
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

/**
 * `RUN = ALWAYS − WIRE`: dig at full rate until the wire under the machine rises, then stop dead.
 *
 * The same controller it has always been, with the colour swapped for the run. Note what an unwired
 * machine does with this: a `WIRE` term with no wire beneath it reads 0, so the extractor digs at
 * full rate — exactly what it did when RED was a channel nobody was emitting on. That is what let
 * every vessel in every save keep working the day the wire layer landed.
 */
private val STOP_WHEN_FULL = Wiring(
    mapOf(
        Action.Run to listOf(
            Trigger(SignalSource.Always, SignalField.FULL),
            Trigger(SignalSource.Wire, -SignalField.FULL),
        ),
    ),
)
