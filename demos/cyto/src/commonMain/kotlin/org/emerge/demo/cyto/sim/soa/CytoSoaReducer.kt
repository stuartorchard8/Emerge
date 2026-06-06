package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CellWork
import org.emerge.demo.cyto.sim.CytoBiologyCore
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.sim.MAX_CHEM
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.RADIUS_ELASTICITY
import org.emerge.demo.cyto.sim.genesForType
import org.emerge.demo.cyto.sim.runGenes
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.ecs.SpatialGrid
import org.emerge.sim.core.ecs.soa.AdditivePartition
import org.emerge.sim.core.ecs.soa.ColumnPartition
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * Struct-of-arrays cyto tick on the generic [SoaWorld][org.emerge.sim.core.ecs.soa.SoaWorld]
 * framework. Runs the same 8-phase pipeline as [org.emerge.demo.cyto.sim.CytoReducer] directly
 * on the persistent [CytoWorld] columns, mutating them in place with NO `SimState` rebuild.
 *
 * Math reconstructs the engine value types (`Frac`/`Coord2`/`Norm`) from the column raws and
 * reuses the exact operators, so results are bit-identical to the engine pipeline; the win is
 * structural (column reads, CSR adjacency, no per-tick snapshot rebuild, no per-cell maps).
 * Parallelism is delegated to the framework's column-partition helpers ([ColumnPartition] /
 * [AdditivePartition]), each bit-identical to its sequential fallback.
 *
 * Lifecycle (weld / division / death) is the growing-colony path implemented by
 * [CytoLifecycle]; chemistry is energy-only on the dense fast path (no benchmark/colony cell
 * type mints a second species).
 */
