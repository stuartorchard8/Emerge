package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.chem.CRITICAL
import org.emerge.demo.outofspace.chem.CLOSE_PACKED
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.machine.MACHINE_BUFFER_CAP
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.VolumeField
import org.emerge.demo.outofspace.world.capacityPerTile
import org.emerge.demo.outofspace.world.massPerTile
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.world.Negligible
import org.emerge.demo.outofspace.world.SLOTS
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.solidMassPerTile
import org.emerge.demo.outofspace.world.material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The overflow budget, as a tripwire.
 *
 * `NUMERIC_LIMITS.md` surveys every place a mass meets a fixed-point scale and works out how much
 * room is left in a `Long`. This is that survey made executable, and it exists for one job: the mass
 * unit is being rebaselined away from "one integer is one gram", and **the whole cost of that change
 * is paid in these intermediates**. Scaling mass by `k` shrinks the headroom of a linear term by `k`
 * and of a quadratic one by `k²`, silently, with no symptom until a pressure or a velocity flips
 * sign somewhere far from the change.
 *
 * ### How to use it during the rescale
 *
 * Turn the knob in [Budget] itself. [targetMassScale] is derived from it — the distance still to
 * travel — so every row that cannot support the remaining rescale fails **by name**, which is the
 * list of expressions that have to be restructured first: a to-do list generated from the game's own
 * constants rather than from anybody's memory of them. As the knob moves the demand shrinks to match,
 * and when it arrives the file keeps guarding the arithmetic it now has.
 *
 * ⚠️ It used to be a literal that had to be raised by hand, and the two got out of step the moment
 * the knob moved: the rows measure the game's constants *in today's unit*, so a fixed million asked
 * for the rescale twice over and every row went red at once. A budget stated independently of the
 * unit it is budgeting is not a budget.
 *
 * ### What a failure means
 *
 * Not necessarily a bug. A row going red is usually this test doing its job: telling you which
 * expression blocks the next order of magnitude. Read the name, decide whether to fix the expression
 * or lower the ambition, and update `NUMERIC_LIMITS.md` in the same commit.
 *
 * ### What it deliberately does not check
 *
 * The two things already broken at one-gram units — `reducedPressure` at the packing wall, and the
 * five-unit diffusion stranding floor (§6.1, §6.2). Both are real and both are documented, but
 * neither is an overflow this shape of test can express, and pinning them here would mean asserting
 * that a bug is still present — which goes red when somebody fixes it and teaches the next reader to
 * distrust the file.
 */
/**
 * Where the energy unit is going: one **centijoule** per integer, ten times coarser than the
 * millijoule the game has always used. Written here rather than derived from [Budget] because it is
 * the destination, and a destination read off the current position is not one.
 *
 * The mass target needs no equivalent: it is one microgram per integer, which is
 * [Budget.MICROGRAMS_PER_UNIT] equalling 1 and so already spelled out by the field's own units.
 */
private const val TARGET_NANOJOULES_PER_UNIT: Long = 10_000_000L

class NumericLimitsTest {

    /**
     * How much finer the mass unit still has to get: the factor every mass in this file would be
     * multiplied by if the target were reached today.
     *
     * ### Why it is derived and not written down
     *
     * It used to be a literal `1_000_000`, and that was right for as long as the knob had not moved.
     * Every worst case in this file is measured by *running the game's own constants* — a hull's
     * `massPerTile`, a `Storage.CAP` — which are integer counts in whatever unit `Budget` is set to
     * right now. Multiply those by a fixed million and the answer is only the target while the build
     * is still at one gram per integer. Turn the knob and the file asks for the rescale **twice**:
     * every row failed, including rows with nothing wrong with them, and the whole file had to be
     * discounted as an artefact at exactly the moment it was most worth reading.
     *
     * [Budget.MICROGRAMS_PER_UNIT] *is* the remaining factor, by definition — it is how many
     * micrograms one integer is still worth, and the target is one. So this reads `1_000_000` at
     * today's unit and `1` at the target, and the file measures the same thing either way. At the
     * target the rows still assert real headroom against [safetyFactor], and the ALREADY OVERFLOWED
     * check still bites; what goes away is only the demand for a rescale that has already happened.
     */
    private val targetMassScale = Budget.MICROGRAMS_PER_UNIT

    /**
     * How much room every row keeps on top of the target, so that a budget is never merely *just*
     * met. Four rather than ten because two of these rows are already inside a factor of twenty at
     * one gram per unit, and a margin that fails on arrival teaches nothing.
     */
    private val safetyFactor = 4.0

