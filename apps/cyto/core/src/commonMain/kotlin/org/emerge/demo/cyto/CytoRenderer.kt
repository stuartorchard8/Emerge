package org.emerge.demo.cyto

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.SpeciesRegistry
import org.emerge.render.torus.ui.UiRectRenderer
import org.emerge.render.torus.shader.CircleShader
import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.Mat4
import org.emerge.render.torus.put
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.min

/** Cell display colour modes, cycled by the on-screen "Color" button (see [CytoRenderer.colorMode]). */
enum class CellColorMode(val label: String) {
    /** Hue from the biomass atom mix, saturation from the cytoplasm:biomass ratio (the original look). */
    Bio("BIO"),
    /** Hue from the cytoplasm atom mix — i.e. the ratio of a cell's cytoplasm contents; grey when empty. */
    Cyt("CYT"),
}

/**
 * Draws the native Cyto world on Emerge's GPU. Reads the [org.emerge.sim.core.sim.SimState]
 * component tables (transform, collider, cell type, spring connections) and routes each
 * cell through [CytoCellShader]. Owns a flat 2D camera in *logical* Cyto units (the engine
 * fixed-point positions are converted back via [CytoUnits]); the membrane-blend neighbour
 * data is the torus-aware delta to each connected cell, with y flipped to match the shader.
 */
class CytoRenderer {
    private val shader = CytoCellShader()

    // Full-screen background fill, drawn first each frame — clears the previous frame
    // without a platform-specific glClear (the engine GPU doesn't expose one), so this
    // works identically on desktop, Android, and web.
    private val bgShader = UiRectRenderer()

    // The cell shader does `min(u_color, texture)`, so a flat white texture yields the
    // cell's colour; the disc shape + shading come from the shader, not the texture. Built
    // procedurally so no PNG asset is needed (works identically on desktop/Android/web).
    private val cellTextureId = createWhiteTexture()

    private var resW = 1f
    private var resH = 1f

    private var centerX = 0f
    private var centerY = 0f
    private var viewHeight = 100f

    private val matP = Mat4.scratch()
    private val matS = Mat4.scratch()
    private val matT = Mat4.scratch()
    private val matM = Mat4.scratch()
    private val matMS = Mat4.scratch()
    private val matMT = Mat4.scratch()
    private val mvp = Mat4.scratch()
    private val colorTmp = FloatArray(4)
    private val neighbourTmp = FloatArray(CytoCellShader.MAX_NEIGHBOURS * 4)

    private val BG_CENTER = floatArrayOf(0f, 0f)
    private val BG_HALF_SIZE = floatArrayOf(1f, 1f)
    private val BG_COLOR = floatArrayOf(0f, 0f, 0f, 1f)

    // ── light-field heatmap (the energy landscape, drawn as the background) ──────────────
    // Reuses the proven instanced-rect shader: the static field grid (one torus tile) baked to
    // heat colours once, projected to NDC + culled to the visible region each frame. Toggle with L.
    var showLightField = true
    /** How cells are coloured (Host-set from the controls' "Color" button). */
    var colorMode = CellColorMode.Bio
    /** EntityId.value of the focused cell (info panel open) — drawn at full value; -1 = none. Host-set. */
    var focusedCellId = -1

    // ── smooth camera follow ─────────────────────────────────────────────────────
    private var followId = -1
    private var followX = 0f
    private var followY = 0f
    private var followVX = 0f
    private var followVY = 0f
    private val FOLLOW_DAMPING = 5f

    /** Recentre the camera on the origin and frame the (possibly resized) torus — used when a fresh world is
     *  started so the view isn't left zoomed on the previous world's scale. */
    fun resetView() {
        centerX = 0f; centerY = 0f
        viewHeight = org.emerge.demo.cyto.sim.CytoUnits.CELLS_PER_AXIS * 1.5f
        followId = -1; followX = 0f; followY = 0f; followVX = 0f; followVY = 0f
    }

    /** Tell the renderer to smoothly follow entity [id] at world position ([x], [y]).
     *  Call every frame — when [id] changes the target resets; when it becomes negative
     *  the camera stops following (coasts to a halt via damping). */
    fun follow(id: Int, x: Float, y: Float) {
        if (id != followId) {
            // new target — snap position, reset velocity
            followId = id
            followX = x
            followY = y
            followVX = 0f
            followVY = 0f
        } else if (id >= 0) {
            followX = x
            followY = y
        }
    }

    /** Apply the follow target to the camera centre [centerX]/[centerY] using damped spring.
     *  Call once per frame in [draw], before any NDC computation. */
    private fun applyFollow() {
        if (followId < 0) return
        val damping = FOLLOW_DAMPING
        var vx = followVX
        var vy = followVY
        vx += (followX - centerX) * damping
        vy += (followY - centerY) * damping
        val frac = 1f / 60f
        vx *= frac
        vy *= frac
        centerX += vx
        centerY += vy
        followVX = vx
        followVY = vy
    }
    /** EntityId.values of the focused cell's directly-welded neighbours, rebuilt each frame in [draw]
     *  (cleared, then refilled — no per-frame allocation). When a cell is focused, every cell NOT in
     *  this set and not the focused cell itself is dimmed, so the welded cluster stands out. */
    private val focusNeighbours = HashSet<Int>()
    private val fieldShader = UiRectRenderer(maxRects = FIELD_CELLS)
    private val fieldCx = FloatArray(FIELD_CELLS)
    private val fieldCy = FloatArray(FIELD_CELLS)
    private val fieldColor = FloatArray(FIELD_CELLS * 4)
    private val fieldHalfLogical = CytoLightField.SPAN / FRES * 0.5f
    private val fInstCenter = FloatArray(FIELD_CELLS * 2)
    private val fInstHalf = FloatArray(FIELD_CELLS * 2)
    private val fInstColor = FloatArray(FIELD_CELLS * 4)

