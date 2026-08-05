package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * Free-floating solid (rock). Own grid, momentum, temperature.
 * ⚠️ Two frames: [impulseX/Y] in world frame, [positionX/Y] in vessel's grid frame.
 * Astern drift: grid moves by ship velocity each tick, so rocks drift relative to ship.
 * Not stored in bodiesOf (derives from machines/conduits/bridges), not Debris (settling would vent it).
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
    /** Momentum in world frame (not vessel frame — ship's frame accelerates). */
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
 * Rock drift: grid moves by ship velocity, rock advances by (rock - ship) velocity.
 * Sweep (not jump) prevents bulkhead stepping. Plating applied after sweep (tick ordering).
 * ⚠️ shipAcceleration only for restingSpeed threshold (not a force on world-frame momentum).
 * ⚠️ Plating costs the ship (prevents momentum pump from gravity).
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