    /**
     * The fastest the ship is expected to go, in tiles per tick.
     *
     * From `OutofspaceHud.NAV_FULL_SCALE_SPEED` — the speed the nav needle is drawn full-scale at,
     * which is the closest thing the game has to a stated design speed. Restated rather than
     * imported because a HUD constant moving for a presentation reason should not silently change
     * what this file believes about flight.
     */
    private val designTopSpeed = 2L

    /**
     * The fewest ticks the ship may take to reach [designTopSpeed] from rest.
     *
     * A design assumption, and the honest way to bound a *per-tick* impulse — which is what
     * `frameAcceleration` divides, unlike the running total `velocityX` divides. The alternative
     * bound is the physical one, "all of the momentum arrives in a single tick", and that is both
     * absurd (the measured peak on a breached hull is six orders below it) and so pessimistic that
     * the row would report a limit nothing can ever hit.
     *
     * ⚠️ **This assumption was FALSIFIED at step 4 of `PLAN_unit_rescale.md`, and it is left here
     * only because nothing else uses it.** It bounds the per-tick impulse at about 2.2e6 — but a rock
     * landing on the deck delivers a contact impulse of **at least 4.3e9**, which is known exactly
     * because that is `Long.MAX / FRAC_ONE`, the value at which the old `netImpulse * FRAC_ONE`
     * wrapped. It was wrapping: `RockContactTest :: a body that lands on the deck settles and stays
     * put` was failing at one gram per unit for that reason and passes now that `frameAcceleration`
     * goes through [scaledRatio].
     *
     * The lesson is about the shape of the file, not the number. A budget row is only as good as the
     * worst case handed to it, and this one was derived from *thrust* — a smooth, designed
     * acceleration — while the expression it guards is also fed by *collisions*, which are neither.
     * Three orders of magnitude of understatement is why the row read green over a live overflow.
     * Any row here whose worst case comes from a design intention rather than a measurement deserves
     * the same suspicion.
     */
    private val minTicksToTopSpeed = 100L

    /** Hot enough for a smelter, cold enough to be a real state. The top of the thermal range. */
    private val designMaxKelvin = 3000L

    /** Tiles in a default rock blob — `Edit.DEFAULT_ROCK_RADIUS` of 2 fills 21 of its 25 cells. */
    private val ROCK_TILES = 21L

    private val gridTiles: Long = OutofspaceConfig().initialGrid.size.toLong()


    /**
     * How many tiles of *floor* a machine consumes, as against how many tiles of material it is
     * made of.
     *
     * The two are the same for everything except a bridge, which is three tiles of rail spanning a
     * gap while occupying none of it (`thermalTiles`, `MachineKind.size`). That exception is the
     * whole reason this is a separate number: divide by the wrong one and a bridge either vanishes
     * from the budget or is counted as if it were three deck plates.
     */

    /**
     * The most mass, and the most heat capacity, that a **single tile** of a packed grid can carry.
     *
     * ⚠️ This is the correction that step 1 of `PLAN_unit_rescale.md` exists to make, and it is worth
     * stating plainly because the wrong version was load-bearing for a conclusion in the survey.
     *
     * The bulk rows used to read `heaviestMachineMass * gridTiles`: a whole smelter's mass charged
     * to **every tile in the grid**. But a smelter is five tiles across, so a grid packed solid with
     * smelters holds `gridTiles / 25` of them, not `gridTiles`. Multiplying the whole-machine figure
     * by the tile count double-counts the footprint and overstates the bound by exactly `thermalTiles`
     * — 25× for the machines that dominate the maximum.
     *
     * Dividing the per-machine figure by its footprint gives the honest quantity: a density, in
     * grams (or joules per kelvin) **per tile of deck**, which is then multiplied by the grid. For
     * Per **tile**, and every kind's figures are already per tile, so there is no divisor left to
     * get wrong — the machine list this used to walk kept a whole-machine total that had to be
     * divided by a footprint, and a bridge was the exception that made the divisor worth stating.
     */
    private val densestTileMass: Long = DeckMachineKind.ALL.maxOf { it.massPerTile }
    private val densestTileCapacity: Long = DeckMachineKind.ALL.maxOf { it.capacityPerTile }

    /**
     * A ship the game actually flies, rather than the heaviest one that could be drawn.
     *
     * An eighth of the grid in hull, which is roughly what a hull enclosing a usable interior comes
     * to and is within a factor of three of the 110 tonnes measured on the standard bare hull.
     */
    private val referenceShipMass: Long = DeckMachineKind.Hull.material.massPerTile * gridTiles / 8L