    // ── matter-field overlay (the adaptive quad-tree reservoir, drawn as bordered leaf squares) ──
    // Each visible leaf → a 2px-bordered square: fill is the leaf's per-area a/b/c atom DENSITY as raw RGB,
    // normalised so a full base-density leaf is white and depletion darkens + discolours it (the channel the
    // cells drained drops out). Borders are drawn first, fills (inset by the border width) painted on top,
    // leaving a 2px frame. Toggle "Matter".
    var showMatterField = false
    private val matterShader = UiRectRenderer(maxRects = MATTER_MAX_LEAVES)
    private val matCx = FloatArray(MATTER_MAX_LEAVES)
    private val matCy = FloatArray(MATTER_MAX_LEAVES)
    private val matHx = FloatArray(MATTER_MAX_LEAVES)
    private val matHy = FloatArray(MATTER_MAX_LEAVES)
    private val matFillColor = FloatArray(MATTER_MAX_LEAVES * 4)
    private val mInstCenter = FloatArray(MATTER_MAX_LEAVES * 2)
    private val mInstHalf = FloatArray(MATTER_MAX_LEAVES * 2)
    private val mInstColor = FloatArray(MATTER_MAX_LEAVES * 4)
    private val matColorTmp = FloatArray(4)

    // ── metabolic activity fields (LIVING_WORLD_PLAN.md §5) ──────────────────────────────────────
    // Flow 3 (CYT→BIO "building"): a soft species-coloured disc drawn over each building cell, its
    // opacity = a per-cell eased intensity that warms up as the cell starts converting cytoplasm to
    // biomass and cools down as it stops. Rendered with the engine's instanced soft-disc shader.
    // The VAO is bound *before* the shader is constructed so the shader's instance attributes attach
    // to it; the base geometry (a triangle circumscribing the unit disc) is uploaded to location 0.
    private val circleVao = GPU.genAndBindVertexArrays()
    private val circleShader = CircleShader()
    private val circleVbo = GPU.genBuffers()
    private val buildMatrices = FloatArray(BUILD_MAX * Mat4.FLOATS)
    private val buildPrimaryIds = FloatArray(BUILD_MAX)
    private val buildShapes = FloatArray(BUILD_MAX)      // all 0 ⇒ soft disc
    private val buildAlphas = FloatArray(BUILD_MAX)
    private val buildTints = FloatArray(BUILD_MAX * 3)
    /** Per-cell eased build intensity, keyed by EntityId.value; evicted when the cell is absent. */
    private val buildIntensity = HashMap<Int, Float>()
    private val buildSeen = HashSet<Int>()
    /** Per-cell eased BIO→ENV decay intensity (flow 4), same keying/eviction as [buildIntensity]. */
    private val decayIntensity = HashMap<Int, Float>()
    private val decaySeen = HashSet<Int>()
    /** Global pulse phase [0,1), advanced once per rendered frame — the expansion clock for the build
     *  glow (per-cell it's offset so cells don't pulse in lockstep). Frame-driven, not tick-driven. */
    private var pulsePhase = 0f

    // ── ENV↔CYT transfer particles (flows 1 & 2) ────────────────────────────────────────────────
    // Discrete world-space particles: spawned each *tick* in proportion to that tick's membrane transfer,
    // then aged every *frame* (independent of tick rate). Flow 1 (absorption) drifts a species-coloured
    // speck from just outside the cell inward to the centre; flow 2 (secretion) is the reverse. A particle
    // is (spawn pos) + progress·(displacement); the alpha envelope fades it in then out (sin(π·progress)).
    private val partX0 = FloatArray(PARTICLE_MAX)
    private val partY0 = FloatArray(PARTICLE_MAX)
    private val partDX = FloatArray(PARTICLE_MAX)
    private val partDY = FloatArray(PARTICLE_MAX)
    private val partProg = FloatArray(PARTICLE_MAX)
    private val partR = FloatArray(PARTICLE_MAX)
    private val partG = FloatArray(PARTICLE_MAX)
    private val partB = FloatArray(PARTICLE_MAX)
    private val partSize = FloatArray(PARTICLE_MAX)
    private var partCount = 0
    private var lastParticleTick = -1L
    private val partRng = kotlin.random.Random(0x0CEE)
    private val matCircS = Mat4.scratch()
    private val matCircT = Mat4.scratch()
    private val matCircM = Mat4.scratch()
    private val mvpCirc = Mat4.scratch()
    private val speciesTmp = FloatArray(3)

    init {
        bakeFieldColors(0L)
        uploadCircleGeom()
    }

    /** Upload the circumscribing triangle the [CircleShader] rasterises the unit disc within (its
     *  fragment shader discards outside `dot(local,local) > 1`). Bound to the shared [circleVao]. */
    private fun uploadCircleGeom() {
        GPU.bindVertexArray(circleVao)
        val verts = floatArrayOf(-1f, 1.7320508f, 2f, 0f, -1f, -1.7320508f)
        val buf = GpuFloatBuffer(verts.size)
        buf.put(verts).flip()
        GPU.bindBuffer(GPU.ARRAY_BUFFER, circleVbo)
        GPU.enableVertexAttribArray(0)
        GPU.putVertexAttribPointer(0, 2, GPU.FLOAT, false, 2 * 4, 0)
        GPU.bufferData(GPU.ARRAY_BUFFER, verts.size, buf, GPU.STATIC_DRAW)
        GPU.bindBuffer(GPU.ARRAY_BUFFER, 0)
    }

