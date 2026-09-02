package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.*

/**
 * A single setting that may or may not be present in a [MachineSettings].
 *
 * The three variants let us distinguish:
 * - [Present]: the source machine had this setting, here is its value.
 * - [Empty]: the source machine had this setting and it was explicitly null/empty.
 * - [Absent]: this setting does not exist on the source machine type, so it should not affect the
 *   target when pasting.
 */
sealed class Setting<out T> {
    /** The source machine had this setting, here is its value. */
    data class Present<T>(val value: T) : Setting<T>()
    /** The source machine had this setting and it was explicitly empty/null. */
    data object Empty : Setting<Nothing>()
    /** This setting does not exist on the source machine type. */
    data object Absent : Setting<Nothing>()
}

/**
 * A snapshot of a machine's configurable settings, independent of its runtime state (buffers,
 * energy, progress).
 *
 * Captured by pressing **B** on a machine — see `OutofspaceController.grab`. The snapshot includes
 * only settings that affect how a machine behaves — wiring, facing, input keys, storage filters, tuning parameters — not transient
 * state like buffer contents, thermal energy, or processing progress.
 *
 * When pasted into another machine, only settings that both machines share are applied. Settings
 * absent from the source are ignored; settings absent from the target are silently skipped.
 */
data class MachineSettings(
    val kind: DeckMachineKind,
    val wiring: Setting<Wiring>,
    val facing: Setting<Direction>,
    val key: Setting<InputKey>,
    val filter: Setting<SpeciesFilter?>,
    val setTemperature: Setting<Int>,
    val dwellTicks: Setting<Int>,
    val ticksPerAction: Setting<Int>,
    val efficiencyPermille: Setting<Int>,
    val massPerTick: Setting<Long>,
    val control: Setting<ThrusterControl>,
) {
    override fun toString(): String = buildString {
        append(kind.label).append(" [")
        append("wiring=").append(if (wiring is Setting.Present) "set" else wiring)
        append(',').append("facing=").append(if (facing is Setting.Present) facing.value else facing)
        append(',').append("key=").append(if (key is Setting.Present) key.value else key)
        append(',').append("filter=").append(if (filter is Setting.Present) if (filter.value == null) "null" else "locked" else filter)
        append(',').append("temp=").append(if (setTemperature is Setting.Present) setTemperature.value else setTemperature)
        append(',').append("dwell=").append(if (dwellTicks is Setting.Present) dwellTicks.value else dwellTicks)
        append(',').append("tpa=").append(if (ticksPerAction is Setting.Present) ticksPerAction.value else ticksPerAction)
        append(',').append("eff=").append(if (efficiencyPermille is Setting.Present) efficiencyPermille.value else efficiencyPermille)
        append(',').append("mpt=").append(if (massPerTick is Setting.Present) massPerTick.value else massPerTick)
        append(',').append("control=").append(if (control is Setting.Present) control.value else control)
        append(']')
    }
}

/**
 * The same settings, pointed [facing] — or unchanged, for a machine that has no facing to point.
 *
 * ⛔ **[Setting.Absent] stays absent.** A hull does not face anywhere, and turning "this kind has no
 * such setting" into "this kind faces right" would hand a facing to every machine that has none the
 * moment the build cursor was turned. The three-way [Setting] exists precisely so that overriding a
 * value and *inventing* one are different operations, and this is the one that overrides.
 *
 * What it is for: the build cursor carries a facing of its own, and a stamped click hands that facing
 * over rather than the one that was captured — which is what makes rotating the brush and clicking a
 * machine already on the deck a way of turning it. See `OutofspaceController.place`.
 */
fun MachineSettings.aimed(facing: Direction): MachineSettings =
    if (this.facing is Setting.Absent) this else copy(facing = Setting.Present(facing))

/**
 * Build a [MachineSettings] snapshot from a [DeckMachine].
 *
 * Only settings that the machine actually has are included as [Setting.Present]. All others are
 * [Setting.Absent].
 */
fun DeckMachine.toMachineSettings(): MachineSettings = MachineSettings(
    kind = kind,
    wiring = Setting.Present(wiring),
    facing = when (this) {
        is DirectedDeckMachine -> Setting.Present(facing)
        else -> Setting.Absent
    },
    key = when (this) {
        is WireButton -> Setting.Present(key)
        else -> Setting.Absent
    },
    filter = when (this) {
        is Storage -> Setting.Present(filter)
        else -> Setting.Absent
    },
    setTemperature = when (this) {
        is Furnace -> Setting.Present(setTemperature)
        else -> Setting.Absent
    },
    dwellTicks = when (this) {
        is Furnace -> Setting.Present(dwellTicks)
        else -> Setting.Absent
    },
    ticksPerAction = when (this) {
        is Concentrator -> Setting.Present(ticksPerAction)
        else -> Setting.Absent
    },
    efficiencyPermille = when (this) {
        is Concentrator -> Setting.Present(efficiencyPermille)
        else -> Setting.Absent
    },
    massPerTick = when (this) {
        is Thruster -> Setting.Present(massPerTick)
        else -> Setting.Absent
    },
    control = when (this) {
        is Thruster -> Setting.Present(control)
        else -> Setting.Absent
    },
)

