package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.sim.core.physics.primitives.Coord

/** What kind of rigid body this is. */
enum class BodyKind {
    /** An ore rock from the field. */
    ROCK,
    /** A machine casing torn loose by dismantling. */
    FRAGMENT,
}

/**
 * Free-floating solid (rock or fragment). Own grid, momentum, temperature.
 *
 * **One frame: the world.** Both [positionX]/[positionY] and [impulseX]/[impulseY] are world
 * quantities, so there is nothing to convert between them and no rotating reference frame to carry
 * fictitious forces. Step 1 of `PLAN_rigid_bodies.md` moved the position here; it used to be in the
 * vessel's grid frame, which was only valid while the vessel's angle was zero and which could not
 * survive vessels and bodies being one kind of thing.
 *
 * Everything that speaks **tiles** — the hull test, the extractor, the spawner, the renderer — asks
 * for [localX]/[localY] against [VesselState.pose] instead. Astern drift is not a rule any more: a
 * body holds still in the world and the grid slides out from under it, which is what it always
 * meant.
 *
 * Not stored in bodiesOf (derives from machines/conduits/bridges).
 */
class RigidBody(
    /** What kind of body this is — determines composition vs machineKind metadata. */
    val kind: BodyKind,
    /** The shape's bounding box, in cells. */
    val width: Int,
    val height: Int,
    /** Which cells of that box are solid, row-major. A body is rarely a rectangle. */
    val cells: BooleanArray,
    /** Top-left corner of [cells], **in the world**, in billionths [Flight.PER_TILE] counts. */
    val positionX: Long,
    val positionY: Long,
    /** Momentum in world frame (not vessel frame — ship's frame accelerates). */
    val impulseX: Long,
    val impulseY: Long,
    /**
     * How far it is turned relative to open space — the exact counterpart of [VesselState.ang], in
     * the same unit and stored for the same reason.
     *
     * Step 3 of `PLAN_rigid_bodies.md`. A body's orientation is a history, not a derivation: it is
     * the integral of every twist it has ever taken and nothing else in the save can reconstruct it.
     */
    val ang: Coord = Coord(0),
    /**
     * Its angular momentum, in mass·tile²/tick — the counterpart of [VesselState.angImpulse].
     *
     * Stored rather than the angular *velocity* because that is the conserved quantity: a body that
     * loses a cell to an extractor keeps its momentum and changes its spin, which is the way round
     * that stays right without anybody having to remember to fix it up.
     */
    val angImpulse: Long = 0L,
    /** What a rock is made of, as proportions. Null for fragments (they carry [machineKind] instead). */
    val oreComposition: Mixture? = null,
    /** Machine type for fragments. Null for rocks. Needed for rendering and future grinder interaction. */
    val machineKind: MachineKind? = null,
    /**
     * Thermal energy, **per filled cell**, in the energy unit [org.emerge.demo.outofspace.num.Budget]
     * states — indexed by a cell's ordinal among the filled ones, not by its position in [cells].
     *
     * One figure for the whole body until step 8 of `PLAN_unit_rescale.md`, and that was the single
     * tightest quantity in the game: twenty-one tiles of solid rock at three thousand kelvin, in one
     * `Long`. Measured, it supported a mass unit 516 times coarser than the target, where a *tile* of
     * the same rock supports 966,000. Spreading the same energy over the cells that hold it is what
     * makes the microgram rebaseline reachable.
     *
     * ⚠️ It buys **range and nothing else today**. A free body is not in [bodiesOf], so it conducts
     * with nothing — not with the ship, and not internally between its own cells. A rock is still
     * isothermal in practice; this only stops it having to say so in one integer. Putting bodies into
     * `stepSolidHeat` would make the per-cell figures mean something, and is not this step.
     */
    val energy: TileEnergy,
) {
    init {
        require(cells.size == width * height) { "a ${width}x$height body cannot have ${cells.size} cells" }
        require(kind == BodyKind.ROCK && oreComposition != null || kind == BodyKind.FRAGMENT && machineKind != null || kind == BodyKind.ROCK && machineKind == null || kind == BodyKind.FRAGMENT && oreComposition == null) {
            "kind $kind must have oreComposition for ROCK, machineKind for FRAGMENT"
        }
    }

    /** How many cells of it there are — what everything about the body scales with. */
    val filled: Int = cells.count { it }

    /**
     * What one of its tiles weighs, from **what the body is actually made of**: a rock's ore, a
     * fragment's casing. Held rather than recomputed because [mass] is read every tick by
     * every body, and a mixture's density is a loop over the species table.
     */
    val massPerTile: Long = when (kind) {
        BodyKind.ROCK -> massPerTileOf(oreComposition ?: Mixture.EMPTY)
        BodyKind.FRAGMENT -> machineKind!!.massPerTile
    }

    /** Millijoules per kelvin for one of its tiles, from that same composition. */
    val capacityPerTile: Long = when (kind) {
        BodyKind.ROCK -> capacityPerTileOf(oreComposition ?: Mixture.EMPTY)
        BodyKind.FRAGMENT -> machineKind!!.capacityPerTile
    }

    val mass: Long get() = filled * massPerTile

    /** Millijoules per kelvin, from the same two numbers every other solid's capacity comes from. */
    val capacity: Long get() = filled * capacityPerTile

    val kelvin: Int get() =
        if (capacity <= 0L) Temperature.SPACE_KELVIN else (energy.total / capacity).toInt()

    /** How fast it is going **through the world**, which is not how fast it crosses the grid. */
    val velocityX: Long get() = scaledRatio(impulseX, mass, Flight.PER_TILE)
    val velocityY: Long get() = scaledRatio(impulseY, mass, Flight.PER_TILE)

    /**
     * Where it is and how far it is turned — everything a transform needs, in one object.
     *
     * ⚠️ Built fresh on every read, and [Pose] runs a CORDIC loop in its constructor. Hoist it out
     * of a loop over cells; it is cheap once a tick and not cheap once a cell.
     */
    val pose: Pose get() = Pose(positionX, positionY, ang)

    /**
     * The same pose expressed in [ship]'s grid, which is where every tile index in the game lives.
     *
     * Composed rather than converted: the corner goes through [Pose.toLocalX] and the angles simply
     * subtract, because rotations commute in two dimensions. That identity is what lets a body have
     * an orientation without anything downstream of it learning about world coordinates.
     */
    fun poseIn(ship: Pose): Pose = Pose(
        ship.toLocalX(positionX, positionY),
        ship.toLocalY(positionX, positionY),
        Coord(ang.raw - ship.ang.raw),
    )

    /**
     * Where its mass is and how reluctantly it spins — [cellDistribution] over its own cells.
     *
     * Held, not computed per read: it is a double walk of the cell grid and the solver asks for it
     * several times a substep. A body's cells never change without a new body being built, so there
     * is nothing for it to go stale against — unlike the vessel's, which moves as cargo does.
     */
    val about: MassDistribution = cellDistribution(width, height, cells, massPerTile)

    /** Its spin, in [Coord] raw per tick — derived from [angImpulse] exactly as [velocityX] is. */
    val angVel: Long get() = angularVelocity(angImpulse, about)

    /** Its centre of mass, in the world — the point it actually spins about. */
    val comX: Long get() = pose.toWorldX(about.comX * COM_SCALE, about.comY * COM_SCALE)
    val comY: Long get() = pose.toWorldY(about.comX * COM_SCALE, about.comY * COM_SCALE)

    /**
     * The centre of its **bounding box**, in the world — where it looks like it is, for the plating
     * test, the spawner and the tests that watch it fall.
     *
     * Not the centre of mass, which is [comX], and the difference is real for anything that is not a
     * rectangle. This is the "is it over the deck" question, and that one wants the silhouette.
     */
    val centreX: Long get() = pose.toWorldX(width * Flight.PER_TILE / 2L, height * Flight.PER_TILE / 2L)
    val centreY: Long get() = pose.toWorldY(width * Flight.PER_TILE / 2L, height * Flight.PER_TILE / 2L)

    /**
     * Its local origin in [pose]'s frame — grid coordinates, which is what every tile index in the
     * game is built from.
     *
     * ⚠️ This is the origin **only**, not the placement: since step 3 a body has an orientation of
     * its own, so knowing where its corner landed does not tell you where its cells are. Anything
     * that walks cells wants [poseIn] instead.
     */
    fun localX(pose: Pose): Long = pose.toLocalX(positionX, positionY)

    fun localY(pose: Pose): Long = pose.toLocalY(positionX, positionY)

    /** Its bounding-box centre in [pose]'s frame — what plating and the extractor's reach want. */
    fun localCentreX(pose: Pose): Long = pose.toLocalX(centreX, centreY)

    fun localCentreY(pose: Pose): Long = pose.toLocalY(centreX, centreY)

    /** Its centre of mass in [pose]'s frame — what a torque arm wants, and only that. */
    fun localComX(pose: Pose): Long = pose.toLocalX(comX, comY)

    fun localComY(pose: Pose): Long = pose.toLocalY(comX, comY)

    /**
     * What [cell] of this body is, geometrically — a disc, for every cell of every body today.
     *
     * ⚠️ A function rather than a stored array, and that is the whole of what requirement 4 of
     * `PLAN_rigid_bodies.md` needs right now. Stu wants cells to be **selectively** OBBs or triangles
     * later; storing a shape per cell before any of them differ would be a column of identical values
     * in every body and in every save, and the narrow phase cannot tell the difference — it asks this
     * question and dispatches on the answer. When shapes start to differ, this reads an array.
     */
    fun shapeAt(cell: Int): CellShape = CellShape.CELL

    fun copy(
        kind: BodyKind = this.kind,
        width: Int = this.width,
        height: Int = this.height,
        cells: BooleanArray = this.cells,
        positionX: Long = this.positionX,
        positionY: Long = this.positionY,
        impulseX: Long = this.impulseX,
        impulseY: Long = this.impulseY,
        ang: Coord = this.ang,
        angImpulse: Long = this.angImpulse,
        oreComposition: Mixture? = this.oreComposition,
        machineKind: MachineKind? = this.machineKind,
        energy: TileEnergy = this.energy,
    ): RigidBody = RigidBody(
        kind = kind, width = width, height = height, cells = cells,
        positionX = positionX, positionY = positionY,
        impulseX = impulseX, impulseY = impulseY,
        ang = ang, angImpulse = angImpulse,
        oreComposition = oreComposition, machineKind = machineKind,
        energy = energy,
    )

    override fun equals(other: Any?): Boolean =
        this === other || (other is RigidBody &&
            kind == other.kind &&
            width == other.width && height == other.height && cells.contentEquals(other.cells) &&
            positionX == other.positionX && positionY == other.positionY &&
            impulseX == other.impulseX && impulseY == other.impulseY &&
            ang == other.ang && angImpulse == other.angImpulse &&
            oreComposition == other.oreComposition && machineKind == other.machineKind && energy == other.energy)

    override fun hashCode(): Int = (kind.ordinal * 31 + (positionX * 31 + positionY).toInt()) * 31 + cells.contentHashCode()

    override fun toString(): String =
        "${kind.name}(${width}x$height, ${filled} cells, ${mass}g at " +
            "${positionX / Flight.PER_TILE},${positionY / Flight.PER_TILE})"

    companion object {
        /**
         * Millitiles to [Flight.PER_TILE]s: what a centre of mass has to be multiplied by to stand
         * next to a position. A thousandth of a tile is as fine as a lever arm is ever measured.
         */
        const val COM_SCALE: Long = Flight.PER_TILE / Rotation.MILLI_TILE

        /**
         * How well a rock conducts heat — the one solid property its composition does not supply.
         *
         * Mass and heat capacity now come from [oreComposition] tile by tile, so what is left here
         * is conductance, and [Material.Firebrick] is not a joke and not a placeholder for it: an
         * asteroid is a poor conductor, which is the property a furnace lining is chosen for.
         * Per-species conductance would need a number [Species] does not carry, so a rock conducts
         * like rock regardless of what it assays at.
         */
        val MATERIAL: Material = Material.Firebrick

        /**
         * Tolerance for fragment shape derivation: 0.1 tile, shaved on exposed edges.
         * Not applied to rocks — rockBlob already rasterises a disc shape.
         */
        val TOLERANCE: Long = Flight.PER_TILE / 10L

        /**
         * A blob roughly [radius] cells across.
         *
         * A disc rather than a square, because the first thing anyone will do is look at it, and a
         * square body reads as a crate. Rasterised on the cell centres so it is symmetric.
         */
        fun rockBlob(
            radius: Int,
            positionX: Long,
            positionY: Long,
            composition: Mixture,
            impulseX: Long = 0L,
            impulseY: Long = 0L,
            kelvin: Int = Temperature.AMBIENT_KELVIN,
        ): RigidBody {
            val d = radius * 2 + 1
            val cells = BooleanArray(d * d)
            for (y in 0 until d) {
                for (x in 0 until d) {
                    val dx = x - radius
                    val dy = y - radius
                    cells[y * d + x] = dx * dx + dy * dy <= radius * radius + radius
                }
            }
            val filled = cells.count { it }
            return RigidBody(
                kind = BodyKind.ROCK,
                width = d, height = d, cells = cells,
                positionX = positionX, positionY = positionY,
                impulseX = impulseX, impulseY = impulseY,
                oreComposition = composition,
                energy = TileEnergy.uniform(filled, capacityPerTileOf(composition) * kelvin),
            )
        }
    }
}

