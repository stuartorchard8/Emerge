package org.emerge.scavengers

import android.app.Activity
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import org.emerge.demo.scavengers.GameMode
import org.emerge.demo.scavengers.LaunchMode
import org.emerge.demo.scavengers.LaunchSettings

class AndroidLauncherView(
    private val activity: Activity,
    initial: LaunchSettings,
) : LinearLayout(activity) {
    private val modeSpinner: Spinner
    private val gameModeSpinner: Spinner
    private val hostIpField: EditText
    private val portField: EditText

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(0x11, 0x11, 0x11))
        val pad = (16f * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)

        modeSpinner = Spinner(context)
        modeSpinner.adapter = themedSpinnerAdapter(LaunchMode.entries.map(LaunchMode::name).toList())
        gameModeSpinner = Spinner(context)
        gameModeSpinner.adapter = themedSpinnerAdapter(GameMode.entries.map(GameMode::name).toList())

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
                val settings = readSettings()
                activity.setContentView((activity as MainActivity).createViewForMode(settings))
            }
        }

        val modeLabel = TextView(context).apply {
            text = "Mode"
            setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
        }
        val gameModeLabel = TextView(context).apply {
            text = "Game Mode"
            setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
        }

        // Ensure spinners are visible on dark background regardless of theme defaults.
        modeSpinner.setBackgroundColor(Color.rgb(0x22, 0x22, 0x22))
        gameModeSpinner.setBackgroundColor(Color.rgb(0x22, 0x22, 0x22))

        addView(modeLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(modeSpinner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(gameModeLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(gameModeSpinner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(hostIpField, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(portField, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(start, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Prefill selections
        modeSpinner.setSelection(
            LaunchMode.entries.indexOf(initial.mode).coerceAtLeast(0),
        )
        gameModeSpinner.setSelection(
            GameMode.entries.indexOf(initial.gameMode).coerceAtLeast(0),
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
        val mode = selectedMode()
        hostIpField.isEnabled = mode == LaunchMode.JOIN || mode == LaunchMode.JOIN_IMPULSE || mode == LaunchMode.JOIN_THIN
    }

    private fun selectedMode(): LaunchMode =
        LaunchMode.entries.getOrElse(modeSpinner.selectedItemPosition) { LaunchMode.LOCAL }

    private fun selectedGameMode(): GameMode =
        GameMode.entries.getOrElse(gameModeSpinner.selectedItemPosition) { GameMode.PVP }

    private fun readSettings(): LaunchSettings {
        val port = portField.text.toString().trim().toIntOrNull() ?: 7777
        val hostIp = hostIpField.text.toString().trim().ifBlank { "127.0.0.1" }
        val settings = LaunchSettings(
            mode = selectedMode(),
            gameMode = selectedGameMode(),
            hostIp = hostIp,
            port = port,
        )
        MainActivity.saveSettings(context, settings)
        return settings
    }
}