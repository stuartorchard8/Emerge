package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.demo.outofspace.world.fluid.AMBIENT_PRESSURE
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.FlowField
import org.emerge.demo.outofspace.world.fluid.MomentumField
import org.emerge.demo.outofspace.world.fluid.tileMass
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * The whole world at one instant: a tile grid of machines, the global stockpile, and the vessel's
 * own gravity.
 *
 * [gravity] is here — in state, as a vector — even though nothing reads it yet. Per the plan, that is
 * the cheap insurance that keeps acceleration-derived gravity a later decision rather than a
 * rewrite: no code is allowed to assume "down" is a constant or implied by array order.
 *
 * [ventedGrams] and [minedGrams] exist so conservation can be checked across the *whole world*, not
 * just one operation. A vent is the only place matter legitimately leaves and a miner the only place
 * it legitimately arrives, so `mined == in-world + vented` must hold on every tick. That invariant
 * catches an entire category of logistics bug at once.
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
    /** Which way each fork last sent material — see [Diverters]. */
    val diverters: Diverters = Diverters.EMPTY,
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
    /** Loose material lying on the deck — see [Debris]. Part of "aboard" for conservation purposes. */
    val debris: Debris = Debris.EMPTY,
    /**
     * Free-floating solids: rocks — see [Rock].
     *
     * A list of its own, and neither a machine nor debris, because it can be neither: [bodiesOf]
     * derives bodies from the three built lists and would not see it, and [settleDebris] walks
     * anything standing in vacuum off the rim and books it as vented, which is the correct rule for
     * a spilled heap and exactly wrong for a rock.
     *
     * ⚠️ **Not part of "aboard".** A rock is not in [inTransitGrams] and not in [massGrams], so it
     * neither breaks the mass balance nor slows the ship down while it is loose. Both of those change
     * when it is *held* rather than merely present, which is the extractor's increment. Its mass has
     * its own ledger meanwhile — see [rockGrams].
     */
    val rocks: List<Rock> = emptyList(),
    val tick: Long = 0L,
    val minedGrams: Long = 0L,
    val ventedGrams: Long = 0L,
    /**
     * Cumulative grams of rock that have arrived from outside the world, and the rock mass the world
     * started with. Together they are rock's answer to `mined == aboard + vented`:
     *
     *     rockGrams == baselineRockGrams + capturedGrams
     *
     * Its own ledger rather than a term in the ore balance, and that is the whole point of it. A
     * rock is **new mass in a closed world**, and the obvious place to put it — [minedGrams] — is the
     * one place it must not go: the miner is a stand-in that the extractor exists to delete, and
     * building the hold on top of it would tie the replacement to the thing being replaced. See
     * `docs/out-of-space-plan.md` §5f and open question 5.
     *
     * The extractor gains a third term here in H3, for mass that has left a rock and become ore, and
     * at that point this is the ledger that proves nothing was created on the way across.
     */
    val capturedGrams: Long = 0L,
    /**
     * The rock mass the world started with, fixed at construction so [capturedGrams] has something
     * to be measured against. The twin of [baselineJoules] and [baselineAirGrams], for the reason
     * they both give: a quantity that can arrive from outside needs a fixed point, or "how much is
     * here" is not a statement about anything.
     */
    val baselineRockGrams: Long = rocks.sumOf { it.massGrams },
    /**
     * Cumulative joules put into the world by machines doing work, and cumulative joules radiated
     * away to space. The thermal counterpart of [minedGrams] and [ventedGrams], and they buy the
     * same thing: `stored + radiated − generated` must never move, so an energy leak is one
     * assertion away rather than a mystery.
     */
    val generatedJoules: Long = 0L,
    val radiatedJoules: Long = 0L,
    /** Cumulative grams of atmosphere lost to space. Air's counterpart to [radiatedJoules]. */
    val airVentedGrams: Long = 0L,
    /**
     * The channel values computed this tick. Kept in the snapshot rather than recomputed by the
     * renderer so that what is drawn is exactly what the sim acted on — and so a machine can be
     * drawn dimmed when its activation is zero, which is the answer to "why has this stopped".
     */
    val signals: Signals = Signals.build { },
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
     * has something to be compared against — the twin of [baselineAirGrams].
     *
     * The balance it anchors is
     * `stored + radiated + solidToAir − generated − construction == baseline`,
     * and the two terms beyond the obvious ones are what the body model costs:
     *
     *  - [constructionJoules], because a body carries its energy with it. Building a wall brings a
     *    wall's worth of room-temperature heat into the world and scrapping one takes it away, and
     *    neither is a leak. The old per-tile field hid this by charging every tile a capacity
     *    whether or not anything was standing on it.
     *  - [solidToAirJoules], because the fabric and the atmosphere now exchange heat, so what one
     *    ledger loses the other gains. Counting it keeps both closed independently, which is what
     *    makes a break in one legible instead of being absorbed by the other.
     */
    val baselineJoules: Long = solidJoules(machines, conduits, bridges, rocks),
    /**
     * Net energy that has arrived in the world inside newly built bodies, less what left inside
     * scrapped ones. Signed, and one term rather than two, because only the difference is ever read.
     */
    val constructionJoules: Long = 0L,
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
     * [org.emerge.demo.outofspace.world.fluid.ProjectionResult.undeliveredX].
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
     * Cumulative momentum the vessel has handed the rocks — by hitting them, and by pulling on them
     * with the deck plating. See [RockContact] and [driftRocks].
     *
     * The sixth store, and unlike [debugImpulseX] it is **not** an apology for a shortcut. Nothing is
     * minted here: `+J` goes to the rock, `−J` to the ship, and the pair conserves by construction.
     * The term exists because only one of those two halves is inside the ledger. [vesselImpulseX] is
     * a ledger quantity and a rock's momentum is not, so an exchange with no name would read as the
     * ship gaining momentum from nowhere — which is exactly the reading the ledger is for. So the
     * identity becomes
     *
     *     vesselImpulse + momentum + pipeMomentum + exhaust + undelivered + rock − debug == 0
     *
     * and `rock` is the same kind of thing [exhaustMomentumX] is: momentum that is genuinely
     * somewhere else now. It stops being a separate store the day a rock is *held* rather than
     * merely present, because then it is part of the ship and the exchange is internal.
     *
     * ⚠️ **The plating pays into it too**, and that is not tidiness. A field the vessel makes is a
     * force the vessel exerts: charge it for the contact and not for the pull and a rock resting on
     * the deck becomes a thruster — pushed down for free, pushed back up with a reaction — and the
     * ledger balances the whole time, because the free half never enters it. Zero under freefall,
     * which is every ship; it is the 1 g fixtures and H4's capture that would have found out.
     */
    val rockImpulseX: Long = 0L,
    val rockImpulseY: Long = 0L,
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
     * Presentation and inspection only; the sim reads [momentum] directly. Cached because the flow
     * overlay wants the whole field every frame while the state behind it only changes once a tick,
     * and rebuilding it sixty times for one tick's worth of answer would be sixty times the work for
     * the same picture.
     */
    val flow: FlowField by lazy { FlowField.derive(EdgeGrid(grid), momentum, tileMass(grid.size, air.copyGrams())) }

    /**
     * Every solid thing aboard, with its own temperature — see [Body]. Cached because the renderer
     * and the inspector both want it every frame while the state behind it changes once a tick.
     */
    val bodies: List<Body> by lazy { bodiesOf(grid, machines, conduits, bridges) }

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
    fun kelvinAt(index: Int): Int = fabricKelvin[index]

    /**
     * The hottest body on each tile, folded once. The heat overlay asks for every tile every frame,
     * and answering each one by scanning the body list would be the whole world times the whole
     * world for a picture that changes once a tick.
     */
    private val fabricKelvin: IntArray by lazy {
        val out = IntArray(grid.size) { Temperature.AMBIENT_KELVIN }
        val seen = BooleanArray(grid.size)
        for (body in bodies) {
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
    val storedJoules: Long get() = solidJoules(machines, conduits, bridges, rocks)

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

    /** Pressure of a tile as a percentage of one atmosphere, for readouts. */
    fun pressurePercentAt(index: Int): Int =
        (air.pressureAt(index) * 100 / AMBIENT_PRESSURE).toInt()

    operator fun get(index: Int): Machine? = machines.getOrNull(index)
    operator fun get(x: Int, y: Int): Machine? = if (grid.inBounds(x, y)) machines[grid.index(x, y)] else null

    /**
     * Every gram still aboard: in belts, in machine buffers, and lying loose on the deck.
     *
     * Debris counts. Taking a machine apart moves its contents from one term of this sum to another
     * rather than removing them, which is exactly why dismantling stopped reading as a leak.
     */
    val inTransitGrams: Long get() = cargoGrams(machines, conduits, bridges, debris)

    /**
     * What a thrust is divided by: the fabric, plus what it carries, and **not** the gas — see
     * [Flight].
     */
    val massGrams: Long get() = vesselMassGrams(machines, conduits, bridges, debris)

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
     * This is what the fluid and the debris are run under — [gravity] alone never is any more. See
     * [experiencedGravity] for the sign, and for why only one axis of the thrust survives.
     */
    val feltGravity: Frac2 get() = experiencedGravity(gravity, netImpulseX, netImpulseY, massGrams)

    /** Just the loose material, for the readout that distinguishes "stored" from "spilled". */
    val debrisGrams: Long get() = debris.totalGrams

    /** Every gram of free-floating rock in the world — the left-hand side of the rock ledger. */
    val rockGrams: Long get() = rocks.sumOf { it.massGrams }

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
         *  - **Debris neither settles nor goes overboard**, because [settleDebris] bails when
         *    [downDirection] has no answer, and "falls off the edge of the grid" was the whole
         *    mechanism by which a breached room lost its heaps. In freefall loose material would be
         *    *dragged* out by the airflow instead, which is a mechanism this game does not have yet.
         *    That is a gap this opens rather than one it creates, and it belongs with the fluid
         *    coupling in §5f H5.
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
    is Miner -> machine.buffer.mass
    is Processor -> (machine.input?.mass ?: 0L) + (machine.product?.mass ?: 0L) + (machine.tailings?.mass ?: 0L)
    is Smelter -> (machine.input?.mass ?: 0L) + (machine.refined?.mass ?: 0L) + (machine.slag?.mass ?: 0L)
    is Storage -> machine.contents?.mass ?: 0L
    is Sensor -> 0L
    is Hull -> 0L
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
    is Bridge -> machine.carried.size * Signals.FULL / Bridge.SLOTS
    is Miner -> (machine.buffer.mass * Signals.FULL / Miner.BUFFER_CAP).toInt()
    is Processor -> (massIn(machine) * Signals.FULL / (MACHINE_BUFFER_CAP + MACHINE_OUTPUT_CAP * 2)).toInt()
    is Smelter -> (massIn(machine) * Signals.FULL / (MACHINE_BUFFER_CAP + MACHINE_OUTPUT_CAP * 2)).toInt()
    is Storage -> ((machine.contents?.mass ?: 0L) * Signals.FULL / Storage.CAP).toInt()
    is Sensor -> 0
    is Hull -> 0
    is Vent -> 0
    is Pump -> 0
}.coerceIn(0, Signals.FULL)

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
    is Miner -> listOf("BUFFER" to machine.buffer)
    is Processor -> listOfNotNull(
        machine.input?.let { "INPUT" to it },
        machine.product?.let { "CONCENTRATE" to it },
        machine.tailings?.let { "TAILINGS" to it },
    )
    is Smelter -> listOfNotNull(
        machine.input?.let { "INPUT" to it },
        machine.refined?.let { "REFINED" to it },
        machine.slag?.let { "SLAG" to it },
    )
    is Storage -> listOfNotNull(machine.contents?.let { "STORED" to it })
    is Sensor, is Vent, is Pump, is Hull -> emptyList()
}

/** Everything a machine holds, species by species — the finer-grained version of [massIn]. */
fun contentsOf(machine: Machine?): Mixture = when (machine) {
    null -> Mixture.EMPTY
    is Bridge -> machine.carried.fold(Mixture.EMPTY) { acc, p -> acc + p.contents }
    is Miner -> machine.buffer.mixture
    is Processor -> (machine.input?.mixture ?: Mixture.EMPTY) +
        (machine.product?.mixture ?: Mixture.EMPTY) + (machine.tailings?.mixture ?: Mixture.EMPTY)
    is Smelter -> (machine.input?.mixture ?: Mixture.EMPTY) +
        (machine.refined?.mixture ?: Mixture.EMPTY) + (machine.slag?.mixture ?: Mixture.EMPTY)
    is Storage -> machine.contents?.mixture ?: Mixture.EMPTY
    is Sensor -> Mixture.EMPTY
    is Hull -> Mixture.EMPTY
    is Vent -> Mixture.EMPTY
    is Pump -> Mixture.EMPTY
}
