package org.emerge.demo.drockets

import org.emerge.render.torus.GPU
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.Vec2
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Alternative lineage view: a full-screen translucent overlay rendering nodes as
 * filled coloured discs (real geometry, not line-segment fakes), with mouse-driven
 * pan + zoom-around-cursor and click-select decoupled from world-camera focus.
 *
 * Sits alongside [CladogramPanelRenderer] — the two are mutually exclusive in
 * [DrocketsRenderer]; toggling one off when the other comes on lives at the
 * composite layer.
 *
 * Inputs the composite calls into:
 *   - [toggleActive] — F4 in the default binding.
 *   - [cycleFilter] — ALL <-> LIVING_ONLY only; the connector heuristics from
 *     the older view aren't included.
 *   - [panByPixels] — call this every frame while a primary-button drag is held.
 *   - [zoomAtCursor] — call this on wheel; zooms around the cursor position
 *     (mathematically: the world point under the cursor stays under the cursor).
 *   - [pickAt] — single click; selects a node without disturbing the world camera.
 *   - [focusLivingDrocketAt] — double click; returns the [EntityId] of a living
 *     drocket whose lineage node was clicked, so the composite can call
 *     [WorldRenderer.focusOn]. Selection inspect and world focus are independent.
 *   - [updateHover] — mouse move while overlay is active.
 *
 * Layout reuses the shared [CladogramLayoutSolver] (the same one
 * [CladogramPanelRenderer] uses); the difference is purely in how the laid-out
 * positions are projected to screen and drawn.
 */
class LineageOverlay {
    private val nodeShader = LineageNodeShader()
    private val edgeShader = CladogramLineShader()

    // Scratch buffers (reallocated on growth, not per frame).
    private var nodeCenters = FloatArray(LineageNodeShader.MAX_INSTANCES * 2)
    private var nodeRadii = FloatArray(LineageNodeShader.MAX_INSTANCES)
    private var nodeColors = FloatArray(LineageNodeShader.MAX_INSTANCES * 4)
    private var nodeRings = FloatArray(LineageNodeShader.MAX_INSTANCES)

    private var edgeEnds = FloatArray(CladogramLineShader.MAX_LINE_INSTANCES * 4)
    private var edgeCols = FloatArray(CladogramLineShader.MAX_LINE_INSTANCES * 4)

    @Volatile var active: Boolean = false
        private set
    @Volatile var filter: CladogramFilterMode = CladogramFilterMode.ALL
        private set

    private var selectedLineageId: Long? = null
    private var hoveredLineageId: Long? = null

    // View state — `pan` is in NDC; `zoom` is a unitless multiplier on logical x/y.
    private var pan: Vec2 = Vec2(0f, 0f)
    private var zoom: Float = 1f

    private var resolution: Vec2 = Vec2(1f, 1f)
    private var lastTotalMs: Float = 0f
    private var solveCache: SolveCache? = null

    fun setResolution(res: Vec2) { resolution = res }

    /** Returns the new active state. The composite is responsible for deactivating
     *  any peer view (eg the older cladogram panel) when this is turned on. */
    fun toggleActive(): Boolean {
        active = !active
        if (!active) {
            selectedLineageId = null
            hoveredLineageId = null
        }
        return active
    }

    /**
     * Cycles between three modes: ALL → LIVING_ONLY → MRCA (LIVING_AND_CONNECTORS) → ALL.
     *
     * MRCA is the minimal connecting subgraph of all currently-living lineages — every
     * living drocket plus the smallest set of ancestors needed to wire them together,
     * with sibling lines that contain no living descendants pruned out. It's the
     * sharpest baseline view for assessing relatedness: anything visible is on a path
     * between two living drockets (or is itself living).
     */
    fun cycleFilter(): CladogramFilterMode {
        filter = when (filter) {
            CladogramFilterMode.ALL -> CladogramFilterMode.LIVING_ONLY
            CladogramFilterMode.LIVING_ONLY -> CladogramFilterMode.LIVING_AND_CONNECTORS
            else -> CladogramFilterMode.ALL
        }
        invalidateLayoutCache()
        return filter
    }

    fun panByPixels(dxPx: Float, dyPx: Float) {
        if (!active) return
        val w = resolution.x.coerceAtLeast(1f)
        val h = resolution.y.coerceAtLeast(1f)
        // Pixel deltas → NDC. Negate y because screen Y grows downward but NDC y grows up.
        pan = Vec2(pan.x + dxPx * 2f / w, pan.y - dyPx * 2f / h)
    }

