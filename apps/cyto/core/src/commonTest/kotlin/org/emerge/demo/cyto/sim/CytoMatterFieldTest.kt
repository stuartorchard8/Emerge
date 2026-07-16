package org.emerge.demo.cyto.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Standalone invariants for the dense matter field: conservation through every op, determinism, and the
 *  diffusion law — non-negativity, isotropy, the size schedule, and the recovery curve's two ends (a scar
 *  stays legible for thousands of ticks, then relaxes to a stable floor). */
class CytoMatterFieldTest {
    private val A = SpeciesRegistry.id("r")
    private val AB = SpeciesRegistry.id("rg")

    /** The diffusion tests below each drive ONE species ('r') and repeat a single pass, so they must use a
     *  pass that actually schedules it — only one species moves per pass, and pass 0 belongs to 'b'. */
    private val pA: Long get() = passFor(A)

    private fun occupiedTexels(f: CytoMatterField): Int { var n = 0; f.forEachTexel { _, _, _, _ -> n++ }; return n }
    private fun digest(f: CytoMatterField): String {
        val sb = StringBuilder()
        f.forEachTexel { x, y, sz, s ->
            sb.append(x).append(',').append(y).append(',').append(sz).append(':')
            for (i in 0 until s.size) sb.append(s.idAt(i)).append('=').append(s.countAt(i)).append(',')
            sb.append(';')
        }
        return sb.toString()
    }

    @Test fun seededUniformFillsEveryTexelAndConserves() {
        val f = CytoMatterField.seededUniform(10)
        val res = f.resolution
        assertEquals(res * res, occupiedTexels(f), "a uniform seed reaches every texel")
        // 3 monomers × 10 each, in every texel — one atom apiece.
        assertEquals(3L * 10 * res * res, f.totalAtoms())
    }

    @Test fun footprintCoversTexelsAndConserves() {
        val f = CytoMatterField.seededUniform(10)
        val t0 = f.totalAtoms()
        val n = f.openFootprint(5f, 5f, 0.6f); f.closeFootprint()
        assertTrue(n > 0, "a footprint should cover at least one texel")
        assertEquals(t0, f.totalAtoms(), "opening a footprint must not move atoms")
    }

    @Test fun exchangeConservesAndReturnsDelta() {
        val f = CytoMatterField.seededUniform(10)
        val before = f.totalAtoms()
        val n = f.openFootprint(5f, 5f, 0.6f)
        assertTrue(n > 0)
        val delta = f.balance(A, cEff = 0, scaleFactor = 0f)   // cell wants 0 ⇒ leaves give delta
        f.closeFootprint()
        assertTrue(delta > 0, "cell with cEff=0 absorbs from a rich footprint")
        assertEquals(before - delta.toLong(), f.totalAtoms(), "grid total changes by exactly −delta")
    }

    @Test fun exchangeLeaksWhenCellRicher() {
        val f = CytoMatterField.seededUniform(10)
        val before = f.totalAtoms()
        f.openFootprint(5f, 5f, 0.6f)
        val n = f.openFootprint(5f, 5f, 0.6f)
        val delta = f.balance(A, cEff = 100 * n, scaleFactor = 0f)   // cell much richer than leaves ⇒ delta < 0 (leaks in)
        f.closeFootprint()
        assertTrue(delta < 0, "cell richer than footprint pushes matter in (negative delta)")
        assertEquals(before - delta.toLong(), f.totalAtoms(), "conserved both ways")
    }

    @Test fun depositConserves() {
        val f = CytoMatterField.seededUniform(10)
        val before = f.totalAtoms()
        f.deposit(-20f, 30f, 0.6f, A, amount = 1000)   // monomer ⇒ molecules == atoms
        assertEquals(before + 1000L, f.totalAtoms(), "deposit adds exactly the amount")
    }

