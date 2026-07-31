package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.process
import org.emerge.demo.outofspace.chem.recipeFor
import org.emerge.demo.outofspace.chem.smelt
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.Rate
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Analyzer
import org.emerge.demo.outofspace.world.Belt
import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.DebrisWork
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Directed
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Occupancy
import org.emerge.demo.outofspace.world.Port
import org.emerge.demo.outofspace.world.PortKind
import org.emerge.demo.outofspace.world.Stream
import org.emerge.demo.outofspace.world.coveredTiles
import org.emerge.demo.outofspace.world.footprintFits
import org.emerge.demo.outofspace.world.inputPortAt
import org.emerge.demo.outofspace.world.portsOf
import org.emerge.demo.outofspace.world.size
import org.emerge.demo.outofspace.world.HeatField
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.MACHINE_BUFFER_CAP
import org.emerge.demo.outofspace.world.MACHINE_OUTPUT_CAP
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Miner
import org.emerge.demo.outofspace.world.Fabricator
import org.emerge.demo.outofspace.world.Processor
import org.emerge.demo.outofspace.world.Sensor
import org.emerge.demo.outofspace.world.Signals
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.Vent
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.fullness
import org.emerge.demo.outofspace.world.settleDebris
import org.emerge.demo.outofspace.world.spoilsOf
import org.emerge.demo.outofspace.world.heatPerGram
import org.emerge.demo.outofspace.world.stepAir
import org.emerge.demo.outofspace.world.stepHeat
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimInput
import org.emerge.sim.core.SimReducer

