package org.emerge.demo.drockets

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * Which family-tree layout the lineage overlay should use to convert lineage structure
 * into positions on screen. Orthogonal to [CladogramFilterMode] — each filter (ALL,
 * LIVING_ONLY, …) can be paired with either layout.
 */
enum class CladogramLayoutMode {
    /**
     * Generation-aligned hierarchical layout: y is determined by node depth, x by
     * parent-centroid sweeps under a non-overlap constraint. Re-solved end-to-end
     * whenever the lineage changes structure, which can rearrange many nodes laterally
     * even for a single birth or death — visually disruptive while the sim is busy.
     */
    HIERARCHICAL,

    /**
     * Force-directed graph layout with persistent per-node state. Every frame applies
     * spring forces along visible edges, repulsion between nearby visible nodes, and
     * velocity damping; the system relaxes incrementally toward equilibrium. New nodes
     * are seeded next to their first visible parent, so a birth perturbs only the local
     * neighbourhood rather than the entire generation row. Positions persist across
     * filter changes and node deaths, making it much easier to track and click an
     * individual node while reproduction is busy.
     *
     * No imposed generation axis: time is implicit in graph structure, not the y
     * coordinate — older lineages sit wherever the springs settle them.
     */
    FORCE_DIRECTED,
}

/**
 * Persistent force-directed layout for the drockets cladogram.
 *
 * Unlike [CladogramLayoutSolver] (which produces an immutable result from scratch each
 * call), this solver retains a per-lineage `(x, y, vx, vy)` between [step] calls.
 * Births inject a new node next to a parent; deaths leave the node where it is; filter
 * changes hide nodes from the simulation but do not discard their saved positions.
 *
 * Forces per step (all in the same logical units as [CladogramLayoutSolver]):
 *  - **Edge spring** pulls connected nodes toward [REST_LENGTH] with stiffness
 *    [SPRING_K]. Springs both pull apart-too-close and pull together-too-far.
 *  - **Horizontal-only pairwise repulsion** pushes nearby nodes apart along x
 *    with full magnitude `F = REPULSION_K / d²`. Magnitude scales with the 2D
 *    separation (so distant pairs feel weaker force) but the direction is purely
 *    horizontal — not cosine-attenuated by the bond angle. A vertically-stacked
 *    pair (same x, different y) feels full horizontal force pushing it sideways,
 *    so visual overlap doesn't persist. The y-component is dropped entirely, so
 *    nodes can still slide past each other vertically without resistance. Cell-
 *    based spatial hashing keeps the step near-linear in node count.
 *  - **Hookean buoyancy on visible roots** ([BUOYANCY_K] · (anchor − currentY)):
 *    a node with no parent currently in the visible set is pulled back toward
 *    [BUOYANCY_ANCHOR_Y] by a spring whose magnitude scales with divergence from
 *    the anchor. The chain weight transmitted through its descendants stretches
 *    this spring until tension balances it, so the root reaches a finite
 *    equilibrium below the anchor instead of drifting forever like a constant
 *    upward force would.
 *  - **Gravity on everything else** ([GRAVITY_K], downward): nodes that do have a
 *    visible parent gently fall away from their ancestors, so each generation
 *    settles below the previous one.
 *  - **Damping** multiplies the previous velocity by [DAMPING] before adding the
 *    integrated force, so the system reaches equilibrium without oscillation.
 *  - **Per-step displacement clamp** ([MAX_DISPLACEMENT]) keeps a single frame from
 *    teleporting a node when forces transiently spike (eg coincident seed).
 *
 * Integration is explicit Euler with damped velocity (one logical "tick" per [step]
 * call); the renderer drives one step per frame while the overlay is active.
 */
class ForceDirectedLayoutSolver {
    /**
     * Per-lineage state, packed as `[x, y, vx, vy]`. Retained across [step] calls so
     * that hidden nodes can return to the same place when filter mode cycles back to a
     * mode that includes them again. Entries are only dropped when [DrocketLineageState]
     * itself forgets the id (which shouldn't normally happen in a running sim — nodes
     * stay in `nodes` after death — but the cleanup keeps memory bounded under
     * snapshot replacement and similar wholesale changes).
     */
    private val state = HashMap<Long, FloatArray>()

    /** Wall time of the most recent [step] in milliseconds, for HUD profiling. */
    var lastStepMs: Float = 0f
        private set

    /**
     * Multiplier applied to every per-step force before integration. Equilibrium
     * positions are unchanged (all forces scale together), so this is purely a
     * relaxation-rate dial. Clamped to [MIN_FORCE_SCALE, MAX_FORCE_SCALE] on
     * assignment so a runaway nudge can't push the integrator into instability.
     */
    var forceScale: Float = 1f
        set(value) { field = value.coerceIn(MIN_FORCE_SCALE, MAX_FORCE_SCALE) }

