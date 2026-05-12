package org.emerge.demo.drockets

import org.emerge.render.torus.GPU
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.Vec2
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * Per-stage timings captured while rendering one cladogram frame, in milliseconds.
 * Surfaced to the HUD when [CladogramPanelRenderer.profilingOn] is true.
 */
data class CladogramProfile(
    val keyMs: Float = 0f,
    val filterMs: Float = 0f,
    val solveMs: Float = 0f,
    val projectMs: Float = 0f,
    val layoutMs: Float = 0f,
    val adjMs: Float = 0f,
    val edgesMs: Float = 0f,
    val nodesMs: Float = 0f,
    val highlightMs: Float = 0f,
    val totalMs: Float = 0f,
)

/**
 * Renders a half-screen cladogram panel (right half) on top of the world.
 *
 * Owns its own viewport state ([panelZoom], [panelPanX], [panelPanY]), filter mode,
 * selection, and per-frame profiling. Drawing is scissor-clipped to the right half;
 * the caller composes this with [WorldRenderer] and [OverlayHud] but isn't aware of
 * the cladogram's internals.
 *
 * Layout caching keys off node/edge/depth/living-set hashes plus the filter mode, so
 * repeated frames without lineage changes skip the (expensive) Sugiyama-style x-axis
 * solve and reuse cached logical positions.
 */
class CladogramPanelRenderer {
    private val cladogramLineShader = CladogramLineShader()
    private val cladoLineScratch = FloatArray(CladogramLineShader.CLADO_MAX_FLOATS)
    private var cladoEdgeEnds = FloatArray(CladogramLineShader.MAX_LINE_INSTANCES * 4)
    private var cladoEdgeCols = FloatArray(CladogramLineShader.MAX_LINE_INSTANCES * 4)

    @Volatile var panelOn: Boolean = false
        private set
    @Volatile var filterMode: CladogramFilterMode = CladogramFilterMode.ALL
        private set
    @Volatile var profilingOn: Boolean = true
        private set

    private var selectedLineageId: Long? = null
    private var panelZoom: Float = 1f
    private var panelPanX: Float = 0f
    private var panelPanY: Float = 0f
    private var solveCache: SolveCache? = null

    private var resolution: Vec2 = Vec2(1f, 1f)
    private var lastProfile: CladogramProfile = CladogramProfile()

    fun setResolution(res: Vec2) { resolution = res }

    /** Returns the new on/off state after toggle. */
    fun togglePanel(): Boolean {
        panelOn = !panelOn
        if (!panelOn) {
            selectedLineageId = null
        } else {
            panelZoom = 1f
            panelPanX = 0f
            panelPanY = 0f
        }
        invalidateLayoutCache()
        return panelOn
    }

    /** Advances filter mode through ALL → LIVING_ONLY → LIVING_AND_PARENTS → LIVING_AND_CONNECTORS → ALL. */
    fun cycleFilter(): CladogramFilterMode {
        filterMode = when (filterMode) {
            CladogramFilterMode.ALL -> CladogramFilterMode.LIVING_ONLY
            CladogramFilterMode.LIVING_ONLY -> CladogramFilterMode.LIVING_AND_PARENTS
            CladogramFilterMode.LIVING_AND_PARENTS -> CladogramFilterMode.LIVING_AND_CONNECTORS
            CladogramFilterMode.LIVING_AND_CONNECTORS -> CladogramFilterMode.ALL
        }
        invalidateLayoutCache()
        return filterMode
    }

    fun toggleProfiling(): Boolean {
        profilingOn = !profilingOn
        return profilingOn
    }

    fun panBy(dxLayout: Float, dyLayout: Float) {
        if (!panelOn) return
        panelPanX += dxLayout
        panelPanY += dyLayout
    }

    /** @return true if the wheel was applied to the panel (caller should swallow it). */
    fun handleWheel(pixelX: Float, framebufferW: Float, factor: Float): Boolean {
        if (!panelOn || framebufferW <= 0f) return false
        if (pixelX < framebufferW * 0.5f) return false
        panelZoom = (panelZoom * factor).coerceIn(0.22f, 4.5f)
        return true
    }