/** Fixed world parameters. */
data class OutofspaceConfig(
    /**
     * Generous, because machines are now rooms: a smelter is five tiles across and a refinery line
     * of them is long. A grid this size is still trivial to sweep, and "a big bound with the hull
     * drawn inside it" is what gives the expansion fantasy without a growable world.
     */
    val grid: Grid = Grid(96, 60),
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

    /**
     * Rewires one term of one action. [slot] at or past the end appends; a null [trigger] removes.
     * One edit covers add, change and remove because they are the same operation on a list, and
     * three edit types would be three chances to get replay ordering subtly different.
     */
    data class Wire(val index: Int, val action: Action, val slot: Int, val trigger: Trigger?) : Edit

    /** Retunes a sensor to a different channel. */
    data class SetChannel(val index: Int, val channel: Channel) : Edit
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
 *  2. **Sense** — every sensor reads the tile it faces and raises its channel, giving one [Signals]
 *     snapshot that the whole tick then agrees on. Reading signals as they are computed would make a
 *     machine's behaviour depend on where it sits in the grid relative to its sensor.
 *  3. **Produce** — miners accrue ore into their buffers.
 *  4. **Process** — processors, smelters and fabricators draw from their inputs and fill outputs.
 *  5. **Eject** — anything holding a full packet's worth of output pushes it out through the matching
 *     output **port**: a specific tile of the machine's footprint, facing a specific way.
 *  6. **Settle debris** — loose material falls toward gravity, and anything lying outside the hull
 *     goes overboard.
 *  7. **Advance belts** — every [Belt.STEP_TICKS] ticks: deliver each belt's head packet, then shift
 *     every belt one slot toward its head.
 *
 * Every machine is throttled by its RUN activation: rate × activation, and nothing at all at zero or
 * below. Activation is a *throttle rather than a switch* so that a weight means something beyond on
 * and off — half a signal is half a machine.
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

        // Structure first: heat and (later) air both need to know what is wall and what is outside,
        // and an edit this tick must be reflected in it this tick.
        val structure = StructureMap.derive(w.grid, w.machines)
        val occupancy = Occupancy(w.originOf.copyOf())

        val signals = Signals.build { raise ->
            for (i in w.machines.indices) {
                when (val m = w.machines[i]) {
                    is Sensor -> {
                        // Through the occupancy index, so a sensor pointed at any tile of a
                        // five-tile furnace reads the furnace rather than nothing.
                        val target = w.grid.neighbour(i, m.facing)
                        val seen = if (target < 0) -1 else w.originOf[target]
                        if (seen >= 0) raise(m.channel, fullness(w.machines[seen]))
                    }
                    // The analyzer's reading persists after the packet leaves, so purity is a
                    // steady signal rather than a flicker as lumps go past.
                    is Analyzer -> raise(m.channel, m.lastPurity)
                    else -> {}
                }
            }
        }
        w.signals = signals

        for (i in w.machines.indices) {
            val m = w.machines[i] ?: continue
            val activation = m.wiring.activation(Action.Run, signals)
            w.machines[i] = when (m) {
                is Miner -> w.produce(cfg, m, activation)
                is Processor -> w.refine(cfg, m, activation, i)
                is Smelter -> w.melt(cfg, m, activation, i)
                is Fabricator -> w.fabricate(cfg, m, activation, i)
                else -> m
            }
        }

        for (i in w.machines.indices) w.eject(i)

        if (state.tick % Belt.STEP_TICKS == 0L) {
            for (i in w.machines.indices) w.deliverBeltHead(i)
            for (i in w.machines.indices) {
                val b = w.machines[i]
                if (b is Belt && b.wiring.activation(Action.Run, signals) > 0) {
                    w.machines[i] = b.copy(slots = shiftTowardHead(b.slots))
                }
            }
        }

        val warmed = state.heat.copyJoules()
        for (i in warmed.indices) warmed[i] += w.heatAdded[i]
        val (heat, radiated) = stepHeat(
            state.grid, structure, occupancy, HeatField.of(warmed), cfg.ticksPerSecond,
        )
        // Settling runs after the edits and after structure is re-derived, so a pile the player just
        // dropped falls this tick, and a pile in a room they just breached leaves with the air.
        w.ventedGrams += settleDebris(state.grid, structure, w.debris, state.gravity)

        val (air, airVented) = stepAir(
            state.grid, structure, state.air, state.gravity, cfg.ticksPerSecond,
        )

        return state.copy(
            machines = w.machines.toList(),
            tick = state.tick + 1,
            minedGrams = w.minedGrams,
            debris = w.debris.snapshot(),
            ventedGrams = w.ventedGrams,
            signals = signals,
            structure = structure,
            occupancy = occupancy,
            heat = heat,
            generatedJoules = w.generatedJoules,
            radiatedJoules = state.radiatedJoules + radiated,
            air = air,
            airVentedGrams = state.airVentedGrams + airVented,
        )
    }

    /** Full snapshots on the wire; a demo sending partial state would merge here instead. */
    override fun patchState(state: VesselState, delta: VesselState): VesselState = delta

    // ── Machine behaviour ─────────────────────────────────────────────────────

    /** True when any output buffer is full, which stops the machine until something drains it. */
    private fun blocked(vararg outputs: Resource?): Boolean =
        outputs.any { (it?.mass ?: 0L) >= MACHINE_OUTPUT_CAP }

    /** Rate scaled by activation: a throttle, not a switch. Zero or negative activation stops it. */
    private fun throttled(gramsPerSecond: Long, activation: Int): Long =
        if (activation <= 0) 0L else gramsPerSecond * activation / Signals.FULL

    private fun Work.refine(cfg: OutofspaceConfig, m: Processor, activation: Int, at: Int): Processor {
        val input = m.input ?: return m
        val (grams, carry) = Rate.tick(throttled(m.gramsPerSecond, activation), cfg.ticksPerSecond, m.carry)
        // Backed up: a full output stops the machine rather than being hoarded. Note this catches
        // the *tailings* side too, so a processor with nowhere to put its waste stops, and the jam
        // travels back up the line where it can be seen.
        if (blocked(m.product, m.tailings)) return m.copy(carry = carry)
        val chunkMass = minOf(grams, input.mass)
        if (chunkMass <= 0L) return m.copy(carry = carry)

        val chunk = Resource(input.form, input.mixture.take(chunkMass))
        heat(at, chunkMass * heatPerGram(m))
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

    private fun Work.melt(cfg: OutofspaceConfig, m: Smelter, activation: Int, at: Int): Smelter {
        val input = m.input ?: return m
        val (grams, carry) = Rate.tick(throttled(m.gramsPerSecond, activation), cfg.ticksPerSecond, m.carry)
        if (blocked(m.refined, m.slag)) return m.copy(carry = carry)
        val chunkMass = minOf(grams, input.mass)
        if (chunkMass <= 0L) return m.copy(carry = carry)

        val chunk = Resource(input.form, input.mixture.take(chunkMass))
        heat(at, chunkMass * heatPerGram(m))
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
     * Combines the fabricator's two input forms into whatever they make, equal masses of each.
     *
     * No recipe needs selecting: the crafting tree is binary, so holding two things that make a third
     * *is* the instruction. Holding two that make nothing is a machine sitting visibly idle, which is
     * a better error message than a dialog.
     */
    private fun Work.fabricate(cfg: OutofspaceConfig, m: Fabricator, activation: Int, at: Int): Fabricator {
        val (grams, carry) = Rate.tick(throttled(m.gramsPerSecond, activation), cfg.ticksPerSecond, m.carry)
        if (blocked(m.output)) return m.copy(carry = carry)
        if (m.inputs.size < 2 || grams <= 0L) return m.copy(carry = carry)

        val a = m.inputs[0]
        val b = m.inputs[1]
        val outputForm = recipeFor(a.form, b.form) ?: return m.copy(carry = carry)
        val each = minOf(grams, a.mass, b.mass)
        if (each <= 0L) return m.copy(carry = carry)

        heat(at, each * 2L * heatPerGram(m))
        val chunkA = a.mixture.take(each)
        val chunkB = b.mixture.take(each)
        val made = Resource(outputForm, chunkA + chunkB)
        val output = m.output.merged(made) ?: return m.copy(carry = carry)

        val remaining = listOf(
            Resource(a.form, a.mixture - chunkA),
            Resource(b.form, b.mixture - chunkB),
        ).filterNot { it.isEmpty }
        return m.copy(inputs = remaining, output = output.buffer, carry = carry)
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
        var minedGrams: Long = state.minedGrams
        val debris: DebrisWork = DebrisWork(state.debris)
        var ventedGrams: Long = state.ventedGrams

        /**
         * tile -> the index its machine is stored at, or -1. Maintained as edits land rather than
         * re-derived per edit, so a tick that places twenty things stays linear.
         */
        val originOf: IntArray = IntArray(state.grid.size).also { o ->
            for (i in machines.indices) {
                val m = machines[i] ?: continue
                for (t in coveredTiles(state.grid, i, m.kind.size)) o[t] = i + 1
            }
            for (i in o.indices) o[i] -= 1
        }

        /** This tick's signal snapshot, set once the sensing pass has run. */
        var signals: Signals = Signals.build { }

        /** Joules each tile gained this tick from machines doing work, applied with the heat step. */
        val heatAdded: LongArray = LongArray(state.grid.size)
        var generatedJoules: Long = state.generatedJoules

        /** Charges [joules] of waste heat to the tile at [index]. */
        fun heat(index: Int, joules: Long) {
            if (joules <= 0L || index !in heatAdded.indices) return
            heatAdded[index] += joules
            generatedJoules += joules
        }

        /** Where a machine instance currently sits — miners are charged heat by identity. */
        fun indexOf(machine: Machine): Int = machines.indexOfFirst { it === machine }

        fun apply(edit: Edit) {
            when (edit) {
                is Edit.Place -> {
                    // The click names the machine's *centre*, and the footprint grows around it.
                    if (edit.index !in machines.indices) return
                    val size = edit.kind.size
                    if (!footprintFits(grid, edit.index, size)) return
                    val covered = coveredTiles(grid, edit.index, size)
                    // Placing over anything occupied is a no-op, so a stray click cannot destroy a
                    // machine — and everything inside it — by accident. With footprints that check
                    // has to cover every tile, not just the one under the cursor.
                    if (covered.any { originOf[it] >= 0 }) return
                    machines[edit.index] = newMachine(edit.kind, edit.facing)
                    for (t in covered) originOf[t] = edit.index
                }
                is Edit.Rotate -> {
                    // Rotating about the centre leaves the covered tiles alone -- footprints are
                    // square -- so only the ports move. That is the whole reason machines anchor at
                    // their centre rather than a corner.
                    val at = originAt(edit.index) ?: return
                    val m = machines[at]
                    if (m is Directed) machines[at] = m.rotated()
                }
                is Edit.Remove -> {
                    // Clicking any tile of a machine removes the whole machine, not a slice of it.
                    val at = originAt(edit.index) ?: return
                    // Whatever it was holding falls on the deck. Deleting it instead would be a
                    // genuine leak, and the mass balance said so -- the answer is somewhere for the
                    // material to go, not an exemption for the player's own edits.
                    debris.spill(at, spoilsOf(machines[at]))
                    for (t in coveredTiles(grid, at, machines[at]!!.kind.size)) originOf[t] = -1
                    machines[at] = null
                }
                is Edit.Wire -> {
                    val at = originAt(edit.index) ?: return
                    val m = machines[at] ?: return
                    val current = m.wiring.triggers(edit.action).toMutableList()
                    when {
                        edit.trigger == null -> if (edit.slot in current.indices) current.removeAt(edit.slot)
                        edit.slot in current.indices -> current[edit.slot] = edit.trigger
                        else -> current.add(edit.trigger)
                    }
                    machines[at] = m.withWiring(m.wiring.with(edit.action, current))
                }
                is Edit.SetChannel -> {
                    val at = originAt(edit.index) ?: return
                    when (val m = machines[at]) {
                        is Sensor -> machines[at] = m.copy(channel = edit.channel)
                        is Analyzer -> machines[at] = m.copy(channel = edit.channel)
                        else -> {}
                    }
                }
            }
        }

        /** The index the machine covering [tile] is stored at, so any tile of it can be edited. */
        private fun originAt(tile: Int): Int? =
            if (tile !in originOf.indices) null else originOf[tile].takeIf { it >= 0 }

        /**
         * Digs. [minedGrams] is incremented **here**, not when a packet ships, because that is the
         * moment matter enters the world — counting it at the belt instead would leave whatever sits
         * in the miner's buffer unaccounted for, and the world-conservation invariant would be a
         * statement about shipping rather than about mass.
         */
        fun produce(cfg: OutofspaceConfig, m: Miner, activation: Int): Miner {
            val (grams, carry) = Rate.tick(throttled(m.gramsPerSecond, activation), cfg.ticksPerSecond, m.carry)
            if (m.buffer.mass >= Miner.BUFFER_CAP) return m.copy(carry = carry)  // backed up: stop digging
            if (grams <= 0L) return m.copy(carry = carry)
            val dug = m.composition.scaledTo(grams)
            minedGrams += dug.total
            heat(indexOf(m), dug.total * heatPerGram(m))
            return m.copy(buffer = Resource(Form.Ore, m.buffer.mixture + dug), carry = carry)
        }

        /**
         * Pushes a full packet out of each of a machine's output ports.
         *
         * Which buffer drains through which port is named by [Stream] rather than worked out from an
         * angle. The old rule — product leaves by `facing`, waste by `facing.clockwise` — only ever
         * worked because every machine was one tile; on a five-tile furnace the two streams leave
         * from genuinely different tiles and the relationship between them is not a rotation.
         */
        fun eject(index: Int) {
            val m = machines[index] ?: return
            if (m is Belt) return   // belts move on their own cadence, below
            // A storage only lets go while its RUN activation is positive, which is what turns it
            // from a bucket into a valve the moment you wire something to it.
            if (m is Storage && m.wiring.activation(Action.Run, signals) <= 0) return
            for (port in portsOf(grid, m, index)) {
                if (port.kind == PortKind.Output) ejectThrough(index, port)
            }
        }

        private fun ejectThrough(index: Int, port: Port) {
            when (val m = machines[index]) {
                is Miner -> {
                    val (packet, rest) = takePacket(m.buffer) ?: return
                    if (send(port, packet)) machines[index] = m.copy(buffer = rest)
                }
                is Processor -> {
                    val buffer = if (port.stream == Stream.Waste) m.tailings else m.product
                    val (packet, rest) = buffer?.let(::takePacket) ?: return
                    if (!send(port, packet)) return
                    val now = machines[index] as Processor
                    machines[index] =
                        if (port.stream == Stream.Waste) now.copy(tailings = rest.orNull())
                        else now.copy(product = rest.orNull())
                }
                is Smelter -> {
                    val buffer = if (port.stream == Stream.Waste) m.slag else m.refined
                    val (packet, rest) = buffer?.let(::takePacket) ?: return
                    if (!send(port, packet)) return
                    val now = machines[index] as Smelter
                    machines[index] =
                        if (port.stream == Stream.Waste) now.copy(slag = rest.orNull())
                        else now.copy(refined = rest.orNull())
                }
                is Fabricator -> {
                    val (packet, rest) = m.output?.let(::takePacket) ?: return
                    if (send(port, packet)) machines[index] = m.copy(output = rest.orNull())
                }
                is Analyzer -> {
                    val held = m.holding ?: return
                    // holding clears; the reading stays, which is what makes the tile readable when
                    // the line is idle.
                    if (send(port, held)) machines[index] = m.copy(holding = null)
                }
                is Storage -> {
                    val (packet, rest) = m.contents?.let(::takePacket) ?: return
                    if (send(port, packet)) machines[index] = m.copy(contents = rest.orNull())
                }
                else -> {}
            }
        }

        /** Hands a belt's head packet out through its output port. */
        fun deliverBeltHead(index: Int) {
            val belt = machines[index] as? Belt ?: return
            val head = belt.slots.firstOrNull() ?: return
            val port = portsOf(grid, belt, index).firstOrNull { it.kind == PortKind.Output } ?: return
            if (send(port, head)) {
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

        /**
         * Offers [packet] out through [port]. True if something on the other side took it.
         *
         * The receiving side must have an **input port on the tile the packet arrives at, facing
         * back the way it came**. Checking the facing and not merely the tile is what stops a
         * three-by-three building behaving like a nine-tile sponge that absorbs anything touching
         * it — a machine has a back and two sides, and routing to the right one is the mechanic.
         */
        fun send(port: Port, packet: Packet): Boolean {
            val target = grid.neighbour(port.tile, port.side)
            if (target < 0) return false
            val at = originOf[target]
            if (at < 0) return false
            val dest = machines[at] ?: return false
            if (inputPortAt(grid, dest, at, target, port.side) == null) return false
            return deliver(at, dest, packet)
        }

        /** Puts [packet] into the accepting machine's own buffers, or refuses it. */
        private fun deliver(target: Int, destination: Machine, packet: Packet): Boolean {
            return when (val dest = destination) {
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
                is Storage -> {
                    if (packet !is SolidPacket) false
                    else {
                        val existing = dest.contents
                        if (existing != null && existing.form != packet.form) false
                        else if ((existing?.mass ?: 0L) >= Storage.CAP) false
                        else {
                            val merged = if (existing == null) packet.resource
                            else Resource(existing.form, existing.mixture + packet.contents)
                            machines[target] = dest.copy(contents = merged)
                            true
                        }
                    }
                }
                is Fabricator -> acceptIntoFabricator(target, dest, packet)
                is Analyzer -> {
                    // One at a time, and only while running: it is a measuring belt, not a buffer.
                    if (dest.holding != null || dest.wiring.activation(Action.Run, signals) <= 0) false
                    else {
                        machines[target] = dest.reading(packet)
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

        /**
         * A fabricator takes up to [Fabricator.MAX_INPUTS] distinct forms and tops up either of them.
         * A third form is refused rather than swapped in, so a mis-wired line backs up visibly
         * instead of silently displacing an ingredient.
         */
        private fun acceptIntoFabricator(target: Int, dest: Fabricator, packet: Packet): Boolean {
            if (packet !is SolidPacket) return false
            val existing = dest.inputs.indexOfFirst { it.form == packet.form }
            if (existing >= 0) {
                if (dest.inputs[existing].mass >= Fabricator.INPUT_CAP) return false
                val merged = dest.inputs.toMutableList()
                merged[existing] = Resource(packet.form, merged[existing].mixture + packet.contents)
                machines[target] = dest.copy(inputs = merged)
                return true
            }
            if (dest.inputs.size >= Fabricator.MAX_INPUTS) return false
            machines[target] = dest.copy(inputs = dest.inputs + packet.resource)
            return true
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
            MachineKind.Fabricator -> Fabricator(facing)
            MachineKind.Storage -> Storage(facing)
            MachineKind.Sensor -> Sensor(facing)
            MachineKind.Analyzer -> Analyzer(facing)
            MachineKind.Vent -> Vent()
            MachineKind.Hull -> Hull()
        }
    }
}