    /** Zooms around the pixel under the cursor: the logical point currently displayed
     *  at that pixel stays at that pixel after the zoom. */
    fun zoomAtCursor(cursorPx: Vec2, factor: Float) {
        if (!active || !factor.isFinite() || factor <= 0f) return
        val cursorNdc = pixelToNdc(cursorPx)
        val logicalBefore = ndcToLogical(cursorNdc)
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Recompute pan so the same logical point lands at the same NDC.
        pan = Vec2(
            cursorNdc.x - logicalBefore.x * zoom,
            cursorNdc.y - logicalBefore.y * zoom,
        )
    }

    /** Single-click selection. Doesn't disturb the world camera. */
    fun pickAt(pixel: Vec2, frame: DrocketsFrame): Long? {
        if (!active) return null
        val hit = hitTest(pixel, frame)
        selectedLineageId = hit
        return hit
    }

    /** Double-click: if the clicked node is a living drocket, return its [EntityId]
     *  so the composite can focus the world camera. */
    fun focusLivingDrocketAt(pixel: Vec2, frame: DrocketsFrame): EntityId? {
        if (!active) return null
        val hit = hitTest(pixel, frame) ?: return null
        selectedLineageId = hit
        if (!frame.lineage.livingLineageIds.contains(hit)) return null
        val entityValue = frame.lineage.entityToLineageId.entries.firstOrNull { it.value == hit }?.key
        return entityValue?.let(::EntityId)
    }

    fun updateHover(pixel: Vec2?, frame: DrocketsFrame) {
        if (!active) { hoveredLineageId = null; return }
        hoveredLineageId = pixel?.let { hitTest(it, frame) }
    }

    /**
     * Builds the lines this view contributes to the HUD: filter mode, selection /
     * hover info, and a one-line solve+draw timing.
     */
    fun hudLines(frame: DrocketsFrame): List<String> {
        if (!active) return emptyList()
        val out = mutableListOf<String>()
        out += "LINEAGE OVERLAY  ${frame.cladogramLayout.summaryLine()}"
        out += when (filter) {
            CladogramFilterMode.ALL -> "FILTER ALL (F8)"
            CladogramFilterMode.LIVING_ONLY -> "FILTER LIVING ONLY (F8)"
            CladogramFilterMode.LIVING_AND_CONNECTORS -> "FILTER MRCA (F8)"
            else -> "FILTER $filter (F8)"
        }
        // Hover takes priority over selection for the active read-out — it's the more
        // ephemeral channel — but both are shown when distinct.
        val hover = hoveredLineageId?.let { frame.lineage.nodes[it]?.let { _ -> it } }
        val sel = selectedLineageId?.let { frame.lineage.nodes[it]?.let { _ -> it } }
        if (hover != null && hover != sel) {
            out += hudLineFor(hover, frame, prefix = "HOVER ")
        }
        if (sel != null) {
            out += hudLineFor(sel, frame, prefix = "SEL ")
        }
        if (lastTotalMs > 0f) {
            val n = (lastTotalMs * 100f).toLong()
            out += "OVERLAY ${n / 100}.${(if (n % 100 < 10) "0" else "") + (n % 100)}MS"
        }
        return out
    }

    private fun hudLineFor(id: Long, frame: DrocketsFrame, prefix: String): String {
        val node = frame.lineage.nodes[id] ?: return "${prefix}LIN $id"
        val alive = frame.lineage.livingLineageIds.contains(id)
        val depth = frame.cladogramLayout.depthById[id] ?: 0
        return "${prefix}LIN $id ${node.sex} ${if (alive) "LIVE" else "DEAD"} D$depth"
    }

    fun draw(frame: DrocketsFrame) {
        if (!active) return
        val totalStart = TimeSource.Monotonic.markNow()
        val solution = getSolveResult(frame)
        val positions = solution.positions
        if (positions.isEmpty()) {
            // Even with no positions, dim the world so the toggle is visible.
            drawDimBackdrop()
            return
        }

        GPU.enableBlend()
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        drawDimBackdrop()

        // Logical -> NDC projection: x and y are in node-spacing units; we centre on
        // origin (the solver returns positions centred around the centroid already)
        // and apply the user's pan + zoom.
        fun toNdc(logical: Pair<Float, Float>): Pair<Float, Float> {
            val px = logical.first * zoom + pan.x
            val py = logical.second * zoom + pan.y
            return px to py
        }

        val highlight = computeHighlight(frame, positions)
        drawEdges(frame, positions, highlight, ::toNdc)
        drawNodes(frame, positions, highlight, ::toNdc)

        GPU.disableBlend()
        lastTotalMs = totalStart.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f
    }

