package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.num.isqrt
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.sim.core.physics.primitives.Coord

/**
 * Rock-hull contact: the swept overlap test, and the constants a bounce is made of.
 *
 * Ricochet: restitution = 0.5, tuned for legibility rather than measured — rock on steel is nearer
 * 0.2–0.4. Exchange: +J to the body, −J to the ship, with the ledger closed through
 * [VesselState.bodyImpulseX] rather than apologised for.
 *
 * ⚠️ Still **frictionless**, and since step 3 that is a visible gap rather than a simplification: a
 * body now carries a spin, and nothing anywhere takes energy out of one. A rock that lands on a
 * corner cartwheels for ever. Friction arrives with the per-cell shapes at step 4 of
 * `PLAN_rigid_bodies.md`, looked up from the two contacting cells.
 *
 * The normal and the contact point are no longer this file's business — [Contact] owns them, and
 * with them the torque that used to be impossible here.
 */
object RockContact {

    /** The bounce, as a fraction. A half — see the note above on why it is not a measurement. */
    const val RESTITUTION_NUM: Long = 1L
    const val RESTITUTION_DEN: Long = 2L

    /**
     * The most a rock may move in one sub-step, so that a contact cannot be missed by stepping over
     * it: half a tile, which is under the smallest feature a hull has.
     *
     * Tunnelling is much less urgent than it looks — a burn is a quarter of a g and the plating is
     * gone — but a fast approach in H4 is the entire point of H4, and a sweep that only works below
     * some speed is a sweep that fails exactly when the player is doing the interesting thing.
     */
    const val MAX_SUBSTEP: Long = Flight.PER_TILE / 2L

    /**
     * The floor under the resting threshold: a thousandth of a tile per tick.
     *
     * The threshold proper is computed from the gravity — see [restingSpeed] — and in genuine
     * freefall that is zero, which would leave a rock bouncing between two walls forever with its
     * speed halving and never arriving. This is what makes that asymptote terminate.
     */
    const val REST_FLOOR: Long = Flight.PER_TILE / 1000L

    /**
     * The most a body that began the tick **inside** the hull may be pushed out of it in one tick.
     *
     * A rock dropped by the editor, freed by an extractor, or shut in by an airlock is a placement
     * and not an impact: it is eased out along its contact normals with its momentum untouched. This
     * is what stops "eased" becoming "launched" — the push is sized on the depth and applied once per
     * *substep*, so a fast body would otherwise be answered at full depth dozens of times in a tick
     * and thrown clean across the room.
     *
     * A tenth of a tile a tick: below what reads as movement while a rock settles, and far too slow
     * to cross a one-tile wall. A body still buried after it gets another tenth next tick.
     */
    const val MAX_DEPENETRATION: Long = Flight.PER_TILE / 10L

    /**
     * Which way two interpenetrating **bodies** are eased apart — a switch, because the two rules
     * feel different and the choice is a judgement about rubble rather than a fact about arithmetic.
     *
     * `false` — **Stu's call, and the default** — is the rule the hull uses: deepest push per
     * direction, along the contacts' own normals. `true` separates along the line joining the two
     * centres of mass instead, which is the only direction two distinct bodies cannot cancel.
     *
     * ⚠️ **`false` jams, and it jams into a stable configuration rather than a rare one.** Two blobs
     * overlapping by half a tile have every cell of one sitting midway between two cells of the
     * other, so each is asked to go left exactly as hard as it is asked to go right and the two
     * subtract to nothing. Measured: a pair placed 3.3 tiles apart eased out to exactly 3.5 and
     * stopped there, which means the interlock is where the per-axis rule *converges*. That reads as
     * rubble that has settled *into* itself rather than rubble sitting on rubble, and it is what the
     * default was chosen for — a pile of ore that keys together looks like a pile of ore. What it is
     * not is a bug that has been left in: the failure mode is stillness in the wrong shape, never
     * violence, and `true` is here for the day that reading stops being wanted.
     *
     * It is a **position** rule either way. Neither setting touches the velocity solve, which always
     * answers each contact on its own normal, so a rock is held up by the same impulse under both.
     */
    var separateAlongCentres: Boolean = false

    /**
     * Resting threshold: v > a/e (below this, bounce ends in one tick = buzzing).
     * REST_FLOOR = perTick/1000 (terminates asymptote in freefall). One rounding chain (§5g).
     */
    fun restingSpeed(accelerationRaw: Long): Long {
        val a = if (accelerationRaw < 0L) -accelerationRaw else accelerationRaw
        // One chain, one rounding — §5g. Split into a multiply and a divide it would truncate twice,
        // and the second truncation is the one that decides whether a rock is asleep.
        val perTick = a * Flight.PER_TILE * RESTITUTION_DEN / (Flight.FRAC_ONE * RESTITUTION_NUM)
        return if (perTick < REST_FLOOR) REST_FLOOR else perTick
    }
}

/** Integer floor division, which is not what `/` does for negatives — and a body goes negative. */
internal fun floorTile(v: Long): Long =
    if (v >= 0L) v / Flight.PER_TILE else -((-v + Flight.PER_TILE - 1L) / Flight.PER_TILE)

