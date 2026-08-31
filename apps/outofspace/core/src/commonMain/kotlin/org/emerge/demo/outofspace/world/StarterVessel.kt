package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.machine.Gauge
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Concentrator
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Vent

/**
 * **What the starting ship is built out of**, kind by kind.
 *
 * ⛔ **A fact about this vessel, not about the kinds.** Nothing in the game is normally made of
 * anything — see [Segment.material] — so a *stated* world has to state its substances, exactly as it
 * states where its walls are. This is the shipwright's specification for one ship. Another starting
 * ship, or one the player builds, answers differently and is no less a ship for it.
 *
 * It is deliberately not [materialBefore], which looks identical and means something else: that one
 * says what an old *save file* meant and belongs to the reader.
 */
private fun madeOf(kind: DeckMachineKind): Species = when (kind) {
    // The skin: cheap, stiff, and the only thing that touches space.
    DeckMachineKind.Hull, DeckMachineKind.Airlock -> Species.Steel
    // A furnace is lined with refractory or it takes itself apart the first time it is lit.
    DeckMachineKind.Furnace -> Species.Firebrick
    // Fittings that sit on a run are the run's metal, so a joint is one substance throughout.
    DeckMachineKind.Bridge, DeckMachineKind.Gauge -> RAIL_METAL
    DeckMachineKind.Valve -> WIRE_METAL
    // Everything else is machinery: light, strong and expensive, which is the trade the ship was
    // launched having already made.
    else -> Species.Titanium
}

/** The track the ship is launched with. Iron: cheap, and a decent conductor. */
private val RAIL_METAL = Species.Iron

/** Its wiring. Copper, for the reason anything is wired in copper. */
private val WIRE_METAL = Species.Copper

/**
 * Starting world: complete refinery line (extractor→concentrator→smelter→storage, waste vents).
 */
fun starterVessel(
    grid: Grid,
): VesselState {
    val deck = DeckArray(grid)
    val rails = arrayOfNulls<Segment>(grid.size)
    val wires = arrayOfNulls<Segment>(grid.size)

    fun put(x: Int, y: Int, m: (TileIndex) -> DeckMachine) {
        // Buildings anchored at centre. Clipped at the rim exactly as the machine `put` is: the
        // hull loops below run past the grid, and `grid.tile` of an off-grid (x, y) is not "no
        // tile" — it is row-major arithmetic landing on somebody else's tile.
        if (grid.inBounds(x, y)) {
            val machine = m(grid.tile(x, y))
            deck.stand(machine, withCasing = true, material = madeOf(machine.kind))
        }
    }

    /**
     * Lay track, keeping existing joins (preserves crossings).
     *
     * [gauge] stands a [Gauge] on the tile as well. It is a building over the run now rather than a
     * flag on it, so laying one is two acts and this does both — the starter vessel is stated, not
     * played, and it never goes through the edit path that would otherwise pair them.
     */
    fun lay(tile: TileIndex, gauge: Boolean = false) {
        rails[tile.index] = rails[tile.index] ?: Segment(Conduit.Rail, material = RAIL_METAL)
        if (gauge && deck[tile] == null) deck.stand(Gauge(tile), withCasing = true, material = RAIL_METAL)
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
        if (wires[tile.index] == null) wires[tile.index] = Segment(Conduit.Signal, material = WIRE_METAL)
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

    put(STARTER_PLATE_X, y) { Extractor(it, Direction.Right) }   // covers x 3..7
    put(13, y) { Concentrator(it, Direction.Right) }                           // covers x 12..14
    put(29, y) { Storage(it, Direction.Right) }   // the inventory: what you can build with

    // Extractor→Concentrator: a gauge reads raw ore. What it reports on is whatever wire runs under
    // it — nothing, here, until the player lays one.
    rail(7, 12, y, setOf(9))
    // Concentrator→tank: a gauge reads concentrate on the way.
    rail(14, 28, y, setOf(17))

    // Waste: vertical drops to vents.
    put(13, y + 4) { Vent(it) }
    column(13, y + 1, y + 4)

    // Wiring demo: 7 rows below.
    val wy = STARTER_DEMO_PLATE_Y
    put(STARTER_PLATE_X, wy) { Extractor(it, Direction.Right).withWiring(STOP_WHEN_FULL) }
    put(11, wy) { Storage(it, Direction.Right) }
    rail(7, 10, wy)
    // Sensor looks at tank bottom edge.
    put(11, wy + 2) { Sensor(it, Direction.Up, threshold = 0, delay = 0, release = 0) }

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
    // ⚠️ Stocked **before** the world is constructed, not after. [VesselState.baselineCargoMass]
    // defaults from what it is handed, so iron poured in afterwards would be cargo the ledger never
    // saw arrive — which reads as ore nobody extracted, and turns every conservation check red.
    val buffers = BufferLayer.forDeck(grid, deck).withStartingIron(grid, deck)
    return VesselState(
        grid = grid,
        deck = deck,
        buffers = buffers,
        rail = RailLayer.empty(grid.size),
        conduits = Conduits.of(
            grid.size,
            Conduit.Rail to rails.toList(),
            Conduit.Signal to wires.toList(),
        ),
    ).fitGrid()
}

/**
 * The heap of iron the vessel starts with — the thing every length of track you draw is built from.
 *
 * A ship that has to *build* its rails needs something to build the first one out of, and there is
 * nowhere else for it to come from: the extractor needs track to send its ore down, and laying that
 * track is the thing being paid for. So the stock is stated here, in the tank that has always been
 * commented "the inventory: what you can build with", rather than earned.
 *
 * ⚠️ Harmless while [VesselState.creative] is on, since nothing consumes it — track still arrives
 * finished. It is what makes the world playable the moment that switch is turned off.
 */
private fun BufferLayer.withStartingIron(grid: Grid, deck: DeckArray): BufferLayer = also {
    val tank = grid.tiles.firstOrNull { deck[it] is Storage } ?: return@also
    val store = bufferTile(grid, deck[tank]!!, tank, BufferRole.Inside) ?: return@also
    // Half a tank. Enough for a few hundred tiles of track, and short of [Storage.CAP] by enough
    // that the first thing the player does cannot be to overflow it.
    val mass = Storage.CAP / 2
    val cold = Mixture.of(Species.Iron to mass, energy = 0)
    // ⚠️ At **ambient**, not at zero energy. Ten tonnes of iron at absolute zero is not a stock of
    // building material, it is a heat sink the size of the ship, and it would suck the vessel cold
    // through the first machine that touched it.
    val warm = Mixture.of(Species.Iron to mass, energy = heatCapacityOf(cold) * Temperature.AMBIENT_KELVIN)
    it.put(store, warm)
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
