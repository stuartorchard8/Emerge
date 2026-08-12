package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
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
 * [ventedGrams] and [extractedGrams] exist so conservation can be checked across the *whole world*,
 * not just one operation. A vent is the only place matter legitimately leaves and an extractor the
 * only place ore legitimately arrives, so `extracted == in-world + vented` must hold on every tick.
 * That invariant catches an entire category of logistics bug at once.
 *
 * Since H3 nothing mints ore: [extractedGrams] is mass that came **off a rock**, so it is a term in
 * the rock ledger too — hinging the ore and mass balances on the same number.
 *
 * There is no separate "banked" term any more: the [Stockpile] is derived from the storages, so what
 * it holds is already counted in [inTransitGrams] and adding it again would double-count.
 */
data class VesselState(
    val grid: Grid,
    /** The deck: buildings, walls, the things that take up floor space. */
    val machines: List<Machine?>,
    /**
     * The conduit layers — one grid of segments per network, sharing tiles freely with the deck
     * beneath and with each other.
     *
     * Separate from `machines` because that is what a layer *is*: track running under a smelter and
     * the smelter itself are both real, both at that tile, and neither is in the other's way. Keyed
     * by [Conduit] because the same is true between layers — see [Conduits] for what one list per
     * tile cost. Structure and air never look here; heat does, because a copper pipe in a hot room
     * has a temperature whether or not anything is flowing down it.
     */
    val conduits: Conduits = Conduits.empty(machines.size),
    /** Bridges, stored at their middle tile. They occupy nothing, so they are not in [occupancy]. */
    val bridges: List<Bridge?> = List(machines.size) { null },
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
     * stored: velocity is [vesselImpulseX] over [massGrams] and can always be recomputed, whereas a
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
     * Free-floating solids: rocks — see [RigidBody].
     *
     * ⚠️ **Not part of "aboard".** A rock is not in [inTransitGrams] and not in [massGrams], so it
     * neither breaks the mass balance nor slows the ship down while it is loose. Its mass has
     * its own ledger.
     */
    val bodies: List<RigidBody> = emptyList(),
    val tick: Long = 0L,
    val extractedGrams: Long = 0L,
    val ventedGrams: Long = 0L,

    /**
     * Cumulative joules put into the world by machines doing work, and cumulative joules radiated
     * away to space. The thermal counterpart of [extractedGrams] and [ventedGrams], and they buy the
     * same thing: `stored + radiated − generated` must never move, so an energy leak is one
     * assertion away rather than a mystery.
     */
    val generatedJoules: Long = 0L,
    val radiatedJoules: Long = 0L,
    /** Cumulative grams of atmosphere lost to space. Air's counterpart to [radiatedJoules]. */
    val airVentedGrams: Long = 0L,
    /**
     * Cumulative grams of atmosphere put into the world by [org.emerge.demo.outofspace.Edit.Inject]
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
    val injectedAirGrams: Long = 0L,
    /** The heat that came in with it — [injectedAirGrams]'s twin, for the energy ledger. */
    val injectedAirJoules: Long = 0L,
    /**
     * The channel values computed this tick. Kept in the snapshot rather than recomputed by the
     * renderer so that what is drawn is exactly what the sim acted on — and so a machine can be
     * drawn dimmed when its activation is zero, which is the answer to "why has this stopped".
     */
    val signals: SignalField = SignalField.none(machines.size),
    /**
     * Which signal network each tile is on, derived every tick — see [SignalNetworks].
     *
     * Kept beside [signals] rather than folded into it because the UI asks both questions: what is a
     * circuit carrying, and *is there a circuit here at all*. The second is the one that answers "why
     * is my machine not responding", and it deserves a straight answer rather than a zero.
     */
    val networks: SignalNetworks = SignalNetworks.derive(grid, conduits),
    /** Derived from where the hull is, every tick — see [StructureMap]. */
    val structure: StructureMap = StructureMap.derive(grid, machines),
    /** Which tiles each machine covers, derived every tick — see [Occupancy]. */
    val occupancy: Occupancy = Occupancy.derive(grid, machines),
    val air: AirField = AirField.ambient(grid, StructureMap.derive(grid, machines)),
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
     * Its heat lives inside it, for the reason [AirField.of] gives at length.
     */
    val pipeAir: AirField = AirField.of(LongArray(grid.size * Species.COUNT)),
    /** How the gas in the pipes is moving. The pipes' twin of [momentum], and state for the same reason. */
    val pipeMomentum: MomentumField = MomentumField.still(EdgeGrid(grid)),
    /**
     * What the atmosphere's energy started at — the gas's twin of [baselineAirGrams], and checked the
     * same way: `airJoules + airVentedJoules == baselineAirJoules` on every tick.
     *
     * The air's heat lives inside [AirField] rather than beside it here, and deliberately — see
     * [AirField.of]. It is the one arrangement `copy(air = …)` cannot desynchronise.
     */
    val baselineAirJoules: Long = air.totalJoules + pipeAir.totalJoules,
    /** Cumulative joules blown overboard with escaping gas. */
    val airVentedJoules: Long = 0L,
    /**
     * The energy the world's **solids** started with. Fixed at construction so the thermal balance
     * has something to be compared against — the twin of [baselineAirGrams]. Bodies are not solids
     * in the ledger — their energy enters the grid only when the extractor bites, at which point
     * [acquiredJoules] records the transfer.
     *
     * The balance it anchors is
     * `stored + radiated + solidToAir − generated − inserted − acquired == baseline`,
     * and the two terms beyond the obvious ones are:
     *
     *  - [insertedJoules], energy the player inserts via debug features (placing machines, etc.).
     *  - [solidToAirJoules], because the fabric and the atmosphere now exchange heat, so what one
     *    ledger loses the other gains. Counting it keeps both closed independently, which is what
     *    makes a break in one legible instead of being absorbed by the other.
     *  - [acquiredJoules], energy the grid acquires from bodies when the extractor bites — bodies
     *    are in [storedJoules] but their energy enters the grid from outside, so subtracting
     *    [acquiredJoules] cancels the double-count: `stored` holds the joules and `acquired`
     *    records the transfer so the ledger stays closed.
     */
    val baselineJoules: Long = solidJoules(machines, conduits, bridges),
    /**
     * Energy the player has inserted into the grid via debug features (placing machines, etc.).
     * Decreases when such things are scrapped.
     */
    val insertedJoules: Long = 0L,
    /**
     * Energy acquired by the grid from bodies via extractor bites. Bodies are not part of the grid
     * — their thermal energy only enters the ledger when the extractor takes it.
     */
    val acquiredJoules: Long = 0L,
    /** Cumulative net energy conducted from the solids into the atmosphere. Negative the other way. */
    val solidToAirJoules: Long = 0L,
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
    val baselineAirGrams: Long = air.totalGrams + pipeAir.totalGrams,
) {
    init {
        require(machines.size == grid.size) { "machine list is ${machines.size}, grid holds ${grid.size}" }
        require(conduits.tileCount == grid.size) {
            "conduit layers are ${conduits.tileCount}, grid holds ${grid.size}"
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
    val stockpile: Stockpile get() = Stockpile.of(machines)

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
        val openness = airlockOpenness(machines, signals)
        val edges = EdgeGrid(grid)
        val apertures = ApertureField.derive(edges, StructureMap.derive(grid, machines, openness), openness)
        diffuseFluid(edges, apertures, air.copyGrams(), joules = null).flow
    }

    /**
     * Every solid thing aboard, with its own temperature — see [Body]. Cached because the renderer
     * and the inspector both want it every frame while the state behind it changes once a tick.
     */
    val solids: List<Body> by lazy { bodiesOf(grid, machines, conduits, bridges) }

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
    fun kelvinAt(index: Int): Int = if (fabricKelvin[index] > 0) fabricKelvin[index] else air.kelvinAt(index)

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
            for (t in body.tiles) {
                if (!seen[t] || k > out[t]) out[t] = k
                seen[t] = true
            }
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
    fun airKelvinAt(index: Int): Int = air.kelvinAt(index)

    /** The machine covering a tile, wherever its centre happens to be. */
    fun machineCovering(index: Int): Machine? = machines.getOrNull(occupancy[index])

    /** The rail segment on a tile, if the layer has one there. */
    fun railAt(index: Int): Segment? = rails.getOrNull(index)

    /**
     * Every port any building or bridge exposes, keyed by the tile it sits on.
     *
     * Derived rather than stored, like everything else structural. Bridges are folded in here rather
     * than handled separately, which is the whole reason they need no special case: to the network a
     * bridge is a thing with an input port and an output port, exactly like a smelter.
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

    /** Every connection point of the machine stored at [index]. */
    fun portsAt(index: Int): List<Port> {
        val m = machines.getOrNull(index) ?: return emptyList()
        return portsOf(grid, m, index)
    }

    /** Thermal energy held by every solid thing aboard — the ledger quantity [baselineJoules] anchors. */
    val storedJoules: Long get() = solidJoules(machines, conduits, bridges)

    /** Total atmosphere still aboard, in the rooms and in the pipes — the ledger quantity. */
    val atmosphereGrams: Long get() = air.totalGrams + pipeAir.totalGrams

    /**
     * The heat that atmosphere is carrying, in the rooms and in the pipes — what [baselineAirJoules]
     * anchors.
     *
     * The counterpart to [atmosphereGrams], and it arrived a whole increment later than it should
     * have. The mass side got a both-fields total when the pipes were built; the energy side kept
     * reading `air.totalJoules` alone. That was invisible for exactly as long as the pipe layer was
     * sealed — no gas crossed, so no joules crossed — and the moment a valve opened, every joule that
     * went into the plumbing read as destroyed. The lesson is the ledger's own: two quantities that
     * share a baseline have to be summed the same way, and the second one is easy to forget because
     * nothing fails until something moves.
     */
    val atmosphereJoules: Long get() = air.totalJoules + pipeAir.totalJoules

    /**
     * How far the air ledger is out: `atmosphere + vented − injected − baseline`, which is **zero**,
     * always, on a world nothing has broken.
     *
     * One property rather than the sum written out at each of the four places that wanted it — the
     * HUD, the harness, the fixtures and the tests. That is not tidiness: [atmosphereJoules] arrived
     * a whole increment after [atmosphereGrams] because the two were summed at separate call sites
     * and only one of them learned about the pipes, and nothing failed until gas moved. Two
     * quantities that share a baseline have to be summed in one place, or eventually they are not
     * summed the same way.
     */
    val airBalance: Long get() = atmosphereGrams + airVentedGrams - injectedAirGrams - baselineAirGrams

    /**
     * The same statement about the air's energy — [airBalance]'s twin, and zero for its reasons.
     *
     * ⚠️ It carries **one term the mass identity does not**: [solidToAirJoules]. Heat crosses between
     * the fabric and the gas and mass never does, so what the solid ledger says it gave, this one has
     * to be holding. Leave it out and a warm room reads as a leak — which is exactly what the first
     * version of this property did.
     *
     * ⚠️ **PARKED — see [heatBalance].** This one is parked with it, for the same reason.
     */
    val airJouleBalance: Long
        get() = atmosphereJoules + airVentedJoules - solidToAirJoules - injectedAirJoules -
            baselineAirJoules

    /**
     * How far the solid energy ledger is out: `stored + radiated + toAir − generated − inserted −
     * acquired − baseline`, the third of the set, and zero on a world nothing has broken.
     *
     * Named here rather than spelled out by each caller because it was spelled out by **six** of
     * them — the HUD and five test files — and a seven-term identity restated seven times is the
     * shape of bug this codebase keeps paying for: [airJouleBalance] exists because its two halves
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
        get() = storedJoules + radiatedJoules + solidToAirJoules -
            generatedJoules - insertedJoules - acquiredJoules - baselineJoules

    /** Pressure of a tile as a percentage of one atmosphere, for readouts. */
    fun pressurePercentAt(index: Int): Int =
        (air.pressureAt(index) * 100 / AMBIENT_PRESSURE).toInt()

    operator fun get(index: Int): Machine? = machines.getOrNull(index)
    operator fun get(x: Int, y: Int): Machine? = if (grid.inBounds(x, y)) machines[grid.index(x, y)] else null

    /**
     * Every gram still aboard: in belts or machine buffers.
     */
    val inTransitGrams: Long get() = cargoGrams(machines, conduits, bridges)

    /**
     * What a thrust is divided by: the fabric, plus what it carries, and **not** the gas — see
     * [Flight].
     */
    val massGrams: Long get() = vesselMassGrams(machines, conduits, bridges)

    /**
     * How fast the vessel is going, in the billionths of a tile per tick [Flight.PER_TILE] counts.
     *
     * Derived rather than integrated: [vesselImpulseX] is the ship's momentum and this is that over
     * its mass, so there is no accumulated velocity to drift and nothing to be wrong about across a
     * save. See [Flight] for what counts as the ship and why the atmosphere does not.
     */
    val velocityX: Long get() = massGrams.let { if (it <= 0L) 0L else vesselImpulseX * Flight.PER_TILE / it }
    val velocityY: Long get() = massGrams.let { if (it <= 0L) 0L else vesselImpulseY * Flight.PER_TILE / it }

    /**
     * What everything loose aboard is actually falling toward: the plating, plus the engine.
     *
     * This is what the fluid is run under — [gravity] alone never is any more. See
     * [experiencedGravity] for the sign, and for why only one axis of the thrust survives.
     */
    val feltGravity: Frac2 get() = experiencedGravity(gravity, netImpulseX, netImpulseY, massGrams)

    /**
     * The ship's acceleration in its own frame, which is what makes the vessel frame a non-inertial
     * one and is therefore the only part of the ship a free rock can feel.
     *
     * The same quantity [experiencedGravity] subtracts from the plating for the gas, pulled out and
     * named because a rock outside the hull needs *this* half and must not be handed the plating —
     * see [feltBy]. Two consumers of one term beats two derivations of it.
     */
    val frameAcceleration: Frac2 get() = frameAcceleration(netImpulseX, netImpulseY, massGrams)

    fun withMachine(index: Int, machine: Machine?): VesselState =
        copy(machines = machines.toMutableList().also { it[index] = machine })

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

        fun empty(grid: Grid): VesselState = VesselState(grid, List(grid.size) { null })
    }
}

