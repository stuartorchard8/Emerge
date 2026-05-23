package org.emerge.androidapp

import android.app.Activity
import android.content.Context
import android.os.Bundle
import org.emerge.demo.scavengers.GameMode
import org.emerge.demo.scavengers.LaunchMode
import org.emerge.demo.scavengers.LaunchSettings

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = loadSavedSettings(this)
        if (SKIP_LAUNCHER) {
            setContentView(createViewForMode(saved))
            return
        }
        val initial = LaunchSettings(
            mode = when (intent.getStringExtra(EXTRA_MODE)) {
                MODE_HOST -> LaunchMode.HOST
                MODE_JOIN -> LaunchMode.JOIN
                MODE_LOOPBACK -> LaunchMode.LOCAL
                else -> saved.mode
            },
            gameMode = when (intent.getStringExtra(EXTRA_GAME_MODE)) {
                GAME_MODE_PVP -> GameMode.PVP
                GAME_MODE_CO_OP -> GameMode.CO_OP
                else -> saved.gameMode
            },
            hostIp = intent.getStringExtra(EXTRA_HOST_IP) ?: saved.hostIp,
            port = intent.getIntExtra(EXTRA_PORT, saved.port),
        )
        setContentView(AndroidLauncherView(activity = this, initial = initial))
    }

    fun createViewForMode(settings: LaunchSettings): android.view.View =
        if (settings.mode == LaunchMode.HEADLESS_HOST)
            AndroidHeadlessHostView(activity = this, settings = settings)
        else
            AndroidTorusGlSurfaceView(activity = this, settings = settings)

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_GAME_MODE = "gameMode"
        const val EXTRA_HOST_IP = "hostIp"
        const val EXTRA_PORT = "port"

        const val MODE_HOST = "host"
        const val MODE_JOIN = "join"
        const val MODE_LOOPBACK = "loopback"
        const val GAME_MODE_PVP = "pvp"
        const val GAME_MODE_CO_OP = "coOp"

        const val SKIP_LAUNCHER = false

        private const val PREFS_NAME = "emerge_launch"

        fun loadSavedSettings(context: Context): LaunchSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val defaults = LaunchSettings()
            return LaunchSettings(
                mode = runCatching { LaunchMode.valueOf(prefs.getString("mode", null) ?: defaults.mode.name) }.getOrDefault(defaults.mode),
                gameMode = runCatching { GameMode.valueOf(prefs.getString("gameMode", null) ?: defaults.gameMode.name) }.getOrDefault(defaults.gameMode),
                hostIp = prefs.getString("hostIp", null) ?: defaults.hostIp,
                port = prefs.getInt("port", defaults.port),
            )
        }

        fun saveSettings(context: Context, settings: LaunchSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("mode", settings.mode.name)
                .putString("gameMode", settings.gameMode.name)
                .putString("hostIp", settings.hostIp)
                .putInt("port", settings.port)
                .apply()
        }
    }
}
