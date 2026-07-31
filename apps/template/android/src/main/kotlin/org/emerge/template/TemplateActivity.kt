package org.emerge.template

import android.app.Activity
import android.os.Bundle

/** Launches the app full-screen on its own GL surface. All the work is in [TemplateAndroidView]. */
class TemplateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TemplateAndroidView(this))
    }
}