/**
 * Does [body], placed in the grid by [at], overlap anything solid?
 *
 * ⚠️ [at] is a **world** pose, the way a body stores itself, and [shipPose] is where the grid is in
 * the world. The grid is the addressing scheme — the tile search below is in tile indices — and it
 * is no longer the frame: see [collectHullContacts]'s note on `shipPose`.
 *
 * ⚠️ **This asks [overlapBetween], which is the same geometry [collectHullContacts] emits contacts
 * from, and the two agreeing is load-bearing.** A body this says is inside the hull is left alone for
 * the whole tick — an editor drop is a placement, not a contact — so a `true` the narrow phase does
 * not back up with a contact is a body with its collisions switched off. Testing full tile boxes
 * while the narrow phase tested inscribed discs did exactly that, and a rock walked through a
 * bulkhead at sixteen tiles a tick: the corner of a tile is outside its own disc, so every grazing
 * touch left the body "wedged" in a wall it was not in.
 */
fun overlapsHull(grid: Grid, structure: StructureMap, body: RigidBody, at: Pose, shipPose: Pose): Boolean {
    val half = Flight.PER_TILE / 2L
    for (cy in 0 until body.height) {
        for (cx in 0 until body.width) {
            val cell = cy * body.width + cx
            if (!body.cells[cell]) continue
            val shape = body.shapeAt(cell)
            val centreX = at.toWorldX(cx * Flight.PER_TILE + half, cy * Flight.PER_TILE + half)
            val centreY = at.toWorldY(cx * Flight.PER_TILE + half, cy * Flight.PER_TILE + half)
            // The search is in the grid and the answer is in the world — see [collectHullContacts].
            val localX = shipPose.toLocalX(centreX, centreY)
            val localY = shipPose.toLocalY(centreX, centreY)
            val reach = shapeReach(shape)
            val tx0 = floorTile(localX - reach)
            val ty0 = floorTile(localY - reach)
            val tx1 = floorTile(localX + reach - 1L)
            val ty1 = floorTile(localY + reach - 1L)
            for (ty in ty0..ty1) {
                if (ty < 0 || ty >= grid.height) continue
                for (tx in tx0..tx1) {
                    if (tx < 0 || tx >= grid.width) continue
                    // The same predicate the narrow phase uses — see the warning above.
                    if (!structure.blocksPassage(grid.tile(tx.toInt(), ty.toInt()))) continue
                    val tileX = tx * Flight.PER_TILE + half
                    val tileY = ty * Flight.PER_TILE + half
                    val hit = overlapBetween(
                        shape, centreX, centreY,
                        CellShape.TILE,
                        shipPose.toWorldX(tileX, tileY), shipPose.toWorldY(tileX, tileY),
                        bFrame = shipPose,
                    )
                    if (hit) return true
                }
            }
        }
    }
    return false
}

/**
 * What one tick of sweeping did to **every** body at once, and what it therefore did to the ship.
 *
 * [handedX] is the momentum the vessel gave the bodies through contacts — the negative of what its
 * own operand received, booked in the same breath so the exchange conserves by construction. Body
 * against body does not appear in it at all, and that is the point: those impulses are internal to
 * the set of bodies and the ship is not a party to them.
 */
class SweptBodies(
    val bodies: List<RigidBody>,
    val handedX: Long,
    val handedY: Long,
    /** The twist that went with it, about the vessel's own centre of mass. */
    val handedTorque: Long,
    /** Every collision this tick that was a collision rather than a weight — see [Impact]. */
    val impacts: List<Impact> = emptyList(),
)

/**
 * A body hitting something, hard enough that the hit was a hit.
 *
 * This is the sweep's only account of itself to anything that is not physics: a host turns one of
 * these into a bang. It says *where*, *how hard*, and *against what*, and nothing else — the volume
 * curve, the distance falloff and the choice of clip are the listener's business and not the
 * solver's.
 *
 * ### Why the impulse and not the speed
 *
 * [impulse] is what the contact actually spent, `mass × Δv`, which is the quantity an ear is built
 * for: an 83-tonne ore body settling slowly against a bulkhead moves the air far more than a pebble
 * flicked at it fast. Reported off the *normal* alone — the tangent is a scrape and sounds nothing
 * like a strike, and it is a separate sound to add rather than the same one louder.
 *
 * ### One per body per tick, and it is the loudest
 *
 * A rock landing flat makes a dozen contacts across a dozen substeps and they are all the same
 * event. Summed, a wide body would be louder than a narrow one falling just as hard, which is
 * backwards for a *peak* — what a listener hears is the hardest touch, not the total.
 */
class Impact(
    /** Which body, as an index into the list handed to [sweepBodies]. */
    val bodyIndex: Int,
    /** Where it happened, world frame, at [Flight.PER_TILE] to the tile. */
    val x: Long,
    val y: Long,
    /** The normal impulse the touch spent, in the same units as [RigidBody.impulseX]. */
    val impulse: Long,
    /** The vessel, or another body — a hull strike rings and rubble on rubble does not. */
    val againstHull: Boolean,
)