    /** Bake the light-field heatmap colours for sim-time [tick]. Static field → baked once at init; the
     *  moving field → re-baked each frame in [draw] so the daylight band animates. */
    private fun bakeFieldColors(tick: Long) {
        val field = CytoLightField.default()
        val cell = CytoLightField.SPAN / FRES
        var i = 0
        for (gy in 0 until FRES) {
            val wy = -CytoLightField.HALF + (gy + 0.5f) * cell
            for (gx in 0 until FRES) {
                val wx = -CytoLightField.HALF + (gx + 0.5f) * cell
                fieldCx[i] = wx; fieldCy[i] = wy
                val t = (field.sampleAt(wx, wy, tick).toFloat() / CytoLightField.STRENGTH.toFloat()).coerceIn(0f, 1f)
                // Perceptual ramp from pure black (no floor) up to the peak yellow.
                // sqrt lifts dim values so any non-zero light reads as clearly lit — ONLY t==0 is black.
                val s = sqrt(t)
                val b = i * 4
                fieldColor[b] = s
                fieldColor[b + 1] = s * 0.90f
                fieldColor[b + 2] = s * 0.43f
                fieldColor[b + 3] = 1f
                i++
            }
        }
    }

    fun setResolution(widthPx: Float, heightPx: Float) {
        resW = max(1f, widthPx)
        resH = max(1f, heightPx)
        GPU.setViewport(0, 0, resW.toInt(), resH.toInt())
    }

    fun panByPixels(dxPx: Float, dyPx: Float) {
        val worldPerPx = viewHeight / resH
        centerX -= dxPx * worldPerPx
        centerY += dyPx * worldPerPx
    }

    fun zoomByFactor(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        viewHeight = (viewHeight / factor).coerceIn(0.5f, 100_000f)
    }

    /** Zoom by [factor] while keeping the world point under screen pixel ([px], [py]) fixed. */
    fun zoomAtScreen(px: Float, py: Float, factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        val before = screenToWorld(px, py)
        viewHeight = (viewHeight / factor).coerceIn(0.5f, 100_000f)
        val after = screenToWorld(px, py)
        centerX += before[0] - after[0]
        centerY += before[1] - after[1]
    }

    /** Framebuffer pixel -> logical world `[x, y]`. */
    fun screenToWorld(px: Float, py: Float): FloatArray {
        val aspect = resW / resH
        val viewWidth = viewHeight * aspect
        val ndcX = px / resW * 2f - 1f
        val ndcY = 1f - py / resH * 2f
        return floatArrayOf(
            centerX + ndcX * viewWidth * 0.5f,
            centerY + ndcY * viewHeight * 0.5f,
        )
    }

    /** Logical world (x, y) -> framebuffer pixel `[px, py]` (inverse of [screenToWorld]). */
    fun worldToScreen(worldX: Float, worldY: Float): FloatArray {
        val aspect = resW / resH
        val viewWidth = viewHeight * aspect
        val ndcX = (worldX - centerX) / (viewWidth * 0.5f)
        val ndcY = (worldY - centerY) / (viewHeight * 0.5f)
        return floatArrayOf(
            (ndcX + 1f) * 0.5f * resW,
            (1f - ndcY) * 0.5f * resH,
        )
    }

