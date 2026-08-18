package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.capacityPerTile
import org.emerge.demo.outofspace.world.diameter
import org.emerge.demo.outofspace.world.size

/**
 * A machine on a tile. Immutable — the reducer builds new ones rather than mutating, so a snapshot
 * of the world is a snapshot of the world.
 *
 * Every machine that produces something has a **facing**: its product leaves that side. The two with
 * a waste stream ([Processor], [Smelter]) put waste out the side *clockwise* of facing, which
 * mirrors the separate out/slag ports on the Godot originals and makes a refinery line read as a
 * spine with waste coming off it.
 *
 * Every machine also carries [wiring]: the `Σ(signal × weight)` rules that decide whether — and how
 * fast — it runs. New machines default to "wired to ALWAYS at full", so placing one just works and
 * wiring is something you add rather than something you must do.
 *
 * Rates are mass per second, turned into whole mass per tick by
 * [org.emerge.demo.outofspace.logistics.Rate] with the fraction kept in each machine's own `carry`.
 * Carry is machine state and not a global precisely so it survives a save.
 */
sealed interface Machine {
    val kind: MachineKind
    val wiring: Wiring

    /**
     * How much thermal energy this machine is holding, in the millijoules [org.emerge.demo.outofspace.world.Material] documents —
     * **one figure per tile of it**, not one for the whole machine.
     *
     * On the machine rather than in a field beside it, and that is load-bearing — see [org.emerge.demo.outofspace.world.Body]. A
     * parallel array keyed by tile would be desynchronised by `copy(machines = …)`, which is the
     * operation every save load, every fixture and every player edit goes through, and the symptom
     * is a freshly laid rail inheriting the energy of the furnace that used to stand there. Here,
     * a machine's capacity and a machine's energy are properties of the same value and cannot come
     * apart. Storing per tile does not weaken that: the array belongs to the machine, so it travels
     * with it and is replaced with it.
     *
     * Defaults to room temperature for the machine's own footprint and material, so placing one
     * needs no separate act of initialisation.
     */
    val energy: TileEnergy

    fun withWiring(wiring: Wiring): Machine

    fun withEnergy(energy: TileEnergy): Machine
}

/**
 * A machine's thermal energy, one entry per tile of it.
 *
 * ### Why per tile
 *
 * Step 6b of `PLAN_unit_rescale.md`. Held as a lump, the largest number a machine can store is
 * `capacityPerTile × thermalTiles × maxKelvin`, and that `thermalTiles` — 25, for the machines that
 * dominate — was the last thing standing between the game and a microgram mass unit. Per tile, the
 * factor leaves the expression and the bound becomes one a single tile can carry, which the budget
 * already had room for.
 *
 * But the reason it is *right* is not the arithmetic. A machine is a real part of the world sitting
 * on a set of adjacent tiles, and one temperature for a five-by-five smelter was a claim that heat
 * crosses two and a half metres of steel instantly. It does not. With a figure per tile, the
 * existing conduction pass — which already joins any two impermeable bodies across a shared face —
 * makes a machine conduct through *itself*, and a smelter acquires a hot face and a cool one with
 * no new physics written for it.
 *
 * ### Why a class and not a `LongArray`
 *
 * Every machine is a `data class`, and a `LongArray` field would give them all **reference**
 * equality: two identical smelters would compare unequal, and `copy()` would share one array
 * between the original and the copy — a mutation through either being visible in both, in a code
 * base whose central promise is that a snapshot of the world is a snapshot of the world. This wraps
 * the array so that equality is by content and the contents are never handed out mutable.
 */
class TileEnergy private constructor(private val perTile: LongArray) {

    val size: Int get() = perTile.size

    operator fun get(index: Int): Long = perTile[index]

    /** Every tile's energy added up — what the ledgers and the scrap value want. */
    val total: Long get() {
        var sum = 0L
        for (j in perTile) sum += j
        return sum
    }

    /** The same energy with [index] replaced. Returns a new value; nothing here mutates. */
    fun with(index: Int, energy: Long): TileEnergy {
        val next = perTile.copyOf()
        next[index] = energy
        return TileEnergy(next)
    }

    /**
     * [added] energy spread evenly across every tile.
     *
     * Even, because waste heat is made by the *work* a machine does and the work happens throughout
     * it — there is no more reason for a smelter's furnace losses to appear in one corner than
     * another. The remainder goes to the first tiles rather than being dropped, so that repeatedly
     * adding less than one joule per tile still warms the machine instead of vanishing.
     */
    fun plusEnergySpread(added: Long): TileEnergy {
        if (perTile.isEmpty()) return this
        val next = perTile.copyOf()
        val each = added / perTile.size
        var remainder = added - each * perTile.size
        for (i in next.indices) {
            var share = each
            if (remainder > 0) { share++; remainder-- } else if (remainder < 0) { share--; remainder++ }
            next[i] += share
        }
        return TileEnergy(next)
    }

