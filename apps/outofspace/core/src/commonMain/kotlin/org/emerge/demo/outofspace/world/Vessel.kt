package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.machine.Airlock
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.machine.Gauge
import org.emerge.demo.outofspace.world.machine.Valve
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.MACHINE_BUFFER_CAP
import org.emerge.demo.outofspace.world.machine.MACHINE_OUTPUT_CAP
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.Processor
import org.emerge.demo.outofspace.world.machine.Pump
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.ThermalDecomposer
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.Vent
import org.emerge.demo.outofspace.world.machine.WireButton
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Frac

import org.emerge.sim.core.physics.primitives.Frac2

/**
 * The whole world at one instant: a tile grid of machines, the global stockpile, and the vessel's
 * own gravity.
 *
 * [gravity] is here — in state, as a vector — even though nothing reads it yet. Per the plan, that is
 * the cheap insurance that keeps acceleration-derived gravity a later decision rather than a
 * rewrite: no code is allowed to assume "down" is a constant or implied by array order.
 *
 * [ventedMass] and [extractedMass] exist so conservation can be checked across the *whole world*,
 * not just one operation. A vent is the only place matter legitimately leaves and an extractor the
 * only place ore legitimately arrives, so `extracted == in-world + vented` must hold on every tick.
 * That invariant catches an entire category of logistics bug at once.
 *
 * Since H3 nothing mints ore: [extractedMass] is mass that came **off a rock**, so it is a term in
 * the rock ledger too — hinging the ore and mass balances on the same number.
 *
 * There is no separate "banked" term any more: the [Stockpile] is derived from the storages, so what
 * it holds is already counted in [inTransitMass] and adding it again would double-count.
 */
