package org.emerge.fluidlab

import android.app.Activity
import android.os.Bundle

/** Launches the app full-screen on its own GL surface. All the work is in [FluidlabAndroidView]. */
class FluidlabActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FluidlabAndroidView(this))
    }
}
