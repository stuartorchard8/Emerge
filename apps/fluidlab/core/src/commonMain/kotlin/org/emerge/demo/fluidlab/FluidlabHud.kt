package org.emerge.demo.fluidlab

import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.Ui

/**
 * The in-game UI, built with the shared immediate-mode toolkit in `:engine:render:torus`.
 *
 * Immediate mode means there are no widget objects and no state to keep in sync: every frame you
 * describe the panels you want from the state you have, and a click runs the lambda you passed this
 * frame.
 *
 * What it shows is chosen for a *lab*: the ledger totals and the solver's own error terms, not a
 * score. `Sub-steps` and `Undelivered` are the two that say whether to trust what is on screen —
 * see [FluidStepReport].
 */
class FluidlabHud {

    /** Set by the host so the buttons can drive it — the HUD never reaches into the sim itself. */
    var onTogglePause: () -> Unit = {}
    var onStep: () -> Unit = {}
    var onReset: () -> Unit = {}
    var onCycleOverlay: () -> Unit = {}

    fun build(ui: Ui, controller: FluidlabController, overlay: Overlay, fps: Float) {
        val state = controller.state
        val report = state.report
        ui.frame {
            panel(Anchor.TopLeft) {
                title("FLUIDLAB")
                keyValue("Tick", state.tick.toString())
                keyValue("FPS", fps.toInt().toString())
                keyValue("Overlay", overlay.name)
                gap()
                keyValue("Mass g", state.totalGrams().toString())
                keyValue("Vented g", state.totalVentedGrams.toString())
                keyValue("Hull impulse", "${report.vesselX}, ${report.vesselY}")
            }
            panel(Anchor.TopRight) {
                title("SOLVER")
                // Both of these are error terms the solver reports rather than hides. Sub-steps
                // climbing means the gas is outrunning an explicit scheme; undelivered impulse is
                // thrust the pressure solve had nowhere to put. Either one growing means the picture
                // on screen is discretisation, not physics.
                keyValue("Sub-steps", report.subSteps.toString())
                keyValue("Undelivered", "${report.undeliveredX}, ${report.undeliveredY}")
                keyValue("Cohesion unpaid", report.cohesionUnpaid.toString())
            }
            panel(Anchor.BottomLeft) {
                title("CONTROLS")
                button(if (controller.paused) "PLAY" else "PAUSE", 0x3A6EA5FFL) { onTogglePause() }
                button("STEP", 0x6E6E8AFFL) { onStep() }
                button("OVERLAY", 0x2E8B40FFL) { onCycleOverlay() }
                button("RESET", 0xCC3333FFL) { onReset() }
                gap()
                row("Drag to pan, wheel to zoom", 0x9A9A9AFFL)
                row("Click a tile to breach it", 0x9A9A9AFFL)
            }
        }
    }
}
