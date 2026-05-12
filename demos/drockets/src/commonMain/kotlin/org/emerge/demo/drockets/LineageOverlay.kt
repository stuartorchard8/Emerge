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

    /** Cycles between [CladogramFilterMode.ALL] and [CladogramFilterMode.LIVING_ONLY]
     *  only — the LIVING_AND_PARENTS / LIVING_AND_CONNECTORS modes from the older
     *  panel aren't part of this view's vocabulary. Returns the new mode. */
    fun cycleFilter(): CladogramFilterMode {
        filter = if (filter == CladogramFilterMode.ALL) {
            CladogramFilterMode.LIVING_ONLY
        } else {
            CladogramFilterMode.ALL
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
            CladogramFilterMode.ALL -> "FILTER ALL (F6)"
            CladogramFilterMode.LIVING_ONLY -> "FILTER LIVING ONLY (F6)"
            else -> "FILTER (other)"
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

        drawEdges(frame, positions, ::toNdc)
        drawNodes(frame, positions, ::toNdc)

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
            val bothLiving = from in living && to in living
            // Edge tint: blend toward parent body colour at moderate alpha when both ends
            // are living (to show the living "trunk"), otherwise a muted gray.
            val (r, g, b, a) = when {
                bothLiving -> {
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
        toNdc: (Pair<Float, Float>) -> Pair<Float, Float>,
    ) {
        val living = frame.lineage.livingLineageIds
        val baseR = nodeBaseRadius(positions.size)
        ensureNodeArrays(positions.size)
        var n = 0
        for ((id, logical) in positions) {
            val (nx, ny) = toNdc(logical)
            val isAlive = id in living
            val isSelected = id == selectedLineageId
            val isHovered = id == hoveredLineageId

            val rgb = bodyRgb(frame.lineage.nodes[id]?.genome)
            val (r, g, b) = if (isAlive) {
                Triple(rgb.first, rgb.second, rgb.third)
            } else {
                // Dim dead nodes to ~35% of body colour so colour identity survives but
                // they read clearly as background.
                Triple(rgb.first * 0.35f, rgb.second * 0.35f, rgb.third * 0.35f)
            }

            val ringFrac = when {
                isSelected -> 0.30f
                isHovered -> 0.20f
                else -> 0f
            }
            val radius = baseR * when {
                isSelected -> 1.55f
                isHovered -> 1.25f
                isAlive -> 1.0f
                else -> 0.78f
            }

            val base2 = n * 2
            nodeCenters[base2] = nx
            nodeCenters[base2 + 1] = ny
            nodeRadii[n] = radius
            val base4 = n * 4
            nodeColors[base4] = r
            nodeColors[base4 + 1] = g
            nodeColors[base4 + 2] = b
            nodeColors[base4 + 3] = if (isAlive) 0.95f else 0.78f
            nodeRings[n] = ringFrac
            n++
        }
        if (n > 0) {
            nodeShader.drawInstanced(n, nodeCenters, nodeRadii, nodeColors, nodeRings)
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
