package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.REACTIONS
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * **A rocket that makes its own heat**: fuel in at one rear door, oxidiser in at the other, and a
 * chamber between them that lights the mixture and vents it out of the bell.
 *
 * The second of the four engines (`PLAN_chemical_rockets.md`). A [Thruster] is the first — a cold
 * gas thruster, a hole that dumps whatever you deliver to it. This one burns, and the difference is
 * worth a factor of four.
 *
 * ### ⛔ The win is molar mass, not energy
 *
 * The obvious reading of "a rocket that burns its propellant" is *more energy, therefore more
 * thrust*, and that reading is wrong here in a way worth stating where somebody will find it.
 * `v_e = √(K·R·T/M)`, and heating water cannot pass about **3600 m/s at any temperature** because
 * water's M is stuck at 18. What burning hydrogen buys is a chamber full of things lighter than
 * water:
 *
 * | H₂:O₂ by mass | unburnt H₂ | chamber | M̄ | v_e |
 * |---|---|---|---|---|
 * | 1:8 (stoichiometric) | 0% | 3508 K | 18.0 | 3600 |
 * | 1:4 | 10% | 2623 K | 10.0 | 4044 |
 * | **1:2** | **25%** | **1795 K** | **6.0** | **4247** |
 *
 * ⚠️ **The best mixture is a *cooler* chamber than the hottest one**, because unburnt hydrogen drags
 * the mean molar mass down faster than the enthalpy it did not release costs. That is why
 * [fuelPermille] is the machine's real control surface and not a nicety: running stoichiometric
 * gives up 15% of the exhaust velocity, and the dial is the only way to reach the light-molecule
 * regime at all.
 *
 * ### The chamber, and ⛔ why it must not gate on its setpoint
 *
 * [CHAMBER_CAP] is small on purpose. An igniter in the *feed* store would hold two hundred kilograms
 * at three thousand kelvin all day, bleeding it into the vessel through the buffer's contact
 * conductance — cooking the ship and wasting the heat. Only what is about to leave is lit.
 *
 * ⛔ **What must not happen is holding the charge until it reaches [setTemperature] and then
 * releasing it.** That makes firing a duty cycle, and two motors either side of the centre of mass
 * cycling out of phase would have the flight balance see a different set of available engines every
 * tick: the ship would wobble, and the wobble would be made by the gate rather than by the physics.
 *
 * ✅ So it **vents continuously and heats continuously, and the setpoint is a ceiling.** The chamber
 * temperature settles at an equilibrium — roughly `T_in + P/(ṁ·cp)` — and the mechanic that falls
 * out is free and legible: *you reach your setpoint only if you throttle down far enough.* The
 * ceiling is also what stops the low-throttle case running away, since ungated the equilibrium at a
 * tenth of a mass flow is a temperature nothing is made of.
 *
 * ⚠️ **For a bipropellant the thermostat's job is ignition, not bulk heating.** Getting a small
 * chamber to 773 K is affordable; heating a full mass flow to a useful temperature is not, and the
 * reaction is what pays for the rest. The element matters far more to a monopropellant, which is the
 * same chamber with one door.
 *
 * ### What it does not do
 *
 * ⚠️ **Nothing here refuses the wrong fluid at either door.** An unlocked motor takes any fluid and
 * no solid — the network never routes a rock to an engine — and which of the two lighter-than-rock
 * things arrives at which door is the player's belt, not this machine's opinion. Feed both doors
 * hydrogen and you have built an expensive cold gas thruster, which is a legible thing to have built
 * by mistake.
 */
