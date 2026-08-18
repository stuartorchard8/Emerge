package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.cook
import org.emerge.demo.outofspace.chem.process
import org.emerge.demo.outofspace.chem.smelt
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.Rate
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.inputBufferRole
import org.emerge.demo.outofspace.world.outputBufferRole
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.FlowCursors
import org.emerge.demo.outofspace.world.FlowGraph
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.advanceSegments
import org.emerge.demo.outofspace.world.squashOnto
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.machine.Directed
import org.emerge.demo.outofspace.world.machine.DirectedDeckMachine
import org.emerge.demo.outofspace.world.fitToFrame
import org.emerge.demo.outofspace.world.growToFit
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Occupancy
import org.emerge.demo.outofspace.world.Port
import org.emerge.demo.outofspace.world.PortKind
import org.emerge.demo.outofspace.world.Stream
import org.emerge.demo.outofspace.world.coveredTiles
import org.emerge.demo.outofspace.world.tryDisplaceAir
import org.emerge.demo.outofspace.world.footprintFits
import org.emerge.demo.outofspace.world.portsOf
import org.emerge.demo.outofspace.world.diameter
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Body
import org.emerge.demo.outofspace.world.bodiesOf
import org.emerge.demo.outofspace.world.BodySlot
import org.emerge.demo.outofspace.world.machine.Airlock
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.MACHINE_BUFFER_CAP
import org.emerge.demo.outofspace.world.machine.MACHINE_OUTPUT_CAP
import org.emerge.demo.outofspace.world.machine.Machine
import org.emerge.demo.outofspace.world.machine.Placed
import org.emerge.demo.outofspace.world.Motion
import org.emerge.demo.outofspace.world.MotionLog
import org.emerge.demo.outofspace.world.machine.MachineKind
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.biteCell
import org.emerge.demo.outofspace.world.reach
import org.emerge.demo.outofspace.world.machine.reachableCell
import org.emerge.demo.outofspace.world.machine.Processor
import org.emerge.demo.outofspace.world.machine.Pump
import org.emerge.demo.outofspace.world.machine.InputKey
import org.emerge.demo.outofspace.world.machine.WireButton
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.SignalNetworks
import org.emerge.demo.outofspace.world.machine.Smelter
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.machine.Vent
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.MassDistribution
import org.emerge.demo.outofspace.world.angularVelocity
import org.emerge.demo.outofspace.world.Pose
import org.emerge.demo.outofspace.world.Rotation
import org.emerge.demo.outofspace.world.ShipMotion
import org.emerge.demo.outofspace.world.driftBodies
import org.emerge.demo.outofspace.world.massDistribution
import org.emerge.demo.outofspace.world.tileCentre
import org.emerge.demo.outofspace.world.torqueAbout
import org.emerge.demo.outofspace.world.fullness
import org.emerge.demo.outofspace.world.vesselMass
import org.emerge.demo.outofspace.world.heatOfWorking
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.TrackLayers
import org.emerge.demo.outofspace.world.machine.Vaporizer
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.exhaustPath
import org.emerge.demo.outofspace.world.EdgeGrid
import org.emerge.demo.outofspace.world.heatCapacityAt
import org.emerge.demo.outofspace.world.MomentumField
import org.emerge.demo.outofspace.world.ApertureField
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.world.PumpDemand
import org.emerge.demo.outofspace.world.TileArray
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.airlockOpenness
import org.emerge.demo.outofspace.world.applyPumps
import org.emerge.demo.outofspace.world.exchangeLayers
import org.emerge.demo.outofspace.world.pipeApertures
import org.emerge.demo.outofspace.world.pipeVolumes
import org.emerge.demo.outofspace.world.applyPressureForce
import org.emerge.demo.outofspace.world.diffuseFluid
import org.emerge.demo.outofspace.world.gasKelvin
import org.emerge.demo.outofspace.world.tileMass
import org.emerge.demo.outofspace.world.tilePressure
import org.emerge.demo.outofspace.world.valveOpenings
import org.emerge.demo.outofspace.world.stepSolidHeat
import org.emerge.demo.outofspace.world.heatCapacity
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.ThermalDecomposer
import org.emerge.demo.outofspace.world.size
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.SimReducer

/** One tick: edits → sense → produce → process → eject → advance conduits → fluid → heat → motion.
 *
 * Subsystems run at different frequencies determined by their period (power of 2).
 * A subsystem with period P runs every P ticks. No rate scaling — each subsystem moves its full
 * amount when it fires.
 *
 * Row-major walk order for determinism. Belt delivery: one step per advance (not instant),
 * so jams crawl backwards visually. */
object OutofspaceReducer : SimReducer<OutofspaceConfig, VesselState, OutofspaceInput> {

    /** Periods (ticks between activations). All must divide [OutofspaceConfig.ticksPerSecond] evenly. */
    const val FLUID_PERIOD      = 8
    const val HEAT_PERIOD       = 8
    const val PUMP_PERIOD       = 8
    const val MACHINE_PERIOD    = 1
    const val RAIL_PERIOD       = 32

    /** Runs on tick 0 (all periods divide 0). */
    private fun shouldRun(tick: Long, period: Int): Boolean = tick % period == 0L