    fun draw(frame: CytoFrame) {
        applyFollow()
        computeProjection()

        // Background fill (opaque) — clears the frame.
        GPU.disableBlend()
        bgShader.drawInstanced(1, BG_CENTER, BG_HALF_SIZE, BG_COLOR)
        // Light-field heatmap over the world (opaque, on top of the clear, under the cells).
        if (org.emerge.demo.cyto.sim.CytoTuning.LIGHT_MOVING) bakeFieldColors(frame.tick)   // animate the band
        drawLightField()
        drawMatterField(frame)

        val components = frame.state.components
        val cells = components.getTable<CytoCellComponent>().asMap()
        val transforms = components.getTable<TransformComponent>()
        val colliders = components.getTable<ColliderComponent>()

        // One pulse-clock tick per rendered frame, shared by the metabolic fields (flows 3 & 4).
        pulsePhase += BUILD_PULSE_SPEED
        if (pulsePhase >= 1f) pulsePhase -= 1f

        GPU.enableBlend()
        // Flow 4 (BIO→ENV decay) draws first, behind the cell discs (LIVING_WORLD_PLAN.md §5 render order).
        drawDecayField(cells, transforms, colliders)

        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        shader.begin(cellTextureId)

        // CSR-based spring access (avoids SimState SpringConstraintComponent allocation)
        val springData = frame.springData
        val springTable = if (springData == null) components.getTable<SpringConstraintComponent>() else null

        // The welded neighbours of the focused cell — used to dim everything outside that cluster so
        // it's obvious which cells the selection is bonded to. Only active while the focused cell is
        // actually present (a focused cell can die without [focusedCellId] being cleared — without this
        // guard its now-empty neighbour set would dim every cell to darkness).
        focusNeighbours.clear()
        val dimActive = focusedCellId >= 0 && cells.containsKey(EntityId(focusedCellId))
        if (dimActive && springTable != null) {
            springTable[EntityId(focusedCellId)]?.springs?.forEach { focusNeighbours.add(it.other.value) }
        }
        if (dimActive && springData != null) {
            val slot = springData.slotOfEntityId(focusedCellId)
            if (slot >= 0) {
                val lo = springData.csrOffset[slot]
                val hi = springData.csrOffset[slot + 1]
                for (k in lo until hi) focusNeighbours.add(springData.csrOtherId[k])
            }
        }

        buildSeen.clear()
        // Particles (flows 1&2) are spawned once per tick; aged every frame in drawParticles().
        val spawnParticles = frame.tick != lastParticleTick
        lastParticleTick = frame.tick
        var buildCount = 0
        for ((id, cell) in cells) {
            val transform = transforms[id] ?: continue
            val collider = colliders[id] ?: continue
            val radius = CytoUnits.toLogical(collider.radius)
            val cx = CytoUnits.toLogical(transform.pos.x)
            val cy = CytoUnits.toLogical(transform.pos.y)

            if (spawnParticles) spawnTransferParticles(cx, cy, radius, cell)

            // Flow 3 (CYT→BIO): ease this cell's build intensity toward this tick's converted amount,
            // then stage a soft disc if it's building enough to see. Keyed by EntityId; evicted below.
            val eid = id.value
            val buildTarget = buildTargetFor(cell)
            val inten = ((buildIntensity[eid] ?: 0f) + (buildTarget - (buildIntensity[eid] ?: 0f)) * BUILD_EASE)
            buildIntensity[eid] = inten
            buildSeen.add(eid)
            if (inten > BUILD_MIN_VISIBLE) {
                averageSpeciesColor(cell.cytToBio, speciesTmp)
                // Emit BUILD_PULSES staggered discs that each grow from the cell centre (r=0) out to its
                // rim (r=radius), fading their opacity to zero over the expansion — so the cell reads as a
                // continuous outward pulse. The per-cell phase offset (golden-ratio hash of the id) keeps
                // neighbouring cells from pulsing in lockstep.
                val offRaw = eid * 0.61803398f
                val base = pulsePhase + (offRaw - floor(offRaw))
                for (k in 0 until BUILD_PULSES) {
                    if (buildCount >= BUILD_MAX) break
                    var frac = base + k.toFloat() / BUILD_PULSES
                    frac -= floor(frac)                                  // wrap to [0,1)
                    val a = inten * (1f - frac) * BUILD_MAX_ALPHA        // fade out as it expands
                    if (a <= 0.003f) continue
                    val r = frac * radius                                // grow 0 → cell radius
                    matCircS.setScale(r, r)
                    matCircT.setTranslation(cx, cy)
                    matCircM.setProduct(matCircT, matCircS)
                    mvpCirc.setProduct(matP, matCircM)
                    mvpCirc.copyInto(buildMatrices, buildCount * Mat4.FLOATS)
                    buildPrimaryIds[buildCount] = 0f
                    buildShapes[buildCount] = 0f
                    buildAlphas[buildCount] = a.coerceIn(0f, 1f)
                    val tb = buildCount * 3
                    buildTints[tb] = speciesTmp[0]; buildTints[tb + 1] = speciesTmp[1]; buildTints[tb + 2] = speciesTmp[2]
                    buildCount++
                }
            }

            matMS.setScale(2f * radius, 2f * radius)
            matMT.setTranslation(cx, cy)
            matM.setProduct(matMT, matMS)
            mvp.setProduct(matP, matM)

            val focused = id.value == focusedCellId
            // Dim a cell only when a present cell is focused and this one is neither it nor a direct weld.
            val dimmed = dimActive && !focused && id.value !in focusNeighbours
            cellColor(cell, focused, dimmed)

            var count = 0
            if (springData != null) {
                // CSR-based iteration (no per-entity ArrayList allocation)
                val slot = springData.slotOfEntityId(id.value)
                if (slot >= 0) {
                    val lo = springData.csrOffset[slot]
                    val hi = springData.csrOffset[slot + 1]
                    for (k in lo until hi) {
                        if (count >= CytoCellShader.MAX_NEIGHBOURS) break
                        val nbEntityId = springData.csrOtherId[k]
                        val nt = transforms[EntityId(nbEntityId)] ?: continue
                        val nr = colliders[EntityId(nbEntityId)] ?: continue
                        val delta = nt.pos - transform.pos
                        val base = count * 4
                        neighbourTmp[base] = CytoUnits.toLogical(delta.x)
                        neighbourTmp[base + 1] = -CytoUnits.toLogical(delta.y)
                        neighbourTmp[base + 2] = CytoUnits.toLogical(nr.radius)
                        neighbourTmp[base + 3] = 0f
                        count++
                    }
                }
            } else if (springTable != null) {
                val neighbours = springTable[id]?.springs
                if (neighbours != null) {
                    for (spring in neighbours) {
                        if (count >= CytoCellShader.MAX_NEIGHBOURS) break
                        val nt = transforms[spring.other] ?: continue
                        val nr = colliders[spring.other] ?: continue
                        val delta = nt.pos - transform.pos
                        val base = count * 4
                        neighbourTmp[base] = CytoUnits.toLogical(delta.x)
                        neighbourTmp[base + 1] = -CytoUnits.toLogical(delta.y)
                        neighbourTmp[base + 2] = CytoUnits.toLogical(nr.radius)
                        neighbourTmp[base + 3] = 0f
                        count++
                    }
                }
            }

            shader.draw(
                mvp = mvp,
                radiusUniform = radius * 2f,
                color = colorTmp,
                neighbours = neighbourTmp,
                count = count,
            )
        }
        // Evict intensity state for cells that vanished (died/off-frame) — same pattern as focusNeighbours.
        if (buildIntensity.size > buildSeen.size) buildIntensity.keys.retainAll(buildSeen)

        // Flow 3: draw the staged build discs on top of the cell discs (LIVING_WORLD_PLAN.md §5 render order).
        // Additive so the "building" glow reads as a brightening core on a cell of any colour (a balanced
        // rgb builder's average colour is near-white, which an alpha blend would wash out invisibly).
        if (buildCount > 0) {
            GPU.setBlendFuncSrcAlphaOne()
            GPU.bindVertexArray(circleVao)
            circleShader.drawInstanced(
                vOffset = 0,
                instanceCount = buildCount,
                matricesColMajor = buildMatrices,
                primaryIds = buildPrimaryIds,
                shapes = buildShapes,
                alphas = buildAlphas,
                tintColorsRgb = buildTints,
            )
            GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        }

        // Flows 1 & 2: age + draw the ENV↔CYT transfer particles, on top of the cells.
        drawParticles()

        GPU.disableBlend()
    }

