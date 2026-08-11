package org.emerge.demo.outofspace.world

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
    /** Thermal energy, in the millijoules [MATERIAL] documents. */
    val joules: Long,
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
        BodyKind.FRAGMENT -> machineKind!!.material.gramsPerTile
    }

    /** Millijoules per kelvin for one of its tiles, from that same composition. */
    val capacityPerTile: Long = when (kind) {
        BodyKind.ROCK -> capacityPerTileOf(oreComposition ?: Mixture.EMPTY)
        BodyKind.FRAGMENT -> machineKind!!.material.capacityPerTile
    }

    val massGrams: Long get() = filled * gramsPerTile

    /** Millijoules per kelvin, from the same two numbers every other solid's capacity comes from. */
    val capacity: Long get() = filled * capacityPerTile

    val kelvin: Int get() = if (capacity <= 0L) Temperature.SPACE_KELVIN else (joules / capacity).toInt()

    /** How fast it is going **through the world**, which is not how fast it crosses the grid. */
    val velocityX: Long get() = massGrams.let { if (it <= 0L) 0L else impulseX * Flight.PER_TILE / it }
    val velocityY: Long get() = massGrams.let { if (it <= 0L) 0L else impulseY * Flight.PER_TILE / it }

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
        joules: Long = this.joules,
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
                joules = filled * capacityPerTileOf(composition) * kelvin,
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
 * ⚠️ shipAcceleration only for restingSpeed threshold (not a force on world-frame momentum).
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
    shipAcceleration: org.emerge.sim.core.physics.primitives.Frac2,
): BodyStep {
    if (bodies.isEmpty()) return BodyStep(bodies, 0L, 0L)
    // What presses a body against a surface in the grid's frame: the plating, less the ship's own
    // acceleration. Exactly [experiencedGravity]'s quantity, and for exactly its reason.
    val restX = RockContact.restingSpeed(platingGravity.x.raw - shipAcceleration.x.raw)
    val restY = RockContact.restingSpeed(platingGravity.y.raw - shipAcceleration.y.raw)

    var handedX = 0L
    var handedY = 0L
    val moved = bodies.map { body ->
        val mass = body.massGrams
        if (mass <= 0L) return@map body
        val felt = platingFeltBy(grid, body.centreX, body.centreY, platingGravity)
        val platingX = mass * felt.x.raw / Flight.FRAC_ONE
        val platingY = mass * felt.y.raw / Flight.FRAC_ONE
        val swept = sweepBody(
            grid, structure, body,
            shipVelocityX, shipVelocityY, shipMassGrams,
            restX, restY,
        )
        handedX += swept.impulseX + platingX
        handedY += swept.impulseY + platingY
        swept.body.copy(
            impulseX = swept.body.impulseX + platingX,
            impulseY = swept.body.impulseY + platingY,
        )
    }
    return BodyStep(moved, handedX, handedY)
}
