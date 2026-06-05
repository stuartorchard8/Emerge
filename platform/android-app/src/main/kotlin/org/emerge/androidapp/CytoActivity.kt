package org.emerge.androidapp

import android.app.Activity
import android.os.Bundle

/** Launches the native Cyto demo full-screen on its own GL surface. */
class CytoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(CytoAndroidView(this))
    }
}
