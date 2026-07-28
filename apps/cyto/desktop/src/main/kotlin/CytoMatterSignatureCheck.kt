package org.emerge.desktop

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.CytoWorldConfig
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimState
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * **Does a colony leave a visible footprint in the matter field?**
 *
 * Scoping question for the render-stream client (`apps/cyto/MULTIPLAYER_PLAN.md` §4): when the player zooms
 * out, cells fade to alpha 0 and the phone is sent only the matter texture, on the premise that metabolism
 * writes a legible signature into the field. That premise is known to be weak in at least one case — an
 * autotroph at equilibrium neither draws down nor secretes much, so its flows are invisible
 * (`LIVING_WORLD_PLAN.md`). This measures it instead of assuming it.
 *
 * Method: tally the field's r/g/b channels exactly as `CytoRenderer.rasterizeMatter` does, stamp a mask of
 * the texels the colony occupies, and compare their density against the far-field background. Visibility is
 * judged on three terms, because no one of them is sufficient:
 *
 * 1. **Contrast**, in 8-bit display levels — what the wire format and the screen actually carry. (A z-score
 *    against background variance was the first attempt and is *wrong*: a pristine world has a perfectly
 *    uniform background, so sd = 0, and dividing by that zero reports "invisible" for what is in fact a
 *    maximally visible mark.)
 * 2. **Extent** — a lone cell shows a healthy per-texel delta while occupying one texel of 512², which no
 *    eye will find at full-torus zoom.
 * 3. **Saturation** — past [SATURATION_PCT] coverage there is no clean background left to compare against,
 *    and what the variance term calls "noise" is the colony's own diffused trails. The contrast test is
 *    suppressed there rather than allowed to report a false negative.
 *
 * Emits a two-panel PNG: the honest phone view (matter only) and the same field with cell **outlines** for
 * alignment — outlines, not discs, so the right panel verifies where the colony is without painting over
 * the very signal being judged. **Read the PNG**; the numbers rank checkpoints, the image decides.
 *
 * `--args="<savePath.bin | --scenario> [<ticks> | <interval>x<count>] [outPng]"`
 */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val source = args.getOrElse(0) { "--scenario" }
    val spec = args.getOrElse(1) { "1200" }
    val out = File(args.getOrElse(2) { "build/cyto-matter-signature.png" })

    // `<n>` measures once at tick n; `<n>x<k>` measures every n ticks, k times — one pass over the colony's
    // whole lifetime instead of one process per guess. A colony grows, peaks and can go extinct (the default
    // scenario is empty by 25k), so a single tick count is as likely to measure a dead world as a live one.
    val (interval, checkpoints) = if (spec.contains('x')) {
        val p = spec.split('x'); p[0].toInt() to p[1].toInt()
    } else spec.toInt() to 1

    val step: () -> Unit
    val snapshot: () -> SimState
    if (source == "--scenario") {
        println("source: default scenario")
        val sim = CytoSoaSim(CytoConfig(), createCytoInitialState())
        step = { sim.stepWorld() }; snapshot = { sim.state() }
    } else {
        // Apply the save's world geometry (the `.world` sidecar) before restoring, as CytoSaves.load does.
        val geom = File(source.removeSuffix(".bin") + ".world")
        if (geom.exists()) {
            val p = geom.readText().trim().split(Regex("\\s+"))
            CytoWorldConfig.applyFrom(p[0].toInt(), p[1].toLong(), p[2].toFloat())
            println("geometry: cellsPerAxis=${p[0]} orbit=${p[1]} day=${p[2]}")
        }
        val controller = CytoController()
        controller.restoreSnapshot(File(source).readBytes())
        println("source: $source")
        step = { controller.stepOnce() }; snapshot = { controller.publish(); controller.latestFrame().state }
    }

    var best: Measured? = null
    if (checkpoints > 1) {
        println()
        println("  tick    cells   delta(levels)  noise(levels)   extent(px)  cover%   verdict")
        println("  --------------------------------------------------------------------------------")
    }
    for (k in 1..checkpoints) {
        repeat(interval) { step() }
        val m = measure(snapshot(), (k * interval).toLong()) ?: continue
        if (checkpoints > 1) {
            println(
                "  ${m.tick.toString().padStart(6)}  ${m.cells.toString().padStart(6)}  " +
                    "${fmt(m.deltaLevels).padStart(13)}  ${fmt(m.noiseLevels).padStart(13)}  " +
                    "${fmt(m.screenPx).padStart(11)}  ${fmt(m.coveragePct).padStart(6)}   ${m.verdict()}",
            )
        }
        // Keep the most populous live checkpoint for the detailed report + PNG — the state the fade-out
        // question is actually about (a colony worth zooming out to look at).
        if (best == null || m.cells > best.cells) best = m
    }

    val m = best
    if (m == null) { println("FAIL: no checkpoint had any cells — nothing to measure"); return }
    println()
    println("detail at tick ${m.tick} (most populous checkpoint):")
    report(m, out)
}

