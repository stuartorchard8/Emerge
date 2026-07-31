package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.process
import org.emerge.demo.outofspace.chem.smelt
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.Rate
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Belt
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Directed
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MACHINE_BUFFER_CAP
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Miner
import org.emerge.demo.outofspace.world.Node
import org.emerge.demo.outofspace.world.Processor
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Stockpile
import org.emerge.demo.outofspace.world.Vent
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimInput
import org.emerge.sim.core.SimReducer

/** Fixed world parameters. */
data class OutofspaceConfig(
    val grid: Grid = Grid(48, 28),
    /** Sim tick rate. Feeds [Rate], which is what makes a machine's grams-per-second exact. */
    val ticksPerSecond: Int = 60,
) {
    val secondsPerTick: Float get() = 1f / ticksPerSecond
}

/** A player action. Actions are values, so they replay, serialise and travel over a wire. */
sealed interface Edit {
    data class Place(val index: Int, val kind: MachineKind, val facing: Direction) : Edit
    data class Rotate(val index: Int) : Edit
    data class Remove(val index: Int) : Edit
}

data class OutofspaceInput(val edits: List<Edit> = emptyList()) : SimInput {
    companion object {
        val EMPTY = OutofspaceInput()
    }
}

/**
 * The rules of the world.
 *
 * One tick, in order:
 *
 *  1. **Edits** — the player's placements land first, so a machine placed this tick works this tick.
 *  2. **Produce** — miners accrue ore into their buffers.
 *  3. **Process** — processors and smelters draw from their input buffer and fill their outputs.
 *  4. **Eject** — anything holding a full packet's worth of output pushes it into the tile it faces.
 *     Waste leaves by the side clockwise of facing, so a rightward line drops its waste downward.
 *  5. **Advance belts** — every [Belt.STEP_TICKS] ticks: deliver each belt's head packet, then shift
 *     every belt one slot toward its head.
 *
 * Passes 4 and 5 walk the grid in row-major order, so when two machines compete to feed one tile the
 * lower index wins. Arbitrary, but *fixed* — which is what determinism actually requires.
 *
 * Delivery is one step per belt per advance rather than a chain resolved instantly. A full
 * downstream belt therefore costs its upstream neighbour a step of latency, which is exactly what
 * makes a jam crawl backwards up the line where you can watch it happen.
 */
object OutofspaceReducer : SimReducer<OutofspaceConfig, VesselState, OutofspaceInput> {

    override fun reduce(
        cfg: OutofspaceConfig,
        state: VesselState,
        inputs: Map<PlayerId, OutofspaceInput>,
    ): VesselState {
        val w = Work(state)

        // Sorted by PlayerId: two players editing on the same tick must be applied in the same order
        // on every peer, and map iteration order is not a promise.
        for ((_, input) in inputs.entries.sortedBy { it.key.value }) {
            for (edit in input.edits) w.apply(edit)
        }

        for (i in w.machines.indices) {
            when (val m = w.machines[i]) {
                is Miner -> w.machines[i] = w.produce(cfg, m)
                is Processor -> w.machines[i] = refine(cfg, m)
                is Smelter -> w.machines[i] = melt(cfg, m)
                else -> {}
            }
        }

        for (i in w.machines.indices) w.eject(i)

        if (state.tick % Belt.STEP_TICKS == 0L) {
            for (i in w.machines.indices) w.deliverBeltHead(i)
            for (i in w.machines.indices) {
                val b = w.machines[i]
                if (b is Belt) w.machines[i] = b.copy(slots = shiftTowardHead(b.slots))
            }
        }

        return state.copy(
            machines = w.machines.toList(),
            stockpile = w.stockpile,
            tick = state.tick + 1,
            minedGrams = w.minedGrams,
            ventedGrams = w.ventedGrams,
        )
    }

    /** Full snapshots on the wire; a demo sending partial state would merge here instead. */
    override fun patchState(state: VesselState, delta: VesselState): VesselState = delta

    // ── Machine behaviour ─────────────────────────────────────────────────────

    private fun refine(cfg: OutofspaceConfig, m: Processor): Processor {
        val input = m.input ?: return m
        val (grams, carry) = Rate.tick(m.gramsPerSecond, cfg.ticksPerSecond, m.carry)
        val chunkMass = minOf(grams, input.mass)
        if (chunkMass <= 0L) return m.copy(carry = carry)

        val chunk = Resource(input.form, input.mixture.take(chunkMass))
        val r = process(chunk, m.efficiencyPermille)
        val product = m.product.merged(r.product) ?: return m.copy(carry = carry)
        val tailings = m.tailings.merged(r.tailings) ?: return m.copy(carry = carry)

        return m.copy(
            input = Resource(input.form, input.mixture - chunk.mixture).orNull(),
            product = product.buffer,
            tailings = tailings.buffer,
            carry = carry,
        )
    }