    /** With diffusion OFF (den = 0) and no decay due, maintain must not move a single atom — the pre-2026-07-16
     *  inert field, kept as a test because `MATTER_DIFFUSE_DEN = 0` is a supported setting and the whole
     *  diffusion feature must be switchable off to exactly this behaviour. */
    @Test fun maintainLeavesAnUnobservedFieldExactlyAsItWasWhenDiffusionIsOff() {
        val f = CytoMatterField.seededUniform(10)
        f.deposit(0f, 0f, 0.6f, AB, amount = 4096)   // a non-uniform pile to notice any drift
        val t0 = f.totalAtoms()
        val d0 = digest(f)
        repeat(520) { f.maintain(decayPeriod = Int.MAX_VALUE, diffuseDen = 0) }
        assertEquals(t0, f.totalAtoms(), "maintain conserves")
        assertEquals(d0, digest(f), "with diffusion off, an unobserved field must not move a single atom")
    }

    // ── diffusion: the size schedule ─────────────────────────────────────────────────────────────────

    /** EXACTLY ONE column moves per pass — the property the whole schedule exists for. A sweep costs the
     *  same whether or not matter moves, so cost tracks columns-swept; capping that at one is what makes
     *  diffusion negligible. Verified end-to-end by observing which species actually MOVE, not by trusting
     *  the schedule's arithmetic. */
    @Test fun exactlyOneSpeciesDiffusesPerPass() {
        for (p in 0 until 24) {
            val scheduled = CytoMatterField.empty().scheduledSpecies(p.toLong())
            val movers = (0 until SpeciesRegistry.size).filter { movesOnPass(it, p.toLong()) }
            assertEquals(1, movers.size, "pass $p moved ${movers.size} species; want exactly one")
            assertEquals(scheduled, movers[0], "pass $p: scheduledSpecies disagrees with what actually moved")
        }
    }

    /** Even passes are monomers, round-robin in lex order — they carry essentially all of the ecological
     *  recovery, so they get half of every pass. Odd passes belong to polymers. */
    @Test fun monomersTakeEveryOtherPassInRoundRobin() {
        val f = CytoMatterField.empty()
        val expected = listOf("b", "g", "r", "b", "g", "r")
        val actual = (0 until 12 step 2).map { SpeciesRegistry.string(f.scheduledSpecies(it.toLong())) }
        assertEquals(expected, actual, "even passes must cycle b -> g -> r")
        for (p in 1 until 12 step 2) {
            assertTrue(SpeciesRegistry.atomCount(f.scheduledSpecies(p.toLong())) >= 2, "pass $p must be a polymer")
        }
    }

    /** The ruler sequence: length n claims 1/2^n of all passes and no two lengths ever collide. Combined
     *  with the round-robin, a species of length n moves once every `2^n · count(n)` passes — longer chains
     *  are doubly slow (rarer slot, more molecules sharing it), which is the point. */
    @Test fun chainLengthClaimsAnExponentiallyRarerShareOfPasses() {
        val f = CytoMatterField.empty()
        val seen = HashMap<Int, Int>()
        val n = 1 shl 12
        // 0 = an idle slot: the ruler sequence keeps doubling past length 10, but a molecule can hold each
        // of the 9 ordered bonds at most once, so there is nothing there to diffuse.
        for (p in 0 until n) {
            val sp = f.scheduledSpecies(p.toLong())
            seen.merge(if (sp < 0) 0 else SpeciesRegistry.atomCount(sp), 1, Int::plus)
        }
        assertEquals(n / 2, seen.getValue(1), "monomers: half of all passes")
        assertEquals(n / 4, seen.getValue(2), "diatoms: a quarter")
        assertEquals(n / 8, seen.getValue(3), "triatoms: an eighth")
        assertEquals(n / 16, seen.getValue(4), "4-chains: a sixteenth")
        assertEquals(n / 1024, seen.getValue(10), "10-chains: the longest legal molecule still gets its share")
        assertEquals(n / 1024, seen.getValue(0), "idle slots are only the 1/2^11 tail past length 10")
    }