    /**
     * An integer count out of [Budget]'s current mass unit and into plain grams.
     *
     * Every mass in this file comes from the game's own constants, so it is a count of whatever one
     * integer is worth today. The budget rows want exactly that. The two *expression* cases below
     * want a physical ship they can then scale themselves, and the difference is invisible until the
     * knob moves — at which point they scale an already-scaled number and overflow.
     */
    private fun inGrams(units: Long): Long = units / Budget.GRAM

    /**
     * A heat capacity in [Budget]'s **energy** unit, from a mass in its mass unit.
     *
     * ### Why the rows cannot say `grams * specificHeat`
     *
     * Five of them did, and it was an energy for exactly as long as the two units were one unit.
     * Specific heat is quoted per kilogram against a thousandth of a joule, so the product has to be
     * divided by [Budget.CAPACITY_DIVISOR] — which was 1, and so read as nothing at all.
     *
     * Left uncorrected the rows overstate a solid tile's energy by ten million and two of them
     * reported ALREADY OVERFLOWED at the target: a tripwire announcing a wrap in an expression the
     * game does not evaluate. That is the more dangerous half of §6.4's lesson seen from the other
     * side — the same missing factor read *green* over the live `heatPerGram` overflow one commit
     * ago, because it was missing from the model and the code in the same way.
     *
     * The mass is divided **first**, matching `capacityPerTileOf` rather than being algebraically
     * tidy: the intermediate a budget row measures has to be the intermediate the code forms, and
     * the code forms this one.
     */
    private fun capacityOf(grams: Long, specificHeat: Long): Long =
        grams / Budget.CAPACITY_DIVISOR * specificHeat

    /** The densest a tile of gas can legitimately get: close packing, three times critical. */
    private val densestPackedLiquid: Long =
        Species.ALL.mapNotNull { CRITICAL[it] }.maxOf { it.massPerTile * 3 }

    /** The densest a tile of anything can get. Not in any intermediate — kept as a stated bound. */
    private val densestSolidTile: Long = Species.ALL.maxOf { it.solidMassPerTile }

    /**
     * `Composition.VOLUME_UNIT`, restated because it is private.
     *
     * ⚠️ If that constant moves and this does not, the `massPerTileOf` row goes quietly wrong. It is
     * restated rather than the constant made internal because the row it guards is about an
     * *unstated invariant* (see the row itself), and widening a constant's visibility would be the
     * wrong repair for that.
     */
    private val volumeUnit = 1_000_000_000L

    /** `PressureForce.SOUND_IMPULSE`, likewise private, and derived from ambient rather than fixed. */
    private val soundImpulse: Long = Stuff.AMBIENT_AIR.total / 4

    private val rows = mutableListOf<String>()
    private val failures = mutableListOf<String>()

    /**
     * One intermediate product.
     *
     * @param exponent how the worst case grows with the mass unit — 1 for a lone mass, 2 where two
     *   masses are multiplied together. Quadratic rows are the dangerous ones: they look roomy at
     *   one gram per unit and are spent four times as fast.
     *
     *   **0 means the expression is scale-invariant**: its worst case is bounded by the *physics*
     *   rather than by the unit, so raising the knob does not spend it at all. That is what step 4's
     *   `scaledRatio` converts a row into, and it is a stronger result than merely widening one —
     *   a `k^1` row has to be re-derived at every future rescale, and a `k^0` row never does.
     */
    /**
     * ⚠️ Formatting is done by hand because this file is in **commonTest**, and `String.format` is a
     * JVM API. It compiles on JVM and fails to resolve on JS, so the tripwire commit that introduced
     * it broke `compileTestKotlinJs` while every JVM run stayed green — the exact trap `reference_
     * common_source_set_jvm_apis` documents. Left as helpers rather than moving the file to jvmTest,
     * because the budget it guards is common-source arithmetic and belongs beside it.
     */
    private fun Long.commas(): String =
        toString().reversed().chunked(3).joinToString(",").reversed()

    /** Three significant figures, without `%.3g`. */
    private fun Double.sig3(): String {
        if (this == 0.0 || !this.isFinite()) return toString()
        val exponent = kotlin.math.floor(kotlin.math.log10(kotlin.math.abs(this))).toInt()
        val mantissa = this / pow10(exponent)
        val rounded = kotlin.math.round(mantissa * 100.0) / 100.0
        return if (exponent in -3..5) {
            val scaled = kotlin.math.round(this * pow10(2 - exponent)) / pow10(2 - exponent)
            scaled.toString()
        } else {
            "${rounded}e$exponent"
        }
    }

