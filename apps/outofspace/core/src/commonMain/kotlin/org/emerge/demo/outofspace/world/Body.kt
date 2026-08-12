package org.emerge.demo.outofspace.world

/**
 * Which list a solid thing is stored in. Enough, with its index, to put a body's heat back where it
 * came from once the conduction pass has worked out where it went.
 */
enum class BodySlot {
    /** [VesselState.machines] — buildings and hull, one entry per machine at its centre tile. */
    Deck,

    /** [VesselState.conduits] — one segment per tile of one conduit layer. */
    Fitting,

    /** [VesselState.bridges] — stored at the middle of the three tiles it spans. */
    Span,
}

/**
 * One solid object with one temperature (wall, furnace, track tile).
 * Energy stored on object (Machine.joules/Segment.joules), not tile-field (prevents desync on copy).
 * [permeable] controls conduction contacts (impermeable = tile-face; permeable = tile-sharing + air).
 */
class Body(
    val slot: BodySlot,
    /** The index it is stored at — its centre tile for a machine, its own tile for a fitting. */
    val at: Int,
    /**
     * Which of a machine's tiles this is, as an index into its [Machine.joules].
     *
     * Zero for anything that is still stored as one piece — a fitting, a bridge — where [at] alone
     * identifies where the energy goes back. For a machine it is the other half of the address:
     * [at] says which machine and this says which of its tiles, and both are needed now that a
     * machine is several bodies rather than one.
     */
    val part: Int = 0,
    /** Every tile it is present on. One for a fitting, three for a span, a footprint for a machine. */
    val tiles: IntArray,
    val material: Material,
    /** True for a fitting on a conduit layer: it shares its tile with the air rather than filling it. */
    val permeable: Boolean,
    /** Thermal energy, in the millijoules [Material] documents. */
    val joules: Long,
    /** Millijoules/kelvin. Uses MachineKind.thermalTiles (not tiles[]) to avoid grid-edge clipping. */
    val capacity: Long,
    /**
     * What crosses a contact of it, per kelvin per tick.
     *
     * Carried rather than read off [material] because a joint conducts through the metal that is
     * actually there: a hull plate is a few per cent of its tile, and so is its cross-section. It
     * therefore takes the same [MachineKind.fillPermille] its [capacity] does, and the pair of them
     * moving together is what holds every thermal time constant in the game still while the masses
     * underneath them become real. Divide one by the other and you get the number
     * [Material.conductanceCentiTicks] states.
     */
    val conductance: Long,
    /** Fitting's conduit layer; null for non-fittings. Third key component (slot+at+conduit). */
    val conduit: Conduit? = null,
    /**
     * A fitting's [Segment.links], carried rather than looked up.
     *
     * Heat runs along a drawn line and not across an undrawn one, so the contact graph needs to know
     * what this segment was joined to. Copying the bitmask here is what lets the conduction pass stop
     * taking a segment list at all: with the material, the energy and the links all on the body, the
     * pass no longer has to be told which list the body came out of in order to ask it anything.
     */
    val links: Int = 0,
) {
    /**
     * Guarded the same way [RigidBody.kelvin] is, and for the same reason.
     *
     * A zero capacity is not hypothetical: it is what a body of nothing has, and [capacity] is a
     * constructor argument rather than something this class derives, so nothing here can promise it
     * is positive. Space is the honest answer for a thing with no heat to hold — it is what the
     * temperature of an empty tile already is — and it is a great deal more honest than the divide
     * by zero this used to be.
     */
    val kelvin: Int get() = if (capacity <= 0L) Temperature.SPACE_KELVIN else (joules / capacity).toInt()

    /** Whether this fitting is joined to its neighbour in [dir] — see [Segment.links]. */
    fun linkedTo(dir: Direction): Boolean = links and (1 shl dir.ordinal) != 0
}

/**
 * Every solid thing in the world, in a fixed order: deck, then fittings, then spans.
 *
 * Rebuilt every tick rather than kept, exactly as [StructureMap] and [Occupancy] are, and for the
 * identical reason — a cache with an invalidation rule is a bug waiting for an edit case nobody
 * thought of, and walking three arrays is a rounding error next to the fluid solve.
 *
 * Takes the three lists rather than a [VesselState] so that the reducer can call it on its own
 * half-built working copies, which is where the heat step actually runs.
 */