    /** Fairness: every legal molecule of a length gets a slot, so no species is privileged by registry
     *  order. Length 3 has 24 members, NOT 27 — SpeciesRegistry forbids a repeated ordered bond, so `bbb`
     *  is not a molecule. */
    @Test fun everyLegalMoleculeOfALengthEventuallyGetsASlot() {
        val f = CytoMatterField.empty()
        for (len in 1..3) {
            val all = (0 until SpeciesRegistry.size).filter { SpeciesRegistry.atomCount(it) == len }.toSet()
            val period = (1 shl len) * all.size
            val hit = (0 until period * 2).map { f.scheduledSpecies(it.toLong()) }
                .filter { it >= 0 && SpeciesRegistry.atomCount(it) == len }.toSet()
            assertEquals(all, hit, "every length-$len molecule must get a slot within its period")
        }
        assertEquals(3, (0 until SpeciesRegistry.size).count { SpeciesRegistry.atomCount(it) == 1 })
        assertEquals(9, (0 until SpeciesRegistry.size).count { SpeciesRegistry.atomCount(it) == 2 })
        assertEquals(24, (0 until SpeciesRegistry.size).count { SpeciesRegistry.atomCount(it) == 3 })
    }

    /** An absent species SPENDS its slot rather than handing it on — the schedule stays a pure function of
     *  `pass`, so a decay that allocates a new column mid-run cannot shift everything else's phase. */
    @Test fun anAbsentSpeciesSpendsItsSlot() {
        val f = CytoMatterField.empty()                  // no columns at all
        val rg = SpeciesRegistry.id("rg")
        val p = (0L until 200L).first { f.scheduledSpecies(it) == rg }
        f.deposit(0f, 0f, 0.3f, A, 1_000)                // only 'r' exists
        val before = digest(f)
        f.diffuse(den = 8, pass = p)                     // 'rg' slot, but 'rg' has no column
        assertEquals(before, digest(f), "an absent species' slot must be a no-op, not a fallback to another")
    }

    /** Seed a lone spike of [sp] in a void and report whether one diffusion pass moved any of it. */
    private fun movesOnPass(sp: Int, pass: Long): Boolean {
        val f = CytoMatterField.empty()
        f.deposit(0f, 0f, 0.3f, sp, 1)
        val col = f.columnOrNull(sp)!!
        col.fill(0)
        val i = f.texelIndex(0f, 0f)
        col[i] = 1_000_000
        f.diffuse(den = 8, pass = pass)
        return col[i] != 1_000_000
    }

    /** The first pass that schedules [sp] — the diffusion tests below each drive a single species, so they
     *  pin that species' pass rather than assuming pass 0 belongs to it (it belongs to `b`). */
    private fun passFor(sp: Int): Long =
        (0L until 100_000L).first { CytoMatterField.empty().scheduledSpecies(it) == sp }

    // ── diffusion ────────────────────────────────────────────────────────────────────────────────────

    /** A uniform field has zero gradient ⇒ zero flux ⇒ diffusion is a bit-exact no-op. This is the property
     *  that makes skipping settled regions EXACT rather than an approximation, so it guards any future
     *  active-tile optimisation as much as it guards the pass itself. */
    @Test fun diffusingAUniformFieldIsABitExactNoOp() {
        val f = CytoMatterField.seededUniform(125)
        val d0 = digest(f)
        repeat(50) { f.diffuse(den = 8, pass = pA) }
        assertEquals(d0, digest(f), "a uniform field must not move under diffusion, ever")
    }

    @Test fun diffusionConservesThroughASpike() {
        val f = CytoMatterField.empty()
        f.deposit(0f, 0f, 0.6f, A, amount = 1_000_000)
        val t0 = f.totalAtoms()
        repeat(200) { f.diffuse(den = 8, pass = pA) }
        assertEquals(t0, f.totalAtoms(), "diffusion is conservation-exact by construction")
    }