    /** Spawn this tick's ENV↔CYT particles for one cell: [envCytIn] → inward absorption specks (flow 1),
     *  [envCytOut] → outward secretion specks (flow 2), a count per species ∝ that species' transfer. */
    private fun spawnTransferParticles(cx: Float, cy: Float, radius: Float, cell: CytoCellComponent) {
        val size = radius * PARTICLE_SIZE_FRAC
        val outer = radius * PARTICLE_OUTER
        for ((species, amt) in cell.envCytIn) {
            speciesColorInto(species, speciesTmp)
            val n = min(PARTICLE_MAX_PER_SPECIES, (amt * PARTICLE_PER_UNIT).roundToInt())
            for (j in 0 until n) {
                val ang = partRng.nextFloat() * TAU
                val dx = cos(ang); val dy = sin(ang)
                // inward: start just outside the border, displace to the centre.
                addParticle(cx + dx * outer, cy + dy * outer, -dx * outer, -dy * outer, size)
            }
        }
        for ((species, amt) in cell.envCytOut) {
            speciesColorInto(species, speciesTmp)
            val n = min(PARTICLE_MAX_PER_SPECIES, (amt * PARTICLE_PER_UNIT).roundToInt())
            for (j in 0 until n) {
                val ang = partRng.nextFloat() * TAU
                val dx = cos(ang); val dy = sin(ang)
                // outward: start at the centre, displace out past the border.
                addParticle(cx, cy, dx * outer, dy * outer, size)
            }
        }
    }

    /** Append one particle (colour read from [speciesTmp]); silently drops if the pool is full. */
    private fun addParticle(x0: Float, y0: Float, dx: Float, dy: Float, size: Float) {
        if (partCount >= PARTICLE_MAX) return
        val i = partCount++
        partX0[i] = x0; partY0[i] = y0; partDX[i] = dx; partDY[i] = dy; partProg[i] = 0f
        partR[i] = speciesTmp[0]; partG[i] = speciesTmp[1]; partB[i] = speciesTmp[2]; partSize[i] = size
    }

    /** Age every live particle one frame, compacting out the dead, and additively draw the survivors as
     *  small species-coloured specks whose opacity fades in then out over their life (sin envelope). */
    private fun drawParticles() {
        var w = 0
        var inst = 0
        for (i in 0 until partCount) {
            val prog = partProg[i] + PARTICLE_PROG_SPEED
            if (prog >= 1f) continue                          // expired → drop (not compacted forward)
            // Keep: compact into slot w.
            partX0[w] = partX0[i]; partY0[w] = partY0[i]; partDX[w] = partDX[i]; partDY[w] = partDY[i]
            partProg[w] = prog
            partR[w] = partR[i]; partG[w] = partG[i]; partB[w] = partB[i]; partSize[w] = partSize[i]
            val x = partX0[i] + prog * partDX[i]
            val y = partY0[i] + prog * partDY[i]
            val alpha = sin(prog.toDouble() * PI).toFloat() * PARTICLE_MAX_ALPHA
            val s = partSize[i]
            matCircS.setScale(s, s)
            matCircT.setTranslation(x, y)
            matCircM.setProduct(matCircT, matCircS)
            mvpCirc.setProduct(matP, matCircM)
            mvpCirc.copyInto(buildMatrices, inst * Mat4.FLOATS)
            buildPrimaryIds[inst] = 0f
            buildShapes[inst] = 0f
            buildAlphas[inst] = alpha
            val tb = inst * 3
            buildTints[tb] = partR[i]; buildTints[tb + 1] = partG[i]; buildTints[tb + 2] = partB[i]
            inst++
            w++
        }
        partCount = w
        if (inst > 0) {
            GPU.setBlendFuncSrcAlphaOne()
            GPU.bindVertexArray(circleVao)
            circleShader.drawInstanced(
                vOffset = 0,
                instanceCount = inst,
                matricesColMajor = buildMatrices,
                primaryIds = buildPrimaryIds,
                shapes = buildShapes,
                alphas = buildAlphas,
                tintColorsRgb = buildTints,
            )
            GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()
        }
    }

    /** RGB of a single species token (r→R, g→G, b→B), normalised to its peak channel, then desaturated
     *  toward that peak by [PARTICLE_SATURATION] (keeps brightness, softens the hue), into [out]. */
    private fun speciesColorInto(token: String, out: FloatArray) {
        var r = 0; var g = 0; var b = 0
        for (ch in token) when (ch) { 'r' -> r++; 'g' -> g++; 'b' -> b++ }
        val peak = max(r, max(g, b))
        if (peak <= 0) { out[0] = 1f; out[1] = 1f; out[2] = 1f; return }
        // Lerp each channel toward 1 (the normalised peak) by (1 - saturation): full sat keeps the pure hue,
        // lower sat pulls it toward white without dimming.
        val t = 1f - PARTICLE_SATURATION
        out[0] = (r.toFloat() / peak).let { it + (1f - it) * t }
        out[1] = (g.toFloat() / peak).let { it + (1f - it) * t }
        out[2] = (b.toFloat() / peak).let { it + (1f - it) * t }
    }