/** One checkpoint's measurement, plus what the PNG needs to draw it. */
private class Measured(
    val tick: Long, val cells: Int, val fres: Int, val half: Float, val texel: Float, val ref: Double,
    val chR: IntArray, val chG: IntArray, val chB: IntArray,
    val colony: BooleanArray, val excluded: BooleanArray, val positions: List<FloatArray>,
    val deltaLevels: Double, val noiseLevels: Double, val screenPx: Double, val coveragePct: Double,
) {
    /**
     * Once the colony covers most of the field there is no clean background left to compare against: the
     * keepout leaves only far corners, and those corners are full of the colony's own diffused trails, which
     * the variance term then counts as "noise". The contrast test is meaningless there — and it is precisely
     * the case where visibility is least in doubt, so it must not be allowed to report a false negative.
     * Above this coverage, extent alone decides.
     */
    val saturated get() = coveragePct >= SATURATION_PCT

    fun visible() = screenPx >= MIN_VISIBLE_PX &&
        (saturated || (deltaLevels >= MIN_VISIBLE_LEVELS && deltaLevels >= 2 * noiseLevels))

    fun verdict() = when {
        screenPx < MIN_VISIBLE_PX -> "too small"
        saturated -> "VISIBLE (saturated)"
        visible() -> "VISIBLE"
        deltaLevels < MIN_VISIBLE_LEVELS -> "no contrast"
        else -> "low contrast"
    }
}

private fun measure(state: SimState, tick: Long): Measured? {
    val grid = state.components.getTable<CytoMatterGridComponent>().asMap()[GRID_SINGLETON]?.grid ?: return null

    val fres = grid.resolution
    val chR = IntArray(fres * fres); val chG = IntArray(fres * fres); val chB = IntArray(fres * fres)
    grid.tallyChannels(chR, chG, chB)

    val half = CytoLightField.HALF
    val texel = CytoMatterField.SPAN / fres
    val transforms = state.components.getTable<TransformComponent>()
    val cells = state.components.getTable<CytoCellComponent>().asMap()

    // Two masks. `colony` = texels the cells sit on (+ a small margin, since the footprint is where they
    // exchange). `excluded` = a wider dilation kept OUT of the background sample, so matter the colony has
    // already diffused into its surroundings doesn't contaminate the "background" it is measured against —
    // that would flatten the contrast and let a real signature read as invisible.
    val colony = BooleanArray(fres * fres)
    val excluded = BooleanArray(fres * fres)
    val positions = ArrayList<FloatArray>(cells.size)
    for ((id, cell) in cells) {
        val pos = transforms[id]?.pos ?: continue
        val lx = CytoUnits.toLogical(pos.x); val ly = CytoUnits.toLogical(pos.y)
        val r = cell.logicalRadius.toFloat()
        positions.add(floatArrayOf(lx, ly, r))
        stamp(colony, fres, half, texel, lx, ly, r + COLONY_MARGIN)
        stamp(excluded, fres, half, texel, lx, ly, r + BACKGROUND_KEEPOUT)
    }

    if (positions.isEmpty()) return null

    val ref = CytoSeed.MATTER_UNIFORM_LEVEL.toDouble() * 4.0
    var bestD = 0.0; var noiseAtBest = 0.0
    for (ch in listOf(chR, chG, chB, lum(chR, chG, chB))) {
        val (d, n) = stats(ch, colony, excluded, ref)
        if (d > bestD) { bestD = d; noiseAtBest = n }
    }
    var colonyTexels = 0
    for (c in colony) if (c) colonyTexels++
    val screenPx = sqrt(colonyTexels.toDouble()) * (PHONE_SHORT_EDGE_PX.toDouble() / fres)
    return Measured(
        tick, positions.size, fres, half, texel, ref, chR, chG, chB, colony, excluded, positions,
        bestD, noiseAtBest, screenPx, colonyTexels * 100.0 / (fres * fres),
    )
}

