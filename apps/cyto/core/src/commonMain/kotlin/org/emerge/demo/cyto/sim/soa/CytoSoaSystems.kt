package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.systems.LyseAttackIntent
import org.emerge.demo.cyto.sim.CellWork
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoExposure
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.demo.cyto.sim.MoleculeStore
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.ecs.SpatialGrid
import org.emerge.sim.core.ecs.soa.SoaSystem

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
    // Slot-ordered entity ids parallel to bioOrderedWorks — lets diffuse partition over an indexable list.
    val bioOrderedIds = ArrayList<EntityId>()
    // Reusable scratch for the drop-contested passive exchange (serial pre-pass touch-count + batch list).
    val exchangeScratch = org.emerge.demo.cyto.sim.ExchangeScratch()
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
            ConnectionsSystem(state, executor, springParallelThreshold),
            GrabSystem(),
            DragSystem(executor, springParallelThreshold),
            SpringSolveSystem(executor, springParallelThreshold, state),
            IntegrateSystem(),
        ),
    )
}
