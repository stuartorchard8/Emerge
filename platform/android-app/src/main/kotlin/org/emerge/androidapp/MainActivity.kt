package org.emerge.androidapp

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.graphics.Color
import org.emerge.demo.physics.LaunchMode
import org.emerge.demo.physics.LaunchSettings

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
    private val hostIpField: EditText
    private val portField: EditText

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(0x11, 0x11, 0x11))
        val pad = (16f * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)

        modeSpinner = Spinner(context)
        modeSpinner.adapter = themedSpinnerAdapter(LaunchMode.entries.map(LaunchMode::name).toList())

        hostIpField = EditText(context).apply {
            hint = "Host IP (for Join)"
            setText(initial.hostIp)
            setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
            setHintTextColor(Color.rgb(0x88, 0x88, 0x88))
        }

        portField = EditText(context).apply {
            hint = "Port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial.port.toString())
            setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
            setHintTextColor(Color.rgb(0x88, 0x88, 0x88))
        }

        val start = Button(context).apply {
            text = "Start"
            setOnClickListener {
                activity.setContentView(TorusGlSurfaceView(activity, readSettings()))
            }
        }

        val modeLabel = TextView(context).apply {
            text = "Mode"
            setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
        }

        // Ensure spinners are visible on dark background regardless of theme defaults.
        modeSpinner.setBackgroundColor(Color.rgb(0x22, 0x22, 0x22))

        addView(modeLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(modeSpinner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(hostIpField, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(portField, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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

    private fun syncEnabledFields() {
        hostIpField.isEnabled = selectedMode() == LaunchMode.JOIN
    }

    private fun selectedMode(): LaunchMode =
        when (modeSpinner.selectedItemPosition) {
            1 -> LaunchMode.HOST
            2 -> LaunchMode.JOIN
            else -> LaunchMode.LOCAL
        }

    private fun readSettings(): LaunchSettings {
        val port = portField.text.toString().trim().toIntOrNull() ?: 7777
        val hostIp = hostIpField.text.toString().trim().ifBlank { "127.0.0.1" }
        return LaunchSettings(
            mode = selectedMode(),
            hostIp = hostIp,
            port = port,
        )
    }
}