    @Test fun diffusionConservesOnTopOfASeededGradient() {
        val f = CytoMatterField.seededUniform(125)
        f.deposit(10f, -10f, 0.9f, A, amount = 500_000)
        f.deposit(-4f, 6f, 0.5f, AB, amount = 80_000)
        val t0 = f.totalAtoms()
        repeat(100) { f.diffuse(den = 8, pass = pA) }
        assertEquals(t0, f.totalAtoms(), "conserved with several species and a non-trivial gradient")
    }

    /** The adversarial case for non-negativity: a texel surrounded by empty neighbours gives on every edge
     *  at once. Within one sweep it gives on at most 2, and only when it holds ≥ 2 — that bound, not a
     *  clamp, is what keeps counts non-negative (clamping up to zero would silently destroy matter). Run at
     *  `den = 2`, the tightest legal divisor, where the unit-flux term and the quotient term both bite. */
    @Test fun diffusionNeverDrivesATexelNegative() {
        val f = CytoMatterField.empty()
        f.deposit(0f, 0f, 0.3f, A, 1)
        val col = f.columnOrNull(A)!!
        col.fill(0)
        col[f.texelIndex(0f, 0f)] = Int.MAX_VALUE / 8      // a lone spike in a void, at extreme magnitude
        // Adjacent tiny counts: the states that make the unit-flux term dangerous (a texel holding 2 with
        // empty neighbours is the exact case a simultaneous 4-neighbour update would drive to -2).
        col[f.texelIndex(4f, 4f)] = 2
        col[f.texelIndex(-4f, -4f)] = 1
        val t0 = f.totalAtoms()
        repeat(300) { f.diffuse(den = 2, pass = pA) }
        for (i in col.indices) assertTrue(col[i] >= 0, "texel $i went negative: ${col[i]}")
        assertEquals(t0, f.totalAtoms(), "still conserved at the non-negativity boundary")
    }

    @Test fun diffusionRejectsADivisorThatCouldGoNegative() {
        val f = CytoMatterField.seededUniform(10)
        assertFailsWith<IllegalArgumentException> { f.diffuse(den = 1, pass = pA) }
    }

    /** A point source must spread equally in ±x and ±y. Catches the `shr`-vs-`/` sign bias (which favours
     *  one subtraction order) and any wrap/seam bug in the edge sweep. The H and V sweeps run in sequence
     *  rather than simultaneously, so the two axes are near- but not bit-identical; each axis' own mirror
     *  symmetry is exact and is what this pins. */
    @Test fun diffusionIsSymmetricAboutAPointSource() {
        val f = CytoMatterField.empty()
        f.deposit(0f, 0f, 0.3f, A, 1)
        val col = f.columnOrNull(A)!!
        col.fill(0)
        val res = f.resolution
        val cx = res / 2; val cy = res / 2
        col[cy * res + cx] = 4_000_000
        repeat(60) { f.diffuse(den = 8, pass = pA) }
        for (r in 1..6) {
            val right = col[cy * res + cx + r]; val left = col[cy * res + cx - r]
            val down = col[(cy + r) * res + cx]; val up = col[(cy - r) * res + cx]
            assertTrue(right > 0, "the source must actually have spread by radius $r")
            assertEquals(right, left, "x-symmetry broken at radius $r")
            assertEquals(down, up, "y-symmetry broken at radius $r")
            // Cross-axis: the operator split allows a small difference, but not a directional bias.
            assertTrue(right in (down * 3 / 4)..(down * 4 / 3), "x/y anisotropy at radius $r: $right vs $down")
        }
    }

