package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * The global construction inventory: how much of each [Form] the vessel has banked, and what it is
 * made of.
 *
 * Composition survives being banked — a stockpile of steel smelted from filthy ore is still filthy,
 * and whatever gets built from it inherits that. It would be much easier to reduce everything to a
 * count of items here, and it would throw away the point of the chemistry.
 */
class Stockpile private constructor(private val byForm: Array<Mixture>) {

    operator fun get(form: Form): Mixture = byForm[form.ordinal]

    val totalGrams: Long get() {
        var sum = 0L
        for (m in byForm) sum += m.total
        return sum
    }

    /** Everything held, in [Form] declaration order so the UI never reshuffles itself. */
    fun entries(): List<Pair<Form, Mixture>> =
        Form.ALL.mapNotNull { f -> byForm[f.ordinal].takeIf { !it.isEmpty }?.let { f to it } }

    fun deposit(resource: Resource): Stockpile {
        if (resource.isEmpty) return this
        val next = byForm.copyOf()
        next[resource.form.ordinal] = next[resource.form.ordinal] + resource.mixture
        return Stockpile(next)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Stockpile && byForm.contentEquals(other.byForm))

    override fun hashCode(): Int = byForm.contentHashCode()

    override fun toString(): String = "Stockpile(${entries().joinToString { "${it.first}=${it.second.total}g" }})"

    companion object {
        val EMPTY: Stockpile = Stockpile(Array(Form.ALL.size) { Mixture.EMPTY })
    }
}

/**
 * The whole world at one instant: a tile grid of machines, the global stockpile, and the vessel's
 * own gravity.
 *
 * [gravity] is here — in state, as a vector — even though nothing reads it yet. Per the plan, that is
 * the cheap insurance that keeps acceleration-derived gravity a later decision rather than a
 * rewrite: no code is allowed to assume "down" is a constant or implied by array order.
 *
 * [ventedGrams] and [minedGrams] exist so conservation can be checked across the *whole world*, not
 * just one operation. A vent is the only place matter legitimately leaves and a miner the only place
 * it legitimately arrives, so `mined == in-world + banked + vented` must hold on every tick. That
 * invariant catches an entire category of logistics bug at once.
 */
data class VesselState(
    val grid: Grid,
    val machines: List<Machine?>,
    val stockpile: Stockpile = Stockpile.EMPTY,
    val gravity: Frac2 = DEFAULT_GRAVITY,
    val tick: Long = 0L,
    val minedGrams: Long = 0L,
    val ventedGrams: Long = 0L,
) {
    init {
        require(machines.size == grid.size) { "machine list is ${machines.size}, grid holds ${grid.size}" }
    }

    operator fun get(index: Int): Machine? = machines.getOrNull(index)
    operator fun get(x: Int, y: Int): Machine? = if (grid.inBounds(x, y)) machines[grid.index(x, y)] else null

    /** Mass currently sitting in belts and machine buffers — everything the logistics network holds. */
    val inTransitGrams: Long
        get() {
            var sum = 0L
            for (m in machines) sum += massIn(m)
            return sum
        }

    fun withMachine(index: Int, machine: Machine?): VesselState =
        copy(machines = machines.toMutableList().also { it[index] = machine })

    companion object {
        /** One g, straight down the screen. A constant *value*, not a constant in the code. */
        val DEFAULT_GRAVITY: Frac2 = Frac2(Frac(0L, 1), Frac(1L, 1))

        fun empty(grid: Grid): VesselState = VesselState(grid, List(grid.size) { null })
    }
}

/** Total mass held by one machine, wherever it keeps it. Used for world-wide conservation checks. */
fun massIn(machine: Machine?): Long = when (machine) {
    null -> 0L
    is Belt -> machine.slots.sumOf { it?.mass ?: 0L }
    is Miner -> machine.buffer.mass
    is Processor -> (machine.input?.mass ?: 0L) + (machine.product?.mass ?: 0L) + (machine.tailings?.mass ?: 0L)
    is Smelter -> (machine.input?.mass ?: 0L) + (machine.refined?.mass ?: 0L) + (machine.slag?.mass ?: 0L)
    is Node -> 0L
    is Vent -> 0L
}

/** Everything a machine holds, species by species — the finer-grained version of [massIn]. */
fun contentsOf(machine: Machine?): Mixture = when (machine) {
    null -> Mixture.EMPTY
    is Belt -> machine.slots.fold(Mixture.EMPTY) { acc, p -> if (p == null) acc else acc + p.contents }
    is Miner -> machine.buffer.mixture
    is Processor -> (machine.input?.mixture ?: Mixture.EMPTY) +
        (machine.product?.mixture ?: Mixture.EMPTY) + (machine.tailings?.mixture ?: Mixture.EMPTY)
    is Smelter -> (machine.input?.mixture ?: Mixture.EMPTY) +
        (machine.refined?.mixture ?: Mixture.EMPTY) + (machine.slag?.mixture ?: Mixture.EMPTY)
    is Node -> Mixture.EMPTY
    is Vent -> Mixture.EMPTY
}