    private fun melt(cfg: OutofspaceConfig, m: Smelter): Smelter {
        val input = m.input ?: return m
        val (grams, carry) = Rate.tick(m.gramsPerSecond, cfg.ticksPerSecond, m.carry)
        val chunkMass = minOf(grams, input.mass)
        if (chunkMass <= 0L) return m.copy(carry = carry)

        val chunk = Resource(input.form, input.mixture.take(chunkMass))
        val r = smelt(chunk)
        // A smelter holds one kind of ingot at a time. If this chunk's dominant species differs from
        // what is already waiting, stall rather than quietly mixing two metals — a stopped machine is
        // the honest signal that the ore body changed under it.
        val refined = m.refined.merged(r.refined) ?: return m.copy(carry = carry)
        val slag = m.slag.merged(r.slag) ?: return m.copy(carry = carry)

        return m.copy(
            input = Resource(input.form, input.mixture - chunk.mixture).orNull(),
            refined = refined.buffer,
            slag = slag.buffer,
            carry = carry,
        )
    }

    /**
     * The new contents of an output buffer after [addition] is merged in, or null if the two forms
     * cannot coexist.
     *
     * The wrapper exists because "nothing to add, buffer still empty" and "these cannot mix" are
     * *both* naturally expressed as a null buffer, and conflating them made every machine stall
     * whenever one of its two output streams happened to be empty — which for an all-slag smelt is
     * always. Distinguishing the two is the whole job of this type.
     */
    private class Merge(val buffer: Resource?)

    private fun Resource?.merged(addition: Resource): Merge? = when {
        addition.isEmpty -> Merge(this)
        this == null || this.isEmpty -> Merge(addition)
        this.form != addition.form -> null
        else -> Merge(Resource(form, mixture + addition.mixture))
    }

    private fun Resource.orNull(): Resource? = if (isEmpty) null else this

    /** Moves everything one slot toward the head, leaving the tail free. */
    private fun shiftTowardHead(slots: List<Packet?>): List<Packet?> {
        val out = arrayOfNulls<Packet>(slots.size)
        for (i in slots.indices) out[i] = slots[i]
        for (i in out.indices) {
            if (out[i] == null && i + 1 < out.size) {
                out[i] = out[i + 1]
                out[i + 1] = null
            }
        }
        return out.toList()
    }

    /**
     * The ore body a new miner draws from, per kilogram. Mostly iron and far too dirty to smelt
     * straight — 410g of iron against 590g of everything else — so a line that runs ore directly into
     * a smelter yields nothing but slag. Learning to put a processor in front is the first thing this
     * world teaches, and it teaches it without a tutorial.
     */
    val DEFAULT_ORE_BODY: Mixture = Mixture.of(
        Species.Iron to 410L,
        Species.Silica to 300L,
        Species.Copper to 180L,
        Species.Titanium to 110L,
    )

    /**
     * Mutable scratch for one tick. The incoming [VesselState] is never touched — this copies the
     * machine list up front and hands back a fresh one, so the reducer stays pure while the passes
     * inside it can still see each other's work.
     */
    private class Work(state: VesselState) {
        val grid: Grid = state.grid
        val machines: MutableList<Machine?> = state.machines.toMutableList()
        var stockpile: Stockpile = state.stockpile
        var minedGrams: Long = state.minedGrams
        var ventedGrams: Long = state.ventedGrams

        fun apply(edit: Edit) {
            when (edit) {
                is Edit.Place -> {
                    if (edit.index !in machines.indices) return
                    // Placing onto an occupied tile is a no-op, so a stray click cannot destroy a
                    // machine — and everything inside it — by accident.
                    if (machines[edit.index] != null) return
                    machines[edit.index] = newMachine(edit.kind, edit.facing)
                }
                is Edit.Rotate -> {
                    val m = machines.getOrNull(edit.index)
                    if (m is Directed) machines[edit.index] = m.rotated()
                }
                is Edit.Remove -> {
                    if (edit.index in machines.indices) machines[edit.index] = null
                }
            }
        }

        /**
         * Digs. [minedGrams] is incremented **here**, not when a packet ships, because that is the
         * moment matter enters the world — counting it at the belt instead would leave whatever sits
         * in the miner's buffer unaccounted for, and the world-conservation invariant would be a
         * statement about shipping rather than about mass.
         */
        fun produce(cfg: OutofspaceConfig, m: Miner): Miner {
            val (grams, carry) = Rate.tick(m.gramsPerSecond, cfg.ticksPerSecond, m.carry)
            if (m.buffer.mass >= Miner.BUFFER_CAP) return m.copy(carry = carry)  // backed up: stop digging
            if (grams <= 0L) return m.copy(carry = carry)
            val dug = m.composition.scaledTo(grams)
            minedGrams += dug.total
            return m.copy(buffer = Resource(Form.Ore, m.buffer.mixture + dug), carry = carry)
        }

