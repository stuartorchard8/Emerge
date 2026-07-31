package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.Ui

/**
 * The in-game UI, built with the shared immediate-mode toolkit.
 *
 * Three panels, each answering one question the player actually has: what am I building, what does
 * the vessel own, and is the world balanced. The last one is a development readout and will go, but
 * while the logistics are young a visible `mined = transit + banked + vented` is worth more than
 * anything else on screen.
 */
class OutofspaceHud {

    var onTogglePause: () -> Unit = {}
    var onReset: () -> Unit = {}

    fun build(ui: Ui, controller: OutofspaceController, fps: Float) {
        val s = controller.state
        ui.frame {
            panel(Anchor.TopLeft) {
                title("OUT OF SPACE")
                keyValue("Tick", s.tick.toString())
                keyValue("FPS", fps.toInt().toString())
                keyValue("Speed", "${controller.speed}x")
                gap()
                title("MASS BALANCE")
                keyValue("Mined", grams(s.minedGrams))
                keyValue("In transit", grams(s.inTransitGrams))
                keyValue("Banked", grams(s.stockpile.totalGrams))
                keyValue("Vented", grams(s.ventedGrams))
                val balanced = s.minedGrams == s.inTransitGrams + s.stockpile.totalGrams + s.ventedGrams
                row(if (balanced) "balanced" else "LEAK", if (balanced) 0x6ED09AFFL else 0xE05A4AFFL)
            }

            panel(Anchor.TopRight) {
                title("STOCKPILE")
                val entries = s.stockpile.entries()
                if (entries.isEmpty()) {
                    row("(nothing banked yet)", 0x9A9A9AFFL)
                } else {
                    for ((form, mixture) in entries) {
                        keyValue(form.name, grams(mixture.total))
                        val dominant = mixture.dominant
                        if (dominant != null && mixture[dominant] < mixture.total) {
                            // Purity is the interesting number, so say it rather than hide it.
                            val pct = mixture[dominant] * 100 / mixture.total
                            row("   $pct% ${dominant.name}", 0x9A9A9AFFL)
                        }
                    }
                }
            }

            panel(Anchor.BottomLeft) {
                title("BUILD  ·  ${controller.brush.label} facing ${controller.brushFacing.name.uppercase()}")
                for (kind in MachineKind.ALL) {
                    val selected = kind == controller.brush
                    button(
                        if (selected) "> ${kind.label}" else "  ${kind.label}",
                        if (selected) kindColor(kind) or 0xFFL else 0x232A38FFL,
                    ) { controller.brush = kind }
                }
                gap()
                row("click place · right-click remove", 0x9A9A9AFFL)
                row("R rotate brush · middle-drag pan", 0x9A9A9AFFL)
                row("wheel zoom · space pause · F5 reset", 0x9A9A9AFFL)
            }

            panel(Anchor.BottomRight) {
                button(if (controller.paused) "PLAY" else "PAUSE", 0x3A6EA5FFL) { onTogglePause() }
                button("RESET", 0xCC3333FFL) { onReset() }
            }
        }
    }

    /** Grams are the sim's unit; kilograms are the reading unit past a certain size. */
    private fun grams(g: Long): String =
        if (g < 10_000L) "${g}g" else "${g / 1000}.${(g % 1000) / 100}kg"
}
