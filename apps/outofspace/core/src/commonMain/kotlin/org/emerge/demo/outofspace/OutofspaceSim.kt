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
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.FlowCursors
import org.emerge.demo.outofspace.world.FlowGraph
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.advanceSegments
import org.emerge.demo.outofspace.world.squashOnto
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Directed
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
import org.emerge.demo.outofspace.world.size
import org.emerge.demo.outofspace.world.Body
import org.emerge.demo.outofspace.world.bodiesOf
import org.emerge.demo.outofspace.world.BodySlot
import org.emerge.demo.outofspace.world.Airlock
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.MACHINE_BUFFER_CAP
import org.emerge.demo.outofspace.world.MACHINE_OUTPUT_CAP
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Motion
import org.emerge.demo.outofspace.world.MotionLog
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Extractor
import org.emerge.demo.outofspace.world.biteCell
import org.emerge.demo.outofspace.world.reach
import org.emerge.demo.outofspace.world.reachableCell
import org.emerge.demo.outofspace.world.Processor
import org.emerge.demo.outofspace.world.Pump
import org.emerge.demo.outofspace.world.InputKey
import org.emerge.demo.outofspace.world.KeyInput
import org.emerge.demo.outofspace.world.Sensor
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.SignalNetworks
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.Vent
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
import org.emerge.demo.outofspace.world.experiencedGravity
import org.emerge.demo.outofspace.world.fullness
import org.emerge.demo.outofspace.world.vesselMass
import org.emerge.demo.outofspace.world.heatOfWorking
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.Vaporizer
import org.emerge.demo.outofspace.world.Thruster
import org.emerge.demo.outofspace.world.exhaustPath
import org.emerge.demo.outofspace.world.EdgeGrid
import org.emerge.demo.outofspace.world.gasCapacityAt
import org.emerge.demo.outofspace.world.MomentumField
import org.emerge.demo.outofspace.world.ApertureField
import org.emerge.demo.outofspace.world.PumpDemand
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
import org.emerge.demo.outofspace.world.gasCapacity
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.SimReducer

