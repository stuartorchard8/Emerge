package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.LOWEST_REACTION_ONSET
import org.emerge.demo.outofspace.chem.REACTIONS
import org.emerge.demo.outofspace.chem.Reaction
import org.emerge.demo.outofspace.chem.SCALE
import org.emerge.demo.outofspace.chem.WIDEST_REACTION
import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.apportionInto
import org.emerge.demo.outofspace.chem.fluid
import org.emerge.demo.outofspace.chem.massAtReducedDensity
import org.emerge.demo.outofspace.chem.reducedTemperature
import org.emerge.demo.outofspace.chem.saturatedVapourDensityAt
import org.emerge.demo.outofspace.chem.vaporisationHeat
import org.emerge.demo.outofspace.num.scaledRatio

/**
 * What a pass of ambient chemistry moved **between the solids and the air**, in both directions.
 *
 * Four numbers, not two, and never netted. Increment 1 only ever ran one way — carbon leaving a
 * belt to become CO₂ — so one mass and one energy said everything. Iron oxidising runs the other
 * way: the oxygen leaves the atmosphere and stays in the solid as scale. Summing the two into a net
 * figure would close both identities for the wrong reason, and the first reaction whose two
 * directions happened to be equal would look like no chemistry at all.
 *
 * [toGasMass]/[toGasEnergy] are what `solidBecameGas` books; [toSolidMass]/[toSolidEnergy] are what
 * `gasBecameSolid` books. Each pair travels together for the reason that function exists: the solid
 * ledger and the air ledger are separate identities, and telling one without the other reads as two
 * unrelated leaks in opposite directions.
 *
 * ⚠️ **One pass fills each pair and neither fills both.** [oxidise] can only ever move matter *into*
 * the layer, so its gas pair is always zero; [offGas] can only ever move matter out of it, so its
 * solid pair is. They share this class because they close the same two identities and the caller
 * adds them up, not because either is capable of the other's direction.
 */
class ChemistryStep(
    val toGasMass: Long,
    val toGasEnergy: Long,
    val toSolidMass: Long,
    val toSolidEnergy: Long,
    /**
     * Energy the reactions **made**, net — negative when they took more than they gave.
     *
     * A fifth number and a different kind from the other four: those are mass changing medium and
     * the heat that rode along with it, which is energy *moving*. This is energy appearing out of
     * chemical bonds or disappearing into them, so it belongs to neither medium's ledger and needs
     * telling on its own.
     *
     * ⚠️ **Positive is energy released.** Burning is strongly positive — a fire is a source — and
     * calcining is strongly negative, which is why a decomposer's element has to keep working.
     * This sentence had the two signs the wrong way round until 2026-08-26; the arithmetic never
     * did, and `reactionEnergy` adds this straight into `generatedEnergy`, which is a source term.
     */
    val releasedEnergy: Long,
) {
    val isNothing: Boolean
        get() = toGasMass == 0L && toGasEnergy == 0L && toSolidMass == 0L &&
            toSolidEnergy == 0L && releasedEnergy == 0L

    companion object {
        val NOTHING = ChemistryStep(0L, 0L, 0L, 0L, 0L)
    }
}

/**
 * Puts [delta] of reaction energy into the matter at [tile] and reports what was actually put.
 *
 * Positive is a reaction warming what it happened to; negative is one cooling it. The two are the
 * same arithmetic and it is written once, because a sign convention with two implementations is a
 * sign convention with one bug.
 *
 * ⚠️ **An endothermic reaction may not drive a tile below zero energy**, which is below absolute
 * zero and would read back as a nonsensical temperature for as long as the matter sat there. It is
 * clamped, and the clamp is why this returns a value rather than being a statement: what the ledger
 * must hear is what was *taken*, not what was asked for.
 *
 * In practice the clamp is nearly unreachable and is a guard rather than a mechanism — a pass
 * converts a fraction of a per cent of the matter, so the energy it draws is a fraction of a per
 * cent of what the matter holds, and a reaction that cools its own feed simply drops below its onset
 * and stops. That is the whole loop a furnace exists to fight: calcining takes more
 * energy per kilogram than the rock holds at its own calcining temperature, so the element has to
 * keep supplying it or the reaction stalls.
 */