    /**
     * Hit-tests a click against laid-out lineage nodes on the right panel.
     * @return the entity to focus on (if the picked node is a living drocket), or null.
     */
    fun tryPick(frame: DrocketsFrame, pixel: Vec2): EntityId? {
        val panelPositions = getPanelLayout(frame).positions
        if (panelPositions.isEmpty()) {
            selectedLineageId = null
            return null
        }
        val fbW = resolution.x
        val fbH = resolution.y
        if (fbW <= 0f || fbH <= 0f) return null

        val mx = ((pixel.x - fbW * 0.5f) / (fbW * 0.5f)).coerceIn(0f, 1f)
        val my = (1f - pixel.y / fbH).coerceIn(0f, 1f)
        val hitR = (0.030f / panelZoom.coerceAtLeast(0.22f)).coerceIn(0.010f, 0.090f)

        var best: Long? = null
        var bestD = Float.POSITIVE_INFINITY
        for ((id, pos) in panelPositions) {
            val dx = pos.first - mx
            val dy = pos.second - my
            val d = dx * dx + dy * dy
            if (d < bestD && d <= hitR * hitR) {
                bestD = d
                best = id
            }
        }

        selectedLineageId = best
        val lineage = frame.lineage
        if (best != null && lineage.livingLineageIds.contains(best)) {
            val entityValue = lineage.entityToLineageId.entries.firstOrNull { it.value == best }?.key
            if (entityValue != null) return EntityId(entityValue)
        }
        return null
    }

    /** Last-frame timings, intended for HUD readout. */
    fun lastProfile(): CladogramProfile = lastProfile

    /**
     * Builds the HUD lines describing current cladogram state (filter mode, profiling
     * timings, selected lineage). Empty list if the panel isn't visible.
     */
    fun hudSummaryLines(frame: DrocketsFrame): List<String> {
        if (!panelOn) return emptyList()
        val out = mutableListOf<String>()
        out += frame.cladogramLayout.summaryLine()
        out += when (filterMode) {
            CladogramFilterMode.ALL -> "FILTER ALL (F6)"
            CladogramFilterMode.LIVING_ONLY -> "FILTER LIVING ONLY (F6)"
            CladogramFilterMode.LIVING_AND_PARENTS -> "FILTER LIVING + PARENTS (F6)"
            CladogramFilterMode.LIVING_AND_CONNECTORS -> "FILTER LIVING + CONNECTORS (F6)"
        }
        if (profilingOn) {
            val p = lastProfile
            out += "CLADO MS K${fmt2(p.keyMs)} F${fmt2(p.filterMs)} " +
                "S${fmt2(p.solveMs)} P${fmt2(p.projectMs)} " +
                "L${fmt2(p.layoutMs)} A${fmt2(p.adjMs)} " +
                "E${fmt2(p.edgesMs)} N${fmt2(p.nodesMs)} " +
                "H${fmt2(p.highlightMs)} T${fmt2(p.totalMs)}"
        }
        val sel = selectedLineageId
        if (sel != null) {
            val node = frame.lineage.nodes[sel]
            val alive = frame.lineage.livingLineageIds.contains(sel)
            out += if (node != null) {
                val d = frame.cladogramLayout.depthById[sel] ?: 0
                "LIN $sel ${node.sex} ${if (alive) "LIVE" else "DEAD"} D$d"
            } else {
                "LIN $sel"
            }
        }
        return out
    }