    /**
     * Flow 4 (BIO→ENV decay): a species-coloured halo that pulses outward from each decaying cell's rim
     * into the surrounding environment, drawn **behind** the cell discs (so only the annulus past the cell
     * radius shows) and additively (a faint dispersing haze against the dark background / light field).
     * Same eased warm-up/cool-down envelope and staggered-pulse machinery as flow 3.
     */
    private fun drawDecayField(
        cells: Map<EntityId, CytoCellComponent>,
        transforms: ComponentTable<TransformComponent>,
        colliders: ComponentTable<ColliderComponent>,
    ) {
        decaySeen.clear()
        var count = 0
        for ((id, cell) in cells) {
            val eid = id.value
            // Decay is a rare discrete shed (one molecule every ~DEGRADE_PERIOD ticks), not a continuous
            // flow — so model it as an impulse: jump to the shed's magnitude when it fires, then cool down
            // slowly so each shed reads as a halo that flashes and disperses.
            val target = decayTargetFor(cell)
            val prev = decayIntensity[eid] ?: 0f
            val inten = if (target > prev) target else prev * DECAY_COOL
            decayIntensity[eid] = inten
            decaySeen.add(eid)
            if (inten <= DECAY_MIN_VISIBLE) continue
            val transform = transforms[id] ?: continue
            val collider = colliders[id] ?: continue
            val radius = CytoUnits.toLogical(collider.radius)
            val cx = CytoUnits.toLogical(transform.pos.x)
            val cy = CytoUnits.toLogical(transform.pos.y)
            averageSpeciesColor(cell.bioToEnv, speciesTmp)
            val offRaw = eid * 0.61803398f
            val base = pulsePhase + (offRaw - floor(offRaw))
            for (k in 0 until DECAY_PULSES) {
                if (count >= BUILD_MAX) break
                var frac = base + k.toFloat() / DECAY_PULSES
                frac -= floor(frac)
                val a = inten * (1f - frac) * DECAY_MAX_ALPHA
                if (a <= 0.003f) continue
                val r = radius * (1f + frac * (DECAY_MAX_SCALE - 1f))   // grow from the rim outward
                matCircS.setScale(r, r)
                matCircT.setTranslation(cx, cy)
                matCircM.setProduct(matCircT, matCircS)
                mvpCirc.setProduct(matP, matCircM)
                mvpCirc.copyInto(buildMatrices, count * Mat4.FLOATS)
                buildPrimaryIds[count] = 0f
                buildShapes[count] = 0f
                buildAlphas[count] = a.coerceIn(0f, 1f)
                val tb = count * 3
                buildTints[tb] = speciesTmp[0]; buildTints[tb + 1] = speciesTmp[1]; buildTints[tb + 2] = speciesTmp[2]
                count++
            }
        }
        if (decayIntensity.size > decaySeen.size) decayIntensity.keys.retainAll(decaySeen)
        if (count > 0) {
            GPU.setBlendFuncSrcAlphaOne()
            GPU.bindVertexArray(circleVao)
            circleShader.drawInstanced(
                vOffset = 0,
                instanceCount = count,
                matricesColMajor = buildMatrices,
                primaryIds = buildPrimaryIds,
                shapes = buildShapes,
                alphas = buildAlphas,
                tintColorsRgb = buildTints,
            )
        }
    }

    /** This tick's normalised BIO→ENV decay target for [cell] ∈ [0,1]: released count over [DECAY_REF]. */
    private fun decayTargetFor(cell: CytoCellComponent): Float {
        if (cell.bioToEnv.isEmpty()) return 0f
        var total = 0L
        for ((_, count) in cell.bioToEnv) total += count
        return (total.toFloat() / DECAY_REF).coerceIn(0f, 1f)
    }

    /** This tick's normalised CYT→BIO build target for [cell] ∈ [0,1]: total converted species count this
     *  tick over [BUILD_REF]. The per-cell easing (warm-up/cool-down) turns this into the disc opacity. */
    private fun buildTargetFor(cell: CytoCellComponent): Float {
        if (cell.cytToBio.isEmpty()) return 0f
        var total = 0L
        for ((_, count) in cell.cytToBio) total += count
        return (total.toFloat() / BUILD_REF).coerceIn(0f, 1f)
    }

    /** Average atom-mix colour of a per-species count [map] (r→R, g→G, b→B), normalised to its peak channel
     *  so the hue is the mix and the brightness is full. Grey-ish stays grey; a pure `rg` build reads yellow. */
    private fun averageSpeciesColor(map: Map<String, Int>, out: FloatArray) {
        var r = 0L; var g = 0L; var b = 0L
        for ((species, count) in map) for (ch in species) when (ch) {
            'r' -> r += count; 'g' -> g += count; 'b' -> b += count
        }
        val peak = max(r, max(g, b)).toDouble()
        if (peak <= 0) { out[0] = 1f; out[1] = 1f; out[2] = 1f; return }
        out[0] = (r / peak).toFloat(); out[1] = (g / peak).toFloat(); out[2] = (b / peak).toFloat()
    }

    /** Draw the static light field as a heatmap: project each grid cell (one torus tile) to NDC,
     *  cull off-screen, instance-draw the visible ones. Camera has no rotation, so world→NDC is a
     *  pure scale+translate and the axis-aligned cells stay axis-aligned. */
    private fun drawLightField() {
        if (!showLightField) return
        val aspect = resW / resH
        val hwx = viewHeight * aspect * 0.5f
        val hwy = viewHeight * 0.5f
        if (hwx <= 0f || hwy <= 0f) return
        val chx = fieldHalfLogical / hwx
        val chy = fieldHalfLogical / hwy
        var n = 0
        for (i in 0 until FIELD_CELLS) {
            val ndcX = (fieldCx[i] - centerX) / hwx
            val ndcY = (fieldCy[i] - centerY) / hwy
            if (ndcX < -1f - chx || ndcX > 1f + chx || ndcY < -1f - chy || ndcY > 1f + chy) continue
            val c2 = n * 2; val c4 = n * 4; val s4 = i * 4
            fInstCenter[c2] = ndcX; fInstCenter[c2 + 1] = ndcY
            fInstHalf[c2] = chx; fInstHalf[c2 + 1] = chy
            fInstColor[c4] = fieldColor[s4]; fInstColor[c4 + 1] = fieldColor[s4 + 1]
            fInstColor[c4 + 2] = fieldColor[s4 + 2]; fInstColor[c4 + 3] = 1f
            n++
        }
        if (n > 0) fieldShader.drawInstanced(n, fInstCenter, fInstHalf, fInstColor)
    }