private fun applyEnthalpy(layer: StuffLayer, tile: TileIndex, delta: Long): Long {
    if (delta == 0L) return 0L
    val applied = if (delta < 0L) maxOf(delta, -layer.energyAt(tile)) else delta
    if (applied == 0L) return 0L
    layer.addEnergy(tile, applied)
    return applied
}

/**
 * Everything the tile's air weighs.
 *
 * The presence-bitmask walk, so it costs the six-or-so species actually in the room rather than the
 * width of the array — see `MassArray.forEachFluid`. It is only asked at a tile that has already
 * answered yes to being hot, having oxygen, and holding a reactant.
 */
private fun airMassAt(air: MassArray, tile: TileIndex): Long {
    var total = 0L
    air.forEachFluid(tile) { _, mass -> total += mass }
    return total
}

/**
 * Volatiles leaving the matter that is carrying them, wherever the tile they are standing in will
 * have them — the other half of "[oxidise] may not put gas anywhere".
 *
 * A lump of comet ore is 8% water, 3% ammonia and 1% methane by mass, and for the whole life of the
 * game it carried them as though they were rock. Nothing asked whether a species that is a gas at
 * room temperature ought to still be riding a belt. This asks, once a pass, everywhere at once.
 *
 * ### The rule, and why it needs no rate
 *
 * A species leaves until the tile's gas is **saturated with it at the temperature of the matter it
 * is leaving** — [saturatedVapourDensity] is exactly the density at which a cell can hold no more
 * of a species as vapour, and [massAtReducedDensity] turns that into the mass a full tile will
 * take. What is already in the air counts against that ceiling, so a lump in a room that is already
 * thick with water vapour sheds nothing, and the same lump in a dry room sheds until it is not.
 *
 * That is a relaxation to equilibrium rather than a rate, and it is here for a reason worth
 * keeping: **a rate is a knob and equilibrium is not.** There is no number
 * in this function that anybody chose. A puddle in a sealed room evaporates until the room is
 * saturated and then stops, because that is what the dome says, not because a constant was tuned
 * until it looked right.
 *
 * ⚠️ **The temperature is the matter's, the pressure is the room's**, and the split is deliberate:
 * a wet rock in a vacuum chamber boils according to how hot *it* is against how hard the *room*
 * pushes back. Reading both off the air would make a hot lump in a cold room inert, which is the
 * one case where off-gassing is most obviously supposed to happen.
 *
 * ⚠️ **A species with no critical point on file has no liquid phase in this model and leaves in
 * full** — see [org.emerge.demo.outofspace.chem.CRITICAL], which holds five entries. Methane and
 * ammonia are gases here, unconditionally, so ore carrying them is ore carrying gas in a sack, and
 * this empties the sack. That is the honest reading of the equation of state and it is also, by
 * some distance, the largest thing this function does.
 *
 * ⛔ **No latent heat yet.** What leaves takes its share of the matter's warmth with it, the same
 * share [handOver] takes between a room and a pipe, but the *cost of the phase change itself* is
 * not charged — so evaporation here does not cool the thing evaporating. [
 * org.emerge.demo.outofspace.chem.cohesionEnergy] is the term that would, and wiring it in is a
 * separate piece of work with a ledger of its own. Until then a lump cannot chill itself back below
 * its own boiling point, and the saturation ceiling is the only thing stopping it.
 *
 * ### Where it may happen — the caller's decision, and no longer "anywhere with a room"
 *
 * [mayVent] answers for the **tile**, not for the matter, and it is the whole of the gate. It used
 * to be the inverse — "is this tile sealed" — and everywhere else was fair game, which made a hopper
 * of ore an open sack and a length of track a slow leak.
 *
 * ⛔ **That is why a store could not be a tank, and it is now the caller who says where a vent is.**
 * A machine's buffer never vents, so a tonne of liquid oxygen keeps. A run of track vents only where
 * the player has put a [org.emerge.demo.outofspace.world.machine.Valve] over it, which turns
 * "my ore is leaking" into "I built a place for it to leak". See `PLAN_fluid_thrusters.md` §2.1.
 *
 * ⚠️ **Structure is still part of the answer and must stay part of it.** A bulkhead has no gas cell,
 * so a volatile inside one has nowhere to go and stays in the lump — the guard `SealedTileGasTest`
 * pins. It is now one half of the caller's predicate rather than the whole of it, and dropping it
 * would put gas inside a wall.
 *
 * ⚠️ **The physics is unchanged and none of it moved.** Saturation, the vapour headroom, the latent
 * heat that makes a boiling liquid cool itself: all still here, all still running wherever they are
 * asked to. What changed is *where* they are asked. A gas fire is still perfectly reachable — a
 * player dumping an asteroid's volatiles to concentrate its ore more cheaply can still fill a
 * corridor with methane — it is a trade they made rather than an accident that befell them.
 *
 * ⚠️ **Give it the layer whose contents are cargo**, exactly as [oxidise] requires and for the same
 * reason: what leaves here is booked by `solidBecameGas`, which closes the cargo identity against
 * the air one. The deck's own metal is fabric and is counted somewhere else.
 */
