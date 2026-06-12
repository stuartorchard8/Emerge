package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.sim.CytoBiologyCore
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterGrid
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.totalBiomassBonds
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.math.absoluteValue
import kotlin.math.sign

/**
 * Applies the structural changes the biology/interaction phases request: detach, destroy (a dead cell
 * recycles **all its matter** to the reservoir, then is removed), weld, and divide (mitosis: an integer
 * split of cytoplasm + biomass, daughter offset along the outward normal, mother's "ahead" springs
 * rewired to it). Runs after biology/connection phases.
 */
object CytoLifecycleSystem : EcsSystem<CytoConfig, SimState, CytoInput> {

    override fun update(
        cfg: CytoConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, CytoInput>,
    ) {
        // Detach: cut every connection of the named cells.
        for (intent in builder.events<DetachIntent>()) {
            for (n in neighboursOf(builder, intent.id)) removeSpringPair(builder, intent.id, n)
        }

        // Destroy: a dying cell returns all its molecules to its reservoir grid-cell (closing the matter
        // loop), drops its springs, then is removed.
        val destroyed = HashSet<EntityId>()
        val destroyEvents = builder.events<CellDestroyIntent>()
        if (destroyEvents.isNotEmpty()) {
            val grid = builder.getComponent<CytoMatterGridComponent>(GRID_SINGLETON)?.grid?.copy()
                ?: CytoMatterGrid.empty()
            for (intent in destroyEvents) {
                if (!destroyed.add(intent.id)) continue
                val cell = builder.getComponent<CytoCellComponent>(intent.id)
                val pos = builder.getComponent<TransformComponent>(intent.id)?.pos
                if (cell != null && pos != null) {
                    val idx = grid.indexOf(CytoUnits.toLogical(pos.x), CytoUnits.toLogical(pos.y))
                    for ((s, c) in cell.cytoplasm) grid.deposit(idx, s, c)
                    for ((s, c) in cell.biomass) grid.deposit(idx, s, c)
                }
                for (n in neighboursOf(builder, intent.id)) removeSpringPair(builder, intent.id, n)
                builder.removeEntity(intent.id)
            }
            builder.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(grid) }
        }

        // Weld: spring-join contacting pairs (once each, skipping the just-destroyed).
        val welded = HashSet<Pair<EntityId, EntityId>>()
        for (intent in builder.events<WeldIntent>()) {
            if (intent.a in destroyed || intent.b in destroyed) continue
            if (!welded.add(intent.a to intent.b)) continue
            if (!springExists(builder, intent.a, intent.b)) addSpring(builder, intent.a, intent.b, cfg)
        }

        // Divide.
        for (intent in builder.events<CellDivisionIntent>()) {
            if (intent.id in destroyed) continue
            divide(builder, cfg, intent.id)
        }
    }

    private fun divide(builder: SimBuilder, cfg: CytoConfig, motherId: EntityId) {
        val cell = builder.getComponent<CytoCellComponent>(motherId) ?: return
        val transform = builder.getComponent<TransformComponent>(motherId) ?: return
        val motionVel = builder.getComponent<MotionComponent>(motherId)?.vel ?: Coord2.zero
        val motherPos = transform.pos
        val neighbours = neighboursOf(builder, motherId)

        // Outward normal = away from the average neighbour direction.
        var sumDelta = Frac2.zero
        for (n in neighbours) {
            val np = builder.getComponent<TransformComponent>(n)?.pos ?: continue
            sumDelta = sumDelta + (np - motherPos)
        }
        val neighbourVector = -(sumDelta / (neighbours.size + 1))
        val neighbourNormal: Norm =
            if (neighbourVector.x.raw == 0L && neighbourVector.y.raw == 0L) Norm.fromAngle(transform.ang)
            else neighbourVector.norm

        val offset = neighbourNormal * CytoUnits.len(0.25f * cell.logicalRadius.toFloat())

        // Group connections by how aligned they are with the split direction.
        val ahead = ArrayList<EntityId>()
        val side = ArrayList<EntityId>()
        for (n in neighbours) {
            val np = builder.getComponent<TransformComponent>(n)?.pos ?: continue
            val toMother = (motherPos - np).norm
            val s = toMother.dot(neighbourNormal).toFloat()
            val group = if (s.absoluteValue < 0.75f) 0f else s.sign
            when (group) {
                -1f -> ahead.add(n)
                0f -> side.add(n)
            }
        }

        // Integer split: daughter takes ⌊C/2⌋ of each species (cytoplasm + biomass), mother keeps ⌈C/2⌉.
        val (motherCyto, daughterCyto) = halve(cell.cytoplasm)
        val (motherBio, daughterBio) = halve(cell.biomass)
        val daughterRadius = radiusForBiomass(daughterBio)

        // Clonal division: the daughter inherits the mother's type AND genome.
        val daughter = builder.spawnCell(
            pos = motherPos + offset,
            vel = motionVel,
            type = cell.type,
            cytoplasm = daughterCyto,
            biomass = daughterBio,
            logicalRadius = daughterRadius,
            genome = cell.genome,
        )

        for (n in ahead) {
            addSpring(builder, daughter, n, cfg)
            removeSpringPair(builder, motherId, n)
        }
        for (n in side) {
            addSpring(builder, daughter, n, cfg)
        }

        // Mother: step back along the split, rotate a quarter turn, keep its half of the matter.
        builder.update<TransformComponent>(motherId) { current ->
            (current ?: transform).copy(pos = motherPos - offset, ang = transform.ang + Frac(1, 2))
        }
        builder.update<CytoCellComponent>(motherId) { current ->
            (current ?: cell).copy(cytoplasm = motherCyto, biomass = motherBio, logicalRadius = radiusForBiomass(motherBio))
        }

        addSpring(builder, motherId, daughter, cfg)
    }

    /** Split a per-species count map: daughter gets ⌊C/2⌋, mother keeps the rest (⌈C/2⌉). */
    private fun halve(m: Map<String, Int>): Pair<Map<String, Int>, Map<String, Int>> {
        val mother = HashMap<String, Int>()
        val daughter = HashMap<String, Int>()
        for ((species, count) in m) {
            val d = count / 2
            if (d > 0) daughter[species] = d
            val keep = count - d
            if (keep > 0) mother[species] = keep
        }
        return mother to daughter
    }

    private fun radiusForBiomass(biomass: Map<String, Int>): Frac =
        Frac(totalBiomassBonds(biomass).toLong(), CytoBiologyCore.BONDS_PER_FULL).sqrt().coerceAtLeast(MIN_RADIUS)
}
