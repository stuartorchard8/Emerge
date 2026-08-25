package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.isqrt
import org.emerge.demo.outofspace.num.scaledRatio

/**
 * What one cell of a body is, geometrically — step 4 of `PLAN_rigid_bodies.md`.
 *
 * A body is a grid of cells and each filled cell has a shape. The narrow phase is a **table indexed
 * by the pair** of shapes, and that is the whole design: a new shape costs one function per pair it
 * can meet and changes nothing in [Contact], nothing in [solveContacts], and nothing in the tick
 * order. Requirement 6 of the plan — *none of these may require redoing the others* — is this rule
 * and no other.
 *
 * | | Disc | Box | Triangle |
 * |---|---|---|---|
 * | **Disc** | built | built | later |
 * | **Box** | built | later (SAT) | later |
 * | **Triangle** | later | later | later |
 *
 * ### Why bodies are discs and the hull is boxes
 *
 * A **union of discs cannot represent a flat surface**, and it is not close: at the natural radius of
 * half a tile, adjacent discs merely touch and the notch between them is a full half-tile deep. Even
 * at the radius that covers a tile's corners a fifth of a tile of scallop remains, and no radius
 * removes it. So a wall built of discs is a row of circles and a rock sliding along it drops into a
 * pothole every tile — which is why the hull is boxes from the first commit rather than later.
 *
 * A rock is discs because **rotation moves only the centres**. A disc has no orientation to get
 * wrong, so a disc body is exactly correct at every angle with no shape maths at all, and it
 * degrades exactly as an asteroid should: bite a tile out with an extractor and its disc goes with
 * it. The scalloping that makes discs wrong for a wall is, on rubble, rubble catching on rubble.
 */
sealed interface CellShape {

    /**
     * A circle about the cell's centre.
     *
     * ⚠️ [radius] is **half a tile and must not be shaved** to satisfy the 0.1-tile anti-pinch
     * tolerance of §4.1. A disc of half a tile already stands 0.207 tile clear of the tile's corner,
     * twice the tolerance asked for, and a corner that does not exist cannot pinch. Shaving it to 0.4
     * would take two adjacent cells of the *same body* from touching to a 0.2-tile gap, turning the
     * body into a bag of loose circles with notches an external disc could nestle into. The tolerance
     * is a bound on how sharp a cell may be, and a disc satisfies it by being a disc.
     */
    class Disc(val radius: Long) : CellShape

    /**
     * An axis-aligned rectangle about the cell's centre, in the frame the cell is being tested in.
     *
     * Axis-aligned is not a limitation here: a hull tile is axis-aligned *in the grid*, and the grid
     * is the frame every collision query is transformed into. A turned body of boxes needs
     * Box-vs-Box SAT and is a later cell of the table.
     *
     * [halfWidth] and [halfHeight] are where §4.1's inset lives when step 4b gives fragments their
     * shape: a face with no solid cell of the same body beyond it is pulled in by
     * [RigidBody.TOLERANCE], and a face with one is **not**. Insetting every face would turn a wall
     * back into a row of separated boxes, which is the scalloping problem by another route.
     */
    class Box(val halfWidth: Long, val halfHeight: Long) : CellShape

    companion object {
        /** One whole tile of hull, which is what every solid tile in the grid is today. */
        val TILE: Box = Box(Flight.PER_TILE / 2L, Flight.PER_TILE / 2L)

        /** The inscribed disc of a tile — a rock's silhouette, rather than a bulging one. */
        val CELL: Disc = Disc(Flight.PER_TILE / 2L)
    }
}

/**
 * The pair table. Emits a [Contact] into [into] if the two shapes overlap, and nothing if not.
 *
 * [a] belongs to the **body** and [b] to the thing it hit, and the normal comes out pointing out of
 * [b] and into [a], which is the direction [Contact] promises and the solver assumes.
 *
 * Positions are centres in the frame both shapes are expressed in, at [Flight.PER_TILE] to the tile.
 *
 * ⚠️ Everything below works on **differences of positions**, reduced before any multiply. A product
 * of two absolute world coordinates overflows a `Long` past 3.04 tiles from the origin — three tiles,
 * not three million — and `PLAN_rigid_bodies.md` §5.3 names it as the single thing most likely to go
 * wrong in this work. It fails silently, as a wrapped value that reads like an absence.
 */
