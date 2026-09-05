package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.scaledRatio

/**
 * A reaction that does not claim to know where the matter is — increments 1 and 4 of
 * `PLAN_unified_reactions.md`, and the shape the other four collapse into.
 *
 * `Decomposition`, `Oxidation`, `Reduction` and `Combustion` each state, by which table they live
 * in, which **store** their matter is kept in: the first three are swept over the cargo layers and
 * the fourth over the fluid field. Nothing checks that claim, and for a fluid it is usually wrong —
 * `offGas` runs over the cargo layers on the same pass and empties them of anything the tile wants
 * as a gas. Three of twenty-two rows had onsets hundreds of kelvin above the point where their own
 * reactant is evicted, and so could only ever fire inside a sealed tile. See
 * `ReactionReachabilityTest`, which is the audit that found them.
 *
 * This type has no store. It states what reacts, what it becomes, how hot, how fast and at what
 * energy; the *pass* asks where the matter actually is.
 *
 * ### Placement: the principal's store, and nothing else
 *
 * [principal] is the reactant the rate is a fraction of and the enthalpy is quoted against — a
 * thing every one of the four tables already nominates, under four names (`Decomposition.reactant`,
 * `Oxidation.reactant`, `Reduction.oxide`, `Combustion.fuel`). **Products go wherever the principal
 * came from.**
 *
 * That one rule reproduces all four current behaviours exactly: a calcining mineral is in the cargo
 * layer so its lime and its CO₂ land there, and a burning gas is in the air so its exhaust lands
 * there. It also keeps the property that fixed the sealed-tile bug — ⛔ **a reaction never decides
 * phase.** `offGas` and condensation do, afterwards, where they can see whether there is anywhere
 * for a gas to go. A reaction that vented its own product put 18.45 kg of a live save inside six
 * hull plates; see `SealedTileGasTest`.
 *
 * ⚠️ **A product the principal's store cannot hold is not representable**, and that is deliberate
 * rather than an oversight. `MassIndex(tile, Species.Carbon)` does not compile, so a gas-phase
 * reaction yielding soot has nowhere to put it. The answer when one is wanted is to *widen the
 * fluid field* so the atmosphere can hold that species — not a third store, and not a rule against
 * the row. It is parked until a row worth having needs it (plan, decision 4), and
 * `ReactionReachabilityTest` fails any row that would need it in the meantime.
 *
 * ### Reagents come from wherever they are
 *
 * A reagent is drawn from **every store that holds it**, pooled. That is what makes the Boudouard
 * reaction expressible: `CO₂ + C → 2 CO` has its principal in the room's air and its carbon on a
 * belt, and until increment 4 there was no shape that could say so — it sat in `REDUCTIONS`, which
 * draws both reagents from one cargo layer, so it wanted CO₂ *as cargo* at 973 K and CO₂ is evicted
 * from a cargo layer above 304 K.
 *
 * ⚠️ **The principal is a reagent like any other and is contended like any other.** It is not
 * special in what it costs — only in what it decides: the temperature the rate is read at, and where
 * the products land.
 *
 * ### What is not here yet
 *
 * ⚠️ **[baseRate] is still stated rather than derived.** A solid burns at its surface and a gas
 * burns throughout, which is why `COMBUSTION_BASE_RATE` is eight times [BASE_RATE] — a fact about
 * the *phase of the principal at this tile*, which this model can now derive and does not. Increment
 * 4f, where it is a rate change and belongs with the pass that computes rates.
 */
