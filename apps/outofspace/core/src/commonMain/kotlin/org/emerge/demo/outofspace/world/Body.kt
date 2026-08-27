package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckArray

/**
 * Which list a solid thing is stored in. Enough, with its index, to put a body's heat back where it
 * came from once the conduction pass has worked out where it went.
 */
enum class BodySlot {
    /**
     * [VesselState.deck] — a building's casing, one body per tile of its footprint.
     *
     * Its energy is stored in the layer *per tile* rather than on the object, so this writes back
     * through [Body.tile] and not [Body.anchor]. There used to be a second slot beside it, `Deck`,
     * for the machines that kept their heat on the data class; nothing does any more.
     */
    DeckStore,

    /** [VesselState.conduits] — one segment per tile of one conduit layer. */
    Fitting,


    /**
     * [VesselState.rail] — the lump riding the track, at the tile it is riding on.
     *
     * Cargo is a thing with a temperature, and until this existed it was the one kind of matter
     * aboard that was not. A packet kept whatever energy it was minted with for the whole of its
     * journey: it did not warm the track it sat on, the track did not warm it, and the room it was
     * crossing might as well not have been there. The same lump conducted while it sat in a
     * machine's buffer and stopped the moment it was set down on a belt, which is not a distinction
     * anything physical makes.
     *
     * Writes back through [Body.tile], like the two below — a lump is stored where it is.
     */
    RailCargo,

    /**
     * [VesselState.buffers] — what a machine is holding, at the tile its store stands on.
     *
     * A body like any other, which is the whole point: the heat solver already joins every pair of
     * bodies sharing a tile, so a buffer conducts with the casing around it without a line of
     * contact logic written for it. Writes back through [Body.tile].
     */
    BufferStore,
}

/**
 * One solid object with one temperature (wall, furnace, track tile).
 * Energy stored on the object for machines ([Machine.energy]) and on the layer for fittings
 * ([Conduits.tracks]) — see [TrackLayers] for why a segment's heat moved off the segment.
 * [preventAirflow] says whether air can be in this body's tile, and so where it meets the air: a
 * body that holds air out meets it across its faces, one that does not meets the air it stands in.
 * It no longer decides whether the body has faces at all — every casing conducts across them.
 */
class Body(
    val slot: BodySlot,
    /**
     * The grid tile this body actually **occupies** — where it is in the world.
     *
     * This is what conduction reads: [stepSolidHeat] asks for its face neighbours, whether it is
     * contained, and which tile of air it couples to, and every one of those is a question about
     * where the metal is. For one tile of a five-by-five smelter that is that tile, not the
     * smelter's centre.
     */
    val tile: TileIndex,
    /**
     * The tile the owning object is **stored at** — the key back into `machines` or the deck.
     *
     * ⚠️ **Not the same as [tile], and conflating the two is a live bug rather than a tidy-up.**
     * A machine occupies its whole footprint but is stored only at its origin, so writing a
     * conducted result back through [tile] reaches a *different* machine — or none — for every
     * part except the centre. When the two were one field, `applyBodyHeat` addressed a one-tile
     * machine with a five-by-five machine's part index and threw straight out of bounds.
     *
     * Equal to [tile] for a fitting, which is stored where it sits.
     */
    val anchor: TileIndex,
    /**
     * Which of a machine's tiles this is, as an index into its [org.emerge.demo.outofspace.world.machine.Machine.energy].
     *
     * Zero for a fitting, which is stored as one piece and needs no second half. For a machine or a
     * bridge it is the other half of the address: [anchor] says which object and this says which of
     * its tiles, and both are needed now that one object is several bodies.
     */
    val part: Int = 0,
    val material: Material,
    /** False for a fitting on a conduit layer: it shares its tile with the air rather than filling it. */
    val preventAirflow: Boolean,
    /** Thermal energy, in the millijoules [Material] documents. */
    val energy: Long,
    /** Millijoules/kelvin, per tile — so a footprint clipped by the grid edge cannot change it. */
    val capacity: Long,
    /**
     * What crosses a contact of it, per kelvin per tick.
     *
     * Carried rather than read off [material] because a joint conducts through the metal that is
     * actually there: a hull plate is a few per cent of its tile, and so is its cross-section. It
     * therefore takes the same [DeckMachineKind.fillPermille] its [capacity] does, and the pair of them
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
    val kelvin: Int get() = if (capacity <= 0L) Temperature.SPACE_KELVIN else (energy / capacity).toInt()

    /** Whether this fitting is joined to its neighbour in [dir] — see [Segment.links]. */
    fun linkedTo(dir: Direction): Boolean = links and (1 shl dir.ordinal) != 0
}

