package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.apportion
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * The air, tile by tile: grams of each gas species in every enclosed tile.
 *
 * Stored as one flat `LongArray` of `tiles × species` rather than a `Mixture` per tile. A mixture per
 * tile would allocate a thousand small objects every tick, and this is the field that will be touched
 * most often once life support and combustion exist.
 *
 * Grams again, and integers again, for the reasons [Mixture] already gives. Pressure here is simply
 * the total mass in a tile: every tile is the same volume, so mass *is* density, and gas flows from
 * dense to sparse. Temperature is deliberately not in it yet — coupling `P ∝ mT` is what gives
 * convection, and it deserves its own pass rather than being smuggled in with the plumbing.
 */
class AirField(private val grams: LongArray) {

    fun gramsOf(tile: Int, species: Species): Long = grams[tile * Species.COUNT + species.ordinal]

    /** Total gas mass in a tile. With uniform tile volume this is the pressure. */
    fun pressureAt(tile: Int): Long {
        var sum = 0L
        val base = tile * Species.COUNT
        for (s in Species.GASES) sum += grams[base + s.ordinal]
        return sum
    }

    /** The tile's air as a [Mixture], for the inspector. Allocates — not for the hot path. */
    fun mixtureAt(tile: Int): Mixture {
        val out = LongArray(Species.COUNT)
        val base = tile * Species.COUNT
        for (s in Species.GASES) out[s.ordinal] = grams[base + s.ordinal]
        return Mixture.ofGrams(out)
    }

    val totalGrams: Long get() {
        var sum = 0L
        for (g in grams) sum += g
        return sum
    }

    fun copyGrams(): LongArray = grams.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || (other is AirField && grams.contentEquals(other.grams))

    override fun hashCode(): Int = grams.contentHashCode()

    companion object {
        /**
         * What a sealed tile holds at one atmosphere: a kilogram of ordinary air, roughly Earth's
         * mix by mass. The numbers matter less than their ratios, which are what the inspector shows
         * and what life support will have to hold steady.
         */
        val AMBIENT_AIR: Mixture = Mixture.of(
            Species.Nitrogen to 755L,
            Species.Oxygen to 232L,
            Species.CarbonDioxide to 13L,
        )

        /**
         * How many relaxation passes a tick runs. Gas equalises quickly — a door opening is not a
         * slow event — but one pass may only move [STABLE_SHARE] of a gradient before the scheme
         * goes unstable, so speed has to come from repeating a stable pass rather than from taking a
         * bigger one.
         *
         * This is the honest shape of the thing. It used to be a per-second flow rate divided by the
         * tick rate, with the pass count derived to keep the two agreeing; the arithmetic worked out
         * to exactly this and no more, so all that machinery was computing a constant.
         */
        const val FLOW_PASSES = 15

        /**
         * The largest share of a gradient one edge may move in a single pass.
         *
         * The cap used to be **half** the gap, which is the right limit for a *pair* of tiles and the
         * wrong one for a grid. Every edge is computed against the same snapshot and applied together,
         * so a tile surrounded by four emptier ones gives away half a gradient four times over in one
         * tick, and the whole field flips: high tiles become low, low become high, and the room sits
         * in a permanent checkerboard, sloshing back and forth forever without equalising.
         *
         * It never showed while the sim ran at 60Hz, because the flow rate divided by 60 was a tenth
         * of the gap and a tenth is stable — the half-gap cap was there but never binding. Dropping
         * the tick rate to 4 made the raw flux one and a half *times* the gap, so the cap bound on
         * every edge of every tile at once and the scheme went straight past its stability limit.
         * That was the clearest evidence that dividing by the tick rate was the wrong idea: the
         * stability of a numerical scheme is a fact about the grid and the step, and letting a
         * display setting choose the step meant a display setting could break the physics.
         *
         * The theoretical limit for a lattice where a tile has four neighbours is an eighth, and a
         * tenth is used instead because *at* the limit the field is only marginally stable: it stops
         * diverging but rings, resting in a ±6 shimmer rather than settling. A tenth damps, which is
         * measurable and is the number 60Hz was accidentally running at all along.
         *
         * The point is that this is a statement about the **grid**, not about the tick rate, so it
         * holds at whatever rate the game is run at.
         */
        const val STABLE_SHARE = 10L

        /**
         * How much of the wrong-way-up gas trades places each tick, as the exact fraction
         * [STRATIFY]/[STRATIFY_PER]. Three quarters, so a room sorts itself out in a few ticks.
         */
        const val STRATIFY = 3L

        /** What [STRATIFY] is out of. See its note. */
        const val STRATIFY_PER = 4L

        fun of(grams: LongArray): AirField = AirField(grams.copyOf())

        /** Every enclosed tile filled with [AMBIENT_AIR]; vacuum left empty. */
        fun ambient(grid: Grid, structure: StructureMap): AirField {
            val grams = LongArray(grid.size * Species.COUNT)
            for (tile in 0 until grid.size) {
                if (!structure.isContained(tile) || structure.isImpermeable(tile)) continue
                val base = tile * Species.COUNT
                for (s in Species.GASES) grams[base + s.ordinal] = AMBIENT_AIR[s]
            }
            return AirField(grams)
        }
    }
}