fun offGas(
    layer: StuffLayer,
    air: MassArray,
    airEnergy: EnergyArray?,
    mayVent: (TileIndex) -> Boolean,
): ChemistryStep {
    var toGasMass = 0L
    var toGasEnergy = 0L
    var released = 0L

    // One buffer for the whole sweep rather than one per tile — [oxidise]'s reason exactly. Indexed
    // by [Fluid] ordinal because that is the space the answers live in, and it is a seventh of
    // [Species.COUNT].
    val leaving = LongArray(Fluid.COUNT)

    layer.forEachOccupiedTile { tile ->
        // The one question asked before any arithmetic, and it is the caller's to answer: may what
        // is standing here let go of its volatiles at all.
        if (!mayVent(tile)) return@forEachOccupiedTile

        val heldMass = layer.massAt(tile)
        if (heldMass <= 0L) return@forEachOccupiedTile
        val kelvin = layer.kelvinAt(tile)

        // ── What wants to leave, decided against the tile as it stands ────────────
        //
        // The presence-bitmask walk, so this costs the handful of species the matter actually
        // holds rather than the width of [Species]. **Decided here and applied below rather than
        // as it goes**, because taking mass out of the row that is being walked is exactly the
        // kind of thing that works until a species reaches zero.
        leaving.fill(0L)
        var total = 0L
        layer.forEachSpecies(tile) { species, held ->
            if (held <= 0L) return@forEachSpecies
            val fluid = species.fluid ?: return@forEachSpecies
            val room = vapourHeadroom(species, kelvin, air[tile, fluid])
            val release = if (held < room) held else room
            if (release <= 0L) return@forEachSpecies
            leaving[fluid.ordinal] = release
            total += release
        }
        if (total <= 0L) return@forEachOccupiedTile

        // ── And now it goes, and pays for going ───────────────────────────────────
        var latent = 0L
        for (fluid in Fluid.ALL) {
            val release = leaving[fluid.ordinal]
            if (release <= 0L) continue
            layer.add(tile, fluid.species, -release)
            air.add(tile, fluid, release)
            latent += vaporisationHeat(release, fluid.species, kelvin)
        }

        // The heat rides along with it, as a share of what the matter held before any of it left —
        // read before the loop above for the reason every share in [oxidise] is read against a
        // snapshot. A hot lump that shed cold vapour would have mislaid its joules.
        val carried = scaledRatio(total, heldMass, layer.energyAt(tile))
        if (carried != 0L) {
            layer.addEnergy(tile, -carried)
            airEnergy?.let { it[tile] += carried }
        }
        // ⛔ **The latent heat, taken out of what is left behind.** Until this was here, evaporation
        // was free: a lump shed vapour, the vapour took its share of the warmth, and the lump came
        // out at exactly the temperature it went in at. That is not a rounding error in the physics,
        // it is the missing half of the mechanism — **a boiling liquid cools itself**, and that is
        // what stops a warm wet rock emptying into the room until there is nothing left of it.
        //
        // Booked as [ChemistryStep.releasedEnergy] and negative, because it belongs to neither
        // medium's ledger: this is thermal energy disappearing into intermolecular bonds, which is
        // the same kind of quantity a reaction enthalpy is and is told the same way.
        //
        // ⚠️ Clamped by [applyEnthalpy] at zero, so matter too cold to pay the bill evaporates
        // anyway and merely reaches absolute zero rather than going below it. Nearly unreachable in
        // practice — the saturation ceiling is vanishingly small at the temperatures where it could
        // bind — but it is a clamp and not a refusal, and a caller that ever sees it should know
        // that the honest answer would have been "then it does not evaporate".
        released += applyEnthalpy(layer, tile, -latent)

        toGasMass += total
        toGasEnergy += carried
    }

    return if (toGasMass == 0L && toGasEnergy == 0L && released == 0L) {
        ChemistryStep.NOTHING
    } else {
        ChemistryStep(toGasMass, toGasEnergy, toSolidMass = 0L, toSolidEnergy = 0L, releasedEnergy = released)
    }
}

