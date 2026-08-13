package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.sim.core.physics.primitives.Coord

/**
 * The angular half of [Flight]: what the vessel spins about, how hard it is to spin, and what one
 * unit of angle, angular velocity and torque mean.
 *
 * The linear side is `p = Σ J`, `v = p/m`, `x += v`. This is the same three lines with the same
 * shapes — `L = Σ τ`, `ω = L/I`, `ang += ω` — and deliberately so: [VesselState.angImpulse] is the
 * twin of [VesselState.vesselImpulseX] and is stored for the same reason, [VesselState.angVel] is
 * derived for the same reason [VesselState.velocityX] is, and [VesselState.ang] is stored for the
 * same reason [VesselState.positionX] is. A history of angular velocities cannot be recomputed from
 * anything; everything else can.
 *
 * ### The units, and why none of them is a tile
 *
 * - **Angle** is a [Coord]: `raw / Int.MAX_VALUE` half-turns, so a full turn is `2·Int.MAX_VALUE`
 *   and `Int` overflow *is* the wrap. Exact, composable by addition, and one `Int` in a save. See
 *   `PLAN_trig_free_rotation.md` §2 for why this and not a direction vector.
 * - **Angular velocity** is [Coord] raw per tick, held as a `Long` so a spin faster than half a turn
 *   a tick is representable rather than silently aliased.
 * - **Angular impulse** is mass·tile²/tick — a lever arm in tiles crossed with a linear impulse, so
 *   it carries the mass unit exactly once and cancels against the vessel's mass in [angularVelocity].
 * - **Radii** are millitiles ([MILLI_TILE]), because a centre of mass almost never lands on a tile
 *   boundary and a torque computed from a rounded lever arm is a torque about the wrong point.
 */
object Rotation {

    /** Radius fixed-point: 1000 units to the tile. The centre of a tile is at `x·1000 + 500`. */
    const val MILLI_TILE: Long = 1_000L

    /**
     * [Coord] raw per radian: a half turn is [Int.MAX_VALUE], so this is `Int.MAX_VALUE / π`.
     *
     * The one place a transcendental number enters, and it enters as a rounded integer constant
     * rather than as a call, so every platform agrees on it by construction — the same argument
     * `Trig` makes at greater length.
     */
    const val RAW_PER_RADIAN: Long = 683_565_276L

    /**
     * Fixed-point for [MassDistribution.gyrationSq]: a squared radius of gyration in micro-tile².
     *
     * Chosen so the accumulation cannot overflow *and* [angularVelocity]'s first division needs no
     * reduction. `k²` is a mass-weighted mean of `r²`, so it is bounded by the largest `r²` on the
     * grid however heavy the ship gets — about 6.5e9 of these on a 96×60 grid, against a `Long`.
     * A moment of inertia written the obvious way, `Σ m·r²`, is **not** so bounded: at a microgram
     * per unit a tile of hull is 3.9e11, and a fully plated 96×60 grid puts `Σ m·r²` within a factor
     * of four of overflowing. Normalising by the mass first is what buys the headroom back, and it
     * costs nothing because [angularVelocity] was going to divide by the mass anyway.
     */
    const val GYRATION_SCALE: Long = 1_000_000L

    /**
     * A single cell's own moment about its own centre, in [GYRATION_SCALE]ths — `a²/6` for a square
     * of side one tile.
     *
     * Without it a body's cells are point masses, and a **one-cell body has no moment of inertia at
     * all**: `gyrationSq` comes out zero, [angularVelocity] short-circuits, and the smallest rock in
     * the game is the one thing in it that cannot be made to spin. That is not a rounding artefact,
     * it is the parallel-axis theorem's other term being left out.
     *
     * ⚠️ Square, because a cell is a square today. When step 4 of `PLAN_rigid_bodies.md` makes cells
     * discs this becomes `r²/2` = 125_000 for a half-tile disc, and it is written as its own constant
     * so that change is one line rather than a hunt.
     */
    const val CELL_MOMENT: Long = GYRATION_SCALE / 6L
}

/**
 * Where the vessel's mass is, not just how much of it there is.
 *
 * Everything rotational is measured against this and nothing else: torques are booked about
 * ([comX], [comY]) because that is the point a free body spins about, and [gyrationSq] says how
 * reluctantly.
 *
 * ⚠️ **It moves.** Cargo rides the rails and buffers fill, so the centre of mass is recomputed every
 * tick alongside the mass — it is not a property of the layout. That is also why a torque booked
 * this tick must use *this* tick's centre; a producer that cached one would slowly start pushing
 * about a point the ship no longer turns about.
 */
