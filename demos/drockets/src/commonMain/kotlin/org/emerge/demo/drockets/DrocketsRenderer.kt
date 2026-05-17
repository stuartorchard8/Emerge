package org.emerge.demo.drockets

import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Vec2

/**
 * Composite Drockets renderer. Owns three sub-renderers:
 *
 *  - [WorldRenderer] — starscape, planets, drockets, knights, particles + camera state.
 *  - [LineageOverlay] — full-screen translucent cladogram with drag-pan, wheel-zoom,
 *    click-to-select, shift+click pair-wise MRCA, and double-click world-focus.
 *  - [OverlayHud] — top-left text overlay with status messages + per-entity phenotype debug.
 *
 * Forwards external calls (input handlers, draw, lifecycle) to the appropriate sub-renderer;
 * the only orchestration that lives here is the per-frame [draw] sequence and the
 * cladogram→world focus hop on a double-click.
 *
 * `contentScale` is accepted for API stability with earlier versions; the sub-renderers
 * derive their own scaling from the framebuffer resolution passed via [setResolution].
 */
class DrocketsRenderer(
    @Suppress("UNUSED_PARAMETER") contentScale: Vec2,
    drocketSpriteAtlasTextureId: Int,
    knightSpriteAtlasTextureId: Int,
) {
    private val world = WorldRenderer(drocketSpriteAtlasTextureId, knightSpriteAtlasTextureId)
    private val lineageOverlay = LineageOverlay()
    private val hud = OverlayHud()

    private var resolution: Vec2 = Vec2(1f, 1f)

    fun setResolution(res: Vec2) {
        resolution = res
        world.setResolution(res)
        lineageOverlay.setResolution(res)
        hud.setResolution(res)
    }

    fun draw(frame: DrocketsFrame) {
        world.draw(frame.state)
        lineageOverlay.draw(frame)

        val extraLines = mutableListOf<String>()
        extraLines += lineageOverlay.hudLines(frame)
        if (hud.showPhenotypeDebug) {
            extraLines += world.focusedDrocketDebugLines()
        }
        hud.draw(extraLines)
    }

    fun cleanup() {
        world.cleanup()
        lineageOverlay.cleanup()
        hud.cleanup()
    }

    // ── View input (camera) ────────────────────────────────────────────────────

    fun zoomByFactor(factor: Float) = world.zoomByFactor(factor)
    fun rotateLeft() = world.rotateBy(Frac(1, 1024))
    fun rotateRight() = world.rotateBy(Frac(-1, 1024))
    fun focusPlanet() = world.focusPlanet()

    // ── Lineage overlay (F2 toggle, F6 cycle filter, F7 cycle layout) ─────────

    val isLineageOverlayActive: Boolean get() = lineageOverlay.active
    val lineageOverlayFilter: CladogramFilterMode get() = lineageOverlay.filter
    val lineageOverlayLayoutMode: CladogramLayoutMode get() = lineageOverlay.layoutMode
    val lineageOverlayForceScale: Float get() = lineageOverlay.forceScale

    /**
     * Silent absolute setters used by the prefs restoration at startup. Unlike the
     * `toggle…` / `cycle…` methods these do not surface a HUD status message —
     * they're applying pre-existing state, not reacting to user input.
     */
    fun applyLineageOverlayPrefs(
        active: Boolean,
        filter: CladogramFilterMode,
        layoutMode: CladogramLayoutMode,
        forceScale: Float,
    ) {
        lineageOverlay.setFilter(filter)
        lineageOverlay.setLayoutMode(layoutMode)
        lineageOverlay.setForceScale(forceScale)
        lineageOverlay.setActive(active)
    }

    fun toggleLineageOverlay() {
        val on = lineageOverlay.toggleActive()
        hud.setOverlayStatus(
            if (on) "Lineage overlay ON (F2)  drag/arrows pan, wheel zoom, F6 filter, F7 layout, Ctrl+Up/Down force scale, dbl-click focus"
            else "Lineage overlay OFF (F2)",
            durationMs = 2_500,
        )
    }

    /**
     * Multiplies the force-directed solver's force-scale by [factor] (eg 2f to
     * speed up relaxation, 0.5f to slow it down) and surfaces the new value as a
     * transient HUD status. Solver clamps to its supported range, so repeated
     * presses past the cap are no-ops.
     */
    fun nudgeLineageOverlayForceScale(factor: Float) {
        lineageOverlay.nudgeForceScale(factor)
        hud.setOverlayStatus(
            "Lineage force scale: x${lineageOverlay.forceScale} (Ctrl+Up/Down)",
            durationMs = 1_500,
        )
    }

    fun cycleLineageOverlayFilter() {
        val mode = lineageOverlay.cycleFilter()
        hud.setOverlayStatus(
            when (mode) {
                CladogramFilterMode.ALL -> "Lineage filter: ALL (F6)"
                CladogramFilterMode.LIVING_ONLY -> "Lineage filter: LIVING ONLY (F6)"
                CladogramFilterMode.LIVING_AND_CONNECTORS -> "Lineage filter: MRCA-WALK (F6)"
                CladogramFilterMode.LIVING_STEINER -> "Lineage filter: LIVING STEINER (F6)"
                CladogramFilterMode.LIVING_ANCESTRY -> "Lineage filter: LIVING ANCESTRY (F6)"
            },
            durationMs = 1_800,
        )
    }

    fun cycleLineageOverlayLayoutMode() {
        val mode = lineageOverlay.cycleLayoutMode()
        hud.setOverlayStatus(
            when (mode) {
                CladogramLayoutMode.HIERARCHICAL -> "Lineage layout: HIERARCHICAL (F7)"
                CladogramLayoutMode.FORCE_DIRECTED -> "Lineage layout: FORCE-DIRECTED (F7)"
            },
            durationMs = 1_800,
        )
    }

    fun panLineageOverlayByPixels(dxPx: Float, dyPx: Float) = lineageOverlay.panByPixels(dxPx, dyPx)

    fun zoomLineageOverlayAtCursor(cursorPx: Vec2, factor: Float) =
        lineageOverlay.zoomAtCursor(cursorPx, factor)

    fun hoverLineageOverlay(pixel: Vec2?, frame: DrocketsFrame) = lineageOverlay.updateHover(pixel, frame)

    /** Double-click on a node: if it's a living drocket, focus the world camera on it. */
    fun handleLineageOverlayDoubleClick(frame: DrocketsFrame, pixel: Vec2): Boolean {
        if (!lineageOverlay.active) return false
        lineageOverlay.focusLivingDrocketAt(pixel, frame)?.let { world.focusOn(it) }
        return true
    }

    /**
     * Routes a primary (left) click. If the lineage overlay is active, the click goes to
     * its hit-test (shift modifies it for pair-wise MRCA selection). Otherwise the world
     * handles it for drocket focusing.
     *
     * @return true if the overlay consumed the click; false if the world handled it.
     */
    fun handlePrimaryClick(frame: DrocketsFrame, pixel: Vec2, shift: Boolean = false): Boolean {
        if (lineageOverlay.active) {
            lineageOverlay.handleSelectClick(pixel, frame, shift)
            return true
        }
        return world.tryFocusDrocketAt(frame.state, pixel)
    }

    // ── HUD input ──────────────────────────────────────────────────────────────

    fun togglePhenotypeDebugHud() = hud.togglePhenotypeDebug()
    fun setOverlayStatus(message: String, durationMs: Long = 2_500) =
        hud.setOverlayStatus(message, durationMs)
}
