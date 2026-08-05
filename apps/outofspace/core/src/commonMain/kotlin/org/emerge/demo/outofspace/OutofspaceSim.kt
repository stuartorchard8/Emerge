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
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Rock
import org.emerge.demo.outofspace.world.driftRocks
import org.emerge.demo.outofspace.world.frameAcceleration
import org.emerge.demo.outofspace.world.experiencedGravity
import org.emerge.demo.outofspace.world.fullness
import org.emerge.demo.outofspace.world.settleDebris
import org.emerge.demo.outofspace.world.vesselMassGrams
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

/** One tick: edits → sense → produce → process → eject → settle debris → advance conduits → fluid → heat → motion.
 * Machines throttled by RUN activation (rate × activation). Row-major walk order for determinism.
 * Belt delivery: one step per advance (not instant), so jams crawl backwards visually. */
object OutofspaceReducer : SimReducer<OutofspaceConfig, VesselState, OutofspaceInput> {

    override fun reduce(
        cfg: OutofspaceConfig,
        state: VesselState,
        inputs: Map<PlayerId, OutofspaceInput>,
    ): VesselState {
        val w = Work(state)

        // Sorted by PlayerId for determinism across peers.
        for ((_, input) in inputs.entries.sortedBy { it.key.value }) {
            for (edit in input.edits) w.apply(edit)
        }

        // Structure first: edits this tick must be reflected.
        val structure = StructureMap.derive(w.grid, w.machines)
        val occupancy = Occupancy(w.originOf.copyOf())

        val signals = Signals.build { raise ->
            for (i in w.machines.indices) {
                when (val m = w.machines[i]) {
                    is Sensor -> {
                        val target = w.grid.neighbour(i, m.facing)
                        val seen = if (target < 0) -1 else w.originOf[target]
                        if (seen >= 0) raise(m.channel, fullness(w.machines[seen]))
                    }
                    else -> {}
                }
            }
            // Gauge persists after packet leaves.
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

        // Rails first: produced output can go on the track after it moves.
        val ports = w.portsByTile(Conduit.Rail)
        if (state.tick % Bridge.STEP_TICKS == 0L) w.advanceRails(ports)

        for ((tile, at) in ports) for (port in at) {
            if (port.kind == PortKind.Output) w.pushOut(tile, port)
        }

        // Felt gravity: plating + engine impulse from previous tick (fluid solved under this gravity). See [experiencedGravity].
        val felt = experiencedGravity(state.gravity, state.netImpulseX, state.netImpulseY, state.massGrams)

        // After edits: dropped material falls this tick.
        w.ventedGrams += settleDebris(state.grid, structure, w.debris, felt)

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

        // Valves first: pressure propagates immediately, both layers see exchange (see [exchangeLayers]).
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

        // Pumps alongside valves, before either layer solved (see [applyPumps]).
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

        // On airGrams (edited by [displaceAir]).
        val fluid = stepFluid(
            edges, roomApertures, w.airGrams, w.momentumX, w.momentumY, felt, w.airJoules,
        )

        // Pipes: same solver, connectivity from player-drawn layout.
        val pipes = stepFluid(
            edges,
            plumbing,
            w.pipeGrams,
            w.pipeMomentumX,
            w.pipeMomentumY,
            felt,
            w.pipeJoules,
            volumes,
        )
        // Pipes cannot vent to rim (ledger check).
        require(pipes.ventedGrams == 0L && pipes.ventedJoules == 0L) {
            "a sealed pipe network vented ${pipes.ventedGrams}g — a rim face was open"
        }

        // ── Flight ────────────────────────────────────────────────────────────
        val machines = w.machines.toList()
        val bridges = w.bridges.toList()
        val debris = w.debris.snapshot()
        val mass = vesselMassGrams(machines, conduits, bridges, debris)

        // Debug thrust: acceleration × mass (see [Edit.Thrust]).
        val thrustX = w.thrustDx.coerceIn(-1, 1) * mass * Edit.DEBUG_THRUST_MILLI_G / 1000L
        val thrustY = w.thrustDy.coerceIn(-1, 1) * mass * Edit.DEBUG_THRUST_MILLI_G / 1000L

        // Rocks fly here, and not up beside the debris, because this is where the ship's own motion
        // is known — and a rock's motion is now stated against the *world* while its position is
        // stated on the *grid*, so drifting one needs the velocity of the grid itself. See [Rock].
        //
        // `state.velocityX` is the start-of-tick velocity, which is exactly what the ship's own
        // position is advanced by below: the grid slides by the same amount for the rock as it does
        // for the hull, because it is the same grid.
        //
        // It is also where a rock can hit something, because a contact is an exchange and the ship's
        // half of it has to join `netImpulse` below in the same tick the rock's half is booked. The
        // acceleration is passed for the resting threshold and is not a force — see [driftRocks].
        val rocksDrifted = driftRocks(
            state.grid,
            structure,
            w.rocks,
            state.gravity,
            state.velocityX,
            state.velocityY,
            mass,
            frameAcceleration(state.netImpulseX, state.netImpulseY, state.massGrams),
        )

        // Vessel pays for rock momentum here: `−J` for the `+J` the rock got (conserved by construction).
        val netImpulseX = fluid.vesselX + pipes.vesselX + crossed.vesselX + pumped.vesselX + thrustX -
            rocksDrifted.handedX
        val netImpulseY = fluid.vesselY + pipes.vesselY + crossed.vesselY + pumped.vesselY + thrustY -
            rocksDrifted.handedY

        return state.copy(
            machines = machines,
            conduits = conduits,
            bridges = bridges,
            diverters = w.diverters.snapshot(),
            tick = state.tick + 1,
            minedGrams = w.minedGrams,
            debris = debris,
            ventedGrams = w.ventedGrams,
            signals = signals,
            structure = structure,
            occupancy = occupancy,
            generatedJoules = w.generatedJoules,
            radiatedJoules = state.radiatedJoules + conducted.radiated,
            constructionJoules = w.constructionJoules,
            // Solid→air energy (see [SolidHeatStep]).
            solidToAirJoules = state.solidToAirJoules + conducted.toAir,
            air = fluid.air,
            pipeAir = pipes.air,
            pipeMomentum = MomentumField.of(edges, pipes.momentumX, pipes.momentumY),
            airVentedGrams = state.airVentedGrams + fluid.ventedGrams,
            // Separate from radiatedJoules: cleaner ledger.
            airVentedJoules = state.airVentedJoules + fluid.ventedJoules,
            momentum = MomentumField.of(edges, fluid.momentumX, fluid.momentumY),
            // Pipe pressure + pump momentum all push the ship (see [exchangeLayers], [applyPumps]).
            vesselImpulseX = state.vesselImpulseX + netImpulseX,
            vesselImpulseY = state.vesselImpulseY + netImpulseY,
            netImpulseX = netImpulseX,
            netImpulseY = netImpulseY,
            // Explicit integration: move by velocity at tick start.
            positionX = state.positionX + state.velocityX,
            positionY = state.positionY + state.velocityY,
            exhaustMomentumX = state.exhaustMomentumX + fluid.escapedX,
            exhaustMomentumY = state.exhaustMomentumY + fluid.escapedY,
            // Undelivered impulse from both fluid layers.
            undeliveredImpulseX = state.undeliveredImpulseX + fluid.undeliveredX + pipes.undeliveredX,
            undeliveredImpulseY = state.undeliveredImpulseY + fluid.undeliveredY + pipes.undeliveredY,
            // Debug engine (non-physics, booked alongside thrust).
            debugImpulseX = state.debugImpulseX + thrustX,
            debugImpulseY = state.debugImpulseY + thrustY,
            rocks = rocksDrifted.rocks,
            rockImpulseX = state.rockImpulseX + rocksDrifted.handedX,
            rockImpulseY = state.rockImpulseY + rocksDrifted.handedY,
            capturedGrams = w.capturedGrams,
            motion = w.motion.freeze(),
        )
    }

    /** Full snapshots on the wire; a demo sending partial state would merge here instead. */
    override fun patchState(state: VesselState, delta: VesselState): VesselState = delta

    /** Pump demands: room→pipe. Both ends optional (missing pump excluded). Activation applied here, not in [applyPumps]. */
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

    /** Grams this tick: rate × activation, with carry-over via [Rate]. */
    private fun throttled(gramsPerTick: Long, activation: Int, carry: Long): Pair<Long, Long> =
        if (activation <= 0) 0L to carry
        else Rate.tick(gramsPerTick * activation, Signals.FULL, carry)

    private fun Work.refine(cfg: OutofspaceConfig, m: Processor, activation: Int, at: Int): Processor {
        val input = m.input ?: return m
        val (grams, carry) = throttled(m.gramsPerTick, activation, m.carry)
        // Full output blocks the machine (catches tailings too).
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
        // Smelter stalls if dominant species differs (stopped machine signals ore change).
        val refined = m.refined.merged(r.refined) ?: return m.copy(carry = carry)
        val slag = m.slag.merged(r.slag) ?: return m.copy(carry = carry)

        return m.copy(
            input = Resource(input.form, input.mixture - chunk.mixture).orNull(),
            refined = refined.buffer,
            slag = slag.buffer,
            carry = carry,
        )
    }

/** Buffer merge: new contents, or null if forms cannot coexist. Null vs Merge(null) distinguishes "empty" from "cannot mix". */
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

    /** Mutable scratch for one tick (reducer stays pure). */
    private class Work(state: VesselState) {
        val grid: Grid = state.grid
        val machines: MutableList<Machine?> = state.machines.toMutableList()
        var minedGrams: Long = state.minedGrams
        val debris: DebrisWork = DebrisWork(state.debris)
        // Editable conduit layers (array of lists avoids per-tile Conduits rebuild).
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

        // Debug engine direction, summed + clamped (see [Edit.Thrust]).
        var thrustDx: Int = 0
        var thrustDy: Int = 0

        /** Free-floating rock, and the running admission of how much of it came from outside. */
        val rocks: MutableList<Rock> = state.rocks.toMutableList()
        var capturedGrams: Long = state.capturedGrams

        // Mutable: edit pass moves air before fluid pass runs.
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

        // Motion log for renderer, built from pre-tick rail state.
        val motion: MotionLog = MotionLog(state.rails)


        // tile → machine index (maintained incrementally for O(n)).
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

        /** Apply conduction results back to machines/segments/bridges. */
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

        // Cut pipe: release gas+heat into room (not deleted — shared ledger).
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
                        // Book energy for new body (heat arriving, not conjured).
                        MachineKind.Rail, MachineKind.Pipe -> {
                            val c = edit.kind.conduit!!
                            if (layer(c)[edit.index] == null) {
                                layer(c)[edit.index] = Segment(c).also { built(it.joules) }
                            }
                        }
                        // Valve: upgrade existing pipe or lay new.
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
                    // Rotation: footprints square, so covered tiles unchanged — only ports move.
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
                        // Cut far halves of joins (prevent phantom connections).
                        for (dir in Direction.ALL) {
                            val n = grid.neighbour(edit.index, dir)
                            if (n >= 0) line[n]?.let { line[n] = it.cutFrom(dir.opposite) }
                        }
                        return
                    }
                    // Whole machine removed (not a slice). Holding drops to deck.
                    val at = originAt(edit.index) ?: return
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
                // Accumulated (mass finalised after edit pass).
                is Edit.Thrust -> { thrustDx += edit.dx; thrustDy += edit.dy }
                is Edit.DropRock -> dropRock(edit.index, edit.radius)
            }
        }

        /** Drop a rock at [at] (capture placeholder). Mass → capturedGrams, energy → built. */
        private fun dropRock(at: Int, radius: Int) {
            if (at !in machines.indices) return
            // The click names the centre tile, and the rock's position is its top-left corner, so
            // the half-width comes off. Half a tile more puts the centre of the disc on the centre
            // of the tile rather than on its corner.
            val half = radius * Flight.PER_TILE
            val rock = Rock.blob(
                radius = radius,
                positionX = (grid.xOf(at) * Flight.PER_TILE) - half + Flight.PER_TILE / 2L,
                positionY = (grid.yOf(at) * Flight.PER_TILE) - half + Flight.PER_TILE / 2L,
                composition = DEFAULT_ORE_BODY,
            )
            rocks.add(rock)
            capturedGrams += rock.massGrams
            built(rock.joules)
        }

        /** Place building (click names centre, footprint grows around it). */
        private fun placeBuilding(at: Int, kind: MachineKind, facing: Direction) {
            val size = kind.size
            if (!footprintFits(grid, at, size)) return
            val covered = coveredTiles(grid, at, size)
            // Over anything occupied = no-op (footprint check, not just cursor tile).
            if (covered.any { originOf[it] >= 0 }) return
            val built = newMachine(kind, facing)
            if (portsClash(portsOf(grid, built, at))) return

            // Every deck machine is solid — air must have somewhere to go. Last check (air, not geometry).
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

        /** Any two ports of the same conduit on one tile clash. */
        private fun portsClash(proposed: List<Port>): Boolean {
            val existing = portsByTile(Conduit.Rail)
            return proposed.any { p -> existing[p.tile].orEmpty().any { it.conduit == p.conduit } }
        }

        /** The direction from [from] to [to] when the two are neighbours, else null. */
        private fun adjacency(from: Int, to: Int): Direction? {
            if (from !in rails.indices || to !in rails.indices) return null
            return Direction.ALL.firstOrNull { grid.neighbour(from, it) == to }
        }

        /** Draw a conduit line (both halves linked symmetrically; gauges keep channel). */
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

        /** Mine (minedGrams incremented here — moment matter enters the world). */
        fun produce(cfg: OutofspaceConfig, m: Miner, activation: Int): Miner {
            val (grams, carry) = throttled(m.gramsPerTick, activation, m.carry)
            if (m.buffer.mass >= Miner.BUFFER_CAP) return m.copy(carry = carry)  // backed up: stop digging
            if (grams <= 0L) return m.copy(carry = carry)
            val dug = m.composition.scaledTo(grams)
            minedGrams += dug.total
            heat(indexOf(m), dug.total * heatPerGram(m))
            return m.copy(buffer = Resource(Form.Ore, m.buffer.mixture + dug), carry = carry)
        }

        /** Ports by tile (bridges folded in — indistinguishable from buildings with ports). */
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

        /** Place output packet at port tile (ports behind buildings). Tops up partial packets where possible. */
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

        /** Advance all conduits one step (flow derived from input ports). */
        fun advanceRails(ports: Map<Int, List<Port>>) {
            // Bridges drain first (three real slots, not one).
            for ((tile, at) in ports) for (port in at) {
                if (port.kind == PortKind.Output && port.fromBridge) depositFromBridge(tile, port)
            }

            // Bridge steps with layer (slots freed for track).
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

            // Split: accepting vs full consumers (traffic runs past full to working).
            val inputs = ports.entries
                .filter { (tile, at) -> rails[tile] != null && at.any { it.kind == PortKind.Input } }
                .map { it.key }
            val (accepting, full) = inputs.partition { tile ->
                ports[tile].orEmpty().any { it.kind == PortKind.Input && hasRoom(it) }
            }
            // Sources (bridge far end gives crossing run its own direction).
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

        /** Take whole packets (limit caps to available room). */
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
                // Miners take no input.
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
            // Fittings placed directly on layers.
            MachineKind.Rail, MachineKind.Pipe, MachineKind.Gauge, MachineKind.Valve, MachineKind.Bridge -> Hull()
        }
    }
}