data class MassDistribution(
    /** Total mass, identical to [vesselMass] — the same walk, so they cannot disagree. */
    val mass: Long,
    /** Centre of mass in millitiles from the grid origin. */
    val comX: Long,
    /** Centre of mass in millitiles from the grid origin. */
    val comY: Long,
    /**
     * Squared radius of gyration about ([comX], [comY]), in [Rotation.GYRATION_SCALE]ths of a tile².
     *
     * The moment of inertia is `mass × gyrationSq / GYRATION_SCALE`, and is deliberately not stored
     * that way round — see [Rotation.GYRATION_SCALE].
     */
    val gyrationSq: Long,
) {
    companion object {
        val EMPTY = MassDistribution(0L, 0L, 0L, 0L)
    }
}

/**
 * The mass, the centre of mass and the radius of gyration, from [forEachVesselMass].
 *
 * Two passes over one walk rather than one pass, because the second moment is about the centre and
 * the centre is not known until the first pass has finished. The parallel-axis trick would fold them
 * into one — accumulate `Σ m·r²` about the origin and subtract `M·d²` — and it is rejected here for
 * the reason [Rotation.GYRATION_SCALE] gives: `Σ m·r²` about the *origin* is the very quantity that
 * overflows, and it overflows worst for exactly the ship whose centre is furthest from the origin.
 * A cheaper pass that is wrong at the top of the range is not cheaper.
 */
fun massDistribution(
    grid: Grid,
    machines: List<Machine?>,
    conduits: Conduits,
    bridges: List<Machine?>,
): MassDistribution {
    var mass = 0L
    // Tile units, not millitiles: `Σ m·x` at a microgram per unit is already 2e17 on a heavy grid,
    // and three more decimal places on the lever arm would put it through the roof for a precision
    // the division below recovers anyway.
    var momentX = 0L
    var momentY = 0L
    forEachVesselMass(machines, conduits, bridges) { tile, fabric, cargo ->
        val m = fabric + cargo
        if (m == 0L) return@forEachVesselMass
        mass += m
        momentX += m * grid.xOf(tile)
        momentY += m * grid.yOf(tile)
    }
    if (mass <= 0L) return MassDistribution.EMPTY

    // `+ MILLI_TILE / 2` because a tile's mass sits at its centre, not at its corner. Added after
    // the division rather than to every term, which is the same number and one rounding instead of
    // one per tile.
    val comX = scaledRatio(momentX, mass, Rotation.MILLI_TILE) + Rotation.MILLI_TILE / 2L
    val comY = scaledRatio(momentY, mass, Rotation.MILLI_TILE) + Rotation.MILLI_TILE / 2L

    var gyrationSq = 0L
    forEachVesselMass(machines, conduits, bridges) { tile, fabric, cargo ->
        val m = fabric + cargo
        if (m == 0L) return@forEachVesselMass
        val rx = tileCentre(grid.xOf(tile)) - comX
        val ry = tileCentre(grid.yOf(tile)) - comY
        // Millitile² is micro-tile², which is what GYRATION_SCALE counts — so `r²` is already in the
        // output's unit and enters as the *scale*, making each term `(m/M)·r²` with the mass unit
        // cancelled inside a single exact call rather than across two lossy ones.
        val rSq = rx * rx + ry * ry
        if (rSq == 0L) return@forEachVesselMass
        gyrationSq += scaledRatio(m, mass, rSq)
    }

    return MassDistribution(mass = mass, comX = comX, comY = comY, gyrationSq = gyrationSq)
}

/** The centre of tile column/row [n], in millitiles. */
fun tileCentre(n: Int): Long = n * Rotation.MILLI_TILE + Rotation.MILLI_TILE / 2L

/**
 * The same three numbers for a **free body**: a grid of equal cells, each weighing [massPerTile].
 *
 * The same two passes as [massDistribution] and for the same reason, but the walk is a cell grid
 * rather than machines-conduits-bridges. Kept as a separate function rather than generalised over a
 * callback because the two disagree about what a "tile" weighs — a vessel tile is fabric plus cargo
 * and changes every tick, a body's cells are all the same rock — and pretending otherwise would cost
 * the body a per-cell lookup it does not need.
 *
 * Positions are millitiles from the body's **local origin**, which is the top-left corner of its cell
 * box: the same origin [RigidBody.positionX] places, so [Pose] can carry both without a fudge.
 */