/**
 * How much more of [species] a full tile at [kelvin] will hold as vapour, given [inAir] of it there.
 *
 * [Long.MAX_VALUE] means "as much as you have": either the species has no critical point on file
 * and so no liquid phase in this model, or the matter is hotter than its critical temperature,
 * where liquid and vapour stop being different things and there is nothing to saturate.
 */
private fun vapourHeadroom(species: Species, kelvin: Int, inAir: Long): Long {
    // Null covers both escapes the doc names: no critical point on file, and hotter than the one
    // there is. The reduced temperature this used to take first said only the former.
    val vapourR = saturatedVapourDensityAt(kelvin, species) ?: return Long.MAX_VALUE
    val ceiling = massAtReducedDensity(vapourR, species, VolumeField.FULL, VolumeField.FULL)
        ?: return Long.MAX_VALUE
    return if (ceiling > inAir) ceiling - inAir else 0L
}

/**
 * **One pass, every reaction, both stores** — increment 4 of `PLAN_unified_reactions.md`.
 *
 * The pass that has no opinion about where matter is kept. A
 * [org.emerge.demo.outofspace.chem.Reaction] names a principal and nothing else about location; this
 * walks the tile's stores, finds the rows whose principal is in each, and runs them there.
 *
 * ### A store is a place a reaction happens *in*
 *
 * There are two kinds and they are not symmetric, because a room and a crate are not symmetric:
 *
 *  - **The fluid field** — [air] — is *ambient*. It is the room, and it touches everything standing
 *    in it. A reaction here may draw a reagent from any cargo layer at the tile, which is what makes
 *    the Boudouard reaction expressible: CO₂ in the room reaching the carbon on a belt.
 *  - **A cargo layer** is a *container*. A reaction in one draws from that layer and from the
 *    surrounding air — carbon on a belt taking the room's oxygen — and **not from another layer**.
 *
 * ⛔ **Two cargo layers at one tile do not touch, and that is a decision.** Pooling them would let a
 * hopper's charge reach onto a belt that merely runs past it, which is new behaviour nobody asked
 * for; `oxidise` never allowed it. The rule that everything touches the air and containers do not
 * touch each other is the physical reading and it is the one that preserves what the game already
 * did.
 *
 * ### One well per species, and this time it really is per species
 *
 * Increment 3 shared the *oxygen* between the passes and left every other reagent to whichever pass
 * happened to run. Here every consumer at a tile — each row, in each store it has a principal in —
 * states its demand for every reagent against one snapshot, the demands are pooled **by species**,
 * and each species' supply is divided once. Only then does anybody take anything.
 *
 * ⛔ `Reaction.kt`'s rule, finally at the level it was always about: *"Never resolve contention by
 * iteration order. Whoever ran first would get the whole supply."*
 *
 * ⚠️ **Cleared by touched-list, not by `fill`.** A tile touches a handful of species and there are
 * [Species.COUNT] of them; wiping the well per tile would be most of what this pass costs.
 *
 * ### Where the products go, and which ledger hears about it
 *
 * Into the store the principal was in — the placement rule, and the whole of it. Mass that crossed
 * to get there is booked: cargo drawn into an air reaction is [ChemistryStep.toGasMass], air drawn
 * into a cargo reaction is [ChemistryStep.toSolidMass], and each carries its share of the heat of
 * the store it left.
 *
 * ⛔ **A reaction still never decides phase.** A gaseous product of a cargo reaction stays in the
 * cargo layer; `offGas` releases it later, where it can see whether there is anywhere for it to go.
 * That is what stopped 18.45 kg of a live save being sealed inside six hull plates — see
 * `SealedTileGasTest` — and it survives the unification untouched.
 */
