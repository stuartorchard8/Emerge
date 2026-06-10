package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.genomeForType
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.cellMass
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.primitives.Norm
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The SoA structural-change path — the analogue of `CytoLifecycleSystem` + the break-removal
 * half of `CytoConnectionMaintenanceSystem`. Structural edits don't touch the dense columns
 * directly; they edit an **editable adjacency** (per-cell ordered [Edge] lists materialized
 * from the spring CSR), append/tombstone cells in the columns, then rebuild the CSR once.
 *
 * Two entry points, mirroring where the array-of-structs pipeline applies structure:
 *  - [applyBreaks] runs *after connections, before forces* — over-stressed springs vanish this
 *    tick so the solver never sees them (matching the engine reducer).
 *  - [apply] runs at the lifecycle barrier *after forces* — death (tombstone + drop springs),
 *    weld (spring-join contacting pairs), and division (mitosis: append a daughter, rewire the
 *    mother's "ahead" springs to it), then a single [SoaWorld.compact] + CSR rebuild.
 *
 * Bit-identity requirements honoured here: daughters allocate ids in division-intent (ascending
 * mother-id) order via [SoaWorld.createEntity], reproducing the engine's `EcsWorld` sequence;
 * spring-list **order** is preserved exactly (append on add, filter on remove) because biology's
 * per-neighbour float diffusion is order-sensitive; and the division geometry reuses the engine
 * `Frac`/`Coord2`/`Norm` operators verbatim.
 */
class CytoLifecycle(private val cfg: CytoConfig) {

    /** One directed spring end in the editable adjacency (CSR's mutable twin for structure). */
    internal class Edge(val otherId: Int, val restRaw: Long, val stiffRaw: Long, val dampRaw: Long, val damage: Float)

    /**
     * Removes springs whose accumulated damage exceeded the break threshold this tick (flagged
     * per directed CSR end by the connections phase). Symmetric by construction, but both
     * directions are dropped regardless of which side flagged. No column membership change.
     */
    fun applyBreaks(w: CytoWorld, brokenEdge: BooleanArray): Boolean {
        val ends = w.csr.ends
        var any = false
        for (k in 0 until ends) if (brokenEdge[k]) { any = true; break }
        if (!any) return false

        val brokenPairs = HashSet<Long>()
        for (slot in 0 until w.count) {
            val ownerId = w.entityId[slot]
            for (k in w.csr.offset[slot] until w.csr.offset[slot + 1]) {
                if (brokenEdge[k]) brokenPairs.add(pairKey(ownerId, w.csr.otherId[k]))
            }
        }
        val adj = materialize(w)
        for ((ownerId, edges) in adj) edges.removeAll { brokenPairs.contains(pairKey(ownerId, it.otherId)) }
        rebuild(w, adj)
        return true
    }

    /** Applies detach, then death, then weld, then division (the engine lifecycle order). */
    fun apply(
        w: CytoWorld,
        weldLo: List<Int>,
        weldHi: List<Int>,
        divideIds: List<Int>,
        destroyIds: List<Int>,
        detachIds: List<Int> = emptyList(),
    ) {
        if (weldLo.isEmpty() && divideIds.isEmpty() && destroyIds.isEmpty() && detachIds.isEmpty()) return
        val adj = materialize(w)

        // Detach (interaction Detach mode): cut every connection of the named cells. Runs first,
        // matching CytoLifecycleSystem's intent order (detach -> destroy -> weld -> divide).
        for (id in detachIds) {
            val nbrs = adj[id]?.map { it.otherId } ?: emptyList()
            for (n in nbrs) removeSpringPair(adj, id, n)
        }

        // Destroy: drop springs to dead cells (both sides), then tombstone the entity.
        val destroyed = HashSet<Int>()
        for (id in destroyIds) {
            if (!destroyed.add(id)) continue
            val nbrs = adj[id]?.map { it.otherId } ?: emptyList()
            for (n in nbrs) removeSpringPair(adj, id, n)
            adj.remove(id)
            w.world.removeEntity(EntityId(id))
            // drop any multi-species side-table entries so a later id reuse can't read them.
            w.extraChem.remove(id); w.extraPending.remove(id); w.suppression.remove(id)
        }

        // Weld: spring-join contacting pairs (once each, skipping the just-destroyed).
        val welded = HashSet<Long>()
        for (i in weldLo.indices) {
            val a = weldLo[i]; val b = weldHi[i]
            if (a in destroyed || b in destroyed) continue
            if (!welded.add(pairKey(a, b))) continue
            if (adj[a]?.any { it.otherId == b } == true) continue
            addSpring(w, adj, a, b)
        }

        // Divide.
        for (id in divideIds) {
            if (id in destroyed) continue
            divide(w, adj, id)
        }

        // Reclaim tombstoned slots (deaths), then rebuild the CSR over the new ordering.
        if (w.world.needsCompaction()) w.world.compact()
        rebuild(w, adj)
    }

    // ── division (ported from CytoLifecycleSystem.divide) ───────────────────────
    private fun divide(w: CytoWorld, adj: LinkedHashMap<Int, MutableList<Edge>>, motherId: Int) {
        val ms = w.slotOf(motherId)
        if (ms < 0) return
        val motherType = CellType.entries[w.type[ms]]
        val motherGenome = w.genome[ms] ?: emptyList()
        val motherPos = Coord2(Coord(w.posX[ms]), Coord(w.posY[ms]))
        val motherAng = Coord(w.ang[ms])
        val motionVel = Coord2(Coord(w.velX[ms]), Coord(w.velY[ms]))
        val motherLogicalRadius = w.logicalRadius[ms]
        val motherEnergy = w.energy[ms]

        // Snapshot the neighbour order before we start editing the mother's adjacency.
        val neighbours = adj[motherId]?.map { it.otherId } ?: emptyList()

        // Outward normal = away from the average neighbour direction.
        var sumDelta = Frac2.zero
        for (n in neighbours) {
            val ns = w.slotOf(n); if (ns < 0) continue
            val np = Coord2(Coord(w.posX[ns]), Coord(w.posY[ns]))
            sumDelta += (np - motherPos)
        }
        val neighbourVector = -(sumDelta / (neighbours.size + 1))
        val neighbourNormal: Norm =
            if (neighbourVector.x.raw == 0L && neighbourVector.y.raw == 0L) Norm.fromAngle(motherAng)
            else neighbourVector.norm
        val offset = neighbourNormal * CytoUnits.len(0.25f * motherLogicalRadius)

        // Group connections by how aligned they are with the split direction.
        val ahead = ArrayList<Int>()
        val side = ArrayList<Int>()
        for (n in neighbours) {
            val ns = w.slotOf(n); if (ns < 0) continue
            val np = Coord2(Coord(w.posX[ns]), Coord(w.posY[ns]))
            val toMother = (motherPos - np).norm
            val s = toMother.dot(neighbourNormal).toFloat()
            val group = if (abs(s) < 0.75f) 0f else sign(s)
            when (group) {
                -1f -> ahead.add(n)
                0f -> side.add(n)
            }
        }

        val daughterEnergy = motherEnergy / 2f
        val daughterRadiusLogical = sqrt(min(1f, daughterEnergy))
        val radius = max(daughterRadiusLogical, MIN_RADIUS)

        // Spawn the daughter (createEntity reproduces the EcsWorld id sequence). Clonal: inherits
        // the mother's type + genome (was hardcoded Stem), so genomes propagate down a lineage.
        val daughterId = w.world.createEntity()
        appendDaughter(w, daughterId, motherPos + offset, motionVel, radius, daughterEnergy, motherType, motherGenome)

        for (n in ahead) { addSpring(w, adj, daughterId.value, n); removeSpringPair(adj, motherId, n) }
        for (n in side) { addSpring(w, adj, daughterId.value, n) }

        // Mother: step back along the split, rotate a quarter turn, halve energy, reset division charge.
        w.posX[ms] = (motherPos.x - offset.x).raw
        w.posY[ms] = (motherPos.y - offset.y).raw
        w.ang[ms] = (motherAng + Frac(1, 2)).raw
        w.energy[ms] = motherEnergy / 2f
        w.divideCharge[ms] = 0f

        addSpring(w, adj, motherId, daughterId.value)
    }

    /** Appends a daughter cell to every column (mirrors SimBuilder.spawnCell / spawnBody), inheriting
     *  the mother's [type] + [genome] clonally. */
    private fun appendDaughter(
        w: CytoWorld, id: EntityId, pos: Coord2, vel: Coord2, radius: Float, energy: Float,
        type: CellType, genome: List<Gene>,
    ) = appendCell(w, id, pos, vel, type, logicalRadius = radius, energy = energy, sticky = false, genome = genome)

    /**
     * Appends a cell to every column (mirrors SimBuilder.spawnCell / spawnBody), clamping the
     * logical radius to [MIN_RADIUS] exactly as `spawnCell` does. Public so the interact phase
     * can spawn pointer-created cells that participate in this tick's pipeline. Does NOT touch
     * the CSR — the caller rebuilds it once after appending (the new cell starts degree-0).
     */
    fun appendCell(
        w: CytoWorld,
        id: EntityId,
        pos: Coord2,
        vel: Coord2,
        type: CellType,
        logicalRadius: Float,
        energy: Float,
        sticky: Boolean,
        genome: List<Gene> = genomeForType(type),
    ) {
        val radius = max(logicalRadius, MIN_RADIUS)
        w.world.add(id, TransformComponent::class, TransformComponent(pos, Coord(0)))
        w.world.add(id, MotionComponent::class, MotionComponent(vel, Coord(0)))
        w.world.add(id, ImpulseComponent::class, ImpulseComponent())
        w.world.add(id, ColliderComponent::class, ColliderComponent(CytoUnits.len(radius)))
        w.world.add(
            id, MaterialComponent::class,
            MaterialComponent(mass = cellMass(radius), bounce = Frac(0), rough = Frac(0)),
        )
        w.world.add(
            id, CytoCellComponent::class,
            CytoCellComponent(
                type = type,
                chemicals = mapOf(CytoCellColumnStore.ENERGY to energy),
                logicalRadius = radius,
                sticky = sticky,
                genome = genome,
            ),
        )
    }

    // ── adjacency helpers ───────────────────────────────────────────────────────

    internal fun materialize(w: CytoWorld): LinkedHashMap<Int, MutableList<Edge>> {
        val adj = LinkedHashMap<Int, MutableList<Edge>>(w.count)
        for (slot in 0 until w.count) {
            val list = ArrayList<Edge>(w.csr.degreeOf(slot))
            for (k in w.csr.offset[slot] until w.csr.offset[slot + 1]) {
                list.add(Edge(w.csr.otherId[k], w.csr.restRaw[k], w.csr.stiffRaw[k], w.csr.dampRaw[k], w.csr.edgeAux[k]))
            }
            adj[w.entityId[slot]] = list
        }
        return adj
    }

    /** Adds a spring on both endpoints (idempotent per direction); rest = rA + rB, damage 0. */
    private fun addSpring(w: CytoWorld, adj: LinkedHashMap<Int, MutableList<Edge>>, a: Int, b: Int) {
        if (a == b) return
        val sa = w.slotOf(a); val sb = w.slotOf(b)
        if (sa < 0 || sb < 0) return
        val rest = w.radiusRaw[sa] + w.radiusRaw[sb]
        attach(adj, a, b, rest)
        attach(adj, b, a, rest)
    }

    private fun attach(adj: LinkedHashMap<Int, MutableList<Edge>>, owner: Int, other: Int, rest: Long) {
        val list = adj.getOrPut(owner) { ArrayList() }
        if (list.any { it.otherId == other }) return
        list.add(Edge(other, rest, cfg.springStiffness.raw, cfg.springDamping.raw, 0f))
    }

    private fun removeSpringPair(adj: LinkedHashMap<Int, MutableList<Edge>>, a: Int, b: Int) {
        adj[a]?.removeAll { it.otherId == b }
        adj[b]?.removeAll { it.otherId == a }
    }

    /** Rebuilds the spring CSR in place from the editable adjacency over the current ordering. */
    internal fun rebuild(w: CytoWorld, adj: Map<Int, MutableList<Edge>>) {
        w.csr.rebuildFrom(
            count = w.count,
            entityIdAt = { w.entityId[it] },
            slotOf = { w.slotOf(it) },
            springsAt = { slot ->
                adj[w.entityId[slot]]?.map { e ->
                    SpringConstraint(EntityId(e.otherId), Frac(e.restRaw), Frac(e.stiffRaw), Frac(e.dampRaw))
                } ?: emptyList()
            },
            edgeAuxAt = { slot, other -> adj[w.entityId[slot]]?.firstOrNull { it.otherId == other.value }?.damage ?: 0f },
        )
    }

    private fun pairKey(a: Int, b: Int): Long {
        val lo = min(a, b); val hi = max(a, b)
        return (lo.toLong() shl 32) or (hi.toLong() and 0xFFFFFFFFL)
    }
}
