package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import kotlin.concurrent.Volatile
import org.emerge.demo.cyto.sim.CellWork
import org.emerge.demo.cyto.sim.MoleculeStore
import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.BioProfile
import org.emerge.demo.cyto.sim.CytoBiologyCore
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoExposure
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoMutation
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.cellMass
import org.emerge.demo.cyto.sim.systems.CellDestroyIntent
import org.emerge.demo.cyto.sim.systems.CellDivisionIntent
import org.emerge.demo.cyto.sim.systems.CytoInteractionSystem
import org.emerge.demo.cyto.sim.systems.CytoLifecycleSystem
import org.emerge.demo.cyto.sim.systems.DetachIntent
import org.emerge.demo.cyto.sim.systems.WeldHealIntent
import org.emerge.demo.cyto.sim.systems.WeldIntent
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.ecs.SpatialGrid
import org.emerge.sim.core.ecs.soa.ColumnPartition
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
 * Most phases (diffusion, reset, contacts, **biology**, connections, forces = grab+drag+spring, integrate)
 * run **in place on the columns** — math reconstructs the engine `Frac`/`Coord2`/`Norm` value types from
 * the column raws and reuses the exact operators, so results are bit-identical to the AoS systems.
 * Biology runs the shared `CytoBiologyCore` on per-cell `CellWork` built from the columns + CSR, with a
 * world PRNG matching `SimBuilder.nextRandomInt` for mutation. Only the **structural** phase
 * (`lifecycle`) — and `interaction` when there's pointer input — is still **bridged**: a minimal
 * `SimState` is materialized, the unmodified AoS system runs, and the world is rebuilt from its output
 * (survivors' in-flight impulse restored). Events cross the bridge by extraction + injection (weld from
 * contacts, divide/destroy from biology, Delete-tap destroys from interaction).
 *
 * The sole cyto reducer (the AoS `CytoReducer` oracle was retired once SoA landed). Behaviour is
 * frozen as committed golden trajectories (`CytoGoldenTest`) with invariants in `CytoSoaSpecTest`.
 */
