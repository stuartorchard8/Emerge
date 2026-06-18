package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.sim.CytoBiologyCore
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterGrid
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.atomCount
import org.emerge.demo.cyto.sim.cellMass
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.totalBiomassBonds
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.MaterialComponent
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

        // The reservoir takes back matter from both death (whole cell) and division (the rounding
        // remainders) — read once, both deposit, write back once.
        val destroyEvents = builder.events<CellDestroyIntent>()
        val divideEvents = builder.events<CellDivisionIntent>()
        val destroyed = HashSet<EntityId>()
        val grid: CytoMatterGrid? =
            if (destroyEvents.isNotEmpty() || divideEvents.isNotEmpty()) {
                builder.getComponent<CytoMatterGridComponent>(GRID_SINGLETON)?.grid?.copy() ?: CytoMatterGrid.empty()
            } else null

        // Destroy: a dying cell returns all its molecules to its reservoir grid-cell, drops its
        // springs, then is removed.
        for (intent in destroyEvents) {
            if (!destroyed.add(intent.id)) continue
            depositCellMatter(builder, grid!!, intent.id)
            for (n in neighboursOf(builder, intent.id)) removeSpringPair(builder, intent.id, n)
            builder.removeEntity(intent.id)
        }

        // Weld: spring-join contacting pairs (once each, skipping the just-destroyed).
        val welded = HashSet<Pair<EntityId, EntityId>>()
        for (intent in builder.events<WeldIntent>()) {
            if (intent.a in destroyed || intent.b in destroyed) continue
            if (!welded.add(intent.a to intent.b)) continue
            if (!springExists(builder, intent.a, intent.b)) addSpring(builder, intent.a, intent.b, cfg)
        }

        // Repair-weld (gene-driven adhesion): a touching pair welded by a firing Repair gene, born at full
        // damage ("0 health") but already healed by the repair the cell(s) spent on it this tick — so it
        // only survives if ongoing Repair keeps it below the break threshold (MORPHOGENESIS: Repair = sticky).
        for (intent in builder.events<WeldHealIntent>()) {
            if (intent.a in destroyed || intent.b in destroyed) continue
            if (!welded.add(intent.a to intent.b)) continue
            if (!springExists(builder, intent.a, intent.b)) {
                addSpring(builder, intent.a, intent.b, cfg, initialDamage = (cfg.connectionBreakDamage - intent.heal).coerceAtLeast(0f))
            }
        }

        // Divide.
        for (intent in divideEvents) {
            if (intent.id in destroyed) continue
            divide(builder, cfg, intent.id, intent.morphogen, intent.morphogenToMother, intent.axisMorphogen, intent.divideAcross, grid!!, destroyed)
        }

        if (grid != null) builder.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(grid) }
    }

    /** Deposit a cell's entire cytoplasm + biomass into its reservoir grid-cell (death recycling). */
    private fun depositCellMatter(builder: SimBuilder, grid: CytoMatterGrid, id: EntityId) {
        val cell = builder.getComponent<CytoCellComponent>(id) ?: return
        val pos = builder.getComponent<TransformComponent>(id)?.pos ?: return
        val idx = grid.indexOf(CytoUnits.toLogical(pos.x), CytoUnits.toLogical(pos.y))
        for ((s, c) in cell.cytoplasm) grid.deposit(idx, s, c)
        for ((s, c) in cell.biomass) grid.deposit(idx, s, c)
    }

    private fun divide(builder: SimBuilder, cfg: CytoConfig, motherId: EntityId, morphogen: String, morphogenToMother: Boolean, axisMorphogen: String, divideAcross: Boolean, grid: CytoMatterGrid, destroyed: HashSet<EntityId>) {
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

        // Oriented division (MORPHOGENESIS.md §Morphogens for shape): if the Mitosis gene named an
        // axis-morphogen, place the daughter relative to that morphogen's LOCAL GRADIENT — *along* it
        // (project → extend) or *across* it (slice → widen into a sheet) — instead of toward free space.
        // The gradient axis is the line from the highest- to the lowest-concentration cell among the mother +
        // welded neighbours (a single position difference ⇒ Frac-range-safe + deterministic, no float). Empty
        // axis-morphogen, or no gradient, ⇒ the free-space neighbourNormal (so unoriented Mitosis is identical).
        val splitNormal: Norm = run {
            if (axisMorphogen.isEmpty()) return@run neighbourNormal
            fun conc(c: CytoCellComponent): Int {
                val b = totalBiomassBonds(c.biomass); return if (b <= 0) 0 else (c.cytoplasm[axisMorphogen] ?: 0) * CytoTuning.CONC_SCALE / b
            }
            var maxC = conc(cell); var minC = maxC; var maxPos = motherPos; var minPos = motherPos
            for (n in neighbours) {
                val nc = builder.getComponent<CytoCellComponent>(n) ?: continue
                val np = builder.getComponent<TransformComponent>(n)?.pos ?: continue
                val c = conc(nc)
                if (c > maxC) { maxC = c; maxPos = np }
                if (c < minC) { minC = c; minPos = np }
            }
            if (maxC == minC) return@run neighbourNormal               // flat → no gradient
            val axisVec = minPos - maxPos                              // down-gradient direction
            if (axisVec.x.raw == 0L && axisVec.y.raw == 0L) return@run neighbourNormal
            val along = axisVec.norm
            if (divideAcross) along.cw90 else along
        }

        // Group connections by how aligned they are with the split direction.
        val ahead = ArrayList<EntityId>()
        val side = ArrayList<EntityId>()
        for (n in neighbours) {
            val np = builder.getComponent<TransformComponent>(n)?.pos ?: continue
            val toMother = (motherPos - np).norm
            val s = toMother.dot(splitNormal).toFloat()
            val group = if (s.absoluteValue < 0.75f) 0f else s.sign
            when (group) {
                -1f -> ahead.add(n)
                0f -> side.add(n)
            }
        }

        // General rounding rule: split each species ⌊C/2⌋ to EACH side and emit the odd remainder
        // (C mod 2) to the environment — whole amounts preserved, remainders to the reservoir, never
        // minted. (Daughter and mother get the same floor share.)
        val gridIdx = grid.indexOf(CytoUnits.toLogical(motherPos.x), CytoUnits.toLogical(motherPos.y))
        // Asymmetric mitosis (MORPHOGENESIS.md §C): the named morphogen is withheld from the even split
        // (skipped in floorSplit) and handed **whole to the daughter** below; the mother keeps none. Empty
        // morphogen ⇒ skip matches nothing ⇒ the split is byte-identical to the old symmetric path.
        val morphogenCount = if (morphogen.isNotEmpty()) (cell.cytoplasm[morphogen] ?: 0) else 0
        val half = floorSplit(cell.cytoplasm, grid, gridIdx, skip = morphogen)
        val halfBio = floorSplit(cell.biomass, grid, gridIdx)

        // If neither daughter can take a whole molecule (every species was count ≤ 1), the cell can't
        // split — it dies, its matter already emitted to the reservoir as the remainders above (plus the
        // withheld morphogen, deposited here so it isn't lost — conservation).
        if (atomCount(half) + atomCount(halfBio) == 0) {
            if (morphogenCount > 0) grid.deposit(gridIdx, morphogen, morphogenCount)
            for (n in neighbours) removeSpringPair(builder, motherId, n)
            builder.removeEntity(motherId)
            destroyed.add(motherId)
            return
        }
        val daughterRadius = radiusForBiomass(halfBio)

        // Place mother and daughter exactly their spring rest length apart — separation = 2·offset =
        // 2·daughterRadius = rA+rB — so the new connection starts RELAXED, with no velocity kick. (The
        // old offset of 0.25·motherRadius put the pair at ~35% of rest, so the spring shoved them apart
        // every division; that churn was what the asymmetric drag rectified into chaotic locomotion.)
        val offset = splitNormal * CytoUnits.len(daughterRadius.toFloat())

        // Clonal division: the daughter inherits the mother's type AND genome (separate map copies). The
        // morphogen (asymmetric mitosis) rides entirely with the daughter — its atoms make the daughter
        // heavier than the mother by that amount (spawnCell derives mass from cytoplasm), still conserved.
        // Asymmetric morphogen goes to the daughter by default, or stays with the mother if morphogenToMother
        // (a centred source). Either way it's allocated whole to exactly one side (conserved).
        val daughterCyto = HashMap(half).apply { if (morphogenCount > 0 && !morphogenToMother) put(morphogen, morphogenCount) }
        val daughter = builder.spawnCell(
            pos = motherPos + offset,
            vel = motionVel,
            type = cell.type,
            cytoplasm = daughterCyto,
            biomass = HashMap(halfBio),
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

        // Mother: step back along the split, rotate a quarter turn, keep its (equal) half of the matter.
        builder.update<TransformComponent>(motherId) { current ->
            (current ?: transform).copy(pos = motherPos - offset, ang = transform.ang + Frac(1, 2))
        }
        val motherCyto = HashMap(half).apply { if (morphogenCount > 0 && morphogenToMother) put(morphogen, morphogenCount) }
        builder.update<CytoCellComponent>(motherId) { current ->
            (current ?: cell).copy(cytoplasm = motherCyto, biomass = HashMap(halfBio), logicalRadius = daughterRadius)
        }
        // Mass = atoms: the two sides' atoms (incl. whichever holds the asymmetric morphogen) + the emitted
        // remainders = the original; both keep the mother's velocity ⇒ momentum conserved. Derive the mother's
        // mass from its ACTUAL cytoplasm (motherCyto), so it's correct whether or not it retained the morphogen
        // — identical to cellMass(half, …) in the default daughter-retention case.
        builder.update<MaterialComponent>(motherId) { current ->
            (current ?: error("mother has no material")).copy(mass = cellMass(motherCyto, halfBio))
        }

        addSpring(builder, motherId, daughter, cfg)
    }

    /** Each side gets ⌊count/2⌋ of a species; the odd remainder (count mod 2) is deposited to the
     *  reservoir cell [gridIdx]. Returns the per-side floor map (daughter and mother share it). [skip] (a
     *  non-empty species) is left out of the even split entirely — the caller allocates it asymmetrically
     *  (the morphogen, handed whole to one daughter). */
    private fun floorSplit(m: Map<String, Int>, grid: CytoMatterGrid, gridIdx: Int, skip: String = ""): Map<String, Int> {
        val half = HashMap<String, Int>()
        for ((species, count) in m) {
            if (species == skip) continue
            val h = count / 2
            if (h > 0) half[species] = h
            val remainder = count - 2 * h
            if (remainder > 0) grid.deposit(gridIdx, species, remainder)
        }
        return half
    }

    private fun radiusForBiomass(biomass: Map<String, Int>): Frac =
        Frac(totalBiomassBonds(biomass).toLong(), CytoBiologyCore.BONDS_PER_FULL).sqrt().coerceAtLeast(MIN_RADIUS)
}