    /** Diffusion must TERMINATE, or the "settled ground is free" property that keeps it negligible in the
     *  tick budget evaporates. Equilibrium here means genuinely flat (every edge below the unit-flux
     *  threshold), NOT the frozen staircase that plain ⌊Δ/den⌋ converges to — see [diffuse]. */
    @Test fun diffusionSettlesAndThenProducesExactlyZeroFurtherFlux() {
        val f = CytoMatterField.seededUniform(125)
        val col = f.columnOrNull(A)!!
        for (i in 0 until 40) col[f.texelIndex(-2f + i * 0.05f, 3f)] = 0   // gouge a crater
        repeat(3_000) { f.diffuse(den = 8, pass = pA) }                    // settles by ~800; 3k is slack
        val settled = digest(f)
        repeat(500) { f.diffuse(den = 8, pass = pA) }
        assertEquals(settled, digest(f), "a settled field must produce exactly zero further flux")
    }

    @Test fun diffusionIsDeterministic() {
        fun run(): String {
            val f = CytoMatterField.seededUniform(125)
            f.deposit(2f, -7f, 0.7f, A, 300_000)
            f.deposit(-9f, 1f, 0.4f, AB, 50_000)
            repeat(240) { p -> f.maintain(decayPeriod = 2000, diffuseDen = 8, pass = p.toLong()) }
            return digest(f)
        }
        assertEquals(run(), run(), "same op sequence ⇒ same field")
    }

    /** The purpose (`PLAN_diffusion.md` §2b): depleted ground must become habitable again. This is the test
     *  that plain ⌊Δ/den⌋ CANNOT pass — it freezes a 21-wide crater at 0/125 forever at den=8 — and it is
     *  why [CytoMatterField.diffuse] carries a unit-flux term. A WIDE crater is the case that matters: the
     *  stalled staircase's depth scales with width, so a narrow one recovers either way and would have
     *  hidden the defect.
     *
     *  Recovery is to ~69% of seed, NOT to full equilibrium, and the upper bound is asserted as tightly as
     *  the lower one — a slope-1 staircase survives by design, because the only way to erase it is a flux
     *  threshold of 1, which would let adjacent texels swap a unit forever and never settle. Termination is
     *  worth more than the last 30%: it is what keeps a relaxed world cheap. If this figure ever moves,
     *  something changed the flux law — re-measure, don't just re-baseline the number. */
    @Test fun diffusionRefillsAWideCraterToAStableFloor() {
        val f = CytoMatterField.seededUniform(125)
        val col = f.columnOrNull(A)!!
        val res = f.resolution
        val c = res / 2
        for (dy in -10..10) for (dx in -10..10) col[(c + dy) * res + (c + dx)] = 0
        val t0 = f.totalAtoms()
        repeat(3_000) { f.diffuse(den = 8, pass = pA) }
        val centre = col[c * res + c]
        assertTrue(centre in 80..95, "a 21-wide crater must recover to its ~86/125 floor, was $centre")
        assertEquals(t0, f.totalAtoms(), "conserved across the whole recovery")
    }

    /** Biological timescale keeps the scar, geological erases it. Pins the SHAPE of the recovery curve, not
     *  just its endpoint: a fresh scar must still read as a scar after a few thousand ticks of maintenance
     *  passes, or diffusion has flattened the world's memory of where life has been. */
    @Test fun aFreshCraterIsStillLegibleAfterAFewThousandTicks() {
        val f = CytoMatterField.seededUniform(125)
        val col = f.columnOrNull(A)!!
        val res = f.resolution
        val c = res / 2
        for (dy in -10..10) for (dx in -10..10) col[(c + dy) * res + (c + dx)] = 0
        // ~5000 ticks of world time at the MATTER_MAINTAIN_PERIOD = 128 cadence.
        repeat(5000 / 128) { f.diffuse(den = 8, pass = pA) }
        assertTrue(col[c * res + c] < 40, "the scar must still be obvious, was ${col[c * res + c]}")
    }