    /** Draw the matter quad-tree as bordered leaf squares: project each visible leaf to NDC, colour it by
     *  its atom mix at low value, and draw a border pass then an inset fill pass so a 2px frame remains.
     *  Variable leaf sizes ⇒ walked + culled fresh each frame (the tree changes as cells refine/collapse it). */
    private fun drawMatterField(frame: CytoFrame) {
        if (!showMatterField) return
        val grid = frame.state.components.getTable<CytoMatterGridComponent>().asMap()[GRID_SINGLETON]?.grid ?: return
        val aspect = resW / resH
        val hwx = viewHeight * aspect * 0.5f
        val hwy = viewHeight * 0.5f
        if (hwx <= 0f || hwy <= 0f) return

        // Collect visible leaves (centre + half-extent in NDC, fill colour) in one tree walk, capped.
        var n = 0
        grid.forEachLeaf { x, y, size, store ->
            if (n >= MATTER_MAX_LEAVES) return@forEachLeaf
            val half = size * 0.5f
            val ndcX = (x + half - centerX) / hwx
            val ndcY = (y + half - centerY) / hwy
            val hX = half / hwx
            val hY = half / hwy
            if (ndcX + hX < -1f || ndcX - hX > 1f || ndcY + hY < -1f || ndcY - hY > 1f) return@forEachLeaf
            matCx[n] = ndcX; matCy[n] = ndcY; matHx[n] = hX; matHy[n] = hY
            leafColor(size, store, matColorTmp)
            val c4 = n * 4
            matFillColor[c4] = matColorTmp[0]; matFillColor[c4 + 1] = matColorTmp[1]
            matFillColor[c4 + 2] = matColorTmp[2]; matFillColor[c4 + 3] = 1f
            n++
        }
        if (n == 0) return

        // Fills inset by a 1px border on every side (NDC spans 2 over the axis pixel count).
        val borderNdcX = 0f * (2f / resW)
        val borderNdcY = 0f * (2f / resH)
        for (i in 0 until n) {
            val c2 = i * 2; val c4 = i * 4
            mInstCenter[c2] = matCx[i]; mInstCenter[c2 + 1] = matCy[i]
            mInstHalf[c2] = max(0f, matHx[i] - borderNdcX); mInstHalf[c2 + 1] = max(0f, matHy[i] - borderNdcY)
            mInstColor[c4] = matFillColor[c4]; mInstColor[c4 + 1] = matFillColor[c4 + 1]
            mInstColor[c4 + 2] = matFillColor[c4 + 2]; mInstColor[c4 + 3] = 1f
        }
        matterShader.drawInstanced(n, mInstCenter, mInstHalf, mInstColor)
    }

    /** Colour a matter leaf by its per-area r/g/b atom DENSITY as raw RGB (r→R, g→G, b→B), normalised by the
     *  leaf's area × the seed density so a full base-density leaf is white (1,1,1) and depletion both darkens
     *  it and shifts its hue away from whatever species the cells drew down. Counts scale with leaf area, so
     *  the divisor is the leaf's finest-cell area × [MATTER_REF_DENSITY]. */
    private fun leafColor(size: Float, store: org.emerge.demo.cyto.sim.MoleculeStore, out: FloatArray) {
        var r = 0L; var g = 0L; var b = 0L
        for (i in 0 until store.size) {
            val cnt = store.countAt(i)
            for (ch in SpeciesRegistry.string(store.idAt(i))) when (ch) {
                'r' -> r += cnt; 'g' -> g += cnt; 'b' -> b += cnt
            }
        }
        val across = (size / MATTER_FINEST_SIZE).toDouble()
        val denom = across * across * MATTER_REF_DENSITY
        out[0] = (r / denom).coerceIn(0.0, 1.0).toFloat()
        out[1] = (g / denom).coerceIn(0.0, 1.0).toFloat()
        out[2] = (b / denom).coerceIn(0.0, 1.0).toFloat()
    }

    fun cleanup() {
        shader.deleteProgram()
        bgShader.deleteProgram()
        fieldShader.deleteProgram()
        matterShader.deleteProgram()
        circleShader.deleteProgram()
        GPU.deleteBuffers(circleVbo)
        if (circleVao != null) GPU.deleteVertexArrays(circleVao)
        GPU.deleteTextures(cellTextureId)
    }

    private fun createWhiteTexture(): Int {
        val data = ByteArray(2 * 2 * 4) { 0xFF.toByte() }
        val id = GPU.genTextures()
        GPU.activeTexture(0)
        GPU.bindTexture2D(id)
        GPU.configureTexture2DRepeatLinear()
        GPU.uploadTextureRGBA8(2, 2, data)
        GPU.bindTexture2D(0)
        return id
    }

    private fun computeProjection() {
        val aspect = resW / resH
        val viewWidth = viewHeight * aspect
        matT.setTranslation(-centerX, -centerY)
        matS.setScale(2f / viewWidth, 2f / viewHeight)
        matP.setProduct(matS, matT)
    }

