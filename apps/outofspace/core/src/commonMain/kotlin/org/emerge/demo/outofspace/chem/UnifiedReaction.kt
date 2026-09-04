package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.scaledRatio

/**
 * A reaction that does not claim to know where the matter is — increments 1 and 4 of
 * `PLAN_unified_reactions.md`, and the shape the other four collapse into.
 *
 * [Decomposition], [Oxidation], [Reduction] and [Combustion] each state, by which table they live
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
 * thing every one of the four tables already nominates, under four names ([Decomposition.reactant],
 * [Oxidation.reactant], [Reduction.oxide], [Combustion.fuel]). **Products go wherever the principal
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
 * belt, and until increment 4 there was no shape that could say so — it sat in [REDUCTIONS], which
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
    /** Positive is **endothermic**, the sign convention of every table in this package. */
    val enthalpyPerKg: Long,
    val baseRate: Long,
) {
    /** Formula units of [principal], for the mass arithmetic and for what the reference prints. */
    val principalUnits: Int = reagents.first { it.first == principal }.second

    /** Mass of one formula unit's worth of [principal] — the denominator of every ratio here. */
    private val principalMass: Long = principalUnits.toLong() * principal.molarMass

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
     * [Oxidation.react]'s, generalised from one scarce reagent to all of them, and with its double
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
     * [Decomposition.decomposed]'s arithmetic, against the same shared Arrhenius climb in reduced
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
     * wrong: it sat in [DECOMPOSITIONS] with an onset of 1100 K while ammonia is evicted from a
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
        // +92 kJ per 2 mol of ammonia, which is 34 g of it: ΔH_f(NH₃) is −46 kJ/mol, and this row
        // cracks **two** of them.
        //
        // ⛔ **It said 46 until 2026-09-04, and the reason is written into the comment it replaces**
        // — *"the figure the row carried in [DECOMPOSITIONS], quoted against the same formula mass
        // so the move changes no number"*. The old row's principal was one ammonia; this one's is
        // two. Keeping the number while doubling the divisor is exactly how a per-mole figure
        // becomes a half-strength per-reaction one, and `everyEnthalpyIsQuotedAgainstItsOwnFormulaMass`
        // could not see it because a halved numerator over a doubled denominator is still a whole
        // number of kJ/mol. See `everyFireIsWorthWhatTheTableSaysItIs`, which is the check that
        // does.
        enthalpyPerKg = 92L * kJPerMolAt(34),
        baseRate = COMBUSTION_BASE_RATE,
    ),

    /**
     * `CO₂ + C → 2 CO` — the Boudouard reaction, and **the row that could not be written down
     * before**: its principal is in the room's air and its other reagent is on a belt.
     *
     * ⚠️ **The proving case for increment 4.** Every earlier shape draws its reagents from one
     * store. [Reduction] takes two solids out of one cargo layer, which is where this row sat — so
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
        // exactly as the row was quoted in [REDUCTIONS] — the move changes no number.
        onsetKelvin = 973,
        enthalpyPerKg = 172L * kJPerMolAt(44),
        baseRate = COMBUSTION_BASE_RATE,
    ),

    /**
     * `100 C₆H₁₂O₆ + 6 H₂O + 6 CO₂ → 101 C₆H₁₂O₆ + 6 O₂` — photosynthesis, and **the row whose
     * principal the unification had to change**.
     *
     * It was a [Reduction] quoted against its six waters, which made water the thing the rate was a
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
     * ⚠️ **Quoted against 100 × 180 g**, the principal's formula mass, not against the six waters.
     * +2803 kJ per mole of glucose formed is per mole of *reaction*, and the divisor has to be
     * whatever the rate is a fraction of — `UnifiedReactionTest` divides it back to check.
     */
    Reaction(
        principal = Species.Algae,
        reagents = listOf(Species.Algae to 100, Species.Water to 6, Species.CarbonDioxide to 6),
        products = listOf(Species.Algae to 101, Species.Oxygen to 6),
        onsetKelvin = 273, // ~0°C.
        enthalpyPerKg = 2803L * kJPerMolAt(18000),
        baseRate = BASE_RATE,
    ),

    // ══ THE FIRES ═════════════════════════════════════════════════════════════════════════════
    //
    // Increment 4b. These were [Combustion], a class earned by the fact that both reagents come out
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
        enthalpyPerKg = -802L * kJPerMolAt(16),
        baseRate = COMBUSTION_BASE_RATE,
    ),
    // 2 H2 + O2 -> 2 H2O. The cleanest and the most eager: nothing else here lights at 773 K and
    // leaves only water behind.
    Reaction(
        principal = Species.Hydrogen,
        reagents = listOf(Species.Hydrogen to 2, Species.Oxygen to 1),
        products = listOf(Species.Water to 2),
        onsetKelvin = 773,
        // −484 kJ per 2 mol of hydrogen, which is 4 g of it — two waters at −242 kJ/mol each.
        //
        // ⛔ **It said 242 until 2026-09-04**, which is per mole of *water* over the mass of *two
        // moles of hydrogen*: the row released half the energy hydrogen actually carries, on the one
        // fuel a vessel is most likely to burn on purpose. Every other row here is already quoted
        // per reaction as written — methane −802/16 g, CO −566/56 g, H₂S −1036/68 g, NH₃ −1267/68 g
        // — so this was the odd one out rather than a convention.
        //
        // ⚠️ **Lower heating value, like its neighbours**: the water leaves as a gas, so the −572 kJ
        // that condensing it would also give back is not on offer. The game has one specific heat
        // per species and no condensation enthalpy in a fire, so LHV is the figure that matches what
        // the products can actually hold.
        enthalpyPerKg = -484L * kJPerMolAt(4),
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
        enthalpyPerKg = -566L * kJPerMolAt(56),
        baseRate = COMBUSTION_BASE_RATE,
    ),
    // 2 H2S + 3 O2 -> 2 SO2 + 2 H2O. The lowest onset in the table by a wide margin, and the reason
    // a sour hold is the one to worry about first.
    Reaction(
        principal = Species.HydrogenSulfide,
        reagents = listOf(Species.HydrogenSulfide to 2, Species.Oxygen to 3),
        products = listOf(Species.SulfurDioxide to 2, Species.Water to 2),
        onsetKelvin = 533,
        enthalpyPerKg = -1036L * kJPerMolAt(68),
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
        enthalpyPerKg = -1267L * kJPerMolAt(68),
        baseRate = COMBUSTION_BASE_RATE,
    ),
    // S + O2 -> SO2. Sulfur vapour, which a pyrite roast puts into the air by the kilogram.
    Reaction(
        principal = Species.Sulfur,
        reagents = listOf(Species.Sulfur to 1, Species.Oxygen to 1),
        products = listOf(Species.SulfurDioxide to 1),
        onsetKelvin = 505,
        enthalpyPerKg = -297L * kJPerMolAt(32),
        baseRate = COMBUSTION_BASE_RATE,
    ),

    // ══ BURNING IN THE ROOM'S AIR ═════════════════════════════════════════════════════════════
    //
    // Increment 4b. These were [Oxidation] — a solid in a cargo layer plus the tile's oxygen — and
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
        // -393.5 kJ/mol of carbon. The number that makes a fire something that sustains itself: a
        // lump burning puts back about thirty times the energy it takes to hold it at its ignition
        // point.
        enthalpyPerKg = -394L * kJPerMolAt(12),
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
        // -1648 kJ per 4 mol of iron, which is 224 g of it. Hot iron in air gets hotter.
        enthalpyPerKg = -1648L * kJPerMolAt(224),
        // ⚠️ **A tenth of [BASE_RATE], and this is what makes "the oxygen attacks the carbon first"
        // true.** In a tile holding both, carbon asks for the larger share of a scarce supply, so it
        // gets it. There is no priority list; it is a consequence of two rates meeting.
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
        // -394 kJ per mole of carbon burned, and a formula unit of steel holds exactly one — so this
        // is the same figure the carbon row carries, quoted against the steel it came out of.
        enthalpyPerKg = -394L * kJPerMolAt(5556),
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
        enthalpyPerKg = 0L,
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
        enthalpyPerKg = 0L,
        baseRate = BASE_RATE,
    ),
)