        /** Pushes a full packet out of any machine holding one: product forward, waste sideways. */
        fun eject(index: Int) {
            when (val m = machines[index]) {
                is Miner -> {
                    val (packet, rest) = takePacket(m.buffer) ?: return
                    if (send(index, m.facing, packet)) machines[index] = m.copy(buffer = rest)
                }
                is Processor -> {
                    m.product?.let(::takePacket)?.let { (packet, rest) ->
                        if (send(index, m.facing, packet)) {
                            machines[index] = (machines[index] as Processor).copy(product = rest.orNull())
                        }
                    }
                    val after = machines[index] as? Processor ?: return
                    after.tailings?.let(::takePacket)?.let { (packet, rest) ->
                        if (send(index, after.facing.clockwise, packet)) {
                            machines[index] = (machines[index] as Processor).copy(tailings = rest.orNull())
                        }
                    }
                }
                is Smelter -> {
                    m.refined?.let(::takePacket)?.let { (packet, rest) ->
                        if (send(index, m.facing, packet)) {
                            machines[index] = (machines[index] as Smelter).copy(refined = rest.orNull())
                        }
                    }
                    val after = machines[index] as? Smelter ?: return
                    after.slag?.let(::takePacket)?.let { (packet, rest) ->
                        if (send(index, after.facing.clockwise, packet)) {
                            machines[index] = (machines[index] as Smelter).copy(slag = rest.orNull())
                        }
                    }
                }
                else -> {}
            }
        }

        /** Hands a belt's head packet to whatever it faces. */
        fun deliverBeltHead(index: Int) {
            val belt = machines[index] as? Belt ?: return
            val head = belt.slots.firstOrNull() ?: return
            if (send(index, belt.facing, head)) {
                machines[index] = belt.copy(slots = belt.slots.toMutableList().also { it[0] = null })
            }
        }

        /**
         * Only whole packets move, so belts carry uniform lumps and a trickle of output does not
         * spray the network with crumbs. Null while the buffer is still filling.
         */
        private fun takePacket(buffer: Resource): Pair<SolidPacket, Resource>? {
            if (buffer.mass < Capacity.PACKET_GRAMS) return null
            val taken = buffer.mixture.take(Capacity.PACKET_GRAMS)
            return SolidPacket(Resource(buffer.form, taken)) to Resource(buffer.form, buffer.mixture - taken)
        }

        private fun Resource.orNull(): Resource? = if (isEmpty) null else this

        /** Offers [packet] to the neighbour of [from] in [dir]. True if it was taken. */
        fun send(from: Int, dir: Direction, packet: Packet): Boolean {
            val target = grid.neighbour(from, dir)
            if (target < 0) return false
            return when (val dest = machines[target]) {
                is Belt -> {
                    val tail = dest.slots.lastIndex
                    if (dest.slots[tail] != null) false
                    else {
                        machines[target] = dest.copy(slots = dest.slots.toMutableList().also { it[tail] = packet })
                        true
                    }
                }
                is Processor -> acceptInto(dest.input, packet)?.let { machines[target] = dest.copy(input = it); true } ?: false
                is Smelter -> acceptInto(dest.input, packet)?.let { machines[target] = dest.copy(input = it); true } ?: false
                is Node -> {
                    if (packet !is SolidPacket) false
                    else {
                        stockpile = stockpile.deposit(packet.resource)
                        machines[target] = dest.copy(absorbedGrams = dest.absorbedGrams + packet.mass)
                        true
                    }
                }
                is Vent -> {
                    ventedGrams += packet.mass
                    machines[target] = dest.copy(ventedGrams = dest.ventedGrams + packet.mass)
                    true
                }
                // Miners take no input, and an empty tile is not a floor to drop things on.
                else -> false
            }
        }

        /** The new input buffer if [packet] is acceptable, else null. */
        private fun acceptInto(existing: Resource?, packet: Packet): Resource? {
            if (packet !is SolidPacket) return null
            if (existing != null && existing.form != packet.form) return null
            if ((existing?.mass ?: 0L) >= MACHINE_BUFFER_CAP) return null
            return if (existing == null) packet.resource
            else Resource(existing.form, existing.mixture + packet.contents)
        }

        private fun newMachine(kind: MachineKind, facing: Direction): Machine = when (kind) {
            MachineKind.Belt -> Belt(facing)
            MachineKind.Miner -> Miner(facing, DEFAULT_ORE_BODY)
            MachineKind.Processor -> Processor(facing)
            MachineKind.Smelter -> Smelter(facing)
            MachineKind.Node -> Node()
            MachineKind.Vent -> Vent()
        }
    }
}