    fun cleanup() {
        nodeShader.delete()
        edgeShader.delete()
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private fun drawDimBackdrop() {
        // Single big disc covering everything, half-transparent black. Cheap and uses
        // the same shader as nodes; no extra GL state plumbing.
        nodeCenters[0] = 0f
        nodeCenters[1] = 0f
        nodeRadii[0] = 4f // well past NDC bounds
        nodeColors[0] = 0f; nodeColors[1] = 0f; nodeColors[2] = 0f; nodeColors[3] = 0.65f
        nodeRings[0] = 0f
        nodeShader.drawInstanced(1, nodeCenters, nodeRadii, nodeColors, nodeRings)
    }

    private fun drawEdges(
        frame: DrocketsFrame,
        positions: Map<Long, Pair<Float, Float>>,
        highlight: HighlightState,
        toNdc: (Pair<Float, Float>) -> Pair<Float, Float>,
    ) {
        val edges = frame.cladogramLayout.edges
        ensureEdgeArrays(edges.size)
        val living = frame.lineage.livingLineageIds
        var n = 0
        for ((from, to) in edges) {
            val pf = positions[from] ?: continue
            val pt = positions[to] ?: continue
            val (fx, fy) = toNdc(pf)
            val (tx, ty) = toNdc(pt)
            val base = n * 4
            edgeEnds[base] = fx
            edgeEnds[base + 1] = fy
            edgeEnds[base + 2] = tx
            edgeEnds[base + 3] = ty

            val ancFrom = highlight.ancestors[from]
            val ancTo = highlight.ancestors[to]
            val descFrom = highlight.descendants[from]
            val descTo = highlight.descendants[to]
            val onAncestorPath = ancFrom != null &&
                ((ancTo != null && ancFrom == ancTo + 1) || (highlight.selected == to && ancFrom == 1))
            val onDescendantPath = descTo != null &&
                ((descFrom != null && descTo == descFrom + 1) || (highlight.selected == from && descTo == 1))

            val (r, g, b, a) = when {
                onAncestorPath -> {
                    val rgb = ancestorColor(ancFrom!!)
                    floatArrayOf(rgb.first, rgb.second, rgb.third, 0.92f)
                }
                onDescendantPath -> {
                    val rgb = descendantColor(descTo!!)
                    floatArrayOf(rgb.first, rgb.second, rgb.third, 0.92f)
                }
                highlight.active -> {
                    // Edge is in a highlight-active frame but not on the selected lineage's
                    // path — fade it to a very low-alpha gray so unrelated structure recedes.
                    floatArrayOf(0.40f, 0.42f, 0.46f, 0.12f)
                }
                from in living && to in living -> {
                    // Living "trunk" line — tinted by the parent's body colour at moderate alpha.
                    val rgb = bodyRgb(frame.lineage.nodes[from]?.genome)
                    floatArrayOf(rgb.first, rgb.second, rgb.third, 0.55f)
                }
                else -> floatArrayOf(0.55f, 0.58f, 0.62f, 0.35f)
            }
            edgeCols[base] = r
            edgeCols[base + 1] = g
            edgeCols[base + 2] = b
            edgeCols[base + 3] = a
            n++
        }
        if (n > 0) {
            edgeShader.drawLineSegmentsInstanced(edgeEnds, edgeCols, n)
        }
    }

    private fun drawNodes(
        frame: DrocketsFrame,
        positions: Map<Long, Pair<Float, Float>>,
        highlight: HighlightState,
        toNdc: (Pair<Float, Float>) -> Pair<Float, Float>,
    ) {
        val living = frame.lineage.livingLineageIds
        val baseR = nodeBaseRadius(positions.size)
        ensureNodeArrays(positions.size)
        var n = 0
        for ((id, logical) in positions) {
            val (nx, ny) = toNdc(logical)
            val isAlive = id in living
            val isSelected = id == highlight.selected
            val isHovered = id == hoveredLineageId
            val ancDist = highlight.ancestors[id]
            val descDist = highlight.descendants[id]
            val isRelated = isSelected || ancDist != null || descDist != null

            // Colour priority: selected → ancestor gradient → descendant gradient → muted
            // gray for unrelated-when-highlighting → body colour (dimmed if dead).
            val rgb = when {
                isSelected -> Triple(1.0f, 0.95f, 0.40f)
                ancDist != null -> ancestorColor(ancDist)
                descDist != null -> descendantColor(descDist)
                highlight.active -> Triple(0.42f, 0.45f, 0.50f)
                else -> bodyRgb(frame.lineage.nodes[id]?.genome).let {
                    if (isAlive) it else Triple(it.first * 0.35f, it.second * 0.35f, it.third * 0.35f)
                }
            }

            val alpha = when {
                isSelected -> 1.0f
                highlight.active && !isRelated -> 0.16f
                isAlive -> 0.95f
                else -> 0.78f
            }

            val ringFrac = when {
                isSelected -> 0.30f
                isHovered -> 0.20f
                else -> 0f
            }
            // Use absolute (not multiplicative) priority so hovering an ancestor still
            // visibly bumps the radius even though "ancestor" already scales it.
            var radius = baseR * if (isAlive) 1.0f else 0.78f
            if (isRelated) radius = maxOf(radius, baseR * 1.15f)
            if (isHovered) radius = maxOf(radius, baseR * 1.25f)
            if (isSelected) radius = maxOf(radius, baseR * 1.55f)

            val base2 = n * 2
            nodeCenters[base2] = nx
            nodeCenters[base2 + 1] = ny
            nodeRadii[n] = radius
            val base4 = n * 4
            nodeColors[base4] = rgb.first
            nodeColors[base4 + 1] = rgb.second
            nodeColors[base4 + 2] = rgb.third
            nodeColors[base4 + 3] = alpha
            nodeRings[n] = ringFrac
            n++
        }
        if (n > 0) {
            nodeShader.drawInstanced(n, nodeCenters, nodeRadii, nodeColors, nodeRings)
        }
    }

    /**
     * Selection-driven highlight state. When a node is selected and laid out, computes
     * the per-ancestor and per-descendant graph distance via BFS over the *currently
     * visible* edge set so the highlight respects the active filter. When no selection,
     * or the selected lineage was filtered out, returns an inactive state and the rest
     * of the renderer falls back to its normal colouring.
     */
    private fun computeHighlight(
        frame: DrocketsFrame,
        positions: Map<Long, Pair<Float, Float>>,
    ): HighlightState {
        val sel = selectedLineageId ?: return HighlightState.INACTIVE
        if (sel !in positions) return HighlightState.INACTIVE
        val children = HashMap<Long, MutableList<Long>>()
        val parents = HashMap<Long, MutableList<Long>>()
        for ((from, to) in frame.cladogramLayout.edges) {
            if (!positions.containsKey(from) || !positions.containsKey(to)) continue
            children.getOrPut(from) { mutableListOf() }.add(to)
            parents.getOrPut(to) { mutableListOf() }.add(from)
        }
        return HighlightState(
            active = true,
            ancestors = bfsDistances(sel, parents),
            descendants = bfsDistances(sel, children),
            selected = sel,
        )
    }

    private fun bfsDistances(seed: Long, adjacency: Map<Long, List<Long>>): Map<Long, Int> {
        val out = LinkedHashMap<Long, Int>()
        val queue = ArrayDeque<Long>()
        queue.addLast(seed)
        out[seed] = 0
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val curDist = out[cur] ?: continue
            for (next in adjacency[cur].orEmpty()) {
                if (out.containsKey(next)) continue
                out[next] = curDist + 1
                queue.addLast(next)
            }
        }
        out.remove(seed)
        return out
    }