/**
 * Every solid thing in the world, in a fixed order: deck, then held matter, then cargo, then
 * fittings.
 *
 * Rebuilt every tick rather than kept, exactly as [StructureMap] and [Occupancy] are, and for the
 * identical reason — a cache with an invalidation rule is a bug waiting for an edit case nobody
 * thought of, and walking three arrays is a rounding error next to the fluid solve.
 *
 * Takes the layers rather than a [VesselState] so that the reducer can call it on its own
 * half-built working copies, which is where the heat step actually runs.
 */
fun bodiesOf(
    grid: Grid,
    conduits: Conduits,
    deck: DeckArray,
    buffers: BufferLayer,
    rail: RailLayer,
): List<Body> {
    val out = ArrayList<Body>(64)
    // The deck layer first, so "deck, then fittings, then spans" still reads in order. One body per
    // tile here too, and for once that needs no index arithmetic: the machine names its own tiles
    // and its energy is already stored against them.
    for (tile in grid.tiles) {
        val m = deck[tile] ?: continue
        for (part in m.tiles(grid)) {
            out.add(
                Body(
                    slot = BodySlot.DeckStore,
                    tile = part,
                    anchor = tile,
                    material = m.kind.material,
                    preventAirflow = m.kind.preventAirflow,
                    energy = deck.stuff.energyAt(part),
                    // From the matter on the tile, not from the kind — so a casing that a reaction
                    // has altered conducts as what it has become. See [StuffLayer.heatCapacityAt].
                    capacity = deck.stuff.heatCapacityAt(part),
                    conductance = m.kind.conductance,
                )
            )
        }
    }
    // What machines are holding. Only stocked stores become bodies: an empty buffer has no thermal
    // mass, nothing to hold a temperature with, and a zero-capacity node in a Jacobi solve is a
    // division waiting to happen. Physically the same statement — nothing there, nothing to warm.
    buffers.stuff.forEachOccupiedTile { tile ->
        val capacity = buffers.stuff.heatCapacityAt(tile)
        if (capacity > 0L) {
            out.add(
                Body(
                    slot = BodySlot.BufferStore,
                    tile = tile,
                    anchor = tile,
                    material = Material.Steel,
                    // Air shares its tile: a buffer is inside a machine, not a wall of it.
                    preventAirflow = false,
                    energy = buffers.stuff.energyAt(tile),
                    capacity = capacity,
                    conductance = BUFFER_CONTACT_CONDUCTANCE,
                )
            )
        }
    }

    // What the belts are carrying. Same shape as a buffer's contents and for the same reasons —
    // including the capacity guard: a tile whose row outlived its lump (a reaction can empty one
    // without releasing it) has nothing to hold a temperature with, and a zero-capacity node in a
    // Jacobi solve is a division waiting to happen.
    rail.stuff.forEachOccupiedTile { tile ->
        val capacity = rail.stuff.heatCapacityAt(tile)
        if (capacity > 0L) {
            out.add(
                Body(
                    slot = BodySlot.RailCargo,
                    tile = tile,
                    anchor = tile,
                    // Nominal: [material] does not feed conduction — [conductance] does — and a heap
                    // of ore is not made of its container any more than a buffer's charge is.
                    material = Material.Steel,
                    preventAirflow = false,
                    energy = rail.stuff.energyAt(tile),
                    capacity = capacity,
                    conductance = CARGO_CONTACT_CONDUCTANCE,
                )
            )
        }
    }

    conduits.all { conduit, tile, s ->
        out.add(
            Body(
                slot = BodySlot.Fitting,
                tile = tile,
                anchor = tile,
                material = conduit.material,
                preventAirflow = false,
                // Both read off the layer rather than off the segment and the kind: the metal in a
                // tile of track is what holds the heat, so the two numbers come from one place and
                // cannot drift apart. [conduitBillOfMaterials] apportions, so the capacity is the
                // kind's to the unit.
                energy = conduits.energyAt(conduit, tile),
                capacity = conduits.heatCapacityAt(conduit, tile),
                conductance = conduit.conductance,
                conduit = conduit,
                links = s.links,
            )
        )
    }
    return out
}

