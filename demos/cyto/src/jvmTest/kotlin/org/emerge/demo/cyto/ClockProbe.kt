package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.totalBiomassBonds
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test

/**
 * Throwaway diagnostic for the hand-authored CLOCK genome (cyto-genome-simple-clock.gene): spawn ONE cell
 * with abundant fuel + monomers (so the clock logic, not light placement, is what's tested), run N ticks
 * (mutation OFF), and record the three clock molecules (bb/ba/bc) + radius each tick. Reports, per variant:
 * whether the clock SELF-STARTS (chems rise from 0), its oscillation range, cycle count/period (bb peaks),
 * radius amplitude (= is it contracting), and alive-at-end. Runs the baseline + every single-gene ablation
 * + a few merge candidates in one pass → /tmp/clockprobe.txt. Not a gated test.
 *   -Dclockticks=2500
 */
class ClockProbe {
    private val baseline = listOf(
        "Break aa : Biomass < 2400 : Convert aa @15",        // 1 metabolism: aa -> biomass
        "Light : Conc(a) > 0 : FormBond a a",                // 2 metabolism: light -> aa (fuel)
        "Break aa : bb < 220 & bc < 200 : FormBond b b @10", // 3 clock START+maintain (bb producer from fuel)
        "Break bc : bb < bc & bb < 200 : FormBond b a @15",  // 4 cycle: bc -> ba
        "Break ba : bc < ba & bc < 200 : FormBond b b @15",  // 5 cycle: ba -> bb
        "Break bb : ba < bb & ba < 200 : FormBond b c @15",  // 6 cycle: bb -> bc
        "Break bb : ba < bb & ba < 200 : Contract @15",      // 7 consume bb + contract
    )
    private val labels = listOf("metab:convert", "metab:fuel", "start", "cyc bc->ba", "cyc ba->bb", "cyc bb->bc", "contract")

    /** The hand-tuned 6-gene METABOLIC clock (cyto-genome-metabolic-clock.gene): fuses metabolism into the
     *  clock — there is no separate `aa` fuel currency. `bb` is the oscillator phase species, the energy store
     *  (broken to drive the ring + contraction), AND the biomass precursor (Convert bb). The bootstrap
     *  producer (#2) burns Light directly into `bb`, so the 7-gene version's Light->aa fuel gene is gone.
     *  Self-starts + sustains (110 cycles / 15k ticks) — a real 7->6 reduction. */
    private val metabolic = listOf(
        "Break bb : ba < bb & ba < 1984 & Biomass < 4000 : Convert bb @15",  // grow biomass from bb (no aa)
        "Light : bb < 8000 & bc < 1984 : FormBond b b",                      // bootstrap producer: Light -> bb
        "Break bc : bb < bc & bb < 1984 : FormBond b a @6",                  // ring: bc -> ba
        "Break ba : bc < ba & bc < 1984 : FormBond b b @6",                  // ring: ba -> bb
        "Break bb : ba < bb & ba < 1984 : FormBond b c @6",                  // ring: bb -> bc
        "Break bb : ba < bb & ba < 1984 & bb > 6000 : Contract @15",         // consume bb + contract (high phase)
    )

    /** The simplified 6-gene clock: a 2-molecule ring bb<->bc (the third molecule ba is gone), with the
     *  start-gate cutoff [cut] as the period knob. */
    private fun ring2(cut: Int) = listOf(
        baseline[0], baseline[1],
        "Break aa : bb < $cut & bc < 200 : FormBond b b @10",  // start: seed bb from fuel (cutoff = period)
        "Break bb : bc < bb & bc < 200 : FormBond b c @15",    // bb -> bc
        "Break bc : bb < bc & bb < 200 : FormBond b b @15",    // bc -> bb
        "Break bb : bc < bb & bc < 200 : Contract @15",        // contract in the bb->bc phase
    )

