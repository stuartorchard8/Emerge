package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.InputKey
import org.emerge.demo.outofspace.world.machine.Rocket
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.ThrusterControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A rocket that makes its own heat.**
 *
 * The second engine (`PLAN_chemical_rockets.md` §6), and the first machine in the game with two
 * different things coming in at two different doors. What is worth pinning:
 *
 *  - ⛔ **the win is molar mass, not energy.** `richer than stoichiometric throws faster` is that
 *    claim made falsifiable, and it is the one a reader will not believe: the *cooler* mixture is
 *    the better engine, because unburnt hydrogen drags the mean molar mass down faster than the
 *    enthalpy it did not release costs.
 *  - **the two doors mean different things**, which is why [BufferRole.Oxidiser] exists — and which
 *    is only true if a delivery is routed by the tile it arrived at rather than by the machine.
 *  - ⛔ **it never gates on its setpoint.** A rocket that held its charge until it was hot enough
 *    would be a duty cycle, and two of them out of phase would wobble the ship.
 *  - **it burns**, which needs no code in this machine at all: a store reacts with itself, so a
 *    chamber over 773 K holding hydrogen and oxygen is a combustion chamber for free.
 */
class RocketTest {

    private val grid = Grid(20, 14)

    /**
     * How long a cold engine is given to reach its onset once the stick moves.
     *
     * Generous against the two-or-three ticks the arithmetic predicts, because what is being pinned
     * is *a light-up rather than a stall* — an exact count here would be a test of the mixture's
     * heat capacity wearing a rocket's clothes.
     */
    private val LIGHT_UP_TICKS = 20

    /**
     * How long a full chamber is given to empty itself once the stick is released.
     *
     * The arithmetic says five — a quarter share taken off a full chamber until
     * [Rocket.EXHAUST_FLOOR] takes the rest in one go — and this is loose against that for
     * [LIGHT_UP_TICKS]' reason: what is pinned is *that it drains*, not the shape of the curve.
     */
    private val SPOOL_DOWN_TICKS = 20

