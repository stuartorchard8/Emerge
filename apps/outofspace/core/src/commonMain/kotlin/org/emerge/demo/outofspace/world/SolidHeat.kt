package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Concentrator
import org.emerge.demo.outofspace.world.machine.Thruster

/** Result of one solid conduction tick. [energy]: new energy per body. [radiated]: energy lost to space. [toAir]: net energy into atmosphere (negative = air heated solid). */
class SolidHeatStep(val energy: LongArray, val radiated: Long, val toAir: Long)

/**
 * Advances every solid body's temperature one tick: conduction between touching bodies, exchange with air,
 * and radiation from exposed surfaces.
 *
 * Contact rules: shared tiles always touch; casings touch across tile faces whether or not they hold
 * the air out; a body open to airflow meets the air of its own tile, one that is not meets the air
 * across its faces; contents (cargo, buffer stores) touch only what shares their tile; fittings link
 * to linked fittings (per [Segment.links]).
 *
 * Radiation: a casing sheds through each face that space reaches ([StructureMap.openToSpace]), so a
 * tile buried inside a footprint sheds nothing; a fitting or a lump sheds only if the tile it stands
 * on is itself open to space.
 *
 * Jacobi solve: all fluxes computed against a snapshot, then applied — avoids Gauss-Seidel leftward bias.
 * Each flux capped at equalisation amount; every node's conductances held to its own capacity, so
 * that several contacts cannot equalise a node past all of them at once (see [withinBudget]); total
 * outgoing energy scaled if requested exceeds available.
 */