/**
 * What falls on the floor when a machine is taken apart: everything it was holding, keeping forms
 * separate. Defined in terms of [contentsBreakdown] so there is exactly one list of "where a machine
 * keeps things" — a second one would drift, and the drift would look like a conservation bug.
 */
fun spoilsOf(machine: Machine?): List<Resource> =
    contentsBreakdown(machine).map { it.second }.filter { !it.isEmpty }

/** Total mass held by one machine, wherever it keeps it. Used for world-wide conservation checks. */
fun massIn(machine: Machine?): Long = when (machine) {
    null -> 0L
    is Bridge -> machine.mass
    is Extractor -> (machine.input?.mass ?: 0L) + machine.buffer.mass
    is Processor -> (machine.input?.mass ?: 0L) + (machine.product?.mass ?: 0L) + (machine.tailings?.mass ?: 0L)
    is Vaporizer -> (machine.input?.mass ?: 0L)
    is Smelter -> (machine.input?.mass ?: 0L) + (machine.refined?.mass ?: 0L) + (machine.slag?.mass ?: 0L)
    is Storage -> machine.contents?.mass ?: 0L
    is Sensor, is KeyInput -> 0L
    is Hull, is Airlock -> 0L
    is Vent -> 0L
    is Pump -> 0L
}

/**
 * How full a machine is, 0..1000 permille — the one number a [Sensor] reads.
 *
 * Every machine answers, so a sensor can be pointed at anything and mean something. The reference
 * capacity differs by kind (a belt's is its slots, a storage's is its tank), which is the point: the
 * question a sensor asks is "is this backing up?", not "how many grams".
 */