    private fun ancestorColor(distance: Int): Triple<Float, Float, Float> {
        val t = ((distance - 1).toFloat() / 6f).coerceIn(0f, 1f)
        // gold (1.0, 0.78, 0.24) -> red (1.0, 0.16, 0.16)
        return Triple(1.0f, 0.78f + (0.16f - 0.78f) * t, 0.24f + (0.16f - 0.24f) * t)
    }

    private fun descendantColor(distance: Int): Triple<Float, Float, Float> {
        val t = ((distance - 1).toFloat() / 6f).coerceIn(0f, 1f)
        // cyan (0.28, 0.92, 1.0) -> blue (0.14, 0.34, 1.0)
        return Triple(0.28f + (0.14f - 0.28f) * t, 0.92f + (0.34f - 0.92f) * t, 1.0f)
    }

    private data class HighlightState(
        val active: Boolean,
        val ancestors: Map<Long, Int>,
        val descendants: Map<Long, Int>,
        val selected: Long?,
    ) {
        companion object {
            val INACTIVE = HighlightState(false, emptyMap(), emptyMap(), null)
        }
    }

    /** Picks the topmost (smallest distance) node within hit radius at [pixel]. */
    private fun hitTest(pixel: Vec2, frame: DrocketsFrame): Long? {
        val positions = getSolveResult(frame).positions
        if (positions.isEmpty()) return null
        val ndc = pixelToNdc(pixel)
        val baseR = nodeBaseRadius(positions.size)
        // Be a bit generous on the hit radius (1.5x) so single-pixel-precision isn't required.
        val hitR = baseR * 1.5f
        val hitR2 = hitR * hitR

        var best: Long? = null
        var bestD2 = Float.POSITIVE_INFINITY
        for ((id, logical) in positions) {
            val nx = logical.first * zoom + pan.x
            val ny = logical.second * zoom + pan.y
            val dx = ndc.x - nx
            val dy = ndc.y - ny
            val d2 = dx * dx + dy * dy
            if (d2 <= hitR2 && d2 < bestD2) {
                bestD2 = d2
                best = id
            }
        }
        return best
    }