/**
 * Sweep **all** the bodies together: one substep clock, one contact list, one solve — step 5 of
 * `PLAN_rigid_bodies.md`.
 *
 * ### ⚠️ Why this is one function and not a loop over bodies
 *
 * It used to be `bodies.map { sweepBody(it) }`, and no amount of care inside `sweepBody` could have
 * made two rocks touch: a body swept alone has nothing to touch but the hull, and by the time the
 * next body is swept the first has already spent its whole tick. That is not an omission in the
 * narrow phase — Disc-vs-Disc has existed since step 4 — it is the **shape of the tick**, which is
 * exactly what §2 of the plan said would have to change and what step 2 deliberately left owed.
 *
 * A stack is the case that makes it unavoidable rather than merely tidier. Three rocks in a pile are
 * two contacts that must be answered *together*: solved in sequence, the top rock is given its
 * support before the middle one knows it is being leaned on, so the pile sinks by one rock's
 * penetration every tick and jitters while it does it. Solved from one frozen state, the middle rock
 * feels the weight above and the deck below in the same pass and the tower converges.
 *
 * ### The ship recoils inside this loop, and it has to
 *
 * [ShipMotion.velocityX] is where the ship started the tick, and for most of this file's life that
 * was the same as where it is: a rock was a pebble against a hull, so the recoil rounded away and a
 * wall that never moved was a wall that never moved. It is not true any more. A default ore body is
 * **83 tonnes** and the box the tests fly is **40**, so a bounce moves the ship more than it moves
 * the rock, and a restitution computed against a stale wall is computed against the wrong closing
 * speed.
 *
 * Left stale it is an energy source rather than merely an inaccuracy. A rock dropped under one g
 * doubled its speed on every bounce — 5, 21, 95, 1821 tiles a tick — because each sub-step's impulse
 * was sized from a closing speed that the previous sub-step's impulse had already spent. Nothing in
 * the ledger noticed: momentum conserved perfectly the whole way up. Conservation of momentum is not
 * conservation of energy, and this is the shape of the difference.
 *
 * With the recoil fed back the arithmetic collapses to the law it was always supposed to be:
 * `rv' = rv + J/μ` and `J = −(1+e)·rv·μ` give `rv' = −e·rv` exactly, for any mass ratio, which is
 * what makes the bounce terminate rather than merely shrink.
 */
/**
 * A body an assembly holds: **geometry the sweep must see, whose pose it must not integrate.**
 *
 * ⛔ **The third state, and its absence was a bug.** Collision and integration are one pass over one
 * list, so for most of this file's life a body could be *simulated* or *invisible* and nothing else.
 * A docked station is neither: it does not drift — its pose is its root's plus a frozen offset, and
 * letting the sweep integrate it too would give two answers for where it is — but it is emphatically
 * still there. Removing it from the list to stop it drifting also removed it from the collision set,
 * so an asteroid flew straight through the station a berthed ship was bolted to, and lodged in it
 * the moment the clamps opened and the geometry came back.
 *
 * ⛔ **Its contacts are booked against the assembly, as [Contact.HULL].** That is not a shortcut: a
 * rigid assembly *is* one body, so a rock bouncing off a docked terminal must push the whole thing,
 * about the whole thing's centre of mass, with the whole thing's inertia resisting. The caller
 * passes the assembly's mass and distribution as the ship operand, and then the solver cannot tell a
 * station's hull from the ship's — which is exactly the property step 5 of `PLAN_rigid_bodies.md`
 * bought and the reason nothing in [solveContacts] needed changing for this.
 *
 * Held members are not tested against **each other**: two members of one assembly cannot move
 * relative to each other, so there is no contact to find. Two *different* assemblies touching is a
 * question that arises the first time there are two, and it is the same question as body-vs-body.
 */
class Held(
    /** The body itself, for its cells, its shapes and what they are made of. */
    val body: RigidBody,
    /** Where it is, given where the assembly's root has got to — see [Assembly.poseOf]. */
    val poseIn: (Pose) -> Pose,
)

