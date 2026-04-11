package org.emerge.androidapp

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import org.emerge.demo.physics.LaunchSettings
import org.emerge.demo.physics.PhysicsHeadlessHostController
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput

/**
 * Minimal Android view for running a headless host — no GL, just status text.
 */
internal class AndroidHeadlessHostView(
    activity: Activity,
    settings: LaunchSettings,
) : LinearLayout(activity) {
    private val statusText: TextView
    private val tickText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var simThread: HandlerThread? = null
    private var simHandler: Handler? = null
    private var controller: PhysicsHeadlessHostController? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(0x11, 0x11, 0x11))
        val pad = (24f * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)

        val title = TextView(context).apply {
            text = "Headless Host"
            setTextColor(Color.rgb(0xEE, 0xEE, 0xEE))
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        }
        statusText = TextView(context).apply {
            text = "starting..."
            setTextColor(Color.rgb(0x88, 0xCC, 0x88))
            textSize = 16f
        }
        tickText = TextView(context).apply {
            text = "tick: 0"
            setTextColor(Color.rgb(0x88, 0x88, 0x88))
            textSize = 14f
        }

        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (16f * resources.displayMetrics.density).toInt()
        })
        addView(tickText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8f * resources.displayMetrics.density).toInt()
        })

        startSimLoop(settings)
    }

    private fun startSimLoop(settings: LaunchSettings) {
        val thread = HandlerThread("headless-sim-loop")
        thread.start()
        val handler = Handler(thread.looper)
        simThread = thread
        simHandler = handler

        handler.post(object : Runnable {
            private var lastStatus = ""
            override fun run() {
                try {
                    val ctrl = controller ?: PhysicsHeadlessHostController(
                        port = settings.port,
                        cfg = PhysicsConfig(),
                        gameMode = settings.gameMode,
                    ).also { controller = it }

                    val frame = ctrl.tick(PhysicsInput.ZERO)
                    val status = ctrl.netStatus
                    if (status != lastStatus || (frame.tick % 60) == 0L) {
                        lastStatus = status
                        mainHandler.post {
                            statusText.text = status
                            tickText.text = "tick: ${frame.tick}"
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("HeadlessHost", "Sim loop failed", t)
                    mainHandler.post {
                        statusText.text = "failed: ${t.javaClass.simpleName}"
                    }
                } finally {
                    simHandler?.postDelayed(this, 16L)
                }
            }
        })
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        simHandler = null
        controller = null
        simThread?.quitSafely()
        simThread = null
    }

    companion object {
        private const val TAG = "HeadlessHostView"
    }
}