/**
 * Every reaction in the game, in a fixed order — the whole of the game's chemistry, in one shape.
 *
 * ### ⚠️ Half of these are still *derived* from the old tables, on purpose
 *
 * [WRITTEN] is the rows that have been rewritten in this shape by hand. The rest are converted from
 * [DECOMPOSITIONS] and [REDUCTIONS] as the list is built.
 *
 * That is a migration step and it is deliberately the *safe* order. Twenty-two rows carrying
 * hand-copied stoichiometry, onsets and enthalpies is twenty-two chances to transpose a digit into a
 * table where a wrong number is invisible — it balances by eye, it passes every test that exists,
 * and it yields the wrong amount of the right thing for ever. Converting them mechanically means the
 * *pass* can be proved to run all of them before anybody retypes a formula.
 *
 * ⛔ **The sweeps that used to read those tables are gone.** A row here and a sweep there would be
 * two engines running the same reaction, which is worse than either. `DECOMPOSITIONS` and
 * `REDUCTIONS` are data for this list and nothing else reads them.
 *
 * ### Order is for reproducibility, not for priority
 *
 * Contention is settled by demand before anything is taken, so which row comes first changes only
 * the rounding. It must stay fixed for the simulation to be deterministic and it means nothing else.
 */
val REACTIONS: List<Reaction> = buildList {
    addAll(WRITTEN)
    for (d in DECOMPOSITIONS) {
        add(
            Reaction(
                principal = d.reactant,
                reagents = listOf(d.reactant to d.reactantUnits),
                products = d.products,
                onsetKelvin = d.onsetKelvin,
                enthalpyPerKg = d.enthalpyPerKg,
                baseRate = d.baseRate,
            ),
        )
    }
    for (r in REDUCTIONS) {
        add(
            Reaction(
                principal = r.oxide,
                // ⚠️ **The catalyst becomes an ordinary reagent on both sides**, which is what a
                // catalyst is: `100 ALGAE + 6 H₂O + 6 CO₂ → 101 ALGAE + 6 O₂`. The separate
                // [Reduction.catalyst] field was a bodge for a shape that could not say it, and the
                // shape can now — the hundred units bound the rate because they are contended like
                // any other reagent, so a bloom still grows in proportion to itself and nothing
                // special had to be written to make it.
                //
                // ⛔ It balances by mass, which is the only reason this is safe: 100 glucose + 6
                // water + 6 CO₂ is 18372 g, and 101 glucose + 6 O₂ is 18372 g. A catalyst that did
                // not close would be matter created every pass.
                reagents = withCatalyst(
                    listOf(r.oxide to r.oxideUnits, r.reductant to r.reductantUnits),
                    r.catalyst,
                    r.catalystUnits,
                ),
                products = withCatalyst(r.products, r.catalyst, r.catalystUnits),
                onsetKelvin = r.onsetKelvin,
                enthalpyPerKg = r.enthalpyPerKg,
                baseRate = r.baseRate,
            ),
        )
    }
}