    /**
     * The same energy with tile [index] gone — one shorter, and the rest untouched.
     *
     * For a body losing a tile, which a machine cannot do and a rock does every time an extractor
     * bites it. **Conservation is structural**: what leaves is exactly `this[index]` and what stays
     * is exactly everything else, so the two add back to the original with no rounding involved at
     * all. The share-of-the-whole arithmetic this replaces had to be written as a remainder of a
     * single truncating divide to achieve the same thing.
     */
    fun dropping(index: Int): TileEnergy {
        val next = LongArray(perTile.size - 1)
        for (i in next.indices) next[i] = if (i < index) perTile[i] else perTile[i + 1]
        return TileEnergy(next)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is TileEnergy && perTile.contentEquals(other.perTile))

    override fun hashCode(): Int = perTile.contentHashCode()

    override fun toString(): String = perTile.joinToString(prefix = "TileEnergy[", postfix = "]")

    companion object {
        /** [count] tiles all holding [each]. */
        fun uniform(count: Int, each: Long): TileEnergy = TileEnergy(LongArray(count) { each })

        /** Takes ownership of [values] — callers must not keep a reference to it. */
        fun of(values: LongArray): TileEnergy = TileEnergy(values)
    }
}

/**
 * How many tiles' worth of material a machine is made of.
 *
 * The footprint, squared — except for a bridge, which claims no floor space at all and is
 * nonetheless three tiles of metal spanning three tiles of room. Deliberately **not** derived from
 * the clipped [org.emerge.demo.outofspace.world.coveredTiles] of wherever it stands: what a thing is made of does not change when it
 * is built near the edge of the grid, and a capacity that varied with position would make an
 * identical machine hold a different amount of heat depending on where you put it.
 */
val MachineKind.thermalTiles: Int
    get() = if (this == MachineKind.Bridge) 3 else size * size

/**
 * The machine's temperature **averaged over its tiles**.
 *
 * ⚠️ A machine no longer *has* a temperature — it has one per tile, and that is the point of storing
 * them separately. This is the mean, which is the right answer for a readout, a ledger or a test that
 * cares how much heat is in the thing, and the wrong one for anything that cares where the heat is.
 * Reach for [Machine.energy] directly when the gradient is the subject.
 */
val Machine.kelvin: Int
    get() {
        val capacity = kind.capacityPerTile * kind.thermalTiles
        return if (capacity <= 0L) Temperature.SPACE_KELVIN else (energy.total / capacity).toInt()
    }

/** The same machine with every one of its tiles at [kelvin] — how a uniform body is stated. */
fun Machine.atKelvin(kelvin: Int): Machine =
    withEnergy(TileEnergy.uniform(kind.thermalTiles, kind.capacityPerTile * kelvin))

/** What a freshly built machine of this kind holds: every tile of it, at room temperature. */
fun ambientEnergy(kind: MachineKind): TileEnergy =
    TileEnergy.uniform(kind.thermalTiles, kind.capacityPerTile * Temperature.AMBIENT_KELVIN)


/** A machine that faces somewhere. Its ports are laid out relative to that direction. */
sealed interface Directed : Machine {
    val facing: Direction
    fun rotated(): Machine
}

/**
 * Machine input buffers hold this much before they stop accepting.
 *
 * **Derivation**: four tonnes — **32 ticks** of a 125 kg/tick machine's throughput, so a machine can
 * run for a good few seconds on a full buffer while its feed is interrupted.
 *
 * ⚠️ Sized in *ticks of throughput*, not in belt-loads, and that distinction has already bitten
 * once. Written as `4 × PACKET_MASS` it silently shrank tenfold when the belt-load went from a
 * tonne to 100 kg, leaving every machine with two ticks of buffer — enough that an extractor
 * stalled before its throttle could make any difference, which is a behaviour change nobody asked
 * for. A buffer's job is to decouple a machine from its supply *for a while*; the unit of "a while"
 * is ticks.
 */
val MACHINE_BUFFER_CAP = 4L * Budget.TONNE

/**
 * And output buffers hold this much before the machine stops *running*.
 *
 * Without this a processor whose waste side is blocked keeps working and hoards its tailings
 * indefinitely — tens of tonnes inside one tile, invisibly. Capping it makes a blocked output
 * back up into the input and then up the belt behind it, which is the same way every other blockage
 * in the game behaves: visibly, and starting at the thing that is actually stuck.
 *
 * **Derivation**: the same four tonnes as [MACHINE_BUFFER_CAP], and deliberately equal to it — a
 * machine that can hoard more output than input would drain its feed before it stalled.
 */
val MACHINE_OUTPUT_CAP = 4L * Budget.TONNE
