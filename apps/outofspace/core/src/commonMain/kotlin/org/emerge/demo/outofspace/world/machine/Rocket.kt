package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.REACTIONS
import org.emerge.demo.outofspace.chem.Reaction
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.isFluid
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
 * ⛔ **And it heats only while it is firing.** The ceiling is a ceiling on a *running* engine; an
 * idle one is cold iron. Left ungated, a rocket nobody was flying held its chamber at
 * [setTemperature] for ever on minted [IGNITER_POWER] and pushed all of it out through nine tiles of
 * casing into the ship. ⚠️ The price is a light-up delay of a couple of ticks from cold — see
 * `OutofspaceSim.burn`, where the gate is and where the arithmetic is.
 *
 * ⚠️ **For a bipropellant the thermostat's job is ignition, not bulk heating.** Getting a small
 * chamber to 773 K is affordable; heating a full mass flow to a useful temperature is not, and the
 * reaction is what pays for the rest. The element matters far more to a monopropellant, which is the
 * same chamber with one door.
 *
 * ### ⛔ The doors are agnostic, and the propellant is what sorts them
 *
 * ⛔ **Neither door is "the fuel door".** Both mouths ask the network for the same two species and a
 * delivery lands in the store its *contents* belong in — see [org.emerge.demo.outofspace.world
 * .inputBufferRoleAt]. That is the machine's whole answer to a question it used to make the player
 * answer: the drawing cannot say which rear port is which, so a belt laid to the wrong one built an
 * expensive cold gas thruster and the only warning was a word in the inspector.
 *
 * ⚠️ **Which needs the machine to know what it burns**, and that is [propellant]. "Put the fuel in
 * the fuel store" is not a rule a machine can follow while *fuel* is a role a belt confers rather
 * than a species the engine named. Locking to a row of [REACTIONS] is what makes it answerable, and
 * the same lock then prices the mixture: [stoichiometricFuelPermille] is where the dial's ladder
 * sits relative to burning clean, read off the table rather than restated.
 *
 * ⚠️ **Unlocked is still a legal engine and still the default**, because a save full of rockets
 * predates this and every one of them has to keep flying. An unlocked motor asks both doors for any
 * fluid and routes by the tile the delivery arrived at, which is exactly what it did before — see
 * `inputBufferRoleAt` again, where the two answers sit side by side.
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
    /**
     * The **fuel** this engine is plumbed for, or null for an unlocked motor that takes any fluid.
     *
     * ⛔ **One species and not a pair, because the oxidiser is not a second choice.** A propellant
     * names exactly one row of [REACTIONS] — the [PROPELLANTS] list is those rows — and that row
     * already says what it burns in. Offering both halves would let a player state a combination the
     * chemistry has no row for, and the engine would then be locked onto a reaction that cannot
     * happen while reporting two perfectly sensible species. See [oxidiser], derived.
     *
     * ⛔ **Saved by NAME, never by an index into [PROPELLANTS].** That list is derived from the
     * reaction table, so a row added or reordered would silently re-plumb every rocket in every save
     * — the same trap `Save.canonicalKindName` exists to avoid one level up.
     */
    val propellant: Species? = null,
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

    /** Locked onto a fuel, or unlocked when it is null — [Thruster.withFilter]'s twin. */
    fun withPropellant(propellant: Species?): Rocket = copy(propellant = propellant)

    /**
     * The row this engine burns, or null while it is unlocked.
     *
     * ⚠️ **Looked up rather than stored**, so a rocket cannot carry a reaction that the table no
     * longer has. If [propellant] names a species that has stopped being a propellant, this answers
     * null and the machine reads as unlocked — which is the safe direction to fail in, because an
     * unlocked motor still flies.
     */
    val combustion: Reaction? get() = propellant?.let { fuel -> PROPELLANTS.firstOrNull { it.principal == fuel } }

    /**
     * What this engine's fuel burns in — **derived from the row, not chosen**.
     *
     * Oxygen for every row in the table today, and deliberately not written as `Species.Oxygen`: the
     * moment a fluorine or a peroxide row lands, an engine locked onto it needs the other door to
     * ask for the right thing without anybody remembering to come back here.
     */
    val oxidiser: Species?
        get() = combustion?.reagents?.firstOrNull { it.first != propellant }?.first

    /**
     * Where **burning clean** sits on [RATIOS]' scale, or null while unlocked.
     *
     * ⚠️ **A readout, not a limit.** The dial is deliberately allowed past it in both directions —
     * the whole mechanic is that the best mixture is richer than this — so what this is for is
     * telling the player *how much* richer they are running. For hydrolox it is 111‰, which is the
     * bottom rung of the ladder and the textbook `1:8`.
     */
    val stoichiometricFuelPermille: Int?
        get() = propellant?.let { fuel -> combustion?.massPermilleOf(fuel) }

    /**
     * The temperature this engine's mixture starts burning at.
     *
     * ⚠️ **Per-engine now, because the propellants do not agree** — hydrogen lights at 773 K and
     * methane at 810 K, and a panel that coloured a methalox chamber "lit" at 780 K would be
     * confidently wrong. An unlocked motor has no row to read, so it falls back to [IGNITION_KELVIN]
     * — hydrogen's, which is the mixture an unlocked rocket is overwhelmingly likely to be fed.
     */
    val ignitionKelvin: Int get() = combustion?.onsetKelvin ?: IGNITION_KELVIN

    companion object {
        /**
         * How much it throws per tick at full activation.
         *
         * A fifth of a belt-load, which is **forty times a [Thruster]'s**. Deliberately: this is a
         * nine-tile installation with two supply lines behind it and a plant making both, against an
         * engine that is two tiles and a tank of whatever was lying around. If the big one were not
         * obviously the big one there would be no reason to build it.
         */
        val MASS_PER_TICK: Long = Capacity.PACKET_MASS / 40L

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
        val CHAMBER_CAP: Long = MASS_PER_TICK * 2L

        /**
         * Every row a rocket can be plumbed for: **a fluid that burns in an oxidiser**.
         *
         * ⛔ **Derived, not curated**, which is this machine's own habit — see [IGNITION_KELVIN]
         * below, where the argument is made for a single number. The test is the one `kindOf` uses
         * to call a reaction a `Fire`: a fluid principal with an oxidiser among its reagents. A row
         * added to the table is a propellant on the dial the same day, and nobody has to remember
         * a second list.
         *
         * ⚠️ **It admits some poor engines and that is correct.** Sulfur is a fluid by the game's
         * reckoning — volatile enough to leave a roasting bed — so sulfur/oxygen is on the list, and
         * it throws sulfur dioxide at M̄ 64, which is dreadful. Refusing it would be this machine
         * having an opinion about a mixture the chemistry is perfectly happy with; the ladder
         * already lets a player run a hydrolox engine stoichiometric and lose 15% for it. A bad
         * choice you can see the number for is the game working.
         */
        val PROPELLANTS: List<Reaction> = REACTIONS.filter { r ->
            r.principal.isFluid && r.reagents.any { it.first != r.principal && it.first.isFluid }
        }

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