fun fullness(machine: Machine?): Int = when (machine) {
    null -> 0
    is Bridge -> machine.carried.size * SignalField.FULL / Bridge.SLOTS
    is Extractor -> (machine.buffer.mass * SignalField.FULL / Extractor.BUFFER_CAP).toInt()
    is Processor -> (massIn(machine) * SignalField.FULL / (MACHINE_BUFFER_CAP + MACHINE_OUTPUT_CAP * 2)).toInt()
    is Vaporizer -> (massIn(machine) * SignalField.FULL / MACHINE_BUFFER_CAP).toInt()
    is Smelter -> (massIn(machine) * SignalField.FULL / (MACHINE_BUFFER_CAP + MACHINE_OUTPUT_CAP * 2)).toInt()
    is Storage -> ((machine.contents?.mass ?: 0L) * SignalField.FULL / Storage.CAP).toInt()
    is Sensor, is KeyInput -> 0
    is Hull, is Airlock -> 0
    is Vent -> 0
    is Pump -> 0
}.coerceIn(0, SignalField.FULL)

/**
 * A machine's contents broken out by the buffer they sit in, for the inspector.
 *
 * Named buffers rather than one lump, because "this processor holds 6kg" is far less useful than
 * "3kg waiting, 2kg of concentrate, 1kg of tailings" — the second tells you which side is stuck.
 */
