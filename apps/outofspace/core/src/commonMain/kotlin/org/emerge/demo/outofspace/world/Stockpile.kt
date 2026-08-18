package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Storage

/**
 * The global construction inventory: how much of each [Form] the vessel has to build with, and what
 * it is made of.
 *
 * **This is a view over the vessel's [org.emerge.demo.outofspace.world.machine.Storage] machines, not an account of its own.** Material is
 * available for construction because it is sitting in a warehouse somewhere aboard, and it stops
 * being available the moment that warehouse is emptied, moved or breached. The earlier design had a
 * central node that absorbed deliveries into a separate tally, which meant matter existed in one of
 * two mutually exclusive places and the conservation check had to name both; deriving it instead
 * removes the seam entirely — there is no act of "banking", only of storing.
 *
 * Composition survives storage — a tank of steel smelted from filthy ore is still filthy, and
 * whatever gets built from it inherits that. It would be much easier to reduce everything to a count
 * of items here, and it would throw away the point of the chemistry.
 */
class Stockpile private constructor(private val byForm: Array<Mixture>) {

    operator fun get(form: Form): Mixture = byForm[form.ordinal]

    val totalMass: Long get() {
        var sum = 0L
        for (m in byForm) sum += m.total
        return sum
    }

    /** Everything held, in [Form] declaration order so the UI never reshuffles itself. */
    fun entries(): List<Pair<Form, Mixture>> =
        Form.ALL.mapNotNull { f -> byForm[f.ordinal].takeIf { !it.isEmpty }?.let { f to it } }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Stockpile && byForm.contentEquals(other.byForm))

    override fun hashCode(): Int = byForm.contentHashCode()

    override fun toString(): String = "Stockpile(${entries().joinToString { "${it.first}=${it.second.total}g" }})"

    companion object {
        val EMPTY: Stockpile = Stockpile(Array(Form.ALL.size) { Mixture.EMPTY })

        /** Everything sitting in every storage aboard, gathered by form. */
        fun of(grid: Grid, deck: DeckArray, buffers: BufferLayer): Stockpile {
            var any = false
            val byForm = Array(Form.ALL.size) { Mixture.EMPTY }
            // Off the deck, which is where warehouses live now. Walked by centre: a tank is three
            // tiles across and stored once, and adding its contents per covered tile would have the
            // inventory report nine times what is aboard.
            for (i in 0 until deck.size) {
                val tile = TileIndex(i)
                val m = deck[tile]
                if (m !is Storage || m.center != tile) continue
                val store = bufferTile(grid, m, tile, BufferRole.Inside) ?: continue
                val held = buffers.resourceAt(store) ?: continue
                if (held.isEmpty) continue
                byForm[held.form.ordinal] = byForm[held.form.ordinal] + held.mixture
                any = true
            }
            return if (any) Stockpile(byForm) else EMPTY
        }
    }
}