    override fun reduce(
        cfg: OutofspaceConfig,
        state: VesselState,
        inputs: Map<PlayerId, OutofspaceInput>,
    ): VesselState {
        val w = Work(state)

        // Sorted by PlayerId for determinism across peers.
        var heldKeys = 0
        for ((_, input) in inputs.entries.sortedBy { it.key.value }) {
            for (edit in input.edits) w.apply(edit)
            // Or-ed rather than taken from one player: two people at two seats are both flying, and
            // "anybody is holding it" is the same rule max-wins already applies on the wire.
            heldKeys = heldKeys or input.heldKeys
        }

        val occupancy = Occupancy(w.originOf.copyOf())

        // ── Machines ─────────────────────────────────────────────────────────────
        // Signals/networks/structure/openness only change when machines change, so
        // we compute them here and reuse between machine ticks (where nothing else
        // modifies them).
        var networks = SignalNetworks.none(w.grid.size)
        var signals = SignalField.none(w.grid.size)
        var openness = IntArray(w.machines.size)
        var structure = StructureMap.derive(w.grid, w.machines, w.deck, openness)
        if (shouldRun(state.tick, MACHINE_PERIOD)) {
            // Signals derived from network + machine fullness + player keys. Only
            // machines read signals, so we compute them here alongside structure.
            networks = SignalNetworks.derive(w.grid, w.conduitsSnapshot())
            w.networks = networks
            signals = SignalField.build(networks) { raise ->
                // The transmitters are deck machines, so this walks the deck. ⚠️ It used to walk
                // `machines`, and a reified lookup that resolves the wrong hierarchy answers *null*
                // — every sensor and every button would simply have stopped emitting, with nothing
                // to see but wiring that no longer does anything.
                for (tile in w.grid.tiles) {
                    when (val m : DeckMachine? = w.deck[tile]) {
                        is Sensor -> {
                            val target = w.grid.neighbour(tile, m.facing)
                            val seen = if (target == TileIndex.NONE) TileIndex.NONE else w.originOf[target]
                            // Either list: a sensor looks at whatever building is there, and a
                            // warehouse — the thing sensors are most often pointed at — stands on
                            // the deck. `w[seen]` cannot answer this, since its reified lookup
                            // resolves one hierarchy or the other and would read a tank as absent.
                            if (seen != TileIndex.NONE) {
                                val target: Placed? = w.machines.getOrNull(seen.index) ?: w.deck[seen]
                                raise(tile, fullness(target, seen, w.grid, w.buffers))
                            }
                        }
                        // A finger on a key, on the same footing as any other transmitter.
                        is WireButton -> if (InputKey.heldIn(heldKeys, m.key)) raise(tile, SignalField.FULL)
                        else -> {}
                    }
                }
                // Gauge persists after packet leaves.
                for (tile in w.grid.tiles) {
                    val r = w.rails[tile.index] ?: continue
                    if (r.isGauge) raise(tile, (r.lastMass * SignalField.FULL / Capacity.PACKET_MASS).toInt())
                }
            }
            w.signals = signals

            // Signals before structure: an airlock is a wall whose solidity is a signal.
            // Edits this tick are already applied in w, so sensors/gauges still see them.
            openness = airlockOpenness(w.deck, signals) ?: IntArray(w.machines.size)
            structure = StructureMap.derive(w.grid, w.machines, w.deck, openness)

            for (tile in w.grid.tiles) {
                val m : Machine = w[tile] ?: continue
                val activation = m.wiring.activation(Action.Run, signals.at(tile))
                w[tile] = when (m) {
                    is Bridge -> m
                }
            }
            for (tile in w.grid.tiles) {
                val m : DeckMachine = w[tile] ?: continue
                val activation = m.wiring.activation(Action.Run, signals.at(tile))
                w[tile] = when (m) {
                    is Hull, is Airlock, is Vent, is Storage,
                    is Sensor, is WireButton, is Pump -> m
                    is Vaporizer -> w.vaporize(m, activation, tile)
                    is Thruster -> w.fire(cfg, m, activation, tile, structure)
                    is Processor -> w.refine(cfg, m, activation, tile)
                    is ThermalDecomposer -> w.refine(cfg, m, activation, tile)
                    is Smelter -> w.melt(cfg, m, activation, tile)
                    is Extractor -> w.leech(m, activation, tile)
                }
            }
        }

        val motion: Motion
        if (shouldRun(state.tick, RAIL_PERIOD)) {
            // Rails first: produced output can go on the track after it moves.
            val ports = w.portsByTile(Conduit.Rail)
            w.advanceRails(ports)

            for ((tile, at) in ports) for (port in at) {
                if (port.kind == PortKind.Output) w.pushOut(tile, port)
            }
            w.readGauges()
            motion = w.motion.freeze()
        } else {
            // Same motion is still in progress from last time.
            motion = state.motion
        }

        // ── Heat ──────────────────────────────────────────────────────────────────
        // When skipped, heat state is carried forward from the previous tick.
        var conductedRadiated = 0L
        var conductedToAir = 0L
        if (shouldRun(state.tick, HEAT_PERIOD)) {
            // Waste heat lands in the machine that did the work — see [heatPerGram] — so it
            // has to conduct out through the casing before the room feels it.
            for (tile in w.grid.tiles) {
                val added = w.heatAdded[tile.index]
                if (added == 0L) continue
                val m : Machine? = w[tile]
                if (m != null) {
                    w[tile] = m.withEnergy(m.energy.plusEnergySpread(added))
                } else {
                    val dm : DeckMachine? = w[tile]
                    dm?.addEnergySpread(added, w.grid, w.deck)
                }
            }
            val bodies = bodiesOf(state.grid, w.machines, w.conduitsSnapshot(), w.bridges, w.deck, w.buffers)
            val result = stepSolidHeat(
                grid = state.grid,
                bodies = bodies,
                structure = structure,
                airEnergy = w.airEnergy,
                heatCapacity = heatCapacity(state.grid.size, w.masses),
            )
            conductedRadiated = result.radiated
            conductedToAir = result.toAir
            w.applyBodyHeat(bodies, result.energy)
        }

        val edges = EdgeGrid(state.grid)
        val conduits = w.conduitsSnapshot()
        val roomApertures = ApertureField.derive(edges, structure, openness)
        val plumbing = pipeApertures(edges, conduits)
        val volumes = pipeVolumes(state.grid, conduits)

        // ── Valves + Pumps ────────────────────────────────────────────────────────
        if (shouldRun(state.tick, PUMP_PERIOD)) {
            // Valves first: pressure propagates immediately, both layers see exchange
            // (see [exchangeLayers]).
            exchangeLayers(
                openings = valveOpenings(state.grid, conduits),
                roomMass = w.masses,
                roomEnergy = w.airEnergy,
                pipeMass = w.pipeMass,
                pipeEnergy = w.pipeEnergy,
                pipeVolumes = volumes,
            )

            // Pumps alongside valves, before either layer is diffused (see [applyPumps]).
            applyPumps(
                demands = pumpDemands(state.grid, w.deck, conduits, signals),
                roomMass = w.masses,
                roomEnergy = w.airEnergy,
                pipeMass = w.pipeMass,
                pipeEnergy = w.pipeEnergy,
                pipeVolumes = volumes,
            )
        }

        // ── Pressure ──────────────────────────────────────────────────────────────
        //
        // On the fluid's period, and not on every tick. Two [tilePressure] sweeps are the most
        // expensive thing the sim does — 47% of a 64 Hz tick, measured — and they are a pure
        // function of the air, which only moves when [diffuseFluid] below fires. Between fluid
        // ticks the sweep would rebuild a field identical to the one already in the state, so on
        // those ticks there is simply no push to apply.
        //
        // ⚠️ **Zero on a skipped tick, and emphatically not the last tick's push carried forward.**
        // [applyPressureForce] books both halves of one exchange: the gas takes `+J` on a face it
        // can cross and the hull takes the complement, and inside a sealed vessel those telescope
        // to nothing. Carrying the hull's half forward while the gas's half fires once mints
        // momentum on seven ticks in eight — `momentumBalance` catches it within a few ticks, which
        // is what that instrument is for. The push is per firing, so it lands per firing.
        //
        // This does change the impulse the hull feels per second, and deliberately so — it is the
        // same "each subsystem moves its full amount when it fires" rule the rest of the periods
        // follow. Against the pre-64 Hz sim it is a *doubling*, not a cut: this fires 8 times a
        // second where the old 4 TPS loop fired 4.
        val pressureImpulseX: Long
        val pressureImpulseY: Long
        val pressureTorque: Long
        if (shouldRun(state.tick, FLUID_PERIOD)) {
            // The pre-diffusion field, deliberately: the gradient that pushes the hull is the one
            // that exists before the gas has been allowed to answer it.
            val roomPressure = tilePressure(
                state.grid.size, w.masses, gasKelvin(w.airEnergy, heatCapacity(state.grid.size, w.masses)),
            )
            val pushed = applyPressureForce(
                edges, roomApertures, w.momentumX, w.momentumY, tileMass(state.grid.size, w.masses), roomPressure,
                w.about,
            )
            val pipePressure = tilePressure(
                state.grid.size, w.pipeMass,
                gasKelvin(w.pipeEnergy, heatCapacity(state.grid.size, w.pipeMass)), volumes,
            )
            val pipePushed = applyPressureForce(
                edges, plumbing, w.pipeMomentumX, w.pipeMomentumY,
                tileMass(state.grid.size, w.pipeMass), pipePressure, w.about,
            )
            pressureImpulseX = pushed.vesselX + pipePushed.vesselX
            pressureImpulseY = pushed.vesselY + pipePushed.vesselY
            pressureTorque = pushed.torque + pipePushed.torque
        } else {
            pressureImpulseX = 0L
            pressureImpulseY = 0L
            pressureTorque = 0L
        }

        // ── Fluid ─────────────────────────────────────────────────────────────────
        // When skipped, fluid state is carried forward from the previous tick.
        var fluidAir = Stuff(w.masses, w.airEnergy)
        var pipeAirResult = state.pipeAir
        var fluidVentedMass = 0L
        var fluidVentedEnergy = 0L
        if (shouldRun(state.tick, FLUID_PERIOD)) {
            val result = diffuseFluid(edges, roomApertures, w.masses, w.airEnergy)
            fluidAir = result.air
            fluidVentedMass = result.ventedMass
            fluidVentedEnergy = result.ventedEnergy
            // Pipes: same model, connectivity from player-drawn layout.
            val pipes = diffuseFluid(edges, plumbing, w.pipeMass, w.pipeEnergy)
            pipeAirResult = pipes.air
            // Pipes cannot vent to rim (ledger check).
            require(pipes.ventedMass == 0L && pipes.ventedEnergy == 0L) {
                "a sealed pipe network vented ${pipes.ventedMass}g — a rim face was open"
            }
        }

        // ── Flight ────────────────────────────────────────────────────────────────
        //
        val machines = w.machines.toList()
        val bridges = w.bridges.toList()
        val mass = vesselMass(w.grid, machines, w.rail, conduits, bridges, w.deck, w.buffers)

        // Debug thrust: acceleration × mass (see [Edit.Thrust]).
        val thrustX = w.thrustDx.coerceIn(-1, 1) * mass * Edit.DEBUG_THRUST_MILLI_G / 1000L
        val thrustY = w.thrustDy.coerceIn(-1, 1) * mass * Edit.DEBUG_THRUST_MILLI_G / 1000L

        // Dynamic rock spawning/despawning
        // World-spawned rocks are free mass, not counted in baselineRockMass.
        // Uses the post-advance position (matching what `positionX/Y` will be below) so the
        // window-recenter decision agrees with the position the HUD reads this same tick
        //
        // ⚠️ **The turn is about the centre of mass, and the origin moves so that it can be.**
        // `positionX` is the world position of the grid's *origin*, and adding the spin to `ang`
        // without moving the origin would rotate the ship about tile (0,0) — a corner of the pad,
        // usually off the hull entirely. That was invisible while nothing in the sim read `ang`;
        // it is not invisible now that a body's frame conversion goes through the pose.
        // [Pose.turnedAbout] is what moves the origin to hold the pivot still.
        //
        // Explicit, from the start-of-tick spin, for the same reason the position is: this tick's
        // torque is not known until this tick's fluid has been solved. `toInt` is not a truncation
        // to apologise for — [Coord]'s two's-complement wrap *is* the turn, so an angle that runs
        // past π comes back at −π exactly and never drifts. See [Rotation].
        val spin = angularVelocity(state.angImpulse, w.about)
        val comScale = Flight.PER_TILE / Rotation.MILLI_TILE
        val newPose = state.pose
            .turnedAbout(Coord(spin.toInt()), w.about.comX * comScale, w.about.comY * comScale)
            .movedBy(state.velocityX, state.velocityY)
        val newPositionX = newPose.x
        val newPositionY = newPose.y
        val newAng = newPose.ang
        val vesselTileX = newPositionX / Flight.PER_TILE
        val vesselTileY = newPositionY / Flight.PER_TILE
        val bodiesToDrift = RockSpawner.process(
            pose = state.pose,
            tick = state.tick,
            bodies = w.bodies.toList(),
            vesselTileX = vesselTileX,
            vesselTileY = vesselTileY,
        )
        // Replace w.bodies contents (driftBodies mutates by reference via the list).
        w.bodies.clear()
        w.bodies.addAll(bodiesToDrift)

        // Bodies fly here because this is where the ship's own motion is known.
        // A body is stated entirely in the *world* now — position and momentum both — so drifting
        // one needs the ship's whole *pose* and not just its velocity: the sweep asks where the body
        // has got to in the grid's frame, and the grid is turning as well as moving. See [RigidBody].
        //
        // Start-of-tick throughout, which is exactly what the ship's own pose is advanced by above:
        // the grid slides and turns by the same amount for the body as it does for the hull, because
        // it is the same grid.
        //
        // It is also where a body can hit something, because a contact is an exchange and the ship's
        // half of it has to join `netImpulse` below in the same tick the body's half is booked. The
        val bodiesDrifted = driftBodies(
            state.grid,
            structure,
            w.bodies,
            state.gravity,
            ShipMotion(state.pose, state.velocityX, state.velocityY, spin),
            mass,
            w.about,
            w.machines,
        )

        // Vessel pays for body momentum here: `−J` for the `+J` the body got (conserved by construction).
        // Everything the vessel handed a body this tick: the extractor took momentum off one
        // (negative), contact and plating gave some to others.
        val handedX = w.bodyHandedX + bodiesDrifted.handedX
        val handedY = w.bodyHandedY + bodiesDrifted.handedY
        // Valves and pumps no longer push: they move mass between two cells at the same place, and a
        // transfer that goes nowhere has no direction to push in. What is left is what presses on the
        // hull, what the debug engine adds, and what the vessel handed the bodies around it.
        // A thruster's exhaust took `+p` overboard with it, so the ship keeps `−p`. Nothing is
        // minted: the two halves are written from one number in [fire].
        //
        // ⚠️ **The frame changes here, and this is the only place it does.** Everything the grid
        // produces — a pressure on a bulkhead, a plume out of a nozzle — is a direction *in the
        // ship*, because the grid is the ship. The ship's momentum is a direction *in the world*.
        // Without this turn a thruster pushed along the grid's axes however the ship was pointing:
        // the torque was right, because a torque is a scalar and reads the same in both frames, so
        // the ship rotated correctly and then accelerated as though it had not. Turned by the
        // **start-of-tick** pose, which is the attitude the fluid was solved at.
        //
        // `handed` is already in the world — a body's momentum is world-frame and the contact
        // solver turns the exchange on the way out (see [sweepBody]) — so it is subtracted after
        // the turn and not before. The debug engine is world-frame too, and for the same reason it
        // has no torque: [Edit.Thrust] is a key that shoves the ship, not a nozzle bolted anywhere.
        //
        // ⚠️ The exhaust is turned **on its own** and then subtracted, rather than being folded into
        // the sum before the turn. `turn(a − b)` and `turn(a) − turn(b)` differ by a unit or two
        // under truncation, and the exhaust store below has to be the same number this subtracts or
        // the ledger picks up a drift of a gram or two a tick — which is exactly the size of thing
        // `momentumBalance` exists to catch, so it must not be the instrument's own doing.
        // The exhaust, in the world. One number, used twice: subtracted from the ship here and added
        // to the overboard store below, which is what makes the pair impossible to unbalance.
        val exhaustX = state.pose.turnedX(w.exhaustMomentumX, w.exhaustMomentumY)
        val exhaustY = state.pose.turnedY(w.exhaustMomentumX, w.exhaustMomentumY)
        val netImpulseX = state.pose.turnedX(pressureImpulseX, pressureImpulseY) + thrustX - handedX - exhaustX
        val netImpulseY = state.pose.turnedY(pressureImpulseX, pressureImpulseY) + thrustY - handedY - exhaustY

        // The same five contributions crossed with the point each one is applied at — see
        // [torqueAbout] for why this is summed term by term and not derived from `netImpulse`.
        //
        // The debug engine is the one term with no position and therefore no torque, and that is
        // deliberate rather than an omission: [Edit.Thrust] is a key that pushes the *ship*, not a
        // nozzle bolted anywhere, so the honest place to apply it is the centre of mass, where its
        // lever arm is zero. When a real engine retires it the term goes with it.
        val handedTorque = w.bodyHandedTorque + bodiesDrifted.handedTorque
        val netTorque = pressureTorque - handedTorque - w.exhaustTorque

        return state.copy(
            machines = machines,
            deck = w.deck,
            buffers = w.buffers,
            rail = w.rail,
            conduits = conduits,
            bridges = bridges,
            diverters = FlowCursors(w.diverters.snapshot(), w.diverters.mergeSnapshot()),
            tick = state.tick + 1,
            extractedMass = w.extractedMass,
            ventedMass = w.ventedMass,
            signals = signals,
            networks = networks,
            structure = structure,
            occupancy = occupancy,
            generatedEnergy = w.generatedEnergy,
            radiatedEnergy = state.radiatedEnergy + conductedRadiated,
            insertedEnergy = w.insertedEnergy,
            acquiredEnergy = w.acquiredEnergy,
            // Solid→air energy (see [SolidHeatStep]).
            solidToAirEnergy = state.solidToAirEnergy + conductedToAir,
            air = fluidAir,
            pipeAir = pipeAirResult,
            pipeMomentum = MomentumField.of(edges, w.pipeMomentumX, w.pipeMomentumY),
            airVentedMass = state.airVentedMass + fluidVentedMass + w.exhaustAirMass,
            // Separate from radiatedEnergy: cleaner ledger.
            airVentedEnergy = state.airVentedEnergy + fluidVentedEnergy + w.exhaustAirEnergy,
            // Debug bellows (non-physics, booked like the debug engine — see [Edit.Inject]).
            injectedAirMass = w.injectedAirMass,
            injectedAirEnergy = w.injectedAirEnergy,
            // Written by [applyPressureForce] and read by nobody: diffusion has no momentum to carry,
            // so what lands here is an impulse total that is never spent. Kept because the save format
            // is unchanged and a model that closes the ledger again will want somewhere to put it.
            // ⚠️ Not a velocity field — the flow overlay measures [diffuseFluid] instead (see
            // [VesselState.flow]). See the extraction plan §3.
            momentum = MomentumField.of(edges, w.momentumX, w.momentumY),
            // Momentum that is genuinely somewhere else now: astern of the ship at 3 km/s.
            //
            // ⚠️ Turned into the world by the same pose `netImpulse` was, and it has to be the same
            // one: this is the `+p` whose `−p` the ship just took, and a pair booked in two
            // different frames does not cancel. Turned as it is *booked* rather than as it is read,
            // because it is a running total over ticks the ship was pointing different ways and
            // there is no one angle that could undo it afterwards.
            exhaustMomentumX = state.exhaustMomentumX + exhaustX,
            exhaustMomentumY = state.exhaustMomentumY + exhaustY,
            // Pipe pressure + pump momentum all push the ship (see [exchangeLayers], [applyPumps]).
            vesselImpulseX = state.vesselImpulseX + netImpulseX,
            vesselImpulseY = state.vesselImpulseY + netImpulseY,
            netImpulseX = netImpulseX,
            netImpulseY = netImpulseY,
            angImpulse = state.angImpulse + netTorque,
            netTorque = netTorque,
            // Explicit integration: move by velocity at tick start.
            positionX = newPositionX,
            positionY = newPositionY,
            ang = newAng,
            // Debug engine (non-physics, booked alongside thrust).
            debugImpulseX = state.debugImpulseX + thrustX,
            debugImpulseY = state.debugImpulseY + thrustY,
            bodies = bodiesDrifted.bodies,
            bodyImpulseX = state.bodyImpulseX + handedX,
            bodyImpulseY = state.bodyImpulseY + handedY,
            motion = motion,
        ).bookedFrameTurn(state.pose).resized(w.fitRequested)
    }