fun sweepBodies(
    grid: Grid,
    structure: StructureMap,
    bodies: List<RigidBody>,
    ship: ShipMotion,
    shipMass: Long,
    shipAbout: MassDistribution,
    /** Per body, because it is derived from what that body weighs — see [driftBodies]. */
    restingSpeedX: LongArray,
    restingSpeedY: LongArray,
    /** The deck, for [frictionBetween]. `null` is bare hull throughout. */
    deck: DeckArray? = null,
    /** What the assembly is carrying: solid, and pose-driven — see [Held]. */
    held: List<Held> = emptyList(),
): SweptBodies {
    val n = bodies.size
    if (n == 0) return SweptBodies(bodies, 0L, 0L, 0L)

    // The loudest touch each body has taken so far this tick, and where it took it — see [Impact]
    // for why it is the loudest and not the sum. Zero means "nothing worth hearing yet".
    val loudest = LongArray(n)
    val loudestX = LongArray(n)
    val loudestY = LongArray(n)
    val loudestHull = BooleanArray(n)
    var contactImpulses = LongArray(0)

    val px = LongArray(n) { bodies[it].positionX }
    val py = LongArray(n) { bodies[it].positionY }
    val ix = LongArray(n) { bodies[it].impulseX }
    val iy = LongArray(n) { bodies[it].impulseY }
    val ang = LongArray(n) { bodies[it].ang.raw.toLong() }
    val angImpulse = LongArray(n) { bodies[it].angImpulse }
    val mass = LongArray(n) { bodies[it].mass }

    // The centre of mass in each body's own frame — the point it spins about and the point every
    // lever arm is measured from. Fixed for the tick: a rock does not shed cells mid-sweep.
    val comLocalX = LongArray(n) { bodies[it].about.comX }
    val comLocalY = LongArray(n) { bodies[it].about.comY }

    // Where the wall is going, updated as the bodies shove it. See the note on this function.
    var svx = ship.velocityX
    var svy = ship.velocityY
    var sav = ship.angVel

    fun worldVel(impulse: Long, m: Long): Long =
        if (m <= 0L) 0L else scaledRatio(impulse, m, Flight.PER_TILE)

    /** How far body [i] can travel this tick, in the grid's axes, centre and tip alike. */
    fun reachOf(i: Int): Long {
        if (mass[i] <= 0L) return 0L
        val rvx = worldVel(ix[i], mass[i]) - svx
        val rvy = worldVel(iy[i], mass[i]) - svy
        // ⚠️ **Turning is travel too.** A body's far corner moves at `ω × r` whether or not its
        // centre is going anywhere, so a spinning rock parked against a bulkhead sweeps its own
        // width through the wall in a tick and a substep count sized on the linear speed alone would
        // step clean over the contact. The radius is the furthest cell corner from the centre of
        // mass, upper-bounded by the whole cell box, which is cheap and never under-estimates.
        val body = bodies[i]
        val spinRadius = maxOf(
            maxOf(comLocalX[i], body.width * Flight.PER_TILE - comLocalX[i]),
            maxOf(comLocalY[i], body.height * Flight.PER_TILE - comLocalY[i]),
        )
        val tip = spinSpeed(angularVelocity(angImpulse[i], body.about) - sav, spinRadius)
        return maxOf(maxOf(abs(rvx), abs(rvy)), abs(tip))
    }

    // ⚠️ **The clock is sized on the fastest *pair*, not on the fastest body**, and that is a step-5
    // change rather than a refinement. Two rocks closing head-on approach each other at the sum of
    // their speeds, so a substep budget sized on the larger alone lets them step through each other
    // at anything over half the tunnelling speed — the one failure mode the sweep exists to rule
    // out, arriving by the one route the hull test could never see. The top two reaches summed is
    // the largest closing speed any pair can have, and it is never smaller than a single body's own
    // reach against the hull.
    var first = 0L
    var second = 0L
    for (i in 0 until n) {
        val r = reachOf(i)
        if (r > first) { second = first; first = r } else if (r > second) { second = r }
    }
    val steps = ((first + second) / RockContact.MAX_SUBSTEP + 1L).toInt()

    /**
     * The vessel's pose part way through the tick.
     *
     * The sweep advances the bodies in the world and the ship in the world, and asks where each body
     * has got to *in the ship's frame* at each substep. Both are moving, so the relative motion —
     * including everything the ship's rotation contributes — falls out of the subtraction instead of
     * being written down as an `ω × r` term that could be got wrong or forgotten.
     */
    fun poseAt(step: Int): Pose = ship.pose.let {
        Pose(
            it.x + svx * step / steps,
            it.y + svy * step / steps,
            Coord((it.ang.raw + sav * step / steps).toInt()),
            it.comLocalX, it.comLocalY,
        )
    }

    /**
     * ⚠️ **There is no `gridPose` any more, and its absence is step 6.**
     *
     * Every body used to be composed into the grid's frame before a single question could be asked
     * of it, because a [CellShape.Box] could not be asked at an angle and so the hull had to be the
     * thing everybody else was expressed relative to. A box carries its own turn now, so contacts,
     * the solve and the position push all happen in the **world**, and the grid appears only inside
     * [collectHullContacts] as what it actually is: the addressing scheme the tile search needs.
     */

    // ── A body that begins the tick already inside something ──────────────────────
    //
    // It is a placement and not a contact: the editor drops rocks onto the deck, an extractor frees
    // one from inside a seam, an airlock can shut on top of one, and — since bodies collide with
    // each other — two of them can be spawned overlapping. None of those is an impact and none
    // should be answered with a bounce: a body that arrived at rest inside a wall has no closing
    // speed to reverse, and reversing the depth instead would fling it across the room.
    //
    // ⚠️ **It is eased out, not exempted.** This used to skip the whole substep — no contacts, no
    // solve, no push — which meant a wedged body flew with its collisions switched off until it
    // happened to come out somewhere. That was survivable only while cells were full tiles, because
    // a tile-wide body cannot clear a one-tile wall inside one such spell. A disc body can, and did:
    // a rock grinding along a bulkhead sank through it a third of a tile a tick and left the ship
    // entirely. Discs did not introduce that, they made it reachable.
    //
    // ⚠️ The velocity solve is **not** skipped for a wedged body, and skipping it was tried first.
    // It looks right — a placement is not an impact, so do not answer it — and it puts a resting
    // rock through the floor: a body lying on the deck is overlapping it, so it reads as wedged
    // every tick, and a wedged body with no normal impulse has nothing holding it up. The plating
    // accelerates it into the deck each tick while a tenth of a tile of push lifts it, gravity wins,
    // and the rock sinks out of the ship. (Measured: dropped at y=12 it was 1,700 tiles below the
    // hull and still falling after 120 ticks.) The exemption is unnecessary anyway: the solver only
    // ever reverses a **closing** speed, so a body that was *placed* inside a wall asks for no
    // impulse of its own accord. What would have flung it was the position push being sized on a
    // depth of a whole tile and applied once per substep, dozens of times a tick. The budget below
    // is the entire fix, and a tenth of a tile a tick is faster than an editor drop can be noticed
    // settling and far too slow to jump a wall.
    val startPose = poseAt(0)
    val startWorld = Array(n) { Pose(px[it], py[it], Coord(ang[it].toInt()), bodies[it].about) }
    // A held member's pose at the start of the tick, for the wedged test below — one per member
    // rather than one per member per body, because it does not depend on which body is asking.
    val heldStart = Array(held.size) { held[it].poseIn(startPose) }
    val depenetration = LongArray(n) { i ->
        var stuck = overlapsHull(grid, structure, bodies[i], startWorld[i], startPose)
        if (!stuck) {
            for (j in 0 until n) {
                if (j == i) continue
                if (bodiesOverlap(bodies[i], startWorld[i], bodies[j], startWorld[j])) { stuck = true; break }
            }
        }
        // ⚠️ And against what the assembly is carrying, on the same terms as the hull: a rock that
        // begins the tick inside a docked terminal is a placement to be eased out of, not a bounce.
        if (!stuck) {
            for (h in held.indices) {
                if (bodiesOverlap(bodies[i], startWorld[i], held[h].body, heldStart[h])) { stuck = true; break }
            }
        }
        if (stuck) RockContact.MAX_DEPENETRATION else Long.MAX_VALUE
    }

    var handedX = 0L
    var handedY = 0L
    var handedTorque = 0L

    val contacts = ArrayList<Contact>(8)
    val turned = arrayOfNulls<Pose>(n)
    val pushLeft = LongArray(n)
    val pushRight = LongArray(n)
    val pushUp = LongArray(n)
    val pushDown = LongArray(n)
    /** The deepest touch each colliding pair has, keyed `a * n + b` — see the position push below. */
    val pairDepth = HashMap<Long, Long>()

    for (k in 0 until steps) {
        val next = poseAt(k + 1)

        // ── One substep of travel, for everything at once ─────────────────────────
        //
        // Taken about each body's centre of mass rather than about its local origin — a body spins
        // about its mass, and turning about the corner of its cell box would walk it sideways across
        // the deck at a speed proportional to its spin. The vessel had exactly this bug until step 1
        // found it.
        for (i in 0 until n) {
            val vx = worldVel(ix[i], mass[i])
            val vy = worldVel(iy[i], mass[i])
            val spin = angularVelocity(angImpulse[i], bodies[i].about)
            val moved = Pose(px[i], py[i], Coord(ang[i].toInt()), bodies[i].about)
                .turned(Coord((spin * (k + 1) / steps - spin * k / steps).toInt()))
                .movedBy(
                    vx * (k + 1) / steps - vx * k / steps,
                    vy * (k + 1) / steps - vy * k / steps,
                )
            turned[i] = moved
        }

        // ── Every touch in the world, in a stable order ───────────────────────────
        //
        // Hull first and then pairs, both by index, because §6 of the plan asks for an order a broad
        // phase cannot perturb. The Jacobi solve makes the order irrelevant to the *answer*, but the
        // accumulators are summed in it and a lockstep host cannot afford the difference.
        contacts.clear()
        for (i in 0 until n) {
            if (mass[i] <= 0L) continue
            collectHullContacts(
                grid, structure, bodies[i], i, turned[i]!!, next,
                restingSpeedX[i], restingSpeedY[i], contacts, deck,
            )
        }
        // ── What the assembly is carrying, on the hull's terms ───────────────────
        //
        // ⚠️ **`Contact.HULL` as the other operand, and that is the whole of it.** A held member's
        // touch is the assembly's touch, so it books against the assembly's mass, centre and inertia
        // — the ship operand — exactly as a touch on the plating does. Emitted after the hull's and
        // before the pairs', keeping §6's stable order: a broad phase cannot perturb it.
        //
        // The **free** body supplies the resting threshold, as it does against the hull, because a
        // held member is not falling and has no closing speed of its own to fall asleep at.
        if (held.isNotEmpty()) {
            for (i in 0 until n) {
                if (mass[i] <= 0L) continue
                for (h in held.indices) {
                    collectBodyContacts(
                        bodies[i], i, turned[i]!!,
                        held[h].body, Contact.HULL, held[h].poseIn(next),
                        restingSpeedX[i], restingSpeedY[i],
                        contacts,
                    )
                }
            }
        }
        for (i in 0 until n) {
            if (mass[i] <= 0L) continue
            for (j in i + 1 until n) {
                if (mass[j] <= 0L) continue
                collectBodyContacts(
                    bodies[i], i, turned[i]!!,
                    bodies[j], j, turned[j]!!,
                    // ⚠️ The **larger** of the two thresholds, so that the pair agrees with itself.
                    // A resting rule is about when a bounce is small enough to stop being a bounce,
                    // and taking one body's answer would make the same contact bouncy or asleep
                    // depending on which of the two the loop happened to name first.
                    maxOf(restingSpeedX[i], restingSpeedX[j]),
                    maxOf(restingSpeedY[i], restingSpeedY[j]),
                    contacts,
                )
            }
        }
        if (contacts.isEmpty()) {
            for (i in 0 until n) {
                px[i] = turned[i]!!.x; py[i] = turned[i]!!.y
                ang[i] = turned[i]!!.ang.raw.toLong()
            }
            continue
        }

        // ── Velocity: every contact in the world, solved together ─────────────────
        //
        // ⚠️ **World axes, and the conjugation either side of this block is gone.** A normal used to
        // arrive in the grid's axes, so every operand's velocity had to be turned into them and its
        // impulse turned back out again — two rotations per body per substep whose only purpose was
        // to meet the hull on the hull's terms. Contacts come back in the world now, so the ship's
        // pose appears nowhere in this block and the ship's own operand is built exactly like a
        // rock's: a mass, a mass distribution, a centre of mass in the world, and a velocity.
        //
        // A scalar spin still needs no turning — it is the same number in any set of axes — which is
        // now true of everything here rather than of the angular half alone.
        val ops = ArrayList<Operand>(n)
        for (i in 0 until n) {
            ops.add(
                Operand(
                    mass = mass[i],
                    about = bodies[i].about,
                    // The pose *is* anchored on the centre of mass now, so this is a read and not
                    // a transform. It was `toWorld(comLocal)` while the anchor was the corner.
                    comX = turned[i]!!.x,
                    comY = turned[i]!!.y,
                    velocityX = worldVel(ix[i], mass[i]),
                    velocityY = worldVel(iy[i], mass[i]),
                    angVel = angularVelocity(angImpulse[i], bodies[i].about),
                ),
            )
        }
        val shipOp = if (shipMass <= 0L) null else Operand(
            mass = shipMass,
            about = shipAbout,
            // ⚠️ The ship's centre of mass is a point **in its own grid**, so it goes out through
            // the ship's pose exactly as a body's goes out through the body's. It was a bare grid
            // coordinate before only because the grid was the frame.
            comX = next.toWorldX(shipAbout.comMilliX * RigidBody.COM_SCALE, shipAbout.comMilliY * RigidBody.COM_SCALE),
            comY = next.toWorldY(shipAbout.comMilliX * RigidBody.COM_SCALE, shipAbout.comMilliY * RigidBody.COM_SCALE),
            velocityX = svx,
            velocityY = svy,
            angVel = sav,
        )
        if (contactImpulses.size < contacts.size) contactImpulses = LongArray(contacts.size)
        solveContacts(contacts, ops, shipOp, impacts = contactImpulses)

        // ⚠️ The point comes back in **grid** axes, like the normal it was measured along, and an
        // [Impact] is a place in the world — the same frame boundary the impulses cross two blocks
        // below. Left unturned a bang on the port bulkhead of a ship lying on its side plays from
        // somewhere off the bow.
        for (i in contacts.indices) {
            val spent = contactImpulses[i]
            if (spent <= 0L) continue
            val c = contacts[i]
            val hull = c.other == Contact.HULL
            // Booked against **both** sides: two rocks that meet are two rocks that were struck, and
            // a report that named only the first operand would go quiet whenever the loudest thing
            // in the room happened to be second in the list.
            for (side in 0..1) {
                val b = if (side == 0) c.body else c.other
                if (b < 0 || b >= n) continue
                if (spent <= loudest[b]) continue
                loudest[b] = spent
                // Already a place in the world — the sweep does not leave it any more.
                loudestX[b] = c.pointX
                loudestY[b] = c.pointY
                loudestHull[b] = hull
            }
        }

        // ⚠️ The impulse comes back in **world** axes now and a body's momentum is in the world, so
        // there is nothing to turn — the pair of `rotScale`s that used to stand here, undoing the
        // conjugation on the way in, went with the grid frame. The frame boundary they guarded has
        // not moved, it has shrunk to one place: [collectHullContacts], which is the only thing left
        // that needs to know the grid exists.
        //
        // ⚠️ The ledger is summed from **the bodies'** impulses and not from the ship's, even though
        // the two are the same quantity with opposite signs and the ship's is one number rather than
        // `n`. What the vessel owes itself is *exactly what the bodies were given* — that is what
        // [VesselState.bodyImpulseX] means — and the two sums differ by a few units of rounding,
        // because each is turned out of grid axes separately. Booked off the ship's side the
        // momentum ledger failed by 2 on a moving ship and by 199 across a grid fit: not enough to
        // see, exactly the kind of thing the ledger exists to catch, and a lie about which number is
        // definitive.
        //
        // Body-on-body stays out of it for free. Those impulses are equal and opposite between two
        // entries of this very sum, so they cancel as they are added, and the vessel is charged for
        // nothing it did not do.
        var stepX = 0L
        var stepY = 0L
        for (i in 0 until n) {
            val op = ops[i]
            ix[i] += op.gaveX
            iy[i] += op.gaveY
            angImpulse[i] += op.spun
            stepX += op.gaveX
            stepY += op.gaveY
        }
        handedX += stepX
        handedY += stepY
        if (shipOp != null) {
            svx += scaledRatio(-stepX, shipMass, Flight.PER_TILE)
            svy += scaledRatio(-stepY, shipMass, Flight.PER_TILE)
            sav += angularVelocity(shipOp.spun, shipAbout)
            // The twist **is** read off the ship's operand, because there is nothing on the bodies'
            // side to sum: each body's `spun` is about its own centre of mass, and a torque about
            // one point says nothing about a torque about another without the arm that separates
            // them — which is the whole reason the contact point is booked rather than derived.
            handedTorque += -shipOp.spun
        }

        // ── Position: out of whatever it is in, along the normals that put it there ─
        //
        // Deepest push per direction rather than the sum, so a cell touching two tiles of the same
        // flat wall is one wall and not two — summing would launch a body off any surface wider
        // than itself.
        //
        // ⚠️ **The depth is projected onto the normal before it is compared**, and step 4 is what
        // made that mandatory. A box against a box has an axis-aligned normal and the projection is
        // the identity, which is why the earlier form — the depth itself, filed under the sign of
        // the normal's component — was right for as long as it was the only shape. A disc against a
        // box corner reports a *diagonal* normal, and filing the whole radial depth under both axes
        // pushes a body out by `depth` along x **and** `depth` along y, up to 1.41× too far and in
        // the wrong direction. What that did on a wall the body was grazing was slide it along the
        // wall rather than off it, and the overlap survived the tick — which is how a rock grinding
        // along the top bulkhead left the ship. The tunnelling test caught it four ticks later.
        for (i in 0 until n) { pushLeft[i] = 0L; pushRight[i] = 0L; pushUp[i] = 0L; pushDown[i] = 0L }
        pairDepth.clear()
        for (c in contacts) {
            if (c.other != Contact.HULL && RockContact.separateAlongCentres) {
                // ── Body against body: along the line of centres, not along the normals ───
                //
                // ⚠️ **A lattice of discs cannot be pushed off another lattice of discs by its own
                // contact normals**, and this is the step's sharpest surprise. Two blobs overlapping
                // by half a tile have every cell of one sitting midway between two cells of the
                // other: each body is asked to go left exactly as hard as it is asked to go right,
                // the deepest-push-per-direction rule subtracts one from the other, and the pair
                // sits interpenetrated for ever.
                //
                // It is not a measure-zero curiosity either, which is what makes it worth a rule of
                // its own. Measured: a pair placed 3.3 tiles apart eased out to exactly 3.5 and
                // **stopped there** — the half-tile interlock is where the per-axis push converges,
                // so it is an attractor rather than a coincidence, and a settling pile would find it
                // on its own.
                //
                // The line between the two centres of mass is the one direction that cannot cancel:
                // two distinct bodies have distinct centres, and separating along it is what "ease
                // them apart" has always meant. It is used for the *position* only — the velocity
                // solve still answers every contact on its own normal, which is where the physics
                // is — so a rock resting on a rock is still held up by a vertical impulse and merely
                // un-overlapped along the line joining them.
                val key = c.body.toLong() * n + c.other
                if (c.depth > (pairDepth[key] ?: 0L)) pairDepth[key] = c.depth
                continue
            }
            // ⚠️ **Half each when both sides can move**, and the whole of it against the ship. Two
            // rocks each pushed out by the full depth separate by twice their overlap, which on a
            // settled pile is a pump: every substep parts them, gravity closes them, and the stack
            // breathes.
            val shared = c.other != Contact.HULL
            val alongX = rotScale(c.depth, c.normalX) / (if (shared) 2L else 1L)
            val alongY = rotScale(c.depth, c.normalY) / (if (shared) 2L else 1L)
            val a = c.body
            if (alongX > 0L) pushRight[a] = maxOf(pushRight[a], alongX)
            if (alongX < 0L) pushLeft[a] = maxOf(pushLeft[a], -alongX)
            if (alongY > 0L) pushDown[a] = maxOf(pushDown[a], alongY)
            if (alongY < 0L) pushUp[a] = maxOf(pushUp[a], -alongY)
            if (!shared) continue
            // The other side of the same touch, pushed the other way: the normal points out of
            // [Contact.other] and into [Contact.body], so it is the same depth with the sign turned.
            val b = c.other
            if (alongX < 0L) pushRight[b] = maxOf(pushRight[b], -alongX)
            if (alongX > 0L) pushLeft[b] = maxOf(pushLeft[b], alongX)
            if (alongY < 0L) pushDown[b] = maxOf(pushDown[b], -alongY)
            if (alongY > 0L) pushUp[b] = maxOf(pushUp[b], alongY)
        }
        for ((key, depth) in pairDepth) {
            val a = (key / n).toInt()
            val b = (key % n).toInt()
            // ⚠️ Differences before any multiply — §5.3 — and reduced to millitiles besides, because
            // the length of this vector is about to be squared.
            val dx = (ops[a].comX - ops[b].comX) / RigidBody.COM_SCALE
            val dy = (ops[a].comY - ops[b].comY) / RigidBody.COM_SCALE
            val length = isqrt(dx * dx + dy * dy)
            // Concentric bodies have no line of centres, so there is nothing to separate along. The
            // next tick's motion gives them one; the same choice [contactBetween] makes for two
            // discs sharing a centre, for the same reason.
            if (length <= 0L) continue
            // ⚠️ **Half each**, because both sides move. Two rocks each pushed out by the full depth
            // separate by twice their overlap, which on a settled pile is a pump: every substep
            // parts them, gravity closes them, and the stack breathes.
            // Magnitude in and the sign reapplied after, the way [unitOf] and [momentOf] already do
            // it: [scaledRatio] guards against a negative scale and a line of centres points
            // whichever way it likes.
            val outX = signed(dx, scaledRatio(if (dx < 0L) -dx else dx, length, depth / 2L))
            val outY = signed(dy, scaledRatio(if (dy < 0L) -dy else dy, length, depth / 2L))
            if (outX > 0L) { pushRight[a] = maxOf(pushRight[a], outX); pushLeft[b] = maxOf(pushLeft[b], outX) }
            if (outX < 0L) { pushLeft[a] = maxOf(pushLeft[a], -outX); pushRight[b] = maxOf(pushRight[b], -outX) }
            if (outY > 0L) { pushDown[a] = maxOf(pushDown[a], outY); pushUp[b] = maxOf(pushUp[b], outY) }
            if (outY < 0L) { pushUp[a] = maxOf(pushUp[a], -outY); pushDown[b] = maxOf(pushDown[b], -outY) }
        }

        // ⚠️ Pushed out along the **world's** axes, which is where the depths are measured now, so
        // the correction is added to the world position rather than applied to a grid pose and read
        // back. ⚠️ This is the one place the change is not merely a change of bookkeeping: the
        // deepest-push-per-direction rule files a push under an axis, and the axes are the world's
        // rather than the deck's. It is the same rule about the same normals — a flat wall is still
        // one wall and not several — but a body wedged into a corner of a *turned* ship now files
        // the two walls it is touching against world x and y instead of grid x and y. The velocity
        // solve is untouched by this: it answers every contact on its own normal either way.
        //
        // Held to what is left of this tick's [depenetration] allowance, which is unlimited for a
        // body that is merely being stopped by a wall and a tenth of a tile for one that began the
        // tick inside something — see the note above for why the second needs a budget at all.
        for (i in 0 until n) {
            var moveX = pushRight[i] - pushLeft[i]
            var moveY = pushDown[i] - pushUp[i]
            val asked = maxOf(abs(moveX), abs(moveY))
            if (asked > depenetration[i]) {
                moveX = moveX * depenetration[i] / asked
                moveY = moveY * depenetration[i] / asked
                depenetration[i] = 0L
            } else if (depenetration[i] != Long.MAX_VALUE) {
                depenetration[i] -= asked
            }
            px[i] = turned[i]!!.x + moveX
            py[i] = turned[i]!!.y + moveY
            ang[i] = turned[i]!!.ang.raw.toLong()
        }
    }

    return SweptBodies(
        List(n) { i ->
            bodies[i].copy(
                positionX = px[i], positionY = py[i],
                impulseX = ix[i], impulseY = iy[i],
                ang = Coord(ang[i].toInt()), angImpulse = angImpulse[i],
            )
        },
        handedX, handedY, handedTorque,
        buildList {
            for (i in 0 until n) {
                if (loudest[i] <= 0L) continue
                add(Impact(i, loudestX[i], loudestY[i], loudest[i], loudestHull[i]))
            }
        },
    )
}

private fun abs(v: Long): Long = if (v < 0L) -v else v

/** [magnitude], carrying [like]'s sign. */
private fun signed(like: Long, magnitude: Long): Long = if (like < 0L) -magnitude else magnitude

/**
 * How the vessel is placed and where it is going, for one tick — everything a sweep needs to know
 * about the thing it might hit.
 *
 * Bundled rather than passed as four longs because the sweep now needs the *pose*, not just the
 * velocity, and a pose plus its rate is one idea. It is also the shape the unified body will take:
 * every operand of a collision is a pose and a motion, and the vessel is about to be one of them.
 */
class ShipMotion(
    val pose: Pose,
    /** World-frame, per tick, at [Flight.PER_TILE] to the tile. */
    val velocityX: Long,
    val velocityY: Long,
    /** [Coord] raw per tick. */
    val angVel: Long,
)