data class VesselState(
    val grid: Grid,
    /**
     * The deck: buildings, walls, the things that take up floor space, and the matter and energy
     * each of them is made of.
     *
     * The **only** list of machines the vessel has. There used to be a second one beside it — a flat
     * `List<Machine?>` — from before a machine's footprint and its matter were per-tile facts; every
     * kind has moved here, and the last of them was the bridge.
     */
    val deck: DeckArray,
    /**
     * Every machine buffer aboard — input, output, waste and processing stores alike, on one layer
     * keyed by the tile the store stands on. See [BufferLayer] for why one layer suffices.
     *
     * ⚠️ **Required, and deliberately so.** It used to default to a layer derived from the machines, and
     * that default was silent in the one direction that matters: code which *built* a layer and then
     * forgot to pass it got back a perfectly well-formed world with every store standing, correctly
     * claimed, and **empty**. The save loader did exactly that and quietly emptied every tank in the
     * game; nothing threw, and two assertions were the only evidence. Everywhere else in this
     * storage migration a missing layer is a compile error, and now it is here too.
     *
     * Fixtures that genuinely do not care still say [BufferLayer.forMachines] — the point is that
     * they say it.
     */
    val buffers: BufferLayer,
    /**
     * Everything riding on the rail network — see [RailLayer].
     *
     * Required for the reason [buffers] is: a layer that defaults to empty is silent in exactly the
     * direction that matters, handing back a well-formed world with every belt mysteriously clear.
     */
    val rail: RailLayer,
    /**
     * The conduit layers — one grid of segments per network, sharing tiles freely with the deck
     * beneath and with each other.
     *
     * Separate from the deck because that is what a layer *is*: track running under a smelter and
     * the smelter itself are both real, both at that tile, and neither is in the other's way. Keyed
     * by [Conduit] because the same is true between layers — see [Conduits] for what one list per
     * tile cost. Structure and air never look here; heat does, because a copper pipe in a hot room
     * has a temperature whether or not anything is flowing down it.
     */
    val conduits: Conduits = Conduits.empty(grid.size),
    /** Which way each fork last sent material — see [FlowCursors]. */
    val diverters: FlowCursors = FlowCursors(),
    /**
     * What moved where during the tick that produced this state, for the renderer — see [Motion].
     *
     * Presentation only: nothing in the sim reads it and the save does not carry it, so a loaded
     * world starts still and is animated again from its first tick. It rides in the snapshot rather
     * than being worked out by the renderer because only the mover knows which of a tile's
     * neighbours a packet came from.
     */
    val motion: Motion = Motion.NONE,
    /**
     * What hit what during the tick that produced this state, for a host to make a noise out of —
     * see [Impact].
     *
     * Presentation only, exactly like [motion], and stated in the same place for the same reason:
     * only the sweep knows which touches were collisions rather than weights, and by the time a host
     * has the next state the velocities that would have told it are already spent. The save does not
     * carry it — a loaded world starts silent, which is what a loaded world should sound like.
     */
    val impacts: List<Impact> = emptyList(),
    /**
     * When the passes a view interpolates last ran — see [Cadences].
     *
     * Presentation only, exactly like [motion] and [impacts]. Unlike them it survives a resize
     * untouched: it holds ticks and spans rather than anything indexed by tile.
     */
    val cadences: Cadences = Cadences(),
    /**
     * Cumulative tiles the grid origin has moved since this world was created, in the world's own
     * frame — bumped whenever the grid grows on a near edge (see `growToFit`).
     *
     * **Bookkeeping for whoever wrote a tile down, not physics.** `index = y * width + x`, so every
     * stored coordinate outside this state — the camera, the selection, a conduit drag in flight —
     * addresses a different tile the moment the grid changes shape. A holder cannot detect that on
     * its own: nothing about the new state says how far it moved. So the state says. A holder keeps
     * the value it last saw and applies the difference; see [FrameShift], which is that pattern
     * written once.
     *
     * Not saved and not part of any digest — it is a running total, so two worlds that reached the
     * same shape by different routes hold different values while being the same world.
     */
    val frameShiftX: Int = 0,
    val frameShiftY: Int = 0,
    /**
     * The clearance this world keeps between everything placed and every grid edge, or **0 for a
     * world that keeps none**. Set by [fitGrid]; the reducer grows the grid to maintain it.
     *
     * A property of the world rather than a constant, because "every world keeps four tiles" is not
     * true and asserting it would be expensive: a hand-authored `Grid(9, 5)` fixture with its hull
     * on the border is not a badly-built vessel, it is a world drawn at the size it meant, and the
     * first tick under a universal pad would silently grow it to 17×13 and move every coordinate
     * written down against it. That is the drift §6 of the plan calls the worst failure mode
     * available, and P2 already settled the principle for saves: a world records the frame it was
     * written in, and running it honours that.
     *
     * So the pad is opt-in, and [fitGrid] is how a world opts in. The starter vessel fits at
     * construction and therefore keeps 4; a fixture built by hand keeps 0 and never moves.
     */
    val gridPad: Int = 0,
    /**
     * Whatever gravity this vessel has with its engines off — [FREEFALL] for a ship, which is all
     * of them.
     *
     * A **setting**, and it stays one. What the world is actually run under is [feltGravity], which
     * is this plus whatever the engine is doing — see [experiencedGravity]. Keeping the two apart is
     * what lets a fixture say `copy(gravity = sideways)` and mean it: a field that were both the dial
     * and the reading would be overwritten by the first tick, silently, and a test that set it would
     * quietly measure something else.
     *
     * It stayed a field when the deck plating was dropped, rather than becoming a constant zero, for
     * the reason [PLATING_ONE_G] gives at length: a term that is only ever zero is a term whose bugs
     * are all invisible. A spun ring or a planet's surface would arrive here too.
     *
     * No code is allowed to assume "down" is a constant or implied by array order, and as of
     * increment G nothing can: what the passes are handed changes whenever the thrust does.
     */
    val gravity: Frac2 = FREEFALL,
    /**
     * Where the vessel has got to, in the billionths of a tile [Flight.PER_TILE] counts.
     *
     * The only genuinely new state increment G adds, and the only part of flight that has to be
     * stored: velocity is [vesselImpulseX] over [mass] and can always be recomputed, whereas a
     * position is a history of velocities and cannot be recomputed from anything.
     *
     * It is a position in **open space**, not on the grid. The grid is the vessel's own frame and
     * travels with it; nothing on it moves because the ship does.
     */
    val positionX: Long = 0L,
    val positionY: Long = 0L,
    /**
     * The momentum the gas handed the ship during the tick that produced this state — the change in
     * [vesselImpulseX], not the running total.
     *
     * Kept because a force is not recoverable from a history: two states a tick apart give it, one
     * state does not, and [feltGravity] and every thrust readout want the force. It is also why the
     * felt gravity lags the thrust by exactly one tick — this tick's impulse is not known until this
     * tick's fluid has been solved, and the fluid is solved under a gravity. Explicit, like every
     * other coupling in the tick. See [experiencedGravity].
     */
    val netImpulseX: Long = 0L,
    val netImpulseY: Long = 0L,
    /**
     * Which way the vessel is pointing, as a [Coord] — see [Rotation] for the unit.
     *
     * The angular twin of [positionX] and stored for the same reason: an orientation is a history of
     * angular velocities. Zero is the orientation the grid is drawn in, so every existing save and
     * every hand-authored fixture starts pointing the way it always did.
     *
     * ⚠️ **Nothing on the grid rotates with it yet.** The grid is still the frame everything aboard
     * is written in, and this says how that frame is turned relative to open space. Step 3 of
     * `PLAN_trig_free_rotation.md` is what makes the renderer and the camera care.
     */
    val ang: Coord = Coord(0),
    /**
     * The vessel's angular momentum — the twin of [vesselImpulseX], in mass·tile²/tick.
     *
     * Stored rather than the angular velocity, for the reason [velocityX] gives: momentum is what
     * the tick's producers add up to, and dividing it by an inertia that changes as cargo moves is
     * something to do on the way out, not on the way in. See [angVel].
     */
    val angImpulse: Long = 0L,
    /**
     * The torque delivered during the tick that produced this state — the twin of [netImpulseX], and
     * kept for the same reason: a force is not recoverable from a history.
     */
    val netTorque: Long = 0L,
    /**
     * Free-floating solids: rocks — see [RigidBody].
     *
     * ⚠️ **Not part of "aboard".** A rock is not in [inTransitMass] and not in [mass], so it
     * neither breaks the mass balance nor slows the ship down while it is loose. Its mass has
     * its own ledger.
     */
    val bodies: List<RigidBody> = emptyList(),
    val tick: Long = 0L,
    val extractedMass: Long = 0L,
    val ventedMass: Long = 0L,
    /**
     * Every gram that has stopped being cargo and become **fabric** — material a ghost has absorbed
     * to build itself with.
     *
     * The mass is still aboard, so nothing has been lost; but [inTransitMass] counts what the vessel
     * is *carrying* and not what it is *made of*, and building moves a gram from one to the other.
     * Without this term the conservation check would read a completed length of track as a leak of
     * exactly its bill of materials.
     *
     * Signed, and it goes **down** when track is deconstructed and its metal handed back to the
     * network — which is why it is one running total rather than a built and a salvaged pair.
     */
    val builtMass: Long = 0L,

    /**
     * Cumulative energy put into the world by machines doing work, and cumulative energy radiated
     * away to space. The thermal counterpart of [extractedMass] and [ventedMass], and they buy the
     * same thing: `stored + radiated − generated` must never move, so an energy leak is one
     * assertion away rather than a mystery.
     */
    val generatedEnergy: Long = 0L,
    val radiatedEnergy: Long = 0L,
    /** Cumulative mass of atmosphere lost to space. Air's counterpart to [radiatedEnergy]. */
    val airVentedMass: Long = 0L,
    /**
     * Cumulative mass of atmosphere put into the world by [org.emerge.demo.outofspace.Edit.Inject]
     * — the debug bellows — rather than by any gas coming from anywhere.
     *
     * The air ledger's [debugImpulseX], and it is here for that term's reason exactly. The identity
     * becomes
     *
     *     atmosphere + airVented − injected == baselineAir
     *
     * which is the old identity precisely whenever nothing has cheated. A tool that minted gas
     * without this term would make `airBalance` non-zero for the rest of the world's life, and an
     * instrument you have learned to ignore is worse than no instrument.
     *
     * It also gives the shortcut a provable death: when air comes from a tank or a cracker instead,
     * this returns to zero and rooms still fill.
     */
    val injectedAirMass: Long = 0L,
    /** The heat that came in with it — [injectedAirMass]'s twin, for the energy ledger. */
    val injectedAirEnergy: Long = 0L,
    /**
     * The channel values computed this tick. Kept in the snapshot rather than recomputed by the
     * renderer so that what is drawn is exactly what the sim acted on — and so a machine can be
     * drawn dimmed when its activation is zero, which is the answer to "why has this stopped".
     */
    val signals: SignalField = SignalField.none(grid.size),
    /**
     * Which signal network each tile is on, derived every tick — see [SignalNetworks].
     *
     * Kept beside [signals] rather than folded into it because the UI asks both questions: what is a
     * circuit carrying, and *is there a circuit here at all*. The second is the one that answers "why
     * is my machine not responding", and it deserves a straight answer rather than a zero.
     */
    val networks: SignalNetworks = SignalNetworks.derive(grid, conduits),
    /** Derived from where the hull is, every tick — see [StructureMap]. */
    val structure: StructureMap = StructureMap.derive(grid, deck),
    /** Which tiles each machine covers, derived every tick — see [Occupancy]. */
    val occupancy: Occupancy = Occupancy.derive(grid, deck),
    val air: Stuff = Stuff.ambientAir(grid, StructureMap.derive(grid, deck)),
    /**
     * What is inside the pipes — a **second fluid field**, on the same lattice and run by the same
     * solver, holding its own gas at its own pressure and temperature.
     *
     * Separate from [air] because a tile is not a thing: a corridor with a pipe along it has both a
     * roomful of air and a pipeful of whatever is being plumbed, and one cell per tile can hold one
     * of them. See [pipeApertures] for why this is a second field rather than the sealed sub-region
     * of [air] the plan originally called for.
     *
     * Starts **empty**, everywhere, and stays empty until something puts gas in it. A pipe is laid
     * evacuated; the alternative — filling it with whatever room it was built in — would mint
     * atmosphere on every placement and is a ledger problem for no gain.
     *
     * Its heat lives inside it, for the reason [Stuff.gas] gives at length.
     */
    val pipeAir: Stuff = Stuff.empty(grid.size),
    /** How the gas in the pipes is moving. The pipes' twin of [momentum], and state for the same reason. */
    val pipeMomentum: MomentumField = MomentumField.still(EdgeGrid(grid)),
    /**
     * What the atmosphere's energy started at — the gas's twin of [baselineAirMass], and checked the
     * same way: `airEnergy + airVentedEnergy == baselineAirEnergy` on every tick.
     *
     * The air's heat lives inside [Stuff] rather than beside it here, and deliberately — see
     * [Stuff.gas]. It is the one arrangement `copy(air = …)` cannot desynchronise.
     */
    val baselineAirEnergy: Long = air.totalEnergy + pipeAir.totalEnergy,
    /** Cumulative energy blown overboard with escaping gas. */
    val airVentedEnergy: Long = 0L,
    /**
     * The energy the world's **solids** started with. Fixed at construction so the thermal balance
     * has something to be compared against — the twin of [baselineAirMass]. Bodies are not solids
     * in the ledger — their energy enters the grid only when the extractor bites, at which point
     * [acquiredEnergy] records the transfer.
     *
     * The balance it anchors is
     * `stored + radiated + solidToAir − generated − inserted − acquired == baseline`,
     * and the two terms beyond the obvious ones are:
     *
     *  - [insertedEnergy], energy the player inserts via debug features (placing machines, etc.).
     *  - [solidToAirEnergy], because the fabric and the atmosphere now exchange heat, so what one
     *    ledger loses the other gains. Counting it keeps both closed independently, which is what
     *    makes a break in one legible instead of being absorbed by the other.
     *  - [acquiredEnergy], energy the grid acquires from bodies when the extractor bites — bodies
     *    are in [storedEnergy] but their energy enters the grid from outside, so subtracting
     *    [acquiredEnergy] cancels the double-count: `stored` holds the energy and `acquired`
     *    records the transfer so the ledger stays closed.
     */
    val baselineEnergy: Long = solidEnergy(conduits) + deck.totalEnergy + buffers.totalEnergy +
        rail.totalEnergy,
    /**
     * Whether the player may conjure things into being rather than build them.
     *
     * In **creative** the world works as it always has: drawing a run of track lays finished track,
     * the metal and the heat arrive from off-world, and [insertedEnergy] books them. With it off,
     * drawing a run lays **ghosts** — track with a representation and no mass, which fills itself
     * from the network. See `apps/outofspace/PLAN_self_building_rails.md`.
     *
     * A field on the world rather than a global `var`, because a global one is a footgun the moment
     * two tests want different answers, and because this is expected to become a world setting
     * chosen at creation. It has no UI: it is the switch in the code, and it is on by default until
     * a ghost can actually finish building itself.
     */
    val creative: Boolean = false,
    /**
     * Whether the autopilot is holding the ship still — see [Sas].
     *
     * On the **vessel** and not on any thruster, because it is not a property of an engine: it is a
     * standing instruction about what the ship should do with the engines it has, and a fleet of
     * motors each with its own opinion about whether to stabilise is not a thing anybody wants to
     * configure. The same reason `state["SAS"]` sat on the vessel in the original.
     */
    val sas: Boolean = false,
    /**
     * The centre tiles of the deck machines the player has marked for deconstruction.
     *
     * A **set on the vessel rather than a bit on the machine**, which is the one place this differs
     * from [Segment.deconstructing]. `DeckMachine` is a sealed interface with eighteen
     * implementations and the flag would have to be threaded through every one of their `copy`
     * signatures for a fact that has nothing to do with what any of them is. Machines are addressed
     * by centre tile everywhere else, so a set of centre tiles is the same key the rest of the code
     * already uses.
     *
     * ⚠️ It is the parallel-array footgun [org.emerge.demo.outofspace.world.machine.DeckArray]'s own
     * doc warns about: a mark keyed by tile outlives the machine that earned it, and the next thing
     * built on that tile would arrive already condemned. Clearing it belongs with the removal, and
     * nowhere else.
     */
    val scrapping: Set<TileIndex> = emptySet(),
    /**
     * Energy the player has inserted into the grid via debug features (placing machines, etc.).
     * Decreases when such things are scrapped.
     */
    val insertedEnergy: Long = 0L,
    /**
     * Energy acquired by the grid from bodies via extractor bites. Bodies are not part of the grid
     * — their thermal energy only enters the ledger when the extractor takes it.
     */
    val acquiredEnergy: Long = 0L,
    /** Cumulative net energy conducted from the solids into the atmosphere. Negative the other way. */
    val solidToAirEnergy: Long = 0L,
    /**
     * How the air is moving: momentum on the faces between tiles — see [MomentumField].
     *
     * In state rather than derived, because it is the fluid's memory. A room does not stop being
     * draughty between ticks, and an exhaust plume that had to be recomputed from pressure every
     * tick would have no inertia and so could never be a jet.
     */
    val momentum: MomentumField = MomentumField.still(EdgeGrid(grid)),
    /**
     * Cumulative impulse the air has delivered to the ship, and cumulative momentum that has gone
     * overboard with escaping gas.
     *
     * The vessel does not move yet — flight is Phase 5. These are here now because they are what
     * makes a breach or an exhaust *mean* something, and because a thrust figure that has been
     * accumulating correctly since the first tick is far easier to trust than one bolted on later.
     * In steady flight the two should mirror each other: what pushes the ship is what the ship
     * pushed against.
     */
    val vesselImpulseX: Long = 0L,
    val vesselImpulseY: Long = 0L,
    val exhaustMomentumX: Long = 0L,
    val exhaustMomentumY: Long = 0L,
    /**
     * Momentum the fluid solve worked out and could not hand to anything — see
     * [org.emerge.demo.outofspace.world.ProjectionResult.undeliveredX].
     *
     * The fourth store, and the one that is an admission rather than a place. A pressure difference
     * across a face with no gas on it has no fluid to accelerate and no wall to push, so it is
     * counted here instead of quietly unbalancing the other three. Small and non-accumulating today
     * — 238 against a vessel impulse of 25310 over 120 ticks of a breach — and expected to grow once
     * the ship moves and drives its own atmosphere toward the hole.
     */
    val undeliveredImpulseX: Long = 0L,
    val undeliveredImpulseY: Long = 0L,
    /**
     * Cumulative momentum put into the ship by [org.emerge.demo.outofspace.Edit.Thrust] — the debug
     * engine — rather than by any gas pushing on anything.
     *
     * The fifth store, and the only one that is not physics. It exists so that the shortcut is
     * *legible*: the ledger identity becomes
     * `vesselImpulse + momentum + pipeMomentum + exhaust + undelivered − debugImpulse == 0`,
     * which is the old identity exactly whenever nothing has cheated, and the whole point is that a
     * key which minted momentum without this term would make `momentumBalance` non-zero forever and
     * so retire the one instrument that found §5e's truncation bug. An instrument you have learned
     * to ignore is worse than no instrument.
     *
     * It also gives the stand-in a provable death: when a real engine lands in increment I, this
     * returns to zero and the ship still moves.
     */
    val debugImpulseX: Long = 0L,
    val debugImpulseY: Long = 0L,
    /**
     * Cumulative momentum the vessel has handed the bodies — by hitting them, and by pulling on them
     * with the deck plating. See [RockContact] and [driftBodies].
     *
     * The sixth store, and unlike [debugImpulseX] it is **not** an apology for a shortcut. Nothing is
     * minted here: `+J` goes to the body, `−J` to the ship, and the pair conserves by construction.
     * The term exists because only one of those two halves is inside the ledger. [vesselImpulseX] is
     * a ledger quantity and a body's momentum is not, so an exchange with no name would read as the
     * ship gaining momentum from nowhere — which is exactly the reading the ledger is for. So the
     * identity becomes
     *
     *     vesselImpulse + momentum + pipeMomentum + exhaust + undelivered + body − debug == 0
     *
     * and `body` is the same kind of thing [exhaustMomentumX] is: momentum that is genuinely
     * somewhere else now. It stops being a separate store the day a body is *held* rather than
     * merely present, because then it is part of the ship and the exchange is internal.
     *
     * ⚠️ **The plating pays into it too**, and that is not tidiness. A field the vessel makes is a
     * force the vessel exerts: charge it for the contact and not for the pull and a body resting on
     * the deck becomes a thruster — pushed down for free, pushed back up with a reaction — and the
     * ledger balances the whole time, because the free half never enters it. Zero under freefall,
     * which is every ship; it is the 1 g fixtures and H4's capture that would have found out.
     */
    val bodyImpulseX: Long = 0L,
    val bodyImpulseY: Long = 0L,
    /**
     * Cumulative angular momentum the exhaust carried off, about the centre of mass — the angular
     * twin of [exhaustMomentumX], and the first term of [angularBalance].
     *
     * Booked from the same number as [Thruster]'s linear half and at the same point, so the pair
     * cannot be unbalanced: the plume leaves with `+τ` about the bell and the ship keeps `−τ`.
     *
     * ⚠️ **Not turned into the world, unlike its linear twin.** A torque is a scalar and reads the
     * same in both frames, so there is no pose to apply and no running total accumulated at
     * attitudes that no longer apply. That is the one thing the angular ledger gets for free.
     */
    val exhaustAngImpulse: Long = 0L,
    /**
     * Cumulative angular momentum the vessel has handed the bodies — the angular twin of
     * [bodyImpulseX], and the same kind of store for the same reason: `+τ` to the body and `−τ` to
     * the ship conserve by construction, but only the ship's half is inside the ledger.
     */
    val bodyAngImpulse: Long = 0L,
    /**
     * **World-frame momentum that appeared because the ship turned, and that nobody applied.**
     *
     * The one term in the ledger that is not an impulse. The gas's momentum lives per-edge on the
     * grid, so it is stated in the *ship's* axes and it is turned into the world at read time — see
     * [momentumBalanceX]. Turn the ship and that stored vector points somewhere else in the world
     * without anything having pushed it: `R(θₜ)·G` is simply not `R(θₜ₋₁)·G`. The difference is real
     * — a shipful of air genuinely does swing round with the hull — but the hull is never charged
     * for swinging it, so the world-frame linear identity has no way to close while the ship rotates.
     *
     * Measured before this term existed: `momentumBalanceX` on a rotating starter vessel walked
     * monotonically to 359 by tick 116, tick by tick exactly equal to
     * `pose.turnedX(gas) − previousPose.turnedX(gas)`, and stopped dead on the ticks the rotation
     * stopped. Nothing was leaking; the instrument was reading a frame change as a loss.
     *
     * ⚠️ **This is bookkeeping and not physics.** It is accumulated and subtracted here so that the
     * ledger states a true identity, and it touches no trajectory: nothing reads it but
     * [momentumBalanceX]. The physics it stands in for — the hull taking a reaction for dragging its
     * own atmosphere round, and that reaction's torque — is not modelled, and this term is exactly
     * how big that omission is. If it ever grows to a size that matters next to [vesselImpulseX],
     * that is the signal to model it rather than to book it.
     */
    val frameTurnImpulseX: Long = 0L,
    val frameTurnImpulseY: Long = 0L,
    /**
     * The air the world started with. Solids and gases never interconvert, so they get separate
     * ledgers — `atmosphere + airVented == baselineAir` is a cleaner statement than folding gas into
     * the ore balance, and a break in one does not obscure the other.
     *
     * **[air] and [pipeAir] share this one ledger**, and that is a departure from the rule above
     * rather than an oversight. Solids and gases get separate ledgers because they never interconvert;
     * room gas and pipe gas interconvert by design — that is what a vent is — so two baselines would
     * disagree the first time a single gram crossed between them, and the disagreement would look
     * exactly like a leak. What must not mix is what cannot mix.
     */
    val baselineAirMass: Long = air.totalMass + pipeAir.totalMass,
    /**
     * The **cargo** the world started with — the twin of [baselineAirMass], for solids.
     *
     * For as long as the vessel began with empty tanks this could be left implicit at zero, and the
     * mass ledger read `aboard + vented == extracted`: everything solid aboard had been dug up. A
     * ship that has to *build* its own track cannot start that way — it needs something to build the
     * first rail out of, and there is nowhere for that to come from, because the extractor needs
     * track to send its ore down. So the starting stock is stated, and stating it means saying so
     * here rather than letting it read as ore nobody extracted.
     *
     * Defaulted from the world it is constructed with, exactly as the air and energy baselines are,
     * so a fixture that states a stocked tank does not become a fixture that states a leak.
     */
    val baselineCargoMass: Long = cargoMass(grid, rail, conduits, deck, buffers),
) {
    init {
        require(deck.size == grid.size) { "deck is ${deck.size}, grid holds ${grid.size}" }
        require(conduits.tileCount == grid.size) {
            "conduit layers are ${conduits.tileCount}, grid holds ${grid.size}"
        }
        // Making `buffers` explicit trades one silent failure for a smaller one: a caller can now
        // hand over a layer derived from a *different* machine list. Sizes catch the careless case.
        require(buffers.tileCount == grid.size) {
            "buffer layer is ${buffers.tileCount}, grid holds ${grid.size}"
        }
    }

    /**
     * The rail layer, which most of the game means when it says "the track".
     *
     * Not a deprecated alias: packets, [FlowField], gauges, bridges and motion are rail concepts and
     * are right to name the rail layer rather than to be generalised over conduits they will never
     * run on. A pipe does not carry a packet. What genuinely spans layers — the thermal contact graph
     * and the save — reads [conduits] instead.
     */
    val rails: List<Segment?> get() = conduits[Conduit.Rail]

    /**
     * What the vessel can build with: the contents of every storage aboard.
     *
     * Derived rather than stored, for the same reason [structure] is — a cached copy is one more
     * thing that can disagree with the world, and this is cheap to fold.
     */
    val stockpile: Stockpile get() = Stockpile.of(grid, deck, buffers)

    /**
     * Where the air is going, tile by tile — see [FlowField].
     *
     * Presentation and inspection only. Measured by running one diffusion pass over a *copy* of the
     * air and reading which way the gas went, rather than being kept as state: the flow is a function
     * of the state, and a stored copy is one more thing that can disagree with the world. That it
     * shows the step about to be taken rather than the one just taken is not a distinction a viewer
     * can make, and it is why the picture cannot go stale — there is nothing to go stale.
     *
     * Cached because the overlay wants the whole field every frame while the state behind it only
     * changes once a tick, and rebuilding it sixty times for one tick's worth of answer would be
     * sixty times the work for the same picture.
     */
    val flow: FlowField by lazy {
        // Airlocks resolved the way the sim resolves them, or a door standing open this tick would
        // be drawn as a wall the air flows through.
        val openness = airlockOpenness(deck, signals)
        val edges = EdgeGrid(grid)
        val apertures = ApertureField.derive(edges, StructureMap.derive(grid, deck, openness), openness)
        diffuseFluid(edges, apertures, air.copyMass(), energies = null).flow
    }

    /**
     * Every solid thing aboard, with its own temperature — see [Body]. Cached because the renderer
     * and the inspector both want it every frame while the state behind it changes once a tick.
     */
    val solids: List<Body> by lazy { bodiesOf(grid, conduits, deck, buffers, rail) }

    /**
     * Temperature of a tile's *fabric* in kelvin — the **hottest** thing standing on it.
     *
     * Hottest rather than an average, because this is a readout and a heat map, and the question
     * anyone asks of one is "is there something dangerous here". A firebrick furnace at 900K sharing
     * a tile with a cold rail averages to something that reads safe and is not. The rail has its own
     * temperature and the inspector can name it; the map shows the worst.
     *
     * Ambient where nothing is standing there at all: an empty tile has no fabric to have a
     * temperature, and its air's is [airKelvinAt].
     */
    fun kelvinAt(tile: TileIndex): Int = if (fabricKelvin[tile.index] > 0) fabricKelvin[tile.index] else air.kelvinAt(tile)

    /**
     * The hottest body on each tile, folded once. The heat overlay asks for every tile every frame,
     * and answering each one by scanning the body list would be the whole world times the whole
     * world for a picture that changes once a tick.
     */
    private val fabricKelvin: IntArray by lazy {
        val out = IntArray(grid.size)
        val seen = BooleanArray(grid.size)
        for (body in solids) {
            val k = body.kelvin
            if (!seen[body.tile.index] || k > out[body.tile.index]) out[body.tile.index] = k
            seen[body.tile.index] = true
        }
        out
    }

    /**
     * Temperature of a tile's *air* in kelvin, or ambient where there is none to have one.
     *
     * Still a separate number from [kelvinAt], and now for a better reason than "they are not
     * coupled yet": they *are* coupled, through [stepSolidHeat], and a wall being hotter than the
     * room it is heating is the whole content of that coupling. This is the one the fluid acts on —
     * it sets pressure, and therefore what makes a warm parcel rise.
     */
    fun airKelvinAt(tile: TileIndex): Int = air.kelvinAt(tile)

    /** The machine covering a tile, wherever its centre happens to be. */
    fun machineCovering(tile: TileIndex): DeckMachine? = deck[occupancy[tile]]

    /** The rail segment on a tile, if the layer has one there. */
    fun railAt(tile: TileIndex): Segment? = rails.getOrNull(tile.index)

    /**
     * Every port any building or bridge exposes, keyed by the tile it sits on.
     *
     * Derived rather than stored, like everything else structural. A bridge is in here on the same
     * terms as everything else, which is the whole reason it needs no special case: to the network a
     * bridge is a thing with an input port and an output port, exactly like a smelter.
     *
     * ⚠️ Asks [standingPortsOf], not [portsOf]: a ghost has one construction port and a machine being
     * taken apart has lost its inputs, and this is what the renderer draws. Drawing the ports a
     * *kind* defines would put arrows on a half-built machine that nothing can deliver to.
     */
    fun portsByTile(conduit: Conduit): Map<TileIndex, List<Port>> {
        val out = HashMap<TileIndex, MutableList<Port>>()
        fun add(port: Port) {
            if (port.conduit == conduit) out.getOrPut(port.tile) { mutableListOf() }.add(port)
        }
        // See the twin of this in the reducer for why deck machines are visited by centre.
        for (tile in grid.tiles) {
            val m = deck[tile] ?: continue
            if (m.center != tile) continue
            for (port in standingPortsOf(grid, deck, buffers, scrapping, m)) add(port)
        }
        return out
    }

    /** Every connection point of whatever is stored at [tile]. */
    fun portsAt(tile: TileIndex): List<Port> {
        val d = deck[tile] ?: return emptyList()
        return if (d.center == tile) standingPortsOf(grid, deck, buffers, scrapping, d) else emptyList()
    }

    /**
     * Thermal energy held by every solid thing aboard — the ledger quantity [baselineEnergy] anchors.
     *
     * ⚠️ **[rail] is in it, and was not.** A lump on a belt has a temperature like everything else
     * aboard, but for as long as it was not a [Body] its heat was outside this sum — so a machine
     * setting a hot packet down destroyed that energy as far as the ledger was concerned, and
     * picking one up minted it. Nothing failed, because this identity is parked (see [heatBalance]),
     * which is exactly how a term goes missing and stays missing.
     */
    val storedEnergy: Long get() = solidEnergy(conduits) + deck.totalEnergy + buffers.totalEnergy +
        rail.totalEnergy

    /** Total atmosphere still aboard, in the rooms and in the pipes — the ledger quantity. */
    val atmosphereMass: Long get() = air.totalMass + pipeAir.totalMass

    /**
     * The heat that atmosphere is carrying, in the rooms and in the pipes — what [baselineAirEnergy]
     * anchors.
     *
     * The counterpart to [atmosphereMass], and it arrived a whole increment later than it should
     * have. The mass side got a both-fields total when the pipes were built; the energy side kept
     * reading `air.totalEnergy` alone. That was invisible for exactly as long as the pipe layer was
     * sealed — no gas crossed, so no energy crossed — and the moment a valve opened, every joule that
     * went into the plumbing read as destroyed. The lesson is the ledger's own: two quantities that
     * share a baseline have to be summed the same way, and the second one is easy to forget because
     * nothing fails until something moves.
     */
    val atmosphereEnergy: Long get() = air.totalEnergy + pipeAir.totalEnergy

    /**
     * How far the air ledger is out: `atmosphere + vented − injected − baseline`, which is **zero**,
     * always, on a world nothing has broken.
     *
     * One property rather than the sum written out at each of the four places that wanted it — the
     * HUD, the harness, the fixtures and the tests. That is not tidiness: [atmosphereEnergy] arrived
     * a whole increment after [atmosphereMass] because the two were summed at separate call sites
     * and only one of them learned about the pipes, and nothing failed until gas moved. Two
     * quantities that share a baseline have to be summed in one place, or eventually they are not
     * summed the same way.
     */
    val airBalance: Long get() = atmosphereMass + airVentedMass - injectedAirMass - baselineAirMass

    /**
     * The same statement about the air's energy — [airBalance]'s twin, and zero for its reasons.
     *
     * ⚠️ It carries **one term the mass identity does not**: [solidToAirEnergy]. Heat crosses between
     * the fabric and the gas and mass never does, so what the solid ledger says it gave, this one has
     * to be holding. Leave it out and a warm room reads as a leak — which is exactly what the first
     * version of this property did.
     *
     * ⚠️ **PARKED — see [heatBalance].** This one is parked with it, for the same reason.
     */
    val airEnergyBalance: Long
        get() = atmosphereEnergy + airVentedEnergy - solidToAirEnergy - injectedAirEnergy -
            baselineAirEnergy

    /**
     * How far the solid energy ledger is out: `stored + radiated + toAir − generated − inserted −
     * acquired − baseline`, the third of the set, and zero on a world nothing has broken.
     *
     * Named here rather than spelled out by each caller because it was spelled out by **six** of
     * them — the HUD and five test files — and a seven-term identity restated seven times is the
     * shape of bug this codebase keeps paying for: [airEnergyBalance] exists because its two halves
     * were summed at separate call sites and only one of them learned about the pipes.
     *
     * ⚠️ **PARKED as of step 3 of `apps/outofspace/PLAN_unit_rescale.md` (2026-08-12).**
     *
     * This property still computes the right thing. What is parked is *checking* it. The rescale
     * pushes the mass unit to a microgram, and §2 of that plan measured the consequence: the
     * whole-grid energy accumulators are single `Long`s and they **overflow** somewhere above
     * `Kₘ = 1.4e5`. That is accepted for the duration — restructuring the accumulators was the only
     * part of the job needing more than a reordering, so taking them out of scope is what kept the
     * plan to five fixes.
     *
     * So for now these totals say nothing, and a non-zero value here is expected rather than
     * alarming. **Mass conservation stays live and is the real tripwire** for a rescaling mistake;
     * it survives the target unit with room to spare.
     *
     * The follow-on that stores ledger *divergence* instead of running totals un-parks all of it.
     * Until then, do not read the energy ledger and do not re-enable its checks piecemeal — the
     * switch is `EnergyLedgers.PARKED` in commonTest, and the parked list is in the plan.
     */
    val heatBalance: Long
        get() = storedEnergy + radiatedEnergy + solidToAirEnergy -
            generatedEnergy - insertedEnergy - acquiredEnergy - baselineEnergy

    /** Pressure of a tile as a percentage of one atmosphere, for readouts. */
    fun pressurePercentAt(tile: TileIndex): Int =
        (air.pressureAt(tile) * 100 / AMBIENT_PRESSURE).toInt()

    operator fun get(tile: TileIndex): DeckMachine? = deck[tile]
    operator fun get(x: Int, y: Int): DeckMachine? = if (grid.inBounds(x, y)) deck[grid.tile(x, y)] else null

    /**
     * Every gram still aboard: in belts or machine buffers.
     */
    val inTransitMass: Long get() = cargoMass(grid, rail, conduits, deck, buffers)

    /**
     * What a thrust is divided by: the fabric, plus what it carries, and **not** the gas — see
     * [Flight].
     */
    val mass: Long get() = vesselMass(grid, rail, conduits, deck, buffers)

    /**
     * Where that mass is, which is what every torque is booked about — see [MassDistribution].
     *
     * Recomputed on demand like [mass], and for the same reason: it is a fact about the layout and
     * its cargo, so storing it would be storing a second answer to a question the walk already
     * answers. The tick computes it once and passes it down; a readout can afford to ask again.
     */
    val distribution: MassDistribution get() = massDistribution(grid, rail, conduits, deck, buffers)

    /**
     * How fast the vessel is turning, in [Coord] raw per tick — the angular twin of [velocityX].
     *
     * Derived from [angImpulse] over the moment of inertia, so like the linear velocity there is no
     * integrated quantity to drift and nothing to be wrong about across a save.
     */
    val angVel: Long get() = angularVelocity(angImpulse, distribution)

    /**
     * How fast the vessel is going, in the billionths of a tile per tick [Flight.PER_TILE] counts.
     *
     * Derived rather than integrated: [vesselImpulseX] is the ship's momentum and this is that over
     * its mass, so there is no accumulated velocity to drift and nothing to be wrong about across a
     * save. See [Flight] for what counts as the ship and why the atmosphere does not.
     */
    val velocityX: Long get() = velocityXAt(mass)
    val velocityY: Long get() = velocityYAt(mass)

    /**
     * The same two velocities, against a [mass] the caller already has.
     *
     * ⚠️ [mass] is a walk over every tile of every layer, and `velocityX`/`velocityY` each start one.
     * The tick reads both, twice — to advance the pose and to hand the bodies the ship's motion —
     * so the plain properties had it walking the whole vessel four times before anything moved.
     * Measured on the desktop save, that was 11% of every execution sample.
     *
     * The formula stays here and stays single: these are what the properties above call, so there is
     * still one statement of what a velocity is, and a caller that has the mass simply stops asking
     * for it again.
     */
    fun velocityXAt(mass: Long): Long = scaledRatio(vesselImpulseX, mass, Flight.PER_TILE)
    fun velocityYAt(mass: Long): Long = scaledRatio(vesselImpulseY, mass, Flight.PER_TILE)

    /**
     * Where the grid sits in the world and how far it is turned — the vessel as one rigid body.
     *
     * [positionX] is the world position of the grid's **origin**, tile (0,0)'s corner, and not of
     * the centre of mass: the centre of mass moves in grid coordinates every time a packet slides
     * down a rail, and a pose anchored to it would slew the whole ship sideways when it did. The
     * ship still *turns* about its centre of mass — see [Pose.turnedAbout], which is what moves the
     * origin to keep that pivot still.
     *
     * This is the single conversion point between the world, which is where bodies and momentum
     * live, and the grid, which is the addressing scheme for everything aboard. Step 1 of
     * `PLAN_rigid_bodies.md`.
     */
    val pose: Pose get() = Pose(positionX, positionY, ang)

    /**
     * The whole momentum identity as one number: zero, or momentum has been minted or lost.
     *
     * `vessel + gas + pipe gas + exhaust + undelivered + bodies − debug engine − frame turn == 0`,
     * where the last term is the one that is not an impulse — see [frameTurnImpulseX]. Written here
     * once because it was written out by hand in seven places, and a conservation law that is
     * transcribed seven times is a conservation law with seven chances to be transcribed wrong.
     *
     * ⚠️ **Stated in the world frame, and the ship's own stores are the ones already in it.**
     * [vesselImpulseX], [exhaustMomentumX] and [bodyImpulseX] are world-frame — see
     * [org.emerge.demo.outofspace.OutofspaceSim] for where the turn happens and why. The gas fields
     * are not and cannot be: [momentum] is per-edge on the grid, so it is a direction in the ship by
     * construction, and it is turned here on the way out instead. That is exact for the gas, whose
     * momentum is a live quantity read at the pose it is read at; [undeliveredImpulseX] is a running
     * total and so is turned by an angle that is only approximately its own, which is tolerable only
     * because the term is tiny and non-accumulating (see its own note). If it ever grows, it needs
     * turning where it is booked, exactly as the exhaust does.
     */
    val momentumBalanceX: Long get() = vesselImpulseX + exhaustMomentumX + bodyImpulseX -
        debugImpulseX - frameTurnImpulseX + pose.turnedX(gasMomentumX, gasMomentumY)

    val momentumBalanceY: Long get() = vesselImpulseY + exhaustMomentumY + bodyImpulseY -
        debugImpulseY - frameTurnImpulseY + pose.turnedY(gasMomentumX, gasMomentumY)

    /** Everything the gas is holding, in the grid's axes — the un-turned half of [momentumBalanceX]. */
    private val gasMomentumX: Long get() = momentum.totalX + pipeMomentum.totalX + undeliveredImpulseX
    private val gasMomentumY: Long get() = momentum.totalY + pipeMomentum.totalY + undeliveredImpulseY

    /**
     * The angular identity as one number: `angImpulse + exhaust + bodies == 0`, or the ship has been
     * spun by something that took no reaction.
     *
     * A torque is a scalar and reads the same in both frames, so unlike [momentumBalanceX] there is
     * no pose to apply here and no frame-turn term to carry — that whole class of correction simply
     * does not arise. What is left is the three stores that can actually spin something: what the
     * ship is turning at, what its exhaust carried off, and what it handed the rocks.
     *
     * ⛔ **There is deliberately no term here for the gas aboard, and that is the entire point of
     * the instrument.** [momentumBalanceX] carries one — `momentum.totalX`, the per-edge field
     * [org.emerge.demo.outofspace.world.applyPressureForce] writes the gas's half of every push
     * into — and because that field is read by no physics, counting it as a store lets the linear
     * ledger close over momentum that can never move anything. Measured: a sealed starter vessel
     * with a pressure pocket and nothing vented accelerates from 0.0058 to 0.0142 tiles/tick over
     * 1200 ticks while `momentumBalance` reads `-0.0000` throughout. **A store nothing can spend is
     * not a store, and an identity that counts one is not an instrument.** So this states the
     * identity over the ship alone, and the gas aboard is treated as rigidly carried — which is what
     * a momentum-free diffusion model is actually saying.
     *
     * ⚠️ **It is therefore RED on today's code, on purpose**, and what it reads is the accumulated
     * pressure torque: `netTorque` books `pressureTorque` onto the ship with nothing on the other
     * side. That is the omission stated as a number rather than hidden behind a store, and it is the
     * thing to watch go to zero. See `PLAN_grid_vs_continuous.md` and [[project_rotation]] for the
     * two ways out — book the hull only where mass genuinely crosses the vessel boundary, or give
     * the gas back a momentum that something spends.
     */
    val angularBalance: Long get() = angImpulse + exhaustAngImpulse + bodyAngImpulse

    /**
     * What everything loose aboard is actually falling toward: the plating, plus the engine.
     *
     * This is what the fluid is run under — [gravity] alone never is any more. See
     * [experiencedGravity] for the sign, and for why only one axis of the thrust survives.
     */
    val feltGravity: Frac2 get() = experiencedGravity(gravity, netImpulseX, netImpulseY, mass)

    /**
     * The ship's acceleration in its own frame, which is what makes the vessel frame a non-inertial
     * one and is therefore the only part of the ship a free rock can feel.
     *
     * The same quantity [experiencedGravity] subtracts from the plating for the gas, pulled out and
     * named because a rock outside the hull needs *this* half and must not be handed the plating —
     * see [feltBy]. Two consumers of one term beats two derivations of it.
     */
    val frameAcceleration: Frac2 get() = frameAcceleration(netImpulseX, netImpulseY, mass)


    companion object {
        /**
         * **What a vessel has when its engines are off: nothing.**
         *
         * The deck plating is gone, and its removal is the point rather than a simplification. A
         * ship in space has no floor; what a crew stands on is the engine, and a vessel that made
         * its own gravity out of nowhere was the one deeply unphysical thing left in a model whose
         * whole purpose is to be a place to do physics. "Down" is now something the vessel *earns*
         * by burning, and it is gone the moment the engine is.
         *
         * That is where §3 was always pointing — "vessel-local constant, parameterised;
         * acceleration-derived later" — and increment G is what made the second half available.
         *
         * ⚠️ Note what it costs, because two of the three are losses and neither is a bug:
         *
         *  - **Convection stops on a coasting ship.** Buoyancy is a gravity term, so a vessel that
         *    is not burning has no natural circulation at all and heat moves only by conduction and
         *    by forced flow. It comes back under thrust. See §5c's "convection, for free, again",
         *    which is now "convection, for free, while the engine is lit".
         *  - Rocks stop plummeting: a fiftieth of a tile per tick per tick under a burn rather than
         *    a whole one, which is most of why H2 is not primarily about tunnelling.
         */
        val FREEFALL: Frac2 = Frac2(Frac(0L), Frac(0L))

        /**
         * One g, straight down the screen — what the plating used to make, kept as a **value**.
         *
         * Not dead code and not nostalgia. §5e's finding was that a quantity only ever run at one
         * value has not been run, and zero is the worst value to get stuck at: every gravity-scaled
         * term goes identically to zero, so a whole class of bug becomes invisible rather than
         * merely unlikely. This is what a fixture sets when it means to exercise buoyancy, settling
         * or drift — and a test that needs it now has to *say so*, which is an improvement on
         * inheriting it from a default and not knowing.
         *
         * It is also the shape a spun ring or a planet's surface would arrive in, if either ever does.
         */
        val PLATING_ONE_G: Frac2 = Frac2(Frac(0L, 1), Frac(1L, 1))

        fun empty(grid: Grid): VesselState =
            VesselState(grid, DeckArray(grid), BufferLayer.empty(grid.size), RailLayer.empty(grid.size))
    }
}

