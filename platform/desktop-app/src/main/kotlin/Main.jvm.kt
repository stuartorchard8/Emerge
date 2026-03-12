package org.emerge.desktop

import javax.swing.*
import org.emerge.demo.physics.*

const val SKIP_LAUNCHER = true

fun main() {
    // Single launch path: always start with an in-app launcher UI.
    if (SKIP_LAUNCHER) {
        DesktopGlSceneView.start(LaunchSettings(mode = LaunchMode.HOST))
    } else {
        SwingUtilities.invokeLater { DesktopLauncher().show() }
    }
}
