package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.BioProfile
import org.emerge.demo.cyto.sim.systems.LyseAttackIntent
import org.emerge.demo.cyto.sim.CellWork
import org.emerge.demo.cyto.sim.CytoBiologyCore
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoExposure
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoMutation
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.MoleculeStore
import org.emerge.demo.cyto.sim.cellMass
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.ecs.SpatialGrid
import org.emerge.sim.core.ecs.soa.ColumnPartition
import org.emerge.sim.core.ecs.soa.SoaSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.sim.SimBuilder
import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource

// ──────────────────────────────────────────────────────────────────────────────
// CytoPipelineState
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Shared mutable scratch that the SoA pipeline phases mutate across a tick.
 * All fields are pre-grown (pooled) and cleared/reset each tick so that the
 * hot phases allocate zero GC pressure.
 */
class CytoPipelineState {

    // ── Contact phase outputs (weld intents for the lifecycle bridge) ──
    val weldLo = ArrayList<Int>()
    val weldHi = ArrayList<Int>()

    // Per-cell count of un-connected cells touched this tick (the Touching gate)
    var touchScratch = IntArray(0)

    // Per-cell list of un-welded cells touched this tick (their entity-id values), filled alongside touchScratch
    var touchingScratch = Array(0) { ArrayList<Int>() }

    // ── Repair-weld intents (canonical pairKey → summed birth-heal), collected from biology, drained by lifecycle ──
    val weldHealByPair = HashMap<Long, Float>()
    val weldHealCount = HashMap<Long, Int>()

    // ── Dividing cell morphogen tracking ──
    val divideMorphogen = HashMap<EntityId, String>()
    val divideMorphogenToMother = HashSet<EntityId>()
    val divideAxis = HashMap<EntityId, String>()
    val divideAcross = HashSet<EntityId>()
    val divideRejectMother = HashSet<EntityId>()

    // ── Biology phase outputs: divide/destroy intents ──
    val divide = ArrayList<EntityId>()
    val destroy = ArrayList<EntityId>()

    // ── Connection maintenance scratch ──
    val connPairDmg = HashMap<Long, Float>()

    // ── Lysis attack intents, accumulated from biology, drained by the reducer after lifecycle ──
    val lyseIntents = ArrayList<LyseAttackIntent>()

    // ── Broadphase grid, reused across ticks ──
    var contactGrid: SpatialGrid? = null

    // ── Reused per-cell scratch for exposure (neighbour diamond-angles) ──
    val expoScratch = LongArray(CytoExposure.MAX_NEIGHBOURS)

    // ── Biology working set, pooled by slot + reused across ticks ──
    var bioCap = 0
    var bioWorks = arrayOfNulls<CellWork>(0)
    var bioNbrs = arrayOfNulls<ArrayList<EntityId>>(0)
    var bioBaseQuanta = LongArray(0)
    var bioCapture = LongArray(0)
    val bioWorksMap = LinkedHashMap<EntityId, CellWork>()
    val bioNeighbourIds = HashMap<EntityId, List<EntityId>>()
    val bioCapSum = HashMap<Int, Long>()
    val bioOrderedWorks = ArrayList<CellWork>()
    // Ascending-EntityId slot order as a reusable IntArray (sorted via packed LongArray)
    var bioOrder = IntArray(0)
    var bioOrderPacked = LongArray(0)
    // Grid-cell grouping for the parallel gene phase
    var bioBucketCount = IntArray(0)
    var bioBucketCursor = IntArray(0)
    var bioGroupSlots = IntArray(0)
    var bioGroupBounds = IntArray(0)

    // ── Spring-solve working set, reused across ticks ──
    var ssCap = 0
    var ssP0x = LongArray(0); var ssP0y = LongArray(0)
    var ssPx = LongArray(0); var ssPy = LongArray(0)
    var ssBvx = LongArray(0); var ssBvy = LongArray(0)
    var ssVx = LongArray(0); var ssVy = LongArray(0)
    var ssMass = LongArray(0)
    var ssDx = LongArray(0); var ssDy = LongArray(0)
    // Per-EDGE precompute (indexed by CSR edge slot)
    var ssEdgeCap = 0
    var ssEnX = LongArray(0); var ssEnY = LongArray(0); var ssEw = LongArray(0)

    fun ensureSpringScratch(n: Int) {
        if (ssCap >= n) return
        ssP0x = LongArray(n); ssP0y = LongArray(n)
        ssPx = LongArray(n); ssPy = LongArray(n)
        ssBvx = LongArray(n); ssBvy = LongArray(n)
        ssVx = LongArray(n); ssVy = LongArray(n)
        ssMass = LongArray(n)
        ssDx = LongArray(n); ssDy = LongArray(n)
        ssCap = n
    }

    fun ensureEdgeScratch(e: Int) {
        if (ssEdgeCap >= e) return
        ssEnX = LongArray(e); ssEnY = LongArray(e); ssEw = LongArray(e)
        ssEdgeCap = e
    }

