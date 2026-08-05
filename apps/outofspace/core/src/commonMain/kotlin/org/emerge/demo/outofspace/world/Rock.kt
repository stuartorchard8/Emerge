package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * A free-floating solid: a rock, with its own grid, its own momentum and its own temperature.
 *
 * ### Why this needed a new home rather than a new machine
 *
 * [Body] is not stored. [bodiesOf] *derives* it every tick from the machines, the conduits and the
 * bridges, which is exactly what stops a body's energy and a body's capacity ever disagreeing — and
 * it means a thing in none of those three lists cannot exist. Nor can a rock be [Debris]: the
 * settling pass walks anything standing in [Structure.Vacuum] straight off the rim and books it as
 * vented, on the grounds that there is no deck out there to land on, so a rock represented that way
 * would be thrown overboard on the tick it appeared.
 *
 * So rocks are their own list on [VesselState], with their own ledger — see [VesselState.rockGrams].
 *
 * ### Its own grid, and no rotation
 *
 * [cells] is the rock's shape on its own little lattice, axis-aligned with the vessel's and offset
 * from it by a fraction of a tile. That is the whole of the "grid/grid" part, and the axis-alignment
 * is a **decision** rather than a limitation — see `docs/out-of-space-plan.md` §5f. Rotation means
 * arbitrary-angle overlap, angular momentum, torque from off-centre contacts and a rock whose cells
 * no longer line up with the fluid cells they sit in, all landing on a momentum ledger that only
 * just closed at residual zero. A tumbling rock is worth wanting later and is not what makes the
 * extractor interesting.
 *
 * ### ⚠️ Two frames, on purpose: momentum is the world's, position is the ship's
 *
 * [impulseX] is momentum **in the world frame**, and [positionX] is where the rock is **on the
 * vessel's grid**. That looks like an inconsistency and is the opposite of one.
 *
 * A position has to be in the grid's frame because the grid is what a position *means* here — which
 * tile, which wall, which side of the hold. A momentum must not be, because the vessel's frame
 * accelerates: the instant a rock and the ship exchange an impulse, that exchange changes the frame
 * every *other* rock's velocity is measured against, and a reduced-mass term computed against a
 * moving ruler goes missing without anything failing. In the world frame there is no pseudo-force
 * to write down at all. A free rock has constant momentum, full stop.
 *
 * The astern drift then falls out of the position being relative rather than out of a force: the
 * grid moves by the ship's velocity each tick, so [driftRocks] advances a rock by its velocity
 * *minus the ship's*, and a rock genuinely at rest slides toward the stern of a burning ship
 * because the stern is coming to meet it. That was H1's headline behaviour and it survives the move
 * unchanged — see `RockTest`.
 *
 * ⚠️ **The plating does not reach it once it is outside the hull.** [platingFeltBy] gives a rock the
 * deck's artificial gravity only while it is over the grid, and nothing at all outside it. The
 * plating is a field the vessel makes and it stops where the vessel does. (H1 handed out the frame's
 * acceleration alongside it, which was the same statement written in the vessel's frame; in the
 * world frame that term simply does not exist.)
 *
 * ### What it does not do yet
 *
 * It conducts with nothing, and it blocks nothing: air flows straight through it and it displaces no
 * gas — the permeable coupling is H5 and is cuttable. It still flies through the hull; contact is
 * H2b. Its energy is counted in the solid ledger from the tick it appears, so that when conduction
 * does arrive there is nothing to reconcile.
 */
