package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.Budget

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species

import org.emerge.demo.outofspace.logistics.FluidPacket
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.SolidPacket
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
    const val VERSION = 13

    /**
     * The tick rate version 1 saves were written at, and so the number that converts their
     * `rate` field from grams per second into the grams per tick version 2 stores.
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
            .append(' ').append(Budget.MICROGRAMS_PER_UNIT).append('\n')
        out.append("grid ").append(state.grid.width).append(' ').append(state.grid.height).append('\n')
        out.append("gravity ").append(state.gravity.x.raw).append(' ').append(state.gravity.y.raw).append('\n')
        // Position absent = origin.
        out.append("position ").append(state.positionX).append(' ').append(state.positionY).append('\n')
        // Felt gravity baseline; a world reloaded without it coasts for one tick under plating alone.
        out.append("thrust ").append(state.netImpulseX).append(' ').append(state.netImpulseY).append('\n')
        out.append("tick ").append(state.tick).append('\n')
        out.append("extracted ").append(state.extractedGrams).append('\n')
        out.append("vented ").append(state.ventedGrams).append('\n')
        out.append("generated ").append(state.generatedJoules).append('\n')
        out.append("radiated ").append(state.radiatedJoules).append('\n')
        out.append("airvented ").append(state.airVentedGrams).append('\n')
        out.append("baselinejoules ").append(state.baselineJoules).append('\n')
        out.append("inserted ").append(state.insertedJoules).append('\n')
        out.append("acquired ").append(state.acquiredJoules).append('\n')
        out.append("solidtoair ").append(state.solidToAirJoules).append('\n')
        out.append("baselineair ").append(state.baselineAirGrams).append('\n')
        // Bodies: free mass, no tracking beyond the list itself.

        // Body momentum in world frame, position on vessel grid. Shape as 0/1 run for hand-editing.
        for (b in state.bodies) {
            out.append("body ").append(b.width).append(' ').append(b.height)
                .append(' ').append(b.positionX).append(' ').append(b.positionY)
                .append(' ').append(b.impulseX).append(' ').append(b.impulseY)
                .append(' ').append(b.joules)
                .append(' ').append(writeMixture(b.oreComposition!!))
                .append(' ')
            for (c in b.cells) out.append(if (c) '1' else '0')
            out.append("   # ").append(b.filled).append(" cells, ").append(b.massGrams).append("g\n")
        }

        // Tiles are written as indices because that is what the world is indexed by, but an index is
        // unreadable to a person and the whole point of the format is that a person can read it. So
        // every placed thing carries its coordinates in a comment, and track spells its links out —
        // `links=5` says nothing, `R-L-` says the run goes left to right through this tile.
        for (i in state.machines.indices) {
            val m = state.machines[i] ?: continue
            out.append("machine ").append(i).append(' ').append(writeMachine(m))
            out.append("   # ").append(where(state.grid, i)).append('\n')
        }
        // One line per segment per layer, keyed `conduit` rather than `rail` since version 6 — the
        // record always named its own network, but while there was one list per tile the keyword
        // could pretend otherwise. A version 5 file writes `rail 42 PIPE ...` and means it.
        state.conduits.all { _, i, r ->
            out.append("conduit ").append(i).append(' ').append(writeSegment(r))
            out.append("   # ").append(where(state.grid, i)).append(' ').append(linkLetters(r)).append('\n')
        }
        for (i in state.bridges.indices) {
            val b = state.bridges[i] ?: continue
            out.append("bridge ").append(i).append(' ').append(writeMachine(b))
            out.append("   # ").append(where(state.grid, i)).append('\n')
        }
        for (tile in 0 until state.grid.size) {
            val cursor = state.diverters.forkCursors[tile] ?: 0
            if (cursor != 0) out.append("diverter ").append(tile).append(' ').append(cursor).append('\n')
        }
        // Which feeder a merge takes from next. A separate line from `diverter` because a tile can
        // be both, and an older save simply has none of these.
        for (tile in 0 until state.grid.size) {
            val cursor = state.diverters.mergeCursors[tile] ?: 0
            if (cursor != 0) out.append("merge ").append(tile).append(' ').append(cursor).append('\n')
        }

        // Solid heat lives on machines/segments (their `k=` field), not a separate per-tile block.
        // Air per tile: mixture is wordy but readable/editable.
        for (tile in 0 until state.grid.size) {
            val mix = state.air.mixtureAt(tile)
            if (mix.isEmpty) continue
            out.append("air ").append(tile).append(' ').append(writeMixture(mix)).append('\n')
        }

        // Packed sparsely like heat. Version 3 and earlier stored per-tile heat; absent loads ambient.
        writeSparse(out, "airheat", state.air.copyJoules())
        out.append("airventedheat ").append(state.airVentedJoules).append('\n')
        // The debug bellows' admission. Appended rather than versioned, like the impulse line:
        // absent reads as zero, which is exactly what a world that never cheated has.
        out.append("airinjected ").append(state.injectedAirGrams)
            .append(' ').append(state.injectedAirJoules).append('\n')
        out.append("baselineairheat ").append(state.baselineAirJoules).append('\n')

        // Packed like heat. Momentum saved because reloading without it resumes becalmed.
        // Pipes: same format, empty network = zero cost.
        for (tile in 0 until state.grid.size) {
            val mix = state.pipeAir.mixtureAt(tile)
            if (mix.isEmpty) continue
            out.append("pipeair ").append(tile).append(' ').append(writeMixture(mix)).append('\n')
        }
        writeSparse(out, "pipeairheat", state.pipeAir.copyJoules())
        writeSparse(out, "pipemomx", state.pipeMomentum.copyX())
        writeSparse(out, "pipemomy", state.pipeMomentum.copyY())

        writeSparse(out, "momx", state.momentum.copyX())
        writeSparse(out, "momy", state.momentum.copyY())

        // Ten impulse values (ledger grew). Appended, not versioned: absent reads as zero.
        out.append("impulse ").append(state.vesselImpulseX).append(' ').append(state.vesselImpulseY)
            .append(' ').append(state.exhaustMomentumX).append(' ').append(state.exhaustMomentumY)
            .append(' ').append(state.undeliveredImpulseX).append(' ').append(state.undeliveredImpulseY)
            .append(' ').append(state.debugImpulseX).append(' ').append(state.debugImpulseY)
            .append(' ').append(state.bodyImpulseX).append(' ').append(state.bodyImpulseY)
            .append('\n')
        return out.toString()
    }

    private const val HEAT_PER_LINE = 12

    /** Index=value pairs, several to a line, skipping zeros. The idiom the heat field already uses. */
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

    private fun where(grid: Grid, tile: Int): String = "(${grid.xOf(tile)}, ${grid.yOf(tile)})"

    /** A segment's links as `RDLU`, with a dash for each direction it is not joined to. */
    private fun linkLetters(s: Segment): String =
        Direction.ALL.joinToString("") { if (s.linkedTo(it)) it.name.take(1) else "-" }

    private fun writeMachine(m: Machine): String {
        val f = StringBuilder(m.kind.name)
        fun put(key: String, value: String?) {
            if (value != null) f.append(' ').append(key).append('=').append(value)
        }
        if (m is Directed) put("facing", m.facing.name)
        when (m) {
            is Bridge -> {
                put("conduit", m.conduit.name)
                put("in", m.entry?.let { writePacket(it) })
                put("span", m.middle?.let { writePacket(it) })
                put("out", m.exit?.let { writePacket(it) })
            }
            is Extractor -> {
                put("buffer", writeResource(m.buffer))
                put("in", m.input?.let { writeResource(it) })
                put("carry", m.carry.toString())
                put("rate", m.gramsPerTick.toString())
            }
            is Processor -> {
                put("in", m.input?.let { writeResource(it) })
                put("out", m.product?.let { writeResource(it) })
                put("waste", m.tailings?.let { writeResource(it) })
                put("carry", m.carry.toString())
                put("rate", m.gramsPerTick.toString())
                put("eff", m.efficiencyPermille.toString())
            }
            is Vaporizer -> {
                put("in", m.input?.let { writeResource(it) })
                put("carry", m.carry.toString())
                put("rate", m.gramsPerTick.toString())
            }
            is Smelter -> {
                put("in", m.input?.let { writeResource(it) })
                put("out", m.refined?.let { writeResource(it) })
                put("waste", m.slag?.let { writeResource(it) })
                put("carry", m.carry.toString())
                put("rate", m.gramsPerTick.toString())
            }
            is Storage -> put("stored", m.contents?.let { writeResource(it) })
            // A sensor is its facing and its wiring, both written by the common code around this.
            is Sensor -> {}
            // A button is its key and its wiring; the common code writes the second.
            is KeyInput -> put("key", m.key.name)
            // A pump holds nothing: what it moves is in the two air fields. Facing, wiring and
            // heat are all written by the common code around this.
            is Pump -> {}
            is Vent -> put("vented", m.ventedGrams.toString())
            // An airlock is its wiring, and the common code around this writes that.
            is Hull, is Airlock -> {}
        }
        // Omitted when a machine is wired the way a freshly placed one is, which is almost all of
        // them — the file should show the wiring somebody actually did.
        if (m.wiring != Wiring.RUNNING) put("wire", writeWiring(m.wiring))
        // And omitted at room temperature, for the same reason: a file full of identical `k=`
        // fields hides the one machine that is actually hot. Version 5 and later; a version 4 file
        // has no per-body heat at all and every body loads at ambient.
        if (m.joules != ambientJoules(m.kind)) put("k", writeTileJoules(m.joules))
        return f.toString()
    }

    /**
     * A machine's per-tile heat, comma-separated in footprint order. Version 12 and later.
     *
     * One field rather than one per tile because a machine's tiles are not addressable from the
     * file: the footprint is derived from the kind and the centre, so the *order* is the address.
     */
    private fun writeTileJoules(j: TileJoules): String =
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
     * The remainder is not dropped: [TileJoules.plusSpread] hands it to the first tiles, so a
     * reloaded machine holds exactly what it held before, to the joule. Anything else would make
     * save/load a slow leak, and the energy ledger would eventually notice.
     */
    private fun readTileJoules(
        field: String,
        machine: Machine,
        version: Int,
        scale: Long,
        fail: (String) -> Nothing,
    ): TileJoules {
        val tiles = machine.kind.thermalTiles
        if (version < 12) {
            val total = (field.toLongOrNull() ?: fail("bad joules '$field'")) * scale
            return TileJoules.uniform(tiles, 0L).plusSpread(total)
        }
        val parts = field.split(',')
        if (parts.size != tiles) fail("expected $tiles per-tile joules for ${machine.kind}, got ${parts.size}")
        return TileJoules.of(LongArray(tiles) { (parts[it].toLongOrNull() ?: fail("bad joules '$field'")) * scale })
    }

    private fun writeSegment(s: Segment): String {
        val f = StringBuilder(s.conduit.name)
        f.append(" links=").append(s.links)
        if (s.isGauge) f.append(" gauge=1")
        // Written only when set, like every other optional field: a file full of `valve=0` would
        // hide the handful of tiles that are actually taps.
        if (s.valve) f.append(" valve=1")
        s.held?.let { f.append(" held=").append(writePacket(it)) }
        // A gauge's reading persists after the packet has gone, so it is state, not decoration.
        s.lastForm?.let { f.append(" lastform=").append(it.name) }
        s.lastDominant?.let { f.append(" lastspecies=").append(it.name) }
        if (s.lastPurity != 0) f.append(" lastpurity=").append(s.lastPurity)
        if (s.lastMass != 0L) f.append(" lastmass=").append(s.lastMass)
        if (s.joules != s.conduit.ambientPerTile) f.append(" k=").append(s.joules)
        return f.toString()
    }

    private fun writeWiring(w: Wiring): String =
        Action.entries.joinToString(";") { action ->
            action.name + ":" + w.triggers(action).joinToString(",") { "${it.source.name}@${it.weightPermille}" }
        }

    private fun writeMixture(m: Mixture): String {
        if (m.isEmpty) return "-"
        return Species.ALL.filter { m[it] > 0L }.joinToString(",") { "${it.name}=${m[it]}" }
    }

    private fun writeResource(r: Resource): String = "${r.form.name}/${writeMixture(r.mixture)}"

    private fun writePacket(p: Packet): String = when (p) {
        is SolidPacket -> "S:" + writeResource(p.resource)
        is FluidPacket -> "F:" + writeMixture(p.contents)
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    /**
     * What every mass-, energy- and momentum-dimensioned number in the file must be multiplied by.
     *
     * ### Why the file states its unit instead of the version implying it
     *
     * A save is a pile of bare integers, and an integer only means something once you know what one
     * of them was worth when it was written. Keying that to the version number would work exactly
     * once: [Budget.MICROGRAMS_PER_UNIT] is a knob, and the moment it moves twice there is no way to
     * recover which of two units a version-13 file was written in. Stating the unit in the file
     * makes every save self-describing and makes any future rescale free of save work entirely.
     *
     * ### Why one factor covers all three dimensions
     *
     * Because `Budget.MILLIJOULE == Budget.GRAM`: [Budget.ENERGY_PER_MASS] is pinned at 1000 by
     * specific heat being quoted per kilogram, and that is exactly the relation which makes a
     * millijoule-per-gram the pairing every capacity expression already assumes. Momentum is
     * `gram·tiles/tick`, mass times a dimensionless velocity, so it follows mass too. One number
     * rescales the lot, and if the [Budget.ENERGY_PER_MASS] relation is ever broken this function is
     * the first thing that has to grow a second factor.
     *
     * Absent means one gram per unit: every file written before version 13 was, and the unit has
     * only ever gone down, so the factor is always a whole number.
     */
    private fun readScale(stated: String?, line: Int): Long {
        val fileUnit = if (stated == null) 1_000_000L else stated.toLongOrNull()
            ?: throw SaveError("line $line: unreadable mass unit '$stated'")
        if (fileUnit <= 0L) throw SaveError("line $line: mass unit must be positive, got $fileUnit")
        if (fileUnit % Budget.MICROGRAMS_PER_UNIT != 0L) {
            // Only ever a widening. A file finer than this build could not be represented without
            // rounding every mass in the world, and silently halving somebody's cargo is a worse
            // outcome than refusing to open it.
            throw SaveError(
                "line $line: save is in units of $fileUnit µg, which this build's " +
                    "${Budget.MICROGRAMS_PER_UNIT} µg cannot represent without losing precision"
            )
        }
        return fileUnit / Budget.MICROGRAMS_PER_UNIT
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
        if (header.size !in 2..3 || header[0] != "outofspace") {
            throw SaveError("line $headerLine: not an Out of Space save")
        }
        val version = header[1].toIntOrNull()
            ?: throw SaveError("line $headerLine: unreadable version '${header[1]}'")
        if (version !in 1..VERSION) {
            throw SaveError("save is version $version, this build reads version 1..$VERSION")
        }
        val scale = readScale(header.getOrNull(2), headerLine)

        val (gridLine, gridTokens) = next()
        if (gridTokens.size != 3 || gridTokens[0] != "grid") throw SaveError("line $gridLine: expected a grid")
        val grid = Grid(
            gridTokens[1].toIntOrNull() ?: throw SaveError("line $gridLine: bad width"),
            gridTokens[2].toIntOrNull() ?: throw SaveError("line $gridLine: bad height"),
        )
        if (grid.size <= 0) throw SaveError("line $gridLine: grid has no tiles")

        val machines = arrayOfNulls<Machine>(grid.size)
        val layers = Array(Conduit.entries.size) { arrayOfNulls<Segment>(grid.size) }
        val bridges = arrayOfNulls<Bridge>(grid.size)
        val diverters = HashMap<Int, Int>()
        val merges = HashMap<Int, Int>()
        val piles = HashMap<Int, MutableList<Resource>>()
        val airGrams = LongArray(grid.size * Species.COUNT)
        val airJoules = LongArray(grid.size)
        val edges = EdgeGrid(grid)
        val momentumX = LongArray(edges.xEdgeCount)
        val momentumY = LongArray(edges.yEdgeCount)
        val pipeGrams = LongArray(grid.size * Species.COUNT)
        val pipeJoules = LongArray(grid.size)
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
        var bodyImpulseX = 0L
        var bodyImpulseY = 0L
        val bodies = ArrayList<RigidBody>()

        // Absent = freefall. Older saves store one-g explicitly.
        var gravity = VesselState.FREEFALL
        var positionX = 0L
        var positionY = 0L
        var netImpulseX = 0L
        var netImpulseY = 0L
        var tick = 0L
        var extracted = 0L
        var vented = 0L
        var generated = 0L
        var radiated = 0L
        var airVented = 0L
        var airVentedJoules = 0L
        var injectedAirGrams = 0L
        var injectedAirJoules = 0L
        var baselineAirJoules: Long? = null
        var baselineJoules: Long? = null
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
            fun scaled(i: Int): Long = long(i) * scale
            fun tile(i: Int): Int {
                val t = tokens.getOrNull(i)?.toIntOrNull() ?: fail("expected a tile index")
                if (t !in 0 until grid.size) fail("tile $t is outside a ${grid.width}x${grid.height} grid")
                return t
            }

            when (tokens[0]) {
                "gravity" -> gravity = Frac2(Frac(long(1)), Frac(long(2)))
                "position" -> { positionX = long(1); positionY = long(2) }
                "thrust" -> { netImpulseX = scaled(1); netImpulseY = scaled(2) }
                "tick" -> tick = long(1)
                // `mined` is v9's name for it: the same quantity, counted at the miner instead.
                "mined", "extracted" -> extracted = scaled(1)
                "vented" -> vented = scaled(1)
                "generated" -> generated = scaled(1)
                "radiated" -> radiated = scaled(1)
                "airvented" -> airVented = scaled(1)
                "airventedheat" -> airVentedJoules = scaled(1)
                "airinjected" -> { injectedAirGrams = scaled(1); injectedAirJoules = scaled(2) }
                "baselineairheat" -> baselineAirJoules = scaled(1)
                "baselinejoules" -> baselineJoules = scaled(1)
                "inserted" -> inserted = scaled(1)
                "acquired" -> acquired = scaled(1)
                // Old spelling: the energy the player inserted, now [insertedJoules].
                "construction" -> inserted = scaled(1)
                "solidtoair" -> solidToAir = scaled(1)
                "baselineair" -> baselineAir = scaled(1)

                "machine" -> {
                    val t = tile(1)
                    if (machines[t] != null) fail("two machines at tile $t")
                    machines[t] = readMachine(tokens.drop(2), version, scale, ::fail)
                }
                // `rail` = v5 spelling; record carries conduit name, so old files land on the right layer.
                "rail", "conduit" -> {
                    val t = tile(1)
                    val segment = readSegment(tokens.drop(2), scale, ::fail)
                    val layer = layers[segment.conduit.ordinal]
                    // Per layer, not per tile. Two segments on one tile is what layers are *for*;
                    // two of the same conduit on one tile is still a corrupt file.
                    if (layer[t] != null) fail("two ${segment.conduit.label} segments at tile $t")
                    layer[t] = segment
                }
                "bridge" -> {
                    val t = tile(1)
                    if (bridges[t] != null) fail("two bridges at tile $t")
                    bridges[t] = readMachine(tokens.drop(2), version, scale, ::fail) as? Bridge ?: fail("not a bridge")
                }
                "diverter" -> diverters[tile(1)] = long(2).toInt()
                "merge" -> merges[tile(1)] = long(2).toInt()
                // V4 stored heat per tile — averaged, which is why it was replaced. Parse for well-formedness, drop.
                "heat" -> for (i in 1 until tokens.size) {
                    val eq = tokens[i].indexOf('=')
                    if (eq < 0) fail("expected tile=joules, got '${tokens[i]}'")
                    val t = tokens[i].substring(0, eq).toIntOrNull() ?: fail("bad tile in '${tokens[i]}'")
                    if (t !in 0 until grid.size) fail("tile $t is outside the grid")
                    tokens[i].substring(eq + 1).toLongOrNull() ?: fail("bad joules in '${tokens[i]}'")
                }
                "airheat" -> readSparse(tokens, airJoules, scale, ::fail)
                "pipeair" -> {
                    val t = tile(1)
                    val mix = readMixture(tokens.getOrNull(2) ?: fail("expected a mixture"), scale, ::fail)
                    for (s in Species.ALL) pipeGrams[t * Species.COUNT + s.ordinal] = mix[s]
                }
                "pipeairheat" -> readSparse(tokens, pipeJoules, scale, ::fail)
                "pipemomx" -> readSparse(tokens, pipeMomentumX, scale, ::fail)
                "pipemomy" -> readSparse(tokens, pipeMomentumY, scale, ::fail)
                "momx" -> readSparse(tokens, momentumX, scale, ::fail)
                "momy" -> readSparse(tokens, momentumY, scale, ::fail)
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
                            // Position is in Flight units — tiles, not mass — so it does not move.
                            positionX = long(3), positionY = long(4),
                            impulseX = scaled(5), impulseY = scaled(6),
                            joules = scaled(7),
                            // ⚠️ NOT scaled. A rock's composition is *proportions*, the same shape
                            // of value as `Material.composition`, and multiplying it would be
                            // meaningless rather than merely wrong — see `capacityPerTileOf`.
                            oreComposition = readMixture(tokens[8], 1L, ::fail),
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
                }
                "air" -> {
                    val t = tile(1)
                    val mix = readMixture(tokens.getOrNull(2) ?: fail("expected a mixture"), scale, ::fail)
                    for (s in Species.ALL) airGrams[t * Species.COUNT + s.ordinal] = mix[s]
                }
                else -> fail("unknown entry '${tokens[0]}'")
            }
        }

        val structure = StructureMap.derive(grid, machines.toList())
        val occupancy = Occupancy.derive(grid, machines.toList())
        val conduits = Conduits.of(
            grid.size,
            *Conduit.entries.map { it to layers[it.ordinal].toList() }.toTypedArray(),
        )
        // Built from both arrays together: save with temp keeps it, older gets ambient (not absolute zero).
        val air = if (airJoules.any { it != 0L }) AirField.of(airGrams, airJoules) else AirField.of(airGrams)
        // Same rule as the room air, and it matters more here: a version 6 file has no pipe lines at
        // all, so the network loads empty rather than at some temperature nothing was ever at.
        val pipeAir =
            if (pipeJoules.any { it != 0L }) AirField.of(pipeGrams, pipeJoules) else AirField.of(pipeGrams)

        // V9: body momentum moved from vessel frame to world frame. `p_world = p_vessel + m_body · v_ship`.
        val loaded = if (version >= 9 || bodies.isEmpty()) bodies.toList() else {
            val shipMass = vesselMassGrams(machines.toList(), conduits, bridges.toList())
            if (shipMass <= 0L) bodies.toList() else bodies.map {
                it.copy(
                    impulseX = it.impulseX + it.massGrams * impulseX / shipMass,
                    impulseY = it.impulseY + it.massGrams * impulseY / shipMass,
                )
            }
        }
        return VesselState(
            grid = grid,
            gridPad = GRID_PAD,
            machines = machines.toList(),
            conduits = conduits,
            bridges = bridges.toList(),
            diverters = FlowCursors(diverters, merges),
            gravity = gravity,
            positionX = positionX,
            positionY = positionY,
            netImpulseX = netImpulseX,
            netImpulseY = netImpulseY,
            tick = tick,
            extractedGrams = extracted,
            ventedGrams = vented,
            generatedJoules = generated,
            radiatedJoules = radiated,
            airVentedGrams = airVented,
            structure = structure,
            occupancy = occupancy,
            insertedJoules = inserted,
            acquiredJoules = acquired,
            solidToAirJoules = solidToAir,
            // A missing baseline means the world's own totals, which is right for a handwritten
            // world and harmless for a saved one, where the line is always present. A version 4
            // file's baseline described the per-tile field, so it is not carried across: the
            // ledger is re-anchored to what the bodies actually hold.
            baselineJoules = if (version >= 5) baselineJoules ?: solidJoules(
                machines.toList(), conduits, bridges.toList()
            ) else solidJoules(machines.toList(), conduits, bridges.toList()),
            air = air,
            pipeAir = pipeAir,
            pipeMomentum = MomentumField.of(edges, pipeMomentumX, pipeMomentumY),
            // Both fields, because they share one ledger — see VesselState.baselineAirGrams.
            baselineAirGrams = baselineAir ?: (air.totalGrams + pipeAir.totalGrams),
            airVentedJoules = airVentedJoules,
            injectedAirGrams = injectedAirGrams,
            injectedAirJoules = injectedAirJoules,
            baselineAirJoules = baselineAirJoules ?: (air.totalJoules + pipeAir.totalJoules),
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
            bodies = loaded,
        )
    }

    /** The reading half of [writeSparse]. */
    private fun readSparse(tokens: List<String>, into: LongArray, scale: Long, fail: (String) -> Nothing) {
        for (i in 1 until tokens.size) {
            val eq = tokens[i].indexOf('=')
            if (eq < 0) fail("expected index=value, got '${tokens[i]}'")
            val at = tokens[i].substring(0, eq).toIntOrNull() ?: fail("bad index in '${tokens[i]}'")
            if (at !in into.indices) fail("index $at is outside the field")
            into[at] = (tokens[i].substring(eq + 1).toLongOrNull() ?: fail("bad value in '${tokens[i]}'")) * scale
        }
    }

    private fun readMachine(tokens: List<String>, version: Int, scale: Long, fail: (String) -> Nothing): Machine {
        val kindName = tokens.firstOrNull() ?: fail("expected a machine kind")
        // A v9 world's `Miner` loads as the [Extractor] that replaced it: same buffer, same port,
        // same place in the line. Its `ore` field is dropped on purpose — an extractor has no ore
        // body of its own, because the rock it is standing on is the ore body now.
        val kind = MachineKind.ALL.firstOrNull { it.name == kindName }
            ?: (MachineKind.Extractor.takeIf { version < 10 && kindName == "Miner" })
            ?: fail("unknown machine '$kindName'")
        val f = fields(tokens.drop(1), fail)

        fun facing(): Direction = f["facing"]?.let { name ->
            Direction.ALL.firstOrNull { it.name == name } ?: fail("unknown direction '$name'")
        } ?: fail("$kindName needs a facing")
        fun res(key: String): Resource? = f[key]?.let { readResource(it, scale, fail) }
        fun num(key: String, fallback: Long): Long =
            f[key]?.let { it.toLongOrNull() ?: fail("bad number '$it'") } ?: fallback
        // ⚠️ Scales the value read from the file but NOT the fallback, which is a current-unit
        // constant off the machine's own data class. Scaling a default would rescale a number that
        // was never in the old unit to begin with.
        fun massNum(key: String, fallback: Long): Long =
            f[key]?.let { (it.toLongOrNull() ?: fail("bad number '$it'")) * scale } ?: fallback

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

        val machine: Machine = when (kind) {
            MachineKind.Bridge -> Bridge(
                facing = facing(),
                conduit = f["conduit"]?.let { name ->
                    Conduit.entries.firstOrNull { it.name == name } ?: fail("unknown conduit '$name'")
                } ?: Conduit.Rail,
                // `held` = v5 bridge slot name; read as entry so old saves load material.
                entry = (f["in"] ?: f["held"])?.let { readPacket(it, scale, fail) },
                middle = f["span"]?.let { readPacket(it, scale, fail) },
                exit = f["out"]?.let { readPacket(it, scale, fail) },
            )
            MachineKind.Extractor -> Extractor(
                facing = facing(),
                buffer = res("buffer") ?: Resource(Form.Ore, Mixture.EMPTY),
                input = res("in"),
                carry = massNum("carry", 0L),
                gramsPerTick = rate(Extractor(Direction.Right).gramsPerTick),
            )
            MachineKind.Processor -> Processor(
                facing = facing(),
                input = res("in"), product = res("out"), tailings = res("waste"),
                carry = massNum("carry", 0L),
                gramsPerTick = rate(Processor(Direction.Right).gramsPerTick),
                efficiencyPermille = num("eff", 900L).toInt(),
            )
            MachineKind.Vaporizer -> Vaporizer(
                facing = facing(),
                input = res("in"),
                carry = massNum("carry", 0L),
                gramsPerTick = rate(Vaporizer(Direction.Right).gramsPerTick),
            )
            MachineKind.Smelter -> Smelter(
                facing = facing(),
                input = res("in"), refined = res("out"), slag = res("waste"),
                carry = massNum("carry", 0L),
                gramsPerTick = rate(Smelter(Direction.Right).gramsPerTick),
            )
            MachineKind.Storage -> Storage(facing = facing(), contents = res("stored"))
            // v10 and earlier named a colour here. Read and discarded: a sensor now drives the wire
            // under it, and no colour can be turned back into a piece of geometry that was never laid.
            MachineKind.Sensor -> Sensor(facing = facing())
            MachineKind.KeyInput -> KeyInput(
                key = f["key"]?.let { name ->
                    InputKey.ALL.firstOrNull { it.name == name } ?: fail("unknown key '$name'")
                } ?: InputKey.Up,
            )
            MachineKind.Vent -> Vent(ventedGrams = massNum("vented", 0L))
            MachineKind.Pump -> Pump(facing())
            MachineKind.Hull -> Hull()
            MachineKind.Airlock -> Airlock()
            // Track is a segment, not a machine, and has its own line.
            MachineKind.Rail, MachineKind.Pipe, MachineKind.Gauge, MachineKind.Valve, MachineKind.Wire ->
                fail("$kindName is a conduit, not a machine")
        }
        // Falls back to what a *freshly placed one of these* is wired to, not to RUNNING. They are
        // the same for every machine but the airlock, which ships sealed — and a door that defaulted
        // to running would come back from a hand-written save wide open.
        val wiring = f["wire"]?.let { readWiring(it, fail) } ?: machine.wiring
        // No `k=` field → room-temperature default (from constructor). Old `heat` lines parsed for well-formedness, discarded.
        val heated = f["k"]?.let { j -> machine.withJoules(readTileJoules(j, machine, version, scale, fail)) } ?: machine
        return heated.withWiring(wiring)
    }

    private fun readSegment(tokens: List<String>, scale: Long, fail: (String) -> Nothing): Segment {
        val conduitName = tokens.firstOrNull() ?: fail("expected a conduit")
        val conduit = Conduit.entries.firstOrNull { it.name == conduitName } ?: fail("unknown conduit '$conduitName'")
        val f = fields(tokens.drop(1), fail)
        val links = f["links"]?.toIntOrNull() ?: fail("a segment needs its links")
        if (links !in 0..15) fail("links must be a 4-bit mask, got $links")
        return Segment(
            conduit = conduit,
            links = links,
            held = f["held"]?.let { readPacket(it, scale, fail) },
            // `gauge=1` since v11; before that, *having* a channel was what made a segment a gauge.
            isGauge = f["gauge"] == "1" || f["channel"] != null,
            lastForm = f["lastform"]?.let { name ->
                Form.ALL.firstOrNull { it.name == name } ?: fail("unknown form '$name'")
            },
            lastDominant = f["lastspecies"]?.let { name ->
                Species.ALL.firstOrNull { it.name == name } ?: fail("unknown species '$name'")
            },
            lastPurity = f["lastpurity"]?.toIntOrNull() ?: 0,
            lastMass = (f["lastmass"]?.toLongOrNull() ?: 0L) * scale,
            valve = f["valve"] == "1",
            // Same rule as massNum: the stored figure scales, the ambient fallback does not.
            joules = f["k"]?.let { (it.toLongOrNull() ?: fail("bad joules '$it'")) * scale }
                ?: conduit.ambientPerTile,
        )
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
     * states a mass. The same syntax carries both — air in a tile is grams, a rock's `oreComposition`
     * is parts — so the distinction cannot be made here and every caller has to declare it.
     */
    private fun readMixture(text: String, scale: Long, fail: (String) -> Nothing): Mixture {
        if (text == "-") return Mixture.EMPTY
        val grams = LongArray(Species.COUNT)
        for (part in text.split(',')) {
            val eq = part.indexOf('=')
            if (eq < 0) fail("expected SPECIES=grams, got '$part'")
            val name = part.substring(0, eq)
            val species = Species.ALL.firstOrNull { it.name == name } ?: fail("unknown species '$name'")
            val mass = part.substring(eq + 1).toLongOrNull() ?: fail("bad mass in '$part'")
            if (mass < 0L) fail("negative mass in '$part'")
            grams[species.ordinal] += mass * scale
        }
        return Mixture.ofGrams(grams)
    }

    private fun readResource(text: String, scale: Long, fail: (String) -> Nothing): Resource {
        val slash = text.indexOf('/')
        if (slash < 0) fail("expected FORM/mixture, got '$text'")
        val name = text.substring(0, slash)
        val form = Form.ALL.firstOrNull { it.name == name } ?: fail("unknown form '$name'")
        return Resource(form, readMixture(text.substring(slash + 1), scale, fail))
    }

    private fun readPacket(text: String, scale: Long, fail: (String) -> Nothing): Packet = when {
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