    fun ensureBioScratch(n: Int) {
        if (bioCap >= n) return
        val nb = arrayOfNulls<CellWork>(n)
        val nn = arrayOfNulls<ArrayList<EntityId>>(n)
        for (i in 0 until bioCap) { nb[i] = bioWorks[i]; nn[i] = bioNbrs[i] }
        for (i in bioCap until n) {
            nb[i] = CellWork(MoleculeStore(CytoTuning.CELL_CHEM_CAP), MoleculeStore(CytoTuning.CELL_CHEM_CAP), CytoTuning.MIN_RADIUS, CellType.Blank, emptyList(), 0, 0, 0, -1, HashMap())
            nn[i] = ArrayList()
        }
        bioWorks = nb; bioNbrs = nn
        bioBaseQuanta = LongArray(n); bioCapture = LongArray(n)
        bioCap = n
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// SoA System implementations for each phase
// ──────────────────────────────────────────────────────────────────────────────

/** Reset phase — zero the dense impulse accumulator. */
class ResetSystem : SoaSystem<CytoConfig, CytoWorld> {
    override fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        val n = world.count
        for (i in 0 until n) {
            world.impPosX[i] = 0L; world.impPosY[i] = 0L
            world.impVelX[i] = 0L; world.impVelY[i] = 0L; world.impAngVel[i] = 0L
        }
    }
}

/** Contacts phase — broadphase contact detection + resolve. Produces weldLo/weldHi. */
class ContactsSystem(
    private val executor: ParallelExecutor?,
    private val state: CytoPipelineState,
) : SoaSystem<CytoConfig, CytoWorld> {
    override fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        state.weldLo.clear(); state.weldHi.clear()
        val n = world.count
        if (state.touchScratch.size < n) state.touchScratch = IntArray(n) else state.touchScratch.fill(0, 0, n)
        if (state.touchingScratch.size < n) state.touchingScratch = Array(n) { ArrayList() } else for (i in 0 until n) state.touchingScratch[i].clear()
        if (n < 2) return

        // Compute grid dimensions
        var maxRadius = 0L
        for (i in 0 until n) if (world.radiusRaw[i] > maxRadius) maxRadius = world.radiusRaw[i]
        if (maxRadius <= 0L) return

        val dims = SpatialGrid.packedDimsFor(
            minCellSize = maxRadius * 2L,
            maxCellsPerAxisLog2 = SpatialGrid.cellsPerAxisLog2For(n),
        )
        if (dims < 0L) return

        val cached = state.contactGrid
        val grid = if (cached != null && cached.packedDims == dims) {
            cached.clearForReuse(); cached
        } else {
            SpatialGrid.ofPackedDims(dims).also { state.contactGrid = it }
        }

        for (i in 0 until n) grid.insert(i, world.posX[i], world.posY[i])

        // Sequential single pass in (i asc, j asc) order
        var scratch = IntArray(16)
        for (i in 0 until n) {
            val aX = world.posX[i]; val aY = world.posY[i]; val aR = world.radiusRaw[i]
            var cc = 0
            grid.forEachNeighbour(aX, aY) { j ->
                if (j > i) {
                    val sum = aR + world.radiusRaw[j]
                    val dx = longAbs((aX - world.posX[j]).toLong())
                    val dy = longAbs((aY - world.posY[j]).toLong())
                    if (dx < sum && dy < sum) {
                        if (cc >= scratch.size) scratch = scratch.copyOf(scratch.size * 2)
                        scratch[cc] = j; cc += 1
                    }
                }
            }
            insertionSort(scratch, cc)
            for (k in 0 until cc) {
                val j = scratch[k]
                // Spring-connected pairs produce no contact effect — skip them before Contact.compute
                if (edgeExists(world, i, world.entityId[j]) || edgeExists(world, j, world.entityId[i])) continue
                val contact = Contact.compute(
                    aId = EntityId(world.entityId[i]), bId = EntityId(world.entityId[j]),
                    aTransform = transformAt(world, i), bTransform = transformAt(world, j),
                    aRadius = Frac(aR), bRadius = Frac(world.radiusRaw[j]),
                ) ?: continue
                handleContact(world, i, j, contact, cfg, state)
            }
        }
    }
}

