package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.spawnBody
import kotlin.math.max

/** Min cell radius (logical), from the original Cyto `Cell`. */
val MIN_RADIUS = Frac(1, 4)
const val RADIUS_ELASTICITY = 3
val MAX_CHEM = Frac(1, 1)

/** Cell mass ∝ radius (original: density = 1/r, mass = density·πr² = πr), as a UInt. */
fun cellMass(logicalRadius: Frac): UInt = max(1, (logicalRadius.toFloat() * 1000f).toInt()).toUInt()

/**
 * Spawns a cell entity: engine physics components + the [CytoCellComponent] biology.
 * Radius is converted from logical to the engine fixed-point scale ([CytoUnits]).
 */
fun SimBuilder.spawnCell(
    pos: Coord2,
    vel: Coord2,
    type: CellType,
    cytoplasm: Map<String, Int> = emptyMap(),
    biomass: Map<String, Int> = STARTER_BIOMASS,
    logicalRadius: Frac = MIN_RADIUS,
    sticky: Boolean = false,
    genome: List<Gene> = genomeForType(type),
): EntityId {
    val radius = logicalRadius.coerceAtLeast(MIN_RADIUS)
    val id = spawnBody(
        pos = pos,
        vel = vel,
        ang = Coord(0),
        angVel = Coord(0),
        mass = cellMass(radius),
        radius = CytoUnits.len(radius.toFloat()),
        bounce = Frac(0),
        rough = Frac(0),
        shape = BodyShape.CIRCLE,
    )
    update<CytoCellComponent>(id) {
        CytoCellComponent(
            type = type,
            logicalRadius = radius,
            cytoplasm = cytoplasm,
            biomass = biomass,
            genome = genome,
            sticky = sticky,
        )
    }
    return id
}

/** Default biomass for a freshly-spawned cell (e.g. a player-placed cell): a little structure so it
 *  doesn't instantly die to the death-on-empty-biomass rule and can be observed. ⚙ */
val STARTER_BIOMASS: Map<String, Int> = mapOf("ab" to 8)