    fun draw(frame: DrocketsFrame) {
        if (!panelOn) return
        val layout = frame.cladogramLayout
        if (layout.positions.isEmpty()) return
        if (selectedLineageId != null && frame.lineage.nodes[selectedLineageId!!] == null) {
            selectedLineageId = null
        }

        val fbW = resolution.x.toInt().coerceAtLeast(1)
        val fbH = resolution.y.toInt().coerceAtLeast(1)
        val halfW = fbW / 2

        GPU.enableScissorTest()
        GPU.setScissor(halfW, 0, fbW - halfW, fbH)

        val totalStart = TimeSource.Monotonic.markNow()
        val layoutStart = TimeSource.Monotonic.markNow()
        val layoutResult = getPanelLayout(frame)
        val panelPositions = layoutResult.positions
        val layoutMs = layoutStart.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f

        fun toNdcPanel(dx: Float, dy: Float): Pair<Float, Float> {
            val ndcX = dx.coerceIn(0f, 1f)
            val ndcY = -1f + 2f * dy.coerceIn(0f, 1f)
            return Pair(ndcX, ndcY)
        }

        val lineage = frame.lineage
        val living = lineage.livingLineageIds

        var nFloat = 0
        fun flushRgba(r: Float, g: Float, b: Float, a: Float) {
            if (nFloat < 4) return
            val vertexCount = nFloat / 2
            cladogramLineShader.drawLinesRgba(cladoLineScratch, vertexCount, r, g, b, a)
            nFloat = 0
        }

        fun pushSeg(ax: Float, ay: Float, bx: Float, by: Float) {
            if (nFloat + 4 > cladoLineScratch.size) return
            cladoLineScratch[nFloat++] = ax
            cladoLineScratch[nFloat++] = ay
            cladoLineScratch[nFloat++] = bx
            cladoLineScratch[nFloat++] = by
        }

        val nodeBaseR = if (layout.stats.nodeCount > 420) 0.012f else 0.018f
        val zoomScale = panelZoom.coerceIn(0.22f, 4.5f)
        val maxRBySpacing = min(CLADO_NODE_X_SPACING, CLADO_GENERATION_Y_SPACING) * zoomScale * 0.40f
        val nodeR = (nodeBaseR * zoomScale).coerceIn(0.004f, maxRBySpacing.coerceAtLeast(0.004f))

        // Adjacency over visible nodes only (for edge color decisions + BFS distances).
        val childrenById = HashMap<Long, MutableList<Long>>()
        val parentsById = HashMap<Long, MutableList<Long>>()
        val adjStart = TimeSource.Monotonic.markNow()
        for ((from, to) in layout.edges) {
            if (!panelPositions.containsKey(from) || !panelPositions.containsKey(to)) continue
            childrenById.getOrPut(from) { mutableListOf() }.add(to)
            parentsById.getOrPut(to) { mutableListOf() }.add(from)
        }

        fun bfsDistances(seed: Long, adjacency: Map<Long, List<Long>>): Map<Long, Int> {
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

        val sel = selectedLineageId
        val ancestors = if (sel != null && panelPositions.containsKey(sel)) bfsDistances(sel, parentsById) else emptyMap()
        val descendants = if (sel != null && panelPositions.containsKey(sel)) bfsDistances(sel, childrenById) else emptyMap()
        val adjMs = adjStart.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f

        // Edge instancing.
        ensureCladoEdgeArrays(layout.edges.size)
        val edgesDrawStart = TimeSource.Monotonic.markNow()
        var edgeInst = 0
        for ((from, to) in layout.edges) {
            if (!panelPositions.containsKey(from) || !panelPositions.containsKey(to)) continue
            val pf = panelPositions[from] ?: continue
            val pt = panelPositions[to] ?: continue
            val (fx, fy) = pf
            val (tx, ty) = pt
            val (aX, aY) = toNdcPanel(fx, fy - nodeR)
            val (bX, bY) = toNdcPanel(tx, ty + nodeR)
            val ancFrom = ancestors[from]
            val ancTo = ancestors[to]
            val descFrom = descendants[from]
            val descTo = descendants[to]
            val bi = edgeInst * 4
            cladoEdgeEnds[bi] = aX
            cladoEdgeEnds[bi + 1] = aY
            cladoEdgeEnds[bi + 2] = bX
            cladoEdgeEnds[bi + 3] = bY
            when {
                ancFrom != null && ((ancTo != null && ancFrom == ancTo + 1) || (sel == to && ancFrom == 1)) -> {
                    val rgb = ancestorColor(ancFrom)
                    cladoEdgeCols[bi] = rgb.first
                    cladoEdgeCols[bi + 1] = rgb.second
                    cladoEdgeCols[bi + 2] = rgb.third
                    cladoEdgeCols[bi + 3] = 0.92f
                }
                descTo != null && ((descFrom != null && descTo == descFrom + 1) || (sel == from && descTo == 1)) -> {
                    val rgb = descendantColor(descTo)
                    cladoEdgeCols[bi] = rgb.first
                    cladoEdgeCols[bi + 1] = rgb.second
                    cladoEdgeCols[bi + 2] = rgb.third
                    cladoEdgeCols[bi + 3] = 0.92f
                }
                living.contains(from) && living.contains(to) -> {
                    val rgb = bodyRgbFromGenome(lineage.nodes[from]?.genome)
                    cladoEdgeCols[bi] = rgb.first
                    cladoEdgeCols[bi + 1] = rgb.second
                    cladoEdgeCols[bi + 2] = rgb.third
                    cladoEdgeCols[bi + 3] = 0.40f
                }
                else -> {
                    cladoEdgeCols[bi] = 0.52f
                    cladoEdgeCols[bi + 1] = 0.56f
                    cladoEdgeCols[bi + 2] = 0.62f
                    cladoEdgeCols[bi + 3] = 0.40f
                }
            }
            edgeInst++
        }
        if (edgeInst > 0) {
            cladogramLineShader.drawLineSegmentsInstanced(cladoEdgeEnds, cladoEdgeCols, edgeInst)
        }
        val edgesMs = edgesDrawStart.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f

        // Node diamonds: dead in batched gray, living one-at-a-time in per-genome body color.
        fun appendDiamond(px: Float, py: Float, r: Float) {
            fun corner(dx: Float, dy: Float): Pair<Float, Float> = toNdcPanel(px + dx, py + dy)
            val (x0, y0) = corner(-r, -r)
            val (x1, y1) = corner(r, -r)
            val (x2, y2) = corner(r, r)
            val (x3, y3) = corner(-r, r)
            pushSeg(x0, y0, x1, y1)
            pushSeg(x1, y1, x2, y2)
            pushSeg(x2, y2, x3, y3)
            pushSeg(x3, y3, x0, y0)
        }

        val nodesStart = TimeSource.Monotonic.markNow()
        for ((id, pos) in panelPositions) {
            if (living.contains(id)) continue
            if (nFloat + 16 > cladoLineScratch.size) {
                flushRgba(0.38f, 0.39f, 0.44f, 0.62f)
            }
            appendDiamond(pos.first, pos.second, nodeR)
        }
        flushRgba(0.38f, 0.39f, 0.44f, 0.62f)

        for ((id, pos) in panelPositions) {
            if (!living.contains(id)) continue
            nFloat = 0
            appendDiamond(pos.first, pos.second, nodeR)
            val rgb = bodyRgbFromGenome(lineage.nodes[id]?.genome)
            flushRgba(rgb.first, rgb.second, rgb.third, 0.95f)
        }
        val nodesMs = nodesStart.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f

        // Selection highlight: ancestor/descendant rings + bright selected diamond.
        val highlightStart = TimeSource.Monotonic.markNow()
        if (sel != null && panelPositions.containsKey(sel)) {
            val highlightR = nodeR * 1.28f
            fun drawFilledCircle(center: Pair<Float, Float>, r: Float, color: Triple<Float, Float, Float>) {
                val steps = 6
                for (i in -steps..steps) {
                    val yy = r * (i.toFloat() / steps.toFloat())
                    val xx = sqrt((r * r - yy * yy).coerceAtLeast(0f))
                    nFloat = 0
                    val (x0, y0) = toNdcPanel(center.first - xx, center.second + yy)
                    val (x1, y1) = toNdcPanel(center.first + xx, center.second + yy)
                    pushSeg(x0, y0, x1, y1)
                    flushRgba(color.first, color.second, color.third, 0.95f)
                }
            }

            for ((id, distance) in ancestors) {
                val p = panelPositions[id] ?: continue
                val rgb = ancestorColor(distance)
                drawFilledCircle(p, highlightR * 0.62f, rgb)
            }
            for ((id, distance) in descendants) {
                val p = panelPositions[id] ?: continue
                val rgb = descendantColor(distance)
                drawFilledCircle(p, highlightR * 0.62f, rgb)
            }

            nFloat = 0
            val p = panelPositions[sel]!!
            appendDiamond(p.first, p.second, nodeR * 1.55f)
            flushRgba(1f, 0.92f, 0.25f, 0.95f)
            drawFilledCircle(p, nodeR * 0.58f, Triple(1f, 1f, 1f))
        }
        val highlightMs = highlightStart.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f

        lastProfile = CladogramProfile(
            keyMs = layoutResult.keyMs,
            filterMs = layoutResult.filterMs,
            solveMs = layoutResult.solveMs,
            projectMs = layoutResult.projectMs,
            layoutMs = layoutMs,
            adjMs = adjMs,
            edgesMs = edgesMs,
            nodesMs = nodesMs,
            highlightMs = highlightMs,
            totalMs = totalStart.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f,
        )

        GPU.disableScissorTest()
    }

    fun cleanup() {
        cladogramLineShader.delete()
    }

    private fun bodyRgbFromGenome(genome: Genome?): Triple<Float, Float, Float> {
        if (genome == null) return Triple(0.85f, 0.88f, 0.92f)
        return genome.phenotype().bodyColor.toRgb()
    }

    private fun ancestorColor(distance: Int): Triple<Float, Float, Float> {
        val t = ((distance - 1).toFloat() / 6f).coerceIn(0f, 1f)
        val near = Triple(1.0f, 0.78f, 0.24f)
        val far = Triple(1.0f, 0.16f, 0.16f)
        return Triple(
            near.first + (far.first - near.first) * t,
            near.second + (far.second - near.second) * t,
            near.third + (far.third - near.third) * t,
        )
    }

    private fun descendantColor(distance: Int): Triple<Float, Float, Float> {
        val t = ((distance - 1).toFloat() / 6f).coerceIn(0f, 1f)
        val near = Triple(0.28f, 0.92f, 1.0f)
        val far = Triple(0.14f, 0.34f, 1.0f)
        return Triple(
            near.first + (far.first - near.first) * t,
            near.second + (far.second - near.second) * t,
            near.third + (far.third - near.third) * t,
        )
    }

    private fun ensureCladoEdgeArrays(minSegments: Int) {
        val need = minSegments * 4
        if (cladoEdgeEnds.size >= need) return
        val cap = maxOf(need, cladoEdgeEnds.size * 2)
        cladoEdgeEnds = FloatArray(cap)
        cladoEdgeCols = FloatArray(cap)
    }

    // ── Layout solve + cache ───────────────────────────────────────────────────

    private data class PanelLayoutResult(
        val positions: Map<Long, Pair<Float, Float>>,
        val keyMs: Float,
        val filterMs: Float,
        val solveMs: Float,
        val projectMs: Float,
    )

    private data class SolveCacheKey(
        val nodeCount: Int,
        val edgeCount: Int,
        val edgeHash: Int,
        val maxDepth: Int,
        val depthHash: Int,
        val livingCount: Int,
        val livingHash: Int,
        val filterMode: CladogramFilterMode,
    )

    private data class SolveCache(
        val key: SolveCacheKey,
        val logicalPositions: Map<Long, Pair<Float, Float>>,
        val filterMs: Float,
        val solveMs: Float,
    )

    private fun invalidateLayoutCache() {
        solveCache = null
    }

    private fun buildSolveCacheKey(frame: DrocketsFrame): SolveCacheKey {
        val layout = frame.cladogramLayout
        val living = frame.lineage.livingLineageIds
        var livingHash = 1
        for (id in living) livingHash = (31 * livingHash) + id.hashCode()
        var edgeHash = 1
        for ((from, to) in layout.edges) {
            edgeHash = (31 * edgeHash) + from.hashCode()
            edgeHash = (31 * edgeHash) + to.hashCode()
        }
        var depthHash = 1
        for ((id, depth) in layout.depthById) {
            depthHash = (31 * depthHash) + id.hashCode()
            depthHash = (31 * depthHash) + depth.hashCode()
        }
        return SolveCacheKey(
            nodeCount = layout.stats.nodeCount,
            edgeCount = layout.edges.size,
            edgeHash = edgeHash,
            maxDepth = layout.stats.maxDepth,
            depthHash = depthHash,
            livingCount = living.size,
            livingHash = livingHash,
            filterMode = filterMode,
        )
    }

    private fun getPanelLayout(frame: DrocketsFrame): PanelLayoutResult {
        val keyStart = TimeSource.Monotonic.markNow()
        val key = buildSolveCacheKey(frame)
        val keyMs = keyStart.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f

        val cached = solveCache
        val (logical, filterMs, solveMs) = if (cached != null && cached.key == key) {
            Triple(cached.logicalPositions, cached.filterMs, cached.solveMs)
        } else {
            val solved = solveLogicalLayout(frame, cached?.logicalPositions)
            solveCache = SolveCache(
                key = key,
                logicalPositions = solved.positions,
                filterMs = solved.filterMs,
                solveMs = solved.solveMs,
            )
            Triple(solved.positions, solved.filterMs, solved.solveMs)
        }

        val projectStart = TimeSource.Monotonic.markNow()
        val projected = projectLogicalToPanel(logical)
        val projectMs = projectStart.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f
        return PanelLayoutResult(
            positions = projected,
            keyMs = keyMs,
            filterMs = filterMs,
            solveMs = solveMs,
            projectMs = projectMs,
        )
    }

    private fun projectLogicalToPanel(
        logicalPositions: Map<Long, Pair<Float, Float>>,
    ): Map<Long, Pair<Float, Float>> {
        if (logicalPositions.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Long, Pair<Float, Float>>(logicalPositions.size)
        for ((id, logical) in logicalPositions) {
            val px = 0.5f + (logical.first * panelZoom) + panelPanX
            val py = 0.88f + (logical.second * panelZoom) + panelPanY
            out[id] = Pair(px, py)
        }
        return out
    }

    private data class LogicalSolveResult(
        val positions: Map<Long, Pair<Float, Float>>,
        val filterMs: Float,
        val solveMs: Float,
    )

    private fun solveLogicalLayout(
        frame: DrocketsFrame,
        seedLogicalPositions: Map<Long, Pair<Float, Float>>?,
    ): LogicalSolveResult {
        val layout = frame.cladogramLayout
        val lineage = frame.lineage
        if (layout.depthById.isEmpty()) return LogicalSolveResult(emptyMap(), 0f, 0f)

        val filterStart = TimeSource.Monotonic.markNow()
        val visibleIds = computeVisibleLineageIds(lineage, layout, filterMode)
        val filterMs = filterStart.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f
        if (visibleIds.isEmpty()) return LogicalSolveResult(emptyMap(), filterMs, 0f)

        val solveStart = TimeSource.Monotonic.markNow()
        val visibleByDepth = LinkedHashMap<Int, MutableList<Long>>()
        for (id in visibleIds) {
            val depth = layout.depthById[id] ?: continue
            visibleByDepth.getOrPut(depth) { mutableListOf() }.add(id)
        }
        if (visibleByDepth.isEmpty()) return LogicalSolveResult(emptyMap(), filterMs, 0f)

        // Reindex visible generations so dead-only gaps collapse out in living-only view.
        val visibleDepths = visibleByDepth.keys.sorted()
        val compactDepthByOriginal = HashMap<Int, Int>(visibleDepths.size)
        for ((idx, depth) in visibleDepths.withIndex()) {
            compactDepthByOriginal[depth] = idx
        }

        val compactDepthToIds = LinkedHashMap<Int, MutableList<Long>>(visibleDepths.size)
        for ((originalDepth, ids) in visibleByDepth) {
            val compactDepth = compactDepthByOriginal[originalDepth] ?: continue
            compactDepthToIds.getOrPut(compactDepth) { mutableListOf() }.addAll(ids)
        }
        for (ids in compactDepthToIds.values) {
            ids.sortWith(compareBy<Long> { lineage.nodes[it]?.birthTick ?: Long.MAX_VALUE }.thenBy { it })
        }

        val visibleIdsSet = compactDepthToIds.values.flatten().toHashSet()
        val parentById = HashMap<Long, List<Long>>(visibleIds.size)
        val childrenById = HashMap<Long, MutableList<Long>>(visibleIds.size)
        for (id in visibleIdsSet) {
            val node = lineage.nodes[id] ?: continue
            val parents = buildList<Long>(2) {
                val m = node.motherLineageId
                val f = node.fatherLineageId
                if (m != null && visibleIdsSet.contains(m)) add(m)
                if (f != null && visibleIdsSet.contains(f)) add(f)
            }
            parentById[id] = parents
            for (p in parents) {
                childrenById.getOrPut(p) { mutableListOf() }.add(id)
            }
        }

        // Stage 1: identify disconnected trees/components.
        val undirected = HashMap<Long, MutableList<Long>>(visibleIdsSet.size)
        for (id in visibleIdsSet) {
            if (!undirected.containsKey(id)) undirected[id] = mutableListOf()
        }
        for ((from, to) in layout.edges) {
            if (!visibleIdsSet.contains(from) || !visibleIdsSet.contains(to)) continue
            undirected[from]?.add(to)
            undirected[to]?.add(from)
        }
        val components = ArrayList<List<Long>>()
        val seen = HashSet<Long>()
        for (id in visibleIdsSet) {
            if (!seen.add(id)) continue
            val queue = ArrayDeque<Long>()
            val comp = ArrayList<Long>()
            queue.addLast(id)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                comp += cur
                for (n in undirected[cur].orEmpty()) {
                    if (seen.add(n)) queue.addLast(n)
                }
            }
            components += comp
        }
        val orderedComponents = components.sortedBy { comp ->
            comp.minOfOrNull { layout.depthById[it] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE
        }

        val xById = HashMap<Long, Float>(visibleIdsSet.size)
        for (ids in compactDepthToIds.values) {
            for ((idx, id) in ids.withIndex()) {
                val seededX = seedLogicalPositions?.get(id)?.first
                xById[id] = seededX ?: (idx.toFloat() * CLADO_NODE_X_SPACING)
            }
        }

        fun applyNonOverlapInLayer(ids: List<Long>, targetX: Map<Long, Float>) {
            if (ids.isEmpty()) return
            val leftToRight = FloatArray(ids.size)
            for (i in ids.indices) {
                val id = ids[i]
                val t = targetX[id] ?: xById[id] ?: 0f
                leftToRight[i] = if (i == 0) t else maxOf(t, leftToRight[i - 1] + CLADO_NODE_X_SPACING)
            }
            val rightToLeft = FloatArray(ids.size)
            for (i in ids.lastIndex downTo 0) {
                val id = ids[i]
                val t = targetX[id] ?: xById[id] ?: 0f
                rightToLeft[i] = if (i == ids.lastIndex) t else minOf(t, rightToLeft[i + 1] - CLADO_NODE_X_SPACING)
            }
            for (i in ids.indices) {
                val id = ids[i]
                xById[id] = (leftToRight[i] + rightToLeft[i]) * 0.5f
            }
        }

        // Stage 2: organize internals of each tree independently with sweeps.
        for (comp in orderedComponents) {
            val allowed = comp.toHashSet()
            val depthToIds = LinkedHashMap<Int, MutableList<Long>>()
            for ((depth, ids) in compactDepthToIds) {
                val subset = ids.filterTo(mutableListOf()) { allowed.contains(it) }
                if (subset.isNotEmpty()) depthToIds[depth] = subset
            }
            val maxDepth = depthToIds.keys.maxOrNull() ?: 0
            val tieEps = 1e-6f

            fun computeDescendantBias(): Map<Long, Float> {
                val bias = HashMap<Long, Float>(allowed.size)
                for (depth in maxDepth downTo 0) {
                    for (id in depthToIds[depth].orEmpty()) {
                        var sum = 0f
                        var count = 0
                        for (child in childrenById[id].orEmpty()) {
                            if (!allowed.contains(child)) continue
                            val cx = bias[child] ?: xById[child] ?: continue
                            sum += cx
                            count++
                        }
                        bias[id] = if (count > 0) sum / count.toFloat() else (xById[id] ?: 0f)
                    }
                }
                return bias
            }

            fun applyTieBiasOrdering(
                ids: MutableList<Long>,
                primary: Map<Long, Float>,
                bias: Map<Long, Float>,
            ) {
                ids.sortWith(compareBy<Long> { primary[it] ?: Float.POSITIVE_INFINITY }.thenBy { it })
                var start = 0
                while (start < ids.size) {
                    val base = primary[ids[start]] ?: Float.POSITIVE_INFINITY
                    var end = start + 1
                    while (end < ids.size) {
                        val v = primary[ids[end]] ?: Float.POSITIVE_INFINITY
                        if (kotlin.math.abs(v - base) > tieEps) break
                        end++
                    }
                    if (end - start > 1) {
                        ids.subList(start, end).sortWith(
                            compareBy<Long> { bias[it] ?: Float.POSITIVE_INFINITY }.thenBy { it }
                        )
                    }
                    start = end
                }
            }

            fun doDownPass() {
                val descendantBias = computeDescendantBias()
                for (depth in 1..maxDepth) {
                    val ids = depthToIds[depth] ?: continue
                    val target = HashMap<Long, Float>(ids.size)
                    for (id in ids) {
                        var sum = 0f
                        var count = 0
                        for (parent in parentById[id].orEmpty()) {
                            if (!allowed.contains(parent)) continue
                            val px = xById[parent] ?: continue
                            sum += px
                            count++
                        }
                        target[id] = if (count > 0) sum / count.toFloat() else (xById[id] ?: 0f)
                    }
                    applyTieBiasOrdering(ids, target, descendantBias)
                    applyNonOverlapInLayer(ids, target)
                }
            }

            fun doUpPass() {
                val descendantBias = computeDescendantBias()
                for (depth in (maxDepth - 1) downTo 0) {
                    val ids = depthToIds[depth] ?: continue
                    val current = HashMap<Long, Float>(ids.size)
                    for (id in ids) current[id] = xById[id] ?: 0f
                    applyTieBiasOrdering(ids, current, descendantBias)
                    applyNonOverlapInLayer(ids, current)
                }
            }

            val seededIds = seedLogicalPositions?.keys ?: emptySet()
            val removedCount = seededIds.count { !allowed.contains(it) }
            val addedCount = allowed.count { !seededIds.contains(it) }
            val churnRatio = if (allowed.isEmpty()) 1f else (addedCount + removedCount).toFloat() / allowed.size.toFloat()
            val useIncrementalPassBudget = seedLogicalPositions != null && churnRatio <= 0.10f

            var downRemaining = if (useIncrementalPassBudget) 1 else maxDepth
            var upRemaining = if (useIncrementalPassBudget) 1 else (maxDepth - 1).coerceAtLeast(0)
            if (downRemaining > 0) {
                doDownPass()
                downRemaining--
            }
            while (downRemaining > 0 || upRemaining > 0) {
                if (upRemaining > 0) {
                    doUpPass()
                    upRemaining--
                }
                if (downRemaining > 0) {
                    doDownPass()
                    downRemaining--
                }
            }
        }

        // Stage 3: position trees side-by-side with a 1-node gap.
        var cursorX = 0f
        for (comp in orderedComponents) {
            val minX = comp.minOfOrNull { xById[it] ?: 0f } ?: 0f
            val maxX = comp.maxOfOrNull { xById[it] ?: 0f } ?: 0f
            val shift = cursorX - minX
            for (id in comp) {
                xById[id] = (xById[id] ?: 0f) + shift
            }
            cursorX = (maxX + shift) + 2f * CLADO_NODE_X_SPACING
        }

        // Center overall graph after side-by-side placement.
        val xMean = if (xById.isNotEmpty()) xById.values.sum() / xById.size.toFloat() else 0f
        val out = LinkedHashMap<Long, Pair<Float, Float>>(visibleByDepth.values.sumOf { it.size })
        // Iterate compactDepthToIds in ascending depth order. (`toSortedMap()` would also work
        // on JVM but isn't in Kotlin/JS stdlib; entries.sortedBy keeps both targets happy.)
        for ((depth, ids) in compactDepthToIds.entries.sortedBy { it.key }) {
            if (ids.isEmpty()) continue
            for (id in ids) {
                val logicalX = (xById[id] ?: 0f) - xMean
                val logicalY = -depth * CLADO_GENERATION_Y_SPACING
                out[id] = Pair(logicalX, logicalY)
            }
        }
        val solveMs = solveStart.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f
        return LogicalSolveResult(out, filterMs, solveMs)
    }

    companion object {
        private const val CLADO_NODE_X_SPACING = 0.060f
        private const val CLADO_GENERATION_Y_SPACING = 0.100f

        /** Multiplatform-safe two-decimal float formatter (truncated, not rounded). */
        private fun fmt2(v: Float): String {
            val n = (v * 100f).toLong()
            val whole = n / 100L
            val frac = (if (n < 0) -n else n) % 100L
            val fracStr = if (frac < 10) "0$frac" else "$frac"
            return "$whole.$fracStr"
        }
    }
}