/** Biology phase — gene execution + writeback. Results stored in state.divide and state.destroy. */
class BiologySystem(
    private val executor: ParallelExecutor?,
    private val springParallelThreshold: Int,
    private val bioParallelThreshold: Int,
    private val profiler: PipelineProfiler?,
    private val noMutateEntityIdProvider: () -> Int,
    private val state: CytoPipelineState,
    private val bioProfile: org.emerge.demo.cyto.sim.BioProfile? = null,
) {

    // Per-tick profiling marker
    private var bioMark = TimeSource.Monotonic.markNow()

    fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        val n = world.count
        if (n == 0) {
            state.divide.clear(); state.destroy.clear()
            return
        }
        if (profiler != null) bioMark = TimeSource.Monotonic.markNow()

        val lightField = CytoLightField.default()
        val grid = world.grid

        state.ensureBioScratch(n)
        if (state.bioOrder.size < n) { state.bioOrder = IntArray(n); state.bioOrderPacked = LongArray(n) }
        val ordered = state.bioOrder
        for (i in 0 until n) state.bioOrderPacked[i] = (world.entityId[i].toLong() shl 32) or i.toLong()
        state.bioOrderPacked.sort(0, n)
        for (k in 0 until n) ordered[k] = (state.bioOrderPacked[k] and 0xFFFFFFFFL).toInt()

        val works = state.bioWorksMap.also { it.clear() }
        val neighbourIds = state.bioNeighbourIds.also { it.clear() }

        val baseQuantaRaw = state.bioBaseQuanta
        val captureMilli = state.bioCapture
        val capSumByGrid = state.bioCapSum.also { it.clear() }

        // Staggered exchange batch sizes (cells per batch).
        val batchSizes = IntArray(CytoTuning.EXCHANGE_BATCHES)

        // Parallel per-cell build. Every write here is slot-local (bioNbrs[slot], baseQuantaRaw[k],
        // captureMilli[k], the slot's own back-buffer store, and the cell's own CellWork), so the range
        // partitions cleanly with no cross-slot writes. The `neighbourIds`/`works` maps (needed only by
        // diffuse) and the order-dependent staggered-batch assignment are deferred to the serial tail below.
        // Hoist the back-buffer capacity grow out of the parallel region so no worker reallocs the array.
        world.cell.ensureCapacityBack(n)
        val bioExec = if (n >= bioParallelThreshold) executor else null
        ColumnPartition.disjoint(n, bioExec, threshold = 1) { kStart, kEnd ->
            for (k in kStart until kEnd) {
                val slot = ordered[k]
                val deg = world.csr.degreeOf(slot)
                val base = world.csr.offset[slot]
                val nbrs = state.bioNbrs[slot]!!.also { it.clear() }
                for (j in 0 until deg) nbrs.add(EntityId(world.csr.otherId[base + j]))

                val work = state.bioWorks[slot]!!
                val expo = work.expoScratch
                var ek = 0
                for (j in 0 until deg) {
                    if (ek >= CytoExposure.MAX_NEIGHBOURS) break
                    val ns = world.csr.otherSlot[base + j]; if (ns < 0) continue
                    val d = delta(world, slot, ns)
                    expo[ek++] = CytoExposure.diamondAngle(d.x, d.y).raw
                }
                val lx = CytoUnits.toLogical(Coord(world.posX[slot])); val ly = CytoUnits.toLogical(Coord(world.posY[slot]))
                val sample = lightField.sampleAt(lx, ly, world.world.tick)
                val exposure = CytoExposure.weight(expo, ek)
                val radius = Frac(world.cell.logicalRadius[slot])

                baseQuantaRaw[k] = if (CytoTuning.LIGHT_IGNORES_EXPOSURE) (sample * CytoTuning.LIGHT_QUANTA_SCALE).raw
                    else ((sample * exposure) * CytoTuning.LIGHT_QUANTA_SCALE).raw

                val exposureMilli = exposure.raw * 1000L / Int.MAX_VALUE.toLong()
                captureMilli[k] = exposureMilli * radius.raw / Int.MAX_VALUE.toLong()

                // Column-slab double-buffer: seed this slot's BACK stores from the committed front, then hand
                // them to work by reference. Genes mutate the back buffer; swapBuffers() commits at the barrier.
                // The front stores stay untouched during the biology pass (readable for divide/lyse intents).
                val backCyto = world.cell.backCytoplasm[slot]
                    ?: MoleculeStore(CytoTuning.CELL_CHEM_CAP).also { world.cell.backCytoplasm[slot] = it }
                world.cell.cytoplasm[slot]?.let { backCyto.copyFrom(it) }
                val backBio = world.cell.backBiomass[slot]
                    ?: MoleculeStore(CytoTuning.CELL_CHEM_CAP).also { world.cell.backBiomass[slot] = it }
                world.cell.biomass[slot]?.let { backBio.copyFrom(it) }
                work.reset(
                    cytoplasm = backCyto,
                    biomass = backBio,
                    logicalRadius = radius,
                    type = CellType.entries[world.cell.type[slot]],
                    genome = world.cell.genome[slot] ?: emptyList(),
                    quanta = 0,
                    touchCount = state.touchScratch[slot],
                    wear = world.cell.wear[slot],
                    gridIndex = -1,
                    weldedDegree = deg,
                    seed = slot,
                )
                for (j in 0 until deg) work.connectionDamage[EntityId(world.csr.otherId[base + j])] = world.csr.edgeAux[base + j]
                for (tid in state.touchingScratch[slot]) {
                    work.touchingIds.add(EntityId(tid))
                    val ts = world.slotOf(tid)
                    if (ts >= 0) {
                        val tlx = CytoUnits.toLogical(Coord(world.posX[ts]))
                        val tly = CytoUnits.toLogical(Coord(world.posY[ts]))
                        if (work._touchingCellN >= work._touchingCellCx.size) {
                            val newSize = work._touchingCellCx.size * 2
                            work._touchingCellCx = work._touchingCellCx.copyOf(newSize)
                            work._touchingCellCy = work._touchingCellCy.copyOf(newSize)
                        }
                        work._touchingCellCx[work._touchingCellN] = tlx
                        work._touchingCellCy[work._touchingCellN] = tly
                        work._touchingCellN++
                    }
                }
                work.exposureMilli = exposureMilli.toInt()
                work.cx = lx; work.cy = ly
            }
        }

        // Serial tail: populate the diffuse lookup maps (cross-cell, so kept off the parallel region) and
        // assign staggered exchange batches greedily in canonical slot order (order-dependent → serial).
        for (k in 0 until n) {
            val slot = ordered[k]
            val work = state.bioWorks[slot]!!
            neighbourIds[EntityId(world.entityId[slot])] = state.bioNbrs[slot]!!
            works[EntityId(world.entityId[slot])] = work
            if (work.exchangeBatch < 0) {
                var bestBatch = 0
                for (b in 1 until CytoTuning.EXCHANGE_BATCHES) {
                    if (batchSizes[b] < batchSizes[bestBatch]) bestBatch = b
                }
                work.exchangeBatch = bestBatch
                batchSizes[bestBatch]++
            }
        }
        bioSplit("bio:build")

        // Compute internalTouching — kept sequential: sub-millisecond even at 8k cells, so fork/join
        // dispatch overhead outweighs any parallel win (measured net-negative). Reads own neighbours
        // (bioNbrs[slot]) + read-only CSR/sparse-set, writes only its own work.internalTouching.
        for (k in 0 until n) {
            val work = state.bioWorks[ordered[k]]!!
            val nbrIds = state.bioNbrs[ordered[k]]
            if (nbrIds != null && work.touchingIds.isNotEmpty()) {
                for (touchId in work.touchingIds) {
                    val touchIdVal = touchId.value
                    val touchSlot = world.slotOf(touchIdVal)
                    for (nbrId in nbrIds) {
                        val nbrSlot = world.slotOf(nbrId.value)
                        if (nbrSlot < 0) continue
                        var nbrKnowsTouch = false
                        for (e in world.csr.offset[nbrSlot] until world.csr.offset[nbrSlot + 1]) {
                            if (world.csr.otherId[e] == touchIdVal) { nbrKnowsTouch = true; break }
                        }
                        if (!nbrKnowsTouch) continue
                        var touchKnowsNbr = false
                        val touchRealSlot = if (touchSlot >= 0) touchSlot else world.slotOf(nbrId.value)
                        if (touchRealSlot >= 0) {
                            for (e in world.csr.offset[touchRealSlot] until world.csr.offset[touchRealSlot + 1]) {
                                if (world.csr.otherId[e] == nbrId.value) { touchKnowsNbr = true; break }
                            }
                        }
                        if (nbrKnowsTouch && touchKnowsNbr) {
                            work.internalTouching.add(touchId)
                            break
                        }
                    }
                }
            }
        }
        bioSplit("bio:internalTouching")

        // Second pass: turn base light into quanta — kept sequential (sub-millisecond; dispatch overhead
        // would exceed the work).
        for (k in 0 until n) {
            val slot = ordered[k]
            val work = state.bioWorks[slot]!!
            work.quanta = if (!CytoTuning.LIGHT_SHADING) {
                (baseQuantaRaw[k] / Int.MAX_VALUE.toLong()).toInt()
            } else {
                val capSum = if (work.gridIndex >= 0) capSumByGrid[work.gridIndex] ?: captureMilli[k] else captureMilli[k]
                if (capSum <= 0L) 0 else (baseQuantaRaw[k] * captureMilli[k] / capSum / Int.MAX_VALUE.toLong()).toInt()
            }
        }
        bioSplit("bio:quanta")

        // Gene phase — parallel
        val tick = world.world.tick.toInt()
        ColumnPartition.disjoint(n, bioExec, threshold = 1) { kStart, kEnd ->
            for (k in kStart until kEnd) CytoBiologyCore.runGenes(state.bioWorks[ordered[k]]!!, tick, bioProfile)
        }
        bioSplit("bio:genes")

        // Passive env-exchange junction
        val orderedWorks = state.bioOrderedWorks.also { it.clear() }
        for (k in 0 until n) orderedWorks.add(state.bioWorks[ordered[k]]!!)
        CytoBiologyCore.passiveEnvExchange(orderedWorks, grid, world.world.tick.toInt(), bioProfile)
        bioSplit("bio:exchange")

        // Cytoplasm diffusion between connected cells — runs every N ticks to reduce cost
        if (world.world.tick % CytoTuning.CYTOPLASM_DIFFUSE_PERIOD == 0L) {
            CytoBiologyCore.diffuse(works, neighbourIds)
        }
        bioSplit("bio:diffuse")

        state.divide.clear(); state.destroy.clear()
        state.divideMorphogen.clear()
        state.divideMorphogenToMother.clear()
        state.divideAxis.clear()
        state.divideAcross.clear()
        state.divideRejectMother.clear()
        state.weldHealByPair.clear()
        state.weldHealCount.clear()

        for (k in 0 until n) {
            val slot = ordered[k]
            val id = EntityId(world.entityId[slot])
            val work = state.bioWorks[slot]!!
            CytoBiologyCore.finish(id, work, grid, state.divide, state.destroy)
            for ((other, heal) in work.weldHeals) {
                val key = pairKey(id.value, other.value)
                state.weldHealByPair[key] = (state.weldHealByPair[key] ?: 0f) + heal
                state.weldHealCount[key] = (state.weldHealCount[key] ?: 0) + 1
            }
            if (work.dividing) {
                if (work.divideMorphogen.isNotEmpty()) {
                    state.divideMorphogen[id] = work.divideMorphogen
                    if (work.divideMorphogenToMother) state.divideMorphogenToMother.add(id)
                }
                if (work.divideAxisMorphogen.isNotEmpty()) {
                    state.divideAxis[id] = work.divideAxisMorphogen
                    if (work.divideAcross) state.divideAcross.add(id)
                }
                if (work.divideRejectMother) state.divideRejectMother.add(id)
            }
        }
        bioSplit("bio:finish")

        // Accumulate lysis attack intents from biology
        state.lyseIntents.clear()
        for (k in 0 until n) {
            val slot = ordered[k]
            val work = state.bioWorks[slot]!!
            if (work.lyseTargets.isEmpty()) continue
            val attackerId = EntityId(world.entityId[slot])
            val victimIds = work.lyseTargets.keys.toList()
            // Damage = total biomass torn off across all victims.
            val totalDamage = work.lyseTargets.values.sum()
            if (totalDamage <= 0) continue
            // Lyse steals all species — no species operand.
            val lyseGene = work.genome.firstOrNull { it.action.type == ActionType.Lyse }
            val g = lyseGene?.efficiency?.coerceIn(0, CytoTuning.EFFICIENCY_MAX_GEAR) ?: 0
            state.lyseIntents.add(LyseAttackIntent(
                attacker = attackerId,
                victims = victimIds,
                damage = totalDamage,
                gear = g,
            ))
        }

        // Write-back: mutate durable scalar columns, commit chem via slab swap, handle genome mutations.
        val noMutateEntityId = noMutateEntityIdProvider()
        for (k in 0 until n) {
            val slot = ordered[k]
            val id = EntityId(world.entityId[slot])
            val work = state.bioWorks[slot]!!
            val mutated = if (world.entityId[slot] == noMutateEntityId) null
                else CytoMutation.mutate(world.cell.genome[slot] ?: emptyList(), if (world.mutationRateDenom >= 0) world.mutationRateDenom else cfg.mutationRateDenom) { until -> nextRandomInt(world, until) }

            val oldRadiusRaw = world.cell.logicalRadius[slot]
            // Commit scalar state directly into the persistent column (front) store.
            world.cell.logicalRadius[slot] = work.logicalRadius.raw
            world.cell.wear[slot] = work.wear
            world.cell.stickyTemp[slot] = false
            if (mutated != null) world.cell.genome[slot] = mutated
            if (work.repaired) {
                for (k in world.csr.offset[slot] until world.csr.offset[slot + 1]) {
                    world.csr.edgeAux[k] = work.connectionDamage[EntityId(world.csr.otherId[k])] ?: 0f
                }
            }
            if (work.logicalRadius.raw != oldRadiusRaw) {
                world.radiusRaw[slot] = CytoUnits.len(work.logicalRadius.coerceAtMost(CytoTuning.MAX_COLLISION_RADIUS).toFloat()).raw
            }
            val newMass = cellMass(work.cytoplasm, work.biomass)
            val oldMass = world.mass[slot].toUInt()
            if (newMass != oldMass) {
                world.mass[slot] = newMass.toInt()
                if (cfg.variableMass && (world.velX[slot] != 0 || world.velY[slot] != 0)) {
                    world.velX[slot] = (world.velX[slot].toLong() * oldMass.toLong() / newMass.toLong()).toInt()
                    world.velY[slot] = (world.velY[slot].toLong() * oldMass.toLong() / newMass.toLong()).toInt()
                }
            }
        }
        // Slab swap: commits all chemistry mutations in O(count) pointer swap instead of per-cell copyFrom.
        world.cell.swapBuffers(n)
        bioSplit("bio:writeback")
    }

    private fun bioSplit(name: String) {
        val p = profiler ?: return
        p.recordPhase(name, bioMark.elapsedNow().inWholeNanoseconds)
        bioMark = TimeSource.Monotonic.markNow()
    }

    private fun nextRandomInt(w: CytoWorld): Int {
        w.world.randomSeed = w.world.randomSeed * 2862933555777941757L + 3037000493L
        return (w.world.randomSeed ushr 32).toInt()
    }
    private fun nextRandomInt(w: CytoWorld, until: Int): Int {
        require(until > 0)
        return (nextRandomInt(w).toLong() and 0x7FFFFFFFL).toInt() % until
    }
}

