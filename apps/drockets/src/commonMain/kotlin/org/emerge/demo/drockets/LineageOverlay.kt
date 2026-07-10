package org.emerge.demo.drockets

import org.emerge.render.torus.GPU
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.Vec2
import kotlin.concurrent.Volatile
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.TimeSource

/**
 * Alternative lineage view: a full-screen translucent overlay rendering nodes as
 * filled coloured discs (real geometry, not line-segment fakes), with mouse-driven
 * orbit-around-centroid and zoom, and click-select decoupled from world-camera focus.
 *
 * The sole cladogram view in [DrocketsRenderer]. (An earlier half-screen panel
 * design lived alongside it for comparison; that one was removed once this version
 * stood on its own — see git history for the side-by-side era.)
 *
 * The camera always frames the centroid of the currently-visible layout: every draw
 * recomputes the mean position of the visible nodes and uses it as the origin for
 * rotation and perspective projection. The user can orbit the camera around that
 * point but cannot displace it; the layout stays centred even as the tree grows.
 *
 * Inputs the composite calls into:
 *   - [toggleActive] — F2 in the default binding.
 *   - [cycleFilter] — ALL <-> LIVING_ONLY only; the connector heuristics from
 *     the older view aren't included.
 *   - [rotateByPixels] — call this every frame while a primary-button drag is
 *     held; horizontal pixels yaw the view around the centroid (y-axis), vertical
 *     pixels pitch it (x-axis).
 *   - [zoomAtCursor] — call this on wheel; zooms around the centroid (the cursor
 *     argument is retained for API compatibility but ignored — once the camera
 *     orbits in 3D the cursor-anchored unprojection isn't well-defined).
 *   - [pickAt] — single click; selects a node without disturbing the world camera.
 *   - [focusLivingDrocketAt] — double click; returns the [EntityId] of a living
 *     drocket whose lineage node was clicked, so the composite can call
 *     [WorldRenderer.focusOn]. Selection inspect and world focus are independent.
 *   - [updateHover] — mouse move while overlay is active.
 *
 * Layout has two interchangeable modes (see [CladogramLayoutMode]): the shared
 * [CladogramLayoutSolver] for hierarchical (depth-banded) output, and the local
 * [ForceDirectedLayoutSolver] for an iteratively-relaxed graph layout that retains
 * per-node positions across frames. F7 cycles between them.
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
    @Volatile var layoutMode: CladogramLayoutMode = CladogramLayoutMode.HIERARCHICAL
        private set

    private var selectedLineageId: Long? = null
    private var secondarySelectedLineageId: Long? = null
    private var hoveredLineageId: Long? = null

    // View state — the camera orbits the layout centroid (no free translation).
    // `viewYaw` rotates around the y-axis (left/right look), `viewPitch` around
    // the x-axis (up/down look), and `cameraDistance` is the camera's distance
    // from the centroid along its view axis: the wheel dollies the camera in or
    // out rather than applying a flat 2D scale. Per-node screen scale comes out
    // as `FOCAL_LENGTH / (cameraDistance + z')`, so closer cameras enlarge the
    // whole scene AND deepen the perspective foreshortening — the projection is
    // a real translation, not a uniform stretch.
    private var viewYaw: Float = 0f
    private var viewPitch: Float = INITIAL_PITCH_RAD
    private var cameraDistance: Float = INITIAL_CAMERA_DISTANCE

    /** Latest centroid of visible logical positions, updated each [draw]. The
     *  projection subtracts this before rotation so the camera orbits the
     *  layout's geometric centre rather than the logical origin. */
    private var centroid: Triple<Float, Float, Float> = Triple(0f, 0f, 0f)

    private var resolution: Vec2 = Vec2(1f, 1f)
    private var lastTotalMs: Float = 0f
    private var solveCache: SolveCache? = null
    private var visibleCache: VisibleCache? = null
    private var lastHighlight: HighlightState = HighlightState.INACTIVE
    /**
     * Incremental cache for [CladogramFilterMode.LIVING_ANCESTRY]. Births/deaths
     * cost O(D × fan-in) instead of the O(L × D) the prior pair-wise MRCA cache
     * paid. Initialised lazily on first use of the filter mode.
     */
    private val livingAncestryCache = LivingAncestryCache()
    private val monotoneFilter = MonotoneFilter()

    /**
     * Persistent positions for [CladogramLayoutMode.FORCE_DIRECTED]. Survives filter
     * changes (so a node hidden by `LIVING_ONLY` returns to the same spot when the
     * filter cycles back to `ALL`) and the hierarchical↔force toggle. The first time
     * force mode is entered, [getForceDirectedSolveResult] seeds it from a one-shot
     * hierarchical solve so we start untangled instead of from a random scatter.
     */
    private val forceSolver = ForceDirectedLayoutSolver()

    /**
     * Tracks whether the force solver has already been stepped during the current
     * draw cycle. Reset at the start of [draw]; set after a force step; consulted by
     * [getForceDirectedSolveResult] so non-draw callers (hit-test, hover) reuse the
     * just-computed positions instead of running another integration step. Without
     * this guard the sim speed scaled with mouse-event frequency — every hover update
     * triggers a hit-test, every hit-test re-ran the force step.
     */
    private var forceStepDoneThisDraw: Boolean = false
    private var lastForceSolution: CladogramLayoutSolution? = null

    fun setResolution(res: Vec2) { resolution = res }

    /** Returns the new active state. */
    fun toggleActive(): Boolean {
        setActive(!active)
        return active
    }

    /** Explicit set without toggling — used by prefs restoration on startup. */
    fun setActive(on: Boolean) {
        active = on
        if (!active) {
            selectedLineageId = null
            secondarySelectedLineageId = null
            hoveredLineageId = null
        }
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
        setFilter(when (filter) {
            CladogramFilterMode.ALL -> CladogramFilterMode.LIVING_ONLY
            CladogramFilterMode.LIVING_ONLY -> CladogramFilterMode.LIVING_AND_CONNECTORS
            CladogramFilterMode.LIVING_AND_CONNECTORS -> CladogramFilterMode.LIVING_FOCUSED
            CladogramFilterMode.LIVING_FOCUSED -> CladogramFilterMode.LIVING_STEINER
            CladogramFilterMode.LIVING_STEINER -> CladogramFilterMode.LIVING_ANCESTRY
            else -> CladogramFilterMode.ALL
        })
        return filter
    }

    /** Explicit set without cycling — used by prefs restoration on startup. */
    fun setFilter(filter: CladogramFilterMode) {
        this.filter = filter
        invalidateLayoutCache()
    }

    /**
     * Cycles HIERARCHICAL ↔ FORCE_DIRECTED. The force solver's stored positions persist
     * across cycles, so toggling back and forth doesn't scramble the layout — only the
     * first entry into force mode triggers a hierarchical-seed initialisation.
     */
    fun cycleLayoutMode(): CladogramLayoutMode {
        setLayoutMode(when (layoutMode) {
            CladogramLayoutMode.HIERARCHICAL -> CladogramLayoutMode.FORCE_DIRECTED
            CladogramLayoutMode.FORCE_DIRECTED -> CladogramLayoutMode.HIERARCHICAL
        })
        return layoutMode
    }

    /** Explicit set without cycling — used by prefs restoration on startup. */
    fun setLayoutMode(mode: CladogramLayoutMode) {
        layoutMode = mode
        // Hierarchical solve is keyed by (stamp, filter) only — switching layout mode
        // doesn't invalidate either cache. The force solver's state is independent;
        // we clear the per-draw step flag so the next force-mode frame integrates
        // immediately rather than returning a stale cached solution from before the
        // toggle.
        forceStepDoneThisDraw = false
    }

    /**
     * Orbit the camera around the layout centroid. Horizontal pixels yaw the view
     * around the y-axis (positive [dxPx], a rightward drag, rotates the camera
     * right — content appears to slide left); vertical pixels pitch around the
     * x-axis (positive [dyPx], a downward drag, lowers the camera so you look up
     * at the layout). Pitch is clamped just shy of ±π/2 to avoid gimbal flips;
     * yaw wraps freely.
     */
    fun rotateByPixels(dxPx: Float, dyPx: Float) {
        if (!active) return
        viewYaw += dxPx * ROTATE_RAD_PER_PX
        viewPitch = (viewPitch - dyPx * ROTATE_RAD_PER_PX).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
    }

    /**
     * Dollies the camera along its view axis. [factor] follows the wheel-zoom
     * convention (factor > 1 from a wheel-up scroll), and we divide
     * [cameraDistance] by it — so scrolling up moves the camera *closer* to the
     * centroid, growing the scene under a true perspective change rather than a
     * flat scale. [cursorPx] is accepted for API compatibility with the original
     * pan+zoom binding but is ignored: with the camera in orbit mode and no
     * free pan parameter, there is no cursor-anchored unprojection to solve for.
     */
    @Suppress("UNUSED_PARAMETER")
    fun zoomAtCursor(cursorPx: Vec2, factor: Float) {
        if (!active || !factor.isFinite() || factor <= 0f) return
        cameraDistance = (cameraDistance / factor).coerceIn(MIN_CAMERA_DISTANCE, MAX_CAMERA_DISTANCE)
    }

    /**
     * Handles a primary-button click. Without `shift`, sets the primary selection and
     * clears any pair-wise secondary. With `shift`, sets / clears the secondary
     * selection, leaving the primary alone — which enables pair-wise MRCA highlighting
     * when both selections are populated.
     */
    fun handleSelectClick(pixel: Vec2, frame: DrocketsFrame, shift: Boolean): Long? {
        if (!active) return null
        val hit = hitTest(pixel, frame)
        if (shift) {
            // Shift+click on the existing secondary toggles it off; shift+click on the
            // primary is a no-op (it's already the primary); shift+click on empty space
            // also clears secondary so you can de-select without missing-clicks moving
            // the primary.
            secondarySelectedLineageId = when {
                hit == null -> null
                hit == selectedLineageId -> secondarySelectedLineageId
                hit == secondarySelectedLineageId -> null
                else -> hit
            }
        } else {
            selectedLineageId = hit
            secondarySelectedLineageId = null
        }
        return hit
    }

    /** Back-compat alias for tests / older callers that just want a non-shift click. */
    fun pickAt(pixel: Vec2, frame: DrocketsFrame): Long? = handleSelectClick(pixel, frame, shift = false)

    /**
     * Multiplies the force-directed solver's runtime force-scale by [factor]. The
     * solver clamps the result to its supported range. Used by the Ctrl+Up/Down
     * binding to step the relaxation rate up/down by a constant ratio. No effect
     * in [CladogramLayoutMode.HIERARCHICAL] mode; the scale persists across mode
     * toggles regardless.
     */
    fun nudgeForceScale(factor: Float) {
        forceSolver.forceScale = forceSolver.forceScale * factor
    }

    /** Absolute set, clamped to the solver's supported range. Used by prefs restoration. */
    fun setForceScale(scale: Float) {
        forceSolver.forceScale = scale
    }

    /** Current force-scale multiplier on the force-directed solver. */
    val forceScale: Float get() = forceSolver.forceScale

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
            CladogramFilterMode.LIVING_AND_CONNECTORS -> "FILTER MRCA-WALK (F6)"
            CladogramFilterMode.LIVING_FOCUSED -> "FILTER LIVING FOCUSED (F6)"
            CladogramFilterMode.LIVING_STEINER -> "FILTER LIVING STEINER (F6)"
            CladogramFilterMode.LIVING_ANCESTRY -> "FILTER LIVING ANCESTRY (F6)"
            else -> "FILTER $filter (F6)"
        }
        out += when (layoutMode) {
            CladogramLayoutMode.HIERARCHICAL -> "LAYOUT HIERARCHICAL (F7)"
            CladogramLayoutMode.FORCE_DIRECTED -> "LAYOUT FORCE-DIRECTED (F7)"
        }
        if (layoutMode == CladogramLayoutMode.FORCE_DIRECTED && forceSolver.forceScale != 1f) {
            out += "FORCE SCALE x${forceSolver.forceScale} (Ctrl+Up/Down)"
        }
        // Hover takes priority over selection for the active read-out — it's the more
        // ephemeral channel — but both are shown when distinct.
        val hover = hoveredLineageId?.let { frame.lineage.nodes[it]?.let { _ -> it } }
        val sel = selectedLineageId?.let { frame.lineage.nodes[it]?.let { _ -> it } }
        val sec = secondarySelectedLineageId?.let { frame.lineage.nodes[it]?.let { _ -> it } }
        if (hover != null && hover != sel && hover != sec) {
            out += hudLineFor(hover, frame, prefix = "HOVER ")
        }
        if (sel != null) {
            out += hudLineFor(sel, frame, prefix = "SEL ")
        }
        if (sec != null && sel != null && sec != sel) {
            out += hudLineFor(sec, frame, prefix = "SEL-B ")
            val h = lastHighlight
            if (h.pairwiseActive && h.mrca != null) {
                out += hudLineFor(h.mrca, frame, prefix = "MRCA ")
                out += "PAIRWISE PATH ${h.pairwisePath.size} nodes"
            } else {
                out += "PAIRWISE: no common ancestor in current view"
            }
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
        // Reset before the solve so this frame's force-step happens exactly once;
        // hit-test/hover callers later in the frame will reuse the result instead of
        // re-stepping the integrator on every mouse-move event.
        forceStepDoneThisDraw = false
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

        // Recompute the centroid of the currently-visible layout so the camera
        // orbits the geometric centre as the tree grows or filters change.
        centroid = computeCentroid(positions)

        // Logical -> NDC projection: orbit-around-centroid with simple perspective.
        // 1. Translate so the centroid is at the origin.
        // 2. Rotate by [viewYaw] around the y-axis (left/right look).
        // 3. Rotate by [viewPitch] around the x-axis (up/down look).
        // 4. Apply a simple perspective scale = FOCAL_LENGTH / (cameraDistance +
        //    z'). The wheel moves [cameraDistance] in or out along the view axis,
        //    so the scale at z'=0 changes too — a closer camera enlarges the
        //    whole scene AND deepens the foreshortening, exactly the dolly
        //    behaviour you'd get if the viewport were a physical hole and the
        //    layout a physical object.
        // 5. Apply the aspect compensation that keeps one logical unit the same
        //    number of pixels in both x and y.
        // The returned third float is the per-node perspective scale, used to
        // size node radii and (via z-sorting) keep painter's-algorithm ordering.
        val aspect = aspectScale()
        val cosY = cos(viewYaw)
        val sinY = sin(viewYaw)
        val cosP = cos(viewPitch)
        val sinP = sin(viewPitch)
        val cx = centroid.first
        val cy = centroid.second
        val cz = centroid.third
        val d = cameraDistance
        fun project(logical: Triple<Float, Float, Float>): Triple<Float, Float, Float> {
            val lx = logical.first - cx
            val ly = logical.second - cy
            val lz = logical.third - cz
            // Yaw around y-axis.
            val x1 = lx * cosY - lz * sinY
            val z1 = lx * sinY + lz * cosY
            // Pitch around x-axis.
            val y2 = ly * cosP - z1 * sinP
            val z2 = ly * sinP + z1 * cosP
            val depth = (d + z2).coerceAtLeast(MIN_DEPTH)
            val s = FOCAL_LENGTH / depth
            val px = x1 * s * aspect.x
            val py = y2 * s * aspect.y
            return Triple(px, py, s)
        }

        val highlight = computeHighlight(frame, positions)
        lastHighlight = highlight
        drawEdges(frame, positions, highlight, ::project)
        drawNodes(frame, positions, highlight, ::project)

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
        positions: Map<Long, Triple<Float, Float, Float>>,
        highlight: HighlightState,
        project: (Triple<Float, Float, Float>) -> Triple<Float, Float, Float>,
    ) {
        val edges = frame.cladogramLayout.edges
        ensureEdgeArrays(edges.size)
        val living = frame.lineage.livingLineageIds
        var n = 0
        for ((from, to) in edges) {
            val pf = positions[from] ?: continue
            val pt = positions[to] ?: continue
            val pfp = project(pf)
            val ptp = project(pt)
            val fx = pfp.first; val fy = pfp.second
            val tx = ptp.first; val ty = ptp.second
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
            val onPairwisePath = highlight.pairwiseActive &&
                from in highlight.pairwisePath && to in highlight.pairwisePath

            val (r, g, b, a) = when {
                onPairwisePath -> floatArrayOf(1.0f, 0.78f, 0.24f, 0.95f)
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
        positions: Map<Long, Triple<Float, Float, Float>>,
        highlight: HighlightState,
        project: (Triple<Float, Float, Float>) -> Triple<Float, Float, Float>,
    ) {
        val living = frame.lineage.livingLineageIds
        val baseR = nodeBaseRadius(positions.size)
        ensureNodeArrays(positions.size)
        // Painter's-algorithm: draw farther nodes first so closer ones layer on top.
        // Sort by the projected perspective scale ascending — smaller scale = farther
        // = drawn first. Calling `project` here keeps the depth ordering in lockstep
        // with the actual screen positions used below.
        val drawOrder = positions.entries.sortedBy { (_, p) -> project(p).third }
        var n = 0
        for ((id, logical) in drawOrder) {
            val projected = project(logical)
            val nx = projected.first
            val ny = projected.second
            val perspectiveScale = projected.third
            val isAlive = id in living
            val isPrimary = id == highlight.selected
            val isSecondary = id == highlight.secondary
            val isMrca = id == highlight.mrca
            val isOnPairwisePath = highlight.pairwiseActive && id in highlight.pairwisePath
            val isHovered = id == hoveredLineageId
            val ancDist = highlight.ancestors[id]
            val descDist = highlight.descendants[id]

            val isRelated = if (highlight.pairwiseActive) {
                isPrimary || isSecondary || isMrca || isOnPairwisePath
            } else {
                isPrimary || ancDist != null || descDist != null
            }

            // Colour priority. Pair-wise mode uses three distinct beacons (primary yellow,
            // secondary orange, MRCA cyan) over a gold "intermediate on-path" base; single-
            // select mode uses the gradient.
            val rgb = when {
                isPrimary -> Triple(1.0f, 0.95f, 0.40f)
                highlight.pairwiseActive && isSecondary -> Triple(1.0f, 0.55f, 0.10f)
                highlight.pairwiseActive && isMrca -> Triple(0.32f, 0.92f, 1.0f)
                highlight.pairwiseActive && isOnPairwisePath -> Triple(1.0f, 0.78f, 0.24f)
                ancDist != null -> ancestorColor(ancDist)
                descDist != null -> descendantColor(descDist)
                highlight.active -> Triple(0.42f, 0.45f, 0.50f)
                else -> bodyRgb(frame.lineage.nodes[id]?.genome).let {
                    if (isAlive) it else Triple(it.first * 0.35f, it.second * 0.35f, it.third * 0.35f)
                }
            }

            val alpha = when {
                isPrimary || isSecondary || isMrca -> 1.0f
                highlight.active && !isRelated -> 0.16f
                isAlive -> 0.95f
                else -> 0.78f
            }

            val ringFrac = when {
                isPrimary || isSecondary || isMrca -> 0.30f
                isHovered -> 0.20f
                else -> 0f
            }
            // Use absolute (not multiplicative) priority so hovering an ancestor still
            // visibly bumps the radius even though "ancestor" already scales it.
            var radius = baseR * if (isAlive) 1.0f else 0.78f
            if (isRelated) radius = maxOf(radius, baseR * 1.15f)
            if (isHovered) radius = maxOf(radius, baseR * 1.25f)
            if (isMrca) radius = maxOf(radius, baseR * 1.40f)
            if (isPrimary || isSecondary) radius = maxOf(radius, baseR * 1.55f)
            // Apply the per-node perspective scale so closer nodes appear larger
            // and farther nodes shrink — matches the position projection above.
            radius *= perspectiveScale

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
            val aspect = aspectScale()
            nodeShader.drawInstanced(
                n, nodeCenters, nodeRadii, nodeColors, nodeRings,
                aspectScaleX = aspect.x,
                aspectScaleY = aspect.y,
            )
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
        positions: Map<Long, Triple<Float, Float, Float>>,
    ): HighlightState {
        val primary = selectedLineageId ?: return HighlightState.INACTIVE
        if (primary !in positions) return HighlightState.INACTIVE
        val children = HashMap<Long, MutableList<Long>>()
        val parents = HashMap<Long, MutableList<Long>>()
        for ((from, to) in frame.cladogramLayout.edges) {
            if (!positions.containsKey(from) || !positions.containsKey(to)) continue
            children.getOrPut(from) { mutableListOf() }.add(to)
            parents.getOrPut(to) { mutableListOf() }.add(from)
        }

        val secondary = secondarySelectedLineageId?.takeIf { it in positions && it != primary }
        if (secondary != null) {
            val pairwise = pairwiseMrca(primary, secondary, parents)
            if (pairwise != null) {
                return HighlightState(
                    active = true,
                    ancestors = emptyMap(),
                    descendants = emptyMap(),
                    selected = primary,
                    secondary = secondary,
                    mrca = pairwise.mrca,
                    pairwisePath = pairwise.path,
                )
            }
        }

        return HighlightState(
            active = true,
            ancestors = bfsDistances(primary, parents),
            descendants = bfsDistances(primary, children),
            selected = primary,
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
        /** Set when a pair-wise MRCA visualisation is active (both a primary AND a secondary
         *  selection exist and the BFS found a common ancestor). The ancestor / descendant
         *  maps above are ignored in that case; [pairwisePath] + [mrca] drive the colouring. */
        val secondary: Long? = null,
        val mrca: Long? = null,
        val pairwisePath: Set<Long> = emptySet(),
    ) {
        val pairwiseActive: Boolean get() = secondary != null && mrca != null && pairwisePath.isNotEmpty()

        companion object {
            val INACTIVE = HighlightState(false, emptyMap(), emptyMap(), null)
        }
    }

    /** Picks the topmost (smallest distance, tie-broken by closest depth) node
     *  within hit radius at [pixel]. Mirrors the draw projection — orbit around
     *  the latest centroid + perspective — so picking agrees with what the user
     *  sees. */
    private fun hitTest(pixel: Vec2, frame: DrocketsFrame): Long? {
        val positions = getSolveResult(frame).positions
        if (positions.isEmpty()) return null
        val ndc = pixelToNdc(pixel)
        val aspect = aspectScale()
        val baseR = nodeBaseRadius(positions.size)
        val cosY = cos(viewYaw); val sinY = sin(viewYaw)
        val cosP = cos(viewPitch); val sinP = sin(viewPitch)
        val cx = centroid.first; val cy = centroid.second; val cz = centroid.third
        val d = cameraDistance

        var best: Long? = null
        var bestD2 = Float.POSITIVE_INFINITY
        var bestScale = Float.NEGATIVE_INFINITY
        for ((id, logical) in positions) {
            val lx = logical.first - cx
            val ly = logical.second - cy
            val lz = logical.third - cz
            val x1 = lx * cosY - lz * sinY
            val z1 = lx * sinY + lz * cosY
            val y2 = ly * cosP - z1 * sinP
            val z2 = ly * sinP + z1 * cosP
            val depth = (d + z2).coerceAtLeast(MIN_DEPTH)
            val s = FOCAL_LENGTH / depth
            val nx = x1 * s * aspect.x
            val ny = y2 * s * aspect.y
            // Un-stretch the NDC delta so the test compares in the same coord
            // space as `baseR` (logical-units-times-perspective, the same units
            // the shader scales the quad by).
            val dx = (ndc.x - nx) / aspect.x
            val dy = (ndc.y - ny) / aspect.y
            val d2 = dx * dx + dy * dy
            // Generous hit radius (1.5x), scaled by perspective so closer nodes
            // are also easier to grab than distant ones.
            val hitR = baseR * 1.5f * s
            val hitR2 = hitR * hitR
            if (d2 <= hitR2 && (d2 < bestD2 - 1e-8f || (kotlin.math.abs(d2 - bestD2) < 1e-8f && s > bestScale))) {
                bestD2 = d2
                bestScale = s
                best = id
            }
        }
        return best
    }

    /**
     * Mean of [positions] in each axis. Treats every visible node equally rather
     * than weighting by edge degree or depth — the camera focuses on the
     * "geometric middle" of what's on screen, which is the most stable choice as
     * the tree grows and prunes.
     */
    private fun computeCentroid(
        positions: Map<Long, Triple<Float, Float, Float>>,
    ): Triple<Float, Float, Float> {
        if (positions.isEmpty()) return Triple(0f, 0f, 0f)
        var sx = 0f; var sy = 0f; var sz = 0f
        for (p in positions.values) {
            sx += p.first; sy += p.second; sz += p.third
        }
        val n = positions.size.toFloat()
        return Triple(sx / n, sy / n, sz / n)
    }

    /**
     * Deterministic per-id random unit vector, uniformly distributed on the
     * sphere. Seeding [kotlin.random.Random] with the lineage id keeps the
     * direction stable across frames and runs, so toggling force mode off and
     * back on doesn't reshuffle the layout. Uses the standard
     * azimuth-plus-uniform-cos-elevation construction:
     *
     *   azimuth   φ ∈ [0, 2π)            uniform in φ
     *   cos(θ)    c ∈ [-1, 1)            uniform in cos(θ) → uniform on sphere
     *   sin(θ)    s = √(1 − c²)
     *   (x, y, z) = (s·cos(φ), s·sin(φ), c)
     */
    private fun randomUnitVector(id: Long): Triple<Float, Float, Float> {
        val rng = kotlin.random.Random(id)
        val phi = 2f * kotlin.math.PI.toFloat() * rng.nextFloat()
        val cosTheta = 2f * rng.nextFloat() - 1f
        val sinTheta = kotlin.math.sqrt((1f - cosTheta * cosTheta).coerceAtLeast(0f))
        return Triple(sinTheta * cos(phi), sinTheta * sin(phi), cosTheta)
    }

    private fun pixelToNdc(pixel: Vec2): Vec2 {
        val w = resolution.x.coerceAtLeast(1f)
        val h = resolution.y.coerceAtLeast(1f)
        return Vec2(pixel.x / w * 2f - 1f, 1f - pixel.y / h * 2f)
    }

    /**
     * Per-axis NDC-stretch factor that makes one logical unit cover the same
     * number of screen pixels in both x and y. Squashes the longer screen axis
     * (`< 1` on that axis, `1f` on the shorter) so the layout doesn't get
     * stretched by viewport aspect.
     */
    private fun aspectScale(): Vec2 {
        val w = resolution.x.coerceAtLeast(1f)
        val h = resolution.y.coerceAtLeast(1f)
        return Vec2(minOf(1f, h / w), minOf(1f, w / h))
    }

    private fun nodeBaseRadius(nodeCount: Int): Float {
        // Scale the base radius gently with population so dense lineages don't visually
        // overlap. The 0.012/0.018 thresholds match the older panel's behaviour roughly.
        // The returned value is in pre-perspective logical units; the per-node
        // perspective scale (applied separately in [drawNodes]) carries the
        // camera-distance dependence, so radii grow as the camera dollies in.
        val populationFactor = if (nodeCount > 420) 0.012f else 0.018f
        // Also clamp to spacing-based limit so closely-packed siblings don't merge.
        val spacingLimit = CladogramLayoutSolver.NODE_X_SPACING * 0.45f
        return populationFactor.coerceIn(0.004f, spacingLimit.coerceAtLeast(0.004f))
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

    /**
     * Visible-id cache for force-directed mode. Hierarchical mode bundles the visible
     * set into its [solveCache.solution], so the cache only matters for the per-frame
     * force path where the solution itself changes every step but the filter result
     * doesn't.
     */
    private data class VisibleCache(
        val versionStamp: Long,
        val filter: CladogramFilterMode,
        val visibleIds: Set<Long>,
    )

    private fun invalidateLayoutCache() {
        solveCache = null
        visibleCache = null
    }

    private fun getSolveResult(frame: DrocketsFrame): CladogramLayoutSolution = when (layoutMode) {
        CladogramLayoutMode.HIERARCHICAL -> getHierarchicalSolveResult(frame)
        CladogramLayoutMode.FORCE_DIRECTED -> getForceDirectedSolveResult(frame)
    }

    private fun getHierarchicalSolveResult(frame: DrocketsFrame): CladogramLayoutSolution {
        val stamp = lineageVersionStamp(frame.lineage)
        val cached = solveCache
        if (cached != null && cached.versionStamp == stamp && cached.filter == filter) {
            return cached.solution
        }
        val (visibleIds, filterMs) = computeVisibleIdsAndCache(frame, stamp)
        val solved = CladogramLayoutSolver.solveWithVisibleIds(
            layout = frame.cladogramLayout,
            lineage = frame.lineage,
            visibleIds = visibleIds,
            seedLogicalPositions = cached?.solution?.positions,
            filterMs = filterMs,
        )
        solveCache = SolveCache(stamp, filter, solved)
        return solved
    }

    /**
     * Force-directed solve path. The visible-id set is cached by (stamp, filter) so the
     * filter cost doesn't repeat every frame, but the force step itself runs every
     * frame — that's the whole point of an iterative relaxation. On first entry into
     * this mode we seed the solver from a one-shot hierarchical solve so we start in
     * an untangled state instead of a random scatter.
     *
     * Hit-test and hover callers reach this method via [getSolveResult] too, but the
     * [forceStepDoneThisDraw] guard prevents them from triggering extra steps within
     * the same draw cycle — without it, sim speed would scale with mouse-event
     * frequency.
     */
    private fun getForceDirectedSolveResult(frame: DrocketsFrame): CladogramLayoutSolution {
        val cached = lastForceSolution
        if (forceStepDoneThisDraw && cached != null) return cached

        val stamp = lineageVersionStamp(frame.lineage)
        val (visibleIds, filterMs) = computeVisibleIdsAndCache(frame, stamp)

        if (forceSolver.isEmpty && visibleIds.isNotEmpty()) {
            // Seed every visible node on the surface of a sphere centred on the
            // origin, with each node's direction drawn from a deterministic
            // per-id random unit vector. This gives the force solver a
            // genuinely 3D starting configuration regardless of the
            // hierarchical layout's shape (which can collapse to a single
            // column for chain lineages, or a plane in general). The relaxation
            // unfolds the topology from this scattered initial state — slower
            // than starting from a hierarchical seed for wide trees, but
            // consistently 3D and free of axis-aligned bias.
            val seed = HashMap<Long, Triple<Float, Float, Float>>(visibleIds.size)
            for (id in visibleIds) {
                val dir = randomUnitVector(id)
                seed[id] = Triple(
                    dir.first * INITIAL_SPHERE_RADIUS,
                    dir.second * INITIAL_SPHERE_RADIUS,
                    dir.third * INITIAL_SPHERE_RADIUS,
                )
            }
            forceSolver.seedFrom(seed)
        }

        val positions = forceSolver.step(
            layout = frame.cladogramLayout,
            lineage = frame.lineage,
            visibleIds = visibleIds,
        )
        val solution = CladogramLayoutSolution(
            positions = positions,
            filterMs = filterMs,
            solveMs = forceSolver.lastStepMs,
        )
        lastForceSolution = solution
        forceStepDoneThisDraw = true
        return solution
    }

    /**
     * Either returns the cached (visibleIds, filterMs=0) pair when (stamp, filter) is
     * unchanged, or recomputes — using [livingAncestryCache] for the LIVING_ANCESTRY
     * filter mode so per-frame work stays bounded — and updates the cache.
     */
    private fun computeVisibleIdsAndCache(
        frame: DrocketsFrame,
        stamp: Long,
    ): Pair<Set<Long>, Float> {
        val cached = visibleCache
        if (cached != null && cached.versionStamp == stamp && cached.filter == filter) {
            return cached.visibleIds to 0f
        }
        val filterStart = kotlin.time.TimeSource.Monotonic.markNow()
        val visibleIds = monotoneFilter.apply(filter, frame.lineage, frame.cladogramLayout, livingAncestryCache)
        val filterMs = filterStart.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f
        visibleCache = VisibleCache(stamp, filter, visibleIds)
        return visibleIds to filterMs
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
        /** Initial pitch for the orbit camera. Positive radians push the upper
         *  end of the y-axis (the root, depth 0) away from the camera, so the
         *  tree reads as receding into the screen from front (young) to back
         *  (ancient). ~30° gives noticeable depth without harsh foreshortening;
         *  the user can change it from here via mouse drag or arrow keys. */
        private const val INITIAL_PITCH_RAD: Float = 0.5236f // π/6
        /** Per-pixel rotation rate for [rotateByPixels]. Same constant for arrow
         *  keys and mouse drag — keystroke step size is governed by the caller
         *  (arrow keys tick every frame at a small synthetic pixel delta). */
        private const val ROTATE_RAD_PER_PX: Float = 0.005f
        /** Clamp on [viewPitch] so the camera never goes through gimbal lock. */
        private const val PITCH_LIMIT: Float = 1.5533f // π/2 − ~2°
        /** Effective focal length of the projection in logical units. Combined
         *  with [cameraDistance], the per-node screen scale is
         *  `FOCAL_LENGTH / (cameraDistance + z')`. Lowering it narrows the FOV
         *  (more telephoto); raising it widens it. */
        private const val FOCAL_LENGTH: Float = 1.0f
        /** Initial camera distance from the centroid along the view axis. At
         *  [INITIAL_CAMERA_DISTANCE] == [FOCAL_LENGTH] the centroid plane lands
         *  at scale 1.0, matching the pre-dolly default view. */
        private const val INITIAL_CAMERA_DISTANCE: Float = 1.0f
        /** Closest the camera can dolly toward the centroid. Smaller values
         *  make perspective foreshortening extreme; clamping prevents the
         *  divisor in `FOCAL_LENGTH / depth` from collapsing. */
        private const val MIN_CAMERA_DISTANCE: Float = 0.125f
        /** Farthest the camera can dolly out. At large distances the projection
         *  approaches orthographic — interesting nodes shrink to dots, so this
         *  bounds how far "out" feels useful. */
        private const val MAX_CAMERA_DISTANCE: Float = 400f
        /** Minimum projection denominator. Clamps the per-node scale so a node
         *  that drifts behind the camera (z' < -cameraDistance) doesn't blow up
         *  to infinity. */
        private const val MIN_DEPTH: Float = 0.1f
        /** Radius of the sphere on which force-mode places every visible node
         *  at first frame. Each node gets a deterministic per-id random unit
         *  vector scaled by this constant, so the initial state is uniformly
         *  scattered in 3D regardless of the cladogram's shape. Picked to
         *  match the default camera distance — the whole sphere fits inside
         *  the view at default zoom. */
        private const val INITIAL_SPHERE_RADIUS: Float = 0.5f
    }
}

/**
 * Pair-wise MRCA result: the most-recent common ancestor and the set of nodes on the
 * shortest path between two lineages (primary → ... → MRCA → ... → secondary).
 */
internal data class PairwiseMrca(val mrca: Long, val path: Set<Long>)

/**
 * Finds the most-recent common ancestor of two lineage nodes via the directed [parents]
 * adjacency, plus the set of nodes on the shortest path between them. Returns null if
 * no common ancestor exists (e.g. the two are in different forest components) or if
 * the two are the same node.
 *
 * Top-level `internal` so the path-reconstruction can be unit-tested independently of
 * any GL state. (A previous in-class version had a path-walk bug that only manifested
 * at render time as missing edge highlights; a test against this entry point would
 * have caught it.)
 *
 * Algorithm:
 *   1. BFS up from each side through the parent edges, recording (distance, predecessor)
 *      for every reachable ancestor.
 *   2. Among nodes reached from both, pick the one minimising `pDist + sDist`. For a
 *      strict tree this is *the* MRCA; for a 2-parent DAG with crossovers it picks the
 *      shortest connecting ancestor (a reasonable visual analogue).
 *   3. Reconstruct each path half by walking *from the MRCA back to the seed* via the
 *      seed's predecessor chain. Predecessors point toward the seed, so this terminates
 *      naturally when we hit the seed (whose predecessor entry is absent).
 *      Note: walking from `seed` via the same pred map is wrong — `pred[seed]` is null
 *      and the walk stops after one step. That was the original bug.
 */
internal fun pairwiseMrca(
    primary: Long,
    secondary: Long,
    parents: Map<Long, List<Long>>,
): PairwiseMrca? {
    if (primary == secondary) return null
    val (primaryDist, primaryPred) = bfsAncestorTree(primary, parents)
    val (secondaryDist, secondaryPred) = bfsAncestorTree(secondary, parents)
    // Tie-break rules used by [findPairwiseMrca] are documented at its definition;
    // shift-click highlight cares about a deterministic MRCA choice for the path
    // reconstruction below.
    val mrca = findPairwiseMrca(primaryDist, secondaryDist) ?: return null

    val path = LinkedHashSet<Long>()
    walkPredChainInto(mrca, primaryPred, path)
    walkPredChainInto(mrca, secondaryPred, path)
    return PairwiseMrca(mrca, path)
}

/**
 * BFS up from [seed] over [parents]. Returns `(distancesByNode, predecessorByNode)`.
 * Predecessor map omits the seed itself and points one step toward the seed.
 */
internal fun bfsAncestorTree(
    seed: Long,
    parents: Map<Long, List<Long>>,
): Pair<Map<Long, Int>, Map<Long, Long>> {
    val dist = LinkedHashMap<Long, Int>()
    val pred = HashMap<Long, Long>()
    val queue = ArrayDeque<Long>()
    queue.addLast(seed)
    dist[seed] = 0
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        val d = dist[cur] ?: continue
        for (next in parents[cur].orEmpty()) {
            if (dist.containsKey(next)) continue
            dist[next] = d + 1
            pred[next] = cur
            queue.addLast(next)
        }
    }
    return dist to pred
}

private fun walkPredChainInto(from: Long, pred: Map<Long, Long>, out: MutableSet<Long>) {
    var cur: Long? = from
    while (cur != null) {
        out += cur
        cur = pred[cur]
    }
}
