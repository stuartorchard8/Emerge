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
 * One solid object with one temperature: a wall, a furnace, a tile of track.
 *
 * ### Why an object and not a tile
 *
 * A tile is not a thing. A tile can hold an iron rail, a copper pipe and a titanium machine at once,
 * because conduits are layers and layers exist so that several things can share the floor. Giving
 * that tile one temperature and one heat capacity — which is what the field this replaces did — is
 * an average over three objects that have different masses, different specific heats and different
 * conductances, and the average is wrong in the direction that matters: it makes the copper as slow
 * as the firebrick and the firebrick as leaky as the copper, so no routing decision the player makes
 * about heat can ever pay off.
 *
 * So the body is the unit. A five-tile smelter is **one** body at one temperature over
 * twenty-five tiles, which is what a furnace is; a run of track is one body per tile, which is what
 * track is, and the run comes back as a run because the tiles are joined.
 *
 * ### Where the energy lives
 *
 * On the object itself — [Machine.joules], [Segment.joules] — and not in a field beside them.
 *
 * That is the same decision, for the same reason, as the atmosphere's heat living inside
 * [AirField]. When energy is the stored quantity and temperature is derived, a parallel array keyed
 * by tile index is desynchronised by exactly one operation: `copy(machines = …)`. Replace the deck
 * and the joules stay behind, now being read against a different capacity — pull a smelter and drop
 * a rail on the tile and the rail is at four thousand kelvin, having inherited a furnace's energy.
 * A save load, a test fixture and every player edit all go through that operation. There is no
 * discipline that survives it; there is only a shape that makes it impossible.
 *
 * Energy on the object costs a field on eight data classes and makes the whole class of bug
 * unreachable, because a body's capacity and a body's energy are properties of the same value.
 *
 * ### Touching
 *
 * [permeable] is what decides who conducts with whom, and it is not quite a physical claim — see
 * [contactsOf]. Impermeable things fill their tile, so they touch across tile faces. Permeable
 * things are fittings threaded through a tile that is otherwise open, so they touch what shares
 * their tile and the air in it, and reach their neighbours only along the network they were drawn
 * into.
 */
class Body(
    val slot: BodySlot,
    /** The index it is stored at — its centre tile for a machine, its own tile for a fitting. */
    val at: Int,
    /** Every tile it is present on. One for a fitting, three for a span, a footprint for a machine. */
    val tiles: IntArray,
    val material: Material,
    /** True for a fitting on a conduit layer: it shares its tile with the air rather than filling it. */
    val permeable: Boolean,
    /** Thermal energy, in the millijoules [Material] documents. */
    val joules: Long,
    /**
     * Millijoules per kelvin — how much of the thing there is, times what its stuff costs to warm.
     *
     * From [MachineKind.thermalTiles] rather than from [tiles], which are clipped to the grid. What
     * a machine is made of does not change when it is built near the rim, and a capacity that did
     * would make the same furnace hold different amounts of heat in different berths.
     */
    val capacity: Long,
    /**
     * Which layer a fitting is on; null for anything that is not one.
     *
     * [slot] and [at] used to identify a body on their own, and stopped doing so the moment a tile
     * could hold a rail *and* a pipe — two fittings, same slot, same index, different objects. This
     * is the third component of the key, and the conduction pass needs it to put the right joules
     * back on the right layer.
     */
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
    val kelvin: Int get() = (joules / capacity).toInt()

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
        out.add(
            Body(
                slot = BodySlot.Deck,
                at = i,
                tiles = coveredTiles(grid, i, m.kind.size).toIntArray(),
                material = m.kind.material,
                permeable = false,
                joules = m.joules,
                capacity = m.kind.material.capacityPerTile * m.kind.thermalTiles,
            )
        )
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
                capacity = conduit.material.capacityPerTile,
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
        // something claims the floor space.
        out.add(
            Body(
                slot = BodySlot.Span,
                at = i,
                tiles = spanTiles(grid, i, b.facing),
                material = b.conduit.material,
                permeable = true,
                joules = b.joules,
                capacity = b.conduit.material.capacityPerTile * MachineKind.Bridge.thermalTiles,
            )
        )
    }
    return out
}

/**
 * Total thermal energy held by every solid thing in the world.
 *
 * Summed straight off the stored values rather than off [bodiesOf], so the ledger costs a walk rather
 * than a build — and so it says the same thing whether or not anything has asked for the bodies.
 */
fun solidJoules(
    machines: List<Machine?>,
    conduits: Conduits,
    bridges: List<Machine?>,
    // Rocks are solids and their energy is in the solid ledger from the tick they appear, long
    // before anything conducts with them -- see [Rock]. Counting it only once contact exists would
    // make the arrival of contact look like energy arriving from nowhere.
    rocks: List<Rock> = emptyList(),
): Long {
    var sum = 0L
    for (m in machines) sum += m?.joules ?: 0L
    conduits.all { _, _, s -> sum += s.joules }
    for (b in bridges) sum += b?.joules ?: 0L
    for (r in rocks) sum += r.joules
    return sum
}

/** The three tiles a bridge occupies: its middle, and one either side along [facing]. */
fun spanTiles(grid: Grid, middle: Int, facing: Direction): IntArray {
    val back = grid.neighbour(middle, facing.opposite)
    val front = grid.neighbour(middle, facing)
    val out = ArrayList<Int>(3)
    if (back >= 0) out.add(back)
    out.add(middle)
    if (front >= 0) out.add(front)
    return out.toIntArray()
}