    /**
     * Colour a cell by its **contents**, per [colorMode]. Each r/g/b atom count maps to R/G/B,
     * normalised by the total so the colour represents the atom-ratio mix (e.g. equal parts → grey,
     * `ab`-only → yellow). The RGB is then scaled by a value factor: 1.0 for the focused cell,
     * 0.75 for normal cells, and [DIM_VALUE] for dimmed neighbours — keeping selection status visible
     * without distorting the colour hue via HSV saturation.
     */
    private fun cellColor(cell: CytoCellComponent, focused: Boolean, dimmed: Boolean) {
        val value = when {
            focused -> 1f
            dimmed -> DIM_VALUE
            else -> 0.75f
        }
        var r = 0L; var g = 0L; var b = 0L
        when (colorMode) {
            CellColorMode.Bio -> {
                for ((species, count) in cell.biomass) for (ch in species) when (ch) {
                    'r' -> r += count; 'g' -> g += count; 'b' -> b += count
                }
            }
            CellColorMode.Cyt -> {
                for ((species, count) in cell.cytoplasm) {
                    if (species.length < 2) continue   // ignore monomers (single-atom species)
                    for (ch in species) when (ch) {
                        'r' -> r += count; 'g' -> g += count; 'b' -> b += count
                    }
                }
            }
        }
        val peak = max(r, max(g, b)).toDouble()
        val scale = if (peak <= 0) 0.0 else value / peak
        colorTmp[0] = (r.toDouble() * scale).coerceIn(0.0, 1.0).toFloat()
        colorTmp[1] = (g.toDouble() * scale).coerceIn(0.0, 1.0).toFloat()
        colorTmp[2] = (b.toDouble() * scale).coerceIn(0.0, 1.0).toFloat()
    }

    private companion object {
        // Heatmap grid resolution per axis over one torus tile (the field is smooth, so coarse is
        // fine — a near-uniform tint up close, the 4 sources visible when zoomed out).
        const val FRES = 48
        const val FIELD_CELLS = FRES * FRES
        // Brightness of cells outside the focused cell's welded cluster — dark enough to recede, but
        // still faintly visible so the surrounding context isn't lost entirely.
        const val DIM_VALUE = 0.5f
        // Matter-overlay caps + look. Leaves are walked + culled to the visible region, so the cap only
        // bites when fully zoomed out over a deeply-refined tree (excess leaves are dropped, not wrapped).
        const val MATTER_MAX_LEAVES = 65535
        // Leaf counts scale with area; normalise by the finest leaf size + the seed density so a full
        // base-density leaf reads as white (1,1,1) regardless of how merged it is.
        val MATTER_FINEST_SIZE = CytoMatterField.TILE / (1 shl CytoMatterField.MAX_DEPTH)
        const val MATTER_REF_DENSITY = CytoSeed.MATTER_UNIFORM_LEVEL.toDouble()*4.0
        // Border colour: a neutral grey, visible against both the bright base fills and depleted dark ones.
        val MATTER_BORDER = floatArrayOf(0.1f, 0.1f, 0.1f)

        // ── Flow 3 (CYT→BIO "building") tuning — iterate via the agent harness. ──
        const val BUILD_MAX = CircleShader.MAX_INSTANCES
        // Per-frame easing toward the tick's build target (warm-up / cool-down rate). ~0.08 ⇒ ≈1s ramp.
        const val BUILD_EASE = 0.08f
        // Converted-count that maps to full build intensity (target saturates here).
        const val BUILD_REF = 120f
        // Peak opacity of a pulse at birth (frac→0), at full intensity. Additive; a few pulses overlap.
        const val BUILD_MAX_ALPHA = 0.7f
        // Below this eased intensity the glow is skipped (fully cooled down).
        const val BUILD_MIN_VISIBLE = 0.02f
        // Number of staggered expansion pulses per building cell — so there's always one mid-flight.
        const val BUILD_PULSES = 3
        // Phase advanced per rendered frame; 1/speed frames per full expansion (~0.015 ⇒ ≈1.1s at 60fps).
        const val BUILD_PULSE_SPEED = 0.015f

        // ── Flow 4 (BIO→ENV "decay") tuning. Halo pulses from the rim (1×) out to DECAY_MAX_SCALE×. ──
        // Impulse model: a shed jumps intensity to (count/REF), then it cools by DECAY_COOL each frame.
        const val DECAY_REF = 2f
        const val DECAY_COOL = 0.93f            // ~0.5s fade at 60fps
        const val DECAY_MAX_ALPHA = 0.85f
        const val DECAY_MIN_VISIBLE = 0.02f
        const val DECAY_PULSES = 2
        const val DECAY_MAX_SCALE = 1.5f

        // ── Flows 1 & 2 (ENV↔CYT transfer particles) tuning. ──
        const val PARTICLE_MAX = 6000                 // hard cap on live particles (excess spawns dropped)
        const val PARTICLE_PER_UNIT = 0.03f           // particles spawned per unit of species transfer/tick
        const val PARTICLE_MAX_PER_SPECIES = 5        // …capped per species per cell per tick
        const val PARTICLE_SIZE_FRAC = 0.035f         // speck radius as a fraction of the cell radius
        const val PARTICLE_SATURATION = 0.5f          // colour saturation of the specks (1 = full species hue)
        const val PARTICLE_OUTER = 1.25f              // spawn/endpoint radial distance in cell radii
        const val PARTICLE_PROG_SPEED = 0.025f        // life progress per frame (~40 frames ≈ 0.7s at 60fps)
        const val PARTICLE_MAX_ALPHA = 0.9f           // peak speck opacity (mid-life; additive)
        val TAU = (2.0 * PI).toFloat()
    }
}