fun react(
    air: MassArray,
    airEnergy: EnergyArray,
    kelvin: IntArray? = null,
    layers: List<StuffLayer> = emptyList(),
): ChemistryStep {
    val tiles = air.data.size / Fluid.COUNT
    val temperature = kelvin ?: gasKelvin(airEnergy, air)

    // Hoisted for the whole sweep rather than per tile — [oxidise]'s reason exactly.
    val allowed = LongArray(WIDEST_REACTION)
    val taken = LongArray(WIDEST_REACTION)
    val parts = LongArray(REACTIONS.maxOf { it.products.size })
    val wantedBySpecies = LongArray(Species.COUNT)
    val scaleBySpecies = LongArray(Species.COUNT)
    val touched = IntArray(WIDEST_REACTION * REACTIONS.size)
    // ⛔ **What each consumer asked for, kept.** The react phase must divide the *same* number the
    // demand phase counted, and `feasible` reads live supply — so recomputing it after the first
    // consumer has drawn gives a different answer, and two identical cargo layers at one tile stop
    // burning identical amounts. Jacobi is a promise about one snapshot; this is where the snapshot
    // is held.
    val planned = LongArray(REACTIONS.size)

    var released = 0L
    // Always zero now that nothing crosses between media in this pass — kept so the shape of a
    // [ChemistryStep] is the same whoever produced it, and so a caller need not know which passes
    // can cross and which cannot.
    val toGasMass = 0L
    val toGasEnergy = 0L
    val toSolidMass = 0L
    val toSolidEnergy = 0L

    // −1 is the air; 0 and up are the cargo layers. One index space so the two loops below can be
    // written once, since everything except the lookups is identical between them.
    val firstStore = -1
    val lastStore = layers.size - 1

    for (i in 0 until tiles) {
        val tile = TileIndex(i)

        // ── Each store, alone with itself ────────────────────────────────────
        //
        // ⛔ **A store reacts with what it is holding, and with nothing else.** Demand, the division
        // of a scarce reagent, and the draw are all inside this loop now, so a packet's fire is fed
        // by the packet's own oxygen and a room's fire by the room's. Stu, 2026-09-04: *"now oxygen
        // has to be present in the mixture to react."*
        //
        // What went with the coupling, deliberately: a lump no longer rusts in the air it passes
        // through, steel no longer gives up its carbon to a hot room, and a fire on a belt no longer
        // dies because the room ran out. Those were the mechanic `AmbientChemistry` was built around,
        // and they are gone on purpose — see `PLAN_fluid_thrusters.md` §2.2.
        //
        // ⚠️ **Nothing crosses between media here any more**, so this pass books no
        // `toGasMass`/`toSolidMass` at all: `offGas` is the only thing that moves matter between the
        // cargo ledger and the air one. The terms are still returned, and are still zero, because a
        // caller that stopped summing them would be wrong the day anything crosses again.
        for (store in firstStore..lastStore) {
            val hot = storeKelvin(store, tile, temperature, layers)
            if (hot < LOWEST_REACTION_ONSET) continue

            // ── What this store's rows want, against one snapshot of it ──────
            //
            // ⛔ Still Jacobi, and still for its original reason: the apportionment below must
            // divide the same number this counted, or two rows drawing on one reagent stop being
            // contended and start being served in declaration order.
            var marks = 0
            for (r in REACTIONS.indices) {
                val reaction = REACTIONS[r]
                val present = presentIn(store, tile, reaction.principal, air, layers)
                if (present <= 0L) continue
                val consumed = feasible(reaction, present, hot, store, tile, air, layers)
                planned[r] = consumed
                if (consumed <= 0L) continue
                for (n in reaction.reagents.indices) {
                    val ordinal = reaction.reagents[n].first.ordinal
                    if (wantedBySpecies[ordinal] == 0L) touched[marks++] = ordinal
                    wantedBySpecies[ordinal] += reaction.reagentFor(n, consumed)
                }
            }
            if (marks == 0) continue

            // What fraction of its own demand each row may have — flooring every share means they
            // cannot sum past what this store holds.
            for (m in 0 until marks) {
                val ordinal = touched[m]
                val supply = supplyOf(Species.ALL[ordinal], store, tile, air, layers)
                val want = wantedBySpecies[ordinal]
                scaleBySpecies[ordinal] = if (want <= supply) SCALE else scaledRatio(supply, want, SCALE)
            }

            // ── And now it happens ───────────────────────────────────────────
            for (r in REACTIONS.indices) {
                val reaction = REACTIONS[r]
                val present = presentIn(store, tile, reaction.principal, air, layers)
                if (present <= 0L) continue

                val unconstrained = planned[r]
                if (unconstrained <= 0L) continue
                for (n in reaction.reagents.indices) {
                    val want = reaction.reagentFor(n, unconstrained)
                    val scale = scaleBySpecies[reaction.reagents[n].first.ordinal]
                    allowed[n] = if (scale >= SCALE) want else scaledRatio(want, SCALE, scale)
                }
                val consumed = reaction.react(present, allowed, hot, taken)
                if (consumed <= 0L) continue

                // ⛔ **From this store, and there is no second place to look.** Matter reacting
                // where it already is has not gone anywhere, so its share of the store's warmth
                // stays exactly where it is — which is what `carryHeat = false` says, and what
                // `AmmoniaCrackingTest` caught the energy ledger a couple of billion joules short
                // for want of.
                for (n in reaction.reagents.indices) {
                    if (taken[n] <= 0L) continue
                    drawFrom(store, tile, reaction.reagents[n].first, taken[n], air, airEnergy, layers, carryHeat = false) { _, _ -> }
                }

                // ── And what it becomes, in the principal's store ────────────
                reaction.splitInto(reaction.totalConsumed(taken), parts)
                for (p in reaction.products.indices) {
                    val mass = parts[p]
                    if (mass <= 0L) continue
                    addTo(store, tile, reaction.products[p].first, mass, air, layers)
                }

                // Per kilogram of the **principal**, which is what the rate was a fraction of and
                // what the row's enthalpy is quoted against.
                released += applyStoreEnthalpy(store, tile, -reaction.enthalpy(consumed), airEnergy, layers)
            }

            for (m in 0 until marks) {
                wantedBySpecies[touched[m]] = 0L
                scaleBySpecies[touched[m]] = 0L
            }
            planned.fill(0L)
        }
    }

    return if (released == 0L && toGasMass == 0L && toSolidMass == 0L) ChemistryStep.NOTHING
    else ChemistryStep(toGasMass, toGasEnergy, toSolidMass, toSolidEnergy, released)
}

