package org.emerge.demo.drockets

import kotlin.math.cos
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
     * spring forces along visible edges, all-pairs isotropic repulsion between visible
     * nodes, and velocity damping; the system relaxes incrementally toward equilibrium. New nodes
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
 *  - **All-pairs isotropic repulsion** pushes every visible pair apart along
 *    the 2D line between them with magnitude `F = k / d²` (Coulomb-like, force
 *    vector = `k · Δ / d³`). Acts equally in x and y, so siblings spread
 *    sideways and ancestor chains stretch vertically from mutual push as well
 *    as from gravity/buoyancy. The constant `k` is [REPULSION_K], with an
 *    optional `+ LIVING_REPULSION_K` bonus when both endpoints are currently
 *    alive. No spatial hash — O(n²), comfortable at visible-cladogram sizes
 *    (well under 1k nodes).
 *  - **Radial-outward "gravity" on every visible node** ([OUTWARD_K]): a
 *    constant-magnitude force pointing away from the **cluster centroid**
 *    (mean position of all visible nodes) along the node's position vector
 *    relative to that centroid. Inflates the whole cluster from the inside.
 *    Centering on exactly the force-receiving set keeps `Σ(pos − centroid) =
 *    0`, so the per-frame outward forces sum to zero and the cluster only
 *    expands radially, never drifts bodily. Skipped at the centroid (no
 *    defined direction); other forces nudge the node off on the next step.
 *  - **Hookean buoyancy on visible roots** ([BUOYANCY_K] · (anchor − pos)):
 *    a node with no parent currently in the visible set is pulled back toward
 *    ([BUOYANCY_ANCHOR_X], [BUOYANCY_ANCHOR_Y]) by a 2D spring whose magnitude
 *    scales with divergence from the anchor. Springs through descendants
 *    transmit chain weight that stretches this spring until tension balances
 *    it. The 2D anchor (versus a y-only anchor) gives the tree a soft "centre"
 *    that the whole cluster drifts toward via spring coupling, so the centroid
 *    of the radial layout stays near the origin instead of wandering.
 *  - **Gravity on visible leaves** ([GRAVITY_K], downward): a node with no
 *    visible children gets a constant downward pull. Combined with buoyancy on
 *    the visible-roots, this stretches the tree vertically from both ends —
 *    middle nodes (those with both a visible parent AND a visible child) feel
 *    no direct vertical force and find their y-position purely through spring
 *    tension transmitted along the chain.
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

        // Pre-pass: identify which visible nodes have at least one visible child.
        // We can't ask the lineage directly — children aren't indexed — so we walk
        // each node's parent pointers and mark its visible parents as having a
        // visible child (this node).
        val hasVisibleChild = BooleanArray(n)
        for (i in 0 until n) {
            val node = lineage.nodes[ids[i]] ?: continue
            node.motherLineageId?.let { indexById[it] }?.let { hasVisibleChild[it] = true }
            node.fatherLineageId?.let { indexById[it] }?.let { hasVisibleChild[it] = true }
        }

        // Pre-pass: cache which dense indices correspond to currently-living nodes,
        // so the repulsion inner loop can branch on liveness without doing a Set
        // lookup per pair.
        val isLiving = BooleanArray(n)
        for (i in 0 until n) {
            if (ids[i] in lineage.livingLineageIds) isLiving[i] = true
        }

        // Per-node bias:
        //   - Visible-root (no visible parent): 2D Hookean buoyancy toward
        //     (BUOYANCY_ANCHOR_X, BUOYANCY_ANCHOR_Y). Pulls roots inward in
        //     both axes and, via spring coupling, drags the rest of the tree
        //     toward the anchor — soft-centring the layout on the origin.
        //   - Visible-leaf (no visible child):  constant downward gravity.
        //   - Middle node (has both):           no direct vertical bias; its
        //                                       position emerges from spring
        //                                       tension transmitted along the
        //                                       chain.
        // A node that is both a root and a leaf (e.g. an isolated single living)
        // feels both — buoyancy pulling toward the anchor, gravity pulling down.
        // Cluster centroid: mean position of every visible node. Used as the
        // origin for the radial-outward force below, which applies to every
        // visible node (not just leaves). The centroid must be computed over
        // exactly the force-receiving set so that Σ(pos − centroid) = 0 holds
        // by construction — that's what keeps the per-frame outward forces
        // zero-sum and prevents the cluster from drifting bodily.
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            cx += xs[i]
            cy += ys[i]
        }
        cx /= n
        cy /= n

        for (i in 0 until n) {
            val node = lineage.nodes[ids[i]]
            val motherVisible = node?.motherLineageId?.let { indexById.containsKey(it) } ?: false
            val fatherVisible = node?.fatherLineageId?.let { indexById.containsKey(it) } ?: false
            val isVisibleRoot = !motherVisible && !fatherVisible
            val isVisibleLeaf = !hasVisibleChild[i]
            if (isVisibleRoot) {
                fxs[i] += BUOYANCY_K * (BUOYANCY_ANCHOR_X - xs[i])
                fys[i] += BUOYANCY_K * (BUOYANCY_ANCHOR_Y - ys[i])
            }
            if (isVisibleLeaf) fys[i] -= GRAVITY_K
            // Radial-outward "gravity" on every visible node: constant magnitude
            // OUTWARD_K, pointing away from the cluster centroid along the
            // node's position vector relative to that centroid. Inflates the
            // whole cluster from the inside rather than just pushing the leaf
            // fringe out. Skipped at the centroid (no well-defined direction).
            val rx = xs[i] - cx
            val ry = ys[i] - cy
            val r2 = rx * rx + ry * ry
            if (r2 > MIN_DIST2) {
                val invR = OUTWARD_K / sqrt(r2)
                fxs[i] += rx * invR
                fys[i] += ry * invR
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

        // All-pairs isotropic repulsion. Coulomb-like: magnitude k / d² along
        // the 2D line between the pair, so the force vector is k · Δ / d³.
        // Acts equally in x and y — siblings spread sideways and ancestor chains
        // feel mutual vertical push on top of the gravity/buoyancy stretch.
        // Living-living pairs get [LIVING_REPULSION_K] on top of the base
        // [REPULSION_K]. Coincident pairs are nudged along +x by the MIN_DIST
        // clamp so the singularity has a deterministic tiebreaker.
        for (i in 0 until n) {
            val xi = xs[i]
            val yi = ys[i]
            val li = isLiving[i]
            for (j in i + 1 until n) {
                var dx = xs[j] - xi
                var dy = ys[j] - yi
                var d2 = dx * dx + dy * dy
                if (d2 < MIN_DIST2) {
                    dx = MIN_DIST
                    dy = 0f
                    d2 = MIN_DIST2
                }
                val k = if (li && isLiving[j]) REPULSION_K + LIVING_REPULSION_K else REPULSION_K
                val invD3 = k / (d2 * sqrt(d2))
                val fx = dx * invD3
                val fy = dy * invD3
                fxs[i] -= fx
                fys[i] -= fy
                fxs[j] += fx
                fys[j] += fy
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

	val scaledMaxDisplacement = MAX_DISPLACEMENT*forceScale
	val scaledMaxDisplacement2 = scaledMaxDisplacement*scaledMaxDisplacement
        // Integrate: vel = damped(prev) + force; clamp displacement; advance position.
        for (i in 0 until n) {
            var vx = vxs[i] * DAMPING + fxs[i]
            var vy = vys[i] * DAMPING + fys[i]
            val vmag2 = vx * vx + vy * vy
            if (vmag2 > scaledMaxDisplacement2) {
                val s = scaledMaxDisplacement / sqrt(vmag2)
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

    companion object {
        /** Natural spring length between connected nodes, in logical units. Other
         *  spatial constants are expressed as multiples of this where it makes sense. */
        const val REST_LENGTH: Float = 0.0f
        // The ratios between SPRING_K, REPULSION_K, BUOYANCY_K, and GRAVITY_K set
        // the equilibrium shape (root depth, chain stretch, sibling spread); the
        // absolute scale sets how fast the system relaxes toward that shape.
        private const val SPRING_K: Float = 0.001f
        // Pairwise isotropic repulsion strength. Applied all-pairs as a 2D
        // Coulomb-like force (magnitude REPULSION_K / d², along the line
        // between the pair). Higher values spread the layout further apart
        // overall but also widen the zig-zag of connected pairs past REST_LENGTH.
        private const val REPULSION_K: Float = 0.000001f
        // Bonus repulsion applied ONLY between pairs where both nodes are
        // currently living, on top of the base REPULSION_K. Spreads alive
        // individuals further apart than dead ancestors do, making them easier
        // to track and click without disturbing the dead-skeleton structure.
        private const val LIVING_REPULSION_K: Float = 0.00000f
        // Constant radial-outward "gravity" applied to every visible node.
        // Each node feels a force of this magnitude pointing away from the
        // cluster centroid (mean position of all visible nodes). Inflates the
        // whole cluster from the inside; combined with springs holding the
        // edges and repulsion separating siblings, this tends to lay out the
        // tree radially with no preferred axis. Centering on all visible nodes
        // keeps Σ(pos − centroid) = 0 so the cluster only expands, never
        // drifts bodily.
        private const val OUTWARD_K: Float = 0.0001f
        // Vertical bias:
        //   - Visible roots (no visible parent) feel a 2D Hookean buoyancy
        //     spring pulling them back toward (BUOYANCY_ANCHOR_X,
        //     BUOYANCY_ANCHOR_Y). Equilibrium root displacement from the anchor
        //     is roughly chain_weight/BUOYANCY_K per axis, where chain weight
        //     accumulates GRAVITY_K and OUTWARD_K along visible descendants.
        //   - Only visible leaves (no visible child) feel a constant downward
        //     gravity. Each spring's tension is the sum of GRAVITY_K over the
        //     leaves below it: a spring connecting to a single leaf carries
        //     GRAVITY_K; a top-of-tree spring carries the full leaf count of its
        //     subtree. Stretch past REST_LENGTH is tension/SPRING_K. Bumping
        //     GRAVITY_K stretches the tree more; bumping SPRING_K keeps it tighter.
        private const val BUOYANCY_K: Float = 0.01f
        private const val BUOYANCY_ANCHOR_X: Float = 0f
        private const val BUOYANCY_ANCHOR_Y: Float = 0f
        private const val GRAVITY_K: Float = 0.0000f
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
        const val MIN_FORCE_SCALE: Float = 1f/4f
        const val MAX_FORCE_SCALE: Float = 256f
    }
}