    private fun pow10(n: Int): Double {
        var out = 1.0
        repeat(kotlin.math.abs(n)) { out *= 10.0 }
        return if (n < 0) 1.0 / out else out
    }

    private fun String.pad(width: Int): String = padEnd(width)

    /**
     * How a row's integer count moves when a unit does.
     *
     * ⚠️ There used to be one exponent, against the mass scale, and that was correct **only while
     * `Budget` held the energy unit equal to the mass unit**. It no longer does, and the difference
     * is not a refinement: an energy quantity is a physical amount of joules divided by the energy
     * unit, so how many integers it takes **does not depend on the mass unit at all**. Every joules
     * row here was reading as `k¹` in mass and it is `k⁰`.
     *
     * Getting that wrong in the other direction is the dangerous case, and it is why this is spelled
     * out per row rather than inferred: a row that ignores a unit it actually depends on reads green
     * over a live overflow, which is `NUMERIC_LIMITS.md` §6.4's whole lesson.
     */
    private enum class Dim {
        /** Grams, and momentum with them. Scales with the mass unit only. */
        MASS,

        /** Joules. Scales with the energy unit only, and that unit is getting **coarser**. */
        ENERGY,

        /** A ratio, a count, a fixed-point fraction. Scales with neither. */
        NONE,
    }

    /**
     * How much finer the energy unit is getting — **less than one**, because it is getting coarser.
     *
     * `NANOJOULES_PER_UNIT` goes from 1e6 (a millijoule) to 1e7 (a centijoule), so every energy
     * quantity takes a tenth as many integers to say.
     *
     * Derived from the current unit for the same reason [targetMassScale] is: it is the distance
     * still to travel, so it reads `0.1` at the millijoule and `1.0` once the centijoule is in
     * place. Current over target rather than target over current, because a smaller unit means
     * *more* integers — the one place in this file where the ratio genuinely inverts.
     */
    private val targetEnergyScale =
        Budget.NANOJOULES_PER_UNIT.toDouble() / TARGET_NANOJOULES_PER_UNIT

    private fun budget(name: String, worst: Long, exponent: Int, dim: Dim = Dim.MASS) {
        val perPower = when (dim) {
            Dim.MASS -> targetMassScale.toDouble()
            Dim.ENERGY -> targetEnergyScale
            Dim.NONE -> 1.0
        }
        val required = safetyFactor * when {
            exponent == 0 -> 1.0
            exponent == 2 -> perPower * perPower
            else -> perPower
        }
        if (worst <= 0L) {
            failures += "$name: worst case came out $worst — it has ALREADY overflowed a Long"
            rows += "  ${name.pad(52)} OVERFLOWED"
            return
        }
        val headroom = Long.MAX_VALUE.toDouble() / worst.toDouble()
        val safeK = if (exponent == 2) kotlin.math.sqrt(headroom) else headroom
        val safe = if (exponent == 0) "safe k=ANY" else "safe k=${safeK.sig3()}"
        rows += "  ${name.pad(52)} worst=${worst.toString().pad(20)} ${dim.name.first()}^$exponent  " +
            "headroom=${headroom.sig3()}  $safe"
        if (headroom < required) {
            failures += "$name: headroom ${headroom.sig3()}, needs ${required.sig3()} for a mass " +
                "scale of $targetMassScale — restructure it or lower the target " +
                "(NUMERIC_LIMITS.md §8)"
        }
    }