    /** True if [step] has never been called and no [seedFrom] has populated state. */
    val isEmpty: Boolean get() = state.isEmpty()

    /**
     * Initialise positions from a prior hierarchical solve. Used when switching from
     * [CladogramLayoutMode.HIERARCHICAL] to [CladogramLayoutMode.FORCE_DIRECTED] so the
     * force pass starts from a well-untangled configuration rather than a random scatter.
     * Existing positions are not overwritten — repeat calls are safe.
     */
    fun seedFrom(positions: Map<Long, Pair<Float, Float>>) {
        for ((id, p) in positions) {
            if (state.containsKey(id)) continue
            state[id] = floatArrayOf(p.first, p.second, 0f, 0f)
        }
    }

    /** Discards all stored positions and velocities. */
    fun reset() {
        state.clear()
        lastStepMs = 0f
    }

    /**
     * Read the retained position of [id] without stepping. Returns `null` for an id the
     * solver hasn't seen. Useful for callers who want to inspect a hidden node's
     * position (it stays where it was last simulated until it becomes visible again).
     */
    fun positionOf(id: Long): Pair<Float, Float>? {
        val s = state[id] ?: return null
        return s[0] to s[1]
    }

    /**
     * Advance the simulation one tick. Mutates state for every id in [visibleIds]: seeds
     * new ones near a visible parent, applies forces over visible edges, integrates
     * positions. Hidden nodes are skipped entirely (their stored positions go untouched).
     *
     * Returns the visible nodes' positions in a fresh map suitable for handing to a
     * [CladogramLayoutSolution]; the underlying state is mutated in place.
     */
    fun step(
        layout: CladogramLayout,
        lineage: DrocketLineageState,
        visibleIds: Set<Long>,
    ): Map<Long, Pair<Float, Float>> {
        val start = TimeSource.Monotonic.markNow()

        // Drop state for ids the lineage has forgotten entirely (snapshot replacement
        // is the realistic case; in normal play nodes persist after death).
        if (state.size > lineage.nodes.size) {
            val drop = ArrayList<Long>()
            for (id in state.keys) {
                if (!lineage.nodes.containsKey(id)) drop.add(id)
            }
            for (id in drop) state.remove(id)
        }

        // Seed any visible id without prior state (a fresh birth, typically). The
        // seed is anchored to the first visible parent so the local cluster grows
        // outward instead of nodes popping in at the centroid.
        for (id in visibleIds) {
            if (state.containsKey(id)) continue
            state[id] = seedNew(id, lineage, visibleIds)
        }

        val n = visibleIds.size
        if (n == 0) {
            lastStepMs = start.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f
            return emptyMap()
        }

        // Build dense working arrays so the inner loops stay cache-friendly.
        val ids = LongArray(n)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val vxs = FloatArray(n)
        val vys = FloatArray(n)
        val indexById = HashMap<Long, Int>(n)
        run {
            var idx = 0
            for (id in visibleIds) {
                val s = state[id] ?: continue
                ids[idx] = id
                xs[idx] = s[0]
                ys[idx] = s[1]
                vxs[idx] = s[2]
                vys[idx] = s[3]
                indexById[id] = idx
                idx++
            }
        }

        val fxs = FloatArray(n)
        val fys = FloatArray(n)

        // Per-node vertical bias. Visible roots (no visible parent) get a Hookean
        // buoyancy spring pulling them back toward BUOYANCY_ANCHOR_Y; every other
        // visible node gets a constant downward gravity. The visible-parent check
        // uses `indexById` rather than the node's lineage parents directly so
        // filter modes that hide ancestors (eg LIVING_ONLY) still let their
        // visible-roots float toward the anchor.
        for (i in 0 until n) {
            val node = lineage.nodes[ids[i]]
            val motherVisible = node?.motherLineageId?.let { indexById.containsKey(it) } ?: false
            val fatherVisible = node?.fatherLineageId?.let { indexById.containsKey(it) } ?: false
            fys[i] += if (!motherVisible && !fatherVisible) {
                BUOYANCY_K * (BUOYANCY_ANCHOR_Y - ys[i])
            } else {
                -GRAVITY_K
            }
        }

        // Spring forces along every edge whose endpoints are both visible.
        for ((from, to) in layout.edges) {
            val i = indexById[from] ?: continue
            val j = indexById[to] ?: continue
            var dx = xs[j] - xs[i]
            var dy = ys[j] - ys[i]
            var d2 = dx * dx + dy * dy
            if (d2 < MIN_DIST2) {
                // Coincident seed (eg twin birth from same parent). Pick an arbitrary
                // axis so the spring resolves the degeneracy on the next step.
                dx = MIN_DIST
                dy = 0f
                d2 = MIN_DIST2
            }
            val d = sqrt(d2)
            val k = SPRING_K * (d - REST_LENGTH) / d
            fxs[i] += k * dx
            fys[i] += k * dy
            fxs[j] -= k * dx
            fys[j] -= k * dy
        }

        // Repulsion via uniform-grid spatial hash. Each cell is wider than the
        // attraction's rest length so two springs' worth of nodes can sit in one
        // cell without missing their mutual repulsion via the 3×3 neighbourhood.
        val cellBuckets = HashMap<Long, ArrayList<Int>>(n)
        for (i in 0 until n) {
            val key = cellKey(xs[i], ys[i])
            cellBuckets.getOrPut(key) { ArrayList(4) }.add(i)
        }
        for (i in 0 until n) {
            val cx = floor(xs[i] / CELL_SIZE).toLong()
            val cy = floor(ys[i] / CELL_SIZE).toLong()
            for (ox in -1L..1L) {
                for (oy in -1L..1L) {
                    val key = packCell(cx + ox, cy + oy)
                    val bucket = cellBuckets[key] ?: continue
                    for (j in bucket) {
                        if (j <= i) continue   // count each pair once
                        var dx = xs[j] - xs[i]
                        var dy = ys[j] - ys[i]
                        var d2 = dx * dx + dy * dy
                        if (d2 > REPULSION_CUTOFF2) continue
                        if (d2 < MIN_DIST2) {
                            dx = MIN_DIST
                            dy = 0f
                            d2 = MIN_DIST2
                        }
                        // Horizontal-only repulsion. Magnitude scales with 2D
                        // separation (REPULSION_K / d²), but the entire force is
                        // applied along the x-axis — *not* cosine-attenuated by the
                        // bond angle. A vertically-stacked pair (dx≈0, dy>0) feels
                        // full horizontal force in opposite x directions, so they
                        // separate sideways instead of overlapping. The y-component
                        // is discarded entirely, so the pair feels no resistance to
                        // sliding past each other vertically.
                        val mag = REPULSION_K / d2
                        val rx = if (dx >= 0f) mag else -mag
                        fxs[i] -= rx
                        fxs[j] += rx
                    }
                }
            }
        }

        // Scale all accumulated forces by the runtime [forceScale]. Multiplying here
        // is equivalent to scaling SPRING_K / REPULSION_K / BUOYANCY_K / GRAVITY_K
        // individually, so equilibrium shape is unchanged — only relaxation rate.
        if (forceScale != 1f) {
            for (i in 0 until n) {
                fxs[i] *= forceScale
                fys[i] *= forceScale
            }
        }

        // Integrate: vel = damped(prev) + force; clamp displacement; advance position.
        for (i in 0 until n) {
            var vx = vxs[i] * DAMPING + fxs[i]
            var vy = vys[i] * DAMPING + fys[i]
            val vmag2 = vx * vx + vy * vy
            if (vmag2 > MAX_DISPLACEMENT2) {
                val s = MAX_DISPLACEMENT / sqrt(vmag2)
                vx *= s
                vy *= s
            }
            xs[i] += vx
            ys[i] += vy
            vxs[i] = vx
            vys[i] = vy
        }

        // Flush back into the persistent state and assemble the result map.
        val out = LinkedHashMap<Long, Pair<Float, Float>>(n)
        for (i in 0 until n) {
            val s = state[ids[i]] ?: continue
            s[0] = xs[i]; s[1] = ys[i]; s[2] = vxs[i]; s[3] = vys[i]
            out[ids[i]] = xs[i] to ys[i]
        }

        lastStepMs = start.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f
        return out
    }