/**
 * Total thermal energy in every solid thing aboard — machines and conduit segments. A bridge's is
 * in the deck layer with every other casing's, since it became a deck machine.
 */
fun solidEnergy(
    conduits: Conduits,
): Long {
    return conduits.totalEnergy
}


/**
 * How fast what a machine holds equalises with the machine holding it.
 *
 * The thermal contact between a buffer and its casing — a pile of ore against the wall of a hopper —
 * and it is a *contact* conductance rather than a material property, which is why it is stated here
 * and not derived from whatever happens to be in the buffer. The contents' own conductivity barely
 * matters: a heap of rubble touches its container at a few points regardless of what the rubble is,
 * and that contact is what limits the flow.
 *
 * **Derivation**: a **400 kg** charge — a few belt-loads, the size a machine actually works on —
 * comes up to its casing's temperature over **a few seconds**. That charge holds about 1.8e7 per
 * kelvin, so a time constant of a couple of hundred ticks wants a conductance around 6e4, which is
 * thirty times [Material.AIR_FILM]. Larger than the air film because solid touching solid carries
 * heat better than a film of still air against a wall; finite because a furnace that heated its
 * charge instantly would leave the player nothing to watch and nothing to plan around.
 *
 * Note what follows from stating it as a conductance rather than as a time: a **heavier charge takes
 * proportionally longer**, because it has more to warm and the same contact to warm it through. That
 * is the physically right behaviour and it is free here.
 *
 * ⚠️ **A dial, not a measurement.** Unlike the densities and specific heats — which are real numbers
 * about real substances — nothing physical pins this. It is set by how long a machine ought to take
 * to heat its contents, and it is the number to reach for when the [Furnace] feels wrong.
 */
val BUFFER_CONTACT_CONDUCTANCE: Long get() = Material.AIR_FILM * 30L

/**
 * How fast a lump on a belt equalises with the track under it and the air around it.
 *
 * The same *kind* of number as [BUFFER_CONTACT_CONDUCTANCE] — a contact, not a material property —
 * and deliberately a third of it. A charge in a hopper is packed against the walls that hold it; a
 * lump on a belt rests on the track and is otherwise standing in the room. Less contact, slower
 * equalisation, and a lump that keeps its heat for a while as it travels rather than taking the
 * temperature of every tile it crosses.
 *
 * What follows from it is the behaviour worth having: a belt-load leaving a furnace **cools on the
 * way**, over a distance rather than instantly, so where a machine is put relative to what feeds it
 * starts to matter. It is also what lets something arrive still hot enough to react — see
 * `chem/Reaction.kt` and `PLAN_ambient_chemistry.md`.
 *
 * ⚠️ **A dial, not a measurement**, exactly as its twin is. Nothing physical pins the contact area
 * between a heap of ore and a belt. Set it by how far a hot lump should travel before it goes cold.
 */
val CARGO_CONTACT_CONDUCTANCE: Long get() = Material.AIR_FILM * 10L