class Reaction(
    /** What the rate is a fraction of, what the enthalpy is per kilogram of, and where products go. */
    val principal: Species,
    /** Everything consumed, [principal] included, as formula units. */
    val reagents: List<Pair<Species, Int>>,
    val products: List<Pair<Species, Int>>,
    val onsetKelvin: Int,
    val baseRate: Long,
) {
    /** Formula units of [principal], for the mass arithmetic and for what the reference prints. */
    val principalUnits: Int = reagents.first { it.first == principal }.second

    /** Mass of one formula unit's worth of [principal] — the denominator of every ratio here. */
    private val principalMass: Long = principalUnits.toLong() * principal.molarMass

    /**
     * The energy this row takes per kilogram of [principal], **derived** — positive is endothermic.
     *
     * ⛔ **Not a field, and that is the point.** It was one until [FORMATION_ENTHALPY] landed: every
     * row carried a hand-typed figure whose only oracle was a test naming nine of them by string.
     * Scoring the table against formation enthalpies found six rows wrong, two of them by a factor
     * of three, all of them passing every test that existed. A number nobody can check is a number
     * that is eventually wrong.
     *
     * Quoting it against [principalMass] is what makes it a per-kilogram figure of *the principal*,
     * which is what the rate is a fraction of. ⚠️ That divisor used to be hand-written too, and
     * getting it wrong is how a per-reaction figure silently becomes a half-strength one — the
     * ammonia and hydrogen rows both shipped that way. Deriving both halves from the same
     * [reagents] list is what closes it.
     */
    val enthalpyPerKg: Long = (
        hessEnthalpyKJ(reagents, products)
            ?: error(
                "No formation enthalpy for part of $principal's row — see FORMATION_ENTHALPY. " +
                    "A row whose energy is unknown must not default to athermal.",
            )
        ).toLong() * kJPerMolAt(principalMass)

    /** Mass each reagent's formula units account for, in [reagents] order. */
    private val reagentMasses: LongArray =
        LongArray(reagents.size) { reagents[it].second.toLong() * reagents[it].first.molarMass }

    /** Which entry of [reagents] is the principal, so the react path can skip its own ratio. */
    val principalIndex: Int = reagents.indexOfFirst { it.first == principal }

    /**
     * How much of reagent [i] goes with [principalMass] of the principal, on the stoichiometric
     * line.
     *
     * Exact ratios of formula-unit masses, as everywhere else in this package — ⚠️ **never a
     * hand-written mass fraction**, which would be a second source of truth for a number the species
     * table already answers and would be wrong silently.
     */
    fun reagentFor(i: Int, principalMass: Long): Long =
        if (i == principalIndex) principalMass
        else scaledRatio(reagentMasses[i], this.principalMass, principalMass)

    /** The inverse: how much principal [mass] of reagent [i] would support. */
    fun principalFor(i: Int, mass: Long): Long =
        if (i == principalIndex) mass
        else scaledRatio(this.principalMass, reagentMasses[i], mass)

    /**
     * What one pass consumes, given the principal present, how hot it is, and **how much of each
     * reagent this row is allowed** — which in a contended tile is less than it asked for.
     *
     * Writes the mass of each reagent into [out] and returns the principal's own share, or zero if
     * nothing happens.
     *
     * ### The starvation path
     *
     * `Oxidation.react`'s, generalised from one scarce reagent to all of them, and with its double
     * flooring intact. Every reagent is proportional to the principal, so shrinking the principal to
     * whatever the tightest allowance supports and then re-deriving *every* reagent from that
     * shrunken figure puts the whole row exactly on the stoichiometric line.
     *
     * ⛔ **Re-derived, never the allowance itself.** Taking `allowed[i]` directly for a reagent that
     * bound would leave that one reagent over-consumed relative to the rest — a reaction running
     * rich, which breaks the atom balance in the direction where it still looks like it is working.
     *
     * ⚠️ **The shrink is a single left-to-right pass and that is sufficient**, because shrinking for
     * one reagent can only reduce what every other reagent wants. Whichever allowance binds hardest
     * is the one still binding at the end.
     */
    fun react(principalPresent: Long, allowed: LongArray, kelvin: Int, out: LongArray): Long {
        var consumed = consumed(principalPresent, kelvin)
        if (consumed <= 0L) return 0L

        for (i in reagents.indices) {
            val want = reagentFor(i, consumed)
            if (want > allowed[i]) {
                consumed = principalFor(i, allowed[i])
                if (consumed <= 0L) return 0L
            }
        }

        for (i in reagents.indices) {
            val mass = reagentFor(i, consumed)
            // Flooring twice can only shrink, never inflate — but a reagent that floors to nothing
            // is a reaction that would take the others and give nothing back for them.
            if (mass <= 0L) return 0L
            out[i] = mass
        }
        return consumed
    }

    /** Everything [out] holds after a [react], which is the mass the products account for. */
    fun totalConsumed(out: LongArray): Long {
        var sum = 0L
        for (i in reagents.indices) sum += out[i]
        return sum
    }

    /**
     * How much of [mass] reacts in one pass at [kelvin], and nothing below the onset.
     *
     * `Decomposition.decomposed`'s arithmetic, against the same shared Arrhenius climb in reduced
     * temperature — the rate law was already one implementation across all four tables and this
     * does not make it a fifth.
     */
    fun consumed(mass: Long, kelvin: Int): Long {
        if (mass <= 0L || kelvin < onsetKelvin) return 0L
        return scaledRatio(reactionFraction(kelvin, onsetKelvin, baseRate), SCALE, mass)
    }

    /**
     * The mass of each of [products], in order, from [totalMass] of everything consumed.
     *
     * [apportion] makes the shares sum to exactly [totalMass], so the row closes whatever the
     * rounding does — the telescoping construction every other split in this package uses, and the
     * reason no arithmetic path here can invent or lose a gram.
     */
    fun split(totalMass: Long): LongArray = apportion(weights, totalMass)

    /** [split] writing into a caller's array, for a sweep that must not allocate per tile. */
    fun splitInto(totalMass: Long, out: LongArray) = apportionInto(weights, totalMass, out)

    /**
     * The mass of each of [reagents], in order, that [totalMass] of everything consumed is made of
     * — the twin of [split], on the other side of the arrow.
     *
     * ⚠️ **Not the same question as [reagentFor], and the difference is conservation.** That one
     * scales each reagent off the principal independently, which is what the ambient sweep wants
     * because the principal is what it *has*; the shares it returns need not sum to anything in
     * particular. This apportions a stated total, so the charge weighs exactly [totalMass] and the
     * row closes against [split] to the unit whatever the rounding does.
     *
     * The caller with a total rather than a principal is a station, whose batch is sized by what it
     * wants *out* — see `StationIndustry.kt`. It has no ledger watching it, which is the reason to
     * be exact here rather than an excuse not to be.
     */
    fun draw(totalMass: Long): LongArray = apportion(reagentWeights, totalMass)

    private val reagentWeights: LongArray =
        LongArray(reagents.size) { reagents[it].second.toLong() * reagents[it].first.molarMass }

    /** The energy [mass] of the **principal** takes to react, or gives back if it is negative. */
    fun enthalpy(mass: Long): Long = perKilogram(mass, enthalpyPerKg)

    private val weights: LongArray =
        LongArray(products.size) { products[it].second.toLong() * products[it].first.molarMass }
}

/**
 * Every reaction that has been moved off the four store-claiming tables, in a fixed order.
 *
 * One row today. It grows as increments 3 and 4 migrate the rest, and when the four tables are
 * empty this is the whole of the game's chemistry.
 *
 * ⚠️ **Order is for reproducibility, not priority** — the rule every other table in this package
 * states, and it will start to mean something once contention arrives in increment 3. For now
 * nothing here competes for anything.
 */