    /** On-demand only (skipped in the normal suite). Run with:
     *    ./gradlew :demos:cyto:jvmTest --tests "*ClockProbe*" -Dclockprobe=1 [-Dclockmode=ablate] [-Dclockticks=8000]
     *  Default mode validates candidate simplifications vs the baseline; `ablate` drops each gene in turn. */
    @Test
    fun run() {
        if (System.getProperty("clockprobe") == null) return   // gate: don't slow the normal jvmTest run
        val ticks = System.getProperty("clockticks")?.toIntOrNull() ?: 8000
        val variants = LinkedHashMap<String, List<String>>()
        // -Dclockgenome=<file>: probe a single genome from a .gene file (vs the built-in comparison set).
        System.getProperty("clockgenome")?.let { path ->
            val genes = java.io.File(path).readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
            variants["file:${java.io.File(path).name}"] = genes
            runVariants(variants, ticks); return
        }
        if (System.getProperty("clockmode") == "ablate") {
            variants["baseline(7)"] = baseline
            for (i in baseline.indices) variants["drop#${i + 1} ${labels[i]}"] = baseline.filterIndexed { j, _ -> j != i }
            runVariants(variants, ticks); return
        }
        // Built-in reference set: two known-good clocks (the 7-gene baseline + the 6-gene metabolic clock)
        // and one known-BAD (the 2-ring, which self-starts but deadlocks past ~4k ticks — kept as a reminder
        // to judge clocks over a LONG run).
        variants["baseline 3-ring(7) [good]"] = baseline
        variants["metabolic-clock(6) [good]"] = metabolic
        variants["2-ring(6) [BAD: locks up >~4k]"] = ring2(220)
        runVariants(variants, ticks)
    }

    // Species whose counts are reported (debugging); the rhythm itself is measured on RADIUS (the actuator
    // output) so it's topology-agnostic. Override with -Dclockwatch=bb,bc,bbc.
    private val watch = (System.getProperty("clockwatch") ?: "bb,ba,bc").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    // Seeding is configurable because monomer-in-the-loop clocks are sensitive to it: a rich environment
    // passively pins a `canHold` monomer phase near the ambient level (uptake), which can wedge the ring.
    // -Dclockseed=a:500,b:500  (cytoplasm)   -Dclockenv=400  (per-monomer reservoir level; -1 = the default).
    private val seedCyto: Map<String, Int> = System.getProperty("clockseed")
        ?.split(",")?.associate { it.substringBefore(":").trim() to it.substringAfter(":").trim().toInt() }
        ?: mapOf("a" to 2000, "b" to 2000, "c" to 500, "aa" to 4000)
    private val envLevel: Int = System.getProperty("clockenv")?.toIntOrNull() ?: -1

    private fun runVariants(variants: LinkedHashMap<String, List<String>>, ticks: Int) {
        val sb = StringBuilder()
        sb.appendLine("=== clock probe ($ticks ticks, single lit cell, mutation OFF) ===")
        sb.appendLine("rhythm measured on RADIUS (the contraction output). self-start = watched chems start 0 and rise.")
        sb.appendLine("AUTONOMOUS = period well under the ${'$'}LIGHT_ORBIT (3600) light day. works = self-start & cycles>=3 & autonomous & alive.")
        sb.appendLine("watch=$watch\n")
        sb.appendLine("variant\tgenes\tselfStart\t${watch.joinToString("\t")}\tradCycles\tperiod\tautonomous\tradΔ\tbiomass\talive\tWORKS\tradius sparkline")
        for ((name, genes) in variants) {
            val r = simulate(GeneCodec.parse(genes.joinToString("\n")), ticks)
            val autonomous = r.period in 1..1800   // oscillates >=2x faster than the 3600-tick light day
            val works = r.selfStart && r.cycles >= 3 && autonomous && r.alive
            sb.appendLine("$name\t${genes.size}\t${r.selfStart}\t${r.watchStr}\t${r.cycles}\t${r.period}\t$autonomous\t${r.radAmp}\t${r.bio}\t${r.alive}\t${if (works) "yes" else "NO"}\t${r.spark}")
        }
        java.io.File("/tmp/clockprobe.txt").writeText(sb.toString())
        println(sb)
    }

    private class Res(
        val selfStart: Boolean, val watchStr: String,
        val cycles: Int, val period: Int, val radAmp: Long, val bio: String, val alive: Boolean, val spark: String,
    )

