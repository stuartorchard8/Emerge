package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.MomentumField
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
    const val VERSION = 10

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
        out.append("outofspace ").append(VERSION).append('\n')
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
            is Sensor -> put("channel", m.channel.name)
            // A pump holds nothing: what it moves is in the two air fields. Facing, wiring and
            // heat are all written by the common code around this.
            is Pump -> {}
            is Vent -> put("vented", m.ventedGrams.toString())
            is Hull -> {}
        }
        // Omitted when a machine is wired the way a freshly placed one is, which is almost all of
        // them — the file should show the wiring somebody actually did.
        if (m.wiring != Wiring.RUNNING) put("wire", writeWiring(m.wiring))
        // And omitted at room temperature, for the same reason: a file full of identical `k=`
        // fields hides the one machine that is actually hot. Version 5 and later; a version 4 file
        // has no per-body heat at all and every body loads at ambient.
        if (m.joules != ambientJoules(m.kind)) put("k", m.joules.toString())
        return f.toString()
    }

    private fun writeSegment(s: Segment): String {
        val f = StringBuilder(s.conduit.name)
        f.append(" links=").append(s.links)
        s.channel?.let { f.append(" channel=").append(it.name) }
        // Written only when set, like every other optional field: a file full of `valve=0` would
        // hide the handful of tiles that are actually taps.
        if (s.valve) f.append(" valve=1")
        s.held?.let { f.append(" held=").append(writePacket(it)) }
        // A gauge's reading persists after the packet has gone, so it is state, not decoration.
        s.lastForm?.let { f.append(" lastform=").append(it.name) }
        s.lastDominant?.let { f.append(" lastspecies=").append(it.name) }
        if (s.lastPurity != 0) f.append(" lastpurity=").append(s.lastPurity)
        if (s.lastMass != 0L) f.append(" lastmass=").append(s.lastMass)
        if (s.joules != s.conduit.material.ambientPerTile) f.append(" k=").append(s.joules)
        return f.toString()
    }

    private fun writeWiring(w: Wiring): String =
        Action.entries.joinToString(";") { action ->
            action.name + ":" + w.triggers(action).joinToString(",") { "${it.channel.name}@${it.weightPermille}" }
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
        if (header.size != 2 || header[0] != "outofspace") {
            throw SaveError("line $headerLine: not an Out of Space save")
        }
        val version = header[1].toIntOrNull()
            ?: throw SaveError("line $headerLine: unreadable version '${header[1]}'")
        if (version !in 1..VERSION) {
            throw SaveError("save is version $version, this build reads version 1..$VERSION")
        }

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
            fun tile(i: Int): Int {
                val t = tokens.getOrNull(i)?.toIntOrNull() ?: fail("expected a tile index")
                if (t !in 0 until grid.size) fail("tile $t is outside a ${grid.width}x${grid.height} grid")
                return t
            }

            when (tokens[0]) {
                "gravity" -> gravity = Frac2(Frac(long(1)), Frac(long(2)))
                "position" -> { positionX = long(1); positionY = long(2) }
                "thrust" -> { netImpulseX = long(1); netImpulseY = long(2) }
                "tick" -> tick = long(1)
                // `mined` is v9's name for it: the same quantity, counted at the miner instead.
                "mined", "extracted" -> extracted = long(1)
                "vented" -> vented = long(1)
                "generated" -> generated = long(1)
                "radiated" -> radiated = long(1)
                "airvented" -> airVented = long(1)
                "airventedheat" -> airVentedJoules = long(1)
                "airinjected" -> { injectedAirGrams = long(1); injectedAirJoules = long(2) }
                "baselineairheat" -> baselineAirJoules = long(1)
                "baselinejoules" -> baselineJoules = long(1)
                "inserted" -> inserted = long(1)
                "acquired" -> acquired = long(1)
                // Old spelling: the energy the player inserted, now [insertedJoules].
                "construction" -> inserted = long(1)
                "solidtoair" -> solidToAir = long(1)
                "baselineair" -> baselineAir = long(1)

                "machine" -> {
                    val t = tile(1)
                    if (machines[t] != null) fail("two machines at tile $t")
                    machines[t] = readMachine(tokens.drop(2), version, ::fail)
                }
                // `rail` = v5 spelling; record carries conduit name, so old files land on the right layer.
                "rail", "conduit" -> {
                    val t = tile(1)
                    val segment = readSegment(tokens.drop(2), ::fail)
                    val layer = layers[segment.conduit.ordinal]
                    // Per layer, not per tile. Two segments on one tile is what layers are *for*;
                    // two of the same conduit on one tile is still a corrupt file.
                    if (layer[t] != null) fail("two ${segment.conduit.label} segments at tile $t")
                    layer[t] = segment
                }
                "bridge" -> {
                    val t = tile(1)
                    if (bridges[t] != null) fail("two bridges at tile $t")
                    bridges[t] = readMachine(tokens.drop(2), version, ::fail) as? Bridge ?: fail("not a bridge")
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
                "airheat" -> readSparse(tokens, airJoules, ::fail)
                "pipeair" -> {
                    val t = tile(1)
                    val mix = readMixture(tokens.getOrNull(2) ?: fail("expected a mixture"), ::fail)
                    for (s in Species.ALL) pipeGrams[t * Species.COUNT + s.ordinal] = mix[s]
                }
                "pipeairheat" -> readSparse(tokens, pipeJoules, ::fail)
                "pipemomx" -> readSparse(tokens, pipeMomentumX, ::fail)
                "pipemomy" -> readSparse(tokens, pipeMomentumY, ::fail)
                "momx" -> readSparse(tokens, momentumX, ::fail)
                "momy" -> readSparse(tokens, momentumY, ::fail)
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
                            positionX = long(3), positionY = long(4),
                            impulseX = long(5), impulseY = long(6),
                            joules = long(7),
                            oreComposition = readMixture(tokens[8], ::fail),
                        ),
                    )
                }
                "impulse" -> {
                    impulseX = long(1); impulseY = long(2)
                    exhaustX = long(3); exhaustY = long(4)
                    // Absent = zero (ledger had fewer stores).
                    if (tokens.size > 6) { undeliveredX = long(5); undeliveredY = long(6) }
                    if (tokens.size > 8) { debugX = long(7); debugY = long(8) }
                    if (tokens.size > 10) { bodyImpulseX = long(9); bodyImpulseY = long(10) }
                }
                "air" -> {
                    val t = tile(1)
                    val mix = readMixture(tokens.getOrNull(2) ?: fail("expected a mixture"), ::fail)
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
    private fun readSparse(tokens: List<String>, into: LongArray, fail: (String) -> Nothing) {
        for (i in 1 until tokens.size) {
            val eq = tokens[i].indexOf('=')
            if (eq < 0) fail("expected index=value, got '${tokens[i]}'")
            val at = tokens[i].substring(0, eq).toIntOrNull() ?: fail("bad index in '${tokens[i]}'")
            if (at !in into.indices) fail("index $at is outside the field")
            into[at] = tokens[i].substring(eq + 1).toLongOrNull() ?: fail("bad value in '${tokens[i]}'")
        }
    }

    private fun readMachine(tokens: List<String>, version: Int, fail: (String) -> Nothing): Machine {
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
        fun res(key: String): Resource? = f[key]?.let { readResource(it, fail) }
        fun num(key: String, fallback: Long): Long =
            f[key]?.let { it.toLongOrNull() ?: fail("bad number '$it'") } ?: fallback

        // V1 rate was per second; V2+ is per tick. Convert v1 by dividing by V1_TICKS_PER_SECOND.
        fun rate(fallback: Long): Long {
            val stored = num("rate", fallback * V1_TICKS_PER_SECOND)
            return if (version < 2) stored / V1_TICKS_PER_SECOND else num("rate", fallback)
        }

        val machine: Machine = when (kind) {
            MachineKind.Bridge -> Bridge(
                facing = facing(),
                conduit = f["conduit"]?.let { name ->
                    Conduit.entries.firstOrNull { it.name == name } ?: fail("unknown conduit '$name'")
                } ?: Conduit.Rail,
                // `held` = v5 bridge slot name; read as entry so old saves load material.
                entry = (f["in"] ?: f["held"])?.let { readPacket(it, fail) },
                middle = f["span"]?.let { readPacket(it, fail) },
                exit = f["out"]?.let { readPacket(it, fail) },
            )
            MachineKind.Extractor -> Extractor(
                facing = facing(),
                buffer = res("buffer") ?: Resource(Form.Ore, Mixture.EMPTY),
                input = res("in"),
                carry = num("carry", 0L),
                gramsPerTick = rate(250L),
            )
            MachineKind.Processor -> Processor(
                facing = facing(),
                input = res("in"), product = res("out"), tailings = res("waste"),
                carry = num("carry", 0L),
                gramsPerTick = rate(125L),
                efficiencyPermille = num("eff", 900L).toInt(),
            )
            MachineKind.Vaporizer -> Vaporizer(
                facing = facing(),
                input = res("in"),
                carry = num("carry", 0L),
                gramsPerTick = rate(125L),
            )
            MachineKind.Smelter -> Smelter(
                facing = facing(),
                input = res("in"), refined = res("out"), slag = res("waste"),
                carry = num("carry", 0L),
                gramsPerTick = rate(125L),
            )
            MachineKind.Storage -> Storage(facing = facing(), contents = res("stored"))
            MachineKind.Sensor -> Sensor(
                facing = facing(),
                channel = f["channel"]?.let { name ->
                    Channel.ALL.firstOrNull { it.name == name } ?: fail("unknown channel '$name'")
                } ?: Channel.Red,
            )
            MachineKind.Vent -> Vent(ventedGrams = num("vented", 0L))
            MachineKind.Pump -> Pump(facing())
            MachineKind.Hull -> Hull()
            // Track is a segment, not a machine, and has its own line.
            MachineKind.Rail, MachineKind.Pipe, MachineKind.Gauge, MachineKind.Valve ->
                fail("$kindName is a conduit, not a machine")
        }
        val wiring = f["wire"]?.let { readWiring(it, fail) } ?: Wiring.RUNNING
        // No `k=` field → room-temperature default (from constructor). Old `heat` lines parsed for well-formedness, discarded.
        val heated = f["k"]?.let { j ->
            machine.withJoules(j.toLongOrNull() ?: fail("bad joules '$j'"))
        } ?: machine
        return heated.withWiring(wiring)
    }

    private fun readSegment(tokens: List<String>, fail: (String) -> Nothing): Segment {
        val conduitName = tokens.firstOrNull() ?: fail("expected a conduit")
        val conduit = Conduit.entries.firstOrNull { it.name == conduitName } ?: fail("unknown conduit '$conduitName'")
        val f = fields(tokens.drop(1), fail)
        val links = f["links"]?.toIntOrNull() ?: fail("a segment needs its links")
        if (links !in 0..15) fail("links must be a 4-bit mask, got $links")
        return Segment(
            conduit = conduit,
            links = links,
            held = f["held"]?.let { readPacket(it, fail) },
            channel = f["channel"]?.let { name ->
                Channel.ALL.firstOrNull { it.name == name } ?: fail("unknown channel '$name'")
            },
            lastForm = f["lastform"]?.let { name ->
                Form.ALL.firstOrNull { it.name == name } ?: fail("unknown form '$name'")
            },
            lastDominant = f["lastspecies"]?.let { name ->
                Species.ALL.firstOrNull { it.name == name } ?: fail("unknown species '$name'")
            },
            lastPurity = f["lastpurity"]?.toIntOrNull() ?: 0,
            lastMass = f["lastmass"]?.toLongOrNull() ?: 0L,
            valve = f["valve"] == "1",
            joules = f["k"]?.toLongOrNull() ?: conduit.material.ambientPerTile,
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
                if (atSign < 0) fail("expected CHANNEL@weight, got '$term'")
                val channelName = term.substring(0, atSign)
                Trigger(
                    Channel.ALL.firstOrNull { it.name == channelName } ?: fail("unknown channel '$channelName'"),
                    term.substring(atSign + 1).toIntOrNull() ?: fail("bad weight in '$term'"),
                )
            }
            wiring = wiring.with(action, triggers)
        }
        return wiring
    }

    private fun readMixture(text: String, fail: (String) -> Nothing): Mixture {
        if (text == "-") return Mixture.EMPTY
        val grams = LongArray(Species.COUNT)
        for (part in text.split(',')) {
            val eq = part.indexOf('=')
            if (eq < 0) fail("expected SPECIES=grams, got '$part'")
            val name = part.substring(0, eq)
            val species = Species.ALL.firstOrNull { it.name == name } ?: fail("unknown species '$name'")
            val mass = part.substring(eq + 1).toLongOrNull() ?: fail("bad mass in '$part'")
            if (mass < 0L) fail("negative mass in '$part'")
            grams[species.ordinal] += mass
        }
        return Mixture.ofGrams(grams)
    }

    private fun readResource(text: String, fail: (String) -> Nothing): Resource {
        val slash = text.indexOf('/')
        if (slash < 0) fail("expected FORM/mixture, got '$text'")
        val name = text.substring(0, slash)
        val form = Form.ALL.firstOrNull { it.name == name } ?: fail("unknown form '$name'")
        return Resource(form, readMixture(text.substring(slash + 1), fail))
    }

    private fun readPacket(text: String, fail: (String) -> Nothing): Packet = when {
        text.startsWith("S:") -> SolidPacket(readResource(text.substring(2), fail))
        text.startsWith("F:") -> FluidPacket(readMixture(text.substring(2), fail))
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