fun stepSolidHeat(
    grid: Grid,
    bodies: List<Body>,
    structure: StructureMap,
    airEnergy: EnergyArray,
    thermalMass: LongArray,
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
        val tile = TileIndex(i)
        // ⚠️ **Two different quantities out of one.** The solver wants a *weight*, and wants it in
        // the same units as [Body.capacity] — so the divisor stays here, where a node too thin to
        // register is a node with nothing worth conducting and is skipped below anyway. The
        // *temperature* must not be formed that way: see [kelvinOf], and [heatCapacityAt] for what
        // pre-dividing did to thin gas.
        capacity[bodyCount + i] = thermalMass[i] / Budget.CAPACITY_DIVISOR
        kelvin[bodyCount + i] = kelvinOf(airEnergy[tile], thermalMass[i])
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

        if (!body.preventAirflow) {
            // Air shares this tile, so that is where the body meets it.
            contacts.join(b, bodyCount + body.tile.index, seriesConductance(k, Joint.AIR_FILM))
        }
        // Casings reach across their tile faces, whether or not they hold the air out — that is
        // what the airflow/thoroughfare split bought. A smelter standing in a room is bolted to the
        // deck plate beside it exactly as a wall is bolted to the next wall; being open to the air
        // is a separate fact about it.
        //
        // ⚠️ **Casings only.** A face is a property of the thing that is *fixed at* the tile, and
        // the two contents slots are not: a lump does not conduct into the tile in front of it, it
        // conducts into what it is sitting on and what it is sitting in, and a buffer is inside a
        // machine with no exposed surface of its own. Let a [BodySlot.BufferStore] take faces and a
        // decomposer's charge is jacketed by nine tiles of firebrick that outweigh it fifty to one
        // — it never reaches its setpoint and nothing ever calcines. See [HEATER_POWER], which is
        // derived against a charge heated *in* the chamber. Fittings are out for their own reason:
        // heat runs along a rail by its drawn links, below, not by adjacency.
        for (dir in Direction.ALL) {
            val next = grid.neighbour(body.tile, dir)
            if (next == TileIndex.NONE) continue
            if (body.slot == BodySlot.DeckStore) {
                // Once per face, not per pair.
                for (i in tiles.startOf(next) until tiles.endOf(next)) {
                    val other = tiles.id(i)
                    if (other <= b || bodies[other].slot != BodySlot.DeckStore) continue
                    contacts.join(b, other, seriesConductance(k, bodies[other].conductance))
                }
            }
            // ⚠️ A body that holds the air out has none in its own tile, so the face is its only
            // way to the atmosphere. Drop this and a hot wall warms nothing but its neighbours and
            // space — see `BodyHeatTest.a hot wall warms the air in the room`.
            if (body.preventAirflow && structure.isContained(next)) {
                contacts.join(b, bodyCount + next.index, seriesConductance(k, Joint.AIR_FILM))
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

    // ── What each node has been asked to conduct, all contacts together ──
    //
    // The cap below is computed against one pair at a time, and that is the exact limit for a pair
    // that is alone in the world. A node with several contacts gets one such licence *per contact*,
    // each of them ignorant of the others, and the sum of them carries it past every neighbour it
    // has — see [withinBudget], which is the correction.
    val asked = LongArray(nodeCount)
    for (c in 0 until contacts.count) {
        asked[contacts.a[c]] += contacts.k[c]
        asked[contacts.b[c]] += contacts.k[c]
    }

    // ── Request every flux against the snapshot ──
    val transfers = Transfers(contacts.count + bodyCount, nodeCount)
    for (c in 0 until contacts.count) {
        val a = contacts.a[c]
        val b = contacts.b[c]
        // Scaled by the tighter of the two ends, so neither can be asked for more than it holds.
        val conductance =
            withinBudget(withinBudget(contacts.k[c], capacity[a], asked[a]), capacity[b], asked[b])
        if (conductance <= 0L) continue
        if (capacity[a] <= 0L || capacity[b] <= 0L) continue
        val gap = kelvin[a] - kelvin[b]
        if (gap == 0) continue
        val hot = if (gap > 0) a else b
        val cold = if (gap > 0) b else a
        val dT = if (gap > 0) gap else -gap

        val wanted = conductance * dT
        // The most that can cross without overshooting equilibrium — the harmonic mean of the two
        // capacities. ⚠️ It is the limit for *this pair*, and says nothing about the others meeting
        // at either end; [withinBudget] above is what makes the two statements add up.
        // Same shape as [seriesConductance] and the same defect: a product of two
        // capacities is quadratic in the mass unit, and capacities are the *larger* of the two
        // quantities here, so this wrapped first. Reduced to a fraction before scaling.
        val harmonic = scaledRatio(capacity[hot], capacity[hot] + capacity[cold], capacity[cold])
        transfers.add(hot, cold, minOf(wanted, harmonic * dT))
    }

    // ── Radiation to space ──
    for (b in bodies.indices) {
        val body = bodies[b]
        var exposure = 0
        if (body.slot == BodySlot.DeckStore) {
            // A casing radiates from its faces, and **whether it lets the air through has nothing
            // to do with it** — that was the old rule and it made a machine's cooling depend on a
            // fact about gas. What decides a face is whether space reaches what is on the other
            // side of it, which [StructureMap.openToSpace] answers with one perimeter for the whole
            // ship: a tile buried in a footprint faces its own neighbours and sheds nothing.
            for (dir in Direction.ALL) {
                val next = grid.neighbour(body.tile, dir)
                // Off the grid counts: the rim opens onto space like any breach does.
                if (next == TileIndex.NONE || structure.openToSpace(next)) exposure++
            }
        } else {
            // ⚠️ Fittings and contents have no faces, for the reason they take no face contacts
            // above: a lump is a heap on a tile, not a surface bolted to one. It sheds if the tile
            // it is standing on is open to the sky, and that is all.
            //
            // ⚠️ An `else` and not an early `continue`. The `continue` that used to be here counted
            // the exposure and then skipped the transfer that spends it, so a body standing in
            // vacuum shed nothing at all — invisible while only fittings were permeable, because a
            // rail in space is a rare thing.
            if (structure.openToSpace(body.tile)) exposure++
        }
        if (exposure == 0) continue
        val gap = kelvin[b] - Temperature.SPACE_KELVIN
        if (gap <= 0) continue
        val wanted = Joint.RADIANCE * exposure * gap
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

/**
 * [conductance], reduced so that everything asked of a node fits inside what the node can hold.
 *
 * ### The stability condition is about a node, not a pair
 *
 * An explicit step moves `k·ΔT` across a contact, and the pair-wise cap beside the call site holds
 * that to the energy that brings *those two* to a common temperature — which is exactly right, and
 * is right only while the pair is the whole world. Give a node three contacts and it collects three
 * separate licences to equalise, each computed in ignorance of the other two, and it lands past
 * every neighbour it has. The sign of every gap flips, the next step overshoots further, and the
 * node ends up swapping temperatures with the tile beside it for ever.
 *
 * That is not a hypothetical. A copper cable in the starter vessel shares its tile with a machine
 * casing and links to two more lengths of itself, and those three caps come to about twice its own
 * capacity. It settled into a permanent **3 K ↔ 790 K** swap with its neighbour — floor and ceiling
 * being [Temperature.SPACE_KELVIN] and whatever the neighbour had — while the air it ran through
 * sat at 340 K.
 *
 * What an explicit diffusion step actually has to satisfy is `Σk ≤ C` at each node: the conductances
 * meeting there, summed, may not exceed the capacity, or the node can be asked to shed more than it
 * has in one go. So each node is given a budget of its own capacity, and a contact is scaled by the
 * tighter of its two ends. Applied to the conductance and not to the settled flux, so it is
 * symmetric in the pair — the two ends still agree on one number, and the energy ledger cannot tell
 * that anything happened.
 *
 * ### Where it binds, and where it is a no-op
 *
 * A material's conductance-to-capacity ratio is `100 / conductanceCentiTicks`: 0.001 for firebrick,
 * 0.02 for titanium, 0.04 for steel, 0.25 for iron and **1.72** for copper. Only copper is stiffer
 * than the step that integrates it, which is why the fluctuation was a copper phenomenon and
 * nothing else in the vessel ever showed it.
 *
 * ⚠️ **A node's contacts are not only its own layer's**, and the count matters more than any single
 * ratio does. Everything standing on a tile touches everything else standing on it — that is the
 * first rule in [stepSolidHeat] and it is deliberately blind to which layer a body came off. So a
 * crossing where rail, pipe, power and signal all run over one machine casing is six cross-layer
 * contacts and four to the casing, on top of each run's own links and the air. Measured against
 * `Σk / C` at such a tile: **rail 1.45, pipe 5.07, power and signal 11.53**. In the starter vessel,
 * where the layers do not stack, rail sits at 0.29–0.83 and is left entirely alone, while the
 * signal run reaches 4.70.
 *
 * So the budget is a no-op for every rail network that does not cross another layer, and takes
 * about a third off one that does — which is the thing to look at first if track conduction ever
 * reads slow.
 *
 * ⚠️ **Radiation is deliberately outside the budget.** It is capped against the gap to space and
 * scaled again against the energy the body has above space, and [Joint.RADIANCE] is one part in
 * six and a half thousand of a hull plate's capacity — far too small to destabilise anything, and
 * folding it in would change how fast every ship in every save cools.
 */
private fun withinBudget(conductance: Long, capacity: Long, asked: Long): Long =
    if (asked <= capacity) conductance else scaledRatio(capacity, asked, conductance)

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
    is Concentrator -> 2_000L    // crushing and grinding
    is Extractor -> 2_000L
    // A rocket's waste heat is what the bell does not throw away. The exhaust's own energy is not
    // this: it leaves with the exhaust, or lands where the exhaust lands. See [Thruster].
    is Thruster -> 10_000L
    else -> 0L
}
