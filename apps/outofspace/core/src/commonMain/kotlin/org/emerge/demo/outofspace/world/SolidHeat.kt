package org.emerge.demo.outofspace.world

/**
 * What one tick of solid conduction did.
 *
 * [joules] is the new energy of each body, in the same order [bodiesOf] returned them. [radiated] is
 * what left for space, and [toAir] is the **net** energy that crossed into the atmosphere — negative
 * when the air was the warmer side and heated the fabric instead.
 *
 * [toAir] exists because coupling the two fields joins two ledgers that were previously independent,
 * and a transfer that is not counted is indistinguishable from a leak in both of them at once. With
 * it, each side still closes on its own: the solids' balance gains a term for what they gave the
 * air, and the air's balance gains the matching one. See [VesselState.solidToAirJoules].
 */
class SolidHeatStep(val joules: LongArray, val radiated: Long, val toAir: Long)

/**
 * Advances every solid body's temperature one tick: conduction between things that touch, exchange
 * with the air, and radiation from whatever is exposed to space.
 *
 * ### What touches what
 *
 * This is the whole model, and it is a contact graph rather than a stencil over the grid.
 *
 *  - **Anything sharing a tile touches.** A rail threaded under a smelter, a pipe crossing a
 *    machine, two conduit layers on the same tile: all in contact. It is not physically grounded —
 *    three objects are not really occupying one cubic metre — but a tile is a bookkeeping unit, not
 *    a volume, and the behaviour it produces is the right one: things built on top of each other
 *    share their heat.
 *  - **Impermeable bodies touch across tile faces.** A wall conducts into the wall beside it and
 *    into the machine beside that, because both fill their tiles and the tiles abut. Contact is
 *    counted **per face**, so two footprints meeting along five tiles conduct five times as fast as
 *    two meeting at one — which is what a bigger joint is.
 *  - **Impermeable bodies touch the air in the tiles beside them.** That is the only way heat
 *    reaches a room: through its walls and the casings of what stands in it.
 *  - **Permeable fittings touch only their own tile** — what shares it, and the air in it. A rail is
 *    threaded through a tile that is otherwise open, so its neighbour across the tile face is air
 *    that the fluid pass is already moving, and pretending the rail also touches the *next* tile's
 *    contents would give the fitting a second, faster path that bulk flow does not have.
 *  - **Fittings touch the fittings they are joined to.** Two tiles of track sitting beside each
 *    other are not connected; two the player *drew a line through* are. Heat follows the same rule
 *    the material does, because it is the same rule — see [Segment.links]. A long copper run is
 *    therefore a thermal short circuit along its length and nothing at all across it, which is what
 *    a cable is and what makes where you route it a decision.
 *
 * A bridge needs no case of its own here. It spans three tiles, and the segments it joins to sit on
 * the two ends, so the shared-tile rule already connects it to the run at either side.
 *
 * ### Jacobi, and pay-afterwards
 *
 * Every flux is computed against one snapshot of the temperatures and applied afterwards, so the
 * answer cannot depend on the order the contact list happens to be in. Reading and writing the live
 * array instead is a Gauss-Seidel sweep, and a Gauss-Seidel sweep over a row-major grid is a leftward
 * bias that conserves energy perfectly while being visibly, unfixably asymmetric. This project has
 * shipped that bug three times — in `applySpeciesDrift`, in the gas heat clamp, and in the field this
 * function replaces — so the snapshot is not a nicety.
 *
 * Each flux is capped at the amount that would *equalise* the two sides, and then every node's total
 * outgoing energy is scaled down if it was asked for more than it holds above the temperature of
 * space. The cap is what stops a large gap plus a coarse timestep sending more heat than exists and
 * ringing; the scaling is what makes a tile drained by six contacts at once still conserve. Both are
 * against the snapshot, never the live array, for the reason above.
 *
 * [airJoules] is edited in place. [airCapacity] must be from the same instant.
 */