fun contentsBreakdown(machine: Machine?): List<Pair<String, Resource>> = when (machine) {
    null -> emptyList()
    // Slot by slot, input end first: "which end of the span is it on" is the only thing worth
    // knowing about a bridge, and one lump labelled IN TRANSIT could not say it.
    is Bridge -> listOf("IN" to machine.entry, "SPAN" to machine.middle, "OUT" to machine.exit)
        .mapNotNull { (label, p) ->
            if (p == null) null else {
                val form = (p as? org.emerge.demo.outofspace.logistics.SolidPacket)?.form ?: Form.Ore
                label to Resource(form, p.contents)
            }
        }
    is Extractor -> listOfNotNull(machine.input?.let { "CRUSHING" to it }, "BUFFER" to machine.buffer)
    is Processor -> listOfNotNull(
        machine.input?.let { "INPUT" to it },
        machine.product?.let { "CONCENTRATE" to it },
        machine.tailings?.let { "TAILINGS" to it },
    )
    is Vaporizer -> listOfNotNull(
        machine.input?.let { "INPUT" to it },
    )
    is Smelter -> listOfNotNull(
        machine.input?.let { "INPUT" to it },
        machine.refined?.let { "REFINED" to it },
        machine.slag?.let { "SLAG" to it },
    )
    is Storage -> listOfNotNull(machine.contents?.let { "STORED" to it })
    is Sensor, is KeyInput, is Vent, is Pump, is Hull, is Airlock -> emptyList()
}