/**
 * What falls on the floor when a machine is taken apart: everything it was holding, keeping forms
 * separate. Defined in terms of [contentsBreakdown] so there is exactly one list of "where a machine
 * keeps things" — a second one would drift, and the drift would look like a conservation bug.
 */
fun spoilsOf(machine: DeckMachine?, centre: TileIndex, grid: Grid, buffers: BufferLayer): List<Mixture> =
    contentsBreakdown(machine, centre, grid, buffers).map { it.second }.filter { !it.isEmpty }

/**
 * Total mass held by one machine, wherever it keeps it. Used for world-wide conservation checks.
 *
 * Kind-blind, and that is the point of [BufferRole]: a machine's stores are its role tiles, so this
 * no longer has to be extended when a machine gains a buffer — a [Bridge] stopped being the one
 * exception when its three slots became three role tiles like anything else's.
 */
fun massIn(machine: DeckMachine?, centre: TileIndex, grid: Grid, buffers: BufferLayer): Long = when (machine) {
    null -> 0L
    else -> {
        var sum = 0L
        for (role in BufferRole.entries) {
            val tile = bufferTile(grid, machine, centre, role) ?: continue
            sum += buffers.massAt(tile)
        }
        sum
    }
}

/**
 * How full a machine is, 0..1000 permille — the one number a [Sensor] reads.
 *
 * Every machine answers, so a sensor can be pointed at anything and mean something. The reference
 * capacity differs by kind (a belt's is its slots, a storage's is its tank), which is the point: the
 * question a sensor asks is "is this backing up?", not "how much mass".
 */