/**
 * The only force a vessel exerts on a body at a distance: the deck plating, and only over the deck.
 *
 * [platingGravity] is [VesselState.gravity], the *setting* — a field the ship makes, which is why it
 * stops where the ship does. There is no second term. A body's momentum is written in the world
 * frame, and the world frame is inertial, so the vessel's own acceleration is not a force on
 * anything: it shows up in [driftBodies] as the grid sliding under the body instead. See [RigidBody].
 */
fun platingFeltBy(grid: Grid, centreX: Long, centreY: Long, platingGravity: org.emerge.sim.core.physics.primitives.Frac2): org.emerge.sim.core.physics.primitives.Frac2 {
    val tx = centreX / Flight.PER_TILE
    val ty = centreY / Flight.PER_TILE
    val aboard = centreX >= 0L && centreY >= 0L && tx < grid.width && ty < grid.height
    return if (aboard) platingGravity else org.emerge.sim.core.physics.primitives.Frac2(
        org.emerge.sim.core.physics.primitives.Frac(0L),
        org.emerge.sim.core.physics.primitives.Frac(0L)
    )
}

/**
 * What one tick did to every body, and what it therefore did to the ship.
 *
 * [handedX] is every gram·tile of momentum the **vessel** gave the bodies this tick, by any means; the
 * ship owes itself the negative of it, and booking both in the same breath is what makes the whole
 * business conserve by construction. See [VesselState.bodyImpulseX].
 */