private val WRITTEN: List<Reaction> = listOf(
    /**
     * `2 NH₃ → N₂ + 3 H₂` — ammonia cracking, and the first reaction in the game that is swept over
     * the store its matter is actually in.
     *
     * ⚠️ **The awkward case on purpose**, exactly as `CARBON_BURN` was the awkward one for
     * `PLAN_ambient_chemistry.md`. Its principal is a *fluid*, which is the case the old shape got
     * wrong: it sat in `DECOMPOSITIONS` with an onset of 1100 K while ammonia is evicted from a
     * cargo layer above its critical point of 405 K, so it has never fired anywhere but inside a
     * bulkhead, where `offGas` is forbidden to run.
     *
     * It is also **endothermic**, which makes `applyAirEnthalpy`'s clamp a mechanism rather than the
     * guard against a hypothetical future it was written as: this is the first gas reaction that
     * takes energy out of a room instead of putting it in.
     *
     * Methane pyrolysis is the same fix and is *not* here — its carbon is not something the air can
     * hold. See the class doc, and the plan's decision 4.
     */
    Reaction(
        principal = Species.Ammonia,
        reagents = listOf(Species.Ammonia to 2),
        products = listOf(Species.Nitrogen to 1, Species.Hydrogen to 3),
        onsetKelvin = 1100,
        baseRate = COMBUSTION_BASE_RATE,
    ),

    /**
     * `CO₂ + C → 2 CO` — the Boudouard reaction, and **the row that could not be written down
     * before**: its principal is in the room's air and its other reagent is on a belt.
     *
     * ⚠️ **The proving case for increment 4.** Every earlier shape draws its reagents from one
     * store. `Reduction` takes two solids out of one cargo layer, which is where this row sat — so
     * it wanted carbon dioxide *as cargo* at 973 K, and CO₂ is evicted from a cargo layer above its
     * critical point of 304 K. `Combustion.kt` credits it with quietly filling the vessel's rooms
     * with carbon monoxide since `14306ded`; it has never fired outside a bulkhead.
     *
     * ⛔ **It crosses a ledger, and that is the point.** The carbon leaves the cargo identity and
     * comes back as part of the CO in the air identity, so a pass of it has to tell both. That is
     * the crossing `oxidise` deliberately closed off in one direction and `offGas` owns in the
     * other; here it is a consequence of where the reagents were, which is what the whole plan is
     * for.
     *
     * Strongly endothermic — a natural thermal brake on a hot room, and the second half of the
     * story a starved fire starts.
     */
    Reaction(
        principal = Species.CarbonDioxide,
        reagents = listOf(Species.CarbonDioxide to 1, Species.Carbon to 1),
        products = listOf(Species.CarbonMonoxide to 2),
        // Favourable around 973 K, dominant above 1200 K. Quoted per kg of CO2, the principal,
        // exactly as the row was quoted in `REDUCTIONS` — the move changes no number.
        onsetKelvin = 973,
        baseRate = COMBUSTION_BASE_RATE,
    ),

    /**
     * `100 C₆H₁₂O₆ + 6 H₂O + 6 CO₂ → 101 C₆H₁₂O₆ + 6 O₂` — photosynthesis, and **the row whose
     * principal the unification had to change**.
     *
     * It was a `Reduction` quoted against its six waters, which made water the thing the rate was a
     * fraction of. Under the placement rule that puts the reaction *in the room's air*, and the
     * algae it makes is not something the air can hold — so a bloom would have grown into the
     * atmosphere and been dropped on the floor, silently, every pass.
     *
     * ⚠️ **Asking "where should the products go?" answers "what is the principal?"** The bloom is
     * the thing that grows, so the bloom is what the products belong to, so the bloom is the
     * principal — and it is not a fluid, so the reaction happens in the tank. The water and the CO₂
     * come out of the air the tank is standing in, which is what photosynthesis is.
     *
     * ⛔ **The oxygen it makes lands in the cargo layer too, and that is correct.** A reaction never
     * decides phase; `offGas` releases it on the next pass, once it can see there is a room to
     * release it into. A tank inside a sealed bulkhead fills with its own oxygen instead of venting
     * it through a hull plate.
     *
     * ⚠️ **The catalyst is gone as a concept.** A hundred units in and a hundred and one out is
     * what a catalyst *is*, and being contended like any other reagent is what makes a bloom grow in
     * proportion to itself. `Reduction.catalyst` was a bodge for a shape that could not say it.
     *
     * ⚠️ **It is worth +2545 kJ here, not the textbook's +2803, and the difference is the water's
     * phase.** The familiar figure is quoted against *liquid* water; [FORMATION_ENTHALPY] quotes
     * water as a gas, because that is where the cohesion ledger's zero sits and the condensation is
     * `settleCohesion`'s to credit. Six waters at the 44 kJ/mol between the two is the whole of the
     * gap. Charging it here as well would be paying for the same phase change twice.
     */
    Reaction(
        principal = Species.Algae,
        reagents = listOf(Species.Algae to 100, Species.Water to 6, Species.CarbonDioxide to 6),
        products = listOf(Species.Algae to 101, Species.Oxygen to 6),
        onsetKelvin = 273, // ~0°C.
        baseRate = BASE_RATE,
    ),

    // ══ THE FIRES ═════════════════════════════════════════════════════════════════════════════
    //
    // Increment 4b. These were `Combustion`, a class earned by the fact that both reagents come out
    // of the air and every product goes back into it — which under the placement rule is not a
    // property of the row at all. It is a property of where the fuel happens to be, and the pass
    // works it out.
    //
    // ⚠️ **They had to move with the oxidations, not before them.** Both tables drink from a tile's
    // oxygen, and a well that covered one and not the other would put back the pass-order bug
    // increment 3 deleted — the fires taking their share after the belts had taken theirs.
    //
    // ⚠️ The onsets are real autoignition temperatures, which is what makes them a design: hydrogen
    // sulfide goes off at 260 °C and ammonia needs 651 °C, so a hold full of comet volatiles has a
    // temperature at which it becomes a problem and a different one at which it becomes a bomb.

    // CH4 + 2 O2 -> CO2 + 2 H2O. The marquee one: methane is what a comet gives up first and what
    // `offGas` puts into every room the vessel has.
    Reaction(
        principal = Species.Methane,
        reagents = listOf(Species.Methane to 1, Species.Oxygen to 2),
        products = listOf(Species.CarbonDioxide to 1, Species.Water to 2),
        onsetKelvin = 810,
        baseRate = COMBUSTION_BASE_RATE,
    ),
    // 2 H2 + O2 -> 2 H2O. The cleanest and the most eager: nothing else here lights at 773 K and
    // leaves only water behind.
    Reaction(
        principal = Species.Hydrogen,
        reagents = listOf(Species.Hydrogen to 2, Species.Oxygen to 1),
        products = listOf(Species.Water to 2),
        onsetKelvin = 773,
        baseRate = COMBUSTION_BASE_RATE,
    ),
    // 2 CO + O2 -> 2 CO2. Carbon monoxide is what a starved fire makes, so this is the second half
    // of a fire that did not get enough air the first time -- and what the Boudouard reaction has
    // been filling the rooms with since it started working.
    Reaction(
        principal = Species.CarbonMonoxide,
        reagents = listOf(Species.CarbonMonoxide to 2, Species.Oxygen to 1),
        products = listOf(Species.CarbonDioxide to 2),
        onsetKelvin = 882,
        baseRate = COMBUSTION_BASE_RATE,
    ),
    // 2 H2S + 3 O2 -> 2 SO2 + 2 H2O. The lowest onset in the table by a wide margin, and the reason
    // a sour hold is the one to worry about first.
    Reaction(
        principal = Species.HydrogenSulfide,
        reagents = listOf(Species.HydrogenSulfide to 2, Species.Oxygen to 3),
        products = listOf(Species.SulfurDioxide to 2, Species.Water to 2),
        onsetKelvin = 533,
        baseRate = COMBUSTION_BASE_RATE,
    ),
    // 4 NH3 + 3 O2 -> 2 N2 + 6 H2O. Ammonia is hard to light and worth the trouble: it burns back
    // to breathable nitrogen and water, so a vessel that can get a hold hot enough has a way of
    // turning a comet's worst volatile into two things it wants.
    Reaction(
        principal = Species.Ammonia,
        reagents = listOf(Species.Ammonia to 4, Species.Oxygen to 3),
        products = listOf(Species.Nitrogen to 2, Species.Water to 6),
        onsetKelvin = 924,
        baseRate = COMBUSTION_BASE_RATE,
    ),
    // S + O2 -> SO2. Sulfur vapour, which a pyrite roast puts into the air by the kilogram.
    Reaction(
        principal = Species.Sulfur,
        reagents = listOf(Species.Sulfur to 1, Species.Oxygen to 1),
        products = listOf(Species.SulfurDioxide to 1),
        onsetKelvin = 505,
        baseRate = COMBUSTION_BASE_RATE,
    ),

    // ══ BURNING IN THE ROOM'S AIR ═════════════════════════════════════════════════════════════
    //
    // Increment 4b. These were `Oxidation` — a solid in a cargo layer plus the tile's oxygen — and
    // the shape that made the class was that the reagents come from two different stores. Under the
    // placement rule that is what the pass does for every row, so the class had nothing left to be.
    //
    // ⚠️ **Their products stay in the cargo layer**, gaseous or not, because the principal is there.
    // A burning lump keeps the CO2 it just made until `offGas` finds somewhere for it to go. ⛔ That
    // is not a guard bolted on, it is the placement rule doing its job — and it is what stopped
    // 18.45 kg of a live save being sealed inside six hull plates.

    // C + O2 -> CO2. The first reaction the game ever had, and the one whose product leaves the
    // solid ledger entirely once `offGas` gets to it.
    Reaction(
        principal = Species.Carbon,
        reagents = listOf(Species.Carbon to 1, Species.Oxygen to 1),
        products = listOf(Species.CarbonDioxide to 1),
        onsetKelvin = CARBON_IGNITION_KELVIN,
        baseRate = BASE_RATE,
    ),
    // 4 Fe + 3 O2 -> 2 Fe2O3. Iron going back to ore, and the awkward direction: the product is a
    // solid, so the tile gets heavier and the air gets lighter.
    //
    // ⚠️ [IRON_OXIDATION_KELVIN] is dry oxidation, not rust in a puddle. Iron in damp air corrodes
    // at room temperature by an electrochemical mechanism this model has nothing to say about; what
    // is modelled is scale forming on hot iron.
    Reaction(
        principal = Species.Iron,
        reagents = listOf(Species.Iron to 4, Species.Oxygen to 3),
        products = listOf(Species.Hematite to 2),
        onsetKelvin = IRON_OXIDATION_KELVIN,
        baseRate = IRON_BASE_RATE,
    ),

    /**
     * `Fe₉₉C + O₂ → 99 Fe + CO₂` — **decarburisation, and the way back out of an alloy.**
     *
     * ⛔ **The only reaction in the game that runs a recipe backwards**, and it is not a special case
     * for doing so: it is what taking carbon out of steel actually is. Bessemer and basic-oxygen
     * steelmaking are precisely this — blow oxygen through the metal and the carbon leaves as gas —
     * and the reason a converter needs no fuel once it is lit is the sign of this row. So hull plate
     * marked for salvage becomes rail iron, and the thing it costs is *oxygen*, which is the first
     * job the atmosphere has ever had that a player has a reason to care about.
     *
     * ⚠️ **It balances exactly on the alloy formula and needed nothing changed to do so**, which is
     * the strongest evidence that `Fe₉₉C` was the right integer pair: one formula unit of steel holds
     * exactly one mole of carbon, so one mole of CO₂ carries it off and 99 iron atoms are left.
     * 5588 g on both sides.
     *
     * ⚠️ **You will not get all of your iron back, and that is the model working rather than
     * leaking.** Iron scales at [IRON_OXIDATION_KELVIN], far below this onset, so the iron this makes
     * is standing in hot air next to the rust row competing for the same oxygen. Carbon wins most of
     * it — [BASE_RATE] against [IRON_BASE_RATE] is the same "carbon outbids iron" mechanism the game
     * already runs on, and it is *why* steelmaking works in reality: at these temperatures carbon has
     * the greater affinity for oxygen. What is left over is scale, which is what a real converter
     * makes too.
     *
     * **Measured, 20 kg of steel in ambient air, share of the consumed steel recovered as iron:**
     *
     * | held at | converted in ~400 passes | iron kept | as scale |
     * |---|---|---|---|
     * | 1100 K | ~nothing | — | — |
     * | 1400 K | 6% | **90%** | 13% Fe₂O₃ |
     * | 1800 K | 54% | **66%** | 46% Fe₂O₃ |
     *
     * ⛔ **So temperature is already the dial, and it is a real trade rather than a tax**: hotter
     * converts faster and loses more of what it converts, because the rust row's rate climbs with the
     * same Arrhenius curve this one does. Metering the blow is how a real converter keeps the loss
     * down and would be a *machine*; it is deliberately not this row, and this table is the evidence
     * that one is not yet needed.
     *
     * The onset is solid-state decarburisation in air, which is a real and well-known nuisance from
     * about 700 °C — the soft skin on a forging that was left too long in the furnace. It does **not**
     * need the metal molten, so a hot airy room is enough and no melting model is implied.
     */
    Reaction(
        principal = Species.Steel,
        reagents = listOf(Species.Steel to 1, Species.Oxygen to 1),
        products = listOf(Species.Iron to 99, Species.CarbonDioxide to 1),
        onsetKelvin = 1000,
        baseRate = BASE_RATE,
    ),

    // ══ MAKING THINGS THE VESSEL IS BUILT OF ══════════════════════════════════════════════════
    //
    // The two rows that turn a *recipe* into a *reaction*. Steel and firebrick used to be
    // `Material` compositions — mixtures a construction site had to be fed in the right ratio, tile
    // by tile, from the ore field onwards — and the tolerance that made routing them survivable is
    // the same tolerance that let a microgram of water ice into a hull plate. Here the ratio is
    // arranged once, hot, in one place, and what comes out is one species that a belt, a filter and
    // a ghost can each say a single thing about.
    //
    // ⛔ **Both are quoted at zero enthalpy, and that is a statement rather than a gap.** Forming a
    // solid solution or a two-phase ceramic from its ingredients releases essentially nothing; the
    // energy a foundry and a brick kiln actually spend is spent *getting the charge to temperature*,
    // which is what [onsetKelvin] already makes the player pay for. ⚠️ What is genuinely not charged
    // is iron's heat of fusion — you cannot alloy steel without melting the iron, and the game has
    // no melting model to take it out of. Charging it here would be putting a melting cost inside a
    // reaction that is not melting, so it is left out and named instead.

    // 99 Fe + C -> Fe99C. Onset is iron's melting point, 1811 K: below it there is no liquid for the
    // carbon to dissolve into, and above it a furnace holding the charge there makes steel.
    Reaction(
        principal = Species.Iron,
        reagents = listOf(Species.Iron to 99, Species.Carbon to 1),
        products = listOf(Species.Steel to 1),
        onsetKelvin = 1811,
        baseRate = BASE_RATE,
    ),
    // 11 MgO + 6 SiO2 -> (MgO)11(SiO2)6. Refractories are fired somewhat above the temperature they
    // are then asked to survive, and 1700 K is the low end of a real magnesia-silica firing.
    //
    // ⚠️ **The onset is chosen to sit under what a carbon fire reaches** (~2300 K), because a
    // furnace lined with firebrick is otherwise the one machine you need heat to build. That the
    // temperature is reachable is a fact; that lighting a carbon fire in a charge of periclase and
    // quartz actually fires it **has not been played through** and is intent rather than a measured
    // bootstrap. The starter vessel ships no furnace today, so until it does (or until this path is
    // demonstrated) a fresh world cannot reach firebrick at all.
    Reaction(
        principal = Species.Periclase,
        reagents = listOf(Species.Periclase to 11, Species.Quartz to 6),
        products = listOf(Species.Firebrick to 1),
        onsetKelvin = 1700,
        baseRate = BASE_RATE,
    ),

    // ══ THERMAL DECOMPOSITION ═════════════════════════════════════════════════════════════════
    //
    // Retyped out of `Decomposition.kt`, which is deleted. A carbonate gives up its CO₂, a hydrate
    // gives up its water, a sulfide gives up its sulfur: no reagent but the reactant itself, which
    // is what made them a separate class and is now just a row with one entry in [reagents].
    //
    // ⚠️ **The onsets are real temperatures**, near enough: calcite calcines around 900 °C,
    // magnesite near 540 °C, serpentine gives up its hydroxyl water around 600 °C. They are what
    // make the setpoint on a decomposer a *decision* — a temperature that cracks magnesite leaves
    // calcite alone, so a mixed feed separates by heat rather than by a filter somebody wrote.

    // CaCO₃ → CaO + CO₂. The marquee tier-1 reaction, named in `Furnace`'s own documentation for as
    // long as it was impossible.
    Reaction(
        principal = Species.Calcite,
        reagents = listOf(Species.Calcite to 1),
        products = listOf(Species.Lime to 1, Species.CarbonDioxide to 1),
        onsetKelvin = 1170,
        baseRate = BASE_RATE,
    ),
    // MgCO₃ → MgO + CO₂, and at a much lower temperature than calcite — which is what makes a
    // dolomitic feed separable by setpoint alone, and what makes the cheapest reaction in the game
    // the one that yields the refractory everything else is fired in.
    Reaction(
        principal = Species.Magnesite,
        reagents = listOf(Species.Magnesite to 1),
        products = listOf(Species.Periclase to 1, Species.CarbonDioxide to 1),
        onsetKelvin = 810,
        baseRate = BASE_RATE,
    ),
    // Mg₃Si₂O₅(OH)₄ → Mg₂SiO₄ + MgSiO₃ + 2 H₂O. Serpentine is 13% water by mass and this is how a
    // vessel gets it out — the marquee reason an outer-system rock is worth hauling.
    Reaction(
        principal = Species.Serpentine,
        reagents = listOf(Species.Serpentine to 1),
        products = listOf(Species.Forsterite to 1, Species.Enstatite to 1, Species.Water to 2),
        onsetKelvin = 900,
        baseRate = BASE_RATE,
    ),
    // FeS₂ → FeS + S. The sulfur leaves as vapour and has to go somewhere, which is the first time a
    // refining step makes the room a problem rather than a backdrop.
    //
    // ⛔ **The one row whose energy depends on a question nobody has answered.** Sulfur's critical
    // temperature is 1314 K and this fires at 1000 K, so it is the only species the game makes below
    // its own critical point — where [FORMATION_ENTHALPY]'s reference phase stops being free. See
    // there, and `FormationTest.theReferencePhaseOnlyMattersForSpeciesNamedHere`.
    Reaction(
        principal = Species.Pyrite,
        reagents = listOf(Species.Pyrite to 1),
        products = listOf(Species.Troilite to 1, Species.Sulfur to 1),
        onsetKelvin = 1000,
        baseRate = BASE_RATE,
    ),
    // 6 Fe₂O₃ → 4 Fe₃O₄ + O₂, and it takes a serious temperature. Note what it does to a room: it
    // *makes* oxygen, so a hot bed of hematite is a slow air supply and also a reason for anything
    // carbon nearby to catch.
    Reaction(
        principal = Species.Hematite,
        reagents = listOf(Species.Hematite to 6),
        products = listOf(Species.Magnetite to 4, Species.Oxygen to 1),
        onsetKelvin = 1730,
        baseRate = BASE_RATE,
    ),
    // C₆H₁₂O₆ → CH₄ + CO₂ + 4 H₂O + 4 C. Cooking or pyrolyzing algae cracks it into volatile gases,
    // water vapour and a solid char residue — dead crops as a fuel source, or a life-support tank
    // ruined by being allowed to overheat.
    //
    // ⚠️ **It is exothermic, and it was written as endothermic.** The row carried +65 kJ/mol until
    // the enthalpies were derived; [FORMATION_ENTHALPY] says −166. Charring biomass warms the tile
    // it is standing on rather than cooling it, which is the correct direction and the opposite of
    // what the table used to claim.
    Reaction(
        principal = Species.Algae,
        reagents = listOf(Species.Algae to 1),
        products = listOf(
            Species.Methane to 1,
            Species.CarbonDioxide to 1,
            Species.Water to 4,
            Species.Carbon to 4,
        ),
        onsetKelvin = 353, // ~80°C -- algae dies and cooks well below mineral cracking points.
        baseRate = BASE_RATE,
    ),

    // ══ REDUCTION ═════════════════════════════════════════════════════════════════════════════
    //
    // Retyped out of `Reduction.kt`, which is deleted. An oxide and a solid reductant, which was a
    // class because it drew two reagents from one cargo layer — a distinction the placement rule
    // abolished, since the pass asks where each reagent is regardless.
    //
    // ⛔ **`Reduction.catalyst` is gone with the file.** It was a rate gate bolted on for a shape
    // that could not say "a hundred units in and a hundred and one out"; photosynthesis says it
    // above, as an ordinary reagent on both sides, which is what a catalyst *is*.
    //
    // ⚠️ **The chain loops, and that is the design.** Quartz and periclase come back out of it, so
    // what the titanium chain actually eats is carbon and heat.

    // SiO₂ + 2 C → Si + 2 CO. How silicon metal is really made, in a submerged-arc furnace, at a
    // temperature that is a problem in itself. Quartz is native and abundant, so this is the row
    // that needs no other row to have run first.
    Reaction(
        principal = Species.Quartz,
        reagents = listOf(Species.Quartz to 1, Species.Carbon to 2),
        products = listOf(Species.Silicon to 1, Species.CarbonMonoxide to 2),
        onsetKelvin = 2000,
        baseRate = BASE_RATE,
    ),
    // 2 MgO + Si → 2 Mg + SiO₂. The Pidgeon process, really done under vacuum — the magnesium comes
    // off as a vapour and is condensed. Here it stays a solid, because no metal in this game boils
    // and inventing a phase for this one would be a rule that applies to nothing else.
    //
    // Note what it gives back: the quartz returns, so the silicon is the only thing spent.
    Reaction(
        principal = Species.Periclase,
        reagents = listOf(Species.Periclase to 2, Species.Silicon to 1),
        products = listOf(Species.Magnesium to 2, Species.Quartz to 1),
        onsetKelvin = 1500,
        baseRate = BASE_RATE,
    ),
    // Mg₂SiO₄ + 4 C → 2 MgO + Si + 2 C + 2 CO. Driven at extreme heat to force carbothermic
    // reduction, then allowed to revert on slow cooling. The un-reverted carbon monoxide vents,
    // leaving an intimate solid mixture of magnesia, silicon metal and carbon soot.
    Reaction(
        principal = Species.Forsterite,
        reagents = listOf(Species.Forsterite to 1, Species.Carbon to 4),
        products = listOf(
            Species.Periclase to 2,
            Species.Silicon to 1,
            Species.Carbon to 2,
            Species.CarbonMonoxide to 2,
        ),
        onsetKelvin = 1800,
        baseRate = BASE_RATE,
    ),
    // MgSiO₃ + 3 C → MgO + Si + C + 2 CO. Pyroxene processing at high heat. Enstatite carries far
    // more silica than forsterite, so its slow-cooled reversion yields a structural surplus of
    // silicon metal while venting a cleaner ratio of carbon monoxide.
    Reaction(
        principal = Species.Enstatite,
        reagents = listOf(Species.Enstatite to 1, Species.Carbon to 3),
        products = listOf(
            Species.Periclase to 1,
            Species.Silicon to 1,
            Species.Carbon to 1,
            Species.CarbonMonoxide to 2,
        ),
        onsetKelvin = 1800,
        baseRate = BASE_RATE,
    ),
    // Fe₂SiO₄ + 2 C → 2 Fe + Si + 2 CO₂. Iron holds oxygen less tightly than magnesium does, so this
    // olivine cracks a good deal cooler — and the iron drops out as solid metal without any gaseous
    // reversion, leaving a clean unit of silicon behind.
    //
    // ⚠️ **It is not the cheap row it used to look like.** The table claimed 210 kJ per formula unit
    // until the enthalpies were derived; [FORMATION_ENTHALPY] says 691, and the comment that used to
    // stand here argued from the low onset to a low energy as though the two were the same fact.
    // They are not: the onset says when it *starts*, and the enthalpy says what it *costs*. ⛔ Note
    // also that this row and the ferrosilite one below make CO₂ where every other carbothermic row
    // here makes CO, which is worth a second look — CO is the favoured product at these
    // temperatures, and it is what the Boudouard row exists to say.
    Reaction(
        principal = Species.Fayalite,
        reagents = listOf(Species.Fayalite to 1, Species.Carbon to 2),
        products = listOf(Species.Iron to 2, Species.Silicon to 1, Species.CarbonDioxide to 2),
        onsetKelvin = 1250,
        baseRate = BASE_RATE,
    ),
    // 2 FeSiO₃ + 3 C → 2 Fe + 2 Si + 3 CO₂. The iron twin to enstatite, and like it a 1:1 mineral
    // structure that yields a large silicon surplus relative to the iron — at mid-tier furnace
    // temperatures.
    //
    // ⚠️ The only row that consumes two units of its principal, so the only one where the formula
    // mass and the molar mass are different numbers. ⚠️ It claimed 480 kJ and is worth 1208; see the
    // fayalite row above, which drifted the same way and for the same reason.
    Reaction(
        principal = Species.Ferrosilite,
        reagents = listOf(Species.Ferrosilite to 2, Species.Carbon to 3),
        products = listOf(Species.Iron to 2, Species.Silicon to 2, Species.CarbonDioxide to 3),
        onsetKelvin = 1200,
        baseRate = BASE_RATE,
    ),
    // FeTiO₃ + C → Fe + TiO₂ + CO. The Becher process: carbon takes the *iron's* oxygen and leaves
    // the titanium's alone, which is the whole trick — carbon cannot touch titania and does not have
    // to. Two useful solids out of one common rock, and ilmenite is six times commoner than rutile.
    Reaction(
        principal = Species.Ilmenite,
        reagents = listOf(Species.Ilmenite to 1, Species.Carbon to 1),
        products = listOf(Species.Iron to 1, Species.Rutile to 1, Species.CarbonMonoxide to 1),
        onsetKelvin = 1200,
        baseRate = BASE_RATE,
    ),
    // TiO₂ + 2 Mg → Ti + 2 MgO. Magnesiothermic reduction, and **exothermic** — magnesium wants
    // oxygen badly enough that this pays for itself once lit, which is exactly the property that
    // makes magnesium the reductant and carbon not. The periclase returns to feed the Pidgeon row.
    //
    // ⛔ **Carbon will not reduce titania**; it gives the carbide, which is why Kroll exists. A
    // one-row `Rutile + C → Ti` would be the hand-written fiction these tables refuse, and the chain
    // has to make a stronger reductant first.
    Reaction(
        principal = Species.Rutile,
        reagents = listOf(Species.Rutile to 1, Species.Magnesium to 2),
        products = listOf(Species.Titanium to 1, Species.Periclase to 2),
        onsetKelvin = 1100,
        baseRate = BASE_RATE,
    ),

    // ══ ROASTING, AND THE OXIDE ORES THAT NEED NO ROAST ═══════════════════════════════════════
    //
    // The refining shape `Minerals.kt` has named in its own documentation since before any of this
    // ran: **sulfides + O₂ → oxide + SO₂**, two-stage, and it makes a genuinely nasty gas the player
    // has to vent or scrub. Every row below is that shape or the second half of it.
    //
    // ⛔ **Roasting needed no new mechanism.** A solid drawing oxygen out of the room's air and
    // leaving a solid and a gas behind is `4 Fe + 3 O₂ → 2 Fe₂O₃` and `S + O₂ → SO₂` in one row, and
    // both of those already worked. What roasting costs is *data* — an oxide species per metal —
    // which is why the rows that need none come first.
    //
    // ⚠️ **These four add no species at all**, and that is why they are the batch that lands first:
    // three of the ores are oxides the game already mines, and the fourth is the sulfide whose oxide
    // does not exist to be made.

    // ⛔ **THE FIRST ROAST IS WRITTEN AND HELD BACK, and the reason is the price model.**
    //
    // `Ag₂S + O₂ → 2 Ag + SO₂` is real and is the roast that skips the oxide — silver oxide falls
    // apart above about 500 K, so there is nothing for a roast at 800 K to make and metallic silver
    // is simply what is left in the pan. Its formation enthalpies are already in
    // [FORMATION_ENTHALPY] and it balances.
    //
    // It fires `StationTest.at list prices no reaction pays`, and **not because the row is wrong.**
    // A compound's list price is `Σ partsPerThousand × elementPrice / 1000`, and a per-mille mass
    // fraction is three significant figures: argentite is 216/248 silver, which is 870.97 per mille
    // and is stored as an integer. That truncation is worth nothing at all on a rock made of iron
    // and oxygen, and silver is one of the most expensive elements in the game — so on this charge
    // it comes to more than the furnace fee, and a station will roast argentite for the rounding.
    //
    // ⚠️ **A latent hole in the pricing, not in the chemistry**, and this is the first row that
    // trades a precious metal against its own ore, which is why nothing found it before. Restoring
    // the row needs that decided — see the roasting notes in `PLAN_unified_reactions.md`.

    // ── The oxide ores: already mined, and never reducible until now ──
    //
    // ⚠️ **The cheapest metals in the game and nobody had noticed.** Chromite, pyrolusite and
    // cassiterite are minerals an extractor has always been able to dig up, they are *already*
    // oxides, and carbothermic reduction is a shape this table has run since the quartz row. They
    // needed no roast, no new species and no new mechanism — only somebody to write the row.
    // Chromite's abundance is 350000, which makes it commoner than native sulfur.

    // SnO₂ + 2 C → Sn + 2 CO. Tin smelting, which is the oldest carbothermic process there is and
    // the easiest: cassiterite gives its oxygen up at a temperature a charcoal fire reaches.
    Reaction(
        principal = Species.Cassiterite,
        reagents = listOf(Species.Cassiterite to 1, Species.Carbon to 2),
        products = listOf(Species.Tin to 1, Species.CarbonMonoxide to 2),
        onsetKelvin = 1500,
        baseRate = BASE_RATE,
    ),
    // MnO₂ + 2 C → Mn + 2 CO.
    //
    // ⚠️ **Written as one step and it is really three.** Pyrolusite sheds oxygen to Mn₂O₃ and Mn₃O₄
    // on the way up — its own `meltingKelvin` comment already says "decomposes" — and industry makes
    // ferromanganese rather than the pure metal because carbon dissolves into it. Both are true and
    // neither is a row this table can carry honestly: the intermediates would be two more species
    // that exist only to be consumed, and the carbide is a phase model the game does not have. The
    // onset is the one the real carbothermic route needs.
    Reaction(
        principal = Species.Pyrolusite,
        reagents = listOf(Species.Pyrolusite to 1, Species.Carbon to 2),
        products = listOf(Species.Manganese to 1, Species.CarbonMonoxide to 2),
        onsetKelvin = 1700,
        baseRate = BASE_RATE,
    ),
    // FeCr₂O₄ + 4 C → Fe + 2 Cr + 4 CO. Ferrochrome, and the row that makes the commonest ore in
    // the game worth mining: chromite is the *only* source of chromium there is, here and in
    // reality, and it hands over iron in the same pass.
    //
    // ⚠️ The real furnace makes an iron-chromium alloy, not two separate metals. The game has no way
    // to say "alloy" except as a species — see steel — and inventing a ferrochrome species to hold a
    // ratio nobody has chosen would be worse than handing over both metals and letting a belt carry
    // them.
    Reaction(
        principal = Species.Chromite,
        reagents = listOf(Species.Chromite to 1, Species.Carbon to 4),
        products = listOf(
            Species.Iron to 1,
            Species.Chromium to 2,
            Species.CarbonMonoxide to 4,
        ),
        onsetKelvin = 1900,
        baseRate = BASE_RATE,
    ),
)

