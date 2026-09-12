package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Reaction
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.chem.BASE_RATE
import org.emerge.demo.outofspace.world.Wiring

/**
 * A well-insulated box in which the player chooses the conditions.
 *
 * Thermal decomposition — carbonates and hydrates giving up CO₂/H₂O on heating alone, calcite →
 * lime + CO₂, serpentine → olivine + water — is not something this machine *does*. It is something
 * that happens to matter at a temperature, anywhere in the vessel, and this is simply the one place
 * where a temperature can be asked for rather than merely suffered. See
 * `PLAN_ambient_chemistry.md`, decision 3.
 *
 * ⚠️ **It has no recipe and no rate.** Those went with the chemistry when the chemistry left. What is
 * left is a thermostat with a timer: it pulls a charge in, runs an element until the charge reaches
 * [setTemperature], holds it there for [dwellTicks], and hands on whatever the charge has *become* by
 * then. If nothing decomposes at the temperature the player set, nothing decomposes — which is the
 * machine finally telling the truth about what it is for.
 *
 * ### Two dials, because conversion is asymptotic
 *
 * ⚠️ **[dwellTicks] is not the `ticksPerAction` increment 3 deleted**, and the difference is the whole
 * argument. That was a hidden constant standing in for a reaction rate nobody had modelled: a charge
 * was "done" after 128 ticks because the number said so. This is a **control**, and it exists because
 * of something the chemistry turned out to be: a reaction approaches completion asymptotically, so
 * there is no moment at which a charge is finished, and any rule claiming to find one is either an
 * invented threshold or a wait that never ends.
 *
 * A residence time is what a real furnace operator sets, and it makes the pair of dials a genuine
 * decision — hotter converts faster but costs more element and leaks more heat into the room; longer
 * converts more of each charge but throttles throughput. Neither dominates.
 *
 * ⚠️ **A setpoint barely above onset is slow, and that is physics rather than a tax.** 30 K over
 * calcite's onset is about 0.3% of the charge per chemistry pass; 400 K over is nearer 4%. What the
 * dwell buys the player is the choice to sit there anyway.
 *
 * ⚠️ **Zero is the default and it is the old behaviour exactly** — hand on the moment the charge is at
 * temperature. So the dial is opt-in, and a decomposer nobody has tuned behaves as it always did.
 *
 * ### What goes in is the player's list, not the network's leftovers
 *
 * ⛔ **A decomposer states what may be sent to it, and an empty statement means nothing may.** See
 * [whitelist] and [FeedBook]. This machine is the reason that interface is not just the ejector's
 * field: what a kiln and a hole in the hull have in common is that both are places the player must
 * *name* what goes, and the network then routes accordingly.
 *
 * ⚠️ **It has no opinion about whether the list makes sense.** Ticking iron on a furnace set to
 * 300 K fills the chamber with iron and holds it at 300 K, which is a legible thing to have built by
 * mistake and not something to refuse on the player's behalf.
 *
 * Its firebrick casing -- if the player builds it in firebrick -- stops being decoration at the
 * same moment. The element is modelled as being *in* the chamber, so the charge is what gets hot and
 * the casing is what the heat then bleeds into — slowly, at the buffer's own contact conductance —
 * and from there into the room. A decomposer working steadily is a heat source you have to plan
 * around.
 *
 * ⛔ **Its gaseous products do NOT leave by the room, and this note used to say they did.** Off-gassing
 * is opt-in and a machine's buffer never vents — `AmbientChemistry`: *"a machine's buffer never vents,
 * so a tonne of liquid oxygen keeps"* — so a charge keeps whatever it turns into and hands the lot on
 * down the belt. ⚠️ Which is also why a chamber's **total mass is invariant while it reacts**, and why
 * [chargedReagents] exists: mass cannot measure conversion when mass never changes.
 */