class CytoSoaReducer(
    private val cfg: CytoConfig,
    private val executor: ParallelExecutor? = null,
    private val profiler: PipelineProfiler? = null,
    // Fine-grained accumulator for the two biology hot phases (off in production). NOT thread-safe, so it
    // requires the single-threaded biology path (bioParallelThreshold left OFF). Reset/printed by the bench.
    private val bioProfile: BioProfile? = null,
    // Slot count above which the spring gather fans across [executor]; below it runs sequentially.
    // Default 2048 from the profileCytoGrowth crossover: at ~1.4k cells the fan-out's wakeup/barrier
    // cost and cache/bandwidth interference with the (single-threaded) biology/contacts phases still
    // outweigh the forces win — a net loss — while by ~2.7k cells forces is ~2.1× and the tick ~1.25×
    // faster. The game's normal carrying capacity (≤~500) thus stays sequential with zero overhead.
    // Tests force the parallel path at small N by lowering this.
    private val springParallelThreshold: Int = 2048,
    // Cell count above which the biology gene phase fans grid-cell groups across [executor]. The grouping is
    // bit-identical to the sequential pass (each grid-cell touches only its own reservoir cell, so groups are
    // independent; parallelMatchesSequential gates it), so this is purely a perf knob.
    //
    // DEFAULTED OFF (Int.MAX_VALUE). Profiling (CytoBench A/B, up to ~5.5k cells) found the fan-out a net
    // LOSS on a high-single-core-turbo desktop CPU: every phase — including untouched single-threaded ones,
    // and even the existing parallel spring solver — slows ~1.5× under the fan-out, because holding 8 cores
    // busy every tick pins the CPU at its all-core clock (~1.5× below single-core turbo). The partial
    // coverage (only the genes sub-phase is parallel) + per-tick invokeAll overhead can't offset that. The
    // scaffold is kept (bit-identical, tested) for flat-all-core-clock targets (servers); lower this there to
    // enable it. See demos/cyto/PERF.md.
    private val bioParallelThreshold: Int = Int.MAX_VALUE,
) {
    private val player = mapOf(PlayerId(0) to CytoInput.EMPTY)

    /** EntityId.value of a cell exempt from natural mutation (the focused/inspected cell), or -1 for none.
     *  Set by the controller from the UI thread, read on the sim thread → `@Volatile`. Exempting a cell
     *  skips its mutation PRNG draws, shifting the sequence — fine for live play; the goldens run with -1
     *  (no focus) so they're unaffected. */
    @Volatile
    var noMutateEntityId = -1

    // weld intents produced by the in-place contacts phase, drained by the lifecycle bridge.
    private val weldLo = ArrayList<Int>()
    private val weldHi = ArrayList<Int>()

    // Repair-weld intents (canonical pairKey → summed birth-heal), collected from the biology Repair action,
    // drained by the lifecycle bridge. A pair may get heal from both touching cells, so it's summed; the
    // count tracks how many of the two cells requested it (a weld forms only at 2 — Option 2, both repairing).
    private val weldHealByPair = HashMap<Long, Float>()
    private val weldHealCount = HashMap<Long, Int>()

    // Reused scratch for the connections phase: one shared damage per undirected connection (pairKey → the
    // best-healed of its two stored edge values), so a connection is a single stress value, not two.
    private val connPairDmg = HashMap<Long, Float>()

    // CellDestroyIntents from Delete taps: emitted by CytoInteractionSystem in the interaction bridge,
    // whose build() discards events — so we extract them here and marshal them into the lifecycle bridge
    // (which consumes them), exactly as the AoS pipeline carries the event across phases.
    private val interactDestroy = ArrayList<Int>()

    // Broadphase grid, reused across ticks: cleared + re-inserted when the dimensions (cell size from
    // maxRadius, cells-per-axis from population) are unchanged, rebuilt only when they shift — so a
    // steady-state colony pays no per-tick grid allocation (was a fresh ~64K-ref array + per-cell IntList).
    private var contactGrid: SpatialGrid? = null

    // reused per-cell scratch for exposure (neighbour diamond-angles); biology is single-threaded.
    private val expoScratch = LongArray(CytoExposure.MAX_NEIGHBOURS)

    // Per-cell count of un-connected cells touched this tick (the Touching gene gate). Filled by the
    // sequential contacts phase, read by biology; transient (slot indices are stable between the two,
    // since membership only changes in the later lifecycle phase). Grown on demand, zeroed each tick.
    private var touchScratch = IntArray(0)

    // Per-cell list of un-welded cells touched this tick (their entity-id values), filled alongside
    // touchScratch by the contacts phase and read by the Repair action to form adhesion welds. Pooled.
    private var touchingScratch = Array(0) { ArrayList<Int>() }

    // Spring-solve working set as raw Frac longs (x/y split), reused across ticks and grown on demand,
    // so the per-body Jacobi gather allocates nothing per tick (no boxed Frac2 working arrays, no
    // per-edge Frac2/Norm temporaries). p0 = start positions (velocity normals + pNet reference);
    // p = working positions (moved by the position pass); bv = base (motion) velocity; v = working
    // velocity (base + accumulated impulse); d = per-iteration per-body accumulated delta.
    private var ssCap = 0
    private var ssP0x = LongArray(0); private var ssP0y = LongArray(0)
    private var ssPx = LongArray(0); private var ssPy = LongArray(0)
    private var ssBvx = LongArray(0); private var ssBvy = LongArray(0)
    private var ssVx = LongArray(0); private var ssVy = LongArray(0)
    private var ssMass = LongArray(0)
    private var ssDx = LongArray(0); private var ssDy = LongArray(0)
    // Per-EDGE precompute (indexed by CSR edge slot): velocity-solve normals (from frozen p0) + weight
    // (from masses). Both are constant across the 4 velocity iterations, and the weight is also reused by
    // the position solve — so they're computed once per tick instead of per-iteration per-edge.
    private var ssEdgeCap = 0
    private var ssEnX = LongArray(0); private var ssEnY = LongArray(0); private var ssEw = LongArray(0)

    private fun ensureSpringScratch(n: Int) {
        if (ssCap >= n) return
        ssP0x = LongArray(n); ssP0y = LongArray(n)
        ssPx = LongArray(n); ssPy = LongArray(n)
        ssBvx = LongArray(n); ssBvy = LongArray(n)
        ssVx = LongArray(n); ssVy = LongArray(n)
        ssMass = LongArray(n)
        ssDx = LongArray(n); ssDy = LongArray(n)
        ssCap = n
    }

    private fun ensureEdgeScratch(e: Int) {
        if (ssEdgeCap >= e) return
        ssEnX = LongArray(e); ssEnY = LongArray(e); ssEw = LongArray(e)
        ssEdgeCap = e
    }

    // Biology working set, pooled by slot + reused across ticks, so a tick allocates no per-cell CellWork /
    // connection-damage map / neighbour list and no per-tick light scratch (the old materialization).
    private var bioCap = 0
    private var bioWorks = arrayOfNulls<CellWork>(0)              // one pooled CellWork per slot, reset()/tick
    private var bioNbrs = arrayOfNulls<ArrayList<EntityId>>(0)   // one pooled neighbour list per slot
    private var bioBaseQuanta = LongArray(0)
    private var bioCapture = LongArray(0)
    private val bioWorksMap = LinkedHashMap<EntityId, CellWork>()   // reused (cleared each tick)
    private val bioNeighbourIds = HashMap<EntityId, List<EntityId>>()
    // Dividing cell → the morphogen its fired Mitosis gene named (asymmetric mitosis); populated in the
    // biology finish loop, read when emitting CellDivisionIntent in bridgeLifecycle. Reused (cleared each tick).
    private val divideMorphogen = HashMap<EntityId, String>()
    // Dividing cells whose Mitosis gene keeps the morphogen in the MOTHER (centred source) — else daughter.
    private val divideMorphogenToMother = HashSet<EntityId>()
    // Oriented division: the axis-morphogen (if any) the split orients to, and which cells split ACROSS it.
    private val divideAxis = HashMap<EntityId, String>()
    private val divideAcross = HashSet<EntityId>()
    private val bioCapSum = HashMap<Int, Long>()
    private val bioOrderedWorks = ArrayList<CellWork>()
    // Ascending-EntityId slot order (Import draw order + mutation PRNG order), as a reusable IntArray —
    // replaces a per-tick `(0 until n).sortedBy { entityId }`, which boxed n Integers + a List every tick.
    // Sorted via a packed (entityId<<32 | slot) LongArray so the primitive sort needs no boxing/comparator;
    // entityIds are unique so the slot tiebreak never differs from the old stable sort.
    private var bioOrder = IntArray(0)
    private var bioOrderPacked = LongArray(0)
    // Grid-cell grouping for the parallel gene phase, rebuilt each tick by a counting-sort over gridIndex
    // (bucket RES²+1 collects the position-less gridIndex<0 cells). bioGroupSlots holds the slots grouped by
    // grid-cell (EntityId order within each group); bioGroupBounds[0..numGroups] delimit the non-empty
    // groups (contiguous ranges into bioGroupSlots), so disjoint() can hand each worker whole groups.
    private var bioBucketCount = IntArray(0)
    private var bioBucketCursor = IntArray(0)
    private var bioGroupSlots = IntArray(0)
    private var bioGroupBounds = IntArray(0)

    private fun ensureBioScratch(n: Int) {
        if (bioCap >= n) return
        val nb = arrayOfNulls<CellWork>(n)
        val nn = arrayOfNulls<ArrayList<EntityId>>(n)
        for (i in 0 until bioCap) { nb[i] = bioWorks[i]; nn[i] = bioNbrs[i] }   // keep pooled objects (handleable cache)
        for (i in bioCap until n) {
            nb[i] = CellWork(MoleculeStore(), MoleculeStore(), MIN_RADIUS, CellType.Blank, emptyList(), 0, 0, 0, -1, HashMap())
            nn[i] = ArrayList()
        }
        bioWorks = nb; bioNbrs = nn
        bioBaseQuanta = LongArray(n); bioCapture = LongArray(n)
        bioCap = n
    }

    fun tick(w: CytoWorld, input: CytoInput = CytoInput.EMPTY): CytoWorld {
        w.world.tick += 1   // advance the deterministic sim clock (drives the moving light; survives the
                            // bridges via toSimState/fromSimState, like randomSeed, and the save codec)
        val inputs = if (input === CytoInput.EMPTY) player else mapOf(PlayerId(0) to input)
        interactDestroy.clear()
        var cur = w
        // interact: interaction (only when there's pointer input) then matter diffusion.
        cur = phaseR("interact") {
            if (input.spawns.isNotEmpty() || input.taps.isNotEmpty()) cur = bridgeInteraction(cur, inputs)
            // Matter diffusion walks every grid-cell, so run it only every Nth tick (it's a slow background
            // process — per-tick resolution is wasted work, especially in a near-uniform field). Deterministic
            // on the sim clock; conservation unaffected (each step is still a conservative move).
            // Quad-tree self-upkeep (QUADTREE.md maintain): progressive collapse of unobserved regions +
            // species decay. Runs every MATTER_DIFFUSE_PERIOD ticks, mutating the field in place (walks only
            // allocated nodes — the void is ~free). collapseDelay is in raw ticks (matches leaf lastAccessTick).
            if (cur.world.tick % CytoTuning.MATTER_DIFFUSE_PERIOD == 0L) {
                cur.grid.maintain(cur.world.tick.toInt(), CytoTuning.MATTER_COLLAPSE_DELAY, CytoTuning.MATTER_DECAY_PERIOD)
            }
            cur
        }
        phase("reset") { reset(cur) }
        phase("contacts") { contacts(cur) }
        val (divide, destroy) = phaseR("biology") { biology(cur) }
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

    // Biology sub-phase splitter (throwaway profiling aid): records the elapsed since the previous split
    // under [name], so the biology() internals (build/exchange/genes/diffuse/finish/writeback) each get
    // their own row. The bio:* rows sum to the "biology" umbrella phase (which is still recorded), so the
    // per-phase share% column is diluted for them — read the absolute avg-us column. Off in production
    // (profiler == null): one null-check per call, nothing recorded, bit-identical.
    private var bioMark = TimeSource.Monotonic.markNow()
    private fun bioSplit(name: String) {
        val p = profiler ?: return
        p.recordPhase(name, bioMark.elapsedNow().inWholeNanoseconds)
        bioMark = TimeSource.Monotonic.markNow()
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
        if (touchScratch.size < n) touchScratch = IntArray(n) else touchScratch.fill(0, 0, n)
        if (touchingScratch.size < n) touchingScratch = Array(n) { ArrayList() } else for (i in 0 until n) touchingScratch[i].clear()
        if (n < 2) return
        var maxRadius = 0L
        for (i in 0 until n) if (w.radiusRaw[i] > maxRadius) maxRadius = w.radiusRaw[i]
        if (maxRadius <= 0L) return
        val dims = SpatialGrid.packedDimsFor(
            minCellSize = maxRadius * 2L,
            maxCellsPerAxisLog2 = SpatialGrid.cellsPerAxisLog2For(n),
        )
        if (dims < 0L) return
        val cached = contactGrid
        val grid = if (cached != null && cached.packedDims == dims) {
            cached.clearForReuse(); cached
        } else {
            SpatialGrid.ofPackedDims(dims).also { contactGrid = it }
        }
        for (i in 0 until n) grid.insert(i, w.posX[i], w.posY[i])

        // Sequential single pass in (i asc, j asc) order — matches ContactSystem's emitted list order
        // and CytoContactSystem's processing order. Repulsion impulse is additive (order-free anyway).
        // The cheap AABB overlap test (|dx|,|dy| < radius sum) runs INSIDE the neighbour gather, so only
        // genuinely-near pairs reach the scratch list and the sort. This matters because a single large
        // (e.g. hoarding) cell forces a coarse grid — `cellSize ≥ 2·maxRadius` for correctness — so every
        // cell's 3×3 window holds dozens of far candidates; box-filtering before the O(cc²) insertion sort
        // (rather than gathering all then sorting then filtering) cuts the sorted set from ~window-occupancy
        // to the handful actually overlapping. Bit-identical: filter-then-stable-sort yields the same
        // surviving pairs in the same j-ascending order as sort-then-filter.
        var scratch = IntArray(16)
        for (i in 0 until n) {
            val aX = w.posX[i]; val aY = w.posY[i]; val aR = w.radiusRaw[i]
            var cc = 0
            grid.forEachNeighbour(aX, aY) { j ->
                if (j > i) {
                    val sum = aR + w.radiusRaw[j]
                    val dx = longAbs((aX - w.posX[j]).toLong())
                    val dy = longAbs((aY - w.posY[j]).toLong())
                    if (dx < sum && dy < sum) {
                        if (cc >= scratch.size) scratch = scratch.copyOf(scratch.size * 2)
                        scratch[cc] = j; cc += 1
                    }
                }
            }
            insertionSort(scratch, cc)
            for (k in 0 until cc) {
                val j = scratch[k]
                // Spring-connected pairs produce no contact effect (CytoContactSystem skips them), and in a
                // welded colony most overlapping pairs are connected — so skip them BEFORE the costly
                // Contact.compute. Both directions, matching springExists(a,b) || springExists(b,a).
                if (edgeExists(w, i, w.entityId[j]) || edgeExists(w, j, w.entityId[i])) continue
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
        // (connected pairs are already excluded before Contact.compute in the caller)
        val sticky = w.cell.sticky[i] || w.cell.stickyTemp[i] || w.cell.sticky[j] || w.cell.stickyTemp[j]
        val close = contact.penetration.raw * 4L > contact.minDist.raw
        if (sticky || close) {
            val ai = w.entityId[i]; val bi = w.entityId[j]
            if (ai < bi) { weldLo.add(ai); weldHi.add(bi) } else { weldLo.add(bi); weldHi.add(ai) }
            return
        }
        // A real (un-welded) collision — both cells register a touch this tick (the Touching gate) and
        // record each other as an adhesion candidate (a firing Repair gene welds them — gene-driven sticky).
        touchScratch[i]++; touchScratch[j]++
        touchingScratch[i].add(w.entityId[j]); touchingScratch[j].add(w.entityId[i])
        val massA = w.mass[i].toUInt(); val massB = w.mass[j].toUInt()
        val total = (massA + massB).toLong()
        if (total <= 0L) return
        val weightA = Frac(massB.toLong(), total.toInt())
        val weightB = Frac(massA.toLong(), total.toInt())
        val normal = contact.normal
        // Repulsion pushes the pair apart proportional to penetration; the damping term removes a fraction of
        // their relative NORMAL velocity, making the collision inelastic. Net per-axis impulse for cell i is
        // normal * ((push - damping*vn) * weightA): the push injects separation speed (overlap → kinetic),
        // the damping bleeds it off, so a continuously-overlapping cluster settles at a finite speed instead
        // of pumping unbounded velocity (the rapid-division explosion). vn>0 means already separating.
        //
        // The relative velocity is read LIVE — base velocity plus the impulse already accumulated this pass —
        // so the contact solve is Gauss–Seidel: a later contact on the same cell sees the velocity earlier
        // contacts already damped, and the corrections converge. Reading a FROZEN velocity instead lets every
        // contact remove the same vn, so in a densely-overlapping cell the dampings stack past the real
        // relative velocity, reverse it and amplify (explicit over-damping) — which itself explodes. Live
        // reads make the pass order-dependent, but contacts run in a fixed (i asc, j asc) order, so it's
        // deterministic; the push half stays purely additive/positional as before.
        val vn = Frac2(
            Frac((w.velX[i].toLong() + w.impVelX[i]) - (w.velX[j].toLong() + w.impVelX[j])),
            Frac((w.velY[i].toLong() + w.impVelY[i]) - (w.velY[j].toLong() + w.impVelY[j])),
        ).dot(normal)                                            // relative normal velocity (+ = separating)
        val effective = contact.penetration * cfg.repulsion - vn * cfg.contactDamping
        val impA = normal * (effective * weightA)
        val impB = -(normal * (effective * weightB))
        w.impVelX[i] += impA.x.raw; w.impVelY[i] += impA.y.raw
        w.impVelX[j] += impB.x.raw; w.impVelY[j] += impB.y.raw
    }

    // ── connections (CytoConnectionMaintenanceSystem) ───────────────────────────
    // Refresh rest lengths, accrue stress damage into edgeAux, break over-stressed springs.
    private fun connections(w: CytoWorld) {
        // A connection has ONE shared damage, not two per-direction values. Pass 1: per undirected pair, take
        // the best-healed of its two stored edge values, so EITHER endpoint's Repair maintains the link.
        val pairDmg = connPairDmg.also { it.clear() }
        for (i in 0 until w.count) {
            for (k in w.csr.offset[i] until w.csr.offset[i + 1]) {
                if (w.csr.otherSlot[k] < 0) continue
                val key = pairKey(w.entityId[i], w.csr.otherId[k])
                val cur = w.csr.edgeAux[k]
                val prev = pairDmg[key]
                if (prev == null || cur < prev) pairDmg[key] = cur
            }
        }
        // Pass 2: add this tick's stress to the shared value and write it back to BOTH directed edges (so they
        // stay in sync). Stress is symmetric and uses the better-connected endpoint's maintenance bonus
        // (1/(degree+1)), so a well-knit body is cheap to hold together (matches degrade()).
        // Collinearity scan cadence (amortizes the through-cell check; damage is ×period to keep break rate
        // cadence-independent — see WELD_COLLINEAR_CHECK_PERIOD).
        val collinearPeriod = cfg.weldCollinearCheckPeriod.coerceAtLeast(1)
        val scanCollinear = collinearPeriod == 1 || w.world.tick % collinearPeriod == 0L
        val broken = HashSet<Long>()
        for (i in 0 until w.count) {
            val radiusA = w.radiusRaw[i]
            for (k in w.csr.offset[i] until w.csr.offset[i + 1]) {
                val nSlot = w.csr.otherSlot[k]
                if (nSlot < 0) continue
                val rest = radiusA + w.radiusRaw[nSlot]
                val restLogical = CytoUnits.toLogical(Frac(rest))
                val dist = deltaLen(w, i, nSlot)
                val stretch = CytoUnits.toLogical(dist) - restLogical
                val deg = maxOf(w.csr.degreeOf(i), w.csr.degreeOf(nSlot))
                // Tension (pulled past rest): maintenance bonus 1/2^deg (better-connected endpoint) — a
                // well-knit body's internal connections are nearly free to HOLD TOGETHER, matching degrade().
                val tension = max(0f, stretch * cfg.connectionStressScale) / (1 shl deg.coerceAtMost(20))
                // Over-stretch: a NON-LINEAR ramp, (stretch/breakDist)^exponent × CONNECTION_BREAK_DAMAGE,
                // reaching a full break-in-one-tick at OVERSTRETCH_BREAK_MULTIPLE × rest (~2 cell-widths of gap,
                // where the bond rendering breaks down). The exponent keeps low/moderate stretch cheap so links
                // flex and recover under normal load, while a hard yank near the break distance spikes to
                // destroy even a perfectly healthy, well-knit, actively-repaired link in ONE tick. NOT
                // degree-discounted — past the break distance the link is non-physical and gives. Integer power
                // via repeated multiply keeps it deterministic (no transcendental pow()).
                val breakDist = cfg.overStretchBreakMultiple * restLogical
                val overStretch = if (stretch > 0f && breakDist > 0f) {
                    val ratio = stretch / breakDist
                    var p = 1f
                    repeat(cfg.overStretchDamageExponent) { p *= ratio }
                    cfg.connectionBreakDamage * p
                } else 0f
                // Compression (crushed inside rest by more than the tolerance): NOT degree-discounted, so an
                // over-packed blob — a cell dividing faster than its daughters can separate — crushes its own
                // welds and sheds them, fragmenting/dispersing instead of fusing into one rigid over-
                // constrained mass the spring solver can't hold. Mild resting overlap (< tolerance) is free.
                val compression = max(0f, -stretch - cfg.compressionTolerance) * cfg.connectionStressScale
                // Through-cell chord: a weld with a common welded neighbour ~collinear between its endpoints is
                // a structural degeneracy invisible to stretch (it sits near rest when crammed). Geometric, so
                // it accrues damage from the angle alone until it breaks (leaving the real A–B/B–C welds).
                val collinear = if (scanCollinear && w.csr.degreeOf(i) >= 2 && w.csr.degreeOf(nSlot) >= 2 &&
                    throughCellChord(w, i, nSlot)) cfg.weldCollinearDamage * collinearPeriod else 0f
                val stress = tension + overStretch + compression + collinear
                val key = pairKey(w.entityId[i], w.csr.otherId[k])
                val damage = max(0f, (pairDmg[key] ?: w.csr.edgeAux[k]) + stress)
                if (damage > cfg.connectionBreakDamage) {
                    broken.add(key)
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

    // ── forces: spring solve (SpringConstraintSystem) — parallel per-body gather Jacobi, split impulse ──
    //
    // Jacobi (not Gauss–Seidel): each iteration reads frozen pos/vel and accumulates every body's
    // correction into a delta buffer applied only after the sweep. Because the CSR is symmetric (cyto
    // welds both endpoints) and the per-edge contribution is the same equal-and-opposite term whether
    // scattered from the edge or gathered from each endpoint, gathering each body's own delta from its
    // neighbours produces per-body deltas bit-identical to the AoS scatter oracle — and lets the gather
    // fan disjointly across cores (each body writes only its own slot, so no merge, no races). The
    // high-id endpoint computes `normal_ba = normFromLen(pos_a - pos_b) = -normal_ab` exactly (Frac
    // division truncates toward zero ⇒ sign-symmetric), so its half matches the AoS low-id scatter term.
    private fun springSolve(w: CytoWorld) {
        val n = w.count
        if (n == 0) return
        ensureSpringScratch(n)
        val p0x = ssP0x; val p0y = ssP0y; val px = ssPx; val py = ssPy
        val bvx = ssBvx; val bvy = ssBvy; val vx = ssVx; val vy = ssVy
        val mass = ssMass; val dx = ssDx; val dy = ssDy
        val csr = w.csr
        // Seed only spring-bearing slots; the gather/apply/emit loops all gate on degree>0, so stale
        // scratch in spring-less slots is never read. asFrac2 widens the Coord raw Int to a Frac raw Long;
        // the impulse seed folds in the grab/drag/contacts impulse already accumulated this tick.
        var anySpring = false
        for (i in 0 until n) {
            if (csr.degreeOf(i) == 0) continue
            anySpring = true
            val pxr = w.posX[i].toLong(); val pyr = w.posY[i].toLong()
            p0x[i] = pxr; p0y[i] = pyr; px[i] = pxr; py[i] = pyr
            bvx[i] = w.velX[i].toLong(); bvy[i] = w.velY[i].toLong()
            vx[i] = bvx[i] + w.impVelX[i]; vy[i] = bvy[i] + w.impVelY[i]
            mass[i] = w.mass[i].toUInt().toLong()
        }
        if (!anySpring) return

        // 0) precompute per-edge velocity normals (from the frozen start positions p0) and the mass weight
        //    — both constant across the 4 velocity iterations (and the weight is reused by the position
        //    solve), so doing them once here instead of per-iteration removes a lenRaw + 3 divisions per
        //    edge from 3 of every 4 velocity sweeps. Invalid edges (no partner / coincident / zero total
        //    mass) get a 0 normal or 0 weight, which makes their contribution exactly 0 — identical to the
        //    old `continue`. Parallel per-body (each body owns a disjoint CSR edge range). Bit-identical.
        val edges = csr.offset[n]
        ensureEdgeScratch(edges)
        val enX = ssEnX; val enY = ssEnY; val ew = ssEw
        ColumnPartition.disjoint(n, executor, springParallelThreshold) { start, end ->
            for (i in start until end) {
                if (csr.degreeOf(i) == 0) continue
                val mi = mass[i]; val p0ix = p0x[i]; val p0iy = p0y[i]
                for (k in csr.offset[i] until csr.offset[i + 1]) {
                    val j = csr.otherSlot[k]
                    if (j < 0) { enX[k] = 0L; enY[k] = 0L; ew[k] = 0L; continue }
                    val total = mi + mass[j]
                    ew[k] = if (total <= 0L) 0L else mass[j] * FRAC_MAX / total.toInt().toLong()
                    // torus-modular delta: positions are widened Coord (Int), so subtract as Int (two's-
                    // complement wrap) before widening — a weld straddling the seam reads the SHORT delta, not
                    // a ~2³² gap. Off-seam (delta fits Int) this is identical to the plain Long subtraction.
                    val ddx = (p0x[j].toInt() - p0ix.toInt()).toLong(); val ddy = (p0y[j].toInt() - p0iy.toInt()).toLong()
                    val dist = lenRaw(ddx, ddy)
                    if (dist == 0L) { enX[k] = 0L; enY[k] = 0L } else {
                        enX[k] = ddx * FRAC_MAX / dist; enY[k] = ddy * FRAC_MAX / dist   // normFromLen
                    }
                }
            }
        }

        // 1) velocity solve: cancel relative NORMAL velocity (damping only), normals from start positions.
        //    Raw-Frac arithmetic mirrors the AoS Frac2/Norm ops op-for-op (see FRAC_MAX / lenRaw notes).
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
                        val relVel = rvx * nx / FRAC_MAX + rvy * ny / FRAC_MAX           // (relVel).dot(normal)
                        val vCorr = relVel * csr.dampRaw[k] / FRAC_MAX
                        val scalar = vCorr * ew[k] / FRAC_MAX                            // vCorr * weight
                        accX += nx * scalar / FRAC_MAX; accY += ny * scalar / FRAC_MAX   // normal * scalar
                    }
                    dx[i] = accX; dy[i] = accY
                }
            }
            for (i in 0 until n) { if (csr.degreeOf(i) == 0) continue; vx[i] += dx[i]; vy[i] += dy[i] }
        }
        // 2) position solve (pseudo-velocity): move working positions toward rest length. The normal must
        //    be recomputed each iteration (working positions move), but the mass weight is the precomputed
        //    [ew]; a 0 weight (zero total mass) zeroes the contribution, as the old `continue` did.
        val compStiffMul = cfg.weldCompressionStiffnessMultiple   // compressed welds push back this much harder
        repeat(ITERATIONS) {
            ColumnPartition.disjoint(n, executor, springParallelThreshold) { start, end ->
                for (i in start until end) {
                    if (csr.degreeOf(i) == 0) continue
                    var accX = 0L; var accY = 0L
                    val pix = px[i]; val piy = py[i]
                    for (k in csr.offset[i] until csr.offset[i + 1]) {
                        val j = csr.otherSlot[k]; if (j < 0) continue
                        val ddx = (px[j].toInt() - pix.toInt()).toLong()   // torus-modular (Int wrap); d = pos[j] - pos[i]
                        val ddy = (py[j].toInt() - piy.toInt()).toLong()
                        val dist = lenRaw(ddx, ddy); if (dist == 0L) continue
                        val nx = ddx * FRAC_MAX / dist; val ny = ddy * FRAC_MAX / dist
                        val lengthError = dist - csr.restRaw[k]                          // dist - Frac(rest); <0 = compressed
                        // A compressed weld pushes back harder than a stretched one relaxes (asymmetric stiffness):
                        // stiffens the outward push that holds a squashable middle cell apart, lifting a
                        // through-cell chord's settled stretch clear of the ordinary-weld band. Stretched/at-rest
                        // path is byte-identical (multiply only when lengthError < 0).
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
        // emit: vel channel = net velocity change (incl. prior impulse); pos channel += position correction.
        for (i in 0 until n) {
            if (csr.degreeOf(i) == 0) continue
            w.impVelX[i] = vx[i] - bvx[i]; w.impVelY[i] = vy[i] - bvy[i]
            w.impPosX[i] += px[i] - p0x[i]; w.impPosY[i] += py[i] - p0y[i]
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
            // Velocity reconciliation: v = Δx/dt (dt=1). The weld solve moves cells through the POSITION
            // channel (impPos, a stable pseudo-velocity projection); discarding it left velX carrying only the
            // damping channel, so drag/contacts were blind to constraint-driven motion (no locomotion). Setting
            // velocity to the realized per-tick displacement folds that motion back in. Torus Coord subtraction
            // is modular ⇒ the small signed delta is exact across the wrap. Welds now carry inertia (they'll
            // overshoot/ring); SPRING_DAMPING's velocity solve bleeds it. (Was: w.velX = vel.x.raw — damping only.)
            w.velX[i] = pos.x.raw - transform.pos.x.raw
            w.velY[i] = pos.y.raw - transform.pos.y.raw
            w.angVel[i] = angVel.raw
        }
    }

    // ── biology (CytoBiologySystem) in place ─────────────────────────────────────
    // The shared CytoBiologyCore (passive exchange → genes → diffusion → degradation/growth/death) runs
    // verbatim on per-cell CellWork; only the orchestration — building CellWork from the columns + CSR,
    // the light/exposure quanta, mutation via the world PRNG, and the write-back (cell columns, collider
    // radius, mass + variable-mass momentum, repair damage into edgeAux) — is reimplemented over columns,
    // so no per-tick SimState materialize. Bit-identical to the AoS system (mutation-off gated). Membership
    // is unchanged (division/death are deferred to lifecycle). Returns its divide/destroy intents.
    private fun biology(w: CytoWorld): Pair<List<EntityId>, List<EntityId>> {
        val n = w.count
        if (n == 0) return emptyList<EntityId>() to emptyList()
        if (profiler != null) bioMark = TimeSource.Monotonic.markNow()
        val lightField = CytoLightField.default()
        val grid = w.grid   // post-diffusion; biology draws/deposits in place (copy-on-write)

        ensureBioScratch(n)
        if (bioOrder.size < n) { bioOrder = IntArray(n); bioOrderPacked = LongArray(n) }
        val ordered = bioOrder   // ascending-EntityId (Import order + PRNG order); valid in [0, n)
        for (i in 0 until n) bioOrderPacked[i] = (w.entityId[i].toLong() shl 32) or i.toLong()
        bioOrderPacked.sort(0, n)
        for (k in 0 until n) ordered[k] = (bioOrderPacked[k] and 0xFFFFFFFFL).toInt()
        val works = bioWorksMap.also { it.clear() }            // pooled, reused each tick
        val neighbourIds = bioNeighbourIds.also { it.clear() }
        // Light shading — interference competition for energy. Cells sharing a grid-cell split that
        // grid-cell's incident light by their light-capture weight (exposure × radius): a cell that grows —
        // taking more space and exposed surface — captures a larger share and STARVES its smaller
        // neighbours, which, unable to power Convert, decay and die, recycling their matter to the commons.
        // A cell alone in its grid-cell keeps its full light (capture share = 1), so isolated trajectories
        // are bit-unchanged. Two-pass: gather each cell's base light + capture weight, sum the weights per
        // grid-cell, then quanta = baseQuanta × (cap / Σcap). `captureMilli` is the weight in milli-units
        // (value × 1000) — a small integer that keeps the share arithmetic overflow-free and cancels
        // exactly for a lone cell (so its quanta is then bit-identical to the un-shaded value).
        val baseQuantaRaw = bioBaseQuanta   // ((sample × exposure) × SCALE).raw, per ordered position
        val captureMilli = bioCapture       // (exposure × radius) × 1000, per ordered position
        val capSumByGrid = bioCapSum.also { it.clear() }   // Σ captureMilli per grid-cell (only used when shading)
        for (k in 0 until n) {
            val slot = ordered[k]
            val id = EntityId(w.entityId[slot])
            val deg = w.csr.degreeOf(slot)
            val base = w.csr.offset[slot]
            val nbrs = bioNbrs[slot]!!.also { it.clear() }
            for (j in 0 until deg) nbrs.add(EntityId(w.csr.otherId[base + j]))
            neighbourIds[id] = nbrs

            var ek = 0
            for (j in 0 until deg) {
                if (ek >= CytoExposure.MAX_NEIGHBOURS) break
                val ns = w.csr.otherSlot[base + j]; if (ns < 0) continue
                val d = delta(w, slot, ns)
                expoScratch[ek++] = CytoExposure.diamondAngle(d.x, d.y).raw
            }
            val lx = CytoUnits.toLogical(Coord(w.posX[slot])); val ly = CytoUnits.toLogical(Coord(w.posY[slot]))
            val sample = lightField.sampleAt(lx, ly, w.world.tick)
            val exposure = CytoExposure.weight(expoScratch, ek)
            val radius = Frac(w.cell.logicalRadius[slot])
            // Light vs surface exposure (CytoTuning.LIGHT_IGNORES_EXPOSURE): when true (default), light is
            // independent of exposure — a buried interior cell still photosynthesises (3rd-dimension model) so
            // it can pull its own weight; when false, light scales by exposure so a surrounded cell is shaded
            // (the original behaviour). Exposure still governs env-exchange + the optional co-located shading.
            baseQuantaRaw[k] = if (CytoTuning.LIGHT_IGNORES_EXPOSURE) (sample * CytoTuning.LIGHT_QUANTA_SCALE).raw
                else ((sample * exposure) * CytoTuning.LIGHT_QUANTA_SCALE).raw
            // capture = exposure × radius, in milli-units. NOT `(exposure * radius)` — a big cell's radius
            // exceeds Frac's safe ±2 value range, so that Frac×Frac overflows Long (negative capture →
            // starves the founder). Reduce exposure to ≤1000 first, then scale by radius.raw.
            val exposureMilli = exposure.raw * 1000L / Int.MAX_VALUE.toLong()   // exposure × 1000, ≤ 1000
            captureMilli[k] = exposureMilli * radius.raw / Int.MAX_VALUE.toLong()

            val work = bioWorks[slot]!!
            work.reset(
                cytoplasm = (w.cell.cytoplasm[slot] ?: MoleculeStore()).copy(),
                biomass = (w.cell.biomass[slot] ?: MoleculeStore()).copy(),
                logicalRadius = radius,
                type = CellType.entries[w.cell.type[slot]],
                genome = w.cell.genome[slot] ?: emptyList(),
                quanta = 0,   // filled below, once per-grid-cell capture sums are known
                touchCount = touchScratch[slot],
                wear = w.cell.wear[slot],
                gridIndex = -1,   // vestigial (no flat grid); the cell uses cx,cy for its footprint
                weldedDegree = deg,
            )
            for (j in 0 until deg) work.connectionDamage[EntityId(w.csr.otherId[base + j])] = w.csr.edgeAux[base + j]
            for (tid in touchingScratch[slot]) work.touchingIds.add(EntityId(tid))
            work.exposureMilli = exposureMilli.toInt()   // surface exposure (0..1000), damps passive env-exchange
            work.cx = lx; work.cy = ly                    // disc-gather footprint centre (logical position)
            works[id] = work
        }
        bioSplit("bio:build")   // per-cell CellWork build: light sample, exposure, cytoplasm/biomass copy, reset
        // Second pass: turn each cell's base light into quanta. With [CytoTuning.LIGHT_SHADING] on, cells
        // sharing a grid-cell split it by capture share (cap / Σcap); the division order (cap/Σcap before
        // /MAX) keeps full integer precision and, for a lone cell where Σcap == cap, reduces to the same
        // un-shaded value. With shading off, every cell simply gets its own full light (no co-located
        // split) — a toggle to A/B whether shading still matters now the day/night cycle drives selection.
        for (k in 0 until n) {
            val slot = ordered[k]
            val work = works.getValue(EntityId(w.entityId[slot]))
            work.quanta = if (!CytoTuning.LIGHT_SHADING) {
                (baseQuantaRaw[k] / Int.MAX_VALUE.toLong()).toInt()
            } else {
                val capSum = if (work.gridIndex >= 0) capSumByGrid[work.gridIndex] ?: 0L else captureMilli[k]
                if (capSum <= 0L) 0 else (baseQuantaRaw[k] * captureMilli[k] / capSum / Int.MAX_VALUE.toLong()).toInt()
            }
        }
        bioSplit("bio:quanta")   // light-shading second pass (per-grid-cell capture-share split)

        val orderedWorks = bioOrderedWorks.also { it.clear() }
        for (k in 0 until n) orderedWorks.add(bioWorks[ordered[k]]!!)
        CytoBiologyCore.passiveEnvExchange(orderedWorks, grid, w.world.tick.toInt(), bioProfile)
        bioSplit("bio:exchange")   // passive env-exchange (the diffusion junction) with the quad-tree field
        // Gene phase. runGenes is now grid-FREE (Import is a junction bias, degrade ejects to cytoplasm), so
        // each cell is fully independent — any partition is bit-identical to sequential. Fan cells (in
        // bioOrder = EntityId order) across disjoint slot ranges; no grid-cell grouping needed any more.
        val exec = if (n >= bioParallelThreshold) executor else null
        ColumnPartition.disjoint(n, exec, threshold = 1) { kStart, kEnd ->
            for (k in kStart until kEnd) CytoBiologyCore.runGenes(bioWorks[ordered[k]]!!, bioProfile)
        }
        bioSplit("bio:genes")   // gene execution
        CytoBiologyCore.diffuse(works, neighbourIds)
        bioSplit("bio:diffuse")   // inter-cell cytoplasm diffusion across welds
        val divide = ArrayList<EntityId>(); val destroy = ArrayList<EntityId>()
        divideMorphogen.clear()
        divideMorphogenToMother.clear()
        divideAxis.clear()
        divideAcross.clear()
        weldHealByPair.clear()
        weldHealCount.clear()
        for (k in 0 until n) {
            val slot = ordered[k]
            val id = EntityId(w.entityId[slot])
            val work = bioWorks[slot]!!
            CytoBiologyCore.finish(id, work, divide, destroy)
            for ((other, heal) in work.weldHeals) {                 // Repair-weld requests → summed + counted per pair
                val key = pairKey(id.value, other.value)
                weldHealByPair[key] = (weldHealByPair[key] ?: 0f) + heal
                weldHealCount[key] = (weldHealCount[key] ?: 0) + 1
            }
            if (work.dividing) {
                if (work.divideMorphogen.isNotEmpty()) {
                    divideMorphogen[id] = work.divideMorphogen
                    if (work.divideMorphogenToMother) divideMorphogenToMother.add(id)
                }
                if (work.divideAxisMorphogen.isNotEmpty()) {
                    divideAxis[id] = work.divideAxisMorphogen
                    if (work.divideAcross) divideAcross.add(id)
                }
            }
        }
        bioSplit("bio:finish")   // CytoBiologyCore.finish per cell (degrade/grow/death intents, weld-heal collect)

        for (k in 0 until n) {
            val slot = ordered[k]
            val id = EntityId(w.entityId[slot])
            val work = bioWorks[slot]!!
            val mutated = if (w.entityId[slot] == noMutateEntityId) null   // focused cell: frozen against mutation
                // Rate from the world if explicitly set (in-game control / save), else the cfg default (-1).
                else CytoMutation.mutate(w.cell.genome[slot] ?: emptyList(), if (w.mutationRateDenom >= 0) w.mutationRateDenom else cfg.mutationRateDenom) { until -> nextRandomInt(w, until) }
            val oldRadiusRaw = w.cell.logicalRadius[slot]
            w.cell.cytoplasm[slot] = work.cytoplasm
            w.cell.biomass[slot] = work.biomass
            w.cell.logicalRadius[slot] = work.logicalRadius.raw
            w.cell.wear[slot] = work.wear
            w.cell.stickyTemp[slot] = false
            if (mutated != null) w.cell.genome[slot] = mutated
            if (work.repaired) {
                for (k in w.csr.offset[slot] until w.csr.offset[slot + 1]) {
                    w.csr.edgeAux[k] = work.connectionDamage[EntityId(w.csr.otherId[k])] ?: 0f
                }
            }
            if (work.logicalRadius.raw != oldRadiusRaw) {
                // Collision radius is capped (MAX_COLLISION_RADIUS) so a metabolically-huge cell keeps a
                // bounded physical footprint — it can't coarsen the broadphase or weld to the whole colony.
                w.radiusRaw[slot] = CytoUnits.len(work.logicalRadius.coerceAtMost(CytoTuning.MAX_COLLISION_RADIUS).toFloat()).raw
            }
            val newMass = cellMass(work.cytoplasm, work.biomass)
            val oldMass = w.mass[slot].toUInt()
            if (newMass != oldMass) {
                w.mass[slot] = newMass.toInt()
                if (cfg.variableMass && (w.velX[slot] != 0 || w.velY[slot] != 0)) {
                    w.velX[slot] = (w.velX[slot].toLong() * oldMass.toLong() / newMass.toLong()).toInt()
                    w.velY[slot] = (w.velY[slot].toLong() * oldMass.toLong() / newMass.toLong()).toInt()
                }
            }
        }
        bioSplit("bio:writeback")   // mutation (CytoMutation.mutate) + write columns/radius/mass back
        return divide to destroy
    }

    /** World PRNG, bit-identical to `SimBuilder.nextRandomInt` (mutation draws, in EntityId order). */
    private fun nextRandomInt(w: CytoWorld): Int {
        w.world.randomSeed = w.world.randomSeed * 2862933555777941757L + 3037000493L
        return (w.world.randomSeed ushr 32).toInt()
    }
    private fun nextRandomInt(w: CytoWorld, until: Int): Int {
        require(until > 0)
        return (nextRandomInt(w).toLong() and 0x7FFFFFFFL).toInt() % until
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
        // Option 2 — a Repair-weld forms only when BOTH touching cells requested it this tick (both repairing
        // in phase): a body's clock-synchronised cells weld each other, but an out-of-phase foreign cell
        // rarely lines up, so cross-organism welding is rare. (weldHealCount[key]==2 ⇒ both sides asked.)
        val readyWelds = weldHealByPair.keys.filter { weldHealCount[it] == 2 }.sorted()
        if (weldLo.isEmpty() && readyWelds.isEmpty() && divide.isEmpty() && destroy.isEmpty() && input.detaches.isEmpty() && interactDestroy.isEmpty()) return w
        val impById = HashMap<Int, ImpulseComponent>(w.count)
        for (slot in 0 until w.count) impById[w.entityId[slot]] = w.impulse.gather(slot)

        val builder = SimBuilder(w.toSimState(includeImpulse = false))
        for (id in input.detaches) builder.emit(DetachIntent(id))      // interact order
        for (idv in interactDestroy) builder.emit(CellDestroyIntent(EntityId(idv)))  // Delete taps (interact, before biology)
        for (id in destroy) builder.emit(CellDestroyIntent(id))         // biology order
        for (i in weldLo.indices) builder.emit(WeldIntent(EntityId(weldLo[i]), EntityId(weldHi[i]))) // contact order
        for (key in readyWelds) builder.emit(WeldHealIntent(EntityId((key ushr 32).toInt()), EntityId(key.toInt()), weldHealByPair.getValue(key)))  // both-repairing pairs, sorted = deterministic
        for (id in divide) builder.emit(CellDivisionIntent(id, divideMorphogen[id] ?: "", id in divideMorphogenToMother, divideAxis[id] ?: "", id in divideAcross))  // biology order
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

    /** Does the weld [i]–[nSlot] pass ~through a common welded neighbour B (a structural degeneracy)? True iff
     *  some cell B is welded to BOTH endpoints AND sits ~collinear between them — angle(i,B,nSlot) > acos(cos
     *  threshold). Squared-cosine test (no sqrt/acos) so it's deterministic: with the angle obtuse (dot<0),
     *  `cos < T` (both negative) ⇔ `dot² > T²·|Bi|²·|BnSlot|²`. Work is bounded by MAX_WELD_DEGREE per endpoint. */
    private fun throughCellChord(w: CytoWorld, i: Int, nSlot: Int): Boolean {
        val cosSq = cfg.weldCollinearCos * cfg.weldCollinearCos
        for (k2 in w.csr.offset[i] until w.csr.offset[i + 1]) {
            val b = w.csr.otherSlot[k2]
            if (b < 0 || b == nSlot) continue
            var common = false                                    // is B also welded to nSlot?
            for (k3 in w.csr.offset[nSlot] until w.csr.offset[nSlot + 1]) {
                if (w.csr.otherSlot[k3] == b) { common = true; break }
            }
            if (!common) continue
            val bix = (w.posX[i] - w.posX[b]).toFloat(); val biy = (w.posY[i] - w.posY[b]).toFloat()
            val bjx = (w.posX[nSlot] - w.posX[b]).toFloat(); val bjy = (w.posY[nSlot] - w.posY[b]).toFloat()
            val dot = bix * bjx + biy * bjy
            if (dot >= 0f) continue                               // ≤90° — B is beside, not between
            val la2 = bix * bix + biy * biy; val lb2 = bjx * bjx + bjy * bjy
            if (dot * dot > cosSq * la2 * lb2) return true        // cos < threshold ⇒ collinear through B
        }
        return false
    }

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

        // Frac fixed-point scale (= Int.MAX_VALUE as Long). The raw-long spring math mirrors the Frac/
        // Frac2/Norm operators op-for-op: Frac.div(o) = raw*MAX/o.raw, Frac.times(o) = raw*o.raw/MAX,
        // Frac(n,d) = n*MAX/d — same grouping, same truncate-toward-zero division, so bit-identical to
        // the AoS oracle (gated by CytoSoaEquivalenceTest, incl. the forced-parallel case).
        private const val FRAC_MAX = 2147483647L  // Int.MAX_VALUE

        // Exact replica of Frac2.len(x,y) on raw longs (no Frac2 allocation): raw-space integer hypot,
        // with the same value-space sqrt fallback above |raw| = Int.MAX (where ax²+ay² would overflow).
        private val SQRT_MAX_INT: Long = longISqrt(FRAC_MAX)
        private fun lenRaw(xr: Long, yr: Long): Long {
            val ax = if (xr < 0L) -xr else xr
            val ay = if (yr < 0L) -yr else yr
            if (ax == 0L) return ay
            if (ay == 0L) return ax
            return if (ax <= FRAC_MAX && ay <= FRAC_MAX) longISqrt(ax * ax + ay * ay)
            else longISqrt(ax * ax / FRAC_MAX + ay * ay / FRAC_MAX, 2L, ax + ay) * SQRT_MAX_INT
        }

        // Integer sqrt, identical to Frac2.longISqrt (same fast double-seeded exact floor + [min,max]
        // clamp), so lenRaw matches Frac2.len. See Frac2.longISqrt for the equivalence/determinism note.
        private fun longISqrt(n: Long, min: Long = 2L, max: Long = 2L * FRAC_MAX): Long {
            if (n < 2) return n
            var x = kotlin.math.sqrt(n.toDouble()).toLong()
            if (x < 1L) x = 1L
            while (x > n / x) x--
            while (x + 1L <= n / (x + 1L)) x++
            return if (x < min) min else if (x > max) max else x
        }
    }
}
