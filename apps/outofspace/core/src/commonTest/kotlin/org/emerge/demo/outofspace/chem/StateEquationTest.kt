package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.world.VolumeField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does a phase transition actually fall out of the equation of state, in integers, at this scale?
 *
 * That is the only question this file asks, and it is worth asking on its own before anything is
 * plumbed into the solver, because everything downstream — boiling, condensation, a liquid holding
 * its own density — is built on the answer being yes. If the fixed-point arithmetic cannot resolve
 * the falling stretch of the isotherm, there is no phase transition to plumb in and the whole
 * approach is dead here rather than three increments later.
 *
 * Nothing below pins a literal from a run. Van der Waals in reduced form has exact analytic
 * answers at the critical point, so those are the oracle, and the qualitative claims (a wiggle
 * appears below the critical temperature, and only below it) are counted off the curve rather than
 * eyeballed.
 */
class StateEquationTest {

    /** Reduced temperatures below, at, and above critical — the three regimes the curve has. */
    private val cold = SCALE * 9 / 10
    private val critical = SCALE
    private val hot = SCALE * 11 / 10

    /** A whole tile — the volume every sweep here uses. */
    private val full = VolumeField.FULL

    @Test
    fun `the critical point is where the equation says it is`() {
        // `Pr(1, 1) = 1` by construction of the reduced form: at Tr = 1 the α term is exactly 1, and
        // the coefficients are built so that repulsion minus attraction is unity there.
        //
        // ⚠️ **A few parts in a hundred million, not exact, and the difference is the point.** Under
        // van der Waals this was exact — the coefficients were the integers 8 and 3 — and the
        // comment here said that anything else meant the fixed point was losing the units.
        // Peng-Robinson's are `1/Zc`, `Ωb/Zc` and `Ωa/Zc²`, which are not rationals with small
        // denominators and are carried as rounded decimals in [SCALE]. The residue below is that
        // rounding and nothing else; a unit error would show up as a factor, not as 3 parts in 1e8.
        val atCritical = reducedPressure(densityR = SCALE, temperatureR = critical, Species.Water)
        assertTrue(
            kotlin.math.abs(atCritical - SCALE) < 100L,
            "the critical point should land on SCALE; got $atCritical",
        )
    }

    @Test
    fun `stiffness at critical density is the repulsion and the attraction cancelling`() {
        // dPr/dρr at ρr = 1 works out as `C·(Tr − α(Tr))` with `C = (1/Zc)/(1 − b)²`, the two
        // coefficients being equal by construction — that equality *is* the inflection that defines
        // the critical point. Zero there; the sign either side is the presence or absence of a phase
        // transition, so it is the single most load-bearing number in the model.
        //
        // ⚠️ It was `6·(Tr − 1)` under van der Waals, and the shape of the change is worth seeing:
        // the distance is measured from `α(Tr)` now rather than from a bare 1, and α is where the
        // acentric factor lives. A law with no third constant had nothing to put there, so it
        // measured from 1 and handed every fluid in the game the same answer.
        val c = (INV_ZC.toDouble() / SCALE) / (1.0 - COVOLUME.toDouble() / SCALE).let { it * it }
        val kappa = CRITICAL[Species.Water]!!.kappa.toDouble() / SCALE
        for (tr in listOf(cold, critical, hot)) {
            val trd = tr.toDouble() / SCALE
            val root = 1.0 + kappa * (1.0 - kotlin.math.sqrt(trd))
            val alpha = if (root <= 0.0) 0.0 else root * root
            val expected = (c * (trd - alpha) * SCALE).toLong()
            val actual = rawStiffness(SCALE, tr)
            assertTrue(
                (actual - expected) in -tolerance..tolerance,
                "at Tr=$tr expected slope 6·(Tr−1)=$expected, measured $actual",
            )
        }
    }

    @Test
    fun `below the critical temperature the isotherm falls somewhere in the middle`() {
        val falling = fallingBand(cold)
        assertTrue(falling != null, "a cold isotherm must have an unstable stretch; found none")

        // The unstable band has to be in the middle of the range, with a stable branch either side:
        // a sparse one that is the vapour and a dense one that is the liquid. A band running off
        // either end would mean one of the two phases does not exist.
        val (from, to) = falling
        assertTrue(from > sweepFrom, "the unstable band must have a vapour branch below it, started at $from")
        assertTrue(to < sweepTo, "the unstable band must have a liquid branch above it, ended at $to")
    }