/** Connections phase — spring stress + damage + break. */
class ConnectionsSystem(private val state: CytoPipelineState) : SoaSystem<CytoConfig, CytoWorld> {
    override fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        val pairDmg = state.connPairDmg.also { it.clear() }
        for (i in 0 until world.count) {
            for (k in world.csr.offset[i] until world.csr.offset[i + 1]) {
                if (world.csr.otherSlot[k] < 0) continue
                val key = pairKey(world.entityId[i], world.csr.otherId[k])
                val cur = world.csr.edgeAux[k]
                val prev = pairDmg[key]
                if (prev == null || cur < prev) pairDmg[key] = cur
            }
        }

        val collinearPeriod = cfg.weldCollinearCheckPeriod.coerceAtLeast(1)
        val scanCollinear = collinearPeriod == 1 || world.world.tick % collinearPeriod == 0L
        val broken = HashSet<Long>()
        for (i in 0 until world.count) {
            val radiusA = world.radiusRaw[i]
            for (k in world.csr.offset[i] until world.csr.offset[i + 1]) {
                val nSlot = world.csr.otherSlot[k]
                if (nSlot < 0) continue
                val rest = radiusA + world.radiusRaw[nSlot]
                val restLogical = CytoUnits.toLogical(Frac(rest))
                val dist = deltaLen(world, i, nSlot)
                val stretch = CytoUnits.toLogical(dist) - restLogical
                val deg = maxOf(world.csr.degreeOf(i), world.csr.degreeOf(nSlot))
                val tension = max(0f, stretch * cfg.connectionStressScale) / (1 shl deg.coerceAtMost(20))

                val breakDist = cfg.overStretchBreakMultiple * restLogical
                val overStretch = if (stretch > 0f && breakDist > 0f) {
                    val ratio = stretch / breakDist
                    var p = 1f
                    repeat(cfg.overStretchDamageExponent) { p *= ratio }
                    cfg.connectionBreakDamage * p
                } else 0f

                val compression = max(0f, -stretch - cfg.compressionTolerance) * cfg.connectionStressScale

                val collinear = if (scanCollinear && world.csr.degreeOf(i) >= 2 && world.csr.degreeOf(nSlot) >= 2 &&
                    throughCellChord(world, i, nSlot, cfg)) cfg.weldCollinearDamage * collinearPeriod else 0f

                val stress = tension + overStretch + compression + collinear
                val key = pairKey(world.entityId[i], world.csr.otherId[k])
                val damage = max(0f, (pairDmg[key] ?: world.csr.edgeAux[k]) + stress)
                if (damage > cfg.connectionBreakDamage) {
                    broken.add(key)
                } else {
                    world.csr.restRaw[k] = rest
                    world.csr.stiffRaw[k] = cfg.springStiffness.raw
                    world.csr.dampRaw[k] = cfg.springDamping.raw
                    world.csr.edgeAux[k] = damage
                }
            }
        }
        if (broken.isEmpty()) return
        pruneEdges(world, broken)
    }
}