    private fun pixelToNdc(pixel: Vec2): Vec2 {
        val w = resolution.x.coerceAtLeast(1f)
        val h = resolution.y.coerceAtLeast(1f)
        return Vec2(pixel.x / w * 2f - 1f, 1f - pixel.y / h * 2f)
    }

    private fun ndcToLogical(ndc: Vec2): Vec2 =
        Vec2((ndc.x - pan.x) / zoom, (ndc.y - pan.y) / zoom)

    private fun nodeBaseRadius(nodeCount: Int): Float {
        // Scale the base radius gently with population so dense lineages don't visually
        // overlap. The 0.012/0.018 thresholds match the older panel's behaviour roughly.
        val populationFactor = if (nodeCount > 420) 0.012f else 0.018f
        // Also clamp to spacing-based limit so closely-packed siblings don't merge.
        val spacingLimit = CladogramLayoutSolver.NODE_X_SPACING * zoom * 0.45f
        return (populationFactor * zoom).coerceIn(0.004f, spacingLimit.coerceAtLeast(0.004f))
    }

    private fun ensureNodeArrays(n: Int) {
        if (nodeCenters.size >= n * 2) return
        val cap = maxOf(n * 2, nodeCenters.size * 2)
        nodeCenters = FloatArray(cap)
        nodeRadii = FloatArray(cap / 2)
        nodeColors = FloatArray(cap * 2)
        nodeRings = FloatArray(cap / 2)
    }

    private fun ensureEdgeArrays(n: Int) {
        if (edgeEnds.size >= n * 4) return
        val cap = maxOf(n * 4, edgeEnds.size * 2)
        edgeEnds = FloatArray(cap)
        edgeCols = FloatArray(cap)
    }

    private fun bodyRgb(genome: Genome?): Triple<Float, Float, Float> {
        if (genome == null) return Triple(0.85f, 0.88f, 0.92f)
        return genome.phenotype().bodyColor.toRgb()
    }

    // ── Layout solve cache (much smaller key than the older panel) ─────────────

    private data class SolveCache(
        val versionStamp: Long,
        val filter: CladogramFilterMode,
        val solution: CladogramLayoutSolution,
    )

    private fun invalidateLayoutCache() { solveCache = null }

    private fun getSolveResult(frame: DrocketsFrame): CladogramLayoutSolution {
        val stamp = lineageVersionStamp(frame.lineage)
        val cached = solveCache
        if (cached != null && cached.versionStamp == stamp && cached.filter == filter) {
            return cached.solution
        }
        val solved = CladogramLayoutSolver.solve(
            layout = frame.cladogramLayout,
            lineage = frame.lineage,
            filterMode = filter,
            seedLogicalPositions = cached?.solution?.positions,
        )
        solveCache = SolveCache(stamp, filter, solved)
        return solved
    }

    /**
     * Lineage version stamp: a cheap integer summary of [lineage] that changes whenever
     * something the layout cares about changes. Replaces the older panel's per-frame
     * edge+depth hash with a constant-time signature.
     */
    private fun lineageVersionStamp(lineage: DrocketLineageState): Long {
        // nextLineageId monotonically increases on every birth and only on births, so
        // it uniquely identifies the set of nodes. livingLineageIds.size + total node
        // count catches death events too (which don't move nextLineageId).
        val births = lineage.nextLineageId
        val living = lineage.livingLineageIds.size.toLong()
        val total = lineage.nodes.size.toLong()
        return births * 1_000_003L + living * 31L + total
    }

    companion object {
        private const val MIN_ZOOM = 0.25f
        private const val MAX_ZOOM = 8f
    }
}