fun fullness(machine: DeckMachine?, centre: TileIndex, grid: Grid, buffers: BufferLayer): Int = when (machine) {
    null -> 0
    // Neither holds anything, so neither has a fullness. A gauge's reading is a different question
    // and reaches the wire by its own route — see [OutofspaceReducer]'s gauge pass.
    is Gauge, is Valve -> 0
    // Slots occupied, not mass: a bridge is full when there is nowhere to put the next lump, and
    // three small packets back it up exactly as three large ones do.
    is Bridge -> {
        var filled = 0
        for (role in BufferRole.entries) {
            val tile = bufferTile(grid, machine, centre, role) ?: continue
            if (buffers.massAt(tile) > 0L) filled++
        }
        filled * SignalField.FULL / Bridge.SLOTS
    }
    // An extractor reads on its output buffer alone: what is in the jaws is a whole cell of rock and
    // dwarfs the ground ore, so counting it would peg the sensor the moment the machine took a bite.
    is Extractor -> (buffers.massAt(bufferTile(grid, machine, centre, BufferRole.Product)!!) *
        SignalField.FULL / Extractor.BUFFER_CAP).toInt()
    is Processor -> (massIn(machine, centre, grid, buffers) * SignalField.FULL / (MACHINE_BUFFER_CAP + MACHINE_OUTPUT_CAP * 2)).toInt()
    is ThermalDecomposer -> (massIn(machine, centre, grid, buffers) * SignalField.FULL / (MACHINE_BUFFER_CAP + MACHINE_OUTPUT_CAP)).toInt()
    is Thruster -> (massIn(machine, centre, grid, buffers) * SignalField.FULL / MACHINE_BUFFER_CAP).toInt()
    is Storage -> (massIn(machine, centre, grid, buffers) * SignalField.FULL / Storage.CAP).toInt()
    is Sensor, is WireButton -> 0
    is Hull, is Airlock -> 0
    is Vent -> 0
    is Pump -> 0
}.coerceIn(0, SignalField.FULL)

