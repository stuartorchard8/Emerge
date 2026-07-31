package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Sensor
import org.emerge.demo.outofspace.world.Signals
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.render.torus.ui.Anchor
import org.emerge.render.torus.ui.Ui

/**
 * The in-game UI, built with the shared immediate-mode toolkit.
 *
 * The wiring panel is the interesting one. It is the same *sentence* as Cyto's gene editor —
 * `WHEN <channel> AT <weight>` instead of `WHEN <condition> DO <action>` — and it is built on the
 * same `clauseRow` widget, three tappable tokens in a row. Reusing that shape rather than inventing
 * a node graph is the largest single saving available here, and it means the mechanic arrives
 * already legible to anyone who has met the other game.
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
                gap()
                title("SIGNALS")
                for (channel in Channel.EMITTABLE) {
                    val value = s.signals[channel]
                    keyValue(channel.label, "${value / 10}%", channel.color, if (value > 0) channel.color else 0x5A5A5AFFL)
                }
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
                title("TOOL  ·  ${controller.tool.label}")
                controlRowOfTools(controller)
                gap()
                if (controller.tool == Tool.Build) {
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
                } else {
                    row("click a machine to wire it", 0x9A9A9AFFL)
                    row("a sensor reads the tile it faces", 0x9A9A9AFFL)
                }
                row("W tool · wheel zoom · space pause", 0x9A9A9AFFL)
            }

            wiringPanel(controller)

            panel(Anchor.BottomRight) {
                button(if (controller.paused) "PLAY" else "PAUSE", 0x3A6EA5FFL) { onTogglePause() }
                button("RESET", 0xCC3333FFL) { onReset() }
            }
        }
    }

    /**
     * The wiring editor: one tappable sentence per term of `RUN = Σ(signal × weight)`.
     *
     * Tapping the channel cycles it, tapping the weight cycles the ladder, tapping the `×` removes
     * the term. Cycling rather than opening a dropdown keeps the whole editor to three tap targets
     * per row, which is what makes it work under a thumb.
     */
    private fun org.emerge.render.torus.ui.UiBuilder.wiringPanel(controller: OutofspaceController) {
        if (controller.tool != Tool.Wire) return
        val index = controller.selected
        if (index < 0) return
        val machine = controller.state[index] ?: return
        val grid = controller.state.grid

        // Bottom-right rather than centred: a centre anchor centres on the *screen*, which is the
        // wrong centre when the build palette owns the bottom-left corner — they overlapped.
        panel(Anchor.BottomRight, rowHeight = 20f) {
            title("WIRING  ·  ${machine.kind.label} (${grid.xOf(index)}, ${grid.yOf(index)})")

            if (machine is Sensor) {
                val watched = grid.neighbour(index, machine.facing)
                val reading = controller.state.signals[machine.channel]
                clauseRow(
                    lhs = "EMIT ON",
                    cmp = machine.channel.label,
                    rhs = "${reading / 10}%",
                    onLhs = { controller.cycleSensorChannel(index, 1) },
                    onCmp = { controller.cycleSensorChannel(index, 1) },
                    onRhs = { controller.cycleSensorChannel(index, 1) },
                )
                val target = if (watched >= 0) controller.state[watched] else null
                row("watching: ${target?.kind?.label ?: "(nothing)"}", 0x9A9A9AFFL)
                gap()
            }

            val action = Action.Run
            val triggers = machine.wiring.triggers(action)
            val activation = machine.wiring.activation(action, controller.state.signals)
            keyValue(action.label, "${activation / 10}%", 0x9A9A9AFFL, if (activation > 0) 0x6ED09AFFL else 0xE05A4AFFL)

            if (triggers.isEmpty()) {
                row("(never runs — no terms)", 0xE05A4AFFL)
            } else {
                for ((slot, trigger) in triggers.withIndex()) {
                    clauseRow(
                        lhs = if (slot == 0) "WHEN " + trigger.channel.label else "PLUS " + trigger.channel.label,
                        cmp = "x",
                        rhs = signed(trigger.percent),
                        onLhs = { controller.cycleTriggerChannel(index, action, slot, 1) },
                        onCmp = { controller.wire(index, action, slot, null) },
                        onRhs = { controller.cycleTriggerWeight(index, action, slot, 1) },
                    )
                }
            }
            button("+ ADD TERM", 0x2E5A6BFFL) {
                controller.wire(index, action, triggers.size, Trigger(Channel.Red, Signals.FULL))
            }
            row("tap channel / weight to cycle, x to delete", 0x7A7A7AFFL)
        }
    }

    private fun org.emerge.render.torus.ui.PanelBuilder.controlRowOfTools(controller: OutofspaceController) {
        actionRow(
            Tool.entries.map { tool ->
                Triple(
                    if (tool == controller.tool) "> ${tool.label}" else tool.label,
                    if (tool == controller.tool) 0x3A6EA5FFL else 0x232A38FFL,
                ) { controller.tool = tool }
            },
        )
    }

    private fun signed(percent: Int): String = if (percent >= 0) "+$percent%" else "$percent%"

    /** Grams are the sim's unit; kilograms are the reading unit past a certain size. */
    private fun grams(g: Long): String =
        if (g < 10_000L) "${g}g" else "${g / 1000}.${(g % 1000) / 100}kg"
}