data class Rocket(
    override val center: TileIndex,
    override val facing: Direction,
    /**
     * How much of each chamber refill is drawn from the **fuel** door, in permille.
     *
     * ⚠️ **A control surface, not a logistics setting.** The point is to trade thrust against exhaust
     * velocity *mid-burn* — which you cannot do by re-plumbing a belt — so it is a dial on the
     * machine and it is offered as [RATIOS], a ladder of round numbers in the way `Furnace.SETPOINTS`
     * is.
     *
     * The default is a third: `1:2` by mass, the peak of the table above.
     */
    val fuelPermille: Int = DEFAULT_FUEL_PERMILLE,
    /**
     * The ceiling the element heats the chamber to. **Not** a gate — see the class note.
     *
     * Defaults above hydrogen's 773 K onset, because an engine that has never been tuned should
     * light rather than sit there full of cold gas wondering why it makes no thrust.
     */
    val setTemperature: Int = DEFAULT_SETPOINT,
    override val carry: Long = 0L,
    override val firing: Int = 0,
    override val control: ThrusterControl = ThrusterControl.Flight,
    override val wiring: Wiring = Wiring.RUNNING,
) : Engine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Rocket
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    override val massPerTick: Long get() = MASS_PER_TICK

    /**
     * ⛔ **The chamber, not either feed.** What this engine throws is what the combustion left, and
     * the two input stores are cold gas that has not been anywhere near an igniter.
     */
    override val propellantRole: BufferRole get() = BufferRole.Inside

    override fun told(activation: Int, carry: Long): Engine = copy(firing = activation, carry = carry)
    override fun withControl(control: ThrusterControl): Engine = copy(control = control)

    /** The dial, clamped to the ladder's ends — a mixture is a fuel fraction and cannot be neither. */
    fun withFuelPermille(permille: Int): Rocket =
        copy(fuelPermille = permille.coerceIn(RATIOS.first(), RATIOS.last()))

    fun withSetTemperature(kelvin: Int): Rocket = copy(setTemperature = kelvin)

    companion object {
        /**
         * How much it throws per tick at full activation.
         *
         * A fifth of a belt-load, which is **forty times a [Thruster]'s**. Deliberately: this is a
         * nine-tile installation with two supply lines behind it and a plant making both, against an
         * engine that is two tiles and a tank of whatever was lying around. If the big one were not
         * obviously the big one there would be no reason to build it.
         */
        val MASS_PER_TICK: Long = Capacity.PACKET_MASS / 5L

        /**
         * How much the chamber holds.
         *
         * ⛔ **Small, and that is the machine.** Four ticks of full flow — enough that a throttled
         * engine is not immediately empty, and little enough that what is held at three thousand
         * kelvin is a few kilograms rather than the two hundred a feed store would be. The heat a
         * chamber leaks into its own casing (and from there into the room) is proportional to what
         * is in it, so this number is also the answer to "how badly does a running engine cook the
         * ship".
         */
        val CHAMBER_CAP: Long = MASS_PER_TICK * 4L

        /**
         * Where `2 H₂ + O₂ → 2 H₂O` lights — **read off the reaction table, not restated**.
         *
         * The panel colours a chamber by whether it is lit, and a number typed in beside that check
         * would be a second opinion about a fact the chemistry already owns: repricing the reaction
         * would leave the readout confidently wrong. This machine performs no chemistry of its own
         * (see the class note) so the table is the only place the answer exists.
         */
        val IGNITION_KELVIN: Int = REACTIONS
            .first { it.principal == Species.Hydrogen && it.products.any { p -> p.first == Species.Water } }
            .onsetKelvin

        /**
         * What the igniter puts into the chamber in one tick, while it is below [setTemperature].
         *
         * ⛔ **Derived from the plan's own equation, `T = T_in + P/(ṁ·cp)`** — the equilibrium a
         * chamber that vents continuously and heats continuously settles at. Rearranged, the power
         * needed to hold a **full mass flow** of ambient propellant at the onset of combustion is
         * `ṁ·cp·ΔT`, and that is exactly this. What falls out is the promise the design makes: at
         * full throttle the engine sits *at* ignition, and every notch below that it runs hotter,
         * up to whatever ceiling the player dialled.
         *
         * ⛔ **It cannot be [HEATER_POWER], and the measurement is worth keeping.** A furnace's
         * element is sized to raise a *stationary* charge of rock, and given this job it failed
         * twice over: it took **620 ticks — ten seconds — to light a chamber that was not venting**,
         * most of it lost into nine tiles of metal through the casing; and with the engine actually
         * firing it never lit **at all**, because a quarter of the chamber is replaced by cold gas
         * every tick and the equilibrium sat around 410 K. An engine that cannot start is not an
         * engine, and no amount of patience fixes an equilibrium.
         *
         * ⚠️ **[MARGIN] is for the casing.** The equation above accounts for the propellant leaving
         * and for nothing else; the chamber also bleeds into nine tiles of metal at the buffer's
         * contact conductance, which is what the ten-second measurement was mostly measuring.
         *
         * ⚠️ **Minted, like every element in the game** — see `Work.heatBuffer` — and this one is a
         * hundred-odd furnaces' worth, which is worth saying out loud. It buys **ignition and
         * nothing else**: past the onset the reaction pays, and the element stops contributing the
         * moment the chamber is over its ceiling. When there is a power grid this is the number that
         * will hurt, and it should.
         */
        val IGNITER_POWER: Long = MARGIN *
            (MASS_PER_TICK * IGNITER_REFERENCE_SPECIFIC_HEAT / Budget.CAPACITY_DIVISOR) *
            (IGNITION_KELVIN - Temperature.AMBIENT_KELVIN)

        /**
         * J/kg/K of a `1:2` hydrogen/oxygen mixture — hydrogen is 14300 and oxygen 918, and a third
         * of the mass being the light one is what makes the mean this high.
         *
         * ⚠️ **Not rock's 900**, which is what a furnace's element is sized against. Using that here
         * would under-size this by six.
         */
        private const val IGNITER_REFERENCE_SPECIFIC_HEAT = 5400L

        /** Headroom for what the casing takes. See [IGNITER_POWER] — this is the number that is chosen. */
        private const val MARGIN = 2L

        /**
         * The mixtures the panel offers, in permille of fuel — richest last.
         *
         * ⚠️ **Round numbers spanning the interesting range, not the stoichiometric point and its
         * neighbours.** 111‰ is `1:8`, the textbook mixture and the *worst* one here; 333‰ is `1:2`
         * and the peak; the two above it are past the peak and getting worse again, which is what
         * makes the dial a decision rather than a slider with a right answer at one end.
         *
         * The bottom rung is not off. There is no "off" on a mixture — an engine that is told to run
         * runs — and stopping one is what its wiring and its throttle are for.
         */
        val RATIOS: List<Int> = listOf(111, 167, 250, 333, 400, 500)

        /** `1:2` by mass: the peak of the table in the class note. */
        const val DEFAULT_FUEL_PERMILLE: Int = 333


        /**
         * Comfortably above the 773 K at which `2 H₂ + O₂ → 2 H₂O` lights.
         *
         * ⚠️ **Onset is where a reaction runs at `BASE_RATE`, which is essentially not at all** —
         * the same trap `Furnace.SETPOINTS` documents. A setpoint *at* 773 K would be an engine that
         * ignites in principle.
         */
        const val DEFAULT_SETPOINT: Int = 1200
    }
}
