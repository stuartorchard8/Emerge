package org.emerge.demo.outofspace.world

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

    /** Bump when a field's meaning changes. An old save is refused rather than misread. */
    const val VERSION = 1

    // ── Writing ───────────────────────────────────────────────────────────────

    fun write(state: VesselState): String {
        val out = StringBuilder()
        out.append("outofspace ").append(VERSION).append('\n')
        out.append("grid ").append(state.grid.width).append(' ').append(state.grid.height).append('\n')
        out.append("gravity ").append(state.gravity.x.raw).append(' ').append(state.gravity.y.raw).append('\n')
        out.append("tick ").append(state.tick).append('\n')
        out.append("mined ").append(state.minedGrams).append('\n')
        out.append("vented ").append(state.ventedGrams).append('\n')
        out.append("generated ").append(state.generatedJoules).append('\n')
        out.append("radiated ").append(state.radiatedJoules).append('\n')
        out.append("airvented ").append(state.airVentedGrams).append('\n')
        out.append("baselinejoules ").append(state.baselineJoules).append('\n')
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
        for (i in state.rails.indices) {
            val r = state.rails[i] ?: continue
            out.append("rail ").append(i).append(' ').append(writeSegment(r))
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

        // Heat is one number per tile and mostly zero outside the hull, so only what is warm is
        // written, packed several to a line to keep a big vessel from becoming thousands of them.
        var onLine = 0
        for (tile in 0 until state.grid.size) {
            val j = state.heat.joulesAt(tile)
            if (j == 0L) continue
            if (onLine == 0) out.append("heat")
            out.append(' ').append(tile).append('=').append(j)
            if (++onLine == HEAT_PER_LINE) { out.append('\n'); onLine = 0 }
        }
        if (onLine != 0) out.append('\n')

        // Air gets a line per tile: a mixture is wordy, and the air in one room is a thing you want
        // to be able to read and edit.
        for (tile in 0 until state.grid.size) {
            val mix = state.air.mixtureAt(tile)
            if (mix.isEmpty) continue
            out.append("air ").append(tile).append(' ').append(writeMixture(mix)).append('\n')
        }
        return out.toString()
    }

    private const val HEAT_PER_LINE = 12

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
                put("rate", m.gramsPerSecond.toString())
            }
            is Processor -> {
                put("in", m.input?.let { writeResource(it) })
                put("out", m.product?.let { writeResource(it) })
                put("waste", m.tailings?.let { writeResource(it) })
                put("carry", m.carry.toString())
                put("rate", m.gramsPerSecond.toString())
                put("eff", m.efficiencyPermille.toString())
            }
            is Smelter -> {
                put("in", m.input?.let { writeResource(it) })
                put("out", m.refined?.let { writeResource(it) })
                put("waste", m.slag?.let { writeResource(it) })
                put("carry", m.carry.toString())
                put("rate", m.gramsPerSecond.toString())
            }
            is Storage -> put("stored", m.contents?.let { writeResource(it) })
            is Sensor -> put("channel", m.channel.name)
            is Vent -> put("vented", m.ventedGrams.toString())
            is Hull -> {}
        }
        // Omitted when a machine is wired the way a freshly placed one is, which is almost all of
        // them — the file should show the wiring somebody actually did.
        if (m.wiring != Wiring.RUNNING) put("wire", writeWiring(m.wiring))
        return f.toString()
    }

    private fun writeSegment(s: Segment): String {
        val f = StringBuilder(s.conduit.name)
        f.append(" links=").append(s.links)
        s.channel?.let { f.append(" channel=").append(it.name) }
        s.held?.let { f.append(" held=").append(writePacket(it)) }
        // A gauge's reading persists after the packet has gone, so it is state, not decoration.
        s.lastForm?.let { f.append(" lastform=").append(it.name) }
        s.lastDominant?.let { f.append(" lastspecies=").append(it.name) }
        if (s.lastPurity != 0) f.append(" lastpurity=").append(s.lastPurity)
        if (s.lastMass != 0L) f.append(" lastmass=").append(s.lastMass)
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
        if (version != VERSION) {
            throw SaveError("save is version $version, this build reads version $VERSION")
        }

        val (gridLine, gridTokens) = next()
        if (gridTokens.size != 3 || gridTokens[0] != "grid") throw SaveError("line $gridLine: expected a grid")
        val grid = Grid(
            gridTokens[1].toIntOrNull() ?: throw SaveError("line $gridLine: bad width"),
            gridTokens[2].toIntOrNull() ?: throw SaveError("line $gridLine: bad height"),
        )
        if (grid.size <= 0) throw SaveError("line $gridLine: grid has no tiles")

        val machines = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        val bridges = arrayOfNulls<Bridge>(grid.size)
        val diverters = HashMap<Int, Int>()
        val piles = HashMap<Int, MutableList<Resource>>()
        val joules = LongArray(grid.size)
        val airGrams = LongArray(grid.size * Species.COUNT)

        var gravity = VesselState.DEFAULT_GRAVITY
        var tick = 0L
        var mined = 0L
        var vented = 0L
        var generated = 0L
        var radiated = 0L
        var airVented = 0L
        var baselineJoules: Long? = null
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
                "tick" -> tick = long(1)
                "mined" -> mined = long(1)
                "vented" -> vented = long(1)
                "generated" -> generated = long(1)
                "radiated" -> radiated = long(1)
                "airvented" -> airVented = long(1)
                "baselinejoules" -> baselineJoules = long(1)
                "baselineair" -> baselineAir = long(1)

                "machine" -> {
                    val t = tile(1)
                    if (machines[t] != null) fail("two machines at tile $t")
                    machines[t] = readMachine(tokens.drop(2), ::fail)
                }
                "rail" -> {
                    val t = tile(1)
                    if (rails[t] != null) fail("two segments at tile $t")
                    rails[t] = readSegment(tokens.drop(2), ::fail)
                }
                "bridge" -> {
                    val t = tile(1)
                    if (bridges[t] != null) fail("two bridges at tile $t")
                    bridges[t] = readMachine(tokens.drop(2), ::fail) as? Bridge ?: fail("not a bridge")
                }
                "diverter" -> diverters[tile(1)] = long(2).toInt()
                "debris" -> {
                    val pile = piles.getOrPut(tile(1)) { mutableListOf() }
                    for (i in 2 until tokens.size) pile.add(readResource(tokens[i], ::fail))
                }
                "heat" -> for (i in 1 until tokens.size) {
                    val eq = tokens[i].indexOf('=')
                    if (eq < 0) fail("expected tile=joules, got '${tokens[i]}'")
                    val t = tokens[i].substring(0, eq).toIntOrNull() ?: fail("bad tile in '${tokens[i]}'")
                    if (t !in 0 until grid.size) fail("tile $t is outside the grid")
                    joules[t] = tokens[i].substring(eq + 1).toLongOrNull() ?: fail("bad joules in '${tokens[i]}'")
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
        val heat = HeatField.of(joules)
        val air = AirField.of(airGrams)
        return VesselState(
            grid = grid,
            machines = machines.toList(),
            rails = rails.toList(),
            bridges = bridges.toList(),
            diverters = Diverters.of(diverters),
            gravity = gravity,
            debris = Debris.of(piles),
            tick = tick,
            minedGrams = mined,
            ventedGrams = vented,
            generatedJoules = generated,
            radiatedJoules = radiated,
            airVentedGrams = airVented,
            structure = structure,
            occupancy = occupancy,
            heat = heat,
            // A missing baseline means the world's own totals, which is right for a hand-written
            // world and harmless for a saved one, where the line is always present.
            baselineJoules = baselineJoules ?: heat.totalJoules,
            air = air,
            baselineAirGrams = baselineAir ?: air.totalGrams,
        )
    }

    private fun readMachine(tokens: List<String>, fail: (String) -> Nothing): Machine {
        val kindName = tokens.firstOrNull() ?: fail("expected a machine kind")
        val kind = MachineKind.ALL.firstOrNull { it.name == kindName } ?: fail("unknown machine '$kindName'")
        val f = fields(tokens.drop(1), fail)

        fun facing(): Direction = f["facing"]?.let { name ->
            Direction.ALL.firstOrNull { it.name == name } ?: fail("unknown direction '$name'")
        } ?: fail("$kindName needs a facing")
        fun res(key: String): Resource? = f[key]?.let { readResource(it, fail) }
        fun num(key: String, fallback: Long): Long =
            f[key]?.let { it.toLongOrNull() ?: fail("bad number '$it'") } ?: fallback

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
                gramsPerSecond = num("rate", 1_000L),
            )
            MachineKind.Processor -> Processor(
                facing = facing(),
                input = res("in"), product = res("out"), tailings = res("waste"),
                carry = num("carry", 0L),
                gramsPerSecond = num("rate", 500L),
                efficiencyPermille = num("eff", 900L).toInt(),
            )
            MachineKind.Smelter -> Smelter(
                facing = facing(),
                input = res("in"), refined = res("out"), slag = res("waste"),
                carry = num("carry", 0L),
                gramsPerSecond = num("rate", 500L),
            )
            MachineKind.Storage -> Storage(facing = facing(), contents = res("stored"))
            MachineKind.Sensor -> Sensor(
                facing = facing(),
                channel = f["channel"]?.let { name ->
                    Channel.ALL.firstOrNull { it.name == name } ?: fail("unknown channel '$name'")
                } ?: Channel.Red,
            )
            MachineKind.Vent -> Vent(ventedGrams = num("vented", 0L))
            MachineKind.Hull -> Hull()
            // Track is a segment, not a machine, and has its own line.
            MachineKind.Rail, MachineKind.Gauge -> fail("$kindName is track, not a machine")
        }
        val wiring = f["wire"]?.let { readWiring(it, fail) } ?: Wiring.RUNNING
        return machine.withWiring(wiring)
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