/** Everything a machine holds, species by species — the finer-grained version of [massIn]. */
fun contentsOf(machine: Machine?): Mixture = when (machine) {
    null -> Mixture.EMPTY
    is Bridge -> machine.carried.fold(Mixture.EMPTY) { acc, p -> acc + p.contents }
    is Extractor -> (machine.input?.mixture ?: Mixture.EMPTY) + machine.buffer.mixture
    is Processor -> (machine.input?.mixture ?: Mixture.EMPTY) +
        (machine.product?.mixture ?: Mixture.EMPTY) + (machine.tailings?.mixture ?: Mixture.EMPTY)
    is Vaporizer -> machine.input?.mixture ?: Mixture.EMPTY
    is Smelter -> (machine.input?.mixture ?: Mixture.EMPTY) +
        (machine.refined?.mixture ?: Mixture.EMPTY) + (machine.slag?.mixture ?: Mixture.EMPTY)
    is Storage -> machine.contents?.mixture ?: Mixture.EMPTY
    is Sensor, is KeyInput -> Mixture.EMPTY
    is Hull, is Airlock -> Mixture.EMPTY
    is Vent -> Mixture.EMPTY
    is Pump -> Mixture.EMPTY
}

/**
 * The same world on a different lattice, translated by (dx, dy) tiles.
 *
 * Cells that exist in both grids keep everything; cells only in the new one are vacuum.
 * The old origin lands at (dx, dy) in the new grid, so growing left by 4 is dx = +4.
 *
 * On a shrink, the gas in a discarded cell is **vented** to `airVentedGrams`/`airVentedJoules` and
 * its face momentum to `exhaustMomentumX/Y`, so every ledger holds. A discarded **solid** throws
 * instead: the grid is fitted around the solids, so losing one means the bounds were wrong. Rocks
 * are not grid-indexed and are translated, never discarded. See `PLAN_dynamic_grid.md` §5.
 */
