package org.emerge.androidapp

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import org.emerge.demo.physics.GameMode
import org.emerge.demo.physics.LaunchMode
import org.emerge.demo.physics.LaunchSettings

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Single launch path: always start in-app with a launcher UI.
        // (We still read intent extras only to prefill the UI for convenience.)
        val defaultLaunchSettings = LaunchSettings()
        if (SKIP_LAUNCHER) {
            setContentView(TorusGlSurfaceView(activity = this, settings = defaultLaunchSettings))
            return
        }
        val initial = LaunchSettings(
            mode = when (intent.getStringExtra(EXTRA_MODE)) {
                MODE_HOST -> LaunchMode.HOST
                MODE_JOIN -> LaunchMode.JOIN
                MODE_LOOPBACK -> LaunchMode.LOCAL
                else -> defaultLaunchSettings.mode
            },
            gameMode = when (intent.getStringExtra(EXTRA_GAME_MODE)) {
                GAME_MODE_PVP -> GameMode.PVP
                GAME_MODE_CO_OP -> GameMode.CO_OP
                else -> defaultLaunchSettings.gameMode
            },
            hostIp = intent.getStringExtra(EXTRA_HOST_IP) ?: defaultLaunchSettings.hostIp,
            port = intent.getIntExtra(EXTRA_PORT, defaultLaunchSettings.port),
        )
        setContentView(LauncherView(activity = this, initial = initial))
    }

    companion object {
        const val EXTRA_MODE = "mode" // "host" | "join" | "loopback"
        const val EXTRA_GAME_MODE = "gameMode" // "pvp" | "coOp"
        const val EXTRA_HOST_IP = "hostIp"
        const val EXTRA_PORT = "port"

        const val MODE_HOST = "host"
        const val MODE_JOIN = "join"
        const val MODE_LOOPBACK = "loopback"
        const val GAME_MODE_PVP = "pvp"
        const val GAME_MODE_CO_OP = "coOp"

        const val SKIP_LAUNCHER = true
    }
}