/**
 * Advances the atmosphere one tick: flow, then stratification, then venting.
 *
 * Flow is computed from the old pressures into a delta buffer and applied afterwards, so — as with
 * heat — the result cannot depend on the order tiles are visited. Gas moved between tiles is a
 * *proportional sample* of the source ([Mixture.take] over the same [apportion]), so a draught
 * carries the room's actual mix rather than skimming one gas off the top.
 *
 * A residual gradient of one gram between neighbours is the resting state, not a bug: a difference
 * of one cannot be split in half without overshooting, so integer pressure settles into a ±1
 * staircase rather than a perfectly flat field. At a kilogram per tile that is a tenth of a percent.
 *
 * [grams] is the tick's working air, **edited in place**: the same array the edit pass has already
 * written to, so a hull put down this tick has moved its air out of the way before the flow runs.
 * The returned [AirField] is a copy, so the state handed back does not alias the scratch.
 *
 * @return the new field and the grams vented to space, which is the only place air legitimately goes.
 */
fun stepAir(
    grid: Grid,
    structure: StructureMap,
    grams: LongArray,
    gravity: Frac2,
): Pair<AirField, Long> {
    // ── Flow: dense to sparse, each edge once ──
    //
    // Sub-stepped, because one pass can only move [AirField.STABLE_SHARE] of a gradient before the
    // scheme goes unstable, and a tick is meant to be a visible amount of equalising. So a tick is
    // [AirField.FLOW_PASSES] stable passes rather than one unstable big one.
    val moves = ArrayList<LongArray>()   // (from, to, amount) collected, then applied
    repeat(AirField.FLOW_PASSES) {
        val pressure = LongArray(grid.size) { tile ->
            var sum = 0L
            val base = tile * Species.COUNT
            for (s in Species.GASES) sum += grams[base + s.ordinal]
            sum
        }
        moves.clear()
        for (tile in 0 until grid.size) {
            if (!structure.isPermeable(tile)) continue
            for (dir in FLOW_DIRS) {
                val other = grid.neighbour(tile, dir)
                if (other < 0 || !structure.isPermeable(other)) continue
                val gap = pressure[tile] - pressure[other]
                if (gap == 0L) continue
                val from = if (gap > 0) tile else other
                val magnitude = if (gap > 0) gap else -gap
                // The lattice stability limit, not the pairwise one — see [AirField.STABLE_SHARE].
                var flux = magnitude / AirField.STABLE_SHARE
                // That division floors, so a small gradient rounds to no flow at all and freezes
                // exactly where it is — a room visibly stops equalising with a permanent staircase
                // across it.
                if (flux == 0L && magnitude >= 2L) flux = 1L
                flux = minOf(flux, pressure[from])          // never more than is there
                if (flux <= 0L) continue
                moves.add(longArrayOf(from.toLong(), (if (gap > 0) other else tile).toLong(), flux))
            }
        }
        for (move in moves) {
            val from = move[0].toInt()
            val to = move[1].toInt()
            transferGas(grams, from, to, move[2])
        }
    }

    stratifyColumns(grid, grams, gravity)

    // ── Venting: anything not enclosed has no air, and what it had is gone ──
    var vented = 0L
    for (tile in 0 until grid.size) {
        if (!grid.isEdge(tile)) continue

        val base = tile * Species.COUNT
        for (s in Species.GASES) {
            vented += grams[base + s.ordinal]
            grams[base + s.ordinal] = 0L
        }
    }
    return AirField.of(grams) to vented
}