fun contactBetween(
    a: CellShape,
    ax: Long,
    ay: Long,
    b: CellShape,
    bx: Long,
    by: Long,
    body: Int,
    /** Which body [b] belongs to, or [Contact.HULL] — carried through untouched. */
    other: Int = Contact.HULL,
    restingSpeedX: Long,
    restingSpeedY: Long,
    friction: Long,
    into: MutableList<Contact>,
    /**
     * The frame [b] is square in — its cos and sin are all that is read, never its origin.
     *
     * `null` means "square in the frame this query is being asked in", which is what every
     * axis-aligned caller wants and what the whole table meant implicitly before step 6.
     *
     * ### ⚠️ Why this parameter is the end of the shared frame
     *
     * A [CellShape.Box] has no angle of its own, so before this it was axis-aligned in *whatever
     * frame it was handed* — and the only way to guarantee that for the hull was to express
     * everything else relative to the hull. One operand got to be square and every other operand had
     * to be turned into its axes first, which is §5's *"you cannot unify vessels and rocks while one
     * of them defines the coordinate system"* showing up as a function signature. Two box-celled
     * operands at different angles have no shared frame that is axis-aligned for both, so as long as
     * this parameter did not exist there could only ever be one of them.
     *
     * Passed as a [Pose] rather than a [Coord] because the caller already has one — a body's pose
     * for the substep — and [Norm.fromAngle] is a CORDIC loop that must not run per cell per tile.
     */
    bFrame: Pose? = null,
) {
    when {
        a is CellShape.Disc && b is CellShape.Box ->
            discVsBox(a, ax, ay, b, bx, by, bFrame, body, other, restingSpeedX, restingSpeedY, friction, into)
        a is CellShape.Disc && b is CellShape.Disc ->
            discVsDisc(a, ax, ay, b, bx, by, body, other, restingSpeedX, restingSpeedY, friction, into)
        else -> throw IllegalArgumentException(
            "no narrow phase for ${a::class.simpleName} against ${b::class.simpleName} — " +
                "add the pair rather than falling through, or the two will pass through each other",
        )
    }
}

/**
 * A disc against an axis-aligned box: **the pair that carries every rock-against-ship contact**, and
 * the easiest one in the table to get exactly right.
 *
 * Closest point on the box to the disc's centre, by clamping; the normal is the difference and the
 * depth is `r − |d|`. No SAT, no clipping, no iteration, and correct at every angle of the body,
 * because a disc does not have an angle.
 */
private fun discVsBox(
    disc: CellShape.Disc,
    discX: Long,
    discY: Long,
    box: CellShape.Box,
    boxX: Long,
    boxY: Long,
    /** The frame the box is square in, or `null` for the query's own — see [contactBetween]. */
    boxFrame: Pose?,
    body: Int,
    other: Int,
    restingSpeedX: Long,
    restingSpeedY: Long,
    friction: Long,
    into: MutableList<Contact>,
) {
    // ⚠️ Relative to the box **first**, and turned into the box's axes second. Both orders give the
    // same answer in exact arithmetic and only one of them is computable: [rotScale] applied to an
    // absolute world coordinate overflows four tiles from the origin (§5.3), so the difference is
    // taken before anything is multiplied by a cosine. This is [Pose.toLocalX]'s own warning, and
    // the reason that function subtracts before it rotates.
    val worldDx = discX - boxX
    val worldDy = discY - boxY
    val dx = if (boxFrame == null) worldDx else boxFrame.unturnedX(worldDx, worldDy)
    val dy = if (boxFrame == null) worldDy else boxFrame.unturnedY(worldDx, worldDy)
    val nearX = if (dx < -box.halfWidth) -box.halfWidth else if (dx > box.halfWidth) box.halfWidth else dx
    val nearY = if (dy < -box.halfHeight) -box.halfHeight else if (dy > box.halfHeight) box.halfHeight else dy
    val outX = dx - nearX
    val outY = dy - nearY
    val distSq = outX * outX + outY * outY

    if (distSq > 0L) {
        // The ordinary case: the centre is outside the box and the touch has a real direction.
        if (distSq >= disc.radius * disc.radius) return
        val dist = isqrt(distSq)
        if (dist <= 0L) return
        // ⚠️ The point and the normal go back out in the frame they came in from. A **point**
        // translates and a **direction** does not, which is why one uses [Pose.turnedX] with the
        // box's centre added back and the other uses it alone — see [Pose.turnedX]'s KDoc. The depth
        // needs neither: it is a length, and a rotation does not change one.
        into.add(
            contactAt(
                body = body, other = other,
                pointX = boxX + (if (boxFrame == null) nearX else boxFrame.turnedX(nearX, nearY)),
                pointY = boxY + (if (boxFrame == null) nearY else boxFrame.turnedY(nearX, nearY)),
                dirX = if (boxFrame == null) outX else boxFrame.turnedX(outX, outY),
                dirY = if (boxFrame == null) outY else boxFrame.turnedY(outX, outY),
                dirLength = dist,
                depth = disc.radius - dist,
                restingSpeedX = restingSpeedX, restingSpeedY = restingSpeedY,
                friction = friction,
            ),
        )
        return
    }

    // ⚠️ The centre is **inside** the box, where the difference vector is zero and has no direction
    // to offer. This is not a corner case to be skipped: a body that starts a tick overlapping — an
    // editor drop, a rock the extractor just freed — arrives here, and returning nothing would mean
    // the solver had no way to push it out. The way out is the nearest face, the same axis-of-least-
    // penetration rule a box-against-box touch uses, and the depth carries the whole radius as well
    // as the distance to that face.
    val throughX = box.halfWidth - if (dx < 0L) -dx else dx
    val throughY = box.halfHeight - if (dy < 0L) -dy else dy
    val alongX = throughX <= throughY
    val sign = if (alongX) (if (dx >= 0L) 1L else -1L) else (if (dy >= 0L) 1L else -1L)
    // The nearest face is a face **of the box**, so the axis it points along is one of the box's,
    // and it comes back out through the same turn as the ordinary case above.
    val localNormalX = if (alongX) sign * Flight.FRAC_ONE else 0L
    val localNormalY = if (alongX) 0L else sign * Flight.FRAC_ONE
    // ⚠️ Emitted through [contactAt] rather than as a [Contact] built by hand, and that is a step-6
    // correction rather than tidying. The resting speed used to be picked as `if (alongX) x else y`,
    // which reads the threshold off the axis the *box* is square in — the same thing as the query's
    // axis for exactly as long as a box could not be turned. Blending it off the normal after the
    // turn is what the shallow case above has always done, and it is the frame-independent form.
    into.add(
        contactAt(
            body = body, other = other,
            pointX = discX, pointY = discY,
            dirX = if (boxFrame == null) localNormalX else boxFrame.turnedX(localNormalX, localNormalY),
            dirY = if (boxFrame == null) localNormalY else boxFrame.turnedY(localNormalX, localNormalY),
            dirLength = Flight.FRAC_ONE,
            depth = disc.radius + (if (alongX) throughX else throughY),
            restingSpeedX = restingSpeedX, restingSpeedY = restingSpeedY,
            friction = friction,
        ),
    )
}