/**
 * What [reaction] wants at [tile], **bounded by what could possibly be delivered**.
 *
 * ⛔ **A row may not reserve a reagent it has no hope of using.** Its rate depends only on the
 * principal and the temperature, so a fire with no oxygen anywhere still "wants" a share of the
 * carbon — and the well, dividing honestly between everybody who asked, hands it one. The carbon is
 * then not consumed, because the reaction cannot run; it is simply withheld from the reduction that
 * could have had it.
 *
 * The symptom is a charge that reduces at the same rate in air as in a vacuum, which is the exact
 * opposite of what the two tables meeting is supposed to produce, and `ReductionSweepTest` has a
 * case named for it — *"is the carbon being double-spent rather than contended?"* It is neither: it
 * is being **reserved by a ghost**.
 *
 * So the demand is clamped, per reagent, to the most that reagent's entire supply could support.
 * Still Jacobi — every row is asked against the same snapshot, before anything is taken — with the
 * one addition that a row cannot ask for more than the tile could ever give it.
 *
 * ⚠️ **Both phases must call this**, or the demand a row is counted for and the allowance it is
 * given come from two different questions.
 */
private fun feasible(
    reaction: Reaction,
    present: Long,
    kelvin: Int,
    store: Int,
    tile: TileIndex,
    air: MassArray,
    layers: List<StuffLayer>,
): Long {
    val consumed = reaction.consumed(present, kelvin)
    if (consumed <= 0L) return 0L
    for (n in reaction.reagents.indices) {
        if (n == reaction.principalIndex) continue
        // ⛔ **Absent, not merely scarce.** Clamping the demand down to what the supply could
        // support looks like the thorough version and is wrong: when a reagent is the binding
        // constraint every row clamps to the *same* ceiling, which flattens the very proportions the
        // apportionment exists to preserve. "The oxygen attacks the carbon first" is true because
        // carbon asks for ten times what iron asks for; clamp both to the tile's oxygen and they ask
        // for the same thing, and `AmbientChemistryTest` watches iron outbid carbon.
        //
        // Scarcity is the well's job. All this has to remove is the demand that could never be met
        // at all.
        if (supplyOf(reaction.reagents[n].first, store, tile, air, layers) <= 0L) return 0L
    }
    return consumed
}