fun stepSolidHeat(
    grid: Grid,
    bodies: List<Body>,
    structure: StructureMap,
    rails: List<Segment?>,
    airJoules: LongArray,
    airCapacity: LongArray,
): SolidHeatStep {
    val bodyCount = bodies.size
    val tileCount = grid.size
    if (bodyCount == 0) return SolidHeatStep(LongArray(0), 0L, 0L)

    // ── Nodes: every body, then every tile of air. One array so a contact needs no idea which
    // kind of thing is on either end of it, which is what keeps solid-to-air from being a
    // second, subtly different exchange rule. ──
    val nodeCount = bodyCount + tileCount
    val capacity = LongArray(nodeCount)
    val kelvin = IntArray(nodeCount)
    for (b in 0 until bodyCount) {
        capacity[b] = bodies[b].capacity
        kelvin[b] = bodies[b].kelvin
    }
    for (t in 0 until tileCount) {
        val c = airCapacity[t]
        capacity[bodyCount + t] = c
        kelvin[bodyCount + t] =
            if (c <= 0L) Temperature.AMBIENT_KELVIN else (airJoules[t] / c).toInt()
    }

    val tiles = TileBodies(grid.size, bodies)
    val contacts = Contacts()

    for (b in bodies.indices) {
        val body = bodies[b]
        val k = body.material.conductance

        for (tile in body.tiles) {
            // Everything else on this tile, whatever layer it is on.
            for (i in tiles.startOf(tile) until tiles.endOf(tile)) {
                val other = tiles.id(i)
                if (other <= b) continue // each unordered pair once
                contacts.join(b, other, seriesConductance(k, bodies[other].material.conductance))
            }

            if (body.permeable) {
                // A fitting reaches the air in its own tile and nothing further.
                contacts.join(b, bodyCount + tile, seriesConductance(k, Material.AIR_FILM))
                continue
            }

            // An impermeable body fills its tile, so it reaches across the faces of it.
            for (dir in Direction.ALL) {
                val next = grid.neighbour(tile, dir)
                if (next < 0) continue
                if (structure.isImpermeable(next)) {
                    // The solid on the far side, if it is not this same body wrapping around.
                    // Registered once per *face* rather than once per pair: a five-tile joint
                    // conducts five times as hard as a one-tile one, which is what a bigger joint is.
                    for (i in tiles.startOf(next) until tiles.endOf(next)) {
                        val other = tiles.id(i)
                        if (other <= b || bodies[other].permeable) continue
                        contacts.join(b, other, seriesConductance(k, bodies[other].material.conductance))
                    }
                } else if (structure.isContained(next)) {
                    contacts.join(b, bodyCount + next, seriesConductance(k, Material.AIR_FILM))
                }
            }
        }

        // Track joined to track: heat runs along a drawn line and not across an undrawn one.
        if (body.slot == BodySlot.Fitting) {
            val segment = rails[body.at]
            if (segment != null) {
                for (dir in Direction.ALL) {
                    if (!segment.linkedTo(dir)) continue
                    val next = grid.neighbour(body.at, dir)
                    if (next < 0) continue
                    val other = tiles.fittingAt(next)
                    if (other < 0 || other <= b) continue
                    contacts.join(b, other, seriesConductance(k, bodies[other].material.conductance))
                }
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
        // The most that can cross before the two are equal: dT * Ca*Cb / (Ca+Cb).
        //
        // The harmonic mean is computed *first* and the gap applied to it afterwards. The other
        // order — gap times one capacity, then divided — floors to zero whenever one side is much
        // smaller than the other, which is precisely the copper-fitting-against-a-hull case, and
        // silently freezes exactly the contacts the material model was built to make interesting.
        // The mean is never larger than the smaller capacity, so the multiply cannot overflow.
        val harmonic = capacity[hot] * capacity[cold] / (capacity[hot] + capacity[cold])
        transfers.add(hot, cold, minOf(wanted, harmonic * dT))
    }

    // ── Radiation: whatever is exposed to space loses heat to it, permanently ──
    for (b in bodies.indices) {
        val body = bodies[b]
        var exposure = 0
        for (tile in body.tiles) {
            if (body.permeable) {
                // A fitting outside the hull is simply in space; a fitting inside it is not exposed
                // at all, because the air and the walls are in the way.
                if (!structure.isContained(tile)) exposure++
                continue
            }
            for (dir in Direction.ALL) {
                val next = grid.neighbour(tile, dir)
                // Off the grid counts: the rim opens onto space like any breach does.
                if (next < 0 || !structure.isContained(next)) exposure++
            }
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
    for (t in 0 until tileCount) {
        val d = delta[bodyCount + t]
        if (d == 0L) continue
        airJoules[t] += d
        toAir += d
    }
    val joules = LongArray(bodyCount) { bodies[it].joules + delta[it] }
    return SolidHeatStep(joules, radiated, toAir)
}

/** The acceptor id meaning "out of the world" — see the radiation block. */
private const val SPACE = -1

/**
 * Which bodies stand on which tile, as a compressed row: counts, a prefix sum, then the ids.
 *
 * A list per tile would allocate a thousand small objects every tick on the one grid-sized structure
 * that is rebuilt from scratch each time. Two passes over a short body list costs nothing and the
 * ids come out in body order, which is what makes the contact walk deterministic.
 */
private class TileBodies(tileCount: Int, bodies: List<Body>) {
    private val start = IntArray(tileCount + 1)
    private val ids: IntArray
    private val fitting = IntArray(tileCount) { -1 }

    init {
        for (b in bodies) for (t in b.tiles) start[t + 1]++
        for (t in 1..tileCount) start[t] += start[t - 1]
        ids = IntArray(start[tileCount])
        val cursor = start.copyOf()
        for (i in bodies.indices) {
            for (t in bodies[i].tiles) ids[cursor[t]++] = i
            if (bodies[i].slot == BodySlot.Fitting) fitting[bodies[i].at] = i
        }
    }

    fun startOf(tile: Int): Int = start[tile]
    fun endOf(tile: Int): Int = start[tile + 1]
    fun id(slot: Int): Int = ids[slot]

    /** The conduit fitting on a tile, or -1. Track joins to track, so this is what a link resolves to. */
    fun fittingAt(tile: Int): Int = fitting[tile]
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

/**
 * Ask-first-pay-afterwards, the same discipline the mass, momentum and gas-heat passes keep.
 *
 * A node can be drained by every contact it has at once and every flux was computed against one
 * snapshot, so the requests have to be totalled and scaled before any of them is honoured. The scale
 * is against what the node holds *above the temperature of space*, which is the energy it actually
 * has to give.
 */
private class Transfers(capacity: Int, nodeCount: Int) {
    private val amount = LongArray(capacity)
    private val from = IntArray(capacity)
    private val to = IntArray(capacity)
    private var count = 0
    val available = LongArray(nodeCount)
    private val requested = LongArray(nodeCount)

    fun add(donor: Int, acceptor: Int, joules: Long) {
        if (joules <= 0L) return
        amount[count] = joules
        from[count] = donor
        to[count] = acceptor
        count++
        requested[donor] += joules
    }

    /** @return the joules that left the world entirely. */
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
 * How much heat a machine dumps into itself per gram it works on, in the millijoules [Material]
 * documents.
 *
 * Tying heat to *work done* rather than to a per-second rate means it needs no clock and no carry of
 * its own: the material flow is already modelled exactly, so the heat that flow implies is exact
 * too. A throttled machine warms its surroundings proportionally less, for free.
 *
 * It goes into the **machine**, not the tile. That is the change the body model buys: a furnace's
 * waste heat is now in the furnace, so it has to conduct out through firebrick and into the air
 * before the room feels it, and lagging or exposing the thing actually changes the answer.
 */
fun heatPerGram(machine: Machine?): Long = when (machine) {
    // Four hundred joules a gram. Ten times what the per-tile field charged, and the increase is a
    // consequence of the model rather than a tuning whim: the same furnace used to be one tile's
    // worth of thermal mass and is now twenty-five tiles of firebrick, which is the heaviest thing
    // in the game. At the old figure it warmed by eight kelvin in two minutes of full production,
    // which is both wrong — smelting iron genuinely costs of order a megajoule a kilogram — and
    // inert, since nothing downstream can feel eight kelvin.
    //
    // Where it settles is set by the casing, not by this: at full rate a furnace loses about
    // 104 kJ/K/tick through its twenty exposed faces, so it comes to rest a few hundred kelvin over
    // the room. Hot enough to matter, not hot enough to be the only thing that matters.
    is Smelter -> 400_000L
    is Processor -> 40_000L    // crushing and grinding
    is Miner -> 20_000L
    else -> 0L
}