    /**
     * Seed a fresh node's position based on its visible parents:
     *  - Both parents visible: the exact midpoint between them. The repulsion and
     *    spring forces nudge the child onto a stable position over the next few
     *    steps — there's no reason to bias it toward one parent at birth.
     *  - One parent visible: offset from that parent by [SEED_OFFSET] along a
     *    golden-angle direction (so simultaneous siblings don't seed on top of each
     *    other).
     *  - Neither visible (lineage root or filter-orphaned): deterministic spiral
     *    around the origin so concurrent roots also don't collide.
     */
    private fun seedNew(id: Long, lineage: DrocketLineageState, visibleIds: Set<Long>): FloatArray {
        val node = lineage.nodes[id]
        if (node != null) {
            val motherState = node.motherLineageId?.takeIf { it in visibleIds }?.let { state[it] }
            val fatherState = node.fatherLineageId?.takeIf { it in visibleIds }?.let { state[it] }
            if (motherState != null && fatherState != null) {
                return floatArrayOf(
                    (motherState[0] + fatherState[0]) * 0.5f,
                    (motherState[1] + fatherState[1]) * 0.5f,
                    0f, 0f,
                )
            }
            val soleParent = motherState ?: fatherState
            if (soleParent != null) {
                val angle = goldenAngle(id)
                return floatArrayOf(
                    soleParent[0] + cos(angle) * SEED_OFFSET,
                    soleParent[1] + sin(angle) * SEED_OFFSET,
                    0f, 0f,
                )
            }
        }
        val angle = goldenAngle(id)
        val r = SEED_OFFSET + ((id and 0xFFFFL).toInt() % 8) * SEED_OFFSET * 0.25f
        return floatArrayOf(cos(angle) * r, sin(angle) * r, 0f, 0f)
    }

