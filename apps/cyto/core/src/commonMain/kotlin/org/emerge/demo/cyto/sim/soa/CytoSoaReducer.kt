package org.emerge.demo.cyto.sim.soa

import kotlin.concurrent.Volatile
import org.emerge.demo.cyto.sim.CellWork
import org.emerge.demo.cyto.sim.MoleculeStore
import org.emerge.demo.cyto.sim.BioProfile
import org.emerge.demo.cyto.sim.CytoBiologyCore
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.SpeciesRegistry
import org.emerge.demo.cyto.sim.cellMass
import org.emerge.demo.cyto.sim.atomCount
import org.emerge.demo.cyto.sim.totalBiomass
import org.emerge.demo.cyto.sim.systems.CellDestroyIntent
import org.emerge.demo.cyto.sim.systems.CytoInteractionSystem
import org.emerge.demo.cyto.sim.systems.LyseAttackIntent
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.ecs.soa.runSoa
import org.emerge.sim.core.ecs.soa.SoaPhase
import org.emerge.sim.core.physics.components.ColliderComponent

import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.contacts
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.time.TimeSource

/**
 * Struct-of-arrays cyto tick on the persistent [CytoWorld] — approach (B) of SOA_LANDING_PLAN.md.
 *
 * Most phases (diffusion, reset, contacts, **biology**, connections, forces = grab+drag+spring, integrate)
 * run **in place on the columns** — math reconstructs the engine `Frac`/`Coord2`/`Norm` value types from
 * the column raws and reuses the exact operators, so results are bit-identical to the AoS systems.
 * Biology runs the shared `CytoBiologyCore` on per-cell `CellWork` built from the columns + CSR, with a
 * world PRNG matching `SimBuilder.nextRandomInt` for mutation. The **structural** phase (`lifecycle` —
 * detach/destroy/weld/weld-heal/division) is also SoA-native ([applyLifecycle], editing the columns + CSR
 * in place via an entity-id-keyed adjacency snapshot). Only `interaction` (when there's pointer input) is
 * still **bridged**: a minimal `SimState` is materialized, the unmodified AoS interaction system runs, and
 * the world is rebuilt from its output. Events reach lifecycle via `state` (weld from contacts,
 * divide/destroy from biology, Delete-tap destroys from interaction).
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
    // Set high enough that the game's normal population stays sequential with zero overhead.
    // Tests force the parallel path at small N by lowering this.
    private val springParallelThreshold: Int = 2048,
    // Cell count above which the biology gene phase fans grid-cell groups across [executor]. The grouping is
    // bit-identical to the sequential pass (each grid-cell touches only its own reservoir cell, so groups are
    // independent; parallelMatchesSequential gates it), so this is purely a perf knob.
    //
    // DEFAULTED OFF — profiling found the fan-out a net LOSS on typical desktop hardware because holding
    // all cores busy pins the CPU at its all-core clock, offsetting the parallelism benefit. The scaffold
    // is kept (bit-identical, tested) for flat-all-core-clock targets (servers); lower this there to
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

    /** Shared mutable scratch for the SoA pipeline hot phases. All fields are pre-grown (pooled) and
     *  cleared/reset each tick so that the hot phases allocate zero GC pressure. */
    private val state = CytoPipelineState()

    /** CellDestroyIntents from Delete taps: emitted by CytoInteractionSystem in the interaction bridge,
     *  whose build() discards events — so we extract them here and marshal them into the lifecycle bridge. */
    private val interactDestroy = ArrayList<Int>()

    /** The hot pipeline split at the biology boundary. Biology runs between contacts and connections. */
    private val parts = CytoHotPipeline(
        state = state,
        executor = executor,
        springParallelThreshold = springParallelThreshold,
        bioParallelThreshold = bioParallelThreshold,
        profiler = profiler,
        noMutateEntityIdProvider = { noMutateEntityId },
        bioProfile = bioProfile,
    )

    fun tick(w: CytoWorld, input: CytoInput = CytoInput.EMPTY): CytoWorld {
        w.world.tick += 1   // advance the deterministic sim clock (drives the moving light; survives the
                            // bridges via toSimState/fromSimState, like randomSeed, and the save codec)
        val inputs = if (input === CytoInput.EMPTY) player else mapOf(PlayerId(0) to input)
        interactDestroy.clear()
        var cur = w
        // interact: interaction (only when there's pointer input) then matter diffusion.
        cur = phaseR("interact") {
            if (input.spawns.isNotEmpty() || input.taps.isNotEmpty()) cur = bridgeInteraction(cur, inputs)
            // Matter field upkeep: species decay, then diffusion. Both are slow background processes, so
            // this runs every MATTER_MAINTAIN_PERIOD ticks rather than per-tick, mutating the field in
            // place. Deterministic on the sim clock; conservation unaffected.
            if (cur.world.tick % CytoTuning.MATTER_MAINTAIN_PERIOD == 0L) {
                cur.grid.maintain(
                    CytoTuning.MATTER_DECAY_PERIOD,
                    CytoTuning.MATTER_DIFFUSE_DEN,
                    cur.world.tick / CytoTuning.MATTER_MAINTAIN_PERIOD,
                )
            }
            cur
        }
        // ── Hot path: SoA pipeline ───────────────────────────────────────────────────
        // Biology runs between contacts and connections (it needs to communicate
        // divide/destroy to the lifecycle bridge, and connections needs biology's weld-heal data).
        // We create phases around the system lists so profiler timing works correctly.
        val preBioPhase = listOf(SoaPhase("reset+contacts", parts.preBio))
        phase("reset+contacts") { runSoa(cfg, cur, inputs, preBioPhase, profiler) }
        phaseR("biology") { parts.bioUpdate(cfg, cur, inputs); state.divide to state.destroy }
        // Profile each post-bio system separately
        for ((idx, sys) in parts.postBio.withIndex()) {
            val name = if (idx == parts.postBio.size - 1) "forces+integrate" else "force:${sys::class.simpleName}"
            phase(name) { runSoa(cfg, cur, inputs, listOf(SoaPhase(name, sys)), profiler) }
        }
        // ── Lifecycle (SoA-native, in place — no AoS round-trip) ────────────────────
        cur = phaseR("lifecycle") { applyLifecycle(cur, state.divide, state.destroy, input) }

        // ── Lysis attack phase ────────────────────────────────────────────────────
        // Process lysis attacks after lifecycle (victims/attackers guaranteed to exist).
        // Tears biomass from victims, assimilates into attacker's cytoplasm.
        // Per MORPHOGENESIS.md §B: efficiency gear controls the damage/capture balance.
        if (state.lyseIntents.isNotEmpty()) {
            cur = phaseR("lyse") { processLyseAttacks(cur, state.lyseIntents); state.lyseIntents.clear(); cur }
        }

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

    // ── SoA-native lifecycle (incremental; see docs/cyto-soa-lifecycle-plan.md) ─────
    /**
     * Applies the tick's lifecycle events directly on the persistent [CytoWorld] — no AoS round-trip.
     * Returns the mutated world, or **null when the tick's event set isn't handled yet** (the caller then
     * takes the [bridgeLifecycle] round-trip). Bit-identical to the round-trip for the sets it covers
     * (gated by CytoGoldenTest / parallelMatchesSequential / conservation).
     *
     * **The whole lifecycle — DETACH + DESTROY + WELD + WELD-HEAL + DIVISION** — in `CytoLifecycleSystem`'s
     * exact order, so it fully replaces the round-trip (no fallback). The spring topology is edited on an
     * entity-id-keyed adjacency snapshot ([LcAdjacency], mutated by the same logic as [CytoLifecycleSystem]),
     * daughters are allocated + column-added exactly as `spawnCell`/`fromSimState` do, then a single compact
     * + `csr.rebuildFrom` reproduces the round-trip's survivor slot order + edge order.
     */
    private fun applyLifecycle(
        w: CytoWorld,
        divide: List<EntityId>,
        destroy: List<EntityId>,
        input: CytoInput,
    ): CytoWorld {
        // Option 2 — a Repair-weld forms only when BOTH touching cells requested it this tick (both repairing
        // in phase): a body's clock-synchronised cells weld each other, but an out-of-phase foreign cell
        // rarely lines up, so cross-organism welding is rare. (state.weldHealCount[key]==2 ⇒ both sides asked.)
        val readyWelds = state.weldHealByPair.keys.filter { state.weldHealCount[it] == 2 }.sorted()
        if (state.weldLo.isEmpty() && readyWelds.isEmpty() && divide.isEmpty() && destroy.isEmpty() && input.detaches.isEmpty() && interactDestroy.isEmpty()) return w

        val adj = LcAdjacency(w)
        val destroyed = HashSet<Int>()

        // Detach (interact order): cut every connection of the named cell.
        for (id in input.detaches) for (n in adj.neighbours(id.value)) adj.removePair(id.value, n)

        // Destroy (emit order: interact Delete-taps, then biology deaths), deduped like the AoS `destroyed`
        // set. Each dying cell recycles ALL its matter to its reservoir grid-cell, drops its springs, is removed.
        val order = ArrayList<Int>(interactDestroy.size + destroy.size)
        for (idv in interactDestroy) if (destroyed.add(idv)) order.add(idv)
        for (id in destroy) if (destroyed.add(id.value)) order.add(id.value)
        for (idv in order) {
            val slot = w.slotOf(idv); if (slot < 0) continue
            depositCellMatterSoa(w, slot)
            for (n in adj.neighbours(idv)) adj.removePair(idv, n)
            w.world.removeEntity(EntityId(idv))
        }

        // Weld then weld-heal, sharing a `welded` dedup set. pairKey gives the (min,max) ordering — the same
        // for a contact weld (weldLo,weldHi) and a repair weld-heal (key>>32,key) — so one pair welds once,
        // whichever fires first. Skip pairs touching a just-destroyed cell; addSpring no-ops on an existing edge.
        val welded = HashSet<Long>()
        for (i in state.weldLo.indices) {                       // contact order
            val a = state.weldLo[i]; val b = state.weldHi[i]
            if (a in destroyed || b in destroyed) continue
            if (!welded.add(pairKey(a, b))) continue
            adj.addSpring(a, b)
        }
        for (key in readyWelds) {                               // both-repairing pairs, sorted = deterministic
            val a = (key ushr 32).toInt(); val b = key.toInt()
            if (a in destroyed || b in destroyed) continue
            if (!welded.add(pairKey(a, b))) continue
            // Repair-weld: born at full break-damage minus the heal the cell(s) spent this tick (see addSpring).
            adj.addSpring(a, b, initialDamage = (cfg.connectionBreakDamage - state.weldHealByPair.getValue(key)).coerceAtLeast(0f))
        }

        // Divide (biology order). Runs after destroy so a daughter reuses a just-freed id exactly as the
        // AoS allocator would (createEntity scans from lastEntityValue skipping live ids).
        for (id in divide) {
            if (destroyed.contains(id.value)) continue
            divideSoa(w, adj, id, destroyed)
        }

        // Reclaim removed slots (stable insertion order — survivors then daughters-in-intent-order) and
        // rebuild the CSR over the new ordering from the (id-keyed, so compaction-stable) adjacency snapshot.
        w.world.compact()
        w.csr.rebuildFrom(
            count = w.count,
            entityIdAt = { w.entityId[it] },
            slotOf = { w.slotOf(it) },
            springsAt = { slot -> adj.springs[w.entityId[slot]] ?: emptyList() },
            edgeAuxAt = { slot, other -> adj.dmg[w.entityId[slot]]?.get(other.value) ?: 0f },
        )
        return w
    }

    /**
     * Entity-id-keyed adjacency snapshot for SoA-native lifecycle edits — the mutable working copy of the
     * spring topology + per-edge damage, seeded from the current CSR (preserving each cell's edge order).
     * The add/remove/query methods reproduce [addSpring]/[removeSpringPair]/[springExists]/[neighboursOf]
     * exactly (degree cap, dedup, symmetric edges, `rest = ra + rb`, damage-preserving attach). Keyed by
     * entity id (not slot) so it survives the [compact] barrier; the CSR is rebuilt from it at the end.
     */
    private inner class LcAdjacency(val w: CytoWorld) {
        val springs = HashMap<Int, ArrayList<SpringConstraint>>(w.count)
        val dmg = HashMap<Int, HashMap<Int, Float>>(w.count)

        init {
            for (slot in 0 until w.count) {
                val lo = w.csr.offset[slot]; val hi = w.csr.offset[slot + 1]
                if (lo >= hi) continue
                val ownerId = w.entityId[slot]
                val list = ArrayList<SpringConstraint>(hi - lo)
                val d = HashMap<Int, Float>(hi - lo)
                for (k in lo until hi) {
                    val other = w.csr.otherId[k]
                    list.add(SpringConstraint(EntityId(other), Frac(w.csr.restRaw[k]), Frac(w.csr.stiffRaw[k]), Frac(w.csr.dampRaw[k])))
                    d[other] = w.csr.edgeAux[k]
                }
                springs[ownerId] = list
                dmg[ownerId] = d
            }
        }

        /** Other endpoint of each of [id]'s springs (a fresh list, safe to iterate while mutating). */
        fun neighbours(id: Int): List<Int> = springs[id]?.map { it.other.value } ?: emptyList()

        /** Add a symmetric spring a↔b (mirrors [addSpring]): degree-capped, deduped, `rest = ra + rb`. */
        fun addSpring(a: Int, b: Int, initialDamage: Float = 0f) {
            if (a == b) return
            val sa = w.slotOf(a); if (sa < 0) return
            val sb = w.slotOf(b); if (sb < 0) return
            val listA = springs[a]
            if (listA == null || listA.none { it.other.value == b }) {   // a NEW weld: enforce the degree cap
                val degA = listA?.size ?: 0
                val degB = springs[b]?.size ?: 0
                if (degA >= cfg.maxWeldDegree || degB >= cfg.maxWeldDegree) return
            }
            val rest = Frac(w.radiusRaw[sa]) + Frac(w.radiusRaw[sb])
            attach(a, b, rest, initialDamage)
            attach(b, a, rest, initialDamage)
        }

        private fun attach(owner: Int, other: Int, rest: Frac, initialDamage: Float) {
            val list = springs.getOrPut(owner) { ArrayList() }
            if (list.none { it.other.value == other }) list.add(SpringConstraint(EntityId(other), rest, cfg.springStiffness, cfg.springDamping))
            val d = dmg.getOrPut(owner) { HashMap() }
            if (!d.containsKey(other)) d[other] = initialDamage   // keep an existing edge's damage; else born at [initialDamage]
        }

        /** Cut the spring between [a] and [b] on both endpoints (mirrors [removeSpringPair]). */
        fun removePair(a: Int, b: Int) {
            springs[a]?.let { l -> l.removeAll { it.other.value == b } }
            springs[b]?.let { l -> l.removeAll { it.other.value == a } }
            dmg[a]?.remove(b); dmg[b]?.remove(a)
        }
    }

    /** Deposit a cell's entire cytoplasm + biomass into its reservoir grid-cell (death recycling), SoA-native.
     *  Mirrors CytoLifecycleSystem.depositCellMatter; deposits are additive per (leaf, species) ⇒ order-free. */
    private fun depositCellMatterSoa(w: CytoWorld, slot: Int) {
        val lx = CytoUnits.toLogical(Coord(w.posX[slot]))
        val ly = CytoUnits.toLogical(Coord(w.posY[slot]))
        val r = CytoTuning.physicalRadius(Frac(w.cell.logicalRadius[slot])).toFloat()
        w.cell.cytoplasm[slot]?.let { for (i in 0 until it.size) w.grid.deposit(lx, ly, r, it.idAt(i), it.countAt(i)) }
        w.cell.biomass[slot]?.let { for (i in 0 until it.size) w.grid.deposit(lx, ly, r, it.idAt(i), it.countAt(i)) }
    }

    /**
     * SoA-native mitosis — a line-for-line port of [CytoLifecycleSystem.divide] onto the persistent world.
     * Reads the mother + neighbours via column [gather] (identical to what the round-trip's SimBuilder sees),
     * edits the spring topology on [adj], allocates the daughter via `world.createEntity` + `world.add` of all
     * 7 columns (matching `spawnBody`/`spawnCell` + `fromSimState`'s ImpulseComponent), and writes the mother's
     * updated Transform/CytoCell/Material back via [scatter]. Grid deposits go straight to `w.grid` (in place,
     * order-free). The "can't split" case removes the mother (its matter already emitted as remainders).
     */
    private fun divideSoa(w: CytoWorld, adj: LcAdjacency, motherId: EntityId, destroyed: HashSet<Int>) {
        val motherSlot = w.slotOf(motherId.value); if (motherSlot < 0) return
        val cell = w.cell.gather(motherSlot)
        val transform = w.transform.gather(motherSlot)
        val motionVel = w.motion.gather(motherSlot).vel
        val motherPos = transform.pos
        val neighbours = adj.neighbours(motherId.value).map { EntityId(it) }

        // Division parameters — the per-cell intent data the biology phase recorded into `state`.
        val morphogen = state.divideMorphogen[motherId] ?: ""
        val morphogenToMother = motherId in state.divideMorphogenToMother
        val axisMorphogen = state.divideAxis[motherId] ?: ""
        val divideAcross = motherId in state.divideAcross
        val rejectMother = motherId in state.divideRejectMother

        fun posOf(id: EntityId): Coord2? = w.slotOf(id.value).let { if (it < 0) null else w.transform.gather(it).pos }
        fun cellOf(id: EntityId): CytoCellComponent? = w.slotOf(id.value).let { if (it < 0) null else w.cell.gather(it) }

        // Outward normal = away from the average neighbour direction.
        var sumDelta = Frac2.zero
        for (n in neighbours) {
            val np = posOf(n) ?: continue
            sumDelta = sumDelta + (np - motherPos)
        }
        val neighbourVector = -(sumDelta / (neighbours.size + 1))
        val neighbourNormal: Norm =
            if (neighbourVector.x.raw == 0L && neighbourVector.y.raw == 0L) Norm.fromAngle(transform.ang)
            else neighbourVector.norm

        // Oriented division (MORPHOGENESIS.md): place the daughter along/across the axis-morphogen gradient.
        val splitNormal: Norm = run {
            if (axisMorphogen.isEmpty()) return@run neighbourNormal
            fun conc(c: CytoCellComponent): Int {
                val b = totalBiomass(c.biomass); return if (b <= 0) 0 else (c.cytoplasm[axisMorphogen] ?: 0) * CytoTuning.CONC_SCALE / b
            }
            var maxC = conc(cell); var minC = maxC; var maxPos = motherPos; var minPos = motherPos
            for (n in neighbours) {
                val nc = cellOf(n) ?: continue
                val np = posOf(n) ?: continue
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
            val np = posOf(n) ?: continue
            val toMother = (motherPos - np).norm
            val s = toMother.dot(splitNormal).toFloat()
            val group = if (s.absoluteValue < 0.75f) 0f else s.sign
            when (group) {
                -1f -> ahead.add(n)
                0f -> side.add(n)
            }
        }

        // Split each species ⌊C/2⌋ to EACH side; the odd remainder goes to the reservoir (conserved, never minted).
        val mlx = CytoUnits.toLogical(motherPos.x); val mly = CytoUnits.toLogical(motherPos.y); val mr = CytoTuning.physicalRadius(cell.logicalRadius).toFloat()
        val morphogenCount = if (morphogen.isNotEmpty()) (cell.cytoplasm[morphogen] ?: 0) else 0
        val half = floorSplitSoa(w, cell.cytoplasm, mlx, mly, mr, skip = morphogen)
        val halfBio = floorSplitSoa(w, cell.biomass, mlx, mly, mr)

        // Neither daughter can take a whole molecule ⇒ the cell can't split: it dies (matter already emitted).
        if (atomCount(half) + atomCount(halfBio) == 0) {
            if (morphogenCount > 0) w.grid.deposit(mlx, mly, mr, SpeciesRegistry.id(morphogen), morphogenCount)
            for (n in neighbours) adj.removePair(motherId.value, n.value)
            w.world.removeEntity(motherId)
            destroyed.add(motherId.value)
            return
        }
        val daughterRadius = radiusForBiomassSoa(halfBio)
        val radius = CytoTuning.physicalRadius(daughterRadius.coerceAtLeast(MIN_RADIUS))
        val offset = splitNormal * CytoUnits.len(daughterRadius.toFloat())

        // Clonal daughter: inherits the mother's type + genome; the asymmetric morphogen rides whole to one side.
        val daughterCyto = HashMap(half).apply { if (morphogenCount > 0 && !morphogenToMother) put(morphogen, morphogenCount) }
        val daughterBio = HashMap(halfBio)
        val daughter = w.world.createEntity()
        // Mirror spawnBody + spawnCell, plus fromSimState's ImpulseComponent — 7 columns, in that order.
        w.world.add(daughter, TransformComponent(pos = motherPos + offset, ang = Coord(0)))
        w.world.add(daughter, MotionComponent(vel = motionVel, angVel = Coord(0)))
        w.world.add(daughter, ImpulseComponent())
        w.world.add(daughter, ColliderComponent(radius = CytoUnits.len(CytoTuning.physicalRadius(radius).toFloat())))
        w.world.add(daughter, MaterialComponent(mass = cellMass(daughterCyto, daughterBio), bounce = Frac(0), rough = Frac(0)))
        w.world.add(daughter, RenderShapeComponent(BodyShape.CIRCLE))
        w.world.add(daughter, CytoCellComponent(type = cell.type, logicalRadius = radius, cytoplasm = daughterCyto, biomass = daughterBio, genome = cell.genome))

        if (!rejectMother) {
            for (n in ahead) { adj.addSpring(daughter.value, n.value); adj.removePair(motherId.value, n.value) }
            for (n in side) adj.addSpring(daughter.value, n.value)
        }

        // Mother: step back along the split, rotate a quarter turn, keep its (equal) half of the matter.
        w.transform.scatter(motherSlot, transform.copy(pos = motherPos - offset, ang = transform.ang + Frac(1, 2)))
        val motherCyto = HashMap(half).apply { if (morphogenCount > 0 && morphogenToMother) put(morphogen, morphogenCount) }
        w.cell.scatter(motherSlot, cell.copy(cytoplasm = motherCyto, biomass = HashMap(halfBio), logicalRadius = daughterRadius))
        w.material.scatter(motherSlot, w.material.gather(motherSlot).copy(mass = cellMass(motherCyto, halfBio)))

        if (!rejectMother) adj.addSpring(motherId.value, daughter.value)
    }

    /** Per-side ⌊count/2⌋ split; the odd remainder is deposited to the reservoir at (cx,cy,radius).
     *  [skip] (the asymmetric morphogen) is left out — allocated whole to one side by the caller.
     *  Mirrors CytoLifecycleSystem.floorSplit. */
    private fun floorSplitSoa(w: CytoWorld, m: Map<String, Int>, cx: Float, cy: Float, radius: Float, skip: String = ""): Map<String, Int> {
        val half = HashMap<String, Int>()
        for ((species, count) in m) {
            if (species == skip) continue
            val h = count / 2
            if (h > 0) half[species] = h
            val remainder = count - 2 * h
            if (remainder > 0) w.grid.deposit(cx, cy, radius, SpeciesRegistry.id(species), remainder)
        }
        return half
    }

    private fun radiusForBiomassSoa(biomass: Map<String, Int>): Frac =
        Frac(totalBiomass(biomass).toLong(), CytoBiologyCore.ATOMS_PER_FULL).sqrt().coerceAtLeast(MIN_RADIUS)

    // ── interaction bridge (only when there's pointer input) ─────────────────────
    private fun bridgeInteraction(w: CytoWorld, inputs: Map<PlayerId, CytoInput>): CytoWorld {
        val builder = SimBuilder(w.toSimState())
        CytoInteractionSystem.update(cfg, builder, inputs)
        for (e in builder.events<CellDestroyIntent>()) interactDestroy.add(e.id.value)  // Delete taps → lifecycle
        val out = builder.build()
        val nw = CytoWorld.fromSimState(out)
        nw.world.randomSeed = out.randomSeed
        return nw
    }

    // ── lysis attack processing ─────────────────────────────────────────────────

    /** Process all lyse attack intents: shred all biomass from victims, assimilate what the attacker
     *  can hold. Undigestible species are forced into the attacker's cytoplasm — a metabolic burden
     *  that accumulates over time (creating evolutionary pressure for prey to produce predator-toxic
     *  chemicals). Returns the world. */
    private fun processLyseAttacks(w: CytoWorld, intents: List<LyseAttackIntent>): CytoWorld {
        for (intent in intents) {
            val attackerSlot = w.slotOf(intent.attacker.value)
            if (attackerSlot < 0) continue

            // Attacker's grid location, for spilling any cap-evicted (toxic) species to the environment.
            val ax = CytoUnits.toLogical(Coord(w.posX[attackerSlot]))
            val ay = CytoUnits.toLogical(Coord(w.posY[attackerSlot]))
            val ar = CytoTuning.physicalRadius(Frac(w.cell.logicalRadius[attackerSlot])).toFloat()

            // All stolen biomass is forced into the attacker's cytoplasm as a metabolic burden — the
            // basis of prey toxicity. (The old gear-based capture/spill fraction was a no-op — both
            // halves landed in cytoplasm regardless of gear — so it's dropped; intent.gear no longer
            // affects lyse. The chem-cap eviction in ingestWithCap is now what spills toxins to env.)
            val damagePerVictim = if (intent.victims.isEmpty()) 0 else intent.damage / intent.victims.size
            if (damagePerVictim <= 0) continue

            for (victimId in intent.victims) {
                val victimSlot = w.slotOf(victimId.value)
                if (victimSlot < 0) continue

                val victimBio = w.cell.biomass[victimSlot] ?: continue
                if (victimBio.isEmpty()) continue

                val attackerCyto = w.cell.cytoplasm[attackerSlot] ?: run {
                    val store = MoleculeStore(CytoTuning.CELL_CHEM_CAP)
                    w.cell.cytoplasm[attackerSlot] = store
                    store
                }

                // Steal all species. Snapshot the victim's (id, count) first: add() below removes a
                // species when it fully drains, which compacts the store — iterating it live by index
                // would shift unvisited species under the cursor and skip them (so "steal all" would
                // silently miss species and stop early). Mirrors decayLeaf's snapshot discipline.
                val vN = victimBio.size
                val vIds = IntArray(vN); val vCnts = IntArray(vN)
                for (i in 0 until vN) { vIds[i] = victimBio.idAt(i); vCnts[i] = victimBio.countAt(i) }
                for (i in 0 until vN) {
                    val spId = vIds[i]
                    val victimCount = vCnts[i]
                    if (victimCount <= 0) continue
                    // Steal proportional to count.
                    val stolen = minOf(damagePerVictim.toLong(), victimCount.toLong()).toInt()
                    victimBio.add(spId, -stolen)

                    // All stolen atoms are forced into the attacker's cytoplasm (the gear-based
                    // capture/spill split is cosmetic here — both halves land in cytoplasm), where they
                    // accumulate as a metabolic burden. Once the cell hits its distinct-species cap, a
                    // new (undigestible, toxic) species evicts the scarcest resident to the environment
                    // — the toxicity release valve. See [ingestWithCap].
                    ingestWithCap(w.grid, attackerCyto, spId, stolen, ax, ay, ar)
                }
            }
        }
        return w
    }

    companion object {
        /**
         * Absorb [amount] of species [spId] into a cell's cytoplasm [cyto], enforcing the fixed
         * distinct-species cap ([CytoTuning.CELL_CHEM_CAP]) — the toxicity mechanic. If the species is
         * already held, or there is room under the cap, it is simply absorbed. Otherwise the scarcest of
         * {resident scarcest, incoming} is evicted, its atoms spilled to the cell's grid leaf at
         * ([ax], [ay]) radius [ar] — so matter is conserved and a saturated cell leaks its rarest
         * chemical to the environment rather than growing unbounded. Deterministic: scarcest = lowest
         * count, ties broken by lowest species id.
         */
        internal fun ingestWithCap(
            grid: CytoMatterField, cyto: MoleculeStore, spId: Int, amount: Int,
            ax: Float, ay: Float, ar: Float,
        ) {
            if (amount <= 0) return
            if (cyto.count(spId) > 0 || cyto.size < CytoTuning.CELL_CHEM_CAP) {
                cyto.inc(spId, amount)
                return
            }
            // At cap with a new species: evict the scarcest, spilling it to the environment.
            val si = cyto.scarcestIndex()
            val scarceId = cyto.idAt(si)
            val scarceCount = cyto.countAt(si)
            if (amount < scarceCount || (amount == scarceCount && spId < scarceId)) {
                grid.deposit(ax, ay, ar, spId, amount)            // incoming is scarcest → straight to env
            } else {
                cyto.add(scarceId, -scarceCount)                  // evict resident scarcest…
                grid.deposit(ax, ay, ar, scarceId, scarceCount)   // …to env
                cyto.inc(spId, amount)                            // …admit the incoming species
            }
        }
    }
}
