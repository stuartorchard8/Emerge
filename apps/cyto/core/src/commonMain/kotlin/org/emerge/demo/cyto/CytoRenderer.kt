package org.emerge.demo.cyto

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.SpeciesRegistry
import org.emerge.render.torus.ui.UiRectRenderer
import kotlin.time.TimeSource
import org.emerge.render.torus.shader.CircleShader
import org.emerge.render.torus.shader.TileShader
import org.emerge.render.torus.RenderTarget
import org.emerge.sim.core.physics.primitives.Vec2
import org.emerge.render.torus.GPU
import org.emerge.render.torus.GpuFloatBuffer
import org.emerge.render.torus.Mat4
import org.emerge.render.torus.put
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.round
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

    // ── Torus tiling ─────────────────────────────────────────────────────────────
    // One period of the world is rendered into [periodTarget], then repeated across the screen by
    // [tileShader]. Only the seam-straddling objects are drawn more than once (into the target), so the
    // number of world copies on screen is independent of the number of cells.
    private val periodTarget = RenderTarget()
    private val tileShader = TileShader()

    /**
     * Whether to route the world through the period texture, so that zooming out past the world's edges
     * shows the torus repeating rather than an island of life in an endlessly repeating ground.
     *
     * On by default. It engages only once a period fits on screen (see [periodTargetSize]), and measured on
     * a 4058-cell world it is a large win exactly where it engages — at 0.25× zoom, 53 → 93 FPS with p95
     * frame time falling from 50.7ms to 12.3ms — and neutral at 0.5×. Far out (0.0625×) it costs a little
     * more than the direct path while drawing some 256 copies of the world instead of one; both paths are
     * dominated there by the per-cell draw calls, which is a matter for instancing, not for tiling.
     */
    var tileWorld = true

    // The cell shader does `min(u_color, texture)`, so a flat white texture yields the
    // cell's colour; the disc shape + shading come from the shader, not the texture. Built
    // procedurally so no PNG asset is needed (works identically on desktop/Android/web).
    private val cellTextureId = createWhiteTexture()

    private var resW = 1f
    private var resH = 1f

    // Camera centre as a torus [Coord] per axis (like Scavengers/Drockets): panning adds fixed-point offsets
    // that wrap for free via two's-complement Int overflow, so the camera never drifts into unbounded space
    // and every object is drawn at its shortest torus delta from the camera → seamless edge wrapping.
    private var cameraX = Coord(0)
    private var cameraY = Coord(0)
    // Camera position in logical units, refreshed once per frame in [computeProjection] — the base the
    // per-object [viewX]/[viewY] wrap against (and what the field shaders take as their centre).
    private var camLogX = 0f
    private var camLogY = 0f
    private var viewHeight = 100f

    // Geometry of the pass currently being drawn — the screen (centred on the camera) or the period texture
    // (centred on the world origin, one period across). Set by [computeProjection] /
    // [computePeriodProjection]; read by everything in [drawWorldLayer], which is what lets one body of
    // drawing code serve both.
    private var passCenterX = 0f
    private var passCenterY = 0f
    private var passHalfW = 50f
    private var passHalfH = 50f
    private var passPixelsH = 1f
    private var periodPass = false

    /** Pass pixels per world unit — the scale at which detail gates ("is this cell big enough to draw
     *  specks in?") have to be judged, since the period texture's resolution is not the screen's. */
    private val passPxPerUnit: Float get() = passPixelsH / (2f * passHalfH)

    private val matP = Mat4.scratch()
    private val matM = Mat4.scratch()
    private val matMS = Mat4.scratch()
    private val matMT = Mat4.scratch()
    private val mvp = Mat4.scratch()
    private val colorTmp = FloatArray(4)
    private val neighbourTmp = FloatArray(CytoCellShader.MAX_NEIGHBOURS * 4)

    private val BG_CENTER = floatArrayOf(0f, 0f)
    private val BG_HALF_SIZE = floatArrayOf(1f, 1f)
    private val BG_COLOR = floatArrayOf(0f, 0f, 0f, 1f)

    // ── daylight (the energy landscape, applied as a white multiply over the finished scene) ──────────────
    // Not a layer of its own: the ground (matter) and the cells draw in their own pigment colours, then one
    // full-screen pass multiplies the lot by the band's level. So light and matter share every pixel — hue is
    // pigment, brightness is light — and neither has to be toggled off to see the other.

    /**
     * How much light the world gets at full night, as a multiplier on the whole scene; the daylight band's
     * peak is always 1.0. So 0.5 = night is half as bright as noon, and 1.0 = no day/night contrast at all
     * (a flat, always-lit world). Player-facing: this is the dial that replaces the old light/matter overlay
     * toggle — instead of swapping which field you can see, you choose how strongly day reads against night,
     * with both fields visible the whole time. The host overwrites this each frame from
     * `CytoControls.nightLevel`, whose ladder is the source of truth for the values a player can pick.
     */
    var nightLevel = 0.25f
    /** Per-gene specks inside each cell ([drawGeneParticles]). Off is a pure visual subtraction — used by
     *  the render benchmark to attribute their cost, and available as a host-side quality knob. */
    var showGeneParticles = true
    /** CPU micros spent in the last [rasterizeMatter] (quad-tree walk → texel fill), and leaves visited.
     *  Read by the render benchmark to split the matter overlay's cost into CPU raster vs GPU sample. */
    var lastRasterizeUs = 0L; private set
    var lastTexelCount = 0; private set
    /** How cells are coloured (Host-set from the controls' "Color" button). */
    var colorMode = CellColorMode.Bio
    /** Which species the matter ground shows, as a [SpeciesRegistry] id; -1 (the default) = all of them
     *  summed, the combined nutrient topology. Host-set from the LAYERS sheet's MATTER LAYER rows. */
    var matterSpeciesId = -1
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
        cameraX = Coord(0); cameraY = Coord(0)
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

    /** Apply the follow target to the camera centre ([cameraX]/[cameraY]) using damped spring.
     *  Call once per frame in [draw], before any NDC computation. */
    private fun applyFollow() {
        if (followId < 0) return
        val damping = FOLLOW_DAMPING
        // Spring toward the target along the SHORTEST torus path (wrapLogical of the logical delta), so a
        // target that has wrapped around an edge is chased across the seam, not the long way round.
        val camLX = CytoUnits.toLogical(cameraX)
        val camLY = CytoUnits.toLogical(cameraY)
        // Offset the target by the free-area pixel shift (see setFollowOffsetPx). worldPerPx = viewHeight/resH
        // (square scaling). Screen +x = world +x, screen +y (down) = world -y, so camera moves the opposite
        // way to slide the cell toward the free centre.
        val worldPerPx = viewHeight / resH
        val tgtX = followX - followOffPxX * worldPerPx
        val tgtY = followY + followOffPxY * worldPerPx
        var vx = followVX
        var vy = followVY
        vx += wrapLogical(tgtX - camLX) * damping
        vy += wrapLogical(tgtY - camLY) * damping
        val frac = 1f / 60f
        vx *= frac
        vy *= frac
        cameraX += CytoUnits.len(vx)
        cameraY += CytoUnits.len(vy)
        followVX = vx
        followVY = vy
    }
    /** EntityId.values of the focused cell's directly-welded neighbours, rebuilt each frame in [draw]
     *  (cleared, then refilled — no per-frame allocation). When a cell is focused, every cell NOT in
     *  this set and not the focused cell itself is dimmed, so the welded cluster stands out. */
    private val focusNeighbours = HashSet<Int>()
    // The light field is drawn as a single full-screen triangle whose fragment shader evaluates the moving
    // daylight band analytically per pixel — continuous by construction, no mesh or per-frame baking.
    private val lightFieldShader = CytoLightFieldShader()

    // ── matter-field overlay (the adaptive quad-tree reservoir, drawn as bordered leaf squares) ──
    // Each visible leaf → a 2px-bordered square: fill is the leaf's per-area a/b/c atom DENSITY as raw RGB,
    // normalised so a full base-density leaf is white and depletion darkens + discolours it (the channel the
    // cells drained drops out). Borders are drawn first, fills (inset by the border width) painted on top,
    // The matter field is rasterised (quad-tree → a fixed-res RGBA density texture) each frame, then drawn as
    // one full-screen triangle sampling it with GL_REPEAT + linear filtering — so it reads as a smooth,
    // torus-wrapped density cloud covering the whole screen (no tile edge, no leaf cap). Matches the light
    // field's full-screen treatment.
    private val matterField = CytoMatterFieldTexture(MATTER_TEX_RES)
    private val matterPixels = ByteArray(MATTER_TEX_RES * MATTER_TEX_RES * 4)
    // Draw-thread-owned scratch for the field's per-channel tally — sized to the field on first use (its
    // resolution tracks the world size, so it isn't known here). See [rasterizeMatter] on why these are ours.
    private var chRedTmp = IntArray(0)
    private var chGreenTmp = IntArray(0)
    private var chBlueTmp = IntArray(0)

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
    private val buildShapes = FloatArray(BUILD_MAX)      // soft discs, except the decay haloes (annuli)
    private val buildAlphas = FloatArray(BUILD_MAX)
    private val buildTints = FloatArray(BUILD_MAX * 3)
    /** Per-cell eased build intensity, keyed by EntityId.value; evicted when the cell is absent. */
    private val buildIntensity = HashMap<Int, Float>()
    private val buildSeen = HashSet<Int>()
    /** Per-cell eased BIO→ENV decay intensity (flow 4), same keying/eviction as [buildIntensity]. */
    private val decayIntensity = HashMap<Int, Float>()
    private val decaySeen = HashSet<Int>()
    /** Per-cell attack-envelope goal for the decay halo (flow 4): the shed level it's easing up toward; 0
     *  once reached (released). Keyed/evicted with [decayIntensity]. */
    private val decayGoal = HashMap<Int, Float>()
    /** Per-cell eased flow colour (RGB), keyed by EntityId.value — the disc/halo hue drifts toward the
     *  currently-transferring species' colour a little each frame instead of snapping, so a cell that
     *  switches which species it's building/shedding cross-fades. Evicted alongside the intensity maps. */
    private val buildColor = HashMap<Int, FloatArray>()
    private val decayColor = HashMap<Int, FloatArray>()
    /** Global pulse phase [0,1) — the expansion clock for the build glow (per-cell it's offset so cells
     *  don't pulse in lockstep). Advanced by [animDt], so it stops dead when the world is paused. */
    private var pulsePhase = 0f

    // ── Perceived-time clock ────────────────────────────────────────────────────────────────────
    // Every cosmetic animation in here (pulses, gene drift, particle life, the intensity/colour eases)
    // used to advance once per *rendered frame*. That made the world look alive while it was paused —
    // specks still wandering, discs still breathing — so "paused" stopped reading as paused. They now
    // advance by [animDt]: how much SIM time passed since the last draw, measured in units of "one frame
    // at realtime speed", so the tuning constants below keep their old meaning at 64 TPS / 60 FPS, the
    // visuals speed up and slow down with the speed control, and they freeze when the sim does.
    /** frame.tick at the previous draw; negative until the first frame. */
    private var lastAnimTick = -1L
    /** Sim time elapsed since the last draw, in nominal frames. 0 while paused. */
    private var animDt = 0f

    // ── Gene particles ──────────────────────────────────────────────────────────────────────────
    /** Per-cell eased gene brightness (one entry per gene), keyed by EntityId.value and evicted like
     *  [buildIntensity]. The only per-particle state there is — position is procedural (see
     *  [drawGeneParticles]). */
    private val geneBright = HashMap<Int, FloatArray>()
    private val geneSeen = HashSet<Int>()
    /** Drift clock for the gene specks, advanced by [animDt] like [pulsePhase] — the wander tracks the
     *  world's speed and stops with it. */
    private var geneTime = 0f

    // ── ENV↔CYT transfer particles (flows 1 & 2) ────────────────────────────────────────────────
    // Discrete world-space particles: spawned each *tick* in proportion to that tick's membrane transfer,
    // then aged every *frame* (independent of tick rate). Flow 1 (absorption) drifts a species-coloured
    // speck from just outside the cell inward to the centre; flow 2 (secretion) is the reverse. A particle
    // is anchored to a cell: its world position is (anchor cell centre) + (spawn offset) + progress·
    // (displacement), so specks track the cell as it moves instead of lagging behind at their spawn point.
    // The alpha envelope fades it in then out (sin(π·progress)).
    private val partOffX = FloatArray(PARTICLE_MAX)   // spawn offset from the anchor cell's centre
    private val partOffY = FloatArray(PARTICLE_MAX)
    private val partBaseX = FloatArray(PARTICLE_MAX)  // anchor cell's centre, refreshed each frame (frozen if it dies)
    private val partBaseY = FloatArray(PARTICLE_MAX)
    private val partCell = IntArray(PARTICLE_MAX)     // anchor cell EntityId.value (which cell the speck rides)
    private val partDX = FloatArray(PARTICLE_MAX)
    private val partDY = FloatArray(PARTICLE_MAX)
    private val partProg = FloatArray(PARTICLE_MAX)
    private val partR = FloatArray(PARTICLE_MAX)
    private val partG = FloatArray(PARTICLE_MAX)
    private val partB = FloatArray(PARTICLE_MAX)
    private val partSize = FloatArray(PARTICLE_MAX)
    private var partCount = 0
    private var lastParticleTick = -1L
    // Adaptive spawn throttle: when the pool saturates, later-iterated cells get their spawns dropped purely
    // by draw order — unfair. Instead we scale *every* cell's spawn demand this tick by [spawnScale],
    // recomputed once per tick from the previous tick's saturation (AIMD): multiplicatively back off when
    // spawns were dropped, additively recover toward 1 when there was slack. Uniform across cells within a
    // tick, so the budget splits fairly by transfer magnitude regardless of iteration order. spawnScale
    // starts low so the world's particles ease in over the first few ticks rather than bursting all at once
    // (a synchronised batch that would fade in/out in unison — a global pulse).
    private var spawnScale = 0.05f
    private var spawnAttempts = 0
    private var spawnDropped = 0
    private val partRng = kotlin.random.Random(0x0CEE)
    // Welded-neighbour unit directions (logical world space) for the cell being spawned — particles are
    // biased onto the *exposed* surface, so specks don't appear on the seams between bonded cells (which
    // read as cell↔cell transfer). Filled per cell by gatherWeldDirs.
    private val weldDirX = FloatArray(WELD_DIR_MAX)
    private val weldDirY = FloatArray(WELD_DIR_MAX)
    private val weldLen = FloatArray(WELD_DIR_MAX)   // centre-to-centre distance to each welded neighbour
    private val weldOtherId = IntArray(WELD_DIR_MAX)  // EntityId.value of each welded neighbour (speck-B anchor)
    private val matCircS = Mat4.scratch()
    private val matCircT = Mat4.scratch()
    private val matCircM = Mat4.scratch()
    private val mvpCirc = Mat4.scratch()
    private val speciesTmp = FloatArray(3)

    init {
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
    fun setResolution(widthPx: Float, heightPx: Float) {
        resW = max(1f, widthPx)
        resH = max(1f, heightPx)
        GPU.setViewport(0, 0, resW.toInt(), resH.toInt())
    }

    // ── viewport recentre ────────────────────────────────────────────────────────
    // When a panel/sheet covers an edge (the L2 cell sheet at the bottom, or the wide docked panels on the
    // right), the followed cell should sit in the middle of the *unobscured* area, not behind the sheet. We
    // do this by panning the camera target (not the projection) so the whole scene — cells, light/matter
    // fields, culling and screen<->world mapping — stays consistent; only follow is affected.
    private var followOffPxX = 0f
    private var followOffPxY = 0f

    /** Shift the follow target so the followed cell renders offset from the screen centre by this many
     *  framebuffer pixels ([dxPx] right, [dyPx] down). Pass (0, 0) to centre normally. The camera eases to
     *  the new target via the usual follow damping, so opening/closing a sheet slides the view smoothly. */
    fun setFollowOffsetPx(dxPx: Float, dyPx: Float) {
        followOffPxX = dxPx
        followOffPxY = dyPx
    }

    /** Jump the camera straight to the (offset) follow target, skipping the damping — for a deterministic
     *  single-frame capture (the agent harness). The live game eases instead. */
    fun snapFollow() {
        if (followId < 0) return
        val worldPerPx = viewHeight / resH
        cameraX = CytoUnits.coord(followX - followOffPxX * worldPerPx)
        cameraY = CytoUnits.coord(followY + followOffPxY * worldPerPx)
        followVX = 0f; followVY = 0f
    }

    fun panByPixels(dxPx: Float, dyPx: Float) {
        val worldPerPx = viewHeight / resH
        cameraX -= CytoUnits.len(dxPx * worldPerPx)
        cameraY += CytoUnits.len(dyPx * worldPerPx)
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
        cameraX += CytoUnits.len(before[0] - after[0])
        cameraY += CytoUnits.len(before[1] - after[1])
    }

    /** Framebuffer pixel -> logical world `[x, y]`. Logical is relative to the (wrapped) camera and may fall
     *  outside `[-HALF, HALF)`; callers convert it back to a torus [Coord] (which wraps), so that's fine. */
    fun screenToWorld(px: Float, py: Float): FloatArray {
        val aspect = resW / resH
        val viewWidth = viewHeight * aspect
        val ndcX = px / resW * 2f - 1f
        val ndcY = 1f - py / resH * 2f
        return floatArrayOf(
            CytoUnits.toLogical(cameraX) + ndcX * viewWidth * 0.5f,
            CytoUnits.toLogical(cameraY) + ndcY * viewHeight * 0.5f,
        )
    }

    /** The logical world position `[x, y]` the camera is centred on — i.e. what the player is looking at.
     *  The campaign's Reset drops the re-seeded cell here, so it lands under their eye rather than at a
     *  world origin that may be off-screen. Every host asks the same question, so it lives here rather than
     *  each one re-deriving its own framebuffer midpoint. */
    fun cameraCentreWorld(): FloatArray = screenToWorld(resW * 0.5f, resH * 0.5f)

    /** Logical world (x, y) -> framebuffer pixel `[px, py]` (inverse of [screenToWorld]). Uses the shortest
     *  torus delta to the camera, so a point that has wrapped past an edge maps to the near side of the view. */
    fun worldToScreen(worldX: Float, worldY: Float): FloatArray {
        val aspect = resW / resH
        val viewWidth = viewHeight * aspect
        val ndcX = wrapLogical(worldX - CytoUnits.toLogical(cameraX)) / (viewWidth * 0.5f)
        val ndcY = wrapLogical(worldY - CytoUnits.toLogical(cameraY)) / (viewHeight * 0.5f)
        return floatArrayOf(
            (ndcX + 1f) * 0.5f * resW,
            (1f - ndcY) * 0.5f * resH,
        )
    }

    /**
     * Draw a frame.
     *
     * The world layer is drawn either straight to the screen or, when [tileWorld] is on, once into
     * [periodTarget] and then tiled across the screen by [tileShader]. The tiled path is what lets the view
     * zoom out past the world's own edges: the repeats cost one texture fetch per pixel rather than a
     * redraw of every cell per repeat.
     */
    fun draw(frame: CytoFrame) {
        advanceAnimClock(frame.tick)
        applyFollow()
        computeProjection()

        val targetPx = periodTargetSize()
        val tiling = targetPx > 0 && periodTarget.resize(targetPx, targetPx)
        if (tiling) {
            computePeriodProjection(targetPx.toFloat())
            periodTarget.begin()
            drawWorldLayer(frame)
            periodTarget.end(resW.toInt(), resH.toInt())

            // Restore the screen projection so anything reading pass geometry after the world layer (and the
            // next frame's screen-space queries) sees the camera, not the period.
            computeProjection()

            val aspect = resW / resH
            GPU.disableBlend()
            tileShader.useFullViewport(resW, resH)
            tileShader.draw(
                periodTextureId = periodTarget.textureId,
                center = Vec2(camLogX, camLogY),
                viewHalfExtent = Vec2(viewHeight * aspect * 0.5f, viewHeight * 0.5f),
                period = Vec2(CytoLightField.SPAN, CytoLightField.SPAN),
            )
        } else {
            drawWorldLayer(frame)
        }
    }

    /**
     * Edge length in pixels for the period texture, or 0 to draw straight to the screen instead.
     *
     * The target holds one period of the world, so it is sized to what one period currently occupies on
     * screen — roughly one texel per screen pixel, which is what keeps the tiled view as sharp as the direct
     * one and means there is no minification for mipmaps to handle.
     *
     * Tiling only engages once a period is no larger than the screen — that is exactly the point where the
     * world's edges come into view and there is something to repeat. Zoomed in past that, the direct path is
     * both correct and cheaper, and the target would be larger than the screen for no visible gain. That
     * bound is what keeps the tiled pass from ever costing more fill than drawing to the screen would.
     *
     * Sizes are quantised, since zoom is continuous and resizing every frame would reallocate constantly and
     * shimmer. The step is deliberately much smaller than a doubling: rounding up to a power of two would
     * waste up to 4× the fill. Within a step the blit rescales slightly, which is invisible.
     */
    private fun periodTargetSize(): Int {
        if (!tileWorld) return 0
        val periodPx = CytoLightField.SPAN * (resH / viewHeight)
        if (!periodPx.isFinite() || periodPx <= 0f) return 0
        if (periodPx > max(resW, resH)) return 0    // zoomed in: no edges in view, nothing to repeat
        val steps = ceil(periodPx / TILE_SIZE_STEP.toFloat()).toInt()
        return (steps * TILE_SIZE_STEP).coerceIn(TILE_MIN_PX, TILE_MAX_PX)
    }

    private fun drawWorldLayer(frame: CytoFrame) {
        // Background fill (opaque) — clears the frame.
        GPU.disableBlend()
        bgShader.drawInstanced(1, BG_CENTER, BG_HALF_SIZE, BG_COLOR)
        // The GROUND is the matter field, in its own pigment colours and unlit — the nutrient topology.
        // Daylight is not a layer here; it lands at the end of the pass as a multiply over everything.
        drawMatterField(frame)

        val components = frame.state.components
        val cells = components.getTable<CytoCellComponent>().asMap()
        val transforms = components.getTable<TransformComponent>()
        val colliders = components.getTable<ColliderComponent>()

        // Advance the pulse clock shared by the metabolic fields (flows 3 & 4).
        pulsePhase += BUILD_PULSE_SPEED * animDt
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
        if (spawnParticles) {
            // Update the throttle from the *previous* spawn-tick's saturation, then reset its counters.
            if (spawnDropped > 0 && spawnAttempts > 0) {
                val fit = (spawnAttempts - spawnDropped).toFloat() / spawnAttempts
                spawnScale = max(SPAWN_SCALE_MIN, spawnScale * fit)
            } else {
                spawnScale = min(1f, spawnScale + SPAWN_SCALE_RECOVER)
            }
            spawnAttempts = 0
            spawnDropped = 0
        }
        var buildCount = 0
        for ((id, cell) in cells) {
            val transform = transforms[id] ?: continue
            val collider = colliders[id] ?: continue
            val radius = CytoUnits.toLogical(collider.radius)
            val cx = CytoUnits.toLogical(transform.pos.x)
            val cy = CytoUnits.toLogical(transform.pos.y)

            if (spawnParticles) {
                val weldCount = gatherWeldDirs(id, transform, springData, springTable, transforms)
                spawnTransferParticles(id.value, cx, cy, radius, cell, weldCount)
                if (weldCount > 0) spawnWeldParticles(id.value, cx, cy, radius, cell, weldCount)
            }

            // Flow 3 (CYT→BIO): ease this cell's build intensity toward this tick's converted amount,
            // then stage a soft disc if it's building enough to see. Keyed by EntityId; evicted below.
            val eid = id.value
            val buildTarget = buildTargetFor(cell)
            val inten = easeToward(buildIntensity[eid] ?: 0f, buildTarget, BUILD_EASE)
            buildIntensity[eid] = inten
            buildSeen.add(eid)
            // Drift the disc hue toward the species currently being built (only while there IS a transfer;
            // during cool-down we hold the last colour so a finished build fades out in its own hue, not grey).
            if (cell.cytToBio.isNotEmpty()) {
                averageSpeciesColor(cell.cytToBio, speciesTmp)
                easeFlowColor(buildColor, eid, speciesTmp, FLOW_COLOR_EASE)
            }
            if (inten > BUILD_MIN_VISIBLE) {
                buildColor[eid]?.let { speciesTmp[0] = it[0]; speciesTmp[1] = it[1]; speciesTmp[2] = it[2] }
                    ?: averageSpeciesColor(cell.cytToBio, speciesTmp)
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
                    forEachSeamImage(viewX(cx), viewY(cy), radius) { ix, iy ->
                        if (buildCount < BUILD_MAX) {
                            matCircT.setTranslation(ix, iy)
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
                }
            }

            matMS.setScale(2f * radius, 2f * radius)

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

            // The neighbour deltas are relative, so an image of this cell at the far edge of the period
            // renders identically — only the placement differs.
            forEachSeamImage(viewX(cx), viewY(cy), radius) { ix, iy ->
                matMT.setTranslation(ix, iy)
                matM.setProduct(matMT, matMS)
                mvp.setProduct(matP, matM)
                shader.draw(
                    mvp = mvp,
                    radiusUniform = radius * 2f,
                    color = colorTmp,
                    neighbours = neighbourTmp,
                    count = count,
                )
            }
        }
        // Evict intensity state for cells that vanished (died/off-frame) — same pattern as focusNeighbours.
        if (buildIntensity.size > buildSeen.size) {
            buildIntensity.keys.retainAll(buildSeen); buildColor.keys.retainAll(buildSeen)
        }

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
        drawParticles(transforms)

        // One speck per gene, drifting in each cell's hollow centre.
        if (showGeneParticles) drawGeneParticles(cells, transforms, colliders)

        // Daylight, LAST: a flat white multiply over the finished world — ground, cells, particles and all.
        // The band is light falling on the scene, not a layer of its own, so the pigments underneath keep
        // their hue and just grow more vibrant under it; nothing is hidden, which is why the matter topology
        // and the light band can share every pixel. The host draws the UI after this, so the UI is unaffected.
        drawLightMultiply(frame.tick)

        GPU.disableBlend()
    }

    /**
     * Draws one particle per gene inside each cell's transparent centre: hue = the gene's energy source
     * (white for Light, the bond's species colour for BreakBond), brightness eased between
     * [GENE_INACTIVE_VALUE] and 1 as the gene's condition flips.
     *
     * Motion is **procedural, not simulated**: each speck's offset is a sum of two sines per axis, seeded
     * from `hash(cellId, geneIndex)`, read off a shared [SIN_LUT]. Two incommensurate frequencies per axis
     * make the path quasi-periodic, so it wanders rather than tracing an obvious Lissajous loop. Nothing is
     * stored per particle and nothing integrates, so the drift costs a few table lookups, survives
     * save/load, and can't accumulate error. Only the eased brightness is remembered (per cell, keyed like
     * [buildIntensity]).
     *
     * The genome is ~17 genes across every cell, so a full world would be ~43k specks — far past what's
     * legible or cheap. Two gates keep the real count low: cells smaller than [GENE_MIN_CELL_PX] on screen
     * are skipped (17 specks in a 10px cell is mush), as are cells outside the view. Both are cheap
     * per-cell tests that run before any per-gene work.
     */
    private fun drawGeneParticles(
        cells: Map<EntityId, CytoCellComponent>,
        transforms: ComponentTable<TransformComponent>,
        colliders: ComponentTable<ColliderComponent>,
    ) {
        geneTime += GENE_TIME_STEP * animDt
        geneSeen.clear()
        // Judged against the pass being drawn, not the screen: in the period pass a cell is as big as it is
        // in the texture, which past a certain zoom-out is too small to carry specks at all.
        val pxPerUnit = passPxPerUnit
        // Half-extents of the pass in logical units, for the off-view test.
        val halfH = passHalfH
        val halfW = passHalfW
        var inst = 0

        for ((id, cell) in cells) {
            val genome = cell.genome
            if (genome.isEmpty()) continue
            val collider = colliders[id] ?: continue
            val radius = CytoUnits.toLogical(collider.radius)
            if (radius * pxPerUnit < GENE_MIN_CELL_PX) continue      // too small to read → skip
            val transform = transforms[id] ?: continue
            val vx = viewX(CytoUnits.toLogical(transform.pos.x))
            val vy = viewY(CytoUnits.toLogical(transform.pos.y))
            if (vx + radius < -halfW || vx - radius > halfW) continue // off-screen → skip
            if (vy + radius < -halfH || vy - radius > halfH) continue

            geneSeen.add(id.value)
            var bright = geneBright[id.value]
            if (bright == null || bright.size != genome.size) {
                // First sight of this cell, or its genome changed length (mutation) — (re)start the ease
                // from the inactive floor rather than from black.
                bright = FloatArray(genome.size) { GENE_INACTIVE_VALUE }
                geneBright[id.value] = bright
            }

            val size = radius * GENE_SIZE_FRAC
            // Keep specks inside the hollow centre: the shader draws a CytoCellShader.MEMBRANE_BORDER-thick
            // membrane in world units, so the free interior shrinks as the cell does (and can vanish).
            val orbit = radius - CytoCellShader.MEMBRANE_BORDER - size
            if (orbit <= 0f) continue

            for (i in genome.indices) {
                if (inst >= GENE_MAX) break
                val active = i < 64 && (cell.activeMask ushr i) and 1L == 1L
                val target = if (active) 1f else GENE_INACTIVE_VALUE
                bright[i] = easeToward(bright[i], target, GENE_EASE)
                val v = bright[i]

                val h = hash32(id.value * 31 + i)
                // Four phases + four frequencies from one hash; frequencies are spread over an irrational-ish
                // spacing so the two sines on an axis don't re-align into a closed loop.
                val ox = sinLut(geneTime * GENE_FREQ_A + phaseOf(h, 0)) * 0.6f +
                    sinLut(geneTime * GENE_FREQ_B + phaseOf(h, 8)) * 0.4f
                val oy = sinLut(geneTime * GENE_FREQ_C + phaseOf(h, 16)) * 0.6f +
                    sinLut(geneTime * GENE_FREQ_D + phaseOf(h, 24)) * 0.4f

                geneColorInto(genome[i].source, speciesTmp)
                matCircS.setScale(size, size)
                forEachSeamImage(vx + ox * orbit, vy + oy * orbit, size) { ix, iy ->
                    if (inst < GENE_MAX) {
                        matCircT.setTranslation(ix, iy)
                        matCircM.setProduct(matCircT, matCircS)
                        mvpCirc.setProduct(matP, matCircM)
                        mvpCirc.copyInto(buildMatrices, inst * Mat4.FLOATS)
                        buildPrimaryIds[inst] = 0f
                        buildShapes[inst] = 0f
                        buildAlphas[inst] = GENE_ALPHA
                        val tb = inst * 3
                        // Scale the hue by the eased brightness — an inactive gene is the same colour at
                        // GENE_INACTIVE_VALUE of the value, not a different (washed-out) colour.
                        buildTints[tb] = speciesTmp[0] * v
                        buildTints[tb + 1] = speciesTmp[1] * v
                        buildTints[tb + 2] = speciesTmp[2] * v
                        inst++
                    }
                }
            }
            if (inst >= GENE_MAX) break
        }

        if (geneBright.size > geneSeen.size) geneBright.keys.retainAll(geneSeen)

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

    /** A gene's particle hue: white for Light (which has no species), else the colour of the bond the gene's
     *  synthesis forms — the molecule its metabolism is actually built around.
     *  [speciesColorInto] already yields white for a token with no colour channels. */
    private fun geneColorInto(source: EnergySource, out: FloatArray) =
        speciesColorInto(if (source is EnergySource.FormBond) source.bond else "", out)

    /** Phase in radians from 8 bits of [h] starting at [shift]. */
    private fun phaseOf(h: Int, shift: Int): Float = ((h ushr shift) and 0xFF) * (TAU / 256f)

    /** Nearest-entry sine lookup. At [SIN_LUT_SIZE] entries the worst-case error is ~0.006 of the
     *  amplitude — invisible on a drifting speck, and it turns four `sin` calls per particle per frame
     *  into four array reads. */
    private fun sinLut(radians: Float): Float {
        val i = (radians * (SIN_LUT_SIZE / TAU)).toInt() and (SIN_LUT_SIZE - 1)
        return SIN_LUT[i]
    }

    private fun hash32(x: Int): Int {
        var h = x * -0x61c88647
        h = h xor (h ushr 15); h *= -0x7ee3623b; h = h xor (h ushr 13)
        return h
    }

    /** Spawn this tick's ENV↔CYT particles for one cell: [envCytIn] → inward absorption specks (flow 1),
     *  [envCytOut] → outward secretion specks (flow 2), a count per species ∝ that species' transfer.
     *  [weldCount] welded-neighbour directions (in [weldDirX]/[weldDirY]) bias spawns onto the exposed
     *  surface; a fully-enclosed cell finds no exposed angle and so emits ~no env particles. */
    private fun spawnTransferParticles(cellId: Int, cx: Float, cy: Float, radius: Float, cell: CytoCellComponent, weldCount: Int) {
        val size = radius * PARTICLE_SIZE_FRAC
        val outerR = radius * PARTICLE_OUTER      // 1.125r — just outside the border
        val innerR = radius * PARTICLE_INNER      // 0.875r — just inside the border
        val bandR = outerR - innerR               // radial span a speck travels
        for ((species, amt) in cell.envCytIn) {
            speciesColorInto(species, speciesTmp)
            val n = min(PARTICLE_MAX_PER_SPECIES, (amt * PARTICLE_PER_UNIT * spawnScale).roundToInt())
            for (j in 0 until n) {
                val ang = pickExposedAngle(weldCount); if (ang < 0f) continue
                val dx = cos(ang); val dy = sin(ang)
                // inward: start at 1.125r, displace inward to 0.875r (peak brightness at the border).
                addParticle(cellId, cx, cy, dx * outerR, dy * outerR, -dx * bandR, -dy * bandR, size)
            }
        }
        for ((species, amt) in cell.envCytOut) {
            speciesColorInto(species, speciesTmp)
            val n = min(PARTICLE_MAX_PER_SPECIES, (amt * PARTICLE_PER_UNIT * spawnScale).roundToInt())
            for (j in 0 until n) {
                val ang = pickExposedAngle(weldCount); if (ang < 0f) continue
                val dx = cos(ang); val dy = sin(ang)
                // outward: start at 0.875r, displace outward to 1.125r.
                addParticle(cellId, cx, cy, dx * innerR, dy * innerR, dx * bandR, dy * bandR, size)
            }
        }
    }

    /** Flow 5 (CYT→CYT across welds): each unit of [weldOut] spawns *two* uncorrelated specks — one leaving
     *  THIS cell outward toward a welded neighbour (0.875r→1.125r), one entering the NEIGHBOUR from the seam
     *  side (its 1.125r→0.875r). Both directions get an independent ±[WELD_JITTER] angular jitter around the
     *  weld line so the exchange reads organic rather than a rigid centre-to-centre beam. The two halves need
     *  no positional correlation; showing both in equal measure just balances the send/receive read. */
    private fun spawnWeldParticles(cellId: Int, cx: Float, cy: Float, radius: Float, cell: CytoCellComponent, weldCount: Int) {
        if (cell.weldOut.isEmpty()) return
        val size = radius * PARTICLE_SIZE_FRAC
        val spanR = (WELD_PARTICLE_END - WELD_PARTICLE_START) * radius   // constant travel length (radii)
        for ((species, amt) in cell.weldOut) {
            speciesColorInto(species, speciesTmp)
            val n = min(PARTICLE_MAX_PER_SPECIES, (amt * WELD_PER_UNIT * spawnScale).roundToInt())
            for (j in 0 until n) {
                val i = partRng.nextInt(weldCount)          // pick one of this cell's welds
                val base = atan2(weldDirY[i], weldDirX[i])
                // Speck A: leaves this cell outward toward the neighbour — anchored to (rides) this cell.
                // Travels START→END radii, with an independent ±WELD_ORIGIN_JITTER shift on the whole span.
                val aOut = base + (partRng.nextFloat() - 0.5f) * 2f * WELD_JITTER
                val ox = cos(aOut); val oy = sin(aOut)
                val aStart = (WELD_PARTICLE_START + (partRng.nextFloat() - 0.5f) * 2f * WELD_ORIGIN_JITTER) * radius
                addParticle(cellId, cx, cy, ox * aStart, oy * aStart, ox * spanR, oy * spanR, size)
                // Speck B: enters the neighbour from the seam side — anchored to (rides) that neighbour, so its
                // offset is relative to the neighbour's centre (nx, ny). Mirror of A: starts far (END) and moves
                // inward to START, with its own independent origin jitter.
                val nx = cx + weldDirX[i] * weldLen[i]; val ny = cy + weldDirY[i] * weldLen[i]
                val aIn = base + (partRng.nextFloat() - 0.5f) * 2f * WELD_JITTER
                val ix = cos(aIn); val iy = sin(aIn)
                val bStart = (WELD_PARTICLE_END + (partRng.nextFloat() - 0.5f) * 2f * WELD_ORIGIN_JITTER) * radius
                addParticle(weldOtherId[i], nx, ny, -ix * bStart, -iy * bStart, ix * spanR, iy * spanR, size)
            }
        }
    }

    /** Fill [weldDirX]/[weldDirY] with the unit directions (logical world space) to this cell's welded
     *  neighbours; returns the count (≤ [WELD_DIR_MAX]). Mirrors the neighbour walk the cell shader uses,
     *  but keeps world-space y (no shader flip). */
    private fun gatherWeldDirs(
        id: EntityId,
        transform: TransformComponent,
        springData: org.emerge.demo.cyto.CytoFrameSpringData?,
        springTable: ComponentTable<SpringConstraintComponent>?,
        transforms: ComponentTable<TransformComponent>,
    ): Int {
        var n = 0
        fun push(otherId: Int, nt: TransformComponent) {
            if (n >= WELD_DIR_MAX) return
            val delta = nt.pos - transform.pos
            val dx = CytoUnits.toLogical(delta.x); val dy = CytoUnits.toLogical(delta.y)
            val len = sqrt(dx * dx + dy * dy)
            if (len > 1e-4f) {
                weldDirX[n] = dx / len; weldDirY[n] = dy / len; weldLen[n] = len; weldOtherId[n] = otherId; n++
            }
        }
        if (springData != null) {
            val slot = springData.slotOfEntityId(id.value)
            if (slot >= 0) {
                val lo = springData.csrOffset[slot]; val hi = springData.csrOffset[slot + 1]
                for (k in lo until hi) {
                    val oid = springData.csrOtherId[k]
                    transforms[EntityId(oid)]?.let { push(oid, it) }
                }
            }
        } else if (springTable != null) {
            springTable[id]?.springs?.forEach { transforms[it.other]?.let { nt -> push(it.other.value, nt) } }
        }
        return n
    }

    /** A random spawn angle that avoids the welded seams: rejects any direction within an arc of a weld
     *  ([WELD_BLOCK_COS]). Returns -1 if no exposed angle is found in [PARTICLE_SPAWN_TRIES] tries (cell
     *  effectively enclosed) so the caller drops the particle. */
    private fun pickExposedAngle(weldCount: Int): Float {
        if (weldCount == 0) return partRng.nextFloat() * TAU
        repeat(PARTICLE_SPAWN_TRIES) {
            val a = partRng.nextFloat() * TAU
            val dx = cos(a); val dy = sin(a)
            var blocked = false
            for (i in 0 until weldCount) {
                if (dx * weldDirX[i] + dy * weldDirY[i] > WELD_BLOCK_COS) { blocked = true; break }
            }
            if (!blocked) return a
        }
        return -1f
    }

    /** Append one particle (colour read from [speciesTmp]); drops if the pool is full. Tracks attempts vs
     *  drops so the per-tick [spawnScale] throttle can back off before ordering-based starvation kicks in. */
    private fun addParticle(cellId: Int, cx: Float, cy: Float, offX: Float, offY: Float, dx: Float, dy: Float, size: Float) {
        spawnAttempts++
        if (partCount >= PARTICLE_MAX) { spawnDropped++; return }
        val i = partCount++
        partCell[i] = cellId; partBaseX[i] = cx; partBaseY[i] = cy
        partOffX[i] = offX; partOffY[i] = offY; partDX[i] = dx; partDY[i] = dy; partProg[i] = 0f
        partR[i] = speciesTmp[0]; partG[i] = speciesTmp[1]; partB[i] = speciesTmp[2]; partSize[i] = size
    }

    /** Age every live particle one frame, compacting out the dead, and additively draw the survivors as
     *  small species-coloured specks whose opacity fades in then out over their life (sin envelope). */
    private fun drawParticles(transforms: ComponentTable<TransformComponent>) {
        var w = 0
        var inst = 0
        for (i in 0 until partCount) {
            val prog = partProg[i] + PARTICLE_PROG_SPEED * animDt
            if (prog >= 1f) continue                          // expired → drop (not compacted forward)
            // Refresh the anchor cell's centre so the speck rides the moving cell; if the cell died, the
            // base stays frozen at its last known centre (the speck ages out in place).
            val nt = transforms[EntityId(partCell[i])]
            if (nt != null) { partBaseX[i] = CytoUnits.toLogical(nt.pos.x); partBaseY[i] = CytoUnits.toLogical(nt.pos.y) }
            // Keep: compact into slot w.
            partCell[w] = partCell[i]; partBaseX[w] = partBaseX[i]; partBaseY[w] = partBaseY[i]
            partOffX[w] = partOffX[i]; partOffY[w] = partOffY[i]; partDX[w] = partDX[i]; partDY[w] = partDY[i]
            partProg[w] = prog
            partR[w] = partR[i]; partG[w] = partG[i]; partB[w] = partB[i]; partSize[w] = partSize[i]
            val x = partBaseX[i] + partOffX[i] + prog * partDX[i]
            val y = partBaseY[i] + partOffY[i] + prog * partDY[i]
            val alpha = sin(prog.toDouble() * PI).toFloat() * PARTICLE_MAX_ALPHA
            val s = partSize[i]
            matCircS.setScale(s, s)
            forEachSeamImage(viewX(x), viewY(y), s) { ix, iy ->
                if (inst < BUILD_MAX) {
                    matCircT.setTranslation(ix, iy)
                    matCircM.setProduct(matCircT, matCircS)
                    mvpCirc.setProduct(matP, matCircM)
                    mvpCirc.copyInto(buildMatrices, inst * Mat4.FLOATS)
                    buildPrimaryIds[inst] = 0f
                    buildShapes[inst] = 0f
                    buildAlphas[inst] = alpha
                    val tb = inst * 3
                    buildTints[tb] = partR[i]; buildTints[tb + 1] = partG[i]; buildTints[tb + 2] = partB[i]
                    inst++
                }
            }
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
     * into the surrounding environment — an actual annulus with the cell-sized hole punched out, so
     * nothing shows through the membrane's transparent middle — drawn **behind** the cell discs and
     * additively (a faint dispersing haze against the dark background / light field).
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
            // flow. Model each shed as an attack-release envelope: a shed latches a goal level, the halo
            // eases *up* toward it at the build rate (BUILD_EASE — same soft fade-in as flow 3), then once
            // it has essentially reached the goal it releases, cooling by DECAY_COOL and clearing the latch.
            val shed = decayTargetFor(cell)
            var goal = decayGoal[eid] ?: 0f
            if (shed > goal) goal = shed                       // (re)start attack toward a new/bigger shed
            val prev = decayIntensity[eid] ?: 0f
            val inten: Float
            if (prev < goal * DECAY_ATTACK_REACHED) {
                inten = easeToward(prev, goal, BUILD_EASE)     // attack: soft eased rise
            } else {
                inten = easeToward(prev, 0f, 1f - DECAY_COOL)  // release: cool down toward zero
                goal = 0f                                      // consume the latch (a new shed re-arms it)
            }
            decayGoal[eid] = goal
            decayIntensity[eid] = inten
            decaySeen.add(eid)
            // Drift the halo hue toward the just-shed species (hold it between sheds, as with the build disc).
            if (cell.bioToEnv.isNotEmpty()) {
                averageSpeciesColor(cell.bioToEnv, speciesTmp)
                easeFlowColor(decayColor, eid, speciesTmp, FLOW_COLOR_EASE)
            }
            if (inten <= DECAY_MIN_VISIBLE) continue
            val transform = transforms[id] ?: continue
            val collider = colliders[id] ?: continue
            val radius = CytoUnits.toLogical(collider.radius)
            val cx = CytoUnits.toLogical(transform.pos.x)
            val cy = CytoUnits.toLogical(transform.pos.y)
            decayColor[eid]?.let { speciesTmp[0] = it[0]; speciesTmp[1] = it[1]; speciesTmp[2] = it[2] }
                ?: averageSpeciesColor(cell.bioToEnv, speciesTmp)
            val offRaw = eid * 0.61803398f
            val base = pulsePhase + (offRaw - floor(offRaw))
            for (k in 0 until DECAY_PULSES) {
                if (count >= BUILD_MAX) break
                var frac = base + k.toFloat() / DECAY_PULSES
                frac -= floor(frac)
                val a = inten * (1f - frac) * DECAY_MAX_ALPHA
                if (a <= 0.003f) continue
                val r = radius * (1f + frac * (DECAY_MAX_SCALE - 1f))   // grow from the rim outward
                // Punch the cell-sized hole out of the middle. The cell disc is a hollow membrane now, so a
                // filled pulse shows straight through its transparent centre — the halo has to be an annulus
                // that starts at the rim, not a disc the cell happens to cover. `radius / r` is that rim
                // expressed in the pulse's own local units, so the hole tracks the pulse as it expands.
                matCircS.setScale(r, r)
                // The halo, not the cell, is what may overhang the seam — replicate on its expanded radius.
                forEachSeamImage(viewX(cx), viewY(cy), r) { ix, iy ->
                    if (count < BUILD_MAX) {
                        matCircT.setTranslation(ix, iy)
                        matCircM.setProduct(matCircT, matCircS)
                        mvpCirc.setProduct(matP, matCircM)
                        mvpCirc.copyInto(buildMatrices, count * Mat4.FLOATS)
                        buildPrimaryIds[count] = radius / r
                        buildShapes[count] = CircleShader.SHAPE_ANNULUS
                        buildAlphas[count] = a.coerceIn(0f, 1f)
                        val tb = count * 3
                        buildTints[tb] = speciesTmp[0]; buildTints[tb + 1] = speciesTmp[1]; buildTints[tb + 2] = speciesTmp[2]
                        count++
                    }
                }
            }
        }
        if (decayIntensity.size > decaySeen.size) {
            decayIntensity.keys.retainAll(decaySeen); decayColor.keys.retainAll(decaySeen)
            decayGoal.keys.retainAll(decaySeen)
        }
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

    /** Ease this cell's stored flow colour toward [target] by [ease] and return it (writing the result into
     *  [target] for the caller to use). First sighting snaps to [target] so a cell's first pulse shows its
     *  true hue rather than drifting up from an arbitrary default. Call only when there IS a transfer this
     *  tick; while cooling down (no transfer) the caller holds the last colour instead of fading to grey. */
    /**
     * Refresh [animDt] from the sim clock: sim ticks since the last draw, expressed in nominal frames
     * (one nominal frame = [REALTIME_TPS] / [NOMINAL_FPS] ticks), so every per-frame constant in here
     * keeps the value it was tuned at while becoming a rate in *perceived* time.
     *
     * Paused (or a repeated snapshot between ticks) gives 0 — the visuals hold still, which is the point.
     * A fast world gives >1, but capped at [ANIM_DT_MAX]: past a few multiples the pulses alias into
     * strobing and the specks smear, and the reading "everything is faster" is already delivered. The
     * first-ever frame also gives 0 (there is no previous tick to difference against), and a rewind —
     * loading a save, or a chapter restarting the world — is clamped to 0 rather than run backwards.
     */
    private fun advanceAnimClock(tick: Long) {
        val elapsed = if (lastAnimTick < 0L) 0L else (tick - lastAnimTick).coerceAtLeast(0L)
        lastAnimTick = tick
        animDt = (elapsed / TICKS_PER_NOMINAL_FRAME).coerceAtMost(ANIM_DT_MAX)
    }

    /** Ease [cur] toward [target] at per-nominal-frame [rate], scaled to perceived time. The coerce keeps
     *  a long frame (or a fast world) from overshooting past the target into oscillation. */
    private fun easeToward(cur: Float, target: Float, rate: Float): Float =
        cur + (target - cur) * (rate * animDt).coerceAtMost(1f)

    private fun easeFlowColor(store: HashMap<Int, FloatArray>, eid: Int, target: FloatArray, ease: Float) {
        val c = store[eid]
        if (c == null) {
            store[eid] = floatArrayOf(target[0], target[1], target[2])
            return
        }
        c[0] = easeToward(c[0], target[0], ease)
        c[1] = easeToward(c[1], target[1], ease)
        c[2] = easeToward(c[2], target[2], ease)
        target[0] = c[0]; target[1] = c[1]; target[2] = c[2]
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

    /** Draw the light field as a single full-screen triangle: its fragment shader evaluates the moving
     *  daylight band analytically per pixel, so the field is continuous and covers the whole screen (torus-
     *  wrapped, no tile edge). Passes the camera→world mapping + the band position for sim-time [tick]. */
    private fun drawLightMultiply(tick: Long) {
        if (nightLevel >= 1f) return   // a flat, always-lit world: the multiply would be an identity pass
        val hwx = passHalfW
        if (hwx <= 0f) return
        GPU.enableBlend()
        GPU.setBlendFuncDstColorZero()   // scene *= light
        lightFieldShader.draw(
            centerX = passCenterX,
            halfViewX = hwx,
            bandX = CytoLightField.bandCenterX(tick),
            falloff = CytoLightField.FALLOFF,
            half = CytoLightField.HALF,
            span = CytoLightField.SPAN,
            night = nightLevel,
        )
        GPU.setBlendFuncSrcAlphaOneMinusSrcAlpha()   // leave the shared blend state as we found it
    }

    /** Draw the matter field: rasterise the quad-tree into the density texture (one torus tile) and draw one
     *  full-screen triangle sampling it with GL_REPEAT + linear filtering, so it reads as a smooth, wrapped
     *  density cloud over the whole screen (the fragment maps each pixel → world → texcoord). */
    private fun drawMatterField(frame: CytoFrame) {
        // No showMatterField guard: matter IS the ground now, not an optional overlay layered on top of it.
        val grid = frame.state.components.getTable<CytoMatterGridComponent>().asMap()[GRID_SINGLETON]?.grid ?: return
        val hwx = passHalfW
        val hwy = passHalfH
        if (hwx <= 0f || hwy <= 0f) return
        val tRaster = TimeSource.Monotonic.markNow()
        rasterizeMatter(grid)
        lastRasterizeUs = tRaster.elapsedNow().inWholeMicroseconds
        matterField.draw(
            pixels = matterPixels,
            centerX = passCenterX, centerY = passCenterY,
            halfViewX = hwx, halfViewY = hwy,
            half = CytoLightField.HALF, span = CytoLightField.SPAN,
            time = frame.tick.toFloat(), amp = MATTER_WARP_AMP,
        )
    }

    /** Rasterise the dense matter field into [matterPixels] (a [MATTER_TEX_RES]² RGBA density map of one
     *  torus tile). The field IS a texel grid, so this is a straight scan of its per-channel atom totals —
     *  no tree walk, no store deref, no per-leaf normalisation, and nothing to clear. Row 0 = y = −HALF.
     *
     *  The field's resolution tracks the world size while the texture is fixed, so a non-default world is
     *  point-sampled (they coincide 1:1 at the default 64-world).
     *
     *  The channel tally lands in buffers this renderer owns, and the sim never touches them. It used to
     *  own them itself and refill them from maintain(), which flashed: the refill blanks a channel before
     *  re-accumulating it, so a frame that scanned during one saw a partly-zeroed field — constantly, once
     *  ticks outpaced frames. Tallying here reads the field's columns live instead, which is benign (a
     *  texel is one int, so the worst case is one texel one tick stale, and a frame mixing two ticks of
     *  matter density is not observable). */
    private fun rasterizeMatter(grid: CytoMatterField) {
        val res = MATTER_TEX_RES
        val fres = grid.resolution
        if (chRedTmp.size != fres * fres) {
            chRedTmp = IntArray(fres * fres); chGreenTmp = IntArray(fres * fres); chBlueTmp = IntArray(fres * fres)
        }
        grid.tallyChannels(chRedTmp, chGreenTmp, chBlueTmp, matterSpeciesId)
        val chR = chRedTmp; val chG = chGreenTmp; val chB = chBlueTmp
        // Reciprocal-multiply, not divide: this runs 3x per texel over the whole grid, and a float multiply
        // is a fraction of the cost of a double divide.
        val scale = (255.0 / MATTER_REF_DENSITY).toFloat()
        val opaque = 255.toByte()
        if (fres == res) {
            // 1:1 (the default world) — one flat pass, no index remap.
            for (i in 0 until res * res) {
                val p = i * 4
                val r = (chR[i] * scale).toInt(); val g = (chG[i] * scale).toInt(); val b = (chB[i] * scale).toInt()
                matterPixels[p] = (if (r > 255) 255 else r).toByte()
                matterPixels[p + 1] = (if (g > 255) 255 else g).toByte()
                matterPixels[p + 2] = (if (b > 255) 255 else b).toByte()
                matterPixels[p + 3] = opaque
            }
        } else {
            for (row in 0 until res) {
                val fbase = (row * fres / res) * fres
                val pbase = row * res
                for (col in 0 until res) {
                    val i = fbase + col * fres / res
                    val p = (pbase + col) * 4
                    val r = (chR[i] * scale).toInt(); val g = (chG[i] * scale).toInt(); val b = (chB[i] * scale).toInt()
                    matterPixels[p] = (if (r > 255) 255 else r).toByte()
                    matterPixels[p + 1] = (if (g > 255) 255 else g).toByte()
                    matterPixels[p + 2] = (if (b > 255) 255 else b).toByte()
                    matterPixels[p + 3] = opaque
                }
            }
        }
        lastTexelCount = res * res
    }

    fun cleanup() {
        shader.deleteProgram()
        bgShader.deleteProgram()
        lightFieldShader.deleteProgram()
        matterField.deleteProgram()
        circleShader.deleteProgram()
        tileShader.deleteProgram()
        periodTarget.delete()
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

    /**
     * Aim the world pass at the screen: centred on the camera, covering [viewHeight] world units vertically
     * and whatever the aspect ratio makes that horizontally.
     */
    private fun computeProjection() {
        val aspect = resW / resH
        camLogX = CytoUnits.toLogical(cameraX)
        camLogY = CytoUnits.toLogical(cameraY)
        setPass(
            centerX = camLogX,
            centerY = camLogY,
            halfW = viewHeight * aspect * 0.5f,
            halfH = viewHeight * 0.5f,
            pixelsH = resH,
            period = false,
        )
    }

    /**
     * Aim the world pass at the period texture instead: centred on the world origin and covering exactly one
     * torus period, square. That the pass is square while the screen is not is fine — the texture is one
     * period of the world, not a picture of the screen, and [TileShader] maps it back.
     *
     * Note that nothing else about the pass has to change. [viewX]/[viewY] already wrap, so centring on the
     * origin turns them from "offset from the camera" into "wrapped into the period" — which is exactly the
     * period-aligned placement the texture needs. What the period pass does add is [forEachSeamImage].
     */
    private fun computePeriodProjection(targetPixels: Float) {
        val half = CytoLightField.SPAN * 0.5f
        setPass(centerX = 0f, centerY = 0f, halfW = half, halfH = half, pixelsH = targetPixels, period = true)
    }

    private fun setPass(
        centerX: Float,
        centerY: Float,
        halfW: Float,
        halfH: Float,
        pixelsH: Float,
        period: Boolean,
    ) {
        passCenterX = centerX
        passCenterY = centerY
        passHalfW = halfW
        passHalfH = halfH
        passPixelsH = pixelsH
        periodPass = period
        // The centre is applied per-object (as a wrapped delta via viewX/viewY), not by a single translation
        // matrix — a matrix can't wrap. So the projection is a pure scale-to-NDC; objects arrive pre-offset.
        matP.setScale(1f / halfW, 1f / halfH)
    }

    /** Wrap a logical delta to the shortest torus offset, `[-HALF, HALF)` (period [CytoLightField.SPAN]). */
    private fun wrapLogical(d: Float): Float {
        val span = CytoLightField.SPAN
        return d - span * round(d / span)
    }

    /** Logical world x/y → pass-relative logical, wrapped to the shortest torus offset. The per-object
     *  camera transform: every drawn thing is placed at its nearest image of the pass centre, so it slides
     *  seamlessly across the torus edges instead of off into unbounded space. */
    private fun viewX(logicalX: Float): Float = wrapLogical(logicalX - passCenterX)
    private fun viewY(logicalY: Float): Float = wrapLogical(logicalY - passCenterY)

    /**
     * Invoke [body] at every position an object of [radius] centred at ([vx], [vy]) has to be drawn.
     *
     * Normally that is once, at the position given. In the period pass it can be up to four times: the
     * period texture is only seamless if whatever overhangs one edge of the world is also drawn at the
     * opposite edge, so the tiling has something to line up with. A cell straddling a corner needs all four.
     *
     * This is the entire cost of the tiled path — a handful of extra draws for the objects actually touching
     * the seam, no matter how many copies of the world end up on screen.
     */
    private inline fun forEachSeamImage(vx: Float, vy: Float, radius: Float, body: (Float, Float) -> Unit) {
        body(vx, vy)
        if (!periodPass) return

        val span = CytoLightField.SPAN
        val half = span * 0.5f
        val overRight = vx + radius > half
        val overLeft = vx - radius < -half
        val overTop = vy + radius > half
        val overBottom = vy - radius < -half

        val dx = if (overRight) -span else if (overLeft) span else 0f
        val dy = if (overTop) -span else if (overBottom) span else 0f
        if (dx != 0f) body(vx + dx, vy)
        if (dy != 0f) body(vx, vy + dy)
        if (dx != 0f && dy != 0f) body(vx + dx, vy + dy)
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
        // Brightness of cells outside the focused cell's welded cluster — dark enough to recede, but
        // still faintly visible so the surrounding context isn't lost entirely.
        const val DIM_VALUE = 0.5f
        // Resolution of the matter density texture (one torus tile). Fixed + world-size-independent. Higher
        // res keeps the matter's real structure crisp under linear filtering (the warp only needs to soften
        // leaf edges, not carry detail). 512² RGBA ≈ 1MB/frame (off-heap upload).
        const val MATTER_TEX_RES = 512
        // Gaseous domain-warp amplitude (uv units) — how far the animated noise displaces the density lookup.
        // Small: just enough to break up the quad-tree's blocky leaf edges without smearing away definition.
        const val MATTER_WARP_AMP = 0.005f
        // Leaf counts scale with area; normalise by the finest leaf size + the seed density so a full
        // base-density leaf reads as white (1,1,1) regardless of how merged it is.
        const val MATTER_REF_DENSITY = CytoSeed.MATTER_UNIFORM_LEVEL.toDouble()*4.0

        // ── Perceived-time clock (see [advanceAnimClock]) ──
        // Nominal display rate the per-frame constants below were tuned against (vsync on Stu's machine).
        const val NOMINAL_FPS = 60f
        // Sim ticks in one nominal frame at realtime speed. CytoController.STEP is the sim's seconds-per-tick,
        // so 1/STEP is its realtime rate (64 TPS) — derived rather than repeated so the two can't drift apart.
        const val TICKS_PER_NOMINAL_FRAME = 1f / CytoController.STEP / NOMINAL_FPS
        // Ceiling on one frame's animation advance, in nominal frames. The speed control reaches thousands of
        // TPS; unclamped, a frame would advance the pulse clock dozens of steps and read as strobing rather
        // than as fast. 6 ⇒ visuals top out at ~6x realtime while the sim keeps climbing.
        const val ANIM_DT_MAX = 6f

        // ── Flow 3 (CYT→BIO "building") tuning — iterate via the agent harness. ──
        // Bounds and quantisation for the period texture (see [periodTargetSize]). The max caps memory at
        // 2048² RGBA (16 MB) and sits inside GL_MAX_TEXTURE_SIZE on every target, phones included.
        const val TILE_MIN_PX = 128
        const val TILE_MAX_PX = 2048
        const val TILE_SIZE_STEP = 128

        const val BUILD_MAX = CircleShader.MAX_INSTANCES
        // Per-frame easing toward the tick's build target (warm-up / cool-down rate). Lower ⇒ gentler
        // fade-in; 0.03 ⇒ ≈2-3s ramp so the build glow eases in softly rather than popping on.
        const val BUILD_EASE = 0.03f
        // Per-frame easing of the build/decay disc hue toward the currently-transferring species' colour
        // (shared by flows 3 & 4). Lower ⇒ slower, more gradual cross-fade when the built species changes.
        const val FLOW_COLOR_EASE = 0.05f
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
        // Attack-release model: a shed latches a goal (count/REF), the halo eases up to it at BUILD_EASE,
        // then releases and cools by DECAY_COOL each frame.
        const val DECAY_REF = 2f
        const val DECAY_COOL = 0.93f            // ~0.5s fade at 60fps
        const val DECAY_ATTACK_REACHED = 0.97f  // fraction of the goal that counts as "reached" → release
        const val DECAY_MAX_ALPHA = 0.85f
        const val DECAY_MIN_VISIBLE = 0.02f
        const val DECAY_PULSES = 2
        const val DECAY_MAX_SCALE = 1.5f

        // ── Gene particles tuning. ──
        // Hard cap on gene specks drawn in one frame. Must not exceed BUILD_MAX: the specks share the
        // build* instance buffers, so a larger cap overflows buildMatrices (ArrayIndexOutOfBounds).
        const val GENE_MAX = BUILD_MAX
        const val GENE_INACTIVE_VALUE = 0.25f         // colour value of a gene that failed its condition
        const val GENE_EASE = 0.08f                   // per-frame ease toward the active/inactive value
        const val GENE_SIZE_FRAC = 0.07f              // speck radius as a fraction of the cell radius
        const val GENE_ALPHA = 0.9f                   // speck opacity (additive; value carried in the tint)
        const val GENE_MIN_CELL_PX = 14f              // skip cells whose radius is under this on screen
        const val GENE_TIME_STEP = 0.016f             // drift clock advance per frame
        // Drift frequencies: two per axis, deliberately non-harmonic so the pair never closes into a loop.
        const val GENE_FREQ_A = 0.9f
        const val GENE_FREQ_B = 1.37f
        const val GENE_FREQ_C = 1.11f
        const val GENE_FREQ_D = 0.71f
        const val SIN_LUT_SIZE = 1024                 // power of two — index masks instead of modulo
        val SIN_LUT = FloatArray(SIN_LUT_SIZE) { sin(it * 2.0 * PI / SIN_LUT_SIZE).toFloat() }

        // ── Flows 1 & 2 (ENV↔CYT transfer particles) tuning. ──
        const val PARTICLE_MAX = 10000                 // hard cap on live particles (excess spawns dropped)
        const val SPAWN_SCALE_MIN = 0.02f             // floor on the adaptive spawn throttle (never fully mute)
        const val SPAWN_SCALE_RECOVER = 0.024f        // per-tick additive recovery (also the ease-in rate: ~40 ticks to full)
        const val PARTICLE_PER_UNIT = 0.03f           // particles spawned per unit of species transfer/tick
        const val PARTICLE_MAX_PER_SPECIES = 5        // …capped per species per cell per tick
        const val PARTICLE_SIZE_FRAC = 0.035f         // speck radius as a fraction of the cell radius
        const val PARTICLE_SATURATION = 1.0f          // colour saturation of the specks (1 = full species hue)
        const val PARTICLE_OUTER = 1.125f             // outer band radius in cell radii (just outside border)
        const val PARTICLE_INNER = 0.125f             // inner band radius in cell radii (just inside border)
        const val PARTICLE_PROG_SPEED = 0.025f        // life progress per frame (~40 frames ≈ 0.7s at 60fps)
        const val PARTICLE_MAX_ALPHA = 0.125f           // peak speck opacity (mid-life; additive)
        const val WELD_DIR_MAX = 16                   // max welded-neighbour directions considered for biasing
        const val PARTICLE_SPAWN_TRIES = 8            // rejection-sampling attempts before dropping a speck
        const val WELD_BLOCK_COS = 0.6f               // cos of the blocked half-arc around each weld (~53°)
        const val WELD_PER_UNIT = 0.01f               // cross-weld specks spawned per unit sent across welds
        const val WELD_JITTER = 0.7f                  // ±angular jitter (rad, ~40°) of cross-weld specks
        const val WELD_PARTICLE_START = 0.25f         // cross-weld speck inner travel radius (cell radii)
        const val WELD_PARTICLE_END = 1.25f           // cross-weld speck outer travel radius (cell radii)
        const val WELD_ORIGIN_JITTER = 0.25f          // ± random shift of a cross-weld speck's whole travel span
        val TAU = (2.0 * PI).toFloat()
    }
}