    @Test
    fun `above the critical temperature there is no phase transition at all`() {
        // Not "a smaller wiggle" — none. Above critical, liquid and gas stop being different
        // things, and the curve is monotonic everywhere. Nothing tells the equation to do this.
        assertEquals(null, fallingBand(hot), "a hot isotherm must be monotonic")
    }

    @Test
    fun `the colder it gets the further apart the two phases sit`() {
        // The width of the unstable band is the density gap between liquid and vapour, and that gap
        // widening as temperature drops is what a latent heat is. It is not configured anywhere;
        // it is the shape of the curve.
        val widths = listOf(SCALE * 5 / 10, SCALE * 7 / 10, SCALE * 9 / 10).map { tr ->
            val band = fallingBand(tr) ?: error("expected an unstable band at Tr=$tr")
            band.second - band.first
        }
        assertEquals(widths.sortedDescending(), widths, "the gap must close as the critical point is approached")
    }

    @Test
    fun `the curve is the same one for every species`() {
        // The law of corresponding states, which is the whole reason this is affordable: species
        // differ only in where their critical point sits, never in the shape of the curve. Water
        // and nitrogen are the same equation. So there is no per-species phase behaviour to author,
        // and no way for one fluid to be tuned into a transition another cannot have.
        for (species in CRITICAL.keys) {
            val c = CRITICAL.getValue(species)
            assertTrue(c.kelvin > 0, "$species needs a real critical temperature")
            assertTrue(c.massPerTile > 0, "$species needs a real critical density")
        }
        assertTrue(Species.Water in CRITICAL && Species.Nitrogen in CRITICAL)
    }

    @Test
    fun `a tile's pressure never falls as mass is crammed into it, right through the packing wall`() {
        // Step 5 of PLAN_unit_rescale.md, and the assertion NUMERIC_LIMITS.md §6.1's measurements
        // should have been. The symptom being pinned is not an exception — it is a pressure that
        // CHANGED SIGN and oscillated as mass went up, because `8·Tr·ρr/(3 − ρr)` reached ~1e17 at
        // a gap of one and `c.pressure × that` wrapped a Long for three species out of four:
        //
        //     801,780 g water →  +45,280,586,281
        //     850,000 g       →  -68,401,856,743
        //     900,000 g       →  +91,503,964,939
        //
        // Reachable in ~72 ticks of the debug water tool on one tile. Swept in GRAMS rather than in
        // reduced density on purpose: mass are what a caller actually controls, and the old bug was
        // reachable only by going past the density the callers were supposed to be held below.
        for (species in CRITICAL.keys) {
            val critical = CRITICAL[species]!!
            for (kelvin in listOf(80, 293, 700, 3000)) {
                var previous = Long.MIN_VALUE
                // From empty to four times close packing — well past anything the volume clamps
                // permit, because "the caller is supposed to prevent this" is what failed before.
                val step = critical.massPerTile / 64
                var mass = 0L
                while (mass <= critical.massPerTile * 12) {
                    val pressure = partialPressure(mass, species, kelvin, full, full)!!
                    assertTrue(
                        pressure >= previous,
                        "$species at $kelvin K: pressure fell at $mass g, $previous -> $pressure",
                    )
                    previous = pressure
                    mass += step
                }
                assertTrue(previous > 0L, "$species at $kelvin K ended at a non-positive $previous")
            }
        }
    }

