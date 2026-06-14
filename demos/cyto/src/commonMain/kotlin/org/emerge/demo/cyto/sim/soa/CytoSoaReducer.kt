package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterGrid
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.systems.CellDestroyIntent
import org.emerge.demo.cyto.sim.systems.CellDivisionIntent
import org.emerge.demo.cyto.sim.systems.CytoBiologySystem
import org.emerge.demo.cyto.sim.systems.CytoInteractionSystem
import org.emerge.demo.cyto.sim.systems.CytoLifecycleSystem
import org.emerge.demo.cyto.sim.systems.DetachIntent
import org.emerge.demo.cyto.sim.systems.WeldIntent
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.ecs.SpatialGrid
import org.emerge.sim.core.physics.components.ColliderComponent

import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.contacts
import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource

/**
 * Struct-of-arrays cyto tick on the persistent [CytoWorld] — approach (B) of SOA_LANDING_PLAN.md.
 *
 * The physics phases (diffusion, reset, contacts, connections, forces = grab+drag+spring, integrate)
 * run **in place on the columns** — math reconstructs the engine `Frac`/`Coord2`/`Norm` value types from
 * the column raws and reuses the exact operators, so results are bit-identical to the AoS systems. The
 * two intricate/structural phases — **biology** and **lifecycle** — are still **bridged**: a minimal
 * `SimState` is materialized, the unmodified AoS system runs on it, and the result is folded back into
 * the columns (biology writes back into existing slots, preserving impulse + CSR; lifecycle changes
 * membership so the world is rebuilt and survivors' impulse restored). Events cross the bridges by
 * extraction (biology's divide/destroy) and injection (into lifecycle).
 *
 * Gated tick-for-tick against [org.emerge.demo.cyto.sim.CytoReducer] by `CytoSoaEquivalenceTest`.
 */