    /** The engine at (8,6) facing right: fuel (7,5), oxidiser (7,7), chamber (8,6), bell (9,6). */
    private val engineAt = grid.tile(8, 6)
    private val fuelTank = grid.tile(2, 5)
    private val oxidiserTank = grid.tile(2, 11)

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        val cfg = OutofspaceConfig(initialGrid = state.grid)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * An engine standing in vacuum with both doors hand-stocked.
     *
     * ⚠️ **Vacuum on purpose**, for `ThrusterTest`'s reason: with air aboard the hull rings and every
     * claim about a temperature or a total acquires a tolerance. What is being measured here is the
     * chamber.
     */
    private fun engine(
        fuel: Mixture = hydrogen(),
        oxidiser: Mixture = oxygen(),
        mix: Int = Rocket.DEFAULT_FUEL_PERMILLE,
        propellant: Species? = null,
    ): VesselState {
        val deck = DeckArray(grid)
        deck += Rocket(
            engineAt, Direction.Right,
            fuelPermille = mix, propellant = propellant, control = ThrusterControl.Wire,
        )
        return VesselState(
            grid, deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
            .stocked(engineAt, fuel, BufferRole.Input)
            .stocked(engineAt, oxidiser, BufferRole.Oxidiser)
    }

    /**
     * The same engine fed down two belts from two tanks, one per door.
     *
     * The only fixture that can say anything about **which door is which**, because stocking a store
     * by hand names the role and so bypasses the entire question. The two runs never touch: the
     * middle of the engine's back face carries no track, so a lump on one belt has no route to the
     * other door.
     *
     * [upper] is what the belt into the **top** door carries and [lower] what the bottom one does —
     * named for the *geometry* and not for fuel and oxidiser, because which is which is precisely
     * what the propellant tests are asking about. [propellant] locks the engine, or leaves it
     * unlocked at null.
     */
    private fun plumbed(
        upper: Mixture = hydrogen(8),
        lower: Mixture = oxygen(8),
        propellant: Species? = null,
    ): VesselState {
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        deck += Rocket(engineAt, Direction.Right, propellant = propellant, control = ThrusterControl.Wire)  // covers x 7..9, y 5..7
        deck += fixtureStorage(fuelTank, Direction.Right)        // pours right from (3,5)
        deck += fixtureStorage(oxidiserTank, Direction.Right)    // pours right from (3,11)
        joinRow(grid, rails, 3, 7, 5)                            // fuel tank → the upper door
        joinRow(grid, rails, 3, 7, 11)                           // oxidiser tank → x = 7 …
        joinCol(grid, rails, 7, 7, 11)                           // … and up to the lower door
        return VesselState(
            grid, deck,
            air = Stuff.gas(MassArray(grid.size)),
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
            .stocked(fuelTank, upper)
            .stocked(oxidiserTank, lower)
    }

    private fun hydrogen(packets: Long = 20L): Mixture =
        Mixture.of(Species.Hydrogen to packets * Capacity.PACKET_MASS, energy = 0L).atAmbient()

    private fun oxygen(packets: Long = 20L): Mixture =
        Mixture.of(Species.Oxygen to packets * Capacity.PACKET_MASS, energy = 0L).atAmbient()

    private fun store(s: VesselState, role: BufferRole): Mixture? = s.inStore(engineAt, role)

    private fun chamber(s: VesselState): Mixture = store(s, BufferRole.Inside) ?: Mixture.EMPTY

    private fun chamberKelvin(s: VesselState): Int =
        s.buffers.stuff.kelvinAt(bufferTile(grid, s.deck[engineAt]!!, engineAt, BufferRole.Inside)!!)

    // ── The doors ────────────────────────────────────────────────────────────

    /**
     * ⭐ **The claim the propellant lock exists for: the belts may be the wrong way round.**
     *
     * The same fixture as `the two rear doors fill two different stores`, with the two tanks' cargo
     * swapped — oxygen down the belt into the top door, hydrogen down the one into the bottom — and
     * the *same assertions*. An engine that knows it burns hydrogen puts hydrogen in the fuel store
     * whichever mouth it came in at, so a vessel whose fuel line has to come round the outside is a
     * vessel, not a mistake.
     *
     * ⛔ Route by the tile, as an unlocked motor still does, and this fails in the way the player
     * hits it: a `1:2` engine running `2:1`, oxidiser-rich, quietly throwing away a third of its
     * exhaust velocity with nothing on the panel to say why.
     */
    @Test
    fun `a locked engine sorts by what arrived, not by which door it arrived at`() {
        val after = run(plumbed(upper = oxygen(8), lower = hydrogen(8), propellant = Species.Hydrogen), 120)

        val fuel = store(after, BufferRole.Input) ?: Mixture.EMPTY
        val oxidiser = store(after, BufferRole.Oxidiser) ?: Mixture.EMPTY
        val burned = chamber(after)

        assertTrue(fuel[Species.Hydrogen] + burned.total > 0L, "no hydrogen reached the fuel store")
        assertTrue(oxidiser[Species.Oxygen] > 0L, "no oxygen reached the oxidiser store")
        // The strict half, and the whole point: neither store holds a gram of the other's species,
        // even though the belts feeding them are crossed.
        assertEquals(0L, fuel[Species.Oxygen], "oxygen reached the fuel store")
        assertEquals(0L, oxidiser[Species.Hydrogen], "hydrogen reached the oxidiser store")
    }

    /**
     * ⚠️ **The other half of the same rule: unlocked still routes by the tile.**
     *
     * Every rocket in every save that predates the lock is unlocked, and they were all plumbed by a
     * player who knew which door was which. Sorting them by contents would silently re-plumb a
     * working vessel, so an engine that has named no propellant behaves exactly as it always did —
     * including getting it wrong when the belts are crossed, which is what this pins.
     */
    @Test
    fun `an unlocked engine still routes by the door`() {
        val after = run(plumbed(upper = oxygen(8), lower = hydrogen(8), propellant = null), 120)

        val fuel = store(after, BufferRole.Input) ?: Mixture.EMPTY
        val oxidiser = store(after, BufferRole.Oxidiser) ?: Mixture.EMPTY

        assertEquals(0L, fuel[Species.Hydrogen], "an unlocked engine sorted hydrogen into the fuel store")
        assertTrue(fuel[Species.Oxygen] > 0L, "the top door did not fill the fuel store")
        assertTrue(oxidiser[Species.Hydrogen] > 0L, "the bottom door did not fill the oxidiser store")
    }

    // ── What the lock is worth ───────────────────────────────────────────────

    /**
     * ⛔ **The mole ratio becomes a mass ratio, and the table is the only place it comes from.**
     *
     * `2 H₂ + O₂` is 4 g of hydrogen against 32 of oxygen — a ninth by mass, which is the `1:8` the
     * class note calls the textbook mixture and the worst one available. That it lands exactly on
     * [Rocket.RATIOS]' bottom rung is not a coincidence: the ladder was built to span this point.
     */
    @Test
    fun `the locked row prices a clean burn`() {
        val hydrolox = Rocket(engineAt, Direction.Right, propellant = Species.Hydrogen)
        assertEquals(111, hydrolox.stoichiometricFuelPermille, "hydrolox does not burn clean at 1:8")
        assertEquals(Rocket.RATIOS.first(), hydrolox.stoichiometricFuelPermille)
        assertEquals(Species.Oxygen, hydrolox.oxidiser, "hydrogen does not burn in oxygen")
        // ⚠️ Methane's row, to prove the figure is read and not hydrogen's number in disguise:
        // CH₄ + 2 O₂ is 16 g against 64, a fifth.
        val methalox = Rocket(engineAt, Direction.Right, propellant = Species.Methane)
        assertEquals(200, methalox.stoichiometricFuelPermille, "methalox does not burn clean at 1:4")
    }

    /**
     * ⚠️ **The onset follows the propellant**, which is what the chamber readout is coloured by.
     * Hydrogen lights at 773 K and methane at 810, and a fleet constant would call a methalox
     * chamber lit while it was still thirty kelvin short of burning anything.
     */
    @Test
    fun `ignition is the locked row's onset`() {
        val unlocked = Rocket(engineAt, Direction.Right)
        assertEquals(Rocket.IGNITION_KELVIN, unlocked.ignitionKelvin, "an unlocked engine lost hydrogen's onset")
        assertEquals(773, Rocket(engineAt, Direction.Right, propellant = Species.Hydrogen).ignitionKelvin)
        assertEquals(810, Rocket(engineAt, Direction.Right, propellant = Species.Methane).ignitionKelvin)
    }

    /**
     * ⛔ **Every rung of the dial is a whole engine**, because the list is derived rather than typed:
     * a row added to [org.emerge.demo.outofspace.chem.REACTIONS] appears here the same day. So the
     * thing worth asserting is not which propellants exist but that each one answers every question
     * the machine asks of it — a rung that named no oxidiser would be a lock that opens one door.
     */
    @Test
    fun `every propellant is a complete engine`() {
        assertTrue(Rocket.PROPELLANTS.size >= 2, "the propellant ladder collapsed")
        for (row in Rocket.PROPELLANTS) {
            val m = Rocket(engineAt, Direction.Right, propellant = row.principal)
            assertEquals(row.principal, m.propellant)
            assertTrue(m.oxidiser != null, "${row.principal} burns in nothing")
            assertTrue(m.oxidiser != m.propellant, "${row.principal} burns in itself")
            val clean = m.stoichiometricFuelPermille
            assertTrue(clean != null && clean in 1..999, "${row.principal} has no mass ratio")
            assertTrue(m.ignitionKelvin > 0, "${row.principal} has no onset")
        }
        // The two the design was written against, so that a table edit that dropped either is a
        // failure here rather than a menu that quietly got shorter.
        val fuels = Rocket.PROPELLANTS.map { it.principal }
        assertTrue(Species.Hydrogen in fuels, "hydrolox is not on the dial")
        assertTrue(Species.Methane in fuels, "methalox is not on the dial")
    }


    @Test
    fun `the two rear doors fill two different stores`() {
        // ⛔ **The claim [BufferRole.Oxidiser] exists for.** Both doors are `PortKind.Input` on the
        // same face of the same machine, so nothing about the port says which is which — the store
        // is chosen by the *tile the delivery arrived at*, which is what `inputBufferRoleAt` added.
        // Route it by the machine instead, as every other kind is routed, and both belts pour into
        // the fuel store and the engine never sees an oxidiser.
        val after = run(plumbed(), 120)

        val fuel = store(after, BufferRole.Input) ?: Mixture.EMPTY
        val oxidiser = store(after, BufferRole.Oxidiser) ?: Mixture.EMPTY
        val burned = chamber(after)

        // Everything that arrived, wherever it now is — the engine has been running the whole time,
        // so a door's store is a level rather than a total.
        assertTrue(
            fuel[Species.Hydrogen] + burned.total > 0L,
            "nothing came in the fuel door",
        )
        assertTrue(oxidiser[Species.Oxygen] > 0L, "nothing came in the oxidiser door")
        // ⚠️ The strict half: neither store holds a gram of what the *other* door is for.
        assertEquals(0L, fuel[Species.Oxygen], "oxygen reached the fuel store")
        assertEquals(0L, oxidiser[Species.Hydrogen], "hydrogen reached the oxidiser store")
    }

    @Test
    fun `the bell is a tile of the machine and the doors are behind it`() {
        val s = engine()
        val m = s.deck[engineAt] as Rocket
        assertEquals(grid.tile(9, 6), m.bell(grid), "the bell is not the middle of the front face")
        assertTrue(m.bell(grid) in m.tiles(grid).toList(), "the bell is not part of the footprint")
        assertEquals(grid.tile(7, 5), bufferTile(grid, m, engineAt, BufferRole.Input), "the fuel store moved")
        assertEquals(grid.tile(7, 7), bufferTile(grid, m, engineAt, BufferRole.Oxidiser), "the oxidiser store moved")
        assertEquals(engineAt, bufferTile(grid, m, engineAt, BufferRole.Inside), "the chamber is not the middle")
    }

    // ── The chamber ──────────────────────────────────────────────────────────

    @Test
    fun `it mixes at the dial`() {
        // One tick, so the chamber holds exactly one refill and nothing has burned yet. At 333‰ that
        // is a third fuel by mass — `1:2`, the peak of the table in `Rocket`.
        val after = run(engine(), 1)
        val held = chamber(after)

        assertTrue(held.total > 0L, "the chamber did not fill")
        val fuelShare = held[Species.Hydrogen] * 1000L / held.total
        assertTrue(
            fuelShare in 320L..346L,
            "the chamber is ${fuelShare}permille fuel, not the 333 the dial asked for",
        )
    }

    @Test
    fun `a starved door makes a lean engine and not a stopped one`() {
        // ⚠️ **A short feed is not a refusal.** All the oxidiser and none of the fuel gives a chamber
        // that is pure oxidiser, which is a bad engine — and stating it the other way, "draw nothing
        // unless both are there", would give a pilot mid-burn an engine that stops dead the moment a
        // belt hiccups, with nothing on the panel to say why.
        val after = run(engine(fuel = Mixture.EMPTY), 2)
        val held = chamber(after)
        assertTrue(held.total > 0L, "an engine with one door empty drew nothing at all")
        assertEquals(held.total, held[Species.Oxygen], "something other than the oxidiser got in")
    }

    @Test
    fun `the chamber lights, and what comes out is water`() {
        // ⛔ **No combustion code lives in this machine.** A store reacts with itself, so a chamber
        // over 773 K holding hydrogen and oxygen burns for free — which is the whole reason the
        // chamber is a store and the igniter is a thermostat rather than a mechanism of its own.
        val after = run(engine(), 60)
        assertTrue(
            chamberKelvin(after) >= Rocket.IGNITION_KELVIN,
            "the chamber never reached ignition: ${chamberKelvin(after)} K",
        )
        assertTrue(chamber(after)[Species.Water] > 0L, "nothing burned")
    }

    @Test
    fun `it never stops to reach its setpoint`() {
        // ⛔ **The duty-cycle test.** A rocket that held its charge until it was hot enough would
        // fire in bursts, and two of them either side of the centre of mass cycling out of phase
        // would have the flight balance see a different set of engines every tick — the ship would
        // wobble, and the wobble would be made by the gate. So: it vents on every single tick it is
        // fed, at whatever temperature it happens to be.
        var s = engine()
        val cfg = OutofspaceConfig(initialGrid = grid)
        var spentEveryTick = true
        // Skipping the first, which is the tick the chamber fills on and so has nothing to throw.
        s = OutofspaceReducer.reduce(cfg, s, emptyMap())
        repeat(30) {
            val before = s.ventedMass
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            if (s.ventedMass <= before) spentEveryTick = false
        }
        assertTrue(spentEveryTick, "the engine skipped a tick, which is the duty cycle this must not have")
    }

    // ── The mechanic ─────────────────────────────────────────────────────────

    @Test
    fun `richer than stoichiometric throws faster`() {
        // ⛔ **The whole plan in one assertion, and the one nobody believes.** 111‰ is `1:8`, the
        // textbook mixture that burns everything and reaches the hottest chamber. 333‰ is `1:2`,
        // which leaves a quarter of the hydrogen unburnt and runs *cooler* — and throws faster
        // anyway, because `v_e = √(K·R·T/M)` and the leftover hydrogen drags M̄ down further than
        // the missing enthalpy pulls T.
        val lean = run(engine(mix = 111), 60)
        val rich = run(engine(mix = 333), 60)

        val leanSpeed = Thruster.exhaustVelocity(chamber(lean))
        val richSpeed = Thruster.exhaustVelocity(chamber(rich))

        assertTrue(leanSpeed > 0L && richSpeed > 0L, "one of the two chambers was empty")
        assertTrue(
            richSpeed > leanSpeed,
            "the rich mixture is worth $richSpeed m/s against the lean one's $leanSpeed — " +
                "molar mass is supposed to beat temperature here",
        )
    }

    @Test
    fun `burning beats dumping the same mass of water`() {
        // The reason this machine exists. A cold gas thruster fed water is 1040 m/s at any
        // temperature it can reach, because water's M is stuck at 18; this reaches into the
        // thousands on the same physics and the same nozzle. ⚠️ Both sides go through
        // [Thruster.exhaustVelocity], which is shared: what differs is only what is in the store.
        val burning = Thruster.exhaustVelocity(chamber(run(engine(), 60)))
        val dumping = Thruster.exhaustVelocity(
            Mixture.of(Species.Water to Rocket.CHAMBER_CAP, energy = 0L).atAmbient(),
        )
        assertTrue(
            burning > dumping * 2L,
            "a burning chamber is worth $burning m/s against cold water's $dumping — that is not worth building",
        )
    }

    // ── The ledgers ──────────────────────────────────────────────────────────

    @Test
    fun `a firing rocket keeps the momentum identity`() {
        // ⛔ **A new engine must add no term to the momentum ledger.** Everything a rocket throws is
        // the same `+p` overboard / `−p` aboard pair a venting breach already is, so this identity
        // holding through a burn is the whole proof that firing is bookkeeping rather than minting.
        val after = run(engine(), 90)
        assertTrue(after.exhaustMomentumX != 0L, "nothing left the nozzle, so this proved nothing")
        assertEquals(
            0L,
            after.momentumBalanceX,
            "the momentum ledger is out: ship ${after.vesselImpulseX}, exhaust ${after.exhaustMomentumX}",
        )
    }

    @Test
    fun `it comes back off a save with its dials and its chamber`() {
        // ⛔ **Three stores and two dials**, and the chamber is the one a round-trip is most likely to
        // drop: it is the only store on this machine that no port serves, so nothing outside the
        // save loop would ever notice it missing until an engine reloaded stone cold.
        val before = run(engine(mix = 500, propellant = Species.Methane), 40)
        val text = Save.write(before)
        val after = Save.read(text)

        val m = after.deck[engineAt] as Rocket
        assertEquals(500, m.fuelPermille, "the mixture dial did not survive")
        // ⛔ **Written by name, so this is also the assertion that nothing saved an ordinal.** A
        // propellant that came back as a different species after a table edit is the failure the
        // save format is shaped to prevent, and the only way to see it is to round-trip one that is
        // not first on the list.
        assertEquals(Species.Methane, m.propellant, "the propellant lock did not survive")
        assertEquals(Rocket.DEFAULT_SETPOINT, m.setTemperature, "the ceiling did not survive")
        assertEquals(ThrusterControl.Wire, m.control, "the control mode did not survive")
        assertEquals(chamber(before).total, chamber(after).total, "the chamber came back a different size")
        assertEquals(
            chamber(before).energy, chamber(after).energy,
            "the chamber came back at a different temperature",
        )
        assertEquals(
            store(before, BufferRole.Oxidiser)?.total, store(after, BufferRole.Oxidiser)?.total,
            "the oxidiser store came back a different size",
        )
    }

    @Test
    fun `the pilot flies it`() {
        // ⛔ **No architectural change was needed for this and that was the finding**, so it is worth
        // a test: `Motor.push` is only a weight in the torque-balance ratio, and the balance reads
        // an engine's [Engine.propellantRole] — so a rocket enters the ship's flight solution by
        // what its *chamber* can throw, which is a different store from a thruster's and the same
        // question.
        val flying = engineOnFlightControl()
        val cfg = OutofspaceConfig(initialGrid = grid)
        val controller = OutofspaceController(cfg, flying)
        controller.mode = Mode.Flight
        // The engine exhausts to +x, so it pushes the ship to −x: the pilot asks to go left.
        controller.heldKeys = InputKey.Left.bit
        repeat(60) { controller.stepOnce() }

        val s = controller.state
        assertTrue(s.exhaustMomentumX > 0L, "the pilot's stick never reached the engine")
        assertTrue(s.velocityX < 0L, "exhaust went +x, so the ship must go −x, not ${s.velocityX}")
        assertTrue((s.deck[engineAt] as Rocket).firing > 0, "the engine does not report what it was told")
    }

    /** The same engine listening to the pilot rather than to its wire. */
    private fun engineOnFlightControl(): VesselState {
        val deck = DeckArray(grid)
        deck += Rocket(engineAt, Direction.Right)
        return VesselState(
            grid, deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
            .stocked(engineAt, hydrogen(), BufferRole.Input)
            .stocked(engineAt, oxygen(), BufferRole.Oxidiser)
    }

    @Test
    fun `an idle engine does not run its element`() {
        // ⛔ **A parked rocket is cold iron.** The element used to hold the chamber at its setpoint
        // for ever whatever the pilot was doing, minting `IGNITER_POWER` every tick — a hundred-odd
        // furnaces' worth — and pushing all of it out through nine tiles of casing into the ship. A
        // ship with four rockets aboard cooked itself while sitting still.
        //
        // ⚠️ **On flight control with the stick untouched**, which is the case that matters: a motor
        // on [ThrusterControl.Flight] never reads its wire, so gating on `on` would have left every
        // flight-controlled rocket in the game heating exactly as before.
        val parked = engineOnFlightControl()
        val before = parked.generatedEnergy
        val after = run(parked, 120)

        assertEquals(before, after.generatedEnergy, "an idle engine minted heat")
        assertTrue(
            chamberKelvin(after) < Rocket.IGNITION_KELVIN,
            "an idle chamber lit itself: ${chamberKelvin(after)} K",
        )
        // ⚠️ **This line means more than it did**, now that the throttle meters the injection: it is
        // no longer "it was not told to fire" but "it never took anything in, so it had nothing to
        // vent when it vented". The engine's doors are stocked throughout — a fuel valve stuck open
        // would show up right here as a parked ship quietly emptying its tanks out of the bell.
        assertEquals(0L, after.exhaustMomentumX, "an idle engine threw something")
        assertEquals(0L, chamber(after).total, "an idle engine filled its chamber anyway")
    }

    @Test
    fun `it lights within a few ticks of the stick moving`() {
        // ⚠️ **The price of the gate above, pinned so it stays a price and not a stall.** The chamber
        // is two ticks of mass flow and the element is sized to raise one tick of it from ambient to
        // ignition, so a cold engine reaches its onset a handful of ticks after the pilot asks — not
        // the ten seconds a furnace's element took, which is the measurement `IGNITER_POWER` exists
        // because of.
        val cfg = OutofspaceConfig(initialGrid = grid)
        val controller = OutofspaceController(cfg, engineOnFlightControl())
        controller.mode = Mode.Flight
        repeat(30) { controller.stepOnce() }   // parked, and so cold
        assertTrue(
            chamberKelvin(controller.state) < Rocket.IGNITION_KELVIN,
            "the chamber was already lit before the stick moved",
        )

        controller.heldKeys = InputKey.Left.bit
        var lit = -1
        repeat(LIGHT_UP_TICKS) {
            controller.stepOnce()
            if (lit < 0 && chamberKelvin(controller.state) >= Rocket.IGNITION_KELVIN) lit = it
        }
        assertTrue(lit in 0 until LIGHT_UP_TICKS, "a cold engine never lit in $LIGHT_UP_TICKS ticks")
    }


    // ── The throttle meters the injection ────────────────────────────────────

    /**
     * The two masses agree to within a gram.
     *
     * ⛔ **Exact equality is the wrong assertion about a mass flow and it is worth saying why.** A
     * chamber is drawn from with [Mixture.take], which is exact *per draw* but splits across species
     * at microgram resolution — so a rate measured over thirty ticks of a three-species chamber lands
     * a few thousand micrograms off a round number. See `reference_oos_microgram_deadlock`.
     *
     * A gram is four parts in ten thousand of a tick's flow: loose enough to absorb that, and far too
     * tight to hide anything these tests are actually looking for — a cap that clips the engine or a
     * floor that binds when it should not would both be off by kilograms.
     */
    private fun assertNear(expected: Long, actual: Long, message: String) {
        val slack = Budget.GRAM
        assertTrue(
            actual in (expected - slack)..(expected + slack),
            "$message (expected about $expected, was $actual)",
        )
    }


    @Test
    fun `a spooled-up engine throws its rated mass`() {
        // ⭐ **The two numbers lining up, which is the whole of the tuning.** The chamber vents a
        // `1/EXHAUST_DIVISOR` share every tick and is fed `MASS_PER_TICK`, so it settles where the
        // two balance — at `MASS_PER_TICK × EXHAUST_DIVISOR`, which is exactly `CHAMBER_CAP`. The
        // consequence is the claim the machine's rating rests on: once spooled up it throws
        // `MASS_PER_TICK` a tick, the same as it did when the throttle metered the exhaust.
        //
        // ⛔ **Set the cap and the divisor independently and this is the test that catches it**, in
        // the direction that matters: a cap below the equilibrium silently clips the engine's rated
        // thrust, and nothing else in the suite would notice.
        assertEquals(
            Rocket.MASS_PER_TICK * Rocket.EXHAUST_DIVISOR, Rocket.CHAMBER_CAP,
            "the chamber cannot hold what full throttle settles at",
        )

        val cfg = OutofspaceConfig(initialGrid = grid)
        val controller = OutofspaceController(cfg, engineOnFlightControl())
        controller.mode = Mode.Flight
        controller.heldKeys = InputKey.Left.bit
        repeat(40) { controller.stepOnce() }         // spool up

        val before = controller.state.ventedMass
        repeat(30) { controller.stepOnce() }
        val perTick = (controller.state.ventedMass - before) / 30L
        assertNear(Rocket.MASS_PER_TICK, perTick, "a spooled-up engine did not throw its rated mass")
    }

    @Test
    fun `the chamber empties itself when the stick is released`() {
        // ⛔ **The whole reason the exhaust stopped consulting the throttle.** An idle rocket used to
        // sit on its combustion products for ever — the chamber full, `room` zero, so no mixture
        // could get in — and the first burn after a long idle threw **water**, M̄ 18, the worst
        // exhaust in the game, before it threw anything worth throwing.
        //
        // ⚠️ **It keeps making thrust while it drains**, which is the price and is not a bug: a
        // residual charge leaving a nozzle is thrust whatever the stick says. What must not happen is
        // that it *stops* draining.
        val cfg = OutofspaceConfig(initialGrid = grid)
        val controller = OutofspaceController(cfg, engineOnFlightControl())
        controller.mode = Mode.Flight
        controller.heldKeys = InputKey.Left.bit
        repeat(40) { controller.stepOnce() }
        assertTrue(chamber(controller.state).total > 0L, "the engine never filled its chamber")

        controller.heldKeys = 0
        var previous = chamber(controller.state).total
        var drained = -1
        repeat(SPOOL_DOWN_TICKS) {
            controller.stepOnce()
            val held = chamber(controller.state).total
            assertTrue(held <= previous, "the chamber refilled itself with the stick released")
            previous = held
            if (drained < 0 && held == 0L) drained = it
        }
        assertTrue(drained >= 0, "the chamber still held $previous after $SPOOL_DOWN_TICKS idle ticks")

        // And having drained it stays drained, rather than trickling on a carry nobody spent.
        val settled = controller.state.ventedMass
        repeat(20) { controller.stepOnce() }
        assertEquals(settled, controller.state.ventedMass, "an empty engine went on venting")
    }

    @Test
    fun `it spools up instead of stepping`() {
        // ⚠️ **The flight-control cost, stated as the thing it actually is.** Thrust is a fact about
        // the chamber now, so it ramps over about [Rocket.EXHAUST_DIVISOR] ticks instead of arriving
        // whole on the tick the stick moves. ⛔ Latency and not a duty cycle — it vents on *every*
        // tick throughout, which is what `it never stops to reach its setpoint` pins separately.
        val cfg = OutofspaceConfig(initialGrid = grid)
        val controller = OutofspaceController(cfg, engineOnFlightControl())
        controller.mode = Mode.Flight
        controller.heldKeys = InputKey.Left.bit

        controller.stepOnce()
        val first = controller.state.ventedMass
        assertTrue(first > 0L, "the first tick of a burn threw nothing at all")
        assertTrue(
            first < Rocket.MASS_PER_TICK,
            "the first tick threw its full rate, so there is no spool-up: $first",
        )

        repeat(40) { controller.stepOnce() }
        val before = controller.state.ventedMass
        controller.stepOnce()
        assertNear(
            Rocket.MASS_PER_TICK, controller.state.ventedMass - before,
            "it never reached its rated rate",
        )
    }

    @Test
    fun `it pushes the ship the other way`() {
        // Direction is the assertion, for `ThrusterTest`'s reason: a sign error still produces a
        // moving ship, so "which way" is the only version of the question worth asking.
        val after = run(engine(), 90)
        assertTrue(after.exhaustMomentumX > 0L, "the exhaust did not go +x")
        assertTrue(after.velocityX < 0L, "exhaust went +x, so the ship must go −x, not ${after.velocityX}")
    }
}