    @Test
    fun `clamping the wall left every representable density untouched`() {
        // The compatibility claim: wherever the old form produced a REPRESENTABLE answer, the new
        // one produces bit-for-bit the same number. Not an approximation — scaledRatio splits
        // `8·Tr·ρr / gap` into a whole part and a remainder, which is an identity.
        //
        // ⚠️ "Representable" is the pressure ceiling, not the density ceiling, and the two are not
        // the same line. At a high enough reduced temperature MAX_REDUCED_PRESSURE bites while the
        // density is still well short of MAX_REDUCED_DENSITY — measured, at Tr=4 and ρr=2.95. That
        // is the clamp doing its job rather than a discrepancy, so the oracle is only consulted
        // where it has an answer worth having.
        // ⚠️ **The oracle is floating point now, and deliberately.** Under van der Waals it was the
        // integer expression rearranged, which made this an exactness check on one rewrite. The
        // Peng-Robinson expression is long enough that restating it in Longs here would be copying
        // the implementation rather than checking it, so the curve is evaluated independently in
        // doubles and the two must agree to a part in a million. That is the stronger statement: it
        // says the fixed-point curve tracks the real one, not that two spellings of the same integer
        // arithmetic agree with each other.
        val zc = 0.3074013
        val bb = 0.07779607390 / zc
        val aa = 0.45723552894 / (zc * zc)
        val kappa = CRITICAL[Species.Water]!!.kappa.toDouble() / SCALE
        var compared = 0
        for (tr in listOf(cold, critical, hot, SCALE * 4)) {
            val trd = tr.toDouble() / SCALE
            val root = 1.0 + kappa * (1.0 - kotlin.math.sqrt(trd))
            val alpha = if (root <= 0.0) 0.0 else root * root
            var density = 0L
            while (density < MAX_REDUCED_DENSITY) {
                val rho = density.toDouble() / SCALE
                val expected =
                    (trd / zc) * rho / (1 - bb * rho) -
                        aa * alpha * rho * rho / (1 + 2 * bb * rho - bb * bb * rho * rho)
                if (expected * SCALE <= MAX_REDUCED_PRESSURE) {
                    val actual = pengRobinsonPressure(density, tr, Species.Water)
                    // A part in a hundred thousand. The integer curve divides four times on its way
                    // to an answer and each division floors, so the residue accumulates with the
                    // size of the terms rather than staying at one unit — measured at 1.1 parts per
                    // million against the double curve at the densest end of the sweep.
                    val slack = maxOf(200.0, kotlin.math.abs(expected) * SCALE / 100_000.0)
                    assertTrue(
                        kotlin.math.abs(actual - expected * SCALE) <= slack,
                        "Tr=$tr, rho=$density: $actual against ${expected * SCALE}",
                    )
                    compared++
                }
                density += SCALE / 64
            }
        }
        // Guards the guard: if the ceiling were ever set low enough to swallow the ordinary curve,
        // the sweep above would pass by comparing almost nothing.
        assertTrue(compared > 700, "only $compared densities were actually compared")
    }

    @Test
    fun `packing the cell solid is refused rather than answered`() {
        // Past close packing there is no pressure to report, only a division by a collapsing gap.
        // The solver has to be held off it, so the boundary is a thrown error and not a clamp.
        val threw = runCatching { reducedPressure(CLOSE_PACKED, critical, Species.Water) }.isFailure
        assertTrue(threw, "close packing must be refused")
        assertTrue(runCatching { reducedPressure(CLOSE_PACKED - 1, critical, Species.Water) }.isSuccess)
    }

    // ── helpers ──

    /** Slope measurement is a finite difference, so allow it a step's worth of rounding. */
    private val tolerance = SCALE / 50

    private val sweepFrom = SCALE / 20
    private val sweepTo = CLOSE_PACKED - SCALE / 20
    private val sweepStep = SCALE / 100

    /**
     * `dPr/drho` measured off [vanDerWaalsPressure] — the *uncorrected* curve.
     *
     * These tests are about the bare equation, and deliberately so. Its falling stretch is the
     * defect that motivates the whole Maxwell construction, so the tests that pin the defect have to
     * look at the curve that still has it; measured against [reducedPressure] they would all pass
     * trivially and prove nothing, because removing exactly this is what that function is for.
     * `SaturationTest` covers the other side — that the corrected curve never falls.
     */
    private fun rawStiffness(densityR: Long, temperatureR: Long, step: Long = SCALE / 1000L): Long {
        val low = (densityR - step).coerceAtLeast(0L)
        val high = (densityR + step).coerceAtMost(CLOSE_PACKED - 1)
        return (pengRobinsonPressure(high, temperatureR, Species.Water) - pengRobinsonPressure(low, temperatureR, Species.Water)) *
            SCALE / (high - low)
    }

    /**
     * The stretch of the isotherm where pressure *falls* as density rises, or null if there is no
     * such stretch. Walked off the real curve rather than solved for, so what it reports is what
     * the integer implementation actually does.
     */
    private fun fallingBand(temperatureR: Long): Pair<Long, Long>? {
        var from: Long? = null
        var to = 0L
        var d = sweepFrom
        while (d <= sweepTo) {
            if (rawStiffness(d, temperatureR) < 0) {
                if (from == null) from = d
                to = d
            }
            d += sweepStep
        }
        return from?.let { it to to }
    }
}
