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
import org.emerge.demo.cyto.sim.SpeciesRegistry
import org.emerge.demo.cyto.sim.cellMass
import org.emerge.demo.cyto.sim.systems.CellDestroyIntent
import org.emerge.demo.cyto.sim.systems.CellDivisionIntent
import org.emerge.demo.cyto.sim.systems.CytoInteractionSystem
import org.emerge.demo.cyto.sim.systems.LyseAttackIntent
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
import org.emerge.sim.core.ecs.soa.runSoa
import org.emerge.sim.core.ecs.soa.SoaPhase
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
        // ── Hot path: SoA pipeline ───────────────────────────────────────────────────
        // Biology runs between contacts and connections (it needs to communicate
        // divide/destroy to the lifecycle bridge, and connections needs biology's weld-heal data).
        // We create phases around the system lists so profiler timing works correctly.
        val preBioPhase = listOf(SoaPhase("reset+contacts", parts.preBio))
        phase("reset+contacts") { runSoa(cfg, cur, inputs, preBioPhase, profiler) }
        phaseR("biology") { parts.bioUpdate(cfg, cur, inputs); state.divide to state.destroy }
        // Profile each post-bio system separately
        for ((idx, sys) in parts.postBio.withIndex()) {
            val name = if (idx == parts.postBio.size - 1) "forces+integrate" else "force:${sys::class.java.simpleName}"
            phase(name) { runSoa(cfg, cur, inputs, listOf(SoaPhase(name, sys)), profiler) }
        }
        // ── Cold bridge: lifecycle ──────────────────────────────────────────────────
        cur = phaseR("lifecycle") { bridgeLifecycle(cur, state.divide, state.destroy, input, inputs) }

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
        // rarely lines up, so cross-organism welding is rare. (state.weldHealCount[key]==2 ⇒ both sides asked.)
        val readyWelds = state.weldHealByPair.keys.filter { state.weldHealCount[it] == 2 }.sorted()
        if (state.weldLo.isEmpty() && readyWelds.isEmpty() && divide.isEmpty() && destroy.isEmpty() && input.detaches.isEmpty() && interactDestroy.isEmpty()) return w
        val impById = HashMap<Int, ImpulseComponent>(w.count)
        for (slot in 0 until w.count) impById[w.entityId[slot]] = w.impulse.gather(slot)

        val builder = SimBuilder(w.toSimState(includeImpulse = false))
        for (id in input.detaches) builder.emit(DetachIntent(id))      // interact order
        for (idv in interactDestroy) builder.emit(CellDestroyIntent(EntityId(idv)))  // Delete taps (interact, before biology)
        for (id in destroy) builder.emit(CellDestroyIntent(id))         // biology order
        for (i in state.weldLo.indices) builder.emit(WeldIntent(EntityId(state.weldLo[i]), EntityId(state.weldHi[i]))) // contact order
        for (key in readyWelds) builder.emit(WeldHealIntent(EntityId((key ushr 32).toInt()), EntityId(key.toInt()), state.weldHealByPair.getValue(key)))  // both-repairing pairs, sorted = deterministic
        for (id in divide) builder.emit(CellDivisionIntent(id, state.divideMorphogen[id] ?: "", id in state.divideMorphogenToMother, state.divideAxis[id] ?: "", id in state.divideAcross, id in state.divideRejectMother))  // biology order
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

    // ── lysis attack processing ─────────────────────────────────────────────────
    /** Process all lyse attack intents: shred all biomass from victims, assimilate what the attacker
     *  can hold. Undigestible species are forced into the attacker's cytoplasm — a metabolic burden
     *  that accumulates over time (creating evolutionary pressure for prey to produce predator-toxic
     *  chemicals). Returns the world. */
    private fun processLyseAttacks(w: CytoWorld, intents: List<LyseAttackIntent>): CytoWorld {
        val maxGear = CytoTuning.EFFICIENCY_MAX_GEAR
        for (intent in intents) {
            val attackerSlot = w.slotOf(intent.attacker.value)
            if (attackerSlot < 0) continue

            // Capture fraction: ⌊(gear+1) / (EFFICIENCY_MAX_GEAR+1)⌋.
            // Low gear = brute shredder (high damage, low capture — most spilled to env).
            // High gear = surgical digester (less damage, high capture — nearly all assimilated).
            // Undigestible species (attacker can't hold them) are FORCED into attacker cytoplasm,
            // creating a metabolic burden — the basis of prey toxicity.
            val captureNum = (intent.gear + 1).toLong()
            val captureDen = (maxGear + 1).toLong()
            val damagePerVictim = if (intent.victims.isEmpty()) 0 else intent.damage / intent.victims.size
            if (damagePerVictim <= 0) continue

            for (victimId in intent.victims) {
                val victimSlot = w.slotOf(victimId.value)
                if (victimSlot < 0) continue

                val victimBio = w.cell.biomass[victimSlot] ?: continue
                if (victimBio.isEmpty()) continue

                val attackerCyto = w.cell.cytoplasm[attackerSlot] ?: run {
                    val store = MoleculeStore()
                    w.cell.cytoplasm[attackerSlot] = store
                    store
                }

                // Steal all species equally (proportional to victim biomass composition).
                var totalStolen = 0
                for (i in 0 until victimBio.size) {
                    val spId = victimBio.idAt(i)
                    val victimCount = victimBio.countAt(i)
                    if (victimCount <= 0) continue
                    // Steal proportional to count.
                    val stolen = minOf(damagePerVictim.toLong(), victimCount.toLong()).toInt()
                    victimBio.add(spId, -stolen)
                    totalStolen += stolen

                    // Assimilate: ⌊stolen × captureNum / captureDen⌋
                    val captured = (stolen.toLong() * captureNum / captureDen).toInt()
                    attackerCyto.inc(spId, captured)

                    // Undigestible (spilled) species are FORCED into attacker cytoplasm instead of env.
                    // These accumulate and dilute useful cytoplasm — the toxicity mechanism.
                    val spilled = stolen - captured
                    if (spilled > 0) {
                        attackerCyto.inc(spId, spilled)
                    }
                }
            }
        }
        return w
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