/**
 * Apply settings from [MachineSettings] to a [DeckMachine], returning a new machine with the
 * settings applied where applicable.
 *
 * Only settings that both the source (captured in [MachineSettings]) and target machine support
 * are applied. Settings marked as [Setting.Absent] are skipped; [Setting.Empty] sets null/defaults;
 * [Setting.Present] applies the value.
 *
 * ⚠️ This creates a new machine instance — machines are immutable. The returned machine has the
 * same [DeckMachine.kind] and [center] as the original, with settings applied on top.
 */
fun DeckMachine.withSettings(settings: MachineSettings): DeckMachine {
    val base = this
    return when (kind) {
        DeckMachineKind.Storage -> {
            base as Storage
            var result = base
            if (settings.wiring is Setting.Present) result = result.copy(wiring = settings.wiring.value)
            if (settings.facing is Setting.Present) result = result.copy(facing = settings.facing.value)
            if (settings.filter is Setting.Present) result = result.copy(filter = settings.filter.value)
            if (settings.filter is Setting.Empty) result = result.copy(filter = null)
            result
        }
        DeckMachineKind.Concentrator -> {
            base as Concentrator
            var result = base
            if (settings.wiring is Setting.Present) result = result.copy(wiring = settings.wiring.value)
            if (settings.facing is Setting.Present) result = result.copy(facing = settings.facing.value)
            if (settings.ticksPerAction is Setting.Present) result = result.copy(ticksPerAction = settings.ticksPerAction.value)
            if (settings.efficiencyPermille is Setting.Present) result = result.copy(efficiencyPermille = settings.efficiencyPermille.value)
            result
        }
        DeckMachineKind.Furnace -> {
            base as Furnace
            var result = base
            if (settings.wiring is Setting.Present) result = result.copy(wiring = settings.wiring.value)
            if (settings.facing is Setting.Present) result = result.copy(facing = settings.facing.value)
            if (settings.setTemperature is Setting.Present) result = result.copy(setTemperature = settings.setTemperature.value)
            if (settings.dwellTicks is Setting.Present) result = result.copy(dwellTicks = settings.dwellTicks.value)
            result
        }
        DeckMachineKind.Thruster -> {
            base as Thruster
            var result = base
            if (settings.wiring is Setting.Present) result = result.copy(wiring = settings.wiring.value)
            if (settings.facing is Setting.Present) result = result.copy(facing = settings.facing.value)
            if (settings.massPerTick is Setting.Present) result = result.copy(massPerTick = settings.massPerTick.value)
            if (settings.control is Setting.Present) result = result.copy(control = settings.control.value)
            result
        }
        DeckMachineKind.KeyInput -> {
            base as WireButton
            var result = base
            if (settings.wiring is Setting.Present) result = result.copy(wiring = settings.wiring.value)
            if (settings.key is Setting.Present) result = result.copy(key = settings.key.value)
            result
        }
        DeckMachineKind.Sensor -> {
            base as Sensor
            var result = base
            if (settings.wiring is Setting.Present) result = result.copy(wiring = settings.wiring.value)
            if (settings.facing is Setting.Present) result = result.copy(facing = settings.facing.value)
            result
        }
        DeckMachineKind.Pump -> {
            base as Pump
            var result = base
            if (settings.wiring is Setting.Present) result = result.copy(wiring = settings.wiring.value)
            if (settings.facing is Setting.Present) result = result.copy(facing = settings.facing.value)
            result
        }
        DeckMachineKind.Bridge -> {
            base as Bridge
            var result = base
            if (settings.wiring is Setting.Present) result = result.copy(wiring = settings.wiring.value)
            if (settings.facing is Setting.Present) result = result.copy(facing = settings.facing.value)
            result
        }
        DeckMachineKind.Extractor -> {
            base as Extractor
            var result = base
            if (settings.wiring is Setting.Present) result = result.copy(wiring = settings.wiring.value)
            if (settings.facing is Setting.Present) result = result.copy(facing = settings.facing.value)
            result
        }
        // Wiring and facing only. The two trade lists are not settings in the sense a copy means —
        // carrying a sell list onto another port would hand it the player's standing orders, which
        // is a different act from copying how a machine is set up.
        DeckMachineKind.DockingPort -> {
            base as DockingPort
            var result = base
            if (settings.wiring is Setting.Present) result = result.copy(wiring = settings.wiring.value)
            if (settings.facing is Setting.Present) result = result.copy(facing = settings.facing.value)
            result
        }
        DeckMachineKind.Valve -> {
            base as Valve
            var result = base
            if (settings.wiring is Setting.Present) result = result.copy(wiring = settings.wiring.value)
            result
        }
        DeckMachineKind.Gauge -> {
            base as Gauge
            var result = base
            if (settings.wiring is Setting.Present) result = result.copy(wiring = settings.wiring.value)
            result
        }
        DeckMachineKind.Hull -> {
            // Hull has wiring but no way to configure it — it is the wall, not a control surface.
            base
        }
        DeckMachineKind.Vent -> {
            // A vent only has wiring, which is the always-on throttle that controls what it discards.
            var result = base as Vent
            if (settings.wiring is Setting.Present) result = result.copy(wiring = settings.wiring.value)
            result
        }
        DeckMachineKind.Airlock -> {
            var result = base as Airlock
            if (settings.wiring is Setting.Present) result = result.copy(wiring = settings.wiring.value)
            result
        }
    }
}