class BodyStep(
    val bodies: List<RigidBody>,
    val handedX: Long,
    val handedY: Long,
    /**
     * The twist that went with it, about the vessel's centre of mass — booked at the body's own
     * centre, because a rock bouncing off a nacelle spins the ship and a rock bouncing off the nose
     * does not. The ship owes itself the negative of this, exactly as it does of [handedX].
     */
    val handedTorque: Long = 0L,
)

/**
 * Body drift: grid moves by ship velocity, body advances by (body - ship) velocity.
 * Sweep (not jump) prevents bulkhead stepping. Plating applied after sweep (tick ordering).
 * ⚠️ Plating costs the ship (prevents momentum pump from gravity).
 */
fun driftBodies(
    grid: Grid,
    structure: StructureMap,
    bodies: List<RigidBody>,
    platingGravity: org.emerge.sim.core.physics.primitives.Frac2,
    ship: ShipMotion,
    shipMass: Long,
    about: MassDistribution = MassDistribution.EMPTY,
    /** The ship's machines by tile — carried this far for one reason, [frictionBetween]. */
    machines: List<Machine?>? = null,
): BodyStep {
    if (bodies.isEmpty()) return BodyStep(bodies, 0L, 0L)
    /**
     * ⚠️ The resting threshold is about a **closing** speed, so it is built from the acceleration
     * that closes the gap — and that depends on the body, which is why it is computed per body
     * rather than once for the tick.
     *
     * Two changes from the version this replaces, and they pull the same way.
     *
     * **It is the relative acceleration.** The plating pushes the body toward the deck and the
     * deck's reaction pushes the ship the other way, so one tick of free flight opens the gap by
     * `a(1 + m/M)`, not by `a`. Against a hull that dwarfed every rock those were the same number.
     * Against a 40-tonne box and an 83-tonne rock they differ by a factor of three.
     *
     * **[shipAcceleration] is gone from it**, and its being there is what actually did the damage.
     * It is the ship's *net* acceleration, which includes the ship's reaction to this very body's
     * plating — the one term the deck cancels the instant the body is resting. So the threshold was
     * circular: the harder the body pressed, the lower the bar for calling it asleep went, and in
     * the steady state the two terms cancelled outright and left nothing but [RockContact.REST_FLOOR].
     * A rock that should have been lying on the floor bounced 1.4 tiles for ever in a perfectly
     * stable limit cycle, with every quantity conserved and nothing to see in the ledger.
     *
     * Thrust does not belong here for the same reason: a ship under burn drags a resting body along
     * *through the deck*, and a force transmitted by the contact cannot also be a force opening it.
     */
    fun restingSpeed(felt: Long, mass: Long): Long =
        if (shipMass <= 0L) RockContact.restingSpeed(felt)
        else RockContact.restingSpeed(felt + scaledRatio(felt, shipMass, mass))

    var handedX = 0L
    var handedY = 0L
    var handedTorque = 0L
    // The ship's velocity moves as it hands momentum out, and the *next* body has to sweep against
    // where the hull is going rather than where it started. Same defect as the stale wall inside
    // [sweepBody] and the same fix, one level up; it only shows with two heavy bodies aboard.
    var shipVx = ship.velocityX
    var shipVy = ship.velocityY
    val moved = bodies.map { body ->
        val mass = body.mass
        if (mass <= 0L) return@map body
        // Plating is a field the *ship* makes, so whether a body is over the deck is a question
        // about the grid, asked in the grid's frame.
        val felt = platingFeltBy(
            grid,
            body.localCentreX(ship.pose),
            body.localCentreY(ship.pose),
            platingGravity,
        )
        // ⚠️ The mass is the *scale*, not the numerator. `mass × raw` is a mass times a fixed-point
        // one — one whole g of plating is a raw of [Flight.FRAC_ONE], 2.1e9 — so written the obvious
        // way round it wraps for any body over about four kilograms at a microgram per unit. An 83 kg
        // rock therefore did not fall at all, and `RockContactTest` reported that as "the body never
        // landed": a wrapped impulse reads as an *absence*, which is the rescale's standing lesson.
        val platingX = scaledRatio(felt.x.raw, Flight.FRAC_ONE, mass)
        val platingY = scaledRatio(felt.y.raw, Flight.FRAC_ONE, mass)
        val swept = sweepBody(
            grid, structure, body,
            ShipMotion(ship.pose, shipVx, shipVy, ship.angVel), shipMass, about,
            restingSpeed(felt.x.raw, mass), restingSpeed(felt.y.raw, mass),
            machines,
        )
        val gaveX = swept.impulseX + platingX
        val gaveY = swept.impulseY + platingY
        handedX += gaveX
        handedY += gaveY
        // ⚠️ Two torques, from two different arms, and conflating them was the bug this split fixes.
        //
        // The **contact** torque comes back from the sweep already booked at the points the touches
        // actually happened, which is the only place it can be booked correctly — a rock landing on
        // one corner twists the ship differently from a rock landing flat, and a figure derived from
        // the total impulse cannot tell those apart.
        //
        // The **plating** torque is a separate arm: a field acts at the body's centre of mass, not
        // at whatever it happens to be touching, and it acts whether the body is touching anything
        // at all. Booked in millitiles, which is what [torqueAbout] works in.
        //
        // ⚠️ [SweptBody.torque] is what the ship **received**, and [handedTorque] is what it
        // **gave** — hence the sign. Equal and opposite, because the two impulses act at the same
        // point, and the whole exchange is booked about the ship's centre of mass either way.
        handedTorque += -swept.torque + torqueAbout(
            about,
            body.localComX(ship.pose) / RigidBody.COM_SCALE,
            body.localComY(ship.pose) / RigidBody.COM_SCALE,
            platingX, platingY,
        )
        if (shipMass > 0L) {
            shipVx += scaledRatio(-gaveX, shipMass, Flight.PER_TILE)
            shipVy += scaledRatio(-gaveY, shipMass, Flight.PER_TILE)
        }
        swept.body.copy(
            impulseX = swept.body.impulseX + platingX,
            impulseY = swept.body.impulseY + platingY,
        )
    }
    return BodyStep(moved, handedX, handedY, handedTorque)
}