fun cellDistribution(width: Int, height: Int, cells: BooleanArray, massPerTile: Long): MassDistribution {
    if (massPerTile <= 0L) return MassDistribution.EMPTY
    var filled = 0L
    var momentX = 0L
    var momentY = 0L
    for (cy in 0 until height) {
        for (cx in 0 until width) {
            if (!cells[cy * width + cx]) continue
            filled++
            momentX += tileCentre(cx)
            momentY += tileCentre(cy)
        }
    }
    if (filled == 0L) return MassDistribution.EMPTY

    // Every cell weighs the same, so the mass divides out of the centroid entirely and the moments
    // can be counted in cells. That is not a micro-optimisation: `Σ m·x` for an 83-tonne rock at a
    // microgram per unit is the quantity [Rotation.GYRATION_SCALE] exists to keep off the books.
    val comX = momentX / filled
    val comY = momentY / filled

    // Summed and then divided once, not divided per cell: `r²` is millitile², which is already
    // [Rotation.GYRATION_SCALE]'s unit, and the sum of it over even a huge body is ~1e14 — nowhere
    // near the range that forced [massDistribution] to fold the division inside its loop. The mass
    // is absent from this sum for the same reason it is absent from the centroid above.
    var rSqTotal = 0L
    for (cy in 0 until height) {
        for (cx in 0 until width) {
            if (!cells[cy * width + cx]) continue
            val rx = tileCentre(cx) - comX
            val ry = tileCentre(cy) - comY
            rSqTotal += rx * rx + ry * ry + Rotation.CELL_MOMENT
        }
    }
    return MassDistribution(
        mass = filled * massPerTile,
        comX = comX,
        comY = comY,
        gyrationSq = rSqTotal / filled,
    )
}

/**
 * How fast a point at lever arm [r] is going because the thing it belongs to is spinning at
 * [angVelRaw] — the `ω × r` that turns an angular velocity into a contact's closing speed.
 *
 * [r] is one component of the arm at [Flight.PER_TILE] to the tile and the result is in the same
 * unit per tick, so this drops straight into the linear velocities the solver already carries. It is
 * the *magnitude* only: which component of the velocity it becomes, and with which sign, is the
 * caller's business, because `ω × r = (−ω·r_y, ω·r_x)` and getting that pairing wrong is a body that
 * spins the wrong way rather than a body that overflows.
 *
 * ⚠️ Not `angVelRaw * r / RAW_PER_RADIAN`. An arm is up to ~1e11 and a spin is raw, so the plain
 * product leaves the range almost immediately — the same trap as [rotScale] and the same fix.
 */
fun spinSpeed(angVelRaw: Long, r: Long): Long {
    if (angVelRaw == 0L || r == 0L) return 0L
    val magnitude = scaledRatio(
        numerator = if (angVelRaw < 0L) -angVelRaw else angVelRaw,
        denominator = Rotation.RAW_PER_RADIAN,
        scale = if (r < 0L) -r else r,
    )
    return if ((angVelRaw < 0L) == (r < 0L)) magnitude else -magnitude
}

/**
 * The torque a linear impulse applied at [atX], [atY] exerts about [about] — `τ = rₓF_y − r_yF_x`.
 *
 * Positions in millitiles, impulse in the units [VesselState.netImpulseX] is in, result in
 * mass·tile²/tick. Every producer of linear impulse books its angular half through here, in the same
 * breath, from the same two numbers: a torque worked out afterwards from the *total* impulse is
 * worked out from a quantity that has already lost the positions, and would be wrong in exactly the
 * case the whole feature exists for — two thrusters that cancel linearly and spin the ship hard.
 */
fun torqueAbout(about: MassDistribution, atX: Long, atY: Long, impulseX: Long, impulseY: Long): Long {
    val rx = atX - about.comX
    val ry = atY - about.comY
    return (rx * impulseY - ry * impulseX) / Rotation.MILLI_TILE
}

/**
 * Angular velocity in [Coord] raw per tick: `ω = L / I`, with `I = mass · gyrationSq`.
 *
 * Divided in that order — by the gyration radius first, then by the mass — because only that order
 * keeps both steps inside [scaledRatio]'s exact range. Dividing by a materialised `I` would need the
 * product that [Rotation.GYRATION_SCALE] exists to avoid, and folding the mass in first leaves
 * `scaledRatio` reducing a 6.5e9 denominator against a 1e12 scale, which shifts the numerator to
 * nothing. The first call here needs no reduction at all and the second is the same shape
 * [VesselState.velocityX] already uses.
 */
fun angularVelocity(angImpulse: Long, distribution: MassDistribution): Long {
    if (angImpulse == 0L || distribution.mass <= 0L || distribution.gyrationSq <= 0L) return 0L
    // mass/tick: the angular momentum with the tile² of the lever arm divided back out.
    val perMass = scaledRatio(angImpulse, distribution.gyrationSq, Rotation.GYRATION_SCALE)
    return scaledRatio(perMass, distribution.mass, Rotation.RAW_PER_RADIAN)
}