    @Test fun decayConservesAndAtomises() {
        val f = CytoMatterField.empty()
        f.deposit(0f, 0f, 0.6f, AB, amount = 4096)   // a pile of 'rg' molecules
        val t0 = f.totalAtoms()
        repeat(20) { f.maintain(decayPeriod = 2) }
        assertEquals(t0, f.totalAtoms(), "decay conserves atoms (rg → r + g)")
        var rg = 0L; var mono = 0L
        f.forEachTexel { _, _, _, s -> rg += s.count(AB).toLong(); mono += s.count(A).toLong() }
        assertTrue(rg < 4096, "some 'rg' atomised")
        assertTrue(mono > 0, "monomers released")
    }

    @Test fun deterministic() {
        fun run(): CytoMatterField {
            val f = CytoMatterField.seededUniform(10)
            f.openFootprint(3f, 3f, 0.6f); f.balance(A, 4, 0f); f.closeFootprint()
            f.deposit(3f, 3f, 0.6f, AB, 500)
            f.openFootprint(-40f, 80f, 0.6f); f.balance(A, 0, 0f); f.closeFootprint()  // near a different tile
            f.maintain(4)
            return f
        }
        assertEquals(digest(run()), digest(run()), "identical op sequences produce identical fields")
    }

    /** [CytoMatterField.tallyChannels] — what the overlay colours itself from — must agree with an
     *  independent tally of the columns, on a fresh field and after any number of maintain passes. A drift
     *  here silently miscolours the overlay. */
    private fun channelDigest(f: CytoMatterField): String {
        val n = f.resolution * f.resolution
        val chR = IntArray(n); val chG = IntArray(n); val chB = IntArray(n)
        f.tallyChannels(chR, chG, chB)
        val sb = StringBuilder()
        for (i in 0 until n) {
            val r = chR[i]; val g = chG[i]; val b = chB[i]
            if (r == 0 && g == 0 && b == 0) continue
            sb.append(i).append(':').append(r).append('/').append(g).append('/').append(b).append(';')
        }
        return sb.toString()
    }

    /** The same digest, computed independently by walking the texels and tallying each one's contents. */
    private fun walkDigest(f: CytoMatterField): String {
        val sb = StringBuilder()
        val t = CytoMatterField.SPAN / f.resolution
        f.forEachTexel { x, y, _, store ->
            var r = 0L; var g = 0L; var b = 0L
            for (i in 0 until store.size) {
                val c = store.countAt(i); val id = store.idAt(i)
                r += c * SpeciesRegistry.atomsInChannel(id, 0)
                g += c * SpeciesRegistry.atomsInChannel(id, 1)
                b += c * SpeciesRegistry.atomsInChannel(id, 2)
            }
            if (r == 0L && g == 0L && b == 0L) return@forEachTexel
            val ix = ((x + CytoMatterField.HALF) / t).toInt()
            val iy = ((y + CytoMatterField.HALF) / t).toInt()
            sb.append(iy * f.resolution + ix).append(':')
            sb.append(r).append('/').append(g).append('/').append(b).append(';')
        }
        return sb.toString()
    }

    @Test fun channelTallyMatchesColumnsOnAFreshField() {
        val f = CytoMatterField.seededUniform(10)
        assertEquals(walkDigest(f), channelDigest(f), "channels must tally on a pre-tick field")
    }

    @Test fun channelTallyTracksMaintainThroughDecay() {
        val f = CytoMatterField.seededUniform(10)
        f.deposit(0f, 0f, 0.3f, AB, 500)
        // Decay rewrites the columns every pass; check the tally follows against an independent walk.
        repeat(64) {
            f.maintain(decayPeriod = 4)
            assertEquals(walkDigest(f), channelDigest(f), "channels drifted from the columns on pass $it")
        }
        var rg = 0L
        f.forEachTexel { _, _, _, s -> rg += s.count(AB).toLong() }
        assertTrue(rg < 500, "decay should have atomised some 'rg' (so the read model tracked real churn)")
    }
}