/**
 * What the inspector calls each of a machine's stores.
 *
 * The role says where a thing is in the machine; this says what the machine calls it there. A
 * thruster's [BufferRole.Input] is propellant and a processor's is feed, and telling the player
 * "INPUT" for both would throw away the only word that says which machine they are looking at.
 */
private fun labelOf(machine: DeckMachine, role: BufferRole): String = when (machine) {
    // Which end of the span it is on, which is the only thing worth knowing about a bridge — and
    // what the three slots were always called in the inspector.
    is Bridge -> when (role) {
        BufferRole.Input -> "IN"
        BufferRole.Inside -> "SPAN"
        else -> "OUT"
    }
    is Extractor -> if (role == BufferRole.Inside) "CRUSHING" else "BUFFER"
    is Storage -> "STORED"
    is Thruster -> "PROPELLANT"
    else -> when (role) {
        BufferRole.Input -> "INPUT"
        BufferRole.Inside -> "PROCESSING"
        BufferRole.Product -> "CONCENTRATE"
        BufferRole.Waste -> "TAILINGS"
    }
}

/**
 * A machine's contents broken out by the buffer they sit in, for the inspector.
 *
 * Named buffers rather than one lump, because "this processor holds 6kg" is far less useful than
 * "3kg waiting, 2kg of concentrate, 1kg of tailings" — the second tells you which side is stuck.
 */
