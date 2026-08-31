package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * The keys a [WireButton] can be bound to.
 *
 * A fixed palette rather than "any key", for the reason the old channel list was a fixed palette:
 * naming a key needs a keyboard, and this game has to work on a phone. Six is enough to fly with —
 * four directions and two spare — and they read at a glance in the wiring panel.
 *
 * They are deliberately *not* named after what they do. A key is an input; what it means is whatever
 * the player wired it to, and a vessel where LEFT vents starboard is a vessel the player built wrong,
 * not a bug.
 */
enum class InputKey(val label: String) {
    Up("UP"),
    Down("DOWN"),
    Left("LEFT"),
    Right("RIGHT"),
    Q("Q"),
    E("E"),
    Z("Z"),
    X("X"),
    ;

    /** This key's bit in the held-key mask carried on [org.emerge.demo.outofspace.OutofspaceInput]. */
    val bit: Int get() = 1 shl ordinal

    companion object {
        val ALL: List<InputKey> = entries.toList()

        fun heldIn(mask: Int, key: InputKey): Boolean = mask and key.bit != 0
    }
}

/**
 * A button: full signal on the wire beneath it while its [key] is held, nothing when it is not.
 *
 * This is the machine the whole wire layer was built to make possible. Every other transmitter
 * reports on the vessel's own state — how full a tank is, how pure a packet was — and none of them
 * is a way for a *person* to say something. A vessel with one of these on a wire is a vessel with a
 * control, and controls are what flying is.
 *
 * It is a transmitter like any other and there is nothing special about it downstream: it drives the
 * network under its tile, the same one a sensor would, and an airlock cannot tell which put the
 * value there. That is the property that makes it worth having — anything a sensor can drive, a
 * finger can now drive too.
 */
data class WireButton(
    override val center: TileIndex,
    val key: InputKey = InputKey.Up,
    override val wiring: Wiring = Wiring.RUNNING,
) : DeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.KeyInput
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)
}
