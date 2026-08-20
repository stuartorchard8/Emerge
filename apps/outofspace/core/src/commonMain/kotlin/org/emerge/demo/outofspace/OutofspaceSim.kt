package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Whitelist
import org.emerge.demo.outofspace.world.Appetites
import org.emerge.demo.outofspace.world.Acceptance
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.cook
import org.emerge.demo.outofspace.chem.process
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
import org.emerge.demo.outofspace.world.machine.Valve
import org.emerge.demo.outofspace.world.machine.Gauge
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.conduitBillOfMaterials
import org.emerge.demo.outofspace.world.constructionPortOf
import org.emerge.demo.outofspace.world.constructionTileOf
import org.emerge.demo.outofspace.world.machineBillOfMaterials
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.world.buildableFrom
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.FlowCursors
import org.emerge.demo.outofspace.world.FlowGraph
import org.emerge.demo.outofspace.world.railAppetites
import org.emerge.demo.outofspace.world.railTiles
import org.emerge.demo.outofspace.world.railEnds
import org.emerge.demo.outofspace.world.railMachineGhosts
import org.emerge.demo.outofspace.world.railGhosts
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.advanceSegments
import org.emerge.demo.outofspace.world.squashOnto
import org.emerge.demo.outofspace.world.Direction
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
import org.emerge.demo.outofspace.world.footprint
import org.emerge.demo.outofspace.world.portsOf
import org.emerge.demo.outofspace.world.standingPortsOf
import org.emerge.demo.outofspace.world.diameter
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Body
import org.emerge.demo.outofspace.world.bodiesOf
import org.emerge.demo.outofspace.world.BodySlot
import org.emerge.demo.outofspace.world.machine.Airlock
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.MACHINE_BUFFER_CAP
import org.emerge.demo.outofspace.world.machine.MACHINE_OUTPUT_CAP
import org.emerge.demo.outofspace.world.Motion
import org.emerge.demo.outofspace.world.MotionLog
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
import org.emerge.demo.outofspace.world.burnCarbon
import org.emerge.demo.outofspace.world.heatCapacity
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.ThermalDecomposer
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

    /**
     * Ambient chemistry. Alongside heat rather than alongside the machines, because what gates a
     * reaction is a temperature — running it more often than the heat that drives it would only ask
     * the same question of the same numbers again.
     */
    const val CHEM_PERIOD       = 8

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
        var openness = IntArray(w.grid.size)
        var structure = StructureMap.derive(w.grid, w.deck, openness)
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
                                val target: DeckMachine? = w.deck[seen]
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
                    val g = w.deck[tile] as? Gauge ?: continue
                    raise(tile, (g.lastMass * SignalField.FULL / Capacity.PACKET_MASS).toInt())
                }
            }
            w.signals = signals

            // Signals before structure: an airlock is a wall whose solidity is a signal.
            // Edits this tick are already applied in w, so sensors/gauges still see them.
            openness = airlockOpenness(w.deck, signals) ?: IntArray(w.grid.size)
            structure = StructureMap.derive(w.grid, w.deck, openness)

            for (tile in w.grid.tiles) {
                val m : DeckMachine = w[tile] ?: continue
                // ⛔ A ghost does nothing. This is the machine's half of "a ghost refuses material it
                // cannot be built from": let a smelter run before it is paid for and the casing is a
                // formality, because the player already has everything the machine was for.
                if (w.deck.isGhost(tile)) continue
                // ⚠️ **Nor does one being taken apart**, and here that is forced rather than chosen:
                // a machine that kept running would refill the very buffers deconstruction is
                // waiting on, and it would never empty. A rail being taken apart still carries
                // traffic because carrying is not producing; this is.
                if (tile in w.scrapping) continue
                val activation = m.wiring.activation(Action.Run, signals.at(tile))
                w[tile] = when (m) {
                    // A bridge is inert like a length of track: its load is shuffled along by
                    // [advanceBridges] with the rest of the conduit step, not by running the machine.
                    // Inert: none of these is run by the tick. A gauge is read after the conduit
                    // step (see [readGauges]) and a valve is a hole, not a mechanism.
                    is Hull, is Airlock, is Vent, is Storage, is Bridge, is Gauge, is Valve,
                    is Sensor, is WireButton, is Pump -> m
                    is Thruster -> w.fire(cfg, m, activation, tile, structure)
                    is Processor -> w.refine(cfg, m, activation, tile)
                    is ThermalDecomposer -> w.refine(cfg, m, activation, tile)
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
                w[tile]?.addEnergySpread(added, w.grid, w.deck)
            }
            val bodies = bodiesOf(state.grid, w.conduitsSnapshot(), w.deck, w.buffers, w.rail)
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

        // ── Chemistry ─────────────────────────────────────────────────────────────
        //
        // After the heat, because temperature is what decides whether anything happens; before the
        // pressure and the fluid, so gas made this tick pushes and spreads in the tick it was made
        // rather than sitting still for one and appearing from nowhere in the next.
        //
        // ⚠️ **The rail layer only, and that is a ledger statement rather than a physical one.**
        // What rides a belt is cargo, so carbon leaving it is exactly what `solidBecameGas` books.
        // The deck's matter and the conduits' own metal are fabric — a different identity, with no
        // term for becoming gas — so a burning hull plate is not a thing this may do yet. See
        // [burnCarbon] and `PLAN_ambient_chemistry.md`.
        if (shouldRun(state.tick, CHEM_PERIOD)) {
            val burnt = burnCarbon(w.rail.stuff, w.masses, w.airEnergy)
            if (burnt.mass != 0L || burnt.energy != 0L) w.solidBecameGas(burnt.mass, burnt.energy)
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
                openings = valveOpenings(state.grid, conduits, w.deck),
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
        val mass = vesselMass(w.grid, w.rail, conduits, w.deck, w.buffers)

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
            w.deck,
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
            deck = w.deck,
            buffers = w.buffers,
            rail = w.rail,
            conduits = conduits,
            diverters = FlowCursors(w.diverters.snapshot(), w.diverters.mergeSnapshot()),
            tick = state.tick + 1,
            extractedMass = w.extractedMass,
            ventedMass = w.ventedMass,
            builtMass = w.builtMass,
            scrapping = w.scrapping.toSet(),
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
            impacts = bodiesDrifted.impacts,
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
    private fun blocked(vararg outputs: Mixture?): Boolean =
        outputs.any { (it?.total ?: 0L) >= MACHINE_OUTPUT_CAP }

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
    private fun Work.store(m: DeckMachine, centre: TileIndex, role: BufferRole): Mixture? =
        buffers.resourceAt(bufferTile(grid, m, centre, role)!!)

    /** Replace the [role] store of the machine at [centre], or empty it if [resource] is null. */
    private fun Work.putStore(m: DeckMachine, centre: TileIndex, role: BufferRole, resource: Mixture?) {
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
            heat(tile, heatOfWorking(fresh.total, m))
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
            heat(tile, heatOfWorking(fresh.total, m))
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
        val chunkMass = minOf(allowance, input.total)
        if (chunkMass <= 0L) return m.copy(carry = carry)

        val path = exhaustPath(grid, structure, tile, m.facing)

        heat(tile, heatOfWorking(chunkMass, m))

        // The propellant, as gas: whatever went into the chamber is what comes out of the bell —
        // as far as anything that can *be* a gas goes.
        //
        // ⚠️ **A solid fired at a bulkhead does not become atmosphere.** The chamber used to put the
        // whole chunk into the air field, whatever it was made of, which is the same thing the
        // mineral vaporizer did and the reason both are visible now that the air is a [Fluid]. What
        // cannot be a fluid still leaves the vessel — it went out of the nozzle — so it is booked
        // overboard as the solid it is and the air ledger is not told a gas appeared.
        // TODO: a thruster fed gravel should arguably refuse to fire rather than throw it away.
        //  That is an acceptance rule, not an arithmetic one, and it is Stu's call.
        val chunk = input.take(chunkMass)
        val parcel = MassArray(1) { _, f -> chunk[f.species] }
        var gaseousMass = 0L
        for (f in Fluid.ALL) gaseousMass += chunk[f.species]
        val solidMass = chunkMass - gaseousMass
        val propellantEnergy = heatCapacityAt(parcel, TileIndex(0)) * Temperature.AMBIENT_KELVIN

        // Everything standing in the plume, taken with it. A jet does not thread between the gas in
        // a corridor; it entrains it, which is why the whole path is walked and not just its ends.
        var scoopedMass = 0L
        var scoopedEnergy = 0L
        for (tile in path.path) {
            // The destination keeps what it has — the exhaust is about to be added to it.
            if (!path.isClear && tile == path.destination) continue
            masses.forEachFluid(tile) { f, held ->
                parcel.add(TileIndex(0), f, held)
                scoopedMass += held
                masses[tile, f] = 0L
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
            for (f in Fluid.ALL) masses.add(destination, f, parcel[TileIndex(0), f])
            // The jet's kinetic energy stops here and becomes heat, which is what makes firing into
            // your own bulkhead expensive rather than merely useless.
            val landed = propellantEnergy + Thruster.kineticEnergy(ejectedMass)
            airEnergy[destination] += landed + scoopedEnergy
            solidBecameGas(gaseousMass, landed)
            // Out of the nozzle and gone, without ever having been a gas. See the parcel above.
            ventedMass += solidMass
        }

        putStore(m, tile, BufferRole.Input, (input - chunk).orNull())
        return m.copy(carry = carry)
    }

/**
     * Buffer merge: what the store now holds.
     *
     * ⚠️ The nullable return is **vestigial**, and deliberately kept for now. It used to mean "these
     * two cannot coexist", which was a real answer while a store carried a form: ore pressed into a
     * buffer of ingots had to be refused, and a machine that had to stall said so this way. Nothing
     * is refusable any more — every two heaps combine — so this never answers null. It is left in
     * place because the demand work is about to give refusal a *new* meaning, and re-threading the
     * callers twice is worse than leaving the seam open once.
     */
    private class Merge(val buffer: Mixture?)

    private fun Mixture?.merged(addition: Mixture): Merge? = when {
        addition.isEmpty -> Merge(this)
        this == null || this.isEmpty -> Merge(addition)
        else -> Merge(this + addition)
    }

    private fun Mixture.orNull(): Mixture? = if (isEmpty) null else this

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
        val deck: DeckArray = state.deck.copyOf()
        val buffers: BufferLayer = state.buffers.copyOf()

        /** Everything riding on the track — see [RailLayer]. Mutated in place through the tick. */
        val rail: RailLayer = state.rail.copyOf()

        /**
         * What the networks are made of — see [TrackLayers]. Mutated in place through the tick
         * beside [layers], and handed back to [Conduits] whole at the end of it.
         */
        val tracks: TrackLayers = state.conduits.tracks.copyOf()
        operator fun get(tile: TileIndex): DeckMachine? = deck[tile]
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
        val diverters: FlowCursors = FlowCursors(state.diverters.snapshot(), state.diverters.mergeSnapshot())
        var ventedMass: Long = state.ventedMass
        var builtMass: Long = state.builtMass

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
        val about: MassDistribution = massDistribution(state.grid, state.rail, state.conduits, state.deck, state.buffers)

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
         * ⚠️ The mineral vaporizer did neither for the whole of its life — every running one drifted
         * `massBalance` down and `airBalance` up by its throughput, on every tick, and no test was
         * pointed at the machine to say so. That machine is gone, but the lesson is why this is one
         * call: two copies of it would eventually be one copy plus a caller that forgot.
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
            for (tile in grid.tiles) {
                val m = deck[tile] ?: continue
                // ⚠️ `m.tiles(grid)`, not `coveredTiles(grid, tile, diameter)`. A bridge's footprint
                // is a **line along its facing**, and the square form claimed the two tiles either
                // side of it as well — which silently stole the origin of whatever was standing
                // there, so a rotation check found the neighbouring tile free and turned onto it.
                // [Occupancy.derive] is the twin of this and already walks the footprint.
                for (t in m.tiles(state.grid)) o[t] = tile
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

        /**
         * Whether the player may conjure things rather than build them — see [VesselState.creative].
         *
         * Held because `state` is a constructor parameter and the edit path runs long after init.
         */
        val creative: Boolean = state.creative

        /** Deck machines marked for deconstruction, by centre tile — see [VesselState.scrapping]. */
        val scrapping: MutableSet<TileIndex> = state.scrapping.toMutableSet()
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
                    // Stored per tile in the deck layer, so this one is addressed by where the metal
                    // is and needs no part index at all.
                    BodySlot.DeckStore -> deck.setEnergy(body.tile, energy[i])
                    // Held matter, addressed by the tile its store stands on — same reason as above.
                    BodySlot.BufferStore -> buffers.stuff.setEnergy(body.tile, energy[i])
                    // Carried matter, addressed by the tile it is riding on. ⚠️ Guarded, unlike the
                    // two above: the rail step runs between the bodies being built and the heat
                    // being written back, so the lump this body describes may have moved on or been
                    // lifted off. Writing its energy to a tile that no longer holds it would put
                    // heat on bare track — [StuffLayer.setEnergy] allocates a row for a non-zero
                    // energy — and take it away from the lump that actually has it.
                    BodySlot.RailCargo -> if (rail.stuff.occupies(body.tile)) {
                        rail.stuff.setEnergy(body.tile, energy[i])
                    }
                    // Keyed by layer as well as tile: two fittings can stand on one tile and each
                    // has its own temperature, so `at` alone would put a pipe's heat on a rail.
                    BodySlot.Fitting -> body.conduit?.let { c ->
                        if (tracks.occupies(c, body.tile)) tracks.setEnergy(c, body.tile, energy[i])
                    }
                }
            }
        }

        // Cut pipe: release gas+heat into room (not deleted — shared ledger).
        fun cutOpen(tile: TileIndex) {
            pipeMass.forEachFluid(tile) { f, held ->
                masses.add(tile, f, held)
                pipeMass[tile, f] = 0L
            }
            airEnergy[tile] += pipeEnergy[tile]
            pipeEnergy[tile] = 0L
        }

        fun apply(edit: Edit) {
            when (edit) {
                // The one place the two kinds of placement diverge, and the only place they need
                // to — see [Brush]. Everything before this point carries one value.
                is Edit.Place -> {
                    if (edit.tile == TileIndex.NONE || edit.tile.index !in 0 until deck.size) return
                    when (val brush = edit.brush) {
                        is Brush.Run -> {
                            val c = brush.conduit
                            if (spokenFor(c, edit.tile)) return
                            if (layer(c)[edit.tile.index] == null) {
                                layer(c)[edit.tile.index] = Segment(c)
                                // In creative the metal arrives from off-world with its heat in it,
                                // and `built` books it. Otherwise it does not arrive at all: what is
                                // laid is a ghost, and it fills itself off the network.
                                if (creative) built(tracks.lay(c, edit.tile))
                            }
                        }
                        is Brush.Building -> placeDeckBuilding(edit.tile, brush.kind, edit.facing, deck)
                    }
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
                    // A turned machine keeps its tiles — footprints are
                    // square — so this moves its ports and nothing else, and `set` leaves the
                    // stores and the casing exactly where they were.
                    //
                    // ⚠️ Except a bridge, whose footprint is a line: turning it moves it off two
                    // tiles and onto two others, so the turn is *refused* when the tiles it would
                    // swing onto are not free. Demolish and rebuild is the way to move a blocked
                    // one, which is the same answer the game gives for anything else in the way.
                    val dm = deck[tile]
                    if (dm is DirectedDeckMachine) {
                        val turned = dm.rotated() as DirectedDeckMachine
                        if (canStandWhereItWouldTurn(turned, tile)) rebuildInPlace(tile, dm, turned)
                    }
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
                        // Bridge, then conduit, then the machine underneath — unchanged, and it has
                        // to be said explicitly now that a bridge is a deck machine: a bridge is
                        // *above* the track it crosses, so peeling from the top reaches it first.
                        // Left to fall through with the rest of the deck it would come off last, and
                        // clearing a bridged tile would start by lifting the rail out from under it.
                        if (deck[originAt(edit.tile) ?: edit.tile] is Bridge) {
                            if (removeMachine(edit.tile)) return
                        }
                        for (c in Conduit.entries) if (removeConduit(edit.tile, c)) return
                        removeMachine(edit.tile)
                    }
                    // A bridge is a deck machine now, so BRIDGE and DECK name the same layer. Kept
                    // as its own entry because a player pointing at a bridge means the bridge.
                    DeleteLayer.Bridge -> removeMachine(edit.tile)
                    DeleteLayer.Rail -> removeConduit(edit.tile, Conduit.Rail)
                    DeleteLayer.Pipe -> removeConduit(edit.tile, Conduit.Pipe)
                    DeleteLayer.Wire -> removeConduit(edit.tile, Conduit.Signal)
                    DeleteLayer.Deck -> removeMachine(edit.tile)
                    DeleteLayer.All -> {
                        for (c in Conduit.entries) removeConduit(edit.tile, c)
                        removeMachine(edit.tile)
                    }
                }
                is Edit.Cancel -> {
                    // Every layer at once — the mark is blind, so calling it off is blind too.
                    //
                    // ⚠️ **Nothing is restored except the target.** A machine that has already given
                    // some of its casing back is left exactly as it stands and simply stops being
                    // condemned; being short of its bill, it is now an ordinary construction site and
                    // fills itself back up off the network. One that has given nothing back holds its
                    // whole bill already and is a finished machine again the moment the mark goes.
                    // There is no matter to put back and no ledger entry, because none ever left.
                    originAt(edit.tile)?.let { scrapping.remove(it) }
                    for (c in Conduit.entries) {
                        val line = layer(c)
                        val segment = line.getOrNull(edit.tile.index) ?: continue
                        if (segment.deconstructing) line[edit.tile.index] = segment.copy(deconstructing = false)
                    }
                }
                is Edit.Wire -> {
                    val tile = originAt(edit.tile) ?: return
                    val dm = deck[tile]
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
                    val m = deck[tile]
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
         * Takes one conduit layer off a tile, cutting the far halves of its joins.
         *
         * Outside creative mode this does not take anything off: it **marks** the segment, which
         * grows it an output port and starts it handing its metal back to the network. The tile goes
         * when it is holding nothing — see [Segment.deconstructing]. Conjuring track out of nothing
         * and making it vanish into nothing are the same privilege, and they are granted together.
         */
        private fun removeConduit(tile: TileIndex, c: Conduit): Boolean {
            val line = layer(c)
            val segment = line.getOrNull(tile.index) ?: return false
            if (!creative) {
                // ⛔ **Track under a machine's port used to be locked, and is not any more.**
                //
                // The lock existed to keep the port rules tractable — a locked run can never be
                // deconstructing, so two awkward priority cases could not arise — and to stop a
                // player stranding a machine by pulling up the very thing feeding it. It bought
                // both by making the most obvious edit in the game silently do nothing, which Stu
                // hit a dozen times in one session. Protecting somebody from an edit they
                // deliberately made is not worth a rule nobody can see.
                //
                // The four states it was avoiding all turned out to be answered already, by code
                // written for other reasons:
                //  - marked rail under a machine's OUTPUT — both are sources; they contend for the
                //    tile and occupancy arbitrates, as two sources always have;
                //  - marked rail under a machine's INPUT — the machine eats what the rail hands
                //    back, which is where the metal was going anyway;
                //  - ghost rail under a machine's INPUT — the ghost eats first, stated outright at
                //    the tick's one consume site;
                //  - ghost rail under a machine's OUTPUT — the machine feeds it, which is how a
                //    tank builds the track at its own port and always was.
                //
                // ⚠️ A machine whose feed is pulled up is now simply disconnected. That is the same
                // answer the game gives for anything else in the way, and it is reversible.
                if (segment.deconstructing) return true
                line[tile.index] = segment.copy(deconstructing = true)
                return true
            }
            dropConduit(tile, c)
            return true
        }

        /**
         * Takes the segment off the tile for good, cutting the far halves of its joins.
         *
         * Two callers with opposite ledgers: the creative delete, where the metal vanishes and
         * `scrapped` books it as leaving the world, and a segment that has finished handing its
         * metal back, where there is nothing left to book. [TrackLayers.clear] answers zero for the
         * second, so one call serves both — the deconstruction path takes the last of the energy out
         * with the last of the mass precisely so that stays true.
         */
        private fun dropConduit(tile: TileIndex, c: Conduit) {
            val line = layer(c)
            if (c == Conduit.Pipe) cutOpen(tile)
            line[tile.index] = null
            scrapped(tracks.clear(c, tile))
            // Cut far halves of joins (prevent phantom connections).
            for (dir in Direction.ALL) {
                val n = grid.neighbour(tile, dir)
                if (n != TileIndex.NONE) line[n.index]?.let { line[n.index] = it.cutFrom(dir.opposite) }
            }
        }

        /** Takes the whole building out (not a slice of it). Whatever it held drops to the deck. */
        private fun removeMachine(tile: TileIndex): Boolean {
            val origin = originAt(tile) ?: return false
            if (!creative) {
                // Outside creative this **marks** rather than removes, exactly as deleting a rail
                // does: the machine grows an output, hands back what it is holding and then what it
                // is made of, and goes when it holds nothing. Conjuring a machine out of nothing and
                // making one vanish into nothing are the same privilege and are granted together.
                scrapping.add(origin)
                return true
            }
            dropMachine(origin)
            return true
        }

        /**
         * Takes the machine off the deck for good.
         *
         * Two callers with opposite ledgers, the same pair [dropConduit] has: the creative delete,
         * where the casing vanishes and `scrapped` books it as leaving the world, and a machine that
         * has finished handing itself back, where there is nothing left to book — its stores and its
         * casing are already empty, so the energy read here is zero.
         *
         * ⚠️ **The mark goes with it.** A mark keyed by tile would otherwise outlive the machine and
         * condemn whatever was built there next.
         */
        private fun dropMachine(tile: TileIndex): Boolean {
            val origin = originAt(tile) ?: return false
            // Energy is in the layer rather than on the object, so it is read *before* the removal —
            // `-=` zeroes the stores on its way out.
            val deckMachine = deck[origin] ?: return false
            for (t in deckMachine.tiles(grid)) originOf[t] = TileIndex.NONE
            scrapped(deckMachine.energy(grid, deck.stuff).sum())
            // The stores come down with the building, for the reason the machine list's do: a store
            // left standing at a tile with nothing on it is a warehouse's worth of iron nobody can
            // reach, and it still counts toward the vessel's mass.
            buffers.releaseRoles(grid, deckMachine, origin)
            deck -= origin
            scrapping.remove(origin)
            return true
        }

        /**
         * Whether [turned] would fit if the machine at [centre] were rotated to it.
         *
         * Only a span can fail this — every square footprint covers the same tiles whichever way it
         * points — but it is asked of everything, because "check the shape you would become" needs
         * no exception and a rule with an exception in it grows a second one.
         */
        private fun canStandWhereItWouldTurn(turned: DeckMachine, centre: TileIndex): Boolean {
            val after = turned.kind.footprint(centre, grid, (turned as? DirectedDeckMachine)?.facing ?: Direction.Right)
                ?: return false
            // Its own tiles do not count as in the way: it is standing on them already.
            return after.all { originOf[it] == TileIndex.NONE || originOf[it] == centre }
        }

        /**
         * Swaps the machine at [centre] for [turned], moving its casing and its stores with it.
         *
         * A demolish-and-rebuild rather than `deck[tile] = turned`, because a span's tiles change:
         * `set` deliberately leaves the matter alone, which is right for a machine that stays where
         * it is and would strand a bridge's casing on the tiles it just swung off. Booked through
         * neither ledger — no metal arrives and none is scrapped, it is the same bridge — so the
         * energy is carried across by hand.
         */
        private fun rebuildInPlace(centre: TileIndex, before: DeckMachine, turned: DeckMachine) {
            val carried = before.tiles(grid).sumOf { deck.stuff.energyAt(it) }
            for (t in before.tiles(grid)) originOf[t] = TileIndex.NONE
            val held = BufferRole.entries.mapNotNull { role ->
                val tile = bufferTile(grid, before, centre, role) ?: return@mapNotNull null
                buffers.resourceAt(tile)?.let { role to it }
            }
            buffers.releaseRoles(grid, before, centre)
            deck -= centre
            deck += turned
            buffers.claimRoles(grid, turned, centre)
            for (t in turned.tiles(grid)) originOf[t] = centre
            for ((role, held1) in held) {
                bufferTile(grid, turned, centre, role)?.let { buffers.put(it, held1) }
            }
            // Put the heat back where the metal went. Spread evenly: the tiles are not the same
            // tiles, so there is no per-tile correspondence to preserve, and a bridge is one object.
            val tiles = turned.tiles(grid)
            val each = carried / tiles.size
            for (t in tiles) deck.stuff.setEnergy(t, each)
            deck.stuff.addEnergy(centre, carried % tiles.size)
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
            if (occupant != TileIndex.NONE && deck[occupant]?.kind?.isPermeable == false) return
            if (water) { injectWater(tile, mass); return }
            val shares = Stuff.AMBIENT_AIR.scaledTo(mass)
            // The parcel on its own, so its heat can be worked out from what actually arrived rather
            // than from the tile it is arriving in — that gas is already at its own temperature.
            val parcel = MassArray(1)
            var added = 0L
            // Every *fluid*, because the parcel is [Stuff.AMBIENT_AIR] scaled and so already
            // contains exactly what air contains — anything else contributes zero. A hardcoded list
            // here said "air is nitrogen, oxygen and carbon dioxide", which was true until argon
            // arrived and then silently injected 987 g of every requested kilogram. This is the same
            // mistake [Stuff.mixtureAt] documents: a caller that enumerates the species it thinks
            // a field holds goes quietly wrong the moment the field holds one more. [Fluid.ALL] is
            // the whole of what this field can hold, so it cannot fall behind that way again.
            for (f in Fluid.ALL) {
                val g = shares[f.species]
                parcel[TileIndex(0), f] = g
                masses.add(tile, f, g)
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
            parcel[TileIndex(0), Fluid.Water] = mass
            masses.add(tile, Fluid.Water, mass)
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

        /**
         * Place building (click names centre, footprint grows around it).
         *
         * The footprint comes from the machine rather than from `coveredTiles(grid, tile, size)`,
         * because a bridge's is a **line along its facing** and a square would have it claim two
         * tiles it does not stand on. `tiles(grid)` is the one answer both shapes give.
         */
        private fun placeDeckBuilding(tile: TileIndex, kind: DeckMachineKind, facing: Direction, deck: DeckArray) {
            val built = newDeckMachine(kind, tile, facing) ?: return
            // Null means it hangs off the grid — half a bridge, or a smelter over the rim.
            val covered = (kind.footprint(tile, grid, facing) ?: return).toList()
            // Over anything occupied = no-op (footprint check, not just cursor tile).
            if (covered.any { originOf[it] != TileIndex.NONE }) return
            if (portsClash(portsOf(grid, built))) return

            // A solid deck machine is solid — air must have somewhere to go. Last check (air, not
            // geometry). A permeable one displaces nothing and so can be laid in a sealed room.
            //
            // ⚠️ **A ghost is refused on the same terms and displaces nothing.** It has no metal to
            // push air aside with, so the room it stands in is unchanged until it is finished — but
            // the restriction still governs where it may be put, or a player would draw a frame in a
            // sealed room and be told only at completion that it could never have been built there.
            // The displacement happens when the casing does.
            if (!kind.isPermeable &&
                !tryDisplaceAir(grid, masses, airEnergy, covered, commit = creative) { originOf[it] == TileIndex.NONE }
            ) return

            // Outside creative the machine arrives as a ghost: standing there, made of nothing, and
            // nothing is booked because nothing came from off-world. See [DeckArray.stand].
            deck.stand(built, withCasing = creative)
            if (creative) built(built.energy(grid, deck.stuff).sum())
            // The stores go up with the building: an empty tank is a tank and not an absence, and a
            // bridge's three slots have to exist before anything can be set down in one.
            buffers.claimRoles(grid, built, tile)
            for (t in covered) originOf[t] = tile
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
            // Drawing a run lays metal wherever there was none, and in creative that metal arrives
            // from off-world with its heat in it — booked for the same reason [Edit.Place] books it.
            // It went unbooked while a segment carried its own energy: the field defaulted to
            // ambient and nothing had to ask for it, so the drag tool quietly minted the heat of
            // every tile it laid. Outside creative nothing arrives and the drag lays ghosts.
            fun raise(tile: TileIndex): Segment {
                if (creative) built(tracks.lay(conduit, tile))
                return Segment(conduit)
            }
            val a = line[from.index] ?: raise(from)
            val b = line[to.index] ?: raise(to)
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
            // Backed up: stop biting. What is not taken is still in the rock, which is a better
            // place for it than a buffer — nothing is forfeit by waiting.
            val held = buffers.resourceAt(bufferTile(grid, m, tile, BufferRole.Product)!!)
            if ((held?.total ?: 0L) >= Extractor.BUFFER_CAP) return m
            if (activation <= 0) return m

            // ⚠️ **A bite goes straight into the store it leaves from.** It used to land in a second
            // buffer and be ground across at a rate; the rate was unobservable, because a belt tile
            // holds one packet and a machine hands over one packet a tick, so what leaves is capped
            // by the rail whatever happens in here. What the second store really bought was a way to
            // meet a rock measured in whole cells with a rate measured in mass — and the buffer cap
            // does that on its own, one cell at a time.
            val found = reachedBody(m, tile)
            if (found < 0) return m
            val bitten = bite(found, tile) ?: return m
            if (bitten.total <= 0L) return m
            heat(tile, heatOfWorking(bitten.total, m))
            putStore(m, tile, BufferRole.Product, (held ?: Mixture.EMPTY) + bitten)
            return m
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
        private fun bite(index: Int, tile: TileIndex): Mixture? {
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
            return body.oreComposition!!.scaledTo(taken.mass)
        }

        /**
         * A segment marked for deconstruction hands its metal back to the network, a packet at a
         * time, and ceases to be once it is holding nothing.
         *
         * The mirror of [absorbIntoGhost] and deliberately the same shape: a ghost is an input
         * because it is short of its bill, and this is an output because it has been told to be. It
         * needs no port either — its own tile is the address.
         *
         * ⚠️ **It is still track while it does this.** A marked run carries traffic to the end, which
         * is what lets a player walk a rail across the grid: draw ghosts ahead, mark the old tiles
         * behind, and the same atoms travel down the line to the new ones.
         *
         * Ceasing to be needs *both* halves empty — the structure and the lump standing on it — or a
         * tile would vanish under a packet and take it with it.
         */
        fun scrapDeconstructing(whitelist: Whitelist) {
            val line = layer(Conduit.Rail)
            for (i in line.indices) {
                val tile = TileIndex(i)
                val segment = line[i] ?: continue
                if (!segment.deconstructing) continue
                val stuff = tracks[Conduit.Rail]
                val held = stuff.massAt(tile)
                if (held > 0L) {
                    // ⚠️ **Nothing comes apart with nowhere to put it.** A rail that dumped its metal
                    // onto a run no consumer is on would jam that run and then be unable to hand back
                    // the rest, having stranded itself behind its own leavings. Waiting instead makes
                    // the refusal *reversible*: build somewhere for it to go and the rail resumes.
                    val giving = stuff.mixtureAt(tile)
                    if (!whitelist.permits(tile, giving)) continue
                    // ⚠️ **And no more of it than is wanted**, which is the same rule [pushOut]
                    // follows one step earlier and for the same reason: a bill is not a round number
                    // of packets. A rail coming apart put a *whole* packet down for a neighbour
                    // short of a fraction of one, and the difference stood on the track for ever —
                    // a residue that comes to rest exactly in front of the material that would
                    // finish the job. Stu found it, a marked rail beside a quarter-built one.
                    val useful = whitelist.room(tile, giving)
                    if (useful <= 0L) continue
                    val room = minOf(rail.headroom(tile), useful)
                    if (room > 0L) {
                        val take = minOf(held, room)
                        // ⛔ **One proportional slice, not a slice per species.** [Mixture.take]
                        // apportions off a *running* total, so the parts sum to exactly what was
                        // asked for; scaling each species on its own truncates each one separately
                        // and the sum comes up short by up to a microgram per species present.
                        //
                        // A microgram is not a rounding detail here, it is a deadlock. The site was
                        // short of `take` and got `take − 2`, so it stops two micrograms below a
                        // bill it can now never reach and reads 99% for ever. The next tick's
                        // request is for those two micrograms alone — and two micrograms scaled
                        // against thirty kilograms of remaining rail truncates to nought at every
                        // species, so nothing is handed back at all, every tick, for good. Stu's
                        // save: a ghost rail at 99% beside a marked rail frozen at 23%, and 94
                        // consecutive ticks of asking for one microgram and being given none.
                        //
                        // It leaves a residue at the other end too, the same microgram wearing the
                        // other sign: a site left one short asks for one less next time, so the
                        // packet that arrives is one *more* than it needs and the difference is put
                        // down as a lump — 1ug of iron standing on finished track, invisible to
                        // every readout that prints grams, and a permanent blockage because packets
                        // never merge.
                        //
                        // Taking the lot takes the heat with it, so a finished tile is genuinely
                        // empty and [dropConduit] has nothing to book. A part-load takes its share:
                        // [Mixture.take] carries the energy in the same proportion, and returns the
                        // mixture itself when the whole of it is asked for.
                        val recovered = giving.take(take)
                        // ⛔ **Asked again of the slice, not just of the pile.** The gate above
                        // asks whether what this rail holds is wanted; that is not the same
                        // question as whether what it is about to *hand over* can be accepted, and
                        // the two answers part company at small draws. A proportional slice of a
                        // contaminated blend is only representative while it is big enough to carry
                        // every species: take one microgram off track that is 98% iron, 1% titanium
                        // and a trace of carbon and the apportionment puts that single microgram on
                        // whichever species the running total happens to land on — so the rail mints
                        // a speck of **pure carbon**, which no iron ghost will admit and nothing on
                        // the network can use.
                        //
                        // It is worse than handing back nothing. Packets never merge, so the speck
                        // owns the tile it lands on for good and the corridor behind it is dead.
                        // Stu's save: `(20,31)` asked for one microgram, minted 1ug of carbon on
                        // the first rail tick, and blocked its own belt.
                        //
                        // Refusing is the wait this pass already has, and it is reversible in the
                        // same way: the rail stays marked and tries again, and the moment somebody
                        // wants enough of it for a slice to look like the pile, it goes.
                        if (!whitelist.permits(tile, recovered)) continue
                        val energy = recovered.energy
                        val moved = recovered.total
                        if (moved > 0L) {
                            // ⚠️ **Nothing leaves the structure layer until the lump has taken it.**
                            // The tile having room is not the same question as the lump accepting
                            // it: an ingot never merges with the ore riding over it, whatever the
                            // headroom, and taking the metal out first destroyed it — a rail
                            // deconstructed under passing traffic came back light. So the deposit is
                            // attempted first and the deduction is what a *successful* one books.
                            //
                            // Refused, the segment simply hands nothing back this tick and stays
                            // marked; it is the occupied-tile wait the feature already has, not a
                            // new one. `take <= room` means the deposit is whole or nothing, so a
                            // false answer here has written nothing.
                            val onto = rail.resourceAt(tile)
                            val landed =
                                if (onto == null) { rail.put(tile, recovered); true }
                                else rail.loadOnto(tile, recovered)
                            if (landed) {
                                for (sp in Species.ALL) {
                                    val part = recovered[sp]
                                    if (part != 0L) stuff[tile, sp] = stuff[tile, sp] - part
                                }
                                stuff.setEnergy(tile, stuff.energyAt(tile) - energy)
                                builtMass -= moved
                            }
                        }
                    }
                }
                if (stuff.massAt(tile) == 0L && rail.isEmpty(tile)) dropConduit(tile, Conduit.Rail)
            }
        }

        /**
         * A deck machine marked for deconstruction comes apart, in the order it has to come apart in.
         *
         * The deck's twin of [scrapDeconstructing], and the ordering is the design rather than an
         * implementation detail:
         *
         * 1. **Its output ports drain as normal.** A machine's product is still its product and
         *    leaves the way it always did — [portsByTile] keeps those ports, so this needs no code.
         * 2. **Its input port hands back** whatever is sitting in the input buffer, onto the belt
         *    that fed it. Its inputs are gone as *ports* (nothing new comes in), so the handing back
         *    is done here, at the tile the port stood on.
         * 3. **Each of those stops the moment its own store is clear.** A store with nothing behind
         *    it is not a place anything leaves from.
         * 4. **Only when every other store is clear** does the centre port start handing back the
         *    **casing**, a packet at a time. Casing is the one thing whose removal cannot be undone,
         *    so it is the last thing done — that ordering is what stops a machine's contents being
         *    destroyed by its own demolition.
         * 5. It ceases to be when its stores and its casing are all empty.
         *
         * ⚠️ **Nothing leaves until the belt has taken it**, which is the lesson `4e8d377` cost:
         * offering first and deducting what a *successful* offer books. A refusal is ordinary — an
         * ingot will not merge with the ore riding over it however much headroom there is — and a
         * refused machine simply hands nothing back this tick and stays marked.
         *
         * ⚠️ A machine with **no track** at the tile it wants to hand back through waits for ever.
         * That is the same occupied-tile family as the rails' open question, not a new one.
         */
        fun scrapMachines(whitelist: Whitelist) {
            if (scrapping.isEmpty()) return
            for (centre in scrapping.toList()) {
                val m = deck[centre] ?: run { scrapping.remove(centre); return@run null } ?: continue

                // Steps 2 and 3: the stores, each at the tile its own port stood on. Product and
                // waste are drained by their ports and are only *waited* on here.
                // ⚠️ A store that **something else already drains** is left to it. Otherwise two
                // mouths empty one store in a tick — harmless for the ledger, since each deducts
                // what it takes, but the machine comes apart at twice the rate it appears to and the
                // same cargo goes down in two places. Two cases, and both are real:
                //
                //  - A store an output port drains. A `Storage` keeps only an `Inside` and that *is*
                //    what its output drains, so a tank empties by its natural exit and this waits.
                //  - **A bridge's slots**, which [advanceBridges] shuffles along whether or not the
                //    bridge is marked — it is part of the conduit step, not of running a machine —
                //    so a marked gantry walks its load out of the far end as it always did.
                val drainedElsewhere = HashSet<BufferRole>()
                for (stream in Stream.entries) outputBufferRole(m, stream)?.let { drainedElsewhere.add(it) }
                if (m is Bridge) drainedElsewhere.addAll(BufferRole.entries)

                var storesHeld = 0L
                var handedBack = false
                for (role in BufferRole.entries) {
                    val store = bufferTile(grid, m, centre, role) ?: continue
                    val held = buffers.resourceAt(store) ?: continue
                    if (held.total <= 0L) continue
                    storesHeld += held.total
                    if (role in drainedElsewhere) continue
                    val onto = handBackTileFor(m, centre, role, store)
                    // Nowhere for it to go is a reason to wait, not a reason to dump. See the twin
                    // guard in [scrapDeconstructing].
                    if (!whitelist.permits(onto, held)) continue
                    if (handBack(whitelist, onto, held, store)) handedBack = true
                }
                if (storesHeld > 0L || handedBack) continue

                // Step 4: the casing, only now that the machine is empty.
                //
                // Out of the same mouth it was built through — its centre, or for a bridge the end
                // it puts material down at, which is where a run leaving it already goes.
                val tiles = m.tiles(grid)
                val out = if (m is Bridge) {
                    portsOf(grid, m).firstOrNull { it.kind == PortKind.Output }?.tile ?: centre
                } else {
                    centre
                }
                // The casing has the same rule as the stores: a machine does not shed its own
                // fabric onto a network that cannot take it.
                var casing = Mixture.EMPTY
                for (t in tiles) casing += deck.stuff.mixtureAt(t)
                if (casing.total > 0L && !whitelist.permits(out, casing)) continue
                if (!handCasingBack(out, tiles)) continue

                // Step 5.
                if (tiles.all { deck.stuff.massAt(it) == 0L }) dropMachine(centre)
            }
        }

        /**
         * Where a store's contents are put down when the machine is being taken apart.
         *
         * ⛔ **A processing buffer goes back out through the input port** — Stu, 2026-08-19. A
         * `Processor` or a `ThermalDecomposer` holds a lump *in the middle of being worked*, and that
         * lump has no business leaving by an output: the output is for finished goods, and what is
         * in there is not finished. The way it came in is the honest way back out.
         *
         * The store itself sits at the machine's centre — see [localBufferOffset] — so this is a
         * deliberate divergence between where a store *is* and where its contents are handed back,
         * and it is the only one.
         *
         * Every machine that keeps a working store has an input port to give it back through: a
         * `Processor` and a `ThermalDecomposer` are the two, and an `Extractor` used to be a third
         * until its two stores became one. A `Storage` never reaches here at all — its `Inside` is
         * what its own output port drains, so the tank empties itself the way it always did.
         */
        private fun handBackTileFor(
            m: DeckMachine,
            centre: TileIndex,
            role: BufferRole,
            store: TileIndex,
        ): TileIndex {
            if (role != BufferRole.Inside) return store
            return bufferTile(grid, m, centre, BufferRole.Input) ?: store
        }

        /**
         * Puts a store's contents down on the track at [onto], and empties [store] if it lands.
         *
         * The two are the same tile for everything except a processing buffer — see
         * [handBackTileFor], which is the one place they come apart.
         *
         * Answers whether anything moved, so the caller can wait a tick rather than moving on to the
         * casing while a store is still emptying.
         *
         * ⛔ **[whitelist] is handed in, and must be.** This runs from [scrapMachines], which is
         * called *before* the tick publishes its whitelist to the field of the same name — so a bare
         * `whitelist` here reads last tick's answer, and on the first tick of a deconstruction reads
         * [Whitelist.empty], which permits nothing anywhere. The store then never came back out and
         * the machine sat on it for ever, looking exactly like a demand rule correctly refusing to
         * strand cargo. It is the same object [scrapDeconstructing] is given, one method along.
         */
        private fun handBack(
            whitelist: Whitelist,
            onto: TileIndex,
            held: Mixture,
            store: TileIndex,
        ): Boolean {
            if (rails[onto.index] == null) return false
            val useful = whitelist.room(onto, held)
            if (useful <= 0L) return false
            val room = minOf(rail.headroom(onto), useful)
            if (room <= 0L) return false
            val take = minOf(held.total, room)
            val moving = if (take >= held.total) held else held.take(take)
            if (moving.total <= 0L) return false
            val offered = moving
            val landed =
                if (rail.resourceAt(onto) == null) { rail.put(onto, offered); true }
                else rail.loadOnto(onto, offered)
            if (!landed) return false
            buffers.put(store, (held - moving).takeIf { it.total > 0L })
            return true
        }

        /**
         * Hands one packet of casing back onto the track at the machine's centre tile.
         *
         * Answers whether the machine is now empty of casing — `true` also when there was nothing
         * left to hand back, which is how a ghost marked for deconstruction goes straight out: it is
         * exactly a partially-built machine, it dumps what it has, and it needs no special case.
         *
         * Taken **evenly off the footprint**, which is how it went on. A machine whose casing came
         * off one tile at a time would drift through the heat solver as a half-thing.
         */
        private fun handCasingBack(centre: TileIndex, tiles: Array<TileIndex>): Boolean {
            var held = 0L
            for (t in tiles) held += deck.stuff.massAt(t)
            if (held <= 0L) return true
            if (rails[centre.index] == null) return false
            val room = rail.headroom(centre)
            if (room <= 0L) return false

            val take = minOf(held, room)
            // ⛔ **One proportional slice over the whole footprint** — see [scrapDeconstructing],
            // which had the identical hand-rolled draw and the identical microgram deadlock. Scaling
            // each species on its own truncates each one separately, so the parts come up short of
            // what was asked for and the site on the other end stops just below its bill, asking
            // for a remainder too small to survive the next truncation.
            val masses = LongArray(Species.COUNT)
            var energy = 0L
            for (t in tiles) {
                for (sp in Species.ALL) masses[sp.ordinal] += deck.stuff[t, sp]
                energy += deck.stuff.energyAt(t)
            }
            val recovered = Mixture.of(masses, energy).take(take)
            val moved = recovered.total
            if (moved <= 0L) return false
            val movedEnergy = recovered.energy

            // The deposit first, the deduction only if it lands — see the note on this pass.
            val landed =
                if (rail.resourceAt(centre) == null) { rail.put(centre, recovered); true }
                else rail.loadOnto(centre, recovered)
            if (!landed) return false

            for (sp in Species.ALL) {
                val part = recovered[sp]
                if (part == 0L) continue
                takeEvenlyOffFootprint(tiles, sp, part)
            }
            takeEnergyEvenlyOffFootprint(tiles, movedEnergy)
            builtMass -= moved
            var left = 0L
            for (t in tiles) left += deck.stuff.massAt(t)
            return left == 0L
        }

        /** Whether any of a machine's stores has anything in it — a bridge's three slots, in practice. */
        private fun holdsAnything(m: DeckMachine, centre: TileIndex): Boolean {
            for (role in BufferRole.entries) {
                val store = bufferTile(grid, m, centre, role) ?: continue
                if (buffers.massAt(store) > 0L) return true
            }
            return false
        }

        /** The mirror of [spreadOverFootprint]: takes [mass] of [species] evenly back off the tiles. */
        private fun takeEvenlyOffFootprint(tiles: Array<TileIndex>, species: Species, mass: Long) {
            var owed = mass
            // Bounded by what each tile actually holds, so an uneven footprint — one tile short
            // because a reaction ate part of it — cannot be driven negative by an even division.
            for (t in tiles) {
                if (owed <= 0L) break
                val here = deck.stuff[t, species]
                if (here <= 0L) continue
                val part = minOf(here, owed)
                deck.stuff[t, species] = here - part
                owed -= part
            }
        }

        private fun takeEnergyEvenlyOffFootprint(tiles: Array<TileIndex>, energy: Long) {
            var owed = energy
            for (t in tiles) {
                if (owed <= 0L) break
                val here = deck.stuff.energyAt(t)
                if (here <= 0L) continue
                val part = minOf(here, owed)
                deck.stuff.setEnergy(t, here - part)
                owed -= part
            }
        }

        /** Whichever species there is most of, for naming what a demolished thing comes back as. */
        private fun dominantSpecies(mixture: Mixture): Species? {
            var best: Species? = null
            var most = 0L
            for (sp in Species.ALL) {
                val mass = mixture[sp]
                if (mass > most) { most = mass; best = sp }
            }
            return best
        }

        /**
         * A ghost takes what it still needs off the lump standing on it, and books the transfer.
         *
         * Returns the whole packet when the tile is left empty — the contract [advanceSegments]
         * already had — and null when a remainder rides on. The remainder is the reason this is not
         * a machine's port: a machine takes a lump whole or refuses it, while a ghost skims what it
         * needs and lets the rest past, over what is by then real track.
         *
         * ### Proportionally, across everything in the lump
         *
         * Not "pick the iron out". A delivery is admitted whole or not at all — see [buildableFrom]
         * — so what came with the iron is part of what is being built with, and taking a fraction of
         * every species is what keeps the tile's composition equal to what it was actually fed.
         *
         * ⚠️ That means junk **dilutes**: a ghost fed 95% iron spends 5% of its appetite on
         * something that is not iron and needs a little more material to finish. That is the cost of
         * the slack and it still terminates, because every delivery adds iron and the shortfall only
         * ever shrinks.
         *
         * ⚠️ The heat comes with the mass, so a rail built out of cold iron is a cold rail. It moves
         * from the transport layer to the structure layer, and **the transport layer's energy is not
         * in [VesselState.storedEnergy]** — that is a gap older than this and the energy ledger is
         * parked, so nothing here books it. Written down rather than papered over.
         */
        fun absorbIntoGhost(tile: TileIndex): Packet? {
            val bill = conduitBillOfMaterials(Conduit.Rail)
            val stuff = tracks[Conduit.Rail]
            // ⛔ **The door, asked of what is standing here rather than of what is coming in.**
            //
            // Every lump *entering* a tile is weighed against the site's bill, and that was taken to
            // be the whole story until a save turned up a length of track reading 56% iron and 43%
            // titanium — the full bill, plus one whole packet of somebody else's cargo, welded into
            // the fabric. It had been a storage; the storage went; its titanium stayed standing on
            // the belt; and the tile under it became a construction site. Nothing pushed anything
            // anywhere. A tile can *become* a ghost beneath a lump that was already there — take a
            // marked run, let it hand some metal back, then CANCEL it — and the entry check has
            // nothing to say about that.
            //
            // ⚠️ Contamination is permanent and silent. The tile then reads as ordinary finished
            // track, and the first thing anyone notices is that it will not come apart: what it is
            // made of is not something the next ghost down the line can be built from, so nothing on
            // the network wants its metal back. Asked here, it cannot matter how the lump arrived.
            val standing = rail.resourceAt(tile) ?: return null
            if (!buildableFrom(bill, standing)) return null
            val need = bill.total - stuff.massAt(tile)
            if (need <= 0L) return null
            val have = rail.massAt(tile)
            if (have <= 0L) return null

            // The whole lump goes in. Taking it wholesale rather than by fraction keeps the common
            // case exact — a fraction of a fraction is where the rounding would live.
            if (have <= need) {
                val packet = rail.packetAt(tile) ?: return null
                for (sp in Species.ALL) {
                    val mass = packet.contents[sp]
                    if (mass != 0L) stuff[tile, sp] = stuff[tile, sp] + mass
                }
                stuff.setEnergy(tile, stuff.energyAt(tile) + packet.contents.energy)
                rail.put(tile, null)
                builtMass += have
                return packet
            }

            // More than it needs: the top-up, and the rest rides on.
            //
            // ⚠️ **A proportional slice, which is now simply what a top-up is.** A site short of
            // `need` grams of matter takes `need` grams of what is standing on it, in the blend it
            // arrived in — the same arithmetic as every other partial draw on the vessel, and the
            // same blend the swallow-it-whole branch above gives it for every delivery but the last.
            //
            // ⚠️ **The junk goes with it, at the rate the metal goes**, and that has not changed:
            // taking only what is billed *concentrates* whatever is not, so a run built out of hull
            // salvage — steel, 99 parts iron to 1 of carbon — stripped the iron off every delivery
            // and precipitated the carbon, until 181g of the pure stuff stood on a tile too far off
            // any bill for anything on the vessel to use. Found in Stu's save. Building with 99%
            // pure material must not leave a 0% pure residue. What used to arrange that by hand,
            // species by species, a proportional slice arranges by construction.
            val lump = standing
            val taking = lump.take(need)
            for (sp in Species.ALL) {
                val mass = taking[sp]
                if (mass != 0L) stuff[tile, sp] = stuff[tile, sp] + mass
            }
            stuff.setEnergy(tile, stuff.energyAt(tile) + taking.energy)
            val remainder = lump - taking
            if (remainder.isEmpty) rail.put(tile, null) else rail.put(tile, remainder)
            builtMass += taking.total
            return null
        }

        /**
         * How much matter the ghost machine [m] is still short of.
         *
         * Summed over the whole footprint, because casing spreads evenly as it arrives rather than
         * completing tiles one at a time — see [DeckArray.holdsFullBill]. A mass rather than a
         * per-species shortfall, because that is what a site is short of: composition is the door's
         * business and was settled before any of this arrived.
         */
        private fun machineShortfall(m: DeckMachine): Long {
            val tiles = m.tiles(grid)
            val bill = machineBillOfMaterials(m.kind, tiles.size)
            var held = 0L
            for (t in tiles) held += deck.stuff.massAt(t)
            return bill.total - held
        }

        /**
         * Puts [mass] of [species] into a machine's casing, **spread evenly over its tiles**.
         *
         * The remainder of the division lands on the centre tile, which is where
         * [DeckMachine.addEnergySpread] puts its own and for the same reason: somewhere has to take
         * it, and the centre is the tile every machine has.
         *
         * Spread rather than piled up because every per-tile reader — [SolidHeat], [Flight],
         * chemistry — believes what it finds on a tile. Pool nine tiles of iron on one and the heat
         * solver conducts as though the furnace were a bar.
         */
        private fun spreadOverFootprint(tiles: Array<TileIndex>, species: Species, mass: Long) {
            if (mass <= 0L) return
            val each = mass / tiles.size
            if (each > 0L) for (t in tiles) deck.stuff[t, species] = deck.stuff[t, species] + each
            val rest = mass - each * tiles.size
            if (rest > 0L) deck.stuff[tiles[0], species] = deck.stuff[tiles[0], species] + rest
        }

        private fun spreadEnergyOverFootprint(tiles: Array<TileIndex>, energy: Long) {
            if (energy == 0L) return
            val each = energy / tiles.size
            if (each != 0L) for (t in tiles) deck.stuff.addEnergy(t, each)
            deck.stuff.addEnergy(tiles[0], energy - each * tiles.size)
        }

        /**
         * A ghost machine takes casing off the lump standing on its construction port.
         *
         * The deck's twin of [absorbIntoGhost], and deliberately the same arithmetic: admitted whole
         * or not at all, a share of every species so the junk that came with the iron is skimmed at
         * the same rate, heat carried rather than conjured, remainder rides on over the track.
         *
         * ### Finishing displaces the air, and may not
         *
         * A solid machine is solid, and the air standing where it now is has to go somewhere. That
         * happens **here**, at completion, rather than at placement — a ghost has no metal to push
         * anything aside with. So the delivery that would finish an impermeable machine is offered
         * to the atmosphere first, and if the air has nowhere left to go the delivery is **refused
         * whole** and the lump rides on: the machine stays a ghost and tries again.
         *
         * ⚠️ It has to be refused rather than absorbed, because a machine that completed with air
         * still inside it would be buried by the next [StructureMap] and the air ledger would lose
         * exactly that much. Conserving it is not optional. The room can change between placement
         * and completion, so the check at placement cannot stand in for this one.
         */
        fun absorbIntoMachineGhost(tile: TileIndex, m: DeckMachine): Packet? {
            // The deck's half of the same door — see [absorbIntoGhost]. A machine footprint is a
            // far easier place to strand somebody's cargo than a single rail tile.
            val standing = rail.resourceAt(tile) ?: return null
            if (!buildableFrom(machineBillOfMaterials(m.kind, m.tiles(grid).size), standing)) return null
            val need = machineShortfall(m)
            if (need <= 0L) return null
            val have = rail.massAt(tile)
            if (have <= 0L) return null
            val tiles = m.tiles(grid)

            // Close enough to finish? Then the air has to be able to leave before anything is taken.
            if (have >= need && !m.kind.isPermeable &&
                !tryDisplaceAir(grid, masses, airEnergy, tiles.toList(), commit = false) {
                    originOf[it] == TileIndex.NONE
                }
            ) return null

            if (have <= need) {
                // The whole lump goes in, wholesale — a fraction of a fraction is where the rounding
                // would live, and the common case is exact.
                val packet = rail.packetAt(tile) ?: return null
                for (sp in Species.ALL) spreadOverFootprint(tiles, sp, packet.contents[sp])
                spreadEnergyOverFootprint(tiles, packet.contents.energy)
                rail.put(tile, null)
                builtMass += have
                finishMachine(m, tiles)
                return packet
            }

            // The top-up at the end: a proportional slice of what is standing here, and the rest
            // rides on. ⚠️ **The junk goes with it, at the rate the metal goes** — see
            // [absorbIntoGhost]. Taking only what is billed concentrates whatever is not, until what
            // rides on is too far off any bill for anything to use.
            val taking = standing.take(need)
            for (sp in Species.ALL) spreadOverFootprint(tiles, sp, taking[sp])
            spreadEnergyOverFootprint(tiles, taking.energy)
            val remainder = standing - taking
            if (remainder.isEmpty) rail.put(tile, null) else rail.put(tile, remainder)
            builtMass += taking.total
            finishMachine(m, tiles)
            return null
        }

        /**
         * The moment a ghost stops being one: if it now holds its whole bill and it is a solid thing,
         * the air where it stands is pushed aside, exactly as a creative placement would have done.
         *
         * Everything else about becoming real is derived and needs no act — its ports come back, it
         * runs, and [StructureMap] starts calling it solid — because all three ask
         * [DeckArray.holdsFullBill] rather than a stored flag.
         */
        private fun finishMachine(m: DeckMachine, tiles: Array<TileIndex>) {
            if (m.kind.isPermeable || !deck.holdsFullBill(m)) return
            // Guaranteed to succeed: the delivery that got here was refused unless the air could go.
            tryDisplaceAir(grid, masses, airEnergy, tiles.toList()) { originOf[it] == TileIndex.NONE }
        }

        /** Ports by tile (bridges folded in — indistinguishable from buildings with ports). */
        fun portsByTile(conduit: Conduit): Map<TileIndex, List<Port>> {
            val out = HashMap<TileIndex, MutableList<Port>>()
            fun add(port: Port) {
                if (port.conduit == conduit) out.getOrPut(port.tile) { mutableListOf() }.add(port)
            }
            // Deck machines have ports too, now that a vent is one. Visited by centre — a machine
            // covering several tiles is stored once, and adding its ports once per covered tile
            // would offer the same packet to it as many times as it is wide.
            for (i in 0 until deck.size) {
                val tile = TileIndex(i)
                val m = deck[tile] ?: continue
                if (m.center != tile) continue
                // What it has *right now*, which is not always what its kind defines — see
                // [standingPortsOf], which is also what the renderer asks so the picture and the
                // routing cannot form two opinions.
                for (port in standingPortsOf(grid, deck, buffers, scrapping, m)) add(port)
            }
            return out
        }

        /** Place output packet at port tile (ports behind buildings). Tops up partial packets where possible. */
        fun pushOut(tile: TileIndex, port: Port) {
            val segment = rails[tile.index] ?: return
            val m = deck[port.owner] ?: return
            // A bridge is not ejected from here. It sets its load down as part of the conduit step
            // — see [depositFromBridge].
            if (m is Bridge) return
            // A storage only lets go while its RUN activation is positive, which is what turns it
            // from a bucket into a valve the moment you wire something to it.
            if (m is Storage && m.wiring.activation(Action.Run, signals.at(port.owner)) <= 0) return

            val buffer = bufferFor(m, port) ?: return
            // ⚠️ **A source holds on to what nothing wants, and lets go of no more than is wanted.**
            // A tank that emptied itself down a run with no consumer on it used to fill that run
            // solid, and a full run is what makes a marked rail unable to hand its metal back — the
            // `ghosts.txt` deadlock, reached by ordinary play. Keeping it in the tank instead costs
            // nothing and is reversible: build something that wants iron and the tank pours again.
            //
            // The *quantity* is the same rule one step further on. A bill is not a round number of
            // packets, so a job wanting 30g more of iron used to be sent a whole 100g of it and the
            // 70g left over stood at the end of the run for ever — which is only harmless while
            // something unlimited waits beyond, and a deadlock while that something is still being
            // built, because the residue comes to rest in front of the material that would build it.
            val useful = whitelist.room(tile, buffer)
            if (useful <= 0L) return
            // Only as much as will actually fit: an empty tile takes a whole packet, a partial one
            // takes what tops it up.
            val room = minOf(rail.headroom(tile), useful)
            val (packet, rest) = takePacket(buffer, room) ?: return
            val wasEmpty = rail.isEmpty(tile)
            if (!rail.loadOnto(tile, packet.contents)) return
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
            val bridge = deck[port.owner] as? Bridge ?: return
            // The far-end slot *is* this tile — a bridge's product port stands on its own last tile,
            // which is why setting down is a change of layer and not of place.
            val store = bufferTile(grid, bridge, port.owner, BufferRole.Product) ?: return
            val held = buffers.resourceAt(store) ?: return
            if (!rail.loadOnto(tile, held)) return
            buffers.put(store, null)
        }

        /**
         * Everything aboard every bridge shuffles one slot toward the output end.
         *
         * Walked from the **far end back**, so a full bridge empties its product slot onto the track
         * and shuffles along in the same step rather than stalling for two — the same rule, and for
         * the same reason, as [FlowGraph.order] on the track itself.
         *
         * The three slots are the three tiles the bridge stands on, so this is three reads and three
         * writes on the buffer layer rather than a `copy` of a data class. What used to be
         * `Bridge.advanced()` is here and not on the machine because the machine no longer holds
         * anything — a bridge is now only its facing, and where its load is is a fact about the
         * world.
         */
        private fun advanceBridges() {
            for (tile in grid.tiles) {
                val b = deck[tile] as? Bridge ?: continue
                if (b.center != tile) continue
                val slots = arrayOf(BufferRole.Input, BufferRole.Inside, BufferRole.Product)
                    .map { bufferTile(grid, b, tile, it) ?: return@map null }
                // Back to front: product first, so the tile it vacates is free for the one behind.
                for (i in slots.indices.reversed()) {
                    if (i == 0) break
                    val into = slots[i] ?: continue
                    val from = slots[i - 1] ?: continue
                    if (buffers.massAt(into) > 0L) continue
                    val moving = buffers.resourceAt(from) ?: continue
                    buffers.put(into, moving)
                    buffers.put(from, null)
                    // A slot that was empty and is not now took delivery from the slot behind it,
                    // which is the only way a bridge slot is ever filled from the inside.
                    motion.bridgeSlotFilled(tile, if (i == 2) Motion.SLOT_EXIT else Motion.SLOT_MIDDLE)
                }
            }
        }

        /** Which of a machine's buffers drains through [port]. */
        private fun bufferFor(m: DeckMachine, port: Port): Mixture? {
            val role = outputBufferRole(m, port.stream) ?: return null
            return buffers.resourceAt(bufferTile(grid, m, port.owner, role) ?: return null)
        }

        /** Write back what is left in the buffer that drained through [port]. */
        private fun drained(m: DeckMachine, port: Port, rest: Mixture?) {
            val role = outputBufferRole(m, port.stream) ?: return
            buffers.put(bufferTile(grid, m, port.owner, role) ?: return, rest)
        }

        /**
         * What may usefully leave each tile, as of this step's flow.
         *
         * Held on [Work] rather than passed around because [pushOut] runs *after* [advanceRails] as
         * a separate pass over the same ports, and it needs the same answer.
         *
         * Empty until the first rail step of a world, which is why it permits nothing: a source
         * asked to emit before any flow has been worked out waits a tick, which is the safe way to
         * be wrong.
         */
        var whitelist: Whitelist = Whitelist.empty()
            private set

        /** How many tiles carry track, so the flow can be rebuilt only when something ceased to be. */
        private fun railCount(): Int = rails.count { it != null }

        /** Advance all conduits one step (flow derived from input ports). */
        fun advanceRails(ports: Map<TileIndex, List<Port>>) {
            // Bridges drain first (three real slots, not one).
            for ((tile, at) in ports) for (port in at) {
                if (port.kind == PortKind.Output && port.fromBridge) depositFromBridge(tile, port)
            }

            // Bridge steps with layer (slots freed for track).
            advanceBridges()

            // All input ports are sinks; machine state (full/accepting) is handled by the absorb callback.
            //
            // A **ghost** is a sink too, and it does not get there by owning a port: a length of
            // track short of its bill of materials *is* an input, which is what makes a drawn run
            // build itself. Its own tile is the address, so it overrides whatever else wants
            // material there — a machine standing over a ghost is simply cut off from the network
            // until its feed has finished building. See `apps/outofspace/PLAN_self_building_rails.md`.
            val ghosts = railGhosts(rails, tracks)
            // A ghost machine is a sink at the tile it is fed at — see [constructionPortOf].
            // Gathered here rather than asked per tile because the absorb callback runs for every
            // loaded tile of every run on every step.
            val machineGhosts = railMachineGhosts(grid, rails, deck, scrapping)
            val ends = railEnds(grid, rails, ports, deck, buffers, scrapping, ghosts)
            val sinks = ends.sinks
            val sources = ends.sources
            val tilesWithTrack = railTiles(rails)
            // ── What each sink will take, and how much is already on its way ──
            //
            // One statement per sink, gathered here rather than asked at the door, because the door
            // is the hottest path in the sim and because a sink's appetite is a fact about the sink
            // rather than about the lump being offered. See [Acceptance].
            //
            // ⚠️ Every machine is [Acceptance.ANYTHING] and that is deliberate, not a stub: a
            // machine's buffer filling up is momentary, and the delivery path already backs the belt
            // up correctly when it does. Only a construction site has an appetite that ends.
            //
            val accepts = HashMap<TileIndex, MutableList<Acceptance>>()
            if (ghosts.isNotEmpty()) {
                val bill = conduitBillOfMaterials(Conduit.Rail)
                val stuff = tracks[Conduit.Rail]
                for (tile in ghosts) {
                    accepts.getOrPut(tile) { mutableListOf() }
                        .add(Acceptance.forBill(bill, bill.total - stuff.massAt(tile)))
                }
            }
            for ((tile, m) in machineGhosts) {
                val bill = machineBillOfMaterials(m.kind, m.tiles(grid).size)
                // ⛔ **A machine site does not stand in the road.** The track under it is finished
                // and paid for; the anti-exploit is about unpaid *track*. See [Acceptance.stopsTraffic].
                accepts.getOrPut(tile) { mutableListOf() }
                    .add(Acceptance.forBill(bill, machineShortfall(m), stopsTraffic = false))
            }

            // ── Which consumers can use what is standing on the track ────────
            //
            // ⛔ **The one thing the flow graph is told about matter, and for one question only** —
            // whether a lump standing on producer-less track justifies a consumer in taking an edge.
            // A consumer gains nothing from material it will not accept: 100kg of titanium in a
            // corridor told a run of iron rail ghosts they had something to gain by reversing that
            // corridor, so they took it and the titanium behind them could not follow. Each lump had
            // to be eaten before the next was released. Found in Stu's save, 2026-08-20.
            //
            // Derived in `RailNetwork` beside every other end of the network, so that the harness
            // and the reducer cannot form two opinions about it. See [Appetites].
            val appetites = railAppetites(grid, ghosts, machineGhosts) { rail.resourceAt(it) }

            var flow = FlowGraph.build(
                tilesWithTrack,
                sources,
                sinks,
                { tile, dir -> rails[tile.index]?.linkedTo(dir) == true },
                grid,
                // ⛔ **What is standing on the track, for producer-less track only** — see
                // [FlowGraph.build]. A stub no output port reaches is fed by its own lumps or by
                // nothing, and until this was passed in the graph could not tell the difference.
                { tile -> !rail.isEmpty(tile) },
                appetites,
                // ⛔ **Unpaid track is a wall to the graph as well as to a lump.** The same set the
                // acceptance rows above make `stopsTraffic`, said once more where the edges are
                // decided — a route drawn through a ghost rail is not a route. See [FlowGraph.build].
                walls = ghosts,
            )

            // Every lump in the flow, read off the layer **once**. The whitelist walk asks what is
            // standing on a tile once per route through it and `resourceAt` allocates, so answering
            // from a layer read there would allocate a mixture per tile per route per tick.
            val lumps = HashMap<TileIndex, Mixture>()
            for (tile in flow.order) rail.resourceAt(tile)?.let { lumps[tile] = it }
            val loadOn: (TileIndex, Mixture?) -> Long = { t, bill ->
                val lump = lumps[t]
                when {
                    lump == null -> 0L
                    bill == null -> lump.total
                    buildableFrom(bill, lump) -> lump.total
                    else -> 0L
                }
            }

            // ── The whitelist, and the pass that reads it ─────────────────────
            //
            // ⚠️ **Built before anything comes apart, which is a reordering.** Deconstruction used to
            // run first so that a tile finishing its death was out of the graph and the run closed up
            // in the same tick. It cannot any more: whether a thing may come apart at all is now a
            // question about where its metal could go, and that question *is* the graph.
            //
            // The tile set only changes when something actually ceases to be, which is rare, so the
            // graph is rebuilt on that edge rather than built twice every step.
            var whitelist = Whitelist.of(flow, rails.size, { accepts[it] }, loadOn)
            val before = railCount()
            scrapDeconstructing(whitelist)
            scrapMachines(whitelist)
            if (railCount() != before) {
                val liveTiles = rails.mapIndexedNotNullTo(mutableSetOf()) { i, seg -> if (seg != null) TileIndex(i) else null }
                flow = FlowGraph.build(
                    liveTiles,
                    sources.filterTo(mutableSetOf()) { rails[it.index] != null },
                    sinks.filterTo(mutableSetOf()) { rails[it.index] != null },
                    { tile, dir -> rails[tile.index]?.linkedTo(dir) == true },
                    grid,
                    { tile -> !rail.isEmpty(tile) },
                    appetites,
                    walls = ghosts,
                )
                whitelist = Whitelist.of(flow, rails.size, { accepts[it] }, loadOn)
            }
            this.whitelist = whitelist

            advanceSegments(
                flow,
                rail,
                diverters,
                motion,
                admits = { from, to ->
                    // Two questions, and they are not the same question.
                    //
                    // ⛔ **The site's own door, which is the anti-exploit.** A construction site is a
                    // free length of track until it is paid for, so a lump it cannot be built from
                    // must not cross it *whatever* is waiting further along. Drop this and a player
                    // routes their whole network over unpaid ghosts.
                    //
                    // **The whitelist, which is the demand rule.** Somewhere reachable from `to` has
                    // to actually want this, or the lump is being sent to fill a dead end.
                    //
                    // ⚠️ The lump is read off the layer at most once, and only when something might
                    // refuse it: `resourceAt` allocates, and this runs for every candidate direction
                    // of every loaded tile on every step.
                    // ⛔ **Only a site that stands in the road is asked** — unpaid track, and nothing
                    // else. A half-built machine over finished track declines what it cannot use
                    // without refusing it passage. See [Acceptance.stopsTraffic].
                    val site = accepts[to]?.filter { it.stopsTraffic }?.takeIf { it.isNotEmpty() }
                    if (site == null && whitelist.permitsAnything(to)) true
                    else rail.resourceAt(from)?.let { lump ->
                        // ⛔ Rationed only where the lump has somewhere else it could go — see
                        // [Whitelist.permits]. Holding back a lump with one way out saves nothing
                        // and only stops it arriving.
                        (site == null || site.any { it.admits(lump) }) &&
                            whitelist.permits(to, lump, rationed = flow.outDegree(from) > 1)
                    } ?: false
                },
            ) { tile ->
                // A ghost eats first, and eats instead of the machine rather than after it: being
                // short of its bill *is* being an input, and the tile is the address, so a machine
                // standing over unbuilt track is cut off until its feed is finished.
                //
                // ⚠️ **Track before the machine**, in that order: a ghost rail under a ghost machine
                // takes what it needs and the remainder rides on into the casing. The rail has to
                // win, because a machine standing on track that cannot carry anything is a machine
                // nothing can ever reach.
                if (ghosts.contains(tile)) return@advanceSegments absorbIntoGhost(tile)
                machineGhosts[tile]?.let { return@advanceSegments absorbIntoMachineGhost(tile, it) }
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
            for (tile in grid.tiles) {
                val gauge = deck[tile] as? Gauge ?: continue
                // Reads the track under it. A gauge with no rail beneath sees nothing, which is a
                // half-built vessel and not an error — the same as a sensor with no wire.
                if (rails[tile.index] == null) continue
                deck[tile] = gauge.reading(rail.packetAt(tile))
            }
        }

        /** Offers a passing packet to whatever owns [port]. Returns what was not taken. */
        private fun offerTo(port: Port, packet: Packet): Packet? {
            val dest = deck[port.owner] ?: return packet
            return if (deliver(port.owner.index, dest, packet)) null else packet
        }

        /** Take packets (limit caps to available room). */
        private fun takePacket(buffer: Mixture, limit: Long = Capacity.PACKET_MASS): Pair<SolidPacket, Mixture>? {
            val want = minOf(Capacity.PACKET_MASS, limit)
            if (want <= 0L || buffer.total <= 0L) return null
            val taken = if (buffer.total < want) buffer else buffer.take(want)
            return SolidPacket(taken) to buffer - taken
        }

        private fun Mixture.orNull(): Mixture? = if (isEmpty) null else this

        private fun deliver(target: Int, destination: DeckMachine, packet: Packet): Boolean {
            return when (destination) {
                // A lump stepping onto a bridge goes into the near-end slot.
                //
                // ⚠️ **A slot takes one packet or none**, and does not merge — which is the one way
                // a bridge differs from every other buffered kind. `acceptInto` would pour the next
                // lump into the same slot up to [MACHINE_BUFFER_CAP], and a bridge is not a hopper:
                // it is three places to stand, and what leaves the far end has to be what stepped on.
                is Bridge -> {
                    val store = bufferTile(grid, destination, destination.center, BufferRole.Input)
                    val solid = packet as? SolidPacket
                    if (store == null || solid == null || buffers.massAt(store) > 0L) false
                    else {
                        buffers.put(store, solid.contents)
                        true
                    }
                }
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
                // None of these is on the material network at all: no ports, nothing to hand
                // anything to. A gauge watches the track under it and never takes anything off it;
                // a valve is an opening onto the room, not a destination.
                is Sensor, is WireButton, is Pump, is Gauge, is Valve -> false
                // Both take a feed, and both take it the way every buffered kind does — by role
                // tile, kind-blind. See the machine-list twin above.
                is Thruster, is Processor, is ThermalDecomposer,
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
        private fun acceptInto(destination: DeckMachine, existing: Mixture?, packet: Packet): Mixture? {
            if (packet !is SolidPacket) return null
            // A warehouse fills to its tank; a working machine takes one lump at a time. That
            // difference is the whole of what "storage" means here.
            //
            // ⚠️ There used to be a second rule: a working machine took `Form.Ore` and refused
            // anything else, and a store refused a lump whose form differed from what it already
            // held. Both went with form. **Nothing filters by kind on this path any more** — the
            // only refusals left are "full" and "not a solid". The demand work is where kind comes
            // back, and it comes back as something a sink *asks for* rather than something it
            // happens to reject at the door.
            val cap = if (destination is Storage) Storage.CAP else MACHINE_BUFFER_CAP
            if ((existing?.total ?: 0L) >= cap) return null
            return if (existing == null) packet.contents
            else existing + packet.contents
        }

        /** What a packet becomes when it is tipped onto the deck. */
        private fun asResource(packet: Packet): Mixture =
            packet.contents

        private fun newDeckMachine(kind: DeckMachineKind, tile: TileIndex, facing: Direction): DeckMachine? = when (kind) {
            DeckMachineKind.Hull -> Hull(tile)
            DeckMachineKind.Airlock -> Airlock(tile)
            DeckMachineKind.Vent -> Vent(tile)
            DeckMachineKind.Storage -> Storage(tile, facing)
            DeckMachineKind.Sensor -> Sensor(tile, facing)
            DeckMachineKind.KeyInput -> WireButton(tile)
            DeckMachineKind.Pump -> Pump(tile, facing)
            DeckMachineKind.Thruster -> Thruster(tile, facing)
            DeckMachineKind.Processor -> Processor(tile, facing)
            DeckMachineKind.ThermalDecomposer -> ThermalDecomposer(tile, facing)
            DeckMachineKind.Extractor -> Extractor(tile, facing)
            DeckMachineKind.Bridge -> Bridge(tile, facing)
            DeckMachineKind.Gauge -> Gauge(tile)
            DeckMachineKind.Valve -> Valve(tile)
        }
    }
}