/** Full breakdown + PNG for the chosen checkpoint. */
private fun report(m: Measured, out: File) {
    val fres = m.fres; val ref = m.ref
    println("cells=${m.cells}  grid=${fres}x$fres  refDensity=$ref")
    println()
    // Judged in **8-bit display levels**, because that is what the wire format and the screen actually carry:
    // the texture quantises refDensity to 255. A z-score alone is the wrong criterion — a pristine world has
    // a perfectly uniform background (sd = 0), where any delta at all is maximally visible, yet z divides by
    // that zero and reports the opposite. So visibility needs both: the delta must clear the quantisation
    // step, AND it must stand above whatever noise the background does have.
    println("channel   bg mean     bg sd    colony mean    delta    delta(levels)  noise(levels)")
    println("--------------------------------------------------------------------------------------")

    for ((name, ch) in listOf("r" to m.chR, "g" to m.chG, "b" to m.chB, "lum" to lum(m.chR, m.chG, m.chB))) {
        printChannel(name, ch, m.colony, m.excluded, ref)
    }

    // Contrast is necessary but not sufficient: a lone cell shows a healthy per-texel delta while occupying
    // a single texel of 512², which no eye will find at full-torus zoom. Visibility needs angular extent too.
    var colonyTexels = 0
    for (c in m.colony) if (c) colonyTexels++
    val coveragePct = colonyTexels * 100.0 / (fres * fres)
    val bestDeltaLevels = m.deltaLevels
    val noiseAtBest = m.noiseLevels
    val screenPx = m.screenPx
    println()
    println(
        "extent: $colonyTexels texels (${fmt(coveragePct)}% of field) ≈ ${fmt(screenPx)}px across " +
            "on a ${PHONE_SHORT_EDGE_PX}px phone edge at full-torus zoom",
    )

    println()
    if (screenPx < MIN_VISIBLE_PX) {
        println(
            "NOTE: footprint is only ${fmt(screenPx)}px across — below the ${MIN_VISIBLE_PX}px findability " +
                "floor regardless of contrast. Fading cells out at this population would leave the player " +
                "an apparently empty world.",
        )
    }
    println(
        when {
            screenPx < MIN_VISIBLE_PX ->
                "VERDICT: NOT VISIBLE — the colony is too small on screen to find at this zoom, whatever " +
                    "its contrast. The fade-out premise does NOT hold here."
            m.saturated ->
                "VERDICT: VISIBLE (saturated) — the colony covers ${fmt(coveragePct)}% of the field, so the " +
                    "contrast test has no clean background left to use and is not meaningful here. At this " +
                    "coverage the footprint IS the field; the premise holds trivially."
            bestDeltaLevels >= MIN_VISIBLE_LEVELS && bestDeltaLevels >= 2 * noiseAtBest ->
                "VERDICT: VISIBLE — colony deviates by ${fmt(bestDeltaLevels)} display levels against " +
                    "${fmt(noiseAtBest)} levels of background noise. The fade-out premise holds here."
            bestDeltaLevels >= MIN_VISIBLE_LEVELS ->
                "VERDICT: MARGINAL — delta ${fmt(bestDeltaLevels)} levels is real but only " +
                    "${fmt(bestDeltaLevels / noiseAtBest.coerceAtLeast(1e-9))}x the background variation " +
                    "(${fmt(noiseAtBest)} levels). Check the PNG: at high coverage that variation is the " +
                    "colony's own trails, not noise, and the eye separates them fine."
            else ->
                "VERDICT: NOT VISIBLE — delta ${fmt(bestDeltaLevels)} display levels is below the " +
                    "$MIN_VISIBLE_LEVELS-level quantisation floor. The fade-out premise does NOT hold here."
        },
    )

    drawPanels(out, m.chR, m.chG, m.chB, fres, m.half, m.texel, ref, m.positions)
    println("wrote ${out.absolutePath}")
}

/** Colony texels = cell radius plus this much logical margin (the membrane exchange footprint). */
private const val COLONY_MARGIN = 1.5f

/** Background is sampled only beyond this logical distance from any cell, so diffused colony matter
 *  doesn't get counted as background and mask the very contrast being measured. */
private const val BACKGROUND_KEEPOUT = 10f

private fun lum(r: IntArray, g: IntArray, b: IntArray): IntArray =
    IntArray(r.size) { (r[it] + g[it] + b[it]) / 3 }

/** Stamp the disc of logical radius [rad] centred at ([lx],[ly]) into [mask], wrapping on the torus. */
private fun stamp(mask: BooleanArray, fres: Int, half: Float, texel: Float, lx: Float, ly: Float, rad: Float) {
    val rt = (rad / texel).toInt().coerceAtLeast(1)
    val cx = ((lx + half) / texel).toInt()
    val cy = ((ly + half) / texel).toInt()
    for (dy in -rt..rt) {
        for (dx in -rt..rt) {
            if (dx * dx + dy * dy > rt * rt) continue
            val ix = ((cx + dx) % fres + fres) % fres      // torus wrap
            val iy = ((cy + dy) % fres + fres) % fres
            mask[iy * fres + ix] = true
        }
    }
}

/** Smallest delta worth calling visible, in 8-bit display levels — below this the wire quantisation
 *  (and any dithering) swallows it regardless of how clean the background is. */
private const val MIN_VISIBLE_LEVELS = 2.0