    @Test
    fun `every mass-carrying intermediate keeps its budgeted headroom`() {
        // ── Flight ────────────────────────────────────────────────────────
        //
        // These two used to be the tightest rows in the game — `velocityX` supported a mass scale of
        // 16.7 against a target of a million. Step 4 of PLAN_unit_rescale.md routed both through
        // [scaledRatio], which reduces the fraction before scaling it, and that takes the mass unit
        // out of the expression **entirely**: what is left is bounded by how fast the ship goes, not
        // by what a gram is worth. Hence `k^0`.
        //
        // ⚠️ Note what these rows now measure: the surviving `n / d * scale` whole-part term. The
        // remainder term cannot overflow by construction (the reduction guarantees it), so budgeting
        // it would be budgeting an identity. What CAN still overflow is a ratio that is physically
        // enormous — a gram of hull carrying a ship's momentum — and that is what the design speed
        // below stands in for.
        budget("velocityX: top speed * PER_TILE (scale-invariant)", designTopSpeed * Flight.PER_TILE, 0)
        // Bounded by a spin-up time rather than by the whole momentum landing at once — see
        // [minTicksToTopSpeed]. Rounded up to a whole tile/tick^2, since the honest figure is below
        // one and a budget of "less than one Frac" is not a budget.
        budget("frameAcceleration: 1 tile/tick^2 * FRAC_ONE (scale-invariant)", Flight.FRAC_ONE, 0)

        // ── Cargo and mixtures ────────────────────────────────────────────
        // apportion used to multiply each weight by the target before dividing by the sum, and BOTH
        // are masses — the tightest quadratic term in the game, safe mass scale 152. Step 4b made it
        // a running total rounded through [scaledRatio]: `cumulative / sum` is a ratio bounded by
        // ONE, so the whole-part term can never exceed the target itself. What is left is linear,
        // and what bounds it is the largest pile anyone can ask to have split — a full Storage.
        budget("apportion: running total, bounded by the target (full Storage)", Storage.CAP, 1)
        budget("apportion: running total, bounded by the target (machine buffer)", MACHINE_BUFFER_CAP, 1)
        // This row used to guard an UNSTATED INVARIANT rather than a margin: `total * VOLUME_UNIT`
        // was safe only because both call sites happen to pass per-mille compositions totalling
        // ~1000 (Material.composition; RockSpawner normalises), and handing that function a real
        // pile of ore — which its signature invites — broke it at 9.2e9 g.
        //
        // Step 4 routed it through [scaledRatio] too, so the invariant is gone: the function is now
        // correct for any pile at any mass unit, and what bounds it is the answer it returns, which
        // is a density. Hence `k^0` against the densest tile there is.
        budget("massPerTileOf: densest tile (scale-invariant)", densestSolidTile, 0)

        // `capacityPerTileOf` multiplies that tile by the mass-averaged specific heat — and unlike
        // most products here, only ONE side is a mass. The tile scales with k; a specific heat is a
        // property of the material and never moves. So this is k¹.
        //
        // ⚠️ Note what is *absent*: [SPECIFIC_HEAT_SCALE]. Written as `tile × mean / SCALE` it would
        // be here, and it measured a safe unit of 118,000 — a row that was already red for the
        // microgram target. Splitting the average instead bounds this by the bare specific heat, so
        // the precision of the average and the headroom of the product no longer trade against each
        // other. If that split is ever undone, this row has to grow the factor back.
        budget(
            "capacityPerTileOf: densest tile * mean specific heat",
            densestSolidTile * Species.ALL.maxOf { it.specificHeat.toLong() },
            1,
        )

        // ── Gas and pressure ──────────────────────────────────────────────
        // `grams / criticalDensity` is a ratio of two masses; step 5 takes it first, so what bounds
        // this is the answer it returns — a multiple of critical density — times the cell fullness.
        // Budgeted at twelve times close packing, far past anything the volume clamps allow, since
        // the point of the row is that no mass can reach it whatever a unit means.
        budget(
            "reducedDensity: over-packed cell * FULL (scale-invariant)",
            12L * CLOSE_PACKED * VolumeField.FULL, 0,
        )
        budget(
            "millimolesOf: packed liquid * millimoles/kg",
            densestPackedLiquid * (1_000_000L / Species.Water.molarMass), 1,
        )
        // Measured against ordinary operating pressure rather than the pathological close-packed
        // one: a tile at the packing wall is in the broken regime of §6.1 anyway, so budgeting for
        // it would be budgeting to keep a bug survivable.
        // Both of these were k² for the same reason and are linear for the same reason: what was
        // written as `mass × mass / mass` is a ratio of two like quantities times a third, and step
        // 4b takes the ratio first. See [scaledRatio].
        //
        // `pressure / AMBIENT_PRESSURE` is unitless, so what is left is the pressure ratio times
        // SOUND_IMPULSE — measured at ordinary operating pressure, for the reason above.
        budget("potentialOf: pressure ratio * SOUND_IMPULSE (10 atm)", 10L * soundImpulse, 1)
        // The species share is now reduced ONCE against a constant, so the per-call term is bounded
        // by the mass handed in rather than by mass times share.
        budget("ambientPressureOf: grams, share reduced once", densestPackedLiquid, 1)
        // ⚠️ The one term step 4b ADDED, and it is the tightest scale-invariant row in the table:
        // the remainder half of that call is `(SHARE_ONE - 1) × AMBIENT_SHARE[s]`, and both are
        // billionths, so it sits at 10^18 with a headroom of nine — forever, at any mass unit.
        // Restated rather than imported because SHARE_ONE is private to Pressure.kt; if that
        // constant is ever widened past a billion, this row is what fails.
        budget("ambientPressureOf: SHARE_ONE^2, the remainder half", 1_000_000_000L * 1_000_000_000L, 0)

        // ── Heat ──────────────────────────────────────────────────────────
        // A single entry of a machine's `joules`, which since step 6b is **one tile of it** rather
        // than all of it. That is the whole of the change as far as this file is concerned: the
        // `thermalTiles` factor left the expression, and with it the 24.76x that made this the last
        // arithmetic row standing between the game and a microgram.
        //
        // ⚠️ Still k^1. This divided a constant by twenty-five; it did not remove an exponent the
        // way steps 4 and 4b did, so the row keeps its slope and goes red again a little past 1e7.
        budget(
            "machine joules: hottest single tile of the heaviest machine",
            DeckMachineKind.ALL.maxOf { it.capacityPerTile } * designMaxKelvin,
            1, Dim.ENERGY,
        )
        // The densest a tile can be, machine and air together — the true per-tile energy ceiling,
        // and the one a rescale has to keep representable no matter how big the map gets.
        budget(
            "tile joules: densest deck tile + its air at max kelvin",
            (densestTileCapacity + capacityOf(Stuff.AMBIENT_AIR.total, Species.Water.specificHeat.toLong())) *
                designMaxKelvin,
            1, Dim.ENERGY,
        )
        // ⚠️ A rock tile is not a deck tile, and `tile joules` above does not bound one. That row
        // uses [densestTileCapacity], a *machine* capacity — machines are hollow (`fillPermille`),
        // where a rock is solid all through, so a rock tile is some 177x heavier than the densest
        // thing that row measures. Two variants, because the difference between them is the whole
        // question of how conservative to be:
        //
        //   - the honest one, `max over species of (tile mass x its OWN specific heat)`. No material
        //     is both the densest and the most heat-hungry, so this is a real material.
        //   - the fictional pairing the [capacityPerTileOf] row uses, densest x hottest.
        budget(
            "solid tile joules: the heaviest real material at max kelvin",
            Species.ALL.maxOf { capacityOf(it.solidMassPerTile, it.specificHeat.toLong()) } * designMaxKelvin,
            1, Dim.ENERGY,
        )
        budget(
            "solid tile joules: densest x hottest (a material that does not exist)",
            capacityOf(densestSolidTile, Species.ALL.maxOf { it.specificHeat.toLong() }) * designMaxKelvin,
            1, Dim.ENERGY,
        )
        budget(
            "solid tile joules: the heaviest real material at AMBIENT",
            Species.ALL.maxOf { capacityOf(it.solidMassPerTile, it.specificHeat.toLong()) } *
                Temperature.AMBIENT_KELVIN.toLong(),
            1, Dim.ENERGY,
        )
        // ⚠️ A whole free body was the tightest quantity in the game — twenty-one tiles of solid
        // rock in one `Long` — and it is now [TileEnergy], one figure per cell. The row is kept
        // pointed at a single cell, and it is the *real*-material bound that it holds to: a body's
        // composition is a mixture of real species, so `densest x hottest` describes a rock that
        // cannot exist. The fictional pairing above is retained beside it precisely so the distance
        // between the two is visible rather than assumed — it is a factor of 25, and the target sits
        // between them.
        // Scales with grid AREA as well as with the mass unit — the one row where growing the map
        // spends overflow headroom. Uses the per-tile density, not the per-machine capacity: see
        // [densestTileCapacity] for why the old form overstated this by 25x.
        // ⚠️ This is a LEDGER aggregate (`storedEnergy`), not a stored simulation quantity, and
        // PLAN_unit_rescale.md §2 puts those out of scope — it is expected to be the last row red
        // when the knob moves, and step 3 decides what to do about it. Corrected, it now agrees with
        // the 7.56e12 J the plan quotes, which the old 25x form did not.
        budget(
            "ship joules: that across the whole grid",
            densestTileCapacity * designMaxKelvin * gridTiles, 1, Dim.ENERGY,
        )
        budget(
            "atmosphere joules: ambient air across the whole grid",
            capacityOf(Stuff.AMBIENT_AIR.total, Species.Water.specificHeat.toLong()) *
                Temperature.AMBIENT_KELVIN.toLong() * gridTiles, 1, Dim.ENERGY,
        )

        // ── Bulk mass ─────────────────────────────────────────────────────
        budget("solid mass: densest deck across the whole grid", densestTileMass * gridTiles, 1)
        budget("cargo: Storage.CAP across the grid", Storage.CAP * gridTiles, 1)
        budget("densest single tile (bound, not an intermediate)", densestSolidTile, 1)

        // Printed on every run, green or red. `NUMERIC_LIMITS.md` §10 says re-measuring means
        // reading this table, and a table you can only see by breaking the build is not one you can
        // re-measure from — the figures in §7 went stale for exactly that reason.
        println(
            buildString {
                appendLine("Overflow budget at ${Budget.MICROGRAMS_PER_UNIT} µg / " +
                    "${Budget.NANOJOULES_PER_UNIT} nJ per unit " +
                    "(mass still to go x$targetMassScale, energy x${targetEnergyScale.sig3()}):")
                for (r in rows) appendLine(r)
            },
        )

        if (failures.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("The overflow budget in NUMERIC_LIMITS.md no longer holds")
                    appendLine("(target mass scale $targetMassScale, safety factor $safetyFactor):")
                    for (f in failures) appendLine("  - $f")
                    appendLine()
                    appendLine("Full table:")
                    for (r in rows) appendLine(r)
                },
            )
        }
    }

    /**
     * The flight model gives the **same velocity** whatever the mass unit is.
     *
     * ### Why this replaced a budget assertion
     *
     * There used to be a case here called `the flight model can fly the reference ship at design
     * speed`, which computed `Long.MAX / (PER_TILE × topSpeed)` and asserted the ship came in under
     * it. That number was real: `velocityX` multiplied momentum by `PER_TILE` before dividing by
     * mass, so there genuinely was a mass above which a ship's velocity wrapped and flipped sign.
     *
     * Step 4 removed the ceiling rather than raising it — `scaledRatio` reduces the fraction before
     * scaling, so there is no longer a mass at which flight breaks, and an assertion about where
     * that mass falls has nothing left to describe. Replacing it with a *bigger* number would have
     * been the worse outcome: it would still be a paper budget, still need re-deriving at the next
     * rescale, and still say nothing about whether the code does the right arithmetic.
     *
     * So this asserts the property the fix actually claims. Velocity is a ratio, and a ratio is
     * unitless: multiply both the momentum and the mass by the mass scale and the answer must not
     * move. That is the whole content of "scale-invariant", it is checkable directly, and it goes red
     * for the real failure mode — an intermediate wrapping — rather than for a bound being redrawn.
     *
     * ⚠️ Deliberately run at a fixed 10⁶ and not at [targetMassScale]. This case is not a progress
     * meter for the rescale; it is a unit test of the expression, and it should hold at the target
     * unit whether or not the knob has been turned yet.
     *
     * ⚠️ And for that to mean anything the masses have to be stated **in grams**, which is what
     * [inGrams] is for. They are read off the game's own constants, and those are integer counts in
     * whatever unit `Budget` is set to — so with the knob at the target, `mass * scale` was a real
     * ship's mass in micrograms multiplied by a further million. The heaviest grid came to 2.75e20,
     * which is not a number, and the case failed reporting that a velocity had moved. It had not;
     * the fixture had. The expression under test was never wrong, and this is the second time in
     * this plan that the instrument's own units have impersonated a defect in the thing measured.
     */
    @Test
    fun `a velocity does not change when the mass unit does`() {
        val scale = 1_000_000L
        // A spread rather than one pair: a bare fitting, a reference ship, and the heaviest grid
        // that can be built — and both signs, since the reduction shifts negatives too.
        val masses = listOf(1_000L, inGrams(referenceShipMass), inGrams(densestTileMass * gridTiles))
        val speeds = listOf(-designTopSpeed, -1L, 0L, 1L, designTopSpeed)

        for (mass in masses) for (speed in speeds) {
            val atOne = scaledRatio(mass * speed, mass, Flight.PER_TILE)
            assertEquals(
                speed * Flight.PER_TILE,
                atOne,
                "at one gram per unit, $mass g at $speed tiles/tick did not read back as $speed",
            )
            // The same physical ship and the same physical speed, in microgram units.
            val atTarget = scaledRatio(mass * scale * speed, mass * scale, Flight.PER_TILE)
            assertEquals(
                atOne,
                atTarget,
                "a ${mass.commas()} g ship at $speed tiles/tick reads $atOne at one gram per unit " +
                    "but $atTarget at a microgram — the rescale moved a velocity",
            )
        }
    }

    /**
     * The reduction does not cost precision worth having.
     *
     * `scaledRatio` shifts both halves of the fraction down until the scaling cannot overflow, which
     * is a real approximation and deserves a real bound rather than an assurance. The claim in its
     * KDoc is "about one part in 10¹⁰, two orders finer than [Flight.PER_TILE] can express", and this
     * is that claim, measured against the exact answer computed in `Double`.
     */
    @Test
    fun `reducing the fraction stays within a millionth of a tile per tick`() {
        // In grams, then scaled to micrograms by hand — see [inGrams] and the note on the case above.
        val mass = inGrams(referenceShipMass) * 1_000_000L
        var worst = 0.0
        // Awkward ratios on purpose: a momentum that divides evenly cannot expose a rounding.
        for (numerator in listOf(1L, 3L, 7L, 999L, 1_000_003L)) {
            val impulse = mass / numerator * 2L + numerator
            val got = scaledRatio(impulse, mass, Flight.PER_TILE).toDouble()
            val exact = impulse.toDouble() / mass.toDouble() * Flight.PER_TILE
            worst = kotlin.math.max(worst, kotlin.math.abs(got - exact))
        }
        println("scaledRatio worst error at a microgram: ${worst.sig3()} of ${Flight.PER_TILE} per tile")
        assertTrue(
            worst < 1_000.0,
            "the reduction lost ${worst.sig3()} units of ${Flight.PER_TILE}, which is more than a " +
                "millionth of a tile per tick — too coarse to call the ratio scale-invariant",
        )
    }

    /**
     * Step 9 of `PLAN_unit_rescale.md`: what the finer unit actually buys, stated as the one number
     * the whole rescale exists to move.
     *
     * ### The two floors
     *
     * `Negligible` is what a *player* can see: the readouts say nothing below half a percent of a
     * tile of ordinary air. It is written as a fraction of [Stuff.AMBIENT_AIR], so it is a
     * **physical** floor — about six grams of gas, whatever one integer happens to be worth. That is
     * the property this pins, and it is why step 9 needed no numeric change to `Negligible` at all:
     * a floor defined against the thing it is a fraction of follows the unit down on its own.
     *
     * The diffusion stranding floor is not physical. A cell sheds `count * FACE_SHARE / SLOTS` per
     * face, so **fewer than [SLOTS] integers can never move at all**, and that is a fixed number of
     * integers — the one quantity in the system the mass unit improves directly and unconditionally
     * (`NUMERIC_LIMITS.md` §6.2).
     *
     * ### Why the gap is the whole point
     *
     * At one gram per integer the two floors were **the same size**: five stranded grams against a
     * six-gram visible floor. §6.2 is blunt about what that meant — the `Negligible` overlay change
     * was *cosmetic cover for a quantisation artefact*, hiding stranded gas at exactly the scale
     * where it was becoming visible. The rescale is the real fix, and this measures it: the gap is
     * the mass scale itself, so at a microgram the stranding floor sits a millionfold below anything
     * a readout could show.
     *
     * Asserted as a ratio rather than as either floor separately, because either alone would pass
     * while the relationship rotted — and the relationship is the claim.
     */
    @Test
    fun `the stranding floor falls away from the floor a player can see`() {
        val visibleGrams = Negligible.MASS / Budget.GRAM
        assertTrue(
            visibleGrams in 4L..8L,
            "the visible floor should be about six grams of gas whatever the unit is, got " +
                "$visibleGrams g — Negligible has stopped being a fraction of a tile of air",
        )

        // Both floors as a count of integers, which is the only footing they share.
        val gap = Negligible.MASS / SLOTS.toLong()
        assertEquals(
            Budget.GRAM * visibleGrams / SLOTS.toLong(), gap,
            "the gap between the two floors is the mass unit and nothing else",
        )
        println(
            "stranding floor $SLOTS units = ${SLOTS * Budget.MICROGRAMS_PER_UNIT} µg; " +
                "visible floor ${Negligible.MASS} units = $visibleGrams g; gap x${gap.commas()}",
        )
        // At one gram per integer this ratio is about 1 and the cover story of §6.2 applies. The
        // knob is what moves it.
        assertTrue(gap >= 1L, "the visible floor can never sit below the stranding floor")
    }
}