/**
 * Two discs: every rock-against-rock contact, once step 5 has a broad phase to find the pairs.
 *
 * The simplest entry in the table by some distance — the normal is the line between the centres and
 * the depth is `r₁ + r₂ − |d|` — and built now rather than with the broad phase because it is the
 * half that can be tested on its own. A pair that is exactly concentric has no direction to separate
 * along and is left alone; the next tick's motion gives it one.
 */
private fun discVsDisc(
    a: CellShape.Disc,
    ax: Long,
    ay: Long,
    b: CellShape.Disc,
    bx: Long,
    by: Long,
    body: Int,
    other: Int,
    restingSpeedX: Long,
    restingSpeedY: Long,
    friction: Long,
    into: MutableList<Contact>,
) {
    val dx = ax - bx
    val dy = ay - by
    val distSq = dx * dx + dy * dy
    val reach = a.radius + b.radius
    if (distSq >= reach * reach) return
    val dist = isqrt(distSq)
    if (dist <= 0L) return
    // On the surface of b, along the line of centres — which for two discs is the middle of the
    // overlap lens as well, so there is nothing to choose between the conventions.
    into.add(
        contactAt(
            body = body, other = other,
            pointX = bx + scaledRatio(dx, dist, b.radius),
            pointY = by + scaledRatio(dy, dist, b.radius),
            dirX = dx, dirY = dy, dirLength = dist,
            depth = reach - dist,
            restingSpeedX = restingSpeedX, restingSpeedY = restingSpeedY,
            friction = friction,
        ),
    )
}

/**
 * A contact whose normal is ([dirX], [dirY]) normalised by its known [dirLength].
 *
 * The normalisation is the one arithmetic step both off-axis pairs share, and it is the one that
 * needs care: a component is up to 1.5e9 and [Flight.FRAC_ONE] is 2.1e9, so the plain
 * `component × FRAC_ONE / length` leaves the range. [scaledRatio] reduces the fraction first, the
 * same discipline [rotScale] applies for the same reason.
 *
 * The resting threshold arrives as one figure per axis because that is how the plating computes it,
 * and an off-axis normal takes the blend its own direction asks for rather than one axis's answer.
 * On an axis-aligned normal this is exactly the old behaviour.
 */