/** Grab force phase. */
class GrabSystem : SoaSystem<CytoConfig, CytoWorld> {
    override fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        val g = (inputs.values.firstOrNull() as? CytoInput)?.grab ?: return
        val slot = world.slotOf(g.entity.value); if (slot < 0) return
        val pos = Coord2(Coord(world.posX[slot]), Coord(world.posY[slot]))
        val vel = Coord2(Coord(world.velX[slot]), Coord(world.velY[slot])).asFrac2()
        val target = CytoUnits.coord2(g.x, g.y)
        val toTarget = target - pos
        val maxReach = CytoUnits.len(cfg.grabMaxReach)
        val reach = if (toTarget.len > maxReach) toTarget.norm * maxReach else toTarget
        val pull = reach * cfg.grabStiffness - vel * cfg.grabDamping
        world.impVelX[slot] += pull.x.raw; world.impVelY[slot] += pull.y.raw
        if (g.sticky) world.cell.stickyTemp[slot] = true
    }
}

/** Drag force phase — viscous drag. */
class DragSystem : SoaSystem<CytoConfig, CytoWorld> {
    override fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        val grabbed = (inputs.values.firstOrNull() as? CytoInput)?.grab?.entity?.value ?: -1
        for (i in 0 until world.count) {
            if (world.entityId[i] == grabbed) continue
            var exposed = Coord2(Coord(world.velX[i]), Coord(world.velY[i])).asFrac2()
            val pos = Coord2(Coord(world.posX[i]), Coord(world.posY[i]))
            for (k in world.csr.offset[i] until world.csr.offset[i + 1]) {
                val nSlot = world.csr.otherSlot[k]; if (nSlot < 0) continue
                val normal = (Coord2(Coord(world.posX[nSlot]), Coord(world.posY[nSlot])) - pos).norm
                val toward = exposed.dot(normal)
                if (toward.raw > 0L) exposed -= normal * toward
            }
            val speed = CytoUnits.toLogical(exposed.len)
            if (speed == 0f) continue
            val radius = Frac(world.cell.logicalRadius[i]).toFloat()
            val surfaceDrag = cfg.dragCoefficient * speed * speed
            val widthDrag = cfg.cellWidthDragCoefficient * radius * speed
            val dragSpeed = min(cfg.dragMaxFraction * speed, surfaceDrag + widthDrag)
            val impulse = exposed.norm * CytoUnits.len(-dragSpeed)
            world.impVelX[i] += impulse.x.raw; world.impVelY[i] += impulse.y.raw
        }
    }
}

