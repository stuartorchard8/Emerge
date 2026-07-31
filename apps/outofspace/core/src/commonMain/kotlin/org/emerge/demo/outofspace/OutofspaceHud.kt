package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Analyzer
import org.emerge.demo.outofspace.world.HeatField
import org.emerge.demo.outofspace.world.Structure
import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Sensor
import org.emerge.demo.outofspace.world.Signals
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.contentsBreakdown
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

    /**
     * @param hovered the tile under the pointer, or -1. Desktop and web have a pointer; on touch
     *   there is no hover, so the inspector falls back to the machine the player last tapped.
     */
    fun build(ui: Ui, controller: OutofspaceController, fps: Float, hovered: Int = -1) {
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
                keyValue("Aboard", grams(s.inTransitGrams))
                keyValue("- in storage", grams(s.stockpile.totalGrams))
                keyValue("- spilled", grams(s.debrisGrams))
                keyValue("Vented", grams(s.ventedGrams))
                // Storage is part of "aboard", not a term beside it: the stockpile is a view over the
                // storages rather than a separate account, so adding it here would double-count.
                val balanced = s.minedGrams == s.inTransitGrams + s.ventedGrams
                row(if (balanced) "balanced" else "LEAK", if (balanced) 0x6ED09AFFL else 0xE05A4AFFL)
                gap()
                title("ATMOSPHERE")
                keyValue("Aboard", grams(s.atmosphereGrams))
                keyValue("Lost", grams(s.airVentedGrams))
                val airBalanced = s.atmosphereGrams + s.airVentedGrams == s.baselineAirGrams
                row(if (airBalanced) "balanced" else "LEAK", if (airBalanced) 0x6ED09AFFL else 0xE05A4AFFL)
                gap()
                title("ENERGY")
                keyValue("Generated", joules(s.generatedJoules))
                keyValue("Radiated", joules(s.radiatedJoules))
                keyValue("Stored", joules(s.storedJoules))
                val heatBalanced = s.storedJoules + s.radiatedJoules - s.generatedJoules == s.baselineJoules
                row(if (heatBalanced) "balanced" else "LEAK", if (heatBalanced) 0x6ED09AFFL else 0xE05A4AFFL)
                gap()
                title("SIGNALS")
                for (channel in Channel.EMITTABLE) {
                    val value = s.signals[channel]
                    keyValue(channel.label, "${value / 10}%", channel.color, if (value > 0) channel.color else 0x5A5A5AFFL)
                }
            }

            panel(Anchor.TopRight) {
                title("STOCKPILE")
                row("(sum of all storage aboard)", 0x7A8A9AFFL)
                val entries = s.stockpile.entries()
                if (entries.isEmpty()) {
                    row("(no storage holding anything)", 0x9A9A9AFFL)
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
                title("TOOL  ·  ${controller.tool.label}   VIEW  ·  ${controller.overlay.label}")
                controlRowOfTools(controller)
                actionRow(
                    Overlay.entries.map { view ->
                        Triple(
                            if (view == controller.overlay) "> ${view.label}" else view.label,
                            if (view == controller.overlay) 0x8A5A2AFFL else 0x232A38FFL,
                        ) { controller.overlay = view }
                    },
                )
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

            inspectPanel(controller, if (hovered >= 0) hovered else controller.selected)
            wiringPanel(controller)

            panel(Anchor.BottomRight) {
                button(if (controller.paused) "PLAY" else "PAUSE", 0x3A6EA5FFL) { onTogglePause() }
                button("RESET", 0xCC3333FFL) { onReset() }
            }
        }
    }

    /**
     * What is actually inside the thing under the pointer.
     *
     * Ore is a *mixture*, and until this existed nothing in the game said so — you could watch a
     * refinery run for an hour and never learn that its ore was 41% iron, let alone that the
     * concentrate leaving the front was 75%. Every buffer is listed separately, because "this
     * processor holds 6kg" is far less useful than knowing which of its three buffers is the stuck
     * one.
     */
    private fun org.emerge.render.torus.ui.UiBuilder.inspectPanel(controller: OutofspaceController, index: Int) {
        if (index < 0) return
        val s = controller.state
        val machine = s.machineCovering(index)
        val spill = s.debris[index]
        // A bare tile with a heap on it is still worth inspecting -- otherwise the material you just
        // dumped on the deck would be visible but unreadable, which is the gap the analyzer existed
        // to close in the first place.
        if (machine == null && spill.isEmpty()) return
        val grid = s.grid

        panel(Anchor.TopRight) {
            val what = machine?.kind?.label ?: "DECK"
            title("INSPECT  ·  $what (${grid.xOf(index)}, ${grid.yOf(index)})")
            tileConditions(controller, index)

            if (spill.isNotEmpty()) {
                title("SPILLED")
                for (resource in spill) {
                    keyValue(resource.form.name, grams(resource.mass))
                    row("   " + composition(resource.mixture), 0x9AA4B4FFL)
                }
                gap()
            }
            if (machine == null) return@panel

            if (machine is Analyzer) {
                // The whole point of the machine, so it leads.
                if (machine.lastDominant == null) {
                    row("nothing has passed through yet", 0x9A9A9AFFL)
                } else {
                    keyValue("LAST SEEN", machine.lastForm?.name ?: "?")
                    keyValue(
                        machine.lastDominant!!.name.uppercase(),
                        "${machine.lastPurity / 10}%",
                        0x9A9A9AFFL,
                        speciesColor(machine.lastDominant),
                    )
                    keyValue("of", grams(machine.lastMass))
                }
                keyValue("reporting on", machine.channel.label, 0x9A9A9AFFL, machine.channel.color)
                gap()
            }

            val buffers = contentsBreakdown(machine)
            if (buffers.isEmpty()) {
                row("(empty)", 0x9A9A9AFFL)
            } else {
                for ((label, resource) in buffers) {
                    keyValue(label, "${grams(resource.mass)}  ${resource.form.name}")
                    row("   " + composition(resource.mixture), 0x9AA4B4FFL)
                }
            }
        }
    }

    /**
     * Where the tile sits and how hot it is — the two facts that will matter most once air arrives,
     * and the ones with nowhere else to live.
     */
    private fun org.emerge.render.torus.ui.PanelBuilder.tileConditions(
        controller: OutofspaceController,
        index: Int,
    ) {
        val s = controller.state
        val structure = s.structure[index]
        keyValue(
            "PLACE",
            when (structure) {
                Structure.Vacuum -> "OUTSIDE"
                Structure.Hull -> "HULL"
                Structure.Interior -> "INSIDE"
            },
            0x9A9A9AFFL,
            if (structure == Structure.Vacuum) 0x7A8AA0FFL else 0x9ED0B0FFL,
        )
        if (structure != Structure.Vacuum) {
            val k = s.kelvinAt(index)
            keyValue(
                "TEMP",
                "${k}K  (${k - 273}C)",
                0x9A9A9AFFL,
                if (k > HeatField.AMBIENT_KELVIN + 60) 0xE0864AFFL else 0x9AC0E0FFL,
            )
        }
        if (structure == Structure.Interior) {
            val percent = s.pressurePercentAt(index)
            keyValue(
                "PRESSURE",
                "$percent% atm",
                0x9A9A9AFFL,
                when {
                    percent < 40 -> 0xE05A4AFFL
                    percent < 85 -> 0xE0A93AFFL
                    else -> 0x9ED0B0FFL
                },
            )
            val mix = s.air.mixtureAt(index)
            if (!mix.isEmpty) row("   " + composition(mix), 0x9AA4B4FFL)
        }
    }

    /**
     * A mixture as percentages, richest first — `IRON 41%  SILI 30%  COPP 18%  TITA 11%`.
     *
     * Percentages rather than masses because the question being asked is almost always "how clean is
     * this", and four-letter species keep the line narrow enough not to stretch the panel.
     */
    private fun composition(mixture: Mixture): String {
        if (mixture.isEmpty) return "empty"
        val total = mixture.total
        return Species.ALL
            .filter { mixture[it] > 0L }
            .sortedByDescending { mixture[it] }
            .joinToString("  ") { "${it.name.take(4).uppercase()} ${mixture[it] * 100 / total}%" }
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

            if (machine is Analyzer) {
                clauseRow(
                    lhs = "REPORT ON",
                    cmp = machine.channel.label,
                    rhs = "${machine.lastPurity / 10}%",
                    onLhs = { controller.cycleSensorChannel(index, 1) },
                    onCmp = { controller.cycleSensorChannel(index, 1) },
                    onRhs = { controller.cycleSensorChannel(index, 1) },
                )
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

    /** Joules get large fast; kJ and MJ keep the panel narrow. */
    private fun joules(j: Long): String = when {
        j < 10_000L -> "${j}J"
        j < 10_000_000L -> "${j / 1000}kJ"
        else -> "${j / 1_000_000}MJ"
    }

    /** Grams are the sim's unit; kilograms are the reading unit past a certain size. */
    private fun grams(g: Long): String =
        if (g < 10_000L) "${g}g" else "${g / 1000}.${(g % 1000) / 100}kg"
}
