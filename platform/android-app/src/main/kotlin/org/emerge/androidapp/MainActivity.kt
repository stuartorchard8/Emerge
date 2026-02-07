package org.emerge.androidapp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import org.emerge.demo.physics.AuthoritativeDemoFrame
import org.emerge.demo.physics.LaunchMode
import org.emerge.demo.physics.LaunchSettings
import org.emerge.demo.physics.PhysicsAuthoritativeHostController
import org.emerge.demo.physics.PhysicsAuthoritativeJoinController
import org.emerge.demo.physics.PhysicsDemoConfig
import org.emerge.demo.physics.createDefaultInitialState
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.camera.TorusOrthoCamera2D
import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx
import org.emerge.sim.core.space.Torus2D

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Single launch path: always start in-app with a launcher UI.
        // (We still read intent extras only to prefill the UI for convenience.)
        val defaultLaunchSettings = LaunchSettings()
        val initial = LaunchSettings(
            mode = when (intent.getStringExtra(EXTRA_MODE)) {
                MODE_HOST -> LaunchMode.HOST
                MODE_JOIN -> LaunchMode.JOIN
                MODE_LOOPBACK -> LaunchMode.LOCAL
                else -> defaultLaunchSettings.mode
            },
            hostIp = intent.getStringExtra(EXTRA_HOST_IP) ?: defaultLaunchSettings.hostIp,
            port = intent.getIntExtra(EXTRA_PORT, defaultLaunchSettings.port),
        )
        setContentView(LauncherView(activity = this, initial = initial))
    }

    companion object {
        const val EXTRA_MODE = "mode" // "host" | "join" | "loopback"
        const val EXTRA_HOST_IP = "hostIp"
        const val EXTRA_PORT = "port"
        const val EXTRA_RENDERER = "renderer" // "gl" | "canvas"

        const val MODE_HOST = "host"
        const val MODE_JOIN = "join"
        const val MODE_LOOPBACK = "loopback"
    }
}

private class LauncherView(
    private val activity: Activity,
    initial: LaunchSettings,
) : LinearLayout(activity) {
    private val modeSpinner: Spinner
    private val hostIpEdit: EditText
    private val portEdit: EditText

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(0x11, 0x11, 0x11))
        val pad = (16f * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)

        modeSpinner = Spinner(context)
        modeSpinner.adapter = themedSpinnerAdapter(LaunchMode.entries.map(LaunchMode::name).toList())

        hostIpEdit = EditText(context).apply {
            hint = "Host IP (for Join)"
            setText(initial.hostIp)
            setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
            setHintTextColor(Color.rgb(0x88, 0x88, 0x88))
        }

        portEdit = EditText(context).apply {
            hint = "Port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial.port.toString())
            setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
            setHintTextColor(Color.rgb(0x88, 0x88, 0x88))
        }

        val start = Button(context).apply {
            text = "Start"
            setOnClickListener { startSelected() }
        }

        val modeLabel = TextView(context).apply {
            text = "Mode"
            setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
        }

        // Ensure spinners are visible on dark background regardless of theme defaults.
        modeSpinner.setBackgroundColor(Color.rgb(0x22, 0x22, 0x22))

        addView(modeLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(modeSpinner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(hostIpEdit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(portEdit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(start, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Prefill selections
        modeSpinner.setSelection(
            when (initial.mode) {
                LaunchMode.LOCAL -> 0
                LaunchMode.HOST -> 1
                LaunchMode.JOIN -> 2
            },
        )
        syncEnabledFields()

        modeSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    syncEnabledFields()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                    syncEnabledFields()
                }
            }
    }

    private fun syncEnabledFields() {
        hostIpEdit.isEnabled = selectedMode() == LaunchMode.JOIN
    }

    private fun themedSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, items) {
            init {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(Color.rgb(0x11, 0x11, 0x11))
                return v
            }
        }

    private fun selectedMode(): LaunchMode =
        when (modeSpinner.selectedItemPosition) {
            1 -> LaunchMode.HOST
            2 -> LaunchMode.JOIN
            else -> LaunchMode.LOCAL
        }

    private fun startSelected() {
        val settings = LaunchSettings(
            mode = selectedMode(),
            hostIp = hostIpEdit.text?.toString()?.trim().orEmpty().ifBlank { "127.0.0.1" },
            port = portEdit.text?.toString()?.toIntOrNull() ?: 7777,
        )

        val content: View = TorusGlSurfaceView(activity, mode = settings.mode, hostIp = settings.hostIp, port = settings.port)
        activity.setContentView(content)
    }
}