/** Spring constraint solve phase — parallel per-body gather Jacobi. */
class SpringSolveSystem(
    private val executor: ParallelExecutor?,
    private val springParallelThreshold: Int,
    private val state: CytoPipelineState,
) : SoaSystem<CytoConfig, CytoWorld> {
    override fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        val n = world.count
        if (n == 0) return
        state.ensureSpringScratch(n)
        val p0x = state.ssP0x; val p0y = state.ssP0y; val px = state.ssPx; val py = state.ssPy
        val bvx = state.ssBvx; val bvy = state.ssBvy; val vx = state.ssVx; val vy = state.ssVy
        val mass = state.ssMass; val dx = state.ssDx; val dy = state.ssDy
        val csr = world.csr

        var anySpring = false
        for (i in 0 until n) {
            if (csr.degreeOf(i) == 0) continue
            anySpring = true
            val pxr = world.posX[i].toLong(); val pyr = world.posY[i].toLong()
            p0x[i] = pxr; p0y[i] = pyr; px[i] = pxr; py[i] = pyr
            bvx[i] = world.velX[i].toLong(); bvy[i] = world.velY[i].toLong()
            vx[i] = bvx[i] + world.impVelX[i]; vy[i] = bvy[i] + world.impVelY[i]
            mass[i] = world.mass[i].toUInt().toLong()
        }
        if (!anySpring) return

        // Precompute per-edge normals and mass weights
        val edges = csr.offset[n]
        state.ensureEdgeScratch(edges)
        val enX = state.ssEnX; val enY = state.ssEnY; val ew = state.ssEw
        ColumnPartition.disjoint(n, executor, springParallelThreshold) { start, end ->
            for (i in start until end) {
                if (csr.degreeOf(i) == 0) continue
                val mi = mass[i]; val p0ix = p0x[i]; val p0iy = p0y[i]
                for (k in csr.offset[i] until csr.offset[i + 1]) {
                    val j = csr.otherSlot[k]
                    if (j < 0) { enX[k] = 0L; enY[k] = 0L; ew[k] = 0L; continue }
                    val total = mi + mass[j]
                    ew[k] = if (total <= 0L) 0L else mass[j] * FRAC_MAX / total.toInt().toLong()
                    val ddx = (p0x[j].toInt() - p0ix.toInt()).toLong(); val ddy = (p0y[j].toInt() - p0iy.toInt()).toLong()
                    val dist = lenRaw(ddx, ddy)
                    if (dist == 0L) { enX[k] = 0L; enY[k] = 0L } else {
                        enX[k] = ddx * FRAC_MAX / dist; enY[k] = ddy * FRAC_MAX / dist
                    }
                }
            }
        }

        // Velocity solve
        repeat(ITERATIONS) {
            ColumnPartition.disjoint(n, executor, springParallelThreshold) { start, end ->
                for (i in start until end) {
                    if (csr.degreeOf(i) == 0) continue
                    var accX = 0L; var accY = 0L
                    val vix = vx[i]; val viy = vy[i]
                    for (k in csr.offset[i] until csr.offset[i + 1]) {
                        val j = csr.otherSlot[k]; if (j < 0) continue
                        val nx = enX[k]; val ny = enY[k]
                        val rvx = vx[j] - vix; val rvy = vy[j] - viy
                        val relVel = rvx * nx / FRAC_MAX + rvy * ny / FRAC_MAX
                        val vCorr = relVel * csr.dampRaw[k] / FRAC_MAX
                        val scalar = vCorr * ew[k] / FRAC_MAX
                        accX += nx * scalar / FRAC_MAX; accY += ny * scalar / FRAC_MAX
                    }
                    dx[i] = accX; dy[i] = accY
                }
            }
            for (i in 0 until n) { if (csr.degreeOf(i) == 0) continue; vx[i] += dx[i]; vy[i] += dy[i] }
        }

        // Position solve
        val compStiffMul = cfg.weldCompressionStiffnessMultiple
        repeat(ITERATIONS) {
            ColumnPartition.disjoint(n, executor, springParallelThreshold) { start, end ->
                for (i in start until end) {
                    if (csr.degreeOf(i) == 0) continue
                    var accX = 0L; var accY = 0L
                    val pix = px[i]; val piy = py[i]
                    for (k in csr.offset[i] until csr.offset[i + 1]) {
                        val j = csr.otherSlot[k]; if (j < 0) continue
                        val ddx = (px[j].toInt() - pix.toInt()).toLong()
                        val ddy = (py[j].toInt() - piy.toInt()).toLong()
                        val dist = lenRaw(ddx, ddy); if (dist == 0L) continue
                        val nx = ddx * FRAC_MAX / dist; val ny = ddy * FRAC_MAX / dist
                        val lengthError = dist - csr.restRaw[k]
                        var pCorr = lengthError * csr.stiffRaw[k] / FRAC_MAX
                        if (lengthError < 0L) pCorr *= compStiffMul
                        val scalar = pCorr * ew[k] / FRAC_MAX
                        accX += nx * scalar / FRAC_MAX; accY += ny * scalar / FRAC_MAX
                    }
                    dx[i] = accX; dy[i] = accY
                }
            }
            for (i in 0 until n) { if (csr.degreeOf(i) == 0) continue; px[i] += dx[i]; py[i] += dy[i] }
        }

        // Emit impulses
        for (i in 0 until n) {
            if (csr.degreeOf(i) == 0) continue
            world.impVelX[i] = vx[i] - bvx[i]; world.impVelY[i] = vy[i] - bvy[i]
            world.impPosX[i] += px[i] - p0x[i]; world.impPosY[i] += py[i] - p0y[i]
        }
    }
}

