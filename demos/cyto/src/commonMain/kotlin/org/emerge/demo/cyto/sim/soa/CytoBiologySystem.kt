package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.BioProfile
import org.emerge.demo.cyto.sim.systems.LyseAttackIntent
import org.emerge.demo.cyto.sim.CellWork
import org.emerge.demo.cyto.sim.CytoBiologyCore
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoExposure
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoMutation
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.MoleculeStore
import org.emerge.demo.cyto.sim.cellMass
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.ecs.soa.ColumnPartition
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Frac
import kotlin.time.TimeSource

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
        val orderedIds = state.bioOrderedIds.also { it.clear() }

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
            val eid = EntityId(world.entityId[slot])
            neighbourIds[eid] = state.bioNbrs[slot]!!
            works[eid] = work
            orderedIds.add(eid)
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
        CytoBiologyCore.passiveEnvExchange(orderedWorks, grid, world.world.tick.toInt(),
            state.exchangeScratch, bioExec, threshold = 1, stats = bioProfile)
        bioSplit("bio:exchange")

        // Cytoplasm diffusion between connected cells — runs every N ticks to reduce cost
        if (world.world.tick % CytoTuning.CYTOPLASM_DIFFUSE_PERIOD == 0L) {
            CytoBiologyCore.diffuse(orderedIds, works, neighbourIds, bioExec, threshold = 1)
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

        // Finish (degrade + death/division decision) — the per-cell compute is cell-local, so it runs
        // slot-partitioned in parallel; the grid deposit + divide/destroy/weld-heal harvests are shared
        // writes replayed serially below in k-order (bit-identical to the single-threaded sweep).
        ColumnPartition.disjoint(n, bioExec, threshold = 1) { kStart, kEnd ->
            for (k in kStart until kEnd) CytoBiologyCore.finishCompute(state.bioWorks[ordered[k]]!!)
        }
        for (k in 0 until n) {
            val slot = ordered[k]
            val id = EntityId(world.entityId[slot])
            val work = state.bioWorks[slot]!!
            CytoBiologyCore.applyDegradeDeposit(work, grid)
            if (work.dying) state.destroy.add(id) else if (work.dividing) state.divide.add(id)
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
        // Per-cell disjoint: every write below is slot-local (durable columns, mass, this slot's own CSR
        // edges) and each cell's mutation RNG is derived purely from (world seed, entity id, tick) — never
        // from iteration order — so the loop parallelises. The slab swap is a single serial pointer swap after.
        val noMutateEntityId = noMutateEntityIdProvider()
        val rateDenom = if (world.mutationRateDenom >= 0) world.mutationRateDenom else cfg.mutationRateDenom
        val baseSeed = world.world.randomSeed
        val mutTick = world.world.tick
        ColumnPartition.disjoint(n, bioExec, threshold = 1) { kStart, kEnd ->
          val rng = MutationRng()
          val draw: (Int) -> Int = { until -> rng.nextInt(until) }
          for (k in kStart until kEnd) {
            val slot = ordered[k]
            val work = state.bioWorks[slot]!!
            val entityId = world.entityId[slot]
            val mutated = if (entityId == noMutateEntityId || rateDenom <= 0) null
                else { rng.seed(baseSeed, entityId, mutTick); CytoMutation.mutate(world.cell.genome[slot] ?: emptyList(), rateDenom, draw) }

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

}

/**
 * Per-cell mutation PRNG (splitmix64). Order-INDEPENDENT: [seed] derives a cell's stream purely from the
 * world seed, the cell's entity id, and the tick — never from how many cells ran before it — so the
 * write-back loop parallelises and stays bit-identical to its sequential fallback. splitmix64's finalizer
 * avalanches every input bit across all 64 output bits, so cells with ADJACENT entity ids (spatially
 * clustered clones) get fully decorrelated streams rather than near-identical mutations. Pure Long
 * arithmetic ⇒ deterministic across JVM/JS/native. One instance per worker chunk (re-seeded per cell).
 */
internal class MutationRng {
    private var state = 0L
    fun seed(base: Long, entityId: Int, tick: Long) {
        // Combine the three inputs with distinct odd increments, then one splitmix finalizer so the starting
        // state is well-scrambled before the first draw (adjacent entity ids ⇒ well-separated states).
        var z = base + entityId.toLong() * GAMMA + tick * TICK_GAMMA
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        state = z xor (z ushr 31)
    }
    fun nextInt(until: Int): Int {
        state += GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        z = z xor (z ushr 31)
        return ((z and 0x7FFFFFFFFFFFFFFFL) % until).toInt()
    }
    private companion object {
        // splitmix64 constants written as signed Long literals (top bit set ⇒ negative two's-complement):
        const val GAMMA = -0x61C8864680B583EBL      // 0x9E3779B97F4A7C15 golden-ratio increment
        const val TICK_GAMMA = 0x2545F4914F6CDD1DL   // a second odd increment for the tick axis
        const val MIX1 = -0x40A7B892E31B1A47L        // 0xBF58476D1CE4E5B9
        const val MIX2 = -0x6B2FB644ECCEEE15L        // 0x94D049BB133111EB
    }
}
