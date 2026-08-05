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
 * The whole world as text, and back again.
 *
 * ### Why text
 * A save here is not primarily a convenience for the player — it is a way to **hand a broken world to
 * someone else**. "The junction at (34, 12) does the wrong thing" costs a paragraph to describe, a
 * reconstruction to reproduce, and gets it subtly wrong about half the time; a file gets it exactly
 * right and costs nothing. That is worth more than compactness, so the format is line-oriented,
 * greppable and diffable: two saves of the same factory differ on the lines that actually changed,
 * and a machine can be retyped by hand to try something.
 *
 * ### What is written
 * Only what the world cannot re-derive. [VesselState.structure], [VesselState.occupancy] and
 * [VesselState.signals] are all recomputed from the machines every tick, so writing them would be
 * writing a cache — and a cache in a save file is a cache that can disagree with the thing it caches.
 * The **ledgers are written**, including the two baselines, precisely because they are not derivable:
 * `mined == aboard + vented` is only a statement about this world's history, and a load that reset
 * them would silently forgive every leak that happened before the save.
 *
 * ### Round-tripping is the test
 * Saving, loading and running on must produce the same world as never having saved at all. The suite
 * asserts exactly that by running two copies for a while and comparing their *text* — which is a
 * sharper check than comparing states, because it fails on anything the format forgot to carry.
 */
object Save {