data class Furnace(
    override val center: TileIndex,
    override val facing: Direction,
    val setTemperature: Int = 900,
    /** How long a charge is held **at** [setTemperature] before it is handed on. */
    val dwellTicks: Int = 0,
    /**
     * How much of [dwellTicks] this charge has served.
     *
     * ⚠️ **Counts only while the charge is at temperature and the machine is running**, which is what
     * makes it a residence time rather than a delay: a decomposer starved of signal, or still ramping,
     * is not holding anything at anything. Reset when a charge is handed on, so the next one serves
     * its own full dwell.
     */
    val heldTicks: Int = 0,
    /**
     * Every species the belts may send here **pure** — see [FeedBook], which is the whole of the
     * rule. Empty means none, not any.
     *
     * ⛔ **A charge nobody chose was the tedium this exists to end.** A decomposer takes anything, so
     * a network with one on it routes *everything* through the kiln; the only way to feed it one
     * species was a tank in front of its mouth locked to that species, re-locked by hand every time
     * something new turned up. Ten things worth decomposing meant ten tanks, on a ship whose whole
     * subject is that there is no room for ten of anything.
     *
     * ⚠️ **The book is not derived from [setTemperature], though it very nearly could be.** A
     * setpoint already implies a set — every species with a single-reagent reaction whose onset it
     * clears — and deriving it would need no panel at all. It is a *list* instead because the two
     * dials answer different questions: the setpoint says how hot, and a player who wants serpentine
     * cooked and calcite left alone at 1100 K is asking something the temperature cannot express.
     * Stu, 2026-09-10.
     */
    val book: Set<Species> = emptySet(),
    /** Whether mixed ore may be sent here — see [FeedBook.ore]. */
    val oreByHand: Boolean = false,
    /**
     * The single reaction this furnace is plumbed for, or null for **broad mode**.
     *
     * ⛔ **This is a MODE, not a dial beside the others.** Setting it swaps the whole control
     * surface: [whitelist] and [setTemperature] stop being things the player states and become
     * things the recipe says, and [dwellTicks] gives way to [completionPermille]. Broad mode is
     * untouched and is what every save before this is.
     *
     * ⛔ **The book is DERIVED here rather than stated alongside**, and that is what keeps
     * `sinkAdmits`' rule intact: *"two statements of one fact are one edit away from disagreeing."*
     * A recipe is a precise way of *writing* a feed list, not a second opinion about one, so
     * [whitelist] reads the reagents and the demand pass never learns that recipes exist.
     *
     * ⚠️ **Saved by the principal's NAME, never an index**, for [Rocket.propellant]'s reason.
     */
    val recipe: Reaction? = null,
    /**
     * How far a recipe charge is taken before it is handed on, in permille of the principal.
     *
     * ⚠️ **Only meaningful in recipe mode, and that is the deep reason the two modes exist.** A
     * completion percentage needs exactly one reaction to be a percentage *of*. A broad furnace
     * holding serpentine and ammonia and hematite at 1100 K is running three conversions at once and
     * "95% done" names nothing — so broad mode states a duration instead and this dial is not shown.
     *
     * ⛔ **It is a threshold the PLAYER states, which is what makes it legal.** The warning this
     * machine was built around — *"any rule claiming to find [the moment a charge is finished] is
     * either an invented threshold or a wait that never ends"* — is a warning against the *designer*
     * inventing one. Asked for, it is a control exactly as [dwellTicks] is.
     */
    val completionPermille: Int = DEFAULT_COMPLETION,
    /**
     * How much of **each** reagent the current recipe charge started with, in [Reaction.reagents]
     * order, so completion can be measured. Empty between charges.
     *
     * ⛔ **Recorded at load, because the charge's MASS cannot answer.** Nothing vents out of a
     * machine buffer — `AmbientChemistry` says so in as many words, *"a machine's buffer never vents,
     * so a tonne of liquid oxygen keeps"* — so a chamber's total is invariant while it reacts and
     * tells you nothing at all about how far it has got. What moves is the reagents, and only
     * against what was loaded.
     *
     * ⛔ **Every reagent and not just the principal, because a principal can be its own product.**
     * Photosynthesis is `1 Algae + 6 Water + 6 CO₂ → 2 Algae + 6 O₂`: the principal *doubles* as the
     * row runs, so a conversion measured on it alone counts down to −100% and a finished charge is
     * held until [RECIPE_TIMEOUT_TICKS] gives up on it. Found in a save, Stu, 2026-09-12. See
     * [convertedPermille], which is the rule that replaced it.
     */
    val chargedReagents: List<Long> = emptyList(),
    override val wiring: Wiring = Wiring.RUNNING,
) : DirectedDeckMachine, FeedBook {
    override val kind: DeckMachineKind get() = DeckMachineKind.Furnace
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    /**
     * ⛔ **The recipe's reagents when there is one, and the player's list otherwise.** One statement
     * of what may be sent, which is the whole of [FeedBook]'s contract — the demand pass, the panel,
     * the save and the stamp all read this and none of them has to know which mode produced it.
     */
    override val whitelist: Set<Species>
        get() = recipe?.reagents?.mapTo(mutableSetOf()) { it.first } ?: book

    /**
     * ⚠️ **A locked furnace takes no ore, whatever the switch said.** A recipe is a statement about
     * pure species in an exact ratio; a blend has no single species to meter and would arrive as an
     * unmeasurable fraction of two of them. The player's own setting is kept in [oreByHand] and comes
     * back when the recipe is cleared.
     */
    override val ore: Boolean get() = if (recipe != null) false else oreByHand

    override fun withFeed(whitelist: Set<Species>, ore: Boolean): FeedBook =
        copy(book = whitelist, oreByHand = ore)

    /** Locked onto [recipe], or broad when it is null — [Rocket.withPropellant]'s twin. */
    fun withRecipe(recipe: Reaction?): Furnace = copy(recipe = recipe)

    fun withCompletion(permille: Int): Furnace =
        copy(completionPermille = permille.coerceIn(COMPLETIONS.first(), COMPLETIONS.last()))

    /**
     * The temperature this furnace actually holds: the recipe's, or the player's in broad mode.
     *
     * ⛔ **The lowest rung ABOVE the onset, never the onset itself.** [SETPOINTS] carries the
     * argument — a reaction *at* its onset runs at [BASE_RATE] and essentially nothing happens, so
     * "the lowest valid temperature for this recipe" is the slowest one that technically qualifies.
     * One rung up is the cheapest setting that actually converts.
     *
     * ⚠️ **Falls back to the player's number if the ladder has no rung for the row**, which is a
     * reaction hotter than 2400 K or one whose window no rung lands inside. Nothing in the table is
     * either today, and `FurnaceRecipeTest` pins that, since a row added above the ladder would
     * otherwise get a setpoint that silently cannot run it.
     */
    val heldKelvin: Int
        get() = recipe?.let { setpointFor(it) } ?: setTemperature

    /**
     * How far the charge in the chamber has got, in permille — **the reagent least of which is
     * left**, given what the chamber still holds.
     *
     * ⛔ **The INPUTS, never the principal alone, and never a product.** A row may make more of its
     * own principal — photosynthesis makes two algae out of one — so the principal's mass is not a
     * measure of anything on such a row and goes the wrong way entirely. See [chargedReagents],
     * where the save that found it is written down.
     *
     * ⛔ **The SMALLEST remaining sets it, which is to say the LARGEST conversion.** A charge is
     * built exactly stoichiometric, so on an ordinary row every reagent runs down together and the
     * choice between them is arithmetic noise. What it decides is the row that regenerates one of
     * its reagents: the algae stays put — grows, even — while the water and the CO₂ go to nothing,
     * and it is the water and the CO₂ that say the charge is spent. Stu's rule, 2026-09-12.
     *
     * ⚠️ **No baseline means done**, which is what hands on a charge the reducer cleared the
     * measurement for — a row swapped underneath a running kiln — rather than holding it to the
     * timeout with nothing left that could ever release it.
     *
     * ⚠️ Clamped to 0..1000. Every term is a fraction of what was loaded, so only a chamber that has
     * *gained* every reagent at once could fall outside, and a negative percentage on the panel was
     * the bug this rule fixes rather than a reading worth preserving.
     */
    fun convertedPermille(left: (Species) -> Long): Int {
        val row = recipe ?: return 0
        // ⛔ **A baseline of the wrong length belongs to a different row**, and reading it
        // positionally against this one measures one species against another's mass. Answered the
        // same way an absent baseline is: unmeasurable, so hand the charge on rather than hold it to
        // the timeout — which is what the reducer's own clear on a recipe change already does.
        if (chargedReagents.size != row.reagents.size) return 1000
        var most: Int? = null
        for ((i, reagent) in row.reagents.withIndex()) {
            val loaded = chargedReagents[i]
            if (loaded <= 0L) continue
            val converted = ((loaded - left(reagent.first)) * 1000L / loaded).toInt()
            if (most == null || converted > most) most = converted
        }
        return (most ?: 1000).coerceIn(0, 1000)
    }

    /** Which reagent of the locked recipe a store holds, or null for a store that is not a feed. */
    fun speciesFor(role: BufferRole): Species? {
        val reagents = recipe?.reagents ?: return null
        val at = when (role) {
            BufferRole.Input -> 0
            BufferRole.SecondReagent -> 1
            BufferRole.ThirdReagent -> 2
            else -> return null
        }
        return reagents.getOrNull(at)?.first
    }

    /** The store [species] belongs in, or null if the locked recipe does not use it. */

    fun roleFor(species: Species): BufferRole? = when (recipe?.reagents?.indexOfFirst { it.first == species }) {
        0 -> BufferRole.Input
        1 -> BufferRole.SecondReagent
        2 -> BufferRole.ThirdReagent
        else -> null
    }

    companion object {
        /**
         * The setpoints the panel offers, coldest first.
         *
         * ⚠️ **A ladder of round numbers, not the reaction onsets themselves.** Offering the onsets
         * would look tidier and would be a trap: a reaction *at* its onset runs at [BASE_RATE] and
         * essentially nothing happens, so every setpoint on the dial would be the slowest possible
         * one for the thing it names. What the player needs is headroom above an onset, which is what
         * the gaps here are.
         *
         * ⚠️ **`ThermalDecomposerUiTest` insists every reaction in every table has a rung strictly
         * above its onset**, so a row added hotter than 2400 K is a test failure rather than a
         * reaction the dial silently cannot reach.
         *
         * The bottom rung is off, near enough: nothing in either table happens at 200 K, so it is how
         * a decomposer is told to stop without unwiring it.
         */
        val SETPOINTS: List<Int> = listOf(200, 300, 900, 1100, 1250, 1400, 1600, 1900, 2200, 2400)

        /**
         * The rung this furnace would hold [reaction] at, or null if the ladder has none.
         *
         * ⛔ **The one statement of the rule**, because the recipe panel prints this number beside
         * every row it offers and a second copy of the arithmetic is a second thing to forget. It
         * was two copies until [Reaction.ceilingKelvin] arrived and needed adding to both.
         *
         * ⚠️ **A ceiling can make this null where an onset never could.** A row with a window has a
         * rung only if one lands inside it; photosynthesis's 273–318 K is cleared by the 300 K rung
         * and nothing else, which is a real furnace setting and not a coincidence worth relying on.
         */
        fun setpointFor(reaction: Reaction): Int? =
            SETPOINTS.firstOrNull { it > reaction.onsetKelvin && it <= reaction.ceilingKelvin }

        /**
         * The residence times the panel offers, in **ticks**.
         *
         * ⚠️ **Ticks, deliberately and temporarily.** This is the first thing in the game to name a
         * duration, and what a tick should be called in front of a player — seconds, cycles, anything
         * — is not decided (Stu, 2026-08-20). Naming it wrong now would put the wrong word in a save
         * file and in every screenshot; naming it `ticks` is obviously provisional, which is the
         * honest state of it.
         *
         * Zero first because zero is the default and the old behaviour: hand the charge on the moment
         * it is at temperature.
         */
        val DWELLS: List<Int> = listOf(0, 100, 250, 500, 1_000, 2_500, 5_000)

        /**
         * The conversion targets a recipe furnace offers, in permille of the principal.
         *
         * ⚠️ **It stops at 990 and that is not timidity.** Conversion is first-order decay, so every
         * extra nine costs as much time as all the nines before it put together: at fifty kelvin over
         * onset, 950‰ is about six hundred passes and 990‰ about twice that. A rung at 999‰ would be
         * a setting that looks like the others and quietly costs an hour.
         */
        val COMPLETIONS: List<Int> = listOf(500, 750, 900, 950, 990)

        /** Nine tenths — converted enough to be worth shipping, cheap enough to be worth waiting for. */
        const val DEFAULT_COMPLETION: Int = 900

        /**
         * The longest a recipe charge is held before it is handed on regardless, in ticks.
         *
         * ⛔ **A charge that cannot reach its target must still LEAVE.** Conversion is asymptotic, so
         * a furnace whose chemistry has stalled — a reagent the player re-plumbed away, a recipe
         * whose row somebody edited — would otherwise hold one charge for the rest of the game with
         * nothing on the panel to say why. This is the bound that turns "hangs" into "hands on
         * early", which is a thing the player can see and diagnose.
         *
         * ⚠️ **Generous on purpose.** Twenty thousand ticks is far past what any rung of [COMPLETIONS]
         * needs at any rung of [SETPOINTS]; it is a stop, not a second dial, and a charge that hits it
         * is reporting a fault rather than making a trade.
         */
        const val RECIPE_TIMEOUT_TICKS: Int = 20_000
    }
}