fun contentsBreakdown(machine: DeckMachine?, centre: TileIndex, grid: Grid, buffers: BufferLayer): List<Pair<String, Mixture>> = when (machine) {
    null -> emptyList()
    else -> BufferRole.entries.mapNotNull { role ->
        val tile = bufferTile(grid, machine, centre, role) ?: return@mapNotNull null
        buffers.resourceAt(tile)?.let { labelOf(machine, role) to it }
    }
}

/** Everything a machine holds, species by species — the finer-grained version of [massIn]. */
fun contentsOf(machine: DeckMachine?, centre: TileIndex, grid: Grid, buffers: BufferLayer): Mixture = when (machine) {
    null -> Mixture.EMPTY
    else -> {
        var out = Mixture.EMPTY
        for (role in BufferRole.entries) {
            val tile = bufferTile(grid, machine, centre, role) ?: continue
            out += buffers.resourceAt(tile) ?: Mixture.EMPTY
        }
        out
    }
}

/**
 * The same world on a different lattice, translated by (dx, dy) tiles.
 *
 * Cells that exist in both grids keep everything; cells only in the new one are vacuum.
 * The old origin lands at (dx, dy) in the new grid, so growing left by 4 is dx = +4.
 *
 * On a shrink, the gas in a discarded cell is **vented** to `airVentedMass`/`airVentedEnergy` and
 * its face momentum to `exhaustMomentumX/Y`, so every ledger holds. A discarded **solid** throws
 * instead: the grid is fitted around the solids, so losing one means the bounds were wrong. Rocks
 * are not grid-indexed and are translated, never discarded. See `PLAN_dynamic_grid.md` §5.
 */
