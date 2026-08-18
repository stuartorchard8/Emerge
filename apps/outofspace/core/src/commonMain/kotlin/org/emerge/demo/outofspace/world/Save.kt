package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species

import org.emerge.demo.outofspace.logistics.FluidPacket
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.machine.Airlock
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Valve
import org.emerge.demo.outofspace.world.machine.Gauge
import org.emerge.demo.outofspace.world.machine.InputKey
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.DirectedDeckMachine
import org.emerge.demo.outofspace.world.machine.Processor
import org.emerge.demo.outofspace.world.machine.Pump
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Smelter
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.ThermalDecomposer
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.TileEnergy
import org.emerge.demo.outofspace.world.machine.Vaporizer
import org.emerge.demo.outofspace.world.machine.Vent
import org.emerge.demo.outofspace.world.machine.WireButton
import org.emerge.demo.outofspace.world.machine.ambientEnergy
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

/** A save that could not be read, with the line that stopped it. */
class SaveError(message: String) : Exception(message)

/**
 * Save/load: the whole world as text. Format is line-oriented, greppable, diffable, and hand-editable.
 *
 * Only writes non-derivable state (ledgers, baselines, body momentum). Structure/occupancy/signals
 * are recomputed from machines each tick. Round-trip test: save/load/run must match never-saved.
 */
object Save {

    /** Bump when a field's meaning changes. An old save is migrated, or refused rather than misread. */
    const val VERSION = 17

    /**
     * The tick rate version 1 saves were written at, and so the number that converts their
     * `rate` field from mass per second into the mass per tick version 2 stores.
     *
     * Frozen here as a literal rather than read from the config, because it is a fact about old
     * files and must not move when the config's tick rate does. Applied in [readMachine].
     */
    const val V1_TICKS_PER_SECOND = 4L

    // ── Writing ───────────────────────────────────────────────────────────────

    fun write(state: VesselState): String {
        val out = StringBuilder()
        // The unit the numbers below are in, stated in the file rather than implied by its
        // version. See [readScale]: a save is only interpretable if you know what one integer meant
        // when it was written, and the version cannot say that once the unit is a knob.
        out.append("outofspace ").append(VERSION)
            .append(' ').append(Budget.MICROGRAMS_PER_UNIT)
            .append(' ').append(Budget.NANOJOULES_PER_UNIT).append('\n')
        out.append("grid ").append(state.grid.width).append(' ').append(state.grid.height).append('\n')
        out.append("gravity ").append(state.gravity.x.raw).append(' ').append(state.gravity.y.raw).append('\n')
        // Position absent = origin.
        out.append("position ").append(state.positionX).append(' ').append(state.positionY).append('\n')
        // Felt gravity baseline; a world reloaded without it coasts for one tick under plating alone.
        out.append("thrust ").append(state.netImpulseX).append(' ').append(state.netImpulseY).append('\n')
        out.append("tick ").append(state.tick).append('\n')
        out.append("extracted ").append(state.extractedMass).append('\n')
        out.append("vented ").append(state.ventedMass).append('\n')
        out.append("generated ").append(state.generatedEnergy).append('\n')
        out.append("radiated ").append(state.radiatedEnergy).append('\n')
        out.append("airvented ").append(state.airVentedMass).append('\n')
        out.append("baselinejoules ").append(state.baselineEnergy).append('\n')
        out.append("inserted ").append(state.insertedEnergy).append('\n')
        out.append("built ").append(state.builtMass).append('\n')
        // The solids the world started with. Absent reads as zero, which is what every world
        // written before a ship had to build its own track began with.
        out.append("baselinecargo ").append(state.baselineCargoMass).append('\n')
        // Absent reads as creative, which is what every world written before the switch existed was
        // — and what every world still is until a ghost can finish building itself.
        out.append("creative ").append(if (state.creative) 1 else 0).append('\n')
        out.append("acquired ").append(state.acquiredEnergy).append('\n')
        out.append("solidtoair ").append(state.solidToAirEnergy).append('\n')
        out.append("baselineair ").append(state.baselineAirMass).append('\n')
        // Bodies: free mass, no tracking beyond the list itself.

        // Body momentum AND position both in the world frame since v15 — see [Pose]. Shape as a
        // 0/1 run for hand-editing.
        for (b in state.bodies) {
            out.append("body ").append(b.width).append(' ').append(b.height)
                .append(' ').append(b.positionX).append(' ').append(b.positionY)
                .append(' ').append(b.impulseX).append(' ').append(b.impulseY)
                .append(' ').append(writeTileEnergy(b.energy))
                .append(' ').append(writeMixture(b.oreComposition!!))
                .append(' ')
            for (c in b.cells) out.append(if (c) '1' else '0')
            // Orientation last, after the shape, so that every field a v15 file had keeps the index
            // it had — a v15 save loads into this build unchanged and simply arrives unturned, which
            // is what it meant.
            out.append(' ').append(b.ang.raw).append(' ').append(b.angImpulse)
            out.append("   # ").append(b.filled).append(" cells, ").append(b.mass).append("g\n")
        }

        // Tiles are written as indices because that is what the world is indexed by, but an index is
        // unreadable to a person and the whole point of the format is that a person can read it. So
        // every placed thing carries its coordinates in a comment, and track spells its links out —
        // `links=5` says nothing, `R-L-` says the run goes left to right through this tile.
        for (tile in state.grid.tiles) {
            val m = state.deck[tile] ?: continue
            out.append("deckmachine ").append(tile.index).append(' ').append(writeDeckMachine(m, state.grid, state.buffers))
            out.append("   # ").append(where(state.grid, tile)).append('\n')
        }
        // One line per segment per layer, keyed `conduit` rather than `rail` since version 6 — the
        // record always named its own network, but while there was one list per tile the keyword
        // could pretend otherwise. A version 5 file writes `rail 42 PIPE ...` and means it.
        state.conduits.all { _, tile, r ->
            out.append("conduit ").append(tile.index).append(' ').append(writeSegment(r, tile, state.rail, state.conduits))
            out.append("   # ").append(where(state.grid, tile)).append(' ').append(linkLetters(r)).append('\n')
        }
        for (tile in state.grid.tiles) {
            val cursor = state.diverters.forkCursors[tile] ?: 0
            if (cursor != 0) out.append("diverter ").append(tile.index).append(' ').append(cursor).append('\n')
        }
        // Which feeder a merge takes from next. A separate line from `diverter` because a tile can
        // be both, and an older save simply has none of these.
        for (tile in state.grid.tiles) {
            val cursor = state.diverters.mergeCursors[tile] ?: 0
            if (cursor != 0) out.append("merge ").append(tile.index).append(' ').append(cursor).append('\n')
        }

        // Solid heat lives on machines/segments (their `k=` field), not a separate per-tile block.
        // Air per tile: mixture is wordy but readable/editable.
        for (tile in state.grid.tiles) {
            val mix = state.air.mixtureAt(tile)
            if (mix.isEmpty) continue
            out.append("air ").append(tile.index).append(' ').append(writeMixture(mix)).append('\n')
        }

        // Packed sparsely like heat. Version 3 and earlier stored per-tile heat; absent loads ambient.
        writeSparse(out, "airheat", state.air.copyEnergy().data)
        writeDeckHeat(out, state.deck.stuff)
        writeDeckStuff(out, state.deck)
        writeTrackStuff(out, state.conduits)
        out.append("airventedheat ").append(state.airVentedEnergy).append('\n')
        // The debug bellows' admission. Appended rather than versioned, like the impulse line:
        // absent reads as zero, which is exactly what a world that never cheated has.
        out.append("airinjected ").append(state.injectedAirMass)
            .append(' ').append(state.injectedAirEnergy).append('\n')
        out.append("baselineairheat ").append(state.baselineAirEnergy).append('\n')

        // Packed like heat. Momentum saved because reloading without it resumes becalmed.
        // Pipes: same format, empty network = zero cost.
        for (tile in state.grid.tiles) {
            val mix = state.pipeAir.mixtureAt(tile)
            if (mix.isEmpty) continue
            out.append("pipeair ").append(tile.index).append(' ').append(writeMixture(mix)).append('\n')
        }
        writeSparse(out, "pipeairheat", state.pipeAir.copyEnergy().data)
        writeSparse(out, "pipemomx", state.pipeMomentum.copyX())
        writeSparse(out, "pipemomy", state.pipeMomentum.copyY())

        writeSparse(out, "momx", state.momentum.copyX())
        writeSparse(out, "momy", state.momentum.copyY())

        // Twelve impulse values (ledger grew). Appended, not versioned: absent reads as zero.
        out.append("impulse ").append(state.vesselImpulseX).append(' ').append(state.vesselImpulseY)
            .append(' ').append(state.exhaustMomentumX).append(' ').append(state.exhaustMomentumY)
            .append(' ').append(state.undeliveredImpulseX).append(' ').append(state.undeliveredImpulseY)
            .append(' ').append(state.debugImpulseX).append(' ').append(state.debugImpulseY)
            .append(' ').append(state.bodyImpulseX).append(' ').append(state.bodyImpulseY)
            .append(' ').append(state.frameTurnImpulseX).append(' ').append(state.frameTurnImpulseY)
            .append('\n')
        // Rotation. A new keyword rather than more fields on `thrust`, because `thrust` means the
        // linear pair and a reader that had to count tokens to find out otherwise is a reader that
        // will eventually miscount. Appended, not versioned: absent reads as zero, which is a ship
        // pointing the way the grid is drawn and not turning — exactly what every save before this
        // was. Only the angular momentum carries the mass unit, so only it is rescaled on the way in.
        out.append("rotation ").append(state.ang.raw)
            .append(' ').append(state.angImpulse).append(' ').append(state.netTorque)
            .append('\n')
        return out.toString()
    }