/** Phone short-edge resolution the extent check reasons in, so "is it findable" is answered in real pixels. */
private const val PHONE_SHORT_EDGE_PX = 1080

/** Below this on-screen size the footprint is unfindable however high its contrast — the lone-cell case,
 *  where a 5-level delta occupies a single texel of the field. */
private const val MIN_VISIBLE_PX = 8.0

/** Field coverage above which the contrast test has no clean background left and extent alone decides. */
private const val SATURATION_PCT = 40.0

/** Background (outside [excluded]) mean/sd vs the mean inside [colony].
 *  Returns (|delta|, background sd), both in 8-bit display levels. */
private fun stats(ch: IntArray, colony: BooleanArray, excluded: BooleanArray, ref: Double): Pair<Double, Double> {
    val (bgMean, bgSd, coMean) = moments(ch, colony, excluded, ref) ?: return 0.0 to 0.0
    return kotlin.math.abs(coMean - bgMean) * 255.0 to bgSd * 255.0
}

/** (background mean, background sd, colony mean) as density fractions of [ref], or null if under-sampled. */
private fun moments(ch: IntArray, colony: BooleanArray, excluded: BooleanArray, ref: Double): Triple<Double, Double, Double>? {
    var bgN = 0L; var bgSum = 0.0; var bgSumSq = 0.0
    var coN = 0L; var coSum = 0.0
    for (i in ch.indices) {
        val v = ch[i] / ref
        if (colony[i]) { coN++; coSum += v }
        if (!excluded[i]) { bgN++; bgSum += v; bgSumSq += v * v }
    }
    if (bgN == 0L || coN == 0L) return null
    val bgMean = bgSum / bgN
    val bgVar = (bgSumSq / bgN) - bgMean * bgMean
    return Triple(bgMean, sqrt(if (bgVar < 0) 0.0 else bgVar), coSum / coN)
}

private fun printChannel(name: String, ch: IntArray, colony: BooleanArray, excluded: BooleanArray, ref: Double) {
    val m = moments(ch, colony, excluded, ref)
    if (m == null) { println("  $name: insufficient sample"); return }
    val (bgMean, bgSd, coMean) = m
    val delta = coMean - bgMean
    println(
        "  ${name.padEnd(6)}  ${fmt(bgMean).padStart(8)}  ${fmt(bgSd).padStart(8)}  ${fmt(coMean).padStart(11)}  " +
            "${fmt(delta).padStart(8)}  ${fmt(kotlin.math.abs(delta) * 255.0).padStart(12)}  " +
            "${fmt(bgSd * 255.0).padStart(13)}",
    )
}

private fun fmt(v: Double): String = ((v * 1000).roundToInt() / 1000.0).toString()

/** Left: matter only (what the faded-out phone would see). Right: same, with cell outlines for alignment. */
private fun drawPanels(
    out: File, chR: IntArray, chG: IntArray, chB: IntArray, fres: Int,
    half: Float, texel: Float, ref: Double, cells: List<FloatArray>,
) {
    val panel = 640
    val img = BufferedImage(panel * 2 + 12, panel, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = Color.BLACK; g.fillRect(0, 0, img.width, img.height)
    val span = 2f * half
    val pxPerLogical = panel / span

    for (p in 0..1) {
        val ox = p * (panel + 12)
        for (iy in 0 until fres) {
            for (ix in 0 until fres) {
                val i = iy * fres + ix
                g.color = Color(
                    (chR[i] / ref).coerceIn(0.0, 1.0).toFloat(),
                    (chG[i] / ref).coerceIn(0.0, 1.0).toFloat(),
                    (chB[i] / ref).coerceIn(0.0, 1.0).toFloat(),
                )
                val x0 = ox + ix * texel * pxPerLogical
                val y0 = iy * texel * pxPerLogical
                val w = (texel * pxPerLogical).toInt().coerceAtLeast(1)
                g.fillRect(x0.toInt(), y0.toInt(), w, w)
            }
        }
        if (p == 1) {
            g.color = Color(255, 255, 0)
            for ((lx, ly, r) in cells.map { Triple(it[0], it[1], it[2]) }) {
                val cxp = ox + (lx + half) * pxPerLogical
                val cyp = (ly + half) * pxPerLogical
                val rad = (r * pxPerLogical).coerceAtLeast(2f)
                g.drawOval((cxp - rad).toInt(), (cyp - rad).toInt(), (2 * rad).toInt(), (2 * rad).toInt())
            }
        }
        g.color = Color(40, 40, 40); g.font = Font("SansSerif", Font.BOLD, 13)
        g.drawString(if (p == 0) "matter only (the phone's zoomed-out view)" else "+ cell outlines (alignment)", ox + 8, 18)
    }
    g.dispose(); out.parentFile?.mkdirs(); ImageIO.write(img, "png", out)
}