/** The air's index in [react]'s one store index space. Cargo layers are 0 and up. */
private const val AIR_STORE = -1

/** How hot the matter in this store is — ⚠️ **its own temperature, never the tile's average**. */
private fun storeKelvin(store: Int, tile: TileIndex, airKelvin: IntArray, layers: List<StuffLayer>): Int =
    if (store == AIR_STORE) airKelvin[tile.index] else layers[store].kelvinAt(tile)

/** How much of [species] this store holds — zero for a solid asked of the air, which cannot hold it. */
private fun presentIn(
    store: Int,
    tile: TileIndex,
    species: Species,
    air: MassArray,
    layers: List<StuffLayer>,
): Long =
    if (store == AIR_STORE) species.fluid?.let { air[tile, it] } ?: 0L
    else layers[store][tile, species]

/**
 * Takes up to [owed] of [species] out of one store, and reports what it took and the heat that went
 * with it.
 *
 * ⚠️ **The heat share is read before the subtraction**, which is [offGas]'s rule and its reason: a
 * store that shed matter and then worked out what it was worth would have mislaid its joules.
 */
private inline fun drawFrom(
    store: Int,
    tile: TileIndex,
    species: Species,
    owed: Long,
    air: MassArray,
    airEnergy: EnergyArray,
    layers: List<StuffLayer>,
    carryHeat: Boolean = true,
    took: (mass: Long, heat: Long) -> Unit,
): Long {
    if (owed <= 0L) return 0L
    if (store == AIR_STORE) {
        val fluid = species.fluid ?: return 0L
        val here = air[tile, fluid]
        if (here <= 0L) return 0L
        val drawn = if (here < owed) here else owed
        val heat = if (carryHeat) scaledRatio(drawn, airMassAt(air, tile), airEnergy[tile]) else 0L
        air.add(tile, fluid, -drawn)
        if (heat != 0L) airEnergy[tile] -= heat
        took(drawn, heat)
        return drawn
    }
    val layer = layers[store]
    val here = layer[tile, species]
    if (here <= 0L) return 0L
    val drawn = if (here < owed) here else owed
    val heat = if (carryHeat) scaledRatio(drawn, layer.massAt(tile), layer.energyAt(tile)) else 0L
    layer.add(tile, species, -drawn)
    if (heat != 0L) layer.addEnergy(tile, -heat)
    took(drawn, heat)
    return drawn
}

