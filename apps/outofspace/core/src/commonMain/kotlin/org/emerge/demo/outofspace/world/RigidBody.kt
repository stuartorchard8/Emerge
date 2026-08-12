package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.chem.Mixture

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
 * ⚠️ Two frames: [impulseX/Y] in world frame, [positionX/Y] in vessel's grid frame.
 * Astern drift: grid moves by ship velocity each tick, so bodies drift relative to ship.
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
    /** Top-left corner of [cells], in the vessel's grid frame, in billionths [Flight.PER_TILE] counts. */
    val positionX: Long,
    val positionY: Long,
    /** Momentum in world frame (not vessel frame — ship's frame accelerates). */
    val impulseX: Long,
    val impulseY: Long,
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
    val joules: TileJoules,
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
     * fragment's casing. Held rather than recomputed because [massGrams] is read every tick by
     * every body, and a mixture's density is a loop over the species table.
     */
    val gramsPerTile: Long = when (kind) {
        BodyKind.ROCK -> gramsPerTileOf(oreComposition ?: Mixture.EMPTY)
        BodyKind.FRAGMENT -> machineKind!!.gramsPerTile
    }

    /** Millijoules per kelvin for one of its tiles, from that same composition. */
    val capacityPerTile: Long = when (kind) {
        BodyKind.ROCK -> capacityPerTileOf(oreComposition ?: Mixture.EMPTY)
        BodyKind.FRAGMENT -> machineKind!!.capacityPerTile
    }

    val massGrams: Long get() = filled * gramsPerTile

    /** Millijoules per kelvin, from the same two numbers every other solid's capacity comes from. */
    val capacity: Long get() = filled * capacityPerTile

    val kelvin: Int get() =
        if (capacity <= 0L) Temperature.SPACE_KELVIN else (joules.total / capacity).toInt()

    /** How fast it is going **through the world**, which is not how fast it crosses the grid. */
    val velocityX: Long get() = scaledRatio(impulseX, massGrams, Flight.PER_TILE)
    val velocityY: Long get() = scaledRatio(impulseY, massGrams, Flight.PER_TILE)

    /** Its centre in the vessel's frame, which is what "where is it" means for everything but drawing. */
    val centreX: Long get() = positionX + width * Flight.PER_TILE / 2L
    val centreY: Long get() = positionY + height * Flight.PER_TILE / 2L

    fun copy(
        kind: BodyKind = this.kind,
        width: Int = this.width,
        height: Int = this.height,
        cells: BooleanArray = this.cells,
        positionX: Long = this.positionX,
        positionY: Long = this.positionY,
        impulseX: Long = this.impulseX,
        impulseY: Long = this.impulseY,
        oreComposition: Mixture? = this.oreComposition,
        machineKind: MachineKind? = this.machineKind,
        joules: TileJoules = this.joules,
    ): RigidBody = RigidBody(
        kind = kind, width = width, height = height, cells = cells,
        positionX = positionX, positionY = positionY,
        impulseX = impulseX, impulseY = impulseY,
        oreComposition = oreComposition, machineKind = machineKind,
        joules = joules,
    )

    override fun equals(other: Any?): Boolean =
        this === other || (other is RigidBody &&
            kind == other.kind &&
            width == other.width && height == other.height && cells.contentEquals(other.cells) &&
            positionX == other.positionX && positionY == other.positionY &&
            impulseX == other.impulseX && impulseY == other.impulseY &&
            oreComposition == other.oreComposition && machineKind == other.machineKind && joules == other.joules)

    override fun hashCode(): Int = (kind.ordinal * 31 + (positionX * 31 + positionY).toInt()) * 31 + cells.contentHashCode()

    override fun toString(): String =
        "${kind.name}(${width}x$height, ${filled} cells, ${massGrams}g at " +
            "${positionX / Flight.PER_TILE},${positionY / Flight.PER_TILE})"

    companion object {
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
                joules = TileJoules.uniform(filled, capacityPerTileOf(composition) * kelvin),
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
class BodyStep(val bodies: List<RigidBody>, val handedX: Long, val handedY: Long)

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
    shipVelocityX: Long,
    shipVelocityY: Long,
    shipMassGrams: Long,
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
        if (shipMassGrams <= 0L) RockContact.restingSpeed(felt)
        else RockContact.restingSpeed(felt + scaledRatio(felt, shipMassGrams, mass))

    var handedX = 0L
    var handedY = 0L
    // The ship's velocity moves as it hands momentum out, and the *next* body has to sweep against
    // where the hull is going rather than where it started. Same defect as the stale wall inside
    // [sweepBody] and the same fix, one level up; it only shows with two heavy bodies aboard.
    var shipVx = shipVelocityX
    var shipVy = shipVelocityY
    val moved = bodies.map { body ->
        val mass = body.massGrams
        if (mass <= 0L) return@map body
        val felt = platingFeltBy(grid, body.centreX, body.centreY, platingGravity)
        // ⚠️ The mass is the *scale*, not the numerator. `mass × raw` is a mass times a fixed-point
        // one — one whole g of plating is a raw of [Flight.FRAC_ONE], 2.1e9 — so written the obvious
        // way round it wraps for any body over about four kilograms at a microgram per unit. An 83 kg
        // rock therefore did not fall at all, and `RockContactTest` reported that as "the body never
        // landed": a wrapped impulse reads as an *absence*, which is the rescale's standing lesson.
        val platingX = scaledRatio(felt.x.raw, Flight.FRAC_ONE, mass)
        val platingY = scaledRatio(felt.y.raw, Flight.FRAC_ONE, mass)
        val swept = sweepBody(
            grid, structure, body,
            shipVx, shipVy, shipMassGrams,
            restingSpeed(felt.x.raw, mass), restingSpeed(felt.y.raw, mass),
        )
        handedX += swept.impulseX + platingX
        handedY += swept.impulseY + platingY
        if (shipMassGrams > 0L) {
            shipVx += scaledRatio(-(swept.impulseX + platingX), shipMassGrams, Flight.PER_TILE)
            shipVy += scaledRatio(-(swept.impulseY + platingY), shipMassGrams, Flight.PER_TILE)
        }
        swept.body.copy(
            impulseX = swept.body.impulseX + platingX,
            impulseY = swept.body.impulseY + platingY,
        )
    }
    return BodyStep(moved, handedX, handedY)
}