/**
 * Every reaction in the game, in a fixed order — the whole of the game's chemistry, in one shape.
 *
 * ⛔ **One list, written once.** Until 2026-09-05 half of these were converted out of
 * `DECOMPOSITIONS` and `REDUCTIONS` as the list was built, because retyping twenty-two rows of
 * stoichiometry by hand is twenty-two chances to transpose a digit into a table where a wrong number
 * is invisible. That was the right order to migrate in and it is not a resting place: the rows are
 * typed here now and both files are deleted, so there is no second shape a reaction can be written
 * in and no conversion step to keep honest.
 *
 * ⚠️ **What made the retype safe was the enthalpies becoming derived first.** The one number that
 * could not be checked by eye is no longer carried by a row at all — see [FORMATION_ENTHALPY] — so
 * what was actually transcribed is formula units, onsets and rates, every one of which
 * `UnifiedReactionTest` closes atom by atom against [MINERALS].
 *
 * ### Order is for reproducibility, not for priority
 *
 * Contention is settled by demand before anything is taken, so which row comes first changes only
 * the rounding. It must stay fixed for the simulation to be deterministic and it means nothing else.
 * ⚠️ The order here is the order the converted list had, so the retype moved no row.
 */
val REACTIONS: List<Reaction> = WRITTEN

/** The widest [REACTIONS] gets, for the per-reagent scratch a sweep hoists once. */
val WIDEST_REACTION: Int = REACTIONS.maxOf { it.reagents.size }

/** The coldest row in [REACTIONS], so a cool tile is rejected without asking each one. */
val LOWEST_REACTION_ONSET: Int = REACTIONS.minOf { it.onsetKelvin }

/** The width of [REACTIONS], for the scratch arrays a sweep hoists once. */
val REACTION_COUNT: Int = REACTIONS.size