    /**
     * Charge the ledger for the momentum the gas appears to gain by being carried round with the
     * hull — see [VesselState.frameTurnImpulseX] for what it is and why it is not physics.
     *
     * Applied to the finished state and against [was], the pose the tick started at, because the
     * term the algebra asks for is the **end-of-tick** gas vector turned through the tick's change
     * of attitude. Working it through: the gas gave the hull `P` this tick and was turned by the
     * start-of-tick pose when it did (see `netImpulse`), so the balance moves by
     * `[R(θₜ) − R(θₜ₋₁)]·(G − P)` — and `G − P` is what the gas is holding when the tick ends, which
     * is what this reads. Nothing to do if the ship did not turn, which is the overwhelmingly common
     * case and worth not paying four multiplies for.
     */
    private fun VesselState.bookedFrameTurn(was: Pose): VesselState {
        if (pose.ang == was.ang) return this
        val gasX = momentum.totalX + pipeMomentum.totalX + undeliveredImpulseX
        val gasY = momentum.totalY + pipeMomentum.totalY + undeliveredImpulseY
        return copy(
            frameTurnImpulseX = frameTurnImpulseX + pose.turnedX(gasX, gasY) - was.turnedX(gasX, gasY),
            frameTurnImpulseY = frameTurnImpulseY + pose.turnedY(gasX, gasY) - was.turnedY(gasX, gasY),
        )
    }

    /**
     * The grid keeps the clearance the world says it keeps — [VesselState.gridPad] — between the
     * vessel and every edge, growing here, at the very end of the tick, if the pad was used up,
     * or shrinking back if the player asked for an explicit fit.
     *
     * **After the tick, never during it.** `Work` is built from the grid the tick started on and
     * every pass since has addressed tiles through it, so a resize partway would leave half a world
     * on each lattice. The edit lands where the player clicked, on the grid they clicked it on, and
     * the world moves underneath afterwards — which is also what `GridGrowTest` pins by digesting a
     * world that grew against the same world built at the final size.
     *
     * Growth only ever adds vacuum tiles, at zero mass, zero energy and zero momentum, so no ledger
     * and no baseline moves; the accounting question is the one §5 raises about *shrinking*, and
     * that is what `fitRequested` triggers: the grid shrinks to the fitted box and whatever cells
     * are discarded are vented by `remapped`, which is why this must happen at the very end of the
     * tick, not during it.
     */
    private fun VesselState.resized(fitRequested: Boolean): VesselState {
        // Only worlds that opted into a pad, which means worlds that were fitted — see
        // [VesselState.gridPad]. A hand-authored fixture keeps the frame it was drawn in.
        if (gridPad <= 0) return this
        val result = if (fitRequested) fitToFrame(gridPad) else growToFit(gridPad)
        if (!result.grew) return this
        // The offset travels to whoever wrote a coordinate down — see [VesselState.frameShiftX].
        return result.state.copy(
            frameShiftX = frameShiftX + result.dx,
            frameShiftY = frameShiftY + result.dy,
        )
    }

    /** Full snapshots on the wire; a demo sending partial state would merge here instead. */
    override fun patchState(state: VesselState, delta: VesselState): VesselState = delta