fun bodiesOf(
    grid: Grid,
    machines: List<Machine?>,
    conduits: Conduits,
    bridges: List<Machine?>,
): List<Body> {
    val out = ArrayList<Body>(64)
    for (i in machines.indices) {
        val m = machines[i] ?: continue
        // ⚠️ One body per TILE of the machine, not one per machine — step 6b of
        // PLAN_unit_rescale.md. Everything interesting about that follows from doing it here and
        // nowhere else: [stepSolidHeat] already joins any two impermeable bodies that share a tile
        // face, and the tiles of one machine are exactly that, so a machine begins conducting
        // through *itself* without a line of new physics. A five-by-five smelter stops being one
        // temperature and grows a hot face and a cool one.
        //
        // Placement enforces `footprintFits`, so a machine's footprint is never clipped by the grid
        // edge and this index lines up with the `thermalTiles` slots its energy is stored in. The
        // bound guards the one case that is not a placed machine: a grid that shrank underneath it.
        val covered = coveredTiles(grid, i, m.kind.size)
        for (part in covered.indices) {
            if (part >= m.joules.size) break
            out.add(
                Body(
                    slot = BodySlot.Deck,
                    at = i,
                    part = part,
                    tiles = intArrayOf(covered[part]),
                    material = m.kind.material,
                    permeable = false,
                    joules = m.joules[part],
                    capacity = m.kind.capacityPerTile,
                    conductance = m.kind.conductance,
                )
            )
        }
    }
    conduits.all { conduit, i, s ->
        out.add(
            Body(
                slot = BodySlot.Fitting,
                at = i,
                tiles = intArrayOf(i),
                material = conduit.material,
                permeable = true,
                joules = s.joules,
                capacity = conduit.capacityPerTile,
                conductance = conduit.conductance,
                conduit = conduit,
                links = s.links,
            )
        )
    }
    for (i in bridges.indices) {
        val b = bridges[i] as? Bridge ?: continue
        // The three tiles it looks like: the one it is stored on and one either side along its
        // facing. It occupies none of them on any layer, which is what a bridge is — but it is
        // still three tiles of metal sitting in three tiles of room, and heat does not care whether
        // something claims the floor space. Split per tile for the same reasons a machine is.
        //
        // ⚠️ The part index is the position along the span and **not** the position in a list of
        // the tiles that happen to be on the grid. A span at the rim loses an end, and if the index
        // came from a compacted list the survivors would shift up one and the bridge would be
        // holding its neighbour's heat. Walking the three offsets and skipping the missing one
        // keeps every slot addressed by what it *is*.
        val span = spanParts(grid, i, b.facing)
        for (part in span.indices) {
            val tile = span[part]
            if (tile < 0 || part >= b.joules.size) continue
            out.add(
                Body(
                    slot = BodySlot.Span,
                    at = i,
                    part = part,
                    tiles = intArrayOf(tile),
                    material = b.conduit.material,
                    permeable = true,
                    joules = b.joules[part],
                    capacity = b.conduit.capacityPerTile,
                    conductance = b.conduit.conductance,
                )
            )
        }
    }
    return out
}

/**
 * Total thermal energy in every solid thing aboard — machines, conduit segments, and bridges.
 */
fun solidJoules(
    machines: List<Machine?>,
    conduits: Conduits,
    bridges: List<Machine?>,
): Long {
    var sum = 0L
    for (m in machines) sum += m?.joules?.total ?: 0L
    conduits.all { _, _, s -> sum += s.joules }
    for (b in bridges) sum += b?.joules?.total ?: 0L
    return sum
}

/** The three tiles a bridge occupies: its middle, and one either side along [facing]. */
fun spanTiles(grid: Grid, middle: Int, facing: Direction): IntArray {
    val out = ArrayList<Int>(3)
    for (tile in spanParts(grid, middle, facing)) if (tile >= 0) out.add(tile)
    return out.toIntArray()
}

/**
 * The same three tiles, **positionally**: back, middle, front, with `-1` for an end off the grid.
 *
 * [spanTiles] compacts the missing ends away, which is right for anything that only wants to visit
 * the tiles and wrong for anything that stores something per tile — see the note in [bodiesOf].
 */
fun spanParts(grid: Grid, middle: Int, facing: Direction): IntArray = intArrayOf(
    grid.neighbour(middle, facing.opposite),
    middle,
    grid.neighbour(middle, facing),
)
