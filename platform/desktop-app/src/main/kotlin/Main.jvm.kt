package org.emerge.desktop

import javax.swing.*
import org.emerge.demo.physics.*

const val SKIP_LAUNCHER = false

fun main() {
    val emergeMode = System.getProperty("emerge.mode")
    if (emergeMode != null) {
        val settings = when (emergeMode) {
            "headless-host" -> LaunchSettings(mode = LaunchMode.HEADLESS_HOST, gameMode = GameMode.CO_OP, port = 7777)
            "join-local" -> LaunchSettings(mode = LaunchMode.JOIN, gameMode = GameMode.CO_OP, hostIp = "127.0.0.1", port = 7777)
            "join-tunnel" -> LaunchSettings(mode = LaunchMode.JOIN, gameMode = GameMode.CO_OP, hostIp = "others-boats.gl.at.ply.gg", port = 63565)
            else -> error("Unknown emerge.mode: $emergeMode")
        }
        println("[main] emerge.mode=$emergeMode → $settings")
        DesktopGlSceneView.start(settings)
        return
    }
    if (SKIP_LAUNCHER) {
        val settings = DesktopLauncher.loadSavedSettings()
        println("[main] Loaded settings: $settings")
        DesktopGlSceneView.start(settings)
    } else {
        SwingUtilities.invokeLater { DesktopLauncher().show() }
    }
}