    private fun simulate(genome: List<Gene>, ticks: Int): Res {
        val initial = run {
            val b = SimBuilder(SimState(randomSeed = 0x9E3779B97F4A7C15uL.toLong()))
            b.spawnCell(
                pos = CytoUnits.coord2(0f, 0f), vel = Coord2.zero, type = CellType.Collector,
                cytoplasm = seedCyto,
                biomass = CytoSeed.STARTER_BIOMASS, logicalRadius = MIN_RADIUS, genome = genome,
            )
            val grid = CytoMatterField.seededUniform(if (envLevel >= 0) envLevel else 4000)
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(grid) }
            b.build()
        }
        val soa = CytoSoaReducer(CytoConfig(mutationRateDenom = 0))
        var w = CytoWorld.fromSimState(initial)
        val series = watch.associateWith { IntArray(ticks + 1) }
        val rad = LongArray(ticks + 1); val bio = IntArray(ticks + 1); val alive = BooleanArray(ticks + 1)
        fun sample(t: Int, s: SimState) {
            val cells = s.components.getTable<CytoCellComponent>().asMap().values
            val c = cells.firstOrNull() ?: return
            alive[t] = true
            for (sp in watch) series.getValue(sp)[t] = c.cytoplasm[sp] ?: 0
            rad[t] = c.logicalRadius.raw; bio[t] = totalBiomassBonds(c.biomass)
        }
        sample(0, initial)
        for (t in 1..ticks) { w = soa.tick(w, CytoInput.EMPTY); sample(t, w.toSimState()) }

        val lo = ticks / 2   // measure only the SECOND HALF — excludes startup transients AND catches lock-ups
                             // (a clock that wobbles early then freezes reads as flat here, unlike a ticks/4 window)
        fun rangeStr(a: IntArray): String {
            var mn = Int.MAX_VALUE; var mx = Int.MIN_VALUE
            for (t in lo..ticks) if (alive[t]) { mn = minOf(mn, a[t]); mx = maxOf(mx, a[t]) }
            return if (mx == Int.MIN_VALUE) "dead" else "$mn..$mx"
        }
        val watchStr = watch.joinToString("\t") { rangeStr(series.getValue(it)) }
        // rhythm on RADIUS: count up-crossings of its midline (deadband), topology-agnostic contraction beats.
        var rmn = Long.MAX_VALUE; var rmx = Long.MIN_VALUE
        for (t in lo..ticks) if (alive[t]) { rmn = minOf(rmn, rad[t]); rmx = maxOf(rmx, rad[t]) }
        val radAmp = if (rmx == Long.MIN_VALUE) 0L else rmx - rmn
        val rmid = (rmn + rmx) / 2; val rdead = radAmp / 8
        var cycles = 0; var above = false
        if (rmx != Long.MIN_VALUE) for (t in lo..ticks) if (alive[t]) {
            if (rad[t] > rmid + rdead && !above) { cycles++; above = true }
            if (rad[t] < rmid - rdead) above = false
        }
        // A flatlined radius has near-zero amplitude; the deadband then shrinks to nothing and counts
        // micro-jitter as thousands of cycles. Require a real amplitude before believing any rhythm.
        if (radAmp < 50_000_000L) cycles = 0
        val period = if (cycles > 0) (ticks - lo) / cycles else 0
        var bmn = Int.MAX_VALUE; var bmx = Int.MIN_VALUE
        for (t in lo..ticks) if (alive[t]) { bmn = minOf(bmn, bio[t]); bmx = maxOf(bmx, bio[t]) }
        val bioStr = if (bmx == Int.MIN_VALUE) "dead" else "$bmn..$bmx"
        val startedZero = watch.sumOf { series.getValue(it)[0] } == 0
        val rose = watch.maxOf { series.getValue(it).maxOrNull() ?: 0 } > 100
        // radius sparkline across the whole run (50 samples, 0-9 by global radius range).
        val gmn = rad.minOrNull() ?: 0L; val gmx = rad.maxOrNull() ?: 0L; val span = (gmx - gmn).coerceAtLeast(1)
        val spark = (0 until 50).joinToString("") { i -> val t = i * ticks / 49; ('0' + (((rad[t] - gmn) * 9) / span).toInt()).toString() }
        return Res(startedZero && rose, watchStr, cycles, period, radAmp, bioStr, alive[ticks], spark)
    }
}