/**
 * [entries] with [catalyst] added to them, or [entries] unchanged if there is no catalyst.
 *
 * The twin of `SpeciesInfo.kt`'s function of the same name, and it exists twice on purpose for
 * exactly as long as the reference and the simulation disagree about what a catalyst is: this one
 * makes it true, that one makes it *readable*. When [REDUCTIONS] is retyped in this shape the
 * catalyst field goes with it and both disappear.
 */
private fun withCatalyst(
    entries: List<Pair<Species, Int>>,
    catalyst: Species?,
    units: Int,
): List<Pair<Species, Int>> {
    if (catalyst == null) return entries
    if (entries.none { it.first == catalyst }) return listOf(catalyst to units) + entries
    return entries.map { (species, n) -> if (species == catalyst) species to (n + units) else species to n }
}

/** The widest [REACTIONS] gets, for the per-reagent scratch a sweep hoists once. */
val WIDEST_REACTION: Int = REACTIONS.maxOf { it.reagents.size }

/** The coldest row in [REACTIONS], so a cool tile is rejected without asking each one. */
val LOWEST_REACTION_ONSET: Int = REACTIONS.minOf { it.onsetKelvin }

/** The width of [REACTIONS], for the scratch arrays a sweep hoists once. */
val REACTION_COUNT: Int = REACTIONS.size
