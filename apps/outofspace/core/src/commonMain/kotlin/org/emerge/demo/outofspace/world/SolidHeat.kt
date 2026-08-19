package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Processor
import org.emerge.demo.outofspace.world.machine.Thruster

/** Result of one solid conduction tick. [energy]: new energy per body. [radiated]: energy lost to space. [toAir]: net energy into atmosphere (negative = air heated solid). */
class SolidHeatStep(val energy: LongArray, val radiated: Long, val toAir: Long)

/**
 * Advances every solid body's temperature one tick: conduction between touching bodies, exchange with air,
 * and radiation from exposed surfaces.
 *
 * Contact rules: shared tiles always touch; impermeable bodies touch across tile faces; permeable fittings
 * touch only their own tile; fittings link to linked fittings (per [Segment.links]).
 *
 * Jacobi solve: all fluxes computed against a snapshot, then applied — avoids Gauss-Seidel leftward bias.
 * Each flux capped at equalisation amount; total outgoing energy scaled if requested exceeds available.
 */
fun stepSolidHeat(
    grid: Grid,
    bodies: List<Body>,
    structure: StructureMap,
    airEnergy: EnergyArray,
    heatCapacity: LongArray,
): SolidHeatStep {
    val bodyCount = bodies.size
    val tileCount = grid.size
    if (bodyCount == 0) return SolidHeatStep(LongArray(0), 0L, 0L)

    // ── Nodes: bodies + air tiles in one array. ──
    val nodeCount = bodyCount + tileCount
    val capacity = LongArray(nodeCount)
    val kelvin = IntArray(nodeCount)
    for (b in 0 until bodyCount) {
        capacity[b] = bodies[b].capacity
        kelvin[b] = bodies[b].kelvin
    }
    for (i in 0 until tileCount) {
        val c = heatCapacity[i]
        val tile = TileIndex(i)
        capacity[bodyCount + i] = c
        kelvin[bodyCount + i] =
            if (c <= 0L) Temperature.AMBIENT_KELVIN else (airEnergy[tile] / c).toInt()
    }

    val tiles = TileBodies(grid.size, bodies)
    val contacts = Contacts()

    for (b in bodies.indices) {
        val body = bodies[b]
        val k = body.conductance

        for (i in tiles.startOf(body.tile) until tiles.endOf(body.tile)) {
            val other = tiles.id(i)
            if (other <= b) continue // each unordered pair once
            contacts.join(b, other, seriesConductance(k, bodies[other].conductance))
        }

        // ⚠️ An `else` and not an early `continue`. This used to sit inside a `for (tile in
        // body.tiles)` loop, where `continue` meant "this body has no more to do *on this tile*"
        // and the track-to-track block below still ran. A body is one tile now, that loop is gone,
        // and the same `continue` skips to the next *body* — which silently retired the block
        // below for every fitting, since a fitting is always permeable. Heat stopped running along
        // rails and pipes at all.
        if (body.permeable) {
            // A fitting reaches the air in its own tile and nothing further.
            contacts.join(b, bodyCount + body.tile.index, seriesConductance(k, Material.AIR_FILM))
        } else {
            // Impermeable: reaches across tile faces.
            for (dir in Direction.ALL) {
                val next = grid.neighbour(body.tile, dir)
                if (next == TileIndex.NONE) continue
                if (structure.isImpermeable(next)) {
                    // Once per face, not per pair.
                    for (i in tiles.startOf(next) until tiles.endOf(next)) {
                        val other = tiles.id(i)
                        if (other <= b || bodies[other].permeable) continue
                        contacts.join(b, other, seriesConductance(k, bodies[other].conductance))
                    }
                } else if (structure.isContained(next)) {
                    contacts.join(b, bodyCount + next.index, seriesConductance(k, Material.AIR_FILM))
                }
            }
        }

        // Track-to-track: heat follows drawn links, not undrawn adjacencies.
        // Per-layer only — a pipe crossing a rail conducts through the tile but must not conduct along the rail's run.
        if (body.slot == BodySlot.Fitting && body.conduit != null) {
            for (dir in Direction.ALL) {
                if (!body.linkedTo(dir)) continue
                val next = grid.neighbour(body.tile, dir)
                if (next == TileIndex.NONE) continue
                val other = tiles.fittingAt(body.conduit, next)
                if (other < 0 || other <= b) continue
                contacts.join(b, other, seriesConductance(k, bodies[other].conductance))
            }
        }
    }

    // ── Request every flux against the snapshot ──
    val transfers = Transfers(contacts.count + bodyCount, nodeCount)
    for (c in 0 until contacts.count) {
        val a = contacts.a[c]
        val b = contacts.b[c]
        val conductance = contacts.k[c]
        if (conductance <= 0L) continue
        if (capacity[a] <= 0L || capacity[b] <= 0L) continue
        val gap = kelvin[a] - kelvin[b]
        if (gap == 0) continue
        val hot = if (gap > 0) a else b
        val cold = if (gap > 0) b else a
        val dT = if (gap > 0) gap else -gap

        val wanted = conductance * dT
        // The most that can cross without overshooting equilibrium — the harmonic mean of the two
        // capacities. Same shape as [seriesConductance] and the same defect: a product of two
        // capacities is quadratic in the mass unit, and capacities are the *larger* of the two
        // quantities here, so this wrapped first. Reduced to a fraction before scaling.
        val harmonic = scaledRatio(capacity[hot], capacity[hot] + capacity[cold], capacity[cold])
        transfers.add(hot, cold, minOf(wanted, harmonic * dT))
    }

    // ── Radiation to space ──
    for (b in bodies.indices) {
        val body = bodies[b]
        var exposure = 0
        if (body.permeable) {
            if (!structure.isContained(body.tile)) exposure++
            continue
        }
        for (dir in Direction.ALL) {
            val next = grid.neighbour(body.tile, dir)
            // Off the grid counts: the rim opens onto space like any breach does.
            if (next == TileIndex.NONE || !structure.isContained(next)) exposure++
        }
        if (exposure == 0) continue
        val gap = kelvin[b] - Temperature.SPACE_KELVIN
        if (gap <= 0) continue
        val wanted = Material.RADIANCE * exposure * gap
        // Never radiate past the temperature of space.
        transfers.add(b, SPACE, minOf(wanted, gap.toLong() * capacity[b]))
    }

    // ── Settle ──
    for (node in 0 until nodeCount) {
        transfers.available[node] =
            (capacity[node] * (kelvin[node] - Temperature.SPACE_KELVIN)).coerceAtLeast(0L)
    }
    val delta = LongArray(nodeCount)
    val radiated = transfers.settle(delta)

    var toAir = 0L
    for (tile in grid.tiles) {
        val d = delta[bodyCount + tile.index]
        if (d == 0L) continue
        airEnergy[tile] += d
        toAir += d
    }
    val energy = LongArray(bodyCount) { bodies[it].energy + delta[it] }
    return SolidHeatStep(energy, radiated, toAir)
}