class Rock(
    /** The shape's bounding box, in cells. */
    val width: Int,
    val height: Int,
    /** Which cells of that box are solid, row-major. A rock is rarely a rectangle. */
    val cells: BooleanArray,
    /** The top-left corner of [cells], in the vessel's frame, in the billionths [Flight.PER_TILE] counts. */
    val positionX: Long,
    val positionY: Long,
    /**
     * Momentum **in the world frame**, in gram·tiles per tick — the same unit the ship's is in, and
     * deliberately not the same frame as [positionX]. See the class note.
     */
    val impulseX: Long,
    val impulseY: Long,
    /** What it is made of, as proportions. The stand-in ore body until there is a reason for more. */
    val composition: Mixture,
    /** Thermal energy, in the millijoules [Material] documents. */
    val joules: Long,
) {
    init {
        require(cells.size == width * height) { "a ${width}x$height rock cannot have ${cells.size} cells" }
    }

    /** How many cells of it there are — what everything about the rock scales with. */
    val filled: Int get() = cells.count { it }

    val massGrams: Long get() = filled * MATERIAL.gramsPerTile

    /** Millijoules per kelvin, from the same two numbers every other solid's capacity comes from. */
    val capacity: Long get() = filled * MATERIAL.capacityPerTile

    val kelvin: Int get() = if (capacity <= 0L) Temperature.SPACE_KELVIN else (joules / capacity).toInt()

    /** How fast it is going **through the world**, which is not how fast it crosses the grid. */
    val velocityX: Long get() = massGrams.let { if (it <= 0L) 0L else impulseX * Flight.PER_TILE / it }
    val velocityY: Long get() = massGrams.let { if (it <= 0L) 0L else impulseY * Flight.PER_TILE / it }

    /** Its centre in the vessel's frame, which is what "where is it" means for everything but drawing. */
    val centreX: Long get() = positionX + width * Flight.PER_TILE / 2L
    val centreY: Long get() = positionY + height * Flight.PER_TILE / 2L

    fun copy(
        positionX: Long = this.positionX,
        positionY: Long = this.positionY,
        impulseX: Long = this.impulseX,
        impulseY: Long = this.impulseY,
        joules: Long = this.joules,
    ): Rock = Rock(width, height, cells, positionX, positionY, impulseX, impulseY, composition, joules)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Rock &&
            width == other.width && height == other.height && cells.contentEquals(other.cells) &&
            positionX == other.positionX && positionY == other.positionY &&
            impulseX == other.impulseX && impulseY == other.impulseY &&
            composition == other.composition && joules == other.joules)

    override fun hashCode(): Int = (positionX * 31 + positionY).toInt() * 31 + cells.contentHashCode()

    override fun toString(): String =
        "Rock(${width}x$height, ${filled} cells, ${massGrams}g at " +
            "${positionX / Flight.PER_TILE},${positionY / Flight.PER_TILE})"

    companion object {
        /**
         * What rocks are made of, thermally.
         *
         * [Material.Firebrick] is not a joke and not a placeholder: an asteroid is a poor conductor
         * with a lot of thermal mass, which is the same pair of properties a furnace lining is chosen
         * for, and inventing a second enum entry with the same two numbers would be inventing a
         * distinction the model cannot express. It gets its own entry the day rocks need to conduct
         * differently from brick — which is the day the extractor's rate becomes a function of
         * temperature, and that is phase change's increment, not this one.
         *
         * ⚠️ Note what this does **not** decide. [composition] is what the rock is made of chemically
         * and is what the extractor will yield; this is what it costs to warm. The two are separate
         * questions and the body model has always kept them so — see [Material]'s note on the masses
         * being tuned while the ratios are real.
         */
        val MATERIAL: Material = Material.Firebrick

        /**
         * A blob roughly [radius] cells across, which is what "a rock" means until something needs
         * more.
         *
         * A disc rather than a square, because the first thing anyone will do is look at it, and a
         * square rock reads as a crate. Rasterised on the cell centres so it is symmetric.
         */
        fun blob(
            radius: Int,
            positionX: Long,
            positionY: Long,
            composition: Mixture,
            impulseX: Long = 0L,
            impulseY: Long = 0L,
            kelvin: Int = Temperature.AMBIENT_KELVIN,
        ): Rock {
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
            return Rock(
                width = d, height = d, cells = cells,
                positionX = positionX, positionY = positionY,
                impulseX = impulseX, impulseY = impulseY,
                composition = composition,
                joules = filled * MATERIAL.capacityPerTile * kelvin,
            )
        }
    }
}

/**
 * The only force a vessel exerts on a rock at a distance: the deck plating, and only over the deck.
 *
 * [platingGravity] is [VesselState.gravity], the *setting* — a field the ship makes, which is why it
 * stops where the ship does. There is no second term. A rock's momentum is written in the world
 * frame, and the world frame is inertial, so the vessel's own acceleration is not a force on
 * anything: it shows up in [driftRocks] as the grid sliding under the rock instead. See [Rock].
 */
fun platingFeltBy(grid: Grid, centreX: Long, centreY: Long, platingGravity: Frac2): Frac2 {
    val tx = centreX / Flight.PER_TILE
    val ty = centreY / Flight.PER_TILE
    val aboard = centreX >= 0L && centreY >= 0L && tx < grid.width && ty < grid.height
    return if (aboard) platingGravity else Frac2(Frac(0L), Frac(0L))
}

