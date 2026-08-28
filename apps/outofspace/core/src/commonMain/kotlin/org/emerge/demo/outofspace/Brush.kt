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

    /**
     * What the thing this brush lays is to be **built out of**, or null for its kind's default.
     *
     * ⛔ **On the brush and not on the [Edit], because it is a standing choice rather than an act.**
     * A player picks a material once and then draws for a while, exactly as they pick a conduit once
     * and then draw; putting it on the edit would make it something re-decided per click, which is
     * neither how the tool behaves nor how the menu would present it.
     *
     * ⚠️ **Nothing sets it yet.** The plumbing beneath is complete — a site stores its choice, its
     * bill follows it, and the door admits only what it asked for — so what is missing is the menu
     * and nothing else. Until there is one this is null everywhere and every brush lays its kind's
     * default, which is why the whole suite is unchanged by the feature existing.
     */
    val material: Species?

    /** A length of conduit, laid into [conduit]'s own layer. */
    data class Run(val conduit: Conduit, override val material: Species? = null) : Brush {
        override val label: String get() = conduit.label
    }

    /** A building, standing on the deck. */
    data class Building(val kind: DeckMachineKind, override val material: Species? = null) : Brush {
        override val label: String get() = kind.label
    }

    /** This brush, laying [material] instead of its kind's default. */
    fun withMaterial(material: Species?): Brush = when (this) {
        is Run -> if (material == this.material) this else copy(material = material)
        is Building -> if (material == this.material) this else copy(material = material)
    }

    companion object {
        /**
         * Everything offered in the build menu, runs first.
         *
         * ⚠️ [Conduit.Power] is deliberately absent: the layer exists and nothing reads it yet, so a
         * brush for it would lay cable that does nothing and looks like a bug rather than like a
         * feature that has not arrived.
         */
        val ALL: List<Brush> =
            Conduit.entries.filter { it != Conduit.Power }.map { Run(it) } +
                DeckMachineKind.ALL.map { Building(it) }
    }
}