class CytoSoaReducer(
    private val cfg: CytoConfig,
    /** Optional worker pool; null → fully sequential. */
    private val executor: ParallelExecutor? = null,
) {
    private val dt = 1f / 64f
    private val lifecycleOps = CytoLifecycle(cfg)

    private val ENERGY = CytoCellColumnStore.ENERGY

    // intent scratch, reused across ticks
    private val weldLo = ArrayList<Int>()   // entityId values, lo < hi
    private val weldHi = ArrayList<Int>()
    private val divideIds = ArrayList<Int>()
    private val destroyIds = ArrayList<Int>()

    // interaction scratch (interact phase), drained by the lifecycle phase
    private val interactDetach = ArrayList<Int>()    // cells to cut all connections (Detach mode)
    private val interactDestroy = ArrayList<Int>()   // cells deleted by a Delete tap

    // per-directed-CSR-end break flags, set in the connections phase, drained by applyBreaks
    private var brokenEdge = BooleanArray(0)

    // reusable additive impulse partition for the spring solver (velX/velY channels)
    private val forcePartition = AdditivePartition(channels = 2)

    fun tick(w: CytoWorld, profiler: PipelineProfiler? = null, input: CytoInput = CytoInput.EMPTY) {
        phase(profiler, "interact") { interact(w, input) }
        phase(profiler, "reset") { reset(w) }
        phase(profiler, "contacts") { contacts(w) }
        phase(profiler, "biology") { biology(w) }
        phase(profiler, "connections") { connections(w) }
        // The spring solver runs first, then the grab pull (matching the AoS forces phase:
        // SpringConstraintSystem then CytoGrabSystem).
        phase(profiler, "forces") { forces(w); grab(w, input.grab) }
        phase(profiler, "lifecycle") { lifecycle(w) }
        phase(profiler, "integrate") { integrate(w) }
    }

    private inline fun phase(p: PipelineProfiler?, name: String, block: () -> Unit) {
        if (p == null) { block(); return }
        val start = TimeSource.Monotonic.markNow()
        block()
        p.recordPhase(name, start.elapsedNow().inWholeNanoseconds)
    }

    // ── interact (CytoInteractionSystem) ─────────────────────────────────────────
    // Pointer interactions for this tick. Spawns are appended to the columns NOW (so they
    // participate in this tick's contacts/biology/forces, exactly as the AoS interact phase's
    // freshly-spawned cells do); Delete/Detach are deferred to the lifecycle phase. The CSR is
    // rebuilt once at the end so every appended cell is present (degree-0) before `contacts`.
    private fun interact(w: CytoWorld, input: CytoInput) {
        interactDetach.clear()
        interactDestroy.clear()
        if (input.spawns.isEmpty() && input.taps.isEmpty() && input.detaches.isEmpty()) return

        val needStructure = input.spawns.isNotEmpty() || input.taps.isNotEmpty()
        // Materialize the adjacency BEFORE any append (the CSR still matches the old count); the
        // single rebuild below extends it over the appended cells.
        val adj = if (needStructure) lifecycleOps.materialize(w) else null

        // Explicit spawns first — allocates ids in the same order as CytoInteractionSystem.
        for (s in input.spawns) spawnCellAt(w, s.x, s.y, s.type)

        // Detach hold mode (one-shot, on grab-start): deferred to the lifecycle phase.
        for (id in input.detaches) interactDetach.add(id.value)

        if (input.taps.isNotEmpty()) {
            // Hit-test against the cell set as it stands AFTER explicit spawns, captured once —
            // tap-spawned cells (below) are not re-tested by later taps, matching the AoS snapshot.
            val hitCount = w.count
            for (tap in input.taps) {
                var hitAny = false
                for (slot in 0 until hitCount) {
                    if (!containsPoint(w, slot, tap.x, tap.y)) continue
                    hitAny = true
                    when (tap.mode) {
                        // TapUp modes act on a click; Base/Sticky/Detach are hold modes handled
                        // on grab, so a click is a no-op.
                        TouchMode.Delete -> interactDestroy.add(w.entityId[slot])
                        TouchMode.Set -> w.type[slot] = tap.type.ordinal
                        TouchMode.Activate, TouchMode.Base, TouchMode.Sticky, TouchMode.Detach -> Unit
                    }
                }
                if (!hitAny) spawnCellAt(w, tap.x, tap.y, tap.type)
            }
        }

        if (adj != null) lifecycleOps.rebuild(w, adj)
    }

    /** Spawns a pointer-created cell (surplus energy, min radius), mirroring CytoInteractionSystem. */
    private fun spawnCellAt(w: CytoWorld, x: Float, y: Float, type: CellType) {
        val id = w.world.createEntity()
        lifecycleOps.appendCell(
            w, id,
            pos = CytoUnits.coord2(x, y), vel = Coord2.zero,
            type = type, logicalRadius = MIN_RADIUS, energy = 2f, sticky = false,
        )
    }

    /** Whether the cell at [slot] contains the logical point (non-wrapping, matching the AoS hit-test). */
    private fun containsPoint(w: CytoWorld, slot: Int, x: Float, y: Float): Boolean {
        val dx = CytoUnits.toLogical(Coord(w.posX[slot])) - x
        val dy = CytoUnits.toLogical(Coord(w.posY[slot])) - y
        val r = CytoUnits.toLogical(Frac(w.radiusRaw[slot]))
        return dx * dx + dy * dy < r * r
    }

    // ── grab (CytoGrabSystem) ────────────────────────────────────────────────────
    // Mouse-joint pull toward the pointer, added after the spring solver. Sticky hold mode sets
    // the transient stickyTemp so the held cell welds to contacts next tick (biology clears it).
    private fun grab(w: CytoWorld, grab: CytoInput.Grab?) {
        if (grab == null) return
        val slot = w.slotOf(grab.entity.value)
        if (slot < 0) return
        val target = CytoUnits.coord2(grab.x, grab.y)
        // Torus-aware delta (raw Int subtraction wraps), matching Coord2.minus.
        val toTarget = Frac2(
            Frac((target.x.raw - w.posX[slot]).toLong()),
            Frac((target.y.raw - w.posY[slot]).toLong()),
        )
        val vel = Frac2(Frac(w.velX[slot].toLong()), Frac(w.velY[slot].toLong()))
        val pull = toTarget * cfg.grabStiffness - vel * cfg.grabDamping
        w.impX[slot] += pull.x.raw
        w.impY[slot] += pull.y.raw
        if (grab.sticky) w.stickyTemp[slot] = true
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

        // Parallel detect into per-chunk lists; apply sequentially in chunk-then-detection order
        // so the weld/impulse/touch application order matches the sequential sweep (i-then-j).
        ColumnPartition.detectThenApply(
            n, executor,
            detect = { s, e, out -> detectRange(w, grid, s, e, out) },
            apply = { handleContact(w, it.i, it.j, it.contact) },
        )
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

    // ── biology (CytoBiologySystem) ─────────────────────────────────────────────
    // Energy-only ticks take the dense fast path; ticks with gene-bearing cells or extra
    // chemicals fall back to the shared CytoBiologyCore (bit-identical to the AoS system).
    private fun biology(w: CytoWorld) {
        divideIds.clear(); destroyIds.clear()
        if (needsChemistry(w)) biologySlow(w) else biologyFast(w)
    }

    /** Slow path is needed when any cell has genes (which can mint species) or extra chemicals. */
    private fun needsChemistry(w: CytoWorld): Boolean {
        if (w.extraChem.isNotEmpty() || w.extraPending.isNotEmpty() || w.suppression.isNotEmpty()) return true
        for (i in 0 until w.count) if (genesForType(CellType.entries[w.type[i]]).isNotEmpty()) return true
        return false
    }

    /** Multi-species biology via the shared core: build CellWorks from columns + side-table,
     *  run genes/reactions/act, write results back. Identical to the AoS system by construction. */
    private fun biologySlow(w: CytoWorld) {
        val n = w.count
        val works = LinkedHashMap<EntityId, CellWork>(n)
        val neighbourCounts = HashMap<EntityId, Int>(n)
        val orderedIds = ArrayList<EntityId>(n)
        for (slot in 0 until n) {
            val id = EntityId(w.entityId[slot])
            orderedIds.add(id)
            // Build as a HashMap (not the LinkedHashMap from chemicalsAt) so its iteration order
            // matches the AoS `HashMap(cell.chemicals)` — bucket order is hash-determined and
            // therefore identical for the same key set, which the enzyme-reaction float
            // accumulation depends on (diffusion/genes are per-chemical-independent and unaffected).
            val chem = HashMap(w.chemicalsAt(slot))
            val pend = w.pendingAt(slot)
            for ((k, v) in pend) chem[k] = ((chem[k] ?: 0f) + v).coerceIn(0f, MAX_CHEM)
            works[id] = CellWork(
                chemicals = chem,
                transfers = HashMap(),
                initialSuppression = w.suppression[id.value] ?: emptyMap(),
                touch = w.touch[slot],
                logicalRadius = w.logicalRadius[slot],
                divideCooldown = w.divideCooldown[slot],
                type = CellType.entries[w.type[slot]],
            )
            neighbourCounts[id] = w.csr.degreeOf(slot)
        }
        // pass 1: genes then enzyme reactions.
        for ((_, work) in works) { runGenes(work, dt); work.touch = 0f; CytoBiologyCore.runReactions(work) }
        // pass 2: act (diffusion / energy / growth / type behaviour / division-death decision).
        val divide = ArrayList<EntityId>(); val destroy = ArrayList<EntityId>()
        for (slot in 0 until n) {
            val deg = w.csr.degreeOf(slot)
            val base = w.csr.offset[slot]
            val nbrs = ArrayList<EntityId>(deg)
            for (k in 0 until deg) nbrs.add(EntityId(w.csr.otherId[base + k]))
            CytoBiologyCore.act(orderedIds[slot], works.getValue(orderedIds[slot]), nbrs, works, neighbourCounts, dt, divide, destroy)
        }
        // write back columns + side-table.
        for (slot in 0 until n) {
            val idv = w.entityId[slot]
            val work = works.getValue(orderedIds[slot])
            w.energy[slot] = work.chemicals[ENERGY] ?: 0f
            writeExtras(w.extraChem, idv, work.chemicals)
            w.energyPending[slot] = work.transfers[ENERGY] ?: 0f
            writeExtras(w.extraPending, idv, work.transfers)
            if (work.suppression.isEmpty()) w.suppression.remove(idv) else w.suppression[idv] = work.suppression
            w.logicalRadius[slot] = work.logicalRadius
            w.divideCooldown[slot] = work.divideCooldown
            w.touch[slot] = 0f
            w.stickyTemp[slot] = work.isStickyTemp
            w.radiusRaw[slot] = CytoUnits.len(work.logicalRadius).raw
        }
        for (id in destroy) destroyIds.add(id.value)
        for (id in divide) divideIds.add(id.value)
    }

    private fun writeExtras(table: HashMap<Int, LinkedHashMap<String, Float>>, id: Int, map: Map<String, Float>) {
        var out: LinkedHashMap<String, Float>? = null
        for ((k, v) in map) if (k != ENERGY) (out ?: LinkedHashMap<String, Float>().also { out = it })[k] = v
        val o = out
        if (o == null) table.remove(id) else table[id] = o
    }

    private fun biologyFast(w: CytoWorld) {
        val n = w.count
        // step 1: fold last tick's pending transfers into energy, reset the accumulator.
        // (Genes/reactions are skipped: no gene-bearing cells this tick.)
        for (i in 0 until n) {
            w.energy[i] = (w.energy[i] + w.energyPending[i]).coerceIn(0f, MAX_CHEM)
            w.energyPending[i] = 0f
            w.touch[i] = 0f // pass 1 clears touch after genes (no genes here)
            // No Sticky gene on the fast path, so isStickyTemp is false: clear the transient set
            // by the grab interaction last tick (the AoS biology rewrites stickyTemp every tick).
            w.stickyTemp[i] = false
        }
        // pass 2: act (diffusion, energy update, growth, type behaviour, division/death).
        for (i in 0 until n) {
            if (dt <= 0f) break
            val e = w.energy[i]
            if (e <= 0f) { destroyIds.add(w.entityId[i]); continue }

            val selfConn = w.csr.degreeOf(i)
            for (k in w.csr.offset[i] until w.csr.offset[i + 1]) {
                val nSlot = w.csr.otherSlot[k]
                val nConn = w.csr.degreeOf(nSlot)
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
        val ends = w.csr.ends
        if (brokenEdge.size < ends) brokenEdge = BooleanArray(ends) else brokenEdge.fill(false, 0, ends)
        ColumnPartition.disjoint(w.count, executor) { s, e -> connectionsRange(w, s, e) }
        // breaks take effect before the spring solver (matching the engine reducer's ordering)
        lifecycleOps.applyBreaks(w, brokenEdge)
    }

    private fun connectionsRange(w: CytoWorld, start: Int, end: Int) {
        val drag = -cfg.connectedDrag
        for (i in start until end) {
            // 1. refresh rest lengths / accumulate damage (in-place on this cell's CSR).
            // Breaks are detected here and applied at the lifecycle barrier (CytoLifecycle).
            val radiusA = w.radiusRaw[i]
            for (k in w.csr.offset[i] until w.csr.offset[i + 1]) {
                val nSlot = w.csr.otherSlot[k]
                val rest = radiusA + w.radiusRaw[nSlot]
                val dist = deltaLen(w, i, nSlot)
                val stretch = CytoUnits.toLogical(dist) - CytoUnits.toLogical(Frac(rest))
                val stress = max(0f, stretch * cfg.connectionStressScale) - 0.25f
                val prior = w.csr.edgeAux[k]
                val damage = max(0f, prior + stress)
                w.csr.restRaw[k] = rest
                w.csr.stiffRaw[k] = cfg.springStiffness.raw
                w.csr.dampRaw[k] = cfg.springDamping.raw
                w.csr.edgeAux[k] = damage
                if (damage > cfg.connectionBreakDamage) brokenEdge[k] = true
            }
            // 3. connected-cell drag ("velocity shielding").
            if (w.csr.offset[i] == w.csr.offset[i + 1]) continue
            var unshielded = Frac2(Frac(w.velX[i].toLong()), Frac(w.velY[i].toLong()))
            for (k in w.csr.offset[i] until w.csr.offset[i + 1]) {
                val nSlot = w.csr.otherSlot[k]
                val normal = delta(w, i, nSlot).norm
                val towardOther = unshielded.dot(normal)
                if (towardOther.raw > 0L) unshielded -= normal * towardOther
            }
            val imp = unshielded * drag
            w.impX[i] += imp.x.raw; w.impY[i] += imp.y.raw
        }
    }

    // ── forces (SpringConstraintSystem) ─────────────────────────────────────────
    // A spring writes BOTH endpoints' impulses, so the additive partition accumulates each
    // worker's writes into its own Long buffers and the main thread sums them onto w.imp
    // (integer add is order-independent → bit-identical to the sequential solve).
    private fun forces(w: CytoWorld) {
        forcePartition.run(w.count, executor, arrayOf(w.impX, w.impY)) { s, e, out ->
            forcesRange(w, s, e, out[0], out[1])
        }
    }

    private fun forcesRange(w: CytoWorld, start: Int, end: Int, outX: LongArray, outY: LongArray) {
        for (i in start until end) {
            val idA = w.entityId[i]
            val countA = w.csr.degreeOf(i)
            if (countA == 0) continue
            for (k in w.csr.offset[i] until w.csr.offset[i + 1]) {
                val idB = w.csr.otherId[k]
                if (idB <= idA) continue // solve each pair once, from the lower id
                val nSlot = w.csr.otherSlot[k]
                val delta = delta(w, i, nSlot) // A -> B
                val dist = delta.len
                if (dist.raw == 0L) continue
                val normal = delta.normFromLen(dist)
                val lengthError = dist - Frac(w.csr.restRaw[k])
                val velA = Frac2(Frac(w.velX[i].toLong()), Frac(w.velY[i].toLong()))
                val velB = Frac2(Frac(w.velX[nSlot].toLong()), Frac(w.velY[nSlot].toLong()))
                val separationSpeed = (velB - velA).dot(normal)
                val rawClosing = lengthError * Frac(w.csr.stiffRaw[k]) + separationSpeed * Frac(w.csr.dampRaw[k])
                val countB = w.csr.degreeOf(nSlot)
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

    // ── lifecycle (CytoLifecycleSystem): detach / weld / division / death ────────
    private fun lifecycle(w: CytoWorld) {
        // Merge interaction-driven destroys (Delete taps) with biology deaths. Interaction
        // intents are emitted before biology's in the AoS event stream, so they lead here (the
        // destroy result is order-independent, but this keeps the ordering faithful).
        val destroy = if (interactDestroy.isEmpty()) destroyIds else interactDestroy + destroyIds
        lifecycleOps.apply(w, weldLo, weldHi, divideIds, destroy, interactDetach)
    }

    // ── integrate (IntegrationSystem) ───────────────────────────────────────────
    // Each cell touches only its own slot, so the update is correct in place (no double-buffer).
    private fun integrate(w: CytoWorld) {
        val n = w.count
        for (i in 0 until n) {
            // newVel = vel + impulse (Coord + Frac, Long-add then truncate to Int)
            val nvx = (w.velX[i].toLong() + w.impX[i]).toInt()
            val nvy = (w.velY[i].toLong() + w.impY[i]).toInt()
            // newPos = pos + newVel.asFrac2() (impulse.pos is always zero for cells)
            w.posX[i] = (w.posX[i].toLong() + nvx.toLong()).toInt()
            w.posY[i] = (w.posY[i].toLong() + nvy.toLong()).toInt()
            w.velX[i] = nvx
            w.velY[i] = nvy
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Spring exists from cell at slot [a] to the cell with EntityId at slot [b]. */
    private fun springExists(w: CytoWorld, a: Int, b: Int): Boolean {
        val bId = w.entityId[b]
        for (k in w.csr.offset[a] until w.csr.offset[a + 1]) if (w.csr.otherId[k] == bId) return true
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
}