/** Puts [mass] of [species] into one store. */
private fun addTo(
    store: Int,
    tile: TileIndex,
    species: Species,
    mass: Long,
    air: MassArray,
    layers: List<StuffLayer>,
) {
    if (store == AIR_STORE) {
        // ⛔ Unrepresentable rather than merely skipped: a product the air cannot hold would vanish
        // here, and quietly. `ReactionReachabilityTest` is what stops such a row being written.
        val fluid = species.fluid ?: return
        air.add(tile, fluid, mass)
    } else {
        layers[store].add(tile, species, mass)
    }
}

/** Adds heat that rode in with crossing matter. */
private fun addEnergyTo(
    store: Int,
    tile: TileIndex,
    energy: Long,
    airEnergy: EnergyArray,
    layers: List<StuffLayer>,
) {
    if (store == AIR_STORE) airEnergy[tile] += energy else layers[store].addEnergy(tile, energy)
}

/** [applyEnthalpy] or [applyAirEnthalpy], whichever this store's matter is counted by. */
private fun applyStoreEnthalpy(
    store: Int,
    tile: TileIndex,
    delta: Long,
    airEnergy: EnergyArray,
    layers: List<StuffLayer>,
): Long =
    if (store == AIR_STORE) applyAirEnthalpy(airEnergy, tile, delta)
    else applyEnthalpy(layers[store], tile, delta)

/**
 * How much of [species] is at [tile] across every store a reaction may draw from.
 *
 * ⚠️ **The pooling is what makes a cross-store row expressible**, and it is also the thing that
 * makes "which store is this reaction in?" stop being a question with an answer. A reagent is
 * wherever it is; the row does not say and does not need to.
 */
/**
 * How much of [species] the store at [store] is holding at [tile] — **its own, and nothing else's.**
 *
 * ⛔ It used to pool the whole tile: the air plus every cargo layer standing in it, so a lump on a
 * belt burned in the room's oxygen. That coupling is gone (Stu, 2026-09-04) and this is where it
 * lived. A packet reacts with what is in the packet.
 */
private fun supplyOf(
    species: Species,
    store: Int,
    tile: TileIndex,
    air: MassArray,
    layers: List<StuffLayer>,
): Long =
    if (store == AIR_STORE) species.fluid?.let { air[tile, it] } ?: 0L
    else layers[store][tile, species]
/**
 * [applyEnthalpy]'s twin for the atmosphere, and clamped for the same reason: a reaction may not
 * drive a cell below zero energy, which is below absolute zero and would read back as a nonsensical
 * temperature for as long as the gas sat there.
 *
 * ⚠️ **The clamp is a mechanism now, not a guard.** Every row of [COMBUSTIONS] is exothermic, so
 * while fires were the only gas chemistry this only ever added. [REACTIONS] brought ammonia
 * cracking, which is endothermic: it is the first thing that takes energy *out* of a room's air, and
 * so the first thing that can drive a cell toward zero. What the ledger must hear is what was
 * actually taken, which is why this returns a value rather than being a statement.
 */
private fun applyAirEnthalpy(airEnergy: EnergyArray, tile: TileIndex, delta: Long): Long {
    if (delta == 0L) return 0L
    val applied = if (delta < 0L) maxOf(delta, -airEnergy[tile]) else delta
    if (applied == 0L) return 0L
    airEnergy[tile] += applied
    return applied
}