fun VesselState.remapped(newGrid: Grid, dx: Int, dy: Int): VesselState {
    require(newGrid.size > 0) { "new grid must be non-empty" }

    for (i in 0 until deck.size) {
        val tile = TileIndex(i)
        if (deck[tile] == null) continue
        val ox = grid.xOf(tile); val oy = grid.yOf(tile)
        require(newGrid.inBounds(ox + dx, oy + dy)) {
            "remap would discard a machine at ($ox, $oy)"
        }
    }
    for (c in Conduit.entries) {
        for (i in conduits[c].indices) {
            val tile = TileIndex(i)
            if (conduits[c][i] == null) continue
            val ox = grid.xOf(tile); val oy = grid.yOf(tile)
            require(newGrid.inBounds(ox + dx, oy + dy)) {
                "remap would discard a ${c.label} conduit at ($ox, $oy)"
            }
        }
    }

    val oldW = grid.width
    val oldH = grid.height
    val oldSize = grid.size

    // ── helpers ───────────────────────────────────────────────────────────
    fun remapTile(ox: Int, oy: Int): TileIndex? {
        val nx = ox + dx
        val ny = oy + dy
        return if (newGrid.inBounds(nx, ny)) newGrid.tile(nx, ny) else null
    }

    // ── 1. The deck ──────────────────────────────────────────────────────
    // The deck is three things on one lattice — the machines, their matter and their energy — so it
    // is remapped in two passes rather than one. `+=` seeds a freshly placed machine at ambient, and
    // this machine is not freshly placed: the stores are copied over the seed afterwards, which is
    // also the only order in which `+=`'s "nothing here yet" requirement can hold.
    val newDeck = DeckArray(newGrid)
    for (ox in 0 until oldW) for (oy in 0 until oldH) {
        val m = deck[grid.tile(ox, oy)] ?: continue
        val ni = remapTile(ox, oy) ?: continue
        newDeck += m.movedTo(ni)
    }
    for (ox in 0 until oldW) for (oy in 0 until oldH) {
        val ni = remapTile(ox, oy) ?: continue
        val oi = grid.tile(ox, oy)
        // ⚠️ Only where the old deck actually stood. Copying unconditionally claims a row for every
        // tile in the grid, which makes the layer dense — the one thing its row layout exists to
        // avoid — and then leaves rows behind at tiles with no machine, so the next `+=` there fails
        // its "nothing here yet" check. That is not hypothetical; it is what this loop did first.
        if (!deck.stuff.occupies(oi)) continue
        newDeck.stuff.claim(ni)
        newDeck.stuff.setEnergy(ni, deck.stuff.energyAt(oi))
        deck.stuff.forEachSpecies(oi) { s, mass -> newDeck.stuff[ni, s] = mass }
    }

    // Buffers move with the lattice like the deck does. Same rule, same reason: only where a store
    // actually stands, or the layer goes dense and stores appear at tiles with no machine on them.
    val newBuffers = BufferLayer.empty(newGrid.size)
    for (ox in 0 until oldW) for (oy in 0 until oldH) {
        val ni = remapTile(ox, oy) ?: continue
        val oi = grid.tile(ox, oy)
        if (!buffers.stuff.occupies(oi)) continue
        newBuffers.claimRole(ni)
        newBuffers.put(ni, buffers.resourceAt(oi))
    }

    // What is riding on the track moves with the lattice too — same rule, same reason.
    val newRail = RailLayer.empty(newGrid.size)
    for (ox in 0 until oldW) for (oy in 0 until oldH) {
        val ni = remapTile(ox, oy) ?: continue
        val oi = grid.tile(ox, oy)
        if (rail.isEmpty(oi)) continue
        newRail.put(ni, rail.resourceAt(oi))
    }

    // ── 2. Conduits: remap each layer ────────────────────────────────────
    var newConduits = Conduits.empty(newGrid.size)
    for (c in Conduit.entries) {
        val oldLayer = conduits[c]
        val newLayer = MutableList(newGrid.size) { null as Segment? }
        for (ox in 0 until oldW) for (oy in 0 until oldH) {
            val ni = remapTile(ox, oy) ?: continue
            val oi = grid.tile(ox, oy)
            newLayer[ni.index] = oldLayer[oi.index]
        }
        newConduits = newConduits.with(c, newLayer)
        // ⚠️ The **matter** is carried across by hand, not just the heat. [Conduits.with] lays
        // nothing now — a segment arriving with no metal is a ghost, which is the whole point — so
        // a growth that did not copy the metal would turn every length of track aboard into a ghost
        // and delete the ship out from under the player.
        //
        // It used to copy only the energy and let `with` re-derive the mass from the kind's bill.
        // That was already wrong and could not say so: a length of track whose composition had been
        // altered came back as pristine iron one grid over, and no ledger could see the difference
        // because the mass was identical. Copying what is actually there is both the fix and the
        // only thing that can carry a half-built ghost across a growth.
        for (ox in 0 until oldW) for (oy in 0 until oldH) {
            val ni = remapTile(ox, oy) ?: continue
            val oi = grid.tile(ox, oy)
            if (oldLayer[oi.index] == null) continue
            val from = conduits.tracks[c]
            val to = newConduits.tracks[c]
            from.forEachSpecies(oi) { sp, mass -> to[ni, sp] = mass }
            to.setEnergy(ni, conduits.energyAt(c, oi))
        }
    }

    // ── 3. Sparse map for diverters ──────────────────────────────────────
    fun remapCursors(src: Map<TileIndex, Int>): HashMap<TileIndex, Int> {
        val out = HashMap<TileIndex, Int>()
        for ((oldTile, cursor) in src) {
            val ox = grid.xOf(oldTile)
            val oy = grid.yOf(oldTile)
            val ni = remapTile(ox, oy)
            if (ni != null) out[ni] = cursor
        }
        return out
    }
    val newDiverters = FlowCursors(remapCursors(diverters.snapshot()), remapCursors(diverters.mergeSnapshot()))

    // ── 4. Dense field arrays: air / pipeAir (mass + energy) ────────────
    fun remapAirField(src: Stuff): Stuff {
        val newMass = MassArray(newGrid.size)
        val oldEnergy = src.copyEnergy()
        val newEnergy = EnergyArray(newGrid.size)
        for (ox in 0 until oldW) for (oy in 0 until oldH) {
            val newTile = remapTile(ox, oy) ?: continue
            val oldTile = grid.tile(ox, oy)
            for (f in Fluid.ALL) {
                newMass[newTile, f] = src.massOf(oldTile, f)
            }
            newEnergy[newTile] = oldEnergy[oldTile]
        }
        return Stuff.from(newMass, newEnergy)
    }
    val newAir = remapAirField(air)
    val newPipeAir = remapAirField(pipeAir)

    // ── 5. Edge fields: momentum, pipeMomentum ───────────────────────────
    val oldMomentumEdges = EdgeGrid(grid)
    val newMomentumEdges = EdgeGrid(newGrid)
    val newMomentumX = LongArray(newMomentumEdges.xEdgeCount)
    val newMomentumY = LongArray(newMomentumEdges.yEdgeCount)
    val srcX = momentum.copyX()
    val srcY = momentum.copyY()
    // x-faces: (x, y) where x ∈ [0, oldW], y ∈ [0, oldH)
    for (oy in 0 until oldH) for (ox in 0..oldW) {
        val oldEdge = oldMomentumEdges.xEdge(ox, oy)
        val nx = ox + dx
        val ny = oy + dy
        if (ny >= 0 && ny < newGrid.height && nx >= 0 && nx <= newGrid.width) {
            val newEdge = newMomentumEdges.xEdge(nx, ny)
            newMomentumX[newEdge] = srcX[oldEdge]
        }
    }
    // y-faces: (x, y) where x ∈ [0, oldW), y ∈ [0, oldH]
    for (ox in 0 until oldW) for (oy in 0..oldH) {
        val oldEdge = oldMomentumEdges.yEdge(ox, oy)
        val nx = ox + dx
        val ny = oy + dy
        if (ny >= 0 && ny <= newGrid.height && nx >= 0 && nx < newGrid.width) {
            val newEdge = newMomentumEdges.yEdge(nx, ny)
            newMomentumY[newEdge] = srcY[oldEdge]
        }
    }
    val newMomentum = MomentumField.of(newMomentumEdges, newMomentumX, newMomentumY)

    val oldPipeEdges = EdgeGrid(grid)
    val newPipeEdges = EdgeGrid(newGrid)
    val newPipeMomentumX = LongArray(newPipeEdges.xEdgeCount)
    val newPipeMomentumY = LongArray(newPipeEdges.yEdgeCount)
    val psrcX = pipeMomentum.copyX()
    val psrcY = pipeMomentum.copyY()
    for (oy in 0 until oldH) for (ox in 0..oldW) {
        val oldEdge = oldPipeEdges.xEdge(ox, oy)
        val nx = ox + dx
        val ny = oy + dy
        if (ny >= 0 && ny < newGrid.height && nx >= 0 && nx <= newGrid.width) {
            val newEdge = newPipeEdges.xEdge(nx, ny)
            newPipeMomentumX[newEdge] = psrcX[oldEdge]
        }
    }
    for (ox in 0 until oldW) for (oy in 0..oldH) {
        val oldEdge = oldPipeEdges.yEdge(ox, oy)
        val nx = ox + dx
        val ny = oy + dy
        if (ny >= 0 && ny <= newGrid.height && nx >= 0 && nx < newGrid.width) {
            val newEdge = newPipeEdges.yEdge(nx, ny)
            newPipeMomentumY[newEdge] = psrcY[oldEdge]
        }
    }
    val newPipeMomentum = MomentumField.of(newPipeEdges, newPipeMomentumX, newPipeMomentumY)

    // ── 5b. The signal maps, and the structure that depends on them ──────
    // Re-derived on the new lattice rather than carried; see the block in the `copy` below for why
    // carrying is not an option. The **readings** are carried, though, and that is the fiddly part:
    // a [SignalField] is values keyed by *network id*, and ids belong to the derivation that made
    // them, so there is no transplanting an old `values` array onto a fresh numbering. What travels
    // is the reading at each tile, put back through [SignalField.build] on the new networks — which
    // takes the max over a network and so reproduces the old value exactly, every tile of one
    // network having carried the same one.
    val newNetworks = SignalNetworks.derive(newGrid, newConduits)
    val newSignals = SignalField.build(newNetworks) { raise ->
        for (ox in 0 until oldW) for (oy in 0 until oldH) {
            val ni = remapTile(ox, oy) ?: continue
            val reading = signals.at(grid.tile(ox, oy))
            if (reading != 0) raise(ni, reading)
        }
    }
    // With the openness the reducer would have passed, so an airlock the player is holding open does
    // not read as a wall for the frame between the resize and the next tick.
    val newStructure = StructureMap.derive(newGrid, newDeck, airlockOpenness(newDeck, newSignals))

    // ── 6. Bodies: nothing to do ─────────────────────────────────────────
    // They used to be shifted by the same offset the tile indices moved by, because they were
    // stored in the grid's frame. They are stored in the **world** now, and a rock does not move
    // because the player built a row of hull off the port bow. What moves instead is the grid's
    // *origin*: tile (0,0) is a different place than it was, `dx`,`dy` tiles away in grid axes, so
    // the pose has to follow it or everything aboard would appear to jump. See [VesselState.pose].
    val newBodies = bodies
    val shiftedOrigin = pose.let { it.movedBy(
        it.toWorldX(-dx.toLong() * Flight.PER_TILE, -dy.toLong() * Flight.PER_TILE) - it.x,
        it.toWorldY(-dx.toLong() * Flight.PER_TILE, -dy.toLong() * Flight.PER_TILE) - it.y,
    ) }

    // ── 7. Vent whatever the new grid does not cover ─────────────────────
    // As a difference of totals rather than a walk of the discarded cells: exact by construction,
    // with no edge index to get wrong. Zero on a grow, so that needs no special case.
    val ventedGas    = (air.totalMass + pipeAir.totalMass) - (newAir.totalMass + newPipeAir.totalMass)
    val ventedEnergy = (air.totalEnergy + pipeAir.totalEnergy) - (newAir.totalEnergy + newPipeAir.totalEnergy)
    val ventedMomX   = (momentum.totalX + pipeMomentum.totalX) - (newMomentum.totalX + newPipeMomentum.totalX)
    val ventedMomY   = (momentum.totalY + pipeMomentum.totalY) - (newMomentum.totalY + newPipeMomentum.totalY)

    // ── 8. Motion: dropped on resize (renderer will re-animate from zero)
    //    Baselines: passed through unchanged — they are conservation constants

    return copy(
        grid = newGrid,
        // Dropped rather than remapped: it is presentation, and a resize is a frame where nothing
        // animates. It must be dropped *explicitly* — `copy()` would carry through arrays sized to
        // the old grid, which the renderer then reads at new-grid tile indices.
        motion = Motion.NONE,
        deck = newDeck,
        // ── ⚠️ The derived maps: re-derived, never carried ────────────────
        //
        // A defaulted field is **not** recomputed by `copy()` — the default expression only runs
        // when the argument is omitted, and `copy` omits nothing. So every one of these rides
        // through a resize as an array sized to the *old* grid, and is then read at new-grid tile
        // indices: a wrong answer where the index still lands inside, and an
        // `ArrayIndexOutOfBoundsException` where it does not.
        //
        // This has now been the same bug four times — `occupancy`, `motion`, and then `structure`
        // and the two signal maps together, which crashed the renderer on the very frame the player
        // placed a building near an edge. **Anything derived from the grid must be named here.** If
        // a new field is added to `VesselState` with a default that mentions `grid`, it belongs in
        // this block or it is already broken.
        occupancy = Occupancy.derive(newGrid, newDeck),
        structure = newStructure,
        networks = newNetworks,
        signals = newSignals,
        buffers = newBuffers,
        rail = newRail,
        conduits = newConduits,
        diverters = newDiverters,
        air = newAir,
        pipeAir = newPipeAir,
        momentum = newMomentum,
        pipeMomentum = newPipeMomentum,
        bodies = newBodies,
        positionX = shiftedOrigin.x,
        positionY = shiftedOrigin.y,
        // vented quantities — grow: difference is zero, no special case needed
        airVentedMass = airVentedMass + ventedGas,
        airVentedEnergy = airVentedEnergy + ventedEnergy,
        exhaustMomentumX = exhaustMomentumX + ventedMomX,
        exhaustMomentumY = exhaustMomentumY + ventedMomY,
        // ⚠️ **Remapped, not carried.** It is a set of tile *indexes*, and a tile index means a
        // different place the moment the lattice changes shape — so a world that grew came back with
        // its condemned machines reprieved and some innocent tile marked instead. Silent both ways:
        // the machine simply stands there at full casing looking finished. Found by the harness, and
        // it is the same class of bug the grid remap has produced twice before.
        scrapping = scrapping.mapNotNullTo(mutableSetOf()) { tile ->
            val nx = grid.xOf(tile) + dx
            val ny = grid.yOf(tile) + dy
            if (newGrid.inBounds(nx, ny)) newGrid.tile(nx, ny) else null
        },
        // baselines passed through explicitly to avoid recompute on copy
        baselineAirMass = baselineAirMass,
        baselineAirEnergy = baselineAirEnergy,
        baselineEnergy = baselineEnergy,
    )
}

/**
 * The same world on a grid fitted to what it contains: the bounding box of every placed thing,
 * plus [pad] tiles on every side. Returns `this` unchanged if the grid is already that shape.
 *
 * This is the shorthand for "fit and throw the offset away" — the shorthand for everything
 * [fitToFrame] does. The detailed arithmetic lives in [fitToFrame]; [fitGrid] calls it and
 * discards the offset, which is all this function ever wanted at construction time where
 * nothing yet holds a coordinate to correct.
 *
 * The contract is `GridFitTest`. The two constraints a first attempt lost, stated once more
 * because they are the whole job:
 *
 * - The box encloses machine **footprints**, not anchors. `RockField.boundsOf` has this right.
 * - The box does **not** enclose rocks. They live outside the world by design.
 *
 * Fitting is also how a world **opts into keeping a pad**: the result records [pad] as its
 * [VesselState.gridPad], and the reducer then grows the grid to maintain it as the player builds.
 * A world that was never fitted keeps no pad and its frame never moves — see [growToFit].
 */
fun VesselState.fitGrid(pad: Int = GRID_PAD): VesselState = fitToFrame(pad).state