/** The acceptor id meaning "out of the world" — see the radiation block. */
private const val SPACE = -1

/** Bodies-per-tile, compressed row format: counts, prefix sum, ids. */
private class TileBodies(tileCount: Int, bodies: List<Body>) {
    private val start = IntArray(tileCount + 1)
    private val ids: IntArray
    private val tileCount = tileCount
    /** One slot per (layer, tile): a tile can hold one fitting of each conduit at once. */
    private val fitting = IntArray(tileCount * Conduit.entries.size) { -1 }

    init {
        for (b in bodies) start[b.tile.index + 1]++
        for (t in 1..tileCount) start[t] += start[t - 1]
        ids = IntArray(start[tileCount])
        val cursor = start.copyOf()
        for (i in bodies.indices) {
            ids[cursor[bodies[i].tile.index]++] = i
            val conduit = bodies[i].conduit
            if (bodies[i].slot == BodySlot.Fitting && conduit != null) {
                fitting[conduit.ordinal * tileCount + bodies[i].tile.index] = i
            }
        }
    }

    fun startOf(tile: TileIndex): Int = start[tile.index]
    fun endOf(tile: TileIndex): Int = start[tile.index + 1]
    fun id(slot: Int): Int = ids[slot]

    /** Fitting of a layer on a tile, or -1. */
    fun fittingAt(conduit: Conduit, tile: TileIndex): Int = fitting[conduit.ordinal * tileCount + tile.index]
}

