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
 * ### What holds it up, and what does not
 *
 * A rock is integrated exactly as the ship is — see [Flight]. Momentum is the stored quantity,
 * velocity is that over the mass, and only the position is state, so there is nothing to drift.
 *
 * ⚠️ **The plating does not reach it once it is outside the hull.** [feltBy] gives a rock the deck's
 * artificial gravity only while it is over the grid, and the frame's acceleration *always*. That
 * split is the physics rather than a convenience: the plating is a field the vessel makes and it
 * stops where the vessel does, whereas the acceleration term is not a force at all — it is the price
 * of writing everything in the frame of something that is speeding up, and it applies to a rock a
 * hundred tiles astern exactly as it applies to the air in the hold. Get that backwards and a
 * captured rock either sticks to the ship like a magnet or falls off the bottom of the universe.
 *
 * ### What it does not do yet
 *
 * **Nothing.** It touches nothing, conducts with nothing, and blocks nothing — it flies through the
 * hull and out the far side, and that is increment H1 doing exactly one thing. Contact is H2, the
 * extractor is H3, and the fluid coupling — permeable, reading the pressure field and not writing to
 * it — is H5 and is cuttable. Its energy is counted in the solid ledger from the tick it appears, so
 * that when conduction does arrive there is nothing to reconcile.
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
    /** Momentum in the vessel's frame, in gram·tiles per tick — the same unit the ship's is in. */
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
 * What a rock at [centreX], [centreY] falls toward: the plating if it is over the deck, and the
 * frame's acceleration wherever it is.
 *
 * See [Rock]'s note for why those two are not the same kind of thing and must not be handed out
 * together. [platingGravity] is [VesselState.gravity], the *setting*; [acceleration] is the ship's
 * own `netImpulse / massGrams`, which is the term [experiencedGravity] subtracts for the gas.
 */
fun feltBy(
    grid: Grid,
    centreX: Long,
    centreY: Long,
    platingGravity: Frac2,
    acceleration: Frac2,
): Frac2 {
    val tx = centreX / Flight.PER_TILE
    val ty = centreY / Flight.PER_TILE
    val aboard = centreX >= 0L && centreY >= 0L && tx < grid.width && ty < grid.height
    val px = if (aboard) platingGravity.x.raw else 0L
    val py = if (aboard) platingGravity.y.raw else 0L
    return Frac2(Frac(px - acceleration.x.raw), Frac(py - acceleration.y.raw))
}

/**
 * One tick of free flight for every rock: gravity into momentum, momentum into position.
 *
 * The same order and the same explicitness as the ship's, and for the same reason — the rock moves
 * by the velocity it had at the *start* of the tick, so this tick's push buys next tick's travel. A
 * body that got a free tick of its own acceleration would outrun the ship it is being compared
 * against, by a little, forever.
 */
fun driftRocks(
    grid: Grid,
    rocks: List<Rock>,
    platingGravity: Frac2,
    acceleration: Frac2,
): List<Rock> {
    if (rocks.isEmpty()) return rocks
    return rocks.map { rock ->
        val mass = rock.massGrams
        if (mass <= 0L) return@map rock
        val felt = feltBy(grid, rock.centreX, rock.centreY, platingGravity, acceleration)
        val moved = rock.copy(
            positionX = rock.positionX + rock.velocityX,
            positionY = rock.positionY + rock.velocityY,
        )
        moved.copy(
            impulseX = rock.impulseX + mass * felt.x.raw / Flight.FRAC_ONE,
            impulseY = rock.impulseY + mass * felt.y.raw / Flight.FRAC_ONE,
        )
    }
}
