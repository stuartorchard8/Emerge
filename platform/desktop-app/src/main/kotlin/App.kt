package org.emerge.desktop

import java.awt.*
import javax.swing.*
import org.emerge.demo.physics.*

fun main() {
    // Single launch path: always start with an in-app launcher UI.
    SwingUtilities.invokeLater { DesktopLauncher().show() }
}

private class DesktopLauncher {
    private val modeBox = JComboBox(LaunchMode.entries.map(LaunchMode::name).toTypedArray())
    private val hostIpField = JTextField("127.0.0.1", 16)
    private val portField = JTextField("7777", 6)

    private val frame = JFrame("Emerge - Launcher").apply {
        defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        contentPane = JPanel(GridBagLayout()).apply {
            background = Color(0x11, 0x11, 0x11)
            val c = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(8, 8, 8, 8)
                weightx = 1.0
            }

            fun row(y: Int, label: String, comp: Component) {
                c.gridy = y
                c.gridx = 0
                c.weightx = 0.0
                add(JLabel(label).apply { foreground = Color(0xEE, 0xEE, 0xEE) }, c)
                c.gridx = 1
                c.weightx = 1.0
                add(comp, c)
            }

            row(0, "Mode", modeBox)
            row(2, "Host IP (Join)", hostIpField)
            row(3, "Port", portField)

            val start = JButton("Start").apply {
                addActionListener {
                    val settings = readSettings()
                    dispose()
                    GlSceneView.start(settings)
                }
            }

            c.gridy = 4
            c.gridx = 0
            c.gridwidth = 2
            add(start, c)

            modeBox.addItemListener { syncEnabledFields() }
            syncEnabledFields()
        }

        pack()
        isLocationByPlatform = true
        isResizable = false
    }

    fun show() {
        val defaultLaunchSettings = LaunchSettings()
        modeBox.selectedItem = defaultLaunchSettings.mode.name
        hostIpField.text = defaultLaunchSettings.hostIp
        portField.text = defaultLaunchSettings.port.toString()
        frame.isVisible = true
    }

    private fun syncEnabledFields() {
        hostIpField.isEnabled = (selectedMode() == LaunchMode.JOIN)
    }

    private fun selectedMode(): LaunchMode =
        when (modeBox.selectedIndex) {
            1 -> LaunchMode.HOST
            2 -> LaunchMode.JOIN
            else -> LaunchMode.LOCAL
        }

    private fun readSettings(): LaunchSettings {
        val port = portField.text.trim().toIntOrNull() ?: 7777
        val hostIp = hostIpField.text.trim().ifBlank { "127.0.0.1" }
        return LaunchSettings(
            mode = selectedMode(),
            hostIp = hostIp,
            port = port,
        )
    }
}