/** Integration phase. */
class IntegrateSystem : SoaSystem<CytoConfig, CytoWorld> {
    override fun update(cfg: CytoConfig, world: CytoWorld, inputs: Map<PlayerId, *>) {
        for (i in 0 until world.count) {
            val transform = transformAt(world, i)
            val motion = MotionComponent(Coord2(Coord(world.velX[i]), Coord(world.velY[i])), Coord(world.angVel[i]))
            val impulse = ImpulseComponent(
                pos = Frac2(Frac(world.impPosX[i]), Frac(world.impPosY[i])),
                vel = Frac2(Frac(world.impVelX[i]), Frac(world.impVelY[i])),
                angVel = Frac(world.impAngVel[i]),
            )
            val vel = motion.vel + impulse.vel
            val pos = transform.pos + impulse.pos + vel.asFrac2()
            val ang = transform.ang + Frac(motion.angVel.raw.toLong()) + impulse.angVel / 2
            val angVel = motion.angVel + impulse.angVel
            world.posX[i] = pos.x.raw; world.posY[i] = pos.y.raw; world.ang[i] = ang.raw
            world.velX[i] = pos.x.raw - transform.pos.x.raw
            world.velY[i] = pos.y.raw - transform.pos.y.raw
            world.angVel[i] = angVel.raw
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Pipeline factory
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Creates the SoA pipeline list for the Cyto hot-path simulation.
 * Each system runs in-place on CytoWorld columns; lifecycle/interaction
 * bridges remain in [CytoSoaReducer] as isolated phases.
 */
data class CytoPipelineParts(
    /** Hot systems that run BEFORE biology: reset, contacts. */
    val preBio: List<SoaSystem<CytoConfig, CytoWorld>>,
    /** The biology updater (not a SoaSystem because it returns divide/destroy via state). */
    val bioUpdate: (CytoConfig, CytoWorld, Map<PlayerId, *>) -> Unit,
    /** Hot systems that run AFTER biology: connections, grab, drag, springSolve, integrate. */
    val postBio: List<SoaSystem<CytoConfig, CytoWorld>>,
)

/**
 * Builds the Cyto hot pipeline split at the biology boundary. Biology runs between contacts
 * and connections, so we return pre-bio and post-bio system lists separately.
 */
fun CytoHotPipeline(
    state: CytoPipelineState,
    executor: ParallelExecutor?,
    springParallelThreshold: Int = 2048,
    bioParallelThreshold: Int = Int.MAX_VALUE,
    profiler: PipelineProfiler?,
    noMutateEntityIdProvider: () -> Int,
    bioProfile: org.emerge.demo.cyto.sim.BioProfile? = null,
): CytoPipelineParts {
    val bioSystem = BiologySystem(executor, springParallelThreshold, bioParallelThreshold, profiler, noMutateEntityIdProvider, state, bioProfile)
    val bioUpdate: (CytoConfig, CytoWorld, Map<PlayerId, *>) -> Unit = bioSystem::update

    return CytoPipelineParts(
        preBio = listOf(
            ResetSystem(),
            ContactsSystem(executor, state),
        ),
        bioUpdate = bioUpdate,
        postBio = listOf(
            ConnectionsSystem(state),
            GrabSystem(),
            DragSystem(),
            SpringSolveSystem(executor, springParallelThreshold, state),
            IntegrateSystem(),
        ),
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// Helper functions (extracted from CytoSoaReducer companion/object scope)
// ──────────────────────────────────────────────────────────────────────────────

/** The number of Jacobi iterations used by the spring constraint solver. */
const val ITERATIONS = 3

/** Frac fixed-point scale (= Int.MAX_VALUE as Long). */
const val FRAC_MAX = 2147483647L  // Int.MAX_VALUE

/** Exact replica of Frac2.len(x,y) on raw longs (no Frac2 allocation). */
private val SQRT_MAX_INT: Long = longISqrt(FRAC_MAX)

fun transformAt(w: CytoWorld, slot: Int): TransformComponent =
    TransformComponent(Coord2(Coord(w.posX[slot]), Coord(w.posY[slot])), Coord(w.ang[slot]))

fun delta(w: CytoWorld, a: Int, b: Int): Frac2 =
    Coord2(Coord(w.posX[b]), Coord(w.posY[b])) - Coord2(Coord(w.posX[a]), Coord(w.posY[a]))

fun deltaLen(w: CytoWorld, a: Int, b: Int): Frac = delta(w, a, b).len

/** Whether slot [i] has a CSR edge to entity-id [otherId]. */
fun edgeExists(w: CytoWorld, i: Int, otherId: Int): Boolean {
    for (k in w.csr.offset[i] until w.csr.offset[i + 1]) if (w.csr.otherId[k] == otherId) return true
    return false
}

/** Does the weld [i]–[nSlot] pass ~through a common welded neighbour B? */
fun throughCellChord(w: CytoWorld, i: Int, nSlot: Int, cfg: CytoConfig): Boolean {
    val cosSq = cfg.weldCollinearCos * cfg.weldCollinearCos
    for (k2 in w.csr.offset[i] until w.csr.offset[i + 1]) {
        val b = w.csr.otherSlot[k2]
        if (b < 0 || b == nSlot) continue
        var common = false
        for (k3 in w.csr.offset[nSlot] until w.csr.offset[nSlot + 1]) {
            if (w.csr.otherSlot[k3] == b) { common = true; break }
        }
        if (!common) continue
        val bix = (w.posX[i] - w.posX[b]).toFloat(); val biy = (w.posY[i] - w.posY[b]).toFloat()
        val bjx = (w.posX[nSlot] - w.posX[b]).toFloat(); val bjy = (w.posY[nSlot] - w.posY[b]).toFloat()
        val dot = bix * bjx + biy * bjy
        if (dot >= 0f) continue
        val la2 = bix * bix + biy * biy; val lb2 = bjx * bjx + bjy * bjy
        if (dot * dot > cosSq * la2 * lb2) return true
    }
    return false
}

/** Rebuild the CSR dropping the [broken] pairs (both directions). */
fun pruneEdges(w: CytoWorld, broken: HashSet<Long>) {
    val keep = HashMap<Int, MutableList<SpringConstraint>>(w.count)
    val dmg = HashMap<Int, HashMap<EntityId, Float>>(w.count)
    for (slot in 0 until w.count) {
        val ownerId = w.entityId[slot]
        for (k in w.csr.offset[slot] until w.csr.offset[slot + 1]) {
            val otherId = w.csr.otherId[k]
            if (broken.contains(pairKey(ownerId, otherId))) continue
            keep.getOrPut(ownerId) { ArrayList() }
                .add(SpringConstraint(EntityId(otherId), Frac(w.csr.restRaw[k]), Frac(w.csr.stiffRaw[k]), Frac(w.csr.dampRaw[k])))
            dmg.getOrPut(ownerId) { HashMap() }[EntityId(otherId)] = w.csr.edgeAux[k]
        }
    }
    w.csr.rebuildFrom(
        count = w.count,
        entityIdAt = { w.entityId[it] },
        slotOf = { w.slotOf(it) },
        springsAt = { slot -> keep[w.entityId[slot]] ?: emptyList() },
        edgeAuxAt = { slot, other -> dmg[w.entityId[slot]]?.get(other) ?: 0f },
    )
}

/** Canonical undirected pair key (low/high entity-id packed into a Long). */
fun pairKey(a: Int, b: Int): Long {
    val lo = min(a, b); val hi = max(a, b)
    return (lo.toLong() shl 32) or (hi.toLong() and 0xFFFFFFFFL)
}

/** Insertion sort (used by contact broadphase neighbour sorting). */
fun insertionSort(a: IntArray, size: Int) {
    for (i in 1 until size) {
        val v = a[i]; var j = i - 1
        while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j -= 1 }
        a[j + 1] = v
    }
}

/** Absolute value for Long. */
fun longAbs(v: Long): Long = if (v < 0L) -v else v

/** Integer sqrt, identical to Frac2.longISqrt. */
fun longISqrt(n: Long, min: Long = 2L, max: Long = 2L * FRAC_MAX): Long {
    if (n < 2) return n
    var x = kotlin.math.sqrt(n.toDouble()).toLong()
    if (x < 1L) x = 1L
    while (x > n / x) x--
    while (x + 1L <= n / (x + 1L)) x++
    return if (x < min) min else if (x > max) max else x
}

/** Raw-space integer hypot, identical to Frac2.len. */
fun lenRaw(xr: Long, yr: Long): Long {
    val ax = if (xr < 0L) -xr else xr
    val ay = if (yr < 0L) -yr else yr
    if (ax == 0L) return ay
    if (ay == 0L) return ax
    return if (ax <= FRAC_MAX && ay <= FRAC_MAX) longISqrt(ax * ax + ay * ay)
    else longISqrt(ax * ax / FRAC_MAX + ay * ay / FRAC_MAX, 2L, ax + ay) * SQRT_MAX_INT
}

/** Handle contact: weld decision + repulsion impulse. */
fun handleContact(w: CytoWorld, i: Int, j: Int, contact: Contact, cfg: CytoConfig, state: CytoPipelineState) {
    val sticky = w.cell.sticky[i] || w.cell.stickyTemp[i] || w.cell.sticky[j] || w.cell.stickyTemp[j]
    val close = CytoTuning.AUTO_WELD_ON_OVERLAP && contact.penetration.raw * 4L > contact.minDist.raw
    if (sticky || close) {
        val ai = w.entityId[i]; val bi = w.entityId[j]
        if (ai < bi) { state.weldLo.add(ai); state.weldHi.add(bi) } else { state.weldLo.add(bi); state.weldHi.add(ai) }
        return
    }
    state.touchScratch[i]++; state.touchScratch[j]++
    state.touchingScratch[i].add(w.entityId[j]); state.touchingScratch[j].add(w.entityId[i])
    val massA = w.mass[i].toUInt(); val massB = w.mass[j].toUInt()
    val total = (massA + massB).toLong()
    if (total <= 0L) return
    val weightA = Frac(massB.toLong(), total.toInt())
    val weightB = Frac(massA.toLong(), total.toInt())
    val normal = contact.normal
    val vn = Frac2(
        Frac((w.velX[i].toLong() + w.impVelX[i]) - (w.velX[j].toLong() + w.impVelX[j])),
        Frac((w.velY[i].toLong() + w.impVelY[i]) - (w.velY[j].toLong() + w.impVelY[j])),
    ).dot(normal)
    val effective = contact.penetration * cfg.repulsion - vn * cfg.contactDamping
    val impA = normal * (effective * weightA)
    val impB = -(normal * (effective * weightB))
    w.impVelX[i] += impA.x.raw; w.impVelY[i] += impA.y.raw
    w.impVelX[j] += impB.x.raw; w.impVelY[j] += impB.y.raw
}