    /** Bump when a field's meaning changes. An old save is migrated, or refused rather than misread. */
    const val VERSION = 8

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
        // Where it has got to. Absent means the origin, which is where every world starts.
        out.append("position ").append(state.positionX).append(' ').append(state.positionY).append('\n')
        // Last tick's thrust, which is not a ledger but is still not derivable: it is what the felt
        // gravity is worked out from, so a world reloaded without it would coast for one tick under
        // the plating alone and then diverge from the one that was never saved.
        out.append("thrust ").append(state.netImpulseX).append(' ').append(state.netImpulseY).append('\n')
        out.append("tick ").append(state.tick).append('\n')
        out.append("mined ").append(state.minedGrams).append('\n')
        out.append("vented ").append(state.ventedGrams).append('\n')
        out.append("generated ").append(state.generatedJoules).append('\n')
        out.append("radiated ").append(state.radiatedJoules).append('\n')
        out.append("airvented ").append(state.airVentedGrams).append('\n')
        out.append("baselinejoules ").append(state.baselineJoules).append('\n')
        out.append("construction ").append(state.constructionJoules).append('\n')
        out.append("solidtoair ").append(state.solidToAirJoules).append('\n')
        out.append("baselineair ").append(state.baselineAirGrams).append('\n')

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
            val cursor = state.diverters[tile]
            if (cursor != 0) out.append("diverter ").append(tile).append(' ').append(cursor).append('\n')
        }
        for (tile in state.debris.tiles()) {
            out.append("debris ").append(tile)
            for (r in state.debris[tile]) out.append(' ').append(writeResource(r))
            out.append("   # ").append(where(state.grid, tile)).append('\n')
        }

        // Solid heat has no line of its own any more. It lives on the machine and the segment,
        // written as their `k=` field, because that is where the energy lives in the world — see
        // [Body]. A separate per-tile block would be a second place for it to be, and the two would
        // disagree the first time somebody hand-edited one of them.

        // Air gets a line per tile: a mixture is wordy, and the air in one room is a thing you want
        // to be able to read and edit.
        for (tile in 0 until state.grid.size) {
            val mix = state.air.mixtureAt(tile)
            if (mix.isEmpty) continue
            out.append("air ").append(tile).append(' ').append(writeMixture(mix)).append('\n')
        }

        // How hot that air is. Packed sparsely like heat, and written after the air itself because
        // it only means anything against the mass it belongs to. A world saved before version 4 has
        // no line here and loads at room temperature, which is what it was simulating.
        writeSparse(out, "airheat", state.air.copyJoules())
        out.append("airventedheat ").append(state.airVentedJoules).append('\n')
        out.append("baselineairheat ").append(state.baselineAirJoules).append('\n')

        // How that air is moving. Packed like heat, and for the same reason: a still vessel writes
        // none of it at all. Saved rather than left to be re-derived because momentum is the fluid's
        // memory -- a world reloaded without it resumes becalmed, and a draught that had to build
        // up again from rest is a different world from the one that was saved.
        // What is in the pipes, written exactly like the room air above it and only where there is
        // any: an empty network costs nothing, which is what every world has until something pumps.
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

        // Eight values since the ledger gained its fifth store. Appended rather than versioned, twice
        // now: a file written with four is a world whose undelivered impulse was zero and one written
        // with six is a world nobody had used the debug engine in, and in both cases absent reads
        // correctly as zero — see the optional pairs below.
        out.append("impulse ").append(state.vesselImpulseX).append(' ').append(state.vesselImpulseY)
            .append(' ').append(state.exhaustMomentumX).append(' ').append(state.exhaustMomentumY)
            .append(' ').append(state.undeliveredImpulseX).append(' ').append(state.undeliveredImpulseY)
            .append(' ').append(state.debugImpulseX).append(' ').append(state.debugImpulseY)
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
            is Miner -> {
                put("ore", writeMixture(m.composition))
                put("buffer", writeResource(m.buffer))
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

        var gravity = VesselState.DEFAULT_GRAVITY
        var positionX = 0L
        var positionY = 0L
        var netImpulseX = 0L
        var netImpulseY = 0L
        var tick = 0L
        var mined = 0L
        var vented = 0L
        var generated = 0L
        var radiated = 0L
        var airVented = 0L
        var airVentedJoules = 0L
        var baselineAirJoules: Long? = null
        var baselineJoules: Long? = null
        var construction = 0L
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
                "mined" -> mined = long(1)
                "vented" -> vented = long(1)
                "generated" -> generated = long(1)
                "radiated" -> radiated = long(1)
                "airvented" -> airVented = long(1)
                "airventedheat" -> airVentedJoules = long(1)
                "baselineairheat" -> baselineAirJoules = long(1)
                "baselinejoules" -> baselineJoules = long(1)
                "construction" -> construction = long(1)
                "solidtoair" -> solidToAir = long(1)
                "baselineair" -> baselineAir = long(1)

                "machine" -> {
                    val t = tile(1)
                    if (machines[t] != null) fail("two machines at tile $t")
                    machines[t] = readMachine(tokens.drop(2), version, ::fail)
                }
                // `rail` is the version 5 spelling and still read: the record carries its own
                // conduit either way, so an old file lands on the right layer with no migration.
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
                "debris" -> {
                    val pile = piles.getOrPut(tile(1)) { mutableListOf() }
                    for (i in 2 until tokens.size) pile.add(readResource(tokens[i], ::fail))
                }
                // Version 4 and earlier stored heat per *tile*. There is no honest way to
                // redistribute a tile's joules over the bodies standing on it — the field averaged
                // them in the first place, which is the reason it was replaced — so the line is
                // parsed for well-formedness and dropped, and every body loads at ambient. Silently
                // ignoring an unknown key instead would let a genuine typo through.
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
                "impulse" -> {
                    impulseX = long(1); impulseY = long(2)
                    exhaustX = long(3); exhaustY = long(4)
                    // Absent in files written before the ledger had a fourth store, and zero is the
                    // right reading of absent: nothing had been counted there yet.
                    if (tokens.size > 6) { undeliveredX = long(5); undeliveredY = long(6) }
                    // Likewise: a world saved before the debug engine existed is a world in which
                    // nothing had cheated, and zero is what that means.
                    if (tokens.size > 8) { debugX = long(7); debugY = long(8) }
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
        // Built from both arrays together, so a save that carries temperature keeps it and one that
        // predates temperature gets the room-temperature default rather than a world at absolute
        // zero. See AirField.of for why the two are one value.
        val air = if (airJoules.any { it != 0L }) AirField.of(airGrams, airJoules) else AirField.of(airGrams)
        // Same rule as the room air, and it matters more here: a version 6 file has no pipe lines at
        // all, so the network loads empty rather than at some temperature nothing was ever at.
        val pipeAir =
            if (pipeJoules.any { it != 0L }) AirField.of(pipeGrams, pipeJoules) else AirField.of(pipeGrams)
        return VesselState(
            grid = grid,
            machines = machines.toList(),
            conduits = conduits,
            bridges = bridges.toList(),
            diverters = Diverters.of(diverters),
            gravity = gravity,
            positionX = positionX,
            positionY = positionY,
            netImpulseX = netImpulseX,
            netImpulseY = netImpulseY,
            debris = Debris.of(piles),
            tick = tick,
            minedGrams = mined,
            ventedGrams = vented,
            generatedJoules = generated,
            radiatedJoules = radiated,
            airVentedGrams = airVented,
            structure = structure,
            occupancy = occupancy,
            constructionJoules = construction,
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
        val kind = MachineKind.ALL.firstOrNull { it.name == kindName } ?: fail("unknown machine '$kindName'")
        val f = fields(tokens.drop(1), fail)

        fun facing(): Direction = f["facing"]?.let { name ->
            Direction.ALL.firstOrNull { it.name == name } ?: fail("unknown direction '$name'")
        } ?: fail("$kindName needs a facing")
        fun res(key: String): Resource? = f[key]?.let { readResource(it, fail) }
        fun num(key: String, fallback: Long): Long =
            f[key]?.let { it.toLongOrNull() ?: fail("bad number '$it'") } ?: fallback

        // Version 1 stated a machine's throughput per *second*; version 2 states it per tick, which
        // is the only unit the sim has. An old file's number is therefore divided by the rate those
        // files ran at, so a v1 factory keeps the throughput it was built with instead of silently
        // speeding up fourfold. Defaults are already per tick, so they are not converted.
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
                // `held` is what a bridge's single slot was called before it became the three tiles
                // it looks like. Read as the entry slot so an older save loads its material rather
                // than quietly dropping it — which would look like a leak, not a format change.
                entry = (f["in"] ?: f["held"])?.let { readPacket(it, fail) },
                middle = f["span"]?.let { readPacket(it, fail) },
                exit = f["out"]?.let { readPacket(it, fail) },
            )
            MachineKind.Miner -> Miner(
                facing = facing(),
                composition = readMixture(f["ore"] ?: fail("a miner needs an ore body"), fail),
                buffer = res("buffer") ?: Resource(Form.Ore, Mixture.EMPTY),
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
        // A file that predates the body model says nothing about a machine's own heat, so it gets
        // the room-temperature default its constructor already supplied. The old per-tile `heat`
        // lines are read and discarded: they described a field that no longer exists, and
        // reinterpreting a tile's joules as a body's would be exactly the misreading the version
        // number is there to prevent.
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