class CytoSoaReducer(
    private val cfg: CytoConfig,
    private val executor: ParallelExecutor? = null,
    private val profiler: PipelineProfiler? = null,
) {
    private val player = mapOf(PlayerId(0) to CytoInput.EMPTY)

    // weld intents produced by the in-place contacts phase, drained by the lifecycle bridge.
    private val weldLo = ArrayList<Int>()
    private val weldHi = ArrayList<Int>()

    // CellDestroyIntents from Delete taps: emitted by CytoInteractionSystem in the interaction bridge,
    // whose build() discards events — so we extract them here and marshal them into the lifecycle bridge
    // (which consumes them), exactly as the AoS pipeline carries the event across phases.
    private val interactDestroy = ArrayList<Int>()

    fun tick(w: CytoWorld, input: CytoInput = CytoInput.EMPTY): CytoWorld {
        val inputs = if (input === CytoInput.EMPTY) player else mapOf(PlayerId(0) to input)
        interactDestroy.clear()
        var cur = w
        // interact: interaction (only when there's pointer input) then matter diffusion.
        cur = phaseR("interact") {
            if (input.spawns.isNotEmpty() || input.taps.isNotEmpty()) cur = bridgeInteraction(cur, inputs)
            cur.grid = cur.grid.diffused(CytoMatterGrid.DIFFUSE_NUM, CytoMatterGrid.DIFFUSE_DEN)
            cur
        }
        phase("reset") { reset(cur) }
        phase("contacts") { contacts(cur) }
        val (divide, destroy) = phaseR("biology") { bridgeBiology(cur, inputs) }
        phase("connections") { connections(cur) }
        phase("forces") { grab(cur, input); drag(cur, input); springSolve(cur) }
        cur = phaseR("lifecycle") { bridgeLifecycle(cur, divide, destroy, input, inputs) }
        phase("integrate") { integrate(cur) }
        return cur
    }

    private inline fun phase(name: String, block: () -> Unit) {
        val p = profiler ?: return block()
        val start = TimeSource.Monotonic.markNow()
        block()
        p.recordPhase(name, start.elapsedNow().inWholeNanoseconds)
    }

    private inline fun <T> phaseR(name: String, block: () -> T): T {
        val p = profiler ?: return block()
        val start = TimeSource.Monotonic.markNow()
        val r = block()
        p.recordPhase(name, start.elapsedNow().inWholeNanoseconds)
        return r
    }

    // ── reset (ImpulseResetSystem) — zero the dense impulse accumulator ──────────
    private fun reset(w: CytoWorld) {
        for (i in 0 until w.count) {
            w.impPosX[i] = 0L; w.impPosY[i] = 0L; w.impVelX[i] = 0L; w.impVelY[i] = 0L; w.impAngVel[i] = 0L
        }
    }

    // ── contacts (engine ContactSystem broadphase + CytoContactSystem) ──────────
    private fun contacts(w: CytoWorld) {
        weldLo.clear(); weldHi.clear()
        val n = w.count
        if (n < 2) return
        var maxRadius = 0L
        for (i in 0 until n) if (w.radiusRaw[i] > maxRadius) maxRadius = w.radiusRaw[i]
        if (maxRadius <= 0L) return
        val grid = SpatialGrid.forMinCellSize(
            minCellSize = maxRadius * 2L,
            maxCellsPerAxisLog2 = SpatialGrid.cellsPerAxisLog2For(n),
        ) ?: return
        for (i in 0 until n) grid.insert(i, w.posX[i], w.posY[i])

        // Sequential single pass in (i asc, j asc) order — matches ContactSystem's emitted list order
        // and CytoContactSystem's processing order. Repulsion impulse is additive (order-free anyway).
        var scratch = IntArray(16)
        for (i in 0 until n) {
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
                    aId = EntityId(w.entityId[i]), bId = EntityId(w.entityId[j]),
                    aTransform = transformAt(w, i), bTransform = transformAt(w, j),
                    aRadius = Frac(aR), bRadius = Frac(w.radiusRaw[j]),
                ) ?: continue
                handleContact(w, i, j, contact)
            }
        }
    }

    private fun handleContact(w: CytoWorld, i: Int, j: Int, contact: Contact) {
        if (edgeExists(w, i, w.entityId[j])) return
        val sticky = w.cell.sticky[i] || w.cell.stickyTemp[i] || w.cell.sticky[j] || w.cell.stickyTemp[j]
        val close = contact.penetration.raw * 4L > contact.minDist.raw
        if (sticky || close) {
            val ai = w.entityId[i]; val bi = w.entityId[j]
            if (ai < bi) { weldLo.add(ai); weldHi.add(bi) } else { weldLo.add(bi); weldHi.add(ai) }
            return
        }
        val massA = w.mass[i].toUInt(); val massB = w.mass[j].toUInt()
        val total = (massA + massB).toLong()
        if (total <= 0L) return
        val weightA = Frac(massB.toLong(), total.toInt())
        val weightB = Frac(massA.toLong(), total.toInt())
        val push = contact.penetration * cfg.repulsion
        val normal = contact.normal
        val impA = normal * (push * weightA)
        val impB = -(normal * (push * weightB))
        w.impVelX[i] += impA.x.raw; w.impVelY[i] += impA.y.raw
        w.impVelX[j] += impB.x.raw; w.impVelY[j] += impB.y.raw
    }

    // ── connections (CytoConnectionMaintenanceSystem) ───────────────────────────
    // Refresh rest lengths, accrue stress damage into edgeAux, break over-stressed springs.
    private fun connections(w: CytoWorld) {
        val broken = HashSet<Long>()
        for (i in 0 until w.count) {
            val radiusA = w.radiusRaw[i]
            for (k in w.csr.offset[i] until w.csr.offset[i + 1]) {
                val nSlot = w.csr.otherSlot[k]
                if (nSlot < 0) continue
                val rest = radiusA + w.radiusRaw[nSlot]
                val dist = deltaLen(w, i, nSlot)
                val stretch = CytoUnits.toLogical(dist) - CytoUnits.toLogical(Frac(rest))
                val stress = max(0f, stretch * cfg.connectionStressScale)
                val damage = max(0f, w.csr.edgeAux[k] + stress)
                if (damage > cfg.connectionBreakDamage) {
                    broken.add(pairKey(w.entityId[i], w.csr.otherId[k]))
                } else {
                    w.csr.restRaw[k] = rest
                    w.csr.stiffRaw[k] = cfg.springStiffness.raw
                    w.csr.dampRaw[k] = cfg.springDamping.raw
                    w.csr.edgeAux[k] = damage
                }
            }
        }
        if (broken.isEmpty()) return
        pruneEdges(w, broken)
    }

    // ── forces: grab (CytoGrabSystem) ───────────────────────────────────────────
    private fun grab(w: CytoWorld, input: CytoInput) {
        val g = input.grab ?: return
        val slot = w.slotOf(g.entity.value); if (slot < 0) return
        val pos = Coord2(Coord(w.posX[slot]), Coord(w.posY[slot]))
        val vel = Coord2(Coord(w.velX[slot]), Coord(w.velY[slot])).asFrac2()
        val target = CytoUnits.coord2(g.x, g.y)
        val toTarget = target - pos
        val maxReach = CytoUnits.len(cfg.grabMaxReach)
        val reach = if (toTarget.len > maxReach) toTarget.norm * maxReach else toTarget
        val pull = reach * cfg.grabStiffness - vel * cfg.grabDamping
        w.impVelX[slot] += pull.x.raw; w.impVelY[slot] += pull.y.raw
        if (g.sticky) w.cell.stickyTemp[slot] = true
    }

    // ── forces: drag (CytoDragSystem) — exposed-surface viscous drag ─────────────
    private fun drag(w: CytoWorld, input: CytoInput) {
        val grabbed = input.grab?.entity?.value ?: -1
        for (i in 0 until w.count) {
            if (w.entityId[i] == grabbed) continue
            var exposed = Coord2(Coord(w.velX[i]), Coord(w.velY[i])).asFrac2()
            val pos = Coord2(Coord(w.posX[i]), Coord(w.posY[i]))
            for (k in w.csr.offset[i] until w.csr.offset[i + 1]) {
                val nSlot = w.csr.otherSlot[k]; if (nSlot < 0) continue
                val normal = (Coord2(Coord(w.posX[nSlot]), Coord(w.posY[nSlot])) - pos).norm
                val toward = exposed.dot(normal)
                if (toward.raw > 0L) exposed -= normal * toward
            }
            val speed = CytoUnits.toLogical(exposed.len)
            if (speed == 0f) continue
            val radius = Frac(w.cell.logicalRadius[i]).toFloat()
            val surfaceDrag = cfg.dragCoefficient * speed * speed
            val widthDrag = cfg.cellWidthDragCoefficient * radius * speed
            val dragSpeed = min(cfg.dragMaxFraction * speed, surfaceDrag + widthDrag)
            val impulse = exposed.norm * CytoUnits.len(-dragSpeed)
            w.impVelX[i] += impulse.x.raw; w.impVelY[i] += impulse.y.raw
        }
    }

    // ── forces: spring solve (SpringConstraintSystem) — sequential Gauss–Seidel, split impulse ──
    private fun springSolve(w: CytoWorld) {
        val n = w.count
        // Collect unique pairs (lo<hi by EntityId) from the CSR, with their spring params.
        val loSlot = ArrayList<Int>(); val hiSlot = ArrayList<Int>()
        val restRaw = ArrayList<Long>(); val stiffRaw = ArrayList<Long>(); val dampRaw = ArrayList<Long>()
        for (i in 0 until n) {
            val ownerId = w.entityId[i]
            for (k in w.csr.offset[i] until w.csr.offset[i + 1]) {
                val nSlot = w.csr.otherSlot[k]; if (nSlot < 0) continue
                if (w.csr.otherId[k] <= ownerId) continue   // each pair once, owned by the lower id
                loSlot.add(i); hiSlot.add(nSlot)
                restRaw.add(w.csr.restRaw[k]); stiffRaw.add(w.csr.stiffRaw[k]); dampRaw.add(w.csr.dampRaw[k])
            }
        }
        val m = loSlot.size
        if (m == 0) return
        // Deterministic order: by (loId, hiId).
        val order = (0 until m).sortedWith(compareBy({ w.entityId[loSlot[it]] }, { w.entityId[hiSlot[it]] }))

        // Working set, slot-indexed (only spring-bearing slots are touched).
        val pos0 = arrayOfNulls<Frac2>(n)
        val pos = arrayOfNulls<Frac2>(n)
        val baseVel = arrayOfNulls<Frac2>(n)
        val vel = arrayOfNulls<Frac2>(n)
        val mass = LongArray(n)
        fun ensure(slot: Int) {
            if (pos0[slot] != null) return
            val p = Coord2(Coord(w.posX[slot]), Coord(w.posY[slot])).asFrac2()
            val bv = Coord2(Coord(w.velX[slot]), Coord(w.velY[slot])).asFrac2()
            val imp = Frac2(Frac(w.impVelX[slot]), Frac(w.impVelY[slot]))
            pos0[slot] = p; pos[slot] = p; baseVel[slot] = bv; vel[slot] = bv + imp
            mass[slot] = w.mass[slot].toUInt().toLong()
        }
        for (idx in order) { ensure(loSlot[idx]); ensure(hiSlot[idx]) }

        // 1) velocity solve (Gauss–Seidel), normals from start positions.
        repeat(ITERATIONS) {
            for (idx in order) {
                val a = loSlot[idx]; val b = hiSlot[idx]
                val delta = pos0[b]!! - pos0[a]!!
                val dist = delta.len
                if (dist.raw == 0L) continue
                val normal = delta.normFromLen(dist)
                val totalMass = mass[a] + mass[b]; if (totalMass <= 0L) continue
                val relVel = (vel[b]!! - vel[a]!!).dot(normal)
                val vCorr = relVel * Frac(dampRaw[idx])
                val weightA = Frac(mass[b], totalMass.toInt())
                val weightB = Frac(mass[a], totalMass.toInt())
                vel[a] = vel[a]!! + normal * (vCorr * weightA)
                vel[b] = vel[b]!! - normal * (vCorr * weightB)
            }
        }
        // 2) position solve (pseudo-velocity), moving working positions toward rest.
        repeat(ITERATIONS) {
            for (idx in order) {
                val a = loSlot[idx]; val b = hiSlot[idx]
                val delta = pos[b]!! - pos[a]!!
                val dist = delta.len
                if (dist.raw == 0L) continue
                val normal = delta.normFromLen(dist)
                val totalMass = mass[a] + mass[b]; if (totalMass <= 0L) continue
                val lengthError = dist - Frac(restRaw[idx])
                val pCorr = lengthError * Frac(stiffRaw[idx])
                val weightA = Frac(mass[b], totalMass.toInt())
                val weightB = Frac(mass[a], totalMass.toInt())
                pos[a] = pos[a]!! + normal * (pCorr * weightA)
                pos[b] = pos[b]!! - normal * (pCorr * weightB)
            }
        }
        // emit: vel channel = net velocity change (incl. prior impulse); pos channel += position correction.
        for (slot in 0 until n) {
            val p0 = pos0[slot] ?: continue
            val vNet = vel[slot]!! - baseVel[slot]!!
            val pNet = pos[slot]!! - p0
            w.impVelX[slot] = vNet.x.raw; w.impVelY[slot] = vNet.y.raw
            w.impPosX[slot] += pNet.x.raw; w.impPosY[slot] += pNet.y.raw
        }
    }

    // ── integrate (IntegrationSystem) ───────────────────────────────────────────
    private fun integrate(w: CytoWorld) {
        for (i in 0 until w.count) {
            val transform = transformAt(w, i)
            val motion = MotionComponent(Coord2(Coord(w.velX[i]), Coord(w.velY[i])), Coord(w.angVel[i]))
            val impulse = ImpulseComponent(
                pos = Frac2(Frac(w.impPosX[i]), Frac(w.impPosY[i])),
                vel = Frac2(Frac(w.impVelX[i]), Frac(w.impVelY[i])),
                angVel = Frac(w.impAngVel[i]),
            )
            val vel = motion.vel + impulse.vel
            val pos = transform.pos + impulse.pos + vel.asFrac2()
            val ang = transform.ang + Frac(motion.angVel.raw.toLong()) + impulse.angVel / 2
            val angVel = motion.angVel + impulse.angVel
            w.posX[i] = pos.x.raw; w.posY[i] = pos.y.raw; w.ang[i] = ang.raw
            w.velX[i] = vel.x.raw; w.velY[i] = vel.y.raw; w.angVel[i] = angVel.raw
        }
    }

    // ── biology bridge ──────────────────────────────────────────────────────────
    // Materialize → run the unmodified CytoBiologySystem → fold the updated cell/material/motion/
    // collider/connection-damage + matter grid back into the EXISTING slots (biology never changes
    // membership), preserving the impulse columns + CSR topology. Returns its divide/destroy intents.
    private fun bridgeBiology(w: CytoWorld, inputs: Map<PlayerId, CytoInput>): Pair<List<EntityId>, List<EntityId>> {
        val builder = SimBuilder(w.toSimState(includeImpulse = false))
        CytoBiologySystem.update(cfg, builder, inputs)
        val divide = builder.events<CellDivisionIntent>().map { it.id }
        val destroy = builder.events<CellDestroyIntent>().map { it.id }
        val out = builder.build()
        val cellsT = out.components.getTable<CytoCellComponent>()
        val matsT = out.components.getTable<MaterialComponent>()
        val motsT = out.components.getTable<MotionComponent>()
        val collsT = out.components.getTable<ColliderComponent>()
        val connT = out.components.getTable<ConnectionStateComponent>()
        for (slot in 0 until w.count) {
            val id = EntityId(w.entityId[slot])
            cellsT[id]?.let { w.cell.scatter(slot, it) }
            matsT[id]?.let { w.material.scatter(slot, it) }
            motsT[id]?.let { w.motion.scatter(slot, it) }
            collsT[id]?.let { w.collider.scatter(slot, it) }
            // Repair genes lower connection damage (and remove the key entirely when fully healed to
            // 0). Fold the post-biology damage into the CSR edges, treating a missing key as 0 — exactly
            // as the connection-maintenance reads `damageState[other] ?: 0f`. (A cell biology didn't
            // touch keeps the materialized map, which still holds every neighbour, so this is a no-op.)
            val dmg = connT[id]?.damage
            for (k in w.csr.offset[slot] until w.csr.offset[slot + 1]) {
                w.csr.edgeAux[k] = dmg?.get(EntityId(w.csr.otherId[k])) ?: 0f
            }
        }
        out.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid?.let { w.grid = it }
        w.world.randomSeed = out.randomSeed
        return divide to destroy
    }

    // ── lifecycle bridge ──────────────────────────────────────────────────────────
    // Materialize → inject the weld/divide/destroy/detach intents → run the unmodified
    // CytoLifecycleSystem (it changes membership: spawns daughters, removes dead, rewires springs) →
    // rebuild the world from its output, restoring surviving cells' in-flight impulse.
    private fun bridgeLifecycle(
        w: CytoWorld,
        divide: List<EntityId>,
        destroy: List<EntityId>,
        input: CytoInput,
        inputs: Map<PlayerId, CytoInput>,
    ): CytoWorld {
        if (weldLo.isEmpty() && divide.isEmpty() && destroy.isEmpty() && input.detaches.isEmpty() && interactDestroy.isEmpty()) return w
        val impById = HashMap<Int, ImpulseComponent>(w.count)
        for (slot in 0 until w.count) impById[w.entityId[slot]] = w.impulse.gather(slot)

        val builder = SimBuilder(w.toSimState(includeImpulse = false))
        for (id in input.detaches) builder.emit(DetachIntent(id))      // interact order
        for (idv in interactDestroy) builder.emit(CellDestroyIntent(EntityId(idv)))  // Delete taps (interact, before biology)
        for (id in destroy) builder.emit(CellDestroyIntent(id))         // biology order
        for (i in weldLo.indices) builder.emit(WeldIntent(EntityId(weldLo[i]), EntityId(weldHi[i]))) // contact order
        for (id in divide) builder.emit(CellDivisionIntent(id))         // biology order
        CytoLifecycleSystem.update(cfg, builder, inputs)
        val out = builder.build()

        val nw = CytoWorld.fromSimState(out)
        for (slot in 0 until nw.count) {
            impById[nw.entityId[slot]]?.let { nw.impulse.scatter(slot, it) }
        }
        nw.world.randomSeed = out.randomSeed
        return nw
    }

    // ── interaction bridge (only when there's pointer input) ─────────────────────
    private fun bridgeInteraction(w: CytoWorld, inputs: Map<PlayerId, CytoInput>): CytoWorld {
        val builder = SimBuilder(w.toSimState(includeImpulse = false))
        CytoInteractionSystem.update(cfg, builder, inputs)
        for (e in builder.events<CellDestroyIntent>()) interactDestroy.add(e.id.value)  // Delete taps → lifecycle
        val out = builder.build()
        val nw = CytoWorld.fromSimState(out)
        nw.world.randomSeed = out.randomSeed
        return nw
    }

    // ── helpers ───────────────────────────────────────────────────────────────────
    private fun transformAt(w: CytoWorld, slot: Int): TransformComponent =
        TransformComponent(Coord2(Coord(w.posX[slot]), Coord(w.posY[slot])), Coord(w.ang[slot]))

    /** Torus-aware position delta posB - posA, as a [Frac2]. */
    private fun delta(w: CytoWorld, a: Int, b: Int): Frac2 =
        Coord2(Coord(w.posX[b]), Coord(w.posY[b])) - Coord2(Coord(w.posX[a]), Coord(w.posY[a]))

    private fun deltaLen(w: CytoWorld, a: Int, b: Int): Frac = delta(w, a, b).len

    /** Whether slot [i] has a CSR edge to entity-id [otherId]. */
    private fun edgeExists(w: CytoWorld, i: Int, otherId: Int): Boolean {
        for (k in w.csr.offset[i] until w.csr.offset[i + 1]) if (w.csr.otherId[k] == otherId) return true
        return false
    }

    /** Rebuild the CSR dropping the [broken] pairs (both directions), preserving edgeAux on the rest. */
    private fun pruneEdges(w: CytoWorld, broken: HashSet<Long>) {
        // Snapshot the surviving adjacency per slot before rebuilding (edgeAux preserved).
        val keep = HashMap<Int, MutableList<SpringConstraint>>(w.count)
        val dmg = HashMap<Int, HashMap<Int, Float>>(w.count)
        for (slot in 0 until w.count) {
            val ownerId = w.entityId[slot]
            for (k in w.csr.offset[slot] until w.csr.offset[slot + 1]) {
                val otherId = w.csr.otherId[k]
                if (broken.contains(pairKey(ownerId, otherId))) continue
                keep.getOrPut(ownerId) { ArrayList() }
                    .add(SpringConstraint(EntityId(otherId), Frac(w.csr.restRaw[k]), Frac(w.csr.stiffRaw[k]), Frac(w.csr.dampRaw[k])))
                dmg.getOrPut(ownerId) { HashMap() }[otherId] = w.csr.edgeAux[k]
            }
        }
        w.csr.rebuildFrom(
            count = w.count,
            entityIdAt = { w.entityId[it] },
            slotOf = { w.slotOf(it) },
            springsAt = { slot -> keep[w.entityId[slot]] ?: emptyList() },
            edgeAuxAt = { slot, other -> dmg[w.entityId[slot]]?.get(other.value) ?: 0f },
        )
    }

    private fun pairKey(a: Int, b: Int): Long {
        val lo = min(a, b); val hi = max(a, b)
        return (lo.toLong() shl 32) or (hi.toLong() and 0xFFFFFFFFL)
    }

    private fun insertionSort(a: IntArray, size: Int) {
        for (i in 1 until size) {
            val v = a[i]; var j = i - 1
            while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j -= 1 }
            a[j + 1] = v
        }
    }

    private fun longAbs(v: Long): Long = if (v < 0L) -v else v

    companion object {
        private const val ITERATIONS = 4   // matches SpringConstraintSystem default
    }
}