/**
 * Lets heavy gas sink and light gas rise, one vertical pair at a time.
 *
 * **This is the one function permitted to assume gravity is axis-aligned** (see the plan's §3). It
 * walks vertical neighbours directly, which is only meaningful when "down" is a grid axis. When
 * acceleration-derived gravity arrives, this is the single thing that gets replaced by a general
 * flux along an arbitrary vector — everything else already takes gravity as a parameter and will not
 * notice.
 *
 * Stratification is a **swap**: an equal mass of the heavier gas goes down as the lighter goes up. It
 * therefore moves composition around without moving pressure, which is what stops it fighting the
 * flow pass, and it conserves each species exactly.
 */
fun stratifyColumns(
    grid: Grid,
    grams: LongArray,
    gravity: Frac2,
) {
    // A diagonal or zero gravity means no stratification rather than a wrong one. Shared with
    // debris settling, which needs the identical answer and must not be able to disagree.
    val down: Direction = downDirection(gravity) ?: return

    // Heaviest first, so each pair is considered once in the order (heavy, lighter).
    val byWeight = Species.GASES.sortedByDescending { it.molarMass }

    for (tile in 0 until grid.size) {
        val below = grid.neighbour(tile, down)
        if (below < 0) continue // Skips comparison off the grid

        val upper = tile * Species.COUNT
        val lower = below * Species.COUNT
        for (h in byWeight.indices) {
            for (l in h + 1 until byWeight.size) {
                val heavy = byWeight[h]
                val light = byWeight[l]
                // Swap what is "the wrong way up": heavy gas above, light gas below.
                val available = minOf(grams[upper + heavy.ordinal], grams[lower + light.ordinal])
                val swap = AirField.STRATIFY * available / AirField.STRATIFY_PER
                if (swap <= 0L) continue
                grams[upper + heavy.ordinal] -= swap
                grams[lower + heavy.ordinal] += swap
                grams[lower + light.ordinal] -= swap
                grams[upper + light.ordinal] += swap
            }
        }
    }
}

/**
 * Tries to shove the air out of an area that is about to stop being air, and reports whether it
 * could.
 *
 * A solid tile is not part of the atmosphere: [stepAir] skips impermeable tiles entirely, so air
 * left inside one is neither flowing nor vented — it is simply frozen, invisible, and waiting to
 * reappear the moment the thing on top of it is removed. Building over a room has to *move* that air
 * rather than swallow it, and it has to move it without deleting a gram, because the vessel's air
 * ledger is a conservation invariant and the player's own edits are not exempt from it.
 *
 * **All or nothing.** If any of [area] holds air that cannot reach open space, nothing is moved and
 * this returns `false` — the caller's job is then to refuse the build. That is the honest rule: the
 * alternative is either destroying the air or leaving it stranded under the new machine, and both of
 * those are the bug this exists to prevent. An area holding no air at all succeeds trivially, so
 * building in vacuum or in an evacuated room is never blocked.
 *
 * Where the air goes is decided by **distance through the area to each way out**. The exits are the
 * permeable tiles touching [area]; a breadth-first walk inward from them gives every tile of the
 * area its distance to each one, and a tile's air is split between the exits in inverse proportion
 * to those distances. So air at the far end of a long machine leaves by the near door rather than
 * being teleported evenly to both ends, and a tile with only one way out sends everything there.
 *
 * The walk is confined to [area] on purpose: distance is how far the air has to travel *through the
 * space being taken away*, which is what decides which way it gets pushed. Once it is out it is the
 * flow pass's problem, and the flow pass runs on the same array immediately afterwards.
 *
 * [permeable] is asked about tiles rather than a [StructureMap] being passed, because this runs
 * *during* the edit pass, before the structure for the tick has been derived.
 */
