package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.TileIndex
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
    ): VesselState {
        val deck = DeckArray(grid)
        deck += Rocket(engineAt, Direction.Right, fuelPermille = mix, control = ThrusterControl.Wire)
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
     */
    private fun plumbed(): VesselState {
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        deck += Rocket(engineAt, Direction.Right, control = ThrusterControl.Wire)  // covers x 7..9, y 5..7
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
            .stocked(fuelTank, hydrogen(8))
            .stocked(oxidiserTank, oxygen(8))
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
        val before = run(engine(mix = 500), 40)
        val text = Save.write(before)
        val after = Save.read(text)

        val m = after.deck[engineAt] as Rocket
        assertEquals(500, m.fuelPermille, "the mixture dial did not survive")
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
    fun `it pushes the ship the other way`() {
        // Direction is the assertion, for `ThrusterTest`'s reason: a sign error still produces a
        // moving ship, so "which way" is the only version of the question worth asking.
        val after = run(engine(), 90)
        assertTrue(after.exhaustMomentumX > 0L, "the exhaust did not go +x")
        assertTrue(after.velocityX < 0L, "exhaust went +x, so the ship must go −x, not ${after.velocityX}")
    }
}
