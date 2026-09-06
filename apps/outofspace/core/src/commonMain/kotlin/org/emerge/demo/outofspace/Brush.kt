package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.machine.DeckMachineKind

/**
 * Something the player can put on a tile: a length of conduit, or a building.
 *
 * ### Why one type rather than two brushes
 *
 * These are the only two things a click can produce, and the difference between them is *where the
 * thing goes* — a run is laid into a conduit layer, a building stands on the deck. That is a fact
 * about the operation, not about the gesture, so it belongs at the point the edit is applied and
 * nowhere before it. Holding them apart cost a `brush`, a `deckBrush` and a `brushKind` to say which
 * of the two the first two meant, plus a second `Edit` case carried all the way through the reducer.
 *
 * This only became possible once every kind kept its matter in a layer. While a machine held its own
 * mass and heat there was a real second code path to reach, and one brush would only have hidden it.
 */
sealed interface Brush {
    val label: String

    /*
     * ⛔ **A brush carries no material, and used to.** It was put here rather than on the [Edit] on
     * the grounds that a material is a standing choice rather than an act — which is true, and the
     * standing choice is `OutofspaceController.buildMaterial`, where it always actually lived. What
     * the field bought was a `withMaterial` copy stamped on at the point of use, which then had to
     * be kept *out* of [ALL] because both the menu highlight and `cycleBrush` compare against these
     * prototypes by equality: a materialled brush dropped out of its own menu. The value was on the
     * edit in all but name, so it is on the edit in name now, and a brush is a prototype again —
     * which is all [ALL] ever wanted it to be.
     */

    /** A length of conduit, laid into [conduit]'s own layer. */
    data class Run(val conduit: Conduit) : Brush {
        override val label: String get() = conduit.label
    }

    /** A building, standing on the deck. */
    data class Building(val kind: DeckMachineKind) : Brush {
        override val label: String get() = kind.label
    }

    companion object {
        /**
         * Everything offered in the build menu, runs first.
         *
         * ✅ [Conduit.Power] is here as of increment 1b of `PLAN_power_network.md`. It was held back
         * while *"the layer exists and nothing reads it yet"* — a brush laying cable that did nothing
         * would read as a bug rather than as a feature that had not arrived. `PowerFlow` reads it
         * now, and a [org.emerge.demo.outofspace.world.machine.SolarPanel] fills it.
         */
        val ALL: List<Brush> =
            Conduit.entries.map { Run(it) } + DeckMachineKind.ALL.map { Building(it) }
    }
}
