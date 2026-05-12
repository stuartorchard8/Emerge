package org.emerge.demo.drockets

import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Vec2

/**
 * Composite Drockets renderer. Owns three sub-renderers:
 *
 *  - [WorldRenderer] — starscape, planets, drockets, knights, particles + camera state
 *  - [CladogramPanelRenderer] — right-half scissor-clipped lineage tree + selection state
 *  - [OverlayHud] — top-left text overlay with status messages + per-entity phenotype debug
 *
 * Forwards external calls (input handlers, draw, lifecycle) to the appropriate sub-renderer;
 * the only orchestration that lives here is the per-frame [draw] sequence and the
 * cladogram→world focus hop on a panel click.
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
    private val cladogram = CladogramPanelRenderer()
    private val hud = OverlayHud()

    private var resolution: Vec2 = Vec2(1f, 1f)

    fun setResolution(res: Vec2) {
        resolution = res
        world.setResolution(res)
        cladogram.setResolution(res)
        hud.setResolution(res)
    }

    fun draw(frame: DrocketsFrame) {
        world.draw(frame.state)
        cladogram.draw(frame)

        val extraLines = mutableListOf<String>()
        extraLines += cladogram.hudSummaryLines(frame)
        if (hud.showPhenotypeDebug) {
            extraLines += world.focusedDrocketDebugLines()
        }
        hud.draw(extraLines)
    }

    fun cleanup() {
        world.cleanup()
        cladogram.cleanup()
        hud.cleanup()
    }

    // ── View input (camera) ────────────────────────────────────────────────────

    fun zoomByFactor(factor: Float) = world.zoomByFactor(factor)
    fun rotateLeft() = world.rotateBy(Frac(1, 1024))
    fun rotateRight() = world.rotateBy(Frac(-1, 1024))
    fun focusPlanet() = world.focusPlanet()

    // ── Cladogram input ────────────────────────────────────────────────────────

    fun toggleCladogramPanel() {
        val on = cladogram.togglePanel()
        hud.setOverlayStatus(
            if (on) "Cladogram ON (F2)  living-only F6" else "Cladogram OFF (F2)",
            durationMs = 2_000,
        )
    }

    fun toggleCladogramLivingOnly() {
        val mode = cladogram.cycleFilter()
        hud.setOverlayStatus(
            when (mode) {
                CladogramFilterMode.ALL -> "Cladogram filter: ALL (F6)"
                CladogramFilterMode.LIVING_ONLY -> "Cladogram filter: LIVING ONLY (F6)"
                CladogramFilterMode.LIVING_AND_PARENTS -> "Cladogram filter: LIVING+PARENTS (F6)"
                CladogramFilterMode.LIVING_AND_CONNECTORS -> "Cladogram filter: LIVING+CONNECTORS (F6)"
            },
            durationMs = 1_800,
        )
    }

    fun toggleCladogramProfiling() {
        val on = cladogram.toggleProfiling()
        hud.setOverlayStatus(
            if (on) "Cladogram profiling ON (F7)" else "Cladogram profiling OFF (F7)",
            durationMs = 1_500,
        )
    }

    fun panCladogram(dxLayout: Float, dyLayout: Float) = cladogram.panBy(dxLayout, dyLayout)

    fun handleCladogramWheel(pixelX: Float, framebufferW: Float, factor: Float): Boolean =
        cladogram.handleWheel(pixelX, framebufferW, factor)

    /**
     * Routes a primary (left) click: if the cladogram panel is on and the click landed
     * on the right half, hit-test in the panel and possibly focus the picked drocket;
     * otherwise hit-test in the world.
     *
     * @return true if a click was consumed by either the cladogram or world picking.
     */
    fun handlePrimaryClick(frame: DrocketsFrame, pixel: Vec2): Boolean {
        if (cladogram.panelOn && resolution.x > 0f && pixel.x >= resolution.x * 0.5f) {
            cladogram.tryPick(frame, pixel)?.let { world.focusOn(it) }
            return true
        }
        return world.tryFocusDrocketAt(frame.state, pixel)
    }

    // ── HUD input ──────────────────────────────────────────────────────────────

    fun togglePhenotypeDebugHud() = hud.togglePhenotypeDebug()
    fun setOverlayStatus(message: String, durationMs: Long = 2_500) =
        hud.setOverlayStatus(message, durationMs)
}
