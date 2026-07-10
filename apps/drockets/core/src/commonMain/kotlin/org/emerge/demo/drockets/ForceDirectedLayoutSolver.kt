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
 * Persistent force-directed layout for the drockets cladogram, in 3D.
 *
 * Unlike [CladogramLayoutSolver] (which produces an immutable 2D result from scratch
 * each call), this solver retains a per-lineage `(x, y, z, vx, vy, vz)` between [step]
 * calls. Births inject a new node next to a parent along a Fibonacci-sphere direction
 * (so simultaneous siblings spread in 3D rather than landing coplanar); deaths leave
 * the node where it is; filter changes hide nodes from the simulation but do not
 * discard their saved positions. The renderer is responsible for projecting the 3D
 * positions to NDC via a fixed tilt + simple perspective.
 *
 * Forces per step (all in the same logical units as [CladogramLayoutSolver]):
 *  - **Edge spring** pulls connected nodes toward [REST_LENGTH] with stiffness
 *    [SPRING_K]. Springs both pull apart-too-close and pull together-too-far.
 *  - **All-pairs isotropic repulsion** pushes every visible pair apart along
 *    the 3D line between them with magnitude `F = k / d²` (Coulomb-like, force
 *    vector = `k · Δ / d³`). Acts equally in x, y, and z, so siblings spread in
 *    every direction and ancestor chains feel mutual push along all axes. The
 *    constant `k` is [REPULSION_K], with an optional `+ LIVING_REPULSION_K`
 *    bonus when both endpoints are currently alive. No spatial hash — O(n²),
 *    comfortable at visible-cladogram sizes (well under 1k nodes).
 *  - **Radial-outward "gravity" on visible leaves** ([OUTWARD_K]): a node
 *    with no visible children gets a constant-magnitude force pointing away
 *    from the **leaf centroid** (mean of visible-leaf positions, in 3D).
 *    Centering on exactly the force-receiving set (leaves only) keeps
 *    `Σ(leaf_pos − leaf_centroid) = 0`, so the leaves can only inflate
 *    outward (now forming a 3D shell rather than a 2D ring), not drift
 *    bodily. Skipped at the centroid (no defined direction); other forces
 *    nudge the leaf off on the next step.
 *  - **Hookean buoyancy on visible roots** ([BUOYANCY_K] · (anchor − pos)):
 *    a node with no visible parents is pulled toward
 *    ([BUOYANCY_ANCHOR_X], [BUOYANCY_ANCHOR_Y], [BUOYANCY_ANCHOR_Z]) by a
 *    3D Hookean spring. Combined with the leaf-only outward force, this
 *    hangs the tree from both ends: roots anchored inside, leaves pushed
 *    outside on a shell, middles settled by spring tension along the chain.
 *    The depth gradient ("inside is older, outside is younger") emerges
 *    from this topology rather than from any depth-aware force scaling.
 *  - **Gravity on visible leaves** ([GRAVITY_K], downward in y): currently
 *    0 — kept around as a tunable. Applies only to y, not z, so "down" still
 *    means down on the screen after projection.
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
     * Per-lineage state, packed as `[x, y, z, vx, vy, vz]` (6 floats). Retained across
     * [step] calls so that hidden nodes can return to the same place when filter mode
     * cycles back to a mode that includes them again. Entries are only dropped when
     * [DrocketLineageState] itself forgets the id (which shouldn't normally happen in
     * a running sim — nodes stay in `nodes` after death — but the cleanup keeps memory
     * bounded under snapshot replacement and similar wholesale changes).
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
    fun seedFrom(positions: Map<Long, Triple<Float, Float, Float>>) {
        for ((id, p) in positions) {
            if (state.containsKey(id)) continue
            state[id] = floatArrayOf(p.first, p.second, p.third, 0f, 0f, 0f)
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
    fun positionOf(id: Long): Triple<Float, Float, Float>? {
        val s = state[id] ?: return null
        return Triple(s[0], s[1], s[2])
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
    ): Map<Long, Triple<Float, Float, Float>> {
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
        val zs = FloatArray(n)
        val vxs = FloatArray(n)
        val vys = FloatArray(n)
        val vzs = FloatArray(n)
        val indexById = HashMap<Long, Int>(n)
        run {
            var idx = 0
            for (id in visibleIds) {
                val s = state[id] ?: continue
                ids[idx] = id
                xs[idx] = s[0]
                ys[idx] = s[1]
                zs[idx] = s[2]
                vxs[idx] = s[3]
                vys[idx] = s[4]
                vzs[idx] = s[5]
                indexById[id] = idx
                idx++
            }
        }

        val fxs = FloatArray(n)
        val fys = FloatArray(n)
        val fzs = FloatArray(n)

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

        // "Hung from both ends" radial bias:
        //   - Visible-root (no visible parent):  2D Hookean buoyancy toward
        //                                        (BUOYANCY_ANCHOR_X,
        //                                        BUOYANCY_ANCHOR_Y). Anchors
        //                                        the inside of the layout.
        //   - Visible-leaf (no visible child):   constant-magnitude radial
        //                                        push away from the leaf
        //                                        centroid (plus the existing
        //                                        downward gravity).
        //   - Middle node (has both):            no direct positional force;
        //                                        spring tension from roots
        //                                        (pulling inward) and leaves
        //                                        (pushing outward) settles it.
        // The depth gradient ("inside is older, outside is younger") emerges
        // from this topology — there's no force scaling by depth, the layout
        // does it itself through chain tension.
        //
        // Leaf centroid: mean of visible-leaf positions. Used as the origin
        // for the leaf outward force so Σ(leaf_pos − leaf_centroid) = 0 holds
        // exactly across the force-receiving set; the cluster can only inflate
        // radially around the leaves, not drift bodily.
        var leafCx = 0f
        var leafCy = 0f
        var leafCz = 0f
        var leafCount = 0
        for (i in 0 until n) {
            if (!hasVisibleChild[i]) {
                leafCx += xs[i]
                leafCy += ys[i]
                leafCz += zs[i]
                leafCount++
            }
        }
        if (leafCount > 0) {
            leafCx /= leafCount
            leafCy /= leafCount
            leafCz /= leafCount
        }

        for (i in 0 until n) {
            val node = lineage.nodes[ids[i]]
            val motherVisible = node?.motherLineageId?.let { indexById.containsKey(it) } ?: false
            val fatherVisible = node?.fatherLineageId?.let { indexById.containsKey(it) } ?: false
            val isVisibleRoot = !motherVisible && !fatherVisible
            val isVisibleLeaf = !hasVisibleChild[i]
            if (isVisibleRoot) {
                fxs[i] += BUOYANCY_K * (BUOYANCY_ANCHOR_X - xs[i])
                fys[i] += BUOYANCY_K * (BUOYANCY_ANCHOR_Y - ys[i])
                fzs[i] += BUOYANCY_K * (BUOYANCY_ANCHOR_Z - zs[i])
            }
            if (isVisibleLeaf) {
                fys[i] -= GRAVITY_K
                val rx = xs[i] - leafCx
                val ry = ys[i] - leafCy
                val rz = zs[i] - leafCz
                val r2 = rx * rx + ry * ry + rz * rz
                if (r2 > MIN_DIST2) {
                    val invR = OUTWARD_K / sqrt(r2)
                    fxs[i] += rx * invR
                    fys[i] += ry * invR
                    fzs[i] += rz * invR
                }
            }
        }

        // Spring forces along every edge whose endpoints are both visible.
        for ((from, to) in layout.edges) {
            val i = indexById[from] ?: continue
            val j = indexById[to] ?: continue
            var dx = xs[j] - xs[i]
            var dy = ys[j] - ys[i]
            var dz = zs[j] - zs[i]
            var d2 = dx * dx + dy * dy + dz * dz
            if (d2 < MIN_DIST2) {
                // Coincident seed (eg twin birth from same parent). Pick an arbitrary
                // axis so the spring resolves the degeneracy on the next step.
                dx = MIN_DIST
                dy = 0f
                dz = 0f
                d2 = MIN_DIST2
            }
            val d = sqrt(d2)
            val k = SPRING_K * (d - REST_LENGTH) / d
            fxs[i] += k * dx
            fys[i] += k * dy
            fzs[i] += k * dz
            fxs[j] -= k * dx
            fys[j] -= k * dy
            fzs[j] -= k * dz
        }

        // All-pairs isotropic repulsion. Coulomb-like: magnitude k / d² along
        // the 3D line between the pair, so the force vector is k · Δ / d³.
        // Acts equally in x, y, and z — siblings spread in every direction.
        // Living-living pairs get [LIVING_REPULSION_K] on top of the base
        // [REPULSION_K]. Coincident pairs are nudged along +x by the MIN_DIST
        // clamp so the singularity has a deterministic tiebreaker.
        for (i in 0 until n) {
            val xi = xs[i]
            val yi = ys[i]
            val zi = zs[i]
            val li = isLiving[i]
            for (j in i + 1 until n) {
                var dx = xs[j] - xi
                var dy = ys[j] - yi
                var dz = zs[j] - zi
                var d2 = dx * dx + dy * dy + dz * dz
                if (d2 < MIN_DIST2) {
                    dx = MIN_DIST
                    dy = 0f
                    dz = 0f
                    d2 = MIN_DIST2
                }
                val k = if (li && isLiving[j]) REPULSION_K + LIVING_REPULSION_K else REPULSION_K
                val invD3 = k / (d2 * sqrt(d2))
                val fx = dx * invD3
                val fy = dy * invD3
                val fz = dz * invD3
                fxs[i] -= fx
                fys[i] -= fy
                fzs[i] -= fz
                fxs[j] += fx
                fys[j] += fy
                fzs[j] += fz
            }
        }

        // Scale all accumulated forces by the runtime [forceScale]. Multiplying here
        // is equivalent to scaling SPRING_K / REPULSION_K / BUOYANCY_K / GRAVITY_K
        // individually, so equilibrium shape is unchanged — only relaxation rate.
        if (forceScale != 1f) {
            for (i in 0 until n) {
                fxs[i] *= forceScale
                fys[i] *= forceScale
                fzs[i] *= forceScale
            }
        }

        val scaledMaxDisplacement = MAX_DISPLACEMENT * forceScale
        val scaledMaxDisplacement2 = scaledMaxDisplacement * scaledMaxDisplacement
        // Integrate: vel = damped(prev) + force; clamp displacement; advance position.
        for (i in 0 until n) {
            var vx = vxs[i] * DAMPING + fxs[i]
            var vy = vys[i] * DAMPING + fys[i]
            var vz = vzs[i] * DAMPING + fzs[i]
            val vmag2 = vx * vx + vy * vy + vz * vz
            if (vmag2 > scaledMaxDisplacement2) {
                val s = scaledMaxDisplacement / sqrt(vmag2)
                vx *= s
                vy *= s
                vz *= s
            }
            xs[i] += vx
            ys[i] += vy
            zs[i] += vz
            vxs[i] = vx
            vys[i] = vy
            vzs[i] = vz
        }

        // Flush back into the persistent state and assemble the result map.
        val out = LinkedHashMap<Long, Triple<Float, Float, Float>>(n)
        for (i in 0 until n) {
            val s = state[ids[i]] ?: continue
            s[0] = xs[i]; s[1] = ys[i]; s[2] = zs[i]
            s[3] = vxs[i]; s[4] = vys[i]; s[5] = vzs[i]
            out[ids[i]] = Triple(xs[i], ys[i], zs[i])
        }

        lastStepMs = start.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f
        return out
    }

    /**
     * Seed a fresh node's position based on its visible parents:
     *  - Both parents visible: the exact midpoint between them in 3D. The repulsion
     *    and spring forces nudge the child onto a stable position over the next few
     *    steps — there's no reason to bias it toward one parent at birth.
     *  - One parent visible: offset from that parent by [SEED_OFFSET] along a
     *    Fibonacci-sphere direction keyed by id (so simultaneous siblings don't seed
     *    on top of each other and spread in 3D rather than coplanar).
     *  - Neither visible (lineage root or filter-orphaned): deterministic 3D spiral
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
                    (motherState[2] + fatherState[2]) * 0.5f,
                    0f, 0f, 0f,
                )
            }
            val soleParent = motherState ?: fatherState
            if (soleParent != null) {
                val dir = fibonacciSphereDir(id)
                return floatArrayOf(
                    soleParent[0] + dir[0] * SEED_OFFSET,
                    soleParent[1] + dir[1] * SEED_OFFSET,
                    soleParent[2] + dir[2] * SEED_OFFSET,
                    0f, 0f, 0f,
                )
            }
        }
        val dir = fibonacciSphereDir(id)
        val r = SEED_OFFSET + ((id and 0xFFFFL).toInt() % 8) * SEED_OFFSET * 0.25f
        return floatArrayOf(dir[0] * r, dir[1] * r, dir[2] * r, 0f, 0f, 0f)
    }

    /**
     * Deterministic 3D unit vector keyed by [id], distributed on the sphere via a
     * Fibonacci spiral. Adjacent ids end up well-separated in all three axes, which
     * keeps the seed direction from collapsing onto a 2D plane when many births land
     * on the same parent in the same step.
     */
    private fun fibonacciSphereDir(id: Long): FloatArray {
        val i = (id and 0xFFFFL).toInt()
        val phi = i * GOLDEN_ANGLE_RAD
        // Use the fractional part of i * golden-ratio as a deterministic latitude
        // fraction in [0, 1); map to z ∈ [-1, 1].
        val frac = ((i * 0.61803398f) - kotlin.math.floor(i * 0.61803398f))
        val zn = 2f * frac - 1f
        val r = sqrt((1f - zn * zn).coerceAtLeast(0f))
        return floatArrayOf(r * cos(phi), r * sin(phi), zn)
    }

    companion object {
        /** Natural spring length between connected nodes, in logical units. Other
         *  spatial constants are expressed as multiples of this where it makes sense. */
        const val REST_LENGTH: Float = 0.0f
        // The ratios between SPRING_K, REPULSION_K, BUOYANCY_K, and GRAVITY_K set
        // the equilibrium shape (root depth, chain stretch, sibling spread); the
        // absolute scale sets how fast the system relaxes toward that shape.
        private const val SPRING_K: Float = 0.01f
        // Pairwise isotropic repulsion strength. Applied all-pairs as a 2D
        // Coulomb-like force (magnitude REPULSION_K / d², along the line
        // between the pair). Higher values spread the layout further apart
        // overall but also widen the zig-zag of connected pairs past REST_LENGTH.
        private const val REPULSION_K: Float = 0.0001f
        // Bonus repulsion applied ONLY between pairs where both nodes are
        // currently living, on top of the base REPULSION_K. Spreads alive
        // individuals further apart than dead ancestors do, making them easier
        // to track and click without disturbing the dead-skeleton structure.
        private const val LIVING_REPULSION_K: Float = 0.00000f
        // Constant radial-outward "gravity" applied to visible LEAVES only
        // (nodes with no visible child). Each leaf feels a force of this
        // magnitude pointing away from the leaf centroid (mean of visible-
        // leaf positions). Pulls the tips of the tree out into a ring;
        // middles are dragged along via spring tension, roots stay anchored
        // by buoyancy — giving the radial "inside is older, outside is
        // younger" gradient from topology alone. Centering on leaves keeps
        // Σ(leaf_pos − leaf_centroid) = 0 so the cluster doesn't drift.
        private const val OUTWARD_K: Float = 0.01f
        // Bias:
        //   - Visible roots (no visible parent) feel a 2D Hookean buoyancy
        //     spring toward (BUOYANCY_ANCHOR_X, BUOYANCY_ANCHOR_Y). Anchors
        //     the inside of the radial layout.
        //   - Only visible leaves (no visible child) feel a constant downward
        //     gravity. (Currently 0 — kept around as a tunable.)
        //   - Middles are settled by spring tension propagated along the
        //     chain. Bumping GRAVITY_K stretches the tree more; bumping
        //     SPRING_K keeps it tighter.
        private const val BUOYANCY_K: Float = 0.002f
        private const val BUOYANCY_ANCHOR_X: Float = 0f
        private const val BUOYANCY_ANCHOR_Y: Float = 0f
        private const val BUOYANCY_ANCHOR_Z: Float = 0f
        private const val GRAVITY_K: Float = 0.0000f
        private const val DAMPING: Float = 0.01f
        // Safety clamp on per-step displacement. Catches degenerate force spikes
        // (eg perfectly coincident seed positions) without affecting normal motion.
        private const val MAX_DISPLACEMENT: Float = 0.01f
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