/**
 * What one tick did to every rock, and what it therefore did to the ship.
 *
 * [handedX] is every gram·tile of momentum the **vessel** gave the rocks this tick, by any means; the
 * ship owes itself the negative of it, and booking both in the same breath is what makes the whole
 * business conserve by construction. See [VesselState.rockImpulseX].
 */
class RockStep(val rocks: List<Rock>, val handedX: Long, val handedY: Long)

/**
 * One tick of flight for every rock: the grid slides under it, the hull stops it, the plating pulls.
 *
 * ⚠️ The two frames are both here and the arithmetic is where they meet. A rock's velocity is
 * through the **world**; its position is on the **grid**; and the grid is itself moving at
 * [shipVelocityX]. So what a position advances by is the *difference* of the two, and a rock at rest
 * in the world drifts astern of a burning ship for the only reason it ever really did — the ship
 * left, and the rock did not.
 *
 * The order and the explicitness are the ship's own — see [VesselState.positionX]: a rock moves by
 * the velocity it had at the *start* of the tick, so this tick's push buys next tick's travel. A
 * body that got a free tick of its own acceleration would outrun the ship it is being compared
 * against, by a little, forever. [shipVelocityX] is likewise the ship's start-of-tick velocity,
 * which is the same number the ship's own position is advanced by in the same tick, so the two
 * frames can never be half a tick out of step with each other.
 *
 * The travel is a **sweep** rather than a jump, so a rock cannot step over a bulkhead it was going
 * fast enough to cross in one tick, and the hull bounces it — see [sweepRock]. The plating is
 * applied after the sweep, for the same reason the position moves first: this tick's push is next
 * tick's travel.
 *
 * ⚠️ [shipAcceleration] is here for one purpose and it is not a force. A rock's momentum is in the
 * world frame and the ship's acceleration is not a force on it; what it *is* is the speed scale that
 * decides when a bounce is too small to be worth having — see [RockContact.restingSpeed]. Passing
 * gravity itself would be wrong under a burn, which is the only gravity this ship has.
 *
 * ### ⚠️ The plating is not free either, and finding that out was the point of writing this down
 *
 * Everything the vessel hands a rock is returned to [RockStep.handedX] and charged to the ship —
 * **including the plating**, which is a field the vessel *makes* and must therefore pay for. The
 * alternative is a momentum pump you could fly on: gravity pushes a resting rock down for nothing,
 * the deck pushes it back up with a reaction, and the ship climbs forever with a rock sitting on the
 * floor. And the ledger would not have caught it, because a contact-only store makes that reading
 * balance. A rock at rest on a deck now costs the ship exactly nothing, which is what "at rest"
 * means, and a rock in *free fall* over the deck genuinely does push the ship — the field is doing
 * work and something has to be pushing back.
 *
 * In freefall the whole term is zero, so this is a statement about the fixtures and about H4's
 * capture rather than about how the game plays today. It is here because a wrong version of it is
 * invisible until it is enormous.
 */
fun driftRocks(
    grid: Grid,
    structure: StructureMap,
    rocks: List<Rock>,
    platingGravity: Frac2,
    shipVelocityX: Long,
    shipVelocityY: Long,
    shipMassGrams: Long,
    shipAcceleration: Frac2,
): RockStep {
    if (rocks.isEmpty()) return RockStep(rocks, 0L, 0L)
    // What presses a rock against a surface in the grid's frame: the plating, less the ship's own
    // acceleration. Exactly [experiencedGravity]'s quantity, and for exactly its reason.
    val restX = RockContact.restingSpeed(platingGravity.x.raw - shipAcceleration.x.raw)
    val restY = RockContact.restingSpeed(platingGravity.y.raw - shipAcceleration.y.raw)

    var handedX = 0L
    var handedY = 0L
    val moved = rocks.map { rock ->
        val mass = rock.massGrams
        if (mass <= 0L) return@map rock
        val felt = platingFeltBy(grid, rock.centreX, rock.centreY, platingGravity)
        val platingX = mass * felt.x.raw / Flight.FRAC_ONE
        val platingY = mass * felt.y.raw / Flight.FRAC_ONE
        val swept = sweepRock(
            grid, structure, rock,
            shipVelocityX, shipVelocityY, shipMassGrams,
            restX, restY,
        )
        handedX += swept.impulseX + platingX
        handedY += swept.impulseY + platingY
        swept.rock.copy(
            impulseX = swept.rock.impulseX + platingX,
            impulseY = swept.rock.impulseY + platingY,
        )
    }
    return RockStep(moved, handedX, handedY)
}
