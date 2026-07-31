package org.emerge.outofspace

import android.app.Activity
import android.os.Bundle

/** Launches the app full-screen on its own GL surface. All the work is in [OutofspaceAndroidView]. */
class OutofspaceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(OutofspaceAndroidView(this))
    }
}
