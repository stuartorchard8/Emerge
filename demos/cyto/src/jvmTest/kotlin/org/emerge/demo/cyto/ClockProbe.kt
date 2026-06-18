package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterGrid
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoSeed
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
        // Validation set: baseline 7-gene vs the simplified 6-gene 2-ring at three period cutoffs.
        variants["baseline 3-ring(7)"] = baseline
        variants["2-ring cut=120(6)"] = ring2(120)
        variants["2-ring cut=220(6)"] = ring2(220)
        variants["2-ring cut=350(6)"] = ring2(350)
        runVariants(variants, ticks)
    }

    private fun runVariants(variants: LinkedHashMap<String, List<String>>, ticks: Int) {
        val sb = StringBuilder()
        sb.appendLine("=== clock probe ($ticks ticks, single lit cell, mutation OFF) ===")
        sb.appendLine("self-start = clock chems start 0 and rise > 100. cycles = bb up-crossings. works = self-start & cycles>=2 & contracts & alive.\n")
        sb.appendLine("variant\tgenes\tselfStart\tbb\tba\tbc\tcycles\tperiod\tradΔ\tbiomass\talive\tWORKS\tbb sparkline (whole run)")
        for ((name, genes) in variants) {
            val r = simulate(GeneCodec.parse(genes.joinToString("\n")), ticks)
            // contraction ⇒ radΔ well above the no-contract baseline (~2.9e8 in this scenario); use 4e8.
            val works = r.selfStart && r.cycles >= 2 && r.radAmp > 400_000_000L && r.alive
            sb.appendLine("$name\t${genes.size}\t${r.selfStart}\t${r.bb}\t${r.ba}\t${r.bc}\t${r.cycles}\t${r.period}\t${r.radAmp}\t${r.bio}\t${r.alive}\t${if (works) "yes" else "NO"}\t${r.spark}")
        }
        java.io.File("/tmp/clockprobe.txt").writeText(sb.toString())
        println(sb)
    }

    private class Res(
        val selfStart: Boolean, val bb: String, val ba: String, val bc: String,
        val cycles: Int, val period: Int, val radAmp: Long, val bio: String, val alive: Boolean, val spark: String,
    )

    private fun simulate(genome: List<Gene>, ticks: Int): Res {
        val initial = run {
            val b = SimBuilder(SimState(randomSeed = 0x9E3779B97F4A7C15uL.toLong()))
            b.spawnCell(
                pos = CytoUnits.coord2(0f, 0f), vel = Coord2.zero, type = CellType.Collector,
                cytoplasm = mapOf("a" to 2000, "b" to 2000, "c" to 500, "aa" to 4000),  // fuel + monomers; NO clock chems
                biomass = CytoSeed.STARTER_BIOMASS, logicalRadius = MIN_RADIUS, genome = genome,
            )
            val grid = CytoMatterGrid.empty()
            for (idx in 0 until CytoMatterGrid.RES * CytoMatterGrid.RES) {
                grid.deposit(idx, "a", 4000); grid.deposit(idx, "b", 4000); grid.deposit(idx, "c", 2000)
            }
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(grid) }
            b.build()
        }
        val soa = CytoSoaReducer(CytoConfig(mutationRateDenom = 0))
        var w = CytoWorld.fromSimState(initial)
        val bb = IntArray(ticks + 1); val ba = IntArray(ticks + 1); val bc = IntArray(ticks + 1)
        val rad = LongArray(ticks + 1); val bio = IntArray(ticks + 1); val alive = BooleanArray(ticks + 1)
        fun sample(t: Int, s: SimState) {
            val cells = s.components.getTable<CytoCellComponent>().asMap().values
            val c = cells.firstOrNull() ?: return
            alive[t] = true
            bb[t] = c.cytoplasm["bb"] ?: 0; ba[t] = c.cytoplasm["ba"] ?: 0; bc[t] = c.cytoplasm["bc"] ?: 0
            rad[t] = c.logicalRadius.raw; bio[t] = totalBiomassBonds(c.biomass)
        }
        sample(0, initial)
        for (t in 1..ticks) { w = soa.tick(w, CytoInput.EMPTY); sample(t, w.toSimState()) }

        val lo = ticks / 4   // steady-state window (skip startup)
        fun range(a: IntArray): Triple<Int, Int, String> {
            var mn = Int.MAX_VALUE; var mx = Int.MIN_VALUE
            for (t in lo..ticks) if (alive[t]) { mn = minOf(mn, a[t]); mx = maxOf(mx, a[t]) }
            return if (mx == Int.MIN_VALUE) Triple(0, 0, "dead") else Triple(mn, mx, "$mn..$mx")
        }
        val (bbmn, bbmx, bbStr) = range(bb); val baStr = range(ba).third; val bcStr = range(bc).third
        // cycles = bb up-crossings of its midline (the clock period), with a deadband to ignore jitter.
        val mid = (bbmn + bbmx) / 2; val dead = (bbmx - bbmn) / 8
        var cycles = 0; var above = false
        for (t in lo..ticks) if (alive[t]) {
            if (bb[t] > mid + dead && !above) { cycles++; above = true }
            if (bb[t] < mid - dead) above = false
        }
        val period = if (cycles > 0) (ticks - lo) / cycles else 0
        var rmn = Long.MAX_VALUE; var rmx = Long.MIN_VALUE
        for (t in lo..ticks) if (alive[t]) { rmn = minOf(rmn, rad[t]); rmx = maxOf(rmx, rad[t]) }
        val radAmp = if (rmx == Long.MIN_VALUE) 0L else rmx - rmn
        var bmn = Int.MAX_VALUE; var bmx = Int.MIN_VALUE
        for (t in lo..ticks) if (alive[t]) { bmn = minOf(bmn, bio[t]); bmx = maxOf(bmx, bio[t]) }
        val bioStr = if (bmx == Int.MIN_VALUE) "dead" else "$bmn..$bmx"
        val startedZero = bb[0] + ba[0] + bc[0] == 0
        val rose = maxOf(bb.maxOrNull() ?: 0, ba.maxOrNull() ?: 0, bc.maxOrNull() ?: 0) > 100
        // bb sparkline across the whole run (50 samples, 0-9 by global bb range) — eyeball the oscillation.
        val gmn = bb.minOrNull() ?: 0; val gmx = bb.maxOrNull() ?: 0; val span = (gmx - gmn).coerceAtLeast(1)
        val spark = (0 until 50).joinToString("") { i -> val t = i * ticks / 49; ('0' + (((bb[t] - gmn) * 9) / span)).toString() }
        return Res(startedZero && rose, bbStr, baStr, bcStr, cycles, period, radAmp, bioStr, alive[ticks], spark)
    }
}
