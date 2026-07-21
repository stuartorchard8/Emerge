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

// Values live in CytoTuning (the single tuning sheet); kept as top-level names for ergonomic use.
val MIN_RADIUS = CytoTuning.MIN_RADIUS
const val RADIUS_ELASTICITY = CytoTuning.RADIUS_ELASTICITY

/** Total atoms in a molecule-count map (Σ count × molecule length). */
fun atomCount(molecules: Map<String, Int>): Int {
    var s = 0
    for ((species, n) in molecules) s += species.length * n
    return s
}

/** Cell mass = its **total atoms** (cytoplasm + biomass), min 1. Atoms are conserved and *additive*,
 *  so division (atoms split between daughters) conserves momentum, and shedding/absorbing matter
 *  changes mass — the basis of the variable-mass propulsion (see CytoBiologySystem). Only mass *ratios*
 *  matter to the physics (spring/contact weighting is ratio-based), so the absolute scale is free. */
fun cellMass(cytoplasm: Map<String, Int>, biomass: Map<String, Int>): UInt =
    max(1, atomCount(cytoplasm) + atomCount(biomass)).toUInt()

/** Total atoms in an id-keyed [MoleculeStore] (Σ count × molecule length). */
fun atomCount(molecules: MoleculeStore): Int {
    var s = 0
    for (i in 0 until molecules.size) s += SpeciesRegistry.atomCount(molecules.idAt(i)) * molecules.countAt(i)
    return s
}

/** Cell mass from id-keyed stores (the hot-path form of [cellMass]). */
fun cellMass(cytoplasm: MoleculeStore, biomass: MoleculeStore): UInt =
    max(1, atomCount(cytoplasm) + atomCount(biomass)).toUInt()

/**
 * Spawns a cell entity: engine physics components + the [CytoCellComponent] biology.
 * Radius is converted from logical to the engine fixed-point scale ([CytoUnits]).
 */
fun SimBuilder.spawnCell(
    pos: Coord2,
    vel: Coord2,
    type: CellType,
    cytoplasm: Map<String, Int> = emptyMap(),
    biomass: Map<String, Int>? = null,
    logicalRadius: Frac = MIN_RADIUS,
    sticky: Boolean = false,
    genome: List<Gene> = genomeForType(type),
): EntityId {
    val biomass = biomass ?: starterBiomassFor(genome)
    val radius = logicalRadius.coerceAtLeast(MIN_RADIUS)
    val id = spawnBody(
        pos = pos,
        vel = vel,
        ang = Coord(0),
        angVel = Coord(0),
        mass = cellMass(cytoplasm, biomass),
        radius = CytoUnits.len(CytoTuning.physicalRadius(radius).toFloat()),   // capped collider; logicalRadius below stays emergent
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

/** Default biomass for a freshly-spawned cell — value in [CytoSeed.STARTER_BIOMASS] (initial data). */
val STARTER_BIOMASS: Map<String, Int> = CytoSeed.STARTER_BIOMASS

/**
 * The starting biomass for a cell with this [genome], made of a chemical the genome can actually produce so
 * a founder doesn't begin with molecules it has no gene to make (which used to seed `gb`/`br` into cells that
 * never touch them). We take the genome's **first Convert gene** — the reaction that locks a molecule into
 * biomass — and fill to the same total **atom count** as [STARTER_BIOMASS] with that molecule. Falls back
 * to [STARTER_BIOMASS] only when the genome has no Convert gene. A monomer Convert is now a valid founder
 * (biomass counts atoms, so a monomer collector has real mass), so it is seeded with monomers rather than
 * rejected as it was under the old bond-count measure.
 */
fun starterBiomassFor(genome: List<Gene>): Map<String, Int> {
    val chem = genome.firstOrNull { it.action.type == ActionType.Convert }?.action?.a
        ?.takeIf { it.isNotEmpty() } ?: return STARTER_BIOMASS
    val atomsPerMolecule = chem.length
    val targetAtoms = totalBiomass(STARTER_BIOMASS)
    val count = (targetAtoms / atomsPerMolecule).coerceAtLeast(1)
    return mapOf(chem to count)
}