fun VesselState.remapped(newGrid: Grid, dx: Int, dy: Int): VesselState {
    require(newGrid.size > 0) { "new grid must be non-empty" }

    for (i in machines.indices) {
        if (machines[i] == null) continue
        val ox = grid.xOf(i); val oy = grid.yOf(i)
        require(newGrid.inBounds(ox + dx, oy + dy)) {
            "remap would discard a machine at ($ox, $oy)"
        }
    }
    for (i in bridges.indices) {
        if (bridges[i] == null) continue
        val ox = grid.xOf(i); val oy = grid.yOf(i)
        require(newGrid.inBounds(ox + dx, oy + dy)) {
            "remap would discard a bridge at ($ox, $oy)"
        }
    }
    for (c in Conduit.entries) {
        for (i in conduits[c].indices) {
            if (conduits[c][i] == null) continue
            val ox = grid.xOf(i); val oy = grid.yOf(i)
            require(newGrid.inBounds(ox + dx, oy + dy)) {
                "remap would discard a ${c.label} conduit at ($ox, $oy)"
            }
        }
    }

    val oldW = grid.width
    val oldH = grid.height
    val oldSize = grid.size

    // ── helpers ───────────────────────────────────────────────────────────
    fun remapTile(ox: Int, oy: Int): Int? {
        val nx = ox + dx
        val ny = oy + dy
        return if (newGrid.inBounds(nx, ny)) newGrid.index(nx, ny) else null
    }

    // ── 1. Tile-indexed lists: machines, bridges ─────────────────────────
    val newMachines = MutableList(newGrid.size) { null as Machine? }
    for (ox in 0 until oldW) for (oy in 0 until oldH) {
        val ni = remapTile(ox, oy) ?: continue
        val oi = grid.index(ox, oy)
        newMachines[ni] = machines[oi]
    }
    val newBridges = MutableList(newGrid.size) { null as Bridge? }
    for (ox in 0 until oldW) for (oy in 0 until oldH) {
        val ni = remapTile(ox, oy) ?: continue
        val oi = grid.index(ox, oy)
        newBridges[ni] = bridges[oi]
    }

    // ── 2. Conduits: remap each layer ────────────────────────────────────
    var newConduits = Conduits.empty(newGrid.size)
    for (c in Conduit.entries) {
        val oldLayer = conduits[c]
        val newLayer = MutableList(newGrid.size) { null as Segment? }
        for (ox in 0 until oldW) for (oy in 0 until oldH) {
            val ni = remapTile(ox, oy) ?: continue
            val oi = grid.index(ox, oy)
            newLayer[ni] = oldLayer[oi]
        }
        newConduits = newConduits.with(c, newLayer)
    }

    // ── 3. Sparse map for diverters ──────────────────────────────────────
    fun remapCursors(src: Map<Int, Int>): HashMap<Int, Int> {
        val out = HashMap<Int, Int>()
        for ((oldTile, cursor) in src) {
            val ox = grid.xOf(oldTile)
            val oy = grid.yOf(oldTile)
            val ni = remapTile(ox, oy)
            if (ni != null) out[ni] = cursor
        }
        return out
    }
    val newDiverters = FlowCursors(remapCursors(diverters.snapshot()), remapCursors(diverters.mergeSnapshot()))

    // ── 4. Dense field arrays: air / pipeAir (grams + joules) ────────────
    fun remapAirField(src: AirField): AirField {
        val newGrams = LongArray(newGrid.size * Species.COUNT)
        val oldJoules = src.copyJoules()
        val newJoules = LongArray(newGrid.size)
        for (ox in 0 until oldW) for (oy in 0 until oldH) {
            val ni = remapTile(ox, oy) ?: continue
            val oi = grid.index(ox, oy)
            val baseN = ni * Species.COUNT
            val baseO = oi * Species.COUNT
            for (s in Species.entries) {
                newGrams[baseN + s.ordinal] = src.gramsOf(oi, Species.entries[s.ordinal])
            }
            newJoules[ni] = oldJoules[oi]
        }
        return AirField.of(newGrams, newJoules)
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

    // ── 6. Bodies: shift positions ───────────────────────────────────────
    val newBodies = bodies.map {
        it.copy(
            positionX = it.positionX + dx * Flight.PER_TILE.toLong(),
            positionY = it.positionY + dy * Flight.PER_TILE.toLong(),
        )
    }

    // ── 7. Vent whatever the new grid does not cover ─────────────────────
    // As a difference of totals rather than a walk of the discarded cells: exact by construction,
    // with no edge index to get wrong. Zero on a grow, so that needs no special case.
    val ventedGas    = (air.totalGrams + pipeAir.totalGrams) - (newAir.totalGrams + newPipeAir.totalGrams)
    val ventedJoules = (air.totalJoules + pipeAir.totalJoules) - (newAir.totalJoules + newPipeAir.totalJoules)
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
        machines = newMachines,
        conduits = newConduits,
        bridges = newBridges,
        diverters = newDiverters,
        air = newAir,
        pipeAir = newPipeAir,
        momentum = newMomentum,
        pipeMomentum = newPipeMomentum,
        bodies = newBodies,
        // vented quantities — grow: difference is zero, no special case needed
        airVentedGrams = airVentedGrams + ventedGas,
        airVentedJoules = airVentedJoules + ventedJoules,
        exhaustMomentumX = exhaustMomentumX + ventedMomX,
        exhaustMomentumY = exhaustMomentumY + ventedMomY,
        // baselines passed through explicitly to avoid recompute on copy
        baselineAirGrams = baselineAirGrams,
        baselineAirJoules = baselineAirJoules,
        baselineJoules = baselineJoules,
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