/** The contact graph, as parallel arrays that grow. Pairs may repeat; their conductances add. */
private class Contacts {
    var a = IntArray(256)
    var b = IntArray(256)
    var k = LongArray(256)
    var count = 0

    fun join(x: Int, y: Int, conductance: Long) {
        if (conductance <= 0L) return
        if (count == a.size) {
            a = a.copyOf(count * 2)
            b = b.copyOf(count * 2)
            k = k.copyOf(count * 2)
        }
        a[count] = x
        b[count] = y
        k[count] = conductance
        count++
    }
}

/** Ask-first-pay-afterwards: requests tallied and scaled against available (above space temp) before applying. */
private class Transfers(capacity: Int, nodeCount: Int) {
    private val amount = LongArray(capacity)
    private val from = IntArray(capacity)
    private val to = IntArray(capacity)
    private var count = 0
    val available = LongArray(nodeCount)
    private val requested = LongArray(nodeCount)

    fun add(donor: Int, acceptor: Int, energy: Long) {
        if (energy <= 0L) return
        amount[count] = energy
        from[count] = donor
        to[count] = acceptor
        count++
        requested[donor] += energy
    }

    /** @return the energy that left the world entirely. */
    fun settle(delta: LongArray): Long {
        var escaped = 0L
        for (i in 0 until count) {
            val donor = from[i]
            var moving = amount[i]
            val asked = requested[donor]
            if (asked > available[donor]) moving = moving * available[donor] / asked
            if (moving <= 0L) continue
            delta[donor] -= moving
            if (to[i] == SPACE) escaped += moving else delta[to[i]] += moving
        }
        return escaped
    }
}

/**
 * The waste heat a machine sheds for working [mass] of material, in [Budget]'s energy unit.
 *
 * ### Why this is a function and not a multiplication
 *
 * [heatPerGram] is millijoules per **gram**, so turning it into an energy means crossing from the
 * mass unit into the energy unit — and every call site wrote it as a bare `mass * heatPerGram(m)`,
 * which is that conversion performed with the factor left out. It read correctly for as long as one
 * integer was one gram *and* one integer was one millijoule, which is to say for as long as the two
 * knobs were the same knob.
 *
 * Uncorrected it is the loudest failure in the rescale: mass units get a million times smaller, the
 * product does not, and a smelter pours a million times its own waste heat into the room. `HeatTest`
 * read the air two tiles from the furnace at **six million kelvin** — and still failed on the
 * monotonic-with-distance assertion rather than on the temperature, because both tiles were absurd
 * and the far one happened to be more absurd. Nothing in the message pointed here.
 *
 * The factor is [Budget.CAPACITY_DIVISOR], the same one a heat capacity needs, and for the same
 * reason: both constants are quoted per gram against a thousandth of a joule. Through [scaledRatio]
 * so that a whole rock cell's worth of mass times 400,000 does not wrap on the way.
 */
fun heatOfWorking(mass: Long, machine: DeckMachine?): Long =
    scaledRatio(mass, Budget.CAPACITY_DIVISOR, heatPerGram(machine))

/** Heat dumped into the machine per gram worked (millijoules per gram). Tied to work done, not time. */
fun heatPerGram(machine: DeckMachine?): Long = when (machine) {
    // 400 kJ/g. Smelting costs ~1 MJ/kg.
    is Processor -> 40_000L    // crushing and grinding
    is Extractor -> 20_000L
    // A rocket's waste heat is what the bell does not throw away. The exhaust's own energy is not
    // this: it leaves with the exhaust, or lands where the exhaust lands. See [Thruster].
    is Thruster -> 50_000L
    else -> 0L
}