/** One tick: edits → sense → produce → process → eject → advance conduits → fluid → heat → motion.
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
        var heldKeys = 0
        for ((_, input) in inputs.entries.sortedBy { it.key.value }) {
            for (edit in input.edits) w.apply(edit)
            // Or-ed rather than taken from one player: two people at two seats are both flying, and
            // "anybody is holding it" is the same rule max-wins already applies on the wire.
            heldKeys = heldKeys or input.heldKeys
        }

        val occupancy = Occupancy(w.originOf.copyOf())

        // Who is joined to whom, this tick, from the wire the player has actually laid. Derived after
        // the edits above so that a run cut a moment ago has already parted.
        val networks = SignalNetworks.derive(w.grid, w.conduitsSnapshot())

        // Every transmitter drives the network under its own tile. There is no addressing step and
        // nothing names anything: reaching a machine means laying wire to it.
        val signals = SignalField.build(networks) { raise ->
            for (i in w.machines.indices) {
                when (val m = w.machines[i]) {
                    is Sensor -> {
                        val target = w.grid.neighbour(i, m.facing)
                        val seen = if (target < 0) -1 else w.originOf[target]
                        if (seen >= 0) raise(i, fullness(w.machines[seen]))
                    }
                    // A finger on a key, on the same footing as any other transmitter.
                    is KeyInput -> if (InputKey.heldIn(heldKeys, m.key)) raise(i, SignalField.FULL)
                    else -> {}
                }
            }
            // Gauge persists after packet leaves.
            for (t in w.rails.indices) {
                val r = w.rails[t] ?: continue
                if (r.isGauge) raise(t, r.lastPurity)
            }
        }
        w.networks = networks
        w.signals = signals

        // Signals before structure, because an airlock is a wall whose solidity is a signal. Nothing
        // upstream minds: sensors read machine fullness and gauges read the rail, and neither asks
        // what is enclosed. Edits this tick are already applied, so both still see them.
        val openness = airlockOpenness(w.machines, signals)
        val structure = StructureMap.derive(w.grid, w.machines, openness)

        for (i in w.machines.indices) {
            val m = w.machines[i] ?: continue
            val activation = m.wiring.activation(Action.Run, signals.at(i))
            w.machines[i] = when (m) {
                is Extractor -> w.leech(m, activation, i)
                is Processor -> w.refine(cfg, m, activation, i)
                is Smelter -> w.melt(cfg, m, activation, i)
                is Vaporizer -> w.vaporize(m, activation, i)
                is Thruster -> w.fire(cfg, m, activation, i, structure)
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
        val felt = experiencedGravity(state.gravity, state.netImpulseX, state.netImpulseY, state.mass)

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
            w.machines[i] = m.withEnergy(m.energy.plusSpread(added))
        }
        val bodies = bodiesOf(state.grid, w.machines, w.conduitsSnapshot(), w.bridges)
        val conducted = stepSolidHeat(
            grid = state.grid,
            bodies = bodies,
            structure = structure,
            airEnergy = w.airEnergy,
            airCapacity = gasCapacity(state.grid.size, w.airMass),
        )
        w.applyBodyHeat(bodies, conducted.energy)

        val edges = EdgeGrid(state.grid)
        val conduits = w.conduitsSnapshot()
        val roomApertures = ApertureField.derive(edges, structure, openness)
        val plumbing = pipeApertures(edges, conduits)
        val volumes = pipeVolumes(state.grid, conduits)

        // Valves first: pressure propagates immediately, both layers see exchange (see [exchangeLayers]).
        val crossed = exchangeLayers(
            openings = valveOpenings(state.grid, conduits),
            roomMass = w.airMass,
            roomEnergy = w.airEnergy,
            pipeMass = w.pipeMass,
            pipeEnergy = w.pipeEnergy,
            pipeVolumes = volumes,
        )

        // Pumps alongside valves, before either layer is diffused (see [applyPumps]).
        val pumped = applyPumps(
            demands = pumpDemands(state.grid, w.machines, conduits, signals),
            roomMass = w.airMass,
            roomEnergy = w.airEnergy,
            pipeMass = w.pipeMass,
            pipeEnergy = w.pipeEnergy,
            pipeVolumes = volumes,
        )

        // ── Thrust, before anything moves ──────────────────────────────────────
        //
        // The hull's share of the pressure it contains, taken from the field as the tick found it.
        // Where gas pushes on a face it can cross, the push goes to the gas; where it pushes on a
        // closed one, the bulkhead takes it — and inside a sealed vessel those terms telescope to
        // exactly zero however the pressure is arranged. Open a hole and one term loses its partner,
        // which is the whole of rocket thrust here. See [applyPressureForce].
        //
        // It reads the pre-diffusion field for the same reason the old solver applied forces before
        // transport: the gradient that pushes the hull this tick is the one that exists before the
        // gas has been allowed to answer it.
        val roomPressure = tilePressure(
            state.grid.size, w.airMass, gasKelvin(w.airEnergy, gasCapacity(state.grid.size, w.airMass)),
        )
        val pushed = applyPressureForce(
            edges, roomApertures, w.momentumX, w.momentumY, tileMass(state.grid.size, w.airMass), roomPressure,
            w.about,
        )
        val pipePressure = tilePressure(
            state.grid.size, w.pipeMass,
            gasKelvin(w.pipeEnergy, gasCapacity(state.grid.size, w.pipeMass)), volumes,
        )
        val pipePushed = applyPressureForce(
            edges, plumbing, w.pipeMomentumX, w.pipeMomentumY,
            tileMass(state.grid.size, w.pipeMass), pipePressure, w.about,
        )

        // On airMass (edited by [displaceAir]).
        val fluid = diffuseFluid(edges, roomApertures, w.airMass, w.airEnergy)

        // Pipes: same model, connectivity from player-drawn layout. Volume does not enter here —
        // diffusion moves a *share* of what a cell holds, and a share is the same fraction of a thin
        // cell as of a fat one. Volume still governs pressure, which is what the valves and pumps
        // above read, so a pipe is still a small place that fills quickly.
        val pipes = diffuseFluid(edges, plumbing, w.pipeMass, w.pipeEnergy)
        // Pipes cannot vent to rim (ledger check).
        require(pipes.ventedMass == 0L && pipes.ventedEnergy == 0L) {
            "a sealed pipe network vented ${pipes.ventedMass}g — a rim face was open"
        }

        // ── Flight ────────────────────────────────────────────────────────────
        val machines = w.machines.toList()
        val bridges = w.bridges.toList()
        val mass = vesselMass(machines, conduits, bridges)

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
        val pressureX = pushed.vesselX + pipePushed.vesselX
        val pressureY = pushed.vesselY + pipePushed.vesselY
        val netImpulseX = state.pose.turnedX(pressureX, pressureY) + thrustX - handedX - exhaustX
        val netImpulseY = state.pose.turnedY(pressureX, pressureY) + thrustY - handedY - exhaustY

        // The same five contributions crossed with the point each one is applied at — see
        // [torqueAbout] for why this is summed term by term and not derived from `netImpulse`.
        //
        // The debug engine is the one term with no position and therefore no torque, and that is
        // deliberate rather than an omission: [Edit.Thrust] is a key that pushes the *ship*, not a
        // nozzle bolted anywhere, so the honest place to apply it is the centre of mass, where its
        // lever arm is zero. When a real engine retires it the term goes with it.
        val handedTorque = w.bodyHandedTorque + bodiesDrifted.handedTorque
        val netTorque = pushed.torque + pipePushed.torque - handedTorque - w.exhaustTorque

        return state.copy(
            machines = machines,
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
            radiatedEnergy = state.radiatedEnergy + conducted.radiated,
            insertedEnergy = w.insertedEnergy,
            acquiredEnergy = w.acquiredEnergy,
            // Solid→air energy (see [SolidHeatStep]).
            solidToAirEnergy = state.solidToAirEnergy + conducted.toAir,
            air = fluid.air,
            pipeAir = pipes.air,
            pipeMomentum = MomentumField.of(edges, w.pipeMomentumX, w.pipeMomentumY),
            airVentedMass = state.airVentedMass + fluid.ventedMass + w.exhaustAirMass,
            // Separate from radiatedEnergy: cleaner ledger.
            airVentedEnergy = state.airVentedEnergy + fluid.ventedEnergy + w.exhaustAirEnergy,
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
            motion = w.motion.freeze(),
        ).resized(w.fitRequested)
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
        machines: List<Machine?>,
        conduits: Conduits,
        signals: SignalField,
    ): List<PumpDemand> {
        var demands: MutableList<PumpDemand>? = null
        for (tile in machines.indices) {
            val pump = machines[tile] as? Pump ?: continue
            if (conduits.at(Conduit.Pipe, tile) == null) continue
            val intake = grid.neighbour(tile, pump.facing)
            if (intake < 0) continue
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

    /** Mass this tick: rate × activation, with carry-over via [Rate]. */
    private fun throttled(massPerTick: Long, activation: Int, carry: Long): Pair<Long, Long> =
        if (activation <= 0) 0L to carry
        else Rate.tick(massPerTick * activation, SignalField.FULL, carry)

    private fun vaporizeToGas(mixture: Mixture): Mixture {
        val out = LongArray(Species.COUNT)
        for (s in Species.ALL) {
            val g = mixture[s]
            if (g <= 0L) continue
            out[s.ordinal] += g
        }
        return Mixture.ofMass(out)
    }

    private fun Work.refine(cfg: OutofspaceConfig, m: Processor, activation: Int, at: Int): Processor {
        val input = m.input ?: return m
        val (mass, carry) = throttled(m.massPerTick, activation, m.carry)
        // Full output blocks the machine (catches tailings too).
        if (blocked(m.product, m.tailings)) return m.copy(carry = carry)
        val chunkMass = minOf(mass, input.mass)
        if (chunkMass <= 0L) return m.copy(carry = carry)

        val chunk = Resource(input.form, input.mixture.take(chunkMass))
        heat(at, heatOfWorking(chunkMass, m))
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
        val (mass, carry) = throttled(m.massPerTick, activation, m.carry)
        if (blocked(m.refined, m.slag)) return m.copy(carry = carry)
        val chunkMass = minOf(mass, input.mass)
        if (chunkMass <= 0L) return m.copy(carry = carry)

        val chunk = Resource(input.form, input.mixture.take(chunkMass))
        heat(at, heatOfWorking(chunkMass, m))
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

    private fun Work.vaporize(m: Vaporizer, activation: Int, at: Int): Vaporizer {
        val input = m.input ?: return m
        val (mass, carry) = throttled(m.massPerTick, activation, m.carry)
        val chunkMass = minOf(mass, input.mass)
        if (chunkMass <= 0L) return m.copy(carry = carry)

        val chunk = Resource(input.form, input.mixture.take(chunkMass))
        heat(at, heatOfWorking(chunkMass, m))
        val gas = vaporizeToGas(chunk.mixture)
        val base = at * Species.COUNT
        val parcel = LongArray(Species.COUNT)
        for (s in Species.ALL) {
            val g = gas[s]
            if (g <= 0L) continue
            airMass[base + s.ordinal] += g
            parcel[s.ordinal] = g
        }
        val energy = gasCapacityAt(parcel, 0) * Temperature.AMBIENT_KELVIN
        airEnergy[at] += energy
        // The ore has left the cargo and the same mass has joined the atmosphere. See [solidBecameGas].
        solidBecameGas(chunkMass, energy)

        return m.copy(
            input = Resource(input.form, input.mixture - chunk.mixture).orNull(),
            carry = carry,
        )
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
        at: Int,
        structure: StructureMap,
    ): Thruster {
        val input = m.input ?: return m
        val (allowance, carry) = throttled(m.massPerTick, activation, m.carry)
        val chunkMass = minOf(allowance, input.mass)
        if (chunkMass <= 0L) return m.copy(carry = carry)

        val path = exhaustPath(grid, structure, at, m.facing)

        heat(at, heatOfWorking(chunkMass, m))

        // The propellant, as gas: whatever went into the chamber is what comes out of the bell.
        val chunk = Resource(input.form, input.mixture.take(chunkMass))
        val parcel = LongArray(Species.COUNT)
        for (s in Species.ALL) parcel[s.ordinal] = chunk.mixture[s]
        val propellantEnergy = gasCapacityAt(parcel, 0) * Temperature.AMBIENT_KELVIN

        // Everything standing in the plume, taken with it. A jet does not thread between the gas in
        // a corridor; it entrains it, which is why the whole path is walked and not just its ends.
        var scoopedMass = 0L
        var scoopedEnergy = 0L
        for (tile in path.path) {
            // The destination keeps what it has — the exhaust is about to be added to it.
            if (!path.isClear && tile == path.destination) continue
            val base = tile * Species.COUNT
            for (s in Species.ALL) {
                val held = airMass[base + s.ordinal]
                if (held <= 0L) continue
                parcel[s.ordinal] += held
                scoopedMass += held
                airMass[base + s.ordinal] = 0L
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
                about, tileCentre(grid.xOf(at)), tileCentre(grid.yOf(at)), outX, outY,
            )
        } else {
            val destination = path.destination
            val base = destination * Species.COUNT
            for (s in Species.ALL) airMass[base + s.ordinal] += parcel[s.ordinal]
            // The jet's kinetic energy stops here and becomes heat, which is what makes firing into
            // your own bulkhead expensive rather than merely useless.
            val landed = propellantEnergy + Thruster.kineticEnergy(ejectedMass)
            airEnergy[destination] += landed + scoopedEnergy
            solidBecameGas(chunkMass, landed)
        }

        return m.copy(
            input = Resource(input.form, input.mixture - chunk.mixture).orNull(),
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
        var extractedMass: Long = state.extractedMass
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
        val about: MassDistribution = massDistribution(state.grid, state.machines, state.conduits, state.bridges)

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
        val airMass: LongArray = state.air.copyMass()

        /** This tick's air temperature, as energy — mutable for the same reason [airMass] is. */
        val airEnergy: LongArray = state.air.copyEnergy()

        /** This tick's momentum, mutable for the same reason [airMass] is. */
        val momentumX: LongArray = state.momentum.copyX()
        val momentumY: LongArray = state.momentum.copyY()

        /** The pipes' own fluid, in the same four working arrays and for the same reasons. */
        val pipeMass: LongArray = state.pipeAir.copyMass()
        val pipeEnergy: LongArray = state.pipeAir.copyEnergy()
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
        fun heat(index: Int, energy: Long) {
            if (energy <= 0L || index !in heatAdded.indices) return
            heatAdded[index] += energy
            generatedEnergy += energy
        }

        /**
         * Charges [energy] to the machine at [index] **without** counting it as generated.
         *
         * The difference from [heat] is the whole point: this is energy that was already in the
         * world and has changed hands — a rock's heat arriving in the extractor that ate it. Booking
         * it as generated would break the thermal balance by exactly the amount that moved. Also
         * increments [acquiredEnergy] to record that the grid acquired this energy from outside.
         */
        fun absorb(index: Int, energy: Long) {
            if (energy == 0L || index !in heatAdded.indices) return
            heatAdded[index] += energy
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
                    BodySlot.Deck -> machines[body.at]?.let {
                        machines[body.at] = it.withEnergy(it.energy.with(body.part, energy[i]))
                    }
                    // Keyed by layer as well as tile: two fittings can stand on one tile and each
                    // has its own temperature, so `at` alone would put a pipe's heat on a rail.
                    BodySlot.Fitting -> body.conduit?.let { c ->
                        layer(c)[body.at]?.let { layer(c)[body.at] = it.copy(energy = energy[i]) }
                    }
                    BodySlot.Span -> bridges[body.at]?.let {
                        bridges[body.at] = it.withEnergy(it.energy.with(body.part, energy[i])) as Bridge
                    }
                }
            }
        }

        // Cut pipe: release gas+heat into room (not deleted — shared ledger).
        fun cutOpen(tile: Int) {
            val base = tile * Species.COUNT
            for (sp in Species.ALL) {
                airMass[base + sp.ordinal] += pipeMass[base + sp.ordinal]
                pipeMass[base + sp.ordinal] = 0L
            }
            airEnergy[tile] += pipeEnergy[tile]
            pipeEnergy[tile] = 0L
        }

        /** Where a machine instance currently sits — extractors are charged heat by identity. */
        fun indexOf(machine: Machine): Int = machines.indexOfFirst { it === machine }

        fun apply(edit: Edit) {
            when (edit) {
                is Edit.Place -> {
                    if (edit.index !in machines.indices) return
                    when (edit.kind) {
                        // Book energy for new body (heat arriving, not conjured).
                        MachineKind.Rail, MachineKind.Pipe, MachineKind.Wire -> {
                            val c = edit.kind.conduit!!
                            if (layer(c)[edit.index] == null) {
                                layer(c)[edit.index] = Segment(c).also { built(it.energy) }
                            }
                        }
                        // Valve: upgrade existing pipe or lay new.
                        MachineKind.Valve -> {
                            val existing = layer(Conduit.Pipe)[edit.index]
                            layer(Conduit.Pipe)[edit.index] =
                                existing?.copy(valve = true)
                                    ?: Segment(Conduit.Pipe, valve = true).also { built(it.energy) }
                        }
                        MachineKind.Gauge -> if (rails[edit.index] == null) {
                            rails[edit.index] = Segment(Conduit.Rail, isGauge = true)
                                .also { built(it.energy) }
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
                is Edit.Remove -> when (edit.layer) {
                    // Fittings come off first, then the building under them. Peeling the track off a
                    // smelter should not also demolish the smelter, and there is no other way to
                    // reach the track once it is threaded underneath.
                    //
                    // One layer per click, because a tile can hold several and taking them all at
                    // once would remove things the player could not see they were pointing at. A
                    // player who *does* mean all of them now has a way to say so.
                    DeleteLayer.Top -> {
                        if (removeBridge(edit.index)) return
                        for (c in Conduit.entries) if (removeConduit(edit.index, c)) return
                        removeMachine(edit.index)
                    }
                    DeleteLayer.Bridge -> removeBridge(edit.index)
                    DeleteLayer.Rail -> removeConduit(edit.index, Conduit.Rail)
                    DeleteLayer.Pipe -> removeConduit(edit.index, Conduit.Pipe)
                    DeleteLayer.Deck -> removeMachine(edit.index)
                    DeleteLayer.All -> {
                        removeBridge(edit.index)
                        for (c in Conduit.entries) removeConduit(edit.index, c)
                        removeMachine(edit.index)
                    }
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
                is Edit.BindKey -> {
                    val at = originAt(edit.index) ?: return
                    val m = machines[at]
                    if (m is KeyInput) machines[at] = m.copy(key = edit.key)
                }
                // Accumulated (mass finalised after edit pass).
                is Edit.Thrust -> { thrustDx += edit.dx; thrustDy += edit.dy }
                is Edit.DropRock -> dropRock(edit.x, edit.y, edit.radius)
                is Edit.Inject -> inject(edit.index, edit.mass, edit.water)
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
        private fun removeBridge(at: Int): Boolean {
            val bridge = bridges.getOrNull(at) ?: return false
            bridges[at] = null
            scrapped(bridge.energy.total)
            return true
        }

        /** Takes one conduit layer off a tile, cutting the far halves of its joins. */
        private fun removeConduit(at: Int, c: Conduit): Boolean {
            val line = layer(c)
            val segment = line.getOrNull(at) ?: return false
            if (c == Conduit.Pipe) cutOpen(at)
            line[at] = null
            scrapped(segment.energy)
            // Cut far halves of joins (prevent phantom connections).
            for (dir in Direction.ALL) {
                val n = grid.neighbour(at, dir)
                if (n >= 0) line[n]?.let { line[n] = it.cutFrom(dir.opposite) }
            }
            return true
        }

        /** Takes the whole building out (not a slice of it). Whatever it held drops to the deck. */
        private fun removeMachine(at: Int): Boolean {
            val origin = originAt(at) ?: return false
            val machine = machines[origin] ?: return false
            for (t in coveredTiles(grid, origin, machine.kind.size)) originOf[t] = -1
            scrapped(machine.energy.total)
            machines[origin] = null
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
        private fun inject(at: Int, mass: Long, water: Boolean = false) {
            if (at !in 0 until grid.size || mass <= 0L) return
            if (originOf[at] >= 0 && machines[originOf[at]]?.kind?.isPermeable == false) return
            val base = at * Species.COUNT
            if (water) { injectWater(at, mass); return }
            val shares = AirField.AMBIENT_AIR.scaledTo(mass)
            // The parcel on its own, so its heat can be worked out from what actually arrived rather
            // than from the tile it is arriving in — that gas is already at its own temperature.
            val parcel = LongArray(Species.COUNT)
            var added = 0L
            // Every species, because the parcel is [AirField.AMBIENT_AIR] scaled and so already
            // contains exactly what air contains — anything else contributes zero. A hardcoded list
            // here said "air is nitrogen, oxygen and carbon dioxide", which was true until argon
            // arrived and then silently injected 987 g of every requested kilogram. This is the same
            // mistake [AirField.mixtureAt] documents: a caller that enumerates the species it thinks
            // a field holds goes quietly wrong the moment the field holds one more.
            for (s in Species.ALL) {
                val g = shares[s]
                parcel[s.ordinal] = g
                airMass[base + s.ordinal] += g
                added += g
            }
            if (added <= 0L) return
            // The heat comes in with the gas, at the temperature everything else here is. Derived
            // from the mass rather than defaulted to zero, which is [AirField.of]'s rule: gas that
            // arrived with no energy is gas at absolute zero, and it stops behaving like a gas.
            val energy = gasCapacityAt(parcel, 0) * Temperature.AMBIENT_KELVIN
            airEnergy[at] += energy
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
        private fun injectWater(at: Int, mass: Long) {
            val parcel = LongArray(Species.COUNT)
            parcel[Species.Water.ordinal] = mass
            airMass[at * Species.COUNT + Species.Water.ordinal] += mass
            val energy = gasCapacityAt(parcel, 0) * Edit.WATER_INJECT_KELVIN
            airEnergy[at] += energy
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
        private fun placeBuilding(at: Int, kind: MachineKind, facing: Direction) {
            val size = kind.size
            if (!footprintFits(grid, at, size)) return
            val covered = coveredTiles(grid, at, size)
            // Over anything occupied = no-op (footprint check, not just cursor tile).
            if (covered.any { originOf[it] >= 0 }) return
            val built = newMachine(kind, facing)
            if (portsClash(portsOf(grid, built, at))) return

            // A solid deck machine is solid — air must have somewhere to go. Last check (air, not
            // geometry). A permeable one displaces nothing and so can be laid in a sealed room.
            if (!kind.isPermeable && !tryDisplaceAir(grid, airMass, covered) { originOf[it] < 0 }) return

            machines[at] = built
            built(built.energy.total)
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
            built(built.energy.total)
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
        fun leech(m: Extractor, activation: Int, at: Int): Extractor {
            val (mass, carry) = throttled(m.massPerTick, activation, m.carry)
            // Backed up: stop working, holding whatever cell is already in the jaws. It is counted
            // as aboard either way, so nothing is forfeit by waiting.
            if (m.buffer.mass >= Extractor.BUFFER_CAP) return m.copy(carry = carry)

            var input = m.input
            if (input == null || input.mass <= 0L) {
                val found = if (activation > 0) reachedBody(m, at) else -1
                input = if (found < 0) null else bite(found, at)
            }
            if (input == null) return m.copy(input = null, carry = carry)
            if (mass <= 0L) return m.copy(input = input, carry = carry)

            // The same shape as a processor working a lump: take a chunk off the input buffer.
            val chunk = input.mixture.take(minOf(mass, input.mass))
            if (chunk.total <= 0L) return m.copy(input = input, carry = carry)
            heat(at, heatOfWorking(chunk.total, m))
            return m.copy(
                input = Resource(input.form, input.mixture - chunk).orNull(),
                buffer = Resource(Form.Ore, m.buffer.mixture + chunk),
                carry = carry,
            )
        }

        /** The first body with a cell over the plate at [at], or `-1`. */
        private fun reachedBody(m: Extractor, at: Int): Int {
            val reach = m.kind.reach
            val x0 = grid.xOf(at) - reach
            val y0 = grid.yOf(at) - reach
            for (r in bodies.indices) {
                if (reachableCell(bodies[r], pose, x0, y0, x0 + 2 * reach, y0 + 2 * reach) >= 0) return r
            }
            return -1
        }

        /**
         * Takes one cell off body [index], which is where mass enters the ore ledger — at the body,
         * not at the belt, so that the two balances are hinged on the same number.
         */
        private fun bite(index: Int, at: Int): Resource? {
            val body = bodies[index]
            val reach = machines[at]!!.kind.reach
            val cell = reachableCell(
                body, pose, grid.xOf(at) - reach, grid.yOf(at) - reach,
                grid.xOf(at) + reach, grid.yOf(at) + reach,
            )
            if (cell < 0) return null
            val taken = biteCell(body, cell)
            extractedMass += taken.mass
            absorb(at, taken.energy)
            // The body lost this; the ship gained it, so the ship gave the body the negative.
            bodyHandedX -= taken.impulseX
            bodyHandedY -= taken.impulseY
            // Booked at the extractor, not at the rock: the arm is bolted to the hull there, and
            // that is the point the reaction to hauling a cell in actually pulls on.
            bodyHandedTorque -= torqueAbout(
                about, tileCentre(grid.xOf(at)), tileCentre(grid.yOf(at)),
                taken.impulseX, taken.impulseY,
            )
            if (taken.body == null) bodies.removeAt(index) else bodies[index] = taken.body
            return Resource(Form.Ore, body.oreComposition!!.scaledTo(taken.mass))
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
            if (m is Storage && m.wiring.activation(Action.Run, signals.at(port.owner)) <= 0) return

            // Only as much as will actually fit: an empty tile takes a whole packet, a partial one
            // takes what tops it up.
            val room = segment.held?.let { Capacity.headroom(it) } ?: Capacity.PACKET_MASS
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
            is Extractor -> m.buffer
            is Processor -> if (port.stream == Stream.Waste) m.tailings else m.product
            is Smelter -> if (port.stream == Stream.Waste) m.slag else m.refined
            is Storage -> m.contents
            else -> null
        }

        /** That machine with the drained buffer replaced. */
        private fun withBuffer(m: Machine, port: Port, rest: Resource?): Machine = when (m) {
            is Extractor -> m.copy(buffer = rest ?: Resource(Form.Ore, Mixture.EMPTY))
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

            // All input ports are sinks; machine state (full/accepting) is handled by the absorb callback.
            val sinks = ports.entries
                .filter { (tile, at) -> rails[tile] != null && at.any { it.kind == PortKind.Input } }
                .map { it.key }
                .toSet()
            // Sources (bridge far end gives crossing run its own direction).
            val sources = ports.entries
                .filter { (tile, at) -> rails[tile] != null && at.any { it.kind == PortKind.Output } }
                .map { it.key }
                .toSet()
            val railTiles = rails.mapIndexedNotNullTo(mutableSetOf()) { i, seg -> if (seg != null) i else null }
            val flow = FlowGraph.build(
                railTiles,
                sources,
                sinks,
                { tile, dir -> rails[tile]?.linkedTo(dir) == true },
                grid,
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
                is Processor -> m.input == null
                is Smelter -> m.input == null
                is Storage -> m.contents == null || (m.contents?.mass ?: 0L) < Storage.CAP
                is Thruster -> m.input == null
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
        private fun takePacket(buffer: Resource, limit: Long = Capacity.PACKET_MASS): Pair<SolidPacket, Resource>? {
            val want = minOf(Capacity.PACKET_MASS, limit)
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
                is Vaporizer -> acceptInto(destination.input, packet)?.let { machines[target] =
                    destination.copy(input = it); true } ?: false
                is Thruster -> acceptInto(destination.input, packet)?.let { machines[target] =
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
                    ventedMass += packet.mass
                    machines[target] = destination.copy(ventedMass = destination.ventedMass + packet.mass)
                    true
                }

                // These machines have no inputs
                is Bridge -> false
                is Extractor -> false
                is Pump -> false
                is Sensor, is KeyInput -> false
                is Hull, is Airlock -> false
            }
        }

        /** The new input buffer if [packet] is acceptable, else null. */
        private fun acceptInto(existing: Resource?, packet: Packet): Resource? {
            if (packet !is SolidPacket) return null
            if (packet.resource.form != Form.Ore) return null
            if (existing != null && existing.form != packet.form) return null
            if ((existing?.mass ?: 0L) >= MACHINE_BUFFER_CAP) return null
            return if (existing == null) packet.resource
            else Resource(existing.form, existing.mixture + packet.contents)
        }

        /** What a packet becomes when it is tipped onto the deck. */
        private fun asResource(packet: Packet): Resource =
            Resource((packet as? SolidPacket)?.form ?: Form.Ore, packet.contents)

        private fun newMachine(kind: MachineKind, facing: Direction): Machine = when (kind) {
            MachineKind.Extractor -> Extractor(facing)
            MachineKind.Processor -> Processor(facing)
            MachineKind.Vaporizer -> Vaporizer(facing)
            MachineKind.Smelter -> Smelter(facing)
            MachineKind.Storage -> Storage(facing)
            MachineKind.Sensor -> Sensor(facing)
            MachineKind.KeyInput -> KeyInput()
            MachineKind.Vent -> Vent()
            MachineKind.Thruster -> Thruster(facing)
            MachineKind.Pump -> Pump(facing)
            MachineKind.Hull -> Hull()
            MachineKind.Airlock -> Airlock()
            // Fittings placed directly on layers.
            MachineKind.Rail, MachineKind.Pipe, MachineKind.Gauge, MachineKind.Valve, MachineKind.Bridge,
            MachineKind.Wire,
            -> Hull()
        }
    }
}