    private const val HEAT_PER_LINE = 12

    /** Index=value pairs, several to a line, skipping zeros. The idiom the heat field already uses. */
    /**
     * The deck's energy, in the same `deckheat tile=value` lines [writeSparse] emits — the layer is
     * row-allocated now, so there is no tile-indexed array to hand it, but the **format is
     * unchanged** and old saves load without a version bump.
     *
     * Tiles are emitted in ascending order rather than in row order. Row order is an artefact of the
     * order things were built and demolished in, and letting it reach the file would make two
     * identical worlds save as different text.
     */
    private fun writeDeckHeat(out: StringBuilder, deck: StuffLayer) {
        val tiles = ArrayList<Int>(deck.occupiedTiles)
        deck.forEachOccupiedTile { if (deck.energyAt(it) != 0L) tiles.add(it.index) }
        tiles.sort()
        var onLine = 0
        for (tile in tiles) {
            if (onLine == 0) out.append("deckheat")
            out.append(' ').append(tile).append('=').append(deck.energyAt(TileIndex(tile)))
            if (++onLine == HEAT_PER_LINE) { out.append('\n'); onLine = 0 }
        }
        if (onLine != 0) out.append('\n')
    }

    /**
     * The deck's **matter**, one `deckstuff tile <mixture>` line per tile whose casing is no longer
     * what it was built as.
     *
     * ### Why only the tiles that differ
     *
     * A casing starts as [tileBillOfMaterials] of its kind, and until something reacts with it that
     * is *all* it ever is — a hull tile is a hull tile. Writing the composition of every deck tile
     * would put thousands of identical lines in the file, one per plate, and none of them would say
     * anything the machine record does not already say. So absence means "made of what its kind is
     * made of", which is exactly what every file written before this line existed meant, and no
     * version bump is needed to read one.
     *
     * The same reasoning as the conduit `k=` field, and the same trap: the comparison must be
     * against **this tile's** bill, not a per-kind constant, because [Mixture.scaledTo] apportions
     * and a multi-species material lands a unit or two off the round figure the table states.
     */
    private fun writeDeckStuff(out: StringBuilder, deck: DeckArray) {
        for (tile in deck.grid.tiles) {
            val m = deck[tile] ?: continue
            val bill = tileBillOfMaterials(m.kind)
            for (part in m.tiles(deck.grid)) {
                if (Species.ALL.all { deck.stuff[part, it] == bill[it] }) continue
                out.append("deckstuff ").append(part.index).append(' ')
                    .append(writeMixture(deck.stuff.mixtureAt(part))).append('\n')
            }
        }
    }

    /**
     * What each length of conduit is **made of**, one `trackstuff <conduit> <tile> <mixture>` line
     * per tile whose metal is no longer its kind's bill of materials.
     *
     * The twin of [writeDeckStuff], written for the same reason and read with the same rule: absence
     * means "made of what its kind is made of", which is what every file written before this line
     * existed meant, so no version bump is needed to read one.
     *
     * ⚠️ It stops being an optimisation and becomes **required** the moment a segment can hold less
     * than its bill. A ghost is exactly that — track with a representation and no mass — and before
     * this line a ghost saved as a finished rail, because the loader re-derived every segment's
     * metal from its kind. See `apps/outofspace/PLAN_self_building_rails.md`.
     */
    private fun writeTrackStuff(out: StringBuilder, conduits: Conduits) {
        for (conduit in Conduit.entries) {
            val stuff = conduits.tracks[conduit]
            val bill = conduitBillOfMaterials(conduit)
            for (tile in 0 until conduits.tileCount) {
                val t = TileIndex(tile)
                if (conduits.at(conduit, t) == null) continue
                if (Species.ALL.all { stuff[t, it] == bill[it] }) continue
                out.append("trackstuff ").append(conduit.name).append(' ').append(tile).append(' ')
                    .append(writeMixture(stuff.mixtureAt(t))).append('\n')
            }
        }
    }

    private fun writeSparse(out: StringBuilder, tag: String, values: LongArray) {
        var onLine = 0
        for (i in values.indices) {
            if (values[i] == 0L) continue
            if (onLine == 0) out.append(tag)
            out.append(' ').append(i).append('=').append(values[i])
            if (++onLine == HEAT_PER_LINE) { out.append('\n'); onLine = 0 }
        }
        if (onLine != 0) out.append('\n')
    }

    private fun where(grid: Grid, tile: TileIndex): String = "(${grid.xOf(tile)}, ${grid.yOf(tile)})"

    /** A segment's links as `RDLU`, with a dash for each direction it is not joined to. */
    private fun linkLetters(s: Segment): String =
        Direction.ALL.joinToString("") { if (s.linkedTo(it)) it.name.take(1) else "-" }

    /**
     * The field name a machine's [role] store has always had in the file.
     *
     * The roles were named after the fact and the keys were not, so two machines spell them their
     * own way: an extractor's jaws are `in` and its ground ore is `buffer`, and a storage's one
     * pooled store is `stored`. Kept exactly as they were, so the storage migration does not
     * invalidate a single save.
     */
    private fun storeKey(m: DeckMachine, role: BufferRole): String = when {
        m is Storage -> "stored"
        m is Extractor -> if (role == BufferRole.Inside) "in" else "buffer"
        // A bridge's middle slot has always been `span`, and it stays `span`: the three slots became
        // three role tiles without the file needing to know.
        m is Bridge && role == BufferRole.Inside -> "span"
        role == BufferRole.Input -> "in"
        role == BufferRole.Inside -> "inside"
        role == BufferRole.Product -> "out"
        else -> "waste"
    }

