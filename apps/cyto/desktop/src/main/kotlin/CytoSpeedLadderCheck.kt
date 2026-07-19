package org.emerge.desktop

import org.emerge.demo.cyto.CytoController

/**
 * Self-check for the **[CytoSimDriver] speed ladder + auto-drop** policy (the pure decision logic, no threads).
 * Matches the repo's main-based verification idiom (`benchCyto`, `checkCytoConservation`). Asserts the SLOW/
 * FAST bounds, the enable predicates, and the auto-drop rule against the spec's own worked examples. Prints
 * every case and exits non-zero on the first failure.
 *
 * `./gradlew :apps:cyto:desktop:checkSpeedLadder`
 */
private var failed = 0
private fun check(name: String, expected: Any?, actual: Any?) {
    val ok = expected == actual
    if (!ok) failed++
    println("  ${if (ok) "ok  " else "FAIL"}  $name : expected=$expected actual=$actual")
}

fun main() {
    println("speed ladder + auto-drop:")

    // Auto-drop (pure): target 256 realizing < 64 drops to 128; no drop until two rungs down; never raises.
    check("autoDrop 256 @ realized 63 -> 128", 128, CytoSimDriver.autoDropTarget(63.0, 256))
    check("autoDrop 256 @ realized 64 -> 256 (no drop)", 256, CytoSimDriver.autoDropTarget(64.0, 256))
    check("autoDrop 256 @ realized 200 -> 256 (no drop)", 256, CytoSimDriver.autoDropTarget(200.0, 256))
    check("autoDrop 512 @ realized 60 -> 128", 128, CytoSimDriver.autoDropTarget(60.0, 512))
    check("autoDrop 65536 @ realized 2000 -> 4096", 4096, CytoSimDriver.autoDropTarget(2000.0, 65536))
    check("autoDrop 8 @ realized 0.3 -> 4 (rung floor)", 4, CytoSimDriver.autoDropTarget(0.3, 8))
    check("autoDrop never raises (fast world)", 128, CytoSimDriver.autoDropTarget(9999.0, 128))

    // Ladder bounds via a real driver instance (no thread started — faster/slower are pure).
    val d = CytoSimDriver(CytoController())
    repeat(30) { d.slower() }
    check("slower floors at MIN_TPS=1", CytoSimDriver.MIN_TPS, d.targetTps)
    check("canSlower false at floor", false, d.canSlower())
    repeat(30) { d.faster() }
    check("faster caps at MAX_TPS=65536", CytoSimDriver.MAX_TPS, d.targetTps)
    check("canFaster false at ceiling", false, d.canFaster())
    // Paused counts as "not behind", so FAST is offered below the ceiling even with actualTps still 0.
    repeat(30) { d.slower() }; d.faster()   // back to 2
    d.setPaused(true)
    check("canFaster true while paused below ceiling", true, d.canFaster())
    check("canSlower true above floor", true, d.canSlower())

    println(if (failed == 0) "\nALL OK" else "\n$failed FAILED")
    if (failed != 0) kotlin.system.exitProcess(1)
}
