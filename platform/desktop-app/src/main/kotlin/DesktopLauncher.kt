package org.emerge.desktop

import org.emerge.demo.physics.GameMode
import org.emerge.demo.physics.LaunchMode
import org.emerge.demo.physics.LaunchSettings
import java.awt.Color
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import java.util.prefs.Preferences

class DesktopLauncher {
    private val modeBox = JComboBox(LaunchMode.entries.map(LaunchMode::name).toTypedArray())
    private val gameModeBox = JComboBox(GameMode.entries.map(GameMode::name).toTypedArray())
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
            row(1, "Game Mode", gameModeBox)
            row(2, "Host IP (Join)", hostIpField)
            row(3, "Port", portField)

            val start = JButton("Start").apply {
                addActionListener {
                    val settings = readSettings()
                    dispose()
                    DesktopGlSceneView.start(settings)
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
        val saved = loadSavedSettings()
        modeBox.selectedItem = saved.mode.name
        gameModeBox.selectedItem = saved.gameMode.name
        hostIpField.text = saved.hostIp
        portField.text = saved.port.toString()
        frame.isVisible = true
    }

    private fun syncEnabledFields() {
        val mode = selectedMode()
        hostIpField.isEnabled = mode == LaunchMode.JOIN || mode == LaunchMode.JOIN_IMPULSE || mode == LaunchMode.JOIN_THIN
    }

    private fun selectedMode(): LaunchMode =
        LaunchMode.entries.getOrElse(modeBox.selectedIndex) { LaunchMode.LOCAL }

    private fun selectedGameMode(): GameMode =
        GameMode.entries.getOrElse(gameModeBox.selectedIndex) { GameMode.PVP }

    private fun readSettings(): LaunchSettings {
        val port = portField.text.trim().toIntOrNull() ?: 7777
        val hostIp = hostIpField.text.trim().ifBlank { "127.0.0.1" }
        val settings = LaunchSettings(
            mode = selectedMode(),
            gameMode = selectedGameMode(),
            hostIp = hostIp,
            port = port,
        )
        saveSettings(settings)
        return settings
    }

    companion object {
        private val prefs: Preferences = Preferences.userNodeForPackage(DesktopLauncher::class.java)
        private const val KEY_MODE = "mode"
        private const val KEY_GAME_MODE = "gameMode"
        private const val KEY_HOST_IP = "hostIp"
        private const val KEY_PORT = "port"

        fun loadSavedSettings(): LaunchSettings {
            val defaults = LaunchSettings()
            return LaunchSettings(
                mode = runCatching { LaunchMode.valueOf(prefs.get(KEY_MODE, defaults.mode.name)) }.getOrDefault(defaults.mode),
                gameMode = runCatching { GameMode.valueOf(prefs.get(KEY_GAME_MODE, defaults.gameMode.name)) }.getOrDefault(defaults.gameMode),
                hostIp = prefs.get(KEY_HOST_IP, defaults.hostIp),
                port = prefs.getInt(KEY_PORT, defaults.port),
            )
        }

        private fun saveSettings(settings: LaunchSettings) {
            prefs.put(KEY_MODE, settings.mode.name)
            prefs.put(KEY_GAME_MODE, settings.gameMode.name)
            prefs.put(KEY_HOST_IP, settings.hostIp)
            prefs.putInt(KEY_PORT, settings.port)
            prefs.flush()
        }
    }
}