private fun contactAt(
    body: Int,
    other: Int,
    pointX: Long,
    pointY: Long,
    dirX: Long,
    dirY: Long,
    dirLength: Long,
    depth: Long,
    restingSpeedX: Long,
    restingSpeedY: Long,
    friction: Long,
): Contact {
    val normalX = unitOf(dirX, dirLength)
    val normalY = unitOf(dirY, dirLength)
    // ⚠️ Weighted in per-mille, not in [Flight.FRAC_ONE]ths. A resting speed is a few tiles a tick,
    // 3e9, and a raw weight is 2.1e9: the two blended at full precision are 1.3e19 and out of range.
    // A thousandth of the blend is far below a threshold that decides whether a rock is asleep.
    val weightX = (if (normalX < 0L) -normalX else normalX) / (Flight.FRAC_ONE / 1000L)
    val weightY = (if (normalY < 0L) -normalY else normalY) / (Flight.FRAC_ONE / 1000L)
    val resting = if (weightX + weightY <= 0L) restingSpeedX else
        (weightX * restingSpeedX + weightY * restingSpeedY) / (weightX + weightY)
    return Contact(
        body = body, other = other,
        pointX = pointX, pointY = pointY,
        normalX = normalX, normalY = normalY,
        depth = depth,
        restingSpeed = resting,
        friction = friction,
    )
}

/** One component of a unit vector, in [Flight.FRAC_ONE]ths, for a signed component and a length. */
private fun unitOf(component: Long, length: Long): Long {
    if (component == 0L) return 0L
    val magnitude = scaledRatio(
        numerator = if (component < 0L) -component else component,
        denominator = length,
        scale = Flight.FRAC_ONE,
    )
    return if (component < 0L) -magnitude else magnitude
}

/**
 * Do the two shapes overlap at all? — the boolean half of [contactBetween], and it **must** answer
 * the same question.
 *
 * ⚠️ The two agreeing is not tidiness, it is the difference between a wall and no wall. The sweep
 * asks this to decide whether a body began the tick already *inside* the hull, and a body that did
 * is left alone for the whole tick, so that an editor drop is not flung across the room. Answer
 * `true` where [contactBetween] emits nothing and the body spends the tick with its collisions
 * switched off — which is precisely what a full-tile box test did the moment cells became discs,
 * because the corner of a tile lies outside the tile's inscribed disc. A rock walked through a
 * bulkhead at sixteen tiles a tick that way.
 *
 * Kept as arithmetic rather than as "did [contactBetween] emit anything" because it is asked per
 * cell per tile and must not allocate; `CellShapeTest` is what holds the two to the same answer.
 */
fun overlapBetween(
    a: CellShape,
    ax: Long,
    ay: Long,
    b: CellShape,
    bx: Long,
    by: Long,
    /** The frame [b] is square in — the same argument [contactBetween] takes, and for the same
     * reason. It **must** be passed whenever that one is, or the two answer different questions and
     * a body reads as wedged in a wall it is not in. */
    bFrame: Pose? = null,
): Boolean =
    when {
        a is CellShape.Disc && b is CellShape.Box -> {
            val worldDx = ax - bx
            val worldDy = ay - by
            val dx = if (bFrame == null) worldDx else bFrame.unturnedX(worldDx, worldDy)
            val dy = if (bFrame == null) worldDy else bFrame.unturnedY(worldDx, worldDy)
            val outX = dx - clampTo(dx, b.halfWidth)
            val outY = dy - clampTo(dy, b.halfHeight)
            val distSq = outX * outX + outY * outY
            // Dead centre counts: the centre inside the box is the deep-overlap case, which
            // [contactBetween] answers with the nearest face rather than skipping.
            distSq < a.radius * a.radius
        }

        a is CellShape.Disc && b is CellShape.Disc -> {
            val dx = ax - bx
            val dy = ay - by
            val reach = a.radius + b.radius
            val distSq = dx * dx + dy * dy
            // ⚠️ Concentric is the one place the pair disagrees on purpose: there is no direction to
            // separate along, so [contactBetween] emits nothing and this says so too.
            distSq in 1L until reach * reach
        }

        else -> throw IllegalArgumentException(
            "no overlap test for ${a::class.simpleName} against ${b::class.simpleName}",
        )
    }

/** Held to `±[limit]` — one axis of the closest point on a box to a point. */
private fun clampTo(value: Long, limit: Long): Long =
    if (value > limit) limit else if (value < -limit) -limit else value

/** How far a shape reaches from its centre along either axis — what a tile search is sized on. */
fun shapeReach(shape: CellShape): Long = when (shape) {
    is CellShape.Disc -> shape.radius
    is CellShape.Box -> maxOf(shape.halfWidth, shape.halfHeight)
}
