package org.emerge.desktop

import javax.swing.*
import org.emerge.demo.physics.*

const val SKIP_LAUNCHER = false

fun main() {
    if (SKIP_LAUNCHER) {
        DesktopGlSceneView.start(DesktopLauncher.loadSavedSettings())
    } else {
        SwingUtilities.invokeLater { DesktopLauncher().show() }
    }
}
