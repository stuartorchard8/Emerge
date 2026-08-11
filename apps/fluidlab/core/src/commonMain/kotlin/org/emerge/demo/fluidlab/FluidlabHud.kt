package org.emerge.demo.fluidlab

import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.Ui

/**
 * The in-game UI, built with the shared immediate-mode toolkit in `:engine:render:torus`.
 *
 * Immediate mode means there are no widget objects and no state to keep in sync: every frame you
 * describe the panels you want from the state you have, and a click runs the lambda you passed this
 * frame. Deleting a panel is deleting its code.
 *
 * The whole widget set is browsable — run `./gradlew :engine:render:ui-gallery:run` for a live
 * window with one panel per widget kind, and read `UIGallery.kt` beside it for the call sites.
 *
 * Sizes are **dp**, scaled to pixels by [Ui.setDensity]. Desktop leaves the scale at 1 (dp == px);
 * a phone host sets it from the display density, which is why the same code is legible on both.
 */
class FluidlabHud {

    /** Set by the host so the buttons can drive it — the HUD never reaches into the sim itself. */
    var onSpawnBurst: () -> Unit = {}
    var onClear: () -> Unit = {}
    var onTogglePause: () -> Unit = {}

    fun build(ui: Ui, controller: FluidlabController, fps: Float) {
        ui.frame {
            panel(Anchor.TopLeft) {
                title("FLUIDLAB")
                keyValue("Tick", controller.tick.toString())
                keyValue("Bodies", controller.bodyCount.toString())
                keyValue("FPS", fps.toInt().toString())
                keyValue("Speed", "${controller.speed}x")
            }
            panel(Anchor.BottomLeft) {
                title("CONTROLS")
                button(if (controller.paused) "PLAY" else "PAUSE", 0x3A6EA5FFL) { onTogglePause() }
                button("SPAWN 50", 0x2E8B40FFL) { onSpawnBurst() }
                button("CLEAR", 0xCC3333FFL) { onClear() }
                gap()
                row("Drag to pan, wheel to zoom", 0x9A9A9AFFL)
                row("Click empty space to spawn", 0x9A9A9AFFL)
            }
        }
    }
}