    private fun writeDeckMachine(m: DeckMachine, grid: Grid, buffers: BufferLayer): String {
        val f = StringBuilder(m.kind.name)
        fun put(key: String, value: String?) {
            if (value != null) f.append(' ').append(key).append('=').append(value)
        }
        if (m is DirectedDeckMachine) put("facing", m.facing.name)
        when (m) {
            // An airlock is its wiring, and the common code around this writes that. A bridge is
            // its facing and its three slots, and both of those are written by the common code too.
            // A valve is its position and nothing else. An airlock is its wiring, and a bridge its
            // facing and its three slots — all of those are written by the common code around this.
            is Hull, is Airlock, is Bridge, is Valve -> {}
            // A gauge's reading persists after the packet has gone, so it is state, not decoration.
            // The field names are the ones the `conduit` record used while a gauge was a segment.
            is Gauge -> {
                m.lastForm?.let { put("lastform", it.name) }
                m.lastDominant?.let { put("lastspecies", it.name) }
                if (m.lastPurity != 0) put("lastpurity", m.lastPurity.toString())
                if (m.lastMass != 0L) put("lastmass", m.lastMass.toString())
            }
            is Vent -> put("vented", m.ventedMass.toString())
            // A warehouse holds nothing itself: its contents are a tile of the buffer layer, and
            // the store loop below writes them under the key the record has always used.
            is Processor -> {
                put("carry", m.carry.toString())
                put("progress", m.progress.toString())
                put("eff", m.efficiencyPermille.toString())
            }
            is ThermalDecomposer -> {
                put("carry", m.carry.toString())
                put("progress", m.progress.toString())
                put("temp", m.setTemperature.toString())
            }
            is Smelter -> {
                put("carry", m.carry.toString())
                put("rate", m.massPerTick.toString())
            }
            is Extractor -> {
                put("carry", m.carry.toString())
                put("rate", m.massPerTick.toString())
            }
            is Storage -> {}
            // A sensor is its facing and its wiring, both written by the common code around this.
            is Sensor -> {}
            // A button is its key and its wiring; the common code writes the second.
            is WireButton -> put("key", m.key.name)
            // A pump holds nothing: what it moves is in the two air fields. Facing and wiring are
            // written by the common code around this.
            is Pump -> {}
            is Vaporizer -> {
                put("carry", m.carry.toString())
                put("rate", m.massPerTick.toString())
            }
            // The exhaust path is derived from the hull every tick and so is not state — see
            // [exhaustPath]; the propellant is a store, written by the role loop below.
            is Thruster -> {
                put("carry", m.carry.toString())
                put("rate", m.massPerTick.toString())
            }
        }
        // The same store loop the machine record has, and it is not optional: a deck machine's
        // buffers are on the same layer under the same keys, and leaving it out wrote a warehouse
        // back empty while the record itself looked perfectly well-formed.
        for (role in bufferRolesOf(m)) {
            val store = bufferTile(grid, m, m.center, role) ?: continue
            put(storeKey(m, role), buffers.resourceAt(store)?.let { writeResource(it) })
        }
        // Omitted when a machine is wired the way a freshly placed one is, which is almost all of
        // them — the file should show the wiring somebody actually did.
        if (m.wiring != Wiring.RUNNING) put("wire", writeWiring(m.wiring))
        return f.toString()
    }

    /**
     * A machine's per-tile heat, comma-separated in footprint order. Version 12 and later.
     *
     * One field rather than one per tile because a machine's tiles are not addressable from the
     * file: the footprint is derived from the kind and the centre, so the *order* is the address.
     */
    private fun writeTileEnergy(j: TileEnergy): String =
        (0 until j.size).joinToString(",") { j[it].toString() }

    /**
     * Reads that back, and migrates a file that predates it.
     *
     * ⚠️ Up to version 11 the `k=` field was a single number: everything the machine held, because a
     * machine had one temperature. Spreading it evenly is the only faithful reading — an isothermal
     * machine *is* one whose tiles are all at the same temperature, so the old state is not being
     * approximated, it is being written out in full. What the file cannot tell us is a gradient it
     * was never able to express, and a saved smelter will now develop one as it runs.
     *
     * The remainder is not dropped: [TileMixture.plusSpread] hands it to the first tiles, so a
     * reloaded machine holds exactly what it held before, to the joule. Anything else would make
     * save/load a slow leak, and the energy ledger would eventually notice.
     */
    /**
     * A free body's per-cell energy, or a pre-v14 single figure spread across [cells].
     *
     * Spread rather than given to one cell, because that is what the single figure meant: a body has
     * never conducted with anything, including itself, so its energy was always uniform and the only
     * question is how many numbers it took to say so.
     */
    private fun bodyEnergy(
        field: String,
        cells: Int,
        scale: Rescale,
        fail: (String) -> Nothing,
    ): TileEnergy {
        if (',' !in field) {
            val total = scale.of(field.toLongOrNull() ?: fail("bad body energy '$field'"))
            return TileEnergy.uniform(cells, 0L).plusEnergySpread(total)
        }
        val parts = field.split(',')
        if (parts.size != cells) fail("a $cells-cell body has ${parts.size} per-cell energy")
        return TileEnergy.of(LongArray(cells) { scale.of(parts[it].toLongOrNull() ?: fail("bad body energy '$field'")) })
    }

    private fun writeSegment(s: Segment, tile: TileIndex, rail: RailLayer, conduits: Conduits): String {
        val f = StringBuilder(s.conduit.name)
        f.append(" links=").append(s.links)
        // The load moved to [RailLayer]; the record keeps the field name it always had, so a file
        // written before that change still loads its belts full.
        if (s.conduit == Conduit.Rail) rail.packetAt(tile)?.let { f.append(" held=").append(writePacket(it)) }
        // Read off the layer, which is where a segment's heat lives now. The record is unchanged:
        // still `k=`, still omitted when the tile is at the temperature a freshly laid one would be,
        // so a file written before the migration and one written after are the same file.
        // Absent reads as "not marked", which is what every file written before deconstruction
        // existed meant, so no version bump.
        if (s.deconstructing) f.append(" scrapping=1")
        val energy = conduits.energyAt(s.conduit, tile)
        // Compared against what *this* tile would hold if freshly laid, not against
        // [Conduit.ambientPerTile]. The bill of materials apportions, so a multi-species conduit's
        // capacity can differ from the kind's by a part in a million — and against the kind's figure
        // every single tile would then look non-ambient and be written out.
        if (energy != conduits.heatCapacityAt(s.conduit, tile) * Temperature.AMBIENT_KELVIN) {
            f.append(" k=").append(energy)
        }
        return f.toString()
    }

    private fun writeWiring(w: Wiring): String =
        Action.entries.joinToString(";") { action ->
            action.name + ":" + w.triggers(action).joinToString(",") { "${it.source.name}@${it.weightPermille}" }
        }

    /**
     * A mixture's species and masses, and — only where asked for — the heat in it.
     *
     * ⚠️ [withEnergy] is **not** a default, and must not become one. Most mixtures in the file have
     * their energy written on a line of their own: the air's is in `airheat`, a casing's in
     * `deckheat`, a length of conduit's in its `k=` field. Emitting it inline as well would have the
     * reader add the same joules twice.
     *
     * A [Resource] is the case with nowhere else to put it, which is why it is the one caller — see
     * [writeResource].
     */
    private fun writeMixture(m: Mixture, withEnergy: Boolean = false): String {
        if (m.isEmpty) return "-"
        val species = Species.ALL.filter { m[it] > 0L }.joinToString(",") { "${it.name}=${m[it]}" }
        if (!withEnergy || m.energy <= 0L) return species
        return "$species,energy=${m.energy}"
    }

    /**
     * A resource, **with its heat**.
     *
     * The reader has always understood `energy=`; the writer never emitted it, so every gram of ore
     * in a tank or riding on a belt arrived back from a save at whatever temperature the reader
     * defaulted to. Invisible while everything a fixture stored happened to be at ambient, and it
     * surfaced the moment the starting vessel was given a stock of iron that had a temperature.
     */
    private fun writeResource(r: Resource): String = "${r.form.name}/${writeMixture(r.mixture, withEnergy = true)}"