    /** Pump demands: room→pipe. Both ends optional (missing pump excluded). Activation applied here, not in [applyPumps]. */
    private fun pumpDemands(
        grid: Grid,
        deck: DeckArray,
        conduits: Conduits,
        signals: SignalField,
    ): List<PumpDemand> {
        var demands: MutableList<PumpDemand>? = null
        for (i in 0 until deck.size) {
            val tile = TileIndex(i)
            val pump = deck[tile] as? Pump ?: continue
            if (conduits.at(Conduit.Pipe, tile) == null) continue
            val intake = grid.neighbour(tile, pump.facing)
            if (intake == TileIndex.NONE) continue
            val activation = pump.wiring.activation(Action.Run, signals.at(tile))
            if (activation <= 0) continue
            val moles = Pump.MILLIMOLES_PER_TICK * activation / SignalField.FULL
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
    * Activity per machine-tick: rate × activation, with carry-over via [Rate].
    *
    * Full rate per machine-tick — unchanged from the old single-rate system. The Rate
    * accumulator's carry handles fractional activation values.
    */
    /**
     * What the machine at [centre] has in its [role] store, or null if that store is empty.
     *
     * The `!!` is the address scheme holding: a machine is only ever asked for a role it keeps, and
     * [bufferTile] returns null for a role it does not — so a null here is a machine being asked for
     * a store it has no concept of, which is a bug in the caller and not a state the world can be in.
     */
    private fun Work.store(m: Placed, centre: TileIndex, role: BufferRole): Resource? =
        buffers.resourceAt(bufferTile(grid, m, centre, role)!!)

    /** Replace the [role] store of the machine at [centre], or empty it if [resource] is null. */
    private fun Work.putStore(m: Placed, centre: TileIndex, role: BufferRole, resource: Resource?) {
        buffers.put(bufferTile(grid, m, centre, role)!!, resource)
    }

    private fun throttled(perTick: Long, activation: Int, carry: Long): Pair<Long, Long> {
        if (activation <= 0) return 0L to carry
        return Rate.tick(perTick * activation, SignalField.FULL, carry)
    }

    private fun Work.refine(cfg: OutofspaceConfig, m: Processor, activation: Int, tile: TileIndex): Processor {
        // Starting a fresh lump is a move between two stores, and the whole tick's heat is applied
        // the moment it starts rather than dribbled out over the action.
        val inProgress = store(m, tile, BufferRole.Inside) ?: run {
            val fresh = store(m, tile, BufferRole.Input) ?: return m // Nothing to do if there's no input
            putStore(m, tile, BufferRole.Input, null)
            putStore(m, tile, BufferRole.Inside, fresh)
            heat(tile, heatOfWorking(fresh.mass, m))
            fresh
        }

        val (actionProgress, carry) = throttled(1, activation, m.carry)
        if (m.progress + actionProgress >= m.ticksPerAction) {
            // Full output blocks the machine (catches tailings too). The lump stays where it is.
            if (store(m, tile, BufferRole.Product) != null || store(m, tile, BufferRole.Waste) != null) {
                return m.copy(progress = m.ticksPerAction, carry = carry)
            }
            val r = process(inProgress, m.efficiencyPermille)
            putStore(m, tile, BufferRole.Inside, null)
            putStore(m, tile, BufferRole.Product, r.product)
            putStore(m, tile, BufferRole.Waste, r.tailings)
            return m.copy(progress = 0, carry = carry)
        }
        return m.copy(progress = m.progress + actionProgress.toInt(), carry = carry)
    }

    private fun Work.refine(cfg: OutofspaceConfig, m: ThermalDecomposer, activation: Int, tile: TileIndex): ThermalDecomposer {
        val inProgress = store(m, tile, BufferRole.Inside) ?: run {
            val fresh = store(m, tile, BufferRole.Input) ?: return m // Nothing to do if there's no input
            putStore(m, tile, BufferRole.Input, null)
            putStore(m, tile, BufferRole.Inside, fresh)
            heat(tile, heatOfWorking(fresh.mass, m))
            fresh
        }

        val (actionProgress, carry) = throttled(1, activation, m.carry)
        if (m.progress + actionProgress >= m.ticksPerAction) {
            if (store(m, tile, BufferRole.Product) != null) {
                return m.copy(progress = m.ticksPerAction, carry = carry)
            }
            putStore(m, tile, BufferRole.Inside, null)
            putStore(m, tile, BufferRole.Product, cook(inProgress, m.setTemperature))
            return m.copy(progress = 0, carry = carry)
        }
        return m.copy(progress = m.progress + actionProgress.toInt(), carry = carry)
    }

    private fun Work.melt(cfg: OutofspaceConfig, m: Smelter, activation: Int, tile: TileIndex): Smelter {
        val input = store(m, tile, BufferRole.Input) ?: return m
        val (mass, carry) = throttled(m.massPerTick, activation, m.carry)
        val heldRefined = store(m, tile, BufferRole.Product)
        val heldSlag = store(m, tile, BufferRole.Waste)
        if (blocked(heldRefined, heldSlag)) return m.copy(carry = carry)
        val chunkMass = minOf(mass, input.mass)
        if (chunkMass <= 0L) return m.copy(carry = carry)

        val chunk = Resource(input.form, input.mixture.take(chunkMass))
        heat(tile, heatOfWorking(chunkMass, m))
        val r = smelt(chunk)
        // Smelter stalls if dominant species differs (stopped machine signals ore change). Both
        // merges are resolved before anything is written, or a stall would leave the ore consumed.
        val refined = heldRefined.merged(r.refined) ?: return m.copy(carry = carry)
        val slag = heldSlag.merged(r.slag) ?: return m.copy(carry = carry)

        putStore(m, tile, BufferRole.Input, Resource(input.form, input.mixture - chunk.mixture).orNull())
        putStore(m, tile, BufferRole.Product, refined.buffer)
        putStore(m, tile, BufferRole.Waste, slag.buffer)
        return m.copy(carry = carry)
    }

    private fun Work.vaporize(m: Vaporizer, activation: Int, tile: TileIndex): Vaporizer {
        val input = store(m, tile, BufferRole.Input) ?: return m
        val (mass, carry) = throttled(m.massPerTick, activation, m.carry)
        val chunkMass = minOf(mass, input.mass)
        if (chunkMass <= 0L) return m.copy(carry = carry)

        val chunk = Resource(input.form, input.mixture.take(chunkMass))
        heat(tile, heatOfWorking(chunkMass, m))
        val gas = chunk.mixture
        val parcel = MassArray(1)
        for (s in Species.ALL) {
            val g = gas[s]
            if (g <= 0L) continue
            masses[MassIndex(tile, s)] += g
            parcel[MassIndex(TileIndex(0), s)] = g
        }
        val energy = heatCapacityAt(parcel, TileIndex(0)) * Temperature.AMBIENT_KELVIN
        airEnergy[tile] += energy
        // The ore has left the cargo and the same mass has joined the atmosphere. See [solidBecameGas].
        solidBecameGas(chunkMass, energy)

        putStore(m, tile, BufferRole.Input, Resource(input.form, input.mixture - chunk.mixture).orNull())
        return m.copy(carry = carry)
    }

    /**
     * One tick of a [Thruster]: throw propellant out of the nozzle and take whatever was in the way
     * with it.
     *
     * The three outcomes are [ExhaustPath]'s three cases and nothing else decides anything here.
     * What is worth reading closely is the **bookkeeping**, because the exhaust crosses two ledgers
     * at once and each half needs its own term:
     *
     *  - propellant is a solid off a belt, so spending it is [VesselState.ventedMass] — the same
     *    store a [Vent] increments, and for the same reason: it has left the vessel's cargo.
     *  - gas the jet scooped up is atmosphere. Overboard it is [VesselState.airVentedMass]; into the
     *    destination tile it has not gone anywhere at all and is booked nowhere.
     *  - propellant that lands in the destination tile has *become* atmosphere, so it is vented from
     *    the solid ledger **and** injected into the air one. Both, or one of the two identities
     *    silently stops being zero — see [VesselState.airBalance].
     *
     * ⚠️ A blocked motor produces no impulse here and that is not an omission. Its exhaust pushes
     * the ship's own hull, so the pair cancels exactly; what the ship feels is whatever
     * [applyPressureForce] makes of a tile that now holds a jet's worth of very hot gas, which is
     * a real force arrived at honestly rather than a fraction of the thrust picked to look right.
     */
    private fun Work.fire(
        cfg: OutofspaceConfig,
        m: Thruster,
        activation: Int,
        tile: TileIndex,
        structure: StructureMap,
    ): Thruster {
        val input = store(m, tile, BufferRole.Input) ?: return m
        val (allowance, carry) = throttled(m.massPerTick, activation, m.carry)
        val chunkMass = minOf(allowance, input.mass)
        if (chunkMass <= 0L) return m.copy(carry = carry)

        val path = exhaustPath(grid, structure, tile, m.facing)

        heat(tile, heatOfWorking(chunkMass, m))

        // The propellant, as gas: whatever went into the chamber is what comes out of the bell.
        val chunk = Resource(input.form, input.mixture.take(chunkMass))
        val parcel = MassArray(1) { _,s -> chunk.mixture[s] }
        val propellantEnergy = heatCapacityAt(parcel, TileIndex(0)) * Temperature.AMBIENT_KELVIN

        // Everything standing in the plume, taken with it. A jet does not thread between the gas in
        // a corridor; it entrains it, which is why the whole path is walked and not just its ends.
        var scoopedMass = 0L
        var scoopedEnergy = 0L
        for (tile in path.path) {
            // The destination keeps what it has — the exhaust is about to be added to it.
            if (!path.isClear && tile == path.destination) continue
            for (s in Species.ALL) {
                val massIndex = MassIndex(tile, s)
                val held = masses[massIndex]
                if (held <= 0L) continue
                parcel[MassIndex(TileIndex(0),s)] += held
                scoopedMass += held
                masses[massIndex] = 0L
            }
            scoopedEnergy += airEnergy[tile]
            airEnergy[tile] = 0L
        }

        val ejectedMass = chunkMass + scoopedMass

        if (path.isClear) {
            // Straight overboard as the solid it still is, so only the solid ledger hears about it.
            ventedMass += chunkMass
            // Out of the world at exhaust velocity, and the ship gets the other half.
            airVentedByExhaust(scoopedMass, scoopedEnergy)
            val impulse = ejectedMass * Thruster.tilesPerTick(cfg.ticksPerSecond)
            val outX = impulse * m.facing.dx
            val outY = impulse * m.facing.dy
            exhaustMomentumX += outX
            exhaustMomentumY += outY
            // At the bell, which is the tile the machine is on. The ship keeps `−p` and `−τ`, and
            // both halves are written from the one number here so neither can be minted.
            exhaustTorque += torqueAbout(
                about, tileCentre(grid.xOf(tile)), tileCentre(grid.yOf(tile)), outX, outY,
            )
        } else {
            val destination = path.destination
            // ⚠️ [destination] and not [tile] — the gas and its heat land in the *same* place, and
            // splitting them is not a rounding error but a category one. Sent home while the energy
            // went down the plume, the destination gained joules and no gas at all: capacity stayed
            // zero, and [gasKelvin] reads a zero capacity as ambient, so a tile with a rocket firing
            // into it reported room temperature however long the burn ran.
            for (s in Species.ALL) masses[MassIndex(destination,s)] += parcel[MassIndex(TileIndex(0),s)]
            // The jet's kinetic energy stops here and becomes heat, which is what makes firing into
            // your own bulkhead expensive rather than merely useless.
            val landed = propellantEnergy + Thruster.kineticEnergy(ejectedMass)
            airEnergy[destination] += landed + scoopedEnergy
            solidBecameGas(chunkMass, landed)
        }

        putStore(m, tile, BufferRole.Input, Resource(input.form, input.mixture - chunk.mixture).orNull())
        return m.copy(carry = carry)
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
     * The orebody a new extractor draws from, per kilogram. Mostly iron and far too dirty to smelt
     * straight — 410g of iron against 590g of everything else — so a line that runs ore directly into
     * a smelter yields nothing but slag. Learning to put a processor in front is the first thing this
     * world teaches, and it teaches it without a tutorial.
     *
     * ⚠️ These four numbers are the same as those species' [Species.relativeAbundance], and they are
     * a **copy, not a derivation**. Deliberately: this is the one orebody every new extractor gets,
     * authored so that the slag lesson lands, whereas abundance is what the rock field scatters. The
     * two agreeing today is a coincidence of authoring and the step 6 audit found them already
     * drifting — osmium has an abundance now and does not appear here, so the extractor cannot
     * produce it and only mined rocks can. That is the intended behaviour and not the bug; the bug
     * would be reading this list as though it told you what the world contains.
     *
     * It is now stated in **minerals**, which is what an ore body is made of. Copper and titanium
     * used to appear here as loose elements, and under the rebuilt species table that is incoherent:
     * neither occurs native, so they arrive as [Species.Chalcopyrite] and [Species.Ilmenite] — the
     * rocks you would actually be digging — and the elements come out of a refinery instead. Iron
     * stays as itself because native iron is real, and it is what a kamacite-bearing body gives you.
     */
    val DEFAULT_ORE_BODY: Mixture = Mixture.of(
        Species.Iron to 410L,
        Species.Quartz to 300L,
        Species.Chalcopyrite to 180L,
        Species.Ilmenite to 110L,
        energy = 0L,
    )

    /** Mutable scratch for one tick (reducer stays pure). */
    private class Work(state: VesselState) {
        val grid: Grid = state.grid
        val machines: MutableList<Machine?> = state.machines.toMutableList()
        val deck: DeckArray = state.deck.copyOf()
        val buffers: BufferLayer = state.buffers.copyOf()

        /** Everything riding on the track — see [RailLayer]. Mutated in place through the tick. */
        val rail: RailLayer = state.rail.copyOf()

        /**
         * What the networks are made of — see [TrackLayers]. Mutated in place through the tick
         * beside [layers], and handed back to [Conduits] whole at the end of it.
         */
        val tracks: TrackLayers = state.conduits.tracks.copyOf()
        inline operator fun <reified T> get(tile: TileIndex): T? = when(T::class) {
            Machine::class -> machines.getOrNull(tile.index)
            DeckMachine::class -> deck[tile]
            else -> null
        } as T?
        operator fun set(tile: TileIndex, value: Machine) {
            machines[tile.index] = value
        }
        operator fun set(tile: TileIndex, value: DeckMachine) {
            deck[tile] = value
        }
        operator fun plusAssign(value: DeckMachine) {
            deck += value
        }
        var extractedMass: Long = state.extractedMass
        // Editable conduit layers (array of lists avoids per-tile Conduits rebuild).
        val layers: Array<MutableList<Segment?>> =
            Array(Conduit.entries.size) { state.conduits[Conduit.entries[it]].toMutableList() }

        fun layer(conduit: Conduit): MutableList<Segment?> = layers[conduit.ordinal]

        /**
         * Whether [tile] is already spoken for by the *other* matter network — see
         * [Conduits.checkExclusion], which is the same rule stated where the state lives.
         *
         * The edit path enforces it as well as the invariant, and the difference is what the player
         * gets: a `require` in [Conduits] would take the game down when somebody drags a pipe over a
         * belt, whereas refusing here means the drag simply does not lay there. The invariant is for
         * the code, this is for the hand on the mouse.
         */
        fun spokenFor(conduit: Conduit, tile: TileIndex): Boolean = when (conduit) {
            Conduit.Rail -> layer(Conduit.Pipe)[tile.index] != null
            Conduit.Pipe -> layer(Conduit.Rail)[tile.index] != null
            // Wires ride under anything: information does not compete for the floor.
            Conduit.Power, Conduit.Signal -> false
        }

        /** The rail layer, which packets, gauges, bridges and motion all mean by "the track". */
        val rails: MutableList<Segment?> get() = layers[Conduit.Rail.ordinal]

        fun conduitsSnapshot(): Conduits =
            Conduits.of(Array(layers.size) { layers[it].toList() }, tracks.copyOf())
        val bridges: MutableList<Bridge?> = state.bridges.toMutableList()
        val diverters: FlowCursors = FlowCursors(state.diverters.snapshot(), state.diverters.mergeSnapshot())
        var ventedMass: Long = state.ventedMass

        /** Running admission of gas conjured by the debug bellows — see [Edit.Inject]. */
        var injectedAirMass: Long = state.injectedAirMass
        var injectedAirEnergy: Long = state.injectedAirEnergy

        // Debug engine direction, summed + clamped (see [Edit.Thrust]).
        var thrustDx: Int = 0
        var thrustDy: Int = 0

        /**
         * What this tick's [Thruster]s threw overboard, and where it went.
         *
         * Four running totals rather than one, because the exhaust is two substances leaving two
         * ledgers: the propellant is a solid off the belt ([VesselState.ventedMass], the same store
         * a [Vent] uses) and the gas the jet scooped out of the corridor on its way past is
         * atmosphere ([VesselState.airVentedMass]). Booking both to one term would close the sum and
         * break both identities. The momentum is the pair's other half — `+p` here is exactly the
         * `−p` handed to the ship below, so nothing is minted and the ledger needs no new store.
         */
        var exhaustAirMass: Long = 0L
        var exhaustAirEnergy: Long = 0L
        var exhaustMomentumX: Long = 0L
        var exhaustMomentumY: Long = 0L

        /**
         * Where the vessel's mass is **at the start of the tick**, which is the point every torque
         * this tick is booked about.
         *
         * One distribution for the whole tick, on purpose. The machine pass fires the thrusters
         * before the cargo pass has finished moving anything, so a producer that asked again
         * mid-tick would get a slightly different centre and the tick would be twisting about two
         * points at once. The same choice, for the same reason, that hands `state.velocityX` to
         * [driftBodies] and integrates the position from it: one frame per tick, stated once.
         */
        val about: MassDistribution = massDistribution(state.grid, state.machines, state.rail, state.conduits, state.bridges, state.deck, state.buffers)

        /**
         * Where the grid sits in the world, taken once from the incoming state and shared — the same
         * choice, for the same reason, as [about]. A body reached by an extractor part way through
         * the machine pass and one reached at the end must be measured against the same frame, or
         * the tick converts two bodies through two different poses.
         */
        val pose: Pose = state.pose

        /**
         * The twist that went overboard with the exhaust — the angular half of [exhaustMomentumX],
         * booked at the thruster that threw it, and the whole reason this step exists. Two engines
         * that balance linearly do not balance about the centre of mass unless they straddle it.
         */
        var exhaustTorque: Long = 0L

        /** Atmosphere a thruster's plume carried off the grid — see [OutofspaceReducer.fire]. */
        fun airVentedByExhaust(mass: Long, energy: Long) {
            exhaustAirMass += mass
            exhaustAirEnergy += energy
        }

        /**
         * Books [mass] of solid **becoming** [energy]'s worth of gas: the one event in the game that
         * crosses between the two mass ledgers.
         *
         * Both halves, always, and that is the entire reason this is a function rather than two
         * lines at each call site. The solid ledger has to hear that the cargo is gone
         * ([VesselState.ventedMass]) and the air ledger has to hear that the atmosphere did not
         * grow on its own ([VesselState.injectedAirMass]); book one and both identities are wrong
         * in opposite directions, which reads as two unrelated leaks.
         *
         * ⚠️ [vaporize] did neither for the whole of its life — every running vaporizer drifted
         * `massBalance` down and `airBalance` up by its throughput, on every tick, and no test was
         * pointed at the machine to say so. It is one call now, shared with [fire], because two
         * copies of this would eventually be one copy plus a machine that forgot.
         */
        fun solidBecameGas(mass: Long, energy: Long) {
            ventedMass += mass
            injectedAirMass += mass
            injectedAirEnergy += energy
        }

        var fitRequested: Boolean = false

        /** Free-floating bodies. No conservation ledger — bodies spawn/despawn freely. */
        val bodies: MutableList<RigidBody> = state.bodies.toMutableList()

        /**
         * Momentum the vessel handed the bodies during the **machine** pass, which is negative: an
         * extractor takes momentum off a body rather than giving it any.
         *
         * Separate from what [driftBodies] hands out only because it happens earlier in the tick;
         * the two are summed into one term below and mean the same thing. See [VesselState.bodyImpulseX].
         */
        var bodyHandedX: Long = 0L
        var bodyHandedY: Long = 0L

        /** Its angular half, about [about] — see [BodyStep.handedTorque]. */
        var bodyHandedTorque: Long = 0L

        // Mutable: edit pass moves air before fluid pass runs.
        val masses: MassArray = state.air.copyMass()

        /** This tick's air temperature, as energy — mutable for the same reason [masses] is. */
        val airEnergy: EnergyArray = state.air.copyEnergy()

        /** This tick's momentum, mutable for the same reason [masses] is. */
        val momentumX: LongArray = state.momentum.copyX()
        val momentumY: LongArray = state.momentum.copyY()

        /** The pipes' own fluid, in the same four working arrays and for the same reasons. */
        val pipeMass: MassArray = state.pipeAir.copyMass()
        val pipeEnergy: EnergyArray = state.pipeAir.copyEnergy()
        val pipeMomentumX: LongArray = state.pipeMomentum.copyX()
        val pipeMomentumY: LongArray = state.pipeMomentum.copyY()

        // Motion log for renderer, built from pre-tick rail state.
        val motion: MotionLog = MotionLog(state.rails, state.rail)

        // tile → machine index (maintained incrementally for O(n)).
        val originOf: TileArray = TileArray(state.grid.size).also { o ->
            for (i in machines.indices) {
                val m = machines[i] ?: continue
                for (t in coveredTiles(state.grid, TileIndex(i), m.kind.size)) o[t] = TileIndex(i)
            }
            for (tile in grid.tiles) {
                val m = deck[tile] ?: continue
                for (t in coveredTiles(state.grid, tile, m.kind.diameter)) o[t] = tile
            }
            for (i in 0 until o.size) o[TileIndex(i)] = TileIndex(o[TileIndex(i)].index)
        }

        /** Who is joined to whom, set once the wire has been swept. */
        var networks: SignalNetworks = SignalNetworks.none(state.grid.size)

        /** This tick's signal snapshot, set once the sensing pass has run. */
        var signals: SignalField = SignalField.none(state.grid.size)

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
        var generatedEnergy: Long = state.generatedEnergy
        var insertedEnergy: Long = state.insertedEnergy
        var acquiredEnergy: Long = state.acquiredEnergy

        /** Charges [energy] of waste heat to the machine stored at [index]. */
        fun heat(tile: TileIndex, energy: Long) {
            if (energy <= 0L || tile.index !in heatAdded.indices) return
            heatAdded[tile.index] += energy
            generatedEnergy += energy
        }

        /**
         * Charges [energy] to the machine at [tile] **without** counting it as generated.
         *
         * The difference from [heat] is the whole point: this is energy that was already in the
         * world and has changed hands — a rock's heat arriving in the extractor that ate it. Booking
         * it as generated would break the thermal balance by exactly the amount that moved. Also
         * increments [acquiredEnergy] to record that the grid acquired this energy from outside.
         */
        fun absorb(tile: TileIndex, energy: Long) {
            if (energy == 0L || tile.index !in heatAdded.indices) return
            heatAdded[tile.index] += energy
            acquiredEnergy += energy
        }

        /** Books energy inserted by the player via debug features. */
        fun built(energy: Long) { insertedEnergy += energy }

        /** Books energy removed by scrapping debug-placed things. */
        fun scrapped(energy: Long) { insertedEnergy -= energy }

        /** Apply conduction results back to machines/segments/bridges. */
        fun applyBodyHeat(bodies: List<Body>, energy: LongArray) {
            for (i in bodies.indices) {
                val body = bodies[i]
                if (energy[i] == body.energy) continue
                when (body.slot) {
                    // A machine is several bodies now, one per tile, so the tile has to be named
                    // as well as the machine — see [Body.part].
                    // Through [Body.anchor] and not [Body.tile]: a machine occupies its whole
                    // footprint but is stored only at its origin, so every part but the centre
                    // would otherwise write into whatever happens to sit on the tile it covers.
                    // Stored per tile in the dense deck layer, so this one is addressed by where
                    // the metal is and needs no part index at all.
                    BodySlot.DeckStore -> deck.setEnergy(body.tile, energy[i])
                    // Held matter, addressed by the tile its store stands on — same reason as above.
                    BodySlot.BufferStore -> buffers.stuff.setEnergy(body.tile, energy[i])
                    BodySlot.Deck -> machines[body.anchor.index]?.let {
                        machines[body.anchor.index] = it.withEnergy(it.energy.with(body.part, energy[i]))
                    }
                    // Keyed by layer as well as tile: two fittings can stand on one tile and each
                    // has its own temperature, so `at` alone would put a pipe's heat on a rail.
                    BodySlot.Fitting -> body.conduit?.let { c ->
                        if (tracks.occupies(c, body.tile)) tracks.setEnergy(c, body.tile, energy[i])
                    }
                    // Same again: a bridge spans three tiles and is stored at the middle one.
                    BodySlot.Span -> bridges[body.anchor.index]?.let {
                        bridges[body.anchor.index] = it.withEnergy(it.energy.with(body.part, energy[i])) as Bridge
                    }
                }
            }
        }

        // Cut pipe: release gas+heat into room (not deleted — shared ledger).
        fun cutOpen(tile: TileIndex) {
            for (s in Species.ALL) {
                val massIndex = MassIndex(tile, s)
                masses[massIndex] += pipeMass[massIndex]
                pipeMass[massIndex] = 0L
            }
            airEnergy[tile] += pipeEnergy[tile]
            pipeEnergy[tile] = 0L
        }

        /** Where a machine instance currently sits — extractors are charged heat by identity. */
        fun indexOf(machine: Machine): Int = machines.indexOfFirst { it === machine }

        fun apply(edit: Edit) {
            when (edit) {
                is Edit.Place -> {
                    if (edit.tile.index !in machines.indices) return
                    when (edit.kind) {
                        // Book energy for new body (heat arriving, not conjured).
                        MachineKind.Rail, MachineKind.Pipe, MachineKind.Wire -> {
                            val c = edit.kind.conduit!!
                            if (spokenFor(c, edit.tile)) return
                            if (layer(c)[edit.tile.index] == null) {
                                layer(c)[edit.tile.index] = Segment(c)
                                built(tracks.lay(c, edit.tile))
                            }
                        }
                        // Valve: upgrade existing pipe or lay new.
                        MachineKind.Valve -> {
                            // A valve is a fitting on a run, so laying one lays pipe — and a tile
                            // with a belt on it has no room for pipe to lay.
                            if (spokenFor(Conduit.Pipe, edit.tile)) return
                            val existing = layer(Conduit.Pipe)[edit.tile.index]
                            layer(Conduit.Pipe)[edit.tile.index] =
                                existing?.copy(valve = true)
                                    ?: Segment(Conduit.Pipe, valve = true)
                                        .also { built(tracks.lay(Conduit.Pipe, edit.tile)) }
                        }
                        MachineKind.Gauge -> if (rails[edit.tile.index] == null && !spokenFor(Conduit.Rail, edit.tile)) {
                            rails[edit.tile.index] = Segment(Conduit.Rail, isGauge = true)
                                .also { built(tracks.lay(Conduit.Rail, edit.tile)) }
                        }
                        MachineKind.Bridge -> placeBridge(edit.tile, edit.facing)
                        else -> placeBuilding(edit.tile, edit.kind, edit.facing)
                    }
                }
                is Edit.PlaceDeck -> {
                    if (edit.tile == TileIndex.NONE || edit.tile.index >= deck.size) return
                    placeDeckBuilding(edit.tile, edit.kind, edit.facing, deck)
                }
                is Edit.Lay -> layConduit(edit.from, edit.to, edit.conduit)
                is Edit.Cut -> {
                    val dir = adjacency(edit.from, edit.to) ?: return
                    val line = layer(edit.conduit)
                    line[edit.from.index]?.let { line[edit.from.index] = it.cutFrom(dir) }
                    line[edit.to.index]?.let { line[edit.to.index] = it.cutFrom(dir.opposite) }
                }
                is Edit.Rotate -> {
                    // Rotation: footprints square, so covered tiles unchanged — only ports move.
                    val tile = originAt(edit.tile) ?: return
                    val m = machines[tile.index]
                    if (m is Directed) machines[tile.index] = m.rotated()
                    // The same for the deck. A turned machine keeps its tiles — footprints are
                    // square — so this moves its ports and nothing else, and `set` leaves the
                    // stores and the casing exactly where they were.
                    val dm = deck[tile]
                    if (dm is DirectedDeckMachine) deck[tile] = dm.rotated()
                }
                is Edit.Remove -> when (edit.layer) {
                    // Fittings come off first, then the building under them. Peeling the track off a
                    // smelter should not also demolish the smelter, and there is no other way to
                    // reach the track once it is threaded underneath.
                    //
                    // One layer per click, because a tile can hold several and taking them all at
                    // once would remove things the player could not see they were pointing at. A
                    // player who *does* mean all of them now has a way to say so.
                    DeleteLayer.Top -> {
                        if (removeBridge(edit.tile)) return
                        for (c in Conduit.entries) if (removeConduit(edit.tile, c)) return
                        removeMachine(edit.tile)
                    }
                    DeleteLayer.Bridge -> removeBridge(edit.tile)
                    DeleteLayer.Rail -> removeConduit(edit.tile, Conduit.Rail)
                    DeleteLayer.Pipe -> removeConduit(edit.tile, Conduit.Pipe)
                    DeleteLayer.Deck -> removeMachine(edit.tile)
                    DeleteLayer.All -> {
                        removeBridge(edit.tile)
                        for (c in Conduit.entries) removeConduit(edit.tile, c)
                        removeMachine(edit.tile)
                    }
                }
                is Edit.Wire -> {
                    val tile = originAt(edit.tile) ?: return
                    val m = machines[tile.index]
                    val dm = deck[tile]
                    if (m != null) {
                        val current = m.wiring.triggers(edit.action).toMutableList()
                        when {
                            edit.trigger == null -> if (edit.slot in current.indices) current.removeAt(edit.slot)
                            edit.slot in current.indices -> current[edit.slot] = edit.trigger
                            else -> current.add(edit.trigger)
                        }
                        machines[tile.index] = m.withWiring(m.wiring.with(edit.action, current))
                    }
                    if (dm != null) {
                        val current = dm.wiring.triggers(edit.action).toMutableList()
                        when {
                            edit.trigger == null -> if (edit.slot in current.indices) current.removeAt(edit.slot)
                            edit.slot in current.indices -> current[edit.slot] = edit.trigger
                            else -> current.add(edit.trigger)
                        }
                        deck[tile] = dm.withWiring(dm.wiring.with(edit.action, current))
                    }
                }
                is Edit.BindKey -> {
                    val tile = originAt(edit.tile) ?: return
                    val m = machines[tile.index]
                    if (m is WireButton) deck[tile] = m.copy(key = edit.key)
                }
                // Accumulated (mass finalised after edit pass).
                is Edit.Thrust -> { thrustDx += edit.dx; thrustDy += edit.dy }
                is Edit.DropRock -> dropRock(edit.x, edit.y, edit.radius)
                is Edit.Inject -> inject(edit.tile, edit.mass, edit.water)
                // Recorded, never acted on here: a resize partway through a tick would leave half a
                // world on each lattice, because `Work` addresses every tile through the grid the
                // tick started on. Consumed at the very end of `reduce` — see [resized].
                is Edit.Fit -> fitRequested = true
            }
        }

        /**
         * Takes a bridge off a tile. True if there was one, which is what makes [DeleteLayer.Top]'s
         * cascade readable as "the first layer that had anything".
         */
        private fun removeBridge(tile: TileIndex): Boolean {
            val bridge = bridges.getOrNull(tile.index) ?: return false
            bridges[tile.index] = null
            scrapped(bridge.energy.total)
            return true
        }

        /** Takes one conduit layer off a tile, cutting the far halves of its joins. */
        private fun removeConduit(tile: TileIndex, c: Conduit): Boolean {
            val line = layer(c)
            line.getOrNull(tile.index) ?: return false
            if (c == Conduit.Pipe) cutOpen(tile)
            line[tile.index] = null
            scrapped(tracks.clear(c, tile))
            // Cut far halves of joins (prevent phantom connections).
            for (dir in Direction.ALL) {
                val n = grid.neighbour(tile, dir)
                if (n != TileIndex.NONE) line[n.index]?.let { line[n.index] = it.cutFrom(dir.opposite) }
            }
            return true
        }

        /** Takes the whole building out (not a slice of it). Whatever it held drops to the deck. */
        private fun removeMachine(tile: TileIndex): Boolean {
            val origin = originAt(tile) ?: return false
            val machine = machines[origin.index]
            if (machine != null) {
                for (t in coveredTiles(grid, origin, machine.kind.size)) originOf[t] = TileIndex.NONE
                scrapped(machine.energy.total)
                // Whatever was in the buffer goes with the machine — spoilsOf has already been asked
                // what falls on the floor, and holding the store open would leave a warehouse's worth
                // of iron at a tile with nothing standing on it.
                buffers.releaseRoles(grid, machine, origin)
                machines[origin.index] = null
                return true
            }
            // The deck layer is the same click. Its energy is in the dense array rather than on the
            // object, so it is read *before* the removal — `-=` zeroes the stores on its way out.
            val deckMachine = deck[origin] ?: return false
            for (t in deckMachine.tiles(grid)) originOf[t] = TileIndex.NONE
            scrapped(deckMachine.energy(grid, deck.stuff).sum())
            deck -= origin
            return true
        }

        /**
         * One tick of the debug bellows: room-temperature gas appears in a tile, and is admitted to.
         *
         * Refused where there is no room to put it — a tile inside a solid machine or outside the
         * hull has no gas volume, and filling one would be pressurising a wall. The refusal is silent
         * and books nothing, so a held button over a wall does exactly nothing rather than quietly
         * accumulating a debt.
         */
        private fun inject(tile: TileIndex, mass: Long, water: Boolean = false) {
            if (tile.index !in 0 until grid.size || mass <= 0L) return
            val occupant = originOf[tile]
            if (occupant != TileIndex.NONE && machines[occupant.index]?.kind?.isPermeable == false) return
            if (occupant != TileIndex.NONE && deck[occupant]?.kind?.isPermeable == false) return
            if (water) { injectWater(tile, mass); return }
            val shares = Stuff.AMBIENT_AIR.scaledTo(mass)
            // The parcel on its own, so its heat can be worked out from what actually arrived rather
            // than from the tile it is arriving in — that gas is already at its own temperature.
            val parcel = MassArray(1)
            var added = 0L
            // Every species, because the parcel is [AirField.AMBIENT_AIR] scaled and so already
            // contains exactly what air contains — anything else contributes zero. A hardcoded list
            // here said "air is nitrogen, oxygen and carbon dioxide", which was true until argon
            // arrived and then silently injected 987 g of every requested kilogram. This is the same
            // mistake [AirField.mixtureAt] documents: a caller that enumerates the species it thinks
            // a field holds goes quietly wrong the moment the field holds one more.
            for (s in Species.ALL) {
                val g = shares[s]
                parcel[MassIndex(TileIndex(0),s)] = g
                masses[MassIndex(tile,s)] += g
                added += g
            }
            if (added <= 0L) return
            // The heat comes in with the gas, at the temperature everything else here is. Derived
            // from the mass rather than defaulted to zero, which is [AirField.of]'s rule: gas that
            // arrived with no energy is gas at absolute zero, and it stops behaving like a gas.
            val energy = heatCapacityAt(parcel, TileIndex(0)) * Temperature.AMBIENT_KELVIN
            airEnergy[tile] += energy
            injectedAirMass += added
            injectedAirEnergy += energy
        }

        /**
         * The water injector — one species, arriving cold, booked exactly as the air injector is.
         *
         * Split out rather than folded into [inject] because the two differ in every particular
         * except the bookkeeping: one species instead of a mixture, and its own arrival temperature
         * rather than the room's, since water at room temperature is a vapour in this model. What
         * they must share is the admission — this mints matter, and `airBalance` stays honest only
         * because the same two counters are told about it.
         */
        private fun injectWater(tile: TileIndex, mass: Long) {
            val parcel = MassArray(1)
            parcel[MassIndex(TileIndex(0),Species.Water)] = mass
            masses[MassIndex(tile, Species.Water)] += mass
            val energy = heatCapacityAt(parcel, TileIndex(0)) * Edit.WATER_INJECT_KELVIN
            airEnergy[tile] += energy
            injectedAirMass += mass
            injectedAirEnergy += energy
        }

        /** Drop a body at ([x], [y]) (capture placeholder). Body heat → stored, booking → inserted. */
        private fun dropRock(x: Float, y: Float, radius: Int) {
            val half = radius * Flight.PER_TILE
            // [Edit.DropRock] carries a *grid* coordinate, because it comes from a cursor over the
            // deck. A body is stored in the world, so it is placed through the pose.
            val localX = (x * Flight.PER_TILE).toLong() - half + Flight.PER_TILE / 2L
            val localY = (y * Flight.PER_TILE).toLong() - half + Flight.PER_TILE / 2L
            val body = RigidBody.rockBlob(
                radius = radius,
                positionX = pose.toWorldX(localX, localY),
                positionY = pose.toWorldY(localX, localY),
                composition = DEFAULT_ORE_BODY,
            )
            bodies.add(body)
            built(body.energy.total)
        }

        /** Place building (click names centre, footprint grows around it). */
        private fun placeBuilding(tile: TileIndex, kind: MachineKind, facing: Direction) {
            val size = kind.size
            if (!footprintFits(grid, tile, size)) return
            val covered = coveredTiles(grid, tile, size)
            // Over anything occupied = no-op (footprint check, not just cursor tile).
            if (covered.any { originOf[it] != TileIndex.NONE }) return
            val built = newMachine(kind, facing) ?: return
            if (portsClash(portsOf(grid, built, tile))) return

            // A solid deck machine is solid — air must have somewhere to go. Last check (air, not
            // geometry). A permeable one displaces nothing and so can be laid in a sealed room.
            if (!kind.isPermeable && !tryDisplaceAir(grid, masses, airEnergy, covered) { originOf[it] == TileIndex.NONE }) return

            machines[tile.index] = built
            built(built.energy.total)
            // A storage's store is a tile in the buffer layer, reserved as the machine goes up so an
            // empty warehouse is a warehouse and not an absence. claimRole refuses a second store on
            // the tile, which is the guard for a one-tile machine wanting two roles.
            buffers.claimRoles(grid, built, tile)
            for (t in covered) originOf[t] = tile
        }

        /** Place building (click names centre, footprint grows around it). */
        private fun placeDeckBuilding(tile: TileIndex, kind: DeckMachineKind, facing: Direction, deck: DeckArray) {
            val size = kind.diameter
            if (!footprintFits(grid, tile, size)) return
            val covered = coveredTiles(grid, tile, size)
            // Over anything occupied = no-op (footprint check, not just cursor tile).
            if (covered.any { originOf[it] != TileIndex.NONE }) return
            val built = newDeckMachine(kind, tile, facing) ?: return
            if (portsClash(portsOf(grid, built))) return

            // A solid deck machine is solid — air must have somewhere to go. Last check (air, not
            // geometry). A permeable one displaces nothing and so can be laid in a sealed room.
            if (!kind.isPermeable && !tryDisplaceAir(grid, masses, airEnergy, covered) { originOf[it] == TileIndex.NONE }) return

            deck += built
            built(built.energy(grid, deck.stuff).sum())
            for (t in covered) originOf[t] = tile
        }

        /**
         * Puts a bridge down, stored at its middle tile.
         *
         * It occupies nothing, so there is no footprint to check — the **only** constraint is its
         * ports, and that is the constraint that gives bridges their shape: two of them cannot share
         * an end, and neither can a bridge end and a building's port, because a segment on that tile
         * would have no way to say which of the two it feeds.
         */
        private fun placeBridge(tile: TileIndex, facing: Direction) {
            if (bridges[tile.index] != null) return
            val built = Bridge(facing)
            val ports = portsOf(grid, built, tile)
            // Both ends have to be on the grid, or it is half a bridge.
            if (ports.size < 2) return
            if (portsClash(ports)) return
            bridges[tile.index] = built
            built(built.energy.total)
        }

        /** Any two ports of the same conduit on one tile clash. */
        private fun portsClash(proposed: List<Port>): Boolean {
            val existing = portsByTile(Conduit.Rail)
            return proposed.any { p -> existing[p.tile].orEmpty().any { it.conduit == p.conduit } }
        }

        /** The direction from [from] to [to] when the two are neighbours, else null. */
        private fun adjacency(from: TileIndex, to: TileIndex): Direction? {
            if (from.index !in rails.indices || to.index !in rails.indices) return null
            return Direction.ALL.firstOrNull { grid.neighbour(from, it) == to }
        }

        /** Draw a conduit line (both halves linked symmetrically; gauges keep channel). */
        private fun layConduit(from: TileIndex, to: TileIndex, conduit: Conduit) {
            val dir = adjacency(from, to) ?: return
            // Track and plumbing compete for the floor, so a drag that would cross the other one
            // lays nothing across that step and joins nothing. The run stops at the obstacle rather
            // than hopping it — which is the puzzle, and is what a bridge is for.
            if (spokenFor(conduit, from) || spokenFor(conduit, to)) return
            // Each layer is its own grid, so a pipe drawn across a *wire* is a crossing rather than
            // a junction — and, now, rather than nothing at all. It used to share one list with the
            // track, find a conduit mismatch, and return having laid no pipe.
            val line = layer(conduit)
            // Drawing a run lays metal wherever there was none, and that metal arrives with its
            // heat in it — booked for the same reason [Edit.Place] books it. It went unbooked while
            // a segment carried its own energy: the field defaulted to ambient and nothing had to
            // ask for it, so the drag tool quietly minted the heat of every tile it laid.
            val a = line[from.index] ?: Segment(conduit).also { built(tracks.lay(conduit, from)) }
            val b = line[to.index] ?: Segment(conduit).also { built(tracks.lay(conduit, to)) }
            line[from.index] = a.joinedTo(dir)
            line[to.index] = b.joinedTo(dir.opposite)
        }

        /** The index the machine covering [tile] is stored at, so any tile of it can be edited. */
        private fun originAt(tile: TileIndex): TileIndex? =
            if (tile == TileIndex.NONE || tile.index >= originOf.size) null else originOf[tile].takeIf { it != TileIndex.NONE }

        /**
         * Leech from whatever rock is lying on the plate at [at], and grind what has been leeched —
         * the tick's only source of ore, and the moment mass crosses from the rock ledger to the ore
         * one.
         *
         * ⚠️ **Three things leave the rock together and all three must be booked here**, because the
         * rock is not part of the vessel and each of them lands in something that is:
         *
         *  - mass, which becomes ore and moves [VesselState.extractedMass];
         *  - heat, which goes into the casing and is a *transfer* rather than work, so it is
         *    [absorb]ed and not [heat]ed — putting it through the generated-energy term would mint
         *    energy that was already in the world;
         *  - momentum, which the ship gains because the ore is now aboard and moving with it. The
         *    ship therefore hands the rock the negative of it, which is what [rockHandedX] is for.
         *
         * Only the mass of it is obvious, and the other two are exactly the kind of half-exchange
         * the ledgers exist to catch.
         */
        fun leech(m: Extractor, activation: Int, tile: TileIndex): Extractor {
            val (mass, carry) = throttled(m.massPerTick, activation, m.carry)
            // Backed up: stop working, holding whatever cell is already in the jaws. It is counted
            // as aboard either way, so nothing is forfeit by waiting.
            val held = buffers.resourceAt(bufferTile(grid, m, tile, BufferRole.Product)!!)
            if ((held?.mass ?: 0L) >= Extractor.BUFFER_CAP) return m.copy(carry = carry)

            var input = store(m, tile, BufferRole.Inside)
            if (input == null || input.mass <= 0L) {
                val found = if (activation > 0) reachedBody(m, tile) else -1
                input = if (found < 0) null else bite(found, tile)
                putStore(m, tile, BufferRole.Inside, input)
            }
            if (input == null || mass <= 0L) return m.copy(carry = carry)

            // The same shape as a processor working a lump: take a chunk off the input buffer.
            val chunk = input.mixture.take(minOf(mass, input.mass))
            if (chunk.total <= 0L) return m.copy(carry = carry)
            heat(tile, heatOfWorking(chunk.total, m))
            putStore(m, tile, BufferRole.Inside, Resource(input.form, input.mixture - chunk).orNull())
            putStore(m, tile, BufferRole.Product, Resource(Form.Ore, (held?.mixture ?: Mixture.EMPTY) + chunk))
            return m.copy(carry = carry)
        }

        /** The first body with a cell over the plate at [at], or `-1`. */
        private fun reachedBody(m: Extractor, tile: TileIndex): Int {
            val reach = m.kind.reach
            val x0 = grid.xOf(tile) - reach
            val y0 = grid.yOf(tile) - reach
            for (r in bodies.indices) {
                if (reachableCell(bodies[r], pose, x0, y0, x0 + 2 * reach, y0 + 2 * reach) >= 0) return r
            }
            return -1
        }

        /**
         * Takes one cell off body [index], which is where mass enters the ore ledger — at the body,
         * not at the belt, so that the two balances are hinged on the same number.
         */
        private fun bite(index: Int, tile: TileIndex): Resource? {
            val body = bodies[index]
            // Off the deck: an extractor is a deck machine, and asking the machine list for one
            // is a null-pointer rather than a wrong answer — which is at least loud.
            val reach = deck[tile]!!.reach
            val cell = reachableCell(
                body, pose, grid.xOf(tile) - reach, grid.yOf(tile) - reach,
                grid.xOf(tile) + reach, grid.yOf(tile) + reach,
            )
            if (cell < 0) return null
            val taken = biteCell(body, cell)
            extractedMass += taken.mass
            absorb(tile, taken.energy)
            // The body lost this; the ship gained it, so the ship gave the body the negative.
            bodyHandedX -= taken.impulseX
            bodyHandedY -= taken.impulseY
            // Booked at the extractor, not at the rock: the arm is bolted to the hull there, and
            // that is the point the reaction to hauling a cell in actually pulls on.
            bodyHandedTorque -= torqueAbout(
                about, tileCentre(grid.xOf(tile)), tileCentre(grid.yOf(tile)),
                taken.impulseX, taken.impulseY,
            )
            if (taken.body == null) bodies.removeAt(index) else bodies[index] = taken.body
            return Resource(Form.Ore, body.oreComposition!!.scaledTo(taken.mass))
        }

        /** Ports by tile (bridges folded in — indistinguishable from buildings with ports). */
        fun portsByTile(conduit: Conduit): Map<TileIndex, List<Port>> {
            val out = HashMap<TileIndex, MutableList<Port>>()
            fun add(port: Port) {
                if (port.conduit == conduit) out.getOrPut(port.tile) { mutableListOf() }.add(port)
            }
            for (i in machines.indices) {
                val m = machines[i] ?: continue
                for (port in portsOf(grid, m, TileIndex(i))) add(port)
            }
            for (i in bridges.indices) {
                val b = bridges[i] ?: continue
                for (port in portsOf(grid, b, TileIndex(i))) add(port)
            }
            // Deck machines have ports too, now that a vent is one. Visited by centre — a machine
            // covering several tiles is stored once, and adding its ports once per covered tile
            // would offer the same packet to it as many times as it is wide.
            for (i in 0 until deck.size) {
                val tile = TileIndex(i)
                val m = deck[tile] ?: continue
                if (m.center != tile) continue
                for (port in portsOf(grid, m)) add(port)
            }
            return out
        }

        /** Place output packet at port tile (ports behind buildings). Tops up partial packets where possible. */
        fun pushOut(tile: TileIndex, port: Port) {
            val segment = rails[tile.index] ?: return
            // Bridges are not ejected from here. They are conduit, so they set their load down as
            // part of the conduit step -- see [depositFromBridge].
            if (port.fromBridge) return

            // Either list — a warehouse stands on the deck, and its output port is the one that
            // most needs this: a tank that cannot push is a tank that silently swallows a line.
            val m = machines[port.owner.index] ?: deck[port.owner] ?: return
            // A storage only lets go while its RUN activation is positive, which is what turns it
            // from a bucket into a valve the moment you wire something to it.
            if (m is Storage && m.wiring.activation(Action.Run, signals.at(port.owner)) <= 0) return

            // Only as much as will actually fit: an empty tile takes a whole packet, a partial one
            // takes what tops it up.
            val room = rail.headroom(tile)
            val buffer = bufferFor(m, port) ?: return
            val (packet, rest) = takePacket(buffer, room) ?: return
            val wasEmpty = rail.isEmpty(tile)
            if (!rail.loadOnto(tile, packet.resource)) return
            drained(m, port, rest.orNull())
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
        private fun depositFromBridge(tile: TileIndex, port: Port) {
            if (rails[tile.index] == null) return
            val bridge = bridges[port.owner.index] ?: return
            val held = bridge.exit as? SolidPacket ?: return
            if (!rail.loadOnto(tile, held.resource)) return
            bridges[port.owner.index] = bridge.copy(exit = null)
        }

        /** Which of a machine's buffers drains through [port]. */
        private fun bufferFor(m: Placed, port: Port): Resource? {
            val role = outputBufferRole(m, port.stream) ?: return null
            return buffers.resourceAt(bufferTile(grid, m, port.owner, role) ?: return null)
        }

        /** Write back what is left in the buffer that drained through [port]. */
        private fun drained(m: Placed, port: Port, rest: Resource?) {
            val role = outputBufferRole(m, port.stream) ?: return
            buffers.put(bufferTile(grid, m, port.owner, role) ?: return, rest)
        }

        /** Advance all conduits one step (flow derived from input ports). */
        fun advanceRails(ports: Map<TileIndex, List<Port>>) {
            // Bridges drain first (three real slots, not one).
            for ((tile, at) in ports) for (port in at) {
                if (port.kind == PortKind.Output && port.fromBridge) depositFromBridge(tile, port)
            }

            // Bridge steps with layer (slots freed for track).
            for (tile in grid.tiles) {
                val b = bridges[tile.index] ?: continue
                if (b.conduit != Conduit.Rail) continue
                val after = b.advanced()
                bridges[tile.index] = after
                // A slot that was empty and is not now took delivery from the slot behind it, which
                // is the only way a bridge slot is ever filled from the inside.
                if (b.exit == null && after.exit != null) motion.bridgeSlotFilled(tile, Motion.SLOT_EXIT)
                if (b.middle == null && after.middle != null) motion.bridgeSlotFilled(tile, Motion.SLOT_MIDDLE)
            }

            // All input ports are sinks; machine state (full/accepting) is handled by the absorb callback.
            val sinks = ports.entries
                .filter { (tile, at) -> rails[tile.index] != null && at.any { it.kind == PortKind.Input } }
                .map { it.key }
                .toSet()
            // Sources (bridge far end gives crossing run its own direction).
            val sources = ports.entries
                .filter { (tile, at) -> rails[tile.index] != null && at.any { it.kind == PortKind.Output } }
                .map { it.key }
                .toSet()
            val railTiles = rails.mapIndexedNotNullTo(mutableSetOf()) { i, seg -> if (seg != null) TileIndex(i) else null }
            val flow = FlowGraph.build(
                railTiles,
                sources,
                sinks,
                { tile, dir -> rails[tile.index]?.linkedTo(dir) == true },
                grid,
            )

            advanceSegments(flow, rail, diverters, motion) { tile ->
                // Nothing here can take anything, so the lump is not even read off the layer. Every
                // loaded tile of every run reaches this on every step; only a handful have a port.
                val at = ports[tile]
                if (at == null || at.none { it.kind == PortKind.Input }) null
                else {
                    val packet = rail.packetAt(tile)
                    var left: Packet? = packet
                    for (port in at) {
                        if (port.kind != PortKind.Input) continue
                        val remaining = left ?: break
                        left = offerTo(port, remaining)
                        if (left == null && port.fromBridge) motion.handedToBridge(tile)
                    }
                    // A machine takes a lump whole or refuses it, so `left` is what arrived or
                    // nothing — there is no partial case to write back.
                    if (left == null && packet != null) {
                        rail.put(tile, null)
                        packet
                    } else null
                }
            }
        }

        /**
         * Gauges read whatever is standing on them once the track has finished moving.
         *
         * A gauge used to be updated by whoever put a packet down, which worked while a segment
         * carried its own load: the write and the reading were one `copy`. The load lives on
         * [RailLayer] now and the segment does not see it go by, so the reading is taken here
         * instead — after the step and after machines have pushed their output out, which is the
         * same moment the last of those writes used to happen.
         */
        fun readGauges() {
            for (i in rails.indices) {
                val segment = rails[i] ?: continue
                if (!segment.isGauge) continue
                val packet = rail.packetAt(TileIndex(i))
                rails[i] = segment.reading(packet)
            }
        }

        /** Offers a passing packet to whatever owns [port]. Returns what was not taken. */
        private fun offerTo(port: Port, packet: Packet): Packet? {
            if (port.fromBridge) {
                val bridge = bridges[port.owner.index] ?: return packet
                if (bridge.entry != null) return packet
                bridges[port.owner.index] = bridge.copy(entry = packet)
                return null
            }
            val dest = machines[port.owner.index]
            val deckDest = deck[port.owner]
            if (dest != null) {
                return if (deliver(port.owner.index, dest, packet)) null else packet
            } else if (deckDest != null) {
                return if (deliver(port.owner.index, deckDest, packet)) null else packet
            } else {
                return packet
            }
        }

        /** Take packets (limit caps to available room). */
        private fun takePacket(buffer: Resource, limit: Long = Capacity.PACKET_MASS): Pair<SolidPacket, Resource>? {
            val want = minOf(Capacity.PACKET_MASS, limit)
            if (want <= 0L || buffer.mass <= 0L) return null
            val taken = if (buffer.mass < want) buffer.mixture else buffer.mixture.take(want)
            return SolidPacket(Resource(buffer.form, taken)) to Resource(buffer.form, buffer.mixture - taken)
        }

        private fun Resource.orNull(): Resource? = if (isEmpty) null else this

        /** Puts [packet] into the accepting machine's own buffers, or refuses it. */
        private fun deliver(target: Int, destination: Machine, packet: Packet): Boolean {
            val centre = TileIndex(target)
            val role = inputBufferRole(destination) ?: return false
            val store = bufferTile(grid, destination, centre, role) ?: return false
            val merged = acceptInto(destination, buffers.resourceAt(store), packet) ?: return false
            buffers.put(store, merged)
            return true
        }

        private fun deliver(target: Int, destination: DeckMachine, packet: Packet): Boolean {
            return when (destination) {
                // Overboard, and booked as it goes: [ventedMass] is the only legitimate way for mass
                // to leave the vessel, so the ledger term and the machine's own running total move
                // together or the world stops adding up.
                is Vent -> {
                    ventedMass += packet.mass
                    deck[destination.center] =
                        destination.copy(ventedMass = destination.ventedMass + packet.mass)
                    true
                }
                // A warehouse takes any form and fills to its tank — the one kind that does, and
                // the whole of what "storage" means here. Everything else about the delivery is the
                // machine-list path's, so it is done by the same two calls.
                is Storage -> {
                    val role = inputBufferRole(destination) ?: return false
                    val store = bufferTile(grid, destination, destination.center, role) ?: return false
                    val merged = acceptInto(destination, buffers.resourceAt(store), packet) ?: return false
                    buffers.put(store, merged)
                    true
                }
                // Neither a sensor nor a button nor a pump is on the material network at all:
                // no ports, nothing to hand anything to.
                is Sensor, is WireButton, is Pump -> false
                // Both take a feed, and both take it the way every buffered kind does — by role
                // tile, kind-blind. See the machine-list twin above.
                is Vaporizer, is Thruster, is Processor, is ThermalDecomposer, is Smelter,
                is Extractor -> {
                    val role = inputBufferRole(destination) ?: return false
                    val store = bufferTile(grid, destination, destination.center, role) ?: return false
                    val merged = acceptInto(destination, buffers.resourceAt(store), packet) ?: return false
                    buffers.put(store, merged)
                    true
                }
                is Hull, is Airlock -> false
            }
        }

        /** The new input buffer if [packet] is acceptable, else null. */
        private fun acceptInto(destination: Placed, existing: Resource?, packet: Packet): Resource? {
            if (packet !is SolidPacket) return null
            // A warehouse takes any form and fills to its tank; a working machine takes ore only,
            // one lump at a time. That difference is the whole of what "storage" means here.
            if (destination !is Storage && packet.resource.form != Form.Ore) return null
            if (existing != null && existing.form != packet.form) return null
            val cap = if (destination is Storage) Storage.CAP else MACHINE_BUFFER_CAP
            if ((existing?.mass ?: 0L) >= cap) return null
            return if (existing == null) packet.resource
            else Resource(existing.form, existing.mixture + packet.contents)
        }

        /** What a packet becomes when it is tipped onto the deck. */
        private fun asResource(packet: Packet): Resource =
            Resource((packet as? SolidPacket)?.form ?: Form.Ore, packet.contents)

        private fun newMachine(kind: MachineKind, facing: Direction): Machine? = when (kind) {
            // Fittings placed directly on layers.
            MachineKind.Rail, MachineKind.Pipe, MachineKind.Gauge, MachineKind.Valve, MachineKind.Bridge,
            MachineKind.Wire -> null
        }

        private fun newDeckMachine(kind: DeckMachineKind, tile: TileIndex, facing: Direction): DeckMachine? = when (kind) {
            DeckMachineKind.Hull -> Hull(tile)
            DeckMachineKind.Airlock -> Airlock(tile)
            DeckMachineKind.Vent -> Vent(tile)
            DeckMachineKind.Storage -> Storage(tile, facing)
            DeckMachineKind.Sensor -> Sensor(tile, facing)
            DeckMachineKind.KeyInput -> WireButton(tile)
            DeckMachineKind.Pump -> Pump(tile, facing)
            DeckMachineKind.Vaporizer -> Vaporizer(tile, facing)
            DeckMachineKind.Thruster -> Thruster(tile, facing)
            DeckMachineKind.Processor -> Processor(tile, facing)
            DeckMachineKind.ThermalDecomposer -> ThermalDecomposer(tile, facing)
            DeckMachineKind.Smelter -> Smelter(tile, facing)
            DeckMachineKind.Extractor -> Extractor(tile, facing)
        }
    }
}