fun tryDisplaceAir(
    grid: Grid,
    grams: LongArray,
    area: Collection<Int>,
    permeable: (Int) -> Boolean,
): Boolean {
    val order = area.toList()
    val slotOf = HashMap<Int, Int>(order.size * 2)
    for (i in order.indices) slotOf[order[i]] = i

    // ── The ways out: permeable tiles touching the area, in a fixed order ──
    val exits = ArrayList<Int>()
    val exitSlot = HashMap<Int, Int>()
    for (tile in order) {
        for (dir in Direction.ALL) {
            val other = grid.neighbour(tile, dir)
            if (other < 0 || other in slotOf || other in exitSlot || !permeable(other)) continue
            exitSlot[other] = exits.size
            exits.add(other)
        }
    }
    if (exits.isEmpty()) return false

    // ── Distance from every exit to every tile of the area, walking only through the area ──
    val distance = Array(exits.size) { IntArray(order.size) { UNREACHABLE } }
    val queue = ArrayDeque<Int>()
    for (e in exits.indices) {
        val d = distance[e]
        queue.clear()
        for (dir in Direction.ALL) {
            val first = grid.neighbour(exits[e], dir)
            val slot = slotOf[first] ?: continue
            if (d[slot] > 1) { d[slot] = 1; queue.addLast(slot) }
        }
        while (queue.isNotEmpty()) {
            val slot = queue.removeFirst()
            for (dir in Direction.ALL) {
                val next = grid.neighbour(order[slot], dir)
                val nextSlot = slotOf[next] ?: continue
                if (d[nextSlot] > d[slot] + 1) { d[nextSlot] = d[slot] + 1; queue.addLast(nextSlot) }
            }
        }
    }

    // ── Work out every move before making any, so a refusal leaves the field untouched ──
    val moved = LongArray(exits.size * Species.COUNT)
    val weights = LongArray(exits.size)
    for (slot in order.indices) {
        val base = order[slot] * Species.COUNT
        var total = 0L
        for (s in Species.GASES) total += grams[base + s.ordinal]
        if (total <= 0L) continue

        var reachable = false
        for (e in exits.indices) {
            val d = distance[e][slot]
            // Inverse distance, scaled so the near exit outweighs the far one without a fraction.
            weights[e] = if (d == UNREACHABLE) 0L else DISPLACE_WEIGHT / d
            if (weights[e] > 0L) reachable = true
        }
        // Air with nowhere to go. Refuse, rather than delete it or bury it.
        if (!reachable) return false

        for (s in Species.GASES) {
            val share = apportion(weights, grams[base + s.ordinal])
            for (e in exits.indices) moved[e * Species.COUNT + s.ordinal] += share[e]
        }
    }

    for (slot in order.indices) {
        val base = order[slot] * Species.COUNT
        for (s in Species.GASES) grams[base + s.ordinal] = 0L
    }
    for (e in exits.indices) {
        val base = exits[e] * Species.COUNT
        for (s in Species.GASES) grams[base + s.ordinal] += moved[e * Species.COUNT + s.ordinal]
    }
    return true
}

/** Stands in for "no path from this exit to this tile" — larger than any real distance. */
private const val UNREACHABLE = Int.MAX_VALUE

/**
 * The numerator of the inverse-distance weighting. Big enough that the *ratios* between distances
 * survive the integer division — at a distance of a hundred the weight is still four figures — and
 * small enough that summing one per exit cannot overflow.
 */
private const val DISPLACE_WEIGHT = 1L shl 20

/** Moves [amount] grams from one tile to another as a proportional sample of the source's mix. */
private fun transferGas(grams: LongArray, from: Int, to: Int, amount: Long) {
    val fromBase = from * Species.COUNT
    val toBase = to * Species.COUNT
    val weights = LongArray(Species.COUNT)
    for (s in Species.GASES) weights[s.ordinal] = grams[fromBase + s.ordinal]
    val share = apportion(weights, amount)
    for (s in Species.GASES) {
        val moved = minOf(share[s.ordinal], grams[fromBase + s.ordinal])
        grams[fromBase + s.ordinal] -= moved
        grams[toBase + s.ordinal] += moved
    }
}

/** Right and down: visiting every tile with these two covers each edge exactly once. */
private val FLOW_DIRS = listOf(Direction.Right, Direction.Down)