    private fun writePacket(p: Packet): String = when (p) {
        is SolidPacket -> "S:" + writeResource(p.resource)
        is FluidPacket -> "F:" + writeMixture(p.contents)
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    /**
     * What a mass-, energy- or momentum-dimensioned number in the file must be multiplied by.
     *
     * ### Why the file states its units instead of the version implying them
     *
     * A save is a pile of bare integers, and an integer only means something once you know what one
     * of them was worth when it was written. Keying that to the version number would work exactly
     * once: both units are knobs, and the moment either moves twice there is no way to recover which
     * of two units a version-14 file was written in. Stating them in the file makes every save
     * self-describing and makes any future rescale free of save work entirely.
     *
     * ### Why there are now two factors and not one
     *
     * Version 13 needed only one, because `Budget.MILLIJOULE == Budget.GRAM` held the energy unit
     * equal to the mass unit and one number rescaled the lot. **That lock is gone** — it is what
     * stopped the rescale at step 8, since a millionfold finer gram made a millionfold finer joule
     * and a rock's energy stopped fitting a `Long`. Its own KDoc named this function as the first
     * thing that would have to grow a second factor, and here it is.
     *
     * Momentum stays on the mass factor: it is `gram·tiles/tick`, a mass times a dimensionless
     * velocity.
     *
     * A missing unit means the pre-knob default — one gram and one millijoule per integer, which is
     * what every file before version 13 (mass) and 14 (energy) was written in. Both have only ever
     * gone in the widening direction, so the factor is always a whole number.
     */
    private fun readScale(stated: String?, line: Int, what: String, mine: Long): Rescale {
        val fileUnit = if (stated == null) 1_000_000L else stated.toLongOrNull()
            ?: throw SaveError("line $line: unreadable $what unit '$stated'")
        if (fileUnit <= 0L) throw SaveError("line $line: $what unit must be positive, got $fileUnit")
        return Rescale(from = fileUnit, to = mine)
    }

    /**
     * Carries a file's unit into this build's, in whichever direction that turns out to be.
     *
     * ⚠️ It used to be a plain multiplier with a `require` that the file's unit divided evenly by
     * this build's, on the stated grounds that a unit "only ever goes down". That was true of mass
     * and **is not true in general** — v14 makes the energy unit ten times *coarser*, and the guard
     * promptly refused every save the game had ever written, including its own from the line before.
     * A rule derived from one dimension's history, applied to a dimension that had none.
     *
     * Both directions are legitimate and they are not symmetrical. Widening (a coarser file into a
     * finer build) is exact. Narrowing (a finer file into a coarser build) rounds, and that rounding
     * is fine precisely because what it discards is **below what this build can represent at all** —
     * it is the same loss any store into a coarser field takes, not the silent halving of somebody's
     * cargo the old guard was written to prevent.
     *
     * Through [scaledRatio] rather than a bare `v * from / to`, because `from` is a whole unit
     * conversion — up to a million — and the values are the largest quantities in the game.
     */
    private class Rescale(private val from: Long, private val to: Long) {
        fun of(value: Long): Long = if (from == to) value else scaledRatio(value, to, from)

        companion object {
            /** For a field that is a *proportion* and has no unit to carry — a rock's composition. */
            val NONE = Rescale(1L, 1L)
        }
    }

    fun read(text: String): VesselState {
        val lines = text.lineSequence().withIndex()
            .map { (n, raw) -> n + 1 to raw.substringBefore('#').trim() }
            .filter { it.second.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) throw SaveError("empty save")

        var at = 0
        fun next(): Pair<Int, List<String>> {
            val (n, line) = lines[at++]
            return n to line.split(' ').filter { it.isNotEmpty() }
        }

        val (headerLine, header) = next()
        if (header.size !in 2..4 || header[0] != "outofspace") {
            throw SaveError("line $headerLine: not an Out of Space save")
        }
        val version = header[1].toIntOrNull()
            ?: throw SaveError("line $headerLine: unreadable version '${header[1]}'")
        if (version !in 1..VERSION) {
            throw SaveError("save is version $version, this build reads version 1..$VERSION")
        }
        val scale = readScale(header.getOrNull(2), headerLine, "mass", Budget.MICROGRAMS_PER_UNIT)
        val energyScale =
            readScale(header.getOrNull(3), headerLine, "energy", Budget.NANOJOULES_PER_UNIT)

        val (gridLine, gridTokens) = next()
        if (gridTokens.size != 3 || gridTokens[0] != "grid") throw SaveError("line $gridLine: expected a grid")
        val grid = Grid(
            gridTokens[1].toIntOrNull() ?: throw SaveError("line $gridLine: bad width"),
            gridTokens[2].toIntOrNull() ?: throw SaveError("line $gridLine: bad height"),
        )
        if (grid.size <= 0) throw SaveError("line $gridLine: grid has no tiles")

        val deck = DeckArray(grid)
        val buffers = BufferLayer.empty(grid.size)
        val rail = RailLayer.empty(grid.size)
        val layers = Array(Conduit.entries.size) { arrayOfNulls<Segment>(grid.size) }
        /** `k=` readings held aside by (conduit ordinal, tile index) — see where they are applied. */
        var creative = true
        var built = 0L
        var baselineCargo: Long? = null
        val segmentEnergy = HashMap<Pair<Int, Int>, Long>()
        // Held aside for the same reason [segmentEnergy] is: the layers do not exist until every
        // segment has been read, and this line *replaces* the metal [Conduits.of] lays.
        val trackStuff = HashMap<Pair<Int, Int>, Mixture>()
        val diverters = HashMap<TileIndex, Int>()
        val merges = HashMap<TileIndex, Int>()
        val airMass = MassArray(grid.size)
        val airEnergy = EnergyArray(grid.size)
        val edges = EdgeGrid(grid)
        val momentumX = LongArray(edges.xEdgeCount)
        val momentumY = LongArray(edges.yEdgeCount)
        val pipeMass = MassArray(grid.size)
        val pipeEnergy = EnergyArray(grid.size)
        val pipeMomentumX = LongArray(edges.xEdgeCount)
        val pipeMomentumY = LongArray(edges.yEdgeCount)
        var impulseX = 0L
        var impulseY = 0L
        var exhaustX = 0L
        var exhaustY = 0L
        var undeliveredX = 0L
        var undeliveredY = 0L
        var debugX = 0L
        var debugY = 0L
        var frameTurnX = 0L
        var frameTurnY = 0L
        var bodyImpulseX = 0L
        var bodyImpulseY = 0L
        val bodies = ArrayList<RigidBody>()

        // Absent = freefall. Older saves store one-g explicitly.
        var gravity = VesselState.FREEFALL
        var positionX = 0L
        var positionY = 0L
        var ang = Coord(0)
        var angImpulse = 0L
        var netTorque = 0L
        var netImpulseX = 0L
        var netImpulseY = 0L
        var tick = 0L
        var extracted = 0L
        var vented = 0L
        var generated = 0L
        var radiated = 0L
        var airVented = 0L
        var airVentedEnergy = 0L
        var injectedAirMass = 0L
        var injectedAirEnergy = 0L
        var baselineAirEnergy: Long? = null
        var baselineEnergy: Long? = null
        var inserted = 0L
        var acquired = 0L
        var solidToAir = 0L
        var baselineAir: Long? = null

        while (at < lines.size) {
            val (n, tokens) = next()
            fun fail(why: String): Nothing = throw SaveError("line $n: $why")
            fun long(i: Int): Long = tokens.getOrNull(i)?.toLongOrNull() ?: fail("expected a number")
            // Every mass-, energy- or momentum-dimensioned field reads through this one and every
            // dimensionless field reads through `long`. Which of the two a line uses IS the
            // statement of what that field means — see [readScale].
            fun scaled(i: Int): Long = scale.of(long(i))

            /**
             * The energy-dimensioned twin. Named separately at every site rather than defaulted,
             * because which dimension a ledger is in is exactly the thing that is easy to get wrong
             * and impossible to see in a diff — the field names are the only evidence, so the call
             * has to name it too.
             */
            fun energy(i: Int): Long = energyScale.of(long(i))
            fun tile(i: Int): TileIndex {
                val t = tokens.getOrNull(i)?.toIntOrNull() ?: fail("expected a tile index")
                if (t !in 0 until grid.size) fail("tile $t is outside a ${grid.width}x${grid.height} grid")
                return TileIndex(t)
            }

            when (tokens[0]) {
                "gravity" -> gravity = Frac2(Frac(long(1)), Frac(long(2)))
                "position" -> { positionX = long(1); positionY = long(2) }
                "thrust" -> { netImpulseX = scaled(1); netImpulseY = scaled(2) }
                "rotation" -> {
                    ang = Coord(tokens.getOrNull(1)?.toIntOrNull() ?: fail("expected an angle"))
                    angImpulse = scaled(2); netTorque = scaled(3)
                }
                "tick" -> tick = long(1)
                // `mined` is v9's name for it: the same quantity, counted at the miner instead.
                "mined", "extracted" -> extracted = scaled(1)
                "vented" -> vented = scaled(1)
                "generated" -> generated = energy(1)
                "radiated" -> radiated = energy(1)
                "airvented" -> airVented = scaled(1)
                "airventedheat" -> airVentedEnergy = energy(1)
                "airinjected" -> { injectedAirMass = scaled(1); injectedAirEnergy = energy(2) }
                "baselineairheat" -> baselineAirEnergy = energy(1)
                "baselinejoules" -> baselineEnergy = energy(1)
                "inserted" -> inserted = energy(1)
                "acquired" -> acquired = energy(1)
                // Old spelling: the energy the player inserted, now [insertedEnergy].
                "construction" -> inserted = energy(1)
                "solidtoair" -> solidToAir = energy(1)
                "baselineair" -> baselineAir = scaled(1)

                // Every kind that was ever written under this keyword now lives on the deck, so
                // `machine` is purely a legacy spelling of `deckmachine` — see [readMachine], which
                // is what is left of the machine list.
                "machine" -> {
                    val t = tile(1)
                    if (deck[t] != null) fail("two machines at tile $t")
                    val dm = readMachine(tokens.drop(2), version, t, grid, buffers, scale, energyScale, ::fail)
                    deck += dm
                    // Its heat rode that record in `k=`, and `deckheat` was not written for it, so
                    // without this the machine comes back at ambient and the thermal ledger reports
                    // the difference on the first tick after the load.
                    readMigratedDeckHeat(tokens.drop(2), energyScale, ::fail)?.let { total ->
                        val tiles = dm.tiles(grid)
                        val each = total / tiles.size
                        for (tile in tiles) deck.stuff.setEnergy(tile, each)
                        deck.stuff.addEnergy(dm.center, total % tiles.size)
                    }
                }
                "deckmachine" -> {
                    val t = tile(1)
                    if (deck[t] != null) fail("two machines at tile $t")
                    deck += readDeckMachine(tokens.drop(2), version, t, grid, buffers, scale, energyScale, ::fail)
                }
                // `rail` = v5 spelling; record carries conduit name, so old files land on the right layer.
                "rail", "conduit" -> {
                    val t = tile(1)
                    val segment = readSegment(tokens.drop(2), t, rail, scale, energyScale, ::fail)
                    // The heat is the layer's, and the layer does not exist until every segment has
                    // been read — so it is held aside by (conduit, tile) and applied below, once
                    // [Conduits.of] has laid the metal that carries it.
                    readSegmentEnergy(tokens.drop(2), energyScale, ::fail)
                        ?.let { segmentEnergy[segment.conduit.ordinal to t.index] = it }
                    val layer = layers[segment.conduit.ordinal]
                    // Per layer, not per tile. Two segments on one tile is what layers are *for*;
                    // two of the same conduit on one tile is still a corrupt file.
                    if (layer[t.index] != null) fail("two ${segment.conduit.label} segments at tile $t")
                    layer[t.index] = segment
                    // A gauge or a valve the record was carrying as a flag. It becomes a building
                    // standing on that tile — unless something is already standing there, which a
                    // hand-edited file can say and the game cannot represent.
                    readMigratedFitting(tokens.drop(2), t, scale, ::fail)?.let { fitting ->
                        if (deck[t] != null) fail("a fitting and a machine both stand at tile $t")
                        deck += fitting
                    }
                }
                // A `bridge` record is how every file up to this one spelled it, back when a bridge
                // was its own list. It is a deck machine now, so the keyword is a legacy spelling of
                // `deckmachine` and lands in the same place — the same routing a `machine` record
                // for a vent gets. The slot keys (`in`, `span`, `out`) did not change; see
                // [storeKey].
                "bridge" -> {
                    val t = tile(1)
                    if (deck[t] != null) fail("two machines at tile $t")
                    deck += readDeckMachine(tokens.drop(2), version, t, grid, buffers, scale, energyScale, ::fail)
                }
                "diverter" -> diverters[tile(1)] = long(2).toInt()
                "merge" -> merges[tile(1)] = long(2).toInt()
                // V4 stored heat per tile — averaged, which is why it was replaced. Parse for well-formedness, drop.
                "heat" -> for (i in 1 until tokens.size) {
                    val eq = tokens[i].indexOf('=')
                    if (eq < 0) fail("expected tile=energy, got '${tokens[i]}'")
                    val t = tokens[i].substring(0, eq).toIntOrNull() ?: fail("bad tile in '${tokens[i]}'")
                    if (t !in 0 until grid.size) fail("tile $t is outside the grid")
                    tokens[i].substring(eq + 1).toLongOrNull() ?: fail("bad energy in '${tokens[i]}'")
                }
                "airheat" -> readSparse(tokens, airEnergy.data, energyScale, ::fail)
                "deckheat" -> readDeckHeat(tokens, deck.stuff, energyScale, ::fail)
                "deckstuff" -> {
                    val t = tile(1)
                    val mix = readMixture(tokens.getOrNull(2) ?: fail("expected a mixture"), scale, ::fail)
                    // The machine must already be down: this line *replaces* the bill of materials
                    // `+=` laid, and writing it onto a bare tile would claim a row the deck has no
                    // machine for. The writer emits it after the machine records for that reason.
                    if (!deck.stuff.occupies(t)) fail("deckstuff at $t, where no deck machine stands")
                    for (s in Species.ALL) deck.stuff[t, s] = mix[s]
                }
                "trackstuff" -> {
                    val name = tokens.getOrNull(1) ?: fail("expected a conduit")
                    val conduit = Conduit.entries.firstOrNull { it.name == name }
                        ?: fail("unknown conduit '$name'")
                    val t = tile(2)
                    val mix = readMixture(tokens.getOrNull(3) ?: fail("expected a mixture"), scale, ::fail)
                    trackStuff[conduit.ordinal to t.index] = mix
                }
                "pipeair" -> {
                    val t = tile(1)
                    val mix = readMixture(tokens.getOrNull(2) ?: fail("expected a mixture"), scale, ::fail)
                    for (s in Species.ALL) pipeMass[MassIndex(t, s)] = mix[s]
                }
                "pipeairheat" -> readSparse(tokens, pipeEnergy.data, energyScale, ::fail)
                "pipemomx" -> readSparse(tokens, pipeMomentumX, scale, ::fail)
                "pipemomy" -> readSparse(tokens, pipeMomentumY, scale, ::fail)
                "momx" -> readSparse(tokens, momentumX, scale, ::fail)
                "momy" -> readSparse(tokens, momentumY, scale, ::fail)
                "creative" -> creative = tokens.getOrNull(1) != "0"
                // Grams that stopped being cargo and became fabric. Absent reads as zero, which is
                // what a world where nothing has ever been built out of its own stores has.
                "baselinecargo" -> baselineCargo = scale.of(tokens.getOrNull(1)?.toLongOrNull() ?: fail("unreadable baseline cargo"))
                "built" -> built = scale.of(tokens.getOrNull(1)?.toLongOrNull() ?: fail("unreadable built mass"))
                "captured" -> {} // consumed, ignored — legacy field
                "baselinebody", "baselinerock" -> {} // consumed, ignored — legacy field
                "body", "rock" -> {
                    val w = tokens[1].toIntOrNull() ?: fail("unreadable body width")
                    val h = tokens[2].toIntOrNull() ?: fail("unreadable body height")
                    val bits = tokens.getOrNull(9) ?: fail("a ${tokens[0]} needs a shape")
                    if (bits.length != w * h) fail("a ${w}x$h ${tokens[0]} has ${bits.length} cells")
                    bodies.add(
                        RigidBody(
                            kind = BodyKind.ROCK,
                            width = w, height = h,
                            cells = BooleanArray(bits.length) { bits[it] == '1' },
                            // Flight units — tiles, not mass — so the mass rescale does not touch it.
                            // World frame since v15; a v14 file is converted below.
                            positionX = long(3), positionY = long(4),
                            impulseX = scaled(5), impulseY = scaled(6),
                            // Per filled cell since v14; one figure before that, spread evenly —
                            // which is exactly what it meant, since a body has never been anything
                            // but isothermal.
                            energy = bodyEnergy(tokens[7], bits.count { it == '1' }, energyScale, ::fail),
                            // ⚠️ NOT scaled. A rock's composition is *proportions*, the same shape
                            // of value as `Material.composition`, and multiplying it would be
                            // meaningless rather than merely wrong — see `capacityPerTileOf`.
                            oreComposition = readMixture(tokens[8], Rescale.NONE, ::fail),
                            // v16. Absent from every earlier file, and absent means zero: a body
                            // that predates step 3 was never turned, because nothing could turn it.
                            ang = Coord(tokens.getOrNull(10)?.toIntOrNull() ?: 0),
                            // Mass·tile²/tick, so it carries the mass unit exactly once and is
                            // rescaled like any other momentum.
                            angImpulse = if (tokens.size > 11) scaled(11) else 0L,
                        ),
                    )
                }
                "impulse" -> {
                    impulseX = scaled(1); impulseY = scaled(2)
                    exhaustX = scaled(3); exhaustY = scaled(4)
                    // Absent = zero (ledger had fewer stores).
                    if (tokens.size > 6) { undeliveredX = scaled(5); undeliveredY = scaled(6) }
                    if (tokens.size > 8) { debugX = scaled(7); debugY = scaled(8) }
                    if (tokens.size > 10) { bodyImpulseX = scaled(9); bodyImpulseY = scaled(10) }
                    if (tokens.size > 12) { frameTurnX = scaled(11); frameTurnY = scaled(12) }
                }
                "air" -> {
                    val t = tile(1)
                    val mix = readMixture(tokens.getOrNull(2) ?: fail("expected a mixture"), scale, ::fail)
                    for (s in Species.ALL) airMass[MassIndex(t, s)] = mix[s]
                }
                else -> fail("unknown entry '${tokens[0]}'")
            }
        }

        val structure = StructureMap.derive(grid, deck)
        val occupancy = Occupancy.derive(grid, deck)
        // A file is hand-editable, so it is hand-breakable: a typed world that puts a belt and a
        // pipe on one tile violates the exclusion rule, and that is a *bad save*, not a bug in the
        // reader. Restated as a [SaveError] so the loader reports it the way it reports any other
        // unreadable line rather than throwing something the caller has no reason to expect.
        val conduits = try {
            Conduits.of(
                grid.size,
                *Conduit.entries.map { it to layers[it.ordinal].toList() }.toTypedArray(),
            )
        } catch (e: IllegalArgumentException) {
            throw SaveError(e.message ?: "the conduit layers do not describe a world")
        }
        // A tile the file gave a composition for is *not* made of its bill — it is a ghost, or a
        // length of track something has reacted with. Applied before the heat, since the energy line
        // is the last word on what this metal holds and must not be overwritten by a re-lay.
        for ((key, mix) in trackStuff) {
            val stuff = conduits.tracks[Conduit.entries[key.first]]
            val t = TileIndex(key.second)
            for (sp in Species.ALL) stuff[t, sp] = mix[sp]
        }
        // Every laid tile came back at ambient; the ones the file had a reading for get theirs back.
        // A tile with no `k=` is left exactly as laid, which is what the writer's omission meant.
        for ((key, energy) in segmentEnergy) {
            conduits.tracks.setEnergy(Conduit.entries[key.first], TileIndex(key.second), energy)
        }
        val air = Stuff.from(airMass, airEnergy)
        val pipeAir = Stuff.from(pipeMass, pipeEnergy)

        // V9: body momentum moved from vessel frame to world frame. `p_world = p_vessel + m_body · v_ship`.
        val momentumFixed = if (version >= 9 || bodies.isEmpty()) bodies.toList() else {
            val shipMass = vesselMass(grid, rail, conduits, deck, buffers)
            if (shipMass <= 0L) bodies.toList() else bodies.map {
                it.copy(
                    impulseX = it.impulseX + it.mass * impulseX / shipMass,
                    impulseY = it.impulseY + it.mass * impulseY / shipMass,
                )
            }
        }
        // V15: body *position* followed its momentum out of the vessel frame and into the world —
        // step 1 of `PLAN_rigid_bodies.md`, which deleted the vessel frame rather than correcting
        // for it. A version 14 file states a body's corner in grid coordinates, so it is placed
        // through the pose the same file describes.
        //
        // In practice every such file has `ang == 0` and the conversion is a translation, because
        // nothing could turn a ship until the tick before this format changed. It goes through
        // [Pose] anyway rather than adding `positionX`: a migration that is only correct for the
        // saves that happen to exist is a migration that breaks on the first one that does not.
        val loaded = if (version >= 15 || bodies.isEmpty()) momentumFixed else {
            val pose = Pose(positionX, positionY, ang)
            momentumFixed.map {
                it.copy(
                    positionX = pose.toWorldX(it.positionX, it.positionY),
                    positionY = pose.toWorldY(it.positionX, it.positionY),
                )
            }
        }
        return VesselState(
            grid = grid,
            gridPad = GRID_PAD,
            deck = deck,
            // The layer the machine records were read into. Omitting it does not fail loudly: the
            // default derived empty stores, so every warehouse came back with its store standing and
            // its contents gone. Hence: no default.
            buffers = buffers,
            rail = rail,
            conduits = conduits,
            diverters = FlowCursors(diverters, merges),
            gravity = gravity,
            positionX = positionX,
            positionY = positionY,
            netImpulseX = netImpulseX,
            netImpulseY = netImpulseY,
            ang = ang,
            angImpulse = angImpulse,
            netTorque = netTorque,
            tick = tick,
            extractedMass = extracted,
            ventedMass = vented,
            generatedEnergy = generated,
            radiatedEnergy = radiated,
            airVentedMass = airVented,
            structure = structure,
            occupancy = occupancy,
            creative = creative,
            builtMass = built,
            // A file that predates the field means "started empty", not "recompute from what is
            // aboard now" -- recomputing would launder every leak the save was written to catch.
            baselineCargoMass = baselineCargo ?: 0L,
            insertedEnergy = inserted,
            acquiredEnergy = acquired,
            solidToAirEnergy = solidToAir,
            // A missing baseline means the world's own totals, which is right for a handwritten
            // world and harmless for a saved one, where the line is always present. A version 4
            // file's baseline described the per-tile field, so it is not carried across: the
            // ledger is re-anchored to what the bodies actually hold.
            baselineEnergy = baselineEnergy ?: solidEnergy(conduits),
            air = air,
            pipeAir = pipeAir,
            pipeMomentum = MomentumField.of(edges, pipeMomentumX, pipeMomentumY),
            // Both fields, because they share one ledger — see VesselState.baselineAirMass.
            baselineAirMass = baselineAir ?: (air.totalMass + pipeAir.totalMass),
            airVentedEnergy = airVentedEnergy,
            injectedAirMass = injectedAirMass,
            injectedAirEnergy = injectedAirEnergy,
            baselineAirEnergy = baselineAirEnergy ?: (air.totalEnergy + pipeAir.totalEnergy),
            momentum = MomentumField.of(edges, momentumX, momentumY),
            vesselImpulseX = impulseX,
            vesselImpulseY = impulseY,
            exhaustMomentumX = exhaustX,
            exhaustMomentumY = exhaustY,
            undeliveredImpulseX = undeliveredX,
            undeliveredImpulseY = undeliveredY,
            debugImpulseX = debugX,
            debugImpulseY = debugY,
            bodyImpulseX = bodyImpulseX,
            bodyImpulseY = bodyImpulseY,
            frameTurnImpulseX = frameTurnX,
            frameTurnImpulseY = frameTurnY,
            bodies = loaded,
        )
    }

    /**
     * The `k=` heat off a pre-migration `machine` record, summed over its tiles.
     *
     * Summed rather than kept per tile because the two representations do not line up: a machine's
     * `k=` had one entry per tile of the machine, and a deck machine's heat is one entry per
     * tile of its footprint. For the one kind this applies to — a one-tile vent — the two are the
     * same number anyway, and the total is the quantity the ledger is checked against.
     */
    private fun readMigratedDeckHeat(
        tokens: List<String>,
        energyScale: Rescale,
        fail: (String) -> Nothing,
    ): Long? {
        val f = fields(tokens.drop(1), fail)
        val field = f["k"] ?: return null
        var sum = 0L
        for (part in field.split(',')) sum += energyScale.of(part.toLongOrNull() ?: fail("bad energy '$field'"))
        return sum
    }

    /** The reading half of [writeSparse]. */
    private fun readDeckHeat(tokens: List<String>, deck: StuffLayer, scale: Rescale, fail: (String) -> Nothing) {
        for (i in 1 until tokens.size) {
            val eq = tokens[i].indexOf('=')
            if (eq < 0) fail("expected index=value, got '${tokens[i]}'")
            val at = tokens[i].substring(0, eq).toIntOrNull() ?: fail("bad index in '${tokens[i]}'")
            if (at !in 0 until deck.tileCount) fail("index $at is outside the field")
            deck.setEnergy(TileIndex(at), scale.of(tokens[i].substring(eq + 1).toLongOrNull() ?: fail("bad value in '${tokens[i]}'")))
        }
    }

    private fun readSparse(tokens: List<String>, into: LongArray, scale: Rescale, fail: (String) -> Nothing) {
        for (i in 1 until tokens.size) {
            val eq = tokens[i].indexOf('=')
            if (eq < 0) fail("expected index=value, got '${tokens[i]}'")
            val at = tokens[i].substring(0, eq).toIntOrNull() ?: fail("bad index in '${tokens[i]}'")
            if (at !in into.indices) fail("index $at is outside the field")
            into[at] = scale.of(tokens[i].substring(eq + 1).toLongOrNull() ?: fail("bad value in '${tokens[i]}'"))
        }
    }

    private fun readMachine(
        tokens: List<String>,
        version: Int,
        tile: TileIndex,
        grid: Grid,
        buffers: BufferLayer,
        scale: Rescale,
        energyScale: Rescale,
        fail: (String) -> Nothing,
    ): DeckMachine {
        val kindName = tokens.firstOrNull() ?: fail("expected a machine kind")
        // A v9 world's `Miner` loads as the [Extractor] that replaced it: same buffer, same port,
        // same place in the line. Its `ore` field is dropped on purpose — an extractor has no ore
        // body of its own, because the rock it is standing on is the ore body now. The rename is
        // applied here rather than in the deck reader so that both spellings land on one path.
        val deckName = if (version < 10 && kindName == "Miner") "Extractor" else kindName
        if (deckName !in DeckMachineKind.ALL.map { it.toString() }) {
            fail("$kindName is a conduit, not a machine")
        }
        return readDeckMachine(
            listOf(deckName) + tokens.drop(1), version, tile, grid, buffers, scale, energyScale, fail,
        )
    }

    private fun readDeckMachine(
        tokens: List<String>,
        version: Int,
        tile: TileIndex,
        grid: Grid,
        buffers: BufferLayer,
        scale: Rescale,
        energyScale: Rescale,
        fail: (String) -> Nothing,
    ): DeckMachine {
        val kindName = tokens.firstOrNull() ?: fail("expected a machine kind")
        val kind = DeckMachineKind.ALL.firstOrNull { it.name == kindName }
            ?: fail("unknown machine '$kindName'")
        val f = fields(tokens.drop(1), fail)

        fun facing(): Direction = f["facing"]?.let { name ->
            Direction.ALL.firstOrNull { it.name == name } ?: fail("unknown direction '$name'")
        } ?: fail("$kindName needs a facing")
        /**
         * A store's contents.
         *
         * ⚠️ Tolerates the `S:` packet prefix, because a **legacy `bridge` record wrote its three
         * slots as packets** rather than as resources — a bridge carried `Packet`s while every other
         * machine's store held a `Resource`, and that difference reached the file. Now that its slots
         * are ordinary role tiles there is one spelling going forward, and this reads the old one.
         * `F:` is refused rather than unwrapped: there was never a fluid bridge, and a file claiming
         * one is a file that means something this build cannot honour.
         */
        fun res(key: String): Resource? = f[key]?.let {
            when {
                it.startsWith("S:") -> readResource(it.substring(2), scale, fail)
                it.startsWith("F:") -> fail("a $kindName store cannot hold a fluid packet: '$it'")
                else -> readResource(it, scale, fail)
            }
        }
        fun num(key: String, fallback: Long): Long =
            f[key]?.let { it.toLongOrNull() ?: fail("bad number '$it'") } ?: fallback
        // ⚠️ Scales the value read from the file but NOT the fallback, which is a current-unit
        // constant off the machine's own data class. Scaling a default would rescale a number that
        // was never in the old unit to begin with.
        fun massNum(key: String, fallback: Long): Long =
            f[key]?.let { scale.of(it.toLongOrNull() ?: fail("bad number '$it'")) } ?: fallback

        // V1 rate was per second; V2+ is per tick. Convert v1 by dividing by V1_TICKS_PER_SECOND.
        /**
         * A machine's throughput, defaulting to **that machine kind's own current default**.
         *
         * ⚠️ The fallback used to be a literal per machine — a fourth copy of a number already
         * stated on the data class — so a save with no `rate` field loaded a machine running at
         * whatever the rate was when this function was written. That is the "caller restates a
         * constant it does not own" family again, and it survived a rate change silently.
         */
        fun rate(fallback: Long): Long {
            val stored = massNum("rate", fallback * V1_TICKS_PER_SECOND)
            return if (version < 2) stored / V1_TICKS_PER_SECOND else massNum("rate", fallback)
        }

        val machine: DeckMachine = when (kind) {
            DeckMachineKind.Hull -> Hull(tile)
            DeckMachineKind.Airlock -> Airlock(tile)
            DeckMachineKind.Vent -> Vent(tile, ventedMass = massNum("vented", 0L))
            DeckMachineKind.Storage -> Storage(tile, facing())
            // v10 and earlier named a colour here. Read and discarded: a sensor now drives the wire
            // under it, and no colour can be turned back into a piece of geometry that was never laid.
            DeckMachineKind.Sensor -> Sensor(tile, facing())
            DeckMachineKind.KeyInput -> WireButton(
                tile,
                key = f["key"]?.let { name ->
                    InputKey.ALL.firstOrNull { it.name == name } ?: fail("unknown key '$name'")
                } ?: InputKey.Up,
            )
            DeckMachineKind.Pump -> Pump(tile, facing())
            DeckMachineKind.Vaporizer -> Vaporizer(
                tile,
                facing = facing(),
                carry = massNum("carry", 0L),
                massPerTick = rate(Vaporizer(tile, Direction.Right).massPerTick),
            )
            DeckMachineKind.Processor -> Processor(
                tile,
                facing = facing(),
                carry = massNum("carry", 0L),
                progress = num("actionProgress", 0L).toInt(),
                efficiencyPermille = num("eff", 900L).toInt(),
            )
            DeckMachineKind.ThermalDecomposer -> ThermalDecomposer(
                tile,
                facing = facing(),
                carry = massNum("carry", 0L),
                progress = num("actionProgress", 0L).toInt(),
                setTemperature = num("temp", 900L).toInt(),
            )
            DeckMachineKind.Extractor -> Extractor(
                tile,
                facing = facing(),
                carry = massNum("carry", 0L),
                massPerTick = rate(Extractor(tile, Direction.Right).massPerTick),
            )
            DeckMachineKind.Smelter -> Smelter(
                tile,
                facing = facing(),
                carry = massNum("carry", 0L),
                massPerTick = rate(Smelter(tile, Direction.Right).massPerTick),
            )
            DeckMachineKind.Thruster -> Thruster(
                tile,
                facing = facing(),
                carry = massNum("carry", 0L),
                massPerTick = rate(Thruster(tile, Direction.Right).massPerTick),
            )
            // Both are fittings that stand over a run and hold nothing. A gauge's reading is state
            // and comes back with it; a valve is only a position.
            DeckMachineKind.Gauge -> Gauge(
                tile,
                lastForm = f["lastform"]?.let { name ->
                    Form.ALL.firstOrNull { it.name == name } ?: fail("unknown form '$name'")
                },
                lastDominant = f["lastspecies"]?.let { name ->
                    Species.ALL.firstOrNull { it.name == name } ?: fail("unknown species '$name'")
                },
                lastPurity = num("lastpurity", 0L).toInt(),
                lastMass = massNum("lastmass", 0L),
            )
            DeckMachineKind.Valve -> Valve(tile)
            DeckMachineKind.Bridge -> {
                // ⛔ A pipe bridge is refused rather than quietly turned into a rail one. There was
                // never a way to build one — `Edit.Place` only ever made rail bridges — so a file
                // that says otherwise was hand-edited, and converting it would silently reroute a
                // network. Every real save says Rail or says nothing.
                f["conduit"]?.let { if (it != Conduit.Rail.name) fail("a bridge carries rail, not $it") }
                Bridge(tile, facing())
            }
        }
        // Falls back to what a *freshly placed one of these* is wired to, not to RUNNING. They are
        // the same for every machine but the airlock, which ships sealed — and a door that defaulted
        // to running would come back from a hand-written save wide open.
        val wiring = f["wire"]?.let { readWiring(it, fail) } ?: machine.wiring
        // Claimed and filled exactly as the machine record's are — see the twin in [readMachine]
        // for why claiming is separate from filling.
        buffers.claimRoles(grid, machine, tile)
        for (role in bufferRolesOf(machine)) {
            val store = bufferTile(grid, machine, tile, role) ?: continue
            buffers.put(store, res(storeKey(machine, role)))
        }
        return machine.withWiring(wiring)
    }

    private fun readSegment(
        tokens: List<String>,
        tile: TileIndex,
        rail: RailLayer,
        scale: Rescale,
        energyScale: Rescale,
        fail: (String) -> Nothing,
    ): Segment {
        val conduitName = tokens.firstOrNull() ?: fail("expected a conduit")
        val conduit = Conduit.entries.firstOrNull { it.name == conduitName } ?: fail("unknown conduit '$conduitName'")
        val f = fields(tokens.drop(1), fail)
        val links = f["links"]?.toIntOrNull() ?: fail("a segment needs its links")
        if (links !in 0..15) fail("links must be a 4-bit mask, got $links")
        // The load is the layer's now, but the field is still the segment's — the record was written
        // before the two were separate and nothing about the file has changed.
        f["held"]?.let { held ->
            val packet = readPacket(held, scale, fail)
            if (packet is SolidPacket) rail.put(tile, packet.resource)
            else fail("only a solid rides the track; tile $tile carries $held")
        }
        return Segment(conduit = conduit, links = links, deconstructing = f["scrapping"] == "1")
    }

    /**
     * The fitting a **legacy** conduit record was carrying, if any — the gauge or the valve that
     * used to be a flag on the segment itself.
     *
     * Both are buildings standing over their run now, so an old file's `gauge=1` or `valve=1` puts
     * one on the deck at that tile. Nothing writes these fields any more; this is migration only,
     * and it is why the reader needs the record after [readSegment] has finished with it.
     */
    private fun readMigratedFitting(
        tokens: List<String>,
        tile: TileIndex,
        scale: Rescale,
        fail: (String) -> Nothing,
    ): DeckMachine? {
        val f = fields(tokens.drop(1), fail)
        // `gauge=1` since v11; before that, *having* a channel was what made a segment a gauge.
        if (f["gauge"] == "1" || f["channel"] != null) {
            return Gauge(
                tile,
                lastForm = f["lastform"]?.let { name ->
                    Form.ALL.firstOrNull { it.name == name } ?: fail("unknown form '$name'")
                },
                lastDominant = f["lastspecies"]?.let { name ->
                    Species.ALL.firstOrNull { it.name == name } ?: fail("unknown species '$name'")
                },
                lastPurity = f["lastpurity"]?.toIntOrNull() ?: 0,
                lastMass = scale.of(f["lastmass"]?.toLongOrNull() ?: 0L),
            )
        }
        if (f["valve"] == "1") return Valve(tile)
        return null
    }

    /**
     * The `k=` reading off a segment record, or null where the file omitted it.
     *
     * Same rule as massNum: the stored figure scales, and it is the ENERGY factor, not the mass one
     * — a conduit's stored heat rode the mass factor until v14 split them, which was correct only
     * while the two units were locked together.
     */
    private fun readSegmentEnergy(
        tokens: List<String>,
        energyScale: Rescale,
        fail: (String) -> Nothing,
    ): Long? {
        val f = fields(tokens.drop(1), fail)
        return f["k"]?.let { energyScale.of(it.toLongOrNull() ?: fail("bad energy '$it'")) }
    }

    private fun readWiring(text: String, fail: (String) -> Nothing): Wiring {
        var wiring = Wiring(emptyMap())
        for (part in text.split(';')) {
            if (part.isEmpty()) continue
            val colon = part.indexOf(':')
            if (colon < 0) fail("expected ACTION:terms, got '$part'")
            val actionName = part.substring(0, colon)
            val action = Action.entries.firstOrNull { it.name == actionName } ?: fail("unknown action '$actionName'")
            val terms = part.substring(colon + 1)
            val triggers = if (terms.isEmpty()) emptyList() else terms.split(',').map { term ->
                val atSign = term.indexOf('@')
                if (atSign < 0) fail("expected SOURCE@weight, got '$term'")
                val sourceName = term.substring(0, atSign)
                Trigger(
                    readSource(sourceName, fail),
                    term.substring(atSign + 1).toIntOrNull() ?: fail("bad weight in '$term'"),
                )
            }
            wiring = wiring.with(action, triggers)
        }
        return wiring
    }

    /**
     * A term's source, reading both spellings.
     *
     * v10 and earlier wrote a colour: `ALWAYS` for the constant and one of six colours otherwise.
     * Every colour becomes [SignalSource.Wire], which is **lossy on purpose** — six global channels
     * cannot survive into a world that has none, and there is no wire in an old file to guess at.
     *
     * It is nonetheless behaviour-preserving in the case that matters. An unwired `Wire` term reads
     * 0, which is exactly what an unemitted channel read, so a vessel wired `ALWAYS − RED` goes on
     * running at full until its owner lays the run that stops it.
     */
    private fun readSource(name: String, fail: (String) -> Nothing): SignalSource =
        when (name) {
            "ALWAYS", "Always" -> SignalSource.Always
            "WIRE", "Wire" -> SignalSource.Wire
            // The v10 palette. Named rather than matched loosely, so a typo is still an error.
            "Red", "Green", "Blue", "Amber", "Cyan", "Violet" -> SignalSource.Wire
            else -> fail("unknown signal source '$name'")
        }

    /**
     * ⚠️ [scale] is **1** for a mixture that states proportions and the file's factor for one that
     * states a mass. The same syntax carries both — air in a tile is mass, a rock's `oreComposition`
     * is parts — so the distinction cannot be made here and every caller has to declare it.
     */
    /**
     * Species names that older saves use, and what they mean now.
     *
     * The species table was rebuilt around real minerals in version 17, and two entries could not
     * survive it. `Silica` was a compound sitting in a list of elements — it was always SiO₂, at
     * quartz's density, so it becomes [Species.Quartz] with every number unchanged and no rescale.
     * `RareEarth` was a fiction: there is no such element, and its 140 g/mol and 7010 kg/m³ were a
     * blend of cerium's mass and neodymium's density. It becomes [Species.Monazite], the ore a
     * player mining "rare earth" was standing in.
     *
     * ⚠️ Monazite is **235** g/mol against RareEarth's 140, so a loaded world's rare-earth pile has
     * the same *mass* — which is what is conserved and what the ledger checks — but a different
     * particle count. Nothing on a belt or in a stockpile notices; a tile of it in the atmosphere
     * would read a lower pressure. That is a real difference and it is the right one, because the
     * old number described nothing.
     *
     * Kept as a map rather than a version-gated branch because a name is a name whatever version
     * wrote it, and a file that says `Silica` means quartz regardless of what else it says.
     */
    private val RENAMED_SPECIES: Map<String, Species> = mapOf(
        "Silica" to Species.Quartz,
        "RareEarth" to Species.Monazite,
    )

    private fun readMixture(text: String, scale: Rescale, fail: (String) -> Nothing): Mixture {
        if (text == "-") return Mixture.EMPTY
        val masses = LongArray(Species.COUNT)
        var energy = 0L
        for (part in text.split(',')) {
            val eq = part.indexOf('=')
            if (eq < 0) fail("expected SPECIES=mass, got '$part'")
            val name = part.substring(0, eq)
            if (name == "energy") {
                val energyPart = part.substring(eq + 1).toLongOrNull() ?: fail("bad energy in '$part'")
                if (energyPart < 0L) fail("negative energy in '$part'")
                energy += energyPart
            } else {
                val species = Species.ALL.firstOrNull { it.name == name }
                    ?: RENAMED_SPECIES[name]
                    ?: fail("unknown species '$name'")
                val mass = part.substring(eq + 1).toLongOrNull() ?: fail("bad mass in '$part'")
                if (mass < 0L) fail("negative mass in '$part'")
                masses[species.ordinal] += scale.of(mass)
            }
        }
        return Mixture.of(masses, energy)
    }

    private fun readResource(text: String, scale: Rescale, fail: (String) -> Nothing): Resource {
        val slash = text.indexOf('/')
        if (slash < 0) fail("expected FORM/mixture, got '$text'")
        val name = text.substring(0, slash)
        val form = Form.ALL.firstOrNull { it.name == name } ?: fail("unknown form '$name'")
        return Resource(form, readMixture(text.substring(slash + 1), scale, fail))
    }

    private fun readPacket(text: String, scale: Rescale, fail: (String) -> Nothing): Packet = when {
        text.startsWith("S:") -> SolidPacket(readResource(text.substring(2), scale, fail))
        text.startsWith("F:") -> FluidPacket(readMixture(text.substring(2), scale, fail))
        else -> fail("expected S: or F:, got '$text'")
    }

    private fun fields(tokens: List<String>, fail: (String) -> Nothing): Map<String, String> {
        val out = HashMap<String, String>()
        for (token in tokens) {
            val eq = token.indexOf('=')
            if (eq < 0) fail("expected key=value, got '$token'")
            out[token.substring(0, eq)] = token.substring(eq + 1)
        }
        return out
    }
}
