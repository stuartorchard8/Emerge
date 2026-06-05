package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.MAX_CHEM
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.RADIUS_ELASTICITY
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.ecs.SpatialGrid
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * Struct-of-arrays cyto tick — the spike prototype. Runs the same 8-phase pipeline as
 * [org.emerge.demo.cyto.sim.CytoReducer] directly on a persistent [CytoWorld], mutating
 * columns in place (double-buffering pos/vel in integrate) with NO `SimState` rebuild.
 *
 * Math is done by reconstructing the engine value types (`Frac`/`Coord2`/`Norm`) from the
 * column raws and reusing the exact operators, so results are bit-identical to the engine
 * pipeline; the win is purely structural (column reads, CSR adjacency, no per-tick snapshot
 * rebuild, no per-cell maps/ArrayLists).
 *
 * Spike scope: energy-only chemistry (no enzyme reactions — see [CytoWorld]); input/grab are
 * no-ops (the benchmark/equivalence harness drive empty input); division/weld/death (lifecycle
 * structural changes) are implemented for the growing-colony scenario but never fire in the
 * settled benchmark on which the go/no-go number is measured.
 */
class CytoSoaReducer(
    private val cfg: CytoConfig,
    /** Optional worker pool; null → fully sequential. Phases parallelise over the dense
     *  columns: connections by disjoint per-cell ranges (no merge), forces into per-chunk
     *  Long buffers merged on the main thread (Frac add is order-independent → bit-identical),
     *  contacts by parallel detect into per-chunk lists handled sequentially in chunk order. */
    private val executor: ParallelExecutor? = null,
) {

    private val dt = 1f / 64f

    // intent scratch, reused across ticks
    private val weldLo = ArrayList<Int>()   // entityId values, lo < hi
    private val weldHi = ArrayList<Int>()
    private val divideIds = ArrayList<Int>()
    private val destroyIds = ArrayList<Int>()

    // reused per-chunk force-impulse buffers (avoid per-tick allocation)
    private var fbufX: Array<LongArray> = emptyArray()
    private var fbufY: Array<LongArray> = emptyArray()

    private fun parallel(): Boolean = executor != null

    private fun chunkBounds(n: Int): IntArray {
        val chunks = executor!!.parallelism.coerceAtMost(n).coerceAtLeast(1)
        val step = (n + chunks - 1) / chunks
        val bounds = IntArray(chunks + 1)
        for (c in 0..chunks) bounds[c] = (c * step).coerceAtMost(n)
        return bounds
    }

    fun tick(w: CytoWorld, profiler: PipelineProfiler? = null) {
        phase(profiler, "reset") { reset(w) }
        phase(profiler, "contacts") { contacts(w) }
        phase(profiler, "biology") { biology(w) }
        phase(profiler, "connections") { connections(w) }
        phase(profiler, "forces") { forces(w) }
        phase(profiler, "lifecycle") { lifecycle(w) }
        phase(profiler, "integrate") { integrate(w) }
    }

    private inline fun phase(p: PipelineProfiler?, name: String, block: () -> Unit) {
        if (p == null) { block(); return }
        val start = TimeSource.Monotonic.markNow()
        block()
        p.recordPhase(name, start.elapsedNow().inWholeNanoseconds)
    }

    // ── reset (ImpulseResetSystem) ──────────────────────────────────────────────
    private fun reset(w: CytoWorld) {
        for (i in 0 until w.count) { w.impX[i] = 0L; w.impY[i] = 0L }
    }

    // ── contacts (engine ContactSystem broadphase + CytoContactSystem) ──────────
    private fun contacts(w: CytoWorld) {
        weldLo.clear(); weldHi.clear()
        val n = w.count
        if (n < 2) return
        var maxRadius = 0L
        for (i in 0 until n) if (w.radiusRaw[i] > maxRadius) maxRadius = w.radiusRaw[i]
        if (maxRadius <= 0L) return
        val grid = SpatialGrid.forMinCellSize(minCellSize = maxRadius * 2L) ?: return
        for (i in 0 until n) grid.insert(i, w.posX[i], w.posY[i])

        // Parallel detect into per-chunk lists; handle sequentially in chunk order so the
        // weld/impulse/touch application order matches the sequential sweep (i-then-j).
        if (parallel() && n >= PARALLEL_THRESHOLD) {
            val bounds = chunkBounds(n)
            val chunks = bounds.size - 1
            val buckets = arrayOfNulls<MutableList<ContactRec>>(chunks)
            val tasks = ArrayList<() -> Unit>(chunks)
            for (c in 0 until chunks) {
                val s = bounds[c]; val e = bounds[c + 1]; if (s >= e) continue
                val cc = c
                tasks.add { val local = ArrayList<ContactRec>(); detectRange(w, grid, s, e, local); buckets[cc] = local }
            }
            executor!!.invokeAll(tasks)
            for (c in 0 until chunks) buckets[c]?.forEach { handleContact(w, it.i, it.j, it.contact) }
        } else {
            val out = ArrayList<ContactRec>()
            detectRange(w, grid, 0, n, out)
            out.forEach { handleContact(w, it.i, it.j, it.contact) }
        }
    }

    private class ContactRec(val i: Int, val j: Int, val contact: Contact)

    /** Collects (never mutates the world) contacts for owners in `[start, end)` — worker-safe. */
    private fun detectRange(w: CytoWorld, grid: SpatialGrid, start: Int, end: Int, out: MutableList<ContactRec>) {
        var scratch = IntArray(16)
        for (i in start until end) {
            val aX = w.posX[i]; val aY = w.posY[i]; val aR = w.radiusRaw[i]
            var cc = 0
            grid.forEachNeighbour(aX, aY) { j ->
                if (j > i) {
                    if (cc >= scratch.size) scratch = scratch.copyOf(scratch.size * 2)
                    scratch[cc] = j; cc += 1
                }
            }
            insertionSort(scratch, cc)
            for (k in 0 until cc) {
                val j = scratch[k]
                val sum = aR + w.radiusRaw[j]
                val dx = longAbs((aX - w.posX[j]).toLong()); if (dx >= sum) continue
                val dy = longAbs((aY - w.posY[j]).toLong()); if (dy >= sum) continue
                val contact = Contact.compute(
                    aId = org.emerge.sim.core.EntityId(w.entityId[i]),
                    bId = org.emerge.sim.core.EntityId(w.entityId[j]),
                    aTransform = transformOf(w, i), bTransform = transformOf(w, j),
                    aRadius = Frac(aR), bRadius = Frac(w.radiusRaw[j]),
                ) ?: continue
                out.add(ContactRec(i, j, contact))
            }
        }
    }

    /** Ported from CytoContactSystem: weld sticky/close non-connected pairs, else repel + touch. */
    private fun handleContact(w: CytoWorld, i: Int, j: Int, contact: Contact) {
        if (springExists(w, i, j) || springExists(w, j, i)) return
        val sticky = w.sticky[i] || w.stickyTemp[i] || w.sticky[j] || w.stickyTemp[j]
        val close = contact.penetration.raw * 4L > contact.minDist.raw
        if (sticky || close) {
            val ai = w.entityId[i]; val bi = w.entityId[j]
            if (ai < bi) { weldLo.add(ai); weldHi.add(bi) } else { weldLo.add(bi); weldHi.add(ai) }
            return
        }
        val massA = w.mass[i].toLong(); val massB = w.mass[j].toLong()
        val total = massA + massB
        if (total <= 0L) return
        val weightA = Frac(massB, total.toInt())
        val weightB = Frac(massA, total.toInt())
        val push = contact.penetration * cfg.repulsion
        val normal = contact.normal
        val impA = normal * (push * weightA)
        val impB = -(normal * (push * weightB))
        w.impX[i] += impA.x.raw; w.impY[i] += impA.y.raw
        w.impX[j] += impB.x.raw; w.impY[j] += impB.y.raw
        val touchAmount = contact.penetration.toFloat()
        w.touch[i] += touchAmount; w.touch[j] += touchAmount
    }

    // ── biology (CytoBiologySystem, energy-only) ────────────────────────────────
    private fun biology(w: CytoWorld) {
        divideIds.clear(); destroyIds.clear()
        val n = w.count
        // step 1: fold last tick's pending transfers into energy, reset the accumulator.
        // (Genes/reactions are skipped: the spike's cell types produce neither.)
        for (i in 0 until n) {
            w.energy[i] = (w.energy[i] + w.energyPending[i]).coerceIn(0f, MAX_CHEM)
            w.energyPending[i] = 0f
            w.touch[i] = 0f // pass 1 clears touch after genes (no genes here)
        }
        // pass 2: act (diffusion, energy update, growth, type behaviour, division/death).
        for (i in 0 until n) {
            if (dt <= 0f) break
            val e = w.energy[i]
            if (e <= 0f) { destroyIds.add(w.entityId[i]); continue }

            val selfConn = w.springOffset[i + 1] - w.springOffset[i]
            for (k in w.springOffset[i] until w.springOffset[i + 1]) {
                val nSlot = w.springOther[k]
                val nConn = w.springOffset[nSlot + 1] - w.springOffset[nSlot]
                val maxConn = max(selfConn, nConn) + 1
                val transfer = e / maxConn // single chemical "energy"; no suppression
                if (transfer > 0f) {
                    w.energyPending[i] -= transfer
                    w.energyPending[nSlot] += transfer
                }
            }

            var targetRadius = 1f
            if (e >= 1f) {
                w.energyPending[i] -= dt
            } else {
                val decay = e * 0.125f + 0.125f
                w.energyPending[i] -= dt * decay * decay
                targetRadius = sqrt(e)
            }
            // contraction is always 0 here (no Contract gene) → no radius scaling.
            when (CellType.entries[w.type[i]]) {
                CellType.Support -> w.energyPending[i] += 5f
                CellType.Stem -> {
                    if (w.divideCooldown[i] > 0f) w.divideCooldown[i] -= dt
                    else if (e > 0.5f) divideIds.add(w.entityId[i])
                }
                else -> Unit
            }
            w.logicalRadius[i] =
                (w.logicalRadius[i] * RADIUS_ELASTICITY + max(targetRadius, MIN_RADIUS)) / (RADIUS_ELASTICITY + 1f)
            // collider radius is a pure function of logicalRadius; recompute (cheap long store).
            w.radiusRaw[i] = CytoUnits.len(w.logicalRadius[i]).raw
        }
    }

    // ── connections (CytoConnectionMaintenanceSystem) ───────────────────────────
    // Every write is per-cell-disjoint (a cell's own CSR entries + its own impulse), so this
    // parallelises by cell range with no merge and is bit-identical to the sequential sweep.
    private fun connections(w: CytoWorld) {
        val n = w.count
        if (parallel() && n >= PARALLEL_THRESHOLD) {
            val bounds = chunkBounds(n)
            val tasks = ArrayList<() -> Unit>(bounds.size - 1)
            for (c in 0 until bounds.size - 1) {
                val s = bounds[c]; val e = bounds[c + 1]; if (s < e) tasks.add { connectionsRange(w, s, e) }
            }
            executor!!.invokeAll(tasks)
        } else {
            connectionsRange(w, 0, n)
        }
    }

    private fun connectionsRange(w: CytoWorld, start: Int, end: Int) {
        val drag = -cfg.connectedDrag
        for (i in start until end) {
            // 1. refresh rest lengths / accumulate damage (in-place on this cell's CSR).
            // Breaks would require a CSR rebuild — deferred to the growing-colony path (none here).
            val radiusA = w.radiusRaw[i]
            for (k in w.springOffset[i] until w.springOffset[i + 1]) {
                val nSlot = w.springOther[k]
                val rest = radiusA + w.radiusRaw[nSlot]
                val dist = deltaLen(w, i, nSlot)
                val stretch = CytoUnits.toLogical(dist) - CytoUnits.toLogical(Frac(rest))
                val stress = max(0f, stretch * cfg.connectionStressScale) - 0.25f
                val prior = w.springDamage[k]
                val damage = max(0f, prior + stress)
                w.springRestRaw[k] = rest
                w.springStiffRaw[k] = cfg.springStiffness.raw
                w.springDampRaw[k] = cfg.springDamping.raw
                w.springDamage[k] = damage
            }
            // 3. connected-cell drag ("velocity shielding").
            if (w.springOffset[i] == w.springOffset[i + 1]) continue
            var unshielded = Frac2(Frac(w.velX[i].toLong()), Frac(w.velY[i].toLong()))
            for (k in w.springOffset[i] until w.springOffset[i + 1]) {
                val nSlot = w.springOther[k]
                val normal = delta(w, i, nSlot).norm
                val towardOther = unshielded.dot(normal)
                if (towardOther.raw > 0L) unshielded -= normal * towardOther
            }
            val imp = unshielded * drag
            w.impX[i] += imp.x.raw; w.impY[i] += imp.y.raw
        }
    }

    // ── forces (SpringConstraintSystem) ─────────────────────────────────────────
    // A spring writes BOTH endpoints' impulses, so a worker can't write w.imp directly
    // (a neighbour may be in another chunk). Each worker accumulates into its own Long
    // buffer; the main thread sums the buffers onto w.imp. Frac/Long add is order-independent
    // → bit-identical to the sequential solve. grab is a no-op in the spike (empty input).
    private fun forces(w: CytoWorld) {
        val n = w.count
        if (parallel() && n >= PARALLEL_THRESHOLD) {
            val bounds = chunkBounds(n)
            val chunks = bounds.size - 1
            ensureForceBuffers(chunks, n)
            val tasks = ArrayList<() -> Unit>(chunks)
            for (c in 0 until chunks) {
                val s = bounds[c]; val e = bounds[c + 1]; if (s >= e) continue
                val cc = c
                tasks.add { forcesRange(w, s, e, fbufX[cc], fbufY[cc]) }
            }
            executor!!.invokeAll(tasks)
            for (c in 0 until chunks) {
                val bx = fbufX[c]; val by = fbufY[c]
                for (k in 0 until n) { w.impX[k] += bx[k]; w.impY[k] += by[k] }
            }
        } else {
            forcesRange(w, 0, n, w.impX, w.impY)
        }
    }

    private fun forcesRange(w: CytoWorld, start: Int, end: Int, outX: LongArray, outY: LongArray) {
        for (i in start until end) {
            val idA = w.entityId[i]
            val countA = w.springOffset[i + 1] - w.springOffset[i]
            if (countA == 0) continue
            for (k in w.springOffset[i] until w.springOffset[i + 1]) {
                val idB = w.springOtherId[k]
                if (idB <= idA) continue // solve each pair once, from the lower id
                val nSlot = w.springOther[k]
                val delta = delta(w, i, nSlot) // A -> B
                val dist = delta.len
                if (dist.raw == 0L) continue
                val normal = delta.normFromLen(dist)
                val lengthError = dist - Frac(w.springRestRaw[k])
                val velA = Frac2(Frac(w.velX[i].toLong()), Frac(w.velY[i].toLong()))
                val velB = Frac2(Frac(w.velX[nSlot].toLong()), Frac(w.velY[nSlot].toLong()))
                val separationSpeed = (velB - velA).dot(normal)
                val rawClosing = lengthError * Frac(w.springStiffRaw[k]) + separationSpeed * Frac(w.springDampRaw[k])
                val countB = w.springOffset[nSlot + 1] - w.springOffset[nSlot]
                val relaxation = max(max(countA, countB), 1)
                val closing = rawClosing / relaxation
                val total = w.mass[i].toLong() + w.mass[nSlot].toLong()
                if (total <= 0L) continue
                val weightA = Frac(w.mass[nSlot].toLong(), total.toInt())
                val weightB = Frac(w.mass[i].toLong(), total.toInt())
                val impA = normal * (closing * weightA)
                val impB = -(normal * (closing * weightB))
                outX[i] += impA.x.raw; outY[i] += impA.y.raw
                outX[nSlot] += impB.x.raw; outY[nSlot] += impB.y.raw
            }
        }
    }

    private fun ensureForceBuffers(chunks: Int, n: Int) {
        if (fbufX.size != chunks || (fbufX.isNotEmpty() && fbufX[0].size < n)) {
            fbufX = Array(chunks) { LongArray(n) }
            fbufY = Array(chunks) { LongArray(n) }
        } else {
            for (c in 0 until chunks) { fbufX[c].fill(0L, 0, n); fbufY[c].fill(0L, 0, n) }
        }
    }

    // ── lifecycle (CytoLifecycleSystem) ─────────────────────────────────────────
    private fun lifecycle(w: CytoWorld) {
        // Settled colony: no weld/divide/destroy intents → nothing to do.
        // Growing-colony structural changes (append daughter + CSR rebuild, tombstone + compact)
        // are the deferred Scenario-B path; assert we're not silently dropping work.
        if (weldLo.isNotEmpty() || divideIds.isNotEmpty() || destroyIds.isNotEmpty()) {
            throw NotImplementedError(
                "SoA lifecycle (weld/divide/destroy) is the deferred growing-colony path; " +
                    "welds=${weldLo.size} divides=${divideIds.size} destroys=${destroyIds.size}",
            )
        }
    }

    // ── integrate (IntegrationSystem) ───────────────────────────────────────────
    private fun integrate(w: CytoWorld) {
        val n = w.count
        for (i in 0 until n) {
            // newVel = vel + impulse (Coord + Frac, Long-add then truncate to Int)
            val nvx = (w.velX[i].toLong() + w.impX[i]).toInt()
            val nvy = (w.velY[i].toLong() + w.impY[i]).toInt()
            // newPos = pos + newVel.asFrac2() (impulse.pos is always zero for cells)
            w.posXNext[i] = (w.posX[i].toLong() + nvx.toLong()).toInt()
            w.posYNext[i] = (w.posY[i].toLong() + nvy.toLong()).toInt()
            w.velXNext[i] = nvx
            w.velYNext[i] = nvy
        }
        // swap double buffers
        var t = w.posX; w.posX = w.posXNext; w.posXNext = t
        t = w.posY; w.posY = w.posYNext; w.posYNext = t
        t = w.velX; w.velX = w.velXNext; w.velXNext = t
        t = w.velY; w.velY = w.velYNext; w.velYNext = t
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Spring exists from cell at slot [a] to the cell with EntityId at slot [b]. */
    private fun springExists(w: CytoWorld, a: Int, b: Int): Boolean {
        val bId = w.entityId[b]
        for (k in w.springOffset[a] until w.springOffset[a + 1]) if (w.springOtherId[k] == bId) return true
        return false
    }

    /** Torus delta A->B as a Frac2 (matches Coord2.minus). */
    private fun delta(w: CytoWorld, a: Int, b: Int): Frac2 =
        Frac2(Frac((w.posX[b] - w.posX[a]).toLong()), Frac((w.posY[b] - w.posY[a]).toLong()))

    private fun deltaLen(w: CytoWorld, a: Int, b: Int): Frac = delta(w, a, b).len

    private fun transformOf(w: CytoWorld, i: Int): TransformComponent =
        TransformComponent(Coord2(Coord(w.posX[i]), Coord(w.posY[i])), Coord(w.ang[i]))

    private fun insertionSort(a: IntArray, size: Int) {
        for (i in 1 until size) {
            val v = a[i]; var j = i - 1
            while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j-- }
            a[j + 1] = v
        }
    }

    private fun longAbs(v: Long): Long = if (v < 0L) -v else v

    companion object {
        /** Below this cell count, fork/join dispatch overhead outweighs the parallel win. */
        private const val PARALLEL_THRESHOLD = 256
    }
}
