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
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Bridge
import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.DiverterWork
import org.emerge.demo.outofspace.world.FlowField
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.advanceSegments
import org.emerge.demo.outofspace.world.squashOnto
import org.emerge.demo.outofspace.world.DebrisWork
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Directed
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Occupancy
import org.emerge.demo.outofspace.world.Port
import org.emerge.demo.outofspace.world.PortKind
import org.emerge.demo.outofspace.world.Stream
import org.emerge.demo.outofspace.world.coveredTiles
import org.emerge.demo.outofspace.world.tryDisplaceAir
import org.emerge.demo.outofspace.world.footprintFits
import org.emerge.demo.outofspace.world.portsOf
import org.emerge.demo.outofspace.world.size
import org.emerge.demo.outofspace.world.Body
import org.emerge.demo.outofspace.world.ambientJoules
import org.emerge.demo.outofspace.world.bodiesOf
import org.emerge.demo.outofspace.world.BodySlot
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.MACHINE_BUFFER_CAP
import org.emerge.demo.outofspace.world.MACHINE_OUTPUT_CAP
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Motion
import org.emerge.demo.outofspace.world.MotionLog
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Miner
import org.emerge.demo.outofspace.world.Processor
import org.emerge.demo.outofspace.world.Pump
import org.emerge.demo.outofspace.world.Sensor
import org.emerge.demo.outofspace.world.Signals
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.Vent
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.fullness
import org.emerge.demo.outofspace.world.settleDebris
import org.emerge.demo.outofspace.world.spoilsOf
import org.emerge.demo.outofspace.world.heatPerGram
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.MomentumField
import org.emerge.demo.outofspace.world.fluid.ApertureField
import org.emerge.demo.outofspace.world.fluid.PumpDemand
import org.emerge.demo.outofspace.world.fluid.applyPumps
import org.emerge.demo.outofspace.world.fluid.exchangeLayers
import org.emerge.demo.outofspace.world.fluid.pipeApertures
import org.emerge.demo.outofspace.world.fluid.pipeVolumes
import org.emerge.demo.outofspace.world.fluid.stepFluid
import org.emerge.demo.outofspace.world.fluid.valveOpenings
import org.emerge.demo.outofspace.world.stepSolidHeat
import org.emerge.demo.outofspace.world.fluid.gasCapacity
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer

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
 *  4. **Process** — processors and smelters draw from their inputs and fill outputs.
 *  5. **Eject** — anything holding a full packet's worth of output pushes it out through the matching
 *     output **port**: a specific tile of the machine's footprint, facing a specific way.
 *  6. **Settle debris** — loose material falls toward gravity, and anything lying outside the hull
 *     goes overboard.
 *  7. **Advance the conduits** — every [Bridge.STEP_TICKS] ticks: shift every bridge along by a
 *     slot, then derive each layer's flow field from where its **input** ports are and move
 *     everything on it one step toward the nearest of them, offering each packet to the port under
 *     it first. A bridge steps with the layer because it *is* three tiles of the layer.
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
                    else -> {}
                }
            }
            // A gauge's reading persists after the packet leaves, so purity is a steady signal
            // rather than a flicker as lumps go past.
            for (r in w.rails) {
                val channel = r?.channel ?: continue
                raise(channel, r.lastPurity)
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
                else -> m
            }
        }

        // Rails first, so a building that produced this tick can put its output on the track after
        // the track moves. The alternative -- load, then move -- causes packets to instantly be transported away
        // from the building in the same tick they were dropped.
        val ports = w.portsByTile(Conduit.Rail)
        if (state.tick % Bridge.STEP_TICKS == 0L) w.advanceRails(ports)

        for ((tile, at) in ports) for (port in at) {
            if (port.kind == PortKind.Output) w.pushOut(tile, port)
        }

        // Settling runs after the edits and after structure is re-derived, so a pile the player just
        // dropped falls this tick, and a pile in a room they just breached leaves with the air.
        w.ventedGrams += settleDebris(state.grid, structure, w.debris, state.gravity)

        // ── Heat ──────────────────────────────────────────────────────────────
        //
        // Conduction runs **before** the fluid, and on the air as the edit pass left it. That order
        // is what makes convection a loop rather than a lag: a wall warms the parcel beside it, and
        // then, in the same tick, buoyancy finds that parcel light and lifts it. The other way round
        // the gas is moved first and heated afterwards, so every parcel rises one tick after it was
        // warmed and the circulation always trails its own cause.
        //
        // Waste heat lands in the machine that did the work — see [heatPerGram] — so it has to
        // conduct out through the casing before the room feels it.
        for (i in w.machines.indices) {
            val added = w.heatAdded[i]
            if (added == 0L) continue
            val m = w.machines[i] ?: continue
            w.machines[i] = m.withJoules(m.joules + added)
        }
        val bodies = bodiesOf(state.grid, w.machines, w.conduitsSnapshot(), w.bridges)
        val conducted = stepSolidHeat(
            grid = state.grid,
            bodies = bodies,
            structure = structure,
            airJoules = w.airJoules,
            airCapacity = gasCapacity(state.grid.size, w.airGrams),
        )
        w.applyBodyHeat(bodies, conducted.joules)

        val edges = EdgeGrid(state.grid)
        val conduits = w.conduitsSnapshot()
        val roomApertures = ApertureField.derive(edges, structure)
        val plumbing = pipeApertures(edges, conduits)
        val volumes = pipeVolumes(state.grid, conduits)

        // Valves first, before either layer is solved. A crossing delivers pressure into a cell, and
        // it should be free to propagate away in the tick it arrived rather than sitting for one —
        // the same reason conduction runs before the fluid. It also settles the ordering question
        // between the two layers by making it moot: both of them see the exchange, so neither is
        // privileged by going first. See [exchangeLayers].
        val crossed = exchangeLayers(
            edges = edges,
            openings = valveOpenings(state.grid, conduits),
            roomApertures = roomApertures,
            roomGrams = w.airGrams,
            roomJoules = w.airJoules,
            roomMx = w.momentumX,
            roomMy = w.momentumY,
            pipeApertures = plumbing,
            pipeGrams = w.pipeGrams,
            pipeJoules = w.pipeJoules,
            pipeMx = w.pipeMomentumX,
            pipeMy = w.pipeMomentumY,
            pipeVolumes = volumes,
        )

        // Pumps, alongside the valves and before either layer is solved, for the same reason. Gas
        // arrives in the pipe as pressure and with no momentum: nothing tells it which way to go
        // along the run, and the ordinary solver works that out on the next pass. See [applyPumps].
        val pumped = applyPumps(
            edges = edges,
            demands = pumpDemands(state.grid, w.machines, conduits, signals),
            roomGrams = w.airGrams,
            roomJoules = w.airJoules,
            roomMx = w.momentumX,
            roomMy = w.momentumY,
            pipeGrams = w.pipeGrams,
            pipeJoules = w.pipeJoules,
            pipeVolumes = volumes,
        )

        // On `w.airGrams`, which the edit pass has already shoved air around in — see [displaceAir].
        val fluid = stepFluid(
            edges, roomApertures, w.airGrams, w.momentumX, w.momentumY, state.gravity, w.airJoules,
        )

        // The pipes, on the same lattice and through the same solver — see [pipeApertures]. Their
        // connectivity comes from what the player drew rather than from what is solid, and their
        // cells are a fraction of a tile, which is the whole of what makes a pipe a pipe.
        val pipes = stepFluid(
            edges,
            plumbing,
            w.pipeGrams,
            w.pipeMomentumX,
            w.pipeMomentumY,
            state.gravity,
            w.pipeJoules,
            volumes,
        )
        // A pipe has no aperture onto the rim, so nothing can cross it. Checked rather than assumed:
        // this is the one number that would silently drain the shared air ledger if it were wrong.
        require(pipes.ventedGrams == 0L && pipes.ventedJoules == 0L) {
            "a sealed pipe network vented ${pipes.ventedGrams}g — a rim face was open"
        }

        return state.copy(
            machines = w.machines.toList(),
            conduits = conduits,
            bridges = w.bridges.toList(),
            diverters = w.diverters.snapshot(),
            tick = state.tick + 1,
            minedGrams = w.minedGrams,
            debris = w.debris.snapshot(),
            ventedGrams = w.ventedGrams,
            signals = signals,
            structure = structure,
            occupancy = occupancy,
            generatedJoules = w.generatedJoules,
            radiatedJoules = state.radiatedJoules + conducted.radiated,
            constructionJoules = w.constructionJoules,
            // What the fabric gave the atmosphere this tick. Both ledgers read it, with opposite
            // signs, which is what lets each of them still close on its own — see [SolidHeatStep].
            solidToAirJoules = state.solidToAirJoules + conducted.toAir,
            air = fluid.air,
            pipeAir = pipes.air,
            pipeMomentum = MomentumField.of(edges, pipes.momentumX, pipes.momentumY),
            airVentedGrams = state.airVentedGrams + fluid.ventedGrams,
            // Its own ledger rather than folded into `radiatedJoules`, for the same reason the air's
            // mass has one separate from the ore's: `atmosphere + vented == baseline` is a cleaner
            // statement than a combined total, and a break in one does not obscure the other.
            airVentedJoules = state.airVentedJoules + fluid.ventedJoules,
            momentum = MomentumField.of(edges, fluid.momentumX, fluid.momentumY),
            // Gas leaning on a pipe elbow pushes the ship exactly as gas leaning on a bulkhead does.
            // Same term, because it is the same physics happening one layer over.
            // Gas shoved into the closed end of a pipe leans on the fitting, and the fitting is
            // bolted to the ship — see [exchangeLayers].
            // A pump's intake stops the gas it draws, and the ship feels it — which is what makes a
            // pump usable as a thruster. See [applyPumps].
            vesselImpulseX = state.vesselImpulseX + fluid.vesselX + pipes.vesselX +
                crossed.vesselX + pumped.vesselX,
            vesselImpulseY = state.vesselImpulseY + fluid.vesselY + pipes.vesselY +
                crossed.vesselY + pumped.vesselY,
            exhaustMomentumX = state.exhaustMomentumX + fluid.escapedX,
            exhaustMomentumY = state.exhaustMomentumY + fluid.escapedY,
            motion = w.motion.freeze(),
        )
    }

    /** Full snapshots on the wire; a demo sending partial state would merge here instead. */
    override fun patchState(state: VesselState, delta: VesselState): VesselState = delta

    /**
     * What every pump is asking to move this tick: from the room it faces, into the pipe under it.
     *
     * Both ends are optional and a pump missing either simply does not appear in the list. No pipe
     * beneath it has nowhere to push; facing the hull, or facing off the edge of the grid, has
     * nothing to draw. Neither is an error — a half-built gas system is an ordinary state to leave a
     * vessel in, and a pump that complains about it would be a pump you cannot build incrementally.
     *
     * Activation is applied here rather than in [applyPumps], so that everything about signals and
     * throttling stays on this side of the boundary. It is a throttle rather than a switch, like
     * every other machine: half a signal is half a pump.
     */
    private fun pumpDemands(
        grid: Grid,
        machines: List<Machine?>,
        conduits: Conduits,
        signals: Signals,
    ): List<PumpDemand> {
        var demands: MutableList<PumpDemand>? = null
        for (tile in machines.indices) {
            val pump = machines[tile] as? Pump ?: continue
            if (conduits.at(Conduit.Pipe, tile) == null) continue
            val intake = grid.neighbour(tile, pump.facing)
            if (intake < 0) continue
            val activation = pump.wiring.activation(Action.Run, signals)
            if (activation <= 0) continue
            val moles = Pump.MILLIMOLES_PER_TICK * activation / Signals.FULL
            if (moles <= 0L) continue
            (demands ?: ArrayList<PumpDemand>(4).also { demands = it }).add(PumpDemand(intake, tile, moles))
        }
        return demands ?: emptyList()
    }

    // ── Machine behaviour ─────────────────────────────────────────────────────

    /** True when any output buffer is full, which stops the machine until something drains it. */
    private fun blocked(vararg outputs: Resource?): Boolean =
        outputs.any { (it?.mass ?: 0L) >= MACHINE_OUTPUT_CAP }

    /**
     * Grams for this tick: the machine's per-tick rate scaled by activation, which is a throttle
     * rather than a switch. Zero or negative activation stops it.
     *
     * The scaling is where the only fraction in a machine's throughput lives, so [Rate] carries the
     * remainder across ticks — see its note. A machine at full activation gets its rate untouched.
     */
    private fun throttled(gramsPerTick: Long, activation: Int, carry: Long): Pair<Long, Long> =
        if (activation <= 0) 0L to carry
        else Rate.tick(gramsPerTick * activation, Signals.FULL, carry)

    private fun Work.refine(cfg: OutofspaceConfig, m: Processor, activation: Int, at: Int): Processor {
        val input = m.input ?: return m
        val (grams, carry) = throttled(m.gramsPerTick, activation, m.carry)
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
        val (grams, carry) = throttled(m.gramsPerTick, activation, m.carry)
        if (blocked(m.refined, m.slag)) return m.copy(carry = carry)
        val chunkMass = minOf(grams, input.mass)
        if (chunkMass <= 0L) return m.copy(carry = carry)

        val chunk = Resource(input.form, input.mixture.take(chunkMass))
        heat(at, chunkMass * heatPerGram(m))
        val r = smelt(chunk)
        // A smelter holds one kind of ingot at a time. If this chunk's dominant species differs from
        // what is already waiting, stall rather than quietly mixing two metals — a stopped machine is
        // the honest signal that the orebody changed under it.
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
     * The new contents of an output buffer after addition is merged in, or null if the two forms
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
     * The orebody a new miner draws from, per kilogram. Mostly iron and far too dirty to smelt
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
        /**
         * The conduit layers, one editable grid each — see [Conduits].
         *
         * An array of lists rather than a [Conduits] because this is the working copy and every edit
         * writes one slot; rebuilding an immutable structure per laid tile would be the one place in
         * the reducer that allocates per keystroke.
         */
        val layers: Array<MutableList<Segment?>> =
            Array(Conduit.entries.size) { state.conduits[Conduit.entries[it]].toMutableList() }

        fun layer(conduit: Conduit): MutableList<Segment?> = layers[conduit.ordinal]

        /** The rail layer, which packets, gauges, bridges and motion all mean by "the track". */
        val rails: MutableList<Segment?> get() = layers[Conduit.Rail.ordinal]

        fun conduitsSnapshot(): Conduits {
            var out = Conduits.empty(grid.size)
            for (c in Conduit.entries) out = out.with(c, layers[c.ordinal].toList())
            return out
        }
        val bridges: MutableList<Bridge?> = state.bridges.toMutableList()
        val diverters: DiverterWork = DiverterWork(state.diverters)
        var ventedGrams: Long = state.ventedGrams

        /**
         * This tick's air, mutable so that the edit pass and the fluid pass work on one array. An
         * edit that changes whether a tile can hold air has to move that air *before* the flow runs,
         * or it is stranded in a wall for as long as the wall stands.
         */
        val airGrams: LongArray = state.air.copyGrams()

        /** This tick's air temperature, as energy — mutable for the same reason [airGrams] is. */
        val airJoules: LongArray = state.air.copyJoules()

        /** This tick's momentum, mutable for the same reason [airGrams] is. */
        val momentumX: LongArray = state.momentum.copyX()
        val momentumY: LongArray = state.momentum.copyY()

        /** The pipes' own fluid, in the same four working arrays and for the same reasons. */
        val pipeGrams: LongArray = state.pipeAir.copyGrams()
        val pipeJoules: LongArray = state.pipeAir.copyJoules()
        val pipeMomentumX: LongArray = state.pipeMomentum.copyX()
        val pipeMomentumY: LongArray = state.pipeMomentum.copyY()

        /**
         * This tick's record of what moved where, for the renderer alone — see [Motion].
         *
         * Built from the rails as they were before anything happened, so a packet ejected by a
         * machine during this tick is correctly reported as having appeared rather than as having
         * always been there.
         */
        val motion: MotionLog = MotionLog(state.rails)


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

        /**
         * Joules each **machine** gained this tick from its own work, indexed by where it is stored.
         *
         * By machine rather than by tile, which is the body model's whole point here: a furnace's
         * waste heat is in the furnace, so it has to get out through the firebrick before the room
         * feels it. Charged to a separate array rather than straight onto the machine because the
         * production passes rebuild machines as they go, and a `copy` in a later pass would drop a
         * write made by an earlier one.
         */
        val heatAdded: LongArray = LongArray(state.grid.size)
        var generatedJoules: Long = state.generatedJoules

        /**
         * Energy that arrived in the world inside newly built bodies, less what left inside scrapped
         * ones — see [VesselState.constructionJoules].
         */
        var constructionJoules: Long = state.constructionJoules

        /** Charges [joules] of waste heat to the machine stored at [index]. */
        fun heat(index: Int, joules: Long) {
            if (joules <= 0L || index !in heatAdded.indices) return
            heatAdded[index] += joules
            generatedJoules += joules
        }

        /** Books a body's energy in or out of the world as it is built or scrapped. */
        fun built(joules: Long) { constructionJoules += joules }

        fun scrapped(joules: Long) { constructionJoules -= joules }

        /**
         * Puts each body's new energy back where it came from.
         *
         * Through [Body.slot] and [Body.at] rather than by rebuilding the lists, so the conduction
         * pass never has to know which of three collections a thing lives in — that is the one
         * question the slot exists to answer.
         */
        fun applyBodyHeat(bodies: List<Body>, joules: LongArray) {
            for (i in bodies.indices) {
                val body = bodies[i]
                if (joules[i] == body.joules) continue
                when (body.slot) {
                    BodySlot.Deck -> machines[body.at]?.let { machines[body.at] = it.withJoules(joules[i]) }
                    // Keyed by layer as well as tile: two fittings can stand on one tile and each
                    // has its own temperature, so `at` alone would put a pipe's heat on a rail.
                    BodySlot.Fitting -> body.conduit?.let { c ->
                        layer(c)[body.at]?.let { layer(c)[body.at] = it.copy(joules = joules[i]) }
                    }
                    BodySlot.Span -> bridges[body.at]?.let { bridges[body.at] = it.withJoules(joules[i]) as Bridge }
                }
            }
        }

        /**
         * Whatever was in a pipe cell, let out into the room — because that is what cutting a pipe
         * does.
         *
         * The gas and its heat both move to the same tile of the vessel's own atmosphere. Not
         * deleted, because [air] and [pipeAir] share one ledger and deleting a gram here would read
         * downstream as a leak; and not refused like a build over a sealed room, because a player
         * pulling a pipe apart has every right to and the gas has somewhere obvious to go.
         *
         * If that tile is outside the hull the gas is now loose in vacuum, and the next fluid step
         * vents it through the rim and books it as vented. That needs no case of its own here, which
         * is the point of putting it in the room rather than inventing a second exit.
         */
        fun cutOpen(tile: Int) {
            val base = tile * Species.COUNT
            for (sp in Species.GASES) {
                airGrams[base + sp.ordinal] += pipeGrams[base + sp.ordinal]
                pipeGrams[base + sp.ordinal] = 0L
            }
            airJoules[tile] += pipeJoules[tile]
            pipeJoules[tile] = 0L
        }

        /** Where a machine instance currently sits — miners are charged heat by identity. */
        fun indexOf(machine: Machine): Int = machines.indexOfFirst { it === machine }

        fun apply(edit: Edit) {
            when (edit) {
                is Edit.Place -> {
                    if (edit.index !in machines.indices) return
                    when (edit.kind) {
                        // Every placement books the energy the new body brings with it: a tile of
                        // track is a tile of iron at room temperature, and that heat is arriving in
                        // the world rather than being conjured out of the ledger.
                        MachineKind.Rail, MachineKind.Pipe -> {
                            val c = edit.kind.conduit!!
                            if (layer(c)[edit.index] == null) {
                                layer(c)[edit.index] = Segment(c).also { built(it.joules) }
                            }
                        }
                        // Unlike a gauge, this upgrades a run that is already there rather than
                        // only laying a fresh tile. A valve is almost always something you add to
                        // plumbing you have already drawn, and refusing that would mean tearing up a
                        // tile of pipe to put a tap on it.
                        MachineKind.Valve -> {
                            val existing = layer(Conduit.Pipe)[edit.index]
                            layer(Conduit.Pipe)[edit.index] =
                                existing?.copy(valve = true)
                                    ?: Segment(Conduit.Pipe, valve = true).also { built(it.joules) }
                        }
                        MachineKind.Gauge -> if (rails[edit.index] == null) {
                            rails[edit.index] = Segment(Conduit.Rail, channel = Channel.Amber)
                                .also { built(it.joules) }
                        }
                        MachineKind.Bridge -> placeBridge(edit.index, edit.facing)
                        else -> placeBuilding(edit.index, edit.kind, edit.facing)
                    }
                }
                is Edit.Lay -> layConduit(edit.from, edit.to, edit.conduit)
                is Edit.Cut -> {
                    val dir = adjacency(edit.from, edit.to) ?: return
                    val line = layer(edit.conduit)
                    line[edit.from]?.let { line[edit.from] = it.cutFrom(dir) }
                    line[edit.to]?.let { line[edit.to] = it.cutFrom(dir.opposite) }
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
                    // Fittings come off first, then the building under them. Peeling the track off a
                    // smelter should not also demolish the smelter, and there is no other way to
                    // reach the track once it is threaded underneath.
                    val bridge = bridges[edit.index]
                    if (bridge != null) {
                        // Every slot: a bridge taken apart mid-span drops all three lumps, or the
                        // conservation invariant would read the dismantle as a leak.
                        if (bridge.carried.isNotEmpty()) {
                            debris.spill(edit.index, bridge.carried.map { asResource(it) })
                        }
                        bridges[edit.index] = null
                        scrapped(bridge.joules)
                        return
                    }
                    // One fitting per click, in layer order, for the same reason the track comes off
                    // before the smelter under it: a tile can hold several and removing them all at
                    // once would take away things the player could not see they were pointing at.
                    for (c in Conduit.entries) {
                        val line = layer(c)
                        val segment = line[edit.index] ?: continue
                        segment.held?.let { debris.spill(edit.index, listOf(asResource(it))) }
                        if (c == Conduit.Pipe) cutOpen(edit.index)
                        line[edit.index] = null
                        scrapped(segment.joules)
                        // Cut the far half of every join too. Leaving them would let a later tile of
                        // track laid here inherit connections the player never drew.
                        for (dir in Direction.ALL) {
                            val n = grid.neighbour(edit.index, dir)
                            if (n >= 0) line[n]?.let { line[n] = it.cutFrom(dir.opposite) }
                        }
                        return
                    }
                    // Clicking any tile of a machine removes the whole machine, not a slice of it.
                    val at = originAt(edit.index) ?: return
                    // Whatever it was holding falls on the deck. Deleting it instead would be a
                    // genuine leak, and the mass balance said so -- the answer is somewhere for the
                    // material to go, not an exemption for the player's own edits.
                    debris.spill(at, spoilsOf(machines[at]))
                    for (t in coveredTiles(grid, at, machines[at]!!.kind.size)) originOf[t] = -1
                    scrapped(machines[at]!!.joules)
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
                    // A gauge is track, so it is retuned before whatever is under it.
                    val gauge = rails[edit.index]
                    if (gauge != null && gauge.isGauge) {
                        rails[edit.index] = gauge.copy(channel = edit.channel)
                        return
                    }
                    val at = originAt(edit.index) ?: return
                    when (val m = machines[at]) {
                        is Sensor -> machines[at] = m.copy(channel = edit.channel)
                        else -> {}
                    }
                }
            }
        }

        /**
         * Puts a building on the deck. The click names its *centre*, and the footprint grows around
         * it.
         */
        private fun placeBuilding(at: Int, kind: MachineKind, facing: Direction) {
            val size = kind.size
            if (!footprintFits(grid, at, size)) return
            val covered = coveredTiles(grid, at, size)
            // Placing over anything occupied is a no-op, so a stray click cannot destroy a machine —
            // and everything inside it — by accident. With footprints that check has to cover every
            // tile, not just the one under the cursor.
            if (covered.any { originOf[it] >= 0 }) return
            val built = newMachine(kind, facing)
            if (portsClash(portsOf(grid, built, at))) return

            // Every deck machine is solid, so the air standing where it is about to be has to go
            // somewhere first. It is the last check because it is the only one that can fail on
            // account of the *air* rather than the geometry: air with nowhere to go means the build
            // is refused, which is the only answer that neither destroys it nor buries it.
            // Through `originOf` rather than `machines`, so the covered tiles of a footprint whose
            // centre is elsewhere count as solid too.
            if (!tryDisplaceAir(grid, airGrams, covered) { originOf[it] < 0 }) return

            machines[at] = built
            built(built.joules)
            for (t in covered) originOf[t] = at
        }

        /**
         * Puts a bridge down, stored at its middle tile.
         *
         * It occupies nothing, so there is no footprint to check — the **only** constraint is its
         * ports, and that is the constraint that gives bridges their shape: two of them cannot share
         * an end, and neither can a bridge end and a building's port, because a segment on that tile
         * would have no way to say which of the two it feeds.
         */
        private fun placeBridge(at: Int, facing: Direction) {
            if (bridges[at] != null) return
            val built = Bridge(facing)
            val ports = portsOf(grid, built, at)
            // Both ends have to be on the grid, or it is half a bridge.
            if (ports.size < 2) return
            if (portsClash(ports)) return
            bridges[at] = built
            built(built.joules)
        }

        /**
         * Whether any of [proposed] would land on a port of the same conduit that already exists.
         *
         * One rule, applied to buildings and bridges alike, and it does **not** care whether the two
         * are inputs or outputs: *any* two ports of one conduit on one tile clash. A bridge end over
         * a tank's input is exactly as ambiguous as a bridge end over another bridge's end — the
         * segment on that tile could not say which of the two it belongs to.
         *
         * Ports of *different* conduits may share a tile freely. A rail port and a pipe port are on
         * different networks, so there is nothing to be ambiguous about.
         */
        private fun portsClash(proposed: List<Port>): Boolean {
            val existing = portsByTile(Conduit.Rail)
            return proposed.any { p -> existing[p.tile].orEmpty().any { it.conduit == p.conduit } }
        }

        /** The direction from [from] to [to] when the two are neighbours, else null. */
        private fun adjacency(from: Int, to: Int): Direction? {
            if (from !in rails.indices || to !in rails.indices) return null
            return Direction.ALL.firstOrNull { grid.neighbour(from, it) == to }
        }

        /**
         * One step of a drag: track at both ends, joined.
         *
         * Both halves of the link are set here and nowhere else, which is what keeps connection
         * symmetric by construction rather than by a rule somebody has to remember. A gauge keeps its
         * channel — drawing a line through one connects it up without retuning it.
         */
        private fun layConduit(from: Int, to: Int, conduit: Conduit) {
            val dir = adjacency(from, to) ?: return
            // Each layer is its own grid, so a pipe drawn across a rail is a crossing rather than a
            // junction — and, now, rather than nothing at all. It used to share one list with the
            // track, find a conduit mismatch, and return having laid no pipe.
            val line = layer(conduit)
            val a = line[from] ?: Segment(conduit)
            val b = line[to] ?: Segment(conduit)
            line[from] = a.joinedTo(dir)
            line[to] = b.joinedTo(dir.opposite)
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
            val (grams, carry) = throttled(m.gramsPerTick, activation, m.carry)
            if (m.buffer.mass >= Miner.BUFFER_CAP) return m.copy(carry = carry)  // backed up: stop digging
            if (grams <= 0L) return m.copy(carry = carry)
            val dug = m.composition.scaledTo(grams)
            minedGrams += dug.total
            heat(indexOf(m), dug.total * heatPerGram(m))
            return m.copy(buffer = Resource(Form.Ore, m.buffer.mixture + dug), carry = carry)
        }

        /**
         * Every port on the layer, keyed by the tile it sits on.
         *
         * Bridges are folded in beside buildings rather than handled apart, which is exactly why a
         * bridge needs no special case: to the network it is a thing with an input port and an
         * output port, indistinguishable from a smelter with fewer buffers.
         */
        fun portsByTile(conduit: Conduit): Map<Int, List<Port>> {
            val out = HashMap<Int, MutableList<Port>>()
            fun add(port: Port) {
                if (port.conduit == conduit) out.getOrPut(port.tile) { mutableListOf() }.add(port)
            }
            for (i in machines.indices) {
                val m = machines[i] ?: continue
                for (port in portsOf(grid, m, i)) add(port)
            }
            for (i in bridges.indices) {
                val b = bridges[i] ?: continue
                for (port in portsOf(grid, b, i)) add(port)
            }
            return out
        }

        /**
         * Puts a packet from an output port onto the track **at the port's own tile**.
         *
         * This is what "ports behind the buildings" means in one line. Material does not cross into a
         * neighbouring tile; the conduit is threaded underneath the building and the building reaches
         * down into it. A machine with no track under its output port simply backs up, however much
         * conveyor is butted against its outside.
         *
         * Where the tile already holds a partial packet the building **tops it up** rather than
         * waiting for it to leave — but only where the two can genuinely combine, which for solids
         * means powder. A full packet blocks.
         */
        fun pushOut(tile: Int, port: Port) {
            val segment = rails[tile] ?: return
            // Bridges are not ejected from here. They are conduit, so they set their load down as
            // part of the conduit step -- see [depositFromBridge].
            if (port.fromBridge) return

            val m = machines[port.owner] ?: return
            // A storage only lets go while its RUN activation is positive, which is what turns it
            // from a bucket into a valve the moment you wire something to it.
            if (m is Storage && m.wiring.activation(Action.Run, signals) <= 0) return

            // Only as much as will actually fit: an empty tile takes a whole packet, a partial one
            // takes what tops it up.
            val room = segment.held?.let { Capacity.headroom(it) } ?: Capacity.PACKET_GRAMS
            val buffer = bufferFor(m, port) ?: return
            val (packet, rest) = takePacket(buffer, room) ?: return
            val wasEmpty = segment.held == null
            if (!load(tile, segment, packet)) return
            machines[port.owner] = withBuffer(m, port, rest.orNull())
            // Only an empty tile counts as an appearance. Topping up a lump already standing there
            // is a change of mass, which draws itself from the mass the tile started the tick with.
            if (wasEmpty) motion.placedByPort(tile)
        }

        /**
         * A bridge putting its exit slot down on the track at its far end.
         *
         * **Records no motion, because nothing moved.** A bridge's ports sit at ±1 from its centre,
         * which is exactly where its entry and exit slots are drawn, so setting down is a change of
         * *layer* at one tile rather than a change of place. Marking it as an arrival would draw the
         * packet growing in on top of the one already sitting there; marking it as a departure would
         * draw a ghost shrinking away from a packet that has not gone anywhere. Left silent, the
         * lump simply continues — off the bridge and along the track in one unbroken slide.
         */
        private fun depositFromBridge(tile: Int, port: Port) {
            val segment = rails[tile] ?: return
            val bridge = bridges[port.owner] ?: return
            val held = bridge.exit ?: return
            if (!load(tile, segment, held)) return
            bridges[port.owner] = bridge.copy(exit = null)
        }

        /** Which of a machine's buffers drains through [port]. */
        private fun bufferFor(m: Machine, port: Port): Resource? = when (m) {
            is Miner -> m.buffer
            is Processor -> if (port.stream == Stream.Waste) m.tailings else m.product
            is Smelter -> if (port.stream == Stream.Waste) m.slag else m.refined
            is Storage -> m.contents
            else -> null
        }

        /** That machine with the drained buffer replaced. */
        private fun withBuffer(m: Machine, port: Port, rest: Resource?): Machine = when (m) {
            is Miner -> m.copy(buffer = rest ?: Resource(Form.Ore, Mixture.EMPTY))
            is Processor -> if (port.stream == Stream.Waste) m.copy(tailings = rest) else m.copy(product = rest)
            is Smelter -> if (port.stream == Stream.Waste) m.copy(slag = rest) else m.copy(refined = rest)
            is Storage -> m.copy(contents = rest)
            else -> m
        }

        /** Places or merges [packet] onto the segment at [tile]. False if there was no room. */
        private fun load(tile: Int, segment: Segment, packet: Packet): Boolean {
            val existing = segment.held
            if (existing == null) {
                rails[tile] = segment.copy(held = packet).reading(packet)
                return true
            }
            val merged = squashOnto(existing, packet) ?: return false
            rails[tile] = segment.copy(held = merged.merged).reading(merged.merged)
            return merged.rejected == null
        }

        /**
         * Moves everything on one conduit one step.
         *
         * The flow field is derived fresh: sinks are the **input** ports that actually have a track
         * under them, so connecting or cutting one tile re-decides which way a whole run points,
         * with no cache to invalidate.
         */
        fun advanceRails(ports: Map<Int, List<Port>>) {
            // A bridge sets down what it has been carrying *first*, before anything shifts.
            //
            // This is the whole of why a bridge's three slots are three real slots. Draining the
            // exit at the end of the step instead — which is where machine ejection happens, and
            // where this used to live — leaves the slot occupied when the shift runs, so the packet
            // behind it cannot advance and the bridge delivers once every two steps with a slot
            // standing idle between. Draining first frees the slot the shift is about to want.
            for ((tile, at) in ports) for (port in at) {
                if (port.kind == PortKind.Output && port.fromBridge) depositFromBridge(tile, port)
            }

            // A bridge is three tiles of the layer, so it steps when the layer steps — and *before*
            // the track does, so the slot a packet vacates is free for the one behind it in the same
            // step. Exactly the reason the track itself is walked most-downstream first.
            for (i in bridges.indices) {
                val b = bridges[i] ?: continue
                if (b.conduit != Conduit.Rail) continue
                val after = b.advanced()
                bridges[i] = after
                // A slot that was empty and is not now took delivery from the slot behind it, which
                // is the only way a bridge slot is ever filled from the inside.
                if (b.exit == null && after.exit != null) motion.bridgeSlotFilled(i, Motion.SLOT_EXIT)
                if (b.middle == null && after.middle != null) motion.bridgeSlotFilled(i, Motion.SLOT_MIDDLE)
            }

            // Split by whether the consumer can take anything at all. A full one still pulls, but
            // only once no accepting one is reachable — so traffic runs *past* a full machine to
            // reach a working one, and backs up against it when there is nowhere else to be.
            val inputs = ports.entries
                .filter { (tile, at) -> rails[tile] != null && at.any { it.kind == PortKind.Input } }
                .map { it.key }
            val (accepting, full) = inputs.partition { tile ->
                ports[tile].orEmpty().any { it.kind == PortKind.Input && hasRoom(it) }
            }
            // Where material enters the layer. A bridge's far end counts, which is what gives the
            // run on the other side of a crossing a direction of its own.
            val sources = ports.entries
                .filter { (tile, at) -> rails[tile] != null && at.any { it.kind == PortKind.Output } }
                .map { it.key }
            val flow = FlowField.derive(
                grid,
                { rails[it] != null },
                { tile, dir -> rails[tile]?.linkedTo(dir) == true },
                accepting,
                full,
                sources,
            )

            val carried = arrayOfNulls<Packet>(rails.size)
            for (i in rails.indices) carried[i] = rails[i]?.held

            advanceSegments(flow, carried, diverters, motion) { tile, packet ->
                var left: Packet? = packet
                for (port in ports[tile].orEmpty()) {
                    if (port.kind != PortKind.Input) continue
                    val remaining = left ?: break
                    left = offerTo(port, remaining)
                    if (left == null && port.fromBridge) motion.handedToBridge(tile)
                }
                left
            }

            for (i in rails.indices) {
                val segment = rails[i] ?: continue
                val now = carried[i]
                if (now !== segment.held) {
                    rails[i] = if (now == null) segment.copy(held = null) else segment.copy(held = now).reading(now)
                }
            }
        }

        /**
         * Whether the thing behind an input port could take **anything** at all right now.
         *
         * This is what makes a full machine transparent to the traffic rather than a wall across it:
         * an input with no room does not pull, so the flow field routes past it to the next consumer
         * that does. Deliberately asked without reference to a particular packet — the field is
         * derived once for the whole layer, before any packet is looked at, and a port that has room
         * but refuses *this* form is handled by the other half of the fix (a tile at a sink still
         * has successors).
         */
        private fun hasRoom(port: Port): Boolean {
            if (port.fromBridge) return bridges[port.owner]?.entry == null
            return when (val m = machines[port.owner]) {
                is Processor -> (m.input?.mass ?: 0L) < MACHINE_BUFFER_CAP
                is Smelter -> (m.input?.mass ?: 0L) < MACHINE_BUFFER_CAP
                is Storage -> (m.contents?.mass ?: 0L) < Storage.CAP
                // A vent is a hole in the hull; it never fills up.
                is Vent -> true
                else -> false
            }
        }

        /** Offers a passing packet to whatever owns [port]. Returns what was not taken. */
        private fun offerTo(port: Port, packet: Packet): Packet? {
            if (port.fromBridge) {
                val bridge = bridges[port.owner] ?: return packet
                if (bridge.entry != null) return packet
                bridges[port.owner] = bridge.copy(entry = packet)
                return null
            }
            val dest = machines[port.owner] ?: return packet
            return if (deliver(port.owner, dest, packet)) null else packet
        }

        /**
         * Only whole packets leave a buffer, so track carries uniform lumps and a trickle of output
         * does not spray the network with crumbs. [limit] caps it to the room actually available.
         */
        private fun takePacket(buffer: Resource, limit: Long = Capacity.PACKET_GRAMS): Pair<SolidPacket, Resource>? {
            val want = minOf(Capacity.PACKET_GRAMS, limit)
            if (want <= 0L || buffer.mass < want) return null
            val taken = buffer.mixture.take(want)
            return SolidPacket(Resource(buffer.form, taken)) to Resource(buffer.form, buffer.mixture - taken)
        }

        private fun Resource.orNull(): Resource? = if (isEmpty) null else this

        /** Puts [packet] into the accepting machine's own buffers, or refuses it. */
        private fun deliver(target: Int, destination: Machine, packet: Packet): Boolean {
            return when (destination) {
                is Processor -> acceptInto(destination.input, packet)?.let { machines[target] =
                    destination.copy(input = it); true } ?: false
                is Smelter -> acceptInto(destination.input, packet)?.let { machines[target] =
                    destination.copy(input = it); true } ?: false
                is Storage -> {
                    if (packet !is SolidPacket) false
                    else {
                        val existing = destination.contents
                        if (existing != null && existing.form != packet.form) false
                        else if ((existing?.mass ?: 0L) >= Storage.CAP) false
                        else {
                            val merged = if (existing == null) packet.resource
                            else Resource(existing.form, existing.mixture + packet.contents)
                            machines[target] = destination.copy(contents = merged)
                            true
                        }
                    }
                }
                is Vent -> {
                    ventedGrams += packet.mass
                    machines[target] = destination.copy(ventedGrams = destination.ventedGrams + packet.mass)
                    true
                }
                // Miners take no input, and a wall is not a hopper.
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

        /** What a packet becomes when it is tipped onto the deck. */
        private fun asResource(packet: Packet): Resource =
            Resource((packet as? SolidPacket)?.form ?: Form.Ore, packet.contents)

        private fun newMachine(kind: MachineKind, facing: Direction): Machine = when (kind) {
            MachineKind.Miner -> Miner(facing, DEFAULT_ORE_BODY)
            MachineKind.Processor -> Processor(facing)
            MachineKind.Smelter -> Smelter(facing)
            MachineKind.Storage -> Storage(facing)
            MachineKind.Sensor -> Sensor(facing)
            MachineKind.Vent -> Vent()
            MachineKind.Pump -> Pump(facing)
            MachineKind.Hull -> Hull()
            // Fittings are placed onto their layer directly and never come through here.
            MachineKind.Rail, MachineKind.Pipe, MachineKind.Gauge, MachineKind.Valve, MachineKind.Bridge -> Hull()
        }
    }
}