    private fun goldenAngle(id: Long): Float =
        ((id and 0xFFFFL).toInt() * GOLDEN_ANGLE_RAD)

    private fun cellKey(x: Float, y: Float): Long =
        packCell(floor(x / CELL_SIZE).toLong(), floor(y / CELL_SIZE).toLong())

    private fun packCell(cx: Long, cy: Long): Long =
        (cx * CELL_HASH_X) xor (cy * CELL_HASH_Y)

    companion object {
        /** Natural spring length between connected nodes, in logical units. Other
         *  spatial constants are expressed as multiples of this where it makes sense. */
        const val REST_LENGTH: Float = 0.10f
        // The ratios between SPRING_K, REPULSION_K, BUOYANCY_K, and GRAVITY_K set
        // the equilibrium shape (root depth, chain stretch, sibling spread); the
        // absolute scale sets how fast the system relaxes toward that shape.
        private const val SPRING_K: Float = 0.001f
        // Pairwise horizontal repulsion strength. Higher values spread siblings
        // further apart horizontally but also widen the zig-zag of connected
        // pairs past REST_LENGTH.
        private const val REPULSION_K: Float = 0.000001f
        // Vertical bias:
        //   - Visible roots (no visible parent) feel a Hookean buoyancy spring
        //     pulling them back toward BUOYANCY_ANCHOR_Y. Equilibrium root
        //     displacement from the anchor is chain_weight/BUOYANCY_K, where the
        //     chain weight is the sum of GRAVITY_K over the root's visible
        //     descendants.
        //   - Every other visible node feels a constant downward gravity. The top
        //     spring of an N-non-root chain carries N·GRAVITY_K of accumulated
        //     weight; its y-stretch past REST_LENGTH is N·GRAVITY_K/SPRING_K, so
        //     bumping GRAVITY_K stretches deep trees more, and bumping SPRING_K
        //     keeps them tighter. The bottom spring of any chain stays close to
        //     REST_LENGTH.
        private const val BUOYANCY_K: Float = 0.01f
        private const val BUOYANCY_ANCHOR_Y: Float = 0f
        private const val GRAVITY_K: Float = 0.0002f
        // Effective repulsion cutoff: pairs farther apart than CELL_SIZE in 2D
        // are skipped. Should be larger than REST_LENGTH so connected pairs still
        // see each other for the horizontal repulsion.
        private const val CELL_SIZE: Float = 0.20f
        private const val REPULSION_CUTOFF2: Float = CELL_SIZE * CELL_SIZE
        private const val DAMPING: Float = 0.01f
        // Safety clamp on per-step displacement. Catches degenerate force spikes
        // (eg perfectly coincident seed positions) without affecting normal motion.
        private const val MAX_DISPLACEMENT: Float = 0.01f
        private const val MAX_DISPLACEMENT2: Float = MAX_DISPLACEMENT * MAX_DISPLACEMENT
        private const val MIN_DIST: Float = 0.005f
        private const val MIN_DIST2: Float = MIN_DIST * MIN_DIST
        private const val SEED_OFFSET: Float = 0.04f
        private const val GOLDEN_ANGLE_RAD: Float = 2.39996323f

        /** Hard bounds on the runtime [forceScale] multiplier. Anything past these
         *  pushes the explicit-Euler integrator into instability; anything below
         *  makes relaxation imperceptibly slow. */
        const val MIN_FORCE_SCALE: Float = 1f/32f
        const val MAX_FORCE_SCALE: Float = 1024f

        // Large odd primes for spatial-hash mixing — same approach as other uniform-grid
        // hashes in the codebase, kept here as local consts to avoid a cross-package dep.
        private const val CELL_HASH_X: Long = 73856093L
        private const val CELL_HASH_Y: Long = 19349663L
    }
}
