package org.emerge.desktop

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.CytoWorldConfig
import java.io.File

/**
 * Measures the **[CytoSimDriver] throttle**: does a target TPS actually deliver that TPS?
 *
 * The counterpart to [CytoBenchmarkKt] (which measures how fast a tick *can* run): this drives the real
 * driver thread over the speed ladder on a real save and reports achieved vs target. A throttle that
 * sleeps per-tick rather than against a carried deadline pays `parkNanos`' fixed ~60us overshoot every
 * tick and lands well under target (2048 -> ~1800) — that shows up here as a large negative error, while
 * the sim itself is nowhere near saturated (see the UNLIMITED rung for the actual ceiling).
 *
 * `--args="<savePath> [secondsPerRung]"`
 */
fun main(args: Array<String>) {
    val path = args.getOrNull(0) ?: "apps/cyto/desktop/cyto-small-save.bin"
    val secs = args.getOrNull(1)?.toDoubleOrNull() ?: 3.0

    // Apply the save's world geometry (the `.world` sidecar) before restoring, as CytoSaves.load does —
    // world size drives tick cost, so measuring without it would profile the wrong world.
    val world = File(path.removeSuffix(".bin") + ".world")
    if (world.exists()) {
        val p = world.readText().trim().split(Regex("\\s+"))
        CytoWorldConfig.applyFrom(p[0].toInt(), p[1].toLong(), p[2].toFloat())
        println("geometry: cellsPerAxis=${p[0]} orbit=${p[1]} day=${p[2]}")
    }
    val controller = CytoController()
    controller.restoreSnapshot(File(path).readBytes())
    println("loaded $path: ${controller.worldStats().cellCount} cells\n")

    val driver = CytoSimDriver(controller)
    driver.start()
    Thread.sleep(2000)                                 // warm the JIT at the default rung
    println(" target      achieved     error     cells")
    println(" -------------------------------------------")

    // Ladder: 64 (REALTIME) doubling up to MAX_TPS, then the UNLIMITED rung.
    while (true) {
        val target = driver.targetTps
        Thread.sleep((secs * 1000).toLong())           // let actualTps settle over several windows
        val achieved = driver.actualTps
        val cells = controller.worldStats().cellCount
        if (target == CytoSimDriver.UNLIMITED) {
            println(" %-11s %8.0f   (ceiling) %7d".format("unlimited", achieved, cells))
            break
        }
        val errPct = 100.0 * (achieved - target) / target
        println(" %-11d %8.0f   %+6.1f%%  %7d".format(target, achieved, errPct, cells))
        driver.faster()
    }
    driver.stop()
}
